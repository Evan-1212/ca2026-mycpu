// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec
import riscv.{Halt, MemAccess}

/** The data-side address decode of T1 (T1-03, D12): `DataBus` on its own, with the test standing
  * in for the devices, and then the whole system running programs that touch every region.
  */
class DecodeMapSpec extends AnyFlatSpec with ChiselSim {
  private val ramBase = MemMap.ramBase
  private val ramEnd  = ramBase + MemMap.ramBytes
  private val regions = Seq(
    (ramBase, MemMap.ramBytes.toLong),
    (MemMap.uartBase, MemMap.uartSize),
    (MemMap.simCtrlBase, MemMap.simCtrlSize)
  )
  private val names = Seq("RAM", "UART", "SimCtrl")

  /** Every device answers at once with its own number in `rdata`. */
  private def answerAll(c: DataBus): Unit =
    for ((device, n) <- c.io.devices.zipWithIndex) {
      device.req.ready.poke(true.B)
      device.resp.valid.poke(true.B)
      device.resp.bits.rdata.poke((0xd0 + n).U)
      device.resp.bits.error.poke(false.B)
    }

  private def request(c: DataBus, addr: Long, strobe: Int = 0, data: Long = 0): Unit = {
    c.io.core.req.valid.poke(true.B)
    c.io.core.req.bits.addr.poke(addr.U)
    c.io.core.req.bits.wstrb.poke(strobe.U)
    c.io.core.req.bits.wdata.poke(data.U)
  }

  "DataBus" should "route a request to the one device whose window holds the address" in {
    simulate(new DataBus(regions)) { c =>
      answerAll(c)
      val probes = Seq(
        ramBase                                -> 0,
        (ramBase + 0x1234)                     -> 0,
        (ramEnd - 4)                           -> 0,
        MemMap.uartBase                        -> 1,
        (MemMap.uartBase + MemMap.Uart.Status) -> 1,
        (MemMap.uartBase + 0xffc)              -> 1,
        MemMap.simCtrlBase                     -> 2,
        (MemMap.simCtrlBase + 0xffc)           -> 2
      )
      for ((addr, target) <- probes) {
        request(c, addr, strobe = 0xf, data = 0xabcd0000L + target)
        for ((device, n) <- c.io.devices.zipWithIndex) {
          device.req.valid.expect((n == target).B, f"0x$addr%08x: ${names(n)} request")
          if (n == target) {
            device.req.bits.addr.expect(addr.U, "the device sees the whole address")
            device.req.bits.wstrb.expect(0xf.U)
            device.req.bits.wdata.expect((0xabcd0000L + target).U)
          }
        }
        c.io.core.resp.valid.expect(true.B)
        c.io.core.resp.bits.error.expect(false.B, f"0x$addr%08x is mapped")
        c.io.core.resp.bits.rdata.expect((0xd0 + target).U, f"0x$addr%08x: the answer of ${names(target)}")
      }
      c.io.core.req.valid.poke(false.B)
      for (device <- c.io.devices) device.req.valid.expect(false.B, "no request from the core, none to a device")
      for (device <- c.io.devices) device.resp.valid.poke(false.B) // a device answers only a request
      c.io.core.resp.valid.expect(false.B, "no request, no answer")
    }
  }

  it should "answer an unmapped address at once with an error, and show the request to no device" in {
    simulate(new DataBus(regions)) { c =>
      answerAll(c)
      val unmapped = Seq(
        0L,
        MemMap.bootRomBase, // T5 only
        MemMap.clintBase,   // T3 on
        MemMap.uartBase - 4,
        MemMap.simCtrlBase + MemMap.simCtrlSize,
        MemMap.vgaBase, // T5 only
        0x40000000L,
        ramBase - 4,
        ramEnd,
        0xfffffffcL
      )
      for (addr <- unmapped; strobe <- Seq(0, 0xf)) {
        request(c, addr, strobe)
        for ((device, n) <- c.io.devices.zipWithIndex)
          device.req.valid.expect(false.B, f"0x$addr%08x: ${names(n)} must not see it")
        c.io.core.resp.valid.expect(true.B, f"0x$addr%08x: answered")
        c.io.core.resp.bits.error.expect(true.B, f"0x$addr%08x: an error")
      }
      c.io.core.req.valid.poke(false.B)
      c.io.core.resp.valid.expect(false.B, "no request, no answer")
    }
  }

  it should "pass on a device that makes the core wait, and a device's own error" in {
    simulate(new DataBus(regions)) { c =>
      answerAll(c)
      request(c, MemMap.uartBase + MemMap.Uart.TxData, strobe = 0xf, data = 'x')
      c.io.devices(1).req.ready.poke(false.B)
      c.io.devices(1).resp.valid.poke(false.B) // the UART holds a write while its FIFO is full
      c.io.core.resp.valid.expect(false.B, "the other devices' answers do not count")
      c.io.core.req.ready.expect(false.B)
      c.io.devices(1).req.valid.expect(true.B)
      c.clock.step(2)
      c.io.core.resp.valid.expect(false.B)
      c.io.devices(1).req.ready.poke(true.B)
      c.io.devices(1).resp.valid.poke(true.B)
      c.io.core.resp.valid.expect(true.B)
      c.io.core.req.ready.expect(true.B)
      request(c, ramBase)
      c.io.devices(0).resp.bits.error.poke(true.B)
      c.io.core.resp.bits.error.expect(true.B, "a device's own error reaches the core")
      request(c, MemMap.simCtrlBase)
      c.io.core.resp.bits.error.expect(false.B, "and only that device's")
    }
    intercept[Exception](simulate(new DataBus(Seq((0x1000L, 0x1000L), (0x1800L, 0x1000L))))(_ => ()))
  }

