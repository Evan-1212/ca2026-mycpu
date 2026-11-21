// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode.{decoder, TruthTable}
import mycpu.common.{Counters, Csr, Trap}

/** The three CSR operations. The values are the low two bits of funct3, which the register and
  * the immediate form of an operation share (Unprivileged spec, "Zicsr" extension).
  */
object CsrOp extends ChiselEnum {
  val Rw = Value(1.U) // csrrw, csrrwi: write the operand
  val Rs = Value(2.U) // csrrs, csrrsi: set the bits of the operand
  val Rc = Value(3.U) // csrrc, csrrci: clear the bits of the operand
}

/** A CSR instruction, as the core hands it to [[CsrFile]]:
  *   - `op`: the operation;
  *   - `imm`: it is an immediate form (`csrrwi`, `csrrsi`, `csrrci`);
  *   - `addr`: the CSR address, bits [31:20] of the instruction;
  *   - `rs1`: bits [19:15] of the instruction, the number of the source register or, in an
  *     immediate form, the 5-bit immediate itself;
  *   - `rs1_data`: the value of the source register (not used by an immediate form).
  * The destination register is not part of it: no CSR of this file has a read side effect, so a
  * `csrrw` with `rd` = `x0` differs from any other only in that the core discards `rdata`.
  */
class CsrCommand extends Bundle {
  val op       = CsrOp()
  val imm      = Bool()
  val addr     = UInt(12.W)
  val rs1      = UInt(5.W)
  val rs1_data = UInt(32.W)
}

/** The CSR instruction port of [[CsrFile]]. All outputs are combinational functions of `cmd` and
  * of the state, in the cycle of `cmd`; the state changes at the end of that cycle.
  *   - `rdata`: the value of the CSR before the instruction (0 for an address without a CSR);
  *   - `illegal`: the instruction must raise an illegal-instruction exception: there is no CSR at
  *     `addr`, or the instruction writes a CSR that is read-only by its address. It is low
  *     without `cmd.valid`. The file never writes for an illegal instruction, whether or not the
  *     core then reports the exception on the trap port;
  *   - `write`: the instruction writes its CSR in this cycle: it writes by the rules of Zicsr, it
  *     is not illegal, and no trap is taken in the cycle (the `csr` field of a retire record);
  *   - `wdata`: the value it writes, as the instruction computes it, before the CSR's own WARL
  *     rules.
  */
class CsrPort extends Bundle {
  val cmd     = Input(Valid(new CsrCommand))
  val rdata   = Output(UInt(32.W))
  val illegal = Output(Bool())
  val write   = Output(Bool())
  val wdata   = Output(UInt(32.W))
}

/** The trap port of [[CsrFile]]. In a cycle with `exception` or `interrupt` the file enters the
  * trap: `mepc`, `mcause`, `mtval` and `mstatus` change at the end of the cycle.
  *   - `exception`: the instruction at `epc` raises the exception with code `cause` (one of
  *     T3-02), and `tval` is what `mtval` gets (T3-02 lists it per cause);
  *   - `interrupt`: the interrupt that `irq` of the file requests in this cycle is taken before
  *     the instruction at `epc`, which has not executed. The file supplies the cause, and `mtval`
  *     becomes 0. It wins over `exception` in the same cycle;
  *   - `epc`: a word-aligned pc;
  *   - `handler_pc`: where execution continues, by `mtvec`. It is valid in every cycle, for the
  *     kind of trap that `interrupt` selects.
  */
class TrapPort extends Bundle {
  val exception  = Input(Bool())
  val interrupt  = Input(Bool())
  val cause      = Input(UInt(5.W))
  val epc        = Input(UInt(32.W))
  val tval       = Input(UInt(32.W))
  val handler_pc = Output(UInt(32.W))
}

/** The MRET port of [[CsrFile]]: in a cycle with `valid` an `mret` commits, `mstatus` changes at
  * the end of the cycle, and `pc` is where execution continues (`mepc`; valid in every cycle).
  */
class MretPort extends Bundle {
  val valid = Input(Bool())
  val pc    = Output(UInt(32.W))
}

