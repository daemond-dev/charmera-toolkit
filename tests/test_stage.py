"""stage: copying a verified update file to a card root.

Everything runs on synthetic images and a temporary folder standing in for the card (with the override
flag, since a tmp_path is not a mount point). One test uses the real stock dump when CHARMERA_STOCK_IMAGE
points at it. No firmware bytes are embedded here.
"""
import argparse
import dataclasses
import hashlib
import os
import shutil
import struct
import zlib
from collections import namedtuple
from pathlib import Path

import pytest

from charmera_toolkit import checksum, frames, image, pack, settings, stage

STAMP = "202611041200"
# Synthetic images carry no firmware bytes, so they hold stand-ins for the build marks, placed where the public
# build has them and shaped like the real ones: the build string and the compile date, the stamp with its NUL,
# and a sensor program (the reset table, a 61-write init table that starts with a page select, switches page
# once more and ends with ff ff, and the code literal that points at it). The init table's hash is not a known
# build's, and neither is the vector table's (one ARM instruction, then 0xFF), so the synthetic_build fixture
# registers both as the public build's where a test wants a file accepted without --stock. Tests on the real
# dump use the real identity.
SENSOR_RESET_OFFSET = 0xF5BFC
SENSOR_START, SENSOR_END = 0xF5C6C, 0xF5CE8
SENSOR_POINTER = 0x7B91C
SYNTHETIC_SENSOR_TABLE = bytes(b for pair in [(0xFD, 0x00)] + [(r, (r * 3) & 0xFF) for r in range(0x20, 0x20 + 58)]
                               + [(0xFD, 0x01), (0x11, 0x80)] for b in pair) + b"\xff\xff"
assert len(SYNTHETIC_SENSOR_TABLE) == SENSOR_END - SENSOR_START == 124
SYNTHETIC_VECTORS = b"\x18\xf0\x9f\xe5" + b"\xff" * 60      # bytes 0x200..0x240 of synthetic_image()


def put_build_marks(img: bytearray) -> None:
    """The public build's marks at its offsets: detect_build reads 'public-dump-modified' where the synthetic_build
    fixture registers the synthetic sensor table as that build's, 'charmera-unknown-build' elsewhere."""
    b = image.PUBLIC_BUILD
    img[b.stamp_offset: b.stamp_offset + 13] = b.stamp.encode() + b"\x00"
    img[b.build_offset: b.build_offset + 20] = b"JH5408  20250520\x00\x00\x00\x00"
    img[0xF3978: 0xF3978 + 12] = b"Oct 14 2025\x00"
    img[SENSOR_RESET_OFFSET: SENSOR_RESET_OFFSET + 6] = image.SENSOR_RESET_TABLE
    img[SENSOR_START: SENSOR_END] = SYNTHETIC_SENSOR_TABLE
    img[SENSOR_POINTER: SENSOR_POINTER + 4] = struct.pack("<I", SENSOR_START - image.VA_OFFSET)


# --- synthetic image (same shape as tests/test_core.py builds) -----------------------------------------------


def make_record(ui_sound=5, playback=5):
    body = bytearray(252)
    body[0] = 1
    body[settings.OFF_PLAYBACK_VOLUME] = playback
    body[settings.OFF_UI_SOUND] = ui_sound
    return bytes(body) + (zlib.crc32(bytes(body)) & 0xFFFFFFFF).to_bytes(4, "little")


def synthetic_image(records=(), with_sounds=True):
    """A fake 2 MiB image with a GP+ pack holding five tiny WAVs and the given settings records."""
    img = bytearray(b"\xff" * checksum.FLASH_SIZE)
    img[0:4] = b"SPII"
    img[0x200:0x240] = SYNTHETIC_VECTORS
    put_build_marks(img)
    if with_sounds:
        base = image.RESOURCE_BASE
        img[base: base + 0x18] = b"GP+\x00\x03\x00\x03\x00" + b"\x00" * 16
        names = ["BEEPWAV", "CLICKWAV", "IMGWAV", "POWEROFF_AUDIOWAV", "POWERON_AUDIOWAV", "ZZZGPZP"]
        sector = 4
        off = base + 0x18
        for n in names:
            img[off: off + 20] = n.encode().ljust(20, b"\x00")
            img[off + 20: off + 24] = struct.pack("<I", sector)
            p = base + sector * 0x200
            if n != "ZZZGPZP":
                bits = 16 if n == "BEEPWAV" else 8
                rate = 11025 if n in ("BEEPWAV", "IMGWAV") else 8000
                data = bytes([0x80 if bits == 8 else 0x11]) * 400
                wav = (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt "
                       + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * bits // 8, bits // 8, bits)
                       + b"data" + struct.pack("<I", len(data)) + data)
                img[p: p + len(wav)] = wav
            else:
                gpzp = frames.encode_gpzp(b"\x00" * 64)   # a real fixed-Huffman stream, as verify expects
                img[p: p + len(gpzp)] = gpzp
            off += 24
            sector += 2
    for i, rec in enumerate(records):
        o = settings.SETTINGS_BASE + i * settings.RECORD_STRIDE
        img[o: o + 256] = rec
        img[o + 256: o + 512] = b"\x00" * 256
    img[settings.PHOTO_DIRECTORY: settings.PHOTO_DIRECTORY + 0x200] = b"\x00" * 0x200
    return bytes(img)


