// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common.{MemMap, TestTools}
import org.scalatest.flatspec.AnyFlatSpec

/** The phone ([[Phone]]) on its own: the register map, the framebuffer, the fill engine against a
  * model of rectangles, the input queue, and the video that it derives from the VGA's timing.
  */
class PhoneSpec extends AnyFlatSpec with ChiselSim {
  import Phone._

  private val display = MemMap.Phone.Display
  private val input   = MemMap.Phone.Input

  def start(c: Phone): Unit = {
    c.io.port.req.valid.poke(false.B)
    c.io.event.valid.poke(false.B)
    c.io.event.bits.poke(0.U)
    video(c, 0, 0, active = false)
    c.reset.poke(true.B)
    c.clock.step(2)
    c.reset.poke(false.B)
  }

  /** The video input as the VGA shows a pixel: its position and whether it is in the picture. */
  def video(c: Phone, x: Int, y: Int, active: Boolean = true): Unit = {
    c.io.video.x.poke(x.U)
    c.io.video.y.poke(y.U)
    c.io.video.active.poke(active.B)
  }

  def request(c: Phone, offset: Long, wdata: Long, wstrb: Int): Long = {
    c.io.port.req.valid.poke(true.B)
    c.io.port.req.bits.addr.poke((MemMap.phoneBase + offset).U)
    c.io.port.req.bits.wdata.poke(wdata.U)
    c.io.port.req.bits.wstrb.poke(wstrb.U)
    c.io.port.req.ready.expect(true.B)
    c.io.port.resp.valid.expect(true.B, "answered in the same cycle")
    c.io.port.resp.bits.error.expect(false.B)
    c.io.port.resp.bits.rdata.peek().litValue.toLong
  }
  def idle(c: Phone): Unit               = c.io.port.req.valid.poke(false.B)
  def read(c: Phone, offset: Long): Long = {
    val data = request(c, offset, 0, 0)
    c.clock.step()
    idle(c)
    data
  }
  def write(c: Phone, offset: Long, data: Long, strobe: Int = 0xf): Unit = {
    request(c, offset, data, strobe)
    c.clock.step()
    idle(c)
  }
  def reg(index: Int): Long   = display + 4L * index
  def inReg(index: Int): Long = input + 4L * index

  /** Queues a fill as `phone_fill` does: after waiting for a free slot. */
  def fillRect(c: Phone, x: Int, y: Int, w: Int, h: Int, color: Int): Unit = {
    while (read(c, reg(Regs.Status)) >> 8 == 0) {}
    queueFill(c, x, y, w, h, color)
  }

  def queueFill(c: Phone, x: Int, y: Int, w: Int, h: Int, color: Int): Unit = {
    write(c, reg(Regs.FillXy), (x & 0xffff) | (y.toLong & 0xffff) << 16)
    write(c, reg(Regs.FillWh), (w & 0xffff) | (h.toLong & 0xffff) << 16)
    write(c, reg(Regs.Fill), color)
  }

  def waitIdle(c: Phone, limit: Int = 1000000): Int = {
    var steps = 0
    while ((read(c, reg(Regs.Status)) & 1) == 1) {
      steps += 1
      assert(steps < limit, "the fill engine never finishes")
    }
    steps
  }

  /** A model of the framebuffer: one RGB565 value per pixel. */
  class Model {
    val pixel                                                  = Array.fill(Width * Height)(-1)
    def fill(x: Int, y: Int, w: Int, h: Int, color: Int): Unit =
      for (yy <- y until math.min(y + h, Height); xx <- x until math.min(x + w, Width) if w > 0 && h > 0)
        pixel(yy * Width + xx) = color
    def word(i: Int): Long = {
      val (lo, hi) = (pixel(2 * i), pixel(2 * i + 1))
      (lo & 0xffffL) | (hi & 0xffffL) << 16
    }
  }

