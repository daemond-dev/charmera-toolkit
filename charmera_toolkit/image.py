"""Reading a Charmera flash image: header, flash map, build identity, resources, sounds, settings, photos.

Everything here is read-only and works on a full 2 MiB dump, a 0x1E0000-byte update file, an official-size
update file of 0x169000 bytes, or a software-only image of exactly 0x170000 bytes (the layout of the first
0x169000 bytes is the same in all four). Any other length below 0x1E0000 is reported as 'truncated'.

Build identity is read from the content, never from a fixed offset, and the version stamp is not treated
as an identity: see the "Known builds" section.

Status labels in comments: VERIFIED = checked against both public dumps and the firmware code by the
research pass; INFERRED = consistent with the evidence but not proven on hardware; UNKNOWN = guess.
"""
from __future__ import annotations

import hashlib
import re
import struct
from collections import Counter
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from . import settings as settings_mod
from .checksum import BLOCK_SIZE, FLASH_SIZE, OFFICIAL_UPDATE_SIZE, UPDATE_FILE_SIZE, byte_sum

# --- Fixed layout (file offsets) ----------------------------------------------------------------------
HEADER_SIZE = 0x200          # VERIFIED: 'SPII' header, ARM vector table starts at 0x200
VA_OFFSET = 0x200            # VERIFIED: file offset = runtime address + 0x200
# VERIFIED: the eight ARM exception vectors, the handler addresses they load and the reset entry's first instruction
# at 0x23C. A camera whose reset vector is broken does not start, so the card updater cannot run on it again.
VECTOR_TABLE = (0x200, 0x240)
CODE_END = 0x110000          # INFERRED: code and tables; the 64 KiB at 0x110000 is zero (heap placeholder)
RESOURCE_BASE = 0x120000     # VERIFIED: 'GP+' resource pack
RESOURCE_SECTOR = 0x200      # VERIFIED: directory entries address 512-byte sectors from RESOURCE_BASE
RESOURCE_LIMIT = 0x16E000    # VERIFIED: the factory self-test sector starts here; verify ends the last entry's slot at it
SOFTWARE_END = 0x170000      # VERIFIED: everything below is identical in both public dumps
SETTINGS_BASE = settings_mod.SETTINGS_BASE
PHOTO_INDEX = 0x17F000       # VERIFIED: 8-byte entries describing the internally stored photos
PHOTO_SLOTS = (0x180000, 0x1A5800)   # VERIFIED in both dumps (two slots, 0x25800 apart)
PHOTO_SLOT_SIZE = 0x25800
USER_AREA_END = 0x1E0000     # VERIFIED: 0x1E0000..0x200000 is 0xFF in every known dump

SPII_MAGIC = b"SPII"

# --- Known builds -------------------------------------------------------------------------------------
#
# What identifies a firmware build, and what does not. The 12-digit stamp the updater compares against
# (`202505201029`) is carried by at least two different builds: the public dump and the build on the
# charmera-film-mods author's camera (its code and resources are public as that project's restore file).
# The build string `JH5408  20250520`, the yellow boot screen and the "original series" body do not tell
# them apart. The compile date does: `Oct 14 2025` in the public dump, `Nov 19 2025` in the film-mods
# build, so the factory rebuilt the firmware under one stamp. A different compile date shows a different
# build; the same date would not show the same build. The folder the firmware was built in is another such
# mark: the source paths compiled into the code ("G:\code\2247\5408\v006\host\...") name it, v006 in the
# public dump and v008_test in the film-mods build (27 paths in each, VERIFIED). A different folder shows a
# different build; the same folder does not show the same build. A fingerprint over the software names a
# build exactly, and even that says which image a file was made from, not whether it fits the camera it goes
# into: the two known builds drive the same sensor with the same 61-write table, and a camera fitted with
# another sensor needs a build nobody has shared. None of these marks says whether a file fits a camera.

CODE_AREA_END = 0x120000          # boot header, code, read-only and initialised data, and the padding below the pack


@dataclass(frozen=True)
class KnownBuild:
    key: str
    label: str
    source: str
    status: str                    # VERIFIED: hashed here from a public file. REPORTED: fingerprint published by its owner.
    stamp: str                     # the 12-digit stamp compiled into it (the same in both known builds)
    official_sha1: str             # SHA-1 of bytes 0..0x169000 (the span the official-size files write)
    official_byte_sum: int         # the camera's own checksum over the same span
    sensor_table_sha256: str       # SHA-256 of the 124-byte sensor init table (identical in both known builds)
    software_sha256: Optional[str] = None   # SHA-256 of bytes 0..0x170000, when the dump is public
    code_sha256: Optional[str] = None       # SHA-256 of bytes 0x200..0x100000 (code and read-only data)
    official_sha256: Optional[str] = None   # SHA-256 of bytes 0..0x169000
    # Where the marks lie in that build's image, for telling a modified copy of it apart from another build.
    # None = not published for that build.
    stamp_offset: Optional[int] = None
    build_offset: Optional[int] = None
    sensor_table_offset: Optional[int] = None
    compiled: Optional[str] = None          # the compile date the build carries (it differs between the known builds)
    vector_table_sha256: Optional[str] = None   # SHA-256 of bytes 0x200..0x240 (VECTOR_TABLE)
    source_tree: Optional[str] = None       # the folder its source paths name (it differs between the known builds)

    @property
    def layout(self) -> Optional[Tuple[int, int, int]]:
        """(stamp, build string, sensor table) offsets, when all three are published for the build."""
        if self.stamp_offset is None or self.build_offset is None or self.sensor_table_offset is None:
            return None
        return self.stamp_offset, self.build_offset, self.sensor_table_offset


