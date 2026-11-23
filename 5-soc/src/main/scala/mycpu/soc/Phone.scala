// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.util._
import mycpu.common.{MemMap, VgaIO}

/** The numbers of the phone that the device, its specs and `software/runtime/include/phone.h`
  * share.
  */
object Phone {

  /** The display: 240 x 320 pixels of RGB565, two to a 32-bit word. */
  val Width        = 240
  val Height       = 320
  val WordsPerLine = Width / 2
  val Words        = WordsPerLine * Height // 38,400

  /** Where the display is in the 640 x 480 picture of [[Vga]]'s timing: the first column and line. */
  val X0 = (Vga.HVisible - Width) / 2  // 200
  val Y0 = (Vga.VVisible - Height) / 2 // 80

  /** `ID` of the display: "R565". */
  val Id = 0x52353635L

  /** The depth of the fill queue (a fill that is being drawn counts), and of the input queue. */
  val FillQueue  = 8
  val InputDepth = 16

  /** The display registers, as word indices from `MemMap.phoneBase + MemMap.Phone.Display`. */
  object Regs {
    val Id      = 0
    val Geom    = 1
    val FbPhys  = 2
    val Ctrl    = 3
    val Status  = 4
    val IrqEn   = 5
    val Frame   = 6
    val Border  = 7
    val FillXy  = 8
    val FillWh  = 9
    val Fill    = 10
    val Present = 11
  }

  /** The bits of `STATUS` (and of `IRQ_EN`, for the two events). The free slots of the fill queue
    * are in bits [15:8].
    */
  val StatusBusy     = 0
  val StatusVblank   = 1
  val StatusVblankEv = 2
  val StatusFillEv   = 3
  val StatusOverflow = 4
  val CtrlScanout    = 0

  /** The input registers, as word indices from `MemMap.phoneBase + MemMap.Phone.Input`. */
  object In {
    val Data   = 0
    val Status = 1
    val IrqEn  = 2
  }
  val InReady    = 0
  val InOverflow = 1
  val InFlush    = 2

  /** The 8-bit channel of a `bits`-bit one, by replicating its high bits (as a display's DAC does). */
  def expand(channel: UInt, bits: Int): UInt = {
    val v = channel(bits - 1, 0)
    (v ## v(bits - 1, 2 * bits - 8))
  }

  /** `0xRRGGBB` of an RGB565 color. */
  def rgb(pixel: UInt): UInt =
    expand(pixel(15, 11), 5) ## expand(pixel(10, 5), 6) ## expand(pixel(4, 0), 5)
}

/** A rectangle to fill, already clipped: columns `x0` until `xe`, lines `y0` until `ye`. */
class FillCommand extends Bundle {
  val x0    = UInt(8.W)
  val y0    = UInt(9.W)
  val xe    = UInt(9.W)
  val ye    = UInt(9.W)
  val color = UInt(16.W)
}

/** The queue of the phone's input events: 16 events of 32 bits, with one producer (the harness's
  * `io.event`, which waits while the queue is full) and the CPU, which can inject an event too. It
  * answers every request in the same cycle.
  *
  * {{{
  *   word 0  DATA    R: pops one event, 0 when there is none      W: pushes one event
  *   word 1  STATUS  R: bit 0 ready, bit 1 overflow (sticky), bits [12:8] queued events
  *                   W: bit 1 = 1 clears overflow, bit 2 = 1 discards every queued event
  *   word 2  IRQ_EN  RW bit 0: `irq` is high while an event is ready
  * }}}
  *
  * An injection into a full queue is dropped and sets overflow; the producer is never dropped, it
  * waits (`event.ready` low). A request takes the cycle's push slot: the producer waits in it.
  * The event word is `type << 28` then, for a pointer, `x << 16 | y` and, for a key or a tick, a
  * value in the low 16 bits (`phone.h` has the types).
  */
class PhoneInput extends Module {
  val io = IO(new Bundle {
    val port  = Flipped(new riscv.MemPort)
    val event = Flipped(Decoupled(UInt(32.W)))
    val irq   = Output(Bool())
  })
  import Phone._

  val queue    = Reg(Vec(InputDepth, UInt(32.W)))
  val rd       = RegInit(0.U(log2Ceil(InputDepth).W))
  val wr       = RegInit(0.U(log2Ceil(InputDepth).W))
  val count    = RegInit(0.U(log2Ceil(InputDepth + 1).W))
  val overflow = RegInit(false.B)
  val irq_en   = RegInit(false.B)

