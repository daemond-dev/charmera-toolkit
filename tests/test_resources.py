"""Tests for the `resources` command: pack listing, extraction and the gated JPEG replacement.

Every image here is synthetic. The JPEGs are either built by hand (a correct marker structure around dummy
entropy data: enough for a segment walker, not decodable) or made by Pillow when a real encoder is needed.
Nothing from a Kodak or Reto firmware is embedded. The stock-image test runs only when CHARMERA_STOCK_IMAGE
points at a local dump.
"""
import argparse
import io
import struct
import zlib
from pathlib import Path

import pytest

from charmera_toolkit import image, resources

# --- synthetic files ------------------------------------------------------------------------------------------------


def seg(marker, payload):
    return bytes([0xFF, marker]) + struct.pack(">H", len(payload) + 2) + payload


def fake_jpeg(width=160, height=80, sampling=((2, 2), (1, 1), (1, 1)), sof=0xC0, precision=8, dri=None,
              jfif=True, extra=(), scan=b"\x12\x34\x56\x78" * 50, scans=None, trailing=b""):
    """A JPEG with a real marker structure (APP0, DQT, SOF, DHT, [DRI], SOS, data, EOI) around dummy scan
    bytes. `extra` adds (marker, payload) segments after APP0; `scans` overrides the components per SOS."""
    out = bytearray(b"\xff\xd8")
    if jfif:
        out += seg(0xE0, b"JFIF\x00\x01\x01\x01\x00\x60\x00\x60\x00\x00")
    for m, payload in extra:
        out += seg(m, payload)
    out += seg(0xDB, b"\x00" + bytes([16] * 64))
    comps = b"".join(bytes([i + 1, (h << 4) | v, 0 if i == 0 else 1]) for i, (h, v) in enumerate(sampling))
    out += seg(sof, struct.pack(">BHHB", precision, height, width, len(sampling)) + comps)
    out += seg(0xC4, b"\x00" + bytes([1] + [0] * 15) + b"\x00")
    if dri is not None:
        out += seg(0xDD, struct.pack(">H", dri))
    for ns in (scans or [len(sampling)]):
        out += seg(0xDA, bytes([ns]) + b"".join(bytes([i + 1, 0x00]) for i in range(ns)) + b"\x00\x3f\x00")
        out += scan
    out += b"\xff\xd9" + trailing
    return bytes(out)


def fake_wav(rate=8000, bits=8, n=300):
    data = bytes([0x80 if bits == 8 else 0x11]) * n
    return (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt "
            + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * bits // 8, bits // 8, bits)
            + b"data" + struct.pack("<I", len(data)) + data)


def gpzp(payload):
    c = zlib.compressobj(9, zlib.DEFLATED, -15, 8, zlib.Z_FIXED)
    stream = c.compress(payload) + c.flush()
    return b"GPZP" + stream + struct.pack("<II", zlib.crc32(payload) & 0xFFFFFFFF, len(payload))


ENTRIES = [
    # (directory name, file bytes, sectors of budget)
    ("AP_ICONGPZP", gpzp(bytes(range(256)) * 20), 2),
    ("AUDIO_REC_BGJPG", fake_jpeg(scan=b"\x9a\xbc" * 400), 4),
    ("BEEPWAV", fake_wav(11025, 16), 2),
    ("F_0_000GPZP", gpzp(b"\x8c" * 4000), 1),
    ("GPRSPAK", b"GPRS\x02\x00\x00\x00" + b"\x55" * 700, 2),
    ("POWER_ON_LOGOJPG", fake_jpeg(extra=[(0xE1, b"Exif\x00\x00" + b"\x00" * 24)]), 3),
    ("PV000GPZP", gpzp(b"\x71\x8c" * 400), 1),
    ("USB_CONNECTGPZP", gpzp(b"\x00" * 3000), 1),
]


def synthetic_image(entries=ENTRIES, pad=0xFF, stamps=True):
    """A fake 2 MiB image whose GP+ pack holds `entries`, each padded to its budget with `pad`."""
    img = bytearray(b"\xff" * 0x200000)
    img[0:4] = b"SPII"
    if stamps:
        b = image.ORIGINAL_2025
        img[b.stamp_offset: b.stamp_offset + 12] = b.stamp.encode()
        img[b.build_offset: b.build_offset + len(b.build_string)] = b.build_string
        img[b.compiled_offset: b.compiled_offset + len(b.compiled)] = b.compiled
    base = image.RESOURCE_BASE
    img[base: base + 0x18] = b"GP+\x00" + struct.pack("<H", len(entries)) + b"\x00" * 18
    sector = 4
    off = base + 0x18
    for name, data, sectors in entries:
        budget = sectors * 0x200
        assert len(data) <= budget, name
        img[off: off + 20] = name.encode().ljust(20, b"\x00")
        img[off + 20: off + 24] = struct.pack("<I", sector)
        p = base + sector * 0x200
        img[p: p + budget] = data + bytes([pad]) * (budget - len(data))
        off += 24
        sector += sectors
    # the pack ends where the last file ends (the last entry's budget runs to the end of the data)
    last_name, last_data, _ = entries[-1]
    end = base + (sector - entries[-1][2]) * 0x200 + len(last_data)
    img[end: 0x170000] = b"\xff" * (0x170000 - end)
    return bytes(img)


