# The resource pack and the `resources` command

Reading order: 9 of 13 · previous: [Photo frames](08-FRAMES.md) · next: [Photo dates (EXIF)](10-EXIF.md) · [all docs](README.md)

What the GP+ pack at 0x120000 holds, how `resources list` / `extract` / `replace` treat it, and how to make a
boot logo the camera has a chance of accepting. Every offset and length below was read off the stock
original-series dump; what the camera does with a changed logo is UNTESTED (no custom logo has been flashed;
toolkit-built files have been, see [the incident notes](FIRST-FLASH-INCIDENT.md)). The frames in the pack have their
own command and document (`frames`, [Photo frames](08-FRAMES.md)),
the sounds too (`sounds`, [Image layout](11-FORMAT.md)).

## The pack

| Where | What |
|---|---|
| 0x120000 | Magic `GP+\0`, then a u16 entry count (43). The firmware checks only the two letters `GP`. |
| 0x120018 | Directory: one 24-byte entry per file, a 20-byte NUL-padded name with the dot removed (`POWER_ON_LOGOJPG`) and a u32 sector number. File offset = 0x120000 + sector x 512. |
| 0x120600 .. 0x168E9A | The files, in directory order, each starting on a 512-byte sector. |
| 0x168E9A .. 0x16E000 | 0xFF. |

There is no checksum over the pack or over any file. The firmware opens a file by name (the lookup strips
the dot, which is why the directory spells names without one) and clamps reads to the slot: the bytes from
the entry's sector to the next entry's sector. The toolkit calls that span the entry's *budget*; the last
entry's budget runs to the end of the data. A file is usually shorter than its budget and the rest of the
slot is padding. In the stock dump that padding is 0xFF in every slot that has any (the erased state of
the flash), and `replace` keeps whatever byte the slot already uses.

### Kinds

The kind of an entry is read from its first bytes, not from its name.

| First bytes | Kind | Length of the file | Change it with |
|---|---|---|---|
| `RIFF` | WAV, the five UI sounds | 8 + the u32 at offset 4 | `sounds replace` |
| `FF D8` | JPEG | up to and including the `FF D9` end marker, found by walking the marker segments to the scan and then through the entropy-coded data (stuffed `FF 00` bytes and restart markers do not end it) | `resources replace` |
| `GPZP` | compressed graphic: 4-byte magic, a raw DEFLATE stream, then a trailer of u32 CRC-32 and u32 length of the inflated data, both little-endian. Names end in `.GPZP` or `.GPZ`. | 4 + the DEFLATE bytes + 8, found by inflating | `frames replace` for `F_n_00s` and `PV00n`; no command for the others yet |
| `GPRS` | `GPRS.PAK`, a nested pack of the menu text in 14 languages | the whole budget (123,392 bytes) | nothing yet |

The stock build has 35 GPZP files, 5 WAVs, 2 JPEGs and the one GPRS pack. The two JPEGs:

| Name | Offset | Budget | File | Metadata it carries |
|---|---|---|---|---|
| `AUDIO_REC_BG.JPG` | 0x120800 | 4608 | 4358 bytes | APP1 (36 bytes) |
| `POWER_ON_LOGO.JPG` | 0x161600 | 3584 | 3532 bytes | APP1, APP12, APP1 (888 bytes together) |

Both are baseline JPEG (SOF0), 160x80 pixels, 8-bit samples, three components with sampling factors
2x2,1x1,1x1 (4:2:0), a JFIF APP0 segment, no restart interval, a single interleaved scan. 160x80 is also
the size of the LCD: the boot code decodes the logo into a 160x80 RGB565 frame (INFERRED from the 0x6400
bytes it copies). The boot code looks `POWER_ON_LOGO.JPG` up by name, hands it to the chip's JPEG decoder
and shows the result until the home screen appears; the same file is shown again for 600 ms at power-off.
`AUDIO_REC_BG.JPG` is the background of the audio-recorder screen. Nothing is known about what the decoder
accepts beyond these two files, which is why `replace` accepts nothing that differs from them.

