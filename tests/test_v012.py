"""v012, the factory build of OV9732 cameras: recognised as Charmera firmware, by its own OV9732 driver.

The synthetic tests always run: they place a stand-in for v012's OV9732 program (the bus context and a 4-byte-entry
init table) in a synthetic image without the SP140A table. The rest run on real images: CHARMERA_FACTORY_DUMP (a v012
dump, the cleaned public copy or an owner's own read) and CHARMERA_STOCK_IMAGE (a v006 dump). An owner's own read is
stood in for by a copy of that dump with bytes changed in the settings store and the photo area, written to a temp
folder; no firmware bytes are kept anywhere else.
"""
import argparse
import hashlib
import json
import os
import struct
from pathlib import Path

import pytest

from charmera_toolkit import build_cmd, flash, identify, image, inspect_cmd, pack, recipes, sensor_read, stage, verify
from test_core import SENSOR_RESET_OFFSET, make_record, synthetic_build, synthetic_image  # noqa: F401 (a fixture)

FACTORY = Path(os.environ.get("CHARMERA_FACTORY_DUMP", ""))
STOCK = Path(os.environ.get("CHARMERA_STOCK_IMAGE", ""))
needs_factory = pytest.mark.skipif(not FACTORY.is_file(), reason="set CHARMERA_FACTORY_DUMP to a v012 dump")
needs_stock = pytest.mark.skipif(not STOCK.is_file(), reason="set CHARMERA_STOCK_IMAGE to a v006 dump")
needs_both = pytest.mark.skipif(not (FACTORY.is_file() and STOCK.is_file()),
                                reason="set CHARMERA_FACTORY_DUMP (v012) and CHARMERA_STOCK_IMAGE (v006)")
CLEAN_V012 = "63e396846f949600c2c1881920860821f2fcd70a70ac79ca54eebaee4a0ccb1c"

# Where the stand-in OV9732 program goes in a synthetic image (code area, 4-byte aligned), and what it holds.
CTX_AT, INIT_AT, LITERAL_AT = 0x108374, 0xF5D3C, 0x7AF04
SYNTHETIC_OV_INIT = (struct.pack("<HH", 0x0103, 1) + b"".join(struct.pack("<HH", 0x3000 + i, i) for i in range(20))
                     + struct.pack("<HH", 0xFFFF, 0xFF))


def ov9732_like(img: bytes) -> bytes:
    """A synthetic image whose sensor set-up is v012's kind: no SP140A reset table, the OV9732 bus context, and a
    referenced init table of (u16 register, u16 value) entries from a software reset to register 0xFFFF."""
    out = bytearray(img)
    out[SENSOR_RESET_OFFSET: SENSOR_RESET_OFFSET + 6] = bytes.fromhex("fe0080ffffff")
    out[CTX_AT: CTX_AT + len(image.OV9732_SCCB)] = image.OV9732_SCCB
    out[INIT_AT: INIT_AT + len(SYNTHETIC_OV_INIT)] = SYNTHETIC_OV_INIT
    out[LITERAL_AT: LITERAL_AT + 4] = struct.pack("<I", INIT_AT - image.VA_OFFSET)
    return bytes(out)


def own_read(dump: bytes) -> bytes:
    """A stand-in for an owner's own read of the same build: other settings and another stored photo."""
    out = bytearray(dump)
    out[0x170300] ^= 0x5A                      # inside the settings store
    out[0x1A0000: 0x1A0010] = b"own camera photo"
    return bytes(out)


def named(tmp_path, payload, stamp="202611041200"):
    p = tmp_path / pack.build_update_name(payload, stamp).filename
    p.write_bytes(payload)
    return p


def checks(path, data, **kw):
    return {c.name: c for c in verify.verify_update(path, data, **kw)}


# --- synthetic ----------------------------------------------------------------------------------------------------


