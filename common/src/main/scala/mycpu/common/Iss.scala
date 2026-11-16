// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import riscv.{Device, Halt, Inst, IsaSim, MemAccess, Reg, Retire, Rv32i}

import scala.collection.mutable

/** A trap: `cause` is the `mcause` value (bit 31 set for an interrupt), `tval` the `mtval` value. */
case class Trap(cause: Long, tval: Long) {

  /** Whether it is an interrupt, and its exception or interrupt code (`mcause` without bit 31). */
  def interrupt: Boolean = (cause >> 31) != 0
  def code: Int          = (cause & 0x7fffffffL).toInt
}

object Trap {
  // exception codes (Privileged spec, Table 3.6)
  val InstMisaligned  = 0L
  val InstAccessFault = 1L
  val Illegal         = 2L
  val Breakpoint      = 3L
  val LoadMisaligned  = 4L
  val LoadAccessFault = 5L
  val StoreMisaligned = 6L
  val StoreAccess     = 7L
  val EcallM          = 11L
  // interrupt codes, without bit 31
  val Msi = 3
  val Mti = 7
  val Mei = 11

  /** The codes of the interrupts that exist, in numeric order (T3-03 ranks them; the golden
    * model's ranking is the task's, [[IssMachine.interrupt]]).
    */
  val interrupts: Seq[Int] = Seq(Msi, Mti, Mei)

  /** The `mip` and `mie` bits of the interrupts that exist: MSIP, MTIP and MEIP. */
  val pendingMask: Long = interrupts.map(1L << _).sum

  /** The `mcause` of interrupt `code`. */
  def interruptCause(code: Int): Long = (1L << 31) | code

  /** The exception codes this hart raises (T3-02), as a mask with bit `code` set. */
  val raisedExceptions: Long = Seq(
    InstMisaligned,
    InstAccessFault,
    Illegal,
    Breakpoint,
    LoadMisaligned,
    LoadAccessFault,
    StoreMisaligned,
    StoreAccess,
    EcallM
  ).map(1L << _).sum

  /** The exception codes and the interrupt codes that a CSR write can store in `mcause`, as masks
    * with bit `code` set. `mcause` is WARL (T3-01): a write of any other value leaves it as it
    * was. The two sets are what Sail 0.16 stores for an M-mode-only RV32IM hart (found by
    * experiment, see `3-csr-trap/README.md`), which is more than this hart raises: the exceptions
    * 0 to 9, 11 to 13, 15 and 19, and the interrupts 1, 3, 5, 7, 9 and 11.
    */
  val legalExceptions: Long = 0x8bbffL
  val legalInterrupts: Long = 0xaaaL

  /** Whether a CSR write can store the 32-bit value `cause` in `mcause`. */
  def legalCause(cause: Long): Boolean = {
    val code = cause & 0x7fffffffL
    code < 32 && ((if ((cause >> 31 & 1) != 0) legalInterrupts else legalExceptions) >> code & 1) != 0
  }
}

/** The counters of Zicntr and Zihpm as this hart has them (T3-04), for the model and the RTL. */
object Counters {

  /** The programmable counters: `mhpmcounter3` to `mhpmcounter10`. */
  val firstHpm     = 3
  val programmable = 8

  /** Whether counter `n` is one of them (the others from 3 to 31 are hardwired 0). */
  def isHpm(n: Int): Boolean = n >= firstHpm && n < firstHpm + programmable

  /** The width of `mhpmevent3..10`: an event ID of 4 bits. */
  val eventBits = 4

  /** The bits of `mcountinhibit` that exist: CY, IR and HPM3 to HPM10 (bit 1 is hardwired 0). */
  val inhibitMask: Long = 0x7fdL
}

/** A CSR written by a CSR instruction: its address and the value the instruction wrote (computed by
  * the instruction, before the CSR's WARL rules apply).
  */
case class CsrWrite(addr: Int, wdata: Long)

/** The machine-mode state of [[Iss]] (T3): the CSRs that a program can write, as fields (`mstatus`
  * as its two bits; `MPP` is hardwired to 3), and the counters, with the reset values of T3-02.
  * [[IssMachine]] works on it; the rest of the model reads it.
  */
final class MachineState(mtvecReset: Long) {
  var mstatusMie, mstatusMpie = false
  var mtvecBase: Long         = mtvecReset & ~3L // BASE, with its two zero bits
  var mtvecMode: Long         = mtvecReset & 1L  // 0 direct, 1 vectored
  var mie: Long               = 0
  var mscratch: Long          = 0
  var mepc: Long              = 0
  var mcause: Long            = 0
  var mtval: Long             = 0
  var mcountinhibit: Long     = 0
  var mcycle: Long            = 0                // 64 bits
  var minstret: Long          = 0

  /** `mhpmcounter<n>` and `mhpmevent<n>` at index n, for the programmable ones. */
  val hpm: Array[Long]      = new Array[Long](Counters.firstHpm + Counters.programmable)
  val hpmEvent: Array[Long] = new Array[Long](Counters.firstHpm + Counters.programmable)
}

/** The rules of Zicsr, of machine-mode traps and of the counters that [[Iss]] applies when its
  * configuration has Zicsr (`IssConfig.machine`). They are not in this package: they are the
  * software half of T3 (`mycpu.csrtrap.IssCsr`), as the M semantics are T2's (`IssConfig.mulDiv`).
  * The model keeps the state ([[MachineState]]), decodes the instructions, knows which CSRs exist
  * and what their WARL fields keep, and asks these functions what the ISA decides. Values are
  * 32-bit quantities in the low bits of a `Long`, as in [[IssRetire]].
  */
trait IssMachine {

  /** Whether a CSR instruction doing `op` (`'w'`, `'s'` or `'c'`: RW, RS or RC, in the register or
    * the immediate form) writes its CSR. `rs1` is the instruction's rs1 field: the number of the
    * source register, or the 5-bit immediate itself.
    */
  def csrWrites(op: Char, rs1: Int): Boolean

  /** Whether a CSR instruction at CSR address `addr` is an illegal instruction: `exists` says
    * whether this hart has a CSR there, and `writes` is what [[csrWrites]] says.
    */
  def csrIllegal(addr: Int, exists: Boolean, writes: Boolean): Boolean

  /** The value that a CSR instruction doing `op` writes, from the CSR's value `old` and `operand`
    * (the source register, or the zero-extended immediate), before the CSR's WARL rules apply.
    */
  def csrValue(op: Char, old: Long, operand: Long): Long

