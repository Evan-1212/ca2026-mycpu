#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
"""Builds the course map of the web observation interface (OBS-04):
web/map.json, which the home page renders (web/README.md, "Course map").

    web-map.py [--root DIR] [--web DIR] [--out FILE] [--bootcamp DIR]

Nothing in the map is written by hand. It comes from

  --root   a tree as students get it: the task directories `<n>-<name>/` that
           exist in it, each task's README.md (title, first paragraph, Goals,
           the Test Suite table), its Chisel sources (`class X ... extends
           Module`) and spec files (`src/test/**/*Spec.scala`), EXERCISES.md
           (the exercises and their blanks) and docs/bootcamp-map.md (the
           notebooks and the bootcamp files each task builds on), and the
           top Makefile (the pinned chisel-bootcamp URL and commit);
  --web    the site: every `<view>/index.js` is a view (its optional
           view.json gives a title, an order and the tasks it serves; without
           a task list, a view serves the tasks that have a `t<n>.json` in its
           directory, or every task when there is none), and examples/index.json
           lists the shipped example bundles;
  --bootcamp  a chisel-bootcamp clone, only to name each notebook's file.

A task directory without a README is listed with what the tree has. An
exercise of EXERCISES.md whose task directory does not exist is an error, and
so is a task directory whose name repeats a task number. The output depends on
the input files only: two runs give identical bytes.
"""

import argparse
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TASK_DIR = re.compile(r"^([1-9])-[a-z0-9][a-z0-9-]*$")
TASK_HEADING = re.compile(r"^## Task ([1-9]): `([^`]+)`\s*$")
EXERCISE_ROW = re.compile(r"^\|\s*([1-9])\.([0-9]+)\s*\|\s*`([^`]+)`\s*\|\s*([0-9]+)\s*\|\s*(.*?)\s*\|\s*$")
TITLE = re.compile(r"^# (?:T[1-9]: )?(.+?)\s*$")
# a class whose parameter list spans lines still counts: `class CPU(\n ...\n) extends Module {`
MODULE = re.compile(r"^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)\b[^{]*?\bextends\s+(?:Module|RawModule)\b", re.M)
SPEC = re.compile(r"^\s*class\s+([A-Za-z_][A-Za-z0-9_]*Spec)\b", re.M)
NOTEBOOK = re.compile(r"\b([1-5])\.([0-9]+)\b")


class Error(Exception):
    """The map cannot be built; the message says why."""


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def markdown_sections(text):
    """{heading text: [lines]} of the `## ` sections of a Markdown file."""
    sections = {}
    current = None
    fence = False
    for line in text.splitlines():
        if line.startswith("```"):
            fence = not fence
        if not fence and line.startswith("## "):
            current = line[3:].strip()
            sections[current] = []
        elif current is not None:
            sections[current].append(line)
    return sections


