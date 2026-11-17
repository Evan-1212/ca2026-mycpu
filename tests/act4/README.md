<!-- SPDX-License-Identifier: MIT -->
# ACT4 for the cores of this repository

ACT4 (riscv-arch-test 4.x) is the compliance suite from T3 on. This
directory holds the DUT configuration of each core and the scripts that build the self-checking
ELFs, run them on a Verilator model, and check the result. T3, T4 and T5 have the same ISA, memory
map and macros, so one configuration, `cores/mycpu-t3`, serves the three: `TASK=4` and `TASK=5`
run its ELFs (on T5 each test starts at `0x8000_0000` through the boot ROM) and name their runs
after their own task.

| File | What it is |
|------|------------|
| `VERSION` | The riscv-arch-test commit. The top-level `make fetch-act4` and this Makefile both read it. |
| `Makefile` | `elfs`, `run`, `ref`, `selftest`, `expected-count`, `clean` (see `make -C tests/act4 help`) |
| `run_one.sh` | Runs one ELF on a task's Vsim: `$SIM +elf=<elf> +max-cycles=<N>` |
| `make ref` + `ref_one.sh` | The "ref_run" of VER-01: every final ELF on Sail with `sail.json` |
| `ref_one.sh` | Runs one final ELF on Sail, with the console and exit code of SimCtrl |
| `run_all.sh` | Runs a set of ELFs through `run_tests.py` and checks it against `EXPECTED_COUNT` |
| `selftest.sh` | Proves that `run_all.sh` fails when it must |
| `cores/mycpu-t3/test_config.yaml` | ACT4 framework configuration |
| `cores/mycpu-t3/mycpu-t3.yaml` | UDB configuration: the extensions and parameters of the hart |
| `cores/mycpu-t3/rvmodel_macros.h` | Halt, console, CLINT and MEIP macros for the DUT |
| `cores/mycpu-t3/link.ld` | RAM at 0x8000_0000, 4 MiB, in the layout of ACT4's `docs/memory_map.md` |
| `cores/mycpu-t3/sail.json` | Sail 0.16 configuration that makes Sail this hart |
| `cores/mycpu-t3/run_cmd.txt` | Template of the command `run_tests.py` runs per ELF |
| `cores/mycpu-t3/EXCLUDED` | Excluded test groups with reasons (none) |
| `cores/mycpu-t3/EXPECTED_COUNT` | Tests per suite at the pinned commit |

## Flow

```
make fetch-act4 fetch-sail                      # once (top level)
make -C tests/act4 elfs TASK=3                  # generate and compile -> build/act4/mycpu-t3/elfs
make -C tests/act4 run  TASK=3 SIM=build/t3/Vsim
make -C tests/act4 ref  TASK=3                  # the same ELFs on Sail, no RTL needed
make -C tests/act4 selftest TASK=3 HALT_SIM=build/t2/Vsim
```

At the top level, `make act4-<target>` is `make -C tests/act4 <target>` (for example
`make act4-run TASK=3 SIM=build/t3/Vsim`). Every path variable (`SIM`, `HALT_SIM`, `BUILD`,
`ACT_DIR`, `SAIL_SIM`, `RISCV`) is absolute or relative to the repository root.

1. **`elfs`** checks that `externals/riscv-arch-test` is at the commit of `VERSION` with no local
   change, removes `build/act4/mycpu-t<TASK>/elfs`, and runs the pinned Makefile with
   `CONFIG_FILES=cores/mycpu-t<TASK>/test_config.yaml WORKDIR=build/act4` and the groups of
   `EXCLUDED` as `EXCLUDE_EXTENSIONS`. riscv-arch-test runs its tools under mise when it is
   installed, else under uv. The framework validates the UDB YAML, generates the test sources it does
   not track (the privileged suites such as `tests/priv/Sm`; the unprivileged ones such as
   `tests/rv32i/I` are tracked files) into the clone's `tests/`, whose `.gitignore` covers them,
   compiles each test
   twice, runs the *signature* build on Sail to compute the expected values, and links them into
   the final self-checking ELF. Afterwards the clone is checked again, so a framework that writes
   into tracked files fails the target. `EXTENSIONS=I,M` builds only those suites. Last, it writes
   `build/act4/mycpu-t<TASK>/elfs.provenance`: the pin, the set of `EXTENSIONS`, the versions of
   Sail and GCC, a hash of the core's configuration files and the hash of every ELF.
