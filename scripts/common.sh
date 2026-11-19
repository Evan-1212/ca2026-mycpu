RED=""
# SPDX-License-Identifier: MIT
GREEN=""
YELLOW=""
BLUE=""
WHITE=""
CYAN=""
NC=""

set_colors() {
  local default_color
  default_color=$(git config --get color.ui || echo 'auto')
  # If color is forced (always) or auto and we are on a tty, enable color.
  if [[ "$default_color" == "always" ]] || [[ "$default_color" == "auto" && -t 1 ]]; then
    RED='\033[1;31m'
    GREEN='\033[1;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[1;34m'
    WHITE='\033[1;37m'
    CYAN='\033[1;36m'
    NC='\033[0m' # No Color
  fi
}

# Inside GitHub Actions the hook checks do not apply: exit with status 0.
check_github_actions() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    exit 0
  fi
}

# Usage: FORMAT [ARGUMENTS...] Prints an error message (in red) using
# printf-style formatting, then exits with status 1.
throw() {
  local fmt="$1"
  shift

  # We prepend "[!]" in red, then apply the format string and arguments, finally
  # reset color.
  printf "\n${RED}[!] $fmt${NC}\n" "$@" >&2
  exit 1
}

# Progress bar
progress() {
  local current_step="$1"
  local total_steps="$2"

  # Compute percentage
  local percentage=$(( (current_step * 100) / total_steps ))
  local done=$(( (percentage * 4) / 10 ))
  local left=$(( 40 - done ))

  # Build bar strings
  local bar_done
  bar_done=$(printf "%${done}s")
  local bar_left
  bar_left=$(printf "%${left}s")

  # Print the progress bar in a single line, updating it dynamically.
  printf "\rProgress: [%s%s] %d%%" "${bar_done// /#}" "${bar_left// /-}" "$percentage"

  # When reaching 100%, automatically print a newline to finalize the output.
  if [ "$percentage" -eq 100 ]; then
    printf "\n"
  fi
}
