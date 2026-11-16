#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
"""EX-08: the configuration of one student, from the student ID.

    student.py write ID [-o FILE]     write the configuration of ID (default: student.json)
    student.py show FILE              print it: register maps, event IDs, the build options
    student.py make FILE              the build options as one line of make words (sim/student.mk)
    student.py cflags FILE            the C compiler's -D flags of the register maps and event IDs
    student.py profiles               every configuration the mapping can give, one per line
    student.py check                  the mapping's own checks (see below)

A configuration leaves the ISA unchanged but changes what correct RTL and correct software
do: the register offsets of SimCtrl and of the UART, the IDs of the hpm events, the divider's
latency (T2 on), the CLINT's tick divisor (T3 on) and the RAM's wait states (T4, T5). Every
option exists in the reference, which passes its checks with each (EX-08), and nothing else is
ever assigned.

The ID is mapped to one of 8 configurations, fixed below (PROFILES). They are the rows of the
two-level orthogonal array L8: across the 8, each option takes each of its two values 4 times,
and each pair of options takes each of its 4 combinations twice. So the whole space that
students can get is 8 configurations, and the reference is checked on every one of them
(`make student-matrix` in the reference repository), not on a sample. Profile 0 is the default of the reference
(docs/student-config.md): no student.json gives the same build as profile 0.

The mapping: the first 8 bytes of SHA-256("ca2026-mycpu EX-08 v1" NUL ID), as a big-endian
integer, modulo 8. The ID is case-insensitive (upper-cased) and has 1 to 32 letters, digits,
`-` or `_`. Changing SALT or PROFILES changes every student's configuration: never do that
during a semester. A file is only ever the configuration of its ID: there is no way to write a
profile by its number, and one whose ID maps to another profile is refused.

`check` verifies that the table is an orthogonal array, that profile 0 is the default, that
every value is one the reference implements (the option lists below are those of the task
Makefiles and of mycpu.common.StudentConfig), that every layout has distinct word-aligned
offsets below 0x20 and every event table is a permutation of 1..8, and that the hash reaches
every profile within the first 1000 sample IDs.
"""

import argparse
import hashlib
import json
import os
import re
import sys

SCHEMA = "ca2026-mycpu-student/1"
SALT = b"ca2026-mycpu EX-08 v1\0"

# Register offsets (D12) and the alternatives a student can get.
SIMCTRL = {
    "d12": {"EXIT": 0x00, "PUTC": 0x04, "CYCLE_LO": 0x08, "CYCLE_HI": 0x0C,
            "INSTRET_LO": 0x10, "INSTRET_HI": 0x14, "MARK": 0x18, "MEIP": 0x1C},
    "rev": {"EXIT": 0x1C, "PUTC": 0x18, "CYCLE_LO": 0x10, "CYCLE_HI": 0x14,
            "INSTRET_LO": 0x08, "INSTRET_HI": 0x0C, "MARK": 0x04, "MEIP": 0x00},
}
UART = {
    "d12": {"TXDATA": 0x0, "RXDATA": 0x4, "STATUS": 0x8, "CTRL": 0xC},
    "swap": {"TXDATA": 0x0, "RXDATA": 0x8, "STATUS": 0x4, "CTRL": 0xC},
}
# hpm event IDs (docs/hpm-events.md), by event.
EVENT_NAMES = ["divider_busy", "taken_branch", "trap_taken", "load_use_stall", "flush_bubble",
               "instruction_wait", "data_wait", "other"]
HPM = {
    "d12": dict(zip(EVENT_NAMES, range(1, 9))),
    "perm": dict(zip(EVENT_NAMES, [3, 6, 8, 1, 7, 2, 5, 4])),
}
# The option values the reference implements (task Makefiles: DIV_LATENCY, TICK_DIVISOR, I_WAIT,
# D_WAIT, RAM_WAIT); a profile may use only these.
IMPLEMENTED = {"div_latency": (34, 66), "tick_divisor": (1, 4), "mem_wait": (0, 1)}

# The options, each with its two values (level 0 first: the reference default).
OPTIONS = [
    ("simctrl", ("d12", "rev")),
    ("uart", ("d12", "swap")),
    ("hpm_events", ("d12", "perm")),
    ("div_latency", (34, 66)),
    ("tick_divisor", (1, 4)),
    ("mem_wait", (0, 1)),
]
# L8(2^7): eight runs, seven two-level columns; the options use the first six.
L8 = [
    (0, 0, 0, 0, 0, 0, 0),
    (0, 0, 0, 1, 1, 1, 1),
    (0, 1, 1, 0, 0, 1, 1),
    (0, 1, 1, 1, 1, 0, 0),
    (1, 0, 1, 0, 1, 0, 1),
    (1, 0, 1, 1, 0, 1, 0),
    (1, 1, 0, 0, 1, 1, 0),
    (1, 1, 0, 1, 0, 0, 1),
]
PROFILES = [{name: values[row[i]] for i, (name, values) in enumerate(OPTIONS)} for row in L8]

