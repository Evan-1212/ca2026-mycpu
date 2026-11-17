#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# selftest.sh <elf-dir> <expected-count-file> <sail.json>
#
# Proves that the ACT4 runner of this directory (run_all.sh, with
# riscv-arch-test's run_tests.py behind it) can fail. One small run on Sail must
# pass; every doctored run after it must fail, and for the reason the case is
# about, which is looked up in the run's output. `make selftest` calls this
# script after a full `make elfs`.
#
# The doctored runs replace the simulator by a few lines of shell, or run ELFs
# that were changed on purpose: one whose expected signature is wrong (the
# self-check must report it), one that never stores to SimCtrl EXIT.
#
# The provenance check of tests/act4/Makefile (`check-elfs`, which `run`, `ref`
# and `expected-count` require) is tried on copies of the ELF set, too.
#
# Environment: ACT_DIR, MISE and JOBS as for run_all.sh; WORKDIR (the cases are
# written to $WORKDIR/selftest); TASK (the core of the ELFs, for the provenance
# cases); SAIL_SIM and RISCV as tests/act4/Makefile has them (the provenance
# records the tools' versions); HALT_SIM, optional: a Verilator model of a core
# without CSRs (T1 or T2), on which every ACT4 ELF must end in a halt that the
# runner reports as a failure.

set -u -o pipefail

