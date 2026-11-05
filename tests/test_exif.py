"""exif-fix: the JPEG EXIF rebuild, the AVI date rewrite, naming/dedup and the command itself.

Pillow (if installed) only generates input JPEGs and double-checks the rebuilt EXIF with an independent
reader; the module under test never imports it. ffmpeg/ffprobe (if on PATH) generate a real AVI and
validate the patched copy; a hand-built RIFF covers the same logic when they are absent.
"""
from __future__ import annotations

import io
import os
import re
import shutil
import struct
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import pytest

from charmera_toolkit import cli
from charmera_toolkit import exif as ex

FALLBACK = datetime(2026, 3, 3, 12, 16, 29)
RECORDED = datetime(2026, 8, 7, 18, 45, 12)
NAME_RE = re.compile(r"^(IMG|VID)_\d{8}_\d{6}_[0-9a-f]{16}\.(jpg|avi|mp4)$")
CAMERA_DATE = b"2026:03:03:12:16:29\x00"  # the all-colons form, 20 bytes with the NUL

# Where the two internally stored photos sit in the public stock dumps (docs/02-RECOVERY.md); each ends at the
# first FF D9 after its start.
STOCK_PHOTO_OFFSETS = (0x180000, 0x1A5800)


# ---- helpers -----------------------------------------------------------------------------------------


def make_jpeg(width: int = 64, height: int = 48, comment: bytes = b"", **save_kw) -> bytes:
    """A baseline JPEG with a JFIF APP0 and no EXIF, like a freshly encoded frame."""
    Image = pytest.importorskip("PIL.Image")
    img = Image.new("RGB", (width, height), (200, 30, 30))
    buf = io.BytesIO()
    if comment:
        img.save(buf, "JPEG", quality=80, comment=comment, **save_kw)
    else:
        img.save(buf, "JPEG", quality=80, **save_kw)
    return buf.getvalue()


def with_exif(jpeg: bytes, payload: bytes) -> bytes:
    return ex.replace_exif(jpeg, ex.scan_jpeg(jpeg), payload)


def exif_of(jpeg: bytes) -> ex.ExifInfo:
    layout = ex.scan_jpeg(jpeg)
    assert layout.exif_found
    return ex.read_exif(jpeg[layout.exif_start + 10 : layout.exif_end])


def set_mtime(path: Path, when: datetime) -> None:
    ts = when.timestamp()
    os.utime(path, (ts, ts))


def segments(jpeg: bytes):
    """(marker, start, end) for every marker segment up to and including SOS, whose end is the end of the file."""
    out = []
    pos = 2
    while pos + 1 < len(jpeg):
        assert jpeg[pos] == 0xFF, hex(pos)
        marker = jpeg[pos + 1]
        if marker == 0xDA:
            out.append((marker, pos, len(jpeg)))
            break
        end = pos + 2 + struct.unpack(">H", jpeg[pos + 2 : pos + 4])[0]
        out.append((marker, pos, end))
        pos = end
    return out


def build_tiff(order: str, ifd0, exif_ifd) -> bytes:
    """A TIFF in the given byte order (">" or "<") from (tag, type, count, value bytes) entries; values over
    4 bytes go out of line, 4-byte values are written inline as given (so a raw offset can be planted)."""

    def ifd_size(entries):
        return 2 + 12 * len(entries) + 4

    exif_off = 8 + ifd_size(ifd0 + [(ex.TAG_EXIF_IFD, ex.TYPE_LONG, 1, b"")])
    ifd0 = sorted(ifd0 + [(ex.TAG_EXIF_IFD, ex.TYPE_LONG, 1, struct.pack(order + "I", exif_off))])
    ext_base = exif_off + ifd_size(exif_ifd)
    ext = bytearray()
    out = bytearray((b"MM" if order == ">" else b"II") + struct.pack(order + "HI", 0x2A, 8))

    def write(entries):
        out.extend(struct.pack(order + "H", len(entries)))
        for tag, typ, count, value in sorted(entries):
            out.extend(struct.pack(order + "HHI", tag, typ, count))
            if len(value) <= 4:
                out.extend(value.ljust(4, b"\x00"))
            else:
                out.extend(struct.pack(order + "I", ext_base + len(ext)))
                ext.extend(value + (b"\x00" if len(value) % 2 else b""))
        out.extend(struct.pack(order + "I", 0))

    write(ifd0)
    write(exif_ifd)
    return bytes(out) + bytes(ext)


def tiff_big_endian(ifd0, exif_ifd) -> bytes:
    return build_tiff(">", ifd0, exif_ifd)


def charmera_tiff(date: bytes = CAMERA_DATE, orientation: int = 1) -> bytes:
    """The camera's EXIF block: little-endian, Generalplus/CBB3, 640x480, YCbCrPositioning 2, the all-colons
    dates, a MakerNote whose offset is exactly the TIFF length (one past the end) and an InteropIFD pointer
    past the end."""

    def build(tiff_len: int) -> bytes:
        return build_tiff(
            "<",
            [
                (ex.TAG_MAKE, ex.TYPE_ASCII, 12, b"Generalplus\x00"),
                (ex.TAG_MODEL, ex.TYPE_ASCII, 5, b"CBB3\x00"),
                (ex.TAG_ORIENTATION, ex.TYPE_SHORT, 1, struct.pack("<H", orientation)),
                (ex.TAG_DATETIME, ex.TYPE_ASCII, 20, date),
                (ex.TAG_YCBCR_POSITIONING, ex.TYPE_SHORT, 1, struct.pack("<H", 2)),
            ],
            [
                (ex.TAG_DATETIME_ORIGINAL, ex.TYPE_ASCII, 20, date),
                (ex.TAG_DATETIME_DIGITIZED, ex.TYPE_ASCII, 20, date),
                (0x927C, ex.TYPE_UNDEFINED, 64, struct.pack("<I", tiff_len)),  # MakerNote at the end: nothing there
                (ex.TAG_PIXEL_X, ex.TYPE_LONG, 1, struct.pack("<I", 640)),
                (ex.TAG_PIXEL_Y, ex.TYPE_LONG, 1, struct.pack("<I", 480)),
                (0xA005, ex.TYPE_LONG, 1, struct.pack("<I", tiff_len + 1000)),  # InteropIFD past the end
            ],
        )

    tiff = build(0)
    tiff = build(len(tiff))  # the inline offset does not change the length
    makernote_entry = tiff.index(struct.pack("<HHI", 0x927C, ex.TYPE_UNDEFINED, 64))
    assert struct.unpack("<I", tiff[makernote_entry + 8 : makernote_entry + 12])[0] == len(tiff)
    return tiff


