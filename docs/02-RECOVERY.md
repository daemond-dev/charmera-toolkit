# Recovering a Charmera

Reading order: 2 of 15 · previous: [The first write](01-FIRST-FLASH.md) · next: [Candidate files](03-FLASH-CANDIDATES.md) · [all docs](README.md)

How to get a Kodak Charmera back to working firmware. This covers the original 2025 series, with a Generalplus
GPCV2247F processor and a 2 MB Puya PY25D16HB SPI flash chip. Read the whole section that applies to you before you touch anything.

Factual claims carry one of these labels:

- VERIFIED: checked against documents or the firmware binary itself.
- REPORTED: stated by community members who did it, not checked independently.
- INFERRED: worked out from what is known, not checked directly.
- UNKNOWN: nobody has published it.

Nothing here distributes Kodak or Reto firmware. The best restore source is your own backup, made with `python -m
charmera_toolkit backup` while the camera still worked. A backup read before the camera was ever written to shows which
firmware fits it, and so do photos the camera took before its first write (`charmera identify`), so know what your
camera runs before you write. Section A rebuilds the camera's firmware from a read of this camera's chip, and a public
chip backup or another camera's read doesn't count as one. Without a backup there's still a way back for a camera with either
known sensor. A camera that boots and freezes in photo mode is fixed from the card
([section D](#the-camera-boots-but-photo-mode-freezes)), and a camera that doesn't boot gets the public file for its
sensor through a chip programmer ([section B, Restore](#restore)).

## Which situation are you in?

1. After a write, does the camera boot, show its menus and play back photos, but freeze when photo or video mode
   starts? That's firmware that doesn't fit the camera. Go to [section D](#the-camera-boots-but-photo-mode-freezes)
   first, whatever the body. The known cases are a Millennium Edition (blue boot screen) or a CLOT edition with
   an original-series image written to it, and an original-series camera on 2026-11-06.
2. Does the camera power on and get far enough to mount the SD card (it shows the live view, plays back photos, or
   reacts to a card)? If yes, go to [section A](#a-the-camera-boots-and-the-updater-runs-restore-through-the-sd-card).
   If not, go to [section B](#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly).
3. Was an update interrupted before it reached 100% (battery died, card pulled, camera switched off while
   "Upgrade NN%" was still counting), or a clip write cut short (cable or clip pulled)? Read
   [section C](#c-an-interrupted-write), which sends you to A or B. An update that reached 100% and was then switched
   off with the power key finished normally. That's how every update ends.

## A. The camera boots and the updater runs: restore through the SD card

The SD updater is the gentle route. It can't help a camera that doesn't boot far enough to mount the card (VERIFIED
from the SDK source: the updater is part of the firmware that's already running). [The first write](01-FIRST-FLASH.md)
has the same steps for a first write of a modified file.

What the updater does (VERIFIED from the SDK source, the firmware itself and hardware reports;
[Research report, section 4](13-PHASE1-REPORT.md#4-the-sd-updater-flow) has the long form):

- It only looks for the file during the first 5 seconds after power-on, and only in the card root, so the card has
  to be in before you switch the camera on. The file must be named `JH_5408<12-digit timestamp>_<8 hex digits>.bin`.
- The 8 hex digits are the unsigned 32-bit sum of every byte of the file, and that sum is the only integrity check.
  A wrong sum shows "Program error" on the screen and nothing is erased.
- The 12-digit timestamp must differ from the stamp compiled into the running firmware (`202505201029` in every known
  build), or the file is ignored (VERIFIED, see `charmera_toolkit/checksum.py`). With `--stock`, `verify` also
  checks the name against the stamp found in your backup.
- With a correct sum it erases the flash in 64 KB blocks from offset 0, programs 256-byte pages and verifies them.
  "Upgrade NN%" advances once per 64 KB block. At 100% the camera stays on its last screen. Nothing reboots by
  itself, and the updater never deletes or renames the file. Holding the power key for about a second switches the
  camera off.
- Two sizes of update file are in use. `verify` only takes another size as a WARN with `--allow-odd-size`, and
  `stage` never takes one. The community files are `0x1E0000` bytes (30 blocks: the code, the resources and the
  camera's own area of settings and stored photos), and their final erase lands on the blank block at `0x1F0000`.
  The film-mods files are `0x169000` bytes (`pack --official-size`: the code and the resources only), and their
  final erase lands on the settings block at `0x170000`, which is a factory reset
  ([Image layout](11-FORMAT.md#update-file-jh_5408stamp_sumbin)). Never put a full 2 MB chip backup on the card. Its final
  erase wraps to block 1 and bricks the camera (REPORTED by the film-mods author, on hardware), and `verify` refuses
  it. `pack` trims your chip backup to `0x1E0000` bytes. The last block, which the final erase clears, is blank in every
  known chip backup, and `pack` refuses a backup where it isn't. Neither size writes or erases the block at `0x1E0000`, so
  whatever the chip holds there stays.

Steps:

1. Build the update file from your own backup. `<your backup>` is a read of this camera's own chip. It's how you
   know what your camera runs before you write, and it's this step's input. No backup? Make the stock build of the
   camera's base instead (`charmera build --base v012 --download --out out/` for an OV9732 camera, `charmera build
   --base v006 --download --out out/` for an SP140A one, as
   [section D](#the-camera-boots-but-photo-mode-freezes) shows) and carry on from step 2 without `--stock`. `pack`
   prints a plan first and writes nothing:

   ```
   python -m charmera_toolkit pack --input <your backup>.bin --out out/restore/
   ```

   Read the plan. When it's what you expect, run the same command again with the gate flag `pack` names in its output
   (`--i-understand-this-file-flashes-a-camera`). It writes `out/restore/JH_5408<stamp>_<sum>.bin`, a `.sha256` and
   a `.MANIFEST.txt`.

2. Check the file. `verify` is read-only and fails closed:

   ```
   python -m charmera_toolkit verify out/restore/JH_5408<stamp>_<sum>.bin --stock <your backup>.bin
   ```

   Any `FAIL` line means don't use the file. Fix the cause and repeat step 1. A file packed from your backup and
   checked against it matches it by construction. That shows the file is your backup's firmware. It only tells you
   what fits the camera if the backup was read before the camera was ever written to (section D).

3. Copy only that one `.bin` file to the root of the card the camera normally uses, and leave the `.sha256` and the
   manifest on the computer. `stage` does this copy with its checks ([Putting the file on the card](04-STAGE.md)).
   Give it the same `--stock`:

   ```
   python -m charmera_toolkit stage out/restore/JH_5408<stamp>_<sum>.bin --card E: --stock <your backup>.bin
   ```

   From a phone, see [Using an Android phone](06-ANDROID.md). Eject the card (Safely Remove, `udisksctl unmount`, or
   the eject in your file manager or in Android) before you take it out of the reader or the phone. If the camera
   itself is the reader, connected by its USB-C cable, connect it the same way every time. Plugging the cable into
   a switched-off camera switches it on and runs the updater from its card. So switch the camera off, take the card
   out, switch the camera on with its power key, wait 10 seconds, put the card back in, then plug in the cable.

4. Check the body once more. This is for the original 2025 series only (matte, with the yellow Kodak boot screen),
   never a Millennium or CLOT edition ([section D](#d-firmware-that-does-not-fit-the-camera)). Charge the battery
   fully and unplug the cable, because an update that loses power part way is [section C](#c-an-interrupted-write).
   With the camera off, put the card in, then power on. The updater finds the file and shows "Upgrade NN%" counting
   up, about 15 to 20 seconds in all.

5. Don't power off and don't pull the card while it counts. At 100% the camera stays on its last screen and doesn't
   reboot by itself. Take the card out, then hold the power key about a second until the camera switches off.

6. Power the camera on without the card. Check that it boots normally.

7. Delete the update file from the card before you use the card again. The camera never deletes it, and the updater
   checks the card in the first 5 seconds after every power-on (VERIFIED), so with the file left on the card the camera writes it
   again at the next power-on. If you do this through the camera's own cable, connect it as in step 3 (card out,
   power on with the power key, wait 10 seconds, card in, then the cable). Never plug the cable into the switched-off
   camera with the file on its card.

If the screen shows "Program error", the byte sum in the filename doesn't match the file. Nothing was erased
(VERIFIED), and the camera switches itself off. Take the card out, delete the file, rebuild it with `pack` (or
`charmera build` if you made it that way) and check it with `verify` before you try again. The usual cause is a file renamed by hand or truncated by a bad copy.

If the camera loses power before 100% in step 5, the flash holds partly erased or partly written firmware and the
camera most likely won't boot. That's [section C](#c-an-interrupted-write).

## B. The camera does not boot: read and write the flash chip directly

This route opens the camera and connects a programmer to the flash chip, usually with soldered wires. It's the only known way to recover a camera
that no longer boots. There's no documented boot-ROM recovery for this SoC, and what the "ISP" test pad does is
UNKNOWN (see [section C](#c-an-interrupted-write)). It's routine if you're careful, but the wrong voltage can
destroy the chip or the SoC. Read all of this section first.

### Tools

- A 3.3 V SPI programmer that flashrom supports. Safe choices are a blue CH341A board jumpered to 3.3 V, a "V1.7"
  CH341A board with its 3.3 V / 5 V switch set to 3.3 V, a Raspberry Pi Pico running pico-serprog
  ([below](#a-raspberry-pi-pico-as-the-programmer)), or a CH347. The common black CH341A board is not safe as sold.
  Its data lines run at 5 V unless it's modified, and flashrom's own documentation warns about it. The modification
  is to lift pin 28 of the CH341A chip, or tie it to 3.3 V. Don't use a 1.8 V adapter board either. This chip is a 3.3 V part. Where the 3.3 V
  jumper sits on a blue board isn't documented here, and boards differ. Set it as the board's own markings show, then
  measure it ([Voltage and back-powering](#voltage-and-back-powering)). Measure whatever the board looks like. This
  project's owner measured 5 V on CS from a CH341A board.
- An SOIC-8 / SOP8 test clip (150 mil) with its ribbon cable, or thin wires soldered to the chip's legs. The chip is
  a leaded SOP8 package. An in-circuit read and write of this camera's chip is documented. The author of
  [charmera-film-mods](https://github.com/Hyperlite-Media/charmera-film-mods) read and rewrote it with an Adafruit
  Trinket M0 (its `docs/NOTES.md` says a camera that would not boot was "Recovered over the Trinket"), and posted a
  photo of the setup on Reddit (r/toycameras, "Charmera firmware dump"). It shows wires attached at the chip's legs,
  no clip, and the battery's leads off the B+ and B- pads. Asked in that thread whether a clip would fit, the author
  answered "Not much room around the flash chip, enough to solder tho", and described taking the board out and
  soldering the jumper wires back on to rewrite it. So expect to solder thin wires to the chip's legs. A clip is
  worth a dry fit (nothing connected), but it may not seat.
- With soldered wires, jumper wires (female-to-male) to join them to the programmer: each chip pin to the same pin of
  the programmer's socket or header ([the pinout](#identifying-the-flash-chip)).
- flashrom, a current release. On Linux, use your distribution's package. On Windows, flashrom.org lists Windows
  builds and the libusb requirement, and the CH341A needs the WinUSB (or libusb-win32) driver installed with Zadig on
  USB device `1a86:5512`. The toolkit prints the same hints when it can't find flashrom.
- On Linux, access to the programmer without sudo. Copy `scripts/70-ch341a.rules` to `/etc/udev/rules.d/` and keep
  the `70-` prefix. It has to sort before systemd's `70-uaccess.rules` and `73-seat-late.rules` for the uaccess tag
  to take effect at plug time. Then run `sudo udevadm control --reload-rules && sudo udevadm trigger` and replug the
  programmer. Or run the toolkit commands with sudo.
- A multimeter, for the voltage check before the clip or wires touch the chip.
- For the soldering this usually takes: a fine-tip soldering iron, solder, flux, thin wire and Kapton tape (for the
  wires on the chip's legs and the battery leads).
- A flat screwdriver or a plastic spudger, a small cross-head screwdriver, a magnifier or a phone camera for reading
  the chip and the pin 1 dot, and a tray for the tiny parts.
- This toolkit and Python 3.9 or newer.

This toolkit drives the programmer with flashrom. If you'd rather use an Adafruit Trinket M0 as the programmer, the
third-party [charmera-film-mods](https://github.com/Hyperlite-Media/charmera-film-mods) project (MIT-licensed)
publishes its own SPI flasher for the same Puya chip. It's a Trinket sketch (`trinket_spi_flash.ino`) and a Python
driver (`flasher/charmera_flash.py`) that read and write the chip without flashrom. It saves the chip's previous contents
first and verifies after. It isn't part of this toolkit and isn't tested here. As published it's POSIX-only (it uses
`termios` and `/dev/cu.usbmodem*`), so on Windows you'd run it under WSL or port it to `pyserial`. The 3.3 V supply
and clip-orientation cautions below apply to any programmer.

### A Raspberry Pi Pico as the programmer

A Pico running the pico-serprog firmware is a 3.3 V programmer. This repo doesn't ship that firmware or link to it.
Search for "pico-serprog" and put its `.uf2` file (`pico_serprog.uf2`) on the Pico the usual way. The firmware decides
which Pico pins carry CS, SO (MISO), SI (MOSI) and SCLK, and this repo doesn't list them, so take the wiring from that
firmware's own instructions and check it twice before anything touches the chip. Power the chip's pin 8 (VCC) from the
Pico's 3.3 V output, pin 36, never pin 40, and join the chip's pin 4 to a Pico GND pin. Whether the camera's board
holds WP# (pin 3) and HOLD# (pin 7) high isn't known. Wire them to 3.3 V as well, or check them if reads come back all
FF. Do the voltage check in
[Voltage and back-powering](#voltage-and-back-powering) on the wire ends before they go on the chip. The camera stays
switched off with its microSD card out.

There are two ways to drive it:

- flashrom, through the toolkit's `probe`, `backup` and `restore`, with `--programmer serprog:dev=COM3` on Windows or
  `--programmer serprog:dev=/dev/ttyACM0` on Linux (use the Pico's own port). This is the only way here to write the
  chip with a Pico.
- `python -m charmera_toolkit.serprog`, a read-only chip reader that talks to the Pico without flashrom. It needs pyserial
  (`pip install pyserial`).

```
python -m charmera_toolkit.serprog --out backups/ --port COM5
```

The reader refuses, in code, every flash command that writes, erases or changes a register. It reads the chip's ID
first and stops when the ID looks like a floating or shorted data line (all `FF` or all `00`), or isn't a 2 MB Puya
part (`--any-chip` reads anyway). Then it reads the whole chip `--reads` times (3 by default, at least 2). It keeps
`<label>_<UTC time>.bin` with a `.sha256` only when two reads are byte for byte the same (the label defaults to
`charmera_flash`), and reads that match nothing keep their `.unverified-read<n>.bin` name. If the reads differ, reseat
the wires and lower `--khz` (1000 by default). Leave out `--port` when only one port looks like a Pico. It writes no
`.manifest.json`, so run `python -m charmera_toolkit inspect` on the result to see its build marks.

### Opening the camera

From byjp's teardown, [Disassembling the Kodak
Charmera](https://byjp.pckt.blog/disassembling-the-kodak-charmera-psk3fky) (2026-08-20, re-read 2026-11-12; REPORTED,
with board photos but no photos of the opening itself):

- Pry the two halves of the shell apart with a flat screwdriver, working around the seam. They're held by tabs and
  take some force. On the viewfinder side a short ribbon cable joins the main board (front half) to the screen (back
  half), so open that side no more than you need to, and the other side about 1 cm at most.
- The ribbon cable on the viewfinder side comes free when you lift the latch on its connector. Don't pull the cable
  with the latch closed.
- Two small cross-head screws hold the board.
- The viewfinder lens is loose once the shell is open and falls out. Put it somewhere safe.
- The speaker wires are fragile. Don't let the speaker hang from them.
- The battery is soldered to the B+ and B- pads. Hyperlite's photo shows it lifted off them for the read, and
  `charmera_flash.py` says a blank read means "check ... that the camera battery is unplugged". Desolder its leads
  for the read, keep them apart and insulated, and solder them back after. Don't short a bare B+ pad.
- Take the microSD card out before you open it. A second write-up, now removed from the web, described a camera
  damaged by lifting the board with the card still in.

What to photograph while it's open, to identify the parts (from byjp's board photos, 2026-08-20; VERIFIED there,
compare yours):

- The camera module's flex, where it runs from the lens holder to its 10-contact connector at the top edge of the
  back of the board (under a cream tape or glue patch). byjp's unit reads `KE28-V1`. A different code points to a
  different module, and likely a different sensor. The lens is glued to the sensor, so don't try to read the die.
- The board silkscreen near the battery pads (`DC28 5408A` on byjp's unit) and the processor's marking with its date
  code (`GPCV2247F -QVM31 MPF6M23.00 2551` there). A different board code or revision letter points to a hardware
  revision.
- The flash chip, for the clip. In Hyperlite's photo the wires go to a chip on the side with the B+/B- battery pads
  and the `DC28 5408A` silkscreen, between the big processor and the three buttons. byjp's text puts the flash on the
  "front" with the microSD reader and USB socket. The two may name the sides differently. Find the 8-pin `PY25D16HB`
  on yours before you connect anything.

### Identifying the flash chip

Look for an 8-pin SOP8 (SOIC-8, 150 mil) package marked `PY25D16HB` (Puya). It's a 16 Mbit part, 2 MB. The part
number is REPORTED by the teardown, and the 2 MB size is VERIFIED by every chip backup. A dot or a chamfered corner marks
pin 1. If the marking is different, stop and note it, because the rest of this guide assumes this part.

The pinout is the standard one for 25-series SPI flash in this package: 1 CS#, 2 SO (MISO), 3 WP#, 4 GND, 5 SI
(MOSI), 6 SCLK, 7 HOLD#, 8 VCC. The PY25D16HB datasheet itself isn't public. Puya's sibling datasheets follow the
standard, so treat this as REPORTED until you've checked it against the markings on your clip and programmer. A
CH341A's ZIF socket and its clip cable are wired for this pinout, so you only have to get pin 1 right.

### Voltage and back-powering

Puya's part-number coding says the "H" in PY25D16HB means a 2.3 to 3.6 V supply (VERIFIED from Puya's datasheets of
sibling parts). Program it at 3.3 V.

Measure before the clip or the wires touch the chip. Plug the programmer into the computer on its own, with the clip
on nothing. With a multimeter, measure the clip's pin 8 (VCC) and pin 1 (CS) against its pin 4 (GND). Both must
read about 3.3 V. If either reads near 5 V, stop and unplug. That board would damage the chip and the processor that
shares its bus. `probe`, `backup` and `restore` print the same check in their checklist.

The SoC's I/O is 3.3 V and it shares the rail with the flash. A clip read feeds the whole board through the flash
VCC pin, so the programmer's regulator ends up carrying the board. Expect that. If reads are unstable, the chip isn't
detected, or the detected size is wrong, the usual cause is back-powering with the battery still connected.

Disconnect the battery before clipping. It's soldered to the B+ and B- pads and taped to the board. Desolder its
leads, tape them so they can't touch anything, and leave them off until the clip is removed.

### Clip orientation

- The clip's cable usually marks pin 1 with a red stripe or a "1". Put that side on the chip's dot.
- On the programmer, the SOP8 adapter or ZIF socket has pin 1 marked on the silkscreen. Match it.
- A reversed clip usually just fails to detect the chip, but it puts supply voltage on the wrong pins. That's one
  more reason to use a 3.3 V programmer (general CH341A experience, not camera-specific).
- Seat the clip squarely. A half-seated clip gives reads that differ from each other.

### Read before you write

1. Check that the chain works. `probe` writes nothing:

   ```
   python -m charmera_toolkit probe
   ```

   Before it runs flashrom it prints a checklist, as `backup` does: a 3.3 V programmer (never an unmodified black
   CH341A), the battery lead lifted, pin 1 of the clip on the chip's dot, and the multimeter check above. It prints
   flashrom's version, then what flashrom saw. Expect a 2048 kB chip. The Puya part isn't in flashrom's chip table by
   name. The community read it as `-c "SFDP-capable chip"` (REPORTED), which is the toolkit's default. `--chip auto`
   lets flashrom probe on its own, and `--programmer` selects another programmer (for a Pico, `serprog:dev=/dev/ttyACM0`
   or `serprog:dev=COM3`). The scripts `scripts/backup.ps1` and `scripts/restore.ps1` (PowerShell) and
   `scripts/backup.sh` and `scripts/restore.sh` (sh) run `backup` and `restore` from any folder, passing every argument
   through.

2. Back up what's on the chip now, even if you think it's broken:

   ```
   python -m charmera_toolkit backup --out backups/ --label chip-now
   ```

   `backup` reads the chip twice (change that with `--reads`). Every read lands first as
   `<label>_<UTC time>.unverified-read<n>.bin`. When the reads are identical, the first becomes the backup,
   `<label>_<UTC time>.bin`, and the others are deleted. Next to the backup, `backup` writes a `.sha256` and a
   `.manifest.json`. The manifest records the flashrom version, the programmer, the chip string, the SHA-256, whether
   the "SPII" magic and the build marks are present, whether the blank tail is blank, and whether the file matches
   one of the public chip backups.

   When the reads differ, each one stays under its `.unverified-read<n>.bin` name for you to compare, nothing else is
   written, and `backup` exits 1. A single read (`--reads 1`) keeps its `.unverified-read1.bin` name, with a
   `.sha256` and a manifest. No second read confirmed it, so it isn't a backup. `verify` and `stage` show a WARN for
   a `--stock` or a `--donor` with such a name (`stock-read`, `donor-read`), and the app refuses such a file as its
   backup. Read the chip again until two reads agree.

   A blank or corrupt chip is still saved, with warnings, because it's the record of what was there. A read that
   isn't 2,097,152 bytes is kept too, but `backup` exits 1 and says so. That file isn't a full-flash image and `restore` will
   refuse it. Get `probe` reporting a 2048 kB chip before you try again.

   On a healthy camera the manifest shows `magic_ok: true`, `build_marks_ok: true` and `tail_blank: true`. The build
   marks are the build string, the 12-digit stamp and the image-sensor set-up table. Each is found by its content and
   recorded with the offset where it was found (in the public chip backup, `0x18964`, `0x218C4` and `0xF5C6C`; in v012,
   `0x1897C`, `0x218D0` and `0xF5D3C`, where the table is v012's 167-write OV9732 table). `build` names the build
   when it's a known one: `public-dump`, `film-mods` or `v012` (the OV9732 cameras' factory build; the one
   OV9732 camera read so far that was never written to says `v012`), or `public-dump-modified`, `film-mods-modified` or
   `v012-modified` for that build with changes. A build with changes is one whose marks lie where that build
   keeps them and whose stamp, sensor table, source-tree folder and compile date are that build's
   ([Image layout](11-FORMAT.md#build-identity)). Any other build shows as `charmera-unknown-build`. That's a build
   nobody has published, and it's the backup worth most, because nobody else can give you that firmware.

   A camera that had firmware written to it before this read holds that firmware. A read of a camera with the public
   restore file written to it says `public-dump`. It's the way back to that state, but it doesn't tell you what fits the camera
   ([section D](#after-a-write)).

### Restore

Without a backup of this camera, write the cleaned public copy of the stock build for its sensor:

| What you have | Sensor | File to write |
| --- | --- | --- |
| Photos the camera took before the bad write with the bare 0x270-byte (624-byte) header or the 0x340-byte (832-byte) EXIF-mod header (any of this project's builds with EXIF dates), or `charmera identify` on them says stock v012 or this project's EXIF mod | OmniVision OV9732 | `charmera-v012-2026-01-12-clean.bin` from [charmera-firmware-v012](https://github.com/the-dumping-grounds/charmera-firmware-v012) |
| Photos from before the bad write with the 0x590-byte (1,424-byte) EXIF header, or `identify` says stock v006 or v008_test, unless the camera ever ran this project's OV9732 port on v006, which writes the same header on OV9732 cameras (then it's OV9732) | SP140A | `charmera-v006-2025-10-14-clean.bin` from [charmera-firmware-v006](https://github.com/the-dumping-grounds/charmera-firmware-v006) |
| No such photos, but `inspect` on the read of the broken chip finds v012's OV9732 table (`718cf3a1…`) or the SP140A table (`aea3976b…`) | the one that table drives (an SP140A table with this project's OV9732 port, which `inspect` names, means OV9732), with the caveat in item 3 below | the file for that sensor |
| None of these | unknown | read [section D](#d-firmware-that-does-not-fit-the-camera) before you write anything |

If you wrote this project's builds to the camera before, go by the last one that took good photos. Every build with the OV9732 port and every
v012 build means OV9732. So does every older numbered build, v0.21 to v0.27
([Older build names](06-ANDROID.md#older-build-names)).

The microSD card comes out of a dead camera, so read its photos in a computer with `charmera identify <the card>`
(or the app's Identify route). The header families are listed under [the donor route](#the-donor-route). Once the
camera boots again on the right stock build, any mod for that base goes on through the card (section A).

The sources, best first:

1. Your own full-flash backup made while the camera worked. It carries your settings and nothing from anyone else.
2. A backup you made of the broken camera, if the manifest says it's intact. In that case the chip probably isn't
   the problem, so look elsewhere before you write anything.
3. The cleaned public copy for the camera's sensor, from the table above. First run `inspect` on the read of the
   broken chip from step 2. The updater erases and programs one 64 KB block after another from block 0, so an
   update cut off part way leaves the later blocks as they were. If it stopped before block 15 (below about 50% on
   the screen for a 0x1E0000 file), the sensor table that chip held before the update, at `0xF5C6C` in v006 and
   `0xF5D3C` in v012, may have survived. If `inspect` finds a sensor table in that read, compare its SHA-256 with
   the copy you mean to write (v006 `aea3976b…`, v012 `718cf3a1…`). A different table means that copy drives the
   sensor differently from the firmware the chip held. The same table makes a fit likelier without proving it. A
   chip that had the wrong build written to it before holds that build's table, not the camera's own.

   Two public chip backups of v006 exist: SHA-256 `cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f`
   (benbadio/keychain-camera-plus) and `720ed0b65c91b149b0916498732b0ecf40227736ef5e3c126c487a7b5253436c`
   (Khimira/Project-Sirius). A cleaned copy of the first, with only its factory settings record and no stored photos,
   is in [the-dumping-grounds/charmera-firmware-v006](https://github.com/the-dumping-grounds/charmera-firmware-v006)
   (`75b911e9fc62cb3e4b461501916d1f1c336a28587296f03eba1f9d90cbc01082`). The OV9732 cameras' factory build v012,
   cleaned the same way, is in
   [the-dumping-grounds/charmera-firmware-v012](https://github.com/the-dumping-grounds/charmera-firmware-v012)
   (`63e396846f949600c2c1881920860821f2fcd70a70ac79ca54eebaee4a0ccb1c`).

   The two public chip backups and the v006 copy are identical in the code and resource area. The two backups differ only in
   the settings store (`0x170000`) and two internally stored photos (`0x180000`, `0x1A5800`) that belong to those
   owners (VERIFIED). A camera with one of them written to it carries that owner's settings and photos until you change
   them. The cleaned copies hold only the factory settings record and no photos. The v012 copy is the OV9732 build.
   Use it only on an OV9732 camera, never on an SP140A one, and use the v006 copy only on an SP140A camera. The
   toolkit recognizes all four by SHA-256 and says so. This project doesn't distribute them.

`restore` refuses an image that isn't exactly 2,097,152 bytes, doesn't start with `SPII`, or carries no Charmera
build marks (the build string, the stamp and the sensor table, found by content). It knows v006, v008_test and v012,
so both cleaned copies and a read of a v006 or v012 chip pass without a flag. It also refuses an image whose ARM
vector table (bytes 0x200..0x240, where the camera starts) is erased or isn't the one all three builds carry: with a
damaged one the camera won't start, and only a chip programmer brings it back. `--allow-unknown-build` overrides
those two checks and nothing else, so use it only for a good read of this camera's own chip. `restore` also refuses
an image whose code refers to more than one sensor program with different contents, since there's no telling which
one would set up the sensor. An image whose resource pack is damaged or missing isn't refused, but `restore` names
the damaged entries and asks for `DIFFERENT` too: the camera opens its boot logo from that pack before it reads the
card. It prints the image's size, SHA-256 and build marks and which build it is, and reminds you
that a build match says which public image it is, not whether it fits this camera.

`restore` never writes without first backing up the chip on the clip. It reads the chip itself, because a backup
file from elsewhere couldn't prove that it came from this chip as it is now. Before it writes, it does this, in
order:

1. It prints the checklist, as `probe` and `backup` do, and reads the chip twice. If the two reads differ, or aren't
   2,097,152 bytes, it writes nothing to the chip. Reads that differ stay as `<label>_<UTC time>.unverified-read1.bin` and
   `.unverified-read2.bin` for you to compare.
2. It keeps the read in the folder you give as `--out` (which it requires), the same way `backup` does:
   `<label>_<UTC time>.bin` with a `.sha256` and a `.manifest.json`. The label is `before-restore` unless you give
   `--label`. The manifest also names the image it was about to write. That read is the chip as it was just before
   the write, and your way back if the image doesn't suit the camera.
3. It prints which of the 32 blocks of 64 KB differ between the image and that read, so you can see, for example,
   that only the settings store changes. When the chip already holds the image byte for byte, it says so and writes
   nothing.
4. It prints the image's sensor table next to the chip's. It asks for a second typed word, `DIFFERENT`, after
   `RESTORE` when the tables differ, when the chip holds no table of the kind the known builds use (it's blank or
   damaged, or holds another kind of firmware), or when the chip holds several sensor programs. When the two tables
   drive different sensors it names both, for example the v012 copy going onto a chip that a wrong write left
   holding v006. It counts this project's OV9732 port (built on v006) as OV9732, though the port keeps the SP140A table. When
   the image is one of the cleaned copies, it says that's expected when you're putting back the build for the camera's
   sensor over a wrong one. Otherwise it says that nothing here can tell whether that firmware fits this camera.
5. It writes the bytes it checked to a private copy in a temporary folder, prints the exact flashrom command and the
   plan, and waits for you to type `RESTORE`. Just before flashrom runs it hashes that copy again. flashrom writes
   the copy, never your file in place, so a file that changed after the check can't reach the chip.

The command, with a source from the list above and a folder for the read it keeps:

```
python -m charmera_toolkit restore --image <source>.bin --out backups/
```

Once you've typed the words it asks for, flashrom erases, writes and verifies, and the toolkit reads the chip back
once more and compares the SHA-256 with the image. The toolkit puts no time limit on the write or on that read-back
and never interrupts flashrom itself. Any mismatch exits non-zero and says what to do next. Don't disconnect until it
reports "Restore verified".

After a verified restore, remove the clip, reconnect the battery, leave the shell open, and power on. An
original-series camera shows the yellow Kodak boot screen (REPORTED). Take a photo and play it back before you
reassemble the shell.

### Things that go wrong, and what they mean

- `probe` finds nothing: the clip isn't seated or is reversed, the battery is still connected, the programmer isn't
  at 3.3 V, or flashrom can't open the USB device (udev rule or sudo on Linux, Zadig driver on Windows).
- `probe` reports a size other than 2048 kB: usually a bad contact or back-powering, sometimes a wrong `--chip`.
- `backup` says the reads differ: contact or power. The kept `.unverified-read1.bin` and `.unverified-read2.bin`
  show which bytes move. Random single bits across the file mean contact, whole regions mean power. `restore`
  refuses for the same reason before it writes anything, keeps its two reads the same way, and the answer is the
  same.
- `restore` fails inside flashrom: keep everything connected, read the message, reseat the clip and run it again
  until flashrom reports VERIFIED. The toolkit tells the two cases apart: if flashrom stopped before it reached the
  erase (no chip found, several chip definitions, a USB or permission error), the chip is untouched and the answer is
  the same as for a failed `probe`. Otherwise the chip may hold a mix of old and new data until a restore verifies.
- The read-back after `restore` doesn't match: same answer, retry. If the same blocks keep failing, the chip may be
  worn or damaged (UNKNOWN for this part). It's a cheap chip, and someone who solders SOP8 can replace it, after
  which the same restore procedure applies.

## C. An interrupted write

The SD updater erases from offset 0 first (VERIFIED). If it was interrupted after the erase began, the start of the
flash, where the boot code lives, is gone and the camera won't boot. The updater can't run again because it's part
of what was erased. Only [section B](#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly) can bring
the camera back.

A clip write interrupted part way leaves a mix of old and new data. Keep the clip on and run `restore` again, and
flashrom will erase and rewrite what differs. If the clip was already removed, start
[section B](#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly) from the top.

No boot-ROM recovery mode is documented for the GPCV2247F (UNKNOWN). Single "ISP" and "3V" test pads sit below the
camera connector, and "RX_A6", "TX_A7" and "G" pads sit near the LED (probably a UART), but nobody knows what they
do (UNKNOWN). Don't count on them. As far as anyone has published, a camera whose flash was partly erased can't be
recovered without a clip write.

If the camera still boots after an interruption (it was interrupted before the erase started, or "Program error"
appeared), nothing was changed (VERIFIED for "Program error"). Go to
[section A](#a-the-camera-boots-and-the-updater-runs-restore-through-the-sd-card).

## D. Firmware that does not fit the camera

### The camera boots but photo mode freezes

After a write, the camera boots, shows its menus and plays back photos, but freezes when photo or video mode starts.
The card updater still runs, so a build that fits the camera's sensor, written from the card, fixes it. You don't
need to open the camera or have a backup.

1. Find the sensor, one of two ways.
   - Photos the camera took before the write. Use the app's "Identify this camera's firmware", or
     `charmera identify <the card>` on a computer (`photo-id` reads single files). "Stock v012" (the bare
     0x270-byte header) or this project's EXIF mod means an OV9732 camera, and "stock v006 or v008_test" (the 0x590-byte EXIF
     header) means an SP140A camera, unless the camera ran this project's OV9732 port on v006, which writes that header too.
     Photos taken after the write show the firmware that was written, so only the older ones count.
   - No such photos? Write the sensor-ID probe. In the app it's "Identify the image sensor". On a computer, make it
     with `charmera build --base v006 --mods sensor-probe --download --out out/`, verify and stage it like any update
     file, open photo mode once, then read the result with `charmera sensor-read <the card>`
     ([the sensor-ID probe](06-ANDROID.md#identify-the-image-sensor-the-sensor-id-probe)). The probe doesn't make
     photo mode work, and the write in step 2 replaces it.
2. Write the build that fits, from the app's "Write custom firmware" or with `charmera build` (`charmera build
   --list` shows every build and its `--mods`). This is for the original 2025 series only. For a Millennium or
   CLOT edition, whether any public build fits is unknown. See [the donor route](#the-donor-route).
   - OV9732: stock v012, the factory firmware of OV9732 cameras (in the app, base v012 with no mods ticked; on a
     computer, `charmera build --base v012 --download --out out/`), or v012 with any of its mods. v006 with the
     OV9732 port (`--base v006 --mods ov9732-port`), the v006 build changed to drive the OV9732, works too, with or
     without the Volume and LED rows (`volume-led`). The stock restore file is no way back for this camera.
   - An OV9732 camera that ran v006 with the OV9732 port and Volume and LED rows took v012 with Volume and LED rows,
     and both work on it ([OV9732 exposure, colour and white
     balance](15-OV9732-AE-WB-FLASH-PORT.md#11-v025-the-factory-ov9732-build-with-the-volume-and-led-rows)). The v006
     port keeps v006's boot and update code byte for byte, so that write ran v006's own updater. A v012 build written
     over stock v006 itself hasn't been tried, but the same updater does the work (INFERRED).
   - SP140A: the stock restore file (v006). On a computer, `charmera build --base v006 --download --out out/`. In the
     app, "Restore stock firmware" needs a backup. Without one, use Advanced > Download the stock restore file.

   On a computer, check the file with `charmera verify out/JH_5408<stamp>_<sum>.bin`. Without `--stock` it warns
   that nothing compares the file with the camera, and for a v012 file it says the table is for the OV9732. Then
   copy it with `stage`, leaving out `--stock` if you have no backup, and write it as in
   [section A](#a-the-camera-boots-and-the-updater-runs-restore-through-the-sd-card), steps 3 to 7.
3. Any other sensor, or a probe result naming neither: only [the donor route](#the-donor-route) is left.

### Why it happens

The symptom is a camera that boots, shows its menus, plays back photos and reacts to its keys, but freezes in photo and
video mode. The shutter clicks and the camera locks up until it switches itself off. The firmware drives exactly one
image sensor, never reads which one is fitted, and carries on when its writes fail (VERIFIED in the public chip backup; v012
has a chip-ID routine, but nothing calls it), so a camera with another sensor behaves exactly like this. A loose or
damaged sensor ribbon looks the same from the firmware's side. But if the camera took photos on its own firmware and
hasn't been opened or dropped since, that points at the firmware, not the ribbon. If such a camera's sensor marking
turns out to be the one the public firmware is said to drive, the difference lies somewhere else between the builds (how
the sensor is wired, powered or clocked, or its register values for that module), and still not in the ribbon
(INFERRED).

The known cases are the Millennium Edition (blue boot screen) and the CLOT edition with the original-series
image written to it (REPORTED by two owners), and an original-series camera of the 1987 blind-box design, bought in Japan in
November 2026, with the public chip backup written to it on 2026-11-06 ([the incident notes](FIRST-FLASH-INCIDENT.md)). So
neither the body nor the boot screen tells you which firmware a camera needs.

The version stamp doesn't tell you either. The public chip backup and the build on the charmera-film-mods author's camera both
carry `202505201029` with different bytes (VERIFIED: that build's code and resources are public as the project's restore
file). Their compile dates differ, `Oct 14 2025` and `Nov 19 2025`, and so do the source-tree folders in the paths of
their source files, `v006` and `v008_test` (VERIFIED). The OV9732 cameras' factory build v012, compiled `Jan 12 2026`
from the source-tree folder `v012`, carries the same stamp (VERIFIED). So the factory rebuilt the firmware under one
stamp, and a camera bought later may run a later build.

The toolkit names a build by a fingerprint over its bytes. It finds the build marks (the compile date and the
source-tree folder included) by their content, not at fixed offsets. It only calls a file a known build "with
changes" when its marks lie where that build keeps them and its stamp, sensor table, source-tree folder and compile
date are that build's. Any other file laid out like a known build is a build it doesn't know, "laid out like" that
one. A build match tells you which image a file was made from, and the toolkit says that's all it tells you. Among
the file checks, the one that says whether a file fits is `verify --stock <a chip backup of that camera>`, which compares
the file's sensor table with the camera's own, and only when that backup was read before the camera's first write
([After a write](#after-a-write)). Outside the files, photos from before the first write and the sensor-ID probe say
which sensor the camera has ([above](#the-camera-boots-but-photo-mode-freezes)).

Don't write an image from another camera to a camera whose own chip hasn't been read (the backup read in section B
counts). That includes a public chip backup and your own backup of another camera. The known files from `charmera build`
(stock v006 or v012 and the mods on them) are the exception when Identify, on photos taken before any write, names
that base: `verify`, `stage` and the app know these files by SHA-256. `restore` can't know which camera is on the clip. It reads the chip first
and asks a second time when the image's sensor table, or the sensor it drives, differs from the chip's, when the
chip holds no table of the kind the known builds use, or when it holds several sensor programs. But the same table proves no fit either.

If that has already happened, the camera boots, so the SD updater still runs. The way back is the camera's own backup
through [section A](#a-the-camera-boots-and-the-updater-runs-restore-through-the-sd-card), if it was read before the
write, or the stock build for its sensor ([above](#the-camera-boots-but-photo-mode-freezes)). v006 and v008_test carry
the same SP140A table, byte for byte, and v012 sets up the OV9732 with a table of another form, so the public builds
cover those two sensors and no other. For any other sensor, only a chip backup from a working camera with the same sensor can
help (the donor route below). The firmware never reads a sensor ID itself, so the sensor-ID probe or the marking on
the sensor module is how you tell, and owners of the same batch or edition are where to ask for a chip backup.

### After a write

A read of the chip taken after a write holds the firmware that was written. It doesn't hold the camera's own. It's the way back
to that state, and the donor route needs it, but it's no reference for which firmware fits. The stock restore file
matches the read of a camera it was written to, whether or not it fits that camera. A camera that had the
public restore file written to it and whose settings haven't changed since reads byte for byte like the public chip backup. `verify` then
shows `--stock` as a WARN, and the app refuses that read as a backup, because it holds no firmware known to fit the
camera. When the read is one of the known builds, `verify`, `stage` and the app show a matching sensor table as a
WARN. Then only something outside the files supports a fit: photos the camera took before its first write, the
sensor-ID probe, or the marking on the camera's sensor module compared with a working camera's (the donor route
below).

### The donor route

For a camera whose own firmware is gone, whose current firmware doesn't fit it, and whose sensor no public build
drives (neither the SP140A nor the OV9732), a chip backup of another working camera (the donor) is the only firmware that
can help. The toolkit's sensor check compares a file with the camera's own
read. After a write that read holds the firmware that fails, so the check refuses every donor file. `--donor` is the
deliberate way past it.

Before you open anything, compare a would-be donor's photos with photos this camera took on its own firmware, before
the write. `photo-id` reads the header that a camera's firmware writes into each photo, from the photo files alone:

```
python -m charmera_toolkit photo-id this-camera/PICT0001.jpg donor/PICT0001.jpg
```

It reads each file only up to the start of the image data (the SOS segment). For each file it reports the kind of
header, the size, the colour sampling, the JPEG quality on the IJG scale, the comment (COM) and the EXIF Make, Model
and Software, then gives its conclusion ([Command reference](07-USAGE.md#read-only-commands)). The conclusion names
one of three still-photo headers:

- The v006 / v008_test family (the SP140A builds, and this project's OV9732 port on v006): 1,424 bytes, an EXIF block (Make
  `Generalplus`, Model `CBB3`, Software `20150615`) and the comment `GPEncoder` padded with spaces. VERIFIED from its
  header template, the code that fills it and the two photos stored in the v006 chip backup. v008_test holds the same,
  VERIFIED by comparing the bytes. A photo can't tell v006 from v008_test.
- The factory build v012 (OV9732 cameras, and this project's v012 builds without EXIF dates): a bare 624-byte header, no EXIF
  block, and the comment `GPEncoder` alone (VERIFIED: v012 holds only this template).
- This project's EXIF mod (any of this project's v012 builds with EXIF dates): 832 bytes, one EXIF block with Make `Kodak` and Model
  `Charmera` right after the start marker, then v012's bare header.

A photo the 2026-11-06 camera took on its original firmware has the bare 624-byte header, 1440x1080, IJG quality 80
(VERIFIED from that photo's first 624 bytes). That's the kind the factory build v012 writes. The v006 family keeps
the same bare header for thumbnails, video frames and a Wi-Fi stream.

Compare the kind of header and the comment. The size and the quality follow the camera's settings and whether a card
was in (VERIFIED in v006), so they say nothing on their own. Use the photo files as they came off each camera's card.
Don't use a copy that a phone, an editor, a messaging app or the toolkit's own `exif-fix` saved again (`photo-id`
recognises an exif-fix copy and doesn't mistake it for the EXIF mod), and don't use a frame taken out of a video,
which has the bare header too. `identify` reads a whole card at once and says whether it holds photos of two families,
which suggests a write in between.

Here's what that can prove. Each of the three headers is written by a camera's own firmware. So if a donor's photos
carry one family's header and this camera's photos from before the write carry another's, the donor was running a build other
than the one this camera came with when it took them. A photo taken after a write shows the firmware that was written, not the
one that fits. Only this camera's photos from before the write say what it came with.

A header no known build writes says less. `photo-id` only calls a photo re-saved by another program when its header
carries a mark an editor leaves (an XMP block, an APP2, APP13 or APP14 segment, a progressive frame, a rotated size).
For any other unknown header, either another program saved the photo again or another build wrote it, and nothing in
the header tells which. The same header doesn't show the same build and proves nothing about the image sensor. It
makes the donor worth opening, to compare the marking on its sensor module with this camera's and to read its chip.
A video helps less. Its frame header doesn't tell the builds apart (INFERRED), though a text in its `JUNK` chunk
other than the packer text v006, v008_test and v012 carry shows other code
([Command reference](07-USAGE.md#read-only-commands)).

What the route needs, and what it doesn't prove:

- Optionally, a read of this camera's chip as it is now, made with a clip
  ([section B](#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)) and given as `--stock`. Without
  one, a WARN says that nothing compares the file with what the camera runs. If this camera runs a firmware you
  wrote from a card, that update file is the way back as long as the donor's firmware starts far enough to run the
  card updater. Opening a camera that works only to read it is a risk of its own.
- A complete read of the donor's chip (exactly 2,097,152 bytes, starting with `SPII`, with the build string and the stamp).
  Ideally the donor is the same edition, bought at the same time and place, its photos carry the same header as this
  camera's own, and its sensor module carries the same marking as this camera's.
- Nothing here checks whether the donor's firmware fits this camera. Matching sensor markings make it likely. Only
  the write itself proves it, and other board differences can remain.
- If the donor's firmware starts far enough to run the card updater, this camera's own read can be written back from
  a card ([section A](#a-the-camera-boots-and-the-updater-runs-restore-through-the-sd-card)). If it doesn't start,
  only a chip programmer can put it back, so have the clip ready before you write.

```
python -m charmera_toolkit pack --input donor.bin --official-size --out out/donor/ --i-understand-this-file-flashes-a-camera
python -m charmera_toolkit verify out/donor/JH_5408<stamp>_<sum>.bin --stock this-camera-now.bin --donor donor.bin
python -m charmera_toolkit stage out/donor/JH_5408<stamp>_<sum>.bin --card E: --stock this-camera-now.bin --donor donor.bin
```

`--official-size` keeps the donor's settings and stored photos out of this camera. The final erase then gives it
factory settings and an empty photo list. If `pack` says the donor carries no sensor table of the kind the known
builds use (a camera with another kind of sensor may keep its table in another form), add `--allow-unknown-build` to
`pack`. `verify` and `stage` take such a donor with `--donor`, as a WARN that says so.

`verify` checks that the file is the donor's firmware unchanged, and that the stamp in its name differs from the one
in this camera's read (`stamp-camera`). With `--allow-modified`, which `stage` always gives, it also takes a mod built
on the donor's firmware that keeps the donor's boot header, vector table (0..0x240) and sensor table, and says so in
a WARN. It shows how the file's sensor table compares with this camera's and names the way back. Some things are a
FAIL on this route too, whatever the flags: an ARM vector table that differs from this camera's read in a file laid
out like it, or from the vector table of the known build whose layout the file has, and several sensor programs in
the file or in `--stock`. The app doesn't take this route. You need a computer for it.

If you own a camera that works, make a backup now
([section B](#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)'s `backup`, with the camera opened
the same way). That backup restores it later with its own firmware and settings. If its build is one nobody has
published, it's the only thing that can, and it's also what other owners with the same parts will need.

## Reference

The image (VERIFIED in both public chip backups; the film-mods offsets VERIFIED in that project's public restore file, and
v012's in a read of a v012 camera):

| Offset | Content |
| --- | --- |
| `0x000000` | magic `SPII`, start of the code |
| `0x018964` | build string `JH5408  20250520` (in the public chip backup; `0x1897C` in the film-mods build and in v012, so the toolkit finds it by content) |
| `0x0218C4` | version stamp `202505201029` (public chip backup; `0x218D0` in the film-mods build and in v012; shared by every known build, so it identifies none of them) |
| `0x0F3978` | compile date `Oct 14 2025` (public chip backup; `Nov 19 2025` at `0xF3604` in the film-mods build; `Jan 12 2026` at `0xF3A48` in v012) |
| `0x0F5C6C` | the 61-write image-sensor set-up table for the SP140A (public chip backup; `0xF5D34` in the film-mods build). v012 has its 167-write OV9732 table at `0xF5D3C` |
| `0x169000` | end of a film-mods-size update file (`pack --official-size`); its final erase clears the block at `0x170000` |
| `0x170000` | settings store (records appended as settings change) |
| `0x180000`, `0x1A5800` | internally stored photos in the public chip backups |
| `0x1E0000` | end of a community-size update file; its final erase clears the block at `0x1F0000`, and neither size writes or erases `0x1E0000..0x1EFFFF` |
| `0x1E0000` to `0x200000` | `0xFF` in every known chip backup |

Hardware, VERIFIED unless marked otherwise. The SoC is a Generalplus GPCV2247F, an ARM7TDMI (VERIFIED from the
firmware's own source paths and the product page). The flash is a Puya PY25D16HB, 16 Mbit, SOP8 150 mil, 2.3 to
3.6 V (package and clip-friendliness REPORTED by the teardown and a second owner; voltage VERIFIED from Puya's
part-number coding). The CH341A programmer's USB ID is `1a86:5512`.

Toolkit commands used here: `probe`, `backup` and `restore` (this document), `pack`, `verify` and `stage` (section
A), `identify`, `photo-id`, `build` and `sensor-read` (section D), and `python -m charmera_toolkit.serprog`
(the Pico reader in section B). They all print help with `--help`. Exit code 0 means the command did what it says.
Anything else means stop and read the message.

---

Reading order: 2 of 15 · previous: [The first write](01-FIRST-FLASH.md) · next: [Candidate files](03-FLASH-CANDIDATES.md) · [all docs](README.md)
