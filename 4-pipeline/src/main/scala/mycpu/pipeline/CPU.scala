// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3._
import chisel3.util._
import mycpu.common.{MemMap, TrapRetire}
import mycpu.csrtrap.{
  CsrFile,
  CsrOp,
  CycleStatus,
  ExceptionCauses,
  HpmClassifier,
  HpmEvents,
  InstructionDecode,
  InterruptLines,
  SystemControl
}
import mycpu.rv32im.{Decode, Execute, MulDivControl, ResultSource}
import mycpu.singlecycle.{DecodedInst, MemoryAccess, RdSource, WriteBack}
import riscv.{ASel, BSel, HaltCause}

/** What ID hands to EX: the decoded instruction, the operands read in ID, whether the fetch
  * failed, and for the `EarlyBranch` variant the outcome of a branch or jump resolved in ID.
  */
class IdEx extends Bundle {
  val pc          = UInt(32.W)
  val inst        = UInt(32.W)
  val d           = new DecodedInst
  val m           = new MulDivControl
  val sys         = new SystemControl
  val serializing = Bool()
  val fault       = Bool()
  val rs1_data    = UInt(32.W)
  val rs2_data    = UInt(32.W)
  // resolved in ID (EarlyBranch only)
  val taken      = Bool()
  val target     = UInt(32.W)
  val misaligned = Bool()
}

/** What EX hands to MEM: the instruction, what EX computed for `rd` (`result`), the address and
  * data of an access, the CSR operand, a branch's outcome, and the causes of an
  * exception found so far.
  */
class ExMem extends Bundle {
  val pc          = UInt(32.W)
  val inst        = UInt(32.W)
  val d           = new DecodedInst
  val sys         = new SystemControl
  val serializing = Bool()
  val fault       = Bool()
  val result      = UInt(32.W)
  val addr        = UInt(32.W)
  val store_data  = UInt(32.W)
  val rs1_data    = UInt(32.W)
  val taken       = Bool()
  val target      = UInt(32.W) // of a jump or branch, also a misaligned one
  val misaligned  = Bool()     // the target of a jump or taken branch is misaligned
}

