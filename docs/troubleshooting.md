<!-- SPDX-License-Identifier: MIT -->
# Troubleshooting

Each entry starts with the message as the tools print it. Start with `make check-deps`: it
checks every host tool and says what to install. [toolchain.md](toolchain.md) has the install
steps.

## Host tools

### `java       FAIL: ...: JDK 11 or newer is required`

`make check-deps` read a major version below 11 from `java -version`. Install JDK 17 and make
sure it is the `java` on your `PATH`.

### `java       WARN: ...: JDK 17 is the tested version (D2)`

A warning, not a failure: the JDK is 11 or newer but not 17. The reference host itself runs
OpenJDK 11.0.27, and every result in this repository was produced with it. JDK 21 and newer have
not been tested with this repository; if sbt or the Chisel compiler plugin fails with an error
inside the JVM on such a version, switch to 17 before looking anywhere else.

### `verilator  FAIL: 5.020 < 5.042; build it from source into ~/.local`

The Verilator on `PATH` is older than the minimum. Distribution packages often are. Build 5.054
from source ([toolchain.md](toolchain.md)); `verilator --version` must then print the new version,
so check that `~/.local/bin` comes before `/usr/bin` on `PATH`.

### `verilator  FAIL: ... cannot build a model (see: verilator --cc --exe --build)`

Verilator runs, but its C++ build does not. `make check-deps` builds a small model with
`verilator --cc --exe --build --assert -Wall`; run that command on a module of your own to see
the compiler's message. The usual cause is a missing C++ compiler or `make`.

### `verilator  FAIL: ... built a model, but its assertion did not fire (exit N)`

The installed Verilator ignores `--assert`. Chisel `assert`s in the model would then never
fire, and a check that relies on them would pass without checking. Reinstall Verilator.

### `riscv-gcc  FAIL: .../riscv-none-elf-gcc not found; install xPack riscv-none-elf-gcc >= 15`

The compiler is expected at `~/riscv/toolchain/bin/riscv-none-elf-gcc`. Pass
`RISCV=<directory that holds bin/>` or `CROSS=<path prefix of the tools>` to `make`, or export
it, if yours is elsewhere. The same variables are read in the task directories and in
`software/`.

### `riscv-gcc  FAIL: -march=rv32i selects multilib '...', not rv32i/ilp32`<br>`riscv-gcc  FAIL: an rv32i_zicsr link pulls M instructions from libgcc`<br>`act4-gcc   FAIL: cannot assemble -march=rv32im_zicsr_zicntr_zihpm_zifencei (ACT4 needs it)`

The compiler is not the xPack GCC 15 that this repository expects: its libgcc for `rv32i`
contains multiply or divide instructions, which a T1 core cannot execute, or its assembler does
not know the counter extensions. Install xPack GNU RISC-V Embedded GCC 15.2.0.

### `act4-env   FAIL: mise tools missing; run: cd externals/riscv-arch-test && mise trust .mise.toml && mise install`<br>`act4-env   FAIL: ACT4 needs Ruby >= 3.2 with Bundler; install mise into ~/.local/bin and run mise install in externals/riscv-arch-test`

The architecture tests (from T3 on) need Ruby 3.2 to 3.x with Bundler, and uv. With mise
installed, run the two `mise` commands of the message once; they install the versions that
riscv-arch-test pins. T1 and T2 do not use these tools, but `make check-deps` reports them for
the whole repository. No other target depends on `make check-deps`, so until T3 a `FAIL` on this
line alone, and the exit status 1 it gives, can wait.

`act4-env   WARN: externals/riscv-arch-test not fetched yet (make fetch-act4); cannot check
ACT4's mise tools` goes away after `make fetch`.

### `PATH       WARN: ~/.local/bin is not on PATH; tools installed there will not be found`

Add `export PATH="$HOME/.local/bin:$PATH"` to your shell's startup file and start a new shell.

## The bootcamp

### `TODO    Alu.scala: finish chisel-bootcamp notebook 5.2`<br>`check-bootcamp: chisel-bootcamp Module 5 is not finished`

An exercise file of the bootcamp still contains `???`. Finish the notebook the message names.
sbt reports the same condition as `chisel-bootcamp Module 5 is not finished:` followed by the
files, when it is run directly without `make`.

If you have finished Module 5 but somewhere else, the build is looking at the wrong directory:

- your own git clone: pass `BOOTCAMP_DIR=<clone>` on every `make` command, or export it;
- only the files, from the bootcamp's container: copy `riscv/src` out of the container
  (`docker cp <container>:<path to the bootcamp>/riscv/src my-bootcamp-src`) and pass
  `BOOTCAMP_SRC=my-bootcamp-src`. The seven exercise files are copied into
  `externals/chisel-bootcamp`; nothing else is taken from your directory.

