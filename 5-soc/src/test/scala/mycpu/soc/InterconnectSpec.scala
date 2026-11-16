// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.soc.SlaveTiming.fixed
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

/** T5-03: [[Axi4LiteXbar]] between two [[Axi4LiteMaster]]s, driven by cores in Scala, and slave
  * models on its ports, with a checker on every interface. The table has two shared slaves, A and
  * B, and one with a port per master, M.
  */
class InterconnectSpec extends AnyFlatSpec with ChiselSim {
  import Axi4Lite.Resp._
  private val A       = 0x00001000L
  private val B       = 0x10000000L
  private val M       = 0x80000000L
  private val regions = Seq(Region(A, 0x1000), Region(B, 0x1000), Region(M, 0x10000, perMaster = true))
  private val names   = Seq("A", "B", "M.i", "M.d") // the slave ports, in order

  /** Addresses in no region, the edges of the regions among them. */
  private val unmapped = Seq(0x0L, 0xffcL, 0x2000L, 0x0ffffffcL, 0x10001000L, 0x7ffffffcL, 0x80010000L, 0xfffffffcL)

  /** The slave models of a bench, by port name, with the given timing each (default: at once). */
  private def models(
      slaves: Vec[Axi4LiteIO],
      timings: Map[String, Iterator[SlaveTiming]],
      pipelined: Boolean
  ): Map[String, SlaveBfm] =
    names.zipWithIndex.map { case (name, k) =>
      name -> new SlaveBfm(slaves(k), timings.getOrElse(name, fixed()), pipelined = pipelined)
    }.toMap

  /** A crossbar bench with its two cores and four slaves. */
  class Rig(c: XbarBench, timings: Map[String, Iterator[SlaveTiming]] = Map.empty, pipelined: Boolean = true) {
    val i      = new CoreDriver(c.io.i)
    val d      = new CoreDriver(c.io.d)
    val slaves = models(c.io.slaves, timings, pipelined)
    val bench  = new Bench(c.clock, Seq(i, d) ++ names.map(slaves): _*)

    def run(limit: Int = 2000): Unit                             = bench.run("the accesses", limit)(i.idle && d.idle)
    def transfers(slave: String, channel: String): Seq[Transfer] =
      slaves(slave).transfers.filter(_.channel == channel).toSeq
    def clean(): Unit = {
      for ((name, slave) <- slaves) assert(slave.violations.isEmpty, s"$name: ${slave.violations.mkString("; ")}")
      assert(i.spurious == 0 && d.spurious == 0, "a response without a request")
    }
  }

  "Axi4LiteXbar" should "let I and D proceed in parallel on different slaves" in {
    simulate(new XbarBench(regions)) { c =>
      val rig = new Rig(c, Map("A" -> fixed(SlaveTiming(r = 8))))
      rig.slaves("A").mem(A + 4) = 0xaaaa
      for (k <- 0 until 4) rig.slaves("B").mem(B + 4L * k) = 0xb0 + k
      rig.i.send(Access(A + 4))                                // a slow read
      rig.d.send((0 until 4).map(k => Access(B + 4L * k)): _*) // four quick ones meanwhile
      rig.run()
      assert(rig.i.done.map(_.rdata) == Seq(0xaaaa) && rig.d.done.map(_.rdata) == (0 until 4).map(0xb0 + _))
      assert(rig.transfers("A", "AR").head.cycle == rig.transfers("B", "AR").head.cycle, "both start in cycle 0")
      assert(rig.d.done.map(_.answered) == Seq(1, 2, 3, 4), "D is not slowed down: one word per cycle")
      assert(rig.i.done.head.answered == 9)
      rig.clean()
    }
  }

  it should "give a shared slave to D when both ask, and to I when D does not" in {
    simulate(new XbarBench(regions)) { c =>
      val rig = new Rig(c)
      for (k <- 0 until 8) rig.slaves("A").mem(A + 4L * k) = 0x100 + k
      rig.i.send(Access(A), Access(A + 4))
      rig.d.send(Access(A + 16), Access(A + 20), Access(A + 24), Access(A + 28, gap = 3))
      rig.run()
      assert(rig.i.done.map(_.rdata) == Seq(0x100, 0x101) && rig.d.done.map(_.rdata) == Seq(0x104, 0x105, 0x106, 0x107))
      // D asks in cycles 0, 1, 2 and gets the slave each time; I, which asked in cycle 0, gets it
      // in cycle 3, the first one in which D does not ask, and again in cycle 4
      val order = rig.transfers("A", "AR").map(t => (t.cycle, t.addr))
      assert(order == Seq((0, A + 16), (1, A + 20), (2, A + 24), (3, A), (4, A + 4), (6, A + 28)), order)
      assert(rig.i.done.map(_.answered) == Seq(4, 5), "I waited for D")
      rig.clean()
    }
  }

