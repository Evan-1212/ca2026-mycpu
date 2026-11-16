// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec
import riscv.{Asm, Halt, MemAccess}

/** `CoSim2.replay`, the comparison itself, on record streams without hardware: the streams of a
  * perfect core (what the model alone does, one record per cycle) agree, and each stream with one
  * thing wrong is a mismatch.
  */
class CoSim2ReplaySpec extends AnyFlatSpec {
  private val (sim, clint, uart) = (MemMap.simCtrlBase, MemMap.clintBase, MemMap.uartBase)
  private val wfi                = AsmX.fixed("wfi")
  private val model              = T3.defaultModel

  /** What a perfect core shows running `source` (see `ideal`), with its image and symbols. */
  private case class Run(image: MemImage, cycles: Seq[CoSimCycle], symbols: Map[String, Long]) {

    /** The index of the `n`-th cycle (from 0) whose record satisfies `p`. */
    def index(n: Int = 0)(p: IssRetire => Boolean): Int = {
      val found = cycles.indices.filter(i => cycles(i).retire.exists(p))
      assert(found.size > n, s"the program has ${found.size} such records")
      found(n)
    }

    /** The stream with cycle `i` replaced by `f` of it. */
    def change(i: Int)(f: CoSimCycle => CoSimCycle): Seq[CoSimCycle] = cycles.updated(i, f(cycles(i)))

    /** As `change`, on the record of cycle `i`. */
    def record(i: Int)(f: IssRetire => IssRetire): Seq[CoSimCycle] = change(i)(c => c.copy(retire = c.retire.map(f)))

    def replay(cycles: Seq[CoSimCycle] = cycles, rules: CoSimRules = CoSimRules()): CoSimResult =
      CoSim2.replay(cycles.iterator, image, model, rules = rules)

    /** The mismatch of the doctored stream `cycles`, which must contain every one of `texts`. */
    def mismatch(cycles: Seq[CoSimCycle], rules: CoSimRules = CoSimRules())(texts: String*): String = {
      val report = replay(cycles, rules).mismatch.getOrElse(fail("no mismatch"))
      for (t <- texts) assert(report.contains(t), report)
      report
    }
  }

  /** The stream of a perfect core: each record the model alone retires, in a cycle of its own, with
    * `mip` as it was before the instruction and the `EXIT` event in the cycle of its store. A WFI
    * that waits retires after as many idle cycles as the model skipped.
    */
  private def ideal(source: String): Run = {
    val program = AsmX.assemble(source)
    val image   = MemImage(program)
    val iss     = Iss(image, IssCsr.T3, devices = IssCsr.devices())
    val cycles  = scala.collection.mutable.ArrayBuffer[CoSimCycle]()
    while (!iss.done && cycles.size < 10000) {
      val (mip, time) = (iss.mip, iss.mtime)
      val r           = iss.step()
      cycles ++= Seq.fill((iss.mtime - time - 1).toInt)(CoSimCycle())
      cycles += CoSimCycle(Some(r), mip, exit = iss.exit)
    }
    assert(iss.exit.contains(0), "the program must exit")
    Run(image, cycles.toSeq, program.symbols)
  }

  // an interrupt turns its own enable off and returns
  private val handler  = """
    handler:
      csrr  s1, mcause
      li    t0, 1
      sll   t0, t0, s1
      csrc  mie, t0
      mret
  """
  private def prologue = f"""
      la    t0, handler
      csrw  mtvec, t0
      li    s6, 0x$sim%x
      li    s7, 0x$clint%x
      li    s8, 0x$uart%x
      li    s9, 0x${clint + MemMap.Clint.MtimecmpLo}%x
      li    s10, 0x${clint + MemMap.Clint.MtimeLo}%x
  """
  private val exit = s"      sw    zero, ${MemMap.SimCtrl.Exit}(s6)\n"