## `resources list IMAGE`

Prints one line per entry: index, name, offset, budget, file length, kind, padding byte and the details
that matter for that kind.

```
charmera resources list my_backup.bin
```

- JPEG: size, process (baseline or not), component count, sampling factors with the usual name (4:2:0),
  whether there is a JFIF APP0, any restart interval, and the metadata segments (what `--strip-metadata`
  would remove) with their total size.
- WAV: sample rate, bit depth, channels, PCM bytes and duration.
- GPZP: the inflated length and whether the trailer's CRC-32 and length match the inflated data.
- GPRS: kept whole.

The `pad` column is `FF` or `00` when every padding byte in the slot is that value, `mix` when it is not
uniform, `-` when the file fills its budget. A line ending in `BAD:` names an entry the toolkit could not
read as its kind claims (a GPZP whose CRC does not match, a WAV whose RIFF size runs past its slot, a JPEG
with no end marker inside the slot); the command then exits 1. Exit 2 means the file is not an image with a
GP+ pack. `list` never writes.

## `resources extract IMAGE --out DIR [--name NAME]`

Writes every entry, or only `--name`, into `DIR` under its display name (`POWER_ON_LOGO.JPG`), trimmed to
the file's own length so that no padding comes along. GPZP files are written as they are in the flash and
also inflated to a `.inflated` sidecar (`AP_ICON.GPZP.inflated`). `GPRS.PAK` and anything of unknown kind
is written whole. `--name` takes either spelling (`POWER_ON_LOGO.JPG` or `POWER_ON_LOGOJPG`), in any case.

```
charmera resources extract my_backup.bin --out resources/
charmera resources extract my_backup.bin --out . --name POWER_ON_LOGO.JPG
```

If any target file already exists the command refuses before writing anything and names the files; pass
`--overwrite` or use an empty directory.

## `resources replace IMAGE --name NAME --file FILE --out NEW.bin [--strip-metadata] [--overwrite] --i-understand-this-is-a-mod`

Puts a JPEG of your own into a JPEG slot. Without the gate flag it prints the plan (both files' properties,
what would be stripped, how the slot would be padded) and writes nothing; with it, it writes a full image to
`--out`. `--out` may not be the input, and an existing file is only replaced with `--overwrite`. A full image must not
go on a card as it is, so `--out` may not be named like an update file (`JH_*.bin`, in any letter case) or lie in a
folder that holds a DCIM folder or a `JH_*.bin`.

```
charmera resources replace my_backup.bin --name POWER_ON_LOGO.JPG --file logo.jpg --out logo.bin
charmera resources replace my_backup.bin --name POWER_ON_LOGO.JPG --file logo.jpg --out logo.bin --strip-metadata --i-understand-this-is-a-mod
charmera pack --input logo.bin --out out/logo/ --i-understand-this-file-flashes-a-camera
charmera verify out/logo/JH_5408<stamp>_<sum>.bin --stock my_backup.bin --allow-modified
```

What has to hold, checked in this order; the first property that differs is named and the command exits 2:

1. The image is the public dump's build or a copy of it (its fingerprint, or its build marks at that build's
   offsets together with that build's sensor table, say so). Other builds have an unknown pack layout.
2. The entry is a JPEG. For a WAV the message points at `sounds replace`, for a frame strip or thumbnail at
   `frames replace`; the other GPZP files and `GPRS.PAK` are refused because nobody knows yet what the
   firmware needs from them.
3. The file is a complete JPEG (start marker, frame header, scan, end marker). Bytes after the end marker
   are dropped, and the plan says how many.
4. Process: baseline (SOF0). Progressive (SOF2), extended sequential (SOF1), lossless and arithmetic-coded
   files are refused; the stock decoder has only been seen with baseline input.