def charmera_jpeg(width: int = 64, height: int = 48, tiff: bytes = b"") -> bytes:
    """A JPEG in the camera's own segment order, built from a Pillow JPEG: SOI, SOF0, COM "GPEncoder" (length
    0x11), DQT, DQT, DRI, four DHTs, the Exif APP1 right before SOS; no APP0."""
    plain = make_jpeg(width, height, restart_marker_rows=1)
    by_marker = {}
    for marker, start, end in segments(plain):
        by_marker.setdefault(marker, []).append(plain[start:end])
    assert len(by_marker[0xC0]) == 1 and len(by_marker[0xDB]) == 2 and len(by_marker[0xC4]) == 4, sorted(by_marker)
    dri = by_marker.get(0xDD, [b"\xff\xdd\x00\x04\x00\x00"])[0]  # older Pillow: a DRI with interval 0
    com = b"\xff\xfe\x00\x11" + ex.GPENCODER_SIGNATURE.ljust(0x11 - 2, b"\x00")
    tiff = tiff or charmera_tiff()
    app1 = b"\xff\xe1" + struct.pack(">H", len(ex.EXIF_HEADER) + len(tiff) + 2) + ex.EXIF_HEADER + tiff
    return b"".join(
        [b"\xff\xd8", by_marker[0xC0][0], com, *by_marker[0xDB], dri, *by_marker[0xC4], app1, by_marker[0xDA][0]]
    )


def check_camera_jpeg(jpeg: bytes, fallback: datetime = FALLBACK):
    """The assertions shared by the synthetic camera-order fixture and the stock image's stored photos."""
    Image = pytest.importorskip("PIL.Image")
    segs = segments(jpeg)
    markers = [m for m, _, _ in segs]
    assert 0xE0 not in markers and markers[0] == 0xC0 and markers[-1] == 0xDA
    last_dht_end = max(end for m, _, end in segs if m == 0xC4)
    app1 = [(start, end) for m, start, end in segs if m == 0xE1]
    assert len(app1) == 1 and app1[0][0] >= last_dht_end  # the APP1 comes after the tables
    sos_start = segs[-1][1]
    assert app1[0][1] == sos_start  # immediately before SOS

    before = ex.scan_jpeg(jpeg)
    assert before.exif_found and (before.exif_start, before.exif_end) == app1[0]  # exif_start is after the DHTs

    info = ex.source_exif(jpeg, before)
    assert info.present and info.make == "Generalplus" and info.model == "CBB3"
    assert re.match(r"^\d{4}:\d{2}:\d{2}[: ]\d{2}:\d{2}:\d{2}$", info.date_time_original)
    assert ex.is_charmera_jpeg(jpeg)

    out, res = ex.fix_jpeg(jpeg, fallback)
    after = ex.scan_jpeg(out)
    assert after.exif_found and after.exif_start == before.exif_start  # the new APP1 sits where the old one was
    assert out[: before.exif_start] == jpeg[: before.exif_start]
    assert out[after.exif_end :] == jpeg[before.exif_end :]
    assert (res.old_make, res.old_model) == ("Generalplus", "CBB3")
    assert res.date_source == "exif" and ex._VALID_DATE_RE.match(res.date_time)
    assert (res.width, res.height) == (before.width, before.height)
    rebuilt = exif_of(out)
    assert (rebuilt.make, rebuilt.model, rebuilt.ycbcr_positioning) == ("Kodak", "Charmera", info.ycbcr_positioning or 1)

    fixed = Image.open(io.BytesIO(out))
    fixed.load()
    original = Image.open(io.BytesIO(jpeg))
    original.load()
    assert fixed.tobytes() == original.tobytes()
    assert fixed.getexif()[ex.TAG_MAKE] == "Kodak"
    return out, res


def riff_chunk(cid: bytes, data: bytes) -> bytes:
    return cid + struct.pack("<I", len(data)) + data + (b"\x00" if len(data) % 2 else b"")


def riff_list(kind: bytes, *children: bytes) -> bytes:
    body = kind + b"".join(children)
    return b"LIST" + struct.pack("<I", len(body)) + body


BOGUS_IDIT = b"Tue Jun 29 12:00:00 2010\n\x00"
BOGUS_ICRD = b"2010-06-29\x00"


def minimal_avi(idit: bytes = BOGUS_IDIT, icrd: bytes = BOGUS_ICRD) -> bytes:
    """The shape of a Charmera video: MJPG 1440x1080 + PCM, IDIT in hdrl (asctime) and ICRD in INFO (ISO),
    exactly as the C# importer's sample builder lays it out."""
    avih = bytearray(56)
    struct.pack_into("<I", avih, 0, 33_333)
    struct.pack_into("<I", avih, 16, 90)
    struct.pack_into("<I", avih, 24, 2)
    struct.pack_into("<I", avih, 32, 1440)
    struct.pack_into("<I", avih, 36, 1080)
    vstrh = bytearray(56)
    vstrh[0:8] = b"vidsMJPG"
    vstrf = bytearray(40)
    struct.pack_into("<III", vstrf, 0, 40, 1440, 1080)
    astrh = bytearray(56)
    astrh[0:4] = b"auds"
    astrf = struct.pack("<HHIIHH", 1, 1, 22050, 44100, 2, 16)
    hdrl = [
        riff_chunk(b"avih", bytes(avih)),
        riff_list(b"strl", riff_chunk(b"strh", bytes(vstrh)), riff_chunk(b"strf", bytes(vstrf))),
        riff_list(b"strl", riff_chunk(b"strh", bytes(astrh)), riff_chunk(b"strf", astrf)),
    ]
    if idit:
        hdrl.append(riff_chunk(b"IDIT", idit))
    frame = b"\xff\xd8" + bytes(range(256)) * 4 + b"\xff\xd9"
    top = [riff_list(b"hdrl", *hdrl)]
    if icrd:
        top.append(riff_list(b"INFO", riff_chunk(b"ICRD", icrd)))
    top.append(riff_list(b"movi", riff_chunk(b"01wb", bytes(64)), riff_chunk(b"00dc", frame), riff_chunk(b"00dc", frame)))
    top.append(riff_chunk(b"idx1", bytes(16)))
    body = b"".join(top)
    return b"RIFF" + struct.pack("<I", 4 + len(body)) + b"AVI " + body