# --- harness ------------------------------------------------------------------------------------------------


def parser():
    p = argparse.ArgumentParser(prog="charmera")
    stage.register(p.add_subparsers(dest="command"))
    return p


def run(*argv):
    args = parser().parse_args(["stage", *argv])
    return args.func(args)


@pytest.fixture
def synthetic_build(monkeypatch):
    """The public build's identity with the synthetic sensor and vector tables in place of the real ones."""
    fake = dataclasses.replace(image.PUBLIC_BUILD, sensor_table_sha256=hashlib.sha256(SYNTHETIC_SENSOR_TABLE).hexdigest(),
                               vector_table_sha256=hashlib.sha256(SYNTHETIC_VECTORS).hexdigest())
    monkeypatch.setitem(image.KNOWN_BUILDS, fake.key, fake)
    return fake


@pytest.fixture
def update_file(tmp_path, synthetic_build):
    """A correctly named update file built from a synthetic image, in its own folder."""
    payload = pack.prepare_payload(synthetic_image([make_record()]))
    name = checksum.build_update_name(payload, STAMP)
    out = tmp_path / "out"
    out.mkdir()
    p = out / name.filename
    p.write_bytes(payload)
    return p


@pytest.fixture
def backup(tmp_path):
    """This camera's own backup: the 2 MiB read the update_file fixture's file was made from."""
    p = tmp_path / "backup.bin"
    p.write_bytes(synthetic_image([make_record()]))
    return p


@pytest.fixture
def card(tmp_path):
    """A folder standing in for the card root, with the DCIM directory a used card has."""
    root = tmp_path / "card"
    (root / "DCIM" / "100MEDIA").mkdir(parents=True)
    return root


@pytest.fixture
def confirmed(monkeypatch):
    """Answer the confirmation with yes; records how often it was asked."""
    calls = []

    def fake(expected):
        calls.append(expected)
        return True

    monkeypatch.setattr(stage, "_confirm", fake)
    return calls


def card_files(root):
    return sorted(p.name for p in root.iterdir() if p.is_file())


OVERRIDE = stage.OVERRIDE_FLAG


# --- tests --------------------------------------------------------------------------------------------------


def test_happy_path_copies_and_verifies(update_file, backup, card, confirmed, capsys):
    assert run(str(update_file), "--card", str(card), OVERRIDE, "--stock", str(backup)) == 0
    out = capsys.readouterr().out
    dest = card / update_file.name
    assert dest.read_bytes() == update_file.read_bytes()
    assert card_files(card) == [update_file.name]           # no temporary file left behind
    assert confirmed == [str(card)]
    assert "PASS filename" in out and "PASS checksum" in out and "PASS stamp" in out
    assert "PASS sensor-table" in out and "PASS vector-table" in out and "PASS software-region" in out
    assert "WARN backup" not in out
    assert "WARN resources" in out                          # the synthetic pack has 6 entries, not 43: shown, not blocking
    assert "Plan" in out and f"destination:  {dest}" in out
    assert f"backup:       {backup}" in out and hashlib.sha256(backup.read_bytes()).hexdigest() in out
    assert "read back sha256 " + hashlib.sha256(update_file.read_bytes()).hexdigest() + ": matches" in out
    assert "not a mount point" in out                       # the override is announced
    low = out.lower()
    assert "delete the update file" in low and "every power-on" in low          # the camera steps are printed
    assert low.index("take the card out first") < low.index("hold the power key about a second")
    assert "millennium" in low and "photos it stored there" in low
    # a backup fits its camera only when it was read before the camera was ever flashed
    flat = " ".join(out.split())
    assert "A backup of a camera read before it was ever flashed, packed with pack, fits that camera whatever its edition." in flat
    assert "A camera's own backup" not in flat
    # Tags mirror verify: no FAIL line anywhere.
    assert not any(line.startswith("FAIL") for line in out.splitlines())