  // every sampled value and every modeled device register, a WFI that waits, and three interrupts
  private lazy val tour = ideal(prologue + f"""
    first:
      csrr  a0, mcycle
      csrr  a1, mcycleh
      csrr  a2, time
      csrr  a3, mhpmcounter3
      lw    a4, ${MemMap.SimCtrl.CycleLo}(s6)
      lw    a5, ${MemMap.SimCtrl.CycleHi}(s6)
      lw    a6, ${MemMap.SimCtrl.InstretLo}(s6)
      lw    a7, 0(s10)               # mtime
      lw    t3, ${MemMap.Uart.Status}(s8)
      lw    t4, ${MemMap.Uart.RxData}(s8)
      csrrs x0, mcycle, t3           # the old value does not show
      csrw  mcycle, zero
    second:
      csrr  t4, mcycle
      csrr  t4, cycle
      sw    t3, 4(s10)               # mtime = 2^32 and a bit
      csrr  t5, timeh
      csrr  t5, time
      li    t1, 0x888
      csrw  mie, t1
      csrr  t5, mip
      lw    t1, 0(s10)
      addi  t1, t1, 40
      sw    t1, 0(s9)
      sw    t3, 4(s9)                # mtimecmp = mtime + 40
      csrsi mstatus, 8
    sleep:
      wfi                            # waits for the timer; MTI is taken after it
      li    t1, 1
      sw    t1, 0(s7)                # msip: MSI
      nop
      sw    t1, ${MemMap.SimCtrl.Meip}(s6)
      nop                            # MEI
      csrr  a0, minstret
      lw    a6, ${MemMap.SimCtrl.InstretLo}(s6)
      csrr  a2, time
      csrr  a0, mcycle
      lw    a4, ${MemMap.SimCtrl.CycleLo}(s6)
      lw    a7, 0(s10)
  """ + exit + handler)

  // MEIP, MSIP and MTIP all pending when mstatus.MIE is set
  private lazy val three = ideal(prologue + f"""
      li    t1, 1
      sw    t1, 0(s7)
      sw    t1, ${MemMap.SimCtrl.Meip}(s6)
      sw    zero, 0(s9)
      sw    zero, 4(s9)              # mtimecmp = 0
      li    t1, 0x888
      csrw  mie, t1
    enable:
      csrsi mstatus, 8
    here:
      nop                            # MEI, MSI and MTI are taken here, in that order
      nop
  """ + exit + handler)

  /** A load record with `v` as the data it read. */
  private def data(v: Long)(r: IssRetire) = r.copy(rdValue = v, mem = r.mem.map(_.copy(data = v)))

  private def interrupts(run: Run) = run.cycles.flatMap(_.retire).flatMap(_.trap).filter(_.interrupt).map(_.code)

  "CoSim2.replay" should "find a perfect core in agreement, sampled values and interrupts included" in {
    for (run <- Seq(tour, three)) {
      val result = run.replay()
      assert(result.mismatch.isEmpty, result.mismatch)
      assert(result.exit.contains(0) && result.cycles == run.cycles.size)
      assert(result.trace == run.cycles.flatMap(_.retire))
    }
    assert(interrupts(tour) == Seq(Trap.Mti, Trap.Msi, Trap.Mei))
    assert(interrupts(three) == Seq(Trap.Mei, Trap.Msi, Trap.Mti))
    val sleep = tour.index()(_.inst == wfi)
    assert(tour.cycles(sleep - 1).retire.isEmpty && tour.cycles(sleep).mip == 0, "the WFI waited")
    assert(tour.cycles(sleep + 1).mip == 0x80 && tour.cycles(sleep + 1).retire.get.trap.exists(_.interrupt), "for MTIP")
    // an EXIT event that shows two cycles before its store retires, as a pipeline's would
    val last  = tour.cycles.size - 1
    val early = tour.change(last)(_.copy(exit = None)).updated(last - 2, tour.cycles(last - 2).copy(exit = Some(0)))
    assert(tour.replay(early).mismatch.isEmpty)
  }

