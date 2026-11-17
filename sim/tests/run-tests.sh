#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Checks the Verilator harness against HarnessTestTop (INF-04 acceptance with a
# state machine instead of a CPU). Usage: run-tests.sh <Vsim> <elf dir> The ELFs
# are built by sim/Makefile from sim/tests/data.S. Nothing here depends on the
# UART divider of the model.

set -u
SIM=$1
ELF=$2
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
fails=0
count=0

fail() {
    echo "  FAIL    $*"
    fails=$((fails + 1))
}

# run <name> <expected exit code> <stdin> <args...>: runs the harness, keeps its
# stdout and stderr in $WORK/<name>.{out,err}. The input comes from a file, not
# a pipe: all of it is there from the first cycle, so what a run does with it
# does not depend on when another process gets to write.
run() {
    local name=$1 expect=$2 input=$3
    shift 3
    count=$((count + 1))
    printf '%s' "$input" > "$WORK/$name.in"
    "$SIM" "$@" < "$WORK/$name.in" > "$WORK/$name.out" 2> "$WORK/$name.err"
    local code=$?
    if [ "$code" -ne "$expect" ]; then
        fail "$name: exit code $code, expected $expect"
        sed 's/^/          /' "$WORK/$name.err"
        return 1
    fi
    echo "  PASS    $name (exit $code)"
}

# expect <name> <file suffix> <grep -E pattern>
expect() {
    if ! grep -qE "$3" "$WORK/$1.$2"; then
        fail "$1: $2 does not match '$3':"
        sed 's/^/          /' "$WORK/$1.$2" | head -40
    fi
}