ID_RE = re.compile(r"^[A-Z0-9_-]{1,32}$")


class StudentError(Exception):
    pass


def normalize(student_id):
    sid = student_id.strip().upper()
    if not ID_RE.match(sid):
        raise StudentError(f"'{student_id}' is not a student ID (1 to 32 letters, digits, '-' or '_')")
    return sid


def profile_of(student_id):
    digest = hashlib.sha256(SALT + normalize(student_id).encode("ascii")).digest()
    return int.from_bytes(digest[:8], "big") % len(PROFILES)


def config(index, student_id):
    p = PROFILES[index]
    return {
        "schema": SCHEMA,
        "id": student_id,
        "profile": index,
        "simctrl": dict(layout=p["simctrl"], **SIMCTRL[p["simctrl"]]),
        "uart": dict(layout=p["uart"], **UART[p["uart"]]),
        "hpm_events": dict(layout=p["hpm_events"], **HPM[p["hpm_events"]]),
        "div_latency": p["div_latency"],
        "tick_divisor": p["tick_divisor"],
        "mem_wait": p["mem_wait"],
    }


def dump(cfg):
    return json.dumps(cfg, indent=2) + "\n"


def load(path):
    """A student.json, checked against its profile: a file edited by hand is refused."""
    try:
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
    except (OSError, ValueError) as e:
        raise StudentError(f"cannot read {path}: {e}") from e
    if not isinstance(cfg, dict) or cfg.get("schema") != SCHEMA:
        raise StudentError(f"{path}: not a {SCHEMA} file (write it with 'make student ID=<id>')")
    index = cfg.get("profile")
    if not isinstance(index, int) or not 0 <= index < len(PROFILES):
        raise StudentError(f"{path}: profile {index!r} is not one of 0..{len(PROFILES) - 1}")
    sid = cfg.get("id")
    if not isinstance(sid, str) or not ID_RE.match(sid):
        raise StudentError(f"{path}: no student ID (write it with 'make student ID=<id>')")
    if profile_of(sid) != index:
        raise StudentError(f"{path}: ID {sid} maps to profile {profile_of(sid)}, not {index}")
    if cfg != config(index, sid):
        raise StudentError(f"{path}: differs from the configuration of profile {index}; "
                           f"write it again with 'make student ID={sid}'")
    return cfg


def make_words(cfg):
    """`key=value` words for sim/student.mk. tag names the register maps and event IDs (empty
    for D12's): models and C builds that depend on them go to directories of their own."""
    layouts = (cfg["simctrl"]["layout"], cfg["uart"]["layout"], cfg["hpm_events"]["layout"])
    tag = "" if layouts == ("d12", "d12", "d12") else "-".join(layouts)
    words = [f"tag={tag}", f"profile={cfg['profile']}", f"div_latency={cfg['div_latency']}",
             f"tick_divisor={cfg['tick_divisor']}", f"mem_wait={cfg['mem_wait']}",
             f"simctrl_layout={cfg['simctrl']['layout']}"]
    # what ACT4's macros store to (tests/act4/Makefile): EXIT, PUTC and MEIP
    words += [f"simctrl_{k.lower()}=0x{cfg['simctrl'][k]:02x}" for k in ("EXIT", "PUTC", "MEIP")]
    return " ".join(words)


def cflags(cfg):
    flags = [f"-DSIMCTRL_{k}=0x{v:02x}" for k, v in cfg["simctrl"].items() if k != "layout"]
    flags += [f"-DUART_{k}=0x{v:x}" for k, v in cfg["uart"].items() if k != "layout"]
    flags += [f"-DHPM_EVENT_{k.upper()}={v}" for k, v in cfg["hpm_events"].items() if k != "layout"]
    return " ".join(flags)