  it should "find an interrupt that is not pending, not enabled or not the first by priority" in {
    val first = three.index()(_.trap.exists(_.interrupt))
    assert(three.cycles(first).mip == 0x888 && three.cycles(first).retire.get.pc == three.symbols("here"))
    // not pending: the model executes the instruction there instead
    three.mismatch(three.change(first)(_.copy(mip = 0)))(
      "(interrupt)",
      "addi zero, zero, 0",
      "trap.valid: core true, model false",
      "mstatus 0x1808, mie 0x888 and (sampled) mip 0x000"
    )
    // pending, but only one that is not enabled: MEIP when the first handler has turned MEIE off
    val second = three.index(1)(_.trap.exists(_.interrupt))
    three.mismatch(three.change(second)(_.copy(mip = 0x800)))("trap.valid: core true, model false", "mie 0x088")
    three.mismatch(three.record(second)(_.copy(trap = Some(Trap(Trap.interruptCause(Trap.Mei), 0)))))(
      "trap.cause: core 0x8000000b, model 0x80000003"
    )
    // enabled and pending, but mstatus.MIE is off: an interrupt in place of the csrsi that sets it
    val enable = three.index()(_.pc == three.symbols("enable"))
    val irq    = three.cycles(first).retire.get.copy(pc = three.symbols("enable"))
    three.mismatch(three.change(enable)(_.copy(retire = Some(irq), mip = 0x888)))(
      "csrrsi zero, mstatus, 8",
      "mstatus 0x1800, mie 0x888 and (sampled) mip 0x888"
    )
    // the priority is MEI > MSI > MTI
    three.mismatch(three.record(first)(_.copy(trap = Some(Trap(Trap.interruptCause(Trap.Mti), 0)))))(
      "trap.cause: core 0x80000007, model 0x8000000b"
    )
    // and an interrupt that is due must be taken: the core executes the instruction instead
    val nop = three.cycles.flatMap(_.retire).find(r => r.pc == three.symbols("here") && r.trap.isEmpty).get
    three.mismatch(three.record(first)(_ => nop))("trap.valid: core false, model true")
  }

  it should "find a counter write through rd = x0 whose old value the counter cannot have had" in {
    val set = tour.index()(_.inst == AsmX("csrrs x0, mcycle, t3").head) // t3 = 1; mcycle is the cycle number
    val r   = tour.cycles(set).retire.get
    assert(r.rd == 0 && r.csr.contains(CsrWrite(Csr.Mcycle, set | 1)))
    // the bits outside the operand are those of the old value: 0x1000 too many
    tour.mismatch(tour.record(set)(_.copy(csr = Some(CsrWrite(Csr.Mcycle, 0x1001)))))(
      "mcycle: the low half reads 0x00001000 in its bits 0xfffffffe"
    )
    // the bits under the operand must be set, whatever the old value was
    tour.mismatch(tour.record(set)(_.copy(csr = Some(CsrWrite(Csr.Mcycle, set & ~1)))))("csr.wdata: core")
  }

  it should "find a wrong SimCtrl INSTRET, which the model counts" in {
    val load = tour.index()(_.mem.exists(_.addr == sim + MemMap.SimCtrl.InstretLo))
    val r    = tour.cycles(load).retire.get
    assert(r.rdValue == load, "one record per cycle so far, none of them a trap")
    tour.mismatch(tour.record(load)(_.copy(rdValue = 0xdeadbeefL, mem = r.mem.map(_.copy(data = 0xdeadbeefL)))))(
      f"rdValue: core 0xdeadbeef, model 0x$load%08x",
      f"mem.data: core 0xdeadbeef, model 0x$load%08x"
    )
    // minstret is the model's too
    val csr = tour.index()(_.inst == AsmX("csrr a0, minstret").head)
    tour.mismatch(tour.record(csr)(r => r.copy(rdValue = r.rdValue + 1)))("rdValue: core")
  }

  it should "find a counter that runs backward or faster than the clock" in {
    def rdValue(v: Long)(r: IssRetire) = r.copy(rdValue = v)
    // csrr a0, mcycle retires in cycle first + 1, and mcycle is at most the number of cycles since reset
    val first = tour.index()(_.pc == tour.symbols("first"))
    tour.mismatch(tour.record(first)(rdValue(first + 2)))(
      "mcycle: the low half reads",
      "nor gain more than one per clock cycle"
    )
    assert(tour.replay(tour.record(first)(rdValue(first - 3))).mismatch.isEmpty, "a slower clock is no error")
    tour.mismatch(tour.record(first + 1)(rdValue(1)))("mcycle: the high half reads 0x00000001")
    tour.mismatch(tour.record(first + 2)(rdValue(first + 9)))("time: the low half reads")
    tour.mismatch(tour.record(first + 3)(rdValue(first + 9)))("mhpmcounter3: the low half reads")
    tour.mismatch(tour.record(first + 4)(data(first + 9)))("SimCtrl CYCLE: the low half reads")
    tour.mismatch(tour.record(first + 5)(data(1)))("SimCtrl CYCLE: the high half reads 0x00000001")
    tour.mismatch(tour.record(first + 7)(data(first + 20)))("time: the low half reads") // the CLINT's mtime
    // after csrw mcycle, zero: the next reads are 0 or 1, then 1 or 2
    val second = tour.index()(_.pc == tour.symbols("second"))
    assert(tour.cycles(second).retire.get.rdValue == 0 && tour.cycles(second + 1).retire.get.rdValue == 1)
    tour.mismatch(tour.record(second)(rdValue(3)))("mcycle: the low half reads 0x00000003")
    // backward: the first read is 2, so the second (through the user shadow, the same counter) is below it
    tour.mismatch(tour.record(second)(rdValue(2)))(
      "mcycle: the low half reads 0x00000001",
      "between 0x2 and",
      "backward"
    )
    // mtime after its high half was stored: the high half holds, the low half goes on
    assert(tour.cycles(second + 3).retire.get.rdValue == 1)
    tour.mismatch(tour.record(second + 3)(rdValue(0)))("time: the high half reads 0x00000000")
    tour.mismatch(tour.record(second + 3)(rdValue(2)))("time: the high half reads 0x00000002")
    // the last reads of the program, after its wait
    val last = tour.cycles.size - 1
    tour.mismatch(tour.record(last - 2)(data(5)))("SimCtrl CYCLE: the low half reads 0x00000005")
    tour.mismatch(tour.record(last - 3)(rdValue(0)))("mcycle: the low half reads 0x00000000")
  }

