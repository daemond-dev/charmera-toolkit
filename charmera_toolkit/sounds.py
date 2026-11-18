"""`sounds`: list, extract, mute or replace the five UI sounds.

Two independent ways to mute (both VERIFIED in the firmware, neither yet tested on hardware):

- settings: the event-sound player returns before playing when settings byte +0x19 is 0. The toolkit
  rewrites the settings store so that its only valid record is the current one with +0x19 = 0. No code or
  resource changes.
- pcm: silence the PCM data of all five WAV resources in place (0x80 for 8-bit, 0x00 for 16-bit), keeping
  every header byte. The Godox C100 kit, built on the same SDK, shipped exactly this and reported it working.

`replace` writes a new clip into a slot, converted to the slot's own sample rate, bit depth and mono
layout, truncated or padded with silence to the stock data length so no header changes.

Writing a modified image is a mod step: the output is a full image, not an update file. Run `pack` on
it afterwards and read what `verify` says. No mod command writes its image under an update file's name
(JH_*.bin) or into a folder that holds DCIM or a JH_*.bin (_check_output).
"""
from __future__ import annotations

import struct
import wave
import os
from pathlib import Path
from typing import Dict, List, Optional

from . import settings as settings_mod
from .image import SOUND_SLOTS, WavSlot, sound_slots

GATE_FLAG = "--i-understand-this-is-a-mod"


# --- Read side ------------------------------------------------------------------------------------------


def list_sounds(image: bytes) -> List[WavSlot]:
    return sound_slots(image)


