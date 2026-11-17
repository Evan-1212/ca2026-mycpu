// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import chisel3.simulator.PeekPokeAPI._
import mycpu.common.{AsmX, Csr, CsrWrite, Iss, IssRetire, IssSample, MemImage, MemMap}
import mycpu.common.{ModelDevice, ReadKind, SimCtrlModel, UartModel}

import scala.collection.mutable
import scala.util.Random

/** [[CsrFile]], [[Clint]] and [[HpmClassifier]] wired as in a core: the CLINT drives `msip`, `mtip`
  * and `time`, `meip` comes from outside (SimCtrl or the UART in a system), and the status of the
  * cycle drives the classifier and, with its `retired`, the file.
  *
  * @param file
  *   the CSR file (a mutant of it in a negative test)
  */
class CsrClintTop(file: => CsrFile = new CsrFile()) extends Module {
  val io = IO(new Bundle {
    val csr    = new CsrPort
    val trap   = new TrapPort
    val mret   = new MretPort
    val irq    = new InterruptRequest
    val mip    = Output(UInt(32.W))
    val status = Input(new CycleStatus)
    val meip   = Input(Bool())
    val clint  = Flipped(new riscv.MemPort)
  })
  val csrs       = Module(file)
  val clint      = Module(new Clint())
  val classifier = Module(new HpmClassifier)
  csrs.io.csr <> io.csr
  csrs.io.trap <> io.trap
  csrs.io.mret <> io.mret
  io.irq               := csrs.io.irq
  io.mip               := csrs.io.mip
  classifier.io.status := io.status
  csrs.io.retired      := io.status.retired
  csrs.io.events       := classifier.io.events
  csrs.io.meip         := io.meip
  csrs.io.msip         := clint.io.msip
  csrs.io.mtip         := clint.io.mtip
  csrs.io.time         := clint.io.time
  clint.io.port <> io.clint
}

/** Random programs for [[IssLockstep]]: CSR instructions of every form on every kind of address,
  * `ecall`, `ebreak`, `mret`, `wfi` (alone, and behind a timer that will wake it), jumps and
  * branches to aligned and misaligned targets, loads and stores of every width to the CLINT,
  * stores to SimCtrl's `MEIP`, and instructions that load new operand values. x16 to x31 are never
  * a destination of the random statements: x28 to x31 hold the addresses of the device registers,
  * and the others belong to the fixed sequences.
  */
object RandomCsrProgram {
  private val counters = (3 to 10).flatMap(n => Seq(s"mhpmcounter$n", s"mhpmcounter${n}h", s"hpmcounter$n"))
  private val events   = (3 to 10).map(n => s"mhpmevent$n")
  private val named    = Seq(
    "mstatus",
    "mstatus",
    "mstatus",
    "mie",
    "mie",
    "mip",
    "mtvec",
    "mtvec",
    "mepc",
    "mepc",
    "mcause",
    "mcause",
    "mtval",
    "mscratch",
    "mcountinhibit",
    "mcountinhibit",
    "misa",
    "mstatush",
    "mcycle",
    "mcycleh",
    "minstret",
    "minstreth",
    "mhpmcounter11",
    "mhpmevent11",
    "cycle",
    "cycleh",
    "time",
    "timeh",
    "instret",
    "instreth",
    "hpmcounter10h",
    "hpmcounter31h",
    "mvendorid",
    "mhartid",
    "mconfigptr"
  )
  // the programmable counters and their events are a third of the picks
  private val interesting = (named ++ events ++ events ++ counters.take(12)).map(Csr.byName)

  private val masks = Seq(0x8L, 0x80L, 0x800L, 0x888L, 0x88L, 0x1888L, 0x7fdL, 0x5L, 0xffffffffL, 0x80000000L,
    0x7fffffffL, 0xfffffffcL, 0x80000003L, 0x80000007L, 0x8000000bL)

