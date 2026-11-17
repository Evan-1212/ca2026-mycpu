// SPDX-License-Identifier: MIT
// The virtual teaching board. mount(container, api) and unmount()
// (README.md, "Contract"). With WebGL it draws the three.js scene of
// scene.js; without it, or when the scene cannot be built, the 2D block
// diagram of panels.js, which reaches the same panels.

import { bundleInfo, loadTrace } from '../waveform/data.js';
import { decodeTrace } from '../waveform/uart.js';
import { el, setChildren, styleLink, loadSetting, saveSetting } from '../waveform/dom.js';
import { taskNumber } from './model.js';
import { blockDiagram, componentList, panelFor, Terminal, vgaScreen } from './panels.js';
import { webglAvailable, createScene } from './scene.js';

const SELF = 'board';
const PROBE_KEY = 'mycpu-obs.board.probes';

let current = null;

export async function mount(container, api) {
  if (current) unmount();
  const view = new BoardView(container, api);
  current = view;
  await view.start();
}

export function unmount() {
  if (current) {
    current.destroy();
    current = null;
  }
}

class BoardView {
  constructor(container, api) {
    this.t0 = performance.now();
    this.container = container;
    this.api = api;
    this.info = bundleInfo(api);
    this.task = taskNumber(this.info.header);
    this.stale = false;
    this.scene = null;
    this.trace = null;
    this.frames = [];
    this.panelId = null;
    this.cleanups = [];
    this.cleanups.push(api.on('link', (m) => this.onLink(m)));
    const onCursor = () => this.onCursor();
    api.cursor.addEventListener('change', onCursor);
    this.cleanups.push(() => api.cursor.removeEventListener('change', onCursor));
    this.terminal = new Terminal((f) => this.charClicked(f));

    this.status = el('p', { class: 'bd-status', role: 'status' }, 'Loading...');
    this.stage = el('div', { class: 'bd-stage' });
    this.side = el('div', { class: 'bd-side' });
    this.panel = el('div', { class: 'bd-panel-host', 'aria-live': 'polite' });
    this.root = el('section', { class: 'bd', dataset: { state: 'loading' } },
      styleLink(new URL('./board.css', import.meta.url)),
      el('h2', {}, 'Virtual teaching board'),
      el('p', { class: 'bd-note' }, 'A virtual teaching board, not a physical PCB: it arranges the parts of the ' +
        'recorded design so that they can be opened. Gray parts with a dashed outline are concepts, which nothing ' +
        'in the bundle records.'),
      this.status,
      el('div', { class: 'bd-main' }, this.stage, this.side),
      this.panel);
    setChildren(container, this.root);
  }

  destroy() {
    this.stale = true;
    for (const f of this.cleanups.splice(0)) f();
    if (this.scene) this.scene.dispose();
    this.scene = null;
    this.container.replaceChildren();
  }

  fieldPresent(name) {
    if (this.trace) return this.trace.has(name);
    const group = name.split('.')[0];
    return this.info.groups.includes(group);
  }

  async start() {
    let screen;
    try {
      [this.trace, screen] = await Promise.all([
        loadTrace(this.api, this.info, { isStale: () => this.stale }),
        vgaScreen(this.api, this.info),
      ]);
    } catch (e) {
      if (this.stale) return;
      this.root.dataset.state = 'error';
      this.status.textContent = `The bundle could not be read: ${e.message}`;
      return;
    }
    if (this.stale || !this.trace) return;
    this.screen = screen;
    this.decode();
    this.side.append(componentList((id) => this.select(id), (n) => this.probeNet('P1', n),
      (f) => this.fieldPresent(f), this.task));
    await this.buildStage();
    if (this.stale) return;
    // the terminal lies over the scene (below the diagram in 2D)
    this.termHome = el('div', { class: 'bd-term-home' }, this.terminal.element);
    this.stage.append(this.termHome);
    this.onCursor();
    this.status.textContent = this.root.dataset.mode === '3d'
      ? 'Click a part to open it; drag a probe onto a pin to see its waveform.'
      : 'Click a block to open it; use "Probe a net" for a waveform.';
    this.root.dataset.state = 'ready';
    this.root.dataset.sceneMs = (performance.now() - this.t0).toFixed(1);
  }