def test_without_a_backup_stage_warns_and_still_writes(update_file, card, confirmed, capsys):
    """The owner's decision of 2026-11-06: no --stock is a warning, not a refusal, and it is said plainly."""
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 0
    out = capsys.readouterr().out
    assert "WARN backup" in out and "nothing here compares the file with the camera's own firmware" in out
    assert "a file that does not fit the camera cannot be undone" in out
    assert "backup:       none given (--stock)" in out
    assert "No backup of this camera's own flash chip was given" in out and "stage still writes the file" in out
    assert (card / update_file.name).read_bytes() == update_file.read_bytes() and confirmed == [str(card)]
    # the file already on the card: nothing to do, and the warning is still there
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 0
    out = capsys.readouterr().out
    assert "nothing to do" in out and "WARN backup" in out


def test_refuses_a_verify_fail(update_file, card, confirmed, capsys):
    bad = update_file.with_name(f"JH_5408{STAMP}_00000001.bin")
    bad.write_bytes(update_file.read_bytes())
    assert run(str(bad), "--card", str(card), OVERRIDE) == 1
    out = capsys.readouterr().out
    assert "FAIL checksum" in out and "RESULT: verify failed" in out
    assert card_files(card) == [] and confirmed == []
    # a stamp equal to the stock stamp is a FAIL too
    stock_named = update_file.with_name(f"JH_5408{checksum.STOCK_TIMESTAMP}_{checksum.byte_sum(update_file.read_bytes()):08X}.bin")
    stock_named.write_bytes(update_file.read_bytes())
    assert run(str(stock_named), "--card", str(card), OVERRIDE) == 1
    assert "FAIL stamp" in capsys.readouterr().out
    # a 2 MiB file never goes on a card
    full = update_file.with_name(f"JH_5408{STAMP}_{checksum.byte_sum(synthetic_image([make_record()])):08X}.bin")
    full.write_bytes(synthetic_image([make_record()]))
    assert run(str(full), "--card", str(card), OVERRIDE) == 1
    assert "FAIL size-2mib" in capsys.readouterr().out
    assert card_files(card) == []


def test_refuses_when_another_update_file_is_present(update_file, card, confirmed, capsys):
    other = card / "jh_5408202601010000_00000000.BIN"      # lower case: FAT does not care, neither may we
    other.write_bytes(b"\x00" * 16)
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 1
    out = capsys.readouterr().out
    assert "already holds 1 update file" in out and other.name in out and "another update file" in out
    assert "undefined" in out
    assert card_files(card) == [other.name] and confirmed == []
    # same name, different content: refused as well, and named as such
    other.unlink()
    (card / update_file.name).write_bytes(b"\x00" * 16)
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 1
    out = capsys.readouterr().out
    assert "same name, different content" in out
    assert (card / update_file.name).read_bytes() == b"\x00" * 16


def test_identical_file_already_present_is_a_no_op(update_file, card, confirmed, capsys):
    dest = card / update_file.name
    shutil.copyfile(update_file, dest)
    before = dest.stat()
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 0
    out = capsys.readouterr().out
    assert "already on the card and is byte-identical" in out and "nothing to do" in out
    assert confirmed == []
    after = dest.stat()
    assert (before.st_mtime_ns, before.st_size) == (after.st_mtime_ns, after.st_size)
    assert card_files(card) == [update_file.name]
    # identical content next to a second update file is still two files: refused
    (card / "JH_5408202601010000_00000000.bin").write_bytes(b"\x00")
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 1
    assert "already holds 2 update file" in capsys.readouterr().out


def test_too_little_space(update_file, card, confirmed, monkeypatch, capsys):
    usage = namedtuple("usage", "total used free")
    size = update_file.stat().st_size
    monkeypatch.setattr(shutil, "disk_usage", lambda path: usage(32 * stage.MIB, 32 * stage.MIB - size, size))
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 1
    out = capsys.readouterr().out
    assert "free; the file needs" in out
    assert card_files(card) == [] and confirmed == []
    # just enough: the slack is all it takes
    monkeypatch.setattr(shutil, "disk_usage", lambda path: usage(32 * stage.MIB, 0, size + stage.FREE_SPACE_SLACK))
    assert run(str(update_file), "--card", str(card), OVERRIDE, "--plan-only") == 0


def test_not_a_mount_point_without_the_override(update_file, card, confirmed, capsys):
    assert run(str(update_file), "--card", str(card)) == 2
    out = capsys.readouterr().out
    assert "is not a mount point or drive root" in out and OVERRIDE in out
    assert card_files(card) == [] and confirmed == []
    # the same folder counts as a card once the platform says it is a mount point
    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(stage, "is_drive_root", lambda path: True)
        assert run(str(update_file), "--card", str(card)) == 0
    assert card_files(card) == [update_file.name]
    assert "not a mount point" not in capsys.readouterr().out