  it should "sample inputs freely, and compare the registers a program determines" in {
    val status = tour.index()(_.mem.exists(_.addr == uart + MemMap.Uart.Status))
    // RXDATA is read into a register the program does not use again: any byte agrees
    assert(tour.replay(tour.record(status + 1)(data(0x5a))).mismatch.isEmpty)
    tour.mismatch(tour.record(status + 1)(data(0x15a)))("mem.data: core 0x0000015a, model 0x0000005a")
    tour.mismatch(tour.record(status)(data(9)))("mem.data: core 0x00000009, model 0x00000001")
    // STATUS goes on into mcycle and mtime: the next record differs, not the load
    val report = tour.mismatch(tour.record(status)(data(7)))("csrrs zero, mcycle, t3")
    assert(report.startsWith(s"instruction ${status + 2} "), report)
    // mip is read as sampled
    val mip = tour.index()(_.inst == AsmX("csrr t5, mip").head)
    tour.mismatch(tour.record(mip)(_.copy(rdValue = 0x80)))("rdValue: core 0x00000080, model 0x00000000")
    val mtip = tour.change(mip)(c => c.copy(mip = 0x80, retire = c.retire.map(_.copy(rdValue = 0x80))))
    assert(tour.replay(mtip).mismatch.isEmpty)
    // MSIP is what the program stored to msip, and MEIP is set while SimCtrl MEIP is: a core
    // whose wires are stuck, and which takes no interrupt for it, is found where mip is used
    three.mismatch(three.cycles.map(c => c.copy(mip = c.mip & ~0x8L)))("MSIP is not bit 0 of the CLINT's msip")
    val ext  = ideal(prologue + s"li t1, 1; sw t1, ${MemMap.SimCtrl.Meip}(s6); csrr a0, mip\n" + exit + handler)
    val read = ext.index()(_.inst == AsmX("csrr a0, mip").head)
    assert(ext.cycles(read).retire.get.rdValue == 0x800 && ext.replay().mismatch.isEmpty)
    ext.mismatch(ext.change(read)(c => c.copy(mip = 0, retire = c.retire.map(_.copy(rdValue = 0)))))(
      "mip 0x000: MEIP is clear although SimCtrl MEIP is set"
    )
    val stuck = tour.change(mip)(c => c.copy(mip = 0x8, retire = c.retire.map(_.copy(rdValue = 0x8))))
    tour.mismatch(stuck)("mip 0x008: MSIP is not bit 0 of the CLINT's msip, which the program left 0")
    // MEIP may be set by another device (the UART of T5) while SimCtrl MEIP is clear: the model
    // then takes MEI before the WFI, which is the difference found, not the MEIP itself
    val sleep = tour.index()(_.pc == tour.symbols("sleep"))
    tour.mismatch(tour.change(sleep)(_.copy(mip = 0x800)))("trap.valid: core false, model true", "mcause 0x8000000b")
    // a modeled device register: SimCtrl MEIP reads back bit 0 of what was stored
    val run = ideal(
      prologue + s"li t1, 3; sw t1, ${MemMap.SimCtrl.Meip}(s6); lw a0, ${MemMap.SimCtrl.Meip}(s6)\n" + exit + handler
    )
    val meip = run.index()(_.mem.exists(m => !m.write && m.addr == sim + MemMap.SimCtrl.Meip))
    assert(run.replay().mismatch.isEmpty && run.cycles(meip).retire.get.rdValue == 1)
    run.mismatch(run.record(meip)(data(3)))("mem.data: core 0x00000003, model 0x00000001")
  }

