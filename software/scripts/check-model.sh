#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Run programs on a task's Verilator model (sim/main.cpp around the task's
# SimTop) and check them, in one of three modes:
#
#   check-model.sh expected <Vsim> <elf dir> T<n>
#       Every `user` and `privileged` program of programs/MANIFEST (a privileged
#       one first runs at T3, as its first-task column says; CLASSES overrides
#       the list of classes):
#       the console output must equal <program dir>/expected.txt and the exit
#       code <program dir>/expected.exit (0 when that file is absent).
#
#   check-model.sh rv32emu <Vsim> <elf dir> T<n> <rv32emu> <rv32emu elf dir>
#       VER-07: the same programs built for PLATFORM=rv32emu run on rv32emu;
#       both must print the same console output, return the same exit code,
#       and agree on a0 at exit. rv32emu dumps its registers at exit (-d). The
#       model shows of a0 what the program writes to SimCtrl EXIT, and EXIT
#       has only bits [7:0] (D12; platform_exit writes no more), which the
#       harness returns as its exit code. So a0 is compared in those 8 bits,
#       and an rv32emu a0 outside 0..255 fails: the model could not show it.
#
#   check-model.sh uart <Vsim> <uart.elf> <uart.elf with CONSOLE_UART=1> [harness arguments]
#       T1-08: the uart program echoes what +uart-stdin sends it. It runs once
#       with its console on SimCtrl PUTC and once with the console on the UART
#       too. The second keeps the program busy sending while its input
#       arrives, so the input must wait for io_uartRxReady (the harness's flow
#       control). What the UART sent is compared byte for byte, from the
#       harness's +uart-log: the echo alone in the first run, the banner, the
#       echo and the count in the second.
#
# Lines that start with "perf:" are platform-dependent numbers and are left out
# of every comparison. Only programs whose first task is at most n run. The
# check fails if fewer programs ran than MANIFEST lists as runnable, so an empty
# run cannot pass. MAX_CYCLES (default 500000000) bounds each model run of the
# first two modes, UART_MAX_CYCLES (default 2000000) each of the third.

set -u
cd "$(dirname "$0")/.." || exit 1

usage() {
    echo "usage: check-model.sh expected <Vsim> <elf dir> T<n>" >&2
    echo "       check-model.sh rv32emu <Vsim> <elf dir> T<n> <rv32emu> <rv32emu elf dir>" >&2
    echo "       check-model.sh uart <Vsim> <uart.elf> <uart.elf with CONSOLE_UART=1> [harness arguments]" >&2
    exit 2
}

mode=${1:-}
case $mode in
expected) [ $# -eq 4 ] || usage ;;
rv32emu) [ $# -eq 6 ] || usage ;;
uart) [ $# -ge 4 ] || usage ;;
*) usage ;;
esac
sim=$2
max_cycles=${MAX_CYCLES:-500000000}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if [ ! -x "$sim" ]; then
    echo "check-model: $sim not found (run 'make verilator' in the task directory)" >&2
    exit 1
fi

fail=0

# uart mode

# The input: more bytes than the receive FIFO holds (8), then EOT. A run that
# loses the EOT never ends by itself, so the bound on these runs is short
# (UART_MAX_CYCLES; the default is ample at 16 clock cycles per bit: a run with
# the console on the UART sends about 130 bytes, 160 cycles each).
uart_text='The quick brown fox jumps over the lazy dog, 0123456789 times.'
uart_max_cycles=${UART_MAX_CYCLES:-2000000}

# uart_run <name> <elf> <harness arguments...>: runs the echo program on the
# input; stdout and stderr in $tmp/<name>.{out,err}. Fails unless it exits 0.
uart_run() {
    local name=$1 elf=$2
    shift 2
    if [ ! -f "$elf" ]; then
        echo "FAIL uart ($name): missing $elf"
        fail=1
        return 1
    fi
    printf '%s\004' "$uart_text" > "$tmp/uart.in"
    "$sim" +elf="$elf" +max-cycles="$uart_max_cycles" +uart-stdin +uart-log="$tmp/$name.uart" "$@" \
        < "$tmp/uart.in" > "$tmp/$name.out" 2> "$tmp/$name.err"
    local code=$?
    if [ $code -ne 0 ]; then
        echo "FAIL uart ($name): exit code $code"
        cat "$tmp/$name.err"
        fail=1
        return 1
    fi
}

