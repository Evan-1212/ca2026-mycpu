#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""The student's ID, the parameters it assigns to the report questions, and the stamp of a run.

Usage: student-id.py check [FILE]           validate the ID file (exit 1 with the reason)
       student-id.py id [FILE]              print the ID (NONE for an auditor)
       student-id.py params [FILE] [--json] [--task N] [--id ID]
                                            print the parameters of the questions
       student-id.py get KEY [FILE] [--id ID]
                                            print one parameter, such as T4.Q1.I_WAIT
       student-id.py stamp [FILE] [RUN...]  print the stamp of a run (sim/student.mk, sim/task.mk)
       student-id.py predicted [FILE] RUN...
                                            refuse a run with an assigned value whose question has
                                            no prediction committed and pushed (sim/task.mk)

FILE is STUDENT_ID at the top of the tree. It holds one line: your student ID with its letters in
upper case (F74121234, not the f74121234 of your school e-mail), or NONE if you audit the course.
An auditor's work goes through the same grading, but its score is not recorded. An NCKU student ID
has nine characters: the department (a letter, then a letter or a digit), the level (4 to 8), the
year, the admission, a serial number and a check digit (C34031328, VL6021023); another school's ID
of 6 to 12 letters and digits is accepted with a note.

The parameters replace "a value of your choice" in the questions of the task READMEs: each is a
function of the ID alone (SHA-256), so the instructors compute the same ones and repeat your runs
with them. Two students rarely share a parameter set, so an answer is not copied from another
report. `make my-params` prints them.

The stamp names a run: the ID, the git e-mail, the HEAD commit (with + when tracked files differ
from it) and a random nonce. The harness writes it into the header of a +trace FST file
(sim/main.cpp) and obs-pack.py into the manifest of a bundle (docs/observation.md, "stamp").

Predictions are binding. RUN is the configuration of a run as sim/task.mk gives it (task=4 i_wait=3
d_wait=5 ...). A run that uses the values your ID assigns to a question of its task (T1.Q1's
UART_DIVIDER, T1.Q2's RAM_BYTES, T3.Q2's TICK_DIVISOR, T4.Q1's I_WAIT and D_WAIT, T5.Q2's
RAM_WAIT) waits for that question's prediction: the file notes/t<n>-q<m>.md, committed, unchanged
since, and pushed to a remote (GitHub records when). `predicted` refuses the run until then, and
the stamp of the run names the commit of the prediction (pred=T4.Q1@<commit>). The instructors
compare the time GitHub received that commit with the commits that hold the measurement.
"""

import hashlib
import json
import os
import re
import secrets
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DEFAULT_FILE = os.path.join(ROOT, "STUDENT_ID")
AUDITOR = "NONE"
ID_RE = re.compile(r"^[A-Z0-9]{6,12}$")
NCKU_RE = re.compile(r"^[A-Z][A-Z0-9][4-8][0-9]{6}$")
SALT = "ca2026-mycpu report parameters"

BENCHMARKS = ["dhrystone", "duremark", "qrcode-table-L", "qrcode-table-M", "qrcode-table-Q", "qrcode-table-H"]
VARIANTS = ["Stall", "Forward", "EarlyBranch"]
# T4 Q2: sequences that are not rows of the hazard table, as (name, producer lines, consumer)
SEQUENCES = [
    ("ALU-store (address)", ["add   a1, t1, t1"], "sw    t1, 4(a1)"),
    ("ALU-load (address)", ["add   a1, t1, t1"], "lw    s0, 0(a1)"),
    ("load-load (address)", ["lw    a1, 8(t2)"], "lw    s0, 0(a1)"),
    ("load-nop-store (address)", ["lw    a1, 8(t2)", "nop"], "sw    t1, 4(a1)"),
    ("ALU-nop-JALR", ["addi  a1, t3, 0", "nop"], "jalr  zero, 0(a1)"),
    ("load-nop-JALR", ["lw    a1, 12(t2)", "nop"], "jalr  zero, 0(a1)"),
    ("two producers", ["add   a1, t1, t1", "add   a2, t1, t1"], "add   s0, a1, a2"),
    ("multiply-store (data)", ["mul   a1, t1, t1"], "sw    a1, 4(t2)"),
    ("ALU-nop-nop-branch", ["add   a1, t1, t1", "nop", "nop"], "beq   a1, zero, n"),
    ("load-nop-nop-use", ["lw    a1, 0(t2)", "nop", "nop"], "add   s0, a1, t1"),
]
COUNTERS = ["mcycle", "minstret", "mhpmcounter3", "mhpmcounter7"]
DEVICES = ["PUTC (SimCtrl)", "a VGA palette entry"]


class IdError(Exception):
    pass


def validate(text):
    """The ID of a file's text, or IdError with the reason."""
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if len(lines) != 1:
        raise IdError("STUDENT_ID must hold one line: your student ID, or NONE if you audit the course")
    value = lines[0]
    if value == AUDITOR:
        return value
    if value.upper() == AUDITOR:
        raise IdError(f"write NONE in upper case, not '{value}'")
    if value != value.upper() and ID_RE.match(value.upper()):
        raise IdError(f"write the letters of your student ID in upper case: {value.upper()}, not {value}")
    if not ID_RE.match(value) or not re.search(r"[0-9]", value):
        raise IdError(f"'{value}' is not a student ID: 6 to 12 upper-case letters and digits, or NONE")
    return value


