// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.util._
import mycpu.common.{MemMap, VgaIO}

/** The numbers of the VGA that the device, its specs and the test tops share. */
object Vga {

  /** The mutants `Vga` can be built with (negative tests only). */
  val mutants: Set[String] = Set("vga-no-id")

  /** VESA 640x480@60 Hz, in pixels: visible, front porch, sync pulse, back porch. */
  val HVisible = 640
  val HFront   = 16
  val HSync    = 96
  val HBack    = 48
  val HTotal   = HVisible + HFront + HSync + HBack // 800

  /** The same in lines. */
  val VVisible = 480
  val VFront   = 10
  val VSync    = 2
  val VBack    = 33
  val VTotal   = VVisible + VFront + VSync + VBack // 525

  /** The framebuffer: 160 x 120 pixels of 4 bits, each shown as a 4 x 4 block. */
  val Scale          = 4
  val FbWidth        = HVisible / Scale        // 160
  val FbHeight       = VVisible / Scale        // 120
  val PixelsPerWord  = 8
  val WordsPerLine   = FbWidth / PixelsPerWord // 20
  val WordsPerBuffer = WordsPerLine * FbHeight // 2,400 words = 9,600 bytes
  val PaletteEntries = 16

  /** The value of the `ID` register: "VGA" and the version of this register map. */
  val Id: Long = 0x56474101L

  /** `STATUS` and `CTRL` bits. */
  val StatusVblank      = 0
  val StatusFront       = 1
  val StatusSwapPending = 2
  val CtrlEnable        = 0
  val CtrlSwap          = 1
}

/** The VGA of the SoC (64 KiB at `MemMap.vgaBase`), on the bootcamp's
  * `MemPort`. Like [[mycpu.common.SimCtrl]] it answers every request in the same cycle and never
  * reports an error, and a write takes effect only with all four byte strobes set; a write with
  * any other nonzero strobe is answered and does nothing.
  *
  * '''Display.''' 640 x 480 at 60 Hz with the VESA timing: 640 visible pixels, 16 front porch, 96
  * sync, 48 back porch (800 per line), and 480 visible lines, 10 front porch, 2 sync, 33 back
  * porch (525 per frame); both syncs negative. The picture is a framebuffer of 160 x 120 pixels,
  * each shown as a block of 4 x 4 screen pixels. A framebuffer pixel is 4 bits, an index into a
  * palette of 16 colors of 24 bits (`0xRRGGBB`; a board with a 12-bit DAC takes the high 4 bits
  * of each channel). There are two buffers. The ''front'' buffer is shown, and software draws
  * into the other one and then asks for a swap.
  *
  * '''Pixel clock.''' There is one clock domain. The pixel clock is a clock enable, high in one
  * cycle of every `pixelDiv` (2 by default: half the CPU clock), so a frame takes
  * 800 x 525 x `pixelDiv` cycles and is the same in every run. An asynchronous FIFO between two
  * clock domains is needed only for a board with a separate pixel clock.
  *
  * '''Register map''' (offsets from `MemMap.vgaBase`, also in `MemMap.Vga` and in
  * `software/runtime/include/mmio.h`; address bits [15:2] are decoded and bits [1:0] ignored):
  *
  * {{{
  *   0x0000  ID       RO  0x56474101 ("VGA", version 1)
  *   0x0004  STATUS   RO  bit 0 vblank: the scan is in lines 480..524
  *                        bit 1 front: the buffer that is shown (0 or 1)
  *                        bit 2 swap_pending: a swap was asked for and has not happened yet
  *   0x0008  CTRL     RW  bit 0 enable (reset 0): 0 shows black; the syncs run all the same
  *                        bit 1 swap: writing 1 asks for a buffer swap, writing 0 changes
  *                              nothing; reads as swap_pending
  *   0x000c  FRAME    RO  the number of frames whose visible part has been scanned out since
  *                        reset (it counts where vblank begins); 32 bits, wraps
  *   0x0010  SWAPS    RO  the number of swaps that have taken effect since reset; 32 bits, wraps
  *   0x0040  PALETTE[0..15]  RW  one word per color, bits [23:0] = 0xRRGGBB (reset 0);
  *                        bits [31:24] read 0
  *   0x4000  BUFFER0  WO  2,400 words (9,600 bytes), up to 0x657f
  *   0x8000  BUFFER1  WO  2,400 words (9,600 bytes), up to 0xa57f
  * }}}
  *
  * Every other offset, the two buffers included, reads 0, and a write to another offset or to a
  * read-only register is ignored.
  *
  * '''Pixel packing.''' Pixel (x, y) of a buffer, 0 <= x < 160 from the left and 0 <= y < 120
  * from the top, is in word `20 * y + x / 8` of that buffer, in bits `[4 * (x % 8) + 3 :
  * 4 * (x % 8)]`: the leftmost of the 8 pixels of a word is in its lowest 4 bits. As bytes in
  * little-endian order that is byte `80 * y + x / 2`, an even x in the low half of the byte. The
  * buffers are write-only, and only as whole words, so the memory needs one write port (the bus)
  * and one read port (the scan); software that wants to change single pixels keeps its own copy.
  * The memory is a `Mem`, read combinationally and registered at once (`pixel_word`), as in
  * [[Axi4LiteRam]] with `syncRead = false`: on an FPGA that is distributed RAM or flip-flops, not
  * a block RAM, whose synchronous read port would need the address one cycle earlier.
  *
  * '''Swap.''' A swap that was asked for takes effect where vblank begins (after the last
  * visible line), never while a picture is being scanned out: `front` flips, `SWAPS` counts, and
  * `swap_pending` falls. One request is remembered, not a count of them. A request made in the
  * very cycle in which vblank begins stays pending until the next vblank. The frame that follows
  * a swap shows the new front buffer from its first pixel to its last, provided software leaves
  * the front buffer and the palette alone during the frame. A pixel reads its buffer word one
  * pixel period before it is shown; a write to that word in that cycle is not seen by it.
  *
  * '''Outputs.''' See [[mycpu.common.VgaIO]]. Every video field has the same delay, one pixel
  * period, from the scan counters to the pins, so `active`, `x`, `y` and the syncs describe the
  * pixel that `rgb` shows.
  *
  * @param pixelDiv
  *   clock cycles per pixel, at least 1.
  * @param pixelShift
  *   for negative tests only: the picture is this many pixel periods late against the syncs,
  *   `active`, `x` and `y` (the pipeline misalignment that the golden-frame check must catch,
  *   T5-07). 0 in every real build.
  * @param mutant
  *   a known bug, for negative tests only ([[Vga.mutants]]): `vga-no-id` reads 0 from the ID
  *   register. `None` in every real build.
  */
