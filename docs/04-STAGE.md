# stage: putting the update file on the card

Reading order: 4 of 15 · previous: [Flash candidates](03-FLASH-CANDIDATES.md) · next: [SD card problems](05-CARDS.md) · [all docs](README.md)

`stage` is the last step before the camera. It copies one verified update file to the root of the camera's SD card
and reads it back to check that the copy matches.

```
charmera stage UPDATE.bin --card <mount point or drive letter> [--stock my_backup.bin] [--donor donor.bin] [--plan-only] [--i-know-what-i-am-doing]
```

## Why a command for a file copy

The camera's SD updater is simple and unforgiving ([Research report, section 4](13-PHASE1-REPORT.md#4-the-sd-updater-flow), [Image layout](11-FORMAT.md)):

- On power-on it scans only the card root, only during the first 5 seconds, for the first file matching
  `JH_5408*.bin`. A file in a folder, or a card inserted after power-on, is never seen.
- The 12 digits after `JH_5408` must differ from the stamp compiled into the running firmware, or the file
  is ignored without a message.
- The 8 hex digits after the underscore must equal the 32-bit byte sum of the file, or the camera shows
  "Program error" and erases nothing. A renamed or truncated file fails this.
- The file isn't deleted after flashing. A card left in the camera flashes again at every power-on.
- What happens with two update files on one card is undefined. The camera takes whichever its filesystem driver
  lists first.

Explorer and `cp` do none of these checks. `stage` does. It also makes sure the bytes that reached the card are the
bytes that passed `verify`, which a plain copy to a cheap card through a cheap reader doesn't guarantee.

## What it does, in order

