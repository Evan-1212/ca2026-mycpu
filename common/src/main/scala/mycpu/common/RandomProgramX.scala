// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import riscv.RandomProgram

import scala.util.Random

/** Constrained random programs for co-simulation (VER-05): the bootcamp's RV32I programs
  * (`riscv.RandomProgram`, the same program for the same seed and length) with M and CSR
  * instructions added between their slots, assembled with [[AsmX]]. Added instructions never write
  * `s10` or `s11`, so the bootcamp's construction still holds: branches go forward, loads and
  * stores stay in the data area, and the program ends.
  *
  *   - `m`: M instructions, some with an operand set just before to a corner value (0, 1, -1,
  *     `0x8000_0000`, `0x7fff_ffff`), so division by zero and `MIN / -1` occur.
  *   - `csr`: CSR instructions of every form. They write only CSRs whose writes change no control
  *     flow (`mscratch`, `mepc`, `mcause`, `mtval`, `mie`, `mcountinhibit`, `mcycle`, `minstret`,
  *     `mhpmcounter3`, `mhpmevent3`, `misa`, `mip`); others, read-only ones included, are only
  *     read (with rs1 = x0 or uimm = 0, which does not write). `mstatus.MIE` stays 0, so no
  *     interrupt is taken. The program sets `mtvec` first, and its final `ecall` traps to a
  *     handler that writes `mcause - 11` (0 for that `ecall`) to SimCtrl `EXIT`: run it on a model
  *     with SimCtrl ([[Iss.simDevices]]).
  *
  * Without `m` and `csr` the program is the bootcamp's, unchanged, ending with `ecall`.
  */
object RandomProgramX {
  private val writable = (0 to 25) ++ (28 to 31) // as RandomProgram: not s10, not s11
  private val corners  = Seq("0", "1", "-1", "0x80000000", "0x7fffffff")
  private val mOps     = Seq("mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu")
  private val written  = Seq(
    "mscratch",
    "mepc",
    "mcause",
    "mtval",
    "mie",
    "mcountinhibit",
    "mcycle",
    "minstret",
    "mhpmcounter3",
    "mhpmevent3",
    "misa",
    "mip"
  )
  private val readOnly = Seq(
    "mstatus",
    "mtvec",
    "mhartid",
    "mvendorid",
    "cycle",
    "instret",
    "time",
    "hpmcounter3",
    "mcycleh",
    "minstreth",
    "mhpmcounter11",
    "mstatush"
  )
  private val slot = """L(\d+):.*""".r

  /** The program for `seed`, with `length` slots of the bootcamp's generator. */
  def apply(seed: Long, m: Boolean = false, csr: Boolean = false, length: Int = 200): String = {
    val program = RandomProgram(seed, length)
    if (!m && !csr) return program
    val rnd                    = new Random(seed ^ 0x5eed5eedL)
    def pick[T](xs: Seq[T]): T = xs(rnd.nextInt(xs.size))
    def rd                     = s"x${pick(writable)}"
    def rs                     = s"x${rnd.nextInt(32)}"

    def mInstruction: Seq[String] =
      if (rnd.nextInt(3) == 0) { // a corner operand, in a register of its own
        val r      = s"x${pick(writable.filter(_ != 0))}"
        val (a, b) = if (rnd.nextBoolean()) (r, rs) else (rs, r)
        Seq(s"li    $r, ${pick(corners)}", s"${pick(mOps)} $rd, $a, $b")
      } else Seq(s"${pick(mOps)} $rd, $rs, $rs")

    def csrInstruction: String =
      if (rnd.nextInt(3) == 0) { // a read only
        val c = pick(readOnly ++ written)
        rnd.nextInt(4) match {
          case 0 => s"csrrs  $rd, $c, x0"
          case 1 => s"csrrc  $rd, $c, x0"
          case 2 => s"csrrsi $rd, $c, 0"
          case _ => s"csrrci $rd, $c, 0"
        }
      } else {
        val c  = pick(written)
        val op = pick(Seq("csrrw", "csrrs", "csrrc", "csrrwi", "csrrsi", "csrrci"))
        if (op.endsWith("i")) s"$op $rd, $c, ${rnd.nextInt(32)}" else s"$op $rd, $c, $rs"
      }

    // added instructions go just before a slot's label, so they belong to the slot before it and a
    // branch to the label skips them
    val body = program.linesIterator.toSeq.flatMap {
      case line @ slot(n) if n.toInt < length =>
        val added = (if (m && rnd.nextInt(4) == 0) mInstruction else Nil) ++
          (if (csr && rnd.nextInt(4) == 0) Seq(csrInstruction) else Nil)
        added.map("    " + _) :+ line
      case line if csr && line.startsWith(s"L$length:") =>
        Seq(
          line,
          "rpx_trap:",
          "    csrr  t0, mcause",
          "    addi  t0, t0, -11",
          f"    li    t1, 0x${MemMap.simCtrlBase + MemMap.SimCtrl.Exit}%x",
          "    sw    t0, 0(t1)",
          "1:  j     1b"
        )
      case line if csr && line.startsWith("# RandomProgram") =>
        Seq(
          s"# RandomProgramX(seed = $seed, m = $m, csr = $csr), from $line",
          "    la    t0, rpx_trap",
          "    csrw  mtvec, t0"
        )
      case line if line.startsWith("# RandomProgram") =>
        Seq(s"# RandomProgramX(seed = $seed, m = $m, csr = $csr), from $line")
      case line => Seq(line)
    }
    body.mkString("", "\n", "\n")
  }
}
