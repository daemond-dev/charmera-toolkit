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


_SOURCE_TREE = re.compile(rb"\\2247\\5408\\([\x20-\x5b\x5d-\x7e]{1,64})\\")


def _source_tree(data: bytes):
    """The source-tree folder by the app's rule (S15), as (folder, offset where its name first begins, how many source
    paths name it): the folder named most often after \\2247\\5408\\ in the code area, the first found on a tie, or
    (None, None, 0). A name is 1 to 64 bytes from 0x20 to 0x7E other than the backslash, and each path is looked for
    after the one before."""
    found = [(m.group(1).decode("ascii"), m.start(1)) for m in _SOURCE_TREE.finditer(data[:image.CODE_AREA_END])]
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
    # the name's bytes, the offset kept (where the name begins) and the next search (after the path's backslash)
    assert "data[q] >= 0x20 && data[q] <= 0x7E && data[q] != '\\\\'" in find and "MAX_FOLDER = 64;" in java
    assert "int p = at + SOURCE_TREE_PREFIX.length;" in find and "treeAt.put(folder, p);" in find
    assert "next = q + 1;" in find and "at = indexOf(data, SOURCE_TREE_PREFIX, next, end);" in find
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
        assert "what their cameras ran before is not known" in _joined(path.read_text(encoding="utf-8")), path.name


def test_app_uses_no_library_call_newer_than_its_minimum_android():
    """minSdkVersion is 24 (Android 7.0); String.join and the other Java 8 library additions below came with API 26."""
    manifest = (REPO / "android" / "AndroidManifest.xml").read_text(encoding="utf-8")
    assert 'android:minSdkVersion="24"' in manifest
    for path in sorted(PKG.glob("*.java")):
        text = path.read_text(encoding="utf-8")
        for call in ("String.join(", "List.of(", "Map.of(", "Set.of(", ".stream()", "java.time.", "Files.readAllBytes"):
            assert call not in text, f"{path.name}: {call}"


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
    assert "if (busy())" in _bodies(java, "private void pickCard()")[0]


def test_app_survives_unusual_picker_results():
    java = ACTIVITY.read_text(encoding="utf-8")
    for body in _bodies(java, "private void pick"):
        assert "startActivityForResult" in body
        assert "catch (ActivityNotFoundException" in body, body[:200]
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
    for name, sha in re.findall(r'"(JH_5408\d{12}_[0-9A-F]{8}\.bin)", "([0-9a-f]{64})"', java):
        assert name in doc and sha in doc, name
    assert len(re.findall(r'"JH_5408\d{12}_[0-9A-F]{8}\.bin", "[0-9a-f]{64}"', java)) == 4   # restore, C, A, B
    stock = re.search(r'STOCK_DUMP_SHA256 = "([0-9a-f]{64})"', java).group(1)
    assert "charmera_stock_latest.bin" in image.PUBLIC_DUMPS[stock]
    commit = "209c25a47ce91c805b663edb6e18861b367a5a1b"
    assert java.count(commit) == 2      # the download and the page point at the same fixed commit


def test_app_writes_nothing_without_a_backup_of_the_camera():
    """The hard stop: Write and Save a copy need a backup on record and a check made against that one backup (WriteGate);
    the confirmation, the tap on Write, the saved copy and the worker ask again; the app's README says so."""
    java = ACTIVITY.read_text(encoding="utf-8")
    gate = GATE.read_text(encoding="utf-8")
    assert "import android" not in gate
    refusal = _bodies(gate, "static String refusal(")[0]
    for clause in ("if (backupLoading)", "if (backupSha256 == null)", "if (checking)", "UpdateCheck.confirms(check, data)",
                   "!backupSha256.equals(check.referenceSha256)"):
        assert clause in refusal, clause
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
    assert re.search(r"No backup of your own camera, no flash", readme)
    assert "Choose this camera's backup" in readme and "Choose this camera's backup" in java


def test_app_never_uses_a_check_made_against_another_backup():
    """app-1, app-2: a check that lands after the backup changed is never shown or used; the file is checked again,
    nothing is written meanwhile, and a write confirmation, a "Done" and a "Saved" made with the old backup go."""
    java = ACTIVITY.read_text(encoding="utf-8")
    apply = _bodies(java, "private void applyCheck(")[0]
    stale = apply.index("if (!Objects.equals(result.referenceSha256, backupSha256)) {")
    assert stale < apply.index("recheckFile();") < apply.index("showChecked(result, origin, chosen);")
    assert "fileCheck = null;" in apply[stale:apply.index("recheckFile();")]
    for opener in ("private void loadFile(", "private void buildRestore("):
        body = _bodies(java, opener)[0]
        assert "applyCheck(" in body and "fileCheck = r;" not in body, opener
    recheck = _bodies(java, "private void recheckFile()")[0]
    assert "checking = true;" in recheck and "fileCheck = null;" in recheck and "dismissWriteDialog();" in recheck
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
    for opener in ("private void loadFile(", "private void downloadDump()"):
        assert "checking = false;" in _bodies(java, opener)[0], opener
    show = _bodies(java, "private void showChecked(")[0]
    assert "backup == null" not in show and show.count("result.compared") == 2


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
    dump = load[load.index("} else if (data.length == Candidates.DUMP_SIZE) {"): load.index("result = UpdateCheck.check(name, data")]
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
    for path in sorted(PKG.glob("*.java")):
        if path.name != "MainActivity.java":
            assert f"src/io/github/daemonddev/charmera/{path.name}" in step1, path.name
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
