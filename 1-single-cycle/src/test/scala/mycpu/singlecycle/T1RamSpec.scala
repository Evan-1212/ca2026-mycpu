// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import mycpu.common.MemMap
import org.scalatest.flatspec.AnyFlatSpec

/** The bootcamp RAM at the size T1 uses it (T1-02): 4 MiB at `0x8000_0000`. Every strobe pattern
  * must write exactly its bytes, at both ends of the memory and in between; firtool has dropped
  * the write masks of a memory before (bootcamp 2.8), and a masked write then writes every byte.
  */
class T1RamSpec extends AnyFlatSpec with ChiselSim {
  private val base  = MemMap.ramBase
  private val bytes = MemMap.ramBytes
  private val words = bytes / 4

  private def load(c: riscv.Ram, addr: Long, data: Long): Unit = {
    c.io.load.valid.poke(true.B)
    c.io.load.bits.addr.poke(addr.U)
    c.io.load.bits.data.poke(data.U)
    c.clock.step()
    c.io.load.valid.poke(false.B)
  }

  private def write(c: riscv.Ram, addr: Long, data: Long, strobe: Int): Unit = {
    c.io.dmem.req.valid.poke(true.B)
    c.io.dmem.req.bits.addr.poke(addr.U)
    c.io.dmem.req.bits.wdata.poke(data.U)
    c.io.dmem.req.bits.wstrb.poke(strobe.U)
    c.io.dmem.resp.valid.expect(true.B, "the RAM answers in the cycle of the request")
    c.io.dmem.resp.bits.error.expect(false.B)
    c.clock.step()
    c.io.dmem.req.valid.poke(false.B)
    c.io.dmem.req.bits.wstrb.poke(0.U)
  }

  /** Reads `addr` through the data port and through the instruction port: both see the same word. */
  private def expectWord(c: riscv.Ram, addr: Long, data: Long, what: String): Unit = {
    for (port <- Seq(c.io.dmem, c.io.imem)) {
      port.req.valid.poke(true.B)
      port.req.bits.addr.poke(addr.U)
      port.req.bits.wstrb.poke(0.U)
      port.resp.valid.expect(true.B)
      port.resp.bits.error.expect(false.B, what)
      port.resp.bits.rdata.expect(data.U, what)
      port.req.valid.poke(false.B)
    }
  }

  private def quiet(c: riscv.Ram): Unit = {
    c.io.load.valid.poke(false.B)
    for (port <- Seq(c.io.dmem, c.io.imem)) {
      port.req.valid.poke(false.B)
      port.req.bits.addr.poke(base.U)
      port.req.bits.wdata.poke(0.U)
      port.req.bits.wstrb.poke(0.U)
    }
  }

  "The 4 MiB RAM" should "write exactly the strobed bytes, for all 16 strobe patterns, across the memory" in {
    simulate(new riscv.Ram(words, base)) { c =>
      quiet(c)
      val old   = 0x44332211L
      val fresh = 0xddccbbaaL
      // the first and last word, the last word of the bootcamp's 64 KiB, and words whose index has
      // a single high bit set or all bits set but one (a dropped address bit would alias them)
      val addresses = Seq(0L, 4L, 0xfffcL, 0x10000L, 0x200000L, 0x3ffff8L, bytes - 4L).map(base + _)
      for (addr <- addresses; strobe <- 0 until 16) {
        load(c, addr, old)
        write(c, addr, fresh, strobe)
        val want = (0 until 4).map { k =>
          val source = if ((strobe >> k & 1) == 1) fresh else old
          source & (0xffL << (8 * k))
        }.sum
        expectWord(c, addr, want, f"strobe ${strobe.toBinaryString}%4s at 0x$addr%08x")
      }
    }
  }

  it should "keep every other word when one is written (no aliasing at 4 MiB)" in {
    simulate(new riscv.Ram(words, base)) { c =>
      quiet(c)
      // one word per address bit: index 0 and each power of two
      val probes = 0L +: (0 until 20).map(bit => 4L << bit)
      for ((offset, n) <- probes.zipWithIndex) load(c, base + offset, 0xa5000000L + n)
      for ((offset, n) <- probes.zipWithIndex) expectWord(c, base + offset, 0xa5000000L + n, s"probe $n")
      // byte, halfword and word stores as the core sends them, each to a different probe
      write(c, base + probes(3), 0x0000ee00L, 0x2)  // sb at offset 1
      write(c, base + probes(7), 0xbeef0000L, 0xc)  // sh at offset 2
      write(c, base + probes(20), 0x12345678L, 0xf) // sw
      val changed = Map(3 -> 0xa500ee03L, 7 -> 0xbeef0007L, 20 -> 0x12345678L)
      for ((offset, n) <- probes.zipWithIndex)
        expectWord(c, base + offset, changed.getOrElse(n, 0xa5000000L + n), s"probe $n after the stores")
    }
  }

  it should "answer an address outside its 4 MiB with an error and write nothing" in {
    simulate(new riscv.Ram(words, base)) { c =>
      quiet(c)
      load(c, base, 0x01020304L)
      load(c, base + bytes - 4, 0x05060708L)
      for (addr <- Seq(base + bytes, base - 4, 0L, MemMap.uartBase, 0xfffffffcL)) {
        c.io.dmem.req.valid.poke(true.B)
        c.io.dmem.req.bits.addr.poke(addr.U)
        c.io.dmem.req.bits.wdata.poke("hffffffff".U)
        c.io.dmem.req.bits.wstrb.poke(15.U)
        c.io.dmem.resp.valid.expect(true.B)
        c.io.dmem.resp.bits.error.expect(true.B, f"0x$addr%08x is outside the RAM")
        c.clock.step()
        c.io.dmem.req.valid.poke(false.B)
        c.io.dmem.req.bits.wstrb.poke(0.U)
      }
      expectWord(c, base, 0x01020304L, "the first word, after writes outside the RAM")
      expectWord(c, base + bytes - 4, 0x05060708L, "the last word, after writes outside the RAM")
    }
  }
}
