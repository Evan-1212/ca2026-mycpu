// SPDX-License-Identifier: MIT
// The CSR file, traps and interrupts of T3 to T5: the CSRs at the
// cursor, every trap of the window with its cause, mepc and the handler's
// first instruction, the path of an interrupt from its line in mip to the
// handler, and a timeline of the interrupt lines, csr.irq, the traps, MRET and
// WFI around the cursor. Every value is a field of the bundle (the `csr`
// group, docs/observation.md) or a retire record; lib/csr.js only names them.

import { clear, h, repoLink, s } from "../lib/dom.js";
import { causeName, lineNames, LINES, mstatusFields, mtvecFields, traps } from "../lib/csr.js";
import { formatValue, hex8 } from "../lib/format.js";

const SPAN = 48; // cycles of the timeline on each side of the cursor
const T3_SRC = "3-csr-trap/src/main/scala/mycpu/csrtrap/";

let current = null;

const hex = (v) => formatValue(v, 32); // "–" for a value that is absent

function kv(rows) {
  return h("dl", { class: "kv" }, rows.flatMap(([label, field, text, note]) => [
    h("dt", {}, [label]),
    h("dd", field ? { "data-field": field } : {}, [h("span", { class: "mono" }, [text]), note ? ` ${note}` : ""]),
  ]));
}

function csrPanel(r) {
  const c = r.csr;
  const ms = mstatusFields(c.mstatus);
  const tv = mtvecFields(c.mtvec);
  const lines = (v) => `(${lineNames(v).join(", ") || "none"})`;
  return h("div", { class: "csr-grid" }, [
    h("section", {}, [h("h3", {}, ["CSRs (state: after the edge that starts the cycle)"]), kv([
      ["mstatus", "csr.mstatus", hex(c.mstatus), `MIE ${ms.MIE}, MPIE ${ms.MPIE}, MPP ${ms.MPP}`],
      ["mie", "csr.mie", hex(c.mie), lines(c.mie)],
      ["mip", "csr.mip", hex(c.mip), lines(c.mip)],
      ["mtvec", "csr.mtvec", hex(c.mtvec), `base ${hex(tv.base)}, ${tv.mode === 1 ? "vectored" : "direct"}`],
      ["mepc", "csr.mepc", hex(c.mepc)],
      ["mcause", "csr.mcause", hex(c.mcause), causeName(c.mcause)],
      ["WFI waits", "csr.wfi", String(c.wfi)],
    ])]),
    h("section", {}, [h("h3", {}, ["In this cycle (comb)"]), kv([
      ["interrupt due (irq)", "csr.irq", String(c.irq)],
      ["trap taken", "csr.trap", String(c.trap), c.trap ? causeName(c.cause) : ""],
      ["cause", "csr.cause", hex(c.cause)],
      ["MRET", "csr.mret", String(c.mret)],
      ["CSR access", "csr.access", String(c.access), c.access ? `address 0x${c.addr.toString(16).padStart(3, "0")}, ` +
        `op ${["", "read-write", "set", "clear"][c.op] || c.op}` : ""],
      ["CSR written", "csr.write", String(c.write), c.access ? `old ${hex(c.rdata)}, written ${hex(c.wdata)}` : ""],
    ])]),
  ]);
}

function trapRow(t, onFollow) {
  return h("tr", { "data-cycle": t.cycle, "data-cause": t.cause }, [
    h("td", {}, [String(t.cycle)]),
    h("td", {}, [causeName(t.cause)]),
    h("td", { class: "mono", "data-col": "pc" }, [t.pc === null ? "–" : hex8(t.pc)]),
    h("td", { class: "mono", "data-col": "mepc" }, [t.mepc === null ? "–" : hex8(t.mepc)]),
    h("td", { class: "mono", "data-col": "mcause" }, [t.mcause === null ? "–" : hex8(t.mcause)]),
    h("td", { class: "mono", "data-col": "handler" }, [t.handler ? `${hex8(t.handler.pc)} (cycle ${t.handler.cycle})` : "–"]),
    h("td", {}, [h("button", { type: "button", class: "link", "data-follow": t.cycle, onclick: () => onFollow(t) }, ["follow"])]),
  ]);
}

/** The path of one trap: from the interrupt line to the handler's first instruction. */
function pathPanel(api, t) {
  const steps = [];
  if (t.interrupt) {
    const code = t.cause & 0x7fffffff;
    const line = LINES.find(([bit]) => bit === code)?.[1] ?? `bit ${code}`;
    steps.push(["line", t.lineRise, `mip.${line} rises: the interrupt line is set`]);
    steps.push(["irq", t.irqRise, "csr.irq is 1: pending, enabled in mie, and mstatus.MIE is 1"]);
  }
  steps.push(["trap", t.cycle, `the trap is taken (csr.trap): ${causeName(t.cause)}; its retire record names pc ` +
    `${t.pc === null ? "–" : hex8(t.pc)}${t.interrupt ? ", the instruction that does not execute" : ""}`]);
  steps.push(["csrs", t.cycle + 1, `the CSRs after it: mepc ${hex(t.mepc)}, mcause ${hex(t.mcause)}`]);
  steps.push(["handler", t.handler?.cycle ?? null, t.handler
    ? `the handler's first instruction retires at pc ${hex8(t.handler.pc)}` : "the handler's first instruction is past the window"]);
  return h("ol", { class: "csr-path", "data-trap": t.cycle }, steps.map(([id, cycle, text]) => h("li", { "data-step": id,
    "data-cycle": cycle ?? "" }, [
    cycle === null ? h("span", { class: "note" }, ["before the window: "])
      : h("button", { type: "button", class: "link", onclick: () => api.cursor.set(cycle) }, [`cycle ${cycle}`]), " ", text,
  ])));
}