def inject_dates(avi: bytes, idit: bytes = BOGUS_IDIT, icrd: bytes = BOGUS_ICRD) -> bytes:
    """Add the camera's date chunks to an AVI that has none: IDIT at the end of LIST hdrl, a LIST INFO with
    ICRD at the end of the RIFF, with the list and RIFF sizes updated."""
    assert ex.is_avi(avi[:12])
    end = min(len(avi), 8 + struct.unpack("<I", avi[4:8])[0])
    out = bytearray(avi[:12])
    pos = 12
    while pos + 8 <= end:
        cid = avi[pos : pos + 4]
        size = struct.unpack("<I", avi[pos + 4 : pos + 8])[0]
        body = avi[pos + 8 : pos + 8 + size]
        if cid == b"LIST" and body[:4] == b"hdrl":
            body += riff_chunk(b"IDIT", idit)
            out += b"LIST" + struct.pack("<I", len(body)) + body
        else:
            out += avi[pos : pos + 8 + size + (size & 1)]
        pos += 8 + size + (size & 1)
    out += riff_list(b"INFO", riff_chunk(b"ICRD", icrd))
    out[4:8] = struct.pack("<I", len(out) - 8)
    return bytes(out)


def ffmpeg_avi(path: Path) -> None:
    """A 0.3 s MJPEG + PCM AVI, the Charmera's codecs, from ffmpeg's test sources."""
    subprocess.run(
        [
            "ffmpeg", "-y", "-nostdin", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=size=64x48:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=22050",
            "-t", "0.3", "-c:v", "mjpeg", "-c:a", "pcm_s16le", "-ac", "1", str(path),
        ],
        check=True,
    )


def ffprobe_tags(path: Path) -> dict:
    proc = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=nb_streams:format_tags", "-of", "default=noprint_wrappers=1", str(path)],
        capture_output=True, text=True, check=True,
    )
    return dict(line.split("=", 1) for line in proc.stdout.splitlines() if "=" in line)


def card(tmp_path: Path) -> Path:
    root = tmp_path / "card"
    (root / "DCIM").mkdir(parents=True)
    (root / "SPIDCIM").mkdir()
    return root


def run_cli(*args: str) -> int:
    return cli.main(["exif-fix", *args])


# ---- dates ---------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "raw, want",
    [
        ("2026:03:03:12:16:29", "2026:03:03 12:16:29"),  # the camera's extra colon
        ("2026:03:03 12:16:29", "2026:03:03 12:16:29"),  # already valid
        ("2026:03:03 12:16:29\x00   ", "2026:03:03 12:16:29"),  # padding
        ("0000:00:00 00:00:00", None),
        ("", None),
        ("garbage", None),
    ],
)
def test_normalize_exif_date(raw, want):
    assert ex.normalize_exif_date(raw) == want


@pytest.mark.parametrize(
    "raw, ymd",
    [
        ("Tue Jun 29 12:00:00 2010\n", (2010, 6, 29)),
        ("Wed Jun  9 12:00:00 2010", (2010, 6, 9)),
        ("Mon Jun 29 12:00:00 2010", (2010, 6, 29)),  # wrong weekday: still read
        ("2010:06:29 12:00:00", (2010, 6, 29)),
        ("2010-06-29\x00", (2010, 6, 29)),
        ("2010-06-29T12:00:00", (2010, 6, 29)),
        ("2010/06/29", (2010, 6, 29)),
    ],
)
def test_parse_avi_date_accepts_common_forms(raw, ymd):
    parsed = ex.parse_avi_date(raw)
    assert parsed is not None
    assert (parsed.year, parsed.month, parsed.day) == ymd


def test_parse_avi_date_rejects_nonsense():
    assert ex.parse_avi_date("") is None
    assert ex.parse_avi_date("Tue Xyz 29 12:00:00 2010") is None
    assert ex.parse_avi_date("not a date") is None


def test_format_avi_date_like_keeps_style_and_length():
    for original in ("Tue Jun 29 12:00:00 2010\n", "Tue Jun 29 12:00:00 2010", "2010:06:29 12:00:00", "2010-06-29", "2010-06-29T12:00:00", "2010-06-29 12:00:00"):
        formatted = ex.format_avi_date_like(original, RECORDED)
        assert len(formatted) == len(original), original
        assert ex.parse_avi_date(formatted) == (RECORDED if len(original.strip()) > 10 else RECORDED.replace(hour=0, minute=0, second=0))
    assert ex.format_avi_date_like("Tue Jun 29 12:00:00 2010\n", RECORDED) == "Fri Aug  7 18:45:12 2026\n"


# ---- JPEG EXIF rebuild ---------------------------------------------------------------------------------


def test_no_exif_gets_mtime_date_true_dimensions_and_camera_identity():
    src = make_jpeg(64, 48)
    out, res = ex.fix_jpeg(src, FALLBACK)

    assert (res.width, res.height, res.orientation) == (64, 48, 1)
    assert res.date_source == "mtime" and res.date_time == "2026:03:03 12:16:29"
    assert not res.exif_was_present and not res.date_repaired
    assert (res.make, res.model) == ("Kodak", "Charmera")

    info = exif_of(out)
    assert info.present
    assert (info.make, info.model, info.orientation) == ("Kodak", "Charmera", 1)
    assert info.date_time == info.date_time_original == info.date_time_digitized == "2026:03:03 12:16:29"

    layout = ex.scan_jpeg(out)
    assert layout.exif_found and layout.exif_start == ex.scan_jpeg(src).exif_end  # right after the JFIF APP0


def test_pillow_reads_the_rebuilt_exif_and_decodes_identical_pixels():
    Image = pytest.importorskip("PIL.Image")
    src = make_jpeg(64, 48)
    out, _ = ex.fix_jpeg(src, FALLBACK)

    fixed = Image.open(io.BytesIO(out))
    fixed.load()
    exif = fixed.getexif()
    assert exif[ex.TAG_MAKE] == "Kodak" and exif[ex.TAG_MODEL] == "Charmera"
    assert exif[ex.TAG_ORIENTATION] == 1 and exif[ex.TAG_DATETIME] == "2026:03:03 12:16:29"
    sub = exif.get_ifd(ex.TAG_EXIF_IFD)
    assert sub[ex.TAG_DATETIME_ORIGINAL] == "2026:03:03 12:16:29"
    assert (sub[ex.TAG_PIXEL_X], sub[ex.TAG_PIXEL_Y]) == (64, 48)
    assert float(sub[ex.TAG_FNUMBER]) == pytest.approx(2.4)
    assert sub[ex.TAG_FOCAL_LENGTH_35MM] == 35
    assert sub[ex.TAG_LENS_MODEL] == ex.LENS_MODEL
    assert sub[ex.TAG_EXIF_VERSION] == b"0230"
    assert fixed.tobytes() == Image.open(io.BytesIO(src)).tobytes()