/** The interrupt request of [[CsrFile]], from the state and the interrupt lines of this cycle:
  *   - `valid`: an interrupt is pending, enabled in `mie`, and `mstatus.MIE` is set: the core
  *     takes it at the next instruction boundary by raising `trap.interrupt`;
  *   - `cause`: its code (3 software, 7 timer, 11 external), the highest-priority one. Without
  *     `valid` it has no meaning;
  *   - `wake`: an interrupt is pending and enabled in `mie`, whatever `mstatus.MIE` is: a WFI
  *     ends (T3-02).
  */
class InterruptRequest extends Bundle {
  val valid = Output(Bool())
  val cause = Output(UInt(4.W))
  val wake  = Output(Bool())

  /** The `mcause` value of the requested interrupt: bit 31 set, `cause` in the low bits. The cores
    * put it in the retire record of the interrupt (`mycpu.common.Trap.interruptCause` is the same
    * value for the golden model).
    */
  def mcause: UInt = Cat(true.B, cause.pad(31))
}

/** The machine-mode CSR file (T3-01 to T3-04): the CSRs of an M-mode-only RV32IM hart with Zicsr,
  * Zicntr and Zihpm, trap entry and return, the interrupt decision and the counters. It is a leaf
  * unit shared by every core from T3 on; the core owns the pc, the decision when
  * to take a trap, and the retire record.
  *
  * '''Ports.''' `csr` ([[CsrPort]]), `trap` ([[TrapPort]]), `mret` ([[MretPort]]) and `irq`
  * ([[InterruptRequest]]); `msip`, `mtip` and `meip` are the interrupt lines (levels, from the
  * CLINT, and from SimCtrl and the UART); `time` is the CLINT's `mtime`; `retired` is high in a
  * cycle in which an instruction retires; `events` is the event vector of the cycle
  * ([[HpmEvents]], [[HpmClassifier]]); `mip` is the value of the `mip` CSR in this cycle, the one
  * the interrupt decision uses and a `csrr mip` reads (for the retire record).
  *
  * '''Contract with the core.''' Each of the following is presented for exactly one cycle, the one
  * in which the instruction commits:
  *   - a CSR instruction: `csr.cmd.valid`, together with `retired` unless `csr.illegal` is high;
  *   - an `mret`: `mret.valid`, together with `retired`;
  *   - a trap: `trap.exception` or `trap.interrupt`.
  * A trap wins: in a cycle with a trap, the file ignores `csr.cmd`, `mret` and `retired`, whatever
  * a core drives on them (no CSR instruction writes, `csr.write` is low, there is no return, and
  * `minstret` does not count: the instruction at `epc` did not retire). A single-cycle core
  * feeds `csr.illegal` back into `trap.exception` in the same cycle (`illegal` does not depend on
  * the trap port). A pipeline serializes CSR instructions, `mret` and `wfi` (T4-04) and presents
  * each in its commit stage, once every older instruction has committed: `csr.illegal` is an
  * exception of that stage, `csr.cmd.valid` is not held while the instruction waits, and what
  * this file reads and writes is the state in program order.
  *
  * '''CSRs''' ([[CsrFile.implemented]] lists the addresses; any other address is illegal):
  *   - `mvendorid`, `marchid`, `mimpid`, `mhartid`, `mconfigptr`: 0, read-only by address;
  *   - `misa`: RV32IM, writes ignored; `mstatush`: 0, writes ignored;
  *   - `mstatus`: `MIE` (bit 3) and `MPIE` (bit 7) are writable, `MPP` (bits 12:11) reads 3;
  *   - `mtvec`: `BASE` (bits 31:2) and `MODE` 0 (direct) or 1 (vectored); a write of another mode
  *     keeps the mode and still writes the base. Reset: `mtvecReset`;
  *   - `mie`, `mip`: bits 3 (software), 7 (timer) and 11 (external); `mip` follows the interrupt
  *     lines and ignores writes;
  *   - `mscratch`, `mtval`: 32 bits; `mepc`: bits [1:0] read 0;
  *   - `mcause`: the Interrupt bit and a 5-bit code. It is WARL: a CSR write of a value outside
  *     the set that Sail stores for such a hart (`mycpu.common.Trap.legalCause`; more causes than
  *     this hart raises) leaves it as it was;
  *   - `mcountinhibit`: bit 0 stops `mcycle`, bit 2 `minstret`, bits 3 to 10 `mhpmcounter3..10`;
  *     the others read 0 ([[CsrFile.inhibitMask]]);
  *   - `mcycle[h]`, `minstret[h]` and the programmable counters of `Counters`
  *     (`mhpmcounter3..10[h]`): 64-bit counters, each half writable;
  *     `mhpmevent3..10`: 4 bits, an event ID (0 and the IDs 9 to 15, which no event has, select
  *     no event); `mhpmcounter11..31[h]` and `mhpmevent11..31`: 0, writes ignored;
  *   - `cycle[h]`, `time[h]`, `instret[h]`, `hpmcounter3..31[h]`: the read-only user shadows of
  *     the counters, and `time` from the CLINT.
  * Every writable CSR resets to 0, except `mtvec`.
  *
  * '''Counters''' (T3-04). `mcycle` counts every clock cycle and `minstret` every cycle in which
  * an instruction retires. `mhpmcounter<n>` counts the cycles in which the event of
  * `mhpmevent<n>` is high in `events`. A counter whose `mcountinhibit` bit is set stands still;
  * the bit that decides is the one before the cycle, so the instruction that writes
  * `mcountinhibit` is still counted, or not, by the old value. A CSR write to either half of a
  * counter replaces the increment of that cycle: the instruction after a write of `v` reads `v`,
  * and an instruction that writes `minstret` or `minstreth` is not counted itself.
  *
  * @param mtvecReset
  *   the reset value of `mtvec` (T3-02: the RAM base, direct mode)
  */
