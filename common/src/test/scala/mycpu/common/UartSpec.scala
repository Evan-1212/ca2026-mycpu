// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** The UART with its `txd` wired to its `rxd`. */
class UartLoopback(val divider: Int) extends Module {
  val io = IO(new Bundle {
    val port = Flipped(new riscv.MemPort)
    val txd  = Output(Bool())
    val irq  = Output(Bool())
  })
  val uart = Module(new Uart(divider))
  uart.io.port <> io.port
  uart.io.rxd := uart.io.txd
  io.txd      := uart.io.txd
  io.irq      := uart.io.irq
}

/** A `SimTop` with constant outputs, as a top written before `SimIO.uartRxReady` existed (it does
  * not mention the port), or one that drives the port low.
  */
class SimTopWithoutUart(driveRxReady: Boolean) extends SimTop {
  def uartDivider: Int = 1
  io.retire                        := 0.U.asTypeOf(io.retire)
  io.halted                        := false.B
  io.exit                          := 0.U.asTypeOf(io.exit)
  io.putc                          := 0.U.asTypeOf(io.putc)
  io.mark                          := false.B
  io.uartTxd                       := true.B
  if (driveRxReady) io.uartRxReady := false.B
}

class UartSpec extends AnyFlatSpec with ChiselSim {
  import MemMap.Uart._
  val base    = MemMap.uartBase
  val TxReady = 1
  val RxValid = 2
  val Overrun = 4

  /** The bus side of a UART under test, by its port and a way to advance the clock. */
  class Bus(port: riscv.MemPort, step: () => Unit) {
    var cycles = 0L

    /** Puts a request on the port until it is answered, returns its data and the cycles it waited,
      * and ends with the clock edge that takes it.
      */
    def request(offset: Long, wdata: Long = 0, wstrb: Int = 0, maxWait: Int = 1000): (BigInt, Int) = {
      port.req.valid.poke(true.B)
      port.req.bits.addr.poke((base + offset).U)
      port.req.bits.wdata.poke(wdata.U)
      port.req.bits.wstrb.poke(wstrb.U)
      var waited = 0
      while (!port.resp.valid.peek().litToBoolean) {
        port.req.ready.expect(false.B, "taken only with the answer")
        assert(waited < maxWait, f"offset 0x$offset%x not answered in $maxWait cycles")
        tick()
        waited += 1
      }
      port.req.ready.expect(true.B)
      port.resp.bits.error.expect(false.B)
      val data = port.resp.bits.rdata.peek().litValue
      tick()
      port.req.valid.poke(false.B)
      (data, waited)
    }
    def read(offset: Long): BigInt           = request(offset)._1
    def write(offset: Long, data: Long): Int = request(offset, data, 0xf)._2
    def tick(): Unit                         = { step(); cycles += 1 }
    def idle(n: Int): Unit                   = { port.req.valid.poke(false.B); for (_ <- 0 until n) tick() }
    def status: Int                          = read(Status).toInt
  }

  def loopback(c: UartLoopback): Bus = {
    c.io.port.req.valid.poke(false.B)
    new Bus(c.io.port, () => c.clock.step())
  }

  "Uart" should "receive what it sends, through a loopback, with any divider" in {
    for (divider <- Seq(1, 5, 16)) simulate(new UartLoopback(divider)) { c =>
      val bus  = loopback(c)
      val text = Seq(0x48, 0x69, 0x21, 0x00, 0xff, 0x55, 0xaa)
      c.io.txd.expect(true.B, "the line is idle high")
      assert(bus.status == TxReady, s"divider $divider: empty")
      for (b <- text) assert(bus.write(TxData, b.toLong | 0xabcd00L) == 0, "not full: answered at once")
      bus.idle(10 * divider * text.size + 4 * divider + 4)
      assert(bus.status == (TxReady | RxValid), s"divider $divider: bytes received, no overrun")
      val got = text.map(_ => bus.read(RxData).toInt)
      assert(got == text, s"divider $divider")
      assert(bus.read(RxData) == 0, "empty: reads 0")
      assert(bus.status == TxReady)
    }
  }

