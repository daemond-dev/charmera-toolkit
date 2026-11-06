"""Tests for the probe / backup / restore commands.

No hardware and no real flashrom: every test puts a fake `flashrom` first on PATH. The fake is a small
Python script that understands `--version`, a bare probe, `-r FILE` (writes the fake chip's 2 MiB) and
`-w FILE` (records what was written). Its behaviour is switched through FAKE_FLASHROM_MODE. Nothing in
this file can touch a real programmer.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import stat
import subprocess
import sys
from pathlib import Path

import pytest

from charmera_toolkit import cli, flash, image
from charmera_toolkit.flash import FLASH_SIZE, TAIL_START

REPO = Path(__file__).resolve().parents[1]

FAKE_FLASHROM = r'''
import json, os, sys, time

state = os.environ["FAKE_FLASHROM_STATE"]
mode = os.environ.get("FAKE_FLASHROM_MODE", "ok")
argv = sys.argv[1:]
with open(os.path.join(state, "calls.log"), "a") as f:
    f.write(json.dumps(argv) + "\n")

if argv == ["--version"]:
    print("flashrom 1.4.0 on Linux 6.1.0 (x86_64)")
    print("flashrom is free software, get the source code at https://flashrom.org")
    sys.exit(0)

programmer = chip = read_to = write_from = None
i = 0
while i < len(argv):
    a = argv[i]
    if a == "-p":
        programmer = argv[i + 1]; i += 2
    elif a == "-c":
        chip = argv[i + 1]; i += 2
    elif a == "-r":
        read_to = argv[i + 1]; i += 2
    elif a == "-w":
        write_from = argv[i + 1]; i += 2
    else:
        print("fake flashrom: unexpected argument %r" % a, file=sys.stderr)
        sys.exit(3)

chip_path = os.path.join(state, "chip.bin")

# A hang is only ever simulated for a read: the toolkit runs the write and its read-back without a
# timeout, so a hanging fake write would hang the test suite.
if mode == "hang" and read_to is not None:
    time.sleep(20)
    sys.exit(0)

def chip_bytes():
    if os.path.exists(chip_path):
        with open(chip_path, "rb") as f:
            return f.read()
    body = (b"SPII" + bytes(range(256)) * 8192)[:0x1E0000]
    return body + b"\xff" * 0x20000

def bump_counter(name):
    p = os.path.join(state, name)
    n = int(open(p).read()) if os.path.exists(p) else 0
    n += 1
    open(p, "w").write(str(n))
    return n

if mode == "nochip":
    print("No EEPROM/flash device found.")
    print("Note: flashrom can never write if the flash chip isn't found automatically.")
    sys.exit(1)

print('Found Unknown flash chip "%s" (2048 kB, SPI) on %s.' % (chip or "SFDP-capable chip", programmer))

if read_to is not None:
    if mode == "readfail":
        print("Error: Reading flash failed, chip returned no data.", file=sys.stderr)
        sys.exit(1)
    data = bytearray(chip_bytes())
    n = bump_counter("reads")
    if mode == "readvary" and n >= 2:
        data[0x1000 + n] ^= 0xFF
    if mode == "readshort":
        data = data[: len(data) // 2]
    with open(read_to, "wb") as f:
        f.write(bytes(data))
    print("Reading flash... done.")
    sys.exit(0)

if write_from is not None:
    with open(write_from, "rb") as f:
        data = f.read()
    with open(os.path.join(state, "written.bin"), "wb") as f:
        f.write(data)
    if mode == "writefail":
        print("Erasing and writing flash chip... FAILED at 0x00010000!", file=sys.stderr)
        sys.exit(1)
    new = bytearray(data)
    if mode == "writecorrupt":
        new[0x175000] ^= 0x01
    with open(chip_path, "wb") as f:
        f.write(bytes(new))
    print("Erasing and writing flash chip... Erase/write done.")
    print("Verifying flash... VERIFIED.")
    sys.exit(0)

print("No operations were specified.")
sys.exit(0)
'''


# The public build's marks, as the content finder expects them: the strings with their NUL, a sensor reset table,
# a 61-write init table (page select first, one more page switch, ff ff marker) and the code literal pointing at it.
STAMP_OFFSET, BUILD_OFFSET = 0x218C4, 0x18964
SENSOR_START = 0xF5C6C
SYNTHETIC_SENSOR_TABLE = bytes(b for pair in [(0xFD, 0x00)] + [(r, (r * 3) & 0xFF) for r in range(0x20, 0x20 + 58)]
                               + [(0xFD, 0x01), (0x11, 0x80)] for b in pair) + b"\xff\xff"


def make_image(fill: int = 0x5A) -> bytes:
    """A 2 MiB image shaped like a stock dump: magic, the build marks, a 0xFF tail."""
    body = bytearray(bytes([fill]) * TAIL_START)
    body[0:4] = b"SPII"
    body[BUILD_OFFSET: BUILD_OFFSET + 17] = b"JH5408  20250520\x00"
    body[STAMP_OFFSET - 1: STAMP_OFFSET + 13] = b"\x00202505201029\x00"   # NUL-terminated, as in the firmware
    body[0xF5BFC: 0xF5C02] = image.SENSOR_RESET_TABLE
    body[SENSOR_START: SENSOR_START + len(SYNTHETIC_SENSOR_TABLE)] = SYNTHETIC_SENSOR_TABLE
    body[0x7B91C: 0x7B920] = (SENSOR_START - 0x200).to_bytes(4, "little")
    return bytes(body) + b"\xff" * (FLASH_SIZE - TAIL_START)


class FakeFlashrom:
    def __init__(self, state: Path):
        self.state = state

    def set_mode(self, mode: str, monkeypatch) -> None:
        monkeypatch.setenv("FAKE_FLASHROM_MODE", mode)

    def set_chip(self, data: bytes) -> None:
        (self.state / "chip.bin").write_bytes(data)

    def calls(self) -> list:
        log = self.state / "calls.log"
        if not log.exists():
            return []
        return [json.loads(line) for line in log.read_text().splitlines() if line.strip()]

    def written(self) -> bytes:
        return (self.state / "written.bin").read_bytes()

    def wrote_anything(self) -> bool:
        return (self.state / "written.bin").exists()


@pytest.fixture
def fake_flashrom(tmp_path, monkeypatch) -> FakeFlashrom:
    bin_dir = tmp_path / "fakebin"
    bin_dir.mkdir()
    state = tmp_path / "state"
    state.mkdir()
    script = bin_dir / "flashrom_fake.py"
    script.write_text(FAKE_FLASHROM, encoding="utf-8")
    if os.name == "nt":
        (bin_dir / "flashrom.bat").write_text(
            f'@echo off\r\n"{sys.executable}" "{script}" %*\r\n', encoding="utf-8"
        )
    else:
        shim = bin_dir / "flashrom"
        shim.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{script}" "$@"\n', encoding="utf-8")
        shim.chmod(shim.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    monkeypatch.setenv("PATH", str(bin_dir) + os.pathsep + os.environ.get("PATH", ""))
    monkeypatch.setenv("FAKE_FLASHROM_STATE", str(state))
    monkeypatch.delenv("FAKE_FLASHROM_MODE", raising=False)
    return FakeFlashrom(state)


def run(argv: list) -> int:
    """Run one of this module's subcommands the way cli.py wires them, isolated from other modules."""
    parser = argparse.ArgumentParser(prog="charmera")
    sub = parser.add_subparsers(dest="command")
    flash.register(sub)
    args = parser.parse_args(argv)
    return int(args.func(args) or 0)