PUBLIC_BUILD = KnownBuild(
    key="public-dump",
    label="the public dump's build (benbadio/keychain-camera-plus and Khimira/Project-Sirius dumps, stamp 202505201029)",
    source="benbadio/keychain-camera-plus dumps/charmera_stock_latest.bin at commit 209c25a; Khimira/Project-Sirius Original_Firmware.bin",
    status="VERIFIED",
    stamp="202505201029",
    official_sha1="d1f21227ab402bc842134070acc29a77041b4a63",
    official_byte_sum=0x0852B369,
    sensor_table_sha256="aea3976bdf7ba2e747d8b56141f7ef0b4e3262481540dc2869457bc814fcb755",
    software_sha256="fe88e8de57e7b1ff1a0856127ee09a21fb3ce2dde2684662c533719b80adc5c8",
    code_sha256="297506be352e2efb0e9231936197c0798f2633dd27a005ae64a0b3ad547d33f6",
    official_sha256="bffddd53d01a42725d63aa08dff5585433604a680a85520b23f3e176ae80f745",
    stamp_offset=0x218C4,
    build_offset=0x18964,
    sensor_table_offset=0xF5C6C,
    compiled="Oct 14 2025",
    vector_table_sha256="cf38b73deaf45d2c525aeba7778cae7d7924baed925fa535c2923f132eb659cd",
    source_tree="v006",
)

FILM_MODS_BUILD = KnownBuild(
    key="film-mods",
    label="the charmera-film-mods author's build (same stamp 202505201029, different bytes, compiled Nov 19 2025)",
    source=("Hyperlite-Media/charmera-film-mods release Charmera_Restore, JH_5408202505201029_0852C329.bin: bytes 0..0x169000 of "
            "the author's factory dump, with the SHA-1 and byte sum the author reported in issue #1 (2026-11-06)"),
    status="VERIFIED",
    stamp="202505201029",
    official_sha1="91499bd3bff3acde053cb8c8255a73cdeb6e3d33",
    official_byte_sum=0x0852C329,
    sensor_table_sha256="aea3976bdf7ba2e747d8b56141f7ef0b4e3262481540dc2869457bc814fcb755",
    code_sha256="8f9d8c7eb883765bb93d5c76469110cc500d1b15d5cbbd20f58fdddcef6359a8",
    official_sha256="5b352e46f1f52175cb4da2278be8db32ecc36be4943424d58cfd8e2cc9c4386f",
    stamp_offset=0x218D0,
    build_offset=0x1897C,
    sensor_table_offset=0xF5D34,
    compiled="Nov 19 2025",
    vector_table_sha256="cf38b73deaf45d2c525aeba7778cae7d7924baed925fa535c2923f132eb659cd",   # the same bytes as the public build's
    source_tree="v008_test",
)

KNOWN_BUILDS = {b.key: b for b in (PUBLIC_BUILD, FILM_MODS_BUILD)}

# The stamp both known builds carry. A file name with this stamp is ignored by a camera running either build.
SHARED_STAMP = "202505201029"

# Public full dumps, for reference only. Never redistributed by this toolkit.
PUBLIC_DUMPS: Dict[str, str] = {
    "cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f":
        "benbadio/keychain-camera-plus dumps/charmera_stock_latest.bin (the public dump's build)",
    "720ed0b65c91b149b0916498732b0ecf40227736ef5e3c126c487a7b5253436c":
        "Khimira/Project-Sirius Original_Firmware.bin (the public dump's build)",
}

# The two photos the camera behind the keychain-camera-plus dump stored in its own memory (at 0x180000 and 0x1A5800),
# by the SHA-256 of each JPEG from FF D8 to FF D9. Every 0x1E0000-byte file made from that dump, the stock restore
# file among them, carries them, so a camera flashed with one holds them afterwards. VERIFIED from the dump; the
# Project-Sirius dump's photos differ and are not hashed here.
PUBLIC_DUMP_PHOTOS: Dict[str, str] = {
    "ec268b506e54743c2f148bde5190313ab4277b629b4965bffc08775bc882dd79": "benbadio/keychain-camera-plus",
    "f5105d39cf35d5a0549fbe2e5420bfe9bc6f0fdfce2edd36a976f2f31059ee0e": "benbadio/keychain-camera-plus",
}

# --- Header -------------------------------------------------------------------------------------------


