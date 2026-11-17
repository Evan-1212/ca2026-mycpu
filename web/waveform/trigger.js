// SPDX-License-Identifier: MIT
// The logic analyzer's trigger: the cycles in which a condition on one
// field starts to hold. Conditions: "value" (the field equals a value),
// "rise" (a 1-bit field goes from 0 to 1, or a wider one increases), "fall"
// (the reverse) and "change" (any new value). A match is the first cycle of a
// run of the field, so "value" matches where the field takes the value, not in
// every cycle that holds it; cycle 0 of the window matches "value" when it
// holds the value and never matches an edge (the cycle before it is unknown).

import { runAt } from './data.js';

export const TRIGGER_KINDS = ['value', 'rise', 'fall', 'change'];

function matches(rle, i, cond) {
  const v = rle.vals[i];
  if (cond.kind === 'value') return v === cond.value >>> 0;
  if (i === 0) return false;
  const p = rle.vals[i - 1];
  if (cond.kind === 'rise') return v > p;
  if (cond.kind === 'fall') return v < p;
  return true; // change: every run after the first begins with one
}

/**
 * The offset of the next match after offset `from` (dir 1) or the previous one
 * before it (dir -1), or null.
 */
export function findMatch(rle, from, cond, dir) {
  if (!TRIGGER_KINDS.includes(cond.kind)) throw new Error(`unknown trigger ${cond.kind}`);
  const runs = rle.starts.length;
  if (dir > 0) {
    for (let i = runAt(rle, Math.min(from, rle.length - 1)) + 1; i < runs; i++) {
      if (matches(rle, i, cond)) return rle.starts[i];
    }
    return null;
  }
  if (from <= 0) return null;
  for (let i = runAt(rle, Math.min(from, rle.length) - 1); i >= 0; i--) if (matches(rle, i, cond)) return rle.starts[i];
  return null;
}
