# Using the toolkit

Reading order: 7 of 13 · previous: [Using an Android phone](06-ANDROID.md) · next: [Photo frames](08-FRAMES.md) · [all docs](README.md)

All commands run as `python -m charmera_toolkit <command>` (or `charmera <command>` after `pip install -e .`).
Python 3.9 or newer, Windows or Linux, no extra packages.

## Read-only commands

```
charmera inspect my_backup.bin --settings --resources
charmera verify JH_5408202611041200_0F065D55.bin --stock my_backup.bin
charmera diff my_backup.bin modified.bin --ranges
charmera sounds list my_backup.bin
charmera sounds extract my_backup.bin --out sounds/
charmera card-check --list
charmera card-check /dev/sdX            # Linux
charmera card-check \\.\PhysicalDrive2  # Windows (run as administrator)
charmera exif-fix --source E:\ --out C:\Photos\charmera
charmera resources list my_backup.bin                                # all 43 resource entries, kinds and lengths
charmera resources extract my_backup.bin --out resources/            # each entry trimmed to its real length
```

`inspect` tells you what a file is: dump or update file, which build it carries (named by a fingerprint over its
bytes, never by the version stamp, which two different builds share), where its build marks and its image-sensor
set-up table were found, whether its software region is byte-identical to the public dump's, what the resource pack
and sound slots contain, which settings record is current (and whether UI sounds are on), and whether it holds another
owner's photos.

`verify` is the gate before a card. It checks the filename checksum, the stamp, the size rule, the magic, the build
marks, the sensor table, the ARM vector table and the software region, and says what each result proves; it refuses
the withdrawn candidates C, A and B by their SHA-256. Always give `--stock <a dump of the camera the file goes into>`:
the sensor table and the software region are compared with it, and the stamp in the file's name must differ from the
one in it (`stamp-camera`); that comparison is the only check that says anything about your camera. `--stock` must
be a complete read of a chip (exactly 2 MiB, starting with `SPII`, with the build string and the stamp), or `verify`
fails; one that is byte for byte a public dump is a WARN, since it may be this camera's own read after a flash with
the public restore file. Only a dump read before the camera was ever flashed shows what fits it: when `--stock` is one
of the known builds, a matching sensor table is a WARN, not a PASS. A FAIL line means do not use the file.

`--allow-modified` accepts a software region that differs from the reference: any mod of the code or the resources
needs it, and the line is then a WARN, never a PASS. Settings-only files pass without it. `stage` always uses it.
`--allow-unknown-build` lets through a file whose build marks are not all found, or, without `--stock`, a sensor table
that is not the known builds'; the line it lets through is a WARN, the software comparison with `--stock` still runs,
and a WARN says when no sensor comparison was possible. `--allow-odd-size` lets through a size other than the two in
use, 0x1E0000 and 0x169000 bytes, as a WARN, and the `final-erase` line names the block the updater would then erase
(a 2 MiB file still fails: its final erase wraps to block 1). `stage` never gives either flag.

What no flag accepts:

- A sensor table that differs from `--stock`'s (`sensor-table`): no toolkit mod changes that table, and a firmware
  that drives a sensor the camera does not have leaves it unable to take pictures. The one way past it is the donor
  route, `--donor <a read of another, working camera's chip>`, for a camera whose own firmware is gone and does not
  fit it; it needs `--stock`, takes only the donor's firmware unchanged, and says that the fit to this camera is
  unknown ([Recovery, section D](02-RECOVERY.md#the-donor-route)).
- More than one sensor program in a file, and, with `--stock`, a file whose sensor table the code does not refer to
  while the one in `--stock` is referred to. Without `--stock`, a table the code does not refer to is a WARN
  ([Image layout](11-FORMAT.md#build-identity)).
- An ARM vector table (bytes 0x200..0x240, where the camera starts) that differs from the reference's in a file laid
  out like it: a camera flashed with a broken one may not start far enough to run the card updater again. For a file
  laid out otherwise, a WARN says the table could not be compared.
- A damaged resource entry (`resource-slots`): every entry's slot must lie inside the resource area and the file,
  must not be erased, and must hold the kind its name says (a WAV with fmt and data chunks that fits its slot, a JPEG
  with an FF D9 end marker inside its slot, a GPZP stream that inflates, matches its CRC and length trailer and uses
  only fixed-Huffman blocks, a GPRS pack). The camera checks none of this, and it opens the boot logo at every boot
  before it reads the card, so a damaged logo can leave a camera the SD updater cannot reach. The rules are in
  [Image layout](11-FORMAT.md#resource-entries).

`diff` compares each resource entry over the larger of its two slots, so an entry erased or cut off in one file is
named (`ERASED in B`, `MISSING from B`) instead of being skipped.

## Commands that write files on your computer

```
charmera pack --input my_backup.bin --out out/restore/               # prints the plan only
charmera pack --input my_backup.bin --out out/restore/ --i-understand-this-file-flashes-a-camera
charmera sounds mute my_backup.bin --out muted.bin --method settings  # prints the plan only
charmera sounds mute my_backup.bin --out muted.bin --method both --i-understand-this-is-a-mod
charmera sounds replace my_backup.bin --out custom.bin --slot IMG.WAV --wav shutter.wav --i-understand-this-is-a-mod
charmera settings show my_backup.bin                                 # what the camera will load
charmera settings set my_backup.bin --out quiet.bin --ui-sound 0 --auto-off 10 --i-understand-this-is-a-mod
charmera tweak list                                                  # experimental start-up / power-off patches
charmera tweak apply my_backup.bin --out fast.bin --enable recommended --i-understand-this-is-a-mod
charmera frames list my_backup.bin                                   # the four photo frames and their byte budgets
charmera frames extract my_backup.bin --out frames/                  # 800x600 PNGs with transparency, plus thumbnails
charmera frames preview my_backup.bin --slot 1 --photo photo-800x600.png --out preview.png
charmera frames replace my_backup.bin --slot 1 --png frame.png --out framed.bin                  # prints the plan only
charmera frames replace my_backup.bin --slot 1 --png frame.png --thumb thumb.png --out framed.bin --i-understand-this-is-a-mod
charmera resources replace my_backup.bin --name POWER_ON_LOGO.JPG --file logo.jpg --out logo.bin --strip-metadata --i-understand-this-is-a-mod
```

`pack` takes a 2 MiB dump (trimmed to 0x1E0000), a 0x1E0000 update file, a 0x169000-byte official-size update file,
or a software-only image of exactly 0x170000 bytes (the last two padded with an erased settings bank and a zeroed photo
directory; the plan shows the padding, and names any damaged resource entry that `verify` will refuse). It refuses any
other length below 0x1E0000 as truncated, because padding a cut-off download would put 0xFF over part of the code or
the resource pack. From a 2 MiB dump it refuses only one whose last block, 0x1F0000..0x200000, is not blank, since
the updater's final erase clears that block; data in 0x1E0000..0x1EFFFF is left out of the file but stays on the chip,
because a 0x1E0000-byte flash never writes or erases that block, so `pack` only notes it. `--official-size` cuts the
result at 0x169000, where the film-mods files end (and official updates would, by that project author's reading of
the updater; no official Charmera update has ever been published): the file then writes the code and the resources
only, nothing of another camera's settings or photos, and the camera's final erase lands on the settings block, so it
starts with factory settings and an empty photo list while its stored photo data and the blocks above 0x1E0000 are
left alone ([Image layout](11-FORMAT.md#update-file-jh_5408stamp_sumbin)). `--out` must be a folder on the computer:
`pack` refuses a drive root or mount point, a folder on removable media, a folder that has a DCIM folder (that is a
card; put the file there with `stage`), and one that already holds another `JH_*.bin`. `--factory-settings` keeps
only the first CRC-valid settings record and erases the rest of the bank; it zeroes the photo directory only when that
sector is erased, so stored photos stay unless you add `--blank-photos`. `--stamp` takes 12 ASCII digits 0-9. `pack`
refuses an input without the Charmera build marks unless `--allow-unknown-build` is given, and notes a build it does
not know.

`settings set` keeps everything else in the record and leaves that record as the only one in the bank. `tweak apply`
refuses any patch whose original bytes are not where it expects them, so it cannot land on the wrong build or on
top of an earlier edit; every tweak is untested on hardware and labelled with its risk. `recommended` is boot-delay,
lcd-poll and fast-poweroff: it shortens only fixed waits where nothing is loading and keeps the power-on logo, which
is on screen while the camera mounts the card. `frames replace` takes an
800x600 RGBA PNG (transparent = see-through), re-encodes it the way the camera stores frames, and refuses with a
per-strip table when the result does not fit the slot's byte budget; see [Photo frames](08-FRAMES.md).

The gate flags exist so that nothing flashable appears by accident. A modified image goes through `pack` and then `verify`
before it is allowed near a card. The mod commands (`sounds`, `settings set`, `tweak`, `frames`, `resources`) write a
full image of their input's size, which must never go on a card as it is, so they refuse an `--out` whose name looks
like an update file (`JH_*.bin`, in any letter case) and an `--out` in a folder that holds a DCIM folder or a
`JH_*.bin`. `inspect` says the same of a file named like an update file whose size is not 0x1E0000 or 0x169000
bytes: it must not go on a card.

`resources replace` swaps a JPEG resource, such as the boot logo, only for a baseline JPEG with the same size,
component count and subsampling as the original that fits the slot; see [Resource pack and boot logo](09-RESOURCES.md). A custom boot logo has
not been tried on a camera.

## Commands that write to hardware

```
charmera stage out/restore/JH_5408<stamp>_<sum>.bin --card E: --stock my_backup.bin                         # Windows
charmera stage out/restore/JH_5408<stamp>_<sum>.bin --card /run/media/deck/<label> --stock my_backup.bin    # Steam Deck
charmera stage out/restore/JH_5408<stamp>_<sum>.bin --card E: --stock my_backup.bin --plan-only             # all checks, writes nothing
charmera restore --image my_backup.bin --backup <backup of the chip as it is now>.bin    # the chip, through a CH341A clip
```

`stage` runs `verify` again, refuses a card that already holds another update file, copies the file to the card
root, and reads it back to compare hashes. Without `--stock` it shows a WARN line saying that no backup of this camera
was given, so nothing compares the file with its own firmware and a file that does not fit cannot be undone, and
still writes a file that passes (the owner's decision of 2026-11-06). See [Putting the file on the card](04-STAGE.md).

`probe` and `backup` ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly))
only read the chip, and print a checklist before they run: a 3.3 V programmer (never an unmodified black CH341A), the
battery lead lifted, pin 1 of the clip on the chip's dot. `restore` reads the chip twice before it writes and refuses
when the reads differ, keeps that read as a backup with its `.sha256` and manifest, shows the image's sensor table
next to the read's and asks a second time when they differ, and has flashrom write a private copy of the bytes it
checked, never your file in place.

`card-fix` and `stage` print what they are about to do and wait for you to type the target back; `restore` waits for the word RESTORE. See [SD card problems](05-CARDS.md) and
[Recovery](02-RECOVERY.md).

---

Reading order: 7 of 13 · previous: [Using an Android phone](06-ANDROID.md) · next: [Photo frames](08-FRAMES.md) · [all docs](README.md)