def test_an_ov9732_program_is_a_sensor_mark():
    img = ov9732_like(synthetic_image([make_record()]))
    marks = image.find_marks(img)
    assert marks.sensor is not None and marks.sensor.sensor == "OV9732" and marks.complete
    assert marks.sensor.init == (INIT_AT, INIT_AT + len(SYNTHETIC_OV_INIT)) and marks.sensor.pairs == 21
    assert marks.sensor.referenced_from == LITERAL_AT and marks.sensor.bus_address == 0x6C
    assert marks.sensor.sha256 == hashlib.sha256(SYNTHETIC_OV_INIT).hexdigest()
    assert marks.sensor_programs == ()                      # SP140A programs only: v012's is not "several programs"
    assert image.detect_build(img).charmera
    info = flash.analyze_image(img)
    assert info["build_marks_ok"] and info["build_marks"]["sensor_table"]["sensor"] == "OV9732"
    # without the bus context there is no OV9732 program, and an SP140A table always wins over it
    no_ctx = bytearray(img)
    no_ctx[CTX_AT: CTX_AT + 10] = b"\xff" * 10
    assert image.find_marks(bytes(no_ctx)).sensor is None
    both = bytearray(synthetic_image([make_record()]))
    both[CTX_AT: CTX_AT + 10] = image.OV9732_SCCB
    both[INIT_AT: INIT_AT + len(SYNTHETIC_OV_INIT)] = SYNTHETIC_OV_INIT
    assert image.find_marks(bytes(both)).sensor.sensor == "SP140A"
    # a table that does not end with register 0xFFFF, or is too short, is not one
    cut = bytearray(img)
    cut[INIT_AT + 4 * 21: INIT_AT + 4 * 22] = struct.pack("<HH", 0x9000, 0)
    assert image.find_marks(bytes(cut)).sensor is None


def test_a_file_for_the_other_sensor_than_the_backup_fails_and_says_which(tmp_path):
    sp140a = synthetic_image([make_record()])
    ov = ov9732_like(sp140a)
    payload = pack.prepare_payload(sp140a)
    by = checks(named(tmp_path, payload), payload, stock=ov, allow_modified=True)
    assert not by["sensor-table"].ok and by["sensor-table"].severity == "fail"
    assert ("The file's table is for the SP140A-class sensor, --stock's for the OmniVision OV9732."
            in by["sensor-table"].detail)
    payload = pack.prepare_payload(ov)
    by = checks(named(tmp_path, payload), payload, stock=sp140a, allow_modified=True)
    assert not by["sensor-table"].ok
    assert "The file's table is for the OmniVision OV9732, --stock's for the SP140A-class sensor." in by["sensor-table"].detail


def test_restore_takes_an_image_with_an_ov9732_program(tmp_path, capsys, synthetic_build):
    img = tmp_path / "ov.bin"
    img.write_bytes(ov9732_like(synthetic_image([make_record()])))
    args = argparse.Namespace(image=str(img), out=str(tmp_path / "r"), flashrom=str(tmp_path / "no-flashrom"),
                              programmer="ch341a_spi", chip="SFDP-capable chip", label="before-restore",
                              allow_unknown_build=False)
    assert flash.cmd_restore(args) == 1                     # stops at flashrom, which is not there, not at the gate
    out, err = capsys.readouterr()
    assert "refusing" not in err and "flashrom" in err
    assert "(v012's OV9732 table)" in out and "multimeter" in out


def test_restore_refusal_names_the_known_builds(tmp_path, capsys):
    img = bytearray(synthetic_image([make_record()]))
    img[SENSOR_RESET_OFFSET: SENSOR_RESET_OFFSET + 6] = bytes.fromhex("fe0080ffffff")
    p = tmp_path / "x.bin"
    p.write_bytes(bytes(img))
    args = argparse.Namespace(image=str(p), out=str(tmp_path / "r"), flashrom=None, programmer="ch341a_spi",
                              chip="SFDP-capable chip", label="before-restore", allow_unknown_build=False)
    assert flash.cmd_restore(args) == 1
    err = " ".join(capsys.readouterr().err.split())
    assert "sensor table missing" in err and "It is not v006, v008_test or v012" in err
    assert "this camera's own." not in err


