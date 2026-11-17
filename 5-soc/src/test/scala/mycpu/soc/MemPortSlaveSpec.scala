// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common.MemMap
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable
import scala.util.Random

/** T5-04: [[MemPortSlave]] around `SimCtrl`, around the UART and around a slow test device,
  * driven on its AXI port by a [[RawMaster]]. The point of every test: one AXI transaction is one
  * request taken by the device, whatever the timing on either side.
  */
class MemPortSlaveSpec extends AnyFlatSpec with ChiselSim {
  import Axi4Lite.Resp._

  private def takes(c: ShellBench): Int = c.takes.peek().litValue.toInt

  /** Steps the clock of a shell bench and watches what the shell shows its device: a request that
    * was not taken must be there again in the next cycle, unchanged (the contract of `MemPort`,
    * and what keeps a device's side effects single). `taken` lists the requests the device took,
    * as (address, data, strobes).
    */
  class DeviceWatch(c: ShellBench) {
    val taken = mutable.ArrayBuffer[(Long, Long, Int)]()

    /** The request that was shown in the last cycle and not taken. */
    var held = Option.empty[(Long, Long, Int)]

    def step(): Unit = {
      val valid = c.request.valid.peek().litToBoolean
      val bits  = c.request.bits
      val now = (bits.addr.peek().litValue.toLong, bits.wdata.peek().litValue.toLong, bits.wstrb.peek().litValue.toInt)
      for (before <- held)
        assert(
          valid && now == before,
          s"the request $before was not taken, and the next cycle shows ${if (valid) now else "none"}"
        )
      val ready = c.request.ready.peek().litToBoolean
      held = if (valid && !ready) Some(now) else None
      if (valid && ready) taken += now
      c.clock.step()
    }
  }

  "MemPortSlave with SimCtrl" should "pulse PUTC and EXIT exactly once per write, whatever the AXI timing" in {
    import MemMap.SimCtrl._
    val base = MemMap.simCtrlBase
    simulate(new SimCtrlBench) { c =>
      val watch = new DeviceWatch(c)
      val putc  = mutable.ArrayBuffer[Int]() // the byte of every cycle with putc.valid
      val exit  = mutable.ArrayBuffer[Int]()
      val m     = new RawMaster(
        c.axi,
        () => {
          if (c.io.putc.valid.peek().litToBoolean) putc += c.io.putc.bits.peek().litValue.toInt
          if (c.io.exit.valid.peek().litToBoolean) exit += c.io.exit.bits.peek().litValue.toInt
          watch.step()
        }
      )
      m.idle()
      val timings =
        for (awAt <- Seq(0, 1, 4); wAt <- Seq(0, 1, 4); readyAfter <- Seq(0, 3, 9)) yield (awAt, wAt, readyAfter)
      for (((awAt, wAt, readyAfter), n) <- timings.zipWithIndex) {
        val result = m.write(base + Putc, 0x40 + n, awAt = awAt, wAt = wAt, readyAfter = readyAfter)
        assert(result == WriteResult(Okay, latency = 2), s"AW at $awAt, W at $wAt")
      }
      assert(putc == timings.indices.map(0x40 + _), "one PUTC per write, in order")
      assert(takes(c) == timings.size)
      assert(exit.isEmpty)
      assert(m.write(base + Exit, 0x1207, readyAfter = 6).resp == Okay)
      m.tick(5)
      assert(exit == Seq(0x07) && putc.size == timings.size, "one EXIT, with bits [7:0]")
    }
  }

  it should "read and write the device's registers, two cycles after the request" in {
    import MemMap.SimCtrl._
    val base = MemMap.simCtrlBase
    simulate(new SimCtrlBench) { c =>
      val watch = new DeviceWatch(c)
      val m     = new RawMaster(c.axi, () => watch.step())
      m.idle()
      assert(m.read(base + Meip) == ReadResult(0, Okay, arWait = 0, latency = 2))
      assert(m.write(base + Meip, 1).resp == Okay)
      c.io.meip.expect(true.B)
      assert(m.read(base + Meip, readyAfter = 4).data == 1)
      val before = m.read(base + CycleLo).data
      m.tick(10)
      val after = m.read(base + CycleLo).data
      assert(after - before == 10 + 3, "CYCLE counts every clock cycle: 10 idle, 3 per read")
      assert(m.read(base + InstretLo).data == 0)
      assert(takes(c) == 6)
    }
  }