  /** The statements both kinds of program share. With `handlers`, the program runs under its own
    * trap handlers, so nothing may send it astray: no `mret` outside a handler, no write to
    * `mtvec` or `mepc`, and no jump through a register.
    */
  private class Statements(rng: Random, items: Int, handlers: Boolean) {
    def pick[T](s: Seq[T]): T = s(rng.nextInt(s.size))
    def chance(percent: Int)  = rng.nextInt(100) < percent
    def dest: String          = s"x${1 + rng.nextInt(15)}"
    def destOrZero: String    = if (chance(20)) "x0" else dest
    def value: Long           = rng.nextInt(100) match {
      case n if n < 20 => (MemMap.ramBase + rng.nextInt(1 << 16)) | rng.nextInt(4) // an address, any low bits
      case n if n < 40 => rng.nextInt(32).toLong                                   // an event ID, a cause
      case n if n < 65 => pick(masks)
      case n if n < 85 => rng.nextInt(2 * items).toLong // near the time: a timer compare value
      case _           => rng.nextLong() & 0xffffffffL
    }
    private val keptFromPrograms = Set(Csr.Mtvec, Csr.Mepc)
    def csrAddress: Int          = {
      val addr = rng.nextInt(100) match {
        case n if n < 85 => pick(interesting)
        case n if n < 92 => pick(CsrFile.implemented)
        case _           => rng.nextInt(4096)
      }
      if (handlers && keptFromPrograms(addr)) Csr.Mscratch else addr
    }
    def csr: String = {
      val op   = pick(Seq("csrrw", "csrrs", "csrrc"))
      val addr = csrAddress
      if (chance(35)) s"${op}i $destOrZero, 0x${addr.toHexString}, ${if (chance(20)) 0 else 1 + rng.nextInt(31)}"
      else s"$op $destOrZero, 0x${addr.toHexString}, ${if (chance(20)) "x0" else dest}"
    }
    // the CLINT's registers as (base register, offset), and one offset without a register
    private val clint                    = Seq("x29" -> 0, "x30" -> 0, "x30" -> 4, "x31" -> 0, "x31" -> 4, "x29" -> 8)
    def clintOperand(width: Int): String = {
      val (reg, offset) = pick(clint)
      // mostly aligned; sometimes a byte lane of the word, which is misaligned for a wider access
      s"${offset + (if (width < 4 || chance(5)) rng.nextInt(4) else 0)}($reg)"
    }
    def store: String = {
      val (op, width) = pick(Seq("sw" -> 4, "sw" -> 4, "sw" -> 4, "sw" -> 4, "sh" -> 2, "sb" -> 1))
      s"$op ${if (chance(30)) "x0" else dest}, ${clintOperand(width)}"
    }
    def load: String = {
      val (op, width) = pick(Seq("lw" -> 4, "lw" -> 4, "lh" -> 2, "lhu" -> 2, "lb" -> 1, "lbu" -> 1))
      s"$op $dest, ${clintOperand(width)}"
    }
    // a jump or a branch over the next instruction, or to the middle of it (which traps)
    def jump: String = {
      val offset = if (chance(40)) 6 else 8
      rng.nextInt(if (handlers) 4 else 5) match {
        case 0 => s"jal $destOrZero, $offset"
        case 1 => s"beq x0, x0, $offset"
        case 2 => s"bne x0, x0, $offset" // not taken
        case 3 => s"bge $dest, $dest, $offset"
        case _ => s"jalr $destOrZero, $dest, ${rng.nextInt(16)}"
      }
    }
    // an interrupt line raised by a store: the software interrupt, or the external one (SimCtrl MEIP)
    def raise: Seq[String] = Seq("li x19, 1", s"sw x19, 0(${pick(Seq("x28", "x29"))})")
    // a WFI that the timer wakes a few cycles later, unless something else is pending before
    def timedWfi: Seq[String] = Seq(
      "lw x17, 0(x31)",
      s"addi x17, x17, ${8 + rng.nextInt(40)}",
      "sw x17, 0(x30)",
      "sw x0, 4(x30)",
      "li x18, 0x80",
      "csrrs x0, mie, x18",
      "wfi"
    )
    def statement: Seq[String] = rng.nextInt(100) match {
      case n if n < 38 => Seq(csr)
      case n if n < 50 => Seq(s"li $dest, 0x${value.toHexString}")
      // what lets interrupts through: the global enable, the three enables, a timer that is due
      case n if n < 54 => Seq(pick(Seq("csrrsi x0, mstatus, 8", "csrrsi x0, mstatus, 8", "csrrci x0, mstatus, 8")))
      case n if n < 56 => Seq(s"csrrw x0, mie, ${pick(Seq("x1", "x2", "x3"))}")
      case n if n < 58 => Seq(pick(Seq("ecall", "ebreak")))
      case n if n < 62 => Seq(if (handlers) csr else "mret")
      case n if n < 65 => if (handlers) raise else Seq("mret")
      case n if n < 74 => Seq(store)
      case n if n < 76 => Seq(s"sw ${if (chance(50)) "x0" else dest}, 0(x28)") // MEIP: bit 0 of the value
      case n if n < 82 => Seq(load)
      case n if n < 87 => Seq(s"addi $dest, $dest, ${rng.nextInt(64) - 32}")
      case n if n < 92 => Seq(jump)
      case n if n < 94 => timedWfi
      case 94          => Seq("wfi")
      case _           => Seq("nop")
    }
    val preamble: Seq[String] = Seq(
      f"li x28, 0x${MemMap.simCtrlBase + MemMap.SimCtrl.Meip}%x",
      f"li x29, 0x${MemMap.clintBase + Clint.Msip}%x",
      f"li x30, 0x${MemMap.clintBase + Clint.MtimecmpLo}%x",
      f"li x31, 0x${MemMap.clintBase + Clint.MtimeLo}%x",
      "li x1, 0x888",
      "li x2, 0x80",
      "li x3, 0x808"
    )
    def body: Seq[String] = preamble ++ Seq.fill(items - preamble.size)(statement).flatten
  }

