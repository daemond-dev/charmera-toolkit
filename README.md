# Charmera toolkit

Tools for backing up, inspecting, verifying, recovering and modding the Kodak Charmera keychain camera
(original 2025 series, Generalplus GPCV2247F, 2 MiB Puya PY25D16HB SPI flash). Windows and Linux, Python 3.9+,
standard library only.

Status: every command is tested on synthetic images, and on real firmware when the public files are given
([Tests](#tests) says which). One camera has been flashed with files from the public dump, and it did not fit: the
camera boots but cannot take pictures ([what happened](docs/FIRST-FLASH-INCIDENT.md)). The rule that follows: **no
backup of your own camera, no flash.** The app holds to it from version 0.5, which is not published yet (below); on a
computer `stage` warns and leaves the stop to you (the [ground rules](#commands) below say exactly what each command
does). [Flash candidates](docs/03-FLASH-CANDIDATES.md) records the withdrawn files and what the flashes showed.

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
| `pack` | a file | Turn an image into a correctly named update file (0x1E0000 bytes, or 0x169000 like the film-mods files), optionally with settings and photos blanked. |
| `stage` | the card | Re-verify an update file against your backup, copy it to the card root and read it back. |
| `card-check` / `card-fix` | nothing / the card | Diagnose and fix the exFAT dirty-bit problem that stops phones reading the card. |
| `exif-fix` | copies of photos | Repair the camera's broken EXIF dates and AVI dates on copies, never on the card. |
| `photo-id` | nothing | Say which kind of firmware wrote a photo, from its header alone: a different header shows a different build. |
| `probe` / `backup` / `restore` | nothing / files / the chip | CH341A full-flash backup and restore through flashrom. |

On a phone, the [Charmera Card app](android/README.md) does the card part with no computer: it makes the published
stock restore file from the public stock dump, compares it with a dump of your own camera's chip, and puts it on the
camera's card over the USB-C cable; without that dump it writes nothing. That is version 0.5, which has not been
published yet: the APK behind the download link is still 0.4, which has no backup step and does not refuse the
withdrawn candidates C, A and B, so do not flash with it. 0.5 is the release titled "Charmera Card for Android 0.5",
and its screen opens with "Before you start" and "No backup of this camera, no flash", then a step "0. Back up this
camera first" ([download the APK](https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk);
the repository is private, so sign in to GitHub in the phone's browser first). The app cannot tell whose chip a dump
came from: it refuses only the two public stock dumps, so give it only a dump read from this camera with a clip.

Ground rules, and what each command does about them:

- No backup of your own camera, no flash. Whether a firmware fits a camera cannot be told from the file, the version
  stamp or the body. A dump of the camera's own chip read before it was ever flashed is the one file known to fit it;
  any read of the chip is the way back to what it held when it was read, but a read taken after a flash shows what the
  camera runs now, not what fits it. `verify --stock <that dump>` is the one check that compares a file with your
  camera, and `--stock` must be a complete read of a chip (exactly 2 MiB, starting with `SPII`, with the build string
  and the stamp). The app refuses to write without a backup on record. `stage` does not refuse: without `--stock` it
  shows a WARN line saying that no backup of this camera was given, so nothing compares the file with its own firmware
  and a file that does not fit cannot be undone, says the same in its plan, and still writes a file that passes (the
  owner's decision of 2026-11-06). Give it `--stock` every time.
- Read-only by default. Nothing is written to a card, a chip or a camera without a typed confirmation: `stage`, the
  one command that puts a file on a card, and `card-fix` ask you to type the target back, and `restore` waits for the
  word RESTORE. `restore` also reads the chip twice before it writes, keeps that read in its `--out` folder as a
  backup, and has flashrom write a private copy of the bytes it checked. `pack` refuses an `--out` that is a drive
  root, a mount point or on removable media, or that holds a DCIM folder or another `JH_*.bin`. The mod commands
  (`sounds`, `settings set`, `tweak`, `frames` and `resources`) refuse an `--out` named like an update file
  (`JH_*.bin`, in any letter case, also with trailing dots or spaces) and an `--out` in a folder that holds a DCIM
  folder or a `JH_*.bin`.
- Anything that produces a flashable or modified image needs an explicit flag and prints its plan first.
- `pack` refuses an input without the Charmera build marks. `verify` names a build by a fingerprint over its bytes
  (never by the version stamp, which two different builds share) and says what a match does and does not prove. It
  accepts only the two update sizes in use, 0x1E0000 and 0x169000 bytes: any other size is a FAIL unless
  `--allow-odd-size` is given, and `stage` never gives it. It names the block the updater's final erase hits.
- No Kodak or Reto firmware is distributed here. The tools work on your own backup; upstream files are referenced
  by URL and hash in the [Research report](docs/13-PHASE1-REPORT.md).

## Documentation

In reading order; the [docs index](docs/README.md) has the same list. Each document links to the previous and
next one, and to the others where it mentions them.

Before and during a flash:

1. [The first flash](docs/01-FIRST-FLASH.md): start here; the whole path, with the point of no return marked.
2. [Recovery](docs/02-RECOVERY.md): read before you flash; the SD updater and CH341A routes back, firmware that does
   not fit the camera, and the donor route.
3. [Flash candidates](docs/03-FLASH-CANDIDATES.md): the three withdrawn files, their hashes, what each changed and what
   the flashes on one camera showed.
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
CHARMERA_STOCK_IMAGE=/path/to/charmera_stock_latest.bin python -m pytest -q   # also runs the tests on real firmware
```

Without the variables the tests use synthetic images only, and every test on real firmware is skipped. Two public
files can be given, and CI's tests workflow downloads both from upstream at fixed addresses and checks their SHA-256:

- `CHARMERA_STOCK_IMAGE`: the public stock dump, `dumps/charmera_stock_latest.bin` from benbadio/keychain-camera-plus
  at commit 209c25a (SHA-256 `cc3b4b2f…`). The image tests in `test_core`, `test_stage`, `test_frames`,
  `test_resources`, `test_exif` and `test_android` run on it and expect what that dump holds (the public build's
  fingerprints, its frames, resources and stored photos), and all of `test_preflight` rebuilds the candidates and the
  stock restore file from it, compares them with the hashes in [Flash candidates](docs/03-FLASH-CANDIDATES.md), and
  checks that padded, cut-off or damaged files are refused before a card. Point it at that dump, not at your own
  backup: a backup of another build fails them, which is no fault of the toolkit, and a backup read after the camera
  was flashed with the public restore file passes them, which says only that the camera now runs the public build,
  not that it fits.
- `CHARMERA_FILM_MODS_RESTORE`: the film-mods project's public restore file (release Charmera_Restore,
  `JH_5408202505201029_0852C329.bin`, SHA-256 `5b352e46…`), for the tests that name that build (`test_core`,
  `test_android` and the app's `UpdateCheckTest` and `CandidatesTest`). CI goes on without it, with a warning, when it
  cannot be fetched.

No test uses the second public dump (Project-Sirius). The app's tests run on the JVM and read `CHARMERA_UPDATE_FILES`
as well, which CI does not set ([the app's Build section](android/README.md#build)).
