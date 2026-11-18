"""The Android app: its checker carries its own copy of the build identity, which must match
charmera_toolkit/image.py and the stock dump; its card handling and the release workflow keep the safeguards below.

The Java code itself is tested on the JVM by android/build.sh (android/test). There is no emulator in CI, so the
lifecycle rules of MainActivity are checked here in its source.
"""
import collections
import hashlib
import importlib.util
import os
import re
from pathlib import Path

from charmera_toolkit import checksum, flash, image

REPO = Path(__file__).resolve().parent.parent
PKG = REPO / "android" / "src" / "io" / "github" / "daemonddev" / "charmera"
SRC = PKG / "UpdateCheck.java"
ACTIVITY = PKG / "MainActivity.java"
GATE = PKG / "WriteGate.java"
FLOW = PKG / "FlashFlow.java"
WORKFLOW = REPO / ".github" / "workflows" / "android.yml"
ANDROID_README = REPO / "android" / "README.md"
BUILD = REPO / "android" / "build.sh"


def _joined(java: str) -> str:
    """The source with each run of concatenated string literals ("a" + "b") read as one, so wording can be found."""
    return re.sub(r'"\s*\+\s*"', "", java)


def _code_area_constant(java: str) -> str:
    m = re.search(r'PUBLIC_CODE_AREA_SHA256 = "([0-9a-f]{64})"', java)
    assert m, "UpdateCheck.java has no PUBLIC_CODE_AREA_SHA256 constant"
    return m.group(1)


def test_app_build_identity_matches_the_python_one():
    """The app names the public build by the same fingerprints as image.py, and finds the marks by the same shapes."""
    java = SRC.read_text(encoding="utf-8")
    block = java[java.index("PUBLIC_BUILD = new Build("): java.index(");", java.index("PUBLIC_BUILD = new Build("))]
    b = image.PUBLIC_BUILD
    for text in (b.software_sha256, b.official_sha256, b.sensor_table_sha256):
        assert f'"{text}"' in block, text
    for number in (b.stamp_offset, b.build_offset, b.sensor_table_offset):
        assert re.search(rf"0x{number:X}\b", block, re.I), hex(number)
    assert "PUBLIC_CODE_AREA_SHA256" in block
    assert f'SHARED_STAMP = "{b.stamp}"' in java and b.stamp == image.SHARED_STAMP == checksum.STOCK_TIMESTAMP
    assert f"UPDATE_SIZE = 0x{checksum.UPDATE_FILE_SIZE:X}" in java
    assert f"OFFICIAL_SIZE = 0x{checksum.OFFICIAL_UPDATE_SIZE:X}" in java
    assert f"RESOURCE_BASE = 0x{image.RESOURCE_BASE:X}" in java
    assert f"CODE_AREA_END = 0x{image.CODE_AREA_END:X}" in java
    assert f"RESOURCE_LIMIT = 0x{image.RESOURCE_LIMIT:X}" in java
    # the same reset-table signature, and no mark read at a fixed offset any more
    assert "SENSOR_RESET_TABLE = {(byte) 0xFD, 0, 0x20, 0, (byte) 0xFF, (byte) 0xFF}" in java
    assert image.SENSOR_RESET_TABLE == bytes.fromhex("fd002000ffff")
    check = java[java.index("public static Result check(String displayName, byte[] data, Build build, byte[] reference)"):]
    assert "build.stampOffset" not in check.split("boolean madeFrom")[0]
    assert "regionEquals(data, build.stampOffset" not in java and "regionEquals(data, build.buildOffset" not in java


def test_app_names_the_film_mods_build_as_image_py_does():
    """The second known build: the same fingerprint, offsets and compile date as image.FILM_MODS_BUILD."""
    java = SRC.read_text(encoding="utf-8")
    start = java.index("FILM_MODS_BUILD = new Build(")
    block = java[start: java.index(");", start)]
    b = image.FILM_MODS_BUILD
    for text in (b.official_sha256, b.sensor_table_sha256):
        assert f'"{text}"' in block, text
    for number in (b.stamp_offset, b.build_offset, b.sensor_table_offset):
        assert re.search(rf"0x{number:X}\b", block, re.I), hex(number)
    assert f"compiled {b.compiled}" in block and b.compiled == "Nov 19 2025"
    assert "OTHER_KNOWN_BUILDS = {FILM_MODS_BUILD}" in java
    restore = Path(os.environ.get("CHARMERA_FILM_MODS_RESTORE", ""))
    if restore.is_file():
        data = restore.read_bytes()
        assert f'"{hashlib.sha256(data[:0x120000]).hexdigest()}"' in block
        assert f'"{hashlib.sha256(data[0x200:0x240]).hexdigest()}"' in block      # the ARM vector table
        assert hashlib.sha256(data).hexdigest() == b.official_sha256 and hashlib.sha1(data).hexdigest() == b.official_sha1


def _build_block(java: str, name: str) -> str:
    start = java.index(f"{name} = new Build(")
    return java[start: java.index(");", start)]


def test_app_code_area_hash_is_the_stock_dumps(stock_image):
    """The app calls a file's program the public dump's only when bytes 0..0x120000 hash to this constant."""
    java = SRC.read_text(encoding="utf-8")
    assert _code_area_constant(java) == hashlib.sha256(stock_image[0:0x120000]).hexdigest()
    assert image.PUBLIC_BUILD.official_sha256 == hashlib.sha256(stock_image[:image.OFFICIAL_UPDATE_SIZE]).hexdigest()
    assert image.PUBLIC_BUILD.official_sha1 == hashlib.sha1(stock_image[:image.OFFICIAL_UPDATE_SIZE]).hexdigest()
    assert image.PUBLIC_BUILD.official_byte_sum == checksum.byte_sum(stock_image[:image.OFFICIAL_UPDATE_SIZE])
    # The vector table the app compares a file laid out like the public build with, when no backup is given.
    assert f'"{hashlib.sha256(stock_image[0x200:0x240]).hexdigest()}"' in _build_block(java, "PUBLIC_BUILD")
    assert "VECTORS = 0x200" in java and "VECTORS_END = 0x240" in java and image.HEADER_SIZE == 0x200


def test_app_finds_the_sensor_program_by_the_same_rule_as_image_py(stock_image):
    """S6: the code literal is looked for at 4-byte-aligned offsets only, every referenced init table is kept so that
    several programs fail, and a changed copy of a known build needs that build's sensor table."""
    java = SRC.read_text(encoding="utf-8")
    find = _bodies(java, "public static Marks findMarks(byte[] data)")[0]
    assert "alignedIndexOf(data, literal, VA_OFFSET, end)" in find and "indexOf(data, literal" not in find
    aligned = _bodies(java, "static int alignedIndexOf(")[0]
    assert "& ~3" in aligned and "i += 4" in aligned
    assert "referenced.add(" in find and "pickedReferenced" not in find
    sensor = java[java.index("if (marks.severalPrograms())"): java.index("// The software: against the backup")]
    assert "several sensor programs" in sensor
    assert "ref.referencedFrom >= 0 && marks.referencedFrom < 0" in sensor
    assert "marks.sensorSha256.equals(layout.sensorSha256)" in java and "laid out like" in java
    # the literal of the real table lies at an aligned offset, so the rule keeps finding it
    tables = image.find_sensor_tables(stock_image)
    assert tables.referenced_from == 0x7B91C and tables.referenced_from % 4 == 0


def test_app_takes_the_stamp_and_a_changed_copy_as_image_py_does(stock_image):
    """Parity: the stamp is the one after '*.bin', and there is none when several different ones follow it; a file
    laid out like a known build is that build with changes only when its stamp and sensor table are that build's.
    image.py does so on the public dump changed that way here, and CandidatesTest holds the app to the same files."""
    java = SRC.read_text(encoding="utf-8")
    find = _bodies(java, "public static Marks findMarks(byte[] data)")[0]
    assert "indexOf(data, updatePattern, i - 64, i) >= 0" in find and "near.size() > 1 ? near" in find
    assert "firstNear >= 0 ? firstNear : runs == 1 ? firstRun" in find
    # the stamp, the build string and the compile date from offset 0 as in image.py, the sensor program after the header
    assert "for (int i = 0; i + 13 <= end; i++)" in find and "indexOf(data, jh, 0, end)" in find
    assert "for (int i = 0; i + 12 <= end && compiled == null; i++)" in find
    assert "indexOf(data, SENSOR_RESET_TABLE, VA_OFFSET, end)" in find
    assert "layout != null && marks.stamp.equals(layout.stamp)" in _check_body(java)
    restamped = bytearray(stock_image)
    restamped[0x218C4:0x218D0] = b"202601010000"
    match = image.detect_build(bytes(restamped))
    assert match.made_from is None and match.layout is image.PUBLIC_BUILD
    twice = bytearray(stock_image)
    twice[0xFFF8:0x1000D] = b"*.bin\x00xx202601010101\x00"      # a second stamp after a second '*.bin'
    marks = image.find_marks(bytes(twice))
    assert marks.stamp is None and marks.stamp_conflict == ("202601010101", "202505201029")


_SOURCE_TREE_PREFIX = b"\\2247\\5408\\"
_SOURCE_TREE_FOLDER = re.compile(rb"([\x20-\x5b\x5d-\x7e]{1,64})\\")


def _source_tree(data: bytes):
    """The source-tree folder by the app's rule (S15), as (folder, offset where its name first begins, how many source
    paths name it): the folder named most often after \\2247\\5408\\ in the code area, the first found on a tie, or
    (None, None, 0). A name is 1 to 64 bytes from 0x20 to 0x7E other than the backslash, and every place the prefix
    starts is looked at, even one that begins at the backslash ending the path before."""
    code, found = data[:image.CODE_AREA_END], []
    at = code.find(_SOURCE_TREE_PREFIX)
    while at >= 0:
        m = _SOURCE_TREE_FOLDER.match(code, at + len(_SOURCE_TREE_PREFIX))
        if m:
            found.append((m.group(1).decode("ascii"), m.start(1)))
        at = code.find(_SOURCE_TREE_PREFIX, at + 1)
    if not found:
        return None, None, 0
    names = collections.Counter(name for name, _ in found)
    folder = names.most_common(1)[0][0]
    return folder, next(at for name, at in found if name == folder), names[folder]


def test_app_records_each_known_builds_source_folder():
    """S15: the source-tree folder is a build mark; the app finds it by content, reports it where its name begins, and
    records it for both known builds."""
    java = SRC.read_text(encoding="utf-8")
    assert 'SOURCE_TREE_PREFIX = "\\\\2247\\\\5408\\\\".getBytes(' in java
    find = _bodies(java, "public static Marks findMarks(byte[] data)")[0]
    assert "indexOf(data, SOURCE_TREE_PREFIX, 0, end)" in find and "e.getValue() > trees.get(sourceTree)" in find
    # the name's bytes, the offset kept (where the name begins) and the next search (from the next byte, so every
    # place the prefix starts is looked at, as image.py does)
    assert "data[q] >= 0x20 && data[q] <= 0x7E && data[q] != '\\\\'" in find and "MAX_FOLDER = 64;" in java
    assert "int p = at + SOURCE_TREE_PREFIX.length;" in find and "treeAt.put(folder, p);" in find
    assert "at = indexOf(data, SOURCE_TREE_PREFIX, at + 1, end);" in find and "next = q + 1;" not in find
    assert '"v006",' in _build_block(java, "PUBLIC_BUILD") and '"v008_test",' in _build_block(java, "FILM_MODS_BUILD")
    assert "A different source folder shows a different build, the same one does not show the same build, and neither" \
           " says whether the file fits your camera." in _joined(java)
    assert "sameSourceTree(layout, marks)" in _check_body(java)


def _check_body(java: str) -> str:
    """UpdateCheck from the full check() on: the checks, the vector table and the helpers after them."""
    return java[java.index("public static Result check(String displayName, byte[] data, Build build, byte[] reference)"):]


def test_app_source_folders_are_the_files_own(stock_image):
    """The folders the app records are the ones the files' own source paths name, all 27 of them in each. On both
    files the app's rule and image.py give the same folder, the same offset (where the folder's name begins: 0x3a4d2
    in the public dump, 0x464ba in the film-mods file) and the same count."""
    files = [(stock_image, "v006", 0x3A4D2)]
    restore = Path(os.environ.get("CHARMERA_FILM_MODS_RESTORE", ""))
    if restore.is_file():
        files.append((restore.read_bytes(), "v008_test", 0x464BA))
    for data, folder, at in files:
        assert _source_tree(data) == (folder, at, 27), folder
        assert data[:image.CODE_AREA_END].count(b"\\2247\\5408\\" + folder.encode("ascii") + b"\\") == 27
        assert data[at - 11: at] == b"\\2247\\5408\\" and data[at: at + len(folder)] == folder.encode("ascii")
        marks = image.find_marks(data)
        assert (marks.source_tree, marks.source_tree_offset, marks.source_tree_paths) == (folder, at, 27), folder


def test_app_rule_and_image_py_agree_on_overlapping_paths():
    """Every place the prefix starts is looked at, in the app as in image.py, so a path that starts at the backslash
    ending the one before counts on both sides (the round-three review's overlap)."""
    code = bytearray(0x1000)
    nested = b"G:\\code\\2247\\5408\\v006\\2247\\5408\\v007\\init.c"
    plain = b"G:\\code\\2247\\5408\\v007\\host\\init.c"
    code[0x100:0x100 + len(nested)] = nested
    code[0x400:0x400 + len(plain)] = plain
    code = bytes(code)
    assert _source_tree(code) == image.source_tree_marks(code) == ("v007", 0x121, 2)


