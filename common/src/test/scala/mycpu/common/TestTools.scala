// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters._
import scala.sys.process._

/** Paths and external tools of the specs. A missing tool fails the spec with a message that says
  * how to get it; nothing is skipped silently (INF-11).
  */
object TestTools {

  /** The repository root and the bootcamp clone, as build.sbt passes them. */
  val root: Path     = Paths.get(sys.props.getOrElse("mycpu.root", "..")).toAbsolutePath.normalize
  val bootcamp: Path = Paths.get(sys.props.getOrElse("mycpu.bootcamp", s"$root/externals/chisel-bootcamp"))

  /** The `riscv-none-elf-` tool prefix: `$CROSS`, else `$RISCV/bin/riscv-none-elf-`, else the
    * default install of D4 (`~/riscv/toolchain`).
    */
  val cross: String = sys.env.getOrElse(
    "CROSS",
    s"${sys.env.getOrElse("RISCV", s"${sys.props("user.home")}/riscv/toolchain")}/bin/riscv-none-elf-"
  )

  /** rv32emu v1.3 (D13): `$RV32EMU`, else the clone `make rv32emu` builds. */
  val rv32emu: Path = Paths.get(sys.env.getOrElse("RV32EMU", s"$root/externals/rv32emu/build/rv32emu"))

  /** Where the task Makefiles build (their `BUILD`): the repository's `build`, unless
    * `-Dmycpu.build=<dir>` names another directory (a relative one is relative to the
    * repository's root, as `BOOTCAMP_DIR` is: the working directory of a spec is its project's).
    */
  val build: Path = root.resolve(sys.props.getOrElse("mycpu.build", "build"))

  /** The executable of C program `name` as `make -C software all PLATFORM=mycpu ARCH=<arch>`
    * builds it. A missing file fails the spec and names the command that builds it (`target` is
    * the task's `make` target that does); nothing is skipped (INF-11).
    */
  def elf(name: String, arch: String, target: String): ElfFile = {
    val path = build.resolve(s"sw/mycpu/$arch/$name.elf")
    if (!Files.isRegularFile(path))
      throw new IllegalStateException(
        s"$path not found: build the programs first with `make -C software all PLATFORM=mycpu ARCH=$arch` " +
          s"(`$target` does it)"
      )
    // EX-08: the programs use the register maps and event IDs they were built for, which must be
    // those of this run (StudentConfig)
    val flags = build.resolve(s"sw/mycpu/$arch/obj/runtime/flags")
    if (Files.isRegularFile(flags)) {
      val text = new String(Files.readAllBytes(flags), java.nio.charset.StandardCharsets.UTF_8)
      val want = configMacros(StudentConfig.simCtrl, StudentConfig.uart, StudentConfig.hpmEvents)
      val got  = builtFor(text)
      if (got != want) {
        def show(v: Option[Int]) = v.fold("-")(n => s"0x${n.toHexString}")
        val diff                 = (want.keySet ++ got.keySet).toSeq.sorted
          .filter(k => want.get(k) != got.get(k))
          .map(k => s"$k ${show(got.get(k))}, not ${show(want.get(k))}")
        throw new IllegalStateException(
          s"$path was built for another student configuration than ${StudentConfig.describe} " +
            s"(${diff.mkString("; ")}): build the programs again (`$target` does it)"
        )
      }
    }
    Elf.read(path)
  }

  /** The macros of `software/runtime/include/mmio.h` that a configuration sets, by name
    * (`SIMCTRL_EXIT`, `UART_STATUS`, `HPM_EVENT_DIVIDER_BUSY`, ...), as `scripts/student.py
    * cflags` passes them.
    */
  def configMacros(simCtrl: Map[String, Int], uart: Map[String, Int], hpm: Map[String, Int]): Map[String, Int] =
    simCtrl.map { case (k, v) => s"SIMCTRL_$k" -> v } ++ uart.map { case (k, v) => s"UART_$k" -> v } ++
      hpm.map { case (k, v) => s"HPM_EVENT_${k.toUpperCase}" -> v }