# ---------------------------------------------------------------------------------------------- wiring


def test_cli_registers_the_three_commands():
    parser = cli.build_parser()
    help_text = parser.format_help()
    for name in ("probe", "backup", "restore"):
        assert name in help_text


def test_chip_args_default_and_auto():
    assert flash.chip_args("SFDP-capable chip") == ["-c", "SFDP-capable chip"]
    assert flash.chip_args("auto") == []
    assert flash.chip_args("AUTO") == []
    assert flash.chip_args("") == []
    assert flash.chip_args(None) == []


# ---------------------------------------------------------------------------------------------- probe


def test_parse_probe_found_sfdp():
    text = (
        "flashrom 1.4.0 on Linux 6.1.0 (x86_64)\n"
        "Using clock_gettime for delay loops (clk_id: 1, resolution: 1ns).\n"
        'Found Unknown flash chip "SFDP-capable chip" (2048 kB, SPI) on ch341a_spi.\n'
        "===\n"
        "This flash part has status UNTESTED for operations: WP\n"
        "No operations were specified.\n"
    )
    parsed = flash.parse_probe_output(text)
    assert parsed.ok
    assert parsed.found == [
        {"vendor": "Unknown", "name": "SFDP-capable chip", "size_kb": "2048", "bus": "SPI", "programmer": "ch341a_spi"}
    ]
    assert any("UNTESTED" in n for n in parsed.notes)
    assert not parsed.no_device


def test_parse_probe_no_device():
    text = "flashrom 1.2 on Linux\nNo EEPROM/flash device found.\nNote: flashrom can never write if the flash chip isn't found automatically.\n"
    parsed = flash.parse_probe_output(text)
    assert parsed.no_device
    assert not parsed.ok
    assert parsed.found == []


def test_parse_probe_multiple_definitions():
    text = (
        'Found Winbond flash chip "W25Q16.V" (2048 kB, SPI) on ch341a_spi.\n'
        'Found Winbond flash chip "W25Q16JV_M" (2048 kB, SPI) on ch341a_spi.\n'
        'Multiple flash chip definitions match the detected chip(s): "W25Q16.V", "W25Q16JV_M"\n'
        "Please specify which chip definition to use with the -c <chipname> option.\n"
    )
    parsed = flash.parse_probe_output(text)
    assert len(parsed.found) == 2
    assert parsed.multiple == ["W25Q16.V", "W25Q16JV_M"]
    assert not parsed.ok


def test_parse_probe_programmer_failure():
    text = (
        "Error: Could not find a CH341A device, or the device could not be opened.\n"
        "Error: Programmer initialization failed.\n"
    )
    parsed = flash.parse_probe_output(text)
    assert not parsed.ok
    assert len(parsed.problems) == 2


