"""identify: which stock firmware a card's photos show, from synthetic headers on a tmp_path card."""
import json
import os
from datetime import datetime
from pathlib import Path

import pytest

from charmera_toolkit import cli, identify, photo_id
from test_photo_id import avi, bare_header, exif_fixed, exif_mod_header, seg, still_header

DAY = 86400
T0 = datetime(2026, 11, 1, 12, 0).timestamp()


def date(t: float) -> str:
    return datetime.fromtimestamp(t).strftime("%Y-%m-%d %H:%M")


def put(root: Path, rel: str, header: bytes, when: float) -> Path:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(header + b"\x12\x34" * 200 + b"\xff\xd9")
    os.utime(p, (when, when))
    return p


def card(tmp_path: Path) -> Path:
    root = tmp_path / "card"
    (root / "DCIM" / "100MEDIA").mkdir(parents=True)
    return root


def run(capsys, *argv):
    code = cli.main(["identify", *map(str, argv)])
    return code, capsys.readouterr().out


def line(out: str, label: str) -> str:
    return next(x for x in out.splitlines() if x.startswith(label))[len(label):].strip()


def caveats_ok(out: str) -> None:
    caveats = [x for x in out.splitlines() if x.startswith("caveat:")]
    assert len(caveats) == 2 and "v006 from v008_test" in caveats[0] and "either base works the same" in caveats[0]
    assert "photos show the firmware that took them" in caveats[1]


def test_one_family_oldest_and_newest(tmp_path, capsys):
    root = card(tmp_path)
    first = put(root, "DCIM/100MEDIA/PICT0001.JPG", bare_header(), T0)
    put(root, "DCIM/100MEDIA/PICT0002.JPG", bare_header(85), T0 + DAY)
    last = put(root, "DCIM/100MEDIA/PICT0003.jpg", bare_header(75), T0 + 2 * DAY)
    put(root, "notes/PICT9999.JPG", still_header(), T0 + 9 * DAY)       # outside DCIM: a card root searches DCIM only
    code, out = run(capsys, root)
    assert code == 0
    assert f"{first}, {date(T0)}, 0x270-byte header (bare)" in line(out, "oldest:")
    assert f"{last}, {date(T0 + 2 * DAY)}" in line(out, "newest:")
    assert line(out, "photos:").startswith("3 found")
    assert line(out, "conclusion:") == ("The photos were taken by stock v012 (an OV9732 camera): base v012. If the camera was flashed after these photos, it runs something else now")
    assert line(out, "base:") == "v012 (stock firmware of OV9732 cameras)"
    assert line(out, "next:") == ("charmera build --list, then charmera build --base v012 --mods <mods> --download "
                                  "--out out/")
    assert line(out, "confidence:").startswith("high:")
    caveats_ok(out)
    assert "not stock:" not in out and "OV9732 port on v006" not in out


def test_the_v006_family_alone_is_high(tmp_path, capsys):
    """Stock assumption: v006-family photos are not doubted for our v0.21-v0.24 writing the same header; one line says
    so, and the confidence stays high."""
    root = card(tmp_path)
    put(root, "DCIM/100MEDIA/PICT0001.JPG", still_header(), T0)
    put(root, "DCIM/100MEDIA/PICT0002.JPG", still_header(), T0 + DAY)
    code, out = run(capsys, root / "DCIM")
    assert code == 0
    assert line(out, "conclusion:") == ("The photos were taken by stock v006 or v008_test (an SP140A camera): base v006. "
                                        "If the camera was flashed after these photos, it runs something else now")
    assert "--base v006 --mods <mods> --download" in line(out, "next:")
    assert line(out, "base:") == "v006 (stock firmware of SP140A cameras)"
    assert line(out, "confidence:").startswith("high:")
    assert line(out, "note:") == identify.OUR_OV9732_PORT
    assert "not stock:" not in out
    caveats_ok(out)
    r = identify.build_report([root])
    assert r.stock is True and r.base == "v006" and r.family == "v006-v008_test"


