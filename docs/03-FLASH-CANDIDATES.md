# Flash candidates

Reading order: 3 of 13 · previous: [Recovery](02-RECOVERY.md) · next: [Putting the file on the card](04-STAGE.md) · [all docs](README.md)

**Withdrawn after flashes on one camera, and what they showed.** Candidate C was flashed on an original-series camera
(1987 blind-box design, bought in Japan in November 2026) on 2026-11-06. The update completed and the camera boots,
but it stays on the mode menu: photo and video mode freeze, the shutter clicks, then the camera locks up until it
switches itself off, with or without a card. The published stock restore file below was flashed next, and gave the
same result. C differs from that file only in the settings bank and the stored photos, and its program code is
identical, so **C's settings did not cause the freeze** (an earlier version of this page said they did). The likely
cause is that the public firmware does not fit this camera: it drives exactly one image sensor, writes it a fixed
table of 61 register settings, never reads which sensor is fitted, and carries on when the writes fail, so a camera
with another sensor boots, shows its menus and freezes when capture starts. That is an inference, about 70 percent at
the time of writing, not something measured on the camera; the alternative is a loose or damaged sensor ribbon. The
details are in [the incident notes](FIRST-FLASH-INCIDENT.md).

C, A and B stay withdrawn, for a different reason than before: they are made from the public dump, and no file made
from the public dump is known to fit a camera whose own firmware has not been read. Do not flash them; `verify` and
the Charmera Card app refuse them by their SHA-256. The same goes for the published stock restore file: it puts back
the public build on a camera that already runs it, and nothing else is known.

