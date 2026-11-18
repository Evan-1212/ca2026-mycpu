// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import chisel3._
import chisel3.simulator.PeekPokeAPI._
import chisel3.util.Valid
import riscv.{Device, Halt, HaltCause, LoadPort, MemAccess}

import scala.collection.mutable
import scala.language.implicitConversions

/** The ports `CoSim2` needs of a system: the load port (written while reset is held), the retire
  * port and whether the core has halted.
  */
class RetireIO extends Bundle {
  val load   = Input(Valid(new LoadPort))
  val retire = Output(Valid(new TrapRetire))
  val halted = Output(Bool())
}

/** A core with its memory, as `CoSim2` drives it, when it is not a full [[SimTop]]. */
abstract class RetireSystem extends Module {
  val io = IO(new RetireIO)

  /** Where the core starts: a program's entry point must be this address. */
  def resetPc: Long = MemMap.ramBase
}

/** What `CoSim2` drives and watches; a [[RetireSystem]] or a [[SimTop]] converts to it. `exit` is
  * SimCtrl's `EXIT` event, if the system has one; `resetPc` is where the core starts.
  */
case class CoSimPorts(
    clock: Clock,
    reset: Reset,
    load: Valid[LoadPort],
    retire: Valid[TrapRetire],
    halted: Bool,
    exit: Option[Valid[UInt]],
    resetPc: Long = MemMap.ramBase
)

object CoSimPorts {
  implicit def fromRetireSystem(c: RetireSystem): CoSimPorts =
    CoSimPorts(c.clock, c.reset, c.io.load, c.io.retire, c.io.halted, None, c.resetPc)
  implicit def fromSimTop(c: SimTop): CoSimPorts =
    CoSimPorts(c.clock, c.reset, c.io.load, c.io.retire, c.io.halted, Some(c.io.exit), c.ramBase)
}

/** What a system shows in one clock cycle: the record it retires, if any, with the `mip` field of
  * that record (`TrapRetire.mip`: what the instruction saw pending; 0 in a cycle without a
  * record), SimCtrl's `EXIT` event with its code, and whether the core says it has halted.
  */
case class CoSimCycle(
    retire: Option[IssRetire] = None,
    mip: Long = 0,
    exit: Option[Int] = None,
    halted: Boolean = false
)

/** The timing that `CoSim2` allows around the end of a program, in clock cycles:
  *   - `exitLead`: SimCtrl's `EXIT` event may show at most this long before the record of the
  *     store that causes it retires (a pipeline stores in an earlier stage than it retires in).
  *     Until then every record is compared as usual, and the store must follow.
  *   - `exitLag`: the event may show at most this long after that record. No record may retire
  *     after the store to `EXIT`, in these cycles or later.
  *   - `haltDrain`: after the instruction that halts a core (T1, T2), nothing may retire for this
  *     long, which is as long as `CoSim2` looks.
  */
case class CoSimRules(exitLead: Int = 8, exitLag: Int = 0, haltDrain: Int = 8)

/** What a run of `CoSim2` gives: the records the core retired (in a comparison, those that agreed
  * with the model), of which `trace` has the last ones and `records` the number (`trace` has them
  * all unless `replay` was told to keep fewer); the cycles from the first cycle after reset to
  * the one in which the program ended or the run was given up, both included (the cycles looked
  * at after a halt do not count); the SimCtrl exit code if the program exited; and why the run
  * failed, if it did: the first difference from the golden model, or a program that did not end.
  */
case class CoSimResult(trace: Seq[IssRetire], records: Long, cycles: Int, exit: Option[Int], mismatch: Option[String])

/** What the history of a free-running 64-bit counter allows it to hold: it is 0 at reset, never
  * runs backward, and gains at most one per clock cycle (true of `mcycle`, of `mtime` at any tick,
  * of every hpm event and of SimCtrl `CYCLE`). The counter is read and written one 32-bit half at
  * a time, by an instruction whose record retires in cycle `cycle`, the record before it in cycle
  * `since`: such an instruction acts after `since` (T4-04 serializes it) and by `cycle`. A counter
  * is taken not to gain 2^32 between two reads.
  */
