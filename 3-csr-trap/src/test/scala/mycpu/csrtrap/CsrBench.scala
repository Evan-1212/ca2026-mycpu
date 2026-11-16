// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import chisel3.simulator.PeekPokeAPI._

/** What a CSR instruction did, as the CSR port answered it. */
case class CsrAnswer(rdata: BigInt, illegal: Boolean, write: Boolean, wdata: BigInt)

/** A testbench's handle on a [[CsrFile]]: its ports, wherever they are (the file itself, or a top
  * that contains one), with the operations a core performs on it, one clock cycle each.
  *
  * Every operation leaves the instruction ports idle afterwards. The interrupt lines, `time` and
  * the events keep the values last poked.
  */
class CsrBench(
    val csr: CsrPort,
    val trap: TrapPort,
    val mret: MretPort,
    val irq: InterruptRequest,
    val mip: UInt,
    val retired: Bool,
    val clock: Clock,
    val reset: Reset
) {
  import CsrFile.Address

  /** No instruction and no trap in this cycle. */
  def idle(): Unit = {
    csr.cmd.valid.poke(false.B)
    csr.cmd.bits.op.poke(CsrOp.Rw)
    csr.cmd.bits.imm.poke(false.B)
    csr.cmd.bits.addr.poke(0.U)
    csr.cmd.bits.rs1.poke(0.U)
    csr.cmd.bits.rs1_data.poke(0.U)
    trap.exception.poke(false.B)
    trap.interrupt.poke(false.B)
    trap.cause.poke(0.U)
    trap.epc.poke(0.U)
    trap.tval.poke(0.U)
    mret.valid.poke(false.B)
    retired.poke(false.B)
  }

  /** Holds reset for two cycles with idle ports; the cycle after it is cycle 0. */
  def start(): Unit = {
    idle()
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
  }

  /** `n` cycles in which nothing happens. */
  def step(n: Int = 1): Unit = clock.step(n)

  /** Presents a CSR instruction and returns the port's answer, without a clock edge. */
  def present(op: CsrOp.Type, addr: Int, rs1: Int, rs1Data: BigInt = 0, imm: Boolean = false): CsrAnswer = {
    csr.cmd.valid.poke(true.B)
    csr.cmd.bits.op.poke(op)
    csr.cmd.bits.imm.poke(imm.B)
    csr.cmd.bits.addr.poke(addr.U)
    csr.cmd.bits.rs1.poke(rs1.U)
    csr.cmd.bits.rs1_data.poke(rs1Data.U)
    CsrAnswer(
      csr.rdata.peek().litValue,
      csr.illegal.peek().litToBoolean,
      csr.write.peek().litToBoolean,
      csr.wdata.peek().litValue
    )
  }

  /** Executes a CSR instruction in one cycle, as a core does: it retires unless it is illegal
    * (then nothing else happens: the caller takes the exception if it wants one).
    */
  def execute(op: CsrOp.Type, addr: Int, rs1: Int, rs1Data: BigInt = 0, imm: Boolean = false): CsrAnswer = {
    val answer = present(op, addr, rs1, rs1Data, imm)
    retired.poke((!answer.illegal).B)
    clock.step()
    idle()
    answer
  }

  /** `csrrw x0, addr, x5` with `value` in x5: one cycle. */
  def write(addr: Int, value: BigInt): CsrAnswer = execute(CsrOp.Rw, addr, rs1 = 5, rs1Data = value)

  /** `csrrs`/`csrrc x0, addr, x5` with `bits` in x5: one cycle. */
  def set(addr: Int, bits: BigInt): CsrAnswer   = execute(CsrOp.Rs, addr, rs1 = 5, rs1Data = bits)
  def clear(addr: Int, bits: BigInt): CsrAnswer = execute(CsrOp.Rc, addr, rs1 = 5, rs1Data = bits)

  /** `csrr x5, addr`: one cycle; the value read. It must not be illegal. */
  def read(addr: Int): BigInt = {
    val answer = execute(CsrOp.Rs, addr, rs1 = 0)
    require(!answer.illegal, f"a read of CSR 0x$addr%03x is illegal")
    answer.rdata
  }

  /** The value of CSR `addr` now, without a clock edge (the read of a `csrr` that is not executed). */
  def peek(addr: Int): BigInt = {
    val answer = present(CsrOp.Rs, addr, rs1 = 0)
    idle()
    require(!answer.illegal, f"a read of CSR 0x$addr%03x is illegal")
    answer.rdata
  }

  /** Takes an exception in one cycle; returns the handler's pc. */
  def exception(cause: Int, epc: BigInt, tval: BigInt): BigInt = {
    trap.exception.poke(true.B)
    trap.cause.poke(cause.U)
    trap.epc.poke(epc.U)
    trap.tval.poke(tval.U)
    val handler = trap.handler_pc.peek().litValue
    clock.step()
    idle()
    handler
  }

  /** Takes the requested interrupt before the instruction at `epc`, in one cycle; returns the
    * handler's pc. `tval` is what the core happens to drive, which must not matter.
    */
  def interrupt(epc: BigInt, tval: BigInt = 0): BigInt = {
    trap.interrupt.poke(true.B)
    trap.epc.poke(epc.U)
    trap.tval.poke(tval.U)
    val handler = trap.handler_pc.peek().litValue
    clock.step()
    idle()
    handler
  }

  /** Executes an `mret` in one cycle; returns the pc it continues at. */
  def mretTo(): BigInt = {
    mret.valid.poke(true.B)
    retired.poke(true.B)
    val pc = mret.pc.peek().litValue
    clock.step()
    idle()
    pc
  }

  /** `n` cycles, each with an instruction that retires and does nothing to the CSRs. */
  def retire(n: Int = 1): Unit = {
    retired.poke(true.B)
    clock.step(n)
    retired.poke(false.B)
  }

  /** `mstatus` bits. */
  def mie: Boolean  = peek(Address.Mstatus).testBit(3)
  def mpie: Boolean = peek(Address.Mstatus).testBit(7)
}

