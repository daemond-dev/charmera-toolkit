"""Core tests: checksum, settings, image parsing, verify and pack logic.

Image-dependent tests run only when CHARMERA_STOCK_IMAGE points at a local original-series dump.
Everything that writes uses synthetic images so no real firmware file is produced by the test run.
"""
import dataclasses
import hashlib
import os
import struct
import zlib
from pathlib import Path

import pytest

from charmera_toolkit import checksum, frames, image, pack, settings, sounds, verify


# Synthetic images carry no firmware bytes, so they hold stand-ins for the build marks, placed where the public
# build has them and shaped like the real ones: the build string and the compile date, the stamp with its NUL, a
# source path that names the public build's folder, and a sensor program (the reset table, a 61-write init table
# that starts with a page select, switches page once more and ends with ff ff, and the code literal that points at
# it). The init table's hash is not a known build's, and neither is the vector table's (one ARM instruction, then
# 0xFF), so the synthetic_build fixture registers both as the public build's where a test wants a file accepted
# without --stock. Tests on the real dump use the real identity.
SENSOR_RESET_OFFSET = 0xF5BFC
SENSOR_START, SENSOR_END = 0xF5C6C, 0xF5CE8
SENSOR_POINTER = 0x7B91C
SYNTHETIC_SENSOR_TABLE = bytes(b for pair in [(0xFD, 0x00)] + [(r, (r * 3) & 0xFF) for r in range(0x20, 0x20 + 58)]
                               + [(0xFD, 0x01), (0x11, 0x80)] for b in pair) + b"\xff\xff"
assert len(SYNTHETIC_SENSOR_TABLE) == SENSOR_END - SENSOR_START == 124
SYNTHETIC_VECTORS = b"\x18\xf0\x9f\xe5" + b"\xff" * 60      # bytes 0x200..0x240 of synthetic_image()
SOURCE_PATH_AT = 0x3A4C0       # where the public dump's first source path starts; its folder name begins at 0x3A4D2


def source_path(folder: bytes) -> bytes:
    """A source path shaped like the firmware's ("G:\\code\\2247\\5408\\v006\\host\\..."), with its NUL."""
    return b"G:\\code\\2247\\5408\\" + folder + b"\\host\\init.c\x00"


def put_build_marks(img: bytearray) -> None:
    """The public build's marks at its offsets: detect_build reads 'public-dump-modified' where the synthetic_build
    fixture registers the synthetic sensor table as that build's, 'charmera-unknown-build' elsewhere."""
    b = image.PUBLIC_BUILD
    img[b.stamp_offset: b.stamp_offset + 13] = b.stamp.encode() + b"\x00"
    img[b.build_offset: b.build_offset + 20] = b"JH5408  20250520\x00\x00\x00\x00"
    img[0xF3978: 0xF3978 + 12] = b"Oct 14 2025\x00"
    path = source_path(b.source_tree.encode())
    img[SOURCE_PATH_AT: SOURCE_PATH_AT + len(path)] = path
    img[SENSOR_RESET_OFFSET: SENSOR_RESET_OFFSET + 6] = image.SENSOR_RESET_TABLE
    img[SENSOR_START: SENSOR_END] = SYNTHETIC_SENSOR_TABLE
    img[SENSOR_POINTER: SENSOR_POINTER + 4] = struct.pack("<I", SENSOR_START - image.VA_OFFSET)


@pytest.fixture
def synthetic_build(monkeypatch):
    """The public build's identity with the synthetic sensor and vector tables in place of the real ones."""
    fake = dataclasses.replace(image.PUBLIC_BUILD, sensor_table_sha256=hashlib.sha256(SYNTHETIC_SENSOR_TABLE).hexdigest(),
                               vector_table_sha256=hashlib.sha256(SYNTHETIC_VECTORS).hexdigest())
    monkeypatch.setitem(image.KNOWN_BUILDS, fake.key, fake)
    return fake


# --- checksum ------------------------------------------------------------------------------------------


def test_byte_sum_and_names():
    assert checksum.byte_sum(b"\x01\x02\x03\x04") == 10
    assert checksum.byte_sum(b"\xff" * 0x1000000) == (0xFF * 0x1000000) & 0xFFFFFFFF
    n = checksum.parse_update_name("JH_5408202610261427_0F065D55.bin")
    assert (n.product, n.stamp, n.checksum) == ("5408", "202610261427", 0x0F065D55)
    assert n.filename == "JH_5408202610261427_0F065D55.bin"
    with pytest.raises(ValueError):
        checksum.parse_update_name("FW_5408202610261427_0F065D55.bin")
    with pytest.raises(ValueError):
        checksum.build_update_name(b"\x00", stamp=checksum.STOCK_TIMESTAMP)
    assert checksum.build_update_name(b"\x00" * 100, stamp="202610261200").filename == "JH_5408202610261200_00000000.bin"


# --- settings ------------------------------------------------------------------------------------------


def make_record(ui_sound=5, playback=5):
    body = bytearray(252)
    body[0] = 1
    body[settings.OFF_PLAYBACK_VOLUME] = playback
    body[settings.OFF_UI_SOUND] = ui_sound
    return bytes(body) + (zlib.crc32(bytes(body)) & 0xFFFFFFFF).to_bytes(4, "little")


def synthetic_image(records=(), with_sounds=True):
    """A fake 2 MiB image with a GP+ pack holding five tiny WAVs and the given settings records."""
    img = bytearray(b"\xff" * checksum.FLASH_SIZE)
    img[0:4] = b"SPII"
    img[0x200:0x240] = SYNTHETIC_VECTORS
    put_build_marks(img)   # a changed copy of the public build to detect_build (the software hash cannot match)
    if with_sounds:
        base = image.RESOURCE_BASE
        img[base: base + 0x18] = b"GP+\x00\x03\x00\x03\x00" + b"\x00" * 16
        names = ["BEEPWAV", "CLICKWAV", "IMGWAV", "POWEROFF_AUDIOWAV", "POWERON_AUDIOWAV", "ZZZGPZP"]
        sector = 4
        off = base + 0x18
        for i, n in enumerate(names):
            img[off: off + 20] = n.encode().ljust(20, b"\x00")
            img[off + 20: off + 24] = struct.pack("<I", sector)
            if n != "ZZZGPZP":
                bits = 16 if n == "BEEPWAV" else 8
                rate = 11025 if n in ("BEEPWAV", "IMGWAV") else 8000
                data = bytes([0x80 if bits == 8 else 0x11]) * 400
                wav = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * bits // 8, bits // 8, bits) + b"data" + struct.pack("<I", len(data)) + data
                p = base + sector * 0x200
                img[p: p + len(wav)] = wav
            else:
                p = base + sector * 0x200
                gpzp = frames.encode_gpzp(b"\x00" * 64)   # a real fixed-Huffman stream, as verify expects
                img[p: p + len(gpzp)] = gpzp
            off += 24
            sector += 2
    for i, rec in enumerate(records):
        o = settings.SETTINGS_BASE + i * settings.RECORD_STRIDE
        img[o: o + 256] = rec
        img[o + 256: o + 512] = b"\x00" * 256
    img[settings.PHOTO_DIRECTORY: settings.PHOTO_DIRECTORY + 0x200] = b"\x00" * 0x200
    return bytes(img)


def test_settings_records_and_current():
    img = synthetic_image([make_record(5), make_record(3), b"\x00" * 256])
    recs = settings.parse_settings(img)
    assert len(recs) == 3
    assert [r.crc_ok for r in recs] == [True, True, False]
    # the firmware loads the sector before the first erased one; the zero record is not erased, so it is
    # selected and fails its CRC (the camera would fall back to defaults)
    assert settings.firmware_current_sector(img) == settings.SETTINGS_BASE + 2 * settings.RECORD_STRIDE
    with pytest.raises(ValueError):
        settings.current_record(img)
    img2 = synthetic_image([make_record(5), make_record(3)])
    cur = settings.current_record(img2)
    assert cur.ui_sound == 3
    new = cur.with_byte(settings.OFF_UI_SOUND, 0)
    assert new.crc_ok and new.ui_sound == 0 and new.playback_volume == 5
    assert settings.diff_records(cur, new) == [settings.OFF_UI_SOUND]


def test_set_ui_sound_rewrites_store():
    img = synthetic_image([make_record(5), make_record(4)])
    out = sounds.set_ui_sound(img, 0)
    recs = settings.parse_settings(out)
    assert len(recs) == 1 and recs[0].crc_ok and recs[0].ui_sound == 0
    assert out[settings.SETTINGS_BASE + 256: settings.SETTINGS_BASE + 512] == b"\x00" * 256
    assert settings.current_record(out).ui_sound == 0
    assert out[: settings.SETTINGS_BASE] == img[: settings.SETTINGS_BASE]
    assert out[settings.SETTINGS_END:] == img[settings.SETTINGS_END:]
    # an image with an erased directory gets a zeroed one
    bad = bytearray(img); bad[settings.PHOTO_DIRECTORY: settings.PHOTO_DIRECTORY + 0x200] = b"\xff" * 0x200
    assert not settings.photo_directory_consistent(bytes(bad))
    fixed = sounds.set_ui_sound(bytes(bad), 0)
    assert settings.photo_directory_consistent(fixed) and fixed[settings.PHOTO_DIRECTORY: settings.PHOTO_DIRECTORY + 8] == b"\x00" * 8