private[common] final class CounterBound {
  import CounterBound._
  private var min, max       = BigInt(0) // the value is in [min, max + (now - stamp)]
  private var stamp          = 0L
  private def top(now: Long) = max + (now - stamp)

  /** Takes a read that shows the bits `mask` of the `high` or low half to be those of `v`; an error
    * if no value the counter may hold has them.
    */
  def read(high: Boolean, v: Long, cycle: Long, since: Long, mask: Long = Half): Option[String] = {
    val shift = if (high) 32 else 0
    val limit = top(cycle)
    val value = atLeast(min, BigInt(mask) << shift, BigInt(v & mask) << shift)
    if (value > limit) {
      val bits = if (mask == Half) "" else f" in its bits 0x$mask%08x"
      Some(
        f"the ${if (high) "high" else "low"} half reads 0x${v & mask}%08x$bits, but the counter is between " +
          f"0x$min%x and 0x$limit%x: it cannot run backward, nor gain more than one per clock cycle"
      )
    } else {
      min = value
      // a whole low half is the value itself; a whole high half leaves the low half open
      max = if (mask != Half) limit else if (high) limit.min(value | Half) else value
      stamp = since
      None
    }
  }

  /** Takes a write of `v` to the `high` or low half. */
  def write(high: Boolean, v: Long, cycle: Long, since: Long): Unit = {
    val limit = top(cycle)
    if (!high) { min = (min >> 32 << 32) | v; max = (limit >> 32 << 32) | v }
    else if ((min >> 32) == (limit >> 32)) {
      min = (min & Half) | (BigInt(v) << 32)
      max = (limit & Half) | (BigInt(v) << 32)
    } else { min = BigInt(v) << 32; max = min | Half } // the low half may have wrapped: any low half
    stamp = since
  }
}

private[common] object CounterBound {

  /** The bits of a whole half. */
  val Half = 0xffffffffL

  /** The smallest x from `a` on whose bits `m` are `t` (`t` has no bit outside `m`; the bits above
    * `m` are free, so there is one).
    */
  def atLeast(a: BigInt, m: BigInt, t: BigInt): BigInt = {
    val differ = (a ^ t) & m
    if (differ == 0) a
    else {
      // the highest bit of m in which a is not t; x keeps a above bit k, has bit k set, and below
      // it the bits of t, with zeros outside m
      def raised(k: Int) = (a >> (k + 1) << (k + 1)) | (BigInt(1) << k) | (t & ((BigInt(1) << k) - 1))
      val i              = differ.bitLength - 1
      if (t.testBit(i)) raised(i) // a has 0 where t has 1: that bit makes x larger
      else {                      // a has 1 where t has 0: x is larger in a free bit above, the lowest that is 0 in a
        var j = i + 1
        while (m.testBit(j) || a.testBit(j)) j += 1
        raised(j)
      }
    }
  }
}

