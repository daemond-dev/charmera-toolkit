# Charmera Card for Android

An Android app (7.0 or later) that puts firmware on a Kodak Charmera's SD card over the camera's USB-C cable and walks
you through the flash. No computer needed. The camera flashes itself from the card, as described in
[The first flash](../docs/01-FIRST-FLASH.md).

Know what your camera runs before you flash. Charmeras come with two image sensors, an OmniVision OV9732 or an
SP140A, and firmware for one can't take pictures on the other. Identify tells you which one you have from the photos on
the card.

## What it does

- Identifies the firmware the camera runs, and so its sensor, from the photos on its card.
- Builds reviewed firmware and writes it to the card:
  - for OV9732 cameras: v0.25 (Volume and LED rows), v0.26 (adds EXIF dates) and v0.27 (adds boot to the last mode
    and capture flash), all on the factory build v012, and stock v012 itself
  - the OV9732 bring-up v0.23 and v0.24, the public build v006 changed to drive the OV9732 (an OV9732 camera that
    was flashed with the public build can take these or the v012 builds)
  - for SP140A cameras: the stock restore file (build v006)
  - the sensor-ID probe, for a camera that can't take photos and can't be identified from its photos
  - any other reviewed mix of a base and its mods, ticked one by one
- Checks the file against a backup of the camera's flash chip, if you have one.
- Walks you through ejecting, flashing and removing the update file, then checks a photo taken afterwards.
- Saves the camera's photos and videos as a zip, with their dates fixed in the copies.
- Test shots against a colour card.

Not sure which version? v0.26 is Volume/LED plus EXIF dates. v0.27 adds boot to the last mode and capture flash.

## Install

