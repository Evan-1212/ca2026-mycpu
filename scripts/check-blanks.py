#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""List the exercise blanks that remain in a source tree.

Usage: scripts/check-blanks.py [<tree>] [--exercises <EXERCISES.md>] [--tasks 1,2]

A blank is a Scala `?` token standing where an expression or a block of
statements belongs. It is counted only when it is a token of its own, outside
strings, character literals and comments, and not used as an operator: `a ? b`,
`x_?`, `??`, `???`, '?' and "why?" are not blanks, while the `?` inside the
`${ ? }` of an interpolated string is one. Every blank belongs to the exercise
named by the nearest `[CA26: Exercise <task>.<n>]` comment above it.

The script prints file:line and the exercise of every blank, per task, and
compares the number of blanks of every exercise in every file with the table in
EXERCISES.md (line numbers are not compared: they move when you edit). It
exits with status 1 when they differ or a blank has no exercise comment above
it, and with status 2 when it cannot run (no EXERCISES.md, unreadable tree, a
source that is not UTF-8).

With --tasks, only the listed tasks are compared (the others may be solved).
"""

import argparse
import bisect
import collections
import os
import re
import sys
import unicodedata

Token = collections.namedtuple("Token", "kind text start end")

# Characters that form Scala operator identifiers (Scala Language Specification, 1.1).
OPCHARS = frozenset("!#%&*+-/:<=>?@\\^|~")
KEYWORDS = frozenset(
    "abstract case catch class def do else extends false final finally for forSome if implicit import lazy "
    "match new null object override package private protected return sealed super this throw trait try true "
    "type val var while with yield".split()
)
# Keywords that end an operand, as an identifier does: `this ? b` is an operator call.
OPERAND_KEYWORDS = frozenset({"false", "null", "super", "this", "true", "type"})
CONTROL_KEYWORDS = frozenset({"if", "while", "for"})

EXERCISE_ID = r"[1-9]\.[1-9][0-9]*"
ID_RE = re.compile(EXERCISE_ID + r"\Z")
HEADER_RE = re.compile(r"\[CA26: Exercise ([^\]\n]*)\]")
# What may stand before a header on its comment line: `// [CA26: Exercise 1.2] <title>`, or `/*` or `*`.
HEADING_PREFIX_RE = re.compile(r"[ \t]*(?:/\*+|//+|\*+)?[ \t]*\Z")
ROW_RE = re.compile(r"^\|\s*(" + EXERCISE_ID + r")\s*\|\s*`([^`]+)`\s*\|\s*([0-9]+)\s*\|")
CHAR_RE = re.compile(r"'(?:\\u+[0-9a-fA-F]{4}|\\[0-7]{1,3}|\\.|[^'\\\n])'")
SCALA_SUFFIXES = (".scala", ".sc")
SKIP_DIRS = frozenset({".git", "externals", "build", "target", "work"})


def _is_opchar(c):
    if c in OPCHARS:
        return True
    return ord(c) > 127 and unicodedata.category(c) in ("Sm", "So")


def _is_ident_start(c):
    return c == "_" or c == "$" or c.isalpha()


def _is_ident_part(c):
    return c == "_" or c == "$" or c.isalnum()


def _operator_end(text, i, plain):
    """Return the index after the operator characters from i on; in code, a comment ends them."""
    n = len(text)
    while i < n and _is_opchar(text[i]) and (plain or not text.startswith(("//", "/*"), i)):
        i += 1
    return i


def _skip_block_comment(text, i):
    """Return the index after the block comment that starts at i. Scala block comments nest."""
    depth = 0
    n = len(text)
    while i < n:
        if text.startswith("/*", i):
            depth += 1
            i += 2
        elif text.startswith("*/", i):
            depth -= 1
            i += 2
            if depth == 0:
                return i
        else:
            i += 1
    return n


def _scan_string(text, i, interpolated):
    """Yield the tokens of the string literal whose opening quote is at i; return the index after it.

    The code inside every `${...}` of an interpolated string is tokenized like
    any other code. The text around it is yielded as string tokens: the piece
    before a `${` ends with these two characters and the next one starts with
    the closing `}`.
    """
    n = len(text)
    piece = i
    triple = text.startswith('"""', i)
    i += 3 if triple else 1
    while i < n:
        c = text[i]
        if interpolated and c == "$":
            if text.startswith("${", i):
                yield Token("string", text[piece : i + 2], piece, i + 2)
                piece = yield from _scan(text, i + 2, False, nested=True)
                i = piece + 1
            else:
                i += 2 if text.startswith("$$", i) else 1
        elif triple:
            if text.startswith('"""', i):
                i += 3
                while i < n and text[i] == '"':  # """a"""" ends with a quote inside the string
                    i += 1
                break
            i += 1
        elif c == "\\":
            i += 2
        elif c == '"':
            i += 1
            break
        elif c == "\n":  # unterminated: a single-quoted string cannot span lines
            break
        else:
            i += 1
    i = min(i, n)
    if i > piece:
        yield Token("string", text[piece:i], piece, i)
    return i


