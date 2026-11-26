# Using an Android phone

Reading order: 6 of 15 · previous: [SD card problems](05-CARDS.md) · next: [Command reference](07-USAGE.md) · [all docs](README.md)

A phone can do the SD-card route, which covers every normal flash, and undoes a flash that left the camera booting
but unable to take pictures. It can't do the chip-level route (a 3.3 V chip programmer such as a CH341A board), which
you need for the backup and for a camera that no longer boots. The backup is the only way back to exactly what the
camera ran, settings included. Without one, a camera that won't boot can still take the public stock file for its
sensor through a chip programmer ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)).
The parts that are still inference are marked.

## Before you flash from a phone

The same rules apply as in [step 5 of The first flash](01-FIRST-FLASH.md#5-flash-the-point-of-no-return):

- Know what your camera runs before you flash: a backup of its chip, or **Identify this camera's firmware** (`charmera
  identify` on a computer). Without either, the app warns but doesn't refuse. The backup is a dump of this camera's
  flash chip, read with a 3.3 V chip programmer on a computer ([step 1 of The first
  flash](01-FIRST-FLASH.md#1-know-what-your-camera-runs-identify-or-a-backup-of-its-chip)). It's the only way back to exactly
  what the camera ran, settings included, and the app compares the update file's image-sensor set-up with it (`verify --stock <backup>` does the same
  on a computer). Only a dump read before the camera was ever flashed can tell you whether a firmware fits it. After a
  flash the dump holds the flashed firmware, so when that's one of the known builds the app shows a matching sensor
  set-up as a warning ([After a flash](01-FIRST-FLASH.md#after-a-flash)). The app can't tell whose chip a dump came
  from. It refuses the public dumps (the two public stock dumps and the cleaned copies of v006 and v012) and any read
  named as unverified (`.unverified-read<n>.bin`), so give it only a dump read from this camera with a clip, from two
  reads that agreed.
- Original 2025 series only: matte body, yellow Kodak boot screen. Never a Millennium Edition (blue boot screen) or a
  CLOT edition ([Recovery, section D](02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera)). The body alone proves
  nothing: the public dump left an original-series camera unable to take pictures.
- Charge the battery fully before every flash. If the power goes while the camera programs itself, only a chip
  programmer can bring it back ([Recovery, section C](02-RECOVERY.md#c-an-interrupted-flash)).
- A 0x1E0000-byte file, such as the stock restore file the app makes, replaces the two photos the camera keeps in its
  own memory. The stock restore file carries the two photos of the camera it was copied from, but its photo list is
  empty, so the camera doesn't show them. Your own two are gone either way. A 0x169000-byte file
  (`pack --official-size` on a computer) leaves the photo data in place, but the camera empties its photo list, so it
  no longer shows them. If yours matter, copy the `SPIDCIM` folder off the card first
  ([step 5 of The first flash](01-FIRST-FLASH.md#5-flash-the-point-of-no-return)).

## The app

[Charmera Card](../android/README.md) does the card part on the phone. It identifies the firmware a camera runs from
the photos on its card. It downloads the stock dump's cleaned copy and makes the stock restore file from it, plus
reviewed patches of that file: the sensor-ID probe, Volume and LED rows (experimental on an SP140A camera), and the
OV9732 port with or without those rows. From the factory build v012 (its public cleaned copy, which it downloads, or
the private read that copy was cleaned from) it makes stock v012 and v012 with any mix of Volume and LED rows, EXIF
dates, boot to last mode and capture flash. From build v008_test's public file it makes stock v008_test, which has no
mods yet. You pick a base and tick its mods, and it makes any reviewed combination
([Picking a base and mods](#picking-a-base-and-mods)). It builds nothing else. Older versions of the app gave some of
these builds numbers, v0.23 to v0.27. [Older build names](#older-build-names) maps them.

It compares the file with your backup if you've chosen one, writes the file to the camera's card over the camera's USB-C cable,
reads it back, and walks you through the flash and the cleanup. Any reviewed file can go to the card without a backup.
The app warns when nothing confirms which firmware the camera runs, or when your backup's firmware drives the other
sensor, and leaves the decision to you. A file it doesn't know can go without a backup too, with a loud warning that
this project didn't review it and nothing compared it with this camera's own firmware. If you've chosen a backup, that
file is checked against it. The app still refuses a file that fails its checks, the withdrawn candidates A and
B, a backup or dump given as the update file, and a write while a backup is still being read or a check is still
running (wait, and it checks again). It also saves the camera's photos and videos as a zip.

The current version is 0.32. On 2026-11-11 the app wrote v006 with the OV9732 port and Volume and LED rows (then
called v0.24) from Flash custom firmware to an OV9732 camera over the camera's USB-C cable, and the camera flashed
it. What the camera showed afterwards is in [OV9732 exposure, colour and white balance](15-OV9732-AE-WB-FLASH-PORT.md).

Download
[charmera-card.apk](https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk)
on the phone. It opens on a Home screen with one button per route. Each route is its own screen of numbered steps,
with each result shown right under its button ([the app's routes](../android/README.md#use)):

- **Back up photos** makes a zip of the photos and videos in `DCIM` and `SPIDCIM`, reads it back and checks it entry
  by entry. You can have their dates fixed in the copies, have the copies named by date and time (**Name the copies by
  date and time**, on by default: `20261111_114931.jpg` in place of the card's name, in the same folder, when the time
  can be read), and have them deleted from the camera, only those whose entries matched. With delete on, the zip also
  keeps each date-fixed file as the camera wrote it in `originals/`, under its name on the card.
- **Flash custom firmware** writes the firmware you put together in its write step. You pick a base and tick its
  mods one by one ([below](#picking-a-base-and-mods)). The step is called **Write firmware**, and the line under its
  title names the firmware your ticks make, such as v012 with Volume and LED rows and EXIF dates
  ([below](#exif-dates)) or v006 with the OV9732 port and Volume and LED rows
  ([below](#repair-an-ov9732-camera-the-ov9732-port-on-v006)). Then you tap **Make this firmware**, write it, eject,
  flash, remove the update file, and check the header of a photo taken afterwards. The first time, it starts on v012
  with Volume and LED rows, EXIF dates, boot to last mode and capture flash (with no factory dump on the phone, making
  it downloads the factory build's cleaned copy), and it remembers your pick. A file loaded with **Use a file on the
  phone** becomes the pick, its base and its boxes, so the step and the file always name the same firmware. A factory
  dump you pick on another route is kept, but only this route (and Advanced) makes firmware from it. Not sure what to
  tick? Each box says what its mod does. On base v012, three mixes have been tried on a camera: Volume and LED rows
  alone, Volume and LED rows with EXIF dates, and all four mods. Any other mix is marked experimental. The way back to
  stock is stock v012 (base v012 with no mods ticked).
- **Identify this camera's firmware** reads the headers of the photos on the card and says which firmware family took
  them, with what to check on the camera ([below](#identify-this-cameras-firmware)). It writes nothing.
- **Identify the image sensor** writes the sensor-ID probe ([below](#identify-the-image-sensor-the-sensor-id-probe))
  and reads its result.
- **Restore stock firmware** compares the stock restore file (v006, SP140A firmware) with this camera's backup, then
  writes it. It needs the backup. Without one, Advanced writes the same file.
- **Test shots** shows a colour card on the phone and saves the shots taken of it as a zip with a manifest.
- **Flash log** lists what was written, flashed, removed and saved, on this phone. **Advanced** has the old all-in-one
  steps: any update file, **Use a file on the phone**, save a copy, write, remove, read the sensor result.

Every screen shows which camera drive the app has saved and whether it's connected. The app can't eject a drive by
itself. It opens the drive's own storage page, and says "Ejected" only once Android reports the drive unmounted.

How the card reaches the app depends on the Android version:

- Android 11 and later: Android doesn't let an app pick the top level of a card in the phone's own SD slot ("Use this
  folder" is greyed out there). Leave the card in the camera and connect the camera with its USB-C cable. The camera
  shows its card as a drive on the phone ([below](#through-the-cameras-own-usb-port)). A
  USB card reader works too. Both can be picked at their top level.
- Android 7 to 10: the phone's own SD slot works as well.
- Only the slot, on Android 11 or later: copy the update file to the card's top level with the Files app
  ([section 3](#3-copy-the-file-to-the-card-root)), then pick that copy in the app's Advanced page (**Use a file on
  the phone**), with your backup chosen there, to have it checked where it lies. If the check fails, delete the copy
  before the card goes into the camera. With no backup chosen, nothing compared it with this camera (the app says so
  loudly), so don't leave it on the card unless you know it fits. Delete it with the Files app after the flash too.

## Identify this camera's firmware

This route needs no flash, no backup and no update file, and it writes nothing to the card or the flash
log. Choose the camera's drive, then tap **Identify from the photos on the card**. Card empty or new? Take two
or three photos with the camera first. With no photos it says "The card holds no photos. Take two or three with the
camera first, then identify again. If photo mode freezes and the camera can't take any, Identify the image sensor
finds the sensor instead."

Photos moved off the card? Copy the camera's originals (the zip's `originals/` folder when delete was on, or the files
as they came off the card) back into `DCIM`, or run `charmera identify <folder>` on a computer. Date-fixed copies don't
count.

The app reads the start of the photos in `DCIM`, only up to the start of the picture (videos are only counted), and
sorts them by file time, as the toolkit's `identify` does. Photos with no file time are left out of the dating. The
answer counts them ("N photos have no file time and were left out of the dating") and the evidence names them. It finds
the oldest and the newest camera-written photo, reading at most 500 from each end. Then it reads 500 photos evenly
spaced by date, those two always among them (every photo when there are no more than that), and looks there for a
change of family:

| Header | Family |
| --- | --- |
| 0x590 bytes, comment "GPEncoder" and six spaces, EXIF Make Generalplus, Model CBB3, Software 20150615 | v006 family (stock v006 or v008_test, SP140A cameras) |
| 0x270 bytes, comment "GPEncoder", no EXIF | v012 family (stock v012, OV9732 cameras) |
| 0x340 bytes, EXIF Make Kodak, Model Charmera, right after the start | our EXIF mod: v012 family, not stock |

Photos it doesn't recognise (from another camera, edited, or an `exif-fix` copy) are counted and left out. When the
newest files on the card are such photos, a note says how many were skipped.

The route answers "which stock firmware took these photos?", so you can pick the base to build on. When every dated
camera-written photo is one family, it says "These photos were taken by stock v012 (an OV9732 camera). If the camera
was flashed after them, it runs something else now." (or "stock v006 or v008_test (an SP140A camera)"), with that
base (v012, or v006 for the v006 family). Photos taken before a bad flash still say which sensor the camera has, so
this works on a camera that can't take pictures any more. Confidence is high when every dated photo was read and
at least 2 camera-written photos agree, and medium for a single photo or a capped read. A v006-family answer adds one
line: our OV9732 port on v006 writes the same header.

A flashed or modded camera is the exception, and the answer says so in a line of its own, "This camera does not look
stock: ...". If any photo has our EXIF mod's header, the mod is installed (and the answer says between which dates,
when bare photos come before it). The base stays v012, since the mod is built on it, with high confidence, and bare and
EXIF-mod photos count as one family. If the card holds two families, either the camera was flashed between them or the
photos come from two cameras. The base then comes from the oldest family (photos from before a flash show the stock
firmware), never the newest, with low confidence.

The answer gives the base, a confidence and the evidence, photo by photo. What a photo can't tell you:

- v006 from v008_test. Both are stock builds for SP140A cameras and write the same header, so the app suggests base
  v006 for either.
- What fits a flashed camera. Photos show the firmware that took them, so on a camera that was flashed, or that freezes
  in Photo mode, only photos from before the flash say what fits.
- Anything about an `exif-fix` copy. A copy fixed with the toolkit's `exif-fix` has no Software tag, which the camera
  always writes. With Make Kodak and Model Charmera it isn't the EXIF mod's header, and with `--keep-make` (Generalplus, CBB3)
  it isn't a v006-family photo. The app recognises neither.
- The true dates. The dates and the order come from the file times, which come from the camera's clock.

The third step lists what you can check on the camera itself
([Telling builds apart on the camera](CAMERA-UI.md#telling-builds-apart-on-the-camera)). The answer's last line names
the base ("Your base is v012.") and says where to pick it, and **Open Flash custom firmware** takes you there. The app
keeps that base (v012 for the factory build, v006 for the v006 family). Flash custom firmware doesn't pick it for
you. If the base you pick there is for the other sensor than the one Identify found, the write step says so.

## Picking a base and mods

The write step of **Flash custom firmware** shows a base menu and one box per mod of that base, each with a one-line
description. You tick the mods you want one by one. The bases are v012 (factory build, OV9732 sensor), v006 (public
build, SP140A sensor) and v008_test (later build, SP140A sensor), which has no mods yet. The step is called **Write
firmware**, and the line under its title names the firmware your ticks make by its base and mods, such as "v012 with
Volume and LED rows and EXIF dates" or "v006 with the OV9732 port". With no mods ticked on v012 it's "stock v012 (the
factory firmware, unchanged)". **Make this firmware** makes it, and the Home tile shows the same name. The app doesn't
pick the base from what **Identify this camera's firmware** found. If you pick a base for the other sensor than the one
Identify found, the step says so. A mod marked *experimental, not yet tried on a camera* adds a warning line but is never
blocked. A mod that can't go with one already ticked is greyed out, with the reason (on v006, that's the sensor-ID
probe with the OV9732 port or with the Volume and LED rows).

The app makes the pick exactly as `charmera build` does on a computer (charmera_toolkit/recipes.py, from the same
recipes.json). v012 comes from the factory dump (the private read when the phone keeps it, otherwise the public cleaned
copy), v006 from the public stock dump, and v008_test from its public file
([the-dumping-grounds/charmera-firmware-v008_test](https://github.com/the-dumping-grounds/charmera-firmware-v008_test),
at a fixed commit, checked by SHA-256). Each mod is applied only over the bytes it was written for. The result is kept
only when it's the reviewed file the recipes list for that base, those mods and that dump, under that file's name, and
anything else is refused. The five builds older versions of the app numbered v0.23 to v0.27 come out byte for byte as
they did then ([Older build names](#older-build-names)). The flash check expects
the header the pick's mods give: the EXIF header with the EXIF mod, the factory header on v012 without it, and the
public family's on v006 and v008_test.

Every pick can be written here without a backup, whatever sensor its firmware drives. You decide whether it fits the
camera. The app warns, without refusing, in two cases. With no backup chosen and no Identify answer for the same
sensor, the file step and the write confirmation say the camera's firmware isn't confirmed and which sensor the file
drives. With a backup chosen whose firmware drives the other sensor (an SP140A pick against an OV9732 backup, say),
the sensor line becomes a "Wrong sensor?" warning. A wrong-sensor flash boots but can't take pictures. The card updater
still works, so flashing a file that fits undoes it.

Stock v012 (base v012, no mods) is the factory firmware of OV9732 cameras, unchanged. It's written here like any other
pick, and it's the way back to stock from every v012 build. Stock v006 is the stock restore file, and this screen
doesn't write it. With base v006 and no mods ticked, **Make this firmware** gives way to two buttons: **Open Restore
stock firmware**, which compares the file with your backup, and **Open Advanced**, which writes it without one. A
combination the recipes mark experimental says so in the warning line and in the write confirmation, even when each of
its mods has been tested alone. The pick (base and mods) is remembered, and a numbered build picked in an older app
(v0.24 to v0.27) comes back as its base and mods.

## The mods on v012

Base v012 is the factory firmware of OV9732 cameras. It has four mods, and you can tick any mix of them. Three mixes
have been tried on a camera: Volume and LED rows alone, Volume and LED rows with EXIF dates, and all four. Any other
mix is marked experimental. The way back to stock from every one is stock v012 (base v012 with no mods ticked). The
file names and SHA-256s are in [The files the app makes](#the-files-the-app-makes).

### Volume and LED rows on v012 (the camera's own factory firmware)

v012 with Volume and LED rows is the factory firmware of OV9732 cameras (build v012, from a private read of a v012
camera's chip taken on 2026-11-13), which drives the OV9732 natively, with the **Volume** and **LED** rows of the
v006 port moved over to it. None of the OV9732 port's sensor work is carried over, since the factory build has its
own start-up table, exposure, white balance and colour. The camera starts with Volume 5 and LED On, and photos carry
no EXIF date.

The factory dump isn't in this repository or the app. The app gets it one of two ways, and keeps a copy in its own
storage that it checks again on each use:

- Download (the default). With no factory dump on the phone, **Make this firmware** on base v012 downloads the
  public cleaned copy, `charmera-v012-2026-01-12-clean.bin` from
  [the-dumping-grounds/charmera-firmware-v012](https://github.com/the-dumping-grounds/charmera-firmware-v012) at a
  fixed commit, and uses it only if its SHA-256 is
  `63e396846f949600c2c1881920860821f2fcd70a70ac79ca54eebaee4a0ccb1c`.
- A file on the phone. **Use a file on the phone** takes the cleaned copy, downloaded in a browser, when the phone
  can't reach GitHub, or the private read it was cleaned from (SHA-256
  `08a7d993996b93cf2468fe08d70a0e77d5e1a9d6ff16bf647f5c7a0edf5d85ce`). It takes no other file as the factory dump,
  by SHA-256. The file picked replaces the downloaded copy and is used from then on. A read of your own v012 camera
  goes in as a backup (**Choose this camera's backup**), and the app compares the file with it.

The app makes v012 with Volume and LED rows from it the same way the computer build does: the dump's first 0x1E0000
bytes, the internal photo slots (0x180000..0x1CB000) blanked, and 172 authored bytes (the source holds only those).
The checker knows the factory build v012 and finds its OV9732 set-up table (v012 has its own OV9732 driver, with no
SP140A-style table). With no backup chosen, the sensor line is a warning that nothing in the file can tell whether
your camera has an OV9732, and that its photos can (Identify).

v006 with the OV9732 port and Volume and LED rows is a way back too, next to stock v012. After a flash of a v012 build
without EXIF dates, the flash check expects the factory header (no EXIF block).

### EXIF dates

The EXIF dates mod puts an EXIF block in every still photo, on the SD card and in the camera's own memory: Make `Kodak`,
Model `Charmera`, Software `v0.26`, and DateTime, DateTimeOriginal and DateTimeDigitized from the camera's clock. Set
the date in the camera's setup menu first, because an unset clock gives `2025:01:01`. The still header grows from the
factory build's bare 0x270 bytes to 0x340: the SOI, an authored APP1 block of 0xD0 bytes, then the factory template's
own segments from SOF0 on. Software `v0.26` is an old build number that's part of the mod's code, so it can't change.
The first build with it to be tried on a camera was v012 with Volume and LED rows and EXIF dates. With the Volume and
LED rows, the camera starts with Volume 5 and LED On.

The app makes it from your factory dump the way it makes v012 with Volume and LED rows, then adds one more layer. That
layer is 332 authored bytes (the APP1 block, a new date-fill routine, and the instructions that point the photo
writers at the new header) and one range of 0x26E bytes, the factory template from SOF0 on, which the app copies from
your factory dump at run time (file 0x10753A to 0x89712). So the source holds none of the vendor's bytes.

v012 with Volume and LED rows is a way back too, and so is v006 with the OV9732 port and Volume and LED rows. After a
flash of a build with EXIF dates, the flash check expects the 0x340-byte header with its APP1 EXIF block (Make Kodak,
Model Charmera).

### Boot to last mode and capture flash

These are two more mods on v012. Both were tested in an emulator first, then on a camera, in v012 with Volume and LED
rows, EXIF dates, boot to last mode and capture flash.

- Boot to last mode. After power-on (and after the card's update check, which is unchanged) the camera goes
  straight into the mode last used, photo or video, instead of the SELECT MODE screen. The first time, that's photo. A
  short press of the power button still opens SELECT MODE. The mode is kept in a spare settings byte (+0x61). The camera
  saves its settings on its own schedule, so a mode change can be lost if you switch it off within a few seconds.
- Capture flash. When a photo is taken, the picture area goes black for 120 ms under the on-screen icons, inside the
  stock 200 ms shutter delay, so you see the shot even at Volume 0. If the camera stops sending preview frames during
  that delay, no flash shows, and nothing else changes.

The app makes that build the way it makes the EXIF one, then adds one more layer of 202 authored bytes. Photos keep
the EXIF dates header (Software `v0.26`), so the photo check can't tell this build from v012 with Volume and LED rows
and EXIF dates. Check boot to last mode and capture flash on the camera itself. v012 with Volume and LED rows and EXIF
dates is a way back too.

## Identify the image sensor (the sensor-ID probe)

If a camera boots to its menu but freezes when it enters photo or video mode, the cause may be an image sensor the
firmware doesn't drive. The app can build a **sensor-ID probe** (the "Identify the image sensor" route) to find out
which sensor is fitted. It's a diagnostic and doesn't make the camera take pictures. If the card has photos the camera
took before any flash, [Identify](#identify-this-cameras-firmware) already tells you: photos taken by stock v012 mean
an OV9732 camera, and you don't need the probe.

The probe is the stock restore file with a small reviewed patch. Its boot code, update code and image-sensor table are
the stock restore file's, byte for byte. Only a dead debug routine and the one call that writes the sensor set-up are
changed. When the camera next enters photo mode, the patch walks the sensor bus across every device address (0x08..0x77)
with the firmware's own read routine. For each address that answers, it reads the 16-bit chip-ID registers
(0x3000..0x301F and 0x3100..0x310F) and writes the result to `SENSOR.TXT` in the card's root, then stops there. It
replaces the camera's firmware until you flash another. On an SP140A camera the stock restore file puts it back. On an
OV9732 camera, undo it with a v012 build, stock or with mods (Flash custom firmware), not the stock restore file. It's
one of the reviewed files the app writes without a backup, like every build of Flash custom firmware.

After flashing it, take the update file off the card first, or the camera re-flashes it on every boot instead of
running it. Then boot the camera and open photo mode so the probe runs. Bring the card back to the phone and tap **Read
the sensor result from the card** (the route's last step). The result is one of these:

- A named sensor, for example "OmniVision OV2680" or "SmartSens SC…". The app read the sensor's 16-bit chip-ID
  registers and matched them, so the exact part is known and a firmware built for it becomes possible.
- A sensor at some address, not yet named. It answered, but its chip ID wasn't at the registers the app reads. The app
  shows the raw register fingerprint and what to try next (often an 8-bit read, for an older part).
- Nothing answered at any address. The sensor isn't responding at all (disconnected, unpowered, or dead). Reseat the
  sensor's ribbon and scan again. If it still answers nowhere, it's a hardware fault no firmware can fix.

For every address that answers, the app shows its 16-bit register fingerprint (0x3000..0x301F and 0x3100..0x310F,
where `FF` means no answer). Those bytes name the exact part against a datasheet, and the `SENSOR.TXT` file holds them
for a closer look.

The app builds the probe deterministically and checks it against a fixed hash, so a wrong byte can't produce a usable
file ([The files the app makes](#the-files-the-app-makes)).

To undo it on an SP140A camera, flash the stock restore file, which the app also makes. If the probe names an OV9732,
skip the stock restore file, which can't take pictures on that camera, and flash a v012 build, stock or with mods
([The mods on v012](#the-mods-on-v012)), or v006 with the OV9732 port
([below](#repair-an-ov9732-camera-the-ov9732-port-on-v006)).

## Repair an OV9732 camera (the OV9732 port on v006)

This is for a camera whose image sensor is an OmniVision OV9732 (Identify on photos from before the flash, or
[the probe](#identify-the-image-sensor-the-sensor-id-probe), names it) and which was flashed with the public build, made for a SuperPix SP140A, so photo and video
mode no longer work. Such a camera can also take the v012 builds (stock v012, or [v012 with mods](#the-mods-on-v012)), since v012 is
the factory firmware of OV9732 cameras. v012 with Volume and LED rows went onto a camera running v006 with the OV9732
port and Volume and LED rows, and works. v006 with the OV9732 port is the public build with the OV9732
driven in place of the SP140A: its start-up table, its exposure and gain, its black level, and image tuning fitted on
a colour card against a working camera. With the Volume and LED rows ticked too, it also adds two setup-menu rows
after FORMAT, **Volume** (system sounds, 0 to 8; it also sets playback volume, and 0 mutes) and **LED** (the automatic flash light,
On as before or Off). Boot and update code are the public build's, byte for byte. What each build changed and what it
measured on a camera is in [OV9732 exposure, colour and white balance](15-OV9732-AE-WB-FLASH-PORT.md). Why the repair is
needed is in [OV9732 repair scope](14-OV9732-REPAIR-SCOPE.md).

The app builds it deterministically and checks it against a fixed hash, so a wrong byte can't produce a usable file
([The files the app makes](#the-files-the-app-makes)).

The stock restore file is no way back for this camera. It doesn't drive the OV9732, so with it photo and video stop
working again. The way back to stock is stock v012 (base v012 with no mods ticked), the factory firmware of OV9732
cameras. To drop the Volume and LED rows, go back to v006 with the OV9732 port alone. The app makes that one too. In
**Flash custom firmware**, pick base v006 and tick only the OV9732 port. Before you update the app or flash another
build, you can also keep a copy of the file that works: make it in **Flash custom firmware**, then tap **Save a copy
to the phone** in **Advanced**. Like any update, the flash itself must not be interrupted.

## Where the dumps come from

Every file the app makes, except a pick on base v008_test, is made from one of two dumps, and only from these
exact files (by SHA-256). The v008_test base comes from its own public file
([Picking a base and mods](#picking-a-base-and-mods)).

| Dump | Copy the app takes | SHA-256 |
| --- | --- | --- |
| Stock (build v006, SP140A) | [the-dumping-grounds/charmera-firmware-v006](https://github.com/the-dumping-grounds/charmera-firmware-v006), `charmera-v006-2025-10-14-clean.bin`: what **Download** fetches, at a fixed commit | `75b911e9fc62cb3e4b461501916d1f1c336a28587296f03eba1f9d90cbc01082` |
| Stock, original | benbadio/keychain-camera-plus `dumps/charmera_stock_latest.bin` (commit 209c25a), picked by hand or kept by an older app | `cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f` |
| Factory (build v012, OV9732) | [the-dumping-grounds/charmera-firmware-v012](https://github.com/the-dumping-grounds/charmera-firmware-v012), `charmera-v012-2026-01-12-clean.bin`: what **Make this firmware** fetches for a v012 pick when the phone has no factory dump, at a fixed commit | `63e396846f949600c2c1881920860821f2fcd70a70ac79ca54eebaee4a0ccb1c` |
| Factory, original | a private read of a v012 camera, the one the cleaned copy was made from | `08a7d993996b93cf2468fe08d70a0e77d5e1a9d6ff16bf647f5c7a0edf5d85ce` |

A cleaned copy has the original's bytes up to 0x170000 (boot header, code, data, resources) unchanged. Only its factory
settings record is kept, and its stored photos are erased. So each file made from it is the same firmware as the one
made from the original, under its own name and SHA-256 (both are in the table below), and the camera starts with factory
settings. The candidates C, A and B are the same file from either copy. The app recognises both files of each build,
so a copy saved by an older app still counts as that build.

### The files the app makes

The app builds each file deterministically and keeps it only when its SHA-256 is the one listed here. "Cleaned" is
the file made from the cleaned copy, which is what you get from a download. "Original" is the same build made from
the original dump (for v012, the private read).

| Build | Made from | File | SHA-256 |
| --- | --- | --- | --- |
| Stock restore file (stock v006) | cleaned | `JH_5408202610261427_0FB74C24.bin` | `9b8841d7b0674ca047a67b06594b92e67d17c387b6fab11f4b5191abc644c3ec` |
| Stock restore file (stock v006) | original | `JH_5408202610261427_0F065D55.bin` | `b10a2f1c6302afbad8c205bd2f1e23c0ed244f6f3c553b513f2bd41f4b446906` |
| v006 with the sensor-ID probe | cleaned | `JH_5408202611082300_0FB74B1C.bin` | `02e2729cffa4dd835f2b138b4ff4df9be89baecfb96f473665dc4b3411e7d2d5` |
| v006 with the sensor-ID probe | original | `JH_5408202611082300_0F065C4D.bin` | `6a7aa6d66914805e741d7da3ae743a5250f0f1ef90622b3acc9da4bb75d2547b` |
| v006 with the OV9732 port | cleaned | `JH_5408202611110023_0FB70DF7.bin` | `554d3c2fb69185df5020c5521eaaf02f59d9282d51a1384c56a91aec586f1466` |
| v006 with the OV9732 port | original | `JH_5408202611110023_0F061F28.bin` | `f0cced2e9cb2c1925d6d20bc54c8002c1543f9710bf5ab90032db316572507b2` |
| v006 with the OV9732 port and Volume and LED rows | cleaned | `JH_5408202611120024_0FB7027B.bin` | `8a46998f48410b1f03f4e6f9231b6896757f106b4d4d6854b8cce4fbd6e2dc5b` |
| v006 with the OV9732 port and Volume and LED rows | original | `JH_5408202611120024_0F0613AC.bin` | `0919c5d857f855b11424407211a132ac61f4c91852e01af583bd6b76c85b1c69` |
| v012 with Volume and LED rows | cleaned | `JH_5408202611130025_0FB76FE9.bin` | `bb0c826e4c5e27d5183b6a4e67d45434cc0d6196f8d0f914b396fb42ae0bd36a` |
| v012 with Volume and LED rows | original | `JH_5408202611130025_0FA3BF37.bin` | `a5495ef4a7ab73e95e7f32351c7dca5dcfaa6d3c51f29860c017f547bbe1d3d0` |
| v012 with Volume and LED rows and EXIF dates | cleaned | `JH_5408202611130026_0FB73D2E.bin` | `be3cb3ec0ed062c0772aaf2ecb9c1c80000b64e6ac8da11cd649ebde80719738` |
| v012 with Volume and LED rows and EXIF dates | original | `JH_5408202611130026_0FA38C7C.bin` | `617770c3f3c9d880f93f06043090d3d07909c845633ca08e9c183deb44fa06d2` |
| v012 with Volume and LED rows, EXIF dates, boot to last mode and capture flash | cleaned | `JH_5408202611170027_0FB73A48.bin` | `89d8b61e8e01e7e89807c766e01e6106eb589b4f927f69b9e50b3286430c0639` |
| v012 with Volume and LED rows, EXIF dates, boot to last mode and capture flash | original | `JH_5408202611170027_0FA38996.bin` | `108af2997b108a05f8edc72a339705e77170061c042eb3b30857ff641b266968` |

Made from the cleaned copy, the stock restore file has the same bytes as the withdrawn candidate C, which was the stock
software with factory settings and no photos. Every other pick, such as stock v012 or a v012 mix that isn't in this
table, has its file name and SHA-256 in `charmera_toolkit/recipes.json`.

## Older build names

Up to app 0.31, this project numbered its builds as it made them. The numbers were only a running build counter, and
they span two bases. The camera never shows them. Setup > Version reads `JH5408  20250520` on every build. Now the app
and `charmera build` name a build by its base and its mods. If you have an old file or old notes, this is what each
number is now:

| Older name | Name now |
| --- | --- |
| v0.21, v0.22 | earlier stages of the OV9732 port on v006. Nothing makes them any more. |
| v0.23 | v006 with the OV9732 port |
| v0.24 | v006 with the OV9732 port and Volume and LED rows |
| v0.25 | v012 with Volume and LED rows |
| v0.26 | v012 with Volume and LED rows and EXIF dates |
| v0.27 | v012 with Volume and LED rows, EXIF dates, boot to last mode and capture flash |

Load a file of v0.23 to v0.27 and the app's file check details say "Older versions of the app called this v0.27" (or
whichever it was). `charmera build --list` and `charmera verify` add the same note for those five. The old numbers stay in a few places
on purpose. Update file names keep the stamps they've always had, such as `JH_5408202611130026_….bin`, since the
camera's updater needs a stamp in the name. For the five numbered builds, the last four digits of the stamp were the
number. `JH_5408202611130026_….bin` is v0.26. Don't name a file by its stamp now, though. Stock v012, stock v008_test
and every newer mix share the stamp `202611170027` with v0.27. To find out what a file is, load it with **Use a file on
the phone** or run `charmera verify`. The check names it by its contents. Photos taken with EXIF dates say Software
`v0.26` whichever build took them, because that text is part of the mod's code. Flash log entries an older app saved keep the name they were saved
with. The app's own version (0.32 and so on) is a separate number.

## Without the app

Everything above is the app's route, and the files it makes need nothing from this section. This is the
route without it: a file made with the toolkit on a computer or in Termux, then copied to the card by hand (or picked
in the app's Advanced page to be checked and written).

The camera flashes itself. On power-on it looks in the card root for a file named `JH_5408<stamp>_<sum>.bin`, checks the
sum in the name, and writes the file to its own flash ([Image layout](11-FORMAT.md)). So all the phone has to do is put
the right file, with its name unchanged, in the top folder of the card.

### 1. Get the update file

There are two kinds of file:

- A reviewed build, the same files the app makes: `charmera build --list`, then for example `charmera build --base
  v012 --mods volume-led,exif --download --out out/` (the base Identify named; `--base v012` with no `--mods` is stock
  v012). Check it with `charmera verify <the file>`, with `--stock <your backup>.bin` if you have one. Without a
  backup, verify warns that nothing confirms what the camera runs.
- A file from your own backup, the dump of this camera's chip from
  [step 1 of The first flash](01-FIRST-FLASH.md#1-know-what-your-camera-runs-identify-or-a-backup-of-its-chip), checked against that
  backup. Here the backup is both the file's input and its `--stock`. Never give the public dump as `--stock`.
  Compared with itself, a file always matches, and then nothing is compared with your camera.

Either build it on a computer or the Steam Deck and copy it to the phone, or build it on the phone in Termux (from
F-Droid). The toolkit is plain Python with no extra packages, so it should run there, but that's untested.

```
pkg install python git
pip install git+https://github.com/daemond-dev/charmera-toolkit
charmera pack --input <your backup>.bin --out out/restore/ --i-understand-this-file-flashes-a-camera
charmera verify out/restore/JH_5408<stamp>_<sum>.bin --stock <your backup>.bin
```

`charmera build --dump <your backup>.bin --mods ... --out out/` builds the same mods from your own read of a v006 or v012
chip, if its firmware is still stock v006 or v012 byte for byte (a read taken before the camera was ever flashed).
The file keeps your camera's settings, isn't a reviewed file, and says so.

Without git, download the repository as a ZIP in the phone's browser and run
`pip install ~/storage/downloads/charmera-toolkit-main.zip` instead.

For a mod that changes the code or the resource pack (sound data, frames, the boot logo, `tweak`), add
`--allow-modified` to the `verify` line. `verify` refuses any change to bytes 0..0x170000 unless it is told the change
is intended ([The first flash, step 3](01-FIRST-FLASH.md#3-verify)).

Run `termux-setup-storage` once if you want Termux to read files from the phone's Downloads folder
(`~/storage/downloads`).

### 2. Get the card readable by the phone

This is the real obstacle for a card in a reader or the phone's slot. A card the camera has used is exFAT with its
"volume dirty" flag set, because the camera never clears it, and Android refuses to mount such a card ([SD card
problems](05-CARDS.md)). There are two ways round it:

- On a computer or the Steam Deck, clear the flag without erasing anything:

  ```
  charmera card-fix /dev/sdX --clear-dirty     # Linux, the whole card; on Windows the card's drive letter, e.g. E:
  ```

  `card-check` first tells you which disk is the card and prints this command for it. Finding the right disk and
  unmounting it are covered in [SD card problems](05-CARDS.md#find-the-right-disk-first). Don't use `--format-fat32`
  for this, because it erases the card.
- Or format the card in the phone (Settings, Storage, the SD card, Format, as portable storage). This erases the card,
  photos included, and Identify needs those photos. Copy them off first, and run Identify on them before you format if
  you haven't. Android writes a clean exFAT or FAT32 volume. The camera reads both file systems, so it should read a
  phone-formatted card, but that's inference, not tested.

The flag comes back once the camera has written to the card again.

### 3. Copy the file to the card root

Use the phone's Files app to copy the `.bin` file to the top level of the SD card, not into `DCIM` or any other folder.
Then check that the name is exactly what `pack` printed, including the 8-character sum after the underscore. Some apps
add `(1)` or change case when a file of the same name exists, and a renamed file is either ignored or rejected with
"Program error". Delete any older `JH_5408*.bin` from the card root first.

Then have the copy checked where it lies: pick it in the app's Advanced page (**Use a file on the phone**), with your
backup chosen there. If the check fails, delete the copy before the card goes into the camera. With no backup chosen
the app still takes it, with the loud unknown-file warning, since nothing has compared it with this camera, so don't
leave it on the card unless you know it fits.

Before you take the card out of the phone or the reader, eject it (Settings, Storage, the card, Eject), so that
everything written has reached the card.

`charmera stage` does these checks for you on a computer. In Termux it probably can't help. Termux can normally only
write to its own folder on a removable card (`Android/data/com.termux/files`), not to the root, which is where the
camera looks.

### 4. Flash

Go through the list in [Before you flash from a phone](#before-you-flash-from-a-phone) once more: you know what the
camera runs, your backup (if you made one) is kept somewhere safe, it's an original 2025 series body, the battery is
fully charged, and the stored photos are saved or given up. Then follow [step 5 of The first
flash](01-FIRST-FLASH.md#5-flash-the-point-of-no-return):

1. Camera off, card in, then switch it on. The camera looks for the file only in the first five seconds after
   power-on.
2. "Upgrade NN%" advances once per 64 KiB block, about 15 to 20 seconds in all. Don't touch the key or the card while
   it counts. "Program error" means the sum in the name didn't match and nothing was erased. The camera switches itself
   off. Take the card out and check the file again.
3. At 100% the camera stays on its last screen. Nothing reboots by itself, and the camera never deletes or renames the
   file. Take the card out, then hold the power key about a second to switch the camera off.
4. Delete the update file from the card (with the app's **Remove update files from the card**, or the Files app)
   before the card goes back into the camera. Never switch the camera on with the update file on its card except to
   flash, or it flashes again at every power-on.

## Through the camera's own USB port

When it's connected by USB, the camera presents its card as a storage device (USB ID 1B3F:8301, "GENPLUS USB-MSDC DISK
A"), so the card can stay in the camera. On 2026-11-06 the drive appeared on an Android phone connected over USB-C, and
the app wrote two files to it: candidate C (app version 0.3), then the stock restore file (version 0.4). The camera
flashed each one at its next power-on, and flashed C a second time when the cable was plugged into it while it was
switched off. The cleanup over the same cable worked too. Neither file fitted that camera, which is why the first rule
is to know what the camera runs.

The same run showed two things. Plugging the cable into a switched-off camera switches it on and runs the updater from
the card, so never connect a camera with an update file on its card unless you want that flash. And the camera looks
for the file only in the first five seconds after power-on, so it doesn't notice a file added while it runs. In that
run the camera's drive mounted and took the writes. Whether Android can refuse the camera's drive for the dirty flag,
as it refuses the same card in a reader, hasn't been tested. If it ever offers to format it, don't: that erases the
photos Identify reads. Clear the flag with `card-fix` on a computer instead ([section 2](#2-get-the-card-readable-by-the-phone)).
The app's screens for this route are in its README: [the app's steps](../android/README.md#use).

1. Connect the camera the way the app's Camera drive step says: switch it off if it's on, take the card out, switch it
   on with its power key, wait 10 seconds, put the card back in, then connect it to the phone with a USB-C data cable.
   Pick the camera's drive in the app and write the file, as for a card reader.
2. Eject the drive (the app's **Eject the drive** opens its page; or Settings, Storage, the USB drive, Eject), then
   unplug the cable.
3. With a fully charged battery and no cable, switch the camera off, then on again. It only looks for the file in the
   first five seconds after power-on, so it didn't notice the file while it was running. The cable comes out first
   because plugging it in powers the camera on and runs the updater, and a flash should start on your key press, with
   the battery charged, not on a cable.
4. At 100% the camera stays on its last screen. Take the card out of the camera, then hold the power key about a second
   to switch it off.
5. To remove the file, switch the camera on without its card and wait 10 seconds (the updater looks at the card only in
   the first five seconds after power-on), insert the card, connect the camera to the phone, and remove the update file
   with the app. A card reader works as well.

Never switch the camera on with the update file on its card except to flash.

## What needs a computer

- The CH341A route (`probe`, `backup`, `restore`). flashrom needs direct access to the programmer over USB. No
  maintained way to run it on an unrooted phone is known. A rooted phone with an OTG cable might manage, but that's
  untested guesswork. The Steam Deck runs flashrom and the toolkit's backup and restore scripts like any Linux machine
  ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)).
- `card-check` and `card-fix`, which open the raw card device. Android doesn't give apps that access without root.
- The donor route, for a camera whose own firmware is gone and whose sensor no reviewed build drives
  ([Recovery, section D](02-RECOVERY.md#the-donor-route)). The app has no donor route for an unreviewed dump from
  another camera. The v012 builds are reviewed builds for OV9732 cameras, and an OV9732 or SP140A camera that boots
  but can't take pictures is fixed from the card with a build for its sensor. With a backup chosen, the app refuses a
  file of your own whose image-sensor set-up differs from that backup.

---

Reading order: 6 of 15 · previous: [SD card problems](05-CARDS.md) · next: [Command reference](07-USAGE.md) · [all docs](README.md)
