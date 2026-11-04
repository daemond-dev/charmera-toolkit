"""card-check / card-fix against image files only. No device is ever opened."""
import os
import shutil
import struct
import subprocess
import uuid
from pathlib import Path

import pytest

from charmera_toolkit import card
from charmera_toolkit import cli

MIB = 1024 * 1024
S = card.SECTOR
IMAGE_ARGS = {"yes": True, "i_know_what_i_am_doing": False}


def sparse_image(path: Path, size: int) -> Path:
    with open(path, "wb") as f:
        f.truncate(size)
    return path


def exfat_boot_region(size: int, flags: int) -> bytes:
    """Sectors 0..11 of a hand-made exFAT volume: boot sector, 8 extended boot sectors, OEM
    parameters, a reserved sector and the checksum sector."""
    boot = bytearray(S)
    boot[0:3] = b"\xEB\x76\x90"
    boot[3:11] = b"EXFAT   "
    volume_sectors = size // S - 2048
    struct.pack_into(
        "<QQIIIIIIBBHBBBBB",
        boot,
        64,
        2048,  # PartitionOffset
        volume_sectors,  # VolumeLength
        128,  # FatOffset
        64,  # FatLength
        256,  # ClusterHeapOffset
        (volume_sectors - 256) // 8,  # ClusterCount
        4,  # FirstClusterOfRootDirectory
        0xC0FFEE11,  # VolumeSerialNumber
        0,  # revision minor
        1,  # revision major
        flags,  # VolumeFlags
        9,  # BytesPerSectorShift
        3,  # SectorsPerClusterShift
        1,  # NumberOfFats
        0x80,  # DriveSelect
        7,  # PercentInUse
    )
    boot[510:512] = b"\x55\xAA"
    ext = bytearray(S)
    ext[508:512] = b"\x00\x00\x55\xAA"
    region = bytes(boot) + bytes(ext) * 8 + bytes(S) * 2
    checksum = card.exfat_boot_checksum(region)
    return region + struct.pack("<I", checksum) * (S // 4)


def make_exfat_image(path: Path, size: int = 32 * MIB, flags: int = card.EXFAT_FLAG_DIRTY) -> Path:
    sparse_image(path, size)
    region = exfat_boot_region(size, flags)
    with open(path, "r+b") as fh:
        card.write_at(fh, 0, card.build_mbr(2048, size // S - 2048, ptype=0x07, disk_signature=0xABCD1234))
        card.write_at(fh, 2048 * S, region)  # main boot region, sectors 0..11
        card.write_at(fh, (2048 + 12) * S, region)  # backup boot region, sectors 12..23
    return path


def check(path: Path) -> card.CheckResult:
    with open(path, "rb") as fh:
        return card.check_disk(fh, os.path.getsize(path))


def corrupt_exfat_bps_shift(path: Path, value: int = 0xFF) -> None:
    """Make the main exFAT boot sector unparseable: BytesPerSectorShift (byte 108) out of range."""
    with open(path, "r+b") as fh:
        boot = bytearray(card.read_at(fh, 2048 * S, S))
        boot[108] = value
        card.write_at(fh, 2048 * S, bytes(boot))


def make_gpt_image(path: Path, size: int = 64 * MIB, entries_lba: int = 2, count: int = 128, entry_size: int = 128, with_entries: bool = True) -> Path:
    """A protective MBR, a primary GPT header with the given entry-array fields and, optionally, one
    'Photo' basic-data entry at LBA 2."""
    sparse_image(path, size)
    with open(path, "r+b") as fh:
        card.write_at(fh, 0, card.build_mbr(1, size // S - 1, ptype=0xEE))
        header = bytearray(S)
        header[0:8] = b"EFI PART"
        struct.pack_into("<QII", header, 72, entries_lba, count, entry_size)
        card.write_at(fh, S, bytes(header))
        if with_entries:
            entry = bytearray(128)
            entry[0:16] = uuid.UUID("ebd0a0a2-b9e5-4433-87c0-68b6b72699c7").bytes_le
            struct.pack_into("<QQ", entry, 32, 2048, size // S - 34)
            entry[56 : 56 + 10] = "Photo".encode("utf-16-le")
            card.write_at(fh, 2 * S, bytes(entry) + bytes(128 * 127))
    return path


def fake_device(monkeypatch, img: Path, **fields):
    """Make resolve_target hand back `img` as if it were a real disk, with the given Target fields."""

    def fake(target, image):
        t = card.Target(
            given=str(img),
            path=str(img),
            size=os.path.getsize(img),
            is_image=False,
            description=card.describe_device("block device sdz", fields.get("removable"), fields.get("bus", ""), "Fake Disk"),
        )
        for k, v in fields.items():
            setattr(t, k, v)
        return t

    monkeypatch.setattr(card, "resolve_target", fake)


# ---------------------------------------------------------------------------------------------
# exFAT dirty bit


def test_exfat_dirty_bit_is_parsed_reported_and_cleared(tmp_path):
    img = make_exfat_image(tmp_path / "exfat.img")
    before = check(img)
    assert before.boot is not None and before.boot.kind == "exfat"
    x = before.boot.exfat
    assert x.dirty and not x.media_failure and x.volume_flags == 0x0002
    assert x.bytes_per_sector == 512 and x.serial == 0xC0FFEE11 and x.percent_in_use == 7
    assert before.exfat_checksum_ok is True
    assert before.exit_code == 1
    assert any("VolumeDirty" in p for p in before.problems)
    assert before.platforms["Android"].startswith("refuses")
    assert before.platforms["iPhone"].startswith("mounts")
    assert before.fixes[0] == "--clear-dirty"
    report = card.format_report(before, "--image exfat.img")
    assert "card-fix --image exfat.img --clear-dirty" in report
    assert "card-fix --image exfat.img --format-fat32" in report

    with open(img, "r+b") as fh:
        checksum_before = card.read_at(fh, (2048 + 11) * S, S)
        res = card.clear_exfat_dirty_bit(fh, 2048 * S)
        assert (res.old_flags, res.new_flags) == (0x0002, 0x0000)
        assert res.sectors_written == [0, 12]
        main = card.parse_exfat_boot_sector(card.read_at(fh, 2048 * S, S))
        backup = card.parse_exfat_boot_sector(card.read_at(fh, (2048 + 12) * S, S))
        assert not main.dirty and not backup.dirty
        # Only the two flag bytes changed.
        region = card.read_at(fh, 2048 * S, 11 * S)
        assert card.exfat_boot_checksum(region) == struct.unpack_from("<I", checksum_before)[0]
        assert card.read_at(fh, (2048 + 11) * S, S) == checksum_before

    after = check(img)
    assert after.exit_code == 0
    assert after.exfat_checksum_ok is True
    assert after.platforms["Android"].startswith("mounts")
    assert "--format-fat32" in after.fixes  # still recommended as the durable fix


def test_exfat_other_flag_bits_survive_the_clear(tmp_path):
    img = make_exfat_image(tmp_path / "exfat.img", flags=card.EXFAT_FLAG_DIRTY | card.EXFAT_FLAG_ACTIVE_FAT | card.EXFAT_FLAG_MEDIA_FAILURE)
    with open(img, "r+b") as fh:
        res = card.clear_exfat_dirty_bit(fh, 2048 * S)
    assert res.new_flags == card.EXFAT_FLAG_ACTIVE_FAT | card.EXFAT_FLAG_MEDIA_FAILURE
    after = check(img)
    assert any("MediaFailure" in p for p in after.problems)


def test_exfat_boot_checksum_ignores_flags_and_percent_in_use():
    region = exfat_boot_region(32 * MIB, 0)[: 11 * S]
    base = card.exfat_boot_checksum(region)
    altered = bytearray(region)
    altered[106] = 0xFF
    altered[107] = 0xFF
    altered[112] = 0x42
    assert card.exfat_boot_checksum(bytes(altered)) == base
    altered[108] ^= 1
    assert card.exfat_boot_checksum(bytes(altered)) != base


def test_exfat_boot_checksum_known_answers():
    # The exFAT rule: cs = (cs rotated right by one bit, 32-bit) + byte, skipping bytes 106, 107 and 112.
    # Eleven 512-byte sectors of zeros except byte 0 = 1: the first step gives 1, then each of the remaining
    # 5632 - 3 - 1 = 5628 bytes rotates right once and adds zero. 5628 mod 32 = 28, so the bit comes to rest
    # at bit 32 - 28 = 4.
    region = bytearray(11 * S)
    region[0] = 1
    assert card.exfat_boot_checksum(bytes(region)) == 0x10
    # Byte 1 = 1 instead: byte 0 contributes nothing, then 5627 rotations; 5627 mod 32 = 27, so bit 5. The
    # excluded bytes hold junk to show they really are skipped.
    region = bytearray(11 * S)
    region[1] = 1
    region[106] = region[107] = region[112] = 0x55
    assert card.exfat_boot_checksum(bytes(region)) == 0x20
    # Short vectors with a carry out of bit 0: 3, then (bit 0 -> bit 31) + (3 >> 1) + 0, then once more + 5.
    assert card.exfat_boot_checksum(b"\x03") == 3
    assert card.exfat_boot_checksum(b"\x03\x00") == 0x80000001
    assert card.exfat_boot_checksum(b"\x03\x00\x05") == 0xC0000005


def test_corrupt_exfat_boot_sector_is_a_problem_not_a_crash(tmp_path, capsys):
    img = make_exfat_image(tmp_path / "card.img", 64 * MIB)
    corrupt_exfat_bps_shift(img)
    r = check(img)
    assert r.boot.kind == "exfat-corrupt" and r.boot.exfat is None
    assert r.exit_code == 1 and r.fixes == ["--format-fat32"]
    assert "exFAT boot sector is corrupt: exFAT BytesPerSectorShift 255 is out of range 9..12" in r.problems
    assert all(v == "unknown" for v in r.platforms.values())
    assert run_cli("card-check", "--image", str(img)) == 1
    assert "card-fix --image" in capsys.readouterr().out
    # The byte-patching fixes decline and write nothing; the reformat still works.
    assert run_cli("card-fix", "--image", str(img), "--clear-dirty", "--fix-mbr-type", "--yes") == 1
    out = capsys.readouterr().out
    assert "--clear-dirty: the exFAT boot sector is corrupt" in out and "Nothing to write" in out
    assert check(img).boot.kind == "exfat-corrupt"
    assert run_cli("card-fix", "--image", str(img), "--format-fat32", "--yes") == 0
    assert check(img).boot.kind == "fat32"


def test_fixers_refuse_when_the_volume_changed_after_the_plan(tmp_path, monkeypatch, capsys):
    img = make_exfat_image(tmp_path / "card.img", 64 * MIB)
    corrupt_exfat_bps_shift(img)
    with open(img, "r+b") as fh:
        with pytest.raises(card.CardError, match="the volume changed since the plan was printed; nothing written"):
            card.clear_exfat_dirty_bit(fh, 2048 * S)
    # Through the command: fine when the plan is printed, corrupt by the time the confirmation comes back.
    img2 = make_exfat_image(tmp_path / "card2.img", 64 * MIB)

    def confirm_and_corrupt(args, target, expected):
        corrupt_exfat_bps_shift(img2)
        return True

    monkeypatch.setattr(card, "_confirm", confirm_and_corrupt)
    assert run_cli("card-fix", "--image", str(img2), "--clear-dirty") == 2
    assert "error: the volume changed since the plan was printed; nothing written" in capsys.readouterr().out
    with open(img2, "rb") as fh:
        boot = card.read_at(fh, 2048 * S, S)
    assert struct.unpack_from("<H", boot, 106)[0] == card.EXFAT_FLAG_DIRTY  # the flags were not touched


# ---------------------------------------------------------------------------------------------
# MBR type


def test_mbr_type_detection_and_fix(tmp_path):
    img = sparse_image(tmp_path / "fat.img", 64 * MIB)
    with open(img, "r+b") as fh:
        card.format_fat32(fh, 64 * MIB, serial=1)
        assert card.set_partition_type(fh, 1, 0x07) == (0x0C, 0x07)
    broken = check(img)
    assert broken.partition.type == 0x07
    assert broken.exit_code == 1
    assert broken.platforms["iPhone"].startswith("refuses")
    assert broken.platforms["Android"] == "mounts"
    assert broken.fixes == ["--fix-mbr-type"]
    with open(img, "r+b") as fh:
        assert card.fix_mbr_type_for_fat32(fh) == (0x07, 0x0C)
    fixed = check(img)
    assert fixed.partition.type == 0x0C and fixed.exit_code == 0
    assert all(v == "mounts" for v in fixed.platforms.values())


def test_fix_mbr_type_refuses_when_not_fat32(tmp_path):
    img = make_exfat_image(tmp_path / "exfat.img")
    with open(img, "r+b") as fh:
        with pytest.raises(card.CardError):
            card.fix_mbr_type_for_fat32(fh)
        assert card.parse_mbr(card.read_at(fh, 0, S)).first.type == 0x07


def test_fix_mbr_type_on_a_superfloppy_names_the_missing_table(tmp_path, capsys):
    flat = sparse_image(tmp_path / "flat.img", 64 * MIB)
    with open(flat, "r+b") as fh:
        card.write_at(fh, 0, card.build_fat32_boot_sector(card.plan_fat32(64 * MIB)))
    assert run_cli("card-fix", "--image", str(flat), "--fix-mbr-type", "--yes") == 1
    out = capsys.readouterr().out
    assert "--fix-mbr-type: no MBR partition table (superfloppy or GPT); there is no type byte to set, use --format-fat32" in out
    assert "not FAT32" not in out
    with open(flat, "rb") as fh:
        assert card.identify_boot_sector(card.read_at(fh, 0, S)) == "fat32"  # untouched
    # A GPT has no type byte either.
    gpt = make_gpt_image(tmp_path / "gpt.img")
    assert run_cli("card-fix", "--image", str(gpt), "--fix-mbr-type", "--yes") == 1
    assert "no MBR partition table (superfloppy or GPT)" in capsys.readouterr().out
    # The message for a wrong filesystem behind a real MBR is unchanged.
    img = make_exfat_image(tmp_path / "exfat.img", 64 * MIB)
    assert run_cli("card-fix", "--image", str(img), "--fix-mbr-type", "--yes") == 1
    assert "the first partition holds exFAT, not FAT32" in capsys.readouterr().out


def test_parse_mbr_and_chs():
    mbr = card.parse_mbr(card.build_mbr(2048, 100000, disk_signature=0x11223344))
    assert mbr.signature_ok and mbr.disk_signature == 0x11223344 and not mbr.gpt_protective
    p = mbr.first
    assert (p.index, p.type, p.start_lba, p.sectors) == (1, 0x0C, 2048, 100000)
    assert p.chs_start == (0x20, 0x21, 0x00)  # LBA 2048 with 255 heads and 63 sectors per track
    assert card.lba_to_chs(0) == (0, 1, 0)
    assert card.lba_to_chs(1024 * 255 * 63) == (0xFE, 0xFF, 0xFF)
    assert card.parse_mbr(card.build_mbr(2048, 1 << 30)).first.chs_end == (0xFE, 0xFF, 0xFF)
    assert all(mbr.partitions[i].empty for i in (1, 2, 3))


# ---------------------------------------------------------------------------------------------
# FAT32 formatting


def fsck_fat():
    return shutil.which("fsck.fat") or shutil.which("dosfsck")


@pytest.mark.parametrize("size_mib", [64, 300])
def test_format_fat32_and_read_it_back(tmp_path, size_mib):
    size = size_mib * MIB
    img = sparse_image(tmp_path / f"card{size_mib}.img", size)
    log = []
    with open(img, "r+b") as fh:
        plan = card.format_fat32(fh, size, label="charmera", serial=0xDEADBEEF, log=log.append)
    assert log and plan.label == "CHARMERA"

    r = check(img)
    assert r.exit_code == 0, r.problems
    assert not r.notes, r.notes
    mbr = r.mbr
    assert mbr.signature_ok and not mbr.gpt_protective
    p = mbr.first
    assert p.type == 0x0C and p.start_lba == 2048 and p.status == 0
    assert p.sectors == size // S - 2048
    assert p.chs_start == (0x20, 0x21, 0x00)

    f = r.boot.fat
    assert r.boot.kind == "fat32"
    assert f.bytes_per_sector == 512 and f.reserved_sectors == 32 and f.num_fats == 2
    assert f.total_sectors == p.sectors and f.hidden_sectors == 2048
    assert f.root_cluster == 2 and f.fsinfo_sector == 1 and f.backup_boot_sector == 6
    assert f.sectors_per_track == 63 and f.heads == 255 and f.drive_number == 0x80 and f.ext_boot_sig == 0x29
    assert f.serial == 0xDEADBEEF and f.label == "CHARMERA   " and f.fs_type == "FAT32   " and f.oem_name == "MSWIN4.1"
    assert f.media == 0xF8 and f.root_entries == 0
    assert f.cluster_count >= card.FAT32_MIN_CLUSTERS
    assert f.cluster_count == plan.cluster_count and f.fat_size == plan.fat_sectors
    assert f.sectors_per_cluster == plan.sectors_per_cluster
    if size_mib == 300:
        assert f.sectors_per_cluster == 8  # 4 KiB, the usual size for volumes up to 8 GiB
    else:
        assert f.sectors_per_cluster == 1  # shrunk so the volume still has 65525 clusters
    # The FAT really is big enough for every cluster.
    assert (f.cluster_count + 2) * 4 <= f.fat_size * 512

    assert r.fat_entries == [0x0FFFFFF8, 0x0FFFFFFF, 0x0FFFFFFF]
    assert r.backup_matches is True
    assert r.fsinfo.signatures_ok
    assert (r.fsinfo.lead, r.fsinfo.struct_sig, r.fsinfo.trail) == (0x41615252, 0x61417272, 0xAA550000)
    assert r.fsinfo.free_count == f.cluster_count - 1 and r.fsinfo.next_free == 3

    base = 2048 * S
    with open(img, "rb") as fh:
        assert card.read_at(fh, base, S) == card.read_at(fh, base + 6 * S, S)
        assert card.read_at(fh, base + S, S) == card.read_at(fh, base + 7 * S, S)
        assert card.read_at(fh, base + 2 * S, S)[510:] == b"\x55\xAA"
        assert card.read_fat_entries(fh, base, f, 3, fat_index=1) == [0x0FFFFFF8, 0x0FFFFFFF, 0x0FFFFFFF]
        # the rest of both FATs is zero
        for i in range(2):
            fat_off = base + (f.reserved_sectors + i * f.fat_size) * S
            assert card.read_at(fh, fat_off + 12, S - 12) == bytes(S - 12)
            assert card.read_at(fh, fat_off + S, (f.fat_size - 1) * S) == bytes((f.fat_size - 1) * S)
        root = card.read_at(fh, base + f.data_start_sector * S, f.sectors_per_cluster * S)
    assert root[0:11] == b"CHARMERA   " and root[11] == 0x08
    assert root[32:] == bytes(len(root) - 32)


@pytest.mark.parametrize("size_mib", [64, 300])
def test_fsck_fat_accepts_the_formatted_volume(tmp_path, size_mib):
    tool = fsck_fat()
    if tool is None:
        if os.environ.get("CHARMERA_REQUIRE_FSCK"):
            pytest.fail("fsck.fat (dosfstools) is not installed and CHARMERA_REQUIRE_FSCK is set")
        pytest.skip("fsck.fat not installed; the FAT32 invariants are covered by our own reader above (set CHARMERA_REQUIRE_FSCK to fail instead)")
    size = size_mib * MIB
    img = sparse_image(tmp_path / "card.img", size)
    with open(img, "r+b") as fh:
        card.format_fat32(fh, size)
    p = check(img).partition
    part = tmp_path / "partition.img"
    with open(img, "rb") as src, open(part, "wb") as dst:
        src.seek(p.start_lba * S)
        remaining = p.sectors * S
        while remaining:
            chunk = src.read(min(remaining, MIB))
            dst.write(chunk)
            remaining -= len(chunk)
    proc = subprocess.run([tool, "-n", str(part)], capture_output=True, text=True)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "differences" not in proc.stdout.lower()


def test_format_zeroes_old_exfat_structures(tmp_path):
    img = make_exfat_image(tmp_path / "card.img", 64 * MIB)
    assert check(img).boot.kind == "exfat"
    with open(img, "r+b") as fh:
        card.format_fat32(fh, 64 * MIB)
        assert card.read_at(fh, (2048 + 12) * S, S) == bytes(S)  # old backup boot sector gone
    r = check(img)
    assert r.boot.kind == "fat32" and r.exit_code == 0


def test_plan_cluster_sizes_for_real_cards():
    gib = 1024 * MIB
    for size, expected in ((8 * gib, 4096), (16 * gib, 8192), (32 * gib, 16384), (64 * gib, 32768), (128 * gib, 32768), (512 * gib, 32768)):
        plan = card.plan_fat32(size)
        assert plan.cluster_bytes == expected, size
        assert card.FAT32_MIN_CLUSTERS <= plan.cluster_count < card.FAT32_MAX_CLUSTERS
        assert (plan.cluster_count + 2) * 4 <= plan.fat_sectors * S
        assert plan.partition_sectors < 1 << 32
    assert card.plan_fat32(4 * gib, cluster_size=32768).cluster_bytes == 32768
    with pytest.raises(card.CardError):
        card.plan_fat32(2 * gib, cluster_size=32768)  # 65487 clusters: just under the FAT32 minimum


def test_plan_refusals():
    with pytest.raises(card.CardError):
        card.plan_fat32(513 * 1024 * MIB)  # over 512 GiB
    with pytest.raises(card.CardError):
        card.plan_fat32(16 * MIB)  # too small for 65525 clusters even at 512 bytes
    with pytest.raises(card.CardError):
        card.plan_fat32(64 * MIB, cluster_size=4096)  # explicit size that cannot give 65525 clusters
    with pytest.raises(card.CardError):
        card.plan_fat32(64 * MIB, cluster_size=768)
    with pytest.raises(card.CardError):
        card.plan_fat32(64 * MIB, label="a label that is too long")
    with pytest.raises(card.CardError):
        card.plan_fat32(64 * MIB, label="bad*name")
    assert card.parse_cluster_size("4K") == 4096
    assert card.parse_cluster_size("32KiB") == 32768
    assert card.parse_cluster_size("512") == 512
    with pytest.raises(card.CardError):
        card.parse_cluster_size("3000")


def test_fat32_geometry_matches_the_microsoft_estimate():
    # 300 MiB partition, 4 KiB clusters: the FATGEN formula and the verification agree.
    fat_sectors, clusters = card.fat32_geometry(300 * MIB // S - 2048, 8)
    assert clusters >= card.FAT32_MIN_CLUSTERS
    assert (clusters + 2) * 4 <= fat_sectors * S
    assert (clusters + 2) * 4 > (fat_sectors - 2) * S  # not wasteful either


# ---------------------------------------------------------------------------------------------
# Other layouts card-check must recognise


def test_gpt_protective_mbr_is_detected(tmp_path):
    img = sparse_image(tmp_path / "gpt.img", 64 * MIB)
    with open(img, "r+b") as fh:
        card.write_at(fh, 0, card.build_mbr(1, 64 * MIB // S - 1, ptype=0xEE))
        header = bytearray(S)
        header[0:8] = b"EFI PART"
        struct.pack_into("<QII", header, 72, 2, 128, 128)
        card.write_at(fh, S, bytes(header))
        entry = bytearray(128)
        entry[0:16] = __import__("uuid").UUID("ebd0a0a2-b9e5-4433-87c0-68b6b72699c7").bytes_le
        struct.pack_into("<QQ", entry, 32, 2048, 64 * MIB // S - 34)
        entry[56:56 + 10] = "Photo".encode("utf-16-le")
        card.write_at(fh, 2 * S, bytes(entry) + bytes(128 * 127))
    r = check(img)
    assert r.mbr.gpt_protective
    assert r.exit_code == 1
    assert any("GPT" in p for p in r.problems)
    assert r.gpt_entries[0].type_name == "Microsoft basic data" and r.gpt_entries[0].name == "Photo"
    assert r.fixes == ["--format-fat32"]


@pytest.mark.parametrize(
    "entries_lba,count,entry_size,why",
    [
        (2, 128, 100, "entry size 100 is not a power of two"),
        (2, 128, 8192, "entry size 8192 is not a power of two between 128 and 4096"),
        (2, 0xFFFFFFFF, 128, "entries is more than the 1024 this tool reads"),
        (1 << 40, 128, 128, "ends past the end of the disk"),
    ],
)
def test_gpt_with_a_bad_entry_array_is_a_problem_not_a_crash(tmp_path, capsys, entries_lba, count, entry_size, why):
    img = make_gpt_image(tmp_path / "gpt.img", entries_lba=entries_lba, count=count, entry_size=entry_size, with_entries=False)
    r = check(img)
    assert r.mbr.gpt_protective and r.gpt_entries == []
    assert r.exit_code == 1 and r.fixes == ["--format-fat32"]
    bad = [p for p in r.problems if p.startswith("GPT header found but its entry array is unreadable (")]
    assert len(bad) == 1 and why in bad[0], r.problems
    assert f"(LBA {entries_lba:,}, {count:,} entries of {entry_size:,} bytes)" in bad[0]
    assert any("GPT partition table" in p for p in r.problems)
    assert run_cli("card-check", "--image", str(img)) == 1
    assert run_cli("card-fix", "--image", str(img), "--clear-dirty", "--yes") == 1  # nothing to write, no traceback
    capsys.readouterr()


def test_gpt_entry_array_short_read_is_a_problem(tmp_path):
    img = make_gpt_image(tmp_path / "gpt.img", with_entries=False)
    with open(img, "r+b") as fh:
        fh.truncate(2 * S)  # the header is there, the array is not
    with open(img, "rb") as fh:
        r = card.check_disk(fh, 64 * MIB)  # the size the disk claims
    bad = [p for p in r.problems if "entry array is unreadable" in p]
    assert len(bad) == 1 and "short read" in bad[0]
    assert r.exit_code == 1 and r.fixes == ["--format-fat32"]
    # A header with no entries is just an empty table, not an error.
    img2 = make_gpt_image(tmp_path / "empty.img", count=0, with_entries=False)
    r = check(img2)
    assert not any("unreadable" in p for p in r.problems) and r.gpt_entries == []


def test_truncated_image_is_reported_not_a_traceback(tmp_path, monkeypatch, capsys):
    # Only the protective MBR survives: the GPT header read hits the end of the file.
    img = make_gpt_image(tmp_path / "gpt.img", with_entries=False)
    with open(img, "r+b") as fh:
        fh.truncate(S)
    with open(img, "rb") as fh:
        r = card.check_disk(fh, 64 * MIB)
    assert r.exit_code == 1 and r.fixes == ["--format-fat32"]
    assert any("could not read the GPT header" in p for p in r.problems)
    # Nothing readable at all while the target still claims 64 MiB: both commands say so and exit 2.
    empty = sparse_image(tmp_path / "empty.img", 0)
    real = card.resolve_target

    def claims_more(target, image):
        t = real(target, image)
        t.size = 64 * MIB
        return t

    monkeypatch.setattr(card, "resolve_target", claims_more)
    assert run_cli("card-check", "--image", str(empty)) == 2
    assert f"error: {empty} is shorter than its partition table says" in capsys.readouterr().out
    assert run_cli("card-fix", "--image", str(empty), "--clear-dirty", "--yes") == 2
    assert "shorter than its partition table says" in capsys.readouterr().out


def test_superfloppy_and_blank_images(tmp_path):
    flat = sparse_image(tmp_path / "flat.img", 64 * MIB)
    with open(flat, "r+b") as fh:
        plan = card.plan_fat32(64 * MIB)
        card.write_at(fh, 0, card.build_fat32_boot_sector(plan))
    r = check(flat)
    assert r.superfloppy and r.exit_code == 1 and r.fixes == ["--format-fat32"]

    blank = sparse_image(tmp_path / "blank.img", 64 * MIB)
    r = check(blank)
    assert r.exit_code == 1 and r.fixes == ["--format-fat32"]
    assert any("no MBR signature" in p for p in r.problems)


def test_identify_boot_sector():
    assert card.identify_boot_sector(bytes(S)) == "unknown"
    assert card.identify_boot_sector(exfat_boot_region(32 * MIB, 0)[:S]) == "exfat"
    assert card.identify_boot_sector(card.build_fat32_boot_sector(card.plan_fat32(64 * MIB))) == "fat32"
    fat16 = bytearray(S)
    fat16[0:3] = b"\xEB\x3C\x90"
    struct.pack_into("<HBHBHHBHHHII", fat16, 11, 512, 4, 4, 2, 512, 65000, 0xF8, 64, 63, 255, 0, 0)
    fat16[54:62] = b"FAT16   "
    fat16[510:512] = b"\x55\xAA"
    assert card.identify_boot_sector(bytes(fat16)) == "fat16"


# ---------------------------------------------------------------------------------------------
# Command layer, on images


def run_cli(*argv):
    return cli.main(list(argv))


def test_cli_check_and_format_on_image(tmp_path, capsys):
    img = make_exfat_image(tmp_path / "card.img", 64 * MIB)
    assert run_cli("card-check", "--image", str(img)) == 1
    out = capsys.readouterr().out
    assert "VolumeDirty 1" in out and "Android  refuses" in out and "--clear-dirty" in out

    assert run_cli("card-fix", "--image", str(img), "--clear-dirty", "--yes") == 0
    out = capsys.readouterr().out
    assert "VolumeFlags 0x0002 -> 0x0000" in out
    assert run_cli("card-check", "--image", str(img)) == 0

    assert run_cli("card-fix", "--image", str(img), "--format-fat32", "--yes", "--label", "photos") == 0
    out = capsys.readouterr().out
    assert "type 0x0C" in out and "no problem found" in out
    r = check(img)
    assert r.boot.kind == "fat32" and r.boot.fat.label == "PHOTOS     " and r.partition.type == 0x0C

    with open(img, "r+b") as fh:
        card.set_partition_type(fh, 1, 0x07)
    assert run_cli("card-check", "--image", str(img)) == 1
    assert run_cli("card-fix", "--image", str(img), "--fix-mbr-type", "--yes") == 0
    assert check(img).partition.type == 0x0C


def test_cli_fix_refusals(tmp_path, capsys):
    img = make_exfat_image(tmp_path / "card.img", 64 * MIB)
    assert run_cli("card-fix", "--image", str(img)) == 2  # nothing to do
    assert run_cli("card-fix", "--image", str(img), "--format-fat32", "--clear-dirty", "--yes") == 2
    assert run_cli("card-fix", "--image", str(tmp_path / "missing.img"), "--clear-dirty", "--yes") == 2
    assert run_cli("card-fix", "--image", str(img), "--format-fat32", "--yes", "--cluster-size", "4096") == 2
    assert run_cli("card-fix", "--image", str(img), "--format-fat32", "--yes", "--label", "way too long label") == 2
    # Without --yes and without a terminal nothing is written.
    assert run_cli("card-fix", "--image", str(img), "--clear-dirty") == 2
    assert check(img).boot.exfat.dirty
    # A fix that does not apply to this filesystem writes nothing and reports the remaining problem.
    assert run_cli("card-fix", "--image", str(img), "--fix-mbr-type", "--yes") == 1
    assert check(img).partition.type == 0x07
    assert run_cli("card-check", str(img)) == 2  # a regular file given as a device
    assert run_cli("card-check") == 2
    capsys.readouterr()


def test_cli_refuses_oversize_target(tmp_path, monkeypatch):
    img = sparse_image(tmp_path / "huge.img", 64 * MIB)
    real = card.resolve_target

    def fake(target, image):
        t = real(target, image)
        t.size = 600 * 1024 * MIB
        return t

    monkeypatch.setattr(card, "resolve_target", fake)
    assert run_cli("card-fix", "--image", str(img), "--format-fat32", "--yes") == 2
    assert check(img).mbr.signature_ok is False  # untouched


def test_format_confirmation_is_the_word_and_the_path(tmp_path, monkeypatch, capsys):
    img = make_exfat_image(tmp_path / "card.img", 64 * MIB)
    prompts = []
    answers = []

    class Tty:
        def isatty(self):
            return True

    def fake_input(prompt):
        prompts.append(prompt)
        return answers.pop(0)

    monkeypatch.setattr(card.sys, "stdin", Tty())
    monkeypatch.setattr("builtins.input", fake_input)
    # The bare word is not enough any more.
    answers.append("FORMAT")
    assert run_cli("card-fix", "--image", str(img), "--format-fat32") == 2
    assert "Not confirmed; nothing was written." in capsys.readouterr().out
    assert check(img).boot.kind == "exfat"
    # The word plus the target as given is.
    answers.append(f"FORMAT {img}")
    assert run_cli("card-fix", "--image", str(img), "--format-fat32") == 0
    assert prompts == [f"Type FORMAT {img} to continue, anything else to stop: "] * 2
    assert check(img).boot.kind == "fat32"
    # The other fixes still ask for the plain path.
    with open(img, "r+b") as fh:
        card.set_partition_type(fh, 1, 0x07)
    answers.append(str(img))
    assert run_cli("card-fix", "--image", str(img), "--fix-mbr-type") == 0
    assert prompts[-1] == f"Type {img} to continue, anything else to stop: "
    assert check(img).partition.type == 0x0C
    capsys.readouterr()


def test_cli_fix_refuses_a_fixed_disk_unless_overridden(tmp_path, monkeypatch, capsys):
    img = make_exfat_image(tmp_path / "card.img", 64 * MIB)
    fake_device(monkeypatch, img, removable=False, bus="NVMe")
    assert run_cli("card-fix", str(img), "--format-fat32", "--yes") == 2
    out = capsys.readouterr().out
    assert "error: block device sdz, fixed NVMe, Fake Disk is not a card: not removable" in out
    assert check(img).boot.kind == "exfat"  # untouched
    # Every fix is gated, not only the reformat.
    assert run_cli("card-fix", str(img), "--clear-dirty", "--yes", ) == 2
    assert "not a card: not removable" in capsys.readouterr().out
    assert check(img).boot.exfat.dirty
    # card-check is read-only and still works on it.
    assert run_cli("card-check", str(img)) == 1
    assert "block device sdz, fixed NVMe, Fake Disk:" in capsys.readouterr().out
    # The override goes ahead, and says so.
    assert run_cli("card-fix", str(img), "--clear-dirty", "--yes", "--i-know-what-i-am-doing") == 0
    out = capsys.readouterr().out
    assert "warning: block device sdz, fixed NVMe, Fake Disk is not a card: not removable" in out
    assert "Device:  " in out and "(block device sdz, fixed NVMe, Fake Disk, " in out
    assert not check(img).boot.exfat.dirty


def test_cli_fix_warns_when_removable_is_unknown(tmp_path, monkeypatch, capsys):
    img = make_exfat_image(tmp_path / "card.img", 64 * MIB)
    fake_device(monkeypatch, img, removable=None)
    assert run_cli("card-fix", str(img), "--clear-dirty", "--yes", "--i-know-what-i-am-doing") == 0
    out = capsys.readouterr().out
    assert "warning: block device sdz, removable?, Fake Disk: removable flag unknown" in out
    assert "error:" not in out
    assert not check(img).boot.exfat.dirty
    # A removable disk gets neither warning nor refusal.
    img2 = make_exfat_image(tmp_path / "card2.img", 64 * MIB)
    fake_device(monkeypatch, img2, removable=True, bus="USB")
    assert run_cli("card-fix", str(img2), "--clear-dirty", "--yes", "--i-know-what-i-am-doing") == 0
    out = capsys.readouterr().out
    assert "warning:" not in out and "(block device sdz, removable USB, Fake Disk, " in out


def test_cli_fix_refuses_a_windows_disk_with_other_letters(tmp_path, monkeypatch, capsys):
    img = make_exfat_image(tmp_path / "card.img", 64 * MIB)
    fake_device(monkeypatch, img, removable=True, bus="USB", disk_number=2, letters=["E", "F"], letter_partition=None)
    assert run_cli("card-fix", str(img), "--format-fat32", "--yes") == 2
    out = capsys.readouterr().out
    assert "error: PhysicalDrive2 holds more than one volume (E:, F:)" in out and "--i-know-what-i-am-doing" in out
    assert check(img).boot.kind == "exfat"  # untouched
    # One letter on partition 1 is the normal card and passes the gate (the write itself needs Windows).
    fake_device(monkeypatch, img, removable=True, bus="USB", disk_number=2, letters=["E"], letter_partition=1)
    monkeypatch.setattr(card, "_confirm", lambda args, target, expected: False)
    assert run_cli("card-fix", str(img), "--format-fat32", "--yes") == 2
    assert "error:" not in capsys.readouterr().out


# ---------------------------------------------------------------------------------------------
# Disk listing, parsed from fake data


linux_paths_only = pytest.mark.skipif(
    os.name == "nt", reason="parses Linux sysfs and /proc/mounts, whose paths start with /")


@linux_paths_only
def test_list_disks_linux_from_fake_sysfs(tmp_path):
    sys_root = tmp_path / "sys"
    dev_root = tmp_path / "dev"
    dev_root.mkdir()

    def disk(name, removable, size_sectors, model=None, mmc_type=None, parts=()):
        d = sys_root / "block" / name
        (d / "device").mkdir(parents=True)
        (d / "removable").write_text(f"{removable}\n")
        (d / "size").write_text(f"{size_sectors}\n")
        if model:
            (d / "device" / "model").write_text(model + "\n")
        if mmc_type:
            (d / "device" / "type").write_text(mmc_type + "\n")
            (d / "device" / "name").write_text("SD64G\n")
        for p in parts:
            (d / p).mkdir()

    disk("sdb", 1, 62333952, model="Ultra", parts=("sdb1",))
    disk("mmcblk0", 0, 124735488, mmc_type="SD", parts=("mmcblk0p1",))
    disk("mmcblk1", 0, 124735488, mmc_type="MMC")
    disk("nvme0n1", 0, 2000409264, model="Samsung SSD", parts=("nvme0n1p1",))
    disk("sdc", 1, 2 * 1024 * 1024 * 1024 * 1024 // 512, model="Big USB disk")
    disk("loop0", 0, 1000)
    mounts = tmp_path / "mounts"
    mounts.write_text(f"{dev_root}/sdb1 /media/me/CHARMERA vfat rw 0 0\n{dev_root}/nvme0n1p1 / ext4 rw 0 0\nproc /proc proc rw 0 0\n")

    disks = {d.path: d for d in card.list_disks_linux(str(sys_root), str(mounts), str(dev_root))}
    assert set(os.path.basename(p) for p in disks) == {"sdb", "mmcblk0", "mmcblk1", "nvme0n1", "sdc"}
    sdb = disks[f"{dev_root}/sdb"]
    assert sdb.removable and sdb.size == 62333952 * 512 and sdb.model == "Ultra"
    assert sdb.mounted == [f"{dev_root}/sdb1 on /media/me/CHARMERA"]
    assert sdb.card_candidate == (True, "")
    mmc = disks[f"{dev_root}/mmcblk0"]
    assert mmc.card_candidate == (True, "") and mmc.bus == "SD card" and mmc.mounted == []
    assert disks[f"{dev_root}/mmcblk1"].card_candidate[0] is False
    nvme = disks[f"{dev_root}/nvme0n1"]
    assert nvme.card_candidate == (False, "not removable") and nvme.mounted
    assert disks[f"{dev_root}/sdc"].card_candidate == (False, "larger than 512 GiB")
    text = card.format_disk_list(list(disks.values()))
    assert "[card?]" in text and "not a card: larger than 512 GiB" in text and "not a card: not removable" in text


def test_windows_list_parsers():
    wmic = "\r\n\r\nNode,Index,InterfaceType,MediaType,Model,Size\r\nPC,0,SCSI,Fixed hard disk media,Samsung SSD 980,1000202273280\r\nPC,2,USB,Removable Media,Generic STORAGE DEVICE USB Device,31914983424\r\n"
    disks = card.parse_wmic_csv(wmic)
    assert [d.path for d in disks] == ["\\\\.\\PhysicalDrive0", "\\\\.\\PhysicalDrive2"]
    assert disks[0].card_candidate == (False, "not removable")
    assert disks[1].card_candidate == (True, "") and disks[1].size == 31914983424
    ps = '"Number","FriendlyName","Size","BusType"\r\n"0","Samsung SSD 980","1000202273280","NVMe"\r\n"1","SD Card","63864569856","SD"\r\n"2","Seagate Expansion","4000787030016","USB"\r\n'
    disks = card.parse_get_disk_csv(ps)
    assert [(d.path, d.card_candidate[0]) for d in disks] == [
        ("\\\\.\\PhysicalDrive0", False),
        ("\\\\.\\PhysicalDrive1", True),
        ("\\\\.\\PhysicalDrive2", False),
    ]
    assert card.parse_wmic_csv("") == [] and card.parse_get_disk_csv("garbage") == []


@linux_paths_only
def test_linux_mounts_for_matches_disk_and_partitions(tmp_path):
    sys_root = tmp_path / "sys"
    (sys_root / "block" / "sdz" / "sdz1").mkdir(parents=True)
    dev = tmp_path / "dev"
    dev.mkdir()
    mounts = tmp_path / "mounts"
    mounts.write_text(f"{dev}/sdz1 /mnt/with\\040space vfat rw 0 0\n{dev}/sdy /mnt/other vfat rw 0 0\n")
    found = card.linux_mounts_for(f"{dev}/sdz", "sdz", str(mounts), str(sys_root))
    assert found == [(f"{dev}/sdz1", "/mnt/with space")]
    assert card.linux_mounts_for(f"{dev}/sdx", "sdx", str(mounts), str(sys_root)) == []


def test_windows_target_spellings_are_recognised():
    for text in ("\\\\.\\PhysicalDrive2", "\\\\?\\PhysicalDrive2", "PhysicalDrive2", "physicaldrive2"):
        m = card._WIN_PHYSICAL.match(text)
        assert m and m.group(1) == "2", text
    assert card._WIN_PHYSICAL.match("PhysicalDrive") is None
    for text in ("E:", "e:", "E", "E:\\"):
        m = card._WIN_LETTER.match(text)
        assert m and m.group(1).upper() == "E", text
    assert card._WIN_LETTER.match("EF:") is None and card._WIN_LETTER.match("/dev/sdb") is None
    assert card._physical_number("\\\\.\\PhysicalDrive7") == 7 and card._physical_number("E:") is None


# ---------------------------------------------------------------------------------------------
# Target resolution: the removable gate's inputs, on fake sysfs and constructed Targets


def test_linux_removable_info_from_fake_sysfs(tmp_path):
    sys_root = tmp_path / "sys"
    (sys_root / "block").mkdir(parents=True)

    def disk(name, removable=None, mmc_type=None, device_path=None, vendor=None, model=None):
        # The kernel lays /sys/block/<name> out as a symlink into /sys/devices/...; the bus is read off that path.
        real = sys_root / "devices" / device_path / "block" / name if device_path else sys_root / "block" / name
        (real / "device").mkdir(parents=True)
        if device_path:
            os.symlink(real, sys_root / "block" / name, target_is_directory=True)
        if removable is not None:
            (real / "removable").write_text(f"{removable}\n")
        if mmc_type:
            (real / "device" / "type").write_text(mmc_type + "\n")
            (real / "device" / "name").write_text("SD64G\n")
        if vendor:
            (real / "device" / "vendor").write_text(vendor + "\n")
        if model:
            (real / "device" / "model").write_text(model + "\n")

    try:
        disk("sdb", 1, device_path="pci0000:00/0000:00:14.0/usb1/1-3/1-3:1.0/host6/target6:0:0/6:0:0:0", vendor="SanDisk", model="Ultra")
        disk("sda", 0, device_path="pci0000:00/0000:00:17.0/ata1/host0/target0:0:0/0:0:0:0", vendor="ATA", model="Samsung SSD 860")
    except (OSError, NotImplementedError):
        pytest.skip("cannot create symlinks here")
    disk("mmcblk0", 0, mmc_type="SD")
    disk("mmcblk1", 0, mmc_type="MMC")
    disk("nvme0n1", 0, model="WD Black")
    disk("sr0", 1)
    disk("vda")  # no removable flag at all

    def info(name):
        return card.linux_removable_info(name, str(sys_root))

    assert info("sdb") == (True, "USB")
    assert info("sda") == (False, "ATA")
    assert info("mmcblk0") == (True, "SD card")  # the slot says 0, the card type wins
    assert info("mmcblk1") == (False, "eMMC")
    assert info("nvme0n1") == (False, "NVMe")
    assert info("sr0") == (False, "optical")
    assert info("vda") == (None, "")
    assert card.linux_disk_model("sdb", str(sys_root)) == "SanDisk Ultra"
    assert card.linux_disk_model("mmcblk0", str(sys_root)) == "SD64G"
    assert card.linux_disk_model("vda", str(sys_root)) == ""
    assert card.describe_device("block device sdb", *info("sdb"), card.linux_disk_model("sdb", str(sys_root))) == "block device sdb, removable USB, SanDisk Ultra"
    # --list sees the same thing.
    listed = {os.path.basename(d.path): d for d in card.list_disks_linux(str(sys_root), str(tmp_path / "no-mounts"), str(tmp_path / "dev"))}
    assert listed["sdb"].card_candidate == (True, "") and listed["sdb"].bus == "USB" and listed["sdb"].model == "SanDisk Ultra"
    assert listed["sda"].card_candidate == (False, "not removable") and listed["sda"].bus == "ATA"
    assert listed["vda"].card_candidate == (False, "removable flag unknown")


def test_describe_device():
    assert card.describe_device("block device sdb", True, "USB", "SanDisk Ultra") == "block device sdb, removable USB, SanDisk Ultra"
    assert card.describe_device("block device nvme0n1", False, "NVMe", "") == "block device nvme0n1, fixed NVMe"
    assert card.describe_device("block device vda", None) == "block device vda, removable?"
    assert (
        card.describe_device("PhysicalDrive2 (disk 2, letters on it: E:, F:)", True, "USB", "Generic STORAGE DEVICE")
        == "PhysicalDrive2 (disk 2, letters on it: E:, F:), removable USB, Generic STORAGE DEVICE"
    )


def test_windows_letter_caution():
    def target(given, letters, partition):
        return card.Target(
            given=given,
            path="\\\\.\\PhysicalDrive2",
            size=64 * MIB,
            is_image=False,
            description="x",
            disk_number=2,
            letters=letters,
            letter_partition=partition,
        )

    assert card.windows_letter_caution(target("E:", ["E"], 1)) is None
    assert card.windows_letter_caution(target("E:", ["E"], 0)) is None  # superfloppy: the letter is the whole disk
    assert card.windows_letter_caution(target("\\\\.\\PhysicalDrive2", ["E"], None)) is None
    assert card.windows_letter_caution(target("\\\\.\\PhysicalDrive2", [], None)) is None
    msg = card.windows_letter_caution(target("F:", ["E", "F"], 2))
    assert msg.startswith("F: is partition 2 of PhysicalDrive2, not the first one") and "also holds E:" in msg
    msg = card.windows_letter_caution(target("E:", ["E", "F", "G"], 1))
    assert msg.startswith("E: shares PhysicalDrive2 with F:, G:") and "--format-fat32" in msg
    msg = card.windows_letter_caution(target("PhysicalDrive2", ["E", "F"], None))
    assert msg.startswith("PhysicalDrive2 holds more than one volume (E:, F:)")
    msg = card.windows_letter_caution(target("f:", ["F"], 3))  # an unlettered partition sits before it
    assert msg.startswith("F: is partition 3 of PhysicalDrive2") and "holds" not in msg
    image = card.Target(given="x.img", path="x.img", size=1, is_image=True, description="image file")
    assert card.windows_letter_caution(image) is None
    linux = card.Target(given="/dev/sdb", path="/dev/sdb", size=1, is_image=False, description="x", sys_name="sdb")
    assert card.windows_letter_caution(linux) is None


def test_posix_open_errors_become_card_errors(tmp_path, monkeypatch):
    dev = sparse_image(tmp_path / "reader", 64 * MIB)
    real_open = os.open

    def no_medium(path, flags, *rest, **kw):
        if str(path) == str(dev):
            raise OSError(123, "No medium found")  # ENOMEDIUM: a reader with no card in it
        return real_open(path, flags, *rest, **kw)

    with monkeypatch.context() as m:
        m.setattr(os, "open", no_medium)
        with pytest.raises(card.CardError) as exc:
            card._posix_device_size(str(dev))
    assert str(exc.value) == f"cannot open {dev}: No medium found (no card in the reader?)"

    def no_device(fd, pos, whence):
        raise OSError(6, "No such device or address")  # ENXIO

    with monkeypatch.context() as m:
        m.setattr(os, "lseek", no_device)
        with pytest.raises(card.CardError) as exc:
            card._posix_device_size(str(dev))
    assert str(exc.value) == f"cannot open {dev}: No such device or address"

    def denied(path, flags, *rest, **kw):
        raise PermissionError(13, "Permission denied")

    with monkeypatch.context() as m:
        m.setattr(os, "open", denied)
        with pytest.raises(card.CardError, match="run with sudo"):
            card._posix_device_size(str(dev))
    assert card._posix_device_size(str(dev)) == 64 * MIB
