// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec
import riscv.Reg

/** What Sail 0.16 did with CSR writes to `mcause`, configured as this hart (M-mode only, RV32IM;
  * `3-csr-trap/README.md`): the observation that the model's rule (`Trap.legalCause`) and the RTL
  * are checked against, independent of both.
  */
object SailMcause {

  /** The exception codes and the interrupt codes (written with bit 31) that were stored. */
  val exceptions: Set[Int] = Set(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 15, 19)
  val interrupts: Set[Int] = Set(1, 3, 5, 7, 9, 11)

  /** Wider values that were written; none of them was stored. */
  val wide: Seq[Long] =
    Seq(0x100L, 0x10000L, 0x40000002L, 0x7fffffffL, 0x80000100L, 0x80010003L, 0xc0000003L, 0xffffffffL)

  /** The values that were written: every code from 0 to 69 without and with bit 31, and `wide`. */
  val written: Seq[Long] = (0 until 70).flatMap(code => Seq(code.toLong, (1L << 31) | code)) ++ wide

  /** Whether Sail stored the written value `value`; any other write left `mcause` as it was. */
  def stored(value: Long): Boolean = {
    val code = (value & 0x7fffffffL).toInt
    !wide.contains(value) && (if ((value >> 31) != 0) interrupts(code) else exceptions(code))
  }
}

/** Directed programs for the CSR, trap, interrupt and counter rules of T3-01 to T3-04, one rule
  * (or a few related ones) each, on the T3 model with the D12 devices.
  *
  * Every program runs inside a frame: `mtvec` points at `handler` (vectored if asked). An exception
  * saves `mcause`, `mepc` and `mtval` in s1, s2 and s3, counts in s4, and returns to `mepc + 4`, or
  * to `s5` if the program set it. An interrupt saves `mcause` and `mepc` in s1 and s2, counts in s4,
  * turns off its own bit in `mie` (the model's `mipHw` stays set, as a level would) and returns.
  * The program ends by falling through to `done`, which writes SimCtrl `EXIT`.
  */
class IssTrapSpec extends AnyFlatSpec {
  private def frame(body: String, vectored: Boolean = false) = s"""
    la    t0, handler
    ${if (vectored) "ori   t0, t0, 1" else ""}
    csrw  mtvec, t0
$body
    j     done
    .balign 64
handler:                 # in vectored mode, interrupt n lands at handler + 4 * n
    j     exception
    .rept 15
    j     interrupt
    .endr
exception:
    csrr  s1, mcause
    bltz  s1, interrupt  # direct mode: interrupts come here too
    csrr  s2, mepc
    csrr  s3, mtval
    addi  s4, s4, 1
    addi  t0, s2, 4
    beqz  s5, 1f
    mv    t0, s5
    li    s5, 0
1:  csrw  mepc, t0
    mret
interrupt:
    csrr  s1, mcause
    csrr  s2, mepc
    addi  s4, s4, 1
    li    t0, 1
    sll   t0, t0, s1     # the low 5 bits of mcause: the interrupt's code
    csrc  mie, t0
    mret
done:
    li    t6, 0x${MemMap.simCtrlBase.toHexString}
    sw    zero, ${MemMap.SimCtrl.Exit}(t6)
"""

  /** Runs `body` in the frame (see the class comment) after `setup`; returns the model and its records. */
  private def run(body: String, vectored: Boolean = false, setup: Iss => Unit = _ => (), maxSteps: Int = 2000) = {
    val (iss, trace, _) = runProgram(body, vectored, setup, maxSteps)
    (iss, trace)
  }

  /** As `run`, and the symbols of the program. */
  private def runProgram(
      body: String,
      vectored: Boolean = false,
      setup: Iss => Unit = _ => (),
      maxSteps: Int = 2000
  ) = {
    val program = AsmX.assemble(frame(body, vectored))
    val iss     = Iss(MemImage(program), IssCsr.T3, devices = IssCsr.devices())
    setup(iss)
    val trace = iss.run(maxSteps)
    assert(iss.exit.contains(0), s"the program did not exit:\n${trace.takeRight(5).mkString("\n")}")
    (iss, trace, program.symbols)
  }
  private def r(iss: Iss, name: String)    = iss.reg(Reg(name))
  private def traps(trace: Seq[IssRetire]) = trace.filter(_.trap.isDefined)

