# The first flash, end to end

Reading order: 1 of 15 · next: [Recovery](02-RECOVERY.md) · [all docs](README.md)

How to put a modified firmware on a Charmera for the first time, step by step, with the point of no return marked.
Every step links to a longer document. Read it before the step, not during it. The [list of all docs](README.md) has
them in reading order.

Only have a phone? The [Charmera Card app](../android/README.md) does steps 4 to 7 on the phone and makes the
published stock restore file itself. Without the backup from step 1 it still writes. For a reviewed file it warns
when nothing confirms what the camera runs, and for a file it doesn't know it warns loudly. Version 0.4, published
before 0.5, had no backup step and didn't refuse the withdrawn candidates, so if you still have it installed, don't
flash with it ([how to tell the two apart](../android/README.md#install)). Read steps 0 and 1 here first.

Not flashing anything? If you only want the photo and video dates fixed, go to
[Photo dates (EXIF)](10-EXIF.md). If your phone won't read the camera's card, go to
[SD card problems](05-CARDS.md).

This only covers the original 2025 series (matte body, yellow Kodak boot screen). Don't flash a Millennium Edition or
a CLOT edition with any file made from the public dump, and don't flash an original-series camera with one either
until its own firmware has been read. See [Recovery, section D](02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera).

## 0. Decide what you can afford to lose

There are two ways to get a camera back after a bad flash, both in [Recovery](02-RECOVERY.md). The SD updater
([section A](02-RECOVERY.md#a-the-camera-boots-and-the-updater-runs-restore-through-the-sd-card)) needs a camera that
still boots far enough to read the card. The CH341A programmer
([section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)) needs the camera
opened and a clip on the flash chip, and it works whatever the firmware does. Nobody has published a recovery of a
camera that doesn't boot without opening it.

So are you willing to open the camera? The backup in step 1 needs it opened anyway. If you aren't, stop here and keep
the camera as it is. A flash that doesn't fit the camera leaves it unable to take pictures, and without a backup of
its own chip there's no way back from that. That's what the public dump did on 2026-11-06 to one original-series
camera, bought in Japan in November 2026 ([what happened](FIRST-FLASH-INCIDENT.md)).

## 1. Know what your camera runs (back up its chip)

Know what your camera runs before you flash. That means a verified dump of this camera's own flash chip, or the
Identify route (`charmera identify`, or "Identify this camera's firmware" in the app). Without either, the app and
`stage` warn, but they don't refuse.

The dump is the better of the two. It's the only way back if a flash goes wrong, and the one file the checks can
compare an update file with. The app writes without one. For a reviewed file it warns when nothing confirms what the
camera runs. For a file it doesn't know it warns loudly that this project didn't review it and it wasn't compared with
this camera's firmware. Without `--stock`, `stage` shows a WARN line but still writes a file that passes
([details](04-STAGE.md#what-it-does-in-order)). Warning instead of refusing was decided on 2026-11-06. Give
`--stock <your backup>` to every `verify` and every `stage`.

You can't tell from outside whether a firmware fits a camera. The public firmware drives exactly one image
sensor. It writes that sensor a fixed table of 61 register settings, never reads which sensor is fitted, and carries
on when the writes fail. A camera with another sensor boots with that firmware, shows its menus and plays back
photos, then freezes the moment photo or video mode starts. The body, the yellow boot screen and the version stamp
don't tell the builds apart: two different builds carry the same stamp `202505201029`
([Research report](13-PHASE1-REPORT.md), [Image layout](11-FORMAT.md#build-identity)). The one file known to fit your
camera is the one read from it before it was ever flashed.

With a CH341A clip ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)):

```
charmera probe
charmera backup --out backups/
charmera inspect backups/<file>.bin
```

`probe` and `backup` print a checklist first: a 3.3 V programmer (never an unmodified black CH341A), the battery lead
lifted, and pin 1 of the clip on the chip's dot. `backup` reads the chip twice and keeps the file only when the reads
agree. `inspect` must find the build marks (the build string, the stamp and the sensor table) and say which build the
file is:

- `public-dump`: your camera runs the same software as the public dump, byte for byte.
- `film-mods`: it runs the build of the charmera-film-mods author's camera.
- `public-dump-modified` or `film-mods-modified`: that build with changes, such as a mod made from it
  ([Image layout](11-FORMAT.md#build-identity) says what counts as one).
- `charmera-unknown-build`: it runs a build nobody has published, which is exactly the case the backup is for.

Keep the file in two places. It's your way back, and it's the `--stock` that every later `verify` and `stage` takes.

### After a flash

A read of a camera that has been flashed before (by you, a seller or an earlier owner) shows the firmware it runs
now, not the one it shipped with. It's still the way back to that state, but it says nothing about which firmware
fits. Comparing a file with it compares the file with whatever
was flashed. A camera flashed with the public restore file reads as the public build (`inspect` says `public-dump`),
and the stock restore file then matches that read whether or not it fits the camera. So when your backup is itself
one of the known builds, `verify`, `stage` and the app show a matching sensor table as a WARN instead of a PASS. The
match proves a fit only if the backup was read before the camera was ever flashed, and nothing in the file can tell
you that. A camera that can't take pictures with the firmware it runs now needs another working camera's firmware:
the donor route in [Recovery, section D](02-RECOVERY.md#the-donor-route).

### Without a clip

Don't flash. The public dump is a copy of one camera. It isn't known to fit yours, and a flash with it can't be undone
without your own backup.

A camera that already lost its original firmware this way can still be flashed through the card, but only a dump from
a working camera with the same sensor can fix it (the donor route above). Read its current chip first if you can,
because that read is the way back if the donor's firmware doesn't start. You can start choosing a donor without a
clip. `photo-id` reads the header of a would-be donor's photos and of the ones this camera took before its flash,
without opening either camera. A different header written by the cameras' own firmware shows a different build. A
photo that another program saved again says nothing about the camera. `photo-id` calls a photo re-saved only when its
header carries a mark an editor leaves, such as an XMP block. Any other header that neither known build writes may be
such a copy or another build's, and nothing in it tells you which. So use the photos as they came off each camera's
card ([Recovery, section D](02-RECOVERY.md#the-donor-route)).

## 2. Pick the file

Build the file from your own backup. `pack` turns the backup into an update file that puts back exactly what the
camera runs now, settings and stored photos included. With `--official-size` it makes a 0x169000-byte file instead,
which writes the software only and leaves the camera's own area to a factory reset. 0x169000 bytes is the size of the
film-mods files, and it's where an official update would end by that project author's reading of the updater. They
also report flashing a file of that size on their own camera (REPORTED). No official update file has been published
or examined here, and this project hasn't flashed one of this size
([Image layout](11-FORMAT.md#update-file-jh_5408stamp_sumbin)).

A mod starts from that backup too. Each of these commands prints what it will change and won't write until you pass
its gate flag:

- `sounds` and `settings`: mute or replace the sounds, change auto-off and standby ([Command reference](07-USAGE.md)).
- `frames`: your own photo frames ([Photo frames](08-FRAMES.md)).
- `resources`: a custom boot logo ([Resource pack and boot logo](09-RESOURCES.md)).
- `tweak`: experimental faster start-up and power-off. Section 6 of the
  [Boot and power notes](12-NOTES-boot-and-power.md) says what each patch changes and why it's a guess.

The three candidates C, A and B in [Flash candidates](03-FLASH-CANDIDATES.md) are withdrawn. `verify` and the app
refuse A and B by their SHA-256, and name C, whose bytes are also the stock restore file made from the cleaned stock
dump. The published stock restore file on the same page fits only a camera whose own firmware is the public build,
and only a backup read before the camera was ever flashed can show that yours is. With that backup as `--stock`, the
`software-region` line says byte-identical and the `sensor-table` line says the same table. (This is a WARN, because
the backup is then the public build itself. See [After a flash](#after-a-flash).) Read both lines yourself. `stage`
refuses a different sensor table, and the app shows a loud "Wrong sensor?" warning for it (the restore file is a
reviewed file) but writes it if you go ahead. Both take a different program with a WARN. If both lines say the same,
that file and a `pack` of your backup are the same software, and the `pack` also puts back your own settings and
photos.

## 3. Verify

```
charmera verify out/JH_5408<stamp>_<sum>.bin --stock <your backup>
charmera verify out/JH_5408<stamp>_<sum>.bin --stock <your backup> --allow-modified   # a mod of code or resources
charmera diff <your backup> out/JH_5408<stamp>_<sum>.bin
```

Always give `--stock <your backup>`. It's the one comparison that says something about your camera, and it only says
what fits for a backup read before the camera was ever flashed. It must be a complete read of a chip as `backup`
writes it: exactly 2 MiB, starting with `SPII`, with the build string and the stamp. Anything else (an update file, a
cut-off read) is a FAIL. A `--stock` that is byte for byte a public dump is a WARN. It may be this camera's own read
after a flash with the public restore file, and then it shows what the camera runs, not what fits it.

`verify` fails closed: a FAIL line means the file doesn't go on a card.

The `sensor-table` line compares the file's image-sensor set-up table with your backup's, found by content in both:

- The table differs from your backup's: FAIL, and no flag clears it, `--allow-unknown-build` included. That
  difference is what leaves a camera unable to take pictures.
- The file or the backup has no sensor table of the kind the known builds use: FAIL, since there's nothing to compare.
- The tables match and your backup is one of the known builds: WARN ([After a flash](#after-a-flash)).
- The tables match and your backup is a build nobody has published: PASS only when the program around the table
  (bytes 0..0x120000, the code and its data) is your backup's too. Otherwise it's a WARN that says the program
  differs, because a different program can drive the same sensor differently.

The one deliberate way past the FAILs is the donor route ([Recovery, section D](02-RECOVERY.md#the-donor-route)).
`--allow-unknown-build` covers only a file without all the build marks (with `--stock`, only one that still has a
sensor table of the known kind) and, without `--stock`, a table that isn't the known builds'.

The `build` line names the known build a file was made from, unchanged or "with changes", or says the build is
unknown. It adds what a build match proves: which public image the file came from, not whether it fits your camera.
A file counts as a known build with changes only when its build marks lie where that build keeps them and it carries
that build's stamp, sensor table, source-tree folder and compile date, as a mod of a backup of that build does. With
any of the four different, it's a build the toolkit doesn't know, "laid out like" that one
([Image layout](11-FORMAT.md#build-identity)). The stamp in the file's name must differ from the one in your backup
(`stamp-camera`), or the camera would ignore the file.

`verify` refuses any change to bytes 0..0x170000 (the code and the resource pack) unless you tell it the change is
intended, which is what `--allow-modified` does. The `software-region` line is then a WARN, never a PASS. No flag
accepts a change to the ARM vector table at 0x200..0x240, where the camera starts. A file laid out like your backup
must carry it byte for byte, because with a broken one the camera may not start far enough to run the card updater
again. A file laid out otherwise is compared with the known build whose layout it has, and a mismatch there is a FAIL
too. Only a file laid out like no known build, whose table isn't your backup's, gets a WARN that says the table
couldn't be compared.

Settings-only files and files from `settings` or `sounds mute --method settings` leave the software region as it is
and pass without `--allow-modified`. Anything built with `sounds mute --method pcm` or `both`, `sounds replace`,
`frames`, `resources` or `tweak` needs it. With the flag, the `software-region` line lists the 64 KiB blocks that
differ from your `--stock` file. They must be only the ones your mod was meant to change (for a PCM mute: 0x120000,
0x150000 and 0x160000). `diff` shows every region that differs from your backup, so you can confirm the change is the
one you intended and nothing else moved. [Image layout](11-FORMAT.md) covers what the regions are and which rules
`verify` enforces, especially [the update file rules](11-FORMAT.md#update-file-jh_5408stamp_sumbin).

## 4. Put it on the card

`stage` runs `verify` again with the same `--stock`, refuses a card that already holds another update file, copies
the file to the card root and reads the copy back to compare hashes. It asks you to type the card path before it
writes. Without `--stock` it warns that no backup of this camera was given and still writes (step 1), so give it.
[Putting the file on the card](04-STAGE.md) explains each refusal. To do this from a phone, use the
[Charmera Card app](../android/README.md) (on Android 11 and later through a USB card reader or the camera's USB-C
cable, not the phone's own SD slot). [Using an Android phone](06-ANDROID.md) covers the rest.

```
charmera stage out/JH_5408<stamp>_<sum>.bin --card E: --stock <your backup>          # Windows
charmera stage out/JH_5408<stamp>_<sum>.bin --card /run/media/deck/<label> --stock <your backup>   # Steam Deck
```

Use a card the camera has formatted itself, or a FAT32 card ([SD card problems](05-CARDS.md)). When `stage` reports
that the read-back matched, eject the card (Windows: Safely Remove Hardware or Explorer's Eject; Linux:
`udisksctl unmount` or the file manager's eject), then take it out of the reader.

The camera can be the card reader too. Connected by its USB-C cable, it shows its card as a USB drive. That's been
seen on a phone. This project hasn't tried it on a computer, where it should show up as a removable drive. Plugging the
cable into a switched-off camera switches it on and runs the updater from the card, so connect it the way the app
does, every time: switch the camera off if it's on, take the card out, switch the camera on with its power key, wait
10 seconds (it looks for an update file only in the first five seconds after power-on), put the card back in, then
plug in the cable. Eject the drive before you unplug the cable.

## 5. Flash (the point of no return)

Before you start:

- You know what the camera runs (step 1). If you made a backup, it passed `inspect` and is kept in two places.
  Without one there's no way back if this flash doesn't fit.
- Original 2025 series only: matte body, yellow Kodak boot screen. Never a Millennium Edition (blue boot
  screen) or a CLOT edition ([Recovery, section D](02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera)). The body
  alone proves nothing, as the incident showed. What does is the comparison in step 3 with a backup read before the
  camera was ever flashed.
- Charge the battery fully, then unplug the cable. Programming takes about 15 to 20 seconds, and losing power in
  that window leaves a camera that only a chip programmer can bring back
  ([Recovery, section C](02-RECOVERY.md#c-an-interrupted-flash)).
- The photos the camera stores in its own memory (up to two, taken with no card in) are replaced by a 0x1E0000-byte
  file. The candidates, and any file packed with `--blank-photos`, erase them. A file packed from someone else's dump
  replaces them with that owner's. Only a file packed from your own backup without `--blank-photos` puts back the
  photos that were in the camera when you made that backup. A 0x169000-byte file (`--official-size`) leaves the
  photo data in place, but the camera empties its photo list, so it no longer shows them and the next photo it
  stores overwrites them. If they matter and you have no backup, get them off the camera first. The camera copies
  them to the `SPIDCIM` folder on the card
  ([Research report, section 2](13-PHASE1-REPORT.md#2-firmware-container-format)), so check that folder on a
  computer before you flash.

Then:

1. Camera off. Insert the card.
2. Hold the power key to switch it on. Within its first five seconds the camera finds the file and shows
   "Upgrade NN%", which goes up once per 64 KiB block, about 15 to 20 seconds in all. Don't touch the key or the card
   while it counts.
3. At 100% the camera stays on its last screen. It doesn't reboot by itself, and it never deletes or renames the
   file. Take the card out, then hold the power key about a second until the camera switches off.
4. If the screen shows "Program error" instead, the sum in the name didn't match the file and nothing was erased.
   The camera switches itself off. Take the card out and run `verify` again.
5. Delete the update file from the card before that card goes back into the camera (on a computer or the phone,
   ejecting the card before you take it out). If it stays there, it flashes again at every power-on. To delete it
   through the camera's own cable, connect the camera as in [step 4](#4-put-it-on-the-card): card out, switch on with
   the power key, wait 10 seconds, card in, then the cable. Plugging the cable into the switched-off camera with the
   file on its card starts that flash again.

## 6. Check

Power on without a card. Does the boot screen appear, does the live view come up, does a shutter press take a
picture? With a card, does a photo save and play back? A muted file should make no sound at power-on, shutter or
power-off. A camera that boots and shows its menus but freezes when photo or video mode starts has received firmware
that doesn't fit it. The way back is step 7 with your backup. If that backup was read after an earlier flash, it
holds that flash's firmware ([After a flash](#after-a-flash)). Write down anything that differs from the stock
behaviour, because few of these files have run on hardware before you.

## 7. Undo

`pack` your own backup (`--factory-settings --blank-photos` optional) and flash it the same way to get back exactly
what you had. If the camera doesn't boot far enough to read the card, see
[Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly).

## Background

You don't need these to flash, but every claim above comes from them:
[Image layout](11-FORMAT.md) for what is where in the flash,
[Boot and power notes](12-NOTES-boot-and-power.md) for the start-up and power-off analysis, and the
[Research report](13-PHASE1-REPORT.md) for the sources, with each finding labelled verified, reported or inferred. If
a camera was already flashed with firmware for the other image sensor, the
[OV9732 repair scope](14-OV9732-REPAIR-SCOPE.md) and
[OV9732 exposure, colour and white balance](15-OV9732-AE-WB-FLASH-PORT.md) cover the repair.

---

Reading order: 1 of 15 · next: [Recovery](02-RECOVERY.md) · [all docs](README.md)
