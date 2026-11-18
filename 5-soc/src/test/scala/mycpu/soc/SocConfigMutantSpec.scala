// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import mycpu.pipeline.{PipelineConfig, Variant}
import org.scalatest.flatspec.AnyFlatSpec

/** `SocConfig.withMutant` selects one bug: the one it is given, and no other. */
class SocConfigMutantSpec extends AnyFlatSpec {
  private val base = SocConfig(Seq(0L), PipelineConfig(Variant.Forward))

  private def selected(c: SocConfig) = (c.mutant, c.core.mutant)

  "SocConfig.withMutant" should "select a mutant of a SoC component and none of the core" in {
    assert(selected(base.withMutant(Some("vga-no-id"))) == (Some("vga-no-id"), None))
  }

  it should "select a mutant of the UART and none of the core" in {
    assert(selected(base.withMutant(Some("uart-no-hold"))) == (Some("uart-no-hold"), None))
  }

  it should "select a mutant of the core and none of the SoC" in {
    assert(selected(base.withMutant(Some("sra"))) == (None, Some("sra")))
  }

  it should "replace a mutant of the core by one of a component, and the other way round" in {
    assert(selected(base.withMutant(Some("sra")).withMutant(Some("no-decerr"))) == (Some("no-decerr"), None))
    assert(selected(base.withMutant(Some("no-decerr")).withMutant(Some("sra"))) == (None, Some("sra")))
  }

  it should "clear both with None" in {
    assert(selected(base.withMutant(Some("sra")).withMutant(None)) == (None, None))
    assert(selected(base.withMutant(Some("vga-no-id")).withMutant(None)) == (None, None))
  }
}
