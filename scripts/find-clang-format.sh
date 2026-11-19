#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Print the path of a clang-format of the major version that formats this
# repository (see CLANG_FORMAT_REQUIRED in common.sh), looking at $CLANG_FORMAT,
# clang-format-20, clang-format and the usual install places. Exit status 1 and
# no output when there is none.
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=common.sh
source "$here/common.sh"
find_clang_format
