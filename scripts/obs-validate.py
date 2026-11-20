#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
"""Validates an observation bundle (OBS-03) against the observation schema
(web/schema/obs-v1.json, OBS-01) and against the retire log of its run.

    obs-validate.py [--schema FILE] BUNDLE_DIR RETIRE_LOG
    obs-validate.py [--schema FILE] --no-retire-log BUNDLE_DIR

It checks that
  - the manifest, every data file and every cycle record satisfy the schema
    (a schema that uses a keyword this script does not implement is refused);
  - the schema versions agree: major 1, and no newer minor than the schema's;
  - every file of the manifest is there with its size and SHA-256;
  - the data files hold the window of the manifest, cycle after cycle, and
    every record has exactly the groups that the manifest lists;
  - with RETIRE_LOG: it is the log the bundle was packed from (its SHA-256),
    its counts hold (mark and end lines), its end line is the bundle's run,
    and every cycle of the window has the same retire record in the bundle as
    in the log (a record line equal character for character, or none in
    both).
--no-retire-log skips the last check and says so; a bundle without a cycle
is refused.
"""

import argparse
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from obslib import (  # noqa: E402
    ObsError,
    Schema,
    compare_retire,
    read_retire_log,
    schema_minor,
    sha256_file,
    window,
)

DEFAULT_SCHEMA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "web", "schema", "obs-v1.json")


def check_file(bundle, entry):
    path = os.path.join(bundle, entry["file"])
    if os.path.relpath(path, bundle).startswith(".."):
        raise ObsError(f"{entry['file']}: outside the bundle")
    if not os.path.isfile(path):
        raise ObsError(f"{entry['file']}: missing")
    if os.path.getsize(path) != entry["bytes"] or sha256_file(path) != entry["sha256"]:
        raise ObsError(f"{entry['file']}: not the file the manifest lists (size or SHA-256)")
    return path


