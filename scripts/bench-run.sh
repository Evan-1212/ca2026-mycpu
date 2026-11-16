#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Run the benchmarks (Dhrystone, DureMark and the QR encoder) on a task's
# Verilator model and write one CSV row per measured workload (BEN-03).
#
# Usage: bench-run.sh <Vsim> <elf dir> <QR_GF=bitwise elf dir> <task> <variant>
# <arch> <out.csv>
#
# <elf dir> holds dhrystone.elf, duremark.elf and qrcode.elf (QR_GF=table, the
# default build); the other directory holds the qrcode.elf built with
# QR_GF=bitwise. The QR encoder has one row per build and ECC level:
# qrcode-<table|bitwise>-<L|M|Q|H>.
#
# Columns:
#   task,variant,arch,benchmark   what ran
#   cycles,instret                the program's own counts between its start
#                                 and stop markers (its "perf:" lines)
#   cpi                           cycles / instret, three decimals
#   score,score_unit              DMIPS/MHz*1000 or DureMark/MHz*1000, if any
#   run_cycles,run_instret        the harness's counts for the whole run, from
#                                 reset to the write of SimCtrl EXIT
#   run_cpi                       run_cycles / run_instret
#   exit                          the program's exit code (always 0: a run
#                                 that exits otherwise fails the script)
#
# With Zicsr (an <arch> such as rv32im_zicsr, from T3 on) the counters measure
# the same regions: the platform back end prints one "perf: hpm cycles=<c>
# instret=<i> events=<e1>,...,<e8>" line per region (QR: one per ECC level, in
# order), and each row has these columns too:
#   hpm_cycles,hpm_instret        mcycle and minstret over the region
#   hpm1..hpm8                    the hpm events 1 to 8 (docs/hpm-events.md)
# Every cycle without a retired instruction is in exactly one partition event,
# so hpm_cycles - hpm_instret must equal hpm1 + hpm4 + hpm5 + hpm6 + hpm7 +
# hpm8, exactly (T3-04, BEN-04); a row for which it does not fails the script.
#
# <out.csv> is removed first, and written only if every run exits 0, the harness
# reports its counts, and every workload prints its counts exactly once: 2 + 2 x
# 4 rows. Otherwise the script fails and leaves no <out.csv>, so a file of an
# earlier run is never taken for this one's. Simulation is cycle-exact, so one
# run per benchmark is enough and two runs give the same file. MAX_CYCLES
# (default 2000000000) bounds each run.

set -u

# numbers print the same in every locale (the decimal point of cpi is a CSV
# hazard)
export LC_ALL=C

if [ $# -ne 7 ]; then
    echo "usage: bench-run.sh <Vsim> <elf dir> <QR_GF=bitwise elf dir> <task> <variant> <arch> <out.csv>" >&2
    exit 2
fi
sim=$1
elfdir=$2
bitwisedir=$3
task=$4
variant=$5
arch=$6
csv=$7
max_cycles=${MAX_CYCLES:-2000000000}
rm -f "$csv" || { echo "bench-run: cannot remove the earlier $csv" >&2; exit 1; }
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if [ ! -x "$sim" ]; then
    echo "bench-run: $sim not found (run 'make verilator' in the task directory)" >&2
    exit 1
fi

# the counters of T3-04 and their events (BEN-03), in the builds with Zicsr (T3
# on)
case $arch in *_zicsr* | rv32g*) hpm=1 ;; *) hpm=0 ;; esac

fail=0
error() {
    echo "bench-run: $*" >&2
    fail=1
}

ratio() { # ratio <a> <b>: a / b with three decimals (b is positive)
    awk -v a="$1" -v b="$2" 'BEGIN { printf "%.3f", a / b }'
}

# field <name> <regex with one group> [file]: the group of the one line of the
# file (the program's output by default) that matches. Unless exactly one line
# does, it says so and returns 1 (it runs in a command substitution, so the
# caller sets `fail`).
field() {
    local name=$1 file=${3:-$tmp/out} count
    count=$(grep -cE "$2" "$file")
    if [ "$count" -ne 1 ]; then
        echo "bench-run: $bench prints $name $count times, not once" >&2
        return 1
    fi
    sed -n -E "s/$2/\\1/p" "$file"
}

row() { # row <benchmark> <cycles> <instret> <score> <unit> [<n>: the n-th hpm line of the run, from 1]
    if ! [[ $2 =~ ^[1-9][0-9]*$ && $3 =~ ^[1-9][0-9]*$ ]]; then
        error "$1: cycles '$2' and instret '$3' are not positive counts"
        return
    fi
    local extra=
    if [ $hpm = 1 ]; then
        extra=$(hpm_columns "$1" "${6:-1}") || {
            fail=1
            return
        }
    fi
    echo "$task,$variant,$arch,$1,$2,$3,$(ratio "$2" "$3"),${4:--},${5:--},$run_cycles,$run_instret,$(ratio "$run_cycles" "$run_instret"),0$extra" >> "$tmp/csv"
}