def test_app_source_folders_match_image_py():
    """S15 parity: each known build's folder in the app is image.py's KnownBuild.source_tree."""
    java = SRC.read_text(encoding="utf-8")
    for name, build in (("PUBLIC_BUILD", image.PUBLIC_BUILD), ("FILM_MODS_BUILD", image.FILM_MODS_BUILD)):
        assert f'"{build.source_tree}",' in _build_block(java, name), name
    assert (image.PUBLIC_BUILD.source_tree, image.FILM_MODS_BUILD.source_tree) == ("v006", "v008_test")


def test_app_takes_a_changed_copy_only_with_the_builds_compile_date():
    """S15 completed: a file laid out like a known build is that build with changes only when it carries that build's
    stamp, sensor table, source folder and compile date, each where the build records one. The app records image.py's
    compile date for both known builds; CandidatesTest holds it to the public dump and the film-mods file with another
    date, and with v007 for v006."""
    java = SRC.read_text(encoding="utf-8")
    for name, build in (("PUBLIC_BUILD", image.PUBLIC_BUILD), ("FILM_MODS_BUILD", image.FILM_MODS_BUILD)):
        assert f'"{build.compiled}",' in _build_block(java, name), name
    assert (image.PUBLIC_BUILD.compiled, image.FILM_MODS_BUILD.compiled) == ("Oct 14 2025", "Nov 19 2025")
    check = _check_body(java)
    made = check[check.index("Build madeFrom = "): check.index(";", check.index("Build madeFrom = "))]
    for clause in ("marks.stamp.equals(layout.stamp)", "marks.sensorSha256.equals(layout.sensorSha256)",
                   "sameSourceTree(layout, marks)", "sameCompiled(layout, marks)"):
        assert clause in made, clause
    same = _bodies(java, "static boolean sameCompiled(")[0]
    assert "return b.compiled == null || b.compiled.equals(marks.compiled);" in same
    assert '"its compile date"' in check and '"the compile date"' in check


def test_app_says_what_a_backup_of_a_known_build_proves():
    """S3: a backup whose firmware is a known, public build makes the same table a warning, not a pass; the sensor
    line speaks of the firmware in the backup, never of the camera's own; a backup made of the exact public dump
    stays refused with the reason that allows it is this camera's read."""
    java = _joined(SRC.read_text(encoding="utf-8"))
    sensor = java[java.index("if (marks.severalPrograms())"): java.index("// The software: against the backup")]
    assert "Build refBuild = knownBuildOf(reference, known);" in sensor
    warn = sensor[sensor.index("} else if (refBuild != null) {"):]
    assert warn.index("Level.WARN") < warn.index("Level.PASS")
    assert "only if the backup was read before the camera was ever flashed" in warn
    assert "your camera's own firmware" not in java and "the way its own firmware does" not in java
    problem = _bodies(java, "public static String backupProblem(")[0]
    assert "a read of a camera that was flashed with the public restore file and has changed no setting since" in problem
    assert "resourceProblems(data, entries)" in problem and "charmera inspect" in problem
    notes = _bodies(java, "public static List<String> backupNotes(")[0]
    assert "knownBuildOf(data, knownBuilds(build))" in notes and "not blank" in notes


def test_app_offers_the_stock_restore_file_only_for_the_public_build():
    """The leftover 0.4 wording is gone: a failed check never sends the owner to the stock restore file as the way to
    restore a camera, and no screen says that owners put it back on cameras that ran this build. A camera flashed with
    the restore file reads as the public build too, so the backup that shows it must be one read before the camera was
    ever flashed (S3)."""
    for path in sorted(PKG.glob("*.java")):
        text = _joined(path.read_text(encoding="utf-8"))
        assert "To restore the camera, tap" not in text, path.name
        assert "already ran this build" not in text, path.name
        assert "left unable to take pictures by" not in text, path.name
        assert "whose backup shows the public build" not in text, path.name
    java = _joined(SRC.read_text(encoding="utf-8"))
    assert "The stock restore file is only for a camera whose backup, read before the camera was ever flashed, shows" \
           " the public build. For any other camera, make the file from its own backup with charmera pack on a" \
           " computer." in java
    assert "whose backup, read before the camera was ever flashed, shows the public build" in _joined(
        ACTIVITY.read_text(encoding="utf-8"))
    for path in (PKG / "Candidates.java", ACTIVITY):
        assert "what their cameras ran before isn't known" in _joined(path.read_text(encoding="utf-8")), path.name


def test_app_uses_no_library_call_newer_than_its_minimum_android():
    """minSdkVersion is 24 (Android 7.0); String.join and the other Java 8 library additions below came with API 26."""
    manifest = (REPO / "android" / "AndroidManifest.xml").read_text(encoding="utf-8")
    assert 'android:minSdkVersion="24"' in manifest
    for path in sorted(PKG.glob("*.java")):
        text = path.read_text(encoding="utf-8")
        for call in ("String.join(", "List.of(", "Map.of(", "Set.of(", ".stream()", "java.time.", "Files.readAllBytes"):
            assert call not in text, f"{path.name}: {call}"


def buttons_end(java: str) -> str:
    """updateButtons() without its closing brace and trailing whitespace."""
    return _bodies(java, "private void updateButtons()")[0][:-1].rstrip()


def _bodies(java: str, opener: str):
    """The text of every block that starts with opener, up to its matching closing brace."""
    out = []
    for m in re.finditer(re.escape(opener), java):
        i = java.index("{", m.start())
        depth = 0
        for j in range(i, len(java)):
            if java[j] == "{":
                depth += 1
            elif java[j] == "}":
                depth -= 1
                if depth == 0:
                    out.append(java[i: j + 1])
                    break
    return out


def test_app_work_that_finishes_late_never_touches_a_closed_screen():
    java = ACTIVITY.read_text(encoding="utf-8")
    posted = _bodies(java, "ui.post(() ->")
    assert posted, "no posted results found"
    for body in posted:
        assert re.match(r"\{\s*if \(gone\(\)", body), body[:120]
    # Every screen class: the zip, the eject receiver, the flash log and the photo check post through the same rule.
    for path in sorted(PKG.glob("*.java")):
        text = path.read_text(encoding="utf-8")
        assert "runOnUiThread(" not in text, path.name
        for m in re.finditer(r"\b(\w+)\.post(Delayed)?\(", text):
            # only lambda posts, so each body can be checked below; MAIN.post is the operation engine's (checked below)
            assert text.startswith("() ->", m.end()), f"{path.name}: {text[m.start():m.start() + 80]}"
            if m.group(1) == "MAIN":
                continue
            body = _bodies(text[m.start():], "() ->")[0]
            assert re.match(r"\{\s*if \(gone\(\)", body), f"{path.name}: {body[:120]}"
    assert "return isFinishing() || isDestroyed();" in java
    # A write or removal reports through MAIN to the screen open when it ends, and only to one that is not gone.
    ended = _bodies(java, "MAIN.post(() ->")
    assert len(ended) == 1 and "s != null && !s.gone()" in ended[0], ended
    # The worker lives as long as the process and is never shut down, so a late inspectCard cannot hit a dead executor.
    assert re.search(r"private static final ExecutorService WORKER\b", java)
    assert ".shutdown" not in java


def test_app_keeps_a_running_write_or_removal_across_screens():
    """app-7: the running operation is the process's, not the screen's. A recreated or second screen keeps Eject and
    the card buttons off while it runs and shows how it ended; the outcome is handed over in a finally."""
    java = ACTIVITY.read_text(encoding="utf-8")
    assert "private static Operation last;" in java and "private static MainActivity screen;" in java
    assert "return comparing || working();" in java and "return last != null && last.outcome == null;" in java
    create = _bodies(java, "protected void onCreate(")[0]
    assert "if (last != null && !last.shown)" in create and "watching = last;" in create
    resume = _bodies(java, "protected void onResume()")[0]
    assert resume.index("screen = this;") < resume.index("catchUp();") < resume.index("inspectCard()")
    assert "if (screen == this)" in _bodies(java, "protected void onDestroy()")[0]
    for opener in ("private void write(", "private void clean("):
        body = _bodies(java, opener)[0]
        assert "start(" in body and "} finally {" in body and "finish(op, " in body.split("} finally {")[1], opener
        assert "busy = " not in body and "ui.post(" not in body, opener
    catch_up = _bodies(java, "private void catchUp()")[0]
    assert "inspectCard();" in catch_up and catch_up.rstrip("}\n ").endswith("updateButtons();")
    buttons = _bodies(java, "private void updateButtons()")[0]
    assert "chooseButton.setEnabled(!busy())" in buttons
    assert "if (busy() || archiving())" in _bodies(java, "private void pickCard()")[0]
    # a running zip is refused by the methods themselves, not only by the disabled buttons
    for opener in ("private void confirmWrite()", "private void confirmClean()", "private void clean(",
                   "private void eject()"):
        assert "busy() || archiving()" in _bodies(java, opener)[0], opener
    assert 'busy() || archiving() ? "a write, a removal or a zip is already running"' in \
           _bodies(java, "private void write(")[0]


def test_app_survives_unusual_picker_results():
    java = ACTIVITY.read_text(encoding="utf-8")
    pickers = re.findall(r"private void (pick\w*)\(", java)
    # the backup, the update file, the drive and the zip destination (ACTION_CREATE_DOCUMENT, application/zip)
    assert {"pickBackup", "pickFile", "pickCard", "pickZip"} <= set(pickers), pickers
    for body in _bodies(java, "private void pick"):
        assert "startActivityForResult" in body
        assert "catch (ActivityNotFoundException" in body, body[:200]
    pick_zip = _bodies(java, "private void pickZip(")[0]
    assert "Intent.ACTION_CREATE_DOCUMENT" in pick_zip and '"application/zip"' in pick_zip
    # Every other screen the app opens (save a copy, the eject pages, the colour card, the installer, links) too: each
    # start sits in a try whose first catch takes ActivityNotFoundException.
    for path in sorted(PKG.glob("*.java")):
        text = path.read_text(encoding="utf-8")
        for m in re.finditer(r"\bstartActivity(ForResult)?\(", text):
            before = text[:m.start()]
            tried = before.rindex("try {")
            assert before.count("{", tried) - before.count("}", tried) >= 1, f"{path.name}: {text[m.start():][:80]}"
            first_catch = text.index("catch (", m.end())
            assert "ActivityNotFoundException" in text[first_catch:text.index(")", first_catch)], \
                f"{path.name}: {text[m.start():][:80]}"
    result = _bodies(java, "protected void onActivityResult(")[0]
    assert "DocumentsContract.isTreeUri(uri)" in result
    read_card = _bodies(java, "private Card readCard(")[0]
    assert "catch (RuntimeException" in read_card
    load = _bodies(java, "private void loadFile(")[0]
    assert "catch (IOException | RuntimeException" in load


def test_app_treats_a_card_it_cannot_list_as_an_error_and_looks_again_on_resume():
    java = ACTIVITY.read_text(encoding="utf-8")
    fill = _bodies(java, "private void fillCard(")[0]
    assert fill.count("q == null") >= 2 and "CardState.NOT_FOUND" in fill
    resume = _bodies(java, "protected void onResume()")[0]
    assert "inspectCard()" in resume


def test_app_writes_exactly_what_the_dialog_showed_after_checking_and_listing_again():
    java = ACTIVITY.read_text(encoding="utf-8")
    confirm = _bodies(java, "private void confirmWrite()")[0]
    assert "write(c, data, check)" in confirm
    write = _bodies(java, "private void write(")[0]
    assert "fileData" not in write and "fileCheck" not in write
    report = _bodies(java, "private String writeAndReport(")[0]
    first_check = report.index("WriteGate.refusal(check, data, backupSha256, loadingBackup, false)")
    relist = report.index("readCard(t)")
    assert first_check < relist < report.index("writeAndVerify(")
    assert "writeRefusal()" in report
    # After a failure the card is listed again and the message says what is on it.
    assert "readCard(t).state.afterwards()" in report
    load = _bodies(java, "private void loadFile(")[0]
    assert "dismissDialog()" in load


def test_app_handles_an_incoming_file_once():
    java = ACTIVITY.read_text(encoding="utf-8")
    create = _bodies(java, "protected void onCreate(")[0]
    assert re.search(r"if \(state == null && .*handleIncoming\(first\)", create, re.S)
    assert "FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY" in create
    new_intent = _bodies(java, "protected void onNewIntent(")[0]
    assert "setIntent(intent)" in new_intent


def test_app_delete_results_are_checked():
    java = ACTIVITY.read_text(encoding="utf-8")
    calls = re.findall(r"[^\n]*DocumentsContract\.deleteDocument\([^\n]*", java)
    assert len(calls) == 1 and "? null :" in calls[0], calls
    # One delete for the whole app: removing update files, the photo wipe and dropping an unfinished zip all go
    # through delete(), whose answer is checked; no other class deletes anything.
    everywhere = [line for path in sorted(PKG.glob("*.java"))
                  for line in re.findall(r"[^\n]*deleteDocument\([^\n]*", path.read_text(encoding="utf-8"))]
    assert everywhere == calls, everywhere
    assert calls[0].strip() in _bodies(java, "private String delete(Uri doc)")[0]


