// SPDX-License-Identifier: MIT
// Drawing of the waveform canvas. All positions are in CSS pixels and
// all cycles are offsets into the window; x(c) maps cycle offset c to the left
// edge of its slot, so cycle c is drawn over [x(c), x(c + 1)).

import { summarize } from './lod.js';
import { formatValue, runAt } from './data.js';
import { charLabel } from './dom.js';

export const RULER_H = 22;
export const ROW_H = 26;

/** The view's mapping between cycle offsets and x. */
export function viewport(start, end, width) {
  const scale = width / (end - start); // pixels per cycle
  return {
    start, end, width, scale,
    x: (c) => (c - start) * scale,
    cycleAt: (x) => start + x / scale,
  };
}

function drawRuler(ctx, vp, colors, first) {
  ctx.fillStyle = colors.ruler;
  ctx.fillRect(0, 0, vp.width, RULER_H);
  ctx.fillStyle = colors.text;
  ctx.strokeStyle = colors.grid;
  ctx.font = '11px ui-monospace, monospace';
  ctx.textBaseline = 'middle';
  const raw = 90 / vp.scale; // about one label per 90 px
  const pow = 10 ** Math.floor(Math.log10(Math.max(raw, 1)));
  const step = [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= raw) || pow * 10;
  const firstTick = Math.ceil(vp.start / step) * step;
  ctx.beginPath();
  for (let c = firstTick; c <= vp.end; c += step) {
    const x = Math.round(vp.x(c)) + 0.5;
    ctx.moveTo(x, RULER_H - 6);
    ctx.lineTo(x, RULER_H);
    ctx.fillText(String(first + c), x + 3, RULER_H / 2 - 2);
  }
  ctx.stroke();
}

function drawBit(ctx, row, vp, colors) {
  const top = row.y + 5;
  const bot = row.y + row.h - 5;
  const yOf = (v) => (v ? top : bot);
  ctx.strokeStyle = colors.bit;
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  const rle = row.rle;
  if (vp.scale >= 1) { // a cycle is a pixel or more: every run is drawn
    let i = runAt(rle, Math.max(0, Math.floor(vp.start)));
    let prevY = null;
    for (; i < rle.starts.length && rle.starts[i] < vp.end; i++) {
      const x0 = Math.max(0, vp.x(rle.starts[i]));
      const next = i + 1 < rle.starts.length ? rle.starts[i + 1] : rle.length;
      const x1 = Math.min(vp.width, vp.x(next));
      const y = yOf(rle.vals[i]);
      if (prevY === null) ctx.moveTo(x0, y); else ctx.lineTo(x0, y);
      ctx.lineTo(x1, y);
      prevY = y;
    }
  } else { // level of detail: one summary per pixel column
    const cols = summarize(rle, vp.start, vp.end, Math.ceil(vp.width));
    for (let c = 0; c < cols.width; c++) {
      const v = cols.value[c];
      if (Number.isNaN(v) || cols.edge[c]) { // both levels in the column: a full-height bar, so no pulse is hidden
        ctx.moveTo(c + 0.5, top);
        ctx.lineTo(c + 0.5, bot);
      }
      if (!Number.isNaN(v)) {
        ctx.moveTo(c, yOf(v));
        ctx.lineTo(c + 1, yOf(v));
      }
    }
  }
  ctx.stroke();
}

// the labels are monospace: one measurement gives the width of every label
let charWidth = 0;

function busSegment(ctx, x0, x1, top, bot, label, colors) {
  const mid = (top + bot) / 2;
  const e = Math.min(3, (x1 - x0) / 2);
  ctx.beginPath();
  ctx.moveTo(x0, mid);
  ctx.lineTo(x0 + e, top);
  ctx.lineTo(x1 - e, top);
  ctx.lineTo(x1, mid);
  ctx.lineTo(x1 - e, bot);
  ctx.lineTo(x0 + e, bot);
  ctx.closePath();
  ctx.stroke();
  if (label !== null && x1 - x0 > 16) {
    if (!charWidth) charWidth = ctx.measureText('0').width;
    const w = label.length * charWidth;
    if (w + 8 <= x1 - x0) {
      ctx.fillStyle = colors.text;
      ctx.fillText(label, (x0 + x1 - w) / 2, mid + 0.5);
    }
  }
}

