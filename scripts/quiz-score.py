#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Score the quiz routines on every pipeline of this tree: the one number that counts for the cost.

Usage: scripts/quiz-score.py [<dir>] [--models t4-stall,t4-forward,...] [--programs quiz-arith,...]
       make quiz-score        (builds the models first)

<dir> holds the routines: a directory with quiz-<name>/routines.S in it (default software/programs, which
is where you write them). They are built with this tree's harness and run on its Verilator models
(build/<model>/Vsim, one for each variant of T4 and T5: `make -C 4-pipeline verilator VARIANT=Stall`, and
so on, which `make quiz-score` does), and scripts/quiz-grade.py scores them against software/quiz/targets.csv,
model by model. The score is the lowest of them.

Why every pipeline: on the single-cycle core a cycle is an instruction, so the cheapest routine is the
shortest. On a pipeline it is the one that avoids the stalls and the flushes of that pipeline, and the
variants differ in exactly those. Simulation is cycle-exact, so the instructors, who run this same tool in
their tree on your routines, get the numbers that you get on a working T4 and T5.

Prints one line per model (score, and how many routines reached each tier) and the lowest. The exit status
is 0 when every routine is correct on every model.
"""

import argparse
import collections
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ARCH_OF_TASK = {"1": "rv32i", "2": "rv32im", "3": "rv32im_zicsr", "4": "rv32im_zicsr", "5": "rv32im_zicsr"}
DEFAULT_MODELS = "t4-stall,t4-forward,t4-earlybranch,t5-stall,t5-forward,t5-earlybranch"
PROGRAMS = ["quiz-arith", "quiz-sine", "quiz-nn", "quiz-enc"]


def cross():
    return os.environ.get("CROSS") or os.path.join(
        os.environ.get("RISCV", os.path.expanduser("~/riscv/toolchain")), "bin", "riscv-none-elf-")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("routines", nargs="?", default=os.path.join(ROOT, "software", "programs"))
    ap.add_argument("--models", default=DEFAULT_MODELS)
    ap.add_argument("--programs", default=",".join(PROGRAMS))
    ap.add_argument("--build", default=os.path.join(ROOT, "build", "quiz-score"))
    args = ap.parse_args(argv)
    programs = [p for p in args.programs.split(",") if p]

    work = args.build
    shutil.rmtree(work, ignore_errors=True)
    shutil.copytree(os.path.join(ROOT, "software"), os.path.join(work, "software"), ignore=shutil.ignore_patterns("build"))
    for name in ("sim", "5-soc"):
        os.symlink(os.path.join(ROOT, name), os.path.join(work, name))
    for p in programs:
        src = os.path.join(args.routines, p, "routines.S")
        if not os.path.isfile(src):
            sys.exit(f"quiz-score: {src} is missing")
        shutil.copyfile(src, os.path.join(work, "software", "programs", p, "routines.S"))

    scores, ok = [], True
    for model in [m for m in args.models.split(",") if m]:
        vsim = os.path.join(ROOT, "build", model, "Vsim")
        if not os.path.exists(vsim):
            variant = model.split("-", 1)[1].capitalize() if "-" in model else ""
            task = {"4": "4-pipeline", "5": "5-soc"}.get(model[1], "<task>")
            sys.exit(f"quiz-score: {vsim} is not built: make -C {task} verilator VARIANT={variant} (make quiz-score builds them all)")
        arch = ARCH_OF_TASK[model[1]]
        b = subprocess.run(["make", "-C", os.path.join(work, "software"), f"ARCH={arch}", "PLATFORM=mycpu",
                            f"BUILD={work}/build"] + programs, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if b.returncode:
            sys.exit(f"quiz-score: the routines do not build for {arch}:\n{b.stdout[-1500:]}")
        g = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "quiz-grade.py"), "--task", model[1], "--vsim", vsim,
                            "--sw", f"{work}/build/sw/mycpu/{arch}", "--model", model, "--cross", cross(),
                            "--programs", ",".join(programs)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        m = re.search(r"quiz score on task \d: ([0-9.]+) / 100", g.stdout)
        score = float(m.group(1)) if m else 0.0
        tiers = collections.Counter(t for _, t in re.findall(r"^\s+(\S+)\s+(best|base|correct|FAILED)\s", g.stdout, re.M))
        ok &= g.returncode == 0
        scores.append(score)
        print(f"{model:16} {score:6.1f}   " + ", ".join(f"{k} {v}" for k, v in sorted(tiers.items())), flush=True)
    low = min(scores) if scores else 0.0
    print(f"quiz on the reference pipelines: {low:.1f} / 100 (the lowest of {len(scores)} models)")
    return 0 if ok and scores else 1


if __name__ == "__main__":
    sys.exit(main())