@dataclass
class SpiiHeader:
    magic: bytes
    words: List[int]
    reg_init: List[tuple]      # INFERRED: (register, value) pairs at 0x28..0x48

    @property
    def magic_ok(self) -> bool:
        return self.magic == SPII_MAGIC


def parse_header(image: bytes) -> SpiiHeader:
    """Decode what is known of the 512-byte boot header. Field meanings beyond the magic are INFERRED."""
    if len(image) < HEADER_SIZE:
        raise ValueError(f"image is {len(image)} bytes, shorter than the 512-byte boot header")
    words = [struct.unpack_from("<I", image, i)[0] for i in range(0, 0x50, 4)]
    pairs = []
    for i in range(0x28, 0x48, 8):
        reg, val = struct.unpack_from("<II", image, i)
        if reg:
            pairs.append((reg, val))
    return SpiiHeader(magic=bytes(image[:4]), words=words, reg_init=pairs)


# --- Build marks, found by content ----------------------------------------------------------------------
#
# Nothing here reads a fixed offset. The marks move between builds (the stamp is at 0x218C4 in the public
# dump and 0x218D0 in the film-mods build; the sensor table at 0xF5C6C and 0xF5D34), so each one is found by
# what it looks like. Every sensor program in the code area is collected, with the code literal that holds its
# init table's address when there is one: verify refuses a file whose code refers to more than one program, and
# warns about a table no code refers to.

# The sensor's reset table: page select (register 0xFD = 0), register 0x20 = 0, end marker. VERIFIED in the
# public dump at 0xF5BFC, confirmed at +0xC8 in the film-mods build by its author.
SENSOR_RESET_TABLE = bytes.fromhex("fd002000ffff")
SENSOR_TABLE_END = b"\xff\xff"
SENSOR_PAGE_REGISTER = 0xFD
_STAMP_RE = re.compile(rb"(?<![0-9])([0-9]{12})\x00")
_BUILD_STRING_RE = re.compile(rb"JH5408 +[0-9]{8}")
_COMPILED_RE = re.compile(rb"(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) [ 0-9][0-9] 20[0-9]{2}(?=\x00)")
_UPDATE_PATTERN = b"*.bin\x00"
# The folder after "\2247\5408\" in the source file paths compiled into the code, with the backslashes of the
# factory's Windows paths: VERIFIED 27 times in each known build, all in the code area.
_SOURCE_TREE_RE = re.compile(rb"\\2247\\5408\\([^\\\x00-\x20\x7f-\xff]{1,64})\\")


@dataclass(frozen=True)
class SensorTables:
    reset: tuple               # (start, end) file offsets of the reset table
    init: tuple                # (start, end) file offsets of the init table, end marker included
    pairs: int                 # (register, value) writes in the init table, end marker excluded
    sha256: str                # of the init table bytes, end marker included
    bus_address: Optional[int]  # the write address the config struct after the reset table names (0x78 here); INFERRED
    referenced_from: Optional[int]  # file offset of the code literal holding the init table's RAM address, or None

    @property
    def init_bytes_len(self) -> int:
        return self.init[1] - self.init[0]


@dataclass(frozen=True)
class BuildMarks:
    stamp: Optional[str] = None
    stamp_offset: Optional[int] = None
    build_string: Optional[bytes] = None
    build_offset: Optional[int] = None
    compiled: Optional[bytes] = None
    compiled_offset: Optional[int] = None
    sensor: Optional[SensorTables] = None
    sensor_programs: Tuple[SensorTables, ...] = ()   # every reset and init table pair found, in file order
    stamp_conflict: Tuple[str, ...] = ()             # the different stamps that follow '*.bin', when they disagree
    # The folder the firmware was built in (source_tree_marks); not one of the marks `complete` asks for.
    source_tree: Optional[str] = None
    source_tree_offset: Optional[int] = None         # where that folder name first appears
    source_tree_paths: int = 0                       # how many source paths name it

    @property
    def complete(self) -> bool:
        """The three marks every Charmera build is expected to carry."""
        return self.stamp is not None and self.build_string is not None and self.sensor is not None

    @property
    def several_sensor_programs(self) -> List[SensorTables]:
        """The sensor programs the code refers to, when they are more than one with different init tables; else
        empty. Which of them sets up the sensor cannot be told from the file."""
        referenced = [t for t in self.sensor_programs if t.referenced_from is not None]
        return referenced if len({t.sha256 for t in referenced}) > 1 else []

    @property
    def layout(self) -> Optional[Tuple[int, int, int]]:
        """(stamp, build string, sensor table) offsets, when all three marks were found."""
        if not self.complete:
            return None
        return self.stamp_offset, self.build_offset, self.sensor.init[0]

    def missing(self, need_sensor_table: bool = True) -> List[str]:
        """The marks that were not found, in plain words."""
        out = []
        if self.build_string is None:
            out.append("build string")
        if self.stamp is None:
            out.append("12-digit stamp" + (f" (several different ones follow '*.bin': {', '.join(self.stamp_conflict)})"
                                           if self.stamp_conflict else ""))
        if need_sensor_table and self.sensor is None:
            out.append("sensor table")
        return out