if [ $# -ne 3 ]; then
    echo "usage: $0 <elf-dir> <expected-count-file> <sail.json>" >&2
    exit 2
fi
elf_dir=$1
expected_all=$2
sail_json=$3
: "${ACT_DIR:?selftest.sh: set ACT_DIR to the riscv-arch-test clone}"
: "${WORKDIR:?selftest.sh: set WORKDIR to the ACT4 build directory}"
: "${TASK:?selftest.sh: set TASK to the task of the ELFs}"
: "${SAIL_SIM:?selftest.sh: set SAIL_SIM as tests/act4/Makefile does}"
: "${RISCV:?selftest.sh: set RISCV as tests/act4/Makefile does}"
here=$(cd "$(dirname "$0")" && pwd)
top=$WORKDIR/selftest
READELF=${READELF:-riscv-none-elf-readelf}
NM=${NM:-riscv-none-elf-nm}

# The sample: three short tests of two suites.
sample=(rv32i/Zifencei/Zifencei-fence.i-00.elf rv32i/Zicntr/Zicntr-csrrc-00.elf rv32i/Zicntr/Zicntr-csrrs-00.elf)
for elf in "${sample[@]}"; do
    if [ ! -f "$elf_dir/$elf" ]; then
        echo "selftest.sh: $elf_dir/$elf is missing; run a full 'make elfs' first" >&2
        exit 2
    fi
done
for suite in Zifencei Zicntr; do
    if ! grep -q "^$suite " "$expected_all"; then
        echo "selftest.sh: $expected_all has no line for suite $suite" >&2
        exit 2
    fi
done

rm -rf "$top"
mkdir -p "$top/bin"
failures=0
cases=0

# Stand-ins for a simulator. Each takes the ELF path as its last argument.
stand_in() { # <name> <body>
    printf '#!/usr/bin/env bash\n%s\n' "$2" > "$top/bin/$1"
    chmod +x "$top/bin/$1"
}
pass_line='echo "RVCP-SUMMARY: TEST PASSED - Test File \"$(basename "$1" .elf).S\""'
stand_in pass-exit1 "$pass_line; exit 1"
stand_in failed-exit0 'echo "RVCP-SUMMARY: TEST FAILED - Test File \"$(basename "$1" .elf).S\""; exit 0'
stand_in sigrun-exit0 'echo "RVCP-SUMMARY: TEST SIGRUN - Test File \"$(basename "$1" .elf).S\""; exit 0'
stand_in silent-exit0 'exit 0'
stand_in sleeper "sleep 30; $pass_line"
stand_in pass-twice-failed "$pass_line"'; echo "RVCP-SUMMARY: TEST FAILED - Test File \"x.S\""'

sail_cmd="SAIL_CONFIG=\"$sail_json\" \"$here/ref_one.sh\""

# A directory of ELFs for one case: the listed files of the sample.
elf_set() { # <case> <elf>...
    local dir=$top/$1/src elf
    shift
    mkdir -p "$dir"
    for elf in "$@"; do
        mkdir -p "$dir/$(dirname "$elf")"
        cp "$elf_dir/$elf" "$dir/$elf"
    done
    echo "$dir"
}

# The file offset of a virtual address in an ELF, from its section table.
file_offset() { # <elf> <address in hex>
    local target=$((16#$2)) type addr off size _
    while read -r type addr off size _; do
        if [ "$type" = PROGBITS ] && [ "$target" -ge $((16#$addr)) ] && [ "$target" -lt $((16#$addr + 16#$size)) ]; then
            echo $((16#$off + target - 16#$addr))
            return
        fi
    done < <("$READELF" -S -W "$1" | sed -n -e 's/^ *\[ *[0-9]*\] *[^ ]* *//p')
}

symbol() { # <elf> <name>
    "$NM" "$1" | awk -v name="$2" '$3 == name { print $1 }'
}

# Overwrite one little-endian word of an ELF.
patch_word() { # <elf> <address in hex> <word, a shell number>
    local offset word=$(($3))
    offset=$(file_offset "$1" "$2")
    [ -n "$offset" ] || { echo "selftest.sh: no section of $1 holds address $2" >&2; exit 2; }
    # shellcheck disable=SC2059 # the format is the four bytes
    printf "$(printf '\\x%02x\\x%02x\\x%02x\\x%02x' \
        $((word & 255)) $((word >> 8 & 255)) $((word >> 16 & 255)) $((word >> 24 & 255)))" |
        dd of="$1" bs=1 seek="$offset" conv=notrunc 2> /dev/null # status=none is not in every dd
}

# Make the first instruction of an ELF `j <symbol>`, so that it runs nothing of
# the test but the code at that symbol.
patch_entry_jump() { # <elf> <symbol>
    local entry target offset
    entry=$(symbol "$1" rvtest_entry_point)
    target=$(symbol "$1" "$2")
    [ -n "$entry" ] && [ -n "$target" ] || { echo "selftest.sh: no rvtest_entry_point or $2 in $1" >&2; exit 2; }
    offset=$((0x$target - 0x$entry))
    [ "$offset" -gt 0 ] && [ "$offset" -lt 1048576 ] || { echo "selftest.sh: $2 is out of reach of a jal" >&2; exit 2; }
    # jal x0: imm[20|10:1|11|19:12], rd = 0, opcode 0x6f
    patch_word "$1" "$entry" $(((offset >> 20 & 1) << 31 | (offset >> 1 & 0x3ff) << 21 |
        (offset >> 11 & 1) << 20 | (offset >> 12 & 0xff) << 12 | 0x6f))
}

# Count a failure unless the output of a case that ran matches a pattern.
require() { # <case> <pattern> <what it means>
    if ! grep -q -e "$2" "$top/$1.out"; then
        echo "  FAIL  $1: $3 (see $top/$1.out)"
        failures=$((failures + 1))
    fi
}

# Run one case and compare with what it must do.
check() { # <case> <pass|fail> <text the output must contain> <elf dir> <expected file> <command> [VAR=value...]
    local name=$1 want=$2 text=$3 dir=$4 expected=$5 command=$6 rc=0 got
    shift 6
    cases=$((cases + 1))
    env "$@" "$here/run_all.sh" "$dir" "$top/$name/run" "$expected" "$command" > "$top/$name.out" 2>&1 || rc=$?
    got=pass
    [ "$rc" -eq 0 ] || got=fail
    if [ "$got" != "$want" ]; then
        echo "  FAIL  $name: the run must $want, but it exited with $rc (see $top/$name.out)"
        failures=$((failures + 1))
    elif ! grep -q -e "$text" "$top/$name.out"; then
        echo "  FAIL  $name: the run did $want, but its output lacks '$text' (see $top/$name.out)"
        failures=$((failures + 1))
    else
        echo "  ok    $name: must $want, and did ($text)"
    fi
}

expected=$top/expected
grep -e '^Zifencei ' -e '^Zicntr ' "$expected_all" > "$expected"
all=$(elf_set sample "${sample[@]}")

echo "selftest: the runner on a sample of ${#sample[@]} ELFs (cases in $top)"

# The sample passes on Sail: the cases below fail for their own reason only.
check sail-pass pass "PASS: 3 tests in 2 suites" "$all" "$expected" "$sail_cmd"

# The simulator's exit code and console.
check exit-nonzero fail "TEST PASSED but exit code 1" "$all" "$expected" "$top/bin/pass-exit1"
check prints-failed fail "TEST FAILED but exit code 0" "$all" "$expected" "$top/bin/failed-exit0"
check prints-sigrun fail "reports SIGRUN" "$all" "$expected" "$top/bin/sigrun-exit0"
check prints-nothing fail "no RVCP-SUMMARY line found" "$all" "$expected" "$top/bin/silent-exit0"
check passed-then-failed fail "TEST FAILED but exit code 0" "$all" "$expected" "$top/bin/pass-twice-failed"
check timeout fail "timed out after 2s" "$all" "$expected" "$top/bin/sleeper" TIMEOUT=2

# The set of tests that ran.
mkdir -p "$top/empty/src"
check no-elfs fail "ran 0 tests, expected" "$top/empty/src" "$expected" "$sail_cmd"
check one-elf-missing fail "suite Zicntr ran 1 tests, expected 2" "$(elf_set fewer "${sample[0]}" "${sample[1]}")" "$expected" "$sail_cmd"
grep -e '^Zifencei ' "$expected_all" > "$top/expected-zifencei"
check suite-not-expected fail "suite Zicntr ran 2 tests, but is not in the expected-count file" "$all" "$top/expected-zifencei" "$sail_cmd"
check suite-not-requested fail "suite Zicntr ran, but is not one of SUITES" "$all" "$expected" "$sail_cmd" SUITES=Zifencei
: > "$top/expected-empty"
check nothing-expected fail "no test is expected" "$all" "$top/expected-empty" "$sail_cmd"

# An ELF whose expected signature is wrong: the test's own check must fail,
# print the summary of a failure and store 1 to EXIT. The word after the canary
# at begin_signature is the first expected value.
doctored=$(elf_set wrong-signature "${sample[@]}")
victim=$doctored/${sample[0]}
signature=$(symbol "$victim" begin_signature)
[ -n "$signature" ] || { echo "selftest.sh: no begin_signature in $victim" >&2; exit 2; }
patch_word "$victim" "$(printf '%x' $((0x$signature + 4)))" 0xa55aa55a
check wrong-signature fail "TEST FAILED" "$doctored" "$expected" "$sail_cmd"
require wrong-signature "exit code 1$" "the doctored ELF did not exit with 1"

# An ELF that never stores to EXIT: `sw zero, 0(t0)` of RVMODEL_HALT_PASS, the
# instruction before mycpu_halted, becomes a nop. It prints its pass and then
# never ends, which is a failure.
doctored=$(elf_set no-exit "${sample[@]}")
victim=$doctored/${sample[0]}
halted=$(symbol "$victim" mycpu_halted)
[ -n "$halted" ] || { echo "selftest.sh: no mycpu_halted in $victim" >&2; exit 2; }
patch_word "$victim" "$(printf '%x' $((0x$halted - 4)))" 0x00000013
check no-exit fail "TEST PASSED but exit code 124" "$doctored" "$expected" "$sail_cmd"

# The provenance check: a copy of the whole ELF set and its provenance, in a
# build directory of its own, is accepted as it is and refused once doctored.
check_provenance() { # <case> <pass|fail> <text the output must contain, or "" for none> [make variable...]
    local name=$1 want=$2 text=$3 rc=0 got=pass
    shift 3
    cases=$((cases + 1))

    # A make of its own: not the parent's jobserver (its warning would be
    # output) nor the parent's command-line variables (EXTENSIONS, ...).
    env -u MAKEFLAGS -u MFLAGS -u MAKELEVEL "${MAKE:-make}" --no-print-directory -f "$here/Makefile" check-elfs TASK="$TASK" BUILD="$top/$name" \
        SAIL_SIM="$SAIL_SIM" RISCV="$RISCV" "$@" \
        > "$top/$name.out" 2>&1 || rc=$?
    [ "$rc" -eq 0 ] || got=fail
    if [ "$got" != "$want" ] || { [ -n "$text" ] && ! grep -q -e "$text" "$top/$name.out"; } ||
        { [ -z "$text" ] && [ -s "$top/$name.out" ]; }; then
        echo "  FAIL  $name: check-elfs must $want with '$text', but exited with $rc (see $top/$name.out)"
        failures=$((failures + 1))
    else
        echo "  ok    $name: must $want, and did (${text:-no output})"
    fi
}
provenance_copy() { # <case>: a copy of the ELF set; prints its ELF directory
    local dir=$top/$1/act4/mycpu-t$TASK
    mkdir -p "$dir"
    cp -r "$elf_dir" "$dir/elfs"
    cp "$elf_dir.provenance" "$dir/elfs.provenance"
    echo "$dir/elfs"
}
if [ -f "$elf_dir.provenance" ]; then
    provenance_copy provenance-intact > /dev/null
    check_provenance provenance-intact pass ""
    copy=$(provenance_copy provenance-renamed)
    mv "$copy/${sample[1]}" "${copy}/${sample[1]%.elf}-renamed.elf"
    check_provenance provenance-renamed fail "were changed since"
    copy=$(provenance_copy provenance-swapped)
    cp "$copy/${sample[1]}" "$copy/${sample[2]}"
    check_provenance provenance-swapped fail "were changed since"
    provenance_copy provenance-extensions > /dev/null
    check_provenance provenance-extensions fail "^> extensions I$" EXTENSIONS=I
    copy=$(provenance_copy provenance-config)
    sed -e 's/^config .*/config 0/' "${copy%/elfs}/elfs.provenance" > "${copy%/elfs}/elfs.provenance.new" &&
        mv "${copy%/elfs}/elfs.provenance.new" "${copy%/elfs}/elfs.provenance"
    check_provenance provenance-config fail "^< config 0$"
else
    echo "selftest.sh: no $elf_dir.provenance; run 'make elfs' first" >&2
    exit 2
fi

# On a Verilator model of a core without CSRs (T1 or T2):
if [ -n "${HALT_SIM:-}" ]; then
    model_cmd="SIM=\"$HALT_SIM\" MAX_CYCLES=1000000 \"$here/run_one.sh\""

    # every test halts at its first CSR instruction, and that is a failure;
    check halt-on-model fail "exit code 6[4-9] indicates failure, no RVCP-SUMMARY line found" "$all" "$expected" "$model_cmd"
    check max-cycles fail "exit code 124 indicates failure" "$all" "$expected" \
        "SIM=\"$HALT_SIM\" MAX_CYCLES=3 \"$here/run_one.sh\""
    check no-model fail "exit code 2 indicates failure" "$all" "$expected" \
        "SIM=\"$top/bin/no-such-model\" \"$here/run_one.sh\""

    # ELFs that jump from their entry straight to the code that reports a pass,
    # which uses no CSR, run RVMODEL_IO_WRITE_STR and RVMODEL_HALT_PASS on the
    # real harness: the console and EXIT of SimCtrl work as the macros and the
    # linker script assume;
    doctored=$(elf_set model-pass-path "${sample[@]}")
    for elf in "${sample[@]}"; do
        patch_entry_jump "$doctored/$elf" exit_cleanup
    done
    check model-pass-path pass "PASS: 3 tests in 2 suites" "$doctored" "$expected" "$model_cmd"

    # and one that jumps to the report of a failure runs RVMODEL_HALT_FAIL.
    doctored=$(elf_set model-fail-path "${sample[@]}")
    patch_entry_jump "$doctored/${sample[0]}" rvtest_fail_summary
    for elf in "${sample[@]:1}"; do
        patch_entry_jump "$doctored/$elf" exit_cleanup
    done
    check model-fail-path fail "suite Zifencei: 1 of 1 tests did not pass" "$doctored" "$expected" "$model_cmd"
    require model-fail-path "exit code 1$" "the doctored ELF did not exit with 1"
fi

if [ "$failures" -ne 0 ]; then
    echo "selftest: $failures of $cases cases did not behave as required"
    exit 1
fi
echo "selftest: all $cases cases behave as required"
