<!-- SPDX-License-Identifier: MIT -->
# ca2026-mycpu: A RISC-V Processor in Chisel, Task by Task

This repository is Lab 3 of the course. You build a RISC-V
processor in [Chisel](https://www.chisel-lang.org/) in five tasks, T1 to T5, from a single-cycle
RV32I core to a pipelined machine-mode system-on-chip with M, Zicsr, Zicntr and Zihpm. Each task is a directory
with its own README, exercises, tests and expected results, and each task builds on your answers
to the tasks before it.

The processor is made of the modules you wrote in
[chisel-bootcamp](https://github.com/sysprog21/chisel-bootcamp) Module 5 (ALU, decode table,
branch unit, load extension, forwarding, UART transmitter). This repository does not contain
them: it compiles them from your bootcamp clone.

Everything is checked in simulation with Verilator. No task needs an FPGA board.

## Prerequisites

1. **chisel-bootcamp Modules 1 to 3 and Module 5 are finished.** Module 4 is optional. The seven
   exercise files of Module 5 (`Alu`, `DecodeRows`, `BranchUnit`, `LoadExtend`, `PipelineFlush`,
   `Forward`, `UartTx`) must be complete: `make check-bootcamp` must pass before any task builds.
2. The host tools of the table below. `make check-deps` checks each of them by making it do real
   work, and prints an install hint for whatever is missing.

### The bootcamp gate

`make check-bootcamp` fails while a `???` remains in an exercise file, naming the notebook to
finish (5.2, 5.3, 5.4, 5.6, 5.7 or 5.8), and then runs the 13 bootcamp specs that check those
files. Every task's `make test`, `make verilator` and `make sim` depend on it, through a stamp
(`build/bootcamp.ok`): from a task directory the gate runs again only when the bootcamp clone,
its pin or a file of its `riscv` project has changed since it last passed. `make check-bootcamp`
at the top always runs it.

There are three ways to tell this repository where your bootcamp answers are:

| You have | Use | What happens |
|----------|-----|--------------|
| nothing yet | `make fetch` | chisel-bootcamp is cloned at the pinned commit into `externals/chisel-bootcamp`. Do Module 5 there. |
| your own git clone | `BOOTCAMP_DIR=<clone>` on every `make` command, or exported in your shell | Your clone is used as it is. It is never reset or moved; it must contain the pinned commit in its history. A relative path is relative to the root of this repository. |
| no clone, only the files (you worked in the bootcamp's container) | `BOOTCAMP_SRC=<copy of the container's riscv/src>` on every `make` command, or exported | The seven exercise files are copied from that directory into the pinned clone in `externals/`. Every other difference is reported and ignored. A relative path is relative to the root of this repository. `BOOTCAMP_DIR` cannot be set at the same time. |

`make bootcamp-patch` writes `build/bootcamp.patch`, your changes to the bootcamp relative to the
pinned commit. It is part of what you submit for each task.
[docs/bootcamp-interface.md](docs/bootcamp-interface.md) lists every bootcamp symbol this
repository uses, and [docs/bootcamp-map.md](docs/bootcamp-map.md) names the notebook each
exercise builds on.

## Toolchain

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17 | 11 or newer is required. The reference results were produced with OpenJDK 11.0.27; `make check-deps` warns for any version other than 17. Newer JDKs are untested. |
| sbt | 1.13.1 | `project/build.properties` pins it; any recent sbt launcher fetches it. |
| Scala | 2.13.18 | fetched by sbt |
| Chisel | 7.16.0 | fetched by sbt, with firtool 1.160.0. Tests use ChiselSim. |
| Verilator | 5.042 or newer | The reference results were produced with 5.054. Build it from source if your distribution's package is older. |
| RISC-V GCC | xPack GNU RISC-V Embedded GCC 15.2.0 (binutils 2.45) | prefix `riscv-none-elf-`, expected in `~/riscv/toolchain/bin`; override with `RISCV=<directory>` or `CROSS=<prefix>` |
| rv32emu | v1.3 (commit `73265fcc3eba`) | fetched by `make fetch`, built by `make rv32emu` |
| chisel-bootcamp | commit `1fdc4d1b0071` | fetched by `make fetch`, or your own clone |
| riscv-arch-test, Sail RISC-V | commit `70907fc67108`, release 0.16 | fetched by `make fetch`; used from T3 on |
| mise, or uv with Ruby 3.2 to 3.x and Bundler | | for the architecture tests from T3 on |
| clang-format | 20 | only for `make indent` and `make check-format` |
| SDL2, qrencode | | optional, T5 only |

The Scala, Chisel and sbt versions are those of chisel-bootcamp's `riscv/build.sbt` and
`riscv/project/build.properties`, so the environment in which you did the bootcamp builds this
repository. [docs/toolchain.md](docs/toolchain.md) gives
the install steps for each tool under `~/.local`, without root.

## Quick Start

```shell
git clone https://github.com/sysprog21/ca2026-mycpu
cd ca2026-mycpu

make hooks            # install the Git hooks: commit rules, Change-Id chain, source checks (make prereq first checks their tools)
make fetch            # clone the pinned external repositories into externals/
make check-deps       # check the host tools
make rv32emu          # build the reference emulator
make check-bootcamp   # your bootcamp Module 5: no ??? left, and its specs pass

cd 1-single-cycle     # then task by task; see each task's README
make test             # every spec of the task
make verilator        # emit SystemVerilog and build the Verilator model
make sim              # run a C program on the model
make test-programs check-rv32emu check-uart cosim-programs
make bench            # Dhrystone, DureMark and the QR encoder -> ../build/bench/t1.csv
make lint             # verilator --lint-only -Wall: zero warnings
```

With your own bootcamp clone, add `BOOTCAMP_DIR=<clone>` to the `make` commands, or export it
once in your shell.

The task sources contain blanks: a Scala `?` under a `[CA26: Exercise <task>.<n>]` comment that
states what the missing code must do. A blank does not compile (`not found: value ?`) until you
replace it. `EXERCISES.md` lists the file and line of every blank, and `make check-blanks` lists
those that remain. A task compiles once all of its blanks, and those of the tasks before it, are
filled.

Besides the blanks, you write four sets of routines in RISC-V assembly, the quiz programs
`software/programs/quiz-{arith,sine,nn,enc}/routines.S`. The same files run on every task, from T1
on; the checks and the cost they print (`make -C <task> quiz`, or `make quiz-score` for all pipelines) are the correctness test of your
core, and later the workload for your hazard and performance analysis. The contract of the routines
is the header next to each `routines.S`, and `scripts/quiz-grade.py` says how they are scored.

## Task Map

| Task | Directory | What you build | ISA | Exercises |
|------|-----------|----------------|-----|-----------|
| T1 | [`1-single-cycle/`](1-single-cycle/) | A single-cycle core out of your bootcamp modules, with a byte-strobed data bus, memory-mapped UART and SimCtrl, and a retire port for co-simulation | RV32I_Zifencei | 10 exercises, 16 blanks |
| T2 | [`2-rv32im/`](2-rv32im/) | A multiplier, an iterative divider with a handshake, and the stall that makes the core wait for it; the golden model of M | RV32IM_Zifencei | 8 exercises, 19 blanks |
| T3 | [`3-csr-trap/`](3-csr-trap/) | Machine-mode CSRs, traps through `mtvec`, `mret` and `wfi`, interrupts from the CLINT and SimCtrl, and performance counters whose events explain every cycle | RV32IM_Zicsr_Zicntr_Zihpm_Zifencei | 15 exercises, 24 blanks |
| T4 | [`4-pipeline/`](4-pipeline/) | A five-stage pipeline of the T3 core in three variants of hazard handling (stall, forwarding, early branches), with memories that may answer late | as T3 | 10 exercises, 10 blanks |
| T5 | [`5-soc/`](5-soc/) | An AXI4-Lite system-on-chip around your T4 pipeline: two masters, a crossbar, a protocol checker on every interface, a boot ROM, the RAM, CLINT, UART and VGA, and a UART shell | as T3 | 9 exercises, 12 blanks |

What each finished task is checked with:

| Check | T1 | T2 |
|-------|----|----|
| ChiselSim specs (`make test`) | 14 specs, 54 tests | 14 specs, 67 tests |
| riscv-tests (`make test-riscv-tests`) | 40 of 40 `rv32ui` | 40 of 40 `rv32ui`, 8 of 8 `rv32um` |
| Co-simulation against the golden model `mycpu.common.Iss` | 500 random programs; whole C programs by retire-log replay | the same, with M and with stalls |
| Programs against expected output, and against rv32emu v1.3 | 12 programs | 12 programs, built without and with M |
| Injected bugs that the checks must catch (`MUTANT=`) | `sra` | `sra`, `no-sign-fix` |
| Benchmarks (`make bench`) | Dhrystone, DureMark, QR encoder | the same, without and with M, two divider latencies |

From T3 on, the checks also include the architecture tests (`make act4`, 101 tests in 10
suites) and the performance-counter identities; T4 runs every check on each of its three
variants, and T5 runs them from the boot ROM on the SoC, with the VGA's golden frames and the
UART shell. Each task's README lists its checks and expected results. Some specs are held back:
they are not in your tree, and the TAs run them on your submission; each task README names
them.

Simulation is cycle-exact, so the cycle and instruction counts in each task's README are to be
reproduced exactly, not approximately. The architecture tests (riscv-arch-test 4, with Sail as the
reference) start at T3, because their test environment needs CSRs.

## Repository Layout

```
ca2026-mycpu/
├── README.md  LICENSE  NOTICE.md  EXERCISES.md  CONTRIBUTING.md
├── Makefile             fetch, check-deps, check-bootcamp, test-all, lint-all, bench-all, indent
├── build.sbt  project/  one sbt build: bootcamp (your code), common, one project per task
├── common/              shared Chisel code: SimCtrl, UART, golden model (Iss), co-simulation,
│                        ELF and retire-log readers, the model emitter
├── 1-single-cycle/      T1
├── 2-rv32im/            T2
├── 3-csr-trap/          T3
├── 4-pipeline/          T4
├── 5-soc/               T5
├── sim/                 the Verilator harness (C++) and the make rules of every task
├── software/
│   ├── runtime/         crt0, linker script, a small libc (no newlib), console and exit
│   ├── programs/        the test programs and demos; programs/MANIFEST lists each with its
│   │                    class and its first task
│   └── benchmarks/      dhrystone, duremark
├── tests/riscv-tests/   rv32um, expanded (rv32ui comes from the bootcamp clone)
├── tests/act4/          the ACT4 configuration of the T3 to T5 cores, and its scripts
├── web/                 the web observation interface (web/README.md)
├── scripts/             fetch-repo.sh, check-deps.sh, check-blanks.py, bench-run.sh, the Git hooks
│                        (*.hook), chain.sh, grade.py, ...
├── tools/               hashchain.c, the hash behind the Change-Id of every commit
├── docs/                toolchain, troubleshooting, bootcamp interface and map, performance,
│                        counter events, your configuration, observation
├── externals/           (not tracked) what `make fetch` clones
└── build/               (not tracked) all build output
```

## Repository-Wide Targets

Run these at the top of the repository. `make help` prints the list.

| Target | What it does |
|--------|--------------|
| `fetch` | clone or verify chisel-bootcamp, riscv-arch-test, rv32emu and Sail at their pins |
| `rv32emu` | build the reference emulator in `externals/rv32emu/build/` |
| `check-deps` | check the host tools |
| `check-bootcamp` | the bootcamp gate |
| `bootcamp-overlay BOOTCAMP_SRC=<dir>` | take the seven exercise files from a plain directory |
| `bootcamp-patch` | write your bootcamp changes to `build/bootcamp.patch` |
| `test-all`, `lint-all`, `bench-all` | `make test`, `lint` or `bench` in every task |
| `quiz-all` | `make quiz` in every task: your routines of the four quiz programs, correct? and what they cost there |
| `bench` | `bench-all`, then the report `build/bench/report.{csv,md}` |
| `bench-xcheck` | the benchmarks' instruction counts on T3 to T5 against rv32emu v1.3 |
| `cosim-random` | the random programs of every task against the golden model (`SEEDS=`, `SEED_FIRST=`) |
| `check-blanks` | list the blanks that remain, and compare with `EXERCISES.md` |
| `hooks` | install the Git hooks into `.git/hooks` (once per clone) |
| `history` | verify that every commit since the base commit continues the Change-Id chain; `repo` also checks the hooks and the commit rules |
| `chaintest`, `unittest` | self-test of the chain tool; unit tests of the scripts (`scripts/test_*.py`) |
| `grade` | score the public checks with points (`scripts/grade.py --list`); `GRADE_ARGS="--only task1,deps"` selects some |
| `indent` | format the Scala sources (scalafmt 3.11.5) and this repository's own C and C++ files (clang-format) |
| `check-format` | fail when `make indent` would change a file |
| `check-lint-flags` | fail when `sim/` or a task `Makefile` contains a `-Wno-` flag (lint warnings are fixed, or waived in `sim/lint.vlt`) |
| `clean` | remove `build/` |
| `distclean` | also remove riscv-arch-test, rv32emu and Sail; chisel-bootcamp is kept, because it may hold your work |

The pins of the external repositories are variables of the `Makefile` (`BOOTCAMP_COMMIT`,
`ACT_COMMIT`, `RV32EMU_COMMIT`, `SAIL_VERSION`). There are no git submodules.

## Commit History

Every commit since the base commit (`0f7e5b6`) must carry a `Change-Id` that continues a hash chain. The id is the
first 40 hex digits of SHA-256 over the previous id and the tree of the commit, and the `commit-msg` hook computes it
when you commit; `tools/hashchain.c` implements the hash without any library. `make history`, `make repo`, `git push`
(the `pre-push` hook) and the GitHub Actions workflow recompute the chain from `git log`. A commit made with
`--no-verify` has no Change-Id, so it is reported by name.

* History must be linear: no merge commits since the base commit. `make hooks` sets `pull.rebase` for the
  repository, so `git pull` rebases. Do not merge, and do not use the GitHub web editor, "Update branch", "Sync fork"
  or a squash merge: they create commits without a link.
* `git commit --amend` is handled: the `post-commit` hook restamps the amended commit when the `commit-msg` hook had
  to guess. It leaves the commits of a rebase, a cherry-pick and a revert alone, and the `post-rewrite` hook says,
  after the rewrite, when the history needs `scripts/chain.sh rechain`.
* A squash (`git rebase -i`, `git merge --squash`) leaves the Change-Ids of the commits it joins in one message. The
  `commit-msg` hook keeps exactly one, the new link, and prints a line when it dropped others; `scripts/chain.sh verify`
  names a commit that has more than one, and `rechain` consolidates it.
* A rebase, a squash, a cherry-pick, a revert and a commit made without the hooks leave links that do not fit.
  Repair them with `scripts/chain.sh rechain`, then push with `git push --force-with-lease`. It restamps only the
  commits whose link is wrong, keeps the others (and their hashes), keeps uncommitted changes, and adds a
  `Rechained: yes` trailer to each commit it rewrote.
* `make chaintest` exercises all of this in a scratch repository.

What the chain does and does not show. It shows that every commit passed through `commit-msg` (or was restamped by
`rechain`, visibly) and that the sequence of trees was not edited afterwards. It does not cover the message text
alone, a force-push back to an earlier valid commit, or the checks of the other hooks; and anyone who runs the tool
by hand can forge a link. Grading verifies a submission with the scripts of the instructors' checkout
(`scripts/grade.sh --chain`), not with the submission's own. Linked Git worktrees are not supported.

The commit message rules and the code style are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Documentation

- [CONTRIBUTING.md](CONTRIBUTING.md): coding style and the Git commit style the hooks enforce
- [docs/toolchain.md](docs/toolchain.md): installing the tools under `~/.local`
- [docs/troubleshooting.md](docs/troubleshooting.md): error messages and what to do about them
- [docs/bootcamp-interface.md](docs/bootcamp-interface.md): the bootcamp symbols this repository uses
- [docs/bootcamp-map.md](docs/bootcamp-map.md): the bootcamp notebook behind each exercise
- [docs/performance.md](docs/performance.md): what the benchmark counters count, and how to read
  and compare CPI
- [docs/hpm-events.md](docs/hpm-events.md): the performance events of T3 to T5
- [docs/observation.md](docs/observation.md) and [web/README.md](web/README.md): the observation
  bundles of your own runs, and the web interface that replays them;
  [docs/observation-teaching.md](docs/observation-teaching.md): how its views serve the tasks
- [tests/act4/README.md](tests/act4/README.md): the architecture tests from T3 on
- `EXERCISES.md`: the file and line of every blank
- each task's `README.md`: goals, block diagram, exercises, commands, expected results, and what
  to submit
- the [course handout](https://hackmd.io/@sysprog/arch2026-lab3) (Lab 3 on HackMD): the exercise
  comments cite its sections as `handout §T<n>.<m>`

## License

This repository is available under the MIT License; see [LICENSE](LICENSE). Third-party files
keep their own license and header, with the license file in their own directory: Dhrystone
(`software/benchmarks/dhrystone/`), DureMark (`software/benchmarks/duremark/`), the Nyan Cat
frames (`software/programs/nyancat/`), riscv-tests (`tests/riscv-tests/`) and three.js
(`web/vendor/three/`). [NOTICE.md](NOTICE.md) lists each of them with its origin and license.