def test_the_checklist_asks_for_a_multimeter_check():
    text = " ".join(" ".join(flash.CHECKLIST).split())
    assert ("you measured the clip with a multimeter, with the programmer plugged in alone and the clip on nothing: pin 8 "
            "(VCC) and pin 1 (CS) against pin 4 (GND) both read about 3.3 V. If either reads near 5 V, stop.") in text


def test_messages_name_the_next_command(capsys):
    assert "charmera build --base v006 --mods sensor-probe --download --out out/" in sensor_read.NO_FILE
    assert "sensor-probe" in {m["name"] for m in recipes.mods_for("v006")}
    p = argparse.ArgumentParser()
    sub = p.add_subparsers()
    stage.register(sub)
    assert "as written by build or pack" in " ".join(sub.choices["stage"].format_help().split())
    assert identify.NEXT.format(base="v012") == ("charmera build --list, then charmera build --base v012 --mods <mods> "
                                                  "--download --out out/")


# --- real images ----------------------------------------------------------------------------------------------------


@needs_factory
def test_inspect_names_v012_and_an_own_read(tmp_path, capsys):
    dump = FACTORY.read_bytes()
    for data in (dump, own_read(dump)):
        p = tmp_path / "read.bin"
        p.write_bytes(data)
        assert inspect_cmd.print_report(p, data) == 0
        out = capsys.readouterr().out
        assert "build:           v012  (the factory build v012 of OV9732 cameras" in out
        assert "167 register writes at 0xf5d3c" in out and "v012's table for the OmniVision OV9732" in out
        assert image.chip_read_problem(data) is None
        # the code and software hashes are v012's own, so an exact v012 image has no "no" line
        checks_ = image.detect_build(data).checks
        assert checks_["code_sha256"] and checks_["software_sha256"] and all(checks_.values())
        assert "  no" not in "".join(l for l in out.splitlines() if l.lstrip().startswith("check"))
        assert inspect_cmd.handler(argparse.Namespace(file=str(p), json=True, resources=False, settings=False)) == 0
        marks = json.loads(capsys.readouterr().out)["marks"]
        assert marks["sensor_table"]["sensor"] == "OV9732" and marks["sensor_table"]["drives"] == "OV9732"
        assert [s["sensor"] for s in marks["sensor_programs"]] == ["OV9732"] and marks["sensor_programs"][0]["pairs"] == 167
    changed = bytearray(dump)
    changed[0x60000] ^= 1                                   # a changed program: v012 with changes
    assert image.detect_build(bytes(changed)).identity == "v012-modified"
    if hashlib.sha256(dump).hexdigest() == CLEAN_V012:
        assert image.public_dump_name(dump) and flash.PUBLIC_DUMPS[CLEAN_V012] == flash.CLEAN_V012


@needs_factory
def test_pack_takes_an_own_v012_read(tmp_path, capsys):
    p = tmp_path / "read.bin"
    p.write_bytes(own_read(FACTORY.read_bytes()))
    args = argparse.Namespace(input=str(p), official_size=False, blank_settings=False, blank_photos=False,
                              factory_settings=False, allow_unknown_build=False, stamp="202611180000", out=str(tmp_path / "o"),
                              overwrite=False, gate=False)
    assert pack.handler(args) == 0
    out = capsys.readouterr().out
    assert "build:           v012" in out and "(v012's, for the OV9732)" in out and "cannot tell that it is" not in out


@needs_factory
def test_restore_takes_the_v012_copy_and_an_own_read(tmp_path, capsys):
    dump = FACTORY.read_bytes()
    for i, data in enumerate((dump, own_read(dump))):
        p = tmp_path / f"img{i}.bin"
        p.write_bytes(data)
        args = argparse.Namespace(image=str(p), out=str(tmp_path / "r"), flashrom=str(tmp_path / "no-flashrom"),
                                  programmer="ch341a_spi", chip="SFDP-capable chip", label="before-restore",
                                  allow_unknown_build=False)
        assert flash.cmd_restore(args) == 1                 # past the gate, stopped at the missing flashrom
        out, err = capsys.readouterr()
        assert "refusing" not in err and "build:    v012 (" in out
        if hashlib.sha256(data).hexdigest() == CLEAN_V012:
            assert "It fits a camera with" in " ".join(out.split())


