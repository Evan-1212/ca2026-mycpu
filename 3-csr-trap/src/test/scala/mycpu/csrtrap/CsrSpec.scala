// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.csrtrap

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common.Csr
import org.scalatest.flatspec.AnyFlatSpec

/** The CSR instruction rules of T3-01 on [[CsrFile]]: reset values, the three address categories
  * (read-only by address, hardwired or WARL, driven by hardware), which instructions write, and
  * the addresses without a CSR.
  */
class CsrSpec extends AnyFlatSpec with ChiselSim {
  import CsrBench.mask32
  import CsrFile.Address._
  import CsrFile.implemented

  private val ones = mask32

  /** The six CSR instructions as (name, operation, immediate form). */
  private val forms = Seq(
    ("csrrw", CsrOp.Rw, false),
    ("csrrs", CsrOp.Rs, false),
    ("csrrc", CsrOp.Rc, false),
    ("csrrwi", CsrOp.Rw, true),
    ("csrrsi", CsrOp.Rs, true),
    ("csrrci", CsrOp.Rc, true)
  )

  private def readOnlyByAddress(addr: Int) = (addr >> 10) == 3
  private def hex(addr: Int)               = f"0x$addr%03x"

  "CsrFile" should "implement the CSRs of T3-01 and no other address" in {
    // The addresses at which a `csrr` does not trap on Sail 0.16 configured as this hart
    // (3-csr-trap/README.md), found by reading all 4096: the ground truth, as address ranges.
    val sail = Seq(
      0x300 -> 0x301,
      0x304 -> 0x305,
      0x310 -> 0x310,
      0x320 -> 0x320,
      0x323 -> 0x344,
      0xb00 -> 0xb00,
      0xb02 -> 0xb1f,
      0xb80 -> 0xb80,
      0xb82 -> 0xb9f,
      0xc00 -> 0xc1f,
      0xc80 -> 0xc9f,
      0xf11 -> 0xf15
    )
    assert(implemented == sail.flatMap { case (first, last) => first to last })
    assert(implemented.size == 171)
    // not there in an M-mode-only hart: mcounteren, medeleg, mideleg, menvcfg, the machine `time`
    for (absent <- Seq(0x306, 0x302, 0x303, 0x30a, 0xb01, 0xb81, 0x321, 0x322))
      assert(!implemented.contains(absent), hex(absent))
    // the assembler's names are these CSRs, and the golden model has the same set
    assert(Csr.byName.values.toSeq.sorted == implemented)
    val model = new mycpu.common.Iss(IssCsr.T3)
    assert((0 until 4096).filter(model.csrExists) == implemented)
  }

  it should "reset every CSR to the value of T3-02" in {
    simulate(new CsrFile()) { c =>
      val b        = CsrBench(c)
      val expected = Map(Mstatus -> BigInt(0x1800), Misa -> BigInt(0x40001100L), Mtvec -> BigInt(0x80000000L))
      for (addr <- implemented)
        assert(b.peek(addr) == expected.getOrElse(addr, BigInt(0)), s"CSR ${hex(addr)} after reset")
    }
  }

  it should "reset mtvec to the value of its parameter" in {
    simulate(new CsrFile(mtvecReset = 0x00001001L)) { c =>
      val b = CsrBench(c)
      assert(b.peek(Mtvec) == 0x1001)
      assert(b.exception(2, 0x100, 0) == 0x1000)
    }
  }

  it should "answer illegal for every address without a CSR, whatever the instruction, and read 0 there" in {
    simulate(new CsrFile()) { c =>
      val b      = CsrBench(c)
      val absent = (0 until 4096).filterNot(implemented.contains)
      assert(absent.size == 4096 - 171)
      for (addr <- absent) {
        // a read (csrrs with x0) and a write
        for ((op, rs1) <- Seq((CsrOp.Rs, 0), (CsrOp.Rw, 5))) {
          val a = b.present(op, addr, rs1, rs1Data = ones)
          assert(a.illegal && !a.write && a.rdata == 0, s"CSR ${hex(addr)}")
        }
      }
      for ((name, op, imm) <- forms; rs1 <- Seq(0, 31); addr <- Seq(0x000, 0x306, 0x345, 0xb01, 0x7ff, 0xfff))
        assert(b.present(op, addr, rs1, imm = imm).illegal, s"$name with rs1 field $rs1 on CSR ${hex(addr)}")
      b.idle()
      c.io.csr.illegal.expect(false.B, "not without a valid instruction")
      for (addr <- implemented) assert(!b.present(CsrOp.Rs, addr, rs1 = 0).illegal, s"a read of CSR ${hex(addr)}")
    }
  }

