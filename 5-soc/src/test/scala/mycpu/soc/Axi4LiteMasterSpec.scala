// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.simulator.Exceptions.AssertionFailed
import chisel3.simulator.scalatest.ChiselSim
import mycpu.soc.SlaveTiming.fixed
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

/** T5-01: [[Axi4LiteMaster]] between a core in Scala ([[CoreDriver]]) and a slave in Scala
  * ([[SlaveBfm]]), with [[Axi4LiteChecker]] on the AXI side. The slave model checks the request
  * channels on its own as well (`violations`), so these tests do not rest on the checker alone.
  */
class Axi4LiteMasterSpec extends AnyFlatSpec with ChiselSim {
  import Axi4Lite.Resp._

  /** A master bench with its core and its slave, ready to step. */
  class Rig(
      c: MasterBench,
      timings: Iterator[SlaveTiming] = fixed(),
      respOf: Long => Int = _ => Okay,
      pipelined: Boolean = false,
      holds: Boolean = false
  ) {
    val core  = new CoreDriver(c.io.core, holds)
    val slave = new SlaveBfm(c.io.axi, timings, respOf, pipelined)
    val bench = new Bench(c.clock, core, slave)

    /** Sends the accesses, runs until all are answered and returns their outcomes. */
    def run(accesses: Access*): Seq[Outcome] = {
      val before = core.done.size
      core.send(accesses: _*)
      bench.run(s"${accesses.size} accesses")(core.idle)
      core.done.drop(before).toSeq
    }
    def clean(): Unit = {
      assert(slave.violations.isEmpty, slave.violations.mkString("\n"))
      assert(core.spurious == 0, "a response without a request")
    }
  }

  private val base = 0x80000000L

  "Axi4LiteMaster" should "turn reads into AR/R and writes into AW/W/B, with the strobes as WSTRB" in {
    simulate(new MasterBench()) { c =>
      val rig = new Rig(c, pipelined = true)
      rig.slave.mem(base + 8) = 0xcafef00dL
      val Seq(read, write, partial, back) = rig.run(
        Access(base + 8),
        Access(base + 4, 0x11223344L, 0xf),
        Access(base + 4, 0xaabbccddL, 0x6),
        Access(base + 4)
      )
      assert(read.rdata == 0xcafef00dL && !read.error)
      assert(!write.error && !partial.error)
      assert(back.rdata == 0x11bbcc44L, "bytes 1 and 2 replaced")
      val seen = rig.slave.transfers.map(t => (t.channel, t.addr, t.data, t.strb)).toSeq
      assert(seen.filter(_._1 == "AR") == Seq(("AR", base + 8, 0, 0), ("AR", base + 4, 0, 0)))
      assert(seen.filter(_._1 == "AW") == Seq(("AW", base + 4, 0, 0), ("AW", base + 4, 0, 0)))
      assert(seen.filter(_._1 == "W") == Seq(("W", 0, 0x11223344L, 0xf), ("W", 0, 0xaabbccddL, 0x6)))
      assert(rig.slave.count("R") == 2 && rig.slave.count("B") == 2)
      // the earliest a registered slave can answer: the cycle after the request
      for (o <- Seq(read, write, partial, back)) assert(o.answered == o.taken + 1, o)
      rig.clean()
    }
  }

