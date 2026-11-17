// SPDX-License-Identifier: MIT
// The board's HTML side: the 2D block diagram, which is also the view
// without WebGL, the panels that a click on a component opens, the virtual
// terminal and the VGA screen. Bundle values are inserted as text only.

import { pressable, s as svgElement } from '../lib/dom.js';
import { el, setChildren, charLabel, bytesHex } from '../waveform/dom.js';
import { formatValue } from '../waveform/data.js';
import { framesUpTo } from '../waveform/uart.js';
import { MEMORY_MAP, UART_REGS, VGA_REGS, COMPONENTS, NETS, hex32 } from './model.js';

/** lib/dom.js's s with the children as arguments, as el is h's. */
function svg(tag, attrs = {}, ...children) {
  return svgElement(tag, attrs, children);
}

/** Whether the bundle's task decodes the memory map entry. */
function decoded(entry, task) {
  return task >= entry.from;
}

function memoryMapTable(task) {
  return el('table', { class: 'bd-map' },
    el('caption', {}, `D12 memory map (T${task || '?'} decodes the rows not marked "not decoded")`),
    el('tr', {}, ['Base', 'Size', 'Device', 'Notes'].map((h) => el('th', {}, h))),
    MEMORY_MAP.map((m) => el('tr', { class: decoded(m, task) ? '' : 'bd-absent', dataset: { device: m.id } },
      el('td', {}, hex32(m.base)), el('td', {}, m.size), el('td', {}, m.device),
      el('td', {}, decoded(m, task) ? m.notes : `not decoded before T${m.from}. ${m.notes}`))));
}

function registerTable(title, base, regs) {
  return el('table', { class: 'bd-regs' }, el('caption', {}, title),
    el('tr', {}, ['Offset', 'Address', 'Register', 'Access', 'Meaning'].map((h) => el('th', {}, h))),
    regs.map((r) => el('tr', { dataset: { reg: r.name } }, el('td', {}, `+0x${r.offset.toString(16)}`),
      el('td', {}, hex32(base + r.offset)), el('td', {}, r.name), el('td', {}, r.access), el('td', {}, r.text))));
}

/**
 * The 2D block diagram: the SoC with its core and the devices of D12 on its
 * data port, and the concepts. onSelect(id) for a click or Enter on a block.
 */
export function blockDiagram(task, onSelect) {
  const boxes = [
    { id: 'soc', x: 150, y: 20, w: 200, h: 70, label: 'SoC: RISC-V core', sub: `T${task || '?'} core and its data port` },
    { id: 'rom', x: 10, y: 150, w: 110, h: 50, label: 'Boot ROM', sub: '0x0000_1000' },
    { id: 'clint', x: 130, y: 150, w: 110, h: 50, label: 'CLINT', sub: '0x0200_0000' },
    { id: 'uart', x: 250, y: 150, w: 110, h: 50, label: 'UART', sub: '0x1000_0000' },
    { id: 'simctrl', x: 370, y: 150, w: 110, h: 50, label: 'SimCtrl', sub: '0x1000_1000' },
    { id: 'vga', x: 70, y: 220, w: 110, h: 50, label: 'VGA', sub: '0x2000_0000' },
    { id: 'ram', x: 310, y: 220, w: 110, h: 50, label: 'RAM', sub: '0x8000_0000' },
    { id: 'clock', x: 380, y: 20, w: 100, h: 30, label: 'Clock (concept)', sub: '', concept: true },
    { id: 'reset', x: 380, y: 60, w: 100, h: 30, label: 'Reset (concept)', sub: '', concept: true },
    { id: 'power', x: 20, y: 20, w: 100, h: 30, label: 'Power (concept)', sub: '', concept: true },
  ];
  const root = svg('svg', { viewBox: '0 0 490 285', class: 'bd-diagram', role: 'group',
    'aria-label': 'block diagram of the SoC and its devices (D12)' });
  // the data port, a bus line to every device
  root.append(svg('line', { x1: 250, y1: 90, x2: 250, y2: 125, class: 'bd-wire' }),
    svg('line', { x1: 65, y1: 125, x2: 425, y2: 125, class: 'bd-wire' }));
  for (const b of boxes) {
    if (b.concept) continue;
    if (b.id !== 'soc') root.append(svg('line', { x1: b.x + b.w / 2, y1: 125, x2: b.x + b.w / 2, y2: b.y, class: 'bd-wire' }));
  }
  for (const b of boxes) {
    const map = MEMORY_MAP.find((m) => m.id === b.id);
    const absent = map && !decoded(map, task);
    const g = svg('g', { class: `bd-block${b.concept ? ' bd-concept' : ''}${absent ? ' bd-absent' : ''}`, role: 'button',
      'data-id': b.id, 'aria-label': b.label, ...pressable(() => onSelect(b.id)) });
    g.append(svg('rect', { x: b.x, y: b.y, width: b.w, height: b.h, rx: 4 }),
      svg('text', { x: b.x + b.w / 2, y: b.y + (b.sub ? b.h / 2 - 4 : b.h / 2 + 4), 'text-anchor': 'middle' }, b.label));
    if (b.sub || absent) {
      g.append(svg('text', { x: b.x + b.w / 2, y: b.y + b.h / 2 + 12, 'text-anchor': 'middle', class: 'bd-sub' },
        absent ? `not in T${task || '?'}` : b.sub));
    }
    root.append(g);
  }
  root.append(svg('text', { x: 245, y: 140, 'text-anchor': 'middle', class: 'bd-sub' }, 'data port (memory map below)'));
  return root;
}