def test_confirmation_mismatch_writes_nothing(update_file, card, monkeypatch, capsys):
    class Tty:
        def isatty(self):
            return True

    prompts = []
    answers = []

    def fake_input(prompt):
        prompts.append(prompt)
        if not answers:
            raise EOFError
        return answers.pop(0)

    monkeypatch.setattr(stage.sys, "stdin", Tty())
    monkeypatch.setattr("builtins.input", fake_input)
    # wrong text
    answers.append("yes")
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 2
    assert "Not confirmed; nothing was written." in capsys.readouterr().out
    assert prompts and str(card) in prompts[-1]
    assert card_files(card) == []
    # EOF (Ctrl-D, closed pipe)
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 2
    assert "Not confirmed; nothing was written." in capsys.readouterr().out
    assert card_files(card) == []
    # the exact path, with a trailing separator forgiven, writes
    answers.append(str(card) + os.sep)
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 0
    assert card_files(card) == [update_file.name]


def test_no_terminal_means_no_write(update_file, card, monkeypatch, capsys):
    class NotATty:
        def isatty(self):
            return False

    monkeypatch.setattr(stage.sys, "stdin", NotATty())
    monkeypatch.setattr("builtins.input", lambda prompt: pytest.fail("input() must not be called without a terminal"))
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 2
    assert "no terminal to confirm on" in capsys.readouterr().out
    assert card_files(card) == []


def test_read_back_mismatch(update_file, card, confirmed, monkeypatch, capsys):
    monkeypatch.setattr(stage, "read_back_sha256", lambda path: "0" * 64)
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 1
    out = capsys.readouterr().out
    assert "read-back MISMATCH" in out and "has been deleted" in out
    assert card_files(card) == []                            # the suspect copy does not stay on the card
    # a read error on the way back is reported, exit 1
    def boom(path):
        raise OSError(5, "Input/output error")
    monkeypatch.setattr(stage, "read_back_sha256", boom)
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 1
    assert "reading" in capsys.readouterr().out
    (card / update_file.name).unlink()


def test_plan_only_writes_nothing(update_file, card, confirmed, capsys):
    assert run(str(update_file), "--card", str(card), OVERRIDE, "--plan-only") == 0
    out = capsys.readouterr().out
    assert "plan only: nothing written." in out
    assert "Plan" in out and str(card / update_file.name) in out
    assert "docs/05-CARDS.md" in out and "exFAT" in out and "FAT32" in out
    assert card_files(card) == [] and confirmed == []
    # a plan on a card without DCIM warns and still exits 0
    bare = card.parent / "bare"
    bare.mkdir()
    assert run(str(update_file), "--card", str(bare), OVERRIDE, "--plan-only") == 0
    assert "no DCIM directory" in capsys.readouterr().out
    assert card_files(bare) == []


def test_usage_errors_are_not_tracebacks(update_file, card, confirmed, tmp_path, capsys):
    assert run(str(tmp_path / "missing.bin"), "--card", str(card), OVERRIDE) == 2
    assert "is not a file" in capsys.readouterr().out
    assert run(str(update_file), "--card", str(card), OVERRIDE, "--stock", str(tmp_path / "nope.bin")) == 2
    assert "--stock" in capsys.readouterr().out
    assert run(str(update_file), "--card", str(tmp_path / "absent"), OVERRIDE) == 2
    assert "does not exist" in capsys.readouterr().out
    assert run(str(update_file), "--card", str(update_file), OVERRIDE) == 2
    assert "is not a directory" in capsys.readouterr().out
    assert run(str(update_file), "--card", "   ", OVERRIDE) == 2
    assert "--card needs" in capsys.readouterr().out
    # a file that is not an update file at all fails verify's filename check, exit 1
    plain = tmp_path / "backup.bin"
    plain.write_bytes(update_file.read_bytes())
    assert run(str(plain), "--card", str(card), OVERRIDE) == 1
    assert "FAIL filename" in capsys.readouterr().out
    assert card_files(card) == [] and confirmed == []


def test_write_error_is_reported_and_leaves_nothing(update_file, card, confirmed, monkeypatch, capsys):
    def fail_copy(image_bytes, dest):
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(stage, "copy_to_root", fail_copy)
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 1
    out = capsys.readouterr().out
    assert "writing to" in out and "No space left on device" in out
    assert card_files(card) == []
    # the real copy cleans its temporary file up when the rename fails
    def fail_replace(a, b):
        raise OSError(30, "Read-only file system")
    monkeypatch.undo()
    monkeypatch.setattr(stage.os, "replace", fail_replace)
    with pytest.raises(OSError):
        stage.copy_to_root(b"abc", card / "x.bin")
    assert card_files(card) == []