def test_app_wipes_only_photos_whose_zip_entry_read_back_the_same():
    """Back up photos: the zip is synced to the phone's storage, read back and checked entry by entry (CRC-32 and
    length) before anything leaves the camera; only then, and only when asked, the files whose entries matched, that
    read whole and that the camera made are deleted, one each, through delete(). Any failure deletes nothing."""
    java = ACTIVITY.read_text(encoding="utf-8")
    run = _bodies(java, "private String runArchive(")[0]
    # the zip is finished, flushed and fsynced before it is read back, and read back before any wipe
    sync = run.index("fos.getFD().sync();")
    assert run.index("zip.finish();") < run.index("zip.flush();") < sync
    write_failed = run.index("} catch (IOException | RuntimeException e) {", sync)
    failed = run[write_failed:run.index("}", run.index("return ", write_failed))]
    assert "Nothing was deleted from the camera." in _joined(failed) and "delete(dest)" in failed
    verify = run.index("verifyZip(dest, written, verified);")
    assert sync < verify and "verified.size() != written.size()" in run[verify:]
    refused = run.index("if (verifyProblem != null) {")
    assert verify < refused and "return " in run[refused:run.index("}", refused)]
    assert "Nothing was deleted from the" in _joined(run[refused:run.index("}", refused)])
    # the wipe runs only when asked, after every return above, and nowhere else
    wipe = run.index("wipeVerified(sourceOf, deletable, verified)")
    assert refused < wipe and "if (wipe) {" in run[refused:wipe]
    assert java.count("wipeVerified(") == 2      # its definition and this one call
    # with delete on, the zip also holds the untouched original of each date-fixed copy, read back like every entry,
    # and the card file goes only when that original matched too
    # (only for a first copy that read whole: no other card file can be deleted, so none other needs its original)
    assert "if (wipe && cp.fixed && whole) {" in run and "copyInto(zip, m, ORIGINALS, false, false, tz, lower);" in run
    # ...under the card file's own name (DateFix.entryName, unique in the same lower set), not the copy's new name
    assert "String original = orig.entry;" in run and "ORIGINALS + entry" not in run
    assert "Copied cp = copyInto(zip, m, \"\", dateNames, fix, tz, lower);" in run
    assert "written.put(original, new long[]{orig.crc, orig.length});" in run
    originals = run.index("Set<String> deletable = ZipCheck.deletable(removable, verified, originalOf);")
    assert verify < originals < wipe
    check = (PKG / "ZipCheck.java").read_text(encoding="utf-8")
    decide = _bodies(check, "static Set<String> deletable(")[0]
    assert "if (!verified.contains(entry)) {" in decide
    assert "if (original != null && !verified.contains(original)) {" in decide
    # a file may go only when it read as long as the card lists it and is the camera's own
    assert "boolean whole = m.size >= 0 && cp.read == m.size;" in run      # an unknown size is never "whole"
    assert re.search(r"if \(!whole\) \{\s*shortRead\+\+;\s*"
                     r"\} else if \(cp\.camera\) \{\s*removable\.add\(entry\);", run)
    copy = _bodies(java, "private Copied copyInto(")[0]
    # the camera's own: a JPEG with its fingerprints, an AVI with its fixed 2010-06-29 (any AVI is not enough)
    assert "c.camera = DateFix.isCharmeraAvi(head);" in copy and "c.camera = DateFix.isCharmeraJpeg(head);" in copy
    assert "DateFix.isAvi(head)" not in copy
    assert "String entry = DateFix.entryName(m.path, stem, prefix, lower);" in copy
    fix = (PKG / "DateFix.java").read_text(encoding="utf-8")
    camera_avi = _bodies(fix, "static boolean isCharmeraAvi(byte[] data)")[0]
    assert "readAviDates(data)" in camera_avi and "isBogus(parseAviDate(f.value))" in camera_avi
    assert "return true;" in camera_avi and camera_avi.rstrip("}\n\r ").endswith("return false;")
    # the zip is never saved onto the camera's own card, and a refused zip deletes the save screen's empty file
    start = _bodies(java, "private void startArchive(")[0]
    assert "onCameraCard(dest) ? " in start and "Choose a place on the phone, not the" in start
    refused = start[start.index("if (refusal != null) {"):start.index("return;", start.index("if (refusal != null) {"))]
    assert "WORKER.execute(() -> delete(dest));" in refused and "clearOutcome(shots);" in refused
    on_card = _bodies(java, "private boolean onCameraCard(Uri dest)")[0]
    assert "EXTERNAL_STORAGE.equals(dest.getAuthority())" in on_card and "DocumentsContract.getDocumentId(dest)" in on_card
    assert "mine.equalsIgnoreCase(id.substring(0, colon))" in on_card and "String mine = treeUuid();" in on_card
    # read back: an entry counts only with the CRC-32 and length written, and a name seen twice counts for neither
    assert "verified.addAll(ZipCheck.verify(raw, written));" in _bodies(java, "private void verifyZip(")[0]
    back = _bodies((PKG / "ZipCheck.java").read_text(encoding="utf-8"), "static Set<String> verify(")[0]
    assert "want != null && want[0] == crc.getValue() && want[1] == length" in back
    assert "if (!seen.add(entry)) {" in back and "verified.remove(entry);" in back
    wiped = _bodies(java, "private String wipeVerified(")[0]
    skip = wiped.index("if (!removable.contains(e.getKey()) || !verified.contains(e.getKey())) {")
    assert skip < wiped.index("continue;", skip) < wiped.index("delete(e.getValue().uri)")
    assert "deleteDocument" not in wiped and wiped.count("delete(") == 1
    # only photos and videos inside DCIM and SPIDCIM: never the top folder, an update file or SENSOR.TXT
    listed = _bodies(java, "private List<Media> listMedia(")[0]
    assert 'r.name.equalsIgnoreCase("DCIM") || spidcim && r.name.equalsIgnoreCase("SPIDCIM")' in listed
    assert "DocumentsContract.Document.MIME_TYPE_DIR.equals(r.mime)" in listed
    collect = _bodies(java, "private void collect(")[0]
    assert 'lower.startsWith("._") || !(video || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))' in collect
    # a drive that does not answer is an error, never a card without photos
    children = _bodies(java, "private List<Row> children(")[0]
    assert re.search(r"if \(q == null\) \{\s*throw ", children)
    # the zip belongs to the process; while it runs nothing else touches the card or the drive
    # (two slots, one per kind, so a later shots zip never erases a backup's result; still only one zip runs)
    assert "private static volatile Archive backupArchive;" in java
    assert "private static volatile Archive shotsArchive;" in java
    assert "private static volatile Archive archive;" not in java
    running = _bodies(java, "private static Archive runningArchive()")[0]
    assert "backupArchive" in running and "shotsArchive" in running and "outcome == null" in running
    assert "return runningArchive() != null;" in _bodies(java, "private static boolean archiving()")[0]
    start = _bodies(java, "private void startArchive(")[0]
    assert 'busy() || archiving() ? "' in start and "WORKER.execute(" in start and "} finally {" in start
    buttons = _bodies(java, "private void updateButtons()")[0]
    zipping = buttons[buttons.index("if (zipping) {"):]
    zipping = zipping[:zipping.index("}")]
    for button in ("writeButton", "cleanButton", "readResultButton", "chooseButton", "ejectButton", "driveChange"):
        assert f"{button}.setEnabled(false);" in zipping, button
    assert "zipButton.setEnabled(idle && !zipping && listed);" in buttons


def test_app_keeps_each_zip_result_in_sight_and_words_failed_deletes_truthfully():
    """Back up photos and Test shots: each kind keeps its own last zip; a drive pulled mid-zip, or the re-list after a
    zip, never hides the result behind the drive step; a delete that threw is never called a refusal."""
    java = ACTIVITY.read_text(encoding="utf-8")
    # each status is filled from its own slot, and a new set or another drive clears only its own kind
    show = _bodies(java, "private void showArchive()")[0]
    assert "showArchive(backupArchive, zipStatus);" in show and "showArchive(shotsArchive, shotsStatus);" in show
    assert "shotsArchive = null;" in _bodies(java, "private void startShots()")[0]
    assert "backupArchive = null;" not in _bodies(java, "private void startShots()")[0]
    start = _bodies(java, "private void startArchive(")[0]
    assert re.search(r"if \(shots\) \{\s*shotsArchive = a;\s*\} else \{\s*backupArchive = a;", start)
    # a zip running, or one whose end has not been on screen with the drive listed, keeps its save step open
    assert "save.ready = () -> unseen(backupArchive);" in _bodies(java, "private void renderBackupPhotos()")[0]
    assert "save.ready = () -> unseen(shotsArchive);" in _bodies(java, "private void renderTestShots()")[0]
    assert "return a != null && !a.seen;" in _bodies(java, "private static boolean unseen(")[0]
    seen = _bodies(java, "private void markSeen(")[0]
    assert "a.outcome != null" in seen and "listed" in seen and "status.isShown()" in seen
    assert java.count("a.seen = true;") == 1 and "a.seen = true;" in seen
    buttons = _bodies(java, "private void updateButtons()")[0]
    assert buttons.index("boolean listed =") < buttons.index("markSeen(backupArchive, zipStatus, listed);") \
        < buttons.index("markSeen(shotsArchive, shotsStatus, listed);") < buttons.index("refreshSteps();")
    # ...while its buttons stay off until the drive is listed again
    assert "zipButton.setEnabled(idle && !zipping && listed);" in buttons
    assert "shotsButton.setEnabled(idle && !zipping && listed && shotsSince != 0);" in buttons
    assert "boolean listed = readable(card) && attached;" in buttons
    # the drive step of these routes stays done while the same, still mounted drive is listed again
    drive = _bodies(java, "private Step readDriveStep(")[0]
    assert "(readable(card) || relisting()) && mounted(volume(treeUuid()))" in drive
    relist = _bodies(java, "private boolean relisting()")[0]
    assert "inspecting && card == null && readable(before)" in relist and "tree.equals(before.tree)" in relist
    assert "relistedFrom = null;" in _bodies(java, "private void dropCard()")[0]
    # the result is revealed once the re-list after the zip is back, not before it moves the steps
    shown = _bodies(java, "private void archiveShown()")[0]
    assert shown.index("revealAfterLook = status;") < shown.index("inspectCard();")
    inspect = _bodies(java, "private void inspectCard(String note)")[0]
    back = _bodies(inspect, "ui.post(() ->")[0]
    assert re.search(r"if \(gone\(\) \|\| request != cardRequest\) \{\s*return;", back)
    assert back.index("updateButtons();") < back.index("reveal(shown);")
    # a refusal and a delete that threw are counted apart, and only a refusal is called one
    wiped = _joined(_bodies(java, "private String wipeVerified(")[0])
    assert "if (CARD_REFUSED.equals(why)) {" in wiped and "refused++;" in wiped and "failed++;" in wiped
    assert wiped.count(": the card refused. ") == 1
    refusal = wiped[wiped.index("if (refused > 0) {"):wiped.index("if (failed > 0) {")]
    assert ": the card refused. " in refusal and "still on the camera." in refusal
    error = wiped[wiped.index("if (failed > 0) {"):wiped.index("if (refused + failed > 0) {")]
    assert "the card refused" not in error and "still on the camera" not in error
    assert "may still be on the camera." in error
    assert 'private static final String CARD_REFUSED = "the card refused";' in java
    assert "deleteDocument(getContentResolver(), doc) ? null : falseAnswer();" in java
    # Android 7 answers false for an error too: there false is never called a refusal (so never "still on the camera")
    false_answer = _bodies(java, "private static String falseAnswer()")[0]
    assert "android.os.Build.VERSION.SDK_INT >= 26 ? CARD_REFUSED : FALSE_ON_7;" in false_answer
    assert "the card refused" not in java[java.index("private static final String FALSE_ON_7"):][:120]
    # a copy that read a different size, or has no size, keeps the backup open with its warnings, never "every file
    # matched", and the folded step still says so
    run = _bodies(java, "private String runArchive(")[0]
    assert "boolean allWhole = shortRead == 0 && shortOriginals == 0;" in run
    assert re.search(r"allWhole \? \"\. Verified: the zip was read back and every file matched\.\"", run)
    assert "backupSaved = allWhole;" in run and "backupSaved = true;" not in java
    assert "read shorter" not in java
    assert 'private static final String DIFFERENT_SIZE = " read a different size than the card lists";' in java
    save = _bodies(java, "private void renderBackupPhotos()")[0]
    assert "copyWarnings(outcome)" in save
    # a refused or waiting zip takes the slot's older outcome away, never a zip still running
    clear = _bodies(java, "private static void clearOutcome(boolean shots)")[0]
    assert re.search(r"if \(a == null \|\| a\.outcome == null\) \{\s*return;", clear)
    assert "backupArchive = null;" in clear and "backupSaved = false;" in clear and "shotsArchive = null;" in clear
    result = _bodies(java, "protected void onActivityResult(")[0]
    assert result.index("pendingZip = uri;") < result.index("clearOutcome(pendingZipShots);")
    # a zip ended mid-way by the process is told about at the next start, and the key goes only once the zip was read
    # back (before any delete from the camera) or ended
    assert "unfinishedZip();" in _bodies(java, "protected void onCreate(")[0]
    assert run.index("verifyZip(dest, written, verified);") < run.index("forgetRunningZip(app);") \
        < run.index("wipeVerified(sourceOf, deletable, verified)")
    assert "forgetRunningZip(app);" in start[start.index("} finally {"):]
    assert "putString(KEY_ZIP_RUNNING, dest.toString())" in start
    assert re.search(r"if \(zipping\) \{\s*getWindow\(\)\.addFlags\(android\.view\.WindowManager\.LayoutParams"
                     r"\.FLAG_KEEP_SCREEN_ON\);\s*\} else \{\s*getWindow\(\)\.clearFlags\(",
                     _bodies(java, "private void updateButtons()")[0])


