// SPDX-License-Identifier: MIT
// From a UART frame to the store that wrote its byte, and back.
//
// A full-word store to TXDATA (D12: 0x1000_0000) pushes one byte into the TX
// FIFO; a store with other byte strobes is ignored by the UART, and a store
// while the FIFO is full is held on the bus and retires when the byte is
// taken. The transmitter takes the bytes in order and has taken a frame's byte
// by the first cycle of its start bit, s. Bytes pushed after it and before s
// are still in the FIFO in cycle s (uart.txCount, a state field: the count
// after the edge that begins s). So the store of the frame starting in s is
// the (txCount[s] + 1)-th most recent TXDATA store that retired before s.
// Searching for the nearest earlier store with the same byte would be wrong
// whenever a byte repeats while the FIFO holds more than one byte.

export const UART_TXDATA = 0x10000000;

/** The full-word stores to TXDATA in the window, in order: {cycle, pc, inst, addr, data, byte}. */
export function txStores(trace) {
  const out = [];
  for (let off = 0; off < trace.count; off++) {
    const r = trace.retire[off];
    const m = r && r.mem;
    if (m && m.write && m.addr === UART_TXDATA && m.bytes === 4) {
      out.push({ cycle: trace.first + off, pc: r.pc, inst: r.inst, addr: m.addr, data: m.data,
        byte: m.data & 0xff });
    }
  }
  return out;
}

/** Number of stores that retired before cycle c (stores are in cycle order). */
function storesBefore(stores, c) {
  let lo = 0;
  let hi = stores.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (stores[mid].cycle < c) lo = mid + 1; else hi = mid;
  }
  return lo;
}

/**
 * The store of a frame: {store} when it is in the window and carries the
 * frame's byte; {before: true} when it retired before the window's first
 * cycle; {mismatch: store} when the store found holds another byte (the
 * recorded run contradicts the FIFO order, which the view reports).
 */
export function storeOfFrame(trace, stores, frame) {
  const queued = trace.value('uart.txCount', frame.start);
  const k = storesBefore(stores, frame.start) - 1 - queued;
  if (k < 0) return { before: true };
  const store = stores[k];
  return store.byte === frame.byte ? { store } : { mismatch: store };
}

/**
 * Every frame's store (storeOfFrame), and the frame of every store found:
 * {ofFrame: Map frame -> result, ofStore: Map store -> frame}.
 */
export function matchFrames(trace, stores, frames) {
  const ofFrame = new Map();
  const ofStore = new Map();
  for (const f of frames) {
    const r = storeOfFrame(trace, stores, f);
    ofFrame.set(f, r);
    if (r.store) ofStore.set(r.store, f);
  }
  return { ofFrame, ofStore };
}