  /** Reads every word that the model has written and compares it. */
  def check(c: Phone, m: Model, what: String): Unit =
    for (i <- 0 until Words if m.pixel(2 * i) >= 0 || m.pixel(2 * i + 1) >= 0) {
      val got  = read(c, MemMap.Phone.Framebuffer + 4L * i)
      val want = m.word(i)
      val mask = (if (m.pixel(2 * i) >= 0) 0xffffL else 0L) | (if (m.pixel(2 * i + 1) >= 0) 0xffff0000L else 0L)
      assert(
        (got & mask) == (want & mask),
        f"$what: word $i (x ${i % WordsPerLine * 2}, y ${i / WordsPerLine}) is 0x$got%08x, expected 0x$want%08x"
      )
    }

  "Phone" should "have the registers that phone.h gives to programs" in {
    simulate(new Phone) { c =>
      start(c)
      assert(read(c, reg(Regs.Id)) == Id)
      assert(read(c, reg(Regs.Geom)) == (Height << 16 | Width))
      assert(read(c, reg(Regs.FbPhys)) == MemMap.phoneBase)
      assert(read(c, reg(Regs.Ctrl)) == 0, "the phone does not scan out after reset")
      c.io.on.expect(false.B)
      assert(read(c, reg(Regs.Status)) >> 8 == FillQueue, "an empty fill queue")
      assert(read(c, reg(Regs.Border)) == 0)
      assert(read(c, reg(Regs.Present)) == 0)
      write(c, reg(Regs.Ctrl), 1)
      assert(read(c, reg(Regs.Ctrl)) == 1)
      c.io.on.expect(true.B)
      write(c, reg(Regs.Ctrl), 0xfffffffeL, 0x7) // a partial write is answered and ignored
      assert(read(c, reg(Regs.Ctrl)) == 1)
      write(c, reg(Regs.Border), 0x12345678L)
      assert(read(c, reg(Regs.Border)) == 0x5678, "the border has 16 bits")
      for (index <- Seq(Regs.Id, Regs.Geom, Regs.FbPhys, Regs.Frame)) {
        val before = read(c, reg(index))
        write(c, reg(index), 0xffffffffL)
        assert(read(c, reg(index)) == before, s"register $index is read-only")
      }
      for (
        offset <- Seq(
          reg(12),
          reg(63),
          display + 0x80,
          MemMap.Phone.Framebuffer + 4L * Words,
          0x2fffcL,
          0x30200L,
          0x3fffcL
        )
      )
        assert(read(c, offset) == 0, f"0x$offset%x reads 0")
      write(c, reg(Regs.Present), 1)
      write(c, reg(Regs.Present), 7)
      assert(read(c, reg(Regs.Present)) == 2, "PRESENT counts writes")
      c.io.commits.expect(2.U)
    }
  }

