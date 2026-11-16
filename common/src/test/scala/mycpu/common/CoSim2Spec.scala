// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.common

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import riscv.{Asm, Halt, MemAccess, Ram, SingleCycleSystem}

/** The bootcamp's single-cycle core with its 64 KiB RAM, retiring through [[TrapRetire]] (no
  * traps, no CSRs): a [[RetireSystem]] to test `CoSim2` with before this repository's cores exist.
  */
class BootcampRetireSystem extends RetireSystem {
  val system = Module(new SingleCycleSystem)
  system.io.load     := io.load
  system.io.dbg.addr := 0.U
  io.halted          := system.io.halted
  val (in, out) = (system.io.retire.bits, io.retire.bits)
  io.retire.valid    := system.io.retire.valid
  out.pc             := in.pc
  out.inst           := in.inst
  out.nextPc         := in.nextPc
  out.rd             := in.rd
  out.rdValue        := in.rdValue
  out.mem.valid      := in.mem.valid
  out.mem.bits.addr  := in.mem.bits.addr
  out.mem.bits.write := in.mem.bits.write
  out.mem.bits.bytes := in.mem.bits.bytes
  out.mem.bits.data  := in.mem.bits.data
  out.halt           := in.halt
  out.trap           := false.B
  out.cause          := 0.U
  out.tval           := 0.U
  out.csr.valid      := false.B
  out.csr.bits.addr  := 0.U
  out.csr.bits.wdata := 0.U
  out.mip            := 0.U
}

/** [[BootcampRetireSystem]] with a fault, to see `CoSim2` find it: record number `at` (from 0)
  * reports `rdValue` whatever the core wrote. Every record has `mip` in its `mip` field.
  */
class FaultyRetireSystem(at: Int, rdValue: Long, mip: Long = 0) extends BootcampRetireSystem {
  io.retire.bits.mip := mip.U
  val count                 = RegInit(0.U(32.W))
  when(io.retire.valid)(count                 := count + 1.U)
  when(count === at.U)(io.retire.bits.rdValue := rdValue.U)
}

class CoSim2Spec extends AnyFlatSpec with ChiselSim {
  private val model = CoSim2.model(IssConfig.T1, size = Ram.bytes)

  private val programs = Seq(
    "fibonacci" -> """
      li    a0, 0
      li    a1, 1
      li    t0, 30
    1:
      add   a2, a0, a1
      mv    a0, a1
      mv    a1, a2
      addi  t0, t0, -1
      bnez  t0, 1b
      ecall
    """,
    "fibonacci in memory" -> """
      la    s0, fib
      li    t0, 1
      sw    zero, 0(s0)
      sw    t0, 4(s0)
      li    t1, 2
      li    t2, 24
    1:
      lw    a0, -8(s0)
      lw    a1, -4(s0)
      addi  s0, s0, 4
      lw    a0, -4(s0)
      lw    a1, 0(s0)
      add   a2, a0, a1
      sw    a2, 4(s0)
      sh    a2, 2(s0)
      lbu   a3, 3(s0)
      addi  t1, t1, 1
      blt   t1, t2, 1b
      ecall
      .data
    fib: .zero 128
    """,
    "ebreak"      -> "nop; ebreak",
    "illegal"     -> "nop; .word 0xffffffff",
    "misaligned"  -> "la t0, d; lw a0, 1(t0); .data; d: .word 0",
    "bad address" -> "li t0, 0x40000000; jr t0"
  )

  "CoSim2" should "find the bootcamp's single-cycle core in agreement with Iss on directed programs" in {
    simulate(new BootcampRetireSystem) { c =>
      for ((name, source) <- programs) {
        val result = CoSim2.check(c, MemImage(Asm(source), MemMap.ramBase), model)
        result.mismatch.foreach(d => fail(s"$name: $d"))
        assert(result.trace.last.halt.isDefined && result.cycles == result.trace.size, name)
      }
      val fib = CoSim2.check(c, MemImage(Asm(programs.head._2), MemMap.ramBase), model)
      assert(fib.trace.last.halt.contains(Halt.Ecall) && fib.trace.filter(_.rd == 12).last.rdValue == 1346269L)
    }
  }

