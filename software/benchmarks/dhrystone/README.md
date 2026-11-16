<!-- SPDX-License-Identifier: MIT -->
# Dhrystone 1.1-mc

`dhrystone.c` is **Dhrystone 1.1**, not 2.1. Its DMIPS figures are not
comparable with published Dhrystone 2.1 numbers.

## Provenance

1. Reinhold P. Weicker, "Dhrystone: A Synthetic Systems Programming
   Benchmark", CACM 27(10), 1984 (Ada).
2. C translation by Rick Richardson, version C/1.1 (1984-1986).
3. Michael Clark, version C/1.1-mc (2017-04-23): `gettimeofday` timing,
   prototypes instead of K&R declarations, the `Proc3` call fixed.
4. rv32emu `tests/dhrystone.c` at rv32emu v1.3, commit
   `73265fcc3eba2d7d643fb2f66f1a7ffefebf5e64`
   (https://github.com/sysprog21/rv32emu). Copied verbatim from that commit
   (identical to the instructor's local copy, checked on 2026-11-15), then
   changed only as listed below.

## License

The original Dhrystone (Weicker, 1984) carries no license statement and has
been redistributed freely for decades. rv32emu ships this version in its
MIT-licensed repository, but its MIT grant is rv32emu's own and does not
cover the authors' work; NOTICE.md records the file as NOASSERTION.
`dhrystone.c` keeps its original header unchanged, and no SPDX line is added to
it.

## Build

`make -C software dhrystone` builds it with the runtime flags plus
`-O2 -fno-inline -fno-builtin` (the flags to record with every result) and
`-DMYCPU`. `-DLOOPS=<n>` changes the loop count (default 500 under `MYCPU`).

The same file still builds and runs on a host without `MYCPU`, with its
original timing and 10,000,000 loops: `cc -O2 dhrystone.c && ./a.out`.

## Local changes

All of them are under `#ifdef MYCPU`; without `MYCPU` the file is the
upstream one.

1. `#include <mmio.h>` for the counters and the benchmark markers.
2. `LOOPS` is a compile-time parameter, default 500 for RTL simulation
   (upstream: fixed at 10,000,000). A value outside 1 to 1,000,000,000 stops
   the build.
3. `String1Loc` is set to `"DHRYSTONE PROGRAM, 1'ST STRING"` before the timed
   loop, as in Weicker's and Richardson's versions. This version never sets
   it, yet `Func2` reads it, so the result (`BoolGlob`) and even termination
   depended on stale stack contents.
4. `gettimeofday` around the timed loop is replaced by `bench_mark_start()`
   and 64-bit `read_cycle()` / `read_instret()` reads before the loop, and the
   same reads followed by `bench_mark_stop()` after it. `mmio.h` reads SimCtrl
   `CYCLE`/`INSTRET` with `-DMYCPU_NO_ZICSR` (a MyCPU without Zicsr: T1, T2)
   and `rdcycle[h]`/`rdinstret[h]` otherwise (T3 on, and rv32emu for every
   `ARCH`), both rollover-safe.
5. The report (`MycpuReport`) prints integers only. It prints and checks the
   final values that Dhrystone defines: `IntGlob` = 5, `BoolGlob` = 1,
   `Char1Glob` = 'A', `Char2Glob` = 'B', `Array1Glob[8]` = 7,
   `Array2Glob[8][7]` = `LOOPS` + 10, `PtrGlb->Discr` = `Ident1`,
   `PtrGlb->EnumComp` = `Ident3`, `PtrGlb->IntComp` = 17,
   `PtrGlbNext->Discr` = `Ident1`, `PtrGlbNext->EnumComp` = `Ident2`,
   `PtrGlbNext->IntComp` = 18, both `StringComp` =
   `"DHRYSTONE PROGRAM, SOME STRING"`, and both `PtrComp` equal. On any
   mismatch it prints `MISMATCH` and exits with code 1.
6. Platform-dependent numbers are on lines that start with `perf:` (VER-07
   ignores them): `cycles`, `instret`, `cycles/loop` and
   `DMIPS/MHz*1000` = `LOOPS` × 10^9 / (`cycles` × 1757), in 64 bits,
   computed as `LOOPS` × 10^9 / 1757 / `cycles` (the same value), so that no
   cycle count overflows it.
7. `malloc`, `strcpy` and `strcmp` come from the runtime's tiny libc, the
   same code on the CPU and on rv32emu (VER-06).

`expected.txt` is the console output without the `perf:` lines for the
default `LOOPS` (500).
