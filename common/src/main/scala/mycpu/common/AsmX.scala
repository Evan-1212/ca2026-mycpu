// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import riscv.{Asm, Inst, Program, Reg, Rv32i}

/** The CSR addresses this repository knows by name (T3-01), for `AsmX` and the disassembler. */
object Csr {
  val Mvendorid     = 0xf11
  val Marchid       = 0xf12
  val Mimpid        = 0xf13
  val Mhartid       = 0xf14
  val Mconfigptr    = 0xf15
  val Mstatus       = 0x300
  val Misa          = 0x301
  val Mie           = 0x304
  val Mtvec         = 0x305
  val Mstatush      = 0x310
  val Mcountinhibit = 0x320
  val Mscratch      = 0x340
  val Mepc          = 0x341
  val Mcause        = 0x342
  val Mtval         = 0x343
  val Mip           = 0x344
  val Mcycle        = 0xb00
  val Minstret      = 0xb02
  val Mcycleh       = 0xb80
  val Minstreth     = 0xb82
  val Cycle         = 0xc00
  val Time          = 0xc01
  val Instret       = 0xc02
  val Cycleh        = 0xc80
  val Timeh         = 0xc81
  val Instreth      = 0xc82

  /** `mhpmcounter<n>` (3 to 31), its high half, and `mhpmevent<n>`. */
  def mhpmcounter(n: Int): Int  = 0xb00 + n
  def mhpmcounterh(n: Int): Int = 0xb80 + n
  def mhpmevent(n: Int): Int    = 0x320 + n

  /** name -> address, for every CSR above and the hpm counters and events. */
  val byName: Map[String, Int] = Map(
    "mvendorid"     -> Mvendorid,
    "marchid"       -> Marchid,
    "mimpid"        -> Mimpid,
    "mhartid"       -> Mhartid,
    "mconfigptr"    -> Mconfigptr,
    "mstatus"       -> Mstatus,
    "misa"          -> Misa,
    "mie"           -> Mie,
    "mtvec"         -> Mtvec,
    "mstatush"      -> Mstatush,
    "mcountinhibit" -> Mcountinhibit,
    "mscratch"      -> Mscratch,
    "mepc"          -> Mepc,
    "mcause"        -> Mcause,
    "mtval"         -> Mtval,
    "mip"           -> Mip,
    "mcycle"        -> Mcycle,
    "minstret"      -> Minstret,
    "mcycleh"       -> Mcycleh,
    "minstreth"     -> Minstreth,
    "cycle"         -> Cycle,
    "time"          -> Time,
    "instret"       -> Instret,
    "cycleh"        -> Cycleh,
    "timeh"         -> Timeh,
    "instreth"      -> Instreth
  ) ++ (3 to 31).flatMap { n =>
    Seq(
      s"mhpmcounter$n"    -> mhpmcounter(n),
      s"mhpmcounter${n}h" -> mhpmcounterh(n),
      s"mhpmevent$n"      -> mhpmevent(n),
      s"hpmcounter$n"     -> (0xc00 + n),
      s"hpmcounter${n}h"  -> (0xc80 + n)
    )
  }

  /** address -> name. */
  val names: Map[Int, String] = byName.map(_.swap)

  /** The name of CSR `addr`, or its address in hex. */
  def name(addr: Int): String = names.getOrElse(addr, f"0x$addr%03x")
}

/** The instructions this repository adds to the bootcamp's RV32I assembler (`riscv.Asm`, which
  * cannot be extended): the M extension, Zicsr, `mret`, `wfi` and `fence.i`. `AsmX` rewrites each
  * of them into a `.word` and passes the rest of the source to `riscv.Asm` unchanged, so labels,
  * directives and RV32I pseudo-instructions work as there. It also reads the Zicsr
  * pseudo-instructions `csrr`, `csrw`, `csrs`, `csrc`, `csrwi`, `csrsi`, `csrci` and
  * `rdcycle[h]`, `rdtime[h]`, `rdinstret[h]`. A CSR operand is a name ([[Csr.byName]]) or a number;
  * a `uimm` operand is a number from 0 to 31; M operands are registers. A label in front of a
  * rewritten instruction is kept. Rewritten instructions are data to `riscv.Asm`, so `Asm.padded`
  * would not pad after them, and `AsmX` offers no padded form.
  */
object AsmX {
  private val mOps = Seq("mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu")

  /** The CSR instructions and their funct3. */
  private val csrOps = Map("csrrw" -> 1, "csrrs" -> 2, "csrrc" -> 3, "csrrwi" -> 5, "csrrsi" -> 6, "csrrci" -> 7)

  /** The fixed words: `mret`, `wfi` and `fence.i` (with zero operands). */
  val fixed: Map[String, Long] = Map("mret" -> 0x30200073L, "wfi" -> 0x10500073L, "fence.i" -> 0x0000100fL)

