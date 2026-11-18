// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3.simulator.scalatest.ChiselSim
import mycpu.csrtrap.RiscvTests
import org.scalatest.flatspec.AnyFlatSpec

/** VER-04 for T4: each mutant, on the variants listed for it below, fails a riscv-test against
  * the golden model, so the checks of this task cannot pass vacuously. The
  * `make` targets are checked the same way by `make test-riscv-tests MUTANT=<name>` and
  * `make act4 MUTANT=<name>`, which must exit nonzero.
  */
class MutantSpec extends AnyFlatSpec with ChiselSim {

  /** The mutants that need no particular variant, run on `Forward` only, and those that apply to
    * some ([[PipelineConfig.applies]]: `no-forward-mem` needs a forwarding path, `no-flush` a
    * branch resolved in EX), run on each of those.
    */
  private val cases: Seq[(String, Variant)] =
    Seq("sra", "mtip-bit5").map(_ -> Variant.Forward) ++
      (for {
        mutant  <- Seq("no-forward-mem", "no-flush")
        variant <- Variant.all if PipelineConfig.applies(mutant, variant)
      } yield mutant -> variant)

  for ((mutant, variant) <- cases) {
    s"The $variant pipeline with mutant $mutant" should "fail a riscv-test" in {
      simulate(MutantEmit.top(Map("variant" -> variant.name, "mutant" -> mutant))) { c =>
        val tests  = RiscvTests.all.iterator
        var failed = Option.empty[String]
        while (failed.isEmpty && tests.hasNext) {
          val (name, image) = tests.next()
          failed = RiscvTests.run(c, image).map(why => s"$name: ${why.linesIterator.next()}")
        }
        assert(failed.isDefined, s"mutant $mutant passed every riscv-test on $variant")
        info(s"first failure: ${failed.get}")
      }
    }
  }
}
