#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# The checks of the phone (T5), which 5-soc/Makefile runs:
#
#   check-phone.sh check <Vsim> <phone.elf, scripted> <tests dir> <out dir>
#       Plays <tests dir>/journey.txt on the model (+phone-script, one event at
#       a time, the pictures of its `capture` lines into <out dir>/rtl) and
#       renders the same journey with the host model of the firmware
#       (<tests dir>/host-oracle.sh <out dir>/host). Every capture must exist in
#       both and be the same picture, byte for byte: the first pixel that
#       differs is reported. The run must exit 0 (the script done).
#   check-phone.sh boot <Vsim> <phone.elf> <out dir>
#       Boots the phone (the build that keeps its own time) and checks that its
#       first picture, frame 1, is not blank: the 240 x 320 phone at the middle
#       of the VGA's picture shows several colors, and the border around it is
#       black.
#   check-phone.sh window <Vsim> <phone.elf>
#       The phone in the harness's window (the model must be built with SDL=1):
#       the mouse and the keyboard of the window are its input, Ctrl+Q closes it.
#
# Environment: MAX_CYCLES (the bound of a run).

set -u -o pipefail
MAX_CYCLES=${MAX_CYCLES:-2000000000}

usage() {
    sed -n '3,/^$/s/^# \{0,1\}//p' "$0" >&2
    exit 2
}

fails=0
fail() {
    echo "  FAIL    $*"
    fails=$((fails + 1))
}

# same <got.ppm> <want.ppm>: the first difference of two P6 pictures, or nothing
first_difference() {
    python3 -I - "$1" "$2" <<'PY'
import sys

def read(path):
    data = open(path, "rb").read()
    fields, pos = [], 0
    while len(fields) < 4:
        while data[pos:pos + 1].isspace():
            pos += 1
        end = pos
        while not data[end:end + 1].isspace():
            end += 1
        fields.append(data[pos:end])
        pos = end
    pos += 1
    return fields, int(fields[1]), data[pos:]

(got_head, w, got), (want_head, w2, want) = read(sys.argv[1]), read(sys.argv[2])
if got_head != want_head:
    print("headers differ: %r against %r" % (got_head, want_head))
elif got != want:
    for i in range(0, min(len(got), len(want)), 3):
        if got[i:i + 3] != want[i:i + 3]:
            n = i // 3
            print("first difference at pixel (%d, %d): rtl %s, host %s; %d bytes differ"
                  % (n % w, n // w, got[i:i + 3].hex(), want[i:i + 3].hex(),
                     sum(a != b for a, b in zip(got, want))))
            break
    else:
        print("lengths differ: %d against %d bytes" % (len(got), len(want)))
PY
}

[ $# -ge 1 ] || usage
mode=$1
shift
case $mode in
check)
    [ $# -eq 4 ] || usage
    sim=$1 elf=$2 tests=$3 out=$4
    for f in "$sim" "$elf" "$tests/journey.txt" "$tests/host-oracle.sh"; do
        [ -e "$f" ] || { echo "check-phone: $f not found" >&2; exit 1; }
    done
    rm -rf "$out"
    mkdir -p "$out/rtl" "$out/host"
    names=$(sed -n 's/^capture[[:space:]]\{1,\}\([^[:space:]]\{1,\}\).*/\1/p' "$tests/journey.txt")
    [ -n "$names" ] || { echo "check-phone: $tests/journey.txt has no capture" >&2; exit 1; }

    if "$tests/host-oracle.sh" "$out/host" > "$out/host.log" 2>&1; then
        echo "  RUN     the host model rendered the journey ($(echo "$names" | wc -l) captures)"
    else
        fail "the host model failed:"
        tail -n 10 "$out/host.log" | sed 's/^/          /'
    fi

    "$sim" +elf="$elf" +max-cycles="$MAX_CYCLES" +phone-script="$tests/journey.txt" \
        +phone-frames="$out/rtl" > "$out/rtl.out" 2> "$out/rtl.err"
    code=$?
    if [ "$code" != 0 ]; then
        fail "the model ended with exit code $code, not 0 (the script done):"
        tail -n 5 "$out/rtl.err" | sed 's/^/          /'
    else
        echo "  RUN     the model played the journey: $(tail -n 1 "$out/rtl.err")"
    fi

    same=0
    for name in $names; do
        for side in rtl host; do
            [ -s "$out/$side/$name.ppm" ] || fail "$name: no $side picture"
        done
        [ -s "$out/rtl/$name.ppm" ] && [ -s "$out/host/$name.ppm" ] || continue
        if cmp -s "$out/rtl/$name.ppm" "$out/host/$name.ppm"; then
            same=$((same + 1))
        else
            fail "$name: the model's picture differs from the host model's: $(first_difference "$out/rtl/$name.ppm" "$out/host/$name.ppm")"
        fi
    done
    echo "  PASS    $same of $(echo "$names" | wc -l) captures equal the host model's"
    [ $fails -eq 0 ] && echo "  OK      check-phone"
    ;;
boot)
    [ $# -eq 3 ] || usage
    sim=$1 elf=$2 out=$3
    [ -x "$sim" ] && [ -f "$elf" ] || { echo "check-phone: $sim or $elf not found" >&2; exit 1; }
    mkdir -p "$out"
    rm -f "$out/boot.ppm"
    "$sim" +elf="$elf" +max-cycles="$MAX_CYCLES" +vga-dump=1:"$out/boot.ppm" +vga-exit-after-dump \
        > "$out/boot.out" 2> "$out/boot.err"
    code=$?
    if [ "$code" != 0 ]; then
        fail "the model ended with exit code $code, not 0:"
        tail -n 5 "$out/boot.err" | sed 's/^/          /'
    else
        why=$(python3 -I - "$out/boot.ppm" <<'PY'
import sys
data = open(sys.argv[1], "rb").read()
pos = data.index(b"255\n") + 4
pix = data[pos:]
W, H, X0, Y0, PW, PH = 640, 480, 200, 80, 240, 320
colors, border = set(), set()
for y in range(H):
    row = pix[y * W * 3:(y + 1) * W * 3]
    for x in range(W):
        c = row[x * 3:x * 3 + 3]
        if X0 <= x < X0 + PW and Y0 <= y < Y0 + PH:
            colors.add(c)
        else:
            border.add(c)
if len(colors) < 4:
    print("the phone shows %d color(s): blank" % len(colors))
elif border != {b"\0\0\0"}:
    print("the border around the phone is not black")
PY
)
        if [ -n "$why" ]; then
            fail "frame 1: $why"
        else
            echo "  PASS    the phone's first picture is not blank ($out/boot.ppm)"
        fi
    fi
    [ $fails -eq 0 ] && echo "  OK      phone boot"
    ;;
window)
    [ $# -eq 2 ] || usage
    sim=$1 elf=$2
    [ -x "$sim" ] && [ -f "$elf" ] || { echo "check-phone: $sim or $elf not found" >&2; exit 1; }
    "$sim" +elf="$elf" +vga-window +phone-input
    code=$?
    [ "$code" = 0 ] || fail "the window run ended with exit code $code"
    ;;
*) usage ;;
esac

[ $fails -eq 0 ]
