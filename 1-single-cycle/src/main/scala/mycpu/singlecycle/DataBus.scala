// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package mycpu.singlecycle

import chisel3._
import chisel3.util._
import mycpu.common.MemMap

/** The data-side address decoder (D12 memory map): one port from the core, one port per device.
  *
  * `regions` lists each device's window as (base address, size in bytes); the windows do not
  * overlap. `io.devices(i)` belongs to `regions(i)`. The decoder is combinational and keeps no
  * state: the core holds its request until it is answered (`riscv.MemPort`).
  *
  * It differs from the bootcamp's `riscv.Bus` (5.8) in what it leaves to the core: it does not
  * register a request, so a device that answers in the same cycle costs no extra cycle.
  */
class DataBus(regions: Seq[(Long, Long)]) extends Module {
  require(regions.nonEmpty, "the bus needs at least one device")
  for (Seq((a, an), (b, _)) <- regions.sortBy(_._1).sliding(2))
    require(a + an <= b, f"the regions at 0x$a%08x and 0x$b%08x overlap")

  val io = IO(new Bundle {
    val core    = Flipped(new riscv.MemPort)
    val devices = Vec(regions.size, new riscv.MemPort)
  })

  val select = regions.map { case (base, size) => MemMap.in(io.core.req.bits.addr, base, size) }

  // [CA26: Exercise 1.7] Find the bug: address decode, and unmapped addresses
  // Contract: a request reaches exactly the device whose window holds its address, and that
  // device's answer (`ready`, `resp`) goes back to the core, in the same cycle if the device
  // answers in the same cycle. No device sees a request for an address outside its window. A
  // request for an address in no window is not left hanging: it is answered at once with
  // `error`, and it has no effect anywhere (bootcamp 5.8; handout §T1.5). The code below
  // compiles, but one line of it breaks this rule: find it with the specs, a retire log or a
  // waveform, fix it, and explain the fix in your report.
  for ((device, hit) <- io.devices.zip(select)) {
    device.req.valid := io.core.req.valid && hit
    device.req.bits  := io.core.req.bits
  }
  when(select.reduce(_ || _)) {
    io.core.req.ready  := Mux1H(select, io.devices.map(_.req.ready))
    io.core.resp.valid := io.core.req.valid
    io.core.resp.bits  := Mux1H(select, io.devices.map(_.resp.bits))
  }.otherwise {
    io.core.req.ready       := true.B
    io.core.resp.valid      := io.core.req.valid
    io.core.resp.bits.rdata := 0.U
    io.core.resp.bits.error := true.B
  }
}
