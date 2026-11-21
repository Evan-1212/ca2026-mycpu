<!-- SPDX-License-Identifier: MIT -->
# From the Bootcamp to the Exercises

Every exercise of this repository builds on something you did in
[chisel-bootcamp](https://github.com/sysprog21/chisel-bootcamp). The comment above each blank
(`[CA26: Exercise <task>.<n>]`) states a contract and cites the notebooks and the specification
text behind it. This file collects those citations in one place and adds, where a notebook has
a section on the topic, the section to reread first.

It names where to look, not what to write: no entry describes the content of a blank.

Notebook numbers and section titles are those of chisel-bootcamp at the commit this repository
pins. "Unprivileged spec" is *The RISC-V Instruction Set Manual, Volume I: Unprivileged
Architecture*; its chapters are cited by name, as in the exercise comments. "Handout" is the
[course handout](https://hackmd.io/@sysprog/arch2026-lab3), whose sections are numbered by task.

## Notebooks used

| Notebook | Title | Used by |
|----------|-------|---------|
| 2.3 | Control Flow | 3.1, 5.3, 5.8 |
| 2.4 | Sequential Logic | 2.2, 2.3, 2.4, 3.4, 3.5, 3.6, 5.4, 5.5, 5.6, 5.7 |
| 2.6 | Testing with ChiselSim | 2.5, 2.6, 4.8 |
| 2.7 | Enums, Decoders and Bundles | 2.2, 2.3, 2.4, 2.5 |
| 2.8 | Memories | 1.5 |
| 3.2_interlude | Chisel Standard Library | 5.1, 5.2 |
| 5.1 | The RV32I Instruction Set | 1.3, 2.1 |
| 5.2 | The ALU, the Immediates and the Registers | 1.8, and the modules T1 instantiates (`Alu`, `ImmGen`, `RegFile`) |
| 5.3 | The Decoder | 1.2, 2.1 |
| 5.4 | A Single-Cycle RV32I Processor | 1.1, 1.3, 1.4, 1.6, 1.8, 1.9, 1.10, 2.6 |
| 5.5 | The Official RISC-V Tests | the riscv-tests that `make test-riscv-tests` runs |
| 5.6 | A Five-Stage Pipeline | 4.1, and `PipelineFlush`, which T4 instantiates |
| 5.7 | Hazards | 4.2, and `Forward`, which T4 instantiates |
| 5.8 | A Bus and a UART | 1.7 |

The bootcamp's `riscv/DESIGN.md` (halt causes, the retire port) is cited by 1.9 and 1.10.

## T1: `1-single-cycle`

| Exercise | Title | File | Bootcamp notebook: section | Also cited |
|----------|-------|------|----------------------------|------------|
| 1.1 | Next-PC selection and PC update | `InstructionFetch.scala` | 5.4: The datapath | handout §T1.1 |
| 1.2 | Zifencei, and words the bootcamp decoder does not know | `InstructionDecode.scala` | 5.3: The control signals; From the table to logic | Unprivileged spec, "Zifencei"; handout §T1.6 |
| 1.3 | Find the bug: what an instruction does with rd | `InstructionDecode.scala` | 5.1: The six instruction formats. 5.4: The datapath | handout §T1.2 |
| 1.4 | Control transfers: the redirect, and a target that is no instruction address | `Execute.scala` | 5.4: Exercise 1: the branch comparator; The datapath | Unprivileged spec, "Control Transfer Instructions"; handout §T1.3 |
| 1.5 | Alignment, and the byte lanes of a store | `MemoryAccess.scala` | 2.8: Write masks. 5.4: Memory | Unprivileged spec, "Load and Store Instructions"; `riscv.MemReq`; handout §T1.4 |
| 1.6 | Sending the request, waiting for the answer, and what the access moved | `MemoryAccess.scala` | 5.4: Memory; The datapath | `TrapRetire` in `common/src/main/scala/mycpu/common/Sim.scala`; handout §T1.4, §T1.5 |
| 1.7 | Find the bug: address decode, and unmapped addresses | `DataBus.scala` | 5.8: The bus | handout §T1.5 |
| 1.8 | The write-back value, and when a register really changes | `WriteBack.scala` | 5.4: The datapath. 5.2: The register file | handout §T1.2, §T1.7 |
| 1.9 | The halt causes, stage by stage | `CPU.scala` | 5.4: Halting | bootcamp `riscv/DESIGN.md`; handout §T1.7 |
| 1.10 | The retire record of an instruction that completes | `CPU.scala` | 5.4: The datapath; Comparing with the golden model | `TrapRetire`, `riscv.RetireInfo`; handout §T1.7 |

## T2: `2-rv32im`

| Exercise | Title | File | Bootcamp notebook: section | Also cited |
|----------|-------|------|----------------------------|------------|
| 2.1 | The rows of the decode table, and the result of the execute stage | `Decode.scala`, `Execute.scala` | 5.1: The six instruction formats. 5.3: The control signals; The table; Exercise: complete the table | Unprivileged spec, "M" extension and the instruction listings; handout §T2.1, §T2.5 |
| 2.2 | One signed multiplier for four instructions | `Multiplier.scala` | 2.4 and 2.7 (widths and signed arithmetic) | Unprivileged spec, "M" extension, multiplication operations; handout §T2.2 |
| 2.3 | The iteration step | `Divider.scala` | 2.4 (widths). 2.7: ChiselEnum; switch and is (a datapath under a state machine) | handout §T2.3 |
| 2.4 | Special cases and signs | `Divider.scala` | 2.4 and 2.7 (two's complement) | Unprivileged spec, "M" extension, division operations and the table of their special cases; handout §T2.3 |
| 2.5 | Handshake, state transitions and kill | `Divider.scala` | 2.6: Modules with Decoupled Interfaces. 2.7: ChiselEnum; switch and is | handout §T2.4 |
| 2.6 | Find the bug: the stall of a division | `Execute.scala` | 2.6: Modules with Decoupled Interfaces. 5.4: The datapath | the header comment of `Divider`; handout §T2.5 |

## T3: `3-csr-trap`

The exercises of T3 rest on the Privileged spec more than on the bootcamp, which has no CSRs.
"Privileged spec" is *The RISC-V Instruction Set Manual, Volume II: Privileged Architecture*.

| Exercise | Title | File | Bootcamp notebook: section | Also cited |
|----------|-------|------|----------------------------|------------|
| 3.1 | CSR instructions: what they write, whether they write, and which are illegal | `CsrFile.scala` | 2.3: `MuxLookup` and bitwise operators | Unprivileged spec, "Zicsr" (CSRRW, CSRRS, CSRRC, and the table of side effects); Privileged spec §2.1; handout §T3.1 |
| 3.2 | Trap entry and return | `CsrFile.scala` | none | Privileged spec §3.1.6.1, §3.1.14 to §3.1.16, §3.3.2; handout §T3.2 |
| 3.3 | The interrupt request | `CsrFile.scala` | none | Privileged spec §3.1.9, §3.1.6.1, §3.3.3; handout §T3.3 |
| 3.4 | Where a trap goes | `CsrFile.scala` | 2.4: `Cat` | Privileged spec §3.1.7; handout §T3.2 |
| 3.5 | Counting, inhibiting and writing the counters | `CsrFile.scala` | 2.4: `Cat` | Privileged spec §3.1.10, §3.1.12; handout §T3.4 |
| 3.6 | The timer registers and the timer interrupt | `Clint.scala` | 2.4: `Cat` and bit ranges | Privileged spec §3.2.1; handout §T3.5 |
| 3.7 | Traps and commit: what an instruction that traps leaves undone | `CPU.scala` | none | `CsrFile`'s ports; handout §T3.2 |
| 3.8 | Which exception an instruction raises | `CPU.scala` | none | `ExceptionCauses`; Privileged spec §3.1.15; handout §T3.2 |
| 3.9 | Taking an interrupt at an instruction boundary | `CPU.scala` | none | handout §T3.3 |
| 3.10 | WFI: waiting for an interrupt | `CPU.scala` | none | `InterruptRequest`; Privileged spec §3.3.3; handout §T3.3 |

## T4: `4-pipeline`

| Exercise | Title | File | Bootcamp notebook: section | Also cited |
|----------|-------|------|----------------------------|------------|
| 4.1 | A stage register with enable and flush | `Stage.scala` | 5.6: `Stage` | handout §T4.1 |
| 4.2 | Stalls in ID, and forwarding into ID | `Hazard.scala`, `CPU.scala` | 5.7: `Forward` | handout §T4.2, §T4.3 |
| 4.3 | Flushes and redirects, FENCE.I included | `CPU.scala` | 5.6: `PipelineFlush` | Unprivileged spec, "Zifencei"; handout §T4.4, §T4.7 |
| 4.4 | Serializing CSR instructions, mret, wfi and fence.i | `CPU.scala` | none | Privileged spec §2.1; handout §T4.5 |
| 4.5 | The commit stage: trap or commit | `CPU.scala` | none | handout §T4.4 |
| 4.6 | Where an interrupt is taken | `CPU.scala` | none | Privileged spec §3.3.3; handout §T4.4 |
| 4.7 | The divider in the pipeline | `CPU.scala` | none | handout §T4.4 |
| 4.8 | A fetch unit for a memory of any latency | `Fetch.scala` | 2.6: `Decoupled` | handout §T4.6 |

## T5: `5-soc`

"AXI4-Lite spec" is the AMBA AXI and ACE Protocol Specification (ARM IHI 0022).

| Exercise | Title | File | Bootcamp notebook: section | Also cited |
|----------|-------|------|----------------------------|------------|
| 5.1 | The master: each VALID until its own READY, and the response to the core | `Axi4LiteMaster.scala` | 3.2_interlude: `Decoupled` | AXI4-Lite spec §A3.2.1, §A3.3.1, §A3.4.4; handout §T5.1 |
| 5.2 | The grant | `Axi4LiteXbar.scala` | 3.2_interlude: `Decoupled` | AXI4-Lite spec §A3.2.1; handout §T5.3 |
| 5.3 | The way back, and an address that no slave owns | `Axi4LiteXbar.scala` | 2.3: `Mux1H` | AXI4-Lite spec §A3.3.1, §A3.4.4; handout §T5.3 |
| 5.4 | The word of a synchronous-read memory on the R channel | `Axi4LiteSlave.scala` | 2.4 | handout §T5.4 |
| 5.5 | Find the bug: the protocol checker | `Axi4LiteChecker.scala` | 2.4: `RegNext` | AXI4-Lite spec §A3.2.1, §A3.3.1; handout §T5.2 |
| 5.6 | Video timing: scan counters, visible area and sync pulses | `Vga.scala` | 2.4: counters and widths | VESA 640 x 480 at 60 Hz; handout §T5.6 |
| 5.7 | Pixel fetch: framebuffer address, x4 scaling and pipeline alignment | `Vga.scala` | 2.4: registers with an enable | handout §T5.7 |
| 5.8 | Buffer swap at vblank | `Vga.scala` | 2.3: `when` | handout §T5.8 |

## Bootcamp exercises that the tasks instantiate

These are your own bootcamp answers, compiled from your clone. A bug in one of them shows up as
a failure here, so `make check-bootcamp` runs the bootcamp's specs first.

| Bootcamp file | Notebook | Where this repository uses it |
|---------------|----------|-------------------------------|
| `Alu.scala` | 5.2 | T1 `Execute` |
| `DecodeRows.scala` | 5.3 | T1 `InstructionDecode` (through `riscv.Decoder`), T2 `Decode` |
| `BranchUnit.scala` | 5.4 | T1 `Execute` |
| `LoadExtend.scala` | 5.4 | T1 `MemoryAccess` |
| `UartTx.scala` | 5.8 | `mycpu.common.Uart` |
| `PipelineFlush.scala` | 5.6 | T4 `CPU` |
| `Forward.scala` | 5.7 | T4 `CPU` |

[bootcamp-interface.md](bootcamp-interface.md) lists every bootcamp symbol the sources use.