  it should "find a WFI that ends its wait with nothing pending and enabled" in {
    // the WFI of the tour waits with mstatus.MIE set: the interrupt record after it shows what woke it
    val sleep  = tour.index()(_.inst == wfi)
    val record = tour.cycles.take(sleep + 1).count(_.retire.isDefined)
    tour.mismatch(tour.change(sleep + 1)(_.copy(mip = 0)))(
      s"instruction $record (cycle ${sleep + 2}): the WFI before this record retired, but none of the interrupts",
      "(mie 0x888) is pending (mip 0x000)",
      "(interrupt)"
    )
    // pending but not enabled does not wake
    val msi  = "li t1, 1; sw t1, 0(s7)\n" // MSIP, which mie does not enable
    val mtie = "li t1, 0x80; csrw mie, t1\n"
    val run  = ideal(prologue + msi + mtie + "li t1, 200; sw t1, 0(s9); sw zero, 4(s9); wfi; nop\n" + exit + handler)
    val wait = run.index()(_.inst == wfi)
    assert(run.replay().mismatch.isEmpty && run.cycles(wait - 1).retire.isEmpty, "it waits with mstatus.MIE clear too")
    assert(run.cycles(wait).mip == 0x008 && run.cycles(wait + 1).mip == 0x088)
    run.mismatch(run.change(wait + 1)(_.copy(mip = 0x808)))(
      "the WFI before this record retired",
      "(mie 0x080)",
      "(mip 0x808)"
    )
    // a WFI that finds an enabled interrupt pending does not wait, and needs nothing after it
    val ready = ideal(prologue + mtie + "sw zero, 0(s9); sw zero, 4(s9); wfi; nop\n" + exit + handler)
    val now   = ready.index()(_.inst == wfi)
    assert(ready.cycles(now).mip == 0x80 && ready.cycles(now - 1).retire.isDefined)
    assert(ready.replay(ready.change(now + 1)(_.copy(mip = 0))).mismatch.isEmpty)
    // with mstatus.MIE set, an interrupt that is due is taken before the WFI, not after it
    val due = tour.cycles(sleep + 1).mip
    tour.mismatch(tour.change(sleep)(_.copy(mip = due)))("core:  8000", "wfi", "trap.valid: core false, model true")
  }

  it should "find a record after the store to EXIT, and an EXIT that does not show" in {
    val last   = tour.cycles.size - 1
    val silent = tour.change(last)(_.copy(exit = None))
    val more   = CoSimCycle(Some(IssRetire(0x80000000L, 0x13, 0x80000004L)))
    assert(tour.replay(tour.cycles :+ more).mismatch.isEmpty, "the comparison ends at the store and its event")
    tour.mismatch(silent)("stored 0 to EXIT", s"cycle ${last + 1}", "the core did not raise EXIT")
    val lag = CoSimRules(exitLag = 2)
    tour.mismatch(silent :+ more, lag)("a record retired after the store to EXIT", "addi zero, zero, 0")
    tour.mismatch(silent :+ CoSimCycle() :+ more.copy(exit = Some(0)), lag)("a record retired after the store to EXIT")
    tour.mismatch(silent :+ CoSimCycle() :+ CoSimCycle(), lag)("the core did not raise EXIT")
    assert(tour.replay(silent :+ CoSimCycle() :+ CoSimCycle(exit = Some(0)), lag).mismatch.isEmpty)
    tour.mismatch(tour.change(last)(_.copy(exit = Some(3))))("the core exited with 3, the model with 0")
    // a system without the event is not asked for it
    assert(CoSim2.replay(silent.iterator, tour.image, model, hasExit = false).mismatch.isEmpty)
  }