  it should "complete a write for every order of AWREADY and WREADY" in {
    val orders = Seq(
      "together, at once"   -> SlaveTiming(aw = 0, w = 0),
      "AW first"            -> SlaveTiming(aw = 0, w = 3),
      "AW first, both late" -> SlaveTiming(aw = 1, w = 4),
      "W first"             -> SlaveTiming(aw = 3, w = 0),
      "W first, both late"  -> SlaveTiming(aw = 5, w = 2),
      "together, late"      -> SlaveTiming(aw = 2, w = 2)
    )
    for ((name, timing) <- orders) simulate(new MasterBench()) { c =>
      val rig        = new Rig(c, fixed(timing))
      val Seq(write) = rig.run(Access(base + 0x10, 0x5a5a0001L, 0xf))
      val aw         = rig.slave.transfers.filter(_.channel == "AW")
      val w          = rig.slave.transfers.filter(_.channel == "W")
      val b          = rig.slave.transfers.filter(_.channel == "B")
      assert(aw.size == 1 && w.size == 1 && b.size == 1, s"$name: one transfer per channel")
      // the model raises a READY once its VALID has waited the given number of cycles, and the
      // master raises both VALIDs in the cycle it takes the request
      assert(aw.head.cycle == write.taken + timing.aw, s"$name: AW transfer")
      assert(w.head.cycle == write.taken + timing.w, s"$name: W transfer")
      assert(aw.head.addr == base + 0x10 && w.head.data == 0x5a5a0001L && w.head.strb == 0xf, name)
      assert(b.head.cycle == math.max(aw.head.cycle, w.head.cycle) + 1, s"$name: B after both")
      assert(write.answered == b.head.cycle && !write.error, s"$name: answered with B")
      assert(rig.slave.mem(base + 0x10) == 0x5a5a0001L, name)
      rig.clean()
    }
  }

  it should "wait for a delayed ARREADY, R and B, and answer the core in the cycle the response comes" in {
    simulate(new MasterBench()) { c =>
      val timings = Iterator(SlaveTiming(ar = 4, r = 6), SlaveTiming(b = 9)) ++ fixed()
      val rig     = new Rig(c, timings)
      rig.slave.mem(base) = 77
      val Seq(read, write) = rig.run(Access(base), Access(base + 4, 1, 0xf))
      assert(read.rdata == 77 && read.answered == read.taken + 4 + 1 + 6, read)
      assert(write.answered == write.taken + 1 + 9, write)
      rig.clean()
    }
  }

  it should "start a load only after the B response of the store before it" in {
    for (delay <- Seq(0, 1, 7)) simulate(new MasterBench()) { c =>
      val rig = new Rig(c, fixed(SlaveTiming(b = delay)))
      rig.slave.mem(base + 0x20) = 0x01d01d01L
      val Seq(store, load) = rig.run(Access(base + 0x20, 0xfeedbeefL, 0xf), Access(base + 0x20))
      val b                = rig.slave.transfers.filter(_.channel == "B").head
      val ar               = rig.slave.transfers.filter(_.channel == "AR").head
      assert(ar.cycle >= b.cycle, s"B delayed by $delay: the load's AR came in cycle ${ar.cycle}, B in ${b.cycle}")
      assert(load.rdata == 0xfeedbeefL, s"B delayed by $delay: the load sees the store")
      assert(store.answered == b.cycle && load.taken >= store.answered)
      rig.clean()
    }
  }

  it should "keep a second request waiting until the response, and take it in that very cycle" in {
    simulate(new MasterBench()) { c =>
      val rig = new Rig(c, fixed(SlaveTiming(r = 3)), pipelined = true)
      for (k <- 0 until 4) rig.slave.mem(base + 4 * k) = 100 + k
      val reads = rig.run((0 until 4).map(k => Access(base + 4 * k)): _*)
      assert(reads.map(_.rdata) == Seq(100, 101, 102, 103))
      for ((o, k) <- reads.zipWithIndex) {
        assert(o.answered == o.taken + 4, s"read $k: R comes 1 + 3 cycles after AR")
        if (k > 0) {
          assert(o.presented == reads(k - 1).taken + 1, s"read $k was offered while read ${k - 1} was outstanding")
          assert(o.taken == reads(k - 1).answered, s"read $k is taken in the cycle read ${k - 1} is answered")
        }
      }
      // never two outstanding: each AR transfer is in or after the cycle of the previous R transfer
      val ar = rig.slave.transfers.filter(_.channel == "AR").map(_.cycle)
      val r  = rig.slave.transfers.filter(_.channel == "R").map(_.cycle)
      assert(ar.tail.zip(r).forall { case (next, previous) => next >= previous })
      rig.clean()
    }
  }

