"""`verify`: decide whether an update file may go on a card. Read-only.

A file passes only if every check below passes. The checks encode what is known about the camera's
updater (VERIFIED: filename-driven byte sum, no other validation; erases and programs whole 64 KiB blocks
from offset 0, then erases one more block) and about the known builds. Anything unknown fails closed. The
camera is known to take two sizes, 0x1E0000 and 0x169000 bytes; any other size fails (--allow-odd-size
makes it a warning here; stage never passes it).

What a pass means, and what it does not: every check here looks at the file. None of them looks at the
camera. A file identical to the public dump matches a camera only if that camera's sensor is the one the
public build drives; three builds carry the same stamp, and the public build does not fit every
original-series camera (after it was flashed, one could no longer take pictures). The only reference that
does say something about a particular camera is a dump of that camera's own flash chip read before it was
ever flashed, given as --stock. --stock must be a complete read of a chip; one named like a read that no
second read confirmed (<label>_<time>.unverified-read<n>.bin) is warned about. When --stock carries a known
build's fingerprint, a sensor table that matches it is a warning, not a pass: it is that public build's table,
and a read taken after a flash shows what the camera runs now, not what fits it. When --stock is of a build
nobody has published, a matching table is a pass only when the program around it (bytes 0..0x120000) is
--stock's too; otherwise it is a warning, since a different program can drive the same sensor differently.
Without --stock a warning says that nothing compares the file with the camera's own firmware.

--allow-modified accepts a software region that differs from the reference, shown as a warning (any mod of
the code or the resources needs it; settings-only files pass without it; stage always passes it). It never
accepts an ARM vector table (0x200..0x240) that differs from --stock's in a file laid out like --stock, or
from that of the known build whose layout the file has, and it does not accept a damaged resource entry.
--allow-unknown-build accepts a file without build marks and, without --stock, a sensor table that is not
the known builds'; stage never passes it. No flag accepts a sensor table that differs from --stock's, a
--stock without a sensor table of the known kind, a file without one checked against --stock (outside the
donor route below, the one deliberate way past these three), several sensor programs in the file or in
--stock, a sensor table no code refers to where --stock's is referred to, or one of the withdrawn candidates
A and B (C is named, not refused: its bytes are the stock restore file made from the cleaned stock dump).

--donor <a read of another, working camera's chip> is the donor route, for a camera whose own firmware is
gone and does not fit it (one flashed with the public dump that can no longer take pictures). --stock, this
camera's own read as it is now, is optional (without it a WARN says nothing compares the file with what the
camera runs, and that the way back is the last update file it was flashed with), and the file must be the
donor's firmware unchanged (or, with
--allow-modified, a mod built on it that keeps the donor's boot header, vector table and sensor table, as a
warning; stage passes it); its sensor table may then differ from
--stock's, as a donor's must to change anything. Its vector table is
checked as on the normal route, and several sensor programs are refused there too. Nothing here can tell
whether the donor's camera has the same sensor and wiring as this one, and the output says so, with the
way back. A backup read after a flash shows what the camera runs now, not what fits it.
"""
from __future__ import annotations

import hashlib
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Tuple

from . import frames as frames_mod
from . import settings as settings_mod
from .checksum import (BLOCK_SIZE, FLASH_SIZE, OFFICIAL_UPDATE_SIZE, PAGE_SIZE, STOCK_TIMESTAMP, UPDATE_FILE_SIZE, byte_sum,
                       parse_update_name)
from .image import (CODE_AREA_END, KNOWN_BUILDS, OV9732_SCCB, RESOURCE_BASE, RESOURCE_LIMIT, SETTINGS_BASE, SOFTWARE_END,
                    SOURCE_TREE_PROVES, SPII_MAGIC, VECTOR_TABLE, BuildMarks, BuildMatch, ResourceEntry, SensorTables, chip_read_problem,
                    describe_erase_target, detect_build, find_marks, match_known_build, photos_whose, public_dump_name,
                    resource_directory, sensor_of, SP140A_INIT, sound_slots, stored_photos, updater_extra_erase, vector_table_sha256)


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


# --- The withdrawn candidates --------------------------------------------------------------------------------------
#
# A and B (docs/03-FLASH-CANDIDATES.md), by the SHA-256 the app refuses them by too (android/.../Candidates.java,
# Kind.A and B). They were withdrawn after the hardware test of 2026-11-06: they are made from the public dump,
# and no file made from it is known to fit a camera whose own firmware has not been read.
#
# C was withdrawn with them, but its bytes are also the stock restore file made from the public dump's cleaned copy
# (the-dumping-grounds/charmera-firmware-v006: the stock software, the factory settings record, no stored photos), and
# the stock restore file is not refused. So C is named, not refused (CLEAN_RESTORE), as the app does.

# The public copies of a factory build other than the public dump's (image.PUBLIC_DUMPS): a donor, not the trouble.
PUBLIC_FACTORY_DUMPS = {"63e396846f949600c2c1881920860821f2fcd70a70ac79ca54eebaee4a0ccb1c"}   # v012, cleaned

CLEAN_RESTORE = {
    "9b8841d7b0674ca047a67b06594b92e67d17c387b6fab11f4b5191abc644c3ec":
        "candidate C (JH_5408202611050003_0FB74C24.bin: the stock restore file's software with the factory settings record)",
}

WITHDRAWN = {
    "c4822cd7eed63169d80c5105811ab6492444793fafcc7a96e809869f2e5ccda7":
        "candidate A (JH_5408202611050001_0FB74D14.bin: C with camera sounds off)",
    "32a7f54e5090050d099681c33378e3d23822a736279ea78f9f3442a45edc3979":
        "candidate B (JH_5408202611050002_0FB0BD3C.bin: A with silenced sound data)",
}


# --- The sentences the reference lines carry -----------------------------------------------------------------------

NOT_THE_CAMERA = ("a build match says which public image the file was made from. Only a backup of the camera's own chip, "
                  "read before it was ever flashed, says whether it fits")

# The way back without a backup, as verify and stage say it.
WAY_BACK = ("A file for the other sensor still boots, and flashing the stock build of the right base from the card undoes it "
            "(charmera build --base v006 --download --out out/ for an SP140A camera, charmera build --base v012 --download "
            "--out out/ for an OV9732 one). A flash that is cut off, or firmware that won't boot, needs a chip programmer")