class Vga(pixelDiv: Int = 2, pixelShift: Int = 0, mutant: Option[String] = None) extends Module {
  require(pixelDiv >= 1, "a pixel takes at least one clock cycle")
  require(pixelShift >= 0, "the picture can only be late")
  require(mutant.forall(Vga.mutants.contains), s"unknown mutant $mutant")
  import Vga._

  val io = IO(new Bundle {
    val port  = Flipped(new riscv.MemPort)
    val video = Output(new VgaIO)
  })

  // The pixel clock enable: the last cycle of each pixel period.
  val tick = if (pixelDiv == 1) true.B else Counter(true.B, pixelDiv)._2

  // Registers that software sees.
  val enable       = RegInit(false.B)
  val swap_pending = RegInit(false.B)
  val front        = RegInit(false.B)
  val frame_count  = RegInit(0.U(32.W))
  val swap_count   = RegInit(0.U(32.W))
  val palette      = RegInit(VecInit(Seq.fill(PaletteEntries)(0.U(24.W))))

  // Both buffers in one memory of 2 x `BufferSpan` words, each in a span of its own, of which it
  // uses the first 2,400: an address is the buffer number followed by the word number. The scan
  // also reads during blanking, at word numbers up to 20 * 131 + 24, which the span covers; those
  // words are never shown.
  val BufferSpan                                  = 4096
  val framebuffer                                 = Mem(2 * BufferSpan, UInt(32.W))
  def buffer_addr(second: Bool, word: UInt): UInt =
    second ## word.pad(log2Ceil(BufferSpan))(log2Ceil(BufferSpan) - 1, 0)

  // The scan position, which steps once per pixel: `h` along the line and `v` down the frame,
  // both from 0, with the visible pixels first.
  val h = RegInit(0.U(log2Ceil(HTotal).W))
  val v = RegInit(0.U(log2Ceil(VTotal).W))

  // What the timing block says about the pixel at (`h`, `v`), before any pipeline delay.
  val h_active     = WireDefault(false.B)
  val v_active     = WireDefault(false.B)
  val hsync        = WireDefault(true.B)  // negative: low during the pulse
  val vsync        = WireDefault(true.B)
  val vblank_start = WireDefault(false.B) // the one cycle in which the scan leaves the last visible line

