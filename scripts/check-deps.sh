#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# Check the host tools ca2026-mycpu needs, and that each one actually works, not
# only that it is on PATH. Called by "make check-deps"; settings come from the
# environment (see the Makefile for the defaults):
#   CROSS          RISC-V GCC prefix, e.g. ~/riscv/toolchain/bin/riscv-none-elf-
#   ACT_CROSS      GCC prefix used for ACT4 (default: CROSS)
#   GCC_MIN        minimum GCC major version
#   VERILATOR_MIN  minimum Verilator version
#   ACT_DIR        riscv-arch-test clone (for its .mise.toml), may not exist yet
#
# Missing tools are expected to be installed under ~/.local (see
# docs/toolchain.md). Exit status: 0 if every required tool works, 1 otherwise.
# Optional tools (T5 only) produce warnings, not failures.

set -u

CROSS=${CROSS:?CROSS not set}
ACT_CROSS=${ACT_CROSS:-$CROSS}
GCC_MIN=${GCC_MIN:-15}
VERILATOR_MIN=${VERILATOR_MIN:-5.042}
ACT_DIR=${ACT_DIR:-externals/riscv-arch-test}
JAVA_MIN=11
JAVA_RECOMMENDED=17
RUBY_MIN=3.2
RUBY_LIMIT=4 # the UDB Gemfile says ruby "~> 3.2", so Ruby 4 is rejected

fail=0
warn=0
tmp=$(mktemp -d) || { echo "check-deps: mktemp failed" >&2; exit 1; }
trap 'rm -rf "$tmp"' EXIT

ok()   { printf "  %-10s %s\n" "$1" "$2"; }
bad()  { printf "  %-10s FAIL: %s\n" "$1" "$2"; fail=1; }
note() { printf "  %-10s WARN: %s\n" "$1" "$2"; warn=1; }

# version_ge A B: true if version A >= version B
version_ge() { [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n1)" = "$2" ]; }
# ruby_ok V: true if Ruby version V is in the window ACT4 accepts
ruby_ok() { version_ge "$1" "$RUBY_MIN" && ! version_ge "$1" "$RUBY_LIMIT"; }

hint_local="install it under ~/.local (~/.local/bin must be on PATH)"

# shellcheck disable=SC2088 # the message names the directory as the user writes it
case ":$PATH:" in
*":$HOME/.local/bin:"*) ;;
*) note PATH "~/.local/bin is not on PATH; tools installed there will not be found" ;;
esac

# basic tools
for t in git curl make; do
    if command -v "$t" >/dev/null 2>&1; then
        ok "$t" "$("$t" --version 2>&1 | head -n1)"
    else
        bad "$t" "not found"
    fi
done

# JDK
if command -v java >/dev/null 2>&1; then
    # the version line, not a "Picked up JAVA_TOOL_OPTIONS" banner
    jv=$(java -version 2>&1 | grep -m1 ' version "')
    jmajor=$(printf '%s\n' "$jv" | sed -E 's/.*version "([0-9]+)(\.[0-9]+)*.*/\1/; s/^1$/8/')
    if ! [ "$jmajor" -ge "$JAVA_MIN" ] 2>/dev/null; then
        bad java "${jv:-java -version printed no version line}: JDK $JAVA_MIN or newer is required"
    elif [ "$jmajor" -ne "$JAVA_RECOMMENDED" ]; then
        note java "$jv: JDK $JAVA_RECOMMENDED is the tested version (D2)"
    else
        ok java "$jv"
    fi
else
    bad java "not found; JDK $JAVA_RECOMMENDED recommended, $hint_local"
fi

# sbt
if command -v sbt >/dev/null 2>&1; then
    sv=$(sbt --script-version 2>/dev/null | tail -n1)
    if [ -n "$sv" ]; then
        ok sbt "launcher $sv (the build pins its own sbt version)"
    else
        bad sbt "the launcher does not report a version; reinstall it, $hint_local"
    fi
else
    bad sbt "not found; $hint_local"