  it should "trap a CSR that is read-only by address only when the instruction writes" in {
    simulate(new CsrFile()) { c =>
      val b        = CsrBench(c)
      val readOnly = implemented.filter(readOnlyByAddress)
      assert(readOnly.size == 5 + 64)
      for (addr <- readOnly; (name, op, imm) <- forms; rs1 <- Seq(0, 1, 31); data <- Seq(BigInt(0), ones)) {
        // RW always writes; RS and RC write unless the rs1 field (x0, or a zero immediate) is 0.
        // The value of the source register plays no part: x5 holding 0 still writes.
        val writes = op == CsrOp.Rw || rs1 != 0
        val a      = b.present(op, addr, rs1, rs1Data = data, imm = imm)
        assert(a.illegal == writes, s"$name on CSR ${hex(addr)} with rs1 field $rs1 and rs1 value $data")
        assert(!a.write, "a read-only CSR is never written")
      }
      // csrrw x0 and csrrwi x0 (no read wanted) are still writes
      assert(b.execute(CsrOp.Rw, Mhartid, rs1 = 0).illegal)
      assert(b.execute(CsrOp.Rw, Cycle, rs1 = 0, imm = true).illegal)
      // the reads that do not trap return the value
      c.io.time.poke("h1234_5678_9abc_def0".U)
      assert(
        b.execute(CsrOp.Rs, Time, rs1 = 0) == CsrAnswer(BigInt("9abcdef0", 16), false, false, BigInt("9abcdef0", 16))
      )
      assert(b.execute(CsrOp.Rc, Timeh, rs1 = 0, imm = true).rdata == 0x12345678)
    }
  }

