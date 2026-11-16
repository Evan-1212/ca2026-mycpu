// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common.AsmX
import org.scalatest.flatspec.AnyFlatSpec
import riscv.HaltCause

/** The core on its own two ports, with the test as the memory (T1-01, T1-07): what it requests
  * in each cycle. A device acts on every request it answers (SimCtrl ends the simulation, the
  * UART pops its receive FIFO or clears its overrun flag), so an instruction that halts before
  * its access, or a core in reset, must put no request on the data port, not even for one cycle.
  * The system-level specs cannot see that: they look at the core after it has stopped.
  */
class T1CpuSpec extends AnyFlatSpec with ChiselSim {
  private def word(source: String): Long = AsmX(source).head

  /** Gives the core `inst` as the word at its PC, and an answer (or none) on the data port. */
  private def present(c: CPU, inst: Long, fetchError: Boolean = false, answered: Boolean = true): Unit = {
    c.io.imem.req.ready.poke(true.B)
    c.io.imem.resp.valid.poke(true.B)
    c.io.imem.resp.bits.rdata.poke(inst.U(32.W))
    c.io.imem.resp.bits.error.poke(fetchError.B)
    c.io.dmem.req.ready.poke(answered.B)
    c.io.dmem.resp.valid.poke(answered.B)
    c.io.dmem.resp.bits.rdata.poke("hcafef00d".U)
    c.io.dmem.resp.bits.error.poke(false.B)
  }

  private def restart(c: CPU): Unit = {
    c.reset.poke(true.B)
    c.clock.step()
    c.reset.poke(false.B)
  }

  // register x0 is the only one with a known value, which is enough: the addresses are the offsets
  private val store = word("sw x0, 8(x0)")

  "The T1 core" should "request an aligned load or store on the data port, in the cycle it retires" in {
    simulate(new CPU) { c =>
      present(c, store)
      c.io.imem.req.valid.expect(true.B)
      c.io.dmem.req.valid.expect(true.B)
      c.io.dmem.req.bits.addr.expect(8.U)
      c.io.dmem.req.bits.wstrb.expect(15.U)
      c.io.retire.valid.expect(true.B)
      c.io.retire.bits.halt.expect(HaltCause.Running)
      c.io.retire.bits.mem.valid.expect(true.B)
      restart(c)
      present(c, word("lbu x1, 3(x0)"))
      c.io.dmem.req.valid.expect(true.B)
      c.io.dmem.req.bits.addr.expect(0.U)
      c.io.dmem.req.bits.wstrb.expect(0.U)
      c.io.retire.bits.rdValue.expect("hca".U)
    }
  }

  it should "put no request on the data port for an instruction that halts before its access" in {
    simulate(new CPU) { c =>
      val misaligned =
        Seq("sw x0, 1(x0)", "sw x0, 2(x0)", "sh x0, 3(x0)", "lw x1, 2(x0)", "lh x1, 1(x0)", "lhu x1, 3(x0)")
      // what halts, with its cause: a misaligned access, and a store that was not fetched properly
      val cases = misaligned.map(s => (s, word(s), false, HaltCause.Misaligned)) :+
        (("a store behind a fetch fault", store, true, HaltCause.BadAddress))
      for ((what, inst, fetchError, cause) <- cases) {
        restart(c)
        present(c, inst, fetchError)
        c.io.dmem.req.valid.expect(false.B, s"$what: no request in the cycle of the halt")
        c.io.retire.valid.expect(true.B, what)
        c.io.retire.bits.halt.expect(cause, what)
        c.io.retire.bits.mem.valid.expect(false.B, s"$what: the record shows no access")
        c.io.retire.bits.nextPc.expect(c.io.retire.bits.pc.peek(), s"$what: execution does not continue")
        c.io.halted.expect(false.B, s"$what: halted rises after the record")
        for (_ <- 0 until 3) {
          c.clock.step()
          c.io.halted.expect(true.B, what)
          c.io.dmem.req.valid.expect(false.B, s"$what: nor afterwards")
          c.io.imem.req.valid.expect(false.B, s"$what: a halted core fetches nothing")
          c.io.retire.valid.expect(false.B, what)
        }
      }
    }
  }

  it should "request nothing and retire nothing while reset is held, with a store at the reset vector" in {
    simulate(new CPU) { c =>
      present(c, store)
      c.reset.poke(true.B)
      for (_ <- 0 until 6) {
        c.io.imem.req.valid.expect(false.B, "no fetch in reset: a testbench loads the program now")
        c.io.dmem.req.valid.expect(false.B, "the store at the reset vector is not sent in reset")
        c.io.retire.valid.expect(false.B, "nothing retires in reset")
        c.io.halted.expect(false.B)
        c.clock.step()
      }
      c.reset.poke(false.B)
      c.io.dmem.req.valid.expect(true.B, "the same store is sent in the first cycle after reset")
      c.io.retire.valid.expect(true.B)
      c.io.retire.bits.pc.expect("h80000000".U)
    }
  }

  it should "hold a request that is not answered, with the PC, and retire it once" in {
    simulate(new CPU) { c =>
      present(c, store, answered = false)
      for (_ <- 0 until 5) {
        c.io.dmem.req.valid.expect(true.B, "the request stays on the port")
        c.io.dmem.req.bits.addr.expect(8.U)
        c.io.dmem.req.bits.wstrb.expect(15.U)
        c.io.imem.req.bits.addr.expect("h80000000".U, "the PC waits")
        c.io.retire.valid.expect(false.B, "an instruction that waits has not retired")
        c.clock.step()
      }
      c.io.dmem.resp.valid.poke(true.B)
      c.io.dmem.req.ready.poke(true.B)
      c.io.retire.valid.expect(true.B)
      c.io.retire.bits.pc.expect("h80000000".U)
      c.clock.step()
      c.io.imem.req.bits.addr.expect("h80000004".U, "the PC moves when the answer comes")
      c.io.retire.bits.pc.expect("h80000004".U, "and the next instruction is a new one")
    }
  }
}