def test_malformed_date_is_repaired_and_chip_make_replaced():
    src = make_jpeg()
    bad = with_exif(src, ex.encode_exif(ex.ExifData(64, 48, "2026:03:03:12:16:29", make="Generalplus", model="CBB3")))
    assert exif_of(bad).date_time_original == "2026:03:03:12:16:29"  # the corruption is really in the file

    out, res = ex.fix_jpeg(bad, datetime(2000, 1, 1))
    assert res.date_source == "exif" and res.date_repaired
    assert res.date_time == "2026:03:03 12:16:29"
    assert (res.old_make, res.old_model) == ("Generalplus", "CBB3")
    assert (res.make, res.model) == ("Kodak", "Charmera")
    assert exif_of(out).date_time_original == "2026:03:03 12:16:29"
    assert exif_of(out).make == "Kodak"


def test_keep_make_preserves_the_camera_values():
    bad = with_exif(make_jpeg(), ex.encode_exif(ex.ExifData(64, 48, "2026:03:03:12:16:29", make="Generalplus", model="CBB3")))
    out, res = ex.fix_jpeg(bad, FALLBACK, keep_make=True)
    assert (res.make, res.model) == ("Generalplus", "CBB3")
    assert (exif_of(out).make, exif_of(out).model) == ("Generalplus", "CBB3")


def test_zero_date_in_exif_falls_back_to_mtime():
    bad = with_exif(make_jpeg(), ex.encode_exif(ex.ExifData(64, 48, "0000:00:00 00:00:00")))
    _, res = ex.fix_jpeg(bad, FALLBACK)
    assert res.date_source == "mtime" and res.date_time == "2026:03:03 12:16:29"
    assert res.exif_was_present


def test_orientation_is_preserved_and_dimensions_come_from_the_frame():
    # The camera says 640x480 in EXIF while the frame is bigger; the frame wins.
    src = make_jpeg(96, 32)
    lying = with_exif(src, ex.encode_exif(ex.ExifData(640, 480, "2026:03:03 12:16:29", orientation=6)))
    out, res = ex.fix_jpeg(lying, FALLBACK)
    assert (res.width, res.height, res.orientation) == (96, 32, 6)
    assert exif_of(out).orientation == 6


def test_big_endian_exif_with_corrupt_makernote_is_read():
    src = make_jpeg()
    date = b"2026:03:03:12:16:29\x00"
    tiff_len_guess = 200  # the MakerNote offset will point past the end, as the camera's does
    tiff = tiff_big_endian(
        [
            (ex.TAG_MAKE, ex.TYPE_ASCII, 12, b"Generalplus\x00"),
            (ex.TAG_MODEL, ex.TYPE_ASCII, 4, b"CBB3"),
            (ex.TAG_ORIENTATION, ex.TYPE_SHORT, 1, struct.pack(">H", 8)),
            (ex.TAG_DATETIME, ex.TYPE_ASCII, 20, date),
        ],
        [
            (ex.TAG_DATETIME_ORIGINAL, ex.TYPE_ASCII, 20, date),
            (0x927C, ex.TYPE_UNDEFINED, 100, struct.pack(">I", tiff_len_guess)),  # MakerNote, offset past the end
        ],
    )
    jpeg = with_exif(src, ex.EXIF_HEADER + tiff)
    out, res = ex.fix_jpeg(jpeg, datetime(2000, 1, 1))
    assert res.orientation == 8
    assert res.date_source == "exif" and res.date_time == "2026:03:03 12:16:29"
    assert (res.old_make, res.old_model) == ("Generalplus", "CBB3")
    assert out[ex.scan_jpeg(out).exif_start + 10 : ex.scan_jpeg(out).exif_start + 12] == b"II"  # rebuilt little-endian


def test_scan_data_is_byte_identical():
    src = make_jpeg(80, 60)
    bad = with_exif(src, ex.encode_exif(ex.ExifData(64, 48, "2026:03:03:12:16:29")))
    for original in (src, bad):
        out, _ = ex.fix_jpeg(original, FALLBACK)
        before, after = ex.scan_jpeg(original), ex.scan_jpeg(out)
        assert original[: before.exif_start] == out[: after.exif_start]
        assert original[before.exif_end :] == out[after.exif_end :]
        assert out[after.exif_end :].endswith(b"\xff\xd9")


def test_out_of_range_orientation_is_written_as_1_and_reported():
    jpeg = with_exif(make_jpeg(), ex.EXIF_HEADER + charmera_tiff(orientation=9))
    assert exif_of(jpeg).orientation == 9  # the bad value is really in the source

    out, res = ex.fix_jpeg(jpeg, FALLBACK)
    assert (res.orientation, res.source_orientation) == (1, 9)
    assert exif_of(out).orientation == 1
    assert "orientation 1 (source had 9)" in ex._describe_photo(res, keep_make=False)

    _, overridden = ex.fix_jpeg(jpeg, FALLBACK, orientation_override=6)
    assert overridden.orientation == 6 and "orientation 6 (source had 9)" in ex._describe_photo(overridden, False)

    _, fine = ex.fix_jpeg(with_exif(make_jpeg(), ex.EXIF_HEADER + charmera_tiff(orientation=8)), FALLBACK)
    assert fine.orientation == 8 and "source had" not in ex._describe_photo(fine, False)
    _, absent = ex.fix_jpeg(make_jpeg(), FALLBACK)
    assert absent.orientation == 1 and absent.source_orientation == 0 and "source had" not in ex._describe_photo(absent, False)
    for bad in (0, 12):  # the writer itself never emits an out-of-range value
        assert exif_of(with_exif(make_jpeg(), ex.encode_exif(ex.ExifData(1, 1, "2026:03:03 12:16:29", orientation=bad)))).orientation == 1


