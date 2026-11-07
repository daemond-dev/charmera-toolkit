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
charmera photo-id this-camera/PICT0001.jpg donor/PICT0001.jpg        # which kind of firmware wrote these photos
```

`inspect` tells you what a file is: dump or update file, which build it carries (named by a fingerprint over its
bytes, never by the version stamp, which two different builds share), where its build marks and its image-sensor
set-up table were found, with the compile date and the source-tree folder, whether its software region is
byte-identical to the public dump's, what the resource pack and sound slots contain, which settings record is current
(and whether UI sounds are on), and whether it holds another owner's photos. A file is a known build "with changes"
(`public-dump-modified`, `film-mods-modified`) only when its build marks lie where that build keeps them and it
carries that build's stamp, sensor table, source-tree folder and compile date, as a mod made from it does; any other
file laid out like a known build is a build the toolkit does not know, "laid out like" it (`charmera-unknown-build`).
`verify`'s `build` line and the app say the same ([Image layout](11-FORMAT.md#build-identity)).

`verify` is the gate before a card. It checks the filename checksum, the stamp, the size rule, the magic, the build
marks, the sensor table, the ARM vector table and the software region, and says what each result proves; it refuses
the withdrawn candidates C, A and B by their SHA-256. Always give `--stock <a dump of the camera the file goes into>`:
the sensor table, the ARM vector table and the software region are compared with it, and the stamp in the file's name
must differ from the one in it (`stamp-camera`); that comparison is the only check that says anything about your
camera. `--stock` must be a complete read of a chip (exactly 2 MiB, starting with `SPII`, with the build string and
the stamp), or `verify` fails; one that is byte for byte a public dump is a WARN, since it may be this camera's own
read after a flash with the public restore file. A `--stock` named like a read that no second read confirmed
(`<label>_<UTC time>.unverified-read<n>.bin`, the name `backup` and `restore` give a single read and reads that
differ) is a WARN, `stock-read`, in `verify` and in `stage`, and so is a `--donor` named that way (`donor-read`):
such a read is not a backup, because a poor clip contact can leave wrong bytes in it that nothing here can see. Read
that chip again until two reads agree. The app refuses such a file as its backup. Only a dump read before the camera
was ever flashed shows what fits it: when `--stock` carries a known build's fingerprint, a matching sensor table is a
WARN, not a PASS. When `--stock` is a build nobody has published, a matching table is a PASS only when bytes
0..0x120000 (the code and its data) are `--stock`'s too; when only the table matches, a WARN says that the program
around it differs, since a different program can drive the same sensor differently. A FAIL line means do not use the
file.

`--allow-modified` accepts a software region that differs from the reference: any mod of the code or the resources
needs it, and the line is then a WARN, never a PASS. Settings-only files pass without it. `stage` always uses it.
`--allow-unknown-build` lets through a file whose build marks are not all found, or, without `--stock`, a sensor table
that is not the known builds'; the line it lets through is a WARN, and the software comparison with `--stock` still
runs. Without `--stock`, a file with no sensor table of the kind the known builds use then gets a WARN that says no
sensor comparison was possible. The flag never lets through a sensor table that differs from `--stock`'s, a file with
no sensor table of the known kind when `--stock` has one, or a `--stock` with none (below): with `--stock`, it lets
through a file without all the build marks only when the file still has a sensor table of the known kind.
`--allow-odd-size` lets through a size other than the two in use, 0x1E0000 and 0x169000 bytes, as a WARN, and the
`final-erase` line names the block the updater would then erase (a 2 MiB file still fails: its final erase wraps to
block 1). `stage` never gives either flag.

What no flag accepts:

- Outside the donor route, a sensor table that differs from `--stock`'s, a file with no sensor table of the kind the
  known builds use when `--stock` has one, or a `--stock` with none (`sensor-table`); `--allow-unknown-build` clears
  none of them. No toolkit mod changes that table, and a firmware that drives a sensor the camera does not have
  leaves it unable to take pictures, while a file without such a table leaves nothing to compare with the camera's
  own. The one way past them is the donor route, `--donor <a read of another, working camera's chip>`, for a camera
  whose own firmware is gone and does not fit it; it needs `--stock`, takes only the donor's firmware unchanged, and
  says that the fit to this camera is unknown ([Recovery, section D](02-RECOVERY.md#the-donor-route)).
- More than one sensor program that the code refers to, with different contents, in the file or in `--stock`, on
  every route (`several sensor programs`): which one sets up the sensor cannot be told. Outside the donor route, with
  `--stock`, also a file whose sensor table the code does not refer to while the one in `--stock` is referred to.
  Without `--stock`, a table the code does not refer to is a WARN ([Image layout](11-FORMAT.md#build-identity)).
- An ARM vector table (bytes 0x200..0x240, where the camera starts) that differs from the reference's in a file laid
  out like it, or, in a file laid out otherwise, from that of the known build whose layout it has, on every route,
  the donor route included: a camera flashed with a broken one may not start far enough to run the card updater
  again. Only a file laid out like neither the reference nor a known build, whose table is not the reference's, gets
  a WARN that says the table could not be compared.
- A damaged resource entry (`resource-slots`): every entry's slot must lie inside the resource area and the file,
  must not be erased, and must hold the kind its name says (a WAV with fmt and data chunks that fits its slot, a JPEG
  with an FF D9 end marker inside its slot, a GPZP stream that inflates, matches its CRC and length trailer and uses
  only fixed-Huffman blocks, a GPRS pack). The camera checks none of this, and it opens the boot logo at every boot
  before it reads the card, so a damaged logo can leave a camera the SD updater cannot reach. The rules are in
  [Image layout](11-FORMAT.md#resource-entries).

`diff` compares each resource entry over the larger of its two slots, so an entry erased or cut off in one file is
named (`ERASED in B`, `MISSING from B`) instead of being skipped.

`photo-id` says what a photo's header shows about the firmware that wrote it, from the photo alone: it reads each file
only up to the start of the image data (the SOS segment), never past its first 64 KiB, and reports the kind of
header, the size, the colour sampling, the JPEG quality on the IJG scale, the comment (COM) and the EXIF Make, Model
and Software, then a conclusion for each file. Both known builds write a still photo with a 1,424-byte header that
holds an EXIF block (Make `Generalplus`, Model `CBB3`, Software `20150615`) and the comment `GPEncoder` padded with
spaces (VERIFIED in the public dump; the film-mods build holds the same header template and the code that fills it,
VERIFIED by comparing the bytes). They keep a bare 624-byte header, with no EXIF block and the comment `GPEncoder`
alone, for thumbnails, video frames and a Wi-Fi stream, never for a still photo (VERIFIED in the public dump by
static analysis; the film-mods build holds the same templates). A photo the 2026-11-06 camera took on its original
firmware has that bare header: still photos with it come from neither known build, so a camera whose photos look like
this ran another build when it took them. Give `photo-id` the files as they came off the card's `DCIM` folder, since
a frame taken out of a video without re-encoding has the bare header too. The size and the quality follow the
camera's settings and whether a card was in (VERIFIED in the public build), so they say nothing on their own.

Any other header says less. `photo-id` calls a photo re-saved by another program only when its header carries a mark
an editor leaves: an XMP block, an APP2, APP13 or APP14 segment, a progressive frame, or a rotated size (taller than
wide). For any other header neither known build writes, such as one with a JFIF segment, an EXIF Make other than
`Generalplus`, the still header's comment without its EXIF block, or tables the camera's encoder does not write,
another program saved the photo again or another build wrote it, and nothing in the header tells which. The still
header does not show the same build either: two builds can share a header, and no header says anything about the
image sensor. For a video, `photo-id` reads the AVI's chunk headers and its first frame's header. The frame header
does not tell the known builds apart (INFERRED: both write their frames from the bare header, and no video of either
was examined), but the text the AVI packer puts in the `JUNK` chunk can show other code: the public build writes
`GP$322 Generalplus AviPackerV3 20140916` there (VERIFIED by static analysis), the film-mods build carries the same
text (VERIFIED by search), and any other text shows that other code wrote the file. That is how a would-be donor's
photos are compared with this camera's own pre-flash photos without opening anything
([Recovery, section D](02-RECOVERY.md#the-donor-route)).

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
like an update file (`JH_*.bin`, in any letter case, also with trailing dots or spaces, which Windows drops) and an
`--out` in a folder that holds a DCIM folder or a `JH_*.bin`. `inspect` says the same of a file named like an update
file whose size is not 0x1E0000 or 0x169000 bytes: it must not go on a card.

`resources replace` swaps a JPEG resource, such as the boot logo, only for a baseline JPEG with the same size,
component count and subsampling as the original that fits the slot; see [Resource pack and boot logo](09-RESOURCES.md). A custom boot logo has
not been tried on a camera.

## Commands that write to hardware

```
charmera stage out/restore/JH_5408<stamp>_<sum>.bin --card E: --stock my_backup.bin                         # Windows
charmera stage out/restore/JH_5408<stamp>_<sum>.bin --card /run/media/deck/<label> --stock my_backup.bin    # Steam Deck
charmera stage out/restore/JH_5408<stamp>_<sum>.bin --card E: --stock my_backup.bin --plan-only             # all checks, writes nothing
charmera restore --image my_backup.bin --out backups/                                                       # the chip, through a CH341A clip
```

`stage` runs `verify` again, refuses a card that already holds another update file, copies the file to the card
root, and reads it back to compare hashes. Without `--stock` it shows a WARN line saying that no backup of this camera
was given, so nothing compares the file with its own firmware and a file that does not fit cannot be undone, and
still writes a file that passes (the owner's decision of 2026-11-06). See [Putting the file on the card](04-STAGE.md).

`probe` and `backup` ([Recovery, section B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly))
only read the chip, and print a checklist before they run: a 3.3 V programmer (never an unmodified black CH341A), the
battery lead lifted, pin 1 of the clip on the chip's dot. `restore` prints the same checklist and reads the chip twice
before it writes, and writes nothing when the reads differ. It keeps that read in the folder given as `--out`, which
it requires, as `<label>_<UTC time>.bin` with a `.sha256` and a `.manifest.json` (`--label` defaults to
`before-restore`), shows the 64 KiB blocks in which the image differs from it, and writes nothing when the chip already
holds the image. It refuses an image with several sensor programs (more than one sensor table the code refers to,
with different contents). It shows the image's sensor table next to the chip's and asks for a second word,
`DIFFERENT`, when they differ, when the chip holds none of the kind the known builds use, or when the chip holds
several sensor programs, and has flashrom write a private copy of the bytes it checked, never your file in place
([Recovery, section B](02-RECOVERY.md#restore)).

`card-fix` and `stage` print what they are about to do and wait for you to type the target back; `restore` waits for the word RESTORE. See [SD card problems](05-CARDS.md) and
[Recovery](02-RECOVERY.md).

---

Reading order: 7 of 13 · previous: [Using an Android phone](06-ANDROID.md) · next: [Photo frames](08-FRAMES.md) · [all docs](README.md)
