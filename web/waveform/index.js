// SPDX-License-Identifier: MIT
// Digital waveforms and a logic analyzer over an observation bundle.
// The view exports mount(container, api) and unmount() (README.md, "Contract").
// It reads the whole window through api.window once, keeps every field of the
// bundle's groups as a typed array, and draws from their run-length forms.

import { bundleInfo, loadTrace, formatValue } from './data.js';
import { el, setChildren, styleLink, loadSetting, saveSetting, hex, charLabel, bytesHex } from './dom.js';
import { decodeTrace, frameBytes } from './uart.js';
import { txStores, matchFrames, UART_TXDATA } from './link.js';
import { findMatch, TRIGGER_KINDS } from './trigger.js';
import { hasBus, decodeAxi } from './axi.js';
import { drawWaves, viewport, RULER_H, ROW_H } from './render.js';

const UART_ROW = '#uart'; // the row of the UART decoder, chosen and saved like a field
const DEFAULT_FIELDS = ['uart.txd', UART_ROW, 'uart.txCount', 'core.pc', 'core.inst', 'core.store', 'core.memReqValid',
  'core.memReqAddr', 'core.memReqWstrb', 'core.memReqWdata', 'core.branchTaken', 'div.busy'];
const SELF = 'waveform';
const SAMPLE_TEXT = {
  state: 'state: the register after the edge that begins the cycle',
  comb: 'comb: computed within the cycle',
  input: 'input: driven by the harness in the cycle',
};

let current = null;

export async function mount(container, api) {
  if (current) unmount();
  const view = new WaveformView(container, api);
  current = view;
  await view.start();
}

export function unmount() {
  if (current) {
    current.destroy();
    current = null;
  }
}

class WaveformView {
  constructor(container, api) {
    this.container = container;
    this.api = api;
    this.info = bundleInfo(api);
    this.stale = false;
    this.trace = null;
    this.pending = []; // links that arrived while the bundle was loading
    this.cleanups = [];
    this.selected = [];
    this.radix = {};
    this.cursorB = null;
    this.frames = [];
    this.stores = [];
    this.selectedFrame = null;
    this.storeMark = null;
    this.view = { start: 0, end: Math.max(1, this.info.count) };
    // registered before the first await, so that a link sent right after navigate() arrives
    this.cleanups.push(api.on('link', (m) => this.onLink(m)));
    const onCursor = () => this.onCursor();
    api.cursor.addEventListener('change', onCursor);
    this.cleanups.push(() => api.cursor.removeEventListener('change', onCursor));
    this.buildSkeleton();
  }

  destroy() {
    this.stale = true;
    for (const f of this.cleanups.splice(0)) f();
    if (this.resizer) this.resizer.disconnect();
    if (this.frameRequest) cancelAnimationFrame(this.frameRequest);
    this.container.replaceChildren();
  }

  // ---- layout ------------------------------------------------------------

  buildSkeleton() {
    const h = this.info.header;
    this.root = el('section', { class: 'wf', dataset: { state: 'loading' } });
    this.status = el('p', { class: 'wf-status', role: 'status' }, 'Loading the bundle...');
    this.root.append(
      styleLink(new URL('./waveform.css', import.meta.url)),
      el('h2', {}, 'Waveforms and logic analyzer'),
      el('p', { class: 'wf-note' },
        'Shows the RTL logic levels that the simulation recorded once per clock cycle, not voltages ',
        'and not timing inside a cycle. A register written in cycle N shows its new value from cycle N + 1.'),
      el('p', { class: 'wf-meta' }, `${h.task || '?'} ${h.variant || ''}, program ${h.program || '?'}, `,
        `cycles ${this.info.first} to ${this.info.end - 1}`),
      this.status,
    );
    setChildren(this.container, this.root);
  }

