#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Unit tests of scripts/quiz-grade.py and scripts/quiz-check.py: parsing, tiers, points, headers."""

import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


grade = load("quiz_grade", "quiz-grade.py")
check = load("quiz_check", "quiz-check.py")

OUTPUT = """quiz: mulu ok
quiz: qmul15 FAILED 3 of 100 checks, first: qmul15(1, 2) = 0, not 0
perf: quiz quiz-arith mulu calls=192 insn=1000 cycles=1500
perf: quiz quiz-arith qmul15 calls=192 insn=2000 cycles=2500
quiz: quiz-arith FAILED
"""


class ParseTest(unittest.TestCase):
    def test_routines_and_costs(self):
        ok, cost, complete = grade.parse(OUTPUT)
        self.assertEqual(ok, {"mulu": True, "qmul15": False})
        self.assertEqual(cost[("quiz-arith", "mulu")], (192, 1000, 1500))
        self.assertFalse(complete)

    def test_a_complete_program(self):
        _, _, complete = grade.parse("quiz: mulu ok\nquiz: quiz-arith complete\n")
        self.assertTrue(complete)


class TierTest(unittest.TestCase):
    targets = {("t4-forward", "quiz-arith", "mulu"): (2000, 1000), ("insn", "quiz-arith", "mulu"): (900, 500)}

    def test_cycles_of_a_listed_model(self):
        self.assertEqual(grade.tiers("t4-forward", "quiz-arith", "mulu", 0, 2000, self.targets)[:2], (True, False))
        self.assertEqual(grade.tiers("t4-forward", "quiz-arith", "mulu", 0, 1050, self.targets)[:2], (True, True))
        self.assertEqual(grade.tiers("t4-forward", "quiz-arith", "mulu", 0, 2001, self.targets)[:2], (False, False))

    def test_an_unlisted_model_is_graded_on_instructions(self):
        t = grade.tiers("t3-div66", "quiz-arith", "mulu", 480, 99999, self.targets)
        self.assertEqual(t[:2], (True, True))
        self.assertEqual(t[3], "insn")

    def test_no_target_earns_no_tier(self):
        self.assertEqual(grade.tiers("t1", "quiz-sine", "sinq", 1, 1, {})[:2], (False, False))


class PointsTest(unittest.TestCase):
    def routine(self, correct, base, best):
        return {"correct": correct, "base": base, "best": best}

    def test_tiers_add_up(self):
        self.assertEqual(grade.points(self.routine(False, True, True), 10), 0)
        self.assertEqual(grade.points(self.routine(True, False, False), 10), 5)
        self.assertEqual(grade.points(self.routine(True, True, False), 10), 7.5)
        self.assertEqual(grade.points(self.routine(True, True, True), 10), 10)


class ScoreToolTest(unittest.TestCase):
    def test_a_missing_routines_file_is_named(self):
        import subprocess
        with tempfile.TemporaryDirectory() as d:
            p = subprocess.run(["python3", os.path.join(HERE, "quiz-score.py"), d, "--models", "t4-stall"],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("routines.S is missing", p.stdout)


class HeaderTest(unittest.TestCase):
    def test_declared_routines_keep_the_order_of_the_header(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "quiz-x.h"), "w", encoding="utf-8") as f:
                f.write("// quiz_commented(a)\nuint32_t quiz_zeta(uint32_t a);\nint quiz_alpha(void);\nquiz_pair_t quiz_zeta(int);\n")
            self.assertEqual(check.declared(d), ["quiz_zeta", "quiz_alpha"])

    def test_extern_data_of_the_header_is_provided(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "quiz-x.h"), "w", encoding="utf-8") as f:
                f.write("extern uint8_t quiz_states[16 * 130];\nextern int quiz_other(int);\n// extern int quiz_note[3];\n")
            self.assertEqual(check.provided(d), {"quiz_states"})

    def test_the_real_headers_declare_routines(self):
        programs = os.path.join(HERE, "..", "software", "programs")
        for name in sorted(os.listdir(programs)):
            if name.startswith("quiz-"):
                self.assertTrue(check.declared(os.path.join(programs, name)), name)


if __name__ == "__main__":
    unittest.main()
