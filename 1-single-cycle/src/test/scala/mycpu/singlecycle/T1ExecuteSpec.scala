// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.Valid
import mycpu.common.AsmX
import org.scalatest.flatspec.AnyFlatSpec
import riscv.IsaSim

import scala.util.Random

/** `Execute` driven by `InstructionDecode`, so a test gives an instruction word instead of every
  * control signal.
  */
class DecodeExecute(mutant: Option[String] = None) extends Module {
  val io = IO(new Bundle {
    val inst              = Input(UInt(32.W))
    val pc                = Input(UInt(32.W))
    val rs1_data          = Input(UInt(32.W))
    val rs2_data          = Input(UInt(32.W))
    val alu_result        = Output(UInt(32.W))
    val redirect          = Output(Valid(UInt(32.W)))
    val target_misaligned = Output(Bool())
  })
  val decode  = Module(new InstructionDecode)
  val execute = Module(new Execute(mutant))
  decode.io.inst       := io.inst
  execute.io.decoded   := decode.io.decoded
  execute.io.pc        := io.pc
  execute.io.rs1_data  := io.rs1_data
  execute.io.rs2_data  := io.rs2_data
  io.alu_result        := execute.io.alu_result
  io.redirect          := execute.io.redirect
  io.target_misaligned := execute.io.target_misaligned
}

/** `Execute` (T1-01): results, addresses, control transfers and their targets. */
class T1ExecuteSpec extends AnyFlatSpec with ChiselSim {
  private val pc      = 0x80001000L
  private val corners = Seq(0, 1, -1, Int.MinValue, Int.MaxValue, 31, 32, 0x55555555, 0xaaaaaaaa)

  private def u32(v: Long): Long = v & 0xffffffffL

  /** Gives the instruction of `source` (assembled at `pc`) the register values `a` and `b`. */
  private def drive(c: DecodeExecute, source: String, a: Int, b: Int): Unit = {
    c.io.inst.poke(AsmX(source, pc).head.U(32.W))
    c.io.pc.poke(pc.U)
    c.io.rs1_data.poke(u32(a).U)
    c.io.rs2_data.poke(u32(b).U)
  }

  private val regOps = Seq("add", "sub", "sll", "slt", "sltu", "xor", "srl", "sra", "or", "and")

  "Execute" should "compute every register-register and register-immediate result as the ISA model does" in {
    simulate(new DecodeExecute) { c =>
      val rnd    = new Random(4)
      val values = corners ++ Seq.fill(12)(rnd.nextInt())
      for (op <- regOps; a <- values; b <- values) {
        drive(c, s"$op a0, a1, a2", a, b)
        c.io.alu_result.expect(u32(IsaSim.alu(op, a, b)).U, s"$op $a, $b")
        c.io.redirect.valid.expect(false.B)
        c.io.target_misaligned.expect(false.B)
      }
      for (op <- Seq("add", "slt", "sltu", "xor", "or", "and"); a <- values; imm <- Seq(-2048, -1, 0, 1, 5, 2047)) {
        val name = if (op == "sltu") "sltiu" else s"${op}i"
        drive(c, s"$name a0, a1, $imm", a, rnd.nextInt())
        c.io.alu_result.expect(u32(IsaSim.alu(op, a, imm)).U, s"$name $a, $imm")
      }
      for (op <- Seq("sll", "srl", "sra"); a <- values; shamt <- Seq(0, 1, 7, 31)) {
        drive(c, s"${op}i a0, a1, $shamt", a, rnd.nextInt())
        c.io.alu_result.expect(u32(IsaSim.alu(op, a, shamt)).U, s"${op}i $a, $shamt")
      }
    }
  }

  it should "compute lui, auipc and the address of loads and stores" in {
    simulate(new DecodeExecute) { c =>
      drive(c, "lui a0, 0xabcde", 7, 9)
      c.io.alu_result.expect("habcde000".U)
      drive(c, "auipc a0, 0xfffff", 7, 9)
      c.io.alu_result.expect(u32(pc - 0x1000).U)
      drive(c, "lw a0, -8(a1)", 0x80002000, 9)
      c.io.alu_result.expect("h80001ff8".U)
      c.io.redirect.valid.expect(false.B)
      drive(c, "sb a2, 2047(a1)", 0x10000000, 9)
      c.io.alu_result.expect("h100007ff".U)
      c.io.redirect.valid.expect(false.B)
      c.io.target_misaligned.expect(false.B, "a data address is not a target")
    }
  }