  /** Straight-line code of at least `items` instructions for `seed`: [[IssLockstep]] executes
    * every instruction once, in order, whatever `mtvec` and `mepc` hold.
    */
  def apply(seed: Long, items: Int): String =
    new Statements(new Random(seed), items, handlers = false).body.mkString("\n")

  /** A program of at least `items` random instructions that runs under its own trap handlers, for
    * `seed`. `mtvec` points at a vector table, in direct or in vectored mode (the program switches
    * now and then). An exception returns behind its instruction. Each interrupt handler silences
    * its source as software does: the software interrupt handler clears `msip` and opens a window
    * for nested interrupts, the timer handler disables `MTIE` or moves `mtimecmp` away first, and
    * the external interrupt handler clears SimCtrl's `MEIP`. The program ends at the label `end`.
    */
  def withHandlers(seed: Long, items: Int): String = {
    val rng        = new Random(seed)
    val statements = new Statements(rng, items, handlers = true)
    val timer      =
      if (rng.nextBoolean()) Seq("li x21, 0x80", "csrrc x0, mie, x21")
      else Seq("li x21, -1", "sw x21, 0(x30)", "sw x21, 4(x30)", "li x21, 0x80", "csrrc x0, mie, x21")
    def setMtvec(vectored: Boolean) =
      Seq("la x16, vectors", s"addi x16, x16, ${if (vectored) 1 else 0}", "csrw mtvec, x16")
    // the `nop` is what a jump over the next instruction, as the last statement of a group, skips:
    // without it the jump would skip the first half of `la`, and `mtvec` would get a stale x16
    val main    = statements.body.grouped(40).flatMap(_ ++ ("nop" +: setMtvec(rng.nextBoolean()))).toSeq
    val vectors = (0 until 12).map {
      case 3  => "j software"
      case 7  => "j timer"
      case 11 => "j external"
      case _  => "j exception"
    }
    (Seq("j main", ".balign 64", "vectors:") ++ vectors ++ Seq(
      // every exception, and in direct mode every interrupt too
      "exception:",
      "csrr x20, mcause",
      "bltz x20, dispatch",
      "csrr x20, mepc",
      "addi x20, x20, 4",
      "csrw mepc, x20",
      "mret",
      "dispatch:",
      "andi x20, x20, 15",
      "addi x21, x0, 3",
      "beq x20, x21, software",
      "addi x21, x0, 7",
      "beq x20, x21, timer",
      "j external",
      "software:",
      "sw x0, 0(x29)",
      "csrr x22, mepc",
      "csrr x23, mstatus",
      "li x21, 0x880",
      "csrrs x0, mie, x21",
      "csrsi mstatus, 8", // a timer or an external interrupt may nest from here
      "nop",
      "nop",
      "csrci mstatus, 8",
      "csrw mepc, x22",
      "csrw mstatus, x23",
      "mret",
      "timer:"
    ) ++ timer ++ Seq("mret", "external:", "sw x0, 0(x28)", "mret", "main:") ++ setMtvec(vectored = false) ++ main ++
      Seq("end:") ++ Seq.fill(4)("nop")).mkString("\n")
  }
}

