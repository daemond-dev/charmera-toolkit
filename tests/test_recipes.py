"""The recipe table (charmera_toolkit/recipes.json), charmera build, and the app's generated copy of the table.

Without the firmware images only the table's shape and the refusals are checked. With CHARMERA_STOCK_IMAGE (v006,
original or cleaned), CHARMERA_FACTORY_DUMP (v012, original or cleaned) and CHARMERA_FILM_MODS_RESTORE (v008_test),
every combination of that dump's base is rebuilt and must be the listed reviewed file, byte for byte.
"""
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest

from charmera_toolkit import build_cmd, recipes as R, verify

ROOT = Path(__file__).resolve().parents[1]
CANDIDATES = ROOT / "android" / "src" / "io" / "github" / "daemonddev" / "charmera" / "Candidates.java"
DOC = R.load()


def env_dump(var):
    p = os.environ.get(var)
    return Path(p).read_bytes() if p and Path(p).is_file() else None


DUMPS = {v: env_dump(v) for v in ("CHARMERA_STOCK_IMAGE", "CHARMERA_FACTORY_DUMP", "CHARMERA_FILM_MODS_RESTORE")}


def test_the_table_is_complete_and_consistent():
    ids = {m["id"] for m in DOC["mods"]}
    for m in DOC["mods"]:
        assert m["id"] == f"{m['base']}/{m['name']}" and m["status"] in ("tested", "experimental")
        assert set(m["conflicts"]) <= ids and set(m["requires"]) <= ids
        for p in m["patches"]:
            assert re.fullmatch(r"([0-9a-f]{2})+", p["new"]) and re.fullmatch(r"[0-9a-f]{64}", p["old_sha256"])
        for c in m["copies"]:
            assert set(c) == {"from", "to", "len", "old_sha256"}      # a copy carries no bytes, only where they are
    keys = {(s["base"], tuple(s["mods"])) for s in DOC["sets"]}
    assert len(keys) == len(DOC["sets"])
    for b in DOC["builds"]:
        assert (b["base"], tuple(b["mods"])) in keys
        assert re.fullmatch(r"JH_5408\d{12}_[0-9A-F]{8}\.bin", b["name"]) and re.fullmatch(r"[0-9a-f]{64}", b["sha256"])
    for s in DOC["sets"]:                                            # every set from every dump of its base
        for d in DOC["dumps"]:
            if d["build"] == s["base"]:
                assert any(b["dump"] == d["id"] and b["base"] == s["base"] and b["mods"] == s["mods"]
                           for b in DOC["builds"]), (s["label"], d["id"])


def test_no_two_mods_of_a_base_touch_the_same_byte():
    def spans(m):
        out = set()
        for c in m["copies"]:
            out |= set(range(c["to"], c["to"] + c["len"]))
        for p in m["patches"]:
            out |= set(range(p["file_offset"], p["file_offset"] + len(p["new"]) // 2))
        return out
    mods = DOC["mods"]
    for i, a in enumerate(mods):
        for b in mods[i + 1:]:
            if a["base"] == b["base"] and b["id"] not in a["conflicts"]:
                assert not spans(a) & spans(b), (a["id"], b["id"])


def test_the_files_the_app_pins_are_rows_of_the_table():
    java = CANDIDATES.read_text(encoding="utf-8")
    pairs = re.findall(r'"(JH_5408\d{12}_[0-9A-F]{8}\.bin)", "([0-9a-f]{64})"', java)
    rows = {(b["name"], b["sha256"]) for b in DOC["builds"]}
    withdrawn = {sha for sha in verify.WITHDRAWN}                     # A and B are not recipes
    for name, sha in pairs:
        if sha in withdrawn or sha in verify.CLEAN_RESTORE and (name, sha) not in rows:
            continue                                                # C's own name; its bytes are the clean restore row
        assert (name, sha) in rows, name


def test_the_apps_copy_is_generated_from_this_table():
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "gen_recipes.py"), "java", "--check"],
                       capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr


def test_mods_are_resolved_per_base_with_their_conflicts():
    assert R.resolve("v012", ["exif", "volume-led"], DOC) == ("v012/volume-led", "v012/exif")   # the table's order
    with pytest.raises(R.RecipeError, match="no mod ov9732-port for base v012"):
        R.resolve("v012", ["ov9732-port"], DOC)
    with pytest.raises(R.RecipeError, match="cannot go with"):
        R.resolve("v006", ["sensor-probe", "ov9732-port"], DOC)
    assert R.resolve("v006", [""], DOC) == ()


def test_an_unknown_dump_is_refused_by_its_hash():
    with pytest.raises(R.RecipeError, match="not a dump the recipes start from"):
        R.make(b"\xff" * R.DUMP_SIZE, [], DOC)


def test_a_mod_refuses_bytes_it_was_not_written_for():
    mod = next(m for m in DOC["mods"] if m["id"] == "v012/volume-led")
    out = bytearray(R.UPDATE_SIZE)                                  # zeros: not the factory build's bytes
    with pytest.raises(R.RecipeError, match="are not the ones this mod replaces"):
        R.apply(out, bytes(R.DUMP_SIZE), mod)


def test_build_lists_every_base(capsys):
    args = type("A", (), {"list": True})
    assert build_cmd.handler(args) == 0
    out = capsys.readouterr().out
    for base in ("v006", "v008_test", "v012"):
        assert f"base {base}" in out
    assert "[experimental]" in out and "--mods volume-led,exif" in out


@pytest.mark.parametrize("var", list(DUMPS))
def test_every_combination_rebuilds_the_reviewed_file(var):
    dump = DUMPS[var]
    if dump is None:
        pytest.skip(f"set {var} to a firmware image to rebuild its base's combinations")
    entry = R.dump_for(dump, DOC)
    rows = [b for b in DOC["builds"] if b["dump"] == entry["id"]]
    assert rows
    for b in rows:
        made = R.make(dump, [i.split("/")[-1] for i in b["mods"]], DOC)
        assert (made.name, made.sha256) == (b["name"], b["sha256"])
        if entry["prep"] != "as-is":
            assert len(made.data) == R.UPDATE_SIZE
        assert made.data[:0x200] == dump[:0x200]                     # no mod touches the boot header


@pytest.mark.parametrize("var", ["CHARMERA_STOCK_IMAGE", "CHARMERA_FACTORY_DUMP"])
def test_build_writes_the_file_and_verify_names_it(var, tmp_path, capsys):
    dump = DUMPS[var]
    if dump is None:
        pytest.skip(f"set {var}")
    entry = R.dump_for(dump, DOC)
    src = tmp_path / "dump.bin"
    src.write_bytes(dump)
    mods = "volume-led,exif" if entry["build"] == "v012" else "ov9732-port,volume-led"
    args = type("A", (), {"list": False, "dump": str(src), "download": False, "base": None, "mods": mods,
                          "out": str(tmp_path / "out"), "no_verify": False})
    assert build_cmd.handler(args) == 0
    out = capsys.readouterr().out
    files = list((tmp_path / "out").iterdir())
    assert len(files) == 1 and R.reviewed(hashlib.sha256(files[0].read_bytes()).hexdigest(), DOC)
    assert "verify 0 failure(s)" in out
    by = {c.name: c for c in verify.verify_update(files[0], files[0].read_bytes())}
    assert by["reviewed"].ok and by["reviewed"].severity == "info"
    assert not any(not c.ok and c.severity == "fail" for c in by.values())


# --- verify and the reviewed builds: the sensor gate stays -------------------------------------------------------------

def test_reviewed_build_never_eases_the_donor_route_or_the_vector_table(monkeypatch):
    row = {"base": "v012", "mods": ["v012/volume-led"], "dump": "v012-clean", "name": "JH_x.bin", "sha256": "0" * 64}
    monkeypatch.setattr(R, "reviewed", lambda sha, recipes=None: row)
    fails = [verify.Check(n, False, f"{n} differs (refusing; no flag accepts it)")
             for n in ("series", "software-region", "from-donor", "vector-table", "size-2mib", "checksum")]
    out = {c.name: c for c in verify.reviewed_build(b"\0" * 0x200000, list(fails))}
    assert out["series"].ok and out["series"].severity == "warn"
    assert out["software-region"].ok
    for n in ("from-donor", "vector-table", "size-2mib", "checksum"):
        assert not out[n].ok and out[n].severity == "fail", n


