"""Flash-path checks against the real stock dump: the withdrawn candidates still reproduce and are refused by their
hash, and the damage a cut-off download or a broken mod leaves is refused before it can reach a card.

Runs only when CHARMERA_STOCK_IMAGE points at an original-series dump. The candidate hashes are those of
docs/03-FLASH-CANDIDATES.md and apply to the keychain-camera-plus dump; with another dump only the checks run.
"""
import argparse
import hashlib
from pathlib import Path

import pytest

from charmera_toolkit import checksum, cli, diff_cmd, image, pack, stage, verify

KEYCHAIN_SHA256 = "cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f"
GATE = "--i-understand-this-file-flashes-a-camera"
MOD = "--i-understand-this-is-a-mod"

# (label, mute method or None, pack options, stamp, file name, sha256) from docs/03-FLASH-CANDIDATES.md
CANDIDATES = [
    ("A", "settings", ["--blank-photos"], "202611050001", "JH_5408202611050001_0FB74D14.bin",
     "c4822cd7eed63169d80c5105811ab6492444793fafcc7a96e809869f2e5ccda7"),
    ("B", "both", ["--blank-photos"], "202611050002", "JH_5408202611050002_0FB0BD3C.bin",
     "32a7f54e5090050d099681c33378e3d23822a736279ea78f9f3442a45edc3979"),
    ("C", None, ["--factory-settings", "--blank-photos"], "202611050003", "JH_5408202611050003_0FB74C24.bin",
     "9b8841d7b0674ca047a67b06594b92e67d17c387b6fab11f4b5191abc644c3ec"),
]
RESTORE_NAME = "JH_5408202610261427_0F065D55.bin"   # the published stock restore: the first 0x1E0000 bytes of the keychain dump


def is_keychain(stock_image: bytes) -> bool:
    return hashlib.sha256(stock_image).hexdigest() == KEYCHAIN_SHA256


@pytest.fixture(scope="module")
def built(stock_image_path, tmp_path_factory):
    """The three candidates, built with the recipe through the command line, as {label: path}."""
    work = tmp_path_factory.mktemp("candidates")
    out = {}
    for label, method, opts, stamp, _name, _sha in CANDIDATES:
        src = str(stock_image_path)
        if method:
            src = str(work / f"{label}.bin")
            assert cli.main(["sounds", "mute", str(stock_image_path), "--out", src, "--method", method,
                             "--base-record", "first", MOD]) == 0
        assert cli.main(["pack", "--input", src, "--out", str(work / "out" / label), *opts, "--stamp", stamp, GATE]) == 0
        files = sorted((work / "out" / label).glob("JH_*.bin"))
        assert len(files) == 1
        out[label] = files[0]
    return out


def stage_plan(path: Path, card: Path) -> int:
    p = argparse.ArgumentParser(prog="charmera")
    stage.register(p.add_subparsers(dest="command"))
    args = p.parse_args(["stage", str(path), "--card", str(card), "--plan-only", stage.OVERRIDE_FLAG])
    return args.func(args)


@pytest.fixture
def card(tmp_path):
    root = tmp_path / "card"
    (root / "DCIM").mkdir(parents=True)
    return root


def failures(path: Path, data: bytes, **kw):
    return [c for c in verify.verify_update(path, data, **kw) if not c.ok and c.severity == "fail"]


def test_candidates_reproduce_and_are_refused(stock_image, stock_image_path, built, card, capsys):
    """C, A and B were withdrawn after the hardware test of 2026-11-06. The recipe still makes them, so a copy found
    later can be recognised: verify and stage refuse them by their hash, as the app does."""
    for label, _method, _opts, _stamp, name, sha in CANDIDATES:
        path = built[label]
        data = path.read_bytes()
        withdrawn = hashlib.sha256(data).hexdigest() in verify.WITHDRAWN
        if is_keychain(stock_image):
            assert path.name == name and hashlib.sha256(data).hexdigest() == sha and withdrawn, label
        names = [c.name for c in failures(path, data, stock=stock_image, allow_modified=(label == "B"))]
        assert names == (["withdrawn"] if withdrawn else []), label
        by = {c.name: c for c in verify.verify_update(path, data, stock=stock_image, allow_modified=True)}
        assert by["sensor-table"].ok and by["resource-slots"].ok, label
        assert stage_plan(path, card) == (1 if withdrawn else 0), label
        out = capsys.readouterr().out
        assert ("FAIL withdrawn" in out) if withdrawn else ("plan only: nothing written." in out), label
    assert sorted(p.name for p in card.iterdir()) == ["DCIM"]


def test_published_restore_file_passes(stock_image, tmp_path, card, capsys):
    if not is_keychain(stock_image):
        pytest.skip("the published restore file is the keychain-camera-plus dump's first 0x1E0000 bytes")
    path = tmp_path / RESTORE_NAME
    path.write_bytes(stock_image[: checksum.UPDATE_FILE_SIZE])
    assert failures(path, path.read_bytes(), stock=stock_image) == []
    # against the public dump, which a read of a camera flashed with this very file looks like: a warning, not a pass
    by = {c.name: c for c in verify.verify_update(path, path.read_bytes(), stock=stock_image)}
    assert by["stock"].severity == "warn" and by["sensor-table"].severity == "warn"
    assert "proves a fit only if --stock was read before the camera was ever flashed" in by["sensor-table"].detail
    assert stage_plan(path, card) == 0
    out = capsys.readouterr().out
    assert "WARN backup" in out and "PASS vector-table" in out and "WARN sensor-table" in out


