"""`verify`: decide whether an update file may go on a card. Read-only.

A file passes only if every check below passes. The checks encode what is known about the camera's
updater (VERIFIED: filename-driven byte sum, no other validation; erases and programs whole 64 KiB blocks
from offset 0, then erases one more block) and about the known builds. Anything unknown fails closed.

What a pass means, and what it does not: every check here looks at the file. None of them looks at the
camera. A file identical to the public dump matches a camera only if that camera's sensor is the one the
public build drives; two builds carry the same stamp, and the public build does not fit every
original-series camera (one was left unable to take pictures by it). The only reference that does say
something about a particular camera is a dump of that camera's own flash chip, given as --stock.

--allow-modified accepts a software region that differs from the reference (any mod of the code or the
resources needs it; settings-only files pass without it; stage always passes it). It does not accept a
sensor table that differs from the reference or a damaged resource entry: the first needs
--allow-unknown-build, which stage never passes, or the donor route below; the second has no override.

--donor <a read of another, working camera's chip> is the donor route, for a camera whose own firmware is
gone and does not fit it (one flashed with the public dump that can no longer take pictures). It needs
--stock, this camera's own read as it is now, and the file must be the donor's firmware unchanged; its
sensor table may then differ from --stock's, as a donor's must to change anything. Nothing here can tell
whether the donor's camera has the same sensor and wiring as this one, and the output says so, with the
way back. A backup read after a flash shows what the camera runs now, not what fits it.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

from . import frames as frames_mod
from . import settings as settings_mod
from .checksum import BLOCK_SIZE, OFFICIAL_UPDATE_SIZE, STOCK_TIMESTAMP, UPDATE_FILE_SIZE, byte_sum, parse_update_name
from .image import (KNOWN_BUILDS, PUBLIC_BUILD, RESOURCE_BASE, RESOURCE_LIMIT, SETTINGS_BASE, SOFTWARE_END, SPII_MAGIC,
                    BuildMatch, ResourceEntry, SensorTables, chip_read_problem, describe_erase_target, detect_build,
                    find_marks, find_sensor_tables, public_dump_name, resource_directory, sound_slots, stored_photos,
                    updater_extra_erase)


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


def resource_limit(image_len: int) -> int:
    """Where the last entry's slot ends: the factory self-test sector at 0x16E000, or the end of an
    official-size file (0x169000 lies before that sector; the camera leaves the rest of the block erased)."""
    if OFFICIAL_UPDATE_SIZE <= image_len < RESOURCE_LIMIT:
        return image_len
    return RESOURCE_LIMIT


def resource_slots(image: bytes, entries: List[ResourceEntry]) -> List[tuple]:
    """(entry, start, end) for every directory entry: an entry owns the bytes up to the next entry's offset;
    the last one up to resource_limit()."""
    out = []
    limit = resource_limit(len(image))
    for i, e in enumerate(entries):
        end = entries[i + 1].offset if i + 1 < len(entries) else limit
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
    limit = resource_limit(len(image))
    for e, start, end in resource_slots(image, entries):
        where = f"{e.display_name} at {start:#x}"
        if start < dir_end or end > limit:
            problems.append(f"{where}: its slot {start:#x}..{end:#x} lies outside the resource area {dir_end:#x}..{limit:#x}")
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


# --- The sentence every build line carries -----------------------------------------------------------------------

NOT_THE_CAMERA = ("a build match says which public image the file was made from, not whether it fits the camera it goes "
                  "into; only a dump of that camera's own chip does")


def _table(t: SensorTables) -> str:
    return f"{t.pairs} writes at {t.init[0]:#x}, sha256 {t.sha256[:8]}..."


def _compiled(marks) -> str:
    return f", compiled {marks.compiled.decode('ascii')} at {marks.compiled_offset:#x}" if marks.compiled else ", no compile date found"


def _donor_checks(image: bytes, match: BuildMatch, stock: Optional[bytes], donor: bytes, name_stamp: Optional[str]) -> List[Check]:
    """The build, sensor and software lines of the donor route, in place of the usual ones."""
    out: List[Check] = []
    n = len(image)
    if stock is None:
        out.append(Check("donor", False, "--donor needs --stock, a read of this camera's own flash chip as it is now: no backup of your own "
                         "camera, no flash. The donor route takes this camera off the firmware it runs, and that read is the only way back"))
        return out
    problem = chip_read_problem(stock, need_sensor_table=False)
    if problem:
        out.append(Check("backup", False, f"--stock is not a complete read of this camera's chip: {problem}. The donor route needs one"))
        return out
    problem = chip_read_problem(donor, need_sensor_table=False)
    if problem:
        out.append(Check("donor", False, f"--donor is not a complete read of a camera's chip: {problem}"))
        return out
    if donor[:SOFTWARE_END] == stock[:SOFTWARE_END]:
        out.append(Check("donor", False, "--donor carries the same firmware as --stock (bytes 0..0x170000 are identical), so the flash "
                         "would change nothing on this camera"))
        return out
    donor_match = detect_build(donor)
    dm = donor_match.marks
    donor_public = public_dump_name(donor)
    out.append(Check("donor", True, f"--donor: {donor_match.label}; build string {dm.build_string.decode('ascii', 'replace').strip()!r}, "
                     f"stamp {dm.stamp}{_compiled(dm)}"
                     + (f"; it is {donor_public} byte for byte, the firmware this camera's trouble started with" if donor_public else ""),
                     "warn" if donor_public else "info"))

    span = min(n, SOFTWARE_END)
    if n < OFFICIAL_UPDATE_SIZE:
        out.append(Check("from-donor", False, f"the file ends at {n:#x}, before the end of the resource pack (0x169000); it cannot be a complete update"))
        return out
    if image[:span] != donor[:span]:
        blocks = [hex(b) for b in range(0, span, BLOCK_SIZE) if image[b: min(b + BLOCK_SIZE, span)] != donor[b: min(b + BLOCK_SIZE, span)]]
        out.append(Check("from-donor", False, f"the file is not --donor's firmware unchanged: bytes 0..{span:#x} differ from it in 64 KiB "
                         f"blocks {blocks}. The donor route takes the donor's firmware as it is: build the file from --donor with pack"))
        return out
    out.append(Check("from-donor", True, f"bytes 0..{span:#x} are byte-identical to --donor: the file is the donor's firmware unchanged"))

    marks = match.marks
    out.append(Check("series", True, f"Charmera firmware: build string {marks.build_string.decode('ascii', 'replace').strip()!r} at "
                     f"{marks.build_offset:#x}{_compiled(marks)}, stamp {marks.stamp} at {marks.stamp_offset:#x}"))
    out.append(Check("build", True, f"{match.label}; {NOT_THE_CAMERA}", "info" if match.build is not None else "warn"))
    stock_stamp = find_marks(stock).stamp
    if name_stamp is not None and stock_stamp is not None and stock_stamp != STOCK_TIMESTAMP:
        out.append(Check("stamp-camera", name_stamp != stock_stamp, f"stamp {name_stamp} in the name "
                         + ("differs from" if name_stamp != stock_stamp else "EQUALS") + f" {stock_stamp}, the stamp of the firmware this "
                         "camera runs (--stock)" + ("" if name_stamp != stock_stamp else ", so the camera would ignore the file")))

    ref = find_sensor_tables(stock)
    sensor = marks.sensor
    if sensor is None:
        out.append(Check("sensor-table", True, "the file (the donor's firmware) has no sensor table of the kind the known builds use: it sets "
                         "up its image sensor some other way, perhaps for another kind of sensor. Nothing here can compare it with this "
                         "camera's", "warn"))
    elif ref is None:
        out.append(Check("sensor-table", True, f"the file's sensor table: {_table(sensor)}; --stock has none of the kind the known builds use, "
                         "so nothing here can compare the two", "warn"))
    elif ref.sha256 == sensor.sha256:
        out.append(Check("sensor-table", True, f"the file sets up the image sensor exactly as --stock does ({_table(sensor)}). If this camera "
                         "cannot take pictures with the firmware it runs now, the donor's is unlikely to change that: it differs from it only "
                         "elsewhere (software-region line)", "warn"))
    else:
        out.append(Check("sensor-table", True, f"the file's image-sensor set-up differs from --stock's, as a donor's must to change anything: "
                         f"the file has {_table(sensor)}, --stock {_table(ref)}. Nothing here can tell whether the donor's camera has the "
                         "same image sensor and wiring as this one: compare the markings on the two cameras' sensor modules, and even "
                         "matching markings leave other board differences possible", "warn"))

    blocks = [hex(b) for b in range(0, span, BLOCK_SIZE) if image[b: min(b + BLOCK_SIZE, span)] != stock[b: min(b + BLOCK_SIZE, span)]]
    out.append(Check("software-region", True, f"bytes 0..{span:#x} differ from --stock (this camera now) in 64 KiB blocks {blocks}: the "
                     "donor's firmware replaces this camera's", "info"))
    stock_public = public_dump_name(stock)
    out.append(Check("way-back", True, "the way back is --stock, this camera's chip as it is now"
                     + (f" (byte-identical to {stock_public}, as a camera flashed with the public restore file reads while its "
                        "settings are unchanged)" if stock_public else "")
                     + ". If the donor's firmware starts far enough to run the card updater, a file made from --stock with pack can be "
                     "flashed from a card; if it does not start, only a chip programmer can put --stock back (docs/02-RECOVERY.md, "
                     "section B)", "warn"))
    return out


def verify_update(path: Path, image: bytes, stock: Optional[bytes] = None, allow_modified: bool = False,
                  allow_unknown_build: bool = False, allow_odd_size: bool = False,
                  donor: Optional[bytes] = None) -> List[Check]:
    checks: List[Check] = []
    n = len(image)
    match = detect_build(image)
    marks = match.marks
    name_stamp: Optional[str] = None

    # 1. Filename.
    try:
        name = parse_update_name(path.name)
        name_stamp = name.stamp
        checks.append(Check("filename", True, f"JH_{name.product} stamp {name.stamp} checksum {name.checksum:08X}"))
        checks.append(Check("product", name.product == "5408", f"product code {name.product}"
                            + ("" if name.product == "5408" else " is not 5408; the camera only scans JH_5408*.bin and would ignore this file")))
        s = byte_sum(image)
        checks.append(Check("checksum", s == name.checksum,
                            f"file byte sum {s:08X} {'==' if s == name.checksum else '!='} filename {name.checksum:08X}"
                            + ("" if s == name.checksum else " (the camera would show 'Program error' and not erase)")))
        checks.append(Check("stamp", name.stamp != STOCK_TIMESTAMP,
                            f"stamp {name.stamp} {'differs from' if name.stamp != STOCK_TIMESTAMP else 'EQUALS'} {STOCK_TIMESTAMP}, "
                            "the stamp both known builds carry"
                            + ("" if name.stamp != STOCK_TIMESTAMP else " (a camera running either build would ignore the file)")))
        real_date = name.stamp_datetime() is not None
        checks.append(Check("stamp-date", real_date, "stamp is a real date" if real_date else "stamp is not a valid YYYYMMDDhhmm",
                            "fail" if real_date else "warn"))
        if marks.stamp is not None:
            if marks.stamp == name.stamp:
                checks.append(Check("stamp-in-file", True, f"the file's own stamp at {marks.stamp_offset:#x} is {marks.stamp}, the same as in "
                                    "its name, so a camera that runs this file ignores it afterwards (as the film-mods files do)", "info"))
            else:
                checks.append(Check("stamp-in-file", True, f"the file's own stamp at {marks.stamp_offset:#x} is {marks.stamp}, not the "
                                    f"{name.stamp} in its name, so a camera that runs this file flashes it again at every power-on while "
                                    "the file is on the card: take the card out before the next power-on", "info"))
    except ValueError as exc:
        checks.append(Check("filename", False, str(exc)))

    # 2. Size and structure.
    if n == UPDATE_FILE_SIZE:
        checks.append(Check("size", True, f"{n} bytes = 30 blocks of 64 KiB, the size the community update files use (writes the settings "
                            "bank and the stored photos too)"))
    elif n == OFFICIAL_UPDATE_SIZE:
        checks.append(Check("size", True, f"{n} bytes = the official size (the film-mods files' size; no official update file has ever been "
                            "published): code, data and the resource pack only; nothing of the camera's own area (settings, stored "
                            "photos) is written", "info"))
    elif n % 0x100 == 0 and SOFTWARE_END <= n <= 0x200000:
        checks.append(Check("size", True, f"{n} bytes, whole 256-byte pages" + ("" if n % BLOCK_SIZE == 0 else "; not a whole number of 64 KiB blocks (the remainder of the last block stays erased)"), "info"))
    elif n % 0x100:
        checks.append(Check("size", allow_odd_size, f"{n} bytes is not a multiple of 256; the updater programs whole pages from its RAM buffer, "
                            "so the last page would receive stale bytes after the file's end", "warn" if allow_odd_size else "fail"))
    else:
        checks.append(Check("size", allow_odd_size, f"{n} bytes is shorter than 0x169000 (the file would end inside the software or resource area, "
                            "leaving stale flash there) or longer than 2 MiB", "warn" if allow_odd_size else "fail"))
    extra = updater_extra_erase(n)
    what = describe_erase_target(extra)
    harmless = 0x1CB000 <= extra < 0x200000
    checks.append(Check("final-erase", harmless or not (extra >= 0x200000), f"after programming, the updater erases the 64 KiB block at {extra:#x}: {what}",
                        "info" if harmless else ("fail" if extra >= 0x200000 else "warn")))
    if n == 0x200000:
        checks.append(Check("size-2mib", False, "a full 2 MiB file: never put one on a card (the final erase wraps to block 1 and bricks the camera). Trim with pack."))
    checks.append(Check("magic", image[:4] == SPII_MAGIC, "starts with 'SPII'" if image[:4] == SPII_MAGIC else f"first bytes {image[:4]!r}, not 'SPII' (the camera does not check this, but it would not boot)"))

    # 3. Build identity, read from the content. The stamp is not an identity: two builds share it.
    if donor is not None:
        checks.extend(_donor_checks(image, match, stock, donor, name_stamp))
    elif not match.charmera:
        missing = [k for k, found in (("build string", marks.build_string), ("12-digit stamp", marks.stamp), ("sensor table", marks.sensor)) if found is None]
        checks.append(Check("series", allow_unknown_build, "no Charmera build marks found (" + ", ".join(missing) + " missing): cannot tell "
                            "whether this is Charmera firmware at all"
                            + ("" if allow_unknown_build else " (refusing; use --allow-unknown-build only with your own dump of the camera it goes into)")))
    else:
        sensor = marks.sensor
        checks.append(Check("series", True, f"Charmera firmware: build string {marks.build_string.decode('ascii', 'replace').strip()!r} at "
                            f"{marks.build_offset:#x}{_compiled(marks)}, stamp {marks.stamp} at {marks.stamp_offset:#x} (the stamp is "
                            "shared by two different builds, so it identifies neither; their compile dates differ)"))
        if stock is not None:
            stock_problem = chip_read_problem(stock, need_sensor_table=False)
            stock_public = public_dump_name(stock)
            if stock_problem:
                checks.append(Check("stock", True, f"--stock is not a complete read of a camera's chip ({stock_problem}): the comparisons "
                                    "below are with that file, not with a camera", "warn"))
            elif stock_public:
                checks.append(Check("stock", True, f"--stock is byte-identical to {stock_public}. A camera flashed with the public restore "
                                    "file reads exactly like this while its settings are unchanged; if this is not a read of the camera the "
                                    "file goes into, the comparisons below say nothing about that camera. And a read taken after a flash "
                                    "shows what the camera runs now, not what fits it", "warn"))
        if match.build is not None:
            status = f"{match.build.status} fingerprint" + (", exact" if not match.modified else ", with changes")
            checks.append(Check("build", True, f"{match.label} ({status}); {NOT_THE_CAMERA}", "info"))
        else:
            checks.append(Check("build", True, f"{match.label}; it belongs only in the camera it was dumped from, or in one with the same "
                                "parts; " + NOT_THE_CAMERA, "warn"))
        table_where = (f"{sensor.pairs} writes at {sensor.init[0]:#x}..{sensor.init[1]:#x} (reset table at {sensor.reset[0]:#x}"
                       + (f", bus address {sensor.bus_address:#x}" if sensor.bus_address is not None else "")
                       + (f", referenced from code at {sensor.referenced_from:#x}" if sensor.referenced_from is not None else ", no code reference found")
                       + ")")
        known_table = sensor.sha256 in {b.sensor_table_sha256 for b in KNOWN_BUILDS.values()}
        ref = find_sensor_tables(stock) if stock is not None else None
        if stock is not None and ref is None:
            checks.append(Check("sensor-table", allow_unknown_build, f"the image-sensor set-up table: {table_where}; --stock has no sensor table to "
                                "compare it with, so the file cannot be matched to that camera"
                                + (" (accepted because of --allow-unknown-build)" if allow_unknown_build else " (refusing; stage never accepts this)"),
                                "warn" if allow_unknown_build else "fail"))
        elif ref is not None:
            same = ref.sha256 == sensor.sha256
            stock_is_public = ref.sha256 == PUBLIC_BUILD.sensor_table_sha256
            if same:
                checks.append(Check("sensor-table", True, f"the image-sensor set-up table is the same as in --stock: {table_where}"
                                    + ("; that is the public build's table, which proves a match only if --stock is a dump of the camera this file "
                                       "goes into" if stock_is_public else "")))
            else:
                checks.append(Check("sensor-table", allow_unknown_build, f"the image-sensor set-up table DIFFERS from --stock's: {table_where}; "
                                    f"--stock has {ref.pairs} writes at {ref.init[0]:#x}, sha256 {ref.sha256[:8]}... The file drives a sensor "
                                    "differently from your reference image, and a camera whose sensor is not the one a table drives freezes in "
                                    "photo and video mode after the flash"
                                    + (" (accepted because of --allow-unknown-build)" if allow_unknown_build
                                       else " (refusing; --allow-unknown-build accepts it, stage never does)"),
                                    "warn" if allow_unknown_build else "fail"))
        elif known_table:
            checks.append(Check("sensor-table", True, f"the image-sensor set-up table is the one both known builds carry: {table_where}, sha256 "
                                f"{sensor.sha256[:8]}... It fits a camera only if that camera's sensor is the one this table drives, which "
                                "nothing in the file can tell: one original-series camera was left unable to take pictures by it. Give "
                                "--stock <a dump of the camera this file goes into> to compare", "info"))
        else:
            checks.append(Check("sensor-table", allow_unknown_build, f"the image-sensor set-up table is not the known builds': {table_where}, sha256 "
                                f"{sensor.sha256[:8]}... No toolkit mod changes this table, so the file was made from a dump of another build. "
                                "Nothing here can tell whether it fits your camera: pass --stock <a dump of this camera's own chip> to compare"
                                + (" (accepted because of --allow-unknown-build)" if allow_unknown_build
                                   else ", or --allow-unknown-build (refusing; stage never accepts it)"),
                                "warn" if allow_unknown_build else "fail"))

        # The software region: against --stock when given (the owner's reference), else against the known builds.
        span = min(n, SOFTWARE_END)
        if n < OFFICIAL_UPDATE_SIZE:
            checks.append(Check("software-region", False, f"the file ends at {n:#x}, before the end of the resource pack (0x169000); it cannot be a complete update"))
        elif stock is not None:
            identical = image[:span] == stock[:span] and (span >= SOFTWARE_END or stock[span:SOFTWARE_END] == b"\xff" * (SOFTWARE_END - span))
            if identical:
                checks.append(Check("software-region", True, f"bytes 0..{span:#x} are byte-identical to --stock"
                                    + (f" (and --stock is blank from {span:#x} to 0x170000, as the update leaves it)" if span < SOFTWARE_END else "")))
            else:
                blocks = [i for i in range(0, SOFTWARE_END, BLOCK_SIZE) if image[i: i + BLOCK_SIZE] != stock[i: i + BLOCK_SIZE]]
                in_res = [b for b in blocks if b >= 0x120000]
                in_code = [b for b in blocks if b < 0x120000]
                detail = (f"bytes 0..0x170000 differ from --stock in 64 KiB blocks {[hex(b) for b in blocks]} (code/table blocks: {len(in_code)}, "
                          f"resource blocks: {len(in_res)})")
                checks.append(Check("software-region", allow_modified, detail + ("" if allow_modified else "; pass --allow-modified if this is your intended mod")))
        elif match.exact:
            checks.append(Check("software-region", True, f"bytes 0..0x169000 are byte-identical to {match.build.label} ({match.build.status})"
                                + (" and 0x169000..0x170000 are blank, as in the public dump" if n >= SOFTWARE_END and image[OFFICIAL_UPDATE_SIZE:SOFTWARE_END] == b"\xff" * (SOFTWARE_END - OFFICIAL_UPDATE_SIZE) else "")))
        else:
            detail = ("the software region differs from every known build" if match.build is None
                      else f"the software region differs from {match.build.label}")
            checks.append(Check("software-region", allow_modified, detail + "; give --stock <your reference image> to see which 64 KiB blocks"
                                + ("" if allow_modified else ", and pass --allow-modified if this is your intended mod")))

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
        listed = settings_mod.photo_directory_lists(image) if hasattr(settings_mod, "photo_directory_lists") else None
        detail = f"{len(photos)} photo(s) stored in the image"
        if photos:
            detail += " (someone else's if this is not your own dump)"
            if listed is not None:
                detail += ("; the photo directory lists none of them, so the camera will not show them" if listed == 0
                           else f"; the photo directory lists {listed}")
        checks.append(Check("photos", True, detail, "info" if not photos else "warn"))
    elif n >= OFFICIAL_UPDATE_SIZE:
        checks.append(Check("own-area", True, "the file stops before the settings bank: no settings record and no stored photo of another camera is "
                            "written; the camera's own settings are erased by the final erase (see above) and its stored photo data is left in place", "info"))

    # 5. Resource pack sanity (only when the whole pack can be present).
    if n >= OFFICIAL_UPDATE_SIZE:
        res = resource_directory(image)
        checks.append(Check("resources", len(res) == 43, f"{len(res)} resource entries" + ("" if len(res) == 43 else " (the known builds have 43)"), "fail" if len(res) == 0 else ("warn" if len(res) != 43 else "info")))
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
    donor = None
    if getattr(args, "donor", None):
        if not Path(args.donor).is_file():
            print(f"error: --donor {args.donor} is not a file")
            return 2
        donor = Path(args.donor).read_bytes()
    checks = verify_update(path, image, stock, args.allow_modified, args.allow_unknown_build, args.allow_odd_size, donor=donor)
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
    print("RESULT: no blocking problem found. This is a static check of the file; it cannot see the camera you will put it in.")
    if donor is not None:
        print("        The file is another camera's firmware. Whether it fits this camera is not known: see the sensor-table and")
        print("        way-back lines above.")
    elif stock is None:
        print("        No --stock was given, so nothing above compares the file with that camera's own firmware.")
    print("        No backup of that camera's own flash chip, no flash: without one, a file that does not fit cannot be undone.")
    return 0


def register(subparsers) -> None:
    p = subparsers.add_parser("verify", help="check an update file's name, checksum, size and build before it goes near a card")
    p.add_argument("file")
    p.add_argument("--stock", help="a dump of the camera this file goes into (your backup): the sensor table and the software region are compared with it")
    p.add_argument("--allow-modified", action="store_true", help="accept a software region that differs from the reference")
    p.add_argument("--allow-unknown-build", action="store_true",
                   help="accept a file without build marks, or one whose image-sensor table differs from the reference (that line then shows WARN); stage never accepts either")
    p.add_argument("--allow-odd-size", action="store_true", help="accept a size that is not a multiple of 256 bytes or lies outside 0x169000..0x200000 (shown as WARN)")
    p.add_argument("--donor", help="the donor route: a read of another, working camera's chip whose firmware the file is, for a camera whose "
                                   "own firmware is gone and does not fit it; needs --stock (this camera's own read as it is now)")
    p.set_defaults(func=handler)
