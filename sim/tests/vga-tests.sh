#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Checks the VGA side of the Verilator harness (T5-07) against VgaTestTop, a
# state machine that draws three pictures, one per buffer swap. Usage:
# vga-tests.sh <Vsim of VgaTestTop> <Vsim of HarnessTestTop> <elf dir>
#                     <directory with the expected frame-<k>.ppm>
# The ELFs are those of run-tests.sh: exit.elf lets the machine exit after its
# third picture, hang.elf makes it wait forever. The expected pictures come from
# vga-expect.py. Neither harness is built with SDL2.

set -u
SIM=$1
PLAIN=$2
ELF=$3
WANT=$4
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
fails=0
count=0

fail() {
    echo "  FAIL    $*"
    fails=$((fails + 1))
}

# run <name> <expected exit code> <harness> <args...>
run() {
    local name=$1 expect=$2 sim=$3
    shift 3
    count=$((count + 1))
    "$sim" "$@" < /dev/null > "$WORK/$name.out" 2> "$WORK/$name.err"
    local code=$?
    if [ "$code" -ne "$expect" ]; then
        fail "$name: exit code $code, expected $expect"
        sed 's/^/          /' "$WORK/$name.err"
        return 1
    fi
    echo "  PASS    $name (exit $code)"
}

# expect <name> <grep -E pattern>: stderr matches
expect() {
    grep -qE "$2" "$WORK/$1.err" || {
        fail "$1: stderr does not match '$2':"
        sed 's/^/          /' "$WORK/$1.err" | head -20
    }
}

# same <name> <dumped file> <k>: the dump is the expected picture k, a PPM of
# 921,615 bytes
same() {
    [ "$(wc -c < "$2")" -eq 921615 ] || fail "$1: $(basename "$2") is not 921,615 bytes"
    cmp -s "$2" "$WANT/frame-$3.ppm" || fail "$1: $(basename "$2") is not picture $3"
}

for k in 1 2 3; do
    [ -s "$WANT/frame-$k.ppm" ] || { echo "vga-tests: $WANT/frame-$k.ppm is missing" >&2; exit 1; }
done
# the three expected pictures differ, so a dump of the wrong frame cannot pass
cmp -s "$WANT/frame-1.ppm" "$WANT/frame-2.ppm" && fail "expected pictures 1 and 2 are the same"
cmp -s "$WANT/frame-1.ppm" "$WANT/frame-3.ppm" && fail "expected pictures 1 and 3 are the same"
cmp -s "$WANT/frame-2.ppm" "$WANT/frame-3.ppm" && fail "expected pictures 2 and 3 are the same"

# Every frame, in one run and not in order; frame 2 twice. The program exits.
run dump-all 0 "$SIM" +elf="$ELF/exit.elf" +vga-dump=3:"$WORK/all-3.ppm" +vga-dump=1:"$WORK/all-1.ppm" \
    +vga-dump=2:"$WORK/all-2.ppm" +vga-dump=2:"$WORK/all-2b.ppm" +retire-log="$WORK/dump-all.log" &&
    {
        same dump-all "$WORK/all-1.ppm" 1
        same dump-all "$WORK/all-2.ppm" 2
        same dump-all "$WORK/all-2b.ppm" 2
        same dump-all "$WORK/all-3.ppm" 3
        expect dump-all '^cycles=[0-9]+ instret=0$'
        [ "$(tail -n 1 "$WORK/dump-all.log" | grep -cE '^end cycles=[0-9]+ instret=0 exit=0$')" -eq 1 ] ||
            fail "dump-all: the retire log does not end with exit=0"
    }

# One frame alone is the same frame: its number does not depend on the others.
run dump-2 0 "$SIM" +elf="$ELF/exit.elf" +vga-dump=2:"$WORK/two.ppm" && same dump-2 "$WORK/two.ppm" 2

# +vga-exit-after-dump ends a program that never exits, right after the last
# frame asked for (frame 2 here: frame 3 is never drawn into the file system),
# and the retire log says why the run stopped.
run exit-after-dump 0 "$SIM" +elf="$ELF/hang.elf" +vga-dump=1:"$WORK/e1.ppm" +vga-dump=2:"$WORK/e2.ppm" \
    +vga-exit-after-dump +max-cycles=20000000 +retire-log="$WORK/exit-after-dump.log" &&
    {
        same exit-after-dump "$WORK/e1.ppm" 1
        same exit-after-dump "$WORK/e2.ppm" 2
        expect exit-after-dump 'the last \+vga-dump frame is written \(frame 2\): cycles=[0-9]+ instret=0$'
        [ "$(tail -n 1 "$WORK/exit-after-dump.log" | grep -cE '^end cycles=[0-9]+ instret=0 stop=vga-dump$')" -eq 1 ] ||
            fail "exit-after-dump: the retire log does not end with stop=vga-dump"
    }
