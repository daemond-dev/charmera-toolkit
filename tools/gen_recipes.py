"""Keep charmera_toolkit/recipes.json's builds table and the app's Recipes.java up to date.

  python tools/gen_recipes.py builds DUMP...   build every set of each dump's base from that dump and record the file
                                               name and SHA-256 (rows of dumps not given are kept, so the private
                                               original v012 read need not be present to refresh the public ones)
  python tools/gen_recipes.py java             write android/.../Recipes.java from recipes.json
  python tools/gen_recipes.py java --check     exit 1 if Recipes.java is not what recipes.json makes (tests run this)

Only the builds table and Recipes.java are written; the dumps, mods and sets are edited by hand and reviewed.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from charmera_toolkit import recipes as R  # noqa: E402

JAVA = ROOT / "android" / "src" / "io" / "github" / "daemonddev" / "charmera" / "Recipes.java"


def recipe_digest(doc, mod_ids) -> str:
    """A short hash of the mods a row was built with (and the base's preparation), so a kept row goes stale visibly."""
    mods = {m["id"]: m for m in doc["mods"]}
    body = [[mods[i]["patches"], mods[i]["copies"]] for i in mod_ids]
    return R.sha256(json.dumps(body, sort_keys=True).encode())[:16]


def write(path: Path, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as f:     # Path.write_text(newline=) needs Python 3.10
        f.write(text)


def refresh_builds(paths):
    doc = R.load()
    given = {}
    for p in paths:
        data = Path(p).read_bytes()
        entry = R.dump_for(data, doc)
        given[entry["id"]] = data
    sets_now = {(s["base"], tuple(s["mods"])) for s in doc["sets"]}
    rows = []
    for b in doc["builds"]:                      # rows of dumps not given are kept while their recipe is unchanged
        if b["dump"] in given:
            continue
        if (b["base"], tuple(b["mods"])) not in sets_now:
            print(f"dropped {b['name']} ({b['dump']}): its combination is no longer a set")
        elif b.get("recipe") != recipe_digest(doc, b["mods"]):
            print(f"dropped {b['name']} ({b['dump']}): its mods changed since it was built; give that dump to rebuild it")
        else:
            rows.append(b)
    for s in doc["sets"]:
        for d in doc["dumps"]:
            if d["build"] != s["base"] or d["id"] not in given:
                continue
            data, _ = R.compose(given[d["id"]], s["mods"], doc)
            rows.append({"base": s["base"], "mods": list(s["mods"]), "dump": d["id"],
                         "name": R.name_for(data, s["stamp"]), "sha256": R.sha256(data),
                         "recipe": recipe_digest(doc, s["mods"])})
    order = {d["id"]: i for i, d in enumerate(doc["dumps"])}
    sets = {(s["base"], tuple(s["mods"])): i for i, s in enumerate(doc["sets"])}
    rows.sort(key=lambda b: (sets[(b["base"], tuple(b["mods"]))], order[b["dump"]]))
    doc["builds"] = rows
    write(R.RECIPES, json.dumps(doc, indent=1) + "\n")
    print(f"{len(rows)} builds ({', '.join(sorted(given))} refreshed)")


def q(s):
    return "null" if s is None else json.dumps(s, ensure_ascii=False)


def strs(xs):
    return "{" + ", ".join(q(x) for x in xs) + "}"


def java_source(doc) -> str:
    out = ["package io.github.daemonddev.charmera;", "",
           "/**",
           " * GENERATED from charmera_toolkit/recipes.json by tools/gen_recipes.py: do not edit (a test checks it).",
           " * The firmware recipes as plain data: the dumps a build starts from, each base's mods (patches with the",
           " * SHA-256 of the bytes they replace, and copies taken from the dump at build time), the combinations offered,",
           " * and the reviewed file every combination makes from every dump. File offset = VA + 0x200.",
           " */",
           "final class Recipes {", "",
           "    private Recipes() {", "    }", "",
           f"    static final String CATALOG_VERSION = {q(doc['catalog']['version'])};",
           f"    static final String CATALOG_STAMP = {q(doc['catalog']['stamp'])};", "",
           "    /** {id, build, copy, prep, sha256, url or null}. */",
           "    static final String[][] DUMPS = {"]
    for d in doc["dumps"]:
        out.append(f"            {strs([d['id'], d['build'], d['copy'], d['prep'], d['sha256'], d['url']])},")
    out += ["    };", "",
            "    /** One entry per mod, in the order mods are applied. */",
            "    static final String[] MOD_IDS = " + strs([m["id"] for m in doc["mods"]]) + ";",
            "    static final String[] MOD_BASES = " + strs([m["base"] for m in doc["mods"]]) + ";",
            "    static final String[] MOD_NAMES = " + strs([m["name"] for m in doc["mods"]]) + ";",
            "    static final String[] MOD_TITLES = " + strs([m["title"] for m in doc["mods"]]) + ";",
            "    static final String[] MOD_STATUS = " + strs([m["status"] for m in doc["mods"]]) + ";",
            "    static final String[] MOD_DESCRIPTIONS = {"]
    out += [f"            {q(m['description'])}," for m in doc["mods"]]
    out += ["    };", "    static final String[][] MOD_CONFLICTS = {"]
    out += [f"            {strs(m['conflicts'])}," for m in doc["mods"]]
    out += ["    };", "    static final String[][] MOD_REQUIRES = {"]
    out += [f"            {strs(m['requires'])}," for m in doc["mods"]]
    out += ["    };", "    /** Per mod: {from, to, len} of each copy, and the SHA-256 of the bytes each copy replaces. */",
            "    static final int[][][] MOD_COPIES = {"]
    for m in doc["mods"]:
        out.append("            {" + ", ".join(f"{{0x{c['from']:X}, 0x{c['to']:X}, 0x{c['len']:X}}}" for c in m["copies"]) + "},")
    out += ["    };", "    static final String[][] MOD_COPY_OLD = {"]
    out += [f"            {strs([c['old_sha256'] for c in m['copies']])}," for m in doc["mods"]]
    out += ["    };", "    /** Per mod: each patch's file offset, its new bytes (hex) and the SHA-256 of the bytes it replaces. */",
            "    static final int[][] MOD_PATCH_OFFSETS = {"]
    for m in doc["mods"]:
        out.append("            {" + ", ".join(f"0x{p['file_offset']:X}" for p in m["patches"]) + "},")
    out += ["    };", "    static final String[][] MOD_PATCH_BYTES = {"]
    for m in doc["mods"]:
        out.append("            {")
        out += [f"                    {q(p['new'])}," for p in m["patches"]]
        out.append("            },")
    out += ["    };", "    static final String[][] MOD_PATCH_OLD = {"]
    for m in doc["mods"]:
        out.append("            {")
        out += [f"                    {q(p['old_sha256'])}," for p in m["patches"]]
        out.append("            },")
    out += ["    };", "",
            "    /** {base, mods joined by ',', stamp, status, label}. */",
            "    static final String[][] SETS = {"]
    for s in doc["sets"]:
        out.append(f"            {strs([s['base'], ','.join(s['mods']), s['stamp'], s['status'], s['label']])},")
    out += ["    };", "",
            "    /** {base, mods joined by ',', dump id, file name, sha256}: the reviewed file of each set from each dump. */",
            "    static final String[][] BUILDS = {"]
    for b in doc["builds"]:
        out.append(f"            {strs([b['base'], ','.join(b['mods']), b['dump'], b['name'], b['sha256']])},")
    out += ["    };", "}", ""]
    return "\n".join(out)


def main(argv):
    if len(argv) >= 2 and argv[1] == "builds" and len(argv) > 2:
        refresh_builds(argv[2:])
        return 0
    if len(argv) >= 2 and argv[1] == "java":
        src = java_source(R.load())
        if "--check" in argv:
            ok = JAVA.is_file() and JAVA.read_text(encoding="utf-8") == src
            print("Recipes.java is up to date" if ok else "Recipes.java is out of date: run tools/gen_recipes.py java")
            return 0 if ok else 1
        write(JAVA, src)
        print(f"wrote {JAVA}")
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