  it should "have the register map that software/runtime/include/phone.h gives to programs" in {
    val header =
      new String(java.nio.file.Files.readAllBytes(TestTools.root.resolve("software/runtime/include/phone.h")))
    val hex     = """(?m)^#define (PHONE_\w+) \(?(0x[0-9a-fA-F]+|\d+)u?\)?\s*(?://.*)?$""".r
    val shifted = """(?m)^#define (PHONE_\w+) \(1u << (\d+)\)""".r
    val defines =
      hex.findAllMatchIn(header).map(m => m.group(1) -> java.lang.Long.decode(m.group(2)).toLong).toMap ++
        shifted.findAllMatchIn(header).map(m => m.group(1) -> (1L << m.group(2).toInt))
    val expected = Map(
      "PHONE_BASE"         -> MemMap.phoneBase,
      "PHONE_WIDTH"        -> Width.toLong,
      "PHONE_HEIGHT"       -> Height.toLong,
      "PHONE_ID"           -> Regs.Id.toLong,
      "PHONE_GEOM"         -> Regs.Geom.toLong,
      "PHONE_FBPHYS"       -> Regs.FbPhys.toLong,
      "PHONE_CTRL"         -> Regs.Ctrl.toLong,
      "PHONE_STATUS"       -> Regs.Status.toLong,
      "PHONE_IRQ_EN"       -> Regs.IrqEn.toLong,
      "PHONE_FRAME"        -> Regs.Frame.toLong,
      "PHONE_BORDER"       -> Regs.Border.toLong,
      "PHONE_FILL_XY"      -> Regs.FillXy.toLong,
      "PHONE_FILL_WH"      -> Regs.FillWh.toLong,
      "PHONE_FILL"         -> Regs.Fill.toLong,
      "PHONE_PRESENT"      -> Regs.Present.toLong,
      "PHONE_ID_VALUE"     -> Id,
      "PHONE_CTRL_SCANOUT" -> (1L << CtrlScanout),
      "PHONE_ST_BUSY"      -> (1L << StatusBusy),
      "PHONE_ST_VBLANK"    -> (1L << StatusVblank),
      "PHONE_ST_VBLANK_EV" -> (1L << StatusVblankEv),
      "PHONE_ST_FILL_EV"   -> (1L << StatusFillEv),
      "PHONE_ST_OVERFLOW"  -> (1L << StatusOverflow),
      "PHONE_FILL_QUEUE"   -> FillQueue.toLong,
      "PHONE_IN_DATA"      -> In.Data.toLong,
      "PHONE_IN_STATUS"    -> In.Status.toLong,
      "PHONE_IN_IRQ_EN"    -> In.IrqEn.toLong,
      "PHONE_IN_READY"     -> (1L << InReady),
      "PHONE_IN_OVERFLOW"  -> (1L << InOverflow),
      "PHONE_IN_FLUSH"     -> (1L << InFlush),
      "PHONE_IN_DEPTH"     -> InputDepth.toLong
    )
    for ((name, value) <- expected)
      assert(defines.get(name).contains(value), f"$name is ${defines.get(name)}, expected 0x$value%x")
    assert(header.contains("#define PHONE_WORDS_PER_LINE (PHONE_WIDTH / 2u)") && WordsPerLine == Width / 2)
    assert(header.contains(f"#define PHONE_REG_BASE (PHONE_BASE + 0x${MemMap.Phone.Display}%05xu)"), "PHONE_REG_BASE")
    assert(header.contains(f"#define PHONE_IN_BASE (PHONE_BASE + 0x${MemMap.Phone.Input}%05xu)"), "PHONE_IN_BASE")
    assert(header.contains(f"(PHONE_BASE + 0x${MemMap.Phone.Framebuffer}%05xu)"), "PHONE_FB")
  }

  it should "read and write the framebuffer by bytes, halfwords and words" in {
    simulate(new Phone) { c =>
      start(c)
      val last = 4L * (Words - 1)
      write(c, 0, 0x11223344L)
      write(c, 4, 0x55667788L)
      write(c, last, 0xcafef00dL)
      assert(read(c, 0) == 0x11223344L)
      assert(read(c, 4) == 0x55667788L)
      assert(read(c, last) == 0xcafef00dL, "the last word")
      write(c, 0, 0xaa00aa00L, 0xa) // bytes 1 and 3
      assert(read(c, 0) == 0xaa22aa44L)
      write(c, 4, 0x0000beefL, 0x3) // the low halfword
      assert(read(c, 4) == 0x5566beefL)
      write(c, 4, 0x12340000L, 0xc) // the high halfword
      assert(read(c, 4) == 0x1234beefL)
      write(c, 4, 0xffffffffL, 0x0) // no strobe is a read: nothing changes
      assert(read(c, 4) == 0x1234beefL)
      write(c, 4L * Words, 0xdeadbeefL) // beyond the framebuffer: answered, ignored
      assert(read(c, 4L * Words) == 0)
      assert(read(c, last) == 0xcafef00dL)
    }
  }

