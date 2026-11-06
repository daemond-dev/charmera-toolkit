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
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from . import settings as settings_mod
from .checksum import FLASH_SIZE, OFFICIAL_UPDATE_SIZE, UPDATE_FILE_SIZE, byte_sum

# --- Fixed layout (file offsets) ----------------------------------------------------------------------
HEADER_SIZE = 0x200          # VERIFIED: 'SPII' header, ARM vector table starts at 0x200
VA_OFFSET = 0x200            # VERIFIED: file offset = runtime address + 0x200
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
# charmera-film-mods author's camera (confirmed by that author from their own dump, 2026-11-06). The strings
# `JH5408  20250520` and `Oct 14 2025`, the yellow boot screen and the "original series" body do not tell
# them apart either. Only a fingerprint over the software itself does, and even that says which public
# image a file was made from, not whether it fits the camera it goes into: the two known builds drive the
# same sensor with the same 61-write table, and a camera fitted with another sensor needs a build nobody has.

CODE_AREA_END = 0x120000          # boot header, code, read-only and initialised data, and the padding below the pack


@dataclass(frozen=True)
class KnownBuild:
    key: str
    label: str
    source: str
    status: str                    # VERIFIED: hashed here from the dump. REPORTED: fingerprint published by its owner.
    stamp: str                     # the 12-digit stamp compiled into it (the same in both known builds)
    official_sha1: str             # SHA-1 of bytes 0..0x169000 (the span official updates write)
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
)