  buildUi() {
    const t = this.trace;
    const fields = t.fields.map((f) => f.name);
    this.addSelect = el('select', { 'aria-label': 'signal to add' },
      fields.map((n) => el('option', { value: n }, n)),
      t.has('uart.txd') ? el('option', { value: UART_ROW }, 'UART decode of uart.txd') : null);
    this.cursorText = el('span', { class: 'wf-cursors' });
    const toolbar = el('div', { class: 'wf-toolbar' },
      el('label', {}, 'Signal ', this.addSelect),
      el('button', { type: 'button', onclick: () => this.addField(this.addSelect.value, true) }, 'Add'),
      el('button', { type: 'button', onclick: () => this.zoom(0.5) }, 'Zoom in'),
      el('button', { type: 'button', onclick: () => this.zoom(2) }, 'Zoom out'),
      el('button', { type: 'button', onclick: () => this.fit() }, 'Whole window'),
      this.cursorText);

    this.trigField = el('select', { 'aria-label': 'trigger signal' }, fields.map((n) => el('option', { value: n }, n)));
    this.trigKind = el('select', { 'aria-label': 'trigger condition' },
      TRIGGER_KINDS.map((k) => el('option', { value: k }, k === 'value' ? 'equals' : k)));
    this.trigValue = el('input', { type: 'text', size: 12, value: '0x10000000', 'aria-label': 'trigger value' });
    this.trigStatus = el('span', { class: 'wf-trig-status' });
    const trigger = el('div', { class: 'wf-trigger' }, 'Trigger: ', this.trigField, this.trigKind, this.trigValue,
      el('button', { type: 'button', onclick: () => this.trigger(-1) }, 'Previous match'),
      el('button', { type: 'button', onclick: () => this.trigger(1) }, 'Next match'),
      this.trigStatus);

    this.names = el('div', { class: 'wf-names' });
    this.canvas = el('canvas', { class: 'wf-canvas', tabindex: 0,
      'aria-label': 'waveforms; click sets cursor A, shift-click cursor B, drag pans, wheel zooms' });
    this.canvasWrap = el('div', { class: 'wf-canvas-wrap' }, this.canvas);
    const waves = el('div', { class: 'wf-waves' }, this.names, this.canvasWrap);

    this.uartPanel = el('section', { class: 'wf-uart' });
    this.tracePanel = el('section', { class: 'wf-traceback', 'aria-live': 'polite' });
    this.axiPanel = el('section', { class: 'wf-axi' });
    this.retirePanel = el('section', { class: 'wf-retire' });
    this.root.append(toolbar, trigger, waves,
      el('div', { class: 'wf-panels' }, this.uartPanel, this.tracePanel, this.axiPanel, this.retirePanel));

    this.bindCanvas();
    const scheme = window.matchMedia('(prefers-color-scheme: dark)');
    const onScheme = () => { this.palette = null; this.scheduleRedraw(); };
    scheme.addEventListener('change', onScheme);
    this.cleanups.push(() => scheme.removeEventListener('change', onScheme));
    this.resizer = new ResizeObserver(() => this.scheduleRedraw());
    this.resizer.observe(this.canvasWrap);
  }

  // ---- loading -----------------------------------------------------------

  async start() {
    const t0 = performance.now();
    let trace;
    try {
      trace = await loadTrace(this.api, this.info, {
        isStale: () => this.stale,
        onProgress: (done, total) => { this.status.textContent = `Loading cycles: ${done} of ${total}`; },
      });
    } catch (e) {
      if (this.stale) return;
      this.root.dataset.state = 'error';
      this.status.textContent = `The bundle could not be read: ${e.message}`;
      return;
    }
    if (this.stale || !trace) return;
    this.trace = trace;
    this.root.dataset.loadMs = (performance.now() - t0).toFixed(1);
    this.decode();
    this.buildUi();
    // the saved choice is per task; a field that this bundle lacks stays chosen and is not drawn
    const saved = loadSetting(this.settingsKey(), null);
    const initial = Array.isArray(saved) ? saved : DEFAULT_FIELDS;
    this.selected = [...new Set(initial.filter((n) => typeof n === 'string'))];
    this.status.textContent = `${trace.count} cycles loaded.`;
    this.renderUartPanel();
    this.renderAxiPanel();
    this.renderRows();
    this.onCursor();
    this.root.dataset.state = 'ready';
    for (const m of this.pending.splice(0)) this.onLink(m);
  }

  settingsKey() {
    return `mycpu-obs.waveform.fields.${this.info.header.task || 'unknown'}`;
  }

  decode() {
    this.uart = decodeTrace(this.trace, this.info.header);
    if (!this.uart) return;
    this.frames = this.uart.frames;
    this.stores = txStores(this.trace);
    this.match = matchFrames(this.trace, this.stores, this.frames);
    this.frameNumber = new Map(this.frames.map((f, n) => [f, n]));
  }

