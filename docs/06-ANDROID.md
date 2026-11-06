# Using an Android phone

Reading order: 6 of 13 · previous: [SD card problems](05-CARDS.md) · next: [Command reference](07-USAGE.md) · [all docs](README.md)

Short version: a phone can do the SD-card route, which covers every normal flash. It cannot do the chip-level
route (CH341A), which is what you need for the first full backup and for a camera that no longer boots. Flashing
from a phone has not been tried yet. What has been seen on a phone: the camera, connected by USB-C, shows its card
as a drive. The parts that are inference are marked.

## Before you flash from a phone

The same rules apply as in [step 5 of The first flash](01-FIRST-FLASH.md#5-flash-the-point-of-no-return):

- Original 2025 series only: matte body, yellow Kodak boot screen. Never a Millennium Edition (blue boot
  screen) or a CLOT edition ([Recovery, section D](02-RECOVERY.md#d-wrong-edition)). The app checks the file,
  not the camera; no check on the file can tell which body it goes into.
- Charge the battery fully before every flash. A power loss while the camera programs itself leaves a camera
  that only a chip programmer can bring back ([Recovery, section C](02-RECOVERY.md#c-an-interrupted-flash)).
- The photos the camera stores in its own memory are replaced by the flash. The
  [flash candidates](03-FLASH-CANDIDATES.md) and any file packed with `--blank-photos` erase them; a file packed
  from the public dump without that option, as in step 1 below, replaces them with that owner's.

## The app

[Charmera Card](../android/README.md) does the whole job on the phone, with no computer: it downloads the public
stock dump, makes one of the three reviewed update files from it (it can make nothing else), writes it to the
camera's card over the camera's USB-C cable, reads it back, and walks you through the flash and the cleanup.
Download [charmera-card.apk](https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk)
on the phone (sign in to GitHub in the phone's browser first: the repository is private). The rest of this page is
the same job done by hand, and what the app cannot help with.

Which way the card reaches the app depends on the Android version:

- Android 11 and later: Android does not let an app pick the top level of a card in the phone's own SD slot
  ("Use this folder" is greyed out there). Leave the card in the camera and connect the camera with its USB-C
  cable: the owner has confirmed that the camera shows its card as a drive on the phone
  ([below](#through-the-cameras-own-usb-port)). A USB card reader works too. Both can be picked at their top level.
- Android 7 to 10: the phone's own SD slot works as well.
- Only the slot, on Android 11 or later: copy the update file to the card's top level with the Files app
  ([section 3](#3-copy-the-file-to-the-card-root)), then pick that copied file in the app's step 1
  (**Use a file on the phone**; the old name was Choose update file) to have it checked where it lies. Delete the
  copy with the Files app after the flash.

## What the phone has to do

The camera flashes itself. On power-on it looks in the card root for a file named
`JH_5408<stamp>_<sum>.bin`, checks the sum in the name, and writes the file to its own flash ([Image layout](11-FORMAT.md)).
So the phone's whole job is to put the right file, with its name unchanged, in the top folder of the card.

## 1. Get the update file

Either build it on a computer or the Steam Deck and copy it to the phone, or build it on the phone in Termux
(from F-Droid). The toolkit is plain Python with no extra packages, so it should run there; that is untested.

```
pkg install python git
pip install git+https://github.com/daemond-dev/charmera-toolkit
charmera pack --input charmera_stock_latest.bin --out out/restore/ ...
charmera verify out/JH_5408<stamp>_<sum>.bin --stock charmera_stock_latest.bin
```

The repository is private, so the `pip install git+https://...` line needs a GitHub token in the URL. Simpler:
download the repository as a ZIP in the phone's browser while signed in to GitHub, then run
`pip install ~/storage/downloads/charmera-toolkit-main.zip`.

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
ignored or rejected with "Program error". Delete any older `JH_5408*.bin` from the card root first. To have the
copy checked, pick it in the app's step 1.

Before you take the card out of the phone or the reader, eject it (Settings, Storage, the card, Eject), so
that everything written has reached the card.

`charmera stage` does these checks for you on a computer. In Termux it probably cannot help: Termux normally
can only write to its own folder on a removable card (`Android/data/com.termux/files`), not to the root, which
is where the camera looks.

## 4. Flash

Check the list in [Before you flash from a phone](#before-you-flash-from-a-phone) once more: original 2025
series body, battery fully charged, the stored photos saved or given up. Then follow
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
DISK A"), so the card can stay in the camera. The owner has confirmed that the drive appears on an Android phone
connected over USB-C; writing an update file that way and flashing from it has not been tried yet. The same
dirty-flag problem applies. The app's screens for this route are in its README:
[the app's five steps](../android/README.md#use).

1. Connect the camera to the phone with a USB-C data cable, pick the camera's drive in the app and write the
   file, as for a card reader.
2. Eject the drive in Android (Settings, Storage, the USB drive, Eject), then unplug the cable.
3. With a fully charged battery and no cable, switch the camera off, then on again. It only looks for the file
   in the first five seconds after power-on, so it did not notice the file while it was running. Whether the
   updater runs when the camera starts up while still plugged in is unknown, which is why the cable comes out
   first.
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

---

Reading order: 6 of 13 · previous: [SD card problems](05-CARDS.md) · next: [Command reference](07-USAGE.md) · [all docs](README.md)
