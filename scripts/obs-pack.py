#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
"""Packs one run of the Verilator harness into an observation bundle (OBS-03):
a manifest.json and chunked data files that a viewer loads one window at a
time (docs/observation.md, "Bundles").

    obs-pack.py --trace RUN.obs --retire-log RUN.log [--uart-log RUN.uart]
                [--vga N:FRAME.ppm]... [--stores LO:HI] [--tests RESULTS.json] [--stamp TEXT]
                [--elf PROG.elf] [--chunk-cycles N] [--force] OUT_DIR

The parts must come from one run: the trace's trailer holds the SHA-256 of the
retire log, the UART log and the +vga-dump frames that the same run of the
harness wrote, and every part given must match it; the retire log's end line
must give the trace's cycle count, instret and ending, and its records must be
the trace's retire records cycle for cycle. --elf must hash to the trace's
elf_sha256, and the results of --tests must name the trace's commit, program
and retire log. Anything else is refused, with the reason, and no bundle is written.

RESULTS.json: {"commit": ..., "program": ..., "retire_log_sha256": ...,
"results": [{"name": ..., "result": "pass"|"fail", "detail": ...}]}, where
retire_log_sha256 is that of the run's retire log, which binds the results to
the run (sim/obs.mk writes one).

--stamp TEXT (scripts/student-id.py stamp: "id=... email=... head=... nonce=...")
records who made the bundle, from which commit, in the manifest's "stamp" (schema
1.2), with the SHA-256 of the stamp, the ELF and the trace together, so that a
copied bundle cannot carry another run's stamp unnoticed.

--stores LO:HI (hexadecimal byte addresses) packs stores.json: every record of
the whole run's retire log that stores to [LO, HI), as [cycle, pc, addr, bytes,
data], so that a view can find the store behind a VGA pixel written long
before the window (schema 1.1).
"""

import argparse
import hashlib
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from obslib import (  # noqa: E402
    BUNDLE_FORMAT,
    CONVENTIONS,
    ObsError,
    Trace,
    compare_retire,
    read_retire_log,
    schema_minor,
    sha256_file,
)


def parse_vga(text):
    frame, sep, path = text.partition(":")
    if not sep or not frame.isdigit() or int(frame) < 1 or not path:
        raise argparse.ArgumentTypeError(f"--vga takes N:<file.ppm>, not {text!r}")
    return int(frame), path


def parse_range(text):
    lo, sep, hi = text.partition(":")
    try:
        lo, hi = int(lo, 16), int(hi, 16)
    except ValueError:
        lo = hi = None
    if not sep or lo is None or not 0 <= lo < hi <= 1 << 32:
        raise argparse.ArgumentTypeError(f"--stores takes LO:HI in hexadecimal, LO < HI, not {text!r}")
    return lo, hi


def need(condition, message):
    if not condition:
        raise ObsError(message)


