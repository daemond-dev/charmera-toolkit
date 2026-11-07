# stage: putting the update file on the card

Reading order: 4 of 13 · previous: [Flash candidates](03-FLASH-CANDIDATES.md) · next: [SD card problems](05-CARDS.md) · [all docs](README.md)

`stage` is the last step before the camera. It copies one verified update file to the root of the camera's
SD card and proves the copy is good, so that the first time the camera sees the file is also the first time
anything could go wrong, and not before.

```
charmera stage UPDATE.bin --card <mount point or drive letter> --stock my_backup.bin [--donor donor.bin] [--plan-only] [--i-know-what-i-am-doing]
```

## Why a command for a file copy

The camera's SD updater is simple and unforgiving ([Research report, section 4](13-PHASE1-REPORT.md#4-the-sd-updater-flow), [Image layout](11-FORMAT.md)):

- On power-on it scans only the card root, only during the first 5 seconds, for the first file matching
  `JH_5408*.bin`. A file in a folder, or a card inserted after power-on, is never seen.
- The 12 digits after `JH_5408` must differ from the stamp compiled into the running firmware, or the file
  is ignored without a message.
- The 8 hex digits after the underscore must equal the 32-bit byte sum of the file, or the camera shows
  "Program error" and erases nothing. A renamed or truncated file fails this.
- The file is not deleted after flashing. A card left in the camera flashes again at every power-on.
- Two update files on one card is an undefined situation: the camera takes whichever its filesystem
  driver lists first.

Explorer or `cp` do none of these checks. `stage` does, and it also makes sure the bytes that reached the
card are the bytes that passed `verify`, which a plain copy to a cheap card through a cheap reader does not
guarantee.

## What it does, in order

