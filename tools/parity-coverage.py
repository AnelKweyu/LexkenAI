#!/usr/bin/env python3
"""Diff the parity appendix's request rows against the contract-test tags in the test sources.

    python -I tools/parity-coverage.py <appendix dir> <parity resources dir> <test sources dir> [--all]

Row ids
    A request table (a Markdown table whose header names a `method` and a `path` column) yields one
    id per row: the `#` column when the table has one (`9`, `D1`), else the row's ordinal within the
    area's request tables (`r1`, `r2`, …). Each area file maps to a short key:
        auth-account-billing → auth      ai-chat → chat        ai-tools → tools
        contracts → contracts            dms → dms             legal-db-knowledge → research
        files-crypt-sharing-admin → files pms → pms
Tags
    A test carries `<area>#<id>` anywhere in its name (no brackets: the JVM forbids them in method names), e.g.
        fun `nav-info answers the role and the firm auth#30`()
    One test may carry several tags; a parked row is listed in <parity dir>/parked.txt as `auth#4`.
Exit status
    1 when an area listed in <parity dir>/in-scope.txt has a row with neither a tag nor a parked
    entry; 0 otherwise. `--all` reports every area, not only the in-scope ones.
"""
import re
import sys
from pathlib import Path

AREA_KEYS = {
    "auth-account-billing": "auth",
    "ai-chat": "chat",
    "ai-tools": "tools",
    "contracts": "contracts",
    "dms": "dms",
    "legal-db-knowledge": "research",
    "files-crypt-sharing-admin": "files",
    "pms": "pms",
}

TAG = re.compile(r"\b(auth|chat|tools|contracts|dms|research|files|pms)#([A-Za-z0-9._-]+)\b")


def cells(line: str) -> list[str]:
    """Split a Markdown table row, honouring escaped pipes and pipes inside backticks."""
    out, cur, in_code, i = [], [], False, 0
    while i < len(line):
        ch = line[i]
        if ch == "\\" and i + 1 < len(line) and line[i + 1] == "|":
            cur.append("|")
            i += 2
            continue
        if ch == "`":
            in_code = not in_code
        if ch == "|" and not in_code:
            out.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
        i += 1
    out.append("".join(cur).strip())
    if out and out[0] == "":
        out = out[1:]
    if out and out[-1] == "":
        out = out[:-1]
    return out


def request_rows(text: str) -> list[tuple[str, str]]:
    """(id, short description) for every row of every request table in an area file."""
    rows: list[tuple[str, str]] = []
    lines = text.splitlines()
    i, ordinal = 0, 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("|") and i + 1 < len(lines) and re.match(r"^\|\s*:?-+", lines[i + 1]):
            header = [c.lower() for c in cells(line)]
            is_request_table = any("method" in h for h in header) and any("path" in h or "url" in h for h in header)
            id_col = next((k for k, h in enumerate(header) if h.strip("` ") == "#"), None)
            i += 2
            while i < len(lines) and lines[i].startswith("|"):
                if is_request_table:
                    row = cells(lines[i])
                    ordinal += 1
                    rid = row[id_col].strip("` *") if id_col is not None and id_col < len(row) and row[id_col].strip("` *") else f"r{ordinal}"
                    desc = next((c for k, c in enumerate(row) if k != id_col and c), "")[:70]
                    rows.append((rid, desc))
                i += 1
            continue
        i += 1
    return rows


def main(argv: list[str]) -> int:
    if len(argv) < 4:
        print(__doc__)
        return 2
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # the inventories use arrows and dashes
    appendix, parity_dir, tests_dir = Path(argv[1]), Path(argv[2]), Path(argv[3])
    show_all = "--all" in argv

    def listed(name: str) -> set[str]:
        file = parity_dir / name
        if not file.exists():
            return set()
        return {l.strip() for l in file.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")}

    in_scope = listed("in-scope.txt")
    parked = listed("parked.txt")

    tags: set[str] = set()
    for src in tests_dir.rglob("*.kt"):
        for area, rid in TAG.findall(src.read_text(encoding="utf-8")):
            tags.add(f"{area}#{rid}")

    failed = False
    print("| Area | Request rows | Tagged | Parked | Missing |")
    print("|---|---|---|---|---|")
    for stem, key in AREA_KEYS.items():
        file = appendix / f"{stem}.md"
        if not file.exists():
            continue
        rows = request_rows(file.read_text(encoding="utf-8"))
        ids = [f"{key}#{rid}" for rid, _ in rows]
        tagged = [t for t in ids if t in tags]
        parked_here = [t for t in ids if t in parked and t not in tags]
        missing = [(t, d) for t, (rid, d) in zip(ids, rows) if t not in tags and t not in parked]
        scope = key in in_scope
        marker = " (in scope)" if scope else ""
        print(f"| {key}{marker} | {len(ids)} | {len(tagged)} | {len(parked_here)} | {len(missing)} |")
        if scope and missing:
            failed = True
        if (scope or show_all) and missing:
            for t, d in missing[:200]:
                print(f"    missing {t}: {d}")
    if failed:
        print("\nparity-coverage: an in-scope area has untagged request rows (see above).")
        return 1
    print("\nparity-coverage: every in-scope request row has a test tag.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
