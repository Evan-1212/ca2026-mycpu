// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** T5-04: [[Axi4LiteRom]], driven on its AXI port by a [[RawMaster]]. */
class Axi4LiteRomSpec extends AnyFlatSpec with ChiselSim {
  import Axi4Lite.Resp._
  private val contents = Seq(0x00000297L, 0x01028293L, 0x30529073L, 0x800002b7L, 0x00028067L, 0xffffffffL, 0x0L, 0x1L)
  private val base     = mycpu.common.MemMap.bootRomBase

  private def master(c: RomBench): RawMaster = {
    val m = new RawMaster(c.io.axi, () => c.clock.step())
    m.idle()
    m
  }

  "Axi4LiteRom" should "return its contents, and 0 in the rest of its window, one cycle after the request" in {
    simulate(new RomBench(contents)) { c =>
      val m = master(c)
      for ((word, k) <- contents.zipWithIndex)
        assert(m.read(base + 4L * k) == ReadResult(word, Okay, arWait = 0, latency = 1), s"word $k")
      for (offset <- Seq(4L * contents.size, 0x800L, 0xffcL))
        assert(m.read(base + offset) == ReadResult(0, Okay, 0, 1), f"offset 0x$offset%x")
      assert(m.read(base + 4 * 3 + 2, readyAfter = 3).data == contents(3), "the word of the address, held until RREADY")
    }
  }

  it should "answer a write with SLVERR and keep its contents" in {
    simulate(new RomBench(contents)) { c =>
      val m = master(c)
      for ((awAt, wAt) <- Seq((0, 0), (0, 2), (3, 0)))
        assert(m.write(base + 4, 0x12345678L, awAt = awAt, wAt = wAt, readyAfter = awAt) == WriteResult(SlvErr, 1))
      assert(m.read(base + 4).data == contents(1))
    }
  }

  it should "deliver one word per cycle" in {
    simulate(new RomBench(contents)) { c =>
      val got = master(c).stream(contents.indices.map(k => base + 4L * k))
      assert(got.map(_._1) == contents)
      assert(got.map(_._2) == (1 to contents.size))
    }
  }

  it should "fit a window of another size, and reject contents that do not fit" in {
    simulate(new RomBench(Seq(7L, 8L), bytes = 8)) { c =>
      val m = master(c)
      assert(m.read(0).data == 7 && m.read(4).data == 8 && m.read(8).data == 7, "a window of two words")
    }
    intercept[IllegalArgumentException](new Axi4LiteRom(Seq.fill(3)(0L), bytes = 8))
    intercept[IllegalArgumentException](new Axi4LiteRom(Seq(0x1ffffffffL)))
    intercept[IllegalArgumentException](new Axi4LiteRom(Seq.empty))
  }
}