  it should "find them in agreement on 50 random programs" in {
    simulate(new BootcampRetireSystem) { c =>
      for (seed <- 0L until 50L)
        CoSim2
          .compare(c, MemImage(AsmX(RandomProgramX(seed)), MemMap.ramBase), model)
          .foreach(d => fail(s"seed $seed: $d"))
    }
  }

  it should "load an ELF built by GCC, BSS included, and time a run" in {
    val elf = TestTools.gcc(
      "cosim2-elf",
      Map("main.S" -> """
        .text
        .globl _start
_start:
        la    t0, table
        la    t1, sums
        li    t2, 8
1:      lw    a0, 0(t0)
        lw    a1, 0(t1)
        add   a1, a1, a0
        sw    a1, 4(t1)
        addi  t0, t0, 4
        addi  t1, t1, 4
        addi  t2, t2, -1
        bnez  t2, 1b
        ecall
        .data
table:  .word 1, 2, 3, 4, 5, 6, 7, 8
        .bss
sums:   .zero 40
""")
    )
    val file  = Elf.read(elf, MemMap.ramBase, Ram.bytes)
    val image = file.image
    // the data segment: the table in the file, then sums as 40 bytes of BSS, zeros in the image
    val data  = file.segments.last
    val table = data.vaddr
    val sums  = table + 32
    assert(data.bytes.size == 32 && data.memsz == 32 + 40)
    val words = image.words.toMap
    assert((0 until 10).forall(i => words(sums + 4 * i) == 0), "the BSS is loaded as zeros")
    simulate(new BootcampRetireSystem) { c =>
      val result = CoSim2.check(c, image, model)
      assert(result.mismatch.isEmpty, result.mismatch)
      // every load, in order: table(i) = i + 1, then sums(i), which is 0 from the BSS for i = 0
      // and the sum of 1..i after that
      val loads    = result.trace.flatMap(_.mem).filterNot(_.write)
      val expected = (0 until 8).flatMap { i =>
        Seq(MemAccess(table + 4 * i, false, 4, i + 1), MemAccess(sums + 4 * i, false, 4, i * (i + 1) / 2))
      }
      assert(loads == expected)
      CoSim2.load(c, image)
      val (trace, cycles) = CoSim2.timed(c)
      assert(trace == result.trace && cycles == trace.size, "single-cycle: one record per cycle")
    }
  }

  it should "refuse an image whose entry point is not the reset pc" in {
    simulate(new BootcampRetireSystem) { c =>
      val image = MemImage(MemMap.ramBase + 4, MemImage(Asm("nop; nop; ecall"), MemMap.ramBase).words)
      val e     = intercept[IllegalArgumentException](CoSim2.check(c, image, model))
      assert(e.getMessage.contains("entry point 0x80000004") && e.getMessage.contains("reset pc 0x80000000"))
    }
  }

  it should "report a difference with both records disassembled and the records before it" in {
    simulate(new BootcampRetireSystem) { c =>
      val image = MemImage(AsmX("li a0, 6; li a1, 7; nop; mul a2, a0, a1; ecall"), MemMap.ramBase)
      // a model with M executes the mul; the RV32I core halts on it as illegal
      val report =
        CoSim2.compare(c, image, CoSim2.model(IssSpec.onlyMul, size = Ram.bytes)).getOrElse(fail("no mismatch"))
      assert(report.startsWith("instruction 3 (cycle 4):"), report)
      assert(
        report.contains("halt: Illegal") && report.contains("mul a2, a0, a1") && report.contains("a2 <- 0x0000002a"),
        report
      )
      for (
        field <- Seq(
          "nextPc: core 0x8000000c, model 0x80000010",
          "rd: core 0, model 12",
          "rdValue: core 0x00000000, model 0x0000002a",
          "halt: core Illegal, model none"
        )
      ) assert(report.contains(field), report)
      assert(report.contains("before it:") && report.contains("addi a1, zero, 7"), report)
    }
  }

