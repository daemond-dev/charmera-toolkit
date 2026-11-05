"""`pack`: turn an image into an update file the camera will accept, with the owner's eyes on it first.

What the updater does with the file (VERIFIED from the SDK source and hardware reports): reads it fully
into RAM, sums every byte and compares with the filename, then erases 64 KiB blocks from offset 0 and
programs the file byte for byte. The published update size is 0x1E0000 bytes (30 blocks); the last two
blocks of flash are blank on every known camera, so trimming a 2 MiB dump to 0x1E0000 loses nothing.
Never feed the camera a full 2 MiB file (see verify).

Without the gate flag this command only prints what it would write.
"""
from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Optional

from . import settings as settings_mod
from .checksum import FLASH_SIZE, UPDATE_FILE_SIZE, build_update_name, now_stamp
from .image import (PHOTO_INDEX, SETTINGS_BASE, SOFTWARE_END, USER_AREA_END, detect_build, image_kind, resource_directory,
                    stored_photos)
from .stage import list_update_files
from .verify import resource_problems

GATE_FLAG = "--i-understand-this-file-flashes-a-camera"


def prepare_payload(image: bytes, blank_settings: bool = False, blank_photos: bool = False,
                    factory_settings: bool = False) -> bytes:
    """Trim to 0x1E0000 and optionally reset the camera-specific areas.

    Inputs: a 2 MiB dump (trimmed; its last 0x20000 bytes must be blank), a 0x1E0000 update file (as it is),
    or a software-only image of exactly 0x170000 bytes (padded with an erased settings bank, a zeroed photo
    directory and 0xFF). Any other length below 0x1E0000 is refused as truncated: padding a cut-off file
    would put 0xFF over the end of the code or the resource pack, and the camera checks neither.

    factory_settings keeps only the first CRC-valid settings record (the factory record on every known
    dump), moved to sector 0, and erases the rest of the bank, so the firmware's normal load path picks it
    rather than its defaults fallback. It zeroes the photo directory only when that sector is erased; the
    directory and the stored photos are otherwise kept, so add blank_photos to remove another owner's
    photos. blank_settings erases the whole bank instead (defaults path, verified statically).
    """
    kind = image_kind(image)
    if kind == "truncated":
        raise ValueError(
            f"the input is {len(image)} bytes ({len(image):#x}): it looks truncated (a cut-off download or an "
            f"incomplete read). pack takes a 2 MiB dump, a {UPDATE_FILE_SIZE:#x}-byte update file, or a software-only "
            f"image of exactly {SOFTWARE_END:#x} bytes; padding anything shorter would leave part of the code or the "
            "resource pack erased. Get a complete copy and run pack again.")
    if kind == "dump":
        tail = image[USER_AREA_END:]
        if tail != b"\xff" * len(tail):
            raise ValueError("bytes 0x1E0000..0x200000 are not blank in this dump; refusing to trim them away")
        payload = bytearray(image[:UPDATE_FILE_SIZE])
    elif kind == "update":
        payload = bytearray(image)
    elif kind == "software-only":
        payload = bytearray(image) + b"\xff" * (UPDATE_FILE_SIZE - len(image))
        blank_settings = True
        blank_photos = True
    else:
        raise ValueError(f"unsupported input size {len(image)} ({len(image):#x}); expected a 2 MiB dump, a "
                         f"{UPDATE_FILE_SIZE:#x}-byte update file or a {SOFTWARE_END:#x}-byte software-only image")
    if factory_settings:
        recs = [r for r in settings_mod.parse_settings(bytes(payload)) if r.crc_ok]
        if not recs:
            raise ValueError("no CRC-valid settings record to keep as the factory record")
        payload = bytearray(settings_mod.rewrite_store(bytes(payload), recs[0]))
    if blank_settings:
        payload[SETTINGS_BASE: settings_mod.SETTINGS_END] = b"\xff" * (settings_mod.SETTINGS_END - SETTINGS_BASE)
    if blank_photos:
        # The firmware's own "delete all" leaves a zeroed directory sector; an 0xFF directory would decode
        # as two used entries of length 0xFFFFFFFF, so write zeros there and erase only the photo data.
        payload[PHOTO_INDEX: PHOTO_INDEX + settings_mod.PHOTO_DIRECTORY_LEN] = b"\x00" * settings_mod.PHOTO_DIRECTORY_LEN
        payload[PHOTO_INDEX + settings_mod.PHOTO_DIRECTORY_LEN: UPDATE_FILE_SIZE] = b"\xff" * (UPDATE_FILE_SIZE - PHOTO_INDEX - settings_mod.PHOTO_DIRECTORY_LEN)
    if not settings_mod.photo_directory_consistent(bytes(payload)):
        payload[PHOTO_INDEX: PHOTO_INDEX + settings_mod.PHOTO_DIRECTORY_LEN] = b"\x00" * settings_mod.PHOTO_DIRECTORY_LEN
    return bytes(payload)