def test_app_says_synced_and_ejected_only_when_it_is():
    """Every write is fsynced; "Synced" is shown only for a write that succeeded, "Ejected" only once Android reports
    the drive unmounted; eject first asks Android, then opens the drive's own page, never settings as the plan."""
    java = ACTIVITY.read_text(encoding="utf-8")
    fully = _bodies(java, "private void writeFully(")[0]
    assert fully.index("out.write(data);") < fully.index("out.flush();") < fully.index("out.getFD().sync();")
    assert java.count("SYNCED") == 2      # the constant and the one place it is shown
    assert "op.write && op.succeeded && op.outcome.startsWith(WRITTEN)\n" \
           "                ? WRITTEN + \" \" + SYNCED" in java
    assert java.count("progressEjected = true;") == 1
    marked = _bodies(java, "private void markEjected()")[0]
    assert "progressEjected = true;" in marked and "setStatus(ejectStatus, EJECTED);" in marked
    assert java.count("setStatus(ejectStatus, EJECTED)") == 1
    # the constant is used nowhere else (no ternary that shows it from a stale flag)
    assert len(re.findall(r"\bEJECTED\b", java)) == 2, re.findall(r"[^\n]*\bEJECTED\b[^\n]*", java)
    # "Ejected" holds only while the drive has not been mounted again: set once, in markEjected, cleared on a mount
    assert java.count("ejectedThisMount = true;") == 1 and "ejectedThisMount = true;" in marked
    assert re.search(r"ACTION_MEDIA_MOUNTED\.equals\(action\)\) \{[^}]*ejectedThisMount = false;",
                     _bodies(java, "private void volumeEvent(")[0])
    assert "ejectedThisMount = false;" in _bodies(java, "private void refreshDrive()")[0]
    assert "progressEjected ?" not in java
    # Android reports a drive "unmounted" when it is pulled out too, just before "bad removal": that broadcast alone is
    # never an eject. The drive counts as ejected only when, a moment later, it is still attached and still unmounted,
    # with no write, removal or zip running; a pull (BAD_REMOVAL), the drive going (REMOVED) or a mount in between
    # cancels that look (unmountCheck).
    event = _bodies(java, "private void volumeEvent(")[0]
    unmount = event[event.index("if (Intent.ACTION_MEDIA_UNMOUNTED.equals(action)) {"):
                    event.index("} else if (Intent.ACTION_MEDIA_EJECT.equals(action)) {")]
    # the unmount itself never marks an eject: it hands over to the settle look (settleUnmount), which does it later
    assert "markEjected();" not in unmount and "settleUnmount(check, seen, mine, 0);" in unmount
    settle = _bodies(java, "private void settleUnmount(")[0]
    later = _bodies(settle, "ui.postDelayed(() ->")[0]
    assert re.search(r"if \(gone\(\) \|\| check != unmountCheck\) \{\s*return;", later)
    assert later.count("markEjected();") == 1 and "android.os.storage.StorageVolume v = volume(mine);" in later
    # ...and only for a drive seen mounted before that unmount: one on its way in is never an eject
    assert "final boolean seen = driveSeenMounted;" in unmount
    assert re.search(r"if \(seen && !busy\(\) && !archiving\(\) && mine\.equalsIgnoreCase\(treeUuid\(\)\)"
                     r" && unmounted\(v\)\) \{\s*markEjected\(\);", later)
    # while Android still says "ejecting" it looks again (bounded), so the result does not wait for a reopen
    assert re.search(r"if \(ejecting\(v\) && tries < EJECTING_LOOKS\) \{\s*settleUnmount\(check, seen, mine, tries \+ 1\);"
                     r"\s*return;", later)
    # a late "ejecting" broadcast never overwrites an eject already confirmed
    ej = event[event.index("Intent.ACTION_MEDIA_EJECT.equals(action)) {"):
               event.index("} else if (Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action)) {")]
    assert re.search(r"if \(!ejectedThisMount\) \{[^}]*setStatus\(ejectStatus, \"Ejecting\u2026\"\);", ej)
    assert "final int check = ++unmountCheck;" in unmount and "if (!busy() && !zipping) {" in unmount
    for action in ("ACTION_MEDIA_BAD_REMOVAL", "ACTION_MEDIA_REMOVED", "ACTION_MEDIA_MOUNTED"):
        start = event.index(f"Intent.{action}.equals(action)) {{")
        branch = event[start:event.index("} else if", start) if "} else if" in event[start:] else len(event)]
        assert "unmountCheck++;" in branch, action
    # A pull always warns, and takes back an "Ejected" the app said just before it.
    bad = event[event.index("Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action)) {"):
                event.index("} else if (Intent.ACTION_MEDIA_REMOVED.equals(action)) {")]
    assert "The drive was unplugged before it was ejected." in _joined(bad) and "undoEject();" in bad
    assert "setStatus(ejectStatus," in bad and "if (!ejectedThisMount)" not in bad
    # it says "synced first" only after a write that worked on this mount (never for a removal: the app sees no sync
    # of a deletion), with nothing running now, and only on a flashing route, whose run (FlashFlow) makes the claim:
    # the app wrote the file (not found it), on the mount seen now, and removed nothing since
    assert "boolean synced = f != null && f.syncedClaim() && !busy() && !zipping;" in bad
    assert "FlashFlow f = flow();" in bad
    flow = FLOW.read_text(encoding="utf-8")
    claim = _bodies(flow, "boolean syncedClaim()")[0]
    assert "return (stage == Stage.WRITTEN || stage == Stage.ANSWERED) && wroteIt && synced;" in claim
    assert flow.count("synced = wroteIt;") == 1 and "synced = wroteIt;" in _bodies(flow, "void written(")[0]
    assert set(re.findall(r"\bsynced = (\w+);", flow)) == {"wroteIt", "false"}     # a write sets it; nothing else
    for ends in ("void driveLeft()", "void mountChanged()", "void removalWorked(Route on)"):
        assert "synced = false;" in _bodies(flow, ends)[0], ends
    assert "synced" not in _bodies(flow, "String serialize()")[0]       # never kept: a restart cannot tell the mount
    # the only write that may claim it is one that worked: newRun(..., true, ...) once, in catchUp's write branch; the
    # same file found on the card (compareWithCard) starts a run that wrote nothing
    caught = _bodies(java, "private void catchUp()")[0]
    assert re.search(r"if \(op\.write\) \{\s*(//[^\n]*\s*)?newRun\(op\.uuid, op\.sha256, op\.cardName, true, "
                     r"op\.backupSha256\);", caught)
    calls = re.findall(r"(?<!void )\bnewRun\(([^;{]*)\);", java)
    assert sorted(c.split(", ")[3] for c in calls) == ["false", "true"], calls
    assert "newRun(uuid, check.sha256, check.cardName, false, check.referenceSha256);" in \
        _bodies(java, "private void compareWithCard(")[0]
    new_run = _bodies(java, "private void newRun(")[0]
    assert "f.written(sha, cardName, true, now, checkedWith, firmwareLabel(sha));" in new_run
    assert re.search(r"if \(x == r && wrote\) \{\s*f\.written\(", new_run)
    assert "f.foundOnCard(sha, cardName, now, checkedWith, firmwareLabel(sha));" in new_run
    # the claim is read before the run takes in the pull (which ends the mount); on a flashing route a pull says what
    # to do next by the run's stage (FlashFlow.afterUnseenEject), and the status is placed only once the steps took the
    # pull in, so it never lands in a step the pull folds
    assert "String next = (f != null ? f.afterUnseenEject(live())" in bad
    assert bad.index("boolean synced =") < bad.index("noteDriveGone(mine);") < bad.index("updateButtons();") \
        < bad.index("afterUnseenEject(") < bad.index("placeEjectStatus();")
    assert re.search(r"refreshSteps\(\);\s*(//[^\n]*\s*)?placeEjectStatus\(\);$", buttons_end(java))
    assert marked.index("setStatus(ejectStatus, EJECTED);") < marked.index("updateButtons();")
    assert "placeEjectStatus();" not in marked
    assert re.search(r"setStatus\(ejectStatus, synced\s*\?", bad)
    assert _joined(bad).count("What was written was synced first") == 1
    undo = _bodies(java, "private void undoEject()")[0]
    assert "ejectedThisMount = false;" in undo and "progressEjected = progressEjectedBefore;" in undo
    check = _bodies(java, "private void checkEject()")[0]
    assert re.search(r"\} else if \(unmounted\(v\)\) \{\s*markEjected\(\);", check)
    mounted = _bodies(java, "private static boolean mounted(")[0]
    assert "android.os.Environment.MEDIA_MOUNTED.equals(v.getState())" in mounted
    # only Android's "unmounted" is an eject: checking, ejecting or unmountable are not
    unmounted = _bodies(java, "private static boolean unmounted(")[0]
    assert "android.os.Environment.MEDIA_UNMOUNTED.equals(v.getState())" in unmounted
    # "removed" (not "bad removal") arrives only for a drive no longer mounted: it counts as an eject only while the
    # unmount's second look still waits for this very unmount (no pull or mount since), of a drive seen mounted first,
    # with no write, removal or zip running; a pull clears that wait.
    removed = event[event.index("} else if (Intent.ACTION_MEDIA_REMOVED.equals(action)) {"):
                    event.index("} else if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {")]
    assert removed.count("markEjected();") == 1
    # an eject: the unmount's look still pending, or Android was ejecting it (and no eject confirmed) when it went
    assert re.search(r"boolean ejectedFirst = unmountPending && pendingUnmount == unmountCheck\s*"
                     r"\|\| ejectingSeen && !ejectedThisMount;", removed)
    assert removed.index("ejectedFirst = ") < removed.index("unmountCheck++;") < removed.index("markEjected();")
    assert re.search(r"if \(ejectedFirst && !busy\(\) && !archiving\(\) && mine\.equalsIgnoreCase\(treeUuid\(\)\)\) "
                     r"\{\s*markEjected\(\);", removed)
    assert "unmountPending = driveSeenMounted;" in unmount and "pendingUnmount = check;" in unmount
    assert "unmountPending = false;" in bad
    assert java.count("markEjected();") == 5      # the two broadcasts, eject() twice and checkEject()
    for caller in ("private void eject()", "private void checkEject()"):
        body = _bodies(java, caller)[0]
        assert body.count("markEjected();") == body.count("unmounted("), caller
    eject = java[java.index("DocumentsContract.ejectRoot(getContentResolver(), root);") - 200:]
    assert eject.index("try {") < eject.index("DocumentsContract.ejectRoot(") < eject.index("catch (RuntimeException")
    assert eject.index("open(page)") < eject.index("open(files)") < eject.index("openStorageSettings()")
    assert 'DOCUMENT_ROOT_SETTINGS = "android.provider.action.DOCUMENT_ROOT_SETTINGS"' in java
    assert 'f.addDataScheme("file");' in java and "unregisterReceiver(volumes);" in java