  /** Enters the trap with the `mcause` value `cause` (bit 31 set for an interrupt) and the `mtval`
    * value `tval`, taken at the instruction at `pc` (the one that raised the exception, or for an
    * interrupt the one that has not executed): changes `s`, and returns the pc of the handler.
    */
  def enterTrap(s: MachineState, pc: Long, cause: Long, tval: Long): Long

  /** Executes an `mret`: changes `s`, and returns the pc where execution continues. */
  def trapReturn(s: MachineState): Long

  /** The code of the interrupt that is taken when the interrupts whose bits are set in `eligible`
    * (`mip & mie`, never 0) are pending and enabled, and `mstatus.MIE` is set.
    */
  def interrupt(eligible: Long): Int

  /** Which counters count in one step (T3-04): `(mcycle, minstret)`. `inhibit` is `mcountinhibit`
    * as it was before the step, `retired` says whether an instruction retired in it, and
    * `wroteCycle` and `wroteInstret` whether its instruction wrote a half of `mcycle` or of
    * `minstret`.
    */
  def counts(inhibit: Long, retired: Boolean, wroteCycle: Boolean, wroteInstret: Boolean): (Boolean, Boolean)
}

/** A device that holds the 64-bit `mtime` that the `time` CSR reads (T3-05: the CLINT,
  * `mycpu.csrtrap.ClintModel`).
  */
trait MtimeSource {
  def mtime: Long
}

/** One step of [[Iss]], in the shape of [[TrapRetire]] (same fields, same meaning, but for `mip`,
  * which is what the hardware sampled before the step and not a result of it: see
  * [[CoSimCycle]]):
  *   - an instruction that retires: as `riscv.Retire` (`rd` 0 when nothing is written), with `csr`
  *     set when it is a CSR instruction that writes its CSR;
  *   - a synchronous exception: `trap` is set, `nextPc` is the handler, nothing else changed (rd 0,
  *     no memory access), and it does not count as retired;
  *   - an interrupt taken before the instruction at `pc`: `trap` is set with bit 31 of the cause,
  *     `inst` is 0 (the instruction did not execute) and `nextPc` is the handler;
  *   - in the tasks without traps (T1, T2), an instruction that halts: `halt` set, `nextPc = pc`.
  */
case class IssRetire(
    pc: Long,
    inst: Long,
    nextPc: Long,
    rd: Int = 0,
    rdValue: Long = 0,
    mem: Option[MemAccess] = None,
    halt: Option[Halt] = None,
    trap: Option[Trap] = None,
    csr: Option[CsrWrite] = None
) {

  /** The bootcamp's record of the same step (which has no trap or CSR fields). */
  def toRetire: Retire = Retire(pc, inst, nextPc, rd, rdValue, mem, halt)

  /** Whether an instruction retired: not a trap, and not an instruction that halts instead. */
  def retired: Boolean = trap.isEmpty && halt.isEmpty

  /** One line of a trace, as `riscv.Retire` prints it, with traps and CSR writes. */
  override def toString: String = {
    val effects = Seq(
      Option.when(rd != 0)(f"${Reg.names(rd)} <- 0x$rdValue%08x"),
      mem.map { m =>
        val arrow = if (m.write) "<-" else "->"
        f"mem[0x${m.addr}%08x] $arrow 0x${m.data}%x (${m.bytes} bytes)"
      },
      csr.map(c => f"${Csr.name(c.addr)} <- 0x${c.wdata}%08x"),
      trap.map(t => f"trap: mcause 0x${t.cause}%08x mtval 0x${t.tval}%08x"),
      Option.when(halt.isEmpty && nextPc != ((pc + 4) & 0xffffffffL))(f"pc <- 0x$nextPc%08x"),
      halt.map(h => s"halt: $h")
    ).flatten
    val text = if (trap.exists(_.interrupt)) "(interrupt)" else AsmX.disassemble(inst, pc)
    f"$pc%08x  $inst%08x  $text%-24s ${effects.mkString(", ")}".trim
  }
}

object IssRetire {

  /** The fields in which `core` and `model` differ, one line each with both raw values, in the
    * order of [[TrapRetire]]. A record prints as its disassembly and effects, which hides a field
    * that has no effect (the `rdValue` of a record with `rd` 0, say); this shows every one.
    */
  def diff(core: IssRetire, model: IssRetire): Seq[String] = {
    def hex(v: Long)                        = f"0x$v%08x"
    def field(name: String, c: Any, m: Any) = Option.when(c != m)(s"$name: core $c, model $m")
    // a part of the record that may be absent: its fields if both have it, else which one has it
    def group[T](name: String, c: Option[T], m: Option[T])(fields: T => Seq[(String, Any)]) =
      (c, m) match {
        case (Some(x), Some(y)) =>
          fields(x).zip(fields(y)).flatMap { case ((n, a), (_, b)) => field(s"$name.$n", a, b) }
        case _ => field(s"$name.valid", c.isDefined, m.isDefined).toSeq
      }
    Seq(
      field("pc", hex(core.pc), hex(model.pc)),
      field("inst", hex(core.inst), hex(model.inst)),
      field("nextPc", hex(core.nextPc), hex(model.nextPc)),
      field("rd", core.rd, model.rd),
      field("rdValue", hex(core.rdValue), hex(model.rdValue))
    ).flatten ++
      group("mem", core.mem, model.mem) { a =>
        Seq("addr" -> hex(a.addr), "write" -> a.write, "bytes" -> a.bytes, "data" -> hex(a.data))
      } ++
      field("halt", core.halt.getOrElse("none"), model.halt.getOrElse("none")) ++
      group("trap", core.trap, model.trap)(t => Seq("cause" -> hex(t.cause), "tval" -> hex(t.tval))) ++
      group("csr", core.csr, model.csr)(c => Seq("addr" -> f"0x${c.addr}%03x", "wdata" -> hex(c.wdata)))
  }
}

/** The extensions of a task:
  *   - `m`: the M extension (T2 on). The model decodes the M instructions itself, but what they
  *     compute is not in this package: it is `mulDiv`, which T2 supplies (`mycpu.rv32im.IssM`).
  *     T1 compiles this package, so a T2 exercise cannot live here;
  *   - `mulDiv`: the result of an M instruction, see [[IssConfig.MulDiv]]. A model with `m` and
  *     without it runs until its program executes an M instruction, and throws there;
  *   - `zicsr`: Zicsr, Zicntr, Zihpm and machine mode with traps and interrupts (T3 on). Without
  *     it, the model halts where a trap would be taken, as the bootcamp's cores do (`trapsHalt`);
  *   - `machine`: the rules of Zicsr, traps and counters ([[IssMachine]]), which T3 supplies
  *     (`mycpu.csrtrap.IssCsr`). A model with `zicsr` and without them throws at its first step
  *     (the counters count in every step);
  *   - `zifencei`: `fence.i` (a no-op: the model has no caches);
  *   - `mtvecReset`: the reset value of `mtvec` (T3-02).
  */
