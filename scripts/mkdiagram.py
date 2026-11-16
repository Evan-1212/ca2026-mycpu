#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Render a block diagram source (`*.dg`) to SVG, for the task READMEs.

Usage:
  mkdiagram.py <source.dg> [<out.svg>]     write the SVG (default: the source with .svg)
  mkdiagram.py --check <source.dg>...      fail when an SVG differs from what its source gives

The output depends only on the source: the same bytes on every host, so `--check` can compare
the committed SVG with a fresh rendering. There is no layout engine: the source places every box.

Source format, one statement per line; `#` starts a comment line; strings are in double quotes
and a `\\"` inside one is a quote:

  diagram <width> <height> "<title>"          first statement: canvas size and accessible title
  group <id> <x> <y> <w> <h> "<label>"        dashed frame, label in its top-left corner
  box <id> <x> <y> <w> <h> "<title>" "<line>"...
                                              a block: a bold title and lines of text, centered
  text <x> <y> "<text>" [start|middle|end]    free text (default start), baseline at y
  arrow <point> <point>... ["<label>"] [both] [dashed]
                                              a line with an arrowhead at its last point (both: at
                                              each end); the label is written along its longest
                                              segment

A point is `<x>,<y>`, or a side of a box or group: `<id>.n`, `.s`, `.e` or `.w` (the middle of the
side), `<id>.n@0.25` (a quarter of the way along it, from the left or the top). Between two points
that share neither x nor y the line turns once: horizontally first after a point on an east or west
side or a plain point, vertically first after a point on a north or south side.

