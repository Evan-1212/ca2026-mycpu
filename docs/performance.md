<!-- SPDX-License-Identifier: MIT -->
# Measuring Performance

How the benchmark numbers of every task are measured, what each counter counts, and how to read
and compare them. The events themselves are defined in [hpm-events.md](hpm-events.md).

## Running the benchmarks

```shell
make -C 4-pipeline bench        # one task: every variant and build -> build/bench/t4.csv
make bench-all                  # every task -> build/bench/t1.csv ... t5.csv
```

`make bench` builds the task's model and the programs, then runs `scripts/bench-run.sh` once per
model and build. Each run executes Dhrystone (500 loops), DureMark (20 iterations) and the QR
encoder (`QR_GF=table` and `QR_GF=bitwise`, ECC levels L, M, Q and H), so a task's file has ten
rows per model and build. The tasks measure these configurations:

| Task | Models (`variant`) | Builds (`arch`) |
|------|--------------------|-----------------|
| T1 | `single-cycle` | `rv32i` |
| T2 | `div34`, `div66` (`DIV_LATENCY`) | `rv32i` (`div34` only), `rv32im` |
| T3 | `div34`, `div66` | `rv32i_zicsr` (`div34` only), `rv32im_zicsr` |
| T4, T5 | `Stall`, `Forward`, `EarlyBranch` | `rv32i_zicsr`, `rv32im_zicsr` |

`make bench-one` in a task directory runs one model and build with your own parameters (for
example `ARCH=`, `VARIANT=`, `DIV_LATENCY=`, the RAM wait states of T4 and T5, and
`BENCH_OUT=<file>`); the task READMEs list them.

## What a row holds

| Column | Meaning |
|--------|---------|
| `task`, `variant`, `arch`, `benchmark` | what ran: the task, its model, the `-march` of the build, and the workload (`dhrystone`, `duremark`, `qrcode-<table\|bitwise>-<L\|M\|Q\|H>`) |
| `cycles`, `instret` | the program's own counts of its timed region, from the `perf:` lines it prints |
| `cpi` | `cycles / instret`, three decimals |
| `score`, `score_unit` | Dhrystone: `LOOPS x 10^9 / 1757 / cycles`, in DMIPS/MHz x 1000; DureMark: its total score, in DureMark/MHz x 1000; `-` for the QR encoder |
| `run_cycles`, `run_instret`, `run_cpi` | the harness's counts for the whole run, from reset to the write of SimCtrl `EXIT`: start-up, printing and, on T5, the boot ROM (7 instructions) included. The programs print their own counts, so `run_instret` differs a little between models that print different numbers |
| `exit` | the program's exit code, always 0: a run that exits otherwise fails `make bench` |
| `hpm_cycles`, `hpm_instret` | from T3 on: `mcycle` and `minstret` over the benchmark's marker region |
| `hpm1` ... `hpm8` | from T3 on: `mhpmcounter3` ... `mhpmcounter10`, counting events 1 to 8 over the same region |

## What each counter counts

- **`mcycle`** counts clock cycles and **`minstret`** retired instructions. A cycle counts once
  whatever happens in it; an instruction counts once, when it retires: an instruction that traps
  does not retire, and in T4 and T5 an instruction that a flush removes never counts.
- **`mhpmcounter3..10`** count the events that the start marker selects (event n on
  `mhpmcounter<n + 2>`). Events 1, 4, 5, 6, 7 and 8 form a **partition**: each cycle without a
  retired instruction raises exactly one of them, the first whose reason holds, in the order
  D-side wait, I-side wait, divider busy, load-use stall, flush bubble, other. Events 2 (taken
  branches and jumps) and 3 (traps taken) count occurrences and are outside the partition.
  [hpm-events.md](hpm-events.md) defines each one per task.
- **T1 and T2 have no CSRs.** Their programs read the same two counts from SimCtrl registers
  (`software/runtime/include/mmio.h`), so their builds have no `hpm_*` columns, and their
  instruction counts differ a little from those of the Zicsr builds, whose code reads CSRs:
  compare instruction counts within one `arch` only.

