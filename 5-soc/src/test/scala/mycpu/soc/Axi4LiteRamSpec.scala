// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

/** T5-04: [[Axi4LiteRam]] in both read modes, driven on its AXI ports by [[RawMaster]]s and, for
  * the throughput a core gets, through two [[Axi4LiteMaster]]s.
  */
class Axi4LiteRamSpec extends AnyFlatSpec with ChiselSim {
  import Axi4Lite.Resp._
  private val words = 64
  private val base  = 0x80000000L

  /** The raw masters of the I and the D port, after the bench's inputs were set to idle. */
  private def masters(c: RamBench, seed: Int = 0): (RawMaster, RawMaster) = {
    val i = new RawMaster(c.io.i, () => c.clock.step())
    val d = new RawMaster(c.io.d, () => c.clock.step())
    i.idle()
    d.idle()
    c.io.load.valid.poke(false.B)
    c.io.seed.poke(seed.U)
    (i, d)
  }

  /** Writes `image` through the load port, one word per cycle. */
  private def load(valid: Bool, addr: UInt, data: UInt, clock: Clock, image: Seq[(Long, Long)]): Unit = {
    for ((a, word) <- image) {
      valid.poke(true.B)
      addr.poke(a.U)
      data.poke(word.U)
      clock.step()
    }
    valid.poke(false.B)
  }
  private def load(c: RamBench, image: Seq[(Long, Long)]): Unit =
    load(c.io.load.valid, c.io.load.bits.addr, c.io.load.bits.data, c.clock, image)

  private def pattern(k: Int): Long = (0x9e3779b9L * (k + 1)) & 0xffffffffL

