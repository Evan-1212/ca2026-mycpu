// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import mycpu.singlecycle.{DecodedInst, RdSource}

/** What the M extension adds to a decoded instruction, for [[Execute]]. As in
  * [[ControlSignals]], the enum fields are meaningful only for the instructions that use them:
  * `rd_source` when the instruction writes `rd`, `mul_op` when the source is the multiplier, and
  * `div_op` when `div` is set. `div` is a flag of the table: false for every word that is no
  * division.
  */
class MulDivControl extends Bundle {
  val rd_source = ResultSource()
  val div       = Bool() // the instruction needs the divider
  val mul_op    = MulOp()
  val div_op    = DivOp()
}

/** Instruction decode of T2: this repository's decode table ([[Decode]], T2-01) in place of the
  * bootcamp's `riscv.Decoder` and of what T1's stage adds to it.
  *
  * The table knows RV32I, `fence.i` and M, and says what each instruction does with `rd`, so
  * nothing is worked out here: the stage puts the table's output into the shape that T1's stages
  * read (`mycpu.singlecycle.DecodedInst`), and passes on what M adds (`m`).
  *
  * To T1's write-back, an M instruction is one whose value for `rd` comes from the execute stage,
  * as an ALU result does: `decoded.rd_source` is `RdSource.Alu` for it, and [[Execute]] selects
  * among the ALU, the multiplier and the divider. The ALU fields of `decoded` may hold anything
  * for an M instruction (the table does not care), which is harmless: none of its flags that
  * make the datapath use the ALU's output (`load`, `store`, `branch`, `jal`, `jalr`) is set.
  */
class InstructionDecode extends Module {
  val io = IO(new Bundle {
    val inst    = Input(UInt(32.W))
    val decoded = Output(new DecodedInst)
    val m       = Output(new MulDivControl)
  })

  val table = Module(new Decode)
  table.io.inst := io.inst
  val ctrl = table.io.ctrl

  val imm_gen = Module(new riscv.ImmGen)
  imm_gen.io.inst   := io.inst
  imm_gen.io.format := ctrl.imm_format

  val d = io.decoded
  d.legal    := ctrl.legal
  d.rs1      := io.inst(19, 15)
  d.rs2      := io.inst(24, 20)
  d.rd       := io.inst(11, 7)
  d.funct3   := io.inst(14, 12)
  d.imm      := imm_gen.io.imm
  d.alu_op   := ctrl.alu_op
  d.op_a_sel := ctrl.op_a_sel
  d.op_b_sel := ctrl.op_b_sel
  d.load     := ctrl.load
  d.store    := ctrl.store
  d.branch   := ctrl.branch
  d.jal      := ctrl.jal
  d.jalr     := ctrl.jalr
  d.ecall    := ctrl.ecall
  d.ebreak   := ctrl.ebreak
  d.rd_write := ctrl.rd_write
  // T1's sources keep their names; the two that T2 adds reach write-back through the execute
  // stage, as an ALU result does
  d.rd_source := RdSource.Alu
  for ((source, t1) <- Seq(ResultSource.Load -> RdSource.Load, ResultSource.Link -> RdSource.Link))
    when(ctrl.rd_source === source) { d.rd_source := t1 }

  io.m.rd_source := ctrl.rd_source
  io.m.div       := ctrl.div
  io.m.mul_op    := ctrl.mul_op
  io.m.div_op    := ctrl.div_op
}
