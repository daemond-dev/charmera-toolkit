"""probe / backup / restore: full-flash backup and restore of the Charmera through flashrom.

The camera's firmware lives in a Puya PY25D16HB, a 16 Mbit (2 MiB) SPI flash in an SOP8 package with
visible legs (REPORTED by the teardown; a second owner confirmed a clip read). flashrom does not know the
part by name; community reads used a CH341A with `-c "SFDP-capable chip"` (REPORTED). This module is a
careful layer over flashrom and never touches USB itself.

Safety rules:

- `probe` and `backup` only run flashrom without an operation or with `-r`. They cannot write.
- `probe`, `backup` and `restore` print the checklist below (CHECKLIST) before flashrom goes near the chip.
- `backup` keeps a read as <label>_<UTC time>.bin only when two or more reads are identical. A single read,
  and reads that differ, keep the name <label>_<UTC time>.unverified-read<n>.bin, which nobody takes for a
  backup.
- `restore` refuses an image that is not exactly 2 MiB, does not start with the "SPII" magic, or carries no
  Charmera build marks (build string, 12-digit stamp, sensor table, each found by content; unless
  --allow-unknown-build). No backup of the chip on the clip, no write: restore reads the chip twice itself,
  keeps that read in --out as backup does (with its SHA-256 and a manifest), and writes nothing when the two
  reads differ. A backup file given from elsewhere could not prove that it came from this chip, now. It
  prints a 64 KiB block diff of the image against that read, the image's sensor table against the chip's,
  the SHA-256 and the exact plan, and only runs `flashrom -w` after the owner types RESTORE, and a second
  word when the two sensor tables are not the same, a chip with none of the known kind and an image with one
  included. flashrom writes a private copy of the checked bytes, hashed again just before it runs, never the
  owner's file in place. Afterwards it reads the chip back and compares.
- The tool never interrupts `flashrom -w` or the read-back after it: those run without a timeout.
  Probe and backup reads keep a generous timeout, since stopping them cannot damage anything.
- Electrical warning that software cannot enforce: the "H" in PY25D16HB means a 2.3 to 3.6 V part
  (VERIFIED from Puya's part-number coding). Program it at 3.3 V, never through a 1.8 V adapter, and not
  with an unmodified black CH341A board, which drives its data lines at 5 V. Disconnect the camera's
  battery first. See docs/02-RECOVERY.md before clipping anything.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Sequence

from . import __version__
from . import image as image_mod
from .checksum import BLOCK_SIZE, FLASH_SIZE

MAGIC = b"SPII"

# The marks every Charmera build carries, found by content (they sit at different offsets in different
# builds): the build string, the 12-digit stamp and the sensor init table. The stamp is the same in both
# known builds and identifies neither; see charmera_toolkit/image.py.
BUILD_MARKS = ("build_string", "stamp", "sensor_table")

# 0x1E0000..0x200000 is 0xFF in every known dump.
TAIL_START = 0x1E0000
BLOCK_COUNT = FLASH_SIZE // BLOCK_SIZE  # 32 blocks of 64 KiB

# Every read lands under <base>.unverified-read<n>.bin first, and only reads confirmed by an identical second
# one become <base>.bin: a single read, reads that differ and a run cut short leave names nobody takes for a
# backup.
UNVERIFIED_READ = "unverified-read"

# What software cannot check, printed before probe, backup and restore let flashrom near the chip. The first
# clip-on is the riskiest electrical step of the whole route: 5 V on the flash, and on the processor that
# shares its bus, can damage the camera before any backup of it exists (docs/02-RECOVERY.md, section B).
CHECKLIST = (
    "Before flashrom runs (nothing here can check this for you):",
    "  - the programmer runs at 3.3 V: a blue CH341A jumpered to 3.3 V, a V1.7 CH341A switched to 3.3 V, a",
    "    Pico running pico-serprog or a CH347. Never an unmodified black CH341A board: its data lines run at",
    "    5 V, which can damage the flash chip and the processor that shares its bus.",
    "  - the camera's battery lead is lifted from its pad and taped, so that the battery and the programmer",
    "    do not both power the board.",
    "  - the clip's pin 1 (red stripe or \"1\") sits on the chip's dot.",
    "If any of these is not so, press Ctrl+C and unplug the programmer: stopping a read harms nothing.",
)

# SHA-256 of the two public stock dumps. They are identical in the code and resource area and differ only
# in the settings store (0x170000) and two internally stored photos (0x180000, 0x1A5800) that belong to
# those owners. The owner's own backup is always the preferred restore source. A camera flashed with the
# public restore file reads exactly like the first of them until a setting changes, so a match can be this
# camera's own read after that flash (public_read_note).
PUBLIC_DUMPS: Dict[str, str] = {
    "cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f": "benbadio/keychain-camera-plus",
    "720ed0b65c91b149b0916498732b0ecf40227736ef5e3c126c487a7b5253436c": "Khimira/Project-Sirius",
}

# Coarse map of the image, used to label the block diff. Boundaries come from the settings module
# (0x170000), the photo offsets in the public dumps and the blank tail.
REGIONS = (
    (0x000000, 0x170000, "code and resources"),
    (0x170000, 0x180000, "settings store"),
    (0x180000, 0x1E0000, "internally stored photos"),
    (0x1E0000, 0x200000, "tail, 0xFF in every known dump"),
)

DEFAULT_PROGRAMMER = "ch341a_spi"
DEFAULT_CHIP = "SFDP-capable chip"
CHIP_AUTO = "auto"

# Seconds. A CH341A reads 2 MiB in well under two minutes; these are generous so a slow programmer
# (serprog on a Pico) is not cut off. There is deliberately no write timeout: subprocess kills the child
# when a timeout expires, and killing `flashrom -w` mid-erase is exactly what this tool must never do.
# The write and the read-back that follows it run with timeout=None.
VERSION_TIMEOUT = 30.0
PROBE_TIMEOUT = 120.0
READ_TIMEOUT = 900.0


class FlashromError(Exception):
    """flashrom could not be found or could not be run. The message is written for the owner."""


@dataclass
class FlashromResult:
    argv: List[str]
    returncode: int
    stdout: str
    stderr: str

    @property
    def output(self) -> str:
        parts = [p for p in (self.stdout, self.stderr) if p]
        return "\n".join(parts)

    @property
    def ok(self) -> bool:
        return self.returncode == 0


# ----------------------------------------------------------------------------------------------------
# Locating and running flashrom
# ----------------------------------------------------------------------------------------------------


def install_hints() -> str:
    """Per-OS pointers for getting flashrom and access to the CH341A. No invented download links."""
    system = platform.system()
    lines = []
    if system == "Windows":
        lines += [
            "Windows: flashrom.org lists Windows builds and the libusb requirement; a build made under",
            "  MSYS2 also works. Install the WinUSB (or libusb-win32) driver for the CH341A (USB ID",
            "  1a86:5512) with Zadig (zadig.akeo.ie) before running flashrom, or it will not see the",
            "  programmer. Pass --flashrom C:\\path\\to\\flashrom.exe if it is not on PATH.",
        ]
    elif system == "Darwin":
        lines += ["macOS: `brew install flashrom`."]
    else:
        lines += [
            "Linux: `sudo apt install flashrom` (Debian, Ubuntu), `sudo dnf install flashrom` (Fedora),",
            "  `sudo pacman -S flashrom` (Arch), or a build from flashrom.org.",
            "  The CH341A (USB ID 1a86:5512) needs udev access: install scripts/70-ch341a.rules",
            "  (see the comment at its top) or run the command with sudo.",
        ]
    lines.append("Point --flashrom at the executable if it is installed somewhere not on PATH.")
    return "\n".join(lines)


def _display(argv: Sequence[str]) -> str:
    """The command line as the current platform's shell would take it, for the plan and the echo.

    shlex.join quotes for a POSIX shell; on Windows the same text would not be the command that runs,
    so there the Windows quoting rules (subprocess.list2cmdline) are used instead.
    """
    argv = list(argv)
    if os.name == "nt":
        return subprocess.list2cmdline(argv)
    return shlex.join(argv)


def find_flashrom(explicit: Optional[str] = None) -> str:
    """Resolve the flashrom executable or raise FlashromError with install hints.

    An explicit path that names a file is returned absolute. A bare or ./relative name would otherwise
    pass the is_file check against the working directory and then be executed through PATH lookup
    (str(Path("./flashrom")) is "flashrom"), so the path shown and the one run could differ.
    """
    if explicit:
        candidate = Path(explicit)
        if candidate.is_file():
            return str(candidate.resolve())
        found = shutil.which(explicit)
        if found:
            return str(Path(found).resolve())
        raise FlashromError(f"--flashrom {explicit!r} is not a file and is not on PATH.\n" + install_hints())
    found = shutil.which("flashrom")
    if found:
        # On Windows, which() also looks in the working directory and may answer with a relative name.
        return str(Path(found).resolve())
    raise FlashromError("flashrom was not found on PATH.\n" + install_hints())


def run_flashrom(
    flashrom: str, args: Sequence[str], timeout: Optional[float], echo: bool = True
) -> FlashromResult:
    """Run flashrom with captured output. Raises FlashromError if it cannot run or outlives `timeout`.

    `timeout=None` waits for flashrom however long it takes; the write and the read-back after it use
    that, because an expired timeout kills the child process. A non-zero exit is returned, not raised,
    so callers can show flashrom's own message.
    """
    argv = [flashrom] + list(args)
    if echo:
        print("Running: " + _display(argv))
        sys.stdout.flush()
    try:
        proc = subprocess.run(
            argv,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
            errors="replace",
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        raise FlashromError(
            f"flashrom did not finish within {timeout or 0:.0f} s and was stopped: {_display(argv)}\n"
            "Check the clip contact, the programmer connection and that the camera battery is "
            "disconnected, then try again."
        ) from exc
    except OSError as exc:
        raise FlashromError(f"could not run {flashrom}: {exc}") from exc
    return FlashromResult(argv, proc.returncode, proc.stdout or "", proc.stderr or "")


def flashrom_version(flashrom: str) -> str:
    """First line of `flashrom --version` that names flashrom and a number."""
    res = run_flashrom(flashrom, ["--version"], VERSION_TIMEOUT, echo=False)
    for line in res.output.splitlines():
        if "flashrom" in line.lower() and re.search(r"\d", line):
            return line.strip()
    if not res.ok:
        raise FlashromError(f"{flashrom} --version failed (exit {res.returncode}):\n{res.output.strip()}")
    first = res.output.strip().splitlines()
    return first[0].strip() if first else "unknown"


def chip_args(chip: Optional[str]) -> List[str]:
    """`-c NAME`, or nothing when the chip is "auto" (let flashrom probe its whole table)."""
    if chip is None or chip.strip() == "" or chip.strip().lower() == CHIP_AUTO:
        return []
    return ["-c", chip]


def read_chip(
    flashrom: str,
    programmer: str,
    chip: Optional[str],
    out_path: Path,
    timeout: Optional[float] = READ_TIMEOUT,
) -> FlashromResult:
    """flashrom -r. Backup reads use the default timeout; the read-back after a write passes None."""
    return run_flashrom(flashrom, ["-p", programmer] + chip_args(chip) + ["-r", str(out_path)], timeout)


def write_chip(flashrom: str, programmer: str, chip: Optional[str], image_path: Path) -> FlashromResult:
    """flashrom -w: flashrom itself erases, writes and verifies. Only restore calls this.

    No timeout, ever: the tool must not be the thing that interrupts an erase or write in progress.
    """
    return run_flashrom(flashrom, ["-p", programmer] + chip_args(chip) + ["-w", str(image_path)], None)


# ----------------------------------------------------------------------------------------------------
# Parsing flashrom's probe output
# ----------------------------------------------------------------------------------------------------


@dataclass
class ProbeResult:
    found: List[Dict[str, str]] = field(default_factory=list)
    no_device: bool = False
    multiple: List[str] = field(default_factory=list)
    problems: List[str] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return bool(self.found) and not self.multiple and not self.no_device


_FOUND_RE = re.compile(
    r'Found (?P<vendor>.+?) flash chip "(?P<name>[^"]+)" \((?P<kb>\d+) kB, (?P<bus>[^)]+)\)'
    r"(?: on (?P<programmer>[^.\s]+))?"
)
_MULTI_RE = re.compile(r"Multiple flash chip definitions match the detected chip\(s\):(?P<names>.*)")


def parse_probe_output(text: str) -> ProbeResult:
    """Pick the lines that matter out of a flashrom run without an operation."""
    result = ProbeResult()
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        m = _FOUND_RE.search(line)
        if m:
            result.found.append(
                {
                    "vendor": m.group("vendor"),
                    "name": m.group("name"),
                    "size_kb": m.group("kb"),
                    "bus": m.group("bus"),
                    "programmer": m.group("programmer") or "",
                }
            )
            continue
        if line.startswith("No EEPROM/flash device found"):
            result.no_device = True
            continue
        m = _MULTI_RE.search(line)
        if m:
            result.multiple = re.findall(r'"([^"]+)"', m.group("names"))
            continue
        low = line.lower()
        if "sfdp" in low or "untested" in low or "please specify which chip" in low:
            result.notes.append(line)
            continue
        if (
            low.startswith("error")
            or "failed" in low
            or "libusb_error" in low
            or "could not" in low
            or "couldn't" in low
            or "permission" in low
            or "not found" in low
        ):
            result.problems.append(line)
    return result


ERASE_WRITE_MARKER = "Erasing and writing"


def write_stopped_before_chip(output: str) -> bool:
    """True when a failed `flashrom -w` evidently never reached the erase/write phase.

    flashrom prints "Erasing and writing flash chip..." once it starts changing the chip. Without that
    line, and with the probe-style symptoms (no device found, several chip definitions, an error line),
    the failure happened while opening the programmer or detecting the chip and the chip is untouched.
    Anything ambiguous is treated as a possible partial write.
    """
    if ERASE_WRITE_MARKER in output:
        return False
    parsed = parse_probe_output(output)
    return parsed.no_device or bool(parsed.multiple) or bool(parsed.problems)


# ----------------------------------------------------------------------------------------------------
# Image checks
# ----------------------------------------------------------------------------------------------------


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def region_name(offset: int) -> str:
    for start, end, name in REGIONS:
        if start <= offset < end:
            return name
    return "beyond 2 MiB"


def build_marks_found(data: bytes) -> Dict[str, Optional[dict]]:
    """Each build mark found by content, with where it was found, or None when it is missing."""
    m = image_mod.find_marks(data)
    return {
        "build_string": None if m.build_string is None else {"offset": f"0x{m.build_offset:X}",
                                                               "value": m.build_string.decode("ascii", "replace")},
        "stamp": None if m.stamp is None else {"offset": f"0x{m.stamp_offset:X}", "value": m.stamp},
        "sensor_table": None if m.sensor is None else {"offset": f"0x{m.sensor.init[0]:X}", "writes": m.sensor.pairs,
                                                       "sha256": m.sensor.sha256},
    }


def analyze_image(data: bytes) -> dict:
    """Everything the manifest and the restore gate want to know about an image."""
    sha = sha256_hex(data)
    marks = build_marks_found(data)
    match = image_mod.detect_build(data)
    tail = data[TAIL_START:]
    return {
        "size": len(data),
        "size_ok": len(data) == FLASH_SIZE,
        "sha256": sha,
        "magic_ok": data[: len(MAGIC)] == MAGIC,
        "build_marks": marks,
        "build_marks_ok": all(marks[k] is not None for k in BUILD_MARKS),
        "build": match.identity,
        "build_label": match.label,
        "tail_blank": len(data) == FLASH_SIZE and tail == b"\xff" * len(tail),
        "public_dump": PUBLIC_DUMPS.get(sha),
    }


def image_warnings(info: dict) -> List[str]:
    out = []
    if not info["size_ok"]:
        out.append(f"size is {info['size']:,} bytes, expected {FLASH_SIZE:,} (2 MiB)")
    if not info["magic_ok"]:
        out.append('the first four bytes are not the "SPII" magic')
    missing = [k for k in BUILD_MARKS if info["build_marks"][k] is None]
    if missing:
        out.append("no Charmera build marks found: " + ", ".join(missing).replace("_", " ") + " missing")
    if info["size_ok"] and not info["tail_blank"]:
        out.append(f"the tail 0x{TAIL_START:X}..0x{FLASH_SIZE:X} is not all 0xFF (it is in every known dump)")
    return out


@dataclass
class BlockDiff:
    index: int
    start: int
    end: int
    differing_bytes: int
    region: str


def block_diff(image: bytes, backup: bytes) -> List[BlockDiff]:
    """64 KiB blocks where the image and the backup differ. A length difference counts as differing."""
    total = max(len(image), len(backup))
    out = []
    for i in range((total + BLOCK_SIZE - 1) // BLOCK_SIZE):
        start = i * BLOCK_SIZE
        end = min(start + BLOCK_SIZE, total)
        a = image[start:end]
        b = backup[start:end]
        if a == b:
            continue
        differing = sum(1 for x, y in zip(a, b) if x != y) + abs(len(a) - len(b))
        out.append(BlockDiff(i, start, end, differing, region_name(start)))
    return out


def print_block_diff(image: bytes, chip: bytes) -> List[BlockDiff]:
    """The image against the read restore made of the chip a moment ago: the blocks the write changes."""
    diffs = block_diff(image, chip)
    blocks_total = (max(len(image), len(chip)) + BLOCK_SIZE - 1) // BLOCK_SIZE
    if not diffs:
        print(f"Block diff: the image is identical to the chip as read just now in all {blocks_total} blocks of 64 KiB.")
        return diffs
    print(f"Block diff against the chip as read just now (64 KiB blocks), {len(diffs)} of {blocks_total} differ:")
    for d in diffs:
        print(
            f"  block {d.index:2d}  0x{d.start:06X}-0x{d.end - 1:06X}  {d.differing_bytes:7,d} bytes differ"
            f"  ({d.region})"
        )
    print(f"  {blocks_total - len(diffs)} blocks are identical between the image and the chip; the write leaves their content as it is.")
    return diffs


# ----------------------------------------------------------------------------------------------------
# Commands
# ----------------------------------------------------------------------------------------------------


def _fail(message: str) -> int:
    print("error: " + message, file=sys.stderr)
    return 1


def _print_indented(text: str, prefix: str = "  | ") -> None:
    for line in text.rstrip().splitlines():
        print(prefix + line)


def _safe_label(label: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "_", label.strip())
    return cleaned.strip("._") or "charmera"


def _ask(prompt: str) -> str:
    """Wrapper around input() so a missing stdin counts as a refusal."""
    try:
        return input(prompt)
    except EOFError:
        return ""


def print_checklist() -> None:
    for line in CHECKLIST:
        print(line)
    sys.stdout.flush()


def _note(lines: Sequence[str], first: str = "note: ") -> None:
    """A note of several lines, the later ones indented under the first."""
    print(first + lines[0])
    for line in lines[1:]:
        print(" " * len(first) + line)


def public_read_note(source: str) -> List[str]:
    """What a read of the chip that is byte-identical to a public dump is, and what it is not."""
    return [
        f"this read is byte-identical to the public dump from {source}. A camera flashed with the",
        "public restore file, or with another file made from that dump, reads exactly like that until a",
        "setting changes, so this may be this camera after such a flash. It is then the way back to that",
        "state, not a reference for which image sensor the camera has. The app does not take it as a",
        "backup: byte for byte it cannot be told from someone else's copy of that dump.",
    ]


def public_image_note(source: str) -> List[str]:
    """What an image to be written that is byte-identical to a public dump is, and the risk it carries."""
    return [
        f"this image is byte-identical to the public dump from {source}. It is that dump, or the read",
        "of a camera flashed with the public restore file (or another file made from that dump) that changed",
        "no setting since, such as a camera after the stock restore flash, and then the way back to that",
        "state. Either way it is not firmware known to fit this camera: after it was flashed, one camera could",
        "no longer take pictures; the likely cause is a sensor it does not drive (INFERRED). Its settings and",
        "its two internal photos are that dump's owner's.",
    ]


def cmd_probe(args: argparse.Namespace) -> int:
    print_checklist()
    try:
        flashrom = find_flashrom(args.flashrom)
        print(f"flashrom: {flashrom}")
        print(f"version:  {flashrom_version(flashrom)}")
        res = run_flashrom(flashrom, ["-p", args.programmer] + chip_args(args.chip), PROBE_TIMEOUT)
    except FlashromError as exc:
        return _fail(str(exc))

    print("flashrom output:")
    _print_indented(res.output or "(no output)")
    parsed = parse_probe_output(res.output)

    if parsed.found:
        for chip in parsed.found:
            where = f" on {chip['programmer']}" if chip["programmer"] else ""
            print(f'Found: {chip["vendor"]} "{chip["name"]}", {chip["size_kb"]} kB, {chip["bus"]}{where}')
            if chip["size_kb"] != str(FLASH_SIZE // 1024):
                print(
                    f"  warning: expected {FLASH_SIZE // 1024} kB (PY25D16HB). A different size usually "
                    "means a bad clip contact, a back-powered board or the wrong chip definition."
                )
    for note in parsed.notes:
        print(f"note: {note}")
    for problem in parsed.problems:
        print(f"problem: {problem}")

    if parsed.multiple:
        print("flashrom matched several chip definitions: " + ", ".join(f'"{n}"' for n in parsed.multiple))
        print("Pick one with --chip NAME. For this camera the community used --chip \"SFDP-capable chip\".")
        return 1
    if parsed.no_device or not parsed.found:
        if parsed.no_device:
            print("No flash chip was detected.")
        else:
            print("flashrom did not report a chip.")
        print(
            "Usual causes: the clip is not seated or is rotated (pin 1 dot), the camera battery is still\n"
            "connected, the programmer is not at 3.3 V, or flashrom cannot open the USB device (udev rule\n"
            "or sudo on Linux, Zadig driver on Windows). If you ran with --chip auto, retry with the\n"
            'default --chip "SFDP-capable chip": the Puya part is not in flashrom\'s table by name.'
        )
        return 1
    if not res.ok:
        print(f"flashrom exited with status {res.returncode}; treat the result above with care.")
        return 1
    return 0


@dataclass
class KeptRead:
    """A read of the chip kept the way backup keeps it. `problem` says why it cannot serve as one: a read
    failed, the reads differ, or the read is not 2 MiB. The caller then stops, and nothing is written to the
    chip."""
    problem: Optional[str] = None
    path: Optional[Path] = None
    data: bytes = b""
    info: dict = field(default_factory=dict)
    sha_path: Optional[Path] = None
    manifest_path: Optional[Path] = None


def read_and_keep(flashrom: str, version: str, programmer: str, chip: Optional[str], out_dir: Path, label: str,
                  reads: int, command: str, extra: Optional[dict] = None) -> KeptRead:
    """Read the chip `reads` times into out_dir and keep the result the way backup does; `command` (backup or
    restore) is the command the messages name.

    Each read is written as <base>.unverified-read<n>.bin. When two or more reads are identical the first
    becomes <base>.bin and the others go; a single read keeps its name. The kept read gets a .sha256 and a
    .manifest.json (`extra` adds to the manifest). Reads that differ are all kept under their names for
    diagnosis, with nothing else.
    """
    label = _safe_label(label)
    started = datetime.now(timezone.utc)
    base = f"{label}_{started.strftime('%Y%m%d-%H%M%SZ')}"
    paths: List[Path] = []
    datas: List[bytes] = []
    print(f"Reading the chip {reads} time(s) through {programmer}, chip {chip!r}.")
    try:
        for i in range(1, reads + 1):
            path = out_dir / f"{base}.{UNVERIFIED_READ}{i}.bin"
            res = read_chip(flashrom, programmer, chip, path)
            if not res.ok:
                print("flashrom output:")
                _print_indented(res.output or "(no output)")
                return KeptRead(problem=(
                    f"flashrom read {i} failed (exit {res.returncode}). Nothing was written to the chip.\n"
                    "Check the clip contact and orientation, that the battery is disconnected, that the\n"
                    "programmer runs at 3.3 V, and that flashrom can open the USB device. Run `probe` first."
                ))
            if not path.is_file():
                return KeptRead(problem=f"flashrom exited 0 but {path} does not exist. Nothing was written to the chip.")
            data = path.read_bytes()
            print(f"read {i}: {len(data):,} bytes, sha256 {sha256_hex(data)}")
            paths.append(path)
            datas.append(data)
    except FlashromError as exc:
        return KeptRead(problem=str(exc) + "\nNothing was written to the chip.")

    first = datas[0]
    mismatched = False
    for path, other in zip(paths[1:], datas[1:]):
        if other == first:
            continue
        mismatched = True
        first_diff = next((k for k, (x, y) in enumerate(zip(first, other)) if x != y), min(len(first), len(other)))
        differing = sum(1 for x, y in zip(first, other) if x != y) + abs(len(first) - len(other))
        print(f"{path.name} differs from {paths[0].name}: first difference at 0x{first_diff:X}, {differing:,} bytes")
    if mismatched:
        print("The reads are kept for diagnosis, under names that say no read confirmed them:")
        for path in paths:
            print(f"  {path}")
        stopped = "no backup was written" if command == "backup" else "nothing was written to the chip"
        return KeptRead(problem=(
            f"the reads are not identical, so {stopped}. Unstable reads usually mean a poor\n"
            "clip contact or a back-powered board (battery still connected). Reseat the clip, disconnect\n"
            f"the battery and run {command} again; compare the kept reads to see which bytes move."
        ))

    if reads >= 2:
        final = out_dir / f"{base}.bin"
        paths[0].replace(final)
        for path in paths[1:]:
            path.unlink()
    else:
        final = paths[0]   # no second read confirmed it, so it keeps the name that says so

    info = analyze_image(first)
    warnings = image_warnings(info)
    for w in warnings:
        print(f"warning: {w}")
    if warnings and (not info["magic_ok"] or not info["size_ok"]):
        print("The file is kept anyway: a blank or corrupt chip still deserves a backup of what is on it.")
    if reads < 2:
        print("warning: a single read was not cross-checked against a second one; an unstable clip contact")
        print(f"         would go unnoticed. It is kept as {final.name},")
        print("         which is not a backup: use --reads 2 or more for a backup you intend to restore from.")
    sha = info["sha256"]
    sha_path = out_dir / f"{base}.sha256"
    sha_path.write_text(f"{sha}  {final.name}\n", encoding="utf-8")

    manifest = {
        "toolkit_version": __version__,
        "command": command,
        "timestamp_utc": started.replace(microsecond=0).isoformat(),
        "label": label,
        "file": final.name,
        "sha256_file": sha_path.name,
        "flashrom_path": flashrom,
        "flashrom_version": version,
        "programmer": programmer,
        "chip": chip if chip_args(chip) else CHIP_AUTO,
        "reads": reads,
        # True only when there was a second read to compare with; a single read proves nothing.
        "reads_identical": True if reads >= 2 else None,
        "size": info["size"],
        "size_ok": info["size_ok"],
        "sha256": sha,
        "magic_ok": info["magic_ok"],
        "build_marks": info["build_marks"],
        "build_marks_ok": info["build_marks_ok"],
        "build": info["build"],
        "tail_blank": info["tail_blank"],
        "public_dump": info["public_dump"],
        "warnings": warnings,
    }
    manifest.update(extra or {})
    manifest_path = out_dir / f"{base}.manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    kept = KeptRead(path=final, data=first, info=info, sha_path=sha_path, manifest_path=manifest_path)

    if not info["size_ok"]:
        # The read is kept for diagnosis, but it is not a backup: restore refuses anything but 2 MiB, and a chip
        # that does not read as 2 MiB is not being read whole.
        print(f"kept:     {final}")
        print(f"          {sha_path}")
        print(f"          {manifest_path}")
        consequence = ("and restore will refuse it" if command == "backup"
                       else "and the chip is not being read whole, so nothing was written to it")
        kept.problem = (
            f"the read is {info['size']:,} bytes, not {FLASH_SIZE:,}: this file is not a full-flash image\n"
            f"{consequence}. `probe` should report a 2048 kB chip; a different size usually\n"
            "means a bad clip contact, a back-powered board (battery still connected) or the wrong --chip.\n"
            f"Fix that and run {command} again. The file above is kept only as a record of what was read."
        )
    return kept


def cmd_backup(args: argparse.Namespace) -> int:
    out_dir = Path(args.out)
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        return _fail(f"cannot create {out_dir}: {exc}")
    if args.reads < 1:
        return _fail("--reads must be at least 1")

    print_checklist()
    try:
        flashrom = find_flashrom(args.flashrom)
        version = flashrom_version(flashrom)
    except FlashromError as exc:
        return _fail(str(exc))
    print(f"flashrom: {flashrom}")
    print(f"version:  {version}")
    kept = read_and_keep(flashrom, version, args.programmer, args.chip, out_dir, args.label, args.reads, "backup")
    if kept.problem:
        return _fail(kept.problem)

    what = "backup:" if args.reads >= 2 else "read:"
    print(f"{what:10s}{kept.path}")
    print(f"sha256:   {kept.info['sha256']}")
    print(f"checksum: {kept.sha_path}")
    print(f"manifest: {kept.manifest_path}")
    if kept.info["public_dump"]:
        _note(public_read_note(kept.info["public_dump"]))
    if args.reads >= 2:
        print("Keep the three files together, and keep a second copy somewhere else.")
    else:
        print("Keep the three files together. One read is not a backup: run backup again with --reads 2 or more.")
    return 0


def cmd_restore(args: argparse.Namespace) -> int:
    image_path = Path(args.image)
    try:
        image = image_path.read_bytes()
    except OSError as exc:
        return _fail(f"cannot read {image_path}: {exc}")

    info = analyze_image(image)
    refusals: List[str] = []
    if not info["size_ok"]:
        refusals.append(f"{image_path.name} is {info['size']:,} bytes; a full-flash image is exactly {FLASH_SIZE:,}")
    if not info["magic_ok"]:
        refusals.append(f'{image_path.name} does not start with the "SPII" magic')
    if not info["build_marks_ok"]:
        missing = ", ".join(k.replace("_", " ") for k in BUILD_MARKS if info["build_marks"][k] is None)
        text = f"this image carries no Charmera build marks ({missing} missing)"
        if args.allow_unknown_build:
            print(f"warning: {text}; continuing because --allow-unknown-build was given.")
            print("Be sure this image is a dump of this camera's own chip.")
        else:
            refusals.append(
                text + ". A firmware that does not fit the camera boots but freezes photo and video mode\n"
                "(seen on hardware). Pass --allow-unknown-build only if you know this image is this camera's own."
            )
    if refusals:
        for r in refusals:
            print(f"refusing: {r}", file=sys.stderr)
        print("Nothing was written.", file=sys.stderr)
        return 1

    print(f"image:    {image_path}")
    print(f"size:     {info['size']:,} bytes")
    print(f"sha256:   {info['sha256']}")
    print("magic:    SPII")
    print(f"build:    {info['build']} ({info['build_label']})")
    print("          a build match says which public image this is, not whether it fits this camera; two builds share the stamp")
    for k in BUILD_MARKS:
        v = info["build_marks"][k]
        if v is None:
            print(f"mark:     {k.replace('_', ' ')} (missing)")
        elif k == "sensor_table":
            print(f"mark:     sensor table at {v['offset']}, {v['writes']} writes, sha256 {v['sha256'][:16]}...")
        else:
            print(f"mark:     {k.replace('_', ' ')} {v['value']!r} at {v['offset']}")
    print(f"tail:     {'all 0xFF' if info['tail_blank'] else 'NOT all 0xFF (every known dump is)'}")
    if info["public_dump"]:
        _note(public_image_note(info["public_dump"]), "note:     ")

    out_dir = Path(args.out)
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        return _fail(f"cannot create {out_dir}: {exc}")

    print_checklist()
    try:
        flashrom = find_flashrom(args.flashrom)
        version = flashrom_version(flashrom)
    except FlashromError as exc:
        return _fail(str(exc))
    print(f"flashrom: {flashrom}")
    print(f"version:  {version}")

    # No backup of the chip on the clip, no write. restore reads it itself, twice: a file given from elsewhere
    # could not prove that it came from this chip as it is now.
    print(f"Before anything is written, the chip is read twice and kept in {out_dir}, as backup keeps it.")
    kept = read_and_keep(flashrom, version, args.programmer, args.chip, out_dir, args.label, 2, "restore",
                         {"before_writing": {"image": image_path.name, "sha256": info["sha256"]}})
    if kept.problem:
        return _fail(kept.problem)
    print(f"kept:     {kept.path}")
    print(f"sha256:   {kept.info['sha256']}")
    print(f"checksum: {kept.sha_path}")
    print(f"manifest: {kept.manifest_path}")
    print(f"chip:     {kept.info['build']} ({kept.info['build_label']})")
    if kept.info["public_dump"]:
        _note(public_read_note(kept.info["public_dump"]), "note:     ")

    print_block_diff(image, kept.data)
    if kept.data == image:
        print("The chip already holds this image, byte for byte: nothing to write.")
        return 0
    second_word = print_sensor_comparison(info["build_marks"]["sensor_table"], kept.info["build_marks"]["sensor_table"])

    work = Path(tempfile.mkdtemp(prefix="charmera-restore-"))
    copy_path = work / "image.bin"
    try:
        return _write_from_private_copy(args, flashrom, image_path, image, info["sha256"], kept.path, second_word,
                                        copy_path)
    finally:
        try:
            copy_path.unlink()
        except OSError:
            pass
        try:
            work.rmdir()   # stays when a read-back that does not match the image is kept in it
        except OSError:
            pass


def _table_text(table: Optional[dict]) -> str:
    if table is None:
        return "none of the kind the known builds use"
    return f"{table['writes']} writes at {table['offset']}, sha256 {table['sha256'][:16]}..."


def print_sensor_comparison(image_table: Optional[dict], chip_table: Optional[dict]) -> Optional[List[str]]:
    """The image's sensor table against the one on the chip now (build_marks_found's entries). When the two are not
    the same, one of them missing included, restore asks a second word before it writes: the lines it prints then are
    returned. None when the tables are the same, or when neither side has one and nothing can be compared."""
    print(f"sensor:   image {_table_text(image_table)}")
    print(f"          chip  {_table_text(chip_table)}")
    indent = " " * 10
    if chip_table is None and image_table is None:
        _note(["Neither the image nor the chip holds a sensor table of the kind the known builds use, so nothing",
               "here can compare the two."], indent)
        return None
    if image_table is not None and chip_table is not None and image_table["sha256"] == chip_table["sha256"]:
        _note(["The same table: the image sets up the image sensor as the firmware on the chip does. That",
               "firmware fits this camera only if it is the one the camera came with, not one flashed onto it since."],
              indent)
        return None
    if chip_table is None:
        # A blank or damaged chip, or one running another kind of firmware: not the same as an image with a table.
        first = ["DIFFERENT: the chip holds no sensor table of the kind the known builds use (it is blank or",
                 "damaged, or holds another kind of firmware), the image has one, so the two are not the same."]
        ask = ["The chip holds no sensor table of the kind the known builds use and the image has one (the sensor",
               "lines above): nothing here can tell whether that firmware fits this camera."]
    elif image_table is None:
        first = ["DIFFERENT: the image has no sensor table of that kind, the firmware on the chip has one."]
        ask = ["The image has no sensor table of the kind the known builds use and the firmware on the chip has one",
               "(the sensor lines above): nothing here can tell whether that firmware fits this camera."]
    else:
        first = ["DIFFERENT: the image sets up the image sensor differently from the firmware on the chip."]
        ask = ["The image sets up the image sensor differently from the firmware on the chip (the sensor lines",
               "above): nothing here can tell whether that firmware fits this camera."]
    _note(first + [
           "Nothing here can tell whether that firmware fits this camera: compare the marking on this camera's",
           "sensor module with the one in the camera the image was read from, and even matching markings leave",
           "other board differences possible. If it does not start, only a chip programmer can put back the",
           "read kept above."], indent)
    return ask


def _write_from_private_copy(args: argparse.Namespace, flashrom: str, image_path: Path, image: bytes, image_sha: str,
                             kept_path: Path, second_word: Optional[List[str]], copy_path: Path) -> int:
    """Copy the checked bytes to copy_path in a private folder, show the plan, ask, check the copy again, have
    flashrom write that copy and read the chip back into the same folder. The owner's file is never handed to
    flashrom: what it holds after the check cannot reach the chip."""
    try:
        with open(copy_path, "xb") as f:
            f.write(image)
    except OSError as exc:
        return _fail(f"cannot write a private copy of the image to {copy_path}: {exc}\nNothing was written to the chip.")

    write_argv = [flashrom, "-p", args.programmer] + chip_args(args.chip) + ["-w", str(copy_path)]
    print("Plan:")
    print(f"  1. {_display(write_argv)}")
    print("     The file it writes is a private copy of the bytes checked above. Its SHA-256 is checked again")
    print(f"     just before flashrom runs, so nothing done to {image_path.name} from now on reaches the chip.")
    print("     flashrom erases, writes and verifies the whole 2 MiB chip. This tool sets no time limit on")
    print("     that step and never interrupts it; it waits for flashrom to finish, however long it takes.")
    print("  2. read the chip back once (also without a time limit) and compare its SHA-256 with the image.")
    print(f"The way back is the read kept above, {kept_path}: the chip as it is now.")
    print("Do not move the clip or unplug anything until this command finishes.")

    answer = _ask("Type RESTORE to write the chip, anything else to stop: ")
    if answer.strip() != "RESTORE":
        print("Stopped. Nothing was written.")
        return 1
    if second_word:
        for line in second_word:
            print(line)
        answer = _ask("Type DIFFERENT to write it all the same, anything else to stop: ")
        if answer.strip() != "DIFFERENT":
            print("Stopped. Nothing was written.")
            return 1

    # What flashrom gets is what was checked, or nothing.
    try:
        copied = copy_path.read_bytes()
    except OSError as exc:
        return _fail(f"cannot read the private copy {copy_path} again: {exc}\nNothing was written to the chip.")
    if sha256_hex(copied) != image_sha:
        return _fail(f"the private copy {copy_path} no longer has the SHA-256 checked above: something changed it\n"
                     "after the check. Nothing was written to the chip. Run restore again.")

    try:
        res = write_chip(flashrom, args.programmer, args.chip, copy_path)
    except FlashromError as exc:
        return _fail(str(exc) + "\nThe chip may be partly written. Keep the clip on and retry restore.")
    print("flashrom output:")
    _print_indented(res.output or "(no output)")
    if not res.ok:
        if write_stopped_before_chip(res.output):
            return _fail(
                f"flashrom -w failed (exit {res.returncode}). flashrom stopped before touching the chip, so\n"
                "the chip still holds what it held before. Usual causes: the clip is not seated or is\n"
                "rotated (pin 1 dot), the camera battery is still connected, the programmer is not at 3.3 V,\n"
                "or flashrom cannot open the USB device (udev rule or sudo on Linux, Zadig driver on\n"
                "Windows). Run `probe` until it reports a 2048 kB chip, then run restore again."
            )
        return _fail(
            f"flashrom -w failed (exit {res.returncode}). The chip may hold a mix of old and new data.\n"
            "Next steps: keep everything connected, do not power the camera, check the output above for\n"
            "the reason (contact, power, verify failure), reseat the clip and run restore again until\n"
            "flashrom reports VERIFIED. Only then disconnect."
        )

    readback_path = copy_path.with_name("readback.bin")
    try:
        # No timeout here either: the chip was just written and nothing should cut this short.
        res = read_chip(flashrom, args.programmer, args.chip, readback_path, timeout=None)
    except FlashromError as exc:
        return _fail(str(exc) + "\nThe write finished but the read-back could not run. Run `backup` to check.")
    if not res.ok or not readback_path.is_file():
        print("flashrom output:")
        _print_indented(res.output or "(no output)")
        return _fail(
            f"the read-back after writing failed (exit {res.returncode}). The write itself reported\n"
            "success, but it is not confirmed. Run `backup` and compare its SHA-256 with the image."
        )
    readback = readback_path.read_bytes()
    readback_sha = sha256_hex(readback)
    print(f"read-back: {len(readback):,} bytes, sha256 {readback_sha}")
    if readback_sha != image_sha:
        diffs = block_diff(image, readback)
        print(f"{len(diffs)} block(s) differ between the image and the read-back:")
        for d in diffs[:16]:
            print(f"  block {d.index:2d}  0x{d.start:06X}  {d.differing_bytes:,} bytes  ({d.region})")
        print(f"The read-back is kept at {readback_path}")
        return _fail(
            "the chip does not match the image after writing.\n"
            "Next steps: do not power the camera; check the clip contact and that the battery is\n"
            "disconnected; run `probe`; then run restore again. If it keeps failing on the same blocks,\n"
            "compare the kept read-back with the image to see whether the bytes are stuck or random."
        )
    try:
        readback_path.unlink()
    except OSError:
        pass
    print("Restore verified: the chip now matches the image.")
    print("Disconnect the clip, reconnect the battery and power the camera on.")
    return 0


# ----------------------------------------------------------------------------------------------------
# Argument parsing
# ----------------------------------------------------------------------------------------------------


def _add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--flashrom", metavar="PATH", help="flashrom executable (default: search PATH)")
    parser.add_argument(
        "--programmer",
        default=DEFAULT_PROGRAMMER,
        metavar="NAME",
        help=f"flashrom programmer string, passed to -p (default: {DEFAULT_PROGRAMMER}; "
        "a Pico running pico-serprog is serprog:dev=/dev/ttyACM0 or serprog:dev=COM3)",
    )
    parser.add_argument(
        "--chip",
        default=DEFAULT_CHIP,
        metavar="NAME",
        help=f'flashrom chip definition, passed to -c (default: "{DEFAULT_CHIP}", which is how the '
        "community read this Puya part; `auto` omits -c and lets flashrom probe)",
    )


def register(subparsers) -> None:
    probe = subparsers.add_parser(
        "probe",
        help="detect the flash chip through flashrom (read-only)",
        description="Print the checklist (3.3 V programmer, battery lead lifted, pin 1 on the dot), show flashrom's "
        "version and ask it to detect the chip on the programmer. Never reads or writes the chip contents.",
    )
    _add_common(probe)
    probe.set_defaults(func=cmd_probe)

    backup = subparsers.add_parser(
        "backup",
        help="read the whole 2 MiB flash through flashrom, N times, and keep it only if the reads agree",
        description="Print the checklist, read the chip --reads times (default 2), require the reads to be identical, "
        "check size and magic, and write <label>_<UTC time>.bin with a .sha256 and a .manifest.json next to it. "
        "A single read, and reads that differ, keep the name <label>_<UTC time>.unverified-read<n>.bin: they are "
        "not a backup. A chip that fails the checks is still saved, with warnings. Never writes to the chip.",
    )
    _add_common(backup)
    backup.add_argument("--out", required=True, metavar="DIR", help="directory for the backup files")
    backup.add_argument("--reads", type=int, default=2, metavar="N", help="how many times to read (default 2)")
    backup.add_argument("--label", default="charmera", metavar="NAME", help="file name prefix (default charmera)")
    backup.set_defaults(func=cmd_backup)

    restore = subparsers.add_parser(
        "restore",
        help="read the chip twice, then write a full 2 MiB image to it through flashrom (asks for a typed RESTORE)",
        description="Check the image (2 MiB, SPII magic, Charmera build marks found by content), read the chip twice "
        "and keep that read in --out as backup does (nothing is written when the reads differ), show a 64 KiB block "
        "diff against it, the two sensor tables, the SHA-256 and the plan, wait for the word RESTORE (and DIFFERENT "
        "when the sensor tables differ or only one of the two has one), then run flashrom -w (erase, write, verify) on "
        "a private copy of the checked image and read the chip back to compare.",
    )
    _add_common(restore)
    restore.add_argument("--image", required=True, metavar="FILE", help="full 2 MiB image to write")
    restore.add_argument(
        "--out",
        required=True,
        metavar="DIR",
        help="directory for the read of the chip restore makes before it writes, kept with a .sha256 and a "
        ".manifest.json as backup keeps them: the way back if the image does not suit the camera",
    )
    restore.add_argument("--label", default="before-restore", metavar="NAME",
                         help="file name prefix of that read (default before-restore)")
    restore.add_argument(
        "--allow-unknown-build",
        action="store_true",
        help="write an image that carries no Charmera build marks (a firmware that does not fit the camera boots but freezes capture)",
    )
    restore.set_defaults(func=cmd_restore)
