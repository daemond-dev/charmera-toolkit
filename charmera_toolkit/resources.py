"""`resources`: list, extract or replace entries of the GP+ resource pack.

The pack (VERIFIED on the stock dump): `GP+\\0` at 0x120000, then from +0x18 one 24-byte directory entry per
file: a 20-byte NUL-padded name with the dot removed (POWER_ON_LOGOJPG) and a u32 sector number, x512 from
the pack start. 43 entries in the original 2025 build. An entry owns the bytes from its offset to the next
entry's offset (its budget; the firmware clamps reads to it). The file inside is usually shorter and the
rest of the slot is padding, 0xFF in every slot of the stock dump.

Kinds, told apart by the first bytes of the slot:

- RIFF: a WAV. Length = 8 + the u32 at +4. Changed with `sounds replace`.
- FF D8: a JPEG. Length = up to and including the EOI marker, found by walking the segment list. The two in
  the stock build (AUDIO_REC_BG.JPG, POWER_ON_LOGO.JPG) are baseline, 160x80, three components with sampling
  factors 2x2,1x1,1x1 (4:2:0), a JFIF APP0, no restart interval, one interleaved scan.
- GPZP: 4-byte magic + raw DEFLATE stream + {u32 LE CRC-32, u32 LE length} of the inflated data. Names end
  in .GPZP or .GPZ. The photo frames (F_n_00s) and their thumbnails (PV00n) are changed with `frames
  replace`; the other GPZP files are menu graphics whose pixel layout is not known well enough to rebuild.
- GPRS: GPRS.PAK, a nested pack holding the menu text. Kept whole.

`replace` only accepts a JPEG for a JPEG slot, and nothing the stock decoder has not been seen to handle:
baseline (SOF0), the same size, component count and sampling factors as the file it replaces, no restart
interval unless the stock file has one, the same scan layout, and it has to fit the slot. The directory is
never touched, so every other entry keeps its offset. A custom logo has not been tried on a camera;
docs/RESOURCES.md says what to expect and docs/RECOVERY.md what to do if it does not boot.

Writing a modified image is a mod step: the output is a full image, not an update file. Run `pack` on it
afterwards and read what `verify` says.
"""
from __future__ import annotations

import re
import struct
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional, Tuple

from .image import ORIGINAL_2025, RESOURCE_BASE, ResourceEntry, WavSlot, detect_build, parse_wav_at, resource_directory
from .sounds import GATE_FLAG, _check_output, _write_mod

KIND_WAV = "WAV"
KIND_JPEG = "JPEG"
KIND_GPZP = "GPZP"
KIND_GPRS = "GPRS"
KIND_UNKNOWN = "?"

GPZP_MAGIC = b"GPZP"
GPRS_MAGIC = b"GPRS"
DEFAULT_PAD = 0xFF            # what every stock slot pads with, and the erased state of the flash

FRAME_ENTRY = re.compile(r"^(F_\d_\d{3}|PV\d{3})$")   # entries that belong to `frames`

# --- JPEG structure -----------------------------------------------------------------------------------------

SOF_NAMES = {
    0xC0: "baseline (SOF0)",
    0xC1: "extended sequential (SOF1)",
    0xC2: "progressive (SOF2)",
    0xC3: "lossless (SOF3)",
    0xC5: "differential sequential (SOF5)",
    0xC6: "differential progressive (SOF6)",
    0xC7: "differential lossless (SOF7)",
    0xC9: "arithmetic sequential (SOF9)",
    0xCA: "arithmetic progressive (SOF10)",
    0xCB: "arithmetic lossless (SOF11)",
    0xCD: "differential arithmetic sequential (SOF13)",
    0xCE: "differential arithmetic progressive (SOF14)",
    0xCF: "differential arithmetic lossless (SOF15)",
}
SOF_BASELINE = 0xC0
_STANDALONE = {0x01, 0xD8, 0xD9} | set(range(0xD0, 0xD8))   # TEM, SOI, EOI, RSTn: no length field

# sampling factors of the first component when the others are 1x1, by the usual name
_SAMPLING_NAMES = {(1, 1): "4:4:4", (2, 1): "4:2:2", (2, 2): "4:2:0", (4, 1): "4:1:1", (1, 2): "4:4:0"}


