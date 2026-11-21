// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.util.BitPat
import chisel3.util.experimental.decode._
import riscv.{ASel, AluOp, BSel, DecodeRows, ImmFormat, Instructions, Kind, Opcode}

/** Where the value written to `rd` comes from. */
object ResultSource extends ChiselEnum {
  val Alu, Load, Link, Mul, Div = Value
}

/** The control signals of an instruction, as this repository's decode table gives them (T2-01).
  *
  * The Bool fields are false for a word that is no instruction (`legal` is false then), so the
  * datapath may use them without looking at `legal`. The enum fields are meaningful only for
  * the instructions that use them, and may hold any bit pattern otherwise, also one that is no
  * value of the enum (bootcamp 5.3): `alu_op`, `imm_format`, `op_a_sel` and `op_b_sel` as the
  * bootcamp's decode rows say, `rd_source` when `rd_write` is set, `mul_op` when `rd_write` is
  * set and `rd_source` is `Mul`, and `div_op` when `div` is set. A datapath must therefore
  * qualify every use of an enum field with the Bool that makes it meaningful: `rd_source ===
  * ResultSource.Mul` alone may be true for a store or for an illegal word.
  */
class ControlSignals extends Bundle {
  val legal      = Bool()
  val alu_op     = AluOp()
  val imm_format = ImmFormat()
  val op_a_sel   = ASel()
  val op_b_sel   = BSel()
  val load       = Bool()
  val store      = Bool()
  val branch     = Bool()
  val jal        = Bool()
  val jalr       = Bool()
  val ecall      = Bool()
  val ebreak     = Bool()
  val rd_write   = Bool() // the instruction writes register `rd` (which may be x0)
  val rd_source  = ResultSource()
  val div        = Bool() // the instruction needs the divider
  val mul_op     = MulOp()
  val div_op     = DivOp()
}

/** A row of the decode table: what one instruction does, in Scala. `None` means that the
  * instruction does not use the signal, so the table does not care about its value there.
  * `tags` names the flags that a later task adds for this row (see [[Decode]]).
  */
case class ControlRow(
    aluOp: Option[AluOp.Type] = None,
    imm: Option[ImmFormat.Type] = None,
    a: Option[ASel.Type] = None,
    b: Option[BSel.Type] = None,
    load: Boolean = false,
    store: Boolean = false,
    branch: Boolean = false,
    jal: Boolean = false,
    jalr: Boolean = false,
    ecall: Boolean = false,
    ebreak: Boolean = false,
    rdWrite: Boolean = false,
    rdSource: Option[ResultSource.Type] = None,
    mulOp: Option[MulOp.Type] = None,
    divOp: Option[DivOp.Type] = None,
    tags: Set[String] = Set.empty
)

/** An instruction of the decode table: its name, the words it covers and its row. */
case class DecodeEntry(name: String, bitPat: BitPat, row: ControlRow) extends DecodePattern

/** This repository's decode table (T2-01; INF-03, step 6), built with `DecodeTable` as the
  * bootcamp's `riscv.Decoder` is (bootcamp 5.3), but with fields of its own.
  *
  * The RV32I entries are not written again: `riscv.DecodeRows.table`, the student's answer to
  * bootcamp 5.3, is read as data, and each of its rows is translated to a [[ControlRow]]. The
  * bootcamp is not changed. `fence.i` (Zifencei) and the eight M instructions are added here.
  *
  * '''How a later task extends the table without changing a field of this one.''' The table is a
  * list of entries and a list of fields, and both can grow:
  *
  *   - new instructions are new [[DecodeEntry]] values, appended to [[Decode.rv32im]];
  *   - a new control signal is a new field, appended with `extraFields`. For a flag, the entry
  *     carries a name in `ControlRow.tags` and the field is `new Decode.TagField(name)`, which is
  *     true exactly for the entries with that tag and false for every other word.
  *
  * For example, T3 adds the CSR instructions as entries with `rdWrite = true`, no `rdSource`
  * and the tag "csr", and `mret` and `wfi` as entries with tags of their own:
  * {{{
  * val csr   = new Decode.TagField("csr")
  * val table = Decode.table(Decode.rv32im ++ csrEntries, Seq(csr))
  * val out   = Decode.decode(inst, table)
  * val ctrl  = Decode.controls(out) // the fields of this file, unchanged
  * val isCsr = out(csr)             // the new flag; it selects the CSR value for rd
  * }}}
  * The existing entries have no such tag, so their rows, and the fields below, stay as they are.
  */
object Decode {

  /** A field of the table: the instruction pattern type is [[DecodeEntry]]. */
  type Field = DecodeField[DecodeEntry, _ <: Data]

