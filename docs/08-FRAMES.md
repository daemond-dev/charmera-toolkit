# Photo frames

Reading order: 8 of 15 · previous: [Command reference](07-USAGE.md) · next: [Resource pack and boot logo](09-RESOURCES.md) · [all docs](README.md)

The camera's frame mode lays one of four decorative overlays over the live view and the saved photo. The
four overlays and their menu thumbnails live in the resource pack at 0x120000, so a modified image can carry
your own. `charmera frames` lists, extracts, previews and replaces them.

The file formats below were checked against the stock dump byte for byte. The see-through rule the camera
applies when it draws a frame comes from the community's disassembly and emulation of the firmware. This
project hasn't flashed a replaced frame to a camera yet, so treat `replace` as an experiment. Its output goes
through `pack` and `verify` like every other mod ([The first flash](01-FIRST-FLASH.md), steps 3 to 5), and you
should keep your backup. The other resources, including the boot logo, are in
[Resource pack and boot logo](09-RESOURCES.md). The byte layout of the pack is in [Image layout](11-FORMAT.md).

## Where the frames are

Each frame `n` (0..3) is four entries in the resource directory:

| entry       | holds                                   | stock offset (frame 0) | stock budget (frames 0 / 1 / 2 / 3)     |
|-------------|-----------------------------------------|------------------------|-----------------------------------------|
| `F_n_000`   | rows 0-199 of the 800x600 overlay       | 0x124000               | 15,872 / 14,336 / 7,680 / 13,824 bytes  |
| `F_n_001`   | rows 200-399                            | 0x127E00               | 2,048 / 8,704 / 2,048 / 18,944 bytes    |
| `F_n_002`   | rows 400-599                            | 0x128600               | 8,704 / 3,584 / 7,168 / 16,896 bytes    |
| `PV00n`     | 112x80 menu thumbnail                   | 0x165E00               | 1,024 / 1,024 / 1,024 / 1,536 bytes     |

An entry's budget is the distance to the next entry in the directory. That's all the room a replacement
gets, because the toolkit doesn't move entries. `charmera frames list` prints the real offsets, budgets and
file sizes of whatever image you give it.

## The GPZP container

All 35 GPZP resources in the stock pack have the same shape:

```
'GPZP'                          4 bytes
raw DEFLATE stream              fixed-Huffman blocks only (no zlib header, no dynamic trees)
CRC-32 of the inflated payload  u32 little-endian (zlib.crc32)
inflated length                 u32 little-endian
0xFF padding                    to the next entry
```

The firmware's inflater has no dynamic-Huffman path, so the toolkit encodes with zlib's `Z_FIXED` strategy
at level 9. That strategy never writes a dynamic block, but zlib still writes a stored (uncompressed) block
when storing is smaller, which happens for near-random content such as noise or glitter. No stock stream
holds a stored block, and nothing shows that the camera's inflater handles one. So `replace` walks every
stream it encoded, block by block, reads it back against the data it was made from, and refuses to write
anything that isn't fixed-Huffman throughout.

Fixed Huffman costs a little compression (8 bits per literal, 7 or 8 bits plus extra bits per match). The
stock encoder isn't zlib: its blocks hold 4,096 symbols, where zlib's hold 4,095 or 16,383. Re-encoding a
stock payload gives a stream 1 to 3 bytes shorter than the original on 12 of the 35 entries and
byte-identical on the other 23. It always inflates to the same bytes.

## The overlay payload

A strip inflates to exactly 240,000 bytes: 100 row-pairs of 2,400 bytes. Each row-pair is laid out like this:

```
800 bytes  luma (Y) of the even row, one byte per pixel
800 bytes  chroma of the row-pair: U then V for pixels 0-1, U then V for pixels 2-3, ...
800 bytes  luma (Y) of the odd row
```

This is 4:2:0 with the two chroma samples of a 2x2 block written between its two rows. Colour is full-range
BT.601: Y = 0.299 R + 0.587 G + 0.114 B, U = -0.168736 R - 0.331264 G + 0.5 B + 128,
V = 0.5 R - 0.418688 G - 0.081312 B + 128. Strip `s` holds rows 200s to 200s+199.

## The see-through rule

When the camera draws a frame over a photo, it walks the overlay in horizontal pixel pairs. If both luma
bytes of a pair are 140 (0x8C), the pair is see-through and the photo stays. Otherwise the pair's two lumas
are written over the photo, and on even rows its U and V bytes too. Chroma plays no part in the decision.