  /** Whether the row of name can be drawn with this bundle. */
  available(name) {
    return name === UART_ROW ? this.trace.has('uart.txd') : this.trace.has(name);
  }

  // ---- rows --------------------------------------------------------------

  addField(name, persist) {
    const t = this.trace;
    if (!t || !this.available(name) || this.selected.includes(name)) return false;
    this.selected.push(name);
    if (persist) this.persist();
    if (this.names) this.renderRows();
    return true;
  }

  removeField(name) {
    this.selected = this.selected.filter((n) => n !== name);
    this.persist();
    this.renderRows();
  }

  persist() {
    saveSetting(this.settingsKey(), this.selected);
  }

  rows() {
    return this.selected.filter((name) => this.available(name)).map((name, i) => {
      const y = RULER_H + i * ROW_H;
      if (name === UART_ROW) return { name, kind: 'uart', y, h: ROW_H };
      const f = this.trace.byName.get(name);
      return { name, kind: f.bits === 1 ? 'bit' : 'bus', y, h: ROW_H, rle: this.trace.runs(name), bits: f.bits,
        radix: this.radix[name] || 'hex', field: f };
    });
  }

  renderRows() {
    const rows = this.rows();
    const items = [el('div', { class: 'wf-name wf-ruler-label', style: `height:${RULER_H}px` }, 'cycle')];
    for (const r of rows) {
      if (r.kind === 'uart') {
        items.push(el('div', { class: 'wf-name', style: `height:${ROW_H}px`, dataset: { row: 'uart' } },
          el('span', { class: 'wf-label' }, 'UART decode of uart.txd'),
          el('button', { type: 'button', class: 'wf-small', 'aria-label': 'remove the UART decode',
            onclick: () => this.removeField(UART_ROW) }, 'x')));
        continue;
      }
      const value = el('span', { class: 'wf-value', dataset: { field: r.name } });
      const radix = r.kind === 'bus' ? el('button', { type: 'button', class: 'wf-small',
        title: 'hexadecimal or decimal', onclick: () => {
          this.radix[r.name] = r.radix === 'hex' ? 'dec' : 'hex';
          this.renderRows();
        } }, r.radix) : null;
      items.push(el('div', { class: 'wf-name', style: `height:${ROW_H}px`, dataset: { row: r.name },
        title: `${r.field.description} (${r.bits} bits; ${SAMPLE_TEXT[r.field.sample] || r.field.sample})` },
      el('span', { class: 'wf-label' }, r.name), el('span', { class: 'wf-class' }, r.field.sample), value, radix,
      el('button', { type: 'button', class: 'wf-small', 'aria-label': `remove ${r.name}`,
        onclick: () => this.removeField(r.name) }, 'x')));
    }
    setChildren(this.names, items);
    this.updateValues();
    this.redraw();
  }

  updateValues() {
    if (!this.names) return;
    const a = this.cursorA();
    for (const v of this.names.querySelectorAll('.wf-value')) {
      const f = this.trace.byName.get(v.dataset.field);
      v.textContent = formatValue(this.trace.value(f.name, a), f.bits, this.radix[f.name] || 'hex');
    }
  }

  // ---- drawing -----------------------------------------------------------

  /** The canvas colors from the view's CSS variables, read once and again when the color scheme changes. */
  colors() {
    if (this.palette) return this.palette;
    const s = getComputedStyle(this.root);
    const c = (n, d) => s.getPropertyValue(n).trim() || d;
    this.palette = {
      bg: c('--wf-bg', '#ffffff'), ruler: c('--wf-ruler', '#eef0f3'), grid: c('--wf-grid', '#d8dce2'),
      text: c('--wf-text', '#1f2328'), bit: c('--wf-bit', '#1a7f37'), bus: c('--wf-bus', '#0b5cad'),
      busy: c('--wf-busy', '#7aa7d8'), frame: c('--wf-frame', '#fff1c2'), error: c('--wf-error', '#ffc9c9'),
      select: c('--wf-select', '#ffd970'), span: c('--wf-span', 'rgba(255, 200, 0, 0.12)'),
      cursorA: c('--wf-cursor-a', '#cf222e'), cursorB: c('--wf-cursor-b', '#8250df'), store: c('--wf-store', '#bc4c00'),
    };
    return this.palette;
  }