  it should "find an EXIT that no store explains" in {
    tour.mismatch(tour.change(3)(_.copy(exit = Some(0))))(
      "the core raised EXIT (0) in cycle 4",
      "8 cycles later the model's program has not stored to EXIT (12 records)"
    )
    // a model without SimCtrl cannot exit at all
    val image  = MemImage(Asm("nop; nop; nop; ecall"), MemMap.ramBase)
    val cycles = Iss(image).run().map(r => CoSimCycle(Some(r), exit = Option.when(r.pc == MemMap.ramBase)(0)))
    val report = CoSim2.replay(cycles.iterator, image).mismatch.getOrElse(fail("no mismatch"))
    assert(report.contains("the core raised EXIT (0) in cycle 1; the model has no SimCtrl"), report)
  }

  it should "find a record after a halt, a halt the model does not have, and streams that end early" in {
    val image                                                 = MemImage(Asm("nop; nop; ecall; nop"), MemMap.ramBase)
    val cycles                                                = Iss(image).run().map(r => CoSimCycle(Some(r)))
    val nop                                                   = cycles.head
    def replay(cycles: Seq[CoSimCycle], maxCycles: Int = 100) =
      CoSim2.replay(cycles.iterator, image, maxCycles = maxCycles)
    val halted = replay(cycles ++ Seq.fill(20)(CoSimCycle(halted = true)))
    assert(halted.mismatch.isEmpty && halted.cycles == 3 && halted.trace.last.halt.contains(Halt.Ecall))
    assert(replay(cycles).mismatch.isEmpty, "the stream may end at the halt")
    val after = replay(cycles ++ Seq(CoSimCycle(), CoSimCycle(), nop)).mismatch.getOrElse(fail("no mismatch"))
    assert(after.contains("a record retired after the one that halted the core"), after)
    val early = replay(cycles.take(1) :+ CoSimCycle(halted = true)).mismatch.getOrElse(fail("no mismatch"))
    assert(early.contains("the core halted after 1 records; the model did not"), early)
    assert(replay(cycles.take(2)).mismatch.exists(_.contains("the cycles ended after 2 records")))
    // an EXIT event after the halt, which no store of the model can explain any more
    val devices = CoSim2.model(devices = Iss.simDevices(clint = false))
    val late    = cycles ++ Seq(CoSimCycle(), CoSimCycle(exit = Some(0)))
    val report  = CoSim2.replay(late.iterator, image, devices).mismatch.getOrElse(fail("no mismatch"))
    assert(report.contains("the core raised EXIT (0) in cycle 5; the model's program halted without a store"), report)
    assert(CoSim2.replay(cycles.iterator, image, devices).mismatch.isEmpty)
    assert(replay(cycles, maxCycles = 2).mismatch.exists(_.contains("did not end within 2 cycles")))
  }

  it should "keep only the last records of a long comparison when told to, and count them all" in {
    def kept(keep: Int, cycles: Seq[CoSimCycle] = tour.cycles) =
      CoSim2.replay(cycles.iterator, tour.image, model, keep = keep)
    val all = tour.replay()
    val n   = all.trace.size
    assert(all.mismatch.isEmpty && all.records == n && n > 10, "by default the trace has every record")
    for (keep <- Seq(0, 1, 3, n - 1, n, n + 1, Int.MaxValue)) {
      val r = kept(keep)
      assert(r.trace == all.trace.takeRight(keep) && r.records == n, s"keep = $keep")
      assert(r.mismatch.isEmpty && r.cycles == all.cycles && r.exit == all.exit, s"keep = $keep")
    }
    // a mismatch names the index of its record among all of them and shows the records before
    // it, however few are kept
    val at      = tour.index(6)(r => (r.inst & 0x7f) == 0x13 && r.rd != 0) // an addi: nothing of it is sampled
    val before  = tour.cycles.take(at).count(_.retire.isDefined)
    val faulty  = tour.record(at)(r => r.copy(rdValue = r.rdValue ^ 1))
    val reports = Seq(Int.MaxValue, 2, 0).map(kept(_, faulty))
    assert(before > 6 && reports.map(_.records) == Seq(before, before, before))
    assert(reports.map(_.trace.size) == Seq(before, 2, 0) && reports(1).trace == reports(0).trace.takeRight(2))
    val text = reports.map(_.mismatch.getOrElse(fail("no mismatch")))
    assert(text.distinct.size == 1 && text.head.startsWith(s"instruction $before (cycle ${at + 1}):"), text.head)
    assert(text.head.split("before it:")(1).linesIterator.count(_.trim.nonEmpty) == 4, "the context is 4 records")
    assert(intercept[IllegalArgumentException](kept(-1)).getMessage.contains("keep -1"))
  }

}
