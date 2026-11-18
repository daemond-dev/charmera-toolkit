"""scripts/ae_luma.py on synthetic MJPEG AVIs: it must tell a converging, a fixed and a ceiling-limited exposure apart."""
import importlib.util
import io
import struct
from pathlib import Path

import pytest

Image = pytest.importorskip("PIL.Image")

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "ae_luma.py"
spec = importlib.util.spec_from_file_location("ae_luma", SCRIPT)
ae = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ae)

FPS = 30


def jpeg(level, bottom=None, size=(64, 48)):
    img = Image.new("L", size, int(max(0, min(255, level))))
    if bottom is not None:
        img.paste(int(bottom), (0, size[1] // 2, size[0], size[1]))
    buf = io.BytesIO()
    img.convert("RGB").save(buf, "JPEG", quality=95)
    return buf.getvalue()


def chunk(fourcc, body):
    return fourcc + struct.pack("<I", len(body)) + body + (b"\0" if len(body) & 1 else b"")


def avi(frames, idx=True):
    avih = struct.pack("<I", 1_000_000 // FPS) + b"\0" * 52
    hdrl = chunk(b"LIST", b"hdrl" + chunk(b"avih", avih))
    movi = chunk(b"LIST", b"movi" + b"".join(chunk(b"00dc", f) for f in frames))
    body = b"AVI " + hdrl + movi + (chunk(b"idx1", b"") if idx else b"")
    return b"RIFF" + struct.pack("<I", len(body)) + body


def trace(recover_to, start=120.0, low=30.0, tau=8.0):
    """3 s at start, a step to low, the AE pulling back toward recover_to, then a 4 s hold."""
    levels = [start + (k % 3) * 0.5 for k in range(3 * FPS)]
    lv = low
    for _ in range(4 * FPS):
        levels.append(lv)
        lv += (recover_to - lv) / tau
    return levels


def run(tmp_path, levels, capsys, name="t.avi", extra=(), idx=True):
    p = tmp_path / name
    frames = [jpeg(v) if not isinstance(v, tuple) else jpeg(*v) for v in levels]
    p.write_bytes(avi(frames, idx=idx))
    assert ae.main([str(p), *extra]) == 0
    return capsys.readouterr().out


def test_converging_loop_reads_recovered(tmp_path, capsys):
    out = run(tmp_path, trace(118), capsys)
    assert "step 1 at 3.00 s (darker)" in out
    assert "[recovered]" in out


def test_fixed_exposure_reads_flat(tmp_path, capsys):
    out = run(tmp_path, trace(30), capsys)
    assert "[flat]" in out


def test_ceiling_limited_loop_reads_partial(tmp_path, capsys):
    out = run(tmp_path, trace(75), capsys)
    assert "[partial]" in out


def test_split_frame_is_flagged_not_counted_as_a_step(tmp_path, capsys):
    levels = [120.0] * (4 * FPS)
    levels[60] = (120, 40)
    out = run(tmp_path, levels, capsys)
    assert "no step in light found" in out
    assert "split frames" in out and "60 (2.00 s)" in out


def test_down_and_up_steps_both_found(tmp_path, capsys):
    levels = trace(118) + trace(120, low=255)[3 * FPS:]      # 100% -> 25%, settle, then back up (clipping first)
    out = run(tmp_path, levels, capsys)
    assert "step 1" in out and "step 2" in out and "(brighter)" in out


def test_unfinished_avi_without_index_still_reads(tmp_path, capsys):
    p = tmp_path / "cut.avi"
    data = avi([jpeg(100)] * 20, idx=False)
    p.write_bytes(data[:-10])                       # last frame cut short
    assert ae.main([str(p)]) == 0
    assert "19 frames" in capsys.readouterr().out


def test_stills_mode(tmp_path, capsys):
    for n, v in enumerate((200, 100)):
        (tmp_path / f"s{n}.jpg").write_bytes(jpeg(v))
    assert ae.main([str(tmp_path / "s0.jpg"), str(tmp_path / "s1.jpg")]) == 0
    out = capsys.readouterr().out
    assert "s0.jpg: mean 200" in out and "s1.jpg: mean 100" in out


def test_not_an_avi(tmp_path, capsys):
    p = tmp_path / "x.avi"
    p.write_bytes(b"nope" * 10)
    assert ae.main([str(p)]) == 1
    assert "not an AVI" in capsys.readouterr().out
