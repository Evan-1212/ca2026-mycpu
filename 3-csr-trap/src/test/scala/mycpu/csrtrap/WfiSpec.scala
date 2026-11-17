// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** WFI on the T3 core (Privileged spec §3.3.3): it does not wait if an enabled interrupt is
  * pending; otherwise it stalls, retiring nothing, until one is, and then retires. An interrupt
  * that is due when the WFI is reached is taken before it (`mepc` = the WFI), one that arrives
  * during the wait after it (`mepc` = its pc + 4); with `mstatus.MIE` clear execution continues
  * at pc + 4. Every program runs against the golden model, which checks that a WFI that waited
  * retires only when something pending and enabled woke it.
  */
class WfiSpec extends AnyFlatSpec with ChiselSim {
  import T3.{exitAddr, msipAddr, mtimeAddr, mtimecmpAddr}

  /** `body` with a handler that silences only the source of its interrupt (the CLINT's `msip`
    * or its timer), counting in `s4`; `delay` cycles after the start the timer is due. The
    * program exits with 0.
    */
  private def program(body: String, delay: Int): (MemImage, Map[String, Long]) = {
    val p = AsmX.assemble(f"""
        la    t0, handler
        csrw  mtvec, t0
        li    s4, 0
${T3.armTimer(delay)}
$body
        li    t0, 0x$exitAddr%x
        sw    zero, 0(t0)
    1:  j     1b
        .balign 4
    handler:
        addi  s4, s4, 1
        csrr  s1, mcause
        csrr  s2, mepc
        andi  t0, s1, 0x1f
        li    t1, 3
        bne   t0, t1, 2f
        li    t0, 0x$msipAddr%x
        sw    zero, 0(t0)
        mret
    2:  li    t0, 0x$mtimecmpAddr%x
        li    t1, -1
        sw    t1, 0(t0)
        sw    t1, 4(t0)
        mret
    """)
    (MemImage(p), p.symbols)
  }

  /** The cycles without a record directly before the record of the instruction at `pc`. */
  private def waited(cycles: Seq[CoSimCycle], pc: Long): Int = {
    val at = cycles.indexWhere(_.retire.exists(r => r.pc == pc && r.trap.isEmpty))
    assert(at >= 0, f"no record of 0x$pc%08x")
    cycles.take(at).reverse.takeWhile(_.retire.isEmpty).size
  }

  private def run(c: Top, image: MemImage): (Seq[CoSimCycle], CoSimResult) = {
    val (cycles, result, _) = runProbed(c, image, probe = false)
    (cycles, result)
  }

  /** The cycles of `image` on `c`, compared with the golden model, and the `csr.wfi` probe of
    * every cycle (if `probe`; else empty).
    */
  private def runProbed(
      c: Top,
      image: MemImage,
      probe: Boolean = true
  ): (Seq[CoSimCycle], CoSimResult, Seq[Boolean]) = {
    val wfi    = mutable.ArrayBuffer[Boolean]()
    val cycles = T3.capture(c, image) { if (probe) wfi += c.io.obs.csr.get.wfi.peek().litToBoolean; () }
    val result = CoSim2.replay(cycles.iterator, image, T3.defaultModel)
    result.mismatch.foreach(m => fail(m))
    assert(result.exit.contains(0))
    (cycles, result, wfi.toVector)
  }

  "WFI" should "wait for an enabled interrupt with MIE = 0, then continue at pc + 4" in {
    simulate(new Top) { c =>
      val (image, s) = program(
        """
        li    t0, 0x80
        csrw  mie, t0
    w:  wfi
    next:
        csrr  a1, mip
        """,
        delay = 200
      )
      val (cycles, result, wfiProbe) = runProbed(c, image)
      val wait                       = waited(cycles, s("w"))
      assert(wait > 150 && wait < 210, s"the WFI waited $wait cycles for a timer 200 cycles away")
      assert(T3.traps(result).isEmpty && T3.reg(result.trace, "a1") == 0x80)
      val wfi = result.trace.find(_.pc == s("w")).get
      assert(wfi.nextPc == s("next"))
      // the csr.wfi probe, a register: from the second cycle of the wait through the WFI's record
      val retired = cycles.indexWhere(_.retire.exists(_.pc == s("w")))
      val high    = wfiProbe.indices.filter(wfiProbe(_))
      assert(high == (retired - wait + 1 to retired), s"csr.wfi high in cycles $high, the WFI's record in $retired")
      info(s"the WFI waited $wait cycles")
    }
  }

  // with MIE set, the pending interrupt is taken before the WFI instead: the next test
  it should "not wait when an enabled interrupt is pending and MIE is clear, but retire at once" in {
    simulate(new Top) { c =>
      val (image, s) = program(
        f"""
        li    t0, 0x$msipAddr%x
        li    t1, 1
        sw    t1, 0(t0)
        li    t0, 0x8
        csrw  mie, t0
    w:  wfi
        """,
        delay = 1000000
      )
      val (cycles, result) = run(c, image)
      assert(waited(cycles, s("w")) == 0 && T3.traps(result).isEmpty)
    }
  }

  it should "let an interrupt due when it is reached be taken before it, and one that wakes it after it" in {
    simulate(new Top) { c =>
      // MSIP is due when the WFI is reached; the handler silences it, the WFI then waits for the timer
      val (image, s) = program(
        f"""
        li    t0, 0x$msipAddr%x
        li    t1, 1
        sw    t1, 0(t0)
        li    t0, 0x88
        csrw  mie, t0
        csrsi mstatus, 8
    w:  wfi
    after:
        nop
        csrci mstatus, 8
        """,
        delay = 300
      )
      val (cycles, result) = run(c, image)
      val taken            = T3.traps(result)
      assert(taken.map(_.trap.get.code) == Seq(Trap.Msi, Trap.Mti), taken.mkString("\n"))
      assert(taken(0).pc == s("w"), "the interrupt due at the WFI is taken before it: mepc = the WFI")
      assert(taken(1).pc == s("after"), "the interrupt that wakes the WFI is taken after it: mepc = pc + 4")
      val wait = waited(cycles, s("w"))
      assert(wait > 200, s"after the first interrupt the WFI waited for the timer ($wait cycles)")
      // the WFI retires in the cycle it wakes, and the interrupt comes in the next one
      val wake = cycles.indexWhere(_.retire.exists(r => r.pc == s("w") && r.trap.isEmpty))
      assert(cycles(wake + 1).retire.exists(_.trap.exists(_.interrupt)))
      assert(cycles(wake).mip == 0, "the WFI's record shows mip from before its wait")
      assert(cycles(wake + 1).mip == 0x80, "the interrupt's record shows what woke the WFI")
    }
  }

  it should "count its wait as event 8 (other), and retire once" in {
    simulate(new Top) { c =>
      val (image, s) = program(
        T3.startCounters +
          """
        li    t0, 0x80
        csrw  mie, t0
    w:  wfi
        """ + T3.readCounters,
        delay = 400
      )
      val (cycles, result) = run(c, image)
      val events           = T3.counters(result.trace)
      val wait             = waited(cycles, s("w"))
      assert(events(HpmEvents.Other) == wait, s"$events, waited $wait")
      assert(events(0) - events(-1) == T3.partition(events), s"mcycle - minstret = the partition: $events")
      assert(result.trace.count(_.pc == s("w")) == 1)
    }
  }
}
