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
# build has them and shaped like the real ones: the build string and the compile date, the stamp with its NUL,
# and a sensor program (the reset table, a 61-write init table that starts with a page select, switches page
# once more and ends with ff ff, and the code literal that points at it). The init table's hash is not a known
# build's, and neither is the vector table's (one ARM instruction, then 0xFF), so the synthetic_build fixture
# registers both as the public build's where a test wants a file accepted without --stock. Tests on the real
# dump use the real identity.
SENSOR_RESET_OFFSET = 0xF5BFC
SENSOR_START, SENSOR_END = 0xF5C6C, 0xF5CE8
SENSOR_POINTER = 0x7B91C
SYNTHETIC_SENSOR_TABLE = bytes(b for pair in [(0xFD, 0x00)] + [(r, (r * 3) & 0xFF) for r in range(0x20, 0x20 + 58)]
                               + [(0xFD, 0x01), (0x11, 0x80)] for b in pair) + b"\xff\xff"
assert len(SYNTHETIC_SENSOR_TABLE) == SENSOR_END - SENSOR_START == 124
SYNTHETIC_VECTORS = b"\x18\xf0\x9f\xe5" + b"\xff" * 60      # bytes 0x200..0x240 of synthetic_image()


def put_build_marks(img: bytearray) -> None:
    """The public build's marks at its offsets, so detect_build reads 'public-dump-modified'."""
    b = image.PUBLIC_BUILD
    img[b.stamp_offset: b.stamp_offset + 13] = b.stamp.encode() + b"\x00"
    img[b.build_offset: b.build_offset + 20] = b"JH5408  20250520\x00\x00\x00\x00"
    img[0xF3978: 0xF3978 + 12] = b"Oct 14 2025\x00"
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
    put_build_marks(img)   # detect_build then reads 'public-dump-modified' (the software hash cannot match)
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
    assert by["software-region"].severity == "info" and "0xf0000" in by["software-region"].detail
    backup_path, donor_path = tmp_path / "backup.bin", tmp_path / "donor.bin"
    backup_path.write_bytes(backup)
    donor_path.write_bytes(donor)
    args = type("A", (), {"file": str(p), "stock": str(backup_path), "donor": str(donor_path), "allow_modified": False,
                          "allow_unknown_build": False, "allow_odd_size": False})
    assert verify.handler(args) == 0
    out = capsys.readouterr().out
    assert "WARN sensor-table" in out and "Whether it fits this camera is not known" in out


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


def test_stock_image_as_stock_is_named_a_public_dump(stock_image, tmp_path):
    """A read of this camera after the stock restore flash can be byte-identical to the public dump: say what that means."""
    restore = pack.prepare_payload(stock_image)
    by = {c.name: c for c in verify.verify_update(_named(tmp_path, restore, "202611061304"), restore, stock=stock_image)}
    assert by["stock"].severity == "warn" and "byte-identical" in by["stock"].detail and "not what fits it" in by["stock"].detail


FILM_MODS_RESTORE = Path(os.environ.get("CHARMERA_FILM_MODS_RESTORE", ""))


@pytest.mark.skipif(not FILM_MODS_RESTORE.is_file(), reason="set CHARMERA_FILM_MODS_RESTORE to the film-mods Charmera_Restore file")
def test_film_mods_restore_file_is_named_by_its_fingerprint(tmp_path):
    data = FILM_MODS_RESTORE.read_bytes()
    m = image.detect_build(data)
    assert m.exact and m.build.key == "film-mods" and m.build.status == "VERIFIED"
    assert m.marks.compiled == b"Nov 19 2025" and m.marks.stamp_offset == 0x218D0 and m.marks.build_offset == 0x1897C
    assert m.marks.sensor.sha256 == image.PUBLIC_BUILD.sensor_table_sha256
    # its stamp is the one both builds run, so a camera running either ignores it, and verify says so
    by = {c.name: c for c in verify.verify_update(tmp_path / FILM_MODS_RESTORE.name, data)}
    assert not by["stamp"].ok and "the charmera-film-mods author's build" in by["build"].detail
    # its vector table is the one image.py carries for that build, and its sensor literal is aligned
    assert image.vector_table_sha256(data) == image.FILM_MODS_BUILD.vector_table_sha256
    assert by["vector-table"].ok and by["vector-table"].severity == "fail"     # a plain PASS
    assert m.marks.sensor.referenced_from == 0x7B5A8 and m.marks.sensor.referenced_from % 4 == 0
    assert m.layout is image.FILM_MODS_BUILD


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
    # a table that differs from the reference still fails outside the donor route
    donor = pack.prepare_payload(_donor(backup))
    by = _checks(_named(tmp_path, donor, "202611061401"), donor, stock=backup, allow_modified=True)
    assert by["sensor-table"].severity == "fail" and "DIFFERS" in by["sensor-table"].detail


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


def test_two_different_stamps_after_the_update_pattern_give_no_stamp(tmp_path, synthetic_build):
    """Which of two different stamps the updater compares cannot be told, so neither is taken."""
    img = bytearray(synthetic_image([make_record()]))
    stamp_at = image.PUBLIC_BUILD.stamp_offset
    img[stamp_at - 8: stamp_at - 2] = b"*.bin\x00"
    one = bytes(img)
    assert image.find_marks(one).stamp == "202505201029" and image.find_marks(one).stamp_conflict == ()
    img[0x30000: 0x30006] = b"*.bin\x00"
    img[0x30008: 0x30015] = b"202601010000\x00"
    two = bytes(img)
    m = image.find_marks(two)
    assert m.stamp is None and m.stamp_conflict == ("202505201029", "202601010000") and not m.complete
    payload = pack.prepare_payload(two)
    by = _checks(_named(tmp_path, payload, "202611061413"), payload, allow_modified=True)
    assert not by["series"].ok and "several different ones follow '*.bin'" in by["series"].detail
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
    assert m.made_from is synthetic_build and "with changes" in m.label
    other = pack.prepare_payload(_donor(backup))
    m = image.detect_build(other)
    assert m.made_from is None and m.layout is synthetic_build
    assert m.label.startswith("Charmera firmware of a build this toolkit does not know, laid out like the public dump's build")
    assert m.identity == "public-dump-modified"            # the key the mod commands and the backup manifest go by
    by = _checks(_named(tmp_path, other, "202611061407"), other, allow_modified=True, allow_unknown_build=True)
    assert by["build"].severity == "warn" and "laid out like" in by["build"].detail
    assert "differs from every known build" in by["software-region"].detail


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
    backup = synthetic_image([make_record()])
    # no sensor table of the kind the known builds use: the reset table is gone
    no_table = bytearray(backup)
    no_table[SENSOR_RESET_OFFSET: SENSOR_RESET_OFFSET + 6] = bytes.fromhex("fe0080ffffff")
    payload = pack.prepare_payload(bytes(no_table))
    p = _named(tmp_path, payload, "202611061409")
    by = _checks(p, payload, stock=backup, allow_unknown_build=True)
    assert by["series"].ok and by["series"].severity == "warn" and "accepted because of --allow-unknown-build" in by["series"].detail
    assert by["sensor-table"].severity == "warn" and "no sensor comparison was possible" in by["sensor-table"].detail
    assert not by["software-region"].ok and "0xf0000" in by["software-region"].detail      # compared with --stock
    by = _checks(p, payload, stock=backup, allow_unknown_build=True, allow_modified=True)
    assert by["software-region"].severity == "warn" and not _failed(by.values())
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
    with pytest.raises(ValueError, match="0x1f0000..0x200000 are not blank"):
        pack.prepare_payload(bytes(img))
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