  it should "run sequential reads at one word per cycle with a slave that answers after one cycle" in {
    simulate(new MasterBench()) { c =>
      val rig = new Rig(c, pipelined = true)
      val n   = 32
      for (k <- 0 until n) rig.slave.mem(base + 4 * k) = 0x1000 + k
      val reads = rig.run((0 until n).map(k => Access(base + 4 * k)): _*)
      assert(reads.map(_.rdata) == (0 until n).map(0x1000 + _))
      assert(reads.map(_.answered - reads.head.taken) == (1 to n), "word k is answered in cycle k + 1")
      rig.clean()
    }
  }

  it should "report SLVERR and DECERR as an error, from the channel of the transaction" in {
    val respOf: Long => Int = addr => ((addr >> 8) & 3).toInt // 0x0xx OKAY, 0x2xx SLVERR, 0x3xx DECERR
    for (idle <- Seq(Okay, SlvErr, DecErr)) simulate(new MasterBench()) { c =>
      val rig = new Rig(c, respOf = respOf)
      rig.slave.idleResp = idle // what the other response channel shows meanwhile
      val outcomes = rig.run(
        Access(0x004),
        Access(0x204),
        Access(0x304),
        Access(0x008, 1, 0xf),
        Access(0x208, 1, 0xf),
        Access(0x308, 1, 0xf)
      )
      assert(outcomes.map(_.error) == Seq(false, true, true, false, true, true), s"idle resp $idle")
      rig.clean()
    }
  }

  it should "drive no VALID during reset and forget the transaction that a reset interrupts" in {
    for (write <- Seq(false, true)) simulate(new MasterBench()) { c =>
      // a slave that takes nothing for a long time
      val slow   = SlaveTiming(ar = 50, aw = 50, w = 50)
      val rig    = new Rig(c, Iterator(slow, slow) ++ fixed()) // the first read and the first write
      val access = if (write) Access(base, 0x1234, 0xf) else Access(base)
      rig.core.send(access)
      rig.bench.step(3)
      val valids = if (write) Seq(c.io.axi.aw.valid, c.io.axi.w.valid) else Seq(c.io.axi.ar.valid)
      valids.foreach(_.expect(true.B, "the request waits for READY"))

      // reset in the middle, while the core drives a request (its next one)
      c.io.core.req.valid.poke(true.B)
      c.reset.poke(true.B)
      for (valid <- Seq(c.io.axi.ar.valid, c.io.axi.aw.valid, c.io.axi.w.valid))
        valid.expect(false.B, "no VALID while reset is asserted")
      c.io.core.req.ready.expect(false.B, "nothing is taken during reset")
      c.clock.step()
      c.io.core.req.valid.poke(false.B)
      for (valid <- Seq(c.io.axi.ar.valid, c.io.axi.aw.valid, c.io.axi.w.valid))
        valid.expect(false.B, "no VALID while reset is asserted")
      c.reset.poke(false.B)
      rig.core.reset()
      rig.slave.reset()

      // nothing is left of it: no VALID and no response
      for (_ <- 0 until 3) {
        rig.bench.step()
        for (valid <- Seq(c.io.axi.ar.valid, c.io.axi.aw.valid, c.io.axi.w.valid))
          valid.expect(false.B, "the interrupted request does not come back")
        c.io.core.resp.valid.expect(false.B)
      }

      // and the adapter works again
      rig.slave.mem(base + 4) = 0x600d
      val before                 = rig.slave.transfers.size
      val Seq(store, load, next) = rig.run(Access(base + 8, 5, 0xf), Access(base + 4), Access(base + 8))
      assert(!store.error && load.rdata == 0x600d && next.rdata == 5)
      assert(rig.slave.transfers.drop(before).count(_.channel == "AR") == 2)
      assert(!rig.slave.mem.contains(base), "the interrupted write never reached the slave")
      assert(rig.core.spurious == 0)
    }
  }