  it should "answer a write without strobes itself, pass on partial strobes, and put a write before a read" in {
    import MemMap.SimCtrl._
    val base = MemMap.simCtrlBase
    simulate(new SimCtrlBench) { c =>
      val watch = new DeviceWatch(c)
      val m     = new RawMaster(c.axi, () => watch.step())
      m.idle()
      // WSTRB 0 would be a read to the device
      assert(m.write(base + Meip, 1, strb = 0) == WriteResult(Okay, 2))
      assert(takes(c) == 0, "the device saw nothing")
      // a partial strobe goes to the device, which ignores such a write (D12)
      assert(m.write(base + Meip, 1, strb = 0x1).resp == Okay)
      assert(takes(c) == 1 && m.read(base + Meip).data == 0)
      // a read and a write that arrive in the same cycle: the write goes first
      c.axi.ar.valid.poke(true.B)
      c.axi.ar.bits.addr.poke((base + Meip).U)
      c.axi.aw.valid.poke(true.B)
      c.axi.aw.bits.addr.poke((base + Meip).U)
      c.axi.w.valid.poke(true.B)
      c.axi.w.bits.data.poke(1.U)
      c.axi.w.bits.strb.poke(0xf.U)
      c.axi.r.ready.poke(true.B)
      c.axi.b.ready.poke(true.B)
      for (ready <- Seq(c.axi.ar.ready, c.axi.aw.ready, c.axi.w.ready)) ready.expect(true.B)
      watch.step()
      m.idle()
      c.axi.r.ready.poke(true.B)
      c.axi.b.ready.poke(true.B)
      watch.step() // the device takes the write
      c.axi.b.valid.expect(true.B)
      c.axi.r.valid.expect(false.B)
      watch.step() // and then the read
      c.axi.r.valid.expect(true.B)
      c.axi.r.bits.data.expect(1.U, "the read sees the write")
      watch.step()
      c.axi.r.valid.expect(false.B)
      assert(takes(c) == 4)
    }
  }

  it should "show the device nothing while reset is asserted, not even a request it had buffered" in {
    import MemMap.SimCtrl._
    val base = MemMap.simCtrlBase
    simulate(new SimCtrlBench) { c =>
      val watch = new DeviceWatch(c)
      val m     = new RawMaster(c.axi, () => watch.step())
      m.idle()
      // a PUTC write is buffered in this cycle and would be shown to SimCtrl in the next one
      c.axi.aw.valid.poke(true.B)
      c.axi.aw.bits.addr.poke((base + Putc).U)
      c.axi.w.valid.poke(true.B)
      c.axi.w.bits.data.poke('x'.U)
      c.axi.w.bits.strb.poke(0xf.U)
      c.axi.aw.ready.expect(true.B)
      c.axi.w.ready.expect(true.B)
      watch.step()
      m.idle()
      c.reset.poke(true.B) // and reset comes first
      c.io.putc.valid.expect(false.B, "no PUTC during reset")
      c.request.valid.expect(false.B, "no request during reset")
      c.clock.step()
      c.reset.poke(false.B)
      for (_ <- 0 until 6) {
        c.io.putc.valid.expect(false.B, "the write was forgotten")
        c.axi.b.valid.expect(false.B, "and is not answered")
        c.clock.step()
      }
      assert(takes(c) == 0)
    }
  }