def logo_pack(jpeg, sectors):
    """An image whose logo slot holds `jpeg` with a budget of `sectors` x 512. A small entry follows it,
    because the last entry's budget runs only to the end of its own data."""
    return synthetic_image([("POWER_ON_LOGOJPG", jpeg, sectors), ("USB_CONNECTGPZP", gpzp(b"\x00" * 100), 1)])


def by_name(image_bytes):
    return {i.entry.display_name: i for i in resources.describe_all(image_bytes)}


def make_args(**kw):
    base = {"action": "list", "image": None, "out": None, "name": None, "file": None, "strip_metadata": False,
            "overwrite": False, "gate": False}
    base.update(kw)
    return type("A", (), base)


# --- JPEG walker ------------------------------------------------------------------------------------------------------


def test_parse_jpeg_structure_and_lengths():
    j = resources.parse_jpeg(fake_jpeg(trailing=b"\x00" * 9))
    assert (j.width, j.height, j.precision) == (160, 80, 8)
    assert j.baseline and j.process == "baseline (SOF0)"
    assert j.sampling_factors == [(2, 2), (1, 1), (1, 1)] and j.sampling == "2x2,1x1,1x1 (4:2:0)"
    assert j.jfif and j.restart_interval is None and j.scans == [3]
    assert j.length == len(fake_jpeg())                      # trailing bytes after EOI are not part of the file
    assert [s.name for s in j.segments] == ["FFD8", "APP0", "FFDB", "FFC0", "FFC4", "FFDA", "FFD9"]
    # a stuffed FF 00 and an FF D9 inside the scan data must not end the scan early
    tricky = fake_jpeg(scan=b"\x01\xff\x00\x02" * 10)
    assert resources.parse_jpeg(tricky).length == len(tricky)
    # restart markers are counted, DRI is read
    r = resources.parse_jpeg(fake_jpeg(dri=4, scan=b"\x01\x02\xff\xd0\x03\x04\xff\xd1\x05"))
    assert r.restart_interval == 4 and r.restart_markers == 2
    # other processes are named
    assert resources.parse_jpeg(fake_jpeg(sof=0xC2)).process == "progressive (SOF2)"
    assert resources.parse_jpeg(fake_jpeg(sof=0xC1)).process == "extended sequential (SOF1)"
    assert resources.parse_jpeg(fake_jpeg(sampling=((1, 1),))).sampling == "1x1 (greyscale)"
    assert resources.parse_jpeg(fake_jpeg(sampling=((1, 1), (1, 1), (1, 1)))).sampling == "1x1,1x1,1x1 (4:4:4)"


def test_parse_jpeg_refuses_broken_files():
    good = fake_jpeg()
    with pytest.raises(ValueError, match="FF D8"):
        resources.parse_jpeg(b"RIFF" + good[4:])
    with pytest.raises(ValueError, match="truncated"):
        resources.parse_jpeg(good[:-2])                      # no EOI
    with pytest.raises(ValueError, match="no length field"):
        resources.parse_jpeg(good[:22])                      # cut right after the DQT marker
    with pytest.raises(ValueError, match="runs past"):
        resources.parse_jpeg(good[:30])                      # cut inside the DQT payload
    with pytest.raises(ValueError, match="runs past"):
        resources.parse_jpeg(good[:20] + b"\xff\xdb\xff\xff" + good[20:])   # a segment claiming 65535 bytes
    with pytest.raises(ValueError, match="expected a marker"):
        resources.parse_jpeg(good[:60] + b"\xff\xdb\xff\xff" + good[60:])   # garbage inside the DQT payload
    with pytest.raises(ValueError, match="before the frame header"):
        resources.parse_jpeg(b"\xff\xd8" + seg(0xDA, b"\x01\x01\x00\x00\x3f\x00") + b"\x00" * 4 + b"\xff\xd9")
    with pytest.raises(ValueError, match="no frame header"):
        resources.parse_jpeg(b"\xff\xd8" + seg(0xE0, b"JFIF\x00") + b"\xff\xd9")
    with pytest.raises(ValueError, match="expected a marker"):
        resources.parse_jpeg(b"\xff\xd8\x00\x00\xff\xd9")


