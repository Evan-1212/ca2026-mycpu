// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import mycpu.csrtrap.{RiscvTests, T3}
import mycpu.pipeline.Variant
import org.scalatest.flatspec.AnyFlatSpec

/** riscv-tests on the SoC: the `rv32ui` and `rv32um` tests of T1 and T2 and T3's machine-mode
  * tests (`t3mi`) in T3's environment (`mycpu.csrtrap.RiscvTests`), and the bus tests of this
  * task (`t5bus`, [[RiscvTestsSpec.busTests]]), each from the boot ROM on and record by record
  * against the golden model.
  *
  * The environment variable `MUTANT` names a bug to inject (`no-decerr` of the crossbar,
  * `ram-wstrb-reversed` of the RAM, `vga-no-id` of the VGA, `uart-no-hold` of the UART, or a
  * mutant of the core, `sra`), on the variant that `VARIANT` names (Forward by default); the spec
  * must then fail (VER-04): `make test-riscv-tests MUTANT=<name> [VARIANT=<v>]`.
  */
class RiscvTestsSpec extends AnyFlatSpec with ChiselSim {
  private val mutant   = sys.env.get("MUTANT").filter(_.nonEmpty)
  private val variants =
    if (mutant.isDefined) Seq(sys.env.get("VARIANT").filter(_.nonEmpty).map(Variant(_)).getOrElse(Variant.Forward))
    else Variant.all
  private val mustFail = mutant.fold("")(m => s" (mutant $m: must fail)")
  private val suites   = RiscvTests.suites :+ ("t5bus" -> RiscvTestsSpec.busTests)

  for (variant <- variants) {
    s"The SoC with the $variant pipeline" should s"pass every rv32ui, rv32um, t3mi and t5bus test$mustFail" in {
      simulate(T5.soc(variant, mutant = mutant)) { c =>
        val failures = suites.flatMap { case (suite, tests) =>
          val failed = tests.flatMap { case (name, image) =>
            val result = CoSim2.check(c, image, T5.defaultModel, maxCycles = 200000)
            result.mismatch.orElse(RiscvTests.verdict(result.exit)).map(why => s"$suite-$name: $why")
          }
          info(s"$suite: ${tests.size - failed.size} of ${tests.size} passed")
          failed
        }
        assert(failures.isEmpty, failures.mkString("\n", "\n", ""))
      }
    }
  }
}

object RiscvTestsSpec {
  import MemMap._

  /** A test in T3's frame (`T3.frame`): `body` sets `t4` to where a faulting access returns, makes
    * the access, and the checks after it compare the trap that the handler recorded (`s1` mcause,
    * `s3` mtval, `s4` the count) with what is expected; case n fails with exit 2n + 1.
    */
  private def faults(cases: Seq[(String, Long, Long)]): String = {
    val body =
      for (((access, cause, tval), n) <- cases.zipWithIndex)
        yield f"""
      la    t4, 1f
      li    a1, 0x$tval%x
      $access
    1:
      li    a0, ${2 * (n + 1) + 1}
      li    t0, ${n + 1}
      bne   s4, t0, done
      li    t0, $cause
      bne   s1, t0, done
      bne   s3, a1, done
"""
    T3.frame(body.mkString + "      li    a0, 0\n")
  }

  /** The bus of the SoC (T5-03, T5-04): what each region answers. An address in no region is a
    * load, store or instruction access fault (`DECERR`), a write to the boot ROM a store access
    * fault (`SLVERR`), a read of it the ROM's word; the VGA's `ID` and the CLINT's `mtime` answer.
    */
  lazy val busTests: Seq[(String, MemImage)] = Seq(
    "decerr" -> T5.asm(
      faults(
        Seq(
          ("lw    t1, 0(a1)", Trap.LoadAccessFault, 0x30000000L),
          ("sw    t1, 0(a1)", Trap.StoreAccess, 0x40000000L),
          ("lb    t1, 0(a1)", Trap.LoadAccessFault, 0x00000000L),
          ("jalr  ra, 0(a1)", Trap.InstAccessFault, 0x00800000L)
        )
      )
    ),
    "rom" -> T5.asm(
      faults(
        Seq(
          ("sw    zero, 0(a1)", Trap.StoreAccess, bootRomBase),
          ("sb    zero, 0(a1)", Trap.StoreAccess, bootRomBase + 3)
        )
      )
    ),
    // 12 stores to TXDATA: the UART takes the first ones at once (those its FIFO holds, and one
    // that goes to the transmitter) and holds the rest until a frame ends (16 clock cycles a bit,
    // 10 bits a frame by default), so the CLINT's mtime advances by at least 2 frames' worth, far
    // more than 100 ticks, between the first and the last
    "uart" -> T5.asm(f"""
      li    a0, 3
      li    t0, 0x$uartBase%x
      li    t3, 0x${clintBase + MemMap.Clint.MtimeLo}%x
      li    t1, 0x61
      lw    s0, 0(t3)
      .rept 12
      sw    t1, ${MemMap.Uart.TxData}(t0)
      .endr
      lw    s1, 0(t3)
      sub   s1, s1, s0
      li    t2, 100
      bltu  s1, t2, 1f           # a store did not wait for the FIFO
      li    a0, 0
    1:
      li    t0, 0x${simCtrlBase + MemMap.SimCtrl.Exit}%x
      sw    a0, 0(t0)
    2:
      j     2b
    """),
    "devices" -> T5.asm(f"""
      li    a0, 3
      li    t0, 0x$bootRomBase%x
      lw    t1, 0(t0)
      li    t2, 0x00000297       # the ROM's first word: auipc t0, 0
      bne   t1, t2, 1f
      li    a0, 5
      li    t0, 0x$vgaBase%x
      lw    t1, 0(t0)
      li    t2, 0x${mycpu.soc.Vga.Id}%x
      bne   t1, t2, 1f
      li    a0, 7
      li    t0, 0x${clintBase + MemMap.Clint.MtimeLo}%x
      lw    t1, 0(t0)
      lw    t2, 0(t0)
      bgeu  t1, t2, 1f           # mtime counts
      li    a0, 0
    1:
      li    t0, 0x${simCtrlBase + MemMap.SimCtrl.Exit}%x
      sw    a0, 0(t0)
    2:
      j     2b
    """)
  )
}