  "CSR instructions" should "read the old value and write as RW, RS and RC do, with their immediate forms" in {
    val (iss, trace) = run("""
      li     t1, 0xf0f0
      csrrw  a0, mscratch, t1     # a0 = 0 (reset), mscratch = f0f0
      li     t2, 0x0f00
      csrrs  a1, mscratch, t2     # a1 = f0f0, mscratch = fff0
      li     t2, 0x00f0
      csrrc  a2, mscratch, t2     # a2 = fff0, mscratch = ff00
      csrrwi a3, mscratch, 21     # a3 = ff00, mscratch = 21
      csrrsi a4, mscratch, 10     # a4 = 21, mscratch = 31
      csrrci a5, mscratch, 3      # a5 = 31, mscratch = 28
      csrr   a6, mscratch
    """)
    assert(Seq("a0", "a1", "a2", "a3", "a4", "a5", "a6").map(r(iss, _)) == Seq(0, 0xf0f0, 0xfff0, 0xff00, 21, 31, 28))
    val writes = trace.flatMap(_.csr).filter(_.addr == Csr.Mscratch).map(_.wdata)
    assert(writes == Seq(0xf0f0, 0xfff0, 0xff00, 21, 31, 28), "the record shows each written value")
  }

  they should "not write with rs1 = x0 or uimm = 0, write with any other rs1, and read nothing for RW to x0" in {
    val (iss, trace) = run("""
      csrw   mscratch, 5
      csrrs  a0, mscratch, x0
      csrrc  a1, mscratch, x0
      csrrsi a2, mscratch, 0
      csrrci a3, mscratch, 0
      li     t1, 0
      csrrs  a4, mscratch, t1     # rs1 is not x0: a write (of the same value)
      csrrw  x0, mscratch, t1     # a write; rd = x0
    """)
    assert(Seq("a0", "a1", "a2", "a3", "a4").map(r(iss, _)).forall(_ == 5) && iss.csr(Csr.Mscratch) == 0)
    val csrRecords = trace.filter(t => AsmX.decode(t.inst).exists(i => AsmX.isCsr(i.name) && i.imm == Csr.Mscratch))
    assert(csrRecords.map(_.csr.isDefined) == Seq(true, false, false, false, false, true, true))
    assert(csrRecords.last.rd == 0 && csrRecords.last.csr.contains(CsrWrite(Csr.Mscratch, 0)))
  }

  "Read-only CSRs (bits 11:10 = 11)" should "trap as illegal on a write, and not on a read without a write" in {
    val (iss, trace) = run("""
      csrr   a0, mhartid
      csrrsi a1, mvendorid, 0
      csrrc  a2, cycle, x0
      csrrw  x0, mhartid, x0      # traps: RW always writes
      csrrs  t0, instret, t1      # traps: rs1 is not x0, even if t1 is 0
      csrrci t0, time, 1          # traps
      csrr   a3, marchid
    """)
    val t = traps(trace)
    assert(t.size == 3 && t.forall(_.trap.get.cause == Trap.Illegal))
    assert(t.map(_.trap.get.tval) == t.map(_.inst), "mtval is the instruction")
    assert(t.forall(x => x.rd == 0 && x.csr.isEmpty))
    assert(r(iss, "s4") == 3 && r(iss, "a0") == 0 && r(iss, "a3") == 0)
  }

  "Unimplemented CSRs" should "trap as illegal on any access" in {
    val (_, trace) = run("""
      csrr  a0, 0x306      # mcounteren: no U-mode
      csrr  a0, 0x180      # satp: no S-mode
      csrr  a0, 0x7b0      # dcsr: debug mode only
      csrw  0x30a, t0      # menvcfg
      csrr  a0, 0xb01      # there is no mtime CSR
    """)
    assert(traps(trace).map(_.trap.get.cause) == Seq.fill(5)(Trap.Illegal))
  }

  "WARL and hardwired CSRs" should "ignore writes without trapping" in {
    val (iss, trace) = run("""
      li    t1, -1
      csrw  misa, t1
      csrr  a0, misa
      csrw  mhpmcounter11, t1
      csrr  a1, mhpmcounter11
      csrw  mhpmevent11, t1
      csrr  a2, mhpmevent11
      csrw  mcountinhibit, t1
      csrr  a3, mcountinhibit
      csrw  mcountinhibit, zero
      csrw  mstatush, t1
      csrr  a4, mstatush
      csrw  mstatus, t1
      csrr  a5, mstatus
      csrw  mstatus, zero
      csrw  mie, t1
      csrr  a6, mie
      csrw  mepc, t1
      csrr  a7, mepc
    """)
    assert(traps(trace).isEmpty)
    assert(r(iss, "a0") == 0x40001100L, "misa: RV32IM")
    assert(r(iss, "a1") == 0 && r(iss, "a2") == 0, "counters 11..31 are hardwired 0")
    assert(r(iss, "a3") == 0x7fdL, "mcountinhibit: bit 1 and bits 11..31 are 0")
    assert(r(iss, "a4") == 0, "mstatush")
    assert(r(iss, "a5") == 0x1888L, "mstatus: MIE, MPIE, and MPP hardwired to 3")
    assert(r(iss, "a6") == 0x888L, "mie: MSIE, MTIE, MEIE")
    assert(r(iss, "a7") == 0xfffffffcL, "mepc[1:0] = 0")
  }

