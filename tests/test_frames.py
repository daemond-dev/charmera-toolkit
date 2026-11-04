"""Tests for `frames`: the PNG codec against Pillow, the GPZP container, the row-pair conversion, the
camera's compositor, the thumbnails and the CLI.

Everything that writes works on synthetic images or on copies under tmp_path; no firmware file is modified
in place. Tests that need the stock dump use the `stock_image` fixture and skip when CHARMERA_STOCK_IMAGE is
unset. Pillow is a reference encoder and decoder only, imported through importorskip; the package never
imports it.
"""
import io
import random
import struct
import zlib

import pytest

from charmera_toolkit import cli, frames, image

W, H = frames.FRAME_W, frames.FRAME_H
TW, TH = frames.THUMB_W, frames.THUMB_H
GATE = frames.GATE_FLAG
# zlib-ng (some Linux distributions ship it behind the zlib API) produces different, still valid, streams
ZLIB_NG = "ng" in zlib.ZLIB_RUNTIME_VERSION.lower()


# --- synthetic material --------------------------------------------------------------------------------------


def transparent_rows():
    return [bytearray(W * 4) for _ in range(H)]


def paint(rows, x0, y0, x1, y1, rgb, alpha=255):
    px = bytes(rgb) + bytes([alpha])
    for y in range(y0, y1):
        rows[y][x0 * 4: x1 * 4] = px * (x1 - x0)


def border_frame(rgb=(220, 40, 40), width=24):
    rows = transparent_rows()
    paint(rows, 0, 0, W, width, rgb)
    paint(rows, 0, H - width, W, H, rgb)
    paint(rows, 0, 0, width, H, rgb)
    paint(rows, W - width, 0, W, H, rgb)
    return [bytes(r) for r in rows]


def square_frame(rgb=(140, 140, 140)):
    rows = transparent_rows()
    paint(rows, 100, 100, 150, 150, rgb)
    return [bytes(r) for r in rows]


def noise_frame(seed=3):
    """Opaque random RGB: compresses to far more than any slot holds."""
    rng = random.Random(seed)
    return [rng.randbytes(W * 3) for _ in range(H)]


_PHOTO = []