def show(cfg):
    lines = [f"student {cfg['id']}: profile {cfg['profile']} of {len(PROFILES)}", ""]
    lines.append(f"SimCtrl at 0x1000_1000 ({cfg['simctrl']['layout']}):")
    for k, v in sorted(((k, v) for k, v in cfg["simctrl"].items() if k != "layout"), key=lambda kv: kv[1]):
        lines.append(f"  +0x{v:02x}  {k}")
    lines.append(f"UART at 0x1000_0000 ({cfg['uart']['layout']}):")
    for k, v in sorted(((k, v) for k, v in cfg["uart"].items() if k != "layout"), key=lambda kv: kv[1]):
        lines.append(f"  +0x{v:02x}  {k}")
    lines.append(f"hpm event IDs ({cfg['hpm_events']['layout']}):")
    for k, v in sorted(((k, v) for k, v in cfg["hpm_events"].items() if k != "layout"), key=lambda kv: kv[1]):
        lines.append(f"  {v}  {k}")
    lines += ["", f"DIV_LATENCY={cfg['div_latency']} (T2 on)", f"TICK_DIVISOR={cfg['tick_divisor']} (T3 on)",
              f"RAM wait states {cfg['mem_wait']} (T4: I_WAIT=D_WAIT={cfg['mem_wait']}; T5: RAM_WAIT={cfg['mem_wait']})"]
    return "\n".join(lines) + "\n"


def self_check():
    problems = []
    # the array: every option two values 4 times each, every pair of options all 4 pairs twice
    for i, (name, values) in enumerate(OPTIONS):
        for v in values:
            if sum(p[name] == v for p in PROFILES) != len(PROFILES) // 2:
                problems.append(f"{name}={v} is not in half the profiles")
        for j in range(i + 1, len(OPTIONS)):
            other, ovalues = OPTIONS[j]
            for v in values:
                for w in ovalues:
                    if sum(p[name] == v and p[other] == w for p in PROFILES) != len(PROFILES) // 4:
                        problems.append(f"{name}={v} with {other}={w} is not in a quarter of the profiles")
    if PROFILES[0] != {name: values[0] for name, values in OPTIONS}:
        problems.append("profile 0 is not the reference default")
    for name, values in IMPLEMENTED.items():
        used = {p[name] for p in PROFILES}
        if not used <= set(values):
            problems.append(f"{name} values {sorted(used - set(values))} are not implemented")
    for table, layouts in (("simctrl", SIMCTRL), ("uart", UART)):
        for layout, regs in layouts.items():
            offs = list(regs.values())
            if len(set(offs)) != len(offs) or any(o % 4 or not 0 <= o < 0x20 for o in offs):
                problems.append(f"{table} layout {layout}: offsets must be distinct, word-aligned, below 0x20")
            if set(regs) != set(layouts["d12"]):
                problems.append(f"{table} layout {layout}: not the registers of d12")
    for layout, ids in HPM.items():
        if sorted(ids.values()) != list(range(1, 9)) or set(ids) != set(EVENT_NAMES):
            problems.append(f"hpm layout {layout}: not a permutation of the IDs 1..8 over the 8 events")
    reached = {profile_of(f"S{n:07d}") for n in range(1000)}
    if reached != set(range(len(PROFILES))):
        problems.append(f"the first 1000 sample IDs reach only the profiles {sorted(reached)}")
    for index in range(len(PROFILES)):
        cfg = config(index, f"S{index}")
        if json.loads(dump(cfg)) != cfg:
            problems.append(f"profile {index} does not survive JSON")
    return problems


def write(path, cfg):
    with open(path + ".new", "w", encoding="utf-8") as f:
        f.write(dump(cfg))
    os.replace(path + ".new", path)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    w = sub.add_parser("write")
    w.add_argument("id")
    w.add_argument("-o", "--output", default="student.json")
    for name in ("show", "make", "cflags"):
        sub.add_parser(name).add_argument("file")
    sub.add_parser("profiles")
    sub.add_parser("check")
    a = ap.parse_args(argv)
    try:
        if a.cmd == "write":
            sid = normalize(a.id)
            cfg = config(profile_of(sid), sid)
            write(a.output, cfg)
            sys.stdout.write(f"wrote {a.output}\n\n" + show(cfg))
        elif a.cmd == "show":
            sys.stdout.write(show(load(a.file)))
        elif a.cmd == "make":
            print(make_words(load(a.file)))
        elif a.cmd == "cflags":
            print(cflags(load(a.file)))
        elif a.cmd == "profiles":
            for index, prof in enumerate(PROFILES):
                print(f"{index} " + " ".join(f"{k}={v}" for k, v in prof.items()))
        elif a.cmd == "check":
            problems = self_check()
            for problem in problems:
                print(f"FAIL {problem}")
            if problems:
                return 1
            print(f"student.py check: PASS ({len(PROFILES)} profiles, an orthogonal array of "
                  f"{len(OPTIONS)} options; every one reached by the hash)")
    except StudentError as e:
        print(f"student.py: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
