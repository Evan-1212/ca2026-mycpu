// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.util._

/** The RAM of the SoC as an AXI4-Lite slave (T5-04): `words` 32-bit words with byte strobes.
  *
  * '''Two ports''', one per master, so the instruction master and the data master never wait
  * for each other: `io.i` is read-only (a write to it is answered `SLVERR` and changes nothing)
  * and `io.d` reads and writes. The slave uses address bits [log2(words) + 1 : 2] only; the
  * crossbar sends it nothing outside its window.
  *
  * '''Reads''' ([[Axi4LiteReadChannel]]): R is valid one cycle after the AR transfer, plus the
  * wait states, in both read modes, and a new AR is taken in the cycle of the R transfer. With no
  * wait states a master that keeps `RREADY` high and offers the next address at once gets
  * '''one word per cycle''': n sequential words take n + 1 cycles, the first AR included. With w
  * wait states every word takes w + 1 cycles.
  *   - `syncRead = false`: the memory is read combinationally in the AR cycle and the word is
  *     registered (a `Mem`; on an FPGA this is distributed RAM or flip-flops).
  *   - `syncRead = true`: the memory has a synchronous read port (a `SyncReadMem`, the block RAM
  *     of an FPGA): the address is registered at the AR transfer and the word comes out of the
  *     memory in the next cycle, which is the R cycle.
  * The modes differ in one case only, a read whose AR transfer falls in the cycle a write to the
  * same word is performed (through the other port): the combinational read returns the old word,
  * the synchronous read the new one, because firtool builds a `SyncReadMem` with a registered read
  * address, whatever read-under-write behavior is asked for (checked with firtool 1.160.0).
  *
  * '''Writes''' ([[Axi4LiteWriteChannel]]): AW and W in either order; the memory is written in
  * the cycle the later of the two is transferred, and B is valid one cycle after that, plus the
  * wait states. A read whose AR transfer comes after that cycle sees the new data, so a load that
  * follows a store's B response is always correct.
  *
  * '''Wait states''' (`waitStates`) delay every R and B: by a fixed number of cycles, or by a
  * random number drawn from an LFSR that starts from `io.seed` (the input exists only then; the
  * harness holds it stable while reset is asserted).
  *
  * '''Load port''': while the core is held in reset, the harness writes the program through
  * `io.load`, one word per cycle, as with the bootcamp's `riscv.Ram`. A load takes priority over a
  * write in the same cycle (which is then lost; there is none during reset).
  *
  * @param words
  *   the number of 32-bit words, a power of two
  * @param syncRead
  *   whether the memory is read synchronously (see above)
  * @param waitStates
  *   the extra latency of every response
  */
class Axi4LiteRam(words: Int, syncRead: Boolean, waitStates: WaitStates = WaitStates.Fixed(0)) extends Module {
  require(isPow2(words) && words >= 2, s"$words words: the RAM size must be a power of two")
  val io = IO(new Bundle {
    val i    = Flipped(new Axi4LiteIO)
    val d    = Flipped(new Axi4LiteIO)
    val load = Input(Valid(new riscv.LoadPort))
    val seed = waitStates match {
      case WaitStates.Random(_) => Some(Input(UInt(16.W)))
      case WaitStates.Fixed(_)  => None
    }
  })
  private val waits             = new WaitStates.Source(waitStates, io.seed, reset)
  private def index(addr: UInt) = addr(log2Ceil(words) + 1, 2)
  private def bytes(word: UInt) = word.asTypeOf(Vec(4, UInt(8.W)))
  private val sync_mem          = Option.when(syncRead)(SyncReadMem(words, Vec(4, UInt(8.W)), SyncReadMem.WriteFirst))
  private val comb_mem          = Option.when(!syncRead)(Mem(words, Vec(4, UInt(8.W))))

  // reads: channel 0 of the wait source for I, channel 1 for D
  for ((port, channel) <- Seq(io.i, io.d).zipWithIndex) {
    val read = Module(new Axi4LiteReadChannel(syncRead))
    read.io.ar <> port.ar
    read.io.r <> port.r
    read.io.waits := waits(channel)
    val at = index(read.io.read.bits)
    read.io.data := sync_mem.map(_.read(at, read.io.read.valid)).getOrElse(comb_mem.get(at)).asUInt
  }

  // writes: D's are performed, I's are refused
  val d_write = Module(new Axi4LiteWriteChannel)
  d_write.io.aw <> io.d.aw
  d_write.io.w <> io.d.w
  d_write.io.b <> io.d.b
  d_write.io.error.get := false.B
  d_write.io.waits.get := waits(2)
  val i_write = Module(new Axi4LiteWriteChannel(refuse = true))
  i_write.io.aw <> io.i.aw
  i_write.io.w <> io.i.w
  i_write.io.b <> io.i.b

  // One write port for the load port and the stores: firtool drops the masks of a memory whose
  // other write port has none (see the bootcamp's `riscv.Ram`).
  val store = d_write.io.write.get
  val addr  = Mux(io.load.valid, io.load.bits.addr, store.bits.addr)
  val data  = Mux(io.load.valid, io.load.bits.data, store.bits.wdata)
  val strb  = Mux(io.load.valid, "b1111".U, store.bits.wstrb)
  when(io.load.valid || store.valid) {
    sync_mem.getOrElse(comb_mem.get).write(index(addr), bytes(data), strb.asBools)
  }
}
