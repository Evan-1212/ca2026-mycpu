<!-- SPDX-License-Identifier: MIT -->
# Your configuration (`student.json`)

Every student builds a slightly different machine. The ISA stays the same, but some register
offsets, event numbers and timing parameters differ, so the correct RTL, the correct software and
the numbers in your report depend on your configuration. Write it once, at the top of the tree:

```shell
make student ID=<your student ID>
make student-show       # print it again later
```

`make student` writes `student.json` and prints what it selected. From then on every `make`
target of the tree builds and checks your configuration: the Verilator models, the C programs,
the golden model the co-simulation compares with, the ChiselSim specs' register maps and ACT4.
Quote the numbers of your own configuration in your report; the TAs regenerate the file from your
ID (`scripts/student.py`), so an edited file is refused by the build.

## What it selects

| Option | Values | Used from | What changes |
|--------|--------|-----------|--------------|
| SimCtrl register offsets | the default layout (`EXIT` +0x00 ... `MEIP` +0x1c), or the reversed one (`MEIP` +0x00 ... `EXIT` +0x1c) | T1 | where programs write their exit code and console bytes, and read `CYCLE`/`INSTRET` |
| UART register offsets | the default (`TXDATA` +0x0, `RXDATA` +0x4, `STATUS` +0x8, `CTRL` +0xc), or `STATUS` and `RXDATA` swapped | T1 | the UART driver and the device's decode |
| hpm event IDs | those of [hpm-events.md](hpm-events.md), or a fixed permutation of 1 to 8 | T3 | the value software writes to `mhpmevent3..10` to count an event |
| `DIV_LATENCY` | 34 or 66 | T2 | the divider's cycles per division, and every CPI with divisions |
| `TICK_DIVISOR` | 1 or 4 | T3 | clock cycles per tick of the CLINT's `mtime`, and so when a timer interrupt arrives |
| RAM wait states | 0 or 1 | T4, T5 | `I_WAIT`/`D_WAIT` of T4 and `RAM_WAIT` of T5: the memory's latency, and every CPI |

`make student-show` prints your register maps with their offsets and your event IDs, which are
the tables to use in place of the default ones of the handout and of [hpm-events.md](hpm-events.md).
The software sees them as the macros of `software/runtime/include/mmio.h` (`SIMCTRL_EXIT`,
`UART_STATUS`, `HPM_EVENT_DIVIDER_BUSY`, ...), and the Scala code as `mycpu.common.MemMap` and
`mycpu.csrtrap.HpmEvents`: name a register or an event, never its number, and your code works
with every configuration.

A command-line setting still wins over the file, for the experiments the task READMEs ask for
(`make -C 2-rv32im bench DIV_LATENCY=34`). `make <target> STUDENT_JSON=` builds the
default instead of your configuration, and `STUDENT_JSON=<file>` another one.

## How it is chosen

`scripts/student.py` maps your ID (upper-cased; letters, digits, `-` and `_`) to one of 8
configurations with a fixed hash. The 8 are an orthogonal array of the six options: each value of
each option appears in half of them, and each pair of values of two options in a quarter. The
reference passes the program checks of every task (test programs, rv32emu comparison, UART,
co-simulation of the C programs, and ACT4 from T3 on) with every one of the 8, T4 and T5 on their
Forward variant; their other variants are checked the same way on demand. Configuration 0 is the
default (`mycpu.common.MemMap`, [hpm-events.md](hpm-events.md)), which is also what the tree builds without a `student.json`. There is no way to pick
a configuration: a `student.json` whose ID maps to another one is refused, and the TAs grade with
the file regenerated from your ID.
