<!-- SPDX-License-Identifier: MIT -->
# Hardware performance events (`mhpmevent3..10`)

The programmable counters of every core from T3 on are `mhpmcounter3` to `mhpmcounter10`
(`mycpu.common.Counters`); `mhpmcounter11..31` and `mhpmevent11..31` read 0 and ignore writes.
Each counter counts the event whose ID software writes to its `mhpmevent` register, in the cycles
in which its `mcountinhibit` bit (3 to 10) is clear. `mhpmevent3..10` are 4-bit fields: ID 0 and
the IDs 9 to 15 select no event (the counter stands still). Eight counters and eight events, so
one run with event n on `mhpmcounter<n + 2>` collects them all; the benchmark markers of the
C runtime set them up that way (`software/runtime/platform_mycpu.c`, BEN-03).

Software names the events with the `HPM_EVENT_*` macros of `software/runtime/include/mmio.h`, never
with their numbers.

The events are defined once, in `3-csr-trap/src/main/scala/mycpu/csrtrap/HpmEvents.scala`
(`HpmEvents`, and the classifier `HpmClassifier` that every core uses), from what the core
reports about each clock cycle (`CycleStatus`).

| ID | Name | Counts | Kind | T3 | T4 | T5 |
|----|------|--------|------|----|----|----|
| 1 | divider busy | cycles in which the divider holds the core | partition | yes | yes | yes |
| 2 | taken branch or jump | `jal`, `jalr` and taken branches that retire (a jump to the next address counts; one that traps on its target does not) | occurrence | yes | yes | yes |
| 3 | trap taken | cycles in which an exception or an interrupt is taken | occurrence | yes | yes | yes |
| 4 | load-use stall | cycles lost to a load-use stall | partition | 0 | yes | yes |
| 5 | flush bubble | cycles in which the retiring stage holds a bubble that a flush made | partition | 0 | yes | yes |
| 6 | I-side wait | cycles in which the core waits for the instruction memory | partition | 0 | with wait states | yes |
| 7 | D-side wait | cycles in which the core waits for the data memory or a device | partition | 0 | yes | yes |
| 8 | other | any other cycle without a retired instruction | partition | yes | yes | yes |

## The partition rule

A cycle in which an instruction retires raises no partition event. Every other cycle raises
**exactly one**, the first of this list whose reason holds:

```
D-side wait (7) > I-side wait (6) > divider busy (1) > load-use stall (4) > flush bubble (5) > other (8)
```

A cycle in which a trap is taken retires nothing (a synchronous exception does not retire its
instruction), so it is in the partition too: in T3 it is "other". Events 2 and 3 count
occurrences and are outside the partition. Therefore, over any stretch of cycles in which every
counter counts (none inhibited, none written):

```
mcycle - minstret = hpm1 + hpm4 + hpm5 + hpm6 + hpm7 + hpm8      (exactly)
```

`make bench` checks this identity for every row (`scripts/bench-run.sh`), `T3CounterSpec` and
the `perf` program check it on the core, and BEN-04 asks it of every T4 and T5 row.
[performance.md](performance.md) shows how to read the benchmark rows with it.

## What "other" is in T3

The single-cycle T3 core has no load-use stall, no flush and memories that answer in the cycle of
the request, so events 4 to 7 read 0. A cycle without a retired instruction is then one of:

- a cycle in which the divider holds a division (event 1): `DIV_LATENCY` per division, one for a
  division by zero or the signed overflow;
- a cycle in which a trap is taken (event 8; event 3 counts it as well);
- a cycle in which a WFI waits for an interrupt (event 8);
- a cycle in which a store waits for a full UART transmit FIFO (event 8; T4 and T5 count it as event 7).

A trap taken while a division waits for the divider abandons the division, and that cycle is the
trap's ("other"), not the divider's.

## What the events are in T4

The pipeline commits in MEM, so a cycle without a retired instruction is one in which MEM holds
no instruction that leaves it. Each bubble carries the event it was made for from the stage
where it was made, and is counted when it reaches MEM:

- **divider busy (1)**: EX holds a division while the divider works, so MEM gets bubbles:
  `DIV_LATENCY` per division (1 for a division by zero or the signed overflow), less any cycle in
  which an older access in MEM waits at the same time (a D-side wait outranks it);
- **load-use stall (4)**: the hazard unit holds an instruction in ID for an operand that is not
  available in time (in `Stall`, every read-after-write hazard; in `Forward`, a load's value one
  instruction later; in `EarlyBranch`, also a branch or `jalr` that needs a value of EX, or a
  load's, in ID). A cycle that ID loses to serialization at the same time is "other";
- **flush bubble (5)**: the bubbles that a taken branch or jump, a trap, an `mret` or a `fence.i`
  makes, including the cycle after the redirect in which the fetch unit has nothing yet;
- **I-side wait (6)**: ID is empty because the fetch unit waits for the instruction memory: 0 with
  a RAM that answers in the cycle of the request, counted with the RAM wait states of T4-06
  (`make ... I_WAIT=<n>`);
- **D-side wait (7)**: MEM holds an access that is not answered yet: the RAM's wait states
  (`D_WAIT=<n>`), or a store to a full UART transmit FIFO;
- **other (8)**: the pipeline filling after reset, the cycles of serialization (a CSR instruction,
  `mret`, `wfi` or `fence.i` waits in ID for the older instructions, and the younger ones wait
  for it), a WFI that waits, and the cycle of a trap.

`4-pipeline/README.md` gives the counts of each hazard sequence per variant and the decomposition
of the benchmarks' CPI.