def test_app_flash_steps_open_after_a_missed_eject():
    """The flashing routes' steps are a FlashFlow run (plain Java, run on the JVM by FlashFlowTest through every
    scenario the review rounds walked), moved on only by explicit events and never by whether the drive is plugged in
    now, kept in the preferences on each event. The eject before the flash is advice, not a gate (the write was
    synced): the flash step is done once "Upgrade 100%" was seen, eject or not; Remove opens after an answer, at once
    after a write that found the file there already, for another update file on the card or another file of the route
    loaded; it is done only once a listing after the removal finds no update file, and stays done until a listing finds
    one again; a lock note names the first open step before it."""
    java = ACTIVITY.read_text(encoding="utf-8")
    flow = FLOW.read_text(encoding="utf-8")
    assert "import android" not in flow
    # no live-state flag decides a flashing step any more
    for gone in ("leftSinceWrite", "backSinceWrite", "leftSinceRemoval", "backSinceRemoval", "removedEjected",
                 "pastFlash", "flashedEarlier", "earlierFlashLock", "progressWritten", "progressWrittenName",
                 "progressWroteIt", "progressRemoved", "progressRemovedFor", "flashAnswer", "flashSeen",
                 "syncedThisMount", "waitsFor", "lockText", "removedHere", "wroteFor", "holdsOther", "noteDriveLeft",
                 "noteDriveMounted", "flashingSteps", "newFlashRun"):
        assert not re.search(rf"\b{gone}\b", java), gone
    # each flashing route's steps are the run's steps, numbered by it, with no done, ready or needs of their own
    for opener, kinds in (("private void renderRepair()", ("INTRO", "WRITE", "CHECK")),
                          ("private void renderProbe()", ("WRITE", "READ")),
                          ("private void renderRestore()", ("BACKUP", "FILE", "WRITE"))):
        body = _bodies(java, opener)[0]
        for kind in kinds:
            assert f"flowStep(FlashFlow.Step.{kind}," in body, (opener, kind)
        for part in ("flashDriveStep();", "flashStep();", "removeStep();"):
            assert part in body, (opener, part)
        for own in (".done = ", ".ready = ", ".needs = "):
            assert own not in body, (opener, own)
    assert "s.kind = FlashFlow.Step.DRIVE;" in _bodies(java, "private Step flashDriveStep()")[0]
    assert "flowStep(FlashFlow.Step.FLASH," in _bodies(java, "private Step flashStep()")[0]
    assert "flowStep(FlashFlow.Step.REMOVE," in _bodies(java, "private Step removeStep()")[0]
    assert "FlashFlow.number(flowRoute(route), kind)" in _bodies(java, "private Step flowStep(")[0]
    for opener in ("private Step flashStep()", "private Step removeStep()", "private Step flashDriveStep()"):
        body = _bodies(java, opener)[0]
        for own in (".done = ", ".ready = ", ".needs = "):
            assert own not in body, (opener, own)
    # refreshSteps takes a flashing step's state and its lock note from the run's view, and names the first open step
    # before a locked step on the other routes too
    steps = _bodies(java, "private void refreshSteps()")[0]
    assert "FlashFlow.View v = f == null ? null : f.view(live());" in steps
    assert "d = v.done(s.kind);" in steps and "lock = v.lockReason(s.kind);" in steps
    assert 'lock = "Do step " + firstOpenBefore(i, n).number + " first.";' in steps
    first = _bodies(java, "private Step firstOpenBefore(")[0]
    assert "!e.isDone && e.lockNote.getVisibility() != View.VISIBLE" in first
    # in the run: the flash step is done once "Upgrade 100%" was seen, eject or not, or once the file is removed; the
    # drive step stays done past the write; Remove is done only from REMOVED on
    done = _bodies(flow, "private boolean isDone(Step s, Live l)")[0]
    assert "return stage == Stage.ANSWERED && Boolean.TRUE.equals(seen) || stage.compareTo(Stage.REMOVING) >= 0;" in done
    assert "ejected" not in done
    assert "return l.driveReady || past();" in done
    assert "return stage.compareTo(Stage.REMOVED) >= 0;" in done
    # another loaded file is written next; after "not seen" and the removal, or a failed check, the file is again
    assert "return past() && !l.loadedOther && !writeAgain();" in done
    again = _bodies(flow, "private boolean writeAgain()")[0]
    assert "return !checkOk;" in again and "stage == Stage.REMOVED && Boolean.FALSE.equals(seen)" in again
    # REMOVED only from a listing that found no update file; a listing that finds one again reopens Remove; a removal
    # alone, the drive leaving or coming back, or an eject moves nothing there
    listing = _bodies(flow, "void listing(String... updateFiles)")[0]
    assert re.search(r"case ANSWERED:\s*case REMOVING:\s*if \(!listedFile\) \{\s*stage = Stage\.REMOVED;", listing)
    assert re.search(r"case REMOVED:\s*case CHECKED:\s*if \(listedFile\) \{\s*stage = Stage\.REMOVING;", listing)
    removal = _bodies(flow, "void removalWorked(Route on)")[0]
    assert "stage = Stage.REMOVING;" in removal and "Stage.REMOVED;" not in removal
    for opener in ("void driveLeft()", "void mountChanged()", "void ejectSeen()"):
        assert "stage" not in _bodies(flow, opener)[0], opener
    # the answer counts whether or not an eject was seen
    answered = _bodies(flow, "void answered(boolean seen)")[0]
    assert re.search(r"if \(stage == Stage\.WRITTEN\) \{\s*stage = Stage\.ANSWERED;", answered)
    assert "eject" not in answered
    # Remove opens after the answer; right after the write only for a reason the run knows; before any write when the
    # card holds an update file (or to keep a removal made there in sight)
    avail = _bodies(flow, "boolean removeAvailable(Live live)")[0]
    assert "return listedFile || removedFirst;" in avail
    assert "return !wroteIt || listedOther || differs || l.loadedOther;" in avail
    assert re.search(r"default:\s*return true;", avail)
    # a lock names the first open step before it
    view = _bodies(flow, "View view(Live live)")[0]
    assert re.search(r'if \(!done\[j\] && !locked\[j\]\) \{\s*note\[i\] = "Do step " \+ \(j \+ 1\) \+ " first\.";',
                     view)
    # a loaded file this route writes, ready to write, that is not the one written (Restore, another file)
    live = _bodies(java, "private FlashFlow.Live live()")[0]
    assert "fitsRoute(fileCheck)" in live and "!fileCheck.sha256.equals(f.sha())" in live
    assert "gateShown(fileCheck, fileData) == null" in live and "l.loadedOther = other && passes;" in live
    # the display's gate is gate() itself, kept only while every input WriteGate reads is the same
    shown = _bodies(java, "private String gateShown(")[0]
    for key in ("m.check != check", "m.data != data", "!Objects.equals(m.backup, backupSha256)",
                "m.loading != loadingBackup", "m.checking != checking",
                "new GateMemo(check, data, backupSha256, loadingBackup, checking, gate(check, data))"):
        assert key in shown, key
    assert "gateShown(" not in _bodies(java, "private void updateButtons()")[0]
    for opener in ("private void confirmWrite()", "private void write(", "private void saveCopy()"):
        assert "gateShown(" not in _bodies(java, opener)[0], opener
    # Restore: a backup changed before the flash starts the run over, so Remove opens for the file on the card
    # (and after "not seen", while the flash step is still open for that file)
    assert re.search(r"route == Route\.RESTORE && \(stage == Stage\.WRITTEN \|\| stage == Stage\.ANSWERED "
                     r"&& !Boolean\.TRUE\.equals\(seen\)\)\s*&& !nz\(nowSha\)\.equals\(backupSha\)",
                     _bodies(flow, "void backupChanged(String nowSha)")[0])
    # a write on another route resets the other runs and counts the card as holding an update file until a listing
    reset = _bodies(flow, "void reset()")[0]
    assert "clearRun();" in reset and "listed = true;" in reset and "listedFile = true;" in reset
    new_run = _bodies(java, "private void newRun(")[0]
    assert re.search(r"\} else \{\s*f\.reset\(\);\s*\}", new_run)
    load = _bodies(java, "private void loadBackup(")[0]
    assert "restore.backupChanged(h);" in load[load.index("if (!Objects.equals(before, h)) {"):]
    # what to do next after an unseen eject or a pull comes from the run, naming only open steps (FlashFlowTest walks
    # every stage), and says nothing of a next step on Restore, whose last step Remove is
    unseen = _bodies(flow, "String afterUnseenEject(Live live)")[0]
    assert "if (route == Route.RESTORE) {" in unseen and "v.open(next)" in unseen
    assert "v.open(Step.FLASH)" in unseen and "v.open(Step.REMOVE) ? RECONNECT_TO_REMOVE" in unseen
    assert java.count("afterUnseenEject(live())") == 2      # the pull (BAD_REMOVAL) and checkEject
    # the drive line while unplugged: flash as the open flash step says, else the safe order while an update file may
    # be on the card (never "switch on and connect" with the card in); from the run, or the drive's runs off a route
    hint = _bodies(flow, "String unpluggedHint(Live live, boolean ejectedIn)")[0]
    assert re.search(r"stage == Stage\.WRITTEN && view\(live\)\.open\(Step\.FLASH\)", hint)
    assert '"Unplugged: flash the camera as step " + number(Step.FLASH) + " says, then answer the question."' in \
        _joined(hint)
    assert "return mayHoldUpdateFile ? SAFE_ORDER : PLAIN_ORDER;" in flow
    assert "return unpluggedHint(mayHoldUpdateFile(), ejectedIn);" in hint
    line = _bodies(java, "private String driveLine()")[0]
    assert re.search(r"return f != null \? f\.unpluggedHint\(live\(\), ejectedIn\)\s*"
                     r": FlashFlow\.unpluggedHint\(mayHoldUpdateFile\(uuid\), ejectedIn\);", line)
    assert "boolean ejectedIn = unmounted(v) || ejecting(v);" in line      # the cable still in: unplug first
    assert "showDriveLine();" in _bodies(java, "private void refreshDrive()")[0]
    assert re.search(r"showDriveLine\(\);[^\n]*\s+boolean listed = ", _bodies(java, "private void updateButtons()")[0])
    assert "driveHint.length() > 0 ? driveHint.getText().toString() : s.resultLine()" in \
        _bodies(java, "private Step flashDriveStep()")[0]
    assert "Switch the camera on with its power key" not in _joined(java)
    # the answer line points at Remove or at the flash step's own More info only while that step is open
    answer = _bodies(flow, "String answerLine(Live live)")[0]
    assert '(v.open(Step.REMOVE) ? " Next: remove the update file." : "")' in answer
    assert '(v.open(Step.FLASH) ? " Open More info below for what to do." : "")' in answer
    assert "See More info above" not in _joined(java) + _joined(flow)
    assert 'setStatus(flashAnswerView, f == null ? "" : f.answerLine(live()));' in \
        _bodies(java, "private void updateButtons()")[0]
    # a write (or the same file found on the card) starts a new run, whatever the run before had reached
    written = _bodies(flow, "void written(String sha, String cardName, boolean wroteIt, long time, String backupSha,")[0]
    assert written.index("clearRun();") < written.index("stage = Stage.WRITTEN;")
    clear = _bodies(flow, "private void clearRun()")[0]
    for field in ("seen = null;", "removedFirst = false;", "differs = false;", "checkOk = false;", 'checkText = "";',
                  "sensor = null;", "synced = false;"):
        assert field in clear, field
    # the run is kept on every event and read back after the process ends, one per drive and route
    run_of = _bodies(java, "private FlashFlow runOf(")[0]
    assert "FlashFlow.parse(getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, null), r)" in run_of
    # a run on screen is never replaced while it is read: a stale finished run starts over only on entering its route
    # (go(), or the first render in onCreate), and "Flash again" only for a finished run (FlashFlowTest covers both)
    assert "staleRun" not in run_of and run_of.count("new FlashFlow(r)") == 1 and "remove(key)" not in run_of
    assert "if (uuid == null) {\n            return new FlashFlow(r);" in run_of.replace("\r\n", "\n")
    fresh = _bodies(java, "private void freshRunIfStale(FlashFlow.Route r)")[0]
    assert "FlashFlow.staleRun(run, want)" in fresh
    # the same version made from the other copy of the dump (original or cleaned) is the same firmware: never stale
    assert "wantKind.matches(run.sha())" in fresh and "want = run.sha();" in fresh
    assert "freshRunIfStale(flowRoute(to));" in _bodies(java, "private void go(Route to)")[0]
    create = _bodies(java, "protected void onCreate(")[0]
    assert create.index("freshRunIfStale(flowRoute(route));") < create.index("render();")
    # the definition, go(), onCreate, and the four places the Repair version can change: the owner's pick in its menu
    # (chooseRepair), a factory dump picked on the Repair route or kept from another route (both in loadFile) and a
    # Repair-version file loaded for that route (followLoadedRepair); each only after repairSel is set
    assert java.count("freshRunIfStale(") == 7
    for opener in ("private void chooseRepair(", "private void loadFile(", "private void followLoadedRepair("):
        body = _bodies(java, opener)[0]
        assert body.index("repairSel = ") < body.index("freshRunIfStale(FlashFlow.Route.REPAIR);"), opener
    assert "return r == FlashFlow.Route.REPAIR ? repairSel.sha256()" in _bodies(java, "private String wantedSha(")[0]
    again = _bodies(java, "private void startNewRun()")[0]
    assert "!FlashFlow.finished(runOf(uuid, r))" in again
    assert "private static boolean finished(" not in java and "private static boolean staleRun(" not in java
    stale = _bodies(flow, "static boolean staleRun(FlashFlow f, String wantSha)")[0]
    assert "wantSha != null && !f.sha.isEmpty() && !wantSha.equals(f.sha) && finished(f)" in stale
    assert "f.stage == Stage.REMOVED || f.stage == Stage.CHECKED" in _bodies(flow, "static boolean finished(")[0]
    # the "Flash again" link is shown and worded from the run as it stands each time the buttons are refreshed
    shown = _bodies(java, "private void showFlashAgain(FlashFlow f)")[0]
    assert "boolean again = f != null && FlashFlow.finished(f);" in shown
    assert '(f.view(live()).current() < 0 ? "Done. " : "") + "Flash again: start a new run"' in shown
    assert "showFlashAgain(f);" in _bodies(java, "private void updateButtons()")[0]
    assert "Done. Flash again" not in java and "attach(col, flashAgainLink);" in _bodies(java, "private void header(")[0]
    # before the answer a listing without the written file opens Write again, and the write step says so (and takes
    # that line back once it no longer holds)
    assert '"The card no longer lists " + cardName + ". Write it again in step " + number(Step.WRITE)' in flow
    assert 'private static final String GONE_LINE = "The card no longer lists ";' in java
    assert "String gone = f == null || busy() ? \"\" : f.goneLine();" in _bodies(java, "private void updateButtons()")[0]
    assert "f.serialize()" in _bodies(java, "private void keep(")[0]
    assert 'KEY_FLASH_RUN + uuid.toUpperCase(Locale.ROOT) + "." + r.name()' in \
        _bodies(java, "private static String runKey(")[0]
    for call in ("f.written(", "f.foundOnCard(", "f.answered(seen);", "f.listing(names);", "f.removalWorked(",
                 "f.differentFileOnCard();", "f.ejectSeen();", "f.checked(", "now.checked(", "f.sensorRead("):
        assert call in java, call
    # the probe's SENSOR.TXT at the write is the run's, so an old file after a restart is never this probe's result
    read = _bodies(java, "private void readSensorResult()")[0]
    assert "final String before = onRecord ? run.sensorAtWrite() : null;" in read
    assert "The app can't tell whether the probe wrote this file since the last flash" in _joined(read)
    # ...and FlashFlowTest is run by build.sh (test_build_runs_every_jvm_test_and_compiles_the_gate)
    assert (REPO / "android" / "test" / "FlashFlowTest.java").exists()
    # every route is named in user text as its header names it
    titles = _bodies(java, "private static String routeTitle(")[0]
    for name in ("Flash custom firmware", "Identify the image sensor", "Restore stock firmware"):
        assert f'return "{name}";' in titles and java.count(f'"{name}"') == 1, name