  it should "give the core no response while reset is asserted, even one that arrives in its first cycle" in {
    simulate(new MasterBench(check = false)) { c => // no checker: the response is driven by hand
      c.io.core.req.valid.poke(true.B)
      c.io.core.req.bits.addr.poke(base.U)
      c.io.core.req.bits.wstrb.poke(0.U)
      c.io.axi.ar.ready.poke(true.B)
      c.io.core.req.ready.expect(true.B)
      c.clock.step() // the read is outstanding
      c.io.core.req.valid.poke(false.B)
      // a slave with a synchronous reset still shows its RVALID in the first cycle of a reset
      c.reset.poke(true.B)
      c.io.axi.r.valid.poke(true.B)
      c.io.axi.r.bits.data.poke(0x77.U)
      c.io.core.resp.valid.expect(false.B, "no response during reset")
      c.clock.step()
      c.io.axi.r.valid.poke(false.B)
      c.reset.poke(false.B)
      c.io.core.resp.valid.expect(false.B)
    }
  }

  it should "drop a response that no transaction waits for, as after a reset of the master alone" in {
    simulate(new MasterBench(check = false)) { c => // the checker would stop at such a slave
      val rig = new Rig(c)
      rig.bench.step(2)
      c.io.axi.r.valid.poke(true.B)
      c.io.axi.b.valid.poke(true.B)
      c.io.axi.r.ready.expect(true.B)
      c.io.axi.b.ready.expect(true.B)
      c.io.core.resp.valid.expect(false.B, "a stray response does not reach the core")
      c.clock.step()
      rig.slave.mem(base) = 3
      assert(rig.run(Access(base)).head.rdata == 3)
      rig.clean()
    }
  }

  "Axi4LiteMaster(holdsRequest = true)" should "serve a core that holds its request until the response" in {
    simulate(new MasterBench(holdsRequest = true)) { c =>
      val timings = Iterator(SlaveTiming(), SlaveTiming(aw = 2, w = 0, b = 1), SlaveTiming(ar = 1, r = 2)) ++
        fixed()
      val rig = new Rig(c, timings, holds = true)
      rig.slave.mem(base) = 9
      val outcomes = rig.run(Access(base), Access(base + 4, 0xabcd, 0x3), Access(base + 4), Access(base))
      assert(outcomes.map(_.rdata).zipWithIndex.collect { case (d, k) if k != 1 => d } == Seq(9, 0xabcd, 9))
      assert(outcomes.forall(!_.error))
      // one transfer per request although the core shows each request for several cycles
      assert(rig.slave.count("AR") == 3 && rig.slave.count("AW") == 1 && rig.slave.count("W") == 1)
      // a request starts in the cycle after the previous response: two cycles per access at least
      assert(outcomes.head.answered == outcomes.head.presented + 1)
      assert(outcomes.sliding(2).forall { case Seq(a, b) => b.presented == a.answered + 1 })
      rig.clean()
    }
  }

  it should "assert that the core does hold it" in {
    def run(change: MasterBench => Unit): Unit = simulate(new MasterBench(holdsRequest = true)) { c =>
      for (ready <- Seq(c.io.axi.ar.ready, c.io.axi.aw.ready, c.io.axi.w.ready)) ready.poke(false.B)
      c.io.axi.r.valid.poke(false.B)
      c.io.axi.b.valid.poke(false.B)
      c.io.core.req.valid.poke(true.B)
      c.io.core.req.bits.addr.poke(base.U)
      c.io.core.req.bits.wstrb.poke(0.U)
      c.clock.step(2)
      change(c)
      c.clock.step(2)
    }
    run(_ => ()) // a held request: no assertion
    val withdrawn = intercept[AssertionFailed](run(_.io.core.req.valid.poke(false.B)))
    assert(withdrawn.getMessage.contains("changed or withdrew a request"))
    val changed = intercept[AssertionFailed](run(_.io.core.req.bits.addr.poke((base + 4).U)))
    assert(changed.getMessage.contains("changed or withdrew a request"))
  }