  /** Whether `name` is an M instruction. */
  def isM(name: String): Boolean = mOps.contains(name)

  /** Whether `name` is a CSR instruction (`csrrw` ... `csrrci`). */
  def isCsr(name: String): Boolean = csrOps.contains(name)

  /** The word of M instruction `name` (`mul` ... `remu`). */
  def encodeM(name: String, rd: Int, rs1: Int, rs2: Int): Long = {
    val f3 = mOps.indexOf(name)
    require(f3 >= 0, s"not an M instruction: $name")
    for (r <- Seq(rd, rs1, rs2)) require(r >= 0 && r < 32, s"$name: there is no register x$r")
    (1L << 25) | (rs2.toLong << 20) | (rs1.toLong << 15) | (f3.toLong << 12) | (rd.toLong << 7) | 0x33
  }

  /** The word of CSR instruction `name`; `src` is rs1, or the `uimm` of the immediate forms. */
  def encodeCsr(name: String, rd: Int, csr: Int, src: Int): Long = {
    val f3 = csrOps.getOrElse(name, throw new IllegalArgumentException(s"not a CSR instruction: $name"))
    require(csr >= 0 && csr < 4096, s"$name: CSR address $csr out of range")
    require(rd >= 0 && rd < 32 && src >= 0 && src < 32, s"$name: operand out of range")
    (csr.toLong << 20) | (src.toLong << 15) | (f3.toLong << 12) | (rd.toLong << 7) | 0x73
  }

  private def bits(w: Long, hi: Int, lo: Int): Int = ((w >>> lo) & ((1L << (hi - lo + 1)) - 1)).toInt

  /** The instruction of `word` among those `AsmX` adds, or None. An M instruction has its
    * registers in `rd`, `rs1` and `rs2`; a CSR instruction has the CSR address in `imm` and rs1 (or
    * `uimm`) in `rs1`; `mret`, `wfi` and `fence.i` have no operands (`fence.i` ignores its fields,
    * as `fence` does).
    */
  def decodeExt(word: Long): Option[Inst] = {
    val w  = word & 0xffffffffL
    val f3 = bits(w, 14, 12)
    val rd = bits(w, 11, 7)
    bits(w, 6, 0) match {
      case 0x33 if bits(w, 31, 25) == 1 => Some(Inst(mOps(f3), rd, bits(w, 19, 15), bits(w, 24, 20)))
      case 0x73 if f3 != 0 && f3 != 4   =>
        Some(Inst(csrOps.find(_._2 == f3).get._1, rd, bits(w, 19, 15), imm = bits(w, 31, 20)))
      case 0x73 if w == fixed("mret") => Some(Inst("mret"))
      case 0x73 if w == fixed("wfi")  => Some(Inst("wfi"))
      case 0x0f if f3 == 1            => Some(Inst("fence.i"))
      case _                          => None
    }
  }

  /** `word` decoded as RV32I or as one of the instructions `AsmX` adds; None if it is neither. */
  def decode(word: Long): Option[Inst] = decodeExt(word).orElse(Rv32i.decode(word))

  /** `word` as assembly, as `Rv32i.disassemble` shows it, with the added instructions too. */
  def disassemble(word: Long, pc: Long = 0): String = decodeExt(word) match {
    case None                   => Rv32i.disassemble(word, pc)
    case Some(i) if isM(i.name) => s"${i.name} ${Reg.names(i.rd)}, ${Reg.names(i.rs1)}, ${Reg.names(i.rs2)}"
    case Some(i) if i.name.endsWith("i") && isCsr(i.name) =>
      s"${i.name} ${Reg.names(i.rd)}, ${Csr.name(i.imm)}, ${i.rs1}"
    case Some(i) if isCsr(i.name) => s"${i.name} ${Reg.names(i.rd)}, ${Csr.name(i.imm)}, ${Reg.names(i.rs1)}"
    case Some(i)                  => i.name
  }

  /** The memory image of `source` assembled for `base` (see `riscv.Asm`). */
  def apply(source: String, base: Long = MemMap.ramBase): Seq[Long] = assemble(source, base).words

  /** `source` assembled for `base`, with the added instructions rewritten first. */
  def assemble(source: String, base: Long = MemMap.ramBase): Program = Asm.assemble(rewrite(source), base)

  /** `source` with every added instruction replaced by a `.word` of its encoding. */
  def rewrite(source: String): String =
    source.linesIterator.zipWithIndex
      .map { case (line, n) =>
        try rewriteLine(line)
        catch {
          case e: IllegalArgumentException =>
            throw new IllegalArgumentException(s"line ${n + 1}: ${e.getMessage}: $line", e)
        }
      }
      .mkString("", "\n", "\n")

  private val labelDef = """\s*([A-Za-z_.$][\w.$]*|\d+)\s*:(.*)""".r

