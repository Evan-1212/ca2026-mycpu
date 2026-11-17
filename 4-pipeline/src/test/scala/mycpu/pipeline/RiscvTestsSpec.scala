// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3.simulator.scalatest.ChiselSim
import mycpu.csrtrap.RiscvTests
import org.scalatest.flatspec.AnyFlatSpec

/** riscv-tests on every variant: the `rv32ui` and `rv32um` tests of T1 and T2 and T3's machine-mode
  * tests (`t3mi`), in T3's environment (`mycpu.csrtrap.RiscvTests`), record by record against the
  * golden model.
  *
  * The environment variable `MUTANT` names a bug to inject (`PipelineConfig.mutants`, or one of
  * `MutantEmit.names`: `mtip-bit5` in the CSR file, `no-flush` in the flush unit; see [[MutantEmit]]),
  * on the variant that `VARIANT` names (Forward by default); the spec must then fail (VER-04):
  * `make test-riscv-tests MUTANT=<name> [VARIANT=<v>]`.
  */
class RiscvTestsSpec extends AnyFlatSpec with ChiselSim {
  private val mutant   = sys.env.get("MUTANT").filter(_.nonEmpty)
  private val variants =
    if (mutant.isDefined) Seq(sys.env.get("VARIANT").filter(_.nonEmpty).map(Variant(_)).getOrElse(Variant.Forward))
    else T4.variants
  private val mustFail = mutant.fold("")(m => s" (mutant $m: must fail)")

  for (variant <- variants) {
    s"The $variant pipeline" should s"pass every rv32ui, rv32um and t3mi test$mustFail" in {
      val params = Map("variant" -> variant.name) ++ mutant.map("mutant" -> _)
      simulate(MutantEmit.top(params)) { c =>
        val failures = RiscvTests.suites.flatMap { case (suite, tests) =>
          val failed = tests.flatMap { case (name, image) =>
            RiscvTests.run(c, image).map(why => s"$suite-$name: $why")
          }
          info(s"$suite: ${tests.size - failed.size} of ${tests.size} passed")
          failed
        }
        assert(failures.isEmpty, failures.mkString("\n", "\n", ""))
      }
    }
  }
}