/** The [[IssSample]] of a co-simulation: for the instruction being compared it gives the model
  * what the hardware showed, and it checks those values against what history determines. A check
  * that fails is an `error`, which `CoSim2` reports as a mismatch.
  *
  * '''Sampled without a check''' (nothing determines them):
  *   - `mip.MTIP`, and `mip.MEIP` while SimCtrl `MEIP` is clear (a UART may drive it, T5), from
  *     the `mip` field of the record (`TrapRetire.mip`). The model, not the hardware, then decides whether an interrupt
  *     is taken and which. An interrupt record that `mip`, `mie`, `mstatus.MIE` and the priority
  *     of T3-03 do not justify therefore differs from the model's record, and so does an
  *     instruction that retires while an interrupt is due.
  *   - loads of `Input` registers (UART `RXDATA` and `STATUS`), from the record's `mem.data`. The
  *     model keeps only the bits that the register has, so a load that shows another bit set
  *     differs from the model's record.
  *
  * '''Sampled and checked''': reads of the free-running counters, from the record's `rdValue` or
  * `mem.data`: `mcycle`, `time` (the CLINT's `mtime`), the programmable counters of `Counters` and SimCtrl `CYCLE`.
  * Each must stay within its [[CounterBound]]: not below the last value read or written, and not
  * above it by more than the clock cycles since. When a CSR instruction with rd = x0 sets or
  * clears bits in a counter, only `old | operand` or `old & ~operand` shows (the record's
  * `csr.wdata`): the bits of the old value outside `operand` are checked in the same way, and the
  * bits under it cannot be known. A load narrower than a word is not checked. `mcountinhibit` is
  * not applied: that a counter stops, and exact cycle counts, are for `CounterSpec` (INF-10).
  *
  * '''Checked, not sampled''': whenever the model uses `mip` (while `mstatus.MIE` is set, at a
  * WFI, for a read of `mip`), its MSIP must be bit 0 of the CLINT's `msip` as the program left it,
  * and its MEIP must be set if SimCtrl `MEIP` is. A WFI that retires although `mip & mie` was 0
  * has waited, and may end its wait only when `mip & mie` is not 0 (T3-02): the record after it
  * must show a `mip` with an enabled bit set. That record is enough: every interrupt line is a
  * level that only the program lowers (a store to the CLINT, to SimCtrl or to the UART's `CTRL`,
  * a load of the UART's `RXDATA`), and a record's `mip` is the one of its instruction's interrupt
  * decision, made before that instruction's own access (T4: `mem_mip`), so nothing can have
  * lowered a line between the WFI's wake and that `mip`. Everything else is the model's
  * own value, compared through the record: `minstret` and SimCtrl `INSTRET` (exact), a `mip` read,
  * and every register a program writes.
  */
final class HardwareSample extends IssSample {
  private var record       = Option.empty[IssRetire]
  private var mipNow       = 0L
  private var cycle, since = 0L                 // the cycle of the record being compared, and of the one before it
  private val bounds       = mutable.Map[Int, CounterBound]()
  private var failed       = Option.empty[String]
  private var awaited      = Option.empty[Long] // mie of the WFI before this record, if it waited
  private def fail(reason: String) = if (failed.isEmpty) failed = Some(reason)
  private def bound(id: Int)       = bounds.getOrElseUpdate(id, new CounterBound)
  private def counterName(id: Int) = id match {
    case Iss.simCtrlCycle => "SimCtrl CYCLE"
    case Iss.timeCounter  => "time"
    case n                => Csr.name(Csr.Mcycle + n)
  }

  /** The first check that failed for the record being compared. */
  def error: Option[String] = failed

  /** Starts the comparison of `record`, retired in clock cycle `cycle` (counted from 1 after
    * reset) with `mip` in its `mip` field. It is an `error` already if the record follows a
    * WFI that waited and nothing is there to have woken it.
    */
  def observe(record: IssRetire, mip: Long, cycle: Long): Unit = {
    this.record = Some(record)
    this.cycle = cycle
    mipNow = mip
    failed = awaited.filter(enabled => (mip & enabled) == 0).map { enabled =>
      f"the WFI before this record retired, but none of the interrupts it waited for (mie 0x$enabled%03x) " +
        f"is pending (mip 0x$mip%03x): a WFI stalls until one is (T3-02)"
    }
    awaited = None
  }

