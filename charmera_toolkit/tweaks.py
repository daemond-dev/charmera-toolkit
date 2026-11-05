"""`tweak`: small, checked byte patches for faster start-up and quieter power-off. Experimental.

Every patch names the exact bytes it expects at its offset (taken from the original 2025-series build) and
is refused when they are not there, so it cannot land in the wrong place on a modified or different build.
None of these has been tested on a camera yet. They come from the boot and power analysis in
docs/12-NOTES-boot-and-power.md; the delays are OS ticks of 10 ms passed to the µC/OS-II delay call.

Risk labels: low = a delay the code only waits out; medium = a delay or output whose purpose is inferred;
high = changes the state machine's path. Start with the low ones, one at a time, on a camera you can restore.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple

from .image import ORIGINAL_2025, detect_build
from .sounds import GATE_FLAG, _check_output, _write_mod


@dataclass(frozen=True)
class Patch:
    name: str
    risk: str
    summary: str
    detail: str
    edits: Tuple[Tuple[int, bytes, bytes], ...]   # (file offset, original bytes, new bytes)


PATCHES: Dict[str, Patch] = {p.name: p for p in [
    Patch("boot-delay", "low", "cut the fixed 100 ms boot delay to 20 ms",
          "mov r0,#10 before the OS delay at boot (file 0x62B8) becomes mov r0,#2. The power key must still be held "
          "200 ms after power-on for the camera to stay on; that check is separate and unchanged.",
          ((0x62B8, b"\x0a\x00\xa0\xe3", b"\x02\x00\xa0\xe3"),)),
    Patch("lcd-poll", "low", "poll for the display every 10 ms instead of 50 ms while waiting for the LCD at boot",
          "The boot state polls a display-ready flag up to 50 times with a 50 ms delay (file 0x6360). This shortens "
          "the delay; the loop still ends as soon as the LCD reports ready, so at most 40 ms is saved.",
          ((0x6360, b"\x05\x00\xa0\xe3", b"\x01\x00\xa0\xe3"),)),
    Patch("logo-hold", "medium", "shorten the 50 ms pause after the power-on logo is drawn to 10 ms (not recommended)",
          "mov r0,#5 at file 0x64F8. The logo's frame buffer is freed right after this pause, so it is probably what "
          "lets the display finish drawing the logo. Saves 40 ms at the risk of a garbled logo; not in the recommended set.",
          ((0x64F8, b"\x05\x00\xa0\xe3", b"\x01\x00\xa0\xe3"),)),
    Patch("skip-logo", "medium", "skip the power-on logo (cosmetic only, not recommended)",
          "The boot state opens 'POWER_ON_LOGO.JPG' by name (string at file 0x6544); changing one letter makes the lookup "
          "fail, and that failure path has not been exercised on hardware. It saves only the decode of a 3.5 KB image: "
          "the logo is what is on screen while the camera mounts the card, so the screen would stay blank for that wait "
          "instead. Power-off uses its own copy of the name (0x23300) and is not affected.",
          ((0x6544, b"POWER_ON_LOGO.JPG\x00", b"POWER_ON_LOG0.JPG\x00"),)),
    Patch("led-off", "medium", "do not switch GPIO 38 on at boot (inferred to be the front LED)",
          "mov r1,#1 before the GPIO write for pin 0x26 at file 0x6520 becomes mov r1,#0. The pin's role is INFERRED: "
          "if it is not the LED (for example a display enable), the screen may stay dark. Test only with a restore path ready.",
          ((0x6520, b"\x01\x10\xa0\xe3", b"\x00\x10\xa0\xe3"),)),
    Patch("fast-poweroff", "low", "cut the idle waits at power-off: logo hold 600 to 50 ms, wait after the sound 200 to 10 ms",
          "mov r0,#0x3c at file 0x2319C becomes mov r0,#5 and mov r0,#0x14 at 0x23050 becomes mov r0,#1. Nothing runs "
          "during either wait. The logo is still drawn and kept for 50 ms, the same time the firmware gives it at "
          "power-on before freeing its frame, so it still shows while the camera shuts down. With UI sounds on, the "
          "shorter waits cut the power-off sound short; with them off they cost nothing. Key release and the "
          "hardware power flag are still waited for.",
          ((0x2319C, b"\x3c\x00\xa0\xe3", b"\x05\x00\xa0\xe3"), (0x23050, b"\x14\x00\xa0\xe3", b"\x01\x00\xa0\xe3"))),
    Patch("boot-to-photo", "high", "enter the photo state directly instead of the home screen after boot",
          "The literal 0x10D (home state) at file 0x6714 becomes 0x101 (photo state). Only worth trying if the stock "
          "camera really waits on the home screen for a key press; what posts the mode change at boot is unknown.",
          ((0x6714, b"\x0d\x01\x00\x00", b"\x01\x01\x00\x00"),)),
]}


# Shortens only fixed waits where nothing is loading; the power-on logo, shown while the card mounts, is kept.
RECOMMENDED = ["boot-delay", "lcd-poll", "fast-poweroff"]


def expand_names(names: List[str]) -> List[str]:
    """Replace 'recommended' with the recommended set, keeping order and dropping repeats."""
    out: List[str] = []
    for n in names:
        for m in (RECOMMENDED if n == "recommended" else [n]):
            if m not in out:
                out.append(m)
    return out


def check_patch(image: bytes, patch: Patch) -> List[str]:
    """Problems that prevent applying the patch (empty when it fits)."""
    problems = []
    for off, orig, new in patch.edits:
        have = image[off: off + len(orig)]
        if have == new:
            problems.append(f"{patch.name}: already applied at {off:#x}")
        elif have != orig:
            problems.append(f"{patch.name}: bytes at {off:#x} are {have.hex()} not {orig.hex()}; wrong build or already modified")
    return problems


def apply_patches(image: bytes, names: List[str]) -> Tuple[bytes, List[str]]:
    """Apply the named patches; raises ValueError listing every problem if any patch does not fit."""
    names = expand_names(names)
    unknown = [n for n in names if n not in PATCHES]
    if unknown:
        raise ValueError("unknown tweak(s): " + ", ".join(unknown) + "; see `tweak list`")
    problems = []
    for n in names:
        problems += check_patch(image, PATCHES[n])
    if problems:
        raise ValueError("\n".join(problems))
    buf = bytearray(image)
    applied = []
    for n in names:
        for off, orig, new in PATCHES[n].edits:
            buf[off: off + len(new)] = new
        applied.append(n)
    return bytes(buf), applied


def handler(args) -> int:
    if args.action == "list":
        for p in PATCHES.values():
            print(f"{p.name:14s} [{p.risk:6s}] {p.summary}")
            print(f"               {p.detail}")
        print(f"\nrecommended = {', '.join(RECOMMENDED)}: only fixed waits where nothing is loading; the power-on logo, "
              "which is on screen while the camera mounts the card, is kept.")
        print("All of these are untested on hardware. Apply one at a time, pack, verify, and keep a restore path.")
        return 0
    if not args.file:
        print("error: apply needs the image to patch: charmera tweak apply IMAGE --enable NAME --out NEW.bin")
        return 2
    src = Path(args.file)
    if not src.is_file():
        print(f"error: {src} is not a file")
        return 2
    if not args.enable:
        print("error: --enable NAME[,NAME...] is required; see `tweak list`")
        return 2
    if not args.out:
        print("error: --out is required")
        return 2
    image = src.read_bytes()
    build = detect_build(image)
    if build.build is not ORIGINAL_2025:
        print("error: these patches are defined for the original 2025 series build only; this image is not that build")
        return 2
    names = [n.strip() for n in args.enable.split(",") if n.strip()]
    if not names:
        print("error: --enable names no tweak; see `tweak list`")
        return 2
    out = Path(args.out)
    problem = _check_output(src, out, args.overwrite)
    if problem:
        print(f"error: {problem}")
        return 2
    try:
        new, applied = apply_patches(image, names)
    except ValueError as exc:
        print(f"error: {exc}")
        return 2
    high = [n for n in applied if PATCHES[n].risk == "high"]
    if high and not args.accept_high_risk:
        print(f"error: {', '.join(high)} is marked high risk; add --accept-high-risk to include it")
        return 2
    what = "tweaks " + ", ".join(applied) + " (EXPERIMENTAL, untested on hardware)"
    return _write_mod(args, new, out, what)


def register(subparsers) -> None:
    p = subparsers.add_parser("tweak", help="experimental start-up and power-off byte patches (list, apply)")
    p.add_argument("action", choices=["list", "apply"])
    p.add_argument("file", nargs="?", help="apply: a 2 MiB dump or a 0x1E0000 update file of the original series")
    p.add_argument("--enable", help="apply: comma-separated tweak names from `tweak list`, or 'recommended'")
    p.add_argument("--out", help="apply: output image path")
    p.add_argument("--accept-high-risk", action="store_true", help="apply: allow tweaks marked high risk")
    p.add_argument("--overwrite", action="store_true", help="allow --out to replace an existing file (never the input)")
    p.add_argument(GATE_FLAG, dest="gate", action="store_true", help="actually write the modified image")
    p.set_defaults(func=handler)
