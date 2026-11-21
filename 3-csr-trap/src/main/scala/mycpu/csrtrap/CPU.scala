// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import chisel3.util._
import mycpu.common.{MemMap, Trap, TrapRetire}
import mycpu.rv32im.{Divider, Execute}
import mycpu.singlecycle.{InstructionFetch, MemoryAccess, WriteBack}
import riscv.HaltCause

/** The interrupt lines and the time of the core (T3-03, T3-05): `msip` and `mtip` from the CLINT,
  * `meip` from SimCtrl's `MEIP` (and in T5 the UART), all levels; `time` is the CLINT's `mtime`.
  */
class InterruptLines extends Bundle {
  val msip = Input(Bool())
  val mtip = Input(Bool())
  val meip = Input(Bool())
  val time = Input(UInt(64.W))
}

/** An exception that the instruction at the pc raises: its code (`mcause`) and what `mtval` gets
  * (T3-02).
  */
class RaisedException extends Bundle {
  val cause = UInt(5.W)
  val tval  = UInt(32.W)
}

object RaisedException {

  /** The exception with code `cause` and `mtval` value `tval`. */
  def apply(cause: UInt, tval: UInt): RaisedException = {
    val e = Wire(new RaisedException)
    e.cause := cause
    e.tval  := tval
    e
  }
}

/** The RV32IM_Zicsr_Zicntr_Zihpm_Zifencei core (T3): T2's single-cycle core with machine-mode traps
  * and interrupts, the CSRs and the counters. An instruction takes one clock cycle, unless it waits
  * for a device or for the divider (as in T2), or it is a WFI that waits for an interrupt.
  *
  * The stages are T1's (fetch, memory access and write-back) and T2's execute stage, with the
  * student's own answers in them, around this task's decode ([[InstructionDecode]]), the CSR file
  * ([[CsrFile]]) and the cycle classifier ([[HpmClassifier]]). Ports:
  *   - `imem`, `dmem`: the instruction and data ports (bootcamp `riscv.MemPort`);
  *   - `irq`: the interrupt lines and the CLINT's time ([[InterruptLines]]);
  *   - `retire`: one record per instruction, trap or interrupt, in the cycle in which it is over
  *     (`mycpu.common.TrapRetire`, whose comment gives the meaning of every field);
  *   - `halted`: always low. Every case in which T1 and T2 halt is a trap here (T3-02).
  *
  * '''Traps''' (T3-02, T3-03). In every cycle the core decides, before the instruction at the pc,
  * whether to take an interrupt: the CSR file requests one (`CsrFile.io.irq`), and the core takes
  * it in that cycle, whatever the instruction is doing: also in a cycle in which the instruction
  * waits for the divider (the division is abandoned with the divider's `kill`, and it starts
  * again from the beginning after the `mret`: `mepc` is its pc) or for a device. `mepc` is the pc
  * of the instruction that has not executed. Otherwise the instruction executes, and if it raises
  * an exception it traps in the cycle in which it is over, with `mepc` its pc. An instruction that
  * traps, or that an interrupt precedes, has no effect beyond the trap: no register, no CSR
  * written by it, no store, no access to a device, no division that it started.
  *
  * '''WFI''' (Privileged spec §3.3.3): if an enabled interrupt is pending when the WFI is reached
  * (`mip & mie` is not 0), it does not wait: it is taken before the WFI if `mstatus.MIE` is set,
  * and the WFI retires at once otherwise. If none is, the WFI waits, with nothing retired, until
  * one is; it then retires in that cycle, and an interrupt that is due is taken in the next cycle,
  * with `mepc` the pc after the WFI. The WFI's record shows the `mip` from before the wait.
  *
  * '''Counters''' (T3-04). `minstret` counts the cycles with an instruction that retires (a trap
  * is not one); the classifier puts every cycle without one into exactly one event of the
  * partition (`HpmEvents.partition`): a cycle in which the divider holds the core is "divider
  * busy", any other ("other") is one in which the core waits for a device, a WFI waits, or a trap
  * is taken. The events of T4 and T5 (load-use stall, flush bubble, I-side and D-side wait) do
  * not happen in this core and count 0.
  *
  * @param divLatency
  *   the divider's latency, one of `Divider.latencies`
  * @param mutant
  *   a bug to inject for negative tests, one of `mycpu.rv32im.Execute.mutants`; `None` in every
  *   real build. A CSR file with a bug comes through `csrFile`
  * @param csrFile
  *   makes the CSR file: the reference, unless a negative test passes one with a bug
  */
