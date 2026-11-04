"""Core tests: checksum, settings, image parsing, verify and pack logic.

Image-dependent tests run only when CHARMERA_STOCK_IMAGE points at a local original-series dump.
Everything that writes uses synthetic images so no real firmware file is produced by the test run.
"""
import os
import struct
import zlib
from pathlib import Path

import pytest

from charmera_toolkit import checksum, image, pack, settings, sounds, verify


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
    img[0x200:0x204] = b"\x18\xf0\x9f\xe5"
    # build stamps so detect_build finds the original series (software hash will not match: that is expected)
    img[image.ORIGINAL_2025.stamp_offset: image.ORIGINAL_2025.stamp_offset + 12] = image.ORIGINAL_2025.stamp.encode()
    img[image.ORIGINAL_2025.build_offset: image.ORIGINAL_2025.build_offset + 16] = image.ORIGINAL_2025.build_string
    img[image.ORIGINAL_2025.compiled_offset: image.ORIGINAL_2025.compiled_offset + 11] = image.ORIGINAL_2025.compiled
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
                img[p: p + 8] = b"GPZP\x00\x00\x00\x00"
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
    assert info["series"] == "original-2025" and all(info["series_checks"].values())
    assert set(info["series_checks"]) == {"stamp", "build_string", "compiled", "code_sha256", "sensor_table", "software_sha256"}
    assert info["software_sha256"] == image.ORIGINAL_2025.software_sha256
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


def test_verify_tags_and_product_code(tmp_path, capsys):
    from charmera_toolkit import verify as verify_mod
    img = synthetic_image([make_record()])
    payload = pack.prepare_payload(img)
    # a 0x170000-byte file: final erase lands on the photo area -> WARN tag, not PASS, and exit 0
    short = payload[:0x170000]
    name = checksum.build_update_name(short, "202611041200")
    p = tmp_path / name.filename
    p.write_bytes(short)
    args = type("A", (), {"file": str(p), "stock": None, "allow_modified": True, "allow_unknown_build": False, "allow_odd_size": False})
    rc = verify_mod.handler(args)
    out = capsys.readouterr().out
    assert rc == 0
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
    assert not by["size"].ok and "shorter than 0x170000" in by["size"].detail
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
    assert new[0x62B8: 0x62BC] == b"\x02\x00\xa0\xe3" and new[0x2319C: 0x231A0] == b"\x01\x00\xa0\xe3"
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