def test_ycbcr_positioning_is_carried_over_from_the_source():
    Image = pytest.importorskip("PIL.Image")
    camera = charmera_jpeg()  # the camera writes 2, co-sited
    assert exif_of(camera).ycbcr_positioning == 2
    out, _ = ex.fix_jpeg(camera, FALLBACK)
    assert exif_of(out).ycbcr_positioning == 2
    assert Image.open(io.BytesIO(out)).getexif()[ex.TAG_YCBCR_POSITIONING] == 2

    out, _ = ex.fix_jpeg(make_jpeg(), FALLBACK)  # no source value: centered
    assert exif_of(out).ycbcr_positioning == 1
    odd = with_exif(
        make_jpeg(),
        ex.EXIF_HEADER + build_tiff("<", [(ex.TAG_YCBCR_POSITIONING, ex.TYPE_SHORT, 1, struct.pack("<H", 7))], []),
    )
    assert exif_of(odd).ycbcr_positioning == 7
    out, _ = ex.fix_jpeg(odd, FALLBACK)
    assert exif_of(out).ycbcr_positioning == 1  # invalid: back to the default
    for value in (0, 3):
        assert exif_of(with_exif(make_jpeg(), ex.encode_exif(ex.ExifData(1, 1, "2026:03:03 12:16:29", ycbcr_positioning=value)))).ycbcr_positioning == 1


def test_second_exif_app1_is_dropped_not_carried_over():
    first = ex.encode_exif(ex.ExifData(64, 48, "2026:03:03:12:16:29", make="Generalplus", model="CBB3"))
    second = ex.encode_exif(ex.ExifData(64, 48, "2001:01:01 01:01:01", make="Other", model="Other"))
    one = with_exif(make_jpeg(), first)
    layout = ex.scan_jpeg(one)
    two = one[: layout.exif_end] + b"\xff\xe1" + struct.pack(">H", len(second) + 2) + second + one[layout.exif_end :]
    layout = ex.scan_jpeg(two)
    assert len(layout.exif_ranges) == 2 and (layout.exif_start, layout.exif_end) == layout.exif_ranges[0]
    assert [m for m, _, _ in segments(two)].count(0xE1) == 2

    out, res = ex.fix_jpeg(two, FALLBACK)
    assert [m for m, _, _ in segments(out)].count(0xE1) == 1
    assert res.date_time == "2026:03:03 12:16:29" and (res.old_make, res.old_model) == ("Generalplus", "CBB3")  # the first wins
    after = ex.scan_jpeg(out)
    assert after.exif_start == layout.exif_start and len(after.exif_ranges) == 1
    assert out[: after.exif_start] == two[: layout.exif_start]
    assert out[after.exif_end :] == two[layout.exif_ranges[1][1] :]  # everything after the second APP1 is intact
    assert b"Other" not in out


def test_camera_segment_order_is_handled():
    """The camera's real layout: SOF0 first, COM "GPEncoder" (length 0x11), DQT, DQT, DRI, four DHTs, then the
    Exif APP1 immediately before SOS, no APP0."""
    camera = charmera_jpeg(64, 48)
    segs = segments(camera)
    markers = [m for m, _, _ in segs]
    assert markers == [0xC0, 0xFE, 0xDB, 0xDB, 0xDD, 0xC4, 0xC4, 0xC4, 0xC4, 0xE1, 0xDA]
    com = [camera[s:e] for m, s, e in segs if m == 0xFE][0]
    assert com[2:4] == b"\x00\x11" and com[4:].startswith(b"GPEncoder")

    out, res = check_camera_jpeg(camera)
    assert (res.width, res.height) == (64, 48)
    assert res.date_repaired and res.date_time == "2026:03:03 12:16:29"
    assert [m for m, _, _ in segments(out)] == markers
    assert exif_of(out).ycbcr_positioning == 2


@pytest.mark.skipif(not os.environ.get("CHARMERA_STOCK_IMAGE"), reason="CHARMERA_STOCK_IMAGE not set")
def test_stock_image_stored_photos_are_fixed():
    """The two photos stored inside a public stock dump (docs/02-RECOVERY.md), each from its offset to the first
    FF D9 after it. Point CHARMERA_STOCK_IMAGE at the .bin to run this."""
    image = Path(os.environ["CHARMERA_STOCK_IMAGE"]).read_bytes()
    for offset in STOCK_PHOTO_OFFSETS:
        assert image[offset : offset + 2] == b"\xff\xd8", f"no JPEG at {offset:#x}"
        end = image.index(b"\xff\xd9", offset) + 2
        jpeg = image[offset:end]
        out, res = check_camera_jpeg(jpeg)
        assert res.width > 0 and res.height > 0
        assert ex.is_charmera_jpeg(out)  # the COM segment survives, so a second pass still recognises the file


def test_is_charmera_jpeg_judges_each_file():
    assert not ex.is_charmera_jpeg(make_jpeg())  # a plain encoder frame
    assert ex.is_charmera_jpeg(make_jpeg(comment=b"GPEncoder"))  # the Generalplus comment
    assert ex.is_charmera_jpeg(with_exif(make_jpeg(), ex.encode_exif(ex.ExifData(64, 48, "2026:03:03 12:16:29", make="Generalplus"))))
    assert ex.is_charmera_jpeg(with_exif(make_jpeg(), ex.encode_exif(ex.ExifData(64, 48, "2026:03:03:12:16:29"))))  # all-colons date
    assert ex.is_charmera_jpeg(charmera_jpeg())
    phone = with_exif(make_jpeg(), ex.encode_exif(ex.ExifData(64, 48, "2026:03:03 12:16:29", make="Apple", model="iPhone")))
    assert not ex.is_charmera_jpeg(phone)
    assert not ex.is_charmera_jpeg(b"not a jpeg")


def test_fix_is_idempotent():
    src = make_jpeg()
    once, _ = ex.fix_jpeg(src, FALLBACK)
    twice, res = ex.fix_jpeg(once, datetime(2000, 1, 1))
    assert twice == once and res.date_source == "exif"


def test_rejects_non_jpeg():
    with pytest.raises(ValueError):
        ex.fix_jpeg(b"not a jpeg", FALLBACK)
    with pytest.raises(ValueError):
        ex.fix_jpeg(b"\xff\xd8\xff\xd9", FALLBACK)  # SOI/EOI only: no frame header


# ---- AVI date rewrite ----------------------------------------------------------------------------------


def test_minimal_avi_dates_are_found_and_patched(tmp_path):
    path = tmp_path / "PICT0001.AVI"
    original = minimal_avi()
    path.write_bytes(original)
    with open(path, "rb") as fh:
        info = ex.read_avi(fh)
    assert (info.width, info.height) == (1440, 1080)
    assert info.duration_seconds == pytest.approx(3.0, abs=0.01)
    assert [f.chunk_id for f in info.date_fields] == ["IDIT", "ICRD"]
    assert ex.has_bogus_avi_date(info)

    with open(path, "r+b") as fh:
        assert ex.patch_avi_dates(fh, info, RECORDED) == (["IDIT", "ICRD"], [])
    patched = path.read_bytes()
    assert len(patched) == len(original)
    with open(path, "rb") as fh:
        after = ex.read_avi(fh)
    assert not ex.has_bogus_avi_date(after)
    assert after.date_fields[0].value.rstrip("\x00") == "Fri Aug  7 18:45:12 2026\n"
    assert after.date_fields[1].value.rstrip("\x00") == "2026-08-07"
    date_bytes = {i for f in info.date_fields for i in range(f.offset, f.offset + f.size)}
    changed = {i for i in range(len(original)) if original[i] != patched[i]}
    assert changed and changed <= date_bytes