That has two consequences when you draw a frame:

- An opaque grey of exactly luma 140 would punch a hole. `rgba_to_frame` catches every opaque pair whose
  two lumas both come out as 140 and sets the second one to 141, and `replace` prints how many pairs it
  nudged. You can't tell 140 from 141 by eye, but if your design is a flat mid-grey you'll see the count.
- Only the even row carries the chroma of a row-pair. When the even row's pair is see-through and the odd
  row's pair isn't, the odd row shows the frame's luma over the photo's colour. So one-pixel-high horizontal
  lines on odd rows take the photo's tint. Make horizontal details at least two rows tall and start them on
  an even row.

This rule is the community's reading of the compositor, confirmed in an emulator. This project hasn't
flashed any of it yet.

## Making a frame

Draw an 800x600 PNG with an alpha channel. Transparent pixels (alpha below 128) are see-through, and
everything else covers the photo. Export it as an 8-bit RGBA PNG.

RGB, greyscale and palette PNGs (palette and greyscale at 1 to 8 bits) are read too. In those, a transparent
colour or palette alpha in a `tRNS` chunk counts as alpha. That's what ImageMagick's `PNG24:` and Pillow's
`transparency=` write. Without one the frame has no see-through area at all, and `replace` says so.
Greyscale-with-alpha, 16-bit and interlaced PNGs are refused with a message asking for an 8-bit RGB or RGBA
export, and a damaged file gets a message saying which chunk is wrong. The toolkit has its own small PNG
reader and doesn't need an image library.

The see-through mask has a horizontal resolution of two pixels. A pair with one transparent and one opaque
pixel is drawn opaque, in the opaque pixel's colour, as the published frame maker does. Colour resolution is
one 2x2 block: a block's U and V are the average over its opaque pixels. Keep edges on even pixel columns and
even rows and neither effect shows.

`extract` writes the stock frames as PNGs you can edit. Some stock colours lie outside what RGB can hold
(the saturated frame 2 most of all). The export desaturates those pixels towards grey instead of clipping
each channel, so every luma byte comes back unchanged when the PNG is converted again. The see-through rule
and the compression both depend on those bytes. Put back untouched, all four stock frames fit their own
slots.

The camera's frames are mostly empty. The stock overlays are 64%, 69%, 91% and 15% see-through, and the
slots were sized for them: frame 2's middle band has 2,048 bytes, and frame 3 has almost 50 KB altogether.

### Why detail costs bytes

The payload is 240,000 bytes per strip and the slot holds a few thousand, so a strip has to compress 15 to
120 times. Fixed Huffman gives nothing away for free. Every change of value costs at least a literal
(8 bits), and every repeated run is a match (a few bits per 258 bytes at best). Flat colour, flat
see-through areas and shapes that repeat from row to row compress well. Gradients, anti-aliased edges,
noise, photographs and text on a textured background don't. When a strip doesn't fit, `replace` prints
every strip with its size and budget and writes nothing:

```
  strip 0: 1,471 bytes, budget 15,872: fits (14,401 spare)
  strip 1: 3,105 bytes, budget 2,048: over by 1,057; simplify that band of the frame (rows 200-399)
  strip 2: 2,004 bytes, budget 8,704: fits (6,700 spare)
  thumbnail: 371 bytes, budget 1,024: fits (653 spare)
```

Pick the frame slot whose budgets match where your detail is, move the detail to a band with room, or
flatten it (fewer colours, hard edges, no gradient).