class CsrFile(mtvecReset: Long = 0x80000000L) extends Module {
  require(mtvecReset >= 0 && mtvecReset < (1L << 32) && (mtvecReset & 2) == 0, "mtvec resets to mode 0 or 1")
  import CsrFile._

  val io = IO(new Bundle {
    val csr     = new CsrPort
    val trap    = new TrapPort
    val mret    = new MretPort
    val irq     = new InterruptRequest
    val msip    = Input(Bool())
    val mtip    = Input(Bool())
    val meip    = Input(Bool())
    val time    = Input(UInt(64.W))
    val retired = Input(Bool())
    val events  = Input(HpmEvents.vector)
    val mip     = Output(UInt(32.W))
  })

  // ---- state
  val mstatus_mie      = RegInit(false.B)
  val mstatus_mpie     = RegInit(false.B)
  val mtvec_base       = RegInit((mtvecReset >> 2).U(30.W))
  val mtvec_mode       = RegInit((mtvecReset & 3).U(2.W))
  val mie              = RegInit(0.U(32.W))
  val mscratch         = RegInit(0.U(32.W))
  val mepc             = RegInit(0.U(32.W))
  val mcause_interrupt = RegInit(false.B)  // mcause bit 31
  val mcause_code      = RegInit(0.U(5.W)) // mcause bits 4:0; no legal cause has another bit
  val mtval            = RegInit(0.U(32.W))
  val mcountinhibit    = RegInit(0.U(32.W))
  val mcycle           = RegInit(0.U(64.W))
  val minstret         = RegInit(0.U(64.W))
  // the programmable counters and their events (`Counters`: mhpmcounter3..10, mhpmevent3..10)
  // are elements 0 to programmable - 1
  val hpm_counters = RegInit(VecInit(Seq.fill(programmable)(0.U(64.W))))
  val hpm_events   = RegInit(VecInit(Seq.fill(programmable)(0.U(Counters.eventBits.W))))

  // The interrupt lines as mip shows them. MSIP is bit 3, MTIP bit 7 and MEIP bit 11.
  val mip = Wire(UInt(32.W))
  mip    := (io.meip.asUInt << Mei).pad(32) | (io.mtip.asUInt << Mti).pad(32) | (io.msip.asUInt << Msi).pad(32)
  io.mip := mip

  val mstatus = mstatusMpp.U(32.W) | (mstatus_mpie.asUInt << 7).pad(32) | (mstatus_mie.asUInt << 3).pad(32)
  val mtvec   = Cat(mtvec_base, mtvec_mode)
  val mcause  = Cat(mcause_interrupt, mcause_code.pad(31))