Three pairs of counts describe slightly different stretches of the same run:

1. `cycles` and `instret` are what the program measures for itself, with `read_cycle()` and
   `read_instret()` around its timed code. Dhrystone reads both around the loop; DureMark adds up
   the cycles of its three timed workloads but counts the instructions of the whole iteration
   loop, which is why its `cycles` can be smaller than its `instret` on a single-cycle core.
2. `hpm_cycles`, `hpm_instret` and the events run from the start marker's write that lets the
   counters count to the stop marker's write to `mcountinhibit`. The region is a few dozen
   cycles longer than the program's own, but every counter sees exactly the same cycles, which
   is what the identity below needs.
3. `run_cycles` and `run_instret` cover the whole run.

## One run is enough

The models are cycle-exact simulations of the RTL: no operating system, no other process, no
clock to sample and no source of randomness the run does not fix itself (a random wait-state
pattern, `I_WAIT=` or `D_WAIT=rand<max>:<seed>` on T4 and `RAM_WAIT=rand<max>:<seed>` on T5, is a
function of its seed). The same model, program and
parameters give the same counts in every run, so one run per configuration is the measurement,
with no variance to average away. Two runs of `make bench` write byte-identical files.

The scores are "per MHz": they count cycles, not time. The simulation has no notion of a clock
period, so a single-cycle core and a five-stage pipeline are compared in cycles only, as if they
ran at the same frequency. Real hardware would clock the pipeline faster.

## Why rv32emu's numbers differ

rv32emu v1.3 is the program-level reference: it runs the same programs, built for
`PLATFORM=rv32emu` (`make -C software all PLATFORM=rv32emu ARCH=<arch>`), and prints the same
`perf:` lines. An rv32emu build reads the counter CSRs for every `arch`, so its `instret` equals
that of the Zicsr builds of T3 to T5 for the same workload (Dhrystone: 244520 without M, 243020
with M); the T1 and T2 builds read SimCtrl instead and count a few more. `make check-rv32emu`
compares console output and exit codes, not the `perf:` lines, so these counts are compared by
hand. Its **`cycle` counter is its instruction count**: rv32emu models no time, so its CPI is 1
by construction and its "cycles" never measure a design. For example, with `rv32im_zicsr`:

| Workload | rv32emu cycles / instret | T3 `div34` cycles / instret | difference |
|----------|--------------------------|------------------------------|------------|
| Dhrystone | 243021 / 243020 | 243021 / 243020 | none: no division in the loop |
| QR table L | 630130 / 630122 | 722304 / 630122 | 92174 = `hpm1`, 2711 divisions x 34 cycles |

The rv32emu numbers were taken with `externals/rv32emu/build/rv32emu
build/sw/rv32emu/rv32im_zicsr/<program>.elf`. A score computed from rv32emu's cycles is that of
an ideal machine that retires one instruction in every cycle. A design's score comes from its
own `cycles` for the same instructions, so it is lower in proportion to the extra cycles; the
hpm columns say exactly where those cycles went, over the marker region (next section).

## CPI decomposes exactly

Over the hpm region every counter counts the same cycles, and each cycle either retires an
instruction or raises exactly one partition event. Therefore, in every row from T3 on:

```
hpm_cycles - hpm_instret = hpm1 + hpm4 + hpm5 + hpm6 + hpm7 + hpm8          (exactly)

CPI = hpm_cycles / hpm_instret
    = 1 + (hpm1 + hpm4 + hpm5 + hpm6 + hpm7 + hpm8) / hpm_instret
```

`scripts/bench-run.sh` checks the identity for every row and fails, writing nothing, when one row
misses it by a single cycle. Worked example, T4 `Forward`, Dhrystone, `rv32im_zicsr` (table
below): 341587 - 243041 = 98546 = 11500 (load-use) + 87018 (flush) + 28 (other), so
CPI = 1 + 0.047 + 0.358 + 0.000 = 1.405. Each term is the cost of one cause in cycles per
instruction.