def _register_table_end(image: bytes, start: int, limit: int = 2048) -> Optional[int]:
    """End (exclusive, marker included) of a (register, value) pair table that starts at `start`, or None."""
    p = start
    stop = min(len(image) - 1, start + limit)
    while p < stop:
        if image[p: p + 2] == SENSOR_TABLE_END:
            return p + 2
        p += 2
    return None


def _literal_offset(image: bytes, ram_address: int, end: int) -> Optional[int]:
    """File offset of a little-endian word holding ram_address in the code area, or None. Only 4-byte-aligned
    words count: the code loads an address from a literal pool, whose words are aligned, and the same four bytes
    found elsewhere are a coincidence."""
    needle = struct.pack("<I", ram_address)
    i = image.find(needle, HEADER_SIZE, end)
    while i >= 0 and i % 4:
        i = image.find(needle, i + 1, end)
    return None if i < 0 else i


def sensor_programs(image: bytes) -> List[SensorTables]:
    """Every sensor reset table in the code area with the init table that follows it, in file order.

    The reset table is found by its exact bytes. The init table is the first (register, value) table after
    it that starts with a page select, holds at least 16 writes, switches page at least once more and ends
    with the ff ff marker. Both must lie in the code area. Each one carries the code literal that holds its
    init table's RAM address (file offset - 0x200), or None when no aligned word does.
    """
    end = min(len(image), CODE_AREA_END)
    found_all = []
    i = image.find(SENSOR_RESET_TABLE, HEADER_SIZE, end)
    while 0 <= i < end:
        reset = (i, i + len(SENSOR_RESET_TABLE))
        p = reset[1]
        found = None
        # p + 2 <= ...: a file may end right after a page register, and the pair needs both of its bytes
        while p + 2 <= min(end, reset[1] + 0x400):
            if image[p] == SENSOR_PAGE_REGISTER and image[p + 1] == 0:
                e = _register_table_end(image, p)
                if e is not None and e <= end:
                    pairs = (e - p) // 2 - 1
                    pages = sum(1 for k in range(p + 2, e - 2, 2) if image[k] == SENSOR_PAGE_REGISTER)
                    if pairs >= 16 and pages >= 1:
                        found = (p, e, pairs)
                        break
            p += 2
        if found:
            s, e, pairs = found
            ref = _literal_offset(image, s - VA_OFFSET, end)
            bus_at = (reset[1] + 3) & ~3
            bus = struct.unpack_from("<I", image, bus_at)[0] if bus_at + 4 <= len(image) else None
            found_all.append(SensorTables(reset, (s, e), pairs, hashlib.sha256(image[s:e]).hexdigest(),
                                          bus if bus is not None and 0 < bus < 0x100 else None, ref))
        i = image.find(SENSOR_RESET_TABLE, i + 1, end)
    return found_all


def _pick_sensor_program(programs: List[SensorTables]) -> Optional[SensorTables]:
    """The one the code refers to by address wins; the first in the file when none is referred to."""
    referenced = [c for c in programs if c.referenced_from is not None]
    return (referenced or programs)[0] if programs else None


def find_sensor_tables(image: bytes) -> Optional[SensorTables]:
    """Locate the sensor reset table and the init table the firmware writes to the image sensor at boot.

    When more than one spot fits (sensor_programs), the one whose init table the code refers to by address wins.
    Whether the code refers to several programs with different tables is BuildMarks.several_sensor_programs.
    """
    return _pick_sensor_program(sensor_programs(image))


def source_tree_marks(code: bytes) -> Tuple[Optional[str], Optional[int], int]:
    """(folder, offset of its first use, how many source paths name it) for the folder after "\\2247\\5408\\" in
    the source paths of the code area, or (None, None, 0). When paths name several folders, the one most of
    them name wins (the first found, on a tie)."""
    found = [(m.group(1).decode("ascii"), m.start(1)) for m in _SOURCE_TREE_RE.finditer(code)]
    if not found:
        return None, None, 0
    counts = Counter(folder for folder, _ in found)
    folder = counts.most_common(1)[0][0]
    return folder, next(at for name, at in found if name == folder), counts[folder]


# What the source-tree folder proves, in the words inspect and pack print under it.
SOURCE_TREE_PROVES = ("a different folder shows a different build; the same folder does not show the same build, and neither "
                      "says whether a file fits a camera")


def known_source_trees() -> str:
    """The known builds' source-tree folders, as 'public-dump: v006, film-mods: v008_test'."""
    return ", ".join(f"{b.key}: {b.source_tree}" for b in KNOWN_BUILDS.values() if b.source_tree)


