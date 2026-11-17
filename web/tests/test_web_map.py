# SPDX-License-Identifier: MIT
"""Tests of scripts/web-map.py: the course map lists every task and
exercise that exists in the tree and nothing else, and the views that exist.

    python3 -m unittest discover -s web/tests -p 'test_*.py'
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

WEB = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(WEB)
WEB_MAP = os.path.join(ROOT, "scripts", "web-map.py")

EXERCISES = """# Exercises

## Task 1: `1-alpha`

2 exercises, 3 blanks.

| Exercise | File | Line | Title |
|----------|------|------|-------|
| 1.1 | `1-alpha/src/main/scala/a/A.scala` | 10 | The first |
| 1.1 | `1-alpha/src/main/scala/a/A.scala` | 11 | The first |
| 1.2 | `1-alpha/src/main/scala/a/A.scala` | 20 | The second, with a &#124; bar |

## Task 2: `2-beta`

1 exercises, 1 blanks.

| Exercise | File | Line | Title |
|----------|------|------|-------|
| 2.1 | `2-beta/src/main/scala/b/B.scala` | 5 | The only one |
"""

BOOTCAMP_MAP = """# Map

## Notebooks used

| Notebook | Title | Used by |
|----------|-------|---------|
| 5.1 | The ISA | 1.1 |
| 5.4 | A Core | 1.2, 2.1 |

## T1: `1-alpha`

| Exercise | Title | File | Bootcamp notebook: section | Also cited |
|----------|-------|------|----------------------------|------------|
| 1.1 | The first | `A.scala` | 5.1: Formats | handout §T1.1 |
| 1.2 | The second | `A.scala` | 5.4: The datapath | handout §T1.2 |

## Bootcamp exercises that the tasks instantiate

| Bootcamp file | Notebook | Where this repository uses it |
|---------------|----------|-------------------------------|
| `Alu.scala` | 5.2 | T1 `Execute` |
| `Forward.scala` | 5.7 | not yet: T4 is in preparation |
"""

README = """<!-- SPDX-License-Identifier: MIT -->
# T{n}: Task {name}

The {name} task, in one paragraph
that has two lines.

## Goals

After this task you can:

- do one thing;
- do another thing, which is
  described on two lines.

## Test Suite