case class IssConfig(
    m: Boolean = false,
    zicsr: Boolean = false,
    zifencei: Boolean = true,
    mtvecReset: Long = MemMap.ramBase,
    mulDiv: Option[IssConfig.MulDiv] = None,
    machine: Option[IssMachine] = None
) {
  require(m || mulDiv.isEmpty, "mulDiv is given, but the M extension is off (m = false)")
  require(zicsr || machine.isEmpty, "machine is given, but Zicsr is off (zicsr = false)")

  /** Whether exceptions halt instead of trapping (every configuration without Zicsr: there is no
    * `mtvec` to trap to).
    */
  def trapsHalt: Boolean = !zicsr
}

object IssConfig {

  /** What an M instruction computes: `(name, rs1, rs2) => rd`, for the names `mul`, `mulh`,
    * `mulhsu`, `mulhu`, `div`, `divu`, `rem` and `remu` and the two source registers.
    */
  type MulDiv = (String, Int, Int) => Int

  val T1: IssConfig = IssConfig()

  /** T3 as far as this package knows it: M and Zicsr are decoded, but what M computes is T2's to
    * give and the rules of Zicsr, traps and counters are T3's. The model of a T3 system is
    * `mycpu.csrtrap.IssCsr(mycpu.rv32im.IssM(IssConfig.T3))` (`mycpu.csrtrap.IssCsr.T3`); this one
    * throws at its first step. The preset of T2 is `mycpu.rv32im.IssM.T2`.
    */
  val T3: IssConfig = IssConfig(m = true, zicsr = true)
}

/** How the model gets the data of a load from a device register:
  *   - `Modeled`: the program determines the register (what it stored, how many instructions it
  *     retired). The model computes the value, and a co-simulation compares it with the
  *     hardware's.
  *   - `Input`: the value comes from outside the program (a UART's receive data and status). It is
  *     sampled through [[IssSample.load]]; only its bits in `mask` exist, the others read 0.
  *   - `Counter`: one 32-bit half (`high` or low) of free-running counter `id`, which counts time
  *     that the ISA does not determine. It is sampled through [[IssSample.load]]; a co-simulation
  *     checks that the counter runs neither backward nor faster than the clock (`HardwareSample`).
  */
sealed trait ReadKind
object ReadKind {
  case object Modeled                        extends ReadKind
  case class Input(mask: Long)               extends ReadKind
  case class Counter(id: Int, high: Boolean) extends ReadKind
}

/** A device with 32-bit registers, as every device of D12 has: a load reads the bytes it addresses
  * of the register's word, and a store takes effect only if it writes the whole word (a narrower
  * store is answered and ignored, the rule of `SimCtrl` and the UART). `kind` says which registers
  * the model cannot compute; a `riscv.Device` that is not a `ModelDevice` is all `Modeled`. A
  * device that follows time, counts instructions or raises interrupts overrides the hooks below,
  * which [[Iss]] calls for every `ModelDevice` it has.
  */
abstract class ModelDevice(val base: Long, val size: Long) extends Device {

  /** The 32-bit register at byte `offset` (a multiple of 4) from `base`, as the model holds it. */
  protected def readWord(offset: Int): Long

  /** A 32-bit store of `data` to the register at `offset`. */
  protected def writeWord(offset: Int, data: Long): Unit

  /** How the model gets the value of the register at `offset`. */
  protected def kindOf(offset: Int): ReadKind = ReadKind.Modeled

  /** `n` clock cycles pass (the model alone: one per step, and the wait of a WFI). */
  def elapse(n: Long): Unit = ()

  /** An instruction retired (a trap is not one). */
  def retired(): Unit = ()

  /** The `mip` bits that the device drives now. */
  def pending: Long = 0

  /** The clock cycles after which the device will, left alone, raise one of the interrupts
    * `enabled` (as in `mie`) that it does not drive yet, if it will.
    */
  def wakeIn(enabled: Long): Option[Long] = None

  private def offset(addr: Long): Int = ((addr - base) & ~3L).toInt

  /** How the model gets the data of a load at `addr`. */
  final def kind(addr: Long): ReadKind = kindOf(offset(addr))

  final def read(addr: Long, bytes: Int): Long =
    ((readWord(offset(addr)) & 0xffffffffL) >>> (8 * (addr & 3))) & ((1L << (8 * bytes)) - 1)

  final def write(addr: Long, bytes: Int, data: Long): Unit =
    if (bytes == 4) writeWord(offset(addr), data & 0xffffffffL)
}

object ModelDevice {

  /** 64-bit `old` with its high or low half replaced by the 32-bit `v`. */
  def withHalf(old: Long, high: Boolean, v: Long): Long =
    if (high) (old & 0xffffffffL) | (v << 32) else (old & ~0xffffffffL) | (v & 0xffffffffL)
}

/** The UART (D12): a store to `TXDATA` appends a byte to `tx`, and `CTRL` bit 0 is read back as
  * stored. `RXDATA` and `STATUS` are inputs: the model alone reads an empty receiver and a
  * transmitter that is always ready (0 and 1).
  */
class UartModel(base: Long = MemMap.uartBase) extends ModelDevice(base, MemMap.uartSize) {
  import MemMap.Uart._

  /** The bytes the program transmitted. */
  val tx: StringBuilder = new StringBuilder
  private var ctrl      = 0L

  protected def readWord(offset: Int): Long = offset match {
    case Status => 1
    case Ctrl   => ctrl
    case _      => 0
  }
  protected def writeWord(offset: Int, data: Long): Unit = offset match {
    case TxData => tx += (data & 0xff).toChar
    case Ctrl   => ctrl = data & 1
    case _      => ()
  }
  override protected def kindOf(offset: Int): ReadKind = offset match {
    case RxData => ReadKind.Input(0xff) // a byte
    case Status => ReadKind.Input(0x7)  // tx_ready, rx_valid, rx_overrun
    case _      => ReadKind.Modeled
  }
}