# hpm_columns <benchmark> <n>: ",<cycles>,<instret>,<e1>,...,<e8>" from the n-th
# "perf: hpm" line of the run, after checking the partition identity. It runs in
# a command substitution: it reports and returns 1, and the caller sets fail.
hpm_columns() {
    local line c i e
    line=$(grep -E '^perf: hpm ' "$tmp/out" | sed -n "$2p")
    if ! [[ $line =~ ^perf:\ hpm\ cycles=([0-9]+)\ instret=([0-9]+)\ events=([0-9]+(,[0-9]+){7})$ ]]; then
        echo "bench-run: $1: no well-formed hpm line number $2 ('$line')" >&2
        return 1
    fi
    c=${BASH_REMATCH[1]}
    i=${BASH_REMATCH[2]}
    IFS=, read -r -a e <<< "${BASH_REMATCH[3]}"
    local partition=$((e[0] + e[3] + e[4] + e[5] + e[6] + e[7]))
    if [ $((c - i)) -ne $partition ]; then
        echo "bench-run: $1: mcycle - minstret = $((c - i)), but the partition events add up to $partition" >&2
        return 1
    fi
    echo ",$c,$i,${BASH_REMATCH[3]}"
}

# hpm_count <expected>: the run printed that many hpm lines (none before T3).
hpm_count() {
    [ $hpm = 1 ] || return 0
    local count
    count=$(grep -cE '^perf: hpm ' "$tmp/out")
    if [ "$count" -ne "$1" ]; then
        error "$bench prints $count 'perf: hpm' lines, not $1"
        return 1
    fi
}

# run <name> <elf>: runs the program; its stdout in $tmp/out, the harness's
# whole-run counts in run_cycles and run_instret. Fails unless the program exits
# 0 and the harness reports both counts.
run() {
    bench=$1
    local elf=$2 code counts
    if [ ! -f "$elf" ]; then
        error "missing $elf (run 'make -C software all PLATFORM=mycpu ARCH=$arch')"
        return 1
    fi
    "$sim" +elf="$elf" +max-cycles="$max_cycles" > "$tmp/out" 2> "$tmp/err" < /dev/null
    code=$?
    if [ $code -ne 0 ]; then
        error "$bench exited with $code"
        tail -n 5 "$tmp/out" "$tmp/err" >&2
        return 1
    fi
    counts=$(field "the harness's counts" '^cycles=([1-9][0-9]* instret=[1-9][0-9]*)$' "$tmp/err") || {
        fail=1
        return 1
    }
    run_cycles=${counts%% *}
    run_instret=${counts##*instret=}
}

# qr <gf> <elf>: the QR encoder prints one line per ECC level.
qr() {
    local gf=$1 ecc line count
    run "qrcode-$gf" "$2" || return
    count=$(grep -c '^perf: ecc=' "$tmp/out")
    if [ "$count" -ne 4 ]; then
        error "$bench prints $count 'perf: ecc=' lines, not the 4 of L, M, Q and H"
        return
    fi
    hpm_count 4 || return
    local n=0
    for ecc in L M Q H; do
        n=$((n + 1))
        line=$(field "ecc=$ecc" "^perf: ecc=$ecc cycles=([0-9]+ instret=[0-9]+)\$") || { fail=1; continue; }
        row "qrcode-$gf-$ecc" "${line%% *}" "${line##*instret=}" "" "" $n
    done
}

header="task,variant,arch,benchmark,cycles,instret,cpi,score,score_unit,run_cycles,run_instret,run_cpi,exit"
[ $hpm = 1 ] && header="$header,hpm_cycles,hpm_instret,hpm1,hpm2,hpm3,hpm4,hpm5,hpm6,hpm7,hpm8"
echo "$header" > "$tmp/csv"

# scored <name> <cycles regex> <instret regex> <score regex> <unit>: a benchmark
# that prints its counts and a score, once each.
scored() {
    local cycles instret score
    run "$1" "$elfdir/$1.elf" || return
    cycles=$(field cycles "$2") && instret=$(field instret "$3") && score=$(field "its score" "$4") || {
        fail=1
        return
    }
    hpm_count 1 || return
    row "$1" "$cycles" "$instret" "$score" "$5"
}

scored dhrystone '^perf: cycles ([0-9]+)$' '^perf: instret ([0-9]+)$' \
    '^perf: DMIPS\/MHz\*1000 ([0-9]+)$' "DMIPS/MHz*1000"
scored duremark '^perf: Cycles +: ([0-9]+)$' '^perf: Instret +: ([0-9]+)$' \
    '^perf: Total Score +: ([0-9]+) DureMark.*$' "DureMark/MHz*1000"
qr table "$elfdir/qrcode.elf"
qr bitwise "$bitwisedir/qrcode.elf"

if [ $fail != 0 ]; then
    echo "bench-run: FAIL: no $csv (an earlier one was removed; see the messages above)" >&2
    exit 1
fi
mkdir -p "$(dirname "$csv")"
cp "$tmp/csv" "$csv"
column -s, -t "$csv" 2> /dev/null || cat "$csv"
echo "bench-run: wrote $csv"