  for (sync <- Seq(false, true)) {
    val ram = s"Axi4LiteRam(syncRead = $sync)"

    ram should "return loaded words on both ports and write bytes by strobe, one cycle after the request" in {
      simulate(new RamBench(words, sync)) { c =>
        val (i, d) = masters(c)
        load(c, (0 until words).map(k => (base + 4L * k, pattern(k))))
        for (k <- Seq(0, 1, 31, words - 1); port <- Seq(i, d))
          assert(port.read(base + 4L * k) == ReadResult(pattern(k), Okay, arWait = 0, latency = 1), s"word $k")
        for (strb <- 0 to 15) {
          val addr = base + 4L * strb
          val data = 0xa1b2c3d4L ^ (strb * 0x01010101L)
          assert(d.write(addr, data, strb) == WriteResult(Okay, latency = 1), s"strobe $strb")
          val expected = SlaveBfm.merge(pattern(strb), data, strb)
          assert(d.read(addr).data == expected && i.read(addr).data == expected, s"strobe $strb")
        }
        // the address above the window and below a word is not decoded here
        assert(i.read(0x12340000L + 4 * 20 + 3).data == pattern(20))
      }
    }

    it should "take AW and W in either order and write when the later one arrives" in {
      simulate(new RamBench(words, sync)) { c =>
        val (i, d) = masters(c)
        load(c, Seq((base + 0x40, 0x0L)))
        for (((awAt, wAt), n) <- Seq((0, 0), (0, 3), (3, 0), (2, 2), (5, 1), (1, 5)).zipWithIndex) {
          val data = 0x1000L + n
          assert(d.write(base + 0x40, data, awAt = awAt, wAt = wAt) == WriteResult(Okay, 1), s"AW at $awAt, W at $wAt")
          assert(i.read(base + 0x40).data == data, s"AW at $awAt, W at $wAt")
        }
      }
    }

    it should "hold R and B, unchanged, until READY" in {
      simulate(new RamBench(words, sync)) { c =>
        val (i, d) = masters(c)
        load(c, (0 until 8).map(k => (base + 4L * k, pattern(k))))
        for (wait <- 0 to 4) {
          // RawMaster checks that the response stays as it is while READY is low
          assert(i.read(base + 4L * wait, readyAfter = wait).data == pattern(wait), s"RREADY after $wait")
          assert(d.write(base + 0x20, wait, readyAfter = wait).resp == Okay, s"BREADY after $wait")
          assert(d.read(base + 0x20, readyAfter = wait).data == wait)
        }
        // a new address is not taken while a word waits for RREADY
        c.io.i.ar.valid.poke(true.B)
        c.io.i.ar.bits.addr.poke((base + 4).U)
        c.io.i.ar.ready.expect(true.B)
        c.clock.step()
        c.io.i.ar.bits.addr.poke((base + 8).U)
        c.io.i.r.valid.expect(true.B)
        c.io.i.ar.ready.expect(false.B, "the word before has not been taken")
        c.clock.step(3)
        c.io.i.r.bits.data.expect(pattern(1).U)
        c.io.i.r.ready.poke(true.B)
        c.io.i.ar.ready.expect(true.B, "taken in the cycle of the R transfer")
        c.clock.step()
        c.io.i.ar.valid.poke(false.B)
        c.io.i.r.valid.expect(true.B)
        c.io.i.r.bits.data.expect(pattern(2).U)
      }
    }

    it should "refuse a write on the instruction port with SLVERR and change nothing" in {
      simulate(new RamBench(words, sync)) { c =>
        val (i, d) = masters(c)
        load(c, Seq((base + 8, 0x600df00dL)))
        for ((awAt, wAt) <- Seq((0, 0), (0, 2), (2, 0)))
          assert(i.write(base + 8, 0xbadbadL, awAt = awAt, wAt = wAt) == WriteResult(SlvErr, 1))
        assert(i.read(base + 8).data == 0x600df00dL && d.read(base + 8).data == 0x600df00dL)
      }
    }

    it should s"give a read in the cycle of a write to its word the ${if (sync) "new" else "old"} word" in {
      simulate(new RamBench(words, sync)) { c =>
        val (i, d) = masters(c)
        load(c, Seq((base + 0x10, 0x01dL)))
        // I reads the word in the very cycle D writes it
        c.io.i.ar.valid.poke(true.B)
        c.io.i.ar.bits.addr.poke((base + 0x10).U)
        c.io.d.aw.valid.poke(true.B)
        c.io.d.aw.bits.addr.poke((base + 0x10).U)
        c.io.d.w.valid.poke(true.B)
        c.io.d.w.bits.data.poke(0x2e3L.U)
        c.io.d.w.bits.strb.poke(0xf.U)
        for (ready <- Seq(c.io.i.ar.ready, c.io.d.aw.ready, c.io.d.w.ready)) ready.expect(true.B)
        c.clock.step()
        i.idle()
        d.idle()
        c.io.i.r.ready.poke(true.B)
        c.io.d.b.ready.poke(true.B)
        c.io.i.r.valid.expect(true.B)
        c.io.i.r.bits.data.expect((if (sync) 0x2e3L else 0x01dL).U)
        c.io.d.b.valid.expect(true.B)
        c.clock.step()
        i.idle()
        d.idle()
        assert(i.read(base + 0x10).data == 0x2e3L, "a read after it sees the write")
      }
    }

    it should "deliver one word per cycle to a master that keeps RREADY high" in {
      simulate(new RamBench(words, sync)) { c =>
        val (i, d) = masters(c)
        load(c, (0 until words).map(k => (base + 4L * k, pattern(k))))
        for (port <- Seq(i, d)) {
          val got = port.stream((0 until words).map(k => base + 4L * k))
          assert(got.map(_._1) == (0 until words).map(pattern))
          assert(got.map(_._2) == (1 to words), "word k arrives k + 1 cycles after the first ARVALID")
        }
      }
    }

    it should "add its fixed wait states to every response" in {
      for (waits <- Seq(1, 2, 5)) simulate(new RamBench(words, sync, WaitStates.Fixed(waits))) { c =>
        val (i, d) = masters(c)
        load(c, (0 until 8).map(k => (base + 4L * k, pattern(k))))
        assert(i.read(base + 4) == ReadResult(pattern(1), Okay, 0, 1 + waits))
        assert(d.read(base + 8, readyAfter = 2) == ReadResult(pattern(2), Okay, 0, 1 + waits))
        assert(d.write(base + 12, 0x77, awAt = 1) == WriteResult(Okay, 1 + waits))
        assert(i.write(base + 12, 0x88) == WriteResult(SlvErr, 1), "the refused write is not delayed")
        assert(i.read(base + 12).data == 0x77)
        val got = i.stream((0 until 8).map(k => base + 4L * k))
        assert(got.map(_._2) == (1 to 8).map(_ * (1L + waits)), s"$waits wait states: a word every ${1 + waits} cycles")
      }
    }

    it should "draw random wait states from its seed" in {
      def latencies(seed: Int): Seq[Int] = {
        var seen = Seq.empty[Int]
        simulate(new RamBench(words, sync, WaitStates.Random(3))) { c =>
          val (i, d) = masters(c, seed)
          c.reset.poke(true.B) // the LFSR takes the seed while reset is asserted
          c.clock.step()
          c.reset.poke(false.B)
          load(c, (0 until 16).map(k => (base + 4L * k, pattern(k))))
          seen = (0 until 16).flatMap { k =>
            val read  = i.read(base + 4L * k)
            val write = d.write(base + 4L * k, k)
            val back  = d.read(base + 4L * k)
            assert(read.data == pattern(k) && write.resp == Okay && back.data == k, s"seed $seed, word $k")
            Seq(read.latency, write.latency, back.latency)
          }
        }
        seen
      }
      val one = latencies(0x1234)
      assert(one.forall(l => l >= 1 && l <= 4) && one.distinct.size == 4, s"0 to 3 wait states: $one")
      assert(latencies(0x1234) == one, "the same seed gives the same delays")
      assert(latencies(0xbeef) != one, "another seed gives other delays")
    }

    it should "serve a core at one word per cycle on each port, stores included, with no wait states" in {
      for (waits <- Seq(0, 1, 2)) simulate(new RamCoreBench(words, sync, WaitStates.Fixed(waits))) { c =>
        val i     = new CoreDriver(c.io.i)
        val d     = new CoreDriver(c.io.d)
        val bench = new Bench(c.clock, i, d)
        c.io.seed.poke(0.U)
        i.drive(0)
        d.drive(0)
        load(
          c.io.load.valid,
          c.io.load.bits.addr,
          c.io.load.bits.data,
          c.clock,
          (0 until 32).map(k => (base + 4L * k, pattern(k)))
        )
        val n = 32
        i.send((0 until n).map(k => Access(base + 4L * k)): _*) // a straight-line fetch
        d.send((0 until n).map(k => Access(base + 0x80 + 4L * (k % 8), k, 0xf)): _*) // stores at the same time
        d.send((0 until 8).map(k => Access(base + 0x80 + 4L * k)): _*)
        bench.run("fetches and stores")(i.idle && d.idle)
        assert(i.done.map(_.rdata) == (0 until n).map(pattern))
        assert(d.done.drop(n).map(_.rdata) == (24 until 32), "the last store to each word")
        // word k is answered (k + 1) * (1 + waits) cycles after the first request was taken
        for (done <- Seq(i.done, d.done.take(n)))
          assert(done.map(_.answered - done.head.taken) == (1 to n).map(_ * (1L + waits)), s"$waits wait states")
        info(
          s"syncRead = $sync, $waits wait states: $n fetches in ${i.done.last.answered - i.done.head.taken + 1} cycles"
        )
      }
    }

    it should "agree with a memory model on random accesses from both ports" in {
      for (seed <- Seq(11, 12)) simulate(new RamCoreBench(words, sync, WaitStates.Random(3))) { c =>
        val rng   = new Random(seed)
        val i     = new CoreDriver(c.io.i)
        val d     = new CoreDriver(c.io.d)
        val bench = new Bench(c.clock, i, d)
        c.io.seed.poke((seed * 977).U)
        c.reset.poke(true.B)
        c.clock.step()
        c.reset.poke(false.B)
        i.drive(0)
        d.drive(0)
        // I reads the lower half, which D only reads; D writes the upper half
        load(
          c.io.load.valid,
          c.io.load.bits.addr,
          c.io.load.bits.data,
          c.clock,
          (0 until words).map(k => (base + 4L * k, pattern(k)))
        )
        val model = scala.collection.mutable.Map((0 until words).map(k => (base + 4L * k, pattern(k))): _*)
        i.send(Seq.fill(300)(Access(base + 4L * rng.nextInt(words / 2), gap = rng.nextInt(3))): _*)
        d.send(Seq.fill(300) {
          if (rng.nextBoolean()) Access(base + 4L * rng.nextInt(words), gap = rng.nextInt(3))
          else
            Access(base + 4L * (words / 2 + rng.nextInt(words / 2)), rng.nextLong() & 0xffffffffL, 1 + rng.nextInt(15))
        }: _*)
        bench.run("random accesses", 10000)(i.idle && d.idle)
        assert(i.done.size == 300 && d.done.size == 300 && i.spurious == 0 && d.spurious == 0)
        for (o <- i.done) assert(!o.error && o.rdata == pattern(((o.access.addr - base) / 4).toInt), s"seed $seed: $o")
        for (o <- d.done) {
          val a = o.access
          assert(!o.error, s"seed $seed: $o")
          if (a.isWrite) model(a.addr) = SlaveBfm.merge(model(a.addr), a.wdata, a.wstrb)
          else assert(o.rdata == model(a.addr), s"seed $seed: $o")
        }
      }
    }
  }
}
