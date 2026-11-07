# The first flash, end to end

Reading order: 1 of 13 · next: [Recovery](02-RECOVERY.md) · [all docs](README.md)

The order of operations for putting a modified firmware on a Charmera for the first time, with the point of
no return marked. Every step links to a longer document; read those before the step, not during it. The
[list of all docs](README.md) has them in reading order.

Only a phone? The [Charmera Card app](../android/README.md) does steps 4 to 7 on the phone, and makes the published stock
restore file itself. It still needs the backup from step 1: without a dump of your own camera's chip it does not write
to the card. That is version 0.5; 0.4, published before it, had no backup step and did not refuse the withdrawn
candidates, so if you still have it installed, do not flash with it ([how to tell the two
apart](../android/README.md#install)). Read steps 0 and 1 here first.

Not flashing anything? If you only want the photo and video dates fixed, go to
[Photo dates (EXIF)](10-EXIF.md). If your phone will not read the camera's card, go to
[SD card problems](05-CARDS.md).

Only the original 2025 series is covered (matte body, yellow Kodak boot screen). A Millennium Edition or a CLOT
edition must not be flashed with any file made from the public dump, and neither must an original-series camera
whose own firmware has not been read first: see [Recovery, section D](02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera).

## 0. Decide what you can afford to lose

Two ways to get a camera back after a bad flash exist, both described in [Recovery](02-RECOVERY.md). The SD
updater ([section A](02-RECOVERY.md#a-the-camera-boots-and-the-updater-runs-restore-through-the-sd-card)) needs a
camera that still boots far enough to read the card. The CH341A programmer ([section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly))
needs the camera opened and a clip on the flash chip, and it works whatever the firmware does. Nobody has yet published a recovery from a camera that does not boot
without opening it.

So the question is: are you willing to open the camera? Step 1 needs it opened anyway, for the backup. If not,
stop here and keep the camera as it is: a flash that does not fit the camera leaves it unable to take pictures, and
without a backup of its own chip there is no way back from that. One original-series camera, bought in Japan in
November 2026, was left that way by the public dump on 2026-11-06 ([what happened](FIRST-FLASH-INCIDENT.md)).

## 1. Back up your camera's chip (no backup, no flash)

This is a hard stop, not a recommendation. Nothing below goes on a card until a verified dump of this camera's own
flash chip exists. The app does not write to a card without one. `stage` does not refuse on its own: without `--stock`
it shows a WARN line saying that no backup of this camera was given, so nothing compares the file with its own
firmware and a file that does not fit cannot be undone, says so again in its plan, and then still writes a file that
passes, because the owner decided on 2026-11-06 that it should warn rather than refuse. On a computer the stop is
yours to keep: give `--stock <your backup>` to every `verify` and every `stage`.

Why: whether a firmware fits a camera cannot be told from outside. The public firmware drives exactly one image
sensor, writes it a fixed table of 61 register settings, never reads which sensor is fitted, and carries on when the
writes fail. A camera fitted with another sensor boots, shows its menus and plays back photos with that firmware, and
freezes the moment photo or video mode starts. Neither the body, nor the yellow boot screen, nor the version stamp
tells the builds apart: two different builds carry the same stamp `202505201029`
([Research report](13-PHASE1-REPORT.md), [Image layout](11-FORMAT.md#build-identity)). The one file known to fit your
camera is the one read from it before it was ever flashed.

With a CH341A clip ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)):

```
charmera probe
charmera backup --out backups/
charmera inspect backups/<file>.bin
```

`probe` and `backup` first print a checklist: a 3.3 V programmer (never an unmodified black CH341A), the battery lead
lifted, pin 1 of the clip on the chip's dot. `backup` reads the chip twice and keeps the file only when the reads
agree. `inspect` must find the build marks (the build string, the stamp and the sensor table) and say which build the
file is: `public-dump` means your camera runs the same software as the public dump, byte for byte, and `film-mods`
that it runs the build of the charmera-film-mods author's camera; `public-dump-modified` or `film-mods-modified`
means that build with changes, such as a mod made from it ([Image layout](11-FORMAT.md#build-identity) says what
counts as one); `charmera-unknown-build` means it runs a build nobody has published, which is exactly the case the
backup is for. Keep the file in two places. It is your way back, and it is the `--stock` every later `verify` and
`stage` takes.

### After a flash

A read of a camera that has been flashed before (by you, a seller or an earlier owner) shows the firmware it runs
now, not the one it shipped with. It is still the way back to that state, and still what the rule asks for before
the next flash, but it says nothing about which firmware fits: compared with it, a file is compared with the firmware
that was flashed. A camera flashed with the public restore file reads as the public build (`inspect` says
`public-dump`), and the stock restore file then matches that read whether or not it fits the camera. So when your
backup is itself one of the known builds, `verify`, `stage` and the app show a matching sensor table as a WARN, not a
PASS: the match proves a fit only if the backup was read before the camera was ever flashed, which nothing in the
file can tell. A camera that cannot take pictures with the firmware it runs now needs another, working camera's
firmware: the donor route in [Recovery, section D](02-RECOVERY.md#the-donor-route).

### Without a clip

Do not flash. The public dump is a copy of one camera; it is not known to fit yours, and a flash with it cannot be
undone without your own backup. A camera that already lost its original firmware this way can still be flashed
through the card, but only a dump from a working camera with the same sensor can fix it (the donor route above), and
reading its current chip first is still what the rule asks. Choosing a donor can start without a clip: `photo-id`
reads the header of a would-be donor's photos and of the ones this camera took before its flash, without opening
either camera. A different header written by the cameras' own firmware shows a different build. A photo that another
program saved again says nothing about the camera: `photo-id` calls a photo re-saved only when its header carries a
mark an editor leaves, such as an XMP block, and any other header neither known build writes may be such a copy or
another build's, with nothing in it to tell which. So use the photos as they came off each camera's card
([Recovery, section D](02-RECOVERY.md#the-donor-route)).

## 2. Pick the file

Build the file from your own backup: `pack` turns the backup into an update file that puts back exactly what the
camera runs now (settings and stored photos included), or with `--official-size` a 0x169000-byte file that writes
the software only and leaves the camera's own area to a factory reset. 0x169000 bytes is the size of the film-mods
files, and where an official update would end by that project author's reading of the updater, who also reports
flashing such a file on their own camera (REPORTED); no official update file has been published or examined here, and
none of this size has been flashed by this project ([Image layout](11-FORMAT.md#update-file-jh_5408stamp_sumbin)).
A mod starts from that backup too. Each of these commands prints what it will change and refuses to write
until you pass its gate flag:

- `sounds` and `settings`: mute or replace the sounds, change auto-off and standby ([Command reference](07-USAGE.md)).
- `frames`: your own photo frames ([Photo frames](08-FRAMES.md)).
- `resources`: a custom boot logo ([Resource pack and boot logo](09-RESOURCES.md)).
- `tweak`: experimental faster start-up and power-off. Section 6 of the
  [Boot and power notes](12-NOTES-boot-and-power.md) says what each patch changes and why it is a guess.

The three candidates C, A and B in [Flash candidates](03-FLASH-CANDIDATES.md) are withdrawn; `verify` and the app
refuse them by their SHA-256. The published stock restore file on the same page fits only a camera whose own firmware
is the public build, and only a backup read before the camera was ever flashed can show that it is yours: with that
backup as `--stock`, the `software-region` line says byte-identical and the `sensor-table` line says the same table
(as a WARN, because the backup is then the public build itself; see [After a flash](#after-a-flash)). Read both lines
yourself: `stage` and the app refuse a different sensor table, but they take a different program with a WARN. If both
say the same, that file and a `pack` of your backup are the same software, and the `pack` also puts back your own
settings and photos.

## 3. Verify

```
charmera verify out/JH_5408<stamp>_<sum>.bin --stock <your backup>
charmera verify out/JH_5408<stamp>_<sum>.bin --stock <your backup> --allow-modified   # a mod of code or resources
charmera diff <your backup> out/JH_5408<stamp>_<sum>.bin
```

Always give `--stock <your backup>`: it is the one comparison that says something about your camera, and it says
what fits only for a backup read before the camera was ever flashed. It must be a complete read of a chip, exactly
2 MiB, starting with `SPII`, with the build string and the stamp, as `backup` writes it; anything else (an update
file, a cut-off read) is a FAIL. A `--stock` that is byte for byte a public dump is a WARN: it may be this camera's
own read after a flash with the public restore file, and then it shows what the camera runs, not what fits it.

`verify` fails closed. A FAIL line means the file does not go on a card. The `sensor-table` line compares the file's
image-sensor set-up table with your backup's, found by content in both. A table that differs from your backup's is a
FAIL that no flag clears, `--allow-unknown-build` included, because that difference is what leaves a camera unable to
take pictures; so are a file with no sensor table of the kind the known builds use and a backup with none, since
either leaves nothing to compare; the one deliberate way past them is the donor route
([Recovery, section D](02-RECOVERY.md#the-donor-route)). `--allow-unknown-build` covers only a file without all the
build marks (with `--stock`, only one that still has a sensor table of the known kind) and, without `--stock`, a
table that is not the known builds'. A match is a WARN when your backup is one of the known builds
([After a flash](#after-a-flash)). When your backup is a build nobody has published, a match is a PASS only when the
program around the table (bytes 0..0x120000, the code and its data) is your backup's too, and a WARN that says the
program differs otherwise: a different program can drive the same sensor differently. The `build` line names the
known build a file was made from, unchanged or "with changes", or says the build is unknown, and adds what a build
match proves: which public image the file came from, not whether it fits your camera. A file is a known build with
changes only when its build marks lie where that build keeps them and it carries that build's stamp, sensor table,
source-tree folder and compile date, as a mod of a backup of that build does; with any of the four different, it is a
build the toolkit does not know, "laid out like" that one ([Image layout](11-FORMAT.md#build-identity)). The stamp in
the file's name must differ from the one in your backup (`stamp-camera`), or the camera would ignore the file.

`verify` refuses any change to bytes 0..0x170000 (the code and the resource pack) unless you tell it the change is
intended, which is what `--allow-modified` does; the `software-region` line is then a WARN, never a PASS. No flag
accepts a change to the ARM vector table at 0x200..0x240, where the camera starts: a file laid out like your backup
must carry it byte for byte, because with a broken one the camera may not start far enough to run the card updater
again. A file laid out otherwise is compared with the known build whose layout it has, and a mismatch there is a
FAIL too; only a file laid out like no known build, whose table is not your backup's, gets a WARN that says the table
could not be compared. Settings-only files and files from `settings` or `sounds mute --method settings` leave the
software region as it is and pass without `--allow-modified`. Anything built with `sounds mute --method pcm` or
`both`, `sounds replace`, `frames`, `resources` or `tweak` needs it. With the flag, the `software-region` line lists
the 64 KiB blocks that differ from your `--stock` file; they must be only the ones your mod was meant to change (for a
PCM mute: 0x120000, 0x150000 and 0x160000). `diff` shows every region that differs from your backup, so you can
confirm the change is the one you intended and nothing else moved. What the regions are and which rules `verify`
enforces: [Image layout](11-FORMAT.md), especially
[the update file rules](11-FORMAT.md#update-file-jh_5408stamp_sumbin).

## 4. Put it on the card

`stage` copies the file to the card root, after running `verify` again with the same `--stock`, refusing a card
that already holds another update file, and reading the copy back to compare hashes. It asks you to type the card
path before it writes. Without `--stock` it warns that no backup of this camera was given and still writes (step 1),
so give it. [Putting the file on the card](04-STAGE.md) explains each refusal. From a phone instead of a
computer, use the [Charmera Card app](../android/README.md) (on Android 11 and later through a USB card reader
or the camera's USB-C cable, not the phone's own SD slot); [Using an Android phone](06-ANDROID.md) covers the rest.

```
charmera stage out/JH_5408<stamp>_<sum>.bin --card E: --stock <your backup>          # Windows
charmera stage out/JH_5408<stamp>_<sum>.bin --card /run/media/deck/<label> --stock <your backup>   # Steam Deck
```

Use a card the camera has formatted itself, or a FAT32 card ([SD card problems](05-CARDS.md)). When `stage`
reports the read-back matched, eject the card (Windows: Safely Remove Hardware or Explorer's Eject; Linux:
`udisksctl unmount` or the file manager's eject), then take it out of the reader.

The camera can be the card reader too: connected by its USB-C cable it shows its card as a USB drive (seen on a
phone; on a computer it should appear as a removable drive, which this project has not tried). Plugging the cable
into a switched-off camera switches it on and runs the updater from the card, so connect it the way the app does,
every time: switch the camera off if it is on, take the card out, switch the camera on with its power key, wait 10
seconds (it looks for an update file only in the first five seconds after power-on), put the card back in, then plug
in the cable. Eject the drive before you unplug the cable.

## 5. Flash (the point of no return)

Before you start:

- Your backup from step 1 exists, passed `inspect`, and is kept in two places. No backup, no flash.
- Original 2025 series only: matte body, yellow Kodak boot screen. Never a Millennium Edition (blue boot
  screen) or a CLOT edition ([Recovery, section D](02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera)). The body
  alone proves nothing, as the incident showed; the comparison in step 3 with a backup read before the camera was ever
  flashed is what does.
- Charge the battery fully, then unplug the cable. Programming takes about 15 to 20 seconds, and a power loss
  in that window leaves a camera that only a chip programmer can bring back
  ([Recovery, section C](02-RECOVERY.md#c-an-interrupted-flash)).
- The photos the camera stores in its own memory (up to two, taken with no card in) are replaced by a 0x1E0000-byte
  file. The candidates, and any file packed with `--blank-photos`, erase them; a file packed from someone else's
  dump replaces them with that owner's. Only a file packed from your own backup without `--blank-photos` puts back
  the photos that were in the camera when you made that backup. A 0x169000-byte file (`--official-size`) leaves the
  photo data in place, but the camera empties its photo list, so it no longer shows them and the next photo it
  stores overwrites them. If they matter and you have no backup, get them off the camera first: the card-side
  `SPIDCIM` folder is where the camera copies them
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
   out) before that card goes back into the camera. Left there, it flashes again at every power-on. To delete it
   through the camera's own cable, connect the camera as in [step 4](#4-put-it-on-the-card): card out, switch on with
   the power key, wait 10 seconds, card in, then the cable. A cable plugged into the switched-off camera with the file
   on its card starts that flash again.

## 6. Check

Power on without a card: does the boot screen appear, does the live view come up, does a shutter press
take a picture? With a card: does a photo save and play back? For a muted file: no sound at power-on,
shutter or power-off. A camera that boots and shows its menus but freezes when photo or video mode starts has
received firmware that does not fit it: the way back is step 7 with your backup (if that backup was itself read after
an earlier flash, it holds that flash's firmware: see [After a flash](#after-a-flash)). Note anything that differs
from the stock behaviour, because few of these files have run on hardware before you.

## 7. Undo

`pack` your own backup (`--factory-settings --blank-photos` optional) and flash it the same way to return to
exactly what you had. If the camera does not boot far enough to read the card:
[Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly).

## Background

You do not need these to flash, but they are where every claim above comes from:
[Image layout](11-FORMAT.md) for what is where in the flash,
[Boot and power notes](12-NOTES-boot-and-power.md) for the start-up and power-off analysis, and the
[Research report](13-PHASE1-REPORT.md) for the sources, with each finding labelled verified, reported or inferred.

---

Reading order: 1 of 13 · next: [Recovery](02-RECOVERY.md) · [all docs](README.md)
