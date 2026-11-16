<!-- SPDX-License-Identifier: MIT -->
# Observation schema v1

The web observation interface ([web/README.md](../web/README.md)) replays real runs of your own RTL.
This page defines what a run records and how it is packed: the probe ports of the RTL, the
`+obs-trace` file of the Verilator harness, and the observation bundle that a viewer loads. The
machine-readable form is [`web/schema/obs-v1.json`](../web/schema/obs-v1.json) (JSON Schema, draft
2020-12). Where the two disagree, the schema is wrong and must be fixed.

The data flows like this:

1. A top declares probe groups (`SimTop.obsGroups`) and connects the ports of `io.obs`
   (`mycpu.common.ObsIO`) to signals that the design already has, so the probes are wiring only.
   `mycpu.common.TopGen` lists the fields in `sim_config.h`.
2. The harness (`sim/main.cpp`, `sim/obs.cpp`) writes one record per cycle with
   `+obs-trace=<file>` (described under "Trace file" below).
3. `scripts/obs-pack.py` combines the trace, the retire log, the UART log, any VGA frames and the
   test results of the same run into a bundle (described under "Bundles" below).
4. `scripts/obs-validate.py` checks a bundle against the schema and against the retire log.

`make obs PROG=<name>` in a task directory runs steps 2 to 4 on the design of step 1 and writes
the bundle to `build/t<n>/obs/<name>/bundle/` (the default model of task T<n>). `make check-obs`
at the top runs the acceptance set for every task.

## Sampling

Every register of the design changes at the rising edge of the one clock. **Cycle N** begins with
a rising edge and lasts until the next one. Cycle 0 is the first cycle in which reset is low.
Reset cycles and the cycles in which the harness loads the program are not recorded.

The record of cycle N shows:

- **state** fields: the value of a register, or of a function of registers only, as it is after
  the rising edge that begins cycle N. It stays the same for the whole cycle. A register written
  in cycle N shows its new value in the record of cycle N + 1.
- **comb** fields: a combinational value computed in cycle N from that state and from the inputs
  of cycle N. The register file's write data (`core.wbValue`) is a comb field: the register holds
  it from cycle N + 1 on.
- **input** fields: what the harness drives in cycle N (`uart.rxd`).

The harness reads every field once per cycle, after the low phase of the clock and before the
rising edge that ends the cycle. It reads the retire port at the same moment, so a record's
`retire` group and the retire log describe the same cycle. The record cycles are exactly the
cycles of the retire log: cycle N is the (N+1)-th cycle counted by its record and `idle` lines,
and the last recorded cycle is `cycles − 1` of its `end` line. The cycles that the harness runs
after EXIT so that the UART can drain belong to neither file. The UART bytes sent in them are in
the UART log.

