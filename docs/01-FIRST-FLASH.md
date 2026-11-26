# The first flash, end to end

Reading order: 1 of 15 · next: [Recovery](02-RECOVERY.md) · [all docs](README.md)

How to put a modified firmware on a Charmera for the first time, step by step, with the point of no return marked.
Every step links to a longer document. Read it before the step, not during it. The [list of all docs](README.md) has
them in reading order.

Camera already flashed and now freezing in photo or video mode? Go to
[Recovery, section D](02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera). It still boots, so it can be fixed
from the card. A camera that doesn't start at all, or whose flash was cut off, is
[Recovery, section C](02-RECOVERY.md#c-an-interrupted-flash) or
[section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly).

Only have a phone? The [Charmera Card app](../android/README.md) identifies the camera's firmware from its photos,
builds the reviewed files (the stock restore file, the sensor-ID probe, v0.23 to v0.27, or another mix of mods you tick)
and does steps 4 to 7 on the phone. Without the backup from step 1 it still writes. For a reviewed file it warns when
nothing confirms what the camera runs, and for a file it doesn't know it warns loudly. Version 0.4, published before
0.5, had no backup step and didn't refuse the withdrawn candidates, so if you still have it installed, don't flash with
it ([how to tell the two apart](../android/README.md#install)). Read steps 0 and 1 here first.

Not flashing anything? If you only want the photo and video dates fixed, go to
[Photo dates (EXIF)](10-EXIF.md). If your phone won't read the camera's card, go to
[SD card problems](05-CARDS.md).

This only covers the original 2025 series (matte body, yellow Kodak boot screen). Don't flash a Millennium Edition or
a CLOT edition with any file made from the public dumps. On an original-series camera, find out which firmware it
runs first (step 1). See [Recovery, section D](02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera).

## 0. Pick a route

There are two image sensors in original-series cameras, and each firmware drives one of them. SP140A cameras run
v006 (the public stock dump, and v008_test from the film-mods project). OV9732 cameras ship with v012. A file for
the wrong sensor boots, shows the menus and plays back photos, then freezes the moment photo or video mode starts.
That's what the v006 restore file did on 2026-11-06 to an OV9732 camera. It was brought back later from the card
with firmware made for its sensor ([what happened](FIRST-FLASH-INCIDENT.md)).

You can flash with or without opening the camera.

Without a clip, the camera stays closed:

1. Find out what it runs with Identify (step 1). It needs photos the camera took on its card, so take two or three
   first.
2. Build the file for that base (step 2), verify it (step 3), put it on the card (step 4) and flash it (step 5).
3. The way back is the stock build of the same base, flashed the same way (step 7).

A file for the wrong sensor still boots, and flashing the stock build of the right base from the card undoes it.
What this route can't undo is a flash that's cut off (the battery dies, the card comes out) or firmware that won't
boot. Those need the camera opened and a chip programmer
([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)). Nobody has
published a recovery of a camera that doesn't boot without opening it.

With a clip, you open the camera and back up its chip first (step 1). This is the stronger route. The backup is the
one file the checks can compare an update file with, it puts back your own settings and stored photos, and with the
programmer it also brings back a camera whose flash was cut off or won't boot.

## 1. Know what your camera runs (Identify, or a backup of its chip)

Know what your camera runs before you flash. That means a verified dump of this camera's own flash chip, or the
Identify route (`charmera identify`, or "Identify this camera's firmware" in the app). Without either, the app and
`stage` warn, but they don't refuse.

The dump is the better of the two. It's the way back from any flash, one that won't boot included, and the one file
the checks can compare an update file with. The app writes without one. For a reviewed file it warns when nothing
confirms what the camera runs. For a file it doesn't know it warns loudly that this project didn't review it and it
wasn't compared with this camera's firmware. Without `--stock`, `stage` shows a WARN line but still writes a file
that passes ([details](04-STAGE.md#what-it-does-in-order)). Give `--stock <your backup>` to every `verify` and every
`stage` when you have one.

You can't tell from outside which sensor a camera has. v006 writes its sensor a fixed table of 61 register settings,
never reads which sensor is fitted, and carries on when the writes fail. v012 has its own driver for the OV9732, with
a table of 167 writes. The body, the yellow boot screen and the version stamp don't tell the builds apart: every
known build carries the stamp `202505201029` ([Research report](13-PHASE1-REPORT.md),
[Image layout](11-FORMAT.md#build-identity)). The camera's photos tell you which stock build took them, and a read of
its chip taken before it was ever flashed shows what it shipped with.

### Without a clip

Put the camera's card in a reader and run Identify on it:

```
charmera identify E:\
```

It reads only the headers of the photos the camera took, so the card needs some. On a new or empty card, take two or
three photos with the camera first. A bare 0x270-byte header means stock v012, an OV9732 camera, base v012. The
0x590-byte header means stock v006 or v008_test, an SP140A camera, base v006. It prints the `charmera build` command
to run next (step 2). The same check is "Identify this camera's firmware" in the app
([Using an Android phone](06-ANDROID.md#identify-this-cameras-firmware)).

Photos show the firmware that took them. If the camera was flashed after them, by you, a seller or an earlier owner,
it runs something else now. On a camera that was flashed before, use photos from before that flash. If there are
none, the sensor-ID probe finds the sensor
([Using an Android phone](06-ANDROID.md#identify-the-image-sensor-the-sensor-id-probe)).

### With a clip

Use a CH341A or another 3.3 V programmer
([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)):

```
charmera probe
charmera backup --out backups/
charmera inspect backups/<file>.bin
```

`probe` and `backup` print a checklist first: a 3.3 V programmer (never an unmodified black CH341A), the battery lead
lifted, pin 1 of the clip on the chip's dot, and a multimeter check. With the programmer plugged in alone and the
clip on nothing, pin 8 (VCC) and pin 1 (CS) should both read about 3.3 V against pin 4 (GND). If either reads near
5 V, stop. A CH341A board used for this project measured 5 V on CS.

`backup` reads the chip twice and keeps the file only when the reads agree. `inspect` must find the build marks (the
build string, the stamp and the sensor table) and say which build the file is:

- `public-dump`: your camera runs the same software as the public v006 dump, byte for byte (an SP140A camera).
- `film-mods`: it runs v008_test, the build of the charmera-film-mods author's camera (SP140A).
- `v012`: it runs the factory build of OV9732 cameras, the same software as the cleaned public v012 copy.
- `public-dump-modified`, `film-mods-modified` or `v012-modified`: that build with changes, such as a mod made from
  it ([Image layout](11-FORMAT.md#build-identity) says what counts as one).
- `charmera-unknown-build`: it runs a build nobody has published, which is exactly the case the backup is for.

The sensor table line says which kind it found: the SP140A table v006 and v008_test carry, or v012's table for the
OmniVision OV9732. A camera that runs our v0.23 or v0.24 shows the SP140A table, because the port keeps it, and
`inspect` adds that the code carries our OV9732 port. That camera is an OV9732.

Keep the file in two places. It's your way back, and it's the `--stock` that every later `verify` and `stage` takes.

### After a flash

A read of a camera that has been flashed before (by you, a seller or an earlier owner) shows the firmware it runs
now, not the one it shipped with. It's still the way back to that state, but it says nothing about which firmware
fits. Comparing a file with it compares the file with whatever was flashed. A camera flashed with the public restore
file reads as the public build (`inspect` says `public-dump`), and the stock restore file then matches that read
whether or not it fits the camera. So when your backup is itself one of the known builds, `verify`, `stage` and the
app show a matching sensor table as a WARN instead of a PASS. The match proves a fit only if the backup was read
before the camera was ever flashed, and nothing in the file can tell you that. A camera that can't take pictures
with the firmware it runs now needs firmware for its sensor:
[Recovery, section D](02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera) covers finding the sensor and the
builds that fit, and [the donor route](02-RECOVERY.md#the-donor-route) for a sensor neither public build drives.

## 2. Pick the file

Most files come from `charmera build`. It makes a firmware file from a base, the stock firmware of one build, and the
mods you pick:

```
charmera build --list
charmera build --base v012 --mods volume-led,exif --download --out out/
```

Take the base Identify gave you (v012 for an OV9732 camera, v006 for an SP140A one). Leave out `--mods` for the stock
build. `--list` shows every base, mod and combination. For v012 that's v0.25 (Volume and LED rows), v0.26 (plus EXIF
dates) and v0.27 (plus boot to the last mode and capture flash), and the other mixes. For v006 it's the stock restore
file, the sensor-ID probe and Volume/LED. v0.23 and v0.24 are v006 ported to the OV9732, for an OV9732 camera that
was flashed with v006. `--download` fetches the base's cleaned public copy at a fixed commit and checks its SHA-256.
The file is written only when it's the reviewed one listed for that combination, and `build` runs `verify` on it.
Combinations marked experimental have been built and checked but not tried on a camera, and `build` says so. More in
[Command reference](07-USAGE.md#build-a-firmware-file-from-a-base-and-mods-charmera-build).

With a backup, `--dump <your backup>` builds from your own read instead, when its firmware is still stock v006 or
v012 byte for byte. The file carries your camera's settings, and it isn't a reviewed file, so `build` labels it that
way and prints the `verify` and `stage` lines to run with `--stock <your backup>`.

Or build the file from your own backup with `pack`. It turns the backup into an update file that puts back exactly
what the camera runs now, settings and stored photos included. With `--official-size` it makes a 0x169000-byte file
instead, which writes the software only and leaves the camera's own area to a factory reset. 0x169000 bytes is the
size of the film-mods files, and it's where an official update would end by that project author's reading of the
updater. They also report flashing a file of that size on their own camera (REPORTED). No official update file has
been published or examined here, and this project hasn't flashed one of this size
([Image layout](11-FORMAT.md#update-file-jh_5408stamp_sumbin)).

Lower-level mods start from that backup too. Each of these commands prints what it will change and won't write until
you pass its gate flag:

- `sounds` and `settings`: mute or replace the sounds, change auto-off and standby ([Command reference](07-USAGE.md)).
- `frames`: your own photo frames ([Photo frames](08-FRAMES.md)).
- `resources`: a custom boot logo ([Resource pack and boot logo](09-RESOURCES.md)).
- `tweak`: experimental faster start-up and power-off. Section 6 of the
  [Boot and power notes](12-NOTES-boot-and-power.md) says what each patch changes and why it's a guess.

The three candidates C, A and B in [Flash candidates](03-FLASH-CANDIDATES.md) are withdrawn. `verify` and the app
refuse A and B by their SHA-256, and name C, whose bytes are also the stock v006 file built from the cleaned v006
copy. The published stock restore file on the same page is stock v006, for SP140A cameras. Only a backup read before
the camera was ever flashed, or Identify, can show that yours is one. With that backup as `--stock`, the
`software-region` line says byte-identical and the `sensor-table` line says the same table. (This is a WARN, because
the backup is then the public build itself. See [After a flash](#after-a-flash).) Read both lines yourself. If both
say the same, that file and a `pack` of your backup are the same software, and the `pack` also puts back your own
settings and photos. With a backup of an OV9732 camera, `verify`, `stage` and the app show a loud wrong-sensor
warning for it ("Wrong sensor?" in the app) and write it if you go ahead. On that camera it boots but can't take
pictures.

## 3. Verify

```
charmera verify out/JH_5408<stamp>_<sum>.bin --stock <your backup>
charmera verify out/JH_5408<stamp>_<sum>.bin --stock <your backup> --allow-modified   # a mod of code or resources
charmera verify out/JH_5408<stamp>_<sum>.bin                                          # no backup
charmera diff <your backup> out/JH_5408<stamp>_<sum>.bin
```

For a file from `charmera build`, here's what you'll see. An INFO `reviewed` line names it. Without `--stock` there's
a WARN `backup` and a WARN `unconfirmed`, because nothing confirms which firmware the camera runs. Its `series`,
`build`, `software-region` and `sensor-table` problems show as WARN (marked as expected for a reviewed build), and a
backup for the other sensor adds a WARN `wrong-sensor`. It needs no `--allow-modified`. The name, size, checksum and
vector-table checks still FAIL as they would for any file. The rules below apply in full to every other file, such
as one from `pack` or the mod commands, or a `build --dump` of your own read.

Give `--stock <your backup>` when you have one. It's the one comparison that says something about your camera, and
it only says what fits for a backup read before the camera was ever flashed. It must be a complete read of a chip as
`backup` writes it: exactly 2 MiB, starting with `SPII`, with the build string and the stamp. Anything else (an update
file, a cut-off read) is a FAIL. A `--stock` that is byte for byte a public dump is a WARN. It may be this camera's
own read after a flash with the public restore file, and then it shows what the camera runs, not what fits it.

`verify` fails closed: a FAIL line means the file doesn't go on a card.

The `sensor-table` line compares the file's image-sensor set-up table with your backup's, found by content in both.
There are two kinds the toolkit knows: the SP140A table v006 and v008_test carry, and v012's table for the OV9732.

- The table differs from your backup's: FAIL, and no flag clears it, `--allow-unknown-build` included. The line names
  the sensor each one is for. That difference is what leaves a camera unable to take pictures.
- The file or the backup has no sensor table of a kind the toolkit knows: FAIL, since there's nothing to compare.
- The tables match and your backup is one of the known builds: WARN ([After a flash](#after-a-flash)).
- The tables match and your backup is a build nobody has published: PASS only when the program around the table
  (bytes 0..0x120000, the code and its data) is your backup's too. Otherwise it's a WARN that says the program
  differs, because a different program can drive the same sensor differently.

Without `--stock`, a known table is a WARN that says which sensor it fits and that nothing in the file can tell
which sensor your camera has.

The one deliberate way past the FAILs is the donor route ([Recovery, section D](02-RECOVERY.md#the-donor-route)).
`--allow-unknown-build` covers only a file without all the build marks (with `--stock`, only one that still has a
sensor table of a known kind) and, without `--stock`, a table that isn't the known builds'.

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
writes. With no backup, leave out `--stock`. It then warns that nothing compares the file with this camera and still
writes once you confirm (step 1). [Putting the file on the card](04-STAGE.md) explains each refusal. To do this from
a phone, use the [Charmera Card app](../android/README.md) (on Android 11 and later through a USB card reader or the
camera's USB-C cable, not the phone's own SD slot). [Using an Android phone](06-ANDROID.md) covers the rest.

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
  Without one, a file for the wrong sensor is undone from the card (step 7), but a flash that's cut off or won't
  boot needs the camera opened.
- Original 2025 series only: matte body, yellow Kodak boot screen. Never a Millennium Edition (blue boot
  screen) or a CLOT edition ([Recovery, section D](02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera)). The body
  alone proves nothing, as the incident showed. What does is the comparison in step 3 with a backup read before the
  camera was ever flashed.
- Charge the battery fully, then unplug the cable. Programming takes about 15 to 20 seconds, and losing power in
  that window leaves a camera that only a chip programmer can bring back
  ([Recovery, section C](02-RECOVERY.md#c-an-interrupted-flash)).
- The photos the camera stores in its own memory (up to two, taken with no card in) are replaced by a 0x1E0000-byte
  file. The candidates, the files from `build --download` and any file packed with `--blank-photos` erase them. A
  file packed from someone else's dump replaces them with that owner's. Only a file packed from your own backup
  without `--blank-photos` puts back the photos that were in the camera when you made that backup. A 0x169000-byte
  file (`--official-size`) leaves the photo data in place, but the camera empties its photo list, so it no longer
  shows them and the next photo it stores overwrites them. If they matter and you have no backup, get them off the
  camera first. The camera copies them to the `SPIDCIM` folder on the card
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
that doesn't fit it. The way back is step 7. If your backup was read after an earlier flash, it holds that flash's
firmware ([After a flash](#after-a-flash)). Write down anything that differs from the stock behaviour, because few of
these files have run on hardware before you.

## 7. Undo

With a backup, `pack` it (`--factory-settings --blank-photos` optional) and flash it the same way to get back exactly
what you had.

Without a backup, flash the stock build of the camera's base:

```
charmera build --base v012 --download --out out/      # OV9732 camera
charmera build --base v006 --download --out out/      # SP140A camera
```

Verify and stage it as in steps 3 and 4, then flash it as in step 5. It puts back the factory software with the
cleaned copy's factory settings, and it erases the photos stored in the camera. If the camera froze after a flash
with firmware for the other sensor, the base is the one its photos from before that flash show (step 1). In the app,
stock v012 is base v012 with no mods ticked.

If the camera doesn't boot far enough to read the card, see
[Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly).

## Background

You don't need these to flash, but every claim above comes from them:
[Image layout](11-FORMAT.md) for what is where in the flash,
[Boot and power notes](12-NOTES-boot-and-power.md) for the start-up and power-off analysis, and the
[Research report](13-PHASE1-REPORT.md) for the sources, with each finding labelled verified, reported or inferred. If
a camera was already flashed with firmware for the other image sensor, the
[OV9732 repair scope](14-OV9732-REPAIR-SCOPE.md) and
[OV9732 exposure, colour and white balance](15-OV9732-AE-WB-FLASH-PORT.md) cover the repair, and
[Using an Android phone](06-ANDROID.md#repair-an-ov9732-camera-the-ov9732-bring-up) has the builds for it.

---

Reading order: 1 of 15 · next: [Recovery](02-RECOVERY.md) · [all docs](README.md)
