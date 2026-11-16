#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
"""VER-06: the instruction counts of the benchmarks on the CPU against rv32emu v1.3.

    bench-xcheck.py --rv32emu EMU --sw SW_DIR --arch ARCH [--arch ARCH ...]
                    [--bench NAME ...] [--csv OUT] LABEL=VSIM [LABEL=VSIM ...]

For every ARCH and benchmark, it first checks that the two builds measure the
same code, from their linker maps and ELF files (SW_DIR/<platform>/<arch>/):

  - the layout of `.text` up to the platform slot (`__platform_start`) is the
    same in both maps: every input section with its address, size and object
    (the platform object's name aside), and every symbol with its address, so
    every function the measured code can call is linked at the same address;
  - the five entry points of the platform slot (link.ld) have the same
    addresses, and so has every symbol of the other output sections that is
    not the platform back end's own (link.ld puts all of the back end's code
    and data into the slot; a symbol of an input section of platform_*.o
    elsewhere is left out, since only one of the two builds has it, while
    the symbols after it still have to keep their addresses);
  - the bytes of `.text` up to the platform slot are identical. The region
    between the markers runs inside that code: only the markers themselves
    and the console are in the slot, and both are outside the counted region
    (the program reads `instret` after its start marker and before its stop
    marker).

Then it runs the rv32emu build on rv32emu and the mycpu build on every model
(LABEL=VSIM, for example T4/Forward=build/t4-forward/Vsim) and compares the
program's own `instret` delta between the markers: on the CPU a `csrr instret`
(minstret) and on rv32emu its `rdinstret`. Dhrystone and DureMark print one
count each; the QR encoder (qrcode) prints one per ECC level. Any difference,
a run that exits non-zero, or a count printed other than once fails the check.

With --csv the comparison is written there, one row per (model, arch,
benchmark): task,variant,arch,benchmark,instret,rv32emu_instret; only when
every check passes, and an older file is removed first, so a failed run
never leaves one.
MAX_CYCLES (default 2000000000) bounds each model run.
"""

import argparse
import os
import re
import struct
import subprocess
import sys

# The program's own count between its markers, per benchmark: (row suffix, regex).
COUNTS = {
    "dhrystone": [("", re.compile(r"^perf: instret (\d+)$", re.M))],
    "duremark": [("", re.compile(r"^perf: Instret +: (\d+)$", re.M))],
    "qrcode": [
        ("-" + ecc, re.compile(r"^perf: ecc=" + ecc + r" cycles=\d+ instret=(\d+)$", re.M)) for ecc in "LMQH"
    ],
}
SLOT_ENTRIES = ("platform_putchar", "platform_exit", "bench_mark_start", "bench_mark_stop", "platform_init")
PLATFORMS = ("mycpu", "rv32emu")


class XcheckError(Exception):
    pass


# ---------------------------------------------------------------------------
# The linker maps


def _join_wrapped(lines):
    """GNU ld puts a long input-section name on a line of its own and its address on the next."""
    out = []
    for line in lines:
        if out and re.fullmatch(r" \S+", out[-1]) and line.startswith("                0x"):
            out[-1] = out[-1] + " " + line.strip()
        else:
            out.append(line)
    return out


def _normalize_object(field):
    """The object of an input section without the build directory, and the platform object
    under one name: `obj/runtime/platform.o`."""
    field = re.sub(r"^.*/sw/[^/]+/[^/]+/obj/", "obj/", field)
    field = re.sub(r"platform_(mycpu|rv32emu)\.o$", "platform.o", field)
    return field


SECTION = re.compile(r"^ (\S+)\s+0x([0-9a-f]+)\s+0x([0-9a-f]+)(?:\s+(\S.*))?$")
SYMBOL = re.compile(r"^\s+0x([0-9a-f]+)\s+([A-Za-z_.$][\w.$]*)$")
ASSIGN = re.compile(r"^\s+0x([0-9a-f]+)\s+(\S+) = ")


