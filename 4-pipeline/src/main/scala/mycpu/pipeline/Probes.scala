// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3._
import mycpu.common.{ObsCsr, ObsPipe, ObsStage}
import mycpu.csrtrap.CsrProbes
import mycpu.singlecycle.Probes.tap

/** The probe groups `pipe` and `csr` of the observation interface (OBS-09) for the pipelined
  * [[CPU]], read with `tap` from the body of the top that calls these (the T4 top and the SoC of
  * T5). `docs/observation.md` ("`pipe` (T4, T5)") says what each field is in this core: the
  * stage registers and the fetch unit's queue are read as they are, and a stage's `stall`,
  * `flush` and `bubble` are the inputs of its stage register (not enabled for its next input,
  * the flush input, a bubble as the next input). They are not combined: when two of them are 1,
  * what the register did shows in the next cycle's record, as the student's `Stage` decided it
  * (exercise 4.1), so no probe restates that rule or another decision of the core.
  */
object PipeProbes {

  private def stage(valid: Bool, pc: UInt, inst: UInt, stall: Bool, flush: Bool, bubble: Bool): ObsStage = {
    val s = Wire(new ObsStage)
    s.valid  := valid
    s.pc     := pc
    s.inst   := inst
    s.stall  := stall
    s.flush  := flush
    s.bubble := bubble
    s
  }

  /** The `pipe` group of `cpu`. */
  def pipe(cpu: CPU): ObsPipe = {
    val o        = Wire(new ObsPipe)
    val enq      = tap(cpu.fetch.queue.io.enq)
    val redirect = tap(cpu.fetch.io.redirect.valid)
    val head     = tap(cpu.fetch.io.deq)
    val ex       = tap(cpu.ex)
    val exNext   = tap(cpu.ex_next.valid)
    val exFree   = tap(cpu.ex_free)
    val mem      = tap(cpu.mem)
    val memNext  = tap(cpu.ex_mem_next.valid)
    val memFree  = tap(cpu.mem_free)
    val flushEx  = tap(cpu.flush_id_ex)
    val flushMem = tap(cpu.flush_ex_mem)
    val wb       = tap(cpu.wb)
    // IF: the word that the fetch unit keeps in this cycle (it enters the queue at the next edge)
    o.fetch := stage(enq.valid, enq.bits.pc, enq.bits.inst, false.B, redirect, !enq.valid)
    // ID: the head of the queue, which ID decodes; the queue keeps no bubble
    o.decode := stage(head.valid, head.bits.pc, head.bits.inst, head.valid && !head.ready, redirect, false.B)
    // EX's register always takes its input, which is its own instruction while EX cannot hand it on
    o.execute := stage(ex.valid, ex.bits.pc, ex.bits.inst, ex.valid && !exFree, flushEx, !exNext)
    o.memory  := stage(mem.valid, mem.bits.pc, mem.bits.inst, mem.valid && !memFree, flushMem, !memNext)
    // WB holds the register write of the instruction that committed in the cycle before; no pc
    o.writeback := stage(wb.valid, 0.U, 0.U, false.B, false.B, !tap(cpu.write_back.io.reg_wen))
    // the operand selects are inside the bootcamp's Forward (an exercise): not observed, 0
    o.forwardA     := 0.U
    o.forwardB     := 0.U
    o.loadUseStall := tap(cpu.hazard.io.stall)
    o
  }

  /** The `csr` group of `cpu` ([[CsrProbes]]): a WFI waits while MEM holds it and it has decided
    * against an interrupt.
    */
  def csr(cpu: CPU): ObsCsr =
    CsrProbes.csr(
      cpu.csr,
      tap(cpu.trap),
      cpu.io.retire.bits.cause,
      tap(cpu.mem_waiting) && tap(cpu.mem.valid) && tap(cpu.mm.sys.wfi)
    )
}
