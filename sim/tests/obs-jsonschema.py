#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
"""A CI check of the subset validator of scripts/obslib.py: validates the
manifest and every data file of each bundle given with the `jsonschema`
library (draft 2020-12), which must agree with scripts/obs-validate.py.

    uv run --with jsonschema python3 sim/tests/obs-jsonschema.py BUNDLE_DIR...

(`make check-obs-jsonschema`). The validator itself needs no package; this
check is for CI only.
"""

import json
import os
import sys

import jsonschema

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def main():
    with open(os.path.join(ROOT, "web", "schema", "obs-v1.json")) as f:
        schema = json.load(f)
    jsonschema.Draft202012Validator.check_schema(schema)
    manifest_check = jsonschema.Draft202012Validator(schema)
    chunk_check = jsonschema.Draft202012Validator({**schema, "$ref": "#/$defs/chunk"})
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    for bundle in sys.argv[1:]:
        with open(os.path.join(bundle, "manifest.json")) as f:
            manifest = json.load(f)
        manifest_check.validate(manifest)
        for entry in manifest["chunks"]:
            with open(os.path.join(bundle, entry["file"])) as f:
                chunk_check.validate(json.load(f))
        print(f"obs-jsonschema: {bundle}: the manifest and {len(manifest['chunks'])} data files are valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
