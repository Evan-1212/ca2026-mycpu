// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._

/** The events that the programmable counters of `mycpu.common.Counters` (`mhpmcounter3..10`) can
  * count (T3-04). A counter counts the event whose ID software writes to its `mhpmevent`
  * register; ID 0 means "no event", and so do the IDs 9 to 15, which the 4-bit register holds but
  * no event has.
  *
  * Six of the events are a '''partition''' of the cycles in which no instruction retires: each
  * such cycle is counted by exactly one of them, so with one counter per event the six counts and
  * `minstret` add up to `mcycle` over a run in which nothing is inhibited or written. When a cycle
  * has more than one reason, the first in this order wins: D-side wait, I-side wait, divider busy,
  * load-use stall, flush bubble, other (a cycle in which a trap is taken retires nothing, so it is
  * in the partition too).
  *
  * The two other events count occurrences and are outside the partition: a taken branch or jump,
  * and a trap taken (an exception or an interrupt).
  */
object HpmEvents {

  /** No event: the counter stands still. */
  val NoEvent = 0

  // The IDs are D12's (1 to 8 in the order below) unless the student's configuration permutes
  // them (EX-08, mycpu.common.StudentConfig); every user of an event names it, never its number.
  private def id(name: String): Int = mycpu.common.StudentConfig.hpmEvents(name)

  /** A cycle in which the divider holds the core (T2-03). */
  val DividerBusy: Int = id("divider_busy")

  /** A branch that is taken, or a jump (an occurrence, outside the partition). */
  val TakenBranch: Int = id("taken_branch")

  /** A trap taken: an exception or an interrupt (an occurrence, outside the partition). */
  val TrapTaken: Int = id("trap_taken")

  /** A cycle lost to a load-use stall (T4). */
  val LoadUseStall: Int = id("load_use_stall")

  /** A cycle in which the retiring stage holds a bubble made by a flush (T4). */
  val FlushBubble: Int = id("flush_bubble")

  /** A cycle in which the core waits for the instruction memory (T4, T5). */
  val InstructionWait: Int = id("instruction_wait")

  /** A cycle in which the core waits for the data memory or a device (T4, T5). */
  val DataWait: Int = id("data_wait")

  /** Any other cycle without a retired instruction: a WFI that waits, the cycle of a trap; in T3 a
    * device wait; in the pipelines the filling after reset and a serialization.
    */
  val Other: Int = id("other")

  /** The number of events; event `id` is element `id - 1` of an event vector. */
  val count = 8

  /** The events of the partition, highest priority first. */
  val partition: Seq[Int] = Seq(DataWait, InstructionWait, DividerBusy, LoadUseStall, FlushBubble, Other)

  /** The events that count occurrences. */
  val occurrences: Seq[Int] = Seq(TakenBranch, TrapTaken)

  /** The name of event `id`, as `docs/hpm-events.md` lists it. */
  def name(id: Int): String = id match {
    case NoEvent         => "none"
    case DividerBusy     => "divider busy"
    case TakenBranch     => "taken branch or jump"
    case TrapTaken       => "trap taken"
    case LoadUseStall    => "load-use stall"
    case FlushBubble     => "flush bubble"
    case InstructionWait => "I-side wait"
    case DataWait        => "D-side wait"
    case Other           => "other"
    case _               => throw new IllegalArgumentException(s"there is no hpm event $id")
  }

  /** An event vector, as [[CsrFile]] takes it: element `id - 1` is high in a cycle that event
    * `id` counts.
    */
  def vector: Vec[Bool] = Vec(count, Bool())

  /** The pulse of event `id` in `events`. */
  def pulse(events: Vec[Bool], id: Int): Bool = {
    require(id >= 1 && id <= count, s"there is no hpm event $id")
    events(id - 1)
  }
}

/** What a core reports about one clock cycle, for [[HpmClassifier]]. A core that cannot be in some
  * state ties that input low (T3 has no load-use stall, flush bubble or memory wait).
  *   - `retired`: an instruction retires in this cycle (the pulse [[CsrFile]] takes too). As
  *     there, a trap wins: with `trap_taken` the cycle counts as one without a retired
  *     instruction, whatever `retired` is;
  *   - `data_wait`, `instruction_wait`: the core waits for an answer on its data or instruction port;
  *   - `divider_busy`: the divider holds the core;
  *   - `load_use_stall`: decode is held for a load in execute;
  *   - `flush_bubble`: the retiring stage holds a bubble that a flush made;
  *   - `taken_branch`: the instruction of this cycle is a taken branch or a jump. It counts only
  *     if that instruction retires;
  *   - `trap_taken`: an exception or an interrupt is taken in this cycle, the cycle in which the
  *     core raises `trap.exception` or `trap.interrupt` of [[CsrFile]].
  */
class CycleStatus extends Bundle {
  val retired          = Bool()
  val data_wait        = Bool()
  val instruction_wait = Bool()
  val divider_busy     = Bool()
  val load_use_stall   = Bool()
  val flush_bubble     = Bool()
  val taken_branch     = Bool()
  val trap_taken       = Bool()
}

/** The cycle classifier of every core (T3-04): from the status of one cycle, the event vector for
  * [[CsrFile]]. It is combinational.
  *
  * A cycle in which an instruction retires raises no partition event. Any other cycle raises
  * exactly one, the first of [[HpmEvents.partition]] whose reason holds, and `Other` when none
  * does. Of the occurrence events, `TrapTaken` follows its input, and `TakenBranch` is raised
  * only with an instruction that retires: a branch that a core resolves in a cycle in which it
  * does not retire (a stall, or a jump that traps on its target) is not counted.
  */
class HpmClassifier extends RawModule {
  val io = IO(new Bundle {
    val status = Input(new CycleStatus)
    val events = Output(HpmEvents.vector)
  })

  private val s = io.status
  // the reason of each partition event, in priority order; `Other` needs none
  private val reasons = Seq(
    HpmEvents.DataWait        -> s.data_wait,
    HpmEvents.InstructionWait -> s.instruction_wait,
    HpmEvents.DividerBusy     -> s.divider_busy,
    HpmEvents.LoadUseStall    -> s.load_use_stall,
    HpmEvents.FlushBubble     -> s.flush_bubble,
    HpmEvents.Other           -> true.B
  )
  require(reasons.map(_._1) == HpmEvents.partition, "the reasons must be in the order of the partition")

  private val retired = s.retired && !s.trap_taken
  // a reason counts only if no instruction retires and no reason before it holds
  private var open = !retired
  for ((id, reason) <- reasons) {
    HpmEvents.pulse(io.events, id) := open && reason
    open = open && !reason
  }
  HpmEvents.pulse(io.events, HpmEvents.TakenBranch) := s.taken_branch && retired
  HpmEvents.pulse(io.events, HpmEvents.TrapTaken)   := s.trap_taken
}
