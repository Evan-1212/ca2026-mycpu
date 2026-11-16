// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common.MemMap
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

/** [[Clint]] (T3-05): the registers, the timer interrupt, the tick divisor, and the golden
  * model's CLINT (`mycpu.csrtrap.ClintModel`) as a reference for random accesses.
  */
class ClintSpec extends AnyFlatSpec with ChiselSim {
  import Clint._
  private val base   = MemMap.clintBase
  private val mask32 = (BigInt(1) << 32) - 1

  /** Puts a request on the port, checks that it is answered in this cycle, and returns the data. */
  private def request(c: Clint, offset: Long, wdata: BigInt = 0, wstrb: Int = 0): BigInt = {
    c.io.port.req.valid.poke(true.B)
    c.io.port.req.bits.addr.poke((base + offset).U)
    c.io.port.req.bits.wdata.poke(wdata.U)
    c.io.port.req.bits.wstrb.poke(wstrb.U)
    c.io.port.req.ready.expect(true.B)
    c.io.port.resp.valid.expect(true.B, "answered in the same cycle")
    c.io.port.resp.bits.error.expect(false.B)
    c.io.port.resp.bits.rdata.peek().litValue
  }
  private def idle(c: Clint): Unit = {
    c.io.port.req.valid.poke(false.B)
    c.io.port.req.bits.wstrb.poke(0.U)
  }

  /** A read without a clock edge. */
  private def read(c: Clint, offset: Long): BigInt = {
    val data = request(c, offset)
    idle(c)
    data
  }

  /** A full-word store: one cycle. */
  private def store(c: Clint, offset: Long, data: BigInt, wstrb: Int = 0xf): Unit = {
    request(c, offset, data, wstrb)
    c.clock.step()
    idle(c)
  }

  private def start(c: Clint): Unit = {
    idle(c)
    c.reset.poke(true.B)
    c.clock.step(2)
    c.reset.poke(false.B)
  }
  private def mtime(c: Clint): BigInt    = (read(c, MtimeHi) << 32) | read(c, MtimeLo)
  private def mtimecmp(c: Clint): BigInt = (read(c, MtimecmpHi) << 32) | read(c, MtimecmpLo)

  "Clint" should "use the offsets of D12 and of the golden model" in {
    assert((Msip, MtimecmpLo, MtimecmpHi, MtimeLo, MtimeHi) == ((0x0, 0x4000, 0x4004, 0xbff8, 0xbffc)))
  }

  it should "reset msip and mtime to 0 and mtimecmp to all ones, with no interrupt pending" in {
    simulate(new Clint()) { c =>
      start(c)
      assert(read(c, Msip) == 0)
      assert(mtime(c) == 0, "the first cycle after reset reads 0")
      assert(mtimecmp(c) == (BigInt(1) << 64) - 1)
      c.io.msip.expect(false.B)
      c.io.mtip.expect(false.B)
      c.io.time.expect(0.U)
      c.io.port.resp.valid.expect(false.B, "no answer without a request")
      assert(read(c, 0x8) == 0 && read(c, 0x4008) == 0 && read(c, 0xbff4) == 0, "an unknown offset reads 0")
    }
  }

  it should "count mtime once per cycle and show it on the time output" in {
    simulate(new Clint()) { c =>
      start(c)
      c.clock.step(25)
      assert(mtime(c) == 25)
      c.io.time.expect(25.U)
      c.clock.step(3)
      c.io.time.expect(28.U)
    }
  }

  it should "write the halves of mtimecmp independently" in {
    simulate(new Clint()) { c =>
      start(c)
      store(c, MtimecmpLo, 0x12345678L)
      assert(mtimecmp(c) == ((mask32 << 32) | 0x12345678L), "the high half is untouched")
      store(c, MtimecmpHi, 0x9abcdef0L)
      assert(mtimecmp(c) == ((BigInt(0x9abcdef0L) << 32) | 0x12345678L))
      store(c, MtimecmpLo, 0)
      assert(mtimecmp(c) == BigInt(0x9abcdef0L) << 32, "the high half is untouched")
      store(c, MtimecmpHi, 0)
      assert(mtimecmp(c) == 0)
    }
  }

  it should "write the halves of mtime independently, the tick of the store's cycle counting on top" in {
    simulate(new Clint()) { c =>
      start(c)
      c.clock.step(4)
      store(c, MtimeHi, 0x5)
      assert(mtime(c) == (BigInt(5) << 32) + 5, "the low half went on counting: 4 + 1")
      store(c, MtimeLo, 0x1000)
      assert(mtime(c) == (BigInt(5) << 32) + 0x1001, "the stored value plus the tick of that cycle")
      // the tick after a store of all ones to the low half carries into the high half
      store(c, MtimeLo, mask32)
      assert(mtime(c) == BigInt(6) << 32)
      c.io.time.expect((BigInt(6) << 32).U)
      store(c, MtimeHi, mask32)
      store(c, MtimeLo, mask32)
      assert(mtime(c) == 0, "mtime wraps at 2^64")
    }
  }