class CPU(
    resetPc: Long = MemMap.ramBase,
    divLatency: Int = Divider.DIV_LATENCY,
    mutant: Option[String] = None,
    csrFile: () => CsrFile = () => new CsrFile()
) extends Module {
  val io = IO(new Bundle {
    val imem   = new riscv.MemPort
    val dmem   = new riscv.MemPort
    val irq    = new InterruptLines
    val retire = Output(Valid(new TrapRetire))
    val halted = Output(Bool())
  })

  // as in T1: nothing is requested, started or retired while reset is held
  val active = !reset.asBool

  val fetch      = Module(new InstructionFetch(resetPc))
  val decode     = Module(new InstructionDecode)
  val regs       = Module(new riscv.RegFile)
  val execute    = Module(new Execute(divLatency, mutant))
  val memory     = Module(new MemoryAccess)
  val write_back = Module(new WriteBack)
  val csr        = Module(csrFile())
  val classifier = Module(new HpmClassifier)
  val d          = decode.io.decoded
  val sys        = decode.io.system

  fetch.io.imem <> io.imem
  fetch.io.active := active
  decode.io.inst  := fetch.io.inst

  regs.io.rs1     := d.rs1
  regs.io.rs2     := d.rs2
  regs.io.dbgAddr := 0.U

  // the instruction at the pc has been fetched, and the core is running
  val fetched = active && fetch.io.inst_valid

  execute.io.pc       := fetch.io.pc
  execute.io.decoded  := d
  execute.io.m        := decode.io.m
  execute.io.rs1_data := regs.io.rs1Data
  execute.io.rs2_data := regs.io.rs2Data

  memory.io.load       := d.load
  memory.io.store      := d.store
  memory.io.funct3     := d.funct3
  memory.io.addr       := execute.io.alu_result
  memory.io.store_data := regs.io.rs2Data
  memory.io.dmem <> io.dmem

  csr.io.msip := io.irq.msip
  csr.io.mtip := io.irq.mtip
  csr.io.meip := io.irq.meip
  csr.io.time := io.irq.time

  val take_interrupt = Wire(Bool())       // an interrupt is taken in this cycle, before the instruction at the pc
  val wfi_waiting    = RegInit(false.B)   // the WFI at the pc has waited for at least one cycle
  val wfi_mip        = RegInit(0.U(32.W)) // mip in the cycle in which the WFI at the pc was reached

  // [CA26: Exercise 3.9] Taking an interrupt at an instruction boundary
  // Contract: `take_interrupt` is high in the cycles in which the core takes the interrupt that
  // the CSR file requests, before the instruction at the pc, as the "Traps" and "WFI" paragraphs
  // of this class describe it; in such a cycle the instruction does not execute: nothing may be
  // started for it in the execute stage, and a division in progress for it is abandoned. The
  // divider's handshake and its `kill` are defined in `Divider` (T2), whose paragraph on
  // combinational paths says what `kill` may depend on (handout §T3.3).
  ?

  // ---- CSR instructions: presented in the cycle in which they execute (they never wait)
  val cmd = csr.io.csr.cmd
  cmd.valid   := fetched && sys.csr && !take_interrupt
  cmd.bits.op := suppressEnumCastWarning(d.funct3(1, 0).asTypeOf(CsrOp())) // means something only for a CSR instruction
  cmd.bits.imm      := d.funct3(2)
  cmd.bits.addr     := fetch.io.inst(31, 20)
  cmd.bits.rs1      := d.rs1
  cmd.bits.rs1_data := regs.io.rs1Data

  // ---- exceptions (T3-02)
  val illegal = !d.legal || csr.io.csr.illegal // a word that is no instruction, or a CSR access that is not allowed
  val causes  = Wire(new ExceptionCauses)
  causes.pc                := fetch.io.pc
  causes.inst              := fetch.io.inst
  causes.fetch_fault       := fetch.io.fetch_fault
  causes.illegal           := illegal
  causes.ecall             := d.ecall
  causes.ebreak            := d.ebreak
  causes.target_misaligned := execute.io.target_misaligned
  causes.target            := execute.io.redirect.bits
  causes.misaligned        := memory.io.misaligned
  causes.fault             := memory.io.fault
  causes.store             := d.store
  causes.addr              := execute.io.alu_result
  val exception = CPU.exception(causes)

  // ---- the end of the instruction
  val completes = Wire(Bool()) // the instruction at the pc is over in this cycle
  val trap      = Wire(Bool()) // a trap is taken in this cycle: an interrupt, or an exception of the instruction
  val commit    = Wire(Bool()) // the instruction retires: it is over and changes the state

  // [CA26: Exercise 3.10] WFI: waiting for an interrupt
  // Contract: `completes` is high in the one cycle in which the instruction at the pc is over
  // (T2's rule: nothing that can make it wait has anything left to wait for), and a WFI is over
  // only when it need not wait any longer. `wfi_waiting` and `wfi_mip` follow the "WFI" paragraph
  // of this class: whether the WFI at the pc has already waited, and the `mip` its record shows.
  // `csr.io.irq.wake` says that an enabled interrupt is pending (`InterruptRequest`; Privileged
  // spec §3.3.3, "Wait for Interrupt"; handout §T3.3).
  ?

  // [CA26: Exercise 3.7] Traps and commit: what an instruction that traps leaves undone
  // Contract: `trap` and `commit` as their comments above say, and `memory.io.enable`, the data
  // port's permission to act for the instruction. An interrupt wins over everything the
  // instruction would do, an exception of the instruction over its own effects; the class comment
  // lists the effects that must not happen. Every effect below is gated by `commit` or `trap`
  // alone, and `MemoryAccess` explains why a request on the data port is an effect in itself
  // (`CsrFile` for the contract of its ports; handout §T3.2).
  ?

  csr.io.trap.interrupt := take_interrupt
  csr.io.trap.exception := trap && !take_interrupt
  csr.io.trap.cause     := exception.bits.cause
  csr.io.trap.epc       := fetch.io.pc
  csr.io.trap.tval      := exception.bits.tval
  csr.io.mret.valid     := commit && sys.mret
  csr.io.retired        := commit

  // where execution continues: the handler, mepc after an mret, or execute's target
  fetch.io.commit         := commit || trap
  fetch.io.redirect.valid := trap || (commit && (sys.mret || execute.io.redirect.valid))
  fetch.io.redirect.bits  := MuxCase(
    execute.io.redirect.bits,
    Seq(trap -> csr.io.trap.handler_pc, sys.mret -> csr.io.mret.pc)
  )

  write_back.io.commit    := commit
  write_back.io.rd_write  := d.rd_write
  write_back.io.rd        := d.rd
  write_back.io.rd_source := d.rd_source
  // a CSR instruction writes the CSR's old value to rd, through the path of an ALU result
  write_back.io.alu_result := Mux(sys.csr, csr.io.csr.rdata, execute.io.result)
  write_back.io.load_value := memory.io.load_value
  write_back.io.pc_plus4   := fetch.io.pc_plus4
  regs.io.wen              := write_back.io.reg_wen
  regs.io.rd               := d.rd
  regs.io.wdata            := write_back.io.reg_wdata

  // ---- the counters' view of the cycle (T3-04)
  val status = classifier.io.status
  status              := 0.U.asTypeOf(new CycleStatus)
  status.retired      := commit
  status.divider_busy := fetched && decode.io.m.div && !execute.io.done && !take_interrupt
  status.taken_branch := execute.io.redirect.valid
  status.trap_taken   := trap
  csr.io.events       := classifier.io.events

  // ---- the retire record: T1's, with what traps and CSRs add
  val record = Wire(new TrapRetire)
  record := mycpu.singlecycle.CPU.retireRecord(fetch, d, execute.io.alu_result, memory, write_back, HaltCause.Running)
  when(trap) {
    record.mem.valid := false.B // nothing was accessed: an access that faults has no effect
    record.trap      := true.B
    record.cause     := exception.bits.cause
    record.tval      := exception.bits.tval
  }
  // an interrupt's own fields, whatever `trap` (exercise 3.7) says
  when(take_interrupt) {
    record.inst  := 0.U // the instruction did not execute
    record.cause := csr.io.irq.mcause
    record.tval  := 0.U
  }
  record.csr.valid      := csr.io.csr.write
  record.csr.bits.addr  := cmd.bits.addr
  record.csr.bits.wdata := csr.io.csr.wdata
  record.mip            := Mux(wfi_waiting, wfi_mip, csr.io.mip)

  io.retire.valid := take_interrupt || completes
  io.retire.bits  := record
  io.halted       := false.B
}

