#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
"""The refusals of scripts/obs-pack.py and scripts/obs-validate.py (OBS-03),
on the outputs of two runs that `make obs` left in RUN_A and RUN_B (each with
run.obs, run.log, run.uart, tests.json and a valid bundle/):

    obs-tests.py RUN_A RUN_B

Every case changes one thing and expects a refusal that names it; the
unchanged bundle of RUN_A must validate before and after. Exits 0 when every
case behaves as expected, and prints one PASS or FAIL line per case.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PACK = os.path.join(ROOT, "scripts", "obs-pack.py")
VALIDATE = os.path.join(ROOT, "scripts", "obs-validate.py")
SCHEMA = os.path.join(ROOT, "web", "schema", "obs-v1.json")


def run(*args):
    p = subprocess.run([sys.executable, *args], capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


sys.path.insert(0, os.path.join(ROOT, "scripts"))
from obslib import ObsError, Schema  # noqa: E402
from obslib import sha256_file as sha  # noqa: E402


class Cases:
    def __init__(self, a, b, tmp):
        self.a, self.b, self.tmp = a, b, tmp
        self.failed = 0

    def file(self, run, name):
        return os.path.join(run, name)

    def elf(self, run):
        """The ELF that `make obs` copied into `run`, under the program's name."""
        return os.path.join(run, os.path.basename(run.rstrip("/")) + ".elf")

    def pack(self, trace=None, log=None, uart=None, tests=None, elf=None):
        out = os.path.join(self.tmp, "packed")
        args = [PACK, "--force", "--trace", trace or self.file(self.a, "run.obs"),
                "--retire-log", log or self.file(self.a, "run.log"),
                "--uart-log", uart or self.file(self.a, "run.uart")]
        if tests:
            args += ["--tests", tests]
        if elf:
            args += ["--elf", elf]
        return run(*args, out)

    def expect(self, name, result, refusal, says=""):
        """`refusal` None: the command must succeed (and print `says`); else it must fail and
        print `refusal`."""
        code, output = result
        ok = (code == 0 and says in output) if refusal is None else (code != 0 and refusal in output)
        print(f"{'PASS' if ok else 'FAIL'} obs-tests {name}" + ("" if ok else f": exit {code}: {output.strip()}"))
        self.failed += not ok

    def bundle_copy(self):
        dst = os.path.join(self.tmp, "bundle")
        shutil.rmtree(dst, ignore_errors=True)
        shutil.copytree(self.file(self.a, "bundle"), dst)
        return dst

    def edit_json(self, path, change):
        with open(path) as f:
            data = json.load(f)
        change(data)
        with open(path, "w") as f:
            json.dump(data, f, separators=(",", ":"))

    def rehash(self, bundle, entry_file):
        """Makes the manifest list `entry_file` as it is now (as a forger would)."""
        def fix(m):
            for e in m["chunks"]:
                if e["file"] == entry_file:
                    path = os.path.join(bundle, entry_file)
                    e["bytes"], e["sha256"] = os.path.getsize(path), sha(path)
        self.edit_json(os.path.join(bundle, "manifest.json"), fix)

    def all(self):
        a_log = self.file(self.a, "run.log")
        self.expect("bundle A validates", run(VALIDATE, self.file(self.a, "bundle"), a_log), None)
        self.expect("A packs again", self.pack(), None)

        # parts of two runs
        self.expect("retire log of run B", self.pack(log=self.file(self.b, "run.log")), "is not the retire log")
        self.expect("UART log of run B", self.pack(uart=self.file(self.b, "run.uart")), "is not the UART log")
        self.expect("ELF of run B", self.pack(elf=self.elf(self.b)), "is not the ELF")
        self.expect("tests of run B", self.pack(tests=self.file(self.b, "tests.json")), "is for program")
        # results of another run of the same program on the same commit (other input, other variant)
        other = os.path.join(self.tmp, "tests.json")
        with open(self.file(self.a, "tests.json")) as f:
            results = json.load(f)
        results["retire_log_sha256"] = sha(self.file(self.b, "run.log"))
        with open(other, "w") as f:
            json.dump(results, f)
        self.expect("tests of another run of A's program", self.pack(tests=other), "are not for this run")
        self.expect("bundle A with the log of run B", run(VALIDATE, self.file(self.a, "bundle"),
                                                          self.file(self.b, "run.log")), "is not the retire log")

        # a trace that was cut off: in the trailer, and in the records
        with open(self.file(self.a, "run.obs"), "rb") as f:
            data = f.read()
        for name, cut in (("trailer", 1), ("records", len(data) // 2)):
            path = os.path.join(self.tmp, f"cut-{name}.obs")
            with open(path, "wb") as f:
                f.write(data[: len(data) - cut])
            self.expect(f"trace cut in its {name}", self.pack(trace=path), "no trailer")
        # a record more than the trailer says (the trailer kept, a record duplicated)
        header_end = data.index(b"\n") + 1
        size = json.loads(data[:header_end])["record_bytes"]
        path = os.path.join(self.tmp, "extra.obs")
        with open(path, "wb") as f:
            f.write(data[:header_end + size] + data[header_end:])
        self.expect("trace with a record too many", self.pack(trace=path), "the trailer says")

        # a doctored retire record in a data file, with the manifest made to match: the line
        # comparison with the log must catch it
        bundle = self.bundle_copy()
        chunk = json.load(open(os.path.join(bundle, "manifest.json")))["chunks"][0]["file"]
        def doctor(c):
            for record in c["cycles"]:
                if "retire" in record:
                    record["retire"]["val"] ^= 1
                    return
        self.edit_json(os.path.join(bundle, chunk), doctor)
        self.expect("doctored record, manifest unchanged", run(VALIDATE, bundle, a_log), "not the file")
        self.rehash(bundle, chunk)
        self.expect("doctored record, manifest rehashed", run(VALIDATE, bundle, a_log), "the retire log pc=")
        # without the log it cannot be caught, and the validator must say that it did not compare
        self.expect("doctored record, without the log", run(VALIDATE, "--no-retire-log", bundle), None,
                    says="NOT compared")

        # a record that disappears from a cycle
        bundle = self.bundle_copy()
        def drop(c):
            for record in c["cycles"]:
                if "retire" in record:
                    del record["retire"]
                    return
        self.edit_json(os.path.join(bundle, chunk), drop)
        self.rehash(bundle, chunk)
        self.expect("dropped record", run(VALIDATE, bundle, a_log), "the bundle has no record")

        # a log whose end line does not match, with the manifest made to match its hash
        bundle = self.bundle_copy()
        log = os.path.join(self.tmp, "end.log")
        with open(a_log) as f:
            lines = f.read().splitlines()
        lines[-1] = lines[-1].replace("exit=0", "exit=1")
        with open(log, "w") as f:
            f.write("\n".join(lines) + "\n")
        self.edit_json(os.path.join(bundle, "manifest.json"),
                       lambda m: m["sources"].__setitem__("retire_log_sha256", sha(log)))
        self.expect("log with another end", run(VALIDATE, bundle, log), "the log ends with")

        # a log with a mark line that its cycles contradict
        lines = open(a_log).read().splitlines()
        lines.insert(1, "mark cycle=7 instret=7")
        with open(log, "w") as f:
            f.write("\n".join(lines) + "\n")
        self.edit_json(os.path.join(bundle, "manifest.json"),
                       lambda m: m["sources"].__setitem__("retire_log_sha256", sha(log)))
        self.expect("log with a wrong mark", run(VALIDATE, bundle, log), "mark says")

        # a data file missing, a value wider than its field, a group too many, a newer schema
        bundle = self.bundle_copy()
        os.remove(os.path.join(bundle, chunk))
        self.expect("missing data file", run(VALIDATE, bundle, a_log), "missing")
        bundle = self.bundle_copy()
        # every top from T1 to T5 has the uart group
        self.edit_json(os.path.join(bundle, chunk), lambda c: c["cycles"][0]["uart"].__setitem__("txCount", 16))
        self.rehash(bundle, chunk)
        self.expect("4-bit field of 16", run(VALIDATE, bundle, a_log), "is above 15")
        bundle = self.bundle_copy()
        # div: a group that no top drives together with uart but T2's; vga: one that only T5's does
        extra = "vga" if "div" in json.load(open(os.path.join(bundle, "manifest.json")))["groups"] else "div"
        fields = {"vga": ("hsync", "vsync", "active", "tick", "rgb", "x", "y"),
                  "div": ("reqValid", "reqReady", "op", "dividend", "divisor", "busy", "respValid", "result", "kill")}
        self.edit_json(os.path.join(bundle, chunk), lambda c: c["cycles"][0].__setitem__(
            extra, {k: 0 for k in fields[extra]}))
        self.rehash(bundle, chunk)
        self.expect("group the manifest does not list", run(VALIDATE, bundle, a_log), "has groups")
        bundle = self.bundle_copy()
        self.edit_json(os.path.join(bundle, "manifest.json"), lambda m: m.__setitem__("schema", "1.99"))
        self.expect("newer schema minor", run(VALIDATE, bundle, a_log), "newer than")
        bundle = self.bundle_copy()
        self.edit_json(os.path.join(bundle, "manifest.json"), lambda m: m.__setitem__("schema", "2.0"))
        self.expect("other schema major", run(VALIDATE, bundle, a_log), "does not match")

        self.stores()

        # a schema with a keyword the validator does not implement
        schema = os.path.join(self.tmp, "schema.json")
        with open(SCHEMA) as f:
            s = json.load(f)
        s["$defs"]["u5"]["multipleOf"] = 2
        with open(schema, "w") as f:
            json.dump(s, f)
        self.expect("schema keyword not implemented", run(VALIDATE, "--schema", schema,
                                                          self.file(self.a, "bundle"), a_log), "not implemented")
        self.schema_semantics()
        self.expect("bundle A still validates", run(VALIDATE, self.file(self.a, "bundle"), a_log), None)
        return self.failed

    def schema_semantics(self):
        """The subset validator's meaning of the keywords, where Python's types differ from JSON's."""
        cases = [  # (name, schema, value, valid)
            ("integer 1.0", {"type": "integer"}, 1.0, True),
            ("integer 1.5", {"type": "integer"}, 1.5, False),
            ("integer true", {"type": "integer"}, True, False),
            ("const [1] for [true]", {"const": [1]}, [True], False),
            ("const {a: 0} for {a: false}", {"const": {"a": 0}}, {"a": False}, False),
            ("enum [1] for 1.0", {"enum": [1]}, 1.0, True),
            ("required on a non-object", {"required": ["a"]}, 3, True),
            ("maximum on a string", {"maximum": 1}, "7", True),
        ]
        path = os.path.join(self.tmp, "mini.json")
        for name, schema, value, valid in cases:
            with open(path, "w") as f:
                json.dump(dict(schema, **{"x-version": "1.0"}), f)
            try:
                Schema(path).validate(value)
                got = True
            except ObsError:
                got = False
            ok = got == valid
            print(f"{'PASS' if ok else 'FAIL'} obs-tests schema: {name} is {'valid' if valid else 'invalid'}")
            self.failed += not ok


def stores_cases(self):
    """--stores (schema 1.1): run B's stores to the UART's registers, packed and checked against
    its log; a doctored or dropped store is refused, and so is a range that is not one."""
    b_log = self.file(self.b, "run.log")
    out = os.path.join(self.tmp, "stores")
    args = [PACK, "--force", "--trace", self.file(self.b, "run.obs"), "--retire-log", b_log,
            "--uart-log", self.file(self.b, "run.uart")]
    self.expect("--stores packs", run(*args, "--stores", "10000000:10001000", out), None)
    manifest = json.load(open(os.path.join(out, "manifest.json")))
    count = manifest.get("stores", {}).get("count", 0)
    ok = count > 0
    print(f"{'PASS' if ok else 'FAIL'} obs-tests --stores holds the run's {count} stores to the UART")
    self.failed += not ok
    self.expect("--stores validates", run(VALIDATE, out, b_log), None)
    self.expect("--stores of an empty range", run(*args, "--stores", "10001000:10001000", out), "LO < HI")
    self.expect("--stores that is no range", run(*args, "--stores", "uart", out), "hexadecimal")
    for name, change in (("doctored", lambda d: d["stores"][0].__setitem__(4, d["stores"][0][4] ^ 1)),
                         ("dropped", lambda d: d["stores"].pop())):
        bundle = os.path.join(self.tmp, "stores-" + name)
        shutil.rmtree(bundle, ignore_errors=True)
        shutil.copytree(out, bundle)
        path = os.path.join(bundle, "stores.json")
        self.edit_json(path, change)
        def fix(m, path=path):
            m["stores"].update(bytes=os.path.getsize(path), sha256=sha(path))
        self.edit_json(os.path.join(bundle, "manifest.json"), fix)
        if name == "dropped":
            self.edit_json(os.path.join(bundle, "manifest.json"), lambda m: m["stores"].__setitem__("count", count - 1))
        self.expect(f"{name} store, manifest rehashed", run(VALIDATE, bundle, b_log), "not the stores of the retire log")
    # what a reader relies on without the log: the order of the log, the range, the widths
    for name, change, says in (
            ("a store out of order", lambda d: d["stores"].insert(0, d["stores"].pop()), "not in order"),
            ("a store outside the range", lambda d: d["stores"][0].__setitem__(2, d["hi"]), "outside the range"),
            ("a byte store of a word", lambda d: d["stores"][0].__setitem__(3, 1) or d["stores"][0].__setitem__(4, 0x100),
             "wider than its field")):
        bundle = os.path.join(self.tmp, "stores-check")
        shutil.rmtree(bundle, ignore_errors=True)
        shutil.copytree(out, bundle)
        path = os.path.join(bundle, "stores.json")
        self.edit_json(path, change)
        self.edit_json(os.path.join(bundle, "manifest.json"),
                       lambda m, path=path: m["stores"].update(bytes=os.path.getsize(path), sha256=sha(path)))
        self.expect(f"{name}, without the log", run(VALIDATE, "--no-retire-log", bundle), says)


Cases.stores = stores_cases


def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    with tempfile.TemporaryDirectory() as tmp:
        failed = Cases(sys.argv[1], sys.argv[2], tmp).all()
    print(f"obs-tests: {'all cases behaved as expected' if not failed else f'{failed} case(s) FAILED'}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
