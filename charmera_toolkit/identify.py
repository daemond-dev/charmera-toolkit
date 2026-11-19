"""`identify`: which STOCK firmware a camera runs, from the headers of its card's photos alone. Read-only.

Its job is to pick the base to build on, and it assumes a stock camera that was never flashed or modded. Being
flashed or modded is the exception: it detects that and says so, without letting it weaken the normal stock answer.

It finds the photos (*.jpg) and videos (*.avi) on a card (under its DCIM folder), in a folder or among the files
given, and reads only their headers, through photo_id (never the image data, never past the first 64 KiB of a file).
Photos with an unknown file time (0 or missing) are left out of the dating and of the change detection, and counted.
It picks the oldest and the newest dated photo by modification time (ties broken by path) that the camera wrote as it
is (photo_id.CAMERA_KINDS), skipping files another program saved again, exif-fix copies and files it cannot read, and
reads the kind of every dated photo, up to a cap (the oldest and newest chosen are always among them).

Families: the "v006 family" is the 0x590-byte Generalplus header (stock v006 or v008_test; SP140A cameras), the
"v012 family" the bare 0x270-byte one (stock v012; OV9732 cameras). The 0x340-byte EXIF-mod header is ours (v0.26 and
later, built on v012): the camera is not stock, and it is of the v012 family.

- all dated camera-written photos one family: "The photos were taken by stock v012 (an OV9732 camera)" (or "v006 or
  v008_test (an SP140A camera)"), base v012 (or v006), and that a flash after these photos leaves the camera running
  something else; confidence high when the scan was not capped and at least 2 camera-written photos agree, else medium;
- any EXIF-mod photo: not stock, our EXIF mod is installed, base v012 (the mod is built on it), confidence high; bare
  photos before them date the install. Bare and EXIF-mod photos are one family;
- two families on the card: not stock (a flash in between, or photos from two cameras), base that of the OLDEST
  family, since photos from before a flash show the stock firmware; confidence low;
- v006-family photos are not doubted for our v0.21-v0.24 writing the same header (the stock assumption); one line
  says so.

A photo cannot tell v006 from v008_test, and a photo shows the firmware that took it. The dates are the files'
modification times as the card holds them, which the camera's clock set; they order the photos, but the clock may be
off.
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from . import photo_id

DEFAULT_CAP = 500
PHOTO_SUFFIXES = (".jpg", ".jpeg")
VIDEO_SUFFIXES = (".avi",)

# The base to build on for each family, and the stock firmware a camera of that family runs.
BASE = {
    photo_id.FAMILY_SP140A: "v006",
    photo_id.FAMILY_OV9732: "v012",
}
STOCK = {
    photo_id.FAMILY_SP140A: "v006 or v008_test",
    photo_id.FAMILY_OV9732: "v012",
}
CAMERA = {
    photo_id.FAMILY_SP140A: "stock firmware of SP140A cameras",
    photo_id.FAMILY_OV9732: "stock firmware of OV9732 cameras",
}
SENSOR = {
    photo_id.FAMILY_SP140A: "an SP140A camera",
    photo_id.FAMILY_OV9732: "an OV9732 camera",
}
# What to run next with the base identify found.
NEXT = "charmera build --list, then charmera build --base {base} --mods <mods> --download --out out/"
SHORT_FAMILY = {
    photo_id.FAMILY_SP140A: "the v006 family (stock v006 or v008_test)",
    photo_id.FAMILY_OV9732: "the v012 family (stock v012)",
}
NOT_STOCK = "This camera does not look stock: "
OUR_V021_V024 = "If this camera was ever flashed with our v0.21-v0.24, its photos look like this too."
CAVEATS = [
    "a photo cannot tell v006 from v008_test (both stock builds for SP140A cameras; either base works the same for "
    "our purposes).",
    "photos show the firmware that took them.",
]


@dataclass
class Media:
    path: Path
    mtime: float
    kind: Optional[str] = None          # photo_id kind, or None until read
    family: Optional[str] = None
    reasons: List[str] = field(default_factory=list)
    length: Optional[int] = None
    software: Optional[str] = None
    error: Optional[str] = None         # why it could not be read, or why it is no JPEG
    read: bool = False

    @property
    def dated(self) -> bool:
        """False when the file time is unknown (0 or missing): such a photo is left out of the dating."""
        return bool(self.mtime) and self.mtime > 0

    @property
    def date(self) -> str:
        return datetime.fromtimestamp(self.mtime).strftime("%Y-%m-%d %H:%M") if self.dated else "no file time"

    @property
    def camera(self) -> bool:
        return self.kind in photo_id.CAMERA_KINDS

    def as_dict(self) -> Dict:
        return {"path": str(self.path), "date": self.date if self.dated else None,
                "mtime": self.mtime if self.dated else None, "kind": self.kind,
                "family": self.family, "header_length": self.length, "software": self.software,
                "reasons": self.reasons, "error": self.error}


# --- Finding the files -------------------------------------------------------------------------------------------


def _dcim_of(folder: Path) -> Optional[Path]:
    try:
        for child in folder.iterdir():
            if child.is_dir() and child.name.lower() == "dcim":
                return child
    except OSError:
        return None
    return None


def find_media(paths: List[Path]) -> Tuple[List[Media], List[Media], List[str]]:
    """(photos, videos, problems) from card roots, folders or files. A folder that holds a DCIM folder is a card
    root, and only its DCIM folder is searched; any other folder is searched whole. Both lists are sorted by
    modification time, then path."""
    photos: Dict[Path, Media] = {}
    videos: Dict[Path, Media] = {}
    problems: List[str] = []

    def add(p: Path) -> None:
        suffix = p.suffix.lower()
        target = photos if suffix in PHOTO_SUFFIXES else videos if suffix in VIDEO_SUFFIXES else None
        if target is None or p in target:
            return
        try:
            target[p] = Media(p, p.stat().st_mtime or 0.0)
        except OSError as exc:
            problems.append(f"{p}: cannot read its date: {exc}")

    for given in paths:
        if given.is_file():
            add(given)
        elif given.is_dir():
            top = _dcim_of(given) or given
            for root, dirs, files in os.walk(top, onerror=lambda e: problems.append(f"{e.filename}: {e.strerror}")):
                dirs.sort()
                for name in sorted(files):
                    add(Path(root) / name)
        else:
            problems.append(f"{given}: no such file or folder")
    order = lambda m: (m.mtime, str(m.path))       # noqa: E731
    return sorted(photos.values(), key=order), sorted(videos.values(), key=order), problems


# --- Reading the headers -------------------------------------------------------------------------------------------


def read_photo(m: Media) -> Media:
    """Fill in m's kind from its header (once)."""
    if m.read:
        return m
    m.read = True
    try:
        h, _avi, _frame = photo_id.open_header(m.path)
    except OSError as exc:
        m.error = f"cannot read it: {exc}"
        return m
    if h is None:
        m.error = "not a JPEG (no FF D8 at the start)"
        return m
    v = photo_id.verdict(h)
    m.kind, m.family, m.reasons, m.length = v.kind, v.family, v.reasons, h.length
    m.software = h.exif.software if h.exif is not None and h.exif.software else None
    return m


