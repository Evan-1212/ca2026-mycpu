#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""List the chisel-bootcamp exercises of Modules 2 to 5 that are still blank.

Usage: scripts/bootcamp-notebooks.py <chisel-bootcamp clone>

An exercise of the notebooks is a code cell with `???` in it (Module 5's exercises of
riscv/src are checked by `make check-bootcamp` itself; this covers the notebooks, among them the
exercises 5.1 and 5.9 that live only there). The cells are read as JSON, never run. A `???` in a
comment, or in a Markdown cell (the Solution accordion), is not a blank. The exit status is 1
when a cell is blank, 0 when none is.

The check proves that the cells were edited in the clone, not that the answers are right: the
notebooks' own `simulate` and `expect` cells say that, when you run them.
"""

import glob
import json
import os
import re
import sys

NOTEBOOK = re.compile(r"^[2-5]\.[0-9]+.*\.ipynb$")


def code_without_comments(source):
    lines = []
    for line in source.splitlines():
        line = re.sub(r"//.*$", "", line)
        lines.append(line)
    return "\n".join(lines)


def blanks(path):
    with open(path, encoding="utf-8") as f:
        nb = json.load(f)
    found = []
    for index, cell in enumerate(nb.get("cells", [])):
        if cell.get("cell_type") != "code":
            continue
        source = cell.get("source", "")
        if isinstance(source, list):
            source = "".join(source)
        if "???" in code_without_comments(source):
            found.append(index)
    return found


def main(argv):
    if len(argv) != 2:
        print(__doc__.split("\n\n")[0] + "\n" + __doc__.split("\n\n")[1], file=sys.stderr)
        return 2
    root = argv[1]
    paths = sorted(p for p in glob.glob(os.path.join(root, "*.ipynb")) if NOTEBOOK.match(os.path.basename(p)))
    if not paths:
        print(f"bootcamp-notebooks: no notebook of Modules 2 to 5 in {root}", file=sys.stderr)
        return 3
    total = 0
    for path in paths:
        cells = blanks(path)
        for index in cells:
            print(f"  TODO    {os.path.basename(path)}: cell {index} still contains ???")
        total += len(cells)
    if total:
        print(f"bootcamp-notebooks: {total} blank exercise cell(s) in {root}", file=sys.stderr)
        return 1
    print(f"  OK      chisel-bootcamp notebooks: {len(paths)} notebooks, no blank cell")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