fi

# Verilator: version, then build and run a tiny model with --assert
if command -v verilator >/dev/null 2>&1; then
    vv=$(verilator --version | awk '{print $2}')
    if ! version_ge "$vv" "$VERILATOR_MIN"; then
        bad verilator "$vv < $VERILATOR_MIN; build it from source into ~/.local"
    else
        cat >"$tmp/top.sv" <<'EOF'
module top (
    input logic clk,
    input logic rst
);
  logic [3:0] n;
  always_ff @(posedge clk) begin
    if (rst) n <= 4'd0;
    else begin
      n <= n + 4'd1;
      assert (n < 4'd3) else $fatal(1, "assert fired");
    end
  end
endmodule
EOF
        cat >"$tmp/main.cpp" <<'EOF'
#include "Vtop.h"
#include "verilated.h"
int main(int argc, char **argv) {
    VerilatedContext ctx;
    ctx.commandArgs(argc, argv);
    ctx.fatalOnError(false);
    Vtop top{&ctx};
    for (int i = 0; i < 40 && !ctx.gotFinish(); i++) {
        top.rst = i < 4;
        top.clk = i & 1;
        top.eval();
    }
    return ctx.gotError() ? 3 : 0;
}
EOF
        if ! (cd "$tmp" && verilator --cc --exe --build --assert -j 0 -Wall \
                --Mdir obj top.sv main.cpp >build.log 2>&1); then
            bad verilator "$vv cannot build a model (see: verilator --cc --exe --build)"
            tail -n 15 "$tmp/build.log" | sed 's/^/             /'
        else
            "$tmp/obj/Vtop" >/dev/null 2>&1
            rc=$?
            if [ "$rc" -eq 3 ]; then
                ok verilator "$vv (built and ran a model; --assert works)"
            elif [ "$rc" -eq 126 ] || [ "$rc" -eq 127 ]; then
                bad verilator "$vv built a model that cannot run (exit $rc; is TMPDIR mounted noexec?)"
            else
                bad verilator "$vv built a model, but its assertion did not fire (exit $rc)"
            fi
        fi
    fi
else
    bad verilator "not found; Verilator >= $VERILATOR_MIN, build it from source into ~/.local"
fi

# RISC-V GCC: version, multilibs, a linked rv32i program without M
check_gcc() { # $1 = label, $2 = prefix
    local gcc="$2gcc"
    if ! command -v "$gcc" >/dev/null 2>&1; then
        bad "$1" "$gcc not found; install xPack riscv-none-elf-gcc >= $GCC_MIN"
        return 1
    fi
    local gv
    gv=$("$gcc" -dumpversion | cut -d. -f1)
    if ! [ "$gv" -ge "$GCC_MIN" ] 2>/dev/null; then
        bad "$1" "$("$gcc" --version | head -n1): GCC $GCC_MIN or newer is required"
        return 1
    fi
    ok "$1" "$("$gcc" --version | head -n1)"
}

if check_gcc riscv-gcc "$CROSS"; then
    for a in rv32i rv32i_zicsr; do
        d=$("${CROSS}gcc" -march=$a -mabi=ilp32 -print-multi-directory)
        [ "$d" = rv32i/ilp32 ] ||
            bad riscv-gcc "-march=$a selects multilib '$d', not rv32i/ilp32"
    done
    cat >"$tmp/m.c" <<'EOF'
volatile int a = 1234567, b = 89;
int main(void) { return a / b + a % b + a * b; }
void _start(void) { main(); for (;;); }
EOF
    if "${CROSS}gcc" -march=rv32i_zicsr -mabi=ilp32 -O2 -ffreestanding -nostdlib -static \
        "$tmp/m.c" -lgcc -o "$tmp/m.elf" 2>"$tmp/gcc.log"; then
        # capture first: a failing objdump must not look like "no M instruction"
        if ! dis=$("${CROSS}objdump" -d "$tmp/m.elf") || [ -z "$dis" ]; then
            bad riscv-gcc "${CROSS}objdump cannot disassemble the linked program"
        elif grep -qE '\s(mul|mulh|mulhsu|mulhu|div|divu|rem|remu)\s' <<<"$dis"; then
            bad riscv-gcc "an rv32i_zicsr link pulls M instructions from libgcc"
        fi
    else
        bad riscv-gcc "cannot link an rv32i_zicsr program with libgcc"
    fi
fi

if [ "$ACT_CROSS" != "$CROSS" ]; then
    check_gcc act4-gcc "$ACT_CROSS"
fi
if command -v "${ACT_CROSS}gcc" >/dev/null 2>&1 &&
    ! echo nop | "${ACT_CROSS}gcc" -march=rv32im_zicsr_zicntr_zihpm_zifencei -mabi=ilp32 \
        -x assembler -c - -o /dev/null 2>/dev/null; then
    bad act4-gcc "cannot assemble -march=rv32im_zicsr_zicntr_zihpm_zifencei (ACT4 needs it)"
fi

# --- ACT4 environment: mise (preferred), or uv plus Ruby >= 3.2 and Bundler --
if command -v mise >/dev/null 2>&1; then
    ok mise "$(mise --version 2>/dev/null | head -n1)"
    if [ -f "$ACT_DIR/.mise.toml" ]; then

        # "mise which" resolves the pinned tools without running mise's
        # dependency hooks ("mise exec" would start ACT4's bundle install).
        if ruby_bin=$(cd "$ACT_DIR" && mise which ruby 2>/dev/null) &&
            bundle_bin=$(cd "$ACT_DIR" && mise which bundle 2>/dev/null) &&
            uv_bin=$(cd "$ACT_DIR" && mise which uv 2>/dev/null) &&
            rv=$("$ruby_bin" -e 'print RUBY_VERSION') &&
            bv=$(PATH="$(dirname "$ruby_bin"):$PATH" "$bundle_bin" --version) &&
            uvv=$("$uv_bin" --version); then
            if ruby_ok "$rv"; then
                ok act4-env "ruby $rv, $bv, ${uvv%% (*} (through mise, from $ACT_DIR/.mise.toml)"
            else
                bad act4-env "mise provides ruby $rv, need >= $RUBY_MIN and < $RUBY_LIMIT"
            fi
        else
            bad act4-env "mise tools missing; run: cd $ACT_DIR && mise trust .mise.toml && mise install"
        fi
    else
        note act4-env "$ACT_DIR not fetched yet (make fetch-act4); cannot check ACT4's mise tools"
    fi
else
    if command -v uv >/dev/null 2>&1; then
        ok uv "$(uv --version)"
    else
        bad uv "not found; ACT4 needs uv or mise, $hint_local"
    fi
    rv=$(ruby -e 'print RUBY_VERSION' 2>/dev/null)
    if [ -n "$rv" ] && ruby_ok "$rv"; then
        if bv=$(bundle --version 2>/dev/null); then
            ok act4-env "ruby $rv, $bv"
        else
            bad act4-env "Bundler not found (ACT4's UDB gem needs it); or install mise"
        fi
    else
        bad act4-env "ACT4 needs Ruby >= $RUBY_MIN and < $RUBY_LIMIT with Bundler; install mise into ~/.local/bin and run mise install in $ACT_DIR"
    fi
fi

# optional, T5 only
if command -v sdl2-config >/dev/null 2>&1; then
    ok sdl2 "$(sdl2-config --version) (T5 VGA window; SDL=0 works without it)"
else
    note sdl2 "not found; T5's VGA window needs SDL2 (SDL=0 uses PPM dumps instead)"
fi
if command -v qrencode >/dev/null 2>&1; then
    ok qrencode "$(qrencode --version 2>&1 | head -n1) (T5-10 matrix check)"
else
    note qrencode "not found; T5-10's matrix check needs it"
fi

[ $warn -eq 0 ] || echo "  (warnings do not fail the check)"
exit $fail
