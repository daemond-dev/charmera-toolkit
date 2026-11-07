"""The flash steps in docs/01..06 say the same thing: how an update ends, the battery, the edition, the stored
photos, --allow-modified for mods, eject before removing, the Android 11 slot limit and the USB-C cleanup. Every doc
holds to the backup rule: the hard stop is stated where a flash starts, every stage example gives --stock, the public
dump is never anyone's --stock outside the candidates' recipe, and no doc calls the series or the stamp a proof.

DOCS_DIR selects the docs folder (default: the repository's docs/); the two READMEs are read from its parent."""
import os
import re
from pathlib import Path

import pytest

DOCS = Path(os.environ.get("DOCS_DIR", Path(__file__).resolve().parent.parent / "docs"))
README = DOCS.parent / "README.md"
APP_README = DOCS.parent / "android" / "README.md"


def text(name: str) -> str:
    return " ".join((DOCS / name).read_text(encoding="utf-8").split())  # unwrap lines


def every_doc():
    """The top README, the app's README and every document in docs/, the incident notes included (a DOCS_DIR copy
    may come without the READMEs)."""
    return [p for p in (README, APP_README) if p.is_file()] + sorted(DOCS.glob("*.md"))


def rel(path: Path) -> str:
    """A doc's path for a message: three of them are called README.md."""
    return path.relative_to(DOCS.parent).as_posix()


def section(name: str, heading: str) -> str:
    """The text under a heading up to the next heading of the same or higher level, unwrapped."""
    lines = (DOCS / name).read_text(encoding="utf-8").splitlines()
    start = next(i for i, l in enumerate(lines) if l.startswith("#") and l.lstrip("#").strip() == heading)
    level = len(lines[start]) - len(lines[start].lstrip("#"))
    end = next((i for i in range(start + 1, len(lines))
                if lines[i].startswith("#") and len(lines[i]) - len(lines[i].lstrip("#")) <= level), len(lines))
    return " ".join(" ".join(lines[start:end]).split())