def test_strip_metadata_removes_app_and_com_only():
    src = fake_jpeg(extra=[(0xE1, b"Exif\x00\x00" + b"\x00" * 30), (0xEC, b"Ducky" + b"\x00" * 10), (0xFE, b"a comment")])
    before = resources.parse_jpeg(src)
    assert [s.name for s in before.metadata] == ["APP1", "APP12", "COM"] and before.metadata_bytes == 36 + 4 + 15 + 4 + 9 + 4
    out, removed = resources.strip_metadata(src)
    assert [s.name for s in removed] == ["APP1", "APP12", "COM"]
    assert out == fake_jpeg()                                # byte-identical to the file without them
    after = resources.parse_jpeg(out)
    assert after.jfif and not after.metadata and after.length == len(out)
    out2, removed2 = resources.strip_metadata(src, keep_app0=False)
    assert [s.name for s in removed2] == ["APP0", "APP1", "APP12", "COM"] and not resources.parse_jpeg(out2).app0
    # the scan data in between is untouched
    assert out.endswith(b"\x12\x34\x56\x78" * 50 + b"\xff\xd9")


# --- list ------------------------------------------------------------------------------------------------------------


def test_describe_kinds_and_lengths():
    img = synthetic_image()
    infos = by_name(img)
    assert list(infos) == ["AP_ICON.GPZP", "AUDIO_REC_BG.JPG", "BEEP.WAV", "F_0_000.GPZP", "GPRS.PAK",
                           "POWER_ON_LOGO.JPG", "PV000.GPZP", "USB_CONNECT.GPZP"]
    kinds = {n: i.kind for n, i in infos.items()}
    assert kinds["AP_ICON.GPZP"] == "GPZP" and kinds["BEEP.WAV"] == "WAV" and kinds["GPRS.PAK"] == "GPRS"
    assert kinds["AUDIO_REC_BG.JPG"] == "JPEG" and kinds["POWER_ON_LOGO.JPG"] == "JPEG"
    for name, data, sectors in ENTRIES:
        i = infos[resources.ResourceEntry(0, name, 0, 0, None).display_name]
        if i.kind == "GPRS":
            assert i.actual == i.budget                       # kept whole
        else:
            assert i.actual == len(data), name
        assert i.problem is None
        assert i.pad_byte == (0xFF if i.padding else None)
    # the last entry's budget runs to the end of the data, so it has no padding
    last = infos["USB_CONNECT.GPZP"]
    assert last.budget == last.actual and last.pad_text == "-"
    # details per kind
    assert infos["BEEP.WAV"].wav.rate == 11025 and "16-bit" in infos["BEEP.WAV"].detail
    assert "160x80 baseline (SOF0)" in infos["POWER_ON_LOGO.JPG"].detail and "4:2:0" in infos["POWER_ON_LOGO.JPG"].detail
    assert "metadata 34 B (APP1)" in infos["POWER_ON_LOGO.JPG"].detail      # 2 marker + 2 length + 30 payload
    assert infos["AP_ICON.GPZP"].gpzp.inflated_len == 5120 and infos["AP_ICON.GPZP"].gpzp.crc_ok
    assert infos["AP_ICON.GPZP"].detail == "inflates to 5120 B, CRC ok"
    assert infos["AP_ICON.GPZP"].pad_text == "FF"


def test_describe_flags_damage_and_zero_padding():
    img = bytearray(synthetic_image(pad=0x00))
    infos = by_name(bytes(img))
    assert all(i.pad_byte == 0x00 for i in infos.values() if i.padding)
    # a GPZP whose trailer CRC is wrong is reported, not raised
    e = infos["AP_ICON.GPZP"]
    crc_at = e.entry.offset + e.actual - 8
    img[crc_at] ^= 0xFF
    bad = by_name(bytes(img))["AP_ICON.GPZP"]
    assert bad.kind == "GPZP" and bad.problem and "CRC-32" in bad.problem and bad.actual == e.actual
    # a WAV whose RIFF size runs past the slot
    w = infos["BEEP.WAV"]
    img[w.entry.offset + 4: w.entry.offset + 8] = struct.pack("<I", 100000)
    badw = by_name(bytes(img))["BEEP.WAV"]
    assert badw.problem and "slot holds" in badw.problem and badw.actual == badw.budget
    # a JPEG with no EOI inside the slot
    j = infos["POWER_ON_LOGO.JPG"]
    img[j.entry.offset + j.actual - 2: j.entry.offset + j.actual] = b"\x00\x00"
    badj = by_name(bytes(img))["POWER_ON_LOGO.JPG"]
    assert badj.kind == "JPEG" and badj.jpeg is None and "truncated" in badj.problem


