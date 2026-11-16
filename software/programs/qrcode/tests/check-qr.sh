#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Matrix check of the QR encoder (T5-10, step 1): build programs/qrcode for
# rv32emu, run it, and compare every printed symbol, mask included, with
#   qrencode -8 -l <L|M|Q|H> -t ASCII -m 0
# for the default text (qr_text.h) and for each line of tests/strings.txt.
#
# When a text does not fit in version 5 at some level, the encoder prints
# "version=none"; the check then requires qrencode to need a larger symbol. When
# only the mask differs, the symbol is rebuilt with qrencode's mask forced
# (QR_MASK); the input must then be recorded in tests/README.md as "string <n>
# ecc <L>" (n counts lines of strings.txt from 0, or "default").
#
# The check cannot pass without comparing: a failed build, emulator run or
# qrencode call, a missing or repeated header, and a symbol that is not a square
# of the announced size are all failures, and the number of symbols checked must
# be 4 for the default text plus 4 per line of strings.txt.
#
# Environment: ARCH (default rv32im_zicsr), QR_GF (table or bitwise), RV32EMU
# (default: the repository's externals/rv32emu/build/rv32emu).

set -u -o pipefail

tests=$(cd "$(dirname "$0")" && pwd)
sw=$(cd "$tests/../../.." && pwd)
arch=${ARCH:-rv32im_zicsr}
gf=${QR_GF:-table}
emu=${RV32EMU:-$sw/../externals/rv32emu/build/rv32emu}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

for tool in "$emu" qrencode; do
    if ! command -v "$tool" > /dev/null; then
        echo "check-qr: $tool not found" >&2
        exit 1
    fi
done

# build <name> [make variables...]: build qrcode (in one build directory, so
# only what the variables change is rebuilt) and run it into $tmp/<name>.out.
# The program must exit with 0. The QR_* variables are set empty first, so that
# only "$@" chooses them: under `make check-qr QR_TEXT=...`, MAKEFLAGS would
# otherwise hand the outer command line's values to this make.
build() {
    local name=$1
    shift
    make -s -C "$sw" PLATFORM=rv32emu ARCH="$arch" QR_GF="$gf" \
        BUILD="$tmp/build" QR_TEXT= QR_MASK= QR_STRINGS= "$@" qrcode \
        > "$tmp/$name.log" 2>&1 || {
        cat "$tmp/$name.log"
        echo "check-qr: build failed" >&2
        exit 1
    }
    "$emu" -q "$tmp/build/sw/rv32emu/$arch/qrcode.elf" > "$tmp/$name.out" || {
        echo "check-qr: $emu failed on the $name build (exit code $?)" >&2
        exit 1
    }
}

# symbol <out file> <header>: print the matrix that follows the header line.
symbol() {
    awk -v h="$2" '$0 == h { p = 1; next } p && /^[#.]+$/ { print; next } { p = 0 }' "$1"
}

# header <out file> <prefix> <level>: the header line of that symbol. Fails
# unless there is exactly one.
header() {
    local h
    h=$(grep -E "^$2ecc=$3 " "$1") && [ "$(wc -l <<< "$h")" -eq 1 ] && echo "$h"
}