  it should "raise MTIP exactly while mtime >= mtimecmp, unsigned" in {
    simulate(new Clint()) { c =>
      start(c)
      store(c, MtimecmpHi, 0)
      store(c, MtimecmpLo, 10) // the second cycle after reset: mtime is 2 afterwards
      for (t <- 2 to 14) {
        assert(mtime(c) == t)
        c.io.mtip.expect((t >= 10).B, s"mtime $t, mtimecmp 10")
        c.clock.step()
      }
      store(c, MtimecmpLo, 1000) // moving the compare value ahead clears the interrupt
      c.io.mtip.expect(false.B)
      store(c, MtimecmpLo, 0)
      c.io.mtip.expect(true.B)
      // unsigned: a compare value with bit 63 set is far in the future, a time with it set is late
      store(c, MtimecmpHi, 0x80000000L)
      c.io.mtip.expect(false.B)
      store(c, MtimeHi, 0x80000000L)
      c.io.mtip.expect(true.B)
      store(c, MtimeHi, 0x7fffffffL)
      c.io.mtip.expect(false.B)
      // equal counts as reached
      store(c, MtimecmpHi, 0)
      store(c, MtimeHi, 0)
      val now = mtime(c)
      store(c, MtimecmpLo, now + 3) // stored while mtime is now; the next cycle reads now + 1
      c.io.mtip.expect(false.B)
      c.clock.step()
      c.io.mtip.expect(false.B)
      c.clock.step()
      assert(mtime(c) == now + 3)
      c.io.mtip.expect(true.B)
    }
  }

  it should "show no transient MTIP during the update low = all ones, high, low" in {
    simulate(new Clint()) { c =>
      start(c)
      // mtime = 0x1_8000_00xx, between the values that the compare register passes through below
      store(c, MtimeHi, 0x1)
      store(c, MtimeLo, 0x80000000L)
      store(c, MtimecmpHi, 0x1)
      store(c, MtimecmpLo, 0x90000000L) // mtimecmp = 0x1_9000_0000: not pending
      c.io.mtip.expect(false.B)
      // each update goes through low = all ones, the new high half, the new low half, and the
      // interrupt must stay low after every one of the three stores. In each update the new low
      // half under the old high half, or the new high half under the old low half, is in the
      // past: one of the two naive orders would raise the interrupt for a moment.
      def update(high: BigInt, low: BigInt): Unit = {
        val (oldHigh, oldLow) = (read(c, MtimecmpHi), read(c, MtimecmpLo))
        val now               = mtime(c) + 3
        assert(((oldHigh << 32) | low) <= now || ((high << 32) | oldLow) <= now, "the update must need the sequence")
        request(c, MtimecmpLo, mask32, 0xf)
        c.io.mtip.expect(false.B)
        c.clock.step()
        c.io.mtip.expect(false.B, "after low = all ones")
        request(c, MtimecmpHi, high, 0xf)
        c.clock.step()
        c.io.mtip.expect(false.B, "after the new high half")
        request(c, MtimecmpLo, low, 0xf)
        c.clock.step()
        idle(c)
        c.io.mtip.expect(false.B, "after the new low half")
        assert(mtimecmp(c) == ((high << 32) | low))
      }
      update(0x2, 0x1000)    // low half first would pass through 0x1_0000_1000, which is in the past
      store(c, MtimeHi, 0x2) // mtime = 0x2_8000_00xx, now behind mtimecmp = 0x2_0000_1000
      c.io.mtip.expect(true.B)
      store(c, MtimecmpLo, 0x90000000L)
      c.io.mtip.expect(false.B)
      update(0x3, 0x2000)      // low half first: 0x2_0000_2000
      update(0x2, 0xf0000000L) // back; high half first would pass through 0x2_0000_2000
      // the naive order does show the transient: from 0x2_f000_0000 to 0x3_0000_0000, low half first
      store(c, MtimecmpLo, 0)
      c.io.mtip.expect(true.B, "0x2_0000_0000 is in the past: the reason for the safe sequence")
      store(c, MtimecmpHi, 0x3)
      c.io.mtip.expect(false.B)
    }
  }

  it should "drive msip from bit 0 of the msip register" in {
    simulate(new Clint()) { c =>
      start(c)
      store(c, Msip, 1)
      c.io.msip.expect(true.B)
      assert(read(c, Msip) == 1)
      store(c, Msip, 0xfffffffeL)
      c.io.msip.expect(false.B, "only bit 0 exists")
      assert(read(c, Msip) == 0)
      store(c, Msip, mask32)
      c.io.msip.expect(true.B)
      assert(read(c, Msip) == 1, "the other bits read 0")
      c.io.mtip.expect(false.B, "msip has nothing to do with the timer")
    }
  }

