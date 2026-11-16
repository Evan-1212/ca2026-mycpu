#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
"""More test results for an observation bundle of a VGA program (OBS-10),
for sim/obs.mk's OBS_CHECKS: the checks of sim/vga.mk on the frames that the
run dumped into RUN_DIR (frame-<N>.ppm), as JSON objects separated by commas.

    obs-vga-checks.py [--golden N:NAME]... [--decode N]... [--expect TEXT] [--golden-dir DIR] RUN_DIR

--golden N:NAME   frame N equals the golden frame DIR/NAME.ppm.gz byte for byte
                  (result "golden:N", as `vga-frames.sh same` compares them)
--decode N        the QR decoder (scripts/qr-decode.py, through uv as sim/vga.mk
                  runs it) reads TEXT from frame N (result "qr-decode:N", what it
                  read in "detail"), as `vga-frames.sh decode` checks it
--expect TEXT     the text that every --decode frame must hold (required with --decode)

Exit code 0 when it could run the checks, whatever they found (a failed check is
a result), 2 when it could not.
"""

import argparse
import gzip
import json
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DECODE = os.environ.get("QR_DECODE", "uv run --no-project --with zxing-cpp==3.1.1 --with pillow python "
                        + os.path.join(ROOT, "scripts", "qr-decode.py")).split()


def frame_arg(text):
    n, sep, name = text.partition(":")
    if not sep or not n.isdigit() or not name:
        raise argparse.ArgumentTypeError(f"--golden takes N:NAME, not {text!r}")
    return int(n), name


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--golden", type=frame_arg, action="append", default=[])
    p.add_argument("--decode", type=int, action="append", default=[])
    p.add_argument("--expect")
    p.add_argument("--golden-dir", default=os.path.join(ROOT, "sim", "tests", "golden"))
    p.add_argument("run_dir")
    args = p.parse_args()
    if args.decode and args.expect is None:
        p.error("--decode needs --expect TEXT")
    results = []
    for n, name in args.golden:
        path = os.path.join(args.run_dir, f"frame-{n}.ppm")
        golden = os.path.join(args.golden_dir, f"{name}.ppm.gz")
        try:
            with open(path, "rb") as f:
                got = f.read()
            with gzip.open(golden) as f:
                want = f.read()
        except OSError as e:
            print(f"obs-vga-checks: {e}", file=sys.stderr)
            return 2
        same = got == want
        results.append({"name": f"golden:{n}", "result": "pass" if same else "fail",
                        "detail": f"{name}.ppm.gz {'equal' if same else 'differs'}"})
    if args.decode:
        paths = [os.path.join(args.run_dir, f"frame-{n}.ppm") for n in args.decode]
        proc = subprocess.run(DECODE + paths, capture_output=True, text=True)
        if proc.returncode == 2:
            print(f"obs-vga-checks: the decoder could not run: {proc.stderr.strip()}", file=sys.stderr)
            return 2
        lines = proc.stdout.split("\n")
        for k, n in enumerate(args.decode):
            text = lines[k] if k < len(lines) else ""
            results.append({"name": f"qr-decode:{n}", "result": "pass" if text == args.expect else "fail", "detail": text})
    print(",".join(json.dumps(r, separators=(",", ":")) for r in results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