  /** Ends the comparison of a record that agreed with the record of `iss`: what it wrote to a
    * counter (a CSR write, a word stored to a `Counter` register) is what that counter now holds.
    */
  def agreed(iss: Iss): Unit = {
    for (r <- record) {
      r.csr match {
        case Some(CsrWrite(addr, wdata)) if Iss.sampled(addr) =>
          bound(addr & 0x1f).write(Iss.highHalf(addr), wdata, cycle, since)
        case _ =>
      }
      r.mem match {
        case Some(MemAccess(addr, true, 4, data)) =>
          iss.readKind(addr, 4) match {
            case Some(ReadKind.Counter(id, high)) => bound(id).write(high, data, cycle, since)
            case _                                =>
          }
        case _ =>
      }
    }
    since = cycle
  }

  private def counterRead(id: Int, high: Boolean, v: Long, mask: Long = CounterBound.Half): Unit =
    bound(id).read(high, v, cycle, since, mask).foreach(e => fail(s"${counterName(id)}: $e"))

  def mip(modeled: Long): Long = {
    val (msip, meip) = (1L << Trap.Msi, 1L << Trap.Mei)
    if (((mipNow ^ modeled) & msip) != 0) {
      val stored = modeled >> Trap.Msi & 1
      fail(f"mip 0x$mipNow%03x: MSIP is not bit 0 of the CLINT's msip, which the program left $stored")
    }
    if ((modeled & ~mipNow & meip) != 0) fail(f"mip 0x$mipNow%03x: MEIP is clear although SimCtrl MEIP is set")
    mipNow
  }

  def csrRead(addr: Int, modeled: Long, op: Char, operand: Long): Long = record match {
    case Some(r) if r.rd != 0 =>
      Iss.counterOf(addr).foreach(counterRead(_, Iss.highHalf(addr), r.rdValue))
      r.rdValue
    case Some(r) if op != 'w' && r.csr.exists(_.addr == addr) =>
      // rd = x0: wdata is old | operand or old & ~operand, which shows the old bits outside operand
      val wdata = r.csr.get.wdata
      val known = ~operand & CounterBound.Half
      Iss.counterOf(addr).foreach(counterRead(_, Iss.highHalf(addr), wdata, known))
      wdata & known
    case _ => modeled // nothing shows what was read (and a csrrw does not depend on it)
  }

  def load(addr: Long, bytes: Int, kind: ReadKind, modeled: Long): Long =
    record.flatMap(_.mem).collect { case MemAccess(`addr`, false, `bytes`, data) => data } match {
      case Some(data) =>
        kind match {
          case ReadKind.Counter(id, high) if bytes == 4 => counterRead(id, high, data)
          case _                                        =>
        }
        data
      case None => modeled // the hardware did something else: the records differ
    }

  def wfiWait(enabled: Long, timer: Option[Long]): Long = {
    awaited = Some(enabled) // the hardware has retired the WFI: what woke it shows at the next record
    0
  }
}

/** Runs programs on a simulated system (a [[RetireSystem]] or a [[SimTop]]) and compares what it
  * retires with the golden model ([[Iss]]), record by record, as the bootcamp's `riscv.CoSim` does
  * for its cores. Use it inside `simulate(new MySystem) { c => ... }`. The comparison itself is
  * `replay`, a function of what the system showed cycle by cycle, which needs no hardware.
  */
object CoSim2 {

  /** Writes `image` through the load port with reset held, then releases reset. The rest of the
    * memory keeps what it held, where the model has zeros: a program must not read memory that it
    * did not load or store (an ELF's BSS is in its image, as zeros). The core starts at its reset
    * pc whatever the image says, so an image with another entry point is refused.
    */
  def load(c: CoSimPorts, image: MemImage): Unit = {
    require(
      image.entry == c.resetPc,
      f"the entry point 0x${image.entry}%08x is not the reset pc 0x${c.resetPc}%08x of the system"
    )
    c.reset.poke(true.B)
    c.load.valid.poke(true.B)
    for ((addr, word) <- image.words) {
      c.load.bits.addr.poke(addr.U)
      c.load.bits.data.poke(word.U)
      c.clock.step()
    }
    c.load.valid.poke(false.B)
    c.clock.step() // reset the core once more, now that the program is in place
    c.reset.poke(false.B)
  }