2. **`run`** links the ELFs into a fresh `build/act4/run/mycpu-t<TASK>[-<VARIANT>]`, runs
   `run_tests.py --jobs <JOBS> --timeout <TIMEOUT>` with the command of `run_cmd.txt`, and then
   `run_all.sh` checks `summary.log`. The run fails unless every test exits 0 and prints the
   `RVCP-SUMMARY: TEST PASSED` line, `summary.log` has one line per ELF, and every suite ran exactly
   the count of `EXPECTED_COUNT` (or of the suites in `EXTENSIONS`, if given). Before it runs
   anything, `run` recomputes the provenance and refuses ELFs of another pin, another
   `EXTENSIONS` (give `run` the `EXTENSIONS` that `elfs` was given), a configuration file changed
   since, or a set changed by hand (an ELF added, removed, renamed or replaced). A missing suite,
   an extra suite, a stale ELF and an empty set are all failures.
3. **`ref`** is the same run with `ref_one.sh`: it proves that the final ELFs, the macros, the
   linker script and `sail.json` are sound before any T3 RTL exists.

### How a task plugs in its simulator

The task's Verilator harness (`sim/main.cpp`) must take `+elf=<path>` and `+max-cycles=<N>`, print
SimCtrl `PUTC` bytes to stdout, and exit with the low byte of the value stored to SimCtrl `EXIT`
(0 pass, 1 fail; 64 + cause for a halt, 124 at the cycle limit). The T1 and T2 harnesses already do.
Then:

```
make -C 3-csr-trap verilator                    # builds build/t3/Vsim (the task's own target)
make -C tests/act4 elfs TASK=3
make -C tests/act4 run TASK=3 SIM=build/t3/Vsim [JOBS=..] [TIMEOUT=600] [MAX_CYCLES=50000000]
```

`SIM` is absolute or relative to the repository root. Paths with spaces are not supported (GNU
make cannot carry them through its variables), though the commands handed to `run_tests.py` quote
theirs.
`SIM_ARGS="+ram-fill=0xdeadbeef"` passes more harness arguments to every run (no quotes inside);
the runs are in parallel, so an output file such as `+retire-log=` is for a run of one ELF by
hand (`SIM=<Vsim> tests/act4/run_one.sh <elf>`). For T4,
`VARIANT=<v>` keeps the results of each variant apart; a mutant (VER-04) is just another `SIM`.

## The Sail runs and the DUT's MMIO (VER-01 step 3)

**The run that computes the expected values does not execute the DUT's halt, console or MEIP
macros.** At the pinned commit, `framework/src/act/build_plan.py` compiles the signature ELF with
`tests/env/sail_macros.h` included after `rvmodel_macros.h`. That file redefines
`RVMODEL_HALT_PASS/FAIL` and `RVMODEL_IO_WRITE_STR` to HTIF (`tohost`, as cvw and rv32emu halt),
`RVMODEL_DATA_SECTION` to the `tohost`/`fromhost` words, `RVMODEL_SET/CLR_MEXT_INT` to Sail's
simple interrupt generator, the latencies to Sail's (1 and 5000), and the CLINT addresses to
`SAIL_CLINT_BASE_ADDRESS`. The framework takes those bases from `sail.json`
(`platform.clint.base`, `platform.simple_interrupt_generator.base`) and refuses a `sail.json`
without them. What the signature run does take from the DUT is `RVMODEL_ACCESS_FAULT_ADDRESS`
(here 0, unmapped in both) and `RVMODEL_BOOT` is undefined there. So SimCtrl is never touched by
it, and the CLINT it touches is Sail's own at 0x0200_0000, the same address as D12.

The **final** ELFs contain the DUT macros only. `make ref` runs them on Sail anyway, so that the
ELFs and macros are checked without RTL:

- `sail.json` declares the CLINT (64 KiB at 0x0200_0000) and the UART plus SimCtrl window
  (8 KiB at 0x1000_0000) as I/O memory, so the SimCtrl stores succeed.
- `ref_one.sh` runs Sail with `--trace-mem` into a reader that turns the stores to `PUTC` into
  stdout and the first store to `EXIT` into the exit code. Sail stops at `--stop-at-pc
  mycpu_halted`, the label both halt macros end at (the instruction after the store to `EXIT`).
  The ELF names the addresses: `RVMODEL_HALT_PASS` also defines the absolute symbols
  `mycpu_simctrl_exit` and `mycpu_simctrl_putc`, so `ref_one.sh` serves any core whose macros
  define the three symbols.
