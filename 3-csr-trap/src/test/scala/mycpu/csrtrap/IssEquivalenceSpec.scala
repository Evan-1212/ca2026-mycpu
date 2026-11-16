// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common.Trap
import org.scalatest.flatspec.AnyFlatSpec

/** [[CsrFile]] and [[Clint]] against the golden model on random programs ([[IssLockstep]] says
  * what is compared and why the model is used through its public API).
  */
class IssEquivalenceSpec extends AnyFlatSpec with ChiselSim {
  private val seeds = sys.env.get("MYCPU_T3_SEEDS").fold(40)(_.toInt)
  private val items = 1500

  /** Whether the run is long enough to ask for what it must reach. */
  private val full = seeds >= 40

  /** Runs `count` programs, which must agree and reach every cause, and returns what they covered. */
  private def lockstep(count: Int, handlers: Boolean): LockstepStats = {
    val stats = new LockstepStats
    simulate(new CsrClintTop()) { c =>
      val mismatch = IssLockstep.runs(c, count, items, stats, handlers)
      assert(mismatch.isEmpty, mismatch.getOrElse(""))
    }
    info(stats.toString)
    assert(stats.steps >= count * items)
    if (full) {
      val causes = Trap.interrupts.map(Trap.interruptCause) ++
        Seq(Trap.InstMisaligned, Trap.Illegal, Trap.Breakpoint, Trap.LoadMisaligned, Trap.StoreMisaligned, Trap.EcallM)
      for (cause <- causes) assert(stats.traps(cause) >= 20, f"traps with mcause 0x$cause%x")
      assert(stats.wfiWaits >= 50 && stats.wfiCycles >= 500, "WFIs that wait for the timer")
      assert(stats.hpmCounts >= 5000 && stats.hpmEvents == (1 to 8).toSet, "every event counted by a counter")
    }
    stats
  }

  "CsrFile and Clint" should s"agree with the golden model on $seeds random programs of $items statements each" in {
    val stats = lockstep(seeds, handlers = false)
    if (full) {
      for (bit <- Trap.interrupts) assert(stats.pending(bit) >= 1000, s"steps with mip bit $bit set")
      assert(stats.vectored >= 50 && stats.csrIllegal >= 500 && stats.mrets >= 1000)
      assert(stats.clintStores >= 2000 && stats.clintLoads >= 1000 && stats.written.size >= 60)
      assert(stats.collisions >= 200 && stats.jumps >= 500, "interrupts over an instruction, taken jumps")
    }
  }

  // these programs are about twice as long, with their handlers and their returns
  it should s"agree with it on ${seeds / 2} random programs that run under their own trap handlers" in {
    val stats = lockstep(seeds / 2, handlers = true)
    if (full) {
      assert(stats.mrets >= 2000 && stats.vectored >= 50 && stats.nested >= 20, "handlers that return and nest")
      assert(stats.afterMret >= 20 && stats.afterEnable >= 20, "interrupts straight after an mret and a CSR write")
    }
  }
}