/** SimCtrl (D12): a store to `EXIT` ends the program with the low byte of the data as its exit
  * code, a store to `PUTC` appends a byte to `console`, and `MEIP` bit 0 is read back as stored
  * and drives the model's `mip.MEIP`. `INSTRET` is the number of instructions retired before the
  * load that reads it (a trap is not one; `mcountinhibit` and writes to `minstret` do not touch
  * it), so it is modeled. `CYCLE` counts clock cycles, which the ISA does not determine (counter
  * `Iss.simCtrlCycle`): the model alone counts one cycle per step.
  */
class SimCtrlModel(base: Long = MemMap.simCtrlBase) extends ModelDevice(base, MemMap.simCtrlSize) {
  import MemMap.SimCtrl._

  /** The exit code, once the program has stored to `EXIT`. */
  var exit: Option[Int] = None

  /** The bytes stored to `PUTC`. */
  val console: StringBuilder = new StringBuilder

  /** The `CYCLE` and `INSTRET` counters (64 bits) and the `MEIP` level. */
  var cycle: Long   = 0
  var instret: Long = 0
  var meip: Boolean = false

  protected def readWord(offset: Int): Long = offset match {
    case CycleLo   => cycle
    case CycleHi   => cycle >>> 32
    case InstretLo => instret
    case InstretHi => instret >>> 32
    case Meip      => if (meip) 1 else 0
    case _         => 0
  }
  protected def writeWord(offset: Int, data: Long): Unit = offset match {
    case Exit => exit = Some((data & 0xff).toInt)
    case Putc => console += (data & 0xff).toChar
    case Meip => meip = (data & 1) != 0
    case _    => ()
  }
  override protected def kindOf(offset: Int): ReadKind = offset match {
    case CycleLo => ReadKind.Counter(Iss.simCtrlCycle, high = false)
    case CycleHi => ReadKind.Counter(Iss.simCtrlCycle, high = true)
    case _       => ReadKind.Modeled
  }
  override def elapse(n: Long): Unit = cycle += n
  override def retired(): Unit       = instret += 1
  override def pending: Long         = if (meip) 1L << Trap.Mei else 0
}

/** Where [[Iss]] gets the values that neither the ISA nor the program determines.
  *
  * Everything else the model computes itself, so a co-simulation checks the hardware's value
  * against it: registers, memory, every CSR but the counters below, `minstret` and SimCtrl
  * `INSTRET` (exact counts of retired instructions), the device registers a program writes, and
  * where interrupts are taken (the highest-priority one of `mie & mip` while `mstatus.MIE` is set,
  * before the next instruction).
  *
  * Sampled, with the model's own value passed as `modeled`:
  *   - `mip`: MSIP, MTIP and MEIP, which devices drive as time passes. From them the model
  *     decides which interrupt is taken, if any, and whether a WFI has to wait.
  *   - `csrRead`: `mcycle`, `time` and the programmable counters of `Counters` (their halves and user shadows).
  *   - `load`: device registers that are an `Input` or a `Counter` ([[ReadKind]]).
  *   - `wfiWait`: how long a WFI waits.
  */
trait IssSample {

  /** The `mip` bits that hardware drives, as the hart sees them when it decides whether to take an
    * interrupt before the instruction about to execute. `modeled` holds what the model's own
    * devices drive: its MSIP and MEIP are what the program stored to the CLINT's `msip` and to
    * SimCtrl `MEIP`, and its MTIP follows the model's guess of `mtime`.
    */
  def mip(modeled: Long): Long

  /** The value that a CSR instruction doing `op` (`'w'`, `'s'` or `'c'`) with `operand` reads from
    * counter CSR `addr`, one of those [[Iss.sampled]] lists.
    */
  def csrRead(addr: Int, modeled: Long, op: Char, operand: Long): Long

  /** The data that a load of `bytes` bytes at device address `addr` reads (zero-extended), from a
    * register of `kind` `Input` or `Counter`.
    */
  def load(addr: Long, bytes: Int, kind: ReadKind, modeled: Long): Long

  /** A WFI executes while none of the interrupts `enabled` in `mie` is pending, so it waits until
    * one is (T3-02): the time (steps of the model, clock cycles) that passes before it retires.
    * `timer` is the model's own answer: the steps until one of its devices raises one, if that
    * can happen.
    */
  def wfiWait(enabled: Long, timer: Option[Long]): Long
}

object IssSample {

  /** The model's own values: one clock cycle per step, devices that nothing outside drives, and a
    * WFI that waits for the model's timer.
    */
  object Model extends IssSample {
    def mip(modeled: Long): Long                                          = modeled
    def csrRead(addr: Int, modeled: Long, op: Char, operand: Long): Long  = modeled
    def load(addr: Long, bytes: Int, kind: ReadKind, modeled: Long): Long = modeled
    def wfiWait(enabled: Long, timer: Option[Long]): Long                 =
      timer.getOrElse(throw new IllegalStateException("the hart is asleep in WFI: nothing can wake it"))
  }
}

/** A read-only memory of the model besides its RAM: T5's boot ROM (D12), `words` from `base` in a
  * window of `size` bytes (the rest of the window reads 0). The model fetches and loads from it,
  * and a store to it is a store access fault, as the SoC's ROM answers a write with `SLVERR`. A
  * model with a ROM starts at `base`, the SoC's reset vector, whatever the program's entry point.
  */
case class IssRom(base: Long, size: Long, words: IndexedSeq[Long]) {
  require(base >= 0 && base % 4 == 0 && size % 4 == 0 && base + size <= (1L << 32), "the ROM must be whole words")
  require(words.size * 4L <= size, s"${words.size} words do not fit in $size bytes")

  def contains(addr: Long, bytes: Int): Boolean = addr >= base && addr + bytes <= base + size

  /** `bytes` bytes at `addr`, little-endian, zero-extended (`contains(addr, bytes)` must hold). */
  def read(addr: Long, bytes: Int): Long = {
    var v = 0L
    for (k <- bytes - 1 to 0 by -1) {
      val at = addr - base + k
      v = v << 8 | (words.lift((at / 4).toInt).getOrElse(0L) >>> (8 * (at % 4)) & 0xffL)
    }
    v
  }
}