def test_the_exif_mod_alone_is_not_stock_on_base_v012(tmp_path, capsys):
    root = card(tmp_path)
    put(root, "DCIM/100MEDIA/PICT0001.JPG", exif_mod_header(), T0)
    put(root, "DCIM/100MEDIA/PICT0002.JPG", exif_mod_header(), T0 + DAY)
    code, out = run(capsys, root)
    assert code == 0
    assert line(out, "not stock:") == "This camera does not look stock: our EXIF mod (Software 'v0.26') is installed"
    assert "built on stock v012: base v012" in line(out, "conclusion:")
    assert line(out, "base:").startswith("v012 ")
    assert line(out, "confidence:").startswith("high:")
    assert "two firmware families" not in out


def test_bare_then_exif_mod_is_one_family_with_the_mod_note(tmp_path, capsys):
    root = card(tmp_path)
    put(root, "DCIM/100MEDIA/PICT0001.JPG", bare_header(), T0)
    put(root, "DCIM/100MEDIA/PICT0002.JPG", bare_header(), T0 + DAY)
    put(root, "DCIM/100MEDIA/PICT0003.JPG", exif_mod_header(), T0 + 2 * DAY)
    code, out = run(capsys, root, "--json")
    data = json.loads(out)
    assert code == 0 and data["stock"] is False and data["base"] == "v012" and data["family"] == "v012"
    assert data["confidence"] == "high" and data["families"] is None
    assert data["not_stock"] == [f"This camera does not look stock: our EXIF mod (Software 'v0.26') "
                                 f"is installed, installed between {date(T0 + DAY)} and {date(T0 + 2 * DAY)}"]
    assert [r["family"] for r in data["family_runs"]] == ["v012"]


@pytest.mark.parametrize("older, newer", [("v012", "v006"), ("v006", "v012")])
def test_two_families_follow_the_oldest(tmp_path, capsys, older, newer):
    """Two families on the card, in either order: not stock, and the base follows the OLDEST family, since photos from
    before a flash show the stock firmware; confidence low."""
    make = {"v012": bare_header, "v006": still_header}
    family = {"v012": "v012", "v006": "v006-v008_test"}
    short = {"v012": "the v012 family (stock v012)", "v006": "the v006 family (stock v006 or v008_test)"}
    stock = {"v012": "v012", "v006": "v006 or v008_test"}
    root = card(tmp_path)
    for i in range(3):
        put(root, f"DCIM/100MEDIA/PICT000{i}.JPG", make[older](), T0 + i * DAY)
    for i in range(3, 5):
        put(root, f"DCIM/100MEDIA/PICT000{i}.JPG", make[newer](), T0 + i * DAY)
    code, out = run(capsys, root)
    assert code == 0
    assert line(out, "not stock:") == (
        f"This camera does not look stock: photos from two firmware families: {short[older]} up to "
        f"{date(T0 + 2 * DAY)}, {short[newer]} from {date(T0 + 3 * DAY)}: the camera was flashed in between, or the "
        "card holds photos from two cameras")
    assert line(out, "conclusion:").startswith(f"The oldest photos show stock {stock[older]} ")
    assert line(out, "conclusion:").endswith(f"base {older}")
    assert line(out, "base:").startswith(older + " ")
    assert line(out, "confidence:").startswith("low:")
    assert line(out, "note:") == identify.OUR_OV9732_PORT          # v006-family photos are on the card
    code, out = run(capsys, root, "--json")
    data = json.loads(out)
    assert data["base"] == older and data["family"] == family[older] and data["stock"] is False
    assert data["confidence"] == "low"