def test_app_says_synced_only_where_it_wrote_and_never_claims_an_eject_it_cannot_see():
    """Back from the eject page with the drive mounted: "Written and synced" only for a write this flashing route's run
    made on this mount, never for a file found on the card or on another route; otherwise the app says the drive is
    connected and claims no eject either way (it may have been ejected and connected again while the app was away)."""
    java = ACTIVITY.read_text(encoding="utf-8")
    check = _bodies(java, "private void checkEject()")[0]
    mounted = check[check.rindex("} else {"):]
    assert "setStatus(ejectStatus, f != null && f.syncedClaim()" in mounted
    assert "Not ejected yet" not in _joined(java)
    assert "The drive is connected. If you haven't ejected it yet, tap " in _joined(mounted)
    gone = check[:check.index("} else if (unmounted(v)) {")]
    # off the flashing routes, how to connect it again comes from the drive's runs: the safe order while the card may
    # hold an update file, as the drive line says (never a fixed "connect it again")
    assert "f != null ? f.afterUnseenEject(live()) : FlashFlow.unpluggedHint(mayHoldUpdateFile(uuid), false)" in gone
    assert gone.index("noteDriveGone(uuid);") < gone.index("updateButtons();") < gone.index("afterUnseenEject(")
    event = _bodies(java, "private void volumeEvent(")[0]
    pulled = event[event.index("Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action)) {"):
                   event.index("} else if (Intent.ACTION_MEDIA_REMOVED.equals(action)) {")]
    assert re.search(r"f != null \? f\.afterUnseenEject\(live\(\)\)\s*"
                     r": FlashFlow\.unpluggedHint\(mayHoldUpdateFile\(mine\), false\)", pulled)
    assert "Connect it again, and eject it" not in _joined(java) and "FlashFlow.CONNECT_LATER" not in java
    # the eject page asked for survives the screen being made again, so onResume still confirms the eject
    assert "out.putBoolean(STATE_EJECT_ASKED, ejectAsked);" in _bodies(java, "protected void onSaveInstanceState(")[0]
    assert "ejectAsked = state.getBoolean(STATE_EJECT_ASKED, false);" in _bodies(java, "protected void onCreate(")[0]


def test_app_test_shots_keep_the_light_and_blame_no_clock_in_more_info():
    """A light picked for a set is kept beside the set's start, so a restored screen keeps the lighting step done; the
    More info line for "no new photos" does not blame the camera's clock (the set's photos are picked by listing)."""
    java = ACTIVITY.read_text(encoding="utf-8")
    choose = _bodies(java, "private void chooseLight()")[0]
    assert "putLong(KEY_LIGHT_FOR, shotsSince)" in choose
    restore = _bodies(java, "private void restoreShots()")[0]
    assert "lightChosen = prefs.getLong(KEY_LIGHT_FOR, 0) == since;" in restore
    assert ".remove(KEY_LIGHT_FOR)" in _bodies(java, "private void startShots()")[0]
    shots = _joined(_bodies(java, "private void renderTestShots()")[0])
    assert '"No new photos found? Tap \\"Choose how many\\" and type how many of the newest photos to save."' in shots
    assert "clock" not in shots


def test_app_keeps_the_stock_dump_only_checked_and_says_so_only_when_kept():
    """The stock dump (and the owner's factory dump) kept on the phone is used only after Candidates checks it again (a
    damaged copy is deleted); each keep writes its own temporary file, synced, then renamed; "keeps a copy" is said
    only when that worked."""
    java = ACTIVITY.read_text(encoding="utf-8")
    kept = _bodies(java, "private byte[] keptDump()")[0]
    assert "return Candidates.stockDump(readAll(in));" in kept
    assert re.search(r"\} catch \(IOException \| IllegalArgumentException e\) \{\s*f\.delete\(\);[^\n]*\s*return null;", kept)
    assert "return keepCopy(KEPT_DUMP, dump);" in _bodies(java, "private boolean keepDump(byte[] dump)")[0]
    assert "return keepCopy(KEPT_FACTORY, dump);" in _bodies(java, "private boolean keepFactoryDump(byte[] dump)")[0]
    factory = _bodies(java, "private byte[] keptFactoryDump()")[0]
    assert "return Candidates.factoryDump(readAll(in));" in factory
    assert re.search(r"\} catch \(IOException \| IllegalArgumentException e\) \{\s*f\.delete\(\);[^\n]*\s*return null;", factory)
    assert 'KEPT_FACTORY = "factory-dump.bin"' in java
    keep = _bodies(java, "private boolean keepCopy(String name, byte[] data)")[0]
    assert "File.createTempFile(name, \".part\", getFilesDir())" in keep and 'name + ".part"' not in keep
    assert keep.index("out.write(data);") < keep.index("out.getFD().sync();") < keep.index("part.renameTo(f)")
    assert keep.count("return false;") == 3 and keep.rstrip("}\r\n ").endswith("return true;")
    assert "private void keepDump(" not in java
    assert "keeping = keepDump(dump);" in java and "final boolean keptNow = keeping;" in java
    assert re.search(r'\+ \(keptNow \? " The app keeps a copy on this phone\." : ""\)', java)
    # once for the stock dump and once for the factory dump's cleaned copy, each said only when it was kept
    assert _joined(java).count("The app keeps a copy on this phone.") == 2
    assert "keeping = keepFactoryDump(dump);" in _bodies(java, "private void downloadFactory(")[0]

def test_release_workflow_runs_one_at_a_time():
    text = WORKFLOW.read_text(encoding="utf-8")
    m = re.search(r"^concurrency:\n((?:[ ]+.*\n)+)", text, re.M)
    assert m, "no top-level concurrency group"
    assert re.search(r"^\s+group: \S+", m.group(1), re.M)
    assert re.search(r"^\s+cancel-in-progress: false$", m.group(1), re.M)


def test_android_readme_links_resolve():
    """tests/test_docs.py covers README.md and docs/; the app's README links into docs/ too."""
    spec = importlib.util.spec_from_file_location("charmera_test_docs", REPO / "tests" / "test_docs.py")
    docs = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(docs)
    problems = []
    for number, line in docs.outside_fences(ANDROID_README.read_text(encoding="utf-8")):
        for m in re.finditer(r"\]\(([^)\s]+)\)", line):
            target = m.group(1)
            if target.startswith(("http://", "https://", "mailto:")):
                continue
            path, _, anchor = target.partition("#")
            dest = (ANDROID_README.parent / path) if path else ANDROID_README
            if not dest.exists():
                problems.append(f"{number}: no file {target}")
            elif anchor and dest.suffix == ".md" and anchor not in docs.anchors(dest):
                problems.append(f"{number}: no heading for {target}")
    assert not problems, "\n".join(problems)


def test_release_certificate_is_pinned_and_the_workflow_checks_it():
    root = SRC.parents[6]
    pin = (root / "android" / "SIGNING_CERT_SHA256").read_text(encoding="utf-8").strip()
    assert re.fullmatch(r"[0-9a-f]{64}", pin)
    wf = (root / ".github" / "workflows" / "android.yml").read_text(encoding="utf-8")
    assert "SIGNING_CERT_SHA256" in wf and "--print-certs" in wf
    publish = wf[wf.index("- name: Publish on Releases"):]
    assert "steps.key.outputs.release == 'true'" in publish.splitlines()[1]


def test_app_candidates_match_the_documented_ones():
    java = (SRC.parent / "Candidates.java").read_text(encoding="utf-8")
    doc = (SRC.parents[6] / "docs" / "03-FLASH-CANDIDATES.md").read_text(encoding="utf-8")
    android = (SRC.parents[6] / "docs" / "06-ANDROID.md").read_text(encoding="utf-8")
    pairs = re.findall(r'"(JH_5408\d{12}_[0-9A-F]{8}\.bin)", "([0-9a-f]{64})"', java)
    # restore, C, A, B, the sensor-ID probe, the OV9732 bring-up v0.24, v0.25 and v0.26, and the files the restore, the
    # probe, v0.24, v0.25 and v0.26 are when made from the cleaned copies (the-dumping-grounds); C, A and B already are.
    assert len(pairs) == 15   # + v0.27 from the original and the cleaned v012
    # The four flash candidates are documented in docs/03; the four reviewed patches (the probe, a diagnostic, and the
    # Repair versions v0.24, v0.25 and v0.26 for OV9732 cameras) and the five files made from the cleaned copies are
    # documented, by exact file name and SHA-256, in the Android guide.
    patches = [(n, h) for n, h in pairs if n not in doc or h not in doc]
    assert len(patches) == 11, "exactly the reviewed patches and the cleaned copies' files should not be docs/03 candidates"
    for name, sha in pairs:
        if (name, sha) in patches:
            continue
        assert name in doc and sha in doc, name
    for pn, ph in patches:
        assert pn in android and ph in android, f"{pn} must be documented in docs/06-ANDROID.md"
    stock = re.search(r' STOCK_DUMP_SHA256 = "([0-9a-f]{64})"', java).group(1)
    assert "charmera_stock_latest.bin" in image.PUBLIC_DUMPS[stock]
    clean = re.search(r'CLEAN_STOCK_SHA256 = "([0-9a-f]{64})"', java).group(1)
    assert "charmera-v006-2025-10-14-clean.bin" in image.PUBLIC_DUMPS[clean]
    commit = re.search(r'V006_COMMIT = "([0-9a-f]{40})"', java).group(1)
    assert java.count("V006_COMMIT") == 3   # defined once; the download and the page point at the same fixed commit


def test_app_write_gate_warns_but_refuses_known_bricks():
    """Warn, don't block: Write and Save a copy need a file that passed its checks, no race in flight, and, when a
    backup is on record, a check made against that one backup (WriteGate); with none on record an unknown file goes
    too. The confirmation, the tap on Write, the saved copy and the worker ask again."""
    java = ACTIVITY.read_text(encoding="utf-8")
    gate = GATE.read_text(encoding="utf-8")
    assert "import android" not in gate
    refusal = _bodies(gate, "static String refusal(")[0]
    for clause in ("if (backupLoading)", "if (checking)", "UpdateCheck.confirms(check, data)",
                   "Candidates.withdrawn(check.sha256) != null",
                   "backupSha256 == null ? check.referenceSha256 != null",
                   "!backupSha256.equals(check.referenceSha256)"):
        assert clause in refusal, clause
    # no refusal just for having no backup on record
    assert "no backup of this camera is on record" not in gate
    assert "if (backupSha256 == null) {" not in refusal
    assert "WriteGate.refusal(check, data, backupSha256, loadingBackup, checking)" in _bodies(java, "private String gate(")[0]
    buttons = _bodies(java, "private void updateButtons()")[0]
    assert "boolean fileOk = gate(fileCheck, fileData) == null;" in buttons
    assert "writeButton.setEnabled(idle && fileOk && cardOk)" in buttons and "saveButton.setEnabled(idle && fileOk)" in buttons
    assert "ejectButton.setEnabled(!busy())" in buttons
    assert "gate(check, data) != null" in _bodies(java, "private void confirmWrite()")[0]
    assert "gate(fileCheck, fileData) != null" in _bodies(java, "private void saveCopy()")[0]
    assert "gate(check, data)" in _bodies(java, "private void write(")[0]
    worker = "WriteGate.refusal(check, data, backupSha256, loadingBackup, false)"
    assert worker in _bodies(java, "private void writeCopy(")[0]
    report = _bodies(java, "private String writeAndReport(")[0]
    assert worker in report and report.index(worker) < report.index("readCard(t)")
    assert "volatile String backupSha256" in java and "volatile boolean loadingBackup" in java
    assert "volatile boolean checking" in java
    load = _bodies(java, "private void loadBackup(")[0]
    assert "UpdateCheck.backupProblem(name, data, Candidates.PUBLIC_DUMP_SHA256S)" in load
    # a new write clears the old removal result, and the other way round; a new drive clears both
    assert 'cleanStatus.setText("")' in _bodies(java, "private void write(")[0]
    assert 'writeStatus.setText("")' in _bodies(java, "private void clean(")[0]
    result = _bodies(java, "protected void onActivityResult(")[0]
    assert 'writeStatus.setText("");\n                cleanStatus.setText("");' in result
    readme = ANDROID_README.read_text(encoding="utf-8")
    assert "Choose this camera's backup" in readme and "Choose this camera's backup" in java


