#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

# Grade a submission with the harness of this checkout.
#
#   scripts/grade.sh [--submission DIR] [--out DIR] [--only a,b] [--timeout S] [--chain]
#
# The harness is everything but the exercises: the specs, the Makefiles, sim/,
# common/, software/ and scripts/ of this checkout. A submission supplies the
# files that hold blanks (the files EXERCISES.md lists). Grading copies this
# checkout into a scratch tree, replaces those files with the submission's, and
# runs scripts/grade.py there, so a spec, a Makefile gate or a script edited in
# the submission changes nothing. Run it from a clean checkout of
# sysprog21/ca2026-mycpu (the instructors' copy, or one fetched in CI), not from
# the submission.
#
# --chain verify the Change-Id chain and the commit rules of the
#             submission's history with this checkout's scripts (the
#             submission needs its full history)
# --only run only these checks (scripts/grade.py --list)
#
# The default submission is the current directory. The scratch tree links
# externals/ of the submission: the bootcamp answers that the tasks build on
# live there (BOOTCAMP_DIR is passed on if set).

here=$(cd "$(dirname "$0")" && pwd)
source "$here/common.sh"
set_colors

trusted=$(cd "$here/.." && pwd)
sub=$(pwd)
out=build/grade
timeout=3600
chain=0
only=
while [ $# -gt 0 ]; do
  case "$1" in
    --submission) sub=$(cd "$2" && pwd) || throw "No such directory: %s" "$2"; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --timeout) timeout=$2; shift 2 ;;
    --only) only=$2; shift 2 ;;
    --chain) chain=1; shift ;;
    *) sed -n '3,25p' "$0"; exit 2 ;;
  esac
done
case "$out" in /*) ;; *) out="$sub/$out" ;; esac

[ -f "$trusted/EXERCISES.md" ] ||
  throw "%s has no EXERCISES.md: run this script from a checkout of the public repository." "$trusted"

# The files with blanks: the second column of the rows of EXERCISES.md.
files=$(sed -n 's/^| [0-9.]* | `\([^`]*\)` | [0-9]* | .*/\1/p' "$trusted/EXERCISES.md" | sort -u)
[ -n "$files" ] || throw "EXERCISES.md lists no file."

if [ $chain -eq 1 ]; then
  echo "== history of the submission"
  (cd "$sub" && "$here/chain.sh" verify && "$here/commitlog.sh") || exit 1
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/grade.XXXXXX") || exit 1
trap 'rm -rf "$work"' EXIT
echo "== harness from $trusted"
tar -C "$trusted" -cf - --exclude=.git --exclude=build --exclude=externals --exclude=target \
    --exclude=work --exclude=__pycache__ . | tar -C "$work" -xf -

echo "== exercises from $sub"
for f in $files; do
  [ -f "$sub/$f" ] || throw "The submission has no %s." "$f"
  [ -L "$sub/$f" ] && throw "%s is a symbolic link." "$f"
  cp "$sub/$f" "$work/$f"
done

# The bootcamp work and the pinned clones are the submission's externals.
[ -d "$sub/externals" ] && ln -s "$sub/externals" "$work/externals"

status=0
args=(--timeout "$timeout" --out "$out")
[ $chain -eq 1 ] && args+=(--history-dir "$sub")
[ -n "$only" ] && args+=(--only "$only")
# Without --chain the scratch tree has no history: leave that check out.
if [ $chain -eq 0 ] && [ -z "$only" ]; then
  args+=(--only "deps,hygiene,task1,task2,task3,task4,task5")
fi
(cd "$work" && python3 scripts/grade.py "${args[@]}") || status=1
exit $status
