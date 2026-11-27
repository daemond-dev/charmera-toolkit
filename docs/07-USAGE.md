# Command reference

Reading order: 7 of 15 · previous: [Using an Android phone](06-ANDROID.md) · next: [Photo frames](08-FRAMES.md) · [all docs](README.md)

All commands run as `python -m charmera_toolkit <command>`. After `pip install -e .`, `charmera <command>` works too.
You need Python 3.9 or newer, on Windows, Linux or macOS. There are no extra packages.

## On a computer: from the card to a write

This is what the Android app does, done at the command line. `identify` works from the camera's photos, so take two
or three with the camera on its card first. Then put the card in a reader.

1. `charmera identify <card>` tells you which stock firmware took the photos, and so the base (v012 for OV9732
   cameras, v006 for SP140A ones), and prints the `build` command to run next. It assumes a stock camera and says so
   when the photos show otherwise. With no usable photo it says so and exits 1.
2. `charmera build --list`, then `charmera build --base <base> --mods <mods> --download --out out/`, makes the
   firmware file from the base's public cleaned copy. It writes the file only when it's the known file.
3. `charmera verify out/JH_….bin --stock <your backup>` runs all the checks and compares the file with your backup.
   With no backup, `charmera verify out/JH_….bin` runs the same checks and warns that nothing confirms what the
   camera runs.
4. `charmera stage out/JH_….bin --card <card> --stock <your backup>` checks the file again, copies it to the card
   root and reads it back. With no backup, leave out `--stock`. It then warns the same way and still writes when
   you confirm. Then put the card in the camera, switch it on, wait for "Upgrade 100%", and take the file off the card
   afterwards.