@needs_factory
def test_build_from_an_own_v012_read_is_not_a_reviewed_file(tmp_path, capsys):
    read = tmp_path / "my-read.bin"
    read.write_bytes(own_read(FACTORY.read_bytes()))
    made = recipes.make(read.read_bytes(), ["volume-led", "exif"], own=True)
    assert not made.reviewed and made.dump_id == "v012-own-read" and made.base == "v012"
    assert recipes.reviewed(made.sha256) is None
    with pytest.raises(recipes.RecipeError, match="not a dump the recipes start from"):
        recipes.make(read.read_bytes(), ["volume-led"])     # without own, only listed dumps
    args = argparse.Namespace(list=False, dump=str(read), download=False, base=None, mods="volume-led,exif",
                              out=str(tmp_path / "out"), no_verify=False)
    assert build_cmd.handler(args) == 0
    out = capsys.readouterr().out
    assert "(NOT a known file)" in out and "built from your own read" in out
    assert "your own read of a v012 chip" in out and "verify 0 failure(s)" in out
    assert "--allow-modified" in out and f"--stock {build_cmd.quote(str(read))}" in out
    f = tmp_path / "out" / made.name
    assert f.read_bytes() == made.data
    # verify and stage take it as any file made from the owner's own read: warnings, no refusal
    by = checks(f, made.data, stock=read.read_bytes(), allow_modified=True)
    assert all(c.ok or c.severity != "fail" for c in by.values())
    assert "reviewed" not in by and by["sensor-table"].ok and by["vector-table"].ok
    card = tmp_path / "card"
    (card / "DCIM").mkdir(parents=True)
    p = argparse.ArgumentParser()
    stage.register(p.add_subparsers(dest="command"))
    a = p.parse_args(["stage", str(f), "--card", str(card), "--stock", str(read), stage.OVERRIDE_FLAG, "--plan-only"])
    assert a.func(a) == 0
    assert "plan only: nothing written." in capsys.readouterr().out


@needs_factory
def test_build_refuses_an_own_read_of_a_changed_firmware(tmp_path):
    changed = bytearray(own_read(FACTORY.read_bytes()))
    changed[0x60000] ^= 1
    with pytest.raises(recipes.RecipeError, match="not stock v006 or v012 byte for byte"):
        recipes.make(bytes(changed), ["volume-led"], own=True)
    with pytest.raises(recipes.RecipeError, match="not a read of a v006 or v012 chip") as exc:
        recipes.make(bytes(changed[:0x1E0000]), [], own=True)
    assert str(exc.value).endswith("build from the public copy instead (--base v006 or v012 with --download)")


def test_build_says_what_to_do_with_a_read_of_stock_v008_test(monkeypatch):
    """v008_test is a base with a public file, so its read is not "a camera flashed with a mod"."""
    monkeypatch.setattr(image, "chip_read_problem", lambda data, need_sensor_table=True: None)
    monkeypatch.setattr(image, "match_known_build", lambda data: image.FILM_MODS_BUILD)
    with pytest.raises(recipes.RecipeError) as exc:
        recipes.own_read(b"\0" * 0x200000)
    assert "this read is stock v008_test" in str(exc.value) and "--base v008_test --download" in str(exc.value)
    assert "with a mod written to it" not in str(exc.value)


@needs_factory
def test_an_own_v012_read_keeps_its_photos_and_directory(tmp_path, capsys):
    """An own read is only cut to the update file's length: stored photos and their directory stay together, as for
    v006. (Blanking the photos alone left a directory that listed a photo no longer there.)"""
    read = bytearray(FACTORY.read_bytes())
    jpeg = b"\xff\xd8\xff\xe0" + b"J" * 5000
    read[0x180000: 0x180000 + len(jpeg)] = jpeg
    read[0x17F000: 0x17F00C] = struct.pack("<III", 1, 1, len(jpeg))
    assert recipes.own_read(bytes(read))["prep"] == "truncate"
    made = recipes.make(bytes(read), ["volume-led"], own=True)
    assert not made.reviewed
    assert made.data[0x180000: 0x180000 + len(jpeg)] == jpeg
    assert made.data[0x17F000: 0x180000] == bytes(read[0x17F000: 0x180000])
    assert made.data[0x170000: 0x1E0000] == bytes(read[0x170000: 0x1E0000])
    src = tmp_path / "read.bin"
    src.write_bytes(bytes(read))
    args = argparse.Namespace(list=False, dump=str(src), download=False, base=None, mods="volume-led",
                              out=str(tmp_path / "out"), no_verify=True)
    assert build_cmd.handler(args) == 0
    assert "it carries your camera's settings and stored photos" in " ".join(capsys.readouterr().out.split())


