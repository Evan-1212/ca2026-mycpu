// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.csrtrap.T3
import mycpu.pipeline.{PipelineConfig, Variant}
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** The default SoC (`obs = true`) with the data master's interface as an output of its own, read
  * on the crossbar's side of the connection: the same nets on which the data master's
  * [[Axi4LiteChecker]] sits, read at the other end of the connection than [[Soc.busProbe]] reads
  * them.
  */
class BusRefSoc(config: SocConfig) extends Soc(config) {
  val ref       = IO(Output(new ObsBus))
  private val d = xbar.io.d
  ref.aw.valid := d.aw.valid
  ref.aw.ready := d.aw.ready
  ref.aw.addr  := d.aw.bits.addr
  ref.w.valid  := d.w.valid
  ref.w.ready  := d.w.ready
  ref.w.data   := d.w.bits.data
  ref.w.strb   := d.w.bits.strb
  ref.b.valid  := d.b.valid
  ref.b.ready  := d.b.ready
  ref.b.resp   := d.b.bits.resp
  ref.ar.valid := d.ar.valid
  ref.ar.ready := d.ar.ready
  ref.ar.addr  := d.ar.bits.addr
  ref.r.valid  := d.r.valid
  ref.r.ready  := d.r.ready
  ref.r.data   := d.r.bits.data
  ref.r.resp   := d.r.bits.resp
}

/** OBS-15 on T5: the `bus` probes of the SoC on a store and a load to the RAM and a store and a load
  * to an address without a device, through the data master. In every cycle each field of `bus`
  * must equal the signal of the data master's interface that it names, as the crossbar sees it
  * ([[BusRefSoc]]). The probes must show each store as an AW and a W transfer with its address,
  * data and strobes and then a B transfer, and each load as an AR transfer and then an R
  * transfer, each at the latest in the cycle of the instruction's record: OKAY and the stored word from the RAM, DECERR
  * where no device answers (the access faults of the two instructions). Every transfer is
  * answered (as many B as AW and W, as many R as AR). Two pairs of fields carry equal signals in
  * the reference's master, so a probe that swapped them would pass: `aw.addr` and `ar.addr` (both
  * the request's address) and `b.ready` and `r.ready` (both always 1). The checkers of the SoC stop the
  * simulation at a protocol violation, so a run that ends has none. The program runs on the SoC
  * as it is and with 2 wait states on the RAM, which delay its B and R: the RAM's B comes at
  * least w + 1 cycles after the later of AW and W, its R at least w + 1 cycles after AR, for w
  * wait states.
  */
class BusProbeSpec extends AnyFlatSpec with ChiselSim {
  import BusProbeSpec._

  private def base = SocConfig(T5.bootRom, PipelineConfig(Variant.Forward))

  "The bus probes of T5" should "show the AW, W and B transfers of stores and the AR and R transfers of loads" in {
    simulate(new BusRefSoc(base))(c => check(c, waits = 0))
  }

  they should "show the same with wait states on the RAM, B and R as late as the wait states make them" in {
    simulate(new BusRefSoc(base.copy(ramWait = WaitStates.Fixed(2))))(c => check(c, waits = 2))
  }

