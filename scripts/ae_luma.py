#!/usr/bin/env python3
"""Per-frame brightness of a Charmera AVI (or a set of JPEG stills), for testing the auto-exposure on the camera.

The AE step test (described here; the on-camera AE results so far are in docs/15-OV9732-AE-WB-FLASH-PORT.md,
sections 7 and 8): film the phone calibration card in a dark room, change the phone brightness in a single step
part-way through (for example 100% -> 25%, hold, then back), and run this on the AVI. Run it on the stock camera's AVI of the same test too, and compare. For every step in light it
reports the level before the step, the level just after it, the level it settles at, and how much of the change the
exposure won back:

    recovered  -- luma comes back most of the way to the pre-step level: the loop converges
    partial    -- it comes back part of the way and flattens: the loop hit a limit (the exposure ceiling), or the
                  step was too large for its range
    flat       -- it stays where the step put it: the exposure is fixed, not adaptive

It also flags "split" frames, where the top and bottom halves differ in brightness much more than usual: the sign of
exposure and gain taking effect on different lines or frames (no group hold).

Stills mode (any .jpg/.jpeg inputs): one line per file, with mean, spread and clipping, for the brightness sweep.

Needs Pillow (pip install pillow); nothing else. Read-only: it never writes to the camera or the card.

    python scripts/ae_luma.py MOVI0001.AVI
    python scripts/ae_luma.py MOVI0001.AVI --roi 0.25,0.3,0.75,0.6 --csv movi0001.csv
    python scripts/ae_luma.py sweep/*.JPG
"""
import argparse
import csv
import io
import statistics
import struct
import sys
from pathlib import Path

try:
    from PIL import Image, ImageStat
except ImportError:  # pragma: no cover - the message is the point
    sys.exit("ae_luma needs Pillow: pip install pillow")

CLIP = 250          # luma at or above this counts as clipped
DARK = 5            # luma at or below this counts as crushed
RECOVERED = 0.7     # fraction of the step won back that counts as converged
FLAT = 0.2          # at or below this, the exposure did not respond


# ----------------------------------------------------------------------------------------------------------- AVI

def avi_frames(data):
    """(microseconds per frame, [JPEG bytes...]) from an MJPEG AVI.

    Walks the RIFF tree instead of trusting the index, so a file the camera did not finish (no idx1, short last
    chunk) still yields every complete frame.
    """
    if data[:4] != b"RIFF" or data[8:12] != b"AVI ":
        raise ValueError("not an AVI file (no RIFF/AVI header)")
    usec = 0
    frames = []

    def walk(start, end):
        nonlocal usec
        p = start
        while p + 8 <= end:
            fourcc = data[p:p + 4]
            size = struct.unpack_from("<I", data, p + 4)[0]
            body = p + 8
            if fourcc in (b"LIST", b"RIFF"):
                walk(body + 4, min(body + size, end))
            elif fourcc == b"avih" and size >= 4:
                usec = struct.unpack_from("<I", data, body)[0]
            elif fourcc[2:4] in (b"dc", b"db") and size > 0:
                if body + size > len(data):
                    break                                   # truncated last frame
                chunk = data[body:body + size]
                if chunk[:2] == b"\xff\xd8":
                    frames.append(chunk)
            p = body + size + (size & 1)

    walk(12, len(data))
    return usec, frames


# --------------------------------------------------------------------------------------------------- measurement

def parse_roi(text):
    vals = [float(v) for v in text.split(",")]
    if len(vals) != 4 or not (0 <= vals[0] < vals[2] <= 1 and 0 <= vals[1] < vals[3] <= 1):
        raise argparse.ArgumentTypeError("roi is x0,y0,x1,y1 as fractions of the frame, e.g. 0.25,0.25,0.75,0.75")
    return vals


