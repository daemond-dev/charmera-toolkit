"""`photo-id`: what the header of a photo says about the firmware that wrote it. Read-only.

Both known builds (image.KNOWN_BUILDS) build every JPEG from header templates in their initialised data, and the
templates are the same bytes in both (VERIFIED in the public dump, where they sit at file 0xFFB78, 0x107448,
0x1079D8 and 0x107C48, and in the film-mods build, where they sit 0xC8 further on):

- the still template (0x107448): 0x590 bytes, SOI, SOF0, a COM of 15 bytes ("GPEncoder" and six spaces, length
  field 0x11), two DQT, DRI 0, the four standard Huffman tables, an APP1 'Exif' block and SOS. Every still photo is
  written from it (the writer at VA 0x6DEC4 writes 0x590 bytes), with Make 'Generalplus', and Model 'CBB3' and
  Software '20150615' written over the template's own by code;
- the bare template (the other three, byte-identical): 0x270 bytes, the same segments without the APP1 block and
  with a 9-byte COM ("GPEncoder", length 0x0B). Both builds use it only for thumbnails, video frames and a Wi-Fi
  stream, never for a still photo.

The camera fills in only the frame size and sampling (SOF), the quantisation tables and, in the still template,
three dates. The tables are the IJG ones (the JPEG standard's Annex K tables at VA 0x1088B8 and 0x108938, scaled by
a quality): 85, 80 or 75 for a photo on a card (settings byte +0x0b), 45 without a card (VERIFIED). So a photo
straight from a camera running either known build has the still header, and a still photo with the bare header, as
the camera saved it in DCIM, was taken by a camera that ran another build at the time. A header with a mark of an
editor or a re-encode (an XMP block, an APP2, APP13 or APP14 segment, a progressive frame, a size taller than wide)
was saved again by another program and says nothing about the camera. An EXIF Make other than Generalplus, a JFIF
or other APP0 segment (the known builds write none, not even MJPEG's 'AVI1'), the still comment without its EXIF
block or tables the encoder never writes show only that neither known build writes the header as it is: another
program may have saved the photo again, or another build may have written it, and nothing in the header tells
which. A header without its quantisation tables or its Huffman tables is cut short, or comes from an encoder that
leaves them out. That one camera's photos look like a known build's does not show that it runs that build: two
builds can share a template.

Only the header is read: each segment on its own, from the SOI up to and including the SOS header, never the
compressed image data after it and never more than the first 64 KiB. For an AVI, the chunk headers up to the first
video frame are read, and that frame's JPEG header. Both builds copy the whole bare template into each video frame
and fill in its size and tables of one IJG quality for both, which the rate control moves from frame to frame
(VERIFIED by static analysis). So a frame header that is the template as filled in does not tell them apart
(INFERRED: no video of either build was examined here), and any other is not the one they write; a JUNK chunk whose
text is not the packer text both carry (AVI_PACKER) shows that other code wrote the file.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

from .exif import EXIF_HEADER, ExifInfo, read_exif

HEADER_LIMIT = 0x10000   # nothing past the first 64 KiB of a file is read

STILL_HEADER_LEN = 0x590
BARE_HEADER_LEN = 0x270
STILL_COMMENT = b"GPEncoder      "     # COM length field 0x11
BARE_COMMENT = b"GPEncoder"            # COM length field 0x0B
# The segments of the two templates, in order (marker bytes after 0xFF).
STILL_MARKERS = (0xD8, 0xC0, 0xFE, 0xDB, 0xDB, 0xDD, 0xC4, 0xC4, 0xC4, 0xC4, 0xE1, 0xDA)
BARE_MARKERS = (0xD8, 0xC0, 0xFE, 0xDB, 0xDB, 0xDD, 0xC4, 0xC4, 0xC4, 0xC4, 0xDA)
# The scan header both templates end with, and their two DQT segments as (payload length, Pq/Tq): 8-bit tables 0, 1.
TEMPLATE_SOS = bytes.fromhex("03010002110311003f00")
TEMPLATE_DQT = [(65, 0x00), (65, 0x01)]
CAMERA_MAKE = "Generalplus"
CAMERA_MODEL = "CBB3"
CAMERA_SOFTWARE = "20150615"
# The text the video packer (AviPackerV3) puts in the JUNK chunk of every AVI it writes: a constant in the public
# build (VERIFIED, static analysis) and present in the film-mods build too (VERIFIED by search).
AVI_PACKER = b"GP$322 Generalplus AviPackerV3 20140916"
XMP_HEADER = b"http://ns.adobe.com/xap/1.0/"

MARKER_NAMES = {0xD8: "SOI", 0xC0: "SOF0", 0xC1: "SOF1", 0xC2: "SOF2", 0xC4: "DHT", 0xDB: "DQT", 0xDD: "DRI", 0xDA: "SOS",
                0xFE: "COM", 0xE0: "APP0", 0xE1: "APP1", 0xE2: "APP2", 0xED: "APP13", 0xEE: "APP14"}

# The JPEG standard's example quantisation tables (ITU T.81 Annex K.1 and K.2), in natural order, which IJG libjpeg
# scales by its quality setting and the camera's encoder scales the same way.
IJG_LUMINANCE = (16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56,
                 14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
                 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99)
IJG_CHROMINANCE = (17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99,
                   47, 66, 99, 99, 99, 99, 99, 99) + (99,) * 32
# Position in natural order of the k-th value of a DQT segment, which lists the table in zig-zag order.
ZIGZAG = (0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14,
          21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60,
          61, 54, 47, 55, 62, 63)


def _dht(table_class_id: int, bits: Tuple[int, ...], values: bytes) -> bytes:
    return bytes([table_class_id]) + bytes(bits) + values


# The standard Huffman tables (ITU T.81 Annex K.3), DC and AC for luminance and chrominance, in the order and form of
# the four DHT segments of both templates.
STANDARD_DHT = (
    _dht(0x00, (0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0), bytes(range(12))),
    _dht(0x10, (0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D), bytes.fromhex(
        "01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738"
        "393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5"
        "a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa")),
    _dht(0x01, (0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0), bytes(range(12))),
    _dht(0x11, (0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77), bytes.fromhex(
        "000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a3536"
        "3738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999a"
        "a2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa")),
)

CONCLUSIONS = {
    "still": "the still-photo header both known builds write",
    "bare": ("a bare header that neither known build writes for a still photo: if this is a photo as the camera saved it "
             "in DCIM, the camera ran another build when it took it"),
    "resave": "re-saved by another program: says nothing about the camera",
    "resave-or-build": ("neither known build writes this header as it is; another program saved it again, or another build "
                        "wrote it; nothing here tells which"),
    "unknown": ("a header neither known build writes, and nothing in it names another program: nothing here can tell what "
                "wrote it"),
    "no-tables": ("a header without its quantisation tables (DQT) or its Huffman tables (DHT) is cut short, or comes from "
                  "an encoder that leaves them out: nothing to conclude from it"),
}
VIDEO = "the frame header both known builds write for video: it does not tell the known builds apart (INFERRED)"
NOT_VIDEO = "not the frame header the known builds write for video: nothing here can tell what wrote it"
OTHER_PACKER = ("other code wrote this file: its JUNK text is not " + repr(AVI_PACKER.decode("ascii"))
                + ", the packer text both known builds carry")


# --- Reading the header -----------------------------------------------------------------------------------------


@dataclass
class Segment:
    marker: int
    offset: int          # of the 0xFF that starts it, in the file
    payload: bytes       # after the length field


def _reader(f) -> Callable[[int, int], bytes]:
    """read(offset, n): up to n bytes at offset, never past HEADER_LIMIT. identify opens the file unbuffered, so
    nothing is read from it beyond what is asked for."""
    def read(offset: int, n: int) -> bytes:
        n = min(n, HEADER_LIMIT - offset)
        if n <= 0:
            return b""
        f.seek(offset)
        out = b""
        while len(out) < n:
            chunk = f.read(n - len(out))
            if not chunk:
                break
            out += chunk
        return out
    return read


def read_segments(read: Callable[[int, int], bytes], start: int = 0) -> Tuple[List[Segment], int, Optional[str]]:
    """The segments from the SOI at `start` up to and including the SOS header, each read on its own, as
    (segments, offset just past the SOS header, why the header could not be read whole or None)."""
    if read(start, 2) != b"\xff\xd8":
        return [], start, "no JPEG start marker (FF D8)"
    segments = [Segment(0xD8, start, b"")]
    pos = start + 2
    while True:
        head = read(pos, 4)
        if len(head) >= 2 and head[0] != 0xFF:
            return segments, pos, f"no marker at {pos:#x}"
        marker = head[1] if len(head) >= 2 else None
        if marker == 0xFF:                         # a fill byte before the marker
            pos += 1
            continue
        if marker is not None and (marker == 0x01 or 0xD0 <= marker <= 0xD7):
            pos += 2
            continue
        if marker == 0xD9:
            return segments, pos, "an end marker before the start of scan"
        if len(head) < 4:
            return segments, pos, ("the first 64 KiB end before the start of scan, and nothing past them is read"
                                   if pos + 4 > HEADER_LIMIT else "the file ends before the start of scan")
        length = struct.unpack(">H", head[2:4])[0]
        payload = read(pos + 4, length - 2) if length >= 2 else b""
        if length < 2 or len(payload) < length - 2:
            why = ("runs past the first 64 KiB, and nothing past them is read" if pos + 2 + length > HEADER_LIMIT
                   else "is cut off")
            return segments, pos, f"the {MARKER_NAMES.get(marker, f'{marker:02X}')} segment at {pos:#x} {why}"
        segments.append(Segment(marker, pos, payload))
        pos += 2 + length
        if marker == 0xDA:
            return segments, pos, None


# --- What the segments hold ---------------------------------------------------------------------------------------


def ijg_table(base: Tuple[int, ...], quality: int, baseline: bool = True) -> List[int]:
    """libjpeg's jpeg_add_quant_table: the base table scaled for a quality of 1..100, in natural order."""
    scale = 5000 // quality if quality < 50 else 200 - 2 * quality
    top = 255 if baseline else 32767
    return [min(top, max(1, (b * scale + 50) // 100)) for b in base]


def quant_tables(segments: List[Segment]) -> Dict[int, Tuple[int, List[int]]]:
    """{table id: (precision, values in natural order)} from every DQT segment; a damaged one ends the reading."""
    tables: Dict[int, Tuple[int, List[int]]] = {}
    for s in segments:
        if s.marker != 0xDB:
            continue
        p = 0
        while p < len(s.payload):
            pq, tq = s.payload[p] >> 4, s.payload[p] & 0x0F
            size = 128 if pq else 64
            raw = s.payload[p + 1: p + 1 + size]
            if len(raw) < size:
                break
            zigzag = list(struct.unpack(">64H", raw)) if pq else list(raw)
            natural = [0] * 64
            for k, v in enumerate(zigzag):
                natural[ZIGZAG[k]] = v
            tables[tq] = (pq, natural)
            p += 1 + size
    return tables


def ijg_qualities(tables: Dict[int, Tuple[int, List[int]]]) -> List[int]:
    """The IJG qualities that reproduce the tables exactly: table 0 the luminance one, table 1 the chrominance one,
    and no other. Empty when none does."""
    if set(tables) != {0, 1}:
        return []
    return [q for q in range(1, 101)
            if all(values == ijg_table(base, q, baseline=not pq)
                   for (pq, values), base in ((tables[0], IJG_LUMINANCE), (tables[1], IJG_CHROMINANCE)))]


@dataclass
class Frame:
    marker: int
    width: int
    height: int
    components: List[Tuple[int, int, int]]   # (id, sampling byte, table)

    @property
    def sampling(self) -> str:
        names = ("Y", "Cb", "Cr") if len(self.components) == 3 else tuple(f"C{c[0]}" for c in self.components)
        text = ", ".join(f"{n} {s >> 4}x{s & 15}" for n, (_, s, _) in zip(names, self.components))
        kinds = {(0x22, 0x11, 0x11): "4:2:0", (0x21, 0x11, 0x11): "4:2:2", (0x11, 0x11, 0x11): "4:4:4"}
        kind = kinds.get(tuple(c[1] for c in self.components))
        return text + (f" ({kind})" if kind else "")


def frame_of(segments: List[Segment]) -> Optional[Frame]:
    for s in segments:
        if 0xC0 <= s.marker <= 0xCF and s.marker not in (0xC4, 0xC8, 0xCC) and len(s.payload) >= 6:
            _precision, height, width, n = struct.unpack(">BHHB", s.payload[:6])
            comps = [tuple(s.payload[6 + 3 * i: 9 + 3 * i]) for i in range(n) if len(s.payload) >= 9 + 3 * i]
            return Frame(s.marker, width, height, comps)
    return None


def exif_of(segments: List[Segment]) -> Optional[ExifInfo]:
    for s in segments:
        if s.marker == 0xE1 and s.payload[:6] == EXIF_HEADER:
            return read_exif(s.payload[6:])
    return None


@dataclass
class Header:
    """What the header of one JPEG says, and the kind it is: 'still', 'bare', 'resave', 'resave-or-build', 'unknown'
    or 'no-tables' (the keys of CONCLUSIONS)."""
    segments: List[Segment]
    length: int
    problem: Optional[str]
    frame: Optional[Frame]
    qualities: List[int]
    comments: List[bytes]
    exif: Optional[ExifInfo]
    kind: str = "unknown"
    reasons: List[str] = field(default_factory=list)    # what shows another program, or what differs from both templates

    @property
    def markers(self) -> Tuple[int, ...]:
        return tuple(s.marker for s in self.segments)


def _other_tables(h: Header) -> List[str]:
    """How the quantisation and Huffman tables differ from those the camera's encoder writes; empty when they do not."""
    out = []
    if not h.qualities:
        out.append("quantisation tables no IJG quality reproduces")
    if tuple(s.payload for s in h.segments if s.marker == 0xC4) != STANDARD_DHT:
        out.append("other Huffman tables than the standard four")
    return out


def _camera_structure(h: Header) -> List[str]:
    """How the frame, the quantisation segments, the restart interval and the scan header differ from those both
    templates share; empty when they do not."""
    out = []
    f = h.frame
    if f is None or f.marker != 0xC0:
        out.append("not a baseline frame (SOF0)")
    elif len(f.components) != 3 or [c[0] for c in f.components] != [1, 2, 3] or [c[2] for c in f.components] != [0, 1, 1]:
        out.append("not three components with the templates' table numbers")
    elif f.components[0][1] not in (0x21, 0x22) or f.components[1][1] != 0x11 or f.components[2][1] != 0x11:
        out.append(f"another sampling ({f.sampling})")
    if [(len(s.payload), s.payload[0] if s.payload else None) for s in h.segments if s.marker == 0xDB] != TEMPLATE_DQT:
        out.append("other quantisation segments than the templates' two (8-bit tables 0 and 1)")
    if [s.payload for s in h.segments if s.marker == 0xDD] != [b"\x00\x00"]:
        out.append("no DRI 0")
    if [s.payload for s in h.segments if s.marker == 0xDA] != [TEMPLATE_SOS]:
        out.append("another scan header (SOS) than the templates'")
    return out


def _an_editor(h: Header) -> List[str]:
    """What in the header names an editor or a re-encode: the camera's encoder writes none of it, in either build."""
    out = []
    for s in h.segments:
        if s.marker == 0xE1 and s.payload.startswith(XMP_HEADER):
            out.append("an XMP block")
        elif s.marker == 0xE2:
            out.append("an ICC profile or MPF APP2 segment")
        elif s.marker == 0xED:
            out.append("a Photoshop APP13 segment")
        elif s.marker == 0xEE:
            out.append("an Adobe APP14 segment")
    if h.frame is not None and h.frame.height > h.frame.width:
        out.append(f"a rotated size ({h.frame.width}x{h.frame.height}, taller than wide)")
    if h.frame is not None and h.frame.marker == 0xC2:
        out.append("a progressive frame (SOF2)")
    return out


def _resaved_or_another_build(h: Header) -> List[str]:
    """What neither known build writes, but another program that saves a photo again could write as well as another
    build: from these alone, nothing tells which of the two wrote the header. The known builds write no APP0 at all,
    not even the 'AVI1' one that MJPEG writers put in video frames (VERIFIED: neither image holds the bytes)."""
    out = []
    for s in h.segments:
        if s.marker == 0xE0:
            out.append("a JFIF APP0 segment" if s.payload[:5] == b"JFIF\x00"
                       else "an 'AVI1' APP0 segment" if s.payload[:4] == b"AVI1" else "an APP0 segment")
    if h.exif is not None and h.exif.make and h.exif.make != CAMERA_MAKE:
        out.append(f"EXIF Make {h.exif.make!r}")
    if STILL_COMMENT in h.comments and h.exif is None:
        out.append("the still template's comment without its EXIF block")
    return out + _other_tables(h)


def classify(h: Header) -> None:
    """Set h.kind and h.reasons."""
    if h.problem:
        h.kind, h.reasons = "unknown", [h.problem]
        return
    missing = [f"no {name} segment" for marker, name in ((0xDB, "DQT"), (0xC4, "DHT")) if marker not in h.markers]
    if missing:
        h.kind, h.reasons = "no-tables", missing
        return
    structure = _camera_structure(h)
    camera = not structure and not _other_tables(h)
    exif = h.exif
    if (camera and h.markers == STILL_MARKERS and h.length == STILL_HEADER_LEN and h.comments == [STILL_COMMENT]
            and exif is not None and (exif.make, exif.model, exif.software) == (CAMERA_MAKE, CAMERA_MODEL, CAMERA_SOFTWARE)
            and h.frame.width >= h.frame.height):
        h.kind = "still"
        return
    if (camera and h.markers == BARE_MARKERS and h.length == BARE_HEADER_LEN and h.comments == [BARE_COMMENT]
            and h.frame.width >= h.frame.height):
        h.kind = "bare"
        return
    editor = _an_editor(h)
    if editor:
        h.kind, h.reasons = "resave", editor
        return
    differs = structure[:]
    if h.markers not in (STILL_MARKERS, BARE_MARKERS):
        differs.append("other segments than either template")
    if not any(c in (STILL_COMMENT, BARE_COMMENT) for c in h.comments):
        differs.append("no GPEncoder comment")
    if exif is not None and (exif.model, exif.software) != (CAMERA_MODEL, CAMERA_SOFTWARE):
        differs.append(f"EXIF Model {exif.model!r} and Software {exif.software!r}")
    either = _resaved_or_another_build(h)
    if either:
        h.kind, h.reasons = "resave-or-build", either + differs
        return
    h.kind, h.reasons = "unknown", differs or [f"a {h.length:#x}-byte header, the size of neither template"]


def read_header(read: Callable[[int, int], bytes], start: int = 0) -> Header:
    segments, end, problem = read_segments(read, start)
    h = Header(segments=segments, length=end - start, problem=problem, frame=frame_of(segments),
               qualities=ijg_qualities(quant_tables(segments)),
               comments=[s.payload for s in segments if s.marker == 0xFE], exif=exif_of(segments))
    classify(h)
    return h


# --- AVI -----------------------------------------------------------------------------------------------------------


@dataclass
class AviHeader:
    width: Optional[int] = None
    height: Optional[int] = None
    usec_per_frame: Optional[int] = None
    codec: Optional[bytes] = None
    junk: List[bytes] = field(default_factory=list)     # text of the JUNK chunks (the packer's signature, when there)
    info: Dict[str, str] = field(default_factory=dict)  # INFO tags read before the frames
    frame_at: Optional[int] = None                       # file offset of the first video frame's data
    problem: Optional[str] = None


def read_avi(read: Callable[[int, int], bytes]) -> AviHeader:
    """The chunk headers of an AVI up to its first video frame (a chunk '##dc' or '##db' inside LIST movi), within the
    first 64 KiB. Chunk bodies are read only for the small header chunks; the frames are not read here."""
    avi = AviHeader()
    head = read(0, 12)
    riff_end = 8 + struct.unpack("<I", head[4:8])[0]
    stack = [(12, riff_end, b"AVI ")]
    while stack:
        pos, end, kind = stack.pop()
        while pos + 8 <= end:
            chunk = read(pos, 8)
            if len(chunk) < 8:
                avi.problem = "no video frame starts within the first 64 KiB"
                return avi
            cid, size = chunk[:4], struct.unpack("<I", chunk[4:8])[0]
            body = pos + 8
            nxt = body + size + (size & 1)
            if cid == b"LIST":
                list_type = read(body, 4)
                if list_type in (b"hdrl", b"strl", b"INFO", b"movi"):
                    stack.append((nxt, end, kind))
                    pos, end, kind = body + 4, min(end, body + size), list_type
                    continue
            elif kind == b"movi" and cid[2:] in (b"dc", b"db"):
                avi.frame_at = body
                return avi
            elif cid == b"avih" and size >= 40:
                avih = read(body, 40)
                if len(avih) == 40:
                    avi.usec_per_frame = struct.unpack("<I", avih[0:4])[0]
                    avi.width, avi.height = struct.unpack("<II", avih[32:40])
            elif cid == b"strf" and size >= 20 and avi.codec is None:
                strf = read(body, 20)
                if len(strf) == 20:
                    avi.codec = strf[16:20]
            elif cid == b"JUNK":
                text = read(body, min(size, 256)).split(b"\x00")
                avi.junk += [t for t in text if len(t) >= 4]
            elif kind == b"INFO" and size <= 256:
                avi.info[cid.decode("ascii", "replace")] = read(body, size).rstrip(b"\x00").decode("ascii", "replace")
            pos = nxt
    avi.problem = "no video frame found before the end of the AVI's lists"
    return avi


# --- The command -----------------------------------------------------------------------------------------------------


def describe_header(h: Header, indent: str = "") -> List[str]:
    """The report lines for one JPEG header (without the file and conclusion lines)."""
    names = " ".join(MARKER_NAMES.get(m, f"{m:02X}") for m in h.markers)
    if h.problem:
        lines = [f"header:      {h.length:#x} bytes read ({names}), then {h.problem}"]
    else:
        what = {"still": "the EXIF still template", "bare": "the bare template, no EXIF block",
                "resave": "written by another program", "resave-or-build": "neither template as it is",
                "unknown": "neither template", "no-tables": "tables missing"}[h.kind]
        lines = [f"header:      {what}, {h.length:#x} bytes ({names})"]
        if h.reasons:
            lines.append(("why:         " if h.kind == "resave" else "differs:     ") + "; ".join(h.reasons))
    if h.frame is not None:
        kind = MARKER_NAMES.get(h.frame.marker, f"SOF {h.frame.marker:02X}")
        lines.append(f"frame:       {h.frame.width}x{h.frame.height} ({kind}), sampling {h.frame.sampling}")
    if h.qualities:
        q = h.qualities
        lines.append("quality:     IJG quality " + (f"{q[0]}" if len(q) == 1 else f"{q[0]} to {q[-1]}")
                     + " reproduces the quantisation tables exactly")
    elif 0xDB in h.markers:
        lines.append("quality:     not IJG (no IJG quality reproduces the quantisation tables exactly)")
    elif h.segments:
        lines.append("quality:     none (no quantisation tables)")
    lines.append("comment:     " + (", ".join(repr(c.decode("latin-1")) for c in h.comments) if h.comments else "none"))
    if h.exif is not None:
        lines.append(f"exif:        Make {h.exif.make!r}, Model {h.exif.model!r}, Software {h.exif.software!r}")
    elif h.segments:
        lines.append("exif:        none")
    return [indent + line for line in lines]


CUT_SHORT = "the header could not be read to the start of scan: nothing to conclude from it"


def identify(path: Path) -> List[str]:
    """The report for one file. Raises OSError when it cannot be read."""
    with open(path, "rb", buffering=0) as f:
        read = _reader(f)
        if read(0, 2) == b"\xff\xd8":
            h = read_header(read)
            return describe_header(h) + [f"conclusion:  {CUT_SHORT if h.problem else CONCLUSIONS[h.kind]}"]
        start = read(0, 12)
        if start[:4] == b"RIFF" and start[8:12] == b"AVI ":
            return _identify_avi(read)
    return ["header:      neither a JPEG (no FF D8 at the start) nor an AVI", "conclusion:  nothing here to read"]


def _identify_avi(read: Callable[[int, int], bytes]) -> List[str]:
    """An AVI's header lines and its first frame's. Both known builds copy the whole bare template into each video frame
    and fill in its size and its tables, one IJG quality for both; the rate control moves that quality from frame to
    frame, from 50 in steps of 5 between 15 and 60 by its thresholds (VERIFIED by static analysis, the range
    INFERRED). So a frame header is theirs exactly when it is of the "bare" kind, and it then does not tell them
    apart; a JUNK text other than AVI_PACKER shows that other code wrote the file. (INFERRED from the firmware: no
    video of either build was examined here.)"""
    avi = read_avi(read)
    lines = ["header:      an AVI"
             + (f", {avi.width}x{avi.height}" if avi.width else "")
             + (f", {avi.codec.decode('ascii', 'replace')!r}" if avi.codec else "")
             + (f", {1_000_000 / avi.usec_per_frame:.2f} frames per second" if avi.usec_per_frame else "")]
    if avi.junk:
        lines.append("junk text:   " + ", ".join(repr(t.decode("ascii", "replace")) for t in avi.junk))
    if avi.info:
        lines.append("info:        " + ", ".join(f"{k} {v!r}" for k, v in sorted(avi.info.items())))
    h = None
    if avi.frame_at is None:
        lines.append(f"frame:       {avi.problem}")
    else:
        h = read_header(read, avi.frame_at)
        lines.append(f"first frame: at {avi.frame_at:#x}")
        lines += describe_header(h, "  ")
    if avi.junk and AVI_PACKER not in avi.junk:
        return lines + [f"conclusion:  {OTHER_PACKER}"]
    if h is None:
        return lines + ["conclusion:  nothing to conclude about the camera from this file"]
    if h.problem:
        return lines + [f"conclusion:  {CUT_SHORT}"]
    if h.kind == "bare":
        return lines + [f"conclusion:  {VIDEO}"]
    if h.kind in ("resave", "resave-or-build"):
        return lines + [f"conclusion:  {CONCLUSIONS[h.kind]}"]
    return lines + [f"conclusion:  {NOT_VIDEO}"]


def handler(args) -> int:
    code = 0
    for i, name in enumerate(args.files):
        path = Path(name)
        if i:
            print()
        print(f"file:        {path}")
        if not path.is_file():
            print("error:       not a file")
            code = 2
            continue
        try:
            for line in identify(path):
                print(line)
        except OSError as exc:
            print(f"error:       cannot read it: {exc}")
            code = 2
    return code


def register(subparsers) -> None:
    p = subparsers.add_parser(
        "photo-id",
        help="say what a photo's header shows about the firmware that took it (read-only; reads the header only)",
        description=(
            "Read the header of each photo (JPEG) or video (AVI) given, and only the header: from the start of the "
            "file to the start of the compressed image data, never more than the first 64 KiB. It names the header "
            "kind and its length, the frame size and sampling, the IJG quality that reproduces the quantisation "
            "tables exactly, the comment, and EXIF Make, Model and Software when there are any, then says what the "
            "header shows. Both known builds (the public dump's and the film-mods build) write every still photo "
            "with the same 0x590-byte header: an EXIF block with Make Generalplus, Model CBB3 and Software 20150615, "
            "and the comment 'GPEncoder' and six spaces. They write a bare 0x270-byte header, no EXIF block and the "
            "comment 'GPEncoder', only for thumbnails, video frames and a Wi-Fi stream."
        ),
        epilog=(
            "What the conclusions mean. 'The still-photo header both known builds write': the camera writes photos the "
            "way both known builds do; that does not show which build it runs, or that it runs either, since two builds "
            "can share a header. 'A bare header that neither known build writes for a still photo': if the file is a "
            "photo as the camera saved it in DCIM, the camera ran another build when it took it. 'Re-saved by another "
            "program': the header carries a mark of an "
            "editor or a re-encode (an XMP block, an APP2, APP13 or APP14 segment, a progressive frame or a size taller "
            "than wide), so a phone, an editor or a messaging app wrote the file again, and its header says nothing "
            "about the camera. 'Neither known build writes this header as it is': an EXIF Make other than Generalplus, "
            "a JFIF or other APP0 segment, the still comment without its EXIF block, or tables the camera's encoder "
            "never writes; "
            "another program may have saved the file again, or another build may have written it, and nothing here "
            "tells which. 'A header neither known build writes, and nothing in it names another program': it differs "
            "from both in other ways, and nothing here can tell what wrote it. A header without its quantisation "
            "tables or its Huffman tables is cut short, or comes from an encoder that leaves them out, and there is "
            "nothing to conclude from it. For a video, a frame header that is the bare template as the encoder fills "
            "it in (one IJG quality for both tables, which the rate control moves from frame to frame) does not tell "
            "the known builds apart (INFERRED), any other frame header is not the one they write, and a JUNK text "
            "other than "
            "'GP$322 Generalplus AviPackerV3 20140916', the packer text both carry, shows that other code wrote the "
            "file. Give photo-id the camera's own files, as copied from the card's DCIM folder: a frame taken out of "
            "a video without re-encoding has the bare header too. Nothing here says whether a firmware fits a camera."
        ),
    )
    p.add_argument("files", nargs="+", metavar="FILE", help="a JPEG or AVI file (only its header is read)")
    p.set_defaults(func=handler)