## Comparing T3, the T4 variants and T5

- **The work is the same.** `hpm_instret` is equal in T3, every T4 variant and T5 for the same
  benchmark and `arch` (243041 for Dhrystone with M, 630138 for QR table L). Ratios of cycles
  are therefore ratios of run time at equal frequency, and CPI differences are differences in
  cycles per instruction of the same instruction stream.
- **T3 is the floor.** It retires an instruction in every cycle but those of the divider
  (`hpm1`) and of traps, WFI and a full UART (`hpm8`).
- **T4** keeps T3's divider cycles and adds the pipeline's own: `hpm4` (load-use stalls), `hpm5`
  (flush bubbles) and a few `hpm8` cycles (filling the pipeline, serialization). The variants
  change exactly those columns. Compare a variant with another event by event: what forwarding
  or resolving branches in ID removes, and what it adds, shows in one column each.
  `hpm5` is exactly 2 x `hpm2` when branches resolve in EX and 1 x `hpm2` when they resolve in
  ID (Dhrystone: 43509 taken branches and jumps, 87018 and 43509 bubbles).
- **T5** adds the bus: `hpm6` (I-side waits) and `hpm7` (D-side waits). The partition takes the
  first reason that holds; work out what that means for a cycle that is both a T4 stall or bubble
  and a T5 wait before you compare T5 with T4 event by event (T5 question Q1).

The task READMEs ask questions that need runs of your own (wait states, a divider latency, a
changed program); answer them with the same decomposition.

## Measured results

Measured on the reference with `make bench-all` (Verilator 5.054, xPack GCC 15.2.0, the pins of
[toolchain.md](toolchain.md)); the rows are copied from `build/bench/t<n>.csv`. The reference
repository's CI compares each row below with the files of a new run, so a change of the design
that moves a number fails there until this page is updated. Your own design gives other numbers
where it differs; compare your `make bench` files with these rows. T1 has no M build; the other rows are
the M builds (`rv32im`, `rv32im_zicsr`); the files have the `rv32i` rows and the other QR rows
too.

| `task` | `variant` | `arch` | `benchmark` | `cycles` | `instret` | `cpi` | `score` |
|---|---|---|---|---|---|---|---|
| T1 | single-cycle | rv32i | dhrystone | 244583 | 244582 | 1.000 | 1163 |
| T1 | single-cycle | rv32i | duremark | 1909742 | 1909844 | 1.000 | 10472 |
| T1 | single-cycle | rv32i | qrcode-table-L | 1071315 | 1071306 | 1.000 | - |
| T2 | div34 | rv32im | dhrystone | 243083 | 243082 | 1.000 | 1170 |
| T2 | div34 | rv32im | duremark | 921043 | 921145 | 1.000 | 21714 |
| T2 | div34 | rv32im | qrcode-table-L | 722306 | 630123 | 1.146 | - |
| T2 | div66 | rv32im | dhrystone | 243083 | 243082 | 1.000 | 1170 |
| T2 | div66 | rv32im | duremark | 921043 | 921145 | 1.000 | 21714 |
| T2 | div66 | rv32im | qrcode-table-L | 809058 | 630123 | 1.284 | - |
| T3 | div34 | rv32im_zicsr | dhrystone | 243021 | 243020 | 1.000 | 1170 |
| T3 | div34 | rv32im_zicsr | duremark | 921034 | 921133 | 1.000 | 21714 |
| T3 | div34 | rv32im_zicsr | qrcode-table-L | 722304 | 630122 | 1.146 | - |
| T3 | div66 | rv32im_zicsr | dhrystone | 243021 | 243020 | 1.000 | 1170 |
| T3 | div66 | rv32im_zicsr | duremark | 921034 | 921133 | 1.000 | 21714 |
| T3 | div66 | rv32im_zicsr | qrcode-table-L | 809056 | 630122 | 1.284 | - |
| T4 | Stall | rv32im_zicsr | dhrystone | 449539 | 243020 | 1.850 | 633 |
| T4 | Stall | rv32im_zicsr | duremark | 1610536 | 921133 | 1.748 | 12418 |
| T4 | Stall | rv32im_zicsr | qrcode-table-L | 1279552 | 630122 | 2.031 | - |
| T4 | Forward | rv32im_zicsr | dhrystone | 341539 | 243020 | 1.405 | 833 |
| T4 | Forward | rv32im_zicsr | duremark | 1264259 | 921133 | 1.373 | 15819 |
| T4 | Forward | rv32im_zicsr | qrcode-table-L | 932478 | 630122 | 1.480 | - |
| T4 | EarlyBranch | rv32im_zicsr | dhrystone | 321538 | 243020 | 1.323 | 885 |
| T4 | EarlyBranch | rv32im_zicsr | duremark | 1226074 | 921133 | 1.331 | 16312 |
| T4 | EarlyBranch | rv32im_zicsr | qrcode-table-L | 912172 | 630122 | 1.448 | - |