| Spec | Tests | What it checks |
|------|-------|----------------|
| `{spec}` | 2 | what it checks |
| `HeldBackSpec` | 1 | a spec that is not in the tree |
"""


def write(root, path, text):
    full = os.path.join(root, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as f:
        f.write(text)


def task(root, dirname, number, name, readme=True):
    if readme:
        write(root, f"{dirname}/README.md", README.format(n=number, name=name, spec=f"{name}Spec"))
    write(root, f"{dirname}/src/main/scala/x/{name}.scala",
          f"package x\nclass {name}(\n  a: Int = 1\n) extends Module {{\n}}\nclass {name}Io extends Bundle {{}}\n")
    write(root, f"{dirname}/src/test/scala/x/{name}Spec.scala", f"package x\nclass {name}Spec extends AnyFlatSpec {{}}\n")


def run_map(root, web):
    out = os.path.join(root, "map.json")
    p = subprocess.run([sys.executable, WEB_MAP, "--root", root, "--web", web, "--out", out],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
    document = None
    if p.returncode == 0:
        with open(out) as f:
            document = json.load(f)
    return p.returncode, document, p.stderr


class FixtureTree(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = tmp.name
        self.web = os.path.join(self.root, "web")
        write(self.root, "EXERCISES.md", EXERCISES)
        write(self.root, "docs/bootcamp-map.md", BOOTCAMP_MAP)
        write(self.root, "Makefile", "BOOTCAMP_URL    ?= https://example.org/bootcamp\n"
                                     "BOOTCAMP_COMMIT ?= " + "a" * 40 + "\n")
        task(self.root, "1-alpha", 1, "Alpha")
        task(self.root, "2-beta", 2, "Beta")
        write(self.web, "plain/index.js", "export async function mount() {}\nexport function unmount() {}\n")
        write(self.web, "ordered/index.js", "export async function mount() {}\nexport function unmount() {}\n")
        write(self.web, "ordered/view.json", json.dumps({"title": "Ordered", "order": 1, "tasks": ["T2"]}))
        write(self.web, "pertask/index.js", "export async function mount() {}\nexport function unmount() {}\n")
        write(self.web, "pertask/t1.json", "{}")
        write(self.web, "lib/bundle.js", "// not a view: no index.js\n")
        write(self.web, "notaview/view.json", json.dumps({"title": "No index.js"}))

    def build(self):
        status, document, err = run_map(self.root, self.web)
        self.assertEqual(status, 0, err)
        return document

    def test_lists_the_tasks_and_exercises_of_the_tree(self):
        m = self.build()
        self.assertEqual([t["id"] for t in m["tasks"]], ["T1", "T2"])
        t1, t2 = m["tasks"]
        self.assertEqual([x["id"] for x in t1["exercises"]], ["1.1", "1.2"])
        self.assertEqual(len(t1["exercises"][0]["blanks"]), 2)
        self.assertEqual(t1["exercises"][1]["title"], "The second, with a | bar")
        self.assertEqual([x["id"] for x in t2["exercises"]], ["2.1"])
        self.assertEqual(t1["title"], "Task Alpha")
        self.assertEqual(t1["summary"], "The Alpha task, in one paragraph that has two lines.")
        self.assertEqual(t1["goals"], ["do one thing;", "do another thing, which is described on two lines."])
        self.assertEqual([x["name"] for x in t1["modules"]], ["Alpha"])
        self.assertEqual([(x["name"], x["checks"]) for x in t1["tests"]], [("AlphaSpec", "what it checks")])
        self.assertEqual([n["id"] for n in t1["prerequisites"]["notebooks"]], ["5.1", "5.4"])
        self.assertEqual([f["file"] for f in t1["prerequisites"]["bootcamp_files"]], ["Alu.scala"])
        self.assertEqual(m["module5"]["repository"], {"url": "https://example.org/bootcamp", "commit": "a" * 40})

    def test_a_task_directory_added_and_removed(self):
        task(self.root, "3-gamma", 3, "Gamma")
        m = self.build()
        self.assertEqual([t["id"] for t in m["tasks"]], ["T1", "T2", "T3"])
        gamma = m["tasks"][2]
        self.assertEqual((gamma["dir"], gamma["exercises"]), ("3-gamma", []))
        shutil.rmtree(os.path.join(self.root, "3-gamma"))
        self.assertEqual([t["id"] for t in self.build()["tasks"]], ["T1", "T2"])

    def test_a_task_without_readme_is_listed_with_what_it_has(self):
        task(self.root, "5-delta", 5, "Delta", readme=False)
        delta = self.build()["tasks"][-1]
        self.assertEqual((delta["id"], delta["title"], delta["goals"], delta["readme"]), ("T5", "5-delta", [], None))
        self.assertEqual([x["name"] for x in delta["tests"]], ["DeltaSpec"])

    def test_names_that_are_no_task_directory_are_ignored(self):
        os.makedirs(os.path.join(self.root, "1_notatask"))
        os.makedirs(os.path.join(self.root, "common"))
        write(self.root, "4-file", "a file, not a directory\n")
        self.assertEqual([t["id"] for t in self.build()["tasks"]], ["T1", "T2"])

    def test_an_exercise_of_a_missing_task_is_an_error(self):
        shutil.rmtree(os.path.join(self.root, "2-beta"))
        status, _, err = run_map(self.root, self.web)
        self.assertEqual(status, 1)
        self.assertIn("2-beta", err)

    def test_two_directories_of_one_task_are_an_error(self):
        task(self.root, "1-other", 1, "Other")
        status, _, err = run_map(self.root, self.web)
        self.assertEqual(status, 1)
        self.assertIn("both task 1", err)

    def test_views_are_the_directories_with_index_js(self):
        m = self.build()
        self.assertEqual([(v["name"], v["title"], v["tasks"]) for v in m["views"]],
                         [("ordered", "Ordered", ["T2"]), ("pertask", "pertask", ["T1"]), ("plain", "plain", None)])
        self.assertEqual(m["tasks"][0]["views"], ["pertask", "plain"])
        self.assertEqual(m["tasks"][1]["views"], ["ordered", "plain"])
        shutil.rmtree(os.path.join(self.web, "plain"))
        self.assertEqual([v["name"] for v in self.build()["views"]], ["ordered", "pertask"])

    def test_the_output_depends_on_the_inputs_only(self):
        first = self.build()
        self.assertEqual(first, self.build())


class RealTree(unittest.TestCase):
    """The map of this repository's student tree against its directory listing and EXERCISES.md."""

    def setUp(self):
        self.tree = ROOT if os.path.isfile(os.path.join(ROOT, "EXERCISES.md")) else os.path.join(ROOT, "build", "public")
        if not os.path.isfile(os.path.join(self.tree, "EXERCISES.md")):
            self.fail(f"no EXERCISES.md in {self.tree}: run make -C web map first (it generates the public tree)")
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        out = os.path.join(tmp.name, "map.json")
        p = subprocess.run([sys.executable, WEB_MAP, "--root", self.tree, "--web", WEB, "--out", out],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        self.assertEqual(p.returncode, 0, p.stderr)
        with open(out) as f:
            self.map = json.load(f)

    def test_every_task_directory_and_nothing_else(self):
        dirs = sorted(n for n in os.listdir(self.tree)
                      if os.path.isdir(os.path.join(self.tree, n)) and n[:1].isdigit() and n[1:2] == "-")
        self.assertEqual([t["dir"] for t in self.map["tasks"]], dirs)

    def test_every_exercise_of_exercises_md_and_nothing_else(self):
        wanted = set()
        with open(os.path.join(self.tree, "EXERCISES.md")) as f:
            for line in f:
                cells = [c.strip() for c in line.strip().strip("|").split("|")]
                if line.startswith("| ") and cells[0][:1].isdigit():
                    wanted.add(cells[0])
        listed = [x["id"] for t in self.map["tasks"] for x in t["exercises"]]
        self.assertEqual(len(listed), len(set(listed)))
        self.assertEqual(set(listed), wanted)

    def test_views_are_the_view_directories_of_web(self):
        dirs = sorted(n for n in os.listdir(WEB) if os.path.isfile(os.path.join(WEB, n, "index.js")))
        self.assertEqual(sorted(v["name"] for v in self.map["views"]), dirs)


if __name__ == "__main__":
    unittest.main()
