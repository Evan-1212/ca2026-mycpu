// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3._
import mycpu.csrtrap.HpmEvents

/** What a stage register holds: an instruction (`valid`, with `bits`), or a bubble. A bubble
  * carries `bubble`, the hpm event that a cycle counts when this bubble is in the commit stage
  * (MEM) and nothing retires (`HpmEvents`: divider busy, load-use stall, flush bubble, I-side wait
  * or other), so every lost cycle is charged to the reason the bubble was made for (T3-04,
  * BEN-04). With `valid`, `bubble` means nothing.
  */
class Slot[T <: Data](gen: T) extends Bundle {
  val valid  = Bool()
  val bubble = UInt(Stage.eventBits.W)
  val bits   = gen
}

object Slot {

  /** A slot of `bits`: an instruction if `valid`, else a bubble made for event `bubble`. */
  def apply[T <: Data](valid: Bool, bits: T, bubble: UInt): Slot[T] = {
    val s = Wire(new Slot(chiselTypeOf(bits)))
    s.valid  := valid
    s.bubble := bubble
    s.bits   := bits
    s
  }
}

/** The stage registers of the pipeline (T4-01), the bootcamp's `riscv.Stage` (5.6) with a bubble
  * that says why it is one.
  */
object Stage {

  /** The width of an hpm event ID in a [[Slot]]. */
  val eventBits: Int = 4

  /** The event of the bubbles in the registers after reset: the pipeline fills ("other"). */
  def resetEvent: UInt = HpmEvents.Other.U(eventBits.W)

  /** The event of a bubble that a flush made. */
  def flushEvent: UInt = HpmEvents.FlushBubble.U(eventBits.W)

  /** A stage register that takes `next` at a clock edge with `enable`, or a bubble of
    * [[flushEvent]] at a clock edge with `flush`, and otherwise holds what it has. It starts as a
    * bubble of [[resetEvent]], with `bits` 0.
    */
  def apply[T <: Data](next: Slot[T], enable: Bool, flush: Bool): Slot[T] = {
    val empty = Wire(chiselTypeOf(next))
    empty        := 0.U.asTypeOf(empty)
    empty.bubble := resetEvent
    val r = RegInit(empty)
    // [CA26: Exercise 4.1] A stage register with enable and flush
    // Contract: at each clock edge the register takes `next` when `enable` is high, a bubble
    // when `flush` is high, and keeps what it holds when neither is. When both are high, the
    // instruction in `next` is on a path that must not go on (a flush is a cancellation that the
    // stage register itself performs), so the bubble wins; a flush bubble is counted as a flush
    // (its `bubble` is `flushEvent`). `bits` of a bubble mean nothing (bootcamp 5.6, `Stage`;
    // handout §T4.1).
    ?
    r
  }
}