NO_BACKUP = ("no backup of this camera was given (--stock), so nothing here compares the file with the camera's own "
             "firmware. Know what this camera runs before you flash it: read its chip (docs/01-FIRST-FLASH.md, step 1) "
             "and give that read as --stock, or run `charmera identify` on its card's photos. " + WAY_BACK)

VECTOR_BROKEN = ("a camera whose reset vector is broken does not start, so the card updater can never run on it again, and "
                 "only a chip programmer brings it back (refusing; no flag accepts it)")

# The name backup and restore keep a read under until a second, identical read confirms it (flash.UNVERIFIED_READ):
# reads that did not agree, and a single read, keep it. Such a read is not a backup.
UNVERIFIED_READ = "unverified-read"


def _table(t: SensorTables) -> str:
    return f"{t.pairs} writes at {t.init[0]:#x}" + (" (OV9732)" if t.sensor == "OV9732" else "") + f", sha256 {t.sha256[:8]}..."


def _for_sensor(t: SensorTables) -> str:
    """Which sensor a table is for, as a phrase."""
    return "the OmniVision OV9732" if t.sensor == "OV9732" else "the SP140A-class sensor"


def _compiled(marks) -> str:
    return f", compiled {marks.compiled.decode('ascii')} at {marks.compiled_offset:#x}" if marks.compiled else ", no compile date found"


def _source_tree(marks, proves: bool = False) -> str:
    """The source-tree folder for a series or donor line; with proves, what the folder proves too."""
    if not marks.source_tree:
        return ", no source tree found"
    return f", source tree {marks.source_tree} at {marks.source_tree_offset:#x}" + (f" ({SOURCE_TREE_PROVES})" if proves else "")


def _several_programs(several: List[SensorTables], in_stock: bool = False) -> Check:
    tables = "; ".join(f"{_table(t)}, referred to from {t.referenced_from:#x}" for t in several)
    if in_stock:
        return Check("sensor-table", False, f"several sensor programs in --stock: its code refers to {len(several)} image-sensor "
                     f"set-up tables with different contents ({tables}). Which one sets up this camera's sensor cannot be told from "
                     "that read, so the file cannot be compared with it (refusing; no flag accepts it)")
    return Check("sensor-table", False, f"several sensor programs: the code refers to {len(several)} image-sensor set-up tables "
                 f"with different contents ({tables}). Which one sets up the sensor cannot be told from the file, so it cannot be "
                 "compared with a camera's (refusing; no flag accepts it)")


def _unverified_read(name: str, flag: str, file_name: Optional[str]) -> List[Check]:
    """A warning when the file given as --stock or --donor has the name of a read that no second read confirmed."""
    if not file_name or UNVERIFIED_READ not in file_name.lower():
        return []
    return [Check(name, True, f"{flag} is {file_name}, a name backup and restore give to reads that did not agree and to a single "
                  "read: such a read is not a backup. A poor clip contact can leave wrong bytes in it that nothing here can see: "
                  "read that chip again until two reads agree (charmera backup) and give that read instead", "warn")]


def _stamp_camera(name_stamp: Optional[str], stock_stamp: Optional[str]) -> List[Check]:
    """The stamp in the name against the stamp of the firmware in the reference, which is the one the camera compares.
    The stamp line already compares the name with the stamp every known build carries."""
    if name_stamp is None or stock_stamp is None or stock_stamp == STOCK_TIMESTAMP:
        return []
    differs = name_stamp != stock_stamp
    return [Check("stamp-camera", differs, f"stamp {name_stamp} in the name " + ("differs from" if differs else "EQUALS")
                  + f" {stock_stamp}, the stamp of the firmware this camera runs (--stock)"
                  + ("" if differs else ", so the camera would ignore the file"))]


def _data_in(data: bytes, start: int, end: int) -> Optional[Tuple[int, int]]:
    """(first, last + 1) of the bytes of data[start:end] that are not 0xFF, or None when there are none."""
    chunk = bytes(data[start:end])
    rest = chunk.lstrip(b"\xff")
    if not rest:
        return None
    return start + len(chunk) - len(rest), start + len(chunk.rstrip(b"\xff"))