  "mhpmevent3..10" should "hold the low 4 bits of what is written, as Sail with hpmevent_width_bits = 4" in {
    val (iss, _) = run("""
      csrwi mhpmevent3, 5
      csrr  a0, mhpmevent3
      li    t1, 9
      csrw  mhpmevent10, t1
      csrr  a1, mhpmevent10
      csrwi mhpmevent4, 15
      csrr  a2, mhpmevent4
      li    t1, 0x1f
      csrw  mhpmevent5, t1
      csrr  a3, mhpmevent5
      li    t1, 0x100
      csrw  mhpmevent5, t1
      csrr  a4, mhpmevent5
      li    t1, -1
      csrw  mhpmevent6, t1
      csrr  a5, mhpmevent6
    """)
    assert(r(iss, "a0") == 5 && r(iss, "a1") == 9 && r(iss, "a2") == 15)
    assert(r(iss, "a3") == 15 && r(iss, "a4") == 0 && r(iss, "a5") == 15)
  }

  "mcause" should "keep its value on a CSR write of a cause that Sail does not accept for this hart" in {
    import SailMcause._
    for (value <- written) assert(Trap.legalCause(value) == stored(value), f"0x$value%x")
    assert(Trap.legalExceptions == exceptions.map(1L << _).sum && Trap.legalInterrupts == interrupts.map(1L << _).sum)
    assert((Trap.raisedExceptions & ~Trap.legalExceptions) == 0, "what the hart raises can be written back")

    val (iss, trace) = run("""
      li    t1, 0x8000000b
      csrw  mcause, t1
      csrr  a0, mcause       # a legal interrupt cause is stored
      li    t1, 10
      csrw  mcause, t1
      csrr  a1, mcause       # 10 is reserved: the previous value stays
      li    t1, 0x80000002
      csrw  mcause, t1
      csrr  a2, mcause       # there is no interrupt 2
      csrwi mcause, 19
      csrr  a3, mcause
      li    t1, -1
      csrw  mcause, t1
      csrr  a4, mcause
      csrsi mcause, 4        # 19 | 4 = 23 is reserved: csrrs is a write like any other
      csrr  a5, mcause
      csrci mcause, 16       # 19 & ~16 = 3
      csrr  a6, mcause
    """)
    assert(traps(trace).isEmpty, "a write of an illegal value is not an illegal instruction")
    assert(r(iss, "a0") == 0x8000000bL && r(iss, "a1") == 0x8000000bL && r(iss, "a2") == 0x8000000bL)
    assert(r(iss, "a3") == 19 && r(iss, "a4") == 19 && r(iss, "a5") == 19 && r(iss, "a6") == 3)
    // the record carries the value the instruction computed, before the WARL rule
    assert(trace.flatMap(_.csr).filter(_.addr == Csr.Mcause).map(_.wdata).take(2) == Seq(0x8000000bL, 10L))
  }

  "mtvec" should "keep its mode on a write of mode 2 or 3, and hardwire bit 1 to 0" in {
    val (iss, _) = run("""
      csrr  a0, mtvec          # the frame's: direct
      li    t1, 0x80001002
      csrw  mtvec, t1
      csrr  a1, mtvec          # base 0x80001000, mode still 0
      li    t1, 0x80002001
      csrw  mtvec, t1
      csrr  a2, mtvec
      li    t1, 0x80003003
      csrw  mtvec, t1
      csrr  a3, mtvec          # mode still 1
      csrw  mtvec, a0
    """)
    assert(r(iss, "a1") == 0x80001000L && r(iss, "a2") == 0x80002001L && r(iss, "a3") == 0x80003001L)
  }

  "mip" should "read the hardware's MSIP, MTIP and MEIP and ignore writes" in {
    val (iss, trace) = run(
      """
      csrr  a0, mip
      li    t1, -1
      csrw  mip, t1
      csrr  a1, mip
    """,
      setup = _.mipHw = 0x80
    ) // MTIP, bit 7
    assert(r(iss, "a0") == 0x80 && r(iss, "a1") == 0x80 && traps(trace).isEmpty)
  }

