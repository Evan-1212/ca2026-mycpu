#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# Take the chisel-bootcamp exercise files from a plain directory (BOOTCAMP_SRC,
# docs/troubleshooting.md), for students who did the bootcamp in its container
# and have no git clone of it on the host.
#
# Usage: bootcamp-overlay.sh <clone> <src> <name>...
#
#   <clone>  the pinned chisel-bootcamp clone that `make fetch-bootcamp` made
#   <src>    a copy of the container's riscv/src directory (docker cp, or a
#            bind mount): it holds main/scala/riscv/<name>.scala
#   <name>   the exercise files, without .scala (BOOTCAMP_EXERCISES in the
#            Makefile: Alu DecodeRows BranchUnit LoadExtend PipelineFlush
#            Forward UartTx)
#
# Only the named files are copied, into <clone>/riscv/src/main/scala/riscv.
# Every other difference between <src> and <clone>/riscv/src is reported and
# ignored: the rest of the build comes from the pinned commit.
#
# A file of the clone is replaced only when it is still the pinned version, or
# is what an earlier run of this script wrote (recorded in the clone's .git
# directory). A file that was changed in the clone by hand is never overwritten:
# the script stops, because that clone holds work of its own, and BOOTCAMP_DIR
# is the way to use it.

set -euo pipefail

die() {
    echo "bootcamp-overlay: $*" >&2
    exit 1
}

[ $# -ge 3 ] || die "usage: $0 <clone> <src> <name>..."
clone=$1
src=$2
shift 2

rel=riscv/src/main/scala/riscv
[ -d "$clone/$rel" ] || die "$clone is not a chisel-bootcamp clone (run: make fetch-bootcamp)"
git -C "$clone" rev-parse --git-dir >/dev/null 2>&1 || die "$clone is not a git repository"
[ -d "$src/main/scala/riscv" ] ||
    die "BOOTCAMP_SRC=$src has no main/scala/riscv directory; it must be a copy of the bootcamp's riscv/src"
if [ "$(cd "$src" && pwd -P)" = "$(cd "$clone/riscv/src" && pwd -P)" ]; then
    die "BOOTCAMP_SRC is the clone's own riscv/src; use BOOTCAMP_DIR for a clone"
fi

sha() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

record=$(git -C "$clone" rev-parse --absolute-git-dir)/mycpu-overlay
recorded() { # $1 = name: the hash this script last wrote for it, or nothing
    if [ -f "$record" ]; then
        awk -v n="$1" '$2 == n { print $1 }' "$record"
    fi
}

# Check everything first, so that a refusal leaves the clone untouched.
todo=()
for name in "$@"; do
    from=$src/main/scala/riscv/$name.scala
    to=$clone/$rel/$name.scala
    [ -f "$from" ] || die "$from not found: BOOTCAMP_SRC must hold every exercise file"
    [ -f "$to" ] || die "$to not found: the clone is not at a revision this repository knows"
    cmp -s "$from" "$to" && continue
    if ! git -C "$clone" diff --quiet HEAD -- "$rel/$name.scala" &&
        [ "$(sha "$to")" != "$(recorded "$name")" ]; then
        die "$to was changed in the clone and differs from BOOTCAMP_SRC.
  That clone holds work of its own. Keep it and use BOOTCAMP_DIR=$clone without BOOTCAMP_SRC,
  or discard it:  git -C $clone checkout -- $rel/$name.scala"
    fi
    todo+=("$name")
done

for name in ${todo[@]+"${todo[@]}"}; do
    cp "$src/main/scala/riscv/$name.scala" "$clone/$rel/$name.scala"
    echo "  OVERLAY $name.scala <- $src"
done
: >"$record.new"
for name in "$@"; do
    echo "$(sha "$clone/$rel/$name.scala") $name" >>"$record.new"
done
mv "$record.new" "$record"

# Everything else must come from the pinned commit; say what is ignored.
exclude=()
for name in "$@"; do
    exclude+=("--exclude=$name.scala")
done
others=$(LC_ALL=C diff -rq "${exclude[@]}" --exclude=target "$clone/riscv/src" "$src" 2>/dev/null |
    sed -e "s#^Files $clone/riscv/src/\(.*\) and .* differ\$#differs: \1#" \
        -e "s#^Only in $clone/riscv/src\(/\(.*\)\)\?: \(.*\)\$#only in the pinned clone: \2/\3#" \
        -e "s#^Only in $src\(/\(.*\)\)\?: \(.*\)\$#only in BOOTCAMP_SRC: \2/\3#" \
        -e 's#: /#: #' || true)
if [ -n "$others" ]; then
    echo "  NOTE    BOOTCAMP_SRC differs from the pinned bootcamp outside the $# exercise files (ignored):"
    printf '%s\n' "$others" | sed 's/^/            /'
fi