# log_ends <name> <end of the `end` line, a grep -E pattern>: $WORK/<name>.log
# is a whole retire log. Its last line is the only `end` line and ends as given,
# every line is of one of the four kinds, and the counts of every `mark` line
# and of the `end` line are those of the lines before them: a cycle for each
# record and for each cycle of an `idle` line, and an instruction for each
# record that is no trap.
log_ends() {
    local log="$WORK/$1.log" why
    why=$(awk -v want="$2" '
        ended { print "line " NR " follows the end line"; bad = 1; exit }
        /^pc=/ { cycles++; if ($0 ~ / trap=- /) instret++; next }
        /^idle [1-9][0-9]*$/ { cycles += $2; next }
        /^(mark cycle|end cycles)=[0-9]+ instret=[0-9]+/ {
            split($2, c, "="); split($3, n, "=")
            if (c[2] != cycles || n[2] != instret) {
                print "line " NR " (" $0 ") follows " cycles " cycles and " instret " instructions"
                bad = 1
                exit
            }
            if ($1 == "end") {
                ended = 1
                if ($0 !~ (" " want "$")) print "the end line is " $0
            }
            next
        }
        { print "line " NR " is of no kind: " $0; bad = 1; exit }
        END { if (!ended && !bad) print "there is no end line" }' "$log")
    [ -z "$why" ] || fail "$1: retire log: $why"
}

# reject <name> <ELF> <grep -E pattern>: the harness refuses the ELF with exit
# code 2 and a message that matches
reject() {
    run "$1" 2 '' +elf="$ELF/$2.elf" && expect "$1" err "$3"
}

text='hello, putc and uart'

# +ram-fill makes the BSS check of HarnessTestTop real: the 16 words of BSS are
# zero only because the loader wrote zeros for p_memsz - p_filesz.
run exit 42 '' +elf="$ELF/exit.elf" +retire-log="$WORK/exit.log" +uart-log="$WORK/exit.uart" \
    +trace="$WORK/exit.fst" +trace-start=10 +ram-fill=0xdeadbeef
# stdout has the string twice: once through PUTC, once decoded from the UART
[ "$(grep -c "^$text\$" "$WORK/exit.out")" -eq 2 ] ||
    { fail "exit: the string is not on stdout twice"; cat "$WORK/exit.out"; }
# the UART log has it once, and nothing else
printf '%s\n' "$text" | cmp -s - "$WORK/exit.uart" || fail "exit: the UART log is not the string, once"
expect exit err '^cycles=[0-9]+ instret=[0-9]+$'

# instret: MARK, 4 header words, 4 data words, 2 x 6 string words, 21 PUTC and
# 21 TXDATA writes, 16 BSS words, EXIT: 80 retire records
expect exit err 'instret=80$'

# The retire log. The machine does nothing in its first cycle, writes MARK in
# the second, and that record alone has a mip (HarnessTestTop.markMip).
[ "$(head -n 3 "$WORK/exit.log" | tr '\n' '|')" = "idle 1|pc=80000000 inst=00b52023 next=80000004 rd=0 val=00000000 mem=w:10001018:4:00000000 csr=- trap=- halt=0 mip=00000888|mark cycle=2 instret=1|" ] ||
    { fail "exit: the retire log does not start with a cycle without a record, the MARK store and its mark line"; head -n 3 "$WORK/exit.log"; }
expect exit log '^pc=80000008 inst=00052583 next=8000000c rd=11 val=00000004 mem=r:80000004:4:00000004 csr=- trap=- halt=0 mip=-$'
expect exit log '^pc=[0-9a-f]{8} inst=00b52023 next=[0-9a-f]{8} rd=0 val=00000000 mem=w:10001000:4:0010012a csr=- trap=- halt=0 mip=-$'
[ "$(grep -c '^pc=' "$WORK/exit.log")" -eq 80 ] || fail "exit: retire log does not have 80 records"
[ "$(grep -c ' mip=-$' "$WORK/exit.log")" -eq 79 ] || fail "exit: 79 records have a mip of 0"

# The end line has the counts of stderr, and the cycles of the log add up to
# them: the machine waits while the UART holds a TXDATA write (the TX FIFO has 8
# bytes for the 21 of the string), so there are runs of cycles without a record,
# and the EXIT store is the record of the last cycle.
log_ends exit "$(cat "$WORK/exit.err") exit=42"
grep -qE '^idle ([2-9]|[1-9][0-9]+)$' "$WORK/exit.log" || fail "exit: no idle line of more than one cycle"
[ "$(tail -n 2 "$WORK/exit.log" | grep -c '^pc=.* mem=w:10001000:4:')" -eq 1 ] ||
    fail "exit: the EXIT store is not the line before the end line"
[ -s "$WORK/exit.fst" ] || fail "exit: no FST trace written"

# The BSS check can fail: bssover.elf expects 8 more words to be zero than its
# segment covers, and they hold the fill.
run bss-past-end 61 '' +elf="$ELF/bssover.elf" +ram-fill=0xdeadbeef

# A run that does not end by itself still ends its retire log, and says why.
run assert 3 '' +elf="$ELF/assert.elf" +retire-log="$WORK/assert.log"
expect assert err 'failed an assertion'
expect assert out "^$text\$"
log_ends assert 'instret=79 stop=assert'

run timeout 124 '' +elf="$ELF/hang.elf" +max-cycles=2000 +retire-log="$WORK/timeout.log"
expect timeout err 'timeout after 2000 cycles'
log_ends timeout 'cycles=2000 instret=79 stop=timeout'
run timeout-at-once 124 '' +elf="$ELF/hang.elf" +max-cycles=1 +retire-log="$WORK/timeout-at-once.log"
[ "$(cat "$WORK/timeout-at-once.log")" = "idle 1
end cycles=1 instret=0 stop=timeout" ] || fail "timeout-at-once: the retire log is not one idle cycle and the end line"

# The halt record, then the cycle in which the harness sees `halted`.
run halt 66 '' +elf="$ELF/halt.elf" +retire-log="$WORK/halt.log" +uart-log="$WORK/halt.uart"
expect halt err 'halted: cause=2 \(ebreak\) pc=0x[0-9a-f]{8}'
[ "$(grep -c "^$text\$" "$WORK/halt.out")" -eq 2 ] || fail "halt: the UART output was not drained"
printf '%s\n' "$text" | cmp -s - "$WORK/halt.uart" || fail "halt: the UART log is not the string, once"
log_ends halt 'instret=80 halt=2'
[ "$(tail -n 3 "$WORK/halt.log" | head -n 2 | sed 's/^pc=.* halt=2 mip=-$/HALT/' | tr '\n' '|')" = "HALT|idle 1|" ] ||
    fail "halt: the retire log does not end with the halt record, one idle cycle and the end line"

# SIGTERM ends the run with 128 + 15, and the trace is closed (a background job
# of a script ignores SIGINT, and the harness leaves it ignored)
"$SIM" +elf="$ELF/hang.elf" +trace="$WORK/int.fst" +retire-log="$WORK/interrupt.log" > /dev/null 2> "$WORK/int.err" &
pid=$!
for _ in $(seq 100); do # the handler is installed before the trace is opened
    [ -e "$WORK/int.fst" ] && break
    sleep 0.05
done
kill -TERM $pid
wait $pid
code=$?
count=$((count + 1))
if [ $code -eq 143 ] && grep -q '^sim: signal 15 after' "$WORK/int.err" && [ -s "$WORK/int.fst" ]; then
    echo "  PASS    interrupt (exit $code)"
else
    fail "interrupt: exit code $code, expected 143 with a message and a trace"
fi
# (how far the machine got when the signal came is a matter of timing)
log_ends interrupt 'stop=signal'

# +uart-stdin
run echo 8 $'echo me\n' +elf="$ELF/echo.elf" +uart-stdin +max-cycles=200000 +uart-log="$WORK/echo.uart"
expect echo out '^echo me$'

# the UART log: the string, then the echo; the PUTC copy of the string is not in
# it
printf '%s\necho me\n' "$text" | cmp -s - "$WORK/echo.uart" || fail "echo: the UART log is not the string and the echo"
run echo-gap 8 $'echo me\n' +elf="$ELF/echo.elf" +uart-stdin +uart-rx-gap=0 +max-cycles=200000
expect echo-gap out '^echo me$'
# end of input: what was sent is echoed, and the run goes on
run echo-eof 124 'abc' +elf="$ELF/echo.elf" +uart-stdin +max-cycles=20000
expect echo-eof out '^abc$'

# Flow control: the program reads nothing for 300 bit times, more than the 21
# frames take, so the harness must stop at a full RX FIFO (8 bytes) and go on
# when the program reads. Without flow control the FIFO overruns (exit 62).
line='0123456789abcdefghij'
run pause 21 "$line"$'\n' +elf="$ELF/pause.elf" +uart-stdin +uart-rx-gap=0 +max-cycles=200000
expect pause out "^$line\$"
run pause-no-flow-control 62 "$line"$'\n' +elf="$ELF/pause.elf" +uart-stdin +uart-no-flow-control \
    +max-cycles=200000
grep -q "^$line\$" "$WORK/pause-no-flow-control.out" && fail "pause-no-flow-control: no input was lost"

# Output files that cannot be written
run bad-trace 2 '' +elf="$ELF/exit.elf" +trace="$WORK/missing/exit.fst"
expect bad-trace err 'cannot write .*/missing/exit.fst'
run bad-retire-log 2 '' +elf="$ELF/exit.elf" +retire-log="$WORK/missing/retire.log"
expect bad-retire-log err 'cannot write .*/missing/retire.log'
run bad-uart-log 2 '' +elf="$ELF/exit.elf" +uart-log="$WORK/missing/uart.log"
expect bad-uart-log err 'cannot write .*/missing/uart.log'
# ... or not to their end (a full disk): the run goes on, and its code is 2
if [ -c /dev/full ]; then
    run full-retire-log 2 '' +elf="$ELF/exit.elf" +retire-log=/dev/full
    expect full-retire-log err 'cannot write /dev/full to its end'
    expect full-retire-log err '^cycles=[0-9]+ instret=80$'
    run full-uart-log 2 '' +elf="$ELF/exit.elf" +uart-log=/dev/full
    expect full-uart-log err 'cannot write /dev/full to its end'
fi
run empty-uart-log 2 '' +elf="$ELF/exit.elf" +uart-log=
expect empty-uart-log err "bad argument '\+uart-log='"

# Rejected ELFs (sim/Makefile says what is wrong with each)
reject bad-text not-elf 'not an ELF file'
reject bad-truncated truncated 'past the end of the file'
reject bad-class elf64 'not a 32-bit ELF'
reject bad-ident ident 'EI_VERSION is not 1'
reject bad-machine machine 'EM_RISCV'
reject bad-version version 'e_version is not 1'
reject bad-memsz memsz 'segment 1: p_filesz is larger than p_memsz'
reject bad-paddr paddr 'segment 1: p_vaddr 0x80000000 differs from p_paddr 0x80000100'
reject bad-align-pow2 align-pow2 'segment 1: p_align 0x3 is not a power of two'
reject bad-align-off align-off 'segment 1: p_vaddr 0x80000000 and p_offset 0x1000 are not congruent modulo p_align 0x2000'
# 64 KiB of RAM: the segment at 0x80010000 is the first byte past it
reject bad-far far 'segment 2 \[0x80010000, 0x80010004\) is outside RAM \[0x80000000, 0x80010000\)'
reject bad-overlap overlap 'segments at 0x80000000 and 0x80000000 overlap'
reject bad-entry entry 'entry point 0x80000004 is not the reset vector'
entry='entry point 0x80000000 is not in the file contents of an executable PT_LOAD segment'
reject bad-entry-outside outside "$entry"
reject bad-entry-noexec noexec "$entry"
reject bad-entry-bss bss-entry "$entry"
run bad-missing 2 '' +elf="$WORK/missing.elf"
expect bad-missing err 'cannot open'

# The size is checked before the file is read: RAM + 64 MiB at most. Sparse
# files, so neither takes room on the disk. Trailing bytes are no segment's. (dd
# extends a file to its seek offset; truncate is not in every system)
cp "$ELF/exit.elf" "$WORK/padded.elf" && dd if=/dev/null of="$WORK/padded.elf" bs=1048576 seek=1 2> /dev/null ||
    fail "cannot make padded.elf"
run padded 42 '' +elf="$WORK/padded.elf"
cp "$ELF/exit.elf" "$WORK/huge.elf" && dd if=/dev/null of="$WORK/huge.elf" bs=1048576 seek=1024 2> /dev/null ||
    fail "cannot make huge.elf"
run bad-huge 2 '' +elf="$WORK/huge.elf"
expect bad-huge err 'file of 1073741824 bytes is larger than the limit of [0-9]+ bytes'
mkdir -p "$WORK/dir.elf"
run bad-directory 2 '' +elf="$WORK/dir.elf"
expect bad-directory err 'cannot read .*/dir.elf$'

# A run whose outputs could not all be opened never started: it reports the
# output, and no +vga-dump frame that it did not show; a trace opened by then
# has no trailer.
run not-started 2 '' +elf="$ELF/exit.elf" +vga-dump=1:"$WORK/never.ppm" \
    +uart-log="$WORK/missing/uart.log"
expect not-started err 'cannot write .*/missing/uart.log'
grep -q 'vga-dump' "$WORK/not-started.err" && fail "not-started: a message about +vga-dump frames"
run not-started-obs 2 '' +elf="$ELF/exit.elf" +obs-trace="$WORK/not-started.obs" +obs-task=T1 \
    +obs-variant=test +vga-window
expect not-started-obs err '\+vga-window: '
[ "$(tail -c 8 "$WORK/not-started.obs")" = OBSEND01 ] &&
    fail "not-started-obs: the trace of a run that never started has a trailer"

# Arguments
run no-elf 2 ''
expect no-elf err 'missing \+elf'
run bad-arg 2 '' +elf="$ELF/exit.elf" +max-cycle=10
expect bad-arg err "bad argument '\+max-cycle=10'"
run bad-fill 2 '' +elf="$ELF/exit.elf" +ram-fill=0x100000000
expect bad-fill err "bad argument '\+ram-fill=0x100000000'"
# Numbers are decimal, or hexadecimal after 0x: no octal, sign or space
n=0
for arg in +max-cycles=010 '+max-cycles= 5' +max-cycles=+5 +max-cycles=0x +ram-fill=0x0x5 +ram-fill=0x-5; do
    n=$((n + 1))
    run "bad-number-$n" 2 '' +elf="$ELF/exit.elf" "$arg" &&
        { grep -qF "bad argument '$arg'" "$WORK/bad-number-$n.err" || fail "bad-number-$n: no bad argument '$arg'"; }
done
run hex-number 124 '' +elf="$ELF/hang.elf" +max-cycles=0x7D0

if [ $fails -ne 0 ]; then
    echo "sim check: $fails failure(s) in $count runs of $SIM" >&2
    exit 1
fi
echo "  OK      sim check: $count runs of $SIM"
