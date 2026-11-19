#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Unit tests of scripts/grade.py: the scoring and the hygiene scan."""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import grade  # noqa: E402


def write(root, rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


class ScoreTest(unittest.TestCase):
    def test_total_is_100(self):
        self.assertEqual(sum(grade.POINTS.values()), 100)

    def test_a_check_with_partial_credit(self):
        r = grade.Result("quiz", False, earned=12.5)
        self.assertEqual(r.points, 12.5)
        self.assertEqual(grade.score([r]), (12.5, 25))
        self.assertIn("partial", grade.report([r]))

    def test_failed_check_scores_zero(self):
        r = grade.Result("task3", False)
        self.assertEqual(r.points, 0)

    def test_score_counts_the_selected_checks_only(self):
        results = [grade.Result("deps", True), grade.Result("task1", False)]
        self.assertEqual(grade.score(results), (5, 17))

    def test_report_shows_the_score(self):
        text = grade.report([grade.Result("hygiene", True), grade.Result("task2", False)])
        self.assertIn("Score: 5/17", text)
        self.assertIn("FAIL", text)


class HygieneTest(unittest.TestCase):
    def scan(self, rel, text):
        with tempfile.TemporaryDirectory() as root:
            write(root, rel, text)
            return grade.scan_hygiene(root)

    def test_clean_tree(self):
        self.assertEqual(self.scan("1-single-cycle/src/test/scala/A.scala", 'it should "work" in {}\n'), [])

    def test_ignored_spec(self):
        found = self.scan("1-single-cycle/src/test/scala/A.scala", 'ignore should "work" in {}\n')
        self.assertEqual(len(found), 1)
        self.assertIn("A.scala:1", found[0])

    def test_bare_pending(self):
        self.assertEqual(len(self.scan("2-rv32im/src/test/scala/A.scala", "  pending\n")), 1)

    def test_pending_as_a_name_is_fine(self):
        self.assertEqual(self.scan("2-rv32im/src/test/scala/A.scala", "assert(stats.pending(bit) >= 1)\n"), [])

    def test_commented_skip_is_fine(self):
        self.assertEqual(self.scan("2-rv32im/src/test/scala/A.scala", "// ignore should be gone\n"), [])

    def test_warning_flag_in_a_task_makefile(self):
        self.assertEqual(len(self.scan("5-soc/Makefile", "VFLAGS += -Wno-fatal\n")), 1)


if __name__ == "__main__":
    unittest.main()