def find_marks(image: bytes) -> BuildMarks:
    """The build marks, each found by its content in the code area. Any of them may be missing.

    The stamp is the one that follows the '*.bin' the updater matches with; when several follow one and they
    differ, which one the updater compares cannot be told, so there is no stamp (stamp_conflict names them)."""
    code = bytes(image[:min(len(image), CODE_AREA_END)])
    stamp = stamp_off = None
    stamps = list(_STAMP_RE.finditer(code))
    near = [m for m in stamps if _UPDATE_PATTERN in code[max(0, m.start(1) - 64): m.start(1)]]
    values = list(dict.fromkeys(m.group(1).decode("ascii") for m in near))
    conflict: Tuple[str, ...] = tuple(values) if len(values) > 1 else ()
    pick = None if conflict else (near[0] if near else (stamps[0] if len(stamps) == 1 else None))
    if pick:
        stamp, stamp_off = pick.group(1).decode("ascii"), pick.start(1)
    b = _BUILD_STRING_RE.search(code)
    c = _COMPILED_RE.search(code)
    programs = sensor_programs(image)
    tree, tree_off, tree_paths = source_tree_marks(code)
    return BuildMarks(
        stamp=stamp, stamp_offset=stamp_off,
        build_string=b.group(0) if b else None, build_offset=b.start() if b else None,
        compiled=c.group(0) if c else None, compiled_offset=c.start() if c else None,
        sensor=_pick_sensor_program(programs), sensor_programs=tuple(programs), stamp_conflict=conflict,
        source_tree=tree, source_tree_offset=tree_off, source_tree_paths=tree_paths,
    )


# --- Build identity -----------------------------------------------------------------------------------


@dataclass
class BuildMatch:
    marks: BuildMarks
    # The known build this image is, or whose marks (stamp, build string, sensor table offsets and the stamp itself)
    # a changed copy keeps. The mod commands go by it, since they need that build's layout; whether the image was
    # made from that build is made_from.
    build: Optional[KnownBuild]
    modified: bool                     # True when it is that build with changes (a mod, or another camera's state)
    checks: Dict[str, bool] = field(default_factory=dict)
    layout: Optional[KnownBuild] = None   # the known build whose mark offsets the image has, whatever its stamp and table

    @property
    def charmera(self) -> bool:
        """Charmera firmware of some build: the build string, the stamp and a sensor table were all found."""
        return self.marks.complete

    @property
    def identity(self) -> str:
        """The key pack, inspect and the backup and restore manifests show: '<build>' or '<build>-modified' for the
        build the image is or was made from (made_from), 'charmera-unknown-build' for any other Charmera firmware,
        one laid out like a known build with another sensor table included, else 'not-charmera'."""
        known = self.made_from
        if known is not None:
            return known.key + ("-modified" if self.modified else "")
        return "charmera-unknown-build" if self.charmera else "not-charmera"

    @property
    def made_from(self) -> Optional[KnownBuild]:
        """The known build this image is or was made from: an exact fingerprint, or that build's marks with the same
        sensor table. A copy whose sensor table differs is another build laid out like it (no toolkit mod changes
        the table), not that build with changes."""
        if self.build is None:
            return None
        if self.modified and (self.marks.sensor is None or self.marks.sensor.sha256 != self.build.sensor_table_sha256):
            return None
        return self.build

    @property
    def label(self) -> str:
        known = self.made_from
        if known is not None:
            return known.label + (" with changes" if self.modified else "")
        if self.charmera:
            if self.layout is not None:
                return f"Charmera firmware of a build this toolkit does not know, laid out like {self.layout.label}"
            return "Charmera firmware of a build this toolkit does not know (no public image has this fingerprint)"
        return "not a known Charmera build (build marks missing)"

    @property
    def exact(self) -> bool:
        return self.build is not None and not self.modified


def software_sha256(image: bytes) -> str:
    return hashlib.sha256(image[:SOFTWARE_END]).hexdigest()


def official_fingerprint(image: bytes) -> Optional[tuple]:
    """(sha256, sha1, byte sum) of bytes 0..0x169000, the span an official-size file writes, or None if shorter."""
    if len(image) < OFFICIAL_UPDATE_SIZE:
        return None
    span = image[:OFFICIAL_UPDATE_SIZE]
    return hashlib.sha256(span).hexdigest(), hashlib.sha1(span).hexdigest(), byte_sum(span)


def match_known_build(image: bytes) -> Optional[KnownBuild]:
    """The known build whose fingerprint over 0..0x169000 this image carries, unmodified, or None."""
    fp = official_fingerprint(image)
    if fp is None:
        return None
    sha256, sha1, s = fp
    for b in KNOWN_BUILDS.values():
        if b.official_sha256 is not None and b.official_sha256 == sha256:
            return b
        if b.official_sha1 == sha1 and b.official_byte_sum == s:
            return b
    return None