def describe_input(input_len: int) -> str:
    """What pack did to the input's length: trimmed, kept or padded, and with what."""
    if input_len == FLASH_SIZE:
        return (f"2 MiB dump, trimmed to {UPDATE_FILE_SIZE:#x} (the {FLASH_SIZE - UPDATE_FILE_SIZE:,} bytes "
                f"{UPDATE_FILE_SIZE:#x}..{FLASH_SIZE:#x} dropped are blank)")
    if input_len == UPDATE_FILE_SIZE:
        return f"{UPDATE_FILE_SIZE:#x}-byte update file, length kept"
    if input_len == SOFTWARE_END:
        return (f"{input_len:#x}-byte software-only image, PADDED with {UPDATE_FILE_SIZE - input_len:,} bytes "
                f"({input_len:#x}..{UPDATE_FILE_SIZE:#x}): settings bank erased, photo directory zeroed, the rest 0xFF")
    return f"{input_len:,} bytes"


def describe(payload: bytes, stamp: str, input_len: Optional[int] = None) -> str:
    name = build_update_name(payload, stamp)
    build = detect_build(payload)
    lines = [f"input:           {describe_input(input_len)}"] if input_len is not None else []
    lines += [
        f"filename:        {name.filename}",
        f"size:            {len(payload)} bytes ({len(payload) // 0x10000} blocks of 64 KiB)",
        f"byte sum:        {name.checksum:08X}",
        f"sha256:          {hashlib.sha256(payload).hexdigest()}",
        f"series:          {build.series} ({build.build.label if build.build else 'unknown build'})",
        f"software region: {'identical to stock' if build.checks.get('software_sha256') else 'MODIFIED or unknown'}",
    ]
    entries = resource_directory(payload)
    problems = resource_problems(payload, entries) if entries else ["no GP+ resource pack"]
    if problems:
        lines.append(f"resources:       DAMAGED, {len(problems)} problem(s), verify will refuse this file: " + "; ".join(problems[:3])
                     + ("; ..." if len(problems) > 3 else ""))
    else:
        lines.append(f"resources:       {len(entries)} entries, each holds the kind its name says")
    recs = settings_mod.parse_settings(payload)
    if not recs:
        lines.append("settings:        blank (camera will create defaults)")
    else:
        try:
            cur = settings_mod.current_record(payload)
            lines.append(f"settings:        {len(recs)} records; the camera will load the one at {cur.offset:#x}: UI sound {cur.ui_sound}, playback volume {cur.playback_volume}")
        except ValueError as exc:
            lines.append(f"settings:        {len(recs)} records; the camera would apply defaults ({exc})")
    photos = stored_photos(payload)
    lines.append(f"stored photos:   {len(photos)}")
    return "\n".join(lines)


