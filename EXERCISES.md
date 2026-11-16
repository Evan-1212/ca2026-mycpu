<!-- SPDX-License-Identifier: MIT -->
# Exercises

This file is generated. Each row is one blank: a `?` in the source, under a
`[CA26: Exercise <task>.<n>]` comment that states what the missing code must do. An exercise
may have several blanks. A blank does not compile (`not found: value ?`) until it is replaced.
`make check-blanks` lists the blanks that remain.

## Task 1: `1-single-cycle`

10 exercises, 16 blanks.

| Exercise | File | Line | Title |
|----------|------|------|-------|
| 1.1 | `1-single-cycle/src/main/scala/mycpu/singlecycle/InstructionFetch.scala` | 58 | Next-PC selection and PC update |
| 1.2 | `1-single-cycle/src/main/scala/mycpu/singlecycle/InstructionDecode.scala` | 77 | Zifencei, and words the bootcamp decoder does not know |
| 1.2 | `1-single-cycle/src/main/scala/mycpu/singlecycle/InstructionDecode.scala` | 78 | Zifencei, and words the bootcamp decoder does not know |
| 1.2 | `1-single-cycle/src/main/scala/mycpu/singlecycle/InstructionDecode.scala` | 79 | Zifencei, and words the bootcamp decoder does not know |
| 1.3 | `1-single-cycle/src/main/scala/mycpu/singlecycle/InstructionDecode.scala` | 94 | What an instruction does with rd |
| 1.4 | `1-single-cycle/src/main/scala/mycpu/singlecycle/Execute.scala` | 59 | Control transfers: the redirect, and a target that is no instruction address |
| 1.5 | `1-single-cycle/src/main/scala/mycpu/singlecycle/MemoryAccess.scala` | 70 | Alignment, and the byte lanes of a store |
| 1.6 | `1-single-cycle/src/main/scala/mycpu/singlecycle/MemoryAccess.scala` | 89 | Sending the request, waiting for the answer, and what the access moved |
| 1.6 | `1-single-cycle/src/main/scala/mycpu/singlecycle/MemoryAccess.scala` | 90 | Sending the request, waiting for the answer, and what the access moved |
| 1.6 | `1-single-cycle/src/main/scala/mycpu/singlecycle/MemoryAccess.scala` | 91 | Sending the request, waiting for the answer, and what the access moved |
| 1.6 | `1-single-cycle/src/main/scala/mycpu/singlecycle/MemoryAccess.scala` | 92 | Sending the request, waiting for the answer, and what the access moved |
| 1.7 | `1-single-cycle/src/main/scala/mycpu/singlecycle/DataBus.scala` | 38 | Address decode: device select, the answer, and unmapped addresses |
| 1.8 | `1-single-cycle/src/main/scala/mycpu/singlecycle/WriteBack.scala` | 39 | The write-back value, and when a register really changes |
| 1.8 | `1-single-cycle/src/main/scala/mycpu/singlecycle/WriteBack.scala` | 40 | The write-back value, and when a register really changes |
| 1.9 | `1-single-cycle/src/main/scala/mycpu/singlecycle/CPU.scala` | 130 | The halt causes, stage by stage |
| 1.10 | `1-single-cycle/src/main/scala/mycpu/singlecycle/CPU.scala` | 165 | The retire record of an instruction that completes |

## Task 2: `2-rv32im`

8 exercises, 19 blanks.

| Exercise | File | Line | Title |
|----------|------|------|-------|
| 2.1 | `2-rv32im/src/main/scala/mycpu/rv32im/Decode.scala` | 173 | The rows of the decode table |
| 2.1 | `2-rv32im/src/main/scala/mycpu/rv32im/Decode.scala` | 209 | The rows of the decode table |
| 2.1 | `2-rv32im/src/main/scala/mycpu/rv32im/Decode.scala` | 215 | The rows of the decode table |
| 2.1 | `2-rv32im/src/main/scala/mycpu/rv32im/Decode.scala` | 216 | The rows of the decode table |
| 2.2 | `2-rv32im/src/main/scala/mycpu/rv32im/Multiplier.scala` | 42 | One signed multiplier for four instructions |
| 2.2 | `2-rv32im/src/main/scala/mycpu/rv32im/Multiplier.scala` | 43 | One signed multiplier for four instructions |
| 2.2 | `2-rv32im/src/main/scala/mycpu/rv32im/Multiplier.scala` | 49 | One signed multiplier for four instructions |
| 2.3 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 168 | The iteration step |
| 2.4 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 134 | Special cases and signs |
| 2.4 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 136 | Special cases and signs |
| 2.4 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 137 | Special cases and signs |
| 2.4 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 152 | Special cases and signs |
| 2.4 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 174 | Special cases and signs |
| 2.5 | `2-rv32im/src/main/scala/mycpu/rv32im/Divider.scala` | 185 | Handshake, state transitions and kill |
| 2.6 | `2-rv32im/src/main/scala/mycpu/rv32im/CPU.scala` | 95 | The stall: an instruction that takes more than one cycle |
| 2.6 | `2-rv32im/src/main/scala/mycpu/rv32im/Execute.scala` | 93 | The stall: an instruction that takes more than one cycle |
| 2.7 | `2-rv32im/src/main/scala/mycpu/rv32im/Execute.scala` | 100 | The result of the execute stage |
| 2.8 | `2-rv32im/src/main/scala/mycpu/rv32im/IssM.scala` | 38 | The golden model of M |
| 2.8 | `2-rv32im/src/main/scala/mycpu/rv32im/IssM.scala` | 49 | The golden model of M |