@needs_factory
def test_a_listed_dump_still_builds_the_reviewed_file(tmp_path, capsys):
    args = argparse.Namespace(list=False, dump=str(FACTORY), download=False, base=None, mods="", out=str(tmp_path / "o"),
                              no_verify=False)
    assert build_cmd.handler(args) == 0
    out = capsys.readouterr().out
    assert "(the known file: recipes.json)" in out and "NOT a known file" not in out
    assert "no backup: charmera verify" in out and "                  charmera stage" in out


@needs_both
def test_verify_compares_the_sensor_family_with_a_v012_backup(tmp_path):
    v012 = own_read(FACTORY.read_bytes())
    v006 = STOCK.read_bytes()
    # a reviewed v006 file against a v012 backup: a loud warning, not a refusal (it is undone from the card)
    stock = recipes.make(v006, [])
    by = checks(Path(stock.name), stock.data, stock=v012)
    assert by["wrong-sensor"].severity == "warn" and "--stock is firmware for the OV9732" in by["wrong-sensor"].detail
    assert by["sensor-table"].severity == "warn" and "The file is firmware for the SP140A, --stock for the OV9732." in by["sensor-table"].detail
    assert not any(c.severity == "fail" and not c.ok for c in by.values())
    # an unreviewed v006 file (from an own read) against the same backup: refused, and it says why
    mine = recipes.make(own_read(v006), [], own=True)
    assert not mine.reviewed
    by = checks(Path(mine.name), mine.data, stock=v012)
    assert not by["sensor-table"].ok and by["sensor-table"].severity == "fail"
    assert "The file is firmware for the SP140A, --stock for the OV9732." in by["sensor-table"].detail
    # v0.24 (v006 with our OV9732 port) against a v012 backup: both drive the OV9732, set up differently
    port = recipes.make(v006, ["ov9732-port", "volume-led"])
    by = checks(Path(port.name), port.data, stock=v012)
    assert "Both are firmware for the OV9732, set up in different ways." in by["sensor-table"].detail
    assert "wrong-sensor" not in by


@needs_both
def test_build_from_an_own_v006_read(tmp_path):
    made = recipes.make(own_read(STOCK.read_bytes()), ["volume-led"], own=True)
    assert not made.reviewed and made.base == "v006" and made.dump_id == "v006-own-read"
    checks_ = checks(Path(made.name), made.data, allow_modified=True)
    assert not any(c.severity == "fail" and not c.ok for c in checks_.values())


def test_verify_says_how_a_wrong_sensor_flash_is_undone(tmp_path, capsys, synthetic_build):
    payload = pack.prepare_payload(synthetic_image([make_record()]))
    p = named(tmp_path, payload)
    args = argparse.Namespace(file=str(p), stock=None, donor=None, allow_modified=True, allow_unknown_build=False,
                              allow_odd_size=False)
    assert verify.handler(args) == 0
    out = " ".join(capsys.readouterr().out.split())
    assert ("A file for the other sensor still boots, and writing the stock build of the right base from the card undoes "
            "it (charmera build --base v006 --download --out out/ for an SP140A camera, charmera build --base v012 --download "
            "--out out/ for an OV9732 one). A write that is cut off, or firmware that won't boot, needs a "
            "chip programmer.") in out
    assert "the only way back" not in out and "cannot be undone" not in out


