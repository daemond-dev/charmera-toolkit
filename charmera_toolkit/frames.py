"""`frames`: list, extract, preview or replace the four photo frames (the overlays of the camera's frame mode).

What is in the resource pack (VERIFIED against the stock dump):

- Each frame n (0..3) is three GPZP files F_n_000, F_n_001, F_n_002, one per 200-row band of an 800x600
  overlay, plus a 112x80 RGB565 menu thumbnail PV00n. A GPZP file is 'GPZP', a raw DEFLATE stream of the
  payload, then a little-endian CRC-32 and length of the payload; the rest of the slot is 0xFF.
- The stock streams use fixed-Huffman blocks only and the camera's inflater has no dynamic-Huffman path, so
  this module always encodes with zlib's Z_FIXED strategy and checks the result for dynamic blocks.
- A strip inflates to 240,000 bytes: 100 row-pairs of 800 luma bytes (even row), 800 U,V bytes interleaved
  per horizontal pixel pair, 800 luma bytes (odd row). BT.601 full range.

The see-through rule (community-verified by disassembly and emulation; this project has not flashed it yet):
a horizontal pixel pair is transparent when both of its luma bytes are 140. Otherwise its two lumas are
copied over the photo and, on even rows, its U,V too. An opaque pair whose lumas both come out as 140 would
become a hole, so `rgba_to_frame` nudges its second luma to 141 and reports how many pairs it touched.

Writing a modified image is a mod step: the output is a full image, not an update file. Run `pack` on it
afterwards and read what `verify` says.

Everything is standard library. The PNG codec here reads 8-bit RGB and RGBA PNGs only; anything else gets a
message asking for that export.
"""
from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

from .image import ResourceEntry, resource_directory
from .sounds import GATE_FLAG, _check_output, _write_mod

# --- Geometry and constants -----------------------------------------------------------------------------

FRAME_W = 800
FRAME_H = 600
STRIPS = 3
STRIP_ROWS = FRAME_H // STRIPS            # 200 rows per strip
ROWPAIR_BYTES = 3 * FRAME_W               # 2400: luma, U/V, luma
STRIP_BYTES = (STRIP_ROWS // 2) * ROWPAIR_BYTES   # 240,000
FRAME_BYTES = STRIPS * STRIP_BYTES        # 720,000
KEY_LUMA = 140                            # 0x8C: both lumas of a pair equal -> see-through
ALPHA_OPAQUE = 128                        # PNG alpha at or above this counts as opaque

THUMB_W = 112
THUMB_H = 80
THUMB_BYTES = THUMB_W * THUMB_H * 2       # 17,920 bytes of little-endian RGB565
KEY_RGB565 = 0x8C71                       # the key grey the menu thumbnails use for see-through areas

GPZP_MAGIC = b"GPZP"
GPZP_OVERHEAD = 4 + 8                     # header + trailer
FRAME_SLOTS = (0, 1, 2, 3)

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

# --- Colour maths (integer, 16.16 fixed point; coefficients sum exactly so greys stay grey) ---------------

# Y = 0.299 R + 0.587 G + 0.114 B
_YR, _YG, _YB = 19595, 38470, 7471
# U = -0.168736 R - 0.331264 G + 0.5 B + 128
_UR, _UG, _UB = -11059, -21709, 32768
# V = 0.5 R - 0.418688 G - 0.081312 B + 128
_VR, _VG, _VB = 32768, -27439, -5329
# R = Y + 1.402 V'; G = Y - 0.344136 U' - 0.714136 V'; B = Y + 1.772 U'
_RV, _GU, _GV, _BU = 91881, -22554, -46802, 116131

_YR_T = [_YR * v for v in range(256)]
_YG_T = [_YG * v for v in range(256)]
_YB_T = [_YB * v for v in range(256)]
_UR_T = [_UR * v for v in range(256)]
_UG_T = [_UG * v for v in range(256)]
_UB_T = [_UB * v for v in range(256)]
_VR_T = [_VR * v for v in range(256)]
_VG_T = [_VG * v for v in range(256)]
_VB_T = [_VB * v for v in range(256)]
# chroma offsets per U or V byte, already rounded to whole levels
_RV_T = [(_RV * (v - 128) + 32768) >> 16 for v in range(256)]
_GU_T = [_GU * (v - 128) for v in range(256)]
_GV_T = [_GV * (v - 128) for v in range(256)]
_BU_T = [(_BU * (v - 128) + 32768) >> 16 for v in range(256)]


def _clamp(v: int) -> int:
    return 0 if v < 0 else (255 if v > 255 else v)


def rgb_to_yuv(r: int, g: int, b: int) -> Tuple[int, int, int]:
    """Full-range BT.601, rounded to whole levels and clamped."""
    y = (_YR_T[r] + _YG_T[g] + _YB_T[b] + 32768) >> 16
    u = ((_UR_T[r] + _UG_T[g] + _UB_T[b] + 32768) >> 16) + 128
    v = ((_VR_T[r] + _VG_T[g] + _VB_T[b] + 32768) >> 16) + 128
    return _clamp(y), _clamp(u), _clamp(v)


def yuv_to_rgb(y: int, u: int, v: int) -> Tuple[int, int, int]:
    r = y + _RV_T[v]
    g = y + ((_GU_T[u] + _GV_T[v] + 32768) >> 16)
    b = y + _BU_T[u]
    return _clamp(r), _clamp(g), _clamp(b)


# --- PNG codec --------------------------------------------------------------------------------------------


def _png_chunk(tag: bytes, body: bytes) -> bytes:
    return struct.pack(">I", len(body)) + tag + body + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)