def read_id(path):
    try:
        with open(path, encoding="utf-8") as f:
            return validate(f.read())
    except FileNotFoundError:
        raise IdError(f"{path} does not exist: create it with your student ID (or NONE if you audit the course)")
    except UnicodeDecodeError:
        raise IdError(f"{path} is not text")


def pick(student, key, options):
    digest = hashlib.sha256(f"{SALT}\n{student}\n{key}".encode()).digest()
    return options[int.from_bytes(digest[:8], "big") % len(options)]


def params(student):
    """{key: value} of every question parameter, in the order of the READMEs."""
    p = {}
    p["T1.Q1.UART_DIVIDER"] = pick(student, "T1.Q1", [d for d in range(4, 65) if d != 16])
    p["T1.Q2.RAM_BYTES"] = pick(student, "T1.Q2", [1 << k for k in range(16, 22)])
    loops = list(range(200, 2001, 100))
    first = pick(student, "T1.Q3.a", loops)
    p["T1.Q3.LOOPS"] = f"{first},{pick(student, 'T1.Q3.b', [n for n in loops if n != first])}"
    p["T2.Q1.QR_TEXT"] = f"MYCPU {student} {pick(student, 'T2.Q1', list(range(100, 1000)))}"
    p["T2.Q2.LOOPS"] = pick(student, "T2.Q2", loops)
    p["T3.Q2.TICK_DIVISOR"] = pick(student, "T3.Q2", list(range(2, 65)))
    waits = [(i, d) for i in range(1, 5) for d in range(1, 5) if (i, d) != (2, 2)]
    i_wait, d_wait = pick(student, "T4.Q1", waits)
    p["T4.Q1.I_WAIT"] = i_wait
    p["T4.Q1.D_WAIT"] = d_wait
    p["T4.Q1.VARIANT"] = pick(student, "T4.Q1.variant", VARIANTS)
    p["T4.Q2.SEQUENCE"] = pick(student, "T4.Q2", [s[0] for s in SEQUENCES])
    p["T4.Q3.COUNTER"] = pick(student, "T4.Q3", COUNTERS)
    p["T5.Q1.BENCHMARK"] = pick(student, "T5.Q1", BENCHMARKS)
    p["T5.Q1.VARIANT"] = pick(student, "T5.Q1.variant", VARIANTS)
    p["T5.Q2.RAM_WAIT"] = pick(student, "T5.Q2", [1, 2, 3, 4])
    p["T5.Q2.BENCHMARK"] = pick(student, "T5.Q2.benchmark", BENCHMARKS)
    p["T5.Q3.DEVICE"] = pick(student, "T5.Q3", DEVICES)
    return p


def sequence_text(name):
    for n, producers, consumer in SEQUENCES:
        if n == name:
            return "; ".join(producers + [consumer])
    raise KeyError(name)


