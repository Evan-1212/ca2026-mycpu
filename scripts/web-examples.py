#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
"""Regenerates the example bundles of the web observation interface
(web/examples/, OBS-04, OBS-05, OBS-11; web/README.md, "Example bundles").

    web-examples.py [--out DIR] [--make MAKE]

Each example is a small window of one run of the reference model, made by the
task's `make obs` (docs/observation.md): the program runs once over its whole
length to find the window, and once more with +obs-start and +obs-cycles to
pack it. The window is fixed by a rule, not by hand-picked cycle numbers:

  main     from the first cycle whose retire record has the PC of `main`
           (the ELF's symbol table, through the disassembly that the software
           build writes next to each ELF), COUNT cycles
  stall    from LEAD cycles before the first stretch of at least LONG cycles
           without a retire record (a division on T2), COUNT cycles

Next to every bundle it writes listing.json, the disassembly lines of the
ELF the run used (found by the ELF's SHA-256 among the software build's ELF
files) for the PCs that occur in the window, and it writes index.json, the
list of examples. A window that holds no retire record, or a stall rule that
finds no stall, is an error.
"""

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
from obslib import ObsError, read_retire_log, sha256_file  # noqa: E402

# (id, task directory, build directory, program, rule, rule parameters, title)
EXAMPLES = (
    ("t1-add", "1-single-cycle", "t1", "add", "main", {"count": 40},
     "T1, add: register sums, a sum that wraps, a sum written to x0"),
    ("t1-lw", "1-single-cycle", "t1", "lw", "main", {"count": 40},
     "T1, lw: words loaded from a table, a load to x0"),
    ("t1-beq", "1-single-cycle", "t1", "beq", "main", {"count": 40},
     "T1, beq: a loop of branches not taken and taken"),
    ("t2-muldiv", "2-rv32im", "t2", "muldiv", "stall", {"lead": 4, "long": 20, "count": 48},
     "T2, muldiv: a division and the stall around it"),
)
DIS_LINE = re.compile(r"^\s*([0-9a-f]{8}):\s+([0-9a-f]{8})\s+(\S.*?)\s*$")
SYMBOL = re.compile(r"^([0-9a-f]{8}) <([^>]+)>:$")


class Error(Exception):
    """The examples cannot be made; the message says why."""


def run_obs(make, task_dir, program, start=0, cycles=0):
    cmd = [make, "--no-print-directory", "-C", os.path.join(ROOT, task_dir), "obs", f"PROG={program}",
           f"OBS_START={start}", f"OBS_CYCLES={cycles}"]
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
    if p.returncode:
        raise Error(f"{' '.join(cmd)} failed:\n{p.stdout}")


def disassembly(elf):
    """The .dis file that the software build wrote next to the ELF with this content."""
    digest = sha256_file(elf)
    for candidate in sorted(glob.glob(os.path.join(ROOT, "build", "sw", "*", "*", os.path.basename(elf)))):
        dis = candidate[: -len(".elf")] + ".dis"
        if os.path.isfile(dis) and sha256_file(candidate) == digest:
            return dis
    raise Error(f"no disassembly of {elf} (SHA-256 {digest}) under build/sw")


def read_dis(path):
    """({pc: (word, text, function)}, {symbol: pc}) of an objdump -d listing."""
    lines, symbols = {}, {}
    function = None
    with open(path) as f:
        for raw in f:
            m = SYMBOL.match(raw.strip())
            if m:
                function = m.group(2)
                symbols[function] = int(m.group(1), 16)
                continue
            m = DIS_LINE.match(raw)
            if m:
                lines[int(m.group(1), 16)] = (m.group(2), " ".join(m.group(3).split()), function)
    return lines, symbols


def window(rule, params, records, symbols):
    """(start, count) of the example's window in the whole run."""
    if rule == "main":
        if "main" not in symbols:
            raise Error("the ELF has no symbol main")
        pcs = [c for c in sorted(records) if records[c].startswith(f"pc={symbols['main']:08x} ")]
        if not pcs:
            raise Error("no retire record has the PC of main")
        return pcs[0], params["count"]
    cycles = sorted(records)
    for before, after in zip(cycles, cycles[1:]):
        if after - before - 1 >= params["long"]:
            return max(0, before + 1 - params["lead"]), params["count"]
    raise Error(f"no stretch of {params['long']} cycles without a retire record")


def make_example(args, spec):
    ident, task_dir, build, program, rule, params, title = spec
    obs = os.path.join(ROOT, "build", build, "obs", program)
    run_obs(args.make, task_dir, program)
    records, _, _ = read_retire_log(os.path.join(obs, "run.log"), 0, sys.maxsize)  # every record of the run
    lines, symbols = read_dis(disassembly(os.path.join(obs, program + ".elf")))
    start, count = window(rule, params, records, symbols)
    run_obs(args.make, task_dir, program, start, count)

    out = os.path.join(args.out, ident)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    shutil.copytree(os.path.join(obs, "bundle"), os.path.join(out, "bundle"))
    with open(os.path.join(out, "bundle", "manifest.json")) as f:
        manifest = json.load(f)
    pcs = set()
    retired = 0
    for chunk in manifest["chunks"]:
        with open(os.path.join(out, "bundle", chunk["file"])) as f:
            for record in json.load(f)["cycles"]:
                pcs.add(record["core"]["pc"])
                retired += "retire" in record
    if not retired:
        raise Error(f"{ident}: the window {start} +{count} holds no retire record")
    listing = {f"{pc:08x}": {"word": lines[pc][0], "text": lines[pc][1], "function": lines[pc][2]}
               for pc in sorted(pcs) if pc in lines}
    with open(os.path.join(out, "listing.json"), "w") as f:
        json.dump({"elf_sha256": manifest["header"]["elf_sha256"], "lines": listing}, f, indent=1, sort_keys=True)
        f.write("\n")
    return {"id": ident, "task": manifest["header"]["task"], "program": program, "title": title,
            "bundle": f"examples/{ident}/bundle", "listing": f"examples/{ident}/listing.json",
            "first": manifest["run"]["first"], "count": manifest["run"]["count"],
            "bytes": sum(os.path.getsize(p) for p in glob.glob(os.path.join(out, "**"), recursive=True)
                         if os.path.isfile(p))}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", default=os.path.join(ROOT, "web", "examples"), help="where the examples go")
    p.add_argument("--make", default=os.environ.get("MAKE", "make"), help="the make program")
    args = p.parse_args()
    try:
        index = [make_example(args, spec) for spec in EXAMPLES]
    except (Error, ObsError, OSError, ValueError, KeyError) as e:
        print(f"web-examples: {e}", file=sys.stderr)
        return 1
    with open(os.path.join(args.out, "index.json"), "w") as f:
        json.dump({"examples": index}, f, indent=1)
        f.write("\n")
    for e in index:
        print(f"web-examples: {e['id']}: cycles {e['first']} +{e['count']}, {e['bytes']} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
