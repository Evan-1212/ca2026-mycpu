// SPDX-License-Identifier: MIT
// AXI4-Lite transactions of the core's data port, from the `bus` group of the
// schema (T5). Only a bundle that has the group gets this decoder; no T1 or T2
// bundle has it. A channel transfers in a cycle in which its valid and ready
// are both 1 (all bus fields are comb). AXI4-Lite has no IDs, so a manager's
// writes complete in order: the k-th AW transfer, the k-th W transfer and the
// k-th B transfer are one write, and the k-th AR and the k-th R transfer one
// read. The pairing starts at the window's first cycle; for a window that
// starts later than cycle 0 a transaction still open at that cycle would shift
// it, and the result says so.

export const AXI_FIELDS = ['aw.valid', 'aw.ready', 'aw.addr', 'w.valid', 'w.ready', 'w.data', 'w.strb',
  'b.valid', 'b.ready', 'b.resp', 'ar.valid', 'ar.ready', 'ar.addr', 'r.valid', 'r.ready', 'r.data',
  'r.resp'].map((f) => `bus.${f}`);

/** True when the trace has every field the decoder reads. */
export function hasBus(trace) {
  return AXI_FIELDS.every((f) => trace.has(f));
}

function transfers(trace, ch, pick) {
  const valid = trace.values.get(`bus.${ch}.valid`);
  const ready = trace.values.get(`bus.${ch}.ready`);
  const out = [];
  for (let i = 0; i < trace.count; i++) {
    if (valid[i] && ready[i]) out.push(Object.assign({ cycle: trace.first + i }, pick(i)));
  }
  return out;
}

/**
 * {writes, reads, aligned}: writes {addr, data, strb, resp, aw, w, b} and reads
 * {addr, data, resp, ar, r}, where aw/w/b/ar/r are the cycles of the transfers
 * (null for one not in the window: the transaction is still open at its end).
 * aligned is false when the window does not start at cycle 0.
 */
export function decodeAxi(trace) {
  if (!hasBus(trace)) throw new Error('the bundle has no bus group');
  const v = (name) => trace.values.get(`bus.${name}`);
  const aw = transfers(trace, 'aw', (i) => ({ addr: v('aw.addr')[i] }));
  const w = transfers(trace, 'w', (i) => ({ data: v('w.data')[i], strb: v('w.strb')[i] }));
  const b = transfers(trace, 'b', (i) => ({ resp: v('b.resp')[i] }));
  const ar = transfers(trace, 'ar', (i) => ({ addr: v('ar.addr')[i] }));
  const r = transfers(trace, 'r', (i) => ({ data: v('r.data')[i], resp: v('r.resp')[i] }));
  const writes = [];
  for (let k = 0; k < Math.max(aw.length, w.length, b.length); k++) {
    writes.push({ addr: aw[k]?.addr ?? null, data: w[k]?.data ?? null, strb: w[k]?.strb ?? null,
      resp: b[k]?.resp ?? null, aw: aw[k]?.cycle ?? null, w: w[k]?.cycle ?? null, b: b[k]?.cycle ?? null });
  }
  const reads = [];
  for (let k = 0; k < Math.max(ar.length, r.length); k++) {
    reads.push({ addr: ar[k]?.addr ?? null, data: r[k]?.data ?? null, resp: r[k]?.resp ?? null,
      ar: ar[k]?.cycle ?? null, r: r[k]?.cycle ?? null });
  }
  return { writes, reads, aligned: trace.first === 0 };
}