/** The virtual terminal: the bytes decoded from uart.txd whose stop bit was sampled by the cursor. */
export class Terminal {
  constructor(onChar) {
    this.onChar = onChar;
    this.text = el('div', { class: 'bd-term-text', role: 'log', 'aria-label': 'virtual terminal output' });
    this.element = el('div', { class: 'bd-terminal' },
      el('div', { class: 'bd-term-title' }, 'Virtual terminal: UART TX decoded from uart.txd up to the cursor'),
      this.text);
    this.shown = -1;
    this.marked = null; // the first cycle of the marked frame, kept across updates
  }

  update(frames, cycle) {
    const upTo = framesUpTo(frames, cycle).filter((f) => f.ok);
    this.text.dataset.cycle = String(cycle);
    if (upTo.length === this.shown) return;
    this.shown = upTo.length;
    const spans = upTo.map((f) => {
      // a button for the keyboard too: Tab reaches it, Enter or Space traces it back
      const s = el('span', { class: 'bd-ch', dataset: { start: String(f.start) }, title: `frame from cycle ${f.start}`,
        role: 'button', 'aria-label': `${charLabel(f.byte)}, frame from cycle ${f.start}`, ...pressable(() => this.onChar(f)) });
      s.textContent = f.byte === 0x0a ? '\n' : f.byte >= 0x20 && f.byte < 0x7f ? String.fromCharCode(f.byte) : charLabel(f.byte);
      if (f.start === this.marked) s.classList.add('bd-on');
      return s;
    });
    setChildren(this.text, spans.length ? spans : el('span', { class: 'bd-term-empty' }, '(nothing sent yet)'));
    this.text.dataset.bytes = bytesHex(upTo.map((f) => f.byte));
  }

  mark(start) {
    this.marked = start;
    for (const s of this.text.querySelectorAll('.bd-ch')) s.classList.toggle('bd-on', Number(s.dataset.start) === start);
  }
}

/** Parses a binary PPM (P6, maxval 255) into an ImageData. */
export function parsePpm(buffer) {
  const bytes = new Uint8Array(buffer);
  let pos = 0;
  const token = () => {
    for (;;) {
      while (pos < bytes.length && /\s/.test(String.fromCharCode(bytes[pos]))) pos++;
      if (bytes[pos] !== 0x23) break; // '#': a comment to the end of the line
      while (pos < bytes.length && bytes[pos] !== 0x0a) pos++;
    }
    const s = pos;
    while (pos < bytes.length && !/\s/.test(String.fromCharCode(bytes[pos]))) pos++;
    return String.fromCharCode(...bytes.subarray(s, pos));
  };
  if (token() !== 'P6') throw new Error('not a binary PPM (P6)');
  const w = Number(token());
  const h = Number(token());
  const max = Number(token());
  pos++;
  if (!(w > 0 && h > 0 && max === 255) || bytes.length - pos < w * h * 3) throw new Error('a PPM of an unexpected form');
  const img = new ImageData(w, h);
  for (let i = 0; i < w * h; i++) {
    img.data[i * 4] = bytes[pos + i * 3];
    img.data[i * 4 + 1] = bytes[pos + i * 3 + 1];
    img.data[i * 4 + 2] = bytes[pos + i * 3 + 2];
    img.data[i * 4 + 3] = 255;
  }
  return img;
}