  val req    = io.port.req
  val write  = req.valid && req.bits.wstrb === "b1111".U
  val read   = req.valid && req.bits.wstrb === 0.U
  val reg    = req.bits.addr(7, 2)
  val full   = count === InputDepth.U
  val flush  = write && reg === In.Status.U && req.bits.wdata(InFlush)
  val inject = write && reg === In.Data.U
  val pop    = read && reg === In.Data.U && count =/= 0.U

  io.event.ready := !full && !inject && !flush
  val push      = !flush && Mux(inject, !full, io.event.valid && io.event.ready)
  val push_data = Mux(inject, req.bits.wdata, io.event.bits)

  when(push) {
    queue(wr) := push_data
    wr        := wr + 1.U
  }
  when(pop) { rd := rd + 1.U }
  when(flush) {
    rd    := wr
    count := 0.U
  }.otherwise {
    count := count + push.asUInt - pop.asUInt
  }
  when(inject && full) { overflow := true.B }
  when(write && reg === In.Status.U && req.bits.wdata(InOverflow)) { overflow := false.B }
  when(write && reg === In.IrqEn.U) { irq_en := req.bits.wdata(0) }

  req.ready               := true.B
  io.port.resp.valid      := req.valid
  io.port.resp.bits.error := false.B
  io.port.resp.bits.rdata := MuxLookup(reg, 0.U)(
    Seq(
      In.Data.U   -> Mux(count =/= 0.U, queue(rd), 0.U),
      In.Status.U -> (count ## 0.U(6.W) ## overflow ## (count =/= 0.U)).pad(32),
      In.IrqEn.U  -> irq_en
    )
  )
  io.irq := irq_en && count =/= 0.U
}

/** The phone of the SoC, a second display beside [[Vga]]: a 240 x 320 RGB565 framebuffer that the
  * CPU reads and writes, a queue of fill commands that draws rectangles into it, and the queue of
  * input events ([[PhoneInput]]). It is not part of the exercises, and it uses no bus master: the
  * framebuffer is in the device, as the VGA's buffers are.
  *
  * '''Window''' (`MemMap.phoneBase`, 256 KiB; one `MemPort`, one request in each cycle, answered in
  * the same cycle): the framebuffer from offset 0 (word `120 * y + x / 2`, pixel x in bits [15:0]
  * of its word when x is even and in [31:16] when it is odd; a write takes its byte strobes, reads
  * and writes of other offsets than the 38,400 words answer 0 and change nothing), the display
  * registers at `MemMap.Phone.Display`, the input registers at `MemMap.Phone.Input`.
  *
  * '''Display registers''' (word indices; a write needs all four strobes):
  *
  * {{{
  *    0  ID       RO  0x52353635 ("R565")
  *    1  GEOM     RO  width | height << 16
  *    2  FBPHYS   RO  the address of the framebuffer, MemMap.phoneBase
  *    3  CTRL     RW  bit 0 scanout (reset 0): show the phone; 0 leaves the VGA's picture alone
  *    4  STATUS   R   bit 0 busy (a fill is queued or running), bit 1 vblank now, bit 2 vblank event,
  *                    bit 3 fill event, bit 4 overflow, bits [15:8] free slots of the fill queue
  *                W   a 1 in bit 2, 3 or 4 clears that event (W1C)
  *    5  IRQ_EN   RW  bits 2 and 3: the events that raise `irq`
  *    6  FRAME    RO  frames scanned out since reset (counted where vblank begins)
  *    7  BORDER   RW  RGB565 of the picture around the phone (reset 0)
  *    8  FILL_XY  RW  x | y << 16
  *    9  FILL_WH  RW  w | h << 16
  *   10  FILL     WO  RGB565: queues the fill of FILL_XY, FILL_WH (clipped to the display; nothing
  *                    is queued for an empty rectangle; a full queue sets overflow and drops it)
  *   11  PRESENT  RW  a write says that the framebuffer holds a complete picture, and the read
  *                    gives the number of such writes. The top adds it to `vga.swaps`, with which
  *                    the Verilator harness numbers the frames of `+vga-dump`.
  * }}}
  *
  * '''Fill engine.''' It takes the queued rectangles in order and draws one word a cycle (a single
  * pixel at an odd edge), in the cycles in which the CPU writes no framebuffer word: a CPU store
  * always goes first. The two are not ordered against each other: a CPU store to a pixel that a
  * queued fill covers lands before or after it, so software waits for `busy` to fall first.
  *
  * '''Video.''' The phone borrows the timing of the VGA (`video`, its `x`, `y` and `active` already
  * describe the pixel that is shown, so `rgb` is a function of them) and, while `on`, replaces its
  * color: the picture at ([[Phone.X0]], [[Phone.Y0]]) of the 640 x 480 frame, `border` around it,
  * and black outside `active`. The expansion of RGB565 to 24 bits replicates the high bits.
  */
class Phone extends Module {
  val io = IO(new Bundle {
    val port    = Flipped(new riscv.MemPort)
    val video   = Input(new VgaIO)
    val rgb     = Output(UInt(24.W))
    val on      = Output(Bool())
    val commits = Output(UInt(32.W))
    val irq     = Output(Bool())
    val event   = Flipped(Decoupled(UInt(32.W)))
  })
  import Phone._