### ``chisel-bootcamp not found at ...; run `make fetch-bootcamp` ``

sbt was started directly, and neither `externals/chisel-bootcamp` nor the directory in the
environment variable `BOOTCAMP_DIR` exists. Run `make fetch`, or export `BOOTCAMP_DIR`. sbt reads
`BOOTCAMP_DIR` from the environment only; a relative path is relative to the root of this
repository.

### `fetch-repo: <dir> does not contain the pinned commit 1fdc4d1b0071.`<br>`fetch-repo: <dir> (HEAD ...) is not based on the pinned commit 1fdc4d1b0071.`

Your bootcamp clone (`BOOTCAMP_DIR`) is older than the revision this repository is written
against, or is not a descendant of it. Fetch the bootcamp's upstream and merge or rebase your
work onto the pinned commit, as the message says. Your clone is never changed by this
repository.

### `check-bootcamp: <dir>/riscv/build.sbt uses Chisel X, but this repository uses Chisel 7.16.0`

The clone's build definition names another Chisel version, so its code was written for another
Chisel. Update the clone to the pinned commit or later.

### `BOOTCAMP_SRC and BOOTCAMP_DIR are alternatives`

`BOOTCAMP_SRC` copies files into the pinned clone that `make fetch` made, and never into a clone
of your own. Use `BOOTCAMP_DIR` alone for your own clone, or `BOOTCAMP_SRC` alone for a plain
directory.

### `bootcamp-overlay: .../Forward.scala was changed in the clone and differs from BOOTCAMP_SRC.`

An exercise file in `externals/chisel-bootcamp` was edited there by hand, and `BOOTCAMP_SRC`
holds another version. Nothing is overwritten. Decide which version is yours: keep the clone and
drop `BOOTCAMP_SRC`, or discard the edit with the `git checkout` command of the message.

### `bootcamp-patch: untracked files are not in the patch; git add them first:`

`build/bootcamp.patch` is a `git diff` against the pinned commit, which does not include files
git does not know. Run `git add` on the listed files in your bootcamp clone (no commit is
needed) and repeat.

### `bootcamp-patch: warning: the patch changes files other than the 7 exercise files:`

A warning. The patch is written, but it also changes bootcamp files that are not exercises. A
teaching assistant rebuilds your submission from this patch, so make sure the extra changes are
intended.

## Compiling

### `not found: value ?`

```
[error] .../1-single-cycle/src/main/scala/mycpu/singlecycle/InstructionFetch.scala:58:3: not found: value ?
```

An exercise blank is not filled in yet. This is the intended state of a task you have not
finished. `make check-blanks` lists every remaining blank with its file and line, and
`EXERCISES.md` gives the title of each.

A task's sbt project compiles only when all of its blanks are filled, and each task depends on
the tasks before it. sbt stops at the first project that fails, so while T1 has blanks, T2
reports T1's errors and none of its own.

### `[error] ... not found: value ?` in a task you have finished

The error names the file. If it is in an earlier task's directory, that task is not finished.
If it is in `common/`, your tree is damaged: `common/` has no blanks.

### sbt appears to hang at the start

The first build downloads Scala, Chisel and firtool. The first `make check-bootcamp` also runs
the bootcamp's 13 specs, which takes several minutes (about 3 minutes 40 seconds on the
reference host).

## Running programs

### `sim: <file>: cannot open <file>`<br>`sim: .../fibonacci.elf not found (make -C ../software all PLATFORM=mycpu ARCH=rv32i)`<br>``... not found: build the programs first with `make -C software all PLATFORM=mycpu ARCH=rv32i` (`make -C 1-single-cycle test` does it)``

The C program has not been built. The task targets (`make test`, `make test-programs`, ...)
build the programs they need; `make sim ELF=<file>` and a spec started directly with sbt do
not. Run the `make -C software` command of the message, with the `ARCH` it names. Programs are
built into `build/sw/mycpu/<arch>/`.

### `sim: <file>: entry point 0x... is not the reset vector 0x80000000`<br>`sim: <file>: segment N [0x..., 0x...) is outside RAM [0x80000000, 0x80400000)`

The harness (exit code 2) refuses an ELF file that the core could not run: every segment must
lie inside the RAM, and execution starts at the RAM's base. Link with
`software/runtime/link.ld`, as `software/Makefile` does. If you built the model with a smaller
`RAM_BYTES`, the program's stack may no longer fit.

### `sim: timeout after N cycles, instret=M` (exit code 124)