def test_list_handler_output_and_exit_codes(tmp_path, capsys):
    img = synthetic_image()
    src = tmp_path / "a.bin"
    src.write_bytes(img)
    assert resources.handler(make_args(image=str(src))) == 0
    out = capsys.readouterr().out
    assert "8 entries" in out and "padding after each file: 0xFF" in out
    assert "POWER_ON_LOGO.JPG" in out and "GPRS.PAK" in out and "BAD" not in out
    # damaged entry: exit 1 and a BAD line
    bad = bytearray(img)
    e = by_name(img)["F_0_000.GPZP"]
    bad[e.entry.offset + 6] ^= 0x55
    (tmp_path / "bad.bin").write_bytes(bytes(bad))
    assert resources.handler(make_args(image=str(tmp_path / "bad.bin"))) == 1
    assert "BAD:" in capsys.readouterr().out
    # not a file, no pack
    assert resources.handler(make_args(image=str(tmp_path / "missing.bin"))) == 2
    (tmp_path / "nopack.bin").write_bytes(b"\xff" * 0x200000)
    assert resources.handler(make_args(image=str(tmp_path / "nopack.bin"))) == 2
    assert "no GP+ resource pack" in capsys.readouterr().out
    (tmp_path / "short.bin").write_bytes(b"SPII" + b"\x00" * 100)
    assert resources.handler(make_args(image=str(tmp_path / "short.bin"))) == 2


# --- extract ---------------------------------------------------------------------------------------------------------


def test_extract_trims_and_writes_sidecars(tmp_path):
    img = synthetic_image()
    out = tmp_path / "res"
    paths = resources.extract(img, out)
    names = sorted(p.name for p in paths)
    assert names == sorted(["AP_ICON.GPZP", "AP_ICON.GPZP.inflated", "AUDIO_REC_BG.JPG", "BEEP.WAV", "F_0_000.GPZP",
                            "F_0_000.GPZP.inflated", "GPRS.PAK", "POWER_ON_LOGO.JPG", "PV000.GPZP", "PV000.GPZP.inflated",
                            "USB_CONNECT.GPZP", "USB_CONNECT.GPZP.inflated"])
    files = {name: data for name, data, _ in ENTRIES}
    assert (out / "POWER_ON_LOGO.JPG").read_bytes() == files["POWER_ON_LOGOJPG"]
    assert (out / "BEEP.WAV").read_bytes() == files["BEEPWAV"]
    assert (out / "AP_ICON.GPZP").read_bytes() == files["AP_ICONGPZP"]
    assert (out / "AP_ICON.GPZP.inflated").read_bytes() == bytes(range(256)) * 20
    assert (out / "F_0_000.GPZP.inflated").read_bytes() == b"\x8c" * 4000
    assert len((out / "GPRS.PAK").read_bytes()) == 2 * 0x200           # the whole budget
    assert not any(p.read_bytes().endswith(b"\xff\xff\xff\xff") for p in paths if p.suffix != ".PAK")


def test_extract_one_and_refuse_overwrite(tmp_path, capsys):
    img = synthetic_image()
    src = tmp_path / "a.bin"
    src.write_bytes(img)
    out = tmp_path / "one"
    assert resources.handler(make_args(action="extract", image=str(src), out=str(out), name="power_on_logo.jpg")) == 0
    assert [p.name for p in out.iterdir()] == ["POWER_ON_LOGO.JPG"]
    marker = (out / "POWER_ON_LOGO.JPG").read_bytes()
    # second run refuses before writing anything, and the directory name form works too
    assert resources.handler(make_args(action="extract", image=str(src), out=str(out), name="POWER_ON_LOGOJPG")) == 2
    assert "already exist" in capsys.readouterr().out and (out / "POWER_ON_LOGO.JPG").read_bytes() == marker
    # a full extract into that directory also refuses, leaving only the one file there
    assert resources.handler(make_args(action="extract", image=str(src), out=str(out))) == 2
    assert [p.name for p in out.iterdir()] == ["POWER_ON_LOGO.JPG"]
    assert resources.handler(make_args(action="extract", image=str(src), out=str(out), overwrite=True)) == 0
    assert len(list(out.iterdir())) == 12
    # unknown name, missing --out
    assert resources.handler(make_args(action="extract", image=str(src), out=str(tmp_path / "x"), name="NOPE.JPG")) == 2
    assert "no entry named NOPE.JPG" in capsys.readouterr().out
    assert resources.handler(make_args(action="extract", image=str(src))) == 2