def _donor_checks(image: bytes, match: BuildMatch, stock: Optional[bytes], donor: bytes, name_stamp: Optional[str],
                  stock_name: Optional[str] = None, donor_name: Optional[str] = None,
                  allow_modified: bool = False) -> List[Check]:
    """The build, sensor, vector-table and software lines of the donor route, in place of the usual ones. With
    allow_modified the file may be a mod built on the donor's firmware, keeping its boot header, vector table and
    sensor table (a warning)."""
    out: List[Check] = []
    n = len(image)
    if stock is None:
        out.append(Check("backup", True, "no --stock: nothing compares the file with what this camera runs now. The way back is the update "
                         "file this camera was last flashed with, from a card, as long as the new firmware starts far enough to run the "
                         "card updater; if it does not, only a chip programmer can help", "warn"))
    else:
        problem = chip_read_problem(stock, need_sensor_table=False)
        if problem:
            out.append(Check("backup", False, f"--stock is not a complete read of this camera's chip: {problem}"))
            return out
        out.extend(_unverified_read("stock-read", "--stock", stock_name))
    problem = chip_read_problem(donor, need_sensor_table=False)
    if problem:
        out.append(Check("donor", False, f"--donor is not a complete read of a camera's chip: {problem}"))
        return out
    if stock is not None and donor[:SOFTWARE_END] == stock[:SOFTWARE_END]:
        out.append(Check("donor", False, "--donor carries the same firmware as --stock (bytes 0..0x170000 are identical), so the flash "
                         "would change nothing on this camera"))
        return out
    donor_match = detect_build(donor)
    dm = donor_match.marks
    donor_public = public_dump_name(donor)
    # the public v006 build is what the trouble started with; the cleaned v012 is the OV9732 cameras' own factory build
    factory_copy = hashlib.sha256(donor).hexdigest() in PUBLIC_FACTORY_DUMPS
    trouble = bool(donor_public) and not factory_copy
    out.append(Check("donor", True, f"--donor: {donor_match.label}; build string {dm.build_string.decode('ascii', 'replace').strip()!r}, "
                     f"stamp {dm.stamp}{_compiled(dm)}{_source_tree(dm, proves=True)}"
                     + (f"; it is {donor_public} byte for byte, the firmware this camera's trouble started with" if trouble
                        else f"; it is {donor_public} byte for byte" if donor_public else ""),
                     "warn" if trouble else "info"))
    out.extend(_unverified_read("donor-read", "--donor", donor_name))

    span = min(n, SOFTWARE_END)
    if n < OFFICIAL_UPDATE_SIZE:
        out.append(Check("from-donor", False, f"the file ends at {n:#x}, before the end of the resource pack (0x169000); it cannot be a complete update"))
        return out
    if image[:span] != donor[:span]:
        blocks = [hex(b) for b in range(0, span, BLOCK_SIZE) if image[b: min(b + BLOCK_SIZE, span)] != donor[b: min(b + BLOCK_SIZE, span)]]
        if not allow_modified:
            out.append(Check("from-donor", False, f"the file is not --donor's firmware unchanged: bytes 0..{span:#x} differ from it in 64 KiB "
                             f"blocks {blocks}. The donor route takes the donor's firmware as it is: build the file from --donor with pack "
                             "(a mod built on the donor's firmware passes with --allow-modified)"))
            return out
        # A mod built on the donor's firmware: the boot header, the vector table and the image-sensor table (its bytes,
        # where it sits, the code literal that points at it and its bus address) stay the donor's. The program around
        # them may differ, and a different program can drive the same sensor differently: the WARN says so.
        lo, hi = VECTOR_TABLE
        if image[:lo] != donor[:lo]:
            out.append(Check("from-donor", False, f"the file's boot header (0..{lo:#x}) differs from --donor's: a mod built on the donor's "
                             "firmware keeps it, since the boot ROM applies it before any code runs (refusing; no flag accepts it)"))
            return out
        if image[lo:hi] != donor[lo:hi]:
            out.append(Check("from-donor", False, f"the file's ARM vector table ({lo:#x}..{hi:#x}) differs from --donor's: a mod built on the "
                             f"donor's firmware keeps it: {VECTOR_BROKEN}"))
            return out
        donor_sensor = find_marks(donor).sensor
        file_sensor = match.marks.sensor

        def where(s):
            return None if s is None else (s.sha256, s.reset, s.init, s.referenced_from, s.bus_address)

        if where(donor_sensor) != where(file_sensor):
            out.append(Check("from-donor", False, "the file's image-sensor table differs from --donor's (its bytes, where it sits, the code "
                             "literal that points at it or its bus address): a mod built on the donor's firmware keeps the donor's sensor "
                             "set-up (refusing; no flag accepts it)"))
            return out
        changed = sum(1 for a, b in zip(image[:span], donor[:span]) if a != b)
        code_changed = image[:CODE_AREA_END] != donor[:CODE_AREA_END]
        out.append(Check("from-donor", True, f"the file is --donor's firmware with {changed} byte(s) changed in 64 KiB blocks {blocks} "
                         f"(accepted because of --allow-modified); its boot header and vector table (0..{hi:#x}) are the donor's"
                         + ("" if donor_sensor is None else ", and so is its sensor table, still referred to from the same code literal")
                         + ("; the donor's sensor set-up is not of the kind the known builds use, so nothing here checks that the "
                            "changes leave it alone" if donor_sensor is None else "")
                         + (f"; the program (bytes 0..{CODE_AREA_END:#x}) differs from the donor's, and a different program can drive "
                            "the same sensor differently" if code_changed else ""), "warn"))
    else:
        out.append(Check("from-donor", True, f"bytes 0..{span:#x} are byte-identical to --donor: the file is the donor's firmware unchanged"))
    # The donor's vector table is the file's, so it is compared with --stock's and the known builds', as on the normal route.
    stock_marks = find_marks(stock) if stock is not None else None
    out.append(_vector_check(image, match, stock, stock_marks))

    marks = match.marks
    out.append(Check("series", True, f"Charmera firmware: build string {marks.build_string.decode('ascii', 'replace').strip()!r} at "
                     f"{marks.build_offset:#x}{_compiled(marks)}{_source_tree(marks, proves=True)}, stamp {marks.stamp} at "
                     f"{marks.stamp_offset:#x}"))
    out.append(Check("build", True, f"{match.label}; {NOT_THE_CAMERA}", "info" if match.made_from is not None else "warn"))
    out.extend(_stamp_camera(name_stamp, stock_marks.stamp if stock_marks is not None else None))

    ref = stock_marks.sensor if stock_marks is not None else None
    sensor = marks.sensor
    several = marks.several_sensor_programs
    if several:
        out.append(_several_programs(several))
    elif stock_marks is not None and stock_marks.several_sensor_programs:
        out.append(_several_programs(stock_marks.several_sensor_programs, in_stock=True))
    elif sensor is None:
        out.append(Check("sensor-table", True, "the file (the donor's firmware) has no sensor table of the kind the known builds use: it sets "
                         "up its image sensor some other way, perhaps for another kind of sensor. Nothing here can compare it with this "
                         "camera's", "warn"))
    elif ref is None:
        out.append(Check("sensor-table", True, f"the file's sensor table: {_table(sensor)}; "
                         + ("no --stock was given" if stock is None else "--stock has none of the kind the known builds use")
                         + ", so nothing here can compare it with this camera's", "warn"))
    elif ref.sha256 == sensor.sha256:
        out.append(Check("sensor-table", True, f"the file sets up the image sensor exactly as --stock does ({_table(sensor)}). If this camera "
                         "cannot take pictures with the firmware it runs now, the donor's is unlikely to change that: it differs from it only "
                         "elsewhere (software-region line)", "warn"))
    else:
        out.append(Check("sensor-table", True, f"the file's image-sensor set-up differs from --stock's, as a donor's must to change anything: "
                         f"the file has {_table(sensor)}, --stock {_table(ref)}. Nothing here can tell whether the donor's camera has the "
                         "same image sensor and wiring as this one: compare the markings on the two cameras' sensor modules, and even "
                         "matching markings leave other board differences possible", "warn"))

    if stock is None:
        return out
    # A difference the route accepts is a warning, as --allow-modified's is on the normal route.
    blocks = [hex(b) for b in range(0, span, BLOCK_SIZE) if image[b: min(b + BLOCK_SIZE, span)] != stock[b: min(b + BLOCK_SIZE, span)]]
    out.append(Check("software-region", True, f"bytes 0..{span:#x} differ from --stock (this camera now) in 64 KiB blocks {blocks}: the "
                     "donor's firmware replaces this camera's", "warn"))
    stock_public = public_dump_name(stock)
    out.append(Check("way-back", True, "the way back is --stock, this camera's chip as it is now"
                     + (f" (byte-identical to {stock_public}, as a camera flashed with the public restore file reads while its "
                        "settings are unchanged)" if stock_public else "")
                     + ". If the donor's firmware starts far enough to run the card updater, a file made from --stock with pack can be "
                     "flashed from a card; if it does not start, only a chip programmer can put --stock back (docs/02-RECOVERY.md, "
                     "section B)", "warn"))
    return out