/** What can make an instruction raise an exception, as the stages of a core report it (T3-02):
  *   - `pc`, `inst`: the instruction (`inst` is 0 after a failed fetch);
  *   - `fetch_fault`: the instruction memory answered its fetch with an error;
  *   - `illegal`: the word is no instruction of this hart, or a CSR access that is not allowed;
  *   - `ecall`, `ebreak`: it is one of these;
  *   - `target_misaligned`: it is a jump or a taken branch whose target (`target`) is misaligned;
  *   - `misaligned`, `fault`: its load or store (`store`) at byte address `addr` is misaligned, or
  *     was answered with an error.
  */
class ExceptionCauses extends Bundle {
  val pc                = UInt(32.W)
  val inst              = UInt(32.W)
  val fetch_fault       = Bool()
  val illegal           = Bool()
  val ecall             = Bool()
  val ebreak            = Bool()
  val target_misaligned = Bool()
  val target            = UInt(32.W)
  val misaligned        = Bool()
  val fault             = Bool()
  val store             = Bool()
  val addr              = UInt(32.W)
}

object CPU {

  /** The exception that an instruction with the causes `c` raises, if any. The T3 core and the T4
    * pipeline (which collects the causes of its stages in its commit stage) both use it.
    */
  def exception(c: ExceptionCauses): Valid[RaisedException] = {
    val exception = Wire(Valid(new RaisedException))
    // [CA26: Exercise 3.8] Which exception an instruction raises
    // Contract: `exception.valid` says that the instruction raises an exception, and
    // `exception.bits` gives its code (`mycpu.common.Trap`) and its `mtval` value, as T3-02 lists
    // them per cause. Several causes can hold for one instruction, in one stage or in more: the
    // instruction raises the one that the Privileged spec ranks first (§3.1.15, "Synchronous
    // exception priority"); T3-02 fixes the order of the two memory causes. `c` has what the
    // stages found, with the target of a jump or branch and the address of an access
    // (`ExceptionCauses`; handout §T3.2).
    ?
    exception
  }
}