- MEIP: Sail has no SimCtrl device, and a SimCtrl register cannot double as Sail's interrupt
  generator (a value with bit 0 set is an access fault to it). So `RVMODEL_SET/CLR_MEXT_INT` first
  store to Sail's generator, placed by `sail.json` at SimCtrl + 0xFE0 (its register at +0xFE4,
  `(1 << 31) | (1 << 11)` to set MEIP), and then to SimCtrl `MEIP`. To the core +0xFE4 is an unused
  SimCtrl offset: `SimCtrl.scala` decodes the whole 4 KiB window and ignores a write to an unknown
  offset, and `Iss`'s SimCtrl model does the same; to Sail `MEIP` is plain memory. Checked: the
  final `InterruptsSm_trigger_M` ELF takes MSI, MTI and MEI four times each on Sail.

Result: all 101 final ELFs pass on Sail (`make ref TASK=3`).

## Known Sail disagreements, and which ACT4 tests meet them

`3-csr-trap/README.md` lists what no Sail key changes. Checked against the 101 selected tests
(their generated sources, and Sail `--trace` of every signature run, 2026-11-15):

- **`mstatus` reads 0 on Sail before its first write or trap.** No test can see it:
  `RVTEST_BOOT_TO_MMODE` (`tests/env/rvtest_setup.h`) writes `mstatus = MPP` before any test code,
  and no trace has a read of `mstatus`, or a trap, before that write.
- **Sail's `mtimecmp` resets to 0, so MTIP is pending from reset.** No test can see it: the M-mode
  trap prolog (`init_Mtimecmp` in `tests/env/rvtest_trap_handler.h`, emitted because
  `RVMODEL_MTIMECMP_ADDRESS` is defined) writes all ones to both halves within the first 30
  instructions, and no trace reads `mip` or traps before that. `Sm_mcsr_walk-02`, which walks
  `mip`, records 0 for every bit on Sail.
- **`mcycle` and `time` follow Sail's clock; `mhpmcounter3..10` count nothing on Sail.** The
  counter tests compare only differences: `Zicntr-*` and `Zihpm-*` check that a second read is not
  smaller; `Sm_mcsr_cntr-00` checks a read-back within 0x7FF of the value written, that an
  inhibited `mcycle`/`minstret` does not change over six `nop`s, that `time` is within 100 of an
  `mtime` written as 42, and that `timeh` equals an `mtimeh` written as 67; `Sm_Zicntr-00` checks
  exact `minstret` deltas, which T3-04 matches. The `mhpmevent` walks (`Sm_mcsr_walk-*`) never read
  a counter. So the T3 core passes them if `mtime` stays within 100 cycles over a `sw`, a `fence`
  and a `csrr time`, and if no event bit leaks into the WARL value of `mhpmevent` (4 bits, 0..15).

Nothing is therefore excluded (`cores/mycpu-t3/EXCLUDED`).

## Decisions

**UDB configuration (`mycpu-t3.yaml`).** Every parameter the pinned UDB requires for these
extensions is set; validation fails on a missing one. The ones that are not obvious:

- `Zmmul` is listed: UDB requires it of every hart with M. It adds the 4 `Zmmul` tests.
- `HPM_EVENTS` lists 0..15: `mhpmevent3..10` are plain 4-bit fields that read back any value
  0..15, as Sail with `hpmevent_width_bits: 4` does (`3-csr-trap/README.md`). Values 9..15 select
  no event. This supersedes T3-01's "read back 0 outside 0–8".
- `COUNTINHIBIT_EN` = bits 0, 2..10 (mask 0x7FD); `HPM_COUNTER_EN` = 3..10 (0x7F8); all 64 bits wide.
- `MCOUNTENABLE_EN` all false and `WFI_U_MODE: false`: required by UDB, meaningless without U mode.
- `TRAP_ON_ILLEGAL_WLRL: false`: `mcause` is WARL here (an illegal code is not stored).
- `MTVEC_ILLEGAL_WRITE_BEHAVIOR: custom`: a write of MODE 2 or 3 keeps the mode but writes BASE,
  which is neither `retain` nor any other UDB value exactly.
- `MISALIGNED_LDST: false` with `MISALIGNED_LDST_EXCEPTION_PRIORITY: high`: misaligned accesses
  trap and outrank access faults (D5, T3-02). `PMA_GRANULARITY: 2`, `NUM_PMP_ENTRIES: 0`.
- `WFI_FINITE: false`: WFI may wait forever (T3-02).
- `mvendorid`/`marchid`/`mimpid`/`mconfigptr` read 0 (`VENDOR_ID_*` 0, `MARCHID_IMPLEMENTED` and
  `MIMPID_IMPLEMENTED` false).

**`rvtest_config.h` and `.svh` are not committed.** ACT4's README still calls them
hand-written, but at the pinned commit the framework generates both from
the UDB YAML into `build/act4/mycpu-t3/env/` (`framework/src/act/parse_udb_config.py`). The core
directory comes first on the include path, so a committed copy would shadow the generated one;
`make elfs` refuses a core directory with any header other than `rvmodel_macros.h`.