def _size_check(n: int, allow_odd_size: bool) -> Check:
    """Two sizes are known to work on the camera, as the app also takes them; every other size is refused."""
    if n == UPDATE_FILE_SIZE:
        return Check("size", True, f"{n} bytes = 30 blocks of 64 KiB, the size the community update files use (writes the settings "
                     "bank and the stored photos too)")
    if n == OFFICIAL_UPDATE_SIZE:
        return Check("size", True, f"{n} bytes = the official size (the film-mods files' size; no official update file has ever been "
                     "published): code, data and the resource pack only; nothing of the camera's own area (settings, stored "
                     "photos) is written", "info")
    if n == FLASH_SIZE:
        why = "the size of a full dump of the chip, not of an update file (see size-2mib)"
    elif n == SOFTWARE_END:
        why = "a software-only image of exactly 0x170000 bytes, from which pack makes the update file, not an update file itself"
    elif n % PAGE_SIZE:
        why = ("not a multiple of 256; the updater programs whole pages from its RAM buffer, so the last page would receive stale "
               "bytes after the file's end")
    elif n < OFFICIAL_UPDATE_SIZE:
        why = ("shorter than 0x169000 (the file would end inside the software or resource area, leaving stale flash there; usually "
               "a cut-off download or copy)")
    elif n < SOFTWARE_END:
        why = "neither the official size 0x169000 nor a software-only 0x170000 image"
    elif n < UPDATE_FILE_SIZE:
        why = ("between the two update sizes: it would write part of the camera's own area (settings, photo directory, stored "
               "photos) and leave the rest as it was, and its final erase lands where neither known size erases (final-erase line)")
    else:
        why = "longer than 0x1E0000" + (" and than the 2 MiB chip" if n > FLASH_SIZE else "")
    return Check("size", allow_odd_size, f"{n} bytes ({n:#x}) is {why}. The camera is known to take two sizes only, {UPDATE_FILE_SIZE:#x} "
                 f"and {OFFICIAL_UPDATE_SIZE:#x} bytes, and pack makes both"
                 + (" (accepted because of --allow-odd-size; stage never accepts it)" if allow_odd_size else ""),
                 "warn" if allow_odd_size else "fail")


