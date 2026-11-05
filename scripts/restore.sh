#!/bin/sh
# Thin wrapper: python -m charmera_toolkit restore, with every argument passed through.
#
#   scripts/restore.sh --image ~/charmera-backups/mine_20260101-120000Z.bin --backup <backup of the chip as it is now>
#
# --backup is a fresh backup of the chip as it is now (make it with backup.sh just before), so the
# block diff shows exactly what the write will change. It is optional.
#
# Runs from any directory: the repository root is put on PYTHONPATH, so relative paths in the
# arguments stay relative to where you run it. Set PYTHON to use a particular interpreter.
# This WRITES the chip after you type RESTORE. Read docs/02-RECOVERY.md first: 3.3 V programmer,
# battery disconnected, your own backup made and checked.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
PYTHONPATH="$root${PYTHONPATH:+:$PYTHONPATH}"
export PYTHONPATH
exec "${PYTHON:-python3}" -m charmera_toolkit restore "$@"
