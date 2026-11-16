// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common.MemMap
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable
import scala.util.Random

/** T5-01 to T5-04 together: [[SocBus]] is the bus of the SoC without a core, with a checker on
  * every master and slave interface. Two cores in Scala drive it with random traffic, and every
  * response is compared with a model of the devices. A protocol violation anywhere stops the
  * simulation, so a test that ends has seen none.
  */
class SocBusSpec extends AnyFlatSpec with ChiselSim {
  private val ramWords              = 256
  private val uartDivider           = 2
  private val rom                   = (0 until 24).map(k => (0x13L + 0x00100000L * k) & 0xffffffffL)
  private val ram                   = MemMap.ramBase
  private def initial(k: Int): Long = (0x85ebca6bL * (k + 3)) & 0xffffffffL

  /** Records the bytes of SimCtrl's PUTC and EXIT events. */
  class Console(c: SocBus) extends Agent {
    val putc                      = mutable.ArrayBuffer[Int]()
    val exit                      = mutable.ArrayBuffer[Int]()
    def drive(cycle: Long): Unit  = ()
    def sample(cycle: Long): Unit = {
      if (c.io.putc.valid.peek().litToBoolean) putc += c.io.putc.bits.peek().litValue.toInt
      if (c.io.exit.valid.peek().litToBoolean) exit += c.io.exit.bits.peek().litValue.toInt
    }
  }

  /** The bus with its two cores, after reset and with the RAM loaded with `initial`. */
  class Rig(c: SocBus, seed: Int = 1) {
    val i       = new CoreDriver(c.io.i)
    val d       = new CoreDriver(c.io.d)
    val console = new Console(c)
    val line    = new UartLine(c.io.txd, uartDivider)
    val bench   = new Bench(c.clock, i, d, console, line)

    // as the harness does: reset is held while the seed is applied and the program is loaded
    c.io.seed.poke(seed.U)
    c.reset.poke(true.B)
    bench.drive()
    for (k <- 0 until ramWords) {
      c.io.load.valid.poke(true.B)
      c.io.load.bits.addr.poke((ram + 4L * k).U)
      c.io.load.bits.data.poke(initial(k).U)
      c.clock.step()
    }
    c.io.load.valid.poke(false.B)
    c.reset.poke(false.B)

    def run(limit: Int = 30000): Unit = bench.run("the traffic", limit)(i.idle && d.idle)
  }

  private val configurations = Seq[(Boolean, WaitStates)](
    (false, WaitStates.Fixed(0)),
    (true, WaitStates.Fixed(0)),
    (false, WaitStates.Fixed(2)),
    (true, WaitStates.Random(3))
  )

  "The SoC bus" should "fetch from the RAM at one word per cycle while the data master is busy elsewhere" in {
    for (sync <- Seq(false, true)) simulate(new SocBus(rom, ramWords, sync)) { c =>
      val rig = new Rig(c)
      val n   = 64
      rig.i.send((0 until n).map(k => Access(ram + 4L * k)): _*)
      // meanwhile: stores and loads in the RAM, and accesses to every other slave
      rig.d.send((0 until 16).flatMap { k =>
        Seq(
          Access(ram + 4L * (128 + k), k, 0xf),
          Access(ram + 4L * (128 + k)),
          Access(MemMap.bootRomBase + 4L * k),
          Access(MemMap.simCtrlBase + MemMap.SimCtrl.Putc, 'a' + k, 0xf),
          Access(MemMap.clintBase + MemMap.Clint.MtimecmpLo, k, 0xf),
          Access(MemMap.uartBase + MemMap.Uart.Status)
        )
      }: _*)
      rig.run()
      assert(rig.i.done.map(_.rdata) == (0 until n).map(initial) && rig.i.done.forall(!_.error))
      assert(rig.i.done.map(_.answered - rig.i.done.head.taken) == (1 to n), "word k is answered in cycle k + 1")
      assert(rig.d.done.forall(!_.error) && rig.console.putc == (0 until 16).map('a' + _))
      val loads = rig.d.done.grouped(6).map(_(1).rdata).toSeq
      assert(loads == (0 until 16), "each load sees the store before it")
      info(
        s"syncRead = $sync: $n sequential fetches through the crossbar in ${rig.i.done.last.answered - rig.i.done.head.taken + 1} cycles"
      )
    }
  }

