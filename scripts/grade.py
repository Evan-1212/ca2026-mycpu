#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Score a working copy against the public checks of the five tasks.

Every check is one command that a student can also run by hand, and each has
points:

  deps      make check-deps: the host tools work
  history   every commit continues the Change-Id hash chain and follows the
            commit rules (scripts/chain.sh verify, scripts/commitlog.sh)
  hygiene   no -Wno- flag, and no skipped, ignored or pending spec in a task
  task1..5  make -C <task> test: every public spec of the task passes
  quiz      make -C 1-single-cycle quiz: the routines that you wrote for the quiz
            programs, scored by correctness and by the cycles they
            cost on the single-cycle core; the points are the share of the score

A task whose blanks are not filled in does not compile, so it scores 0: an
untouched copy of the public repository scores the points of no task. The held
back specs, the live modification and the questions of the report are the
instructors' and are not part of this script.

Run `make grade`; `scripts/grade.sh` runs the same checks on a clean copy of
the harness (see its header). Options:

  --list            print the checks and their points
  --only a,b        run only these checks (the score is then out of their points)
  --timeout S       seconds allowed to one check (default 3600)
  --out DIR         write grade.json and grade.md there
  --history-dir D   run the history check in this checkout (default: here)

The exit status is 0 when every selected check passes.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TASKS = {
    "task1": "1-single-cycle",
    "task2": "2-rv32im",
    "task3": "3-csr-trap",
    "task4": "4-pipeline",
    "task5": "5-soc",
}
POINTS = {"deps": 5, "history": 5, "hygiene": 5}
POINTS.update({name: 12 for name in TASKS})
POINTS["quiz"] = 25

# Spec code that stops a test from running: ScalaTest's ignore, a bare pending, @Ignore.
# (cancel is not listed: the specs use it to drop a mutant run that does not apply.)
SKIP_PATTERN = re.compile(r"^\s*(ignore\s*[\s(]|pending\s*$)|@Ignore\b")
# Verilator warnings are fixed or waived in sim/lint.vlt, never turned off.
FLAG_PATTERN = re.compile(r"-Wno-")


class Result:
    def __init__(self, name, ok, detail="", seconds=0.0, earned=None):
        self.name = name
        self.ok = ok
        self.detail = detail
        self.seconds = seconds
        self.earned = earned  # a check with partial credit says how many points it earned

    @property
    def points(self):
        if self.earned is not None:
            return self.earned
        return POINTS[self.name] if self.ok else 0


def run(argv, cwd, timeout, keep=8):
    """Run one command; return (ok, last `keep` lines of its output)."""
    try:
        p = subprocess.run(argv, cwd=cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return False, "timed out after %d s" % timeout
    except OSError as e:
        return False, str(e)
    tail = "\n".join(p.stdout.strip().splitlines()[-keep:])
    return p.returncode == 0, tail


def scan_hygiene(root):
    """Return the lines of the task specs and Makefiles that break a rule."""
    problems = []
    for task in TASKS.values():
        test_dir = os.path.join(root, task, "src", "test")
        for base, _dirs, files in os.walk(test_dir):
            for name in sorted(files):
                if not name.endswith(".scala"):
                    continue
                path = os.path.join(base, name)
                with open(path, encoding="utf-8", errors="replace") as f:
                    for n, line in enumerate(f, 1):
                        code = line.split("//", 1)[0]
                        if SKIP_PATTERN.search(code):
                            problems.append("%s:%d: a spec that does not run" % (os.path.relpath(path, root), n))
        makefile = os.path.join(root, task, "Makefile")
        if os.path.exists(makefile):
            with open(makefile, encoding="utf-8", errors="replace") as f:
                for n, line in enumerate(f, 1):
                    if FLAG_PATTERN.search(line):
                        problems.append("%s:%d: -Wno- flag" % (os.path.relpath(makefile, root), n))
    return problems


def check_history(history_dir, timeout):
    here = os.path.join(ROOT, "scripts")
    for script in ("chain.sh", "commitlog.sh"):
        ok, tail = run([os.path.join(here, script)] + (["verify"] if script == "chain.sh" else []),
                       history_dir, timeout)
        if not ok:
            return False, "%s: %s" % (script, tail)
    return True, "hash chain and commit rules hold"


def run_check(name, args):
    start = time.time()
    if name == "deps":
        ok, detail = run(["make", "check-deps"], ROOT, args.timeout)
    elif name == "history":
        ok, detail = check_history(args.history_dir, args.timeout)
    elif name == "quiz":
        ok, detail = run(["make", "-C", TASKS["task1"], "quiz"], ROOT, args.timeout, keep=60)
        m = re.search(r"quiz score on task 1: ([0-9.]+) / 100", detail)
        earned = round(float(m.group(1)) * POINTS["quiz"] / 100.0, 1) if m else 0.0
        return Result(name, ok and earned >= POINTS["quiz"], detail, time.time() - start, earned)
    elif name == "hygiene":
        problems = scan_hygiene(ROOT)
        ok, detail = (not problems), "\n".join(problems[:8]) or "no skipped spec, no -Wno- flag"
    else:
        ok, detail = run(["make", "-C", TASKS[name], "test"], ROOT, args.timeout)
    return Result(name, ok, detail, time.time() - start)


def score(results):
    """(points earned, points possible) of the results."""
    return sum(r.points for r in results), sum(POINTS[r.name] for r in results)


def report(results):
    earned, possible = score(results)
    lines = ["| Check | Points | Result | Seconds |", "|---|---|---|---|"]
    for r in results:
        verdict = "pass" if r.ok else "partial" if r.points else "FAIL"
        lines.append("| %s | %g/%d | %s | %.0f |" % (r.name, r.points, POINTS[r.name], verdict, r.seconds))
    lines.append("")
    lines.append("Score: %g/%d" % (earned, possible))
    return "\n".join(lines) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--only", default="")
    ap.add_argument("--timeout", type=int, default=3600)
    ap.add_argument("--out", default="")
    ap.add_argument("--history-dir", default=ROOT)
    args = ap.parse_args(argv)

    if args.list:
        for name, pts in POINTS.items():
            print("%-8s %3d" % (name, pts))
        print("%-8s %3d" % ("total", sum(POINTS.values())))
        return 0

    names = [n for n in args.only.split(",") if n] or list(POINTS)
    unknown = [n for n in names if n not in POINTS]
    if unknown:
        print("grade: unknown check %s (see --list)" % ", ".join(unknown), file=sys.stderr)
        return 2

    results = []
    for name in names:
        print("== %s" % name, flush=True)
        r = run_check(name, args)
        print("   %s" % ("pass" if r.ok else "FAIL"), flush=True)
        if not r.ok:
            print("\n".join("   " + line for line in r.detail.splitlines()), flush=True)
        results.append(r)

    text = report(results)
    print()
    print(text, end="")
    if args.out:
        os.makedirs(args.out, exist_ok=True)
        earned, possible = score(results)
        with open(os.path.join(args.out, "grade.json"), "w", encoding="utf-8") as f:
            json.dump({"score": earned, "possible": possible,
                       "checks": [{"name": r.name, "ok": r.ok, "points": r.points,
                                   "seconds": round(r.seconds, 1), "detail": r.detail} for r in results]},
                      f, indent=2)
            f.write("\n")
        with open(os.path.join(args.out, "grade.md"), "w", encoding="utf-8") as f:
            f.write(text)
    return 0 if all(r.ok for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
