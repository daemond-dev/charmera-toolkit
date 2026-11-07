"""The mod commands' output rule: a mod is an image, not an update file, so it is never written under an update
file's name or into what looks like a camera card.

sounds, settings set, tweak, frames and resources all write through sounds._check_output. The frames and resources
commands are covered in their own test files; this file checks the rule itself and the commands that need no
resource pack. Every image here is synthetic.
"""
import struct
import zlib

import pytest

from charmera_toolkit import cli, image, settings
from charmera_toolkit.sounds import GATE_FLAG, _check_output

UPDATE_NAMES = ["JH_5408202611061200_00000000.bin", "jh_5408202611061200_1121E2C8.BIN", "Jh_anything.Bin", "JH_.bin"]


def minimal_image() -> bytes:
    """2 MiB with the public build's marks where that build has them, one valid settings record and a zeroed photo
    directory: enough for sounds mute, settings set and tweak to reach their output check."""
    img = bytearray(b"\xff" * 0x200000)
    img[0:4] = b"SPII"
    b = image.PUBLIC_BUILD
    img[b.stamp_offset: b.stamp_offset + 13] = b.stamp.encode() + b"\x00"
    img[b.build_offset: b.build_offset + 20] = b"JH5408  20250520\x00\x00\x00\x00"
    img[0xF5BFC: 0xF5C02] = image.SENSOR_RESET_TABLE
    table = bytes(x for pair in [(0xFD, 0x00)] + [(r, (r * 3) & 0xFF) for r in range(0x20, 0x20 + 58)]
                  + [(0xFD, 0x01), (0x11, 0x80)] for x in pair) + b"\xff\xff"
    img[0xF5C6C: 0xF5C6C + len(table)] = table
    img[0x7B91C: 0x7B920] = struct.pack("<I", 0xF5C6C - image.VA_OFFSET)
    body = bytearray(252)
    body[0] = 1
    body[settings.OFF_UI_SOUND] = 5
    record = bytes(body) + (zlib.crc32(bytes(body)) & 0xFFFFFFFF).to_bytes(4, "little")
    img[settings.SETTINGS_BASE: settings.SETTINGS_BASE + 256] = record
    img[settings.SETTINGS_BASE + 256: settings.SETTINGS_BASE + 512] = b"\x00" * 256
    img[settings.PHOTO_DIRECTORY: settings.PHOTO_DIRECTORY + 0x200] = b"\x00" * 0x200
    return bytes(img)


@pytest.fixture
def src(tmp_path):
    p = tmp_path / "work" / "backup.bin"
    p.parent.mkdir()
    p.write_bytes(minimal_image())
    return p


@pytest.fixture
def card(tmp_path):
    """A folder that looks like a camera's card: it has a DCIM folder."""
    root = tmp_path / "card"
    (root / "DCIM" / "100MEDIA").mkdir(parents=True)
    return root


def test_check_output_refuses_an_update_file_name_in_any_case(src):
    for name in UPDATE_NAMES:
        why = _check_output(src, src.parent / name, overwrite=True)
        assert why and "named like an update file (JH_*.bin)" in why and "pack" in why, name
    # names that only look similar are fine
    for name in ("muted.bin", "JH5408.bin", "my_JH_copy.bin", "JH_5408.bin.txt"):
        assert _check_output(src, src.parent / name, overwrite=False) is None, name


def test_check_output_refuses_a_folder_that_looks_like_a_card(src, card, tmp_path):
    why = _check_output(src, card / "muted.bin", overwrite=False)
    assert why and "has a DCIM folder, so it looks like a camera card" in why
    (card / "DCIM").rename(card / "dcim")             # FAT is case-insensitive, and so is the check
    assert "has a DCIM folder" in _check_output(src, card / "muted.bin", overwrite=False)
    # a card set up to flash, without DCIM: an update file in the folder is enough
    fresh = tmp_path / "fresh"
    fresh.mkdir()
    (fresh / "jh_5408202610261427_0F065D55.BIN").write_bytes(b"x")
    why = _check_output(src, fresh / "muted.bin", overwrite=False)
    assert why and "holds an update file (jh_5408202610261427_0F065D55.BIN)" in why
    # a folder named like an update file is not an update file; a folder deep inside DCIM is not the card root
    other = tmp_path / "other"
    (other / "JH_folder.bin").mkdir(parents=True)
    assert _check_output(src, other / "muted.bin", overwrite=False) is None
    assert _check_output(src, card / "dcim" / "100MEDIA" / "muted.bin", overwrite=False) is None


def test_the_input_check_still_comes_first(src):
    assert "is the input file" in _check_output(src, src, overwrite=True)


def run(*argv):
    return cli.main([str(a) for a in argv])


@pytest.mark.parametrize("argv", [
    ("sounds", "mute", "{src}", "--method", "pcm"),
    ("settings", "set", "{src}", "--ui-sound", "0"),
    ("tweak", "apply", "{src}", "--enable", "boot-delay"),
])
def test_mod_commands_refuse_update_names_and_card_folders(argv, src, card, capsys):
    argv = [a.replace("{src}", str(src)) for a in argv]
    for out in [src.parent / n for n in UPDATE_NAMES] + [card / "muted.bin"]:
        assert run(*argv, "--out", out, GATE_FLAG) == 2, (argv[0], out)
        text = capsys.readouterr().out
        assert "named like an update file" in text or "looks like a camera card" in text, (argv[0], out, text)
        assert not out.exists()
    assert sorted(p.name for p in card.iterdir()) == ["DCIM"]
    assert sorted(p.name for p in src.parent.iterdir()) == ["backup.bin"]
    assert src.read_bytes() == minimal_image()


def test_sounds_and_settings_still_write_a_mod_to_an_ordinary_folder(src, tmp_path, capsys):
    out = tmp_path / "mods"
    out.mkdir()
    assert run("sounds", "mute", src, "--method", "pcm", "--out", out / "quiet.bin", GATE_FLAG) == 0
    assert run("settings", "set", src, "--ui-sound", "0", "--out", out / "set.bin", GATE_FLAG) == 0
    assert "wrote" in capsys.readouterr().out
    assert sorted(p.name for p in out.iterdir()) == ["quiet.bin", "set.bin"]
    assert settings.current_record((out / "set.bin").read_bytes()).ui_sound == 0
    # and the dry run applies the same rule: it names no update file and no card as its target
    assert run("sounds", "mute", src, "--method", "pcm", "--out", out / "JH_5408202611061200_00000000.bin") == 2
    assert "named like an update file" in capsys.readouterr().out


def test_help_says_where_a_mod_may_not_go():
    parser = cli.build_parser()
    for name in ("sounds", "settings", "tweak", "frames", "resources"):
        sub = next(a for a in parser._actions if a.dest == "command").choices[name]
        help_text = " ".join(sub.format_help().split())
        assert "JH_*.bin" in help_text and "DCIM" in help_text, name