@dataclass
class JpegSegment:
    marker: int
    offset: int        # of the FF byte
    length: int        # marker + length field + payload; 2 for a standalone marker

    @property
    def name(self) -> str:
        if 0xE0 <= self.marker <= 0xEF:
            return f"APP{self.marker - 0xE0}"
        if self.marker == 0xFE:
            return "COM"
        return f"FF{self.marker:02X}"

    @property
    def is_metadata(self) -> bool:
        """APP1..APP15 and COM: what --strip-metadata removes (APP0 is decided separately)."""
        return 0xE1 <= self.marker <= 0xEF or self.marker == 0xFE


@dataclass
class JpegInfo:
    length: int                                   # bytes up to and including the EOI marker
    sof_marker: int
    precision: int
    width: int
    height: int
    components: List[Tuple[int, int, int, int]]   # (id, horizontal factor, vertical factor, quant table)
    jfif: bool                                    # has an APP0 segment starting 'JFIF\0'
    app0: bool                                    # has any APP0 segment
    restart_interval: Optional[int]               # the DRI value, None without a DRI segment
    restart_markers: int                          # RSTn markers seen inside the entropy-coded data
    scans: List[int]                              # components in each SOS, in order
    segments: List[JpegSegment] = field(default_factory=list)

    @property
    def baseline(self) -> bool:
        return self.sof_marker == SOF_BASELINE

    @property
    def process(self) -> str:
        return SOF_NAMES.get(self.sof_marker, f"unknown frame type (FF{self.sof_marker:02X})")

    @property
    def sampling_factors(self) -> List[Tuple[int, int]]:
        return [(h, v) for _id, h, v, _tq in self.components]

    @property
    def sampling(self) -> str:
        """The factors as ImageMagick writes them (2x2,1x1,1x1), with the usual name when there is one."""
        text = ",".join(f"{h}x{v}" for h, v in self.sampling_factors)
        if len(self.components) == 1:
            return f"{text} (greyscale)"
        if len(self.components) == 3 and self.sampling_factors[1:] == [(1, 1), (1, 1)]:
            name = _SAMPLING_NAMES.get(self.sampling_factors[0])
            if name:
                return f"{text} ({name})"
        return text

    @property
    def metadata(self) -> List[JpegSegment]:
        return [s for s in self.segments if s.is_metadata]

    @property
    def metadata_bytes(self) -> int:
        return sum(s.length for s in self.metadata)

    def describe(self) -> str:
        parts = [f"{self.width}x{self.height} {self.process}", f"{len(self.components)} comp", f"sampling {self.sampling}"]
        parts.append("JFIF" if self.jfif else ("APP0 (not JFIF)" if self.app0 else "no JFIF"))
        if self.restart_interval:
            parts.append(f"restart interval {self.restart_interval}")
        if self.metadata:
            parts.append(f"metadata {self.metadata_bytes} B (" + ", ".join(s.name for s in self.metadata) + ")")
        if len(self.scans) != 1 or self.scans[0] != len(self.components):
            parts.append(f"{len(self.scans)} scans")
        return ", ".join(parts)