**Stock restore (the public build's own file).** `JH_5408202610261427_0F065D55.bin` from
https://github.com/benbadio/keychain-camera-plus (commit 209c25a, `release/stock/`), SHA-256
`b10a2f1c6302afbad8c205bd2f1e23c0ed244f6f3c553b513f2bd41f4b446906`. Several owners report flashing it and working
cameras afterwards; what their cameras ran before is not known. It is exactly the first 0x1E0000 bytes of the stock
dump below, so the Charmera Card app makes it from that dump and checks this hash. It carries that camera's settings
records and its two stored photos; the photo list in the file is empty, so a camera flashed with it does not show
them. Whether it fits your camera only a backup read before the camera was ever flashed can tell:
`charmera verify <file> --stock <that backup>` reporting the same sensor table and a byte-identical software region.
Read both lines. The sensor-table match is a WARN, because such a backup is then the public build itself, and
`stage` and the app take a different software region with a WARN rather than refusing it. A backup read after the
camera was flashed with this file matches it whether or not it fits
([Recovery, section D](02-RECOVERY.md#after-a-flash)).

Three update files built from the public original-series dump, reviewed statically and by an independent
pass. They are reproducible byte for byte from the recipe below, so they are not
committed; the recipe and the hashes stay here so that a copy found later can be recognised.

Input: `charmera_stock_latest.bin` from https://github.com/benbadio/keychain-camera-plus (commit 209c25a),
SHA-256 `cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f`. The Project-Sirius dump gives the same
software region; its settings bank and photos differ, so the hashes below are for the keychain dump.

Before any update file goes on a card: a verified full-flash backup of your own camera, made with `backup`, as in
[The first flash, step 1](01-FIRST-FLASH.md#1-back-up-your-cameras-chip-no-backup-no-flash). No backup, no flash. Then
an original 2025 series body (matte, yellow Kodak boot screen; a Millennium or CLOT body must never receive a file
made from this dump) and a fully charged battery.

All three erase the photos the camera stores in its own memory (up to two, taken with no card in), because
they are packed with `--blank-photos`; any file you pack with that option does the same. Only your own backup,
packed without `--blank-photos`, puts back the photos that were in the camera when you made it. If they matter
and you have no backup, get them off the camera before you flash.

| Candidate | What differs from stock | Update file | SHA-256 |
|---|---|---|---|
| A: settings-only mute | Settings bank holds one record: the factory record with UI sound 0 (CRC recomputed). Photo directory zeroed, photo slots erased. Code and resources byte-identical to stock. | `JH_5408202611050001_0FB74D14.bin` | `c4822cd7eed63169d80c5105811ab6492444793fafcc7a96e809869f2e5ccda7` |
| B: settings mute + silent WAVs | As A, plus the PCM of all five WAV resources replaced by silence (headers untouched). Survives a settings reset. | `JH_5408202611050002_0FB0BD3C.bin` | `32a7f54e5090050d099681c33378e3d23822a736279ea78f9f3442a45edc3979` |
| C: clean stock restore | Stock software, the factory settings record only, photo directory zeroed, photo slots erased. No other owner's settings or photos. | `JH_5408202611050003_0FB74C24.bin` | `9b8841d7b0674ca047a67b06594b92e67d17c387b6fab11f4b5191abc644c3ec` |

What the camera does with each (VERIFIED from the firmware's loader and updater). On hardware only C has run, on the
camera above on 2026-11-06: the update reached 100% and the camera booted to its menus, and then froze in photo and
video mode as described at the top. A and B were never flashed.

- The updater accepts the file (name sum matches), erases and programs blocks 0 to 29 ("Upgrade NN%" advances once per 64 KiB block), then erases block 31 (empty). At 100% the camera stays on its last screen; nothing reboots by itself, and the file is neither deleted nor renamed. Take the card out, then hold the power key about a second to switch the camera off.
- On the next boot the settings loader stops at sector 1 (erased) and loads sector 0; its CRC passes, and the camera runs with those values: UI sound 0 (A, B) or 5 (C), playback volume 5, auto-off code 2, language and date-format bytes as in the factory record. (Sector 120, the zeroed photo directory, is not erased either, but the loader never gets that far.) Two bytes differ from what the dumped camera was last running: +0x03 is 1 instead of 0 and +0x53 is 0 instead of 2; both values occur in other live records of the two dumps, so they are known-good states, and your own choices (frame/style selector) start from the factory state.
- The photo directory reads as two unused entries, so playback without a card shows nothing.
- A and B: the event-sound player returns before playing for every event (power on, shutter, keys, power off). B also has silent audio data if a later settings reset turns the gate back on.

Known limits:

- A settings-only mute is undone if the camera ever falls back to defaults: CRC failure, the stock bug after about 120 settings saves (a fresh bank like these has 119 free sectors before it; the published stock restore has 104), or the SDK's Sys.Reset menu item and network set-by-id paths, which this camera cannot reach. B does not have that weakness.
- The speaker amplifier is still switched on around the (now silent) sounds; whether that produces an audible click is unknown until tested.
- Recovery from any of these is your own backup, packed with `pack`. The stock restore file puts back the public
  build, which is a recovery only for a camera that ran that build.

## Recipe

```
python -m charmera_toolkit sounds mute charmera_stock_latest.bin --out A.bin --method settings --base-record first --i-understand-this-is-a-mod
python -m charmera_toolkit pack --input A.bin --out out/A --blank-photos --stamp 202611050001 --i-understand-this-file-flashes-a-camera

python -m charmera_toolkit sounds mute charmera_stock_latest.bin --out B.bin --method both --base-record first --i-understand-this-is-a-mod
python -m charmera_toolkit pack --input B.bin --out out/B --blank-photos --stamp 202611050002 --i-understand-this-file-flashes-a-camera

python -m charmera_toolkit pack --input charmera_stock_latest.bin --out out/C --factory-settings --blank-photos --stamp 202611050003 --i-understand-this-file-flashes-a-camera

python -m charmera_toolkit verify out/A/JH_5408202611050001_0FB74D14.bin --stock charmera_stock_latest.bin
python -m charmera_toolkit verify out/B/JH_5408202611050002_0FB0BD3C.bin --stock charmera_stock_latest.bin --allow-modified
python -m charmera_toolkit verify out/C/JH_5408202611050003_0FB74C24.bin --stock charmera_stock_latest.bin
```

Each of the three `verify` runs now gives a FAIL that names the candidate: `verify` knows the three files by their
SHA-256 and refuses them, as the app does, which is how a rebuilt or found copy is recognised. Its other lines still
show what each file is. B's verify needs `--allow-modified` because `verify` refuses any change to bytes 0..0x170000
(the code and the resource pack) unless it is told the change is intended, and B changes three resource blocks. A
and C leave that region byte-identical to stock and need no flag for it.

The stamps are fixed so the names reproduce. Any other stamp works too on a camera that runs either known build,
except `202505201029`, the stamp both carry (with `--stock`, `verify` also refuses the stamp found in your backup).
The stamp is only in the name: the file's bytes, and so its hash and the checksum part of the name, stay the same,
and `pack` writes the name.

## Byte-level diff against the stock dump (first 0x1E0000 bytes)

- A: block 0x170000 (7,681 bytes: byte +0x19 of record 0, its CRC, and records 1 to 15 erased to 0xFF; the second page of record 0 was already zero), blocks 0x180000 and 0x1A0000 (the two stored photos erased to 0xFF). The photo directory at 0x17F000 was already zero in this dump.
- B: as A plus blocks 0x120000, 0x150000, 0x160000 (only the PCM bytes inside the five WAVs; 5,182 + 686 + 10,459 bytes).
- C: block 0x170000 (records 1 to 15 erased), blocks 0x180000 and 0x1A0000 (photos erased).

## If you rebuild one of these from your own backup

The three files above are made from the public dump and are not to be flashed. The same recipe with your own backup
in place of `charmera_stock_latest.bin` makes the same change to your camera's own firmware: A's settings-only mute,
B's silent sounds, or C's factory settings. The hashes above then do not apply, and leaving out `--blank-photos`
keeps the photos stored in the camera. The full sequence, with the backup, staging and undo, is
[The first flash](01-FIRST-FLASH.md); this is the short form.

1. Required, before anything else: `backup` your camera and keep the file in two places
   ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)). No backup,
   no flash. It is the input of the recipe and the `--stock` below; the public dump is not a substitute for it.
2. `verify` the file against your backup (`--stock <backup>`, plus `--allow-modified` for a mod of the code or the
   resources such as B); read every line. `verify` refuses any change to bytes 0..0x170000 unless it is told the change
   is intended, and then shows it as a WARN. A sensor table that differs from your backup's, a file with no sensor
   table of the kind the known builds use, and a backup with none are each a FAIL in `verify` and in `stage` that no
   flag clears, `--allow-unknown-build` included, which covers only a file without all the build marks (with
   `--stock`, only one that still has a sensor table of the known kind) and, without `--stock`, a table that is not
   the known builds'. No recipe here changes the table, and the one deliberate way past such a FAIL is the
   donor route ([Recovery, section D](02-RECOVERY.md#the-donor-route)). The same table is a WARN when your backup is
   one of the known builds; with a backup of a build nobody has published, it is a PASS only when the program around
   it is your backup's too, and a WARN otherwise.
3. A card the camera has formatted itself (exFAT) or a FAT32 card; either works for the update ([SD card problems](05-CARDS.md)).
   Reformatting erases the card, so copy its photos off first if you do. Only the one file in the root (`stage --stock
   <backup>` puts it there: [Putting the file on the card](04-STAGE.md)); eject the card before you take it out of the
   reader.
4. Original 2025 series body only, never a Millennium or CLOT body; battery fully charged and the cable unplugged; the
   photos stored in the camera saved or given up (see above). Camera off, card in, power on. Wait for "Upgrade NN%" to
   reach 100%; the camera then stays on its last screen and does not reboot by itself. Take the card out, then hold the
   power key about a second to switch off. Delete the file from the card before it goes back into the camera.
5. Power on without the card and check the boot screen, the live view and a shutter press, and for a muted file
   whether the power-on sound is gone. A camera that boots and shows its menus but freezes when photo mode starts
   received firmware that does not fit it.
6. To revert: `pack` your own backup and flash that.

---

Reading order: 3 of 13 · previous: [Recovery](02-RECOVERY.md) · next: [Putting the file on the card](04-STAGE.md) · [all docs](README.md)
