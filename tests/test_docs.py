"""The docs link to each other; keep every relative link and heading anchor valid, and the reading order intact."""
import re
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DOCS = REPO / "docs"


def slug(heading: str) -> str:
    """The anchor GitHub generates for a heading."""
    s = heading.strip().lower()
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


def outside_fences(text: str):
    fence = False
    for number, line in enumerate(text.splitlines(), 1):
        if line.startswith("```"):
            fence = not fence
            continue
        if not fence:
            yield number, line


def anchors(path: Path) -> set:
    found = set()
    for _, line in outside_fences(path.read_text(encoding="utf-8")):
        m = re.match(r"^#{1,6} (.+)$", line)
        if m:
            found.add(slug(m.group(1)))
    return found


def markdown_files():
    return [REPO / "README.md", *sorted(DOCS.glob("*.md"))]


def test_every_relative_link_and_anchor_resolves():
    problems = []
    for md in markdown_files():
        for number, line in outside_fences(md.read_text(encoding="utf-8")):
            for m in re.finditer(r"\]\(([^)\s]+)\)", line):
                target = m.group(1)
                if target.startswith(("http://", "https://", "mailto:")):
                    continue
                path, _, anchor = target.partition("#")
                dest = (md.parent / path) if path else md
                if not dest.exists():
                    problems.append(f"{md.name}:{number}: no file {target}")
                elif anchor and dest.suffix == ".md" and anchor not in anchors(dest):
                    problems.append(f"{md.name}:{number}: no heading for {target}")
    assert not problems, "\n".join(problems)


def test_docs_are_numbered_in_reading_order_and_each_links_its_neighbours():
    numbered = sorted(p for p in DOCS.glob("*.md") if re.match(r"^\d\d-", p.name))
    assert [int(p.name[:2]) for p in numbered] == list(range(1, len(numbered) + 1))
    index = (DOCS / "README.md").read_text(encoding="utf-8")
    readme = (REPO / "README.md").read_text(encoding="utf-8")
    for i, p in enumerate(numbered):
        text = p.read_text(encoding="utf-8")
        assert f"({p.name})" in index, f"{p.name} missing from docs/README.md"
        assert f"(docs/{p.name})" in readme, f"{p.name} missing from README.md"
        assert "[all docs](README.md)" in text
        if i > 0:
            assert f"({numbered[i - 1].name})" in text, f"{p.name} does not link back to {numbered[i - 1].name}"
        if i + 1 < len(numbered):
            assert f"({numbered[i + 1].name})" in text, f"{p.name} does not link on to {numbered[i + 1].name}"


def test_the_first_doc_links_to_every_other_doc():
    numbered = sorted(p for p in DOCS.glob("*.md") if re.match(r"^\d\d-", p.name))
    first = numbered[0].read_text(encoding="utf-8")
    missing = [p.name for p in numbered[1:] if f"({p.name}" not in first]
    assert not missing, f"{numbered[0].name} does not link to {missing}"


def test_no_reference_uses_an_old_unnumbered_name():
    stems = [re.sub(r"^\d\d-", "", p.stem) for p in DOCS.glob("[0-9][0-9]-*.md")]
    pattern = re.compile(r"(?<![\w-])(" + "|".join(map(re.escape, stems)) + r")\.md")
    files = markdown_files() + sorted((REPO / "charmera_toolkit").glob("*.py")) + sorted(p for p in (REPO / "scripts").glob("*") if p.is_file())
    hits = []
    for f in files:
        for number, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if pattern.search(line):
                hits.append(f"{f.relative_to(REPO)}:{number}: {line.strip()[:100]}")
    assert not hits, "\n".join(hits)


IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".tif", ".tiff", ".heic"}
# Byte patterns of an embedded metadata block, refused anywhere in the file whatever its format.
METADATA_MARKS = (b"Exif\x00\x00", b"http://ns.adobe.com/xap/1.0/", b"<x:xmpmeta", b"<?xpacket")


def jpeg_metadata(data: bytes):
    """The APP1 Exif/XMP segments of a JPEG, walked marker by marker up to the start of the scan."""
    found, pos = [], 2
    while pos + 4 <= len(data):
        if data[pos] != 0xFF:
            found.append(f"no marker at {pos:#x}")
            break
        marker = data[pos + 1]
        if marker == 0xFF:            # fill byte
            pos += 1
            continue
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            pos += 2
            continue
        if marker in (0xD9, 0xDA):    # end of image, start of scan: no header segments follow
            break
        length = int.from_bytes(data[pos + 2:pos + 4], "big")
        body = data[pos + 4:pos + 2 + length]
        if marker == 0xE1 and (body.startswith(b"Exif\x00") or body.startswith(b"http://ns.adobe.com/xap/")):
            found.append(f"APP1 {body[:4]!r} at {pos:#x}")
        pos += 2 + length
    return found


