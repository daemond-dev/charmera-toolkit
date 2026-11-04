"""Command-line entry point. Each feature module exposes `register(subparsers)`; this file only wires them.

Modules are imported lazily so a broken optional module does not take the whole CLI down.
"""
from __future__ import annotations

import argparse
import importlib
import os
import sys

# (module name, one-line description). Order is the order shown in --help.
FEATURE_MODULES = [
    ("charmera_toolkit.inspect_cmd", "inspect: parse a dump or update file"),
    ("charmera_toolkit.verify", "verify: check an update file before it goes anywhere near a card"),
    ("charmera_toolkit.diff_cmd", "diff: compare two images region by region"),
    ("charmera_toolkit.pack", "pack: build an update file (output gated)"),
    ("charmera_toolkit.sounds", "sounds: list, extract, mute or replace the UI sounds"),
    ("charmera_toolkit.resources", "resources: list, extract or replace entries of the resource pack"),
    ("charmera_toolkit.frames", "frames: list, extract, preview or replace the four photo frames"),
    ("charmera_toolkit.settings_cmd", "settings: show or change the stored settings (auto-off, standby, volumes)"),
    ("charmera_toolkit.tweaks", "tweak: experimental start-up and power-off byte patches"),
    ("charmera_toolkit.stage", "stage: copy a verified update file to the root of the camera's SD card (asks first)"),
    ("charmera_toolkit.card", "card-check / card-fix: the exFAT dirty-bit and MBR type problem"),
    ("charmera_toolkit.exif", "exif-fix: repair EXIF dates and AVI dates on copies of your photos"),
    ("charmera_toolkit.flash", "backup / restore: CH341A full-flash backup and restore through flashrom"),
]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="charmera",
        description="Kodak Charmera firmware and card toolkit. Read-only commands never write; "
        "destructive commands ask first.",
    )
    sub = parser.add_subparsers(dest="command", metavar="command")
    for modname, _desc in FEATURE_MODULES:
        try:
            mod = importlib.import_module(modname)
        except Exception as exc:  # pragma: no cover - only when a module is missing or broken
            print(f"warning: {modname} unavailable: {exc}", file=sys.stderr)
            continue
        register = getattr(mod, "register", None)
        if register is not None:
            register(sub)
    return parser


def _safe_console() -> None:
    """Never let a file name the console cannot encode (Windows code pages, redirected output) abort a run."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            try:
                reconfigure(errors="backslashreplace")
            except (ValueError, OSError):  # pragma: no cover - closed or exotic streams
                pass


def main(argv: list[str] | None = None) -> int:
    _safe_console()
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "func", None):
        parser.print_help()
        return 2
    try:
        return int(args.func(args) or 0)
    except BrokenPipeError:  # output piped into `head` or a closed pager: not an error worth a traceback
        try:
            sys.stdout = open(os.devnull, "w")
        except OSError:
            pass
        return 1
    except OSError as exc:  # unreadable input, full disk, missing folder: say so in one line
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
