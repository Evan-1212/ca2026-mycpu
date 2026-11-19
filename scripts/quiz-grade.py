#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Run the quiz programs on one task's model and score the routines that you wrote.

Usage: scripts/quiz-grade.py --task <n> --vsim <Vsim> --sw <dir with the ELFs> [--model <name>]
                             [--programs a,b] [--targets <csv>] [--out <dir>] [--max-cycles <n>]

For each program (software/programs/quiz-<name>), in this order:

  1. scripts/quiz-check.py reads routines.o: base RV32I only, nothing undefined.
  2. The ELF runs on the model. Its `quiz: <routine> ok` lines say which routines
     are correct; `perf: quiz <program> <routine> calls=<n> insn=<n> cycles=<n>`
     says what a routine costs on this model.
  3. A routine earns, out of its share of the program's points (every program is
     worth the same, every routine of a program the same):
        50 %  it is correct (all its checks pass, and it keeps s0 to s11 and sp)
        25 %  and it costs no more cycles than the routine of the quiz (`base`)
        25 %  and it costs no more than 105 % of the cycles of the reference (`best`)
     `base` and `best` are the cycles of 192 calls on this model, from the quiz's
     own listing and from the reference, in software/quiz/targets.csv (columns
     model,program,routine,base,best). A model that the file does not list is graded
     on the instructions (the rows of the model `insn`), which are the same on every task.

A routine that is not correct earns nothing, whatever it costs. The report shows the
cost of each routine, the cycles per instruction, and the target cycles, so that the
same routine can be compared from task to task: that comparison (the hazards of
T4 and T5 against the single cycle of T1) is the point of the programs.

