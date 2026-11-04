"""`diff`: say exactly what differs between two images, region by region.

Use it to review a mod before packing it (stock dump against the modified image), to compare two backups of the
same camera, or to check a public dump against your own. It never writes anything.

Regions follow the layout in docs/FORMAT.md. For each region the output gives the number of differing bytes and
the first few differing ranges; the settings bank is additionally explained record by record and the resource
pack entry by entry, because those are where mods and normal use leave their marks.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Tuple

from . import image as img
from . import settings as settings_mod

# (name, start, end) in file offsets; end is exclusive. The last region runs to the longer file's end.
REGIONS: Tuple[Tuple[str, int, int], ...] = (
    ("boot header (SPII)", 0, img.HEADER_SIZE),
    ("code and tables", img.HEADER_SIZE, img.CODE_END),
    ("heap placeholder (zeros)", img.CODE_END, img.RESOURCE_BASE),
    ("resource pack (GP+)", img.RESOURCE_BASE, img.SOFTWARE_END),
    ("settings bank", settings_mod.SETTINGS_BASE, settings_mod.PHOTO_DIRECTORY),
    ("photo directory", settings_mod.PHOTO_DIRECTORY, img.PHOTO_SLOTS[0]),
    ("photo slot 0", img.PHOTO_SLOTS[0], img.PHOTO_SLOTS[1]),
    ("photo slot 1", img.PHOTO_SLOTS[1], img.PHOTO_SLOTS[1] + img.PHOTO_SLOT_SIZE),
    ("unused (0xFF)", img.PHOTO_SLOTS[1] + img.PHOTO_SLOT_SIZE, 0x200000),
)


@dataclass
class RegionDiff:
    name: str
    start: int
    end: int
    differing: int
    ranges: List[Tuple[int, int]]   # [start, end) of differing runs, first MAX_RANGES only
    only_in: Optional[str]          # "a" / "b" when one file ends inside the region
    sha_a: str
    sha_b: str


MAX_RANGES = 8


def differing_ranges(a: bytes, b: bytes, base: int, limit: int = MAX_RANGES) -> Tuple[int, List[Tuple[int, int]]]:
    """Count differing bytes over the common length and collect the first `limit` differing runs."""
    n = min(len(a), len(b))
    count = 0
    ranges: List[Tuple[int, int]] = []
    i = 0
    # Fast skip over identical 4 KiB chunks, then byte-walk the rest.
    chunk = 4096
    while i < n:
        j = min(i + chunk, n)
        if a[i:j] == b[i:j]:
            i = j
            continue
        run_start = None
        for k in range(i, j):
            if a[k] != b[k]:
                count += 1
                if run_start is None:
                    run_start = k
            elif run_start is not None:
                if len(ranges) < limit:
                    ranges.append((base + run_start, base + k))
                elif ranges and ranges[-1][1] == base + run_start:
                    ranges[-1] = (ranges[-1][0], base + k)
                run_start = None
        if run_start is not None:
            if ranges and ranges[-1][1] == base + run_start:
                ranges[-1] = (ranges[-1][0], base + j)
            elif len(ranges) < limit:
                ranges.append((base + run_start, base + j))
        i = j
    # Merge runs split at chunk boundaries.
    merged: List[Tuple[int, int]] = []
    for r in ranges:
        if merged and merged[-1][1] == r[0]:
            merged[-1] = (merged[-1][0], r[1])
        else:
            merged.append(r)
    return count, merged


def diff_regions(a: bytes, b: bytes) -> List[RegionDiff]:
    out = []
    longest = max(len(a), len(b))
    for name, start, end in REGIONS:
        end = min(end, longest) if name != REGIONS[-1][0] else longest
        if start >= longest:
            break
        sa, sb = a[start:end], b[start:end]
        count, ranges = differing_ranges(sa, sb, start)
        only_in = None
        if len(sa) != len(sb):
            only_in = "a" if len(sa) > len(sb) else "b"
        out.append(RegionDiff(name, start, end, count, ranges, only_in,
                              hashlib.sha256(sa).hexdigest()[:16], hashlib.sha256(sb).hexdigest()[:16]))
    return out


def explain_settings(a: bytes, b: bytes) -> List[str]:
    """Record-level view of the settings bank: which records exist in each, and how the current ones differ."""
    lines = []
    if len(a) < settings_mod.BANK_END or len(b) < settings_mod.BANK_END:
        return ["  one file is too short to hold the settings bank"]
    ra, rb = settings_mod.parse_settings(a), settings_mod.parse_settings(b)
    lines.append(f"  records: {len(ra)} in A ({sum(r.crc_ok for r in ra)} CRC-valid), "
                 f"{len(rb)} in B ({sum(r.crc_ok for r in rb)} CRC-valid)")
    try:
        ca = settings_mod.current_record(a)
    except ValueError as exc:
        ca = None
        lines.append(f"  A would apply defaults: {exc}")
    try:
        cb = settings_mod.current_record(b)
    except ValueError as exc:
        cb = None
        lines.append(f"  B would apply defaults: {exc}")
    if ca is not None and cb is not None:
        changed = settings_mod.diff_records(ca, cb)
        if not changed:
            lines.append(f"  the record each camera would load is identical (A at {ca.offset:#x}, B at {cb.offset:#x})")
        else:
            lines.append(f"  the record each camera would load differs in {len(changed)} byte(s) "
                         f"(A at {ca.offset:#x}, B at {cb.offset:#x}):")
            for off in changed:
                name = settings_mod.FIELD_NAMES.get(off, "unknown field")
                lines.append(f"    +{off:#04x} {ca.raw[off]:3d} -> {cb.raw[off]:3d}  {name}")
    return lines


def explain_resources(a: bytes, b: bytes) -> List[str]:
    """Entry-level view of the resource pack: which named resources differ."""
    lines = []
    try:
        da, db = img.resource_directory(a), img.resource_directory(b)
    except Exception as exc:  # malformed pack in one file
        return [f"  resource directory unreadable: {exc}"]
    names_a = {e.display_name: e for e in da}
    names_b = {e.display_name: e for e in db}
    if list(names_a) != list(names_b):
        lines.append(f"  directory differs: {len(da)} entries in A, {len(db)} in B")
    for name, ea in names_a.items():
        eb = names_b.get(name)
        if eb is None:
            lines.append(f"  {name}: only in A")
            continue
        if ea.offset != eb.offset:
            lines.append(f"  {name}: moved {ea.offset:#x} -> {eb.offset:#x}")
        size = min(ea.budget or 0, eb.budget or 0)
        pa = a[ea.offset: ea.offset + size]
        pb = b[eb.offset: eb.offset + size]
        if pa != pb:
            count, _ = differing_ranges(pa, pb, ea.offset)
            lines.append(f"  {name}: {count} differing byte(s) within its {size} byte slot")
    for name in names_b:
        if name not in names_a:
            lines.append(f"  {name}: only in B")
    if not lines:
        lines.append("  every resource entry is byte-identical")
    return lines


def handler(args) -> int:
    pa, pb = Path(args.a), Path(args.b)
    for p in (pa, pb):
        if not p.is_file():
            print(f"error: {p} is not a file")
            return 2
    a, b = pa.read_bytes(), pb.read_bytes()
    print(f"A: {pa} ({len(a)} bytes, {img.image_kind(a)})")
    print(f"B: {pb} ({len(b)} bytes, {img.image_kind(b)})")
    if a == b:
        print("identical")
        return 0
    if len(a) != len(b):
        print(f"sizes differ by {abs(len(a) - len(b))} bytes; the longer file's extra bytes count as differences")
    total = 0
    any_diff = False
    for rd in diff_regions(a, b):
        extra = ""
        if rd.only_in:
            short = rd.end - min(len(a), len(b)) if min(len(a), len(b)) > rd.start else rd.end - rd.start
            extra = f", {short} byte(s) only in {rd.only_in.upper()}"
        if rd.differing == 0 and not rd.only_in:
            status = "same"
        else:
            status = f"{rd.differing} byte(s) differ{extra}"
            any_diff = True
        total += rd.differing
        print(f"{rd.start:#08x}-{rd.end:#08x} {rd.name:26s} {status}")
        if args.ranges and rd.ranges:
            for s, e in rd.ranges:
                print(f"    {s:#08x}-{e:#08x} ({e - s} bytes)")
            if rd.differing and len(rd.ranges) == MAX_RANGES:
                print("    ...")
        if rd.name == "settings bank" and (rd.differing or rd.only_in):
            for line in explain_settings(a, b):
                print(line)
        if rd.name == "resource pack (GP+)" and rd.differing:
            for line in explain_resources(a, b):
                print(line)
    print(f"total: {total} differing byte(s) over the common length")
    # What a reviewer most wants to know about a mod: did anything outside the expected places move?
    sw_a, sw_b = a[: img.SOFTWARE_END], b[: img.SOFTWARE_END]
    if sw_a == sw_b:
        print("software region (0x0-0x170000) identical: the two files differ only in settings or photos")
    else:
        code_same = a[img.HEADER_SIZE: img.CODE_END] == b[img.HEADER_SIZE: img.CODE_END]
        print("software region differs" + (" (resource pack only; code unchanged)" if code_same else " INCLUDING CODE"))
    return 1 if any_diff else 0


def register(subparsers) -> None:
    p = subparsers.add_parser("diff", help="compare two images region by region (read-only)")
    p.add_argument("a", help="first image (dump or update file)")
    p.add_argument("b", help="second image")
    p.add_argument("--ranges", action="store_true", help="list the first differing byte ranges in each region")
    p.set_defaults(func=handler)