/** The status of one clock cycle as a testbench drives it ([[CycleStatus]]), and the events that
  * such a cycle raises by the rule of T3-04. The rule is written out here, with the event IDs of
  * `docs/hpm-events.md`, and not derived from [[HpmEvents]], so that a change there shows.
  */
case class CycleFlags(
    retired: Boolean = false,
    dataWait: Boolean = false,
    instructionWait: Boolean = false,
    dividerBusy: Boolean = false,
    loadUseStall: Boolean = false,
    flushBubble: Boolean = false,
    takenBranch: Boolean = false,
    trapTaken: Boolean = false
) {
  def poke(s: CycleStatus): Unit = {
    s.retired.poke(retired.B)
    s.data_wait.poke(dataWait.B)
    s.instruction_wait.poke(instructionWait.B)
    s.divider_busy.poke(dividerBusy.B)
    s.load_use_stall.poke(loadUseStall.B)
    s.flush_bubble.poke(flushBubble.B)
    s.taken_branch.poke(takenBranch.B)
    s.trap_taken.poke(trapTaken.B)
  }

  /** Whether an instruction retires: a trap wins over `retired`. */
  def retires: Boolean = retired && !trapTaken

  /** The IDs of the events that the cycle raises: one event of the partition unless an instruction
    * retires (D-side wait 7, then I-side wait 6, divider busy 1, load-use stall 4, flush bubble 5,
    * and other 8), a taken branch (2) only with an instruction that retires, and a trap taken (3).
    */
  def events: Set[Int] = {
    import CycleFlags.ev
    val partition =
      if (retires) None
      else if (dataWait) Some(ev(7))
      else if (instructionWait) Some(ev(6))
      else if (dividerBusy) Some(ev(1))
      else if (loadUseStall) Some(ev(4))
      else if (flushBubble) Some(ev(5))
      else Some(ev(8))
    partition.toSet ++ Option.when(takenBranch && retires)(ev(2)) ++ Option.when(trapTaken)(ev(3))
  }
}

object CycleFlags {

  /** The ID of the event that D12 numbers `n` (docs/hpm-events.md), in the student's configuration
    * (EX-08): `n` itself without one. Taken from the file, not from the design.
    */
  def ev(n: Int): Int =
    mycpu.common.StudentConfig.hpmEvents(mycpu.common.StudentConfig.hpmDefault.collectFirst { case (k, `n`) => k }.get)

  /** The waits and stalls a core might report in a cycle, each with probability 1/4, and nothing
    * else.
    */
  def stalls(rng: scala.util.Random): CycleFlags = fromBits(random(rng) & 0x3e)

  /** Eight flags, each set with probability 1/4, as bits. */
  def random(rng: scala.util.Random): Int = (0 until 8).map(i => if (rng.nextInt(4) == 0) 1 << i else 0).sum

  /** The IDs of the events that classifier `c` raises for `flags`. */
  def raised(c: HpmClassifier, flags: CycleFlags): Set[Int] = {
    flags.poke(c.io.status)
    (1 to 8).filter(id => c.io.events(id - 1).peek().litToBoolean).toSet
  }

  /** The status whose eight flags are the bits of `bits`, `retired` first. */
  def fromBits(bits: Int): CycleFlags = {
    def bit(n: Int) = (bits >> n & 1) != 0
    CycleFlags(bit(0), bit(1), bit(2), bit(3), bit(4), bit(5), bit(6), bit(7))
  }
}

object CsrBench {

  /** The bench of a [[CsrFile]], with its interrupt lines low, `time` 0 and no events. */
  def apply(c: CsrFile): CsrBench = {
    c.io.msip.poke(false.B)
    c.io.mtip.poke(false.B)
    c.io.meip.poke(false.B)
    c.io.time.poke(0.U)
    c.io.events.foreach(_.poke(false.B))
    val bench = new CsrBench(c.io.csr, c.io.trap, c.io.mret, c.io.irq, c.io.mip, c.io.retired, c.clock, c.reset)
    bench.start()
    bench
  }

  /** The bench of the [[CsrFile]] of a [[CsrClintTop]], with the external interrupt line low,
    * no request on the CLINT's port and an idle cycle status.
    */
  def apply(c: CsrClintTop): CsrBench = {
    c.io.meip.poke(false.B)
    idle(c.io.clint)
    CycleFlags().poke(c.io.status)
    val bench =
      new CsrBench(c.io.csr, c.io.trap, c.io.mret, c.io.irq, c.io.mip, c.io.status.retired, c.clock, c.reset)
    bench.start()
    bench
  }

  /** No request on a memory port. */
  def idle(port: riscv.MemPort): Unit = {
    port.req.valid.poke(false.B)
    port.req.bits.addr.poke(0.U)
    port.req.bits.wdata.poke(0.U)
    port.req.bits.wstrb.poke(0.U)
  }

  /** Drives the three interrupt lines of `c`. */
  def lines(c: CsrFile, msip: Boolean, mtip: Boolean, meip: Boolean): Unit = {
    c.io.msip.poke(msip.B)
    c.io.mtip.poke(mtip.B)
    c.io.meip.poke(meip.B)
  }

  val mask32: BigInt = (BigInt(1) << 32) - 1
}