The exit status is 0 when every routine is correct.
"""

import argparse
import csv
import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROGRAM_DIRS = os.path.join(ROOT, "software", "programs")
TARGETS = os.path.join(ROOT, "software", "quiz", "targets.csv")
BEST_SLACK = 1.05
WEIGHTS = {"correct": 50, "base": 25, "best": 25}

QUIZ_LINE = re.compile(r"^quiz: (\S+) (ok|FAILED)(?: .*)?$")
DONE_LINE = re.compile(r"^quiz: (quiz-\S+) (complete|FAILED)$")
PERF_LINE = re.compile(r"^perf: quiz (\S+) (\S+) calls=(\d+) insn=(\d+) cycles=(\d+)$")


def programs(only):
    found = sorted(os.path.basename(d) for d in glob.glob(os.path.join(PROGRAM_DIRS, "quiz-*")) if os.path.isdir(d))
    if only:
        missing = [p for p in only if p not in found]
        if missing:
            raise SystemExit(f"quiz-grade: no such program: {', '.join(missing)}")
        found = [p for p in found if p in only]
    return found


def load_targets(path):
    table = {}
    if os.path.exists(path):
        with open(path, newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                table[(row["model"], row["program"], row["routine"])] = (int(row["base"]), int(row["best"]))
    return table


def parse(output):
    """(routines: name -> ok, costs: (program, routine) -> (calls, insn, cycles), complete)."""
    ok, cost, complete = {}, {}, False
    for line in output.splitlines():
        m = DONE_LINE.match(line)
        if m:
            complete = m.group(2) == "complete"
            continue
        m = QUIZ_LINE.match(line)
        if m:
            ok[m.group(1)] = m.group(2) == "ok"
            continue
        m = PERF_LINE.match(line)
        if m:
            cost[(m.group(1), m.group(2))] = (int(m.group(3)), int(m.group(4)), int(m.group(5)))
    return ok, cost, complete


def tiers(model, program, routine, insn, cycles, targets):
    """(base reached, best reached, the targets used, their unit)."""
    if (model, program, routine) in targets:
        base, best = targets[(model, program, routine)]
        return cycles <= base, cycles <= best * BEST_SLACK, (base, best), "cycles"
    if ("insn", program, routine) in targets:
        base, best = targets[("insn", program, routine)]
        return insn <= base, insn <= best * BEST_SLACK, (base, best), "insn"
    return False, False, None, "none"


def grade_program(args, program, targets):
    short = program[len("quiz-"):]
    sw = args.sw
    result = {"program": program, "routines": [], "check": None, "ran": False, "exit": None}
    obj = os.path.join(sw, "obj", program, "routines.o")
    elf = os.path.join(sw, program + ".elf")
    check = subprocess.run(
        [sys.executable, os.path.join(ROOT, "scripts", "quiz-check.py"), "--cross", args.cross,
         os.path.join(PROGRAM_DIRS, program), obj],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    result["check"] = check.stdout.strip()
    if check.returncode:
        return result
    if not os.path.exists(elf):
        result["check"] = f"{elf} is missing: run make in software/ first"
        return result
    try:
        run = subprocess.run([args.vsim, "+elf=" + elf, f"+max-cycles={args.max_cycles}"], stdin=subprocess.DEVNULL,
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=args.timeout)
        output, result["exit"] = run.stdout, run.returncode
    except subprocess.TimeoutExpired as e:
        output, result["exit"] = (e.stdout or b"").decode() if isinstance(e.stdout, bytes) else (e.stdout or ""), "timeout"
    result["ran"] = True
    ok, cost, _ = parse(output)
    names = routines_of(program)
    for name in names:
        calls, insn, cycles = cost.get((program, name), (0, 0, 0))
        correct = ok.get(name, False)
        base_ok, best_ok, used, unit = tiers(args.model, program, name, insn, cycles, targets)
        result["routines"].append({
            "routine": name, "correct": correct, "calls": calls, "insn": insn, "cycles": cycles,
            "base": correct and base_ok, "best": correct and best_ok and base_ok, "targets": used, "unit": unit,
        })
    return result


def routines_of(program):
    """The routines of a program, in the order of its checks: its header's prototypes minus the helpers."""
    sys.path.insert(0, os.path.join(ROOT, "scripts"))
    import importlib.util
    spec = importlib.util.spec_from_file_location("quiz_check", os.path.join(ROOT, "scripts", "quiz-check.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return [n[len("quiz_"):] for n in module.declared(os.path.join(PROGRAM_DIRS, program))]


def points(routine, share):
    pct = 0
    if routine["correct"]:
        pct += WEIGHTS["correct"]
        if routine["base"]:
            pct += WEIGHTS["base"]
        if routine["best"]:
            pct += WEIGHTS["best"]
    return share * pct / 100.0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--task", required=True)
    ap.add_argument("--vsim", required=True)
    ap.add_argument("--sw", required=True)
    ap.add_argument("--model", default="")
    ap.add_argument("--programs", default="")
    ap.add_argument("--targets", default=TARGETS)
    ap.add_argument("--out", default="")
    ap.add_argument("--max-cycles", type=int, default=500000000)
    ap.add_argument("--timeout", type=int, default=3600)
    ap.add_argument("--cross", default=os.environ.get("CROSS", "riscv-none-elf-"))
    args = ap.parse_args(argv)
    args.model = args.model or f"t{args.task}"

    names = programs([p for p in args.programs.split(",") if p])
    targets = load_targets(args.targets)
    results = [grade_program(args, p, targets) for p in names]
    program_points = 100.0 / len(names) if names else 0.0
    total, all_correct = 0.0, True
    print(f"quiz on task {args.task} (model {args.model})")
    for r in results:
        print(f"\n{r['program']}: {r['check']}" if r["check"] else f"\n{r['program']}")
        if not r["ran"]:
            all_correct = False
            continue
        if r["exit"] not in (0,):
            print(f"  the program exited with {r['exit']}")
        share = program_points / max(len(r["routines"]), 1)
        print(f"  {'routine':<14} {'result':<8} {'insn':>9} {'cycles':>9} {'CPI':>6}  {'target (base/best)':<22} points")
        for x in r["routines"]:
            all_correct &= x["correct"]
            p = points(x, share)
            total += p
            cpi = f"{x['cycles'] / x['insn']:.2f}" if x["insn"] else "-"
            tgt = f"{x['targets'][0]}/{x['targets'][1]} {x['unit']}" if x["targets"] else "no target"
            tier = "best" if x["best"] else "base" if x["base"] else "correct" if x["correct"] else "FAILED"
            print(f"  {x['routine']:<14} {tier:<8} {x['insn']:>9} {x['cycles']:>9} {cpi:>6}  {tgt:<22} {p:5.1f}")
    print(f"\nquiz score on task {args.task}: {total:.1f} / 100")
    if args.out:
        os.makedirs(args.out, exist_ok=True)
        with open(os.path.join(args.out, f"quiz-{args.model}.json"), "w", encoding="utf-8") as f:
            json.dump({"task": args.task, "model": args.model, "score": round(total, 2), "programs": results}, f, indent=2)
            f.write("\n")
    return 0 if all_correct and results else 1


if __name__ == "__main__":
    sys.exit(main())