def test_safe_filename():
    assert resources.safe_filename("POWER_ON_LOGO.JPG") == "POWER_ON_LOGO.JPG"
    assert resources.safe_filename("../x/y") == "_x_y"
    assert "/" not in resources.safe_filename("a/b\\c") and resources.safe_filename("") == "entry"


# --- replace ---------------------------------------------------------------------------------------------------------


def test_replace_happy_path_preserves_everything_else():
    img = synthetic_image()
    old = by_name(img)["POWER_ON_LOGO.JPG"]
    new_jpeg = fake_jpeg(scan=b"\xaa\xbb\xcc" * 30)           # shorter than the stock file, same geometry
    assert len(new_jpeg) < old.actual
    out, plan = resources.replace_jpeg(img, "POWER_ON_LOGO.JPG", new_jpeg)
    off = old.entry.offset
    assert out[off: off + len(new_jpeg)] == new_jpeg
    assert out[off + len(new_jpeg): off + old.budget] == b"\xff" * (old.budget - len(new_jpeg))   # padding preserved
    assert plan.pad_byte == 0xFF and not plan.pad_assumed and plan.gap == old.actual - len(new_jpeg)
    assert out[:off] == img[:off] and out[off + old.budget:] == img[off + old.budget:]
    # directory unchanged: same names, offsets and budgets
    before = [(e.name, e.offset, e.budget) for e in image.resource_directory(img)]
    after = [(e.name, e.offset, e.budget) for e in image.resource_directory(out)]
    assert before == after
    assert by_name(out)["POWER_ON_LOGO.JPG"].actual == len(new_jpeg)
    # a longer file that still fits overwrites part of the padding and leaves the rest
    bigger = fake_jpeg(scan=b"\x01\x02" * 500)
    assert old.actual < len(bigger) <= old.budget
    out2, plan2 = resources.replace_jpeg(img, "POWER_ON_LOGOJPG", bigger)
    assert plan2.gap == 0 and out2[off: off + len(bigger)] == bigger
    assert out2[off + len(bigger): off + old.budget] == b"\xff" * (old.budget - len(bigger))
    # zero padding is copied as zero
    zimg = synthetic_image(pad=0x00)
    zout, zplan = resources.replace_jpeg(zimg, "POWER_ON_LOGO.JPG", new_jpeg)
    assert zplan.pad_byte == 0x00 and zout[off + len(new_jpeg): off + old.budget] == b"\x00" * (old.budget - len(new_jpeg))


def test_replace_refusals_name_the_property():
    img = synthetic_image()

    def refused(data, name="POWER_ON_LOGO.JPG", strip=False):
        with pytest.raises(ValueError) as exc:
            resources.plan_replace(img, name, data, strip)
        return str(exc.value)

    assert refused(fake_jpeg(sof=0xC2)).startswith("process: the file is progressive (SOF2)")
    assert refused(fake_jpeg(sof=0xC1)).startswith("process: the file is extended sequential (SOF1)")
    assert refused(fake_jpeg(width=80, height=40)).startswith("size: 80x40; the slot's file is 160x80")
    assert refused(fake_jpeg(sampling=((1, 1), (1, 1), (1, 1)))).startswith("sampling: 1x1,1x1,1x1 (4:4:4); the stock file is 2x2,1x1,1x1 (4:2:0)")
    assert refused(fake_jpeg(sampling=((2, 1), (1, 1), (1, 1)))).startswith("sampling: 2x1,1x1,1x1 (4:2:2)")
    assert refused(fake_jpeg(sampling=((1, 1),))).startswith("components: 1; the stock file has 3")
    assert refused(fake_jpeg(dri=8)).startswith("restart interval: the file has a DRI segment (interval 8)")
    assert refused(fake_jpeg(scan=b"\x01\xff\xd0\x02")).startswith("restart markers: 1 RSTn")
    assert refused(fake_jpeg(scans=[1, 1, 1])).startswith("scan layout: 3 scan(s) of [1, 1, 1] components")
    too_big = fake_jpeg(scan=b"\x01" * 2000, extra=[(0xE1, b"Exif\x00\x00" + b"\x00" * 100)])
    msg = refused(too_big)
    assert msg.startswith(f"length: {len(too_big)} B but the slot holds 1536 B") and "--strip-metadata would save 110 B" in msg
    assert refused(b"RIFF" + b"\x00" * 40).startswith("replacement file: not a JPEG")
    assert refused(fake_jpeg()[:-1]).startswith("replacement file: the JPEG ends inside its entropy-coded data (truncated")
    assert refused(fake_jpeg()[:22]).startswith("replacement file: segment FFDB at offset 20 has no length field")
    # non-JPEG slots point at the right command or refuse
    assert "charmera sounds replace" in refused(fake_jpeg(), "BEEP.WAV")
    assert "photo frame 0" in refused(fake_jpeg(), "F_0_000.GPZP") and "charmera frames replace" in refused(fake_jpeg(), "F_0_000.GPZP")
    assert "photo frame 0" in refused(fake_jpeg(), "PV000.GPZP")
    assert "no known-safe way" in refused(fake_jpeg(), "AP_ICON.GPZP")
    assert "no known-safe way" in refused(fake_jpeg(), "GPRS.PAK")
    assert "no entry named NOPE" in refused(fake_jpeg(), "NOPE")
    # a slot whose JPEG is damaged cannot be used as the reference
    broken = bytearray(img)
    e = by_name(img)["AUDIO_REC_BG.JPG"]
    broken[e.entry.offset + e.actual - 2: e.entry.offset + e.actual] = b"\x00\x00"
    with pytest.raises(ValueError, match="not a JPEG slot"):
        resources.plan_replace(bytes(broken), "AUDIO_REC_BG.JPG", fake_jpeg())