  it should "give D a shared slave for writes too, and finish what the slave has seen of I first" in {
    simulate(new XbarBench(regions)) { c =>
      // slave A lets every AR, AW and W wait three cycles, so a request is seen before it is taken
      val rig = new Rig(c, Map("A" -> fixed(SlaveTiming(ar = 3, aw = 3, w = 3))), pipelined = false)
      def order(channel: String, first: Int) =
        rig.transfers("A", channel).drop(first).map(t => if (channel == "W") t.data else t.addr)
      // both ask in the same cycle: D first
      rig.i.send(Access(A + 4))
      rig.d.send(Access(A + 8))
      rig.run()
      assert(order("AR", 0) == Seq(A + 8, A + 4))
      rig.i.send(Access(A + 4, 0x11, 0xf))
      rig.d.send(Access(A + 8, 0x22, 0xf))
      rig.run()
      assert(order("AW", 0) == Seq(A + 8, A + 4) && order("W", 0) == Seq(0x22, 0x11))
      // I is at the slave one cycle before D asks: I's request stays there, D follows
      rig.i.send(Access(A + 12))
      rig.d.send(Access(A + 16, gap = 1))
      rig.run()
      assert(order("AR", 2) == Seq(A + 12, A + 16))
      rig.i.send(Access(A + 12, 0x33, 0xf))
      rig.d.send(Access(A + 16, 0x44, 0xf, gap = 1))
      rig.run()
      assert(order("AW", 2) == Seq(A + 12, A + 16) && order("W", 2) == Seq(0x33, 0x44))
      assert(Seq(4, 8, 12, 16).map(o => rig.slaves("A").mem(A + o)) == Seq(0x11, 0x22, 0x33, 0x44))
      rig.clean() // the slave model saw no VALID fall and no payload change
    }
  }

  it should "decode reads and writes independently: a read of I and a write of D on one slave together" in {
    simulate(new XbarBench(regions)) { c =>
      val rig = new Rig(c, Map("B" -> fixed(SlaveTiming(b = 6, r = 2))), pipelined = false)
      rig.slaves("B").mem(B + 8) = 0x5151
      rig.d.send(Access(B, 0x77, 0xf))
      rig.i.send(Access(B + 8, gap = 1), Access(B + 8))
      rig.run()
      val b = rig.transfers("B", "B").head.cycle
      assert(
        rig.transfers("B", "AR").head.cycle < b && rig.i.done.head.answered < b,
        "the read does not wait for the write"
      )
      assert(rig.i.done.map(_.rdata) == Seq(0x5151, 0x5151) && rig.d.done.head.answered == b)
      rig.clean()
    }
  }

  it should "never make the masters wait for each other on a slave with a port per master" in {
    simulate(new XbarBench(regions)) { c =>
      val rig = new Rig(c)
      for (k <- 0 until 16) {
        rig.slaves("M.i").mem(M + 4L * k) = 0x1000 + k; rig.slaves("M.d").mem(M + 4L * k) = 0x2000 + k
      }
      rig.i.send((0 until 16).map(k => Access(M + 4L * k)): _*)
      rig.d.send((0 until 16).map(k => Access(M + 4L * k)): _*)
      rig.run()
      assert(
        rig.i.done.map(_.rdata) == (0 until 16).map(0x1000 + _) && rig.d.done.map(_.rdata) == (0 until 16).map(
          0x2000 + _
        )
      )
      for (done <- Seq(rig.i.done, rig.d.done)) assert(done.map(_.answered) == (1 to 16), "one word per cycle each")
      assert(rig.slaves("M.i").count("AR") == 16 && rig.slaves("M.d").count("AR") == 16)
      rig.clean()
    }
  }