def test_card_changed_between_plan_and_write(update_file, card, monkeypatch, capsys):
    def confirm_and_drop_a_file(expected):
        (card / "JH_5408202601010000_00000000.bin").write_bytes(b"\x00")
        return True

    monkeypatch.setattr(stage, "_confirm", confirm_and_drop_a_file)
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 1
    assert "changed since the plan was printed" in capsys.readouterr().out
    assert card_files(card) == ["JH_5408202601010000_00000000.bin"]


def test_non_fat_filesystem_is_refused_unless_overridden(update_file, card, confirmed, monkeypatch, capsys):
    monkeypatch.setattr(stage, "is_drive_root", lambda path: True)
    monkeypatch.setattr(stage, "linux_mount_entry", lambda root, mounts_path="/proc/mounts": ("/dev/sdz1", "ext4"))
    monkeypatch.setattr(stage, "linux_removable", lambda device, sys_root="/sys": True)
    if os.name == "nt":
        pytest.skip("the Linux mount lookup is not used on Windows")
    assert run(str(update_file), "--card", str(card)) == 1
    out = capsys.readouterr().out
    assert "is ext4; the camera reads exFAT and FAT32 only" in out and "docs/05-CARDS.md" in out
    assert card_files(card) == []
    assert run(str(update_file), "--card", str(card), OVERRIDE, "--plan-only") == 0
    assert "warning:" in capsys.readouterr().out
    # a fixed disk is not a card
    monkeypatch.setattr(stage, "linux_mount_entry", lambda root, mounts_path="/proc/mounts": ("/dev/sdz1", "vfat"))
    monkeypatch.setattr(stage, "linux_removable", lambda device, sys_root="/sys": False)
    assert run(str(update_file), "--card", str(card)) == 1
    assert "not on removable media" in capsys.readouterr().out
    # a removable exFAT card passes and the plan names the device and the type
    monkeypatch.setattr(stage, "linux_mount_entry", lambda root, mounts_path="/proc/mounts": ("/dev/mmcblk0p1", "exfat"))
    monkeypatch.setattr(stage, "linux_removable", lambda device, sys_root="/sys": True)
    assert run(str(update_file), "--card", str(card), "--plan-only") == 0
    out = capsys.readouterr().out
    assert "exfat, /dev/mmcblk0p1" in out and "warning:" not in out


def test_helpers(tmp_path):
    assert stage.looks_like_update_file("JH_5408202611041200_0F065D55.bin")
    assert stage.looks_like_update_file("jh_x.BIN")
    assert not stage.looks_like_update_file("._JH_5408202611041200_0F065D55.bin")
    assert not stage.looks_like_update_file("JH_5408202611041200_0F065D55.bin.part")
    assert not stage.looks_like_update_file(stage.TEMP_NAME)
    assert stage.same_target("/run/media/deck/CARD/", "/run/media/deck/CARD")
    assert stage.same_target("  /media/u/CARD  ", "/media/u/CARD")
    assert not stage.same_target("", "/media/u/CARD")
    assert not stage.same_target("/media/u/CARD2", "/media/u/CARD")
    if os.name != "nt":
        assert stage.normalize_card("/run/media/deck/CARD/") == Path("/run/media/deck/CARD")
        assert not stage.same_target("/media/u/card", "/media/u/CARD")
    # /proc/mounts parsing: escapes, and the last mount on a point wins
    mounts = tmp_path / "mounts"
    point = tmp_path / "My Card"
    point.mkdir()
    real = os.path.realpath(str(point)).replace(" ", "\\040")
    mounts.write_text(f"/dev/sdb1 {real} vfat rw 0 0\n/dev/mmcblk0p1 {real} exfat rw 0 0\nnone /proc proc rw 0 0\n")
    assert stage.linux_mount_entry(point, str(mounts)) == ("/dev/mmcblk0p1", "exfat")
    assert stage.linux_mount_entry(tmp_path, str(mounts)) == (None, None)
    assert stage.linux_mount_entry(point, str(tmp_path / "absent")) == (None, None)
    # copy_to_root: temporary name, final name, directory fsync result is a bool
    dest = tmp_path / "card2" / "JH_5408202611041200_00000000.bin"
    dest.parent.mkdir()
    synced = stage.copy_to_root(b"\x00" * 100, dest)
    assert isinstance(synced, bool) and dest.read_bytes() == b"\x00" * 100
    assert sorted(p.name for p in dest.parent.iterdir()) == [dest.name]
    assert stage.read_back_sha256(dest) == hashlib.sha256(b"\x00" * 100).hexdigest()
    assert stage.file_equals(dest, b"\x00" * 100) and not stage.file_equals(dest, b"\x00" * 99) and not stage.file_equals(dest, b"\x01" + b"\x00" * 99)
    assert stage.human_size(1966080) == "1.9 MiB" and stage.human_size(512) == "512 B" and stage.human_size(3 * 1024 ** 3) == "3.0 GiB"