  it should "fetch from the boot ROM at one word per cycle, and let the data master in when it asks" in {
    simulate(new SocBus(rom, ramWords, syncRead = true)) { c =>
      val rig = new Rig(c)
      rig.i.send(rom.indices.map(k => Access(MemMap.bootRomBase + 4L * k)): _*)
      rig.run()
      assert(rig.i.done.map(_.rdata) == rom)
      assert(rig.i.done.map(_.answered - rig.i.done.head.taken) == (1 to rom.size))
      // both masters read the ROM, the data master without a pause: it gets every turn
      val first = rig.bench.cycle
      rig.i.send(rom.indices.take(8).map(k => Access(MemMap.bootRomBase + 4L * k)): _*)
      rig.d.send(rom.indices.take(8).map(k => Access(MemMap.bootRomBase + 4L * (8 + k))): _*)
      rig.run()
      assert(rig.d.done.map(_.rdata) == rom.slice(8, 16) && rig.i.done.takeRight(8).map(_.rdata) == rom.take(8))
      assert(rig.d.done.map(_.answered - first) == (1 to 8), "D is not delayed")
      assert(rig.i.done.takeRight(8).map(_.answered - first) == (9 to 16), "I follows when D has finished")
    }
  }

  it should "report an access fault for an unmapped address, a write to the ROM and a write to the RAM by I" in {
    simulate(new SocBus(rom, ramWords, syncRead = true)) { c =>
      val rig = new Rig(c)
      rig.d.send(
        Access(0x0),
        Access(0x2000, 1, 0xf),
        Access(MemMap.vgaBase),
        Access(ram + 4L * ramWords),
        Access(MemMap.bootRomBase, 0xbad, 0xf),
        Access(MemMap.bootRomBase),
        Access(MemMap.simCtrlBase + MemMap.SimCtrl.Exit, 0x12a, 0xf)
      )
      rig.i.send(Access(ram + 8, 0xbad, 0xf), Access(ram + 8), Access(0x4000))
      rig.run()
      rig.bench.step(4)
      assert(rig.d.done.map(_.error) == Seq(true, true, true, true, true, false, false))
      assert(rig.d.done(5).rdata == rom.head, "the ROM is unchanged")
      assert(rig.i.done.map(_.error) == Seq(true, false, true) && rig.i.done(1).rdata == initial(2))
      assert(rig.console.exit == Seq(0x2a), "one EXIT event")
    }
  }