/** What a lockstep run covered. */
class LockstepStats {
  var steps           = 0
  var csrInstructions = 0
  var csrWrites       = 0
  var csrIllegal      = 0
  var mrets           = 0
  var clintStores     = 0
  var clintLoads      = 0
  var vectored        = 0
  var nested          = 0 // traps taken while a handler runs (programs with handlers)
  var afterMret       = 0 // interrupts taken straight after an mret
  var afterEnable     = 0 // interrupts taken straight after a CSR write
  var collisions      = 0 // interrupts taken in a cycle that also presents an instruction or an exception
  var jumps           = 0 // instructions counted as a taken branch
  var wfiWaits        = 0 // WFIs that waited for the timer
  var wfiCycles       = 0
  var wfiSkipped      = 0 // WFIs that nothing would wake in time
  var hpmCounts       = 0 // increments of the programmable counters
  val pending: Array[Int] = new Array[Int](32) // steps in which each mip bit was set
  val traps: mutable.Map[Long, Int] = mutable.Map[Long, Int]().withDefaultValue(0) // by mcause
  val written: mutable.Set[Int]     = mutable.Set[Int]()                           // CSR addresses written
  val hpmEvents: mutable.Set[Int]   = mutable.Set[Int]()                           // events that a counter counted
  override def toString: String     = {
    val byCause = traps.toSeq.sortBy(_._1).map { case (cause, n) => f"0x$cause%x: $n" }.mkString(", ")
    s"$steps steps: $csrInstructions CSR instructions ($csrWrites write, $csrIllegal illegal, ${written.size} " +
      s"addresses written), $mrets mret, $clintStores CLINT stores, $clintLoads CLINT loads, traps by mcause " +
      s"{$byCause}, $vectored interrupts through a vector, $nested nested traps, $afterMret interrupts after an " +
      s"mret and $afterEnable after a CSR write, $collisions interrupts over a presented instruction, $jumps " +
      s"taken jumps, $wfiWaits WFI waits of $wfiCycles cycles ($wfiSkipped WFIs skipped), $hpmCounts hpm counts " +
      s"of events ${hpmEvents.toSeq.sorted.mkString(",")}, steps with MSIP/MTIP/MEIP pending " +
      s"${pending(3)}/${pending(7)}/${pending(11)}"
  }
}

/** The testbench's own model of `mhpmcounter3..10`, cycle by cycle (the golden model holds only
  * what a program writes to them): a CSR write to a half replaces it and the increment of the
  * cycle; otherwise the counter gains one if its event is raised and its inhibit bit, as it was
  * before the cycle, is clear.
  */
private class HpmReference(stats: LockstepStats) {
  private val counters = new Array[Long](8) // mhpmcounter3..10

  /** The index of the counter that CSR `addr` reads or writes, if it is one of the eight. */
  private def index(addr: Int): Option[Int] = Iss.counterOf(addr).filter(n => n >= 3 && n <= 10).map(_ - 3)

  /** The value a read of counter CSR `addr` gives, if it is one of the eight counters. */
  def read(addr: Int): Option[Long] =
    index(addr).map(i => (if (Iss.highHalf(addr)) counters(i) >>> 32 else counters(i)) & 0xffffffffL)

  /** One clock cycle that raises `raised`, with `mcountinhibit` and the eight `mhpmevent`s as they
    * were before it, and the CSR write of the cycle, if any.
    */
  def cycle(raised: Set[Int], inhibit: Long, selected: Seq[Int], write: Option[CsrWrite]): Unit = {
    val written = write.flatMap(w => index(w.addr))
    for (w <- write; i <- written) counters(i) = ModelDevice.withHalf(counters(i), Iss.highHalf(w.addr), w.wdata)
    for (i <- counters.indices if !written.contains(i) && raised(selected(i)) && (inhibit >> (i + 3) & 1) == 0) {
      counters(i) += 1
      stats.hpmCounts += 1
      stats.hpmEvents += selected(i)
    }
  }
}

