# Thin wrapper: python -m charmera_toolkit restore, with every argument passed through.
#
#   .\scripts\restore.ps1 --image C:\charmera-backups\mine_20260101-120000Z.bin --backup <backup of the chip as it is now>
#
# --backup is a fresh backup of the chip as it is now (make it with backup.ps1 just before), so the
# block diff shows exactly what the write will change. It is optional.
#
# Runs from any directory: the repository root is put on PYTHONPATH, so relative paths in the
# arguments stay relative to where you run it. Set $env:PYTHON to use a particular interpreter.
# This WRITES the chip after you type RESTORE. Read docs/02-RECOVERY.md first: 3.3 V programmer,
# battery disconnected, your own backup made and checked.
$root = Split-Path -Parent $PSScriptRoot
if ($env:PYTHONPATH) { $env:PYTHONPATH = "$root;$env:PYTHONPATH" } else { $env:PYTHONPATH = $root }
$python = if ($env:PYTHON) { $env:PYTHON } else { "python" }
# Resolve the interpreter first: a missing one is a terminating error here, not a silent exit 0.
$interpreter = (Get-Command $python -ErrorAction Stop).Source
# Preset, so a launch that fails before Python runs cannot leave the exit code at 0.
$LASTEXITCODE = 1
& $interpreter -m charmera_toolkit restore @args
if (-not $?) { if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } else { exit 1 } }
exit $LASTEXITCODE
