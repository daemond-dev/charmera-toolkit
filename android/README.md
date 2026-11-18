# Charmera Card for Android

A small Android app (7.0 or later) that does an SD-card flash from the phone, with no computer for the card part: it
downloads the public stock firmware dump, makes the published stock restore file from it, compares that file with a
dump of your own camera's flash chip, writes it to the camera's card over the camera's USB-C cable, and walks you through
flashing and cleaning up. It can also build reviewed patches of that file: a **sensor-ID probe** that reports
which image sensor a camera has (a diagnostic for a camera that boots to its menu but freezes entering photo mode),
and the **OV9732 bring-up** that repairs a camera with an OmniVision OV9732 sensor flashed with the public build
([the bring-up](../docs/06-ANDROID.md#repair-an-ov9732-camera-the-ov9732-bring-up)), and, from the owner's own factory
dump picked on the phone, **firmware v0.25** ([v0.25](../docs/06-ANDROID.md#firmware-v025-the-cameras-own-factory-firmware))
and **v0.26**, v0.25 with EXIF dates ([v0.26](../docs/06-ANDROID.md#firmware-v026-exif-dates)), and **v0.27**, v0.26 that boots into the last camera mode and flashes the screen black on a shot ([v0.27](../docs/06-ANDROID.md#firmware-v027-boot-to-the-last-mode-capture-flash)).
It also saves the camera's
photos and videos as a zip. The camera flashes itself from the card, as described in
[The first flash](../docs/01-FIRST-FLASH.md).

**No backup of your own camera, no flash.** The app writes the stock restore file, or any file of your own, to the
card only while a dump of that camera's own flash chip is on record (**Choose this camera's backup**, step 1 of
Restore stock firmware), and only an update file it has compared with that dump. The reviewed files, the probe and
the firmware for the OV9732 sensor (every v012 build, stock v012 included, and v006 builds with the OV9732 port: v0.23
to v0.27 and their Custom combinations), are the only files it writes without one. Whether a firmware fits a
camera cannot be told from the file, from the version stamp or from the boot screen: two different builds carry the same
stamp, and the public dump left one original-series camera unable to take pictures. A dump of a camera's own chip read
before it was ever flashed is the one file known to fit it; any dump of the chip is the way back to what it held when
it was read. Reading the chip needs a CH341A clip and a computer
([Recovery, section B](../docs/02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)); the app
cannot do it, and it cannot tell whose chip a dump came from: it refuses the public dumps (the two public stock dumps, the stock dump's cleaned copy and the cleaned copy of the factory build v012) and any read
named as unverified (`.unverified-read<n>.bin`), so give it only a dump read from this camera with a clip, from two
reads that agreed.

It uses the network for these things only. It downloads the stock dump's cleaned copy from
[the-dumping-grounds/charmera-firmware-v006](https://github.com/the-dumping-grounds/charmera-firmware-v006) on GitHub,
at a fixed commit, and accepts the download only if its SHA-256 is that copy's (`75b911e9…`); it keeps that checked copy in
its own storage on the phone, checks it again each time it uses it, and downloads it again only if the copy is gone or
no longer the stock dump. For firmware v0.25, v0.26 and v0.27 on a phone with no factory dump, it downloads the factory
build v012's cleaned copy from
[the-dumping-grounds/charmera-firmware-v012](https://github.com/the-dumping-grounds/charmera-firmware-v012) the same
way (`63e39684…`), and keeps it until you pick your own read. And **Check for app updates**, only when you tap it, downloads the app's release APK from
GitHub to compare its version with this one. No firmware is shipped inside the app. It reaches the card through
Android's own folder picker, which grants access to the one folder you choose, so it needs no storage permission.

Status: on 2026-11-06 the app wrote two files to one camera's card, over the camera's USB-C cable with the card in
the camera. Version 0.3 wrote candidate C, which the camera flashed twice (the second time when the cable was plugged
into it while it was switched off, which the connection steps below now prevent); version 0.4 wrote the stock restore
file. The writing, the flashes and the cleanup worked as the app describes them. Neither file fitted that camera, which
is why 0.5 adds the backup step. Version 0.7 adds a sensor-ID probe that reports which image sensor a camera has, and
rebuilds the screen as a column of cards. Version 0.24 splits that column into separate routes, one job per screen
(see [Use](#use)), and adds the photo backup, a flash log, test shots and a flash check. Version 0.26 adds a version menu to Flash custom firmware (v0.26, v0.25 with EXIF dates;
v0.25, the camera's own factory firmware with the Volume and LED rows; or v0.24). Version 0.28 adds **Custom** to
that menu: a base and its mods, any reviewed combination ([Custom build](#custom-build)). The current version is 0.29; it writes every reviewed file without a backup, with a warning when nothing confirms what the camera runs.
On 2026-11-11 it wrote the OV9732 bring-up, firmware v0.24 (**Flash custom firmware**), to the owner's camera over
the camera's USB-C cable, and the camera flashed it
([what the camera showed](../docs/15-OV9732-AE-WB-FLASH-PORT.md#10-v024-volume-and-led-rows-in-the-setup-menu)).

Version 0.4, the one published at the [android-app release](https://github.com/daemond-dev/charmera-toolkit/releases/tag/android-app)
before 0.5, had no backup step and did not refuse C, A or B; it was taken down on 2026-11-06, and if you still have
it installed, do not flash with it. The plain-Java parts of 0.26 (the checker, the card rules, the file builder,
WriteGate, the rule the screen asks before every write and save, the flash log, the flashing routes' steps, the photo
header check and the date repair) compile and pass their tests on a computer's JVM ([Build](#build)).
The screen itself needs the Android SDK and is built only by the workflow.

## Before you flash

- **A backup of this camera's flash chip,** read with a CH341A clip and kept somewhere safe. Without it the app does not
  write. The backup is also what you compare the update file with: the app refuses a file whose image-sensor set-up
  differs from the firmware in your backup, because that is what leaves a camera unable to take pictures. A backup
  read after the camera was flashed holds the flashed firmware, not the camera's own: it shows what the camera runs
  now, not what fits it, so when it is one of the known builds the app shows a matching set-up as a warning, not a
  pass ([After a flash](../docs/01-FIRST-FLASH.md#after-a-flash)). A camera whose own firmware is gone needs another
  camera's firmware by the donor route, which takes a computer
  ([Recovery, section D](../docs/02-RECOVERY.md#the-donor-route)).
- **Original 2025 series only:** matte body, yellow Kodak boot screen. Never flash a Millennium Edition (blue boot
  screen) or a CLOT edition with these files. Owners report that the firmware boots there but the live view freezes or
  the camera cannot take a picture ([Recovery, section D](../docs/02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera));
  the same happened to an original-series camera, so the body alone proves nothing.
- **Charge the battery fully first.** Programming takes 15 to 20 seconds, and a power loss in that window leaves a
  camera that only a chip programmer can bring back ([Recovery](../docs/02-RECOVERY.md)).
- **Stored photos:** a 0x1E0000-byte file, such as the stock restore file, replaces the two photos the camera keeps in
  its own memory. The stock restore file carries the two photos of the camera it was dumped from, but its photo list
  is empty, so the camera does not show them; your own two are gone all the same. A 0x169000-byte file (made with
  `pack --official-size` on a computer) leaves the photo data in place, but the camera empties its photo list, so it
  no longer shows them. If yours matter, copy the `SPIDCIM` folder off the card first
  ([The first flash, step 5](../docs/01-FIRST-FLASH.md#5-flash-the-point-of-no-return)).

## Install

Check the version first: the current build opens on a Home screen with one button per route (Back up photos, Flash
custom firmware, Identify the image sensor, Restore stock firmware, Test shots), and the restore route begins
with **Choose this camera's backup**. Version 0.4 had no backup step and did not refuse the withdrawn candidates C, A
and B; if you still have it installed, do not flash with it.

Download
[charmera-card.apk](https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk)
from the [android-app release](https://github.com/daemond-dev/charmera-toolkit/releases/tag/android-app), open it,
and allow your browser or file manager to install apps when Android asks. Play Protect may warn that it does not
know the app; that is because it is not from the Play Store. Or download the APK on a computer and copy it to the
phone over USB.

## Use

The app opens on **Home**. Each button there opens one route, a screen with only that job's numbered steps; a step's
result appears right under its button, and **More info** opens the detail behind it. A step done shows ✓, a step that
needs an earlier one says which. Back, or **‹ Home**, returns to Home. Every screen starts with the drive bar,
"Camera drive: <name> · Connected ✓ / Not connected · Change": the app remembers the drive and asks again only when
none is saved or the saved one is gone. Home also shows the camera firmware the flash log last recorded, and links to
**Flash log**, **Advanced** and **Check for app updates**.

- **Back up photos.** Copies the photos and videos in the card's `DCIM` and `SPIDCIM` folders (never the update file,
  `SENSOR.TXT` or anything else) into a zip you choose with **Choose where to save the zip**; choose a place on the
  phone, not the camera's card (the app refuses the card). Options: fix photo and video dates in the copies (on by
  default; the card's files stay as they are), **Name the copies by date and time** (on by default: a copy is named
  by when it was taken, as `20261111_114931.jpg`, in the same folder, when that time can be read; otherwise, and with
  the option off, it keeps its name on the card), and delete them from the camera after a verified backup (off by
  default). The app syncs the zip, reads it back and compares every entry with what it
  wrote; it deletes from the camera only files whose entry matched, that read whole and that the camera made, and
  nothing at all if any part failed. With delete on, the zip also holds each date-fixed file as the camera wrote it,
  in `originals/` under its name on the card, and a file is deleted only when both its entries matched.
- **Flash custom firmware.** For a camera the probe names as OV9732: pick the version in its menu (newest first; it
  starts on the newest the app can make), make it (no backup needed) and write it. v0.24 is made from the public stock
  dump; v0.25, v0.26 and v0.27 from the factory build v012: with no factory dump on the phone, making them downloads its public
  cleaned copy ([the-dumping-grounds/charmera-firmware-v012](https://github.com/the-dumping-grounds/charmera-firmware-v012),
  checked by SHA-256); or pick your own read once with **Use a file on the phone**, which is then used instead (picked
  on another route, it is kept, and this route makes the firmware). Then eject, flash, remove
  the update file, and **Check the newest photo**, which reads the header of a photo taken after the flash. The header shows only the firmware family, not whether the
  picture is right. The menu's last row is **Custom: pick base and mods** ([Custom build](#custom-build)).
- **Identify this camera's firmware.** No flash, no backup, no file, and it writes nothing. Choose the drive, then
  **Identify from the photos on the card**: the app reads the headers of up to 500 photos in `DCIM` and says which
  firmware family took the oldest and the newest it recognises (the v006 / v008_test family, the factory build v012,
  or our EXIF mod v0.26), where the family changes, a suggested base with a confidence, and the evidence under
  **Evidence**. A photo cannot tell v006 from v008_test, and a photo taken after a flash shows the firmware that took
  it, not what fits the camera. A third step lists what to check on the camera itself: Volume and LED rows after FORMAT
  (our v0.24 or later), a freeze in Photo or Video mode after a flash (a build for the other sensor), and Setup >
  Version, which tells nothing ([the route](../docs/06-ANDROID.md#identify-this-cameras-firmware)).
- **Identify the image sensor.** Makes the sensor-ID probe (no backup needed), writes it, then eject, flash, remove the
  update file, and **Read the sensor result from the card** once the camera has opened photo mode
  ([the probe](../docs/06-ANDROID.md#identify-the-image-sensor-the-sensor-id-probe)).
- **Restore stock firmware.** Needs this camera's backup (step 1), then the stock restore file (step 2), the drive,
  the write, eject and flash, and removing the update file.
- **Test shots.** Shows the colour card on the phone (or use a printed one), names the lighting, and saves the photos
  taken since then as `Charmera-<firmware>-<lighting>-<date>-<time>.zip` with a `manifest.txt`.
- **Flash log.** What the app wrote, removed and saved, and what you answered to "Did the camera show Upgrade 100%?",
  newest first, with each file's firmware and SHA-256 prefix. Kept on the phone only.
- **Advanced.** The old all-in-one screen as compact steps: the backup, any update file (download, **Use a file on the
  phone**, **Save a copy to the phone**), the drive, the write, removing update files, reading the sensor result and
  the app update check. A file opened from another app lands here.

The steps the routes share:

**The backup** (**Choose this camera's backup**). Copy the 2 MB file the toolkit's `backup` command read from this
camera's chip to the phone and choose it. The app takes it only if it is a complete dump that carries the firmware's
build marks and is not byte for byte one of the public dumps (the two public stock dumps, the stock dump's cleaned copy and the cleaned copy of the factory build v012): such a file is a copy of someone else's
camera, or a read of a camera flashed with the public restore file that has changed no setting since, and either way
not firmware known to fit yours. It also refuses a dump whose sounds, pictures and menus are missing or damaged, as a
read that lost contact part way is, and a dump with the build string and the stamp but no image-sensor set-up table
of the kind it knows, which it asks you to keep and look at with `charmera inspect` on a computer. And it refuses a
file named like a read that no second read confirmed (`<label>_<UTC time>.unverified-read<n>.bin`, the name `backup`
gives a single read and reads that differ): read the chip again until two reads agree, and choose the
`<label>_<UTC time>.bin` that `backup` then keeps. It cannot tell whose chip any other dump came from, so choose only
one read from this camera with a clip. A recreated screen (dark mode, font size) reads the file again; after the app
is closed, choose it again.

**The stock restore file.** The app downloads the stock dump's cleaned copy, checks it, and makes the stock restore
file from it (its first 0x1E0000 bytes), using it only if its SHA-256 is the reviewed file's: `9b8841d7…`
(`JH_5408202610261427_0FB74C24.bin`), the stock software with the factory settings record and no stored photos. From
the original public dump, kept by an older app or picked by hand, it makes the published file (`b10a2f1c…`,
`JH_5408202610261427_0F065D55.bin`), a copy of one working camera's memory, with that camera's settings. It is only for a camera whose backup, read before
the camera was ever flashed, shows the public build. Several owners report flashing it and working cameras
afterwards; what their cameras ran before is not known, and it did not fit every original-series camera. The checks
shown under the button compare it with your backup: the image-sensor set-up must be the same, and the software line
says whether the file is the same software as your backup, byte for byte, or differs (a difference is a warning,
never a pass, but the start of the program, the ARM vector table at 0x200, must be your backup's in a file laid out
like it; a file laid out otherwise than your backup, or checked while no backup is on record, is held to the table of
the known build whose layout it has, and only a file laid out like neither, whose table is not your backup's, gets a
warning that the table could not be compared). When your backup is itself one of the known builds, as a camera
flashed with the public restore file reads, a matching set-up is a warning too: it proves a fit only if the backup was
read before the camera was ever flashed. With a backup of a build nobody has published, the same set-up is a pass only
when the program around it is your backup's too, and a warning that says so otherwise. Without a backup, the sensor
line is a warning that cannot be cleared and the write stays off. In **Advanced**, **Use a file on the phone** takes
the stock dump, a `JH_5408…bin` file of either size the app accepts (0x1E0000 or 0x169000 bytes), or a copy already on
the card. If the file chosen is candidate A or B, the app refuses it: the file is dropped, and the write and the
copy stay off ([why](../docs/03-FLASH-CANDIDATES.md)). Candidate C's bytes are the stock restore file made from the
cleaned stock dump the app downloads, so the app takes them as that file. Each flashing route writes only its own kind of file.

**Camera drive.** Plugging the cable into a switched-off camera switches it on, and if its card holds an update file
the camera flashes that file again. So the app always has you connect the camera this way: switch it off if it is on,
take the card out, switch the camera on with its power key, wait 10 seconds, put the card back in, then connect the
camera with a USB-C data cable. It shows its card as a drive. Tap **Choose the camera's drive** (or **Change** in the
drive bar), open that drive in the picker's menu and tap "Use this folder" in its top folder, not inside DCIM. A USB
card reader works the same way. The phone's own SD slot works only on Android 7 to 10: from Android 11 on, Android
does not let an app choose the top level of a card in the phone's slot ("Use this folder" is greyed out there; see
[What it cannot do](#what-it-cannot-do) for the slot). The app looks at the drive again whenever you come back to it.
Picking a drive clears the results of an earlier write or removal.

**Write.** Tap **Write the update file to the card** (in Flash custom firmware, **Make firmware v…** first) and confirm; the
confirmation names the backup the file was compared with, or says the file is a reviewed patch written without one.
The app refuses a card that already holds an update file, and lists the card again just before it writes. It writes
the file under a temporary name, syncs it, renames it to the exact name the camera looks for, and reads it back to
compare SHA-256. If any of that fails, it lists the card again and says what is on it now. A write that worked says
"Synced to the camera ✓".

**Eject, then flash.** Tap **Eject the drive** (off while a write or a removal runs). Android lets only the system
eject a drive, so the app asks it and then opens the drive's own storage page (or the Files app); tap Eject or Unmount
there and come back. The app says "Ejected ✓" only once Android reports the drive unmounted and it is still attached
a moment later (a drive pulled out without ejecting is reported unmounted too). Then unplug the cable.
Switch the camera off, then on again with the card in: it looks for the file only in its first five seconds, then
shows "Upgrade" with a percentage, about 15 to 20 seconds in all. No "Upgrade" screen and a normal start mean it did
not find the file: switch it off, connect it as above and check what the card holds. At 100% it stays on its last
screen. Take the card out first, then hold the power key about a second to switch off. "Program error" means nothing
was erased and the camera switches itself off: take the card out, remove the file as below, and write it again.
Answer "Did the camera show Upgrade 100%?" so the flash log records it. The write was synced before it reported
success, so the eject is advice, not a gate: "Yes" completes the step whether or not the app saw an eject (you may
have ejected and unplugged before coming back, or pulled the cable). "No / something else" keeps the step open with
its More info. Remove opens once you answer, and at once after a write that found the file on the card already;
plugging the camera back in before you answer opens nothing. While the camera is away to be flashed, the Camera drive
step stays done and its line says to flash as the flash step says; the app never asks you to switch the camera on and
connect it while an update file may be on its card, except in the order above.

**Remove the update file.** The camera does not delete the file and would flash it again at every power-on. With the
card still out, switch the camera on with its own power key (not by plugging in the cable), wait 10 seconds, insert
the card, connect the camera to the phone, and tap **Remove update files from the card**. If that button is greyed
out, pick the camera's drive again so the app looks at the card again. For a card in the phone's own slot on Android
11 or later, delete the file from the card's top folder with the Files app. Then switch the camera off and on normally
and check the boot screen, the live view and a test photo. The step counts as done once the app lists the card after
the removal and finds no update file there, ejected or not; unplugging and reconnecting afterwards does not undo
that, and only a listing that finds an update file again opens the step again. After "No / something else", the write
step opens again once the file is removed, so you can write it again.

Each flashing route keeps its progress on the phone, for each camera drive, so when Android ends the app while the
camera is away, it opens again at the same step. Writing a file starts that route's flash over (the other routes'
too, since the card then holds that file), and on Restore stock firmware choosing another backup before the flash
does as well.

## About the camera's USB-C connection

Connected by USB, the camera presents its SD card as a USB drive (USB ID 1B3F:8301, "GENPLUS USB-MSDC DISK A"). On
2026-11-06 the drive appeared on an Android phone connected over USB-C, the app wrote an update file to it, and the
camera flashed that file at its next power-on; the cleanup over the same cable worked too. Two things the same run
showed, which the steps above are built around: the camera looks for the update file only in the first five seconds
after power-on, so it does not notice a file added while it is running; and plugging the cable into a switched-off
camera switches it on and runs the updater, so a camera is never connected with an update file on its card unless
that flash is wanted.

## Custom build

In **Flash custom firmware**, the version menu's last row, **Custom: pick base and mods**, shows a base menu (v012,
the factory build for OV9732 cameras; v006 and v008_test, SP140A builds) and one box per mod of that base with its
description. It starts on the base **Identify this camera's firmware** found. Experimental mods (not yet tried on a
camera) are marked and add a warning, but can be ticked; a mod that conflicts with a ticked one is greyed out with
the reason. The app builds the pick from that base's dump (v012: your factory dump or its public cleaned copy; v006:
the public stock dump; v008_test: its public file, downloaded at a fixed commit and checked by SHA-256) with
`Recipe.java`, the app's copy of charmera_toolkit/recipes.py, and keeps it only when it is the reviewed file
`Recipes.java` lists for that base, those mods and that dump, under that file's name. No pick needs a backup:
whether a file fits the camera is your call. When nothing confirms which firmware the camera runs (no backup on
record, and Identify not run or naming the other sensor's family), the file step and the write confirmation say so;
a backup on record whose firmware drives the other sensor gives a loud "Wrong sensor?" warning. Neither refuses.
Stock v006 (the stock restore file) is written on Restore stock firmware, which compares it with your backup, or on
Advanced. A combination marked experimental says so even when each of its mods is
tested alone. The pick is remembered as base and mods. Details:
[Custom build](../docs/06-ANDROID.md#custom-build-pick-a-base-and-mods).

## What it cannot do

- **Read the chip:** the backup needs a CH341A clip and a computer; the app only checks the file and
  compares the update file with it ([Recovery, section B](../docs/02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)).
- **Unreadable cards:** it cannot make Android read a card the camera has marked dirty. Format the card in the
  phone, or run `charmera card-fix` on a computer ([SD card problems](../docs/05-CARDS.md)).
- **Other mods:** it makes only the stock restore file and the reviewed files (the sensor-ID probe, the Repair
  versions v0.24 to v0.27 and the other reviewed combinations of [Custom build](#custom-build)). A file from your own backup, frames, a boot logo or start-up tweaks need the toolkit on a computer, or in Termux ([Using an Android phone](../docs/06-ANDROID.md)); the app then
  checks the file they produce, against your backup, and writes it. In Termux, `pip install` takes the toolkit
  straight from its GitHub URL.
- **The donor route:** for a camera whose own firmware is gone and does not fit it, another camera's firmware is
  the only help, and the app never takes that route: it compares every file with the backup on record and refuses a
  sensor set-up that differs. A computer does it ([Recovery, section D](../docs/02-RECOVERY.md#the-donor-route)).
- **Read-back:** the comparison may read Android's cached copy rather than the card itself. Ejecting the card
  before removing it is what makes sure the data reached the card.
- **Eject by itself:** Android lets only the system eject a drive; the app opens the drive's page and waits to see
  it unmounted.
- **Card root on Android 11 and later:** Android blocks the picker from granting access to the top level of the
  phone's internal storage and of an SD card in the phone's own slot. USB card readers and the camera's USB drive
  are not blocked. On those phones use the camera's cable or a reader. For the slot, tap **Save a copy to the
  phone** in Advanced (for any file but the probe and the Repair versions it needs the backup on record too), copy that file to the card's top level with the Files app, then choose
  the copy with **Use a file on the phone** to have it checked where it lies.

## Build

`./build.sh [versionName] [versionCode]` with `SDK` pointing at an Android SDK that has `platforms;android-35`
and `build-tools;35.0.0`, a JDK 17 or newer, and `zip`. The version name defaults to the one in `VERSION`. It runs
the tests of the checker, the card rules, the file builder, the write gate, the flash log, the flashing routes' steps,
the photo header check and the date repair on the plain JVM first (`test/UpdateCheckTest.java`,
`test/CardStateTest.java`, `test/CandidatesTest.java`, `test/WriteGateTest.java`, `test/FlashLogTest.java`,
`test/FlashFlowTest.java`, `test/PhotoIdTest.java` and `test/DateFixTest.java`).
Set `CHARMERA_STOCK_IMAGE` to the public stock dump (its cleaned copy or the original) to have CandidatesTest make
the restore file and the three
withdrawn candidates and compare them with the published and reviewed files, and to check that the checker finds the
dump's build marks by content; set `CHARMERA_FILM_MODS_RESTORE` to the film-mods project's public restore file to have
UpdateCheckTest check that the checker names that build, and CandidatesTest that it compares that file's vector table
with its own build's; set `CHARMERA_FACTORY_DUMP` to the factory build v012 (its cleaned copy or the original read) to
have the tests make v0.25 and v0.26 from it; set `CHARMERA_UPDATE_FILES` to real update files to check those too. Each test prints how many
of its checks passed and failed, and stops the build when one fails. Then it builds and signs
`out/charmera-card-<version>.apk`. No Gradle. The workflow downloads the cleaned v006 and v012 copies and the film-mods
restore file from the-dumping-grounds' firmware repositories for every build, checks their SHA-256, and runs those checks before it publishes; it does not set
`CHARMERA_UPDATE_FILES`.

GitHub Actions builds it on every push to `main` that touches `android/` and publishes it as the `android-app`
release (`.github/workflows/android.yml`), one run at a time. Run from the Actions tab on another branch, it builds
and keeps the APK as a run artifact without publishing. Every published build is signed with the same release key, so
a new version installs over the old one. The key lives only in the repo secrets `SIGNING_KEYSTORE_B64`,
`SIGNING_ALIAS` and `SIGNING_PASS`. Before publishing, the workflow checks that the APK's certificate matches
`SIGNING_CERT_SHA256` in this folder, and it refuses to publish anything else. Without the secrets a build is
signed with a throwaway key and kept only as a run artifact.

Keep a copy of the three secret values somewhere safe: GitHub never shows a secret again once it is saved. If the
key is lost, make a new one, save it in the secrets, put its certificate SHA-256 (from `apksigner verify
--print-certs`) in `SIGNING_CERT_SHA256`, and uninstall the app once before installing the first build signed with
it.