# uart_check <name> <what>: the UART log of run <name> must be $tmp/want.uart,
# and its stdout the bytes of $tmp/want.putc and of the UART log, each in its
# order (the harness prints a PUTC byte at once and a UART byte when its frame
# ends, so where the two meet on stdout depends on the timing of the model).
uart_check() {
    local name=$1 what=$2
    if ! cmp -s "$tmp/want.uart" "$tmp/$name.uart"; then
        echo "FAIL uart ($what): the UART did not send what was expected"
        diff -u "$tmp/want.uart" "$tmp/$name.uart" | head -20
        fail=1
    elif ! is_merge "$tmp/$name.out" "$tmp/want.putc" "$tmp/$name.uart"; then
        echo "FAIL uart ($what): stdout is not the SimCtrl PUTC text and the UART bytes"
        diff -u "$tmp/want.putc" "$tmp/$name.out" | head -20
        fail=1
    else
        echo "PASS uart ($what): ${#uart_text} bytes echoed in order, $(tail -n 1 "$tmp/$name.err")"
    fi
}

# is_merge <file> <a> <b>: the bytes of <file> are those of <a> and those of
# <b>, the bytes of each in their order.
is_merge() {
    {
        od -An -v -tu1 "$1"
        echo next
        od -An -v -tu1 "$2"
        echo next
        od -An -v -tu1 "$3"
    } | awk '
        BEGIN { part = n[0] = n[1] = n[2] = 0 }
        $1 == "next" { part++; next }
        { for (k = 1; k <= NF; k++) byte[part, n[part]++] = $k }
        END {
            if (n[0] != n[1] + n[2]) exit 1
            # ok[j]: the first i bytes of the file are the first j of a and the first i - j of b
            ok[0] = 1
            for (i = 0; i < n[0]; i++)
                for (j = (i + 1 < n[1] ? i + 1 : n[1]); j >= 0; j--)
                    ok[j] = (ok[j] && i - j < n[2] && byte[0, i] == byte[2, i - j]) ||
                            (j > 0 && ok[j - 1] && byte[0, i] == byte[1, j - 1])
            exit !ok[n[1]]
        }'
}

if [ "$mode" = uart ]; then
    putc_elf=$3
    uart_elf=$4
    shift 4
    banner='uart echo: type, Ctrl-D ends'
    trailer="uart echo: ${#uart_text} bytes"

    # Console on the UART: everything the program prints is one stream, and
    # SimCtrl PUTC is silent.
    if uart_run console-uart "$uart_elf" "$@"; then
        printf '%s\n%s\n%s\n' "$banner" "$uart_text" "$trailer" > "$tmp/want.uart"
        : > "$tmp/want.putc"
        uart_check console-uart 'console on the UART'
    fi

    # Console on SimCtrl PUTC: the UART sends the echo and nothing else.
    if uart_run console-putc "$putc_elf" "$@"; then
        printf '%s' "$uart_text" > "$tmp/want.uart"
        printf '%s\n\n%s\n' "$banner" "$trailer" > "$tmp/want.putc"
        uart_check console-putc 'console on PUTC'
    fi
    [ $fail = 0 ] && echo "check-model (uart): PASS"
    exit $fail
fi

# expected and rv32emu modes

elfdir=$3
task=$4
emu=${5:-}
emudir=${6:-}
manifest=programs/MANIFEST

case $task in T[1-5]) ;; *) usage ;; esac
if [ "$mode" = rv32emu ] && [ ! -x "$emu" ]; then
    echo "check-model: $emu not found (run 'make rv32emu' at the top)" >&2
    exit 1
fi

runnable=0
ran=0