  it should "decide by the instruction, not by the value, whether a writable CSR is written" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mscratch, 0xa5a5a5a5L)
      for ((name, op, imm) <- forms; rs1 <- Seq(0, 7); data <- Seq(BigInt(0), ones)) {
        val a      = b.present(op, Mscratch, rs1, rs1Data = data, imm = imm)
        val writes = op == CsrOp.Rw || rs1 != 0
        assert(!a.illegal && a.write == writes, s"$name with rs1 field $rs1")
        assert(a.rdata == 0xa5a5a5a5L, "the old value is read in every form")
      }
      b.idle()
      // csrrs and csrrc with x0 leave the CSR alone, whatever the register port carries
      b.execute(CsrOp.Rs, Mscratch, rs1 = 0, rs1Data = ones)
      b.execute(CsrOp.Rc, Mscratch, rs1 = 0, rs1Data = ones)
      b.execute(CsrOp.Rs, Mscratch, rs1 = 0, imm = true)
      b.execute(CsrOp.Rc, Mscratch, rs1 = 0, imm = true)
      assert(b.peek(Mscratch) == 0xa5a5a5a5L)
      // csrrw x0 and csrrwi x0 write
      b.execute(CsrOp.Rw, Mscratch, rs1 = 0, rs1Data = ones) // the register is x0: the core gives 0
      assert(b.peek(Mscratch) == ones, "csrrw writes rs1_data, which the core makes 0 for x0")
      b.execute(CsrOp.Rw, Mscratch, rs1 = 0, rs1Data = ones, imm = true)
      assert(b.peek(Mscratch) == 0, "csrrwi with a zero immediate writes 0")
      c.io.csr.write.expect(false.B, "not without a valid instruction")
    }
  }

  it should "compute the written value as RW, RS and RC do, from a register and from an immediate" in {
    simulate(new CsrFile()) { c =>
      val b   = CsrBench(c)
      val old = BigInt(0x0ff0f0f5L)
      val src = BigInt(0x3c3c3c1aL)
      b.write(Mscratch, old)
      val cases = Seq(
        (CsrOp.Rw, false, src, src),
        (CsrOp.Rs, false, src, old | src),
        (CsrOp.Rc, false, src, old & ~src & mask32),
        (CsrOp.Rw, true, ones, BigInt(0x1a)), // an immediate form ignores the register value
        (CsrOp.Rs, true, ones, old | 0x1a),
        (CsrOp.Rc, true, ones, old & ~BigInt(0x1a) & mask32)
      )
      for ((op, imm, data, expected) <- cases) {
        b.write(Mscratch, old)
        val a = b.execute(op, Mscratch, rs1 = 0x1a, rs1Data = data, imm = imm)
        assert(a == CsrAnswer(old, illegal = false, write = true, expected), s"$op imm=$imm")
        assert(b.peek(Mscratch) == expected, s"$op imm=$imm")
      }
      // the immediate is zero-extended: 31 sets five bits and no more
      b.write(Mscratch, 0)
      b.execute(CsrOp.Rs, Mscratch, rs1 = 31, imm = true)
      assert(b.peek(Mscratch) == 31)
    }
  }

  it should "keep 32 bits of mscratch and mtval, and bits 31:2 of mepc" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      for (value <- Seq(ones, BigInt(0x80000003L), BigInt(0x12345677L), BigInt(0))) {
        for (addr <- Seq(Mscratch, Mtval)) {
          b.write(addr, value)
          assert(b.peek(addr) == value, hex(addr))
        }
        val a = b.write(Mepc, value)
        assert(a.wdata == value, "wdata is the value before the WARL rule")
        assert(b.peek(Mepc) == (value & ~BigInt(3) & mask32))
        assert(b.mretTo() == (value & ~BigInt(3) & mask32), "mret goes to mepc")
      }
      b.write(Mepc, 0)
      b.set(Mepc, 0x7)
      assert(b.peek(Mepc) == 0x4, "csrrs cannot set bits 1:0 either")
    }
  }

  it should "keep mcause on a write of a value that is not a cause of this hart, as Sail does" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      for (sentinel <- Seq(2L, 0x80000007L); value <- SailMcause.written) {
        b.write(Mcause, sentinel)
        assert(b.peek(Mcause) == sentinel)
        val a = b.write(Mcause, value)
        assert(!a.illegal && a.write && a.wdata == value, "the write is legal and is reported with its value")
        assert(b.peek(Mcause) == (if (SailMcause.stored(value)) value else sentinel), f"mcause written with 0x$value%x")
      }
      // csrrs and csrrc are writes like any other: the result must be a legal cause
      b.write(Mcause, 19)
      b.set(Mcause, 4) // 23 is reserved
      assert(b.peek(Mcause) == 19)
      b.clear(Mcause, 16)
      assert(b.peek(Mcause) == 3)
      b.set(Mcause, BigInt(0x80000000L)) // interrupt 3
      assert(b.peek(Mcause) == 0x80000003L)
      // trap entry writes its cause whatever the register holds
      b.exception(cause = 11, epc = 0x80000000L, tval = 0)
      assert(b.peek(Mcause) == 11)
    }
  }

  it should "implement MIE and MPIE of mstatus and hardwire MPP to 3 and everything else to 0" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mstatus, ones)
      assert(b.peek(Mstatus) == 0x1888)
      b.write(Mstatus, 0)
      assert(b.peek(Mstatus) == 0x1800, "MPP stays 3")
      b.set(Mstatus, 0x8)
      assert(b.peek(Mstatus) == 0x1808)
      b.set(Mstatus, 0x80)
      b.clear(Mstatus, 0x1808)
      assert(b.peek(Mstatus) == 0x1880)
      b.write(Mstatush, ones)
      assert(b.peek(Mstatush) == 0)
      assert(!b.write(Misa, 0).illegal, "a write to misa is legal")
      assert(b.peek(Misa) == 0x40001100L, "and ignored")
      b.write(Misa, ones)
      assert(b.peek(Misa) == 0x40001100L)
    }
  }

  it should "keep the previous mode on a write of mtvec.MODE >= 2, and still write the base" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mtvec, 0x80001000L)
      assert(b.peek(Mtvec) == 0x80001000L)
      b.write(Mtvec, 0x80002002L) // mode 2: reserved
      assert(b.peek(Mtvec) == 0x80002000L, "the base is written, the mode stays direct")
      b.write(Mtvec, 0x80003003L)
      assert(b.peek(Mtvec) == 0x80003000L)
      b.write(Mtvec, 0x80004001L) // vectored
      assert(b.peek(Mtvec) == 0x80004001L)
      b.write(Mtvec, 0x80005002L)
      assert(b.peek(Mtvec) == 0x80005001L, "the mode stays vectored")
      b.write(Mtvec, 0x80006003L)
      assert(b.peek(Mtvec) == 0x80006001L)
      b.set(Mtvec, 0x2) // csrrs of bit 1 makes mode 3
      assert(b.peek(Mtvec) == 0x80006001L)
      b.write(Mtvec, ones)
      assert(b.peek(Mtvec) == 0xfffffffdL, "bit 1 reads 0")
      b.write(Mtvec, 0x80007000L)
      assert(b.peek(Mtvec) == 0x80007000L, "back to direct")
    }
  }

  it should "implement only the three interrupt bits of mie, and ignore writes to mip" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mie, ones)
      assert(b.peek(Mie) == 0x888)
      b.clear(Mie, 0x80)
      assert(b.peek(Mie) == 0x808)
      b.write(Mie, 0)
      for (value <- Seq(ones, BigInt(0x888))) {
        val a = b.write(Mip, value)
        assert(!a.illegal && a.write, "a write to mip is legal and is reported as a write")
        assert(b.peek(Mip) == 0, "mip follows the interrupt lines only")
        b.set(Mip, value)
        assert(b.peek(Mip) == 0)
      }
      CsrBench.lines(c, msip = true, mtip = true, meip = true)
      b.write(Mip, 0)
      b.clear(Mip, ones)
      assert(b.peek(Mip) == 0x888, "software cannot clear a pending bit")
    }
  }

  it should "implement mcountinhibit with mask 0x7FD" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mcountinhibit, ones)
      assert(b.peek(Mcountinhibit) == 0x7fd, "bit 1 (TM) and bits 11 to 31 are hardwired 0")
      b.write(Mcountinhibit, 0x2)
      assert(b.peek(Mcountinhibit) == 0)
      b.set(Mcountinhibit, 0x802)
      assert(b.peek(Mcountinhibit) == 0)
      for (bit <- Seq(0, 2, 3, 4, 5, 6, 7, 8, 9, 10)) {
        b.write(Mcountinhibit, BigInt(1) << bit)
        assert(b.peek(Mcountinhibit) == (BigInt(1) << bit), s"bit $bit")
      }
    }
  }

  it should "keep the low 4 bits of what is written to mhpmevent3..10" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      for (n <- 3 to 10) {
        for (event <- 0 to 15) {
          b.write(mhpmevent(n), event)
          assert(b.peek(mhpmevent(n)) == event, s"mhpmevent$n = $event")
        }
        for (value <- Seq(BigInt(16), BigInt(0x18), BigInt(0x100), BigInt(0x80000001L), ones)) {
          b.write(mhpmevent(n), 5)
          val a = b.write(mhpmevent(n), value)
          assert(!a.illegal && a.wdata == value)
          assert(b.peek(mhpmevent(n)) == (value & 0xf), s"mhpmevent$n written with $value")
        }
      }
      // the registers are separate
      for (n <- 3 to 10) b.write(mhpmevent(n), n - 2)
      for (n <- 3 to 10) assert(b.peek(mhpmevent(n)) == n - 2)
    }
  }

  it should "ignore writes to mhpmcounter11..31[h] and mhpmevent11..31 without a trap" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      for (n <- 11 to 31; addr <- Seq(mhpmcounter(n), mhpmcounterh(n), mhpmevent(n))) {
        val a = b.write(addr, ones)
        assert(!a.illegal && a.write, s"a write to CSR ${hex(addr)} is legal")
        assert(b.peek(addr) == 0, s"CSR ${hex(addr)} reads 0")
        assert(!b.set(addr, 0x1f).illegal)
        assert(b.peek(addr) == 0)
      }
      for (n <- 11 to 31; addr <- Seq(Cycle + n, Cycleh + n)) assert(b.peek(addr) == 0)
      b.step(5)
      for (n <- 11 to 31) assert(b.peek(mhpmcounter(n)) == 0)
    }
  }

  it should "show the counters at their user addresses and time from the CLINT" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mcountinhibit, 0x7fd) // everything stands still
      val pairs = Seq(Mcycle -> Cycle, Mcycleh -> Cycleh, Minstret -> Instret, Minstreth -> Instreth) ++
        (3 to 10).flatMap(n => Seq(mhpmcounter(n) -> (Cycle + n), mhpmcounterh(n) -> (Cycleh + n)))
      def value(i: Int) = BigInt(0x10000000L) * (i % 15 + 1) + i
      for (((machine, user), i) <- pairs.zipWithIndex) {
        b.write(machine, value(i))
        assert(b.peek(machine) == value(i), hex(machine))
        assert(b.peek(user) == value(i), s"${hex(user)} shows ${hex(machine)}")
      }
      // every half still holds its own value: a write to one half or counter touched no other
      for (((machine, user), i) <- pairs.zipWithIndex) {
        assert(b.peek(machine) == value(i), s"${hex(machine)} after the writes to the others")
        assert(b.peek(user) == value(i), s"${hex(user)} after the writes to the others")
      }
      // and one more write to each leaves all the others as they are
      for (((machine, _), i) <- pairs.zipWithIndex) {
        b.write(machine, ones - i)
        for (((other, user), j) <- pairs.zipWithIndex) {
          val expected = if (j < i) ones - j else if (j == i) ones - i else value(j)
          assert(
            b.peek(other) == expected && b.peek(user) == expected,
            s"${hex(other)} after a write to ${hex(machine)}"
          )
        }
      }
      c.io.time.poke("hfedc_ba98_7654_3210".U)
      assert(b.peek(Time) == BigInt("76543210", 16))
      assert(b.peek(Timeh) == BigInt("fedcba98", 16))
    }
  }

  it should "write nothing for an illegal instruction, and nothing in a cycle with a trap" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mscratch, 0x1111)
      b.write(Mepc, 0x2000)
      // illegal: there is no CSR, or it is read-only; the state is as before
      assert(b.execute(CsrOp.Rw, 0x345, rs1 = 5, rs1Data = ones).illegal)
      assert(b.execute(CsrOp.Rw, Instret, rs1 = 5, rs1Data = ones).illegal)
      assert(b.peek(Mscratch) == 0x1111 && b.peek(Minstret) == 2, "the two writes above retired, nothing else")
      // a trap in the cycle of a CSR write: the write does not happen, and is not reported
      b.present(CsrOp.Rw, Mscratch, rs1 = 5, rs1Data = 0x2222)
      c.io.csr.write.expect(true.B, "without a trap the instruction writes")
      c.io.trap.exception.poke(true.B)
      c.io.csr.write.expect(false.B, "with a trap in the cycle it does not")
      c.io.csr.illegal.expect(false.B, "illegal does not depend on the trap port")
      c.io.csr.wdata.expect(0x2222.U, "nor does the value")
      b.exception(cause = 5, epc = 0x3000, tval = 0x44)
      assert(b.peek(Mscratch) == 0x1111)
      assert(b.peek(Mepc) == 0x3000 && b.peek(Mcause) == 5 && b.peek(Mtval) == 0x44)
      // a trap in the cycle of a write to a trap CSR: the trap's value stays
      b.present(CsrOp.Rw, Mepc, rs1 = 5, rs1Data = 0x7000)
      b.exception(cause = 7, epc = 0x4000, tval = 0x55)
      assert(b.peek(Mepc) == 0x4000)
      // a trap in the cycle of an mret: mstatus is that of the trap
      b.write(Mstatus, 0x8) // MIE 1, MPIE 0
      c.io.mret.valid.poke(true.B)
      b.exception(cause = 2, epc = 0x5000, tval = 0)
      assert(b.peek(Mstatus) == 0x1880, "MPIE <- MIE, MIE <- 0: the mret did nothing")
    }
  }

  it should "write nothing, and return from nothing, in a cycle with an interrupt" in {
    simulate(new CsrFile()) { c =>
      val b = CsrBench(c)
      b.write(Mscratch, 0x1111)
      b.write(Mie, 0x8)
      CsrBench.lines(c, msip = true, mtip = false, meip = false)
      // the interrupt is taken before a CSR instruction: that instruction did not execute
      for ((addr, value) <- Seq(Mscratch -> 0x2222, Mepc -> 0x7000, Mcause -> 5, Mstatus -> 0x88, Mie -> 0)) {
        b.write(Mstatus, 0x8)
        b.present(CsrOp.Rw, addr, rs1 = 5, rs1Data = value)
        c.io.trap.interrupt.poke(true.B)
        c.io.csr.write.expect(false.B, s"CSR ${hex(addr)}: not a write of the retire record")
        b.interrupt(epc = 0x80000040L)
        assert(b.peek(Mscratch) == 0x1111 && b.peek(Mie) == 0x8, s"CSR ${hex(addr)}")
        assert(b.peek(Mepc) == 0x80000040L && b.peek(Mcause) == 0x80000003L && b.peek(Mstatus) == 0x1880)
      }
      assert(b.peek(Minstret) == 2 + 5, "the two writes of the setup and one of mstatus per round retired")
      // the interrupt is taken before an mret: mstatus is that of the trap, not of the return
      b.write(Mstatus, 0x8) // MIE 1, MPIE 0
      c.io.mret.valid.poke(true.B)
      b.interrupt(epc = 0x80000080L)
      assert(b.peek(Mstatus) == 0x1880 && b.peek(Mepc) == 0x80000080L)
      // with an exception reported for that instruction too, the interrupt is what is taken
      b.write(Mstatus, 0x8)
      b.present(CsrOp.Rw, 0x7c0, rs1 = 5, rs1Data = 1) // no such CSR: illegal
      c.io.csr.illegal.expect(true.B)
      c.io.trap.exception.poke(true.B)
      c.io.trap.cause.poke(2.U)
      b.interrupt(epc = 0x800000c0L, tval = 0x7c029073L)
      assert(b.peek(Mcause) == 0x80000003L && b.peek(Mtval) == 0 && b.peek(Mepc) == 0x800000c0L)
    }
  }
}