def detect_build(image: bytes) -> BuildMatch:
    """Say which build an image is, from its content, and what that does and does not prove.

    An exact fingerprint over bytes 0..0x169000 names a known build. Failing that, the marks' offsets name
    the known build a modified image was made from (a mod keeps the stamp, the build string and the sensor
    table where they were, and the table itself: BuildMatch.made_from). Images with marks at other offsets are
    Charmera firmware of an unknown build; images without marks are not Charmera firmware. The stamp alone
    never identifies a build.
    """
    marks = find_marks(image)
    exact = match_known_build(image)
    build, modified = exact, False
    if build is None and marks.complete:
        for b in KNOWN_BUILDS.values():
            same = [marks.stamp_offset == b.stamp_offset if b.stamp_offset is not None else None,
                    marks.build_offset == b.build_offset if b.build_offset is not None else None,
                    marks.sensor.init[0] == b.sensor_table_offset if b.sensor_table_offset is not None else None]
            known = [x for x in same if x is not None]
            if known and all(known) and marks.stamp == b.stamp:
                build, modified = b, True
                break
    layout = exact if exact is not None else next(
        (b for b in KNOWN_BUILDS.values() if marks.layout is not None and b.layout == marks.layout), None)
    checks = {
        "build_string": marks.build_string is not None,
        "stamp": marks.stamp is not None,
        "compiled": marks.compiled is not None,
        "sensor_table": marks.sensor is not None,
        "sensor_table_referenced": marks.sensor is not None and marks.sensor.referenced_from is not None,
        "sensor_table_known": marks.sensor is not None and marks.sensor.sha256 in {b.sensor_table_sha256 for b in KNOWN_BUILDS.values()},
        "code_sha256": len(image) >= 0x100000 and hashlib.sha256(image[0x200:0x100000]).hexdigest() == PUBLIC_BUILD.code_sha256,
        "software_sha256": len(image) >= SOFTWARE_END and software_sha256(image) == PUBLIC_BUILD.software_sha256,
        "fingerprint": exact is not None,
    }
    return BuildMatch(marks, build, modified, checks, layout)


def chip_read_problem(image: bytes, need_sensor_table: bool = True) -> Optional[str]:
    """Why an image is not a complete read of a Charmera's flash chip, as a clause, or None when it is one.

    A read is exactly 2 MiB, starts with SPII and carries the build string and the stamp, and, unless
    need_sensor_table is False, a sensor table of the kind the known builds use. A camera fitted with another
    sensor may run firmware that keeps its table in another form, which is why a donor's read is taken without
    one. Being byte-identical to a public dump is not a problem by itself: a camera flashed with the public
    restore file whose settings never changed since reads exactly like it.
    """
    if len(image) != FLASH_SIZE:
        return (f"it is {len(image):,} bytes, and a read of the chip is exactly {FLASH_SIZE:,}"
                + ("; this is an update file, not a read of a chip" if len(image) in (UPDATE_FILE_SIZE, OFFICIAL_UPDATE_SIZE) else ""))
    if image[:4] != SPII_MAGIC:
        return "it does not start with SPII as Charmera firmware does, so it is not a readable copy of the chip"
    missing = find_marks(image).missing(need_sensor_table)
    if missing:
        return "the build marks every Charmera firmware carries were not all found (" + ", ".join(missing) + " missing)"
    return None


def public_dump_name(image: bytes) -> Optional[str]:
    """Which public dump an image is, byte for byte, or None."""
    return PUBLIC_DUMPS.get(hashlib.sha256(image).hexdigest())


def vector_table_sha256(image: bytes) -> Optional[str]:
    """SHA-256 of the ARM vector table (bytes 0x200..0x240), or None when the image ends before it."""
    lo, hi = VECTOR_TABLE
    return hashlib.sha256(image[lo:hi]).hexdigest() if len(image) >= hi else None


def updater_extra_erase(file_len: int) -> int:
    """Flash offset of the 64 KiB block the updater erases after programming a file of this length.

    VERIFIED from the updater: (length rounded up to 256) + 0x10000, masked to the block.
    """
    return (((file_len + 0xFF) & ~0xFF) + 0x10000) & ~0xFFFF


def describe_erase_target(off: int) -> str:
    if off >= FLASH_SIZE:
        block = (off - FLASH_SIZE) // BLOCK_SIZE
        what = "the boot header and the vector table" if block == 0 else "code"
        return (f"beyond the 2 MiB chip: the address wraps to block {block} ({what}) and bricks the camera (a 2 MiB file, "
                "whose final erase wraps to block 1, did so on hardware: confirmed by the film-mods author)")
    if SETTINGS_BASE <= off < 0x180000:
        return ("the settings bank and the photo directory: at the next start the camera applies factory defaults and writes an "
                "empty photo list, so the photos stored in its own memory stay but are no longer listed")
    if 0x180000 <= off < 0x1CB000:
        return "the internal photo area: a stored photo is partly erased while the directory still lists it"
    if 0x1CB000 <= off < USER_AREA_END:
        return "the unused area above the photo slots (0xFF on every known camera; harmless)"
    if off >= USER_AREA_END:
        return "a blank block (harmless)"
    return "inside the software or resource area"


def embedded_stamp(image: bytes) -> str:
    """The 12-digit stamp compiled into the firmware, found by content; '' when there is none."""
    return find_marks(image).stamp or ""


# --- Image kind ---------------------------------------------------------------------------------------