5. Sample precision: 8 bits, like the stock file.
6. Size: exactly the width and height of the file it replaces (160x80 for both stock JPEGs).
7. Components and sampling factors: three components with 2x2,1x1,1x1 (4:2:0). Greyscale, 4:4:4 and 4:2:2
   files are refused.
8. Restart interval: none, because the stock files have none (a DRI segment or RSTn markers in the scan
   data are refused). If a stock file had one, any interval would be allowed.
9. Scan layout: one interleaved scan of all three components, like the stock file.
10. Length, after `--strip-metadata` if given: at most the budget of the slot (3584 bytes for the logo,
    4608 for the recorder background).

`--strip-metadata` removes APP1..APP15 and COM segments (Exif, XMP, ICC profiles, Photoshop and "Ducky"
blocks, comments) and keeps the JFIF APP0 when the stock file has one, which both do. The entropy-coded
data is copied byte for byte, so the picture does not change. The message for an oversized file says how
much stripping would save.

The new file goes at the entry's offset. Where the old file reached beyond the new one, the gap is filled
with the slot's padding byte (0xFF on every stock slot); the directory, every other entry and every other
byte of the image stay as they were, so `diff` against the input shows exactly one range inside the slot.

### Making a logo that passes

The target is a 160x80, baseline, 4:2:0 JPEG of at most 3584 bytes. Start from a 160x80 picture (or let
the tool resize; `!` in the ImageMagick command ignores the aspect ratio, so crop first if that matters).

ImageMagick:

```
convert in.png -resize 160x80! -sampling-factor 2x2,1x1,1x1 -quality 80 -strip out.jpg
```

Pillow:

```python
from PIL import Image
im = Image.open("in.png").convert("RGB").resize((160, 80))
im.save("out.jpg", "JPEG", quality=80, subsampling=2, optimize=True)
```

`subsampling=2` is Pillow's name for 4:2:0; `optimize=True` builds smaller Huffman tables and does not
make the file progressive (`progressive=True` would, and is refused). ffmpeg: `ffmpeg -i in.png -vf
scale=160:80 -pix_fmt yuvj420p -q:v 4 out.jpg` (the default MJPEG encoder writes baseline). GIMP: export as
JPEG with "Progressive" off, subsampling "4:2:0 (chroma quartered)", and the Exif, XMP, thumbnail and
comment boxes off.

Then run `replace` without the gate flag. It prints both files' properties side by side and either says
every check matches or names the one that does not. If the file is too large, lower the quality a step at a
time or add `--strip-metadata`; the yellow stock logo is 3532 bytes with 888 bytes of metadata, so a
picture of similar simplicity fits easily at quality 80.

### Before you flash it

A custom logo has not been tried on a camera. What is known: the decoder is in hardware, the boot code
calls it once and blocks until it returns, and the file is only reached after the settings are loaded and
the power-on sound is started. What is not known: whether the decoder checks anything the toolkit does not,
what it does with a file it dislikes (show garbage, show nothing, or hang the boot before the SD card is
mounted), and whether the Huffman tables an encoder "optimizes" are accepted as readily as the standard
ones. Two ways to keep the risk small:

- Have a full backup (`charmera backup`) and read [Recovery](02-RECOVERY.md) first. If the camera hangs at the logo
  it never mounts the card, so the SD updater cannot help; getting back then means writing the backup with
  a CH341A ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)). Do not try a logo without one.
- Try the recorder background first (`--name AUDIO_REC_BG.JPG`, 4608-byte budget). It is shown only when
  you enter the audio recorder, after the camera has booted and mounted the card, so a file the decoder
  rejects can be undone through the SD updater. If it displays correctly, the same encoder settings are a
  good candidate for the logo. (This ordering is a suggestion from how the files are used, not a tested
  procedure.)

If the camera does not boot after a logo change, go to [Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly), and restore the
backup you made before the change.

---

Reading order: 9 of 13 · previous: [Photo frames](08-FRAMES.md) · next: [Photo dates (EXIF)](10-EXIF.md) · [all docs](README.md)
