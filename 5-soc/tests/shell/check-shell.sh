#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# The checks of the UART shell (T5-08) and the demo of the SoC (T5-09), which
# 5-soc/Makefile runs:
#
#   check-shell.sh session <Vsim> <shell.elf> <shell.elf, console on the UART> <tests dir> <out dir>
#       Sends <tests dir>/session.txt to the shell over +uart-stdin, twice: to
#       the build whose console is SimCtrl PUTC (its stdout is compared) and to
#       the build whose console is the UART (its +uart-log is compared). Each
#       run must exit with the code of the session's last line and print
#       <tests dir>/expected.txt, apart from lines that start with "perf:"
#       (counter values) and the mepc of a trap (a code address). The frames
#       that the session's qr and vga commands show are checked too: the QR
#       pictures decode to their text, and the first QR style and nyancat's
#       first frame equal their golden frames.
#   check-shell.sh demo <Vsim> <shell.elf, console on the UART> <tests dir> <out dir>
#       Boots the shell and runs <tests dir>/demo.txt, with "@HOLD@" replaced
#       by $DEMO_HOLD (the display frames each QR picture stays). With SDL=1
#       the VGA is shown in the harness's window (closing it ends the run);
#       otherwise nyancat's first frame and every QR picture are dumped into
#       <out dir>, compared with the golden frames and decoded. The session
#       must end with exit 0.
#
# Environment: QR_URL, QR_STYLES and VGA_GOLDEN (required: sim/vga.mk's text,
# styles and golden frames), MAX_CYCLES (the bound of a run), QR_DECODE (the
# decoder, as sim/tests/vga-frames.sh takes it), SDL, DEMO_HOLD.

set -u -o pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
FRAMES=$ROOT/sim/tests/vga-frames.sh

# the text and the styles of the QR checks, and the golden frames: those of
# sim/vga.mk
URL=${QR_URL:?set QR_URL, the text that every QR style decodes to}
read -r -a styles <<< "${QR_STYLES:?set QR_STYLES, the styles of qrcode-vga in order}"
GOLDEN=${VGA_GOLDEN:?set VGA_GOLDEN, the directory of the golden frames}
MAX_CYCLES=${MAX_CYCLES:-500000000}

usage() {
    sed -n '3,/^$/s/^# \{0,1\}//p' "$0" >&2
    exit 2
}

fails=0
fail() {
    echo "  FAIL    $*"
    fails=$((fails + 1))
}

# normalize <file>: the text that is compared, without the lines of counters and
# with the mepc of a trap (an address in the program) left out
normalize() {
    grep -v '^perf:' "$1" | sed -E 's/mepc=0x[0-9a-f]{8}/mepc=*/'
}

# run <name> <Vsim> <elf> <session> <out dir> [harness arguments]: one run, its
# stdout in <out>/<name>.out, stderr in .err, UART log in .uart, exit code in
# `code`
run() {
    local name=$1 sim=$2 elf=$3 session=$4 out=$5
    shift 5
    "$sim" +elf="$elf" +max-cycles="$MAX_CYCLES" +uart-stdin +uart-log="$out/$name.uart" "$@" \
        < "$session" > "$out/$name.out" 2> "$out/$name.err"
    code=$?
}

# compare <what> <want> <got>: the normalized texts are the same
compare() {
    if diff -u <(normalize "$2") <(normalize "$3") > "$3.diff"; then
        echo "  PASS    $1: the session printed the expected text ($(grep -c . "$3") lines)"
    else
        fail "$1: the text differs from $2:"
        head -n 20 "$3.diff" | sed 's/^/          /'
    fi
}