Near-random content can fail a second way, even when it fits. Where about 16 KB of a band is noise, zlib
stores that part uncompressed in a stored block, which no stock frame uses (see
[The GPZP container](#the-gpzp-container)). In the stock layout only frame 3's middle and bottom bands are
big enough to hold one. `replace` then refuses that band and writes nothing:

```
  strip 1: 18,480 bytes, budget 18,944: cannot be written, too noisy for the camera's encoding: zlib could only store 16,429 bytes of it uncompressed (a stored DEFLATE block), and the camera's frames use fixed-Huffman blocks only; simplify that band of the frame (rows 200-399): flat colours, no noise, grain or photo texture
error: strip 1 cannot be written: the camera's frames hold fixed-Huffman blocks only (see the line above); nothing written
```

## The thumbnail

`PV00n` is the picture the frame menu shows: 112x80 pixels, little-endian RGB565, 17,920 bytes inflated.
See-through areas are painted with the key grey 0x8C71 (R 136, G 140, B 136).

`replace` makes a thumbnail from the frame. Each thumbnail pixel covers about 7x7.5 frame pixels and takes
the most common colour among the opaque ones. (Averaging would blend every edge into a new colour, and
fixed-Huffman DEFLATE charges for each one.) An area that is mostly see-through gets 0x8C71, and an opaque
pixel that happens to pack to exactly 0x8C71 is moved one blue level. The thumbnail slots are small
(1,024 bytes, 1,536 for frame 3). When the full-colour thumbnail doesn't fit, colours are rounded to fewer
RGB565 levels, one bit per channel at a time, until it does, and `replace` says when that happened. The four
stock frames, put back from their PNGs, get thumbnails of 400 to 1,406 bytes at full colour, so none of them
needs any rounding.

To supply your own thumbnail, pass `--thumb` with a 112x80 PNG. Transparent pixels (alpha below 128) and
pixels of the key grey itself (anything that packs to 0x8C71) become 0x8C71. Every other pixel is written as
its RGB565 colour, without the nudge. `extract` writes `thumb-n.png` as RGBA with the key grey transparent
(the grey is kept underneath), so a thumbnail put back unchanged gives the stock thumbnail word for word,
key grey included. An RGB `thumb-n.png` without alpha goes back the same way.

Nobody has checked whether the menu treats 0x8C71 as a key colour or just paints it. The stock thumbnails
use it where the frame is see-through, so the toolkit does the same.

## The flow

```
charmera frames list my_backup.bin
charmera frames extract my_backup.bin --out frames/
    # frames/frame-0.png .. frame-3.png and thumb-0.png .. thumb-3.png (RGBA, transparent where see-through),
    # and the raw .gpzp files
charmera frames preview my_backup.bin --slot 1 --photo photo-800x600.png --out preview.png
    # photo-800x600.png must be exactly 800x600; crop or scale it yourself first
charmera frames replace my_backup.bin --slot 1 --png frame.png --out framed.bin
    # prints the plan: see-through fraction, nudged pairs, every strip's size against its budget
charmera frames replace my_backup.bin --slot 1 --png frame.png --thumb thumb.png --out framed.bin --i-understand-this-is-a-mod
charmera pack --input framed.bin --out out/frames/ --i-understand-this-file-flashes-a-camera
charmera verify out/frames/JH_5408<stamp>_<sum>.bin --stock my_backup.bin --allow-modified
```

`extract` refuses to write into a folder that already holds any of the files it would write, and names
them. Otherwise a second extract would replace the PNGs you edited there. Pass `--overwrite` to replace
them, or extract into an empty folder. It never replaces the input file.

`replace` never writes to the input file, and it refuses an existing `--out` without `--overwrite`. Its
output is a full image that must not go on a card as it is, so it also refuses an `--out` named like an
update file (`JH_*.bin` in any letter case, also with trailing dots or spaces) and an `--out` in a folder that
holds a DCIM folder or a `JH_*.bin`.

It writes each of the four encoded files into its slot, followed by 0xFF padding up to the budget, so every
byte outside those four slots is unchanged. The software region therefore differs from your backup, and
`verify` reports that as a FAIL unless you pass `--allow-modified`. With the flag, the same line is a WARN
for the intended mod (it still names the regions that changed) and every other check runs as usual. Without
it, verify ends with "do not put this file on a card".

Start from `extract`. The stock frame PNGs show what the camera draws, and editing one keeps you inside its
budgets.

## What is not known

- Nothing from this module has been flashed. The community verified the compositor rule in an emulator.
  That the camera accepts the toolkit's streams (fixed Huffman, but with zlib's block boundaries instead of
  the stock encoder's) is expected from the inflater's code, not shown on hardware.
- Whether the menu keys on 0x8C71 in the thumbnail or just displays it.
- Whether the firmware tolerates a frame entry whose file is longer than the stock one but within the
  budget. The directory stores sectors, not lengths, and the trailer carries the length, so it should. That
  doubt is why `replace` fills to the budget and never beyond.
- How the camera scales the 800x600 overlay onto a saved photo of a different size, if it does.

---

Reading order: 8 of 15 · previous: [Command reference](07-USAGE.md) · next: [Resource pack and boot logo](09-RESOURCES.md) · [all docs](README.md)
