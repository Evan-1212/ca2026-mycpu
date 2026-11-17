// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** OBS-15 on T3: the `csr` probes of the default T3 top (`obs = true`) through a timer interrupt,
  * an `ecall` and the two `mret`s that return from them. In every cycle `csr.mepc`, `csr.mcause`,
  * `csr.mstatus`, `csr.mie` and `csr.mtvec` must equal what the golden model ([[Iss]] with
  * [[IssCsr.T3]], the T3 rules of `IssMachine`) holds after the records of the cycles before. In
  * every cycle with a record, `csr.trap`, `csr.cause`, `csr.mret` and `csr.mip` must say what the
  * record is, `csr.irq` whether an interrupt is pending (the record's `mip`), enabled in `mie` and
  * allowed by `mstatus.MIE` (the program has cycles in which the timer's interrupt is pending and
  * enabled but `MIE` is clear: in the handler), and for a CSR instruction `csr.access`, `addr`, `op`, `write`, `wdata`
  * and `rdata` what it read and wrote. The model runs on the core's records ([[CoSim2.replay]]'s
  * `HardwareSample`), so the interrupt is taken where the core took it, and the record must equal
  * the model's. `csr.wfi` is not exercised: the program has no WFI (`WfiSpec` checks it).
  */
class CsrProbesSpec extends AnyFlatSpec with ChiselSim {
  import CsrProbesSpec._

  "The csr probes of T3" should "show the CSRs as the golden model has them, and each trap, mret and access, through an interrupt, an ecall and mret" in {
    simulate(new Top) { c =>
      val image  = T3.asm(T3.frame(program))
      val seen   = mutable.ArrayBuffer[Map[String, BigInt]]()
      val cycles = T3.capture(c, image) { seen += ProbeValues.peek(c.io.obs.csr.get, "csr"); () }
      val result = CoSim2.replay(cycles.iterator, image, T3.defaultModel)
      result.mismatch.foreach(m => fail(m))
      assert(result.exit.contains(0), s"exit ${result.exit}")
      val checked = check(cycles, seen.toVector, image)
      assert(checked.traps == Seq(Trap.interruptCause(Trap.Mti), Trap.EcallM), s"the traps ${checked.traps}")
      assert(checked.mrets == 2, s"${checked.mrets} mret")
      assert(checked.gated > 0, "no record with an interrupt pending and enabled while MIE is clear")
      assert(checked.accesses >= 8, s"${checked.accesses} CSR instructions")
      assert(
        checked.mstatus.exists(m => (m & 8) != 0) && checked.mstatus.exists(m => (m & 8) == 0),
        "MIE set and clear"
      )
      info(
        s"${cycles.size} cycles, ${checked.gated} records with MTI held back by MIE, mstatus took ${checked.mstatus.map(v => f"0x$v%x").mkString(", ")}"
      )
    }
  }
}

object CsrProbesSpec {
  import org.scalatest.Assertions.assert

  /** A timer interrupt while the program waits for it, then an `ecall`; [[T3.frame]]'s handler
    * silences the timer and returns from both with `mret`.
    */
  val program: String = s"""
      li    t0, 0x80
      csrw  mie, t0
      csrsi mstatus, 8
${T3.armTimer(30)}
    spin:
      beqz  s4, spin
      ecall
      li    a0, 0
  """

  /** What the cycles showed: the causes of the traps, the numbers of `mret`s, of CSR
    * instructions and of records with an interrupt that `MIE` holds back, the `mstatus` values.
    */
  case class Checked(traps: Seq[Long], mrets: Int, accesses: Int, gated: Int, mstatus: Seq[BigInt])

  private val Mret = 0x30200073L

  /** Checks the `csr` probes of `seen` (one map per cycle of `cycles`) against the golden model
    * running `image` on the records of `cycles`.
    */
  def check(cycles: Seq[CoSimCycle], seen: Seq[Map[String, BigInt]], image: MemImage): Checked = {
    assert(cycles.size == seen.size)
    val sample   = new HardwareSample
    val iss      = T3.defaultModel(sample).load(image)
    val traps    = mutable.ArrayBuffer[Long]()
    var mrets    = 0
    var accesses = 0
    var gated    = 0 // records with an interrupt pending and enabled while MIE is clear
    for (((now, p), k) <- cycles.zip(seen).zipWithIndex) {
      for (
        (field, csr) <- Seq(
          "csr.mepc"    -> Csr.Mepc,
          "csr.mcause"  -> Csr.Mcause,
          "csr.mstatus" -> Csr.Mstatus,
          "csr.mie"     -> Csr.Mie,
          "csr.mtvec"   -> Csr.Mtvec
        )
      )
        assert(p(field) == iss.csr(csr), f"cycle $k: $field is 0x${p(field)}%x, the golden model's 0x${iss.csr(csr)}%x")
      def is(field: String, want: BigInt, r: IssRetire) =
        assert(p(field) == want, f"cycle $k: $field is 0x${p(field)}%x, 0x$want%x for $r")
      now.retire match {
        case Some(r) =>
          val mret = r.trap.isEmpty && r.inst == Mret
          is("csr.trap", if (r.trap.isDefined) 1 else 0, r)
          is("csr.mip", now.mip, r)
          val pending = (now.mip & iss.csr(Csr.Mie)) != 0
          val enabled = (iss.csr(Csr.Mstatus) & 8) != 0
          is("csr.irq", if (pending && enabled) 1 else 0, r)
          if (pending && !enabled) gated += 1
          r.trap.foreach { t =>
            is("csr.cause", t.cause, r)
            traps += t.cause
          }
          is("csr.mret", if (mret) 1 else 0, r)
          if (mret) mrets += 1
          if (r.trap.isEmpty) {
            val csrInst = (r.inst & 0x7f) == 0x73 && (r.inst >> 12 & 7) != 0
            is("csr.access", if (csrInst) 1 else 0, r)
            if (csrInst) {
              accesses += 1
              is("csr.addr", r.inst >> 20, r)
              is("csr.op", r.inst >> 12 & 3, r)
              is("csr.write", if (r.csr.isDefined) 1 else 0, r)
              r.csr.foreach(w => is("csr.wdata", w.wdata, r))
              if (r.rd != 0) is("csr.rdata", r.rdValue, r)
            }
          }
          sample.observe(r, now.mip, k + 1)
          val golden = iss.step()
          assert(golden == r, s"cycle $k: the core retired $r, the golden model $golden")
          sample.agreed(iss)
        case None =>
          assert(p("csr.trap") == 0 && p("csr.mret") == 0, s"cycle $k: a trap or mret without a record")
      }
    }
    Checked(traps.toSeq, mrets, accesses, gated, seen.map(_("csr.mstatus")).distinct)
  }
}
