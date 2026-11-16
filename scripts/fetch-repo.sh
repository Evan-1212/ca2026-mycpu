#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# Clone or check a git repository at a pinned commit, without git submodules.
#
# Usage: fetch-repo.sh [--keep] <url> <commit> <dir>
#
#   (default)  The directory is a read-only dependency (e.g. riscv-arch-test).
#              It is created with a shallow fetch of <commit>. If it already
#              exists at another commit and its working tree is clean, it is
#              moved to <commit> (a pin bump). Local changes are never discarded:
#              the script stops instead.
#   --keep     The directory holds the user's own work (chisel-bootcamp).
#              It is created the same way, but an existing directory is never
#              moved: the script only checks that <commit> is an ancestor of HEAD,
#              i.e. the work is based on the pinned revision or a later one.
#
# A new clone is made in <dir>.partial and renamed to <dir> when it is complete,
# so an interrupted clone never looks like a finished one. An existing <dir>
# must be the top of a git repository of its own: an empty directory is cloned
# into, any other directory that git would resolve to an enclosing repository
# (this course's checkout, for one) is refused, and a repository without a
# commit (a clone interrupted by an earlier version of this script) is fetched
# again.

set -euo pipefail

keep=0
if [ "${1:-}" = "--keep" ]; then
    keep=1
    shift
fi
if [ $# -ne 3 ]; then
    echo "usage: $0 [--keep] <url> <commit> <dir>" >&2
    exit 2
fi
url=$1
commit=$2
dir=$3
while [ "${#dir}" -gt 1 ] && [ "${dir%/}" != "$dir" ]; do
    dir=${dir%/}
done

die() {
    echo "fetch-repo: $*" >&2
    exit 1
}

if [ -z "$dir" ] || [ "$dir" = / ]; then
    die "refusing the directory '$dir'"
fi

fetch_commit() { # fetch_commit <repository>
    # GitHub serves any reachable commit by its full SHA, so a shallow fetch of
    # exactly the pinned commit is enough.
    git -C "$1" fetch --quiet --depth 1 origin "$commit" ||
        die "cannot fetch $commit from $url"
}

checkout_fetched() { # checkout_fetched <repository>
    git -C "$1" -c advice.detachedHead=false checkout --quiet --detach FETCH_HEAD ||
        die "cannot check out ${commit:0:12} in $1"
}

# clone: a new clone in $dir.partial, renamed to $dir at the end. A partial
# directory that this script left behind (it holds the marker) is made again;
# any other is not touched.
partial=$dir.partial
marker=$partial/.git/fetch-repo-partial
clone() {
    if [ -e "$partial" ] || [ -L "$partial" ]; then
        if [ ! -d "$partial" ] || [ -L "$partial" ] || [ ! -f "$marker" ]; then
            die "$partial exists and was not made by this script; move it away and retry"
        fi
        rm -rf "$partial"
    fi
    echo "  CLONE   $url @ ${commit:0:12} -> $dir"
    mkdir -p "$partial" || die "cannot create $partial"
    trap 'rm -rf "$partial"' EXIT
    git -C "$partial" -c init.defaultBranch=main init --quiet || die "cannot create a repository in $partial"
    : > "$marker"
    git -C "$partial" remote add origin "$url"
    fetch_commit "$partial"
    checkout_fetched "$partial"

    # From here on a failure leaves the complete clone, with its marker, in
    # $partial: the next run makes it again.
    trap - EXIT
    if [ -d "$dir" ]; then
        rmdir "$dir" || die "$dir is no longer empty; the clone is in $partial"
    fi
    mv "$partial" "$dir" || die "cannot rename $partial to $dir"
    rm -f "$dir/.git/fetch-repo-partial"
}

if [ ! -e "$dir" ] && [ ! -L "$dir" ]; then
    clone
    exit 0
fi
[ -d "$dir" ] || die "$dir exists but is not a directory; move it away and retry"

# The directory must be a repository of its own: git -C on a directory inside
# another work tree (an empty folder in this course's checkout, say) would act
# on that one.
real=$(cd "$dir" && pwd -P) || die "cannot enter $dir"
top=$(git -C "$dir" rev-parse --show-toplevel 2> /dev/null) && top=$(cd "$top" && pwd -P) || top=
if [ "$top" != "$real" ]; then
    if [ -z "$(ls -A "$dir")" ]; then
        if [ -L "$dir" ]; then
            die "$dir is a symbolic link to an empty directory; remove the link or clone into its target"
        fi
        clone
        exit 0
    fi
    die "$dir exists but is not a git repository of its own; move it away and retry"
fi

# A repository without a commit: an interrupted clone. Fetch the pin into it;
# nothing in it is discarded (checkout stops before it would overwrite a file).
if ! head=$(git -C "$dir" rev-parse --verify --quiet 'HEAD^{commit}'); then
    echo "  FETCH   $url @ ${commit:0:12} -> $dir (a repository without a commit)"
    if git -C "$dir" remote get-url origin > /dev/null 2>&1; then
        git -C "$dir" remote set-url origin "$url"
    else
        git -C "$dir" remote add origin "$url"
    fi
    fetch_commit "$dir"
    checkout_fetched "$dir"
    exit 0
fi

if [ "$head" = "$commit" ]; then
    if [ $keep -eq 0 ] && [ -n "$(git -C "$dir" status --porcelain --untracked-files=no)" ]; then
        echo "fetch-repo: warning: $dir is at the pin but has local changes to tracked files" >&2
    fi
    exit 0
fi

if [ $keep -eq 1 ]; then
    if ! git -C "$dir" cat-file -e "$commit^{commit}" 2>/dev/null; then
        die "$dir does not contain the pinned commit ${commit:0:12}.
  Update it first:  git -C $dir fetch origin && git -C $dir merge origin/HEAD"
    fi
    if ! git -C "$dir" merge-base --is-ancestor "$commit" "$head"; then
        die "$dir (HEAD ${head:0:12}) is not based on the pinned commit ${commit:0:12}.
  Rebase or merge your work onto ${commit:0:12}."
    fi
    echo "  OK      $dir is based on ${commit:0:12} (HEAD ${head:0:12})"
    exit 0
fi

if [ -n "$(git -C "$dir" status --porcelain)" ]; then
    die "$dir has local changes and is at ${head:0:12}, not the pinned ${commit:0:12}.
  Commit or discard them, or remove $dir, and retry."
fi
echo "  UPDATE  $dir ${head:0:12} -> ${commit:0:12}"
fetch_commit "$dir"
checkout_fetched "$dir"
