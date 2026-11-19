# Image layout reference

Reading order: 11 of 15 · previous: [Photo dates (EXIF)](10-EXIF.md) · next: [Boot and power notes](12-NOTES-boot-and-power.md) · [all docs](README.md)

This is the layout of a 2 MiB Charmera flash dump, and how the SD update file relates to it. Every
offset was checked against the two public dumps and the published update files, and the film-mods build's
offsets against that project's public restore file. Anything inferred or reported, and not read off the
bytes, is marked as such. The long form, with sources, is in the [Research report](13-PHASE1-REPORT.md).

File offset = runtime address + 0x200. The flash is a Puya PY25D16HB: 2 MiB, 64 KiB erase blocks, 256-byte pages.

| File offset | Size | Contents |
|---|---|---|
| 0x000000 | 0x200 | Boot header, magic `SPII`. Register/value pairs the boot ROM applies before jumping to the code: 0xD0000088 = 0xA12B and 0xD000008C = 0x2495 are the PLL/power unlock keys, 0xD0000028 = 0x8005, 0xD00000AC = 0x104. One word (0x1B0000) has no known meaning. |
| 0x000200 | ~0xFF870 | ARM7TDMI code and read-only data, vector table first (0x200..0x240). In the public dump: build stamp `202505201029` at 0x218C4, build string `JH5408  20250520` at 0x18964, compile date `Oct 14 2025` at 0xF3978, the sensor reset table at 0xF5BFC and the 61-write sensor init table at 0xF5C6C. Another build keeps them elsewhere (the film-mods build, VERIFIED from that project's public restore file: build string at 0x1897C, stamp at 0x218D0, compile date `Nov 19 2025` at 0xF3604, init table at 0xF5D34), so the toolkit finds them by content. See [Build identity](#build-identity). |
| 0x0FFA70 | 0xB6C0 | Initialised data (`.data`), copied to RAM at boot. |
| 0x10B130 | to 0x120000 | Zero padding. 0x110000..0x120000 is the heap placeholder. |
| 0x120000 | 0x48E9A | Resource pack: magic `GP+\0` followed by two u16 words (both 3 in the stock build; meaning unknown) and zero padding to +0x18, then 24-byte directory entries of a 20-byte name plus a u32 sector number (×512 from 0x120000), 43 in the stock build. The list ends at the first name starting with 0x00 or 0xFF. No checksum. Holds the five UI sounds, the logo and menu JPEGs, the GPZP frames, and `GPRS.PAK` at 0x141400. Ends at 0x168E9A, then 0xFF to 0x16E000. |
| 0x16E000 | 0x200 | Factory self-test sector. |
| 0x170000 | 0x10000 | Settings bank (see below the table). |
| 0x17F000 | 0x200 | Photo directory: two 8-byte entries {u16 number, u16 used, u32 length}. It sits inside the settings bank's erase block, so the bank's wrap-around erases it (internal photos then read as orphaned until the next photo save or delete), and a photo save, delete or FORMAT near the end of the bank can erase the current settings record (inferred from the code, not tested). |
| 0x180000 | 0x25800 | Internal photo slot 0 (0x590-byte header + JPEG). |
| 0x1A5800 | 0x25800 | Internal photo slot 1. |
| 0x1CB000 | to 0x200000 | Unused, 0xFF in every known dump. 0x1E0000..0x1EFFFF is the one block a 0x1E0000-byte update and its final erase never touch (see [Update file](#update-file-jh_5408stamp_sumbin)). |

The settings bank at 0x170000 is 128 512-byte sectors in one 64 KiB erase block. Each sector is a 252-byte record
plus a CRC32 (zlib, little-endian) at +0xFC, and a zero second page. Sector 120 (0x17F000) is the photo directory,
and the writer steps over it.

- The firmware loads the sector before the first erased one.
- A record is appended only when a setting changed (menu language, photo/video stamp, date format, USB mode), about
  every 5 s or at power-off. The frame selector is RAM only, and setting the date writes the clock, not the record.
- When sector 127 is reached the whole block is erased and writing restarts at sector 0 (static analysis of VA
  0x1D0C/0x1B44, 2026-11-11, not observed on a camera). This supersedes the model in the
  [Research report](13-PHASE1-REPORT.md).

The toolkit's settings reader (`charmera_toolkit/settings.py`) follows this model. It reads records in sectors 0..119
and 121..127, and reports a fallback to defaults only for a boot that would land on sector 120 (when sector 121 is the
first erased one). A rewrite of the store keeps the photo directory and erases stale records past it.

## Settings record fields

Offsets are within the 252-byte record. "Verified" means the firmware's setter and reader for that byte were
followed in the code.

| Offset | Meaning | Status |
|---|---|---|
| +0x00 | Menu language: index into the firmware's language table, 0..10 | verified (cycled by its setter, read as the language table index; the owner confirmed that holding Up in photo mode changes the language) |
| +0x01 | Auto power-off code: 1 = 3 min, 2 = 5 min, n ≥ 3 = n min; default 2 | verified; timer rate unmeasured |
| +0x05 | Playback volume 0..8 | verified |
| +0x19 | UI sound level 0..8, 0 = off; default 5 | verified |
| +0x51 | Standby minutes before the audio path is powered down; default 2 | verified |
| +0x03, +0x12, +0x52, +0x53 | Change between live records in the dumps; purpose unknown | observed only |

## Build identity

The version stamp `202505201029` doesn't identify a build, because three different builds carry it. One is the
public dump (v006). Another is the factory firmware of OV9732 cameras (v012). The third is the build on the
charmera-film-mods author's camera (v008_test). Its author confirmed it from their own dump on 2026-11-06, and
that project's public restore file holds it. All three show the build string `JH5408  20250520`. Their compile
dates differ (`Oct 14 2025` for v006, `Nov 19 2025` for v008_test, `Jan 12 2026` for v012), and so do the
source-tree folders in their source paths, `v006`, `v008_test` and `v012` (below). So the factory rebuilt the
firmware under one stamp.

A different compile date shows that two builds differ. It never shows that a file fits a camera, and the
same date wouldn't show that two builds are the same. The film-mods author's boot screen and body aren't
known. The yellow boot screen and the "original series" body didn't tell the public build apart from what the
2026-11-06 camera needed. So the toolkit goes by these rules:

- The toolkit names a build by a fingerprint over its bytes. For the public dump that's the SHA-256 of
  0..0x169000 (the span a 0x169000-byte file writes), `bffddd53…`, plus SHA-1 `d1f21227…` and byte sum
  `0852B369` over the same span, which is what the film-mods author compared against. Their build has SHA-1
  `91499bd3…` and sum `0852C329`, and SHA-256 `5b352e46…` of that project's public restore file (VERIFIED).
  A file without that fingerprint counts as a changed copy of a known build, that build "with changes"
  (`public-dump-modified`, `film-mods-modified`), only when two things hold. Its marks have to lie where that
  build keeps them (the stamp, the build string and the sensor table at that build's offsets), and it has to
  carry that build's stamp, sensor table, source-tree folder and compile date, as a mod made from it does. No
  toolkit mod changes any of the four, and the toolkit records all four for both known builds. Any other file
  laid out like a known build, even one that differs from it only in its compile date or its source-tree
  folder, is "a build this toolkit does not know, laid out like" that build (`charmera-unknown-build`).
  `verify`, `inspect`, `pack`, the backup and restore manifests and the app all name a build by this rule.
- The build marks are found by content, never at a fixed offset. The stamp is 12 ASCII digits ending in NUL
  (preferably the one that follows the `*.bin` pattern the updater matches with). The build string is
  `JH5408` plus spaces plus 8 digits. The sensor tables are the reset table `fd 00 20 00 ff ff`, followed by
  the first register/value table that starts with a page select (`fd 00`), switches page at least once more,
  holds at least 16 writes and ends with `ff ff`. The code literal that holds the init table's RAM address
  (file offset minus 0x200) is searched for at 4-byte-aligned offsets only. In the public dump it's at
  0x7B91C, in the film-mods build at 0x7B5A8. A table the code refers to is preferred. More than one
  referenced init table with different contents is a FAIL ("several sensor programs"), whether in the file or in
  the reference (`--stock`, or the app's backup), on every route including the donor route, and no flag clears
  it. `restore` refuses an image with several sensor programs, and asks for the second word `DIFFERENT`
  before it writes over a chip that holds several. Without `--stock`, a table the code doesn't refer to is a
  WARN. With `--stock`, outside the donor route, a file whose table isn't referred to fails when the one in
  `--stock` is. The app follows the same rule.
- The source-tree folder is a build mark too. The firmware keeps the paths of some of its source files, such
  as `G:\code\2247\5408\v006\host\tcpip\lwip-1.4.0\src\netif\etharp.c` in the public dump and
  `G:\code\2247\5408\v008_test\host\init.c` in the film-mods build. The folder after `\2247\5408\` is `v006`
  in the public build and `v008_test` in the film-mods build, 27 times in each (VERIFIED). The toolkit finds
  it by content, as the folder name that most often follows `\2247\5408\` in bytes 0..0x120000 (a name of
  printable ASCII, 0x20 to 0x7E, without a backslash). It records the folder for each known build and reports
  it with the other marks, at the offset where the name begins (0x3A4D2 in the public dump, 0x464BA in the
  film-mods build), in `verify`'s `series` line, `inspect`, `pack` and the app's build-marks line. A
  different folder shows a different build. The same folder doesn't show the same build, and neither says
  whether a file fits a camera.
- The ARM vector table at 0x200..0x240 is where the camera starts. `verify` and the app both check it on
  every route, the donor route included, in this order. The same 64 bytes as the reference's (`--stock`, or
  the app's backup) are a PASS. A file laid out like the reference (the stamp, the build string and the
  sensor table at the same offsets) with other bytes there is a FAIL. A file laid out differently from the
  reference, and any file checked without one, is compared with the known build whose layout it has, using a
  SHA-256 of those bytes kept for each known build. (The two known builds carry the same 64 bytes,
  SHA-256 `cf38b73d…`, VERIFIED.) The same bytes are a PASS, other bytes a FAIL. So a file laid out like the
  film-mods build, checked against a backup of the public build, is held to the film-mods build's table. Only
  a file laid out like neither the reference nor a known build, whose table isn't the reference's, gets a
  WARN saying the table couldn't be compared. No flag overrides a FAIL here, since a camera with a broken
  reset vector may not start far enough to run the card updater again.
- A build match says which public image a file was made from. Whether the file fits the camera it goes into
  is another question. Both known builds carry the same 61-write sensor table, byte for byte, and drive one
  sensor without reading which one is fitted, and the public dump left an original-series camera unable to
  take pictures. A known build's fingerprint is taken over 0..0x169000. The sensor-table rules, which
  `verify` and the app share, are in [The first flash, step 3](01-FIRST-FLASH.md#3-verify).

## Update file (`JH_5408<stamp>_<sum>.bin`)

- There are two lengths, and `verify` and `stage` take no other (`verify --allow-odd-size` takes another as
  a WARN, and `stage` never passes that flag).
  - The community files are 0x1E0000 bytes: the first 0x1E0000 bytes of a dump, including the settings bank
    and the photo slots of whoever made it (`pack` can blank those). The updater's extra erase lands on the
    blank block 0x1F0000.
  - The film-mods files are 0x169000 bytes (`pack --official-size`). The name comes from that project's
    reading that official updates would end there. No official Charmera update has ever been published.
    That author reports flashing a 0x169000-byte file on their own camera (REPORTED), and this project has
    flashed none. Such a file holds the code and the whole resource pack and nothing of the camera's own
    area. The extra erase lands on 0x170000, the settings bank and the photo directory, so the camera starts
    with factory defaults and an empty photo list. The stored photo data at 0x180000 and the blocks above
    0x1E0000 aren't touched.

  `pack` also takes a software-only image of exactly 0x170000 bytes, or an official-size file, and pads it.
  Any other shorter input is refused as truncated, because the updater programs whatever the file holds and
  padding a cut-off file would erase part of the code or the resource pack.
- The block 0x1E0000..0x1EFFFF (block 30) is the one block that a 0x1E0000-byte file and its final erase
  never touch. The file ends at its start, and the final erase hits the block after it. Whatever a camera's
  chip held there before its first such flash is still there after any number of them, probably 0xFF as in
  every known dump (INFERRED). A first backup reads it with the rest of the chip, so keep that backup as it
  is. When `pack` trims a 2 MiB dump it leaves this block out and only notes data there, since a
  0x1E0000-byte flash leaves it on the chip. It refuses a dump whose last block, 0x1F0000..0x200000, isn't
  blank, because the final erase clears that block.
- `<stamp>` is 12 ASCII digits (0-9 only, because the camera reads fixed character positions). It must
  differ from the stamp compiled into the running firmware, or the updater ignores the file. Both known
  builds carry `202505201029`. With `--stock`, `verify` also refuses the stamp found there (`stamp-camera`).
  The toolkit never changes the stamp inside the file, so a toolkit-built file flashes again at every
  power-on while it's on the card. The film-mods tool writes the new stamp inside its files too, so a camera
  ignores them once it runs one. (REPORTED in that project's notes. That the camera then ignores the file
  follows from the stamp compare, INFERRED.)
- `<sum>` is the unsigned 32-bit sum of every byte of the file, as 8 hex digits. A mismatch shows
  "Program error" and nothing is written.
- The updater looks only in the card's root, and only during the first 5 seconds after power-on. It erases
  64 KiB blocks from 0 up to the file length, then erases one more 64 KiB block at
  ((length rounded up to 256) + 0x10000) rounded down to 64 KiB, then programs and verifies 256-byte pages.
  For a 0x1E0000 file that extra block is 0x1F0000 (block 31, blank anyway, and block 30 is left alone). For a
  0x169000 file it's 0x170000, the settings bank. For a 0x170000 file it's 0x180000, the first photo slot.
  For a 2 MiB file it's 0x210000, beyond the chip: the address wraps to block 1 and the camera no longer
  boots (confirmed on hardware by the film-mods author). That's why the length matters, and `verify` names
  the block it would hit.
- The file isn't deleted afterwards. Take the card out before the next power-on, or it flashes again.

## Resource entries

The camera checks only the pack's `GP+` magic and nothing inside it, so `verify` checks the rest. An entry's
slot runs from its offset to the next entry's offset, and the last entry's slot runs to the factory
self-test sector at 0x16E000. Every slot must lie after the directory and inside the file, must not be erased
(all 0xFF), and must hold the kind its name says:

| Name ends in | Must hold |
|---|---|
| `.WAV` | `RIFF` ... `WAVE` with a `fmt ` and a `data` chunk; 8 + the RIFF size fits the slot |
| `.JPG` | starts FF D8 and has an FF D9 end marker inside the slot |
| `.GPZP`, `.GPZ` | `GPZP`, a raw DEFLATE stream that inflates, a trailer whose CRC-32 and length match, fixed-Huffman blocks only |
| `.PAK` | starts `GPRS` |

The two public dumps, the published stock restore file, the film-mods restore file and the three withdrawn
[flash candidates](03-FLASH-CANDIDATES.md) pass this check. (`verify` refuses the candidates for what they
are, not for their resources.) A cut-off download padded with 0xFF fails here even when the rest of the file
looks right. In an official-size file the last entry's slot ends at 0x169000 instead of 0x16E000.

## Resource sound slots

| Name | Offset | Format | Data bytes | Slot |
|---|---|---|---|---|
| BEEP.WAV | 0x121E00 | 11025 Hz, 16-bit | 4032 | 4096 |
| CLICK.WAV | 0x123200 | 8000 Hz, 8-bit | 1882 | 2048 |
| IMG.WAV (shutter) | 0x15FC00 | 11025 Hz, 8-bit | 5480 | 5632 |
| POWEROFF_AUDIO.WAV | 0x162400 | 8000 Hz, 8-bit | 8421 | 8704 |
| POWERON_AUDIO.WAV | 0x164600 | 8000 Hz, 8-bit | 6097 | 6144 |

A replacement must fit its slot, header included. `sounds replace` resamples and trims to make it fit, and
says what it cut. The event player in this build never requests BEEP.WAV.

## GPZP (compressed frames)

`GPZP` + raw DEFLATE (fixed Huffman) + trailer {u32 CRC32 of the inflated data, u32 inflated length} + 0xFF
padding to the next entry. The 800×600 frames inflate to three 240,000-byte strips in a row-pair 4:2:0
layout ([Y even row][U,V per pair][Y odd row]). Luma 0x8C in both rows of a pair is the see-through key.
Thumbnails (`PV00n`) are 112×80 RGB565 little-endian with 0x8C71 as the key colour.

The stock encoder isn't zlib: its blocks hold exactly 4,096 symbols. Python's
`zlib.compressobj(9, zlib.DEFLATED, -15, 8, zlib.Z_FIXED)` reproduces 23 of the 35 stock streams byte for
byte. The other 12 come out 1 to 3 bytes shorter and inflate to identical data. Every stock stream uses
fixed-Huffman blocks only. `frames replace` refuses any output that isn't fixed-Huffman, stored blocks
included, and `verify` fails any GPZP entry with a block that isn't fixed-Huffman, stored blocks included.

---

Reading order: 11 of 15 · previous: [Photo dates (EXIF)](10-EXIF.md) · next: [Boot and power notes](12-NOTES-boot-and-power.md) · [all docs](README.md)