## Task 3: `3-csr-trap`

15 exercises, 24 blanks.

| Exercise | File | Line | Title |
|----------|------|------|-------|
| 3.1 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 272 | Which CSR instructions write, and which are illegal |
| 3.2 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 257 | The value a CSR instruction writes |
| 3.2 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 258 | The value a CSR instruction writes |
| 3.3 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 374 | Trap entry |
| 3.4 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 347 | What MRET does to mstatus |
| 3.5 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 336 | The interrupt request |
| 3.6 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 355 | Where a trap goes |
| 3.7 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CsrFile.scala` | 326 | Counting, inhibiting and writing the counters |
| 3.8 | `3-csr-trap/src/main/scala/mycpu/csrtrap/Clint.scala` | 96 | The timer registers and the timer interrupt |
| 3.9 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CPU.scala` | 204 | Traps and commit: what an instruction that traps leaves undone |
| 3.10 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CPU.scala` | 306 | Which exception an instruction raises |
| 3.11 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CPU.scala` | 155 | Taking an interrupt at an instruction boundary |
| 3.12 | `3-csr-trap/src/main/scala/mycpu/csrtrap/CPU.scala` | 195 | WFI: waiting for an interrupt |
| 3.13 | `3-csr-trap/src/main/scala/mycpu/csrtrap/IssCsr.scala` | 31 | Zicsr in the golden model |
| 3.13 | `3-csr-trap/src/main/scala/mycpu/csrtrap/IssCsr.scala` | 34 | Zicsr in the golden model |
| 3.13 | `3-csr-trap/src/main/scala/mycpu/csrtrap/IssCsr.scala` | 37 | Zicsr in the golden model |
| 3.14 | `3-csr-trap/src/main/scala/mycpu/csrtrap/IssCsr.scala` | 46 | Traps in the golden model |
| 3.14 | `3-csr-trap/src/main/scala/mycpu/csrtrap/IssCsr.scala` | 51 | Traps in the golden model |
| 3.14 | `3-csr-trap/src/main/scala/mycpu/csrtrap/IssCsr.scala` | 56 | Traps in the golden model |
| 3.15 | `3-csr-trap/src/main/scala/mycpu/csrtrap/ClintModel.scala` | 49 | The counters in the golden model |
| 3.15 | `3-csr-trap/src/main/scala/mycpu/csrtrap/ClintModel.scala` | 50 | The counters in the golden model |
| 3.15 | `3-csr-trap/src/main/scala/mycpu/csrtrap/ClintModel.scala` | 57 | The counters in the golden model |
| 3.15 | `3-csr-trap/src/main/scala/mycpu/csrtrap/ClintModel.scala` | 62 | The counters in the golden model |
| 3.15 | `3-csr-trap/src/main/scala/mycpu/csrtrap/IssCsr.scala` | 64 | The counters in the golden model |

## Task 4: `4-pipeline`

10 exercises, 10 blanks.

| Exercise | File | Line | Title |
|----------|------|------|-------|
| 4.1 | `4-pipeline/src/main/scala/mycpu/pipeline/Stage.scala` | 64 | A stage register with enable and flush |
| 4.2 | `4-pipeline/src/main/scala/mycpu/pipeline/Hazard.scala` | 77 | When an instruction must wait in ID |
| 4.3 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 216 | Forwarding into ID |
| 4.4 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 512 | Flushes and redirects |
| 4.5 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 466 | Serializing CSR instructions, mret, wfi and fence.i |
| 4.6 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 396 | The commit stage: trap or commit |
| 4.7 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 387 | Where an interrupt is taken |
| 4.8 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 271 | The divider in the pipeline |
| 4.9 | `4-pipeline/src/main/scala/mycpu/pipeline/CPU.scala` | 502 | FENCE.I |
| 4.10 | `4-pipeline/src/main/scala/mycpu/pipeline/Fetch.scala` | 87 | A fetch unit for a memory of any latency |

## Task 5: `5-soc`

9 exercises, 12 blanks.

| Exercise | File | Line | Title |
|----------|------|------|-------|
| 5.1 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteMaster.scala` | 113 | The request channels: each VALID until its own READY |
| 5.2 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteMaster.scala` | 124 | The response to the core |
| 5.2 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteMaster.scala` | 127 | The response to the core |
| 5.3 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteXbar.scala` | 167 | The grant |
| 5.3 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteXbar.scala` | 209 | The grant |
| 5.4 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteXbar.scala` | 87 | The way back, and an address that no slave owns |
| 5.4 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteXbar.scala` | 121 | The way back, and an address that no slave owns |
| 5.5 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteSlave.scala` | 67 | The word of a synchronous-read memory on the R channel |
| 5.6 | `5-soc/src/main/scala/mycpu/soc/Axi4LiteChecker.scala` | 56 | The checker's rule for a stable payload |
| 5.7 | `5-soc/src/main/scala/mycpu/soc/Vga.scala` | 167 | Video timing: scan counters, visible area and sync pulses |
| 5.8 | `5-soc/src/main/scala/mycpu/soc/Vga.scala` | 185 | Pixel fetch: framebuffer address, x4 scaling and pipeline alignment |
| 5.9 | `5-soc/src/main/scala/mycpu/soc/Vga.scala` | 236 | Buffer swap at vblank |