def read_video(m: Media) -> Tuple[Media, str]:
    """m, and the conclusion photo-id draws for the whole file from its chunk headers and first frame header."""
    m.read = True
    try:
        lines = photo_id.identify(m.path)
    except OSError as exc:
        m.error = f"cannot read it: {exc}"
        return m, m.error
    return m, next((line[len("conclusion:"):].strip() for line in lines if line.startswith("conclusion:")), "")


def why_skipped(m: Media) -> str:
    if m.error:
        return m.error
    if m.kind == "exif-fix":
        return "a copy the toolkit's exif-fix made, not the camera's own file"
    if m.kind == "resave":
        return "re-saved by another program (" + "; ".join(m.reasons) + ")"
    return photo_id.CONCLUSIONS.get(m.kind, "unknown")


def first_camera(photos: List[Media], budget: int) -> Tuple[Optional[Media], List[Media]]:
    """The first camera-written photo in this order, reading at most `budget` headers, and the ones skipped."""
    skipped = []
    for m in photos[:budget]:
        read_photo(m)
        if m.camera:
            return m, skipped
        skipped.append(m)
    return None, skipped


def sample(photos: List[Media], cap: int, keep: Tuple[Optional[Media], ...] = ()) -> List[Media]:
    """All photos when there are no more than cap; else cap of them evenly spaced by date order, the first and last
    among them. The photos in `keep` (the oldest and newest camera-written ones chosen) are always included."""
    n = len(photos)
    if n <= cap:
        return list(photos)
    picks = {n - 1} if cap <= 1 else {round(i * (n - 1) / (cap - 1)) for i in range(cap)}
    index = {id(m): i for i, m in enumerate(photos)}
    picks |= {index[id(m)] for m in keep if m is not None and id(m) in index}
    return [photos[i] for i in sorted(picks)]