Clock-enable conventions (the header's `conventions` object): there is one clock and no gated
clock. A UART bit lasts `uart_divider` cycles. The VGA fields describe a pixel only in cycles in
which `vga.tick` is 1.

A field can follow the rule and still mean nothing in a cycle: `div.result` is the divider's
result register in every cycle, but it is a result only in a cycle in which `div.respValid` is 1.
The tables below say so for each such field.

## Header

| Field | Meaning |
|-------|---------|
| `task` | `T1` … `T5` (`+obs-task`) |
| `variant` | the model's variant within the task, as the task Makefile names it (`single-cycle`, `div34`; `+obs-variant`) |
| `commit` | the git commit that `HEAD` named when the model was elaborated, with `-dirty` if a tracked file differed from it, or `unknown` (`SIM_GIT_COMMIT`, written by `TopGen`) |
| `top` | the Scala class of the top |
| `program` | the ELF's file name without `.elf` |
| `elf_sha256` | SHA-256 of the ELF file, computed by the harness |
| `schema` | the schema version, `1.0` |
| `uart_divider`, `ram_base`, `ram_bytes` | elaboration parameters of the model |
| `conventions` | the constants of "Sampling" above |

`TopGen` takes the commit when it elaborates the model. sim.mk elaborates again only when a
source changes, so a model built before a commit that changed no source keeps the earlier commit.
That commit holds the same RTL.

## Groups and fields

Every field is an unsigned integer of the width given: a 1-bit field is 0 or 1. The name in
parentheses is the signal that the probe is connected to in T1 and T2. A record has exactly the
groups that the manifest lists. `retire` is the exception: it appears only in a cycle that has a
retire record.

### `core` (T1, T2)

The datapath of the instruction at the PC.

| Field | Bits | Class | Meaning (signal) |
|-------|-----:|-------|------------------|
| `pc` | 32 | state | the PC register (`InstructionFetch.io.pc`) |
| `inst` | 32 | comb | the word fetched at `pc`; 0 if the fetch faulted (`InstructionFetch.io.inst`) |
| `fetchValid` | 1 | comb | the instruction port answered; 0 in reset and after a halt (`InstructionFetch.io.inst_valid`) |
| `legal` | 1 | comb | `inst` is an instruction of the task's ISA (`DecodedInst.legal`) |
| `load`, `store`, `branch`, `jal`, `jalr`, `ecall`, `ebreak` | 1 each | comb | the decoded kind (`DecodedInst`), all 0 when `legal` is 0 |
| `rdWrite` | 1 | comb | the instruction writes `rd`, x0 included (`DecodedInst.rd_write`) |
| `rs1`, `rs2` | 5 | comb | the register file's read addresses (`RegFile.io.rs1`, `rs2`) |
| `rs1Value`, `rs2Value` | 32 | comb | the values read (`RegFile.io.rs1Data`, `rs2Data`) |
| `aluA`, `aluB` | 32 | comb | the ALU's operands (`Alu.io.a`, `b`) |
| `aluResult` | 32 | comb | the ALU's output, also the address of a load or store (`Alu.io.out`) |
| `branchTaken` | 1 | comb | control goes elsewhere than `pc + 4`: a taken branch, JAL or JALR (`Execute.io.redirect.valid`) |
| `nextPc` | 32 | comb | the next instruction's address in program order (`InstructionFetch.io.next_pc`); the PC takes it only if the instruction completes |
| `wbSource` | 2 | comb | the write-back select (`WriteBack.io.rd_source`): 0 execute result (ALU, in T2 also multiplier or divider), 1 load, 2 link |
| `wbEnable` | 1 | comb | the register file's write enable (`RegFile.io.wen`) |
| `wbRd` | 5 | comb | its write address (`RegFile.io.rd`) |
| `wbValue` | 32 | comb | its write data (`RegFile.io.wdata`) |
| `memReqValid`, `memReqReady` | 1 | comb | the data port's request handshake (`MemPort.req`) |
| `memReqAddr` | 32 | comb | the word address of the request |
| `memReqWstrb` | 4 | comb | the byte strobes; 0 for a read |
| `memReqWdata` | 32 | comb | the write data, by byte lane |
| `memRespValid` | 1 | comb | the device answers (`MemPort.resp.valid`) |
| `memRespRdata` | 32 | comb | the word read |
| `memRespError` | 1 | comb | no device at the address |

### `div` (T2 on)

The divider's ports (`Divider.io`).

| Field | Bits | Class | Meaning |
|-------|-----:|-------|---------|
| `reqValid`, `reqReady` | 1 | comb | the request handshake |
| `op` | 2 | comb | 0 div, 1 divu, 2 rem, 3 remu |
| `dividend`, `divisor` | 32 | comb | the operands |
| `busy` | 1 | state | an accepted request is in progress |
| `respValid` | 1 | comb | the divider answers in this cycle |
| `result` | 32 | state | the quotient or remainder, meaningful when `respValid` is 1 |
| `kill` | 1 | comb | the division in progress is abandoned (tied low in T2) |

### `uart` (T1 on)

| Field | Bits | Class | Meaning |
|-------|-----:|-------|---------|
| `txd` | 1 | state | the transmit line, idle high (`SimIO.uartTxd`) |
| `rxd` | 1 | input | the receive line as the harness drives it (`SimIO.uartRxd`) |
| `txCount`, `rxCount` | 4 | state | bytes in the TX and RX FIFOs, 0 to 8 (`Queue.io.count` of `Uart`) |

### `retire` (every task)

The retire record of the cycle. Its fields and values are those of a record line of the retire
log (version 2, `sim/main.cpp`): `pc`, `inst`, `next`, `rd` (5 bits), `val`, `halt` (3 bits,
`riscv.HaltCause`), `mip`. It also has `mem` {`write`, `addr`, `bytes` ∈ {1, 2, 4}, `data`},
`csr` {`addr` (12 bits), `wdata`} and `trap` {`cause`, `tval`}, each absent where the log prints
`-`. A `mip` of 0 is printed as `-` in the log and is 0 here. `scripts/obslib.py` (`retire_line`)
turns the object back into the log's line.

### Which top drives which group

| Task | Top | Groups (besides `retire`) |
|------|-----|---------------------------|
| T1 | `mycpu.singlecycle.Top` | `core`, `uart` |
| T2 | `mycpu.rv32im.Top` | `core`, `div`, `uart` |
| T3 | `mycpu.csrtrap.Top` | `core`, `csr`, `uart` |
| T4 | `mycpu.pipeline.Top` | `csr`, `pipe`, `uart` |
| T5 | `mycpu.soc.Soc` | `csr`, `pipe`, `bus`, `uart`, `vga` |

Each top takes `obs=0` in its `Emit` to build a model without probe ports. T3 reads `core` from
the same stages as T1 and T2 (`mycpu.singlecycle.Probes`). `core` describes a single-cycle
datapath, so T4 and T5 have `pipe` instead. T4 and T5 drive no `div` group: the divider sits in
EX, and `pipe.execute` shows the instruction that waits for it.

### `csr` (T3 on)

The CSR file (`mycpu.csrtrap.CsrFile`) as the core uses it: in T3 in the cycle in which the
instruction at the PC executes, in T4 and T5 in the commit stage (MEM).

| Field | Bits | Class | Meaning (signal) |
|-------|-----:|-------|------------------|
| `access` | 1 | comb | a CSR instruction is presented to the file (`CsrFile.io.csr.cmd.valid`) |
| `addr` | 12 | comb | its CSR address (`cmd.bits.addr`) |
| `op` | 2 | comb | 1 read-write, 2 set, 3 clear (`cmd.bits.op`) |
| `write` | 1 | comb | the instruction writes its CSR in this cycle (`io.csr.write`, the `csr` part of a retire record) |
| `rdata`, `wdata` | 32 | comb | the CSR's old value and the value written (`io.csr.rdata`, `io.csr.wdata`) |
| `trap` | 1 | comb | a trap is taken in this cycle: an exception or an interrupt (`CPU.trap`) |
| `cause` | 32 | comb | the `cause` of the core's retire record: the `mcause` of the trap when `trap` is 1 |
| `mret` | 1 | comb | an MRET returns in this cycle (`io.mret.valid`) |
| `irq` | 1 | comb | an interrupt is pending, enabled in `mie`, and `mstatus.MIE` is set (`io.irq.valid`): the core takes it at the next instruction boundary it may |
| `mip` | 32 | state | the `mip` CSR, from the interrupt lines (`io.mip`; the CLINT's `mtip` and `msip` are registers) |
| `mie`, `mstatus`, `mtvec`, `mepc`, `mcause` | 32 | state | the CSRs as they are after the edge that starts the cycle (`CsrFile.mie`, ...); a trap taken in cycle N shows its `mepc` and `mcause` in cycle N + 1 |
| `wfi` | 1 | state | a WFI waits (T3: `CPU.wfi_waiting`; T4, T5: MEM holds a WFI that waits, `mem_waiting`) |

### `pipe` (T4, T5)

The five stages of `mycpu.pipeline.CPU`. `valid`, `pc` and `inst` describe what a stage holds in
this cycle; `pc` and `inst` mean something only when `valid` is 1 (a bubble keeps whatever its
register held, often the instruction that ID still has). `stall`, `flush` and `bubble` (comb)
are the inputs of the stage's register for the next rising edge: `stall`, the register is not
enabled to take a new instruction, so it keeps its own; `flush`, its flush input (a taken branch
or jump, a FENCE.I, a trap or an MRET); `bubble`, the input it would take is a bubble (the stage
before it has nothing for it, or holds its instruction back). They are the core's signals
(`mycpu.pipeline.PipeProbes`), not combined into a decision: two of them can be 1 in one cycle
(a held instruction that a trap flushes), and what the register then did is in the next cycle's
record, as the stage register decides it (`Stage`, exercise 4.1).

| Stage | `valid`, `pc`, `inst` | `stall` | `flush` | `bubble` |
|-------|-----------------------|---------|---------|----------|
| `fetch` | the word the fetch unit keeps in this cycle (`Fetch.queue.io.enq`); comb, not state: IF has no stage register, the queue is its output | 0 | `Fetch.io.redirect.valid`: everything requested or held is dropped | no word is kept (`enq.valid` is 0) |
| `decode` | the head of the fetch unit's queue, which ID decodes (`Fetch.io.deq`) | ID does not hand its instruction on (`valid` and not `deq.ready`) | `Fetch.io.redirect.valid`: the queue is flushed, and with it ID's instruction unless it leaves for EX in this cycle (a branch resolved in ID, a FENCE.I) | 0: the queue holds no bubble |
| `execute` | the ID/EX register (`CPU.ex`) | EX cannot hand its instruction on (`valid` and not `ex_free`: the divider, or MEM is busy), so the register's input is its own instruction | `flush_id_ex` | the register's input is a bubble (`ex_next.valid` is 0): a hazard stall, a serialization, an empty queue |
| `memory` | the EX/MEM register (`CPU.mem`), the commit stage | the register is not enabled (`valid` and not `mem_free`: MEM waits for the data port or in a WFI) | `flush_ex_mem` | the register's input from EX is a bubble (`ex_mem_next.valid` is 0; it takes it when enabled) |
| `writeback` | `valid`: the MEM/WB register holds a register write (`CPU.wb.valid`); `pc` and `inst` are 0, since WB keeps no PC: its instruction is the one whose retire record was in the cycle before | 0 | 0 | the instruction in MEM writes no register (`WriteBack.io.reg_wen` is 0) |

| Field | Bits | Class | Meaning (signal) |
|-------|-----:|-------|------------------|
| `forwardA`, `forwardB` | 2 | comb | **0 in T4 and T5, not observed.** The select is inside the bootcamp's `Forward` (notebook 5.7), which is an exercise; a probe would have to restate it. A view shows the forwarding paths as concepts |
| `loadUseStall` | 1 | comb | the hazard unit holds the instruction in ID (`HazardUnit.io.stall`): in `Stall` for every data hazard, in `Forward` and `EarlyBranch` for a value that only MEM or WB has in time. It is the hpm load-use-stall event (4) when nothing else holds ID |

The browser tests of the pipeline view (web/pipeline/) count, for each sequence of `HazardSpec`,
the cycles with `loadUseStall` while ID holds the consumer, and the bubbles in EX between a taken
branch and its target, and compare them with the spec's numbers for each variant.

### `bus` (T5)

The five AXI4-Lite channels of the data master's interface (`Soc.d_master.io.axi`), as they
are, all comb: `aw` and `ar` {`valid`, `ready`, `addr` 32}, `w` {`valid`, `ready`, `data` 32,
`strb` 4}, `b` {`valid`, `ready`, `resp` 2}, `r` {`valid`, `ready`, `data` 32, `resp` 2}. A
transfer happens in a cycle with `valid` and `ready` both 1. The instruction master is not
probed; `pipe.fetch` shows what it returns.

### `vga` (T5)

`hsync`, `vsync`, `active`, `tick` 1, `rgb` 24, `x` 10, `y` 10, all state: the video outputs of
`mycpu.soc.Vga` (`SimIO.vga`, `VgaIO`; `swaps`, a register count and no video signal, is not
recorded). They describe a pixel only in a cycle in which `tick` is 1. A frame takes 840,000
cycles, far more than a bundle's window holds; the pictures themselves come with the bundle as
`+vga-dump` frames, and `stores.json` holds the stores that drew them (see "Bundles").

## Versioning

The version is `<major>.<minor>`, in the schema's `x-version`, in the trace header and in every
bundle.

- **Minor** (1.0 → 1.1): a field or a group is added, or a new optional part is added to the
  manifest. An old bundle stays valid under the new schema.
- **Major** (1.x → 2.0): a field is removed or renamed, its width changes, or its meaning or
  sampling class changes, even when the name stays. The schema file gets a new name
  (`obs-v2.json`).

`obs-validate.py` refuses a bundle of another major version, and a bundle of a newer minor version
than its schema (a newer bundle may hold fields that the older schema does not know).

**1.1** (for the SoC and VGA views) added two optional parts to the manifest: `stores` (the file
`stores.json`) and the `cycle` of a VGA frame. Bundles of 1.0, the shipped examples among them,
stay valid. The harness writes 1.1 in the trace header from then on. The `csr`, `pipe`, `bus` and
`vga` groups were in 1.0 already; the tables above say what the tops of T3 to T5 connect to them,
and where the core gave a field no signal of its own (`pipe.forwardA`, `forwardB`, `writeback.pc`).

## Trace file (`+obs-trace`)

```
Vsim +elf=<prog.elf> +obs-trace=<file> +obs-task=T1 +obs-variant=single-cycle \
     [+obs-start=N] [+obs-cycles=N] [+retire-log=<file>] [+uart-log=<file>]
```

`+obs-start` (default 0) and `+obs-cycles` (default 0, meaning up to the end) bound the window.
The run itself always goes to its end, so that the end counts are those of the whole run. The
file has three parts (`sim/obs.h`). All integers are little-endian.

1. **Header:** one line of JSON. It holds the header fields above, `first` and `limit` (the
   window), `groups`, `record_bytes`, and `fields`: for every field its `name`, `bits`, byte
   `offset` and `bytes`. A field takes 1, 2 or 4 bytes by its width, and the fields are packed in
   the order that `TopGen` lists them. They follow the probe groups, then `retire.*`.
2. **Records:** `record_bytes` per cycle, for consecutive cycles from `first`.
3. **Trailer:** a JSON object, its length as a 64-bit integer, then the 8 bytes `OBSEND01`. The
   object holds `records`, `end` (`cycles`, `instret` and `how`, as the retire log's `end` line
   has them) and `outputs`: the SHA-256 of the retire log, the UART log and each `+vga-dump`
   frame that the same run wrote (not the empty file of a frame it never showed), computed after
   the harness closed them. If a hash cannot be computed, the trailer is not written. `frames`
   (from 1.1) gives, for each `+vga-dump` frame written, the cycle in which its last visible pixel
   was sampled.

A file without the trailer was cut off, and the reader refuses it. So does a file whose record
count is not the trailer's, or not the window's.

**Why binary and not NDJSON (measured on T1, Dhrystone with `LOOPS=500`, 276,699 cycles):**

| Output | Bytes per cycle | Whole run | Host instructions per cycle (`perf stat`) |
|--------|----------------:|----------:|------------------------------------------:|
| no output | – | – | 2,748 |
| `+obs-trace` (binary, 123 B/record on T1, 141 on T2) | 123 | 34.0 MB | +1,939 |
| `+retire-log` (text, for comparison) | 98 | 27.1 MB | +5,131 |
| the same records as JSON (the bundle's form) | 659 | 182 MB (computed) | not measured |

The binary trace is 5.4 times smaller than the same data as JSON lines. The harness writes it with
no formatting at all, and it costs 40 % of the host instructions that the text retire log does.
The JSON form is made only for the window that a bundle keeps, by `obs-pack.py`.

**Speed of a run without `+obs-trace`.** Measured with Dhrystone `LOOPS=20000`
(9,814,175 cycles), counting the host's user-mode instructions (`perf stat -e instructions:u`,
3 runs each). Instructions do not depend on the load of the shared host. Wall time does, by
±15 % here.

| Model | Host instructions | Per cycle | Wall time (3 runs, loaded host) |
|-------|------------------:|----------:|--------------------------------:|
| harness and model before the probe ports | 25,892,287,753 | 2,638 | 5.84 s (1.68 M cycles/s) |
| this harness, T1 top with `obs=0` (no probe ports) | 25,931,549,606 | 2,642 (+0.15 %) | 6.04 s |
| this harness, T1 top with its probes (the default) | 26,311,868,537 | 2,681 (+1.6 %) | 5.94 s (1.65 M cycles/s) |

The probe ports cost 1.6 % in host instructions. Verilator must keep those signals as outputs,
where it could fold some of them away before. The harness adds one comparison per cycle. This is
below the noise of wall time on a shared host. A model without probes comes from the task's
`Emit` with `obs=0`, for example `sbt "singleCycle/runMain mycpu.singlecycle.Emit <dir> obs=0"`.

## Bundles

A bundle is a directory:

```
manifest.json            header, run, groups, chunk index, other files, sources
cycles/<first>.json      a chunk: {"first", "count", "cycles": [cycle record, ...]}
uart.bin                 the UART log of the run, if packed
vga/frame-<N>.ppm        +vga-dump frames, if packed
stores.json              the run's stores to an address range, if packed (1.1)
```

A chunk holds `--chunk-cycles` consecutive cycles (1024 by default) as the schema's `cycle`
objects. The manifest lists each chunk with `first`, `count`, `bytes` and `sha256`, so a viewer
fetches only the chunks of the window it shows. `run` holds `cycles`, `instret` and `how` (the
`end` line), `first`, `count` and `limit` (the window). `tests` holds the results of the task's
checks for this model and program: `make obs` records the exit code and the console output
against `expected.txt`. `sources` holds the SHA-256 of the trace, the retire log and the UART log
that the bundle was packed from.

Each entry of `vga` has the frame's number and, from 1.1, its `cycle` (the trailer's `frames`).
`stores` (1.1, only with `--stores LO:HI`) lists `stores.json`: every record of the whole run's
retire log that stores to a byte address in `[lo, hi)`, as `[cycle, pc, addr, bytes, data]` in
the order of the log, with `lo`, `hi` and the `count`. A VGA frame is drawn long before its
window, by stores whose records are outside the window; with the VGA's range
(`20000000:20010000`) a view finds the store behind a pixel: frame N shows buffer N mod 2 (a
swap flips the buffer that is shown), pixel (x, y) is in word 20 × ⌊y / 4⌋ + ⌊x / 32⌋ of it,
and the last whole-word store to that word in a cycle up to the frame's `cycle` wrote it,
provided that the program left the word and the palette alone while the frame was scanned out
(the 767,678 cycles from its first visible pixel to its last, with the VGA's two cycles per
pixel), which `Vga.scala` makes the condition for a frame to be what the program drew. The VGA
view marks a pixel whose word or palette entry was written in that time as uncertain.

```
obs-pack.py --trace run.obs --retire-log run.log [--uart-log run.uart] [--vga N:frame.ppm]...
            [--stores LO:HI] [--tests tests.json] [--elf prog.elf] [--chunk-cycles N] [--force]
            <bundle>
obs-validate.py <bundle> <retire log>          # or: --no-retire-log <bundle>
```

**Refusing parts of different runs.** `obs-pack.py` packs nothing unless the parts come from
the same run:

- the retire log, the UART log and every frame must have the SHA-256 that the trace's trailer
  holds for that run;
- the log's `end` line must equal the trailer's end;
- the log's records must equal the trace's retire records cycle for cycle;
- `--elf` must hash to `elf_sha256`;
- the test results must name the trace's commit and program, and the SHA-256 of the run's
  retire log (`retire_log_sha256`, which `make obs` writes into them).

It writes the bundle to `<bundle>.partial` and renames it only when the bundle is complete.

**What `obs-validate.py` checks.**

- The manifest, every chunk and every record satisfy the schema. The validator implements only
  the keywords that the schema uses (`scripts/obslib.py`) and refuses a schema that uses any
  other, so a check that it does not make cannot pass unnoticed. It uses the standard library
  only.
- The versions are compatible.
- Every file has its listed size and hash.
- The chunks hold the window cycle after cycle, and every record has exactly the listed groups.
- With the retire log: the log is the one packed (its hash), and its `mark` and `end` counts
  hold. Its `end` line equals the bundle's `run`. Every cycle of the window has the same record
  line in the bundle as in the log, or no record in both. `stores.json`, if the bundle has one,
  holds exactly the log's records that store to its range.
- With `--no-retire-log`, the last checks are skipped, and the output says so. A bundle without a
  cycle is refused.

`sim/tests/obs-tests.py`, run by `make check-obs`, checks each refusal: parts of two runs, traces
cut in the trailer or in the records or with a record too many, a doctored retire record with and
without a matching manifest, a dropped record, a log with another end or a wrong mark, a missing
chunk, a value wider than its field, an extra group, schema versions, an unimplemented
schema keyword, and for `--stores` (on the uart program's stores to the UART) a doctored and a
dropped store and two ranges that are none.

## Size

Measured with `make check-obs`. Dhrystone (`LOOPS=500`) is limited to its first 10,000 cycles,
and the chunks hold 1024 cycles each.

| Task | Groups | Bundle | Per cycle | Chunks with `gzip -9` |
|------|--------|-------:|----------:|---------------------:|
| T1 | core, uart, retire | 6,597,408 B | 660 B | 151,922 B |
| T2 | core, div, uart, retire | 7,774,882 B | 777 B | 177,605 B |
| T3 | core, csr, uart, retire | 8,434,982 B | 843 B | 191,716 B |
| T4 (`Forward`) | csr, pipe, uart, retire | 7,774,138 B | 777 B | 101,957 B |
| T5 (`Forward`) | csr, pipe, bus, uart, vga, retire | 10,451,306 B | 1,045 B | 183,899 B |

**Budget: a 10,000-cycle Dhrystone bundle is at most 9,000,000 bytes** (`OBS_BUDGET_BYTES` in
`sim/obs.mk`, checked by `make check-obs`) on T1 to T4, which leaves 6 % of room over T3, and
**12,000,000 bytes on T5** (`5-soc/Makefile`), whose `bus` and `vga` groups add 26 fields: 15 %
of room. The chunks are plain JSON so that a page can read them without a decoder. They compress
by a factor of 43 to 76, which a web server's transfer compression gives for free.

## Commands

| Command | What it does |
|---------|--------------|
| `make -C 1-single-cycle obs PROG=add` | run, pack and validate `build/t1/obs/add/bundle` |
| `make -C 2-rv32im obs PROG=muldiv OBS_START=1000 OBS_CYCLES=5000` | a window of a run |
| `make -C 2-rv32im obs PROG=uart OBS_INPUT=<file>` | the UART input of the run (the uart program ends at Ctrl-D) |
| `make -C 3-csr-trap obs PROG=irqtrap OBS_CYCLES=10000` | the first 10,000 cycles of irqtrap on T3: six timer interrupts |
| `make -C 4-pipeline obs PROG=hazards VARIANT=Stall` | the hazard sequences of T4-02 (`software/programs/hazards`) on one variant |
| `make -C 5-soc obs PROG=nyancat OBS_VGA=1 OBS_VGA_EXIT=1 OBS_STORES=20000000:20010000` | frame 1 of nyancat, the stores that drew it, and the run's first cycles (with `OBS_CYCLES`, `OBS_START`) |
| `make check-obs` | for each of T1 to T5 (T4 and T5 on `Forward`): bundles of `add`, `lw`, `beq` (directed assembly programs in `software/programs/`), `muldiv` and `uart` (20,000 cycles at most) that validate, the refusals above, and the Dhrystone budget |
| `sbt singleCycle/testOnly mycpu.singlecycle.ObsSpec` | each probe equals the signal it names, cycle by cycle |
| `sbt rv32im/testOnly mycpu.rv32im.T2ObsSpec` | the same for T2, the divider included |
| `sbt csrTrap/testOnly mycpu.csrtrap.CsrProbesSpec` | T3: `csr.mepc`, `mcause`, `mstatus`, `mie`, `mtvec` equal the golden model's in every cycle of a timer interrupt, an `ecall` and their `mret`s; `trap`, `cause`, `irq`, `mret`, `mip` and the CSR access fields say what each record is (`wfi` is not exercised) |
| `sbt pipeline/testOnly mycpu.pipeline.PipeProbesSpec` | per variant, on the `hazards` program: `pipe.loadUseStall` and the EX bubbles give `HazardSpec`'s counts for its 21 sequences (the numbers the browser test counts); the `stall`, `flush` and `bubble` bits of ID, EX and MEM say what the stage holds next (also on `Forward` with wait states); each stage's `inst` is the program's word at its `pc`; MEM holds each committed instruction |
| `sbt soc/testOnly mycpu.soc.BusProbeSpec` | each `bus` field equals the data master's interface as the crossbar sees it, in every cycle, with and without RAM wait states; the transfers of two stores and two loads, OKAY from the RAM and DECERR where no device is (`aw.addr` and `ar.addr`, and `b.ready` and `r.ready`, carry equal signals, so a swap of them goes unseen) |

## Interactive execution: measured, go and no-go

The site replays bundles. Two ways to step a model from the page instead were measured on
2026-11-16 (a, load average 18 to 20) and 2026-11-17 (b, 16 to 20), on the shared 48-core
host, with the T1 model of this tree
and Dhrystone built with `LOOPS=20000` (9,814,175 cycles).

**(a) A local mode: the harness behind a socket on `127.0.0.1`.** The spike was a Python HTTP
server that answers `GET /w?start=N&count=64` by running the harness with `+obs-start=N
+obs-cycles=64 +max-cycles=N+64` and returning the 64 records as JSON (`scripts/obslib.py`
reads the trace), and a page in headless Chromium (Playwright 1.63.0) that fetches it. Best of
three:

| Window starts at cycle | Harness run (server) | Request, server side | Round trip from the page |
|-----------------------:|---------------------:|---------------------:|-------------------------:|
| 0 | 15.9 ms | 21.6 ms | 48.9 to 69.7 ms |
| 100,000 | 67.4 ms | 74.7 ms | 88.7 to 151.7 ms |
| 1,000,000 | 537.6 ms | 543.0 ms | 571.6 to 699 ms |
| 5,000,000 | 2,430.3 ms | 2,433.9 ms | 2,601.9 to 3,332 ms |
| 9,000,000 | 4,587.5 ms | 4,593.1 ms | 4,221.9 to 5,036.9 ms |

The whole run without `+obs-trace` takes 4.59 s: 2.1 M cycles per second. Running again to the
cycle asked for costs about 0.5 µs per cycle on top of 16 ms for the process and the program's
load, and the page adds 30 to 50 ms for the request and the JSON of 64 records.

**Verdict (a): go, with a model that stays alive.** A step that runs the program again from
reset is interactive (under 100 ms) only up to about 100,000 cycles.
The fixed costs that remain when the model is not restarted, the request and the records, are
30 to 50 ms, so a harness mode that keeps the model and its state between commands (reset,
step N, run to a pc or a field value, read a window) and answers on a local socket is fast
enough. It needs a command loop in `sim/main.cpp` and the reading side in `lib/bundle.js`; it
is not built.

**(b) The Verilator model, the harness and the ELF loader in WebAssembly (emscripten).**
Measured on 2026-11-17 on the same host (load average 16 to 20) with emscripten 6.0.13 (emsdk
installed under `~/.local/emsdk`, `./emsdk install 6.0.13`; emsdk needs Python 3.10 or later,
`EMSDK_PYTHON` named one) and its Node.js 24.19.0. The input was the `SimTop.sv` that
`make -C 1-single-cycle verilator` generated, verilated once with the flags of `sim/task.mk`
(`--cc --assert -O3 --x-assign fast --x-initial fast --trace-fst`, no `--build`). The
generated C++, the Verilator runtime and `sim/{main,elf,obs,uart,vga}.cpp` were compiled
unchanged by `g++ -O2` and by `em++ -O2 -sUSE_ZLIB=1 -sNODERAWFS=1 -sALLOW_MEMORY_GROWTH=1`,
with lz4 1.10.0 built by `emcc -O2` (the FST writer needs it). Two things the native build does
not need were added: three functions that emscripten's libc lacks and that the Verilator
runtime calls (`pthread_getaffinity_np`, `pthread_setaffinity_np`, `sched_getcpu`, given as
returning "unknown"), and, for the 32-bit target, a copy of Verilator's
`fstcpp/fstcpp_variable_info.h` without its two `static_assert`s, because Verilator 5.054's
FST writer refuses 32-bit targets. Every build printed the same console output and
`cycles=9814175 instret=9814175` for Dhrystone `LOOPS=20000`. Best of three:

| Build | Whole run | Start, to `+max-cycles=1` | Cycles per second |
|-------|----------:|--------------------------:|------------------:|
| `build/t1/Vsim` of the task's flow (g++, Verilator's default `-Os`; 4.59 s in (a), a day before) | 4.67 s | 16 ms | 2.10 M |
| the same sources, `g++ -O2` | 4.24 s | 18 ms | 2.32 M |
| `em++ -O2`, wasm32, in Node.js | 5.49 s | 235 ms | 1.79 M (1.87 M after the start) |
| `em++ -O2 -sMEMORY64=1`, wasm64 (FST writer unchanged), in Node.js | 7.34 s | 237 ms | 1.34 M (1.38 M after the start) |

The wasm32 model runs at 77 % of the native `-O2` build (80 % after the start), 85 % of the
task's own `Vsim`; wasm64 at 58 %. The module is 361 KB of WebAssembly and 74 KB of
JavaScript; the start includes Node.js loading and compiling it. Not measured: Chromium (the same V8 engine, but
it needs the ELF in emscripten's in-memory file system instead of `NODERAWFS`).

**Verdict (b): go on speed, not the next step.** At the rate measured in Node.js, a model that
is already loaded runs 100,000 cycles in about 55 ms (after a start of 235 ms, once), without a
server, which is fast enough for interactive stepping; in a browser this is expected of the same
engine but was not measured. But the model
has to be compiled with a toolchain of 1.8 GB (emsdk with its Node.js, a 312 MB download) that
the course does not otherwise need, and the 32-bit build needs an edited copy of a Verilator
runtime header that the next Verilator release can break (its assertion says that 32-bit
targets are not supported); the 64-bit build avoids the edit at 58 % of the native `-O2` speed
(64 % of the task's `Vsim`), and needs a browser with WebAssembly Memory64, which was not
tried. A student's own design would have to go
through that toolchain each time it changes. (a) uses the Verilator build that every task
already has, so it stays the first candidate; (b) is the one to take up if the site must run
without a local process. Hosted execution stays out of scope.

## Limits

- A probe shows a value, never how it was computed: the schema has no field that restates the
  logic of an exercise. `branchTaken`, for instance, is the redirect that the execute stage
  outputs, not the comparison behind it.
- `core` describes a single-cycle datapath. T4 and T5 have `pipe` for their stages instead.
- The probes show logic levels of the RTL, not timing inside a cycle.
