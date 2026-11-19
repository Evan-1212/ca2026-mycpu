#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Refuse a chisel-bootcamp Module 5 answer that is a copy of the bootcamp's own solution.

Usage: scripts/bootcamp-copies.py <bootcamp clone>

The tasks compile your answers to Module 5 (riscv/src/main/scala/riscv/*.scala: DecodeRows, Forward,
PipelineFlush, ...) into the core. The bootcamp clone also carries solutions of some of them
(riscv/solutions/main/scala/riscv/). A file of yours that equals its solution once comments, blank
lines and white space are removed is reported, and the exit status is 1: write the module yourself
(`make check-bootcamp` runs this). A clone without solutions has nothing to compare: exit 0.
"""

import os
import re
import sys

SOURCES = "riscv/src/main/scala/riscv"
SOLUTIONS = "riscv/solutions/main/scala/riscv"


def normalized(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return re.sub(r"\s+", "", text)


def copies(clone):
    solutions = os.path.join(clone, SOLUTIONS)
    if not os.path.isdir(solutions):
        return []
    found = []
    for name in sorted(os.listdir(solutions)):
        mine = os.path.join(clone, SOURCES, name)
        if not name.endswith(".scala") or not os.path.isfile(mine):
            continue
        with open(os.path.join(solutions, name), encoding="utf-8") as a, open(mine, encoding="utf-8") as b:
            if normalized(a.read()) == normalized(b.read()):
                found.append(name)
    return found


def main(argv):
    if len(argv) != 1:
        print(__doc__.split("\n\n")[1], file=sys.stderr)
        return 2
    found = copies(argv[0])
    for name in found:
        print(f"  COPY    {SOURCES}/{name} is the bootcamp's solution ({SOLUTIONS}/{name})")
    if found:
        print("bootcamp-copies: write these modules yourself; the tasks build on your own answers", file=sys.stderr)
        return 1
    print("  OK      no Module 5 answer is a copy of the bootcamp's solutions")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