def test_genuine_avi_date_is_left_alone(tmp_path):
    path = tmp_path / "v.avi"
    original = minimal_avi(idit=b"Sat Aug  1 10:00:00 2026\n\x00", icrd=b"")
    path.write_bytes(original)
    with open(path, "r+b") as fh:
        info = ex.read_avi(fh)
        assert not ex.has_bogus_avi_date(info)
        assert ex.patch_avi_dates(fh, info, RECORDED) == ([], [])
    assert path.read_bytes() == original


def test_too_small_date_field_is_reported_not_truncated(tmp_path):
    path = tmp_path / "v.avi"
    path.write_bytes(minimal_avi(idit=b"Jun 29 12:00:00 2010", icrd=b""))  # no weekday: 20 bytes of room
    with open(path, "r+b") as fh:
        info = ex.read_avi(fh)
        assert ex.has_bogus_avi_date(info)
        assert ex.patch_avi_dates(fh, info, RECORDED) == ([], ["IDIT"])


def test_read_avi_rejects_other_files(tmp_path):
    with pytest.raises(ValueError):
        ex.read_avi(io.BytesIO(b"not a video"))


@pytest.mark.skipif(shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None, reason="ffmpeg not on PATH")
def test_ffmpeg_avi_with_injected_camera_date_is_fixed_and_still_valid(tmp_path):
    plain = tmp_path / "plain.avi"
    ffmpeg_avi(plain)
    path = tmp_path / "PICT0002.AVI"
    original = inject_dates(plain.read_bytes())
    path.write_bytes(original)
    assert ffprobe_tags(path).get("TAG:creation_time", "").startswith("2010-06-29")  # ffmpeg sees the fake date too

    with open(path, "r+b") as fh:
        info = ex.read_avi(fh)
        assert [f.chunk_id for f in info.date_fields] == ["IDIT", "ICRD"]
        assert ex.patch_avi_dates(fh, info, RECORDED) == (["IDIT", "ICRD"], [])

    assert len(path.read_bytes()) == len(original)
    tags = ffprobe_tags(path)
    assert tags["TAG:creation_time"].startswith("2026-08-07 18:45:12")
    assert tags["TAG:date"] == "2026-08-07"
    decode = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-f", "null", "-"], capture_output=True, text=True)
    assert decode.returncode == 0 and decode.stderr == ""


# ---- camera detection ----------------------------------------------------------------------------------


def test_is_charmera_volume(tmp_path):
    assert not ex.is_charmera_volume(tmp_path)  # no DCIM
    dcim = tmp_path / "DCIM"
    dcim.mkdir()
    assert not ex.is_charmera_volume(tmp_path)  # DCIM alone is any camera
    (dcim / "PICT0001.JPG").write_bytes(make_jpeg())
    assert not ex.is_charmera_volume(tmp_path)  # a JPEG without the Generalplus comment
    (dcim / "PICT0002.JPG").write_bytes(make_jpeg(comment=b"GPEncoder 1.0"))
    assert ex.is_charmera_volume(tmp_path)  # signature within the first 4 KiB
    (dcim / "PICT0002.JPG").unlink()
    (tmp_path / "SPIDCIM").mkdir()
    assert ex.is_charmera_volume(tmp_path)  # the sibling folder


def test_media_root_and_listing(tmp_path):
    root = card(tmp_path)
    (root / "DCIM" / "100MEDIA").mkdir()
    (root / "DCIM" / "100MEDIA" / "PICT0003.jpeg").write_bytes(b"x")
    (root / "DCIM" / "PICT0001.JPG").write_bytes(b"x")
    (root / "DCIM" / "PICT0002.AVI").write_bytes(b"x")
    (root / "DCIM" / "._PICT0001.JPG").write_bytes(b"x")
    (root / "DCIM" / "notes.txt").write_bytes(b"x")
    assert ex.media_root(root) == root / "DCIM"
    assert ex.media_root(root / "DCIM") == root / "DCIM"
    assert [p.name for p in ex.list_media(ex.media_root(root))] == ["PICT0003.jpeg", "PICT0001.JPG", "PICT0002.AVI"]


def test_hash_token_round_trip():
    assert ex.token_from_name("IMG_20260303_121629_0123456789abcdef.jpg") == "0123456789abcdef"
    assert ex.token_from_name("VID_20260303_121629_0123456789ABCDEF_1.mp4") is None  # collision suffix is not a token
    assert ex.token_from_name("PICT0001.JPG") is None
    assert ex.output_name("photo", FALLBACK, "0123456789abcdef", ".jpg") == "IMG_20260303_121629_0123456789abcdef.jpg"


# ---- the command ---------------------------------------------------------------------------------------


def test_cli_copies_fixes_names_preserves_mtime_and_dedups(tmp_path, capsys):
    root = card(tmp_path)
    src = root / "DCIM" / "PICT0001.JPG"
    src.write_bytes(with_exif(make_jpeg(), ex.encode_exif(ex.ExifData(64, 48, "2026:03:03:12:16:29", make="Generalplus", model="CBB3"))))
    set_mtime(src, FALLBACK)
    (root / "DCIM" / "._PICT0001.JPG").write_bytes(b"\x00\x05\x16\x07")
    before = src.read_bytes()
    out = tmp_path / "fixed"

    assert run_cli("--source", str(root), "--out", str(out)) == 0
    text = capsys.readouterr().out
    assert "PICT0001.JPG -> IMG_" in text and "extra colon repaired" in text and "64x48" in text
    assert "1 photos fixed" in text

    produced = sorted(p.name for p in out.iterdir())
    assert len(produced) == 1 and NAME_RE.match(produced[0])
    assert produced[0].startswith("IMG_20260303_121629_")
    assert produced[0] == "IMG_20260303_121629_" + ex.hash_token(ex.sha256_file(src)) + ".jpg"
    dst = out / produced[0]
    assert dst.stat().st_mtime == pytest.approx(src.stat().st_mtime, abs=1e-6)
    assert src.read_bytes() == before  # the source is never touched
    assert exif_of(dst.read_bytes()).date_time_original == "2026:03:03 12:16:29"
    assert not list(out.glob("*.charmera-tmp"))

    assert run_cli("--source", str(root), "--out", str(out)) == 0
    text = capsys.readouterr().out
    assert "skipped" in text and "0 photos fixed" in text and "1 skipped" in text
    assert sorted(p.name for p in out.iterdir()) == produced