def image_kind(image: bytes) -> str:
    """'dump' (2 MiB), 'update' (0x1E0000, the published size), 'official-update' (0x169000, where official
    updates and the film-mods files end: code, data and the whole resource pack, nothing of the camera's own
    area), 'software-only' (exactly 0x170000), 'truncated' (any other length below 0x1E0000: a cut-off
    download or an incomplete read, which would leave part of the code or resource pack erased), or 'unknown'."""
    n = len(image)
    if n == FLASH_SIZE:
        return "dump"
    if n == UPDATE_FILE_SIZE:
        return "update"
    if n == SOFTWARE_END:
        return "software-only"
    if n == OFFICIAL_UPDATE_SIZE:
        return "official-update"
    if n < UPDATE_FILE_SIZE:
        return "truncated"
    return "unknown"


def tail_blank(image: bytes, start: int = USER_AREA_END) -> Optional[bool]:
    if len(image) <= start:
        return None
    return image[start:] == b"\xff" * (len(image) - start)


# --- Resource pack ------------------------------------------------------------------------------------


@dataclass
class ResourceEntry:
    index: int
    name: str          # directory spelling, e.g. 'BEEPWAV' (8.3 name without the dot)
    sector: int
    offset: int
    budget: Optional[int]   # bytes until the next entry; None for the last entry

    @property
    def display_name(self) -> str:
        for ext in ("GPZP", "GPZ", "WAV", "JPG", "PAK"):
            if self.name.endswith(ext) and len(self.name) > len(ext):
                return f"{self.name[:-len(ext)]}.{ext}"
        return self.name


def find_resources_end(image: bytes) -> int:
    """Offset just past the last non-0xFF byte below the settings store (0x168E9A on the known build)."""
    i = min(len(image), SETTINGS_BASE) - 1
    while i > RESOURCE_BASE and image[i] == 0xFF:
        i -= 1
    return i + 1


def resource_directory(image: bytes) -> List[ResourceEntry]:
    if image[RESOURCE_BASE: RESOURCE_BASE + 4] != b"GP+\x00":
        return []
    entries = []
    off = RESOURCE_BASE + 0x18
    while off + 24 <= len(image):
        name = bytes(image[off: off + 20])
        sector = struct.unpack_from("<I", image, off + 20)[0]
        if name[0] in (0, 0xFF):
            break
        entries.append(ResourceEntry(len(entries), name.rstrip(b"\x00").decode("ascii", "replace"), sector,
                                     RESOURCE_BASE + sector * RESOURCE_SECTOR, None))
        off += 24
    end = find_resources_end(image)
    for i, e in enumerate(entries):
        nxt = entries[i + 1].offset if i + 1 < len(entries) else end
        e.budget = max(0, nxt - e.offset)
    return entries


# --- Sounds -------------------------------------------------------------------------------------------

SOUND_SLOTS = {
    # directory name: (what it is, sound id used by the event player)
    "CLICKWAV": ("key click", 0),
    "POWERON_AUDIOWAV": ("power on", 2),
    "POWEROFF_AUDIOWAV": ("power off", 3),
    "BEEPWAV": ("menu beep", 5),
    "IMGWAV": ("shutter", 6),
}