def handler(args) -> int:
    src = Path(args.input)
    if not src.is_file():
        print(f"error: {src} is not a file")
        return 2
    image = src.read_bytes()
    try:
        payload = prepare_payload(image, args.blank_settings, args.blank_photos, args.factory_settings)
    except ValueError as exc:
        print(f"error: {exc}")
        return 2
    build = detect_build(payload)
    if build.build is None and not args.allow_unknown_build:
        print("error: this image carries no known build stamps, so the toolkit cannot tell which camera series it is for.")
        print("       Refusing to pack it. Use --allow-unknown-build only for a dump taken from the camera it will go back into.")
        return 2
    stamp = args.stamp or now_stamp()
    try:
        name = build_update_name(payload, stamp)
    except ValueError as exc:
        print(f"error: {exc}")
        return 2
    text = describe(payload, stamp, len(image))
    print(text)
    print()
    entries = resource_directory(payload)
    if not entries or resource_problems(payload, entries):
        print("error: the resource pack is damaged (see the resources line above), so verify and stage would refuse this")
        print("       file and the camera might not start with it. Refusing to pack it; build from a complete dump.")
        return 2
    if not args.gate:
        print(f"dry run: nothing written. Re-run with {GATE_FLAG} to write the file into --out.")
        return 0
    out_dir = Path(args.out)
    out = out_dir / name.filename
    if out_dir.exists() and not out_dir.is_dir():
        print(f"error: --out must be a directory; pack names the file itself (it would write {out_dir}/{name.filename})")
        return 2
    if out_dir.is_dir():
        try:
            present, has_dcim = list_update_files(out_dir)
        except OSError as exc:
            print(f"error: cannot list {out_dir}: {exc}")
            return 2
        if has_dcim:
            print(f"error: {out_dir} has a DCIM folder, so it looks like a camera card. pack never writes to a card: write the")
            print("       file to a folder on the computer, run `charmera verify` on it, then put it on the card with `charmera stage`,")
            print("       which checks the file again, refuses a second update file and reads the copy back.")
            return 2
        others = [f for f in present if f.name.upper() != name.filename.upper()]
        if others:
            print(f"error: {out_dir} already holds another update file: {', '.join(f.name for f in others)}.")
            print("       Two update files side by side is how the wrong one ends up on a card. Move or delete it, or choose")
            print("       another --out (one folder per update file).")
            return 2
    if out.exists() and not args.overwrite:
        print(f"error: {out} already exists; remove it, choose another --out, or pass --overwrite")
        return 2
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
        out.write_bytes(payload)
        (out_dir / (name.filename + ".sha256")).write_text(f"{hashlib.sha256(payload).hexdigest()}  {name.filename}\n", encoding="utf-8")
        (out_dir / (name.filename + ".MANIFEST.txt")).write_text(text + "\n", encoding="utf-8")
    except OSError as exc:
        print(f"error: cannot write into {out_dir}: {exc}")
        return 2
    print(f"wrote {out}")
    print("Before it touches a card: run `charmera verify` on it, and make sure you have a verified full-flash backup.")
    return 0


def register(subparsers) -> None:
    p = subparsers.add_parser("pack", help="build an update file from a dump or image (prints a plan unless gated)")
    p.add_argument("--input", required=True,
                   help="2 MiB dump, 0x1E0000 update file, or software-only image of exactly 0x170000 bytes (any other shorter file is refused as truncated)")
    p.add_argument("--out", default="out",
                   help="output directory on the computer; refused if it holds a DCIM folder (a card: use stage) or another JH_*.bin")
    p.add_argument("--stamp", help="build stamp of 12 ASCII digits 0-9, YYYYMMDDhhmm (default: now, UTC); must differ from the stock stamp")
    p.add_argument("--factory-settings", action="store_true",
                   help="keep only the first CRC-valid (factory) settings record and erase the rest of the bank; the photo directory "
                        "is zeroed only if it is erased, and stored photos are kept (add --blank-photos to remove them)")
    p.add_argument("--blank-settings", action="store_true", help="erase the settings store so the camera recreates defaults (verified in code, not on hardware)")
    p.add_argument("--blank-photos", action="store_true", help="erase the internally stored photos and their index")
    p.add_argument("--allow-unknown-build", action="store_true")
    p.add_argument("--overwrite", action="store_true", help="replace an existing output file of the same name")
    p.add_argument(GATE_FLAG, dest="gate", action="store_true", help="actually write the update file")
    p.set_defaults(func=handler)