  /** The values of those macros in a build whose `flags` file (software/Makefile) is `text`: its
    * `-D` flags of them over the defaults of D12, which `mmio.h` takes for a macro not given. So a
    * build with a file of the D12 maps and one without a file are the same build, and every flag
    * counts, not only SimCtrl's `EXIT`.
    */
  def builtFor(text: String): Map[String, Int] = {
    val flags = """-D((?:SIMCTRL|UART|HPM_EVENT)_[A-Z_]+)=(0x[0-9a-fA-F]+|[0-9]+)""".r
      .findAllMatchIn(text)
      .map(m => m.group(1) -> java.lang.Long.decode(m.group(2)).intValue)
      .toMap
    configMacros(StudentConfig.simCtrlDefault, StudentConfig.uartDefault, StudentConfig.hpmDefault) ++ flags
  }

  /** A scratch directory under the project's `target/`. */
  def scratch(name: String): Path = {
    val dir = Paths.get("target", "spec-scratch", name).toAbsolutePath
    Files.createDirectories(dir)
    dir
  }

  /** The `.S` files of directory `dir`, by name, as (name, source). */
  def sources(dir: Path): Seq[(String, String)] = {
    require(Files.isDirectory(dir), s"$dir not found")
    Files
      .list(dir)
      .iterator
      .asScala
      .toSeq
      .filter(_.toString.endsWith(".S"))
      .sortBy(_.getFileName.toString)
      .map(p => (p.getFileName.toString.stripSuffix(".S"), new String(Files.readAllBytes(p), "UTF-8")))
  }

  /** Runs `cmd`; fails with its output unless it exits 0. */
  def run(cmd: Seq[String], what: String): String = {
    val out  = new StringBuilder
    val code =
      try cmd ! ProcessLogger(l => out ++= l + "\n", l => out ++= l + "\n")
      catch {
        case e: java.io.IOException =>
          throw new IllegalStateException(s"$what: cannot run ${cmd.head}: ${e.getMessage}")
      }
    if (code != 0) throw new IllegalStateException(s"$what failed (exit $code): ${cmd.mkString(" ")}\n$out")
    out.toString
  }

  /** A linker script for the D12 RAM, with two `PT_LOAD` segments (code, then data and BSS). */
  def linkerScript(origin: Long): String =
    f"""OUTPUT_ARCH(riscv)
       |ENTRY(_start)
       |PHDRS { text PT_LOAD; data PT_LOAD; }
       |SECTIONS {
       |  . = 0x$origin%08x;
       |  .text : { *(.text.init) *(.text .text.*) } :text
       |  .rodata : { *(.rodata .rodata.* .srodata .srodata.*) } :text
       |  . = ALIGN(16);
       |  .data : { *(.data .data.* .sdata .sdata.*) } :data
       |  .bss : { *(.bss .bss.* .sbss .sbss.* COMMON) } :data
       |}
       |""".stripMargin

  /** Builds an executable from `files` (name -> contents, `.S` or `.c`) with xPack GCC (D4),
    * linked at `origin`, and returns its path.
    */
  def gcc(name: String, files: Map[String, String], march: String = "rv32i", origin: Long = MemMap.ramBase): Path = {
    val gccPath = Paths.get(cross + "gcc")
    if (!Files.isExecutable(gccPath))
      throw new IllegalStateException(
        s"$gccPath not found: install xPack GCC 15 (D4) or set CROSS / RISCV; see `make check-deps`"
      )
    val dir = scratch(name)
    for ((f, text) <- files) Files.write(dir.resolve(f), text.getBytes("UTF-8"))
    Files.write(dir.resolve("link.ld"), linkerScript(origin).getBytes("UTF-8"))
    val elf = dir.resolve(s"$name.elf")
    run(
      Seq(
        gccPath.toString,
        s"-march=$march",
        "-mabi=ilp32",
        "-O2",
        "-ffreestanding",
        "-nostdlib",
        "-nostartfiles",
        "-static",
        "-Wl,-n",
        "-Wl,--build-id=none",
        "-T",
        dir.resolve("link.ld").toString,
        "-o",
        elf.toString
      ) ++
        files.keys.toSeq.sorted.map(f => dir.resolve(f).toString) :+ "-lgcc",
      s"building $name"
    )
    elf
  }
}