def test_files_other_software_saved_are_skipped(tmp_path, capsys):
    root = card(tmp_path)
    put(root, "DCIM/100MEDIA/EDIT0000.JPG", b"\xff\xd8" + seg(0xED, b"Photoshop 3.0\x00") + bare_header()[2:], T0 - DAY)
    put(root, "DCIM/100MEDIA/PICT0001.JPG", bare_header(), T0)
    put(root, "DCIM/100MEDIA/PICT0002.JPG", bare_header(), T0 + DAY)
    put(root, "DCIM/100MEDIA/PICT0003.JPG", exif_fixed(bare_header()), T0 + 2 * DAY)
    broken = root / "DCIM" / "100MEDIA" / "PICT0004.JPG"
    broken.write_bytes(b"not a jpeg")
    os.utime(broken, (T0 + 3 * DAY, T0 + 3 * DAY))
    code, out = run(capsys, root)
    assert code == 0
    skipped = [x for x in out.splitlines() if x.startswith("skipped:")]
    assert len(skipped) == 3
    assert "EDIT0000.JPG" in skipped[0] and "re-saved by another program (a Photoshop APP13 segment)" in skipped[0]
    assert any("PICT0004.JPG" in x and "not a JPEG" in x for x in skipped)
    assert any("PICT0003.JPG" in x and "exif-fix made" in x for x in skipped)
    assert "PICT0001.JPG" in line(out, "oldest:") and "PICT0002.JPG" in line(out, "newest:")
    assert line(out, "confidence:").startswith("high:")          # skipped files do not weaken the stock answer
    assert ("3 photo(s) read say nothing about the camera and were left out: 1 saved again by another program (a phone or "
            "editor), 1 copies exif-fix made, 1 unreadable or not the camera's own") in line(out, "note:")


def test_nothing_usable(tmp_path, capsys):
    root = card(tmp_path)
    code, out = run(capsys, root)
    assert code == 1 and line(out, "conclusion:") == ("no photos found: nothing to conclude. Take two or three photos with "
                                                      "the camera on this card first, then run identify again. "
                                                      + identify.NO_PHOTOS_PROBE)
    assert "sensor-probe" in identify.NO_PHOTOS_PROBE
    assert "caveat:" not in out and "next:" not in out
    put(root, "DCIM/100MEDIA/PICT0001.JPG", b"\xff\xd8" + seg(0xE2, b"ICC_PROFILE\x00") + bare_header()[2:], T0)
    code, out = run(capsys, root)
    assert code == 1 and "no dated photo the camera wrote as it is" in line(out, "conclusion:")
    assert "Take two or three photos with the camera on this card" in line(out, "conclusion:")
    code, out = run(capsys, tmp_path / "missing")
    assert code == 1 and "no such file or folder" in line(out, "problem:")


def test_the_cap_and_the_files_given(tmp_path, capsys):
    root = card(tmp_path)
    files = [put(root, f"DCIM/100MEDIA/PICT{i:04d}.JPG", bare_header(), T0 + i * 60) for i in range(12)]
    code, out = run(capsys, root, "--cap", "5")
    assert code == 0 and "12 found, 5 headers read for families (capped at 5; evenly spaced by date)" in out
    assert line(out, "confidence:").startswith("medium:") and "read 5 of 12 dated photo headers" in out
    r = identify.build_report([root], cap=5)
    assert r.capped and r.scanned == 5 and r.oldest.path == files[0] and r.newest.path == files[-1]
    # single files, and a tie in time broken by path
    a = put(tmp_path, "x/B.JPG", still_header(), T0)
    b = put(tmp_path, "x/A.JPG", still_header(), T0)
    r = identify.build_report([a, b])
    assert r.oldest.path == b and r.newest.path == a
    code, out = run(capsys, a)
    assert code == 0 and line(out, "photo:").startswith(str(a)) and "only one camera-written photo" in out
    assert line(out, "confidence:").startswith("medium:")


def test_the_cap_always_reads_the_chosen_oldest_and_newest(tmp_path):
    """With a cap and non-camera files at both ends, the evenly spaced picks land on the skipped files; the oldest and
    newest camera-written photos are read all the same, so a change of family at the very end is still seen."""
    root = card(tmp_path)
    fixed = exif_fixed(bare_header())
    for i in range(3):
        put(root, f"DCIM/100MEDIA/A{i}.JPG", fixed, T0 + i)
    put(root, "DCIM/100MEDIA/B0.JPG", bare_header(), T0 + 10)
    for i in range(5):
        put(root, f"DCIM/100MEDIA/C{i}.JPG", bare_header(), T0 + 20 + i)
    put(root, "DCIM/100MEDIA/D0.JPG", still_header(), T0 + 100)
    for i in range(3):
        put(root, f"DCIM/100MEDIA/E{i}.JPG", fixed, T0 + 200 + i)
    r = identify.build_report([root], cap=4)
    assert r.oldest.path.name == "B0.JPG" and r.newest.path.name == "D0.JPG"
    names = [m.path.name for m in identify.sample(sorted(identify.find_media([root])[0], key=lambda m: (m.mtime, str(m.path))), 4)]
    assert "B0.JPG" not in names and "D0.JPG" not in names          # the even picks alone would miss both
    assert r.capped and r.scanned == 6
    assert [x["family"] for x in r.family_runs] == ["v012", "v006-v008_test"]
    assert r.base == "v012" and r.confidence == "low" and r.stock is False
    assert "approximate" in r.confidence_why


