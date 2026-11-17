// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.Valid
import mycpu.common.{AsmX, TestTools}
import org.scalatest.flatspec.AnyFlatSpec
import riscv.Instructions

import scala.util.Random

/** [[Execute]] driven by [[InstructionDecode]], so a test gives an instruction word instead of
  * every control signal, next to T1's execute stage on the same word (through T1's decode).
  */
class DecodeExecute(latency: Int = Divider.DIV_LATENCY, mutant: Option[String] = None) extends Module {
  val io = IO(new Bundle {
    val valid             = Input(Bool())
    val inst              = Input(UInt(32.W))
    val pc                = Input(UInt(32.W))
    val rs1_data          = Input(UInt(32.W))
    val rs2_data          = Input(UInt(32.W))
    val alu_result        = Output(UInt(32.W))
    val redirect          = Output(Valid(UInt(32.W)))
    val target_misaligned = Output(Bool())
    val result            = Output(UInt(32.W))
    val done              = Output(Bool())
    // T1's stage on the same inputs
    val t1_alu_result        = Output(UInt(32.W))
    val t1_redirect          = Output(Valid(UInt(32.W)))
    val t1_target_misaligned = Output(Bool())
  })
  val decode  = Module(new InstructionDecode)
  val execute = Module(new Execute(latency, mutant))
  decode.io.inst       := io.inst
  execute.io.valid     := io.valid
  execute.io.kill      := false.B
  execute.io.decoded   := decode.io.decoded
  execute.io.m         := decode.io.m
  execute.io.pc        := io.pc
  execute.io.rs1_data  := io.rs1_data
  execute.io.rs2_data  := io.rs2_data
  io.alu_result        := execute.io.alu_result
  io.redirect          := execute.io.redirect
  io.target_misaligned := execute.io.target_misaligned
  io.result            := execute.io.result
  io.done              := execute.io.done

  val t1 = Module(new mycpu.singlecycle.DecodeExecute)
  t1.io.inst              := io.inst
  t1.io.pc                := io.pc
  t1.io.rs1_data          := io.rs1_data
  t1.io.rs2_data          := io.rs2_data
  io.t1_alu_result        := t1.io.alu_result
  io.t1_redirect          := t1.io.redirect
  io.t1_target_misaligned := t1.io.target_misaligned
}

/** [[Execute]] (T2-04): the result it selects, and when it is done. The stall itself, as the core
  * sees it, is specified by `T2CpuSpec` and `StallSpec`.
  */
class T2ExecuteSpec extends AnyFlatSpec with ChiselSim {
  private val pc = 0x80001000L

  private def drive(c: DecodeExecute, word: Long, a: Int, b: Int, valid: Boolean = true): Unit = {
    c.io.valid.poke(valid.B)
    c.io.inst.poke(word.U(32.W))
    c.io.pc.poke(pc.U)
    c.io.rs1_data.poke(Operands.u32(a).U(32.W))
    c.io.rs2_data.poke(Operands.u32(b).U(32.W))
  }

  "Execute" should "be T1's stage for every RV32I instruction: same ALU result and transfer, done at once" in {
    simulate(new DecodeExecute) { c =>
      val rnd = new Random(21)
      for ((name, _) <- Instructions.all; _ <- 0 until 30) {
        val word   = Instructions.random(name, rnd)
        val (a, b) = Operands.random(1, rnd.nextLong()).head
        val text   = f"$name (0x$word%08x) with 0x$a%08x, 0x$b%08x"
        drive(c, word, a, b)
        c.io.done.expect(true.B, s"$text: nothing to wait for")
        c.io.alu_result.expect(c.io.t1_alu_result.peek(), s"$text: alu_result")
        c.io.redirect.valid.expect(c.io.t1_redirect.valid.peek(), s"$text: redirect")
        if (c.io.redirect.valid.peek().litToBoolean)
          c.io.redirect.bits.expect(c.io.t1_redirect.bits.peek(), s"$text: target")
        c.io.target_misaligned.expect(c.io.t1_target_misaligned.peek(), s"$text: target_misaligned")
        // where the value for rd is the ALU's, the stage's result is the ALU's
        val kind = riscv.DecodeRows.table(name).kind
        if (kind.litValue == riscv.Kind.Alu.litValue) c.io.result.expect(c.io.t1_alu_result.peek(), s"$text: result")
        c.clock.step()
      }
    }
  }