Download
[charmera-card.apk](https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk)
from the [android-app release](https://github.com/daemond-dev/charmera-toolkit/releases/tag/android-app), open it, and
allow your browser or file manager to install apps when Android asks. Play Protect may warn that it doesn't know the
app, because it isn't from the Play Store. You can also download the APK on a computer and copy it to the phone over
USB.

Check the version. The current build opens on a Home screen with one button per route (Back up photos, Identify this
camera's firmware, Flash custom firmware, Identify the image sensor, Restore stock firmware, Test shots), and the
restore route begins with **Choose this camera's backup**. Version 0.4 had no backup step and didn't refuse the
withdrawn candidates C, A and B. If you still have it installed, don't flash with it.

## Use

The usual run, for a camera that works:

1. Switch the camera off, take the card out, switch it on, wait 10 seconds, put the card back and connect the camera
   with a USB-C data cable. Pick its drive when the app asks.
2. **Identify this camera's firmware.** Card empty? Take two or three photos with the camera first.
3. **Flash custom firmware.** Pick the base (v012 for an OV9732 camera), tick the mods you want, make it and write
   it.
4. Eject, unplug, switch the camera off and on with the card in, and wait for "Upgrade 100%".
5. Take the card out, switch off, reconnect as in step 1 and remove the update file. Then take a photo, switch off,
   reconnect and tap **Check the newest photo**.

The app opens on **Home**. Each button there opens one route, a screen with only that job's numbered steps. A step's
result appears right under its button, and **More info** opens the detail behind it. A step that's done shows ✓, and a
step that needs an earlier one says which. Back, or **‹ Home**, returns to Home. Every screen starts with the drive
bar, "Camera drive: <name> · Connected ✓ / Not connected · Change". The app remembers the drive and asks again only
when none is saved or the saved one is gone. Home also shows the camera firmware the flash log last recorded, and
links to **Flash log**, **Advanced** and **Check for app updates**.

- **Back up photos.** Copies the photos and videos in the card's `DCIM` and `SPIDCIM` folders (never the update file,
  `SENSOR.TXT` or anything else) into a zip. Pick where it goes with **Choose where to save the zip**: a place on the
  phone, not the camera's card (the app refuses the card). There are three options. Fixing photo and video dates in
  the copies is on by default, and the card's files stay as they are. **Name the copies by date and time** is on by
  default too: each copy is named by when it was taken (`20261111_114931.jpg`, in the same folder) when that time can
  be read, and otherwise keeps its card name. With the option off, every copy keeps its card name. Deleting them from
  the camera after a verified backup is off by default. The app syncs the zip, reads it back and compares every entry
  with what it wrote. It deletes from the camera only files whose entry matched, that read whole and that the camera
  made, and nothing at all if any part failed. With delete on, the zip also holds each date-fixed file as the camera
  wrote it, in `originals/` under its name on the card, and a file is deleted only when both its entries matched.
- **Flash custom firmware.** Pick a base in the menu and tick its mods one by one ([Picking a base and
  mods](#picking-a-base-and-mods)), then make it (no backup needed) and write it. Base v012 is for a camera whose photos
  Identify says were taken by stock v012, or that the probe names as an OV9732. The step's title names the version your
  ticks make. Not sure what to tick? On base v012, the Volume and LED rows make v0.25, adding EXIF dates makes v0.26, and adding boot
  to the last mode and capture flash makes v0.27. v0.24 is made from the public stock dump. v0.25, v0.26 and v0.27 are
  made from the factory build v012. With no factory dump on the phone, making them downloads its public cleaned copy
  ([the-dumping-grounds/charmera-firmware-v012](https://github.com/the-dumping-grounds/charmera-firmware-v012), checked
  by SHA-256). **Use a file on the phone** takes that copy, or the private read it was cleaned from, in its place (if
  you pick it on another route, it's kept, and this route makes the firmware). A read of your own v012 camera goes in as
  a backup instead. Then eject, flash, remove the update file, and tap **Check the newest photo**, which reads the
  header of a photo taken after the flash. The header shows only the firmware family, not whether the picture is right.
  The way back to stock on an OV9732 camera is stock v012 (base v012 with no mods ticked).
- **Identify this camera's firmware.** No flash, no backup, no file, and it writes nothing. Choose the drive, then tap
  **Identify from the photos on the card**. Card empty or new? Take two or three photos with the camera first. The
  app reads the headers of up to 500 photos in `DCIM` and says which firmware family took the oldest and the newest it
  recognises (the v006 / v008_test family, the factory build v012, or our EXIF mod v0.26), where the family changes,
  and a suggested base with a confidence, with the evidence under **Evidence**. A photo can't tell v006 from v008_test,
  and a photo taken after a flash shows the firmware that took it, not what fits the camera. Photos from before a bad
  flash still say which sensor the camera has. A third step lists what to check on the camera itself: Volume and LED
  rows after FORMAT (our v0.24 or later), a freeze in Photo or Video mode after a flash (a build for the other sensor),
  and Setup > Version, which tells you nothing ([the route](../docs/06-ANDROID.md#identify-this-cameras-firmware)).
  Photos moved off the card? Copy the camera's originals (the zip's `originals/` folder when delete was on, or the
  files as they came off the card) back into `DCIM`, or run `charmera identify <folder>` on a computer. Date-fixed
  copies don't count.
- **Identify the image sensor.** Makes the sensor-ID probe (no backup needed) and writes it. Then eject, flash, remove
  the update file, and tap **Read the sensor result from the card** once the camera has opened photo mode
  ([the probe](../docs/06-ANDROID.md#identify-the-image-sensor-the-sensor-id-probe)). If Identify already names the
  family from photos taken before any flash, you don't need it. On an SP140A camera the stock restore file undoes it.
  On an OV9732 camera, undo it with stock v012 or v0.25 or later (Flash custom firmware), not the stock restore file.
- **Restore stock firmware.** For SP140A cameras. Needs this camera's backup (step 1), then the stock restore file
  (step 2), the drive, the write, eject and flash, and removing the update file. Without a backup, **Advanced** writes
  the same file. On an OV9732 camera, stock is base v012 with no mods ticked, in Flash custom firmware.
- **Test shots.** Shows the colour card on the phone (or use a printed one), names the lighting, and saves the photos
  taken since then as `Charmera-<firmware>-<lighting>-<date>-<time>.zip` with a `manifest.txt`.
- **Flash log.** What the app wrote, removed and saved, and what you answered to "Did the camera show Upgrade 100%?",
  newest first, with each file's firmware and SHA-256 prefix. It's kept on the phone only.
- **Advanced.** The old all-in-one screen as compact steps: the backup, any update file (download, **Use a file on the
  phone**, **Save a copy to the phone**), the drive, the write, removing update files, reading the sensor result and
  the app update check. A file opened from another app lands here.

The routes share these steps.

**The backup** (**Choose this camera's backup**). Copy the 2 MB file the toolkit's `backup` command read from this
camera's chip to the phone and choose it. (The app's 2 MB and the toolkit's 2 MiB are the same size, 2,097,152
bytes.) The app takes it only if it's a complete dump that carries the firmware's build marks and isn't byte for byte
one of the public dumps (the two public stock dumps, the stock dump's cleaned copy and the cleaned copy of the factory
build v012). Such a file is either a copy of someone else's camera or a read of a camera flashed with that public
file that hasn't changed a setting since, and in both cases it isn't firmware known to fit yours. The app also refuses
a dump whose sounds, pictures and menus are missing or damaged, as they are in a read that lost contact part way. A
dump with the build string and the stamp but no image-sensor set-up table of a kind it knows (the SP140A table of v006
and v008_test, or v012's OV9732 table) is refused too, and the app asks you to keep it and look at it with `charmera
inspect` on a computer. It also refuses a file named like a read that no second read confirmed
(`<label>_<UTC time>.unverified-read<n>.bin`, the name `backup` gives a single read and reads that differ). Read the
chip again until two reads agree, and choose the `<label>_<UTC time>.bin` that `backup` then keeps. The app can't tell
whose chip any other dump came from, so choose only a read from this camera with a clip. If Android redraws the screen
(after a dark-mode or font-size change), the app reads the file again. After the app has been closed, choose it again.

**The stock restore file.** This is SP140A firmware. The app downloads the stock dump's cleaned copy, checks it, and
makes the stock restore file from it (its first 0x1E0000 bytes). It uses the file only if its SHA-256 is the reviewed
file's: `9b8841d7…` (`JH_5408202610261427_0FB74C24.bin`), the stock software with the factory settings record and no
stored photos. From the original public dump, kept by an older app or picked by hand, it makes the published file
(`b10a2f1c…`, `JH_5408202610261427_0F065D55.bin`), a copy of one working camera's memory with that camera's settings.
It's only for a camera whose backup, read before the camera was ever flashed, shows the public build. Several owners
report flashing it and having working cameras afterwards, but what their cameras ran before isn't known, and it didn't
fit every original-series camera. On an OV9732 camera it boots but can't take pictures.

The checks shown under the button compare the restore file with your backup:

- Sensor line. If the image-sensor set-up differs, the line is a "Wrong sensor?" warning, since the restore file is a
  reviewed file. When your backup is itself one of the known builds (as a read of a camera flashed with the public
  restore file is), a matching set-up is a warning too, because it proves a fit only if the backup was read before
  the camera was ever flashed. With a backup of a build nobody has published, the same set-up is a pass only when the
  program around it is your backup's too, and a warning that says so otherwise. Without a backup, the sensor line is
  a warning that can't be cleared, and the write still goes ahead.
- Software line. It says whether the file is the same software as your backup, byte for byte, or differs. A
  difference is a warning, never a pass.
- Vector table. The ARM vector table at 0x200, where the camera starts, must match your backup's when the file is
  laid out like your backup. A file laid out differently, or checked with no backup chosen, is held to the table of
  the known build whose layout it has. Only a file laid out like none of them, whose table isn't your backup's, gets
  a warning that the table couldn't be compared.

In **Advanced**, **Use a file on the phone** takes the stock dump, a `JH_5408…bin` file of either size the app accepts
(0x1E0000 or 0x169000 bytes), or a copy already on the card. If the file chosen is candidate A or B, the app refuses
it: the file is dropped, and the write and the copy stay off ([why](../docs/03-FLASH-CANDIDATES.md)). Candidate C's
bytes are the stock restore file made from the cleaned stock dump the app downloads, so the app takes them as that
file. Each flashing route writes only its own kind of file.

**Camera drive.** Plugging the cable into a switched-off camera switches it on, and if its card holds an update file
the camera flashes that file again. So the app always has you connect the camera this way: switch it off if it's on,
take the card out, switch the camera on with its power key, wait 10 seconds, put the card back in, then connect the
camera with a USB-C data cable. It shows its card as a drive. Tap **Choose the camera's drive** (or **Change** in the
drive bar), open that drive in the picker's menu and tap "Use this folder" in its top folder, not inside DCIM. A USB
card reader works the same way. The phone's own SD slot works only on Android 7 to 10. From Android 11 on, Android
doesn't let an app choose the top level of a card in the phone's slot ("Use this folder" is greyed out there; see
[What it cannot do](#what-it-cannot-do) for the slot). The app looks at the drive again whenever you come back to it.
Picking a drive clears the results of an earlier write or removal.

**Write.** Tap **Write the update file to the card** (in Flash custom firmware, tap **Make firmware v…** first) and
confirm. The confirmation names the backup the file was compared with, or says the file is a reviewed patch written
without one, or, for a file the app doesn't know with no backup chosen, carries the unknown-file warning. The app
refuses a card that already holds an update file, and lists the card again just before it writes. It writes the file
under a temporary name, syncs it, renames it to the exact name the camera looks for, and reads it back to compare
SHA-256. If any of that fails, it lists the card again and says what's on it now. A write that worked says "Synced to
the camera ✓".

**Eject, then flash.** Tap **Eject the drive** (it's off while a write or a removal runs). Android lets only the system
eject a drive, so the app asks it and then opens the drive's own storage page (or the Files app). Tap Eject or Unmount
there and come back. The app says "Ejected ✓" only once Android reports the drive unmounted and it's still attached a
moment later (a drive pulled out without ejecting is reported unmounted too). Then unplug the cable.

Switch the camera off, then on again with the card in. It looks for the file only in its first five seconds, then shows
"Upgrade" with a percentage, about 15 to 20 seconds in all. If there's no "Upgrade" screen and it starts normally, it
didn't find the file: switch it off, connect it as above and check what the card holds. At 100% it stays on its last
screen. Take the card out first, then hold the power key about a second to switch off. "Program error" means nothing
was erased, and the camera switches itself off. Take the card out, remove the file as below, and write it again.

Answer "Did the camera show Upgrade 100%?" so the flash log records it. The write was synced before it reported
success, so the app doesn't require an eject. "Yes" completes the step whether or not the app saw an eject (you may have
ejected and unplugged before coming back, or pulled the cable). "No / something else" keeps the step open with its More
info. Remove opens once you answer, and straight away after a write that found the file on the card already. Plugging
the camera back in before you answer opens nothing. While the camera is away to be flashed, the Camera drive step stays
done and its line tells you to flash as the flash step says. The app never asks you to switch the camera on and connect
it while an update file may be on its card, except in the order above.

**Remove the update file.** The camera doesn't delete the file and would flash it again at every power-on. With the
card still out, switch the camera on with its own power key (not by plugging in the cable), wait 10 seconds, insert the
card, connect the camera to the phone, and tap **Remove update files from the card**. If that button is greyed out,
pick the camera's drive again so the app looks at the card again. For a card in the phone's own slot on Android 11 or
later, delete the file from the card's top folder with the Files app. Then switch the camera off and on normally and
check the boot screen, the live view and a test photo. The step counts as done once the app lists the card after the
removal and finds no update file there, ejected or not. Unplugging and reconnecting afterwards doesn't undo that. Only
a listing that finds an update file again reopens the step. After "No / something else", the write step opens again
once the file is removed, so you can write it again.

Each flashing route keeps its progress on the phone, for each camera drive, so if Android ends the app while the camera
is away, it opens again at the same step. Writing a file starts that route's flash over (and the other routes' too,
since the card then holds that file). On Restore stock firmware, choosing another backup before the flash does as well.

## Before you flash

- Know what the camera runs: run Identify, or choose a backup of its chip. Without either, the app warns but doesn't
  refuse.
- Every reviewed file is written without a backup, with a warning when nothing confirms what the camera runs. That's
  the probe, every v012 build (stock v012 included), the v006 builds with the OV9732 port (v0.23, v0.24) and the
  other mixes you can tick, and the stock restore file (from Advanced; the Restore stock firmware route itself needs a
  backup). A file the app doesn't know (any file of your own) is written without one too, with a loud warning that
  this project didn't review it and nothing compared it with this camera's own firmware. If you've chosen a backup,
  the file is checked against it first.
- The app still refuses a file that fails its checks, the withdrawn candidates A and B, a backup or dump given as the
  update file, and a write while a backup is still being read or a check is still running (wait, then it checks
  again).
- Make a backup of this camera's flash chip if you can, read with a 3.3 V chip programmer on a computer, and keep it
  somewhere safe. The app can't read the chip. The backup is the only way back to exactly what the camera ran,
  settings included, and it's what the app compares the update file with. Without one, a camera that won't boot can
  still take the public stock file for its sensor through a chip programmer
  ([Recovery, section B](../docs/02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)). With a backup chosen, the app refuses a file of your own
  whose image-sensor set-up differs from the firmware in the backup, because that's what leaves a camera unable to
  take pictures. For a reviewed file it shows a "Wrong sensor?" warning instead and writes it if you go ahead. A
  backup read after the camera was flashed holds the flashed firmware, so it shows what the camera runs now. When
  that's one of the known builds, the app shows a matching set-up as a warning
  ([After a flash](../docs/01-FIRST-FLASH.md#after-a-flash)).
- A camera that boots but can't take pictures after a flash has firmware for the other sensor. The card updater still
  runs, so flashing a build that fits its sensor undoes it: stock v012, v0.25 to v0.27 or the bring-up on an OV9732
  camera, the stock restore file on an SP140A one. Photos it took before the bad flash tell you the sensor (Identify), and so does
  the probe. A camera with another sensor needs the donor route on a computer ([Recovery, section
  D](../docs/02-RECOVERY.md#the-donor-route)).
- Original 2025 series only: matte body, yellow Kodak boot screen. Never flash a Millennium Edition (blue boot screen)
  or a CLOT edition with these files. Owners report that the firmware boots there but the live view freezes or the
  camera can't take a picture ([Recovery, section D](../docs/02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera)).
  The same happened to an original-series camera, so the body alone proves nothing.
- Charge the battery fully first. Programming takes 15 to 20 seconds, and if the power goes in that window, only a
  chip programmer can bring the camera back ([Recovery](../docs/02-RECOVERY.md)).
- Save the stored photos if you want them. A 0x1E0000-byte file, such as the stock restore file, replaces the two
  photos the camera keeps in its own memory. The stock restore file carries the two photos of the camera it was dumped
  from, but its photo list is empty, so the camera doesn't show them. Your own two are gone either way. A 0x169000-byte
  file (made with `pack --official-size` on a computer) leaves the photo data in place, but the camera empties its
  photo list, so it no longer shows them. If yours matter, copy the `SPIDCIM` folder off the card first
  ([The first flash, step 5](../docs/01-FIRST-FLASH.md#5-flash-the-point-of-no-return)).

You can't tell whether a firmware fits a camera from the file, the version stamp or the boot screen. Every known build
(v006, v008_test and v012) carries the same stamp, and the public dump left one original-series camera unable to take
pictures. A dump of a camera's own chip read before it was ever flashed is the one file known to fit it, and any dump
of the chip is the way back to what it held when it was read. Reading the chip needs a chip programmer and a computer
([Recovery, section B](../docs/02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)). The
app can't tell whose chip a dump came from. It refuses the public dumps (the two public stock dumps, the stock dump's
cleaned copy and the cleaned copy of the factory build v012) and any read named as unverified
(`.unverified-read<n>.bin`), so give it only a dump read from this camera, from two reads that agreed.

## About the camera's USB-C connection

Connected by USB, the camera presents its SD card as a USB drive (USB ID 1B3F:8301, "GENPLUS USB-MSDC DISK A"). On
2026-11-06 the drive appeared on an Android phone connected over USB-C, the app wrote an update file to it, and the
camera flashed that file at its next power-on. The cleanup over the same cable worked too. The same run showed two
things, and the steps above are built around them. The camera looks for the update file only in the first five seconds
after power-on, so it doesn't notice a file added while it's running. And plugging the cable into a switched-off camera
switches it on and runs the updater, so never connect a camera with an update file on its card unless you want that
flash.

## Picking a base and mods

The write step of **Flash custom firmware** shows a base menu (v012, the factory build for OV9732 cameras; v006 and
v008_test, SP140A builds) and one box per mod of that base, with its description. You tick the mods one by one, and
the step's title names the version they make: on v012, the Volume and LED rows make v0.25, adding EXIF dates makes v0.26, and adding
boot to the last mode and capture flash makes v0.27. With no mods ticked it's stock v012. On v006, the OV9732 port
alone is v0.23, and with Volume/LED too it's v0.24. A file loaded with **Use a file on the phone** becomes the pick,
its base and its boxes. The app doesn't pick the base from what **Identify this camera's firmware** found. If the base
you pick v012 or v006 and it isn't the base it found, the step says so. Experimental mods (not yet tried on a
camera) are marked and add a warning, but can be ticked. A mod that conflicts with a ticked one is greyed out, with the
reason.

The app builds the pick from that base's dump (v012: the public cleaned copy, or the private read it was cleaned from
if you picked it; v006: the public stock dump; v008_test: its public file, downloaded at a fixed commit and checked by
SHA-256) with `Recipe.java`, the app's copy of charmera_toolkit/recipes.py. It keeps the result only when it's the
reviewed file `Recipes.java` lists for that base, those mods and that dump, under that file's name.

No pick needs a backup. You decide whether a file fits the camera. When nothing confirms which firmware the camera runs
(no backup chosen, and Identify not run or naming the other sensor's family), the file step and the write confirmation
say so. A chosen backup whose firmware drives the other sensor gives a loud "Wrong sensor?" warning. Neither one
refuses. Stock v012 (base v012 with no mods ticked) is the factory firmware of OV9732 cameras, written here like any
other pick, and the way back to stock from every v012 version. Stock v006 (the stock restore file) is made here but
written on Restore stock firmware, which compares it with your backup, or on Advanced. A combination marked experimental
says so even when each of its mods has been tested alone. The pick is remembered as base and mods. Details: [Picking a
base and mods](../docs/06-ANDROID.md#picking-a-base-and-mods).

## What it cannot do

- It can't read the chip. Reading the chip needs a chip programmer and a computer. The app only checks the backup file
  and compares the update file with it ([Recovery, section
  B](../docs/02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)).
- It can't make Android read a card the camera has marked dirty in a USB card reader or the phone's slot
  ([SD card problems](../docs/05-CARDS.md)). Over the camera's own cable the drive mounted and took the write in the
  2026-11-06 run. Whether Android can refuse the camera's drive the same way hasn't been tested. Don't format the card
  in the phone to get round it: that erases the photos Identify reads, and the flag comes back the next time the
  camera writes to the card. Copy the photos off on a computer, which mounts the card anyway, and clear the flag there
  with `charmera card-fix`.
- It makes no other mods. It makes only the stock restore file and the reviewed files (the sensor-ID probe, v0.23 to
  v0.27 and the other reviewed mixes you can tick in Flash custom firmware, see [Picking a base and
  mods](#picking-a-base-and-mods)). A file from your own backup, frames, a boot logo or start-up tweaks need the toolkit
  on a computer, or in Termux ([Using an Android phone](../docs/06-ANDROID.md)). The app then checks the file they
  produce, against your backup if you've chosen one, and writes it (without a backup, with the loud unknown-file
  warning). In Termux, `pip install` takes the toolkit straight from its GitHub URL.
- It has no donor route for an unreviewed dump from another camera. The v012 builds are reviewed builds for OV9732
  cameras, checked by SHA-256. For a camera whose own firmware is gone and whose sensor no reviewed build drives,
  another camera's firmware is the only help. With a backup chosen, the app refuses a file of your own whose
  image-sensor set-up differs from that backup. A computer does the donor route
  ([Recovery, section D](../docs/02-RECOVERY.md#the-donor-route)).
- Its read-back may read Android's cached copy instead of the card itself. Ejecting the card before removing it is what
  makes sure the data reached the card.
- It can't eject a drive by itself. Android lets only the system eject a drive, so the app opens the drive's page and
  waits to see it unmounted.
- It can't reach the card root on Android 11 and later. Android blocks the picker from granting access to the top level
  of the phone's internal storage and of an SD card in the phone's own slot. USB card readers and the camera's USB drive
  aren't blocked, so on those phones use the camera's cable or a reader. For the slot, tap **Save a copy to the phone**
  in Advanced (it saves any file the app would write, under the same rules), copy that file to the card's top level
  with the Files app, then choose the copy with **Use a file on the phone** to have it checked where it lies.

## What it downloads

The app uses the network for these things only. No firmware ships inside the app.

- The stock dump's cleaned copy, from
  [the-dumping-grounds/charmera-firmware-v006](https://github.com/the-dumping-grounds/charmera-firmware-v006) on
  GitHub, at a fixed commit. It accepts the download only if its SHA-256 is that copy's (`75b911e9…`). It keeps that
  checked copy in its own storage on the phone, checks it again each time it uses it, and downloads it again only if
  the copy is gone or no longer the stock dump.
- For v0.25, v0.26, v0.27 and other v012 picks, on a phone with no factory dump, the factory build v012's cleaned copy
  from [the-dumping-grounds/charmera-firmware-v012](https://github.com/the-dumping-grounds/charmera-firmware-v012) the
  same way (`63e39684…`). It keeps it until you pick the private read it was cleaned from.
- For a pick on base v008_test, build v008_test's public file from
  [the-dumping-grounds/charmera-firmware-v008_test](https://github.com/the-dumping-grounds/charmera-firmware-v008_test)
  the same way, at a fixed commit and checked by SHA-256.
- Check for app updates, only when you tap it, downloads the app's release APK from GitHub to compare its version with
  this one.

It reaches the card through Android's own folder picker, which grants access to the one folder you choose, so it needs
no storage permission.

## Version history

- 0.3 and 0.4 (2026-11-06): wrote candidate C, then the stock restore file, to one camera's card over the camera's
  USB-C cable. The camera flashed C twice, the second time when the cable was plugged into it while it was switched
  off, which the connection steps above now prevent. The writing, the flashes and the cleanup worked as the app
  describes them, but neither file fitted that camera.
- 0.5: added the backup step.
- 0.7: added the sensor-ID probe and rebuilt the screen as a column of cards.
- 0.24: split that column into separate routes, one job per screen ([Use](#use)), and added the photo backup, a flash
  log, test shots and a flash check.
- 0.26: added a version menu to Flash custom firmware (v0.26, v0.25 or v0.24).
- 0.28: added Custom to that menu, a base and its mods in any reviewed combination ([Picking a base and
  mods](#picking-a-base-and-mods)).
- 0.29: writes every reviewed file without a backup, with a warning when nothing confirms what the camera runs.
- 0.30: also writes a file it doesn't know without a backup, with a loud warning.
- 0.31: knows v012 (factory OV9732 firmware) when it checks a file or backup, so your own v012 read counts
  as a backup. Clearer next steps for SP140A owners going back to stock and for cameras with no photos left.
- 0.32 (current): Flash custom firmware drops its version menu. It shows the base menu and one box per mod, so
  you tick the features you want one by one.

On 2026-11-11 the app wrote the OV9732 bring-up, firmware v0.24 (Flash custom firmware), to an OV9732 camera over the
camera's USB-C cable, and the camera flashed it
([what the camera showed](../docs/15-OV9732-AE-WB-FLASH-PORT.md#10-v024-volume-and-led-rows-in-the-setup-menu)).

Version 0.4, the one published at the [android-app
release](https://github.com/daemond-dev/charmera-toolkit/releases/tag/android-app) before 0.5, had no backup step and
didn't refuse C, A or B. It was taken down on 2026-11-06. If you still have it installed, don't flash with it. The
plain-Java parts (the checker, the card rules, the file builder and its recipes, WriteGate, the rule the screen asks
before every write and save, the flash log, the flashing routes' steps, the photo header check, Identify and the date
repair) compile and pass their tests on a computer's JVM ([Build](#build)). The screen itself needs the Android SDK
and is built only by the workflow.

## Build

`./build.sh [versionName] [versionCode]` with `SDK` pointing at an Android SDK that has `platforms;android-35` and
`build-tools;35.0.0`, a JDK 17 or newer, and `zip`. The version name defaults to the one in `VERSION`. It first runs
the tests of the checker, the card rules, the file builder, the write gate, the flash log, the flashing routes' steps,
the photo header check and the date repair on the plain JVM (`test/UpdateCheckTest.java`, `test/CardStateTest.java`,
`test/CandidatesTest.java`, `test/WriteGateTest.java`, `test/FlashLogTest.java`, `test/FlashFlowTest.java`,
`test/PhotoIdTest.java` and `test/DateFixTest.java`).

Optional environment variables add checks against real files:

- `CHARMERA_STOCK_IMAGE`, the public stock dump (its cleaned copy or the original): CandidatesTest makes the restore
  file and the three withdrawn candidates and compares them with the published and reviewed files, and checks that the
  checker finds the dump's build marks by content.
- `CHARMERA_FILM_MODS_RESTORE`, the film-mods project's public restore file: UpdateCheckTest checks that the checker
  names that build, and CandidatesTest that it compares that file's vector table with its own build's.
- `CHARMERA_FACTORY_DUMP`, the factory build v012 (its cleaned copy or the original read): the tests make v0.25 and
  v0.26 from it.
- `CHARMERA_UPDATE_FILES`, real update files: the tests check those too.

Each test prints how many of its checks passed and failed, and stops the build when one fails. Then the script builds
and signs `out/charmera-card-<version>.apk`. No Gradle. For every build, the workflow downloads the cleaned v006 and
v012 copies and the film-mods restore file from the-dumping-grounds' firmware repositories, checks their SHA-256, and
runs those checks before it publishes. It doesn't set `CHARMERA_UPDATE_FILES`.

GitHub Actions builds it on every push to `main` that touches `android/` and publishes it as the `android-app` release
(`.github/workflows/android.yml`), one run at a time. Run from the Actions tab on another branch, it builds and keeps
the APK as a run artifact without publishing. Every published build is signed with the same release key, so a new
version installs over the old one. The key lives only in the repo secrets `SIGNING_KEYSTORE_B64`, `SIGNING_ALIAS` and
`SIGNING_PASS`. Before publishing, the workflow checks that the APK's certificate matches `SIGNING_CERT_SHA256` in this
folder, and it refuses to publish anything else. Without the secrets, a build is signed with a throwaway key and kept
only as a run artifact.

Keep a copy of the three secret values somewhere safe, because GitHub never shows a secret again once it's saved. If
the key is lost, make a new one, save it in the secrets, put its certificate SHA-256 (from `apksigner verify
--print-certs`) in `SIGNING_CERT_SHA256`, and uninstall the app once before installing the first build signed with it.