def validate(args):
    schema = Schema(args.schema)
    bundle = args.bundle
    with open(os.path.join(bundle, "manifest.json")) as f:
        manifest = json.load(f)
    schema.validate(manifest, "#", "manifest")
    minor = schema_minor(manifest["schema"], "manifest")
    if minor > schema_minor(schema.version, args.schema):
        raise ObsError(f"the bundle has schema {manifest['schema']}, newer than {schema.version}: "
                       "update scripts and schema")
    if manifest["header"]["schema"] != manifest["schema"]:
        raise ObsError("the header and the manifest name different schema versions")

    run = manifest["run"]
    groups = set(manifest["groups"])
    if "retire" not in groups:
        raise ObsError("the groups do not list retire, which every bundle has")
    probe_groups = groups - {"retire"}
    expect = window(run["cycles"], run["first"], run["limit"])
    if run["count"] != expect or run["count"] == 0:
        raise ObsError(f"the window has {run['count']} cycles; the run and its limit give {expect}, "
                       "and a bundle must have at least one")

    # the log first, read once: each data file is compared with it as it is read
    log_records = None
    stored = manifest.get("stores")
    log_stores = (stored["lo"], stored["hi"], []) if stored else None
    if not args.no_retire_log:
        log_records, end, log_hash = read_retire_log(args.retire_log, run["first"], run["count"], log_stores)
        if log_hash != manifest["sources"]["retire_log_sha256"]:
            raise ObsError(f"{args.retire_log} is not the retire log the bundle was packed from")
        if (end["cycles"], end["instret"], end["how"]) != (run["cycles"], run["instret"], run["how"]):
            raise ObsError(f"the log ends with {end}, the bundle's run with "
                           f"cycles={run['cycles']} instret={run['instret']} {run['how']}")

    # the data files: the window, cycle after cycle, each record valid with exactly the groups
    # listed and, with the log, with the log's record of its cycle
    expect = run["first"]
    records = compared = 0
    for entry in manifest["chunks"]:
        with open(check_file(bundle, entry)) as f:
            chunk = json.load(f)
        schema.validate(chunk, "#/$defs/chunk", entry["file"])
        if (chunk["first"], chunk["count"], len(chunk["cycles"])) != (entry["first"], entry["count"], entry["count"]):
            raise ObsError(f"{entry['file']}: not the cycles the manifest lists")
        for i, record in enumerate(chunk["cycles"]):
            if record["cycle"] != expect:
                raise ObsError(f"{entry['file']}[{i}]: cycle {record['cycle']}, expected {expect}")
            present = set(record) - {"cycle", "retire"}
            if present != probe_groups:
                raise ObsError(f"{entry['file']}: cycle {expect} has groups {sorted(present)}, "
                               f"the manifest {sorted(probe_groups)}")
            records += "retire" in record
            expect += 1
        if log_records is not None:
            compared += compare_retire(chunk["cycles"], log_records, bundle, whole=False)
    if expect != run["first"] + run["count"]:
        raise ObsError(f"the data files end at cycle {expect}, the window at {run['first'] + run['count']}")
    # every cycle of the window has been compared, so this holds unless a log record is in no cycle
    if log_records is not None and compared != len(log_records):
        raise ObsError(f"the log has {len(log_records)} records in the window, the bundle {compared}")

    for entry in ([manifest["uart"]] if manifest["uart"] else []) + manifest["vga"]:
        check_file(bundle, entry)
    if stored:
        # the stores of the whole run to [lo, hi): with the log, exactly its records that store there
        with open(check_file(bundle, stored)) as f:
            data = json.load(f)
        schema.validate(data, "#/$defs/stores", stored["file"])
        if (data["lo"], data["hi"], len(data["stores"])) != (stored["lo"], stored["hi"], stored["count"]):
            raise ObsError(f"{stored['file']}: not the range and count the manifest lists")
        if not data["lo"] < data["hi"]:
            raise ObsError(f"{stored['file']}: the range {data['lo']:#x}..{data['hi']:#x} is empty")
        # what a reader may rely on without the log too: the log's order, the range, the fields' widths
        last = -1
        for i, (cycle, pc, addr, size, value) in enumerate(data["stores"]):
            where = f"{stored['file']}: store {i}"
            if not last <= cycle < run["cycles"]:
                raise ObsError(f"{where}: cycle {cycle} is not in order within the run's {run['cycles']} cycles")
            if not data["lo"] <= addr < data["hi"] or size not in (1, 2, 4) or addr % size:
                raise ObsError(f"{where}: a store of {size} bytes to {addr:#x}, outside the range or misaligned")
            if pc >= 1 << 32 or value >= 1 << (8 * size):
                raise ObsError(f"{where}: pc {pc:#x} or data {value:#x} wider than its field")
            last = cycle
        if log_stores is not None and data["stores"] != log_stores[2]:
            raise ObsError(f"{stored['file']}: not the stores of the retire log to that range")
    if manifest["uart"] and manifest["uart"]["sha256"] != manifest["sources"]["uart_log_sha256"]:
        raise ObsError("uart.bin is not the UART log the bundle was packed from")

    stamp = manifest.get("stamp")
    if stamp is not None:  # schema 1.2: bound to this run's ELF and trace
        keys = ("id", "email", "head", "nonce") + (("pred",) if "pred" in stamp else ())
        bound = "\n".join([*(f"{k}={stamp[k]}" for k in keys), manifest["header"]["elf_sha256"],
                           manifest["sources"]["trace_sha256"]])
        if hashlib.sha256(bound.encode()).hexdigest() != stamp["sha256"]:
            raise ObsError("the stamp is not the one of this run (its SHA-256 does not match the ELF and the trace)")
        print(f"obs-validate: {bundle}: made by {stamp['id']} ({stamp['email']}) at {stamp['head']}, nonce {stamp['nonce']}"
              + (f", predictions {stamp['pred']}" if "pred" in stamp else ""))

    summary = f"obs-validate: {bundle}: schema {manifest['schema']}, {run['count']} cycles from {run['first']}, "
    if log_records is None:
        print(summary + f"{records} retire records; NOT compared with a retire log (--no-retire-log)")
    else:
        print(summary + f"{compared} retire records equal to the log line for line, "
              f"cycles={run['cycles']} as its end line")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--schema", default=DEFAULT_SCHEMA, help="the schema (default: web/schema/obs-v1.json)")
    p.add_argument("--no-retire-log", action="store_true", help="do not compare with the retire log")
    p.add_argument("bundle", help="the bundle directory (with manifest.json)")
    p.add_argument("retire_log", nargs="?", help="the retire log of the bundle's run")
    args = p.parse_args()
    if bool(args.retire_log) == args.no_retire_log:
        p.error("give the retire log of the run, or --no-retire-log")
    try:
        validate(args)
    except (ObsError, OSError, ValueError, KeyError) as e:
        print(f"obs-validate: {args.bundle}: INVALID: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
