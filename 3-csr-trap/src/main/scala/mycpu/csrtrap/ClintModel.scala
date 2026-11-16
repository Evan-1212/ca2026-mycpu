// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import mycpu.common.{Iss, MemMap, ModelDevice, MtimeSource, ReadKind, Trap}

/** The CLINT in the golden model (D12, T3-05), the reference of [[Clint]]: `msip` bit 0 and the
  * two halves of `mtimecmp` (reset to all ones) are read back as stored; `mtime` is the counter
  * that the `time` CSR reads too (`Iss.timeCounter`), each half writable on its own. `MSIP` is
  * `msip` bit 0 and `MTIP` follows `mtime` and `mtimecmp` (Privileged spec §3.2.1).
  *
  * The model alone advances `mtime` by one tick every `tickDivisor` steps, as [[Clint]] does per
  * clock cycle: the first tick after `tickDivisor` steps, from a prescaler that a store to `mtime`
  * does not restart. A store takes effect first, and the time of its step then passes, so the
  * tick of that step counts on top of the stored value. In a co-simulation `mtime` is sampled
  * (`ReadKind.Counter`): only what the program stores and the timer interrupt it raises are
  * modeled.
  */
class ClintModel(base: Long = MemMap.clintBase, tickDivisor: Int = 1)
    extends ModelDevice(base, MemMap.clintSize)
    with MtimeSource {
  require(tickDivisor >= 1, "the tick divisor must be at least 1")
  import MemMap.Clint._

  /** `msip` bit 0, and the 64-bit `mtimecmp` and `mtime`. */
  var msip: Boolean  = false
  var mtimecmp: Long = -1L // all ones
  var mtime: Long    = 0
  private var phase  = 0L  // steps since the last tick

  protected def readWord(offset: Int): Long = offset match {
    case Msip       => if (msip) 1 else 0
    case MtimecmpLo => mtimecmp
    case MtimecmpHi => mtimecmp >>> 32
    case MtimeLo    => mtime
    case MtimeHi    => mtime >>> 32
    case _          => 0
  }

  // [CA26: Exercise 3.15] The counters in the golden model
  // Contract: the timer, as this class's comment and `Clint`'s define it: a store (`data`) to a
  // half of `mtimecmp` or `mtime` replaces that half alone (`ModelDevice.withHalf`), `elapse(n)`
  // lets `n` steps pass, `mtip` is the timer interrupt (Privileged spec §3.2.1; handout §T3.5).
  protected def writeWord(offset: Int, data: Long): Unit = offset match {
    case Msip                    => msip = (data & 1) != 0
    case MtimecmpLo | MtimecmpHi =>
      mtimecmp = ?
    case MtimeLo | MtimeHi => mtime = ?
    case _                 => ()
  }

  // [CA26: Exercise 3.15] The counters in the golden model
  // Contract: see above; `phase` counts the steps since the last tick.
  override def elapse(n: Long): Unit = {
    ?
  }

  // [CA26: Exercise 3.15] The counters in the golden model
  // Contract: see above: whether the timer interrupt is pending.
  def mtip: Boolean = ?

  override protected def kindOf(offset: Int): ReadKind = offset match {
    case MtimeLo => ReadKind.Counter(Iss.timeCounter, high = false)
    case MtimeHi => ReadKind.Counter(Iss.timeCounter, high = true)
    case _       => ReadKind.Modeled
  }
  override def pending: Long = (if (msip) 1L << Trap.Msi else 0) | (if (mtip) 1L << Trap.Mti else 0)

  /** MTIP rises when `mtime` reaches `mtimecmp`, if MTIE is set in `enabled`: the steps until
    * then. A distance of 2^63 ticks or more counts as never (the reset value of `mtimecmp` is
    * that far).
    */
  override def wakeIn(enabled: Long): Option[Long] =
    Option
      .when((enabled >> Trap.Mti & 1) != 0 && !mtip)(mtimecmp - mtime)
      .filter(ticks => ticks > 0 && ticks <= Long.MaxValue / tickDivisor)
      .map(ticks => ticks * tickDivisor - phase)
}