/** The golden ISA model of this repository (INF-10): RV32I, and as configured M, Zicsr with the
  * counters, machine-mode traps and interrupts (T3-01 to T3-04). It executes one instruction (or
  * takes one trap) per `step` and reports it as an [[IssRetire]].
  *
  * The memory is one RAM of `size` bytes at `base`, zeros until a program is loaded,
  * `devices` (loads and stores only; instructions come from the RAM), and with `rom` a read-only
  * memory from which instructions come too ([[IssRom]]; the model then starts there). The values that neither
  * the ISA nor the program determines come from `sample`
  * ([[IssSample]] lists them); with the default sample the model stands alone.
  *
  * Choices of the model where the ISA leaves room:
  *   - '''Counter writes''' (T3-04). A CSR write "takes effect after the writing instruction has
  *     otherwise completed" (Privileged spec, Hardware Performance Monitor), so the instruction
  *     after a write to `mcycle` or `minstret` reads the written value: the write replaces that
  *     step's increment, in either half. Sail 0.16 does the same for `minstret`; its `mcycle`
  *     follows a platform clock (it read 0, 0, then 101 twice after a write of 100) and is no
  *     reference. The increments of a step are decided by `mcountinhibit` as it was before the
  *     step, as in Sail.
  *   - '''WARL fields''' (T3-01), as in Sail 0.16 configured for this hart: a CSR write of a value
  *     that `mcause` cannot hold ([[Trap.legalCause]]) leaves `mcause` as it was, and
  *     `mhpmevent3..10` keep the low 4 bits of what is written (the IDs 9 to 15 select no event).
  *   - '''WFI''' (T3-02) retires at once if `mip & mie` is not 0, whatever `mstatus.MIE` is, and
  *     otherwise waits for as long as `sample.wfiWait` says: alone, until the CLINT's `mtime`
  *     reaches `mtimecmp` with MTIE set (if that is less than 2^63 cycles away), skipping that
  *     time in one step. A WFI that nothing in the
  *     model can wake does not execute: the model is then `asleep`, which ends `run`.
  *   - '''Devices'''. Every [[ModelDevice]] is told of the cycles that pass and the instructions
  *     that retire, and drives `mip`; the [[SimCtrlModel]] is where a program exits, and the
  *     [[MtimeSource]] (the CLINT, `mycpu.csrtrap.ClintModel`) is what the `time` CSR reads.
  *
  * The rules that decide CSR instructions, traps, `mret`, which interrupt is taken and when the
  * counters count are `config.machine`'s ([[IssMachine]]); the choices above are this class's.
  *
  * Without Zicsr (`config.trapsHalt`), every exception halts as in `riscv.IsaSim`, with the same
  * records: a fetch outside the RAM retires with `inst` 0 and halts `BadAddress`, then illegal
  * words, `ecall`/`ebreak`, misaligned jump targets and accesses (misaligned outranks a bad
  * address), and accesses outside the RAM and devices.
  */
