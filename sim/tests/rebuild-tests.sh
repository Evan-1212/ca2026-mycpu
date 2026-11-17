#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Checks the dependency rules of sim.mk's sim_topgen: when TopGen runs again,
# and that it does not otherwise. Usage: rebuild-tests.sh <work dir> sbt is
# replaced by a script that writes the two outputs and counts its runs, so this
# takes a second; the real TopGen runs in the rest of `make check`.

set -u
WORK=$1
SIM_DIR=$(cd "$(dirname "$0")/.." && pwd)
unset MAKEFLAGS MFLAGS # not the jobs and variables of the make that runs this
fails=0

fail() {
    echo "  FAIL    rebuild: $*"
    fails=$((fails + 1))
}

rm -rf "$WORK"
mkdir -p "$WORK/src"
touch "$WORK/src/Top.scala"

# `sbt -batch "<command>"`: the last word of the command is the directory
cat > "$WORK/sbt" << 'EOF'
#!/bin/sh
gen=${2##* }
echo "$2" >> "$gen/../runs"
echo "// $2" > "$gen/SimTop.sv"
[ -n "${NO_HEADER:-}" ] || echo "// $2" > "$gen/sim_config.h"
EOF
chmod +x "$WORK/sbt"

cat > "$WORK/Makefile" << EOF
include $SIM_DIR/sim.mk
VARIANT ?= a
\$(eval \$(call sim_topgen,$WORK/gen,emit \$(VARIANT) $WORK/gen,\$(wildcard $WORK/src/*.scala)))
gen: $WORK/gen/SimTop.sv $WORK/gen/sim_config.h
deps:
	@printf '%s\n' \$(SIM_SCALA_DEPS)
EOF

run_make() {
    make -s -f "$WORK/Makefile" SBT="$WORK/sbt" "$@"
}

# step <TopGen runs expected> <what was done> [make arguments...]
step() {
    local want=$1 what=$2
    shift 2
    : > "$WORK/runs"
    if ! run_make gen "$@" > "$WORK/log" 2>&1; then
        fail "$what: make failed"
        sed 's/^/          /' "$WORK/log"
        return
    fi
    local got
    got=$(wc -l < "$WORK/runs")
    if [ "$got" -ne "$want" ] || [ ! -f "$WORK/gen/SimTop.sv" ] || [ ! -f "$WORK/gen/sim_config.h" ]; then
        fail "$what: TopGen ran $got time(s), expected $want"
    fi
}

step 1 "the first build"
step 0 "nothing changed"
rm "$WORK/gen/sim_config.h"
step 1 "sim_config.h deleted"
rm "$WORK/gen/SimTop.sv"
step 1 "SimTop.sv deleted"
step 0 "nothing changed after the deletions"

# A change to any elaboration input (-W: make takes the file as just modified)
step 1 "the top's own source changed" -W "$WORK/src/Top.scala"
for pattern in '/build\.sbt$' '/project/build\.properties$' '/common/src/main/scala/mycpu/common/Uart\.scala$' \
    '/riscv/src/main/scala/riscv/UartTx\.scala$'; do
    file=$(run_make deps | grep -E "$pattern")
    if [ -z "$file" ]; then
        fail "no prerequisite matches $pattern"
        continue
    fi
    step 1 "$file changed" -W "$file"
done
step 0 "nothing changed after the changes"

# The command and the set of sources
step 1 "the sbt command changed" VARIANT=b
step 0 "the same command again" VARIANT=b
touch -t 200001010000 "$WORK/src/Old.scala" # older than the outputs
step 1 "a source file added" VARIANT=b
rm "$WORK/src/Old.scala"
step 1 "a source file removed" VARIANT=b
step 0 "nothing changed after that" VARIANT=b

# A generator that writes only one of the two files is an error
rm "$WORK/gen/sim_config.h"
if NO_HEADER=1 run_make gen VARIANT=b > "$WORK/log" 2>&1 || ! grep -q 'did not write' "$WORK/log"; then
    fail "a missing sim_config.h after TopGen was not reported"
fi

if [ $fails -ne 0 ]; then
    echo "sim rebuild tests: $fails failure(s)" >&2
    exit 1
fi
echo "  OK      sim rebuild tests"
