# Thin wrapper: python -m charmera_toolkit backup, with every argument passed through.
#
#   .\scripts\backup.ps1 --out C:\charmera-backups --label mine
#
# Before the clip goes on (backup prints this list too; docs/02-RECOVERY.md, section B):
#   - a 3.3 V programmer: a blue CH341A jumpered to 3.3 V, a V1.7 CH341A switched to 3.3 V, a Pico
#     running pico-serprog or a CH347. Never an unmodified black CH341A board: its data lines run
#     at 5 V, which can damage the flash chip and the processor that shares its bus.
#   - the camera's battery lead lifted from its pad and taped.
#   - the clip's pin 1 (red stripe or "1") on the chip's dot.
#
# The backup is <label>_<UTC time>.bin with a .sha256 and a .manifest.json, kept only when two reads
# are identical. A single read (--reads 1), and reads that differ, keep the name
# <label>_<UTC time>.unverified-read<n>.bin: they are not a backup.
#
# Runs from any directory: the repository root is put on PYTHONPATH, so relative paths in the
# arguments stay relative to where you run it. Set $env:PYTHON to use a particular interpreter.
# Read-only: this never writes to the chip. Windows needs the WinUSB (or libusb-win32) driver on the
# CH341A, installed with Zadig, and a Windows build of flashrom (see docs/02-RECOVERY.md).
$root = Split-Path -Parent $PSScriptRoot
if ($env:PYTHONPATH) { $env:PYTHONPATH = "$root;$env:PYTHONPATH" } else { $env:PYTHONPATH = $root }
$python = if ($env:PYTHON) { $env:PYTHON } else { "python" }
# Resolve the interpreter first: a missing one is a terminating error here, not a silent exit 0.
$interpreter = (Get-Command $python -ErrorAction Stop).Source
# Preset, so a launch that fails before Python runs cannot leave the exit code at 0.
$LASTEXITCODE = 1
& $interpreter -m charmera_toolkit backup @args
if (-not $?) { if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } else { exit 1 } }
exit $LASTEXITCODE
