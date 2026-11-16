# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
"""What scripts/obs-pack.py and scripts/obs-validate.py share (OBS-03): the
+obs-trace reader, the retire log reader, and the subset of JSON Schema that
web/schema/obs-v1.json uses. Python 3 standard library only.

docs/observation.md defines the formats; sim/obs.h and sim/main.cpp write them.
"""

import hashlib
import json
import mmap
import re
import struct

TRACE_FORMAT = "mycpu-obs-trace"
TRACE_VERSION = 1
BUNDLE_FORMAT = "mycpu-obs-bundle"
TRAILER_MAGIC = b"OBSEND01"
SCHEMA_MAJOR = 1
CONVENTIONS = {
    "edge": "rising",
    "sample": "after-start-edge",
    "cycle0": "first-after-reset",
    "uart_bit": "uart_divider",
    "pixel_enable": "vga.tick",
}


class ObsError(Exception):
    """An input that cannot be used; the message says why."""


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def window(cycles, first, limit):
    """The cycles that a trace of a run of `cycles` cycles has, from `first` on,
    at most `limit` of them (0: no limit): +obs-start and +obs-cycles."""
    count = max(0, cycles - first)
    return min(count, limit) if limit else count


def schema_minor(version, what):
    """The minor number of schema version `version` (major must be 1)."""
    m = re.fullmatch(r"(\d+)\.(\d+)", version if isinstance(version, str) else "")
    if not m or int(m.group(1)) != SCHEMA_MAJOR:
        raise ObsError(f"{what}: schema version {version!r} is not {SCHEMA_MAJOR}.x")
    return int(m.group(2))


# ---------------------------------------------------------------------------
# +obs-trace


class Trace:
    """An +obs-trace file: `header` and `trailer` (dicts), and the records.

    Refuses a file that the harness did not write to its end: no trailer, a
    record area that is not whole records, or a record count other than the
    trailer's and the window's.
    """

    def __init__(self, path):
        # mapped, not read: a trace of a whole run has hundreds of MB
        with open(path, "rb") as f:
            try:
                data = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
            except ValueError:
                raise ObsError(f"{path}: empty, not an +obs-trace") from None
        self.path = path
        self.sha256 = hashlib.sha256(data).hexdigest()
        newline = data.find(b"\n")
        if newline < 0 or data[:1] != b"{":
            raise ObsError(f"{path}: not an +obs-trace (no header line)")
        try:
            self.header = json.loads(data[:newline])
        except ValueError as e:
            raise ObsError(f"{path}: bad header: {e}") from None
        h = self.header
        if h.get("format") != TRACE_FORMAT or h.get("version") != TRACE_VERSION:
            raise ObsError(f"{path}: not an +obs-trace of version {TRACE_VERSION}")
        if len(data) < newline + 1 + 16 or data[len(data) - 8 :] != TRAILER_MAGIC:
            raise ObsError(f"{path}: no trailer: the trace was cut off, or its run did not end")
        length = struct.unpack("<Q", data[len(data) - 16 : len(data) - 8])[0]
        start = len(data) - 16 - length
        if start < newline + 1:
            raise ObsError(f"{path}: bad trailer length")
        try:
            self.trailer = json.loads(data[start : len(data) - 16])
        except ValueError as e:
            raise ObsError(f"{path}: bad trailer: {e}") from None
        self._check_layout()
        body = memoryview(data)[newline + 1 : start]
        size = h["record_bytes"]
        if len(body) % size:
            raise ObsError(f"{path}: {len(body)} bytes of records are not whole records of {size} bytes")
        self.count = len(body) // size
        t = self.trailer
        if not isinstance(t.get("end"), dict):
            raise ObsError(f"{path}: the run has no end (the harness did not finish it)")
        if t.get("records") != self.count:
            raise ObsError(f"{path}: {self.count} records, the trailer says {t.get('records')}")
        expect = window(t["end"]["cycles"], h["first"], h["limit"])
        if self.count != expect:
            raise ObsError(f"{path}: {self.count} records for a window of {expect} cycles")
        self._body = body

    def _check_layout(self):
        h = self.header
        offset = 0
        fmt = "<"
        for f in h.get("fields", []):
            bits = f.get("bits")
            want = 1 if bits <= 8 else 2 if bits <= 16 else 4
            if not 1 <= bits <= 32 or f.get("bytes") != want or f.get("offset") != offset:
                raise ObsError(f"{self.path}: bad layout of field {f.get('name')}")
            fmt += {1: "B", 2: "H", 4: "I"}[want]
            offset += want
        if offset != h.get("record_bytes") or not h.get("fields"):
            raise ObsError(f"{self.path}: the fields do not make a record of {h.get('record_bytes')} bytes")
        self._struct = struct.Struct(fmt)
        self.names = [f["name"] for f in h["fields"]]

    def cycles(self):
        """Each record as the schema's cycle object, in order."""
        first = self.header["first"]
        names = self.names
        for i, values in enumerate(self._struct.iter_unpack(self._body)):
            yield cycle_record(first + i, dict(zip(names, values)))