def test_replace_strip_metadata_and_trailing_bytes():
    img = synthetic_image()
    old = by_name(img)["POWER_ON_LOGO.JPG"]
    src = fake_jpeg(scan=b"\x01" * 1200, extra=[(0xE1, b"Exif\x00\x00" + b"\x00" * 200), (0xFE, b"comment")], trailing=b"junk")
    with pytest.raises(ValueError, match="^length"):
        resources.plan_replace(img, "POWER_ON_LOGO.JPG", src)
    plan = resources.plan_replace(img, "POWER_ON_LOGO.JPG", src, strip=True)
    assert [s.name for s in plan.removed] == ["APP1", "COM"] and plan.trailing_dropped == 4
    assert plan.data == fake_jpeg(scan=b"\x01" * 1200) and len(plan.data) <= old.budget
    assert plan.new.jfif and not plan.new.metadata
    # when the slot's file has no JFIF APP0, the APP0 of the new file goes too
    nojfif = logo_pack(fake_jpeg(jfif=False), 3)
    plan2 = resources.plan_replace(nojfif, "POWER_ON_LOGO.JPG", fake_jpeg(extra=[(0xFE, b"x")]), strip=True)
    assert [s.name for s in plan2.removed] == ["APP0", "COM"] and not plan2.new.app0


def test_replace_handler_gate_build_and_output_rules(tmp_path, capsys):
    img = synthetic_image()
    src = tmp_path / "in.bin"
    src.write_bytes(img)
    jpg = tmp_path / "logo.jpg"
    jpg.write_bytes(fake_jpeg(scan=b"\x42" * 100))
    out = tmp_path / "out.bin"
    base = dict(action="replace", image=str(src), name="POWER_ON_LOGO.JPG", file=str(jpg), out=str(out))
    # missing pieces
    assert resources.handler(make_args(**dict(base, name=None))) == 2
    assert resources.handler(make_args(**dict(base, file=None))) == 2
    assert resources.handler(make_args(**dict(base, out=None))) == 2
    assert resources.handler(make_args(**dict(base, file=str(tmp_path / "missing.jpg")))) == 2
    # dry run: plan printed, nothing written
    assert resources.handler(make_args(**base)) == 0
    text = capsys.readouterr().out
    assert "dry run" in text and "replace POWER_ON_LOGO.JPG" in text and "checks:" in text and "not been tried on a camera" in text
    assert not out.exists()
    # gated: written, slot changed, rest identical
    assert resources.handler(make_args(**base, gate=True)) == 0
    assert "wrote" in capsys.readouterr().out
    written = out.read_bytes()
    e = by_name(img)["POWER_ON_LOGO.JPG"]
    assert written[e.entry.offset: e.entry.offset + 100 + 2] != img[e.entry.offset: e.entry.offset + 102]
    assert written[: e.entry.offset] == img[: e.entry.offset] and written[e.entry.offset + e.budget:] == img[e.entry.offset + e.budget:]
    assert src.read_bytes() == img
    # existing output, output == input
    assert resources.handler(make_args(**base, gate=True)) == 2
    assert "already exists" in capsys.readouterr().out
    assert resources.handler(make_args(**dict(base, out=str(src)), gate=True)) == 2
    assert src.read_bytes() == img
    assert resources.handler(make_args(**base, gate=True, overwrite=True)) == 0
    # a refusal from the checks is exit 2 with the property named
    (tmp_path / "prog.jpg").write_bytes(fake_jpeg(sof=0xC2))
    assert resources.handler(make_args(**dict(base, file=str(tmp_path / "prog.jpg"), out=str(tmp_path / "p.bin")), gate=True)) == 2
    assert "error: process:" in capsys.readouterr().out and not (tmp_path / "p.bin").exists()
    # unknown build: refused even with the gate
    unknown = tmp_path / "unknown.bin"
    unknown.write_bytes(synthetic_image(stamps=False))
    assert resources.handler(make_args(**dict(base, image=str(unknown), out=str(tmp_path / "u.bin")), gate=True)) == 2
    assert "not the original 2025 series" in capsys.readouterr().out and not (tmp_path / "u.bin").exists()
    # list and extract still work on that image
    assert resources.handler(make_args(image=str(unknown))) == 0