  it should "hold a write while the TX FIFO is full, and set the sticky overrun bit" in {
    val divider = 4
    simulate(new UartLoopback(divider)) { c =>
      val bus = loopback(c)
      // the transmitter takes the first byte, the FIFO the next 8
      for (b <- 0 until 9) {
        assert(bus.write(TxData, 0x30 + b) == 0, s"byte $b answered at once")
        if (b < 8) assert((bus.status & TxReady) != 0, s"byte $b: not full yet")
      }
      assert((bus.status & TxReady) == 0, "full")
      val start  = bus.cycles
      val waited = bus.write(TxData, 0x39) // the 10th byte waits for the first frame to end
      assert(waited > 0 && waited <= 10 * divider, s"held for $waited cycles")
      assert((bus.status & TxReady) == 0, "full again")
      bus.idle(10 * divider * 10 + 4 * divider)
      assert(bus.status == (TxReady | RxValid | Overrun), "8 bytes kept, 2 dropped")
      assert(bus.status == (TxReady | RxValid), "reading STATUS cleared the overrun bit")
      assert((0 until 8).map(_ => bus.read(RxData).toInt) == (0x30 until 0x38), "the first 8 bytes, in order")
      assert(bus.status == TxReady)
      assert(bus.cycles - start > 9 * 10 * divider)
    }
  }

  it should "raise the interrupt while enabled and a byte waits, and ignore partial writes" in {
    simulate(new UartLoopback(3)) { c =>
      val bus = loopback(c)
      c.io.irq.expect(false.B)
      assert(bus.read(Ctrl) == 0, "CTRL resets to 0")
      bus.write(Ctrl, 0xffffffffL)
      assert(bus.read(Ctrl) == 1, "only bit 0")
      c.io.irq.expect(false.B, "enabled, but nothing received")
      bus.write(TxData, 'x'.toLong)
      bus.idle(50)
      c.io.irq.expect(true.B, "a byte waits")
      bus.write(Ctrl, 0)
      c.io.irq.expect(false.B, "disabled")
      bus.write(Ctrl, 1)
      c.io.irq.expect(true.B)
      assert(bus.read(RxData) == 'x'.toInt)
      c.io.irq.expect(false.B, "the RX FIFO is empty again")

      for (strobe <- Seq(0x1, 0x3, 0x7, 0x8)) { // answered at once, no effect
        assert(bus.request(TxData, 'y'.toLong, strobe)._2 == 0)
        assert(bus.request(Ctrl, 0, strobe)._2 == 0)
      }
      assert(bus.read(Ctrl) == 1, "CTRL kept")
      for (_ <- 0 until 50) { bus.tick(); c.io.txd.expect(true.B, "nothing was sent") }
      assert(bus.status == TxReady)

      bus.write(Status, 0xffffffffL) // read-only registers ignore writes
      bus.write(RxData, 0xffffffffL)
      assert(bus.status == TxReady)
      for (offset <- Seq(TxData, 0x10, 0x100, 0xffc)) assert(bus.read(offset) == 0, f"offset 0x$offset%x")
    }
  }

  /** The bits of a frame, in the order sent. */
  def frame(byte: Int, stop: Int = 1): Seq[Int] = 0 +: (0 until 8).map(i => (byte >> i) & 1) :+ stop

  /** Sends `bytes` to `c.io.rxd` the way the harness's `+uart-stdin` does: a frame starts only
    * `gap` cycles or more after the previous one ended and, with `flowControl`, only while `rxReady`
    * is high. The returned bus advances the sender with the clock.
    */
  class Sender(c: Uart, divider: Int, bytes: Seq[Int], gap: Int, flowControl: Boolean) {
    private val pending = scala.collection.mutable.Queue.from(bytes)
    private var levels  = List.empty[Boolean] // the cycles left of the frame on the line
    private var idle    = 0                   // cycles before the next frame may start
    def sent: Int       = bytes.size - pending.size

    private def step(): Unit = {
      if (levels.isEmpty && idle > 0) idle -= 1
      else if (levels.isEmpty && pending.nonEmpty && (!flowControl || c.io.rxReady.peek().litToBoolean)) {
        levels = frame(pending.dequeue()).flatMap(bit => List.fill(divider)(bit == 1)).toList
      }
      c.io.rxd.poke(levels.headOption.getOrElse(true).B)
      if (levels.nonEmpty) {
        levels = levels.tail
        if (levels.isEmpty) idle = gap
      }
      c.clock.step()
    }
    c.io.port.req.valid.poke(false.B)
    c.io.rxd.poke(true.B)
    val bus = new Bus(c.io.port, () => step())
  }

