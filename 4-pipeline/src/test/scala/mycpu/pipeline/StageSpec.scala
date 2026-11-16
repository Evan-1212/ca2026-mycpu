// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.csrtrap.HpmEvents
import org.scalatest.flatspec.AnyFlatSpec

/** A [[Stage]] register of 8-bit values with its inputs and its contents on ports. */
class StageBox extends Module {
  val io = IO(new Bundle {
    val in     = Input(new Slot(UInt(8.W)))
    val enable = Input(Bool())
    val flush  = Input(Bool())
    val out    = Output(new Slot(UInt(8.W)))
  })
  io.out := Stage(io.in, io.enable, io.flush)
}

/** T4-01: the stage register takes its input with `enable`, a flush bubble with `flush` (also
  * together with `enable`), and otherwise holds; a bubble keeps the event it was made for.
  */
class StageSpec extends AnyFlatSpec with ChiselSim {
  private val other   = HpmEvents.Other
  private val flushed = HpmEvents.FlushBubble

  "A stage register" should "start as a bubble of the pipeline's fill, take, hold and flush" in {
    simulate(new StageBox) { c =>
      def drive(valid: Boolean, bits: Int, bubble: Int, enable: Boolean, flush: Boolean): Unit = {
        c.io.in.valid.poke(valid.B)
        c.io.in.bits.poke(bits.U)
        c.io.in.bubble.poke(bubble.U)
        c.io.enable.poke(enable.B)
        c.io.flush.poke(flush.B)
        c.clock.step()
      }
      def expect(valid: Boolean, bits: Option[Int], bubble: Option[Int]): Unit = {
        c.io.out.valid.expect(valid.B)
        bits.foreach(b => c.io.out.bits.expect(b.U))
        bubble.foreach(b => c.io.out.bubble.expect(b.U))
      }
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)
      expect(valid = false, Some(0), Some(other))

      drive(valid = true, 0x11, 0, enable = false, flush = false)
      expect(valid = false, Some(0), Some(other)) // no enable: it holds the reset bubble
      drive(valid = true, 0x11, 0, enable = true, flush = false)
      expect(valid = true, Some(0x11), None) // takes the instruction
      drive(valid = true, 0x22, 0, enable = false, flush = false)
      expect(valid = true, Some(0x11), None) // holds it
      drive(valid = false, 0x33, HpmEvents.LoadUseStall, enable = true, flush = false)
      expect(valid = false, None, Some(HpmEvents.LoadUseStall)) // a bubble keeps its event
      drive(valid = true, 0x44, 0, enable = true, flush = false)
      expect(valid = true, Some(0x44), None)
      drive(valid = true, 0x55, 0, enable = true, flush = true)
      expect(valid = false, None, Some(flushed)) // the flush wins over the enable
      drive(valid = true, 0x66, 0, enable = true, flush = false)
      drive(valid = true, 0x77, 0, enable = false, flush = true)
      expect(valid = false, None, Some(flushed)) // a flush empties a register that holds
      drive(valid = false, 0, HpmEvents.DividerBusy, enable = true, flush = true)
      expect(valid = false, None, Some(flushed)) // and is counted as a flush, whatever came in
      drive(valid = false, 0, HpmEvents.InstructionWait, enable = true, flush = false)
      expect(valid = false, None, Some(HpmEvents.InstructionWait))
    }
  }
}
