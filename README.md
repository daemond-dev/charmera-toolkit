# Charmera toolkit

Tools for backing up, inspecting, verifying, recovering and modding the Kodak Charmera keychain camera
(original 2025 series, Generalplus GPCV2247F, 2 MiB Puya PY25D16HB SPI flash). Windows and Linux, Python 3.9+,
standard library only.

Status: every command is unit-tested against two public dumps and the published update files. Nothing has been
flashed to a camera yet; [Flash candidates](docs/03-FLASH-CANDIDATES.md) lists the first files meant to be tried,
with hashes and the exact bytes they change.

**Start with [The first flash](docs/01-FIRST-FLASH.md).** It walks through backup, choosing a file, verifying,
putting it on the card, flashing and undoing, and links to every other document at the step where you need it.

```
pip install -e .
charmera --help
```

## Commands

Examples for every command are in the [Command reference](docs/07-USAGE.md).

| Command | Writes to | What it does |
|---|---|---|
| `inspect` | nothing | Identify a dump or update file: build, hashes, sounds, settings, stored photos. |
| `verify` | nothing | Gate an update file before it goes on a card: name checksum, stamp, size and erase rule, build, diff against stock. |
| `diff` | nothing | Compare two images region by region; explains settings and resource changes. |
| `sounds` | a file | List, extract, mute or replace the five UI sounds. |
| `settings` | a file | Show the record the camera will load, or write one with new auto-off, standby, volume values. |
| `tweak` | a file | Experimental start-up and power-off byte patches, each checked against the stock bytes. |
| `frames` | a file | List, extract, preview or replace the four photo frames (PNG in and out, budgets checked). |
| `resources` | nothing / a file | List or extract every resource entry; replace a JPEG resource such as the boot logo. |
| `pack` | a file | Turn an image into a correctly named update file, optionally with settings and photos blanked. |
| `stage` | the card | Re-verify an update file, copy it to the card root and read it back. |
| `card-check` / `card-fix` | nothing / the card | Diagnose and fix the exFAT dirty-bit problem that stops phones reading the card. |
| `exif-fix` | copies of photos | Repair the camera's broken EXIF dates and AVI dates on copies, never on the card. |
| `probe` / `backup` / `restore` | nothing / files / the chip | CH341A full-flash backup and restore through flashrom. |

On a phone, the [Charmera Card app](android/README.md) checks an update file and puts it on the SD card
([download the APK](https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk);
the repository is private, so sign in to GitHub in the phone's browser first).

Ground rules baked into the commands:

- Read-only by default. Nothing is written to a card, a chip or a camera without a typed confirmation.
- Anything that produces a flashable or modified image needs an explicit flag and prints its plan first.
- `pack` refuses an input it cannot identify as the original 2025 series, and `verify` fails any file whose
  erase footprint would reach beyond what the published update files touch.
- No Kodak or Reto firmware is distributed here. The tools work on your own backup; upstream files are referenced
  by URL and hash in the [Research report](docs/13-PHASE1-REPORT.md).

## Documentation

In reading order; the [docs index](docs/README.md) has the same list. Each document links to the previous and
next one, and to the others where it mentions them.

Before and during a flash:

1. [The first flash](docs/01-FIRST-FLASH.md): start here; the whole path, with the point of no return marked.
2. [Recovery](docs/02-RECOVERY.md): read before you flash; the SD updater and CH341A routes back, and the editions
   that must never be flashed.
3. [Flash candidates](docs/03-FLASH-CANDIDATES.md): the first three files to try, their hashes and what each changes.
4. [Putting the file on the card](docs/04-STAGE.md): the `stage` command and what each refusal means.
5. [SD card problems](docs/05-CARDS.md): why phones reject a card the camera has used, and the fix.
6. [Using an Android phone](docs/06-ANDROID.md): what a phone can do and what needs a computer.

Commands and mods:

7. [Command reference](docs/07-USAGE.md): every command, with examples.
8. [Photo frames](docs/08-FRAMES.md): your own frames from PNGs.
9. [Resource pack and boot logo](docs/09-RESOURCES.md): the resource pack, and a boot logo that fits.
10. [Photo dates (EXIF)](docs/10-EXIF.md): what `exif-fix` repairs on copies of your photos and videos.

Reference and background:

11. [Image layout](docs/11-FORMAT.md): what is where in the flash, settings fields, update-file rules.
12. [Boot and power notes](docs/12-NOTES-boot-and-power.md): the analysis behind `tweak` and `settings`.
13. [Research report](docs/13-PHASE1-REPORT.md): the sources, each claim labelled verified, reported or inferred.

## Tests

```
pip install -e ".[test]"
python -m pytest -q
CHARMERA_STOCK_IMAGE=/path/to/your_backup.bin python -m pytest -q   # also runs the checks against a real dump
```