  redraw() {
    if (!this.canvas || this.stale) return;
    const t0 = performance.now();
    const rows = this.rows();
    const width = Math.max(50, this.canvasWrap.clientWidth);
    const height = RULER_H + rows.length * ROW_H;
    const dpr = window.devicePixelRatio || 1;
    if (this.canvas.width !== Math.round(width * dpr) || this.canvas.height !== Math.round(height * dpr)) {
      this.canvas.width = Math.round(width * dpr);
      this.canvas.height = Math.round(height * dpr);
      this.canvas.style.width = `${width}px`;
      this.canvas.style.height = `${height}px`;
    }
    const ctx = this.canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.vp = viewport(this.view.start, this.view.end, width);
    const off = (n) => (n === null ? null : n - this.trace.first);
    const f = this.selectedFrame;
    drawWaves(ctx, {
      vp: this.vp, height, rows, colors: this.colors(), first: this.trace.first,
      marks: { a: off(this.cursorA()), b: off(this.cursorB), store: off(this.storeMark),
        span: f ? [f.start - this.trace.first, f.end + 1 - this.trace.first] : null },
      frames: this.frames,
      selectedFrame: this.selectedFrame,
    });
    this.root.dataset.renderMs = (performance.now() - t0).toFixed(2);
    this.root.dataset.viewStart = String(this.trace.first + Math.floor(this.view.start));
    this.root.dataset.viewEnd = String(this.trace.first + Math.ceil(this.view.end));
  }

  scheduleRedraw() {
    if (this.frameRequest) return;
    this.frameRequest = requestAnimationFrame(() => {
      this.frameRequest = null;
      this.redraw();
    });
  }

  // ---- view and cursors ----------------------------------------------------

  setView(start, end) {
    const n = this.trace.count;
    const span = Math.min(n, Math.max(4, end - start));
    // a span below the smallest keeps the requested center, so zooming further does not pan
    if (span !== end - start) start = (start + end - span) / 2;
    start = Math.max(0, Math.min(n - span, start));
    this.view = { start, end: start + span };
    this.scheduleRedraw();
  }

  zoom(factor, at) {
    const { start, end } = this.view;
    const c = at === undefined ? (start + end) / 2 : at;
    this.setView(c - (c - start) * factor, c + (end - c) * factor);
  }

  fit() { this.setView(0, this.trace.count); }

  /** Shows offsets a..b (inclusive) if they are not in view, with a margin. */
  reveal(a, b) {
    const { start, end } = this.view;
    if (a >= start && b + 1 <= end) return;
    const span = Math.max(end - start, (b - a + 1) * 1.4);
    const mid = (a + b + 1) / 2;
    this.setView(mid - span / 2, mid + span / 2);
  }

  cursorA() {
    const n = Number(this.api.cursor.get());
    const t = this.trace;
    if (!t) return n;
    return Math.max(t.first, Math.min(t.first + t.count - 1, Number.isFinite(n) ? n : t.first));
  }

  onCursor() {
    if (!this.trace || !this.cursorText) return;
    const a = this.cursorA();
    const parts = [`A: cycle ${a}`];
    if (this.cursorB !== null) parts.push(`B: cycle ${this.cursorB}`, `B - A: ${this.cursorB - a} cycles`);
    else parts.push('B: shift-click to set');
    this.cursorText.textContent = parts.join('   ');
    this.cursorText.dataset.a = String(a);
    this.cursorText.dataset.delta = this.cursorB === null ? '' : String(this.cursorB - a);
    this.updateValues();
    this.renderRetire(a);
    this.reveal(a - this.trace.first, a - this.trace.first);
    this.scheduleRedraw();
  }