  // one model per configuration: each seed starts from a reset, as a new run of the harness would
  for ((sync, waits) <- configurations)
    it should s"agree with a model of its devices on random traffic (syncRead = $sync, $waits, seeds 1 and 2)" in {
      simulate(new SocBus(rom, ramWords, sync, waits, uartDivider)) { c =>
        for (seed <- Seq(1, 2)) {
          val rig = new Rig(c, seed * 0x3c6f)
          val rng = new Random(seed * 100 + (if (sync) 7 else 0))
          import MemMap._
          def gap()  = if (rng.nextInt(4) == 0) rng.nextInt(4) else 0
          def word() = rng.nextLong() & 0xffffffffL
          // a device write has an effect with all four strobes only (D12)
          def strobe() = if (rng.nextInt(5) == 0) 1 + rng.nextInt(14) else 0xf

          // I: fetches from the lower half of the RAM, which D only reads, from the ROM, from a
          // SimCtrl register that never changes, and from nowhere
          val n = 400
          rig.i.send(Seq.fill(n)(rng.nextInt(10) match {
            case 0     => Access(Seq(0x0L, 0x3000L, vgaBase, 0xf0000000L)(rng.nextInt(4)), gap = gap())
            case 1 | 2 => Access(bootRomBase + 4L * rng.nextInt(32), gap = gap())
            case 3     => Access(simCtrlBase + SimCtrl.InstretLo, gap = gap())
            case _     => Access(ram + 4L * rng.nextInt(ramWords / 2), gap = gap())
          }): _*)
          // D: everything
          val registers = Seq(
            clintBase + Clint.Msip,
            clintBase + Clint.MtimecmpLo,
            clintBase + Clint.MtimecmpHi,
            uartBase + Uart.Ctrl,
            simCtrlBase + SimCtrl.Meip
          )
          val counters = Seq(clintBase + Clint.MtimeLo, simCtrlBase + SimCtrl.CycleLo)
          rig.d.send(Seq.fill(n)(rng.nextInt(20) match {
            case 0 => Access(Seq(0x0L, 0x3000L, vgaBase, 0xf0000000L)(rng.nextInt(4)), word(), rng.nextInt(16), gap())
            case 1 => Access(bootRomBase + 4L * rng.nextInt(32), word(), rng.nextInt(16), gap())
            case 2 => Access(uartBase + Uart.TxData, word(), strobe(), gap())
            case 3 => Access(simCtrlBase + SimCtrl.Putc, word(), strobe(), gap())
            case 4 | 5        => Access(registers(rng.nextInt(registers.size)), word(), strobe(), gap())
            case 6 | 7        => Access(registers(rng.nextInt(registers.size)), gap = gap())
            case 8            => Access(counters(rng.nextInt(counters.size)), gap = gap())
            case 9            => Access(uartBase + Uart.Status, gap = gap())
            case 10 | 11 | 12 =>
              Access(ram + 4L * (ramWords / 2 + rng.nextInt(ramWords / 2)), word(), 1 + rng.nextInt(15), gap())
            case _ => Access(ram + 4L * rng.nextInt(ramWords), gap = gap())
          }): _*)
          rig.run()
          rig.bench.step(10 * uartDivider * 12) // the UART sends what is left in its FIFO

          def mapped(addr: Long)        = SocBus.regions(ramWords).exists(r => addr >= r.base && addr < r.base + r.size)
          def inRom(addr: Long)         = addr >= bootRomBase && addr < bootRomBase + bootRomSize
          def romWord(addr: Long): Long = rom.applyOrElse(((addr - bootRomBase) / 4).toInt, (_: Int) => 0L)

          assert(rig.i.done.size == n && rig.d.done.size == n && rig.i.spurious == 0 && rig.d.spurious == 0)
          for (o <- rig.i.done) {
            val a = o.access.addr
            assert(o.error == !mapped(a), s"I: $o")
            val expected =
              if (!mapped(a)) 0 else if (inRom(a)) romWord(a) else if (a >= ram) initial(((a - ram) / 4).toInt) else 0
            assert(o.rdata == expected, s"I: $o")
          }

          // the model: RAM words, the read/write registers (masked to their bits), the two byte sinks
          val memory = mutable.Map((0 until ramWords).map(k => (ram + 4L * k, initial(k))): _*)
          val state  = mutable.Map(registers.map(_ -> 0L): _*)
          state(clintBase + Clint.MtimecmpLo) = 0xffffffffL
          state(clintBase + Clint.MtimecmpHi) = 0xffffffffL
          val oneBit  = Set(clintBase + Clint.Msip, uartBase + Uart.Ctrl, simCtrlBase + SimCtrl.Meip)
          val sent    = mutable.ArrayBuffer[Int]()
          val printed = mutable.ArrayBuffer[Int]()
          val counts  = mutable.Map[Long, Long]().withDefaultValue(0L)
          for (o <- rig.d.done) {
            val a = o.access
            assert(o.error == (!mapped(a.addr) || (inRom(a.addr) && a.isWrite)), s"D: $o")
            if (!o.error) {
              if (a.addr >= ram) {
                if (a.isWrite) memory(a.addr) = SlaveBfm.merge(memory(a.addr), a.wdata, a.wstrb)
                else assert(o.rdata == memory(a.addr), s"D: $o")
              } else if (inRom(a.addr)) assert(o.rdata == romWord(a.addr), s"D: $o")
              else if (state.contains(a.addr)) {
                if (!a.isWrite) assert(o.rdata == state(a.addr), s"D: $o")
                else if (a.wstrb == 0xf) state(a.addr) = if (oneBit(a.addr)) a.wdata & 1 else a.wdata
              } else if (counters.contains(a.addr)) {
                assert(o.rdata > counts(a.addr), s"D: a counter must run on: $o")
                counts(a.addr) = o.rdata
              } else if (a.isWrite && a.wstrb == 0xf) {
                if (a.addr == uartBase + Uart.TxData) sent += (a.wdata & 0xff).toInt
                if (a.addr == simCtrlBase + SimCtrl.Putc) printed += (a.wdata & 0xff).toInt
              }
            } else if (!a.isWrite) assert(o.rdata == 0 || inRom(a.addr), s"D: $o")
          }
          assert(rig.console.putc == printed, "every PUTC once, in order")
          assert(rig.line.bytes == sent, "every UART byte once, in order")
          assert(rig.console.exit.isEmpty)
          c.io.meip.expect((state(simCtrlBase + SimCtrl.Meip) == 1).B)
          c.io.msip.expect((state(clintBase + Clint.Msip) == 1).B)
          info(s"seed $seed: ${rig.bench.cycle} cycles, ${sent.size} UART bytes, ${printed.size} PUTC bytes")
        }
      }
    }
}