@pytest.mark.skipif(os.name == "nt", reason="sysfs lookup is Linux only and the fake tree needs symlinks")
def test_linux_removable_through_a_fake_sysfs(tmp_path):
    """A partition of a USB disk resolves to its whole disk and that disk's removable flag; a built-in SD
    slot (mmcblk, removable=0 in sysfs) counts as removable because the mmc type says SD."""
    sysfs = tmp_path / "sys"
    (sysfs / "class" / "block").mkdir(parents=True)
    (sysfs / "block").mkdir()
    usb_disk = sysfs / "devices" / "pci" / "usb1" / "host" / "block" / "sdb"
    (usb_disk / "sdb1").mkdir(parents=True)
    (usb_disk / "removable").write_text("1\n")
    os.symlink(usb_disk / "sdb1", sysfs / "class" / "block" / "sdb1")
    os.symlink(usb_disk, sysfs / "block" / "sdb")
    mmc = sysfs / "devices" / "platform" / "mmc_host" / "block" / "mmcblk0"
    (mmc / "mmcblk0p1").mkdir(parents=True)
    (mmc / "device").mkdir()
    (mmc / "removable").write_text("0\n")
    (mmc / "device" / "type").write_text("SD\n")
    os.symlink(mmc / "mmcblk0p1", sysfs / "class" / "block" / "mmcblk0p1")
    os.symlink(mmc, sysfs / "block" / "mmcblk0")
    assert stage.linux_removable("/dev/sdb1", str(sysfs)) is True
    assert stage.linux_removable("/dev/mmcblk0p1", str(sysfs)) is True
    assert stage.linux_removable("/tmp/not-a-device", str(sysfs)) is None
    assert stage.linux_removable("/dev/absent9", str(sysfs)) is None


def _renamed(update_file, data, stamp="202611041201"):
    p = update_file.with_name(checksum.build_update_name(data, stamp).filename)
    p.write_bytes(data)
    return p


def test_refuses_a_changed_sensor_table(update_file, card, confirmed, capsys):
    """verify --allow-modified does not cover the sensor table, and stage never passes --allow-unknown-build."""
    bad = bytearray(update_file.read_bytes()); bad[SENSOR_START + 4] ^= 1
    src = _renamed(update_file, bytes(bad))
    assert run(str(src), "--card", str(card), OVERRIDE, "--plan-only") == 1
    out = capsys.readouterr().out
    assert "FAIL sensor-table" in out and "verify failed" in out and "Plan" not in out
    assert card_files(card) == [] and not confirmed


def test_refuses_an_erased_or_damaged_resource_entry(update_file, card, confirmed, capsys):
    data = update_file.read_bytes()
    entries = image.resource_directory(data)
    last = entries[-1]
    beep = next(e for e in entries if e.display_name == "BEEP.WAV")
    erased = bytearray(data); erased[last.offset: last.offset + 0x200] = b"\xff" * 0x200
    riff = bytearray(data); riff[beep.offset: beep.offset + 4] = b"XIFF"
    for i, bad in enumerate((erased, riff)):
        src = _renamed(update_file, bytes(bad), f"20261104120{i + 2}")
        assert run(str(src), "--card", str(card), OVERRIDE, "--plan-only") == 1
        out = capsys.readouterr().out
        assert "FAIL resource-slots" in out and "verify failed" in out
    assert card_files(card) == [] and not confirmed


# --- against the real stock image --------------------------------------------------------------------------------


def test_stage_a_stock_update_file(stock_image, stock_image_path, card, confirmed, tmp_path, capsys):
    # --blank-photos alone: --factory-settings --blank-photos would be candidate C, which stage refuses (below)
    payload = pack.prepare_payload(stock_image, blank_photos=True)
    name = checksum.build_update_name(payload, STAMP)
    src = tmp_path / "out" / name.filename
    src.parent.mkdir()
    src.write_bytes(payload)
    assert run(str(src), "--card", str(card), OVERRIDE, "--stock", str(stock_image_path)) == 0
    out = capsys.readouterr().out
    assert "PASS software-region" in out and "INFO resources" in out and "PASS vector-table" in out
    # --stock is a public build, as a read of a camera flashed with the public restore file is: a warning, not a pass
    assert "WARN sensor-table" in out and "proves a fit only if --stock was read before the camera was ever flashed" in out
    assert (card / name.filename).read_bytes() == payload
    assert card_files(card) == [name.filename]
    # once more: identical copy already there, nothing to do
    assert run(str(src), "--card", str(card), OVERRIDE) == 0
    assert "nothing to do" in capsys.readouterr().out
    # the withdrawn candidate C, made the way docs/03 made it, is refused by its hash
    c = pack.prepare_payload(stock_image, factory_settings=True, blank_photos=True)
    if hashlib.sha256(stock_image).hexdigest() == "cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f":
        c_path = tmp_path / "c" / checksum.build_update_name(c, "202611050003").filename
        c_path.parent.mkdir()
        c_path.write_bytes(c)
        assert run(str(c_path), "--card", str(card), OVERRIDE, "--stock", str(stock_image_path)) == 1
        assert "FAIL withdrawn" in capsys.readouterr().out
        assert card_files(card) == [name.filename]