  /** The statements of a line and its comment, split as `riscv.Asm` splits them: `;` separates,
    * `#` starts a comment, both outside strings and character literals.
    */
  private def split(line: String): (Seq[String], String) = {
    val out         = scala.collection.mutable.ListBuffer[String]()
    var quote: Char = 0
    var start       = 0
    var i           = 0
    while (i < line.length && !(quote == 0 && line(i) == '#')) {
      val c = line(i)
      if (quote != 0) { if (c == '\\') i += 1 else if (c == quote) quote = 0 }
      else if (c == '"' || c == '\'') quote = c
      else if (c == ';') { out += line.substring(start, i); start = i + 1 }
      i += 1
    }
    out += line.substring(start, i.min(line.length))
    (out.toSeq, line.substring(i.min(line.length)))
  }

  private def rewriteLine(line: String): String = {
    val (stmts, comment) = split(line)
    val rewritten        = stmts.map(rewriteStatement)
    if (rewritten == stmts) line else rewritten.mkString(";") + comment
  }

  private def rewriteStatement(stmt: String): String = stmt match {
    case labelDef(label, rest) =>
      val r = rewriteStatement(rest)
      if (r == rest) stmt else s"$label: $r"
    case _ =>
      val text = stmt.trim
      val op   = text.takeWhile(c => !c.isWhitespace).toLowerCase
      val args = text.drop(op.length).split(",").map(_.trim).filter(_.nonEmpty).toList
      word(op, args).fold(stmt)(w => f"  .word 0x$w%08x")
  }

  /** The value of a decimal, `0x` or `0b` literal, as a `Long`: operands are range-checked before
    * they are narrowed, so an oversized one is an error and not another value.
    */
  private def number(s: String): Long = {
    val t   = s.trim.toLowerCase
    val neg = t.startsWith("-")
    val u   = t.stripPrefix("-")
    val v   =
      try {
        // parseLong takes a sign of its own, which may only stand before the whole literal
        val prefixed = u.startsWith("0x") || u.startsWith("0b")
        val digits   = if (prefixed) u.drop(2) else u
        if (digits.startsWith("-") || digits.startsWith("+")) throw new NumberFormatException
        if (prefixed) java.lang.Long.parseLong(digits, if (u(1) == 'x') 16 else 2)
        else java.lang.Long.parseLong(digits)
      } catch { case _: NumberFormatException => throw new IllegalArgumentException(s"not a number: $s") }
    if (neg) -v else v
  }

  /** The number `s`, which must be from 0 to `max`. */
  private def ranged(s: String, max: Int, what: String): Int = {
    val v = number(s)
    require(v >= 0 && v <= max, s"$what $s out of range (0 to $max)")
    v.toInt
  }

  private def csr(s: String): Int  = Csr.byName.getOrElse(s.toLowerCase, ranged(s, 0xfff, "CSR address"))
  private def uimm(s: String): Int = ranged(s, 31, "uimm")

  /** The word of CSR instruction `op` (`csrrw`, `csrrs` or `csrrc`), in its immediate form if
    * `imm` or if `src` is not a register.
    */
  private def csrOp(op: String, rd: Int, c: String, src: String, imm: Boolean): Long =
    if (Reg.isReg(src) && !imm) encodeCsr(op, rd, csr(c), Reg(src))
    else {
      require(imm || src.headOption.exists(ch => ch.isDigit || ch == '-'), s"$src is neither a register nor a number")
      encodeCsr(op + "i", rd, csr(c), uimm(src))
    }

  /** The word of statement `op args` if it is an added instruction or pseudo-instruction. */
  private def word(op: String, args: List[String]): Option[Long] = {
    def r(s: String) = Reg(s)
    def need(n: Int) = require(args.size == n, s"$op takes $n operands")
    val counters     = Map("rdcycle" -> Csr.Cycle, "rdtime" -> Csr.Time, "rdinstret" -> Csr.Instret)
    op match {
      case _ if isM(op) => need(3); Some(encodeM(op, r(args(0)), r(args(1)), r(args(2))))
      // as GNU as does, a register form with a number as its source is the immediate form
      case _ if isCsr(op) => need(3); Some(csrOp(op.stripSuffix("i"), r(args(0)), args(1), args(2), op.endsWith("i")))
      case _ if fixed.contains(op) => need(0); fixed.get(op)
      case "csrr"                  => need(2); Some(encodeCsr("csrrs", r(args(0)), csr(args(1)), 0))
      case "csrw" | "csrs" | "csrc" | "csrwi" | "csrsi" | "csrci" =>
        need(2); Some(csrOp("csrr" + op(3), 0, args(0), args(1), op.endsWith("i")))
      case _ if counters.contains(op.stripSuffix("h")) =>
        need(1)
        val base = counters(op.stripSuffix("h"))
        Some(encodeCsr("csrrs", r(args(0)), if (op.endsWith("h")) base + 0x80 else base, 0))
      case _ => None
    }
  }
}