def test_release_notes_say_what_the_app_does_now():
    wf = (Path(__file__).resolve().parent.parent / ".github" / "workflows" / "android.yml").read_text(encoding="utf-8")
    notes = next(line for line in wf.splitlines() if line.strip().startswith('NOTES="'))
    assert "writes nothing without a backup" not in notes
    for part in ("It identifies the firmware the camera runs from its photos",
                 "(the stock restore file, the sensor-ID probe, or a base with the mods you tick)",
                 "writes it to the card over the camera's USB-C cable", "It works without a backup",
                 "warns when nothing confirms what the camera runs",
                 "A backup is the only way back to exactly what the camera ran",
                 "a camera that won't boot can still take the public stock file for its sensor", "(Android 7.0 or later)",
                 "android/README.md"):
        assert part in notes, part
    assert "\u2014" not in notes and "\u2013" not in notes


# --- several OV9732 programs, and the sensor family behind the same table -------------------------------------------

SECOND_OV_AT, SECOND_LITERAL_AT = 0xF6000, 0x7C100


def with_a_second_ov9732_program(img: bytes, same: bool = False) -> bytes:
    """The OV9732-like image with a second init table the code refers to: other contents, or the same ones."""
    table = bytearray(SYNTHETIC_OV_INIT)
    if not same:
        table[6] ^= 0x01                                   # another value in the second write
    out = bytearray(img)
    out[SECOND_OV_AT: SECOND_OV_AT + len(table)] = table
    out[SECOND_LITERAL_AT: SECOND_LITERAL_AT + 4] = struct.pack("<I", SECOND_OV_AT - image.VA_OFFSET)
    return bytes(out)


def test_several_ov9732_programs_are_refused_as_sp140a_ones_are(tmp_path, capsys, synthetic_build):
    one = ov9732_like(synthetic_image([make_record()]))
    two = with_a_second_ov9732_program(one)
    marks = image.find_marks(two)
    assert marks.sensor_programs == () and len(marks.ov9732_programs) == 2
    assert len(marks.several_sensor_programs) == 2 and marks.sensor.init[0] == INIT_AT     # the pick is unchanged
    assert image.find_marks(one).several_sensor_programs == [] and len(image.find_marks(one).ov9732_programs) == 1
    assert image.find_marks(with_a_second_ov9732_program(one, same=True)).several_sensor_programs == []
    payload = pack.prepare_payload(two)
    for kw in ({}, {"stock": one}):
        s = checks(named(tmp_path, payload), payload, allow_modified=True, allow_unknown_build=True, **kw)["sensor-table"]
        assert not s.ok and s.severity == "fail"
        assert "several sensor programs: the code refers to 2 image-sensor set-up tables with different contents" in s.detail
        assert "(OV9732)" in s.detail and "referred to from 0x7af04" in s.detail and "referred to from 0x7c100" in s.detail
    good = pack.prepare_payload(one)
    s = checks(named(tmp_path, good), good, stock=two, allow_modified=True)["sensor-table"]
    assert not s.ok and "several sensor programs in --stock" in s.detail
    # restore refuses it the same way, whatever the flags
    p = tmp_path / "two.bin"
    p.write_bytes(two)
    for allow in (False, True):
        args = argparse.Namespace(image=str(p), out=str(tmp_path / "r"), flashrom=str(tmp_path / "no-flashrom"),
                                  programmer="ch341a_spi", chip="SFDP-capable chip", label="before-restore",
                                  allow_unknown_build=allow)
        assert flash.cmd_restore(args) == 2
        err = " ".join(capsys.readouterr().err.split())
        assert "refusing: the code in two.bin refers to 2 image-sensor set-up tables with different contents" in err