1. **verify.** Runs the same checks as `charmera verify FILE --stock ... --allow-modified` and prints them with the
   same tags (PASS, FAIL, WARN, INFO). A modified software region is allowed, because that is what a mod is, and is
   shown as a WARN, never a PASS. These are FAIL on every route, the donor route below included, and no flag of
   `stage` changes that: several sensor programs that the code refers to, with different contents, in the file or in
   `--stock`; an ARM vector table (0x200..0x240) that differs from `--stock`'s in a file laid out like it, or, in a
   file laid out otherwise, from that of the known build whose layout the file has; any size but 0x1E0000 and 0x169000
   bytes (`stage` never passes `--allow-odd-size`), a 2 MiB file included; a wrong checksum; the shared stamp, or with
   `--stock` the stamp found in it (`stamp-camera`); a `--stock` that is not a complete read of a chip; a damaged
   resource entry; and the withdrawn candidates C, A and B. Outside the donor route these are FAIL too: missing build
   marks (the donor route takes a donor without a sensor table of the known kind as a WARN); a sensor table that
   differs from `--stock`'s, or a `--stock` with no sensor table of the kind the known builds use (without `--stock`, a
   table that is not the known builds'); and a table the code does not refer to while the one in `--stock` is referred
   to. Any FAIL refuses with exit 1. WARN lines are shown and do not block. Always give `--stock my_backup.bin`, a
   dump of the camera this card goes into: it is what the sensor table is compared with, and it makes the
   software-region line list the 64 KiB blocks that differ from your own dump. Only a dump read before the camera was
   ever flashed shows what fits it; when the dump is itself a known build, a matching sensor table is a WARN
   ([The first flash, After a flash](01-FIRST-FLASH.md#after-a-flash)), and when it is a build nobody has published,
   the same table is a PASS only when the program around it is the dump's too. No backup, no flash. Without `--stock`,
   `stage` shows a WARN line among its checks (no backup of this camera was given, so nothing compares the file with
   its own firmware, and a file that does not fit cannot be undone), says the same in the plan, and still writes a
   file that passes: the owner decided on 2026-11-06 that it should warn, not refuse. With `--donor` and no `--stock`
   it does not write at all.
   `--donor donor.bin` is the donor route for a camera whose own firmware is gone and does not fit it
   ([Recovery, section D](02-RECOVERY.md#the-donor-route)): it needs `--stock`, the file must be the donor's firmware
   unchanged, and its sensor table may then differ from `--stock`'s, as a WARN; a donor without a sensor table of the
   kind the known builds use is taken with a WARN too. The vector-table check and the other checks of the first list
   above run on this route as on every other. The output says that nothing checks whether the donor's camera has the
   same sensor and wiring, and that only a chip programmer can put `--stock` back if the donor's firmware does not
   start.
2. **The card.** `--card` must exist, be a directory, and be a mount point (Linux) or a drive root
   (Windows: `E:` or `E:\`). On Linux the device behind the mount and its filesystem type are read from
   `/proc/mounts`, and the whole disk's removable flag from sysfs (a card in a built-in slot counts as
   removable, the same rule `card-check --list` uses). On Windows the volume's filesystem, label and drive
   type come from the system. A non-removable volume and a filesystem the camera cannot read (NTFS, ext4,
   and the like) are refused. A card without a `DCIM` directory gets a warning, not a refusal: new cards
   have none. Free space is read with the OS and must cover the file plus a little slack.
3. **Other update files.** The root is listed and every `JH_*.bin`, in any letter case, counts. One file
   with the same name and byte-identical content means the file is already staged: `stage` says so and
   exits 0 without writing. Anything else is a refusal: remove the old file first. A file left over from an
   earlier update is the usual reason, since the camera never deletes it.
4. **The plan.** Source, sha256, byte sum, size, the card as the OS describes it, the destination path, how
   the copy is made, and the steps that follow on the camera (below). With `--plan-only` this is where it
   stops, exit 0, nothing written.
5. **Confirmation.** You type the `--card` value back. Anything else, an empty line, Ctrl-D or a closed
   stdin means "Not confirmed; nothing was written", exit 2. Without a terminal it does not ask at all and
   exits 2; there is no `--yes`, because the whole point of the command is the pause.
6. **The copy.** The root is listed once more (if an update file appeared meanwhile: refused). The file is
   written as `charmera-stage.part`, a name the camera does not match, flushed with `fsync`, renamed to the
   final name with `os.replace`, and the directory is synced where the platform allows it (Linux). Then the
   copy is read back from the card (on Linux the cached pages are dropped first so the read really goes to
   the medium) and its sha256 and size are compared with the source. A mismatch deletes the copy and exits 1.
   Any OS error is printed as one line with exit 1. A write or rename error leaves nothing under the final name. A read-back error happens after the rename, so the unverified copy is still on the card: delete it before the card goes into the camera, as the error line says.

Exit codes: 0 done (or already staged, or plan only); 1 a check failed (verify, another update file, space,
a refused volume, a failed write or read-back); 2 a usage problem or no confirmation.

## The sequence on the camera

Before you start: only an original 2025 series body (matte, yellow Kodak boot screen; never a Millennium or
CLOT edition, [Recovery, section D](02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera)). Charge the battery fully, then unplug the
cable. Programming takes 15 to 20 seconds and a power loss in that window leaves a camera that only a chip
programmer can bring back ([Recovery](02-RECOVERY.md)). Whether the updater runs on USB power without a battery
is not known. A 0x1E0000-byte file replaces the photos the camera stores in its own memory with the ones in the
file; the [flash candidates](03-FLASH-CANDIDATES.md) and any file packed with `--blank-photos` erase them. A
0x169000-byte file leaves the photo data in place, but the camera empties its photo list, so it no longer shows them
and the next photo it stores overwrites them.

1. Camera off. Put the card in. Then, and only then, power on: the updater arms only during the first five
   seconds after power-on and does not notice a card inserted later.
2. Hold the power key. The screen shows "Upgrade 0%" counting up to 100%, one step per 64 KiB block.
   "Program error" means the checksum in the name did not match the file; nothing was erased and the camera
   switches itself off. Remove the card and run `verify` again.
3. At 100% the camera stays on its last screen. Nothing reboots by itself, and the updater never deletes or
   renames the file. Take the card out, then hold the power key about a second until the camera switches off.
   Then power on without the card and check the boot screen, the live view and a shutter press.
4. Delete the update file from the card before using that card in the camera again, and eject the card before
   you take it out of the reader. Leave the file there and the camera flashes it again at every power-on.
   `stage` on the same card refuses until the old file is gone.

The camera's own USB-C cable can be the card reader: connected, the camera shows its card as a USB drive (seen on a
phone; this project has not tried it on a computer). Plugging the cable into a switched-off camera switches it on and
runs the updater from the card, so a camera whose card holds an update file flashes it again. Connect it the way the
app does, for staging and for deleting the file alike: switch the camera off if it is on, take the card out, switch
the camera on with its power key, wait 10 seconds (the updater looks only in the first five seconds after power-on),
put the card back in, then plug in the cable. Eject the drive before you unplug the cable, and unplug the cable before
step 1.

If the camera ignores the file (no "Upgrade" screen, normal boot), the updater did not see it. The causes:
the card went in after power-on (the updater looks only in the first five seconds); the file is in a folder
instead of the card root; the name was changed on the way, so it no longer starts with `JH_5408` and the
12-digit stamp (`stage` writes the exact name, a copy made by hand may not); or another `JH_5408*.bin` in the
root was found first. Switch the camera off, take the card out, look at the card root on a computer, and run
`stage` again: it refuses a card that holds another update file. The stamp is not the cause for a file
`verify` passed, unless the camera runs a build whose stamp is not known. No toolkit command changes the stamp
compiled into the firmware, so a camera running either known build, or flashed with a toolkit-built file, still runs
`202505201029`, and `verify` refuses that stamp in a name; with `--stock`, it also refuses the stamp found in your
backup (`stamp-camera`), which covers a camera that runs another build.

## What the refusals mean

| Message | Meaning | What to do |
|---|---|---|
| `FAIL ...` then `RESULT: verify failed` | The file itself is wrong: name checksum, stamp, size (only 0x1E0000 and 0x169000 bytes are taken), build. | Rebuild it with `pack`; never rename an update file by hand. |
| `WARN ...` no backup of this camera was given | Not a refusal: `--stock` is missing, so nothing compared the file with this camera's own firmware, and `stage` writes after it. | Stop and run it again with `--stock <your backup>`. No backup, no flash. |
| `FAIL ...` `--stock` is not a complete read of a chip | `--stock` is not a read of exactly 2 MiB that starts with `SPII` and carries the build string and the stamp: an update file, a cut-off read, something else. | Give the `<label>_<UTC time>.bin` that `backup` wrote when its reads agreed, not an update file made from it. |
| `WARN ...` `--stock` is byte-identical to a public dump | It is the public dump, or this camera's read after a flash with the public restore file. Either way it shows what the camera runs now, not what fits it. | Read [Recovery, section D](02-RECOVERY.md#after-a-flash) before you flash. |
| `FAIL sensor-table` | The image-sensor set-up table differs from your `--stock` dump's, or `--stock` has no sensor table of the kind the known builds use (without `--stock`: the file's table is not the known builds'). No toolkit mod changes the table, and that difference is what leaves a camera unable to take pictures. No flag clears it outside the donor route. | Do not use the file on this camera. Without `--stock`, give it: your backup is the only reference that says anything about your camera. If `--stock` has no table of that kind, keep it and look at it with `inspect`: nothing here can compare a file with it. If your backup was read after the camera was flashed, the table in it is the flashed firmware's: if the camera's own firmware is already gone and the file is another, working camera's, see the donor route ([Recovery, section D](02-RECOVERY.md#the-donor-route)). |
| `WARN sensor-table` with a match | The table is the same as in `--stock`, and `--stock` is one of the known builds; or `--stock` is a build nobody has published and the program around the table (bytes 0..0x120000) differs from `--stock`'s, and a different program can drive the same sensor differently. | A match with a known build proves a fit only if your backup was read before the camera was ever flashed ([The first flash, After a flash](01-FIRST-FLASH.md#after-a-flash)). For a different program, check that the `software-region` line lists only the blocks your mod was meant to change. |
| `FAIL ...` several sensor programs | The file, or `--stock`, holds more than one sensor table the code refers to, with different contents, so nothing can tell which one the camera would run, or which one to compare with. | Do not use the file; rebuild it from your backup. If `--stock` holds them, look at it with `inspect`. |
| `FAIL ...` ARM vector table | Bytes 0x200..0x240, where the camera starts, differ from `--stock`'s in a file laid out like it, or from those of the known build whose layout the file has. A camera flashed with it may not start far enough to run the card updater again. | Do not use the file; no flag of `stage` accepts it, on any route. Rebuild it from your backup. |
| `FAIL stamp-camera` | The stamp in the name is the one found in `--stock`, so the camera would ignore the file. | Rebuild it with `pack`, which gives it a fresh stamp. |
| `FAIL ...` candidate C, A or B | The file is one of the withdrawn [flash candidates](03-FLASH-CANDIDATES.md), known by its SHA-256. | Do not flash it. Make a file from your own backup instead. |
| `--donor needs --stock` | The donor route was asked for without a read of this camera's own chip. | Read the chip first ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)) and give it as `--stock`. |
| `FAIL from-donor` | The file is not the donor's firmware unchanged. | Build it from `--donor` with `pack`, and change nothing in it. |
| `FAIL series` | The build marks (build string, stamp, sensor table) were not found. | Not Charmera firmware, or a damaged copy. Rebuild from a complete dump. |
| `FAIL resource-slots` | A resource entry is erased, cut off or not the kind its name says, typically from a truncated download or a damaged dump. | Rebuilding from the same input does not help: get a complete dump (check its SHA-256) and build again. |
| `... is not a mount point or drive root` | You gave a folder on the card, or the card is not mounted. | Give the mount point itself (`/run/media/deck/<label>`, `E:`). For a test on a plain folder add `--i-know-what-i-am-doing`. |
| `... does not exist` / `... is not a directory` | Wrong path, card not inserted, or a device path (`/dev/sdb1`). | `stage` takes the mounted path, not the device; `card-check` and `card-fix` take the device. |
| `... is not on removable media` | The mount is a fixed disk. | Check the path. `--i-know-what-i-am-doing` overrides if the card really shows up as fixed. |
| `... is ext4; the camera reads exFAT and FAT32 only` | The card is formatted with something the camera cannot read. | `card-fix --format-fat32` ([SD card problems](05-CARDS.md)) or let the camera format it. |
| `already holds N update file(s) in its root` | An earlier update file, or a different build of this one, is still there. | Delete it from the card, then run `stage` again. |
| `... is already on the card and is byte-identical` | Not a refusal: the staged copy is already there. Exit 0. | Go to the camera. |
| `has ... free; the file needs ...` | The card is full. | Make room or use another card. |
| `Not confirmed; nothing was written.` | The typed text did not match `--card`. | Run again and type the card path exactly (a trailing slash is forgiven). |
| `no terminal to confirm on` | stdin is not a terminal: a pipe, a script, or Git Bash on Windows (mintty does not present a terminal to Python). | Run it from cmd, PowerShell or Windows Terminal, or prefix it with `winpty` under Git Bash; `--plan-only` shows the plan without asking. |
| `the card changed since the plan was printed` | An update file appeared between the plan and the write. | Look at the card, remove what should not be there, run again. |
| `read-back MISMATCH` | The bytes read back from the card differ from the file. The copy was deleted. | Use another card or reader. Do not put that card in the camera. |
| `writing to ... failed: ...` | The OS refused the write (read-only card, full card, pulled card). | Fix the cause; if a `charmera-stage.part` remains on the card, delete it. |

## Windows

```
charmera verify out\JH_5408202611041200_0F065D55.bin --stock backups\my_backup.bin
charmera stage  out\JH_5408202611041200_0F065D55.bin --card E: --stock backups\my_backup.bin
```

`E:` and `E:\` both work. The plan names the volume (`exFAT, label CHARMERA, 29.7 GiB free of 29.7 GiB, DCIM
present`) and the destination `E:\JH_5408202611041200_0F065D55.bin`; the confirmation is `E:` typed back
(letter case does not matter). Windows cannot sync a directory the way Linux does and the read-back may be
served from the cache, so when it reports a match, still use "Safely Remove Hardware" (or Explorer's Eject)
before pulling the card. No administrator prompt is needed; this is an ordinary file write.

## Linux

```
lsblk -o NAME,SIZE,RM,FSTYPE,LABEL,MOUNTPOINTS          # find the card and where it is mounted
charmera stage out/JH_5408202611041200_0F065D55.bin --card /media/$USER/CHARMERA --stock backups/my_backup.bin
udisksctl unmount -b /dev/sdb1                           # or the file manager's eject
```

Desktop environments mount a card under `/media/<user>/<label>` or `/run/media/<user>/<label>`; a camera-
formatted card has no label and shows up by its serial or UUID instead. `stage` reads the device and the
type from `/proc/mounts` and shows them in the plan (`exfat, /dev/sdb1, ...`). No `sudo` is needed; writing
to the mounted card is a normal file operation, and the mount point is the one place the command accepts.

### Steam Deck

In desktop mode a card in the Deck's own slot mounts under `/run/media/deck/<label>` (a card formatted by the
camera, which has no label, appears under its serial number, for example `/run/media/deck/0123-4567`); a card
in a USB reader mounts under `/run/media/deck/<label>` as well. In Konsole:

```
ls /run/media/deck/
python -m charmera_toolkit stage out/JH_5408202611041200_0F065D55.bin --card /run/media/deck/0123-4567 --stock backups/my_backup.bin
```

The Deck's slot reports the card as `mmcblk0`, which sysfs marks as not removable; `stage` uses the mmc type
instead (an SD card is removable), the same rule as `card-check --list`, so the Deck's own slot is accepted.
Eject with the Dolphin eject button or `udisksctl unmount -b /dev/mmcblk0p1` before taking the card out.
Gaming mode formats cards as ext4 for games; such a card is refused, and the camera could not read it anyway.

## Notes

- The camera formats cards as exFAT and reads exFAT and FAT32. Either works for the update. Phones often
  refuse the camera's exFAT card because the camera never clears the dirty bit; [SD card problems](05-CARDS.md) explains it
  and `card-fix` repairs it. That problem does not affect the updater.
- `--i-know-what-i-am-doing` exists for testing the command on a plain folder, and overrides the removable
  and filesystem refusals. It does not override a verify FAIL, another update file, or missing space.
- Only the update file is written to the card. `stage` never formats, never touches `DCIM`, and never
  deletes anything except its own failed copy.
- A `charmera-stage.part` on a card is an interrupted run. The camera ignores it; delete it.
- [The first flash](01-FIRST-FLASH.md) puts this step in the sequence from backup to first flash.
- On a phone, the [Charmera Card app](../android/README.md) does the same job.

---

Reading order: 4 of 13 · previous: [Flash candidates](03-FLASH-CANDIDATES.md) · next: [SD card problems](05-CARDS.md) · [all docs](README.md)