def png_write(dest, width: int, height: int, rows: Sequence[bytes], mode: str = "RGBA") -> int:
    """Write an 8-bit non-interlaced PNG (filter 0 on every row, zlib level 9).

    `dest` is a path or an object with write(). `rows` holds `height` byte strings of width * channels
    bytes each. Returns the number of bytes written.
    """
    channels = {"RGB": 3, "RGBA": 4}.get(mode)
    if channels is None:
        raise ValueError("mode must be 'RGB' or 'RGBA'")
    if len(rows) != height:
        raise ValueError(f"expected {height} rows, got {len(rows)}")
    stride = width * channels
    for i, row in enumerate(rows):
        if len(row) != stride:
            raise ValueError(f"row {i} is {len(row)} bytes, expected {stride}")
    raw = b"".join(b"\x00" + bytes(row) for row in rows)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6 if channels == 4 else 2, 0, 0, 0)
    data = PNG_SIGNATURE + _png_chunk(b"IHDR", ihdr) + _png_chunk(b"IDAT", zlib.compress(raw, 9)) + _png_chunk(b"IEND", b"")
    if hasattr(dest, "write"):
        dest.write(data)
    else:
        Path(dest).write_bytes(data)
    return len(data)


_PNG_COLOR_NAMES = {0: "greyscale", 2: "RGB", 3: "palette", 4: "greyscale with alpha", 6: "RGBA"}
PNG_HINT = "export as 8-bit RGB or RGBA PNG"


