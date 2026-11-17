// SPDX-License-Identifier: MIT
// The data side of the waveform view and the board: what they read from the
// host's api (README.md, "Contract") and the per-field series they draw.
//
// Cycle numbers are absolute, as in the bundle: the window holds cycles
// first .. first + count - 1, where first is the manifest's run.first.

import { hex } from './dom.js';

const BLOCK = 4096; // cycles per api.window call while loading

/** The bundle facts a view needs, from the api of lib/bundle.js. */
export function bundleInfo(api) {
  return {
    header: api.header,
    first: api.first,
    count: api.cycles(),
    end: api.last + 1,
    groups: api.groups,
    vga: Array.isArray(api.manifest.vga) ? api.manifest.vga : [],
  };
}

/**
 * The leaf fields of the groups (retire excluded: it has a record only in some
 * cycles), in schema order: {name, path, bits, sample, description}, from api.schema.
 */
function fieldsOf(schema, groups) {
  return groups.filter((g) => g !== 'retire').flatMap((g) => schema.groups.get(g).map((name) => {
    const f = schema.fields.get(name);
    return { name, path: name.split('.'), bits: f.bits, sample: f.sample ?? '', description: f.description };
  }));
}

/**
 * Run-length form of one field: starts[i] is the cycle offset at which value
 * vals[i] begins; the last run lasts to the end of the window.
 */
export function runLength(values) {
  const starts = [];
  const vals = [];
  for (let i = 0; i < values.length; i++) {
    if (i === 0 || values[i] !== values[i - 1]) {
      starts.push(i);
      vals.push(values[i]);
    }
  }
  return { starts: Uint32Array.from(starts), vals: Uint32Array.from(vals), length: values.length };
}

/** Index of the run that holds offset i (0 <= i < rle.length). */
export function runAt(rle, i) {
  let lo = 0;
  let hi = rle.starts.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (rle.starts[mid] <= i) lo = mid; else hi = mid - 1;
  }
  return lo;
}

/**
 * Every field of the window as a typed array, with its run-length form, and
 * the retire record of each cycle (or undefined).
 */
export class Trace {
  constructor(first, count, fields) {
    this.first = first;
    this.count = count;
    this.fields = fields;
    this.byName = new Map(fields.map((f) => [f.name, f]));
    this.values = new Map(fields.map((f) => [f.name, new Uint32Array(count)]));
    this.retire = new Array(count);
    this.rle = new Map();
  }

  has(name) { return this.values.has(name); }

  /** The value of field name in absolute cycle n. */
  value(name, n) { return this.values.get(name)[n - this.first]; }

  runs(name) {
    let r = this.rle.get(name);
    if (!r) {
      r = runLength(this.values.get(name));
      this.rle.set(name, r);
    }
    return r;
  }

  retireAt(n) { return this.retire[n - this.first]; }
}

/**
 * Reads the whole window through api.window in blocks, so that the host loads
 * one chunk after the other. onProgress(done, total) is called after each
 * block; isStale() true stops the load (the view was unmounted).
 */
export async function loadTrace(api, info, { onProgress, isStale } = {}) {
  const trace = new Trace(info.first, info.count, fieldsOf(api.schema, info.groups));
  const getters = trace.fields.map((f) => [trace.values.get(f.name), f.path]);
  for (let s = info.first; s < info.end; s += BLOCK) {
    const e = Math.min(s + BLOCK, info.end);
    const records = await api.window(s, e); // checked by lib/bundle.js: in order, every field of the groups
    if (isStale && isStale()) return null;
    fill(trace, getters, records, s);
    if (onProgress) onProgress(e - info.first, info.count);
  }
  return trace;
}

function fill(trace, getters, records, s) {
  for (let i = 0; i < records.length; i++) {
    const r = records[i];
    const off = s + i - trace.first;
    for (const [arr, path] of getters) {
      let v = r;
      for (const k of path) v = v[k];
      arr[off] = v;
    }
    trace.retire[off] = r.retire;
  }
}

/** Formats a value of a field of `bits` bits. */
export function formatValue(v, bits, radix) {
  return radix === 'dec' ? String(v >>> 0) : hex(v, Math.max(1, Math.ceil(bits / 4)));
}