def test_a_plain_stock_file_is_eased_too_and_unconfirmed_is_a_warning(monkeypatch):
    row = {"base": "v006", "mods": [], "dump": "v006-clean", "name": "JH_x.bin", "sha256": "0" * 64}
    monkeypatch.setattr(R, "reviewed", lambda sha, recipes=None: row)
    fails = [verify.Check(n, False, "x (refusing; no flag accepts it)") for n in ("sensor-table", "software-region", "series")]
    out = {c.name: c for c in verify.reviewed_build(b"\0" * 0x200000, list(fails))}
    for n in ("sensor-table", "software-region", "series"):
        assert out[n].ok and out[n].severity == "warn", n
    assert out["unconfirmed"].severity == "warn" and "charmera identify" in out["unconfirmed"].detail
    out = {c.name: c for c in verify.reviewed_build(b"\0" * 0x200000, list(fails), stock=b"\0" * 0x200000)}
    assert "unconfirmed" not in out


def test_a_reviewed_build_never_asks_for_allow_modified(monkeypatch):
    """A reviewed file needs no --allow-modified: neither verify's FAIL hint nor stage's "accepted because of" survives."""
    row = {"base": "v012", "mods": ["v012/volume-led"], "dump": "v012-clean", "name": "JH_x.bin", "sha256": "0" * 64}
    monkeypatch.setattr(R, "reviewed", lambda sha, recipes=None: row)
    for allow in (False, True):
        checks = [verify.Check("software-region", allow, "the software region differs from v012; give --stock <x> to see which 64 KiB "
                               "blocks" + (verify.ALLOW_MODIFIED_ACCEPTED if allow else verify.ALLOW_MODIFIED_HINTS[1]),
                               "warn" if allow else "fail"),
                  verify.Check("software-region", allow, "bytes differ" + (verify.ALLOW_MODIFIED_ACCEPTED if allow
                                                                         else verify.ALLOW_MODIFIED_HINTS[0]),
                               "warn" if allow else "fail")]
        for c in verify.reviewed_build(b"\0" * 0x200000, checks):
            assert "--allow-modified" not in c.detail, c.detail
            if c.name == "software-region":
                assert c.detail.endswith(" (a reviewed build: this is expected)")


def test_reviewed_files_from_real_dumps_never_mention_allow_modified():
    for var in ("CHARMERA_STOCK_IMAGE", "CHARMERA_FACTORY_DUMP"):
        dump = DUMPS[var]
        if dump is None:
            continue
        mods = ["volume-led", "exif"] if R.dump_for(dump, DOC)["build"] == "v012" else ["ov9732-port", "volume-led"]
        built = R.make(dump, mods, DOC)
        for allow in (False, True):     # verify as typed, and as stage calls it
            for c in verify.verify_update(Path(built.name), built.data, allow_modified=allow):
                assert "--allow-modified" not in c.detail, (var, allow, c.name, c.detail)


def test_sensor_of_tells_the_two_sensors_apart():
    assert verify.sensor_of(b"\0" * 64 + verify.SP140A_INIT) == "SP140A"
    assert verify.sensor_of(b"\0" * 64 + verify.OV9732_SCCB) == "OV9732"
    assert verify.sensor_of(verify.SP140A_INIT + verify.OV9732_SCCB) == "OV9732"   # v006 with our OV9732 port
    assert verify.sensor_of(b"\0" * 64) is None


def test_the_sensor_gate_on_real_images():
    stock, factory = DUMPS["CHARMERA_STOCK_IMAGE"], DUMPS["CHARMERA_FACTORY_DUMP"]
    if stock is None or factory is None:
        pytest.skip("set CHARMERA_STOCK_IMAGE and CHARMERA_FACTORY_DUMP")
    assert verify.sensor_of(stock) == "SP140A" and verify.sensor_of(factory) == "OV9732"
    v027 = R.make(factory, ["volume-led", "exif", "boot-last-mode", "capture-flash"], DOC)
    by = {c.name: c for c in verify.verify_update(Path(v027.name), v027.data, stock=stock)}
    assert by["wrong-sensor"].severity == "warn" and "drives the OV9732" in by["wrong-sensor"].detail
    assert not any(not c.ok and c.severity == "fail" for c in by.values())
    by = {c.name: c for c in verify.verify_update(Path(v027.name), v027.data, stock=factory)}
    assert "wrong-sensor" not in by and by["reviewed"].ok
    restore = R.make(stock, [], DOC)                                 # the stock v006 restore file, the bad-flash path
    by = {c.name: c for c in verify.verify_update(Path(restore.name), restore.data, stock=factory)}
    assert by["wrong-sensor"].severity == "warn" and "drives the SP140A" in by["wrong-sensor"].detail
    assert not any(not c.ok and c.severity == "fail" for c in by.values())   # warned, not refused: the owner decides