# matrix_size <file>: print n when the file is an n x n matrix of '#' and '.'
# with n at least 21 (version 1); fail otherwise.
matrix_size() {
    awk 'NR == 1 { n = length($0) }
         !/^[#.]+$/ || length($0) != n { bad = 1 }
         END { if (bad || NR != n || n < 21) exit 1; print n }' "$1"
}

# qrencode's symbol for a text, normalized to one '#' or '.' per module.
reference() {
    printf '%s' "$1" | qrencode -8 -l "$2" -t ASCII -m 0 -o - |
        sed 's/##/#/g; s/  /./g'
}

# The mask in the format information of a normalized symbol (first copy).
format_mask() {
    local -a row
    mapfile -t row < "$1"
    local bits=0 i
    for i in 0 1 2 3 4 5; do
        [ "${row[i]:8:1}" = '#' ] && bits=$((bits | 1 << i))
    done
    [ "${row[7]:8:1}" = '#' ] && bits=$((bits | 1 << 6))
    [ "${row[8]:8:1}" = '#' ] && bits=$((bits | 1 << 7))
    [ "${row[8]:7:1}" = '#' ] && bits=$((bits | 1 << 8))
    for i in 9 10 11 12 13 14; do
        [ "${row[8]:$((14 - i)):1}" = '#' ] && bits=$((bits | 1 << i))
    done
    echo $((((bits ^ 0x5412) >> 10) & 7))
}

pass=0
recorded=0
fail=0

failed() {
    echo "FAIL $*"
    fail=$((fail + 1))
}

# check_level <id> <text> <out file> <prefix> <level>: compare one symbol and
# count it as exactly one of pass, recorded or fail.
check_level() {
    local id=$1 text=$2 out=$3 prefix=$4 level=$5 h size

    if ! reference "$text" "$level" > "$tmp/ref" ||
        ! size=$(matrix_size "$tmp/ref"); then
        failed "$id ecc $level: qrencode failed or printed no square symbol"
        return
    fi
    if ! h=$(header "$out" "$prefix" "$level"); then
        failed "$id ecc $level: the output does not have exactly one header"
        return
    fi
    if [ "$h" = "${prefix}ecc=$level version=none" ]; then
        if [ "$size" -gt 37 ]; then
            pass=$((pass + 1))
        else
            failed "$id ecc $level: encoder gave up, qrencode fits in version 5"
        fi
        return
    fi
    if [[ ! $h =~ \ version=([1-5])\ mask=([0-7])$ ]]; then
        failed "$id ecc $level: malformed header '$h'"
        return
    fi
    local version=${BASH_REMATCH[1]} our_mask=${BASH_REMATCH[2]}

    symbol "$out" "$h" > "$tmp/mine"
    if ! size=$(matrix_size "$tmp/mine") || [ "$size" -ne $((17 + 4 * version)) ]; then
        failed "$id ecc $level: the symbol is not a version $version square"
        return
    fi
    if cmp -s "$tmp/mine" "$tmp/ref"; then
        pass=$((pass + 1))
        return
    fi

    local ref_mask fh
    ref_mask=$(format_mask "$tmp/ref")
    if [ "$ref_mask" != "$our_mask" ]; then
        [ -f "$tmp/mask$ref_mask.out" ] ||
            build "mask$ref_mask" QR_MASK="$ref_mask" "${forced_vars[@]}"
        if fh=$(header "$tmp/mask$ref_mask.out" "$prefix" "$level") &&
            symbol "$tmp/mask$ref_mask.out" "$fh" > "$tmp/forced" &&
            cmp -s "$tmp/forced" "$tmp/ref"; then
            if grep -q "string $id ecc $level" "$tests/README.md"; then
                recorded=$((recorded + 1))
            else
                failed "$id ecc $level: mask $our_mask, qrencode chose $ref_mask;" \
                    "the symbols match with the mask forced, but the input is not" \
                    "recorded in tests/README.md"
            fi
            return
        fi
    fi
    failed "$id ecc $level: symbol differs from qrencode (mask $our_mask, qrencode $ref_mask)"
    diff "$tmp/ref" "$tmp/mine" | head -10
}

# check <id> <text> <out file> <prefix>: compare the four levels.
check() {
    local level
    for level in L M Q H; do
        check_level "$@" "$level"
    done
}

# The default text.
default_text=$(sed -n 's/^#define QR_TEXT "\(.*\)"$/\1/p' "$tests/../qr_text.h")
if [ -z "$default_text" ]; then
    echo "check-qr: no default QR_TEXT in qr_text.h" >&2
    exit 1
fi
forced_vars=()
build default
check default "$default_text" "$tmp/default.out" ""
rm -rf "$tmp"/mask*  # forced-mask builds of the default text

# The strings, compiled into one table (escaped as C string literals).
sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g; s/.*/"&",/' "$tests/strings.txt" > "$tmp/strings.inc"
if [ ! -s "$tmp/strings.inc" ]; then
    echo "check-qr: $tests/strings.txt is missing or empty" >&2
    exit 1
fi
forced_vars=(QR_STRINGS="$tmp/strings.inc")
build strings "${forced_vars[@]}"
n=0
while IFS= read -r text || [ -n "$text" ]; do
    check "$n" "$text" "$tmp/strings.out" "string=$n "
    n=$((n + 1))
done < "$tests/strings.txt"

# Counted independently of the loop above: 4 levels for the default text and for
# each line of strings.txt.
want=$((4 * (1 + $(grep -c '' "$tests/strings.txt"))))
if [ $((pass + recorded + fail)) -ne "$want" ]; then
    failed "counted $((pass + recorded + fail)) symbols, expected $want"
fi
echo "check-qr ($arch, QR_GF=$gf): $pass symbols match, $recorded recorded mask" \
    "disagreements, $fail failures ($want symbols)"
[ "$fail" = 0 ]