def test_app_warns_loudly_on_an_unknown_file_with_no_backup():
    """An unknown file (not reviewed) checked with no backup goes to the card with none on record; the file step and
    the write confirmation both carry the loud warning."""
    java = ACTIVITY.read_text(encoding="utf-8")
    unknown_file = _joined(java[java.index("UNKNOWN_FILE = "):java.index("DOWNLOAD = ")])
    for fact in ("isn't one of this project's reviewed builds", "with no backup chosen",
                 "can't compare it with this camera's firmware", "Only write it if you know it fits."):
        assert fact in unknown_file, fact
    unknown = _bodies(java, "private boolean unknownNoBackup(")[0]
    assert "!isReviewedPatch(result) && !result.compared && result.referenceSha256 == null" in unknown
    assert "backupSha256 == null" in unknown
    line = _bodies(java, "private SpannableString checkLine(")[0]
    assert "} else if (unknownNoBackup(result)) {" in line
    assert 'state = "\\u26a0 Unknown file."' in line
    assert "next = UNKNOWN_FILE + " in line
    assert "Needs this camera's backup first" not in java
    confirm = _bodies(java, "private void confirmWrite()")[0]
    assert "final boolean unknown = unknownNoBackup(check);" in confirm
    assert '(unknown ? "\\n\\u26a0 Unknown file. " + UNKNOWN_FILE' in confirm


def test_app_never_uses_a_check_made_against_another_backup():
    """app-1, app-2: a check that lands after the backup changed is never shown or used; the file is checked again,
    nothing is written meanwhile, and a write confirmation, a "Done" and a "Saved" made with the old backup go."""
    java = ACTIVITY.read_text(encoding="utf-8")
    apply = _bodies(java, "private void applyCheck(")[0]
    # Finding 7: the reviewed patches (the sensor-ID probe and the OV9732 bring-up) are backup-independent and exempt
    # from the re-check; every other file whose check was made against a different backup is re-checked, and nothing
    # is kept meanwhile. Lock the exact condition so a dropped !isReviewedPatch guard cannot pass.
    assert "if (!Candidates.checkedAlone(result.sha256) && !Objects.equals(result.referenceSha256, backupSha256)) {" in apply
    stale = apply.index("!Objects.equals(result.referenceSha256, backupSha256)")
    assert stale < apply.index("recheckFile();") < apply.index("showChecked(result, origin, chosen);")
    assert "fileCheck = null;" in apply[stale:apply.index("recheckFile();")]
    for opener in ("private void loadFile(", "private void buildRestore("):
        body = _bodies(java, opener)[0]
        assert "applyCheck(" in body and "fileCheck = r;" not in body, opener
    # The probe is checked with no reference on every file-entry path, through one shared rule (Candidates.check), so
    # no backup it was never meant to match can refuse it (finding 7). recheckFile never checks the probe (its guard).
    for opener in ("private void loadFile(", "private void buildRestore("):
        assert "Candidates.check(" in _bodies(java, opener)[0], opener
    cand = (PKG / "Candidates.java").read_text(encoding="utf-8")
    check_rule = _bodies(cand, "public static UpdateCheck.Result check(")[0]
    # Lock the exact polarity: a != -> == typo must fail here (CandidatesTest also proves the rule by execution).
    assert "reference != null && checkedAlone(UpdateCheck.sha256Hex(data, 0, data.length)) ? null : reference" in check_rule
    probe_rule = _bodies(cand, "public static boolean isProbe(")[0]
    assert "Kind.PROBE.matches(" in probe_rule and "sha256Hex(data" in probe_rule
    # Exactly the reviewed files are exempt, by SHA-256: the probe, the Repair versions and every build of the recipe
    # table (the owner decides whether it fits; the app warns when nothing confirms it), nothing else.
    reviewed = _bodies(cand, "public static boolean isReviewedPatch(String sha256)")[0]
    assert "return Kind.PROBE.matches(sha256) || repairVersion(sha256) != null || Recipe.reviewed(sha256) != null;" in reviewed
    recipe = (PKG / "Recipe.java").read_text(encoding="utf-8")
    assert "backupFree" not in recipe and "backupFree" not in cand
    drives = _bodies(recipe, "static boolean drivesOv9732(Selection sel)")[0]
    assert "V012.equals(sel.base) || V006.equals(sel.base) && sel.has(OV9732_PORT)" in drives
    alone = _bodies(cand, "public static boolean checkedAlone(String sha256)")[0]
    assert "Recipe.drivesOv9732(Recipe.selectionOf(sha256))" in alone
    assert "!s.has(Recipe.PROBE_MOD) && !(s.stock() && Recipe.V006.equals(s.base))" in _bodies(
        cand, "public static boolean isRepairFile(String sha256)")[0]
    assert "return new Kind[]{Kind.V027, Kind.V026, Kind.V025, Kind.BRINGUP};" in _bodies(cand, "public static Kind[] repairVersions()")[0]
    # A factory version is softened only for the bytes that hash to it, and only its "unknown build" lines.
    assert 'Arrays.asList("series", "sensor-table")' in cand
    assert re.search(r"if \(k != null && k\.base == Base\.FACTORY\) \{\s*return asFactory\(r, k\);", check_rule)
    # a reviewed build of another base (v012, v008_test) by its exact bytes too; every reviewed build's sensor line that
    # compares it with a backup is a loud warning (wrongSensor), never a refusal
    assert "if (s == null) {" in check_rule
    assert "ref == null ? r : UpdateCheck.check(name, data, UpdateCheck.PUBLIC_BUILD, null);" in check_rule
    assert "return wrongSensor(Recipe.V006.equals(s.base) ? r : asOtherBuild(r, s, alone), s, alone);" in check_rule
    # only a failure the file has with no backup at all is softened; one that compares it with the backup stands
    soft = _bodies(cand, "static UpdateCheck.Result softened(")[0]
    assert "FACTORY_WARN_ONLY.contains(l.name) && failsAlone(alone, l)" in soft
    recheck = _bodies(java, "private void recheckFile()")[0]
    assert "checking = true;" in recheck and "fileCheck = null;" in recheck and "dismissWriteDialog();" in recheck
    # A file checked with no backup (the probe, the OV9732 builds) is never re-checked against one: the checkedAlone
    # guard must RETURN. Slice to the NEXT if-statement so a dropped return inside this guard fails here. Every other
    # file, reviewed SP140A builds included, is re-checked through Candidates.check (its warnings kept).
    assert "Candidates.check(name, data, ref);" in recheck
    guard = recheck.index("if (fileCheck != null && Candidates.checkedAlone(fileCheck.sha256)) {")
    guard_block = recheck[guard:recheck.index("if (", guard + 1)]
    assert "return;" in guard_block
    posted = _bodies(recheck, "ui.post(() ->")[0]
    assert "check != checkRequest" in posted and "checking = false;" in posted and "dismissWriteDialog();" in posted
    load = _bodies(java, "private void loadBackup(")[0]
    changed = load[load.index("if (!Objects.equals(before, h)) {"):]
    assert changed.index("dismissWriteDialog();") < changed.index("outdated(h);") < changed.index("recheckFile();")
    # "Done" and "Saved" go once the backup on record is not the one that file was checked against (by SHA-256, so a
    # recreated screen that reads the same backup again changes nothing).
    outdated = _bodies(java, "private void outdated(")[0]
    assert "writtenWith != null && !writtenWith.equals(now)" in outdated and "startsWith(WRITTEN)" in outdated
    assert "savedWith != null && !savedWith.equals(now)" in outdated and "startsWith(SAVED)" in outdated
    assert "writtenWith = op.backupSha256;" in _bodies(java, "private void catchUp()")[0]
    assert "savedWith = with;" in _bodies(java, "private void writeCopy(")[0]
    for opener in ("private void loadFile(", "private void downloadDump("):
        assert "checking = false;" in _bodies(java, opener)[0], opener
    # the verdict line (checkLine) and the detail (showChecked) both go by the check's own comparison, never by
    # whether a backup is on record now
    show = _bodies(java, "private void showChecked(")[0]
    line = _bodies(java, "private SpannableString checkLine(")[0]
    assert "checkLine(result, chosen)" in show
    assert "backup == null" not in show and show.count("result.compared") == 1
    assert "backup == null" not in line and line.count("result.compared") == 1


def test_app_refuses_the_withdrawn_candidates_and_blames_the_name_only_for_a_name_problem():
    """app-12: a withdrawn C, A or B is shown and never kept for writing, even if its own checks pass; the restore
    file's "only its name failed" message needs every failing line to be about the name (UpdateCheck)."""
    java = ACTIVITY.read_text(encoding="utf-8")
    load = _bodies(java, "private void loadFile(")[0]
    assert "Candidates.Kind withdrawn = withdrawnKind(result.sha256);" in load
    assert load.index("if (withdrawn != null) {") < load.index("} else if (restore && UpdateCheck.nameIsTheOnlyProblem(result)) {")
    assert re.search(r"result = null;[^\n]*\n\s*data = null;", load)
    posted = _bodies(load, "ui.post(() ->")[0]
    refused = posted[posted.index("} else {", posted.index("applyCheck(")):]
    assert "fileData = null;" in refused and "fileCheck = null;" in refused
    name_only = _bodies(SRC.read_text(encoding="utf-8"), "public static boolean nameIsTheOnlyProblem(")[0]
    for name in ('"name"', '"product"', '"checksum"', '"stamp"'):
        assert name in name_only
    assert '"sensor-table"' not in name_only


def test_app_points_to_step_0_only_a_dump_that_step_0_takes():
    """A 2 MiB file picked in step 1 is pointed to step 0 only when step 0 would take it as a backup; any other one
    gets what UpdateCheck.backupProblem finds wrong with it. Candidates.stockDump's refusal names no step."""
    load = _bodies(ACTIVITY.read_text(encoding="utf-8"), "private void loadFile(")[0]
    dump = load[load.index("} else if (data.length == Candidates.DUMP_SIZE) {"): load.index("result = Candidates.check(name, data")]
    assert "String problem = UpdateCheck.backupProblem(name, data, Candidates.PUBLIC_DUMP_SHA256S);" in dump
    accepted, refused = dump.split("} else {", 1)
    assert "if (problem == null) {" in accepted and "q(BACKUP)" in accepted
    assert "q(BACKUP)" not in refused and "sentence(problem)" in refused and "Candidates.stockDump(" not in dump
    stock = _bodies((PKG / "Candidates.java").read_text(encoding="utf-8"), "public static byte[] stockDump(")[0]
    assert "step 0" not in stock and "Choose this camera's backup" not in stock


def test_app_takes_no_read_that_a_second_read_did_not_confirm_as_the_backup():
    """No backup of your own camera, no flash: step 0 refuses a file whose name holds the mark the toolkit's backup and
    restore give to reads that did not agree and to a single read (flash.UNVERIFIED_READ, in any case), whatever its
    bytes, saying such a read is not a backup until two reads agree. Step 1 checks the same name, so it points no such
    dump to step 0. The rule is plain Java, run in UpdateCheckTest."""
    java = SRC.read_text(encoding="utf-8")
    assert f'UNVERIFIED_READ = "{flash.UNVERIFIED_READ}";' in java
    named = _bodies(java, "public static String backupProblem(String name, byte[] data, String[] publicSha256)")[0]
    rule = "if (name != null && name.toLowerCase(Locale.ROOT).contains(UNVERIFIED_READ)) {"
    assert rule in named and named.index(rule) < named.index("return backupProblem(data, publicSha256);")
    assert "such a read is not a backup until two reads agree" in _joined(named)
    activity = ACTIVITY.read_text(encoding="utf-8")
    call = "UpdateCheck.backupProblem(name, data, Candidates.PUBLIC_DUMP_SHA256S)"
    for opener in ("private void loadBackup(", "private void loadFile("):
        body = _bodies(activity, opener)[0]
        assert call in body and body.index("String name = displayName(uri);") < body.index(call), opener
    assert "UpdateCheck.backupProblem(data," not in activity


def test_build_runs_every_jvm_test_and_compiles_the_gate():
    """build.sh compiles every plain-Java class the app has and runs every test in android/test before it builds."""
    build = BUILD.read_text(encoding="utf-8")
    step1 = build[build.index('echo "1/4'): build.index('echo "2/4')]
    android_only = ("MainActivity.java", "ApkProvider.java", "CalibrationActivity.java")   # not JVM-testable
    for path in sorted(PKG.glob("*.java")):
        text = path.read_text(encoding="utf-8")
        if path.name in android_only:
            # only a class that really needs Android may skip the JVM: it must use the platform
            assert "import android." in text, path.name
        else:
            assert "import android" not in text, f"{path.name} uses Android: add it to android_only"
            assert f"src/io/github/daemonddev/charmera/{path.name}" in step1, path.name
    # the plain classes the screens lean on (the flash log, the date repair and the photo header check) are tested there
    for name in ("FlashLog", "FlashFlow", "DateFix", "PhotoId", "ZipCheck", "WriteGate", "CardState", "Candidates",
                 "UpdateCheck"):
        assert (PKG / f"{name}.java").exists() and (REPO / "android" / "test" / f"{name}Test.java").exists(), name
    for path in sorted((REPO / "android" / "test").glob("*.java")):
        assert f"test/{path.name}" in step1, path.name
        package = re.search(r"^package ([\w.]+);", path.read_text(encoding="utf-8"), re.M)
        cls = (package.group(1) + "." if package else "") + path.stem
        assert re.search(rf"^java -cp out/test {re.escape(cls)}$", step1, re.M), cls