def _scan(text, i, plain, nested=False):
    """Yield the tokens from index i on.

    With nested, the code of a `${...}` is scanned: the generator stops at the
    `}` that closes it and returns the index of that brace.
    """
    n = len(text)
    depth = 0
    while i < n:
        c = text[i]
        if c.isspace():
            i += 1
            continue
        start = i
        if not plain:
            if text.startswith("//", i):
                end = text.find("\n", i)
                i = n if end < 0 else end
                if text[i - 1 : i] == "\r":
                    i -= 1
                yield Token("comment", text[start:i], start, i)
                continue
            if text.startswith("/*", i):
                i = _skip_block_comment(text, i)
                yield Token("comment", text[start:i], start, i)
                continue
            if c == '"':
                i = yield from _scan_string(text, i, start > 0 and _is_ident_part(text[start - 1]))
                continue
            if c == "'":
                m = CHAR_RE.match(text, i)
                if m:
                    i = m.end()
                    yield Token("char", text[start:i], start, i)
                    continue
            if c == "`":
                end = text.find("`", i + 1)
                line_end = text.find("\n", i)
                if end > 0 and (line_end < 0 or end < line_end):
                    i = end + 1
                    yield Token("ident", text[start:i], start, i)
                    continue
        if _is_ident_start(c):
            i += 1
            while i < n and _is_ident_part(text[i]):
                i += 1
            if text[i - 1] == "_":  # an operator suffix belongs to the identifier: `x_?`, `unary_!`
                i = _operator_end(text, i, plain)
            yield Token("ident", text[start:i], start, i)
        elif c.isdigit():
            i += 1
            while i < n and _is_ident_part(text[i]):
                i += 1
            yield Token("number", text[start:i], start, i)
        elif _is_opchar(c):
            i = _operator_end(text, i + 1, plain)
            yield Token("op", text[start:i], start, i)
        else:
            if nested and c == "{":
                depth += 1
            elif nested and c == "}":
                if not depth:
                    return start
                depth -= 1
            i += 1
            yield Token("punct", c, start, i)
    return n


def lex(text, plain=False):
    """Tokenize Scala source.

    Yields Token(kind, text, start, end) with kind one of comment, string, char,
    ident, number, op, punct. Whitespace is dropped. With plain=True, comments,
    strings and character literals are not recognized and their contents are
    tokenized like code (used to search prose and non-Scala files).
    """
    return _scan(text, 0, plain)


def code_tokens(text):
    """The tokens of Scala source without its comments."""
    return [t for t in lex(text) if t.kind != "comment"]


class Lines:
    """Maps character offsets of a text to 1-based line numbers."""

    def __init__(self, text):
        self.starts = [0] + [m.end() for m in re.finditer("\n", text)]

    def line(self, offset):
        return bisect.bisect_right(self.starts, offset)

    def offset(self, line):
        return self.starts[line - 1]


def ends_operand(token):
    """True when an operator may follow the token: after `x`, `1`, `"s"` or `)`, a `?` is an operator."""
    if token.kind == "ident":
        return token.text not in KEYWORDS or token.text in OPERAND_KEYWORDS
    if token.kind == "string":
        return not token.text.endswith("${")
    if token.kind == "op":
        return token.text == "?"  # a blank is an operand itself
    return token.kind in ("number", "char") or token.text in (")", "]", "}")


def blank_tokens(text, tokens):
    """The `?` tokens that are blanks: those standing where an operand begins.

    A `?` after an operand on the same line is an infix or postfix operator
    (`a ? b`). The condition of `if`, `while` and `for` is not an operand, so
    the `?` of `if (c) ? else y` is a blank.
    """
    after_operand = False
    previous = None
    control = []  # for every open `(`: does it follow `if`, `while` or `for`?
    for token in tokens:
        if token.kind == "comment":
            continue
        is_question = token.kind == "op" and token.text == "?"
        if is_question and (not after_operand or "\n" in text[previous.end : token.start]):
            yield token
        after_operand = ends_operand(token)
        if token.kind == "punct":
            if token.text == "(":
                control.append(previous is not None and previous.text in CONTROL_KEYWORDS)
            elif token.text == ")" and control and control.pop():
                after_operand = False
        previous = token


def comment_headers(tokens):
    """Yield (comment token, match of HEADER_RE in its text, is heading) for every header text in a comment.

    A heading opens its comment line and names the exercise of the blanks
    below it; anywhere else in a comment the text only refers to an exercise.
    """
    for token in tokens:
        if token.kind == "comment":
            for m in HEADER_RE.finditer(token.text):
                line_start = token.text.rfind("\n", 0, m.start()) + 1
                yield token, m, HEADING_PREFIX_RE.match(token.text, line_start, m.start()) is not None


