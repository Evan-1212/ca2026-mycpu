#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# ref_one.sh <elf>
#
# Runs one final (self-checking) ACT4 ELF on the Sail reference model and
# behaves like the Verilator harness: the bytes the program stores to SimCtrl
# PUTC go to stdout, and the exit code is the value it stores to SimCtrl EXIT.
#
# Sail has no SimCtrl. sail.json declares its address range as plain I/O memory,
# so the stores succeed, and Sail's memory trace shows them; this script reads
# the trace. The ELF names the addresses: rvmodel_macros.h defines the absolute
# symbols mycpu_simctrl_exit and mycpu_simctrl_putc, and the label mycpu_halted,
# which both RVMODEL_HALT_* macros reach right after the store to EXIT and where
# Sail is stopped. Nothing else of SimCtrl is modeled. MEIP works because
# sail.json puts Sail's interrupt generator on an unused SimCtrl offset (see
# README.md).
#
# Environment:
#   SAIL_CONFIG  the core's sail.json (required)
#   SAIL         sail_riscv_sim (default: on PATH)
#   NM           nm for RISC-V ELFs (default: riscv-none-elf-nm on PATH)
#   MAX_INSTS    instruction limit (default 20000000)
#
# Exit codes:
#   0..255  the low byte of the first value stored to EXIT
#   124     no store to EXIT within MAX_INSTS instructions
#   2       bad usage, or the ELF lacks one of the three symbols
#   3       Sail itself failed; its output is on stderr

set -u -o pipefail
export LC_ALL=C

if [ $# -ne 1 ]; then
    echo "usage: $0 <elf>" >&2
    exit 2
fi
elf=$1
: "${SAIL_CONFIG:?ref_one.sh: set SAIL_CONFIG to the sail.json of the core}"
SAIL=${SAIL:-sail_riscv_sim}
NM=${NM:-riscv-none-elf-nm}
MAX_INSTS=${MAX_INSTS:-20000000}

# One `<name> <address>` line per symbol of the ELF.
symbols=$("$NM" "$elf" | awk '{ print $3, $1 }') || exit 2
symbol() {
    local value
    value=$(awk -v name="$1" '$1 == name { print $2 }' <<< "$symbols")
    if [ -z "$value" ]; then
        echo "ref_one.sh: $elf has no symbol $1 (not built with this core's rvmodel_macros.h?)" >&2
        exit 2
    fi
    echo "$value"
}
halted=$(symbol mycpu_halted) || exit 2
exit_addr=$(symbol mycpu_simctrl_exit) || exit 2
putc_addr=$(symbol mycpu_simctrl_putc) || exit 2

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# The trace has one line per memory access, e.g.
#   mem[W,0x010001004] <- 0x00000052
# Console bytes are printed as they come; the exit code goes to a file. The
# reader is a process substitution, so it sees the end of the trace exactly
# once, whether or not Sail ever opened it.
"$SAIL" --config "$SAIL_CONFIG" --inst-limit "$MAX_INSTS" --stop-at-pc "0x$halted" \
    --trace-mem --trace-output >(
        awk -v exit_addr="$((16#$exit_addr))" -v putc_addr="$((16#$putc_addr))" -v code_file="$tmp/code" '
            function hex(s,    i, n) {
                n = 0
                s = tolower(s)
                for (i = 1; i <= length(s); i++)
                    n = n * 16 + index("0123456789abcdef", substr(s, i, 1)) - 1
                return n
            }
            /^mem\[W,0x/ {
                addr = $1
                sub(/^mem\[W,0x/, "", addr)
                sub(/\]$/, "", addr)
                a = hex(addr)
                if (a == putc_addr) {
                    printf "%c", hex(substr($3, length($3) - 1))
                } else if (a == exit_addr && !seen) {
                    seen = 1
                    print hex(substr($3, length($3) - 1)) > code_file
                }
            }'
    ) "$elf" > "$tmp/sail.out" 2>&1
sail_rc=$?
wait $!

if [ "$sail_rc" -ne 0 ]; then
    echo "ref_one.sh: $SAIL exited with $sail_rc:" >&2
    cat "$tmp/sail.out" >&2
    exit 3
fi
if [ ! -s "$tmp/code" ]; then
    echo "ref_one.sh: no store to SimCtrl EXIT within $MAX_INSTS instructions" >&2
    exit 124
fi
exit "$(cat "$tmp/code")"
