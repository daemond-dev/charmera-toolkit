# Recovering a Charmera

Reading order: 2 of 13 · previous: [The first flash](01-FIRST-FLASH.md) · next: [Flash candidates](03-FLASH-CANDIDATES.md) · [all docs](README.md)

A decision tree for getting a Kodak Charmera (original 2025 series: Generalplus GPCV2247F, 2 MiB Puya
PY25D16HB SPI flash) back to a working firmware. It is written for a careful owner with no prior flashing
experience. Read the whole section that applies to you before touching anything.

Every factual claim carries one of three labels:

- VERIFIED: checked against documents or the firmware binary itself.
- REPORTED: stated by community members who did it; not independently checked.
- UNKNOWN: nobody has published it.

Nothing here distributes Kodak or Reto firmware. The restore source is always your own backup, made with
`python -m charmera_toolkit backup` while the camera still worked. If you have no backup, read [section B](#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)
to the end before deciding anything.

## Which situation are you in?

1. Does the camera boot, show its menus and play back photos, but freeze when photo or video mode starts, after a
   flash? That is firmware that does not fit the camera: [section D](#d-firmware-that-does-not-fit-the-camera) first,
   whatever the body. A Millennium Edition (blue boot screen) or a CLOT edition flashed with an original-series image
   is the known case; an original-series camera had it on 2026-11-06 too.
2. Does the camera power on and get far enough to mount the SD card (it shows the live view, plays back
   photos, or reacts to a card)? Yes: [section A](#a-the-camera-boots-and-the-updater-runs-restore-through-the-sd-card). No: [section B](#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly).
3. Was an update interrupted before it reached 100% (battery died, card pulled, camera switched off while
   "Upgrade NN%" was still counting), or a clip write cut short (cable or clip pulled)? Read
   [section C](#c-an-interrupted-flash), which will send you to A or B. An update that reached 100% and was
   then switched off with the power key finished normally; that is how every update ends.

## A. The camera boots and the updater runs: restore through the SD card

The SD updater is the gentle route. It cannot help a camera that does not boot far enough to mount the
card (VERIFIED from the SDK source: the updater is part of the firmware that is already running). The same
steps, for a first flash of a modified file rather than a restore, are in [The first flash](01-FIRST-FLASH.md).

What the updater does (VERIFIED from the SDK source, the firmware itself and hardware reports; the long form
is [Research report, section 4](13-PHASE1-REPORT.md#4-the-sd-updater-flow)):

- It looks only during the first 5 seconds after power-on, so the card must be in before the camera is
  switched on, and only in the card root, for a file named `JH_5408<12-digit timestamp>_<8 hex digits>.bin`.
- The 8 hex digits are the unsigned 32-bit sum of every byte of the file. That sum is the only integrity
  check. A wrong sum shows "Program error" on the screen and nothing is erased.
- The 12-digit timestamp must differ from the stamp compiled into the running firmware
  (`202505201029` on the original series), or the file is ignored (VERIFIED, see
  `charmera_toolkit/checksum.py`).
- With a correct sum it erases the flash in 64 KiB blocks from offset 0, programs 256-byte pages and
  verifies them. "Upgrade NN%" advances once per 64 KiB block. At 100% the camera stays on its last screen:
  nothing reboots by itself, and the updater never deletes or renames the file. Holding the power key about a
  second switches the camera off.
- Published update files are `0x1E0000` bytes (30 blocks). Do not put a full 2 MiB dump on the card: the
  toolkit's `verify` refuses it, and one owner reports a bricked camera from doing so (REPORTED). `pack`
  trims your dump to the right size; the last 128 KiB are blank in every known dump, so nothing is lost.

Steps:

1. Build the update file from your own backup. `pack` prints a plan first and writes nothing:

   ```
   python -m charmera_toolkit pack --input <your backup>.bin --out out/restore/
   ```

   Read the plan. When it is what you expect, run the same command again with the gate flag that `pack`
   names in its output (`--i-understand-this-file-flashes-a-camera`). It writes
   `out/JH_5408<stamp>_<sum>.bin`, a `.sha256` and a `.MANIFEST.txt`.

2. Check the file. `verify` is read-only and fails closed:

   ```
   python -m charmera_toolkit verify out/JH_5408<stamp>_<sum>.bin --stock <your backup>.bin
   ```

   Any `FAIL` line means do not use the file. Fix the cause and repeat step 1.

3. Copy only that one `.bin` file to the root of the card the camera normally uses. Leave the `.sha256`
   and the manifest on the computer. `charmera stage` does this copy with its checks
   ([Putting the file on the card](04-STAGE.md)); from a phone, see [Using an Android phone](06-ANDROID.md).
   Eject the card (Safely Remove, `udisksctl unmount`, the file manager's or Android's eject) before you take
   it out of the reader or the phone.

4. Check the body once more: original 2025 series only, matte with the yellow Kodak boot screen, never a
   Millennium or CLOT edition ([section D](#d-firmware-that-does-not-fit-the-camera)). Charge the battery fully and unplug the cable:
   an update that loses power part way is [section C](#c-an-interrupted-flash). With the camera off, put
   the card in, then power on. The updater finds the file and shows "Upgrade NN%" counting up, about 15 to 20
   seconds in all.

5. Do not power off and do not pull the card while it counts. At 100% the camera stays on its last screen
   and does not reboot by itself. Take the card out, then hold the power key about a second until the camera
   switches off.

6. Power the camera on without the card. It should boot normally.

7. Delete the update file from the card before you use the card again. The camera never deletes it, and
   the updater looks at the card in the first 5 seconds after every power-on (VERIFIED), so a card left with
   the file in it flashes again at the next power-on.

If the screen shows "Program error": the byte sum in the filename does not match the file. Nothing was
erased (VERIFIED), and the camera switches itself off. Take the card out, delete the file, rebuild it with
`pack` and check it with `verify` before trying again. A file renamed by hand or truncated by a bad copy is
the usual cause.

If the camera loses power before 100% in step 5, the flash holds a partly erased or partly written firmware
and the camera will most likely not boot. That is [section C](#c-an-interrupted-flash).

## B. The camera does not boot: read and write the flash chip directly

This route opens the camera and clips a programmer onto the flash chip. It is the only known way to
recover a camera that no longer boots (there is no documented boot-ROM recovery for this SoC, UNKNOWN
status for the "ISP" header; see [section C](#c-an-interrupted-flash)). Done carefully it is routine; done at the wrong voltage it
can destroy the chip or the SoC. Read all of this section first.

### Tools

- A 3.3 V SPI programmer that flashrom supports. Safe choices: a blue CH341A board jumpered to 3.3 V, a
  "V1.7" CH341A board with a 3.3 V / 5 V switch set to 3.3 V, a Raspberry Pi Pico running pico-serprog,
  or a CH347. The common black CH341A board is NOT safe as sold: its data lines run at 5 V unless it is
  modified (lift pin 28 of the CH341A, or tie it to 3.3 V); flashrom's own documentation warns about it.
  Do not use a 1.8 V adapter board: this chip is a 3.3 V part.
- An SOIC-8 / SOP8 test clip (150 mil) with its ribbon cable. The chip has visible legs, so a clip works
  (REPORTED by the teardown; a second owner confirmed a clip read).
- flashrom, a current release. Linux: your distribution's package. Windows: flashrom.org lists Windows builds
  and the libusb requirement; the CH341A needs the WinUSB (or libusb-win32) driver installed with Zadig
  on USB device `1a86:5512`. The toolkit prints the same hints when it cannot find flashrom.
- On Linux, access to the programmer without sudo: copy `scripts/70-ch341a.rules` to
  `/etc/udev/rules.d/` (keep the `70-` prefix: it has to sort before systemd's `70-uaccess.rules` and
  `73-seat-late.rules` for the uaccess tag to take effect at plug time), run
  `sudo udevadm control --reload-rules && sudo udevadm trigger`, replug the programmer. Or run the
  toolkit commands with sudo.
- A flat screwdriver or a plastic spudger, a small cross-head screwdriver, a magnifier or a phone camera
  for reading the chip and the pin 1 dot, tape, and a tray for the tiny parts.
- This toolkit and Python 3.9 or newer.

### Opening the camera

From JP's teardown (REPORTED):

- Pry the two halves of the shell apart with a flat screwdriver, working around the seam.
- The ribbon cable on the viewfinder side releases by lifting the latch on its connector. Do not pull
  the cable with the latch closed.
- Two small cross-head screws hold the board.
- The viewfinder lens is loose once the shell is open and falls out. Put it somewhere safe.
- The speaker wires are fragile. Do not let the speaker hang from them.
- The battery is wired, not socketed, and taped to the board.

### Identifying the flash chip

Look for an 8-pin SOP8 (SOIC-8, 150 mil) package marked `PY25D16HB` (Puya). It is a 16 Mbit part,
2 MiB (the part number is REPORTED by the teardown; the 2 MiB size is VERIFIED by every dump). A dot or a chamfered corner marks pin 1.
If the marking is different, stop and note it: the rest of this guide assumes this part.

Pinout, standard for 25-series SPI flash in this package (the exact PY25D16HB datasheet is not public;
Puya's sibling datasheets follow the standard, so treat this as REPORTED until you have checked it
against the markings on your clip and programmer): 1 CS#, 2 SO (MISO), 3 WP#, 4 GND, 5 SI (MOSI),
6 SCLK, 7 HOLD#, 8 VCC. A CH341A's ZIF socket and its clip cable are wired for this pinout; you only have
to get pin 1 right.

### Voltage and back-powering

- Puya's part-number coding says the "H" in PY25D16HB means a 2.3 to 3.6 V supply (VERIFIED from Puya's
  datasheets of sibling parts). Program it at 3.3 V.
- The SoC's I/O is 3.3 V and it shares the rail with the flash. A clip read feeds the whole board through
  the flash VCC pin, so the programmer's regulator ends up carrying the board. Expect that. If reads are
  unstable, the chip is not detected, or the detected size is wrong, back-powering with the battery still
  connected is the usual cause.
- Disconnect the battery before clipping. It is wired and taped to the board: unplug or unsolder its
  connector at the board, tape the free lead so it cannot touch anything, and leave it off until the clip
  is removed.

### Clip orientation

- The clip's cable usually marks pin 1 with a red stripe or a "1". Put that side on the chip's dot.
- On the programmer, the SOP8 adapter or ZIF socket has pin 1 marked on the silkscreen. Match it.
- A reversed clip usually just fails to detect the chip, but it puts supply voltage on the wrong pins,
  which is one more reason to use a 3.3 V programmer (general CH341A experience, not camera-specific).
- Seat the clip squarely; a half-seated clip produces reads that differ from each other.

### Read before you write

1. Prove the chain works and nothing is written:

   ```
   python -m charmera_toolkit probe
   ```

   It prints flashrom's version, then what flashrom saw. Expect a 2048 kB chip. The Puya part is not in
   flashrom's chip table by name; the community read it as `-c "SFDP-capable chip"` (REPORTED), which is
   the toolkit's default. `--chip auto` lets flashrom probe on its own, `--programmer` selects another
   programmer (for a Pico: `serprog:dev=/dev/ttyACM0` or `serprog:dev=COM3`).

2. Back up what is on the chip now, even if you think it is broken:

   ```
   python -m charmera_toolkit backup --out backups/ --label before-restore
   ```

   `backup` reads the chip twice (change with `--reads`; a single read is saved but flagged as not
   cross-checked) and only keeps the result when every read is identical; otherwise it keeps the reads
   for you to compare and exits 1. It then writes `<label>_<UTC time>.bin`, a `.sha256` and a
   `.manifest.json` that records the flashrom version, the programmer, the chip string, the SHA-256,
   whether the "SPII" magic and the build stamps are present, whether the blank tail is blank, and
   whether the file matches one of the public dumps. A blank or corrupt chip is still saved, with
   warnings: it is the record of what was there. A read that is not 2 MiB is also kept, but `backup`
   exits 1 and says so: that file is not a full-flash image, `restore` will refuse it, and `probe`
   should be reporting a 2048 kB chip before you try again.

   On a healthy camera the manifest shows `magic_ok: true`, `build_marks_ok: true` (the build string, the 12-digit
   stamp and the image-sensor set-up table, each found by its content, with the offset where it was found; in the
   public dump `0x18964`, `0x218C4` and `0xF5C6C`) and `tail_blank: true`. `build` names the build when it is a known
   one (`public-dump`) and says `charmera-unknown-build` when nobody has published it, which is the backup worth most:
   nobody else can give you that firmware.

### Restore

The source, in order of preference:

1. Your own full-flash backup made while the camera worked. It carries your settings and nothing from
   anyone else.
2. A backup you made of the broken camera, if the manifest says it is intact (then the chip is probably
   not the problem; look elsewhere before writing).
3. A public stock dump, only as a last resort. Two exist for the original series: SHA-256
   `cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f` (benbadio/keychain-camera-plus)
   and `720ed0b65c91b149b0916498732b0ecf40227736ef5e3c126c487a7b5253436c` (Khimira/Project-Sirius).
   They are identical in the code and resource area and differ only in the settings store (`0x170000`)
   and two internally stored photos (`0x180000`, `0x1A5800`) that belong to those owners (VERIFIED). A
   camera flashed with one of them carries that owner's settings and photos until you change them. The
   toolkit recognizes both by SHA-256 and says so. This project does not distribute them.

Then:

```
python -m charmera_toolkit restore --image <source>.bin --backup <backup of the chip as it is now>.bin
```

`restore` refuses an image that is not exactly 2,097,152 bytes, does not start with `SPII`, or carries no
Charmera build marks (the build string, the stamp and the sensor table, found by content; `--allow-unknown-build`
overrides that check and nothing else; use it only for an image you know belongs to this camera). It prints which
build the image is, and that a build match says which public image it is, not whether it fits this camera. `--backup` takes a fresh backup of the chip as it is
now (the one from step 2 above, made with the clip still in place); `restore` then prints which of the
32 blocks of 64 KiB differ between that backup and the image, which is exactly what the write will
change, so you can see that, for example, only the settings store changes. An older backup still gives a
useful comparison, but then the blocks it lists as identical are only identical to that file, not
necessarily to the chip. It prints the SHA-256 and the exact flashrom command, and waits for you to type
`RESTORE`. Then flashrom erases, writes and verifies, and the toolkit reads the chip back once more and
compares the SHA-256 with the image. The toolkit puts no time limit on the write or on that read-back
and never interrupts flashrom itself. Any mismatch exits non-zero and says what to do next; do not
disconnect until it reports "Restore verified".

After a verified restore: remove the clip, reconnect the battery, close nothing yet, and power on. An
original-series camera shows the yellow Kodak boot screen (REPORTED). Take a photo and play it back
before you reassemble the shell.

### Things that go wrong, and what they mean

- `probe` finds nothing: clip not seated or reversed, battery still connected, programmer not at 3.3 V,
  or flashrom cannot open the USB device (udev rule or sudo on Linux; Zadig driver on Windows).
- `probe` reports a size other than 2048 kB: usually a bad contact or back-powering, sometimes a wrong
  `--chip`.
- `backup` says the reads differ: contact or power. The kept `.read1.bin` and `.read2.bin` show which
  bytes move; random single bits across the file mean contact, whole regions mean power.
- `restore` fails inside flashrom: keep everything connected, read the message, reseat the clip and run
  it again until flashrom reports VERIFIED. The toolkit tells the two cases apart: if flashrom stopped
  before it reached the erase (no chip found, several chip definitions, a USB or permission error), the
  chip is untouched and the answer is the same as for a failed `probe`; otherwise the chip may hold a
  mix of old and new data until a restore verifies.
- The read-back after `restore` does not match: same answer, retry. If the same blocks keep failing, the
  chip may be worn or damaged (UNKNOWN for this part; it is a cheap chip and can be replaced by someone
  who solders SOP8, after which the same restore procedure applies).

## C. An interrupted flash

What is known and what is not:

- The SD updater erases from offset 0 first (VERIFIED). If it was interrupted after the erase began, the
  start of the flash, where the boot code lives, is gone and the camera will not boot. The updater cannot
  run again because it is part of what was erased. Only [section B](#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly) can bring the camera back.
- A clip write interrupted part way leaves a mix of old and new data. Keep the clip on and run `restore`
  again; flashrom will erase and rewrite what differs. If the clip was already removed, start [section B](#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)
  from the top.
- No boot-ROM recovery mode is documented for the GPCV2247F (UNKNOWN). A 10-pin "ISP" header footprint
  and a "3V" test pad exist on the board, but their pinout and function are not known (UNKNOWN). Do not
  count on them. Without a clip write, a camera whose flash was partly erased is, as far as anyone has
  published, unrecoverable.
- If the camera still boots after an interruption (it was interrupted before the erase started, or
  "Program error" appeared), nothing was changed (VERIFIED for "Program error"). Go to [section A](#a-the-camera-boots-and-the-updater-runs-restore-through-the-sd-card).

## D. Firmware that does not fit the camera

- The symptom: the camera boots, shows its menus, plays back photos and reacts to its keys, but photo and video mode
  freeze; the shutter clicks and the camera locks up until it switches itself off. The firmware drives exactly one
  image sensor, never reads which one is fitted, and carries on when its writes fail (VERIFIED in the public dump),
  so a camera with another sensor behaves exactly like this. A loose or damaged sensor ribbon looks the same from
  the firmware's side, but a camera that took photos on its own firmware and was not opened or dropped since points
  at the firmware, not the ribbon. If such a camera's sensor marking turns out to be the one the public firmware is
  said to drive, the difference lies elsewhere between the builds (how the sensor is wired, powered or clocked, or
  its register values for that module), still not in the ribbon (INFERRED).
- Known cases: the Millennium Edition (blue boot screen) and the CLOT edition flashed with the original-series image
  (REPORTED by two owners), and an original-series camera of the 1987 blind-box design, bought in Japan in November
  2026, flashed with the public dump on 2026-11-06 ([the incident notes](FIRST-FLASH-INCIDENT.md)). So the body and
  the boot screen do not say which firmware a camera needs.
- The version stamp does not either: the public dump and the build on the charmera-film-mods author's camera both
  carry `202505201029` with different bytes (VERIFIED: that build's code and resources are public as the project's
  restore file). Their compile dates differ, `Oct 14 2025` and `Nov 19 2025`, so the factory rebuilt the firmware
  under one stamp, and a camera bought later may run a later build. The toolkit names a build by a fingerprint over
  its bytes, finds the build marks (compile date included) by their content rather than at fixed offsets, and says
  plainly that a build match tells which image a file was made from, not whether it fits a camera. The one check
  that does is `verify --stock <a dump of that camera>`, the file's sensor table against the camera's own, and only
  when that dump was read before the camera's first flash: a read taken after one shows what the camera runs now,
  not what fits it.
- Do not flash an image from another camera, your own backup of another camera or a public dump included, onto a
  camera whose own chip has not been read. `restore` cannot know which camera is on the clip.
- If it has already been done: the camera boots, so the SD updater still runs. The way back is the camera's own
  backup through [section A](#a-the-camera-boots-and-the-updater-runs-restore-through-the-sd-card). Without one, only a
  dump from a working camera with the same sensor can help; identify the sensor from the marking on its module (the
  firmware never reads an ID, so the marking is the way), and ask owners of the same batch or edition for a dump. The
  two known builds carry the same sensor table, byte for byte, so neither helps a camera with another sensor.

### The donor route

For a camera whose own firmware is gone and does not fit it, a dump of another, working camera (the donor) is the
only firmware that can help. The toolkit's sensor check compares a file with the camera's own read, which after a
flash holds the firmware that fails, so it refuses every donor file; `--donor` is the deliberate way past it.

What it needs, and what it does not prove:

- A read of this camera's chip as it is now, with a CH341A clip ([section B](#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)):
  no backup of your own camera, no flash, on this route as on every other. It is the only way back if the donor's
  firmware does not suit this camera.
- A complete read of the donor's chip (2 MiB, with the build marks). Ideally the donor is the same edition, bought at
  the same time and place, and its sensor module carries the same marking as this camera's.
- Whether the donor's firmware fits this camera is not checked by anything here. Matching sensor markings make it
  likely; nothing short of the flash proves it, and other board differences can remain.
- If the donor's firmware starts far enough to run the card updater, this camera's own read can be flashed back from
  a card ([section A](#a-the-camera-boots-and-the-updater-runs-restore-through-the-sd-card)). If it does not start,
  only a chip programmer can put it back, so have the clip ready before you flash.

```
python -m charmera_toolkit pack --input donor.bin --official-size --out out/donor/ --i-understand-this-file-flashes-a-camera
python -m charmera_toolkit verify out/donor/JH_5408<stamp>_<sum>.bin --stock this-camera-now.bin --donor donor.bin
python -m charmera_toolkit stage out/donor/JH_5408<stamp>_<sum>.bin --card E: --stock this-camera-now.bin --donor donor.bin
```

`--official-size` keeps the donor's settings and stored photos out of this camera; the final erase then gives it
factory settings and an empty photo list. If `pack` says the donor carries no sensor table of the kind the known
builds use (a camera with another kind of sensor may keep its table in another form), add `--allow-unknown-build` to
`pack`; `verify` and `stage` accept such a donor with `--donor` and say so. `verify` checks that the file is the
donor's firmware unchanged, shows how its sensor table compares with this camera's, and names the way back. The
app does not take this route: a computer does.
- If you own a camera that works, make a backup now ([section B](#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)'s
  `backup`, with the camera opened the same way). That backup is the only thing that can restore it later, and if its
  build is one nobody has published, it is what other owners with the same parts will need.

## Reference

The image (VERIFIED in both public dumps):

| Offset | Content |
| --- | --- |
| `0x000000` | magic `SPII`, start of the code |
| `0x018964` | build string `JH5408  20250520` (in the public dump; another build keeps it elsewhere, so the toolkit finds it by content) |
| `0x0218C4` | version stamp `202505201029` (shared by two different builds; identifies neither) |
| `0x0F3978` | compile date `Oct 14 2025` (public dump; `Nov 19 2025` at `0xF3604` in the film-mods build) |
| `0x0F5C6C` | the 61-write image-sensor set-up table (public dump; `0xF5D34` in the film-mods build) |
| `0x170000` | settings store (records appended as settings change) |
| `0x180000`, `0x1A5800` | internally stored photos in the public dumps |
| `0x1E0000` to `0x200000` | `0xFF` in every known dump; update files stop here |

Hardware (VERIFIED unless marked): SoC Generalplus GPCV2247F, ARM7TDMI (VERIFIED from the firmware's own
source paths and the product page). Flash Puya PY25D16HB, 16 Mbit, SOP8 150 mil, 2.3 to 3.6 V (package and
clip-friendliness REPORTED by the teardown and a second owner; voltage VERIFIED from Puya's part-number
coding). Programmer USB ID for the CH341A: `1a86:5512`.

Toolkit commands used here: `probe`, `backup`, `restore` (this document), `pack` and `verify` (section
A). All of them print `--help`. Exit code 0 means the command did what it says; anything else means stop
and read the message.

---

Reading order: 2 of 13 · previous: [The first flash](01-FIRST-FLASH.md) · next: [Flash candidates](03-FLASH-CANDIDATES.md) · [all docs](README.md)