def parse_jpeg(blob: bytes) -> JpegInfo:
    """Walk the marker segments of a JPEG and report its structure. Raises ValueError for anything that is
    not a complete JPEG. Bytes after the EOI marker are ignored (the length excludes them)."""
    blob = bytes(blob)
    if blob[:2] != b"\xff\xd8":
        raise ValueError("not a JPEG (no FF D8 start marker)")
    segments = [JpegSegment(0xD8, 0, 2)]
    sof = None
    jfif = False
    app0 = False
    dri = None
    rst = 0
    scans: List[int] = []
    p = 2
    while True:
        if p + 2 > len(blob):
            raise ValueError("the JPEG ends before its EOI marker (truncated file)")
        if blob[p] != 0xFF:
            raise ValueError(f"expected a marker at offset {p}, found byte {blob[p]:#04x}")
        m = blob[p + 1]
        if m == 0xFF:                 # fill byte before a marker
            p += 1
            continue
        if m == 0xD9:
            segments.append(JpegSegment(0xD9, p, 2))
            length = p + 2
            break
        if m in _STANDALONE:
            segments.append(JpegSegment(m, p, 2))
            p += 2
            continue
        if p + 4 > len(blob):
            raise ValueError(f"segment FF{m:02X} at offset {p} has no length field (truncated file)")
        ln = struct.unpack_from(">H", blob, p + 2)[0]
        if ln < 2 or p + 2 + ln > len(blob):
            raise ValueError(f"segment FF{m:02X} at offset {p} runs past the end of the file")
        seg = blob[p + 4: p + 2 + ln]
        segments.append(JpegSegment(m, p, 2 + ln))
        if m in SOF_NAMES:
            if sof is not None:
                raise ValueError("more than one frame header (SOF)")
            if len(seg) < 6:
                raise ValueError("frame header (SOF) too short")
            precision, height, width, nc = struct.unpack_from(">BHHB", seg, 0)
            if nc == 0 or len(seg) < 6 + 3 * nc:
                raise ValueError("frame header (SOF) does not describe its components")
            comps = [(seg[6 + 3 * i], seg[7 + 3 * i] >> 4, seg[7 + 3 * i] & 15, seg[8 + 3 * i]) for i in range(nc)]
            sof = (m, precision, width, height, comps)
        elif m == 0xE0:
            app0 = True
            if seg[:5] == b"JFIF\x00":
                jfif = True
        elif m == 0xDD:
            if len(seg) < 2:
                raise ValueError("restart interval segment (DRI) too short")
            dri = struct.unpack_from(">H", seg, 0)[0]
        elif m == 0xDA:
            if sof is None:
                raise ValueError("a scan (SOS) appears before the frame header (SOF)")
            if not seg:
                raise ValueError("scan header (SOS) too short")
            scans.append(seg[0])
            p += 2 + ln
            # entropy-coded data: FF 00 is a stuffed byte, FF D0..D7 a restart marker, FF FF a fill byte;
            # any other marker ends the scan
            while True:
                q = blob.find(b"\xff", p)
                if q < 0 or q + 1 >= len(blob):
                    raise ValueError("the JPEG ends inside its entropy-coded data (truncated file)")
                nxt = blob[q + 1]
                if nxt == 0x00:
                    p = q + 2
                elif 0xD0 <= nxt <= 0xD7:
                    rst += 1
                    p = q + 2
                elif nxt == 0xFF:
                    p = q + 1
                else:
                    p = q
                    break
            continue
        p += 2 + ln
    if sof is None:
        raise ValueError("no frame header (SOF) before the EOI marker")
    if not scans:
        raise ValueError("no scan (SOS) before the EOI marker")
    marker, precision, width, height, comps = sof
    return JpegInfo(length, marker, precision, width, height, comps, jfif, app0, dri, rst, scans, segments)


def strip_metadata(blob: bytes, keep_app0: bool = True) -> Tuple[bytes, List[JpegSegment]]:
    """Remove APP1..APP15 and COM segments (and APP0 unless keep_app0). Returns the new bytes, trimmed at
    the EOI marker, and the segments that were removed. The entropy-coded data is copied untouched."""
    info = parse_jpeg(blob)
    out = bytearray()
    removed = []
    prev_end = 0
    for seg in info.segments:
        out += blob[prev_end: seg.offset]
        drop = seg.is_metadata or (seg.marker == 0xE0 and not keep_app0)
        if drop:
            removed.append(seg)
        else:
            out += blob[seg.offset: seg.offset + seg.length]
        prev_end = seg.offset + seg.length
    return bytes(out), removed


# --- GPZP -------------------------------------------------------------------------------------------------------


@dataclass
class GpzpInfo:
    stream_len: int
    inflated_len: int
    trailer_len: int
    trailer_crc: int
    payload: bytes

    @property
    def length(self) -> int:
        return 4 + self.stream_len + 8

    @property
    def crc_ok(self) -> bool:
        return self.trailer_crc == zlib.crc32(self.payload) & 0xFFFFFFFF

    @property
    def length_ok(self) -> bool:
        return self.trailer_len == self.inflated_len

    @property
    def problem(self) -> Optional[str]:
        if not self.length_ok:
            return f"trailer says {self.trailer_len} B but the stream inflates to {self.inflated_len} B"
        if not self.crc_ok:
            return f"trailer CRC-32 {self.trailer_crc:08x} does not match the inflated data ({zlib.crc32(self.payload) & 0xFFFFFFFF:08x})"
        return None