class Iss(
    val config: IssConfig = IssConfig.T1,
    val base: Long = MemMap.ramBase,
    val size: Int = MemMap.ramBytes,
    val devices: Seq[Device] = Nil,
    val sample: IssSample = IssSample.Model,
    val rom: Option[IssRom] = None
) {
  require(
    base >= 0 && base % 4 == 0 && size % 4 == 0 && size > 0 && base + size <= (1L << 32),
    "the RAM must be whole words"
  )
  require(devices.forall(d => d.base + d.size <= base || d.base >= base + size), "a device overlaps the RAM")
  require(
    rom.forall { r =>
      def apart(b: Long, s: Long) = r.base + r.size <= b || r.base >= b + s
      apart(base, size) && devices.forall(d => apart(d.base, d.size))
    },
    "the ROM overlaps the RAM or a device"
  )

  private val ram                   = new Array[Byte](size)
  private val x                     = new Array[Int](32)
  private var _pc: Long             = base
  private var _halted: Option[Halt] = None
  private val models                = devices.collect { case m: ModelDevice => m }.toArray
  private val simCtrl               = models.collectFirst { case s: SimCtrlModel => s } // where the program exits
  private val clint                 = models.collectFirst { case c: MtimeSource => c }  // what `time` reads

  // machine-mode state (T3), and its rules, which the configuration supplies (see IssMachine)
  private val st                       = new MachineState(config.mtvecReset)
  private var ownTime                  = 0L // what `time` reads without a CLINT
  private lazy val machine: IssMachine = config.machine.getOrElse {
    throw new IllegalStateException(
      "the program needs a rule of Zicsr, traps or counters, and this model has Zicsr without them: " +
        "build the configuration with mycpu.csrtrap.IssCsr (IssCsr.T3, or IssCsr(config))"
    )
  }

  /** Interrupt-pending bits (MSIP, MTIP, MEIP) that a test drives from outside the model's
    * devices; the model ORs them with what its devices drive.
    */
  var mipHw: Long = 0

  /** The pc of the next step, why the model halted (only without traps), and the registers. */
  def pc: Long               = _pc
  def halted: Option[Halt]   = _halted
  def reg(n: Int): Long      = x(n) & 0xffffffffL
  def regs: IndexedSeq[Long] = (0 until 32).map(reg)

  /** `mtime`, as the model guesses it: the CLINT's, or without one a count of steps. */
  def mtime: Long = clint.fold(ownTime)(_.mtime)

  /** The exit code written to the [[SimCtrlModel]] among the devices, if any. */
  def exit: Option[Int] = simCtrl.flatMap(_.exit)

  /** Whether the program has ended: halted, or exited through SimCtrl. */
  def ended: Boolean = _halted.isDefined || exit.isDefined

  /** Whether the hart is at a WFI that nothing in the model can wake: no enabled interrupt is
    * pending by the model's own devices and `mipHw`, and its timer will not raise one.
    */
  def asleep: Boolean = // the fetch first: it is what nearly always says no
    config.zicsr && instructionAt(_pc).contains(Iss.wfi) && !ended &&
      (modeledMip & st.mie) == 0 && deviceWake.isEmpty

  /** Whether the program can exit: the model has a [[SimCtrlModel]]. */
  def canExit: Boolean = simCtrl.isDefined

  /** Whether `run` stops here: the program has ended, or the hart is asleep for good. */
  def done: Boolean = ended || asleep

  /** Writes `image` into the RAM and starts at its entry point, which must be a word of the RAM
    * (with a ROM: at the ROM's first word, see [[IssRom]]).
    */
  def load(image: MemImage): Iss = {
    require(
      image.entry % 4 == 0 && inRam(image.entry, 4),
      f"the entry point 0x${image.entry}%08x is not a word of the RAM"
    )
    for ((addr, b) <- image.bytes) {
      require(inRam(addr, 1), f"0x$addr%08x is outside the RAM")
      ram((addr - base).toInt) = b
    }
    _pc = rom.fold(image.entry)(_.base)
    this
  }

  private def inRam(addr: Long, bytes: Int)  = addr >= base && addr + bytes <= base + size
  private def inRom(addr: Long, bytes: Int)  = rom.exists(_.contains(addr, bytes))
  private def device(addr: Long, bytes: Int) = devices.find(d => addr >= d.base && addr + bytes <= d.base + d.size)
  private def lowBytes(v: Long, bytes: Int)  = v & ((1L << (8 * bytes)) - 1)
  private def u32(v: Long): Long             = v & 0xffffffffL

  /** `bytes` bytes of the RAM at `addr`, little-endian, zero-extended. */
  def read(addr: Long, bytes: Int): Long = {
    require(inRam(addr, bytes), f"0x$addr%08x is outside the RAM")
    bytesAt(addr, bytes)
  }

  /** How the model gets the data of a load of `bytes` bytes at `addr`, if a device is there. */
  def readKind(addr: Long, bytes: Int): Option[ReadKind] = device(addr, bytes).map(kindOf(_, addr))

  private def kindOf(d: Device, addr: Long): ReadKind = d match {
    case m: ModelDevice => m.kind(addr)
    case _              => ReadKind.Modeled
  }

  private def bytesAt(addr: Long, bytes: Int): Long = {
    var v = 0L
    for (k <- bytes - 1 to 0 by -1) v = v << 8 | (ram((addr - base).toInt + k) & 0xffL)
    v
  }

  /** `bytes` bytes of the ROM at `addr`, if the ROM holds them. */
  private def romRead(addr: Long, bytes: Int): Option[Long] =
    rom.filter(_.contains(addr, bytes)).map(_.read(addr, bytes))

  /** The instruction word at `pc`, if the RAM or the ROM holds it. */
  private def instructionAt(pc: Long): Option[Long] =
    if (inRam(pc, 4)) Some(bytesAt(pc, 4)) else romRead(pc, 4)

  private def load(addr: Long, bytes: Int): Long = device(addr, bytes) match {
    case None    => romRead(addr, bytes).getOrElse(bytesAt(addr, bytes))
    case Some(d) =>
      val modeled = lowBytes(d.read(addr, bytes), bytes)
      kindOf(d, addr) match {
        case ReadKind.Modeled         => modeled
        case kind: ReadKind.Counter   => lowBytes(sample.load(addr, bytes, kind, modeled), bytes)
        case kind @ ReadKind.Input(m) => // masked to the bytes of the register that the load addresses
          lowBytes(sample.load(addr, bytes, kind, modeled) & (m >>> (8 * (addr & 3))), bytes)
      }
  }

  private def store(addr: Long, bytes: Int, value: Long): Unit = device(addr, bytes) match {
    case None    => for (k <- 0 until bytes) ram((addr - base).toInt + k) = (value >> (8 * k)).toByte
    case Some(d) => d.write(addr, bytes, value)
  }

  // ---- CSRs (T3-01)

  /** Whether CSR `addr` exists (only with Zicsr). */
  def csrExists(addr: Int): Boolean = config.zicsr && (addr match {
    case Csr.Mvendorid | Csr.Marchid | Csr.Mimpid | Csr.Mhartid | Csr.Mconfigptr         => true
    case Csr.Mstatus | Csr.Misa | Csr.Mie | Csr.Mtvec | Csr.Mstatush | Csr.Mcountinhibit => true
    case Csr.Mscratch | Csr.Mepc | Csr.Mcause | Csr.Mtval | Csr.Mip                      => true
    case a if a >= Csr.mhpmevent(3) && a <= Csr.mhpmevent(31)                            => true
    case a                                                                               => Iss.counterOf(a).isDefined
  })

  /** The interrupt-pending bits by the model's own devices and `mipHw`. */
  private def modeledMip: Long = {
    var bits = mipHw
    for (m <- models) bits |= m.pending
    bits & Trap.pendingMask
  }

  /** `mip` as the hart sees it now: from `sample`. */
  def mip: Long = sample.mip(modeledMip) & Trap.pendingMask

  private def counterValue(n: Int): Long = n match {
    case 0                      => st.mcycle
    case 1                      => mtime
    case 2                      => st.minstret
    case n if Counters.isHpm(n) => st.hpm(n)
    case _                      => 0
  }

  /** The value of CSR `addr` as the model holds it (`mip` as the hart sees it; no side effect). */
  def csr(addr: Int): Long = {
    require(csrExists(addr), f"no CSR 0x$addr%03x")
    addr match {
      case Csr.Mstatus       => (if (st.mstatusMie) 1L << 3 else 0) | (if (st.mstatusMpie) 1L << 7 else 0) | (3L << 11)
      case Csr.Misa          => (1L << 30) | (1L << 8) | (if (config.m) 1L << 12 else 0)
      case Csr.Mie           => st.mie
      case Csr.Mtvec         => st.mtvecBase | st.mtvecMode
      case Csr.Mscratch      => st.mscratch
      case Csr.Mepc          => st.mepc
      case Csr.Mcause        => st.mcause
      case Csr.Mtval         => st.mtval
      case Csr.Mip           => mip
      case Csr.Mcountinhibit => st.mcountinhibit
      case a if a >= Csr.mhpmevent(3) && a <= Csr.mhpmevent(31) =>
        val n = a - Csr.Mcountinhibit // mhpmevent<n> is n above mcountinhibit
        if (Counters.isHpm(n)) st.hpmEvent(n) else 0
      case a =>
        Iss.counterOf(a).fold(0L) { n => // else the ID registers, mstatush
          val v = counterValue(n)
          u32(if (Iss.highHalf(a)) v >>> 32 else v)
        }
    }
  }

  // set by a write to either half of mcycle or minstret in the current step, for
  // IssMachine.counts (see the class comment)
  private var wroteCycle, wroteInstret = false

  /** A CSR write of `v` to `addr`, through the WARL rules of each CSR (T3-01). */
  private def writeCsr(addr: Int, v: Long): Unit = {
    def half(old: Long) = ModelDevice.withHalf(old, Iss.highHalf(addr), v)
    addr match {
      case Csr.Mstatus       => st.mstatusMie = (v >> 3 & 1) != 0; st.mstatusMpie = (v >> 7 & 1) != 0
      case Csr.Mie           => st.mie = v & Trap.pendingMask
      case Csr.Mtvec         => st.mtvecBase = v & ~3L; if ((v & 3) < 2) st.mtvecMode = v & 3
      case Csr.Mscratch      => st.mscratch = v
      case Csr.Mepc          => st.mepc = v & ~3L
      case Csr.Mcause        => if (Trap.legalCause(v)) st.mcause = v
      case Csr.Mtval         => st.mtval = v
      case Csr.Mcountinhibit => st.mcountinhibit = v & Counters.inhibitMask
      case a if Counters.isHpm(a - Csr.Mcountinhibit) =>
        st.hpmEvent(a - Csr.Mcountinhibit) = v & ((1L << Counters.eventBits) - 1)
      case Csr.Mcycle | Csr.Mcycleh     => st.mcycle = half(st.mcycle); wroteCycle = true
      case Csr.Minstret | Csr.Minstreth => st.minstret = half(st.minstret); wroteInstret = true
      case a if (a & 0xc00) != 0xc00 && Iss.counterOf(a).exists(Counters.isHpm) => // not a read-only user shadow
        st.hpm(a & 0x1f) = half(st.hpm(a & 0x1f))
      case _ => () // misa, mip, mstatush, mhpmcounter11..31, mhpmevent11..31: WARL, writes ignored
    }
  }

  // ---- traps (T3-02, T3-03)

  private def haltFor(cause: Long): Halt = cause match {
    case Trap.Illegal                                                     => Halt.Illegal
    case Trap.EcallM                                                      => Halt.Ecall
    case Trap.Breakpoint                                                  => Halt.Ebreak
    case Trap.InstMisaligned | Trap.LoadMisaligned | Trap.StoreMisaligned => Halt.Misaligned
    case _                                                                => Halt.BadAddress
  }

  /** An exception at `pc`: a trap, or a halt without traps. */
  private def except(pc: Long, word: Long, cause: Long, tval: Long): IssRetire =
    if (config.trapsHalt) {
      val h = haltFor(cause)
      _halted = Some(h)
      IssRetire(pc, word, pc, halt = Some(h))
    } else {
      takeTrap(pc, word, cause, tval)
    }

  /** Enters the trap handler: the record of an exception of `word` at `pc`, or of an interrupt. */
  private def takeTrap(pc: Long, word: Long, cause: Long, tval: Long): IssRetire = {
    _pc = u32(machine.enterTrap(st, pc, cause, tval))
    IssRetire(pc, word, _pc, trap = Some(Trap(cause, tval)))
  }

  // What an M instruction computes is not part of this package (see [[IssConfig]]). A model that
  // decodes M without it must not guess a result: it stops the run that needs one.
  private val mulDiv: IssConfig.MulDiv = config.mulDiv.getOrElse { (name, _, _) =>
    throw new IllegalStateException(
      s"the program executes `$name`, and this model has the M extension without its semantics: " +
        "build the configuration with mycpu.rv32im.IssM (IssM.T2, or IssM(config))"
    )
  }

  // decoding is the slow part of a step; programs execute the same words again and again
  private val decoded = mutable.HashMap[Long, Option[Inst]]()

  private def decode(word: Long): Option[Inst] = decoded.getOrElseUpdate(
    word,
    AsmX.decodeExt(word) match {
      case Some(i) if AsmX.isM(i.name)    => Option.when(config.m)(i)
      case Some(i) if i.name == "fence.i" => Option.when(config.zifencei)(i)
      case Some(i)                        => Option.when(config.zicsr)(i) // Zicsr, mret, wfi
      case None                           => Rv32i.decode(word)
    }
  )

  /** Lets `n` clock cycles pass: the devices count them, and `mcycle` if asked. */
  private def elapse(n: Long, countCycle: Boolean): Unit = {
    if (countCycle) st.mcycle += n
    if (clint.isEmpty) ownTime += n
    for (m <- models) m.elapse(n)
  }

  /** Takes the interrupt that is due at `pc`, or else executes the instruction there. The program
    * must not have ended; alone, the model must not be `asleep` either.
    */
  def step(): IssRetire = {
    require(!ended, s"the program has ended (${_halted.getOrElse(s"exit ${exit.get}")})")
    wroteCycle = false
    wroteInstret = false
    val inhibit = st.mcountinhibit // the value before the step: IssMachine.counts decides with it
    val r       = interrupt().getOrElse(fetchAndExecute())
    // the model counts one cycle per step (without Zicsr, nothing can read or change the counters)
    val (cycle, instret) =
      if (config.zicsr) machine.counts(inhibit, r.retired, wroteCycle, wroteInstret) else (true, r.retired)
    elapse(1, countCycle = cycle)
    if (instret) st.minstret += 1
    if (r.retired) for (m <- models) m.retired()
    r
  }

  /** The interrupt taken before the instruction at `pc` (T3-03), if one is pending and enabled
    * while `mstatus.MIE` is set: the one that `machine` chooses.
    */
  private def interrupt(): Option[IssRetire] =
    if (!config.zicsr || !st.mstatusMie) None
    else {
      val eligible = mip & st.mie
      if (eligible == 0) None // the usual case
      else {
        val code = machine.interrupt(eligible)
        require((eligible >> code & 1) != 0, f"interrupt $code is not among the eligible 0x$eligible%03x")
        Some(takeTrap(_pc, 0, Trap.interruptCause(code), 0))
      }
    }

  /** The steps until one of the model's devices raises an enabled interrupt, if one will. */
  private def deviceWake: Option[Long] = models.flatMap(_.wakeIn(st.mie)).minOption

  private def fetchAndExecute(): IssRetire = instructionAt(_pc) match {
    case None       => except(_pc, 0, Trap.InstAccessFault, _pc)
    case Some(word) =>
      decode(word) match {
        case None    => except(_pc, word, Trap.Illegal, word)
        case Some(i) => execute(_pc, word, i)
      }
  }

  private def execute(pc: Long, word: Long, i: Inst): IssRetire = {
    val a = x(i.rs1)
    val b = x(i.rs2)
    // branches and stores have no rd field (decode gives 0), so they write nothing either
    def retire(next: Long, value: Long = 0, mem: Option[MemAccess] = None, csr: Option[CsrWrite] = None) = {
      if (i.rd != 0) x(i.rd) = value.toInt
      _pc = u32(next) // the pc wraps at 2^32, as a core's 32-bit pc does
      IssRetire(pc, word, _pc, i.rd, if (i.rd == 0) 0 else u32(value), mem, csr = csr)
    }
    // an instruction without a destination register (the rd field of fence is reserved)
    def advance(next: Long = pc + 4) = { _pc = u32(next); IssRetire(pc, word, _pc) }
    // a jump or a taken branch; it traps (or halts) without writing its link register if the target is misaligned
    def jump(target: Long) =
      if (target % 4 != 0) except(pc, word, Trap.InstMisaligned, target) else retire(target, pc + 4)
    // a misaligned address outranks an access fault (T3-02, as the bootcamp's cores check them)
    def access(addr: Long, bytes: Int, write: Boolean)(body: => IssRetire) =
      if (addr % bytes != 0) except(pc, word, if (write) Trap.StoreMisaligned else Trap.LoadMisaligned, addr)
      else if (!inRam(addr, bytes) && device(addr, bytes).isEmpty && (write || !inRom(addr, bytes)))
        except(pc, word, if (write) Trap.StoreAccess else Trap.LoadAccessFault, addr)
      else body
    lazy val addr  = u32(a.toLong + i.imm) // of loads, stores and jalr
    lazy val bytes = IsaSim.width(i.name)

    i.name match {
      case "lui"                                           => retire(pc + 4, i.imm)
      case "auipc"                                         => retire(pc + 4, pc + i.imm)
      case "jal"                                           => jump(u32(pc + i.imm))
      case "jalr"                                          => jump(addr & ~1L)
      case "beq" | "bne" | "blt" | "bge" | "bltu" | "bgeu" =>
        if (IsaSim.taken(i.name, a, b)) jump(u32(pc + i.imm)) else retire(pc + 4)
      case "lb" | "lh" | "lw" | "lbu" | "lhu" =>
        access(addr, bytes, write = false) {
          val raw = load(addr, bytes)
          retire(pc + 4, IsaSim.loadValue(i.name, raw), Some(MemAccess(addr, write = false, bytes, raw)))
        }
      case "sb" | "sh" | "sw" =>
        access(addr, bytes, write = true) {
          val data = lowBytes(u32(b), bytes)
          store(addr, bytes, data)
          retire(pc + 4, mem = Some(MemAccess(addr, write = true, bytes, data)))
        }
      // one in-order hart without caches: nothing to order
      case "fence" | "fence.i" => advance()
      case "ecall"             => except(pc, word, Trap.EcallM, 0)
      case "ebreak"            => except(pc, word, Trap.Breakpoint, pc)
      case "mret"              => advance(machine.trapReturn(st))
      case "wfi"               =>
        // T3-02: a stall until an enabled interrupt is pending, then pc + 4 (the interrupt is taken
        // before the next instruction if mstatus.MIE is set)
        if ((mip & st.mie) == 0)
          elapse(
            sample.wfiWait(st.mie, deviceWake),
            countCycle = machine.counts(st.mcountinhibit, false, false, false)._1
          )
        advance()
      case name if AsmX.isCsr(name)     => csrInstruction(pc, word, i, (v, c) => retire(pc + 4, v, csr = c))
      case name if AsmX.isM(name)       => retire(pc + 4, mulDiv(name, a, b))
      case name if Rv32i.isAluImm(name) => retire(pc + 4, IsaSim.alu(name.filterNot(_ == 'i'), a, i.imm)) // addi -> add
      case name                         => retire(pc + 4, IsaSim.alu(name, a, b))
    }
  }

  /** A CSR instruction (T3-01), by `machine`'s rules: whether it writes, whether it is illegal,
    * and what it writes. A CSRRW with rd = x0 does not read its CSR (which matters only for a CSR
    * whose value is sampled).
    */
  private def csrInstruction(pc: Long, word: Long, i: Inst, retire: (Long, Option[CsrWrite]) => IssRetire) = {
    val addr    = i.imm & 0xfff
    val op      = i.name.stripSuffix("i").last // 'w', 's' or 'c'
    val operand = if (i.name.endsWith("i")) i.rs1.toLong else u32(x(i.rs1))
    val writes  = machine.csrWrites(op, i.rs1)
    val reads   = op != 'w' || i.rd != 0
    if (machine.csrIllegal(addr, csrExists(addr), writes)) except(pc, word, Trap.Illegal, word)
    else {
      require(csrExists(addr), f"the CSR instruction at 0x$pc%08x is legal, but there is no CSR 0x$addr%03x")
      val old =
        if (!reads) 0L
        else if (Iss.sampled(addr)) u32(sample.csrRead(addr, csr(addr), op, operand))
        else csr(addr)
      val wdata = u32(machine.csrValue(op, old, operand))
      if (writes) writeCsr(addr, wdata)
      retire(old, Option.when(writes)(CsrWrite(addr, wdata)))
    }
  }

  /** Steps until the model is `done` (the program ended, or the hart is asleep for good), at most
    * `maxSteps` times; returns the records.
    */
  def run(maxSteps: Int = 1000000): Seq[IssRetire] = {
    val trace = mutable.ArrayBuffer[IssRetire]()
    while (!done && trace.size < maxSteps) trace += step()
    trace.toSeq
  }
}

