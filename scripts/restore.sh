#!/bin/sh
# Thin wrapper: python -m charmera_toolkit restore, with every argument passed through.
#
#   scripts/restore.sh --image ~/charmera-backups/mine_20260101-120000Z.bin --out ~/charmera-backups
#
# No backup of the chip on the clip, no write: restore reads the chip twice before it writes and
# keeps that read in --out (before-restore_<UTC time>.bin, a .sha256 and a .manifest.json, as
# backup.sh keeps them). That read is the way back if the image does not suit this camera. When the
# two reads differ it writes nothing. It shows the block diff against that read and the image's
# sensor table against the chip's, and when the tables differ it asks a second time: nothing here
# can tell whether that firmware fits this camera. flashrom writes a private copy of the checked
# image, never the file named in --image.
#
# Before the clip goes on (restore prints this list too; docs/02-RECOVERY.md, section B):
#   - a 3.3 V programmer: a blue CH341A jumpered to 3.3 V, a V1.7 CH341A switched to 3.3 V, a Pico
#     running pico-serprog or a CH347. Never an unmodified black CH341A board: its data lines run
#     at 5 V, which can damage the flash chip and the processor that shares its bus.
#   - the camera's battery lead lifted from its pad and taped.
#   - the clip's pin 1 (red stripe or "1") on the chip's dot.
#
# Runs from any directory: the repository root is put on PYTHONPATH, so relative paths in the
# arguments stay relative to where you run it. Set PYTHON to use a particular interpreter.
# This WRITES the chip after you type RESTORE. Read docs/02-RECOVERY.md first.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
PYTHONPATH="$root${PYTHONPATH:+:$PYTHONPATH}"
export PYTHONPATH
exec "${PYTHON:-python3}" -m charmera_toolkit restore "$@"
