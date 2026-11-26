"""photo-id: what the header of a photo says about the firmware that took it.

tests/fixtures/original-photo-header.jpg-header is the owner's camera's header: the first 624 bytes of a photo
that camera took on its original firmware, before it was ever flashed, cut at the end of the SOS header, so it
holds no image data. The other headers are built here, from the parts the templates share (the JPEG standard's
tables, no firmware bytes; the EXIF mod's block from its authored names and lengths only), and, when
CHARMERA_STOCK_IMAGE points at the public dump, from the templates and the two stored photos in it, and when
CHARMERA_FACTORY_DUMP points at a v012 dump, from its bare templates.
"""
import io
import os
import struct
from pathlib import Path

import pytest

from datetime import datetime

from charmera_toolkit import cli, exif, image, photo_id

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "original-photo-header.jpg-header"
FILM_MODS_RESTORE = Path(os.environ.get("CHARMERA_FILM_MODS_RESTORE", ""))
FACTORY_DUMP = Path(os.environ.get("CHARMERA_FACTORY_DUMP", ""))
# The four bare templates of the factory build v012 (file offsets in its 2 MiB dump).
V012_BARE = (0xFFC68, 0x107538, 0x1077A8, 0x107A18)

# The templates in the public dump (file offsets), and how far the film-mods build has them moved on.
T0, T1, T2, T3 = 0xFFB78, 0x107448, 0x1079D8, 0x107C48
FILM_MODS_SHIFT = 0xC8


def seg(marker, payload):
    return bytes([0xFF, marker]) + struct.pack(">H", len(payload) + 2) + payload


def dqt(quality, chroma=None):
    """The two DQT segments the camera's encoder writes for an IJG quality (each table in zig-zag order). `chroma`
    gives the second table a quality of its own, which the encoder never does."""
    out = b""
    for tq, base, q in ((0, photo_id.IJG_LUMINANCE, quality),
                        (1, photo_id.IJG_CHROMINANCE, quality if chroma is None else chroma)):
        natural = photo_id.ijg_table(base, q)
        out += seg(0xDB, bytes([tq]) + bytes(natural[photo_id.ZIGZAG[k]] for k in range(64)))
    return out


def sof(width=1440, height=1080, y=0x22, marker=0xC0):
    return seg(marker, struct.pack(">BHHB", 8, height, width, 3) + bytes([1, y, 0, 2, 0x11, 1, 3, 0x11, 1]))