object Iss {

  /** The model with `image` loaded. */
  def apply(
      image: MemImage,
      config: IssConfig = IssConfig.T1,
      size: Int = MemMap.ramBytes,
      devices: Seq[Device] = Nil
  ): Iss =
    new Iss(config, MemMap.ramBase, size, devices).load(image)

  /** The devices of T1 and T2 besides the RAM (D12): the UART and SimCtrl. The CLINT of T3 on is
    * modeled by that task (`mycpu.csrtrap.ClintModel`, `mycpu.csrtrap.IssCsr.devices`), so
    * `clint` must be false here.
    */
  def simDevices(clint: Boolean): Seq[Device] = {
    require(!clint, "the CLINT model is T3's: use mycpu.csrtrap.IssCsr.devices")
    Seq(new UartModel(), new SimCtrlModel())
  }

  /** The free-running counters by number: those of Zicntr and Zihpm have the number of their CSR
    * (`mcycle` 0, `time` and the CLINT's `mtime` 1, `minstret` 2, `mhpmcounter<n>` n), and SimCtrl
    * `CYCLE` comes after them.
    */
  val timeCounter: Int  = 1
  val simCtrlCycle: Int = 32

  /** The number of the counter that CSR `addr` reads (a machine counter or its user shadow, either
    * half), if it is one.
    */
  def counterOf(addr: Int): Option[Int] = {
    val n = addr & 0x1f
    Option.when(((addr & ~0x9f) == 0xb00 && n != 1) || (addr & ~0x9f) == 0xc00)(n)
  }

  /** Whether a read of CSR `addr` comes from an [[IssSample]]: `mcycle`, `time` and the hpm
    * counters 3..10 (with their shadows and high halves), which count time that the ISA does not
    * determine.
    */
  def sampled(addr: Int): Boolean = counterOf(addr).exists(n => n != 2 && n < Counters.firstHpm + Counters.programmable)

  /** Whether counter CSR `addr` is the high half (`mcycleh`, `timeh`, ...). */
  def highHalf(addr: Int): Boolean = (addr & 0x80) != 0

  private val wfi = AsmX.fixed("wfi")
}