  "MemPortSlave with the UART" should "delay a TXDATA write while the FIFO is full and send every byte once" in {
    import MemMap.Uart._
    val base    = MemMap.uartBase
    val divider = 4
    simulate(new UartBench(divider)) { c =>
      val watch = new DeviceWatch(c)
      val line  = new UartLine(c.io.txd, divider)
      val m     = new RawMaster(c.axi, () => { line.sample(0); watch.step() })
      m.idle()
      val text = "AXI4-Lite: held".map(_.toInt)
      // the transmitter takes the first byte and the FIFO eight more: nine writes are not delayed,
      // and the tenth is not either when a frame has ended meanwhile
      val latencies = text.zipWithIndex.map { case (byte, n) =>
        m.write(base + TxData, byte, awAt = n % 2, wAt = n % 3, readyAfter = n % 4).latency
      }
      assert(latencies.take(9).forall(_ == 2), s"the first nine: $latencies")
      // the last writes each wait for a frame to end: the FIFO stays full
      assert(latencies.forall(_ <= 10 * divider + 2) && latencies.takeRight(4).forall(_ > 2 * divider), s"$latencies")
      assert(takes(c) == text.size, "the device took each write once")
      assert(m.read(base + Status).data == 0, "tx_ready is low: the FIFO is full")
      m.tick(10 * divider * 11)
      assert(line.bytes == text, "every byte once, in order")
      assert(m.read(base + Status).data == 1, "tx_ready")
      assert(takes(c) == text.size + 2)
    }
  }

  it should "show the held write to the device in every cycle until it is taken, and nothing after" in {
    import MemMap.Uart._
    val base    = MemMap.uartBase
    val divider = 16
    simulate(new UartBench(divider)) { c =>
      val watch = new DeviceWatch(c)
      val m     = new RawMaster(c.axi, () => watch.step())
      m.idle()
      for (n <- 0 until 9) m.write(base + TxData, 0x30 + n)
      val before = takes(c)
      // the tenth write: AW and W are taken, B does not come
      c.axi.aw.valid.poke(true.B)
      c.axi.aw.bits.addr.poke((base + TxData).U)
      c.axi.w.valid.poke(true.B)
      c.axi.w.bits.data.poke(0x39.U)
      c.axi.w.bits.strb.poke(0xf.U)
      watch.step()
      m.idle()
      var waited = 0
      while (!c.axi.b.valid.peek().litToBoolean) {
        assert(waited < 10 * divider + 4, "B never came")
        if (waited > 1) {
          assert(takes(c) == before, "not taken while the FIFO is full")
          assert(watch.held.contains((base + TxData, 0x39L, 0xf)), "the write is shown to the device in every cycle")
        }
        // meanwhile the shell takes one more request into its buffers, and no more than that
        c.axi.ar.valid.poke(true.B)
        c.axi.ar.bits.addr.poke((base + Status).U)
        if (waited > 0) c.axi.ar.ready.expect(false.B, "one read is buffered already")
        watch.step()
        waited += 1
      }
      assert(waited > 2 * divider, s"held for $waited cycles")
      assert(takes(c) == before + 1, "taken once")
      c.axi.b.ready.poke(true.B)
      c.axi.r.ready.poke(true.B)
      watch.step()
      c.axi.b.valid.expect(false.B)
      c.axi.r.valid.expect(true.B, "the read that waited behind the write")
      watch.step()
      m.idle()
      assert(takes(c) == before + 2)
    }
  }

