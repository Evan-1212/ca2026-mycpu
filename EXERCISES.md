<!-- SPDX-License-Identifier: MIT -->
# Exercises

This file is generated. Each row of a blank table is one blank: a `?` in the source, under a
`[CA26: Exercise <task>.<n>]` comment that states what the missing code must do. An exercise
may have several blanks. A blank does not compile (`not found: value ?`) until it is replaced.
`make check-blanks` lists the blanks that remain.

A find-the-bug exercise has no blank: the code under its header compiles, but somewhere below
the header, in the same file, it breaks the rule that the header states. Find the bug with the
tests, a retire log or a waveform, fix it, and say in your report which test or run showed it,
in which cycle and signal, and why the fix is right.

## Task 1: `1-single-cycle`

10 exercises: 14 blanks, and 2 bugs to find.

| Exercise | File | Line | Title |
|----------|------|------|-------|
| 1.1 | `1-single-cycle/src/main/scala/mycpu/singlecycle/InstructionFetch.scala` | 58 | Next-PC selection and PC update |
| 1.2 | `1-single-cycle/src/main/scala/mycpu/singlecycle/InstructionDecode.scala` | 84 | Zifencei, and words the bootcamp decoder does not know |
| 1.2 | `1-single-cycle/src/main/scala/mycpu/singlecycle/InstructionDecode.scala` | 85 | Zifencei, and words the bootcamp decoder does not know |
| 1.2 | `1-single-cycle/src/main/scala/mycpu/singlecycle/InstructionDecode.scala` | 86 | Zifencei, and words the bootcamp decoder does not know |
| 1.4 | `1-single-cycle/src/main/scala/mycpu/singlecycle/Execute.scala` | 69 | Control transfers: the redirect, and a target that is no instruction address |
| 1.5 | `1-single-cycle/src/main/scala/mycpu/singlecycle/MemoryAccess.scala` | 70 | Alignment, and the byte lanes of a store |
| 1.6 | `1-single-cycle/src/main/scala/mycpu/singlecycle/MemoryAccess.scala` | 89 | Sending the request, waiting for the answer, and what the access moved |
| 1.6 | `1-single-cycle/src/main/scala/mycpu/singlecycle/MemoryAccess.scala` | 90 | Sending the request, waiting for the answer, and what the access moved |
| 1.6 | `1-single-cycle/src/main/scala/mycpu/singlecycle/MemoryAccess.scala` | 91 | Sending the request, waiting for the answer, and what the access moved |
| 1.6 | `1-single-cycle/src/main/scala/mycpu/singlecycle/MemoryAccess.scala` | 92 | Sending the request, waiting for the answer, and what the access moved |
| 1.8 | `1-single-cycle/src/main/scala/mycpu/singlecycle/WriteBack.scala` | 38 | The write-back value, and when a register really changes |
| 1.8 | `1-single-cycle/src/main/scala/mycpu/singlecycle/WriteBack.scala` | 39 | The write-back value, and when a register really changes |
| 1.9 | `1-single-cycle/src/main/scala/mycpu/singlecycle/CPU.scala` | 149 | The halt causes, stage by stage |
| 1.10 | `1-single-cycle/src/main/scala/mycpu/singlecycle/CPU.scala` | 184 | The retire record of an instruction that completes |

Find the bug:

| Exercise | Title | Header |
|----------|-------|--------|
| 1.3 | Find the bug: what an instruction does with rd | `1-single-cycle/src/main/scala/mycpu/singlecycle/InstructionDecode.scala`, line 60 |
| 1.7 | Find the bug: address decode, and unmapped addresses | `1-single-cycle/src/main/scala/mycpu/singlecycle/DataBus.scala`, line 32 |

## Task 2: `2-rv32im`

6 exercises: 14 blanks, and 1 bug to find.

| Exercise | File | Line | Title |
|----------|------|------|-------|
| 2.1 | `2-rv32im/src/main/scala/mycpu/rv32im/Decode.scala` | 173 | The rows of the decode table, and the result of the execute stage |
| 2.1 | `2-rv32im/src/main/scala/mycpu/rv32im/Decode.scala` | 206 | The rows of the decode table, and the result of the execute stage |
| 2.1 | `2-rv32im/src/main/scala/mycpu/rv32im/Decode.scala` | 212 | The rows of the decode table, and the result of the execute stage |
| 2.1 | `2-rv32im/src/main/scala/mycpu/rv32im/Decode.scala` | 213 | The rows of the decode table, and the result of the execute stage |
| 2.1 | `2-rv32im/src/main/scala/mycpu/rv32im/Execute.scala` | 110 | The rows of the decode table, and the result of the execute stage |
| 2.2 | `2-rv32im/src/main/scala/mycpu/rv32im/Multiplier.scala` | 41 | One signed multiplier for four instructions |
| 2.2 | `2-rv32im/src/main/scala/mycpu/rv32im/Multiplier.scala` | 44 | One signed multiplier for four instructions |
| 2.3 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 168 | The iteration step |
| 2.4 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 134 | Special cases and signs |
| 2.4 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 136 | Special cases and signs |
| 2.4 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 137 | Special cases and signs |
| 2.4 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 152 | Special cases and signs |
| 2.4 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 174 | Special cases and signs |
| 2.5 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 185 | Handshake, state transitions and kill |