  it should "show in rxReady every byte of a sender that waits two cycles after a frame" in {
    val text = (0x41 until 0x41 + 12).toSeq // more than the RX FIFO holds
    for (divider <- Seq(1, 2, 3, 4, 7)) simulate(new Uart(divider)) { c =>
      val sender = new Sender(c, divider, text, gap = 2, flowControl = true)
      val bus    = sender.bus
      c.io.rxReady.expect(true.B, "empty")
      bus.idle(text.size * 10 * divider + 20 * divider + 20) // long enough to send them all
      assert(sender.sent == 8, s"divider $divider: the sender stopped at a full FIFO")
      c.io.rxReady.expect(false.B, "full")
      val got     = scala.collection.mutable.ArrayBuffer[Int]()
      var overrun = false
      while (got.size < text.size) {
        val status = bus.status
        overrun ||= (status & Overrun) != 0
        if ((status & RxValid) != 0) got += bus.read(RxData).toInt
        assert(bus.cycles < 100 * text.size * divider + 1000, s"divider $divider: got only $got")
      }
      assert(got == text && !overrun, s"divider $divider: every byte arrived, in order")
      c.io.rxReady.expect(true.B)
    }
  }

  it should "overrun when the sender ignores rxReady, or looks at it too early" in {
    val text = (0x41 until 0x41 + 12).toSeq
    simulate(new Uart(4)) { c =>
      val sender = new Sender(c, 4, text, gap = 2, flowControl = false)
      sender.bus.idle(text.size * 10 * 4 + 100)
      assert(sender.sent == text.size)
      assert((sender.bus.status & Overrun) != 0, "no flow control: bytes were dropped")
    }
    // With a bit of one cycle, the 8th byte reaches the FIFO two cycles after its frame: a sender
    // that waits only one cycle still sees room and sends a 9th.
    simulate(new Uart(1)) { c =>
      val sender = new Sender(c, 1, text, gap = 1, flowControl = true)
      sender.bus.idle(text.size * 10 + 100)
      assert(sender.sent == 9)
      assert((sender.bus.status & Overrun) != 0, "a gap of one cycle is too short at divider 1")
    }
  }

  "SimTop" should "tie uartRxReady high unless the top drives it" in {
    simulate(new SimTopWithoutUart(driveRxReady = false))(_.io.uartRxReady.expect(true.B))
    simulate(new SimTopWithoutUart(driveRxReady = true)) { c =>
      c.io.uartRxd.poke(true.B)
      c.io.uartRxReady.expect(false.B, "the top's connection replaces the default")
    }
  }

  "UartRx" should "sample mid-bit, ignore a glitch, and drop a frame without its stop bit" in {
    val divider = 8
    simulate(new UartRx(divider)) { c =>
      val got                         = scala.collection.mutable.ArrayBuffer[Int]()
      var errors                      = 0
      def drive(bits: Seq[Int]): Unit = for (b <- bits; _ <- 0 until divider) {
        c.io.rxd.poke((b == 1).B)
        if (c.io.byte.valid.peek().litToBoolean) got += c.io.byte.bits.peek().litValue.toInt
        if (c.io.framingError.peek().litToBoolean) errors += 1
        c.clock.step()
      }
      c.io.rxd.poke(true.B)
      c.clock.step(5)
      drive(Seq(1, 1))
      for (_ <- 0 until divider / 2 - 1) { // a glitch shorter than half a bit is not a start bit
        c.io.rxd.poke(false.B)
        c.clock.step()
      }
      drive(Seq(1, 1))
      assert(got.isEmpty && errors == 0, "the glitch")
      drive(frame(0xa6) ++ frame(0x5a, stop = 0) ++ Seq(1, 1) ++ frame(0x3d) ++ Seq(1))
      assert(got == Seq(0xa6, 0x3d) && errors == 1)
      drive(frame(0x12) ++ frame(0xc8)) // back to back (no byte is a bit palindrome)
      drive(Seq(1, 1))
      assert(got == Seq(0xa6, 0x3d, 0x12, 0xc8))
    }
  }
}