/**
 * The VGA screen as a 2D canvas: the bundle's first frame dump, or the
 * placeholder text. Returns {canvas, frame} (frame null for the placeholder).
 */
export async function vgaScreen(api, info) {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  const entry = info.vga[0];
  if (entry) {
    try {
      const img = parsePpm(await api.file(entry.file));
      canvas.width = img.width;
      canvas.height = img.height;
      ctx.putImageData(img, 0, 0);
      return { canvas, frame: entry.frame };
    } catch (e) {
      return placeholder(canvas, ctx, `VGA frame ${entry.frame} could not be read: ${e.message}`);
    }
  }
  return placeholder(canvas, ctx, 'no VGA data in this bundle');
}

function placeholder(canvas, ctx, text) {
  canvas.width = 640;
  canvas.height = 480;
  ctx.fillStyle = '#101418';
  ctx.fillRect(0, 0, 640, 480);
  ctx.fillStyle = '#9aa4ae';
  ctx.font = '28px system-ui, sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText(text, 320, 240, 600);
  return { canvas, frame: null, text };
}

/** A value of the trace at the cursor, as text. */
function at(trace, name, cycle, radix = 'hex') {
  if (!trace || !trace.has(name)) return 'not recorded';
  return formatValue(trace.value(name, cycle), trace.byName.get(name).bits, radix);
}

/**
 * The panel of component id. ctx: {task, info, trace, frames, cycle, screen,
 * select(id), openWaveform(field)}.
 */
export function panelFor(id, ctx) {
  const { task, info, trace, cycle } = ctx;
  const probeButton = (field) => el('button', { type: 'button', class: 'bd-open', dataset: { field },
    onclick: () => ctx.openWaveform(field) }, `Waveform of ${field}`);
  const title = (t) => el('h3', {}, t);
  const map = MEMORY_MAP.find((m) => m.id === id);
  switch (id) {
    case 'soc':
      return el('div', { class: 'bd-panel', dataset: { panel: 'soc' } }, title('SoC: block diagram'),
        el('p', {}, `The ${info.header.top || 'top'} of task ${info.header.task || '?'}, ${info.header.variant || ''}: ` +
          'a RISC-V core whose data port reaches the devices of the memory map.'),
        blockDiagram(task, ctx.select), memoryMapTable(task),
        el('p', {}, `At cycle ${cycle}: pc ${at(trace, 'core.pc', cycle)}, inst ${at(trace, 'core.inst', cycle)}.`));
    case 'uart':
      return el('div', { class: 'bd-panel', dataset: { panel: 'uart' } }, title('UART: registers and outputs'),
        registerTable(`UART at ${hex32(0x10000000)} (D12)`, 0x10000000, UART_REGS),
        el('p', {}, `Bit time: ${info.header.uart_divider ?? '?'} cycles (uart_divider, an elaboration parameter); 8N1 frames.`),
        el('p', { class: 'bd-values' }, `At cycle ${cycle}: txd ${at(trace, 'uart.txd', cycle, 'dec')}, rxd ${at(trace, 'uart.rxd', cycle, 'dec')}, ` +
          `TX FIFO ${at(trace, 'uart.txCount', cycle, 'dec')}, RX FIFO ${at(trace, 'uart.rxCount', cycle, 'dec')} bytes.`),
        uartOutput(ctx), probeButton('uart.txd'), probeButton('uart.rxd'));
    case 'vga': {
      const present = task >= 5;
      return el('div', { class: 'bd-panel', dataset: { panel: 'vga' } }, title('VGA: registers and output'),
        present ? null : el('p', { class: 'bd-absent-note' }, `The VGA is part of the T5 SoC; this bundle is of ${info.header.task || 'an unknown task'}.`),
        registerTable(`VGA at ${hex32(0x20000000)} (D12, T5)`, 0x20000000, VGA_REGS),
        el('p', {}, '640 x 480 at 60 Hz from a 160 x 120 framebuffer of 4-bit pixels, each a 4 x 4 block.'),
        el('figure', { class: 'bd-screen' }, ctx.screen.canvas,
          el('figcaption', { dataset: { frame: ctx.screen.frame === null ? '' : String(ctx.screen.frame) } },
            ctx.screen.frame === null ? ctx.screen.text : `Frame ${ctx.screen.frame} of the bundle's +vga-dump`)));
    }
    case 'ram':
      return el('div', { class: 'bd-panel', dataset: { panel: 'ram' } }, title('RAM'),
        el('p', {}, `Base ${hex32(info.header.ram_base ?? 0x80000000)}, ${info.header.ram_bytes ?? '?'} bytes ` +
          '(elaboration parameters from the header).'),
        el('p', { class: 'bd-values' }, `At cycle ${cycle}: data port request ${at(trace, 'core.memReqValid', cycle, 'dec')}, ` +
          `address ${at(trace, 'core.memReqAddr', cycle)}, strobes ${at(trace, 'core.memReqWstrb', cycle, 'dec')}.`),
        probeButton('core.memReqAddr'));
    case 'rom':
      return el('div', { class: 'bd-panel', dataset: { panel: 'rom' } }, title('Boot ROM'),
        el('p', {}, `${hex32(map.base)}, ${map.size}: ${map.notes}`),
        task >= 5 ? null : el('p', { class: 'bd-absent-note' },
          `Not part of ${info.header.task || 'this task'}: T1 to T4 start at RAM ${hex32(0x80000000)}.`));
    case 'clock':
      return conceptPanel('clock', 'Clock (concept)', 'One clock drives every register. A recorded cycle begins with ' +
        `its rising edge; this bundle holds cycles ${info.first} to ${info.end - 1}. The clock itself is not a field.`);
    case 'reset':
      return conceptPanel('reset', 'Reset (concept)', 'Cycle 0 is the first cycle in which reset is low. Reset cycles ' +
        'are not recorded.');
    case 'power':
      return conceptPanel('power', 'Power (concept)', 'A simulation has no supply. The board shows where a power ' +
        'connector would be.');
    default:
      if (map) {
        return el('div', { class: 'bd-panel', dataset: { panel: id } }, title(map.device),
          el('p', {}, `${hex32(map.base)}, ${map.size}: ${map.notes}`),
          decoded(map, task) ? null : el('p', { class: 'bd-absent-note' }, `Not decoded before T${map.from}.`));
      }
      return el('div', { class: 'bd-panel', dataset: { panel: id } }, title(id));
  }
}