def git(*args):
    try:
        return subprocess.run(["git", "-C", ROOT, *args], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


# a run's configuration word (sim/task.mk) -> the question key whose value it is, for the questions
# whose assigned values are make variables of a run; T4.Q1 needs both of its waits
RUN_KEYS = {
    "T1.Q1": {"uart_divider": "T1.Q1.UART_DIVIDER"},
    "T1.Q2": {"ram_bytes": "T1.Q2.RAM_BYTES"},
    "T3.Q2": {"tick_divisor": "T3.Q2.TICK_DIVISOR"},
    "T4.Q1": {"i_wait": "T4.Q1.I_WAIT", "d_wait": "T4.Q1.D_WAIT"},
    "T5.Q2": {"ram_wait": "T5.Q2.RAM_WAIT"},
}


def parse_run(words):
    """{name: value} of RUN words name=value; other words are left to the caller."""
    run = {}
    for w in words:
        name, sep, value = w.partition("=")
        if sep:
            run[name] = value
    return run


def run_questions(student, run):
    """The questions of the run's task whose assigned values the run uses."""
    p = params(student)
    found = []
    for question, keys in RUN_KEYS.items():
        if run.get("task") != question[1]:
            continue
        if all(run.get(word, "") == str(p[key]) for word, key in keys.items()):
            found.append(question)
    return found


def prediction_file(question):
    task, q = question.lower().split(".")
    return f"notes/{task}-{q}.md"


def prediction(question):
    """(commit or None, why not) of the prediction of a question: committed, unchanged and pushed."""
    path = prediction_file(question)
    commit = git("log", "-1", "--format=%H", "--", path)
    if not commit:
        return None, f"{path} is not committed"
    if git("status", "--porcelain", "--", path):
        return None, f"{path} has changes that are not committed"
    if not git("branch", "-r", "--contains", commit):
        return None, f"the commit of {path} ({commit[:10]}) is not pushed to a remote"
    return commit, ""


def stamp(path, run=None):
    try:
        student = read_id(path)
    except IdError:
        student = "unset" if not os.path.exists(path) else "invalid"
    head = git("rev-parse", "--short=12", "HEAD") or "none"
    if head != "none" and git("status", "--porcelain", "--untracked-files=no"):
        head += "+"
    email = re.sub(r"\s", "", git("config", "user.email")) or "-"
    text = f"id={student} email={email} head={head} nonce={secrets.token_hex(6)}"
    if run and student not in ("unset", "invalid"):
        marks = []
        for question in run_questions(student, run):
            commit, _ = prediction(question)
            marks.append(f"{question}@{commit[:10] if commit else 'none'}")
        if marks:
            text += " pred=" + ",".join(marks)
    return text


def predicted(path, run):
    """0 when the run may go on; 1, with the reason, when a prediction is missing."""
    try:
        student = read_id(path)
    except IdError:
        return 0  # no ID, no assigned values: nothing to wait for
    missing = []
    for question in run_questions(student, run):
        commit, why = prediction(question)
        if commit:
            print(f"  PREDICT {question}: {prediction_file(question)} at {commit[:10]}, pushed")
        else:
            missing.append(f"{question}: {why}")
    if not missing:
        return 0
    print("student-id: this run uses the values your ID assigns to " + ", ".join(m.split(":")[0] for m in missing)
          + ", and the prediction comes first:", file=sys.stderr)
    for m in missing:
        print(f"  {m}", file=sys.stderr)
    print("  Write your prediction and its derivation there, commit it and push it (git push), then run again.",
          file=sys.stderr)
    return 1


def main(argv):
    if not argv or argv[0] not in ("check", "id", "params", "get", "stamp", "predicted"):
        print(__doc__.split("\n\n")[1], file=sys.stderr)
        return 2
    cmd, rest = argv[0], argv[1:]
    as_json = "--json" in rest
    task = override = None
    words = []
    i = 0
    while i < len(rest):
        if rest[i] == "--json":
            pass
        elif rest[i] in ("--task", "--id") and i + 1 < len(rest):
            task, override = (rest[i + 1], override) if rest[i] == "--task" else (task, rest[i + 1])
            i += 1
        else:
            words.append(rest[i])
        i += 1
    key = words.pop(0) if cmd == "get" and words else None
    run = parse_run(words)
    words = [w for w in words if "=" not in w]
    path = words[0] if words else DEFAULT_FILE
    if cmd == "stamp":
        print(stamp(path, run))
        return 0
    if cmd == "predicted":
        return predicted(path, run)
    try:
        student = validate(override) if override is not None else read_id(path)
    except IdError as e:
        print(f"student-id: {e}", file=sys.stderr)
        return 1
    if cmd == "check":
        note = ""
        if student == AUDITOR:
            note = " (an auditor: graded, not scored)"
        elif not NCKU_RE.match(student):
            note = " (not the format of an NCKU student ID: accepted as it is; check it)"
        print(f"  OK      {student}{note}")
        return 0
    if cmd == "id":
        print(student)
        return 0
    p = params(student)
    if cmd == "get":
        if key not in p:
            print(f"student-id: no parameter {key} (student-id.py params lists them)", file=sys.stderr)
            return 1
        print(p[key])
        return 0
    if task:
        p = {k: v for k, v in p.items() if k.startswith(f"T{task}.")}
    if as_json:
        print(json.dumps({"id": student, "params": p}, indent=1))
        return 0
    print(f"Parameters of the report questions for {student}" + (" (an auditor: graded, not scored)" if student == AUDITOR else ""))
    for k, v in p.items():
        extra = f"   ({sequence_text(v)})" if k == "T4.Q2.SEQUENCE" else ""
        print(f"  {k:22} {v}{extra}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
