# Using an Android phone

Reading order: 6 of 13 · previous: [SD card problems](05-CARDS.md) · next: [Command reference](07-USAGE.md) · [all docs](README.md)

Short version: a phone can do the SD-card route, which covers every normal flash. It cannot do the chip-level
route (CH341A), which is what you need for the first full backup and for a camera that no longer boots. Nothing
in this file has been tried on a phone yet; the parts that are inference are marked.

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
charmera pack --input charmera_stock_latest.bin --out out/ ...
charmera verify out/JH_5408<stamp>_<sum>.bin --stock charmera_stock_latest.bin
```

Run `termux-setup-storage` once if you want Termux to read files from the phone's Downloads folder
(`~/storage/downloads`).

## 2. Get the card readable by the phone

This is the real obstacle. A card the camera has used is exFAT with its "volume dirty" flag set and an MBR type
Android does not expect, and Android refuses to mount it ([SD card problems](05-CARDS.md)). Two ways round it:

- Format the card in the phone (Settings, Storage, the SD card, Format, as portable storage). This erases the card.
  Android writes a clean exFAT or FAT32 volume. The camera reads both file systems, so it should read a
  phone-formatted card; that is inference, not tested.
- Or run `charmera card-fix` on a computer or the Steam Deck once, which clears the flag without erasing photos.

After the camera has written to the card again, the flag comes back.

## 3. Copy the file to the card root

Use the phone's Files app: copy the `.bin` file to the top level of the SD card, not into `DCIM` or any other
folder. Then check that the name is exactly what `pack` printed, including the 8-character sum after the
underscore; some apps add `(1)` or change case when a file of the same name exists, and a renamed file is either
ignored or rejected with "Program error". Delete any older `JH_5408*.bin` from the card root first.

`charmera stage` does these checks for you on a computer. In Termux it probably cannot help: Termux normally
can only write to its own folder on a removable card (`Android/data/com.termux/files`), not to the root, which
is where the camera looks.

## 4. Flash

Card into the powered-off camera, then follow [step 5 of The first flash](01-FIRST-FLASH.md#5-flash-the-point-of-no-return): power on, wait for the update to
finish, power off, take the card out, and delete the update file from it before the card goes back into the camera.

## Through the camera's own USB port (untested)

When connected by USB the camera presents its card as a storage device (USB ID 1B3F:8301, "GENPLUS USB-MSDC
DISK A"). With a USB-C OTG adapter the phone may be able to write to the card while it sits in the camera, which
saves taking the card out. The same dirty-flag problem applies, and nobody has reported trying it.

## What needs a computer

- The CH341A route (`probe`, `backup`, `restore`). flashrom needs direct access to the programmer over USB.
  No maintained way to run it on an unrooted phone is known; a rooted phone with an OTG cable might manage,
  but that is untested guesswork. The Steam Deck runs flashrom and the toolkit's backup and restore scripts as any Linux
  machine does ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)).
- `card-check` and `card-fix`, which open the raw card device. Android does not give apps that access without root.

---

Reading order: 6 of 13 · previous: [SD card problems](05-CARDS.md) · next: [Command reference](07-USAGE.md) · [all docs](README.md)
