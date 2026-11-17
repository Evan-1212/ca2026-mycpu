#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# run_one.sh <elf>
#
# Runs one ACT4 ELF on a task's Verilator model (sim/main.cpp) and passes its
# console and its exit code through: 0 is a pass (the test stored 0 to SimCtrl
# EXIT), 1 a failed self-check, 64 + c a halt of the core, 124 the cycle limit
# (see sim/main.cpp for the rest). ACT4's run_tests.py appends the ELF path to
# the command of cores/<core>/run_cmd.txt, which names this script.
#
# Environment:
#   SIM         the model, e.g. build/t3/Vsim (required)
#   MAX_CYCLES  +max-cycles of the harness (default 50000000)
#   SIM_ARGS    more harness arguments, split at spaces, e.g. "+ram-fill=0xdeadbeef".
#               They are the same for every ELF, and run_tests.py runs many at
#               once, so an output file (+retire-log=, +trace=) would be written
#               by all of them: run one ELF by hand for that.

set -u

if [ $# -ne 1 ]; then
    echo "usage: SIM=<path to Vsim> $0 <elf>" >&2
    exit 2
fi
if [ -z "${SIM:-}" ]; then
    echo "run_one.sh: set SIM to the Verilator model of the task (its Vsim)" >&2
    exit 2
fi
if [ ! -x "$SIM" ]; then
    echo "run_one.sh: SIM=$SIM is not an executable file" >&2
    exit 2
fi

# shellcheck disable=SC2086 # SIM_ARGS is a list of arguments
exec "$SIM" "+elf=$1" "+max-cycles=${MAX_CYCLES:-50000000}" ${SIM_ARGS:-}
