# Flash candidates

Reading order: 3 of 15 · previous: [Recovery](02-RECOVERY.md) · next: [Putting the file on the card](04-STAGE.md) · [all docs](README.md)

**The three candidates on this page are withdrawn. Don't flash them.**

Candidate C was flashed on an original-series camera (1987 blind-box design, bought in Japan in November 2026) on
2026-11-06. The update completed and the camera boots, but it stays on the mode menu. Photo and video mode freeze, the
shutter clicks, and then the camera locks up until it switches itself off, with or without a card. The published stock
restore file below was flashed next and gave the same result. C differs from that file only in the settings bank and
the stored photos, and its program code is identical, so C's settings did not cause the freeze. (An earlier version of
this page said they did.)

The likely cause is that the public firmware does not fit this camera. It drives exactly one image sensor, writes it a
fixed table of 61 register settings, never reads which sensor is fitted, and carries on when the writes fail. So a
camera with another sensor boots, shows its menus and freezes when capture starts. That's an inference (about 70
percent at the time of writing), not something measured on the camera. The other possibility is a loose or damaged
sensor ribbon. The details are in [the incident notes](FIRST-FLASH-INCIDENT.md).

C, A and B stay withdrawn, but the reason has changed. They're made from the public dump, and no file made from the
public dump is known to fit a camera whose own firmware hasn't been read. `verify` and the Charmera Card app refuse A
and B by their SHA-256. They name C but don't refuse it, because C's bytes are also the stock restore file made from
the public dump's cleaned copy (below), and the stock restore file isn't refused. Treat the stock restore file with the
same caution: all it's known to do is put the public build back on a camera that already runs it.

## The stock restore file

`JH_5408202610261427_0F065D55.bin` from https://github.com/benbadio/keychain-camera-plus (commit 209c25a,
`release/stock/`), SHA-256 `b10a2f1c6302afbad8c205bd2f1e23c0ed244f6f3c553b513f2bd41f4b446906`, is the public build's
own restore file. It's exactly the first 0x1E0000 bytes of the stock dump below, so the Charmera Card app makes it from
that dump and checks this hash. Made from the original dump, it carries that camera's settings records and its two
stored photos. The photo list in the file is empty, so a camera flashed with it doesn't show them.