def runs(scanned: List[Media]) -> List[Dict]:
    """Consecutive runs, in date order, of the camera-written photos' families: family, first and last photo."""
    out: List[Dict] = []
    for m in scanned:
        if not m.camera:
            continue
        if out and out[-1]["family"] == m.family:
            out[-1]["last"], out[-1]["count"] = m, out[-1]["count"] + 1
        else:
            out.append({"family": m.family, "first": m, "last": m, "count": 1})
    return out


# --- The conclusion -------------------------------------------------------------------------------------------------


@dataclass
class Report:
    photos: int = 0
    videos: int = 0
    scanned: int = 0
    capped: bool = False
    oldest: Optional[Media] = None
    newest: Optional[Media] = None
    skipped_oldest: List[Media] = field(default_factory=list)
    skipped_newest: List[Media] = field(default_factory=list)
    undated: List[Media] = field(default_factory=list)
    newest_video: Optional[Media] = None
    video_says: str = ""
    family_runs: List[Dict] = field(default_factory=list)
    problems: List[str] = field(default_factory=list)
    camera_count: int = 0
    stock: Optional[bool] = None
    not_stock: List[str] = field(default_factory=list)    # each "This camera does not look stock: ..."
    notes: List[str] = field(default_factory=list)
    family: Optional[str] = None                          # the family of the base
    base: Optional[str] = None
    confidence: Optional[str] = None
    confidence_why: str = ""
    families_text: str = ""
    conclusion: str = ""

    @property
    def reached(self) -> bool:
        return self.family is not None

    @property
    def undated_text(self) -> str:
        n = len(self.undated)
        if not n:
            return ""
        if n == 1:
            return "1 photo has no file time and was left out of the dating"
        return f"{n} photos have no file time and were left out of the dating"


def _families_text(family_runs: List[Dict]) -> str:
    first = family_runs[0]
    if len(family_runs) == 2:
        last = family_runs[1]
        return (f"photos from two firmware families: {SHORT_FAMILY[first['family']]} up to {first['last'].date}, "
                f"{SHORT_FAMILY[last['family']]} from {last['first'].date}: the camera was flashed in between, or "
                "the card holds photos from two cameras")
    return (f"photos from two firmware families, which change {len(family_runs) - 1} times by date ("
            + "; ".join(f"{SHORT_FAMILY[x['family']]} {x['first'].date} to {x['last'].date}" for x in family_runs)
            + "): the camera was flashed more than once, or the card holds photos from more than one camera")


NO_PHOTOS_PROBE = ("If photo mode freezes and the camera can't take any, the sensor-ID probe finds the sensor instead "
                   "(Identify the image sensor in the app, or `charmera build --base v006 --mods sensor-probe --download "
                   "--out out/`)")


def _left_out_text(left_out: List[Media]) -> str:
    """Why the photos read that the camera did not write as they are were left out."""
    kinds = []
    resaved = sum(1 for m in left_out if m.kind == "resave")
    fixed = sum(1 for m in left_out if m.kind == "exif-fix")
    other = len(left_out) - resaved - fixed
    if resaved:
        kinds.append(f"{resaved} saved again by another program (a phone or editor)")
    if fixed:
        kinds.append(f"{fixed} copies exif-fix made")
    if other:
        kinds.append(f"{other} unreadable or not the camera's own")
    return (f"{len(left_out)} photo(s) read say nothing about the camera and were left out: " + ", ".join(kinds)
            + ". `charmera photo-id <file>` says why for one file")