**`sail.json`** starts from rv32emu v1.3's target file (same Sail release) and contains every key
of `3-csr-trap/sail-m-only.json` with the same value (checked by a key-by-key comparison). Its
header lists each difference from rv32emu's file. `platform.instructions_per_tick: 1` lets `mtime`
advance once per instruction.

**Differences from rv32emu v1.3's target** (`externals/rv32emu/tests/arch-test-target/rv32emu-rv32imafc`):
extensions narrowed to D5 (no A, F, C; Zmmul added), `include_priv_tests: True`, halt through
SimCtrl `EXIT` instead of `tohost`, a real console loop instead of an empty `RVMODEL_IO_WRITE_STR`,
MEIP through SimCtrl, `RVMODEL_ACCESS_FAULT_ADDRESS` 0, RAM 4 MiB instead of rv32emu's 2 GiB, and
the `sail.json` changes above. `link.ld` keeps rv32emu's layout below its "Most users" line.

**Latencies.** `RVMODEL_INTERRUPT_LATENCY 10` and `RVMODEL_TIMER_INT_SOON_DELAY 1000` are bounds
from the T3 design (an interrupt source reaches `mip` in one cycle; the M-mode paths between
arming the timer and waiting are under 20 instructions); `rvmodel_macros.h` gives the reasoning.
Larger values only cost time; the tests stay correct if an interrupt comes early.

## Toolchain

xPack GCC 15.2 `riscv-none-elf-*` in `$(RISCV)/bin` (default `~/riscv/toolchain`), Sail 0.16
`sail_riscv_sim` (`make fetch-sail`, or `SAIL_SIM=<yours>`), mise with Ruby 3.4 and uv (or uv and
Bundler) for the framework. The Makefile puts the first two on `PATH`; `test_config.yaml` names the
tools by file name.

## Measurements (2026-11-15, 32 threads)

| Step | Result | Time |
|------|--------|------|
| `elfs TASK=3`, cold (after `clean`) | 101 ELFs | 1 min 56 s |
| `elfs TASK=3`, framework cache warm | 101 ELFs | 47 s |
| `elfs TASK=3 EXTENSIONS=<one suite>`, warm | I 39, M 8, Zmmul 4, Zicsr 6, Zicntr 2, Zihpm 2, Zifencei 1, Sm 28, ExceptionsSm 4, InterruptsSm 7 | 2–4 s each |
| `ref TASK=3` | 101 of 101 pass on Sail | 5 s |
| `selftest TASK=3 HALT_SIM=build/t2/Vsim` | 24 of 24 cases behave as required | 13 s |

`selftest` checks that the runner fails on: an exit code 1 after a pass line, `TEST FAILED` with
exit 0, `SIGRUN`, no output, a pass followed by a fail line, a timeout, an empty ELF set, one ELF
missing, a suite not in `EXPECTED_COUNT`, a suite not requested, an empty `EXPECTED_COUNT`, an ELF
with a wrong expected signature (its own check prints `TEST FAILED` and exits 1), and an ELF whose
store to `EXIT` was removed. On T2 (no CSRs) every test halts at its first CSR instruction (exit
67, an illegal-instruction halt) and is reported as a failure, the cycle limit gives 124, a missing
model 2. ELFs patched to jump straight to the pass or fail report prove the SimCtrl console and
`EXIT` path on the real harness: they pass, or fail with exit 1.

`selftest` also tries the provenance check of `run`, `ref` and `expected-count` on copies of the
ELF set: the intact copy is accepted; a renamed ELF, an ELF overwritten by another (same names and
count), the set checked with other `EXTENSIONS`, and a changed configuration hash are refused.
Checked by hand as well: an edited `EXCLUDED`, and the ELFs of `elfs EXTENSIONS=I` given to `ref`
or `expected-count` without `EXTENSIONS`, are refused.

## Pin bump

1. Write the new commit into `VERSION`, then `make fetch-act4` (the clone must be clean).
2. `make -C tests/act4 clean elfs TASK=<n>`; fix the UDB YAML if validation fails.
3. `make -C tests/act4 ref TASK=<n>` must pass; then redo the checks of "Known Sail
   disagreements" for new or changed tests (grep the sources for `mip`, `mstatus`, counters and
   `mtime`; Sail `--trace` of the signature runs in `build/act4/mycpu-t<n>/build`).
4. `make -C tests/act4 expected-count TASK=<n>` rewrites `EXPECTED_COUNT` from the full ELF set
   (it refuses `EXTENSIONS`, and ELFs whose provenance is not a full `make elfs` with the current
   pin and configuration); review the diff and commit it with `VERSION`.