def test_cli_source_can_be_any_folder_and_keep_names(tmp_path, capsys):
    folder = tmp_path / "loose"
    folder.mkdir()
    (folder / "a.jpeg").write_bytes(make_jpeg())
    out = tmp_path / "out"
    assert run_cli("--source", str(folder), "--out", str(out), "--keep-names") == 0
    assert [p.name for p in out.iterdir()] == ["a.jpeg"]
    assert run_cli("--source", str(folder), "--out", str(out), "--keep-names") == 0
    assert "already exists" in capsys.readouterr().out
    assert [p.name for p in out.iterdir()] == ["a.jpeg"]


def test_keep_names_keeps_same_named_photos_from_different_folders(tmp_path, capsys):
    root = card(tmp_path)
    first = root / "DCIM" / "100MEDIA" / "PICT0001.JPG"
    second = root / "DCIM" / "101MEDIA" / "PICT0001.JPG"
    first.parent.mkdir()
    second.parent.mkdir()
    first.write_bytes(charmera_jpeg(64, 48))
    second.write_bytes(charmera_jpeg(32, 32))  # a different photo under the same name
    set_mtime(first, FALLBACK)
    set_mtime(second, RECORDED)
    out = tmp_path / "fixed"

    assert run_cli("--source", str(root), "--out", str(out), "--keep-names") == 0
    text = capsys.readouterr().out
    assert sorted(p.name for p in out.iterdir()) == ["100MEDIA_PICT0001.JPG", "101MEDIA_PICT0001.JPG"]
    assert "2 photos fixed" in text and "0 skipped" in text and "0 name collisions" in text
    assert "already exists" not in text
    assert ex.scan_jpeg((out / "101MEDIA_PICT0001.JPG").read_bytes()).width == 32

    # A second run recognises both as already imported.
    assert run_cli("--source", str(root), "--out", str(out), "--keep-names") == 0
    text = capsys.readouterr().out
    assert text.count("already exists") == 2 and "2 skipped" in text and "0 photos fixed" in text
    assert sorted(p.name for p in out.iterdir()) == ["100MEDIA_PICT0001.JPG", "101MEDIA_PICT0001.JPG"]

    # A different file whose name is already taken in the output is written under another name, not dropped.
    other = tmp_path / "other"
    other.mkdir()
    (other / "100MEDIA_PICT0001.JPG").write_bytes(charmera_jpeg(16, 16))
    set_mtime(other / "100MEDIA_PICT0001.JPG", datetime(2026, 9, 9, 9, 9, 9))
    assert run_cli("--source", str(other), "--out", str(out), "--keep-names") == 0
    text = capsys.readouterr().out
    assert "name taken by a different file, written as 100MEDIA_PICT0001_1.JPG" in text
    assert "1 photos fixed" in text and "0 skipped" in text and "1 name collisions" in text
    assert (out / "100MEDIA_PICT0001_1.JPG").exists() and ex.scan_jpeg((out / "100MEDIA_PICT0001.JPG").read_bytes()).width == 64

    # Without --keep-names the hash names never collide.
    hashed = tmp_path / "hashed"
    assert run_cli("--source", str(root), "--out", str(hashed)) == 0
    assert len(list(hashed.iterdir())) == 2 and all(NAME_RE.match(p.name) for p in hashed.iterdir())


def test_cli_copies_other_cameras_jpegs_unchanged_unless_relabel_all(tmp_path, capsys):
    root = card(tmp_path)
    camera = root / "DCIM" / "PICT0001.JPG"
    camera.write_bytes(charmera_jpeg())
    phone = root / "DCIM" / "IMG_0007.JPG"
    phone_bytes = with_exif(
        make_jpeg(),
        ex.EXIF_HEADER + build_tiff(">", [(ex.TAG_MAKE, ex.TYPE_ASCII, 6, b"Apple\x00"), (ex.TAG_MODEL, ex.TYPE_ASCII, 7, b"iPhone\x00"),
                                       (ex.TAG_DATETIME, ex.TYPE_ASCII, 20, b"2026:05:05 05:05:05\x00"),
                                       (0x8825, ex.TYPE_LONG, 1, struct.pack(">I", 8))],  # a GPS IFD pointer: tags this tool never writes
                                      [(0x829A, ex.TYPE_RATIONAL, 1, struct.pack(">II", 1, 250))]),  # ExposureTime
    )
    phone.write_bytes(phone_bytes)
    set_mtime(phone, RECORDED)
    out = tmp_path / "fixed"

    assert run_cli("--source", str(root), "--out", str(out), "--keep-names") == 0
    text = capsys.readouterr().out
    assert "IMG_0007.JPG -> IMG_0007.JPG: 64x48, not a Charmera photo, copied unchanged" in text
    assert "PICT0001.JPG -> PICT0001.JPG: 64x48, orientation 1" in text
    assert "1 photos fixed, 1 photos copied unchanged (not Charmera)" in text
    copied = out / "IMG_0007.JPG"
    assert copied.read_bytes() == phone_bytes  # GPS and exposure tags untouched
    assert copied.stat().st_mtime == pytest.approx(phone.stat().st_mtime, abs=1e-6)
    assert exif_of((out / "PICT0001.JPG").read_bytes()).make == "Kodak"

    forced = tmp_path / "forced"
    assert run_cli("--source", str(root), "--out", str(forced), "--keep-names", "--relabel-all") == 0
    text = capsys.readouterr().out
    assert "copied unchanged" not in text and "2 photos fixed" in text
    relabelled = exif_of((forced / "IMG_0007.JPG").read_bytes())
    assert (relabelled.make, relabelled.model, relabelled.date_time) == ("Kodak", "Charmera", "2026:05:05 05:05:05")