def build_report(paths: List[Path], cap: int = DEFAULT_CAP) -> Report:
    photos, videos, problems = find_media(paths)
    r = Report(photos=len(photos), videos=len(videos), problems=problems)
    dated = [m for m in photos if m.dated]
    r.undated = [m for m in photos if not m.dated]
    r.oldest, r.skipped_oldest = first_camera(dated, cap)
    r.newest, r.skipped_newest = first_camera(list(reversed(dated)), cap)
    if videos:
        r.newest_video, r.video_says = read_video(videos[-1])
    scanned = sample(dated, cap, (r.oldest, r.newest))
    r.capped = len(scanned) < len(dated)
    for m in scanned:
        read_photo(m)
    r.scanned = len(scanned)
    r.family_runs = runs(scanned)
    if r.newest is None:
        r.conclusion = (("no dated photo the camera wrote as it is was found: nothing to conclude. Take two or three "
                         "photos with the camera on this card, then run identify again"
                         if photos else "no photos found: nothing to conclude. Take two or three photos with the camera on "
                         "this card first, then run identify again") + ". " + NO_PHOTOS_PROBE)
        return r
    cam = [m for m in scanned if m.camera]
    r.camera_count = len(cam)
    families: List[str] = []
    for m in cam:
        if m.family not in families:
            families.append(m.family)
    mods = [m for m in cam if m.kind == "exif-mod"]
    oldest_family = cam[0].family          # the oldest camera-written photo is always among those read
    r.family, r.base = oldest_family, BASE[oldest_family]

    if mods:
        first_mod = mods[0]
        text = ("our EXIF mod (v0.26 or later" + (f", Software {first_mod.software!r}" if first_mod.software else "")
                + ") is installed")
        before = [m for m in cam if m.kind == "bare" and m.mtime <= first_mod.mtime and m is not first_mod]
        if before:
            text += f", installed between {before[-1].date} and {first_mod.date}"
        r.not_stock.append(NOT_STOCK + text)
    if len(families) > 1:
        r.families_text = _families_text(r.family_runs)
        r.not_stock.insert(0, NOT_STOCK + r.families_text)   # it decides the base, so it comes first (as in the app)
        r.stock = False
        newer = next(f for f in families if f != oldest_family)
        r.conclusion = (f"The oldest photos show stock {STOCK[oldest_family]} (photos from before a flash show the "
                        f"stock firmware), the newer ones {SHORT_FAMILY[newer]}: base {r.base}")
        r.confidence = "low"
        r.confidence_why = ("the card holds photos from two firmware families; the base follows the oldest, from "
                            "before the change")
        if r.capped:
            r.confidence_why += (f"; read {r.scanned} of {len(dated)} dated photo headers, so the change-over dates "
                                 "are approximate")
    elif mods:
        r.stock = False
        r.conclusion = f"The camera runs our EXIF mod (v0.26 or later), which is built on stock v012: base {r.base}"
        r.confidence = "high"
        r.confidence_why = "the EXIF mod's header is ours alone, and the mod is built on v012"
    else:
        r.stock = True
        r.conclusion = (f"The photos were taken by stock {STOCK[oldest_family]} ({SENSOR[oldest_family]}): base {r.base}. "
                        "If the camera was flashed after these photos, it runs something else now")
        whys = []
        if r.camera_count < 2:
            whys.append("only one camera-written photo was found")
        if r.capped:
            whys.append(f"read {r.scanned} of {len(dated)} dated photo headers, evenly spaced by date")
        if whys:
            r.confidence, r.confidence_why = "medium", "; ".join(whys)
        else:
            r.confidence = "high"
            r.confidence_why = f"all {r.camera_count} camera-written photos read show the same family, oldest to newest"
    left_out = [m for m in scanned if not m.camera]
    if left_out:
        r.notes.append(_left_out_text(left_out))
    elif r.skipped_newest:
        r.notes.append(f"the newest {len(r.skipped_newest)} photo file(s) were not the camera's own and were skipped")
    if photo_id.FAMILY_SP140A in families:
        r.notes.append(OUR_V021_V024)
    return r


def report_lines(r: Report) -> List[str]:
    lines = [f"photos:      {r.photos} found" + (f", {r.scanned} headers read for families" if r.photos else "")
             + (f" (capped at {r.scanned}; evenly spaced by date)" if r.capped else ""),
             f"videos:      {r.videos} found"]
    for p in r.problems:
        lines.append(f"problem:     {p}")

    def evidence(label: str, m: Optional[Media]) -> None:
        if m is None:
            return
        lines.append(f"{label:<13}{m.path}, {m.date}, {m.length:#x}-byte header ({m.kind})")
        lines.append(f"             {photo_id.CONCLUSIONS[m.kind]}")

    evidence("photo:" if r.oldest is not None and r.oldest is r.newest else "oldest:", r.oldest)
    for m in r.skipped_oldest:
        lines.append(f"skipped:     {m.path}, {m.date}: {why_skipped(m)}")
    if r.newest is not None and r.newest is not r.oldest:
        evidence("newest:", r.newest)
    for m in r.skipped_newest:
        if all(m is not o for o in r.skipped_oldest):
            lines.append(f"skipped:     {m.path}, {m.date}: {why_skipped(m)}")
    if r.undated:
        lines.append(f"undated:     {r.undated_text}")
        for m in r.undated:
            lines.append(f"             {m.path}")
    if r.newest_video is not None:
        v = r.newest_video
        lines.append(f"video:       {v.path}, {v.date}: {r.video_says}")
        lines.append("             (a video's frames do not tell the builds apart; it is not used for the conclusion)")
    for text in r.not_stock:
        lines.append(f"not stock:   {text}")
    lines.append(f"conclusion:  {r.conclusion}")
    if r.reached:
        lines.append(f"base:        {r.base} ({CAMERA[r.family]})")
        lines.append(f"confidence:  {r.confidence}: {r.confidence_why}")
        for n in r.notes:
            lines.append(f"note:        {n}")
        for c in CAVEATS:
            lines.append(f"caveat:      {c}")
        lines.append(f"next:        {NEXT.format(base=r.base)}")
    return lines