  it should "answer and ignore a store narrower than a word" in {
    simulate(new Clint()) { c =>
      start(c)
      store(c, MtimecmpLo, 0x11111111L)
      store(c, MtimecmpHi, 0x22222222L)
      for (wstrb <- 1 until 15) {
        store(c, Msip, mask32, wstrb)
        store(c, MtimecmpLo, mask32, wstrb)
        store(c, MtimecmpHi, 0, wstrb)
        val before = mtime(c)
        store(c, MtimeLo, 0x40000000L, wstrb)
        store(c, MtimeHi, 0x7, wstrb)
        assert(mtime(c) == before + 2, s"mtime only counted, strobes $wstrb")
        assert(read(c, Msip) == 0, s"strobes $wstrb")
        assert(mtimecmp(c) == ((BigInt(0x22222222L) << 32) | 0x11111111L), s"strobes $wstrb")
      }
      c.io.msip.expect(false.B)
    }
  }

  it should "count mtime once every tickDivisor cycles" in {
    for (divisor <- Seq(2, 3, 8)) {
      simulate(new Clint(tickDivisor = divisor)) { c =>
        start(c)
        for (cycle <- 0 until 5 * divisor) {
          assert(mtime(c) == cycle / divisor, s"cycle $cycle with divisor $divisor")
          c.clock.step()
        }
        // a store does not disturb the prescaler: the next tick comes when it was due
        store(c, MtimecmpHi, 0) // cycle 5 * divisor: a tick boundary has just passed
        val stored = BigInt(1000)
        store(c, MtimeLo, stored) // the second cycle of the period (a tick only if divisor is 2)
        val first = if (divisor == 2) 1 else 0
        assert(mtime(c) == stored + first)
        c.clock.step(divisor)
        assert(mtime(c) == stored + first + 1)
        store(c, MtimecmpLo, stored + first + 3)
        c.clock.step(divisor - 1)
        c.io.mtip.expect(false.B)
        c.clock.step(divisor)
        c.io.mtip.expect(true.B)
      }
    }
  }

  it should "agree with the golden model's CLINT on random loads and stores, cycle by cycle" in {
    simulate(new Clint()) { c =>
      start(c)
      val model   = new ClintModel()
      val rng     = new Random(2026)
      val offsets = Seq(Msip, MtimecmpLo, MtimecmpHi, MtimeLo, MtimeHi, 0x8, 0x4008, 0xbff4, 0xfffc)
      val values  = Seq(BigInt(0), BigInt(1), BigInt(2), mask32, mask32 - 1, BigInt(0x80000000L), BigInt(0x7fffffffL))
      var stores  = 0
      var pending = 0
      val cycles  = 20000
      for (cycle <- 0 until cycles) {
        // the outputs and every register, before anything happens in this cycle
        c.io.msip.expect(model.msip.B, s"cycle $cycle")
        c.io.mtip.expect(model.mtip.B, s"cycle $cycle")
        c.io.time.expect((BigInt(model.mtime) & ((BigInt(1) << 64) - 1)).U, s"cycle $cycle")
        if (model.mtip) pending += 1
        rng.nextInt(4) match {
          case 0 => // a store of any width at any byte offset
            val offset = offsets(rng.nextInt(offsets.size))
            // near the present, so that MTIP comes and goes
            val near  = (BigInt(model.mtime) & mask32) + rng.nextInt(64) - 16
            val data  = if (rng.nextBoolean()) values(rng.nextInt(values.size)) else near & mask32
            val bytes = Seq(4, 4, 4, 2, 1)(rng.nextInt(5))
            val lane  = if (bytes == 4) 0 else rng.nextInt(4 / bytes) * bytes
            request(c, offset, data << (8 * lane) & mask32, ((1 << bytes) - 1) << lane)
            model.write(base + offset + lane, bytes, data.toLong & ((1L << (8 * bytes)) - 1))
            stores += 1
          case 1 => // a load
            val offset = offsets(rng.nextInt(offsets.size))
            assert(request(c, offset) == model.read(base + offset, 4), f"cycle $cycle, a load at 0x$offset%x")
          case _ => idle(c)
        }
        c.clock.step()
        model.elapse(1)
      }
      idle(c)
      assert(stores > 4000 && pending > 1000 && pending < cycles - 1000, s"$stores stores, MTIP in $pending cycles")
      info(s"$cycles cycles, $stores stores, MTIP pending in $pending of them")
    }
  }
}