  /** Reads and writes of `unmapped` from both masters, then one mapped access each; returns the
    * error flags of the unmapped ones.
    */
  private def unmappedRun(mutant: Option[String]): Seq[Boolean] = {
    var errors = Seq.empty[Boolean]
    simulate(new XbarBench(regions, mutant)) { c =>
      val rig = new Rig(c)
      rig.slaves("A").mem(A) = 0xa
      rig.slaves("B").mem(B) = 0xb
      val accesses = unmapped.flatMap(addr => Seq(Access(addr), Access(addr, 0xdead, 0xf)))
      rig.i.send(accesses :+ Access(A): _*)
      rig.d.send(accesses.reverse :+ Access(B): _*)
      rig.run()
      val (iBad, dBad) = (rig.i.done.init, rig.d.done.init)
      for (o <- iBad ++ dBad) {
        assert(o.answered == o.taken + 1, s"answered in the next cycle: $o")
        if (!o.access.isWrite) assert(o.rdata == 0, s"no data: $o")
      }
      for ((name, slave) <- rig.slaves)
        assert(slave.transfers.size == (if (name == "A" || name == "B") 2 else 0), s"$name saw ${slave.transfers}")
      // the edges of each region are mapped, and the crossbar works on after the errors
      assert(rig.i.done.last.rdata == 0xa && !rig.i.done.last.error && rig.d.done.last.rdata == 0xb)
      rig.i.send(Access(A + 0xffc), Access(B + 0xffc), Access(M), Access(M + 0xfffc))
      rig.run()
      assert(rig.i.done.takeRight(4).forall(!_.error))
      rig.clean()
      errors = (iBad ++ dBad).map(_.error).toSeq
    }
    errors
  }

  it should "answer an address in no region with DECERR, without touching a slave" in {
    val errors = unmappedRun(None)
    assert(errors.size == 4 * unmapped.size && errors.forall(identity))
  }

  "The no-decerr mutant" should "be caught: its unmapped accesses report no error" in {
    assert(unmappedRun(Some("no-decerr")).forall(!_))
  }

  /** The crossbar alone, D driven raw, with slave models of the given timing. */
  private def raw(timing: SlaveTiming)(body: (RawMaster, Map[String, SlaveBfm], XbarRawBench) => Unit): Unit =
    simulate(new XbarRawBench(regions)) { c =>
      val slaves = models(c.io.slaves, names.map(_ -> fixed(timing)).toMap, pipelined = false)
      val bench  = new Bench(c.clock, names.map(slaves): _*)
      val i      = new RawMaster(c.io.i, () => bench.step(), () => bench.drive())
      val d      = new RawMaster(c.io.d, () => bench.step(), () => bench.drive())
      i.idle()
      d.idle()
      body(d, slaves, c)
      for ((name, slave) <- slaves) assert(slave.violations.isEmpty, s"$name: ${slave.violations.mkString("; ")}")
    }

  "Axi4LiteXbar" should "route a write whose W comes before its AW, or whose slave takes W first" in {
    for (timing <- Seq(SlaveTiming(), SlaveTiming(aw = 4), SlaveTiming(w = 4), SlaveTiming(aw = 2, w = 2, b = 3)))
      raw(timing) { (d, slaves, _) =>
        for (((awAt, wAt), n) <- Seq((0, 0), (0, 3), (3, 0), (6, 1)).zipWithIndex) {
          val start = d.cycle
          assert(d.write(B + 4L * n, 0x900 + n, awAt = awAt, wAt = wAt, readyAfter = n).resp == Okay)
          assert(slaves("B").mem(B + 4L * n) == 0x900 + n, s"$timing, AW at $awAt, W at $wAt")
          val w = slaves("B").transfers.filter(_.channel == "W").last
          assert(w.cycle >= start + awAt, "W carries no address: it is passed on with its AW, not before")
          assert(d.read(B + 4L * n, readyAfter = n).data == 0x900 + n)
        }
        assert(slaves("B").count("AW") == 4 && slaves("B").count("W") == 4 && slaves("A").transfers.isEmpty)
        // the same on a port of its own, where no arbiter stands between the decoder and the slave
        for (((awAt, wAt), n) <- Seq((0, 3), (3, 0), (6, 1)).zipWithIndex) {
          val start = d.cycle
          assert(d.write(M + 4L * n, 0xa00 + n, awAt = awAt, wAt = wAt).resp == Okay)
          assert(slaves("M.d").mem(M + 4L * n) == 0xa00 + n, s"$timing, AW at $awAt, W at $wAt")
          val w = slaves("M.d").transfers.filter(_.channel == "W").last
          assert(w.cycle >= start + awAt, "W carries no address: it is passed on with its AW, not before")
        }
        // the same on an address in no region: answered here, and held until BREADY and RREADY
        for ((awAt, wAt) <- Seq((0, 0), (0, 3), (3, 0))) {
          assert(d.write(0x2000, 1, awAt = awAt, wAt = wAt, readyAfter = 3) == WriteResult(DecErr, 1))
          assert(d.read(0x2000, readyAfter = 3) == ReadResult(0, DecErr, arWait = 0, latency = 1))
        }
        assert(
          slaves.values.map(_.transfers.size).sum == 4 * 5 + 3 * 3,
          "four writes and four reads on B, three writes on M.d, nothing else"
        )
      }
  }

