"""The flash steps in docs/01..06 say the same thing: how an update ends, the battery, the edition, the stored
photos, --allow-modified for mods, eject before removing, the Android 11 slot limit and the USB-C cleanup.

DOCS_DIR selects the docs folder (default: the repository's docs/)."""
import os
import re
from pathlib import Path

import pytest

DOCS = Path(os.environ.get("DOCS_DIR", Path(__file__).resolve().parent.parent / "docs"))


def text(name: str) -> str:
    return " ".join((DOCS / name).read_text(encoding="utf-8").split())  # unwrap lines


def section(name: str, heading: str) -> str:
    """The text under a heading up to the next heading of the same or higher level, unwrapped."""
    lines = (DOCS / name).read_text(encoding="utf-8").splitlines()
    start = next(i for i, l in enumerate(lines) if l.startswith("#") and l.lstrip("#").strip() == heading)
    level = len(lines[start]) - len(lines[start].lstrip("#"))
    end = next((i for i in range(start + 1, len(lines))
                if lines[i].startswith("#") and len(lines[i]) - len(lines[i].lstrip("#")) <= level), len(lines))
    return " ".join(" ".join(lines[start:end]).split())


FLASH_STEPS = [
    ("01-FIRST-FLASH.md", "5. Flash (the point of no return)"),
    ("02-RECOVERY.md", "A. The camera boots and the updater runs: restore through the SD card"),
    ("03-FLASH-CANDIDATES.md", "Flashing order, when you are ready"),
    ("04-STAGE.md", "The sequence on the camera"),
    ("06-ANDROID.md", "4. Flash"),
]
FILES = ["01-FIRST-FLASH.md", "02-RECOVERY.md", "03-FLASH-CANDIDATES.md", "04-STAGE.md", "05-CARDS.md", "06-ANDROID.md"]


@pytest.mark.parametrize("name", FILES)
def test_no_contradicting_end_of_update(name):
    t = text(name).lower()
    for wrong in ("not poweroff", "whenever it mounts", "normal screen or has powered itself off",
                  "waits for the card to be removed", "waits for a one-second power-key hold"):
        assert wrong not in t, f"{name}: {wrong!r}"


@pytest.mark.parametrize("name,heading", FLASH_STEPS)
def test_every_flash_step_ends_the_same_way(name, heading):
    s = section(name, heading)
    assert "100%" in s, name
    assert "last screen" in s, name
    assert re.search(r"take the card out, then hold the power key about a second", s, re.I), name


@pytest.mark.parametrize("name,heading", FLASH_STEPS)
def test_every_flash_step_names_battery_and_edition(name, heading):
    s = section(name, heading)
    # 06's flash step points back to its own "Before you flash from a phone" list; read both.
    if name == "06-ANDROID.md":
        s += " " + section(name, "Before you flash from a phone")
    if name == "03-FLASH-CANDIDATES.md":
        s += " " + text(name)[:3000]
    assert re.search(r"fully charged|charge the battery fully", s, re.I), name
    assert "original 2025 series" in s.lower() or "original-series" in s.lower(), name
    assert "Millennium" in s and "CLOT" in s, name


@pytest.mark.parametrize("name,before", [("01-FIRST-FLASH.md", "## 5. Flash"), ("03-FLASH-CANDIDATES.md", "## Flashing order")])
def test_stored_photos_warning_before_the_flash_step(name, before):
    raw = (DOCS / name).read_text(encoding="utf-8")
    head = " ".join(raw[: raw.index(before) + 2000].split())
    assert re.search(r"erase[sd]? (the|those|them).{0,40}photos|photos.{0,80}(erase|replaced)", head, re.I), name


def test_verify_for_mods_shows_allow_modified_and_why():
    s = section("01-FIRST-FLASH.md", "3. Verify")
    assert "--allow-modified" in s
    assert "unless you tell it the change is intended" in s
    assert "--allow-modified" in section("03-FLASH-CANDIDATES.md", "Flashing order, when you are ready")
    assert "unless it is told the change is intended" in text("03-FLASH-CANDIDATES.md")
    assert "--allow-modified" in text("06-ANDROID.md")


@pytest.mark.parametrize("name,heading", [("01-FIRST-FLASH.md", "4. Put it on the card"),
                                          ("06-ANDROID.md", "3. Copy the file to the card root"),
                                          ("03-FLASH-CANDIDATES.md", "Flashing order, when you are ready")])
def test_eject_before_removing(name, heading):
    assert "eject" in section(name, heading).lower(), name


def test_android_slot_limit_and_fallback():
    t = text("06-ANDROID.md")
    assert "Android 11 and later" in t and "Android 7 to 10" in t
    assert "USB card reader" in t and "USB-C" in t
    assert "Files app" in t and "Use a file on the phone" in t
    assert "Choose update file" not in t      # the button's old name


def test_usb_c_cleanup():
    s = section("06-ANDROID.md", "Through the camera's own USB port")
    assert "without its card and wait 10 seconds" in s
    assert "Never switch the camera on with the update file on its card except to flash" in s


def test_ignored_file_diagnosis_lists_the_real_causes():
    s = text("04-STAGE.md")
    assert "needs a file with a fresh one" not in s
    for cause in ("after power-on", "in a folder", "another `JH_5408*.bin`"):
        assert cause in s, cause


def test_candidates_agree_with_first_flash():
    t = text("03-FLASH-CANDIDATES.md")
    assert "Card formatted FAT32" not in t
    assert "is recommended" not in t and "Recommended:" not in t     # the backup is required, not recommended
    assert "No backup, no flash" in t
    assert "did not cause the freeze" in t and "does not fit" in t   # the corrected cause
    assert "The diff confirms" not in t


HARD_STOP = re.compile(r"no backup[^.]{0,40}no flash", re.I)


@pytest.mark.parametrize("name,heading", [("01-FIRST-FLASH.md", "1. Back up your camera's chip (no backup, no flash)"),
                                          ("03-FLASH-CANDIDATES.md", "Flashing order, when you are ready"),
                                          ("06-ANDROID.md", "Before you flash from a phone"),
                                          ("04-STAGE.md", "What it does, in order")])
def test_the_backup_is_a_hard_stop_before_every_flash(name, heading):
    s = section(name, heading)
    assert HARD_STOP.search(s), name
    assert "recommended" not in s.lower(), name


def test_no_doc_calls_the_stamp_or_the_series_a_proof():
    for name in FILES:
        t = text(name).lower()
        assert "only ways to tell editions apart" not in t, name
        assert "identical to every original-series camera read so far" not in t, name


def test_android_card_fix_command():
    t = text("06-ANDROID.md")
    assert "an MBR type Android does not expect" not in t
    assert "card-fix /dev/sdX --clear-dirty" in t