# Every program directory must have a MANIFEST class.
for d in programs/*/ benchmarks/*/; do
    name=$(basename "$d")
    if ! grep -Eq "^$name[[:space:]]+(user|mmio-in|vga|privileged)[[:space:]]+T[1-5]\$" "$manifest"; then
        echo "FAIL $name: no valid class in $manifest"
        fail=1
    fi
done

# (a last line without its newline is read too)
while read -r name class first || [ -n "$name" ]; do
    case $name in '' | \#*) continue ;; esac

    # rv32emu runs user programs only (D13); the model runs the classes asked
    # for
    classes=user
    [ "$mode" = expected ] && classes=${CLASSES:-user privileged}
    case " $classes " in *" $class "*) ;; *) continue ;; esac
    [ "${first#T}" -le "${task#T}" ] || continue
    runnable=$((runnable + 1))

    dir=programs/$name
    [ -d "$dir" ] || dir=benchmarks/$name
    elf=$elfdir/$name.elf
    if [ ! -f "$elf" ]; then
        echo "FAIL $name: missing $elf (run 'make -C software all PLATFORM=mycpu')"
        fail=1
        continue
    fi

    "$sim" +elf="$elf" +max-cycles="$max_cycles" > "$tmp/out" 2> "$tmp/err" < /dev/null
    code=$?
    grep -v '^perf:' "$tmp/out" > "$tmp/got"

    a0=
    if [ "$mode" = expected ]; then
        if [ ! -f "$dir/expected.txt" ]; then
            echo "FAIL $name: missing $dir/expected.txt"
            fail=1
            continue
        fi
        grep -v '^perf:' "$dir/expected.txt" > "$tmp/want"
        want_code=0
        [ -f "$dir/expected.exit" ] && want_code=$(cat "$dir/expected.exit")
        reference=$dir/expected.txt
    else
        if [ ! -f "$emudir/$name.elf" ]; then
            echo "FAIL $name: missing $emudir/$name.elf (run 'make -C software all PLATFORM=rv32emu')"
            fail=1
            continue
        fi
        rm -f "$tmp/regs"
        "$emu" -q -d "$tmp/regs" "$emudir/$name.elf" > "$tmp/emu" 2> "$tmp/emu.err" < /dev/null
        want_code=$?
        grep -v '^perf:' "$tmp/emu" > "$tmp/want"
        reference=rv32emu
        # a0 is x10 in rv32emu's register dump
        a0=$(sed -n -E 's/^ *"x10": *([0-9]+),?$/\1/p' "$tmp/regs" 2> /dev/null)
        if [ -z "$a0" ]; then
            echo "FAIL $name: rv32emu wrote no a0 (x10) to its register dump"
            fail=1
            continue
        fi
    fi
    ran=$((ran + 1))

    if ! diff -u "$tmp/want" "$tmp/got" > "$tmp/diff"; then
        echo "FAIL $name: console output differs from $reference"
        head -20 "$tmp/diff"
        fail=1
    elif [ "$code" != "$want_code" ]; then
        echo "FAIL $name: exit code $code, $reference has $want_code"
        cat "$tmp/err"
        fail=1
    elif [ -n "$a0" ] && [ "$a0" != "$code" ]; then
        echo "FAIL $name: a0 at exit is $a0 on rv32emu; the model wrote $code to EXIT (bits [7:0] are all it shows)"
        fail=1
    else
        echo "PASS $name (exit $code${a0:+, a0 $a0}, $(wc -l < "$tmp/got") lines, $(tail -n 1 "$tmp/err"))"
    fi
done < "$manifest"

if [ "$ran" -lt "$runnable" ]; then
    echo "FAIL: ran $ran of $runnable runnable programs"
    fail=1
fi
if [ "$runnable" -eq 0 ]; then
    echo "FAIL: $manifest lists no runnable program for $task"
    fail=1
fi
[ $fail = 0 ] && echo "check-model ($mode): PASS ($ran programs, $task, $(basename "$elfdir"))"
exit $fail
