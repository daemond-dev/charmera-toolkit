"""The Android app: its checker carries its own copy of the build identity, which must match
charmera_toolkit/image.py and the stock dump; its card handling and the release workflow keep the safeguards below.

The Java code itself is tested on the JVM by android/build.sh (android/test). There is no emulator in CI, so the
lifecycle rules of MainActivity are checked here in its source.
"""
import hashlib
import importlib.util
import re
from pathlib import Path

from charmera_toolkit import checksum, image

REPO = Path(__file__).resolve().parent.parent
PKG = REPO / "android" / "src" / "io" / "github" / "daemonddev" / "charmera"
SRC = PKG / "UpdateCheck.java"
ACTIVITY = PKG / "MainActivity.java"
WORKFLOW = REPO / ".github" / "workflows" / "android.yml"
ANDROID_README = REPO / "android" / "README.md"


def _code_area_constant(java: str) -> str:
    m = re.search(r'ORIGINAL_2025_CODE_AREA_SHA256 = "([0-9a-f]{64})"', java)
    assert m, "UpdateCheck.java has no ORIGINAL_2025_CODE_AREA_SHA256 constant"
    return m.group(1)


def test_app_build_identity_matches_the_python_one():
    java = SRC.read_text(encoding="utf-8")
    block = java[java.index("ORIGINAL_2025 = new Build("): java.index(");", java.index("ORIGINAL_2025 = new Build("))]
    b = image.ORIGINAL_2025
    for text in (b.stamp, b.build_string.decode(), b.compiled.decode(), b.software_sha256, b.sensor_table_sha256):
        assert f'"{text}"' in block, text
    for number in (b.stamp_offset, b.build_offset, b.compiled_offset, *b.sensor_table):
        assert re.search(rf"0x{number:X}\b", block, re.I), hex(number)
    assert "ORIGINAL_2025_CODE_AREA_SHA256" in block
    assert f"UPDATE_SIZE = 0x{checksum.UPDATE_FILE_SIZE:X}" in java
    assert f"RESOURCE_BASE = 0x{image.RESOURCE_BASE:X}" in java
    assert "CODE_AREA_END = 0x120000" in java
    assert f"RESOURCE_LIMIT = 0x{image.RESOURCE_LIMIT:X}" in java
    assert f'STOCK_STAMP' not in java or checksum.STOCK_TIMESTAMP in java


def test_app_code_area_hash_is_the_stock_dumps(stock_image):
    """The app calls a file's code stock only when bytes 0..0x120000 (header, code, data) hash to this constant."""
    java = SRC.read_text(encoding="utf-8")
    assert _code_area_constant(java) == hashlib.sha256(stock_image[0:0x120000]).hexdigest()


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
    # The worker lives as long as the process and is never shut down, so a late inspectCard cannot hit a dead executor.
    assert re.search(r"private static final ExecutorService WORKER\b", java)
    assert ".shutdown" not in java


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
    first_check = report.index("UpdateCheck.confirms(check, data)")
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