  // In every cycle with `tick` the scan position advances by one pixel: `h` runs through a whole
  // line, then `v` advances, and after the last line both start again at 0. The numbers are those
  // of `object Vga`.
  // [CA26: Exercise 5.7] Video timing: scan counters, visible area and sync pulses
  // Contract: `h` and `v` count as described above. `h_active` and `v_active` say whether
  // (`h`, `v`) is in the visible columns and lines. `hsync` and `vsync` are low exactly during
  // their pulses, each after the front porch of its direction; `vsync` changes only where a line
  // begins. `vblank_start` is high in the one cycle, a `tick` cycle, whose step takes the scan
  // out of the last pixel of the last visible line (VESA 640x480@60 timing; bootcamp 2.4 for
  // counters and widths; handout §T5.6).
  ?

  // The picture's pipeline stage. The framebuffer memory is read once per pixel, in the cycle
  // with `tick`, for the pixel at (`h`, `v`), and the word read is registered in `pixel_word`
  // together with the place of the pixel in that word, `pixel_lane` (0 for bits [3:0]). So the
  // picture reaches the outputs one pixel period after the scan counters. `pixel_index` is the
  // palette index of the pixel that the video outputs show in this cycle.
  val pixel_word  = Reg(UInt(32.W))
  val pixel_lane  = Reg(UInt(log2Ceil(PixelsPerWord).W))
  val pixel_index = WireDefault(0.U(4.W))

  // [CA26: Exercise 5.8] Pixel fetch: framebuffer address, x4 scaling and pipeline alignment
  // Contract: at each `tick`, `pixel_word` and `pixel_lane` load the word of the front buffer,
  // and the place in it, of the framebuffer pixel under screen position (`h`, `v`): a
  // framebuffer pixel covers 4 x 4 screen pixels ("Pixel packing" above). `pixel_index` is that
  // pixel's 4 bits. `io.video.hsync`, `vsync`, `active`, `x` and `y` describe the same pixel as
  // `pixel_index`: every output has the picture's pipeline depth, in every line, the first and
  // the last column included (bootcamp 2.4 for registers with an enable; handout §T5.7).
  ?

  // The negative-test knob: the picture alone goes through `pixelShift` more registers.
  val shown_index = (0 until pixelShift).foldLeft(pixel_index)((index, _) => RegEnable(index, 0.U, tick))
  io.video.tick  := tick
  io.video.rgb   := Mux(io.video.active && enable, palette(shown_index), 0.U)
  io.video.swaps := swap_count

  // The bus: registers in the first quarter of the window, then the two buffers.
  val req      = io.port.req
  val write    = req.valid && req.bits.wstrb === "b1111".U
  val region   = req.bits.addr(15, 14)
  val offset   = req.bits.addr(13, 2) ## 0.U(2.W)
  val word     = req.bits.addr(13, 2)
  val in_regs  = region === 0.U
  val in_table = in_regs && offset >= MemMap.Vga.Palette.U && offset < (MemMap.Vga.Palette + 4 * PaletteEntries).U
  val entry    = req.bits.addr(5, 2)
  val status   = swap_pending ## front ## !v_active
  val ctrl     = swap_pending ## enable

  req.ready               := true.B
  io.port.resp.valid      := req.valid
  io.port.resp.bits.error := false.B
  io.port.resp.bits.rdata := 0.U
  when(in_regs) {
    io.port.resp.bits.rdata := MuxLookup(offset, 0.U)(
      Seq(
        MemMap.Vga.Id.U     -> (if (mutant.contains("vga-no-id")) 0.U(32.W) else Id.U(32.W)),
        MemMap.Vga.Status.U -> status,
        MemMap.Vga.Ctrl.U   -> ctrl,
        MemMap.Vga.Frame.U  -> frame_count,
        MemMap.Vga.Swaps.U  -> swap_count
      )
    )
    when(in_table) { io.port.resp.bits.rdata := palette(entry) }
  }

  val ctrl_write = write && in_regs && offset === MemMap.Vga.Ctrl.U
  when(ctrl_write) { enable := req.bits.wdata(CtrlEnable) }
  when(write && in_table) { palette(entry) := req.bits.wdata(23, 0) }
  when(write && (region === 1.U || region === 2.U) && word < WordsPerBuffer.U) {
    framebuffer.write(buffer_addr(region === 2.U, word), req.bits.wdata)
  }

  when(vblank_start) { frame_count := frame_count + 1.U }
  // [CA26: Exercise 5.9] Buffer swap at vblank
  // Contract: a write of 1 to `CTRL.swap` is remembered in `swap_pending`. A remembered request
  // takes effect in the cycle with `vblank_start` and at no other time: the other buffer becomes
  // the front buffer, `swap_count` counts it, and the request is forgotten. A request written in
  // that same cycle is not lost: it is still pending afterwards (bootcamp 2.3: the last
  // connection wins; handout §T5.8).
  ?
}