def parse_gpzp(blob: bytes) -> GpzpInfo:
    """Inflate a GPZP slot (padding after the trailer is allowed). Raises ValueError when the stream or the
    trailer is unusable; a CRC or length mismatch is reported through GpzpInfo.problem instead."""
    blob = bytes(blob)
    if blob[:4] != GPZP_MAGIC:
        raise ValueError("no GPZP magic")
    d = zlib.decompressobj(-15)
    try:
        payload = d.decompress(blob[4:])
    except zlib.error as exc:
        raise ValueError(f"the DEFLATE stream is corrupt ({exc})")
    if not d.eof:
        raise ValueError("the DEFLATE stream has no final block inside the slot")
    rest = d.unused_data
    if len(rest) < 8:
        raise ValueError("the 8-byte CRC/length trailer is missing")
    crc, ln = struct.unpack_from("<II", rest, 0)
    return GpzpInfo(len(blob) - 4 - len(rest), len(payload), ln, crc, payload)


# --- Entries ----------------------------------------------------------------------------------------------------


@dataclass
class ResourceInfo:
    entry: ResourceEntry
    kind: str
    actual: int                         # bytes of file data; the whole budget for GPRS and unknown kinds
    detail: str
    problem: Optional[str] = None       # why the entry looks damaged
    pad_byte: Optional[int] = None      # 0xFF or 0x00 when the padding is uniform; None when absent or mixed
    jpeg: Optional[JpegInfo] = None
    wav: Optional[WavSlot] = None
    gpzp: Optional[GpzpInfo] = None

    @property
    def budget(self) -> int:
        return self.entry.budget or 0

    @property
    def padding(self) -> int:
        return self.budget - self.actual

    @property
    def pad_text(self) -> str:
        if self.padding == 0:
            return "-"
        return f"{self.pad_byte:02X}" if self.pad_byte is not None else "mix"


def padding_byte(slot: bytes, actual: int) -> Optional[int]:
    tail = slot[actual:]
    if not tail:
        return None
    if tail == b"\xff" * len(tail):
        return 0xFF
    if tail == b"\x00" * len(tail):
        return 0x00
    return None


def describe_entry(image: bytes, entry: ResourceEntry) -> ResourceInfo:
    budget = entry.budget or 0
    slot = bytes(image[entry.offset: entry.offset + budget])
    info = ResourceInfo(entry, KIND_UNKNOWN, len(slot), "")
    try:
        if slot[:4] == b"RIFF":
            info.kind = KIND_WAV
            w = parse_wav_at(image, entry)
            info.wav = w
            info.detail = f"{w.rate} Hz {w.bits}-bit {w.channels}ch, {w.data_len} B PCM, {w.duration_ms} ms"
            if w.total_len > budget:
                info.problem = f"RIFF size says {w.total_len} B but the slot holds {budget}"
            else:
                info.actual = w.total_len
        elif slot[:2] == b"\xff\xd8":
            info.kind = KIND_JPEG
            j = parse_jpeg(slot)
            info.jpeg = j
            info.actual = j.length
            info.detail = j.describe()
        elif slot[:4] == GPZP_MAGIC:
            info.kind = KIND_GPZP
            g = parse_gpzp(slot)
            info.gpzp = g
            info.actual = g.length
            info.detail = f"inflates to {g.inflated_len} B, " + ("CRC ok" if g.problem is None else "CRC BAD")
            info.problem = g.problem
        elif slot[:4] == GPRS_MAGIC:
            info.kind = KIND_GPRS
            info.detail = "nested pack (menu text), kept whole"
        elif not slot:
            info.detail = "empty slot"
        else:
            info.detail = f"unknown magic {slot[:4].hex(' ')}"
    except (ValueError, struct.error, IndexError) as exc:
        info.problem = str(exc)
        info.actual = len(slot)
    info.pad_byte = padding_byte(slot, info.actual)
    return info


def describe_all(image: bytes) -> List[ResourceInfo]:
    return [describe_entry(image, e) for e in resource_directory(image)]


def find_entry(entries: List[ResourceEntry], name: str) -> ResourceEntry:
    """Match a directory name (POWER_ON_LOGOJPG) or a display name (POWER_ON_LOGO.JPG), case-insensitively."""
    key = name.strip().upper().replace(".", "")
    for e in entries:
        if e.name.upper() == key:
            return e
    names = ", ".join(e.display_name for e in entries)
    raise ValueError(f"no entry named {name}; the pack holds: {names}")