def read_map(path):
    """The parts of a map that VER-06 compares:
    (measured .text layout, slot entry symbols, other symbols, __platform_start)."""
    try:
        with open(path, encoding="utf-8") as f:
            lines = _join_wrapped(f.read().splitlines())
    except OSError as e:
        raise XcheckError(f"cannot read {path}: {e.strerror}") from e
    output = None  # the output section a line belongs to
    part = None  # in .text: "measured", "entries" or "private"
    platform = False  # the last input section is the platform back end's (platform_*.o)
    layout, entries, others = [], [], []
    start = None
    for line in lines:
        m = re.match(r"^(\.\S+|/DISCARD/)\s*(?:0x|$)", line)
        if m:
            output = m.group(1)
            part = "measured" if output == ".text" else None
            platform = False
            continue
        if line.startswith("OUTPUT(") or line == "":
            continue
        a = ASSIGN.match(line)
        if a:
            if a.group(2) == "__platform_start" and output == ".text":
                start = int(a.group(1), 16)
                part = "entries"
            continue
        if output == ".text" and line.startswith(" *platform_*.o(.text .text.*"):
            part = "private"
            continue
        s = SYMBOL.match(line)
        if s:
            item = (int(s.group(1), 16), s.group(2))
            if part == "measured":
                layout.append(("symbol",) + item)
            elif part == "entries":
                entries.append(item)
            elif part is None and output is not None and not platform:
                others.append(item)
            continue
        c = SECTION.match(line)
        if c:
            platform = re.search(r"platform_(mycpu|rv32emu)\.o$", c.group(4) or "") is not None
        if part == "measured":
            if c and c.group(1) != "*fill*":
                layout.append(("section", c.group(1), int(c.group(2), 16), int(c.group(3), 16),
                               _normalize_object(c.group(4) or "")))
            elif line.startswith(" *fill*"):
                layout.append(("fill", line.split()[1], line.split()[2]))
    if start is None:
        raise XcheckError(f"{path}: no __platform_start in .text (is it a map of runtime/link.ld?)")
    return layout, entries, others, start


def text_bytes(path):
    """(address, bytes) of the `.text` section of an ELF32 little-endian file."""
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError as e:
        raise XcheckError(f"cannot read {path}: {e.strerror}") from e
    if data[:4] != b"\x7fELF" or data[4] != 1 or data[5] != 1:
        raise XcheckError(f"{path}: not a 32-bit little-endian ELF file")
    shoff, = struct.unpack_from("<I", data, 0x20)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x2E)

    def section(i):
        return struct.unpack_from("<IIIIIIIIII", data, shoff + i * shentsize)

    strtab = section(shstrndx)
    for i in range(shnum):
        name, _type, _flags, addr, offset, size = section(i)[:6]
        end = data.index(b"\0", strtab[4] + name)
        if data[strtab[4] + name:end] == b".text":
            return addr, data[offset:offset + size]
    raise XcheckError(f"{path}: no .text section")


def same_text(sw, arch, bench):
    """None if the mycpu and rv32emu builds of `bench` measure the same code, else why not."""
    maps, elfs = {}, {}
    for p in PLATFORMS:
        base = os.path.join(sw, p, arch, bench)
        maps[p] = read_map(base + ".map")
        elfs[p] = text_bytes(base + ".elf")
    (la, ea, oa, sa), (lb, eb, ob, sb) = maps["mycpu"], maps["rv32emu"]
    if sa != sb:
        return f"the platform slot starts at 0x{sa:08x} on mycpu and 0x{sb:08x} on rv32emu"
    if la != lb:
        diff = next((f"{x} / {y}" for x, y in zip(la, lb) if x != y), f"{len(la)} / {len(lb)} lines")
        return f"the .text layout before the platform slot differs: {diff}"
    names = sorted(n for _, n in ea)
    if names != sorted(SLOT_ENTRIES) or sorted(ea) != sorted(eb):
        return f"the slot's entry points differ: {sorted(ea)} / {sorted(eb)}"
    if sorted(oa) != sorted(ob):
        diff = sorted(set(oa) ^ set(ob))[:4]
        return f"symbol addresses outside .text differ: {diff}"
    (ta, ba), (tb, bb) = elfs["mycpu"], elfs["rv32emu"]
    n = sa - ta
    if ta != tb or n <= 0 or len(ba) < n or len(bb) < n:
        return ".text does not start at the same address or is shorter than its measured part"
    if ba[:n] != bb[:n]:
        first = next(i for i in range(n) if ba[i] != bb[i])
        return f"the .text bytes differ at 0x{ta + first:08x}"
    if not any(sym == "symbol" for sym, *_ in la):
        return "no symbol before the platform slot"
    return None


