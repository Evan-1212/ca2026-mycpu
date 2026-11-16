// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec
import riscv.Halt

/** The halt causes of T1 (T1-07): one directed program per cause and per way to reach it. Each
  * runs against the golden model; the spec then checks what the model cannot see: `halted` rises
  * after the halting record and stays, and nothing retires any more.
  *
  * A device must not see the access of an instruction that halts. For SimCtrl `EXIT` the
  * comparison itself watches every cycle (`CoSim2` reports an `EXIT` event that the model's
  * program did not cause). For every other device effect, [[T1CpuSpec]] checks the core's data
  * port in the cycle of the halt.
  */
class HaltSpec extends AnyFlatSpec with ChiselSim {

  /** Runs `source` against the golden model, expects it to halt with `cause`, and returns the trace. */
  private def expectHalt(c: Top, name: String, source: String, cause: Halt): Seq[IssRetire] = {
    val result = CoSim2.check(c, T1.asm(source), T1.model)
    result.mismatch.foreach(d => fail(s"$name: $d"))
    val last = result.trace.last
    assert(last.halt.contains(cause), s"$name: $last")
    assert(last.nextPc == last.pc && last.rd == 0 && last.mem.isEmpty, s"$name: a halt changes no state: $last")
    assert(result.trace.init.forall(_.halt.isEmpty), s"$name: only the last record halts")
    assert(result.cycles == result.trace.size, s"$name: one record per cycle, the halting one included")
    c.io.halted.expect(true.B, s"$name: halted")
    for (_ <- 0 until 5) {
      c.clock.step()
      c.io.halted.expect(true.B, s"$name: a halted core stays halted")
      c.io.retire.valid.expect(false.B, s"$name: a halted core retires nothing")
    }
    result.trace
  }

  "The T1 core" should "halt on ecall with cause Ecall" in {
    simulate(new Top) { c =>
      val trace = expectHalt(c, "ecall", "li a0, 3; ecall; li a0, 4", Halt.Ecall)
      assert(T1.regAfter(trace, "a0") == 3, "nothing after the ecall ran")
    }
  }

  it should "halt on ebreak with cause Ebreak" in {
    simulate(new Top) { c =>
      val trace = expectHalt(c, "ebreak", "li a0, 3; ebreak; li a0, 4", Halt.Ebreak)
      assert(T1.regAfter(trace, "a0") == 3)
    }
  }

  it should "halt on a word that is no instruction with cause Illegal" in {
    simulate(new Top) { c =>
      val words = Seq(
        "0x00000000" -> "all zeros",
        "0xffffffff" -> "all ones",
        "0x02b50533" -> "mul (M, from T2 on)",
        "0x30200073" -> "mret (from T3 on)",
        "0x34011173" -> "csrrw (Zicsr, from T3 on)",
        "0x0000200f" -> "MISC-MEM with funct3 010",
        "0x00003003" -> "ld (RV64)"
      )
      for ((word, what) <- words) {
        val trace = expectHalt(c, what, s"li a0, 3; .word $word; li a0, 4", Halt.Illegal)
        assert(trace.last.inst == java.lang.Long.decode(word) && T1.regAfter(trace, "a0") == 3, what)
      }
    }
  }