def extract(image: bytes, out_dir: Path) -> List[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for s in sound_slots(image):
        p = out_dir / (s.entry.display_name.lower())
        with wave.open(str(p), "wb") as w:
            w.setnchannels(s.channels)
            w.setsampwidth(s.bits // 8)
            w.setframerate(s.rate)
            w.writeframes(bytes(image[s.data_offset: s.data_offset + s.data_len]))
        written.append(p)
    return written


# --- Mute -----------------------------------------------------------------------------------------------


def mute_pcm(image: bytes, names: Optional[List[str]] = None) -> bytes:
    """Silence the PCM of the named slots (directory names, default all five). Headers untouched."""
    buf = bytearray(image)
    for s in sound_slots(image):
        if names and s.entry.name not in names:
            continue
        buf[s.data_offset: s.data_offset + s.data_len] = bytes([s.silence_byte]) * s.data_len
    return bytes(buf)


def set_ui_sound(image: bytes, level: int, base: str = "current") -> bytes:
    """Rewrite the settings store so one record with UI sound = level is the only record.

    base = "current": start from the record the camera would load (keeps that camera's choices).
    base = "first": start from the first record in the bank, which on every known dump is the factory
    default record (identical in both public dumps). Use this when the dump is not your own camera's.
    The rest of the bank is erased so the firmware cannot pick an older record, and the photo directory
    is zeroed if it was erased.
    """
    if not 0 <= level <= 8:
        raise ValueError("level must be 0..8")
    if len(image) < settings_mod.BANK_END:
        raise ValueError("image does not contain the settings store")
    if base == "first":
        recs = [r for r in settings_mod.parse_settings(image) if r.crc_ok]
        if not recs:
            raise ValueError("no CRC-valid settings record in the image")
        src = recs[0]
    elif base == "current":
        src = settings_mod.current_record(image)
    else:
        raise ValueError("base must be 'current' or 'first'")
    return settings_mod.rewrite_store(image, src.with_byte(settings_mod.OFF_UI_SOUND, level))


# --- Replace --------------------------------------------------------------------------------------------


def _read_pcm(path: Path):
    """Return (channels, sampwidth, rate, frames) from a PCM WAV. Raises ValueError for anything else."""
    try:
        with wave.open(str(path), "rb") as w:
            if w.getcomptype() != "NONE":
                raise ValueError(f"{path}: only uncompressed PCM WAV is supported")
            return w.getnchannels(), w.getsampwidth(), w.getframerate(), w.readframes(w.getnframes())
    except (wave.Error, EOFError) as exc:
        raise ValueError(f"{path}: not a readable PCM WAV ({exc})")


def _to_s16_mono(frames: bytes, channels: int, width: int) -> List[int]:
    n = len(frames) // (channels * width)
    out = []
    for i in range(n):
        acc = 0
        for c in range(channels):
            o = (i * channels + c) * width
            if width == 1:
                v = (frames[o] - 128) << 8
            elif width == 2:
                v = struct.unpack_from("<h", frames, o)[0]
            elif width == 3:
                v = int.from_bytes(frames[o: o + 3], "little", signed=True) >> 8
            elif width == 4:
                v = struct.unpack_from("<i", frames, o)[0] >> 16
            else:
                raise ValueError("unsupported sample width")
            acc += v
        out.append(acc // channels)
    return out


def _resample(samples: List[int], src: int, dst: int) -> List[int]:
    """Rate conversion. Downsampling averages the source samples that cover each output sample (a box
    low-pass), so content above the slot's Nyquist frequency does not alias; upsampling interpolates."""
    if src == dst or not samples:
        return samples
    n = max(1, int(len(samples) * dst / src))
    out = []
    if src > dst:
        for i in range(n):
            a = int(i * src / dst)
            b = max(a + 1, int((i + 1) * src / dst))
            chunk = samples[a:b] or [samples[-1]]
            out.append(sum(chunk) // len(chunk))
        return out
    for i in range(n):
        x = i * src / dst
        j = int(x)
        f = x - j
        a = samples[min(j, len(samples) - 1)]
        b = samples[min(j + 1, len(samples) - 1)]
        out.append(int(a + (b - a) * f))
    return out


class Converted:
    """Result of fitting a clip into a slot."""

    def __init__(self, pcm: bytes, clip_ms: int, kept_ms: int):
        self.pcm = pcm
        self.clip_ms = clip_ms
        self.kept_ms = kept_ms

    @property
    def note(self) -> str:
        if self.clip_ms > self.kept_ms:
            return f"clip is {self.clip_ms} ms, slot holds {self.kept_ms} ms: the rest is cut"
        if self.clip_ms < self.kept_ms:
            return f"clip is {self.clip_ms} ms, slot holds {self.kept_ms} ms: padded with silence"
        return f"clip fills the slot exactly ({self.kept_ms} ms)"


def convert_for_slot(path: Path, slot: WavSlot, gain: float = 1.0) -> Converted:
    """PCM bytes in the slot's own format, exactly slot.data_len long, plus what was cut or padded."""
    ch, width, rate, frames = _read_pcm(path)
    s16 = _resample(_to_s16_mono(frames, ch, width), rate, slot.rate)
    if gain != 1.0:
        s16 = [max(-32768, min(32767, int(v * gain))) for v in s16]
    if slot.bits == 16:
        pcm = b"".join(struct.pack("<h", v) for v in s16)
    elif slot.bits == 8:
        pcm = bytes(((v + 32768) >> 8) & 0xFF for v in s16)
    else:
        raise ValueError("slot has an unsupported bit depth")
    bytes_per_ms = slot.rate * (slot.bits // 8) / 1000.0
    clip_ms = int(len(pcm) / bytes_per_ms)
    kept_ms = int(slot.data_len / bytes_per_ms)
    if len(pcm) >= slot.data_len:
        return Converted(pcm[: slot.data_len], clip_ms, kept_ms)
    return Converted(pcm + bytes([slot.silence_byte]) * (slot.data_len - len(pcm)), clip_ms, kept_ms)


def replace_sound(image: bytes, slot_name: str, wav_path: Path, gain: float = 1.0):
    """Return (modified image, note about cutting or padding)."""
    name = slot_name.upper().replace(".", "")
    for s in sound_slots(image):
        if s.entry.name == name:
            conv = convert_for_slot(wav_path, s, gain)
            buf = bytearray(image)
            buf[s.data_offset: s.data_offset + s.data_len] = conv.pcm
            return bytes(buf), conv.note
    raise ValueError(f"no sound slot named {slot_name}; slots are {', '.join(n for n in SOUND_SLOTS)}")


# --- CLI ------------------------------------------------------------------------------------------------


def _print_slots(image: bytes) -> None:
    for s in sound_slots(image):
        print(f"{s.entry.display_name:20s} {SOUND_SLOTS[s.entry.name][0]:10s} at {s.entry.offset:#8x}  {s.rate} Hz "
              f"{s.bits}-bit {s.channels}ch  {s.duration_ms} ms  data {s.data_len} B  budget {s.entry.budget} B")


def _write_mod(args, image: bytes, out: Path, what: str) -> int:
    if not getattr(args, "gate", False):
        print(f"dry run: would write {out} ({len(image)} bytes) with {what}.")
        print(f"Re-run with {GATE_FLAG} to write it. The result is an image, not an update file; pack and verify it next.")
        return 0
    try:
        out.write_bytes(image)
    except OSError as exc:
        print(f"error: could not write {out}: {exc}")
        return 1
    print(f"wrote {out} ({len(image)} bytes): {what}")
    print("next: charmera pack --input <this file> ... then charmera verify <the update file>")
    return 0


def created_name(name: str) -> str:
    """The name Windows gives the file it creates for `name`: it drops trailing dots and spaces ("JH_....bin." lands
    as "JH_....bin"), and "name:stream" or "name::$DATA" writes a stream of the file before the colon."""
    return name.split(":", 1)[0].rstrip(" .")


def _check_output(src: Path, out: Path, overwrite: bool) -> Optional[str]:
    """Why an output path must be refused, or None. Never lets a mod land on top of the input, under an update
    file's name, or on what looks like a camera card.

    A mod is an image, not an update file: it keeps the input's length and nothing has verified it. Named
    JH_*.bin in a card's root, only its byte sum would stand between it and a flash, and a 2 MiB one erases
    past the end of the chip, which wraps to block 1 and bricks the camera (REPORTED on hardware by the
    film-mods author). So such a name is refused in any case, and also when it is the name Windows would
    create (created_name), and so is a folder that holds DCIM or a JH_*.bin, as pack refuses them.
    """
    # stage imports verify, which imports frames, which imports this module: import it here, not at the top.
    from .stage import list_update_files, looks_like_update_file

    try:
        same = out.resolve() == src.resolve()
    except OSError:
        same = False
    if not same and out.exists():
        try:
            same = os.path.samefile(out, src)   # a hard link or another path to the same file
        except OSError:
            pass
    if same:
        return "--out is the input file; write the mod to a new path and keep your backup untouched"
    if out.is_dir():
        return f"{out} is a directory; give a file name for --out"
    if looks_like_update_file(out.name) or looks_like_update_file(created_name(out.name)):
        return (f"{out.name} is named like an update file (JH_*.bin), the name a camera flashes from its card; a mod "
                "is an image, not an update file: give --out another name, then build the update file with pack and check it with verify")
    if not out.parent.is_dir():
        return f"the folder {out.parent} does not exist; create it first or choose another --out"
    folder = os.path.abspath(str(out.parent))
    try:
        present, has_dcim = list_update_files(out.parent)
    except OSError as exc:
        return f"cannot list {folder}: {exc}"
    if has_dcim:
        return (f"{folder} has a DCIM folder, so it looks like a camera card; write the mod to a folder on the computer "
                "(pack, verify and stage take it to the card from there)")
    if present:
        return (f"{folder} holds an update file ({', '.join(p.name for p in present)}), as a card set up to flash does; "
                "write the mod to a folder on the computer that holds none")
    if out.exists() and not overwrite:
        return f"{out} already exists; choose another name or pass --overwrite"
    return None


def handler(args) -> int:
    src = Path(args.file)
    if not src.is_file():
        print(f"error: {src} is not a file")
        return 2
    if args.action != "list" and not args.out:
        print(f"error: --out is required for {args.action}")
        return 2
    if args.action == "replace" and not (args.slot and args.wav):
        print("error: replace needs --slot and --wav")
        return 2
    image = src.read_bytes()
    try:
        if args.action == "list":
            _print_slots(image)
            return 0
        if args.action == "extract":
            for p in extract(image, Path(args.out)):
                print(f"wrote {p}")
            return 0
    except ValueError as exc:
        print(f"error: {exc}")
        return 2
    out = Path(args.out)
    problem = _check_output(src, out, args.overwrite)
    if problem:
        print(f"error: {problem}")
        return 2
    try:
        if args.action == "mute":
            what = []
            if args.method in ("settings", "both"):
                image = set_ui_sound(image, 0, args.base_record)
                what.append(f"settings byte +0x19 = 0 (UI sounds off), from the {args.base_record} record")
            if args.method in ("pcm", "both"):
                image = mute_pcm(image)
                what.append("all five WAV slots silenced")
            return _write_mod(args, image, out, "; ".join(what))
        if args.action == "replace":
            image, note = replace_sound(image, args.slot, Path(args.wav), args.gain)
            print(f"note: {note}")
            return _write_mod(args, image, out, f"{args.slot} replaced by {args.wav}")
    except ValueError as exc:
        hint = ""
        if "settings" in str(exc):
            hint = " (try --base-record first, or build from a dump with a valid settings record)"
        print(f"error: {exc}{hint}")
        return 2
    except OSError as exc:
        print(f"error: {exc}")
        return 2
    return 2


def register(subparsers) -> None:
    p = subparsers.add_parser("sounds", help="list, extract, mute or replace the UI sounds")
    p.add_argument("action", choices=["list", "extract", "mute", "replace"])
    p.add_argument("file", help="a 2 MiB dump or a 0x1E0000 update file")
    p.add_argument("--out", help="output directory (extract) or output image path (mute/replace; never named JH_*.bin, "
                                 "never in a folder that holds DCIM or a JH_*.bin)")
    p.add_argument("--method", choices=["settings", "pcm", "both"], default="settings",
                   help="mute: settings byte only (default), silence the PCM, or both")
    p.add_argument("--base-record", choices=["current", "first"], default="current",
                   help="mute: which settings record to derive the muted record from (first = factory record)")
    p.add_argument("--slot", help="replace: CLICK.WAV, POWERON_AUDIO.WAV, POWEROFF_AUDIO.WAV, BEEP.WAV or IMG.WAV")
    p.add_argument("--wav", help="replace: a PCM WAV file")
    p.add_argument("--gain", type=float, default=1.0, help="replace: linear gain applied before conversion")
    p.add_argument("--overwrite", action="store_true", help="allow --out to replace an existing file (never the input)")
    p.add_argument(GATE_FLAG, dest="gate", action="store_true", help="actually write the modified image")
    p.set_defaults(func=handler)