def safe_filename(name: str) -> str:
    """Directory names come from the image; keep them to plain file-name characters."""
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", name).lstrip(".")
    return cleaned or "entry"


# --- Extract ----------------------------------------------------------------------------------------------------


def extract(image: bytes, out_dir: Path, name: Optional[str] = None, overwrite: bool = False) -> List[Path]:
    """Write every entry (or the named one) trimmed to its file length, GPZP entries with a .inflated
    sidecar. Refuses before writing anything when a target file exists and overwrite is False."""
    entries = resource_directory(image)
    if not entries:
        raise ValueError(f"no GP+ resource pack at {RESOURCE_BASE:#x} (need a dump or an update file)")
    if name:
        entries = [find_entry(entries, name)]
    plan: List[Tuple[Path, bytes]] = []
    for e in entries:
        info = describe_entry(image, e)
        base = safe_filename(e.display_name)
        plan.append((out_dir / base, bytes(image[e.offset: e.offset + info.actual])))
        if info.gpzp is not None:
            plan.append((out_dir / (base + ".inflated"), info.gpzp.payload))
    if not overwrite:
        existing = [p.name for p, _ in plan if p.exists()]
        if existing:
            shown = ", ".join(existing[:5]) + (f" and {len(existing) - 5} more" if len(existing) > 5 else "")
            raise ValueError(f"{len(existing)} file(s) already exist in {out_dir}: {shown}; pass --overwrite or use an empty directory")
    out_dir.mkdir(parents=True, exist_ok=True)
    for p, data in plan:
        p.write_bytes(data)
    return [p for p, _ in plan]


# --- Replace ----------------------------------------------------------------------------------------------------


@dataclass
class ReplacePlan:
    entry: ResourceEntry
    old: JpegInfo
    new: JpegInfo
    data: bytes                       # exactly what goes into the slot
    removed: List[JpegSegment]        # metadata segments --strip-metadata took out
    trailing_dropped: int             # bytes after the EOI marker of the given file, not written
    pad_byte: int
    pad_assumed: bool                 # True when the slot had no uniform padding to copy

    @property
    def gap(self) -> int:
        """Bytes between the end of the new file and the end of the old one that get the padding byte."""
        return max(0, self.old.length - len(self.data))


def check_replacement(old: JpegInfo, new: JpegInfo, budget: int) -> None:
    """Raise ValueError naming the first property of `new` that the slot's JPEG does not share."""
    if not new.baseline:
        raise ValueError(f"process: the file is {new.process}; the stock file is baseline (SOF0) and only baseline is accepted")
    if new.precision != old.precision:
        raise ValueError(f"precision: {new.precision}-bit samples; the stock file has {old.precision}-bit")
    if (new.width, new.height) != (old.width, old.height):
        raise ValueError(f"size: {new.width}x{new.height}; the slot's file is {old.width}x{old.height} and the size must match exactly")
    if len(new.components) != len(old.components):
        raise ValueError(f"components: {len(new.components)}; the stock file has {len(old.components)}"
                         + (" (colour, not greyscale)" if len(old.components) == 3 and len(new.components) == 1 else ""))
    if new.sampling_factors != old.sampling_factors:
        raise ValueError(f"sampling: {new.sampling}; the stock file is {old.sampling}")
    if new.restart_interval and not old.restart_interval:
        raise ValueError(f"restart interval: the file has a DRI segment (interval {new.restart_interval}); the stock file has none")
    if new.restart_markers and not old.restart_interval:
        raise ValueError(f"restart markers: {new.restart_markers} RSTn markers in the scan data; the stock file has none")
    if new.scans != old.scans:
        raise ValueError(f"scan layout: {len(new.scans)} scan(s) of {new.scans} components; the stock file has {len(old.scans)} scan of {old.scans}")
    if new.length > budget:
        hint = " (--strip-metadata would save %d B)" % new.metadata_bytes if new.metadata else ""
        raise ValueError(f"length: {new.length} B but the slot holds {budget} B{hint}; use a lower quality or less detail")


