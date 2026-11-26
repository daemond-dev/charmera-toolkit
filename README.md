# Charmera toolkit

Custom firmware, backups and fixes for the Kodak Charmera keychain camera, from your phone or a computer.

Know what your camera runs before you flash. Charmeras come with two different image sensors, and firmware for one
can't drive the other. Get it wrong and the camera still boots, but it can't take pictures. The tools here tell you
which camera you have before anything gets written.

## What can it do?

- Flash custom firmware with extras the stock firmware doesn't have:
  - Volume and LED rows in the setup menu
  - real dates in the photos' EXIF
  - booting straight into the last mode you used
  - a quick black flash on screen when you take a shot
- Pick your own mix of those, or go back to stock. EXIF dates, boot to last mode and capture flash are for OV9732
  cameras (base v012) only. SP140A cameras get Volume/LED, and it's still experimental there.
- Tell which firmware a camera runs from the photos on its card, without opening it up.
- Back up and restore the camera's flash chip with a 3.3 V chip programmer (a CH341A board or a Pico), for when
  things go badly wrong. The camera has to be opened, and expect to solder thin wires to the chip's legs.
- Fix the broken dates on photos and videos (on copies, never on the card).
- Fix SD cards that Android phones refuse to read after the camera has used them.
- Build files with your own photo frames, boot logo, UI sounds, or auto-off and standby times. These haven't been
  tried on a camera yet, so treat them as experiments.

## Getting started

### On a phone

