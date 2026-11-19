#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

# What the Git hooks and scripts/chain.sh need on this machine, checked by using
# each tool, not by finding its name: `make prereq`, `make hooks` and the CI run
# it. It only looks: it installs nothing, and every failure names the fix. (The
# tools of the labs themselves are checked by `make check-deps`.)
#
#   required   bash 4 or newer; git with `interpret-trailers` and trailer formats that give the
#              values of one key, joined (the hash chain reads the Change-Ids with them); a C
#              compiler that builds tools/hashchain.c, whose hash passes its own self-test
#   optional   aspell with its English dictionary (the spelling of commit messages), clang-format
#              (the formatting of C and C++ in the pre-commit hook), python3 (its script syntax
#              check); a missing one is reported as skipped, with what is then not checked
#
# Exit status: 0 when every required tool works, 1 otherwise.

set -u

here=$(cd "$(dirname "$0")" && pwd)
fail=0
tmp=$(mktemp -d) || { echo "check-prereq: mktemp failed" >&2; exit 1; }
trap 'rm -rf "$tmp"' EXIT

ok()   { printf "  %-12s %s\n" "$1" "$2"; }
bad()  { printf "  %-12s FAIL: %s\n" "$1" "$2"; fail=1; }
skip() { printf "  %-12s skipped: %s\n" "$1" "$2"; }

# bash: the hooks use associative arrays
if [ "${BASH_VERSINFO[0]}" -ge 4 ]; then
  ok bash "${BASH_VERSION%%(*}"
else
  bad bash "${BASH_VERSION%%(*} is too old: the hooks need bash 4 or newer (macOS: brew install bash)"
fi

# git: version, then the features, used in a scratch repository
if command -v git > /dev/null 2>&1; then
  gv=$(git --version)
  probe=$(
    set -e
    cd "$tmp"
    git init -q probe
    cd probe
    git config user.name t
    git config user.email t@example.com
    git config commit.gpgsign false
    echo x > f
    git add f
    printf 'Subject line here\n\nBody.\n\nChange-Id: Iaa\nChange-Id: Ibb\n' | git -c core.hooksPath=/dev/null commit -q -F -
    git log -1 --format='%(trailers:key=Change-Id,valueonly,separator=%x2C)'
    printf 'a  \n\n\nb\n' | git stripspace > /dev/null
    git interpret-trailers --trailer "Change-Id: Icc" < /dev/null > /dev/null
  ) 2> /dev/null
  if [ "$probe" = "Iaa,Ibb" ]; then
    ok git "${gv#git version }"
  else
    bad git "${gv#git version } does not give the trailers of a commit as the chain reads them: install git 2.30 or newer"
  fi
else
  bad git "not found: install git"
fi

# a C compiler: hashchain.c builds, and its SHA-256 passes its self-test
cc_cmd=${CC:-cc}
if command -v "$cc_cmd" > /dev/null 2>&1; then
  if "$cc_cmd" -O2 -o "$tmp/hashchain" "$here/../tools/hashchain.c" 2> "$tmp/cc.log" && "$tmp/hashchain" selftest > /dev/null 2>&1; then
    ok "$cc_cmd" "builds tools/hashchain.c, and the hash passes its self-test"
  else
    bad "$cc_cmd" "cannot build tools/hashchain.c or its self-test fails: $(head -n 1 "$tmp/cc.log")"
  fi
else
  bad "$cc_cmd" "not found: install a C compiler (build-essential, or xcode-select --install), or set CC"
fi

# optional tools
if command -v aspell > /dev/null 2>&1; then
  if aspell dump dicts 2> /dev/null | grep -qx en; then
    ok aspell "with the English dictionary"
  else
    skip aspell "no English dictionary (aspell-en): the spelling of commit messages is not checked"
  fi
else
  skip aspell "not installed: the spelling of commit messages is not checked"
fi
if command -v "${CLANG_FORMAT:-clang-format}" > /dev/null 2>&1; then
  ok clang-format "$("${CLANG_FORMAT:-clang-format}" --version | head -n 1)"
else
  skip clang-format "not installed: the pre-commit hook cannot check the formatting of C and C++"
fi
if command -v python3 > /dev/null 2>&1; then
  ok python3 "$(python3 --version 2>&1)"
else
  skip python3 "not installed: the pre-commit hook cannot check the syntax of Python scripts"
fi

if [ "$fail" -ne 0 ]; then
  echo "check-prereq: a required tool is missing or does not work; fix it, then run this again"
fi
exit $fail