def test_register_wires_the_flags():
    parser = argparse.ArgumentParser(prog="charmera")
    sub = parser.add_subparsers(dest="command")
    resources.register(sub)
    a = parser.parse_args(["resources", "replace", "x.bin", "--name", "POWER_ON_LOGO.JPG", "--file", "l.jpg", "--out", "n.bin",
                           "--strip-metadata", "--overwrite", "--i-understand-this-is-a-mod"])
    assert (a.action, a.image, a.name, a.file, a.out) == ("replace", "x.bin", "POWER_ON_LOGO.JPG", "l.jpg", "n.bin")
    assert a.strip_metadata and a.overwrite and a.gate and a.func is resources.handler
    b = parser.parse_args(["resources", "list", "x.bin"])
    assert b.action == "list" and not b.gate and b.name is None
    with pytest.raises(SystemExit):
        parser.parse_args(["resources", "frobnicate", "x.bin"])


# --- real encoder output (Pillow) -----------------------------------------------------------------------------------------


def pil_jpeg(size=(160, 80), quality=80, subsampling=2, progressive=False, **kw):
    PIL_Image = pytest.importorskip("PIL.Image")
    im = PIL_Image.new("RGB", size)
    px = im.load()
    for y in range(size[1]):
        for x in range(size[0]):
            px[x, y] = ((x * 255) // max(1, size[0] - 1), (y * 255) // max(1, size[1] - 1), (x * y) % 256)
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=quality, subsampling=subsampling, progressive=progressive, optimize=True, **kw)
    return buf.getvalue()