  it should "halt on a misaligned load or store with cause Misaligned, before it touches memory" in {
    simulate(new Top) { c =>
      val accesses =
        Seq("lw a1, 1(s0)", "lw a1, 2(s0)", "lh a1, 3(s0)", "lhu a1, 1(s0)", "sw t0, 3(s0)", "sh t0, 1(s0)")
      for (access <- accesses) {
        val source = s"""
          la s0, d; li t0, -1; li a1, 3
          $access
          li a1, 4
          .data
          d: .word 0x11223344, 0x55667788
        """
        val trace = expectHalt(c, access, source, Halt.Misaligned)
        assert(T1.regAfter(trace, "a1") == 3, s"$access: rd is not written")
      }
      // a misaligned store writes nothing: put a word outside every program image, try to
      // overwrite part of it, and read it back with a third program (a load keeps other memory)
      val keep = CoSim2.check(c, T1.asm("li s0, 0x80001000; li t0, 0x11223344; sw t0, 0(s0); ecall"), T1.model)
      keep.mismatch.foreach(fail(_))
      expectHalt(c, "sh over a kept word", "li s0, 0x80001000; li t0, -1; sh t0, 1(s0)", Halt.Misaligned)
      expectHalt(c, "sw over a kept word", "li s0, 0x80001000; li t0, -1; sw t0, 2(s0)", Halt.Misaligned)
      CoSim2.load(c, T1.asm("li s0, 0x80001000; lw a3, 0(s0); ecall"))
      assert(T1.regAfter(CoSim2.run(c).trace, "a3") == 0x11223344L, "the word survived both stores")
      // misaligned and unmapped at once: Misaligned is found first
      expectHalt(c, "misaligned and unmapped", "li s0, 0x40000000; lw a1, 2(s0)", Halt.Misaligned)
      // a store that would end the simulation if it reached SimCtrl: CoSim2 would report the EXIT event
      expectHalt(c, "misaligned store to SimCtrl", f"li s0, 0x${MemMap.simCtrlBase}%x; sw zero, 1(s0)", Halt.Misaligned)
    }
  }

  it should "halt on a jump or taken branch to a misaligned target with cause Misaligned, without linking" in {
    simulate(new Top) { c =>
      val jal = expectHalt(c, "jal +2", "li ra, 3; .word 0x002000ef; nop", Halt.Misaligned) // jal ra, +2
      assert(T1.regAfter(jal, "ra") == 3, "the link register is not written")
      val jalr = expectHalt(c, "jalr", "li ra, 3; la t0, l; jalr ra, 2(t0); l: nop", Halt.Misaligned)
      assert(T1.regAfter(jalr, "ra") == 3)
      expectHalt(c, "taken branch +2", "li a1, 1; li a2, 1; .word 0x00c58163; nop", Halt.Misaligned) // beq a1, a2, +2
      // not taken, the same branch is harmless; and jalr clears bit 0, so an odd address is fine
      val fine = expectHalt(
        c,
        "not taken",
        "li a1, 1; li a2, 2; .word 0x00c58163; la t0, l; jalr ra, 1(t0); nop; l: li a0, 7; ecall",
        Halt.Ecall
      )
      assert(T1.regAfter(fine, "a0") == 7)
    }
  }

  it should "halt with cause BadAddress on a fetch, load or store that no memory or device answers" in {
    simulate(new Top) { c =>
      val fetch = expectHalt(c, "fetch", "li t0, 0x40000000; jr t0", Halt.BadAddress)
      assert(fetch.last.pc == 0x40000000L && fetch.last.inst == 0, "the faulting fetch retires with the word 0")
      val devices = expectHalt(c, "fetch from a device", f"li t0, 0x${MemMap.uartBase}%x; jr t0", Halt.BadAddress)
      assert(devices.last.pc == MemMap.uartBase, "devices are on the data side only")
      expectHalt(c, "fetch past the RAM", f"li t0, 0x${MemMap.ramBase + MemMap.ramBytes}%x; jr t0", Halt.BadAddress)
      expectHalt(c, "load", "li t0, 0x40000000; lw a0, 0(t0)", Halt.BadAddress)
      expectHalt(c, "store", "li t0, 0x40000000; sb a0, 3(t0)", Halt.BadAddress)
    }
  }

  it should "do nothing while reset is held, whatever the memory holds at the reset vector" in {
    simulate(new Top) { c =>
      // a store to SimCtrl EXIT as the first instructions: with reset held, it must not happen
      val image = T1.asm(f"li t0, 0x${MemMap.simCtrlBase}%x; li t1, 9; sw t1, ${MemMap.SimCtrl.Exit}(t0); 1: j 1b")
      CoSim2.load(c, image)
      c.reset.poke(true.B)
      for (_ <- 0 until 10) {
        c.io.retire.valid.expect(false.B, "nothing retires in reset")
        c.io.exit.valid.expect(false.B, "no device sees a request in reset")
        c.io.halted.expect(false.B)
        c.clock.step()
      }
      c.reset.poke(false.B)
      val result = CoSim2.run(c, 20)
      assert(result.exit.contains(9) && result.trace.size == 3 && result.trace.head.pc == MemMap.ramBase)
    }
  }
}
