// SPDX-License-Identifier: MIT
// The SoC of T5 and its AXI4-Lite bus: the block diagram of the SoC
// with the D12 memory map, the five channels of the data master at the cursor
// (`bus` group: valid, ready and payload, all comb), and the transactions of
// the window, each placed on the slave its address falls in. A channel
// transfers in a cycle with valid and ready both 1. AXI4-Lite has no IDs: a
// master's writes complete in order, so an AW and a W transfer (in either
// order, or in one cycle) make a write, which the next B transfer answers; an
// AR and the next R make a read. A B (or R) that comes when no write (read) of
// the window is open answers one that began before the window: it is shown as
// such, with what the window has of it, and the pairing goes on after it.

import { clear, h, pressable, repoLink, s } from "../lib/dom.js";
import { formatValue, hex8 } from "../lib/format.js";

const T5_SRC = "5-soc/src/main/scala/mycpu/soc/";

// D12 (5-soc/README.md, "The memory map"; MemMap): the slaves of the crossbar, in Soc.regions order
const SLAVES = [
  { id: "rom", name: "boot ROM", base: 0x00001000, size: 0x1000 },
  { id: "clint", name: "CLINT", base: 0x02000000, size: 0x10000 },
  { id: "uart", name: "UART", base: 0x10000000, size: 0x1000 },
  { id: "simctrl", name: "SimCtrl", base: 0x10001000, size: 0x1000 },
  { id: "vga", name: "VGA", base: 0x20000000, size: 0x10000 },
  { id: "ram", name: "RAM", base: 0x80000000, size: null },
];
const CHANNELS = [["aw", "AW", "addr"], ["w", "W", "data"], ["b", "B", "resp"], ["ar", "AR", "addr"], ["r", "R", "data"]];

let current = null;

function slaveOf(addr, ramBytes) {
  return SLAVES.find((sl) => addr >= sl.base && addr - sl.base < (sl.size ?? ramBytes)) ?? null;
}

/**
 * The transactions of the records: {writes, reads}, each with the cycles of its transfers (null
 * for one outside the window) and `before`: it began before the window's first cycle. In a
 * cycle the response is taken first: it answers an older transaction than an address or data
 * transfer of the same cycle.
 */
export function transactions(records) {
  const fire = (r, ch) => r.bus[ch].valid && r.bus[ch].ready;
  const writes = [], reads = [];
  let aws = [], ws = []; // AW and W transfers not yet paired with each other
  let open = []; // writes with AW and W, not answered
  let ars = []; // reads not answered
  const write = (a, d) => ({ kind: "write", addr: a?.addr ?? null, data: d?.data ?? null, strb: d?.strb ?? null, resp: null,
    start: Math.min(a?.cycle ?? Infinity, d?.cycle ?? Infinity), aw: a?.cycle ?? null, w: d?.cycle ?? null, b: null, before: false });
  for (const r of records) {
    const x = r.bus;
    if (fire(r, "b")) {
      let t = open.shift();
      if (!t) { // its AW, its W or both came before the window
        t = write(aws.shift() ?? null, ws.shift() ?? null);
        t.before = true;
        if (t.start === Infinity) t.start = null;
        writes.push(t);
      }
      t.resp = x.b.resp;
      t.b = r.cycle;
    }
    if (fire(r, "r")) {
      let t = ars.shift();
      if (!t) {
        t = { kind: "read", addr: null, data: null, resp: null, start: null, ar: null, r: null, before: true };
        reads.push(t);
      }
      Object.assign(t, { data: x.r.data, resp: x.r.resp, r: r.cycle });
    }
    if (fire(r, "aw")) aws.push({ cycle: r.cycle, addr: x.aw.addr });
    if (fire(r, "w")) ws.push({ cycle: r.cycle, data: x.w.data, strb: x.w.strb });
    while (aws.length && ws.length) {
      const t = write(aws.shift(), ws.shift());
      writes.push(t);
      open.push(t);
    }
    if (fire(r, "ar")) {
      const t = { kind: "read", addr: x.ar.addr, data: null, resp: null, start: r.cycle, ar: r.cycle, r: null, before: false };
      reads.push(t);
      ars.push(t);
    }
  }
  // an AW or W whose other half is past the window's end
  for (const a of aws) writes.push(write(a, null));
  for (const d of ws) writes.push(write(null, d));
  return { writes, reads };
}

