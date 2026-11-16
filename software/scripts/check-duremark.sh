#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# BEN-02: the DureMark checksum of the MYCPU build, run on rv32emu, equals the
# checksum of a host build that runs the same number of iterations
# (-DDUREMARK_FIXED_ITERATIONS=<n>, with n read from the rv32emu output).
#
# Usage: scripts/check-duremark.sh <host cc> <rv32emu> <duremark.elf>

set -u -o pipefail
cd "$(dirname "$0")/.." || exit 1

if [ $# -ne 3 ]; then
    echo "usage: $0 <host cc> <rv32emu> <duremark.elf>" >&2
    exit 1
fi
hostcc=$1
emu=$2
elf=$3
src=benchmarks/duremark
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

die() {
    echo "check-duremark: FAIL: $*" >&2
    exit 1
}

# field <file> <label>: the number on the report line "<label> : <number>".
# Fails unless there is exactly one such line.
field() {
    local v
    v=$(sed -n "s/^$2 *: *\([0-9][0-9]*\)\$/\1/p" "$1")
    [[ $v =~ ^[0-9]+$ ]] && echo "$v"
}

"$emu" -q "$elf" > "$tmp/target.out" || die "$emu failed on $elf"
iterations=$(field "$tmp/target.out" Iterations) ||
    die "no iteration count in the rv32emu output"
checksum=$(field "$tmp/target.out" Checksum) ||
    die "no checksum in the rv32emu output"

"$hostcc" -std=c99 -O2 -DDUREMARK_FIXED_ITERATIONS="$iterations" -o "$tmp/duremark" \
    "$src"/*.c ||
    die "the host build failed"
"$tmp/duremark" > "$tmp/host.out" || die "the host run failed"
host_iterations=$(field "$tmp/host.out" Iterations) ||
    die "no iteration count in the host output"
host_checksum=$(field "$tmp/host.out" Checksum) ||
    die "no checksum in the host output"

[ "$host_iterations" = "$iterations" ] ||
    die "the host ran $host_iterations iterations, rv32emu $iterations"
[ "$host_checksum" = "$checksum" ] ||
    die "checksum $checksum on rv32emu, $host_checksum on the host ($iterations iterations)"
echo "check-duremark: PASS ($iterations iterations, checksum $checksum on rv32emu and on the host)"
