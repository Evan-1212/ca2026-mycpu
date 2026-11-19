# SPDX-License-Identifier: MIT
"""Tests of scripts/bootcamp-notebooks.py."""

import json
import os
import subprocess
import sys
import tempfile
import unittest

TOOL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bootcamp-notebooks.py")


def notebook(*cells):
    return {"cells": [{"cell_type": t, "source": s} for t, s in cells], "nbformat": 4}


class NotebookScanTest(unittest.TestCase):
    def run_tool(self, files):
        with tempfile.TemporaryDirectory() as d:
            for name, nb in files.items():
                with open(os.path.join(d, name), "w", encoding="utf-8") as f:
                    json.dump(nb, f)
            return subprocess.run([sys.executable, TOOL, d], capture_output=True, text=True)

    def test_a_blank_code_cell_is_named(self):
        p = self.run_tool({"2.1_first_module.ipynb": notebook(("code", "val x = 1"), ("code", ["simulate(???) {\n", "}"]))})
        self.assertEqual(p.returncode, 1)
        self.assertIn("2.1_first_module.ipynb: cell 1", p.stdout)

    def test_comments_and_markdown_are_not_blanks(self):
        p = self.run_tool({
            "3.6_types.ipynb": notebook(("code", "val y = 2 // was ???"), ("markdown", "the solution replaces ???")),
            "5.9_debug_and_verify.ipynb": notebook(("code", "def program: String = \"nop\"")),
        })
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("2 notebooks, no blank cell", p.stdout)

    def test_modules_0_and_1_are_not_read(self):
        p = self.run_tool({"1_intro_to_scala.ipynb": notebook(("code", "???")), "2.1_first_module.ipynb": notebook(("code", "1"))})
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)

    def test_no_notebook_is_an_error(self):
        self.assertEqual(self.run_tool({}).returncode, 3)


if __name__ == "__main__":
    unittest.main()