def _sensor_checks(image: bytes, marks: BuildMarks, stock: Optional[bytes], stock_marks: Optional[BuildMarks],
                   allow_unknown_build: bool) -> List[Check]:
    """The file's image-sensor program against --stock's when given, else against the known builds'. Outside the
    donor route a table that differs from --stock's, a --stock without one, and a file without one checked against
    --stock are refused whatever the flags."""
    sensor = marks.sensor
    several = marks.several_sensor_programs
    if several:
        return [_several_programs(several)]
    if stock is not None and stock_marks is not None and stock_marks.several_sensor_programs:
        return [_several_programs(stock_marks.several_sensor_programs, in_stock=True)]
    if sensor is None:
        if stock is not None:
            ref = stock_marks.sensor if stock_marks is not None else None
            return [Check("sensor-table", False, "the file has no image-sensor set-up table of the kind the known builds use, "
                          + (f"while --stock has one ({_table(ref)})" if ref is not None else "and --stock has none either")
                          + ": nothing here can tell how the file sets up the camera's image sensor, so it cannot be matched to "
                          "that camera (refusing; no flag accepts it. If this camera's own firmware is gone and the file is "
                          "another, working camera's firmware, that is the donor route, --donor)")]
        return [Check("sensor-table", True, "the file has no image-sensor set-up table of the kind the known builds use, so no sensor "
                      "comparison was possible: nothing here can tell how it sets up the camera's image sensor", "warn")]
    table_where = (f"{sensor.pairs} writes at {sensor.init[0]:#x}..{sensor.init[1]:#x} ("
                   + ("OV9732 bus set-up" if sensor.sensor == "OV9732" else "reset table") + f" at {sensor.reset[0]:#x}"
                   + (f", bus address {sensor.bus_address:#x}" if sensor.bus_address is not None else "")
                   + (f", referenced from code at {sensor.referenced_from:#x}" if sensor.referenced_from is not None else ", no code reference found")
                   + ")")
    no_code = ("; no code refers to it by address, so the code may set the sensor up from a table this check does not see"
               if sensor.referenced_from is None else "")
    if stock is not None:
        ref = stock_marks.sensor if stock_marks is not None else None
        if ref is None:
            return [Check("sensor-table", False, f"the image-sensor set-up table: {table_where}; --stock has none of the kind the known "
                          "builds use to compare it with, so the file cannot be matched to that camera (refusing; no flag accepts it. If "
                          "this camera's own firmware is gone and the file is another, working camera's firmware, that is the donor "
                          "route, --donor)")]
        if sensor.referenced_from is None and ref.referenced_from is not None:
            return [Check("sensor-table", False, f"the file's image-sensor set-up table ({table_where}) is referred to by no code, while "
                          f"--stock's is (from {ref.referenced_from:#x}): the file's code may set the sensor up from a table this check "
                          "does not see (refusing; no flag accepts it)")]
        theirs, ours = sensor_of(stock), sensor_of(image)
        if ref.sha256 == sensor.sha256 and ours and theirs and ours != theirs:
            # The same table, but one side's code carries our OV9732 port: the two drive different sensors.
            # Not a refusal: a file for the other sensor boots, and the stock build of the right base undoes it from the
            # card. This is also what v0.24 built from the own read of an OV9732 camera that was flashed with v006 looks like.
            return [Check("sensor-table", True, f"the image-sensor set-up table is the same as in --stock ({table_where}), but the "
                          f"file is firmware for the {ours} and --stock for the {theirs}: one of them also carries the OV9732 "
                          "driver our port adds (v0.21 to v0.24), so it drives the OV9732 whatever the table says. If --stock "
                          f"shows the sensor this camera has, the camera boots with the file but can't take pictures until you "
                          "flash firmware for its sensor. If --stock is a read of a camera that was flashed with firmware for the "
                          "wrong sensor (an OV9732 camera that ran v006, for example), the file may be the fix", "warn")]
        if ref.sha256 == sensor.sha256:
            same_program = image[:CODE_AREA_END] == stock[:CODE_AREA_END]
            stock_build = match_known_build(stock)
            if stock_build is not None:
                drives = ("the file drives the sensor as the firmware in --stock does, which proves a fit only if --stock was read "
                          "before the camera was ever flashed" if same_program else
                          f"the program around it differs from --stock's (bytes 0..{CODE_AREA_END:#x}), and a different program can "
                          "drive the same sensor differently; even the same program would show a fit only if --stock was read "
                          "before the camera was ever flashed")
                return [Check("sensor-table", True, f"the image-sensor set-up table is the same as in --stock: {table_where}. --stock carries "
                              f"{stock_build.label}, a public build, so this is that build's table: {drives}. A read taken after a "
                              "flash with a file made from a public dump shows what the camera runs now, not what fits it", "warn")]
            if same_program:
                return [Check("sensor-table", True, f"the image-sensor set-up table is the same as in --stock: {table_where}, and so is the "
                              f"program around it (bytes 0..{CODE_AREA_END:#x}): the file drives the image sensor as the firmware in "
                              "--stock does")]
            return [Check("sensor-table", True, f"the image-sensor set-up table is the same as in --stock: {table_where}, but the program "
                          f"around it differs from --stock's (bytes 0..{CODE_AREA_END:#x}), and a different program can drive the same "
                          "sensor differently: this does not show that the file drives the sensor as the firmware in --stock does", "warn")]
        if ours and theirs and ours != theirs:
            sensors = f" The file is firmware for the {ours}, --stock for the {theirs}."
        elif ours == theirs == "OV9732" and sensor.sensor != ref.sensor:
            sensors = " Both are firmware for the OV9732, set up in different ways."
        elif sensor.sensor != ref.sensor:
            sensors = f" The file's table is for {_for_sensor(sensor)}, --stock's for {_for_sensor(ref)}."
        else:
            sensors = ""
        return [Check("sensor-table", False, f"the image-sensor set-up table DIFFERS from --stock's: {table_where}; "
                      f"--stock has {ref.pairs} writes at {ref.init[0]:#x}, sha256 {ref.sha256[:8]}...{sensors} The file drives the sensor "
                      "differently from the firmware in --stock, and a camera whose sensor is not the one a table drives can be expected "
                      "to freeze in photo and video mode after the flash (refusing; no flag accepts it). If this camera's own firmware "
                      "is gone and the file is another, working camera's firmware, that is the donor route (--donor)")]
    if sensor.sensor == "OV9732" and sensor.sha256 in {b.sensor_table_sha256 for b in KNOWN_BUILDS.values()}:
        return [Check("sensor-table", True, f"the image-sensor set-up table is v012's, for the OmniVision OV9732: {table_where}, "
                      f"sha256 {sensor.sha256[:8]}... It fits a camera only if that camera has an OV9732, which nothing in the "
                      "file can tell. `charmera identify` on the camera's photos, or --stock <a dump of the camera this file goes "
                      "into, read before it was ever flashed>, says which sensor it has" + no_code, "warn")]
    if sensor.sha256 in {b.sensor_table_sha256 for b in KNOWN_BUILDS.values()} and sensor_of(image) == "OV9732":
        return [Check("sensor-table", True, f"the image-sensor set-up table is the SP140A one v006 and v008_test carry: {table_where}, sha256 "
                      f"{sensor.sha256[:8]}..., but the code also carries our OV9732 port (v0.21 to v0.24), so it drives the "
                      "OV9732. It fits a camera only if that camera has an OV9732, which nothing in the file can tell. `charmera "
                      "identify` on the camera's photos, or --stock <a dump of the camera this file goes into, read before it was "
                      "ever flashed>, says which sensor it has" + no_code, "warn")]
    if sensor.sha256 in {b.sensor_table_sha256 for b in KNOWN_BUILDS.values()}:
        return [Check("sensor-table", True, f"the image-sensor set-up table is the SP140A one v006 and v008_test carry: {table_where}, sha256 "
                      f"{sensor.sha256[:8]}... It fits a camera only if that camera has an SP140A, which nothing in the file can "
                      "tell; on an OV9732 camera it boots but can't take pictures. `charmera identify` on the camera's photos, or "
                      "--stock <a dump of the camera this file goes into, read before it was ever flashed>, says which sensor it has"
                      + no_code, "warn")]
    return [Check("sensor-table", allow_unknown_build, f"the image-sensor set-up table is not the known builds': {table_where}, sha256 "
                  f"{sensor.sha256[:8]}... No toolkit mod changes this table, so the file was made from a dump of another build. "
                  "Nothing here can tell whether it fits your camera: pass --stock <a dump of this camera's own chip> to compare"
                  + no_code
                  + (" (accepted because of --allow-unknown-build)" if allow_unknown_build
                     else ", or --allow-unknown-build (refusing; stage never accepts it)"),
                  "warn" if allow_unknown_build else "fail")]


def _vector_check(image: bytes, match: BuildMatch, stock: Optional[bytes], stock_marks: Optional[BuildMarks]) -> Check:
    """The ARM vector table, on every route: byte-identical to --stock's passes; laid out like --stock but different
    fails; otherwise it is compared with the known build whose layout the file has (by the hash each known build
    carries), and only a file laid out like none of them is a warning. No flag overrides a failure."""
    lo, hi = VECTOR_TABLE
    where = f"the ARM vector table ({lo:#x}..{hi:#x}, where the camera starts)"
    if len(image) < hi:
        return Check("vector-table", False, f"the file ends at {len(image):#x}, before the end of {where}")
    if stock is not None and len(stock) >= hi:
        if image[lo:hi] == stock[lo:hi]:
            return Check("vector-table", True, f"{where} is byte-identical to --stock's")
        if match.marks.layout is not None and stock_marks is not None and stock_marks.layout == match.marks.layout:
            return Check("vector-table", False, f"{where} differs from --stock's, although the file's build marks lie where --stock "
                         f"has them: {VECTOR_BROKEN}")
    build = match.layout
    if build is not None and build.vector_table_sha256 is not None:
        same = vector_table_sha256(image) == build.vector_table_sha256
        return Check("vector-table", same, f"{where} " + ("is byte-identical to" if same else "differs from") + f" that of {build.label}, "
                     "whose layout the file has" + ("" if same else f": {VECTOR_BROKEN}"))
    return Check("vector-table", True, f"{where} could not be compared: the file's build marks lie where no known build"
                 + (" and not where --stock" if stock is not None else "") + " has them, so there is nothing to compare it with", "warn")