/** The timeline of the interrupt lines, irq, the traps, MRET and WFI around `cycle`. */
function timeline(records, cycle) {
  const lanes = [
    ...LINES.map(([bit, name]) => [`mip.${name}`, (r) => (r.csr.mip >> bit) & 1]),
    ...LINES.map(([bit, name]) => [`mie.${name}`, (r) => (r.csr.mie >> bit) & 1]),
    ["mstatus.MIE", (r) => (r.csr.mstatus >> 3) & 1],
    ["irq", (r) => r.csr.irq], ["trap", (r) => r.csr.trap], ["mret", (r) => r.csr.mret], ["wfi", (r) => r.csr.wfi],
    ["retire", (r) => (r.retire ? 1 : 0)],
  ];
  const cell = 8;
  const label = 96;
  const row = 16;
  const svg = s("svg", { class: "csr-timeline", viewBox: `0 0 ${label + records.length * cell} ${lanes.length * row + 18}`,
    role: "img", "aria-label": "timeline of the interrupt lines and traps" });
  lanes.forEach(([name, get], k) => {
    const y = k * row;
    svg.append(s("text", { x: 0, y: y + 12, class: "lane" }, [name]));
    records.forEach((r, i) => {
      if (get(r)) svg.append(s("rect", { x: label + i * cell, y: y + 3, width: cell - 1, height: row - 6, class: `on lane-${k}` }));
    });
  });
  const at = records.findIndex((r) => r.cycle === cycle);
  if (at >= 0) svg.append(s("rect", { x: label + at * cell, y: 0, width: cell - 1, height: lanes.length * row, class: "cursor" }));
  if (records.length) {
    svg.append(s("text", { x: label, y: lanes.length * row + 14, class: "lane" }, [`cycle ${records[0].cycle}`]));
    svg.append(s("text", { x: label + records.length * cell, y: lanes.length * row + 14, class: "lane", "text-anchor": "end" },
      [`${records[records.length - 1].cycle}`]));
  }
  return svg;
}

async function render() {
  const view = current;
  if (!view?.records) return;
  const n = view.api.cursor.get();
  const r = view.records[n - view.api.first];
  clear(view.now).append(h("h2", {}, [`Cycle ${n}`]), csrPanel(r));
  const from = Math.max(0, n - view.api.first - SPAN);
  clear(view.line).append(h("h2", {}, ["Timeline"]), timeline(view.records.slice(from, n - view.api.first + SPAN + 1), n));
  for (const row of view.table.querySelectorAll("tr[data-cycle]")) row.classList.toggle("cursor", Number(row.dataset.cycle) === n);
}

export async function mount(container, api) {
  unmount();
  const root = h("div", { id: "csr" }, [h("p", {}, ["The CSR file of the recorded run, its traps and interrupts. ",
    "Chisel: ", repoLink(T3_SRC + "CsrFile.scala", "CsrFile"), ", ", repoLink(T3_SRC + "CPU.scala", "CPU (T3)"),
    "; the fields are those of docs/observation.md, \"csr\"."])]);
  container.append(root);
  // the view's state; a mount that an unmount (or a newer mount) overtook while it read does nothing
  const token = { api, root, records: null };
  current = token;
  if (!api.groups.includes("csr")) {
    root.append(h("p", { class: "warn" }, ["This bundle has no csr group of probe fields."]));
    return;
  }
  const status = h("p", { class: "note" }, ["reading the window..."]);
  root.append(status);
  let records;
  try {
    records = await api.window(api.first, api.last + 1);
  } catch (e) {
    if (current === token) status.textContent = `The window could not be read: ${e.message}`;
    return;
  }
  if (current !== token) return;
  const list = traps(records);
  const now = h("section", { class: "panel", id: "csr-now" });
  const line = h("section", { class: "panel", id: "csr-line" });
  const path = h("section", { class: "panel", id: "csr-path" }, [h("h2", {}, ["Path of a trap"]),
    h("p", { class: "note" }, ["Choose \"follow\" on a trap."])]);
  const follow = (t) => {
    clear(path).append(h("h2", {}, [`Path of the trap in cycle ${t.cycle}`]), pathPanel(api, t));
    api.cursor.set(t.interrupt && t.lineRise !== null ? t.lineRise : t.cycle);
  };
  const table = h("table", { class: "csr-traps" }, [
    h("thead", {}, [h("tr", {}, ["cycle", "cause", "pc (record)", "mepc after", "mcause after", "handler's first instruction", ""]
      .map((t) => h("th", {}, [t])))]),
    h("tbody", {}, list.map((t) => trapRow(t, follow)))]);
  status.textContent = `${list.length} trap${list.length === 1 ? "" : "s"} in the window of cycles ${api.first} to ${api.last}` +
    (api.first > 0 ? " (a trap before the window is not listed)" : "");
  root.append(now, h("section", { class: "panel", id: "csr-trap-list" }, [h("h2", {}, ["Traps"]), h("div", { class: "table-wrap" }, [table])]),
    path, line);
  Object.assign(token, { records, now, line, table });
  api.cursor.addEventListener("change", render);
  await render();
}

export function unmount() {
  if (!current) return;
  current.api.cursor.removeEventListener("change", render);
  current.root.remove();
  current = null;
}