# --- names: a build is its base and the mods on it, never a number --------------------------------------------------

def test_every_set_is_named_by_its_base_and_mods():
    for s in DOC["sets"]:
        assert s["label"] == R.firmware_name(s["base"], s["mods"], DOC), s["label"]
        assert "v0." not in s["label"] and " + " not in s["label"]
    for m in DOC["mods"]:
        assert m["phrase"] and "v0." not in m["phrase"]
    assert [b["id"] for b in DOC["bases"]] == ["v012", "v006", "v008_test"]


def test_the_name_rule():
    name = lambda base, *mods: R.firmware_name(base, list(mods), DOC)     # noqa: E731
    assert name("v012", "v012/capture-flash", "v012/exif", "v012/volume-led", "v012/boot-last-mode") == \
        "v012 with Volume and LED rows, EXIF dates, boot to last mode and capture flash"   # the table's order
    assert name("v012", "v012/exif", "v012/volume-led") == "v012 with Volume and LED rows and EXIF dates"
    assert name("v012", "v012/exif") == "v012 with EXIF dates"
    assert name("v006", "v006/ov9732-port") == "v006 with the OV9732 port"
    assert name("v006", "v006/volume-led", "v006/ov9732-port") == "v006 with the OV9732 port and Volume and LED rows"
    assert name("v006", "v006/sensor-probe") == "v006 with the sensor-ID probe"
    assert name("v012") == "stock v012 (the factory firmware, unchanged)"
    assert name("v006") == "stock v006 (the stock restore file)"
    assert name("v008_test") == "stock v008_test (the charmera-film-mods restore file, unchanged under a new file name)"
    assert name("v099") == "stock v099"
    assert name("v012", "v012/nope", "v012/exif") == "v012 with EXIF dates and v012/nope"   # an unknown id: itself, last


def test_only_the_five_numbered_builds_keep_their_old_name():
    old = {(s["base"], tuple(s["mods"])): s["old_name"] for s in DOC["sets"] if "old_name" in s}
    assert old == {
        ("v006", ("v006/ov9732-port",)): "v0.23",
        ("v006", ("v006/ov9732-port", "v006/volume-led")): "v0.24",
        ("v012", ("v012/volume-led",)): "v0.25",
        ("v012", ("v012/volume-led", "v012/exif")): "v0.26",
        ("v012", ("v012/volume-led", "v012/exif", "v012/boot-last-mode", "v012/capture-flash")): "v0.27",
    }
    assert R.old_name("v012", ("v012/volume-led", "v012/exif"), DOC) == "v0.26"
    assert R.old_name("v012", ("v012/exif",), DOC) is None
    # any order of the same mods is the same build, as in the app's Recipe.Selection
    assert R.old_name("v012", ["v012/exif", "v012/volume-led"], DOC) == "v0.26"
    assert R.old_name("v006", ["v006/volume-led", "v006/ov9732-port"], DOC) == "v0.24"
    assert R.set_for("v012", ["v012/exif", "v012/volume-led"], DOC)["old_name"] == "v0.26"


def test_build_list_names_each_set_and_maps_the_old_numbers(capsys):
    args = type("A", (), {"list": True})
    assert build_cmd.handler(args) == 0
    out = capsys.readouterr().out
    lines = [l for l in out.splitlines() if l.startswith("  set ")]
    assert any(l.endswith("v012 with Volume and LED rows, EXIF dates, boot to last mode and capture flash  (older "
                          "versions of the app called this v0.27)") for l in lines)
    assert sum("older versions of the app called this" in l for l in lines) == 5
    assert not any("v0." in l.split("(older versions")[0] for l in lines)