  "Reset" should "give the values of T3-02" in {
    val iss = new Iss(IssCsr.T3)
    assert(iss.csr(Csr.Mstatus) == 0x1800, "MIE = MPIE = 0, MPP = 3")
    assert(iss.csr(Csr.Mtvec) == 0x80000000L, "direct, at the RAM")
    for (
      c <- Seq(Csr.Mie, Csr.Mcountinhibit, Csr.Mscratch, Csr.Mepc, Csr.Mcause, Csr.Mtval, Csr.Mhartid, Csr.Mvendorid)
    )
      assert(iss.csr(c) == 0, Csr.name(c))
    assert(!iss.csrExists(Csr.Mstatus + 0x1000) && !new Iss(IssConfig(m = true)).csrExists(Csr.Mstatus))
  }

  "Exceptions" should "set mepc, mcause and mtval as T3-02 says, and change nothing else" in {
    val (iss, trace, sym) = runProgram("""
    ill: .word 0xffffffff         # illegal: mtval = the instruction
    ec:  ecall                    # mtval = 0
    eb:  ebreak                   # mtval = pc
         la    t1, buf
    lm:  lw    a0, 2(t1)          # load misaligned: mtval = the address
    sm:  sh    a0, 1(t1)          # store misaligned
         li    t2, 0x40000002
    mf:  lw    a0, 0(t2)          # misaligned and unmapped: misaligned outranks the access fault
         li    t2, 0x40000000
    lf:  lw    a0, 0(t2)          # load access fault
    sf:  sb    a0, 3(t2)          # store access fault
         la    t2, target
         addi  t2, t2, 2
    jm:  jalr  ra, 0(t2)          # instruction address misaligned: on the jump, rd not written
    bm:  beq   zero, zero, 6      # a taken branch to a misaligned target
    target:
         la    s5, back           # the handler returns there
         li    t2, 0x40000000
         jr    t2                 # instruction access fault at 0x40000000
    back:
         j     out
         .data
    buf: .word 0, 0
         .text
    out:
    """)
    val expected = Seq(
      (Trap.Illegal, sym("ill"), 0xffffffffL),
      (Trap.EcallM, sym("ec"), 0L),
      (Trap.Breakpoint, sym("eb"), sym("eb")),
      (Trap.LoadMisaligned, sym("lm"), sym("buf") + 2),
      (Trap.StoreMisaligned, sym("sm"), sym("buf") + 1),
      (Trap.LoadMisaligned, sym("mf"), 0x40000002L),
      (Trap.LoadAccessFault, sym("lf"), 0x40000000L),
      (Trap.StoreAccess, sym("sf"), 0x40000003L),
      (Trap.InstMisaligned, sym("jm"), sym("target") + 2),
      (Trap.InstMisaligned, sym("bm"), sym("bm") + 6),
      (Trap.InstAccessFault, 0x40000000L, 0x40000000L)
    )
    assert(traps(trace).map(x => (x.trap.get.cause, x.pc, x.trap.get.tval)) == expected)
    assert(r(iss, "ra") == 0, "the misaligned jalr did not write ra")
    assert(traps(trace).last.inst == 0, "a fetch fault reports instruction 0")
    assert(traps(trace).forall(x => x.rd == 0 && x.mem.isEmpty && x.csr.isEmpty && x.nextPc == sym("handler")))
    assert(r(iss, "s4") == expected.size)
  }

  "Trap entry and MRET" should "move MIE to MPIE and back, keep MPP = 3, and return to mepc" in {
    val (iss, _) = run("""
      csrsi  mstatus, 8          # MIE = 1
      la     s5, after
      ecall
    after:
      csrr   a0, mstatus         # after MRET: MIE = MPIE (1), MPIE = 1
      csrr   a1, mcause
      csrci  mstatus, 8
      csrr   a2, mstatus         # MIE = 0, MPIE = 1
      la     s5, after2
      ecall                      # MPIE = 0, MIE = 0
    after2:
      csrr   a3, mstatus         # MIE = MPIE (0), MPIE = 1
    """)
    assert(r(iss, "a0") == 0x1888L && r(iss, "a1") == Trap.EcallM)
    assert(r(iss, "a2") == 0x1880L && r(iss, "a3") == 0x1880L)
    val (iss2, trace2) = run("""
      csrsi  mstatus, 8
      la     t0, check
      csrw   mtvec, t0
    trap:
      ecall
    check:
      csrr   a0, mstatus         # in the handler: MPIE = 1, MIE = 0, MPP = 3
      csrr   a1, mepc
      la     t0, handler
      csrw   mtvec, t0
      csrci  mstatus, 8
    """)
    assert(r(iss2, "a0") == 0x1880L && r(iss2, "a1") == traps(trace2).head.pc)
  }