def test_per_file_output_survives_an_ascii_only_stdout(tmp_path, monkeypatch):
    root = card(tmp_path)
    (root / "DCIM" / "写真.JPG").write_bytes(charmera_jpeg())
    (root / "DCIM" / "kaputté.JPG").write_bytes(b"not a jpeg")
    (root / "DCIM" / "ok.JPG").write_bytes(charmera_jpeg())
    raw = io.BytesIO()
    monkeypatch.setattr(sys, "stdout", io.TextIOWrapper(raw, encoding="ascii", errors="strict", write_through=True))
    out = tmp_path / "out ü"

    assert run_cli("--source", str(root), "--out", str(out), "--keep-names") == 1  # the bad file, not the encoding
    text = raw.getvalue().decode("ascii")
    assert "\\u5199\\u771f.JPG -> \\u5199\\u771f.JPG: 64x48" in text
    assert "kaputt\\xe9.JPG FAILED" in text
    assert "ok.JPG -> ok.JPG" in text
    assert "2 photos fixed" in text and "1 failed" in text
    assert sorted(p.name for p in out.iterdir()) == ["ok.JPG", "写真.JPG"]


def test_dry_run_writes_nothing(tmp_path, capsys):
    root = card(tmp_path)
    (root / "DCIM" / "PICT0001.JPG").write_bytes(make_jpeg())
    (root / "DCIM" / "PICT0002.AVI").write_bytes(minimal_avi())
    out = tmp_path / "fixed"
    assert run_cli("--source", str(root), "--out", str(out), "--dry-run", "--verbose") == 0
    text = capsys.readouterr().out
    assert not out.exists()
    assert text.count("would write") == 2
    assert "replaces 2010-06-29 in IDIT, ICRD" in text
    assert "Dry run, nothing written" in text


def test_cli_fixes_avi_date_in_the_copy(tmp_path, capsys):
    root = card(tmp_path)
    src = root / "DCIM" / "PICT0002.AVI"
    original = minimal_avi()
    src.write_bytes(original)
    set_mtime(src, RECORDED)
    out = tmp_path / "fixed"

    assert run_cli("--source", str(root), "--out", str(out)) == 0
    text = capsys.readouterr().out
    assert "1440x1080" in text and "replaces 2010-06-29 in IDIT, ICRD" in text and "1 videos date-fixed" in text
    produced = [p for p in out.iterdir()]
    assert len(produced) == 1 and produced[0].name.startswith("VID_20260807_184512_") and produced[0].suffix == ".avi"
    with open(produced[0], "rb") as fh:
        after = ex.read_avi(fh)
    assert not ex.has_bogus_avi_date(after)
    assert after.date_fields[0].value.rstrip("\x00") == "Fri Aug  7 18:45:12 2026\n"
    assert produced[0].stat().st_size == len(original)
    assert produced[0].stat().st_mtime == pytest.approx(src.stat().st_mtime, abs=1e-6)
    assert src.read_bytes() == original


def test_cli_video_copy_leaves_the_avi_unchanged(tmp_path):
    root = card(tmp_path)
    original = minimal_avi()
    (root / "DCIM" / "PICT0002.AVI").write_bytes(original)
    out = tmp_path / "fixed"
    assert run_cli("--source", str(root), "--out", str(out), "--video", "copy") == 0
    produced = list(out.iterdir())
    assert len(produced) == 1 and produced[0].read_bytes() == original


def test_cli_transcode_falls_back_without_ffmpeg(tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(ex, "find_ffmpeg", lambda: None)
    root = card(tmp_path)
    (root / "DCIM" / "PICT0002.AVI").write_bytes(minimal_avi())
    out = tmp_path / "fixed"
    assert run_cli("--source", str(root), "--out", str(out), "--video", "transcode") == 0
    text = capsys.readouterr().out
    assert "ffmpeg is not on PATH" in text and "Falling back" in text
    produced = list(out.iterdir())
    assert len(produced) == 1 and produced[0].suffix == ".avi"
    with open(produced[0], "rb") as fh:
        assert not ex.has_bogus_avi_date(ex.read_avi(fh))


@pytest.mark.skipif(shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None, reason="ffmpeg not on PATH")
def test_cli_transcode_produces_mp4_with_creation_time(tmp_path, capsys):
    root = card(tmp_path)
    plain = tmp_path / "plain.avi"
    ffmpeg_avi(plain)
    src = root / "DCIM" / "PICT0002.AVI"
    src.write_bytes(inject_dates(plain.read_bytes()))
    set_mtime(src, RECORDED)
    out = tmp_path / "fixed"

    assert run_cli("--source", str(root), "--out", str(out), "--video", "transcode") == 0
    assert "1 videos transcoded" in capsys.readouterr().out
    produced = list(out.iterdir())
    assert len(produced) == 1 and produced[0].name.startswith("VID_20260807_184512_") and produced[0].suffix == ".mp4"
    assert produced[0].read_bytes()[4:8] == b"ftyp"
    assert produced[0].stat().st_mtime == pytest.approx(src.stat().st_mtime, abs=1e-6)
    tags = ffprobe_tags(produced[0])
    assert tags["nb_streams"] == "2"
    # ffmpeg reads the naive creation_time as local time and stores the instant in UTC with a Z suffix.
    expected_utc = RECORDED.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    assert tags["TAG:creation_time"].startswith(expected_utc), tags["TAG:creation_time"]
    assert tags["TAG:creation_time"].endswith("Z")


def test_cli_reports_bad_files_and_exits_1(tmp_path, capsys):
    root = card(tmp_path)
    (root / "DCIM" / "PICT0001.JPG").write_bytes(b"this is not a jpeg")
    (root / "DCIM" / "PICT0002.JPG").write_bytes(make_jpeg(comment=b"GPEncoder"))
    out = tmp_path / "fixed"
    assert run_cli("--source", str(root), "--out", str(out)) == 1
    text = capsys.readouterr().out
    assert "PICT0001.JPG FAILED" in text and "1 photos fixed" in text and "1 failed" in text
    assert len(list(out.iterdir())) == 1


def test_cli_missing_source_dir(tmp_path, capsys):
    assert run_cli("--source", str(tmp_path / "nope"), "--out", str(tmp_path / "out")) == 1
    assert "not a directory" in capsys.readouterr().err


def test_cli_autodetect_uses_content_check(tmp_path, capsys, monkeypatch):
    other = tmp_path / "usbstick"
    (other / "DCIM").mkdir(parents=True)
    root = card(tmp_path)
    (root / "DCIM" / "PICT0001.JPG").write_bytes(make_jpeg())
    out = tmp_path / "fixed"
    monkeypatch.setattr(ex, "candidate_volumes", lambda: [other, root])
    assert run_cli("--out", str(out)) == 0
    assert f"Charmera found at {root}" in capsys.readouterr().out
    assert len(list(out.iterdir())) == 1

    monkeypatch.setattr(ex, "candidate_volumes", lambda: [other])
    assert run_cli("--out", str(out)) == 1
    assert "no Charmera found" in capsys.readouterr().err