  /** A flag: true for the entries `get` selects, false for the other entries and for every word
    * that is no instruction.
    */
  class FlagField(val name: String, get: ControlRow => Boolean) extends BoolDecodeField[DecodeEntry] {
    def genTable(entry: DecodeEntry): BitPat = if (get(entry.row)) y else n
    override def default: BitPat             = n
  }

  /** The flag of the entries that carry `tag` in `ControlRow.tags`: how a later task adds a signal. */
  class TagField(tag: String) extends FlagField(tag, _.tags.contains(tag))

  /** An enum-valued field; an entry may leave it out (`None`: don't care). */
  class EnumField[T <: EnumType](val name: String, gen: => T, get: ControlRow => Option[T])
      extends DecodeField[DecodeEntry, T] {
    def chiselType: T                        = gen
    def genTable(entry: DecodeEntry): BitPat = get(entry.row).map(BitPat(_)).getOrElse(dc)
  }

  object LegalField     extends FlagField("legal", _ => true)
  object AluOpField     extends EnumField[AluOp.Type]("alu_op", AluOp(), _.aluOp)
  object ImmFormatField extends EnumField[ImmFormat.Type]("imm_format", ImmFormat(), _.imm)
  object OpASelField    extends EnumField[ASel.Type]("op_a_sel", ASel(), _.a)
  object OpBSelField    extends EnumField[BSel.Type]("op_b_sel", BSel(), _.b)
  object LoadField      extends FlagField("load", _.load)
  object StoreField     extends FlagField("store", _.store)
  object BranchField    extends FlagField("branch", _.branch)
  object JalField       extends FlagField("jal", _.jal)
  object JalrField      extends FlagField("jalr", _.jalr)
  object EcallField     extends FlagField("ecall", _.ecall)
  object EbreakField    extends FlagField("ebreak", _.ebreak)
  object RdWriteField   extends FlagField("rd_write", _.rdWrite)
  object RdSourceField  extends EnumField[ResultSource.Type]("rd_source", ResultSource(), _.rdSource)
  object DivField       extends FlagField("div", _.divOp.isDefined)
  object MulOpField     extends EnumField[MulOp.Type]("mul_op", MulOp(), _.mulOp)
  object DivOpField     extends EnumField[DivOp.Type]("div_op", DivOp(), _.divOp)

  /** The fields of [[ControlSignals]], in its order. */
  val fields: Seq[Field] = Seq(
    LegalField,
    AluOpField,
    ImmFormatField,
    OpASelField,
    OpBSelField,
    LoadField,
    StoreField,
    BranchField,
    JalField,
    JalrField,
    EcallField,
    EbreakField,
    RdWriteField,
    RdSourceField,
    DivField,
    MulOpField,
    DivOpField
  )

  /** The row of an RV32I instruction, from its row in the bootcamp's table. The ALU fields are
    * taken over as they are; the rest follows from the bootcamp's `Kind`.
    */
  def fromBootcamp(row: riscv.Row): ControlRow = {
    val alu = ControlRow(aluOp = row.aluOp, imm = row.imm, a = row.a, b = row.b)
    def writes(source: ResultSource.Type): ControlRow = alu.copy(rdWrite = true, rdSource = Some(source))
    // [CA26: Exercise 2.1] The rows of the decode table, and the result of the execute stage
    // Contract: for each of the bootcamp's kinds, `alu` with the flags of that kind set: what
    // the instruction is (`load`, `store`, `branch`, `jal`, `jalr`, `ecall`, `ebreak`), whether
    // it writes `rd`, and from where (`writes`). A kind that only moves the pc on sets nothing
    // (bootcamp 5.1 for the instruction formats, 5.3 for `Kind`; handout §T2.1).
    val rows: Seq[(Kind.Type, ControlRow)] = ?
    // collectFirst would hide a second row of a kind behind the first one
    require(rows.map(_._1.litValue).distinct.size == rows.size, "a kind has two control rows")
    rows
      .collectFirst { case (kind, controls) if kind.litValue == row.kind.litValue => controls }
      .getOrElse(throw new IllegalArgumentException(s"no control row for kind ${row.kind}"))
  }

  /** The 40 RV32I instructions, with the rows of the student's bootcamp table. */
  def rv32i: Seq[DecodeEntry] = {
    val rows = DecodeRows.table
    Instructions.all.map { case (name, bitPat) =>
      val row = rows.getOrElse(name, throw new IllegalArgumentException(s"no row for $name in riscv.DecodeRows"))
      DecodeEntry(name, bitPat, fromBootcamp(row))
    }
  }