def cycle_record(cycle, flat):
    """The schema's cycle object from the fields of one trace record
    (`group.field` -> value)."""
    record = {"cycle": cycle}
    retire = {}
    for name, value in flat.items():
        group, _, rest = name.partition(".")
        if group == "retire":
            retire[rest] = value
            continue
        node = record.setdefault(group, {})
        *path, leaf = rest.split(".")
        for p in path:
            node = node.setdefault(p, {})
        node[leaf] = value
    if retire.get("valid"):
        r = {k: retire[k] for k in ("pc", "inst", "next", "rd", "val")}
        if retire["mem.valid"]:
            r["mem"] = {k: retire["mem." + k] for k in ("write", "addr", "bytes", "data")}
        if retire["csr.valid"]:
            r["csr"] = {"addr": retire["csr.addr"], "wdata": retire["csr.wdata"]}
        if retire["trap.valid"]:
            r["trap"] = {"cause": retire["trap.cause"], "tval": retire["trap.tval"]}
        r["halt"] = retire["halt"]
        r["mip"] = retire["mip"]
        record["retire"] = r
    return record


# ---------------------------------------------------------------------------
# The retire log (version 2, sim/main.cpp; mycpu.common.RetireLog reads it too)

_RECORD = re.compile(
    r"pc=[0-9a-f]{8} inst=[0-9a-f]{8} next=[0-9a-f]{8} rd=\d{1,2} val=[0-9a-f]{8} "
    r"mem=(-|[rw]:[0-9a-f]{8}:[124]:[0-9a-f]{8}) csr=(-|[0-9a-f]{3}:[0-9a-f]{8}) "
    r"trap=(-|[0-9a-f]{8}:[0-9a-f]{8}) halt=\d{1,9} mip=(-|[0-9a-f]{8})"
)
_IDLE = re.compile(r"idle ([1-9]\d{0,17})")
_MARK = re.compile(r"mark cycle=(\d{1,18}) instret=(\d{1,18})")
_END = re.compile(r"end cycles=(\d{1,18}) instret=(\d{1,18}) ((?:exit|halt|stop)=\S+)")


def read_retire_log(path, first, count, stores=None):
    """Reads a whole retire log, once, and checks its counts, as
    RetireLog.cycles does: the cycles and instret at every `mark` line and at
    the `end` line, which must be there and be the last line. A record line in
    the window [first, first + count) must have the format of sim/main.cpp;
    one outside it is only counted (a log has a line per instruction of the
    whole run, and the window is what is compared).

    Returns (records, end, sha256): `records` maps each cycle of the window
    that has a record to its line, `end` is a dict with `cycles`, `instret` and
    `how`, and `sha256` is that of the file.

    `stores`, if given, is (lo, hi, out): every record of the whole run, in the
    window or not, that stores to a byte address in [lo, hi) is appended to the
    list `out` as [cycle, pc, addr, bytes, data] (the line must then have the
    format of sim/main.cpp too).
    """
    records = {}
    cycle = 0
    instret = 0
    end = None
    last = first + count
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for number, raw in enumerate(f, 1):
            digest.update(raw)
            if end is not None:
                raise ObsError(f"{path}:{number}: a line after the end line")
            if raw.startswith(b"pc="):
                if first <= cycle < last:
                    line = raw.rstrip(b"\n").decode("ascii", "replace")
                    if not _RECORD.fullmatch(line):
                        raise ObsError(f"{path}:{number}: not a retire-log line: {line[:80]}")
                    records[cycle] = line
                if stores is not None and b" mem=w:" in raw:
                    line = raw.rstrip(b"\n").decode("ascii", "replace")
                    if not (m := _RECORD.fullmatch(line)):
                        raise ObsError(f"{path}:{number}: not a retire-log line: {line[:80]}")
                    _, addr, size, data = m.group(1).split(":")
                    if stores[0] <= int(addr, 16) < stores[1]:
                        stores[2].append([cycle, int(line[3:11], 16), int(addr, 16), int(size), int(data, 16)])
                instret += b" trap=- " in raw
                cycle += 1
                continue
            line = raw.rstrip(b"\n").decode("ascii", "replace")
            where = f"{path}:{number}"
            if (m := _IDLE.fullmatch(line)) is not None:
                cycle += int(m.group(1))
            elif (m := _MARK.fullmatch(line)) is not None:
                if (int(m.group(1)), int(m.group(2))) != (cycle, instret):
                    raise ObsError(f"{where}: mark says cycle={m.group(1)} instret={m.group(2)}, "
                                   f"the log has {cycle} and {instret}")
            elif (m := _END.fullmatch(line)) is not None:
                end = {"cycles": int(m.group(1)), "instret": int(m.group(2)), "how": m.group(3)}
                if (end["cycles"], end["instret"]) != (cycle, instret):
                    raise ObsError(f"{where}: end says cycles={end['cycles']} instret={end['instret']}, "
                                   f"the log has {cycle} and {instret}")
            else:
                raise ObsError(f"{where}: not a retire-log line: {line[:80]}")
    if end is None:
        raise ObsError(f"{path}: no end line: the log was cut off")
    return records, end, digest.hexdigest()