  bindCanvas() {
    let drag = null;
    const cycleAt = (ev) => {
      const r = this.canvas.getBoundingClientRect();
      return Math.floor(this.vp.cycleAt(ev.clientX - r.left));
    };
    const on = (type, fn, opts) => {
      this.canvas.addEventListener(type, fn, opts);
      this.cleanups.push(() => this.canvas.removeEventListener(type, fn, opts));
    };
    on('pointerdown', (ev) => {
      drag = { x: ev.clientX, start: this.view.start, end: this.view.end, moved: false };
      this.canvas.setPointerCapture(ev.pointerId);
    });
    on('pointermove', (ev) => {
      if (!drag) return;
      const dx = ev.clientX - drag.x;
      if (Math.abs(dx) > 3) drag.moved = true;
      if (drag.moved) {
        const d = dx / this.vp.scale;
        this.setView(drag.start - d, drag.end - d);
      }
    });
    on('pointerup', (ev) => {
      const d = drag;
      drag = null;
      if (!d || d.moved) return;
      const off = Math.max(0, Math.min(this.trace.count - 1, cycleAt(ev)));
      const cycle = this.trace.first + off;
      const r = this.canvas.getBoundingClientRect();
      const y = ev.clientY - r.top;
      const row = this.rows().find((rw) => y >= rw.y && y < rw.y + rw.h);
      if (ev.shiftKey) {
        this.cursorB = cycle;
        this.onCursor();
        return;
      }
      if (row && row.kind === 'uart') {
        const f = this.frames.find((fr) => cycle >= fr.start && cycle <= fr.end);
        if (f) { this.selectFrame(f); return; }
      }
      this.api.cursor.set(cycle);
    });
    on('wheel', (ev) => {
      ev.preventDefault();
      const r = this.canvas.getBoundingClientRect();
      this.zoom(ev.deltaY > 0 ? 1.25 : 0.8, this.vp.cycleAt(ev.clientX - r.left));
    }, { passive: false });
    on('keydown', (ev) => {
      if (ev.key !== 'ArrowLeft' && ev.key !== 'ArrowRight') return;
      ev.preventDefault();
      this.api.cursor.set(this.cursorA() + (ev.key === 'ArrowRight' ? 1 : -1));
    });
  }

  // ---- trigger -------------------------------------------------------------

  trigger(dir) {
    const name = this.trigField.value;
    const kind = this.trigKind.value;
    let value = 0;
    if (kind === 'value') {
      const text = this.trigValue.value.trim();
      value = /^0x[0-9a-f]+$/i.test(text) ? parseInt(text, 16) : /^\d+$/.test(text) ? Number(text) : NaN;
      if (!Number.isFinite(value) || value > 0xffffffff) {
        this.trigStatus.textContent = 'The value must be a decimal or 0x-prefixed hexadecimal number below 2^32.';
        return;
      }
    }
    const from = this.cursorA() - this.trace.first;
    const hit = findMatch(this.trace.runs(name), from, { kind, value }, dir);
    if (hit === null) {
      this.trigStatus.textContent = `No ${dir > 0 ? 'later' : 'earlier'} match of ${name}.`;
      this.trigStatus.dataset.hit = '';
      return;
    }
    const cycle = this.trace.first + hit;
    this.addField(name, true);
    this.trigStatus.textContent = `${name} matches in cycle ${cycle}.`;
    this.trigStatus.dataset.hit = String(cycle);
    this.api.cursor.set(cycle);
  }

  // ---- UART, stores and links ------------------------------------------------

  renderUartPanel() {
    const p = this.uartPanel;
    if (!this.uart) {
      setChildren(p, el('h3', {}, 'UART decoder'), el('p', {}, 'The bundle has no uart group.'));
      return;
    }
    const u = this.uart;
    const m = u.measured;
    const divText = u.fromHeader !== null
      ? `Bit time: ${u.fromHeader} cycles (uart_divider of the header)` +
        (m ? `; the line's low pulses measure ${m.bitTime} cycles (${m.pulses} pulses)` +
          (u.agrees ? '.' : ', which is not a multiple of the header\'s divider: the decoding may be wrong.') : '.')
      : m ? `Bit time: ${m.bitTime} cycles, measured from ${m.pulses} low pulses (the header has no uart_divider).`
        : 'The header has no uart_divider and the line never pulses low: nothing to decode.';
    const bytes = frameBytes(this.frames);
    const chars = el('div', { class: 'wf-chars' }, this.frames.map((f, i) => el('button', {
      type: 'button', class: f.ok ? 'wf-char' : 'wf-char wf-bad', dataset: { frame: String(i), start: String(f.start) },
      title: `frame from cycle ${f.start} to ${f.end}` + (f.ok ? `, byte ${hex(f.byte, 2)}` : ', framing error'),
      onclick: () => this.selectFrame(f),
    }, f.ok ? charLabel(f.byte) : '?')));
    const storeList = el('ol', { class: 'wf-stores' }, this.stores.map((s, i) => el('li', {},
      el('button', { type: 'button', class: 'wf-store', dataset: { store: String(i), cycle: String(s.cycle) },
        onclick: () => this.selectStore(s) },
      `cycle ${s.cycle}: pc ${hex(s.pc)} stores ${hex(s.data)} (${charLabel(s.byte)})`))));
    setChildren(p,
      el('h3', {}, 'UART decoder (8N1 on uart.txd)'),
      el('p', { class: 'wf-divider', dataset: { divider: String(u.divider) } }, divText),
      this.uart.searched ? el('p', { class: 'wf-warn' }, 'This window starts after cycle 0, inside the UART\'s ' +
        'traffic: its first start bit is the edge from which the most frames decode without a framing error.') : null,
      el('p', {}, `${this.frames.length} frames. Click a character for the store that wrote it.`),
      chars,
      el('pre', { class: 'wf-uart-text', dataset: { bytes: bytesHex(bytes) } },
        [...bytes].map((b) => String.fromCharCode(b)).join('')),
      el('h3', {}, `Stores to TXDATA (${hex(UART_TXDATA)}), full word`),
      this.stores.length ? storeList : el('p', {}, 'None in this window.'));
  }