const RESP = ["OKAY", "EXOKAY", "SLVERR", "DECERR"];

function diagram(r, active, ramBytes) {
  const svg = s("svg", { viewBox: "0 0 640 300", class: "soc-diagram", role: "img", "aria-label": "block diagram of the SoC" });
  const box = (x, y, w, hh, label, cls, id) => s("g", { class: `soc-box ${cls}`, "data-block": id }, [
    s("rect", { x, y, width: w, height: hh, rx: 4 }), s("text", { x: x + w / 2, y: y + hh / 2 + 5, "text-anchor": "middle" }, [label])]);
  svg.append(box(10, 40, 150, 90, "CPU (T4 pipeline)", "", "cpu"), box(10, 170, 150, 40, "I master", "concept", "imaster"),
    box(180, 40, 120, 40, "D master", active ? "busy" : "", "dmaster"), box(330, 20, 90, 260, "crossbar", "", "xbar"));
  SLAVES.forEach((sl, i) => {
    const y = 20 + i * 44;
    const on = active && slaveOf(active.addr ?? -1, ramBytes) === sl;
    svg.append(box(450, y, 180, 36, `${sl.name} ${hex8(sl.base)}`, on ? "target" : "", sl.id));
    svg.append(s("line", { x1: 420, y1: y + 18, x2: 450, y2: y + 18, class: on ? "soc-link on" : "soc-link" }));
  });
  // the five channels of the data master, between it and the crossbar
  CHANNELS.forEach(([ch, name], i) => {
    const x = 180 + i * 24;
    const v = r.bus[ch].valid;
    const rd = r.bus[ch].ready;
    svg.append(s("g", { class: `soc-ch ${v && rd ? "fire" : v ? "valid" : ""}`, "data-channel": ch }, [
      s("rect", { x, y: 90, width: 20, height: 40 }), s("text", { x: x + 10, y: 145, "text-anchor": "middle" }, [name]),
      s("text", { x: x + 10, y: 108, "text-anchor": "middle", class: "small" }, [String(v)]),
      s("text", { x: x + 10, y: 124, "text-anchor": "middle", class: "small" }, [String(rd)])]));
  });
  svg.append(s("text", { x: 180, y: 162, class: "small" }, ["valid / ready of each channel"]));
  svg.append(s("line", { x1: 300, y1: 60, x2: 330, y2: 60, class: active ? "soc-link on" : "soc-link" }));
  svg.append(s("line", { x1: 160, y1: 190, x2: 330, y2: 190, class: "soc-link concept" }));
  svg.append(s("text", { x: 170, y: 205, class: "small" }, ["instruction fetches: not probed (concept)"]));
  return svg;
}

function channelTable(r) {
  return h("table", { class: "soc-channels" }, [
    h("thead", {}, [h("tr", {}, ["channel", "valid", "ready", "payload"].map((t) => h("th", {}, [t])))]),
    h("tbody", {}, CHANNELS.map(([ch, name]) => {
      const c = r.bus[ch];
      const payload = ch === "aw" || ch === "ar" ? `addr ${formatValue(c.addr, 32)}`
        : ch === "w" ? `data ${formatValue(c.data, 32)} strb ${formatValue(c.strb, 4)}`
          : ch === "b" ? `resp ${RESP[c.resp]}` : `data ${formatValue(c.data, 32)} resp ${RESP[c.resp]}`;
      return h("tr", { "data-channel": ch, class: c.valid && c.ready ? "fire" : "" }, [h("th", {}, [name]),
        h("td", { "data-field": `bus.${ch}.valid` }, [String(c.valid)]), h("td", { "data-field": `bus.${ch}.ready` }, [String(c.ready)]),
        h("td", { class: "mono" }, [payload])]);
    }))]);
}

