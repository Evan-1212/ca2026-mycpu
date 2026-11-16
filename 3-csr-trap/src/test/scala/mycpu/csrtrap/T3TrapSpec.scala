// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3.simulator.scalatest.ChiselSim
import mycpu.common._
import org.scalatest.flatspec.AnyFlatSpec

/** The exceptions of the T3 core (T3-02), one directed program per rule, each run on the system
  * and compared with the golden model record by record; on top of that, every trap's `mepc`,
  * `mcause` and `mtval`, and what the instruction left undone, are checked against values
  * written into the test from the specification, so a mistake shared by the core and the model
  * shows too.
  */
class T3TrapSpec extends AnyFlatSpec with ChiselSim {

  /** The program `body` in the frame of [[T3.frame]], assembled with its symbols. */
  private def program(body: String, vectored: Boolean = false): (MemImage, Map[String, Long]) = {
    val p = AsmX.assemble(T3.frame(body, vectored))
    (MemImage(p), p.symbols)
  }

  /** Runs `body` (see [[program]]); returns the traps, the whole trace and the symbols. */
  private def traps(
      c: Top,
      body: String,
      vectored: Boolean = false
  ): (Seq[IssRetire], Seq[IssRetire], Map[String, Long]) = {
    val (image, symbols) = program(body, vectored)
    val result           = T3.check(c, image)
    (T3.traps(result), result.trace, symbols)
  }

  /** `r` is an exception with these values, taken at `pc`, going to `handler`. */
  private def expect(r: IssRetire, pc: Long, cause: Long, tval: Long, handler: Long): Unit = {
    assert(
      r.pc == pc && r.trap.contains(Trap(cause, tval)),
      f"$r: expected mcause $cause, mtval 0x$tval%x at 0x$pc%08x"
    )
    assert(r.nextPc == handler && r.rd == 0 && r.mem.isEmpty && r.csr.isEmpty, s"$r: a trap changes nothing else")
  }

  "The T3 core" should "trap on ecall with mtval 0 and on ebreak with mtval = pc" in {
    simulate(new Top) { c =>
      val (t, _, s) = traps(c, "    e1: ecall\n    e2: ebreak\n")
      assert(t.size == 2)
      expect(t(0), s("e1"), Trap.EcallM, 0, s("trap"))
      expect(t(1), s("e2"), Trap.Breakpoint, s("e2"), s("trap"))
    }
  }

  it should "trap on a word that is no instruction, with mtval = the word" in {
    simulate(new Top) { c =>
      val words     = Seq(0x00000000L, 0xffffffffL, 0x0000707fL, 0x30200873L, 0x10500173L, 0x04004033L)
      val body      = words.zipWithIndex.map { case (w, i) => f"    w$i: .word 0x$w%08x" }.mkString("\n")
      val (t, _, s) = traps(c, body)
      assert(t.size == words.size)
      for ((w, i) <- words.zipWithIndex) expect(t(i), s(s"w$i"), Trap.Illegal, w, s("trap"))
    }
  }

  it should "trap on a CSR that does not exist and on a write to a read-only one, and leave rd as it was" in {
    simulate(new Top) { c =>
      val body          = """
        li    a1, 0x1234
        li    a2, 0
      i1: csrr  a1, 0x7c0           # no CSR there
      i2: csrw  mhartid, a2         # csrrw x0 writes, even a read-only CSR
      i3: csrrs a1, cycle, a2       # rs1 is not x0: a write, although a2 is 0
      i4: csrrci a1, mvendorid, 1
      i5: csrrwi x0, instret, 0
          csrrs a3, cycle, x0       # no write: legal
          csrrsi a4, mhartid, 0
          csrrc a5, time, zero
          csrw  misa, a1            # read/write address, writes ignored: legal
          csrw  mhpmcounter11, a1
          csrw  mhpmevent31, a1
          csrw  mip, a1
      """
      val (t, trace, s) = traps(c, body)
      assert(t.size == 5, t.mkString("\n"))
      for ((r, i) <- t.zipWithIndex) expect(r, s(s"i${i + 1}"), Trap.Illegal, r.inst, s("trap"))
      assert(T3.reg(trace, "a1") == 0x1234, "an illegal CSR instruction writes no register")
    }
  }

  it should "trap on a misaligned load or store with mtval = the address, and not access memory" in {
    simulate(new Top) { c =>
      val body          = """
        la    a1, data
        li    a2, -1
      l1: lw    a3, 1(a1)
      l2: lh    a3, 3(a1)
      l3: lhu   a3, 1(a1)
      s1: sw    a2, 2(a1)
      s2: sh    a2, 1(a1)
          lw    a4, 0(a1)            # still 0x11223344
          lw    a5, 4(a1)
          lb    a6, 3(a1)            # a byte is never misaligned
          j     out
          .balign 4
      data:
          .word 0x11223344
          .word 0x55667788
      out:
      """
      val (t, trace, s) = traps(c, body)
      val data          = s("data")
      assert(t.size == 5)
      expect(t(0), s("l1"), Trap.LoadMisaligned, data + 1, s("trap"))
      expect(t(1), s("l2"), Trap.LoadMisaligned, data + 3, s("trap"))
      expect(t(2), s("l3"), Trap.LoadMisaligned, data + 1, s("trap"))
      expect(t(3), s("s1"), Trap.StoreMisaligned, data + 2, s("trap"))
      expect(t(4), s("s2"), Trap.StoreMisaligned, data + 1, s("trap"))
      assert(T3.reg(trace, "a4") == 0x11223344L && T3.reg(trace, "a5") == 0x55667788L, "no store happened")
      assert(T3.reg(trace, "a3") == 0, "no load wrote rd")
    }
  }