def test_pillow_jpeg_is_accepted_and_decodable_after_strip():
    PIL_Image = pytest.importorskip("PIL.Image")
    img = synthetic_image()
    old = by_name(img)["POWER_ON_LOGO.JPG"]
    plain = pil_jpeg()
    j = resources.parse_jpeg(plain)
    assert j.baseline and (j.width, j.height) == (160, 80) and j.sampling_factors == [(2, 2), (1, 1), (1, 1)] and j.jfif
    assert j.length == len(plain)
    # give it the budget of the slot: the real file may be bigger than the hand-made one
    big_slot = logo_pack(fake_jpeg(), 8)
    assert by_name(big_slot)["POWER_ON_LOGO.JPG"].budget == 4096
    out, plan = resources.replace_jpeg(big_slot, "POWER_ON_LOGO.JPG", plain)
    e = by_name(big_slot)["POWER_ON_LOGO.JPG"]
    assert out[e.entry.offset: e.entry.offset + len(plain)] == plain
    assert by_name(out)["POWER_ON_LOGO.JPG"].jpeg.sampling == "2x2,1x1,1x1 (4:2:0)"
    # metadata: an Exif block and a comment are stripped and the pixels survive
    with_meta = pil_jpeg(exif=b"Exif\x00\x00MM\x00\x2a\x00\x00\x00\x08\x00\x00\x00\x00" + b"\x00" * 400, comment=b"hello there")
    jm = resources.parse_jpeg(with_meta)
    assert {s.name for s in jm.metadata} >= {"APP1", "COM"} and len(with_meta) > len(plain)
    stripped, removed = resources.strip_metadata(with_meta)
    assert len(stripped) < len(with_meta) and not resources.parse_jpeg(stripped).metadata
    a = PIL_Image.open(io.BytesIO(stripped)); a.load()
    b = PIL_Image.open(io.BytesIO(with_meta)); b.load()
    assert a.size == (160, 80) and a.tobytes() == b.tobytes()
    plan2 = resources.plan_replace(big_slot, "POWER_ON_LOGO.JPG", with_meta, strip=True)
    assert plan2.data == stripped and [s.name for s in plan2.removed] == [s.name for s in removed]
    # a tight slot refuses before stripping and accepts after, when the stripped file fits
    tight = logo_pack(fake_jpeg(), (len(stripped) + 0x1FF) // 0x200)
    if len(with_meta) > by_name(tight)["POWER_ON_LOGO.JPG"].budget:
        with pytest.raises(ValueError, match="^length"):
            resources.plan_replace(tight, "POWER_ON_LOGO.JPG", with_meta)
    assert resources.plan_replace(tight, "POWER_ON_LOGO.JPG", with_meta, strip=True).data == stripped
    # replacement image decodes
    c = PIL_Image.open(io.BytesIO(out[e.entry.offset: e.entry.offset + len(plain)])); c.load()
    assert c.size == (160, 80)


def test_pillow_variants_are_refused():
    pytest.importorskip("PIL.Image")
    img = logo_pack(fake_jpeg(), 16)

    def refused(data):
        with pytest.raises(ValueError) as exc:
            resources.plan_replace(img, "POWER_ON_LOGO.JPG", data)
        return str(exc.value)

    assert refused(pil_jpeg(progressive=True)).startswith("process: the file is progressive (SOF2)")
    assert refused(pil_jpeg(size=(160, 120))).startswith("size: 160x120")
    assert refused(pil_jpeg(subsampling=0)).startswith("sampling: 1x1,1x1,1x1 (4:4:4)")
    assert refused(pil_jpeg(subsampling=1)).startswith("sampling: 2x1,1x1,1x1 (4:2:2)")
    PIL_Image = pytest.importorskip("PIL.Image")
    grey = io.BytesIO()
    PIL_Image.new("L", (160, 80), 128).save(grey, "JPEG", quality=80)
    assert refused(grey.getvalue()).startswith("components: 1; the stock file has 3 (colour, not greyscale)")
    # too big for a 1536-byte slot
    small = synthetic_image()
    with pytest.raises(ValueError, match="^length"):
        resources.plan_replace(small, "POWER_ON_LOGO.JPG", pil_jpeg(quality=100))


# --- against the real stock image -------------------------------------------------------------------------------------------


def test_stock_image_resources(stock_image):
    infos = resources.describe_all(stock_image)
    assert len(infos) == 43
    assert all(i.problem is None for i in infos), [i.entry.display_name for i in infos if i.problem]
    kinds = {}
    for i in infos:
        kinds[i.kind] = kinds.get(i.kind, 0) + 1
    assert kinds == {"GPZP": 35, "JPEG": 2, "WAV": 5, "GPRS": 1}
    for i in infos:
        if i.kind == "GPZP":
            assert i.gpzp.crc_ok and i.gpzp.length_ok, i.entry.display_name
        if i.padding:
            assert i.pad_byte == 0xFF, i.entry.display_name
    byn = {i.entry.display_name: i for i in infos}
    for name, off, budget, length in (("AUDIO_REC_BG.JPG", 0x120800, 4608, 4358), ("POWER_ON_LOGO.JPG", 0x161600, 3584, 3532)):
        i = byn[name]
        assert (i.entry.offset, i.budget, i.actual) == (off, budget, length)
        j = i.jpeg
        assert j.baseline and (j.width, j.height) == (160, 80) and len(j.components) == 3
        assert j.sampling_factors == [(2, 2), (1, 1), (1, 1)] and j.jfif and j.restart_interval is None and j.scans == [3]
    assert byn["GPRS.PAK"].actual == byn["GPRS.PAK"].budget == 123392
    assert byn["BEEP.WAV"].actual == 4076 and byn["USB_CONNECT.GPZP"].padding == 0
    # the stock logo fits the background slot and passes every check; nothing else in the image changes
    logo = stock_image[byn["POWER_ON_LOGO.JPG"].entry.offset: byn["POWER_ON_LOGO.JPG"].entry.offset + 3532]
    out, plan = resources.replace_jpeg(stock_image, "AUDIO_REC_BG.JPG", logo)
    off = byn["AUDIO_REC_BG.JPG"].entry.offset
    assert plan.gap == 4358 - 3532 and out[off: off + 3532] == logo
    assert out[off + 3532: off + 4608] == b"\xff" * (4608 - 3532)
    assert out[:off] == stock_image[:off] and out[off + 4608:] == stock_image[off + 4608:]
    # the background is too big for the logo slot, and fits after stripping its metadata
    bg = stock_image[off: off + 4358]
    with pytest.raises(ValueError, match="^length: 4358 B but the slot holds 3584 B"):
        resources.plan_replace(stock_image, "POWER_ON_LOGO.JPG", bg)
    stripped, removed = resources.strip_metadata(logo)
    assert [s.name for s in removed] == ["APP1", "APP12", "APP1"] and len(stripped) == 3532 - (36 + 19 + 833)
    assert resources.parse_jpeg(stripped).jfif