Find the bug:

| Exercise | Title | Header |
|----------|-------|--------|
| 2.6 | Find the bug: the stall of a division | `2-rv32im/src/main/scala/mycpu/rv32im/Execute.scala`, line 66 |

## Task 3: `3-csr-trap`

10 exercises, 12 blanks.

| Exercise | File | Line | Title |
|----------|------|------|-------|
| 3.1 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 263 | CSR instructions: what they write, whether they write, and which are illegal |
| 3.1 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 275 | CSR instructions: what they write, whether they write, and which are illegal |
| 3.2 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 350 | Trap entry and return |
| 3.2 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 377 | Trap entry and return |
| 3.3 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 339 | The interrupt request |
| 3.4 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 358 | Where a trap goes |
| 3.5 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 329 | Counting, inhibiting and writing the counters |
| 3.6 | `3-csr-trap/src/main/scala/mycpu/csrtrap/Clint.scala` | 96 | The timer registers and the timer interrupt |
| 3.7 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CPU.scala` | 204 | Traps and commit: what an instruction that traps leaves undone |
| 3.8 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CPU.scala` | 307 | Which exception an instruction raises |
| 3.9 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CPU.scala` | 155 | Taking an interrupt at an instruction boundary |
| 3.10 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CPU.scala` | 195 | WFI: waiting for an interrupt |

## Task 4: `4-pipeline`

8 exercises, 10 blanks.

| Exercise | File | Line | Title |
|----------|------|------|-------|
| 4.1 | `4-pipeline/src/main/scala/mycpu/pipeline/Stage.scala` | 63 | A stage register with enable and flush |
| 4.2 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 216 | Stalls in ID, and forwarding into ID |
| 4.2 | `4-pipeline/src/main/scala/mycpu/pipeline/Hazard.scala` | 77 | Stalls in ID, and forwarding into ID |
| 4.3 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 502 | Flushes and redirects, FENCE.I included |
| 4.3 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 512 | Flushes and redirects, FENCE.I included |
| 4.4 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 466 | Serializing CSR instructions, mret, wfi and fence.i |
| 4.5 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 396 | The commit stage: trap or commit |
| 4.6 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 386 | Where an interrupt is taken |
| 4.7 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 271 | The divider in the pipeline |
| 4.8 | `4-pipeline/src/main/scala/mycpu/pipeline/Fetch.scala` | 87 | A fetch unit for a memory of any latency |

## Task 5: `5-soc`

8 exercises: 11 blanks, and 1 bug to find.

| Exercise | File | Line | Title |
|----------|------|------|-------|
| 5.1 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteMaster.scala` | 113 | The master: each VALID until its own READY, and the response to the core |
| 5.1 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteMaster.scala` | 124 | The master: each VALID until its own READY, and the response to the core |
| 5.1 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteMaster.scala` | 127 | The master: each VALID until its own READY, and the response to the core |
| 5.2 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteXbar.scala` | 167 | The grant |
| 5.2 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteXbar.scala` | 209 | The grant |
| 5.3 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteXbar.scala` | 87 | The way back, and an address that no slave owns |
| 5.3 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteXbar.scala` | 121 | The way back, and an address that no slave owns |
| 5.4 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteSlave.scala` | 67 | The word of a synchronous-read memory on the R channel |
| 5.6 | `5-soc/src/main/scala/mycpu/soc/Vga.scala` | 177 | Video timing: scan counters, visible area and sync pulses |
| 5.7 | `5-soc/src/main/scala/mycpu/soc/Vga.scala` | 195 | Pixel fetch: framebuffer address, x4 scaling and pipeline alignment |
| 5.8 | `5-soc/src/main/scala/mycpu/soc/Vga.scala` | 245 | Buffer swap at vblank |

Find the bug:

| Exercise | Title | Header |
|----------|-------|--------|
| 5.5 | Find the bug: the protocol checker | `5-soc/src/main/scala/mycpu/soc/Axi4LiteChecker.scala`, line 48 |