def plan_replace(image: bytes, name: str, new_bytes: bytes, strip: bool = False) -> ReplacePlan:
    entries = resource_directory(image)
    if not entries:
        raise ValueError(f"no GP+ resource pack at {RESOURCE_BASE:#x} (need a dump or an update file)")
    entry = find_entry(entries, name)
    info = describe_entry(image, entry)
    shown = entry.display_name
    if info.kind == KIND_WAV:
        raise ValueError(f"{shown} is a WAV; use: charmera sounds replace IMAGE --slot {shown} --wav FILE --out NEW.bin")
    if info.kind == KIND_GPZP:
        stem = entry.display_name.split(".")[0]
        if FRAME_ENTRY.match(stem):
            raise ValueError(f"{shown} belongs to photo frame {stem[2] if stem.startswith('F_') else stem[-1]}; "
                             f"use: charmera frames replace IMAGE --slot N --png FRAME.png --out NEW.bin")
        raise ValueError(f"{shown} is a GPZP menu graphic; there is no known-safe way to rebuild one yet")
    if info.kind == KIND_GPRS:
        raise ValueError(f"{shown} is the nested menu-text pack; there is no known-safe way to change it yet")
    if info.kind != KIND_JPEG or info.jpeg is None:
        why = info.problem or info.detail
        raise ValueError(f"{shown} is not a JPEG slot ({why}); only JPEG entries can be replaced here")
    try:
        new = parse_jpeg(new_bytes)
    except ValueError as exc:
        raise ValueError(f"replacement file: {exc}")
    trailing = len(new_bytes) - new.length
    data = bytes(new_bytes[: new.length])
    removed: List[JpegSegment] = []
    if strip:
        data, removed = strip_metadata(data, keep_app0=info.jpeg.jfif)
        new = parse_jpeg(data)
    check_replacement(info.jpeg, new, info.budget)
    pad = info.pad_byte if info.pad_byte is not None else DEFAULT_PAD
    return ReplacePlan(entry, info.jpeg, new, data, removed, trailing, pad, info.pad_byte is None)


def apply_replace(image: bytes, plan: ReplacePlan) -> bytes:
    """The new file over the old one; the bytes the old file covered beyond it get the slot's padding
    byte; the rest of the slot and everything else in the image stay as they were."""
    buf = bytearray(image)
    off = plan.entry.offset
    buf[off: off + len(plan.data)] = plan.data
    if plan.gap:
        buf[off + len(plan.data): off + plan.old.length] = bytes([plan.pad_byte]) * plan.gap
    return bytes(buf)


def replace_jpeg(image: bytes, name: str, new_bytes: bytes, strip: bool = False) -> Tuple[bytes, ReplacePlan]:
    plan = plan_replace(image, name, new_bytes, strip)
    return apply_replace(image, plan), plan


# --- CLI ----------------------------------------------------------------------------------------------------------


def _print_list(image: bytes) -> int:
    infos = describe_all(image)
    if not infos:
        print(f"no GP+ resource pack at {RESOURCE_BASE:#x} (need a dump or an update file)")
        return 2
    last = infos[-1]
    end = last.entry.offset + last.budget
    print(f"GP+ pack at {RESOURCE_BASE:#x}: {len(infos)} entries, data ends at {end:#x}")
    print(f"{'idx':>3}  {'name':22s} {'offset':>8}  {'budget':>6}  {'actual':>6}  {'kind':5s} {'pad':3s}  details")
    bad = 0
    for i in infos:
        e = i.entry
        line = (f"{e.index:3d}  {e.display_name:22s} {e.offset:#8x}  {i.budget:6d}  {i.actual:6d}  "
                f"{i.kind:5s} {i.pad_text:3s}  {i.detail}")
        if i.problem:
            bad += 1
            line += f"  BAD: {i.problem}"
        print(line)
    pads = {i.pad_byte for i in infos if i.padding}
    mixed = [i.entry.display_name for i in infos if i.padding and i.pad_byte is None]
    if mixed:
        print(f"padding: not uniform in {', '.join(mixed)}; " + ", ".join(f"0x{p:02X}" for p in sorted(p for p in pads if p is not None)) + " elsewhere")
    elif pads:
        print("padding after each file: " + ", ".join(f"0x{p:02X}" for p in sorted(pads)))
    if bad:
        print(f"{bad} entr{'y' if bad == 1 else 'ies'} look damaged")
        return 1
    return 0