CANDIDATES_STEPS = "If you rebuild one of these from your own backup"
FLASH_STEPS = [
    ("01-FIRST-FLASH.md", "5. Flash (the point of no return)"),
    ("02-RECOVERY.md", "A. The camera boots and the updater runs: restore through the SD card"),
    ("03-FLASH-CANDIDATES.md", CANDIDATES_STEPS),
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


@pytest.mark.parametrize("name,before", [("01-FIRST-FLASH.md", "## 5. Flash"), ("03-FLASH-CANDIDATES.md", "## " + CANDIDATES_STEPS)])
def test_stored_photos_warning_before_the_flash_step(name, before):
    raw = (DOCS / name).read_text(encoding="utf-8")
    head = " ".join(raw[: raw.index(before) + 2000].split())
    assert re.search(r"erase[sd]? (the|those|them).{0,40}photos|photos.{0,80}(erase|replaced)", head, re.I), name


@pytest.mark.parametrize("name", ["01-FIRST-FLASH.md", "04-STAGE.md", "06-ANDROID.md"])
def test_stored_photos_depend_on_the_file_size(name):
    """A 0x1E0000-byte file replaces the stored photos; a 0x169000-byte file leaves them but empties the list."""
    t = text(name)
    assert "0x1E0000-byte file" in t and "0x169000-byte file" in t, name
    assert re.search(r"0x169000-byte file.{0,60}leaves the photo data in place, but the camera empties its photo list", t), name


def test_verify_for_mods_shows_allow_modified_and_why():
    s = section("01-FIRST-FLASH.md", "3. Verify")
    assert "--allow-modified" in s
    assert "unless you tell it the change is intended" in s
    assert "--allow-modified" in section("03-FLASH-CANDIDATES.md", CANDIDATES_STEPS)
    assert "unless it is told the change is intended" in text("03-FLASH-CANDIDATES.md")
    assert "--allow-modified" in text("06-ANDROID.md")


@pytest.mark.parametrize("name,heading", [("01-FIRST-FLASH.md", "4. Put it on the card"),
                                          ("06-ANDROID.md", "3. Copy the file to the card root"),
                                          ("03-FLASH-CANDIDATES.md", CANDIDATES_STEPS)])
def test_eject_before_removing(name, heading):
    assert "eject" in section(name, heading).lower(), name


@pytest.mark.parametrize("name,heading", [("01-FIRST-FLASH.md", "4. Put it on the card"),
                                          ("02-RECOVERY.md", "A. The camera boots and the updater runs: restore through the SD card"),
                                          ("04-STAGE.md", "The sequence on the camera")])
def test_the_camera_cable_is_connected_the_way_the_app_does(name, heading):
    """Plugging the cable into a switched-off camera switches it on and runs the updater from its card."""
    s = section(name, heading)
    assert re.search(r"cable into a switched-off camera switches it on and runs the updater", s), name
    assert re.search(r"take the card out, switch the camera on with its power key, wait 10 seconds", s), name


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


def intro(path: Path) -> str:
    """A file's text before its first ## heading, unwrapped."""
    lines = path.read_text(encoding="utf-8").splitlines()
    end = next((i for i, l in enumerate(lines) if l.startswith("## ")), len(lines))
    return " ".join(" ".join(lines[:end]).split())


@pytest.mark.parametrize("name,heading", [("01-FIRST-FLASH.md", "1. Back up your camera's chip (no backup, no flash)"),
                                          ("02-RECOVERY.md", "A. The camera boots and the updater runs: restore through the SD card"),
                                          ("03-FLASH-CANDIDATES.md", CANDIDATES_STEPS),
                                          ("06-ANDROID.md", "Before you flash from a phone"),
                                          ("04-STAGE.md", "What it does, in order")])
def test_the_backup_is_a_hard_stop_before_every_flash(name, heading):
    s = section(name, heading)
    assert HARD_STOP.search(s), name
    assert "recommended" not in s.lower(), name


@pytest.mark.parametrize("path", [README, DOCS / "02-RECOVERY.md"], ids=["README.md", "02-RECOVERY.md"])
def test_the_hard_stop_opens_the_readme_and_recovery(path):
    s = intro(path)
    assert HARD_STOP.search(s), path.name
    assert "recommended" not in s.lower(), path.name
    if path == README:
        assert "- No backup of your own camera, no flash." in path.read_text(encoding="utf-8")   # the first ground rule


def test_the_docs_say_what_stage_does_without_a_backup():
    """stage warns without --stock and still writes (the owner's decision of 2026-11-06); no doc promises a refusal."""
    for path in every_doc():
        t = " ".join(path.read_text(encoding="utf-8").split()).lower()
        for wrong in ("nothing in this toolkit or its app will write to a card without", "baked in everywhere",
                      "baked into the commands"):
            assert wrong not in t, f"{rel(path)}: {wrong!r}"
    for name, heading in (("01-FIRST-FLASH.md", "1. Back up your camera's chip (no backup, no flash)"),
                          ("04-STAGE.md", "What it does, in order")):
        s = section(name, heading)
        assert re.search(r"without `--stock`,? (?:`stage` |it )shows a WARN line", s, re.I), name
        assert "still writes a file that passes" in s, name
    assert "still writes a file that passes" in " ".join(README.read_text(encoding="utf-8").split())


STAGE_EXAMPLE = re.compile(r"\b(?:charmera|charmera_toolkit)\s+stage\s+[^`\s]")


def test_every_stage_example_gives_the_backup():
    """Every `charmera stage FILE ...` line, in a code block or inline, carries --stock."""
    missing = []
    for path in every_doc():
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if STAGE_EXAMPLE.search(line) and "--stock" not in line:
                missing.append(f"{rel(path)}:{number}: {line.strip()}")
    assert not missing, "\n".join(missing)


def test_the_public_dump_is_nobodys_stock_outside_the_recipe():
    """--stock charmera_stock_latest.bin compares a file with the public dump itself; only the candidates' recipe,
    which documents how those files were checked, may show it."""
    found = []
    recipe = section("03-FLASH-CANDIDATES.md", "Recipe")
    for path in every_doc():
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if "--stock charmera_stock_latest.bin" in line:
                if path.name == "03-FLASH-CANDIDATES.md" and " ".join(line.split()) in recipe:
                    continue
                found.append(f"{rel(path)}:{number}: {line.strip()}")
    assert not found, "\n".join(found)
    assert recipe.count("--stock charmera_stock_latest.bin") == 3


# Wording that treats the series, the body or the stamp as an identity or a proof of fit, and claims the docs have
# corrected. The research report keeps its history: there a phrase may stand in a paragraph that marks it as
# superseded or corrected on 2026-11-06.
RETIRED = (
    "only ways to tell editions apart",
    "identical to every original-series camera read so far",
    "exist for the original series",
    "on the original series",
    "original 2025 series, unmodified",
    "only for an original-series body",
    "onto cameras that already ran this build",
    "back on cameras that already ran this build",
    "the one hardware test",
    "what the one test showed",
    "either size the camera accepts",
    "any update file replaces the photos",
    "whatever flags are given",
)
DATED = re.compile(r"(superseded|corrected) 2026-11-06", re.I)


def test_no_doc_calls_the_stamp_or_the_series_a_proof():
    problems = []
    for path in every_doc():
        raw = path.read_text(encoding="utf-8")
        for paragraph in re.split(r"\n\s*\n", raw):
            flat = " ".join(paragraph.split()).lower()
            for phrase in RETIRED:
                if phrase in flat and not (path.name == "13-PHASE1-REPORT.md" and DATED.search(flat)):
                    problems.append(f"{rel(path)}: {phrase!r}")
    assert not problems, "\n".join(problems)


def test_the_report_keeps_its_history_and_marks_what_changed():
    t = text("13-PHASE1-REPORT.md")
    assert t.count("Superseded 2026-11-06") >= 8
    for kept in ("only ways to tell editions apart", "original 2025 series, unmodified software",
                 "When this was written the toolkit built only 0x1E0000 files"):
        assert kept in t, kept
    assert "0x169000-byte update files (`pack --official-size`)" in t   # the note at the top names the second size


def test_android_card_fix_command():
    t = text("06-ANDROID.md")
    assert "an MBR type Android does not expect" not in t
    assert "card-fix /dev/sdX --clear-dirty" in t