def test_probe_command_finds_chip(fake_flashrom, capsys):
    assert run(["probe"]) == 0
    out = capsys.readouterr().out
    assert "flashrom 1.4.0" in out
    assert 'Found: Unknown "SFDP-capable chip", 2048 kB, SPI on ch341a_spi' in out
    calls = fake_flashrom.calls()
    assert calls[0] == ["--version"]
    assert calls[1] == ["-p", "ch341a_spi", "-c", "SFDP-capable chip"]
    assert not fake_flashrom.wrote_anything()


def test_probe_command_chip_auto_omits_c(fake_flashrom):
    assert run(["probe", "--chip", "auto"]) == 0
    assert fake_flashrom.calls()[1] == ["-p", "ch341a_spi"]


def test_probe_command_no_chip(fake_flashrom, monkeypatch, capsys):
    fake_flashrom.set_mode("nochip", monkeypatch)
    assert run(["probe"]) == 1
    out = capsys.readouterr().out
    assert "No flash chip was detected" in out


def test_flashrom_missing_gives_install_hints(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("PATH", str(tmp_path / "empty"))
    assert run(["probe"]) == 1
    err = capsys.readouterr().err
    assert "flashrom was not found" in err
    assert "--flashrom" in err


def test_flashrom_explicit_path_missing(tmp_path, capsys):
    assert run(["probe", "--flashrom", str(tmp_path / "nope")]) == 1
    err = capsys.readouterr().err
    assert "is not a file" in err


def test_find_flashrom_relative_name_is_returned_absolute(fake_flashrom, tmp_path, monkeypatch, capsys):
    """A bare or ./relative --flashrom passes the is_file check against cwd; the path returned (and so
    the one shown and run) must be that file, absolute, not a PATH lookup of the same name."""
    name = "flashrom.bat" if os.name == "nt" else "flashrom"
    local = tmp_path / "local"
    local.mkdir()
    shutil.copy2(tmp_path / "fakebin" / name, local / name)
    monkeypatch.chdir(local)
    expected = (local / name).resolve()
    for given in (f".{os.sep}{name}", name):
        found = flash.find_flashrom(given)
        assert Path(found).is_absolute(), found
        assert Path(found) == expected
    # Absolute paths are unchanged; a PATH lookup answers with the PATH copy, absolute (Windows' which()
    # also searches the working directory, so the lookup runs from a directory without a copy).
    assert Path(flash.find_flashrom(str(expected))) == expected
    monkeypatch.chdir(tmp_path)
    found = flash.find_flashrom(None)
    assert Path(found).is_absolute(), found
    assert Path(found) == (tmp_path / "fakebin" / name).resolve()
    monkeypatch.chdir(local)

    assert run(["probe", "--flashrom", f".{os.sep}{name}"]) == 0
    out = capsys.readouterr().out
    assert f"flashrom: {expected}" in out
    assert "Running: " + flash._display([str(expected), "-p", "ch341a_spi", "-c", "SFDP-capable chip"]) in out


def test_display_quotes_for_the_running_platform(monkeypatch):
    windows = ["C:\\Program Files\\flashrom\\flashrom.exe", "-p", "ch341a_spi", "-c", "SFDP-capable chip"]
    monkeypatch.setattr(flash.os, "name", "nt")
    assert flash._display(windows) == subprocess.list2cmdline(windows)
    assert flash._display(windows) == '"C:\\Program Files\\flashrom\\flashrom.exe" -p ch341a_spi -c "SFDP-capable chip"'
    monkeypatch.setattr(flash.os, "name", "posix")
    assert flash._display(["/opt/x y/flashrom", "-c", "SFDP-capable chip"]) == "'/opt/x y/flashrom' -c 'SFDP-capable chip'"


def test_run_flashrom_timeout_on_a_read(fake_flashrom, tmp_path, monkeypatch):
    fake_flashrom.set_mode("hang", monkeypatch)
    exe = flash.find_flashrom(None)
    with pytest.raises(flash.FlashromError) as info:
        flash.run_flashrom(exe, ["-p", "ch341a_spi", "-r", str(tmp_path / "x.bin")], timeout=0.5, echo=False)
    assert "did not finish" in str(info.value)


def test_hang_fixture_does_not_apply_to_probe_or_write(fake_flashrom, tmp_path, monkeypatch):
    """The fake only hangs on -r. A probe or a write in hang mode returns at once, so no test can wait
    on an un-timed write by accident."""
    fake_flashrom.set_mode("hang", monkeypatch)
    exe = flash.find_flashrom(None)
    assert flash.run_flashrom(exe, ["-p", "ch341a_spi"], timeout=5.0, echo=False).ok
    image = tmp_path / "img.bin"
    image.write_bytes(make_image())
    assert flash.write_chip(exe, "ch341a_spi", "SFDP-capable chip", image).ok


def test_write_and_readback_have_no_timeout_and_reads_keep_theirs(fake_flashrom, tmp_path, monkeypatch):
    seen = []
    real = flash.run_flashrom

    def recording(flashrom, args, timeout, echo=True):
        seen.append((list(args), timeout))
        return real(flashrom, args, timeout, echo)

    monkeypatch.setattr(flash, "run_flashrom", recording)
    monkeypatch.setattr("builtins.input", lambda prompt="": "RESTORE")
    image = tmp_path / "stock.bin"
    image.write_bytes(make_image())

    assert run(["probe"]) == 0
    assert run(["backup", "--out", str(tmp_path / "b")]) == 0
    assert run(["restore", "--image", str(image)]) == 0

    assert all(t == flash.VERSION_TIMEOUT for a, t in seen if a == ["--version"])
    assert [t for a, t in seen if "-r" not in a and "-w" not in a and a != ["--version"]] == [flash.PROBE_TIMEOUT]
    backup_reads = [t for a, t in seen if "-r" in a][:2]
    assert backup_reads == [flash.READ_TIMEOUT, flash.READ_TIMEOUT]
    writes = [(a, t) for a, t in seen if "-w" in a]
    assert len(writes) == 1 and writes[0][1] is None
    readback = [(a, t) for a, t in seen if "-r" in a][2:]
    assert len(readback) == 1 and readback[0][1] is None
    assert not hasattr(flash, "WRITE_TIMEOUT")


# ---------------------------------------------------------------------------------------------- backup


def test_backup_happy_path(fake_flashrom, tmp_path, capsys):
    image = make_image()
    fake_flashrom.set_chip(image)
    out_dir = tmp_path / "backups"
    assert run(["backup", "--out", str(out_dir), "--label", "mine"]) == 0

    bins = sorted(out_dir.glob("mine_*.bin"))
    assert len(bins) == 1, list(out_dir.iterdir())
    final = bins[0]
    assert ".read" not in final.name
    assert final.read_bytes() == image
    assert not list(out_dir.glob("*.read*.bin"))

    expected_sha = flash.sha256_hex(image)
    sha_file = out_dir / (final.stem + ".sha256")
    assert sha_file.read_text() == f"{expected_sha}  {final.name}\n"

    manifest = json.loads((out_dir / (final.stem + ".manifest.json")).read_text())
    assert manifest["file"] == final.name
    assert manifest["sha256"] == expected_sha
    assert manifest["size"] == FLASH_SIZE
    assert manifest["size_ok"] is True
    assert manifest["magic_ok"] is True
    assert manifest["build_marks"]["build_string"] == {"offset": "0x18964", "value": "JH5408  20250520"}
    assert manifest["build_marks"]["stamp"] == {"offset": "0x218C4", "value": "202505201029"}
    assert manifest["build_marks"]["sensor_table"]["offset"] == "0xF5C6C" and manifest["build_marks"]["sensor_table"]["writes"] == 61
    assert manifest["build_marks_ok"] is True
    assert manifest["build"] == "public-dump-modified"
    assert manifest["tail_blank"] is True
    assert manifest["public_dump"] is None
    assert manifest["reads"] == 2
    assert manifest["reads_identical"] is True
    assert manifest["programmer"] == "ch341a_spi"
    assert manifest["chip"] == "SFDP-capable chip"
    assert manifest["flashrom_version"].startswith("flashrom 1.4.0")
    assert manifest["flashrom_path"] == flash.find_flashrom(None)
    assert Path(manifest["flashrom_path"]).is_absolute()
    assert manifest["warnings"] == []
    assert manifest["timestamp_utc"].endswith("+00:00")

    calls = fake_flashrom.calls()
    reads = [c for c in calls if "-r" in c]
    assert len(reads) == 2
    for c in reads:
        assert c[:4] == ["-p", "ch341a_spi", "-c", "SFDP-capable chip"]
    assert not any("-w" in c for c in calls)
    assert not fake_flashrom.wrote_anything()

    out = capsys.readouterr().out
    assert expected_sha in out
    assert "Keep the three files together" in out
    assert "not cross-checked" not in out


def test_backup_single_read_is_not_recorded_as_identical(fake_flashrom, tmp_path, capsys):
    fake_flashrom.set_chip(make_image())
    out_dir = tmp_path / "b"
    assert run(["backup", "--out", str(out_dir), "--reads", "1"]) == 0
    manifest = json.loads(next(out_dir.glob("*.manifest.json")).read_text())
    assert manifest["reads"] == 1
    assert manifest["reads_identical"] is None
    assert len([c for c in fake_flashrom.calls() if "-r" in c]) == 1
    out = capsys.readouterr().out
    assert "single read was not cross-checked" in out
    assert "Keep the three files together" in out


def test_backup_reads_n_times_and_chip_auto(fake_flashrom, tmp_path):
    fake_flashrom.set_chip(make_image())
    assert run(["backup", "--out", str(tmp_path / "b"), "--reads", "3", "--chip", "auto"]) == 0
    reads = [c for c in fake_flashrom.calls() if "-r" in c]
    assert len(reads) == 3
    assert all("-c" not in c for c in reads)
    manifest = json.loads(next((tmp_path / "b").glob("*.manifest.json")).read_text())
    assert manifest["chip"] == "auto"
    assert manifest["reads"] == 3


def test_backup_differing_reads_exits_1_and_keeps_reads(fake_flashrom, tmp_path, monkeypatch, capsys):
    fake_flashrom.set_chip(make_image())
    fake_flashrom.set_mode("readvary", monkeypatch)
    out_dir = tmp_path / "b"
    assert run(["backup", "--out", str(out_dir)]) == 1
    kept = sorted(p.name for p in out_dir.iterdir())
    assert len(kept) == 2
    assert all(".read" in n and n.endswith(".bin") for n in kept)
    assert not list(out_dir.glob("*.sha256"))
    assert not list(out_dir.glob("*.manifest.json"))
    captured = capsys.readouterr()
    assert "not identical" in captured.err
    assert "first difference at 0x1002" in captured.out


def test_backup_flashrom_read_failure(fake_flashrom, tmp_path, monkeypatch, capsys):
    fake_flashrom.set_mode("readfail", monkeypatch)
    assert run(["backup", "--out", str(tmp_path / "b")]) == 1
    captured = capsys.readouterr()
    assert "read 1 failed" in captured.err
    assert "chip returned no data" in captured.out


def test_backup_keeps_blank_chip_with_warnings(fake_flashrom, tmp_path, capsys):
    fake_flashrom.set_chip(b"\xff" * FLASH_SIZE)
    out_dir = tmp_path / "b"
    assert run(["backup", "--out", str(out_dir)]) == 0
    final = next(out_dir.glob("charmera_*.bin"))
    assert final.read_bytes() == b"\xff" * FLASH_SIZE
    manifest = json.loads((out_dir / (final.stem + ".manifest.json")).read_text())
    assert manifest["magic_ok"] is False
    assert manifest["build_marks_ok"] is False
    assert manifest["build_marks"] == {"build_string": None, "stamp": None, "sensor_table": None}
    assert manifest["build"] == "not-charmera"
    assert manifest["tail_blank"] is True
    assert any("SPII" in w for w in manifest["warnings"])
    out = capsys.readouterr().out
    assert "kept anyway" in out


def test_backup_short_read_is_kept_but_exits_1(fake_flashrom, tmp_path, monkeypatch, capsys):
    """A read of the wrong size is not a backup: restore refuses it, so backup must not report success.
    The file, its checksum and its manifest are still written, as the record of what was read."""
    fake_flashrom.set_chip(make_image())
    fake_flashrom.set_mode("readshort", monkeypatch)
    out_dir = tmp_path / "b"
    assert run(["backup", "--out", str(out_dir)]) == 1
    final = next(out_dir.glob("charmera_*.bin"))
    assert final.read_bytes() == make_image()[: FLASH_SIZE // 2]
    assert (out_dir / (final.stem + ".sha256")).is_file()
    manifest = json.loads((out_dir / (final.stem + ".manifest.json")).read_text())
    assert manifest["size"] == FLASH_SIZE // 2
    assert manifest["size_ok"] is False
    assert manifest["tail_blank"] is False
    assert any("expected 2,097,152" in w for w in manifest["warnings"])
    captured = capsys.readouterr()
    assert "not a full-flash image" in captured.err
    assert "restore will refuse it" in captured.err
    assert "2048 kB" in captured.err
    assert str(final) in captured.out
    assert "Keep the three files together" not in captured.out
    assert "backup:   " not in captured.out
    # And restore does refuse exactly that file.
    assert run(["restore", "--image", str(final)]) == 1
    assert "Nothing was written" in capsys.readouterr().err
    assert not fake_flashrom.wrote_anything()


def test_backup_recognizes_public_dump(fake_flashrom, tmp_path, monkeypatch, capsys):
    image = make_image(0x11)
    fake_flashrom.set_chip(image)
    monkeypatch.setitem(flash.PUBLIC_DUMPS, flash.sha256_hex(image), "example/public-dump")
    out_dir = tmp_path / "b"
    assert run(["backup", "--out", str(out_dir)]) == 0
    manifest = json.loads(next(out_dir.glob("*.manifest.json")).read_text())
    assert manifest["public_dump"] == "example/public-dump"
    assert "example/public-dump" in capsys.readouterr().out


def test_backup_label_is_sanitized(fake_flashrom, tmp_path):
    fake_flashrom.set_chip(make_image())
    out_dir = tmp_path / "b"
    assert run(["backup", "--out", str(out_dir), "--label", "my cam/#1"]) == 0
    final = next(out_dir.glob("*.bin"))
    assert final.name.startswith("my_cam_1_")


def test_backup_rejects_zero_reads(fake_flashrom, tmp_path, capsys):
    assert run(["backup", "--out", str(tmp_path / "b"), "--reads", "0"]) == 1
    assert "--reads" in capsys.readouterr().err


# ---------------------------------------------------------------------------------------------- restore


def test_restore_refuses_wrong_size(fake_flashrom, tmp_path, capsys):
    image = tmp_path / "short.bin"
    image.write_bytes(make_image()[:-1])
    assert run(["restore", "--image", str(image)]) == 1
    err = capsys.readouterr().err
    assert "2,097,152" in err
    assert "Nothing was written" in err
    assert not fake_flashrom.wrote_anything()
    assert fake_flashrom.calls() == []


def test_restore_refuses_wrong_magic(fake_flashrom, tmp_path, capsys):
    data = bytearray(make_image())
    data[0:4] = b"XXXX"
    image = tmp_path / "nomagic.bin"
    image.write_bytes(bytes(data))
    assert run(["restore", "--image", str(image)]) == 1
    assert "SPII" in capsys.readouterr().err
    assert not fake_flashrom.wrote_anything()


def test_restore_refuses_missing_build_marks_without_flag(fake_flashrom, tmp_path, capsys):
    data = bytearray(make_image())
    data[STAMP_OFFSET + 12] = ord("Z")      # no NUL after the digits: not a stamp string any more
    image_path = tmp_path / "other.bin"
    image_path.write_bytes(bytes(data))
    assert run(["restore", "--image", str(image_path)]) == 1
    err = capsys.readouterr().err
    assert "no Charmera build marks" in err and "stamp missing" in err
    assert "--allow-unknown-build" in err
    assert not fake_flashrom.wrote_anything()
    # another stamp at the same place is still a Charmera build mark: the stamp is not an identity
    data = bytearray(make_image())
    data[STAMP_OFFSET: STAMP_OFFSET + 12] = b"202601010000"
    image_path.write_bytes(bytes(data))
    assert flash.analyze_image(bytes(data))["build_marks_ok"] is True
    assert flash.analyze_image(bytes(data))["build"] == "charmera-unknown-build"


def test_restore_missing_build_stamp_allowed_with_flag(fake_flashrom, tmp_path, monkeypatch, capsys):
    data = bytearray(make_image())
    data[BUILD_OFFSET: BUILD_OFFSET + 16] = b"X" * 16
    image = tmp_path / "other.bin"
    image.write_bytes(bytes(data))
    monkeypatch.setattr("builtins.input", lambda prompt="": "no")
    assert run(["restore", "--image", str(image), "--allow-unknown-build"]) == 1
    captured = capsys.readouterr()
    assert "continuing because --allow-unknown-build" in captured.out
    assert "Stopped. Nothing was written." in captured.out
    assert not fake_flashrom.wrote_anything()


def test_restore_declined_writes_nothing(fake_flashrom, tmp_path, monkeypatch, capsys):
    image = tmp_path / "stock.bin"
    image.write_bytes(make_image())
    monkeypatch.setattr("builtins.input", lambda prompt="": "restore")  # wrong case is a refusal
    assert run(["restore", "--image", str(image)]) == 1
    assert "Stopped. Nothing was written." in capsys.readouterr().out
    assert not fake_flashrom.wrote_anything()
    assert not any("-w" in c for c in fake_flashrom.calls())


def test_restore_happy_path(fake_flashrom, tmp_path, monkeypatch, capsys):
    data = make_image()
    image = tmp_path / "stock.bin"
    image.write_bytes(data)
    fake_flashrom.set_chip(make_image(0x00))  # the chip holds something else before the write
    prompts = []

    def fake_input(prompt=""):
        prompts.append(prompt)
        return "RESTORE"

    monkeypatch.setattr("builtins.input", fake_input)
    assert run(["restore", "--image", str(image)]) == 0

    assert prompts and "RESTORE" in prompts[0]
    assert fake_flashrom.written() == data
    calls = fake_flashrom.calls()
    ops = [c for c in calls if "-w" in c or "-r" in c]
    assert len(ops) == 2
    assert ops[0][:4] == ["-p", "ch341a_spi", "-c", "SFDP-capable chip"]
    assert ops[0][4:] == ["-w", str(image)]
    assert ops[1][4] == "-r"
    out = capsys.readouterr().out
    assert flash.sha256_hex(data) in out
    assert "Plan:" in out
    exe = flash.find_flashrom(None)
    assert "  1. " + flash._display([exe, "-p", "ch341a_spi", "-c", "SFDP-capable chip", "-w", str(image)]) in out
    assert "never interrupts it" in out
    assert "Running: " + flash._display([exe, "-p", "ch341a_spi", "-c", "SFDP-capable chip", "-w", str(image)]) in out
    assert "Restore verified" in out
    assert not Path(ops[1][5]).exists()  # the read-back temp file is removed after a verified restore


def test_restore_readback_mismatch_exits_nonzero(fake_flashrom, tmp_path, monkeypatch, capsys):
    data = make_image()
    image = tmp_path / "stock.bin"
    image.write_bytes(data)
    fake_flashrom.set_mode("writecorrupt", monkeypatch)
    monkeypatch.setattr("builtins.input", lambda prompt="": "RESTORE")
    assert run(["restore", "--image", str(image)]) == 1
    captured = capsys.readouterr()
    assert "does not match the image after writing" in captured.err
    assert "block 23" in captured.out  # 0x175000 is in the settings store block
    assert "settings store" in captured.out
    readback = [c for c in fake_flashrom.calls() if "-r" in c][0]
    assert Path(readback[5]).exists()  # kept for diagnosis


def test_restore_write_failure_exits_nonzero(fake_flashrom, tmp_path, monkeypatch, capsys):
    image = tmp_path / "stock.bin"
    image.write_bytes(make_image())
    fake_flashrom.set_mode("writefail", monkeypatch)
    monkeypatch.setattr("builtins.input", lambda prompt="": "RESTORE")
    assert run(["restore", "--image", str(image)]) == 1
    captured = capsys.readouterr()
    assert "flashrom -w failed" in captured.err
    assert "FAILED at 0x00010000" in captured.out
    # The erase/write phase had started, so the chip may be half written.
    assert "mix of old and new data" in captured.err
    assert "stopped before touching the chip" not in captured.err
    assert not any("-r" in c for c in fake_flashrom.calls())


def test_restore_write_failure_before_the_chip_was_touched(fake_flashrom, tmp_path, monkeypatch, capsys):
    """flashrom -w that fails while detecting the chip (nothing erased) gets probe-style advice, not the
    'mix of old and new data' warning."""
    image = tmp_path / "stock.bin"
    image.write_bytes(make_image())
    fake_flashrom.set_mode("nochip", monkeypatch)
    monkeypatch.setattr("builtins.input", lambda prompt="": "RESTORE")
    assert run(["restore", "--image", str(image)]) == 1
    captured = capsys.readouterr()
    assert "flashrom -w failed" in captured.err
    assert "stopped before touching the chip" in captured.err
    assert "mix of old and new data" not in captured.err
    assert "`probe`" in captured.err
    assert "No EEPROM/flash device found" in captured.out
    assert not fake_flashrom.wrote_anything()
    assert not any("-r" in c for c in fake_flashrom.calls())


def test_write_stopped_before_chip_classification():
    stopped = flash.write_stopped_before_chip
    assert stopped(
        "No EEPROM/flash device found.\n"
        "Note: flashrom can never write if the flash chip isn't found automatically.\n"
    )
    assert stopped(
        "Error: Could not find a CH341A device, or the device could not be opened.\n"
        "Error: Programmer initialization failed.\n"
    )
    assert stopped(
        'Found Winbond flash chip "W25Q16.V" (2048 kB, SPI) on ch341a_spi.\n'
        'Found Winbond flash chip "W25Q16JV_M" (2048 kB, SPI) on ch341a_spi.\n'
        'Multiple flash chip definitions match the detected chip(s): "W25Q16.V", "W25Q16JV_M"\n'
    )
    # Once "Erasing and writing" has been printed the chip may be half written, whatever else is there.
    assert not stopped(
        'Found Unknown flash chip "SFDP-capable chip" (2048 kB, SPI) on ch341a_spi.\n'
        "Erasing and writing flash chip... FAILED at 0x00010000!\n"
    )
    assert not stopped(
        "Erasing and writing flash chip... Erase/write done.\n"
        "Verifying flash... FAILED at 0x00000100! Expected=0x5a, Found=0xff\n"
    )
    # Ambiguous output (no symptom, no marker) is treated as a possible partial write.
    assert not stopped("")
    assert not stopped('Found Unknown flash chip "SFDP-capable chip" (2048 kB, SPI) on ch341a_spi.\n')


def test_restore_prints_block_diff_against_backup(fake_flashrom, tmp_path, monkeypatch, capsys):
    stock = make_image()
    backup = bytearray(stock)
    backup[0x170000:0x170100] = b"\x01" * 0x100  # a settings record only the owner has
    backup[0x1A5800:0x1A5810] = b"\x02" * 0x10  # second photo slot
    image = tmp_path / "stock.bin"
    image.write_bytes(stock)
    backup_path = tmp_path / "mine.bin"
    backup_path.write_bytes(bytes(backup))
    monkeypatch.setattr("builtins.input", lambda prompt="": "no")
    assert run(["restore", "--image", str(image), "--backup", str(backup_path)]) == 1
    out = capsys.readouterr().out
    assert "2 of 32 differ" in out
    assert "block 23  0x170000-0x17FFFF      256 bytes differ  (settings store)" in out
    assert "block 26  0x1A0000-0x1AFFFF       16 bytes differ  (internally stored photos)" in out
    assert "30 blocks are identical between the image and the given backup." in out
    # Only a fresh read of the chip could promise that identical blocks will not change.
    assert "will not change" not in out
    assert flash.sha256_hex(bytes(backup)) in out


def test_restore_backup_help_and_prompt_ask_for_a_fresh_backup(fake_flashrom, tmp_path, monkeypatch, capsys):
    parser = argparse.ArgumentParser(prog="charmera")
    sub = parser.add_subparsers(dest="command")
    flash.register(sub)
    help_text = " ".join(sub.choices["restore"].format_help().split())  # argparse wraps lines
    assert "a fresh backup of the chip as it is now, to show what the write will change" in help_text
    assert "your own backup" not in help_text

    image = tmp_path / "stock.bin"
    image.write_bytes(make_image())
    monkeypatch.setattr("builtins.input", lambda prompt="": "")
    assert run(["restore", "--image", str(image)]) == 1
    out = capsys.readouterr().out
    assert "fresh backup of the chip" in out
    assert "which blocks the write will change" in out


def test_restore_identical_backup(fake_flashrom, tmp_path, monkeypatch, capsys):
    stock = make_image()
    image = tmp_path / "stock.bin"
    image.write_bytes(stock)
    monkeypatch.setattr("builtins.input", lambda prompt="": "")
    assert run(["restore", "--image", str(image), "--backup", str(image)]) == 1
    assert "identical to the backup in all 32 blocks" in capsys.readouterr().out


def test_restore_notes_public_dump(fake_flashrom, tmp_path, monkeypatch, capsys):
    stock = make_image(0x33)
    image = tmp_path / "public.bin"
    image.write_bytes(stock)
    monkeypatch.setitem(flash.PUBLIC_DUMPS, flash.sha256_hex(stock), "example/public-dump")
    monkeypatch.setattr("builtins.input", lambda prompt="": "")
    assert run(["restore", "--image", str(image)]) == 1
    out = capsys.readouterr().out
    assert "public dump from example/public-dump" in out
    assert "last resort" in out


def test_restore_without_flashrom_stops_before_prompt(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("PATH", str(tmp_path / "empty"))
    image = tmp_path / "stock.bin"
    image.write_bytes(make_image())
    monkeypatch.setattr("builtins.input", lambda prompt="": pytest.fail("must not prompt without flashrom"))
    assert run(["restore", "--image", str(image)]) == 1
    assert "flashrom was not found" in capsys.readouterr().err


# ------------------------------------------------------------------------------------- scripts and docs


def test_udev_rule_sorts_before_uaccess_and_every_reference_agrees(monkeypatch):
    rules = sorted(p.name for p in (REPO / "scripts").glob("*.rules"))
    assert rules == ["70-ch341a.rules"]
    # The uaccess tag is acted on by systemd's 70-uaccess.rules and 73-seat-late.rules; a file that sorts
    # after them (99-*) sets the tag too late to grant access at plug time.
    assert rules[0] < "70-uaccess.rules" < "73-seat-late.rules"
    text = (REPO / "scripts" / "70-ch341a.rules").read_text(encoding="utf-8")
    assert "sudo cp scripts/70-ch341a.rules /etc/udev/rules.d/" in text
    assert 'TAG+="uaccess"' in text
    assert 'ATTR{idVendor}=="1a86", ATTR{idProduct}=="5512"' in text

    monkeypatch.setattr(flash.platform, "system", lambda: "Linux")
    hints = flash.install_hints()
    assert "scripts/70-ch341a.rules" in hints
    assert "99-ch341a" not in hints
    for rel in ("docs/02-RECOVERY.md", "scripts/backup.sh"):
        content = (REPO / rel).read_text(encoding="utf-8")
        assert "scripts/70-ch341a.rules" in content, rel
        assert "99-ch341a" not in content, rel


@pytest.mark.parametrize("name", ["backup.ps1", "restore.ps1"])
def test_powershell_wrappers_fail_closed_without_an_interpreter(name):
    """No PowerShell here, so this checks the shape: the interpreter is resolved with a terminating
    error, the exit code is preset to 1, and a failed call cannot fall through to exit 0."""
    text = (REPO / "scripts" / name).read_text(encoding="utf-8")
    assert "Get-Command $python -ErrorAction Stop" in text
    resolve = text.index("Get-Command $python")
    preset = text.index("$LASTEXITCODE = 1")
    call = text.index("& $interpreter -m charmera_toolkit")
    check = text.index("if (-not $?)")
    assert resolve < preset < call < check
    assert "& $python" not in text
    assert text.rstrip().endswith("exit $LASTEXITCODE")


@pytest.mark.parametrize("name", ["restore.sh", "restore.ps1"])
def test_restore_wrappers_describe_backup_as_the_chip_now(name):
    text = (REPO / "scripts" / name).read_text(encoding="utf-8")
    assert "--backup <backup of the chip as it is now>" in text
    assert "--backup <your backup>" not in text


def test_recovery_doc_matches_the_tool():
    text = " ".join((REPO / "docs" / "02-RECOVERY.md").read_text(encoding="utf-8").split())  # unwrap lines
    assert "--backup <backup of the chip as it is now>.bin" in text
    assert "if flashrom stopped before it reached the erase" in text
    assert "the chip is untouched" in text
    assert "no time limit on the write or on that read-back" in text
    assert "will not change" not in text


# ---------------------------------------------------------------------------------------------- helpers


def test_analyze_image_and_block_diff():
    stock = make_image()
    info = flash.analyze_image(stock)
    assert info["size_ok"] and info["magic_ok"] and info["build_marks_ok"] and info["tail_blank"]
    assert info["public_dump"] is None
    assert flash.image_warnings(info) == []

    other = bytearray(stock)
    other[0x1F0000] = 0x00
    diffs = flash.block_diff(stock, bytes(other))
    assert [(d.index, d.differing_bytes, d.region) for d in diffs] == [(31, 1, "tail, 0xFF in every known dump")]
    info2 = flash.analyze_image(bytes(other))
    assert info2["tail_blank"] is False
    assert any("not all 0xFF" in w for w in flash.image_warnings(info2))

    shorter = flash.block_diff(stock, stock[:FLASH_SIZE - 10])
    assert shorter[-1].index == 31 and shorter[-1].differing_bytes == 10
