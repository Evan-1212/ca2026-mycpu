#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Run every `user` program of programs/MANIFEST on rv32emu and compare its
# console output with <program dir>/expected.txt and its exit code with <program
# dir>/expected.exit (0 when that file is absent). Lines that start with "perf:"
# are platform-dependent numbers and are ignored. This is the rv32emu half of
# VER-07; each task's own check compares its Verilator model with the same
# files.
#
# The MANIFEST is validated first: every line is "<name> <class> T<1-5>" for an
# existing program directory, no name appears twice, and every program directory
# has a line. The check fails when no program is runnable.
#
# Usage: scripts/check-rv32emu.sh <rv32emu> <elf dir> [T<n>] With T<n>, only
# programs whose first task is at most n are run.

set -u -o pipefail
cd "$(dirname "$0")/.." || exit 1

if [ $# -lt 2 ] || [ $# -gt 3 ]; then
    echo "usage: $0 <rv32emu> <elf dir> [T<n>]" >&2
    exit 1
fi
emu=$1
elfdir=$2
task=${3:-}
manifest=programs/MANIFEST

if [ ! -x "$emu" ]; then
    echo "check-rv32emu: $emu not found (run 'make rv32emu' at the top)" >&2
    exit 1
fi
if [ -n "$task" ] && [[ ! $task =~ ^T[1-5]$ ]]; then
    echo "check-rv32emu: the task must be T1 to T5, not '$task'" >&2
    exit 1
fi
if [ ! -f "$manifest" ]; then
    echo "check-rv32emu: $manifest not found" >&2
    exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

fail=0
failed() {
    echo "FAIL $*"
    fail=1
}

# Pass 1: validate the MANIFEST and collect the programs to run.
declare -A class_of=() dir_of=()
to_run=()
lineno=0
while read -r name class first extra || [ -n "$name" ]; do
    lineno=$((lineno + 1))
    case $name in '' | \#*) continue ;; esac
    if [ -n "$extra" ] || [[ ! $class =~ ^(user|mmio-in|vga|privileged)$ ]] ||
        [[ ! $first =~ ^T[1-5]$ ]]; then
        failed "$manifest:$lineno: expected '<name> <class> T<1-5>'"
        continue
    fi
    if [ -n "${class_of[$name]:-}" ]; then
        failed "$manifest:$lineno: $name is listed twice"
        continue
    fi
    class_of[$name]=$class
    for d in "programs/$name" "benchmarks/$name"; do
        [ -d "$d" ] && dir_of[$name]=$d
    done
    if [ -z "${dir_of[$name]:-}" ]; then
        failed "$manifest:$lineno: no program directory for $name"
        continue
    fi
    [ "$class" = user ] || continue
    if [ -n "$task" ] && [ "${first#T}" -gt "${task#T}" ]; then
        continue
    fi
    to_run+=("$name")
done < "$manifest"

for d in programs/*/ benchmarks/*/; do
    name=$(basename "$d")
    [ -n "${class_of[$name]:-}" ] || failed "$name: no line in $manifest"
done
if [ ${#to_run[@]} -eq 0 ]; then
    failed "no runnable program in $manifest${task:+ for $task}"
fi

# Pass 2: run them.
passed=0
for name in "${to_run[@]}"; do
    dir=${dir_of[$name]}
    elf=$elfdir/$name.elf
    if [ ! -f "$elf" ] || [ ! -s "$dir/expected.txt" ]; then
        failed "$name: missing $elf or $dir/expected.txt"
        continue
    fi
    want_code=0
    if [ -f "$dir/expected.exit" ]; then
        want_code=$(< "$dir/expected.exit")
        if [[ ! $want_code =~ ^[0-9]+$ ]]; then
            failed "$name: $dir/expected.exit is not an exit code"
            continue
        fi
    fi

    "$emu" -q "$elf" > "$tmp/out" 2> "$tmp/err"
    code=$?

    grep -v '^perf:' "$tmp/out" > "$tmp/got"
    grep -v '^perf:' "$dir/expected.txt" > "$tmp/want"
    if ! diff -u "$tmp/want" "$tmp/got" > "$tmp/diff"; then
        failed "$name: output differs"
        head -20 "$tmp/diff"
    elif [ "$code" != "$want_code" ]; then
        failed "$name: exit code $code, expected $want_code"
        cat "$tmp/err"
    else
        echo "PASS $name"
        passed=$((passed + 1))
    fi
done

[ "$fail" = 0 ] && echo "check-rv32emu: PASS ($passed programs, $(basename "$elfdir"))"
exit "$fail"