def test_undecodable_confirmation_is_not_confirmed(monkeypatch, capsys):
    import sys as _sys
    from charmera_toolkit import stage

    class Tty:
        def isatty(self):
            return True

    def bad_input(prompt=""):
        raise UnicodeDecodeError("ascii", b"\xc3", 0, 1, "ordinal not in range(128)")

    monkeypatch.setattr(_sys, "stdin", Tty())
    monkeypatch.setattr("builtins.input", bad_input)
    assert stage._confirm("/media/card") is False
    assert "Not confirmed" in capsys.readouterr().out


# --- the donor route ---------------------------------------------------------------------------------------------


@pytest.fixture
def donor_case(tmp_path, synthetic_build):
    """This camera's read, a donor's read with another sensor table, and an official-size file made from the donor."""
    backup = synthetic_image([make_record()])
    donor = bytearray(backup)
    donor[SENSOR_START + 5] ^= 0x01
    donor = bytes(donor)
    payload = pack.prepare_payload(donor, official_size=True)
    out = tmp_path / "out"
    out.mkdir()
    src = out / checksum.build_update_name(payload, STAMP).filename
    src.write_bytes(payload)
    backup_path, donor_path = tmp_path / "backup.bin", tmp_path / "donor.bin"
    backup_path.write_bytes(backup)
    donor_path.write_bytes(donor)
    return src, backup_path, donor_path


def test_stage_refuses_a_donor_file_without_the_donor_route(donor_case, card, confirmed, capsys):
    src, backup_path, donor_path = donor_case
    assert run(str(src), "--card", str(card), OVERRIDE, "--stock", str(backup_path)) == 1
    assert "FAIL sensor-table" in capsys.readouterr().out
    assert card_files(card) == [] and confirmed == []


def test_stage_donor_route_needs_the_backup(donor_case, card, confirmed, capsys):
    src, backup_path, donor_path = donor_case
    assert run(str(src), "--card", str(card), OVERRIDE, "--donor", str(donor_path)) == 2
    out = capsys.readouterr().out
    assert "--donor needs --stock" in out and "Nothing written" in out
    assert card_files(card) == [] and confirmed == []


def test_stage_donor_route_writes_and_says_the_fit_is_unknown(donor_case, card, confirmed, capsys):
    src, backup_path, donor_path = donor_case
    assert run(str(src), "--card", str(card), OVERRIDE, "--stock", str(backup_path), "--donor", str(donor_path)) == 0
    out = capsys.readouterr().out
    assert "WARN sensor-table" in out and "WARN way-back" in out and "PASS from-donor" in out
    assert "PASS vector-table" in out and "WARN software-region" in out
    assert "Donor route:" in out and "only a chip programmer can put --stock back" in out
    assert (card / src.name).read_bytes() == src.read_bytes() and confirmed == [str(card)]


def test_stage_donor_route_refuses_a_donor_with_a_broken_vector_table(donor_case, card, confirmed, tmp_path, capsys):
    """The donor's firmware goes on unchanged, so a donor whose reset vector is broken never reaches a card, whether it is
    laid out like --stock or only like a known build."""
    src, backup_path, donor_path = donor_case
    donor = bytearray(donor_path.read_bytes())
    donor[0x200:0x240] = bytes(b ^ 0xFF for b in donor[0x200:0x240])
    donor_path.write_bytes(bytes(donor))
    payload = pack.prepare_payload(bytes(donor), official_size=True)
    broken = _renamed(src, payload, "202611041230")
    moved = bytearray(backup_path.read_bytes())
    moved[0x218C3: 0x218D1] = b"\x00" * 14
    moved[0x218D0: 0x218DD] = b"202505201029\x00"              # this camera's read laid out like no known build
    moved_path = tmp_path / "moved-backup.bin"
    moved_path.write_bytes(bytes(moved))
    for stock, said in ((backup_path, "differs from --stock's"), (moved_path, "whose layout the file has")):
        assert run(str(broken), "--card", str(card), OVERRIDE, "--stock", str(stock), "--donor", str(donor_path), "--plan-only") == 1
        out = capsys.readouterr().out
        assert "FAIL vector-table" in out and said in out and "verify failed" in out and "Plan" not in out
    assert card_files(card) == [] and confirmed == []


