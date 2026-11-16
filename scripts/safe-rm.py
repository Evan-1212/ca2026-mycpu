#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# MyCPU is freely redistributable under the MIT License. See the file
# "LICENSE" for information on usage and redistribution of this file.
"""The `rm -rf` of the top-level Makefile's clean, distclean and fetch-sail, with guards.

    safe-rm.py --root <repository> [--under <dir> --outside keep|refuse] [--] <path>...

Every path is checked before anything is removed. The script refuses (exit 1, nothing removed)
  - an empty path;
  - `/`, the repository itself or any directory that contains it;
  - with --under: a path that is not strictly inside <dir> (EXTERNALS), when --outside is
    `refuse` and the path exists as anything other than an empty directory. With `keep` such a
    path is left alone and named (a RV32EMU_DIR, ACT_DIR or SAIL_DIR of the user's own, outside
    EXTERNALS, is never deleted). <dir> itself (resolved, symbolic links included) must not be
    empty, `/` or above the repository.
The check is on where the entry itself is (its parent directory resolved, symbolic links
included): a symbolic link is removed as a link, and what it points to is never touched.
"""

import argparse
import os
import shutil
import sys


def entry(path):
    """Where the directory entry of `path` is: its parent resolved, its own name kept."""
    path = os.path.abspath(path)
    parent, name = os.path.split(path)
    return os.path.join(os.path.realpath(parent), name) if name else os.path.realpath(path)


def contains(outer, inner):
    """outer is inner or one of the directories above it."""
    return outer == "/" or inner == outer or inner.startswith(outer + "/")


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", required=True, help="the repository, which is never removed")
    ap.add_argument("--under", help="remove only paths inside this directory")
    ap.add_argument("--outside", choices=("keep", "refuse"), default="refuse",
                    help="what to do with a path outside --under (default: refuse)")
    ap.add_argument("paths", nargs="*")
    args = ap.parse_args(argv)
    root = os.path.realpath(args.root)

    under = None
    if args.under is not None:
        under = os.path.realpath(args.under) if args.under else ""
        if not args.under or under == "/" or (contains(under, root) and under != root):
            print(f"safe-rm: refusing to remove anything under '{args.under}': it is empty, / or "
                  f"contains the repository {root}", file=sys.stderr)
            return 1

    remove, errors = [], []
    for path in args.paths:
        if not path:
            errors.append("refusing an empty path")
            continue
        where = entry(path)
        if contains(where, root):
            errors.append(f"refusing to remove '{path}': it is / or contains the repository {root}")
            continue
        if under is not None and not where.startswith(under + "/"):
            if args.outside == "keep":
                print(f"  KEEP    {path} (not inside {args.under}; remove it by hand)")
                continue
            if os.path.lexists(where) and not (os.path.isdir(where) and not os.path.islink(where)
                                               and not os.listdir(where)):
                errors.append(f"refusing to remove '{path}': it is not inside {args.under}; "
                              "remove it by hand or move it there")
                continue
        remove.append(where)
    if errors:
        for e in errors:
            print(f"safe-rm: {e}", file=sys.stderr)
        return 1
    for where in remove:
        if os.path.islink(where) or (os.path.lexists(where) and not os.path.isdir(where)):
            os.unlink(where)
        elif os.path.isdir(where):
            shutil.rmtree(where)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
