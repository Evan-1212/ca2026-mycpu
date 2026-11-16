// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common.{AsmX, MemImage}
import mycpu.csrtrap.{HpmEvents, T3}
import org.scalatest.flatspec.AnyFlatSpec

/** T4-02: directed hazard sequences, each with the number of cycles that the consumer waits in ID
  * (a load-use stall, hpm event 4) and that a taken branch or jump costs in flushed instructions
  * (hpm event 5), per variant, exactly as `4-pipeline/README.md` derives them from the stage that
  * produces and the stage that consumes each operand. Each sequence runs between nops, against
  * the golden model; the counts are read twice: from the cycles between the records (the retire
  * port) and from the hpm counters around the sequence.
  */
class HazardSpec extends AnyFlatSpec with ChiselSim {
  import HazardSpec._

  for (variant <- T4.variants) {
    s"The $variant pipeline" should "stall and flush exactly as derived, for every hazard sequence" in {
      simulate(T4.top(variant)) { c =>
        for (s <- sequences) {
          val (image, symbols) = program(s)
          val run              = T4.timed(c, image)()
          val counts           = T3.counters(run.result.trace)
          val flushes          = s.flushes(variant)
          val what             = s"${s.name} on $variant"
          // a held-back row (its stall count is for you to derive) runs and flushes as checked here;
          // its stall count is checked by the grading run
          if (s.published) {
            val stalls = s.stalls(variant)
            assert(run.idleBefore(symbols("c")) == stalls, s"$what: cycles without a record before the consumer's")
            assert(
              counts(HpmEvents.LoadUseStall) == stalls,
              s"$what: load-use stall cycles (hpm event ${HpmEvents.LoadUseStall})"
            )
          }
          assert(run.gap(symbols("c"), symbols("n")) == flushes, s"$what: cycles between the consumer and the next")
          assert(counts(HpmEvents.FlushBubble) == flushes, s"$what: flush bubbles (hpm event 5)")
          assert(T4.reg(run.result, "a0") == 0)
        }
        info(s"${sequences.size} sequences, stalls ${sequences
            .map(s => if (s.published) s.stalls(variant).toString else "?")
            .mkString(" ")}")
      }
    }
  }
}

object HazardSpec {

  /** A hazard sequence: the producer (one line, or more for one with a nop after it), the
    * consumer, and the cycles it costs per variant: (Stall, Forward, EarlyBranch).
    */
  case class Sequence(
      name: String,
      producer: String,
      consumer: String,
      stall: (Int, Int, Int),
      flush: (Int, Int, Int)
  ) {
    private def of(t: (Int, Int, Int), v: Variant) = v match {
      case Variant.Stall       => t._1
      case Variant.Forward     => t._2
      case Variant.EarlyBranch => t._3
    }
    def stalls(v: Variant): Int  = of(stall, v)
    def flushes(v: Variant): Int = of(flush, v)

    /** False for a row whose stall counts this file does not give (`heldBack`). */
    def published: Boolean = stall != heldBack
  }

  private val none = (0, 0, 0)

  /** The stall counts of a row that 4-pipeline/README.md asks you to derive (two of them from its
    * pipeline diagrams): not given here, and checked by the grading run.
    */
  val heldBack: (Int, Int, Int) = (-1, -1, -1)
  // a taken branch or jump resolved in EX flushes two instructions, one resolved in ID one
  private val taken = (2, 2, 1)

  /** The sequences of T4-02. Registers: `t1` = 7, `t2` = the address of `cell` (which holds 9, the
    * address of `cell` at +8 and the address of `n` at +12), `t3` = the address of `n`. The
    * producer writes `a1`; the consumer reads it. `n` is the instruction after the consumer, which
    * a branch or jump goes to as well.
    */
  val sequences: Seq[Sequence] = Seq(
    Sequence("ALU-ALU", "add   a1, t1, t1", "add   s0, a1, a1", (2, 0, 0), none),
    Sequence("ALU-nop-ALU", "add   a1, t1, t1\n      nop", "add   s0, a1, t1", (1, 0, 0), none),
    Sequence("load-use", "lw    a1, 0(t2)", "add   s0, a1, t1", (2, 1, 1), none),
    Sequence("load-nop-use", "lw    a1, 0(t2)\n      nop", "add   s0, a1, t1", (1, 0, 0), none),
    Sequence("load-store (data)", "lw    a1, 0(t2)", "sw    a1, 4(t2)", (2, 1, 1), none),
    Sequence("load-store (address)", "lw    a1, 8(t2)", "sw    t1, 4(a1)", heldBack, none),
    Sequence("ALU-store (data)", "add   a1, t1, t1", "sw    a1, 4(t2)", heldBack, none),
    Sequence("ALU-branch", "add   a1, t1, t1", "beq   a1, zero, n", (2, 0, 1), none),
    Sequence("ALU-nop-branch", "add   a1, t1, t1\n      nop", "beq   a1, zero, n", (1, 0, 0), none),
    Sequence("ALU-branch (taken)", "add   a1, t1, t1", "bne   a1, zero, n", (2, 0, 1), taken),
    Sequence("load-branch", "lw    a1, 0(t2)", "beq   a1, zero, n", (2, 1, 2), none),
    Sequence("load-nop-branch", "lw    a1, 0(t2)\n      nop", "beq   a1, zero, n", heldBack, none),
    Sequence("load-branch (taken)", "lw    a1, 0(t2)", "bne   a1, zero, n", (2, 1, 2), taken),
    Sequence("JALR after load", "lw    a1, 12(t2)", "jalr  zero, 0(a1)", (2, 1, 2), taken),
    Sequence("JALR after ALU", "addi  a1, t3, 0", "jalr  zero, 0(a1)", heldBack, taken),
    Sequence("x0 destination (ALU)", "add   zero, t1, t1", "add   s0, zero, zero", none, none),
    Sequence("x0 destination (load)", "lw    zero, 0(t2)", "add   s0, zero, t1", none, none),
    Sequence("x0 destination (branch)", "add   zero, t1, t1", "bne   zero, zero, n", none, none),
    Sequence("independent", "add   a1, t1, t1", "add   s0, t1, t1", none, none),
    Sequence("taken branch", "nop", "beq   zero, zero, n", none, taken),
    Sequence("jal", "nop", "jal   zero, n", none, taken)
  )

  /** The program of `s`, with its symbols: `c` the consumer and `n` the next instruction. */
  def program(s: Sequence): (MemImage, Map[String, Long]) = {
    val p = AsmX.assemble(T3.frame(s"""
      li    t1, 7
      la    t2, cell
      la    t3, n
      sw    t2, 8(t2)
      sw    t3, 12(t2)
${T3.startCounters}
      nop
      nop
      nop
      nop
      ${s.producer}
  c:  ${s.consumer}
  n:  nop
      nop
      nop
      nop
${T3.readCounters}
      j     out
      .balign 16
  cell:
      .word 9, 0, 0, 0
  out:
"""))
    (MemImage(p), p.symbols)
  }
}