ALLOW_MODIFIED_ACCEPTED = " (accepted because of --allow-modified)"
ALLOW_MODIFIED_HINTS = ("; pass --allow-modified if this is your intended mod",
                        ", and pass --allow-modified if this is your intended mod")


def _software_checks(image: bytes, match: BuildMatch, stock: Optional[bytes], allow_modified: bool) -> List[Check]:
    """The software region against --stock when given (the owner's reference), else against the known builds. A
    difference that --allow-modified (or stage) accepts is a warning, never a pass."""
    n = len(image)
    span = min(n, SOFTWARE_END)
    if n < OFFICIAL_UPDATE_SIZE:
        return [Check("software-region", False, f"the file ends at {n:#x}, before the end of the resource pack (0x169000); it cannot be a complete update")]
    accepted = ALLOW_MODIFIED_ACCEPTED if allow_modified else ALLOW_MODIFIED_HINTS[0]
    if stock is not None:
        out: List[Check] = []
        if image[:span] == stock[:span]:
            out.append(Check("software-region", True, f"bytes 0..{span:#x} are byte-identical to --stock"))
        else:
            # Each block only as far as the file goes: an official-size file ends inside the block at 0x160000.
            blocks = [b for b in range(0, span, BLOCK_SIZE) if image[b: min(b + BLOCK_SIZE, span)] != stock[b: min(b + BLOCK_SIZE, span)]]
            in_res = [b for b in blocks if b >= RESOURCE_BASE]
            in_code = [b for b in blocks if b < RESOURCE_BASE]
            out.append(Check("software-region", allow_modified, f"bytes 0..{span:#x} differ from --stock in 64 KiB blocks "
                             f"{[hex(b) for b in blocks]} (code/table blocks: {len(in_code)}, resource blocks: {len(in_res)})" + accepted,
                             "warn" if allow_modified else "fail"))
        data = _data_in(stock, span, SOFTWARE_END)
        if data:
            out.append(Check("own-data", True, f"--stock holds data at {data[0]:#x}..{data[1]:#x}, after the end of the file and before the "
                             f"settings bank (the factory self-test sector starts at 0x16E000): the update leaves {span:#x}..0x170000 erased, "
                             "so the camera loses it. A 0x1E0000-byte file made from the backup keeps it", "warn"))
        return out
    if match.exact:
        return [Check("software-region", True, f"bytes 0..0x169000 are byte-identical to {match.build.label} ({match.build.status})"
                      + (" and 0x169000..0x170000 are blank, as in the public dump" if n >= SOFTWARE_END and image[OFFICIAL_UPDATE_SIZE:SOFTWARE_END] == b"\xff" * (SOFTWARE_END - OFFICIAL_UPDATE_SIZE) else ""))]
    known = match.made_from
    detail = "the software region differs from every known build" if known is None else f"the software region differs from {known.label}"
    return [Check("software-region", allow_modified, detail + "; give --stock <your reference image> to see which 64 KiB blocks"
                  + (ALLOW_MODIFIED_ACCEPTED if allow_modified else ALLOW_MODIFIED_HINTS[1]),
                  "warn" if allow_modified else "fail")]


def _reference_checks(image: bytes, match: BuildMatch, stock: Optional[bytes], name_stamp: Optional[str], allow_modified: bool,
                      allow_unknown_build: bool, stock_name: Optional[str] = None) -> List[Check]:
    """The backup, build, sensor, vector-table and software lines outside the donor route."""
    out: List[Check] = []
    marks = match.marks
    stock_marks: Optional[BuildMarks] = None
    if stock is None:
        out.append(Check("backup", True, NO_BACKUP, "warn"))
    else:
        problem = chip_read_problem(stock, need_sensor_table=False)
        stock_public = public_dump_name(stock)
        if problem:
            out.append(Check("stock", False, f"--stock is not a complete read of a camera's chip: {problem}. It cannot stand for the "
                             "camera the file goes into, so nothing below compares the file with that camera: give the 2 MiB backup read "
                             "from its chip (docs/01-FIRST-FLASH.md, step 1)"))
        elif stock_public:
            out.append(Check("stock", True, f"--stock is byte-identical to {stock_public}. A camera flashed with the public restore "
                             "file reads exactly like this while its settings are unchanged; if this is not a read of the camera the "
                             "file goes into, the comparisons below say nothing about that camera. And a read taken after a flash "
                             "shows what the camera runs now, not what fits it", "warn"))
        out.extend(_unverified_read("stock-read", "--stock", stock_name))
        stock_marks = find_marks(stock)
        out.extend(_stamp_camera(name_stamp, stock_marks.stamp))
    if not match.charmera:
        out.append(Check("series", allow_unknown_build, "no Charmera build marks found (" + ", ".join(marks.missing()) + " missing): cannot "
                         "tell whether this is Charmera firmware at all"
                         + (" (accepted because of --allow-unknown-build)" if allow_unknown_build
                            else " (refusing; use --allow-unknown-build only with your own dump of the camera it goes into)"),
                         "warn" if allow_unknown_build else "fail"))
        if not allow_unknown_build:
            return out
    else:
        out.append(Check("series", True, f"Charmera firmware: build string {marks.build_string.decode('ascii', 'replace').strip()!r} at "
                         f"{marks.build_offset:#x}{_compiled(marks)}{_source_tree(marks)}, stamp {marks.stamp} at {marks.stamp_offset:#x} "
                         "(the stamp is shared by every known build, so it identifies none of them; their compile dates differ, and so do "
                         "their source trees. A different date or tree shows a different build, the same one does not show the same "
                         "build, and none of these marks says whether the file fits a camera)"))
        known = match.made_from
        if known is not None:
            status = f"{known.status} fingerprint" + (", exact" if not match.modified else ", with changes")
            out.append(Check("build", True, f"{match.label} ({status}); {NOT_THE_CAMERA}", "info"))
        else:
            out.append(Check("build", True, f"{match.label}; it belongs only in the camera it was dumped from, or in one with the same "
                             "parts; " + NOT_THE_CAMERA, "warn"))
    out.extend(_sensor_checks(image, marks, stock, stock_marks, allow_unknown_build))
    out.append(_vector_check(image, match, stock, stock_marks))
    out.extend(_software_checks(image, match, stock, allow_modified))
    return out