def test_the_same_sp140a_table_with_the_ov9732_port_is_another_sensor(tmp_path, synthetic_build):
    """The same SP140A table on both sides, but one side's code carries the OV9732 driver: they drive different
    sensors, so verify refuses it as a different table, and says why, as restore's sensor comparison says DIFFERENT."""
    sp = bytearray(synthetic_image([make_record()]))
    sp[0x100000: 0x100004] = image.SP140A_INIT
    sp = bytes(sp)
    ported = bytearray(sp)
    ported[CTX_AT: CTX_AT + len(image.OV9732_SCCB)] = image.OV9732_SCCB
    ported = bytes(ported)
    assert image.sensor_of(sp) == "SP140A" and image.sensor_of(ported) == "OV9732"
    assert image.find_marks(ported).sensor.sha256 == image.find_marks(sp).sensor.sha256
    for file, stock, ours, theirs in ((ported, sp, "OV9732", "SP140A"), (sp, ported, "SP140A", "OV9732")):
        payload = pack.prepare_payload(file)
        s = checks(named(tmp_path, payload), payload, stock=stock, allow_modified=True)["sensor-table"]
        assert s.ok and s.severity == "warn"     # boots, and the card undoes it: a warning, not a refusal
        assert s.detail.startswith("the image-sensor set-up table is the same as in --stock (")
        assert f"but the file is firmware for the {ours} and --stock for the {theirs}: one of them also carries" in s.detail
        assert "refusing" not in s.detail
        table = flash.build_marks_found(file)["sensor_table"]
        chip = flash.build_marks_found(stock)["sensor_table"]
        assert flash._drives(table) != flash._drives(chip)
    # the same file against itself is still the same table and the same sensor
    payload = pack.prepare_payload(ported)
    assert checks(named(tmp_path, payload), payload, stock=ported)["sensor-table"].ok


@needs_stock
def test_a_reviewed_ported_build_against_an_sp140a_backup_warns(tmp_path):
    """v0.23 (v006 with our OV9732 port) keeps v006's table: against a v006 backup that's the same table for another
    sensor. Both the reviewed file and one made from an own read get a warning: an OV9732 camera that was flashed
    with v006 has exactly that own read, and v0.24 from it is the fix."""
    v006 = STOCK.read_bytes()
    port = recipes.make(v006, ["ov9732-port"])
    by = checks(Path(port.name), port.data, stock=v006)
    assert by["sensor-table"].severity == "warn"
    assert "but the file is firmware for the OV9732 and --stock for the SP140A" in by["sensor-table"].detail
    assert by["wrong-sensor"].severity == "warn"
    assert not any(c.severity == "fail" and not c.ok for c in by.values())
    mine = recipes.make(own_read(v006), ["ov9732-port"], own=True)
    by = checks(Path(mine.name), mine.data, stock=v006, allow_modified=True)
    assert by["sensor-table"].ok and by["sensor-table"].severity == "warn"
    assert "but the file is firmware for the OV9732 and --stock for the SP140A" in by["sensor-table"].detail
    assert not any(c.severity == "fail" and not c.ok for c in by.values())


@needs_factory
def test_v012_holds_one_ov9732_program():
    marks = image.find_marks(FACTORY.read_bytes())
    assert len(marks.ov9732_programs) >= 1 and marks.several_sensor_programs == []
    assert marks.sensor.sha256 == image.V012_BUILD.sensor_table_sha256


def test_the_way_back_commands_work():
    """Every `charmera build` command the way-back text gives parses, with --download, which build needs without --dump."""
    texts = [verify.WAY_BACK, (Path(__file__).resolve().parent.parent / "charmera_toolkit" / "stage.py").read_text(encoding="utf-8"),
             (Path(__file__).resolve().parent.parent / "charmera_toolkit" / "verify.py").read_text(encoding="utf-8")]
    for doc in ("02-RECOVERY.md", "04-STAGE.md"):
        texts.append((Path(__file__).resolve().parent.parent / "docs" / doc).read_text(encoding="utf-8"))
    from charmera_toolkit import cli
    import re
    found = 0
    for text in texts:
        flat_text = " ".join(text.replace('")', " ").replace('print("', " ").replace("`", " ").split())
        assert "--base v006 or --base v012" not in flat_text and "charmera build --base v012 for" not in flat_text
        for m in re.finditer(r"charmera build --base (v006|v012) --download --out out/", flat_text):
            found += 1
            args = cli.build_parser().parse_args(m.group(0).split()[1:])
            assert args.download and args.base == m.group(1) and args.out == "out/"
    assert found >= 8
