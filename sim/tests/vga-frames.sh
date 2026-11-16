#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# The steps of the golden-frame checks of sim/vga.mk (T5-07, T5-10). A frame is
# a PPM that the harness wrote for +vga-dump, <out dir>/<name>.ppm, and its
# golden frame is <golden dir>/<name>.ppm.gz (gzip -9 -n of the same bytes).
#
#   vga-frames.sh run <Vsim> <elf> <out dir> <name>...
#       Runs the program and dumps frame 1 as the first name, frame 2 as the
#       second, and so on. The program must exit with 0, and the run is
#       bounded by MAX_CYCLES (default 100000000). Its console output goes to
#       <out dir>/<first name>.console.
#   vga-frames.sh same <golden dir> <out dir> <name>...
#       Every frame equals its golden frame, byte for byte.
#   vga-frames.sh differ <golden dir> <out dir> <name>...
#       No frame equals its golden frame (a mutant was caught by each).
#   vga-frames.sh golden <golden dir> <out dir> <name>...
#       Writes the golden frames from the frames.
#   vga-frames.sh decode <text> <out dir> <name>...
#       The QR decoder reads exactly <text> from every frame.
#   vga-frames.sh no-decode <text> <out dir> <name>...
#       The QR decoder reads <text> from no frame (the mutant of T5-10).
#
# The decoder is the command in QR_DECODE, by default scripts/qr-decode.py run
# by uv with zxing-cpp 3.1.1 and Pillow.

set -u -o pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
QR_DECODE=${QR_DECODE:-uv run --no-project --with zxing-cpp==3.1.1 --with pillow python $ROOT/scripts/qr-decode.py}
PPM_BYTES=921615

usage() {
    sed -n '3,/^$/s/^# \{0,1\}//p' "$0" >&2
    exit 2
}

[ $# -ge 4 ] || usage
mode=$1
shift
fails=0
fail() {
    echo "  FAIL    $*"
    fails=$((fails + 1))
}

# frame <out dir> <name>: sets `path` to the frame, which must be a whole PPM
frame() {
    path="$1/$2.ppm"
    [ -f "$path" ] && [ "$(wc -c < "$path")" -eq $PPM_BYTES ] ||
        { fail "$2: $path is not a frame of $PPM_BYTES bytes"; return 1; }
}

case $mode in
run)
    sim=$1 elf=$2 out=$3
    shift 3
    [ -x "$sim" ] || { echo "vga-frames: $sim not found" >&2; exit 1; }
    [ -f "$elf" ] || { echo "vga-frames: $elf not found" >&2; exit 1; }
    mkdir -p "$out"
    args=()
    n=0
    for name in "$@"; do
        n=$((n + 1))
        rm -f "$out/$name.ppm"
        args+=("+vga-dump=$n:$out/$name.ppm")
    done
    "$sim" +elf="$elf" +max-cycles="${MAX_CYCLES:-100000000}" "${args[@]}" \
        < /dev/null > "$out/$1.console" 2> "$out/$1.err"
    code=$?
    if [ $code -ne 0 ]; then
        fail "$(basename "$elf"): exit code $code, expected 0"
        sed 's/^/          /' "$out/$1.console" "$out/$1.err" | tail -n 12
    else
        echo "  RUN     $(basename "$elf"): $n frame(s), $(tail -n 1 "$out/$1.err")"
    fi
    ;;
same | differ | golden)
    golden=$1 out=$2
    shift 2
    for name in "$@"; do
        frame "$out" "$name" || continue
        if [ "$mode" = golden ]; then
            mkdir -p "$golden"
            gzip -9 -n -c "$path" > "$golden/$name.ppm.gz" || fail "$name: cannot write the golden frame"
            echo "  GOLDEN  $name.ppm.gz ($(wc -c < "$golden/$name.ppm.gz") bytes)"
            continue
        fi
        if [ ! -f "$golden/$name.ppm.gz" ]; then
            fail "$name: no golden frame $golden/$name.ppm.gz (make golden-vga writes it)"
        elif ! gzip -t "$golden/$name.ppm.gz" 2> /dev/null ||
            [ "$(gzip -dc "$golden/$name.ppm.gz" | wc -c)" -ne $PPM_BYTES ]; then
            fail "$name: the golden frame $golden/$name.ppm.gz is not a compressed frame of $PPM_BYTES bytes"
        elif gzip -dc "$golden/$name.ppm.gz" | cmp -s - "$path"; then
            [ "$mode" = same ] && echo "  PASS    $name: equals the golden frame" ||
                fail "$name: equals the golden frame, but must not"
        else
            [ "$mode" = differ ] && echo "  PASS    $name: differs from the golden frame, as it must" ||
                fail "$name: differs from the golden frame $golden/$name.ppm.gz"
        fi
    done
    ;;
decode | no-decode)
    text=$1 out=$2
    shift 2
    paths=()
    for name in "$@"; do
        frame "$out" "$name" && paths+=("$path")
    done
    first=${1:-}
    # one run of the decoder for all frames: it prints a line per frame
    if [ ${#paths[@]} -eq $# ]; then
        $QR_DECODE "${paths[@]}" > "$out/$1.decoded" 2> "$out/$1.decode.err"
        code=$?
        if [ $code -ge 2 ] || [ "$(wc -l < "$out/$1.decoded")" -ne $# ]; then
            fail "the QR decoder failed (exit $code):"
            sed 's/^/          /' "$out/$1.decode.err" | tail -n 5
        else
            while IFS= read -r read <&3; do
                name=$1
                shift
                if [ "$read" = "$text" ]; then
                    [ "$mode" = decode ] && echo "  PASS    $name: decodes to $read" ||
                        fail "$name: decodes to $read, but must not"
                else
                    [ "$mode" = no-decode ] && echo "  PASS    $name: does not decode, as it must (read: '$read')" ||
                        fail "$name: decodes to '$read', not to '$text'"
                fi
            done 3< "$out/$first.decoded"
        fi
    fi
    ;;
*) usage ;;
esac

[ $fails -eq 0 ]