  // ---- the CSR instruction: what it reads
  val cmd  = io.csr.cmd.bits
  val addr = cmd.addr

  /** The 64-bit value of counter `n` (0 `mcycle`, 1 `time`, 2 `minstret`, 3..31 hpm), if it is
    * not hardwired to 0.
    */
  def counter(n: Int): Option[UInt] = n match {
    case 0                      => Some(mcycle)
    case 1                      => Some(io.time)
    case 2                      => Some(minstret)
    case n if Counters.isHpm(n) => Some(hpm_counters(n - firstHpm))
    case _                      => None
  }

  // the CSRs that do not read 0, by address
  val nonzero: Seq[(Int, UInt)] = Seq(
    Address.Misa          -> misa.U(32.W),
    Address.Mstatus       -> mstatus,
    Address.Mtvec         -> mtvec,
    Address.Mie           -> mie,
    Address.Mip           -> mip,
    Address.Mscratch      -> mscratch,
    Address.Mepc          -> mepc,
    Address.Mcause        -> mcause,
    Address.Mtval         -> mtval,
    Address.Mcountinhibit -> mcountinhibit
  ) ++
    (0 until programmable).map(i => Address.mhpmevent(firstHpm + i) -> hpm_events(i).pad(32)) ++
    (0 until 32).flatMap { n =>
      counter(n).toSeq.flatMap { value =>
        // the machine counter (there is no machine `time`) and its user shadow, both halves of each
        val bases = (if (n == 1) Nil else Seq(Address.Mcycle)) :+ Address.Cycle
        bases.flatMap(base => Seq((base + n) -> value(31, 0), (base + 0x80 + n) -> value(63, 32)))
      }
    }
  require(nonzero.forall { case (a, _) => implemented.contains(a) })

  // a minimized two-level decode of `implemented`, in place of one comparator for each address
  val exists =
    decoder.qmc(addr, TruthTable(implemented.map(a => BitPat(a.U(12.W)) -> BitPat.Y()), BitPat.N())).asBool
  val rdata = MuxLookup(addr, 0.U(32.W))(nonzero.map { case (a, value) => a.U -> value })
  io.csr.rdata := rdata

  // ---- the CSR instruction: what it writes
  val operand = Mux(cmd.imm, cmd.rs1.pad(32), cmd.rs1_data)
  // [CA26: Exercise 3.1] CSR instructions: what they write, whether they write, and which are illegal
  // Contract: `wdata` is the new value of the CSR as the instruction computes it from the old
  // value `rdata` and from `operand` (the source register, or the zero-extended immediate), for
  // each of the three operations of `CsrOp` (Unprivileged spec, "Zicsr" extension, the
  // descriptions of CSRRW, CSRRS and CSRRC; bootcamp 2.3 for `MuxLookup` and bitwise operators).
  val wdata = ?
  io.csr.wdata := wdata

  val trap_taken = io.trap.exception || io.trap.interrupt
  val writes     = Wire(Bool()) // the instruction writes its CSR, by the rules of Zicsr
  // [CA26: Exercise 3.1] CSR instructions: what they write, whether they write, and which are illegal
  // Contract: drive `writes`, `io.csr.illegal` and `io.csr.write` as the comments of `CsrPort`
  // and of this class define them (Unprivileged spec, "Zicsr" extension, the table of read and
  // write side effects, for the instructions that write). `exists` says whether there is a CSR
  // at `addr`; which addresses are read-only is a property of the address (Privileged spec §2.1,
  // "CSR Address Mapping Conventions"). A CSR whose fields are hardwired or WARL is not
  // read-only in this sense: writing it is legal (handout §T3.1).
  ?

  /** Whether the instruction writes the CSR at `a` in this cycle. */
  def writes_to(a: Int): Bool = io.csr.write && addr === a.U

  /** Whether bit `code` of `mask` is set: `code` is one of the codes that `mask` lists. */
  def listed(mask: Long, code: UInt): Bool = {
    require(mask >= 0 && mask < (1L << 32), f"0x$mask%x is not a mask of 32 codes")
    VecInit(Seq.tabulate(32)(i => ((mask >> i & 1) != 0).B))(code)
  }

