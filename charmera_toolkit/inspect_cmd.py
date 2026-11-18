"""`inspect`: print what is in a dump or update file. Read-only."""
from __future__ import annotations

import json
import os
from pathlib import Path

from . import settings as settings_mod
from .checksum import OFFICIAL_UPDATE_SIZE, UPDATE_FILE_SIZE, parse_update_name
from .image import SOUND_SLOTS, SOURCE_TREE_PROVES, known_source_trees, summarize
from .stage import looks_like_update_file


def _fmt_bool(v) -> str:
    return "yes" if v else ("no" if v is False else "n/a")


def _program(t) -> str:
    return (f"{t.pairs} register writes at {t.init[0]:#x}..{t.init[1]:#x} ({t.init_bytes_len} bytes), reset table at {t.reset[0]:#x}"
            + (f", bus address {t.bus_address:#x}" if t.bus_address is not None else "")
            + (f", referenced from code at {t.referenced_from:#x}" if t.referenced_from is not None else ", no code reference found"))


def print_report(path: Path, image: bytes, show_resources: bool = False, show_settings: bool = False) -> int:
    info = summarize(image)
    print(f"file:            {path}")
    print(f"size:            {info['size']} bytes ({info['size'] / 1024:.0f} KiB), kind: {info['kind']}")
    print(f"sha256:          {info['sha256']}")
    print(f"byte sum:        {info['byte_sum']:08X}")
    if info["public_dump"]:
        print(f"identity:        matches a public dump: {info['public_dump']}")
    print(f"magic 'SPII':    {_fmt_bool(info['magic_ok'])}")
    print(f"header words:    {' '.join(f'{w:08x}' for w in info['header_words'][:18])}")
    if info["reg_init"]:
        print("reg init list:   " + ", ".join(f"[{r:#010x}]={v:#x}" for r, v in info["reg_init"]) + "  (INFERRED meaning)")
    print(f"build:           {info['identity']}  ({info['identity_label']})")
    print("                 a build match says which public image this is, not whether it fits a given camera: only a dump of")
    print("                 that camera's own chip read before it was ever flashed does. The stamp 202505201029 is carried by")
    print("                 two different builds.")
    for k, v in info["identity_checks"].items():
        print(f"  check {k:24s} {_fmt_bool(v)}")
    m = info["marks"]
    if m.stamp_conflict:
        print(f"stamp:           (not found: several different stamps follow '*.bin': {', '.join(m.stamp_conflict)}; which one the updater "
              "compares cannot be told, and verify refuses the file)")
    else:
        print(f"stamp:           {m.stamp or '(not found)'}" + (f"  at {m.stamp_offset:#x}" if m.stamp else "") + "  (found by content, not at a fixed offset)")
    print(f"build string:    {m.build_string.decode('ascii', 'replace') if m.build_string else '(not found)'}" + (f"  at {m.build_offset:#x}" if m.build_string else ""))
    print(f"compiled:        {m.compiled.decode('ascii', 'replace') if m.compiled else '(not found)'}" + (f"  at {m.compiled_offset:#x}" if m.compiled else ""))
    if m.source_tree:
        print(f"source tree:     {m.source_tree}  at {m.source_tree_offset:#x}, named by {m.source_tree_paths} source paths  (found by "
              f"content: the folder after \\2247\\5408\\; {known_source_trees()})")
        print(f"                 {SOURCE_TREE_PROVES}")
    else:
        print("source tree:     (not found: no source path names the folder the firmware was built in)")
    if m.sensor:
        t = m.sensor
        print(f"sensor table:    {_program(t)}")
        print(f"                 sha256 {t.sha256}")
        print("                 " + ("the same table as in both known builds (public dump, film-mods): it drives one sensor and reads no chip ID"
                                    if info["identity_checks"].get("sensor_table_known") else "NOT the known builds' table"))
        if t.referenced_from is None:
            print("                 no code refers to it by address, so the code may set the sensor up from another table (verify warns)")
        others = [p for p in m.sensor_programs if p != t]
        for p in others:
            print(f"  also found:    {_program(p)}, sha256 {p.sha256[:8]}...")
        if m.several_sensor_programs:
            print(f"                 SEVERAL sensor programs: the code refers to {len(m.several_sensor_programs)} init tables with different "
                  "contents, so which one sets up the sensor cannot be told; verify refuses the file")
    else:
        print("sensor table:    not found (no reset table 'fd 00 20 00 ff ff' followed by an init table in the code area)")
    if info["software_sha256"]:
        print(f"software sha256: {info['software_sha256']}  (bytes 0..0x170000)")
    fp = info.get("official_fingerprint")
    if fp:
        print(f"official span:   sha1 {fp[1]}  byte sum {fp[2]:08X}  (bytes 0..0x169000, what an official-size file writes; the film-mods author's build is 91499bd3... / 0852C329)")

    # Filename check when it looks like an update file. The camera flashes any JH_5408*.bin in a card root whose sum
    # matches its name, whatever its size, so a file of any other size must never go on a card under such a name.
    named_like_update = looks_like_update_file(path.name)
    try:
        name = parse_update_name(path.name)
        ok = name.checksum == info["byte_sum"]
        print(f"update name:     stamp {name.stamp}, checksum {name.checksum:08X} -> {'matches file' if ok else 'DOES NOT MATCH file'}")
        if not ok:
            print("                 renaming the file to match does not make it safe: verify checks what the camera does not")
    except ValueError:
        pass
    if named_like_update and info["size"] not in (UPDATE_FILE_SIZE, OFFICIAL_UPDATE_SIZE):
        print(f"NOT FOR A CARD:  this file is named like an update file but is {info['size']:,} bytes; the camera is known to take")
        print(f"                 only {UPDATE_FILE_SIZE:#x} and {OFFICIAL_UPDATE_SIZE:#x} bytes. Never put it on a card, under this name or")
        print("                 any other (a 2 MiB file's final erase wraps to block 1 and bricks the camera). pack makes update files,")
        print("                 and verify and stage check them.")

    res = info["resources"]
    print(f"resources:       {len(res)} entries in the GP+ pack" + (f", data ends at {info['resources_end']:#x}" if info["resources_end"] else ""))
    if show_resources:
        for e in res:
            print(f"  {e.index:2d} {e.display_name:20s} at {e.offset:#8x}  budget {e.budget}")
    if info.get("sounds_error"):
        print(f"sounds:          cannot be read: {info['sounds_error']}")
    if info["sounds"]:
        print("sounds:")
        for s in info["sounds"]:
            what = SOUND_SLOTS[s.entry.name][0]
            print(f"  {s.entry.display_name:20s} {what:10s} at {s.entry.offset:#8x}  {s.rate} Hz {s.bits}-bit "
                  f"{s.channels}ch  {s.duration_ms} ms  data {s.data_len} B  slot budget {s.entry.budget} B")

    recs = info["settings_records"]
    if recs:
        cur = info["current_settings"]
        if cur:
            print(f"settings:        {len(recs)} records, {info['settings_valid']} CRC-valid; the camera will load the one at {cur.offset:#x}: "
                  f"UI sound {cur.ui_sound} ({'OFF' if cur.ui_sound == 0 else 'on'}), playback volume {cur.playback_volume}, "
                  f"auto-off code {cur.raw[settings_mod.OFF_AUTO_OFF]}")
        else:
            print(f"settings:        {len(recs)} records, {info['settings_valid']} CRC-valid; the camera would apply defaults: {info['current_note']}")
        if show_settings and cur:
            for off, name in settings_mod.FIELD_NAMES.items():
                print(f"  +{off:#04x} = {cur.raw[off]:3d}  {name}")
    elif info["size"] > settings_mod.SETTINGS_BASE:
        print("settings:        store is blank (0xFF); the camera will create defaults")

    if info["photo_index"] or info["stored_photos"]:
        print(f"stored photos:   index {[(p.slot, p.used, p.size) for p in info['photo_index']]}, "
              f"JPEG data {[(hex(o), n) for o, n in info['stored_photos']]}")
        if info["stored_photos"]:
            print(f"                 ({info['photos_whose']})")
    if info["tail_blank"] is not None:
        print(f"tail 0x1E0000..: {'all 0xFF' if info['tail_blank'] else 'NOT blank'}")
    return 0


