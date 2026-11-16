// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.pipeline

import chisel3.simulator.scalatest.ChiselSim
import mycpu.csrtrap.RiscvTests
import org.scalatest.flatspec.AnyFlatSpec

/** VER-04 for T4: each mutant, on each variant it applies to, fails a riscv-test or a hazard
  * sequence against the golden model, so the checks of this task cannot pass vacuously. The
  * `make` targets are checked the same way by `make test-riscv-tests MUTANT=<name>` and
  * `make act4 MUTANT=<name>`, which must exit nonzero.
  */
class MutantSpec extends AnyFlatSpec with ChiselSim {

  /** The mutants and the variants each applies to (`no-forward-mem` needs a forwarding path, and
    * `no-flush` a branch resolved in EX).
    */
  private val cases: Seq[(String, Variant)] = Seq(
    "sra"            -> Variant.Forward,
    "no-forward-mem" -> Variant.Forward,
    "no-forward-mem" -> Variant.EarlyBranch,
    "no-flush"       -> Variant.Stall,
    "no-flush"       -> Variant.Forward,
    "mtip-bit5"      -> Variant.Forward
  )

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