  it should "agree with a memory model on random traffic from both masters, with random slave timing" in {
    for (seed <- Seq(21, 22, 23)) simulate(new XbarBench(regions)) { c =>
      val rng     = new Random(seed)
      def delay() = if (rng.nextInt(3) == 0) rng.nextInt(4) else 0
      def timing  = Iterator.continually(SlaveTiming(delay(), delay(), delay(), delay(), delay()))
      val rig     = new Rig(c, names.map(_ -> timing).toMap, pipelined = false)
      // I reads words 0 to 15 of every slave, which nobody writes; D reads all and writes 16 to 31
      def initial(base: Long, k: Int, salt: Int): Long = (base >> 12) ^ (k * 0x01000193L + salt) & 0xffffffL
      val model                                        = scala.collection.mutable.Map[Long, Long]()
      for ((name, base, salt) <- Seq(("A", A, 0), ("B", B, 0), ("M.i", M, 1), ("M.d", M, 0)); k <- 0 until 32) {
        rig.slaves(name).mem(base + 4L * k) = initial(base, k, salt)
        if (name != "M.i") model(base + 4L * k) = initial(base, k, 0)
      }
      def address(words: Range): Long =
        if (rng.nextInt(10) == 0) unmapped(rng.nextInt(unmapped.size))
        else Seq(A, B, M)(rng.nextInt(3)) + 4L * (words.start + rng.nextInt(words.size))
      val n = 250
      rig.i.send(Seq.fill(n)(Access(address(0 until 16), gap = delay())): _*)
      rig.d.send(Seq.fill(n) {
        if (rng.nextBoolean()) Access(address(0 until 32), gap = delay())
        else Access(address(16 until 32), rng.nextLong() & 0xffffffffL, 1 + rng.nextInt(15), gap = delay())
      }: _*)
      rig.run(20000)
      assert(rig.i.done.size == n && rig.d.done.size == n)
      for (o <- rig.i.done) {
        val a = o.access.addr
        if (unmapped.contains(a)) assert(o.error && o.rdata == 0, s"seed $seed: $o")
        else
          assert(
            !o.error && o.rdata == initial(a & ~0xfffL, ((a & 0xfff) / 4).toInt, if (a >= M) 1 else 0),
            s"seed $seed: $o"
          )
      }
      for (o <- rig.d.done) {
        val a = o.access
        assert(o.error == unmapped.contains(a.addr), s"seed $seed: $o")
        if (!o.error && a.isWrite) model(a.addr) = SlaveBfm.merge(model(a.addr), a.wdata, a.wstrb)
        if (!a.isWrite) assert(o.rdata == (if (o.error) 0 else model(a.addr)), s"seed $seed: $o")
      }
      // nothing lost, nothing sent twice, nothing sent to the wrong slave
      val all = rig.i.done.map(o => ("i", o.access)) ++ rig.d.done.map(o => ("d", o.access))
      for ((name, base) <- Seq(("A", A), ("B", B), ("M.i", M), ("M.d", M))) {
        val mine = all.collect {
          case (who, a)
              if (a.addr & ~0xffffL) == (base & ~0xffffL) && !unmapped.contains(a.addr) &&
                (base != M || name == s"M.$who") =>
            a
        }
        assert(rig.slaves(name).count("AR") == mine.count(!_.isWrite), s"seed $seed: reads of $name")
        assert(rig.slaves(name).count("AW") == mine.count(_.isWrite), s"seed $seed: writes of $name")
        assert(
          rig.slaves(name).count("W") == mine.count(_.isWrite) && rig.slaves(name).count("B") == mine.count(_.isWrite)
        )
      }
      rig.clean()
    }
  }
}