  it should "name a field that differs when the two records print alike" in {
    val image = MemImage(Asm("li a0, 6; li a1, 7; nop; add a2, a0, a1; ecall"), MemMap.ramBase)
    // record 2 is the nop: rd = x0, where the faulty core reports an rdValue of 5
    simulate(new FaultyRetireSystem(at = 2, rdValue = 5)) { c =>
      val result = CoSim2.check(c, image, model)
      val report = result.mismatch.getOrElse(fail("no mismatch"))
      assert(result.trace.size == 2 && report.startsWith("instruction 2 (cycle 3):"), report)
      val Seq(core, golden) =
        Seq("core:", "model:").map(k => report.linesIterator.find(_.contains(k)).get.split(k)(1).trim)
      assert(core == golden, "the two records print alike")
      assert(report.contains("fields that differ:\n    rdValue: core 0x00000005, model 0x00000000"), report)
    }
    // the same program on a core whose fault is never reached: the `mip` field of the record is
    // read (0x888), which a model without interrupts has no use for
    simulate(new FaultyRetireSystem(at = 100, rdValue = 5, mip = 0x888)) { c =>
      CoSim2.load(c, image)
      val li = IssRetire(MemMap.ramBase, 0x00600513, MemMap.ramBase + 4, rd = 10, rdValue = 6)
      assert(CoSim2.observe(c) == CoSimCycle(Some(li), mip = 0x888))
      assert(CoSim2.compare(c, image, model).isEmpty)
    }
  }
}

/** The parts of `CoSim2` that need no golden model with Zicsr: the hardware sample, the counter
  * bounds and the halt causes. The replay of T3 records (traps, interrupts, sampled counters) is
  * `3-csr-trap`'s `CoSim2ReplaySpec`, as the rules of Zicsr are T3's.
  */
class CoSim2UnitSpec extends AnyFlatSpec {
  "HardwareSample" should "give the hardware's values, and recover a counter read through rd = x0" in {
    val s   = new HardwareSample
    val iss = new Iss(IssConfig.T3, devices = Iss.simDevices(clint = false), sample = s)
    s.observe(IssRetire(0, 0, 0, rd = 5, rdValue = 3), mip = 0x80, cycle = 4)
    assert(s.mip(0) == 0x80 && s.csrRead(Csr.Mcycle, 7, 's', 0) == 3 && s.error.isEmpty)
    s.agreed(iss)
    // rd = x0: csrrs with 0xc wrote 0xf, so the old value was 3 outside those bits; mcycle is 3 to 8
    s.observe(IssRetire(0, 0, 0, csr = Some(CsrWrite(Csr.Mcycle, 0xf))), mip = 0, cycle = 5)
    assert(s.csrRead(Csr.Mcycle, 7, 's', 0xc) == 3 && s.error.isEmpty)
    assert(s.csrRead(Csr.Time, 7, 's', 0) == 7 && s.csrRead(Csr.Mcycle, 7, 'w', 0xf) == 7, "not read: the model's")
    // the same record as a csrrc of 0x30: an old value of 0xf or 0x1f or ..., more than mcycle can be
    assert(s.csrRead(Csr.Mcycle, 7, 'c', 0x30) == 0xf && s.error.exists(_.contains("in its bits 0xffffffcf")), s.error)
    s.observe(IssRetire(0, 0, 0, csr = Some(CsrWrite(Csr.Mcycle, 0xff0f))), mip = 0, cycle = 5)
    s.agreed(iss) // mcycle is now 0xff0f
    s.observe(IssRetire(0, 0, 0, rd = 5, rdValue = 0xff0e), mip = 0, cycle = 6)
    assert(s.csrRead(Csr.Mcycle, 0, 's', 0) == 0xff0e)
    assert(s.error.exists(_.contains("between 0xff0f and 0xff11")), s.error)
    val status = MemAccess(MemMap.uartBase + MemMap.Uart.Status, write = false, 4, 3)
    s.observe(IssRetire(0, 0, 0, mem = Some(status)), mip = 0, cycle = 7)
    assert(s.error.isEmpty, "a new record")
    assert(s.load(status.addr, 4, ReadKind.Input(7), 1) == 3 && s.load(status.addr - 4, 4, ReadKind.Input(7), 9) == 9)
    // a WFI that waited: what woke it must show at the next record
    assert(s.wfiWait(0x080, Some(5)) == 0 && s.error.isEmpty)
    s.observe(IssRetire(0, 0, 0), mip = 0x800, cycle = 9)
    assert(s.error.exists(_.contains("the WFI before this record retired")), s.error)
    s.observe(IssRetire(0, 0, 0), mip = 0, cycle = 10)
    assert(s.wfiWait(0x080, None) == 0 && s.error.isEmpty)
    s.observe(IssRetire(0, 0, 0), mip = 0x880, cycle = 11)
    assert(s.error.isEmpty)
  }

