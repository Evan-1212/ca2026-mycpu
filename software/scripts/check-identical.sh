#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# VER-06 precondition: for an ARCH with Zicsr, the mycpu and rv32emu builds of a
# program differ only inside the platform slot of runtime/link.ld. For each
# program this compares
#   - the loaded image (objcopy -O binary) before and after the slot, and
#   - the address of every symbol outside the slot and of the slot's five
#     entry points,
# so the code and data that a benchmark measures, and every address it uses, are
# the same on both platforms.
#
# Each <sw dir>/<platform>/<arch> must hold exactly <programs> ELFs.
#
# Usage: scripts/check-identical.sh <objcopy> <nm> <sw dir> <programs> <arch>...

set -u -o pipefail

if [ $# -lt 5 ]; then
    echo "usage: $0 <objcopy> <nm> <sw dir> <programs> <arch>..." >&2
    exit 1
fi
objcopy=$1
nm=$2
sw=$3
programs=$4
shift 4

slot=1024  # bytes, as in runtime/link.ld
entries='platform_putchar|platform_exit|bench_mark_start|bench_mark_stop|platform_init'
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

fail=0
failed() {
    echo "FAIL $*"
    fail=1
}

# address <symbols file> <symbol>: its address in hex, or nothing.
address() {
    awk -v s="$2" '$3 == s { print $1 }' "$1"
}

# compare <mycpu elf> <rv32emu elf>: print nothing when they are the same
# outside the slot, otherwise the reason.
compare() {
    local side=a elf
    for elf in "$1" "$2"; do
        if ! "$nm" -n "$elf" > "$tmp/$side.sym" 2> /dev/null ||
            ! "$objcopy" -O binary "$elf" "$tmp/$side.bin" 2> /dev/null; then
            echo "cannot read $elf"
            return
        fi
        side=b
    done

    local base start
    base=$(address "$tmp/a.sym" _start)
    start=$(address "$tmp/a.sym" __platform_start)
    if [ -z "$base" ] || [ -z "$start" ]; then
        echo "no _start or __platform_start symbol"
        return
    fi
    local offset=$((16#$start - 16#$base))
    local size
    size=$(wc -c < "$tmp/a.bin" | tr -d ' ')
    if [ "$offset" -le 0 ] || [ "$size" -lt $((offset + slot)) ]; then
        echo "the platform slot is not inside the image"
        return
    fi
    if [ "$size" -ne "$(wc -c < "$tmp/b.bin" | tr -d ' ')" ]; then
        echo "the images differ in size"
        return
    fi
    if ! cmp -s -n "$offset" "$tmp/a.bin" "$tmp/b.bin"; then
        echo "the images differ before the platform slot"
        return
    fi
    if ! cmp -s -i $((offset + slot)) "$tmp/a.bin" "$tmp/b.bin"; then
        echo "the images differ after the platform slot"
        return
    fi

    # Addresses are 8 hex digits, so they compare as strings.
    local end
    end=$(printf '%08x' $((16#$start + slot)))
    for side in a b; do
        awk -v lo="$start" -v hi="$end" -v entries="^($entries)\$" \
            '($1 "") < (lo "") || ($1 "") >= (hi "") || $3 ~ entries { print $1, $3 }' \
            "$tmp/$side.sym" > "$tmp/$side.out"
    done
    if [ "$(grep -Ec " ($entries)\$" "$tmp/a.out")" -ne 5 ]; then
        echo "the platform slot does not have its five entry points"
        return
    fi
    if ! cmp -s "$tmp/a.out" "$tmp/b.out"; then
        echo "symbol addresses differ:"
        diff "$tmp/a.out" "$tmp/b.out" | head -10
    fi
}

checked=0
for arch; do
    count=0
    for elf in "$sw/mycpu/$arch"/*.elf; do
        [ -f "$elf" ] || continue
        count=$((count + 1))
        name=$(basename "$elf" .elf)
        other=$sw/rv32emu/$arch/$name.elf
        if [ ! -f "$other" ]; then
            failed "$arch $name: $other is missing"
            continue
        fi
        reason=$(compare "$elf" "$other")
        [ -z "$reason" ] || failed "$arch $name: $reason"
    done
    [ "$count" -eq "$programs" ] ||
        failed "$sw/mycpu/$arch: $count ELFs, expected $programs"
    checked=$((checked + count))
done

[ "$fail" = 0 ] && echo "check-identical: PASS ($checked programs, identical outside the platform slot)"
exit "$fail"
