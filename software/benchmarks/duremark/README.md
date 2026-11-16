<!-- SPDX-License-Identifier: MIT -->
# DureMark 1.0

A small CPU benchmark inspired by CoreMark (linked list, matrix and state
machine workloads), by Serge Vakulenko.

## Provenance and license

`main.c`, `list.c`, `matrix.c`, `state.c`, `duremark.h` and `LICENSE`
are copied from DureMark (https://github.com/sergev/duremark) at commit
`59fc1bb6ecb0a11a7d703bf651b32ec3c12c1c88`, through the instructor's local
clone (its `._*` AppleDouble files are not copied).

MIT License, Copyright (c) 2025 Serge Vakulenko: see `LICENSE`, copied
unchanged. The file headers are kept unchanged, and no SPDX line is added.

## Build

`make -C software duremark` builds it with the runtime flags and
`-DMYCPU`; `ITERATIONS=<n>` changes the iteration count (default 20, and a
value outside 1 to 4,000,000,000 stops the build).

On a host, `cc -std=c99 -O2 main.c list.c matrix.c state.c` builds the
upstream benchmark (self-calibrating, floating-point report). Without
`MYCPU` and `DUREMARK_FIXED_ITERATIONS`, the preprocessed source of every
file is identical to upstream's, and so is each object file (`cc -std=c99
-E -P` and `cc -std=c99 -O2 -c`, compared with `cmp` on 2026-11-15), so the
default host build behaves exactly as upstream.

Adding `-DDUREMARK_FIXED_ITERATIONS=<n>` to the host build is the one
addition outside `MYCPU`. It is off by default; it runs exactly n iterations
once and prints the same checksum as the `MYCPU` build with `ITERATIONS=<n>`.
`make -C software check-duremark [ITERATIONS=<n>]` checks that: it runs the
`MYCPU` build on rv32emu, builds the host program with the iteration count
that run printed, and compares the two checksums (20 iterations:
`Checksum : 1111990183`).

## Local changes

1. `duremark.h`: under `MYCPU`, `du_ticks_t` is `uint64_t` (cycles).
2. `main.c`, under `MYCPU`: `<mmio.h>` replaces `<time.h>`, and
   `du_start_time`/`du_get_time` read the 64-bit cycle counter
   (`read_cycle()`) instead of `clock()`.
3. `main.c`: with `DUREMARK_FIXED_ITERATIONS` (always defined under
   `MYCPU`, as `ITERATIONS`, default 20), the self-calibrating loop is
   replaced by one run of that many iterations. Under `MYCPU` that run is
   between `bench_mark_start()` and `bench_mark_stop()`, and its
   instruction count is read too. On a host this mode is the only change,
   and only when `DUREMARK_FIXED_ITERATIONS` is defined.
4. `main.c`, under `MYCPU`: the report prints integers only. The
   platform-dependent lines start with `perf:` (VER-07 ignores them): cycles,
   instret, each workload's share of the cycles in per mille (`du_per_mille`,
   which cannot overflow), and the score `DureMark/MHz*1000` = iterations ×
   10^9 / cycles, in 64 bits. The iteration count and the checksum are
   printed as upstream.

`expected.txt` is the console output without the `perf:` lines for the
default `ITERATIONS` (20).