Get the [Charmera Card app](https://github.com/daemond-dev/charmera-toolkit/releases/download/android-app/charmera-card.apk)
(Android 7.0 or later). Plug the camera in with its USB-C cable and follow the app. It identifies the camera, builds
the firmware, puts it on the card and walks you through the flash. No computer needed.

More in [the app's README](android/README.md) and [Using an Android phone](docs/06-ANDROID.md).

Don't use app version 0.4. It was taken down on 2026-11-06 because it didn't refuse the withdrawn candidates A and B
([what they were](docs/03-FLASH-CANDIDATES.md)).

### On a computer

You need Python 3.9 or newer, on Windows, macOS or Linux. Nothing else to install. Download the repo (Code >
Download ZIP, or `git clone`) and in its folder run:

```
python -m pip install -e .
charmera --help
```

If `charmera` isn't found after the install, `python -m charmera_toolkit --help` runs the same tool. Every command
works that way.

Then follow [The first flash](docs/01-FIRST-FLASH.md). The short version is `identify`, `build`, `verify`, `stage`,
with examples in the [Command reference](docs/07-USAGE.md#on-a-computer-from-the-card-to-a-flash).

### Camera bricked or can't take pictures after a flash?

If it boots and shows its menus but freezes in photo or video mode, it has firmware for the other sensor. The SD
updater still runs, so flashing a build that fits its sensor from the card fixes it, without opening the camera.
Photos it took before the bad flash tell you the sensor (Identify), and so does the sensor-ID probe. See
[Recovery, section D](docs/02-RECOVERY.md#d-firmware-that-does-not-fit-the-camera).

If it doesn't boot at all, only a chip programmer brings it back: [Recovery, section B](docs/02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly).
If an update was cut off before 100%, start at [section C](docs/02-RECOVERY.md#c-an-interrupted-flash).

## Before you flash

- Know what your camera runs before you flash: a backup of its chip, or the Identify route (Identify this camera's
  firmware in the app, or `charmera identify` on a computer). Neither refuses if you skip it. The app and `stage` warn
  you and let you decide.
- Make a backup anyway if you can. It's the only way back to exactly what the camera ran, settings included, and the
  only file the checks can compare new firmware with. Without one, a camera that won't boot can still take the public
  stock file for its sensor through a chip programmer ([Recovery, section B](docs/02-RECOVERY.md#b-the-camera-does-not-boot-read-and-write-the-flash-chip-directly)).
  [The first flash](docs/01-FIRST-FLASH.md) shows how to read one.
- Charge the battery. A flash that gets cut off leaves a camera only a chip programmer can bring back.
- Flashing replaces the photos stored in the camera's own memory. Photos on the SD card are fine.

Without `--stock <your backup>`, `stage` shows a WARN line but still writes a file that passes. The app works the same
way, with a loud warning for any file this project didn't review. Both still refuse files that fail their checks, and
the app refuses the withdrawn candidates A and B and backups given as update files.

## Which firmware?

A base is the stock firmware of one build (v006, v008_test or v012). A mod is a change on top of it. In the app you pick
a base and tick its mods one by one (`charmera build` takes `--base` and `--mods`). What they make from the public dumps
is a reviewed file: this project built and checked that exact file, and `verify`, `stage` and the app know it by its
SHA-256. A file made any other way, from your own read for example, isn't one, and the checks treat it more strictly.

| Your camera | Base | Versions | Back to stock |
|---|---|---|---|
| OmniVision OV9732 sensor | v012 (its factory firmware) | v0.25 Volume/LED, v0.26 adds EXIF dates, v0.27 adds boot to last mode and capture flash | stock v012 (in the app, base v012 with no mods ticked), or `charmera build --base v012 --download` |
| SP140A sensor | v006 (public stock) | Volume/LED, experimental (tick it on base v006, or `--mods volume-led`) | the stock restore file: Advanced > Download the stock restore file (no backup needed), or Restore stock firmware with your backup. On a computer, `charmera build --base v006 --download` |
| OV9732 that was flashed with v006 | v012, or v006 with the OV9732 port | v0.25 to v0.27 as in the first row (v0.25 went onto a camera running v0.24 and works), or v0.23 (v006 changed to drive the OV9732) and v0.24 (adds Volume/LED) | stock v012, as in the first row. The stock restore file can't take pictures on this camera. |

Identify (in the app, or `charmera identify`) reads the photos on the card and says which firmware took them, which
tells you the sensor. Photos from before a bad flash still count. Photos taken while it ran our v0.21 to v0.24 look
like v006's, though the camera is an OV9732. On an empty card, take two or three photos first.
If the camera can't take any, the sensor-ID probe tells you (Identify the image sensor in the app, or
`charmera build --base v006 --mods sensor-probe --download`).

## Commands

| Command | Writes to | What it does |
|---|---|---|
| `identify` | nothing | Read a card's photos and say which firmware took them, and which base to build on. |
| `build` | a file | Make a firmware file from a base and the mods you pick. Same recipes as the app. |
| `verify` | nothing | Check an update file before it goes on a card. |
| `stage` | the card | Check the file again, copy it to the card and read it back. |
| `inspect` | nothing | Say what's in a dump or update file. |
| `diff` | nothing | Compare two images region by region. |
| `photo-id` | nothing | Say what a photo's header shows about the firmware that took it. |
| `sensor-read` | nothing | Read the sensor-ID probe's SENSOR.TXT. |
| `sounds` | a file | List, extract, mute or replace the five UI sounds. |
| `settings` | a file | Show or change auto-off, standby and volume. |
| `frames` | a file | List, preview or replace the four photo frames. |
| `resources` | a file | List or replace resources, like the boot logo. |
| `tweak` | a file | Experimental start-up and power-off patches. |
| `pack` | a file | Turn an image into a correctly named update file. |
| `exif-fix` | copies | Fix the camera's broken dates on copies of your photos and videos. |
| `card-check` / `card-fix` | nothing / the card | Find and fix the exFAT problem that stops phones reading the card. |
| `probe` / `backup` / `restore` | nothing / files / the chip | Back up and restore the whole flash chip with a 3.3 V chip programmer (CH341A, Pico or CH347). |

Nothing touches a card, chip or camera until you type a confirmation. The [Command reference](docs/07-USAGE.md) has
examples for all of them.

## Docs

Flashing:

1. [The first flash](docs/01-FIRST-FLASH.md). Start here.
2. [Recovery](docs/02-RECOVERY.md). Read it before you flash, and go there if a flash went wrong.
3. [Flash candidates](docs/03-FLASH-CANDIDATES.md). The three withdrawn files and what happened with them.
4. [Putting the file on the card](docs/04-STAGE.md). What `stage` does and what its errors mean.
5. [SD card problems](docs/05-CARDS.md). Why phones reject a card the camera has used.
6. [Using an Android phone](docs/06-ANDROID.md). Everything the app does.

Commands and mods:

7. [Command reference](docs/07-USAGE.md)
8. [Photo frames](docs/08-FRAMES.md)
9. [Resource pack and boot logo](docs/09-RESOURCES.md)
10. [Photo dates (EXIF)](docs/10-EXIF.md)

Under the hood:

11. [Image layout](docs/11-FORMAT.md). What's where in the flash.
12. [Boot and power notes](docs/12-NOTES-boot-and-power.md)
13. [Research report](docs/13-PHASE1-REPORT.md). The sources, each claim marked verified, reported or inferred.
14. [OV9732 repair scope](docs/14-OV9732-REPAIR-SCOPE.md). Why v006 can't drive an OV9732.
15. [OV9732 exposure, colour and white balance](docs/15-OV9732-AE-WB-FLASH-PORT.md)

How this started: [the first flash that went wrong](docs/FIRST-FLASH-INCIDENT.md). The camera's buttons and menus are
in [Camera UI](docs/CAMERA-UI.md).

## Running the tests

```
pip install -e ".[test]"
python -m pytest -q
```

That runs on synthetic images only. To run the tests on real firmware too, point these at the public files:

| Variable | File |
|---|---|
| `CHARMERA_STOCK_IMAGE` | Stock v006: `charmera-v006-2025-10-14-clean.bin` from [charmera-firmware-v006](https://github.com/the-dumping-grounds/charmera-firmware-v006). The original `charmera_stock_latest.bin` works too. |
| `CHARMERA_FACTORY_DUMP` | Factory v012: `charmera-v012-2026-01-12-clean.bin` from [charmera-firmware-v012](https://github.com/the-dumping-grounds/charmera-firmware-v012). |
| `CHARMERA_FILM_MODS_RESTORE` | The film-mods restore file, also `charmera-v008_test-2025-11-19.bin` in [charmera-firmware-v008_test](https://github.com/the-dumping-grounds/charmera-firmware-v008_test). |

CI downloads the same files at fixed commits and checks their SHA-256. The app has its own build and tests, covered
in [its README](android/README.md#build).

## Disclaimer

This project isn't affiliated with Kodak or with Reto, which makes the Charmera under licence from Kodak. No Kodak or
Reto firmware is distributed here. The tools work on your own backup, or on public files referenced by URL and hash.
Flashing firmware can leave a camera that only a chip programmer can fix, so you do it at your own risk.

Kodak is a trademark of Eastman Kodak Company.