  val scanout     = RegInit(false.B)
  val border      = RegInit(0.U(16.W))
  val irq_en      = RegInit(0.U(2.W))    // STATUS bits 2 and 3
  val vblank_ev   = RegInit(false.B)
  val fill_ev     = RegInit(false.B)
  val overflow    = RegInit(false.B)
  val fill_set    = WireDefault(false.B) // the fill queue has drained in this cycle
  val frame_count = RegInit(0.U(32.W))
  val commit      = RegInit(0.U(32.W))
  val fill_xy     = RegInit(0.U(32.W))
  val fill_wh     = RegInit(0.U(32.W))

  val framebuffer       = Mem(Words, Vec(4, UInt(8.W)))
  def bytes(word: UInt) = word.asTypeOf(Vec(4, UInt(8.W)))

  // the vertical blanking of the VGA's scan: lines 480 to 524 (it starts at reset, in line 524)
  val vblank      = io.video.y >= Vga.VVisible.U
  val vblank_prev = RegNext(vblank, true.B)
  val vblank_edge = vblank && !vblank_prev
  when(vblank_edge) {
    frame_count := frame_count + 1.U
  }

  // --- the bus: the framebuffer, the display registers, the input registers
  val input      = Module(new PhoneInput)
  val req        = io.port.req
  val write      = req.valid && req.bits.wstrb =/= 0.U
  val read       = req.valid && req.bits.wstrb === 0.U
  val full       = req.bits.wstrb === "b1111".U
  val word       = req.bits.addr(17, 2)
  val in_fb      = word < Words.U
  val block      = req.bits.addr(17, 8)
  val in_display = block === (MemMap.Phone.Display >> 8).U
  val in_input   = block === (MemMap.Phone.Input >> 8).U
  val reg        = req.bits.addr(7, 2)

  input.io.port.req.valid := req.valid && in_input
  input.io.port.req.bits  := req.bits
  input.io.event <> io.event
  io.irq := input.io.irq || (vblank_ev && irq_en(0)) || (fill_ev && irq_en(1))

  // --- the fill queue and its engine
  val queue  = Module(new Queue(new FillCommand, FillQueue))
  val active = RegInit(false.B)
  val cmd    = Reg(new FillCommand)
  val cx     = Reg(UInt(9.W))
  val cy     = Reg(UInt(9.W))
  val row    = Reg(UInt(16.W)) // 120 * cy
  val free   = FillQueue.U - queue.io.count - active.asUInt
  val busy   = active || queue.io.count =/= 0.U

