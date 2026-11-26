"""Firmware recipes: a base (the stock firmware of one build) plus the mods we wrote for it, as data.

recipes.json, next to this file, is the one source both the toolkit and the Android app build from (the app's copy,
android/.../Recipes.java, is generated from it by tools/gen_recipes.py). It lists:

  dumps    the firmware images a build starts from, by SHA-256: each build's original read and its cleaned public copy
           (the-dumping-grounds repositories), with the fixed-commit address of the public ones.
  mods     per base: patches ({file_offset, new bytes, SHA-256 of the bytes they replace}) and copies ({from, to, len}:
           vendor bytes taken from the dump at build time, so none are stored here). Every new byte is ours.
  bases    what each base's stock firmware is, for its name ("stock v012 (the factory firmware, unchanged)").
  sets     the combinations offered, each with the stamp its file name carries, whether it was tested on a camera, its
           name (label: firmware_name of its base and mods) and, for the five that had one, the number older versions
           of the app and the docs called it (old_name, e.g. "v0.27"). The camera never shows either.
  builds   every set made from every dump: the file name and SHA-256 of the result. make() refuses a result that is not
           listed here, so a file it writes is always one this project generated, reviewed and published by hash.

A mod is checked before it is applied: the bytes it replaces must hash to what was recorded. File offset = VA + 0x200.

An owner's own read of a v006 or v012 chip (own_read) is a base too: its firmware must be that build's, byte for byte
over 0..0x169000, and its settings and photos are its own. A file made from one cannot be a reviewed file, since no
published hash covers that camera's own area, so make() returns it marked reviewed=False.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

from . import checksum

RECIPES = Path(__file__).with_name("recipes.json")
UPDATE_SIZE = 0x1E0000
DUMP_SIZE = 0x200000
PHOTOS = (0x180000, 0x1CB000)


class RecipeError(ValueError):
    """A dump, mod or combination the recipes do not allow, or a result that is not the reviewed file."""


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


@dataclass(frozen=True)
class Made:
    data: bytes
    name: str
    sha256: str
    base: str
    mods: Tuple[str, ...]
    dump_id: str
    status: str          # "tested" or "experimental"
    label: str
    reviewed: bool = True  # False for a file made from an owner's own read (own_read): no published hash matches it


def load(path: Path = RECIPES) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


# The known builds (image.KNOWN_BUILDS keys) an own read can be of, and the base each one is.
OWN_READ_BASES = {"public-dump": "v006", "v012": "v012"}


def own_read(data: bytes, recipes: Optional[dict] = None) -> dict:
    """A dumps entry for an owner's own read of a v006 or v012 chip, or RecipeError. The read must be a complete 2 MiB
    read of the chip whose firmware (bytes 0..0x169000) is that build's unchanged. It is cut to the update file's
    length and nothing else: its settings, stored photos and photo directory stay as read (a cleaned public copy has
    its photos blanked only to keep them out of a published file)."""
    from . import image
    recipes = recipes or load()
    problem = image.chip_read_problem(data)
    if problem:
        raise RecipeError(f"not a dump the recipes start from (SHA-256 {sha256(data)}), and not a read of a v006 or v012 "
                          f"chip either: {problem}. Give a full read of the chip, or build from the public copy instead "
                          "(--base v006 or v012 with --download)")
    build = image.match_known_build(data)
    base = OWN_READ_BASES.get(build.key) if build is not None else None
    if build is not None and build.key == "film-mods":
        raise RecipeError("not a dump the recipes start from: this read is stock v008_test. Its public file is the whole "
                          "update, so use charmera build --base v008_test --download")
    if base is None:
        found = image.detect_build(data)
        raise RecipeError("not a dump the recipes start from, and the firmware in this read is not stock v006 or v012 byte "
                          f"for byte (it is {found.identity}: {found.label}). A camera flashed with a mod reads like this. "
                          "Build from the public copy instead (--download), or from a read taken before the camera was "
                          "flashed")
    return {"id": f"{base}-own-read", "build": base, "copy": "own", "prep": "truncate", "size": len(data),
            "sha256": sha256(data), "source": "your own read of the chip", "url": None}


def dump_for(data: bytes, recipes: Optional[dict] = None) -> dict:
    """The dumps entry these bytes are, by SHA-256, or RecipeError."""
    recipes = recipes or load()
    sha = sha256(data)
    for d in recipes["dumps"]:
        if d["sha256"] == sha:
            return d
    known = ", ".join(f"{d['id']} ({d['sha256'][:8]}…)" for d in recipes["dumps"])
    raise RecipeError(f"not a dump the recipes start from (SHA-256 {sha}); known: {known}")


def mods_for(base: str, recipes: Optional[dict] = None) -> List[dict]:
    recipes = recipes or load()
    return [m for m in recipes["mods"] if m["base"] == base]


def resolve(base: str, names: Iterable[str], recipes: Optional[dict] = None) -> Tuple[str, ...]:
    """Mod names as given (short, e.g. 'exif') to their ids on this base, in the recipes' order; checks conflicts."""
    recipes = recipes or load()
    mods = mods_for(base, recipes)
    by_name = {m["name"]: m for m in mods}
    wanted = [n.strip() for n in names if n.strip()]
    unknown = [n for n in wanted if n not in by_name]
    if unknown:
        have = ", ".join(sorted(by_name)) or "none"
        raise RecipeError(f"no mod {', '.join(unknown)} for base {base}; its mods: {have}")
    chosen = [m for m in mods if m["name"] in wanted]          # the recipes' order, whatever order they were given
    ids = {m["id"] for m in chosen}
    for m in chosen:
        clash = sorted(set(m.get("conflicts", [])) & ids)
        if clash:
            raise RecipeError(f"{m['name']} cannot go with {', '.join(c.split('/')[-1] for c in clash)}")
        missing = sorted(set(m.get("requires", [])) - ids)
        if missing:
            raise RecipeError(f"{m['name']} needs {', '.join(c.split('/')[-1] for c in missing)}")
    return tuple(m["id"] for m in chosen)