  "Axi4LiteMaster" should "agree with a memory model on random accesses and random slave timing" in {
    for (seed <- Seq(1, 2, 3)) simulate(new MasterBench()) { c =>
      val rng                 = new Random(seed)
      def delay()             = if (rng.nextInt(3) == 0) rng.nextInt(5) else 0
      val timings             = Iterator.continually(SlaveTiming(delay(), delay(), delay(), delay(), delay()))
      val respOf: Long => Int = addr => if ((addr & 0x100) != 0) SlvErr else Okay
      val rig                 = new Rig(c, timings, respOf)
      val accesses            = Seq.fill(300) {
        val addr = base + 4 * rng.nextInt(16) + (if (rng.nextInt(8) == 0) 0x100 else 0)
        if (rng.nextBoolean()) Access(addr, gap = delay())
        else Access(addr, rng.nextLong() & 0xffffffffL, 1 + rng.nextInt(15), gap = delay())
      }
      val outcomes = rig.run(accesses: _*)
      val model    = scala.collection.mutable.Map[Long, Long]().withDefaultValue(0L)
      for ((o, k) <- outcomes.zipWithIndex) {
        val a      = o.access
        val failed = (a.addr & 0x100) != 0
        assert(o.access == accesses(k) && o.error == failed, s"seed $seed, access $k: $o")
        if (a.isWrite && !failed) model(a.addr) = SlaveBfm.merge(model(a.addr), a.wdata, a.wstrb)
        if (!a.isWrite) assert(o.rdata == model(a.addr), s"seed $seed, access $k: $o")
      }
      assert(rig.slave.count("AR") == accesses.count(!_.isWrite))
      assert(rig.slave.count("AW") == accesses.count(_.isWrite) && rig.slave.count("W") == rig.slave.count("AW"))
      rig.clean()
    }
  }

  "The drop-awvalid mutant" should "be caught by the checker, by the slave model, and by a write that never ends" in {
    val lateAw = SlaveTiming(aw = 2)
    // the checker stops the simulation
    val failure = intercept[AssertionFailed] {
      simulate(new MasterBench(mutant = Some("drop-awvalid"))) { c =>
        new Rig(c, fixed(lateAw)).run(Access(base, 1, 0xf))
      }
    }
    assert(failure.getMessage.contains("AWVALID fell before AWREADY"), failure.getMessage)
    // without the checker: the slave model sees the same, and the write hangs
    simulate(new MasterBench(mutant = Some("drop-awvalid"), check = false)) { c =>
      val rig = new Rig(c, fixed(lateAw))
      rig.core.send(Access(base, 1, 0xf))
      rig.bench.step(200)
      assert(rig.core.done.isEmpty, "the write must not complete: its AW was withdrawn")
      assert(rig.slave.violations.exists(_.contains("AWVALID fell before AWREADY")), rig.slave.violations)
      assert(rig.slave.count("AW") == 0 && rig.slave.count("W") == 1)
    }
    // the mutant is invisible to a slave that is always ready, so the tests above need the late AWREADY
    simulate(new MasterBench(mutant = Some("drop-awvalid"))) { c =>
      val rig = new Rig(c)
      assert(!rig.run(Access(base, 1, 0xf)).head.error)
      rig.clean()
    }
    // and the reference passes where the mutant fails
    simulate(new MasterBench()) { c =>
      val rig = new Rig(c, fixed(lateAw))
      assert(!rig.run(Access(base, 1, 0xf)).head.error)
      rig.clean()
    }
  }
}
