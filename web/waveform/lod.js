// SPDX-License-Identifier: MIT
// Level of detail: what one pixel column of a trace shows when it
// covers more than one cycle. A column is summarized by its transition count,
// and for a 1-bit field by the levels it reaches, both from the field's
// run-length form with two binary searches, so a column costs O(log runs)
// whatever the zoom. A column with a change in it is drawn as changing, so a
// pulse of one cycle stays visible in a window of any length.

import { runAt } from './data.js';

/**
 * Summaries of the columns of [start, end) (offsets into the window) over
 * `width` columns. Column c covers the cycles from floor(start + c * span) to
 * ceil(start + (c + 1) * span) - 1, span = (end - start) / width. Returns
 * typed arrays indexed by column: changes[c], the number of cycles of the
 * column whose value differs from that of the cycle before; value[c], the
 * column's one value when it holds a single run, else NaN (a 1-bit field then
 * reaches both levels); edge[c], 1 when the column's first cycle is a change.
 */
export function summarize(rle, start, end, width) {
  const changes = new Uint32Array(width);
  const value = new Float64Array(width);
  const edge = new Uint8Array(width);
  const span = (end - start) / width;
  const last = rle.length - 1;
  for (let c = 0; c < width; c++) {
    const a = Math.min(last, Math.floor(start + c * span));
    const b = Math.min(last, Math.max(a, Math.ceil(start + (c + 1) * span) - 1));
    const ra = runAt(rle, a);
    const rb = runAt(rle, b);
    edge[c] = a > 0 && rle.starts[ra] === a ? 1 : 0;
    changes[c] = rb - ra + edge[c];
    value[c] = ra === rb ? rle.vals[ra] : NaN;
  }
  return { changes, value, edge, width };
}