def _unfilter(raw: bytes, width: int, height: int, bpp: int) -> List[bytes]:
    """Undo the five PNG scanline filters. Pure Python, so it is the slow part of reading a large PNG."""
    stride = width * bpp
    expected = height * (stride + 1)
    if len(raw) != expected:
        raise ValueError(f"PNG image data is {len(raw)} bytes, expected {expected} for {width}x{height}")
    rows: List[bytes] = []
    prev = bytes(stride)
    pos = 0
    for y in range(height):
        ftype = raw[pos]
        line = bytearray(raw[pos + 1: pos + 1 + stride])
        pos += 1 + stride
        if ftype == 0:
            pass
        elif ftype == 1:   # Sub: each lane is a running sum
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif ftype == 2:   # Up
            line = bytearray((a + b) & 0xFF for a, b in zip(line, prev))
        elif ftype == 3:   # Average
            for i in range(bpp):
                line[i] = (line[i] + (prev[i] >> 1)) & 0xFF
            for i in range(bpp, stride):
                line[i] = (line[i] + ((line[i - bpp] + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:   # Paeth
            for i in range(bpp):
                line[i] = (line[i] + prev[i]) & 0xFF
            for i in range(bpp, stride):
                a = line[i - bpp]
                b = prev[i]
                c = prev[i - bpp]
                p = a + b - c
                pa = p - a if p >= a else a - p
                pb = p - b if p >= b else b - p
                pc = p - c if p >= c else c - p
                if pa <= pb and pa <= pc:
                    pred = a
                elif pb <= pc:
                    pred = b
                else:
                    pred = c
                line[i] = (line[i] + pred) & 0xFF
        else:
            raise ValueError(f"PNG row {y} uses unknown filter type {ftype}")
        prev = line
        rows.append(bytes(line))
    return rows


def png_read(data: bytes) -> Tuple[int, int, str, List[bytes]]:
    """Decode an 8-bit RGB or RGBA non-interlaced PNG. Returns (width, height, mode, rows).

    Anything else (palette, greyscale, 16-bit, interlaced) raises ValueError with the export hint.
    """
    if data[:8] != PNG_SIGNATURE:
        raise ValueError("not a PNG file (bad signature)")
    pos = 8
    ihdr = None
    idat = []
    while pos + 8 <= len(data):
        length, tag = struct.unpack_from(">I4s", data, pos)
        body = data[pos + 8: pos + 8 + length]
        if len(body) != length or pos + 12 + length > len(data):
            raise ValueError(f"PNG chunk {tag!r} is truncated")
        crc = struct.unpack_from(">I", data, pos + 8 + length)[0]
        if crc != zlib.crc32(tag + body) & 0xFFFFFFFF:
            raise ValueError(f"PNG chunk {tag!r} fails its CRC: the file is corrupt")
        pos += 12 + length
        if tag == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", body)
        elif tag == b"IDAT":
            idat.append(body)
        elif tag == b"IEND":
            break
    if ihdr is None:
        raise ValueError("PNG has no IHDR chunk")
    width, height, depth, ctype, compression, filt, interlace = ihdr
    if ctype not in (2, 6) or depth != 8 or interlace != 0:
        what = []
        if ctype not in (2, 6):
            what.append(_PNG_COLOR_NAMES.get(ctype, f"colour type {ctype}"))
        if depth != 8:
            what.append(f"{depth}-bit")
        if interlace != 0:
            what.append("interlaced")
        raise ValueError(f"unsupported PNG ({', '.join(what)}): {PNG_HINT}")
    if compression != 0 or filt != 0:
        raise ValueError(f"unsupported PNG compression or filter method: {PNG_HINT}")
    if not idat:
        raise ValueError("PNG has no image data")
    try:
        raw = zlib.decompress(b"".join(idat))
    except zlib.error as exc:
        raise ValueError(f"PNG image data does not inflate ({exc})")
    bpp = 4 if ctype == 6 else 3
    return width, height, "RGBA" if ctype == 6 else "RGB", _unfilter(raw, width, height, bpp)


def _as_rgba(rows: Sequence[bytes], width: int) -> List[bytes]:
    """Accept RGB or RGBA rows of the given width and return RGBA rows (alpha 255 added when missing)."""
    out = []
    for i, row in enumerate(rows):
        if len(row) == width * 4:
            out.append(bytes(row))
        elif len(row) == width * 3:
            b = bytearray(width * 4)
            b[0::4] = row[0::3]
            b[1::4] = row[1::3]
            b[2::4] = row[2::3]
            b[3::4] = b"\xff" * width
            out.append(bytes(b))
        else:
            raise ValueError(f"row {i} is {len(row)} bytes; expected {width * 3} (RGB) or {width * 4} (RGBA)")
    return out


# --- GPZP container -----------------------------------------------------------------------------------------


@dataclass
class GpzpFile:
    payload: bytes
    stream_len: int

    @property
    def file_len(self) -> int:
        """Header + DEFLATE stream + trailer: what has to fit in the slot."""
        return GPZP_OVERHEAD + self.stream_len


def parse_gpzp(blob: bytes) -> GpzpFile:
    """Inflate a GPZP file (padding after the trailer is allowed) and verify its CRC-32 and length."""
    blob = bytes(blob)
    if blob[:4] != GPZP_MAGIC:
        raise ValueError("not a GPZP file (no 'GPZP' header)")
    d = zlib.decompressobj(-15)
    try:
        payload = d.decompress(blob[4:])
    except zlib.error as exc:
        raise ValueError(f"GPZP: the DEFLATE stream is corrupt ({exc})")
    if not d.eof:
        raise ValueError("GPZP: the DEFLATE stream ends before its final block")
    rest = d.unused_data
    if len(rest) < 8:
        raise ValueError("GPZP: the 8-byte CRC/length trailer is missing")
    crc, length = struct.unpack_from("<II", rest, 0)
    if length != len(payload):
        raise ValueError(f"GPZP: trailer length {length} but the stream inflates to {len(payload)} bytes")
    if crc != zlib.crc32(payload) & 0xFFFFFFFF:
        raise ValueError(f"GPZP: trailer CRC-32 {crc:08x} does not match the payload ({zlib.crc32(payload) & 0xFFFFFFFF:08x})")
    return GpzpFile(payload, len(blob) - 4 - len(rest))


def decode_gpzp(blob: bytes) -> bytes:
    return parse_gpzp(blob).payload


def encode_gpzp(payload: bytes) -> bytes:
    """Header, fixed-Huffman level-9 raw DEFLATE stream, CRC-32 and length (no padding)."""
    payload = bytes(payload)
    c = zlib.compressobj(9, zlib.DEFLATED, -15, 8, zlib.Z_FIXED)
    stream = c.compress(payload) + c.flush()
    return GPZP_MAGIC + stream + struct.pack("<II", zlib.crc32(payload) & 0xFFFFFFFF, len(payload))


# A small DEFLATE block walker: enough to list block types and prove a stream has no dynamic-Huffman blocks.

_LEN_EXTRA = [0] * 8 + [1] * 4 + [2] * 4 + [3] * 4 + [4] * 4 + [5] * 4 + [0]
_DIST_EXTRA = [0, 0, 0, 0] + [n for n in range(1, 14) for _ in (0, 1)]


class _BitReader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0
        self.buf = 0
        self.cnt = 0

    def bit(self) -> int:
        if self.cnt == 0:
            if self.pos >= len(self.data):
                raise ValueError("DEFLATE stream ends inside a block")
            self.buf = self.data[self.pos]
            self.pos += 1
            self.cnt = 8
        b = self.buf & 1
        self.buf >>= 1
        self.cnt -= 1
        return b

    def bits_lsb(self, n: int) -> int:
        v = 0
        for i in range(n):
            v |= self.bit() << i
        return v

    def code_msb(self, n: int) -> int:
        v = 0
        for _ in range(n):
            v = (v << 1) | self.bit()
        return v

    def align(self) -> None:
        self.buf = 0
        self.cnt = 0


def _fixed_litlen(br: _BitReader) -> int:
    code = br.code_msb(7)
    if code <= 0b0010111:
        return 256 + code
    code = (code << 1) | br.bit()
    if 0b00110000 <= code <= 0b10111111:
        return code - 0b00110000
    if 0b11000000 <= code <= 0b11000111:
        return 280 + code - 0b11000000
    code = (code << 1) | br.bit()
    if 0b110010000 <= code <= 0b111111111:
        return 144 + code - 0b110010000
    raise ValueError("invalid fixed-Huffman code")


def deflate_blocks(stream: bytes) -> List[Tuple[str, int]]:
    """(kind, symbol count) per block: 'stored', 'fixed' or 'dynamic'. Walking stops at a dynamic block,
    which is reported with count -1; a stored block reports its byte length."""
    br = _BitReader(bytes(stream))
    out: List[Tuple[str, int]] = []
    while True:
        final = br.bit()
        btype = br.bits_lsb(2)
        if btype == 0:
            br.align()
            if br.pos + 4 > len(br.data):
                raise ValueError("stored block header is truncated")
            n, nn = struct.unpack_from("<HH", br.data, br.pos)
            if n != (~nn & 0xFFFF):
                raise ValueError("stored block length check fails")
            br.pos += 4 + n
            out.append(("stored", n))
        elif btype == 1:
            count = 0
            while True:
                sym = _fixed_litlen(br)
                if sym == 256:
                    break
                count += 1
                if sym > 256:
                    if sym > 285:
                        raise ValueError("invalid length code")
                    br.bits_lsb(_LEN_EXTRA[sym - 257])
                    dist = br.code_msb(5)
                    if dist > 29:
                        raise ValueError("invalid distance code")
                    br.bits_lsb(_DIST_EXTRA[dist])
            out.append(("fixed", count))
        elif btype == 2:
            out.append(("dynamic", -1))
            return out
        else:
            raise ValueError("reserved block type 3")
        if final:
            return out


def describe_blocks(stream: bytes) -> str:
    try:
        blocks = deflate_blocks(stream)
    except ValueError as exc:
        return f"unreadable ({exc})"
    kinds = [k for k, _ in blocks]
    if len(set(kinds)) == 1:
        return f"{len(kinds)} {kinds[0]} block{'s' if len(kinds) != 1 else ''}"
    return ", ".join(f"{k}" for k in kinds)


def fixed_huffman_only(stream: bytes) -> bool:
    try:
        return all(kind != "dynamic" for kind, _ in deflate_blocks(stream))
    except ValueError:
        return False


# --- Row-pair 4:2:0 <-> RGB(A) ----------------------------------------------------------------------------


def _check_rowpair(data: bytes, what: str = "row-pair data") -> int:
    if len(data) % ROWPAIR_BYTES:
        raise ValueError(f"{what} is {len(data)} bytes, not a multiple of {ROWPAIR_BYTES}")
    return (len(data) // ROWPAIR_BYTES) * 2


def rowpair_to_rgb(data: bytes, preserve_luma: bool = False) -> List[bytes]:
    """800-wide RGB rows from row-pair 4:2:0 bytes (any whole number of row-pairs). No key handling.

    A YUV value outside the RGB gamut is clipped per channel by default, which is what a display does.
    With preserve_luma the chroma is scaled towards grey instead until the pixel fits, so its luma survives
    a round trip through RGB exactly; `frame_to_rgba` uses that for frames that will be edited and put back.
    """
    height = _check_rowpair(data)
    rows: List[bytes] = []
    for p in range(height // 2):
        base = p * ROWPAIR_BYTES
        ye = data[base: base + FRAME_W]
        uv = data[base + FRAME_W: base + 2 * FRAME_W]
        yo = data[base + 2 * FRAME_W: base + 3 * FRAME_W]
        re = bytearray(FRAME_W * 3)
        ro = bytearray(FRAME_W * 3)
        for k in range(FRAME_W // 2):
            u = uv[2 * k]
            v = uv[2 * k + 1]
            dr = _RV_T[v]
            dg = (_GU_T[u] + _GV_T[v] + 32768) >> 16
            db = _BU_T[u]
            for row, ysrc in ((re, ye), (ro, yo)):
                for x in (2 * k, 2 * k + 1):
                    y = ysrc[x]
                    o = x * 3
                    r = y + dr
                    g = y + dg
                    b = y + db
                    if preserve_luma and (r < 0 or r > 255 or g < 0 or g > 255 or b < 0 or b > 255):
                        r, g, b = _desaturate(y, u, v)
                    row[o] = 0 if r < 0 else (255 if r > 255 else r)
                    row[o + 1] = 0 if g < 0 else (255 if g > 255 else g)
                    row[o + 2] = 0 if b < 0 else (255 if b > 255 else b)
        rows.append(bytes(re))
        rows.append(bytes(ro))
    return rows


def _desaturate(y: int, u: int, v: int) -> Tuple[int, int, int]:
    """The RGB of (y, u, v) with its chroma scaled by the largest factor that keeps all channels in 0..255.
    Luma is untouched, so converting the result back gives y again (to within rounding)."""
    offsets = (_RV * (v - 128), _GU * (u - 128) + _GV * (v - 128), _BU * (u - 128))   # 16.16 fixed point
    scale = 1 << 16
    for d in offsets:
        if d > 0:
            scale = min(scale, ((255 - y) << 32) // d)
        elif d < 0:
            scale = min(scale, (y << 32) // -d)
    r, g, b = (y + ((d * scale + (1 << 31)) >> 32) for d in offsets)
    return r, g, b


def key_mask(data: bytes) -> List[List[bool]]:
    """Per row, per horizontal pair: True where the camera would show the photo (both lumas 140)."""
    height = _check_rowpair(data)
    mask: List[List[bool]] = []
    for p in range(height // 2):
        base = p * ROWPAIR_BYTES
        for off in (0, 2 * FRAME_W):
            row = data[base + off: base + off + FRAME_W]
            mask.append([row[2 * k] == KEY_LUMA and row[2 * k + 1] == KEY_LUMA for k in range(FRAME_W // 2)])
    return mask


def frame_to_rgba(data: bytes) -> List[bytes]:
    """RGBA rows: alpha 0 for see-through pairs, 255 elsewhere. Pass 720,000 bytes for a whole frame.

    Colours outside the RGB gamut are desaturated rather than clipped so that every luma byte survives
    the trip through a PNG editor and back; see rowpair_to_rgb.
    """
    rgb = rowpair_to_rgb(data, preserve_luma=True)
    mask = key_mask(data)
    out: List[bytes] = []
    for row, m in zip(rgb, mask):
        a = bytearray(FRAME_W * 4)
        a[0::4] = row[0::3]
        a[1::4] = row[1::3]
        a[2::4] = row[2::3]
        alpha = bytearray(b"\xff" * FRAME_W)
        for k, key in enumerate(m):
            if key:
                alpha[2 * k] = 0
                alpha[2 * k + 1] = 0
        a[3::4] = alpha
        out.append(bytes(a))
    return out


def rgba_to_frame(rows: Sequence[bytes]) -> Tuple[bytes, int]:
    """Row-pair 4:2:0 bytes from 800-wide RGBA (or RGB) rows, plus the number of nudged pairs.

    Per horizontal pair and row: both pixels transparent -> key (140, 140); one transparent -> the opaque
    pixel's colour is used for both; both opaque -> their own lumas. An opaque pair whose lumas are both
    140 gets its second luma set to 141 so the camera does not treat it as a hole. The pair's U,V is the
    average over the opaque pixels of the two rows of the row-pair, 128,128 when there are none.
    """
    rows = _as_rgba(rows, FRAME_W)
    if len(rows) % 2:
        raise ValueError(f"{len(rows)} rows: a frame needs an even number of rows")
    out = bytearray(len(rows) // 2 * ROWPAIR_BYTES)
    nudged = 0
    thr = ALPHA_OPAQUE
    for p in range(len(rows) // 2):
        base = p * ROWPAIR_BYTES
        pair_rows = (rows[2 * p], rows[2 * p + 1])
        for k in range(FRAME_W // 2):
            x = 8 * k
            usum = 0
            vsum = 0
            n = 0
            for ri, row in enumerate(pair_rows):
                a0 = row[x + 3] >= thr
                a1 = row[x + 7] >= thr
                yo = base + (0 if ri == 0 else 2 * FRAME_W) + 2 * k
                if not a0 and not a1:
                    out[yo] = KEY_LUMA
                    out[yo + 1] = KEY_LUMA
                    continue
                if a0:
                    r0, g0, b0 = row[x], row[x + 1], row[x + 2]
                    usum += _UR_T[r0] + _UG_T[g0] + _UB_T[b0]
                    vsum += _VR_T[r0] + _VG_T[g0] + _VB_T[b0]
                    n += 1
                if a1:
                    r1, g1, b1 = row[x + 4], row[x + 5], row[x + 6]
                    usum += _UR_T[r1] + _UG_T[g1] + _UB_T[b1]
                    vsum += _VR_T[r1] + _VG_T[g1] + _VB_T[b1]
                    n += 1
                if not a0:
                    r0, g0, b0 = r1, g1, b1
                elif not a1:
                    r1, g1, b1 = r0, g0, b0
                y0 = (_YR_T[r0] + _YG_T[g0] + _YB_T[b0] + 32768) >> 16
                y1 = (_YR_T[r1] + _YG_T[g1] + _YB_T[b1] + 32768) >> 16
                if y0 == KEY_LUMA and y1 == KEY_LUMA:
                    y1 = KEY_LUMA + 1
                    nudged += 1
                out[yo] = y0
                out[yo + 1] = y1
            uo = base + FRAME_W + 2 * k
            if n:
                out[uo] = _clamp(((usum + 32768 * n) // (65536 * n)) + 128)
                out[uo + 1] = _clamp(((vsum + 32768 * n) // (65536 * n)) + 128)
            else:
                out[uo] = 128
                out[uo + 1] = 128
    return bytes(out), nudged


def photo_to_rowpair(rows: Sequence[bytes]) -> bytes:
    """Row-pair 4:2:0 bytes from 800-wide RGB (or RGBA, alpha ignored) rows; U,V averaged per 2x2 block."""
    rows = _as_rgba(rows, FRAME_W)
    if len(rows) % 2:
        raise ValueError(f"{len(rows)} rows: need an even number of rows")
    out = bytearray(len(rows) // 2 * ROWPAIR_BYTES)
    for p in range(len(rows) // 2):
        base = p * ROWPAIR_BYTES
        re = rows[2 * p]
        ro = rows[2 * p + 1]
        for k in range(FRAME_W // 2):
            x = 8 * k
            usum = 0
            vsum = 0
            for ri, row in enumerate((re, ro)):
                yo = base + (0 if ri == 0 else 2 * FRAME_W) + 2 * k
                for j in (0, 4):
                    r, g, b = row[x + j], row[x + j + 1], row[x + j + 2]
                    out[yo + (j >> 2)] = _clamp((_YR_T[r] + _YG_T[g] + _YB_T[b] + 32768) >> 16)
                    usum += _UR_T[r] + _UG_T[g] + _UB_T[b]
                    vsum += _VR_T[r] + _VG_T[g] + _VB_T[b]
            out[base + FRAME_W + 2 * k] = _clamp(((usum + 4 * 32768) // (4 * 65536)) + 128)
            out[base + FRAME_W + 2 * k + 1] = _clamp(((vsum + 4 * 32768) // (4 * 65536)) + 128)
    return bytes(out)


def compose(photo: bytes, frame: bytes) -> bytes:
    """The camera's compositor: per horizontal pair, a key pair (both lumas 140) shows the photo; any other
    pair copies the frame's lumas and, on even rows, its U,V. Both inputs are row-pair 4:2:0 of equal size."""
    _check_rowpair(photo, "photo")
    _check_rowpair(frame, "frame")
    if len(photo) != len(frame):
        raise ValueError(f"photo ({len(photo)} bytes) and frame ({len(frame)} bytes) differ in size")
    out = bytearray(photo)
    for p in range(len(frame) // ROWPAIR_BYTES):
        base = p * ROWPAIR_BYTES
        uvb = base + FRAME_W
        odd = base + 2 * FRAME_W
        for k in range(FRAME_W // 2):
            e = base + 2 * k
            if not (frame[e] == KEY_LUMA and frame[e + 1] == KEY_LUMA):
                out[e] = frame[e]
                out[e + 1] = frame[e + 1]
                out[uvb + 2 * k] = frame[uvb + 2 * k]
                out[uvb + 2 * k + 1] = frame[uvb + 2 * k + 1]
            o = odd + 2 * k
            if not (frame[o] == KEY_LUMA and frame[o + 1] == KEY_LUMA):
                out[o] = frame[o]
                out[o + 1] = frame[o + 1]
    return bytes(out)


# --- Thumbnails (112x80 RGB565) ----------------------------------------------------------------------------


def rgb565(r: int, g: int, b: int) -> int:
    return ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)


def thumb_to_rgb(data: bytes) -> List[bytes]:
    if len(data) != THUMB_BYTES:
        raise ValueError(f"thumbnail is {len(data)} bytes, expected {THUMB_BYTES} (112x80 RGB565)")
    rows = []
    for j in range(THUMB_H):
        row = bytearray(THUMB_W * 3)
        for i in range(THUMB_W):
            p = struct.unpack_from("<H", data, (j * THUMB_W + i) * 2)[0]
            r5, g6, b5 = p >> 11, (p >> 5) & 0x3F, p & 0x1F
            row[3 * i] = (r5 << 3) | (r5 >> 2)
            row[3 * i + 1] = (g6 << 2) | (g6 >> 4)
            row[3 * i + 2] = (b5 << 3) | (b5 >> 2)
        rows.append(bytes(row))
    return rows


def rgb_to_thumb(rows: Sequence[bytes]) -> bytes:
    """Little-endian RGB565 from 112-wide RGB or RGBA rows. Transparent pixels (alpha below 128) become the
    key grey; an opaque pixel that happens to pack to the key grey is nudged one blue level so it stays."""
    if len(rows) != THUMB_H:
        raise ValueError(f"thumbnail needs {THUMB_H} rows, got {len(rows)}")
    rows = _as_rgba(rows, THUMB_W)
    out = bytearray(THUMB_BYTES)
    o = 0
    for row in rows:
        for i in range(THUMB_W):
            x = 4 * i
            if row[x + 3] < ALPHA_OPAQUE:
                p = KEY_RGB565
            else:
                p = rgb565(row[x], row[x + 1], row[x + 2])
                if p == KEY_RGB565:
                    p += 1
            struct.pack_into("<H", out, o, p)
            o += 2
    return bytes(out)


THUMB_MAX_DROPPED_BITS = 4     # the last rung of the colour ladder: RGB565 reduced to 1, 2 and 1 bits


def _thumbnail_once(rows: Sequence[bytes], dropped_bits: int) -> bytes:
    """One 112x80 RGB565 thumbnail of an 800x600 RGBA frame.

    Each thumbnail pixel covers a box of about 7x7.5 frame pixels. A box that is mostly see-through gets the
    key grey; otherwise it takes the most common colour among its opaque pixels, which keeps flat colours and
    hard edges the way the stock thumbnails do (averaging would blend edges into hundreds of colours that
    fixed-Huffman DEFLATE cannot compress). `dropped_bits` first rounds every colour to fewer RGB565 levels.
    """
    rm = (0xFF << (3 + dropped_bits)) & 0xFF
    gm = (0xFF << (2 + dropped_bits)) & 0xFF
    thumb_rows = []
    for j in range(THUMB_H):
        y0 = (j * FRAME_H) // THUMB_H
        y1 = ((j + 1) * FRAME_H) // THUMB_H
        trow = bytearray(THUMB_W * 4)
        for i in range(THUMB_W):
            x0 = (i * FRAME_W) // THUMB_W
            x1 = ((i + 1) * FRAME_W) // THUMB_W
            counts: Dict[Tuple[int, int, int], int] = {}
            total = 0
            opaque = 0
            for y in range(y0, y1):
                row = rows[y]
                for x in range(x0, x1):
                    o = 4 * x
                    total += 1
                    if row[o + 3] >= ALPHA_OPAQUE:
                        opaque += 1
                        c = (row[o] & rm, row[o + 1] & gm, row[o + 2] & rm)
                        counts[c] = counts.get(c, 0) + 1
            t = 4 * i
            if 2 * opaque >= total and opaque:
                best = max(counts.items(), key=lambda kv: (kv[1], kv[0]))[0]
                trow[t: t + 3] = bytes(best)
                trow[t + 3] = 255
            else:
                trow[t + 3] = 0
        thumb_rows.append(bytes(trow))
    return rgb_to_thumb(thumb_rows)


def make_thumbnail_fitting(rows: Sequence[bytes], budget: Optional[int]) -> Tuple[bytes, int]:
    """The thumbnail of an 800x600 frame and the number of colour bits dropped to make its GPZP file fit
    `budget` bytes (0 when the full-colour one fits or no budget is given). If even the last rung of the
    ladder is too large, that one is returned and the caller's budget check reports it."""
    rows = _as_rgba(rows, FRAME_W)
    if len(rows) != FRAME_H:
        raise ValueError(f"a frame has {FRAME_H} rows, got {len(rows)}")
    thumb = _thumbnail_once(rows, 0)
    if budget is None or len(encode_gpzp(thumb)) <= budget:
        return thumb, 0
    for dropped in range(1, THUMB_MAX_DROPPED_BITS + 1):
        thumb = _thumbnail_once(rows, dropped)
        if len(encode_gpzp(thumb)) <= budget:
            return thumb, dropped
    return thumb, THUMB_MAX_DROPPED_BITS


def make_thumbnail(rows: Sequence[bytes], budget: Optional[int] = None) -> bytes:
    """112x80 RGB565 menu thumbnail of an 800x600 RGBA frame; see-through areas are the key grey 0x8C71.
    With `budget`, colours are reduced step by step until the encoded thumbnail fits that many bytes."""
    return make_thumbnail_fitting(rows, budget)[0]


# --- The frames inside an image -----------------------------------------------------------------------------


def strip_name(slot: int, strip: int) -> str:
    return f"F_{slot}_{strip:03d}GPZP"


def thumb_name(slot: int) -> str:
    return f"PV{slot:03d}GPZP"


@dataclass
class FrameSlot:
    slot: int
    strips: List[ResourceEntry]
    thumb: ResourceEntry

    @property
    def entries(self) -> List[ResourceEntry]:
        return self.strips + [self.thumb]


def frame_slots(image: bytes) -> Dict[int, FrameSlot]:
    """The frames the resource directory holds, by slot number. A slot is listed only when complete."""
    by_name = {e.name: e for e in resource_directory(image)}
    out: Dict[int, FrameSlot] = {}
    for n in FRAME_SLOTS:
        wanted = [strip_name(n, s) for s in range(STRIPS)] + [thumb_name(n)]
        found = [by_name[name] for name in wanted if name in by_name]
        if len(found) == len(wanted) and all(e.budget for e in found):
            out[n] = FrameSlot(n, found[:STRIPS], found[STRIPS])
    return out


def _slot_or_raise(image: bytes, slot: int) -> FrameSlot:
    slots = frame_slots(image)
    if slot not in slots:
        have = ", ".join(str(n) for n in slots) or "none"
        raise ValueError(f"the image has no complete frame {slot} (frames present: {have})")
    return slots[slot]


def read_entry(image: bytes, entry: ResourceEntry) -> GpzpFile:
    return parse_gpzp(image[entry.offset: entry.offset + (entry.budget or 0)])


def read_frame(image: bytes, slot: int) -> bytes:
    """The 720,000 row-pair bytes of frame `slot`, strips concatenated."""
    fs = _slot_or_raise(image, slot)
    parts = []
    for s, e in enumerate(fs.strips):
        payload = read_entry(image, e).payload
        if len(payload) != STRIP_BYTES:
            raise ValueError(f"{e.display_name} inflates to {len(payload)} bytes, expected {STRIP_BYTES}")
        parts.append(payload)
    return b"".join(parts)


def read_thumbnail(image: bytes, slot: int) -> bytes:
    fs = _slot_or_raise(image, slot)
    payload = read_entry(image, fs.thumb).payload
    if len(payload) != THUMB_BYTES:
        raise ValueError(f"{fs.thumb.display_name} inflates to {len(payload)} bytes, expected {THUMB_BYTES}")
    return payload


# --- Replace ----------------------------------------------------------------------------------------------


@dataclass
class EncodedPart:
    label: str              # 'strip 0'.. 'strip 2' or 'thumbnail'
    entry: ResourceEntry
    data: bytes             # the complete GPZP file (no padding)
    rows: Optional[Tuple[int, int]] = None   # image rows this part covers

    @property
    def size(self) -> int:
        return len(self.data)

    @property
    def budget(self) -> int:
        return self.entry.budget or 0

    @property
    def over(self) -> int:
        return max(0, self.size - self.budget)

    @property
    def fits(self) -> bool:
        return self.size <= self.budget

    def describe(self) -> str:
        head = f"{self.label}: {self.size:,} bytes, budget {self.budget:,}"
        if self.fits:
            return f"{head}: fits ({self.budget - self.size:,} spare)"
        if self.rows:
            hint = f"simplify that band of the frame (rows {self.rows[0]}-{self.rows[1]})"
        else:
            hint = "simplify the thumbnail (fewer colours, flat areas)"
        return f"{head}: over by {self.over:,}; {hint}"


class BudgetError(ValueError):
    """Raised when an encoded part does not fit its slot; `parts` carries the whole table."""

    def __init__(self, parts: List[EncodedPart]):
        self.parts = parts
        over = [p for p in parts if not p.fits]
        super().__init__(f"{len(over)} of {len(parts)} parts do not fit their slots:\n" + "\n".join("  " + p.describe() for p in parts))


@dataclass
class ReplacePlan:
    slot: int
    parts: List[EncodedPart]
    nudged: int
    transparent_pairs: int
    total_pairs: int
    thumb_bits_dropped: int = 0     # colour reduction the generated thumbnail needed to fit (0 = none)

    @property
    def fits(self) -> bool:
        return all(p.fits for p in self.parts)


def plan_replacement(image: bytes, slot: int, frame_rows: Sequence[bytes],
                     thumb_rows: Optional[Sequence[bytes]] = None) -> ReplacePlan:
    """Convert and encode a frame (and its thumbnail) for `slot` without checking budgets or writing."""
    fs = _slot_or_raise(image, slot)
    rows = _as_rgba(frame_rows, FRAME_W)
    if len(rows) != FRAME_H:
        raise ValueError(f"the frame must be {FRAME_W}x{FRAME_H}; got {len(rows)} rows")
    rowpair, nudged = rgba_to_frame(rows)
    mask = key_mask(rowpair)
    transparent = sum(sum(1 for k in m if k) for m in mask)
    parts = []
    for s, e in enumerate(fs.strips):
        strip = rowpair[s * STRIP_BYTES: (s + 1) * STRIP_BYTES]
        parts.append(EncodedPart(f"strip {s}", e, encode_gpzp(strip), (s * STRIP_ROWS, (s + 1) * STRIP_ROWS - 1)))
    dropped = 0
    if thumb_rows is not None:
        thumb = rgb_to_thumb(thumb_rows)
    else:
        thumb, dropped = make_thumbnail_fitting(rows, fs.thumb.budget)
    parts.append(EncodedPart("thumbnail", fs.thumb, encode_gpzp(thumb)))
    for p in parts:
        if not fixed_huffman_only(p.data[4:-8]):
            raise ValueError(f"{p.label}: zlib produced a non-fixed-Huffman block; refusing to write it")
    return ReplacePlan(slot, parts, nudged, transparent, len(mask) * (FRAME_W // 2), dropped)


def apply_plan(image: bytes, plan: ReplacePlan) -> bytes:
    """Write every part into its slot followed by 0xFF padding to the budget. Raises BudgetError first."""
    if not plan.fits:
        raise BudgetError(plan.parts)
    buf = bytearray(image)
    for p in plan.parts:
        off = p.entry.offset
        buf[off: off + p.budget] = p.data + b"\xff" * (p.budget - p.size)
    return bytes(buf)


def replace_frame(image: bytes, slot: int, frame_rows: Sequence[bytes],
                  thumb_rows: Optional[Sequence[bytes]] = None) -> Tuple[bytes, ReplacePlan]:
    plan = plan_replacement(image, slot, frame_rows, thumb_rows)
    return apply_plan(image, plan), plan


# --- Extract and preview (library side) ---------------------------------------------------------------------


def extract(image: bytes, out_dir: Path) -> List[Path]:
    """frame-n.png (RGBA), thumb-n.png (RGB) and the raw .gpzp files (header to trailer, no padding)."""
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for n, fs in frame_slots(image).items():
        for e in fs.entries:
            g = read_entry(image, e)
            p = out_dir / (e.display_name.lower())
            p.write_bytes(image[e.offset: e.offset + g.file_len])
            written.append(p)
        p = out_dir / f"frame-{n}.png"
        png_write(p, FRAME_W, FRAME_H, frame_to_rgba(read_frame(image, n)), "RGBA")
        written.append(p)
        p = out_dir / f"thumb-{n}.png"
        png_write(p, THUMB_W, THUMB_H, thumb_to_rgb(read_thumbnail(image, n)), "RGB")
        written.append(p)
    return written


def preview(image: bytes, slot: int, photo_rows: Sequence[bytes]) -> List[bytes]:
    """RGB rows of `photo` (800x600 RGB/RGBA rows) as the camera would show it under frame `slot`."""
    rows = _as_rgba(photo_rows, FRAME_W)
    if len(rows) != FRAME_H:
        raise ValueError(f"the photo must be {FRAME_W}x{FRAME_H}; got {len(rows)} rows")
    return rowpair_to_rgb(compose(photo_to_rowpair(rows), read_frame(image, slot)))


def _load_png(path: Path, width: int, height: int, what: str) -> List[bytes]:
    try:
        w, h, _mode, rows = png_read(path.read_bytes())
    except OSError as exc:
        raise ValueError(f"{path}: {exc}")
    except ValueError as exc:
        raise ValueError(f"{path}: {exc}")
    if (w, h) != (width, height):
        raise ValueError(f"{path}: the {what} is {w}x{h}; scale or crop it to exactly {width}x{height} first")
    return rows


# --- CLI ----------------------------------------------------------------------------------------------------


def _print_list(image: bytes) -> None:
    slots = frame_slots(image)
    if not slots:
        print("no frames found (the resource directory has no complete F_n_000..002 + PV00n set)")
        return
    for n, fs in slots.items():
        print(f"frame {n}")
        for s, e in enumerate(fs.strips):
            line = f"  {e.display_name:14s} at {e.offset:#8x}  budget {e.budget:6,} B  rows {s * STRIP_ROWS}-{(s + 1) * STRIP_ROWS - 1}"
            try:
                g = read_entry(image, e)
                line += f"  file {g.file_len:6,} B ({g.stream_len:,} compressed, {describe_blocks(image[e.offset + 4: e.offset + 4 + g.stream_len])})"
                line += f"  inflates {len(g.payload):,} B, trailer ok" if len(g.payload) == STRIP_BYTES else f"  inflates {len(g.payload):,} B (expected {STRIP_BYTES:,})"
            except ValueError as exc:
                line += f"  BAD: {exc}"
            print(line)
        e = fs.thumb
        line = f"  {e.display_name:14s} at {e.offset:#8x}  budget {e.budget:6,} B  thumbnail {THUMB_W}x{THUMB_H} RGB565"
        try:
            g = read_entry(image, e)
            line += f"  file {g.file_len:6,} B ({g.stream_len:,} compressed, {describe_blocks(image[e.offset + 4: e.offset + 4 + g.stream_len])})"
            line += "  trailer ok" if len(g.payload) == THUMB_BYTES else f"  inflates {len(g.payload):,} B (expected {THUMB_BYTES:,})"
        except ValueError as exc:
            line += f"  BAD: {exc}"
        print(line)


def _print_plan(plan: ReplacePlan) -> None:
    pct = 100.0 * plan.transparent_pairs / plan.total_pairs if plan.total_pairs else 0.0
    print(f"frame {plan.slot}: {plan.transparent_pairs:,} of {plan.total_pairs:,} pixel pairs see-through ({pct:.1f}%)")
    if plan.transparent_pairs == 0:
        print("note: nothing is transparent; this frame would cover the whole photo")
    print(f"nudged {plan.nudged:,} opaque pair{'s' if plan.nudged != 1 else ''} whose lumas were both 140 (the see-through key) to 140/141")
    if plan.thumb_bits_dropped:
        print(f"thumbnail: colours reduced by {plan.thumb_bits_dropped} bit{'s' if plan.thumb_bits_dropped != 1 else ''} per channel to fit its slot (pass --thumb to supply your own)")
    for p in plan.parts:
        print(f"  {p.describe()}")


def handler(args) -> int:
    src = Path(args.file)
    if not src.is_file():
        print(f"error: {src} is not a file")
        return 2
    action = args.action
    if action != "list" and not args.out:
        print(f"error: --out is required for {action}")
        return 2
    if action in ("preview", "replace"):
        if args.slot is None:
            print(f"error: {action} needs --slot N (0..3)")
            return 2
        if args.slot not in FRAME_SLOTS:
            print(f"error: --slot must be 0..3, not {args.slot}")
            return 2
    if action == "preview" and not args.photo:
        print("error: preview needs --photo PHOTO.png (exactly 800x600)")
        return 2
    if action == "replace" and not args.png:
        print("error: replace needs --png FRAME.png (exactly 800x600, alpha marks the see-through area)")
        return 2
    image = src.read_bytes()
    try:
        if action == "list":
            _print_list(image)
            return 0
        if action == "extract":
            for p in extract(image, Path(args.out)):
                print(f"wrote {p}")
            return 0
        out = Path(args.out)
        problem = _check_output(src, out, args.overwrite)
        if problem:
            print(f"error: {problem}")
            return 2
        if action == "preview":
            photo = _load_png(Path(args.photo), FRAME_W, FRAME_H, "photo")
            rows = preview(image, args.slot, photo)
            n = png_write(out, FRAME_W, FRAME_H, rows, "RGB")
            print(f"wrote {out} ({n:,} bytes): {args.photo} under frame {args.slot}, composited the way the camera does it")
            return 0
        if action == "replace":
            frame_rows = _load_png(Path(args.png), FRAME_W, FRAME_H, "frame")
            thumb_rows = _load_png(Path(args.thumb), THUMB_W, THUMB_H, "thumbnail") if args.thumb else None
            plan = plan_replacement(image, args.slot, frame_rows, thumb_rows)
            _print_plan(plan)
            if not plan.fits:
                print("error: the frame does not fit its slots; nothing written")
                return 2
            new_image = apply_plan(image, plan)
            what = f"frame {args.slot} replaced by {args.png}" + (f" with thumbnail {args.thumb}" if args.thumb else " with a generated thumbnail")
            return _write_mod(args, new_image, out, what)
    except ValueError as exc:
        print(f"error: {exc}")
        return 2
    except OSError as exc:
        print(f"error: {exc}")
        return 2
    return 2


def register(subparsers) -> None:
    p = subparsers.add_parser("frames", help="list, extract, preview or replace the four photo frames")
    p.add_argument("action", choices=["list", "extract", "preview", "replace"])
    p.add_argument("file", help="a 2 MiB dump or a 0x1E0000 update file")
    p.add_argument("--out", help="output directory (extract), PNG path (preview) or image path (replace)")
    p.add_argument("--slot", type=int, help="frame number 0..3 (preview, replace)")
    p.add_argument("--photo", help="preview: an 800x600 RGB or RGBA PNG")
    p.add_argument("--png", help="replace: an 800x600 RGBA PNG; transparent pixels show the photo")
    p.add_argument("--thumb", help="replace: a 112x80 PNG for the frame menu (default: made from the frame)")
    p.add_argument("--overwrite", action="store_true", help="allow --out to replace an existing file (never the input)")
    p.add_argument(GATE_FLAG, dest="gate", action="store_true", help="replace: actually write the modified image")
    p.set_defaults(func=handler)
