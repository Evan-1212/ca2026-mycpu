// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.util._
import mycpu.singlecycle.DecodedInst

/** Execute of T2: T1's execute stage (the ALU and the control-transfer decision, which M does not
  * change), the multiplier and the divider.
  *
  * A multiplication is combinational, like an ALU operation. A division is not: the divider
  * ([[Divider]], T2-03) answers `latency` cycles after it accepts a request, or one cycle after
  * for a division by zero and for the signed overflow. This is the first instruction of the core
  * that takes more than one cycle by itself, and the stage says so with `done`. The core waits on
  * it as it waits on a data access that is not answered yet: the PC, the registers and the data
  * port keep their state, the instruction stays where it is, and it retires in the cycle in
  * which `done` is high.
  *
  *   - `valid`: the core is running and has the instruction in this cycle (out of reset, not
  *     halted, and the word at the PC has been fetched). Without it, `decoded` and `m` describe
  *     no instruction, and nothing may be started for them. The core keeps it high, with the
  *     same instruction and operands, from the cycle it issues a division to the cycle of the
  *     response (the instruction memory of T1 to T3 answers in every cycle). The divider
  *     answers once: a core that is not there to take the response issues the division again.
  *   - `alu_result`, `redirect`, `target_misaligned`: as in T1's `Execute`. `alu_result` stays the
  *     ALU's output, which is also the address of a load or store.
  *   - `result`: the value that an instruction whose source is the execute stage writes to `rd`.
  *   - `done`: the instruction has nothing left to wait for in this stage.
  *
  *   - `kill`: the divider's `kill` (see [[Divider]]): the division in progress is abandoned.
  *     The T2 core ties it low: it has nothing that could abandon an instruction it has started.
  *     The later cores are where it is used. A core that takes an interrupt (T3 on) abandons a
  *     division in progress when an interrupt is taken before it, and the pipeline connects it
  *     to the flush of the stage that holds the division: an interrupt or an older exception
  *     during a divider stall abandons it (T4-04). It must not depend on `done` in the same
  *     cycle (see the divider's paragraph on combinational paths).
  *
  * @param divLatency
  *   the divider's latency, one of `Divider.latencies`
  * @param mutant
  *   a bug to inject for negative tests (VER-04), one of [[Execute.mutants]]: T1's (`sra`, ...)
  *   go to T1's `Execute`, `no-sign-fix` to the divider; `mulh-unsigned` makes `mulh` multiply
  *   unsigned (as `mulhu`), and `no-div-stall` makes a division done at once, before the divider
  *   answers. `None` in every real build.
  */
class Execute(divLatency: Int = Divider.DIV_LATENCY, mutant: Option[String] = None) extends Module {
  require(mutant.forall(Execute.mutants.contains), s"unknown mutant ${mutant.getOrElse("")}")
  val io = IO(new Bundle {
    val valid             = Input(Bool())
    val kill              = Input(Bool())
    val pc                = Input(UInt(32.W))
    val decoded           = Input(new DecodedInst)
    val m                 = Input(new MulDivControl)
    val rs1_data          = Input(UInt(32.W))
    val rs2_data          = Input(UInt(32.W))
    val alu_result        = Output(UInt(32.W))
    val redirect          = Output(Valid(UInt(32.W)))
    val target_misaligned = Output(Bool())
    val result            = Output(UInt(32.W))
    val done              = Output(Bool())
  })

  // T1's stage, unchanged: the student's own answer to exercise 1.4 serves here too
  val base = Module(new mycpu.singlecycle.Execute(mutant.filter(mycpu.singlecycle.Execute.mutants)))
  base.io.pc           := io.pc
  base.io.decoded      := io.decoded
  base.io.rs1_data     := io.rs1_data
  base.io.rs2_data     := io.rs2_data
  io.alu_result        := base.io.alu_result
  io.redirect          := base.io.redirect
  io.target_misaligned := base.io.target_misaligned

  // the operands of an M instruction are the two source registers, whatever the ALU's selects say
  val multiplier = Module(new Multiplier)
  multiplier.io.op := (if (mutant.contains(Execute.MulhUnsigned))
                         Mux(io.m.mul_op === MulOp.Mulh, MulOp.Mulhu, io.m.mul_op)
                       else io.m.mul_op)
  multiplier.io.a := io.rs1_data
  multiplier.io.b := io.rs2_data

  val divider = Module(new Divider(divLatency, mutant.filter(Divider.mutants)))
  divider.io.req.bits.op       := io.m.div_op
  divider.io.req.bits.dividend := io.rs1_data
  divider.io.req.bits.divisor  := io.rs2_data
  divider.io.kill              := io.kill

  // [CA26: Exercise 2.6] The stall: an instruction that takes more than one cycle
  // Contract: a division is given to the divider once, and the instruction is `done` in the cycle
  // in which the divider answers it, not before; every other instruction is done at once. The
  // header of `Divider` defines the handshake: what `req.ready` and `resp.valid` promise, and so
  // what the requester may and may not do while it waits. The core holds the instruction and its
  // operands until then, and a division whose result nobody reads (rd is x0) is carried out like
  // any other. Mind `valid` (bootcamp 2.6 for `Decoupled` and `Valid`; handout §T2.5).
  ?
  if (mutant.contains(Execute.NoDivStall)) io.done := true.B

  // [CA26: Exercise 2.7] The result of the execute stage
  // Contract: `result` is what the instruction computes for `rd` when the decode table names the
  // ALU, the multiplier or the divider as the source (`m.rd_source`, see `ControlSignals` for
  // when the field means something). For the other instructions `result` is not used, and
  // `alu_result` above is not affected by this choice (bootcamp 5.3; handout §T2.5).
  io.result := ?
}

object Execute {

  val MulhUnsigned = "mulh-unsigned"
  val NoDivStall   = "no-div-stall"

  /** The bugs `mutant` can inject (VER-04): T1's (`sra`, ...), the divider's (`no-sign-fix`) and
    * this stage's (`mulh-unsigned`, `no-div-stall`).
    */
  val mutants: Set[String] =
    mycpu.singlecycle.Execute.mutants ++ Divider.mutants ++ Set(MulhUnsigned, NoDivStall)
}