def measure(jpeg, roi):
    """Luma statistics of one frame: ROI mean/stdev, clipped and crushed fractions, top- and bottom-half means."""
    img = Image.open(io.BytesIO(jpeg)).convert("L")       # Rec.601 luma, the JPEG's own Y
    w, h = img.size
    box = (int(roi[0] * w), int(roi[1] * h), int(roi[2] * w), int(roi[3] * h))
    part = img.crop(box)
    st = ImageStat.Stat(part)
    hist = part.histogram()
    n = sum(hist) or 1
    top = ImageStat.Stat(img.crop((0, 0, w, h // 2))).mean[0]
    bottom = ImageStat.Stat(img.crop((0, h // 2, w, h))).mean[0]
    return {
        "mean": st.mean[0],
        "std": st.stddev[0],
        "clip": sum(hist[CLIP:]) / n,
        "dark": sum(hist[:DARK + 1]) / n,
        "top": top,
        "bottom": bottom,
    }


# ------------------------------------------------------------------------------------------------------ analysis

def _median(xs):
    return statistics.median(xs) if xs else 0.0


def find_steps(y, fps, min_gap_s=0.5):
    """Frame indices where the light changed in one step.

    A stimulus step is a one-frame jump that persists; AE's own response is gradual, and a split-frame blip comes
    straight back. So a step is a frame-to-frame jump well above the typical jitter whose new level (median of the
    next 3 frames) still differs from the old one (median of the previous 3) by that much.
    """
    if len(y) < 8:
        return []
    d = [abs(y[i] - y[i - 1]) for i in range(1, len(y))]
    mad = _median([abs(v - _median(d)) for v in d])
    thresh = max(8.0, 6.0 * mad + _median(d))
    gap = max(1, int(round(min_gap_s * fps)))
    steps = []
    for i in range(3, len(y) - 3):
        if abs(y[i] - y[i - 1]) < thresh:
            continue
        before = _median(y[i - 3:i])
        after = _median(y[i:i + 3])
        if abs(after - before) < thresh:
            continue
        if steps and i - steps[-1] < gap:
            if abs(y[i] - y[i - 1]) > abs(y[steps[-1]] - y[steps[-1] - 1]):
                steps[-1] = i
            continue
        steps.append(i)
    return steps


def analyse_step(y, i, end, fps):
    """Pre-step level, the level right after the step, the settled level, and the fraction recovered."""
    pre = _median(y[max(0, i - int(fps)):i])                     # the last second before the step
    hit = _median(y[i:i + 2])                                     # the first frames after it
    tail = max(2, int(fps * 0.5))                                 # the last half second of the segment
    settled = _median(y[max(i + 2, end - tail):end])
    change = pre - hit
    recovered = (settled - hit) / change if abs(change) > 1e-9 else 0.0
    if recovered >= RECOVERED:
        verdict = "recovered"
    elif recovered <= FLAT:
        verdict = "flat"
    else:
        verdict = "partial"
    band = 0.05 * max(abs(settled), 1.0)
    settle_frames = next((k - i for k in range(i, end) if all(abs(v - settled) <= band for v in y[k:end])), None)
    return {
        "frame": i, "time_s": i / fps, "pre": pre, "after_step": hit, "settled": settled,
        "recovered": recovered, "verdict": verdict,
        "settle_s": None if settle_frames is None else settle_frames / fps,
        "segment_s": (end - i) / fps,
    }


def split_frames(rows):
    """Frames whose top/bottom brightness ratio is far from the clip's usual ratio."""
    ratios = [(r["top"] + 1) / (r["bottom"] + 1) for r in rows]
    med = _median(ratios)
    mad = _median([abs(v - med) for v in ratios]) or 0.01
    return [k for k, v in enumerate(ratios) if abs(v - med) > max(0.15 * med, 8 * mad)]


# ------------------------------------------------------------------------------------------------------- reports

def report_avi(path, args):
    usec, frames = avi_frames(path.read_bytes())
    fps = args.fps or (1e6 / usec if usec else 30.0)
    if not frames:
        print(f"{path.name}: no MJPEG frames found")
        return 1
    rows = []
    for k, jpeg in enumerate(frames):
        try:
            m = measure(jpeg, args.roi)
        except Exception as e:  # a corrupt frame should not end the run
            print(f"  frame {k}: unreadable ({e}); skipped", file=sys.stderr)
            continue
        m["frame"] = k
        m["time_s"] = k / fps
        rows.append(m)
    y = [r["mean"] for r in rows]
    print(f"{path.name}: {len(rows)} frames at {fps:.2f} fps ({len(rows) / fps:.1f} s), ROI {args.roi}")
    print(f"  luma min {min(y):.1f}  median {_median(y):.1f}  max {max(y):.1f}")

    if args.steps:
        steps = sorted(int(round(t * fps)) for t in args.steps)
    else:
        steps = find_steps(y, fps)
    if not steps:
        print("  no step in light found (pass --steps 3.0,10.0 with the times in seconds if you know them)")
    for n, i in enumerate(steps):
        end = steps[n + 1] if n + 1 < len(steps) else len(y)
        s = analyse_step(y, i, end, fps)
        direction = "darker" if s["after_step"] < s["pre"] else "brighter"
        settle = "never within 5%" if s["settle_s"] is None else f"within 5% after {s['settle_s']:.2f} s"
        print(f"  step {n + 1} at {s['time_s']:.2f} s ({direction}): pre {s['pre']:.1f} -> after {s['after_step']:.1f}"
              f" -> settled {s['settled']:.1f}  recovered {100 * s['recovered']:.0f}%  [{s['verdict']}]"
              f"  {settle} (segment {s['segment_s']:.1f} s)")
        if s["segment_s"] < 2.0:
            print("    (under 2 s to the next step or the end: hold each level longer so it can settle)")

    splits = split_frames(rows)
    if splits:
        shown = ", ".join(f"{rows[k]['frame']} ({rows[k]['time_s']:.2f} s)" for k in splits[:12])
        more = f" and {len(splits) - 12} more" if len(splits) > 12 else ""
        print(f"  split frames (top/bottom brightness mismatch): {shown}{more}")
    clipped = max(r["clip"] for r in rows)
    print(f"  worst ROI clipping {100 * clipped:.2f}% (luma >= {CLIP})")

    if args.csv:
        write_csv(args.csv, rows)
        print(f"  per-frame values written to {args.csv}")
    return 0


def report_stills(paths, args):
    rows = []
    for p in paths:
        m = measure(p.read_bytes(), args.roi)
        m["file"] = p.name
        rows.append(m)
        print(f"{p.name}: mean {m['mean']:.1f}  std {m['std']:.1f}  clip {100 * m['clip']:.2f}%"
              f"  dark {100 * m['dark']:.2f}%")
    if args.csv:
        write_csv(args.csv, rows)
        print(f"values written to {args.csv}")
    return 0


def write_csv(path, rows):
    keys = list(rows[0].keys())
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=keys)
        w.writeheader()
        for r in rows:
            w.writerow({k: (f"{v:.4f}" if isinstance(v, float) else v) for k, v in r.items()})


def main(argv=None):
    ap = argparse.ArgumentParser(description="Per-frame brightness of a Charmera AVI or JPEG stills (AE test).")
    ap.add_argument("files", nargs="+", type=Path, help="one or more .AVI files, or .JPG stills")
    ap.add_argument("--roi", type=parse_roi, default=[0.25, 0.25, 0.75, 0.75],
                    help="region to measure, x0,y0,x1,y1 as fractions (default: the centre half)")
    ap.add_argument("--steps", type=lambda s: [float(v) for v in s.split(",")],
                    help="times of the light steps in seconds, if the automatic finder misses them")
    ap.add_argument("--fps", type=float, help="override the frame rate from the AVI header")
    ap.add_argument("--csv", type=Path, help="write the per-frame (or per-file) values here")
    args = ap.parse_args(argv)

    stills = [p for p in args.files if p.suffix.lower() in (".jpg", ".jpeg")]
    videos = [p for p in args.files if p not in stills]
    if stills and videos:
        ap.error("give AVIs or stills, not both in one run")
    if args.csv and len(videos) > 1:
        ap.error("--csv takes one AVI at a time")
    if stills:
        return report_stills(stills, args)
    status = 0
    for p in videos:
        try:
            status |= report_avi(p, args)
        except ValueError as e:
            print(f"{p.name}: {e}")
            status = 1
    return status


if __name__ == "__main__":
    sys.exit(main())