[ $# -ge 1 ] || usage
mode=$1
shift
case $mode in
session)
    [ $# -eq 5 ] || usage
    sim=$1 putc_elf=$2 uart_elf=$3 tests=$4 out=$5
    for f in "$sim" "$putc_elf" "$uart_elf" "$tests/session.txt" "$tests/expected.txt"; do
        [ -e "$f" ] || { echo "check-shell: $f not found" >&2; exit 1; }
    done
    mkdir -p "$out"
    want_exit=$(sed -n 's/^exit \([0-9]*\)$/\1/p' "$tests/session.txt" | tail -n 1)

    # the frames, in the order in which the session shows them: qr classic (the
    # URL), qr logo sysprog21, then nyancat's first frame
    names=(qr-classic qr-logo-text nyancat-1)
    dumps=()
    for k in "${!names[@]}"; do
        rm -f "$out/${names[$k]}.ppm"
        dumps+=("+vga-dump=$((k + 1)):$out/${names[$k]}.ppm")
    done

    run putc "$sim" "$putc_elf" "$tests/session.txt" "$out" "${dumps[@]}"
    if [ "$code" != "$want_exit" ]; then
        fail "console on PUTC: exit code $code, expected $want_exit"
        tail -n 5 "$out/putc.out" "$out/putc.err" | sed 's/^/          /'
    else
        echo "  RUN     console on PUTC: exit $code, $(tail -n 1 "$out/putc.err")"
        compare 'console on PUTC' "$tests/expected.txt" "$out/putc.out"
        [ -s "$out/putc.uart" ] && fail "console on PUTC: the shell sent bytes on the UART"
    fi

    run uart "$sim" "$uart_elf" "$tests/session.txt" "$out"
    if [ "$code" != "$want_exit" ]; then
        fail "console on the UART: exit code $code, expected $want_exit"
        tail -n 5 "$out/uart.uart" "$out/uart.err" | sed 's/^/          /'
    else
        echo "  RUN     console on the UART: exit $code, $(tail -n 1 "$out/uart.err")"
        compare 'console on the UART' "$tests/expected.txt" "$out/uart.uart"
    fi

    # the frames of the first run
    "$FRAMES" decode "$URL" "$out" qr-classic || fails=$((fails + 1))
    "$FRAMES" decode 'sysprog21' "$out" qr-logo-text || fails=$((fails + 1))
    "$FRAMES" same "$GOLDEN" "$out" qr-classic nyancat-1 || fails=$((fails + 1))
    [ $fails -eq 0 ] && echo "  OK      check-shell"
    ;;
demo)
    [ $# -eq 4 ] || usage
    sim=$1 elf=$2 tests=$3 out=$4
    if [ ! -x "$sim" ] || [ ! -f "$elf" ] || [ ! -f "$tests/demo.txt" ]; then
        echo "check-shell: $sim, $elf or $tests/demo.txt not found" >&2
        exit 1
    fi
    mkdir -p "$out"
    sed "s/@HOLD@/${DEMO_HOLD:-1}/" "$tests/demo.txt" > "$out/session.txt"
    nyan=$(sed -n 's/^vga \([0-9]*\)$/\1/p' "$tests/demo.txt")
    case $nyan in
    '' | *[!0-9]*)
        echo "check-shell: $tests/demo.txt needs exactly one 'vga <frames>' line" >&2
        exit 1
        ;;
    esac
    args=()
    if [ "${SDL:-0}" = 1 ]; then
        args+=(+vga-window)
    else

        # nyancat shows frames 1..$nyan, then each QR style one frame, then the
        # custom text
        args+=("+vga-dump=1:$out/nyancat-1.ppm")
        for k in "${!styles[@]}"; do
            args+=("+vga-dump=$((nyan + 1 + k)):$out/qr-${styles[$k]}.ppm")
        done
        args+=("+vga-dump=$((nyan + 1 + ${#styles[@]})):$out/qr-text.ppm")
    fi
    echo "  DEMO    the shell from the boot ROM, session $out/session.txt"
    "$sim" +elf="$elf" +max-cycles="$MAX_CYCLES" +uart-stdin "${args[@]}" < "$out/session.txt" \
        2> "$out/demo.err" | tee "$out/demo.out"
    code=$?
    echo "  DEMO    $(tail -n 1 "$out/demo.err")"
    [ "$code" = 0 ] || fail "the demo ended with exit code $code"
    if [ "${SDL:-0}" != 1 ] && [ $fails -eq 0 ]; then
        text=$(sed -n 's/^qr color \(.*\)$/\1/p' "$tests/demo.txt")
        "$FRAMES" same "$GOLDEN" "$out" nyancat-1 "${styles[@]/#/qr-}" || fails=$((fails + 1))
        "$FRAMES" decode "$URL" "$out" "${styles[@]/#/qr-}" || fails=$((fails + 1))
        "$FRAMES" decode "$text" "$out" qr-text || fails=$((fails + 1))
        echo "  FRAMES  $out/*.ppm"
    fi
    [ $fails -eq 0 ] && echo "  OK      demo"
    ;;
*) usage ;;
esac

[ $fails -eq 0 ]