/** The five-stage pipelined RV32IM_Zicsr_Zicntr_Zihpm_Zifencei machine-mode core (T4): fetch (IF),
  * decode and register read (ID), execute (EX), memory and commit (MEM), and register write-back
  * (WB), with the hazard handling of `config.variant` ([[Variant]]), built from one source.
  *
  * The units are those of the earlier tasks, with the student's own answers in them (D14): T3's
  * decode table and CSR file and the cycle classifier, T2's execute stage with its multiplier and
  * iterative divider (and, in ID, T1's execute stage for `EarlyBranch`), T1's memory access and
  * write-back, the bootcamp's register file, `Forward` and `PipelineFlush`. This task adds the
  * stage registers ([[Stage]]), a fetch unit for a memory of any latency ([[Fetch]]), and the
  * hazard unit ([[HazardUnit]]).
  *
  * '''Ports''' are T3's: `imem` and `dmem` (`riscv.MemPort`, as `Decoupled` requests and `Valid`
  * responses that may come any number of cycles later, T4-06), `irq`, `retire` and `halted`
  * (always low). The data port has at most one request in flight, from MEM, and keeps its address
  * on the port until the response comes; `req.valid` of either port never depends on that port's
  * response in the same cycle.
  *
  * '''Commit''' is in MEM. An instruction commits in the cycle it leaves MEM without a trap: its
  * retire record is out in that cycle, `minstret` counts it, a CSR instruction reads and writes
  * its CSR then, and an `mret` returns then. WB only writes the register file (and forwards); an
  * instruction there has committed. The record of a store to SimCtrl `EXIT` is therefore in the
  * cycle of the store itself.
  *
  * '''Precise traps''' (T4-04). Every stage notes what can make its instruction trap (a failed
  * fetch, an illegal word, `ecall`, `ebreak`, a misaligned target), and MEM adds its own (a CSR
  * access that is not allowed, a misaligned or faulting access) and takes the exception, by T3's
  * priority, when the instruction would leave MEM. MEM holds the oldest instruction, so traps are
  * taken in program order; the trap flushes every younger instruction (EX, ID, and what the fetch
  * unit has fetched or requested) and abandons a division in EX. An interrupt is taken on the
  * oldest instruction that has not committed: the one in MEM, or when MEM holds a bubble the one
  * in EX, else the one in ID, else the next one the fetch unit will hand over; `mepc` is its pc.
  * It is not taken on an instruction in MEM that has passed the point where it decided that no
  * interrupt was due (an access already presented to the data port, which must finish, or a WFI
  * that waits): it is taken after it. The `mip` of each record is that of its decision.
  *
  * '''Side effects only from the oldest instruction.''' The data port is used from MEM only, by
  * the oldest instruction, when nothing older is left: a store or a load of a device register
  * with a read side effect cannot happen for an instruction that an older one's trap cancels.
  * Younger instructions write no register before they commit (WB comes after MEM) and no CSR.
  *
  * '''Serialization.''' Every CSR instruction (counters included), `mret`, `wfi` and `fence.i`
  * waits in ID until EX and MEM are empty, so every older instruction has committed, and no
  * younger instruction leaves ID while one of them is in EX or MEM, so it commits before any
  * younger one issues. A counter read therefore counts exactly the older instructions, and a CSR
  * write cannot outlive an older trap.
  *
  * '''FENCE.I''' (T4-07): when it leaves ID, every older store has completed (they have
  * committed, which needs their response), and the fetch unit drops every instruction it holds or
  * has requested and fetches again from the pc after the `fence.i`.
  *
  * '''Counters''' (T3-04, T4-05). `minstret` counts the instructions that commit. Every cycle in
  * which none does is charged to one event of the partition: a D-side wait (MEM waits for its
  * response), or the event that the bubble in MEM was made for ([[Slot]]): divider busy (EX held
  * by the divider), load-use stall (ID held by the hazard unit), flush bubble (made by a flush, or
  * the cycle after a redirect in which the fetch unit has nothing yet), I-side wait (the fetch
  * unit waits for the instruction memory), or other (the pipeline filling after reset, a
  * serialization, a WFI waiting, the cycle of a trap).
  *
  * @param resetPc
  *   the first pc fetched
  * @param config
  *   the variant, the divider's latency, the fetch unit's depth and the mutant ([[PipelineConfig]])
  * @param csrFile
  *   makes T3's CSR file: the student's own, unless a negative test passes one with a bug
  * @param flushUnit
  *   makes the bootcamp's `PipelineFlush`: the student's own, unless a negative test passes one
  *   with a bug
  */