/** The model-equivalence check of the T3 leaf units: the golden model (`mycpu.common.Iss`, with its
  * `ClintModel` and `SimCtrlModel`) runs a [[RandomCsrProgram]], and every one of its steps is
  * replayed on a [[CsrClintTop]] as a single-cycle core would present it: the CSR instruction on
  * the CSR port, the exception or the interrupt on the trap port, the `mret` on its port, a CLINT
  * access on the memory port, the level of SimCtrl's `MEIP` on the external interrupt line, and
  * the status of the cycle. A step is one clock cycle, but for a WFI that waits: its cycles pass
  * on the hardware one by one, with nothing presented, until the wake signal rises in the cycle
  * the model says.
  *
  * The model is used through its public API alone, so the reference is the very code that the
  * co-simulation of the cores uses (the model's choices where the ISA leaves room are in its class
  * comment), not a second transcription of T3's rules. One model step is one clock cycle, which
  * makes `mcycle` and the CLINT's `mtime` comparable too. The only values the model takes from
  * outside are those of `mhpmcounter3..10`, which it does not count: they come from the
  * testbench's own count ([[HpmReference]]) of the events that each cycle's status raises
  * ([[CycleFlags.events]]), and the hardware's counters are compared with that count.
  *
  * The leaf units do not own the pc, so the testbench does, in one of two ways:
  *   - '''in order''' ([[RandomCsrProgram.apply]]): after a trap, an `mret` or a jump, once the
  *     hardware's handler or return address has been compared with the model's, the model is sent
  *     on to the next instruction of the program (`Iss.load` of an empty image sets the pc and
  *     nothing else). Every instruction executes once, whatever `mtvec` and `mepc` hold. The
  *     external interrupt line also changes at random, by a source outside the model's devices;
  *   - '''with handlers''' ([[RandomCsrProgram.withHandlers]]): the pc is the model's. Traps enter
  *     real handlers, which silence their source, return with `mret`, nest, and are interrupted
  *     again straight after the `mret` or the write that enables an interrupt. The run ends at
  *     the program's end or after `6 * items` steps.
  *
  * Compared in every step: `mip` and the wake condition before the step; whether an interrupt is
  * requested and which (the model decides: no request may be pending when the model takes none);
  * the handler's pc; `illegal`, the value read, whether the instruction writes and what; the pc
  * of an `mret`; the data of a CLINT load. In a cycle with an interrupt, the instruction that the
  * interrupt precedes is presented too, or an exception, and in a cycle with any trap now and
  * then `retired`: the file must ignore them. After every step `mstatus` and three other CSRs are
  * read back (any address, also ones without a CSR), and every `sweep` steps all CSRs and the
  * CLINT's registers.
  */
object IssLockstep {
  private case class Mismatch(message: String) extends Exception(message)

  private def check(condition: Boolean, message: => String): Unit = if (!condition) throw Mismatch(message)

  /** The longest WFI wait that is replayed; a WFI that would wait longer is skipped. */
  private val maxWait = 200

  private val wfi  = AsmX.fixed("wfi")
  private val mret = AsmX.fixed("mret")

  /** The CSR instruction that `word` is, if it is one. */
  private def csrInstruction(word: Long) = AsmX.decodeExt(word).filter(i => AsmX.isCsr(i.name))

  /** Runs `count` programs (seeds 1 to `count`, `items` statements each; under their own handlers
    * if `handlers`) on `c` and returns the first difference, if any; `stats` is updated.
    */
  def runs(c: CsrClintTop, count: Int, items: Int, stats: LockstepStats, handlers: Boolean): Option[String] =
    (1 to count).iterator.map(seed => run(c, seed, items, stats, handlers)).collectFirst { case Some(m) => m }

