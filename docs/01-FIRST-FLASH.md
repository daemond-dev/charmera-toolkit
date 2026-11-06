# The first flash, end to end

Reading order: 1 of 13 · next: [Recovery](02-RECOVERY.md) · [all docs](README.md)

The order of operations for putting a modified firmware on a Charmera for the first time, with the point of
no return marked. Every step links to a longer document; read those before the step, not during it. The
[list of all docs](README.md) has them in reading order.

Only a phone? The [Charmera Card app](../android/README.md) does steps 2 to 7 on the phone, making candidate C,
A or B from the public stock dump itself; read steps 0 and 1 here first.

Not flashing anything? If you only want the photo and video dates fixed, go to
[Photo dates (EXIF)](10-EXIF.md). If your phone will not read the camera's card, go to
[SD card problems](05-CARDS.md).

Only the original 2025 series is covered (matte body, yellow Kodak boot screen). A Millennium Edition or a CLOT
edition must not be flashed with any file from this toolkit: see [Recovery, section D](02-RECOVERY.md#d-wrong-edition).

## 0. Decide what you can afford to lose

Two ways to get a camera back after a bad flash exist, both described in [Recovery](02-RECOVERY.md). The SD
updater ([section A](02-RECOVERY.md#a-the-camera-boots-and-the-updater-runs-restore-through-the-sd-card)) needs a
camera that still boots far enough to read the card. The CH341A programmer ([section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly))
needs the camera opened and a clip on the flash chip, and it works whatever the firmware does. Nobody has yet published a recovery from a camera that does not boot
without opening it.

So the question is: are you willing to open the camera if the first try goes wrong? If not, stop at step 2
and keep the stock files; the risk of the mods below is small but it is not zero, and the first person to
try each of them is you.

## 1. Back up the stock firmware (recommended) or accept the public stock image

With a CH341A clip ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)):

```
charmera probe
charmera backup --out backups/
charmera inspect backups/<file>.bin
```

`backup` reads the chip twice and keeps the file only when the reads agree. `inspect` must report
`series: original-2025` and `check software_sha256 yes`. That file is your way back.

Without a clip, the published stock dump (hash in [Research report](13-PHASE1-REPORT.md), reproduced by `pack` from the
published restore file) is identical to every original-series camera read so far in the first 0x170000 bytes.
Your settings and the photos the camera stores in its own memory are the only things that differ. The risk
you accept is that your camera is the first one whose firmware differs. Flashing any of the candidates below
resets your settings and erases those stored photos, and without a backup they cannot be put back (see the
warning in step 5).

## 2. Pick the file

[Flash candidates](03-FLASH-CANDIDATES.md) lists the three reviewed files, in the order to try them:

- C: stock, settings reset to factory, photos blanked. Changes nothing in the code or resources. Try this
  first if you only want to know that the updater path works on your camera.
- A: C plus UI sounds set to 0 in the settings record. Fully reversible by flashing C.
- B: A plus silenced sound data in the resource pack, for the case where the settings are ever reset.

All three erase the two photos the camera stores in its own memory.

Build your own instead, then `pack` it. Each of these commands prints what it will change and refuses to write
until you pass its gate flag:

- `sounds` and `settings`: mute or replace the sounds, change auto-off and standby ([Command reference](07-USAGE.md)).
- `frames`: your own photo frames ([Photo frames](08-FRAMES.md)).
- `resources`: a custom boot logo ([Resource pack and boot logo](09-RESOURCES.md)).
- `tweak`: experimental faster start-up and power-off. Section 6 of the
  [Boot and power notes](12-NOTES-boot-and-power.md) says what each patch changes and why it is a guess.

## 3. Verify

```
charmera verify out/JH_5408<stamp>_<sum>.bin --stock <your backup or the stock dump>
charmera verify out/JH_5408<stamp>_<sum>.bin --stock <your backup or the stock dump> --allow-modified   # a mod of code or resources
charmera diff <your backup> out/JH_5408<stamp>_<sum>.bin
```

`verify` fails closed. A FAIL line means the file does not go on a card. It refuses any change to bytes
0..0x170000 (the code and the resource pack) unless you tell it the change is intended, which is what
`--allow-modified` does. Candidates A and C, and files from `settings` or `sounds mute --method settings`,
leave that region as it is and pass without the flag. Candidate B and anything built with `sounds mute
--method pcm` or `both`, `sounds replace`, `frames`, `resources` or `tweak` need it. With the flag, the
`software-region` line lists the 64 KiB blocks that differ from your `--stock` file; they must be only the
ones your mod was meant to change (for B: 0x120000, 0x150000 and 0x160000). `diff` shows every region that
differs from your backup, so you can confirm the change is the one you intended and nothing else moved.
What the regions are and which rules `verify` enforces: [Image layout](11-FORMAT.md), especially
[the update file rules](11-FORMAT.md#update-file-jh_5408stamp_sumbin).

## 4. Put it on the card

`stage` copies the file to the card root, after running `verify` again, refusing a card that already holds
another update file, and reading the copy back to compare hashes. It asks you to type the card path before
it writes. [Putting the file on the card](04-STAGE.md) explains each refusal. From a phone instead of a
computer, use the [Charmera Card app](../android/README.md) (on Android 11 and later through a USB card reader
or the camera's USB-C cable, not the phone's own SD slot); [Using an Android phone](06-ANDROID.md) covers the rest.

```
charmera stage out/JH_5408<stamp>_<sum>.bin --card E:          # Windows
charmera stage out/JH_5408<stamp>_<sum>.bin --card /run/media/deck/<label>   # Steam Deck
```

Use a card the camera has formatted itself, or a FAT32 card ([SD card problems](05-CARDS.md)). When `stage`
reports the read-back matched, eject the card (Windows: Safely Remove Hardware or Explorer's Eject; Linux:
`udisksctl unmount` or the file manager's eject), then take it out of the reader.

## 5. Flash (the point of no return)

Before you start:

- Original 2025 series only: matte body, yellow Kodak boot screen. Never a Millennium Edition (blue boot
  screen) or a CLOT edition ([Recovery, section D](02-RECOVERY.md#d-wrong-edition)). No check on the file can
  tell which body it goes into; only you can.
- Charge the battery fully, then unplug the cable. Programming takes about 15 to 20 seconds, and a power loss
  in that window leaves a camera that only a chip programmer can bring back
  ([Recovery, section C](02-RECOVERY.md#c-an-interrupted-flash)).
- The photos the camera stores in its own memory (up to two, taken with no card in) are replaced. The
  candidates, and any file packed with `--blank-photos`, erase them; a file packed from someone else's dump
  replaces them with that owner's. Only a file packed from your own backup without `--blank-photos` puts back
  the photos that were in the camera when you made that backup. If they matter and you have no backup, get
  them off the camera first: the card-side `SPIDCIM` folder is where the camera copies them
  ([Research report, section 2](13-PHASE1-REPORT.md#2-firmware-container-format)), so check that folder on a
  computer before you flash.

Then:

1. Camera off. Insert the card.
2. Hold the power key to switch on. Within its first five seconds the camera finds the file and shows
   "Upgrade NN%", which advances once per 64 KiB block, about 15 to 20 seconds in all. Do not touch the
   key or the card while it counts.
3. At 100% the camera stays on its last screen. Nothing reboots by itself, and the camera never deletes or
   renames the file. Take the card out, then hold the power key about a second until the camera switches off.
4. If the screen shows "Program error" instead, the sum in the name did not match the file and nothing was
   erased; the camera switches itself off. Take the card out and re-run `verify`.
5. Delete the update file from the card (on a computer or the phone, ejecting the card before you take it
   out) before that card goes back into the camera. Left there, it flashes again at every power-on.

## 6. Check

Power on without a card: does the boot screen appear, does the live view come up, does a shutter press
take a picture? With a card: does a photo save and play back? For the mute candidates: no sound at power-on,
shutter or power-off. Note anything that differs from the stock behaviour, because nobody has run these
files on hardware before you.

## 7. Undo

Flash C the same way to return to stock behaviour with factory settings, or `pack` your own backup
(`--factory-settings --blank-photos` optional) to return to exactly what you had. If the camera does not
boot far enough to read the card: [Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly).

## Background

You do not need these to flash, but they are where every claim above comes from:
[Image layout](11-FORMAT.md) for what is where in the flash,
[Boot and power notes](12-NOTES-boot-and-power.md) for the start-up and power-off analysis, and the
[Research report](13-PHASE1-REPORT.md) for the sources, with each finding labelled verified, reported or inferred.

---

Reading order: 1 of 13 · next: [Recovery](02-RECOVERY.md) · [all docs](README.md)