  it should "take each branch exactly when its condition holds, to pc + offset" in {
    simulate(new DecodeExecute) { c =>
      val rnd    = new Random(5)
      val values = corners ++ Seq.fill(8)(rnd.nextInt())
      for (op <- Seq("beq", "bne", "blt", "bge", "bltu", "bgeu"); a <- values; b <- values) {
        drive(c, s"$op a1, a2, l; nop; nop; l: nop", a, b)
        val taken = IsaSim.taken(op, a, b)
        c.io.redirect.valid.expect(taken.B, s"$op $a, $b")
        if (taken) c.io.redirect.bits.expect((pc + 12).U)
        c.io.target_misaligned.expect(false.B)
      }
      // a backward branch: assembled 8 bytes before pc, the branch (the third word) is at pc
      drive(c, "nop", 3, 3)
      c.io.inst.poke(AsmX("l: nop; nop; beq a1, a2, l", pc - 8)(2).U(32.W))
      c.io.redirect.valid.expect(true.B)
      c.io.redirect.bits.expect((pc - 8).U, "a backward branch")
    }
  }

  it should "always take jal and jalr, and clear bit 0 of a jalr target" in {
    simulate(new DecodeExecute) { c =>
      drive(c, "jal ra, l; nop; l: nop", 0, 0)
      c.io.redirect.valid.expect(true.B)
      c.io.redirect.bits.expect((pc + 8).U)
      drive(c, "jalr ra, 16(a1)", 0x80002000, 0)
      c.io.redirect.valid.expect(true.B)
      c.io.redirect.bits.expect("h80002010".U)
      drive(c, "jalr ra, 17(a1)", 0x80002000, 0)
      c.io.redirect.bits.expect("h80002010".U, "bit 0 of rs1 + imm is cleared")
      c.io.target_misaligned.expect(false.B)
      drive(c, "jalr ra, -1(a1)", 0x80002000, 0)
      c.io.target_misaligned.expect(true.B, "0x80001ffe is not an instruction address")
      c.io.redirect.valid.expect(false.B, "and control does not go there")
    }
  }

  it should "report a misaligned target only for a transfer that happens, and then not redirect" in {
    simulate(new DecodeExecute) { c =>
      val beqBy2 = 0x00c58163L // beq a1, a2, +2
      val jalBy2 = 0x002000efL // jal ra, +2
      c.io.pc.poke(pc.U)
      c.io.rs1_data.poke(5.U)
      c.io.rs2_data.poke(5.U)
      c.io.inst.poke(beqBy2.U)
      c.io.target_misaligned.expect(true.B, "a taken branch to pc + 2")
      c.io.redirect.valid.expect(false.B, "an instruction that raises the exception passes control nowhere")
      c.io.rs2_data.poke(6.U)
      c.io.redirect.valid.expect(false.B)
      c.io.target_misaligned.expect(false.B, "a branch that is not taken goes nowhere")
      c.io.inst.poke(jalBy2.U)
      c.io.target_misaligned.expect(true.B, "jal to pc + 2")
      c.io.redirect.valid.expect(false.B)
      drive(c, "jalr ra, 2(a1)", 0x80002000, 0)
      c.io.target_misaligned.expect(true.B, "jalr to an address with bit 1 set")
      c.io.redirect.valid.expect(false.B)
      drive(c, "jalr ra, 3(a1)", 0x80002000, 0)
      c.io.target_misaligned.expect(true.B, "bit 0 is cleared, bit 1 is not")
      drive(c, "jalr ra, 4(a1)", 0x80002000, 0)
      c.io.target_misaligned.expect(false.B)
      c.io.redirect.valid.expect(true.B)
      drive(c, "addi a0, a1, 2", 0x80002000, 0)
      c.io.target_misaligned.expect(false.B, "an ALU result is not a target")
      // a load, a store and an ALU instruction whose result has low bits set are no transfers
      for (other <- Seq("lw a0, 2(a1)", "sw a2, 3(a1)", "sub a0, a1, a2", "auipc a0, 1", "lui a0, 1")) {
        drive(c, other, 0x80002001, 0)
        c.io.target_misaligned.expect(false.B, other)
        c.io.redirect.valid.expect(false.B, other)
      }
    }
  }

  "The sra mutant" should "shift in zeros where sra shifts in the sign, and change nothing else" in {
    simulate(new DecodeExecute(Some("sra"))) { c =>
      drive(c, "sra a0, a1, a2", -16, 2)
      c.io.alu_result.expect("h3ffffffc".U, "the injected bug (VER-04)")
      drive(c, "srai a0, a1, 2", -16, 0)
      c.io.alu_result.expect("h3ffffffc".U)
      drive(c, "srl a0, a1, a2", -16, 2)
      c.io.alu_result.expect("h3ffffffc".U)
      drive(c, "add a0, a1, a2", -16, 2)
      c.io.alu_result.expect("hfffffff2".U)
    }
    intercept[Exception](simulate(new DecodeExecute(Some("no-such-mutant")))(_ => ()))
  }
}
