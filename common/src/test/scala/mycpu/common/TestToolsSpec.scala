// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import org.scalatest.flatspec.AnyFlatSpec

/** `TestTools.builtFor`, the guard of `TestTools.elf` against programs built for another student
  * configuration (EX-08): every macro of the build's flags counts, and a file with D12's maps is
  * the same build as no file. The flags are those `scripts/student.py cflags` gives.
  */
class TestToolsSpec extends AnyFlatSpec {
  private val d12 =
    TestTools.configMacros(StudentConfig.simCtrlDefault, StudentConfig.uartDefault, StudentConfig.hpmDefault)

  /** The `-D` flags of the configuration of `id`, from scripts/student.py. */
  private def cflags(id: String): String = {
    val json = TestTools.scratch("test-tools-spec").resolve(s"$id.json").toString
    val py   = TestTools.root.resolve("scripts/student.py").toString
    TestTools.run(Seq("python3", py, "write", id, "-o", json), "student.py write")
    TestTools.run(Seq("python3", py, "cflags", json), "student.py cflags").trim
  }

  /** A software/Makefile flags file of the runtime with those flags. */
  private def flagsFile(cflags: String) =
    s"riscv-none-elf-gcc -march=rv32im_zicsr -O2 -DMYCPU $cflags | libc: -fno-tree-loop-distribute-patterns | " +
      "platform: -ffunction-sections  | link: -march=rv32im_zicsr\n"

  "builtFor" should "take D12's values for a build without flags and for one of profile 0" in {
    assert(TestTools.builtFor(flagsFile("")) == d12)
    assert(TestTools.builtFor(flagsFile(cflags("ZZZ-9_X"))) == d12) // profile 0
  }

  it should "read every register and event of another profile" in {
    val p7 = TestTools.builtFor(flagsFile(cflags("F74121234"))) // profile 7: SimCtrl reversed, UART swapped
    assert(p7("SIMCTRL_EXIT") == 0x1c && p7("SIMCTRL_MEIP") == 0x00)
    assert(p7("UART_STATUS") == 0x4 && p7("UART_RXDATA") == 0x8)
    assert(p7.keySet == d12.keySet)
    val p2 = TestTools.builtFor(flagsFile(cflags("S0000000"))) // profile 2: UART swapped, events permuted
    assert(p2("SIMCTRL_EXIT") == 0x00, "the same EXIT offset as D12")
    assert(p2 != d12, "a build that differs in the UART or the events alone is another configuration")
    assert(p2("HPM_EVENT_DIVIDER_BUSY") == 3)
  }

  it should "count a flag the configuration does not have" in {
    assert(TestTools.builtFor(flagsFile("-DUART_STATUS=0x4")) != d12)
  }
}
