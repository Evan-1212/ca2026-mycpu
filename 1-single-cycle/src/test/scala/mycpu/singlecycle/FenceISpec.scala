// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec
import riscv.Halt

/** Zifencei in T1 (T1-06): a program stores an instruction, executes `fence.i` and runs what it
  * stored. The RAM answers fetches combinationally, so `fence.i` has nothing to flush; it must
  * still be a legal instruction.
  */
class FenceISpec extends AnyFlatSpec with ChiselSim {

  // overwrites the instruction at `slot` with the one at `template`, then runs into it
  private def patch(fence: String) = s"""
      la    t0, slot
      la    t1, template
      lw    t2, 0(t1)
      sw    t2, 0(t0)
      $fence
    slot:
      li    a0, 1
      ecall
    template:
      li    a0, 42
  """

  // rewrites the addi at `slot` in each of three passes: it adds 1, then 2, then 3
  private val rewriteInLoop = """
      li    s0, 0
      li    s1, 3
      li    t3, 0x100000       # one more in the immediate field of an I-type instruction
      la    t0, slot
    slot:
      addi  s0, s0, 1
      lw    t2, 0(t0)
      add   t2, t2, t3
      sw    t2, 0(t0)
      fence.i
      addi  s1, s1, -1
      bnez  s1, slot
      mv    a0, s0
      ecall
  """

  "The T1 core" should "run an instruction it stored, after fence.i" in {
    simulate(new Top) { c =>
      val result = CoSim2.check(c, T1.asm(patch("fence.i")), T1.model)
      result.mismatch.foreach(fail(_))
      assert(result.trace.last.halt.contains(Halt.Ecall) && T1.regAfter(result.trace, "a0") == 42)
      val fence = result.trace.find(_.inst == AsmX.fixed("fence.i")).getOrElse(fail("fence.i did not retire"))
      assert(fence.nextPc == fence.pc + 4 && fence.rd == 0 && fence.mem.isEmpty && fence.halt.isEmpty, "a no-op")

      // without the store the slot keeps its instruction (the test above is not vacuous)
      val unpatched = CoSim2.check(c, T1.asm(patch("fence.i").replace("sw    t2, 0(t0)", "nop")), T1.model)
      unpatched.mismatch.foreach(fail(_))
      assert(T1.regAfter(unpatched.trace, "a0") == 1)
    }
  }

  it should "accept fence.i with any value in its unused fields" in {
    simulate(new Top) { c =>
      // rd = a0, rs1 = a1 and a nonzero immediate: still a no-op, and a0 is not written
      val result = CoSim2.check(c, T1.asm(patch(".word 0x0ff5950f")), T1.model)
      result.mismatch.foreach(fail(_))
      assert(result.trace.last.halt.contains(Halt.Ecall) && T1.regAfter(result.trace, "a0") == 42)
      assert(result.trace.exists(r => r.inst == 0x0ff5950fL && r.rd == 0))
    }
  }

  it should "run code that rewrites itself in a loop" in {
    simulate(new Top) { c =>
      val result = CoSim2.check(c, T1.asm(rewriteInLoop), T1.model)
      result.mismatch.foreach(fail(_))
      assert(result.trace.last.halt.contains(Halt.Ecall) && T1.regAfter(result.trace, "a0") == 6, "1 + 2 + 3")
      assert(result.trace.count(_.inst == AsmX.fixed("fence.i")) == 3)
    }
  }
}