  private val causes = Map(
    HaltCause.Ecall.litValue      -> Halt.Ecall,
    HaltCause.Ebreak.litValue     -> Halt.Ebreak,
    HaltCause.Illegal.litValue    -> Halt.Illegal,
    HaltCause.Misaligned.litValue -> Halt.Misaligned,
    HaltCause.BadAddress.litValue -> Halt.BadAddress
  )

  /** The halt that the `halt` field of a record reports as `value`, if any. A value that
    * `riscv.HaltCause` does not define is an error of the core, not a record without a halt.
    */
  private[common] def haltOf(value: BigInt): Option[Halt] =
    if (value == HaltCause.Running.litValue) None
    else
      causes.get(value).orElse {
        throw new IllegalStateException(s"the core reports halt cause $value, which riscv.HaltCause does not define")
      }

  private def long(u: UInt): Long    = u.peek().litValue.toLong
  private def bool(b: Bool): Boolean = b.peek().litToBoolean

  /** The record the system retires in this cycle, if any. */
  def retired(c: CoSimPorts): Option[IssRetire] = Option.when(bool(c.retire.valid)) {
    val r = c.retire.bits
    IssRetire(
      pc = long(r.pc),
      inst = long(r.inst),
      nextPc = long(r.nextPc),
      rd = long(r.rd).toInt,
      rdValue = long(r.rdValue),
      mem = Option.when(bool(r.mem.valid))(
        MemAccess(long(r.mem.bits.addr), bool(r.mem.bits.write), long(r.mem.bits.bytes).toInt, long(r.mem.bits.data))
      ),
      halt = haltOf(r.halt.peek().litValue),
      trap = Option.when(bool(r.trap))(Trap(long(r.cause), long(r.tval))),
      csr = Option.when(bool(r.csr.valid))(CsrWrite(long(r.csr.bits.addr).toInt, long(r.csr.bits.wdata)))
    )
  }

  /** What the system shows in this cycle. */
  def observe(c: CoSimPorts): CoSimCycle = {
    val record = retired(c) // before `halted`: a core may raise it in the cycle its halt retires
    CoSimCycle(
      record,
      mip = if (record.isDefined) long(c.retire.bits.mip) else 0,
      exit = c.exit.filter(e => bool(e.valid)).map(e => long(e.bits).toInt),
      halted = bool(c.halted)
    )
  }

  /** Runs the loaded program until a halt retires, the core halts or SimCtrl `EXIT` is written, for
    * at most `maxCycles` cycles. Nothing is compared: the result's `mismatch` only says whether
    * the program failed to end in that time.
    */
  def run(c: CoSimPorts, maxCycles: Int = 100000): CoSimResult = {
    val (seen, done) = cycles(c, maxCycles)
    val trace        = seen.flatMap(_.retire)
    val timeout      = Option.when(!done)(s"the program did not end within $maxCycles cycles (${trace.size} records)")
    CoSimResult(trace, trace.size, seen.size, seen.lastOption.flatMap(_.exit), timeout)
  }

  /** What the system shows in each cycle of the loaded program, up to the one in which a halt
    * retires, the core halts or SimCtrl `EXIT` is written, or `maxCycles` cycles; and whether it
    * ended. This is the input of `replay`.
    */
  def cycles(c: CoSimPorts, maxCycles: Int = 100000): (Seq[CoSimCycle], Boolean) = {
    val seen = mutable.ArrayBuffer[CoSimCycle]()
    var done = false
    while (!done && seen.size < maxCycles) {
      val now = observe(c)
      seen += now
      done = now.retire.exists(_.halt.isDefined) || now.halted || now.exit.isDefined
      if (!done) c.clock.step() // stay on the halt: the state can be read
    }
    (seen.toSeq, done)
  }

  /** As `run`: the records and the number of cycles. */
  def timed(c: CoSimPorts, maxCycles: Int = 100000): (Seq[IssRetire], Int) = {
    val r = run(c, maxCycles)
    (r.trace, r.cycles)
  }

