// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.singlecycle.{ObsCheck, ObsRef, T1}
import org.scalatest.flatspec.AnyFlatSpec

/** The T2 system with the reference signals of `ObsRef` and of the divider as an output of its
  * own. The divider's are read from the execute stage's side where it has them: the request's
  * operands are the execute stage's register inputs, its result the execute stage's `result`
  * input of write-back when the instruction is a division.
  */
class T2ObsTop extends Top(uartDivider = 4) {
  import mycpu.singlecycle.Probes.tap
  val ref = IO(Output(new Bundle {
    val core = new ObsCore
    val div  = new ObsDiv
    val uart = new ObsUart
  }))
  ref.core := ObsRef.core(
    cpu.fetch,
    cpu.d,
    cpu.execute.base.alu,
    cpu.execute.io.rs1_data,
    cpu.execute.io.rs2_data,
    cpu.execute.io.redirect.valid,
    cpu.memory,
    cpu.write_back
  )
  val divider = cpu.execute.divider
  ref.div.reqValid  := tap(divider.io.req.valid)
  ref.div.reqReady  := tap(divider.io.req.ready)
  ref.div.op        := tap(cpu.execute.io.m.div_op).asUInt
  ref.div.dividend  := tap(cpu.execute.io.rs1_data)
  ref.div.divisor   := tap(cpu.execute.io.rs2_data)
  ref.div.busy      := tap(divider.io.busy)
  ref.div.respValid := tap(divider.io.resp.valid)
  ref.div.result    := tap(divider.io.resp.bits)
  ref.div.kill      := tap(cpu.execute.io.kill)
  ref.uart          := ObsRef.uart(uart, io.uartTxd, io.uartRxd)
}

/** OBS-02 on T2: every probe of `core`, `div` and `uart` equals the signal it names, in every
  * cycle of three directed programs (add, lw and beq; a division and the store of its result; a
  * UART write), and the probes agree with the retire record of their cycle.
  */
class T2ObsSpec extends AnyFlatSpec with ChiselSim {
  private def probes(c: T2ObsTop) =
    Seq("core" -> c.io.obs.core.get, "div" -> c.io.obs.div.get, "uart" -> c.io.obs.uart.get)
  private def refs(c: T2ObsTop) = Seq("core" -> c.ref.core, "div" -> c.ref.div, "uart" -> c.ref.uart)

  "The probes of T2" should "show add, lw and beq as the datapath has them" in {
    simulate(new T2ObsTop) { c =>
      ObsCheck.checkAddLwBeq(ObsCheck.run(c, T1.asm(ObsCheck.addLwBeq), probes(c), refs(c)))
    }
  }

  it should "show a division, its stall and the store of its result" in {
    simulate(new T2ObsTop) { c =>
      val cycles = ObsCheck.run(
        c,
        T1.asm("""
          li    t0, 1000
          li    t1, -7
          la    t3, buf
          div   t2, t0, t1
          sw    t2, 0(t3)
          ecall
          .data
        buf:
          .word 0
        """),
        probes(c),
        refs(c)
      )
      val issued = cycles.indexWhere(_("div.reqValid") == 1)
      val done   = cycles.indexWhere(_("div.respValid") == 1)
      assert(issued >= 0 && done > issued, "no division")
      assert(cycles(issued)("div.reqReady") == 1 && cycles(issued)("div.op") == 0)
      assert(cycles(issued)("div.dividend") == 1000 && cycles(issued)("div.divisor") == BigInt(0xfffffff9L))
      // the stall: the divider is busy, nothing retires and the PC holds, up to the response
      val stall = cycles.slice(issued + 1, done)
      assert(stall.size == Divider.DIV_LATENCY - 1, s"${stall.size} cycles between request and response")
      assert(stall.forall(s => s("div.busy") == 1 && s.retire.isEmpty && s("core.pc") == cycles(issued)("core.pc")))
      val quotient = BigInt(-142 & 0xffffffffL)
      assert(cycles(done)("div.result") == quotient && cycles(done)("core.wbValue") == quotient)
      assert(cycles(done).retire.exists(_.rdValue == quotient))
      val store = cycles(done + 1)
      assert(store("core.store") == 1 && store("core.memReqWdata") == quotient, "the store of the quotient")
      assert(cycles.forall(_("div.kill") == 0))
      assert(ObsCheck.checkRetire(cycles) == cycles.count(_.retire.isDefined))
    }
  }

  it should "show a UART write, from the store to the line" in {
    simulate(new T2ObsTop) { c =>
      ObsCheck.checkUartWrite(ObsCheck.run(c, T1.asm(ObsCheck.uartWrite), probes(c), refs(c), drain = 20))
    }
  }
}
