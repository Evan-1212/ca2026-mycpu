#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Check the object file of the routines that a student wrote for a quiz program.

Usage: scripts/quiz-check.py [--cross <prefix>] <program-dir> <routines.o>

The routines are RV32I assembly and nothing else: the quiz is about
what a core without a multiplier, a divider or a counter can do. This reads the
object file, not the source, so a macro or a `.word` cannot hide an instruction:

  - every instruction is one of the 37 of the base integer set (no M, no A, no F,
    no C, no CSR, no ecall, no ebreak, no fence);
  - no word of an executable section is data (an encoding written as `.word`);
  - no undefined symbol except the data that the header declares `extern` (the array of
    states of quiz-nn, say): a routine calls only the routines of its own file;
  - every routine that the program's header declares is defined and global.

<program-dir> is software/programs/quiz-<name>; the routines it declares are the
`quiz_<name>(` prototypes of its quiz-<name>.h. Prints one line per problem and
exits 1 if there is one.
"""

import argparse
import glob
import os
import re
import subprocess
import sys

RV32I = set(
    """lui auipc jal jalr beq bne blt bge bltu bgeu lb lh lw lbu lhu sb sh sw
    addi slti sltiu xori ori andi slli srli srai add sub sll slt sltu xor srl sra or and""".split()
)


def run(argv):
    p = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if p.returncode:
        raise SystemExit(f"quiz-check: {' '.join(argv)} failed: {p.stderr.strip()}")
    return p.stdout


def declared(program_dir):
    """The routines of the contract header: names followed by `(` at the start of a prototype."""
    names = []
    for header in sorted(glob.glob(os.path.join(program_dir, "quiz-*.h"))):
        with open(header, encoding="utf-8") as f:
            text = re.sub(r"//[^\n]*", "", f.read())
        names += re.findall(r"\b(quiz_[a-z0-9_]+)\s*\(", text)
    return list(dict.fromkeys(names))  # in the order of the header, each once


def provided(program_dir):
    """The data that the harness defines and the header declares: `extern <type> quiz_<name>[...]`."""
    names = []
    for header in sorted(glob.glob(os.path.join(program_dir, "quiz-*.h"))):
        with open(header, encoding="utf-8") as f:
            text = re.sub(r"//[^\n]*", "", f.read())
        names += re.findall(r"^\s*extern\s+[^;(]*?\b(quiz_[a-z0-9_]+)\s*\[", text, re.M)
    return set(names)


def check(cross, program_dir, obj):
    problems = []
    objdump, nm = cross + "objdump", cross + "nm"
    # instructions
    seen = 0
    for line in run([objdump, "-d", "-M", "no-aliases", obj]).splitlines():
        m = re.match(r"\s*([0-9a-f]+):\s+([0-9a-f]+)\s+(\S+)", line)
        if not m:
            continue
        seen += 1
        word, mnemonic = m.group(2), m.group(3)
        if mnemonic not in RV32I:
            what = "data in a code section" if mnemonic.startswith((".", "unknown")) else f"`{mnemonic}`"
            problems.append(f"{os.path.basename(obj)}: {what} at {m.group(1)}: only the base RV32I instructions are allowed")
        elif len(word) != 8:
            problems.append(f"{os.path.basename(obj)}: a compressed instruction at {m.group(1)}")
    if not seen:
        problems.append(f"{os.path.basename(obj)}: no instruction at all")
    # symbols
    defined, undefined = set(), []
    for line in run([nm, obj]).splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0] == "U":
            undefined.append(parts[1])
        elif len(parts) == 3 and parts[1] in "TtDdRrBb":
            if parts[1] in "TDRB":
                defined.add(parts[2])
    allowed = provided(program_dir)
    for name in undefined:
        if name in allowed:
            continue
        problems.append(f"undefined symbol {name}: a routine may call only the routines of its own file")
    for name in declared(program_dir):
        if name not in defined:
            problems.append(f"{name} is not defined as a global symbol")
    return problems


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--cross", default=os.environ.get("CROSS", "riscv-none-elf-"))
    ap.add_argument("program_dir")
    ap.add_argument("object")
    args = ap.parse_args(argv)
    problems = check(args.cross, args.program_dir, args.object)
    for p in problems:
        print(f"quiz-check: {p}")
    if not problems:
        print(f"quiz-check: {os.path.basename(os.path.normpath(args.program_dir))}: RV32I only, "
              f"{len(declared(args.program_dir))} routines defined")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