The events of the hpm region (`rv32im_zicsr`; `hpm3` is 0 in every row, as no benchmark traps):

| `task` | `variant` | `arch` | `benchmark` | `hpm_cycles` | `hpm_instret` | `hpm1` | `hpm2` | `hpm4` | `hpm5` | `hpm6` | `hpm7` | `hpm8` |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T3 | div34 | rv32im_zicsr | dhrystone | 243041 | 243041 | 0 | 43509 | 0 | 0 | 0 | 0 | 0 |
| T3 | div34 | rv32im_zicsr | qrcode-table-L | 722312 | 630138 | 92174 | 83410 | 0 | 0 | 0 | 0 | 0 |
| T4 | Stall | rv32im_zicsr | dhrystone | 449587 | 243041 | 0 | 43509 | 119500 | 87018 | 0 | 0 | 28 |
| T4 | Stall | rv32im_zicsr | qrcode-table-L | 1279574 | 630138 | 92174 | 83410 | 390408 | 166820 | 0 | 0 | 34 |
| T4 | Forward | rv32im_zicsr | dhrystone | 341587 | 243041 | 0 | 43509 | 11500 | 87018 | 0 | 0 | 28 |
| T4 | Forward | rv32im_zicsr | qrcode-table-L | 932500 | 630138 | 92174 | 83410 | 43334 | 166820 | 0 | 0 | 34 |
| T4 | EarlyBranch | rv32im_zicsr | dhrystone | 321582 | 243041 | 0 | 43509 | 35000 | 43509 | 0 | 0 | 32 |
| T4 | EarlyBranch | rv32im_zicsr | qrcode-table-L | 912193 | 630138 | 92174 | 83410 | 106436 | 83410 | 0 | 0 | 35 |

T5 counts the same work and the same redirects as T4; its cycles, waits and CPI are what its
question Q1 asks you to predict and then measure, and are not listed here:

| `task` | `variant` | `arch` | `benchmark` | `hpm_instret` | `hpm1` | `hpm2` |
|---|---|---|---|---|---|---|
| T5 | Stall | rv32im_zicsr | dhrystone | 243041 | 0 | 43509 |
| T5 | Stall | rv32im_zicsr | qrcode-table-L | 630138 | 92174 | 83410 |
| T5 | Forward | rv32im_zicsr | dhrystone | 243041 | 0 | 43509 |
| T5 | Forward | rv32im_zicsr | qrcode-table-L | 630138 | 92174 | 83410 |
| T5 | EarlyBranch | rv32im_zicsr | dhrystone | 243041 | 0 | 43509 |
| T5 | EarlyBranch | rv32im_zicsr | qrcode-table-L | 630138 | 92174 | 83410 |

In all 150 rows from T3 on (both builds, every workload), `hpm_cycles - hpm_instret` equals the
sum of the partition events exactly.
