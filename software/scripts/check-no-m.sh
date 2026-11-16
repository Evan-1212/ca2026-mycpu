#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# INF-07: no M instruction in any ELF built for a no-M ARCH, libgcc included,
# and the M fixture (muldiv built for mycpu/rv32im) contains them, so the M
# build really uses the instructions.
#
# Every <sw dir>/<platform>/<arch> directory, for both platforms, must hold
# exactly <programs> ELFs, and each must disassemble to at least one
# instruction, so a missing build or a failing objdump cannot pass.
#
# Usage: scripts/check-no-m.sh <objdump> <sw dir> <programs> <no-M arch>...

set -u -o pipefail

if [ $# -lt 4 ]; then
    echo "usage: $0 <objdump> <sw dir> <programs> <no-M arch>..." >&2
    exit 1
fi
objdump=$1
sw=$2
programs=$3
shift 3

m_insn='mul|mulh|mulhsu|mulhu|div|divu|rem|remu'
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

fail=0
failed() {
    echo "FAIL $*"
    fail=1
}

# disassemble <elf>: write the disassembly to $tmp/dis; fail when objdump fails
# or prints no instruction.
disassemble() {
    "$objdump" -d "$1" > "$tmp/dis" 2> "$tmp/err" &&
        grep -Eq '^\s*[0-9a-f]+:\s+[0-9a-f]{8}\s' "$tmp/dis"
}

checked=0
for arch; do
    for platform in mycpu rv32emu; do
        dir=$sw/$platform/$arch
        count=0
        for elf in "$dir"/*.elf; do
            [ -f "$elf" ] || continue
            count=$((count + 1))
            if ! disassemble "$elf"; then
                failed "$elf: cannot disassemble"
            elif grep -Eq "\s($m_insn)\s" "$tmp/dis"; then
                failed "$elf: M instruction in a no-M build"
            fi
        done
        [ "$count" -eq "$programs" ] ||
            failed "$dir: $count ELFs, expected $programs"
        checked=$((checked + count))
    done
done

fixture=$sw/mycpu/rv32im/muldiv.elf
if ! disassemble "$fixture"; then
    failed "$fixture: cannot disassemble"
else
    for insn in mul mulh div divu rem remu; do
        grep -Eq "\s$insn\s" "$tmp/dis" ||
            failed "$fixture: no $insn in the rv32im muldiv fixture"
    done
fi

[ "$fail" = 0 ] && echo "check-no-m: PASS ($checked no-M ELFs, M fixture has M instructions)"
exit "$fail"
