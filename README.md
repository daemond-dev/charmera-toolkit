# Charmera toolkit

Tools for backing up, inspecting, verifying, recovering and modding the Kodak Charmera keychain camera
(original 2025 series, Generalplus GPCV2247F, 2 MiB Puya PY25D16HB SPI flash). Windows and Linux, Python 3.9+,
standard library only.

Status: every command is unit-tested against two public dumps and the published update files. Nothing has been
flashed to a camera yet; `docs/FLASH-CANDIDATES.md` lists the first files meant to be tried, with hashes and the
exact bytes they change.

```
pip install -e .
charmera --help
```

## Commands

| Command | Writes to | What it does |
|---|---|---|
| `inspect` | nothing | Identify a dump or update file: build, hashes, sounds, settings, stored photos. |
| `verify` | nothing | Gate an update file before it goes on a card: name checksum, stamp, size and erase rule, build, diff against stock. |
| `diff` | nothing | Compare two images region by region; explains settings and resource changes. |
| `sounds` | a file | List, extract, mute or replace the five UI sounds. |
| `settings` | a file | Show the record the camera will load, or write one with new auto-off, standby, volume values. |
| `tweak` | a file | Experimental start-up and power-off byte patches, each checked against the stock bytes. |
| `frames` | a file | List, extract, preview or replace the four photo frames (PNG in and out, budgets checked). |
| `pack` | a file | Turn an image into a correctly named update file, optionally with settings and photos blanked. |
| `card-check` / `card-fix` | nothing / the card | Diagnose and fix the exFAT dirty-bit problem that stops phones reading the card. |
| `exif-fix` | copies of photos | Repair the camera's broken EXIF dates and AVI dates on copies, never on the card. |
| `probe` / `backup` / `restore` | nothing / files / the chip | CH341A full-flash backup and restore through flashrom. |

Ground rules baked into the commands:

- Read-only by default. Nothing is written to a card, a chip or a camera without a typed confirmation.
- Anything that produces a flashable or modified image needs an explicit flag and prints its plan first.
- `pack` refuses an input it cannot identify as the original 2025 series, and `verify` fails any file whose
  erase footprint would reach beyond what the published update files touch.
- No Kodak or Reto firmware is distributed here. The tools work on your own backup; upstream files are referenced
  by URL and hash in `docs/PHASE1-REPORT.md`.

## Documentation

- `docs/FIRST-FLASH.md`: the first flash end to end, with the point of no return marked.
- `docs/USAGE.md`: the commands, with examples.
- `docs/FORMAT.md`: the image layout, settings fields, update-file rules, sound slots and frame format.
- `docs/RECOVERY.md`: the recovery decision tree (SD updater, CH341A, what is unknown).
- `docs/FLASH-CANDIDATES.md`: the first files to try on a camera and how they were reviewed.
- `docs/CARDS.md`: the exFAT dirty-bit problem and the FAT32 fix.
- `docs/EXIF.md`: what is wrong with the camera's EXIF and AVI dates and what `exif-fix` does.
- `docs/FRAMES.md`: the frame format, PNG requirements and what is still unverified about the overlay.
- `docs/NOTES-boot-and-power.md`: boot sequence, auto-off and standby analysis behind `tweak` and `settings`.
- `docs/PHASE1-REPORT.md`: the research this is built on, every claim labelled verified, reported or inferred.

## Tests

```
pip install -e ".[test]"
python -m pytest -q
CHARMERA_STOCK_IMAGE=/path/to/your_backup.bin python -m pytest -q   # also runs the checks against a real dump
```