def in_table_order(mod_ids: Sequence[str], recipes: Optional[dict] = None) -> Tuple[str, ...]:
    """The mods in the table's order, each once, whatever order they were given in; an unknown id is kept at the end
    (the app's Recipe.Selection orders them the same way)."""
    recipes = recipes or load()
    order = [m["id"] for m in recipes["mods"]]
    known = [i for i in order if i in mod_ids]
    return tuple(known + [i for i in dict.fromkeys(mod_ids) if i not in order])


def set_for(base: str, mod_ids: Sequence[str], recipes: Optional[dict] = None) -> dict:
    recipes = recipes or load()
    ids = in_table_order(mod_ids, recipes)
    for s in recipes["sets"]:
        if s["base"] == base and tuple(s["mods"]) == ids:
            return s
    raise RecipeError(f"base {base} with {', '.join(i.split('/')[-1] for i in mod_ids) or 'no mods'} is not one of "
                      "the reviewed combinations (charmera build --list shows them)")


def prepare(dump: bytes, entry: dict) -> bytearray:
    """The dump cut to the base's update file, as the reviewed files were made from it."""
    how = entry["prep"]
    if how == "as-is":
        return bytearray(dump)
    out = bytearray(dump[:UPDATE_SIZE])
    if how == "truncate-blank-photos":
        out[PHOTOS[0]:PHOTOS[1]] = b"\xff" * (PHOTOS[1] - PHOTOS[0])
    elif how != "truncate":
        raise RecipeError(f"unknown preparation {how!r}")
    return out


def apply(out: bytearray, dump: bytes, mod: dict) -> None:
    """One mod: its copies, then its patches, each only over the bytes it was written for."""
    for c in mod.get("copies", []):
        frm, to, n = c["from"], c["to"], c["len"]
        if frm + n > len(dump) or to + n > len(out):
            raise RecipeError(f"{mod['id']}: a copy lies outside the dump or the file")
        if sha256(bytes(out[to:to + n])) != c["old_sha256"]:
            raise RecipeError(f"{mod['id']}: the bytes at file 0x{to:X} are not the ones this mod replaces")
        out[to:to + n] = dump[frm:frm + n]
    for p in mod["patches"]:
        new = bytes.fromhex(p["new"])
        off = p["file_offset"]
        if off + len(new) > len(out):
            raise RecipeError(f"{mod['id']}: a patch lies outside the file")
        if sha256(bytes(out[off:off + len(new)])) != p["old_sha256"]:
            raise RecipeError(f"{mod['id']}: the bytes at file 0x{off:X} are not the ones this mod replaces")
        out[off:off + len(new)] = new


