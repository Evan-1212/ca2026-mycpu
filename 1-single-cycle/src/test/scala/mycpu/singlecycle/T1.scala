// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import mycpu.common._

/** What the T1 specs share: the golden model of the T1 system, program images and ELF files. */
object T1 {

  /** The golden model of [[Top]]: RV32I_Zifencei without traps, the 4 MiB RAM, and the UART and
    * SimCtrl windows (device loads return what the hardware read).
    */
  def model: IssSample => Iss = CoSim2.model(IssConfig.T1, MemMap.ramBytes, Iss.simDevices(clint = false))

  /** `source` assembled at the reset vector (`AsmX`: RV32I, and the extensions of the later tasks). */
  def asm(source: String): MemImage = MemImage(AsmX(source), MemMap.ramBase)

  /** A program that gives every register a known value (0), as the golden model starts with: the
    * register file has no reset, and a C program saves registers it has not written yet.
    */
  val clearRegisters: MemImage = asm((1 to 31).map(r => s"li x$r, 0").mkString("", "\n", "\necall"))

  /** The executable of C program `name`, built for `rv32i` (see `TestTools.elf`). */
  def elf(name: String): ElfFile = TestTools.elf(name, "rv32i", "make -C 1-single-cycle test")

  /** Gives every register the value 0 on the system `c`, by running [[clearRegisters]]. */
  def clear(c: CoSimPorts): Unit = {
    CoSim2.load(c, clearRegisters)
    val last = CoSim2.run(c).trace.lastOption
    require(last.exists(_.halt.contains(riscv.Halt.Ecall)), s"clearRegisters did not end in ecall: $last")
  }

  /** The value register `reg` holds after `trace` (0 if the trace never writes it). */
  def regAfter(trace: Seq[IssRetire], reg: String): Long =
    trace.reverseIterator.find(_.rd == riscv.Reg(reg)).map(_.rdValue).getOrElse(0L)
}