# Without the option the same run goes on to its cycle limit.
run no-exit-after-dump 124 "$SIM" +elf="$ELF/hang.elf" +vga-dump=1:"$WORK/n1.ppm" +max-cycles=4000000 &&
    same no-exit-after-dump "$WORK/n1.ppm" 1

# A frame that the run never shows: exit code 2, whatever the program's code.
run never-shown 2 "$SIM" +elf="$ELF/exit.elf" +vga-dump=1:"$WORK/s1.ppm" +vga-dump=4:"$WORK/s4.ppm" &&
    {
        same never-shown "$WORK/s1.ppm" 1
        expect never-shown 'ended before it showed 1 of the frames asked for; the last whole frame was frame 3'
    }

# The same with +obs-trace: its trailer vouches for the frame written, not for
# the empty file of the frame never shown (OBS-03: scripts/obs-pack.py must
# refuse that file as a frame of the run).
run never-shown-obs 2 "$SIM" +elf="$ELF/exit.elf" +vga-dump=1:"$WORK/o1.ppm" +vga-dump=4:"$WORK/o4.ppm" \
    +obs-trace="$WORK/o.obs" +obs-task=T5 +obs-variant=vga-test &&
    {
        trailer=$(tail -c 4096 "$WORK/o.obs" | tr -c '[:print:]' '\n' | grep -a '"outputs"')
        case $trailer in
        *'"vga_dump:1":"'$(sha256sum < "$WORK/o1.ppm" | cut -c1-64)'"'*) ;;
        *) fail "never-shown-obs: the trailer has no hash of frame 1: $trailer" ;;
        esac
        case $trailer in
        *vga_dump:4*) fail "never-shown-obs: the trailer vouches for frame 4, which the run never showed" ;;
        esac
    }
run cut-short 2 "$SIM" +elf="$ELF/hang.elf" +vga-dump=3:"$WORK/c3.ppm" +max-cycles=1000000 &&
    expect cut-short 'timeout after 1000000 cycles' && expect cut-short 'ended before it showed 1 of the frames'

# A top without a VGA shows no frame at all.
run no-vga 2 "$PLAIN" +elf="$ELF/exit.elf" +vga-dump=1:"$WORK/p1.ppm" &&
    expect no-vga 'ended before it showed 1 of the frames asked for; the last whole frame was frame 0'

# Bad arguments and files.
run bad-dump-path 2 "$SIM" +elf="$ELF/exit.elf" +vga-dump=1:"$WORK/missing/f.ppm" &&
    expect bad-dump-path "cannot write $WORK/missing/f.ppm"
for bad in +vga-dump=0:"$WORK/z.ppm" +vga-dump=1 +vga-dump=1: +vga-dump=:x.ppm +vga-dump=x:y.ppm \
    +vga-dump=4294967296:"$WORK/z.ppm" +vga-dump=-1:"$WORK/z.ppm" +vga-exit-after-dump=1 +vga-window=1; do
    run "bad-arg-$count" 2 "$SIM" +elf="$ELF/exit.elf" "$bad" && expect "bad-arg-$((count - 1))" "bad argument '"
done
run exit-without-dump 2 "$SIM" +elf="$ELF/exit.elf" +vga-exit-after-dump &&
    expect exit-without-dump '\+vga-exit-after-dump needs a \+vga-dump'

# The default build has no SDL2 and says so; it does not run the program.
run window-without-sdl 2 "$SIM" +elf="$ELF/exit.elf" +vga-window &&
    expect window-without-sdl '\+vga-window: this harness was built without SDL2'
if ldd "$SIM" | grep -qi sdl; then
    fail "the default harness is linked with SDL"
fi

if [ $fails -ne 0 ]; then
    echo "sim check: $fails failure(s) in $count VGA runs of $SIM" >&2
    exit 1
fi
echo "  OK      sim check: $count VGA runs of $SIM"