def headings(tokens, lines):
    """[(line, exercise ID)] of the exercise headings."""
    return [
        (lines.line(token.start + m.start()), m.group(1))
        for token, m, heading in comment_headers(tokens)
        if heading and ID_RE.match(m.group(1))
    ]


def find_blanks(text):
    """Return [(line, exercise ID or None)] for every blank in Scala source."""
    lines = Lines(text)
    tokens = list(lex(text))
    headers = headings(tokens, lines)
    header_lines = [h[0] for h in headers]
    blanks = []
    for token in blank_tokens(text, tokens):
        line = lines.line(token.start)
        k = bisect.bisect_right(header_lines, line)
        blanks.append((line, headers[k - 1][1] if k else None))
    return blanks


def scala_files(tree):
    """Relative paths of the Scala sources under tree, sorted. Build output and clones are skipped."""
    found = []
    for root, dirs, files in os.walk(tree):
        dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
        for name in files:
            if name.endswith(SCALA_SUFFIXES):
                found.append(os.path.relpath(os.path.join(root, name), tree).replace(os.sep, "/"))
    return sorted(found)


def read_expected(path):
    """Return [(file, exercise ID)], one for every blank row of the table in EXERCISES.md."""
    expected = []
    with open(path, encoding="utf-8") as f:
        for row in f:
            m = ROW_RE.match(row)
            if m:
                expected.append((m.group(2), m.group(1)))
    return expected


def id_key(exercise):
    task, n = exercise.split(".")
    return (int(task), int(n))


def task_of(exercise):
    return int(exercise.split(".")[0])


def parse_tasks(text):
    """The set of task numbers of a --tasks value such as "1,2"; raises ValueError for anything else."""
    try:
        return {int(t) for t in text.split(",")}
    except ValueError:
        raise ValueError(f"--tasks takes task numbers separated by commas, not '{text}'") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description="List the remaining `?` exercise blanks and check their count.")
    parser.add_argument("tree", nargs="?", default=".", help="source tree (default: the current directory)")
    parser.add_argument("--exercises", help="exercise table (default: <tree>/EXERCISES.md)")
    parser.add_argument("--tasks", help="comma-separated tasks to compare (default: all)")
    args = parser.parse_args(argv)

    table = args.exercises or os.path.join(args.tree, "EXERCISES.md")
    if not os.path.isdir(args.tree):
        print(f"check-blanks: {args.tree} is not a directory", file=sys.stderr)
        return 2
    if not os.path.isfile(table):
        print(f"check-blanks: {table} not found (the exercise table of a generated tree)", file=sys.stderr)
        return 2
    try:
        tasks = sorted(parse_tasks(args.tasks)) if args.tasks else None
    except ValueError as e:
        print(f"check-blanks: {e}", file=sys.stderr)
        return 2

    found = []  # (file, line, exercise ID)
    orphans = []
    reading = table
    try:
        expected = collections.Counter(read_expected(table))
        for path in scala_files(args.tree):
            reading = os.path.join(args.tree, path)
            with open(reading, encoding="utf-8", newline="") as f:
                text = f.read()
            for line, exercise in find_blanks(text):
                if exercise is None:
                    orphans.append((path, line))
                else:
                    found.append((path, line, exercise))
    except (OSError, UnicodeDecodeError) as e:
        print(f"check-blanks: cannot read {reading}: {e}", file=sys.stderr)
        return 2
    counted = collections.Counter((path, exercise) for path, _, exercise in found)
    places = sorted(set(expected) | set(counted), key=lambda k: (k[0], id_key(k[1])))  # (file, exercise ID)

    failed = False
    for task in tasks or sorted({task_of(exercise) for _, exercise in places}):
        blanks = sorted((b for b in found if task_of(b[2]) == task), key=lambda b: (id_key(b[2]), b[0], b[1]))
        mine = [k for k in places if task_of(k[1]) == task]
        wrong = [k for k in mine if expected[k] != counted[k]]
        want = sum(expected[k] for k in mine)
        print(f"Task {task}: {len(blanks)} blanks, EXERCISES.md lists {want}: {'MISMATCH' if wrong else 'ok'}")
        for path, line, exercise in blanks:
            print(f"  {path}:{line}  Exercise {exercise}")
        for path, exercise in wrong:
            print(
                f"  {path}: Exercise {exercise} has {counted[(path, exercise)]} blanks, "
                f"EXERCISES.md lists {expected[(path, exercise)]}"
            )
        failed = failed or bool(wrong)
    for path, line in orphans:
        print(f"{path}:{line}: `?` without a [CA26: Exercise <task>.<n>] comment above it")
    if failed or orphans:
        print("check-blanks: the blanks do not match EXERCISES.md", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