def test_mute_pcm_and_replace(tmp_path):
    img = synthetic_image([make_record()])
    slots = image.sound_slots(img)
    assert {s.entry.name for s in slots} == set(image.SOUND_SLOTS)
    muted = sounds.mute_pcm(img)
    for s in image.sound_slots(muted):
        assert muted[s.data_offset: s.data_offset + s.data_len] == bytes([s.silence_byte]) * s.data_len
        assert muted[s.entry.offset: s.data_offset] == img[s.entry.offset: s.data_offset]
    # replacement: a 1 kHz-ish stereo 16-bit 44.1 kHz clip is converted to the slot's format and length
    import wave
    p = tmp_path / "clip.wav"
    with wave.open(str(p), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(44100)
        w.writeframes(b"".join(struct.pack("<hh", (i % 50) * 600 - 15000, (i % 50) * 600 - 15000) for i in range(2000)))
    rep, note = sounds.replace_sound(img, "BEEP.WAV", p)
    assert "ms" in note
    s = [x for x in image.sound_slots(rep) if x.entry.name == "BEEPWAV"][0]
    assert s.data_len == 400 and rep[s.entry.offset: s.data_offset] == img[s.entry.offset: s.data_offset]
    assert rep[s.data_offset: s.data_offset + s.data_len] != img[s.data_offset: s.data_offset + s.data_len]
    with pytest.raises(ValueError):
        sounds.replace_sound(img, "NOPE.WAV", p)


def test_pack_prepare_and_verify_synthetic(tmp_path):
    img = synthetic_image([make_record()])
    payload = pack.prepare_payload(img)
    assert len(payload) == checksum.UPDATE_FILE_SIZE
    name = checksum.build_update_name(payload, "202611041200")
    path = tmp_path / name.filename
    checks = verify.verify_update(path, payload)
    by = {c.name: c for c in checks}
    assert by["filename"].ok and by["checksum"].ok and by["stamp"].ok and by["size"].ok and by["magic"].ok
    assert by["series"].ok and not by["software-region"].ok  # synthetic software region is not stock
    # a renamed file fails the checksum
    bad = tmp_path / "JH_5408202611041200_00000001.bin"
    assert not {c.name: c for c in verify.verify_update(bad, payload)}["checksum"].ok
    # a 2 MiB file is flagged, and the extra-erase arithmetic matches the verified updater
    full = {c.name: c for c in verify.verify_update(tmp_path / name.filename, img)}
    assert "size-2mib" in full and not full["size-2mib"].ok and not full["final-erase"].ok
    assert image.updater_extra_erase(0x1E0000) == 0x1F0000
    assert image.updater_extra_erase(0x169000) == 0x170000
    assert image.updater_extra_erase(0x170000) == 0x180000
    assert image.updater_extra_erase(0x200000) == 0x210000
    assert image.updater_extra_erase(0x168E9A) == 0x170000
    odd = {c.name: c for c in verify.verify_update(tmp_path / name.filename, payload[:-1])}
    assert not odd["size"].ok
    # blanking photos leaves a zeroed directory, blanking settings leaves the bank erased
    blank = pack.prepare_payload(img, blank_settings=True, blank_photos=True)
    assert settings.parse_settings(blank) == []
    assert blank[settings.PHOTO_DIRECTORY: settings.PHOTO_DIRECTORY + 0x200] == b"\x00" * 0x200
    assert blank[settings.PHOTO_DIRECTORY + 0x200: settings.PHOTO_DIRECTORY + 0x400] == b"\xff" * 0x200
    with pytest.raises(ValueError):
        pack.prepare_payload(img[:-1] + b"\x00")


# --- against the real stock image -----------------------------------------------------------------------


def test_stock_image_identity(stock_image):
    info = image.summarize(stock_image)
    assert info["kind"] == "dump" and info["magic_ok"]
    assert info["identity"] == "public-dump" and all(info["identity_checks"].values())
    assert set(info["identity_checks"]) == {"stamp", "build_string", "compiled", "code_sha256", "sensor_table", "software_sha256",
                                            "sensor_table_referenced", "sensor_table_known", "fingerprint"}
    assert info["software_sha256"] == image.PUBLIC_BUILD.software_sha256
    m = info["marks"]
    assert (m.stamp, m.stamp_offset, m.build_offset, m.compiled_offset) == ("202505201029", 0x218C4, 0x18964, 0xF3978)
    assert m.sensor.init == (0xF5C6C, 0xF5CE8) and m.sensor.reset == (0xF5BFC, 0xF5C02) and m.sensor.pairs == 61
    assert m.sensor.bus_address == 0x78 and m.sensor.referenced_from == 0x7B91C
    assert m.sensor.sha256 == image.PUBLIC_BUILD.sensor_table_sha256
    assert info["official_fingerprint"][1:] == (image.PUBLIC_BUILD.official_sha1, image.PUBLIC_BUILD.official_byte_sum)
    assert info["tail_blank"] is True
    assert len(info["resources"]) == 43
    names = [s.entry.display_name for s in info["sounds"]]
    assert names == ["BEEP.WAV", "CLICK.WAV", "IMG.WAV", "POWEROFF_AUDIO.WAV", "POWERON_AUDIO.WAV"]
    fmts = {s.entry.display_name: (s.rate, s.bits, s.data_len) for s in info["sounds"]}
    assert fmts["BEEP.WAV"] == (11025, 16, 4032)
    assert fmts["IMG.WAV"] == (11025, 8, 5480)
    assert fmts["POWERON_AUDIO.WAV"] == (8000, 8, 6097)
    assert info["current_settings"].ui_sound == 5 and info["current_settings"].playback_volume == 5
    assert info["resources_end"] == 0x168E9A
    # the published stock update file is exactly the first 0x1E0000 bytes with byte sum 0F065D55 for the
    # keychain-camera-plus dump; the Project-Sirius dump differs only above 0x170000
    payload = pack.prepare_payload(stock_image)
    if info["sha256"] == "cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f":
        assert checksum.byte_sum(payload) == 0x0F065D55


def test_stock_image_mute_paths_do_not_touch_code(stock_image):
    muted = sounds.mute_pcm(stock_image)
    diff = [i for i in range(0, len(stock_image), 0x10000) if muted[i: i + 0x10000] != stock_image[i: i + 0x10000]]
    assert all(0x120000 <= b < 0x170000 for b in diff)
    quiet = sounds.set_ui_sound(stock_image, 0)
    diff = [i for i in range(0, len(stock_image), 0x10000) if quiet[i: i + 0x10000] != stock_image[i: i + 0x10000]]
    assert diff == [0x170000]
    assert settings.current_record(quiet).ui_sound == 0


# --- review follow-ups -------------------------------------------------------------------------------------


def test_verify_tags_and_product_code(tmp_path, capsys, synthetic_build):
    from charmera_toolkit import verify as verify_mod
    img = synthetic_image([make_record()])
    payload = pack.prepare_payload(img)
    # a 0x170000-byte file is a software-only image, not one of the two sizes the camera takes: refused. Its final
    # erase lands on the photo area, a WARN line of its own.
    short = payload[:0x170000]
    name = checksum.build_update_name(short, "202611041200")
    p = tmp_path / name.filename
    p.write_bytes(short)
    args = type("A", (), {"file": str(p), "stock": None, "allow_modified": True, "allow_unknown_build": False, "allow_odd_size": False})
    rc = verify_mod.handler(args)
    out = capsys.readouterr().out
    assert rc == 1
    assert any(line.startswith("FAIL size") and "software-only image" in line for line in out.splitlines())
    assert any(line.startswith("WARN final-erase") for line in out.splitlines())
    # wrong product code is a FAIL
    bad = tmp_path / ("JH_1234202611041200_%08X.bin" % checksum.byte_sum(payload))
    bad.write_bytes(payload)
    by = {c.name: c for c in verify_mod.verify_update(bad, payload, allow_modified=True)}
    assert not by["product"].ok
    # missing --stock is an error, not a traceback
    args2 = type("A", (), {"file": str(p), "stock": str(tmp_path / "nope.bin"), "allow_modified": True, "allow_unknown_build": False, "allow_odd_size": False})
    assert verify_mod.handler(args2) == 2
    # 256-aligned but too short gets the right explanation
    tiny = payload[:0x160000]
    by = {c.name: c for c in verify_mod.verify_update(tmp_path / "JH_5408202611041200_00000000.bin", tiny, allow_modified=True)}
    assert not by["size"].ok and "shorter than 0x169000" in by["size"].detail
    assert not by["software-region"].ok and "ends at" in by["software-region"].detail


def test_sounds_handler_refusals(tmp_path, capsys):
    img = synthetic_image([make_record()])
    src = tmp_path / "backup.bin"
    src.write_bytes(img)
    base = {"file": str(src), "out": None, "method": "settings", "base_record": "current", "slot": None, "wav": None, "gain": 1.0, "overwrite": False, "gate": True}

    def run(**kw):
        a = dict(base); a.update(kw)
        return sounds.handler(type("A", (), a))

    assert run(action="mute") == 2                       # no --out
    assert run(action="mute", out=str(src)) == 2         # out == input
    assert src.read_bytes() == img
    assert run(action="replace", out=str(tmp_path / "x.bin")) == 2   # no slot/wav
    other = tmp_path / "exists.bin"
    other.write_bytes(b"x")
    assert run(action="mute", out=str(other)) == 2       # exists without --overwrite
    assert other.read_bytes() == b"x"
    assert run(action="mute", out=str(other), overwrite=True) == 0
    blank = bytearray(img); blank[settings.SETTINGS_BASE: settings.SETTINGS_END] = b"\xff" * (settings.SETTINGS_END - settings.SETTINGS_BASE)
    (tmp_path / "blank.bin").write_bytes(bytes(blank))
    assert run(action="mute", file=str(tmp_path / "blank.bin"), out=str(tmp_path / "m.bin")) == 2
    assert "error:" in capsys.readouterr().out


def test_resample_box_filter_and_replace_note(tmp_path):
    import math
    img = synthetic_image([make_record()])
    p = tmp_path / "tone.wav"
    import wave
    with wave.open(str(p), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(44100)
        w.writeframes(b"".join(struct.pack("<h", int(20000 * math.sin(2 * math.pi * 6000 * i / 44100))) for i in range(4410)))
    out, note = sounds.replace_sound(img, "CLICK.WAV", p)
    assert "the rest is cut" in note or "padded" in note
    s = [x for x in image.sound_slots(out) if x.entry.name == "CLICKWAV"][0]
    pcm = out[s.data_offset: s.data_offset + s.data_len]
    # a 6 kHz tone has no business in an 8 kHz slot: after the box filter the energy must be small
    samples = [b - 128 for b in pcm[:400]]
    assert max(abs(v) for v in samples) < 40


def test_pack_stamp_consistency(tmp_path, monkeypatch):
    img = synthetic_image([make_record()])
    src = tmp_path / "in.bin"
    src.write_bytes(img)
    stamps = iter(["202611041200", "202611041201", "202611041202"])
    monkeypatch.setattr(pack, "now_stamp", lambda: next(stamps))
    args = type("A", (), {"input": str(src), "out": str(tmp_path / "o"), "stamp": None, "blank_settings": False, "blank_photos": False,
                          "factory_settings": False, "allow_unknown_build": False, "overwrite": False, "gate": True})
    assert pack.handler(args) == 0
    files = sorted(p.name for p in (tmp_path / "o").iterdir())
    assert files == ["JH_5408202611041200_%08X.bin" % checksum.byte_sum(pack.prepare_payload(img)),
                     files[0] + ".MANIFEST.txt", files[0] + ".sha256"]
    assert files[0] in (tmp_path / "o" / files[1]).read_text()
    args.stamp = "202611041200"
    assert pack.handler(args) == 2   # same name exists, no --overwrite
    args.overwrite = True
    assert pack.handler(args) == 0


def test_settings_command(tmp_path, capsys):
    from charmera_toolkit import settings_cmd
    img = synthetic_image([make_record(), make_record(4, 3)])
    src = tmp_path / "in.bin"; src.write_bytes(img)
    base = {"file": str(src), "all": False, "out": None, "base_record": "current", "ui_sound": None, "playback_volume": None,
            "auto_off": None, "standby": None, "overwrite": False, "gate": True}
    def run(**kw):
        a = dict(base); a.update(kw); return settings_cmd.handler(type("A", (), a))
    assert run(action="show") == 0
    assert "the camera will load this one" in capsys.readouterr().out
    assert run(action="set") == 2                                   # nothing to change
    assert run(action="set", ui_sound=0) == 2                       # no --out
    assert run(action="set", ui_sound=9, out=str(tmp_path / "o.bin")) == 2   # out of range
    assert run(action="set", ui_sound=0, auto_off=10, standby=1, out=str(tmp_path / "o.bin")) == 0
    out = (tmp_path / "o.bin").read_bytes()
    cur = settings.current_record(out)
    assert (cur.ui_sound, cur.raw[settings.OFF_AUTO_OFF], cur.raw[settings.OFF_STANDBY], cur.playback_volume) == (0, 10, 1, 3)
    assert len(settings.parse_settings(out)) == 1
    assert run(action="set", ui_sound=0, out=str(tmp_path / "o.bin")) == 2    # exists


def test_tweaks_apply_and_refuse(tmp_path, capsys):
    from charmera_toolkit import tweaks
    img = bytearray(synthetic_image([make_record()]))
    # plant the original bytes of every patch so the synthetic build looks like stock at those sites
    for p in tweaks.PATCHES.values():
        for off, orig, _new in p.edits:
            img[off: off + len(orig)] = orig
    img = bytes(img)
    new, applied = tweaks.apply_patches(img, ["boot-delay", "fast-poweroff"])
    assert applied == ["boot-delay", "fast-poweroff"]
    assert new[0x62B8: 0x62BC] == b"\x02\x00\xa0\xe3" and new[0x2319C: 0x231A0] == b"\x05\x00\xa0\xe3"
    rec, rec_applied = tweaks.apply_patches(img, ["recommended", "boot-delay"])
    assert rec_applied == ["boot-delay", "lcd-poll", "fast-poweroff"]
    assert rec[0x6544: 0x6555] == img[0x6544: 0x6555] and rec[0x64F8: 0x64FC] == img[0x64F8: 0x64FC]   # logo kept
    assert sum(1 for a, b in zip(img, new) if a != b) == 3
    with pytest.raises(ValueError):
        tweaks.apply_patches(new, ["boot-delay"])          # already applied
    with pytest.raises(ValueError):
        tweaks.apply_patches(img, ["nope"])
    src = tmp_path / "s.bin"; src.write_bytes(img)
    base = {"file": str(src), "enable": "boot-delay", "out": str(tmp_path / "t.bin"), "accept_high_risk": False, "overwrite": False, "gate": True}
    def run(**kw):
        a = dict(base); a.update(kw); return tweaks.handler(type("A", (), a))
    assert run(action="list") == 0
    assert run(action="apply", enable="boot-to-photo") == 2       # high risk without the flag
    assert run(action="apply", enable="boot-to-photo", accept_high_risk=True, out=str(tmp_path / "h.bin")) == 0
    assert run(action="apply") == 0
    assert (tmp_path / "t.bin").read_bytes()[0x62B8: 0x62BC] == b"\x02\x00\xa0\xe3"
    # a modified build (software hash differs but stamps present) is still accepted; a non-stock byte is refused
    bad = bytearray(img); bad[0x62B8] = 0x07
    (tmp_path / "b.bin").write_bytes(bytes(bad))
    assert run(action="apply", file=str(tmp_path / "b.bin"), out=str(tmp_path / "x.bin")) == 2
    assert "wrong build or already modified" in capsys.readouterr().out


def test_diff_command_regions_and_settings(tmp_path, capsys):
    from charmera_toolkit import diff_cmd
    base = synthetic_image([make_record(), make_record(ui_sound=3)])
    beep = next(e for e in image.resource_directory(base) if e.display_name == "BEEP.WAV").offset
    mod = bytearray(base)
    mod[0x62B8] = 0x02                       # one code byte
    mod[beep + 100: beep + 110] = bytes(b ^ 0xFF for b in base[beep + 100: beep + 110])   # inside BEEP.WAV
    mod = bytes(mod)
    count, ranges, truncated = diff_cmd.differing_ranges(base, mod, 0)
    assert not truncated
    assert count == 11 and ranges[0] == (0x62B8, 0x62B9) and ranges[1] == (beep + 100, beep + 110)
    regions = {r.name: r for r in diff_cmd.diff_regions(base, mod)}
    assert regions["code and tables"].differing == 1
    assert regions["resource pack (GP+)"].differing == 10
    assert regions["settings bank"].differing == 0
    (tmp_path / "a.bin").write_bytes(base); (tmp_path / "b.bin").write_bytes(mod)
    args = type("A", (), {"a": str(tmp_path / "a.bin"), "b": str(tmp_path / "b.bin"), "ranges": True})
    assert diff_cmd.handler(args) == 1
    out = capsys.readouterr().out
    assert "INCLUDING CODE" in out and "BEEP.WAV: 10 differing" in out and "0x0062b8-0x0062b9" in out
    # identical files exit 0; a settings-only change is explained field by field
    args.b = args.a
    assert diff_cmd.handler(args) == 0
    from charmera_toolkit.sounds import set_ui_sound
    quiet = set_ui_sound(base, 0)
    (tmp_path / "q.bin").write_bytes(quiet)
    args.b = str(tmp_path / "q.bin")
    assert diff_cmd.handler(args) == 1
    out = capsys.readouterr().out
    assert "+0x19   3 ->   0" in out and "differ only in settings or photos" in out


def test_diff_ranges_cross_chunk_boundaries_and_report_truncation():
    from charmera_toolkit import diff_cmd
    a = bytes(3 * 4096)
    b = bytearray(a)
    b[4090:4100] = b"\x01" * 10            # one run across the first chunk boundary
    b[8192 - 1] = 1                         # a run ending exactly at a boundary
    count, runs, truncated = diff_cmd.differing_ranges(a, bytes(b), 0x100)
    assert count == 11 and not truncated
    assert runs == [(0x100 + 4090, 0x100 + 4100), (0x100 + 8191, 0x100 + 8192)]
    many = bytearray(a)
    for k in range(0, 40, 2):
        many[k] = 1
    count, runs, truncated = diff_cmd.differing_ranges(a, bytes(many), 0, limit=8)
    assert count == 20 and len(runs) == 8 and truncated
    # a run that crosses a boundary after the limit is reached still sets the flag
    edge = bytearray(a)
    for k in range(0, 16, 2):
        edge[k] = 1
    edge[4095:4097] = b"\x01\x01"
    count, runs, truncated = diff_cmd.differing_ranges(a, bytes(edge), 0, limit=8)
    assert len(runs) == 8 and truncated


def test_diff_names_non_code_software_changes(tmp_path, capsys):
    from charmera_toolkit import diff_cmd
    base = synthetic_image([make_record()])
    heap = bytearray(base); heap[0x110010] ^= 0xFF
    (tmp_path / "a.bin").write_bytes(base); (tmp_path / "b.bin").write_bytes(bytes(heap))
    args = type("A", (), {"a": str(tmp_path / "a.bin"), "b": str(tmp_path / "b.bin"), "ranges": False})
    assert diff_cmd.handler(args) == 1
    out = capsys.readouterr().out
    assert "software region differs in: heap placeholder (zeros)" in out and "resource pack only" not in out
    assert "INCLUDING CODE" not in out


def test_output_checks_refuse_hard_links_dirs_and_missing_folders(tmp_path):
    from charmera_toolkit.sounds import _check_output
    src = tmp_path / "backup.bin"; src.write_bytes(b"x")
    link = tmp_path / "link.bin"
    try:
        os.link(src, link)
    except (OSError, AttributeError):
        pytest.skip("hard links not available here")
    assert "is the input file" in _check_output(src, link, overwrite=True)
    assert "is a directory" in _check_output(src, tmp_path, overwrite=True)
    assert "does not exist" in _check_output(src, tmp_path / "nope" / "out.bin", overwrite=False)
    assert _check_output(src, tmp_path / "new.bin", overwrite=False) is None


def test_truncated_images_are_reported_not_tracebacks(tmp_path, capsys):
    from charmera_toolkit import cli
    img = synthetic_image([make_record()])
    beep = next(e for e in image.resource_directory(img) if e.display_name == "BEEP.WAV").offset
    cut = tmp_path / "cut.bin"; cut.write_bytes(img[: beep + 20])
    assert cli.main(["inspect", str(cut)]) == 0
    assert "cannot be read" in capsys.readouterr().out
    assert cli.main(["sounds", "list", str(cut)]) == 2
    assert "error:" in capsys.readouterr().out
    bank = tmp_path / "bank.bin"; bank.write_bytes(img[: settings.SETTINGS_BASE + 0x400])
    assert cli.main(["inspect", str(bank)]) == 0
    out = capsys.readouterr().out
    assert "None" not in out


def test_tweak_apply_argument_errors(tmp_path, capsys):
    from charmera_toolkit import cli
    assert cli.main(["tweak", "apply", "--enable", "boot-delay", "--out", str(tmp_path / "o.bin")]) == 2
    assert "needs the image" in capsys.readouterr().out
    src = tmp_path / "s.bin"; src.write_bytes(synthetic_image([make_record()]))
    assert cli.main(["tweak", "apply", str(src), "--enable", " , ", "--out", str(tmp_path / "o.bin")]) == 2
    assert "names no tweak" in capsys.readouterr().out


def test_pack_out_must_be_a_directory(tmp_path, capsys):
    from charmera_toolkit import cli
    src = tmp_path / "s.bin"; src.write_bytes(synthetic_image([make_record()]))
    afile = tmp_path / "muted.bin"; afile.write_bytes(b"x")
    rc = cli.main(["pack", "--input", str(src), "--out", str(afile), "--allow-unknown-build", "--stamp", "202611050001",
                   "--i-understand-this-file-flashes-a-camera"])
    assert rc == 2
    assert "--out must be a directory" in capsys.readouterr().out


# --- flash-path hardening ----------------------------------------------------------------------------------


def _named(tmp_path, payload, stamp="202611041200"):
    p = tmp_path / checksum.build_update_name(payload, stamp).filename
    p.write_bytes(payload)
    return p


def _entry(img, display_name):
    return next(e for e in image.resource_directory(img) if e.display_name == display_name)


def test_stamps_and_names_take_ascii_digits_only(tmp_path):
    for stamp in ("٢٠٢٦١١٠٥٠٠٠٠",   # Arabic-Indic
                  "２０２６１１０５００００",   # full-width
                  "²⁰²⁶¹¹⁰⁵⁰⁰⁰⁰"):  # superscript
        with pytest.raises(ValueError, match="ASCII"):
            checksum.build_update_name(b"\x00" * 16, stamp)
        name = f"JH_5408{stamp}_00000000.bin"
        with pytest.raises(ValueError):
            checksum.parse_update_name(name)
        by = {c.name: c for c in verify.verify_update(tmp_path / name, b"\x00" * 16)}
        assert not by["filename"].ok
    with pytest.raises(ValueError):
        checksum.parse_update_name("JH_5408202611041200_00000000.bin\n")
    assert checksum.parse_update_name("JH_5408202611041200_0000ABCD.bin").checksum == 0xABCD


def test_pack_refuses_a_non_ascii_stamp(tmp_path, capsys):
    from charmera_toolkit import cli
    src = tmp_path / "s.bin"; src.write_bytes(synthetic_image([make_record()]))
    rc = cli.main(["pack", "--input", str(src), "--out", str(tmp_path / "o"), "--stamp", "２" * 12,
                   "--i-understand-this-file-flashes-a-camera"])
    assert rc == 2 and "ASCII" in capsys.readouterr().out
    assert not (tmp_path / "o").exists()


def test_image_kind_calls_a_short_file_truncated():
    assert image.image_kind(b"\xff" * checksum.FLASH_SIZE) == "dump"
    assert image.image_kind(b"\xff" * checksum.UPDATE_FILE_SIZE) == "update"
    assert image.image_kind(b"\xff" * image.SOFTWARE_END) == "software-only"
    for n in (0, 0x100, image.RESOURCE_BASE, 0x166000, image.SOFTWARE_END - 0x100, image.SOFTWARE_END + 0x100, 0x1DFF00):
        assert image.image_kind(b"\xff" * n) == "truncated", hex(n)
    assert image.image_kind(b"\xff" * (checksum.UPDATE_FILE_SIZE + 0x100)) == "unknown"


def test_pack_refuses_a_truncated_input_and_shows_the_padding(tmp_path, capsys):
    from charmera_toolkit import cli
    img = synthetic_image([make_record()])
    for n in (image.RESOURCE_BASE, 0x166000, image.SOFTWARE_END - 0x100, image.SOFTWARE_END + 0x10000, checksum.UPDATE_FILE_SIZE - 0x100):
        with pytest.raises(ValueError, match="looks truncated"):
            pack.prepare_payload(img[:n])
    cut = tmp_path / "partial_download.bin"; cut.write_bytes(img[:0x166000])
    assert cli.main(["pack", "--input", str(cut), "--out", str(tmp_path / "o"), "--stamp", "202611050001",
                     "--i-understand-this-file-flashes-a-camera"]) == 2
    assert "looks truncated" in capsys.readouterr().out and not (tmp_path / "o").exists()
    # exactly 0x170000 is a software-only image: accepted, and the plan says what was padded
    sw = tmp_path / "software.bin"; sw.write_bytes(img[:image.SOFTWARE_END])
    payload = pack.prepare_payload(img[:image.SOFTWARE_END])
    assert len(payload) == checksum.UPDATE_FILE_SIZE and payload[:image.SOFTWARE_END] == img[:image.SOFTWARE_END]
    assert cli.main(["pack", "--input", str(sw), "--out", str(tmp_path / "o"), "--stamp", "202611050001"]) == 0
    out = capsys.readouterr().out
    assert "PADDED with 458,752 bytes (0x170000..0x1e0000)" in out
    full = tmp_path / "dump.bin"; full.write_bytes(img)
    assert cli.main(["pack", "--input", str(full), "--out", str(tmp_path / "o"), "--stamp", "202611050001"]) == 0
    assert "trimmed to 0x1e0000" in capsys.readouterr().out


def test_pack_plan_reports_damaged_resources(tmp_path, capsys):
    from charmera_toolkit import cli
    img = bytearray(synthetic_image([make_record()]))
    z = _entry(bytes(img), "ZZZ.GPZP")
    img[z.offset: z.offset + 0x200] = b"\xff" * 0x200
    src = tmp_path / "s.bin"; src.write_bytes(bytes(img))
    for gate in ([], ["--i-understand-this-file-flashes-a-camera"]):
        assert cli.main(["pack", "--input", str(src), "--out", str(tmp_path / "o"), "--stamp", "202611050001", *gate]) == 2
        out = capsys.readouterr().out
        assert "resources:       DAMAGED" in out and "ZZZ.GPZP" in out and "Refusing to pack it" in out
    assert not (tmp_path / "o").exists()                        # nothing written, even with the gate


def test_verify_fails_a_changed_sensor_table_unless_allow_unknown_build(tmp_path, capsys, synthetic_build):
    payload = pack.prepare_payload(synthetic_image([make_record()]))
    by = {c.name: c for c in verify.verify_update(_named(tmp_path, payload), payload, allow_modified=True)}
    assert by["sensor-table"].ok
    bad = bytearray(payload); bad[SENSOR_START + 4] ^= 1
    bad = bytes(bad)
    p = _named(tmp_path, bad, "202611041201")
    by = {c.name: c for c in verify.verify_update(p, bad, allow_modified=True)}
    assert not by["sensor-table"].ok and by["sensor-table"].severity == "fail" and "stage never accepts it" in by["sensor-table"].detail
    by = {c.name: c for c in verify.verify_update(p, bad, allow_modified=True, allow_unknown_build=True)}
    assert by["sensor-table"].severity == "warn"
    args = type("A", (), {"file": str(p), "stock": None, "allow_modified": True, "allow_unknown_build": False, "allow_odd_size": False})
    assert verify.handler(args) == 1
    assert "FAIL sensor-table" in capsys.readouterr().out
    args.allow_unknown_build = True
    assert verify.handler(args) == 0
    assert "WARN sensor-table" in capsys.readouterr().out


def _gpzp_slot_problem(img, data):
    """Put `data` in the last entry's slot (it runs to 0x16E000, so there is room) and check the image."""
    z = _entry(img, "ZZZ.GPZP")
    buf = bytearray(img)
    span = max(0x400, len(data))
    buf[z.offset: z.offset + span] = data + b"\xff" * (span - len(data))
    assert len(buf) == len(img)
    return verify.resource_problems(bytes(buf))


def test_resource_problems_checks_every_entry_against_its_name():
    img = synthetic_image([make_record()])
    assert verify.resource_problems(img) == []
    beep, z = _entry(img, "BEEP.WAV"), _entry(img, "ZZZ.GPZP")

    def damaged(edit):
        buf = bytearray(img); edit(buf); return verify.resource_problems(bytes(buf))

    # an erased slot, a WAV that is not RIFF, an oversized RIFF size, a WAV without a data chunk
    assert any("BEEP.WAV" in p and "erased" in p for p in damaged(lambda b: b.__setitem__(slice(beep.offset, beep.offset + beep.budget), b"\xff" * beep.budget)))
    assert any("not a RIFF WAVE" in p for p in damaged(lambda b: b.__setitem__(slice(beep.offset, beep.offset + 1), b"X")))
    assert any("RIFF size says" in p for p in damaged(lambda b: b.__setitem__(slice(beep.offset + 4, beep.offset + 8), struct.pack("<I", 0x2000))))
    assert any("no data chunk" in p for p in damaged(lambda b: b.__setitem__(slice(beep.offset + 36, beep.offset + 40), b"junk")))
    # GPZP: a stream that does not inflate, a bad trailer CRC, a dynamic-Huffman stream, a stored block, an erased last entry
    good = frames.encode_gpzp(bytes(range(256)) * 4)
    assert _gpzp_slot_problem(img, good) == []
    assert any("corrupt" in p or "ends before" in p for p in _gpzp_slot_problem(img, good[:4] + b"\xff\xff" + good[6:]))
    assert any("CRC" in p for p in _gpzp_slot_problem(img, good[:-8] + struct.pack("<II", 0, 1024)))
    payload = bytes((i * i * 7919 >> 3) % 26 + 97 for i in range(4000))   # skewed letters: zlib picks a dynamic block
    for strategy, kind in ((zlib.Z_DEFAULT_STRATEGY, "dynamic"), (None, "stored")):
        if strategy is None:
            c = zlib.compressobj(0, zlib.DEFLATED, -15)
        else:
            c = zlib.compressobj(9, zlib.DEFLATED, -15, 8, strategy)
        stream = c.compress(payload) + c.flush()
        blob = b"GPZP" + stream + struct.pack("<II", zlib.crc32(payload) & 0xFFFFFFFF, len(payload))
        assert frames.parse_gpzp(blob).payload == payload
        assert any(f"{kind} DEFLATE blocks" in p for p in _gpzp_slot_problem(img, blob)), kind
    assert any("ZZZ.GPZP" in p and "erased" in p for p in damaged(lambda b: b.__setitem__(slice(z.offset, z.offset + 0x200), b"\xff" * 0x200)))
    # a name of no known kind, a JPEG name over non-JPEG bytes, a JPEG without its end marker, a .PAK without GPRS
    def rename(buf, new):
        o = image.RESOURCE_BASE + 0x18 + 24 * z.index
        buf[o: o + 20] = new.ljust(20, b"\x00")
    assert any("no known kind" in p for p in damaged(lambda b: rename(b, b"ZZZBMP")))
    assert any("not a JPEG" in p for p in damaged(lambda b: rename(b, b"ZZZJPG")))
    def jpeg_without_eoi(buf):
        rename(buf, b"ZZZJPG"); buf[z.offset: z.offset + 0x200] = b"\xff\xd8\xff\xe0" + b"\x00" * 0x1FC
    assert any("no FF D9 end marker" in p for p in damaged(jpeg_without_eoi))
    def jpeg_ok(buf):
        rename(buf, b"ZZZJPG"); buf[z.offset: z.offset + 0x200] = b"\xff\xd8\xff\xe0" + b"\x00" * 0x1FA + b"\xff\xd9"
    assert damaged(jpeg_ok) == []
    assert any("not a GPRS pack" in p for p in damaged(lambda b: rename(b, b"ZZZPAK")))
    def gprs_ok(buf):
        rename(buf, b"ZZZPAK"); buf[z.offset: z.offset + 4] = b"GPRS"
    assert damaged(gprs_ok) == []
    # a slot that points past the resource area, and one that is empty because the next entry starts at the same sector
    assert any("outside the resource area" in p for p in damaged(lambda b: b.__setitem__(
        slice(image.RESOURCE_BASE + 0x18 + 24 * z.index + 20, image.RESOURCE_BASE + 0x18 + 24 * z.index + 24), struct.pack("<I", 0x800))))
    assert any("empty slot" in p for p in damaged(lambda b: b.__setitem__(
        slice(image.RESOURCE_BASE + 0x18 + 24 * beep.index + 20, image.RESOURCE_BASE + 0x18 + 24 * beep.index + 24),
        struct.pack("<I", (_entry(img, "CLICK.WAV").offset - image.RESOURCE_BASE) // 0x200))))


def test_verify_fails_damaged_resources_even_with_allow_modified(tmp_path, capsys, synthetic_build):
    img = bytearray(synthetic_image([make_record()]))
    z = _entry(bytes(img), "ZZZ.GPZP")
    img[z.offset: z.offset + 0x200] = b"\xff" * 0x200
    payload = pack.prepare_payload(bytes(img))
    p = _named(tmp_path, payload)
    by = {c.name: c for c in verify.verify_update(p, payload, allow_modified=True, allow_unknown_build=True, allow_odd_size=True)}
    assert not by["resource-slots"].ok and by["resource-slots"].severity == "fail" and "ZZZ.GPZP" in by["resource-slots"].detail
    args = type("A", (), {"file": str(p), "stock": None, "allow_modified": True, "allow_unknown_build": True, "allow_odd_size": True})
    assert verify.handler(args) == 1
    assert "FAIL resource-slots" in capsys.readouterr().out
    clean = pack.prepare_payload(synthetic_image([make_record()]))
    assert {c.name: c for c in verify.verify_update(_named(tmp_path, clean), clean, allow_modified=True)}["resource-slots"].ok


def test_diff_reports_an_erased_last_resource_entry(tmp_path, capsys):
    from charmera_toolkit import diff_cmd
    a = pack.prepare_payload(synthetic_image([make_record()]))
    z = _entry(a, "ZZZ.GPZP")
    assert z.index == len(image.resource_directory(a)) - 1          # the last entry
    b = bytearray(a); b[z.offset: z.offset + 0x200] = b"\xff" * 0x200
    b = bytes(b)
    assert _entry(b, "ZZZ.GPZP").budget == 0                       # its budget in B is 0: the old diff skipped it
    lines = diff_cmd.explain_resources(a, b)
    assert any("ZZZ.GPZP: ERASED in B" in line for line in lines)
    assert not any("byte-identical" in line for line in lines)
    (tmp_path / "a.bin").write_bytes(a); (tmp_path / "b.bin").write_bytes(b)
    args = type("A", (), {"a": str(tmp_path / "a.bin"), "b": str(tmp_path / "b.bin"), "ranges": False})
    assert diff_cmd.handler(args) == 1
    out = capsys.readouterr().out
    assert "ZZZ.GPZP: ERASED in B" in out and "every resource entry is byte-identical" not in out
    # a raw cut-off file: the missing part of each entry is named
    cut = a[: z.offset + 8]
    assert any("ZZZ.GPZP" in line and "MISSING from B" in line for line in diff_cmd.explain_resources(a, cut))
    # a change outside every entry is not called identical
    hdr = bytearray(a); hdr[image.RESOURCE_BASE + 4] ^= 1
    assert diff_cmd.explain_resources(a, bytes(hdr)) == [
        "  every resource entry is byte-identical; the differing bytes lie outside the entries "
        "(pack header, directory, or the padding after the last entry)"]


def test_pack_out_refuses_a_card_or_a_folder_with_another_update_file(tmp_path, capsys):
    from charmera_toolkit import cli
    src = tmp_path / "s.bin"; src.write_bytes(synthetic_image([make_record()]))
    gate = "--i-understand-this-file-flashes-a-camera"
    card = tmp_path / "card"; (card / "DCIM").mkdir(parents=True)
    assert cli.main(["pack", "--input", str(src), "--out", str(card), "--stamp", "202611050001", gate]) == 2
    out = capsys.readouterr().out
    assert "DCIM" in out and "charmera stage" in out
    assert sorted(p.name for p in card.iterdir()) == ["DCIM"]
    out_dir = tmp_path / "o"
    assert cli.main(["pack", "--input", str(src), "--out", str(out_dir), "--stamp", "202611050001", gate]) == 0
    first = sorted(p.name for p in out_dir.iterdir())
    assert cli.main(["pack", "--input", str(src), "--out", str(out_dir), "--stamp", "202611050002", gate, "--overwrite"]) == 2
    assert "already holds another update file" in capsys.readouterr().out
    assert sorted(p.name for p in out_dir.iterdir()) == first
    # the same name again is the existing rule: refused without --overwrite, replaced with it
    assert cli.main(["pack", "--input", str(src), "--out", str(out_dir), "--stamp", "202611050001", gate]) == 2
    assert cli.main(["pack", "--input", str(src), "--out", str(out_dir), "--stamp", "202611050001", gate, "--overwrite"]) == 0
    # a lower-case jh_*.BIN counts too, as it does for stage
    other = tmp_path / "o2"; other.mkdir(); (other / "jh_5408000000000000_00000000.BIN").write_bytes(b"x")
    assert cli.main(["pack", "--input", str(src), "--out", str(other), "--stamp", "202611050001", gate]) == 2


def test_factory_settings_keeps_photos_and_says_so(tmp_path, capsys):
    import argparse
    img = bytearray(synthetic_image([make_record(), make_record(ui_sound=3)]))
    img[settings.PHOTO_DIRECTORY: settings.PHOTO_DIRECTORY + 8] = struct.pack("<HHI", 0, 1, 0x100)
    img[image.PHOTO_SLOTS[0]: image.PHOTO_SLOTS[0] + 4] = b"\xff\xd8\xff\xd9"
    img = bytes(img)
    out = pack.prepare_payload(img, factory_settings=True)
    recs = settings.parse_settings(out)
    assert len(recs) == 1 and recs[0].offset == settings.SETTINGS_BASE and recs[0].ui_sound == 5
    assert out[settings.PHOTO_DIRECTORY: settings.PHOTO_DIRECTORY + 8] == img[settings.PHOTO_DIRECTORY: settings.PHOTO_DIRECTORY + 8]
    assert image.stored_photos(out) == image.stored_photos(img) != []
    both = pack.prepare_payload(img, factory_settings=True, blank_photos=True)
    assert image.stored_photos(both) == [] and both[settings.PHOTO_DIRECTORY: settings.PHOTO_DIRECTORY + 8] == b"\x00" * 8
    parser = argparse.ArgumentParser(prog="charmera")
    pack.register(parser.add_subparsers(dest="command"))
    text = " ".join(parser._subparsers._group_actions[0].choices["pack"].format_help().split())
    assert "stored photos are kept (add --blank-photos to remove them)" in text
    assert "and zero the photo directory" not in text
    assert "zeroes the photo directory only when that sector is erased" in " ".join(pack.prepare_payload.__doc__.split())


def test_tweak_refuses_a_cut_off_image(tmp_path, capsys):
    from charmera_toolkit import cli
    src = tmp_path / "cut.bin"; src.write_bytes(synthetic_image([make_record()])[:0x166000])
    assert cli.main(["tweak", "apply", str(src), "--enable", "recommended", "--out", str(tmp_path / "o.bin")]) == 2
    assert "cut-off download" in capsys.readouterr().out
    assert not (tmp_path / "o.bin").exists()


# --- official-size update files ---------------------------------------------------------------------------------


def test_pack_official_size_and_verify(tmp_path, synthetic_build):
    img = synthetic_image([make_record(), make_record(ui_sound=3)])
    official = pack.prepare_payload(img, official_size=True)
    assert len(official) == checksum.OFFICIAL_UPDATE_SIZE and official == img[:checksum.OFFICIAL_UPDATE_SIZE]
    assert image.image_kind(official) == "official-update"
    assert image.updater_extra_erase(len(official)) == settings.SETTINGS_BASE
    p = _named(tmp_path, official, "202611061200")
    # against the camera's own dump: everything passes, and the file carries nothing of the camera's own area
    by = {c.name: c for c in verify.verify_update(p, official, stock=img)}
    assert by["size"].ok and by["size"].severity == "info" and "the official size" in by["size"].detail
    assert "no official update file has ever been published" in by["size"].detail
    assert by["final-erase"].ok and by["final-erase"].severity == "warn" and "factory defaults" in by["final-erase"].detail
    assert by["sensor-table"].ok and by["sensor-table"].severity == "fail"       # a plain PASS
    assert by["software-region"].ok and "byte-identical to --stock" in by["software-region"].detail
    assert by["own-area"].severity == "info" and "settings" not in by and "photos" not in by
    assert by["resources"].severity == "warn" and by["resource-slots"].ok and by["sounds"].ok   # 6 synthetic entries, not 43
    assert not [c for c in by.values() if not c.ok and c.severity == "fail"]
    # without --stock the known (here: registered) table is a WARN, as in the app, and the software region names the build
    by = {c.name: c for c in verify.verify_update(p, official)}
    assert by["sensor-table"].ok and by["sensor-table"].severity == "warn"
    assert not by["software-region"].ok and "--stock" in by["software-region"].detail
    # an official-size file is also a valid input: padded like a software-only image
    back = pack.prepare_payload(official)
    assert len(back) == checksum.UPDATE_FILE_SIZE and back[:len(official)] == official
    assert settings.parse_settings(back) == [] and back[settings.PHOTO_DIRECTORY: settings.PHOTO_DIRECTORY + 8] == b"\x00" * 8
    # a resource pack that reaches past 0x169000 cannot be cut
    big = bytearray(img)
    big[checksum.OFFICIAL_UPDATE_SIZE + 0x100] = 0x55
    with pytest.raises(ValueError, match="past the official size"):
        pack.prepare_payload(bytes(big), official_size=True)


def test_pack_official_size_from_the_command_line(tmp_path, capsys, synthetic_build):
    from charmera_toolkit import cli
    src = tmp_path / "s.bin"; src.write_bytes(synthetic_image([make_record()]))
    assert cli.main(["pack", "--input", str(src), "--out", str(tmp_path / "o"), "--stamp", "202611061200", "--official-size"]) == 0
    out = capsys.readouterr().out
    assert "the official size, as the film-mods files" in out and "final erase:     block 0x170000" in out
    assert "settings:        none in the file" in out and "stored photos:   none in the file" in out
    assert cli.main(["pack", "--input", str(src), "--out", str(tmp_path / "o"), "--stamp", "202611061200", "--official-size",
                     "--i-understand-this-file-flashes-a-camera"]) == 0
    files = list((tmp_path / "o").glob("JH_*.bin"))
    assert len(files) == 1 and files[0].stat().st_size == checksum.OFFICIAL_UPDATE_SIZE


def test_stock_image_official_size_fingerprint(stock_image, tmp_path):
    """The public dump cut at 0x169000 carries the fingerprint the film-mods author compared against."""
    official = pack.prepare_payload(stock_image, official_size=True)
    assert hashlib.sha1(official).hexdigest() == image.PUBLIC_BUILD.official_sha1
    assert checksum.byte_sum(official) == image.PUBLIC_BUILD.official_byte_sum
    p = _named(tmp_path, official, "202611061200")
    by = {c.name: c for c in verify.verify_update(p, official, stock=stock_image)}
    assert not [c for c in by.values() if not c.ok and c.severity == "fail"]
    assert "the public dump's build" in by["build"].detail and by["build"].severity == "info"
    assert image.detect_build(official).identity == "public-dump"


def test_verify_names_the_build_and_says_what_a_match_proves(tmp_path, synthetic_build):
    payload = pack.prepare_payload(synthetic_image([make_record()]))
    p = _named(tmp_path, payload)
    by = {c.name: c for c in verify.verify_update(p, payload, allow_modified=True)}
    assert by["series"].ok and "identifies neither" in by["series"].detail
    assert by["build"].ok and "the public dump's build" in by["build"].detail and "not whether it fits the camera" in by["build"].detail
    assert by["stamp-in-file"].severity == "info" and "flashes it again at every power-on" in by["stamp-in-file"].detail
    # a file whose marks sit elsewhere is Charmera firmware of an unknown build: a warning, never a stamp-based pass
    moved = bytearray(payload)
    moved[0x218C3: 0x218D1] = b"\x00" * 14
    moved[0x218D0: 0x218DD] = b"202505201029\x00"
    moved = bytes(moved)
    m = image.detect_build(moved)
    assert m.charmera and m.build is None and m.identity == "charmera-unknown-build"
    by = {c.name: c for c in verify.verify_update(_named(tmp_path, moved, "202611041201"), moved, allow_modified=True)}
    assert by["build"].severity == "warn" and "does not know" in by["build"].detail


def test_the_compile_date_is_read_and_shown(tmp_path, synthetic_build):
    payload = pack.prepare_payload(synthetic_image([make_record()]))
    marks = image.find_marks(payload)
    assert marks.compiled == b"Oct 14 2025" and marks.compiled_offset == 0xF3978
    by = {c.name: c for c in verify.verify_update(_named(tmp_path, payload), payload, allow_modified=True)}
    assert "compiled Oct 14 2025 at 0xf3978" in by["series"].detail and "compile dates differ" in by["series"].detail
    assert image.PUBLIC_BUILD.compiled == "Oct 14 2025" and image.FILM_MODS_BUILD.compiled == "Nov 19 2025"


def _with_source_paths(img, folders, at=SOURCE_PATH_AT):
    """The image with one source path per folder, 0x40 bytes apart from `at`, the first over the stand-in path."""
    out = bytearray(img)
    for i, folder in enumerate(folders):
        raw = source_path(folder)
        out[at + 0x40 * i: at + 0x40 * i + len(raw)] = raw
    return bytes(out)


def _without_source_paths(img):
    """The image with the stand-in source path erased: no folder at all."""
    out = bytearray(img)
    out[SOURCE_PATH_AT: SOURCE_PATH_AT + 0x40] = b"\xff" * 0x40
    return bytes(out)


def test_the_source_tree_folder_is_a_build_mark_and_is_shown(tmp_path, capsys, synthetic_build):
    """S15: the folder after \\2247\\5408\\ in the source paths, the one most of them name; a different folder shows a
    different build, the same one does not show the same build, and neither shows a fit."""
    import json
    from charmera_toolkit import cli
    assert (image.PUBLIC_BUILD.source_tree, image.FILM_MODS_BUILD.source_tree) == ("v006", "v008_test")
    plain = pack.prepare_payload(synthetic_image([make_record()]))
    m = image.find_marks(plain)
    assert (m.source_tree, m.source_tree_offset, m.source_tree_paths) == ("v006", SOURCE_PATH_AT + 18, 1)   # the stand-in
    bare = _without_source_paths(plain)
    m = image.find_marks(bare)
    assert (m.source_tree, m.source_tree_offset, m.source_tree_paths) == (None, None, 0)
    assert "no source tree found" in _checks(_named(tmp_path, bare), bare, allow_modified=True)["series"].detail
    assert "source tree:     NOT FOUND" in pack.describe(bare, "202611061430")
    payload = _with_source_paths(plain, [b"v009", b"v006", b"v006"])
    m = image.find_marks(payload)
    assert (m.source_tree, m.source_tree_offset, m.source_tree_paths) == ("v006", 0x3A500 + 18, 2)
    assert m.complete == image.find_marks(plain).complete           # not one of the marks a Charmera build must carry
    series = _checks(_named(tmp_path, payload, "202611061431"), payload, allow_modified=True)["series"].detail
    assert "source tree v006 at 0x3a512" in series and "so do their source trees" in series
    assert "the same one does not show the same build" in series and "says whether the file fits a camera" in series
    text = pack.describe(payload, "202611061431")
    assert "source tree:     v006 at 0x3a512, named by 2 source paths (public-dump: v006, film-mods: v008_test)" in text
    assert image.SOURCE_TREE_PROVES in text
    (tmp_path / "tree.bin").write_bytes(payload)
    assert cli.main(["inspect", str(tmp_path / "tree.bin")]) == 0
    out = capsys.readouterr().out
    assert "source tree:     v006  at 0x3a512, named by 2 source paths" in out and image.SOURCE_TREE_PROVES in out
    assert cli.main(["inspect", "--json", str(tmp_path / "tree.bin")]) == 0
    marks = json.loads(capsys.readouterr().out)["marks"]
    assert (marks["source_tree"], marks["source_tree_offset"], marks["source_tree_paths"]) == ("v006", 0x3A512, 2)


def test_stock_image_source_tree(stock_image, tmp_path):
    m = image.find_marks(stock_image)
    assert (m.source_tree, m.source_tree_paths) == (image.PUBLIC_BUILD.source_tree, 27) == ("v006", 27)
    assert stock_image[m.source_tree_offset - 11: m.source_tree_offset] == b"\\2247\\5408\\"
    assert m.source_tree_offset == 0x3A4D2                              # where the folder's name begins, as the app reports it
    restore = stock_image[: checksum.UPDATE_FILE_SIZE]
    by = _checks(_named(tmp_path, restore, "202611061432"), restore)
    assert f"source tree v006 at {m.source_tree_offset:#x}" in by["series"].detail


def test_a_folder_name_holds_any_printable_byte_but_the_backslash(synthetic_build):
    """S15, as the app finds it: the folder after \\2247\\5408\\ is 1 to 64 bytes of 0x20..0x7E other than the backslash
    that ends it, a space included, and it is reported at the offset where its name begins."""
    bare = _without_source_paths(pack.prepare_payload(synthetic_image([make_record()])))
    spaced = _with_source_paths(bare, [b"v006 rc 1", b"v006 rc 1", b"v006"])
    m = image.find_marks(spaced)
    assert (m.source_tree, m.source_tree_offset, m.source_tree_paths) == ("v006 rc 1", SOURCE_PATH_AT + 18, 2)
    assert spaced[m.source_tree_offset - 11: m.source_tree_offset] == b"\\2247\\5408\\"
    for folder in (b"v00\x7f", b"v00\x1f", b"v00\xe9", b"v" * 65):          # none of these is a folder name
        assert image.find_marks(_with_source_paths(bare, [folder])).source_tree is None, folder
    assert image.find_marks(_with_source_paths(bare, [b"v" * 64])).source_tree == "v" * 64


def test_stock_image_with_another_source_tree_or_compile_date_is_laid_out_like_it(stock_image, tmp_path, capsys):
    """S15: a copy laid out like the public build is that build with changes only when its stamp, sensor table, source
    tree and compile date are that build's. The public dump with \\2247\\5408\\v006\\ changed to v007 in all 27 paths,
    or with another compile date, is a build this toolkit does not know, laid out like it, to verify (with and without
    --stock), inspect and pack; the mods still go by its layout. A copy with a changed resource is still that build
    with changes."""
    from charmera_toolkit import inspect_cmd
    old, new = b"\\2247\\5408\\v006\\", b"\\2247\\5408\\v007\\"
    tree = stock_image.replace(old, new)
    assert stock_image.count(old) == 27 and tree.count(new) == 27 and len(tree) == len(stock_image)
    at = image.find_marks(stock_image).compiled_offset
    assert stock_image[at: at + 12] == b"Oct 14 2025\x00"
    date = stock_image[:at] + b"Oct 15 2025" + stock_image[at + 11:]
    beep = _entry(stock_image, "BEEP.WAV")
    sound = bytearray(stock_image)
    sound[beep.offset + 0x40] ^= 1
    for dump, other in ((tree, "source tree"), (date, "compile date"), (bytes(sound), None)):
        payload = pack.prepare_payload(dump)
        match = image.detect_build(payload)
        assert match.build is image.PUBLIC_BUILD and match.layout is image.PUBLIC_BUILD and match.modified
        if other:
            label = (f"Charmera firmware of a build this toolkit does not know, laid out like {image.PUBLIC_BUILD.label}: its "
                     f"{other} is not that build's")
            identity, level = "charmera-unknown-build", "warn"
            assert match.made_from is None
        else:
            label, identity, level = f"{image.PUBLIC_BUILD.label} with changes", "public-dump-modified", "info"
            assert match.made_from is image.PUBLIC_BUILD
        assert (match.label, match.identity) == (label, identity), other
        path = tmp_path / checksum.build_update_name(payload, "202611061440").filename      # verify reads only its name
        for kw in ({}, {"stock": stock_image}):
            build = _checks(path, payload, allow_modified=True, **kw)["build"]
            assert build.severity == level and build.detail.startswith(label), (other, kw)
        assert inspect_cmd.print_report(path, payload) == 0
        assert f"build:           {identity}  ({label})" in capsys.readouterr().out
        assert f"build:           {identity} ({label})" in pack.describe(payload, "202611061440")


def test_pack_official_size_refuses_data_it_would_drop(synthetic_build):
    img = bytearray(synthetic_image([make_record()]))
    img[0x16E000: 0x16E004] = b"JHCM"       # a factory self-test sector that is not blank
    with pytest.raises(ValueError, match="would drop it"):
        pack.prepare_payload(bytes(img), official_size=True)
    assert len(pack.prepare_payload(bytes(img))) == checksum.UPDATE_FILE_SIZE    # the community size keeps it


# --- the donor route ------------------------------------------------------------------------------------------
#
# A camera whose own firmware is gone and does not fit it can take another, working camera's firmware: --donor is
# that camera's read, --stock this camera's own read as it is now. The synthetic donor is the synthetic image with
# another sensor table, as a camera fitted with another sensor would need.

DONOR_SENSOR_TABLE = bytes(b for pair in [(0xFD, 0x00)] + [(r, (r * 5 + 1) & 0xFF) for r in range(0x20, 0x20 + 58)]
                           + [(0xFD, 0x01), (0x11, 0x40)] for b in pair) + b"\xff\xff"
assert len(DONOR_SENSOR_TABLE) == SENSOR_END - SENSOR_START


def _donor(base, table=DONOR_SENSOR_TABLE):
    img = bytearray(base)
    img[SENSOR_START: SENSOR_END] = table
    return bytes(img)


def _donor_case(tmp_path):
    """This camera's read, a donor's read, and an official-size file made from the donor."""
    backup = synthetic_image([make_record()])
    donor = _donor(backup)
    payload = pack.prepare_payload(donor, official_size=True)
    return backup, donor, payload, _named(tmp_path, payload, "202611061300")


def _failed(checks):
    return [c.name for c in checks if not c.ok and c.severity == "fail"]


def test_a_donor_file_is_refused_against_the_cameras_backup_without_the_donor_route(tmp_path, synthetic_build):
    backup, donor, payload, p = _donor_case(tmp_path)
    by = {c.name: c for c in verify.verify_update(p, payload, stock=backup, allow_modified=True)}
    assert by["sensor-table"].severity == "fail" and "DIFFERS from --stock" in by["sensor-table"].detail


def test_the_donor_route_needs_the_cameras_own_backup(tmp_path, synthetic_build):
    backup, donor, payload, p = _donor_case(tmp_path)
    checks = verify.verify_update(p, payload, donor=donor)
    assert "donor" in _failed(checks)
    assert "no backup of your own camera, no flash" in next(c for c in checks if c.name == "donor").detail


def test_the_donor_route_takes_the_donors_firmware_and_says_the_fit_is_unknown(tmp_path, capsys, synthetic_build):
    backup, donor, payload, p = _donor_case(tmp_path)
    checks = verify.verify_update(p, payload, stock=backup, donor=donor)
    assert _failed(checks) == []
    by = {c.name: c for c in checks}
    assert by["from-donor"].ok and "unchanged" in by["from-donor"].detail
    assert by["sensor-table"].severity == "warn" and "Nothing here can tell" in by["sensor-table"].detail
    assert by["way-back"].severity == "warn" and "chip programmer" in by["way-back"].detail
    # the software region the route accepts is a warning, as --allow-modified's is; the vector table is --stock's
    assert by["software-region"].severity == "warn" and "0xf0000" in by["software-region"].detail
    assert by["vector-table"].ok and by["vector-table"].severity == "fail" and "--stock's" in by["vector-table"].detail
    backup_path, donor_path = tmp_path / "backup.bin", tmp_path / "donor.bin"
    backup_path.write_bytes(backup)
    donor_path.write_bytes(donor)
    args = type("A", (), {"file": str(p), "stock": str(backup_path), "donor": str(donor_path), "allow_modified": False,
                          "allow_unknown_build": False, "allow_odd_size": False})
    assert verify.handler(args) == 0
    out = capsys.readouterr().out
    assert "WARN sensor-table" in out and "Whether it fits this camera is not known" in out


def test_the_donor_route_says_what_the_source_tree_proves(tmp_path, synthetic_build):
    """S15 on the donor route: the series line and the donor line name the source tree with what it proves, as the
    normal series line does."""
    backup, donor, payload, p = _donor_case(tmp_path)
    by = _checks(p, payload, stock=backup, donor=donor)
    for name in ("series", "donor"):
        assert f"source tree v006 at {SOURCE_PATH_AT + 18:#x} ({image.SOURCE_TREE_PROVES})" in by[name].detail, name
    # a donor whose source paths name no folder says so, and nothing more
    bare = _without_source_paths(donor)
    official = pack.prepare_payload(bare, official_size=True)
    by = _checks(_named(tmp_path, official, "202611061314"), official, stock=backup, donor=bare)
    for name in ("series", "donor"):
        assert "no source tree found" in by[name].detail and image.SOURCE_TREE_PROVES not in by[name].detail, name


def test_the_donor_route_refuses_what_is_not_the_donors_firmware(tmp_path, synthetic_build):
    backup, donor, payload, p = _donor_case(tmp_path)
    changed = bytearray(payload)
    changed[0x30000] ^= 1
    changed = bytes(changed)
    assert "from-donor" in _failed(verify.verify_update(_named(tmp_path, changed, "202611061301"), changed, stock=backup, donor=donor))
    # a donor that is this camera's own firmware, or that is not a read of a chip
    assert "donor" in _failed(verify.verify_update(p, payload, stock=backup, donor=backup))
    checks = verify.verify_update(p, payload, stock=backup, donor=payload)
    assert "donor" in _failed(checks) and "update file" in next(c for c in checks if c.name == "donor").detail
    # and --stock must be a read of a chip
    assert "backup" in _failed(verify.verify_update(p, payload, stock=payload, donor=donor))


def test_the_donor_route_takes_a_donor_with_another_kind_of_sensor_table(tmp_path, synthetic_build):
    """A camera with another kind of sensor may keep its table in another form: the route still works, and says so."""
    backup = synthetic_image([make_record()])
    donor = bytearray(_donor(backup))
    donor[SENSOR_RESET_OFFSET: SENSOR_RESET_OFFSET + 6] = bytes.fromhex("fe0080ffffff")   # not the known builds' reset table
    donor = bytes(donor)
    assert image.find_sensor_tables(donor) is None and not image.detect_build(donor).charmera
    payload = pack.prepare_payload(donor, official_size=True)
    p = _named(tmp_path, payload, "202611061302")
    assert "series" in _failed(verify.verify_update(p, payload, stock=backup, allow_modified=True))
    checks = verify.verify_update(p, payload, stock=backup, donor=donor)
    assert _failed(checks) == []
    assert "no sensor table of the kind" in next(c for c in checks if c.name == "sensor-table").detail


def _moved_stamp(img):
    """The image with its stamp moved to 0x218D0, so its marks lie where no known build has them."""
    out = bytearray(img)
    out[0x218C3: 0x218D1] = b"\x00" * 14
    out[0x218D0: 0x218DD] = b"202505201029\x00"
    return bytes(out)


def _broken_vectors(img):
    out = bytearray(img)
    out[0x200:0x240] = bytes(b ^ 0xFF for b in out[0x200:0x240])
    return bytes(out)


def test_the_donor_route_refuses_a_donor_with_a_broken_vector_table(tmp_path, synthetic_build):
    """S5 on the donor route: the file is the donor's firmware unchanged, so a donor whose reset vector is broken would
    leave a camera the card updater cannot reach. Its vector table is compared as on the normal route."""
    backup = synthetic_image([make_record()])
    donor = _broken_vectors(_donor(backup))
    payload = pack.prepare_payload(donor, official_size=True)
    p = _named(tmp_path, payload, "202611061310")
    # laid out like --stock: compared with --stock's
    by = _checks(p, payload, stock=backup, donor=donor)
    assert by["from-donor"].ok
    v = by["vector-table"]
    assert not v.ok and v.severity == "fail" and "differs from --stock's" in v.detail and "no flag accepts it" in v.detail
    # --stock laid out otherwise: compared with the known build whose layout the file has
    moved = _moved_stamp(backup)
    assert image.find_marks(moved).layout != image.find_marks(donor).layout
    v = _checks(p, payload, stock=moved, donor=donor)["vector-table"]
    assert not v.ok and v.severity == "fail" and "that of the public dump's build" in v.detail and "whose layout the file has" in v.detail
    # an intact donor passes that line either way
    good = _donor(backup)
    good_payload = pack.prepare_payload(good, official_size=True)
    q = _named(tmp_path, good_payload, "202611061311")
    for stock in (backup, moved):
        checks = verify.verify_update(q, good_payload, stock=stock, donor=good)
        assert _failed(checks) == [] and next(c for c in checks if c.name == "vector-table").ok


def test_several_sensor_programs_in_stock_are_refused(tmp_path, synthetic_build):
    """S6: when --stock's code refers to several sensor programs, which one sets this camera's sensor up cannot be told,
    so nothing can be compared with it, on either route and whatever the flags."""
    backup = synthetic_image([make_record()])
    stock = _second_program(backup)
    assert len(image.find_marks(stock).several_sensor_programs) == 2
    payload = pack.prepare_payload(backup)
    p = _named(tmp_path, payload, "202611061415")
    for kw in ({}, {"allow_unknown_build": True}):
        s = _checks(p, payload, stock=stock, allow_modified=True, **kw)["sensor-table"]
        assert not s.ok and s.severity == "fail" and "several sensor programs in --stock" in s.detail and "no flag accepts it" in s.detail
        assert "referred to from 0x7b91c" in s.detail and "referred to from 0x7b000" in s.detail       # which programs
    donor = _donor(backup)
    official = pack.prepare_payload(donor, official_size=True)
    checks = verify.verify_update(_named(tmp_path, official, "202611061416"), official, stock=stock, donor=donor)
    assert "sensor-table" in _failed(checks)
    assert "several sensor programs in --stock" in next(c for c in checks if c.name == "sensor-table").detail
    # a file without a sensor table of the known kind is refused against such a --stock too
    no_table = bytearray(backup)
    no_table[SENSOR_RESET_OFFSET: SENSOR_RESET_OFFSET + 6] = bytes.fromhex("fe0080ffffff")
    none = pack.prepare_payload(bytes(no_table))
    s = _checks(_named(tmp_path, none, "202611061417"), none, stock=stock, allow_modified=True, allow_unknown_build=True)["sensor-table"]
    assert not s.ok and "several sensor programs in --stock" in s.detail


def test_stock_image_donor_route(stock_image, tmp_path):
    """The real dump as this camera's read, and the real dump with one sensor register changed as a donor's."""
    sensor = image.find_sensor_tables(stock_image)
    donor = bytearray(stock_image)
    donor[sensor.init[0] + 5] ^= 0x01            # the value of the third write
    donor = bytes(donor)
    assert image.find_sensor_tables(donor).sha256 != sensor.sha256
    payload = pack.prepare_payload(donor, official_size=True)
    p = _named(tmp_path, payload, "202611061303")
    by = {c.name: c for c in verify.verify_update(p, payload, stock=stock_image, allow_modified=True)}
    assert by["sensor-table"].severity == "fail"
    checks = verify.verify_update(p, payload, stock=stock_image, donor=donor)
    assert _failed(checks) == []
    by = {c.name: c for c in checks}
    assert "byte-identical to benbadio/keychain-camera-plus" in by["way-back"].detail
    assert by["vector-table"].ok and by["software-region"].severity == "warn"
    for name in ("series", "donor"):
        assert f"source tree v006 at 0x3a4d2 ({image.SOURCE_TREE_PROVES})" in by[name].detail, name
    # the same donor with its reset vector destroyed never passes (the round-one review's probe)
    broken = _broken_vectors(donor)
    payload = pack.prepare_payload(broken, official_size=True)
    checks = verify.verify_update(_named(tmp_path, payload, "202611061312"), payload, stock=stock_image, donor=broken)
    assert _failed(checks) == ["vector-table"]


def test_stock_image_as_stock_is_named_a_public_dump(stock_image, tmp_path):
    """A read of this camera after the stock restore flash can be byte-identical to the public dump: say what that means."""
    restore = pack.prepare_payload(stock_image)
    by = {c.name: c for c in verify.verify_update(_named(tmp_path, restore, "202611061304"), restore, stock=stock_image)}
    assert by["stock"].severity == "warn" and "byte-identical" in by["stock"].detail and "not what fits it" in by["stock"].detail


FILM_MODS_RESTORE = Path(os.environ.get("CHARMERA_FILM_MODS_RESTORE", ""))
# The name the film-mods release publishes the file under. The checks read the stamp and the sum from the name, so
# the tests give the bytes this name, whatever the local copy is called (CI saves it as film-restore.bin).
FILM_MODS_RESTORE_NAME = "JH_5408202505201029_0852C329.bin"


@pytest.mark.skipif(not FILM_MODS_RESTORE.is_file(), reason="set CHARMERA_FILM_MODS_RESTORE to the film-mods Charmera_Restore file")
def test_film_mods_restore_file_is_named_by_its_fingerprint(tmp_path):
    data = FILM_MODS_RESTORE.read_bytes()
    m = image.detect_build(data)
    assert m.exact and m.build.key == "film-mods" and m.build.status == "VERIFIED"
    assert m.marks.compiled == b"Nov 19 2025" and m.marks.stamp_offset == 0x218D0 and m.marks.build_offset == 0x1897C
    assert m.marks.sensor.sha256 == image.PUBLIC_BUILD.sensor_table_sha256
    # its stamp is the one both builds run, so a camera running either ignores it, and verify says so
    by = {c.name: c for c in verify.verify_update(tmp_path / FILM_MODS_RESTORE_NAME, data)}
    assert not by["stamp"].ok and "the charmera-film-mods author's build" in by["build"].detail
    # its vector table is the one image.py carries for that build, and its sensor literal is aligned
    assert image.vector_table_sha256(data) == image.FILM_MODS_BUILD.vector_table_sha256
    assert by["vector-table"].ok and by["vector-table"].severity == "fail"     # a plain PASS
    assert m.marks.sensor.referenced_from == 0x7B5A8 and m.marks.sensor.referenced_from % 4 == 0
    assert m.layout is image.FILM_MODS_BUILD


@pytest.mark.skipif(not FILM_MODS_RESTORE.is_file(), reason="set CHARMERA_FILM_MODS_RESTORE to the film-mods Charmera_Restore file")
def test_film_mods_restore_file_source_tree(tmp_path):
    """S15 on the second known build: another folder than the public build's, which shows another build."""
    data = FILM_MODS_RESTORE.read_bytes()
    m = image.find_marks(data)
    assert (m.source_tree, m.source_tree_paths) == (image.FILM_MODS_BUILD.source_tree, 27) == ("v008_test", 27)
    assert data[m.source_tree_offset - 11: m.source_tree_offset] == b"\\2247\\5408\\"
    assert f"source tree:     v008_test at {m.source_tree_offset:#x}, named by 27 source paths" in pack.describe(data, "202611061433")
    by = _checks(tmp_path / FILM_MODS_RESTORE_NAME, data)
    assert "source tree v008_test" in by["series"].detail


# --- the gates: backup, sizes, sensor programs, vector table, withdrawn files ------------------------------------------


def _checks(path, data, **kw):
    return {c.name: c for c in verify.verify_update(path, data, **kw)}


def test_without_stock_a_warning_says_nothing_compares_the_file_with_the_camera(tmp_path, capsys, synthetic_build):
    payload = pack.prepare_payload(synthetic_image([make_record()]))
    p = _named(tmp_path, payload)
    by = _checks(p, payload, allow_modified=True)
    assert by["backup"].severity == "warn" and "nothing here compares the file with the camera's own firmware" in by["backup"].detail
    assert "cannot be undone" in by["backup"].detail and "no flash" in by["backup"].detail
    # the known table without --stock is a WARN, as in the app, never INFO
    assert by["sensor-table"].severity == "warn"
    backup = tmp_path / "backup.bin"
    backup.write_bytes(synthetic_image([make_record()]))
    assert "backup" not in _checks(p, payload, stock=backup.read_bytes())
    args = type("A", (), {"file": str(p), "stock": None, "allow_modified": True, "allow_unknown_build": False, "allow_odd_size": False})
    assert verify.handler(args) == 0
    out = capsys.readouterr().out
    assert "WARN backup" in out and "No --stock was given" in out


def test_stock_must_be_a_complete_read_of_a_chip(tmp_path, capsys, synthetic_build):
    backup = synthetic_image([make_record()])
    payload = pack.prepare_payload(backup)
    p = _named(tmp_path, payload)
    assert "stock" not in _checks(p, payload, stock=backup)
    # the update file itself, a 2 MiB file that is not Charmera firmware, and a read whose build marks are gone
    no_spii = b"XXXX" + backup[4:]
    no_marks = bytearray(backup)
    no_marks[image.PUBLIC_BUILD.build_offset: image.PUBLIC_BUILD.build_offset + 6] = b"\xff" * 6
    for bad, why in ((payload, "this is an update file"), (no_spii, "does not start with SPII"), (bytes(no_marks), "build string missing")):
        by = _checks(p, payload, stock=bad, allow_modified=True)
        assert not by["stock"].ok and by["stock"].severity == "fail" and why in by["stock"].detail, why
    (tmp_path / "update.bin").write_bytes(payload)
    args = type("A", (), {"file": str(p), "stock": str(tmp_path / "update.bin"), "allow_modified": True, "allow_unknown_build": False,
                          "allow_odd_size": False})
    assert verify.handler(args) == 1
    assert "FAIL stock" in capsys.readouterr().out


def test_a_stock_named_like_an_unverified_read_is_warned_about(tmp_path, capsys, synthetic_build):
    """backup and restore keep a read under <label>_<time>.unverified-read<n>.bin until a second read agrees with it:
    reads that did not agree, or a single read. Such a read is not a backup."""
    from charmera_toolkit import flash
    assert verify.UNVERIFIED_READ == flash.UNVERIFIED_READ
    backup = synthetic_image([make_record()])
    payload = pack.prepare_payload(backup)
    p = _named(tmp_path, payload)
    w = _checks(p, payload, stock=backup, stock_name="charmera_20261106-120000Z.unverified-read1.bin")["stock-read"]
    assert w.ok and w.severity == "warn" and "such a read is not a backup" in w.detail and "until two reads agree" in w.detail
    assert "stock-read" not in _checks(p, payload, stock=backup, stock_name="charmera_20261106-120000Z.bin")
    read = tmp_path / "before-restore_20261106-120000Z.Unverified-Read2.bin"
    read.write_bytes(backup)
    args = type("A", (), {"file": str(p), "stock": str(read), "allow_modified": False, "allow_unknown_build": False,
                          "allow_odd_size": False})
    assert verify.handler(args) == 0
    assert "WARN stock-read" in capsys.readouterr().out
    # the donor route warns about both reads
    backup_, donor, official, q = _donor_case(tmp_path)
    by = _checks(q, official, stock=backup_, donor=donor, stock_name="x.unverified-read1.bin", donor_name="d.unverified-read1.bin")
    assert by["stock-read"].severity == "warn" and by["donor-read"].severity == "warn" and "--donor is d.unverified" in by["donor-read"].detail
    assert _failed(by.values()) == []


def test_sizes_other_than_the_two_the_camera_takes_are_refused(tmp_path, synthetic_build):
    payload = pack.prepare_payload(synthetic_image([make_record()]))
    for n in (0x160000, 0x16A000, 0x16F000, 0x170000, 0x175000, 0x17F000, 0x180000, 0x1C0000, 0x1D8000, 0x1EFF00, 0x1F0000):
        data = payload[:n] if n <= len(payload) else payload + b"\xff" * (n - len(payload))
        p = _named(tmp_path, data)
        by = _checks(p, data, allow_modified=True)
        assert not by["size"].ok and by["size"].severity == "fail", hex(n)
        assert "0x1e0000 and 0x169000" in by["size"].detail, hex(n)
        odd = _checks(p, data, allow_modified=True, allow_odd_size=True)
        assert odd["size"].ok and odd["size"].severity == "warn" and "stage never accepts it" in odd["size"].detail, hex(n)
        p.unlink()
    # each gets its own reason, not "shorter than 0x169000" for a longer one
    assert "neither the official size 0x169000 nor a software-only 0x170000 image" in _checks(tmp_path / "x.bin", payload[:0x16A000])["size"].detail
    assert "software-only image" in _checks(tmp_path / "x.bin", payload[:0x170000])["size"].detail
    assert "between the two update sizes" in _checks(tmp_path / "x.bin", payload[:0x1C0000])["size"].detail
    # what the camera holds after a 0x175000-byte flash: the settings record, and an erased photo directory
    by = _checks(tmp_path / "x.bin", payload[:0x175000], allow_odd_size=True)
    assert not by["photo-directory"].ok and "after the flash" in by["photo-directory"].detail
    # exactly 0x170000: the settings block is not touched, the final erase lands on the photo area
    by = _checks(tmp_path / "x.bin", payload[:0x170000], allow_odd_size=True)
    assert "settings stay as they are" in by["own-area"].detail and "0x180000" in by["own-area"].detail


def test_describe_erase_target_names_the_block_it_wraps_to():
    assert "wraps to block 0 (the boot header and the vector table)" in image.describe_erase_target(0x200000)
    assert "wraps to block 1" in image.describe_erase_target(0x210000)


def test_a_reference_of_a_known_build_gives_a_warning_for_a_matching_table(tmp_path, monkeypatch, synthetic_build):
    backup = synthetic_image([make_record()])
    payload = pack.prepare_payload(backup)
    p = _named(tmp_path, payload)
    # a read of a build nobody has published: a matching table is a PASS
    by = _checks(p, payload, stock=backup)
    assert by["sensor-table"].ok and by["sensor-table"].severity == "fail" and "proves" not in by["sensor-table"].detail
    # the same read, known by its fingerprint over 0..0x169000, as a read of a camera flashed with a public file is
    known = dataclasses.replace(synthetic_build, official_sha256=hashlib.sha256(backup[:checksum.OFFICIAL_UPDATE_SIZE]).hexdigest())
    monkeypatch.setitem(image.KNOWN_BUILDS, known.key, known)
    by = _checks(p, payload, stock=backup)
    assert by["sensor-table"].ok and by["sensor-table"].severity == "warn"
    assert "proves a fit only if --stock was read before the camera was ever flashed" in by["sensor-table"].detail
    assert "not what fits it" in by["sensor-table"].detail
    # with another program around the same table it does not even show that the file drives the sensor that way
    code = bytearray(payload)
    code[0x30000] ^= 1
    code = bytes(code)
    s = _checks(_named(tmp_path, code, "202611061400"), code, stock=backup, allow_modified=True)["sensor-table"]
    assert s.ok and s.severity == "warn" and "the program around it differs from --stock's" in s.detail
    assert "drives the sensor as the firmware in --stock does" not in s.detail
    # a table that differs from the reference still fails outside the donor route
    donor = pack.prepare_payload(_donor(backup))
    by = _checks(_named(tmp_path, donor, "202611061401"), donor, stock=backup, allow_modified=True)
    assert by["sensor-table"].severity == "fail" and "DIFFERS" in by["sensor-table"].detail


def test_a_reference_of_an_unknown_build_passes_a_matching_table_only_with_its_program(tmp_path, synthetic_build):
    """S3: against a read of a build nobody has published, the same table is a pass only when bytes 0..0x120000 are the
    read's too; a different program can drive the same sensor differently."""
    backup = synthetic_image([make_record()])
    payload = pack.prepare_payload(backup)
    s = _checks(_named(tmp_path, payload), payload, stock=backup)["sensor-table"]
    assert s.ok and s.severity == "fail" and "and so is the program around it (bytes 0..0x120000)" in s.detail
    code = bytearray(payload)
    code[0x30000] ^= 1                            # the program, not the table
    code = bytes(code)
    s = _checks(_named(tmp_path, code, "202611061419"), code, stock=backup, allow_modified=True)["sensor-table"]
    assert s.ok and s.severity == "warn" and "the same as in --stock" in s.detail
    assert "the program around it differs from --stock's" in s.detail and "a different program can drive the same sensor" in s.detail
    # a change in the resource pack leaves the program as it is: still a pass
    beep = next(e for e in image.resource_directory(payload) if e.display_name == "BEEP.WAV")
    sound = bytearray(payload)
    sound[beep.offset + 0x40] ^= 1
    sound = bytes(sound)
    s = _checks(_named(tmp_path, sound, "202611061420"), sound, stock=backup, allow_modified=True)["sensor-table"]
    assert s.ok and s.severity == "fail"


def test_allow_unknown_build_never_clears_a_table_that_differs_from_stock(tmp_path, capsys, synthetic_build):
    """S3: a table that differs from --stock's, or a --stock with no table of the known kind, is a FAIL outside the
    donor route whatever the flags."""
    from charmera_toolkit import cli
    backup = synthetic_image([make_record()])
    donor = pack.prepare_payload(_donor(backup))
    p = _named(tmp_path, donor, "202611061421")
    s = _checks(p, donor, stock=backup, allow_modified=True, allow_unknown_build=True, allow_odd_size=True)["sensor-table"]
    assert not s.ok and s.severity == "fail" and "DIFFERS" in s.detail and "no flag accepts it" in s.detail and "--donor" in s.detail
    no_table = bytearray(backup)
    no_table[SENSOR_RESET_OFFSET: SENSOR_RESET_OFFSET + 6] = bytes.fromhex("fe0080ffffff")
    payload = pack.prepare_payload(backup)
    q = _named(tmp_path, payload, "202611061422")
    s = _checks(q, payload, stock=bytes(no_table), allow_modified=True, allow_unknown_build=True)["sensor-table"]
    assert not s.ok and s.severity == "fail" and "--stock has none of the kind the known builds use" in s.detail
    assert "no flag accepts it" in s.detail
    stock_path = tmp_path / "backup.bin"
    stock_path.write_bytes(backup)
    args = type("A", (), {"file": str(p), "stock": str(stock_path), "allow_modified": True, "allow_unknown_build": True,
                          "allow_odd_size": False})
    assert verify.handler(args) == 1
    assert "FAIL sensor-table" in capsys.readouterr().out
    # without --stock the flag still accepts a table that is not the known builds' (test above), and its help says what it never does
    parser = cli.build_parser()
    text = " ".join(next(a for a in parser._actions if a.dest == "command").choices["verify"].format_help().split())
    assert ("It never accepts a table that differs from --stock's, a --stock without one, or a file without one checked against "
            "--stock: only the donor route (--donor) takes those") in text
    text = " ".join(next(a for a in parser._actions if a.dest == "command").choices["pack"].format_help().split())
    assert "stage refuses such a file outside the donor route (--donor)" in text


def test_stock_image_without_a_sensor_table_fails_against_a_stock_with_one(stock_image, tmp_path, capsys):
    """S3 amended, on the round-two review's probe: the dump with its reset table zeroed, packed, against a --stock with
    one settings byte changed, with every flag. A file with no sensor table of the known kind fails against a --stock
    that has one, outside the donor route, whatever the flags."""
    from charmera_toolkit import cli
    reset = image.find_sensor_tables(stock_image).reset
    dump = bytearray(stock_image)
    dump[reset[0]: reset[1]] = bytes(reset[1] - reset[0])
    payload = pack.prepare_payload(bytes(dump))
    assert image.find_marks(payload).sensor is None
    stock = bytearray(stock_image)
    stock[0x170010] ^= 0xFF
    ref = tmp_path / "settings-changed.bin"
    ref.write_bytes(bytes(stock))
    p = _named(tmp_path, payload, "202611061200")
    assert cli.main(["verify", str(p), "--stock", str(ref), "--allow-unknown-build", "--allow-modified", "--allow-odd-size"]) == 1
    out = capsys.readouterr().out
    line = next(line for line in out.splitlines() if line.startswith("FAIL sensor-table"))
    assert "while --stock has one (61 writes at 0xf5c6c" in line and "no flag accepts it" in line and "--donor" in line
    assert "WARN series" in out and "accepted because of --allow-unknown-build" in out


def test_a_broken_vector_table_is_refused_whatever_the_flags(tmp_path, synthetic_build):
    backup = synthetic_image([make_record()])
    payload = pack.prepare_payload(backup)
    bad = bytearray(payload)
    bad[0x200:0x240] = bytes(b ^ 0xFF for b in bad[0x200:0x240])
    bad = bytes(bad)
    p = _named(tmp_path, bad, "202611061402")
    for kw in ({}, {"stock": backup}):
        by = _checks(p, bad, allow_modified=True, allow_unknown_build=True, allow_odd_size=True, **kw)
        assert not by["vector-table"].ok and by["vector-table"].severity == "fail" and "no flag accepts it" in by["vector-table"].detail
        # the differing software region is a WARN where --allow-modified accepts it, never a PASS
        assert by["software-region"].ok and by["software-region"].severity == "warn"
    assert "--stock's" in _checks(p, bad, stock=backup)["vector-table"].detail
    by = _checks(_named(tmp_path, payload), payload, stock=backup)
    assert by["vector-table"].ok and by["vector-table"].severity == "fail"     # a plain PASS
    # laid out like no known build and not like --stock: the table cannot be compared, which is a warning
    moved = bytearray(bad)
    moved[0x218C3: 0x218D1] = b"\x00" * 14
    moved[0x218D0: 0x218DD] = b"202505201029\x00"
    moved = bytes(moved)
    assert image.detect_build(moved).layout is None
    for kw in ({}, {"stock": backup}):
        by = _checks(_named(tmp_path, moved, "202611061403"), moved, allow_modified=True, **kw)
        assert by["vector-table"].ok and by["vector-table"].severity == "warn" and "could not be compared" in by["vector-table"].detail


def _second_program(img, literal_at=0x7B000):
    """A second reset and init table at 0xF7000 with another init table, referred to from an aligned literal."""
    out = bytearray(img)
    out[0xF7000: 0xF7006] = image.SENSOR_RESET_TABLE
    out[0xF7006: 0xF7006 + len(DONOR_SENSOR_TABLE)] = DONOR_SENSOR_TABLE
    out[literal_at: literal_at + 4] = struct.pack("<I", 0xF7006 - image.VA_OFFSET)
    return bytes(out)


def test_several_sensor_programs_are_refused(tmp_path, capsys, synthetic_build):
    from charmera_toolkit import cli
    backup = synthetic_image([make_record()])
    two = pack.prepare_payload(_second_program(backup))
    marks = image.find_marks(two)
    assert len(marks.sensor_programs) == 2 and len(marks.several_sensor_programs) == 2
    p = _named(tmp_path, two, "202611061404")
    for kw in ({}, {"stock": backup}, {"stock": _second_program(backup)}):
        by = _checks(p, two, allow_modified=True, allow_unknown_build=True, **kw)
        assert not by["sensor-table"].ok and "several sensor programs" in by["sensor-table"].detail
    # the donor route refuses it too
    donor = _second_program(backup)
    two_official = pack.prepare_payload(donor, official_size=True)
    checks = verify.verify_update(_named(tmp_path, two_official, "202611061405"), two_official, stock=backup, donor=donor)
    assert "sensor-table" in _failed(checks)
    # a second table that no code refers to changes nothing
    stray = pack.prepare_payload(_second_program(backup, literal_at=0x7B001))
    assert image.find_marks(stray).several_sensor_programs == [] and image.find_marks(stray).sensor.init[0] == SENSOR_START
    # inspect lists both, pack says verify refuses the file
    (tmp_path / "two.bin").write_bytes(two)
    assert cli.main(["inspect", str(tmp_path / "two.bin")]) == 0
    out = capsys.readouterr().out
    assert "also found:" in out and "SEVERAL sensor programs" in out
    assert "SEVERAL sensor programs" in pack.describe(two, "202611061404")


def test_two_different_stamps_after_the_update_pattern_give_no_stamp(tmp_path, capsys, synthetic_build):
    """Which of two different stamps the updater compares cannot be told, so neither is taken."""
    from charmera_toolkit import cli
    img = bytearray(synthetic_image([make_record()]))
    stamp_at = image.PUBLIC_BUILD.stamp_offset
    img[stamp_at - 8: stamp_at - 2] = b"*.bin\x00"
    img[0x10000: 0x1000D] = b"202601010000\x00"     # a 12-digit run before the stamp, with no '*.bin' before it
    one = bytes(img)
    m = image.find_marks(one)
    assert (m.stamp, m.stamp_offset, m.stamp_conflict) == ("202505201029", stamp_at, ())   # the one after '*.bin' wins
    img[0x30000: 0x30006] = b"*.bin\x00"
    img[0x30008: 0x30015] = b"202601010000\x00"
    two = bytes(img)
    m = image.find_marks(two)
    assert m.stamp is None and m.stamp_conflict == ("202505201029", "202601010000") and not m.complete
    payload = pack.prepare_payload(two)
    by = _checks(_named(tmp_path, payload, "202611061413"), payload, allow_modified=True)
    assert not by["series"].ok and "several different ones follow '*.bin'" in by["series"].detail
    (tmp_path / "two.bin").write_bytes(two)
    assert cli.main(["inspect", str(tmp_path / "two.bin")]) == 0
    assert "several different stamps follow '*.bin': 202505201029, 202601010000" in capsys.readouterr().out
    # the same stamp twice is no conflict
    img[0x30008: 0x30015] = b"202505201029\x00"
    assert image.find_marks(bytes(img)).stamp == "202505201029"


def test_a_sensor_table_no_code_refers_to(tmp_path, synthetic_build):
    backup = synthetic_image([make_record()])
    unref = bytearray(backup)
    unref[SENSOR_POINTER: SENSOR_POINTER + 4] = b"\xff" * 4
    # an unaligned copy of the address is not a literal the code loads
    unref[0x7B921: 0x7B925] = struct.pack("<I", SENSOR_START - image.VA_OFFSET)
    payload = pack.prepare_payload(bytes(unref))
    sensor = image.find_sensor_tables(payload)
    assert sensor is not None and sensor.referenced_from is None
    assert image.find_sensor_tables(backup).referenced_from == SENSOR_POINTER
    p = _named(tmp_path, payload, "202611061406")
    by = _checks(p, payload, allow_modified=True)
    assert by["sensor-table"].ok and by["sensor-table"].severity == "warn" and "no code refers to it" in by["sensor-table"].detail
    # against a reference whose table the code does refer to: refused, whatever the flags
    by = _checks(p, payload, stock=backup, allow_modified=True, allow_unknown_build=True)
    assert not by["sensor-table"].ok and "referred to by no code" in by["sensor-table"].detail
    # against a reference whose table no code refers to either, the tables are compared as usual
    by = _checks(p, payload, stock=bytes(unref), allow_modified=True)
    assert by["sensor-table"].ok and "the same as in --stock" in by["sensor-table"].detail


def test_a_changed_copy_with_another_sensor_table_is_another_build(tmp_path, synthetic_build):
    backup = synthetic_image([make_record()])
    payload = pack.prepare_payload(backup)
    m = image.detect_build(payload)
    assert m.made_from is synthetic_build and "with changes" in m.label and m.identity == "public-dump-modified"
    other = pack.prepare_payload(_donor(backup))
    m = image.detect_build(other)
    assert m.made_from is None and m.layout is synthetic_build
    assert m.label.startswith("Charmera firmware of a build this toolkit does not know, laid out like the public dump's build")
    # the key pack, inspect and the manifests show follows made_from; the mod commands go by m.build, the layout
    assert m.identity == "charmera-unknown-build" and m.build is synthetic_build
    assert "build:           charmera-unknown-build (Charmera firmware of a build this toolkit does not know" in pack.describe(
        other, "202611061407")
    by = _checks(_named(tmp_path, other, "202611061407"), other, allow_modified=True, allow_unknown_build=True)
    assert by["build"].severity == "warn" and "laid out like" in by["build"].detail
    assert "differs from every known build" in by["software-region"].detail
    assert m.label.endswith(": its sensor table is not that build's")


def test_a_changed_copy_with_another_source_tree_or_compile_date_is_another_build(tmp_path, monkeypatch, synthetic_build):
    """S15: the source-tree folder and the compile date are build marks too. A copy laid out like a known build is that
    build with changes only when they are that build's as well, as in the app; the mods go by the layout either way."""
    payload = pack.prepare_payload(synthetic_image([make_record()]))
    at = image.find_marks(payload).compiled_offset
    cases = {"source tree": _with_source_paths(payload, [b"v007"]),
             "compile date": payload[:at] + b"Nov 19 2025" + payload[at + 11:]}
    cases["source tree and compile date"] = _with_source_paths(cases["compile date"], [b"v008_test"])
    for other, changed in cases.items():
        m = image.detect_build(changed)
        assert m.made_from is None and m.build is synthetic_build and m.layout is synthetic_build, other
        assert m.identity == "charmera-unknown-build" and m.label.endswith(
            f": its {other} {'are' if ' and ' in other else 'is'} not that build's"), other
        by = _checks(_named(tmp_path, changed, "202611061441"), changed, allow_modified=True)
        assert by["build"].severity == "warn" and "laid out like the public dump's build" in by["build"].detail, other
    # without any source path, the folder is not that build's either; a build that records neither asks for neither
    assert image.detect_build(_without_source_paths(payload)).made_from is None
    no_marks = dataclasses.replace(synthetic_build, source_tree=None, compiled=None)
    monkeypatch.setitem(image.KNOWN_BUILDS, no_marks.key, no_marks)
    assert image.detect_build(cases["source tree and compile date"]).made_from is no_marks


def test_the_stamp_in_the_name_must_differ_from_the_one_the_camera_runs(tmp_path, synthetic_build):
    backup = bytearray(synthetic_image([make_record()]))
    backup[image.PUBLIC_BUILD.stamp_offset: image.PUBLIC_BUILD.stamp_offset + 12] = b"202509300000"
    backup = bytes(backup)
    payload = pack.prepare_payload(synthetic_image([make_record()]))
    same = _checks(_named(tmp_path, payload, "202509300000"), payload, stock=backup, allow_modified=True)
    assert not same["stamp-camera"].ok and "the camera would ignore the file" in same["stamp-camera"].detail
    other = _checks(_named(tmp_path, payload, "202611061408"), payload, stock=backup, allow_modified=True)
    assert other["stamp-camera"].ok


def test_the_withdrawn_candidates_are_refused_by_their_hash(tmp_path, monkeypatch, synthetic_build):
    payload = pack.prepare_payload(synthetic_image([make_record()]))
    monkeypatch.setitem(verify.WITHDRAWN, hashlib.sha256(payload).hexdigest(), "candidate X (a stand-in)")
    by = _checks(_named(tmp_path, payload), payload, allow_modified=True, allow_unknown_build=True, allow_odd_size=True)
    assert not by["withdrawn"].ok and "candidate X" in by["withdrawn"].detail and "no flag accepts it" in by["withdrawn"].detail
    # the hashes are those the app refuses C, A and B by
    java = (Path(__file__).resolve().parent.parent / "android" / "src" / "io" / "github" / "daemonddev" / "charmera"
            / "Candidates.java").read_text(encoding="utf-8")
    import re
    app = {kind: sha for kind, sha in re.findall(r'\b([CAB])\(false,.*?"JH_5408\d{12}_[0-9A-F]{8}\.bin", "([0-9a-f]{64})"\)', java, re.S)}
    assert set(app) == {"C", "A", "B"}
    real = {k: v for k, v in verify.WITHDRAWN.items() if not v.startswith("candidate X")}
    assert set(real) == set(app.values())
    for kind, sha in app.items():
        assert real[sha].startswith(f"candidate {kind} ")


def test_allow_unknown_build_still_compares_with_stock(tmp_path, capsys, synthetic_build):
    """--allow-unknown-build takes a file without build marks, and --stock is still compared with it. S3 amended: with
    --stock, a file without a sensor table of the known kind fails outside the donor route whatever the flags, as does
    a --stock without one."""
    backup = synthetic_image([make_record()])
    # no sensor table of the kind the known builds use: the reset table is gone
    no_table = bytearray(backup)
    no_table[SENSOR_RESET_OFFSET: SENSOR_RESET_OFFSET + 6] = bytes.fromhex("fe0080ffffff")
    payload = pack.prepare_payload(bytes(no_table))
    p = _named(tmp_path, payload, "202611061409")
    by = _checks(p, payload, stock=backup, allow_unknown_build=True)
    assert by["series"].ok and by["series"].severity == "warn" and "accepted because of --allow-unknown-build" in by["series"].detail
    assert not by["software-region"].ok and "0xf0000" in by["software-region"].detail      # compared with --stock
    for stock, said in ((backup, "while --stock has one (61 writes at 0xf5c6c"), (bytes(no_table), "and --stock has none either")):
        by = _checks(p, payload, stock=stock, allow_unknown_build=True, allow_modified=True, allow_odd_size=True)
        s = by["sensor-table"]
        assert not s.ok and s.severity == "fail" and said in s.detail, said
        assert "no flag accepts it" in s.detail and "--donor" in s.detail
        assert _failed(by.values()) == ["sensor-table"]
    # without --stock the flag accepts it, and the line says that nothing compared the sensor set-up
    s = _checks(p, payload, allow_unknown_build=True, allow_modified=True)["sensor-table"]
    assert s.ok and s.severity == "warn" and "no sensor comparison was possible" in s.detail
    # without the flag the series line still refuses the file
    assert _failed(verify.verify_update(p, payload, stock=backup, allow_modified=True)) == ["series"]
    # a file that lacks only its build string still has its sensor table compared
    no_string = bytearray(backup)
    no_string[image.PUBLIC_BUILD.build_offset: image.PUBLIC_BUILD.build_offset + 6] = b"\xff" * 6
    payload = pack.prepare_payload(bytes(no_string))
    by = _checks(_named(tmp_path, payload, "202611061410"), payload, stock=backup, allow_unknown_build=True, allow_modified=True)
    assert by["sensor-table"].ok and "the same as in --stock" in by["sensor-table"].detail


def test_photo_directory_lists_counts_as_the_app_does(tmp_path, synthetic_build):
    img = bytearray(synthetic_image([make_record()]))
    d = settings.PHOTO_DIRECTORY
    assert settings.photo_directory_lists(bytes(img)) == 0                     # zeroed
    img[d: d + 8] = struct.pack("<HHI", 0, 1, 0x100)
    assert settings.photo_directory_lists(bytes(img)) == 1
    img[d + 8: d + 16] = struct.pack("<HHI", 1, 1, 0x200)
    assert settings.photo_directory_lists(bytes(img)) == 2
    img[d + 8: d + 16] = struct.pack("<HHI", 1, 0xFFFF, 0x200)                 # used 0xFFFF is not a photo
    assert settings.photo_directory_lists(bytes(img)) == 1
    img[d: d + 0x200] = b"\xff" * 0x200                                         # erased: none
    assert settings.photo_directory_lists(bytes(img)) == 0
    assert settings.photo_directory_lists(bytes(img[:d + 8])) == 0
    # verify says so when photos are stored and the list is empty
    photo = bytearray(synthetic_image([make_record()]))
    photo[image.PHOTO_SLOTS[0]: image.PHOTO_SLOTS[0] + 4] = b"\xff\xd8\xff\xd9"
    payload = pack.prepare_payload(bytes(photo))
    by = _checks(_named(tmp_path, payload), payload, allow_modified=True)
    assert "the photo directory lists none of them, so the camera will not show them" in by["photos"].detail
    assert "its own only if" in by["photos"].detail


def test_a_file_that_ends_right_after_a_page_register_is_read_without_a_traceback(tmp_path, capsys):
    from charmera_toolkit import cli
    short = b"\x00" * 0x200 + image.SENSOR_RESET_TABLE + b"\xfd"
    assert image.find_sensor_tables(short) is None and image.find_marks(short).sensor is None
    by = _checks(tmp_path / "JH_5408202611061411_00000000.bin", short)
    assert not by["size"].ok and not by["series"].ok
    cut = synthetic_image([make_record()])[: SENSOR_START + 1]
    assert cut[-1] == 0xFD and image.find_sensor_tables(cut) is None
    (tmp_path / "short.bin").write_bytes(short)
    assert cli.main(["inspect", str(tmp_path / "short.bin")]) == 0
    capsys.readouterr()


def test_official_size_file_against_stock_compares_only_what_it_holds(tmp_path, synthetic_build):
    backup = bytearray(synthetic_image([make_record()]))
    official = pack.prepare_payload(bytes(backup), official_size=True)
    p = _named(tmp_path, official, "202611061412")
    changed = bytearray(backup)
    changed[0x30000] ^= 1
    by = _checks(p, official, stock=bytes(changed), allow_modified=True)
    assert "['0x30000']" in by["software-region"].detail and "0x160000" not in by["software-region"].detail
    # the camera's own data between the end of the file and the settings bank is named, not counted as a changed block
    backup[0x16E000: 0x16E004] = b"JHCM"
    by = _checks(p, official, stock=bytes(backup))
    assert by["software-region"].ok and "byte-identical to --stock" in by["software-region"].detail
    assert by["own-data"].severity == "warn" and "0x16e000..0x16e004" in by["own-data"].detail and "keeps it" in by["own-data"].detail


def test_pack_refuses_a_drive_root_or_removable_media_without_dcim(tmp_path, capsys, monkeypatch):
    from charmera_toolkit import cli
    src = tmp_path / "s.bin"
    src.write_bytes(synthetic_image([make_record()]))
    gate = "--i-understand-this-file-flashes-a-camera"
    root = tmp_path / "freshly-formatted-card"
    root.mkdir()
    monkeypatch.setattr(pack, "is_drive_root", lambda path: Path(path) == root)
    assert cli.main(["pack", "--input", str(src), "--out", str(root), "--stamp", "202611050001", gate]) == 2
    out = capsys.readouterr().out
    assert "is a drive root or mount point" in out and "charmera stage" in out
    assert list(root.iterdir()) == []
    monkeypatch.undo()
    # a folder on removable media, however deep and whether it exists yet
    monkeypatch.setattr(pack, "windows_volume_info", lambda path: ("exFAT", "CARD", True))
    monkeypatch.setattr(pack, "linux_mount_entry", lambda path, mounts_path="/proc/mounts": ("/dev/mmcblk0p1", "exfat"))
    monkeypatch.setattr(pack, "linux_removable", lambda device, sys_root="/sys": True)
    deep = tmp_path / "stick" / "updates"
    assert cli.main(["pack", "--input", str(src), "--out", str(deep), "--stamp", "202611050001", gate]) == 2
    assert "lies on removable media" in capsys.readouterr().out
    assert not deep.exists()
    monkeypatch.undo()
    assert cli.main(["pack", "--input", str(src), "--out", str(tmp_path / "o"), "--stamp", "202611050001", gate]) == 0


def test_pack_keeps_the_untouched_block_of_a_dump_and_refuses_only_the_one_the_erase_destroys(tmp_path, capsys, synthetic_build):
    from charmera_toolkit import cli
    img = bytearray(synthetic_image([make_record()]))
    img[0x1E0000: 0x1E0004] = b"KEEP"           # neither written nor erased by a 0x1E0000-byte flash
    src = tmp_path / "dump.bin"
    src.write_bytes(bytes(img))
    assert len(pack.prepare_payload(bytes(img))) == checksum.UPDATE_FILE_SIZE
    assert cli.main(["pack", "--input", str(src), "--out", str(tmp_path / "o"), "--stamp", "202611050001"]) == 0
    out = capsys.readouterr().out
    assert "note:" in out and "the camera keeps its own as they are" in out and "not all blank" in out
    img[0x1F0000: 0x1F0004] = b"GONE"           # the block the final erase of a 0x1E0000-byte file destroys
    with pytest.raises(ValueError, match="0x1f0000..0x200000 are not blank") as exc:
        pack.prepare_payload(bytes(img))
    # an official-size file leaves the blocks from 0x180000 on alone, but its final erase resets the settings block
    for said in (str(exc.value), pack.payload_notes(bytes(img), official_size=True)[0]):
        assert "leaves everything from 0x180000 on as it is" in said and "above 0x170000" not in said
        assert "resets the settings bank and the photo directory, in the block at 0x170000" in said
    assert len(pack.prepare_payload(bytes(img), official_size=True)) == checksum.OFFICIAL_UPDATE_SIZE
    # an official-size file blames the right thing for data past 0x169000
    selftest = bytearray(synthetic_image([make_record()]))
    selftest[0x16E000: 0x16E004] = b"JHCM"
    with pytest.raises(ValueError, match="factory self-test sector") as exc:
        pack.prepare_payload(bytes(selftest), official_size=True)
    assert "resource pack" not in str(exc.value) and "lose its own copy" in str(exc.value)
    big = bytearray(synthetic_image([make_record()]))
    big[checksum.OFFICIAL_UPDATE_SIZE + 0x100] = 0x55
    with pytest.raises(ValueError, match="the resource pack, or data after it"):
        pack.prepare_payload(bytes(big), official_size=True)


def test_inspect_says_a_file_named_like_an_update_file_of_another_size_never_goes_on_a_card(tmp_path, capsys):
    from charmera_toolkit import cli
    img = synthetic_image([make_record()])
    full = tmp_path / "JH_5408202611061200_00000000.bin"
    full.write_bytes(img)
    assert cli.main(["inspect", str(full)]) == 0
    out = capsys.readouterr().out
    assert "DOES NOT MATCH file" in out and "renaming the file to match does not make it safe" in out
    assert "NOT FOR A CARD" in out and "bricks the camera" in out
    assert "read before it was ever flashed" in out
    lower = tmp_path / "jh_5408202611061200_1dd7adf0.BIN"
    lower.write_bytes(img[:0x170000])
    assert cli.main(["inspect", str(lower)]) == 0
    assert "NOT FOR A CARD" in capsys.readouterr().out
    update = tmp_path / checksum.build_update_name(pack.prepare_payload(img), "202611061200").filename
    update.write_bytes(pack.prepare_payload(img))
    assert cli.main(["inspect", str(update)]) == 0
    out = capsys.readouterr().out
    assert "matches file" in out and "NOT FOR A CARD" not in out


def test_stock_image_vector_table_and_sensor_literal(stock_image, tmp_path):
    """The public build's vector table hash is the dump's, and its sensor literal is aligned and referenced."""
    assert image.vector_table_sha256(stock_image) == image.PUBLIC_BUILD.vector_table_sha256
    m = image.find_marks(stock_image)
    assert m.sensor.referenced_from == 0x7B91C and len(m.sensor_programs) == 1 and m.several_sensor_programs == []
    assert image.detect_build(stock_image).layout is image.PUBLIC_BUILD


def test_stock_image_restore_file_photos_are_named(stock_image, tmp_path):
    """The stock restore file carries the photos of the camera the keychain dump was read from, and lists none."""
    if hashlib.sha256(stock_image).hexdigest() != "cc3b4b2fd7eb71ba5a441decdc86984261fac8418ee3e105d7e2b3c38b7ea42f":
        pytest.skip("the photo hashes are those of the keychain-camera-plus dump")
    restore = stock_image[: checksum.UPDATE_FILE_SIZE]
    assert [o for o, _owner in image.public_dump_photos(restore)] == list(image.PHOTO_SLOTS)
    by = _checks(_named(tmp_path, restore, "202610261427"), restore)
    assert "benbadio/keychain-camera-plus dump was read from" in by["photos"].detail
    assert "the photo directory lists none of them" in by["photos"].detail
    assert "benbadio/keychain-camera-plus dump was read from" in pack.describe(restore, "202610261427")
