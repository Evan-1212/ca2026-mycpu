// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import mycpu.common._
import mycpu.csrtrap.{IssCsr, T3}
import mycpu.pipeline.{PipelineConfig, Variant}

/** The VGA (T5-06) as the golden model sees it: `ID` and the palette are modeled (the palette
  * reads back what was stored, bits 23..0), `STATUS`, `CTRL`, `FRAME` and `SWAPS` follow the scan,
  * which the ISA does not determine, so they are sampled from the hardware (`ReadKind.Input`), and
  * everything else, the write-only buffers included, reads 0.
  */
class VgaDeviceModel(base: Long = MemMap.vgaBase) extends ModelDevice(base, MemMap.vgaSize) {
  import MemMap.Vga._
  private val palette = new Array[Long](16)

  private def entry(offset: Int): Option[Int] =
    Option.when(offset >= Palette && offset < Palette + 4 * palette.length)((offset - Palette) / 4)

  protected def readWord(offset: Int): Long = offset match {
    case Id => mycpu.soc.Vga.Id
    case o  => entry(o).map(palette(_)).getOrElse(0L)
  }
  protected def writeWord(offset: Int, data: Long): Unit = entry(offset).foreach(palette(_) = data & 0xffffffL)
  override protected def kindOf(offset: Int): ReadKind   = offset match {
    case Status        => ReadKind.Input(0x7)
    case Ctrl          => ReadKind.Input(0x3)
    case Frame | Swaps => ReadKind.Input(0xffffffffL)
    case _             => ReadKind.Modeled
  }
}

/** What the T5 specs share: the SoC, its golden model and the programs. */
object T5 {

  /** The boot ROM image of the build directory (`make -C software boot`, which `make -C 5-soc
    * test` runs first).
    */
  lazy val bootRom: Seq[Long] = Soc.readBootRom(Soc.defaultBootRom)

  /** The SoC with T4's `variant`, the RAM read synchronously or not, its wait states, its size,
    * and a mutant if given ([[SocConfig.withMutant]]).
    */
  def soc(
      variant: Variant = Variant.Forward,
      syncRead: Boolean = true,
      ramWait: WaitStates = WaitStates.Fixed(0),
      ramBytes: Int = MemMap.ramBytes,
      mutant: Option[String] = None
  ): Soc = new Soc(
    SocConfig(bootRom, PipelineConfig(variant), ramBytes = ramBytes, syncRead = syncRead, ramWait = ramWait)
      .withMutant(mutant)
  )

  /** The golden model of the SoC: T3's ISA and devices (the CLINT with `tickDivisor`), the VGA,
    * and the boot ROM `rom`, where the model starts.
    */
  def model(tickDivisor: Int = 1, rom: Seq[Long] = bootRom, ramBytes: Int = MemMap.ramBytes): IssSample => Iss = {
    val image = IssRom(MemMap.bootRomBase, MemMap.bootRomSize, rom.toIndexedSeq)
    sample =>
      new Iss(
        IssCsr.T3,
        MemMap.ramBase,
        ramBytes,
        IssCsr.devices(tickDivisor) :+ new VgaDeviceModel(),
        sample,
        Some(image)
      )
  }

  /** The golden model of the default SoC. */
  lazy val defaultModel: IssSample => Iss = model()

  /** `source` assembled at `0x8000_0000`, where the boot ROM jumps. */
  def asm(source: String): MemImage = T3.asm(source)

  /** The executable of C program `name`, built for `rv32im_zicsr` unless `arch` says otherwise. */
  def elf(name: String, arch: String = "rv32im_zicsr"): ElfFile =
    TestTools.elf(name, arch, "make -C 5-soc test")

  /** The value of register `reg` after the records of `result`. */
  def reg(result: CoSimResult, reg: String): Long = mycpu.singlecycle.T1.regAfter(result.trace, reg)

  /** Whether `addr` is in the boot ROM's window. */
  def inRom(addr: Long): Boolean = addr >= MemMap.bootRomBase && addr < MemMap.bootRomBase + MemMap.bootRomSize

  /** The records of the boot ROM in `trace`: those before the first one outside the ROM. */
  def bootRecords(trace: Seq[IssRetire]): Seq[IssRetire] = trace.takeWhile(r => inRom(r.pc))
}

/** `mycpu.common.RetireLogCheck` for the SoC: compares `+retire-log`s of a T5 Verilator model,
  * from the boot ROM on, with [[T5.model]] (`make cosim-programs`). `--tick=<n>` gives the CLINT's
  * tick divisor of the model, `--boot=<file>` the boot ROM image (default `Soc.defaultBootRom`),
  * `--ram=<bytes>` the size of its RAM (default `MemMap.ramBytes`).
  */
object RetireLogCheck {

  /** Arguments: `[--tick=<n>] [--boot=<file>] [--ram=<bytes>] [--stalls] (<elf> <retire log>)...`. */
  def main(args: Array[String]): Unit = {
    val names                = Seq("tick", "boot", "ram")
    def option(name: String) = args.filter(_.startsWith(s"--$name=")).lastOption.map(_.stripPrefix(s"--$name="))
    val divisor              = option("tick").map(_.toInt).getOrElse(1)
    val rom  = Soc.readBootRom(option("boot").map(java.nio.file.Paths.get(_)).getOrElse(Soc.defaultBootRom))
    val ram  = option("ram").map(Integer.decode(_).toInt).getOrElse(MemMap.ramBytes)
    val rest = args.filterNot(a => names.exists(n => a.startsWith(s"--$n=")))
    mycpu.common.RetireLogCheck.run(rest, T5.model(divisor, rom, ram), ram)
  }
}
