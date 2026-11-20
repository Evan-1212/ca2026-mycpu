// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import riscv.{ASel, AluOp, BSel, Kind, Opcode}

/** Where the value written to `rd` comes from. */
object RdSource extends ChiselEnum {
  val Alu, Load, Link = Value
}

/** An instruction as the rest of the datapath sees it. When `legal` is false the word is not an
  * RV32I_Zifencei instruction: every flag below is then false, and the other fields mean nothing.
  * The ALU fields are the bootcamp decoder's (`riscv.Control`); they are meaningful only for the
  * instructions that use the ALU (bootcamp 5.3).
  */
class DecodedInst extends Bundle {
  val legal     = Bool()
  val rs1       = UInt(5.W)
  val rs2       = UInt(5.W)
  val rd        = UInt(5.W)
  val funct3    = UInt(3.W)
  val imm       = UInt(32.W)
  val alu_op    = AluOp()
  val op_a_sel  = ASel()
  val op_b_sel  = BSel()
  val load      = Bool()
  val store     = Bool()
  val branch    = Bool()
  val jal       = Bool()
  val jalr      = Bool()
  val ecall     = Bool()
  val ebreak    = Bool()
  val rd_write  = Bool() // the instruction writes register `rd` (which may be x0)
  val rd_source = RdSource()
}

/** Instruction decode. The decode table is the student's own from bootcamp 5.3 (`riscv.Decoder`,
  * built from `riscv.DecodeRows.table`), and the immediate comes from the bootcamp's `ImmGen`.
  * This stage adds what the table does not say: Zifencei, and what each kind of instruction does
  * with `rd`.
  */
class InstructionDecode extends Module {
  val io = IO(new Bundle {
    val inst    = Input(UInt(32.W))
    val decoded = Output(new DecodedInst)
  })

  val decoder = Module(new riscv.Decoder)
  decoder.io.inst := io.inst
  val ctrl = decoder.io.ctrl

  val imm_gen = Module(new riscv.ImmGen)
  imm_gen.io.inst   := io.inst
  imm_gen.io.format := ctrl.immFormat

  // [CA26: Exercise 1.3] Find the bug: what an instruction does with rd
  // Contract: `rd_write` is true exactly for the instructions that write a result to register
  // rd, and `rd_source` then names where that result comes from: the ALU, the data memory or the
  // link address. Instructions whose encoding has no rd field must not write (bootcamp 5.1 and
  // 5.4; handout §T1.2). The code of this class compiles, but one line of it breaks this rule:
  // find it with the specs, a retire log or a waveform, fix it, and explain the fix in your
  // report (which run showed it, in which cycle and signal, and why the fix is right).
  val d = io.decoded
  d.rs1      := io.inst(19, 15)
  d.rs2      := io.inst(24, 20)
  d.rd       := io.inst(11, 7)
  d.funct3   := io.inst(14, 12)
  d.imm      := imm_gen.io.imm
  d.alu_op   := ctrl.aluOp
  d.op_a_sel := ctrl.aSel
  d.op_b_sel := ctrl.bSel

  // [CA26: Exercise 1.2] Zifencei, and words the bootcamp decoder does not know
  // Contract: FENCE.I is not in the bootcamp's RV32I table. In this core it is a legal instruction
  // that changes nothing but the PC: the RAM answers a fetch in the cycle of the request, so a
  // store is visible to the very next fetch and there is nothing to flush. The Unprivileged spec
  // ("Zifencei") says which fields of the word make it a FENCE.I and what to do with the others.
  // `is_kind(k)`: the word is an instruction of kind `k`. Mind what the table's fields hold for a
  // word that it does not cover (bootcamp 5.3; handout §T1.6).
  val is_fence_i = ?
  d.legal := ?
  def is_kind(k: Kind.Type): Bool = ?

  d.load   := is_kind(Kind.Load)
  d.store  := is_kind(Kind.Store)
  d.branch := is_kind(Kind.Branch)
  d.jal    := is_kind(Kind.Jal)
  d.jalr   := is_kind(Kind.Jalr)
  d.ecall  := is_kind(Kind.Ecall)
  d.ebreak := is_kind(Kind.Ebreak)

  // what the instruction does with rd
  d.rd_write  := is_kind(Kind.Alu) || d.load || d.store || d.jal || d.jalr
  d.rd_source := Mux(d.load, RdSource.Load, Mux(d.jal || d.jalr, RdSource.Link, RdSource.Alu))
}