  decode() {
    const uart = decodeTrace(this.trace, this.info.header);
    if (uart) this.frames = uart.frames;
  }

  async buildStage() {
    const fallback = (why) => {
      this.root.dataset.mode = '2d';
      setChildren(this.stage, el('p', { class: 'bd-fallback' }, why),
        blockDiagram(this.task, (id) => this.select(id)));
    };
    if (!webglAvailable()) {
      fallback('WebGL is not available in this browser, so the board is shown as its 2D block diagram.');
      return;
    }
    const saved = loadSetting(PROBE_KEY, {});
    try {
      this.scene = await createScene(this.stage, {
        task: this.task,
        fieldPresent: (f) => this.fieldPresent(f),
        probePositions: saved && typeof saved === 'object' ? saved : {},
        screen: this.screen.canvas,
        onSelect: (id) => this.select(id, { keep: true }), // the scene stays where it is on the page
        onProbe: (probe, net, pos) => this.dropped(probe, net, pos),
        onRendered: (spots) => this.renderHotspots(spots),
      });
      if (this.stale) { this.scene.dispose(); this.scene = null; return; }
      this.root.dataset.mode = '3d';
      this.renderHotspots(this.scene.hotspots());
    } catch (e) {
      if (this.scene) this.scene.dispose();
      this.scene = null;
      setChildren(this.stage);
      fallback(`The 3D board could not be built (${e.message}), so it is shown as its 2D block diagram.`);
    }
  }

  /** The projected positions of the scene's parts, as data attributes (for tests and tools). */
  renderHotspots(spots) {
    if (!this.hotspotList) {
      this.hotspotList = el('ul', { class: 'bd-hotspots', hidden: true });
      this.stage.append(this.hotspotList);
    }
    setChildren(this.hotspotList, spots.map((s) => el('li', { dataset: { kind: s.kind, id: s.id, x: s.x.toFixed(1),
      y: s.y.toFixed(1), name: s.name || '', net: s.net || '' } })));
  }

  cycle() {
    const n = Number(this.api.cursor.get());
    return Math.max(this.info.first, Math.min(this.info.end - 1, Number.isFinite(n) ? n : this.info.first));
  }

  onCursor() {
    if (!this.trace) return;
    this.terminal.update(this.frames, this.cycle());
    if (this.panelId) this.select(this.panelId, { keep: true });
  }

  select(id, { keep = false } = {}) {
    this.panelId = id;
    const ctx = { task: this.task, info: this.info, trace: this.trace, frames: this.frames, cycle: this.cycle(),
      screen: this.screen, select: (x) => this.select(x), openWaveform: (f) => this.openWaveform(f) };
    const p = panelFor(id, ctx);
    setChildren(this.panel, p);
    this.root.dataset.panel = id;
    if (!keep) p.scrollIntoView({ block: 'nearest' });
  }

  async openWaveform(field) {
    await this.api.navigate('waveform');
    this.api.link('probe', { signal: field, from: SELF });
  }

  probeNet(probe, net) {
    if (!net) return;
    if (net.field === null) {
      this.status.textContent = `${net.label} is drawn as a concept; no field records it.`;
      return;
    }
    if (!this.fieldPresent(net.field)) {
      this.status.textContent = `${net.field} is not recorded in this bundle.`;
      return;
    }
    this.status.textContent = `${probe} on ${net.label} (${net.field}).`;
    this.openWaveform(net.field);
  }

  dropped(probe, net, pos) {
    const saved = loadSetting(PROBE_KEY, {});
    const all = saved && typeof saved === 'object' ? saved : {};
    all[probe] = { x: pos.x, z: pos.z, net: net ? net.id : null };
    saveSetting(PROBE_KEY, all);
    if (!net) {
      this.status.textContent = `${probe} is on no net; drop it on a pin.`;
      return;
    }
    this.probeNet(probe, net);
  }

  async charClicked(f) {
    this.terminal.mark(f.start);
    await this.api.navigate('waveform');
    this.api.link('uart-char', { cycle: f.start, char: f.byte, from: SELF });
  }

  onLink(m) {
    // the terminal exists from the constructor on and keeps a mark for a character it shows later
    if (!m || !m.payload || m.payload.from === SELF) return;
    if (m.kind === 'uart-char') this.terminal.mark(m.payload.cycle);
  }
}