def test_photos_with_no_file_time_are_left_out_of_the_dating(tmp_path, capsys):
    root = card(tmp_path)
    put(root, "DCIM/100MEDIA/PICT0001.JPG", bare_header(), T0)
    put(root, "DCIM/100MEDIA/PICT0002.JPG", bare_header(), T0 + DAY)
    z1 = put(root, "DCIM/100MEDIA/PICT0003.JPG", still_header(), 0)        # another family, but undated
    z2 = put(root, "DCIM/100MEDIA/PICT0004.JPG", still_header(), 0)
    code, out = run(capsys, root)
    assert code == 0
    assert line(out, "undated:") == "2 photos have no file time and were left out of the dating"
    assert str(z1) in out and str(z2) in out
    assert line(out, "conclusion:") == ("The photos were taken by stock v012 (an OV9732 camera): base v012. If the camera was flashed after these photos, it runs something else now")
    assert "not stock:" not in out and "PICT0001.JPG" in line(out, "oldest:")
    assert line(out, "photos:").startswith("4 found, 2 headers read")
    data = json.loads(run(capsys, root, "--json")[1])
    assert data["undated"] == [str(z1), str(z2)] and data["stock"] is True
    assert data["next"] == identify.NEXT.format(base="v012")
    # only undated photos: nothing to date, nothing to conclude
    only = card(tmp_path / "only")
    put(only, "DCIM/100MEDIA/PICT0001.JPG", bare_header(), 0)
    code, out = run(capsys, only)
    assert code == 1 and line(out, "undated:").startswith("1 photo has no file time")


def test_the_newest_video_is_shown_but_not_used(tmp_path, capsys, monkeypatch):
    root = card(tmp_path)
    put(root, "DCIM/100MEDIA/PICT0001.JPG", bare_header(), T0)
    clip = root / "DCIM" / "100MEDIA" / "MOVI0001.AVI"
    clip.write_bytes(avi(bare_header(quality=50, width=1280, height=720, y=0x21)))
    seen = []
    real = photo_id._reader

    def recording(f):
        read = real(f)

        def wrapped(offset, n):
            out = read(offset, n)
            seen.append(offset + len(out))
            return out
        return wrapped

    monkeypatch.setattr(photo_id, "_reader", recording)
    code, out = run(capsys, root)
    assert code == 0 and photo_id.VIDEO in line(out, "video:")
    assert max(seen) < clip.stat().st_size - 1000          # only headers were read


def test_json(tmp_path, capsys):
    root = card(tmp_path)
    put(root, "DCIM/100MEDIA/PICT0001.JPG", bare_header(), T0)
    put(root, "DCIM/100MEDIA/PICT0002.JPG", still_header(), T0 + DAY)
    code, out = run(capsys, root, "--json")
    data = json.loads(out)
    assert code == 0 and data["reached"] is True
    assert data["family"] == "v012" and data["base"] == "v012" and data["confidence"] == "low"
    assert data["conclusion"].endswith("base v012") and data["stock"] is False and len(data["not_stock"]) == 1
    assert [r["family"] for r in data["family_runs"]] == ["v012", "v006-v008_test"]
    assert data["oldest"]["kind"] == "bare" and data["newest"]["kind"] == "still" and len(data["caveats"]) == 2
    code, out = run(capsys, tmp_path / "empty", "--json")
    assert code == 1 and json.loads(out)["reached"] is False


@pytest.mark.parametrize("bad", ["a.txt"])
def test_other_files_are_ignored(tmp_path, capsys, bad):
    root = card(tmp_path)
    (root / "DCIM" / bad).write_text("hello")
    code, out = run(capsys, root)
    assert code == 1 and line(out, "photos:").startswith("0 found")