def retire_line(r):
    """The retire log's record line for the schema's retire object `r`."""
    mem = "-"
    if "mem" in r:
        m = r["mem"]
        mem = f"{'w' if m['write'] else 'r'}:{m['addr']:08x}:{m['bytes']}:{m['data']:08x}"
    csr = f"{r['csr']['addr']:03x}:{r['csr']['wdata']:08x}" if "csr" in r else "-"
    trap = f"{r['trap']['cause']:08x}:{r['trap']['tval']:08x}" if "trap" in r else "-"
    mip = f"{r['mip']:08x}" if r["mip"] else "-"
    return (f"pc={r['pc']:08x} inst={r['inst']:08x} next={r['next']:08x} rd={r['rd']} "
            f"val={r['val']:08x} mem={mem} csr={csr} trap={trap} halt={r['halt']} mip={mip}")


def compare_retire(cycles, log_records, what, whole=True):
    """Checks that the retire objects of `cycles` (cycle objects) are the log's
    record lines of the same cycles, and, if `cycles` is the whole window,
    that the log has no record in a cycle outside them. Returns the number of
    records compared."""
    compared = 0
    seen = set()
    for c in cycles:
        n = c["cycle"]
        seen.add(n)
        mine = retire_line(c["retire"]) if "retire" in c else None
        theirs = log_records.get(n)
        if mine != theirs:
            raise ObsError(f"{what}: cycle {n}: the bundle has {mine or 'no record'}, "
                           f"the retire log {theirs or 'no record'}")
        compared += mine is not None
    missing = sorted(set(log_records) - seen) if whole else []
    if missing:
        raise ObsError(f"{what}: the retire log has a record in cycle {missing[0]}, which the bundle lacks")
    return compared


# ---------------------------------------------------------------------------
# JSON Schema, the subset that web/schema/obs-v1.json uses (draft 2020-12
# meaning). A schema that uses any other keyword is refused, so that a check
# this code does not make cannot pass silently.

_ANNOTATIONS = {"$schema", "$id", "$defs", "$comment", "title", "description", "default", "examples"}
_KEYWORDS = {"$ref", "type", "properties", "required", "additionalProperties", "items", "enum", "const",
             "minimum", "maximum", "minLength", "pattern", "minItems", "maxItems"}
_TYPES = {
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
    "string": lambda v: isinstance(v, str),
    # JSON has one number type: 1.0 is an integer, as JSON Schema says
    "integer": lambda v: (isinstance(v, int) and not isinstance(v, bool)) or (isinstance(v, float) and v.is_integer()),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "boolean": lambda v: isinstance(v, bool),
    "null": lambda v: v is None,
}