# ---------------------------------------------------------------------------
# The runs


def counts(bench, text, where):
    """{row name: count} of one run's output; every count printed exactly once."""
    out = {}
    for suffix, regex in COUNTS[bench]:
        found = regex.findall(text)
        if len(found) != 1:
            raise XcheckError(f"{where}: {bench}{suffix}: the count is printed {len(found)} times, not once")
        out[bench + suffix] = int(found[0])
    return out


def run(cmd, where):
    try:
        p = subprocess.run(cmd, stdin=subprocess.DEVNULL, capture_output=True, text=True, errors="replace")
    except OSError as e:
        raise XcheckError(f"{where}: cannot run {cmd[0]}: {e.strerror}") from e
    if p.returncode != 0:
        tail = "\n".join((p.stdout + p.stderr).splitlines()[-5:])
        raise XcheckError(f"{where}: exit code {p.returncode}\n{tail}")
    return p.stdout


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--rv32emu", required=True)
    ap.add_argument("--sw", required=True, help="the software build directory (<build>/sw)")
    ap.add_argument("--arch", action="append", required=True)
    ap.add_argument("--bench", action="append", choices=sorted(COUNTS))
    ap.add_argument("--csv")
    ap.add_argument("models", nargs="+", metavar="LABEL=VSIM")
    a = ap.parse_args(argv)
    benches = a.bench or ["dhrystone", "duremark", "qrcode"]
    max_cycles = os.environ.get("MAX_CYCLES", "2000000000")
    models = []
    for m in a.models:
        label, sep, vsim = m.partition("=")
        if not sep or not label or not vsim or label.count("/") > 1:
            ap.error(f"a model is LABEL=VSIM with LABEL <task> or <task>/<variant>, not '{m}'")
        if not os.access(vsim, os.X_OK):
            ap.error(f"{vsim} is not an executable model (run 'make verilator' in its task)")
        models.append((label, vsim))

    # a failed run must not leave the file of an earlier one that passed
    if a.csv and os.path.lexists(a.csv):
        os.remove(a.csv)
    fail = 0
    rows = []
    for arch in a.arch:
        for bench in benches:
            try:
                why = same_text(a.sw, arch, bench)
            except XcheckError as e:
                why = str(e)
            if why:
                print(f"FAIL {arch} {bench}: not the same measured code: {why}")
                fail += 1
                continue
            try:
                emu = counts(bench, run([a.rv32emu, "-q", os.path.join(a.sw, "rv32emu", arch, bench + ".elf")],
                                        f"rv32emu {arch} {bench}"), f"rv32emu {arch}")
            except XcheckError as e:
                print(f"FAIL {e}")
                fail += 1
                continue
            for label, vsim in models:
                elf = os.path.join(a.sw, "mycpu", arch, bench + ".elf")
                where = f"{label} {arch} {bench}"
                try:
                    got = counts(bench, run([vsim, "+elf=" + elf, "+max-cycles=" + max_cycles], where), where)
                except XcheckError as e:
                    print(f"FAIL {e}")
                    fail += 1
                    continue
                task, _, variant = label.partition("/")
                for name in emu:
                    ok = got[name] == emu[name]
                    rows.append(f"{task},{variant or '-'},{arch},{name},{got[name]},{emu[name]}")
                    print(f"{'PASS' if ok else 'FAIL'} {label} {arch} {name}: instret {got[name]}"
                          f" {'=' if ok else '!='} {emu[name]} (rv32emu)")
                    fail += not ok
    compared = len(rows)
    if a.csv and not fail:
        os.makedirs(os.path.dirname(os.path.abspath(a.csv)), exist_ok=True)
        with open(a.csv, "w", encoding="utf-8") as f:
            f.write("task,variant,arch,benchmark,instret,rv32emu_instret\n" + "".join(r + "\n" for r in rows))
    if fail:
        print(f"bench-xcheck: FAIL ({fail} failed, {compared} counts compared)")
        return 1
    print(f"bench-xcheck: PASS ({compared} counts equal to rv32emu's on {len(models)} models,"
          f" {len(a.arch)} builds, the same measured code in each)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