  /** Runs the program of `seed` (`items` statements; under its own handlers if `handlers`) on `c`,
    * from reset, to its end. Returns the first difference, if any; `stats` is updated.
    */
  def run(
      c: CsrClintTop,
      seed: Long,
      items: Int,
      stats: LockstepStats,
      handlers: Boolean = false,
      sweep: Int = 64
  ): Option[String] = {
    val source  = if (handlers) RandomCsrProgram.withHandlers(seed, items) else RandomCsrProgram(seed, items)
    val program = AsmX.assemble(source)
    val base    = MemMap.ramBase
    val top     = base + 4L * program.words.size
    val end     = if (handlers) program.symbols("end") else top
    val clint   = new ClintModel()
    val hpm     = new HpmReference(stats)
    val sample  = new IssSample {
      def mip(modeled: Long): Long                                          = modeled
      def csrRead(addr: Int, modeled: Long, op: Char, operand: Long): Long  = hpm.read(addr).getOrElse(modeled)
      def load(addr: Long, bytes: Int, kind: ReadKind, modeled: Long): Long = modeled
      def wfiWait(enabled: Long, timer: Option[Long]): Long                 = IssSample.Model.wfiWait(enabled, timer)
    }
    val iss = new Iss(IssCsr.T3, base, (top - base).toInt, Seq(new UartModel(), new SimCtrlModel(), clint), sample)
      .load(MemImage(program))
    val rng   = new Random(seed ^ 0x5eed)
    val bench = CsrBench(c)
    val port  = c.io.clint

    def idle(): Unit = {
      bench.idle()
      CsrBench.idle(port)
    }
    def clintRead(offset: Int): BigInt = {
      port.req.valid.poke(true.B)
      port.req.bits.addr.poke((MemMap.clintBase + offset).U)
      val data = port.resp.bits.rdata.peek().litValue
      port.req.valid.poke(false.B)
      data
    }
    def inClint(addr: Long) = addr >= MemMap.clintBase && addr < MemMap.clintBase + MemMap.clintSize

    /** The external interrupt line: SimCtrl's `MEIP`, or a source outside the model's devices. */
    def driveMeip(): Unit = c.io.meip.poke(((iss.mip >> CsrFile.Mei & 1) != 0).B)

    /** The CSRs `addrs` of the hardware against the model's (the testbench's count for the hpm
      * counters), without a clock edge: each is read as by a `csrrs` with `x0`. The ports must be
      * idle.
      */
    def compareCsrs(addrs: Iterable[Int], when: => String): Unit = {
      c.io.csr.cmd.bits.op.poke(CsrOp.Rs)
      c.io.csr.cmd.valid.poke(true.B)
      for (addr <- addrs) {
        c.io.csr.cmd.bits.addr.poke(addr.U)
        val exists = iss.csrExists(addr)
        check(c.io.csr.illegal.peek().litToBoolean == !exists, f"$when: CSR 0x$addr%03x exists: model $exists")
        if (exists) {
          val rdata = c.io.csr.rdata.peek().litValue
          val model = hpm.read(addr).getOrElse(iss.csr(addr))
          check(rdata == model, f"$when: ${Csr.name(addr)} is 0x$rdata%08x, model 0x$model%08x")
        }
      }
      c.io.csr.cmd.valid.poke(false.B)
    }
    def compareAll(when: => String): Unit = {
      compareCsrs(CsrFile.implemented, when)
      val registers = Seq(
        "msip"        -> (Clint.Msip, if (clint.msip) 1L else 0L),
        "mtimecmp lo" -> (Clint.MtimecmpLo, clint.mtimecmp & 0xffffffffL),
        "mtimecmp hi" -> (Clint.MtimecmpHi, clint.mtimecmp >>> 32),
        "mtime lo"    -> (Clint.MtimeLo, clint.mtime & 0xffffffffL),
        "mtime hi"    -> (Clint.MtimeHi, clint.mtime >>> 32)
      )
      for ((name, (offset, model)) <- registers) {
        val value = clintRead(offset)
        check(value == model, f"$when: CLINT $name is 0x$value%08x, model 0x$model%08x")
      }
    }

    /** Presents the CSR instruction `i` with the register values `regs`. */
    def presentCsr(i: riscv.Inst, regs: IndexedSeq[Long]): CsrAnswer = {
      val op = i.name.take(5) match {
        case "csrrw" => CsrOp.Rw
        case "csrrs" => CsrOp.Rs
        case _       => CsrOp.Rc
      }
      bench.present(op, i.imm & 0xfff, i.rs1, rs1Data = regs(i.rs1), imm = i.name.endsWith("i"))
    }

    /** Presents the interrupt of step `r`, and with it what the file must ignore: the instruction
      * that the interrupt precedes, which does not execute, or an exception a core reports for it.
      */
    def presentInterrupt(r: IssRetire, regs: IndexedSeq[Long], what: => String): Unit = {
      val cause = c.io.irq.cause.peek().litValue
      check(cause == r.trap.get.code, s"$what\n  the hardware requests interrupt $cause")
      c.io.trap.interrupt.poke(true.B)
      c.io.trap.epc.poke(r.pc.U)
      c.io.trap.tval.poke((rng.nextLong() & 0xffffffffL).U)
      val word = if (r.pc >= base && r.pc < top) iss.read(r.pc, 4) else 0L
      csrInstruction(word) match {
        case Some(i) =>
          check(!presentCsr(i, regs).write, s"$what\n  a CSR instruction writes in the cycle of an interrupt")
          stats.collisions += 1
        case None if word == mret =>
          c.io.mret.valid.poke(true.B)
          stats.collisions += 1
        case None if rng.nextBoolean() =>
          c.io.trap.exception.poke(true.B)
          c.io.trap.cause.poke(Seq(0, 2, 3, 4, 5, 6, 7, 11)(rng.nextInt(8)).U)
          stats.collisions += 1
        case None =>
      }
    }

    /** Presents the instruction of step `r`: an exception, a CSR instruction, an `mret`, a CLINT
      * access.
      */
    def presentInstruction(r: IssRetire, regs: IndexedSeq[Long], what: => String): Unit = {
      for (t <- r.trap) {
        c.io.trap.exception.poke(true.B)
        c.io.trap.cause.poke(t.code.U)
        c.io.trap.epc.poke(r.pc.U)
        c.io.trap.tval.poke(t.tval.U)
      }
      for (i <- csrInstruction(r.inst)) {
        val answer = presentCsr(i, regs)
        stats.csrInstructions += 1
        check(answer.illegal == r.trap.isDefined, s"$what\n  illegal: hardware ${answer.illegal}")
        check(answer.write == r.csr.isDefined, s"$what\n  writes its CSR: hardware ${answer.write}")
        if (r.trap.isEmpty && i.rd != 0)
          check(answer.rdata == r.rdValue, f"$what\n  the hardware reads 0x${answer.rdata}%08x")
        for (w <- r.csr) {
          check(answer.wdata == w.wdata, f"$what\n  the hardware writes 0x${answer.wdata}%08x")
          stats.csrWrites += 1
          stats.written += w.addr
        }
        if (r.trap.isDefined) stats.csrIllegal += 1
      }
      if (r.inst == mret) {
        c.io.mret.valid.poke(true.B)
        val target = c.io.mret.pc.peek().litValue
        check(target == r.nextPc, f"$what\n  the hardware returns to 0x$target%08x")
        stats.mrets += 1
      }
      for (m <- r.mem if inClint(m.addr)) {
        val lane = (m.addr & 3).toInt
        port.req.valid.poke(true.B)
        port.req.bits.addr.poke((m.addr & ~3L).U)
        port.req.bits.wdata.poke(((m.data << (8 * lane)) & 0xffffffffL).U)
        port.req.bits.wstrb.poke((if (m.write) ((1 << m.bytes) - 1) << lane else 0).U)
        check(port.resp.valid.peek().litToBoolean, s"$what\n  the CLINT does not answer")
        if (m.write) stats.clintStores += 1
        else {
          val data = (port.resp.bits.rdata.peek().litValue >> (8 * lane)) & ((BigInt(1) << (8 * m.bytes)) - 1)
          check(data == m.data, f"$what\n  the CLINT reads 0x$data%x")
          stats.clintLoads += 1
        }
      }
    }

    try {
      compareAll("after reset")
      var step                 = 0
      var pc                   = base
      var last                 = Option.empty[IssRetire]
      var depth                = 0 // handlers entered and not left (programs with handlers)
      def running              = pc >= base && pc < end && (!handlers || step < 6 * items)
      def goOn(to: Long): Unit = {
        pc = to
        if (running && iss.pc != pc) iss.load(MemImage(pc, IndexedSeq.empty))
      }
      while (running) {
        // the messages are built only for a mismatch (a record prints with its disassembly), from
        // the step number of this iteration
        val at         = step
        lazy val where = s"seed $seed, step $at"
        // a source outside the model's devices; with handlers, only what a handler can silence
        if (!handlers && rng.nextInt(100) < 3) iss.mipHw ^= 1L << CsrFile.Mei
        val regs     = iss.regs
        val mip      = iss.mip
        val enabled  = iss.csr(Csr.Mie)
        val pending  = (mip & enabled) != 0
        val inhibit  = iss.csr(Csr.Mcountinhibit)
        val selected = (3 to 10).map(n => iss.csr(Csr.mhpmevent(n)).toInt)
        driveMeip() // that source may have changed
        check(c.io.mip.peek().litValue == mip, f"$where: mip is 0x${c.io.mip.peek().litValue}%x, model 0x$mip%x")
        check(c.io.irq.wake.peek().litToBoolean == pending, s"$where: wake, model $pending")
        for (bit <- Seq(CsrFile.Msi, CsrFile.Mti, CsrFile.Mei) if (mip >> bit & 1) != 0) stats.pending(bit) += 1

        // a WFI that has to wait: for the timer, if that is soon enough; else the program skips it
        val waits = iss.read(pc, 4) == wfi && !pending
        (if (waits) clint.wakeIn(enabled).filter(_ <= maxWait) else Some(0L)) match {
          case None =>
            stats.wfiSkipped += 1
            goOn(pc + 4)
          case Some(wait) =>
            val r         = iss.step()
            lazy val what = s"$where: $r"
            for (cycle <- 0L until wait) {
              check(!c.io.irq.wake.peek().litToBoolean, s"$what\n  the hardware wakes $cycle cycles into $wait")
              val flags = CycleFlags.stalls(rng)
              flags.poke(c.io.status)
              c.clock.step()
              hpm.cycle(flags.events, inhibit, selected, None)
            }
            if (wait > 0) {
              check(c.io.irq.wake.peek().litToBoolean, s"$what\n  the hardware does not wake after $wait cycles")
              stats.wfiWaits += 1
              stats.wfiCycles += wait.toInt
            }
            val requested = c.io.irq.valid.peek().litToBoolean
            val interrupt = r.trap.exists(_.interrupt)
            val jump      = (r.inst & 0x7f) match { // a jump, or a branch that is taken
              case 0x6f | 0x67 => true
              case 0x63        => r.trap.isDefined || r.nextPc != r.pc + 4
              case _           => false
            }
            // with a trap, `retired` must not matter; a jump that traps on its target is flagged
            // as a core might, and must not be counted
            val flags = CycleFlags
              .stalls(rng)
              .copy(
                retired = if (r.trap.isDefined) rng.nextInt(4) == 0 else r.retired,
                takenBranch = jump && !interrupt,
                trapTaken = r.trap.isDefined
              )
            if (interrupt) {
              check(requested, s"$what\n  the hardware requests no interrupt")
              presentInterrupt(r, regs, what)
              if (r.nextPc != (iss.csr(Csr.Mtvec) & ~3L)) stats.vectored += 1
              if (last.exists(_.inst == mret)) stats.afterMret += 1
              if (last.exists(_.csr.isDefined)) stats.afterEnable += 1
            } else {
              // a WFI that waited retires in the cycle it wakes, whether or not an interrupt follows
              val due = wait > 0 && (iss.csr(Csr.Mstatus) & 8) != 0
              check(requested == due, s"$what\n  the hardware requests interrupt ${c.io.irq.cause.peek().litValue}")
              presentInstruction(r, regs, what)
              if (r.inst == mret) depth = (depth - 1).max(0)
            }
            for (t <- r.trap) {
              val handler = c.io.trap.handler_pc.peek().litValue
              check(handler == r.nextPc, f"$what\n  the hardware's handler is at 0x$handler%08x")
              stats.traps(t.cause) += 1
              if (handlers && depth > 0) stats.nested += 1
              depth += 1
            }
            if (flags.events(HpmEvents.TakenBranch)) stats.jumps += 1
            flags.poke(c.io.status)
            c.clock.step()
            hpm.cycle(flags.events, inhibit, selected, r.csr)
            idle()
            driveMeip() // a store to SimCtrl's MEIP shows from the next cycle on
            step += 1
            stats.steps += 1
            last = Some(r)
            // with handlers the pc is the model's; else the program goes on in order: after the
            // instruction, or at the one an interrupt preceded
            goOn(if (handlers) iss.pc else if (interrupt) r.pc else r.pc + 4)

            lazy val after = s"$where, after $r"
            val others     = Seq.fill(3) {
              if (rng.nextBoolean()) CsrFile.implemented(rng.nextInt(CsrFile.implemented.size)) else rng.nextInt(4096)
            }
            compareCsrs(Csr.Mstatus +: others, after)
            if (step % sweep == 0) compareAll(after)
        }
      }
      // a program under its own handlers must not go astray: it ends at its end, or runs out of steps
      check(!handlers || pc >= end || step >= 6 * items, f"seed $seed: the program left its code, pc 0x$pc%08x")
      compareAll(s"seed $seed, at the end")
      None
    } catch {
      case Mismatch(message) =>
        idle()
        Some(message)
    }
  }
}