def verify_update(path: Path, image: bytes, stock: Optional[bytes] = None, allow_modified: bool = False,
                  allow_unknown_build: bool = False, allow_odd_size: bool = False,
                  donor: Optional[bytes] = None, stock_name: Optional[str] = None,
                  donor_name: Optional[str] = None) -> List[Check]:
    """Every check, in the order printed. stock_name and donor_name are the file names --stock and --donor were read from,
    for the warning about a read that no second read confirmed."""
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
                            "the stamp every known build carries"
                            + ("" if name.stamp != STOCK_TIMESTAMP else " (a camera running any of them would ignore the file)")))
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
    checks.append(_size_check(n, allow_odd_size))
    extra = updater_extra_erase(n)
    what = describe_erase_target(extra)
    harmless = 0x1CB000 <= extra < 0x200000
    checks.append(Check("final-erase", harmless or not (extra >= 0x200000), f"after programming, the updater erases the 64 KiB block at {extra:#x}: {what}",
                        "info" if harmless else ("fail" if extra >= 0x200000 else "warn")))
    if n == 0x200000:
        checks.append(Check("size-2mib", False, "a full 2 MiB file: never put one on a card (the final erase wraps to block 1 and bricks the camera). Trim with pack."))
    checks.append(Check("magic", image[:4] == SPII_MAGIC, "starts with 'SPII'" if image[:4] == SPII_MAGIC else f"first bytes {image[:4]!r}, not 'SPII' (the camera does not check this, but it would not boot)"))

    # 3. A withdrawn candidate is refused by its hash, whatever else it passes.
    sha = hashlib.sha256(image).hexdigest()
    withdrawn = WITHDRAWN.get(sha)
    if sha in CLEAN_RESTORE:
        checks.append(Check("withdrawn", True, f"this is {CLEAN_RESTORE[sha]}: the same bytes as the stock restore file made "
                            "from the public dump's cleaned copy (the-dumping-grounds/charmera-firmware-v006). C was withdrawn "
                            "after the hardware test of 2026-11-06, when a camera flashed with it froze in photo and video mode, "
                            "and so did one flashed with the stock restore file: it drives only the SP140A-class sensor "
                            "(docs/03-FLASH-CANDIDATES.md)", severity="info"))
    if withdrawn:
        checks.append(Check("withdrawn", False, f"this is {withdrawn}, withdrawn after the hardware test of 2026-11-06: it is made from "
                            "the public dump, and no file made from it is known to fit a camera whose own firmware has not been read. A "
                            "camera flashed with C froze in photo and video mode (docs/03-FLASH-CANDIDATES.md). Do not flash it "
                            "(refusing; no flag accepts it)"))

    # 4. Build identity, read from the content, and the comparisons with the reference. The stamp is not an
    # identity: two builds share it.
    if donor is not None:
        checks.extend(_donor_checks(image, match, stock, donor, name_stamp, stock_name, donor_name, allow_modified))
    else:
        checks.extend(_reference_checks(image, match, stock, name_stamp, allow_modified, allow_unknown_build, stock_name))

    # 5. What the file carries into the camera's own area, as the camera holds it afterwards: the updater erases the
    # block a file ends in and programs it only up to the file's end, so the rest of that block reads 0xFF.
    held = image + b"\xff" * (-n % BLOCK_SIZE) if n > SETTINGS_BASE else image
    if n > SETTINGS_BASE:
        recs = settings_mod.parse_settings(held)
        valid = [r for r in recs if r.crc_ok]
        if not recs:
            checks.append(Check("settings", True, "settings store blank: the camera will create defaults (INFERRED from emulation, not hardware-tested)", "info"))
        else:
            try:
                cur = settings_mod.current_record(held)
                checks.append(Check("settings", True, f"{len(recs)} settings records ({len(valid)} valid); the camera will load the one at {cur.offset:#x}: UI sound {cur.ui_sound}, playback volume {cur.playback_volume}, auto-off code {cur.raw[settings_mod.OFF_AUTO_OFF]}", "info"))
            except ValueError as exc:
                checks.append(Check("settings", True, f"{len(recs)} settings records but {exc}", "warn"))
        consistent = settings_mod.photo_directory_consistent(held)
        checks.append(Check("photo-directory", consistent,
                            "photo directory sector is consistent with the settings state" if consistent
                            else "a valid settings record exists but the photo directory sector at 0x17F000 is 0xFF"
                            + (" after the flash (the file ends inside that block, and the camera holds 0xFF from the file's end)"
                               if len(held) > n else "")
                            + "; the camera would read two bogus photo entries. Zero that sector (pack does this)."))
        photos = stored_photos(held)
        listed = settings_mod.photo_directory_lists(held)
        detail = f"{len(photos)} photo(s) stored in the image"
        if photos:
            detail += (f" ({photos_whose(held)}); the photo directory lists none of them, so the camera will not show them" if listed == 0
                       else f" ({photos_whose(held)}); the photo directory lists {listed}")
        checks.append(Check("photos", True, detail, "info" if not photos else "warn"))
    elif n >= OFFICIAL_UPDATE_SIZE:
        if SETTINGS_BASE <= extra < 0x180000:
            own = "the camera's own settings are erased by the final erase (see above) and its stored photo data is left in place"
        else:
            own = f"the camera's own settings stay as they are, and the final erase hits the block at {extra:#x} instead (see above)"
        checks.append(Check("own-area", True, "the file stops before the settings bank: no settings record and no stored photo of another camera is "
                            "written; " + own, "info"))

    # 6. Resource pack sanity (only when the whole pack can be present).
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
    return reviewed_build(image, checks, stock=stock, donor=donor)


# What a firmware image drives (image.sensor_of): the OV9732 context wins over the SP140A table, so a v006 build with
# our port counts as OV9732.


# The lines a reviewed modded build fails only because it is ours and not stock: no stock build marks, a software
# region changed on purpose, a sensor table that is not the SP140A one. A camera with the other sensor gets its own
# loud wrong-sensor warning instead (not a refusal: such a flash is undone from the card). Never the donor route's own
# refusals, never size, name, checksum, magic or the file's vector table. The same for a plain stock file (no mods).
REVIEWED_WARN_ONLY = ("series", "build", "software-region", "sensor-table")