  "MemPortSlave with a slow device" should "wait for the device to take a request and to answer it" in {
    for ((period, delay) <- Seq((1, 0), (1, 1), (3, 0), (3, 2), (5, 7))) simulate(new SlowDeviceBench(period, delay)) {
      c =>
        val watch = new DeviceWatch(c)
        val m     = new RawMaster(c.axi, () => watch.step())
        m.idle()
        val rng    = new Random(period * 31 + delay)
        var writes = 0
        for (n <- 0 until 40) {
          val addr  = (rng.nextInt(1 << 12) & ~0x103L) | (if (n % 5 == 0) 0x100 else 0)
          val resp  = if ((addr & 0x100) != 0) SlvErr else Okay
          val after = rng.nextInt(4)
          if (rng.nextBoolean()) {
            val read = m.read(addr, readyAfter = after)
            assert(read.data == (~addr & 0xffffffffL) && read.resp == resp, f"period $period, delay $delay, 0x$addr%x")
            assert(read.latency >= 2 + delay && read.latency < 2 + delay + period)
          } else {
            val write = m.write(addr, n, awAt = rng.nextInt(3), wAt = rng.nextInt(3), readyAfter = after)
            assert(write.resp == resp && write.latency >= 2 + delay && write.latency < 2 + delay + period)
            writes += 1
          }
          assert(takes(c) == n + 1, "one request per transaction")
        }
        c.io.writes.expect(writes.U)
    }
  }
  it should "answer a write without strobes after the write before it, which the device has not answered yet" in {
    // two writes in flight at once: without the checker, whose bound is that of the SoC's arbiter
    simulate(new SlowDeviceBench(period = 1, delay = 6, checked = false)) { c =>
      val watch = new DeviceWatch(c)
      val m     = new RawMaster(c.axi, () => watch.step())
      m.idle()
      c.axi.b.ready.poke(true.B)
      // write 1 fails in the device (address bit 8), six cycles after the device took it; write 2
      // has no strobes and needs no device. Both are sent as fast as the shell takes them.
      val writes    = Seq((0x100L, 0xf), (0x200L, 0x0))
      var sent      = 0
      var awDone    = false
      var wDone     = false
      val responses = scala.collection.mutable.ArrayBuffer[Int]()
      for (_ <- 0 until 30) {
        val (addr, strb) = writes(math.min(sent, 1))
        c.axi.aw.valid.poke((sent < 2 && !awDone).B)
        c.axi.aw.bits.addr.poke(addr.U)
        c.axi.w.valid.poke((sent < 2 && !wDone).B)
        c.axi.w.bits.data.poke(0x55.U)
        c.axi.w.bits.strb.poke(strb.U)
        if (sent < 2) {
          awDone ||= c.axi.aw.ready.peek().litToBoolean
          wDone ||= c.axi.w.ready.peek().litToBoolean
        }
        if (c.axi.b.valid.peek().litToBoolean) responses += c.axi.b.bits.resp.peek().litValue.toInt
        watch.step()
        if (awDone && wDone) { sent += 1; awDone = false; wDone = false }
      }
      assert(responses == Seq(SlvErr, Okay), "the responses in the order of the writes")
      c.io.writes.expect(1.U, "the device saw the first write only")
    }
  }
  it should "keep a read it has shown the device when a write arrives before the device takes it" in {
    simulate(new SlowDeviceBench(period = 8, delay = 1)) { c =>
      for (start <- 0 until 8) {
        c.reset.poke(true.B) // a fresh start for each phase of the device
        c.clock.step()
        c.reset.poke(false.B)
        val watch = new DeviceWatch(c)
        val m     = new RawMaster(c.axi, () => watch.step())
        m.idle()
        m.tick(start) // every phase of the device's period
        c.axi.r.ready.poke(true.B)
        c.axi.b.ready.poke(true.B)
        c.axi.ar.valid.poke(true.B)
        c.axi.ar.bits.addr.poke(0x40.U)
        watch.step()
        c.axi.ar.valid.poke(false.B)
        watch.step() // the read is shown to the device from this cycle on
        c.axi.aw.valid.poke(true.B)
        c.axi.aw.bits.addr.poke(0x80.U)
        c.axi.w.valid.poke(true.B)
        c.axi.w.bits.data.poke(0x77.U)
        c.axi.w.bits.strb.poke(0xf.U)
        watch.step()
        m.idle()
        c.axi.r.ready.poke(true.B)
        c.axi.b.ready.poke(true.B)
        for (_ <- 0 until 30) watch.step() // DeviceWatch fails if the read is replaced by the write
        assert(
          watch.taken.map(t => (t._1, t._3)) == Seq((0x40L, 0), (0x80L, 0xf)),
          s"start $start: the read, then the write"
        )
      }
    }
  }
}