class Schema:
    def __init__(self, path):
        with open(path) as f:
            self.root = json.load(f)
        self._resolved = {}  # $ref -> schema, as every field of every cycle names one
        self.version = self.root.get("x-version")
        schema_minor(self.version, path)
        self._check(self.root, "#")

    def _check(self, node, where):
        if isinstance(node, bool):
            return
        if not isinstance(node, dict):
            raise ObsError(f"schema {where}: not a schema")
        for key, value in node.items():
            if key.startswith("x-") or key in _ANNOTATIONS:
                if key == "$defs":
                    for name, sub in value.items():
                        self._check(sub, f"{where}/$defs/{name}")
                continue
            if key not in _KEYWORDS:
                raise ObsError(f"schema {where}: keyword {key} is not implemented by scripts/obslib.py")
            if key == "properties":
                for name, sub in value.items():
                    self._check(sub, f"{where}/properties/{name}")
            elif key in ("items", "additionalProperties"):
                self._check(value, f"{where}/{key}")
            elif key == "$ref":
                self.resolve(value)
            elif key == "type":
                for t in value if isinstance(value, list) else [value]:
                    if t not in _TYPES:
                        raise ObsError(f"schema {where}: unknown type {t}")

    def field_widths(self):
        """{`group.field`: x-width} of every field of a cycle record (`core.pc`, `retire.mem.addr`)."""
        widths = {}

        def walk(node, name):
            if "$ref" in node and "x-width" not in node:
                node = self.resolve(node["$ref"])
            if "x-width" in node:
                widths[name] = node["x-width"]
            for key, sub in node.get("properties", {}).items():
                walk(sub, f"{name}.{key}")

        for group, sub in self.resolve("#/$defs/cycle")["properties"].items():
            if group != "cycle":
                walk(sub, group)
        return widths

    def resolve(self, ref):
        if ref in self._resolved:
            return self._resolved[ref]
        if not ref.startswith("#/"):
            raise ObsError(f"schema: only local references are implemented: {ref}")
        node = self.root
        for part in ref[2:].split("/"):
            if not isinstance(node, dict) or part not in node:
                raise ObsError(f"schema: {ref} does not resolve")
            node = node[part]
        self._resolved[ref] = node
        return node

    def validate(self, value, ref="#", where="$"):
        """Raises ObsError at the first place where `value` does not satisfy
        the schema at `ref`."""
        self._validate(value, self.resolve(ref) if ref != "#" else self.root, where)

    def _validate(self, v, s, where):
        if s is True:
            return
        if s is False:
            raise ObsError(f"{where}: not allowed")
        if "$ref" in s:
            self._validate(v, self.resolve(s["$ref"]), where)
        if "type" in s:
            types = s["type"] if isinstance(s["type"], list) else [s["type"]]
            if not any(_TYPES[t](v) for t in types):
                raise ObsError(f"{where}: {json.dumps(v)[:60]} is not of type {'/'.join(types)}")
        if "const" in s and not _equal(v, s["const"]):
            raise ObsError(f"{where}: {json.dumps(v)[:60]} is not {json.dumps(s['const'])}")
        if "enum" in s and not any(_equal(v, e) for e in s["enum"]):
            raise ObsError(f"{where}: {json.dumps(v)[:60]} is not one of {json.dumps(s['enum'])}")
        if _TYPES["number"](v):
            if "minimum" in s and v < s["minimum"]:
                raise ObsError(f"{where}: {v} is below {s['minimum']}")
            if "maximum" in s and v > s["maximum"]:
                raise ObsError(f"{where}: {v} is above {s['maximum']}")
        elif isinstance(v, str):
            if "minLength" in s and len(v) < s["minLength"]:
                raise ObsError(f"{where}: shorter than {s['minLength']}")
            if "pattern" in s and not re.search(s["pattern"], v):
                raise ObsError(f"{where}: {v!r} does not match {s['pattern']}")
        elif isinstance(v, list):
            if "minItems" in s and len(v) < s["minItems"]:
                raise ObsError(f"{where}: fewer than {s['minItems']} items")
            if "maxItems" in s and len(v) > s["maxItems"]:
                raise ObsError(f"{where}: more than {s['maxItems']} items")
            if "items" in s:
                for i, item in enumerate(v):
                    self._validate(item, s["items"], f"{where}[{i}]")
        elif isinstance(v, dict):
            for key in s.get("required", ()):
                if key not in v:
                    raise ObsError(f"{where}: {key} is missing")
            props = s.get("properties", {})
            extra = s.get("additionalProperties", True)
            for key, item in v.items():
                if key in props:
                    self._validate(item, props[key], f"{where}.{key}")
                else:
                    self._validate(item, extra, f"{where}.{key}")


def _equal(a, b):
    """JSON equality, at every depth: true and 1 are different values, 1 and 1.0 the same."""
    if isinstance(a, bool) or isinstance(b, bool):
        return type(a) is type(b) and a == b
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(_equal(x, y) for x, y in zip(a, b))
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(_equal(a[k], b[k]) for k in a)
    if isinstance(a, (list, dict)) or isinstance(b, (list, dict)):
        return False
    return a == b