def reviewed_build(image: bytes, checks: List[Check], stock: Optional[bytes] = None,
                   donor: Optional[bytes] = None) -> List[Check]:
    """A file that is one of the reviewed recipe builds (charmera_toolkit/recipes.json, by SHA-256), stock files
    included: say which one, warn when nothing confirms what the camera runs (no --stock, no --donor), warn loudly
    when the backup (or the donor) is firmware for the other sensor, and turn the failures it has only for being a
    known build that is not this camera's into warnings, as the app does. Whether it fits the camera is the owner's
    call; every file-level check (size, checksum, vector table, donor route) still fails as for any file."""
    from . import recipes
    try:
        row = recipes.reviewed(hashlib.sha256(image).hexdigest())
        if row is None:
            return checks
        label = recipes.label(row["base"], row["mods"])
        status = recipes.set_for(row["base"], row["mods"])["status"]
    except (OSError, ValueError, KeyError):
        return checks
    drives = sensor_of(image)
    probe = any(m.endswith("/sensor-probe") for m in row["mods"])     # the probe is for finding out the sensor
    out = [Check("reviewed", True, f"this is a reviewed file from charmera build: {label}, made from {row['dump']} "
                 f"({row['name']}; {status}" + ("" if status == "tested" else ": not yet tried on a camera") + ")"
                 + (f"; it drives the {drives}" if drives and not probe else ""), "info")]
    if stock is None and donor is None and not probe:
        out.append(Check("unconfirmed", True, "not compared with a backup, so nothing here confirms which firmware the "
                         f"camera runs. This build drives the {drives or 'sensor of its base'}; on a camera with the "
                         "other sensor it boots but cannot take pictures, until it is flashed with firmware that fits. "
                         "charmera identify (from the camera's photos) or --stock with its backup confirms it", "warn"))
    for what, ref in (("--stock", stock), ("--donor", donor)):
        if ref is None or probe or drives is None:
            continue
        theirs = sensor_of(ref)
        if theirs and theirs != drives:
            out.append(Check("wrong-sensor", True, f"{what} is firmware for the {theirs}, and this build drives the "
                             f"{drives}. If {what} is this camera's chip as it left the factory, this build cannot take "
                             "pictures on it (the camera boots and the card updater still works, so it can be flashed "
                             "back). If it was read after a flash with the wrong firmware, it says nothing about the "
                             "sensor: check that with charmera identify (photos from before the flash) or the "
                             "sensor-ID probe", "warn"))
        if what == "--stock" and ref[0x200:0x240] != image[0x200:0x240]:
            out.append(Check("vector-table-stock", True, "the ARM vector table (0x200..0x240) differs from --stock's: "
                             "this build keeps its own base's, so the backup is of a camera running another build",
                             "warn"))
    for c in checks:
        failed = not c.ok and c.severity == "fail"
        accepted = c.severity == "warn" and ALLOW_MODIFIED_ACCEPTED in c.detail
        if c.name in REVIEWED_WARN_ONLY and (failed or accepted):
            # A reviewed build needs no --allow-modified: drop the flag's hint, or the note that it accepted the line.
            detail = c.detail.replace(" (refusing;", " (not refused for this reviewed build;")
            for hint in ALLOW_MODIFIED_HINTS + (ALLOW_MODIFIED_ACCEPTED,):
                detail = detail.replace(hint, "")
            out.append(Check(c.name, True, detail + " (a reviewed build: this is expected)", "warn"))
        else:
            out.append(c)
    return out


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
    checks = verify_update(path, image, stock, args.allow_modified, args.allow_unknown_build, args.allow_odd_size, donor=donor,
                           stock_name=Path(args.stock).name if args.stock else None,
                           donor_name=Path(args.donor).name if donor is not None else None)
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
        what = ("a mod built on another camera's firmware" if image[:SOFTWARE_END] != donor[:SOFTWARE_END]
                else "another camera's firmware")
        print(f"        The file is {what}. Whether it fits this camera is not known: see the sensor-table and")
        print("        " + ("backup lines above." if stock is None else "way-back lines above."))
    elif stock is None:
        print("        No --stock was given, so nothing above compares the file with that camera's own firmware.")
    if donor is None:
        print("        Know what that camera runs before you flash it (a backup of its chip, or `charmera identify`).")
        print("        A file for the other sensor still boots, and flashing the stock build of the right base from the")
        print("        card undoes it (charmera build --base v006 --download --out out/ for an SP140A camera, charmera")
        print("        build --base v012 --download --out out/ for an OV9732 one). A flash that is cut off, or firmware")
        print("        that won't boot, needs a chip programmer.")
    return 0


def register(subparsers) -> None:
    p = subparsers.add_parser("verify", help="check an update file's name, checksum, size and build before it goes near a card")
    p.add_argument("file")
    p.add_argument("--stock", help="a complete 2 MiB read of the chip of the camera this file goes into (your backup): the sensor table, "
                                   "the vector table and the software region are compared with it. Only a read made before the camera was "
                                   "ever flashed says whether a firmware fits it")
    p.add_argument("--allow-modified", action="store_true",
                   help="accept a software region that differs from the reference (that line then shows WARN); never a changed vector table. "
                        "With --donor, accept a mod built on the donor's firmware that keeps its boot header, vector table (0..0x240) and "
                        "sensor table (stage always does)")
    p.add_argument("--allow-unknown-build", action="store_true",
                   help="accept a file without build marks, or, without --stock, one whose image-sensor table is not the known builds' "
                        "(that line then shows WARN); stage never accepts either. It never accepts a table that differs from --stock's, "
                        "a --stock without one, or a file without one checked against --stock: only the donor route (--donor) takes those")
    p.add_argument("--allow-odd-size", action="store_true",
                   help="accept a size other than 0x1E0000 and 0x169000 bytes, the two the camera is known to take (shown as WARN); stage never accepts one")
    p.add_argument("--donor", help="the donor route: a read of another, working camera's chip whose firmware the file is (or, with "
                                   "--allow-modified, a mod built on it), for a camera whose "
                                   "own firmware is gone and does not fit it; --stock (this camera's own read as it is now) is optional")
    p.set_defaults(func=handler)