  it should "fill rectangles as a model of them does, clipped to the display, at odd and even edges" in {
    simulate(new Phone) { c =>
      start(c)
      val m     = new Model
      val rects = Seq(
        (0, 0, 240, 1, 0x1111),       // a whole line
        (3, 5, 1, 1, 0x2222),         // one pixel, odd column
        (4, 5, 1, 1, 0x3333),         // one pixel, even column
        (1, 10, 5, 4, 0x4444),        // odd start, odd width
        (2, 20, 6, 3, 0x5555),        // even start, even width
        (1, 30, 4, 2, 0x6666),        // odd start, even width
        (2, 40, 5, 2, 0x7777),        // even start, odd width
        (235, 100, 50, 30, 0x8888),   // clipped at the right
        (10, 310, 10, 40, 0x9999),    // clipped at the bottom
        (230, 300, 400, 400, 0xaaaa), // clipped on both
        (239, 319, 1, 1, 0xbbbb),     // the last pixel
        (0, 0, 0, 5, 0xcccc),         // empty: nothing
        (5, 5, 5, 0, 0xcccc),         // empty: nothing
        (240, 0, 5, 5, 0xcccc),       // outside: nothing
        (0, 320, 5, 5, 0xcccc)        // outside: nothing
      )
      for ((x, y, w, h, color) <- rects) {
        fillRect(c, x, y, w, h, color)
        m.fill(x, y, w, h, color)
        waitIdle(c)
      }
      check(c, m, "rectangles")
      val random = new scala.util.Random(26)
      for (_ <- 0 until 40) {
        val (x, y, w, h, color) =
          (random.nextInt(250), random.nextInt(330), random.nextInt(40), random.nextInt(20), random.nextInt(0x10000))
        fillRect(c, x, y, w, h, color)
        m.fill(x, y, w, h, color)
      }
      waitIdle(c)
      check(c, m, "random rectangles")
    }
  }

  it should "queue fills, report busy, free slots, fill events and overflow, and draw them in order" in {
    simulate(new Phone) { c =>
      start(c)
      write(c, reg(Regs.IrqEn), 0)
      c.io.irq.expect(false.B)
      // a large fill keeps the engine busy while more are queued
      fillRect(c, 0, 0, 240, 40, 0x0001)
      val status = read(c, reg(Regs.Status))
      assert((status & 1) == 1, "busy")
      assert(status >> 8 <= FillQueue - 1, "a fill is queued or running")
      for (i <- 0 until FillQueue - 1) queueFill(c, 0, 0, 240, 40, 0x0100 + i)
      assert(read(c, reg(Regs.Status)) >> 8 == 0, "the queue is full")
      queueFill(c, 0, 0, 1, 1, 0xffff) // dropped
      assert((read(c, reg(Regs.Status)) >> StatusOverflow & 1) == 1, "overflow")
      write(c, reg(Regs.Status), 1L << StatusOverflow)
      assert((read(c, reg(Regs.Status)) >> StatusOverflow & 1) == 0, "overflow is W1C")
      assert((read(c, reg(Regs.Status)) >> StatusFillEv & 1) == 0, "the queue has not drained")
      waitIdle(c)
      val after = read(c, reg(Regs.Status))
      assert((after >> StatusFillEv & 1) == 1, "the fill event")
      assert(after >> 8 == FillQueue, "the queue is empty again")
      assert(read(c, 0) == 0x01060106L, "the fills ran in order, and the dropped one did not")
      assert(read(c, 4L * (WordsPerLine * 40 - 1)) == 0x01060106L)
      c.io.irq.expect(false.B)
      write(c, reg(Regs.IrqEn), 1L << StatusFillEv)
      c.io.irq.expect(true.B)
      write(c, reg(Regs.Status), 1L << StatusFillEv)
      c.io.irq.expect(false.B)
    }
  }