  /** Whether a CSR write can store `value` in `mcause` (`Trap.legalCause`, in hardware). */
  def legal_cause(value: UInt): Bool = value(30, 5) === 0.U &&
    Mux(value(31), listed(Trap.legalInterrupts, value(4, 0)), listed(Trap.legalExceptions, value(4, 0)))

  // WARL rules of the plain registers (T3-01)
  when(writes_to(Address.Mstatus)) {
    mstatus_mie  := wdata(3)
    mstatus_mpie := wdata(7)
  }
  when(writes_to(Address.Mtvec)) {
    mtvec_base := wdata(31, 2)
    when(wdata(1, 0) < 2.U)(mtvec_mode := wdata(1, 0)) // a reserved mode keeps the previous one
  }
  when(writes_to(Address.Mie))(mie           := wdata & interruptMask.U(32.W))
  when(writes_to(Address.Mscratch))(mscratch := wdata)
  when(writes_to(Address.Mepc))(mepc         := Cat(wdata(31, 2), 0.U(2.W)))
  when(writes_to(Address.Mcause) && legal_cause(wdata)) { // any other value: mcause stays
    mcause_interrupt := wdata(31)
    mcause_code      := wdata(4, 0)
  }
  when(writes_to(Address.Mtval))(mtval                 := wdata)
  when(writes_to(Address.Mcountinhibit))(mcountinhibit := wdata & inhibitMask.U(32.W))
  for (i <- 0 until programmable)
    when(writes_to(Address.mhpmevent(firstHpm + i)))(hpm_events(i) := wdata(Counters.eventBits - 1, 0))

  // ---- counters (T3-04)
  // the pulse of each event ID that `mhpmevent` can hold; ID 0 and the IDs that no event has never pulse
  val event_pulses = VecInit((false.B +: io.events).padTo(1 << Counters.eventBits, false.B))
  // an instruction retires in this cycle: a trap wins over `retired`
  val instruction_retired = io.retired && !trap_taken

  // the counters that count, as (register, address of the low half, number); the number of a
  // counter is also its bit in mcountinhibit
  val counters: Seq[(UInt, Int, Int)] = Seq((mcycle, Address.Mcycle, 0), (minstret, Address.Minstret, 2)) ++
    (0 until programmable).map(i => (hpm_counters(i), Address.mhpmcounter(firstHpm + i), firstHpm + i))

  // [CA26: Exercise 3.5] Counting, inhibiting and writing the counters
  // Contract: at the end of every cycle, each counter of `counters` (`mcycle`, `minstret`, then
  // `mhpmcounter3..10`, selected by `hpm_events(0..7)`) takes a CSR write to one of its halves,
  // or counts, or stands still, as the "Counters" paragraph of the class comment defines.
  // `writes_to(address)` says that the instruction writes that CSR in this cycle, with `wdata`;
  // a high half is 0x80 above its low half; `event_pulses(id)` is the pulse of event `id`
  // (Privileged spec §3.1.10 and §3.1.12; handout §T3.4; bootcamp 2.4 for `Cat`).
  ?

  // ---- interrupts (T3-03)
  val irq_cause = Wire(UInt(4.W))
  // [CA26: Exercise 3.3] The interrupt request
  // Contract: drive `irq_cause`, `io.irq.valid` and `io.irq.wake` as `InterruptRequest` defines
  // them, from `mip`, `mie` and `mstatus_mie`. When several interrupts could be taken, the cause
  // is the one the specification ranks highest; when none can, the cause is not looked at
  // (Privileged spec §3.1.9, "Machine Interrupt Registers", for the condition and the priority
  // order; §3.1.6.1 for the global enable; §3.3.3 for what ends a WFI; handout §T3.3).
  ?
  io.irq.cause := irq_cause