FILM_MODS_BUILD = KnownBuild(
    key="film-mods",
    label="the charmera-film-mods author's build (same stamp 202505201029, different bytes; fingerprint reported by its owner)",
    source="Hyperlite-Media/charmera-film-mods: the author's factory dump, not public; fingerprint from issue #1 (2026-11-06)",
    status="REPORTED",
    stamp="202505201029",
    official_sha1="91499bd3bff3acde053cb8c8255a73cdeb6e3d33",
    official_byte_sum=0x0852C329,
    sensor_table_sha256="aea3976bdf7ba2e747d8b56141f7ef0b4e3262481540dc2869457bc814fcb755",
    stamp_offset=0x218D0,
    sensor_table_offset=0xF5D34,
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
# what it looks like, and the sensor table is cross-checked against the code that points at it.

# The sensor's reset table: page select (register 0xFD = 0), register 0x20 = 0, end marker. VERIFIED in the
# public dump at 0xF5BFC, confirmed at +0xC8 in the film-mods build by its author.
SENSOR_RESET_TABLE = bytes.fromhex("fd002000ffff")
SENSOR_TABLE_END = b"\xff\xff"
SENSOR_PAGE_REGISTER = 0xFD
_STAMP_RE = re.compile(rb"(?<![0-9])([0-9]{12})\x00")
_BUILD_STRING_RE = re.compile(rb"JH5408 +[0-9]{8}")
_COMPILED_RE = re.compile(rb"(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) [ 0-9][0-9] 20[0-9]{2}(?=\x00)")
_UPDATE_PATTERN = b"*.bin\x00"


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

    @property
    def complete(self) -> bool:
        """The three marks every Charmera build is expected to carry."""
        return self.stamp is not None and self.build_string is not None and self.sensor is not None


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
    """File offset of a little-endian word holding ram_address in the code area, or None."""
    needle = struct.pack("<I", ram_address)
    i = image.find(needle, HEADER_SIZE, end)
    return None if i < 0 else i


def find_sensor_tables(image: bytes) -> Optional[SensorTables]:
    """Locate the sensor reset table and the init table the firmware writes to the image sensor at boot.

    The reset table is found by its exact bytes. The init table is the first (register, value) table after
    it that starts with a page select, holds at least 16 writes, switches page at least once more and ends
    with the ff ff marker. Both must lie in the code area. When more than one spot fits, the one whose init
    table the code refers to by address (a literal of RAM address = file offset - 0x200) wins.
    """
    end = min(len(image), CODE_AREA_END)
    candidates = []
    i = image.find(SENSOR_RESET_TABLE, HEADER_SIZE, end)
    while 0 <= i < end:
        reset = (i, i + len(SENSOR_RESET_TABLE))
        p = reset[1]
        found = None
        while p < min(end, reset[1] + 0x400):
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
            candidates.append(SensorTables(reset, (s, e), pairs, hashlib.sha256(image[s:e]).hexdigest(),
                                           bus if bus is not None and 0 < bus < 0x100 else None, ref))
        i = image.find(SENSOR_RESET_TABLE, i + 1, end)
    if not candidates:
        return None
    referenced = [c for c in candidates if c.referenced_from is not None]
    return (referenced or candidates)[0]


def find_marks(image: bytes) -> BuildMarks:
    """The build marks, each found by its content in the code area. Any of them may be missing."""
    code = bytes(image[:min(len(image), CODE_AREA_END)])
    stamp = stamp_off = None
    stamps = list(_STAMP_RE.finditer(code))
    near = [m for m in stamps if _UPDATE_PATTERN in code[max(0, m.start(1) - 64): m.start(1)]]
    pick = near[0] if near else (stamps[0] if len(stamps) == 1 else None)
    if pick:
        stamp, stamp_off = pick.group(1).decode("ascii"), pick.start(1)
    b = _BUILD_STRING_RE.search(code)
    c = _COMPILED_RE.search(code)
    return BuildMarks(
        stamp=stamp, stamp_offset=stamp_off,
        build_string=b.group(0) if b else None, build_offset=b.start() if b else None,
        compiled=c.group(0) if c else None, compiled_offset=c.start() if c else None,
        sensor=find_sensor_tables(image),
    )


# --- Build identity -----------------------------------------------------------------------------------


@dataclass
class BuildMatch:
    marks: BuildMarks
    build: Optional[KnownBuild]        # the known build this image is, or was made from
    modified: bool                     # True when it is that build with changes (a mod, or another camera's state)
    checks: Dict[str, bool] = field(default_factory=dict)

    @property
    def charmera(self) -> bool:
        """Charmera firmware of some build: the build string, the stamp and a sensor table were all found."""
        return self.marks.complete

    @property
    def identity(self) -> str:
        if self.build is not None:
            return self.build.key + ("-modified" if self.modified else "")
        return "charmera-unknown-build" if self.charmera else "not-charmera"

    @property
    def label(self) -> str:
        if self.build is not None:
            return self.build.label + (" with changes" if self.modified else "")
        if self.charmera:
            return "Charmera firmware of a build this toolkit does not know (no public image has this fingerprint)"
        return "not a known Charmera build (build marks missing)"

    @property
    def exact(self) -> bool:
        return self.build is not None and not self.modified


def software_sha256(image: bytes) -> str:
    return hashlib.sha256(image[:SOFTWARE_END]).hexdigest()


def official_fingerprint(image: bytes) -> Optional[tuple]:
    """(sha256, sha1, byte sum) of bytes 0..0x169000, the span official updates write, or None if shorter."""
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
    table where they were). Images with marks at other offsets are Charmera firmware of an unknown build;
    images without marks are not Charmera firmware. The stamp alone never identifies a build.
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
    return BuildMatch(marks, build, modified, checks)


def updater_extra_erase(file_len: int) -> int:
    """Flash offset of the 64 KiB block the updater erases after programming a file of this length.

    VERIFIED from the updater: (length rounded up to 256) + 0x10000, masked to the block.
    """
    return (((file_len + 0xFF) & ~0xFF) + 0x10000) & ~0xFFFF


def describe_erase_target(off: int) -> str:
    if off >= FLASH_SIZE:
        return "beyond the 2 MiB chip (the address wraps to block 1 and bricks the camera: confirmed by the film-mods author on hardware)"
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
        "tail_blank": tail_blank(image),
    }
    return info
