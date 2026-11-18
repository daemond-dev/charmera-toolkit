"""charmera build: make a firmware update file from a base (the stock firmware of one build) and the mods chosen.

The same recipes the Android app builds from (recipes.json). The result is written only when it is the reviewed file
listed for that combination, so a file this command writes is byte for byte one the project generated and published
by SHA-256. It is then checked with verify, as any update file is.

  charmera build --list
  charmera build --base v012 --mods volume-led,exif --download --out DIR
  charmera build --dump charmera_flash_....bin --mods volume-led --out DIR
"""
from __future__ import annotations

import argparse
import http.client
import os
import shlex
import subprocess
import sys
import urllib.request
from pathlib import Path
from typing import Optional

from . import recipes as R

MAX_DOWNLOAD = 0x400000


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "charmera-toolkit"})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read(MAX_DOWNLOAD + 1)
    if len(data) > MAX_DOWNLOAD:
        raise R.RecipeError(f"{url} sent more than {MAX_DOWNLOAD} bytes: not a dump")
    return data


def quote(path: str) -> str:
    """A path as the user's shell takes it when pasted back."""
    return subprocess.list2cmdline([path]) if os.name == "nt" else shlex.quote(path)


def public_dump(doc: dict, base: str) -> dict:
    """The dump --download takes for a base: its cleaned copy, else its only public copy."""
    cands = [d for d in doc["dumps"] if d["build"] == base and d.get("url")]
    cands.sort(key=lambda d: d["copy"] != "clean")
    if not cands:
        raise R.RecipeError(f"no public dump of base {base} to download")
    return cands[0]


def print_list(doc: dict) -> None:
    for base, sets in R.sets_by_base(doc).items():
        dumps = [d for d in doc["dumps"] if d["build"] == base]
        print(f"base {base}")
        for d in dumps:
            where = "downloads with --download" if d.get("url") and d["copy"] == "clean" else (
                "public" if d.get("url") else "private: give it with --dump")
            print(f"  dump  {d['id']:14s} {d['sha256'][:16]}…  {where}")
        for m in R.mods_for(base, doc):
            flag = "" if m["status"] == "tested" else "  [experimental]"
            print(f"  mod   {m['name']:14s} {m['title']}{flag}")
            print(f"        {m['description']}")
        for s in sets:
            mods = ",".join(i.split("/")[-1] for i in s["mods"]) or "(none)"
            flag = "" if s["status"] == "tested" else "  [experimental]"
            print(f"  set   --mods {mods:44s} {s['label']}{flag}")
        print()


def handler(args) -> int:
    try:
        doc = R.load()
    except (OSError, ValueError) as exc:
        print(f"error: cannot read the recipes: {exc}")
        return 2
    if args.list:
        print_list(doc)
        return 0
    try:
        if args.dump:
            dump = Path(args.dump).read_bytes()
            entry = R.dump_for(dump, doc)
            if args.base and args.base != entry["build"]:
                print(f"error: --dump is base {entry['build']}, not {args.base}")
                return 2
            got = f"{args.dump} ({entry['id']})"
        elif args.download:
            if not args.base:
                print("error: --download needs --base (v006, v008_test or v012)")
                return 2
            entry = public_dump(doc, args.base)
            print(f"downloading {entry['id']} from {entry['url']}")
            dump = fetch(entry["url"])
            if R.sha256(dump) != entry["sha256"]:
                print(f"error: the download is not {entry['id']} (SHA-256 {R.sha256(dump)}); nothing written. A network "
                      "that asks you to sign in, or a cut-off download, can cause this.")
                return 1
            got = f"{entry['id']}, downloaded and checked"
        else:
            print("error: give --dump FILE or --download (with --base), or --list")
            return 2
        made = R.make(dump, (args.mods or "").split(","), doc)
    except R.RecipeError as exc:
        print(f"error: {exc}")
        return 1
    except (OSError, http.client.HTTPException) as exc:
        print(f"error: {exc}" + (" (the download was cut off or refused: try again)" if args.download else ""))
        return 2

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    dest = out_dir / made.name
    if dest.exists() and dest.read_bytes() != made.data:
        print(f"error: {dest} exists with other contents; nothing written")
        return 1
    dest.write_bytes(made.data)
    print(f"built  {made.label}")
    print(f"from   {got}")
    print(f"file   {dest}")
    print(f"sha256 {made.sha256}  (the reviewed file: recipes.json)")
    if made.status != "tested":
        print("WARN   this combination is experimental: built and checked, but not yet tried on a camera. Keep the file "
              "the camera runs now, to flash it back if something is wrong.")
    if args.no_verify:
        return 0
    from . import verify
    checks = verify.verify_update(dest, made.data)
    failed = [c for c in checks if not c.ok and c.severity == "fail"]
    warned = [c for c in checks if c.severity == "warn"]
    print(f"verify {len(failed)} failure(s), {len(warned)} warning(s)" + ("" if not failed else ":"))
    for c in failed:
        print(f"  FAIL {c.name}: {c.detail}")
    if failed:
        return 1
    shown = quote(str(dest))
    print(f"next   charmera verify {shown} --stock <your backup>   (all the lines; a backup compares it with your camera)")
    print(f"       charmera stage {shown} --card <the camera's card> --stock <your backup>")
    return 0


def register(subparsers) -> None:
    p = subparsers.add_parser(
        "build",
        help="make a firmware update file from a base and chosen mods (the app's recipes)",
        description=(
            "Make a firmware update file from a base, the stock firmware of one build (v006 and v008_test for SP140A "
            "cameras, v012 for OV9732 cameras: charmera identify says which a camera runs), and the mods chosen. "
            "The file is written only when it is the reviewed one listed for that combination."),
        epilog=("charmera build --list shows every base, mod and combination. --download fetches the base's public "
                "cleaned copy from the-dumping-grounds' repositories at a fixed commit and checks its SHA-256; "
                "--dump takes a dump you have (your own read, or a public copy). Experimental combinations are built "
                "with a warning."),
    )
    p.add_argument("--list", action="store_true", help="show the bases, mods and combinations, and exit")
    p.add_argument("--base", choices=("v006", "v008_test", "v012"), help="the base (needed with --download)")
    p.add_argument("--mods", default="", help="comma-separated mod names, e.g. volume-led,exif (none: stock)")
    src = p.add_mutually_exclusive_group()
    src.add_argument("--dump", help="the dump to build from")
    src.add_argument("--download", action="store_true", help="download the base's public dump")
    p.add_argument("--out", default=".", help="folder to write the update file to (default: here)")
    p.add_argument("--no-verify", action="store_true", help="do not run verify on the result")
    p.set_defaults(func=handler)