def compose(dump: bytes, mod_ids: Sequence[str], recipes: Optional[dict] = None,
            entry: Optional[dict] = None) -> Tuple[bytes, dict]:
    """The bytes of a base plus mods, unchecked against the builds table (tools/gen_recipes.py uses this). entry is the
    dumps entry the dump is (default: dump_for)."""
    recipes = recipes or load()
    entry = entry or dump_for(dump, recipes)
    base = entry["build"]
    mods = {m["id"]: m for m in mods_for(base, recipes)}
    out = prepare(dump, entry)
    for i in mod_ids:
        if i not in mods:
            raise RecipeError(f"{i} is not a mod of base {base}")
        apply(out, dump, mods[i])
    return bytes(out), entry


def name_for(data: bytes, stamp: str) -> str:
    return checksum.build_update_name(data, stamp).filename


def make(dump: bytes, names: Iterable[str] = (), recipes: Optional[dict] = None, own: bool = False) -> Made:
    """A reviewed file: the dump's base with these mods, refused unless the result is the listed one. With own, a dump
    the recipes do not list may be an owner's own read of a v006 or v012 chip (own_read): the file made from it is
    returned with reviewed=False, unless it happens to be a reviewed file byte for byte."""
    recipes = recipes or load()
    try:
        entry = dump_for(dump, recipes)
    except RecipeError:
        if not own:
            raise
        entry = own_read(dump, recipes)
    base = entry["build"]
    ids = resolve(base, names, recipes)
    s = set_for(base, ids, recipes)
    data, _ = compose(dump, ids, recipes, entry)
    sha = sha256(data)
    if entry["copy"] == "own":
        row = reviewed(sha, recipes)
        name = row["name"] if row else name_for(data, s["stamp"])
        return Made(data, name, sha, base, ids, entry["id"], s["status"], s["label"], reviewed=row is not None)
    for b in recipes["builds"]:
        if b["base"] == base and tuple(b["mods"]) == ids and b["dump"] == entry["id"]:
            if b["sha256"] != sha:
                raise RecipeError(f"the file made is not the reviewed one, so it is not written. This should not happen."
                                  f" Its SHA-256 is {sha}; the reviewed file's is {b['sha256']}.")
            return Made(data, b["name"], sha, base, ids, entry["id"], s["status"], s["label"])
    raise RecipeError(f"no reviewed file is listed for {s['label']} from {entry['id']}")


def reviewed(sha: str, recipes: Optional[dict] = None) -> Optional[dict]:
    """The builds row of a reviewed file, by its SHA-256, or None."""
    recipes = recipes or load()
    for b in recipes["builds"]:
        if b["sha256"] == sha:
            return b
    return None


def join_and(parts: Sequence[str]) -> str:
    """"a", "a and b", "a, b and c"."""
    parts = list(parts)
    if len(parts) <= 1:
        return "".join(parts)
    return ", ".join(parts[:-1]) + " and " + parts[-1]


def firmware_name(base: str, mod_ids: Sequence[str], recipes: Optional[dict] = None) -> str:
    """What a build is called: its base and the mods on it, e.g. "v012 with Volume and LED rows and EXIF dates", or
    "stock v012 (the factory firmware, unchanged)" with no mods. Each mod is named by its phrase in recipes.json, in the
    table's order whatever order they were given in; an unknown id is named by itself, at the end. The app's
    Recipe.firmwareName follows the same rule, and every set's label is this name (tests check both)."""
    recipes = recipes or load()
    if not mod_ids:
        stock = next((b["stock"] for b in recipes.get("bases", []) if b["id"] == base), None)
        return f"stock {base}" + (f" ({stock})" if stock else "")
    order = [m["id"] for m in recipes["mods"]]
    phrase = {m["id"]: m["phrase"] for m in recipes["mods"]}
    known = [i for i in order if i in mod_ids]
    unknown = [i for i in dict.fromkeys(mod_ids) if i not in phrase]
    return f"{base} with " + join_and([phrase[i] for i in known] + unknown)


def old_name(base: str, mod_ids: Sequence[str], recipes: Optional[dict] = None) -> Optional[str]:
    """The number an older app and older notes used for this build ("v0.27"), or None. Only five builds had one."""
    recipes = recipes or load()
    ids = in_table_order(mod_ids, recipes)
    for s in recipes["sets"]:
        if s["base"] == base and tuple(s["mods"]) == ids:
            return s.get("old_name")
    return None


def label(base: str, mod_ids: Sequence[str], recipes: Optional[dict] = None) -> str:
    return set_for(base, mod_ids, recipes)["label"]


def sets_by_base(recipes: Optional[dict] = None) -> Dict[str, List[dict]]:
    recipes = recipes or load()
    out: Dict[str, List[dict]] = {}
    for s in recipes["sets"]:
        out.setdefault(s["base"], []).append(s)
    return out
