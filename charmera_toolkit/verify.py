"""`verify`: decide whether an update file is safe to put on a card. Read-only.

A file passes only if every check below passes. The checks encode what is known about the camera's
updater (VERIFIED: filename-driven byte sum, no other validation; erases and programs whole 64 KiB blocks
from offset 0) and about the build (the original 2025 series). Anything unknown fails closed.

--allow-modified accepts a software region that differs from stock (any mod of the code or the resources
needs it; settings-only files pass without it; stage always passes it). It does not accept a changed image-sensor table or a damaged resource entry: the first needs
--allow-unknown-build, which stage never passes, and the second has no override.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

from . import frames as frames_mod
from . import settings as settings_mod
from .checksum import BLOCK_SIZE, STOCK_TIMESTAMP, UPDATE_FILE_SIZE, byte_sum, parse_update_name
from .image import (ORIGINAL_2025, RESOURCE_BASE, RESOURCE_LIMIT, SETTINGS_BASE, SOFTWARE_END, SPII_MAGIC, ResourceEntry,
                    describe_erase_target, detect_build, resource_directory, sound_slots, stored_photos, updater_extra_erase)


@dataclass
class Check:
    name: str
    ok: bool
    detail: str
    severity: str = "fail"   # fail | warn | info


# --- Resource entries ------------------------------------------------------------------------------------------
#
# The camera checks only the pack's magic, nothing inside it (no checksum, docs/13-PHASE1-REPORT.md), so a cut-off
# or damaged pack is flashed as it is. POWER_ON_LOGO.JPG is opened at every boot before the card is mounted
# (docs/12-NOTES-boot-and-power.md), so an erased or broken logo can leave a camera that the SD updater cannot
# reach. Each entry is therefore checked against the kind its name says it is, inside the slot it owns.

DIRECTORY_START = RESOURCE_BASE + 0x18
DIRECTORY_ENTRY = 24
MAX_LISTED_PROBLEMS = 8


def resource_slots(image: bytes, entries: List[ResourceEntry]) -> List[tuple]:
    """(entry, start, end) for every directory entry: an entry owns the bytes up to the next entry's offset;
    the last one up to the factory self-test sector at 0x16E000."""
    out = []
    for i, e in enumerate(entries):
        end = entries[i + 1].offset if i + 1 < len(entries) else RESOURCE_LIMIT
        out.append((e, e.offset, end))
    return out


def _wav_problem(slot: bytes) -> Optional[str]:
    if len(slot) < 12 or slot[:4] != b"RIFF" or slot[8:12] != b"WAVE":
        return f"is not a RIFF WAVE (starts {slot[:4].hex(' ')})"
    total = 8 + struct.unpack_from("<I", slot, 4)[0]
    if total > len(slot):
        return f"RIFF size says {total} B but the slot holds {len(slot)} B"
    p = 12
    fmt = data = False
    while p + 8 <= total:
        cid = slot[p: p + 4]
        csz = struct.unpack_from("<I", slot, p + 4)[0]
        if cid == b"fmt " and csz >= 16:
            fmt = True
        elif cid == b"data":
            data = True
            break
        p += 8 + csz + (csz & 1)
    if not (fmt and data):
        return "has no " + " and no ".join(n for n, ok in (("fmt chunk", fmt), ("data chunk", data)) if not ok)
    return None


def _jpeg_problem(slot: bytes) -> Optional[str]:
    if slot[:2] != b"\xff\xd8":
        return f"is not a JPEG (starts {slot[:4].hex(' ')}, not FF D8)"
    if slot.find(b"\xff\xd9", 2) < 0:
        return "has no FF D9 end marker inside its slot (cut off or partly erased)"
    return None


def _gpzp_problem(slot: bytes) -> Optional[str]:
    if slot[:4] != frames_mod.GPZP_MAGIC:
        return f"is not GPZP (starts {slot[:4].hex(' ')})"
    try:
        g = frames_mod.parse_gpzp(slot)
        blocks = frames_mod.deflate_blocks(slot[4: 4 + g.stream_len])
    except ValueError as exc:
        return str(exc)
    other = sorted({kind for kind, _ in blocks if kind != "fixed"})
    if other:
        return f"uses {' and '.join(other)} DEFLATE blocks; every stock stream is fixed-Huffman only"
    return None


def _gprs_problem(slot: bytes) -> Optional[str]:
    if slot[:4] != b"GPRS":
        return f"is not a GPRS pack (starts {slot[:4].hex(' ')})"
    return None


KIND_CHECKS = {"WAV": _wav_problem, "JPG": _jpeg_problem, "GPZP": _gpzp_problem, "GPZ": _gpzp_problem, "PAK": _gprs_problem}


def resource_problems(image: bytes, entries: Optional[List[ResourceEntry]] = None) -> List[str]:
    """One line per directory entry whose slot is outside the pack or the file, erased, or does not hold
    the kind its name says (.WAV, .JPG, .GPZP/.GPZ, .PAK). Empty when every entry is sound."""
    if entries is None:
        entries = resource_directory(image)
    problems = []
    dir_end = DIRECTORY_START + DIRECTORY_ENTRY * len(entries)
    for e, start, end in resource_slots(image, entries):
        where = f"{e.display_name} at {start:#x}"
        if start < dir_end or end > RESOURCE_LIMIT:
            problems.append(f"{where}: its slot {start:#x}..{end:#x} lies outside the resource area {dir_end:#x}..{RESOURCE_LIMIT:#x}")
            continue
        if end <= start:
            problems.append(f"{where}: empty slot (the next entry starts at {end:#x})")
            continue
        if end > len(image):
            problems.append(f"{where}: its slot {start:#x}..{end:#x} runs past the end of the file ({len(image):#x} bytes)")
            continue
        slot = bytes(image[start:end])
        if slot == b"\xff" * len(slot):
            problems.append(f"{where}: erased (all {len(slot)} bytes of its slot are 0xFF)")
            continue
        ext = e.display_name.rpartition(".")[2] if "." in e.display_name else ""
        check = KIND_CHECKS.get(ext)
        if check is None:
            problems.append(f"{where}: the name has no known kind (.WAV, .JPG, .GPZP, .GPZ, .PAK)")
            continue
        try:
            why = check(slot)
        except (ValueError, struct.error, IndexError) as exc:
            why = f"cannot be read ({exc})"
        if why:
            problems.append(f"{where}: {why}")
    return problems


def verify_update(path: Path, image: bytes, stock: Optional[bytes] = None, allow_modified: bool = False,
                  allow_unknown_build: bool = False, allow_odd_size: bool = False) -> List[Check]:
    checks: List[Check] = []

    # 1. Filename.
    try:
        name = parse_update_name(path.name)
        checks.append(Check("filename", True, f"JH_{name.product} stamp {name.stamp} checksum {name.checksum:08X}"))
        checks.append(Check("product", name.product == "5408", f"product code {name.product}"
                            + ("" if name.product == "5408" else " is not 5408; the camera only scans JH_5408*.bin and would ignore this file")))
        s = byte_sum(image)
        checks.append(Check("checksum", s == name.checksum,
                            f"file byte sum {s:08X} {'==' if s == name.checksum else '!='} filename {name.checksum:08X}"
                            + ("" if s == name.checksum else " (the camera would show 'Program error' and not erase)")))
        checks.append(Check("stamp", name.stamp != STOCK_TIMESTAMP,
                            f"stamp {name.stamp} {'differs from' if name.stamp != STOCK_TIMESTAMP else 'EQUALS'} the stock stamp {STOCK_TIMESTAMP}"
                            + ("" if name.stamp != STOCK_TIMESTAMP else " (the updater would ignore the file)")))
        real_date = name.stamp_datetime() is not None
        checks.append(Check("stamp-date", real_date, "stamp is a real date" if real_date else "stamp is not a valid YYYYMMDDhhmm",
                            "fail" if real_date else "warn"))
    except ValueError as exc:
        checks.append(Check("filename", False, str(exc)))

    # 2. Size and structure.
    n = len(image)
    if n == UPDATE_FILE_SIZE:
        checks.append(Check("size", True, f"{n} bytes = 30 blocks of 64 KiB (the proven update size)"))
    elif n % 0x100 == 0 and SOFTWARE_END <= n <= 0x200000:
        checks.append(Check("size", True, f"{n} bytes, whole 256-byte pages" + ("" if n % BLOCK_SIZE == 0 else "; not a whole number of 64 KiB blocks (the remainder of the last block stays erased)"), "info"))
    elif n % 0x100:
        checks.append(Check("size", allow_odd_size, f"{n} bytes is not a multiple of 256; the updater programs whole pages from its RAM buffer, "
                            "so the last page would receive stale bytes after the file's end", "warn" if allow_odd_size else "fail"))
    else:
        checks.append(Check("size", allow_odd_size, f"{n} bytes is shorter than 0x170000 (the file would end inside the software or resource area, "
                            "leaving stale flash there) or longer than 2 MiB", "warn" if allow_odd_size else "fail"))
    extra = updater_extra_erase(n)
    what = describe_erase_target(extra)
    harmless = 0x1CB000 <= extra < 0x200000
    checks.append(Check("final-erase", harmless or not (extra >= 0x200000), f"after programming, the updater erases the 64 KiB block at {extra:#x}: {what}",
                        "info" if harmless else ("fail" if extra >= 0x200000 else "warn")))
    if n == 0x200000:
        checks.append(Check("size-2mib", False, "a full 2 MiB file: never put one on a card. Trim to 0x1E0000 with pack."))
    checks.append(Check("magic", image[:4] == SPII_MAGIC, "starts with 'SPII'" if image[:4] == SPII_MAGIC else f"first bytes {image[:4]!r}, not 'SPII' (the camera does not check this, but it would not boot)"))

    # 3. Build identity.
    build = detect_build(image)
    if build.build is None:
        checks.append(Check("series", allow_unknown_build, "no known build stamps found: cannot tell which camera series this is for"
                            + ("" if allow_unknown_build else " (refusing; use --allow-unknown-build only with your own dump of that camera)")))
    else:
        checks.append(Check("series", True, f"{build.build.label}"))
        s_start, s_end = build.build.sensor_table
        if build.checks.get("sensor_table", False):
            checks.append(Check("sensor-table", True, f"the image-sensor set-up table at {s_start:#x}..{s_end:#x} is the stock one"))
        else:
            checks.append(Check("sensor-table", allow_unknown_build,
                                f"the image-sensor set-up table at {s_start:#x}..{s_end:#x} differs from the {build.build.key} one; "
                                "no toolkit mod changes it, and the wrong table is what stops other editions from taking pictures"
                                + (" (accepted because of --allow-unknown-build)" if allow_unknown_build
                                   else " (refusing; --allow-unknown-build accepts it, stage never does)"),
                                "warn" if allow_unknown_build else "fail"))
        exact = build.checks.get("software_sha256", False)
        if exact:
            checks.append(Check("software-region", True, "bytes 0..0x170000 are byte-identical to the stock original-series software"))
        elif n < SOFTWARE_END:
            checks.append(Check("software-region", False, f"the file ends at {n:#x}, before the end of the software region (0x170000); it cannot be a complete update"))
        else:
            detail = "bytes 0..0x170000 differ from stock"
            if stock is not None and len(stock) >= SOFTWARE_END:
                blocks = [i for i in range(0, SOFTWARE_END, BLOCK_SIZE) if image[i:i + BLOCK_SIZE] != stock[i:i + BLOCK_SIZE]]
                detail += f" in 64 KiB blocks {[hex(b) for b in blocks]}"
                in_res = [b for b in blocks if b >= 0x120000]
                in_code = [b for b in blocks if b < 0x120000]
                detail += f" (code/table blocks: {len(in_code)}, resource blocks: {len(in_res)})"
            checks.append(Check("software-region", allow_modified, detail + ("" if allow_modified else "; pass --allow-modified if this is your intended mod")))

    # 4. What the file carries into the camera's own area.
    if n > SETTINGS_BASE:
        recs = settings_mod.parse_settings(image)
        valid = [r for r in recs if r.crc_ok]
        if not recs:
            checks.append(Check("settings", True, "settings store blank: the camera will create defaults (INFERRED from emulation, not hardware-tested)", "info"))
        else:
            try:
                cur = settings_mod.current_record(image)
                checks.append(Check("settings", True, f"{len(recs)} settings records ({len(valid)} valid); the camera will load the one at {cur.offset:#x}: UI sound {cur.ui_sound}, playback volume {cur.playback_volume}, auto-off code {cur.raw[settings_mod.OFF_AUTO_OFF]}", "info"))
            except ValueError as exc:
                checks.append(Check("settings", True, f"{len(recs)} settings records but {exc}", "warn"))
        checks.append(Check("photo-directory", settings_mod.photo_directory_consistent(image),
                            "photo directory sector is consistent with the settings state" if settings_mod.photo_directory_consistent(image)
                            else "a valid settings record exists but the photo directory sector at 0x17F000 is 0xFF; the camera would read two bogus photo entries. Zero that sector (pack does this)."))
        photos = stored_photos(image)
        checks.append(Check("photos", True, f"{len(photos)} photo(s) stored in the image" + (" (someone else's if this is not your own dump)" if photos else ""), "info" if not photos else "warn"))

    # 5. Resource pack sanity (only when the whole pack is present).
    if n >= SOFTWARE_END:
        res = resource_directory(image)
        checks.append(Check("resources", len(res) == 43, f"{len(res)} resource entries" + ("" if len(res) == 43 else " (stock has 43)"), "fail" if len(res) == 0 else ("warn" if len(res) != 43 else "info")))
        if res:
            problems = resource_problems(image, res)
            if problems:
                listed = problems[:MAX_LISTED_PROBLEMS]
                rest = [line.split(" at ", 1)[0] for line in problems[MAX_LISTED_PROBLEMS:]]
                checks.append(Check("resource-slots", False, f"{len(problems)} of {len(res)} entries are damaged: " + "; ".join(listed)
                                    + (f"; and {len(rest)} more: {', '.join(rest)}" if rest else "")
                                    + ". The camera checks none of this; the boot logo is opened at every boot before the card is read, so a damaged one can leave a camera the SD updater cannot reach."))
            else:
                checks.append(Check("resource-slots", True, f"all {len(res)} entries lie inside the pack, none is erased, and each holds the kind its name says "
                                    "(WAV with fmt and data within its slot, JPEG with an end marker, GPZP that inflates with a matching trailer and only fixed-Huffman blocks, GPRS)"))
        try:
            slots = sound_slots(image)
            bad = [s.entry.display_name for s in slots if s.total_len > (s.entry.budget or 0)]
            checks.append(Check("sounds", not bad, f"{len(slots)} WAV slots parse" + (f"; oversized: {bad}" if bad else "")))
        except ValueError as exc:
            checks.append(Check("sounds", False, str(exc)))
    return checks


def handler(args) -> int:
    path = Path(args.file)
    if not path.is_file():
        print(f"error: {path} is not a file")
        return 2
    image = path.read_bytes()
    stock = None
    if args.stock:
        if not Path(args.stock).is_file():
            print(f"error: --stock {args.stock} is not a file")
            return 2
        stock = Path(args.stock).read_bytes()
    checks = verify_update(path, image, stock, args.allow_modified, args.allow_unknown_build, args.allow_odd_size)
    failed = False
    for c in checks:
        if c.severity == "warn":
            tag = "WARN"
        elif c.severity == "info" and c.ok:
            tag = "INFO"
        else:
            tag = "PASS" if c.ok else "FAIL"
        if not c.ok and c.severity == "fail":
            failed = True
        print(f"{tag:4s} {c.name:16s} {c.detail}")
    print()
    if failed:
        print("RESULT: do not put this file on a card.")
        return 1
    print("RESULT: no blocking problem found. This is a static check; it cannot see which camera you will put it in.")
    print("        Only use it on the series it was built for, with a verified backup of your own flash.")
    return 0


def register(subparsers) -> None:
    p = subparsers.add_parser("verify", help="check an update file's name, checksum, size and build before it goes near a card")
    p.add_argument("file")
    p.add_argument("--stock", help="your own stock dump, to show which blocks a modified file changes")
    p.add_argument("--allow-modified", action="store_true", help="accept a file whose software region differs from stock")
    p.add_argument("--allow-unknown-build", action="store_true",
                   help="accept a file without known build stamps, or one whose image-sensor table differs from stock (that line then shows WARN); stage never accepts either")
    p.add_argument("--allow-odd-size", action="store_true", help="accept a size that is not a multiple of 256 bytes or lies outside 0x170000..0x200000 (shown as WARN)")
    p.set_defaults(func=handler)