class CPU(
    resetPc: Long = MemMap.ramBase,
    config: PipelineConfig = PipelineConfig(),
    csrFile: () => CsrFile = () => new CsrFile(),
    flushUnit: () => riscv.PipelineFlush = () => new riscv.PipelineFlush
) extends Module {
  val io = IO(new Bundle {
    val imem   = new riscv.MemPort
    val dmem   = new riscv.MemPort
    val irq    = new InterruptLines
    val retire = Output(Valid(new TrapRetire))
    val halted = Output(Bool())
  })

  val variant = config.variant
  val early   = variant == Variant.EarlyBranch // branches resolve in ID
  val active  = !reset.asBool

  val fetch      = Module(new Fetch(resetPc, config.fetchDepth))
  val decode     = Module(new InstructionDecode)
  val regs       = Module(new riscv.RegFile)
  val hazard     = Module(new HazardUnit(variant))
  val flush      = Module(flushUnit())
  val execute    = Module(new Execute(config.divLatency, config.executeMutant))
  val memory     = Module(new MemoryAccess)
  val write_back = Module(new WriteBack)
  val csr        = Module(csrFile())
  val classifier = Module(new HpmClassifier)

  fetch.io.imem <> io.imem
  fetch.io.active := active
  csr.io.msip     := io.irq.msip
  csr.io.mtip     := io.irq.mtip
  csr.io.meip     := io.irq.meip
  csr.io.time     := io.irq.time

  // Signals of the later stages that the earlier ones need, defined further down.
  val trap           = Wire(Bool())                  // a trap is taken in this cycle (MEM's exception, or an interrupt)
  val take_interrupt = Wire(Bool())                  // the trap is an interrupt, on the oldest uncommitted instruction
  val mret_commit    = Wire(Bool())                  // an mret commits in MEM
  val mem_free       = Wire(Bool())                  // MEM can take an instruction at the end of this cycle
  val mem_fwd        = Wire(Valid(new riscv.Result)) // what EX/MEM can forward: the result of the instruction in MEM
  val wb_fwd         = Wire(Valid(new riscv.Result)) // what MEM/WB forwards: the register WB writes

  /** The operand of register `rs` with the value `value` read for it: the newest result for `rs`
    * of an older instruction in MEM (if `from_mem`) or WB (bootcamp 5.7, `Forward`).
    */
  def forwarded(rs: UInt, value: UInt, from_mem: Valid[riscv.Result]): UInt = {
    val f = Module(new riscv.Forward)
    f.io.rs       := rs
    f.io.regValue := value
    f.io.exMem    := from_mem
    f.io.memWb    := wb_fwd
    f.io.value
  }
  val no_result = Wire(Valid(new riscv.Result))
  no_result := 0.U.asTypeOf(no_result)

  // ======== ID: the head of the fetch unit's queue
  val head = fetch.io.deq
  val d    = decode.io.decoded
  val sys  = decode.io.system
  decode.io.inst := head.bits.inst
  // fence.i: the pattern of T2's Zifencei entry (not a copy of it)
  val fencei      = Decode.zifencei.head.bitPat === head.bits.inst
  val serializing = Wire(Bool()) // the instruction waits for every older one, and every younger one for it

  // which registers the instruction reads; a field it does not use may hold anything
  val alu_kind = d.rd_write && decode.io.m.rd_source === ResultSource.Alu && !sys.csr
  val m_kind   =
    d.rd_write && (decode.io.m.rd_source === ResultSource.Mul || decode.io.m.rd_source === ResultSource.Div)
  val uses_rs1 = ((alu_kind || d.load || d.store || d.jalr) && d.op_a_sel === ASel.Rs1) || d.branch || m_kind ||
    (sys.csr && !d.funct3(2))
  val uses_rs2 = (alu_kind && d.op_b_sel === BSel.Rs2) || d.branch || d.store || m_kind

  regs.io.rs1     := d.rs1
  regs.io.rs2     := d.rs2
  regs.io.dbgAddr := 0.U
  // the register file is written in WB and read in ID in the same cycle (write-through)
  // [CA26: Exercise 4.2] Stalls in ID, and forwarding into ID
  // Contract: `id_from_mem` is what ID's operands may take from EX/MEM in this variant, as the
  // variants table of the handout says where each variant needs a branch's operands, and what
  // `HazardUnit` waits for; `no_result` is a source that never matches. MEM/WB always reaches ID,
  // through `forwarded` (handout §T4.3).
  val id_from_mem = ?
  val id_rs1      = forwarded(d.rs1, regs.io.rs1Data, id_from_mem)
  val id_rs2      = forwarded(d.rs2, regs.io.rs2Data, id_from_mem)

  // EarlyBranch: the branch decision and the target in ID, with T1's execute stage
  // (the other variants resolve them in EX: no branch decision in ID)
  val id_branch     = Wire(Valid(UInt(32.W))) // the branch or jump in ID is taken, to `bits`
  val id_misaligned = Wire(Bool())            // its target is misaligned
  if (early) {
    val unit = Module(new mycpu.singlecycle.Execute())
    unit.io.pc       := head.bits.pc
    unit.io.decoded  := d
    unit.io.rs1_data := id_rs1
    unit.io.rs2_data := id_rs2
    id_branch        := unit.io.redirect
    id_misaligned    := unit.io.target_misaligned
    assert(
      !unit.io.redirect.valid || unit.io.redirect.bits(31, 2) === unit.io.alu_result(31, 2),
      "ID's target is its ALU's"
    )
  } else {
    id_branch     := 0.U.asTypeOf(id_branch)
    id_misaligned := false.B
  }

  // ======== the stage registers' current contents
  val id_ex_next  = Wire(new Slot(new IdEx))
  val ex_mem_next = Wire(new Slot(new ExMem))
  val ex_next     = Wire(new Slot(new IdEx)) // what EX holds next: ID's slot, or its own instruction
  val id_issue    = Wire(Bool())             // the instruction in ID leaves for EX at the end of this cycle
  val id_bubble   = Wire(UInt(Stage.eventBits.W))

  val flush_id_ex  = Wire(Bool())
  val flush_ex_mem = Wire(Bool())
  val ex           = Stage(ex_next, true.B, flush_id_ex)
  val mem          = Stage(ex_mem_next, mem_free, flush_ex_mem)
  val e            = ex.bits
  val mm           = mem.bits

  // ======== EX
  val ex_rs1 = if (variant == Variant.Stall) e.rs1_data else forwarded(e.d.rs1, e.rs1_data, mem_fwd)
  val ex_rs2 = if (variant == Variant.Stall) e.rs2_data else forwarded(e.d.rs2, e.rs2_data, mem_fwd)

  // The divider answers once, for one cycle; EX keeps the answer (`div_value`) while it cannot
  // leave yet (an older access waits in MEM), so the division is not started a second time.
  val div_held  = RegInit(false.B)
  val div_value = Reg(UInt(32.W))

  // [CA26: Exercise 4.7] The divider in the pipeline
  // Contract: drive the execute stage's `valid` and `kill` (T2's `Execute`, whose header and the
  // divider's give the contract of both). A division is carried out once for each time its
  // instruction is in EX and is not canceled: an answer it got is kept above, and an instruction
  // that a trap removes from EX (its own interrupt, or an older instruction's exception) leaves
  // no division behind; it is fetched again and divides from the beginning. Mind which signals
  // `kill` may depend on (handout §T4.4).
  ?
  execute.io.pc       := e.pc
  execute.io.decoded  := e.d
  execute.io.m        := e.m
  execute.io.rs1_data := ex_rs1
  execute.io.rs2_data := ex_rs2

  // EarlyBranch: EX decides every branch again, with the operands it has, and must agree with ID
  if (early)
    assert(
      !ex.valid || (execute.io.redirect.valid === e.taken && execute.io.target_misaligned === e.misaligned &&
        (!e.taken || execute.io.redirect.bits === e.target)),
      "EX and ID decide a branch differently"
    )
  val ex_done       = execute.io.done || div_held
  val ex_taken      = if (early) e.taken else execute.io.redirect.valid
  val ex_target     = if (early) e.target else execute.io.redirect.bits
  val ex_misaligned = if (early) e.misaligned else execute.io.target_misaligned
  // EX leaves for MEM at the end of this cycle; a redirect from EX takes effect only then
  val ex_moves    = ex.valid && ex_done && mem_free && !(trap || mret_commit)
  val ex_redirect = if (early) false.B else ex_moves && ex_taken
  val ex_free     = !ex.valid || (ex_done && mem_free)
  // while EX holds its instruction, its operands are kept as forwarded: the instructions they
  // came from move on
  ex_next := id_ex_next
  when(!ex_free) {
    ex_next               := ex
    ex_next.bits.rs1_data := ex_rs1
    ex_next.bits.rs2_data := ex_rs2
  }
  when(trap || ex_moves) {
    div_held := false.B
  }.elsewhen(ex.valid && e.m.div && execute.io.done && !div_held) {
    div_held  := true.B
    div_value := execute.io.result
  }

  val exm = Wire(new ExMem)
  exm.pc          := e.pc
  exm.inst        := e.inst
  exm.d           := e.d
  exm.sys         := e.sys
  exm.serializing := e.serializing
  exm.fault       := e.fault
  exm.result      := Mux(e.d.rd_source === RdSource.Link, e.pc + 4.U, Mux(div_held, div_value, execute.io.result))
  exm.addr        := execute.io.alu_result
  exm.store_data  := ex_rs2
  exm.rs1_data    := ex_rs1
  exm.taken       := ex_taken
  exm.target      := ex_target
  exm.misaligned  := ex_misaligned
  // a bubble into MEM: EX's own, or one made while the divider holds EX
  ex_mem_next := Slot(ex.valid && ex_done, exm, Mux(ex.valid, HpmEvents.DividerBusy.U, ex.bubble))

  // ======== MEM: the access, the commit, traps
  val mem_waiting = RegInit(false.B)   // MEM's instruction has decided against an interrupt and waits
  val mem_mip     = RegInit(0.U(32.W)) // mip at that decision
  val req_sent    = RegInit(false.B)   // MEM's request was accepted, and its response has not come

  memory.io.enable     := mem.valid && !take_interrupt && !req_sent
  memory.io.load       := mm.d.load
  memory.io.store      := mm.d.store
  memory.io.funct3     := mm.d.funct3
  memory.io.addr       := mm.addr
  memory.io.store_data := mm.store_data
  memory.io.dmem <> io.dmem
  when(io.dmem.resp.valid)(req_sent := false.B).elsewhen(io.dmem.req.fire)(req_sent := true.B)

  val cmd = csr.io.csr.cmd
  cmd.valid   := mem.valid && mm.sys.csr && !take_interrupt
  cmd.bits.op := suppressEnumCastWarning(
    mm.d.funct3(1, 0).asTypeOf(CsrOp())
  ) // means something only for a CSR instruction
  cmd.bits.imm      := mm.d.funct3(2)
  cmd.bits.addr     := mm.inst(31, 20)
  cmd.bits.rs1      := mm.d.rs1
  cmd.bits.rs1_data := mm.rs1_data

  val mem_pc4 = mm.pc + 4.U
  // where MEM's instruction goes on, if it does not trap
  val mem_next_pc = Mux(mm.taken, mm.target, mem_pc4)

  // the causes of an exception, collected by the stages, ranked by T3's rule
  val causes = Wire(new ExceptionCauses)
  causes.pc                := mm.pc
  causes.inst              := mm.inst
  causes.fetch_fault       := mm.fault
  causes.illegal           := !mm.d.legal || csr.io.csr.illegal
  causes.ecall             := mm.d.ecall
  causes.ebreak            := mm.d.ebreak
  causes.target_misaligned := mm.misaligned
  causes.target            := mm.target
  causes.misaligned        := memory.io.misaligned
  causes.fault             := memory.io.fault
  causes.store             := mm.d.store
  causes.addr              := mm.addr
  val exception = mycpu.csrtrap.CPU.exception(causes)

  val wake      = csr.io.irq.wake
  val completes = Wire(Bool()) // MEM's instruction is over in this cycle
  val commit    = Wire(Bool()) // it commits

  // the oldest instruction that has not committed, for an interrupt
  val oldest_pc =
    Mux(mem.valid, mm.pc, Mux(ex.valid, e.pc, Mux(head.valid, head.bits.pc, fetch.io.expected_pc)))

  // [CA26: Exercise 4.6] Where an interrupt is taken
  // Contract: `take_interrupt` is high in the cycles in which the core takes the interrupt that
  // the CSR file requests (`csr.io.irq.valid`), on the oldest instruction that has not committed
  // (`oldest_pc`), as the "Precise traps" paragraph of this class describes it; `mem_waiting` and
  // `mem_mip` say whether MEM's instruction has already decided that no interrupt was due and is
  // still there in the next cycle (an access presented to the data port and not answered, or a WFI
  // that waits), and with which `mip`. A WFI that is reached with an interrupt due is preceded by
  // it; one that waits retires when it wakes, and the interrupt is taken after it (the WFI rules
  // of 3-csr-trap/README.md, "Interrupts and WFI"; Privileged spec §3.3.3; handout §T4.4).
  ?

  // [CA26: Exercise 4.5] The commit stage: trap or commit
  // Contract: drive `completes`, `trap`, `commit` and `mem_free` (see their comments where they
  // are declared) for the instruction in MEM, whose exception, if it raises one, is `exception`.
  // MEM's instruction is over by T3's rule, WFI included (`wake` is T3's). An interrupt wins over
  // everything MEM's instruction would do; an exception is taken when the instruction would leave
  // MEM, never earlier; an instruction that traps neither commits nor goes on to WB. MEM can take
  // the next instruction when it is empty or its instruction leaves in this cycle, also by a trap
  // (handout §T4.4).
  ?
  mret_commit := commit && mm.sys.mret

  csr.io.trap.interrupt := take_interrupt
  csr.io.trap.exception := trap && !take_interrupt
  csr.io.trap.cause     := exception.bits.cause
  csr.io.trap.epc       := oldest_pc // MEM's pc whenever MEM's instruction traps
  csr.io.trap.tval      := exception.bits.tval
  csr.io.mret.valid     := mret_commit
  csr.io.retired        := commit

  // T1's write-back: what the instruction writes to rd (a CSR instruction the CSR's old value)
  write_back.io.commit     := commit
  write_back.io.rd_write   := mm.d.rd_write
  write_back.io.rd         := mm.d.rd
  write_back.io.rd_source  := mm.d.rd_source
  write_back.io.alu_result := Mux(mm.sys.csr, csr.io.csr.rdata, mm.result)
  write_back.io.load_value := memory.io.load_value
  write_back.io.pc_plus4   := mem_pc4

  // what EX/MEM forwards: a result that EX computed (not a load's or a CSR's, which come in MEM)
  mem_fwd.valid      := mem.valid && mm.d.rd_write && !mm.d.load && !mm.sys.csr && !config.has("no-forward-mem").B
  mem_fwd.bits.rd    := mm.d.rd
  mem_fwd.bits.value := mm.result

  // ======== WB
  val wb = RegNext(
    {
      val r = Wire(Valid(new riscv.Result))
      r.valid      := write_back.io.reg_wen
      r.bits.rd    := mm.d.rd
      r.bits.value := write_back.io.reg_wdata
      r
    },
    0.U.asTypeOf(Valid(new riscv.Result))
  )
  wb_fwd        := wb
  regs.io.wen   := wb.valid
  regs.io.rd    := wb.bits.rd
  regs.io.wdata := wb.bits.value

  // ======== hazards, serialization, issue (ID)
  def producer(valid: Bool, d: DecodedInst, s: SystemControl, waits: Bool): HazardProducer = {
    val p = Wire(new HazardProducer)
    p.valid  := valid
    p.writes := d.rd_write
    p.rd     := d.rd
    p.late   := d.load || s.csr
    p.waits  := waits
    p
  }
  hazard.io.id.valid    := head.valid
  hazard.io.id.rs1      := d.rs1
  hazard.io.id.rs2      := d.rs2
  hazard.io.id.uses_rs1 := uses_rs1
  hazard.io.id.uses_rs2 := uses_rs2
  hazard.io.id.early    := early.B && (d.branch || d.jalr)
  hazard.io.ex          := producer(ex.valid, e.d, e.sys, false.B)
  hazard.io.mem         := producer(mem.valid, mm.d, mm.sys, !completes)

  val serial_wait   = Wire(Bool()) // ID's instruction waits for older ones (exercise 4.4)
  val behind_serial = Wire(Bool()) // ID's instruction waits for an older one that serializes
  // [CA26: Exercise 4.4] Serializing CSR instructions, mret, wfi and fence.i
  // Contract: `serializing` says that the instruction in ID is one of those that the
  // "Serialization" paragraph of this class lists (`sys` and `fencei` decode them), and
  // `serial_wait` and `behind_serial` hold the instruction in ID as that paragraph describes it:
  // one that serializes leaves ID only once every older instruction has committed, and no
  // instruction leaves ID while one that serializes has not committed. The stage registers'
  // `serializing` fields say what EX and MEM hold; an instruction in WB has committed
  // (Privileged spec §2.1 for the order of CSR accesses; handout §T4.5).
  ?
  id_issue := head.valid && ex_free && !hazard.io.stall && !serial_wait && !behind_serial && !flush_id_ex
  // a cycle that ID would lose to serialization anyway is not one lost to a data hazard
  id_bubble := Mux(
    !head.valid,
    fetch.io.gap,
    Mux(hazard.io.stall && !serial_wait && !behind_serial, HpmEvents.LoadUseStall.U, HpmEvents.Other.U)
  )
  head.ready := id_issue

  val ide = Wire(new IdEx)
  ide.pc          := head.bits.pc
  ide.inst        := head.bits.inst
  ide.d           := d
  ide.m           := decode.io.m
  ide.sys         := sys
  ide.serializing := serializing
  ide.fault       := head.bits.fault
  ide.rs1_data    := id_rs1
  ide.rs2_data    := id_rs2
  ide.taken       := id_branch.valid
  ide.target      := id_branch.bits
  ide.misaligned  := id_misaligned
  id_ex_next      := Slot(id_issue, ide, id_bubble)

  // ======== redirects and flushes
  val id_taken  = if (early) id_issue && id_branch.valid else false.B
  val id_fencei = Wire(Bool())     // the redirect of a fence.i (exercise 4.3)
  val id_target = Wire(UInt(32.W)) // where a redirect from ID fetches from
  // [CA26: Exercise 4.3] Flushes and redirects, FENCE.I included
  // Contract: `id_fencei` is high in the cycle in which the fence.i in ID may make the fetch unit
  // start again, and `id_target` is where ID's redirect fetches from, for a fence.i and for a
  // branch or jump resolved in ID (`id_branch`). By then every older store must have completed,
  // which the serialization of exercise 4.4 provides, and what the fetch unit holds or has
  // requested may be stale: the instructions after the fence.i are fetched again (Unprivileged
  // spec, "Zifencei"; T4-07; handout §T4.7).
  ?
  val halt        = trap || mret_commit // every younger instruction is canceled
  val id_redirect = Wire(Bool())        // ID redirects the fetch unit: a branch resolved there, or a fence.i
  // [CA26: Exercise 4.3] Flushes and redirects, FENCE.I included
  // Contract: connect the bootcamp's `PipelineFlush` (5.6) and drive `flush_id_ex`, `flush_ex_mem`
  // (EX, MEM get a bubble in place of the instruction before), `id_redirect` and the fetch unit's
  // redirect, which also drops what the fetch unit holds and has requested: a trap or mret in MEM
  // cancels every younger instruction; a taken branch or jump in EX the two younger ones; one
  // resolved in ID, and a fence.i, only what the fetch unit has, which then fetches from
  // `id_target`. An older stage's redirect wins (handout §T4.4).
  ?

  // ======== the counters' view of the cycle (T3-04)
  val status = classifier.io.status
  val bubble = mem.bubble
  status              := 0.U.asTypeOf(new CycleStatus)
  status.retired      := commit
  status.trap_taken   := trap
  status.taken_branch := commit && mm.taken
  status.data_wait    := mem.valid && !trap && memory.io.access && !memory.io.done
  // a cycle with a bubble in MEM and no trap counts the bubble's event
  def counted(event: Int): Bool = !mem.valid && !trap && bubble === event.U
  status.divider_busy     := counted(HpmEvents.DividerBusy)
  status.load_use_stall   := counted(HpmEvents.LoadUseStall)
  status.flush_bubble     := counted(HpmEvents.FlushBubble)
  status.instruction_wait := counted(HpmEvents.InstructionWait)
  csr.io.events           := classifier.io.events

  // ======== the retire record: an instruction that commits, or a trap
  val record = Wire(new TrapRetire)
  record                := 0.U.asTypeOf(new TrapRetire)
  record.pc             := oldest_pc
  record.inst           := Mux(take_interrupt, 0.U, mm.inst)
  record.nextPc         := Mux(trap, csr.io.trap.handler_pc, Mux(mm.sys.mret, csr.io.mret.pc, mem_next_pc))
  record.rd             := write_back.io.retire_rd
  record.rdValue        := write_back.io.retire_value
  record.mem.valid      := commit && memory.io.access
  record.mem.bits.addr  := mm.addr
  record.mem.bits.write := mm.d.store
  record.mem.bits.bytes := memory.io.access_bytes
  record.mem.bits.data  := memory.io.access_data
  record.halt           := HaltCause.Running
  record.trap           := trap
  record.cause          := Mux(take_interrupt, csr.io.irq.mcause, exception.bits.cause)
  record.tval           := Mux(take_interrupt, 0.U, exception.bits.tval)
  record.csr.valid      := csr.io.csr.write
  record.csr.bits.addr  := cmd.bits.addr
  record.csr.bits.wdata := csr.io.csr.wdata
  record.mip            := Mux(mem_waiting, mem_mip, csr.io.mip)

  io.retire.valid := commit || trap
  io.retire.bits  := record
  io.halted       := false.B

  // the data port has one request in flight at most, and only MEM's
  val asked = Mux(req_sent, true.B, io.dmem.req.fire) // accepted before, or in this cycle
  when(io.dmem.resp.valid)(assert(asked, "a data response without a request"))
}
