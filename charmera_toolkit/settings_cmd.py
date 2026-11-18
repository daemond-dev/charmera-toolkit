"""`settings`: show the camera's stored settings, or write an image whose current record has new values.

What the bytes mean comes from the firmware's own setters and the code that reads them (see settings.py
and docs/13-PHASE1-REPORT.md section 11). Only fields whose meaning is verified can be set here:

- ui-sound: 0 turns every event sound off, 1..8 is their volume (default 5).
- playback-volume: 0..8 (default 5).
- auto-off: the code the key scanner uses: 1 = 3 minutes, 2 = 5 minutes (default), n >= 3 = n minutes.
  The real timer rate has not been measured on a camera, so treat the minutes as nominal.
- standby: minutes of idle time before the speaker amplifier and audio are switched off (default 2).

The output is a full image, not an update file: pack and verify it next.
"""
from __future__ import annotations

from pathlib import Path
from typing import Dict, Optional

from . import settings as settings_mod
from .sounds import GATE_FLAG, _check_output, _write_mod

FIELDS: Dict[str, tuple] = {
    # option name: (offset, minimum, maximum, description)
    "ui_sound": (settings_mod.OFF_UI_SOUND, 0, 8, "UI sound level, 0 = off"),
    "playback_volume": (settings_mod.OFF_PLAYBACK_VOLUME, 0, 8, "playback volume"),
    "auto_off": (settings_mod.OFF_AUTO_OFF, 1, 60, "auto power-off code (1 = 3 min, 2 = 5 min, n >= 3 = n min)"),
    "standby": (settings_mod.OFF_STANDBY, 1, 60, "standby after n idle minutes"),
}


def describe_record(rec: settings_mod.SettingsRecord) -> str:
    lines = [f"record at {rec.offset:#x}, CRC {'valid' if rec.crc_ok else 'INVALID'}"]
    for off, name in sorted(settings_mod.FIELD_NAMES.items()):
        lines.append(f"  +{off:#04x} = {rec.raw[off]:3d}  {name}")
    return "\n".join(lines)


def apply_changes(image: bytes, changes: Dict[str, int], base: str = "current") -> bytes:
    """Return an image whose settings bank holds one record: the base record with the changes applied."""
    if base == "first":
        recs = [r for r in settings_mod.parse_settings(image) if r.crc_ok]
        if not recs:
            raise ValueError("no CRC-valid settings record in the image")
        rec = recs[0]
    else:
        rec = settings_mod.current_record(image)
    for key, value in changes.items():
        off, lo, hi, _ = FIELDS[key]
        if not lo <= value <= hi:
            raise ValueError(f"{key.replace('_', '-')} must be {lo}..{hi}")
        rec = rec.with_byte(off, value)
    return settings_mod.rewrite_store(image, rec)


def handler(args) -> int:
    src = Path(args.file)
    if not src.is_file():
        print(f"error: {src} is not a file")
        return 2
    image = src.read_bytes()
    if len(image) < settings_mod.BANK_END:
        print("error: the file does not contain the settings store (need a dump or an update file)")
        return 2
    if args.action == "show":
        recs = settings_mod.parse_settings(image)
        print(f"{len(recs)} records in the bank, {sum(1 for r in recs if r.crc_ok)} CRC-valid")
        try:
            cur = settings_mod.current_record(image)
            print("the camera will load this one:")
            print(describe_record(cur))
        except ValueError as exc:
            print(f"the camera would apply defaults: {exc}")
        if args.all:
            for r in recs:
                print(describe_record(r))
        return 0
    changes = {k: getattr(args, k) for k in FIELDS if getattr(args, k) is not None}
    if not changes:
        print("error: nothing to change; give at least one of " + ", ".join("--" + k.replace("_", "-") for k in FIELDS))
        return 2
    if not args.out:
        print("error: --out is required for set")
        return 2
    out = Path(args.out)
    problem = _check_output(src, out, args.overwrite)
    if problem:
        print(f"error: {problem}")
        return 2
    try:
        new = apply_changes(image, changes, args.base_record)
    except ValueError as exc:
        print(f"error: {exc}")
        return 2
    what = ", ".join(f"{k.replace('_', '-')} = {v}" for k, v in changes.items()) + f" (from the {args.base_record} record)"
    return _write_mod(args, new, out, what)


def register(subparsers) -> None:
    p = subparsers.add_parser("settings", help="show the stored settings, or write an image with changed values")
    p.add_argument("action", choices=["show", "set"])
    p.add_argument("file", help="a 2 MiB dump or a 0x1E0000 update file")
    p.add_argument("--all", action="store_true", help="show: print every record in the bank")
    p.add_argument("--out", help="set: output image path (never named JH_*.bin, never in a folder that holds DCIM or a JH_*.bin)")
    p.add_argument("--base-record", choices=["current", "first"], default="current",
                   help="set: start from the record the camera would load, or from the first (factory) record")
    for key, (_off, lo, hi, desc) in FIELDS.items():
        p.add_argument("--" + key.replace("_", "-"), type=int, default=None, metavar=f"{lo}..{hi}", help=f"set: {desc}")
    p.add_argument("--overwrite", action="store_true", help="allow --out to replace an existing file (never the input)")
    p.add_argument(GATE_FLAG, dest="gate", action="store_true", help="actually write the modified image")
    p.set_defaults(func=handler)
