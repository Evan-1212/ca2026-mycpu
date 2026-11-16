// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.rv32im

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.Valid
import chisel3.util.experimental.BoringUtils
import mycpu.common.{AsmX, MemMap, TrapRetire}
import org.scalatest.flatspec.AnyFlatSpec
import riscv.HaltCause

/** The T2 core with two of its internal signals brought out, for [[T2CpuSpec]]: the register
  * file's write enable and the divider's `busy`.
  */
class CpuProbe(latency: Int = Divider.DIV_LATENCY) extends Module {
  val io = IO(new Bundle {
    val imem     = new riscv.MemPort
    val dmem     = new riscv.MemPort
    val retire   = Output(Valid(new TrapRetire))
    val halted   = Output(Bool())
    val reg_wen  = Output(Bool())
    val div_busy = Output(Bool())
  })
  val cpu = Module(new CPU(divLatency = latency))
  io.imem <> cpu.io.imem
  io.dmem <> cpu.io.dmem
  io.retire   := cpu.io.retire
  io.halted   := cpu.io.halted
  io.reg_wen  := BoringUtils.tapAndRead(cpu.regs.io.wen)
  io.div_busy := BoringUtils.tapAndRead(cpu.execute.divider.io.busy)
}

/** The core on its own two ports, with the test as the memory (T2-04): what the stall holds, in
  * every cycle. The system-level specs compare what retires; only here can a cycle in which
  * nothing retires be looked at: the PC, the register file's write enable, the data port and the
  * divider's `busy`.
  */
class T2CpuSpec extends AnyFlatSpec with ChiselSim {
  private val base     = MemMap.ramBase
  private val loadData = 0xcafef00dL // what the test's memory answers every load with

  /** A program for the test's memory: no branches, registers written before they are read, loads
    * and stores at small addresses off x0.
    */
  private val source = """
      addi x1, x0, 1000
      addi x2, x0, 7
      sw   x1, 16(x0)        # a store right before a division
      div  x3, x1, x2
      sw   x3, 20(x0)        # and one right after, of its result
      lw   x4, 24(x0)
      rem  x1, x1, x2        # rd is rs1
      div  x1, x1, x2        # again, on the new value
      div  x0, x4, x2        # a result that nobody reads
      divu x5, x4, x2        # divisions back to back
      remu x5, x5, x2
      div  x8, x4, x0        # by zero
      rem  x9, x4, x0
      lui  x6, 0x80000
      addi x7, x0, -1
      div  x10, x6, x7       # the signed overflow
      rem  x11, x6, x7
      divu x12, x6, x7       # no overflow when unsigned
      mul  x13, x4, x2
      lw   x14, 28(x0)       # a load right before a division
      div  x15, x14, x2
      sw   x15, 32(x0)
      ecall
  """
  private val words  = AsmX(source)

  /** One instruction of the program as the core must execute it. */
  private case class Step(
      index: Int,
      name: String,
      rd: Int,          // the register it changes, 0 if none
      value: Long,      // and the value
      stall: Int,       // cycles it waits for the divider
      access: Boolean,  // whether it is a load or a store
      division: Boolean // whether it goes to the divider
  ) {
    val pc: Long = base + 4L * index
  }

  /** The steps of the program for a divider of the latency `latency`, worked out here from the
    * specification ([[MModel]]): no golden model of the core is involved.
    */
  private def steps(latency: Int): Seq[Step] = {
    val x = new Array[Int](32)
    words.zipWithIndex.map { case (word, index) =>
      val i                   = AsmX.decode(word).getOrElse(fail(f"0x$word%08x"))
      val (a, b)              = (x(i.rs1), x(i.rs2))
      val division            = MModel.divNames.contains(i.name)
      val value: Option[Long] = i.name match {
        case "addi"                 => Some((a + i.imm) & 0xffffffffL)
        case "lui"                  => Some(i.imm & 0xffffffffL)
        case "lw"                   => Some(loadData)
        case "sw" | "ecall"         => None
        case name if AsmX.isM(name) => Some(MModel.result(name, a, b).toLong)
        case other                  => fail(s"the program has no $other")
      }
      val wait = if (division) Division(i.name, a, b).latency(latency) else 0
      val rd   = if (value.isDefined) i.rd else 0
      if (rd != 0) x(rd) = value.get.toInt
      Step(index, i.name, rd, if (rd == 0) 0 else value.get, wait, i.name == "lw" || i.name == "sw", division)
    }
  }

  /** What the test's memory always does: it answers every fetch, and never with an error. */
  private def connectMemory(c: CpuProbe): Unit = {
    c.io.imem.req.ready.poke(true.B)
    c.io.imem.resp.valid.poke(true.B)
    c.io.imem.resp.bits.error.poke(false.B)
    c.io.dmem.resp.bits.rdata.poke(loadData.U(32.W))
    c.io.dmem.resp.bits.error.poke(false.B)
  }

