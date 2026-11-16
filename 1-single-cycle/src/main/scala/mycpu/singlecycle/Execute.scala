// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.util._
import riscv.{ASel, AluOp, BSel}

/** Execute: the ALU and the control-transfer decision.
  *
  * The ALU is the student's own from bootcamp 5.2 and the comparator is the one from bootcamp
  * 5.4 (`riscv.BranchUnit`). The bootcamp decode table makes the ALU compute the result of an
  * arithmetic instruction, the address of a load or store, and the sum that the target of a
  * branch or jump is made from, so one adder serves all of them.
  *
  *   - `redirect`: valid when the instruction passes control to another instruction than the one
  *     at pc + 4, with the address of that instruction.
  *   - `target_misaligned`: the instruction raises the ISA's instruction-address-misaligned
  *     exception (there is no C extension). This core halts on it; from T3 on it is a trap.
  *
  * `mutant` injects a known bug for the negative checks (VER-04): with `Some("sra")`, an
  * arithmetic right shift is carried out as a logical one. It is `None` in every real build.
  */
class Execute(mutant: Option[String] = None) extends Module {
  require(mutant.forall(Execute.mutants.contains), s"unknown mutant ${mutant.getOrElse("")}")
  val io = IO(new Bundle {
    val pc                = Input(UInt(32.W))
    val decoded           = Input(new DecodedInst)
    val rs1_data          = Input(UInt(32.W))
    val rs2_data          = Input(UInt(32.W))
    val alu_result        = Output(UInt(32.W))
    val redirect          = Output(Valid(UInt(32.W)))
    val target_misaligned = Output(Bool())
  })
  val d = io.decoded

  val alu = Module(new riscv.Alu)
  alu.io.op     := (if (mutant.contains("sra")) Mux(d.alu_op === AluOp.Sra, AluOp.Srl, d.alu_op) else d.alu_op)
  alu.io.a      := MuxLookup(d.op_a_sel, io.rs1_data)(Seq(ASel.Pc -> io.pc, ASel.Zero -> 0.U))
  alu.io.b      := Mux(d.op_b_sel === BSel.Imm, d.imm, io.rs2_data)
  io.alu_result := alu.io.out

  // the comparator decides a conditional branch from the two source registers, while the ALU is
  // busy with the sum
  val branch_unit = Module(new riscv.BranchUnit)
  branch_unit.io.funct3 := d.funct3
  branch_unit.io.a      := io.rs1_data
  branch_unit.io.b      := io.rs2_data
  val transfers = Wire(Bool()) // the ISA says control goes to the target, if the target allows it

  // [CA26: Exercise 1.4] Control transfers: the redirect, and a target that is no instruction address
  // Contract: `redirect.valid` is high in exactly the cycles in which the instruction at `pc`
  // passes control to `redirect.bits`. The Unprivileged spec gives the target of JAL, of JALR and
  // of the branches, and says which instruction raises an instruction-address-misaligned
  // exception and in which case ("Control Transfer Instructions"): `target_misaligned` is that
  // exception. An instruction that raises it passes control nowhere (bootcamp 5.4; handout §T1.3).
  ?
}

object Execute {

  /** The bugs `mutant` can inject (VER-04). */
  val mutants: Set[String] = Set("sra")
}