  val status  = (free ## 0.U(3.W) ## overflow ## fill_ev ## vblank_ev ## vblank ## busy).pad(32)
  val fill_w  = fill_wh(15, 0)
  val fill_h  = fill_wh(31, 16)
  val fill_x  = fill_xy(15, 0)
  val fill_y  = fill_xy(31, 16)
  val fill_ok = fill_w =/= 0.U && fill_h =/= 0.U && fill_x < Width.U && fill_y < Height.U
  val x_end   = Mux(fill_x +& fill_w > Width.U, Width.U, fill_x + fill_w)
  val y_end   = Mux(fill_y +& fill_h > Height.U, Height.U, fill_y + fill_h)

  val fill_write = write && full && in_display && reg === Regs.Fill.U
  val queued     = fill_write && fill_ok && free =/= 0.U
  queue.io.enq.valid      := queued
  queue.io.enq.bits.x0    := fill_x(7, 0)
  queue.io.enq.bits.y0    := fill_y(8, 0)
  queue.io.enq.bits.xe    := x_end(8, 0)
  queue.io.enq.bits.ye    := y_end(8, 0)
  queue.io.enq.bits.color := req.bits.wdata(15, 0)
  queue.io.deq.ready      := !active
  when(fill_write && fill_ok && free === 0.U) { overflow := true.B }

  when(queue.io.deq.fire) {
    active := true.B
    cmd    := queue.io.deq.bits
    cx     := queue.io.deq.bits.x0
    cy     := queue.io.deq.bits.y0
    row    := queue.io.deq.bits.y0 * WordsPerLine.U
  }

  // The next word of the rectangle: one pixel at an odd column or when one pixel is left of the
  // line, else both pixels of a word.
  val single    = cx(0) || cmd.xe - cx === 1.U
  val fill_idx  = row + cx(8, 1)
  val fill_mask = Mux(!single, "b1111".U(4.W), Mux(cx(0), "b1100".U(4.W), "b0011".U(4.W)))
  val next_cx   = Mux(single, cx + 1.U, cx + 2.U)
  val line_end  = next_cx >= cmd.xe
  val fill_draw = active && !(write && in_fb)
  when(fill_draw) {
    cx := Mux(line_end, cmd.x0, next_cx)
    when(line_end) {
      cy  := cy + 1.U
      row := row + WordsPerLine.U
      when(cy + 1.U === cmd.ye) {
        active   := false.B
        fill_set := !queued && queue.io.count === 0.U
      }
    }
  }

  // --- the framebuffer: one write port (the CPU's store first, else the fill), two read ports
  val cpu_store = req.valid && in_fb && write
  val w_idx     = Mux(cpu_store, word, fill_idx)
  val w_data    = Mux(cpu_store, bytes(req.bits.wdata), bytes(cmd.color ## cmd.color))
  val w_mask    = Mux(cpu_store, req.bits.wstrb, fill_mask)
  when(cpu_store || fill_draw) { framebuffer.write(w_idx, w_data, w_mask.asBools) }

  val cpu_word = framebuffer(word).asUInt

  // --- the registers
  val rdata = WireDefault(0.U(32.W))
  when(in_fb && read) { rdata := cpu_word }
  when(in_display) {
    rdata := MuxLookup(reg, 0.U)(
      Seq(
        Regs.Id.U      -> Id.U(32.W),
        Regs.Geom.U    -> (Height.U(16.W) ## Width.U(16.W)),
        Regs.FbPhys.U  -> MemMap.phoneBase.U(32.W),
        Regs.Ctrl.U    -> scanout,
        Regs.Status.U  -> status,
        Regs.IrqEn.U   -> (irq_en ## 0.U(2.W)),
        Regs.Frame.U   -> frame_count,
        Regs.Border.U  -> border,
        Regs.FillXy.U  -> fill_xy,
        Regs.FillWh.U  -> fill_wh,
        Regs.Present.U -> commit
      )
    )
  }
  when(in_input) { rdata := input.io.port.resp.bits.rdata }

  when(write && full && in_display) {
    switch(reg) {
      is(Regs.Ctrl.U)(scanout := req.bits.wdata(CtrlScanout))
      is(Regs.Status.U) {
        when(req.bits.wdata(StatusVblankEv)) { vblank_ev := false.B }
        when(req.bits.wdata(StatusFillEv)) { fill_ev := false.B }
        when(req.bits.wdata(StatusOverflow)) { overflow := false.B }
      }
      is(Regs.IrqEn.U)(irq_en   := req.bits.wdata(3, 2))
      is(Regs.Border.U)(border  := req.bits.wdata(15, 0))
      is(Regs.FillXy.U)(fill_xy := req.bits.wdata)
      is(Regs.FillWh.U)(fill_wh := req.bits.wdata)
      is(Regs.Present.U)(commit := commit + 1.U)
    }
  }

  req.ready               := true.B
  io.port.resp.valid      := req.valid
  io.port.resp.bits.error := false.B
  io.port.resp.bits.rdata := rdata

  // --- the video: the picture, the border and black, for the pixel at (x, y) that `video` shows
  val x      = io.video.x
  val y      = io.video.y
  val inside = x >= X0.U && x < (X0 + Width).U && y >= Y0.U && y < (Y0 + Height).U
  val px     = x - X0.U
  val py     = y - Y0.U
  val scan   = framebuffer(Mux(inside, py * WordsPerLine.U + px(8, 1), 0.U)).asUInt
  val pixel  = Mux(px(0), scan(31, 16), scan(15, 0))
  io.on      := scanout
  io.rgb     := Mux(io.video.active, rgb(Mux(inside, pixel, border)), 0.U)
  io.commits := commit

  // The events are set last, so that a new one wins over a clear (W1C) in the same cycle.
  when(vblank_edge) { vblank_ev := true.B }
  when(fill_set) { fill_ev := true.B }
}