Errors (the line number of the statement): an unknown statement, a duplicate or unknown id, a box
whose text does not fit in it, a point outside the canvas.
"""

import os
import re
import sys

FONT = 13  # px
LINE = 16  # px between baselines
CHAR = 0.6 * FONT  # width of a character of a monospace font
PAD = 6  # px between a box's border and its text
TOKEN_RE = re.compile(r'"((?:[^"\\]|\\.)*)"|(\S+)')


class Error(Exception):
    pass


def tokens(line, number):
    out = []
    for m in TOKEN_RE.finditer(line):
        if m.group(1) is not None:
            out.append(("str", m.group(1).replace('\\"', '"').replace("\\\\", "\\")))
        else:
            out.append(("word", m.group(2)))
    return out


def number_of(token, number):
    kind, value = token
    if kind != "word" or not re.fullmatch(r"-?\d+", value):
        raise Error(f"line {number}: '{value}' is not a whole number")
    return int(value)


def escape(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def text_width(text):
    return len(text) * CHAR


class Diagram:
    def __init__(self):
        self.width = self.height = None
        self.title = ""
        self.frames = {}  # id -> (x, y, w, h)
        self.items = []  # SVG elements, in source order

    def point(self, word, number):
        m = re.fullmatch(r"(-?\d+),(-?\d+)", word)
        if m:
            x, y, side = int(m.group(1)), int(m.group(2)), None
        else:
            m = re.fullmatch(r"([A-Za-z_][\w-]*)\.([nsew])(?:@([01](?:\.\d+)?))?", word)
            if not m:
                raise Error(f"line {number}: '{word}' is not a point (x,y or <id>.<n|s|e|w>[@f])")
            if m.group(1) not in self.frames:
                raise Error(f"line {number}: no box or group '{m.group(1)}' above this line")
            x0, y0, w, h = self.frames[m.group(1)]
            side = m.group(2)
            f = float(m.group(3)) if m.group(3) else 0.5
            if f > 1:
                raise Error(f"line {number}: the fraction in '{word}' is above 1")
            x, y = {
                "n": (x0 + w * f, y0),
                "s": (x0 + w * f, y0 + h),
                "w": (x0, y0 + h * f),
                "e": (x0 + w, y0 + h * f),
            }[side]
            x, y = round(x), round(y)
        if not (0 <= x <= self.width and 0 <= y <= self.height):
            raise Error(f"line {number}: the point {word} = ({x}, {y}) is outside the canvas")
        return x, y, side

    def add_frame(self, ident, x, y, w, h, number):
        if ident in self.frames:
            raise Error(f"line {number}: the id '{ident}' is used twice")
        if x < 0 or y < 0 or w <= 0 or h <= 0 or x + w > self.width or y + h > self.height:
            raise Error(f"line {number}: '{ident}' does not lie inside the canvas")
        self.frames[ident] = (x, y, w, h)

    def statement(self, words, number):
        head = words[0][1]
        args = words[1:]
        if self.width is None and head != "diagram":
            raise Error(f"line {number}: the first statement must be `diagram`")
        if head == "diagram":
            if self.width is not None or len(args) != 3 or args[2][0] != "str":
                raise Error(f"line {number}: `diagram <width> <height> \"<title>\"`, once")
            self.width, self.height = number_of(args[0], number), number_of(args[1], number)
            self.title = args[2][1]
        elif head in ("group", "box"):
            if len(args) < 6 or args[0][0] != "word" or any(a[0] != "str" for a in args[5:]):
                raise Error(f"line {number}: `{head} <id> <x> <y> <w> <h> \"<text>\"...`")
            ident = args[0][1]
            x, y, w, h = (number_of(a, number) for a in args[1:5])
            texts = [a[1] for a in args[5:]]
            self.add_frame(ident, x, y, w, h, number)
            if head == "group":
                if len(texts) != 1:
                    raise Error(f"line {number}: a group has one label")
                if text_width(texts[0]) + 2 * PAD > w:
                    raise Error(f"line {number}: the label of '{ident}' is wider than the group")
                self.items.append(
                    f'<rect class="group" x="{x}" y="{y}" width="{w}" height="{h}" rx="6"/>'
                    f'<text class="glabel" x="{x + PAD}" y="{y + LINE}">{escape(texts[0])}</text>'
                )
                return
            wide = max(text_width(t) for t in texts)
            if wide + 2 * PAD > w:
                raise Error(f"line {number}: the text of '{ident}' is {wide:.0f} px wide, the box {w} px")
            if len(texts) * LINE + 2 * PAD > h:
                raise Error(f"line {number}: {len(texts)} lines of text do not fit in '{ident}' ({h} px)")
            parts = [f'<rect class="box" x="{x}" y="{y}" width="{w}" height="{h}" rx="3"/>']
            top = y + (h - len(texts) * LINE) / 2 + LINE - 4
            for i, t in enumerate(texts):
                cls = "title" if i == 0 else "line"
                parts.append(f'<text class="{cls}" x="{x + w / 2:g}" y="{top + i * LINE:g}">{escape(t)}</text>')
            self.items.append("".join(parts))
        elif head == "text":
            if len(args) not in (3, 4) or args[2][0] != "str":
                raise Error(f"line {number}: `text <x> <y> \"<text>\" [start|middle|end]`")
            x, y = number_of(args[0], number), number_of(args[1], number)
            anchor = args[3][1] if len(args) == 4 else "start"
            if anchor not in ("start", "middle", "end"):
                raise Error(f"line {number}: the anchor is start, middle or end, not '{anchor}'")
            self.inside(x, y, text_width(args[2][1]), anchor, FONT, number)
            self.items.append(f'<text class="free" x="{x}" y="{y}" text-anchor="{anchor}">{escape(args[2][1])}</text>')
        elif head == "arrow":
            points, label, both, dashed = [], None, False, False
            for kind, value in args:
                if kind == "str":
                    if label is not None:
                        raise Error(f"line {number}: an arrow has one label")
                    label = value
                elif value == "both":
                    both = True
                elif value == "dashed":
                    dashed = True
                else:
                    points.append(self.point(value, number))
            if len(points) < 2:
                raise Error(f"line {number}: an arrow needs two points at least")
            self.items.append(self.arrow(points, label, both, dashed, number))
        else:
            raise Error(f"line {number}: unknown statement '{head}'")

    @staticmethod
    def route(points):
        """The polyline through the points, with one turn between two that are not aligned."""
        out = [points[0][:2]]
        for (x0, y0, side), (x1, y1, _) in zip(points, points[1:]):
            if x0 != x1 and y0 != y1:
                out.append((x0, y1) if side in ("n", "s") else (x1, y0))
            out.append((x1, y1))
        return out

    def inside(self, x, y, width, anchor, size, number):
        """Fail unless a text of this width, anchored at (x, y), lies on the canvas."""
        left = {"start": x, "middle": x - width / 2, "end": x - width}[anchor]
        if left < 0 or left + width > self.width or y - size < 0 or y > self.height:
            raise Error(f"line {number}: the text at ({x}, {y}) does not fit on the canvas")

    def arrow(self, points, label, both, dashed, number):
        path = self.route(points)
        d = " ".join(f"{x},{y}" for x, y in path)
        cls = "wire dashed" if dashed else "wire"
        ends = ' marker-end="url(#head)"' + (' marker-start="url(#tail)"' if both else "")
        svg = f'<polyline class="{cls}" points="{d}"{ends}/>'
        if label:
            segments = list(zip(path, path[1:]))
            (ax, ay), (bx, by) = max(segments, key=lambda s: abs(s[1][0] - s[0][0]) + abs(s[1][1] - s[0][1]))
            width = len(label) * 0.6 * (FONT - 2)
            if ay == by:  # horizontal: above the line, centered
                x, y, anchor = (ax + bx) / 2, ay - 4, "middle"
            else:  # vertical: to the right of the line, at its middle
                x, y, anchor = ax + 4, (ay + by) / 2 + 4, "start"
            self.inside(x, y, width, anchor, FONT - 2, number)
            extra = ' text-anchor="middle"' if anchor == "middle" else ""
            svg += f'<text class="wlabel" x="{x:g}" y="{y:g}"{extra}>{escape(label)}</text>'
        return svg

    def svg(self):
        style = (
            "text{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:%dpx;fill:#1f2328}"
            ".title{font-weight:bold;text-anchor:middle}.line{text-anchor:middle}"
            ".glabel{font-weight:bold;fill:#57606a}.wlabel{font-size:%dpx;fill:#57606a}"
            ".box{fill:#f6f8fa;stroke:#1f2328;stroke-width:1.2}"
            ".group{fill:none;stroke:#8c959f;stroke-width:1;stroke-dasharray:6 4}"
            ".wire{fill:none;stroke:#1f2328;stroke-width:1.2}.dashed{stroke-dasharray:4 3}"
            ".head{fill:#1f2328}.bg{fill:#ffffff}"
            "@media (prefers-color-scheme:dark){text{fill:#e6edf3}.glabel,.wlabel{fill:#9198a1}"
            ".box{fill:#161b22;stroke:#e6edf3}.group{stroke:#6e7681}.wire{stroke:#e6edf3}"
            ".head{fill:#e6edf3}.bg{fill:#0d1117}}" % (FONT, FONT - 2)
        )
        out = [
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.width}" height="{self.height}" '
            f'viewBox="0 0 {self.width} {self.height}" role="img" aria-label="{escape(self.title)}">',
            f"<title>{escape(self.title)}</title>",
            f"<style>{style}</style>",
            '<defs><marker id="head" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="7" markerHeight="7" '
            'orient="auto"><path class="head" d="M0,0 L10,5 L0,10 z"/></marker>'
            '<marker id="tail" viewBox="0 0 10 10" refX="0" refY="5" markerWidth="7" markerHeight="7" '
            'orient="auto"><path class="head" d="M10,0 L0,5 L10,10 z"/></marker></defs>',
            f'<rect class="bg" x="0" y="0" width="{self.width}" height="{self.height}"/>',
        ]
        out += self.items
        out.append("</svg>")
        return "\n".join(out) + "\n"


def render(source_text, name="<source>"):
    diagram = Diagram()
    for number, raw in enumerate(source_text.split("\n"), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        try:
            words = tokens(line, number)
            diagram.statement(words, number)
        except Error as e:
            raise Error(f"{name}: {e}")
    if diagram.width is None:
        raise Error(f"{name}: no `diagram` statement")
    return diagram.svg()


def svg_path(source):
    return os.path.splitext(source)[0] + ".svg"


def main(argv=None):
    args = list(sys.argv[1:] if argv is None else argv)
    try:
        if args[:1] == ["--check"]:
            if len(args) < 2:
                raise Error("--check needs at least one source")
            stale = []
            for source in args[1:]:
                with open(source, encoding="utf-8") as f:
                    want = render(f.read(), source)
                target = svg_path(source)
                have = open(target, encoding="utf-8").read() if os.path.isfile(target) else None
                if have != want:
                    stale.append(target)
            for target in stale:
                print(f"mkdiagram: {target} is not what its source gives (run make diagrams)", file=sys.stderr)
            if stale:
                return 1
            print(f"  OK      {len(args) - 1} block diagrams are what their sources give")
            return 0
        if len(args) not in (1, 2):
            raise Error("usage: mkdiagram.py <source.dg> [<out.svg>] | --check <source.dg>...")
        with open(args[0], encoding="utf-8") as f:
            svg = render(f.read(), args[0])
        target = args[1] if len(args) == 2 else svg_path(args[0])
        with open(target, "w", encoding="utf-8") as f:
            f.write(svg)
        return 0
    except (Error, OSError) as e:
        print(f"mkdiagram: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
