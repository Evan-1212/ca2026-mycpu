// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.soc

import chisel3._
import chisel3.util._

/** AXI4-Lite as this SoC uses it (T5-01): 32-bit addresses, 32-bit data, four byte strobes and a
  * 2-bit response (AMBA AXI4-Lite spec §B1.1 lists the signals).
  *
  * `AxPROT` is not part of the bundles: every master of this SoC would tie it to a constant and no
  * slave reads it. A bridge to a foreign AXI4-Lite device ties `AWPROT` and `ARPROT` there
  * (3'b000 for the data master, 3'b100 for the instruction master).
  */
object Axi4Lite {
  val addrBits = 32
  val dataBits = 32
  val strbBits = dataBits / 8
  val respBits = 2

  /** The response codes of AXI4-Lite (spec §A3.4.4). `EXOKAY` (1) does not exist in AXI4-Lite. */
  object Resp {
    val Okay   = 0 // the access succeeded
    val SlvErr = 2 // the slave was reached and reports an error
    val DecErr = 3 // no slave is at the address (the interconnect answers)
  }

  /** The response of a slave that was reached: `SLVERR` with `error`, otherwise `OKAY`. */
  def resp(error: Bool): UInt = Mux(error, Resp.SlvErr.U(respBits.W), Resp.Okay.U(respBits.W))
}

/** The payload of the AW and AR channels: the address of the access. */
class Axi4LiteAddr extends Bundle {
  val addr = UInt(Axi4Lite.addrBits.W)
}

/** The payload of the W channel: the data and, per byte, whether it is written. */
class Axi4LiteWrite extends Bundle {
  val data = UInt(Axi4Lite.dataBits.W)
  val strb = UInt(Axi4Lite.strbBits.W)
}

/** The payload of the B channel: the response to a write. */
class Axi4LiteWriteResp extends Bundle {
  val resp = UInt(Axi4Lite.respBits.W)
}

/** The payload of the R channel: the word read and the response to the read. */
class Axi4LiteRead extends Bundle {
  val data = UInt(Axi4Lite.dataBits.W)
  val resp = UInt(Axi4Lite.respBits.W)
}

/** An AXI4-Lite interface as its master sees it: the five channels, each a `Decoupled` whose
  * `valid` and `bits` come from the sender and whose `ready` comes from the receiver. A slave's
  * port is `Flipped(new Axi4LiteIO)`, and a monitor's is `Input(new Axi4LiteIO)`.
  *
  * The rules every channel follows (AMBA AXI4-Lite spec §A3.2.1; bootcamp 3.2_interlude for
  * `Decoupled`): a transfer happens in a cycle with `valid` and `ready` both high; a sender does
  * not wait for `ready` before it raises `valid`, and once `valid` is high it stays high, with the
  * same payload, until the transfer. Between channels (§A3.3.1): a slave answers on R only after
  * the AR transfer, and on B only after both the AW and the W transfer of that write, which may
  * come in either order or together. AXI4-Lite has no transaction IDs, so responses come back in
  * the order of the requests.
  */
class Axi4LiteIO extends Bundle {
  val aw = Decoupled(new Axi4LiteAddr)
  val w  = Decoupled(new Axi4LiteWrite)
  val b  = Flipped(Decoupled(new Axi4LiteWriteResp))
  val ar = Decoupled(new Axi4LiteAddr)
  val r  = Flipped(Decoupled(new Axi4LiteRead))
}
