# Using an Android phone

Reading order: 6 of 15 · previous: [SD card problems](05-CARDS.md) · next: [Command reference](07-USAGE.md) · [all docs](README.md)

Short version: a phone can do the SD-card route, which covers every normal flash. It cannot do the chip-level
route (CH341A), which is what you need for the backup that every flash requires, and for a camera that no longer
boots. On 2026-11-06 the app wrote two files to one camera's card over the camera's own USB-C cable, candidate C
(app version 0.3) and then the stock restore file (version 0.4), and the camera flashed each; it flashed C a second
time when the cable was plugged into it while it was switched off. The writes, the flashes and the cleanup worked
as described here. Neither file fitted the camera, which is why the backup is a hard stop below. The parts that are
still inference are marked.

## Before you flash from a phone

The same rules apply as in [step 5 of The first flash](01-FIRST-FLASH.md#5-flash-the-point-of-no-return):

- No backup of your own camera, no flash. The backup is a dump of this camera's flash chip, read with a CH341A clip
  on a computer ([step 1 of The first flash](01-FIRST-FLASH.md#1-back-up-your-cameras-chip-no-backup-no-flash)). The app
  does not write to the card without it, and compares the update file's image-sensor set-up with it; `verify
  --stock <backup>` does the same on a computer. Whether a firmware fits a camera cannot be told any other way, and
  only a dump read before the camera was ever flashed tells it: after a flash the dump holds the flashed firmware, and
  when it is one of the known builds the app shows a matching sensor set-up as a warning, not a pass
  ([After a flash](01-FIRST-FLASH.md#after-a-flash)). The app cannot tell whose chip a dump came from: it refuses the
  public dumps (the two public stock dumps and the cleaned copies of v006 and v012) and any read named as unverified
  (`.unverified-read<n>.bin`), so give it only a dump read
  from this camera with a clip, from two reads that agreed.
- Original 2025 series only: matte body, yellow Kodak boot screen. Never a Millennium Edition (blue boot
  screen) or a CLOT edition ([Recovery, section D](02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera)). The body
  alone proves nothing: an original-series camera was left unable to take pictures by the public dump.
- Charge the battery fully before every flash. A power loss while the camera programs itself leaves a camera
  that only a chip programmer can bring back ([Recovery, section C](02-RECOVERY.md#c-an-interrupted-flash)).
- A 0x1E0000-byte file, such as the stock restore file the app makes, replaces the two photos the camera keeps in its
  own memory. The stock restore file carries the two photos of the camera it was copied from, but its photo list is
  empty, so the camera does not show them; your own two are gone all the same. A 0x169000-byte file
  (`pack --official-size` on a computer) leaves the photo data in place, but the camera empties its photo list, so it
  no longer shows them. If yours matter, copy the `SPIDCIM` folder off the card first
  ([step 5 of The first flash](01-FIRST-FLASH.md#5-flash-the-point-of-no-return)).

## The app

[Charmera Card](../android/README.md) does the card part on the phone: it downloads the public stock dump, makes the
published stock restore file from it, plus two reviewed patches built from that same file: the sensor-ID probe and the
OV9732 bring-up v0.24; and from the factory build v012 (its public cleaned copy, which it downloads, or the owner's
own read, picked on the phone), firmware v0.25, v0.26 and v0.27; and, with **Custom**, any other reviewed
combination of a base and its mods ([Custom build](#custom-build-pick-a-base-and-mods)). It builds nothing else. It compares the restore file with your backup, writes it to
the camera's card over the camera's USB-C cable, reads it back, and walks you through the flash and the cleanup. Every
reviewed file goes to the card without a backup: it warns when nothing confirms which firmware the camera runs, and
leaves the call to you. A file it does not know needs a backup on record. It also saves
the camera's photos and videos as a zip. The current version is 0.29. On 2026-11-11 it wrote the OV9732 bring-up
v0.24 (Flash custom firmware) to the owner's camera over the camera's USB-C cable, and the camera flashed it; what the
camera showed afterwards is in [OV9732 exposure, colour and white balance](15-OV9732-AE-WB-FLASH-PORT.md).
Download [charmera-card.apk](https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk)
on the phone. It opens on a Home screen with one button per route, and each route is its own screen of numbered
steps, each result shown right under its button ([the app's routes](../android/README.md#use)):

- **Back up photos:** a zip of the photos and videos in `DCIM` and `SPIDCIM`, read back and checked entry by entry;
  optionally with their dates fixed in the copies, optionally with the copies named by date and time (**Name the
  copies by date and time**, on by default: `20261111_114931.jpg` in place of the card's name, in the same folder,
  when the time can be read), and optionally deleted from the camera, only those whose entries matched. With delete
  on, the zip also keeps each date-fixed file as the camera wrote it in `originals/`, under its name on the card.
- **Flash custom firmware:** writes the firmware version picked in its menu, newest first: v0.27, v0.26
  ([below](#firmware-v026-exif-dates)), v0.25 ([below](#firmware-v025-the-cameras-own-factory-firmware)) or the OV9732
  bring-up v0.24
  ([below](#repair-an-ov9732-camera-the-ov9732-bring-up)); then eject, flash, remove the update file, and a check of
  the header of a photo taken afterwards. The menu starts on the newest version (v0.27: with no factory dump on the
  phone, making it downloads the factory build's cleaned copy) and remembers a version you pick. A Repair-version
  file loaded with **Use a file on the phone** becomes the pick, so the step and the file always name one version.
  The menu's last row, **Custom: pick base and mods**, builds any other reviewed combination
  ([below](#custom-build-pick-a-base-and-mods)).
  Your factory dump picked on another route is kept, but only this route (and Advanced) makes firmware from it.
- **Identify this camera's firmware:** reads the headers of the photos on the card and says which firmware family
  took them, with what to check on the camera ([below](#identify-this-cameras-firmware)). It writes nothing.
- **Identify the image sensor:** writes the sensor-ID probe ([below](#identify-the-image-sensor-the-sensor-id-probe))
  and reads its result.
- **Restore stock firmware:** compares the stock restore file with this camera's backup, then writes it.
- **Test shots:** shows a colour card on the phone and saves the shots taken of it as a zip with a manifest.
- **Flash log** (what was written, flashed, removed and saved, on this phone) and **Advanced** (the old all-in-one
  steps: any update file, **Use a file on the phone**, save a copy, write, remove, read the sensor result).

Every screen shows which camera drive the app has saved and whether it is connected. The app cannot eject a drive by
itself: it opens the drive's own storage page, and says "Ejected" only once Android reports the drive unmounted.
Version 0.4, published before the backup step, had no backup step and did not refuse the withdrawn candidates C, A and
B; it was taken down on 2026-11-06, and if you still have it installed, do not flash with it. The rest of this page is
the same job done by hand, and what the app cannot help with.

Which way the card reaches the app depends on the Android version:

- Android 11 and later: Android does not let an app pick the top level of a card in the phone's own SD slot
  ("Use this folder" is greyed out there). Leave the card in the camera and connect the camera with its USB-C
  cable: the owner has confirmed that the camera shows its card as a drive on the phone
  ([below](#through-the-cameras-own-usb-port)). A USB card reader works too. Both can be picked at their top level.
- Android 7 to 10: the phone's own SD slot works as well.
- Only the slot, on Android 11 or later: copy the update file to the card's top level with the Files app
  ([section 3](#3-copy-the-file-to-the-card-root)), then pick that copied file in the app's Advanced page
  (**Use a file on the phone**), with your backup on record there, to have it checked where it lies. If the check
  finds a problem, or there was no backup on record to compare the file with, delete the copy before the card goes
  into the camera. Delete it with the Files app after the flash too.

## Identify this camera's firmware

This route needs no flash, no backup and no update file, and it writes nothing: not to the card, not to the flash
log, not to a flash run. Choose the camera's drive, then tap **Identify from the photos on the card**. The app reads
the start of photos in `DCIM` (only up to the start of the picture; videos are only counted) and sorts them by file
time, as the toolkit's `identify` does. Photos with no file time are left out of the dating: the answer counts them
("N photos have no file time and were left out of the dating") and the evidence names them. It finds the oldest and
the newest camera-written photo (reading at most 500 from each end), then reads 500 photos evenly spaced by date,
those two always among them (every photo when there are no more), and looks there for a change of family:

| Header | Family |
| --- | --- |
| 0x590 bytes, comment "GPEncoder" and six spaces, EXIF Make Generalplus, Model CBB3, Software 20150615 | v006 family (stock v006 or v008_test, SP140A cameras) |
| 0x270 bytes, comment "GPEncoder", no EXIF | v012 family (stock v012, OV9732 cameras) |
| 0x340 bytes, EXIF Make Kodak, Model Charmera, right after the start | our EXIF mod (v0.26 or later): v012 family, not stock |

Photos it does not recognise (from another camera, edited, or an `exif-fix` copy) are counted and left out; when
the newest files on the card are such photos, a note says how many were skipped. The route answers "which
stock firmware does this camera run?", assuming a stock camera, to pick the base to build on: when every dated
camera-written photo is one family it says "This camera runs stock v012" (or "stock v006 or v008_test"), with that
base (v012, or v006 for the v006 family). Confidence is high when every dated photo was read and at least 2
camera-written photos agree, medium for a single photo or a capped read. A v006-family answer adds one line: our
v0.21-v0.24 write the same header.

Being flashed or modded is the exception, and the answer says so in a line of its own, "This camera does not look
stock: ...":

- **our EXIF mod's header** on any photo: the mod is installed (between which dates, when bare photos come before
  it). The base stays v012, as the mod is built on it, with high confidence. Bare and EXIF-mod photos are one family;
- **two families on one card**: the camera was flashed between them, or the card holds photos from two cameras. The
  base then comes from the oldest family (photos from before a flash show the stock firmware), never the newest,
  with low confidence.

The answer gives the base, a confidence and the evidence, photo by photo. What a photo cannot say:

- it cannot tell v006 from v008_test: both are stock builds for SP140A cameras and write the same header; either
  base works the same for our purposes;
- photos show the firmware that took them: on a camera that was flashed, or that freezes in Photo mode, only photos
  from before the flash say what fits;
- a copy fixed with the toolkit's `exif-fix` carries no Software tag, which the camera always writes: with Make
  Kodak and Model Charmera it is not v0.26's header, and with `--keep-make` (Generalplus, CBB3) it is not a
  v006-family photo; the app does not recognise either;
- the dates and the order come from the file times, which come from the camera's clock.

The third step lists what the owner can check on the camera itself
([Telling builds apart on the camera](CAMERA-UI.md#telling-builds-apart-on-the-camera)). The app keeps the suggested
base, and **Custom** on Flash custom firmware starts on it (v012 for the factory build, v006 for the v006 family).

## Identify the image sensor (the sensor-ID probe)

If a camera boots to its menu but freezes when it enters photo or video mode, the cause may be an image sensor the
firmware does not drive. The app can build a **sensor-ID probe** (the "Identify the image sensor" route) to find out
which sensor is fitted. It is a diagnostic, not a repair: it does not make the camera take pictures.

The probe is the stock restore file with a small reviewed patch. Its boot code, update code and image-sensor table
are the stock restore file's byte for byte; only a dead debug routine and the one call that writes the sensor set-up
are changed. When the camera next enters photo mode, the patch walks the sensor bus across every device address
(0x08..0x77) with the firmware's own read routine, and for each address that answers it reads the 16-bit chip-ID
registers (0x3000..0x301F and 0x3100..0x310F), writes the result to `SENSOR.TXT` in the card's root, then stops as the camera does now. Because it changes
no boot or update code and the stock restore file puts it back, it is one of the reviewed files the app writes without
a backup (the others are the Repair versions: v0.24, v0.25, v0.26 and v0.27).

After flashing it, take the update file off the card first — otherwise the camera re-flashes it on every boot
instead of running it — then boot the camera and open photo mode so the probe runs. Bring the card back to the phone
and tap **Read the sensor result from the card** (the route's last step). The result reads as:

- **A named sensor** — for example "OmniVision OV2680" or "SmartSens SC…". The app read the sensor's 16-bit chip-ID
  registers and matched them, so the exact part is known and a firmware built for it becomes possible.
- **A sensor at some address, not yet named** — it answered, but its chip ID was not at the registers the app reads.
  The app shows the raw register fingerprint and what to try next (often an 8-bit read, for an older part).
- **Nothing answered at any address** — the sensor is not responding at all (disconnected, unpowered, or dead).
  Reseat the sensor's ribbon and scan again; if it still answers nowhere, it is a hardware fault no firmware can fix.

For every address that answers, the app shows its 16-bit register fingerprint (0x3000..0x301F and 0x3100..0x310F,
where `FF` means no answer). Those bytes name the exact part against a datasheet; the `SENSOR.TXT` file holds them for
a closer look.

The app builds the probe deterministically and checks it against a fixed hash, so a wrong byte cannot produce a
usable file. The exact artifact:

- file name: `JH_5408202611082300_0F065C4D.bin`
- SHA-256: `6a7aa6d66914805e741d7da3ae743a5250f0f1ef90622b3acc9da4bb75d2547b`

Made from the cleaned copy of the stock dump the app now downloads (see
[Where the dumps come from](#where-the-dumps-come-from)), it is the same code, with only the factory settings record
and no stored photos:

- file name: `JH_5408202611082300_0FB74B1C.bin`
- SHA-256: `02e2729cffa4dd835f2b138b4ff4df9be89baecfb96f473665dc4b3411e7d2d5`

To undo it, flash the stock restore file, which the app also makes: `JH_5408202610261427_0F065D55.bin` from the
original dump, or `JH_5408202610261427_0FB74C24.bin` (SHA-256
`9b8841d7b0674ca047a67b06594b92e67d17c387b6fab11f4b5191abc644c3ec`) from the cleaned copy. The second has the same
bytes as the withdrawn candidate C, which was the stock software with factory settings and no photos.

## Repair an OV9732 camera (the OV9732 bring-up)

For a camera whose image sensor is an OmniVision OV9732 (the probe above names it) and which was flashed with the public
build, made for a SuperPix SP140A, so that photo and video mode no longer work. The bring-up is the public build with
the OV9732 driven in place of the SP140A: its start-up table, its exposure and gain, its black level, and image tuning
fitted on a colour card against a working camera. From v0.24 it also adds two setup-menu rows after FORMAT: **Volume**
(system sounds, 0 to 8; it also sets playback volume, and 0 mutes) and **LED** (the automatic flash light: On as before,
or Off). Boot and update code are the public build's, byte for byte. What each
version changes and what it measured on a camera is in
[OV9732 exposure, colour and white balance](15-OV9732-AE-WB-FLASH-PORT.md); why the repair is needed is in
[OV9732 repair scope](14-OV9732-REPAIR-SCOPE.md).

The app builds it deterministically and checks it against a fixed hash, so a wrong byte cannot produce a usable file.
The exact artifact (firmware v0.24):

- file name: `JH_5408202611120024_0F0613AC.bin`
- SHA-256: `0919c5d857f855b11424407211a132ac61f4c91852e01af583bd6b76c85b1c69`

Made from the cleaned copy of the stock dump, the same code with the factory settings record and no stored photos:

- file name: `JH_5408202611120024_0FB7027B.bin`
- SHA-256: `8a46998f48410b1f03f4e6f9231b6896757f106b4d4d6854b8cce4fbd6e2dc5b`

The stock restore file (`JH_5408202610261427_0F065D55.bin`) is no way back for this camera: it does not drive the
OV9732, so with it photo and video stop working again. The way back from v0.24 is the previous version's file,
firmware v0.23:

- file name: `JH_5408202611110023_0F061F28.bin`
- SHA-256: `f0cced2e9cb2c1925d6d20bc54c8002c1543f9710bf5ab90032db316572507b2`

The current app does not make it; it makes only the current version. So before you update the app or flash a newer
version, keep a copy of the file that works: make it in **Flash custom firmware**, then tap **Save a copy to the
phone** in **Advanced**. Like any update, the flash itself must not be interrupted.

### Firmware v0.25 (the camera's own factory firmware)

v0.25 is the owner's camera's own factory firmware (build v012, read off its chip on 2026-11-13), which drives the
OV9732 natively, with v0.24's **Volume** and **LED** rows relocated to it. Nothing of the v0.21..v0.24 sensor work is
carried over: the factory build has its own start-up table, exposure, white balance and colour. The camera starts with
Volume 5 and LED On; photos carry no EXIF date.

The factory dump is in neither this repository nor the app. The app gets it one of two ways, and keeps a copy in its
own storage that it checks again on each use:

- **Download (the default).** With no factory dump on the phone, **Make firmware v0.25** (or v0.26, v0.27) downloads the
  public cleaned copy, `charmera-v012-2026-01-12-clean.bin` from
  [the-dumping-grounds/charmera-firmware-v012](https://github.com/the-dumping-grounds/charmera-firmware-v012) at a
  fixed commit, and uses it only if its SHA-256 is
  `63e396846f949600c2c1881920860821f2fcd70a70ac79ca54eebaee4a0ccb1c`.
- **Your own read.** Pick it with **Use a file on the phone** (for this camera's owner,
  `charmera_flash_20261113T193627Z.bin`, SHA-256 `08a7d993996b93cf2468fe08d70a0e77d5e1a9d6ff16bf647f5c7a0edf5d85ce`).
  It replaces the downloaded copy and is used from then on. The cleaned copy, downloaded in a browser, can be picked
  the same way when the phone cannot reach GitHub. The app makes v0.25 from it as the computer build does: the dump's first 0x1E0000 bytes, the
internal photo slots (0x180000..0x1CB000) blanked, and 172 authored bytes (the source holds only those). The checker
does not know the factory build (no SP140A-style sensor table), so for these exact bytes it shows that as a warning,
not a failure; every other check stands. The exact artifact:

- file name: `JH_5408202611130025_0FA3BF37.bin`
- SHA-256: `a5495ef4a7ab73e95e7f32351c7dca5dcfaa6d3c51f29860c017f547bbe1d3d0`

Made from the cleaned copy, the same code with the factory settings record:

- file name: `JH_5408202611130025_0FB76FE9.bin`
- SHA-256: `bb0c826e4c5e27d5183b6a4e67d45434cc0d6196f8d0f914b396fb42ae0bd36a`

The way back from v0.25 is v0.24's file, which the app makes: pick v0.24 in the menu. The flash check expects the
factory header (no EXIF block) after a v0.25 flash.

### Firmware v0.26 (EXIF dates)

v0.26 is v0.25 plus an EXIF block in every still photo, on the SD card and in the camera's own memory: Make `Kodak`,
Model `Charmera`, Software `v0.26`, and DateTime, DateTimeOriginal and DateTimeDigitized from the camera's clock. Set
the date in the camera's setup menu first: an unset clock gives `2025:01:01`. The still header grows from the factory
build's bare 0x270 bytes to 0x340: the SOI, an authored APP1 block of 0xD0 bytes, then the factory template's own
segments from SOF0 on. The camera starts with Volume 5 and LED On, as with v0.25.

The app makes it from your factory dump as it makes v0.25, then one more layer: 332 authored bytes (the APP1 block,
a new date-fill routine, and the instructions that point the photo writers at the new header) and one range of
0x26E bytes, the factory template from SOF0 on, that the app copies from your factory dump at run time (file 0x10753A
to 0x89712), so the source holds none of the vendor's bytes. The exact artifact:

- file name: `JH_5408202611130026_0FA38C7C.bin`
- SHA-256: `617770c3f3c9d880f93f06043090d3d07909c845633ca08e9c183deb44fa06d2`

Made from the cleaned copy:

- file name: `JH_5408202611130026_0FB73D2E.bin`
- SHA-256: `be3cb3ec0ed062c0772aaf2ecb9c1c80000b64e6ac8da11cd649ebde80719738`

The way back from v0.26 is v0.25's or v0.24's file, both in the menu. The flash check expects the 0x340-byte header
with its APP1 EXIF block (Make Kodak, Model Charmera) after a v0.26 flash.

### Firmware v0.27 (boot to the last mode, capture flash)

v0.27 is v0.26 plus two changes, both new and so far tested only in an emulator, not on a camera:

- **Boot to the last mode.** After power-on (and after the card's update check, which is unchanged) the camera goes
  straight into the mode last used, Picture or Video, instead of the mode menu; Picture the first time. A short press of
  the power button still opens the mode menu. The mode is kept in a spare settings byte (+0x61); the camera saves its
  settings on its own schedule, so a mode change can be lost if it is switched off within a few seconds.
- **Capture flash.** When a photo is taken the picture area goes black for 120 ms under the on-screen icons, inside the
  stock 200 ms shutter delay, so a shot shows even at Volume 0. If the camera stops sending preview frames during that
  delay, no flash shows; nothing else changes.

The app makes it as it makes v0.26, then one more layer of 202 authored bytes (build-v027/build_v027.py). Photos keep
v0.26's EXIF header (Software `v0.26`), so the photo check cannot tell v0.26 from v0.27: check v0.27 on the camera.
The way back is v0.26's file, in the menu. The exact artifacts:

- from the owner's read: `JH_5408202611170027_0FA38996.bin`, SHA-256
  `108af2997b108a05f8edc72a339705e77170061c042eb3b30857ff641b266968`
- from the cleaned copy: `JH_5408202611170027_0FB73A48.bin`, SHA-256
  `89d8b61e8e01e7e89807c766e01e6106eb589b4f927f69b9e50b3286430c0639`

### Custom build (pick a base and mods)

Since 0.28 the version menu's last row, **Custom: pick base and mods**, opens a base menu and one box per mod of
that base, each with its one-line description. The bases are v012 (the factory build, OV9732), v006 (the public
build, SP140A) and v008_test (a later SP140A build, which has no mods yet). Custom starts on the base the
**Identify this camera's firmware** route found, when it found one. A mod marked *experimental, not yet tried on a
camera* adds a warning line but is never blocked; a mod that cannot go with one already ticked (on v006, the
sensor-ID probe with the OV9732 port or the Volume and LED rows) is greyed out, with the reason.

The app makes the pick exactly as `charmera build` does on a computer (charmera_toolkit/recipes.py, from the same
recipes.json): v012 from the factory dump (your own read when the phone keeps one, else the public cleaned copy),
v006 from the public stock dump, v008_test from its public file
([the-dumping-grounds/charmera-firmware-v008_test](https://github.com/the-dumping-grounds/charmera-firmware-v008_test),
at a fixed commit, checked by SHA-256). Each mod is applied only over the bytes it was written for, and the result is
kept only when it is the reviewed file the recipes list for that base, those mods and that dump, under that file's
name; anything else is refused. The presets (v0.24 to v0.27) are made exactly as before. The flash check expects
the header the pick's mods give (the EXIF header with the EXIF mod, the factory header on v012 without it, the
public family's on v006 and v008_test).

Every pick is written here without a backup, whatever sensor its firmware drives: whether it fits the camera is
your call. The app warns, and does not refuse, in two cases. With no backup on record and no Identify answer for the
same sensor, the file step and the write confirmation say the camera's firmware is not confirmed and which sensor the
file drives. With a backup on record whose firmware drives the other sensor (an SP140A pick against an OV9732 backup),
the sensor line is a "Wrong sensor?" warning. A wrong-sensor flash boots and cannot take pictures; the card updater
still works, so flashing a file that fits undoes it. Stock v006 is the stock restore file itself, written on
**Restore stock firmware** (it compares the file with your backup) or on **Advanced**.
A combination the recipes mark experimental says so, in the warning line and in the write confirmation, even when each of its mods is
tested alone. The pick, base and mods, is remembered; a version picked in an older app is moved to its base and
mods.

## Where the dumps come from

The app makes every file from one of two dumps, and only from these exact files (by SHA-256):

| Dump | Copy the app takes | SHA-256 |
| --- | --- | --- |
| Stock (build v006, SP140A) | [the-dumping-grounds/charmera-firmware-v006](https://github.com/the-dumping-grounds/charmera-firmware-v006), `charmera-v006-2025-10-14-clean.bin`: what **Download** fetches, at a fixed commit | `75b911e9fc62cb3e4b461501916d1f1c336a28587296f03eba1f9d90cbc01082` |
| Stock, original | benbadio/keychain-camera-plus `dumps/charmera_stock_latest.bin` (commit 209c25a), picked by hand or kept by an older app | `cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f` |
| Factory (build v012, OV9732) | [the-dumping-grounds/charmera-firmware-v012](https://github.com/the-dumping-grounds/charmera-firmware-v012), `charmera-v012-2026-01-12-clean.bin`: what **Make firmware v0.25/v0.26** fetches when the phone has no factory dump, at a fixed commit | `63e396846f949600c2c1881920860821f2fcd70a70ac79ca54eebaee4a0ccb1c` |
| Factory, original | the owner's own read, `charmera_flash_20261113T193627Z.bin`, private | `08a7d993996b93cf2468fe08d70a0e77d5e1a9d6ff16bf647f5c7a0edf5d85ce` |

A cleaned copy has the original's bytes up to 0x170000 (boot header, code, data, resources) unchanged; only its
factory settings record is kept and its stored photos are erased. So each file made from it is the same firmware as
the one made from the original, under its own name and SHA-256 (both are listed above), and the camera starts with
factory settings. The candidates C, A and B are the same file from either copy. The app recognises both files of each
version, so a copy saved by an older app still counts as that version.

## What the phone has to do

The camera flashes itself. On power-on it looks in the card root for a file named
`JH_5408<stamp>_<sum>.bin`, checks the sum in the name, and writes the file to its own flash ([Image layout](11-FORMAT.md)).
So the phone's whole job is to put the right file, with its name unchanged, in the top folder of the card.

## 1. Get the update file

Build it from your own backup, the dump of this camera's chip from
[step 1 of The first flash](01-FIRST-FLASH.md#1-back-up-your-cameras-chip-no-backup-no-flash), and check it against
that backup. This route needs the backup as much as the app does: without one, do not use it. Never build the file
from the public dump, and never give the public dump as `--stock`: compared with itself, a file always matches, and
nothing is then compared with your camera.

Either build it on a computer or the Steam Deck and copy it to the phone, or build it on the phone in Termux
(from F-Droid). The toolkit is plain Python with no extra packages, so it should run there; that is untested.

```
pkg install python git
pip install git+https://github.com/daemond-dev/charmera-toolkit
charmera pack --input <your backup>.bin --out out/restore/ --i-understand-this-file-flashes-a-camera
charmera verify out/restore/JH_5408<stamp>_<sum>.bin --stock <your backup>.bin
```

Without git, download the repository as a ZIP in the phone's browser and run
`pip install ~/storage/downloads/charmera-toolkit-main.zip` instead.

For a mod that changes the code or the resource pack (sound data, frames, the boot logo, `tweak`), add
`--allow-modified` to the `verify` line: `verify` refuses any change to bytes 0..0x170000 unless it is told
the change is intended ([The first flash, step 3](01-FIRST-FLASH.md#3-verify)).

Run `termux-setup-storage` once if you want Termux to read files from the phone's Downloads folder
(`~/storage/downloads`).

## 2. Get the card readable by the phone

This is the real obstacle. A card the camera has used is exFAT with its "volume dirty" flag set, because the
camera never clears it, and Android refuses to mount such a card ([SD card problems](05-CARDS.md)). Two ways
round it:

- Format the card in the phone (Settings, Storage, the SD card, Format, as portable storage). This erases the
  card, so copy its photos off first. Android writes a clean exFAT or FAT32 volume. The camera reads both file
  systems, so it should read a phone-formatted card; that is inference, not tested.
- Or, on a computer or the Steam Deck, clear the flag without erasing anything:

  ```
  charmera card-fix /dev/sdX --clear-dirty     # Linux, the whole card; on Windows the card's drive letter, e.g. E:
  ```

  `card-check` first tells you which disk is the card and prints this command for it; finding the right disk
  and unmounting it are in [SD card problems](05-CARDS.md#find-the-right-disk-first). Do not use
  `--format-fat32` for this: it erases the card.

After the camera has written to the card again, the flag comes back.

## 3. Copy the file to the card root

Use the phone's Files app: copy the `.bin` file to the top level of the SD card, not into `DCIM` or any other
folder. Then check that the name is exactly what `pack` printed, including the 8-character sum after the
underscore; some apps add `(1)` or change case when a file of the same name exists, and a renamed file is either
ignored or rejected with "Program error". Delete any older `JH_5408*.bin` from the card root first. Then have the
copy checked where it lies: pick it in the app's Advanced page (**Use a file on the phone**), with your backup on
record there. If the check finds a problem, or the app has no backup on record to compare it with, delete the copy before
the card goes into the camera.

Before you take the card out of the phone or the reader, eject it (Settings, Storage, the card, Eject), so
that everything written has reached the card.

`charmera stage` does these checks for you on a computer. In Termux it probably cannot help: Termux normally
can only write to its own folder on a removable card (`Android/data/com.termux/files`), not to the root, which
is where the camera looks.

## 4. Flash

Check the list in [Before you flash from a phone](#before-you-flash-from-a-phone) once more: the backup of this
camera kept safe, original 2025 series body, battery fully charged, the stored photos saved or given up. Then follow
[step 5 of The first flash](01-FIRST-FLASH.md#5-flash-the-point-of-no-return):

1. Camera off, card in, then switch it on. The camera looks for the file only in the first five seconds after
   power-on.
2. "Upgrade NN%" advances once per 64 KiB block, about 15 to 20 seconds in all. Do not touch the key or the
   card while it counts. "Program error" means the sum in the name did not match and nothing was erased; the
   camera switches itself off. Take the card out and check the file again.
3. At 100% the camera stays on its last screen. Nothing reboots by itself, and the camera never deletes or
   renames the file. Take the card out, then hold the power key about a second to switch the camera off.
4. Delete the update file from the card (with the app's **Remove update files from the card**, or the Files
   app) before the card goes back into the camera. Never switch the camera on with the update file on its
   card except to flash: it would flash again at every power-on.

## Through the camera's own USB port

When connected by USB the camera presents its card as a storage device (USB ID 1B3F:8301, "GENPLUS USB-MSDC
DISK A"), so the card can stay in the camera. On 2026-11-06 the drive appeared on an Android phone connected over
USB-C, the app wrote an update file to it, and the camera flashed that file at its next power-on; the cleanup over
the same cable worked too. Two things the same run showed: plugging the cable into a switched-off camera switches it
on and runs the updater from the card, so a camera is never connected with an update file on its card unless that
flash is wanted; and the camera looks for the file only in the first five seconds after power-on, so it does not
notice a file added while it runs. The same dirty-flag problem applies. The app's screens for this route are in its
README: [the app's steps](../android/README.md#use).

1. Connect the camera the way the app's Camera drive step says: switch it off if it is on, take the card out, switch it on with
   its power key, wait 10 seconds, put the card back in, then connect it to the phone with a USB-C data cable. Pick
   the camera's drive in the app and write the file, as for a card reader.
2. Eject the drive (the app's **Eject the drive** opens its page; or Settings, Storage, the USB drive, Eject), then
   unplug the cable.
3. With a fully charged battery and no cable, switch the camera off, then on again. It only looks for the file
   in the first five seconds after power-on, so it did not notice the file while it was running. The cable comes
   out first because plugging it in powers the camera on and runs the updater, and a flash should start on your
   key press, with the battery charged, not on a cable.
4. At 100% the camera stays on its last screen. Take the card out of the camera, then hold the power key about
   a second to switch it off.
5. To remove the file: switch the camera on without its card and wait 10 seconds (the updater looks at the
   card only in the first five seconds after power-on), insert the card, connect the camera to the phone, and
   remove the update file with the app. A card reader works as well.

Never switch the camera on with the update file on its card except to flash.

## What needs a computer

- The CH341A route (`probe`, `backup`, `restore`). flashrom needs direct access to the programmer over USB.
  No maintained way to run it on an unrooted phone is known; a rooted phone with an OTG cable might manage,
  but that is untested guesswork. The Steam Deck runs flashrom and the toolkit's backup and restore scripts as any Linux
  machine does ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)).
- `card-check` and `card-fix`, which open the raw card device. Android does not give apps that access without root.
- The donor route, for a camera whose own firmware is gone and does not fit it
  ([Recovery, section D](02-RECOVERY.md#the-donor-route)). The app never takes it: it compares every file with the
  backup on record and refuses a sensor set-up that differs.

---

Reading order: 6 of 15 · previous: [SD card problems](05-CARDS.md) · next: [Command reference](07-USAGE.md) · [all docs](README.md)
