#!/usr/bin/env python3

# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

"""Make every source file carry the one-line 2026 copyright notice.

Idempotent. Run with no arguments over the tracked files of the repository, or with `--tree DIR` over a
checked-out tree (which is how the history rewrite ran it, on trees that did not yet contain this script).

- Drops any `Copyright <years> New Vector Ltd.` line, whatever the comment style.
- Sets the year of `Copyright (c) <year> Element Creations Ltd.` to 2026.
- Adds the header to an XML or Python file that has none (vector drawables, this repository's own scripts).
"""

import argparse
import os
import re
import subprocess
import sys

YEAR = "2026"
NOTICE = f"Copyright (c) {YEAR} Element Creations Ltd."
SPDX = "SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial."
SEE = "Please see LICENSE files in the repository root for full details."

EXTENSIONS = (".kt", ".kts", ".xml", ".sh", ".py", ".pro", ".yml")
SKIPPED_DIRS = {".git", ".gradle", "build", "sdks", ".idea"}

NEW_VECTOR_LINE = re.compile(r"^[ \t]*[*#~]+[ \t]*Copyright [0-9, -]+ New Vector Ltd\.\r?\n", re.MULTILINE)
ELEMENT_YEAR = re.compile(r"Copyright \(c\) 20\d\d(?:[,-] ?20\d\d)? Element Creations Ltd\.")

XML_HEADER = f"<!--\n  ~ {NOTICE}\n  ~\n  ~ {SPDX}\n  ~ {SEE}\n  -->\n"
HASH_HEADER = f"# {NOTICE}\n#\n# {SPDX}\n# {SEE}\n"


def fix(text: str, extension: str) -> str:
    text = NEW_VECTOR_LINE.sub("", text)
    text = ELEMENT_YEAR.sub(NOTICE, text)
    if "Copyright" in text:
        return text
    if extension == ".xml":
        if text.startswith("<?xml"):
            declaration, rest = text.split("\n", 1)
            return f"{declaration}{XML_HEADER}{rest}"
        return XML_HEADER + text
    if extension == ".py":
        lines = text.split("\n")
        index = 0
        while index < len(lines) and lines[index].startswith("#!") or index < len(lines) and lines[index].startswith("# -*-"):
            index += 1
        prefix = "\n".join(lines[:index])
        rest = "\n".join(lines[index:])
        return (prefix + "\n\n" if prefix else "") + HASH_HEADER + ("\n" if not rest.startswith("\n") else "") + rest
    return text


def candidates(tree: str | None) -> list[str]:
    if tree is None:
        listed = subprocess.run(["git", "ls-files", "-z"], check=True, capture_output=True).stdout.decode()
        return [path for path in listed.split("\0") if path]
    paths = []
    for root, dirs, files in os.walk(tree):
        dirs[:] = [d for d in dirs if d not in SKIPPED_DIRS]
        for name in files:
            paths.append(os.path.relpath(os.path.join(root, name), tree))
    return paths


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tree", help="walk this directory instead of `git ls-files`")
    parser.add_argument("--check", action="store_true", help="report files that would change, exit 1 if any")
    args = parser.parse_args()

    base = args.tree or "."
    changed = []
    for path in candidates(args.tree):
        extension = os.path.splitext(path)[1]
        if extension not in EXTENSIONS:
            continue
        full = os.path.join(base, path)
        with open(full, encoding="utf-8") as handle:
            before = handle.read()
        after = fix(before, extension)
        if after != before:
            changed.append(path)
            if not args.check:
                with open(full, "w", encoding="utf-8") as handle:
                    handle.write(after)
    for path in changed:
        print(("would fix " if args.check else "fixed ") + path)
    return 1 if (args.check and changed) else 0


if __name__ == "__main__":
    sys.exit(main())