  it should "never lose a CPU store while a fill runs" in {
    simulate(new Phone) { c =>
      start(c)
      fillRect(c, 0, 0, 240, 4, 0xaaaa)
      for (i <- 0 until 100) write(c, 4L * (i % 100 + 120 * 10), 0x10000000L + i) // below the fill
      for (i <- 0 until 100) write(c, 4L * (i % 100 + 120 * 10), 0x20000000L + i)
      waitIdle(c)
      for (i <- 0 until 100) assert(read(c, 4L * (i % 100 + 120 * 10)) == 0x20000000L + i, s"word $i")
      for (i <- 0 until 480) assert(read(c, 4L * i) == 0xaaaaaaaaL, s"filled word $i")
    }
  }

  it should "scan out the picture, the border around it and black outside the active area" in {
    simulate(new Phone) { c =>
      start(c)
      // two words of known pixels: (0, 0) = 0xf800 red, (1, 0) = 0x07e0 green, (2, 0) = 0x001f blue, (3, 0) = 0xffff
      write(c, 0, 0x07e0f800L)
      write(c, 4, 0xffff001fL)
      write(c, 4L * WordsPerLine * (Height - 1) + 4L * (WordsPerLine - 1), 0x8410_a514L) // the last two pixels
      write(c, reg(Regs.Border), 0x1234)
      def rgbAt(x: Int, y: Int, active: Boolean = true): Long = {
        video(c, x, y, active)
        c.io.rgb.peek().litValue.toLong
      }
      assert(rgbAt(X0, Y0) == 0xff0000, "red: 5 bits replicated")
      assert(rgbAt(X0 + 1, Y0) == 0x00ff00)
      assert(rgbAt(X0 + 2, Y0) == 0x0000ff)
      assert(rgbAt(X0 + 3, Y0) == 0xffffff)
      assert(rgbAt(X0 + Width - 2, Y0 + Height - 1) == rgb565(0xa514))
      assert(rgbAt(X0 + Width - 1, Y0 + Height - 1) == rgb565(0x8410))
      val border = rgb565(0x1234)
      for ((x, y) <- Seq((0, 0), (X0 - 1, Y0), (X0 + Width, Y0), (X0, Y0 - 1), (X0, Y0 + Height), (639, 479)))
        assert(rgbAt(x, y) == border, s"border at ($x, $y)")
      for ((x, y) <- Seq((0, 0), (X0, Y0), (639, 479))) assert(rgbAt(x, y, active = false) == 0, s"black at ($x, $y)")
    }
  }

  def rgb565(p: Int): Long = {
    def e(v: Int, bits: Int) = (v << (8 - bits) | v >> (2 * bits - 8)) & 0xff
    (e(p >> 11 & 31, 5).toLong << 16) | (e(p >> 5 & 63, 6).toLong << 8) | e(p & 31, 5)
  }

  it should "count frames and raise the vblank event where the VGA's scan enters line 480" in {
    simulate(new Phone) { c =>
      start(c)
      video(c, 0, 100)
      c.clock.step()
      val frames = read(c, reg(Regs.Frame))
      write(c, reg(Regs.Status), (1L << StatusVblankEv)) // the one from reset
      assert((read(c, reg(Regs.Status)) & 6) == 0, "no vblank, no event")
      video(c, 639, 479)
      c.clock.step(2)
      assert((read(c, reg(Regs.Status)) & 6) == 0)
      video(c, 0, 480)
      c.clock.step(2)
      assert((read(c, reg(Regs.Status)) & 6) == 6, "vblank, and its event")
      assert(read(c, reg(Regs.Frame)) == frames + 1)
      video(c, 100, 524)
      c.clock.step(3)
      assert(read(c, reg(Regs.Frame)) == frames + 1, "one frame is counted once")
      write(c, reg(Regs.IrqEn), 1L << StatusVblankEv)
      c.io.irq.expect(true.B)
      write(c, reg(Regs.Status), 1L << StatusVblankEv)
      c.io.irq.expect(false.B)
      video(c, 0, 0)
      c.clock.step(2)
      assert((read(c, reg(Regs.Status)) & 6) == 0)
    }
  }