  it should "trap on an access to an unmapped address, misaligned first" in {
    simulate(new Top) { c =>
      val body          = """
        li    a1, 0x30000000
        li    a3, 7
      f1: lw    a3, 0(a1)
      f2: sb    a3, 1(a1)
      f3: lw    a3, 2(a1)            # misaligned and unmapped: misaligned
      f4: sh    a3, 3(a1)
      f5: lbu   a3, -1(zero)         # 0xffffffff
      """
      val (t, trace, s) = traps(c, body)
      assert(t.size == 5)
      expect(t(0), s("f1"), Trap.LoadAccessFault, 0x30000000L, s("trap"))
      expect(t(1), s("f2"), Trap.StoreAccess, 0x30000001L, s("trap"))
      expect(t(2), s("f3"), Trap.LoadMisaligned, 0x30000002L, s("trap"))
      expect(t(3), s("f4"), Trap.StoreMisaligned, 0x30000003L, s("trap"))
      expect(t(4), s("f5"), Trap.LoadAccessFault, 0xffffffffL, s("trap"))
      assert(T3.reg(trace, "a3") == 7)
    }
  }

  it should "trap on a jump or a taken branch to a misaligned target, on the instruction itself" in {
    simulate(new Top) { c =>
      val body          = """
        li    ra, 5
        la    a1, target
        addi  a2, a1, 2
      j1: jalr  ra, 0(a2)            # misaligned: no link written
      j2: jal   ra, 6
      j3: beq   zero, zero, 10
      j4: bne   zero, zero, 10       # not taken: no trap
          addi  a3, a1, 1
      j5: jalr  ra, 0(a3)            # bit 0 is cleared: aligned, no trap
          .balign 4
      target:
          addi  a4, zero, 1
      """
      val (t, trace, s) = traps(c, body)
      assert(t.size == 3, t.mkString("\n"))
      expect(t(0), s("j1"), Trap.InstMisaligned, s("target") + 2, s("trap"))
      expect(t(1), s("j2"), Trap.InstMisaligned, s("j2") + 6, s("trap"))
      expect(t(2), s("j3"), Trap.InstMisaligned, s("j3") + 10, s("trap"))
      assert(trace.exists(r => r.pc == s("j5") && r.nextPc == s("target") && r.rd == 1), "jalr clears bit 0")
      assert(trace.filter(_.pc < s("j5")).forall(r => r.rd != 1 || r.rdValue == 5), "a trapping jump links nothing")
    }
  }

  it should "trap on a fetch from an unmapped address with mtval = mepc = that address" in {
    simulate(new Top) { c =>
      val body          = """
        la    t4, back
        li    a1, 0x40000000
      go: jalr  ra, 0(a1)
      back:
      """
      val (t, trace, s) = traps(c, body)
      assert(t.size == 1)
      expect(t.head, 0x40000000L, Trap.InstAccessFault, 0x40000000L, s("trap"))
      assert(t.head.inst == 0, "nothing was fetched")
      assert(trace.exists(r => r.pc == s("go") && r.nextPc == 0x40000000L && r.rd == 1), "the jump retires")
    }
  }

  it should "send exceptions to BASE in vectored mode" in {
    simulate(new Top) { c =>
      val (t, _, s) = traps(c, "    e1: ecall\n    e2: .word 0\n", vectored = true)
      assert(t.size == 2 && t.forall(_.nextPc == s("trap")), "an exception is not vectored")
    }
  }

  it should "move MIE to MPIE on trap entry and back on mret, with MPIE = 1 and MPP = 3 after it" in {
    simulate(new Top) { c =>
      // a handler of its own that reads mstatus, and mstatus after each mret
      val source = f"""
        la    t0, handler
        csrw  mtvec, t0
        li    s1, 0
        csrsi mstatus, 8           # MIE = 1 (no interrupt is enabled in mie)
      e1: ecall
        csrr  s3, mstatus          # MIE = 1, MPIE = 1
        csrci mstatus, 8           # MIE = 0
      e2: ecall
        csrr  s5, mstatus          # MIE = 0, MPIE = 1
        li    t0, 0x${T3.exitAddr}%x
        sw    zero, 0(t0)
      1: j    1b
        .balign 4
      handler:
        csrr  t1, mstatus
        mv    s2, s1
        mv    s1, t1
        csrr  t2, mepc
        addi  t2, t2, 4
        csrw  mepc, t2
        mret
      """
      val result                  = T3.check(c, T3.asm(source))
      val trace                   = result.trace
      def at(n: Int, reg: String) = trace.filter(_.rd == riscv.Reg(reg)).map(_.rdValue).apply(n)
      assert(at(1, "s1") == 0x1880L, "in the first handler: MPP 3, MPIE 1, MIE 0")
      assert(at(2, "s1") == 0x1800L && at(1, "s2") == 0x1880L, "in the second: MPIE 0")
      assert(T3.reg(trace, "s3") == 0x1888L, "after the first mret: MIE 1, MPIE 1")
      assert(T3.reg(trace, "s5") == 0x1880L, "after the second mret: MIE 0, MPIE 1")
    }
  }

  it should "start from the reset values of T3-02" in {
    simulate(new Top) { c =>
      val csrs   = Seq("mstatus", "mtvec", "mie", "mip", "mepc", "mcause", "mtval", "mscratch", "mcountinhibit", "misa")
      val reads  = csrs.zipWithIndex.map { case (n, i) => s"csrr x${i + 5}, $n" }.mkString("\n")
      val source = f"$reads\nli t0, 0x${T3.exitAddr}%x\nsw zero, 0(t0)\n1: j 1b\n"
      val trace  = T3.check(c, T3.asm(source)).trace
      val values = csrs.indices.map(i => trace(i).rdValue)
      assert(values == Seq(0x1800L, 0x80000000L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0x40001100L), values)
    }
  }
}