def _print_plan(plan: ReplacePlan, source: str) -> None:
    e = plan.entry
    print(f"replace {e.display_name} (entry {e.index} at {e.offset:#x}, budget {e.budget} B)")
    print(f"  slot holds: {plan.old.length} B, {plan.old.describe()}")
    print(f"  {source}: {len(plan.data)} B, {plan.new.describe()}")
    if plan.removed:
        print("  stripped: " + ", ".join(f"{s.name} ({s.length} B)" for s in plan.removed))
    if plan.trailing_dropped:
        print(f"  dropped {plan.trailing_dropped} B after the EOI marker of the file (not part of the JPEG)")
    print("  checks: process, precision, size, components, sampling, restart interval, scan layout, length: all match the slot")
    where = "what the slot uses" if not plan.pad_assumed else "assumed; the slot had no uniform padding to copy"
    print(f"  padding: {plan.gap} B of 0x{plan.pad_byte:02X} after the new file ({where}); "
          f"{(e.budget or 0) - len(plan.data)} B of the slot stay free; the directory is not touched")
    print(f"note: a replaced {e.display_name} has not been tried on a camera. Read docs/RESOURCES.md before flashing it,")
    print("      and docs/RECOVERY.md if the camera does not boot afterwards.")


def handler(args) -> int:
    src = Path(args.image)
    if not src.is_file():
        print(f"error: {src} is not a file")
        return 2
    image = src.read_bytes()
    if not resource_directory(image):
        print(f"error: no GP+ resource pack at {RESOURCE_BASE:#x} in {src} (need a dump or an update file)")
        return 2
    action = args.action
    if action == "list":
        return _print_list(image)
    if action == "extract":
        if not args.out:
            print("error: --out DIR is required for extract")
            return 2
        try:
            for p in extract(image, Path(args.out), args.name, args.overwrite):
                print(f"wrote {p}")
        except (ValueError, OSError) as exc:
            print(f"error: {exc}")
            return 2
        return 0
    # replace
    if not (args.name and args.file and args.out):
        print("error: replace needs --name ENTRY, --file JPEG and --out NEW.bin")
        return 2
    if detect_build(image).build is not ORIGINAL_2025:
        print("error: the image is not the original 2025 series build (its stamps were not found); "
              "the resource layout is only known for that build")
        return 2
    out = Path(args.out)
    problem = _check_output(src, out, args.overwrite)
    if problem:
        print(f"error: {problem}")
        return 2
    new_path = Path(args.file)
    if not new_path.is_file():
        print(f"error: {new_path} is not a file")
        return 2
    try:
        new_bytes = new_path.read_bytes()
        plan = plan_replace(image, args.name, new_bytes, args.strip_metadata)
    except (ValueError, OSError) as exc:
        print(f"error: {exc}")
        return 2
    _print_plan(plan, f"{new_path.name}" + (" after --strip-metadata" if args.strip_metadata else ""))
    new_image = apply_replace(image, plan)
    try:
        return _write_mod(args, new_image, out, f"{plan.entry.display_name} replaced by {new_path}")
    except OSError as exc:
        print(f"error: {exc}")
        return 2


def register(subparsers) -> None:
    p = subparsers.add_parser("resources", help="list, extract or replace entries of the GP+ resource pack")
    p.add_argument("action", choices=["list", "extract", "replace"])
    p.add_argument("image", metavar="IMAGE", help="a 2 MiB dump or a 0x1E0000 update file")
    p.add_argument("--out", help="output directory (extract) or output image path (replace)")
    p.add_argument("--name", help="an entry, e.g. POWER_ON_LOGO.JPG (extract: only that one; replace: the slot to change)")
    p.add_argument("--file", help="replace: the JPEG to put into the slot")
    p.add_argument("--strip-metadata", action="store_true",
                   help="replace: drop APP1..APP15 and COM segments from the JPEG first (keeps a JFIF APP0 when the stock file has one)")
    p.add_argument("--overwrite", action="store_true", help="allow --out to replace existing files (never the input)")
    p.add_argument(GATE_FLAG, dest="gate", action="store_true", help="replace: actually write the modified image")
    p.set_defaults(func=handler)