def write_file(out, name, data):
    path = os.path.join(out, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    return {"file": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


STAMP_KEYS = ("id", "email", "head", "nonce")


def stamp_of(text, elf_sha256, trace_sha256):
    """The manifest's stamp: the fields of `text` and the SHA-256 that binds them to the run."""
    fields = dict(w.split("=", 1) for w in text.split() if "=" in w)
    if sorted(fields) != sorted(STAMP_KEYS):
        raise ValueError(f"--stamp needs exactly {', '.join(STAMP_KEYS)}, not {text!r}")
    bound = "\n".join([*(f"{k}={fields[k]}" for k in STAMP_KEYS), elf_sha256, trace_sha256])
    return dict(fields, sha256=hashlib.sha256(bound.encode()).hexdigest())


def pack(args):
    trace = Trace(args.trace)
    h, t = trace.header, trace.trailer
    schema_minor(h.get("schema"), args.trace)
    outputs = t.get("outputs", {})
    end = t["end"]

    # every part must be one that this run of the harness wrote; the log is read once, for its
    # hash and its records
    first, count = h["first"], trace.count
    stores = (args.stores[0], args.stores[1], []) if args.stores else None
    log_records, log_end, log_hash = read_retire_log(args.retire_log, first, count, stores)
    need(outputs.get("retire_log") == log_hash,
         f"{args.retire_log} is not the retire log of the run of {args.trace}"
         + ("" if "retire_log" in outputs else " (that run wrote none: give it +retire-log)"))
    uart_hash = None
    if args.uart_log:
        uart_hash = sha256_file(args.uart_log)
        need(outputs.get("uart_log") == uart_hash,
             f"{args.uart_log} is not the UART log of the run of {args.trace}")
    for frame, path in args.vga:
        need(outputs.get(f"vga_dump:{frame}") == sha256_file(path),
             f"{path} is not frame {frame} of the run of {args.trace}")
    if args.elf:
        need(sha256_file(args.elf) == h["elf_sha256"], f"{args.elf} is not the ELF that {args.trace} ran")

    need(log_end == end,
         f"the end line of {args.retire_log} ({log_end}) is not the end of the run of {args.trace} ({end})")

    tests = None
    if args.tests:
        with open(args.tests) as f:
            results = json.load(f)
        need(results.get("commit") == h["commit"],
             f"{args.tests} is for commit {results.get('commit')}, the trace for {h['commit']}")
        need(results.get("program") == h["program"],
             f"{args.tests} is for program {results.get('program')}, the trace for {h['program']}")
        need(results.get("retire_log_sha256") == log_hash,
             f"the results of {args.tests} are not for this run (their retire_log_sha256 is not the log's)")
        tests = {"sha256": sha256_file(args.tests), "results": results.get("results")}

    need(args.force or not os.path.exists(args.out), f"{args.out} exists (--force replaces it)")
    # the bundle is made in a directory of its own and takes OUT_DIR's place only when it is whole
    out = args.out.rstrip("/") + ".partial"
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    try:
        write_bundle(args, out, trace, log_records, log_hash, uart_hash, tests, stores)
    except BaseException:
        shutil.rmtree(out, ignore_errors=True)
        raise
    shutil.rmtree(args.out, ignore_errors=True)
    os.rename(out, args.out)


def write_bundle(args, out, trace, log_records, log_hash, uart_hash, tests, stores):
    h, end = trace.header, trace.trailer["end"]
    first, count = h["first"], trace.count

    chunks = []
    batch = []
    compared = 0

    def flush():
        nonlocal compared
        if not batch:
            return
        compared += compare_retire(batch, log_records, args.trace, whole=False)
        data = json.dumps({"first": batch[0]["cycle"], "count": len(batch), "cycles": batch},
                          separators=(",", ":")).encode()
        entry = write_file(out, f"cycles/{batch[0]['cycle']:012d}.json", data)
        chunks.append(dict(entry, first=batch[0]["cycle"], count=len(batch)))
        batch.clear()

    for record in trace.cycles():
        batch.append(record)
        if len(batch) == args.chunk_cycles:
            flush()
    flush()
    need(chunks, f"{args.trace} has no cycle in its window (+obs-start is past the end of the run)")
    need(compared == len(log_records), f"{args.trace}: the retire log has records outside the trace")

    uart = None
    if args.uart_log:
        with open(args.uart_log, "rb") as f:
            uart = write_file(out, "uart.bin", f.read())
    vga = []
    frames = trace.trailer.get("frames", {})
    for frame, path in sorted(args.vga):
        with open(path, "rb") as f:
            entry = dict(write_file(out, f"vga/frame-{frame}.ppm", f.read()), frame=frame)
        if str(frame) in frames:  # traces of harnesses before schema 1.1 have no frame cycles
            entry["cycle"] = frames[str(frame)]
        vga.append(entry)
    stored = None
    if stores is not None:
        data = json.dumps({"lo": stores[0], "hi": stores[1], "stores": stores[2]}, separators=(",", ":")).encode()
        stored = dict(write_file(out, "stores.json", data), lo=stores[0], hi=stores[1], count=len(stores[2]))

    header = {k: h[k] for k in ("task", "variant", "commit", "top", "program", "elf_sha256", "schema",
                                "uart_divider", "ram_base", "ram_bytes")}
    header["conventions"] = CONVENTIONS
    manifest = {
        "format": BUNDLE_FORMAT,
        "schema": h["schema"],
        "header": header,
        "run": {"cycles": end["cycles"], "instret": end["instret"], "how": end["how"],
                "first": first, "count": count, "limit": h["limit"]},
        "groups": h["groups"],
        "chunks": chunks,
        "uart": uart,
        "vga": vga,
        "tests": tests,
        "sources": {"trace_sha256": trace.sha256, "retire_log_sha256": log_hash, "uart_log_sha256": uart_hash},
    }
    if stored is not None:  # an optional part (schema 1.1)
        manifest["stores"] = stored
    if args.stamp:  # an optional part (schema 1.2)
        manifest["stamp"] = stamp_of(args.stamp, h["elf_sha256"], trace.sha256)
    with open(os.path.join(out, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1)
        f.write("\n")
    size = sum(c["bytes"] for c in chunks)
    print(f"obs-pack: {args.out}: {count} cycles from {first} in {len(chunks)} chunks ({size} bytes), "
          f"{compared} retire records equal to the log, run {end['how']} after {end['cycles']} cycles")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--trace", required=True, help="the +obs-trace of the run")
    p.add_argument("--retire-log", required=True, help="the +retire-log of the same run")
    p.add_argument("--uart-log", help="the +uart-log of the same run")
    p.add_argument("--vga", type=parse_vga, action="append", default=[], help="N:<file>: frame N of +vga-dump")
    p.add_argument("--stores", type=parse_range, help="LO:HI: the run's stores to these addresses (hexadecimal)")
    p.add_argument("--tests", help="the test results of the model and program (JSON)")
    p.add_argument("--elf", help="the ELF that was run, to check its hash")
    p.add_argument("--stamp", help="id=... email=... head=... nonce=...: who made the run (scripts/student-id.py)")
    p.add_argument("--chunk-cycles", type=int, default=1024, help="cycles per data file (default 1024)")
    p.add_argument("--force", action="store_true", help="replace OUT_DIR if it exists")
    p.add_argument("out", help="the bundle directory to write")
    args = p.parse_args()
    if args.chunk_cycles < 1:
        p.error("--chunk-cycles must be at least 1")
    try:
        pack(args)
    except (ObsError, OSError, ValueError, KeyError) as e:
        print(f"obs-pack: refused: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