  private def check(c: BusRefSoc, waits: Int): Unit = {
    val assembled = AsmX.assemble(T3.frame(program))
    val image     = MemImage(assembled)
    val cell      = assembled.symbols("cell")
    val seen      = mutable.ArrayBuffer[(Map[String, BigInt], Map[String, BigInt])]()
    val cycles    = T3.capture(c, image, 20000, after = CoSimRules().exitLead) {
      seen += ProbeValues.peek(c.io.obs.bus.get, "bus") -> ProbeValues.peek(c.ref, "bus"); ()
    }
    val result = CoSim2.replay(cycles.iterator, image, T5.defaultModel, maxCycles = 20000)
    result.mismatch.foreach(m => fail(m))
    assert(result.exit.contains(0), s"exit ${result.exit}")
    for (((probe, ref), k) <- seen.zipWithIndex; (name, v) <- probe)
      assert(v == ref(name), s"cycle $k: probe $name is $v, the data master's interface ${ref(name)}")
    val bus = seen.map(_._1).toSeq
    val t   = transfers(bus)
    assert(t("aw").size == t("b").size && t("w").size == t("b").size, s"AW, W and B transfers: $t")
    assert(t("ar").size == t("r").size, s"AR and R transfers: $t")

    def isTrap(cause: Long)(r: IssRetire) = r.trap.exists(_.cause == cause)
    val stores                            = Seq(
      (cell, Word, 0, recordAt(cycles, r => r.mem.exists(m => m.write && m.addr == cell), "the store")),
      (Unmapped, Other, Decerr, recordAt(cycles, isTrap(Trap.StoreAccess), "the store access fault"))
    )
    val writes = for ((addr, data, resp, record) <- stores) yield {
      val what = f"the store to 0x$addr%08x"
      val aw   = only(t("aw").filter(k => bus(k)("bus.aw.addr") == addr), s"$what: AW")
      val w    = only(t("w").filter(k => bus(k)("bus.w.data") == data), s"$what: W")
      assert(bus(w)("bus.w.strb") == 0xf, s"$what, cycle $w: the W transfer ${bus(w)}")
      val b = t("b").find(_ > (aw max w)).getOrElse(fail(s"$what: no B transfer after its AW and W"))
      assert(bus(b)("bus.b.resp") == resp && b <= record, s"$what, cycle $b: B ${bus(b)}, the record in cycle $record")
      (b - (aw max w), s"AW $aw W $w B $b record $record")
    }
    val loads = Seq(
      (cell, Some(Word), 0, recordAt(cycles, r => r.mem.exists(m => !m.write && m.addr == cell), "the load")),
      (Unmapped, None, Decerr, recordAt(cycles, isTrap(Trap.LoadAccessFault), "the load access fault"))
    )
    val reads = for ((addr, data, resp, record) <- loads) yield {
      val what = f"the load from 0x$addr%08x"
      val ar   = only(t("ar").filter(k => bus(k)("bus.ar.addr") == addr), s"$what: AR")
      val r    = t("r").find(_ > ar).getOrElse(fail(s"$what: no R transfer after its AR"))
      assert(bus(r)("bus.r.resp") == resp && r <= record, s"$what, cycle $r: R ${bus(r)}, the record in cycle $record")
      data.foreach(d => assert(bus(r)("bus.r.data") == d, s"$what, cycle $r: R ${bus(r)}"))
      (r - ar, s"AR $ar R $r record $record")
    }
    assert(writes(0)._1 >= waits + 1, s"the RAM's B ${writes(0)._1} cycles after AW and W, with $waits wait states")
    assert(reads(0)._1 >= waits + 1, s"the RAM's R ${reads(0)._1} cycles after AR, with $waits wait states")
    info(s"RAM store: ${writes(0)._2}; load: ${reads(0)._2}; no device, store: ${writes(1)._2}; load: ${reads(1)._2}")
  }
}

object BusProbeSpec {
  import org.scalatest.Assertions.fail

  val Word: Long     = 0x12345678L
  val Other: Long    = 0x0badf00dL
  val Unmapped: Long = 0x30000000L // no device of the D12 memory map
  val Decerr: Int    = 3

  /** In [[T3.frame]], whose handler returns after an access fault: a store of [[Word]] to the RAM
    * and a load of it back, then a store of [[Other]] and a load at [[Unmapped]].
    */
  val program: String = f"""
      la    t0, cell
      li    t1, 0x$Word%x
      sw    t1, 0(t0)
      lw    t2, 0(t0)
      li    t3, 0x$Unmapped%x
      li    a1, 0x$Other%x
      sw    a1, 0(t3)
      lw    t2, 0(t3)
      j     1f
      .balign 4
    cell:
      .word 0
    1:
  """

  /** The cycles of the transfers of each channel (`aw`, `w`, `b`, `ar`, `r`): VALID and READY high. */
  def transfers(bus: Seq[Map[String, BigInt]]): Map[String, Seq[Int]] =
    Seq("aw", "w", "b", "ar", "r").map { ch =>
      ch -> bus.indices.filter(k => bus(k)(s"bus.$ch.valid") == 1 && bus(k)(s"bus.$ch.ready") == 1)
    }.toMap

  /** The cycle of the one record that `is` accepts. */
  def recordAt(cycles: Seq[CoSimCycle], is: IssRetire => Boolean, what: String): Int =
    only(cycles.indices.filter(k => cycles(k).retire.exists(is)), what)

  def only(ks: Seq[Int], what: String): Int = ks match {
    case Seq(k) => k
    case _      => fail(s"$what: cycles ${ks.mkString(" ")}, not one")
  }
}
