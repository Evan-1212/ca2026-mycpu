// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.rv32im.Divider
import org.scalatest.flatspec.AnyFlatSpec

/** T4-04 on every variant: traps are taken in MEM in program order and leave nothing of the
  * instructions they cancel; CSR side effects cannot outlive an older trap; an interrupt during a
  * divider stall abandons the division, with `mepc` the division's pc, and the division starts
  * again after the `mret`. Every run is also compared with the golden model, record by record.
  */
class PreciseTrapSpec extends AnyFlatSpec with ChiselSim {
  private val fault = "0x30000000" // no device there: a load or store faults (cause 5 or 7)

  for (variant <- T4.variants)
    s"The $variant pipeline" should "keep its traps precise, its CSR effects ordered and its divisions restartable" in {
      simulate(T4.top(variant)) { c =>
        for ((what, check) <- checks) withClue(s"$what: ")(check(c))
      }
    }

  /** The checks, each on a system that is reset by the program it loads. */
  private val checks: Seq[(String, SimTop => Unit)] = Seq(
    "not trap on an exception in the shadow of a taken branch or jump" -> { c =>
      val result = T4.run(
        c,
        s"""
        li    t0, $fault
        beq   zero, zero, 1f
        .word 0                    # illegal
        ecall
        lw    a1, 0(t0)            # access fault
        1:
        jal   zero, 2f
        ebreak
        sw    a1, 0(t0)
        .word 0xffffffff
        2:
        la    t1, 3f
        jalr  zero, 0(t1)
        ecall
        .word 0
        3:
        csrr  a1, mscratch
        """
      )
      assert(T4.traps(result).isEmpty, "a canceled instruction trapped")
      assert(T4.reg(result, "s4") == 0)
    },
    "trap on the older of two exceptions in flight, then on the younger" -> { c =>
      val result = T4.run(
        c,
        s"""
        li    t0, $fault
        la    t1, 1f
        lw    a1, 0(t0)            # load access fault, in MEM ...
        .word 0                    # ... while this illegal word is in EX or ID
        ecall
        jalr  zero, 2(t1)          # misaligned target: found in EX (or ID), after the older ones
        1:
        sw    a1, 0(t0)            # store access fault
        ebreak
        """
      )
      val causes   = T4.traps(result).map(_.trap.get.code.toLong)
      val expected =
        Seq(Trap.LoadAccessFault, Trap.Illegal, Trap.EcallM, Trap.InstMisaligned, Trap.StoreAccess, Trap.Breakpoint)
      assert(causes == expected, s"causes $causes")
    },
    "let no CSR instruction younger than a trapping instruction take effect" -> { c =>
      val result = T4.run(
        c,
        s"""
        li    a2, 9
        csrw  mscratch, a2
        li    t0, $fault
        la    t4, 1f               # the handler returns here
        li    a3, 0x88
        lw    a1, 0(t0)            # access fault
        csrw  mscratch, zero       # canceled: mscratch stays 9
        csrw  mie, a3              # canceled: mie stays 0
        csrrw a4, mtvec, zero      # canceled: mtvec stays
        1:
        csrr  a5, mscratch
        csrr  a6, mie
        la    t4, 2f
        ecall                      # an exception that is not a memory one
        csrw  mscratch, zero
        mret                       # canceled too: mstatus stays
        2:
        csrr  a7, mscratch
        csrr  s5, mstatus
        """
      )
      assert(T4.traps(result).size == 2)
      assert(T4.reg(result, "a5") == 9 && T4.reg(result, "a6") == 0 && T4.reg(result, "a7") == 9)
      assert(T4.reg(result, "s5") == CsrFileMpp, "mstatus after the trap and its mret")
      assert(T4.traps(result).forall(r => r.rd == 0 && r.mem.isEmpty && r.csr.isEmpty))
    },
    "write no register, no memory and no CSR for an instruction that traps" -> { c =>
      val result = T4.run(c, mycpu.csrtrap.PreciseTrapSpec.nothingWritten)
      assert(T4.traps(result).size == 4)
      assert(T4.reg(result, "a3") == 5 && T4.reg(result, "a4") == 0 && T4.reg(result, "a5") == 9)
    },
    "abandon a division for an interrupt and start it again after the mret, wherever the interrupt lands" -> { c =>
      var landed = Set.empty[String]
      var killed = 0
      for (delay <- PreciseTrapSpec.programs.indices) {
        val (image, s) = PreciseTrapSpec.programs(delay)
        var putcs      = 0
        val run        = T4.timed(c, image)(if (c.io.putc.valid.peek().litToBoolean) putcs += 1)
        val result     = run.result
        val trace      = result.trace
        assert(putcs == 1, s"delay $delay: $putcs bytes printed")
        assert(T4.reg(result, "a4") == 1, s"delay $delay: the word was incremented once")
        assert(T4.reg(result, "a3") == 142 && T4.reg(result, "a5") == 143, s"delay $delay: the division")
        val taken = trace.filter(_.trap.exists(_.interrupt))
        assert(taken.size <= 1 && T4.reg(result, "s4") == taken.size, s"delay $delay: one interrupt at most")
        val at = taken.headOption.fold(-1L)(_.pc)
        for ((label, pc) <- s if Seq("store", "putc", "divide", "after").contains(label) && pc == at) landed += label
        // on the division while the divider was busy: the cycles before the interrupt have no record
        val cycles = run.cycles
        val index  = cycles.indexWhere(_.retire.exists(_.trap.exists(_.interrupt)))
        if (at == s("divide") && cycles(index - 1).retire.isEmpty && cycles(index - 2).retire.isEmpty) {
          killed += 1
          // the division starts again and takes its whole latency in EX
          assert(run.idleBefore(s("divide")) >= Divider.DIV_LATENCY, s"delay $delay")
        }
      }
      assert(landed == Set("store", "putc", "divide", "after"), s"the interrupt landed on $landed")
      assert(killed > 20, s"$killed runs took the interrupt while the divider was busy")
      info(s"the interrupt landed on ${landed.toSeq.sorted.mkString(", ")}; $killed times during the division")
    }
  )

  /** `mstatus` with MIE 0 and MPIE 1 (after an mret), MPP 3. */
  private val CsrFileMpp = mycpu.csrtrap.CsrFile.mstatusMpp | 0x80L
}

object PreciseTrapSpec {

  /** T3's programs of its precise-trap spec (`mycpu.csrtrap.PreciseTrapSpec.program`), for the
    * delays 0 to 80, assembled once.
    */
  lazy val programs: IndexedSeq[(MemImage, Map[String, Long])] =
    (0 to 80).map(mycpu.csrtrap.PreciseTrapSpec.program)
}
