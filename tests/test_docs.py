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
    files = markdown_files() + sorted((REPO / "charmera_toolkit").glob("*.py")) + sorted((REPO / "scripts").glob("*"))
    hits = []
    for f in files:
        for number, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if pattern.search(line):
                hits.append(f"{f.relative_to(REPO)}:{number}: {line.strip()[:100]}")
    assert not hits, "\n".join(hits)
