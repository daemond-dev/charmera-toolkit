# Thin wrapper: python -m charmera_toolkit backup, with every argument passed through.
#
#   .\scripts\backup.ps1 --out C:\charmera-backups --label mine
#
# Runs from any directory: the repository root is put on PYTHONPATH, so relative paths in the
# arguments stay relative to where you run it. Set $env:PYTHON to use a particular interpreter.
# Read-only: this never writes to the chip. Windows needs the WinUSB (or libusb-win32) driver on the
# CH341A, installed with Zadig, and a Windows build of flashrom (see docs/RECOVERY.md).
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