  renderAxiPanel() {
    const p = this.axiPanel;
    if (!hasBus(this.trace)) {
      setChildren(p, el('h3', {}, 'AXI4-Lite decoder'),
        el('p', {}, 'This bundle has no bus group, so there are no AXI4-Lite transactions to show (the T5 SoC records them).'));
      p.dataset.enabled = 'false';
      return;
    }
    p.dataset.enabled = 'true';
    const axi = decodeAxi(this.trace);
    const cyc = (c) => (c === null ? 'open' : String(c));
    const val = (v) => (v === null ? '-' : hex(v));
    setChildren(p, el('h3', {}, 'AXI4-Lite transactions'),
      axi.aligned ? null : el('p', {}, 'The window starts after cycle 0: a transaction open at its start shifts the pairing.'),
      el('table', {}, el('tr', {}, ['kind', 'addr', 'data', 'strb/resp', 'cycles'].map((h) => el('th', {}, h))),
        axi.writes.map((w) => el('tr', {}, el('td', {}, 'write'), el('td', {}, val(w.addr)), el('td', {}, val(w.data)),
          el('td', {}, `${w.strb === null ? '-' : w.strb.toString(2).padStart(4, '0')} / ${w.resp === null ? '-' : w.resp}`),
          el('td', {}, `AW ${cyc(w.aw)}, W ${cyc(w.w)}, B ${cyc(w.b)}`))),
        axi.reads.map((r) => el('tr', {}, el('td', {}, 'read'), el('td', {}, val(r.addr)), el('td', {}, val(r.data)),
          el('td', {}, `- / ${r.resp === null ? '-' : r.resp}`), el('td', {}, `AR ${cyc(r.ar)}, R ${cyc(r.r)}`)))));
  }

  renderRetire(a) {
    const r = this.trace.retireAt(a);
    const rows = [];
    if (r) {
      rows.push(['pc', hex(r.pc)], ['inst', hex(r.inst)], ['next', hex(r.next)], ['rd', String(r.rd)], ['val', hex(r.val)]);
      if (r.mem) rows.push(['mem', `${r.mem.write ? 'write' : 'read'} ${r.mem.bytes} bytes at ${hex(r.mem.addr)}: ${hex(r.mem.data)}`]);
    }
    setChildren(this.retirePanel, el('h3', {}, `Retire record of cycle ${a}`),
      r ? el('table', { class: 'wf-retire-table' }, rows.map(([k, v]) => el('tr', {}, el('th', {}, k),
        el('td', { dataset: { key: k } }, v)))) : el('p', {}, 'No instruction retired in this cycle.'));
  }

  markChar(f) {
    const n = this.frameNumber.get(f);
    for (const b of this.uartPanel.querySelectorAll('.wf-char')) b.classList.toggle('wf-on', Number(b.dataset.frame) === n);
  }