function transactionTable(view, list) {
  const ramBytes = view.api.header.ram_bytes;
  return h("table", { class: "soc-transactions" }, [
    h("thead", {}, [h("tr", {}, ["kind", "address", "slave", "data", "strobes", "response", "cycles"].map((t) => h("th", {}, [t])))]),
    h("tbody", {}, list.map((t) => h("tr", { "data-kind": t.kind, "data-start": t.start ?? "", "data-addr": t.addr ?? "",
      "data-data": t.data ?? "", ...(t.start !== null ? pressable(() => view.api.cursor.set(t.start)) : {}) }, [
      h("td", {}, [t.kind]), h("td", { class: "mono" }, [t.addr === null ? "–" : hex8(t.addr)]),
      h("td", {}, [t.addr === null ? "–" : slaveOf(t.addr, ramBytes)?.name ?? "no slave (DECERR)"]),
      h("td", { class: "mono" }, [t.data === null ? "–" : hex8(t.data)]),
      h("td", { class: "mono" }, [t.kind === "write" && t.strb !== null ? formatValue(t.strb, 4) : ""]),
      h("td", {}, [t.resp === null ? "open at the window's end" : RESP[t.resp] + (t.before ? " (began before the window)" : "")]),
      h("td", { class: "mono" }, [t.kind === "write" ? `AW ${t.aw ?? "–"} W ${t.w ?? "–"} B ${t.b ?? "–"}` : `AR ${t.ar ?? "–"} R ${t.r ?? "–"}`]),
    ])))]);
}

async function render() {
  const view = current;
  if (!view?.records) return;
  const n = view.api.cursor.get();
  const r = view.records[n - view.api.first];
  // the transaction the cursor is in: started, and not yet answered
  const ends = (t) => (t.kind === "write" ? t.b : t.r); // null: open at the window's end
  const open = view.all.find((t) => t.start !== null && t.start <= n && (ends(t) === null || ends(t) >= n)) ?? null;
  clear(view.now).append(h("h2", {}, [`Cycle ${n}`]), diagram(r, open, view.api.header.ram_bytes), channelTable(r),
    h("p", { id: "soc-open" }, [open ? `in a ${open.kind} of ${hex8(open.addr ?? 0)} (${slaveOf(open.addr ?? -1, view.api.header.ram_bytes)?.name ?? "no slave"})`
      : "no data transaction is open"]));
  const near = view.all.filter((t) => t.start !== null && Math.abs(t.start - n) <= 64).slice(0, 40);
  clear(view.list).append(h("h2", {}, ["Transactions near the cursor"]), h("div", { class: "table-wrap" }, [transactionTable(view, near)]),
    h("p", { class: "note" }, [`${view.tx.writes.length} writes and ${view.tx.reads.length} reads in the window`]));
}

export async function mount(container, api) {
  unmount();
  const root = h("div", { id: "soc" }, [h("p", {}, ["The SoC of the recorded run and its data bus. Chisel: ",
    repoLink(T5_SRC + "Soc.scala", "Soc"), ", ", repoLink(T5_SRC + "Axi4LiteMaster.scala", "Axi4LiteMaster"), ", ",
    repoLink(T5_SRC + "Axi4LiteXbar.scala", "Axi4LiteXbar"), ", ", repoLink(T5_SRC + "Axi4LiteChecker.scala", "Axi4LiteChecker"),
    ". The memory map is D12; the fields are those of docs/observation.md, \"bus\"."])]);
  container.append(root);
  const token = { api, root, records: null };
  current = token;
  if (!api.groups.includes("bus")) {
    root.append(h("p", { class: "warn" }, ["This bundle has no bus group of probe fields."]));
    return;
  }
  let records;
  try {
    records = await api.window(api.first, api.last + 1);
  } catch (e) {
    if (current === token) root.append(h("p", { class: "error" }, [`The window could not be read: ${e.message}`]));
    return;
  }
  if (current !== token) return;
  const tx = transactions(records);
  Object.assign(token, { records, tx, all: [...tx.writes, ...tx.reads].sort((a, b) => (a.start ?? 0) - (b.start ?? 0)),
    now: h("section", { class: "panel", id: "soc-now" }), list: h("section", { class: "panel", id: "soc-list" }) });
  root.append(token.now, token.list);
  api.cursor.addEventListener("change", render);
  await render();
}

export function unmount() {
  if (!current) return;
  current.api.cursor.removeEventListener("change", render);
  current.root.remove();
  current = null;
}