def handler(args) -> int:
    path = Path(args.file)
    if not path.is_file():
        print(f"error: {path} is not a file")
        return 2
    image = path.read_bytes()
    if len(image) < 0x200:
        print(f"error: {path} is {len(image)} bytes; a dump is 2 MiB and an update file 0x1E0000 bytes")
        return 2
    if args.json:
        info = summarize(image)
        out = {k: v for k, v in info.items() if k not in ("resources", "sounds", "settings_records", "current_settings", "photo_index", "build", "marks")}
        m = info["marks"]
        out["marks"] = {
            "stamp": m.stamp, "stamp_offset": m.stamp_offset,
            "build_string": m.build_string.decode("ascii", "replace") if m.build_string else None, "build_offset": m.build_offset,
            "compiled": m.compiled.decode("ascii", "replace") if m.compiled else None, "compiled_offset": m.compiled_offset,
            "source_tree": m.source_tree, "source_tree_offset": m.source_tree_offset, "source_tree_paths": m.source_tree_paths,
            "sensor_table": None if not m.sensor else {"reset": list(m.sensor.reset), "init": list(m.sensor.init), "pairs": m.sensor.pairs,
                                                       "sha256": m.sensor.sha256, "bus_address": m.sensor.bus_address,
                                                       "referenced_from": m.sensor.referenced_from},
            "sensor_programs": [{"init": list(p.init), "pairs": p.pairs, "sha256": p.sha256, "referenced_from": p.referenced_from}
                                for p in m.sensor_programs],
            "several_sensor_programs": bool(m.several_sensor_programs),
            "stamp_conflict": list(m.stamp_conflict),
        }
        out["resources"] = [{"name": e.display_name, "offset": e.offset, "budget": e.budget} for e in info["resources"]]
        out["sounds"] = [{"name": s.entry.display_name, "offset": s.entry.offset, "rate": s.rate, "bits": s.bits,
                          "channels": s.channels, "data_len": s.data_len, "duration_ms": s.duration_ms} for s in info["sounds"]]
        cur = info["current_settings"]
        out["current_settings"] = {"offset": cur.offset, "ui_sound": cur.ui_sound, "playback_volume": cur.playback_volume} if cur else None
        out["photo_index"] = [vars(p) for p in info["photo_index"]]
        print(json.dumps(out, indent=2, default=str))
        return 0
    return print_report(path, image, args.resources, args.settings)


def register(subparsers) -> None:
    p = subparsers.add_parser("inspect", help="parse a dump or update file and print what it contains (read-only)")
    p.add_argument("file")
    p.add_argument("--resources", action="store_true", help="list every resource-pack entry")
    p.add_argument("--settings", action="store_true", help="print the known settings fields of the current record")
    p.add_argument("--json", action="store_true", help="machine-readable output")
    p.set_defaults(func=handler)


if __name__ == "__main__":  # pragma: no cover
    import sys
    sys.exit(handler(type("A", (), {"file": sys.argv[1], "resources": True, "settings": True, "json": False})))
