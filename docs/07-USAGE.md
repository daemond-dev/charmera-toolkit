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
marks, the sensor table and the software region, and says what each result proves. Always give `--stock <a dump of
the camera the file goes into>`: the sensor table and the software region are compared with it, and that comparison is
the only check that says anything about your camera. A FAIL line means do not use the file.

`--allow-modified` accepts a software region that differs from the reference: any mod of the code or the resources
needs it. Settings-only files pass without it. `stage` always uses it.
Two things it does not accept. A sensor table that differs from `--stock`'s (`sensor-table`), or, without `--stock`,
from the known builds', fails unless `--allow-unknown-build` is given, and `stage` never gives it: no toolkit mod
changes that table, and a firmware that drives a sensor the camera does not have leaves it unable to take pictures.
The one way past it is the donor route, `--donor <a read of another, working camera's chip>`, for a camera whose own
firmware is gone and does not fit it; it needs `--stock`, takes only the donor's firmware unchanged, and says that the
fit to this camera is unknown ([Recovery, section D](02-RECOVERY.md#the-donor-route)).
A damaged resource entry (`resource-slots`) always fails: every entry's slot must lie inside the
resource area and the file, must not be erased, and must hold the kind its name says (a WAV with fmt and data chunks that fits its slot, a JPEG
with an FF D9 end marker inside its slot, a GPZP stream that inflates, matches its CRC and length trailer and uses only
fixed-Huffman blocks, a GPRS pack). The camera checks none of this, and it opens the boot logo at every boot before
it reads the card, so a damaged logo can leave a camera the SD updater cannot reach. The rules are in
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
the resource pack. `--official-size` cuts the result at 0x169000, where official updates end: the file then writes the
code and the resources only, nothing of another camera's settings or photos, and the camera's final erase lands on the
settings block, so it starts with factory settings and an empty photo list while its stored photo data and the blocks
above 0x1E0000 are left alone ([Image layout](11-FORMAT.md#update-file-jh_5408stamp_sumbin)). `--out` must be a folder
on the computer: `pack` refuses one that has a DCIM folder (that is a card; put the file there with `stage`) or that
already holds another `JH_*.bin`. `--factory-settings` keeps only the first CRC-valid settings record and erases the
rest of the bank; it zeroes the photo directory only when that sector is erased, so stored photos stay unless you add
`--blank-photos`. `--stamp` takes 12 ASCII digits 0-9. `pack` refuses an input without the Charmera build marks unless
`--allow-unknown-build` is given, and notes a build it does not know.

`settings set` keeps everything else in the record and leaves that record as the only one in the bank. `tweak apply`
refuses any patch whose original bytes are not where it expects them, so it cannot land on the wrong build or on
top of an earlier edit; every tweak is untested on hardware and labelled with its risk. `recommended` is boot-delay,
lcd-poll and fast-poweroff: it shortens only fixed waits where nothing is loading and keeps the power-on logo, which
is on screen while the camera mounts the card. `frames replace` takes an
800x600 RGBA PNG (transparent = see-through), re-encodes it the way the camera stores frames, and refuses with a
per-strip table when the result does not fit the slot's byte budget; see [Photo frames](08-FRAMES.md).

The gate flags exist so that nothing flashable appears by accident. A modified image goes through `pack` and then `verify`
before it is allowed near a card.

`resources replace` swaps a JPEG resource, such as the boot logo, only for a baseline JPEG with the same size,
component count and subsampling as the original that fits the slot; see [Resource pack and boot logo](09-RESOURCES.md). A custom boot logo has
not been tried on a camera.

## Commands that write to hardware

```
charmera stage out/JH_5408<stamp>_<sum>.bin --card E:                         # Windows
charmera stage out/JH_5408<stamp>_<sum>.bin --card /run/media/deck/<label>    # Steam Deck
charmera stage out/JH_5408<stamp>_<sum>.bin --card E: --plan-only             # all checks, writes nothing
```

`stage` runs `verify` again, refuses a card that already holds another update file, copies the file to the card
root, and reads it back to compare hashes. See [Putting the file on the card](04-STAGE.md).

`card-fix` and `stage` print what they are about to do and wait for you to type the target back; `restore` waits for the word RESTORE. See [SD card problems](05-CARDS.md) and
[Recovery](02-RECOVERY.md).

---

Reading order: 7 of 13 · previous: [Using an Android phone](06-ANDROID.md) · next: [Photo frames](08-FRAMES.md) · [all docs](README.md)