The way back is the stock build of the base that fits the camera, written the same way. Build it with
`charmera build --base v012 --download --out out/`, or with `--base v006`. That works as long as the camera still boots, and a file for the other sensor
does. A write that is cut off, or firmware that won't boot, needs a chip programmer ([Recovery](02-RECOVERY.md)).

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
charmera identify E:\                                               # which stock firmware the camera runs (the base)
charmera sensor-read E:\                                            # what the sensor-ID probe's SENSOR.TXT says
```

`inspect` tells you what a file is:

- whether it's a chip backup or an update file, and which build it carries
- where its build marks and its image-sensor set-up table were found, with the compile date and the source-tree folder
- whether its software region is byte-identical to the public chip backup's
- what the resource pack and the sound slots contain
- which settings record is current, and whether UI sounds are on
- whether it holds another owner's photos

The build is named by a fingerprint over its bytes, never by the version stamp, which every known build shares. The
known builds are `public-dump` (v006), `film-mods` (v008_test) and `v012`, the factory build of OV9732 cameras. v012
sets up its sensor with a table of another form (167 writes for the OV9732), and `inspect` names it as v012's.

A file counts as a known build "with changes" (`public-dump-modified`, `film-mods-modified`, `v012-modified`) only
when its build marks lie where that build keeps them and it carries that build's stamp, sensor table, source-tree
folder and compile date, as a mod made from it does. Any other file laid out like a known build is a build the toolkit
doesn't know, "laid out like" it (`charmera-unknown-build`). `verify`'s `build` line and the app say the same ([Image
layout](11-FORMAT.md#build-identity)).

`verify` is the gate before a card. It checks the filename checksum, the stamp, the size rule, the magic, the build
marks, the sensor table, the ARM vector table and the software region, and says what each result proves. It refuses
the withdrawn candidates A and B by their SHA-256 and names C, whose bytes are the stock restore file made from the
cleaned v006 copy.

A file `charmera build` makes from a public copy is a known file, and `verify` knows it by its SHA-256. One built from
your own read isn't. For a known file you'll see an INFO `reviewed` line that names the build, the public file it was
made from and whether it has been tried on a camera. Its `series`, `build`, `software-region` and `sensor-table` lines are a WARN where another
file would FAIL, ending "(a known build: this is expected)", and it needs no `--allow-modified`. Without `--stock`
you get a WARN `unconfirmed`, except for the sensor-ID probe. With a `--stock` that is firmware for the other sensor you
get a WARN `wrong-sensor`. The size, checksum, stamp, vector-table, sensor-program and resource checks treat it like
any other file. The rest of this section is about files you made yourself.

Give `--stock <a chip backup of the camera the file goes into>` when you have one. The sensor table, the ARM vector table and
the software region are compared with it, and the stamp in the file's name must differ from the one in it
(`stamp-camera`). That comparison is the only check that says anything about your camera. `--stock` must be a complete
read of a chip, or `verify` fails. That means exactly 2,097,152 bytes, starting with `SPII`, with the build string
and the stamp. One
that's byte for byte a public chip backup is a WARN, since it may be this camera's own read after a write of the public
restore file.

`backup` and `restore` name a single read, and reads that differ, `<label>_<UTC time>.unverified-read<n>.bin`. A
`--stock` named like that, a read that no second read confirmed, is a WARN, `stock-read`, in `verify` and in `stage`,
and so is a `--donor` named that way (`donor-read`). Such a read isn't a backup, because a poor clip contact can leave
wrong bytes in it that nothing here can see. Read that chip again until two reads agree. The app refuses such a file
as its backup.

Only a chip backup read before the camera was ever written to shows what fits it. When `--stock` carries a known build's
fingerprint, a matching sensor table is only a WARN. When `--stock` is a build nobody has published, a matching
table is a PASS only when bytes 0..0x120000 (the code and its data) are `--stock`'s too. When only the table matches,
a WARN says the program around it differs, since a different program can drive the same sensor differently. A FAIL
line means don't use the file.

`--allow-modified` accepts a software region that differs from the reference. Any mod of the code or the resources
needs it, and the line is then a WARN, never a PASS. A known file from `build` doesn't need it. Settings-only files pass
without it. `stage` always uses it. With `--donor` it lets through a mod built on the donor's firmware that keeps the
donor's boot header, vector table (0..0x240) and sensor table, as a WARN.

`--allow-unknown-build` lets through a file whose build marks aren't all found, or, without `--stock`, a sensor table
that isn't the known builds'. The line it lets through is a WARN, and the software comparison with `--stock` still
runs. Without `--stock`, a file with no sensor table of the kind the known builds use then gets a WARN that says no
sensor comparison was possible. The flag never lets through a sensor table that differs from `--stock`'s, a file with
no sensor table of the known kind when `--stock` has one, or a `--stock` with none (below). With `--stock`, it lets
through a file without all the build marks only when the file still has a sensor table of the known kind.

`--allow-odd-size` lets through a size other than the two in use, 0x1E0000 and 0x169000 bytes, as a WARN, and the
`final-erase` line names the block the updater would then erase. A 2,097,152-byte file still fails, because its final erase
wraps to block 1. `stage` never gives `--allow-unknown-build` or `--allow-odd-size`.

What no flag accepts:

- Outside the donor route, a sensor table that differs from `--stock`'s, a file with no sensor table of the kind the
  known builds use when `--stock` has one, or a `--stock` with none (`sensor-table`). `--allow-unknown-build` clears
  none of them. No toolkit mod changes that table. Firmware that drives a sensor the camera doesn't have leaves it
  unable to take pictures, and a file without such a table leaves nothing to compare with the camera's own. The one
  way past them is the donor route, `--donor <a read of another, working camera's chip>`, for a camera whose own
  firmware is gone and doesn't fit it. `--stock` is optional there, and without it you get a WARN. A known file from `build`
  gets a WARN for these instead, as above. The route takes the donor's
  firmware unchanged, or with `--allow-modified`, which `stage` always gives, a mod built on it that keeps its boot
  header, vector table and sensor table, as a WARN. It says that the fit to this camera is unknown
  ([Recovery, section D](02-RECOVERY.md#the-donor-route)).
- On every route, more than one sensor program that the code refers to, with different contents, in the file or in
  `--stock` (`several sensor programs`), since nothing can tell which one sets up the sensor. Outside the donor route,
  with `--stock`, also a file whose sensor table the code doesn't refer to while the one in `--stock` is referred to.
  Without `--stock`, a table the code doesn't refer to is a WARN ([Image layout](11-FORMAT.md#build-identity)).
- On every route, the donor route included, an ARM vector table (bytes 0x200..0x240, where the camera starts) that
  differs from the reference's in a file laid out like it, or, in a file laid out otherwise, from that of the known
  build whose layout it has. A camera with a broken one written to it may not start far enough to run the card updater
  again. Only a file laid out like neither the reference nor a known build, whose table isn't the reference's, gets a
  WARN that says the table couldn't be compared.
- A damaged resource entry (`resource-slots`). Every entry's slot must lie inside the resource area and the file, must
  not be erased, and must hold the kind its name says: a WAV with fmt and data chunks that fits its slot, a JPEG with
  an FF D9 end marker inside its slot, a GPZP stream that inflates, matches its CRC and length trailer and uses only
  fixed-Huffman blocks, or a GPRS pack. The camera checks none of this, and it opens the boot logo at every boot before
  it reads the card, so a damaged logo can leave a camera the SD updater can't reach. The rules are in
  [Image layout](11-FORMAT.md#resource-entries).

`diff` compares each resource entry over the larger of its two slots, so an entry erased or cut off in one file is
named (`ERASED in B`, `MISSING from B`) instead of being skipped.

`photo-id` says what a photo's header shows about the firmware that wrote it, from the photo alone. It reads each file
only up to the start of the image data (the SOS segment), and never past its first 64 KiB. It reports the kind of
header, the size, the colour sampling, the JPEG quality on the IJG scale, the comment (COM) and the EXIF Make, Model
and Software, then a conclusion for each file. Three still-photo headers are known, and the conclusion names the one
it found:

- The v006 / v008_test family, the SP140A builds, writes a
  1,424-byte (0x590) header with an EXIF block (Make `Generalplus`, Model `CBB3`, Software `20150615`) and the comment
  `GPEncoder` padded with spaces. This is VERIFIED in v006. v008_test holds the same template and the code that fills
  it, VERIFIED by comparing the bytes. A photo can't tell v006 from v008_test. This project's OV9732 port is built on v006 and
  writes this header too.
- The factory build v012 of OV9732 cameras writes a bare 624-byte (0x270)
  header, with no EXIF block and the comment `GPEncoder` alone. This project's v012 builds without EXIF dates keep it. v012 holds this template four times and no 0x590 one
  (VERIFIED by search of the v012 dump). The v006 family holds the same bare template, byte for byte, but uses it only for thumbnails, video
  frames and a Wi-Fi stream. A photo the 2026-11-06 camera took on its original firmware has this header.
- This project's EXIF mod (any of this project's v012 builds with EXIF dates) writes an 832-byte (0x340) header: the start marker, one
  EXIF block of 0xD0 bytes with Make `Kodak`, Model `Charmera` and Software `v0.26`, then v012's bare header
  (VERIFIED in the update file of v012 with Volume and LED rows and EXIF dates). Software `v0.26` is an old build
  number that's part of the mod's code, so every build with EXIF dates writes it.

The toolkit's own `exif-fix` also writes Make `Kodak` and Model `Charmera`, on copies, with no Software tag and the
rest of the header kept. `photo-id` calls such a copy an exif-fix copy, not the EXIF mod, and says which family the
original photo shows. Give `photo-id` the files as they came off the card's `DCIM` folder, since a frame taken out of
a video without re-encoding has the bare header too. The size and the quality follow the camera's settings and whether
a card was in (VERIFIED in v006), so on their own they say nothing. A photo shows the firmware that took it, not the
one that fits the camera. A photo taken after a write shows the firmware that was written.

Any other header says less. `photo-id` says a photo was re-saved by another program only when its header carries a
mark an editor leaves: an XMP block, an APP2, APP13 or APP14 segment, a progressive frame, or a rotated size (taller
than wide). Some headers may have been saved again by another program or written by another build, and nothing in
them tells which. Those are a header with a JFIF or other APP0 segment (the v006 family writes none, not even MJPEG's
`AVI1`), an EXIF Make other than these, the still header's comment without its EXIF block, or tables the camera's
encoder doesn't write. A header that differs from all the templates in other ways gets "nothing here can tell what
wrote it". One without its quantisation or Huffman tables is cut short or comes from an encoder that leaves them out,
and there's nothing to conclude. Two builds can share a header, and no header says anything about the image sensor
itself.

For a video, `photo-id` reads the AVI's chunk headers and its first frame's header. v006 copies the whole bare
template into each frame and fills in its size and its tables, with one IJG quality for both, which the rate control
moves from frame to frame (VERIFIED by static analysis). A frame header that is the template as filled in doesn't tell
the builds apart (INFERRED: no video of any build was examined, and v012's video writer wasn't analysed). Any other
frame header isn't the one they write. The text the AVI packer puts in the `JUNK` chunk can show other code. v006
writes `GP$322 Generalplus AviPackerV3 20140916` there (VERIFIED by static analysis), v008_test and v012 carry the
same text (VERIFIED by search), and any other text shows that other code wrote the file. That's how a would-be donor's
photos are compared with this camera's own photos from before the write without opening anything
([Recovery, section D](02-RECOVERY.md#the-donor-route)).

`identify` does the same for a whole card and answers one question: which stock firmware took this camera's photos,
and so which base do you build on? It assumes a stock camera that was never written to or modded. A camera that was
written to or modded is the exception. `identify` says so in a line of its own and still gives a base. Give it the card's root
folder (it then searches only `DCIM`), a `DCIM` folder, any other folder, or photo and video files. It reads only
headers. It needs photos the camera took on that card, at least two for `high` confidence, so take two or three
first.

Photos with no file time (0 or missing) are left out of the dating and of the change detection, and listed ("N photos
have no file time and were left out of the dating"). `identify` picks the oldest and the newest dated photo, by
modification time, among those that are still as the camera wrote them. It skips, by name, photos another program
saved again, exif-fix copies and files it can't read. It reads the kind of every dated photo, up to `--cap` (500 by
default). When there are more, it reads that many, evenly spaced by date, plus the oldest and newest camera-written
photos it chose, and says so. It shows the newest video's verdict without using it.

The v006 family is the 0x590-byte Generalplus header (stock v006 or v008_test, on SP140A cameras), and the v012 family
the bare 0x270-byte one (stock v012, on OV9732 cameras). The 0x340-byte EXIF-mod header is ours (every build with
EXIF dates, built on v012), so a camera that writes it isn't stock, and it's of the v012 family. The possible answers:

- All dated camera-written photos of one family: "The photos were taken by stock v012 (an OV9732 camera): base v012"
  or "The photos were taken by stock v006 or v008_test (an SP140A camera): base v006", followed by "If firmware was
  written to the camera after these photos, it runs something else now". Confidence is `high` when the scan wasn't capped and at
  least two camera-written photos agree, and `medium` for a single photo or a capped scan. v006-family photos aren't
  doubted just because our OV9732 port on v006 writes the same header. One line says "If our OV9732 port on v006
  was ever written to this camera, its photos look like this too."
- Any EXIF-mod photo: "This camera does not look stock: our EXIF mod (Software 'v0.26') is installed", with "installed
  between <date> and <date>" when bare photos come before them. The base is `v012` (the mod is built on it), with
  confidence `high`. Bare and EXIF-mod photos are one family, not a change of family.
- Photos of both families: "This camera does not look stock: photos from two firmware families: <older> up to
  <date>, <newer> from <date>: firmware was written to the camera in between, or the card holds photos from two cameras". The
  base is the oldest family's, never the newest one's, since photos from before a write show the stock firmware.
  Confidence is `low`.

It always adds two caveats: a photo can't tell v006 from v008_test (both are stock builds for SP140A cameras, and
either base works the same for our purposes), and photos show the firmware that took them. The dates are the files'
modification times, set by the camera's clock. With a conclusion it ends with a `next:` line, `charmera build --list,
then charmera build --base <base> --mods <mods> --download --out out/`. `--json` prints the same as JSON, with
`stock`, `not_stock`, `base`, `family` (the base's family), `confidence`, `next`, `notes` and `undated`. The exit code
is 0 when it reached a conclusion and 1 when no usable photo was found. Then it tells you to take two or three photos
with the camera on that card and run it again.

`sensor-read` decodes the `SENSOR.TXT` that the sensor-ID probe writes to the card's top folder when Photo mode opens,
with the same decoder as the app. Give it the card's root folder or the file. It reads all four layouts the probe has
written: the current 16-bit ID read (`A7 01`), the 8-bit fingerprint (`A6 01`), the bus scan (`A5 01`) and an older
probe's two bytes. The 16-bit ID read names the part from its chip-ID registers (an OV9732 reads `97 32` at
0x300A/0x300B). `sensor-read` says whether a sensor answered at the SP140A's address 0x3C (bus 0x78), the one the
probe's firmware drives, somewhere else, or nowhere. The file says what the probe read when it ran, but not when that
was. The exit code is 0 when a file was decoded, 1 when there's no `SENSOR.TXT`, and 2 when it can't be read.

To make the probe, run `charmera build --base v006 --mods sensor-probe --download --out out/`, then verify and stage
it as any update file. It's v006 with a patch, so it changes no boot or update code. On an SP140A camera the stock
v006 build (the stock restore file) undoes it. On an OV9732 camera undo it with a v012 build, stock or with mods, not
the stock restore file. You don't need the probe when `identify` already names the base from photos taken before any
write.

## Commands that write files on your computer

### Build a firmware file from a base and mods (charmera build)

These are the same recipes the Android app builds from, on Windows, Linux or macOS. A base is the stock firmware of
one build: `v012` for OV9732 cameras, `v006` (or `v008_test`) for SP140A cameras. `charmera identify` says which one a
camera's photos point to. Mods are the changes we wrote for that base.

```
charmera build --list                                                # every base, mod and combination
charmera build --base v012 --mods volume-led,exif --download --out out/
charmera build --base v012 --download --out out/                     # no --mods: stock v012
charmera build --dump charmera_flash_....bin --mods volume-led,exif,boot-last-mode,capture-flash --out out/
```

- `--download` fetches the base's public cleaned copy from the-dumping-grounds' repositories at a fixed commit, and
  uses it only if its SHA-256 matches. `--dump` takes a file you have, either a public copy or your own read of a v006
  or v012 chip.
- From a public copy, the file is written only when it's byte for byte the known file listed for that combination
  in `charmera_toolkit/recipes.json`, and every mod checks the bytes it replaces first. Combinations not yet tried on
  a camera are built with a warning (`[experimental]` in `--list`).
- From your own read, the firmware in it (bytes 0..0x169000) must be stock v006 or v012 byte for byte, or `build`
  refuses it. A camera with a mod written to it reads like that, so build from the public copy instead, or from a
  read taken before the camera was written to. The file keeps your read's settings and the photos stored in the camera's
  own memory. No published hash matches it, so `build` prints
  `NOT a known file` and a WARN, and `verify`, `stage` and the app check it as any file you made yourself.
- Then it runs `verify` on the result and prints the commands to run next. For a known file those are `verify` and
  `stage` with `--stock <your backup>`, and the same two without `--stock` for when you have no backup. For a file
  from your own read they give that read as `--stock` (and `--allow-modified` when it has mods). `verify` knows
  known files by SHA-256. It says which build a file is and doesn't refuse it for being modded.
- Every set is named by its base and mods, the same way in `--list` and in the app, such as "v012 with Volume and LED
  rows and EXIF dates" for `--base v012 --mods volume-led,exif`. The five sets older versions of the app numbered
  v0.23 to v0.27 say so in `--list`, for example "(older versions of the app called this v0.26)". The full list is in
  [Older build names](06-ANDROID.md#older-build-names).

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

`pack` takes a 2,097,152-byte chip backup, trimmed to 0x1E0000, a 0x1E0000 update file, a 0x169000-byte official-size update file,
or a software-only image of exactly 0x170000 bytes. The last two are padded with an erased settings bank and a zeroed
photo directory. The plan shows the padding and names any damaged resource entry that `verify` will refuse. `pack`
refuses any other length below 0x1E0000 as truncated, because padding a cut-off download would put 0xFF over part of
the code or the resource pack.

From a chip backup it refuses only one whose last block, 0x1F0000..0x200000, isn't blank, since the updater's final
erase clears that block. Data in 0x1E0000..0x1EFFFF is left out of the file but stays on the chip, because a
0x1E0000-byte update never writes or erases that block, so `pack` only notes it.

`--official-size` cuts the result at 0x169000, where the film-mods files end. Official updates would end there too, by
that project author's reading of the updater, but no official Charmera update has ever been published. The file then
writes only the code and the resources, nothing of another camera's settings or photos. The camera's final erase lands
on the settings block, so it starts with factory settings and an empty photo list, while its stored photo data and the
blocks above 0x1E0000 are left alone ([Image layout](11-FORMAT.md#update-file-jh_5408stamp_sumbin)).

`--out` must be a folder on the computer. `pack` refuses a drive root or mount point, a folder on removable media, a
folder that has a DCIM folder, and one that already holds another `JH_*.bin`. A folder with a DCIM folder is a card,
and `stage` is what puts the file there. `--factory-settings` keeps only the first CRC-valid settings record and erases the rest of the
bank. It zeroes the photo directory only when that sector is erased, so stored photos stay unless you add
`--blank-photos`. `--stamp` takes 12 ASCII digits 0-9. `pack` refuses an input without the Charmera build marks
(v006, v008_test and v012 all have them) unless `--allow-unknown-build` is given, and notes a build it doesn't know.

`settings set` keeps everything else in the record and leaves that record as the only one in the bank. `tweak apply`
refuses any patch whose original bytes aren't where it expects them, so it can't land on the wrong build or on top of
an earlier edit. Every tweak is untested on hardware and labelled with its risk. `recommended` is boot-delay,
lcd-poll and fast-poweroff. It shortens only fixed waits where nothing is loading, and keeps the power-on logo, which
is on screen while the camera mounts the card. `frames replace` takes an 800x600 RGBA PNG (transparent means
see-through), re-encodes it the way the camera stores frames, and refuses with a per-strip table when the result
doesn't fit the slot's byte budget. See [Photo frames](08-FRAMES.md).

The `--i-understand-…` gate flags exist so that nothing ready to write to a camera appears by accident. A modified image goes through
`pack` and then `verify` before it's allowed near a card. The mod commands (`sounds`, `settings set`, `tweak`,
`frames`, `resources`) write a full image of their input's size, which must never go on a card as it is. So they
refuse an `--out` whose name looks like an update file (`JH_*.bin`, in any letter case, also with trailing dots or
spaces, which Windows drops), and an `--out` in a folder that holds a DCIM folder or a `JH_*.bin`. `inspect` warns
the same way about a file named like an update file whose size isn't 0x1E0000 or 0x169000 bytes. It must not go on a
card.

`resources replace` swaps a JPEG resource, such as the boot logo, only for a baseline JPEG with the same size,
component count and subsampling as the original that fits the slot. See [Resource pack and boot logo](09-RESOURCES.md).
Nobody has tried a custom boot logo on a camera yet.

## Commands that write to hardware

```
charmera stage out/restore/JH_5408<stamp>_<sum>.bin --card E: --stock my_backup.bin                         # Windows
charmera stage out/restore/JH_5408<stamp>_<sum>.bin --card /run/media/deck/<label> --stock my_backup.bin    # Steam Deck
charmera stage out/restore/JH_5408<stamp>_<sum>.bin --card E: --stock my_backup.bin --plan-only             # all checks, writes nothing
charmera stage out/JH_5408<stamp>_<sum>.bin --card E:                                                       # no backup: warns, still writes
charmera restore --image my_backup.bin --out backups/                                                       # the chip, through a CH341A clip
```

`stage` runs `verify` again, refuses a card that already holds another update file, copies the file to the card
root, and reads it back to compare hashes. `stage` warns without `--stock` but still writes a file that passes
([details](04-STAGE.md#what-it-does-in-order)).

`probe` and `backup` ([Recovery, section
B](02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)) only read the chip. Before they
run they print a checklist: a 3.3 V programmer (never an unmodified black CH341A), the battery lead lifted, pin 1 of
the clip on the chip's dot, and a multimeter check of the clip with the programmer plugged in alone. The clip's pin 8 (VCC) and
pin 1 (CS) against its pin 4 (GND) both read about 3.3 V. If either reads near 5 V, stop. A board that looks right can
still put 5 V on CS.

`restore` prints the same checklist and reads the chip twice before it writes, and writes nothing when the reads
differ. It keeps that read in the folder given as `--out`, which it requires, as `<label>_<UTC time>.bin` with a
`.sha256` and a `.manifest.json` (`--label` defaults to `before-restore`). It shows the 64 KB blocks in which the
image differs from that read, and writes nothing when the chip already holds the image. It refuses an image without
the Charmera build marks, one that isn't v006, v008_test or v012 or is damaged, and one whose ARM vector table
(0x200..0x240) is erased or isn't the one those builds carry, unless `--allow-unknown-build` is given, which is only
for a good read of this camera's own chip. It asks for `DIFFERENT` when the image's resource pack is damaged or
missing, and names the damaged entries. The cleaned v006 and v012 copies and a read of a v006
or v012 chip pass without it. It refuses an image with several sensor programs (more than one sensor table the code
refers to, with different contents) whatever the flags. It shows the image's sensor table next to the chip's and asks
for a second word, `DIFFERENT`, when they differ (naming the sensor each is for when they're for different sensors),
when the chip holds none of the kind the known builds use, or when the chip holds several sensor programs. It has
flashrom write a private copy of the bytes it checked, never your file in place ([Recovery, section
B](02-RECOVERY.md#restore)).

`card-fix` and `stage` print what they're about to do and wait for you to type the target back. `restore` waits for
the word RESTORE. See [SD card problems](05-CARDS.md) and [Recovery](02-RECOVERY.md).

---

Reading order: 7 of 15 · previous: [Using an Android phone](06-ANDROID.md) · next: [Photo frames](08-FRAMES.md) · [all docs](README.md)
