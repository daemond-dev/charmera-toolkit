"""`stage`: put a verified update file on the card the way the camera's updater expects it.

What the updater does (VERIFIED in the firmware and the SDK source; docs/PHASE1-REPORT.md section 4,
docs/FORMAT.md): on power-on it looks only in the card root, only during the first 5 seconds, for the
first file matching JH_5408*.bin. The 12 digits after JH_5408 must differ from the stamp compiled into the
running firmware or the file is ignored. The 8 hex digits after the underscore must equal the 32-bit byte
sum of the file or the camera shows "Program error" and erases nothing. The file is not deleted after
flashing, so a card left in the camera flashes again at every power-on. Two update files on one card is
an undefined situation.

So this command runs `verify` again and refuses on any FAIL; accepts only a mount point or drive root
that holds no other JH_*.bin and has room for the file; prints the plan and the steps that follow on the
camera; waits for the card path to be typed back; then writes to a temporary name the camera ignores,
fsyncs, renames to the final name, reads the copy back and compares hashes. Without the typed
confirmation nothing is written. `--plan-only` stops after the plan.
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional, Tuple

from .checksum import byte_sum
from .verify import Check, verify_update

OVERRIDE_FLAG = "--i-know-what-i-am-doing"
# The copy is written under this name first. It does not match JH_5408*.bin, so a half-written file is
# invisible to the camera if the card is pulled or the run is interrupted.
TEMP_NAME = "charmera-stage.part"
FREE_SPACE_SLACK = 0x10000     # a directory entry plus cluster rounding
CHUNK = 1 << 20
MIB = 1024 * 1024

# Filesystem names as Linux (/proc/mounts) and Windows (GetVolumeInformation) report them. The camera
# formats cards as exFAT and reads exFAT and FAT32; the second set it certainly cannot read.
FAT_FAMILY = {"exfat", "vfat", "msdos", "fat", "fat12", "fat16", "fat32"}
NOT_FAT = {"ntfs", "ntfs3", "refs", "ext2", "ext3", "ext4", "btrfs", "xfs", "f2fs", "zfs", "hfs", "hfsplus", "apfs",
           "udf", "iso9660", "tmpfs", "overlay", "nfs", "nfs4", "cifs", "smbfs"}

CAMERA_STEPS = [
    "1. Camera off. Put the card in.",
    "2. Hold the power key. Within its first five seconds the camera finds the file and the screen shows the",
    "   update progress (\"Upgrade NN%\", about 15 to 20 seconds). \"Program error\" means the checksum in the name",
    "   did not match the file; nothing was erased, remove the card and run verify again.",
    "3. When the progress has reached 100% and the screen stops changing (the updater waits there; it does not",
    "   restart by itself), hold the power key until the camera switches off. Take the card out, then delete the",
    "   update file from it before using that card in the camera again: the camera does not delete the file",
    "   and would flash again at every power-on.",
]


class StageError(Exception):
    """A refusal. `code` is the exit code: 1 when a check failed, 2 for a usage problem."""

    def __init__(self, message: str, code: int = 1):
        super().__init__(message)
        self.code = code


@dataclass
class CardInfo:
    given: str                      # --card as the user typed it (what the confirmation echoes)
    root: Path                      # normalized root the file goes into
    is_mount: bool
    fstype: Optional[str]
    device: Optional[str]           # Linux: the block device behind the mount
    label: Optional[str]            # Windows: the volume label
    removable: Optional[bool]       # None when the platform does not say
    total: int
    free: int
    has_dcim: bool
    update_files: List[Path]        # JH_*.bin in the root, case-insensitive
    notes: List[str] = field(default_factory=list)


# --- Paths and platforms --------------------------------------------------------------------------------


def normalize_card(given: str) -> Path:
    """The root directory for --card. On Windows a bare drive letter (E: or E:\\) becomes E:\\, because
    Path('E:') alone means the current directory of drive E, not its root."""
    s = given.strip()
    if os.name == "nt":
        drive, rest = os.path.splitdrive(s)
        if drive and rest in ("", "\\", "/"):
            return Path(drive + "\\")
    return Path(s)


def is_drive_root(path: Path) -> bool:
    """True for a mount point (POSIX, or a Windows volume mounted in a folder) or a Windows drive root."""
    try:
        return os.path.ismount(str(path))
    except (OSError, ValueError):
        return False


def looks_like_update_file(name: str) -> bool:
    """Case-insensitive JH_*.bin: wider than the camera's own JH_5408*.bin on purpose."""
    upper = name.upper()
    return upper.startswith("JH_") and upper.endswith(".BIN")


