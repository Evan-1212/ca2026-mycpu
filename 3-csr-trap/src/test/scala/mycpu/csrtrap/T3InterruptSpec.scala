// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec

/** The interrupts of the T3 core (T3-03), one directed program per rule, on the system with its
  * CLINT and SimCtrl `MEIP`, against the golden model, and against values from the specification:
  * the bit positions of `mip` and `mie` (MSIP 3, MTIP 7, MEIP 11), the gate by `mstatus.MIE` and
  * `mie`, the priority MEI > MSI > MTI, vectored mode, and `mepc` the next instruction that has not
  * executed.
  */
class T3InterruptSpec extends AnyFlatSpec with ChiselSim {
  import T3.{meipAddr, msipAddr, mtimecmpAddr}

  /** Raises the interrupt `code` from its device: the CLINT's `msip`, its timer (due now), or
    * SimCtrl's `MEIP`.
    */
  private def raise(code: Int): String = code match {
    case Trap.Msi => f"li t0, 0x$msipAddr%x; li t1, 1; sw t1, 0(t0)"
    case Trap.Mti => f"li t0, 0x$mtimecmpAddr%x; sw zero, 4(t0); sw zero, 0(t0)"
    case Trap.Mei => f"li t0, 0x$meipAddr%x; li t1, 1; sw t1, 0(t0)"
  }

  private def interrupts(result: CoSimResult): Seq[IssRetire] = T3.traps(result).filter(_.trap.get.interrupt)

  "The T3 core" should "show MSIP, MTIP and MEIP at bits 3, 7 and 11 of mip, pending with MIE = 0" in {
    simulate(new Top) { c =>
      for ((code, i) <- Seq(Trap.Msi, Trap.Mti, Trap.Mei).zipWithIndex) {
        val source = s"""
          ${raise(code)}
        1:
          csrr  a1, mip
          beqz  a1, 1b
          li    t0, ${1L << code}
          csrw  mie, t0              # enabled, but MIE is 0: not taken
          csrr  a2, mip
        """
        val result = T3.run(c, source)
        assert(T3.reg(result.trace, "a1") == 1L << code && T3.reg(result.trace, "a2") == 1L << code, s"code $code")
        assert(T3.traps(result).isEmpty, "no interrupt without mstatus.MIE")
        info(s"interrupt $code: mip reads 0x${(1L << code).toHexString}")
      }
    }
  }

  it should "take an enabled pending interrupt only with mstatus.MIE, before the next instruction" in {
    simulate(new Top) { c =>
      val source = s"""
          ${raise(Trap.Mti)}
          li    t0, 0x8              # MSIE only: MTIP is pending but not enabled
          csrw  mie, t0
          csrsi mstatus, 8
          nop
          li    t0, 0x80
      on: csrw  mie, t0              # now enabled: taken before the next instruction
      next:
          addi  a1, zero, 1
      """
      val p      = AsmX.assemble(T3.frame(source))
      val result = T3.check(c, MemImage(p))
      val taken  = interrupts(result)
      assert(taken.size == 1, taken.mkString("\n"))
      val r = taken.head
      assert(r.trap.contains(Trap(Trap.interruptCause(Trap.Mti), 0)) && r.inst == 0, r)
      assert(r.pc == p.symbols("next") && r.nextPc == p.symbols("trap"), "mepc is the instruction not executed")
      assert(T3.reg(result.trace, "a1") == 1, "it executes after the mret")
    }
  }

  it should "take MEI, then MSI, then MTI when all three are pending, vectored to BASE + 4 * code" in {
    simulate(new Top) { c =>
      for (vectored <- Seq(false, true)) {
        val source = s"""
            ${raise(Trap.Mti)}
            ${raise(Trap.Msi)}
            ${raise(Trap.Mei)}
            li    t0, 0x888
            csrw  mie, t0
            csrsi mstatus, 8
            nop
        """
        val p      = AsmX.assemble(T3.frame(source, vectored))
        val result = T3.check(c, MemImage(p))
        val taken  = interrupts(result)
        assert(taken.map(_.trap.get.code) == Seq(Trap.Mei, Trap.Msi, Trap.Mti), taken.mkString("\n"))
        val base = p.symbols("trap")
        for (r <- taken)
          assert(r.nextPc == (if (vectored) base + 4 * r.trap.get.code else base), s"vectored $vectored: $r")
        info(s"vectored $vectored: ${taken.map(r => f"${r.trap.get.code} -> 0x${r.nextPc}%08x").mkString(", ")}")
      }
    }
  }

  it should "follow the interrupt lines as levels: a source that stays raised is taken again after mret" in {
    simulate(new Top) { c =>
      // a handler of its own that counts and silences MEIP only on the third entry
      val source = f"""
          la    t0, handler
          csrw  mtvec, t0
          li    s4, 0
          ${raise(Trap.Mei)}
          li    t0, 0x800
          csrw  mie, t0
          csrsi mstatus, 8
      back:
          li    t0, 0x${T3.exitAddr}%x
          sw    s4, 0(t0)
      1:  j     1b
          .balign 4
      handler:
          addi  s4, s4, 1
          li    t0, 3
          bne   s4, t0, 2f
          li    t0, 0x$meipAddr%x
          sw    zero, 0(t0)
      2:  mret
      """
      val result = T3.check(c, T3.asm(source), exit = 3)
      assert(interrupts(result).size == 3)
    }
  }
}