function drawBus(ctx, row, vp, colors) {
  const top = row.y + 4;
  const bot = row.y + row.h - 4;
  const rle = row.rle;
  ctx.strokeStyle = colors.bus;
  ctx.lineWidth = 1;
  ctx.font = '11px ui-monospace, monospace';
  ctx.textBaseline = 'middle';
  const label = (v) => formatValue(v, row.bits, row.radix);
  if (vp.scale >= 1) {
    for (let i = runAt(rle, Math.max(0, Math.floor(vp.start))); i < rle.starts.length && rle.starts[i] < vp.end; i++) {
      const next = i + 1 < rle.starts.length ? rle.starts[i + 1] : rle.length;
      busSegment(ctx, vp.x(rle.starts[i]), vp.x(next), top, bot, label(rle.vals[i]), colors);
    }
    return;
  }
  // level of detail: constant columns merge into labeled segments, changing columns into a band
  const cols = summarize(rle, vp.start, vp.end, Math.ceil(vp.width));
  let segStart = 0;
  let segValue = null;
  const flush = (end) => {
    if (segValue !== null && end > segStart) busSegment(ctx, segStart, end, top, bot, label(segValue), colors);
    segValue = null;
  };
  ctx.fillStyle = colors.busy;
  for (let c = 0; c < cols.width; c++) {
    const v = cols.value[c];
    if (Number.isNaN(v)) { // several values in the column: a filled band
      flush(c);
      ctx.fillStyle = colors.busy;
      ctx.fillRect(c, top, 1, bot - top);
    } else if (cols.edge[c] || v !== segValue) {
      flush(c);
      segStart = c;
      segValue = v;
    }
  }
  flush(cols.width);
}

function drawUart(ctx, row, vp, colors, frames, selected, first) {
  const top = row.y + 3;
  const bot = row.y + row.h - 3;
  ctx.font = '11px ui-monospace, monospace';
  ctx.textBaseline = 'middle';
  for (const f of frames) {
    const start = f.start - first;
    const end = f.end - first;
    if (end < vp.start || start > vp.end) continue;
    const x0 = vp.x(start);
    const x1 = vp.x(end + 1);
    ctx.fillStyle = f === selected ? colors.select : f.ok ? colors.frame : colors.error;
    ctx.fillRect(x0, top, Math.max(1, x1 - x0), bot - top);
    if (vp.scale >= 0.5) { // the ten sample points
      ctx.fillStyle = colors.text;
      for (const s of f.samples) ctx.fillRect(vp.x(s - first + 0.5) - 0.5, bot - 4, 1, 4);
    }
    const text = f.ok ? charLabel(f.byte) : 'framing error';
    const w = ctx.measureText(text).width;
    if (w + 4 <= x1 - x0) {
      ctx.fillStyle = colors.text;
      ctx.fillText(text, (x0 + x1 - w) / 2, (top + bot) / 2);
    }
  }
}

/** The x of the line of cursor c (a cycle offset), drawn in the middle of its cycle; null when not in view. */
export function cursorX(vp, c) {
  if (c === null || c === undefined || c + 0.5 < vp.start || c + 0.5 > vp.end) return null;
  return Math.round(vp.x(c + 0.5)) + 0.5;
}

/**
 * Draws the whole canvas. rows: {kind: 'bit'|'bus'|'uart', y, h, rle, bits,
 * radix}; marks: {a, b, store, span} as cycle offsets (or null); frames are
 * the UART frames with absolute cycles, first the window's first cycle.
 */
export function drawWaves(ctx, { vp, height, rows, colors, first, marks, frames, selectedFrame }) {
  ctx.fillStyle = colors.bg;
  ctx.fillRect(0, 0, vp.width, height);
  drawRuler(ctx, vp, colors, first);
  if (marks.span) {
    ctx.fillStyle = colors.span;
    ctx.fillRect(vp.x(marks.span[0]), RULER_H, vp.x(marks.span[1]) - vp.x(marks.span[0]), height);
  }
  for (const row of rows) {
    ctx.strokeStyle = colors.grid;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(0, row.y + row.h + 0.5);
    ctx.lineTo(vp.width, row.y + row.h + 0.5);
    ctx.stroke();
    if (row.kind === 'bit') drawBit(ctx, row, vp, colors);
    else if (row.kind === 'bus') drawBus(ctx, row, vp, colors);
    else drawUart(ctx, row, vp, colors, frames, selectedFrame, first);
  }
  const line = (c, color, dash) => {
    const x = cursorX(vp, c);
    if (x === null) return;
    ctx.strokeStyle = color;
    ctx.setLineDash(dash);
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(x, 0);
    ctx.lineTo(x, height);
    ctx.stroke();
    ctx.setLineDash([]);
  };
  line(marks.store, colors.store, [2, 2]);
  line(marks.b, colors.cursorB, [5, 3]);
  line(marks.a, colors.cursorA, []);
}
