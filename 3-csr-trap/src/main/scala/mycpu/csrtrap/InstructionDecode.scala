// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import chisel3.util.BitPat
import mycpu.rv32im.{ControlRow, Decode, DecodeEntry, MulDivControl, ResultSource}
import mycpu.singlecycle.{DecodedInst, RdSource}
import riscv.Opcode

/** What the privileged instructions add to a decoded instruction (T3): each flag is false for a
  * word that is no such instruction.
  *   - `csr`: a CSR instruction (Zicsr). Its operation is the low two bits of funct3
  *     (`CsrOp`), and funct3 bit 2 says it is an immediate form;
  *   - `mret`, `wfi`: the two instructions of the machine-mode ISA that this hart has besides
  *     `ecall` and `ebreak` (which the RV32I table already knows, as `DecodedInst.ecall` and
  *     `ebreak`).
  */
class SystemControl extends Bundle {
  val csr  = Bool()
  val mret = Bool()
  val wfi  = Bool()
}

/** Instruction decode of T3: T2's decode table with the instructions of Zicsr, `mret` and `wfi`
  * appended, by the extension mechanism of `mycpu.rv32im.Decode`: the new entries carry tags,
  * and a `TagField` per tag makes it a flag of the table. No entry and no field of T2's table
  * changes.
  *
  * The output is T2's (`decoded`, `m`), from the same fields in the same way as T2's stage gives
  * it, plus `system`. A CSR instruction writes `rd` (with the CSR's old value, which the core
  * puts where an ALU result would go: its `rd_source` is `RdSource.Alu`).
  */
class InstructionDecode extends Module {
  val io = IO(new Bundle {
    val inst    = Input(UInt(32.W))
    val decoded = Output(new DecodedInst)
    val m       = Output(new MulDivControl)
    val system  = Output(new SystemControl)
  })

  val out  = Decode.decode(io.inst, InstructionDecode.table)
  val ctrl = Decode.controls(out)

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
  // as in T2's stage: the sources of T1 keep their names, the others reach write-back as an ALU
  // result does
  d.rd_source := RdSource.Alu
  for ((source, t1) <- Seq(ResultSource.Load -> RdSource.Load, ResultSource.Link -> RdSource.Link))
    when(ctrl.rd_source === source) { d.rd_source := t1 }

  io.m.rd_source := ctrl.rd_source
  io.m.div       := ctrl.div
  io.m.mul_op    := ctrl.mul_op
  io.m.div_op    := ctrl.div_op

  io.system.csr  := out(InstructionDecode.CsrFlag)
  io.system.mret := out(InstructionDecode.MretFlag)
  io.system.wfi  := out(InstructionDecode.WfiFlag)
}

object InstructionDecode {

  /** The flags that T3 adds to the table, one per tag. */
  object CsrFlag  extends Decode.TagField("csr")
  object MretFlag extends Decode.TagField("mret")
  object WfiFlag  extends Decode.TagField("wfi")

  /** The names of the CSR instructions, by funct3 (0 and 4 are no CSR instruction). */
  val csrNames: Seq[(String, Int)] =
    Seq("CSRRW" -> 1, "CSRRS" -> 2, "CSRRC" -> 3, "CSRRWI" -> 5, "CSRRSI" -> 6, "CSRRCI" -> 7)

  /** The six CSR instructions: any CSR address, any rs1 (or uimm) and rd, with their funct3 under
    * the SYSTEM opcode (Unprivileged spec, "Zicsr" extension). They write rd, from the CSR.
    */
  def zicsr: Seq[DecodeEntry] = csrNames.map { case (name, funct3) =>
    require(funct3 >= 0 && funct3 < 8, s"$name: funct3 $funct3 has more than 3 bits")
    // the word as a pattern string, bit 31 first: csr and rs1 free, funct3, rd free, the opcode
    val bits = "?" * 17 + f"${funct3.toBinaryString.toInt}%03d" + "?" * 5 + Opcode.System.litValue.toString(2)
    DecodeEntry(
      name,
      BitPat("b" + bits),
      ControlRow(rdWrite = true, rdSource = Some(ResultSource.Alu), tags = Set("csr"))
    )
  }

  /** `mret` and `wfi`, each a single word (Privileged spec, the instruction listing). */
  def privileged: Seq[DecodeEntry] = Seq(
    DecodeEntry("MRET", BitPat("b00110000001000000000000001110011"), ControlRow(tags = Set("mret"))),
    DecodeEntry("WFI", BitPat("b00010000010100000000000001110011"), ControlRow(tags = Set("wfi")))
  )

  /** The instructions of T3: T2's, Zicsr, `mret` and `wfi`. */
  def rv32imZicsr: Seq[DecodeEntry] = Decode.rv32im ++ zicsr ++ privileged

  /** The decode table of T3: T2's fields and the three flags. */
  def table = Decode.table(rv32imZicsr, Seq(CsrFlag, MretFlag, WfiFlag))
}