@dataclass
class WavSlot:
    entry: ResourceEntry
    riff_size: int
    fmt_tag: int
    channels: int
    rate: int
    bits: int
    data_offset: int     # absolute file offset of the PCM data
    data_len: int

    @property
    def total_len(self) -> int:
        return 8 + self.riff_size

    @property
    def duration_ms(self) -> int:
        frame = max(1, self.channels * (self.bits // 8))
        return int(1000 * (self.data_len // frame) / self.rate) if self.rate else 0

    @property
    def silence_byte(self) -> int:
        return 0x80 if self.bits == 8 else 0x00


def parse_wav_at(image: bytes, entry: ResourceEntry) -> WavSlot:
    """Parse the WAV in a resource slot. Raises ValueError (never struct.error) on a damaged or truncated file."""
    off = entry.offset
    if off + 12 > len(image):
        raise ValueError(f"{entry.display_name} at {off:#x} lies past the end of the file (truncated image)")
    if image[off: off + 4] != b"RIFF" or image[off + 8: off + 12] != b"WAVE":
        raise ValueError(f"{entry.display_name} at {off:#x} is not a RIFF WAVE")
    try:
        return _parse_wav_chunks(image, entry)
    except struct.error:
        raise ValueError(f"{entry.display_name} at {off:#x} is cut off (truncated image)") from None


def _parse_wav_chunks(image: bytes, entry: ResourceEntry) -> WavSlot:
    off = entry.offset
    riff_size = struct.unpack_from("<I", image, off + 4)[0]
    p = off + 12
    fmt = None
    data = None
    end = off + 8 + riff_size
    while p + 8 <= end:
        cid = bytes(image[p: p + 4])
        csz = struct.unpack_from("<I", image, p + 4)[0]
        if cid == b"fmt " and csz >= 16:
            fmt = struct.unpack_from("<HHIIHH", image, p + 8)
        elif cid == b"data":
            data = (p + 8, csz)
            break
        p += 8 + csz + (csz & 1)
    if fmt is None or data is None:
        raise ValueError(f"{entry.display_name}: missing fmt or data chunk")
    if data[0] + data[1] > len(image):
        raise ValueError(f"{entry.display_name}: sample data runs past the end of the file (truncated image)")
    tag, ch, rate, _brate, _align, bits = fmt
    return WavSlot(entry, riff_size, tag, ch, rate, bits, data[0], data[1])


def sound_slots(image: bytes) -> List[WavSlot]:
    out = []
    for e in resource_directory(image):
        if e.name in SOUND_SLOTS:
            out.append(parse_wav_at(image, e))
    return out


# --- Photos stored in flash ---------------------------------------------------------------------------


@dataclass
class PhotoIndexEntry:
    slot: int
    used: int
    size: int


def photo_index(image: bytes) -> List[PhotoIndexEntry]:
    """8-byte entries (u16 slot, u16 used, u32 size); VERIFIED against the stored JPEG lengths."""
    out = []
    for i in range(0, 0x40, 8):
        if len(image) < PHOTO_INDEX + i + 8:
            break
        slot, used, size = struct.unpack_from("<HHI", image, PHOTO_INDEX + i)
        if used == 0 and size == 0:
            continue
        out.append(PhotoIndexEntry(slot, used, size))
    return out


def jpeg_length_at(image: bytes, off: int) -> Optional[int]:
    if image[off: off + 2] != b"\xff\xd8":
        return None
    end = image.find(b"\xff\xd9", off)
    return None if end < 0 else end + 2 - off


def stored_photos(image: bytes) -> List[tuple]:
    out = []
    for slot_off in PHOTO_SLOTS:
        if len(image) > slot_off + 2:
            n = jpeg_length_at(image, slot_off)
            if n:
                out.append((slot_off, n))
    return out


def public_dump_photos(image: bytes) -> List[tuple]:
    """(slot offset, whose camera) for each stored photo that is one of PUBLIC_DUMP_PHOTOS, by its hash."""
    out = []
    for off, n in stored_photos(image):
        owner = PUBLIC_DUMP_PHOTOS.get(hashlib.sha256(image[off: off + n]).hexdigest())
        if owner:
            out.append((off, owner))
    return out


def photos_whose(image: bytes) -> str:
    """Whose the stored photos in an image are, as far as the image can tell, as a clause."""
    public = public_dump_photos(image)
    if public:
        owner = public[0][1]
        return (f"the photos of the camera the {owner} dump was read from: every 0x1E0000-byte file made from that dump "
                "carries them, so a camera flashed with one holds them too")
    return ("the photos stored in the camera this was read from: its own only if no 0x1E0000-byte file carrying "
            "photos was flashed onto it before that read")


# --- Summary -------------------------------------------------------------------------------------------


def summarize(image: bytes) -> dict:
    """Everything inspect prints, as plain data (also used by verify and pack)."""
    kind = image_kind(image)
    hdr = parse_header(image)
    build = detect_build(image)
    recs = settings_mod.parse_settings(image) if len(image) > SETTINGS_BASE else []
    valid = [r for r in recs if r.crc_ok]
    current = None
    current_note = None
    if len(image) >= settings_mod.BANK_END:
        try:
            current = settings_mod.current_record(image)
        except ValueError as exc:
            current_note = str(exc)
    elif len(image) > settings_mod.SETTINGS_BASE:
        current_note = "the file ends inside the settings bank, so the record the camera would load cannot be told"
    sounds_error = None
    try:
        sounds = sound_slots(image) if len(image) > RESOURCE_BASE else []
    except ValueError as exc:
        sounds, sounds_error = [], str(exc)
    info = {
        "current_note": current_note,
        "size": len(image),
        "kind": kind,
        "sha256": hashlib.sha256(image).hexdigest(),
        "byte_sum": byte_sum(image),
        "magic_ok": hdr.magic_ok,
        "header_words": hdr.words,
        "reg_init": hdr.reg_init,
        "identity": build.identity,
        "identity_label": build.label,
        "identity_checks": build.checks,
        "build": build,
        "marks": build.marks,
        "embedded_stamp": build.marks.stamp or "",
        "software_sha256": software_sha256(image) if len(image) >= SOFTWARE_END else None,
        "official_fingerprint": official_fingerprint(image),
        "public_dump": PUBLIC_DUMPS.get(hashlib.sha256(image).hexdigest()),
        "resources": resource_directory(image),
        "resources_end": find_resources_end(image) if len(image) > RESOURCE_BASE else None,
        "sounds": sounds,
        "sounds_error": sounds_error,
        "settings_records": recs,
        "settings_valid": len(valid),
        "current_settings": current,
        "photo_index": photo_index(image) if len(image) > PHOTO_INDEX + 0x40 else [],
        "stored_photos": stored_photos(image),
        "photos_whose": photos_whose(image),
        "tail_blank": tail_blank(image),
    }
    return info