  /** `fence.i` (Zifencei): legal, and nothing but the pc changes; which words are `fence.i` is the
    * rule of exercise 1.2, and rv32emu, the reference of the co-simulation, decodes them by it.
    */
  def zifencei: Seq[DecodeEntry] =
    Seq(DecodeEntry("FENCE.I", any(17) ## BitPat("b001") ## any(5) ## BitPat(Opcode.MiscMem), ControlRow()))

  private def any(width: Int): BitPat = BitPat.dontCare(width)

  /** The names of the M instructions, in the order of funct3. */
  val mNames: Seq[String] = Seq("MUL", "MULH", "MULHSU", "MULHU", "DIV", "DIVU", "REM", "REMU")

  // [CA26: Exercise 2.1] The rows of the decode table, and the result of the execute stage
  // Contract: `mPattern` covers exactly the words of the M instruction with that funct3, for
  // any registers. A word that differs in a fixed bit of the encoding must not match, or an
  // illegal word becomes legal (Unprivileged spec, "M" extension and the instruction listings;
  // bootcamp 5.3 for `BitPat`; handout §T2.1).
  private def mPattern(funct3: Int): BitPat = ?

  // [CA26: Exercise 2.1] The rows of the decode table, and the result of the execute stage
  // Contract: the rows of a multiplication and of a division. Both write `rd`; the operands
  // are the two registers, which reach the multiplier and the divider directly, not through
  // the ALU (bootcamp 5.3 for don't-care fields; handout §T2.1).
  private def mulRow(op: MulOp.Type): ControlRow = ?
  private def divRow(op: DivOp.Type): ControlRow = ?

  /** The eight M instructions. */
  def m: Seq[DecodeEntry] = mNames.zipWithIndex.map { case (name, funct3) =>
    val row = if (funct3 < 4) mulRow(MulOp.all(funct3)) else divRow(DivOp.all(funct3 - 4))
    DecodeEntry(name, mPattern(funct3), row)
  }

  /** The instructions of T2: RV32I, Zifencei and M. */
  def rv32im: Seq[DecodeEntry] = rv32i ++ zifencei ++ m

  /** The decode table of `entries` with the fields of [[ControlSignals]] and then `extraFields`.
    * No two entries may cover the same word: the table has no order of priority, so a word with
    * two rows would have two sets of outputs. A later task that adds instructions gives each a
    * pattern of its own (the encodings of RV32 are disjoint), and none refines a broader one.
    */
  def table(entries: Seq[DecodeEntry] = rv32im, extraFields: Seq[Field] = Nil): DecodeTable[DecodeEntry] = {
    for (Seq(x, y) <- entries.combinations(2))
      require(!x.bitPat.overlap(y.bitPat), s"${x.name} and ${y.name} cover the same word")
    val names = (fields ++ extraFields).map(_.name)
    require(names.distinct.size == names.size, s"a field name is used twice: ${names.mkString(", ")}")
    new DecodeTable(entries, fields ++ extraFields)
  }

  /** The decoder of `table` for `inst`, as gates: one output per field, read with `out(field)`. */
  def decode(inst: UInt, table: DecodeTable[DecodeEntry]): DecodeBundle =
    // decoder.qmc: Chisel's own minimizer. The enum cast warning is suppressed on purpose: an enum
    // field may hold bits that are no value of its enum wherever the table does not care.
    suppressEnumCastWarning(decoder.qmc(inst, table.table).asTypeOf(table.bundle))

  /** The fields of [[ControlSignals]] out of a decoder's output. */
  def controls(out: DecodeBundle): ControlSignals = {
    val ctrl = Wire(new ControlSignals)
    ctrl.legal      := out(LegalField)
    ctrl.alu_op     := out(AluOpField)
    ctrl.imm_format := out(ImmFormatField)
    ctrl.op_a_sel   := out(OpASelField)
    ctrl.op_b_sel   := out(OpBSelField)
    ctrl.load       := out(LoadField)
    ctrl.store      := out(StoreField)
    ctrl.branch     := out(BranchField)
    ctrl.jal        := out(JalField)
    ctrl.jalr       := out(JalrField)
    ctrl.ecall      := out(EcallField)
    ctrl.ebreak     := out(EbreakField)
    ctrl.rd_write   := out(RdWriteField)
    ctrl.rd_source  := out(RdSourceField)
    ctrl.div        := out(DivField)
    ctrl.mul_op     := out(MulOpField)
    ctrl.div_op     := out(DivOpField)
    ctrl
  }
}

/** The decoder as a module: an instruction in, its control signals out (combinational).
  *
  * @param entries
  *   the instructions it knows: those of T2, unless a later task or a spec passes its own
  */
class Decode(entries: Seq[DecodeEntry] = Decode.rv32im) extends RawModule {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val ctrl = Output(new ControlSignals)
  })
  io.ctrl := Decode.controls(Decode.decode(io.inst, Decode.table(entries)))
}
