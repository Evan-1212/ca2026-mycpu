// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.util._

/** The boot ROM of the SoC as an AXI4-Lite slave (T5-04, T5-05).
  *
  * The contents are constants of the design, built into it at elaboration with `VecInit`: no
  * `$readmemh`, so the model does not depend on a file at run time. Word k of `contents` is at
  * offset 4k of the window; the rest of the window reads 0. The slave uses address bits
  * [log2(bytes) - 1 : 2] only.
  *
  * A read ([[Axi4LiteReadChannel]], combinational data, no wait states) is answered one cycle
  * after its AR transfer, and a new AR is taken in the cycle of the R transfer: one word per
  * cycle. A write ([[Axi4LiteWriteChannel]]) is answered with `SLVERR`.
  *
  * @param contents
  *   the words, each in 0 to 0xFFFF_FFFF
  * @param bytes
  *   the size of the window, a power of two (D12: 4 KiB)
  */
class Axi4LiteRom(contents: Seq[Long], bytes: Int = 0x1000) extends Module {
  require(isPow2(bytes) && bytes >= 8, s"$bytes bytes: the ROM window must be a power of two")
  require(contents.nonEmpty && contents.size <= bytes / 4, s"${contents.size} words do not fit in $bytes bytes")
  require(contents.forall(w => w >= 0 && w <= 0xffffffffL), "a ROM word is 32 bits")
  val io = IO(new Bundle {
    val axi = Flipped(new Axi4LiteIO)
  })

  // The table holds the words rounded up to a power of two; the rest of the window reads 0.
  private val indexBits = log2Ceil(contents.size.max(2))
  val table             = VecInit(contents.padTo(1 << indexBits, 0L).map(_.U(32.W)))

  val read = Module(new Axi4LiteReadChannel(syncData = false))
  read.io.ar <> io.axi.ar
  read.io.r <> io.axi.r
  read.io.waits := 0.U
  private val offset = read.io.read.bits(log2Ceil(bytes) - 1, 2) // the word in the window
  read.io.data := (
    if (1 << indexBits == bytes / 4) table(offset)
    else Mux(offset(offset.getWidth - 1, indexBits) === 0.U, table(offset(indexBits - 1, 0)), 0.U)
  )

  val write = Module(new Axi4LiteWriteChannel(refuse = true))
  write.io.aw <> io.axi.aw
  write.io.w <> io.axi.w
  write.io.b <> io.axi.b
}
