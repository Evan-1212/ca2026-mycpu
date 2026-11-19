#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Refuse a chisel-bootcamp Module 5 answer that is a copy of the bootcamp's own solution.

Usage: scripts/bootcamp-copies.py [--pin COMMIT] <bootcamp clone>

The tasks compile your answers to Module 5 (riscv/src/main/scala/riscv/*.scala: Alu, Forward,
LoadExtend, ...) into the core. The bootcamp clone also carries solutions of some of them
(riscv/solutions/main/scala/riscv/). The comparison is token by token, of the answer only:

  - a file is cut into Scala tokens, without comments and white space;
  - the answer is what replaces the skeleton's `???`: the tokens that differ from the file at the
    pinned commit (--pin, the bootcamp pin of the Makefile), in the student's file and in the
    solution alike;
  - every name that the answer introduces (a local value, a helper, a parameter) is renamed by
    its order of appearance, so renaming does not hide a copy;
  - an answer of fewer than MIN_TOKENS tokens is not judged: a short answer has one natural form,
    which an honest student writes too.

Equal answers are reported as COPY and the exit status is 1; answers that are nearly equal
(SequenceMatcher ratio of at least NEAR) are reported as NEAR, a warning only. A clone without
solutions has nothing to compare: exit 0. Without --pin, or when the pin is not in the clone, the
whole files are compared the same way.
"""

import difflib
import os
import re
import subprocess
import sys

SOURCES = "riscv/src/main/scala/riscv"
SOLUTIONS = "riscv/solutions/main/scala/riscv"
MIN_TOKENS = 40
NEAR = 0.9

TOKEN = re.compile(r'"(?:\\.|[^"\\])*"|[A-Za-z_][A-Za-z0-9_]*|0x[0-9a-fA-F_]+|\d+|:=|===|=/=|<<|>>|&&|\|\||[^\s\w]')
NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]*$")
SCALA = {"val", "var", "def", "if", "else", "match", "case", "for", "yield", "new", "class", "object", "extends",
         "with", "true", "false", "import", "package", "private", "override", "this", "null", "return", "type"}


def tokens(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return TOKEN.findall(text)


def answer(file_tokens, skeleton_tokens):
    """The tokens of the file that the skeleton does not have, in order."""
    if skeleton_tokens is None:
        return file_tokens
    out = []
    for op, _, _, j1, j2 in difflib.SequenceMatcher(None, skeleton_tokens, file_tokens, autojunk=False).get_opcodes():
        if op != "equal":
            out += file_tokens[j1:j2]
    return out


def canonical(tokens_, known):
    """Names that the skeleton or Scala does not define, renamed by their order of appearance."""
    names, out = {}, []
    for t in tokens_:
        if NAME.match(t) and t not in known and t not in SCALA:
            out.append(names.setdefault(t, f"N{len(names)}"))
        else:
            out.append(t)
    return out


def skeleton(clone, pin, name):
    if not pin:
        return None
    p = subprocess.run(["git", "-C", clone, "show", f"{pin}:{SOURCES}/{name}"], capture_output=True, text=True)
    return tokens(p.stdout) if p.returncode == 0 and p.stdout else None


def compare(clone, pin=None):
    """[(name, verdict, detail)] with verdict COPY, NEAR, OK or SMALL."""
    solutions = os.path.join(clone, SOLUTIONS)
    if not os.path.isdir(solutions):
        return []
    found = []
    for name in sorted(os.listdir(solutions)):
        mine = os.path.join(clone, SOURCES, name)
        if not name.endswith(".scala") or not os.path.isfile(mine):
            continue
        with open(os.path.join(solutions, name), encoding="utf-8") as a, open(mine, encoding="utf-8") as b:
            sol, own = tokens(a.read()), tokens(b.read())
        skel = skeleton(clone, pin, name)
        known = set(skel or [])
        sol_answer, own_answer = canonical(answer(sol, skel), known), canonical(answer(own, skel), known)
        if len(sol_answer) < MIN_TOKENS:
            found.append((name, "SMALL", f"{len(sol_answer)} tokens"))
            continue
        ratio = difflib.SequenceMatcher(None, sol_answer, own_answer, autojunk=False).ratio()
        verdict = "COPY" if own_answer == sol_answer else "NEAR" if ratio >= NEAR else "OK"
        found.append((name, verdict, f"{len(own_answer)} tokens, {ratio:.0%} like the solution"))
    return found


def copies(clone, pin=None):
    return [name for name, verdict, _ in compare(clone, pin) if verdict == "COPY"]


def main(argv):
    pin = None
    if argv[:1] == ["--pin"] and len(argv) >= 2:
        pin, argv = argv[1], argv[2:]
    if len(argv) != 1:
        print(__doc__.split("\n\n")[1], file=sys.stderr)
        return 2
    results = compare(argv[0], pin)
    for name, verdict, detail in results:
        if verdict == "COPY":
            print(f"  COPY    {SOURCES}/{name} is the bootcamp's solution, names aside ({detail})")
        elif verdict == "NEAR":
            print(f"  NEAR    {SOURCES}/{name} is close to the bootcamp's solution ({detail}): is it your own?")
    if any(v == "COPY" for _, v, _ in results):
        print("bootcamp-copies: write these modules yourself; the tasks build on your own answers", file=sys.stderr)
        return 1
    judged = sum(1 for _, v, _ in results if v != "SMALL")
    print(f"  OK      no Module 5 answer is a copy of the bootcamp's solutions ({judged} judged, "
          f"{len(results) - judged} too short to judge)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
