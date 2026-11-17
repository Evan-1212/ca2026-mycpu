#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# run_all.sh <elf-dir> <run-dir> <expected-count-file> <command>
#
# Runs every ELF below <elf-dir> with <command> (the ELF path is appended)
# through riscv-arch-test's run_tests.py, in parallel and with a timeout per
# test, then checks the result against <expected-count-file>. tests/act4's
# Makefile calls it for the Verilator model (`make run`) and for Sail (`make
# ref`).
#
# <run-dir> is made anew for each call, so no log or summary of an earlier run
# can be read as this run's: <run-dir>/elfs holds links to the ELFs,
# <run-dir>/logs one log per test, <run-dir>/summary.log one line per test.
#
# The run fails (exit 1) unless all of these hold:
#   - run_tests.py exits 0: every test exits 0 and prints exactly the
#     RVCP-SUMMARY line of a pass (FAILED, SIGRUN, no summary line, a nonzero
#     exit code and a timeout are failures);
#   - summary.log has one line per ELF, and each one is a pass;
#   - every suite that must run ran exactly the number of tests that
#     <expected-count-file> gives for it, and no other suite ran. The suites
#     that must run are all those of the file, or those of SUITES.
# An empty ELF set therefore fails.
#
# Environment:
#   ACT_DIR  the riscv-arch-test clone (required)
#   MISE     the mise executable; empty or unset: run_tests.py runs under
#            `uv run` from PATH (the script needs Python >= 3.10)
#   JOBS     tests in parallel (default: one per core)
#   TIMEOUT  seconds per test (default 600)
#   SUITES   suites that must run, separated by commas or spaces
#            (default: every suite of <expected-count-file>)

set -u -o pipefail

if [ $# -ne 4 ]; then
    echo "usage: $0 <elf-dir> <run-dir> <expected-count-file> <command>" >&2
    exit 2
fi
elf_dir=$1
run_dir=$2
expected=$3
command=$4
: "${ACT_DIR:?run_all.sh: set ACT_DIR to the riscv-arch-test clone}"
JOBS=${JOBS:-$(nproc)}
TIMEOUT=${TIMEOUT:-600}
SUITES=${SUITES:-}

fail() {
    echo "run_all.sh: $*" >&2
    exit 1
}

[ -f "$ACT_DIR/run_tests.py" ] || fail "no run_tests.py in ACT_DIR=$ACT_DIR (run 'make fetch-act4')"
[ -f "$expected" ] || fail "no expected-count file $expected"
[ -d "$elf_dir" ] || fail "no ELF directory $elf_dir (run 'make elfs' first)"
case $run_dir in
    /*) ;;
    *) fail "<run-dir> must be an absolute path, not '$run_dir'" ;;
esac
elf_dir=$(cd "$elf_dir" && pwd)

# A fresh run directory with links to the ELFs. run_tests.py writes its logs and
# summary.log next to the ELF directory it is given.
rm -rf "$run_dir"
mkdir -p "$run_dir/elfs"
elf_count=0
while IFS= read -r -d '' elf; do
    mkdir -p "$run_dir/elfs/${elf%/*}"
    ln -s "$elf_dir/$elf" "$run_dir/elfs/$elf"
    elf_count=$((elf_count + 1))
done < <(cd "$elf_dir" && find . -name '*.elf' -type f -print0)

if [ -n "${MISE:-}" ]; then
    runner=("$MISE" exec -- uv run --frozen)
else
    runner=(uv run --frozen)
fi

rc=0
(cd "$ACT_DIR" && "${runner[@]}" python run_tests.py --jobs "$JOBS" --timeout "$TIMEOUT" \
    "$command" "$run_dir/elfs") || rc=$?

summary=$run_dir/summary.log
[ -f "$summary" ] || : > "$summary" # run_tests.py writes none for an empty ELF set

status=0
awk -v suites="$SUITES" -v elf_count="$elf_count" -v rc="$rc" '
    function bad(message) {
        print "  FAIL: " message
        failed = 1
    }
    FILENAME == ARGV[1] {
        sub(/#.*/, "")
        if (NF == 0)
            next
        if (NF != 2 || $2 !~ /^[0-9]+$/ || ($1 in want)) {
            bad("line " FNR " of the expected-count file is not `<suite> <count>`, or repeats a suite")
            next
        }
        want[$1] = $2
        order[++suite_count] = $1
        next
    }
    {
        # <dir>/<suite>/<test>.log  RVCP-SUMMARY: TEST PASSED - Test File "<test>.S"
        parts = split($1, path, "/")
        suite = parts >= 2 ? path[parts - 1] : "?"
        if (!(suite in ran))
            suites_run++
        ran[suite]++
        lines++
        if ($0 ~ /  RVCP-SUMMARY: TEST PASSED - Test File "[^"]*"$/)
            passed[suite]++
        else
            not_passed[++bad_lines] = $0
    }
    END {
        if (suites != "") {
            gsub(/,/, " ", suites)
            count = split(suites, name, " ")
            for (i = 1; i <= count; i++) {
                required[name[i]] = 1
                if (!(name[i] in want))
                    bad("suite " name[i] " is not in the expected-count file")
            }
        } else {
            for (i = 1; i <= suite_count; i++)
                required[order[i]] = 1
        }

        printf "  %-16s %8s %8s %8s\n", "suite", "expected", "ran", "passed"
        for (i = 1; i <= suite_count; i++) {
            suite = order[i]
            if (!(suite in required) && !(suite in ran))
                continue
            printf "  %-16s %8d %8d %8d\n", suite, want[suite], ran[suite], passed[suite]
            if (!(suite in required))
                bad("suite " suite " ran, but is not one of SUITES (stale ELFs?)")
            else if (ran[suite] + 0 != want[suite])
                bad("suite " suite " ran " (ran[suite] + 0) " tests, expected " want[suite])
            if (passed[suite] + 0 != ran[suite] + 0)
                bad("suite " suite ": " (ran[suite] - passed[suite]) " of " ran[suite] " tests did not pass")
            total_want += (suite in required) ? want[suite] : 0
        }
        for (suite in ran)
            if (!(suite in want))
                bad("suite " suite " ran " ran[suite] " tests, but is not in the expected-count file")
        for (i = 1; i <= bad_lines && i <= 20; i++)
            print "  not passed: " not_passed[i]
        if (total_want == 0)
            bad("no test is expected: the expected-count file is empty")
        if (lines != elf_count)
            bad("summary.log has " lines " lines for " elf_count " ELFs")
        if (rc != 0)
            bad("run_tests.py exited with " rc)
        if (failed)
            exit 1
        print "  PASS: " lines " tests in " suites_run " suites"
    }
' "$expected" "$summary" || status=1

echo "  summary: $summary"
exit "$status"