DRI = seg(0xDD, b"\x00\x00")
DHT = b"".join(seg(0xC4, t) for t in photo_id.STANDARD_DHT)
SOS = seg(0xDA, bytes([3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 0x3F, 0]))
JFIF = seg(0xE0, b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00")


def exif_app1(make="Generalplus", model="CBB3         ", software="20150615", size=794):
    """An APP1 Exif block with IFD0 Make, Model and Software, padded to `size` bytes, marker included (the camera's is 794)."""
    entries = [(0x010F, make), (0x0110, model), (0x0131, software)]
    data_at = 8 + 2 + 12 * len(entries) + 4
    ifd, blobs = struct.pack("<H", len(entries)), b""
    for tag, text in entries:
        raw = text.encode("ascii") + b"\x00"
        value = raw.ljust(4, b"\x00") if len(raw) <= 4 else struct.pack("<I", data_at + len(blobs))
        if len(raw) > 4:
            blobs += raw
        ifd += struct.pack("<HHI", tag, 2, len(raw)) + value
    payload = b"Exif\x00\x00" + b"II*\x00" + struct.pack("<I", 8) + ifd + b"\x00" * 4 + blobs
    return seg(0xE1, payload + b"\x00" * (size - 4 - len(payload)))


def still_header(quality=80, width=1440, height=1080, model="CBB3         ", app1=None, before=b""):
    """Shaped like the still template: SOI SOF0 COM DQT DQT DRI DHT x4 APP1 SOS, 0x590 bytes."""
    return (b"\xff\xd8" + before + sof(width, height) + seg(0xFE, b"GPEncoder      ") + dqt(quality) + DRI + DHT
            + (exif_app1(model=model) if app1 is None else app1) + SOS)


def bare_header(quality=80, width=1440, height=1080, y=0x22):
    """Shaped like the bare template: SOI SOF0 COM DQT DQT DRI DHT x4 SOS, 0x270 bytes."""
    return b"\xff\xd8" + sof(width, height, y) + seg(0xFE, b"GPEncoder") + dqt(quality) + DRI + DHT + SOS


def exif_mod_app1(software="v0.26"):
    """An APP1 shaped like our EXIF mod's: 0xD0 bytes with the marker, Make Kodak, Model Charmera and Software."""
    return exif_app1(make="Kodak", model="Charmera", software=software, size=photo_id.EXIF_MOD_APP1_LEN)


def exif_mod_header(quality=80, width=1440, height=1080, app1=None):
    """Shaped like our EXIF mod's header: SOI, one APP1, then the bare template from SOF0 on, 0x340 bytes."""
    return b"\xff\xd8" + (exif_mod_app1() if app1 is None else app1) + bare_header(quality, width, height)[2:]


def exif_fixed(header: bytes) -> bytes:
    """What the toolkit's exif-fix makes of a photo with this header (on a copy): its own rebuilt EXIF block."""
    out, _ = exif.fix_jpeg(header + b"\x12\x34" * 100 + b"\xff\xd9", datetime(2026, 11, 14, 12, 0, 0))
    return out


def header_of(data: bytes) -> photo_id.Header:
    return photo_id.read_header(photo_id._reader(io.BytesIO(data)))


def report(tmp_path, data: bytes, name="PICT0001.jpg") -> str:
    p = tmp_path / name
    p.write_bytes(data)
    return "\n".join(photo_id.identify(p))


def conclusion(text: str) -> str:
    return next(line for line in text.splitlines() if line.startswith("conclusion:"))[len("conclusion:"):].strip()


# --- the owner's camera --------------------------------------------------------------------------------------------


def test_the_owners_photo_has_the_factory_build_v012s_bare_header(tmp_path, capsys):
    """The owner's camera, on its original firmware, wrote this photo with the bare template: the factory build v012's
    still-photo header (OV9732 cameras), which the v006 family uses only for thumbnails and video frames."""
    data = FIXTURE.read_bytes()
    assert len(data) == 624 and data[-14:-12] == b"\xff\xda"      # the header and nothing after its SOS
    h = header_of(data)
    assert h.kind == "bare" and h.length == len(data) and h.problem is None
    assert (h.frame.width, h.frame.height, h.frame.components[0][1]) == (1440, 1080, 0x22)
    assert h.qualities == [80] and h.comments == [b"GPEncoder"] and h.exif is None
    assert cli.main(["photo-id", str(FIXTURE)]) == 0
    out = capsys.readouterr().out
    assert "the bare template, no EXIF block, 0x270 bytes" in out and "IJG quality 80" in out and "1440x1080" in out
    assert conclusion(out) == BARE == photo_id.CONCLUSIONS["bare"]
    assert photo_id.verdict(header_of(data)) == ("bare", "v012", [])


def test_the_owners_header_is_the_bare_template_with_its_size_and_quality(stock_image):
    """Byte for byte: the dump's bare template with the 1440x1080 size, the 2x2 sampling and the quality-80 tables
    filled in, as the encoder fills them in, is the owner's header."""
    made = bytearray(stock_image[T0: T0 + photo_id.BARE_HEADER_LEN])
    made[7:11] = struct.pack(">HH", 1080, 1440)
    made[0xD] = 0x22
    at = made.find(b"\xff\xdb")
    made[at: at + 138] = dqt(80)
    assert bytes(made) == FIXTURE.read_bytes()


# --- the three still-photo headers the known builds write ---------------------------------------------------------


STILL = ("the v006 / v008_test family's still-photo header (SP140A builds; our OV9732 port on v006 writes it too; a photo cannot "
         "tell v006 from v008_test)")
BARE = ("the factory build v012's still-photo header (OV9732 cameras), which our v012 builds without EXIF dates keep; the v006 family writes it "
        "only for thumbnails and video frames, so a frame taken out of a video has it too")
EXIF_MOD = "our EXIF mod's still-photo header (any of our builds with EXIF dates, built on the factory build v012)"


def test_the_still_and_the_bare_header(tmp_path):
    still = still_header()
    assert len(still) == photo_id.STILL_HEADER_LEN and len(bare_header()) == photo_id.BARE_HEADER_LEN
    h = header_of(still)
    assert h.kind == "still" and h.qualities == [80]
    assert (h.exif.make, h.exif.model, h.exif.software) == ("Generalplus", "CBB3", "20150615")
    text = report(tmp_path, still + b"\x12\x34" * 500 + b"\xff\xd9")
    assert "the v006 family's EXIF still template, 0x590 bytes (SOI SOF0 COM DQT DQT DRI DHT DHT DHT DHT APP1 SOS)" in text
    assert "comment:     'GPEncoder      '" in text and "Make 'Generalplus', Model 'CBB3', Software '20150615'" in text
    assert "sampling Y 2x2, Cb 1x1, Cr 1x1 (4:2:0)" in text
    assert conclusion(text) == STILL == photo_id.CONCLUSIONS["still"]
    assert photo_id.verdict(h) == ("still", "v006-v008_test", [])
    for quality in (85, 80, 75, 45):            # a card photo's three, and the photo taken without a card
        assert header_of(still_header(quality)).qualities == [quality]
        assert header_of(bare_header(quality)).kind == "bare"
        assert header_of(exif_mod_header(quality)).kind == "exif-mod"


def test_the_exif_mods_header(tmp_path):
    """Our EXIF mod (v0.26 and later): SOI, one 0xD0-byte APP1 with Make Kodak, Model Charmera and a Software tag, then
    v012's bare template from SOF0 on, 0x340 bytes in all."""
    data = exif_mod_header()
    assert len(data) == photo_id.EXIF_MOD_HEADER_LEN
    h = header_of(data)
    assert h.kind == "exif-mod" and h.reasons == [] and h.qualities == [80]
    assert (h.exif.make, h.exif.model, h.exif.software) == ("Kodak", "Charmera", "v0.26")
    assert photo_id.verdict(h) == ("exif-mod", "v012", [])
    text = report(tmp_path, data + b"\x12\x34" * 500 + b"\xff\xd9")
    assert "the EXIF mod's: one Kodak/Charmera EXIF block, then the bare template, 0x340 bytes" in text
    assert "(SOI APP1 SOF0 COM DQT DQT DRI DHT DHT DHT DHT SOS)" in text
    assert conclusion(text) == EXIF_MOD == photo_id.CONCLUSIONS["exif-mod"]
    # a later version names itself in Software, same length
    assert header_of(exif_mod_header(app1=exif_mod_app1(software="v0.27"))).kind == "exif-mod"
    # the same layout at another length is not the mod's header
    longer = header_of(exif_mod_header(app1=exif_app1(make="Kodak", model="Charmera", software="v0.26", size=0xE0)))
    assert longer.kind == "resave-or-build" and "a Kodak/Charmera EXIF header of 0x350 bytes, not the EXIF mod's 0x340" in longer.reasons
    # the APP1 after the SOF0, or a second APP1, is not the mod's layout either
    moved = b"\xff\xd8" + sof() + exif_mod_app1() + bare_header()[2 + len(sof()):]
    assert header_of(moved).kind == "resave-or-build"
    two = b"\xff\xd8" + exif_mod_app1() + exif_mod_app1() + bare_header()[2:]
    assert header_of(two).kind == "resave-or-build"


def test_an_exif_fix_copy_is_told_from_the_exif_mod(tmp_path):
    """The toolkit's exif-fix writes Make Kodak and Model Charmera on copies, with no Software tag, and keeps every
    other segment: a copy of a v006-family photo keeps the 0x590 layout's segments, a copy of a v012 photo gets its
    APP1 right after the SOI. Neither is the EXIF mod, and each still names the family of the photo it came from."""
    from_still = header_of(exif_fixed(still_header()))
    assert from_still.markers == photo_id.STILL_MARKERS and from_still.length != photo_id.STILL_HEADER_LEN
    assert (from_still.exif.make, from_still.exif.model, from_still.exif.software) == ("Kodak", "Charmera", "")
    assert photo_id.verdict(from_still) == ("exif-fix", "v006-v008_test", [])
    from_bare = header_of(exif_fixed(bare_header()))
    assert from_bare.markers == photo_id.EXIF_MOD_MARKERS and from_bare.length != photo_id.EXIF_MOD_HEADER_LEN
    assert photo_id.verdict(from_bare) == ("exif-fix", "v012", [])
    assert photo_id.verdict(header_of(exif_fixed(exif_mod_header()))) == ("exif-fix", "v012", [])
    text = report(tmp_path, exif_fixed(still_header()))
    assert "an exif-fix copy: Kodak/Charmera EXIF without Software" in text
    assert conclusion(text) == (photo_id.CONCLUSIONS["exif-fix"] + "; that is " + photo_id.FAMILIES["v006-v008_test"])
    assert "not the EXIF mod" in conclusion(text)
    # Kodak/Charmera with a Software tag on the v006 family's layout: neither exif-fix nor the EXIF mod writes that
    h = header_of(still_header(app1=exif_app1(make="Kodak", model="Charmera")))
    assert h.kind == "resave-or-build" and any("neither the EXIF mod nor exif-fix writes" in r for r in h.reasons)


def test_an_exif_fix_keep_make_copy_of_a_v006_family_photo(tmp_path):
    """exif-fix --keep-make keeps Generalplus/CBB3 on a copy of a 0x590 photo and writes no Software tag: an exif-fix
    copy of a v006-family photo, not 'unknown' and not the camera's own photo (which always has Software)."""
    out, res = exif.fix_jpeg(still_header() + b"\x12\x34" * 100 + b"\xff\xd9", datetime(2026, 11, 14, 12, 0, 0),
                             keep_make=True)
    assert (res.make, res.model) == ("Generalplus", "CBB3")
    h = header_of(out)
    assert h.markers == photo_id.STILL_MARKERS and h.length != photo_id.STILL_HEADER_LEN
    assert (h.exif.make, h.exif.model, h.exif.software) == ("Generalplus", "CBB3", "")
    assert photo_id.verdict(h) == ("exif-fix", "v006-v008_test", [])
    text = report(tmp_path, out)
    assert "an exif-fix copy: Generalplus/CBB3 (--keep-make) EXIF without Software" in text
    assert conclusion(text) == (photo_id.CONCLUSIONS["exif-fix"] + "; that is " + photo_id.FAMILIES["v006-v008_test"])
    # on a bare photo there is no Make to keep, so --keep-make writes Kodak/Charmera: the same exif-fix copy as without
    out, _ = exif.fix_jpeg(bare_header() + b"\x12\x34" * 100 + b"\xff\xd9", datetime(2026, 11, 14, 12, 0, 0),
                           keep_make=True)
    assert photo_id.verdict(header_of(out)) == ("exif-fix", "v012", [])
    # the camera's own photo, with its Software tag, stays a camera photo
    assert header_of(still_header()).kind == "still"


RESAVED = "re-saved by another program: says nothing about the camera"
EITHER = ("no known build writes this header as it is; another program saved it again, or another build wrote it; "
          "nothing here tells which")


def test_a_mark_of_an_editor_or_a_re_encode_shows_a_re_saved_photo(tmp_path):
    """Only what names an editor or a re-encode makes a re-save: XMP, APP2, APP13, APP14, a progressive frame, a size
    taller than wide. The camera's encoder writes none of it, in either known build."""
    rotated = header_of(bare_header(width=1080, height=1440))
    assert rotated.kind == "resave" and rotated.reasons == ["a rotated size (1080x1440, taller than wide)"]
    progressive = header_of(bare_header().replace(sof(), sof(marker=0xC2)))
    assert progressive.kind == "resave" and "a progressive frame (SOF2)" in progressive.reasons
    for marker, payload, why in ((0xE1, b"http://ns.adobe.com/xap/1.0/\x00<x:xmpmeta/>", "an XMP block"),
                                 (0xE2, b"ICC_PROFILE\x00\x01\x01", "an ICC profile or MPF APP2 segment"),
                                 (0xED, b"Photoshop 3.0\x00", "a Photoshop APP13 segment"),
                                 (0xEE, b"Adobe\x00\x64\x00\x00\x00\x00\x01", "an Adobe APP14 segment")):
        h = header_of(b"\xff\xd8" + seg(marker, payload) + bare_header()[2:])
        assert h.kind == "resave" and h.reasons == [why], why
    phone = (b"\xff\xd8" + JFIF + exif_app1(make="Apple", model="iPhone 15", software="17.1", size=200)
             + seg(0xE2, b"ICC_PROFILE\x00\x01\x01") + seg(0xDB, b"\x00" + bytes(range(2, 66))) + sof(4032, 3024) + DHT + SOS)
    h = header_of(phone)
    assert h.kind == "resave" and h.reasons == ["an ICC profile or MPF APP2 segment"] and h.qualities == []
    text = report(tmp_path, phone)
    assert "written by another program" in text and "not IJG" in text and conclusion(text) == RESAVED


def test_what_another_program_or_another_build_could_write_tells_neither(tmp_path):
    """An EXIF Make other than Generalplus (and not the EXIF mod's or exif-fix's Kodak), a JFIF APP0, the still
    comment without its EXIF block and tables the camera's encoder never writes: no known build writes such a header,
    but another build could as well as another program, so nothing here tells which (the round-two review's cases
    among them)."""
    kodak = header_of(still_header(app1=exif_app1(make="KODAK")))            # the still template exactly, Make KODAK
    assert kodak.kind == "resave-or-build" and kodak.reasons == ["EXIF Make 'KODAK'"]
    jfif = header_of(b"\xff\xd8" + JFIF + bare_header()[2:])                  # the bare template and a JFIF APP0
    assert jfif.kind == "resave-or-build" and jfif.reasons[0] == "a JFIF APP0 segment"
    alone = ["the still template's comment without its EXIF block"]
    still_comment = header_of(b"\xff\xd8" + sof() + seg(0xFE, b"GPEncoder      ") + dqt(80) + DRI + DHT + SOS)
    assert still_comment.kind == "resave-or-build" and still_comment.reasons == alone
    assert header_of(still_header(app1=b"")).reasons == alone
    assert header_of(still_header(before=JFIF)).kind == "resave-or-build"
    # Make Kodak with another Model, a phone photo with no editor's mark, and tables the encoder never writes, with
    # the GPEncoder comment or without it
    other_kodak = header_of(exif_mod_header(app1=exif_app1(make="Kodak", model="EasyShare", software="1.0",
                                                           size=photo_id.EXIF_MOD_APP1_LEN)))
    assert other_kodak.kind == "resave-or-build" and "EXIF Make 'Kodak'" in other_kodak.reasons
    phone = (b"\xff\xd8" + JFIF + exif_app1(make="Apple", model="iPhone 15", software="17.1", size=200)
             + seg(0xDB, b"\x00" + bytes(range(2, 66))) + sof(4032, 3024) + DHT + SOS)
    assert header_of(phone).kind == "resave-or-build"
    not_ijg = bare_header().replace(dqt(80), seg(0xDB, b"\x00" + bytes([2] * 64)) + seg(0xDB, b"\x01" + bytes([3] * 64)))
    h = header_of(not_ijg)
    assert h.kind == "resave-or-build" and h.qualities == [] and h.reasons == ["quantisation tables no IJG quality reproduces"]
    stripped_bare = b"\xff\xd8" + seg(0xDB, b"\x00" + bytes(range(2, 66))) + sof() + DHT + SOS
    h = header_of(stripped_bare)
    assert h.kind == "resave-or-build" and "quantisation tables no IJG quality reproduces" in h.reasons
    assert "no GPEncoder comment" in h.reasons
    text = report(tmp_path, still_header(app1=exif_app1(make="KODAK")))
    assert "header:      no template as it is, 0x590 bytes" in text and "differs:     EXIF Make 'KODAK'" in text
    assert conclusion(text) == EITHER and photo_id.CONCLUSIONS["resave-or-build"] == EITHER


def test_a_header_without_tables_leaves_nothing_to_conclude(tmp_path):
    """A header without its DQT or its DHT segments is cut short, or comes from an encoder that leaves them out:
    nothing to conclude from it (the round-two review's SOI SOF0 SOS, and SOI APP1 with a truncated TIFF then SOS,
    among them)."""
    no_tables = ("a header without its quantisation tables (DQT) or its Huffman tables (DHT) is cut short, or comes from "
                 "an encoder that leaves them out: nothing to conclude from it")
    for data, missing in ((b"\xff\xd8" + sof() + SOS, ["no DQT segment", "no DHT segment"]),
                          (b"\xff\xd8" + seg(0xE1, b"Exif\x00\x00II") + SOS, ["no DQT segment", "no DHT segment"]),
                          (b"\xff\xd8" + sof() + dqt(80) + SOS, ["no DHT segment"])):
        h = header_of(data)
        assert h.kind == "no-tables" and h.reasons == missing, data
        text = report(tmp_path, data)
        assert "header:      tables missing" in text and conclusion(text) == no_tables
    assert "quality:     none (no quantisation tables)" in report(tmp_path, b"\xff\xd8" + sof() + SOS)


def test_a_header_neither_template_and_no_program_named_is_unknown(tmp_path):
    other_model = header_of(still_header(model="CBB4         "))
    assert other_model.kind == "unknown" and other_model.reasons == ["EXIF Model 'CBB4' and Software '20150615'"]
    no_dri = bare_header().replace(DRI, b"")
    h = header_of(no_dri)
    assert h.kind == "unknown" and "no DRI 0" in h.reasons and h.qualities == [80]
    text = report(tmp_path, no_dri)
    assert "header:      no template, " in text and conclusion(text).startswith("a header no known build writes")
    assert photo_id.verdict(h)[:2] == ("unknown", None)


def test_the_templates_scan_header_and_quantisation_segments_are_part_of_them():
    """Every template ends with the same scan header and holds two 8-bit DQT segments, tables 0 then 1: a header that
    differs in either is not one the known builds write, whatever its length (the round-three fix review's cases)."""
    cr_on_table_0 = SOS.replace(bytes([3, 0x11]), bytes([3, 0x00]))
    h = header_of(still_header().replace(SOS, cr_on_table_0))
    assert h.kind == "unknown" and "another scan header (SOS) than the templates'" in h.reasons
    # both tables in one DQT segment, then an empty one: still 0x590 bytes and quality 80, but not the template
    tables = dqt(80)
    one_segment = seg(0xDB, tables[4:69] + tables[73:]) + seg(0xDB, b"")
    h = header_of(still_header().replace(tables, one_segment))
    assert h.length == photo_id.STILL_HEADER_LEN and h.qualities == [80] and h.kind == "unknown"
    assert "other quantisation segments than the templates' two (8-bit tables 0 and 1)" in h.reasons
    assert header_of(still_header()).kind == "still" and header_of(bare_header()).kind == "bare"


def test_ijg_qualities_are_found_exactly():
    for q in range(1, 101):
        tables = {0: (0, photo_id.ijg_table(photo_id.IJG_LUMINANCE, q)), 1: (0, photo_id.ijg_table(photo_id.IJG_CHROMINANCE, q))}
        assert q in photo_id.ijg_qualities(tables), q
    assert photo_id.ijg_qualities({0: (0, photo_id.ijg_table(photo_id.IJG_LUMINANCE, 80))}) == []
    mixed = {0: (0, photo_id.ijg_table(photo_id.IJG_LUMINANCE, 80)), 1: (0, photo_id.ijg_table(photo_id.IJG_CHROMINANCE, 75))}
    assert photo_id.ijg_qualities(mixed) == []


# --- only the header is read -------------------------------------------------------------------------------------


@pytest.fixture
def reads(monkeypatch):
    """Every (offset, bytes returned) photo_id reads from a file."""
    seen = []
    real = photo_id._reader

    def recording(f):
        read = real(f)

        def wrapped(offset, n):
            out = read(offset, n)
            seen.append((offset, len(out)))
            return out
        return wrapped

    monkeypatch.setattr(photo_id, "_reader", recording)
    return seen


def test_nothing_after_the_sos_header_is_read(tmp_path, reads):
    for header in (still_header(), bare_header(), FIXTURE.read_bytes()):
        reads.clear()
        report(tmp_path, header + b"\x55" * 0x30000 + b"\xff\xd9")
        assert max(o + n for o, n in reads) == len(header)


def test_nothing_past_the_first_64_kib_is_read(tmp_path, reads):
    big = b"\xff\xd8" + seg(0xE2, b"\x00" * 0xFFF0) + seg(0xE2, b"\x00" * 0xFFF0) + bare_header()[2:]
    text = report(tmp_path, big)
    assert max(o + n for o, n in reads) <= photo_id.HEADER_LIMIT
    assert "runs past the first 64 KiB, and nothing past them is read" in text and conclusion(text) == photo_id.CUT_SHORT
    cut = report(tmp_path, bare_header()[:0x100], "cut.jpg")
    assert "is cut off" in cut and conclusion(cut) == photo_id.CUT_SHORT


def test_the_command_line(tmp_path, capsys):
    good = tmp_path / "PICT0001.jpg"
    good.write_bytes(still_header())
    assert cli.main(["photo-id", str(good), str(FIXTURE)]) == 0
    out = capsys.readouterr().out
    assert out.count("conclusion:") == 2 and STILL in out and BARE in out
    assert cli.main(["photo-id", str(good), str(tmp_path / "missing.jpg")]) == 2
    assert "error:       not a file" in capsys.readouterr().out
    other = tmp_path / "notes.txt"
    other.write_bytes(b"hello")
    assert cli.main(["photo-id", str(other)]) == 0
    assert "neither a JPEG" in capsys.readouterr().out


def test_help_explains_what_the_conclusions_mean():
    parser = cli.build_parser()
    sub = next(a for a in parser._actions if a.dest == "command").choices["photo-id"]
    text = " ".join(sub.format_help().split()).replace("- ", "-")     # argparse may wrap a line after a hyphen
    for words in ("only the header", "never more than the first 64 KiB", "Make Generalplus, Model CBB3 and Software 20150615",
                  "The factory build v012 (OV9732 cameras) and our v012 builds without EXIF dates write a bare 0x270-byte header",
                  "Our EXIF mod writes 0x340 bytes", "a photo cannot tell v006 from v008_test",
                  "the v006 family writes the same bare header for thumbnails and video frames",
                  "'A copy the toolkit's exif-fix made'", "it is not the EXIF mod",
                  "says nothing about the camera",
                  "an XMP block, an APP2, APP13 or APP14 segment, a progressive frame or a size taller than wide",
                  "'No known build writes this header as it is': an EXIF Make other than these, a JFIF or other APP0 "
                  "segment, the still comment without its EXIF block, or tables the camera's encoder never writes",
                  "another program may have saved the file again, or another build may have written it, and nothing here "
                  "tells which", "A header without its quantisation tables or its Huffman tables is cut short, or comes from "
                  "an encoder that leaves them out",
                  "does not tell the builds apart (INFERRED), any other frame header is not the one they write",
                  "a JUNK text other than 'GP$322 Generalplus AviPackerV3 20140916', the packer text v006, v008_test and "
                  "v012 all carry, shows that other code wrote the file",
                  "a photo taken after a flash shows the flashed firmware", "not a frame taken out of a video",
                  "Nothing here says whether a firmware fits a camera", "use identify"):
        assert words in text, words
    assert "USB" not in text                                   # the bare template's USB-camera role is not shown
    assert "USB" not in photo_id.__doc__


# --- AVI ------------------------------------------------------------------------------------------------------------


def chunk(cid, body):
    return cid + struct.pack("<I", len(body)) + body + (b"\x00" if len(body) & 1 else b"")


def riff_list(kind, *chunks):
    return chunk(b"LIST", kind + b"".join(chunks))


def avi(frame: bytes) -> bytes:
    """An MJPEG AVI shaped like what AviPackerV3 writes: hdrl (avih, strl with strh and strf), a JUNK chunk with the
    packer's signature, and a movi list whose first chunk is the frame (followed by dummy image data)."""
    avih = struct.pack("<10I", 33333, 0, 0, 0x10, 1, 0, 1, 0, 1280, 720) + b"\x00" * 16
    strf = struct.pack("<IiiHH4sIiiII", 40, 1280, 720, 1, 24, b"MJPG", 0, 0, 0, 0, 0)
    body = (b"AVI " + riff_list(b"hdrl", chunk(b"avih", avih), riff_list(b"strl", chunk(b"strh", b"vids" + b"\x00" * 52),
                                                                          chunk(b"strf", strf)))
            + chunk(b"JUNK", b"GP$322 Generalplus AviPackerV3 20140916\x00" + b"\x00" * 200)
            + riff_list(b"movi", chunk(b"00dc", frame + b"\x66" * 5000 + b"\xff\xd9")))
    return b"RIFF" + struct.pack("<I", len(body)) + body


def test_the_frame_header_of_a_video_does_not_tell_the_known_builds_apart(tmp_path, reads):
    frame = bare_header(quality=50, width=1280, height=720, y=0x21)
    data = avi(frame)
    text = report(tmp_path, data, "MOVI0001.avi")
    assert "an AVI, 1280x720, 'MJPG', 30.00 frames per second" in text and "GP$322 Generalplus AviPackerV3 20140916" in text
    assert "  header:      the bare template" in text
    video = ("the bare frame header the v006 family writes for video (v012 holds the same template): it does not tell "
             "the builds apart (INFERRED)")
    assert conclusion(text) == video == photo_id.VIDEO
    frame_at = data.index(b"00dc") + 8
    assert max(o + n for o, n in reads) == frame_at + len(frame)     # nothing of the frame's image data is read
    # the known builds write no APP0 at all, not even the 'AVI1' one MJPEG writers put in each frame
    marked = b"\xff\xd8" + seg(0xE0, b"AVI1\x00" + b"\x00" * 9) + frame[2:]
    text = report(tmp_path, avi(marked), "MOVI0002.avi")
    assert "an 'AVI1' APP0 segment" in text and conclusion(text) == EITHER
    # frames with an editor's mark were written again by another program; a JFIF frame tells neither apart
    edited = b"\xff\xd8" + seg(0xED, b"Photoshop 3.0\x00") + frame[2:]
    assert conclusion(report(tmp_path, avi(edited), "MOVI0003.avi")) == RESAVED
    jfif = b"\xff\xd8" + JFIF + frame[2:]
    assert conclusion(report(tmp_path, avi(jfif), "MOVI0004.avi")) == EITHER
    # the template fixes its frame, its restart interval and its four Huffman tables, so a frame that differs in any of
    # them is not the one the known builds write, whatever its segment order (the round-three review's DHT and DRI)
    t0 = photo_id.STANDARD_DHT[0]
    other_dht = frame.replace(t0, t0[:-1] + bytes([t0[-1] ^ 1]))
    assert conclusion(report(tmp_path, avi(other_dht), "MOVI0007.avi")) == EITHER
    other_dri = frame.replace(DRI, seg(0xDD, b"\x00\x10"))
    text = report(tmp_path, avi(other_dri), "MOVI0008.avi")
    assert "no DRI 0" in text and conclusion(text) == photo_id.NOT_VIDEO
    # MJPEG writers may leave the Huffman tables out of a frame; the known builds' frames carry all four
    no_dht = b"\xff\xd8" + seg(0xE0, b"AVI1\x00" + b"\x00" * 9) + frame[2:].replace(DHT, b"")
    text = report(tmp_path, avi(no_dht), "MOVI0009.avi")
    assert "no DHT segment" in text and conclusion(text) == photo_id.NOT_VIDEO
    assert "not a camera" not in text
    # the rate control moves one IJG quality for both tables, so tables no single IJG quality reproduces are not the
    # known builds' (the round-three fix review: empty, mixed-quality, 16-bit and off-by-one tables)
    rated = bytearray(frame)
    rated[frame.index(b"\xff\xdb") + 15] += 1
    assert header_of(bytes(rated)).qualities == []
    assert conclusion(report(tmp_path, avi(bytes(rated)), "MOVI0010.avi")) == EITHER
    mixed = frame.replace(dqt(50), dqt(45, chroma=50))
    assert header_of(mixed).qualities == [] and conclusion(report(tmp_path, avi(mixed), "MOVI0011.avi")) == EITHER
    empty = frame.replace(dqt(50), seg(0xDB, b"") + seg(0xDB, b""))
    assert conclusion(report(tmp_path, avi(empty), "MOVI0012.avi")) == EITHER
    wide = frame.replace(dqt(50), b"".join(
        seg(0xDB, bytes([0x10 | tq]) + b"".join(struct.pack(">H", v) for v in tables[5 + 69 * tq:69 + 69 * tq]))
        for tq, tables in ((0, dqt(50)), (1, dqt(50)))))
    text = report(tmp_path, avi(wide), "MOVI0013.avi")
    assert "other quantisation segments than the templates' two" in text and conclusion(text) == photo_id.NOT_VIDEO


def test_a_junk_text_other_than_the_packers_shows_other_code_wrote_the_video(tmp_path):
    """Both known builds carry the packer text the JUNK chunk holds; another text shows other code, whatever the frames."""
    frame = bare_header(quality=50, width=1280, height=720, y=0x21)
    other = avi(frame).replace(photo_id.AVI_PACKER, b"Lavf58.29.100".ljust(len(photo_id.AVI_PACKER), b"\x00"))
    text = report(tmp_path, other, "MOVI0005.avi")
    assert "junk text:   'Lavf58.29.100'" in text and "  header:      the bare template" in text
    assert conclusion(text) == ("other code wrote this file: its JUNK text is not 'GP$322 Generalplus AviPackerV3 20140916', "
                                "the packer text v006, v008_test and v012 all carry")
    # a JUNK chunk that holds no text says nothing either way
    blank = avi(frame).replace(photo_id.AVI_PACKER, bytes(len(photo_id.AVI_PACKER)))
    assert conclusion(report(tmp_path, blank, "MOVI0006.avi")) == photo_id.VIDEO


def test_the_packer_text_is_in_both_known_builds(stock_image):
    assert stock_image.count(photo_id.AVI_PACKER) == 1
    if FILM_MODS_RESTORE.is_file():
        assert FILM_MODS_RESTORE.read_bytes().count(photo_id.AVI_PACKER) == 1


# --- the real files --------------------------------------------------------------------------------------------------


def _from_template(template: bytes, quality: int, width: int, height: int) -> bytes:
    """A template filled in as the encoder fills it in: the size, the 2x2 sampling and the tables; for the still
    template also Model 'CBB3' and Software '20150615', which code writes over the template's own."""
    h = bytearray(template)
    h[7:11] = struct.pack(">HH", height, width)
    h[0xD] = 0x22
    at = h.find(b"\xff\xdb")
    h[at: at + 138] = dqt(quality)
    if len(h) == photo_id.STILL_HEADER_LEN:
        h[0x32C: 0x32C + 13] = b"CBB3" + b" " * 9
        h[0x34C: 0x34C + 8] = b"20150615"
    return bytes(h)


def test_stock_image_templates_and_stored_photos(stock_image):
    """The templates as the public dump holds them, and the two photos its camera stored: the same header kinds, the
    standard Huffman tables and the JPEG standard's quantisation tables."""
    bare = stock_image[T0: T0 + photo_id.BARE_HEADER_LEN]
    assert stock_image[T2: T2 + len(bare)] == bare and stock_image[T3: T3 + len(bare)] == bare
    still = stock_image[T1: T1 + photo_id.STILL_HEADER_LEN]
    segments, end, problem = photo_id.read_segments(photo_id._reader(io.BytesIO(still)))
    assert problem is None and end == photo_id.STILL_HEADER_LEN
    assert tuple(s.payload for s in segments if s.marker == 0xC4) == photo_id.STANDARD_DHT
    assert tuple(s.marker for s in segments) == photo_id.STILL_MARKERS
    # the encoder's base tables (VA 0x1088B8 and 0x108938), 16-bit words
    assert struct.unpack("<64H", stock_image[0x1088B8 + image.VA_OFFSET: 0x1088B8 + image.VA_OFFSET + 128]) == photo_id.IJG_LUMINANCE
    assert struct.unpack("<64H", stock_image[0x108938 + image.VA_OFFSET: 0x108938 + image.VA_OFFSET + 128]) == photo_id.IJG_CHROMINANCE
    # filled in as for a photo on a card, and as for a video frame
    h = header_of(_from_template(still, 80, 1440, 1080))
    assert h.kind == "still" and h.qualities == [80]
    assert header_of(_from_template(bare, 85, 1440, 1080)).kind == "bare"
    # the photos the dump's camera stored in its own memory, taken without a card: the still header at quality 45
    for slot in image.PHOTO_SLOTS:
        if stock_image[slot: slot + 2] == b"\xff\xd8":
            h = header_of(stock_image[slot: slot + photo_id.STILL_HEADER_LEN + 0x100])
            assert h.kind == "still" and h.length == photo_id.STILL_HEADER_LEN, hex(slot)
            assert h.qualities == [45] and (h.frame.width, h.frame.height) == (960, 720), hex(slot)


@pytest.mark.skipif(not FILM_MODS_RESTORE.is_file(), reason="set CHARMERA_FILM_MODS_RESTORE to the film-mods Charmera_Restore file")
def test_film_mods_build_holds_the_same_templates(stock_image):
    film = FILM_MODS_RESTORE.read_bytes()
    for at, size in ((T0, photo_id.BARE_HEADER_LEN), (T1, photo_id.STILL_HEADER_LEN), (T2, photo_id.BARE_HEADER_LEN),
                     (T3, photo_id.BARE_HEADER_LEN)):
        assert film[at + FILM_MODS_SHIFT: at + FILM_MODS_SHIFT + size] == stock_image[at: at + size], hex(at)


@pytest.mark.skipif(not FACTORY_DUMP.is_file(), reason="set CHARMERA_FACTORY_DUMP to a dump of the factory build v012")
def test_the_factory_build_v012_holds_the_bare_template_and_no_still_one():
    """v012 holds four copies of the bare template and no 0x590 still template; filled in as the encoder fills it in,
    the bare one is the 'bare' kind, and with one EXIF mod APP1 put after its SOI, the 'exif-mod' kind."""
    dump = FACTORY_DUMP.read_bytes()
    assert b"GPEncoder      " not in dump
    first = dump[V012_BARE[0]: V012_BARE[0] + photo_id.BARE_HEADER_LEN]
    for at in V012_BARE:
        t = dump[at: at + photo_id.BARE_HEADER_LEN]
        assert t == first, hex(at)
        segments, end, problem = photo_id.read_segments(photo_id._reader(io.BytesIO(t)))
        assert problem is None and end == photo_id.BARE_HEADER_LEN and segments[2].payload == b"GPEncoder"
        assert tuple(s.marker for s in segments) == photo_id.BARE_MARKERS, hex(at)
    filled = _from_template(first, 80, 1440, 1080)
    assert header_of(filled).kind == "bare"
    assert filled == FIXTURE.read_bytes()            # the owner's camera's original photo header, byte for byte
    assert header_of(b"\xff\xd8" + exif_mod_app1() + filled[2:]).kind == "exif-mod"