def test_release_workflow_runs_the_source_and_doc_checks_before_it_builds():
    """critic wf-1: the checks that hold the backup gate (tests/test_android.py) and the flash docs run in the job that
    publishes, before build.sh, with the public dump in place; the publish conditions stay as they were."""
    wf = WORKFLOW.read_text(encoding="utf-8")
    run = wf.index("tests/test_android.py tests/test_flash_docs.py tests/test_docs.py")
    assert wf.index("actions/setup-python@") < run
    assert wf.index('echo "CHARMERA_STOCK_IMAGE=') < run < wf.index("./build.sh")
    assert "python -m pytest" in wf[wf.rindex("\n", 0, run): run]
    publish = wf[wf.index("- name: Publish on Releases"):]
    assert "if: github.ref == 'refs/heads/main' && steps.key.outputs.release == 'true'" in publish.splitlines()[1]


def test_app_repair_route_offers_each_version_from_a_menu():
    """0.26: the Repair route writes the version picked in its menu (Candidates.repairVersions, newest first), defaulting
    to the latest the app can make; every Repair version belongs to that route; the menu is off while anything runs, and
    a pick drops a loaded file of another version; the factory dump is recognised before any other 2 MiB file."""
    java = ACTIVITY.read_text(encoding="utf-8")
    assert "Kind.BRINGUP" not in java and "bringupFirmwareVersion" not in java and "isBringup" not in java
    assert "Candidates.isRepairFile(sha) ? FlashFlow.Route.REPAIR" in _bodies(java, "private static FlashFlow.Route routeOfFile(")[0]
    assert "return isRepair(check);" in _bodies(java, "private boolean fitsRoute(")[0]
    views = _bodies(java, "private void makeViews()")[0]
    # 0.28: the pick is base and mods (Recipe.Selection), remembered as such, with the old Kind name migrated once
    assert "repairSel = Recipe.remembered(picked.getString(KEY_REPAIR_SET, null), picked.getString(KEY_REPAIR_OLD, null));" in views
    assert "picked.edit().remove(KEY_REPAIR_OLD).putString(KEY_REPAIR_SET, repairSel.serialize())" in views
    assert 'KEY_REPAIR_OLD = "repairVersion"' in java
    version_menu = _bodies(java, "private android.widget.Spinner versionMenu()")[0]
    assert "Recipe.presets()" in version_menu and "rows[presets.length] = CUSTOM_ROW;" in version_menu
    assert 'CUSTOM_ROW = "Custom: pick base and mods"' in java
    assert "chooseRepair(customStart(), true);" in version_menu
    menu = _bodies(java, "private android.widget.Spinner menu(")[0]
    assert "new android.widget.Spinner(this, android.widget.Spinner.MODE_DROPDOWN)" in menu
    assert "setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(cardFill))" in menu
    assert "t.setTextColor(textPrimary);" in _bodies(java, "private View menuRow(")[0]
    assert "repairChoice.setEnabled(idle && !downloading && !checking);" in _bodies(java, "private void updateButtons()")[0]
    choose = _bodies(java, "private void chooseRepair(")[0]
    assert choose.index("if (busy() || downloading || checking || dialog != null) {") < choose.index("repairSel = sel;")
    assert choose.index("repairSel = sel;") < choose.index("rememberRepair();")
    assert "putString(KEY_REPAIR_SET, repairSel.serialize())" in _bodies(java, "private void rememberRepair()")[0]
    assert "(fileCheck == null || !sel.matches(fileCheck.sha256))" in choose and "dropFile();" in choose
    load = _bodies(java, "private void loadFile(")[0]
    assert load.index("Candidates.isFactoryDump(data)") < load.index("Candidates.isStockDump(data)") \
        < load.index("UpdateCheck.backupProblem(name, data, Candidates.PUBLIC_DUMP_SHA256S)")
    assert "factoryKept = keepFactoryDump(factory);" in load
    make = _bodies(java, "private void makePatch(")[0]
    assert "makeFactory(sel, route);" in make and "downloadDump(sel, route);" in make and "downloadV008(sel, route);" in make
    assert "Candidates.FACTORY_DUMP_NAME" in _bodies(java, "private static String needsFactory(")[0]
    cand = (PKG / "Candidates.java").read_text(encoding="utf-8")
    build = _bodies(cand, "public static byte[] build(byte[] dump, Kind kind)")[0]
    assert build.index("factoryDump(dump);") < build.index("transform(dump, kind)") < build.index("kind.shaFrom(dump)")
    # the factory dump's own bytes never ship: the v0.25 table holds only the 172 authored bytes
    table = cand[cand.index("static final byte[][] V025_BYTES"):cand.index("};", cand.index("static final byte[][] V025_BYTES"))]
    assert sum(len(h) // 2 for h in re.findall(r'hex\("([0-9a-f]+)"\)', table)) == 172
    # v0.26: 332 authored bytes in 45 runs, and the vendor template part is a copy from the dump, never bytes here
    table = cand[cand.index("static final byte[][] V026_BYTES"):cand.index("};", cand.index("static final byte[][] V026_BYTES"))]
    assert sum(len(h) // 2 for h in re.findall(r'hex\("([0-9a-f]+)"\)', table)) == 332
    assert len(re.findall(r'hex\("', table)) == 45
    assert "static final int[][] V026_COPIES = {{0x10753A, 0x89712, 0x26E}};" in cand
    assert "V026_LAYER = new Layer(V026_COPIES, V026_OFFSETS, V026_BYTES);" in cand
    assert "return new Layer[]{V025_LAYER, V026_LAYER};" in _bodies(cand, "static Layer[] layersOf(")[0]


def test_app_repair_route_keeps_title_menu_and_file_on_one_version():
    """0.26 review: a Repair-version file loaded for the Repair route becomes the menu's pick (so the step's title, its
    check line and the file written name one version); the way-back line fits the version picked; the menu looks off
    while it is off; a factory dump is kept on every route but made into firmware only where firmware is written; the
    flash check expects v0.26's EXIF header after a v0.26 write."""
    java = ACTIVITY.read_text(encoding="utf-8")
    apply = _bodies(java, "private void applyCheck(")[0]
    assert apply.index("fileCheck = result;") < apply.index("followLoadedRepair(result);") \
        < apply.index("showChecked(result, origin, chosen);")
    follow = _bodies(java, "private void followLoadedRepair(")[0]
    assert "if (fileFor != Route.REPAIR || k == null || k.equals(repairSel)) {" in follow
    assert follow.index("repairSel = k;") < follow.index("rememberRepair();") < follow.index("render();")
    # the way back: an older version in the menu for a factory version, v0.23's file (made here by Custom) for
    # v0.24, the restore file for v008_test and the SP140A v006 picks; no "no backup" line for a pick that needs one
    repair = _bodies(java, "private void renderRepair()")[0]
    assert "wayBack(repairSel)" in repair and "You don't need a backup." in repair
    back = _bodies(java, "private static String wayBack(")[0]
    said = _joined(back)
    assert "an older version, or stock v012" in said and "It's the way back from every v012 version" in said         and "write v0.23's file. The app makes it here too" in said
    v008 = back[back.index("if (Recipe.V008.equals(sel.base)) {"):]
    v008 = v008[:v008.index("}")]
    assert "older version" not in v008 and "stock restore file" in v008
    # the version menu dims while it is off
    assert "repairChoice.setAlpha(repairChoice.isEnabled() ? 1f : 0.45f);" in _bodies(java, "private void updateButtons()")[0]
    # a factory dump: kept on every route, built only on Repair and Advanced
    assert "return forRoute == Route.REPAIR || forRoute == Route.ADVANCED;" in _bodies(java, "private static boolean buildsFactory(")[0]
    load = _bodies(java, "private void loadFile(")[0]
    factory = load[load.index("if (fd != null) {"):]
    assert load.index("factoryKept = keepFactoryDump(factory);") < load.index("if (fd != null) {")
    assert factory.index("factoryDump = fd;") < factory.index("if (!buildsFactory(forRoute)) {") \
        < factory.index("showFile(factoryKeptOnly(fromFactory), null);") < factory.index("buildRestore(fd, request")
    assert "makes firmware" in _bodies(java, "private static String factoryKeptOnly(")[0]
    # the flash check: the header each written firmware's photos should have
    check = _bodies(java, "private void checkPhoto()")[0]
    assert "PhotoId.Kind expected = made != null ? Recipe.expectedPhoto(made) : PhotoId.expectedFor(written);" in check
    assert "verdict = r.kind == expected;" in check
    photo = (PKG / "PhotoId.java").read_text(encoding="utf-8")
    expected = _bodies(photo, "static Kind expectedFor(")[0]
    assert "written == Candidates.Kind.V026" in expected and "return Kind.FACTORY_EXIF;" in expected
    assert "static final int FACTORY_EXIF_HEADER = 0x340;" in photo
    assert 'FACTORY_MAKE = "Kodak"' in photo and 'FACTORY_MODEL = "Charmera"' in photo


def test_app_downloads_the_factory_dumps_cleaned_copy_when_the_phone_has_none():
    """Both ways in: the owner's own read picked with Use a file (kept, used first), or, with none on the phone, the
    factory build v012's public cleaned copy, downloaded at a fixed commit and checked by SHA-256 before use."""
    java = ACTIVITY.read_text(encoding="utf-8")
    make = _bodies(java, "private void makeFactory(")[0]
    assert "downloadFactory(sel, forRoute, request);" in make
    assert make.index("keptFactoryDump()") < make.index("downloadFactory(")
    dl = _bodies(java, "private void downloadFactory(")[0]
    assert "Candidates.factoryDump(fetch(Candidates.CLEAN_FACTORY_DOWNLOAD))" in dl
    assert "Candidates.FACTORY_DUMP_NAME" in dl and "q(USE_FILE)" in dl   # the manual way is named on every failure
    hint = _bodies(java, "private static String needsFactory(")[0]
    assert "Candidates.CLEAN_FACTORY_NAME" in hint and "q(USE_FILE)" in hint


def test_app_identify_route_reads_the_photos_and_writes_nothing():
    """The "Identify this camera's firmware" route needs no flash run, no backup and no file: it lists the card, reads
    only the start of each photo through Identify (plain Java, run on the JVM by IdentifyTest), and never writes to or
    deletes from the card, never touches a FlashFlow run or the flash log, and never drops or loads an update file."""
    java = ACTIVITY.read_text(encoding="utf-8")
    assert "private enum Route { HOME, BACKUP_PHOTOS, REPAIR, PROBE, RESTORE, TEST_SHOTS, FLASH_LOG, ADVANCED, IDENTIFY }" in java
    titles = _bodies(java, "private static String routeTitle(")[0]
    assert 'return "Identify this camera\'s firmware";' in titles and java.count('"Identify this camera\'s firmware"') == 1
    assert "tile(routeTitle(Route.IDENTIFY)," in _bodies(java, "private void renderHome()")[0]
    assert "renderIdentify();" in _bodies(java, "private void render()")[0]
    assert "default:\n                return null;" in _bodies(java, "private static FlashFlow.Route flowRoute(")[0].replace("\r\n", "\n")
    assert "case IDENTIFY" not in _bodies(java, "private static FlashFlow.Route flowRoute(")[0]
    route = [_bodies(java, opener)[0] for opener in
             ("private void renderIdentify()", "private void identifyFirmware()", "private void showIdentify()")]
    for body in route:
        for call in ("delete(", "deleteDocument", "openOutputStream", "createDocument", "renameDocument", "writeFully(",
                     "confirmWrite", "confirmClean", "FlashFlow", "flowStep(", "runOf(", "keep(", "flow()",
                     "appendLog(", "clearLog(", "dropFile(", "loadFile(", "pickFile(", "fileData", "fileCheck",
                     "repairSel", "KEY_REPAIR", ".edit()"):
            assert call not in body, call
    render, run, show = route
    assert 'readDriveStep("1")' in render and "look.add(identifyButton);" in render
    assert "listMedia(t, false)" in run and "readUpTo(in, PhotoId.HEADER_LIMIT + 16)" in run
    assert "Identify.identify(entries, TimeZone.getDefault())" in run and "identifiedBase = r.base;" in run
    # the three guided questions, worded from what is known
    said = _joined(render)
    for fact in ("Setup page 2: are there Volume and LED rows after FORMAT? Then one of our mods (v0.24 or later) is"
                 " installed.",
                 "Does photo or video mode freeze after a flash? Then that firmware is for the other sensor family and"
                 " doesn't drive this camera's sensor",
                 "Setup > Version shows JH5408  20250520 on every known build, ours too, so it tells you nothing."):
        assert fact in said, fact
    assert "identifyButton.setEnabled(idle && !zipping && !identifying && listed);" in \
        _bodies(java, "private void updateButtons()")[0]
    # Identify's two caveats are always given with its answer
    ident = (PKG / "Identify.java").read_text(encoding="utf-8")
    assert "r.caveats.add(CAVEAT_V008);" in ident and "r.caveats.add(CAVEAT_AFTER_FLASH);" in ident
    assert "A photo can't tell v006 from v008_test" in _joined(ident)
    assert "only photos from before the flash say what fits" in _joined(ident)