/** What the UART has sent by the cursor: the text of the virtual terminal, which lies over the board. */
function uartOutput(ctx) {
  const sent = framesUpTo(ctx.frames, ctx.cycle).filter((f) => f.ok);
  return el('div', { class: 'bd-uart-out' },
    el('p', {}, `Output: ${sent.length} bytes decoded from uart.txd by cycle ${ctx.cycle} (the virtual terminal shows them):`),
    el('pre', {}, sent.map((f) => (f.byte === 0x0a || (f.byte >= 0x20 && f.byte < 0x7f)
      ? String.fromCharCode(f.byte) : charLabel(f.byte))).join('')));
}

function conceptPanel(id, heading, text) {
  return el('div', { class: 'bd-panel bd-concept', dataset: { panel: id } }, el('h3', {}, heading),
    el('p', { class: 'bd-concept-note' }, 'Concept: drawn for orientation, not recorded.'), el('p', {}, text));
}

/** The list of components and nets, as buttons: the board for the keyboard, and the 3D scene's hotspots. */
export function componentList(onSelect, onProbe, fieldPresent, task) {
  return el('div', { class: 'bd-list' },
    el('h3', {}, 'Components'),
    el('ul', {}, COMPONENTS.map((c) => el('li', {}, el('button', { type: 'button', class: 'bd-item', dataset: { id: c.id },
      onclick: () => onSelect(c.id) }, c.kind === 'concept' ? `${c.label} (concept)` :
      task >= c.from ? c.label : `${c.label} (not in T${task || '?'})`)))),
    el('h3', {}, 'Probe a net'),
    el('ul', {}, NETS.map((n) => el('li', {}, el('button', { type: 'button', class: 'bd-net', dataset: { net: n.id },
      disabled: n.field !== null && !fieldPresent(n.field),
      onclick: () => onProbe(n) }, n.field ? `${n.label}: ${n.field}` : n.label)))));
}