_OCTAL_ESCAPE = re.compile(r"\\([0-7]{3})")


def _unescape_mount_field(s: str) -> str:
    """/proc/mounts writes a space as \\040, a tab as \\011, a backslash as \\134."""
    return _OCTAL_ESCAPE.sub(lambda m: chr(int(m.group(1), 8)), s)


def linux_mount_entry(root: Path, mounts_path: str = "/proc/mounts") -> Tuple[Optional[str], Optional[str]]:
    """(device, filesystem type) of the mount at `root`, or (None, None). The last matching line wins,
    because a later mount on the same point shadows an earlier one."""
    try:
        target = os.path.realpath(str(root))
        with open(mounts_path, encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
    except OSError:
        return None, None
    found: Tuple[Optional[str], Optional[str]] = (None, None)
    for line in lines:
        fields = line.split()
        if len(fields) < 3:
            continue
        if _unescape_mount_field(fields[1]) == target:
            found = (_unescape_mount_field(fields[0]), fields[2])
    return found


def linux_removable(device: str, sys_root: str = "/sys") -> Optional[bool]:
    """Whether the whole disk behind a device such as /dev/mmcblk0p1 or /dev/sdb1 is removable, from
    sysfs through card.py's rule (an SD card in a built-in slot reports removable=0 but is one)."""
    if not device.startswith("/dev/"):
        return None
    name = os.path.basename(os.path.realpath(device))
    if not name:
        return None
    link = os.path.join(sys_root, "class", "block", name)
    parent = os.path.basename(os.path.dirname(os.path.realpath(link)))
    disk = name if parent == "block" else parent
    try:
        from .card import linux_removable_info
        removable, _bus = linux_removable_info(disk, sys_root)
    except Exception:
        return None
    return removable


def windows_volume_info(root: Path) -> Tuple[Optional[str], Optional[str], Optional[bool]]:  # pragma: no cover - Windows only
    """(filesystem type, label, removable) for a drive root, or Nones when Windows does not say."""
    try:
        import ctypes
        kernel32 = ctypes.windll.kernel32  # type: ignore[attr-defined]
        label = ctypes.create_unicode_buffer(261)
        fs = ctypes.create_unicode_buffer(261)
        serial = ctypes.c_uint32(0)
        max_len = ctypes.c_uint32(0)
        flags = ctypes.c_uint32(0)
        ok = kernel32.GetVolumeInformationW(ctypes.c_wchar_p(str(root)), label, 261, ctypes.byref(serial),
                                            ctypes.byref(max_len), ctypes.byref(flags), fs, 261)
        fstype = (fs.value or None) if ok else None
        vol_label = (label.value or None) if ok else None
        # DRIVE_REMOVABLE 2, DRIVE_FIXED 3, DRIVE_REMOTE 4, DRIVE_CDROM 5, DRIVE_RAMDISK 6; 0 and 1 unknown.
        kind = kernel32.GetDriveTypeW(ctypes.c_wchar_p(str(root)))
        removable: Optional[bool]
        if kind == 2:
            removable = True
        elif kind in (3, 4, 5, 6):
            removable = False
        else:
            removable = None
        return fstype, vol_label, removable
    except Exception:
        return None, None, None


def human_size(n: int) -> str:
    if n >= 1024 * MIB:
        return f"{n / (1024 * MIB):.1f} GiB"
    if n >= MIB:
        return f"{n / MIB:.1f} MiB"
    if n >= 1024:
        return f"{n / 1024:.1f} KiB"
    return f"{n} B"


# --- The card -------------------------------------------------------------------------------------------


def list_update_files(root: Path) -> Tuple[List[Path], bool]:
    """(JH_*.bin files in the root, whether a DCIM directory is there). Raises OSError."""
    files: List[Path] = []
    has_dcim = False
    with os.scandir(str(root)) as it:
        for entry in it:
            try:
                if entry.name.upper() == "DCIM" and entry.is_dir():
                    has_dcim = True
                elif looks_like_update_file(entry.name) and entry.is_file():
                    files.append(Path(entry.path))
            except OSError:
                continue
    files.sort(key=lambda p: p.name.upper())
    return files, has_dcim


def inspect_card(given: str, override: bool) -> CardInfo:
    """Everything the plan needs to know about the card, or a StageError saying why it is refused."""
    if not given.strip():
        raise StageError("--card needs the card's mount point or drive letter", 2)
    root = normalize_card(given)
    try:
        exists, is_dir = root.exists(), root.is_dir()
    except OSError as exc:
        raise StageError(f"cannot access {given}: {exc}", 2) from None
    if not exists:
        raise StageError(f"{given} does not exist; is the card in the reader and mounted?", 2)
    if not is_dir:
        raise StageError(f"{given} is not a directory. stage takes the mount point or drive letter of the card, not a device "
                         "(card-check and card-fix take the device)", 2)
    notes: List[str] = []
    is_mount = is_drive_root(root)
    if not is_mount:
        if not override:
            raise StageError(f"{given} is not a mount point or drive root. The camera reads only the card root, so give the point "
                             "where the card is mounted (Linux: /run/media/deck/<label> or /media/<user>/<label>; Windows: E:). "
                             f"To test on a plain folder pass {OVERRIDE_FLAG}.", 2)
        notes.append(f"{given} is not a mount point or drive root; going on because of {OVERRIDE_FLAG}")

    fstype: Optional[str] = None
    device: Optional[str] = None
    label: Optional[str] = None
    removable: Optional[bool] = None
    if os.name == "nt":  # pragma: no cover - Windows only
        fstype, label, removable = windows_volume_info(root)
    elif is_mount:
        device, fstype = linux_mount_entry(root)
        if device:
            removable = linux_removable(device)

    if removable is False:
        msg = f"{given} is not on removable media" + (f" ({device})" if device else "") + ", so it is not a card"
        if not override:
            raise StageError(f"{msg}. Refusing; pass {OVERRIDE_FLAG} if it really is the card.", 1)
        notes.append(f"{msg}; going on because of {OVERRIDE_FLAG}")
    if fstype:
        low = fstype.lower()
        if low in NOT_FAT:
            msg = f"{given} is {fstype}; the camera reads exFAT and FAT32 only (it formats cards as exFAT itself)"
            if not override:
                raise StageError(f"{msg}. Reformat the card (docs/CARDS.md) or pass {OVERRIDE_FLAG}.", 1)
            notes.append(f"{msg}; going on because of {OVERRIDE_FLAG}")
        elif low not in FAT_FAMILY:
            notes.append(f"filesystem type {fstype}: cannot tell whether the camera can read it (it reads exFAT and FAT32)")

    try:
        usage = shutil.disk_usage(str(root))
    except OSError as exc:
        raise StageError(f"cannot read the free space of {given}: {exc}", 1)
    try:
        update_files, has_dcim = list_update_files(root)
    except OSError as exc:
        raise StageError(f"cannot list {given}: {exc}", 1)
    if not has_dcim:
        notes.append(f"{given} has no DCIM directory, so it does not look like a card the camera has used; fine for a new card, "
                     "worth a second look otherwise")
    return CardInfo(given=given, root=root, is_mount=is_mount, fstype=fstype, device=device, label=label, removable=removable,
                    total=usage.total, free=usage.free, has_dcim=has_dcim, update_files=update_files, notes=notes)


# --- Hashes, copy, confirmation ---------------------------------------------------------------------------


def file_equals(path: Path, image: bytes) -> bool:
    """Byte-identical, read in chunks. Raises OSError."""
    if path.stat().st_size != len(image):
        return False
    pos = 0
    with open(path, "rb") as f:
        while True:
            chunk = f.read(CHUNK)
            if not chunk:
                break
            if chunk != image[pos: pos + len(chunk)]:
                return False
            pos += len(chunk)
    return pos == len(image)


def read_back_sha256(path: Path) -> str:
    """sha256 of the file as read from the card. On Linux the cached pages are dropped first so the read
    goes to the medium; Windows has no such hint, so there the read-back may come from the cache."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        fadvise = getattr(os, "posix_fadvise", None)
        if fadvise is not None:
            try:
                fadvise(f.fileno(), 0, 0, os.POSIX_FADV_DONTNEED)
            except OSError:
                pass
        while True:
            chunk = f.read(CHUNK)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def fsync_directory(directory: Path) -> bool:
    """Commit the rename to the medium where the platform allows it (not on Windows)."""
    if os.name == "nt":  # pragma: no cover - Windows only
        return False
    try:
        fd = os.open(str(directory), os.O_RDONLY)
    except OSError:
        return False
    try:
        os.fsync(fd)
        return True
    except OSError:
        return False
    finally:
        os.close(fd)


def copy_to_root(image: bytes, dest: Path) -> bool:
    """Write `image` next to `dest` under TEMP_NAME, flush and fsync, rename onto `dest`, fsync the
    directory. Returns whether the directory fsync happened. On OSError the temporary file is removed and
    the error re-raised; nothing ever appears under the final name unless it is complete."""
    tmp = dest.with_name(TEMP_NAME)
    try:
        with open(tmp, "wb") as f:
            for i in range(0, len(image), CHUNK):
                f.write(image[i: i + CHUNK])
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, dest)
    except OSError:
        try:
            tmp.unlink()
        except OSError:
            pass
        raise
    return fsync_directory(dest.parent)


def same_target(typed: str, expected: str) -> bool:
    """The typed confirmation matches the --card value; a trailing separator does not count, and on
    Windows neither does the case of the drive letter."""
    def norm(s: str) -> str:
        s = s.strip()
        if len(s) > 1:
            s = s.rstrip("/\\")
        return s.upper() if os.name == "nt" else s
    return bool(typed.strip()) and norm(typed) == norm(expected)


def _confirm(expected: str) -> bool:
    if not sys.stdin or not sys.stdin.isatty():
        print("error: no terminal to confirm on. Re-run interactively (on Windows: cmd, PowerShell or Windows Terminal; "
              "under Git Bash prefix the command with winpty); --plan-only shows the plan without asking.")
        return False
    try:
        typed = input(f"Type {expected} to write the file to the card, anything else to stop: ")
    except (EOFError, UnicodeDecodeError):
        print("Not confirmed; nothing was written.")
        return False
    if not same_target(typed, expected):
        print("Not confirmed; nothing was written.")
        return False
    return True


# --- Output ---------------------------------------------------------------------------------------------


def print_checks(checks: List[Check]) -> bool:
    """verify's table, with its tags. Returns True when a blocking check failed."""
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
    return failed


def describe_card(info: CardInfo) -> str:
    parts = []
    if info.fstype:
        parts.append(info.fstype)
    if info.label:
        parts.append(f"label {info.label}")
    if info.device:
        parts.append(info.device)
    parts.append(f"{human_size(info.free)} free of {human_size(info.total)}")
    parts.append("DCIM present" if info.has_dcim else "no DCIM")
    if not info.is_mount:
        parts.append("plain folder, not a mount point")
    return ", ".join(parts)


def print_plan(src: Path, image: bytes, info: CardInfo, dest: Path) -> None:
    print("Plan")
    print(f"  source:       {src}")
    print(f"  sha256:       {hashlib.sha256(image).hexdigest()}")
    print(f"  byte sum:     {byte_sum(image):08X} (the checksum in the name)")
    print(f"  size:         {len(image):,} bytes ({human_size(len(image))})")
    print(f"  card:         {info.given}  ({describe_card(info)})")
    print(f"  destination:  {dest}")
    print(f"  how:          written as {TEMP_NAME}, flushed to the card, renamed to the final name, read back and compared")
    print()
    print("Charge the camera first: a power loss during programming leaves a camera that only a chip programmer can")
    print("recover (docs/RECOVERY.md). Then, on the camera:")
    for line in CAMERA_STEPS:
        print(f"  {line}")
    print()
    print("Cards: the camera formats them as exFAT and reads exFAT and FAT32. Phones may refuse the camera's exFAT card")
    print("(the dirty-bit problem); docs/CARDS.md explains and card-fix repairs it. That does not affect the update.")


# --- Command --------------------------------------------------------------------------------------------


def handler(args) -> int:
    src = Path(args.file)
    if not src.is_file():
        print(f"error: {src} is not a file")
        return 2
    try:
        image = src.read_bytes()
    except OSError as exc:
        print(f"error: cannot read {src}: {exc}")
        return 2
    stock: Optional[bytes] = None
    if args.stock:
        stock_path = Path(args.stock)
        if not stock_path.is_file():
            print(f"error: --stock {args.stock} is not a file")
            return 2
        try:
            stock = stock_path.read_bytes()
        except OSError as exc:
            print(f"error: cannot read --stock {stock_path}: {exc}")
            return 2
    override = bool(getattr(args, "i_know_what_i_am_doing", False))
    plan_only = bool(getattr(args, "plan_only", False))
    card_given = (args.card or "").strip()

    # 1. verify, exactly as the verify command would, with modified software allowed (that is what mods are).
    print(f"verify {src.name}:")
    checks = verify_update(src, image, stock=stock, allow_modified=True, allow_unknown_build=False, allow_odd_size=False)
    if print_checks(checks):
        print()
        print("RESULT: verify failed; this file does not go on a card.")
        return 1
    print()

    # 2. the card.
    try:
        info = inspect_card(card_given, override)
    except StageError as exc:
        print(f"error: {exc}")
        return exc.code
    for note in info.notes:
        print(f"warning: {note}")

    dest = info.root / src.name
    same_name = [p for p in info.update_files if p.name.upper() == src.name.upper()]
    if same_name and len(info.update_files) == 1:
        try:
            identical = file_equals(same_name[0], image)
        except OSError as exc:
            print(f"error: cannot read {same_name[0]}: {exc}")
            return 1
        if identical:
            print(f"{same_name[0]} is already on the card and is byte-identical to {src}; nothing to do.")
            print("After the camera has flashed it, delete it from the card: the camera does not, and would flash again at every power-on.")
            return 0
    if info.update_files:
        print(f"error: {info.given} already holds {len(info.update_files)} update file(s) in its root:")
        for p in info.update_files:
            what = "same name, different content" if p.name.upper() == src.name.upper() else "another update file"
            print(f"  {p}  ({what})")
        print("       The camera flashes the first JH_5408*.bin it finds, and two of them on one card is an undefined situation.")
        print("       Remove it first. A file left from an earlier update is the usual reason; the camera never deletes it.")
        return 1
    if info.free < len(image) + FREE_SPACE_SLACK:
        print(f"error: {info.given} has {human_size(info.free)} free; the file needs {human_size(len(image))} plus a directory entry. "
              "Make room or use another card.")
        return 1

    # 3. the plan and the confirmation.
    print_plan(src, image, info, dest)
    print()
    if plan_only:
        print("plan only: nothing written.")
        return 0
    if not _confirm(card_given):
        return 2

    # 4. the copy. Look again first: the plan was printed a moment ago, but someone may have put a file there.
    try:
        now_files, _dcim = list_update_files(info.root)
    except OSError as exc:
        print(f"error: cannot list {info.given}: {exc}; nothing written")
        return 1
    if now_files:
        print(f"error: the card changed since the plan was printed ({now_files[0].name} appeared); nothing written.")
        return 1
    try:
        dir_synced = copy_to_root(image, dest)
    except OSError as exc:
        print(f"error: writing to {info.given} failed: {exc}")
        print(f"       Nothing is left under the final name. If a {TEMP_NAME} remains on the card, delete it.")
        return 1
    try:
        got = read_back_sha256(dest)
        size = dest.stat().st_size
    except OSError as exc:
        print(f"error: reading {dest} back failed: {exc}")
        print("       Delete it from the card before the card goes into the camera.")
        return 1
    want = hashlib.sha256(image).hexdigest()
    if got != want or size != len(image):
        print(f"error: read-back MISMATCH: wrote sha256 {want} ({len(image):,} bytes), read back {got} ({size:,} bytes).")
        try:
            dest.unlink()
            print(f"       {dest} has been deleted; the card or the reader is not trustworthy. Try another card.")
        except OSError as exc:
            print(f"       Could not delete {dest} ({exc}). Delete it yourself before the card goes into the camera.")
        return 1
    print(f"wrote {dest} ({len(image):,} bytes); read back sha256 {got}: matches.")
    if not dir_synced:
        print("note: the directory could not be synced on this platform; use the OS's eject before pulling the card.")
    print("Eject the card properly (Windows: Safely Remove; Linux: udisksctl unmount or the file manager's eject), then follow the steps above.")
    return 0


def register(subparsers) -> None:
    p = subparsers.add_parser("stage", help="copy a verified update file to the root of the camera's SD card (asks before writing)")
    p.add_argument("file", help="the JH_5408<stamp>_<sum>.bin update file, as written by pack")
    p.add_argument("--card", required=True,
                   help="the card's mount point (Linux: /run/media/deck/<label>, /media/<user>/<label>) or drive (Windows: E: or E:\\)")
    p.add_argument("--stock", help="your own stock dump, so verify can show which blocks a modified file changes")
    p.add_argument("--plan-only", action="store_true", help="run every check and print the plan, then exit without asking or writing")
    p.add_argument(OVERRIDE_FLAG, dest="i_know_what_i_am_doing", action="store_true",
                   help="allow a plain folder that is not a mount point (for testing), a non-removable volume, or a filesystem the camera cannot read")
    p.set_defaults(func=handler)
