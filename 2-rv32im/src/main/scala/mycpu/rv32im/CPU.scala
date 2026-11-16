// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.util._
import mycpu.common.{MemMap, TrapRetire}
import mycpu.singlecycle.{InstructionFetch, MemoryAccess, WriteBack}
import riscv.HaltCause

/** The RV32IM_Zifencei core (T2): T1's single-cycle core with the M extension. An instruction
  * still takes one clock cycle, with two exceptions: one that waits for a device (as in T1), and
  * a division, which waits for the iterative divider (`divLatency` cycles, or one for a division
  * by zero and for the signed overflow). While an instruction waits, the core stalls: nothing
  * is retired and no state changes.
  *
  * The stages are T1's (`mycpu.singlecycle`: fetch, memory access and write-back, with the
  * student's own answers in them), except decode and execute, which are this task's
  * ([[InstructionDecode]], [[Execute]]). The ports are T1's:
  *   - `imem`, `dmem`: the instruction and data ports (bootcamp `riscv.MemPort`).
  *   - `retire`: one record per instruction, in program order, in the cycle the instruction
  *     completes (`mycpu.common.TrapRetire`; `trap` is false, `csr` is never valid, `mip` is 0).
  *     A division retires in the cycle of the divider's response.
  *   - `halted`: the core has halted, from the cycle after the halting instruction retired. The
  *     causes are T1's (there are no traps before T3); M adds none.
  *
  * @param divLatency
  *   the divider's latency, one of `Divider.latencies`
  * @param mutant
  *   a bug to inject for negative tests, one of `Execute.mutants`; `None` in every real build
  */
class CPU(
    resetPc: Long = MemMap.ramBase,
    divLatency: Int = Divider.DIV_LATENCY,
    mutant: Option[String] = None
) extends Module {
  val io = IO(new Bundle {
    val imem   = new riscv.MemPort
    val dmem   = new riscv.MemPort
    val retire = Output(Valid(new TrapRetire))
    val halted = Output(Bool())
  })

  val halted = RegInit(false.B)
  // as in T1: nothing is requested, started or retired while reset is held
  val active = !halted && !reset.asBool

  val fetch      = Module(new InstructionFetch(resetPc))
  val decode     = Module(new InstructionDecode)
  val regs       = Module(new riscv.RegFile)
  val execute    = Module(new Execute(divLatency, mutant))
  val memory     = Module(new MemoryAccess)
  val write_back = Module(new WriteBack)
  val d          = decode.io.decoded

  fetch.io.imem <> io.imem
  fetch.io.active   := active
  fetch.io.redirect := execute.io.redirect
  decode.io.inst    := fetch.io.inst

  regs.io.rs1     := d.rs1
  regs.io.rs2     := d.rs2
  regs.io.dbgAddr := 0.U

  execute.io.valid    := active && fetch.io.inst_valid
  execute.io.kill     := false.B // nothing abandons an instruction once it has started
  execute.io.pc       := fetch.io.pc
  execute.io.decoded  := d
  execute.io.m        := decode.io.m
  execute.io.rs1_data := regs.io.rs1Data
  execute.io.rs2_data := regs.io.rs2Data

  memory.io.enable     := active && fetch.io.inst_valid
  memory.io.load       := d.load
  memory.io.store      := d.store
  memory.io.funct3     := d.funct3
  memory.io.addr       := execute.io.alu_result
  memory.io.store_data := regs.io.rs2Data
  memory.io.dmem <> io.dmem

  // T1's halt causes and their order (the student's own answer to exercise 1.9)
  val cause = mycpu.singlecycle.CPU.haltCause(fetch, d, execute.io.target_misaligned, memory)
  val halts = cause =/= HaltCause.Running

  // T1's condition: the instruction was fetched, and its data access, if any, was answered
  val served = active && fetch.io.inst_valid && memory.io.done

  // [CA26: Exercise 2.6] The stall: an instruction that takes more than one cycle
  // Contract: `completes` is high in the one cycle in which the instruction at the PC is over:
  // nothing that can make it wait has anything left to wait for. Until then the PC, the
  // register file and the retire port stay as they are, which the lines below see to once
  // `completes` is right (bootcamp 5.4; handout §T2.5).
  val completes = ?
  val commit    = completes && !halts
  when(completes && halts) {
    halted := true.B
  }
  io.halted       := halted
  fetch.io.commit := commit

  write_back.io.commit     := commit
  write_back.io.rd_write   := d.rd_write
  write_back.io.rd         := d.rd
  write_back.io.rd_source  := d.rd_source
  write_back.io.alu_result := execute.io.result // the execute stage's value: ALU, multiplier or divider
  write_back.io.load_value := memory.io.load_value
  write_back.io.pc_plus4   := fetch.io.pc_plus4
  regs.io.wen              := write_back.io.reg_wen
  regs.io.rd               := d.rd
  regs.io.wdata            := write_back.io.reg_wdata

  // T1's retire record (exercise 1.10). The address of an access is the ALU's output, not `result`.
  io.retire.valid := completes
  io.retire.bits  := mycpu.singlecycle.CPU.retireRecord(fetch, d, execute.io.alu_result, memory, write_back, cause)
}