1. Verify. `stage` runs the same checks as `charmera verify FILE --stock ... --allow-modified` and prints them with
   the same tags (PASS, FAIL, WARN, INFO). A modified software region is allowed, because that's what a mod is, and
   shows as a WARN, never a PASS.

   A file `charmera build` makes from a public copy is a reviewed file (one from your own read isn't), and `verify`
   knows it by its SHA-256. For one of those you'll see an INFO `reviewed` line that names the build, the dump it was
   made from and whether it has been tried on a camera. Its `series`, `build`, `software-region` and `sensor-table`
   lines are a WARN where another file would FAIL, and end "(a reviewed build: this is expected)". Without `--stock`
   it adds a WARN `unconfirmed` (not for the sensor-ID probe), since nothing confirms what the camera runs. With a
   `--stock` that is firmware for the other sensor it adds a WARN `wrong-sensor`. The first list below still applies
   to it in full. The second applies to every file that isn't a reviewed one.

   These are a FAIL on every route, the donor route below included, and no flag of `stage` changes that:

   - several sensor programs that the code refers to, with different contents, in the file or in `--stock`
   - an ARM vector table (0x200..0x240) that differs from `--stock`'s in a file laid out like it, or, in a file laid
     out otherwise, from that of the known build whose layout the file has
   - any size but 0x1E0000 and 0x169000 bytes, a 2 MiB file included (`stage` never passes `--allow-odd-size`)
   - a wrong checksum
   - the shared stamp, or with `--stock` the stamp found in it (`stamp-camera`)
   - a `--stock` that isn't a complete read of a chip
   - a damaged resource entry
   - the withdrawn candidates A and B. (C is named in an INFO line, because its bytes are the stock restore file made
     from the cleaned stock dump.)

   Outside the donor route these are a FAIL too, except in a reviewed file:

   - missing build marks (the donor route takes a donor without a sensor table of the known kind as a WARN)
   - a sensor table that differs from `--stock`'s, or a `--stock` with no sensor table of the kind the known builds
     use (without `--stock`, a table that isn't the known builds')
   - a table the code doesn't refer to while the one in `--stock` is referred to.

   Any FAIL refuses with exit 1. WARN lines are shown and don't block.

   When you have a backup, give it as `--stock my_backup.bin`, a dump of the camera this card goes into, so you know
   what your camera runs before you flash. The sensor table is compared with it, and it makes the software-region line
   list the 64 KiB blocks that differ from your own dump. Only a dump read before the camera was ever flashed shows
   what fits it. When the dump is itself a known build, a matching sensor table is a WARN ([The first flash, After a
   flash](01-FIRST-FLASH.md#after-a-flash)). When it's a build nobody has published, the same table is a PASS only
   when the program around it is the dump's too.

   Without `--stock`, `stage` shows a WARN line among its checks, says the same in the plan, and still writes a file
   that passes. The warning says no backup of this camera was given, so nothing compares the file with its own
   firmware, and tells you to find out what the camera runs first (its chip, or `charmera identify` on its photos).
   A file for the other sensor still boots, and flashing the stock build of the right base from the card undoes it
   (`charmera build --base v006 --download --out out/` for an SP140A camera, `charmera build --base v012 --download
   --out out/` for an OV9732 one). A flash that is cut off, or firmware that won't boot, needs a chip
   programmer. Warning instead of refusing was a deliberate choice, made on 2026-11-06.

   `--donor donor.bin` is the donor route, for a camera whose own firmware is gone and doesn't fit it ([Recovery,
   section D](02-RECOVERY.md#the-donor-route)). `--stock` is optional there. Without it a WARN `backup` says nothing
   compares the file with what the camera runs now, and that the way back is the update file the camera was last
   flashed with, from a card, as long as the new firmware starts far enough to run the updater. The file must be the
   donor's firmware unchanged, or a mod built on it that keeps the donor's boot header, vector table (0..0x240) and
   sensor table, which shows as a WARN. Its sensor table may then differ from `--stock`'s, as a WARN, and a donor
   without a sensor table of the kind the known builds use is taken with a WARN too. The vector-table check and the
   other checks in the first list above run on this route as on every other. The output says that nothing checks
   whether the donor's camera has the same sensor and wiring, and that only a chip programmer can bring the camera
   back if the donor's firmware doesn't start.
2. The card. `--card` must exist, be a directory, and be a mount point (Linux) or a drive root (Windows: `E:` or
   `E:\`). On Linux the device behind the mount and its filesystem type are read from `/proc/mounts`, and the whole
   disk's removable flag from sysfs. A card in a built-in slot counts as removable, the same rule `card-check --list`
   uses. On Windows the volume's filesystem, label and drive type come from the system. A non-removable volume and a
   filesystem the camera can't read (NTFS, ext4 and the like) are refused. A card without a `DCIM` directory gets a
   warning, not a refusal, since new cards have none. Free space is read from the OS and must cover the file plus a
   little slack.
3. Other update files. `stage` lists the root, and every `JH_*.bin`, in any letter case, counts. If the only one is
   a file with the same name and byte-identical content, the file is already staged: `stage` says so and exits 0
   without writing. Anything else is a refusal, and you remove the old file first. The usual reason is a file left
   over from an earlier update, since the camera never deletes it.
4. The plan. `stage` prints the source, sha256, byte sum, size, the card as the OS describes it, the destination
   path, how the copy is made, and the steps that follow on the camera (below). With `--plan-only` it stops here,
   exit 0, nothing written.
5. Confirmation. You type the `--card` value back. Anything else, an empty line, Ctrl-D or a closed stdin gives
   "Not confirmed; nothing was written", exit 2. Without a terminal it doesn't ask at all and exits 2. There's no
   `--yes`, because the whole point of the command is the pause.
6. The copy. `stage` lists the root once more and refuses if an update file appeared meanwhile. It writes the file
   as `charmera-stage.part`, a name the camera doesn't match, flushes it with `fsync`, renames it to the final name
   with `os.replace`, and syncs the directory where the platform allows it (Linux). Then it reads the copy back from
   the card and compares its sha256 and size with the source. On Linux it drops the cached pages first, so the read
   really goes to the medium. A mismatch deletes the copy and exits 1.

   Any OS error is printed as one line with exit 1. A write or rename error leaves nothing under the final name. A
   read-back error happens after the rename, so the unverified copy is still on the card. Delete it before the card
   goes into the camera, as the error line says.

Exit codes:

- 0: done, already staged, or plan only.
- 1: a check failed (verify, another update file, space, a refused volume, a failed write or read-back).
- 2: a usage problem or no confirmation.

## The sequence on the camera

Use only an original 2025 series body (matte, yellow Kodak boot screen), never a Millennium or CLOT edition
([Recovery, section D](02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera)). Charge the battery fully, then unplug
the cable. Programming takes 15 to 20 seconds, and a power loss in that window leaves a camera that only a chip
programmer can bring back ([Recovery](02-RECOVERY.md)). Whether the updater runs on USB power without a battery isn't
known.

A 0x1E0000-byte file replaces the photos the camera stores in its own memory with the ones in the file. The
[flash candidates](03-FLASH-CANDIDATES.md) and any file packed with `--blank-photos` erase them. A 0x169000-byte file
leaves the photo data in place, but the camera empties its photo list, so it no longer shows them and the next photo it
stores overwrites them.

1. Camera off. Put the card in. Only then power on. The updater arms only during the first five seconds after
   power-on and doesn't notice a card inserted later.
2. Hold the power key. The screen shows "Upgrade 0%" counting up to 100%, one step per 64 KiB block.
   "Program error" means the checksum in the name didn't match the file. Nothing was erased, and the camera
   switches itself off. Remove the card and run `verify` again.
3. At 100% the camera stays on its last screen. Nothing reboots by itself, and the updater never deletes or
   renames the file. Take the card out, then hold the power key about a second until the camera switches off.
   Then power on without the card and check the boot screen, the live view and a shutter press.
4. Delete the update file from the card before you use that card in the camera again, and eject the card before
   you take it out of the reader. If you leave the file there, the camera flashes it again at every power-on.
   `stage` on the same card refuses until the old file is gone.

The camera's own USB-C cable can be the card reader. Connected, the camera shows its card as a USB drive. (This has
been seen on a phone. This project hasn't tried it on a computer.) Plugging the cable into a switched-off camera
switches it on and runs the updater from the card, so a camera whose card holds an update file flashes it again.
Connect it the way the app does, both for staging and for deleting the file: switch the camera off if it's on, take
the card out, switch the camera on with its power key, wait 10 seconds (the updater looks only in the first five
seconds after power-on), put the card back in, then plug in the cable. Eject the drive before you unplug the cable,
and unplug the cable before step 1.

If the camera ignores the file (no "Upgrade" screen, normal boot), the updater didn't see it. The causes:

- the card went in after power-on (the updater looks only in the first five seconds)
- the file is in a folder instead of the card root
- the name changed on the way, so it no longer starts with `JH_5408` and the 12-digit stamp (`stage` writes the
  exact name, a copy made by hand may not)
- another `JH_5408*.bin` in the root was found first.

Switch the camera off, take the card out, look at the card root on a computer, and run `stage` again. It refuses a
card that holds another update file.

The stamp isn't the cause for a file `verify` passed, unless the camera runs a build whose stamp isn't known. No
toolkit command changes the stamp compiled into the firmware, so a camera running any known build, or flashed with
a toolkit-built file, still runs `202505201029`, and `verify` refuses that stamp in a name. With `--stock`, it also
refuses the stamp found in your backup (`stamp-camera`), which covers a camera that runs another build.

## What the refusals mean

| Message | Meaning | What to do |
|---|---|---|
| `FAIL ...` then `RESULT: verify failed` | The file itself is wrong: name checksum, stamp, size (only 0x1E0000 and 0x169000 bytes are taken), build. | Rebuild it with `build` or `pack`. Never rename an update file by hand. |
| `INFO reviewed` | Not a refusal. The file is a reviewed file from `charmera build`, named with its base, mods and dump, and whether it has been tried on a camera. | Read the WARN lines that follow it. |
| `WARN ...` no backup of this camera was given | Not a refusal. `--stock` is missing, so nothing compared the file with this camera's own firmware, and `stage` writes after it. | If you have a backup, run it again with `--stock <your backup>`. If not, know what the camera runs (`charmera identify` on its photos) before you confirm. |
| `WARN unconfirmed` | Not a refusal. A reviewed file and no `--stock`: the line names the sensor the build drives, and on a camera with the other sensor it boots but can't take pictures until it's flashed with firmware that fits. | The same as the line above. |
| `WARN wrong-sensor` | Not a refusal. A reviewed file, and `--stock` (or `--donor`) is firmware for the other sensor. If that read is the camera as it left the factory, the file can't take pictures on it. If it was read after a flash with the wrong firmware, it says nothing about the sensor. | Check the sensor with `charmera identify` on photos from before any flash, or with the sensor-ID probe, and build for that sensor. |
| `FAIL ...` `--stock` is not a complete read of a chip | `--stock` isn't a read of exactly 2 MiB that starts with `SPII` and carries the build string and the stamp. It's an update file, a cut-off read, or something else. | Give the `<label>_<UTC time>.bin` that `backup` wrote when its reads agreed, not an update file made from it. |
| `WARN ...` `--stock` is byte-identical to a public dump | It's the public dump, or this camera's read after a flash with the public restore file. Either way it shows only what the camera runs now. | Read [Recovery, section D](02-RECOVERY.md#after-a-flash) before you flash. |
| `WARN stock-read` or `WARN donor-read` | `--stock` or `--donor` is named like a read that no second read confirmed (`<label>_<UTC time>.unverified-read<n>.bin`): a single read, or reads that didn't agree. Such a read isn't a backup. A poor clip contact can leave wrong bytes in it that nothing here can see. | Read that chip again until two reads agree (`backup`), and give the `<label>_<UTC time>.bin` it then keeps. |
| `FAIL sensor-table` | The image-sensor set-up table differs from your `--stock` dump's, the file has none of the kind the known builds use while `--stock` has one, or `--stock` has none (without `--stock`: the file's table isn't the known builds'). No toolkit mod changes the table, and that difference is what leaves a camera unable to take pictures. The line names the sensor each side is for when it can tell. No flag clears it outside the donor route. A reviewed file from `build` gets a WARN here instead, with `wrong-sensor` when the sensors differ. | Don't use the file on this camera. If you left out `--stock`, add it. Your backup is the only reference that says anything about your camera. If `--stock` has no table of that kind, keep it and look at it with `inspect`, because nothing here can compare a file with it. If your backup was read after the camera was flashed, the table in it is the flashed firmware's. If the camera's own firmware is already gone and the file is another, working camera's, see the donor route ([Recovery, section D](02-RECOVERY.md#the-donor-route)). |
| `WARN sensor-table` with a match | The table is the same as in `--stock`, and `--stock` is one of the known builds. Or `--stock` is a build nobody has published and the program around the table (bytes 0..0x120000) differs from `--stock`'s, and a different program can drive the same sensor differently. | A match with a known build proves a fit only if your backup was read before the camera was ever flashed ([The first flash, After a flash](01-FIRST-FLASH.md#after-a-flash)). For a different program, check that the `software-region` line lists only the blocks your mod was meant to change. |
| `WARN sensor-table` without `--stock` | The file carries a known build's table: the SP140A one that v006 and v008_test carry, or v012's for the OmniVision OV9732. Nothing in the file says whether the camera has that sensor. | Run `charmera identify` on the camera's photos, or give `--stock` with a backup read before the camera was ever flashed. |
| `FAIL ...` several sensor programs | The file, or `--stock`, holds more than one sensor table the code refers to, with different contents, so nothing can tell which one the camera would run, or which one to compare with. | Don't use the file. Rebuild it from your backup. If `--stock` holds them, look at it with `inspect`. |
| `FAIL ...` ARM vector table | Bytes 0x200..0x240, where the camera starts, differ from `--stock`'s in a file laid out like it, or from those of the known build whose layout the file has. A camera flashed with it may not start far enough to run the card updater again. | Don't use the file. No flag of `stage` accepts it, on any route. Rebuild it from your backup. |
| `FAIL stamp-camera` | The stamp in the name is the one found in `--stock`, so the camera would ignore the file. | Rebuild it with `pack`, which gives it a fresh stamp. |
| `FAIL ...` candidate A or B | The file is one of the withdrawn [flash candidates](03-FLASH-CANDIDATES.md), known by its SHA-256. | Don't flash it. Make a file with `build`, or from your own backup, instead. |
| `INFO withdrawn` candidate C | The file is candidate C's bytes, which are also the stock restore file made from the cleaned stock dump. | Treat it as the stock restore file: only for a camera whose own firmware is the public build. |
| `FAIL from-donor` | The file isn't the donor's firmware unchanged, or it's a mod of it that changes the donor's boot header, vector table or sensor table. | Build it from `--donor` with `pack`. A mod built on it must keep those three. |
| `FAIL series` | The build marks (build string, stamp, sensor table) weren't found. | Not Charmera firmware, or a damaged copy. Rebuild from a complete dump. |
| `FAIL resource-slots` | A resource entry is erased, cut off or not the kind its name says, typically from a truncated download or a damaged dump. | Rebuilding from the same input doesn't help. Get a complete dump (check its SHA-256) and build again. |
| `... is not a mount point or drive root` | You gave a folder on the card, or the card isn't mounted. | Give the mount point itself (`/run/media/deck/<label>`, `E:`). For a test on a plain folder add `--i-know-what-i-am-doing`. |
| `... does not exist` / `... is not a directory` | Wrong path, card not inserted, or a device path (`/dev/sdb1`). | `stage` takes the mounted path, not the device. `card-check` and `card-fix` take the device. |
| `... is not on removable media` | The mount is a fixed disk. | Check the path. `--i-know-what-i-am-doing` overrides it if the card really shows up as fixed. |
| `... is ext4; the camera reads exFAT and FAT32 only` | The card is formatted with something the camera can't read. | `card-fix --format-fat32` ([SD card problems](05-CARDS.md)), or let the camera format it. |
| `already holds N update file(s) in its root` | An earlier update file, or a different build of this one, is still there. | Delete it from the card, then run `stage` again. |
| `... is already on the card and is byte-identical` | Not a refusal. The staged copy is already there. Exit 0. | Go to the camera. |
| `has ... free; the file needs ...` | The card is full. | Make room or use another card. |
| `Not confirmed; nothing was written.` | The typed text didn't match `--card`. | Run it again and type the card path exactly (a trailing slash is forgiven). |
| `no terminal to confirm on` | stdin isn't a terminal: a pipe, a script, or Git Bash on Windows (mintty doesn't present a terminal to Python). | Run it from cmd, PowerShell or Windows Terminal, or prefix it with `winpty` under Git Bash. `--plan-only` shows the plan without asking. |
| `the card changed since the plan was printed` | An update file appeared between the plan and the write. | Look at the card, remove what shouldn't be there, and run it again. |
| `read-back MISMATCH` | The bytes read back from the card differ from the file. The copy was deleted. | Use another card or reader. Don't put that card in the camera. |
| `writing to ... failed: ...` | The OS refused the write (read-only card, full card, pulled card). | Fix the cause. If a `charmera-stage.part` remains on the card, delete it. |

## Windows

```
charmera verify out\JH_5408202611041200_0F065D55.bin --stock backups\my_backup.bin
charmera stage  out\JH_5408202611041200_0F065D55.bin --card E: --stock backups\my_backup.bin
```

With no backup, leave out `--stock`. Both commands then warn that nothing compares the file with the camera, and
`stage` still writes when you confirm:

```
charmera verify out\JH_5408202611041200_0F065D55.bin
charmera stage  out\JH_5408202611041200_0F065D55.bin --card E:
```

`E:` and `E:\` both work. The plan names the volume (`exFAT, label CHARMERA, 29.7 GiB free of 29.7 GiB, DCIM
present`) and the destination `E:\JH_5408202611041200_0F065D55.bin`. To confirm, type `E:` back (letter case doesn't
matter). Windows can't sync a directory the way Linux does, and the read-back may be served from the cache, so even
when it reports a match, use "Safely Remove Hardware" (or Explorer's Eject) before you pull the card. You don't need
an administrator prompt, since this is an ordinary file write.

## Linux

```
lsblk -o NAME,SIZE,RM,FSTYPE,LABEL,MOUNTPOINTS          # find the card and where it is mounted
charmera stage out/JH_5408202611041200_0F065D55.bin --card /media/$USER/CHARMERA --stock backups/my_backup.bin
charmera stage out/JH_5408202611041200_0F065D55.bin --card /media/$USER/CHARMERA   # no backup: warns, still writes
udisksctl unmount -b /dev/sdb1                           # or the file manager's eject
```

Desktop environments mount a card under `/media/<user>/<label>` or `/run/media/<user>/<label>`. A card the camera
formatted has no label and shows up by its serial or UUID instead. `stage` reads the device and the type from
`/proc/mounts` and shows them in the plan (`exfat, /dev/sdb1, ...`). You don't need `sudo`. Writing to the mounted
card is a normal file operation, and the mount point is the one place the command accepts.

### Steam Deck

In desktop mode a card in the Deck's own slot mounts under `/run/media/deck/<label>`. A card formatted by the camera
has no label, so it appears under its serial number, for example `/run/media/deck/0123-4567`. A card in a USB reader
mounts under `/run/media/deck/<label>` as well. In Konsole:

```
ls /run/media/deck/
python -m charmera_toolkit stage out/JH_5408202611041200_0F065D55.bin --card /run/media/deck/0123-4567 --stock backups/my_backup.bin
```

Without a backup, the same line without `--stock` warns and still writes when you confirm.

The Deck's slot reports the card as `mmcblk0`, which sysfs marks as not removable. `stage` goes by the mmc type
instead (an SD card is removable), the same rule as `card-check --list`, so the Deck's own slot is accepted. Eject
with the Dolphin eject button or `udisksctl unmount -b /dev/mmcblk0p1` before you take the card out. Gaming mode
formats cards as ext4 for games. Such a card is refused, and the camera couldn't read it anyway.

## Notes

- The camera formats cards as exFAT and reads exFAT and FAT32. Either works for the update. Phones often
  refuse the camera's exFAT card because the camera never clears the dirty bit. [SD card problems](05-CARDS.md)
  explains it, and `card-fix` repairs it. That problem doesn't affect the updater.
- `--i-know-what-i-am-doing` exists for testing the command on a plain folder, and overrides the removable
  and filesystem refusals. It doesn't override a verify FAIL, another update file, or missing space.
- Only the update file is written to the card. `stage` never formats, never touches `DCIM`, and never
  deletes anything except its own failed copy.
- A `charmera-stage.part` on a card is left over from an interrupted run. The camera ignores it. Delete it.
- [The first flash](01-FIRST-FLASH.md) puts this step in the sequence from backup to first flash.
- On a phone, the [Charmera Card app](../android/README.md) does the same job.

---

Reading order: 4 of 15 · previous: [Flash candidates](03-FLASH-CANDIDATES.md) · next: [SD card problems](05-CARDS.md) · [all docs](README.md)