  // ---- trap return and trap entry (T3-02)
  val mret_taken = io.mret.valid && !trap_taken
  io.mret.pc := mepc
  // [CA26: Exercise 3.2] Trap entry and return
  // Contract: an `mret` that commits (`mret_taken`) restores the interrupt enable that trap entry
  // saved and leaves the saved copy in the state the specification gives it. `MPP` is hardwired
  // in an M-mode-only hart and needs nothing (Privileged spec §3.1.6.1, "Privilege and Global
  // Interrupt-Enable Stack in mstatus register", and §3.3.2, "Trap-Return Instructions").
  ?

  // [CA26: Exercise 3.4] Where a trap goes
  // Contract: `io.trap.handler_pc` is the address of the handler by `mtvec` (`mtvec_base` is
  // BASE, `mtvec_mode` is MODE), for an interrupt with cause `irq_cause` if `io.trap.interrupt`
  // is high and for an exception otherwise, modulo 2^32. `io.trap.interrupt` comes late in the
  // cycle: it selects between addresses that do not depend on it (Privileged spec §3.1.7,
  // "Machine Trap-Vector Base-Address Register"; handout §T3.2; bootcamp 2.4 for `Cat`).
  io.trap.handler_pc := ?

  // a core's pc is always word-aligned (IALIGN is 32), it takes only an interrupt that is
  // requested, and it raises only the exceptions of T3-02
  assert(!trap_taken || io.trap.epc(1, 0) === 0.U, "trap.epc must be word-aligned")
  assert(!io.trap.interrupt || io.irq.valid, "trap.interrupt without an interrupt request")
  assert(
    !io.trap.exception || io.trap.interrupt || listed(Trap.raisedExceptions, io.trap.cause),
    "trap.cause is not an exception code of this hart"
  )

  // [CA26: Exercise 3.2] Trap entry and return
  // Contract: in a cycle with a trap (`trap_taken`), update `mepc`, `mcause` (its two registers:
  // `mcause_interrupt`, `mcause_code`), `mtval` and the two `mstatus` bits as the specification
  // prescribes for a trap taken into M-mode, from the trap port for an exception and with cause
  // `irq_cause` for an interrupt (`TrapPort` says which of the two it is when both inputs are
  // high, and what `mtval` gets). Nothing else writes these registers in such a cycle: a CSR
  // write and an `mret` are canceled above (Privileged spec §3.1.6.1 for `mstatus`, §3.1.14
  // `mepc`, §3.1.15 `mcause` and its Interrupt bit, §3.1.16 `mtval`; handout §T3.2).
  ?
}

object CsrFile {

  /** The CSR addresses (Privileged spec, Tables 2.2 to 2.6): those the assembler and the golden
    * model know by name.
    */
  val Address: Csr.type = Csr

  /** The interrupt codes, which are also the bit numbers in `mip` and `mie`. */
  val Msi: Int = Trap.Msi
  val Mti: Int = Trap.Mti
  val Mei: Int = Trap.Mei

  /** The bits of `mie` and `mip` that exist. */
  val interruptMask: Long = Trap.pendingMask

  /** The programmable counters: `mhpmcounter3` to `mhpmcounter10`. */
  val firstHpm: Int     = Counters.firstHpm
  val programmable: Int = Counters.programmable

  /** The bits of `mcountinhibit` that exist: CY, IR and HPM3 to HPM10 (bit 1 is hardwired 0). */
  val inhibitMask: Long = Counters.inhibitMask

  /** `misa`: MXL = 1 (32 bits), I and M. */
  val misa: Long = (1L << 30) | (1L << 8) | (1L << 12)

  /** The reset value of `mstatus`, and what it reads at least: MPP = 3. */
  val mstatusMpp: Long = 3L << 11

  /** Every CSR address this file implements, in ascending order. */
  val implemented: Seq[Int] = {
    import Address._
    val machine = Seq(Mvendorid, Marchid, Mimpid, Mhartid, Mconfigptr, Mstatus, Misa, Mie, Mtvec, Mstatush) ++
      Seq(Mcountinhibit, Mscratch, Mepc, Mcause, Mtval, Mip)
    val events   = (3 to 31).map(mhpmevent)
    val counters = (0 to 31).flatMap { n =>
      (if (n == 1) Nil else Seq(Mcycle + n, Mcycleh + n)) ++ Seq(Cycle + n, Cycleh + n)
    }
    (machine ++ events ++ counters).sorted
  }
}