def gradient_photo():
    if not _PHOTO:
        rows = []
        for y in range(H):
            row = bytearray(W * 3)
            row[0::3] = bytes((x * 255) // (W - 1) for x in range(W))
            row[1::3] = bytes([(y * 255) // (H - 1)]) * W
            row[2::3] = bytes(((x + y) // 4) & 255 for x in range(W))
            rows.append(bytes(row))
        _PHOTO.append(rows)
    return _PHOTO[0]


def synthetic_frame_image(slot=1, frame_rows=None, budgets=(16384, 16384, 16384, 2048)):
    """A 2 MiB image whose resource pack holds one complete frame set (three strips and a thumbnail) and a
    small terminator entry so the thumbnail's budget is defined. Budgets are in bytes, multiples of 512."""
    frame_rows = frame_rows or border_frame()
    img = bytearray(b"\xff" * 0x200000)
    img[0:4] = b"SPII"
    base = image.RESOURCE_BASE
    img[base: base + 0x18] = b"GP+\x00" + b"\x00" * 20
    names = [frames.strip_name(slot, s) for s in range(3)] + [frames.thumb_name(slot), "ZZZGPZP"]
    rowpair, _ = frames.rgba_to_frame(frame_rows)
    payloads = [rowpair[s * frames.STRIP_BYTES: (s + 1) * frames.STRIP_BYTES] for s in range(3)]
    payloads += [frames.make_thumbnail(frame_rows), b"\x00" * 16]
    sector = 0x20
    off = base + 0x18
    for name, payload, budget in zip(names, payloads, list(budgets) + [512]):
        img[off: off + 20] = name.encode().ljust(20, b"\x00")
        img[off + 20: off + 24] = struct.pack("<I", sector)
        blob = frames.encode_gpzp(payload)
        assert len(blob) <= budget
        p = base + sector * image.RESOURCE_SECTOR
        img[p: p + len(blob)] = blob
        off += 24
        sector += budget // image.RESOURCE_SECTOR
    return bytes(img)


def run(*argv):
    return cli.main(list(argv))


def in_gamut(y, u, v):
    """True when the BT.601 inverse lands inside 0..255 on all three channels, i.e. no clamping happened."""
    r = y + 1.402 * (v - 128)
    g = y - 0.344136 * (u - 128) - 0.714136 * (v - 128)
    b = y + 1.772 * (u - 128)
    return all(-0.5 <= c <= 255.5 for c in (r, g, b))


# --- PNG codec ----------------------------------------------------------------------------------------------


def _filter_row(ftype, line, prev, bpp):
    out = bytearray(len(line))
    for i in range(len(line)):
        a = line[i - bpp] if i >= bpp else 0
        b = prev[i]
        c = prev[i - bpp] if i >= bpp else 0
        if ftype == 0:
            pred = 0
        elif ftype == 1:
            pred = a
        elif ftype == 2:
            pred = b
        elif ftype == 3:
            pred = (a + b) >> 1
        else:
            p = a + b - c
            pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
            pred = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
        out[i] = (line[i] - pred) & 0xFF
    return bytes(out)


def _build_png(w, h, rows, ctype, filters, depth=8, interlace=0):
    bpp = {2: 3, 6: 4}[ctype]
    prev = bytes(w * bpp)
    raw = b""
    for row, ft in zip(rows, filters):
        raw += bytes([ft]) + _filter_row(ft, row, prev, bpp)
        prev = row
    ihdr = struct.pack(">IIBBBBB", w, h, depth, ctype, 0, 0, interlace)
    return (frames.PNG_SIGNATURE + frames._png_chunk(b"IHDR", ihdr) + frames._png_chunk(b"IDAT", zlib.compress(raw))
            + frames._png_chunk(b"IEND", b""))


def _idat_filters(data):
    pos, idat = 8, b""
    while pos < len(data):
        ln, tag = struct.unpack_from(">I4s", data, pos)
        if tag == b"IDAT":
            idat += data[pos + 8: pos + 8 + ln]
        pos += 12 + ln
    return idat


def test_png_roundtrip_both_ways_with_pillow():
    Image = pytest.importorskip("PIL.Image")
    rng = random.Random(7)
    w, h = 37, 23
    for mode, ch in (("RGB", 3), ("RGBA", 4)):
        rows = [bytes(rng.randrange(256) if x > 10 * ch else (x * 7 + y * 3) & 255 for x in range(w * ch)) for y in range(h)]
        # ours -> Pillow
        buf = io.BytesIO()
        n = frames.png_write(buf, w, h, rows, mode)
        assert n == len(buf.getvalue())
        im = Image.open(io.BytesIO(buf.getvalue()))
        assert im.mode == mode and im.size == (w, h)
        assert im.tobytes() == b"".join(rows)
        # Pillow (adaptive filters) -> ours
        pbuf = io.BytesIO()
        Image.frombytes(mode, (w, h), b"".join(rows)).save(pbuf, "PNG")
        data = pbuf.getvalue()
        rw, rh, rmode, rrows = frames.png_read(data)
        assert (rw, rh, rmode) == (w, h, mode)
        assert rrows == rows
        # make sure Pillow really used filtered rows, so the unfilter code ran
        raw = zlib.decompress(_idat_filters(data))
        used = {raw[i] for i in range(0, len(raw), 1 + w * ch)}
        assert used - {0}
        # every filter type, built by hand, decodes like Pillow does
        for ft in range(5):
            data = _build_png(w, h, rows, 6 if ch == 4 else 2, [ft] * h)
            assert frames.png_read(data)[3] == rows
            assert Image.open(io.BytesIO(data)).tobytes() == b"".join(rows)
        mixed = _build_png(w, h, rows, 6 if ch == 4 else 2, [(y * 3) % 5 for y in range(h)])
        assert frames.png_read(mixed)[3] == rows
    # a path destination and the input checks
    with pytest.raises(ValueError):
        frames.png_write(io.BytesIO(), 4, 2, [bytes(12)], "RGB")
    with pytest.raises(ValueError):
        frames.png_write(io.BytesIO(), 4, 2, [bytes(12), bytes(11)], "RGB")
    with pytest.raises(ValueError):
        frames.png_write(io.BytesIO(), 4, 1, [bytes(4)], "L")


def test_png_rejects_other_formats(tmp_path):
    Image = pytest.importorskip("PIL.Image")
    for mode, word in (("L", "greyscale"), ("P", "palette"), ("I;16", "16-bit"), ("LA", "alpha")):
        buf = io.BytesIO()
        Image.new(mode, (6, 5)).save(buf, "PNG")
        with pytest.raises(ValueError) as exc:
            frames.png_read(buf.getvalue())
        assert frames.PNG_HINT in str(exc.value) and word in str(exc.value)
    rows = [bytes(6 * 3)] * 5
    with pytest.raises(ValueError) as exc:
        frames.png_read(_build_png(6, 5, rows, 2, [0] * 5, interlace=1))
    assert "interlaced" in str(exc.value) and frames.PNG_HINT in str(exc.value)
    good = _build_png(6, 5, rows, 2, [0] * 5)
    bad = bytearray(good)
    bad[8 + 8 + 13] ^= 0xFF          # IHDR CRC
    with pytest.raises(ValueError) as exc:
        frames.png_read(bytes(bad))
    assert "CRC" in str(exc.value)
    with pytest.raises(ValueError):
        frames.png_read(b"GIF89a" + b"\x00" * 40)
    with pytest.raises(ValueError):
        frames.png_read(good[:-20])
    assert frames.png_read(good)[3] == rows
    p = tmp_path / "x.png"
    frames.png_write(p, 6, 5, rows, "RGB")
    assert frames.png_read(p.read_bytes())[:3] == (6, 5, "RGB")


# --- GPZP container -------------------------------------------------------------------------------------------


def test_gpzp_container_synthetic():
    payload = bytes(range(256)) * 40 + b"\x8c" * 1000
    blob = frames.encode_gpzp(payload)
    assert blob[:4] == b"GPZP"
    crc, length = struct.unpack("<II", blob[-8:])
    assert crc == zlib.crc32(payload) & 0xFFFFFFFF and length == len(payload)
    assert frames.decode_gpzp(blob) == payload
    assert frames.decode_gpzp(blob + b"\xff" * 300) == payload      # slot padding is fine
    g = frames.parse_gpzp(blob + b"\xff" * 300)
    assert g.file_len == len(blob) and g.stream_len == len(blob) - 12
    blocks = frames.deflate_blocks(blob[4:-8])
    assert blocks and all(kind == "fixed" for kind, _ in blocks)
    assert frames.fixed_huffman_only(blob[4:-8])
    assert "fixed" in frames.describe_blocks(blob[4:-8])
    # the walker recognises what the camera cannot inflate
    text = b"".join(f"line {i} of the test, value {i * i}\n".encode() for i in range(400))
    dyn = zlib.compressobj(9, zlib.DEFLATED, -15)
    stream = dyn.compress(text) + dyn.flush()
    assert any(kind == "dynamic" for kind, _ in frames.deflate_blocks(stream))
    assert not frames.fixed_huffman_only(stream)
    stored = zlib.compressobj(0, zlib.DEFLATED, -15)
    stream = stored.compress(text) + stored.flush()
    assert all(kind == "stored" for kind, _ in frames.deflate_blocks(stream))
    # trailer checks
    bad = bytearray(blob)
    bad[-8] ^= 1
    with pytest.raises(ValueError, match="CRC"):
        frames.decode_gpzp(bytes(bad))
    bad = bytearray(blob)
    bad[-1] ^= 1
    with pytest.raises(ValueError, match="length"):
        frames.decode_gpzp(bytes(bad))
    with pytest.raises(ValueError, match="trailer"):
        frames.decode_gpzp(blob[:-8])
    with pytest.raises(ValueError, match="GPZP"):
        frames.decode_gpzp(b"RIFF" + blob[4:])
    with pytest.raises(ValueError):
        frames.decode_gpzp(blob[:20])


def test_gpzp_roundtrip_on_every_stock_entry(stock_image):
    entries = [e for e in image.resource_directory(stock_image) if stock_image[e.offset: e.offset + 4] == b"GPZP"]
    assert len(entries) == 35
    identical = set()
    for e in entries:
        slot = stock_image[e.offset: e.offset + e.budget]
        g = frames.parse_gpzp(slot)
        assert g.file_len <= e.budget
        assert slot[g.file_len:] == b"\xff" * (e.budget - g.file_len), e.name
        stream = slot[4: 4 + g.stream_len]
        assert all(kind == "fixed" for kind, _ in frames.deflate_blocks(stream)), e.name
        again = frames.encode_gpzp(g.payload)
        assert frames.decode_gpzp(again) == g.payload
        assert frames.fixed_huffman_only(again[4:-8])
        assert len(again) <= g.file_len, (e.name, len(again), g.file_len)
        if again == slot[: g.file_len]:
            identical.add(e.name)
    if not ZLIB_NG:
        # The stock encoder is not zlib (its blocks hold 4096 symbols, zlib's hold 2^n - 1), so byte identity
        # holds only where a single block with the same match sequence results; F_0_001 is one of 23 such.
        assert "F_0_001GPZP" in identical
        assert len(identical) >= 20, sorted(identical)


# --- Row-pair conversion -----------------------------------------------------------------------------------------


def test_colour_maths():
    for grey in (0, 1, 127, 140, 254, 255):
        assert frames.rgb_to_yuv(grey, grey, grey) == (grey, 128, 128)
        assert frames.yuv_to_rgb(grey, 128, 128) == (grey, grey, grey)
    assert frames.rgb_to_yuv(255, 0, 0)[0] == 76
    assert frames.rgb_to_yuv(0, 255, 0)[0] == 150
    assert frames.rgb_to_yuv(0, 0, 255) == (29, 255, 107)
    for rgb in ((200, 30, 30), (10, 200, 90), (90, 60, 230), (140, 141, 139)):
        y, u, v = frames.rgb_to_yuv(*rgb)
        back = frames.yuv_to_rgb(y, u, v)
        assert all(abs(a - b) <= 2 for a, b in zip(rgb, back)), (rgb, back)


def test_rgba_to_frame_rules_and_nudge():
    rowpair, nudged = frames.rgba_to_frame(square_frame())
    assert len(rowpair) == frames.FRAME_BYTES
    assert nudged == 50 * 25                      # 50 rows of 25 opaque pairs at the key luma
    mask = frames.key_mask(rowpair)
    assert len(mask) == H and all(len(m) == W // 2 for m in mask)
    for y in range(H):
        for k in range(W // 2):
            assert mask[y][k] == (not (100 <= y < 150 and 50 <= k < 75)), (y, k)
    # the nudged pairs read 140, 141 and keep neutral chroma
    p, k = 60, 60
    base = p * frames.ROWPAIR_BYTES
    assert rowpair[base + 2 * k: base + 2 * k + 2] == b"\x8c\x8d"
    assert rowpair[base + W + 2 * k: base + W + 2 * k + 2] == b"\x80\x80"
    # a fully transparent row-pair is key with neutral chroma
    assert rowpair[: frames.ROWPAIR_BYTES] == b"\x8c" * W + b"\x80" * W + b"\x8c" * W
    # one transparent pixel borrows its neighbour; chroma averages only the opaque pixels
    rows = transparent_rows()
    rows[0][40: 44] = bytes((200, 30, 30, 255))           # pixel 10 opaque, pixel 11 transparent
    rows[1][44: 48] = bytes((10, 200, 90, 255))           # odd row: pixel 11 opaque, pixel 10 transparent
    rows[0][80: 88] = bytes((0, 0, 255, 255)) * 2          # pixels 20, 21 opaque blue
    rows[0][88: 96] = bytes((0, 0, 255, 60)) * 2           # pixels 22, 23: alpha below 128 is transparent
    rp, nudged = frames.rgba_to_frame([bytes(r) for r in rows])
    assert nudged == 0
    y_red = frames.rgb_to_yuv(200, 30, 30)[0]
    y_green = frames.rgb_to_yuv(10, 200, 90)[0]
    assert rp[10: 12] == bytes((y_red, y_red))
    assert rp[2 * W + 10: 2 * W + 12] == bytes((y_green, y_green))
    u_red, v_red = frames.rgb_to_yuv(200, 30, 30)[1:]
    u_green, v_green = frames.rgb_to_yuv(10, 200, 90)[1:]
    assert abs(rp[W + 10] - (u_red + u_green) / 2) <= 1 and abs(rp[W + 11] - (v_red + v_green) / 2) <= 1
    assert rp[20: 22] == bytes((29, 29)) and rp[W + 20: W + 22] == bytes((255, 107))
    assert rp[22: 24] == b"\x8c\x8c" and rp[W + 22: W + 24] == b"\x80\x80"
    # RGB rows (no alpha) are fully opaque
    rp2, _ = frames.rgba_to_frame([bytes(W * 3) for _ in range(H)])
    assert not any(any(m) for m in frames.key_mask(rp2))
    with pytest.raises(ValueError):
        frames.rgba_to_frame([bytes(W * 4)] * 3)
    with pytest.raises(ValueError):
        frames.rgba_to_frame([bytes(W * 2)] * 2)


def test_frame_to_rgba_and_back_synthetic():
    rows = border_frame((30, 90, 200), 32)
    rp, _ = frames.rgba_to_frame(rows)
    back = frames.frame_to_rgba(rp)
    assert len(back) == H and all(len(r) == W * 4 for r in back)
    assert [r[3::4] for r in back] == [r[3::4] for r in rows]           # alpha mask identical
    px = back[0][0: 3]
    assert all(abs(a - b) <= 2 for a, b in zip(px, (30, 90, 200)))
    rp2, nudged = frames.rgba_to_frame(back)
    assert nudged == 0 and frames.key_mask(rp2) == frames.key_mask(rp)
    assert rp2 == rp                                                   # flat colours round-trip exactly
    # a single strip decodes to 200 rows
    assert len(frames.frame_to_rgba(rp[: frames.STRIP_BYTES])) == frames.STRIP_ROWS
    with pytest.raises(ValueError):
        frames.frame_to_rgba(rp[:-1])


def test_photo_to_rowpair_and_compose_against_reference():
    photo = gradient_photo()
    photo_rp = frames.photo_to_rowpair(photo)
    assert len(photo_rp) == frames.FRAME_BYTES
    # flat photo: every luma and chroma is the colour's own
    flat = frames.photo_to_rowpair([bytes((90, 60, 230)) * W] * H)
    y, u, v = frames.rgb_to_yuv(90, 60, 230)
    assert flat[: frames.ROWPAIR_BYTES] == bytes([y]) * W + bytes([u, v]) * (W // 2) + bytes([y]) * W
    # a 2x2 block's chroma is the average of its four pixels
    rows = [bytes(W * 3) for _ in range(H)]
    r0 = bytearray(rows[0]); r0[0: 6] = bytes((255, 0, 0, 0, 0, 255)); rows[0] = bytes(r0)
    r1 = bytearray(rows[1]); r1[0: 6] = bytes((0, 255, 0, 255, 255, 255)); rows[1] = bytes(r1)
    rp = frames.photo_to_rowpair(rows)
    us = [frames.rgb_to_yuv(*c) for c in ((255, 0, 0), (0, 0, 255), (0, 255, 0), (255, 255, 255))]
    assert abs(rp[W] - sum(c[1] for c in us) / 4) <= 1 and abs(rp[W + 1] - sum(c[2] for c in us) / 4) <= 1
    assert rp[0] == frames.rgb_to_yuv(255, 0, 0)[0] and rp[2 * W + 1] == 255

    frame_rp, _ = frames.rgba_to_frame(border_frame((220, 40, 40), 24))
    out = frames.compose(photo_rp, frame_rp)
    # direct re-implementation of the published rule
    ref = bytearray(photo_rp)
    for p in range(H // 2):
        b = p * frames.ROWPAIR_BYTES
        for k in range(W // 2):
            e = b + 2 * k
            if not (frame_rp[e] == 140 and frame_rp[e + 1] == 140):
                ref[e: e + 2] = frame_rp[e: e + 2]
                ref[b + W + 2 * k: b + W + 2 * k + 2] = frame_rp[b + W + 2 * k: b + W + 2 * k + 2]
            o = b + 2 * W + 2 * k
            if not (frame_rp[o] == 140 and frame_rp[o + 1] == 140):
                ref[o: o + 2] = frame_rp[o: o + 2]
    assert out == bytes(ref)
    assert out != photo_rp
    # a fully see-through frame changes nothing; an even-row-only key pair keeps the photo's chroma
    clear, _ = frames.rgba_to_frame(transparent_rows())
    assert frames.compose(photo_rp, clear) == photo_rp
    rows = transparent_rows()
    paint(rows, 0, 1, 2, 2, (255, 255, 255))                  # odd row 1, pixels 0-1 opaque; row 0 see-through
    half, _ = frames.rgba_to_frame([bytes(r) for r in rows])
    mixed = frames.compose(photo_rp, half)
    assert mixed[W: W + 2] == photo_rp[W: W + 2] and mixed[2 * W: 2 * W + 2] == b"\xff\xff"
    with pytest.raises(ValueError):
        frames.compose(photo_rp, frame_rp[:-frames.ROWPAIR_BYTES])
    rgb = frames.rowpair_to_rgb(out)
    assert len(rgb) == H and len(rgb[0]) == W * 3
    assert all(abs(a - b) <= 3 for a, b in zip(rgb[0][0: 3], (220, 40, 40)))


# --- Thumbnails -----------------------------------------------------------------------------------------------


def test_thumbnail_pack_and_make():
    assert frames.rgb565(255, 255, 255) == 0xFFFF and frames.rgb565(0, 0, 0) == 0
    assert frames.rgb565(136, 140, 136) == frames.KEY_RGB565
    rng = random.Random(5)
    words = [rng.randrange(65536) for _ in range(TW * TH)]
    data = b"".join(struct.pack("<H", w) for w in words)
    rows = frames.thumb_to_rgb(data)
    assert len(rows) == TH and all(len(r) == TW * 3 for r in rows)
    repacked = frames.rgb_to_thumb(rows)
    # 565 -> 888 -> 565 is lossless, except that an opaque key-grey pixel is nudged by one blue level
    back = [struct.unpack_from("<H", repacked, 2 * i)[0] for i in range(TW * TH)]
    assert all(b == (w + 1 if w == frames.KEY_RGB565 else w) for w, b in zip(words, back))
    # transparent pixels become the key grey; an RGB (no alpha) row is opaque
    rgba = [bytes((136, 140, 136, 0)) * TW] + [bytes((136, 140, 136, 255)) * TW] * (TH - 1)
    t = frames.rgb_to_thumb(rgba)
    assert t[: 2 * TW] == struct.pack("<H", frames.KEY_RGB565) * TW
    assert t[2 * TW: 4 * TW] == struct.pack("<H", frames.KEY_RGB565 + 1) * TW
    with pytest.raises(ValueError):
        frames.thumb_to_rgb(data[:-2])
    with pytest.raises(ValueError):
        frames.rgb_to_thumb(rows[:-1])
    # a border frame: corners carry the colour, the middle is see-through
    thumb = frames.make_thumbnail(border_frame((220, 40, 40), 24))
    assert len(thumb) == frames.THUMB_BYTES
    words = [struct.unpack_from("<H", thumb, 2 * i)[0] for i in range(TW * TH)]
    assert words[0] == frames.rgb565(220, 40, 40)
    assert words[TW * (TH // 2) + TW // 2] == frames.KEY_RGB565
    assert 0.1 < words.count(frames.KEY_RGB565) / len(words) < 0.95
    assert frames.make_thumbnail_fitting(border_frame((220, 40, 40), 24), 1024) == (thumb, 0)
    with pytest.raises(ValueError):
        frames.make_thumbnail(border_frame()[:-1])
    # a two-way gradient has thousands of colours: the ladder drops colour bits until the file fits
    grad = []
    for y in range(H):
        row = bytearray(W * 3)
        row[0::3] = bytes((x * 255) // (W - 1) for x in range(W))
        row[1::3] = bytes([(y * 255) // (H - 1)]) * W
        row[2::3] = bytes([90]) * W
        grad.append(bytes(row))
    full = frames.make_thumbnail(grad)
    assert len(frames.encode_gpzp(full)) > 2048
    fitted, dropped = frames.make_thumbnail_fitting(grad, 2048)
    assert 1 <= dropped <= frames.THUMB_MAX_DROPPED_BITS and len(frames.encode_gpzp(fitted)) <= 2048
    assert frames.make_thumbnail(grad, 2048) == fitted
    # noise never fits: the last rung comes back and the caller's budget check says so
    noisy, dropped = frames.make_thumbnail_fitting(noise_frame(), 1024)
    assert dropped == frames.THUMB_MAX_DROPPED_BITS and len(frames.encode_gpzp(noisy)) > 1024


# --- Frames inside an image, replace, extract, preview ------------------------------------------------------------


def test_frame_slots_and_replace_library(tmp_path):
    img = synthetic_frame_image(slot=1)
    slots = frames.frame_slots(img)
    assert list(slots) == [1]
    fs = slots[1]
    assert [e.budget for e in fs.entries] == [16384, 16384, 16384, 2048]
    assert frames.read_frame(img, 1) == frames.rgba_to_frame(border_frame())[0]
    assert frames.read_thumbnail(img, 1) == frames.make_thumbnail(border_frame())
    with pytest.raises(ValueError, match="no complete frame 0"):
        frames.read_frame(img, 0)
    plan = frames.plan_replacement(img, 1, border_frame((30, 90, 200), 32))
    assert plan.fits and plan.nudged == 0 and 0.5 < plan.transparent_pairs / plan.total_pairs < 0.95
    assert plan.thumb_bits_dropped == 0
    assert [p.label for p in plan.parts] == ["strip 0", "strip 1", "strip 2", "thumbnail"]
    assert all("fits" in p.describe() for p in plan.parts)
    new = frames.apply_plan(img, plan)
    assert frames.read_frame(new, 1) == frames.rgba_to_frame(border_frame((30, 90, 200), 32))[0]
    # an over-budget frame is refused with a table and the image is untouched
    plan = frames.plan_replacement(img, 1, noise_frame())
    assert not plan.fits
    with pytest.raises(frames.BudgetError) as exc:
        frames.apply_plan(img, plan)
    text = str(exc.value)
    assert "strip 0:" in text and "over by" in text and "rows 0-199" in text
    assert plan.parts[0].over == plan.parts[0].size - 16384
    with pytest.raises(ValueError, match="800x600"):
        frames.plan_replacement(img, 1, border_frame()[:-2])
    # wrong-size thumbnail
    with pytest.raises(ValueError):
        frames.plan_replacement(img, 1, border_frame(), [bytes(TW * 3)] * (TH - 1))
    # synthetic image without frames
    plain = bytearray(b"\xff" * 0x200000)
    plain[image.RESOURCE_BASE: image.RESOURCE_BASE + 0x18] = b"GP+\x00" + b"\x00" * 20
    assert frames.frame_slots(bytes(plain)) == {}


def check_replace_cli(img, slot, tmp_path, capsys):
    """The replace command on a copy of `img`: dry run, gated write, parse-back, untouched bytes, refusals."""
    src = tmp_path / "src.bin"
    src.write_bytes(img)
    rows = border_frame((30, 90, 200), 32)
    png = tmp_path / "frame.png"
    frames.png_write(png, W, H, rows, "RGBA")
    out = tmp_path / "out.bin"
    common = ("frames", "replace", str(src), "--slot", str(slot), "--png", str(png))
    assert run(*common, "--out", str(out)) == 0
    text = capsys.readouterr().out
    assert "dry run" in text and "fits" in text and "nudged 0" in text and not out.exists()
    assert run(*common, "--out", str(out), GATE) == 0
    text = capsys.readouterr().out
    assert "wrote" in text and "pack" in text and out.exists()
    new = out.read_bytes()
    assert len(new) == len(img) and src.read_bytes() == img
    fs = frames.frame_slots(img)[slot]
    pos = 0
    for a, b in sorted((e.offset, e.offset + e.budget) for e in fs.entries):
        assert new[pos: a] == img[pos: a]
        pos = b
    assert new[pos:] == img[pos:]
    assert frames.read_frame(new, slot) == frames.rgba_to_frame(rows)[0]
    assert len(frames.read_thumbnail(new, slot)) == frames.THUMB_BYTES
    for e in fs.entries:
        g = frames.read_entry(new, e)
        assert new[e.offset + g.file_len: e.offset + e.budget] == b"\xff" * (e.budget - g.file_len)
    # the other frames (if any) still decode
    for n in frames.frame_slots(new):
        assert len(frames.read_frame(new, n)) == frames.FRAME_BYTES
    # refusals: never the input, never an existing file without --overwrite
    assert run(*common, "--out", str(src), GATE) == 2
    assert src.read_bytes() == img
    assert run(*common, "--out", str(out), GATE) == 2
    assert "exists" in capsys.readouterr().out
    assert run(*common, "--out", str(out), "--overwrite", GATE) == 0
    capsys.readouterr()
    # over budget: exit 2, a per-strip table, nothing written
    noise = tmp_path / "noise.png"
    frames.png_write(noise, W, H, noise_frame(), "RGB")
    out2 = tmp_path / "out2.bin"
    assert run("frames", "replace", str(src), "--slot", str(slot), "--png", str(noise), "--out", str(out2), GATE) == 2
    text = capsys.readouterr().out
    assert "over by" in text and "simplify" in text and "nothing written" in text and not out2.exists()
    assert "strip 2:" in text and "thumbnail:" in text
    # wrong size, bad slot, missing options
    small = tmp_path / "small.png"
    frames.png_write(small, 100, 100, [bytes(400)] * 100, "RGBA")
    assert run("frames", "replace", str(src), "--slot", str(slot), "--png", str(small), "--out", str(out2), GATE) == 2
    assert "exactly 800x600" in capsys.readouterr().out and not out2.exists()
    assert run("frames", "replace", str(src), "--slot", "7", "--png", str(png), "--out", str(out2), GATE) == 2
    assert run("frames", "replace", str(src), "--png", str(png), "--out", str(out2), GATE) == 2
    assert run("frames", "replace", str(src), "--slot", str(slot), "--out", str(out2), GATE) == 2
    assert run("frames", "replace", str(src), "--slot", str(slot), "--png", str(png), GATE) == 2
    assert run("frames", "replace", str(tmp_path / "missing.bin"), "--slot", str(slot), "--png", str(png), "--out", str(out2), GATE) == 2
    capsys.readouterr()
    # a custom thumbnail
    thumb = tmp_path / "thumb.png"
    frames.png_write(thumb, TW, TH, [bytes((200, 200, 0, 255)) * TW] * TH, "RGBA")
    out3 = tmp_path / "out3.bin"
    assert run(*common, "--thumb", str(thumb), "--out", str(out3), GATE) == 0
    t = frames.read_thumbnail(out3.read_bytes(), slot)
    assert t == struct.pack("<H", frames.rgb565(200, 200, 0)) * (TW * TH)
    return new


def test_replace_cli_synthetic(tmp_path, capsys):
    check_replace_cli(synthetic_frame_image(slot=3), 3, tmp_path, capsys)


def test_list_extract_preview_cli_synthetic(tmp_path, capsys):
    img = synthetic_frame_image(slot=2)
    src = tmp_path / "src.bin"
    src.write_bytes(img)
    assert run("frames", "list", str(src)) == 0
    text = capsys.readouterr().out
    assert "frame 2" in text and text.count("trailer ok") == 4 and "F_2_001.GPZP" in text and "PV002.GPZP" in text
    assert "frame 0" not in text
    out_dir = tmp_path / "ex"
    assert run("frames", "extract", str(src), "--out", str(out_dir)) == 0
    names = sorted(p.name for p in out_dir.iterdir())
    assert names == ["f_2_000.gpzp", "f_2_001.gpzp", "f_2_002.gpzp", "frame-2.png", "pv002.gpzp", "thumb-2.png"]
    for s in range(3):
        assert frames.decode_gpzp((out_dir / f"f_2_00{s}.gpzp").read_bytes()) == frames.read_frame(img, 2)[s * frames.STRIP_BYTES: (s + 1) * frames.STRIP_BYTES]
    assert frames.decode_gpzp((out_dir / "pv002.gpzp").read_bytes()) == frames.read_thumbnail(img, 2)
    w, h, mode, rows = frames.png_read((out_dir / "frame-2.png").read_bytes())
    assert (w, h, mode) == (W, H, "RGBA")
    assert [r[3::4] for r in rows] == [r[3::4] for r in border_frame()]
    assert frames.png_read((out_dir / "thumb-2.png").read_bytes())[:3] == (TW, TH, "RGB")
    capsys.readouterr()
    # preview
    photo = tmp_path / "photo.png"
    frames.png_write(photo, W, H, gradient_photo(), "RGB")
    out = tmp_path / "preview.png"
    assert run("frames", "preview", str(src), "--slot", "2", "--photo", str(photo), "--out", str(out)) == 0
    assert "wrote" in capsys.readouterr().out
    w, h, mode, rows = frames.png_read(out.read_bytes())
    assert (w, h, mode) == (W, H, "RGB")
    expect = frames.rowpair_to_rgb(frames.compose(frames.photo_to_rowpair(gradient_photo()), frames.read_frame(img, 2)))
    assert rows == expect
    assert all(abs(a - b) <= 3 for a, b in zip(rows[0][0: 3], (220, 40, 40)))      # the border
    mid = rows[H // 2][(W // 2) * 3: (W // 2) * 3 + 3]
    assert all(abs(a - b) <= 6 for a, b in zip(mid, gradient_photo()[H // 2][(W // 2) * 3: (W // 2) * 3 + 3]))
    assert run("frames", "preview", str(src), "--slot", "2", "--photo", str(photo), "--out", str(out)) == 2    # exists
    assert run("frames", "preview", str(src), "--slot", "2", "--photo", str(photo), "--out", str(out), "--overwrite") == 0
    small = tmp_path / "small.png"
    frames.png_write(small, 100, 100, [bytes(300)] * 100, "RGB")
    assert run("frames", "preview", str(src), "--slot", "2", "--photo", str(small), "--out", str(tmp_path / "p2.png")) == 2
    assert "exactly 800x600" in capsys.readouterr().out
    assert run("frames", "preview", str(src), "--slot", "0", "--photo", str(photo), "--out", str(tmp_path / "p3.png")) == 2
    assert "no complete frame 0" in capsys.readouterr().out
    assert run("frames", "preview", str(src), "--slot", "2", "--out", str(tmp_path / "p4.png")) == 2
    assert run("frames", "preview", str(src), "--slot", "2", "--photo", str(photo)) == 2
    # an image without frames lists nothing and refuses to extract anything
    plain = tmp_path / "plain.bin"
    plain.write_bytes(b"\xff" * 0x200000)
    assert run("frames", "list", str(plain)) == 0
    assert "no frames found" in capsys.readouterr().out
    assert run("frames", "extract", str(plain), "--out", str(tmp_path / "ex2")) == 0
    assert not list((tmp_path / "ex2").iterdir())


def test_frames_help_and_registration():
    with pytest.raises(SystemExit) as exc:
        run("frames", "--help")
    assert exc.value.code == 0
    with pytest.raises(SystemExit):
        run("frames", "list", "x.bin", "--slot", "a")


# --- Against the stock image --------------------------------------------------------------------------------------


def test_stock_frames_decode(stock_image):
    slots = frames.frame_slots(stock_image)
    assert list(slots) == [0, 1, 2, 3]
    for n, fs in slots.items():
        assert [e.name for e in fs.strips] == [f"F_{n}_00{s}GPZP" for s in range(3)]
        assert all(2048 <= e.budget <= 18944 for e in fs.strips)
        assert fs.thumb.budget == (1536 if n == 3 else 1024)
        for e in fs.strips:
            assert len(frames.read_entry(stock_image, e).payload) == frames.STRIP_BYTES
        rp = frames.read_frame(stock_image, n)
        assert len(rp) == frames.FRAME_BYTES
        rows = frames.frame_to_rgba(rp)
        assert len(rows) == H and all(len(r) == W * 4 for r in rows)
        clear = sum(r[3::4].count(0) for r in rows)
        assert 0.10 < clear / (W * H) < 0.95, (n, clear)
        thumb = frames.read_thumbnail(stock_image, n)
        assert len(thumb) == frames.THUMB_BYTES
        trows = frames.thumb_to_rgb(thumb)
        assert len(trows) == TH and len(trows[0]) == TW * 3
        key = sum(1 for i in range(TW * TH) if struct.unpack_from("<H", thumb, 2 * i)[0] == frames.KEY_RGB565)
        assert 0.10 < key / (TW * TH) < 0.95
        # our thumbnail's see-through mask agrees with the stock one almost everywhere
        ours = frames.make_thumbnail(rows)
        agree = sum(1 for i in range(TW * TH)
                    if (struct.unpack_from("<H", ours, 2 * i)[0] == frames.KEY_RGB565) == (struct.unpack_from("<H", thumb, 2 * i)[0] == frames.KEY_RGB565))
        assert agree / (TW * TH) > 0.85, (n, agree)


def test_stock_frame_rgba_roundtrip(stock_image):
    for n in range(4):
        rp = frames.read_frame(stock_image, n)
        back, nudged = frames.rgba_to_frame(frames.frame_to_rgba(rp))
        assert nudged == 0
        assert frames.key_mask(back) == frames.key_mask(rp)
        opaque = 0
        out_of_gamut = 0
        chroma_off = 0
        for p in range(H // 2):
            b = p * frames.ROWPAIR_BYTES
            for k in range(W // 2):
                u, v = rp[b + W + 2 * k], rp[b + W + 2 * k + 1]
                pair_in_gamut = True
                pair_opaque = False
                for off in (0, 2 * W):
                    e = b + off + 2 * k
                    if rp[e] == 140 and rp[e + 1] == 140:
                        continue
                    pair_opaque = True
                    for x in (e, e + 1):
                        opaque += 1
                        # every luma survives the trip through RGB, in or out of gamut
                        assert abs(rp[x] - back[x]) <= 2, (n, x, rp[x], back[x], u, v)
                        if not in_gamut(rp[x], u, v):
                            out_of_gamut += 1
                            pair_in_gamut = False
                if pair_opaque and pair_in_gamut:
                    if abs(rp[b + W + 2 * k] - back[b + W + 2 * k]) > 2 or abs(rp[b + W + 2 * k + 1] - back[b + W + 2 * k + 1]) > 2:
                        chroma_off += 1
        assert chroma_off == 0, (n, chroma_off)
        # the saturated frame 2 has about 5% of its opaque pixels outside the RGB gamut, the others under 1%
        assert out_of_gamut < 0.10 * opaque, (n, out_of_gamut, opaque)
        # and the re-encoded strips fit their slots again
        for s, e in enumerate(frames.frame_slots(stock_image)[n].strips):
            assert len(frames.encode_gpzp(back[s * frames.STRIP_BYTES: (s + 1) * frames.STRIP_BYTES])) <= e.budget, (n, s)


def test_replace_cli_on_stock_copy(stock_image, tmp_path, capsys):
    new = check_replace_cli(stock_image, 1, tmp_path, capsys)
    # everything outside the resource pack is untouched, the other three frames are intact
    assert new[: image.RESOURCE_BASE] == stock_image[: image.RESOURCE_BASE]
    assert new[image.SOFTWARE_END:] == stock_image[image.SOFTWARE_END:]
    for n in (0, 2, 3):
        assert frames.read_frame(new, n) == frames.read_frame(stock_image, n)
        assert frames.read_thumbnail(new, n) == frames.read_thumbnail(stock_image, n)
    assert image.find_resources_end(new) == image.find_resources_end(stock_image)


def test_stock_frames_survive_extract_and_replace(stock_image, tmp_path, capsys):
    """The flow a user follows: extract a stock frame as PNG, put it back unchanged. Every strip and the
    generated thumbnail must fit the stock slots again (frame 3's thumbnail, 1,406 of 1,536 bytes, is the
    tightest; frame 2's bottom band only fits because the export preserves luma)."""
    src = tmp_path / "src.bin"
    src.write_bytes(stock_image)
    out_dir = tmp_path / "ex"
    assert run("frames", "extract", str(src), "--out", str(out_dir)) == 0
    capsys.readouterr()
    for n in range(4):
        out = tmp_path / f"back{n}.bin"
        assert run("frames", "replace", str(src), "--slot", str(n), "--png", str(out_dir / f"frame-{n}.png"),
                   "--out", str(out), GATE) == 0, n
        text = capsys.readouterr().out
        assert "nudged 0" in text and "over by" not in text
        new = out.read_bytes()
        rp = frames.read_frame(new, n)
        orig = frames.read_frame(stock_image, n)
        assert frames.key_mask(rp) == frames.key_mask(orig)
        for p in range(H // 2):
            b = p * frames.ROWPAIR_BYTES
            for off in (0, 2 * W):
                assert all(abs(a - c) <= 2 for a, c in zip(rp[b + off: b + off + W], orig[b + off: b + off + W])), (n, p)
        assert len(frames.read_thumbnail(new, n)) == frames.THUMB_BYTES
    assert src.read_bytes() == stock_image
