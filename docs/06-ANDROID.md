# Using an Android phone

Reading order: 6 of 13 · previous: [SD card problems](05-CARDS.md) · next: [Command reference](07-USAGE.md) · [all docs](README.md)

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
  two public stock dumps and any read named as unverified (`.unverified-read<n>.bin`), so give it only a dump read
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
published stock restore file from it (it can make nothing else), compares that file with your backup, writes it to
the camera's card over the camera's USB-C cable, reads it back, and walks you through the flash and the cleanup. It
writes nothing without a backup on record. That is version 0.5.
Download [charmera-card.apk](https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk)
on the phone, and check which version you got: 0.5 is the release titled "Charmera Card for Android 0.5", and its
screen opens with "Before you start" and "No backup of this camera, no flash", then a step "0. Back up this camera
first". Version 0.4, published before it, had no backup step and did not refuse the withdrawn candidates C, A and B;
it was taken down on 2026-11-06, and if you still have it installed, do not flash with it. The rest of this page is
the same job done by hand, and what the app cannot help with.

Which way the card reaches the app depends on the Android version:

- Android 11 and later: Android does not let an app pick the top level of a card in the phone's own SD slot
  ("Use this folder" is greyed out there). Leave the card in the camera and connect the camera with its USB-C
  cable: the owner has confirmed that the camera shows its card as a drive on the phone
  ([below](#through-the-cameras-own-usb-port)). A USB card reader works too. Both can be picked at their top level.
- Android 7 to 10: the phone's own SD slot works as well.
- Only the slot, on Android 11 or later: copy the update file to the card's top level with the Files app
  ([section 3](#3-copy-the-file-to-the-card-root)), then pick that copied file in the app's step 1
  (**Use a file on the phone**), with your backup on record in step 0, to have it checked where it lies. If the check
  finds a problem, or there was no backup on record to compare the file with, delete the copy before the card goes
  into the camera. Delete it with the Files app after the flash too.

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
copy checked where it lies: pick it in the app's step 1 (**Use a file on the phone**), with your backup on record in
step 0. If the check finds a problem, or the app has no backup on record to compare it with, delete the copy before
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

1. Connect the camera the way the app's step 2 says: switch it off if it is on, take the card out, switch it on with
   its power key, wait 10 seconds, put the card back in, then connect it to the phone with a USB-C data cable. Pick
   the camera's drive in the app and write the file, as for a card reader.
2. Eject the drive in Android (Settings, Storage, the USB drive, Eject), then unplug the cable.
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

Reading order: 6 of 13 · previous: [SD card problems](05-CARDS.md) · next: [Command reference](07-USAGE.md) · [all docs](README.md)