def report_json(r: Report) -> Dict:
    def run(x: Dict) -> Dict:
        return {"family": x["family"], "count": x["count"], "first": x["first"].date, "last": x["last"].date,
                "first_path": str(x["first"].path), "last_path": str(x["last"].path)}
    return {
        "reached": r.reached,
        "photos": r.photos, "videos": r.videos, "scanned": r.scanned, "capped": r.capped,
        "oldest": r.oldest.as_dict() if r.oldest else None,
        "newest": r.newest.as_dict() if r.newest else None,
        "skipped": [m.as_dict() for m in r.skipped_oldest
                    + [m for m in r.skipped_newest if all(m is not o for o in r.skipped_oldest)]],
        "undated": [str(m.path) for m in r.undated],
        "undated_text": r.undated_text or None,
        "newest_video": dict(r.newest_video.as_dict(), conclusion=r.video_says) if r.newest_video else None,
        "family_runs": [run(x) for x in r.family_runs],
        "families": r.families_text or None,
        "stock": r.stock,
        "not_stock": r.not_stock,
        "family": r.family, "family_text": photo_id.FAMILIES.get(r.family) if r.family else None,
        "base": r.base, "confidence": r.confidence, "confidence_why": r.confidence_why or None,
        "conclusion": r.conclusion,
        "next": NEXT.format(base=r.base) if r.reached else None,
        "notes": r.notes,
        "caveats": CAVEATS if r.reached else [],
        "problems": r.problems,
    }


def handler(args) -> int:
    r = build_report([Path(p) for p in args.paths], cap=max(1, args.cap))
    if args.json:
        print(json.dumps(report_json(r), indent=2))
    else:
        for line in report_lines(r):
            print(line)
    return 0 if r.reached else 1


def register(subparsers) -> None:
    p = subparsers.add_parser(
        "identify",
        help="say which stock firmware a camera runs, from its card's photos, and the base to build on (read-only; "
             "headers only)",
        description=(
            "Assuming a stock camera that was never flashed or modded, say which stock firmware it runs and so which "
            "base to build on. It finds the photos (*.jpg) and videos (*.avi) on a card (its DCIM folder), in a "
            "folder or among the files given, and reads only their headers. Photos with no file time are left out of "
            "the dating and counted. It picks the oldest and the newest dated photo the camera wrote as it is, "
            "skipping files another program saved again and exif-fix copies, and reads the kind of every dated photo "
            "(up to --cap, the oldest and newest always among them). When every photo is from one family it says "
            "'The photos were taken by stock v012' (the bare 0x270-byte header; base v012) or 'stock v006 or v008_test' "
            "(the 0x590-byte header; base v006), and prints the build command to run next. It also says when the camera doesn't look stock: when it finds our EXIF "
            "mod's header (v0.26 or later, base v012, the mod's own base), or photos from two families (a flash in "
            "between, or two cameras). With two families, the base follows the oldest, since photos from before a "
            "flash show the stock firmware, and the confidence is low."
        ),
        epilog=(
            "A photo cannot tell v006 from v008_test (both stock builds for SP140A cameras; either base works the "
            "same for our purposes). Photos show the firmware that took them. The dates are the files' modification "
            "times, which the camera's clock set. It needs photos the camera took on this card: take two or three first. "
            "Exit code 0 when a conclusion was reached, 1 when no usable photo was found."
        ),
    )
    p.add_argument("paths", nargs="+", metavar="PATH", help="a card root, a DCIM folder, another folder or photo/video files")
    p.add_argument("--json", action="store_true", help="print the result as JSON")
    p.add_argument("--cap", type=int, default=DEFAULT_CAP,
                   help=f"read at most this many photo headers for the family scan (default {DEFAULT_CAP}; the "
                        "oldest and newest camera-written photos are always read)")
    p.set_defaults(func=handler)