  "Vectored mode" should "send exceptions to BASE and interrupts to BASE + 4 * code" in {
    val (iss, trace, sym) = runProgram(
      """
      ecall                      # to BASE, the `j exception` entry
      li     t1, 0x80            # MTIE
      csrw   mie, t1
      csrsi  mstatus, 8
    wait:
      nop
      csrci  mstatus, 8
    """,
      vectored = true,
      setup = _.mipHw = 0x80
    )
    val Seq(ecall, irq) = traps(trace)
    assert(ecall.trap.contains(Trap(Trap.EcallM, 0)) && ecall.nextPc == sym("handler"))
    assert(irq.trap.contains(Trap(Trap.interruptCause(Trap.Mti), 0)) && irq.nextPc == sym("handler") + 4 * 7)
    assert(irq.inst == 0 && irq.pc == sym("wait"), "taken before the next instruction, which did not execute")
    assert(r(iss, "s2") == sym("wait") && r(iss, "s1") == Trap.interruptCause(Trap.Mti), "mepc = the next instruction")
  }

  "Interrupts" should "be taken only when mstatus.MIE, mie and mip agree, at bits 3, 7 and 11" in {
    for ((bit, name) <- Seq(3 -> "MSI", 7 -> "MTI", 11 -> "MEI")) {
      def program(mieBits: Int, mstatusMie: Boolean) = s"""
        li     t1, $mieBits
        csrw   mie, t1
        ${if (mstatusMie) "csrsi  mstatus, 8" else "nop"}
        nop
        csrci  mstatus, 8
      """
      val taken = run(program(1 << bit, mstatusMie = true), setup = _.mipHw = 1L << bit)._2
      assert(traps(taken).map(_.trap.get.cause) == Seq(Trap.interruptCause(bit)), name)
      for (
        (mieBits, mstatusMie, why) <- Seq((1 << bit, false, "MIE = 0"), (0x888 & ~(1 << bit), true, "its mie bit off"))
      ) {
        val notTaken = run(program(mieBits, mstatusMie), setup = _.mipHw = 1L << bit)._2
        assert(traps(notTaken).isEmpty, s"$name with $why")
      }
    }
  }

  they should "go by priority MEI, then MSI, then MTI" in {
    val (_, trace) = run(
      """
      li     t1, 0x888
      csrw   mie, t1
      csrsi  mstatus, 8
    loop:                        # the handler turns off each interrupt it takes
      csrr   t1, mie
      bnez   t1, loop
      csrci  mstatus, 8
    """,
      setup = _.mipHw = 0x888
    )
    assert(traps(trace).map(_.trap.get.code) == Seq(Trap.Mei, Trap.Msi, Trap.Mti))
  }

  they should "be taken after a WFI with mepc = its pc + 4; WFI continues at pc + 4 when MIE = 0" in {
    val wfi               = AsmX.fixed("wfi")
    val (iss, trace, sym) = runProgram(
      """
      li     t1, 0x80
      csrw   mie, t1
      wfi                        # pending, MIE = 0: continues at pc + 4
      csrsi  mstatus, 8          # the interrupt is taken before the next instruction
    w:
      csrci  mstatus, 8
    """,
      setup = _.mipHw = 0x80
    )
    val wfis = trace.filter(_.inst == wfi)
    assert(wfis.size == 1 && wfis.head.nextPc == wfis.head.pc + 4 && wfis.head.trap.isEmpty)
    assert(traps(trace).map(_.pc) == Seq(sym("w")) && r(iss, "s2") == sym("w"))
    // nothing pending: WFI waits for the CLINT's timer (the model skips that time in one step),
    // then the interrupt is taken at pc + 4
    val (iss2, trace2, sym2) = runProgram(f"""
      li     t0, 0x${MemMap.clintBase + MemMap.Clint.MtimecmpLo}%x
      li     t1, 1000
      sw     t1, 0(t0)
      sw     zero, 4(t0)             # mtimecmp = 1000
      li     t1, 0x80
      csrw   mie, t1
      csrsi  mstatus, 8
      csrr   a0, mcycle
    sleep:
      wfi
    woken:
      csrci  mstatus, 8
      csrr   a1, time
      csrr   a2, mcycle
    """)
    val i = trace2.indexWhere(_.inst == wfi)
    assert(trace2(i).pc == sym2("sleep") && trace2(i).retired && trace2(i).nextPc == sym2("woken"))
    assert(trace2(i + 1).trap.contains(Trap(Trap.interruptCause(Trap.Mti), 0)) && trace2(i + 1).pc == sym2("woken"))
    assert(r(iss2, "s2") == sym2("woken"), "mepc")
    assert(trace2.size < 100 && r(iss2, "a1") >= 1000 && r(iss2, "a1") < 1100, "the wait is one step")
    assert(r(iss2, "a2") - r(iss2, "a0") > 900, "mcycle counts the cycles of the wait")
  }