def png_metadata(data: bytes):
    """The eXIf, iTXt, tEXt and zTXt chunks of a PNG (XMP travels in iTXt)."""
    found, pos = [], 8
    while pos + 8 <= len(data):
        length = int.from_bytes(data[pos:pos + 4], "big")
        kind = data[pos + 4:pos + 8]
        if kind in (b"eXIf", b"iTXt", b"tEXt", b"zTXt"):
            found.append(f"{kind.decode()} chunk at {pos:#x}")
        if kind == b"IEND":
            break
        pos += 12 + length
    return found


def test_no_image_under_docs_carries_exif_xmp_or_gps():
    """docs/CAMERA-UI.md says its photos carry no metadata: no image under docs/ may hold an Exif (and so GPS) or XMP
    block, in a JPEG's APP1 segments, in a PNG's eXIf/iTXt/tEXt/zTXt chunks, or anywhere in its bytes."""
    images = sorted(p for p in DOCS.rglob("*") if p.is_file() and p.suffix.lower() in IMAGE_SUFFIXES)
    assert images, "no images found under docs/"
    problems = []
    for p in images:
        data = p.read_bytes()
        name = p.relative_to(REPO).as_posix()
        if data.startswith(b"\xff\xd8"):
            problems += [f"{name}: {f}" for f in jpeg_metadata(data)]
        elif data.startswith(b"\x89PNG\r\n\x1a\n"):
            problems += [f"{name}: {f}" for f in png_metadata(data)]
        problems += [f"{name}: holds {mark!r}" for mark in METADATA_MARKS if mark in data]
    assert not problems, "\n".join(problems)


def test_metadata_checks_catch_exif_xmp_and_png_text():
    """The checks above find what they look for, so finding nothing in docs/ means something."""
    exif = b"\xff\xd8" + b"\xff\xe1" + (2 + 10).to_bytes(2, "big") + b"Exif\x00\x00GPS!" + b"\xff\xda\x00\x02"
    xmp_body = b"http://ns.adobe.com/xap/1.0/\x00<x:xmpmeta/>"
    xmp = b"\xff\xd8" + b"\xff\xe1" + (2 + len(xmp_body)).to_bytes(2, "big") + xmp_body + b"\xff\xda\x00\x02"
    clean = b"\xff\xd8" + b"\xff\xe0" + (2 + 5).to_bytes(2, "big") + b"JFIF\x00" + b"\xff\xda\x00\x02"
    assert jpeg_metadata(exif) and jpeg_metadata(xmp) and not jpeg_metadata(clean)
    assert not any(mark in clean for mark in METADATA_MARKS)

    def chunk(kind, body=b""):
        return len(body).to_bytes(4, "big") + kind + body + b"\x00" * 4

    head = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", b"\x00" * 13)
    for kind in (b"eXIf", b"iTXt", b"tEXt", b"zTXt"):
        assert png_metadata(head + chunk(kind, b"x") + chunk(b"IEND")), kind
    assert not png_metadata(head + chunk(b"IDAT", b"x") + chunk(b"IEND"))


def test_v024_patch_table_in_docs_15_matches_the_apps_rows_offsets():
    """docs/15 section 10 lists v0.24's patch sites by VA; they must be Candidates.ROWS_OFFSETS minus 0x200 (file
    offset = VA + 0x200), each once, no more and no fewer. A range "a..b" names the site at a."""
    java = (REPO / "android" / "src" / "io" / "github" / "daemonddev" / "charmera" / "Candidates.java").read_text(
        encoding="utf-8")
    m = re.search(r"static final int\[\] ROWS_OFFSETS = \{([^}]*)\};", java)
    assert m, "ROWS_OFFSETS not found in Candidates.java"
    offsets = [int(x, 16) for x in re.findall(r"0x[0-9A-Fa-f]+", m.group(1))]
    assert len(offsets) == 15
    doc = (DOCS / "15-OV9732-AE-WB-FLASH-PORT.md").read_text(encoding="utf-8")
    start = doc.index("## 10. ")
    section = doc[start:doc.index("\n## ", start + 1)]
    rows = [line for line in section.splitlines() if line.startswith("| 0x")]
    assert rows, "no VA table in docs/15 section 10"
    listed = []
    for row in rows:
        va_column = re.sub(r"(0x[0-9A-Fa-f]+)\.\.0x[0-9A-Fa-f]+", r"\1", row.split("|")[1])
        listed += [int(x, 16) for x in re.findall(r"0x[0-9A-Fa-f]+", va_column)]
    assert sorted(listed) == sorted(o - 0x200 for o in offsets), (
        [hex(v) for v in sorted(listed)], [hex(o - 0x200) for o in sorted(offsets)])


def test_the_older_build_names_table_matches_the_recipes():
    """docs/06-ANDROID.md's "Older build names" table is where a reader with an old file maps v0.23..v0.27 to today's
    names. Each row is a set's old_name and its label, as recipes.json has them."""
    from charmera_toolkit import recipes as R
    text = (DOCS / "06-ANDROID.md").read_text(encoding="utf-8")
    named = [s for s in R.load()["sets"] if "old_name" in s]
    assert len(named) == 5
    for s in named:
        assert f"| {s['old_name']} | {s['label']} |" in text, s["old_name"]
