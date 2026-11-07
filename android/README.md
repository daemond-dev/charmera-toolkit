# Charmera Card for Android

A small Android app (7.0 or later) that does an SD-card flash from the phone, with no computer for the card part: it
downloads the public stock firmware dump, makes the published stock restore file from it, compares that file with a
dump of your own camera's flash chip, writes it to the camera's card over the camera's USB-C cable, and walks you through
flashing and cleaning up. It can also build a **sensor-ID probe** that reports which image sensor a camera has — a diagnostic for a camera that boots to its menu but freezes entering photo mode. The camera flashes itself from the card, as described in [The first flash](../docs/01-FIRST-FLASH.md).

**No backup of your own camera, no flash.** The app writes to the card only while a dump of that camera's own flash chip
is on record (step 0 on its screen), and only an update file it has compared with that dump. Whether a firmware fits a
camera cannot be told from the file, from the version stamp or from the boot screen: two different builds carry the same
stamp, and the public dump left one original-series camera unable to take pictures. A dump of a camera's own chip read
before it was ever flashed is the one file known to fit it; any dump of the chip is the way back to what it held when
it was read. Reading the chip needs a CH341A clip and a computer
([Recovery, section B](../docs/02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)); the app
cannot do it, and it cannot tell whose chip a dump came from: it refuses the two public stock dumps and any read
named as unverified (`.unverified-read<n>.bin`), so give it only a dump read from this camera with a clip, from two
reads that agreed.

It uses the network only to download the stock dump from its upstream repository on GitHub, at a fixed commit, and
accepts the download only if its SHA-256 is that of the public dump. No firmware is shipped inside the app. It
reaches the card through Android's own folder picker, which grants access to the one folder you choose, so it needs
no storage permission.

Status, 2026-11-06: the app wrote two files to one camera's card that day, over the camera's USB-C cable with the card
in the camera. Version 0.3 wrote candidate C, which the camera flashed twice (the second time when the cable was plugged
into it while it was switched off, which the connection steps below now prevent); version 0.4 wrote the stock restore
file. The writing, the flashes and the cleanup worked as the app describes them. Neither file fitted that camera, which
is why 0.5 adds the backup step. Version 0.7 adds a sensor-ID probe that reports which image sensor a camera has, and
rebuilds the screen as a column of cards.

