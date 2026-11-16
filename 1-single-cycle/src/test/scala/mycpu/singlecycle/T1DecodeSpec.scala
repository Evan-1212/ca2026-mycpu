// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common.AsmX
import org.scalatest.flatspec.AnyFlatSpec
import riscv.{ASel, AluOp, BSel, Instructions, Rv32i}

import scala.util.Random

/** `InstructionDecode` on its own (T1-01, T1-06): what each RV32I instruction does, `fence.i`, and
  * words that are no instruction.
  */
class T1DecodeSpec extends AnyFlatSpec with ChiselSim {

  /** The flags of [[DecodedInst]] that an instruction may set. */
  private case class Flags(
      load: Boolean = false,
      store: Boolean = false,
      branch: Boolean = false,
      jal: Boolean = false,
      jalr: Boolean = false,
      ecall: Boolean = false,
      ebreak: Boolean = false,
      rdWrite: Boolean = false,
      source: Option[RdSource.Type] = None
  )
  private val nothing = Flags()

  private def expected(name: String): Flags = name match {
    case "LB" | "LH" | "LW" | "LBU" | "LHU" => Flags(load = true, rdWrite = true, source = Some(RdSource.Load))
    case "SB" | "SH" | "SW"                 => Flags(store = true)
    case "BEQ" | "BNE" | "BLT" | "BGE" | "BLTU" | "BGEU" => Flags(branch = true)
    case "JAL"    => Flags(jal = true, rdWrite = true, source = Some(RdSource.Link))
    case "JALR"   => Flags(jalr = true, rdWrite = true, source = Some(RdSource.Link))
    case "FENCE"  => nothing
    case "ECALL"  => Flags(ecall = true)
    case "EBREAK" => Flags(ebreak = true)
    case _ /* lui, auipc, register and immediate ALU ops */ => Flags(rdWrite = true, source = Some(RdSource.Alu))
  }

  private def check(c: InstructionDecode, word: Long, legal: Boolean, f: Flags, what: String): Unit = {
    c.io.inst.poke(word.U(32.W))
    val d    = c.io.decoded
    val text = f"$what (0x$word%08x)"
    d.legal.expect(legal.B, s"$text: legal")
    d.load.expect(f.load.B, s"$text: load")
    d.store.expect(f.store.B, s"$text: store")
    d.branch.expect(f.branch.B, s"$text: branch")
    d.jal.expect(f.jal.B, s"$text: jal")
    d.jalr.expect(f.jalr.B, s"$text: jalr")
    d.ecall.expect(f.ecall.B, s"$text: ecall")
    d.ebreak.expect(f.ebreak.B, s"$text: ebreak")
    d.rd_write.expect(f.rdWrite.B, s"$text: rd_write")
    f.source.foreach(s => d.rd_source.expect(s, s"$text: rd_source"))
    d.rs1.expect(((word >> 15) & 31).U, s"$text: rs1")
    d.rs2.expect(((word >> 20) & 31).U, s"$text: rs2")
    d.rd.expect(((word >> 7) & 31).U, s"$text: rd")
    d.funct3.expect(((word >> 12) & 7).U, s"$text: funct3")
  }

  /** RV32I (the bootcamp's software decoder) or `fence.i`; `AsmX.decode` also knows M and Zicsr. */
  private def legalInT1(word: Long): Boolean =
    Rv32i.decode(word).isDefined || AsmX.decodeExt(word).exists(_.name == "fence.i")

  "InstructionDecode" should "give every RV32I instruction its flags, register fields and write-back source" in {
    simulate(new InstructionDecode) { c =>
      val rnd = new Random(1)
      for ((name, _) <- Instructions.all; _ <- 0 until 20)
        check(c, Instructions.random(name, rnd), legal = true, expected(name), name)
    }
  }

  it should "pass on the immediate and the ALU controls of the bootcamp decoder" in {
    simulate(new InstructionDecode) { c =>
      def decode(source: String) = { c.io.inst.poke(AsmX(source).head.U(32.W)); c.io.decoded }
      val addi                   = decode("addi a0, a1, -3")
      addi.imm.expect("hfffffffd".U)
      addi.alu_op.expect(AluOp.Add)
      addi.op_a_sel.expect(ASel.Rs1)
      addi.op_b_sel.expect(BSel.Imm)
      val sra = decode("sra a0, a1, a2")
      sra.alu_op.expect(AluOp.Sra)
      sra.op_b_sel.expect(BSel.Rs2)
      val lui = decode("lui a0, 0xfffff")
      lui.imm.expect("hfffff000".U)
      lui.op_a_sel.expect(ASel.Zero)
      val sw = decode("sw a0, -4(sp)")
      sw.imm.expect("hfffffffc".U)
      sw.alu_op.expect(AluOp.Add)
      val beq = decode("l: beq a0, a1, l")
      beq.imm.expect(0.U)
      beq.op_a_sel.expect(ASel.Pc)
      val jal = decode("jal ra, l; nop; l: nop")
      jal.imm.expect(8.U)
      jal.op_a_sel.expect(ASel.Pc)
      val jalr = decode("jalr ra, 2047(a0)")
      jalr.imm.expect(2047.U)
      jalr.op_a_sel.expect(ASel.Rs1)
    }
  }

  it should "decode fence.i as a legal instruction that does nothing, whatever its other fields hold" in {
    simulate(new InstructionDecode) { c =>
      check(c, AsmX.fixed("fence.i"), legal = true, nothing, "fence.i")
      val rnd = new Random(2)
      for (_ <- 0 until 200) {
        val word = (rnd.nextLong() & 0xffffffffL & ~0x707fL) | 0x100fL // opcode MISC-MEM, funct3 001
        check(c, word, legal = true, nothing, "fence.i with other fields set")
      }
    }
  }

  it should "report words that are no RV32I_Zifencei instruction as not legal, with no flag set" in {
    simulate(new InstructionDecode) { c =>
      val rnd      = new Random(3)
      val directed = Seq(
        0x00000000L, 0xffffffffL, 0x0000200fL, // MISC-MEM with funct3 010
        0x0000700fL, 0x02b50533L,              // mul a0, a0, a1 (M: T2)
        0x30200073L,                           // mret (T3)
        0x10500073L,                           // wfi (T3)
        0x34011173L,                           // csrrw sp, mscratch, sp (Zicsr: T3)
        0x00200073L,                           // SYSTEM with another funct12
        0x00003003L,                           // ld (RV64)
        0x00003023L,                           // sd (RV64)
        0x00002063L,                           // a branch with funct3 010
        0x00001067L,                           // jalr with funct3 001
        0x40001013L,                           // slli with funct7 0100000
        0x20005013L                            // srli with another funct7
      )
      val nearMisses = for ((name, _) <- Instructions.all; _ <- 0 until 40) yield Instructions.nearMiss(name, rnd)
      val random     = Seq.fill(2000)(rnd.nextLong() & 0xffffffffL)
      val illegal    = (directed ++ nearMisses ++ random).filterNot(legalInT1)
      assert(directed.forall(illegal.contains) && illegal.size > 1500)
      for (word <- illegal) check(c, word, legal = false, nothing, "not an instruction")
    }
  }
}