Made from the public dump's cleaned copy instead,
[the-dumping-grounds/charmera-firmware-v006](https://github.com/the-dumping-grounds/charmera-firmware-v006)
(`charmera-v006-2025-10-14-clean.bin`, SHA-256 `75b911e9fc62cb3e4b461501916d1f1c336a28587296f03eba1f9d90cbc01082`,
the one the app now downloads), the same first 0x1E0000 bytes are candidate C byte for byte (SHA-256 `9b8841d7…`):
the same software, the factory settings record and no stored photos. From the cleaned copy the app checks
`9b8841d7…`.

Several owners report flashing it and having working cameras afterwards. What their cameras ran before isn't known.

Only a backup read before the camera was ever flashed can tell whether it fits yours. Run
`charmera verify <file> --stock <that backup>` and look for the same sensor table and a byte-identical software
region. Read both lines yourself, because neither result blocks anything. The sensor-table match is only a WARN, since
such a backup is then the public build itself, and `stage` and the app accept a different software region with a WARN
instead of refusing it. A backup read after the camera was flashed with this file matches it whether or not it fits
([Recovery, section D](02-RECOVERY.md#after-a-flash)).

## The three candidates

These are three update files built from the public original-series dump, reviewed statically and by an independent
pass. They're reproducible byte for byte from the recipe below, so they aren't committed. The recipe and the hashes
stay here so a copy found later can be recognised.

The input is `charmera_stock_latest.bin` from https://github.com/benbadio/keychain-camera-plus (commit 209c25a),
SHA-256 `cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f`. The Project-Sirius dump and the cleaned
copy in [the-dumping-grounds/charmera-firmware-v006](https://github.com/the-dumping-grounds/charmera-firmware-v006)
give the same software region. Every candidate rewrites the settings bank to the factory record and blanks the photos,
so C, A and B come out byte-identical from the keychain dump and from the cleaned copy. From the Project-Sirius dump the
hashes below may differ.

Before any update file goes on a card, know what your camera runs. That comes from a verified full-flash backup made
with `backup`, as in [The first flash, step 1](01-FIRST-FLASH.md#1-know-what-your-camera-runs-identify-or-a-backup-of-its-chip), or
from `identify`. The backup is also the only way back to exactly what the camera ran. You also need an original 2025
series body (matte, yellow Kodak boot screen) and a fully charged battery. A Millennium or CLOT body must never
receive a file made from this dump.

All three erase the photos the camera stores in its own memory (up to two, taken with no card in), because they're
packed with `--blank-photos`. Any file you pack with that option does the same. Only your own backup, packed without
`--blank-photos`, puts back the photos that were in the camera when you made it. If they matter and you have no
backup, get them off the camera before you flash.

| Candidate | What differs from stock | Update file | SHA-256 |
|---|---|---|---|
| A: settings-only mute | Settings bank holds one record: the factory record with UI sound 0 (CRC recomputed). Photo directory zeroed, photo slots erased. Code and resources byte-identical to stock. | `JH_5408202611050001_0FB74D14.bin` | `c4822cd7eed63169d80c5105811ab6492444793fafcc7a96e809869f2e5ccda7` |
| B: settings mute + silent WAVs | As A, plus the PCM of all five WAV resources replaced by silence (headers untouched). Survives a settings reset. | `JH_5408202611050002_0FB0BD3C.bin` | `32a7f54e5090050d099681c33378e3d23822a736279ea78f9f3442a45edc3979` |
| C: clean stock restore | Stock software, the factory settings record only, photo directory zeroed, photo slots erased. No other owner's settings or photos. | `JH_5408202611050003_0FB74C24.bin` | `9b8841d7b0674ca047a67b06594b92e67d17c387b6fab11f4b5191abc644c3ec` |

What the camera does with each file is VERIFIED from the firmware's loader and updater. Only C has run on hardware, on
the camera above on 2026-11-06. The update reached 100% and the camera booted to its menus, then froze in photo and
video mode as described at the top. A and B were never flashed.

- The updater accepts the file (the name sum matches), erases and programs blocks 0 to 29 ("Upgrade NN%" advances
  once per 64 KiB block), then erases block 31 (empty). At 100% the camera stays on its last screen. Nothing reboots
  by itself, and the file is neither deleted nor renamed. Take the card out, then hold the power key about a second
  to switch the camera off.
- On the next boot the settings loader stops at sector 1 (erased) and loads sector 0. Its CRC passes, and the camera
  runs with those values: UI sound 0 (A, B) or 5 (C), playback volume 5, auto-off code 2, and the language and
  date-format bytes as in the factory record. (Sector 120, the zeroed photo directory, isn't erased either, but the
  loader never gets that far.)

  Two bytes differ from what the dumped camera was last running: +0x03 is 1 instead of 0, and +0x53 is 0 instead of
  2. Both values occur in other live records of the two dumps, so they're known-good states. Your own choices (the
  frame/style selector) start from the factory state.
- The photo directory reads as two unused entries, so playback without a card shows nothing.
- In A and B, the event-sound player returns before playing for every event (power on, shutter, keys, power off). B
  also has silent audio data in case a later settings reset turns the gate back on.

Known limits:

- A settings-only mute is undone if the camera ever falls back to defaults. B doesn't have that weakness. The camera
  falls back to defaults on:
  - a CRC failure
  - the bank's wrap-around after its 127th sector, which erases the photo directory with it
  - a photo-directory update near the end of the bank erasing the current record (see [Format](11-FORMAT.md),
    inferred from the code, not tested)
  - the SDK's Sys.Reset menu item and network set-by-id paths, which this camera can't reach
- The speaker amplifier is still switched on around the (now silent) sounds. Whether that makes an audible click is
  unknown until someone tests it.
- To recover from any of these, flash your own backup, packed with `pack`. The stock restore file puts back the public
  build, which only counts as a recovery for a camera that ran that build.

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

The `verify` runs for A and B now give a FAIL that names the candidate. `verify` knows the files by their SHA-256 and
refuses them, as the app does, and that's how a rebuilt or found copy is recognised. C's run names it in an INFO line
instead, as the stock restore file made from the cleaned copy. The other lines still show what each file is. B's
`verify` needs `--allow-modified` because `verify` refuses any change to bytes 0..0x170000 (the code and the resource
pack) unless it is told the change is intended, and B changes three resource blocks. A and C leave that region
byte-identical to stock and don't need the flag.

The stamps are fixed so the names reproduce. Any other stamp works too on a camera that runs any known build,
except `202505201029`, the stamp every known build carries. (With `--stock`, `verify` also refuses the stamp found in your
backup.) The stamp is only in the name. The file's bytes, and so its hash and the checksum part of the name, stay the
same, and `pack` writes the name.

## Byte-level diff against the stock dump (first 0x1E0000 bytes)

- A: block 0x170000 (7,681 bytes: byte +0x19 of record 0, its CRC, and records 1 to 15 erased to 0xFF; the second page of record 0 was already zero), blocks 0x180000 and 0x1A0000 (the two stored photos erased to 0xFF). The photo directory at 0x17F000 was already zero in this dump.
- B: as A plus blocks 0x120000, 0x150000, 0x160000 (only the PCM bytes inside the five WAVs; 5,182 + 686 + 10,459 bytes).
- C: block 0x170000 (records 1 to 15 erased), blocks 0x180000 and 0x1A0000 (photos erased).

## If you rebuild one of these from your own backup

The three files above are made from the public dump and aren't to be flashed. Run the same recipe with your own backup
in place of `charmera_stock_latest.bin` and it makes the same change to your camera's own firmware: A's settings-only
mute, B's silent sounds, or C's factory settings. The hashes above then don't apply, and leaving out `--blank-photos`
keeps the photos stored in the camera. [The first flash](01-FIRST-FLASH.md) has the full sequence, with the backup,
staging and undo. This is the short form.

1. `backup` your camera first and keep the file in two places
   ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)). You
   can't skip this one. The backup is the recipe's input and the `--stock` below, and the public dump can't stand in
   for it. It's also how you know what your camera runs before you flash.
2. `verify` the file against your backup (`--stock <backup>`, plus `--allow-modified` for a mod of the code or the
   resources, such as B) and read every line. `verify` refuses any change to bytes 0..0x170000 unless it is told the
   change is intended, and then shows it as a WARN.

   Three sensor-table results are a FAIL in `verify` and in `stage` that no flag clears: a sensor table that differs
   from your backup's, a file with no sensor table of the kind the known builds use, and a backup with none.
   `--allow-unknown-build` doesn't clear them either. It covers only a file without all the build marks (with
   `--stock`, only one that still has a sensor table of the known kind) and, without `--stock`, a table that isn't the
   known builds'. No recipe here changes the table. The one deliberate way past such a FAIL is the donor route
   ([Recovery, section D](02-RECOVERY.md#the-donor-route)).

   A matching table is a WARN when your backup is one of the known builds. With a backup of a build nobody has
   published, it's a PASS only when the program around it is your backup's too, and a WARN otherwise.
3. Use a card the camera has formatted itself (exFAT) or a FAT32 card. Either works for the update
   ([SD card problems](05-CARDS.md)). Reformatting erases the card, so copy its photos off first if you do. Put only
   the one file in the root; `stage --stock <backup>` puts it there ([Putting the file on the card](04-STAGE.md)).
   Eject the card before you take it out of the reader.
4. Use an original 2025 series body only, never a Millennium or CLOT body. Charge the battery fully and unplug the
   cable. Save the photos stored in the camera first, or accept losing them (see above). With the camera off, put the
   card in and power on. Wait for "Upgrade NN%" to reach 100%. The camera then stays on its last screen and doesn't
   reboot by itself. Take the card out, then hold the power key about a second to switch off. Delete the file from the
   card before it goes back into the camera.
5. Power on without the card and check the boot screen, the live view and a shutter press. For a muted file, check
   that the power-on sound is gone. A camera that boots and shows its menus but freezes when photo mode starts
   received firmware that doesn't fit it.
6. To revert, `pack` your own backup and flash that.

---

Reading order: 3 of 15 · previous: [Recovery](02-RECOVERY.md) · next: [Putting the file on the card](04-STAGE.md) · [all docs](README.md)