def test_a_restore_file_with_a_broken_vector_table_never_reaches_a_card(stock_image, tmp_path, card, capsys):
    """The ARM vector table at 0x200 is where the camera starts: a file laid out like the public build must carry
    that build's, whatever --allow-modified says (stage always passes it)."""
    data = bytearray(stock_image[: checksum.UPDATE_FILE_SIZE])
    data[0x200:0x240] = bytes(b ^ 0xFF for b in data[0x200:0x240])
    data = bytes(data)
    path = tmp_path / checksum.build_update_name(data, "202611061300").filename
    path.write_bytes(data)
    for kw in ({}, {"stock": stock_image}):
        assert "vector-table" in [c.name for c in failures(path, data, allow_modified=True, **kw)], kw
    assert stage_plan(path, card) == 1
    out = capsys.readouterr().out
    assert "FAIL vector-table" in out and "WARN software-region" in out and "Plan" not in out
    assert sorted(p.name for p in card.iterdir()) == ["DCIM"]


def test_a_sensor_table_the_code_no_longer_refers_to_is_caught(stock_image, tmp_path):
    """Zeroing the code literal that points at the init table leaves the table in place: without --stock that is a
    warning, against the dump it came from a refusal."""
    sensor = image.find_sensor_tables(stock_image)
    assert sensor.referenced_from == 0x7B91C and sensor.referenced_from % 4 == 0
    data = bytearray(stock_image[: checksum.UPDATE_FILE_SIZE])
    data[0x7B91C: 0x7B920] = b"\x00" * 4
    data = bytes(data)
    path = tmp_path / checksum.build_update_name(data, "202611061301").filename
    by = {c.name: c for c in verify.verify_update(path, data, allow_modified=True)}
    assert by["sensor-table"].severity == "warn" and "no code refers to it" in by["sensor-table"].detail
    assert "sensor-table" in [c.name for c in failures(path, data, stock=stock_image, allow_modified=True, allow_unknown_build=True)]


def test_pack_refuses_a_cut_off_stock_dump(stock_image, tmp_path, capsys):
    for n in (0x124000, 0x164800, 0x166000, 0x168000, 0x168E9A, 0x16FF00, 0x170100, 0x1DFF00):
        with pytest.raises(ValueError, match="looks truncated"):
            pack.prepare_payload(stock_image[:n])
    payload = pack.prepare_payload(stock_image[: image.SOFTWARE_END])
    assert verify.resource_problems(payload) == []


def padded(stock_image: bytes, cut: int) -> bytes:
    """What a cut-off download becomes when something pads it to the update size with 0xFF."""
    return stock_image[:cut] + b"\xff" * (checksum.UPDATE_FILE_SIZE - cut)


def test_verify_and_stage_refuse_a_padded_cut_off_dump(stock_image, tmp_path, card, capsys):
    # every cut that loses resource bytes is refused, including the range the old checks passed
    for cut in list(range(0x120000, 0x168E9A, 0x4000)) + [0x164800, 0x166000, 0x168000, 0x168E00, 0x168E99]:
        data = padded(stock_image, cut)
        path = tmp_path / checksum.build_update_name(data, "202611050009").filename
        names = [c.name for c in failures(path, data, allow_modified=True)]
        assert "resource-slots" in names or "resources" in names, hex(cut)
    # a stock dump cut off at 0x166000 no longer reaches a card
    data = padded(stock_image, 0x166000)
    path = tmp_path / checksum.build_update_name(data, "202611050009").filename
    path.write_bytes(data)
    assert stage_plan(path, card) == 1
    out = capsys.readouterr().out
    assert "FAIL resource-slots" in out and "PV001.GPZP" in out and "USB_CONNECT.GPZP" in out
    # a cut after the last resource byte loses nothing
    whole = padded(stock_image, 0x168E9A)
    assert verify.resource_problems(whole) == []


def test_damaged_candidate_c_is_refused(stock_image, built, tmp_path, card, capsys):
    base = built["C"].read_bytes()
    entries = {e.display_name: e for e in image.resource_directory(base)}
    logo, shutter = entries["POWER_ON_LOGO.JPG"], entries["IMG.WAV"]
    sensor = bytearray(base); sensor[0xF5C70] ^= 1
    no_logo = bytearray(base); no_logo[logo.offset: logo.offset + logo.budget] = b"\xff" * logo.budget
    big_riff = bytearray(base); big_riff[shutter.offset + 4: shutter.offset + 8] = (0x2000).to_bytes(4, "little")
    for i, (bad, check) in enumerate(((sensor, "sensor-table"), (no_logo, "resource-slots"), (big_riff, "resource-slots"))):
        data = bytes(bad)
        path = tmp_path / checksum.build_update_name(data, f"20261105001{i}").filename
        path.write_bytes(data)
        assert check in [c.name for c in failures(path, data, allow_modified=True)], check
        assert stage_plan(path, card) == 1
        assert f"FAIL {check}" in capsys.readouterr().out
    # --allow-unknown-build turns the sensor table into a warning for verify alone
    data = bytes(sensor)
    path = tmp_path / checksum.build_update_name(data, "202611050010").filename
    assert failures(path, data, allow_modified=True, allow_unknown_build=True) == []


def test_diff_names_the_erased_last_entry(stock_image, tmp_path, capsys):
    data = padded(stock_image, 0x168000)
    lines = diff_cmd.explain_resources(stock_image, data)
    assert lines == ["  USB_CONNECT.GPZP: ERASED in B (all 3738 bytes of its slot are 0xFF; 3652 differ)"]
    lines = diff_cmd.explain_resources(stock_image, padded(stock_image, 0x166000))
    assert any(line.startswith("  USB_CONNECT.GPZP: ERASED in B") for line in lines)
    assert any(line.startswith("  PV001.GPZP: ERASED in B") for line in lines)