  it should "keep a new vblank event that arrives in the cycle in which software clears the old one" in {
    simulate(new Phone) { c =>
      start(c)
      video(c, 0, 100)
      c.clock.step(2)
      write(c, reg(Regs.Status), 1L << StatusVblankEv)
      assert((read(c, reg(Regs.Status)) >> StatusVblankEv & 1) == 0)
      video(c, 0, Vga.VVisible)                               // vblank begins in this cycle...
      request(c, reg(Regs.Status), 1L << StatusVblankEv, 0xf) // ...and software clears in it
      c.clock.step()
      idle(c)
      assert((read(c, reg(Regs.Status)) >> StatusVblankEv & 1) == 1, "the new event survives")
    }
  }

  def offer(c: Phone, event: Long): Boolean = {
    c.io.event.valid.poke(true.B)
    c.io.event.bits.poke(event.U)
    val taken = c.io.event.ready.peek().litToBoolean
    c.clock.step()
    c.io.event.valid.poke(false.B)
    taken
  }

  it should "queue input events in order, with a status, a flush, an overflow and an interrupt" in {
    simulate(new Phone) { c =>
      start(c)
      assert(read(c, inReg(In.Data)) == 0, "nothing is queued")
      assert(read(c, inReg(In.Status)) == 0)
      for (i <- 0 until InputDepth) assert(offer(c, 0x10000000L + i), s"event $i is taken")
      assert(read(c, input + 0x10) == 0, "unused input offset reads zero")
      write(c, input + 0x14, 1L << InFlush)
      write(c, input + 0x18, 1)
      assert(read(c, input + 0x18) == 0, "unused input offset does not alias IRQ_EN")
      c.io.irq.expect(false.B)
      assert(read(c, inReg(In.Status)) == (InputDepth << 8 | 1))
      c.io.event.valid.poke(true.B)
      c.io.event.ready.expect(false.B, "the producer waits while the queue is full")
      c.io.event.valid.poke(false.B)
      assert((read(c, inReg(In.Status)) >> InOverflow & 1) == 0, "the producer cannot overflow it")
      write(c, inReg(In.Data), 0x4000_0041L) // injected into a full queue
      assert((read(c, inReg(In.Status)) >> InOverflow & 1) == 1, "overflow")
      for (i <- 0 until InputDepth) assert(read(c, inReg(In.Data)) == 0x10000000L + i, s"event $i comes out in order")
      assert(read(c, inReg(In.Data)) == 0)
      write(c, inReg(In.Status), 1L << InOverflow)
      assert(read(c, inReg(In.Status)) == 0, "W1C")
      write(c, inReg(In.Data), 0x4000_0041L)
      write(c, inReg(In.Data), 0x4000_0042L)
      assert(read(c, inReg(In.Status)) == (2 << 8 | 1))
      c.io.irq.expect(false.B)
      write(c, inReg(In.IrqEn), 1)
      assert(read(c, inReg(In.IrqEn)) == 1)
      c.io.irq.expect(true.B, "an event is ready")
      write(c, inReg(In.Status), 1L << InFlush)
      assert(read(c, inReg(In.Status)) == 0, "flushed")
      c.io.irq.expect(false.B)
      assert(read(c, inReg(In.Data)) == 0)
      assert(offer(c, 0x5000_0010L))
      assert(read(c, inReg(In.Data)) == 0x5000_0010L, "after a flush the queue works")
      // a CPU injection takes the cycle's slot: the producer waits that cycle and then gets in
      c.io.event.valid.poke(true.B)
      c.io.event.bits.poke(0x1000_0001L.U)
      request(c, inReg(In.Data), 0x4000_0002L, 0xf)
      c.io.event.ready.expect(false.B)
      c.clock.step()
      idle(c)
      c.io.event.ready.expect(true.B)
      c.clock.step()
      c.io.event.valid.poke(false.B)
      assert(read(c, inReg(In.Data)) == 0x4000_0002L)
      assert(read(c, inReg(In.Data)) == 0x1000_0001L)
    }
  }
}