def test_stage_warns_about_a_stock_named_like_an_unverified_read(update_file, backup, card, confirmed, capsys):
    read = backup.with_name("charmera_20261106-120000Z.unverified-read1.bin")
    read.write_bytes(backup.read_bytes())
    assert run(str(update_file), "--card", str(card), OVERRIDE, "--stock", str(read), "--plan-only") == 0
    out = " ".join(capsys.readouterr().out.split())
    assert "WARN stock-read" in out and "such a read is not a backup" in out and "plan only: nothing written." in out


# --- the gates stage inherits from verify ------------------------------------------------------------------------


def test_stage_refuses_a_stock_that_is_not_a_read_of_a_chip(update_file, card, confirmed, capsys):
    """The update file itself, or any other file that is not a complete 2 MiB read, cannot stand for the camera."""
    assert run(str(update_file), "--card", str(card), OVERRIDE, "--stock", str(update_file)) == 1
    out = capsys.readouterr().out
    assert "FAIL stock" in out and "this is an update file, not a read of a chip" in out and "Plan" not in out
    assert card_files(card) == [] and confirmed == []


def test_stage_shows_modified_software_as_a_warning_and_refuses_a_broken_vector_table(update_file, backup, card, confirmed, capsys):
    data = update_file.read_bytes()
    beep = next(e for e in image.resource_directory(data) if e.display_name == "BEEP.WAV")
    mod = bytearray(data)
    mod[beep.offset + 0x40] ^= 0x01           # one PCM byte: a mod of the resources
    src = _renamed(update_file, bytes(mod))
    assert run(str(src), "--card", str(card), OVERRIDE, "--stock", str(backup), "--plan-only") == 0
    out = capsys.readouterr().out
    assert "WARN software-region" in out and "PASS software-region" not in out and "accepted because of --allow-modified" in out
    # the same file with its vector table destroyed: stage passes --allow-modified, and still refuses it
    broken = bytearray(data)
    broken[0x200:0x240] = bytes(b ^ 0xFF for b in broken[0x200:0x240])
    src = _renamed(update_file, bytes(broken), "202611041202")
    for extra in ((), ("--stock", str(backup))):
        assert run(str(src), "--card", str(card), OVERRIDE, "--plan-only", *extra) == 1
        out = capsys.readouterr().out
        assert "FAIL vector-table" in out and "verify failed" in out and "Plan" not in out
    assert card_files(card) == [] and confirmed == []


def test_stage_refuses_every_size_but_the_two(update_file, card, confirmed, capsys):
    data = update_file.read_bytes()
    for i, n in enumerate((0x170000, 0x175000, 0x1C0000)):
        src = _renamed(update_file, data[:n], f"20261104121{i}")
        assert run(str(src), "--card", str(card), OVERRIDE, "--plan-only") == 1
        out = capsys.readouterr().out
        assert "FAIL size" in out and "verify failed" in out
    assert card_files(card) == [] and confirmed == []


def test_stage_refuses_a_withdrawn_candidate(update_file, card, confirmed, monkeypatch, capsys):
    from charmera_toolkit import verify
    monkeypatch.setitem(verify.WITHDRAWN, hashlib.sha256(update_file.read_bytes()).hexdigest(), "candidate X (a stand-in)")
    assert run(str(update_file), "--card", str(card), OVERRIDE) == 1
    assert "FAIL withdrawn" in capsys.readouterr().out
    assert card_files(card) == [] and confirmed == []


def test_the_camera_steps_give_the_safe_way_to_connect_the_camera(update_file, card, confirmed, capsys):
    """Plugging the cable into a switched-off camera switches it on and runs the updater: the app's order, here too."""
    assert run(str(update_file), "--card", str(card), OVERRIDE, "--plan-only") == 0
    low = " ".join(capsys.readouterr().out.lower().split())
    assert "plugging the cable into a switched-off camera switches it on" in low
    step = low[low.index("plugging the cable into a switched-off camera"):]
    order = ["camera off", "card out", "switch the camera on with its power key", "wait 10 seconds", "card in", "then the cable"]
    at = [step.index(s) for s in order]
    assert at == sorted(at)


def test_stage_help_says_what_stock_is_for():
    text = " ".join(parser()._subparsers._group_actions[0].choices["stage"].format_help().split())
    assert "Only a read made before the camera was ever flashed says whether a firmware fits it" in text
    assert "Without --stock stage warns" in text
