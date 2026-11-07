# Image layout reference

Reading order: 11 of 13 · previous: [Photo dates (EXIF)](10-EXIF.md) · next: [Boot and power notes](12-NOTES-boot-and-power.md) · [all docs](README.md)

What is where in a 2 MiB Charmera flash dump, and how the SD update file relates to it. Every offset below was
checked against the two public dumps and the published update files; where something is inferred rather than
read off the bytes it says so. The long form, with sources, is in the [Research report](13-PHASE1-REPORT.md).

File offset = runtime address + 0x200. The flash is a Puya PY25D16HB: 2 MiB, 64 KiB erase blocks, 256-byte pages.

| File offset | Size | Contents |
|---|---|---|
| 0x000000 | 0x200 | Boot header, magic `SPII`. Register/value pairs the boot ROM applies before jumping to the code: 0xD0000088 = 0xA12B and 0xD000008C = 0x2495 are the PLL/power unlock keys, 0xD0000028 = 0x8005, 0xD00000AC = 0x104. One word (0x1B0000) has no known meaning. |
| 0x000200 | ~0xFF870 | ARM7TDMI code and read-only data, vector table first. In the public dump: build stamp `202505201029` at 0x218C4, build string `JH5408  20250520` at 0x18964, compile date `Oct 14 2025` at 0xF3978, the sensor reset table at 0xF5BFC and the 61-write sensor init table at 0xF5C6C. Another build keeps them elsewhere (the film-mods build: stamp at 0x218D0, init table at 0xF5D34), so the toolkit finds them by content; see [Build identity](#build-identity). |
| 0x0FFA70 | 0xB6C0 | Initialised data (`.data`), copied to RAM at boot. |
| 0x10B130 | to 0x120000 | Zero padding. 0x110000..0x120000 is the heap placeholder. |
| 0x120000 | 0x48E9A | Resource pack: magic `GP+\0` followed by two u16 words (both 3 in the stock build; meaning unknown) and zero padding to +0x18, then 24-byte directory entries of a 20-byte name plus a u32 sector number (×512 from 0x120000), 43 in the stock build; the list ends at the first name starting with 0x00 or 0xFF. No checksum. Holds the five UI sounds, the logo and menu JPEGs, the GPZP frames, `GPRS.PAK` at 0x141400. Ends at 0x168E9A; 0xFF to 0x16E000. |
| 0x16E000 | 0x200 | Factory self-test sector. |
| 0x170000 | 0xF000 | Settings bank: up to 120 512-byte sectors, each a 252-byte record + CRC32 (zlib, little-endian) at +0xFC and a zero second page. The firmware loads the sector before the first erased one. |
| 0x17F000 | 0x200 | Photo directory: two 8-byte entries {u16 number, u16 used, u32 length}. Also sector 120 of the settings bank, which is why the stock camera misbehaves after about 120 settings saves. |
| 0x180000 | 0x25800 | Internal photo slot 0 (0x590-byte header + JPEG). |
| 0x1A5800 | 0x25800 | Internal photo slot 1. |
| 0x1CB000 | to 0x200000 | Unused, 0xFF. |

## Settings record fields

Offsets within the 252-byte record. Verified means the firmware's setter and reader for the byte were followed.

| Offset | Meaning | Status |
|---|---|---|
| +0x00 | Capture style / frame selector 0..10 | verified (cycled by its setter) |
| +0x01 | Auto power-off code: 1 = 3 min, 2 = 5 min, n ≥ 3 = n min; default 2 | verified; timer rate unmeasured |
| +0x05 | Playback volume 0..8 | verified |
| +0x19 | UI sound level 0..8, 0 = off; default 5 | verified |
| +0x51 | Standby minutes before the audio path is powered down; default 2 | verified |
| +0x03, +0x12, +0x52, +0x53 | Change between live records in the dumps; purpose unknown | observed only |

## Build identity

What identifies a build, and what does not. The version stamp `202505201029` is carried by two different builds: the
public dump and the build on the charmera-film-mods author's camera, which has different bytes (its author confirmed
it from their own dump on 2026-11-06). The build string, the compile date, the yellow boot screen and the
"original series" body do not tell them apart either. So:

- The toolkit names a build by a fingerprint over its bytes: SHA-256 of 0..0x169000 (the span an official-size file writes)
  for the public dump, `bffddd53…`; SHA-1 `d1f21227…` and byte sum `0852B369` over the same span, which is what the
  film-mods author compared against (their build: SHA-1 `91499bd3…`, sum `0852C329`). A modified copy of a known build
  is recognised by its marks lying where that build keeps them.
- The build marks are found by content, never at a fixed offset: the stamp as 12 ASCII digits ending in NUL (by
  preference the one that follows the `*.bin` pattern the updater matches with), the build string as `JH5408` plus
  spaces plus 8 digits, and the sensor tables as the reset table `fd 00 20 00 ff ff` followed by the first
  register/value table that starts with a page select (`fd 00`), switches page at least once more, holds at least 16
  writes and ends with `ff ff`; the init table found this way is cross-checked against the code literal that holds
  its RAM address (file offset minus 0x200; in the public dump at 0x7B91C).
- A build match says which public image a file was made from. It does not say whether the file fits the camera it
  goes into: both known builds carry the same 61-write sensor table, byte for byte, and drive one sensor without
  reading which is fitted, and the public dump left an original-series camera unable to take pictures. The one check
  that does say something about a camera is `verify --stock <a dump of that camera>`, which compares the file's
  sensor table and software with the camera's own.

