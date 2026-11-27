# The card: why phones reject it, and how to fix it

Reading order: 5 of 15 · previous: [Putting the file on the card](04-STAGE.md) · next: [Using an Android phone](06-ANDROID.md) · [all docs](README.md)

`card-check` and `card-fix` work on the microSD card, not on the camera's firmware. They need a computer. What a
phone can do on its own is in [Using an Android phone](06-ANDROID.md).

## Why phones reject the card

The Charmera formats its card as exFAT, in one MBR partition of type 0x07, and it never clears the exFAT
VolumeDirty flag (bit 1 of VolumeFlags, bytes 106..107 of the boot sector). Every write session leaves the flag set.
Mac, Linux, Windows and iPhone mount the card anyway. Android doesn't, and offers to format the card instead.
Formatting it on the phone doesn't help for long, because the camera writes to the card again on the next shot and
the flag is back.

The fix that holds is FAT32, with two details to get right:

- The MBR partition type must be 0x0C (FAT32 LBA). `mkfs.fat` on Linux leaves the old 0x07 in the partition
  table, and iPhones refuse a FAT32 card whose type byte says 0x07. The SD Association formatter and Windows
  write 0x0C.
- FAT32 can't hold a file larger than 4 GiB. Photos and the camera's short clips are far below that.

The community that worked this out found that with FAT32 and type 0x0C, the same card mounts on Android, iPhone, Mac
and Linux. sparkyfen's Charmera notes are the source for the findings above. If the camera ever reformats the card
back to exFAT, `card-check` shows it, and `card-fix --format-fat32` puts FAT32 back.

If you want to keep exFAT, for instance for files over 4 GiB, clear the dirty bit with `card-fix --clear-dirty`
instead. It lasts until the camera next writes to the card, and then you have to do it again
before the card goes into an Android phone.

## Find the right disk first

Both commands take the whole disk, not a partition. You confirm everything `card-fix` does by typing the device path
back. For a reformat you type `FORMAT` followed by the path. It refuses a disk the OS reports as not removable. Typing
the path of the wrong removable disk back would still destroy that disk, so check twice.

Linux, including a Steam Deck in desktop mode (Konsole):

```
lsblk -o NAME,SIZE,RM,TYPE,MODEL,MOUNTPOINTS
python -m charmera_toolkit card-check --list
```

The card is the removable disk of the right size: `/dev/sdX` through a USB reader, `/dev/mmcblk0` in the Deck's
own slot, whose partitions are `mmcblk0p1` and so on. Unmount it before fixing: `udisksctl unmount -b /dev/mmcblk0p1` or
`sudo umount /dev/sdX1`. Reading and writing a block device needs `sudo`. On the Deck, set a password with `passwd`
once if you haven't.

Windows:

- Disk Management (Win+X, Disk Management): the card is "Disk N" with "Removable" in the left column.
- PowerShell: `Get-Disk` lists Number, FriendlyName, Size and BusType (USB or SD for a card).
- `python -m charmera_toolkit card-check --list` prints the same, with the drive letters on each disk.

Use `\\.\PhysicalDriveN` with that number, or just the card's drive letter (`E:`). The tool resolves the letter to
its physical drive and works on the whole disk. The Device line names that disk and every drive letter on it
(`PhysicalDrive2 (disk 2, letters on it: E:, F:), removable USB, ...`). When more than one letter sits on the disk, or
the letter you gave isn't the disk's first partition, `card-fix` stops and names the other letters. Pass
`--i-know-what-i-am-doing` if you mean the whole disk. Both commands need an administrator prompt. Right-click
PowerShell or Terminal and choose "Run as administrator".

## Check and fix the card

```
python -m charmera_toolkit card-check --list              # candidate disks; nothing is opened for writing
python -m charmera_toolkit card-check /dev/sdX            # or \\.\PhysicalDrive2, or E:, or --image card.img
```

`card-check` reads the MBR and the first partition's boot sector and prints:

- the partitions (type, start, size), and a GPT if there is one;
- the filesystem (exFAT, FAT32, FAT16/12 or unknown);
- for exFAT, the VolumeFlags with the dirty and media-failure bits, and the boot-region checksum;
- for FAT32, the BPB (sectors per cluster, reserved sectors, FATs, total sectors, FAT size, root cluster, geometry,
  drive number), the backup boot sector, FSInfo and the first FAT entries;
- a verdict per platform and the fix command.

Exit code 0 means no problem, 1 means it found a problem, and 2 means it couldn't look. It never writes.

It reports damage instead of crashing on it. A GPT whose entry array can't be read (bad entry size or count, or an
array past the end of the disk) and an exFAT boot sector that doesn't parse (for example BytesPerSectorShift out of
range) each become a problem line with `--format-fat32` as the fix. A disk or image that ends before its partition
table says exits 2 with "shorter than its partition table says".

```
python -m charmera_toolkit card-fix /dev/sdX --clear-dirty       # exFAT: clear VolumeDirty, main and backup boot sector
python -m charmera_toolkit card-fix /dev/sdX --fix-mbr-type      # FAT32 behind a 0x07 entry: set the type byte to 0x0C
python -m charmera_toolkit card-fix /dev/sdX --format-fat32      # erase: new MBR (one 0x0C partition at 1 MiB) and FAT32
        [--label CHARMERA] [--cluster-size 32K]
```

`--format-fat32` zeroes the first 4 MiB, so nothing of the old exFAT boot region survives, and writes an MBR with one
partition of type 0x0C starting at sector 2048. The FAT32 volume has 512-byte sectors, 32 reserved sectors, two
FATs, FSInfo at sector 1, the backup boot sector at 6, the root directory in cluster 2, the usual 63/255 geometry,
drive number 0x80, OEM name MSWIN4.1, a random serial, and the label both in the boot sector and as a root-directory
entry. Cluster size follows the usual table (4 KiB up to 8 GiB, 8 KiB to 16 GiB, 16 KiB to 32 GiB, 32 KiB above)
unless `--cluster-size` says otherwise. On small volumes it's reduced automatically so the cluster count stays
at or above 65525, which is what makes a volume FAT32.

`--clear-dirty` changes two bytes in the main boot sector and the same two in the backup boot sector (sector 12 of the
volume). The boot-region checksum in sector 11 leaves out exactly those bytes, so it stays valid and isn't touched.

Before writing, `card-fix` prints the device (path, removable flag, bus and model, for example `block device sdb, removable
USB, SanDisk Ultra`), its size and every operation, then asks you to type the device path back. For a reformat the
answer is `FORMAT` followed by the path exactly as you gave it (`Type FORMAT /dev/sdb to continue`), so the bare word
never erases anything. `--yes --i-know-what-i-am-doing` skips the question for scripts. On an image file, `--yes`
alone is enough.

It refuses:

- a disk larger than 512 GiB;
- a disk the OS reports as not removable (the same rule as `--list`, "not a card: not removable"). An unknown flag
  only warns, and `--i-know-what-i-am-doing` overrides the refusal when a card does show up as fixed;
- a mounted card (Linux: anything in /proc/mounts for the disk or its partitions);
- on Windows, a disk with more than one drive letter on it, unless `--i-know-what-i-am-doing` is given.

On Windows it locks and dismounts the card's volumes first, and stops if that fails. Close any Explorer windows
showing the card first. It reads the boot sector again right before each write. If it no longer parses, `card-fix` stops
with "the volume changed since the plan was printed; nothing written" and exits 2. Writes go through O_SYNC and
fsync, and the result is checked and printed afterwards.

On a card without an MBR partition table (a superfloppy or a GPT), `--fix-mbr-type` says so and does nothing. There's
no type byte to set, so `--format-fat32` is the fix.

`--image FILE` runs any of this on a disk image instead of a device. The tests use that.

## When exFAT is what you want

Keep exFAT only if you need files over 4 GiB. Then, after every camera session and before the card goes into an
Android phone, unmount it, optionally run `fsck.exfat -y /dev/sdX1`, then run `card-fix /dev/sdX --clear-dirty`.

---

Reading order: 5 of 15 · previous: [Putting the file on the card](04-STAGE.md) · next: [Using an Android phone](06-ANDROID.md) · [all docs](README.md)