  "The T1 system" should "reach the RAM, the UART and SimCtrl by address, as the golden model does" in {
    val program = f"""
      li    s0, 0x${ramEnd - 16}%x       # the last words of the RAM
      li    t0, 0x12345678
      sw    t0, 12(s0)
      sh    t0, 8(s0)
      sb    t0, 5(s0)
      lw    a1, 12(s0)
      lhu   a2, 8(s0)
      lb    a3, 5(s0)
      li    s1, 0x${MemMap.uartBase}%x
      lw    a4, ${MemMap.Uart.Status}(s1)     # tx_ready
      li    t1, 1
      sw    t1, ${MemMap.Uart.Ctrl}(s1)
      lw    a5, ${MemMap.Uart.Ctrl}(s1)
      li    t1, 85
      sw    t1, ${MemMap.Uart.TxData}(s1)
      li    s2, 0x${MemMap.simCtrlBase}%x
      li    t1, 79
      sw    t1, ${MemMap.SimCtrl.Putc}(s2)
      li    t1, 75
      sw    t1, ${MemMap.SimCtrl.Putc}(s2)
      lw    a6, ${MemMap.SimCtrl.InstretLo}(s2)
      lw    a7, ${MemMap.SimCtrl.CycleLo}(s2)
      sw    zero, ${MemMap.SimCtrl.Mark}(s2)
      li    t1, 42
      sw    t1, ${MemMap.SimCtrl.Exit}(s2)
    1:
      j     1b
    """
    simulate(new Top) { c =>
      CoSim2.load(c, T1.asm(program))
      // watch the SimCtrl events while the program runs
      val putc  = new StringBuilder
      var marks = 0
      var exit  = Option.empty[Int]
      val trace = scala.collection.mutable.ArrayBuffer[IssRetire]()
      var cycle = 0
      while (exit.isEmpty && cycle < 200) {
        trace ++= CoSim2.retired(c)
        if (c.io.putc.valid.peek().litToBoolean) putc += c.io.putc.bits.peek().litValue.toChar
        if (c.io.mark.peek().litToBoolean) marks += 1
        if (c.io.exit.valid.peek().litToBoolean) exit = Some(c.io.exit.bits.peek().litValue.toInt)
        c.clock.step()
        cycle += 1
      }
      assert(exit.contains(42) && putc.toString == "OK" && marks == 1)
      def reg(name: String) = T1.regAfter(trace.toSeq, name)
      assert(reg("a1") == 0x12345678L && reg("a2") == 0x5678L && reg("a3") == 0x78L, "the RAM's last words")
      assert((reg("a4") & 1) == 1 && reg("a5") == 1, "UART STATUS.tx_ready and CTRL")
      // SimCtrl counts what the core retires: INSTRET is read by instruction n (counted from 0) and
      // reads n; CYCLE reads the same one instruction (one cycle) later
      val instretRead =
        trace.indexWhere(_.mem.contains(MemAccess(MemMap.simCtrlBase + MemMap.SimCtrl.InstretLo, false, 4, reg("a6"))))
      assert(instretRead > 0 && reg("a6") == instretRead && reg("a7") == instretRead + 1, "INSTRET and CYCLE")

      // the same program, record by record against the golden model
      val result = CoSim2.check(c, T1.asm(program), T1.model)
      result.mismatch.foreach(fail(_))
      assert(result.exit.contains(42))
    }
  }

  it should "halt with BadAddress on a load or store between the regions, changing nothing" in {
    simulate(new Top) { c =>
      val holes =
        Seq(0x0L, MemMap.clintBase, MemMap.uartBase + MemMap.uartSize + MemMap.simCtrlSize, ramEnd, 0x7ffffffcL)
      for (addr <- holes; access <- Seq("lw a0, 0(t0)", "sw t1, 0(t0)", "lbu a0, 3(t0)", "sb t1, 3(t0)")) {
        val result =
          CoSim2.check(c, T1.asm(f"li t0, 0x$addr%x; li t1, 7; li a0, 5; $access; li a0, 9; ecall"), T1.model)
        result.mismatch.foreach(d => fail(f"$access at 0x$addr%08x: $d"))
        val last = result.trace.last
        assert(last.halt.contains(Halt.BadAddress), f"$access at 0x$addr%08x")
        assert(last.mem.isEmpty && last.rd == 0 && last.nextPc == last.pc, "a halt changes nothing")
        assert(T1.regAfter(result.trace, "a0") == 5)
      }
    }
  }
}