  it should "give every product in the cycle of the instruction" in {
    simulate(new DecodeExecute) { c =>
      val pairs = Operands.cornerPairs ++ Operands.random(500, seed = 22)
      for ((a, b) <- pairs; name <- MModel.mulNames) {
        drive(c, AsmX(s"$name a0, a1, a2").head, a, b)
        c.io.done.expect(true.B, MModel.show(name, a, b))
        c.io.result.expect(MModel.mul(name, a, b).U(32.W), MModel.show(name, a, b))
        c.io.redirect.valid.expect(false.B)
        c.io.target_misaligned.expect(false.B)
        c.clock.step()
      }
    }
  }

  for (latency <- Divider.latencies)
    it should s"hold a division for exactly $latency cycles (1 for the special cases), then give its result once" in {
      simulate(new DecodeExecute(latency)) { c =>
        val divisions = DivCheck.divisions(Operands.cornerPairs ++ Operands.random(40, seed = 23))
        assert(divisions.exists(_.special) && divisions.exists(!_.special))
        for (d <- divisions) {
          val word = AsmX(s"${d.name} a0, a1, a2").head
          drive(c, word, d.a, d.b)
          // cycle 0 accepts the request; the answer comes `wait` cycles later, and not before
          val wait = d.latency(latency)
          for (cycle <- 0 until wait) {
            c.io.done.expect(false.B, s"$d: done in cycle $cycle of $wait")
            c.io.redirect.valid.expect(false.B, s"$d: a division redirects nothing")
            c.io.target_misaligned.expect(false.B, d.toString)
            c.clock.step()
          }
          c.io.done.expect(true.B, s"$d: not done after $wait cycles")
          c.io.result.expect(d.expected.U(32.W), d.toString)
          c.clock.step()
          // the core has moved on: the next instruction is another one, and is not held up
          drive(c, AsmX("add a0, a1, a2").head, d.a, d.b)
          c.io.done.expect(true.B, s"$d: the instruction after the division waits")
          c.clock.step()
        }
      }
    }

  it should "start no division for a word that the core does not have yet" in {
    for (latency <- Divider.latencies)
      simulate(new DecodeExecute(latency)) { c =>
        val d = Division("div", 1000, -7)
        // the word is on the stage's inputs for a while with `valid` low, as before a fetch is answered
        drive(c, AsmX("div a0, a1, a2").head, 5, 5, valid = false)
        for (_ <- 0 until latency + 3) {
          c.io.done.expect(false.B, "a division is not done before it started")
          c.clock.step()
        }
        // from the cycle in which the core has it, with the operands of that cycle: the full latency
        drive(c, AsmX("div a0, a1, a2").head, d.a, d.b)
        for (cycle <- 0 until latency) {
          c.io.done.expect(false.B, s"latency $latency: done in cycle $cycle; the divider had started early")
          c.clock.step()
        }
        c.io.done.expect(true.B)
        c.io.result.expect(d.expected.U(32.W), "the operands are those of the cycle the request was made in")
      }
  }

  // The specs take the latencies from `Divider`; this pins the numbers that T2-03 gives them.
  "The divider's latencies" should "be 34 or 66 cycles, and 1 for the special cases" in {
    assert(Divider.latencies == Seq(34, 66) && Divider.DIV_LATENCY == 34 && Divider.EARLY_OUT_LATENCY == 1)
  }

  "The mutants" should "be T1's sra and the divider's no-sign-fix, each in its own unit" in {
    assert(Execute.mutants == Set("sra", "no-sign-fix"))
    val refused = intercept[Exception](simulate(new DecodeExecute(mutant = Some("no-such-bug")))(_ => ()))
    assert(TestTools.causedBy(refused, "unknown mutant no-such-bug"), refused)
    simulate(new DecodeExecute(mutant = Some("sra"))) { c =>
      drive(c, AsmX("sra a0, a1, a2").head, -16, 2)
      c.io.result.expect("h3ffffffc".U, "sra shifts in zeros")
      c.io.t1_alu_result.expect("hfffffffc".U, "the reference next to it does not")
      val d = Division("div", -7, 2)
      drive(c, AsmX("div a0, a1, a2").head, d.a, d.b)
      c.clock.step(Divider.DIV_LATENCY)
      c.io.result.expect(d.expected.U(32.W), "the divider is the reference")
    }
    simulate(new DecodeExecute(mutant = Some("no-sign-fix"))) { c =>
      drive(c, AsmX("sra a0, a1, a2").head, -16, 2)
      c.io.result.expect("hfffffffc".U, "the ALU is the reference")
      drive(c, AsmX("div a0, a1, a2").head, -7, 2)
      c.clock.step(Divider.DIV_LATENCY)
      c.io.done.expect(true.B)
      c.io.result.expect(3.U, "-7 / 2 without the sign fix-up")
    }
  }
}