## Update file (`JH_5408<stamp>_<sum>.bin`)

- Two lengths. The community files are 0x1E0000 bytes: the first 0x1E0000 bytes of a dump, including the settings
  bank and the photo slots of whoever made it (`pack` can blank those), and the updater's extra erase lands on the
  blank block 0x1F0000. The film-mods files are 0x169000 bytes (`pack --official-size`, a name taken from that
  project's reading that official updates would end there; no official Charmera update has ever been published): the
  code and the whole resource pack, nothing of the camera's own area, and the extra erase lands on 0x170000, the
  settings bank and the photo directory, so the camera starts with factory defaults and an empty photo list; the
  stored photo data at 0x180000 and the blocks above 0x1E0000 are not touched. `pack` also takes a software-only
  image of exactly 0x170000 bytes, or an official-size file, and pads it; any other shorter input is refused as
  truncated, because the updater programs whatever the file holds and padding a cut-off file would erase part of the
  code or the resource pack.
- `<stamp>` is 12 ASCII digits (0-9 only; the camera reads fixed character positions) and must differ from the stamp
  compiled into the running firmware or the updater ignores the file. Both known builds carry `202505201029`. The
  toolkit never changes the stamp inside the file, so a toolkit-built file flashes again at every power-on while it is
  on the card; the film-mods tool writes the new stamp inside its files too, so a camera ignores them once it runs one.
- `<sum>` is the unsigned 32-bit sum of every byte of the file, as 8 hex digits. A mismatch shows "Program error"
  and nothing is written.
- The updater looks only in the card's root, only during the first 5 seconds after power-on, erases 64 KiB
  blocks from 0 up to the file length, then erases one more 64 KiB block at ((length rounded up to 256) + 0x10000)
  rounded down to 64 KiB, then programs and verifies 256-byte pages. For a 0x1E0000 file that extra block is
  0x1F0000 (block 31, blank anyway; block 30 is left alone). For a 0x169000 file it is 0x170000, the settings bank.
  For a 0x170000 file it is 0x180000, the first photo slot. For a 2 MiB file it is 0x210000, beyond the chip: the
  address wraps to block 1 and the camera no longer boots (confirmed on hardware by the film-mods author). That is why
  the length matters: `verify` names the block it would hit.
- The file is not deleted afterwards. Take the card out before the next power-on or it flashes again.

## Resource entries

The camera checks only the pack's `GP+` magic, nothing inside it, so `verify` does. An entry's slot runs from its offset to the next
entry's offset; the last entry's slot runs to the factory self-test sector at 0x16E000. Every slot must lie after the
directory and inside the file, must not be erased (all 0xFF), and must hold the kind its name says:

| Name ends in | Must hold |
|---|---|
| `.WAV` | `RIFF` ... `WAVE` with a `fmt ` and a `data` chunk; 8 + the RIFF size fits the slot |
| `.JPG` | starts FF D8 and has an FF D9 end marker inside the slot |
| `.GPZP`, `.GPZ` | `GPZP`, a raw DEFLATE stream that inflates, a trailer whose CRC-32 and length match, fixed-Huffman blocks only |
| `.PAK` | starts `GPRS` |

The two public dumps, the published stock restore file and the three withdrawn [flash candidates](03-FLASH-CANDIDATES.md)
pass. A cut-off download padded with 0xFF fails here even when the rest of the file looks right. In an official-size
file the last entry's slot ends at 0x169000 instead of 0x16E000.

## Resource sound slots

| Name | Offset | Format | Data bytes | Slot |
|---|---|---|---|---|
| BEEP.WAV | 0x121E00 | 11025 Hz, 16-bit | 4032 | 4096 |
| CLICK.WAV | 0x123200 | 8000 Hz, 8-bit | 1882 | 2048 |
| IMG.WAV (shutter) | 0x15FC00 | 11025 Hz, 8-bit | 5480 | 5632 |
| POWEROFF_AUDIO.WAV | 0x162400 | 8000 Hz, 8-bit | 8421 | 8704 |
| POWERON_AUDIO.WAV | 0x164600 | 8000 Hz, 8-bit | 6097 | 6144 |

A replacement must fit its slot (header included); `sounds replace` resamples and trims to make it fit and says
what it cut. BEEP.WAV is never requested by the event player in this build.

## GPZP (compressed frames)

`GPZP` + raw DEFLATE (fixed Huffman) + trailer {u32 CRC32 of the inflated data, u32 inflated length} + 0xFF padding
to the next entry. The 800×600 frames inflate to three 240,000-byte strips in a row-pair 4:2:0 layout
([Y even row][U,V per pair][Y odd row]); luma 0x8C in both rows of a pair is the see-through key. Thumbnails
(`PV00n`) are 112×80 RGB565 little-endian with 0x8C71 as the key colour. The stock encoder is not zlib: its
blocks hold exactly 4,096 symbols. Python's `zlib.compressobj(9, zlib.DEFLATED, -15, 8, zlib.Z_FIXED)` reproduces 23 of
the 35 stock streams byte for byte; the other 12 come out 1 to 3 bytes shorter and inflate to identical data.
Every stock stream uses fixed-Huffman blocks only. `frames replace` refuses any output that is not fixed-Huffman, stored blocks included, and `verify`
fails any GPZP entry with a block that is not fixed-Huffman, stored blocks included.

---

Reading order: 11 of 13 · previous: [Photo dates (EXIF)](10-EXIF.md) · next: [Boot and power notes](12-NOTES-boot-and-power.md) · [all docs](README.md)
