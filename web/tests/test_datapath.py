# SPDX-License-Identifier: MIT
"""Checks of the datapath descriptions web/datapath/t<n>.json
against the schema and the tree: every bound field exists with the width the
wire states, every Chisel binding names a file of the tree that defines the
module, every test named is a spec of the task, every wire end is a port of
a component, and no description holds code (structure and bindings only).
"""

import glob
import json
import os
import re
import sys
import unittest

WEB = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(WEB)
sys.path.insert(0, os.path.join(ROOT, "scripts"))
from obslib import Schema  # noqa: E402
TASK_DIRS = {"T1": "1-single-cycle", "T2": "2-rv32im"}
SIDES = {"left", "right", "top", "bottom"}
# text of a description: names, labels and sentences; an operator of Chisel or Scala in it is code
CODE = re.compile(r":=|<>|===|=/=|&&|\|\||<<|>>|\bMux\b|\bwhen\s*\(|\bUInt\b|\bBool\(|=>")


def strings(node):
    if isinstance(node, str):
        yield node
    elif isinstance(node, dict):
        for k, v in node.items():
            yield k
            yield from strings(v)
    elif isinstance(node, list):
        for v in node:
            yield from strings(v)


class Descriptions(unittest.TestCase):
    def setUp(self):
        self.fields = Schema(os.path.join(WEB, "schema", "obs-v1.json")).field_widths()
        self.paths = sorted(glob.glob(os.path.join(WEB, "datapath", "t[0-9].json")))
        self.assertEqual([os.path.basename(p) for p in self.paths], ["t1.json", "t2.json"])

    def each(self):
        for path in self.paths:
            with open(path) as f:
                yield os.path.basename(path), json.load(f)

    def test_fields_and_widths(self):
        for name, d in self.each():
            for c in d["components"]:
                for field in (f for row in c["rows"] for f in row):
                    self.assertIn(field, self.fields, f"{name}: {c['id']}")
                if "alert" in c:
                    self.assertIn(c["alert"]["field"], self.fields)
            for w in d["wires"]:
                if "field" in w:
                    self.assertIn(w["field"], self.fields, f"{name}: {w['id']}")
                    self.assertEqual(w["bits"], self.fields[w["field"]], f"{name}: {w['id']}")

    def test_ports_and_ids(self):
        for name, d in self.each():
            ids = [c["id"] for c in d["components"]] + [w["id"] for w in d["wires"]]
            self.assertEqual(len(ids), len(set(ids)), name)
            comps = {c["id"] for c in d["components"]}
            for w in d["wires"]:
                for end in (w["from"], w["to"]):
                    comp, side, at = end.split(".", 2)
                    self.assertIn(comp, comps, f"{name}: {w['id']}")
                    self.assertIn(side, SIDES)
                    self.assertTrue(0 <= float(at) <= 1, end)

    def test_chisel_bindings_and_tests(self):
        for name, d in self.each():
            task_dir = TASK_DIRS[d["task"]]
            specs = {os.path.basename(p)[: -len(".scala")]
                     for p in glob.glob(os.path.join(ROOT, task_dir, "src", "test", "**", "*Spec.scala"), recursive=True)}
            for c in d["components"]:
                if "chisel" in c:
                    path = os.path.join(ROOT, c["chisel"]["file"])
                    self.assertTrue(os.path.isfile(path), f"{name}: {c['id']}: {path}")
                    with open(path) as f:
                        self.assertRegex(f.read(), r"\bclass\s+" + c["chisel"]["module"] + r"\b")
                self.assertTrue("chisel" in c or "bootcamp" in c, f"{name}: {c['id']} names no module")
                for spec in c["tests"]:
                    self.assertIn(spec, specs, f"{name}: {c['id']}")

    def test_t2_adds_the_divider_the_multiplier_and_the_stall(self):
        d = dict(self.each())["t2.json"]
        comps = {c["id"]: c for c in d["components"]}
        self.assertIn("div.busy", comps["divider"]["rows"][1])
        self.assertTrue(comps["multiplier"].get("concept"))
        self.assertEqual(comps["fetch"]["alert"]["field"], "div.busy")

    def test_no_code_in_the_descriptions(self):
        for name, d in self.each():
            for text in strings(d):
                self.assertIsNone(CODE.search(text), f"{name}: {text!r}")


if __name__ == "__main__":
    unittest.main()