  "CounterBound" should "keep a counter between its last value and that plus the cycles since" in {
    val b = new CounterBound
    assert(b.read(high = false, 0, cycle = 1, since = 0).isEmpty && b.read(high = false, 3, 2, 1).isDefined)
    assert(b.read(high = false, 10, cycle = 10, since = 9).isEmpty)
    assert(b.read(high = false, 9, 11, 10).isDefined && b.read(high = false, 13, 11, 10).isDefined)
    assert(b.read(high = true, 0, 11, 10).isEmpty && b.read(high = true, 1, 11, 10).isDefined)
    // a write to the low half, then a carry into the high half 6 cycles later
    b.write(high = false, 0xfffffffdL, cycle = 12, since = 11)
    assert(b.read(high = true, 1, 13, 12).isDefined && b.read(high = false, 0xfffffffeL, 13, 12).isEmpty)
    assert(b.read(high = false, 3, 18, 17).isEmpty && b.read(high = true, 1, 19, 18).isEmpty)
    assert(b.read(high = true, 0, 20, 19).isDefined && b.read(high = false, 2, 20, 19).isDefined)
    // a write to the high half keeps the low half; the counter wraps at 2^64
    b.write(high = true, 0xffffffffL, cycle = 21, since = 20)
    assert(b.read(high = true, 0xffffffffL, 22, 21).isEmpty && b.read(high = false, 4, 22, 21).isEmpty)
    b.write(high = false, 0xffffffffL, 23, 22)
    assert(b.read(high = true, 0, 25, 24).isEmpty && b.read(high = false, 1, 26, 25).isEmpty)
    assert(b.read(high = false, 0, 27, 26).isDefined)
    // some bits of a half: the counter is 2^64 + 1 to 2^64 + 3
    assert(b.read(high = false, 0x10002, 27, 26, mask = 0xfffe).isEmpty && b.read(high = false, 1, 27, 26).isDefined)
    assert(b.read(high = false, 4, 28, 27, mask = 0xc).isEmpty && b.read(high = false, 0, 28, 27, mask = 0xc).isDefined)
  }

  it should "find the smallest value from a bound on that has given bits" in {
    val rnd = new scala.util.Random(1)
    for (_ <- 0 until 2000) {
      val (a, m) = (BigInt(rnd.nextInt(4096)), BigInt(rnd.nextInt(256)) << rnd.nextInt(5))
      val t      = BigInt(rnd.nextInt(4096)) & m
      val x      = CounterBound.atLeast(a, m, t)
      assert(x == Iterator.iterate(a)(_ + 1).find(v => (v & m) == t).get, s"a $a, m $m, t $t")
    }
  }

  "CoSim2" should "refuse a halt cause that the core's enumeration does not define" in {
    import riscv.HaltCause
    assert(CoSim2.haltOf(HaltCause.Running.litValue).isEmpty)
    assert(CoSim2.haltOf(HaltCause.Ebreak.litValue).contains(Halt.Ebreak))
    assert(intercept[IllegalStateException](CoSim2.haltOf(7)).getMessage.contains("halt cause 7"))
  }
}