  /** A golden model for `check`: `config`, a RAM of `size` bytes at `MemMap.ramBase`, and
    * `devices` (evaluated anew for each model).
    */
  def model(
      config: IssConfig = IssConfig.T1,
      size: Int = MemMap.ramBytes,
      devices: => Seq[Device] = Nil
  ): IssSample => Iss = sample => new Iss(config, MemMap.ramBase, size, devices, sample)

  /** Compares what a system showed, cycle by cycle from the first cycle after reset (`cycles`),
    * with the model that `newModel` builds running `image`. The model gets the sample through
    * which it sees the hardware, a [[HardwareSample]], whose comment says which values are
    * sampled and which are checked. `hasExit` says whether the system can show SimCtrl's `EXIT`
    * event.
    *
    * Every record must equal the model's record of the same step, and the values sampled for it
    * must pass the sample's checks. The comparison ends without a mismatch when
    *   - a halt retires that the model has too, and nothing retires in the `rules.haltDrain`
    *     cycles after it; or
    *   - the model's program stores to `EXIT` and the system shows the event with the same code,
    *     within the cycles that `rules` allows around the store's record (a system without the
    *     event is not asked for it).
    *
    * It ends with a mismatch at the first record that differs or fails a check; at a record that
    * retires after the halt or after the store to `EXIT`; at an `EXIT` event that no modeled
    * store explains, or a store whose event does not show; when the core halts and the model does
    * not; and when `cycles` or `maxCycles` runs out first. A mismatch names the index of the
    * record and its cycle, and shows both records, every field that differs with its raw values,
    * the interrupt state the model decided on, and the `context` records before.
    *
    * `keep` bounds the memory of a long comparison: the result's `trace` has only the last `keep`
    * records that agreed (all of them by default, which suits the runs of a spec; a replay of the
    * millions of records of a `+retire-log` keeps a few). Its `records` counts them all.
    */
  def replay(
      cycles: Iterator[CoSimCycle],
      image: MemImage,
      newModel: IssSample => Iss = model(),
      hasExit: Boolean = true,
      maxCycles: Int = 100000,
      context: Int = 4,
      rules: CoSimRules = CoSimRules(),
      keep: Int = Int.MaxValue
  ): CoSimResult = {
    require(keep >= 0 && context >= 0, s"keep $keep and context $context are numbers of records")
    val sample  = new HardwareSample
    val iss     = newModel(sample).load(image)
    val window  = keep.max(context).max(1)        // the last record is looked at below
    val trace   = mutable.ArrayDeque[IssRetire]() // the last `window` records that agreed
    var records = 0L                              // how many agreed
    var cycle   = 0
    var event   = Option.empty[(Int, Int)]        // the EXIT event: its code and its cycle
    var stored  = Option.empty[Int]               // the cycle of the model's store to EXIT
    var halt    = Option.empty[Int]               // the cycle in which the core halted

    def result(mismatch: Option[String]) =
      CoSimResult(
        trace.takeRight(keep).toSeq,
        records,
        halt.getOrElse(cycle),
        event.map(_._1).orElse(iss.exit),
        mismatch
      )
    def differ(what: String)                       = result(Some(what))
    def lines(title: String, items: Iterable[Any]) =
      if (items.isEmpty) "" else items.map(i => s"\n    $i").mkString(s"\n  $title:", "", "")
    def at(detail: String) =
      differ(s"instruction $records (cycle $cycle):$detail" + lines("before it", trace.takeRight(context)))

    while (cycles.hasNext && cycle < maxCycles) {
      val now = cycles.next()
      cycle += 1
      now.retire match {
        case Some(core) if halt.isDefined =>
          return differ(s"a record retired after the one that halted the core:\n  $core")
        case Some(core) if stored.isDefined =>
          return differ(s"a record retired after the store to EXIT (cycle ${stored.get}):\n  $core")
        case Some(core) =>
          // what the model decides on, read before the step changes it
          val traps   = iss.config.zicsr
          val mstatus = if (traps) iss.csr(Csr.Mstatus) else 0L
          val mie     = if (traps) iss.csr(Csr.Mie) else 0L
          def state   =
            if (traps) f"\n  the model had mstatus 0x$mstatus%x, mie 0x$mie%03x and (sampled) mip 0x${now.mip}%03x"
            else ""
          def failed = sample.error.map(e => at(s" $e\n  core:  $core" + state))
          sample.observe(core, now.mip, cycle)
          if (failed.isDefined) return failed.get
          val golden = iss.step()
          if (golden != core)
            return at(
              s"\n  core:  $core\n  model: $golden" +
                lines("fields that differ", IssRetire.diff(core, golden)) + state
            )
          if (failed.isDefined) return failed.get
          sample.agreed(iss)
          if (trace.size == window) trace.removeHead()
          trace += core
          records += 1
          if (iss.exit.isDefined) stored = Some(cycle)
        case None =>
      }
      if (event.isEmpty) event = now.exit.map(_ -> cycle)

      if (halt.isEmpty && (now.halted || trace.lastOption.exists(_.halt.isDefined))) {
        if (iss.halted.isEmpty) return differ(s"the core halted after $records records; the model did not")
        halt = Some(cycle)
      }
      // a halted model stores nothing more, so nothing can explain an EXIT event any longer
      if (halt.isDefined && event.isDefined && stored.isEmpty) {
        val (e, since) = event.get
        return differ(s"the core raised EXIT ($e) in cycle $since; the model's program halted without a store to EXIT")
      }
      if (halt.exists(cycle - _ >= rules.haltDrain)) return result(None)

      (event, stored) match {
        case (Some((e, since)), _) if !iss.canExit =>
          return differ(s"the core raised EXIT ($e) in cycle $since; the model has no SimCtrl to exit through")
        case (Some((e, _)), Some(_)) =>
          return result(Option.when(!iss.exit.contains(e))(s"the core exited with $e, the model with ${iss.exit.get}"))
        case (Some((e, since)), None) if cycle - since >= rules.exitLead =>
          return differ(
            s"the core raised EXIT ($e) in cycle $since; ${rules.exitLead} cycles later the model's program " +
              s"has not stored to EXIT ($records records)"
          )
        case (None, Some(_)) if !hasExit                           => return result(None)
        case (None, Some(since)) if cycle - since >= rules.exitLag =>
          return differ(
            s"the model's program stored ${iss.exit.get} to EXIT (record ${records - 1}, cycle $since); " +
              "the core did not raise EXIT"
          )
        case _ =>
      }
    }
    if (halt.isDefined) result(None) // the cycles ran out while nothing retired after the halt
    else if (cycles.hasNext)
      differ(s"the program did not end within $maxCycles cycles (the core retired $records records)")
    else differ(s"the cycles ended after $records records, before the program did")
  }

  /** Loads `image`, runs it on the system and compares it with the model that `newModel` builds:
    * `replay` of what the system shows from the first cycle after reset.
    */
  def check(
      c: CoSimPorts,
      image: MemImage,
      newModel: IssSample => Iss = model(),
      maxCycles: Int = 100000,
      context: Int = 4,
      rules: CoSimRules = CoSimRules()
  ): CoSimResult = {
    load(c, image)
    var first  = true
    val cycles = Iterator.continually {
      if (first) first = false else c.clock.step()
      observe(c)
    }
    replay(cycles, image, newModel, c.exit.isDefined, maxCycles, context, rules)
  }

  /** As `check`: the first difference, if any. */
  def compare(
      c: CoSimPorts,
      image: MemImage,
      newModel: IssSample => Iss = model(),
      maxCycles: Int = 100000
  ): Option[String] = check(c, image, newModel, maxCycles).mismatch
}
