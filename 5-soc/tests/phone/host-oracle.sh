#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# The host oracle of the phone: builds software/programs/phone/cellphone.c for
# the host, replays a journey and writes its pictures as <name>.ppm (P6, 240 x
# 320) into the output directory. Exit status 0 when every `expect` held.
#
# Usage: tests/phone/host-oracle.sh <output directory> [journey]
set -eu -o pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
if [ $# -lt 1 ] || [ $# -gt 2 ]; then
    echo "usage: $0 <output directory> [journey]" >&2
    exit 2
fi
out=$1
journey=${2:-$here/journey.txt}
cc=${HOSTCC:-cc}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$out"

# the same application source, built with the host's checks on (CP_ASSERT)
"$cc" -O1 -std=gnu11 -Wall -I"$root/software/programs/phone" \
    "$here/host/host.c" "$root/software/programs/phone/cellphone.c" \
    "$root/software/programs/phone/font_5x8.c" -o "$tmp/host"
"$tmp/host" "$journey" "$out"