  /** From a frame to its store: the trace-back of a UART character. */
  selectFrame(f, { emitChar = true } = {}) {
    this.selectedFrame = f;
    this.markChar(f);
    if (emitChar && f.ok) this.api.link('uart-char', { cycle: f.start, char: f.byte, from: SELF });
    const r = this.match.ofFrame.get(f);
    const head = `Frame ${this.frameNumber.get(f)}: ${f.ok ? `${charLabel(f.byte)} (${hex(f.byte, 2)})` : 'framing error'}, ` +
      `start bit in cycle ${f.start}, stop bit sampled in cycle ${f.end}; ` +
      `${this.trace.value('uart.txCount', f.start)} later bytes waited in the TX FIFO.`;
    const out = el('div', { class: 'wf-tb', dataset: { frame: String(f.start) } }, el('p', {}, head));
    if (r.store) {
      const s = r.store;
      this.storeMark = s.cycle;
      out.dataset.storeCycle = String(s.cycle);
      out.append(el('table', {}, [['store retired in cycle', String(s.cycle)], ['pc', hex(s.pc)], ['inst', hex(s.inst)],
        ['address', hex(s.addr)], ['data', hex(s.data)]].map(([k, v]) => el('tr', {}, el('th', {}, k),
        el('td', { dataset: { key: k } }, v)))));
      setChildren(this.tracePanel, el('h3', {}, 'Trace-back'), out);
      this.reveal(s.cycle - this.trace.first, f.end - this.trace.first);
      this.api.cursor.set(s.cycle);
      this.api.link('store', { cycle: s.cycle, addr: s.addr, value: s.data, from: SELF });
      this.api.link('instruction', { cycle: s.cycle, pc: s.pc, inst: s.inst, from: SELF });
    } else {
      this.storeMark = null;
      out.append(el('p', { class: 'wf-warn' }, r.before
        ? 'The store that wrote this byte retired before the first cycle of this bundle.'
        : `The store found by FIFO order (cycle ${r.mismatch.cycle}) holds ${hex(r.mismatch.byte, 2)}, not this byte: the recorded run does not follow the FIFO order.`));
      setChildren(this.tracePanel, el('h3', {}, 'Trace-back'), out);
      this.reveal(f.start - this.trace.first, f.end - this.trace.first);
      this.api.cursor.set(f.start);
    }
    this.scheduleRedraw();
  }

  /** From a store to TXDATA to the frame that sent its byte. */
  selectStore(s) {
    const f = this.match.ofStore.get(s) || null;
    this.storeMark = s.cycle;
    if (!f) {
      this.selectedFrame = null;
      setChildren(this.tracePanel, el('h3', {}, 'Store to frame'), el('p', { class: 'wf-warn', dataset: { storeCycle: String(s.cycle) } },
        `The byte stored in cycle ${s.cycle} is not sent completely within this bundle.`));
      this.api.cursor.set(s.cycle);
      return;
    }
    this.selectedFrame = f;
    this.markChar(f);
    setChildren(this.tracePanel, el('h3', {}, 'Store to frame'), el('div', { class: 'wf-tb',
      dataset: { storeCycle: String(s.cycle), frame: String(f.start) } }, el('p', {},
      `The store in cycle ${s.cycle} (pc ${hex(s.pc)}) wrote ${charLabel(s.byte)}; its frame starts in cycle ${f.start} ` +
      `and its stop bit is sampled in cycle ${f.end}.`)));
    this.reveal(s.cycle - this.trace.first, f.end - this.trace.first);
    this.api.cursor.set(f.start);
    this.api.link('uart-char', { cycle: f.start, char: f.byte, from: SELF });
    this.scheduleRedraw();
  }

  onLink(m) {
    if (!m || (m.payload && m.payload.from === SELF)) return;
    if (!this.trace) { this.pending.push(m); return; }
    const p = m.payload || {};
    if (m.kind === 'probe') {
      if (this.trace.has(p.signal)) {
        this.selected = [p.signal, ...this.selected.filter((n) => n !== p.signal)];
        this.persist();
        this.renderRows();
        this.status.textContent = `Probe on ${p.signal}.`;
      } else {
        this.status.textContent = `The probed signal ${p.signal} is not recorded in this bundle.`;
      }
      this.status.dataset.probe = String(p.signal);
    } else if (m.kind === 'uart-char') {
      const f = this.frames.find((fr) => fr.start === p.cycle) || this.frames.find((fr) => p.cycle >= fr.start && p.cycle <= fr.end);
      if (f) this.selectFrame(f, { emitChar: false });
    } else if (m.kind === 'store') {
      const s = this.stores.find((st) => st.cycle === p.cycle);
      if (s) this.selectStore(s);
    }
  }
}
