#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

# Repository checks run by `make test`: the Git hooks are installed and every
# commit since the project start follows the commit rules.

common_script="$(dirname "$0")/common.sh"
[ -r "$common_script" ] || { echo "[!] '$common_script' not found or not readable." >&2; exit 1; }
source "$common_script"
set_colors

"$(dirname "$0")/chain.sh" verify || exit 1

check_github_actions

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  throw "This directory is not a Git repository."
fi

"$(dirname "$0")/install-git-hooks" --check || throw "The Git hooks are not in effect. Run 'make hooks' once to install them."

"$(dirname "$0")/commitlog.sh"