  "WFI" should "leave the model asleep when nothing can wake it" in {
    for (
      (setup, why) <- Seq(
        "nop"                            -> "mie = 0",
        "li t1, 0x800; csrw mie, t1"     -> "MEIE alone, and nothing drives MEIP",
        "li t1, 0x80; csrw mie, t1; nop" -> "MTIE, but mtimecmp is at its reset value, 2^64 - 1"
      )
    ) {
      val program = AsmX.assemble(s"$setup; li a0, 1; sleep: wfi; li a0, 2; ecall")
      val iss     = Iss(MemImage(program), IssCsr.T3, devices = IssCsr.devices())
      assert(iss.canExit && !Iss(MemImage(program), IssCsr.T3).canExit)
      val trace = iss.run(100)
      assert(iss.asleep && iss.done && !iss.ended && iss.pc == program.symbols("sleep"), why)
      assert(trace.forall(_.inst != AsmX.fixed("wfi")) && iss.reg(Reg("a0")) == 1, s"$why: the WFI did not retire")
      assert(intercept[IllegalStateException](iss.step()).getMessage.contains("asleep"), why)
      assert(iss.mtime == trace.size && iss.csr(Csr.Mcycle) == trace.size, s"$why: no time was skipped")
      // an interrupt line from outside wakes it, if it is enabled
      iss.mipHw = 0x888
      if (iss.csr(Csr.Mie) != 0)
        assert(!iss.asleep && iss.step().inst == AsmX.fixed("wfi") && iss.step().rdValue == 2, why)
      else assert(iss.asleep, "pending but not enabled does not wake")
    }
  }

  "minstret" should "count retired instructions: two reads N instructions apart differ by N + 1" in {
    val (iss, _) = run("""
      csrr   a0, minstret
      nop
      nop
      nop
      csrr   a1, minstret
      csrr   a2, instret             # the user shadow
      csrr   a3, minstreth
    """)
    assert(r(iss, "a1") - r(iss, "a0") == 4 && r(iss, "a2") - r(iss, "a1") == 1 && r(iss, "a3") == 0)
  }

  it should "not count an instruction that traps, and count MRET" in {
    val (iss, trace) = run("""
    first:
      csrr   a0, minstret
      ecall
      ebreak
      csrr   a1, minstret
    """)
    val from    = trace.indexWhere(t => AsmX.disassemble(t.inst) == "csrrs a0, minstret, zero")
    val to      = trace.indexWhere(t => AsmX.disassemble(t.inst) == "csrrs a1, minstret, zero")
    val between = trace.slice(from, to)
    assert(between.count(_.trap.isDefined) == 2 && between.count(_.inst == AsmX.fixed("mret")) == 2)
    assert(r(iss, "a1") - r(iss, "a0") == between.count(_.trap.isEmpty))
  }

  it should "take a write over the writing instruction's own increment, in both halves" in {
    val (iss, _) = run("""
      li     t1, 100
      csrw   minstret, t1
      csrr   a0, minstret            # 100: the write was not followed by an increment
      csrr   a1, minstret            # 101
      li     t1, -1
      csrw   minstret, t1
      csrw   minstreth, zero         # 0x0000_0000_ffff_ffff, again without an increment
      csrr   a2, minstreth           # 0, then the count carries into the high half
      csrr   a3, minstret            # 0
      csrr   a4, minstreth           # 1
    """)
    assert(Seq("a0", "a1", "a2", "a3", "a4").map(r(iss, _)) == Seq(100, 101, 0, 0, 1))
  }

  "mcountinhibit" should "stop minstret (bit 2) and mcycle (bit 0)" in {
    val (iss, _) = run("""
      csrwi  mcountinhibit, 5
      csrr   a0, minstret
      csrr   a2, mcycle
      nop
      csrr   a1, minstret
      csrr   a3, mcycle
      csrwi  mcountinhibit, 0
      csrr   a4, mcycle
      csrr   a5, cycle               # the user shadow
    """)
    assert(r(iss, "a1") == r(iss, "a0") && r(iss, "a3") == r(iss, "a2"))
    assert(r(iss, "a5") - r(iss, "a4") == 1, "the model's mcycle counts steps")
  }

