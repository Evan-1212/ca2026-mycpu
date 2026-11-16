// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.singlecycle.T1
import org.scalatest.flatspec.AnyFlatSpec
import riscv.Halt

/** The halt causes on the T2 core (T1-07, unchanged by M): the core takes them from T1
  * (`mycpu.singlecycle.CPU.haltCause`), around another decode and another execute stage, so each
  * cause is reached once more here, and each next to a division: the instruction that halts
  * comes right after one, with a register that the division wrote. `HaltSpec` of T1 has the
  * cases of each cause in full.
  */
class T2HaltSpec extends AnyFlatSpec with ChiselSim {

  /** Runs `source` against the golden model and expects it to halt with `cause`. */
  private def expectHalt(c: Top, name: String, source: String, cause: Halt): Seq[IssRetire] = {
    val result = CoSim2.check(c, T1.asm(source), T2.model)
    T2.checkStalls(result, c.divLatency, name) // the halting record waits for nothing
    val last = result.trace.last
    assert(last.halt.contains(cause), s"$name: $last")
    assert(last.nextPc == last.pc && last.rd == 0 && last.mem.isEmpty, s"$name: a halt changes no state: $last")
    assert(result.trace.init.forall(_.halt.isEmpty), s"$name: only the last record halts")
    c.io.halted.expect(true.B, s"$name: halted")
    for (_ <- 0 until 5) {
      c.clock.step()
      c.io.halted.expect(true.B, s"$name: a halted core stays halted")
      c.io.retire.valid.expect(false.B, s"$name: a halted core retires nothing")
    }
    result.trace
  }

  // a0 = 36 / 4 = 9 (odd: a jump to it is misaligned), s0 = an address without a device
  private val setup = "li a1, 36; li a2, 4; li s0, 0x40000000; div a0, a1, a2"

  "The T2 core" should "halt for T1's causes, right after a division too" in {
    for (latency <- Divider.latencies)
      simulate(new Top(divLatency = latency)) { c =>
        val cases = Seq(
          ("ecall", "ecall", Halt.Ecall),
          ("ebreak", "ebreak", Halt.Ebreak),
          ("mret (from T3 on)", ".word 0x30200073", Halt.Illegal),
          ("csrrw (Zicsr, from T3 on)", ".word 0x34011173", Halt.Illegal),
          ("funct7 0000010 under OP", ".word 0x04c58533", Halt.Illegal),
          ("mulw (RV64)", ".word 0x02c5853b", Halt.Illegal),
          ("a misaligned load", "lw a3, 0(a0)", Halt.Misaligned),
          ("a misaligned store", "sh a1, 0(a0)", Halt.Misaligned),
          ("a jump to a misaligned target", "addi a0, a0, 1; jalr ra, 0(a0)", Halt.Misaligned),
          ("a load from an unmapped address", "lw a3, 0(s0)", Halt.BadAddress),
          ("a store to an unmapped address", "sb a1, 3(s0)", Halt.BadAddress),
          ("a fetch from an unmapped address", "jr s0", Halt.BadAddress)
        )
        for ((name, halting, cause) <- cases) {
          val trace = expectHalt(c, name, s"$setup; $halting; li a0, 77", cause)
          assert(T1.regAfter(trace, "a0") != 77, s"$name: nothing after the halt ran")
          assert(trace.exists(r => AsmX.decodeExt(r.inst).exists(_.name == "div")), name)
        }
      }
  }

  it should "execute the M instructions, which T1 halts on as illegal" in {
    simulate(new Top) { c =>
      for (name <- MModel.mulNames ++ MModel.divNames) {
        val trace = expectHalt(c, name, s"li a1, -36; li a2, 5; $name a0, a1, a2; ecall", Halt.Ecall)
        assert(T1.regAfter(trace, "a0") == MModel.result(name, -36, 5), name)
      }
    }
  }
}