The run reached `+max-cycles`. `make sim` passes `MAX_CYCLES` (default 500000000); a spec or a
check script may use a smaller bound. Compare `instret` with the cycle count:

- `instret` stopped far below the cycle count: the core no longer retires instructions. Find
  the last retired instruction with `SIM_ARGS="+retire-log=retire.log"`; the log ends with
  `end cycles=... instret=... stop=timeout`, and the record before it is the last instruction
  that completed. A core that waits forever for a memory or divider response looks like this.
- `instret` keeps up with the cycles: the program is in a loop. The `pc=` fields of the last
  records show where; look the addresses up in `build/sw/mycpu/<arch>/<program>.dis`.

Raise the bound with `make sim MAX_CYCLES=<n>` only when the program is expected to run that
long.

### `sim: halted: cause=N (name) pc=0x... cycles=... instret=...` (exit code 64 + N)

In T1 and T2 the core has no traps: an `ecall`, an `ebreak`, an illegal instruction, a
misaligned access or target, and an access to an unmapped address halt it, and the harness
exits with 64 plus the cause (65 `ecall`, 66 `ebreak`, 67 illegal instruction, 68 misaligned,
69 bad address). Look up the `pc` in the program's `.dis` file. An illegal instruction at a
multiply or divide in T1 means the program was built with `ARCH=rv32im`.

### `sim: the model failed an assertion at cycle N` (exit code 3)

A Chisel `assert` in the model fired. Rerun with `SIM_ARGS="+trace=run.fst +trace-start=<a few
cycles before N>"` and look at the cycle in GTKWave or Surfer.

### `check-model: .../rv32emu not found (run 'make rv32emu' at the top)`<br>`test: .../rv32emu not found (run 'make rv32emu' at the top, or set RV32EMU)`<br>``... not found: run `make fetch-rv32emu rv32emu` (or set RV32EMU)``

The reference emulator has not been built. Run `make rv32emu` at the top of the repository; it
clones rv32emu v1.3 if needed and builds `externals/rv32emu/build/rv32emu` (the build log is
`build/rv32emu.log`). `make check-rv32emu` in every task and `make test` in T2 need it.

### `check-model: .../Vsim not found (run 'make verilator' in the task directory)`

A check script was started by hand before the model was built. The `make` targets of the task
directories build the model themselves.

## The UART

### The program prints nothing on the UART

- **The console is not the UART by default.** `printf` writes to SimCtrl `PUTC`, which the
  harness copies to stdout. Only a program built with `CONSOLE_UART=1` prints through the UART:
  `make -C software <program> CONSOLE_UART=1 BUILD=<a directory of its own>`. The `uart` program
  uses the UART directly in either build.
- **The harness decodes the UART for you.** Bytes the model sends on its TX line appear on
  stdout, mixed with `PUTC` output. `SIM_ARGS="+uart-log=uart.bin"` writes only the UART's bytes
  to a file. An empty file means the model never sent a start bit.
- **A store to `TXDATA` while the transmit FIFO is full is held, not dropped**: the core waits
  until an entry is free. If your core does not wait (exercises 1.6 and 1.7), bytes are lost or
  repeated; `T1UartSpec` checks exactly this.

### `sim: N UART frame(s) without a stop bit (is the divider D?)`

The harness sampled a frame whose stop bit was low. It decodes with the divider the model was
built with (`UART_DIVIDER`, default 16, in `sim_config.h`), so the transmitter does not hold each
bit for that many cycles. Check how your `UartTx` (bootcamp 5.8) is instantiated and that its
bit period is the divider.

### Input sent with `+uart-stdin` is lost

By default the harness sends the next byte only while the model's receive FIFO has room. That
needs the top-level output `io.uartRxReady` to be driven from the UART; a top that leaves it
unconnected is treated as always ready, and a slow program then overruns the FIFO.
`+uart-no-flow-control` turns the pacing off on purpose (`make check-uart
UART_ARGS=+uart-no-flow-control` must fail).

## Formatting and lint

### `check-format: these files are not formatted (run 'make indent'):`<br>`check-format: Scala sources are not formatted (run 'make indent'; log: build/check-format.log)`

`make indent` formats them: clang-format for the C and C++ files the first message lists, and
scalafmt for the Scala sources, whose `isn't formatted properly!` lines name the files. scalafmt
inserts a space after `//` in line comments; the exercise tools accept the comments in both forms.

### `%Warning-...` from `make lint`

`make lint` must print no warning. Do not add `-Wno-...` flags or `lint_off` comments: the only
waivers are those in `sim/lint.vlt`, for names that firtool generates. A warning about a signal
of your own (unused, width mismatch) is a real finding in your Chisel code.