  "The counters" should "take a write over their own increment, and follow Sail 0.16 for minstret" in {
    // the minstret and mcountinhibit values are what `sail_riscv_sim --rv32 --trace-reg` printed for
    // this sequence; Sail's mcycle follows a platform clock and is no reference (see Iss)
    val (iss, _) = run("""
      li     t1, 100
      csrw   minstret, t1
      csrr   a0, minstret          # 100
      csrr   a1, minstret          # 101
      li     t1, -1
      csrw   minstret, t1
      csrw   minstreth, zero       # a write to the high half suppresses the increment too
      csrr   a2, minstreth         # 0
      csrr   a3, minstret          # 0
      csrr   a4, minstreth         # 1
      csrwi  mcountinhibit, 4      # counted: the value before the instruction decides
      csrr   a5, minstret          # 3
      csrr   a6, minstret          # 3
      csrwi  mcountinhibit, 0      # not counted
      csrr   a7, minstret          # 3
      csrw   mcycle, zero
      csrr   s2, mcycle            # 0: the write replaces the increment of its cycle
      csrr   s3, mcycle            # 1
      li     t2, -1
      csrw   mcycle, t2
      csrwi  mcycleh, 9            # 0x0000_0009_ffff_ffff, in either half
      csrr   s8, mcycleh           # 9, then the count carries
      csrr   s9, mcycleh           # 10
      li     t1, 5
      csrw   minstreth, t1
      csrr   s6, minstret          # 7
      csrr   s7, minstreth         # 5
    """)
    assert(
      Seq("a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "s2", "s3", "s8", "s9", "s6", "s7").map(r(iss, _)) ==
        Seq(100, 101, 0, 0, 1, 3, 3, 3, 0, 1, 9, 10, 13, 5)
    )
  }

  "The hpm counters" should "hold what is written to 3..10, through their shadows too, and read 0 above" in {
    val (iss, _) = run("""
      li     t1, 0x12345
      csrw   mhpmcounter3, t1
      csrwi  mhpmcounter3h, 7
      csrr   a0, mhpmcounter3
      csrr   a1, hpmcounter3h
      csrw   mhpmcounter10, t1
      csrr   a2, hpmcounter10
      csrw   mhpmcounter31, t1
      csrr   a3, hpmcounter31
    """)
    assert(Seq("a0", "a1", "a2", "a3").map(r(iss, _)) == Seq(0x12345, 7, 0x12345, 0))
  }

  "CSRs, M, MRET and WFI" should "be illegal without their extensions, halting as the bootcamp's cores do" in {
    for (
      (src, config) <- Seq(
        "csrr a0, mscratch" -> IssConfig(m = true),
        "mret"              -> IssConfig(m = true),
        "wfi"               -> IssConfig(m = true),
        "mul a0, a1, a2"    -> IssConfig.T1,
        "fence.i"           -> IssConfig(zifencei = false)
      )
    ) {
      val iss      = Iss(MemImage(AsmX(src), MemMap.ramBase), config)
      val Seq(rec) = iss.run(10)
      assert(rec.halt.contains(riscv.Halt.Illegal) && rec.nextPc == rec.pc && rec.trap.isEmpty, src)
    }
    val fenceI = Iss(MemImage(AsmX("fence.i; ecall"), MemMap.ramBase))
    assert(fenceI.run(10).head.nextPc == MemMap.ramBase + 4, "fence.i is a no-op with Zifencei (T1)")
  }

  "The sample hook" should "supply mip, counter reads and device inputs, and nothing the program determines" in {
    val uart    = MemMap.uartBase
    val program = AsmX.assemble(frame(f"""
      csrr   a0, mcycle              # from the hardware
      csrw   mcycle, a0              # csrrw with rd = x0: nothing is read
      csrr   a1, minstret            # the model's: determined by the ISA
      li     t1, 0x$uart%x
      lw     a2, 8(t1)               # UART STATUS: from the hardware
      lw     a3, 12(t1)              # UART CTRL: the model's
      li     t1, 0x800
      csrw   mie, t1
      csrsi  mstatus, 8
    irq:
      nop                            # the hardware raises MEIP here, once
      csrci  mstatus, 8
    """))
    var pending = 0L
    var reads   = 0
    val sample  = new IssSample {
      def mip(modeled: Long)                                         = pending
      def csrRead(addr: Int, modeled: Long, op: Char, operand: Long) = {
        assert(addr == Csr.Mcycle && op == 's', "only a counter that follows time is sampled, and only when read")
        reads += 1
        0xc0ffeeL
      }
      def load(addr: Long, bytes: Int, kind: ReadKind, modeled: Long) = {
        assert(addr == uart + 8 && kind == ReadKind.Input(7) && modeled == 1, "only an input register is sampled")
        0xf3 // the bits that STATUS does not have read 0
      }
      def wfiWait(enabled: Long, timer: Option[Long]) = 0
    }
    val iss = new Iss(IssCsr.T3, devices = IssCsr.devices(), sample = sample)
    iss.load(MemImage(program))
    val trace = scala.collection.mutable.ArrayBuffer[IssRetire]()
    while (!iss.done && trace.size < 500) {
      pending = if (iss.pc == program.symbols("irq") && !trace.exists(_.trap.isDefined)) 0x800 else 0
      trace += iss.step()
    }
    assert(iss.exit.contains(0))
    assert(Seq("a0", "a1", "a2", "a3").map(n => iss.reg(Reg(n))) == Seq(0xc0ffeeL, 5, 3, 0))
    assert(reads == 1, "csrw mcycle (csrrw with rd = x0) does not read the CSR")
    val irq = trace.filter(_.trap.isDefined)
    assert(irq.map(_.pc) == Seq(program.symbols("irq")) && irq.head.trap.get.code == Trap.Mei)
    // the model decides on interrupts: pending ones are not taken while the hart has them disabled
    pending = 0x888
    val off = new Iss(IssCsr.T3, sample = sample)
    off.load(MemImage(AsmX("nop; csrr a0, mip; nop"), MemMap.ramBase))
    assert(off.step().trap.isEmpty && off.step().rdValue == 0x888 && off.step().trap.isEmpty)
  }

  "The devices" should "read back what a program stores, count for INSTRET, and drive mip" in {
    val (sim, clint) = (MemMap.simCtrlBase, MemMap.clintBase)
    val (iss, trace) = run(f"""
      li     s6, 0x$sim%x
      li     s7, 0x$clint%x
      ecall                          # a trap: not an instruction for INSTRET
      lw     a0, ${MemMap.SimCtrl.InstretLo}(s6)
      lw     a1, ${MemMap.SimCtrl.InstretLo}(s6)
      lw     a2, ${MemMap.SimCtrl.InstretHi}(s6)
      lbu    a3, ${MemMap.SimCtrl.InstretLo}(s6)
      sb     zero, ${MemMap.SimCtrl.Exit}(s6)      # not a whole word: ignored
      li     t1, 0x888
      csrw   mie, t1
      li     t1, 3
      sw     t1, ${MemMap.SimCtrl.Meip}(s6)        # bit 0 only
      lw     a4, ${MemMap.SimCtrl.Meip}(s6)
      csrr   a5, mip                 # MEIP
      sw     zero, ${MemMap.SimCtrl.Meip}(s6)
      sw     t1, ${MemMap.Clint.Msip}(s7)
      lw     a6, ${MemMap.Clint.Msip}(s7)
      csrr   a7, mip                 # MSIP
      csrsi  mstatus, 8              # MSI is taken; its handler turns MSIE off
      csrci  mstatus, 8
      li     t0, 0x${clint + MemMap.Clint.MtimecmpLo}%x
      sw     zero, 4(t0)             # mtimecmp = 0x0000_0000_ffff_ffff: each half on its own
      lw     s8, 0(t0)
      lw     s9, 4(t0)
      sw     zero, 0(t0)             # mtimecmp = 0: MTIP
      csrr   s10, mip                # MSIP and MTIP
      li     t1, 0x${MemMap.uartBase}%x
      li     t2, 'A'
      sw     t2, 0(t1)               # UART TXDATA
    """)
    // INSTRET is the number of instructions retired before the load, the ecall not among them
    val load = trace.indexWhere(_.mem.exists(_.addr == sim + MemMap.SimCtrl.InstretLo))
    val n    = trace.take(load).count(_.retired)
    assert(n == load - 1 && n > 10, "one trap so far")
    assert(r(iss, "a0") == n && r(iss, "a1") == n + 1 && r(iss, "a2") == 0 && r(iss, "a3") == n + 3)
    assert(r(iss, "a4") == 1 && r(iss, "a5") == 0x800 && r(iss, "a6") == 1 && r(iss, "a7") == 0x008)
    assert(traps(trace).map(_.trap.get.cause) == Seq(Trap.EcallM, Trap.interruptCause(Trap.Msi)))
    assert(r(iss, "s8") == 0xffffffffL && r(iss, "s9") == 0 && r(iss, "s10") == 0x088)
    assert(iss.devices.collectFirst { case u: UartModel => u.tx.toString }.contains("A"))
    assert(iss.readKind(sim + MemMap.SimCtrl.CycleLo, 4).contains(ReadKind.Counter(Iss.simCtrlCycle, high = false)))
    assert(iss.readKind(clint + MemMap.Clint.MtimeHi, 4).contains(ReadKind.Counter(Iss.timeCounter, high = true)))
    assert(iss.readKind(sim + MemMap.SimCtrl.InstretLo, 4).contains(ReadKind.Modeled))
    assert(iss.readKind(MemMap.ramBase, 4).isEmpty, "the RAM is no device")
  }
}