  /** Runs the program with a data port that answers each access after `dataWait` cycles, and
    * checks every cycle against `steps`.
    */
  private def check(c: CpuProbe, latency: Int, dataWait: Int): Int = {
    val program = steps(latency)
    assert(program.count(_.stall == latency) == 8 && program.count(_.stall == Divider.EARLY_OUT_LATENCY) == 4)
    connectMemory(c)
    var cycle = 0
    for (step <- program) {
      val text  = s"${step.name} at index ${step.index}"
      val total = step.stall + (if (step.access) dataWait else 0)
      for (waited <- 0 to total) {
        val at   = s"$text, cycle $waited of $total (cycle $cycle of the run)"
        val last = waited == total
        // the PC holds: the same instruction is requested until it retires
        c.io.imem.req.valid.expect(true.B, at)
        c.io.imem.req.bits.addr.expect(step.pc.U, s"$at: the PC")
        c.io.imem.resp.bits.rdata.poke(words(step.index).U(32.W))
        // the data port carries the access of a load or a store for as long as it waits, and
        // nothing in any other cycle: no access is repeated, and none is made early
        c.io.dmem.req.valid.expect(step.access.B, s"$at: the data port")
        c.io.dmem.req.ready.poke(last.B)
        c.io.dmem.resp.valid.poke((step.access && last).B)
        // the divider is busy from the cycle after it took the request to the cycle it answers in
        c.io.div_busy.expect((step.division && waited > 0).B, s"$at: the divider's busy")
        // nothing retires and no register is written until the instruction is over
        c.io.retire.valid.expect(last.B, s"$at: retire")
        c.io.reg_wen.expect((last && step.rd != 0).B, s"$at: the register file's write enable")
        c.io.halted.expect(false.B, at)
        if (last) {
          val r = c.io.retire.bits
          r.pc.expect(step.pc.U, at)
          r.inst.expect(words(step.index).U, at)
          r.rd.expect(step.rd.U, s"$at: rd")
          r.rdValue.expect(step.value.U(32.W), s"$at: the value")
          r.mem.valid.expect(step.access.B, at)
          r.halt.expect(if (step.name == "ecall") HaltCause.Ecall else HaltCause.Running, at)
          r.nextPc.expect((if (step.name == "ecall") step.pc else step.pc + 4).U, at)
        }
        c.clock.step()
        cycle += 1
      }
    }
    c.io.halted.expect(true.B)
    c.io.div_busy.expect(false.B)
    cycle
  }

  for (latency <- Divider.latencies)
    s"The T2 core (DIV_LATENCY = $latency)" should
      "hold the PC, the registers and the data port while the divider is busy, and retire each division once" in {
        simulate(new CpuProbe(latency)) { c =>
          val cycles = check(c, latency, dataWait = 0)
          // 23 instructions, eight divisions of the full latency and four that are answered early
          assert(cycles == 23 + 8 * latency + 4 * Divider.EARLY_OUT_LATENCY)
          info(s"23 instructions in $cycles cycles")
        }
      }

  it should "do so next to a data port that makes loads and stores wait" in {
    for (latency <- Divider.latencies)
      simulate(new CpuProbe(latency)) { c =>
        val cycles = check(c, latency, dataWait = 3)
        assert(cycles == 23 + 8 * latency + 4 * Divider.EARLY_OUT_LATENCY + 5 * 3, "5 accesses wait 3 cycles each")
      }
  }

  it should "start no division while reset is held or before the fetch is answered" in {
    for (latency <- Divider.latencies)
      simulate(new CpuProbe(latency)) { c =>
        val div = AsmX("div x3, x0, x0").head // x0 is the one register with a known value: 0 / 0
        connectMemory(c)
        c.io.imem.resp.bits.rdata.poke(div.U(32.W))
        c.io.dmem.req.ready.poke(true.B)
        c.io.dmem.resp.valid.poke(false.B)
        // a division at the reset vector, with reset held: the divider must not start
        c.reset.poke(true.B)
        for (_ <- 0 until 5) {
          c.io.div_busy.expect(false.B, "the divider is idle in reset")
          c.io.retire.valid.expect(false.B)
          c.clock.step()
        }
        c.reset.poke(false.B)
        // out of reset, but the memory has not answered the fetch yet
        c.io.imem.resp.valid.poke(false.B)
        for (_ <- 0 until latency + 3) {
          c.io.div_busy.expect(false.B, "no instruction yet: the divider is idle")
          c.io.retire.valid.expect(false.B)
          c.clock.step()
        }
        // from the cycle of the answer: accepted, busy for one cycle (0 / 0 is answered early), retired
        c.io.imem.resp.valid.poke(true.B)
        c.io.div_busy.expect(false.B)
        c.io.retire.valid.expect(false.B)
        c.clock.step()
        c.io.div_busy.expect(true.B)
        c.io.retire.valid.expect(true.B, "0 / 0 retires one cycle after it was issued")
        c.io.retire.bits.rdValue.expect("hffffffff".U)
        c.io.reg_wen.expect(true.B)
        c.clock.step()
        c.io.imem.req.bits.addr.expect((base + 4).U, "the PC moves once")
      }
  }
}
