// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.util._
import mycpu.common.{MemMap, TrapRetire}
import riscv.HaltCause

/** The single-cycle RV32I_Zifencei core (T1): each instruction is fetched, decoded, executed and
  * retired in one clock cycle, unless a device makes it wait (the UART's transmit FIFO is full).
  *
  * Ports:
  *   - `imem`, `dmem`: the instruction and data ports (bootcamp `riscv.MemPort`). The instruction
  *     port must answer in the cycle of the request; the data port may answer later.
  *   - `retire`: one record per instruction, in program order, in the cycle the instruction
  *     completes (`mycpu.common.TrapRetire`; this core has no traps and no CSRs, so `trap` is
  *     false, `csr` is never valid and `mip` is 0). The instruction that halts the core retires too.
  *   - `halted`: the core has halted, from the cycle after the halting instruction retired.
  *
  * There are no traps before T3, so the core halts where a later task traps, with the bootcamp's
  * causes (`riscv.HaltCause`, bootcamp riscv/DESIGN.md): `ecall`, `ebreak`, a word that is not an
  * instruction, a misaligned load, store or control-transfer target, and an address with nothing
  * behind it (a fetch, load or store). An instruction that halts changes no state: no register,
  * no memory, not the PC.
  *
  * `mutant` injects a bug for negative tests (see [[Execute]]).
  */
class CPU(resetPc: Long = MemMap.ramBase, mutant: Option[String] = None) extends Module {
  val io = IO(new Bundle {
    val imem   = new riscv.MemPort
    val dmem   = new riscv.MemPort
    val retire = Output(Valid(new TrapRetire))
    val halted = Output(Bool())
  })

  val halted = RegInit(false.B)
  // Reset is synchronous and a testbench holds it while it loads the program: until it is
  // released, the logic below sees whatever the memory holds at the reset PC. The core must not
  // act on that (a store, a write to SimCtrl), so nothing is requested or retired while in reset.
  val active = !halted && !reset.asBool

  val fetch      = Module(new InstructionFetch(resetPc))
  val decode     = Module(new InstructionDecode)
  val regs       = Module(new riscv.RegFile)
  val execute    = Module(new Execute(mutant))
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

  execute.io.pc       := fetch.io.pc
  execute.io.decoded  := d
  execute.io.rs1_data := regs.io.rs1Data
  execute.io.rs2_data := regs.io.rs2Data

  memory.io.enable     := active && fetch.io.inst_valid
  memory.io.load       := d.load
  memory.io.store      := d.store
  memory.io.funct3     := d.funct3
  memory.io.addr       := execute.io.alu_result
  memory.io.store_data := regs.io.rs2Data
  memory.io.dmem <> io.dmem

  val cause = CPU.haltCause(fetch, d, execute.io.target_misaligned, memory)
  val halts = cause =/= HaltCause.Running

  // the instruction is over in this cycle: it was fetched, and its data access, if any, was answered
  val completes = active && fetch.io.inst_valid && memory.io.done
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
  write_back.io.alu_result := execute.io.alu_result
  write_back.io.load_value := memory.io.load_value
  write_back.io.pc_plus4   := fetch.io.pc_plus4
  regs.io.wen              := write_back.io.reg_wen
  regs.io.rd               := d.rd
  regs.io.wdata            := write_back.io.reg_wdata

  io.retire.valid := completes
  io.retire.bits  := CPU.retireRecord(fetch, d, execute.io.alu_result, memory, write_back, cause)
}

/** What a core made of these stages says about the instruction in them: why it halts the core,
  * and its retire record. The core of T2 is made of the same stages around another execute
  * stage, and uses the two functions as this one does (2-rv32im/README.md): they take the stages
  * themselves, and from the execute stage the two signals they need.
  */
object CPU {

  /** Why the instruction at the PC halts the core (`Running`: it does not).
    *
    * `stage_cause(i)` is the reason that stage i finds in its own work (`Running`: none), for the
    * stages in the order in which an instruction goes through them: fetch, decode, execute
    * (`target_misaligned`, as [[Execute]] reports it) and memory access. The cause is that of the
    * first stage that has one.
    */
  def haltCause(
      fetch: InstructionFetch,
      d: DecodedInst,
      target_misaligned: Bool,
      memory: MemoryAccess
  ): HaltCause.Type = {
    val stage_cause = Wire(Vec(4, HaltCause()))

    // [CA26: Exercise 1.9] The halt causes, stage by stage
    // Contract: each stage reports the causes that the header of class `CPU` gives it, from the
    // signals of that stage alone. Several can hold for one instruction, in one stage or in more.
    // The golden model reports the one it meets first as it processes the instruction step by step
    // (bootcamp riscv/DESIGN.md), and co-simulation compares the cause: an earlier stage wins
    // below, and within a stage the order is yours to get right (handout §T1.7).
    ?

    // the cause of the first stage that has one (the last stage's, `Running` or not, if none before it has)
    PriorityMux(stage_cause.map(_ =/= HaltCause.Running), stage_cause)
  }

  /** The retire record of the instruction at the PC, to be reported in the cycle in which it
    * completes. `cause` is [[haltCause]]'s, and `alu_result` the ALU's output for the instruction
    * (`Execute.io.alu_result`). The fields that only later tasks use (`trap`, `cause`, `tval`,
    * `csr`, `mip`) stay 0.
    */
  def retireRecord(
      fetch: InstructionFetch,
      d: DecodedInst,
      alu_result: UInt,
      memory: MemoryAccess,
      write_back: WriteBack,
      cause: HaltCause.Type
  ): TrapRetire = {
    val halts = cause =/= HaltCause.Running
    // The record of an instruction that halts the core: nothing changed, and execution does not
    // continue.
    val r = Wire(new TrapRetire)
    r        := 0.U.asTypeOf(new TrapRetire)
    r.pc     := fetch.io.pc
    r.inst   := fetch.io.inst
    r.nextPc := fetch.io.pc
    r.halt   := cause

    // [CA26: Exercise 1.10] The retire record of an instruction that completes
    // Contract: what the record of an instruction that does not halt says besides the lines above:
    // where execution continues, the register it changed and the memory access it made, with the
    // meaning that `TrapRetire` (common/Sim.scala) and the bootcamp's `riscv.RetireInfo` give the
    // fields. The golden model compares every field of every record; the stages report most of
    // them already (handout §T1.7).
    ?
    r
  }
}