Version 0.4, the one published at the [android-app release](https://github.com/daemond-dev/charmera-toolkit/releases/tag/android-app)
before 0.5, had no backup step and did not refuse C, A or B; it was taken down on 2026-11-06, and if you still have
it installed, do not flash with it. 0.7 had not been run on a phone when this was written. Its plain-Java parts (the
checker, the card
rules, the file builder and WriteGate, the rule the screen asks before every write and save) compile and pass their
tests on a computer's JVM ([Build](#build)). The screen itself needs the Android SDK and is built only by the
workflow.

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

Check the version first: the current build's screen is a column of cards, headed by a yellow "Before you flash" notice
("No backup of this camera, no flash") and a numbered step "Back up the camera". Version 0.4 had no backup step and did
not refuse the withdrawn candidates C, A and B; if you still have it installed, do not flash with it.

Download
[charmera-card.apk](https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk)
from the [android-app release](https://github.com/daemond-dev/charmera-toolkit/releases/tag/android-app), open it,
and allow your browser or file manager to install apps when Android asks. Play Protect may warn that it does not
know the app; that is because it is not from the Play Store. Or download the APK on a computer and copy it to the
phone over USB.

## Use

The app's screen is a column of cards, one per step; a card's **▸** line opens the detail behind it. The six steps:

0. **Back up this camera first.** Copy the 2 MB file the toolkit's `backup` command read from this camera's chip to
   the phone and tap **Choose this camera's backup**. The app takes it only if it is a complete dump that carries the
   firmware's build marks and is not byte for byte one of the two public stock dumps: such a file is a copy of someone
   else's camera, or a read of a camera flashed with the public restore file that has changed no setting since, and
   either way not firmware known to fit yours. It also refuses a dump whose sounds, pictures and menus are missing or
   damaged, as a read that lost contact part way is, and a dump with the build string and the stamp but no
   image-sensor set-up table of the kind it knows, which it asks you to keep and look at with `charmera inspect` on a
   computer. And it refuses a file named like a read that no second read confirmed
   (`<label>_<UTC time>.unverified-read<n>.bin`, the name `backup` gives a single read and reads that differ): read
   the chip again until two reads agree, and choose the `<label>_<UTC time>.bin` that `backup` then keeps. It cannot
   tell whose chip any other dump came from, so choose only one read from this camera with a clip. It shows the
   file's name and SHA-256, compares the update file with it in step 1, and writes to the card only while this backup
   is on record. A recreated screen (dark mode, font size) reads the file again; after the app is closed, choose it
   again.
1. **Get the update file.** Tap **Download the stock restore file**. The app downloads the stock dump, checks it,
   and makes the published stock restore file from it (its first 0x1E0000 bytes), using it only if its SHA-256 is
   the published file's (`b10a2f1c…`). That file is a copy of one working camera's memory, with that camera's
   settings. Several owners report flashing it and working cameras afterwards; what their cameras ran before is not
   known, and it did not fit every original-series camera. The checks shown under the button compare it with your
   backup: the image-sensor set-up must be the same, and the software line says whether the file is the same software
   as your backup, byte for byte, or differs (a difference is a warning, never a pass, but the start of the program,
   the ARM vector table at 0x200, must be your backup's in a file laid out like it; a file laid out otherwise than
   your backup, or checked while no backup is on record, is held to the table of the known build whose layout it has,
   and only a file laid out like neither, whose table is not your backup's, gets a warning that the table could not
   be compared). When your backup is itself one of the known builds, as a camera flashed with the public restore file
   reads, a matching set-up is a warning too: it proves a fit only if the backup was read before the camera was ever
   flashed. With a backup of a build nobody has published, the same set-up is a pass only when the program around it
   is your backup's too, and a warning that says so otherwise. Without a backup, the sensor line is a warning that
   cannot be cleared and the write stays off. Offline, or made a file on a computer?
   **Use a file on the phone** takes the stock dump, a `JH_5408…bin` file of either size the app accepts (0x1E0000 or
   0x169000 bytes), or a copy already on the card. If the file chosen is candidate C, A or B, the app refuses it: the
   file is dropped, and the write and the copy stay off ([why](../docs/03-FLASH-CANDIDATES.md)). Under step 1 is also **Make the sensor-ID probe**: a diagnostic that writes the camera's image-sensor identifier to `SENSOR.TXT` when the camera next enters photo mode, read back with **Read the sensor result from the card** in step 4. It is the one file the app writes without a backup, because it changes no boot or update code and the stock restore file above puts it back ([the probe](../docs/06-ANDROID.md#identify-the-image-sensor-the-sensor-id-probe)).
2. **Connect the camera.** Plugging the cable into a switched-off camera switches it on, and if its card holds an
   update file the camera flashes that file again. So the app always has you connect the camera this way: switch
   it off if it is on, take the card out, switch the camera on with its power key, wait 10 seconds, put the card
   back in, then connect the camera with a USB-C data cable. It shows its card as a drive. Tap **Choose the
   camera's drive**, open that drive in the picker's menu and tap "Use this folder" in its top folder, not inside
   DCIM. A USB card reader works the same way. The phone's own SD slot works only on Android 7 to 10: from
   Android 11 on, Android does not let an app choose the top level of a card in the phone's slot ("Use this folder"
   is greyed out there; see [What it cannot do](#what-it-cannot-do) for the slot). The app remembers the drive and
   looks at it again whenever you come back to the app, or pick the drive again. Picking a drive clears the results
   of an earlier write or removal.
3. **Write to the card.** Tap **Write the update file to the card** and confirm; the confirmation names the backup
   the file was compared with. The app refuses a card that already holds an update file, and lists the card again
   just before it writes. It writes the file under a temporary name, renames it to the exact name the camera looks
   for, and reads it back to compare SHA-256. If any of that fails, it lists the card again and says what is on it
   now. When it is done, the app says so right under the button and in a short message, and offers **Open storage
   settings**: Android does not let an app eject a drive, so eject it there before you unplug. The **Eject the drive
   (opens Android's storage settings)** button under step 3 opens the same screen; it is off while a write or a
   removal is running.
4. **Flash.** Eject the drive in Android's storage settings and unplug the cable. Switch the camera off, then on
   again with the card in: it looks for the file only in its first five seconds, then shows "Upgrade" with a
   percentage, about 15 to 20 seconds in all. No "Upgrade" screen and a normal start mean it did not find the
   file: switch it off, connect it as in step 2 and check what the card holds. At 100% it stays on its last
   screen. Take the card out first, then hold the power key about a second to switch off. "Program error" means
   nothing was erased and the camera switches itself off: take the card out, remove the file as in step 5, and
   write it again.
5. **Clean up.** The camera does not delete the file and would flash it again at every power-on. With the card
   still out, switch the camera on with its own power key (not by plugging in the cable), wait 10 seconds, insert
   the card, connect the camera to the phone, and tap **Remove update files from the card**. The result shows right
   under that button and as a short message, and the app offers the storage settings to eject the drive. If that
   button is greyed out, pick the camera's drive again with **Choose the camera's drive** so the app looks at the card
   again. For a card in the phone's own slot on Android 11 or later, delete the file from the card's top folder with
   the Files app. Then switch the camera off and on normally and check the boot screen, the live view and a test
   photo.

## About the camera's USB-C connection

Connected by USB, the camera presents its SD card as a USB drive (USB ID 1B3F:8301, "GENPLUS USB-MSDC DISK A"). On
2026-11-06 the drive appeared on an Android phone connected over USB-C, the app wrote an update file to it, and the
camera flashed that file at its next power-on; the cleanup over the same cable worked too. Two things the same run
showed, which the steps above are built around: the camera looks for the update file only in the first five seconds
after power-on, so it does not notice a file added while it is running; and plugging the cable into a switched-off
camera switches it on and runs the updater, so a camera is never connected with an update file on its card unless
that flash is wanted.

## What it cannot do

- **Read the chip:** the backup in step 0 needs a CH341A clip and a computer; the app only checks the file and
  compares the update file with it ([Recovery, section B](../docs/02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)).
- **Unreadable cards:** it cannot make Android read a card the camera has marked dirty. Format the card in the
  phone, or run `charmera card-fix` on a computer ([SD card problems](../docs/05-CARDS.md)).
- **Other mods:** it makes only the stock restore file and the sensor-ID probe. A file from your own backup, frames, a boot logo or start-up
  tweaks need the toolkit on a computer, or in Termux ([Using an Android phone](../docs/06-ANDROID.md)); the app then
  checks the file they produce, against your backup, and writes it. In Termux, `pip install` takes the toolkit
  straight from its GitHub URL.
- **The donor route:** for a camera whose own firmware is gone and does not fit it, another camera's firmware is
  the only help, and the app never takes that route: it compares every file with the backup on record and refuses a
  sensor set-up that differs. A computer does it ([Recovery, section D](../docs/02-RECOVERY.md#the-donor-route)).
- **Read-back:** the comparison may read Android's cached copy rather than the card itself. Ejecting the card
  before removing it is what makes sure the data reached the card.
- **Card root on Android 11 and later:** Android blocks the picker from granting access to the top level of the
  phone's internal storage and of an SD card in the phone's own slot. USB card readers and the camera's USB drive
  are not blocked. On those phones use the camera's cable or a reader. For the slot, tap **Save a copy to the
  phone** (it needs the backup on record too), copy that file to the card's top level with the Files app, then choose
  the copy with **Use a file on the phone** to have it checked where it lies.

## Build

`./build.sh [versionName] [versionCode]` with `SDK` pointing at an Android SDK that has `platforms;android-35`
and `build-tools;35.0.0`, a JDK 17 or newer, and `zip`. The version name defaults to the one in `VERSION`. It runs
the tests of the checker, the card rules, the file builder and the write gate on the plain JVM first
(`test/UpdateCheckTest.java`, `test/CardStateTest.java`, `test/CandidatesTest.java` and `test/WriteGateTest.java`).
Set `CHARMERA_STOCK_IMAGE` to the public stock dump to have CandidatesTest make the restore file and the three
withdrawn candidates and compare them with the published and reviewed files, and to check that the checker finds the
dump's build marks by content; set `CHARMERA_FILM_MODS_RESTORE` to the film-mods project's public restore file to have
UpdateCheckTest check that the checker names that build, and CandidatesTest that it compares that file's vector table
with its own build's; set `CHARMERA_UPDATE_FILES` to real update files to check those too. Each test prints how many
of its checks passed and failed, and stops the build when one fails. Then it builds and signs
`out/charmera-card-<version>.apk`. No Gradle. The workflow downloads the public dump and the film-mods restore file
from upstream for every build, checks their SHA-256, and runs those checks before it publishes; it does not set
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