def table_rows(lines):
    """The cells of each row of the first Markdown table in lines, without the header and rule."""
    rows = []
    started = False
    for line in lines:
        if line.startswith("|"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if not started:
                started = True
                continue
            if all(set(c) <= set("-: ") for c in cells):
                continue
            rows.append(cells)
        elif started:
            break
    return rows


def bullets(lines):
    """The items of the first bullet list in lines, continuation lines joined."""
    items = []
    for line in lines:
        if line.startswith("- "):
            items.append(line[2:].strip())
        elif items and line.startswith("  ") and line.strip():
            items[-1] += " " + line.strip()
        elif items and not line.strip():
            break
    return items


def first_paragraph(text):
    """The first paragraph after the title, as one line."""
    lines = []
    seen_title = False
    for line in text.splitlines():
        if not seen_title:
            seen_title = line.startswith("# ")
            continue
        if not line.strip():
            if lines:
                break
            continue
        if line.startswith("#"):
            break
        lines.append(line.strip())
    return " ".join(lines)


def strip_code(cell):
    return cell.replace("`", "")


def plain(text):
    """Markdown inline markup as plain text: links keep their text, emphasis and code marks go."""
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    return re.sub(r"\*\*|`", "", text)


def notebook_ids(text):
    return sorted({f"{a}.{b}" for a, b in NOTEBOOK.findall(text)}, key=lambda n: tuple(int(x) for x in n.split(".")))


def read_bootcamp_map(path):
    """(notebooks {id: title}, task rows {task: [(exercise, notebook text)]}, instantiated files [(file, notebook, use)])."""
    if not os.path.isfile(path):
        return {}, {}, []
    sections = markdown_sections(read(path))
    notebooks = {}
    for cells in table_rows(sections.get("Notebooks used", [])):
        if len(cells) >= 2:
            notebooks[cells[0]] = cells[1]
    per_task = {}
    for heading, lines in sections.items():
        m = re.match(r"^(T[1-9]): `[^`]+`$", heading)
        if m:
            per_task[m.group(1)] = [(cells[0], cells[3]) for cells in table_rows(lines) if len(cells) >= 4]
    files = []
    for cells in table_rows(sections.get("Bootcamp exercises that the tasks instantiate", [])):
        if len(cells) >= 3:
            files.append((strip_code(cells[0]), cells[1], cells[2]))
    return notebooks, per_task, files


def read_exercises(path):
    """{task number: [{"id", "title", "blanks": [{"file", "line"}]}]} of EXERCISES.md."""
    if not os.path.isfile(path):
        raise Error(f"{path} not found: the map lists the exercises of the public tree's EXERCISES.md")
    tasks = {}
    current = None
    for number, line in enumerate(read(path).splitlines(), 1):
        m = TASK_HEADING.match(line)
        if m:
            current = (int(m.group(1)), m.group(2))
            tasks[current] = []
            continue
        m = EXERCISE_ROW.match(line)
        if m:
            if current is None or int(m.group(1)) != current[0]:
                raise Error(f"{path}:{number}: exercise {m.group(1)}.{m.group(2)} outside its task's section")
            ident = f"{m.group(1)}.{m.group(2)}"
            rows = tasks[current]
            if not rows or rows[-1]["id"] != ident:
                rows.append({"id": ident, "title": m.group(5).replace("&#124;", "|"), "blanks": []})
            rows[-1]["blanks"].append({"file": m.group(3), "line": int(m.group(4))})
    return tasks


def scala_classes(root, pattern, regex):
    found = []
    for path in sorted(glob.glob(os.path.join(root, pattern), recursive=True)):
        rel = os.path.relpath(path, os.path.dirname(root.rstrip("/"))).replace(os.sep, "/")
        for name in regex.findall(read(path)):
            found.append({"name": name, "file": rel})
    return found


def notebook_files(bootcamp):
    """{notebook id: file name} of a chisel-bootcamp clone (`5.4_single_cycle.ipynb`)."""
    files = {}
    if bootcamp and os.path.isdir(bootcamp):
        for name in sorted(os.listdir(bootcamp)):
            m = re.match(r"^([0-9]+\.[0-9]+)_.*\.ipynb$", name)
            if m:
                files[m.group(1)] = name
    return files


def bootcamp_pin(root):
    """{"url", "commit"} of the chisel-bootcamp clone that the top Makefile pins, or None."""
    path = os.path.join(root, "Makefile")
    if not os.path.isfile(path):
        return None
    text = read(path)
    url = re.search(r"^BOOTCAMP_URL\s*\??=\s*(https://\S+?)(?:\.git)?\s*$", text, re.M)
    commit = re.search(r"^BOOTCAMP_COMMIT\s*\??=\s*([0-9a-f]{40})\s*$", text, re.M)
    return {"url": url.group(1), "commit": commit.group(1)} if url and commit else None


def read_views(web):
    views = []
    for index in sorted(glob.glob(os.path.join(web, "*", "index.js"))):
        directory = os.path.dirname(index)
        name = os.path.basename(directory)
        meta = {}
        meta_path = os.path.join(directory, "view.json")
        if os.path.isfile(meta_path):
            with open(meta_path, encoding="utf-8") as f:
                meta = json.load(f)
        tasks = meta.get("tasks")
        if tasks is None:
            per_task = sorted(f"T{m.group(1)}" for m in (re.match(r"^t([1-9])\.json$", n) for n in os.listdir(directory)) if m)
            tasks = per_task or None
        views.append({"name": name, "title": meta.get("title", name), "order": meta.get("order", 100),
                      "summary": meta.get("summary", ""), "tasks": tasks})
    return sorted(views, key=lambda v: (v["order"], v["name"]))


def read_examples(web):
    path = os.path.join(web, "examples", "index.json")
    if not os.path.isfile(path):
        return []
    with open(path, encoding="utf-8") as f:
        return [{k: e[k] for k in ("id", "task", "program", "title", "bundle", "listing", "first", "count")}
                for e in json.load(f)["examples"]]


def task_entry(root, dirname, exercises, bootcamp_rows, bootcamp_files, notebooks, views, examples):
    number = int(TASK_DIR.match(dirname).group(1))
    ident = f"T{number}"
    base = os.path.join(root, dirname)
    readme_path = os.path.join(base, "README.md")
    entry = {"id": ident, "dir": dirname, "title": dirname, "summary": "", "readme": None, "goals": []}
    tests_described = {}
    if os.path.isfile(readme_path):
        text = read(readme_path)
        sections = markdown_sections(text)
        m = TITLE.match(next((line for line in text.splitlines() if line.startswith("# ")), ""))
        if m:
            entry["title"] = m.group(1)
        entry["summary"] = plain(first_paragraph(text))
        entry["readme"] = f"{dirname}/README.md"
        entry["goals"] = [plain(g) for g in bullets(sections.get("Goals", []))]
        for cells in table_rows(sections.get("Test Suite", [])):
            if len(cells) >= 2:  # | Spec | [Tests |] What it checks |
                for name in re.findall(r"`([^`]+)`", cells[0]) or [strip_code(cells[0])]:
                    tests_described[name] = plain(cells[-1])
    used = notebook_ids(" ".join(text for _, text in bootcamp_rows.get(ident, [])))
    entry["prerequisites"] = {
        "notebooks": [{"id": n, "title": notebooks.get(n, "")} for n in used],
        "bootcamp_files": [{"file": f, "notebook": nb} for f, nb, use in bootcamp_files
                           if re.search(r"\b" + ident + r"\b", use)],
    }
    entry["modules"] = scala_classes(base, "src/main/scala/**/*.scala", MODULE)
    specs = scala_classes(base, "src/test/scala/**/*Spec.scala", SPEC)
    for spec in specs:
        spec["checks"] = tests_described.get(spec["name"], "")
    entry["tests"] = specs
    entry["exercises"] = exercises
    entry["views"] = [v["name"] for v in views if v["tasks"] is None or ident in v["tasks"]]
    entry["examples"] = [e["id"] for e in examples if e["task"] == ident]
    return entry


def build(root, web, bootcamp):
    names = sorted(n for n in os.listdir(root) if TASK_DIR.match(n) and os.path.isdir(os.path.join(root, n)))
    numbers = {}
    for n in names:
        number = int(TASK_DIR.match(n).group(1))
        if number in numbers:
            raise Error(f"{numbers[number]} and {n} are both task {number}")
        numbers[number] = n
    exercises = read_exercises(os.path.join(root, "EXERCISES.md"))
    for number, dirname in sorted(exercises):
        if numbers.get(number) != dirname:
            raise Error(f"EXERCISES.md lists task {number} as `{dirname}`, which is not a task directory of {root}")
    notebooks, bootcamp_rows, bootcamp_files = read_bootcamp_map(os.path.join(root, "docs", "bootcamp-map.md"))
    files = notebook_files(bootcamp)
    views = read_views(web)
    examples = read_examples(web)
    tasks = [task_entry(root, numbers[n], exercises.get((n, numbers[n]), []), bootcamp_rows, bootcamp_files,
                        notebooks, views, examples) for n in sorted(numbers)]
    module5 = {
        "id": "M5",
        "title": "chisel-bootcamp Module 5",
        "source": "docs/bootcamp-map.md" if notebooks else None,
        "repository": bootcamp_pin(root),
        "notebooks": [{"id": n, "title": t, "file": files.get(n)} for n, t in notebooks.items()],
        "bootcamp_files": [{"file": f, "notebook": nb, "use": use} for f, nb, use in bootcamp_files],
    }
    return {"format": "mycpu-web-map", "version": 1, "module5": module5, "tasks": tasks,
            "views": views, "examples": examples}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--root", default=os.path.join(HERE, ".."), help="the tree to describe (default: this one)")
    p.add_argument("--web", default=os.path.join(HERE, "..", "web"), help="the site (default: web/ of this tree)")
    p.add_argument("--out", help="the map to write (default: <web>/map.json)")
    p.add_argument("--bootcamp", help="a chisel-bootcamp clone, to name the notebook files")
    args = p.parse_args()
    try:
        document = build(args.root, args.web, args.bootcamp)
    except (Error, OSError, ValueError, KeyError) as e:
        print(f"web-map: {e}", file=sys.stderr)
        return 1
    out = args.out or os.path.join(args.web, "map.json")
    data = json.dumps(document, indent=1, sort_keys=True) + "\n"
    with open(out + ".new", "w", encoding="utf-8") as f:
        f.write(data)
    os.replace(out + ".new", out)
    exercises = sum(len(t["exercises"]) for t in document["tasks"])
    print(f"web-map: {out}: {len(document['tasks'])} tasks ({', '.join(t['id'] for t in document['tasks'])}), "
          f"{exercises} exercises, {len(document['views'])} views, {len(document['examples'])} examples")
    return 0


if __name__ == "__main__":
    sys.exit(main())
