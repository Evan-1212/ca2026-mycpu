// SPDX-License-Identifier: MIT
// The pipeline of T4 and T5: the five stages at the cursor, a
// pipeline chart (instructions by cycles) around it with its stalls, flushes
// and bubbles, the hazards of the whole window (the cycles each instruction
// waited in ID for the hazard unit, and the bubbles each taken branch or jump
// cost), and the same hazards of bundles of the other variants side by side.
// lib/pipe.js follows the instructions from the `pipe` group; nothing here
// decides what the core decides.

import { BundleError } from "../lib/bundle.js";
import { compareControls } from "../lib/compare.js";
import { clear, h, pressable, repoLink, s } from "../lib/dom.js";
import { formatValue, hex8 } from "../lib/format.js";
import { follow, hazards, LETTERS, STAGES } from "../lib/pipe.js";

const SPAN = 20; // cycles of the chart on each side of the cursor
const T4_SRC = "4-pipeline/src/main/scala/mycpu/pipeline/";
const T4_TEST = "4-pipeline/src/test/scala/mycpu/pipeline/";

let current = null;

function instText(api, pc, inst) {
  return api.listing(pc) ?? formatValue(inst, 32);
}

function stagePanel(api, r) {
  const p = r.pipe;
  const flag = (v, name) => (v ? h("span", { class: `pp-flag pp-${name}` }, [name]) : "");
  const rows = STAGES.map((st) => {
    const g = p[st];
    const what = st === "writeback" ? (g.valid ? "a register write" : "–")
      : g.valid ? `${hex8(g.pc)} ${instText(api, g.pc, g.inst)}` : "bubble";
    return h("tr", { "data-stage": st, class: g.valid ? "" : "pp-empty" }, [
      h("th", {}, [`${LETTERS[st]} ${st}`]),
      h("td", { class: "mono", "data-col": "what" }, [what]),
      h("td", { "data-col": "flags" }, [flag(g.stall, "stall"), flag(g.flush, "flush"), flag(g.bubble, "bubble")]),
    ]);
  });
  return h("div", {}, [
    h("div", { class: "table-wrap" }, [h("table", { class: "pp-stages" }, [h("tbody", {}, rows)])]),
    h("p", {}, [h("span", { "data-field": "pipe.loadUseStall" }, [`hazard unit holds ID (pipe.loadUseStall): ${p.loadUseStall}`])]),
    h("p", { class: "concept" }, ["Forwarding paths (concept): which operand EX takes from EX/MEM or MEM/WB is decided in the ",
      "bootcamp's Forward (notebook 5.7), an exercise; the bundle does not record it (pipe.forwardA and forwardB are 0)."]),
  ]);
}

/** The chart of the records `from` to `to` (indices into view.records). */
function chart(view, cycle) {
  const { api, records, followed } = view;
  const lo = Math.max(api.first, cycle - SPAN);
  const hi = Math.min(api.last, cycle + SPAN);
  const shown = followed.instances.filter((i) => [...i.cells.keys()].some((n) => n >= lo && n <= hi) ||
    (i.flushed !== null && i.flushed + 1 >= lo && i.flushed + 1 <= hi));
  const cols = hi - lo + 1;
  const head = h("tr", {}, [h("th", {}, ["pc"]), h("th", {}, ["instruction"]),
    ...Array.from({ length: cols }, (_, k) => h("th", { class: lo + k === cycle ? "cursor" : "", "data-cycle": lo + k },
      [String((lo + k) % 1000).padStart(3, "0")]))]);
  const body = shown.map((i) => h("tr", { "data-instance": i.id, "data-pc": i.pc }, [
    h("td", { class: "mono" }, [hex8(i.pc)]),
    h("td", { class: "mono" }, [instText(api, i.pc, i.inst)]),
    ...Array.from({ length: cols }, (_, k) => {
      const n = lo + k;
      const c = i.cells.get(n);
      if (c) {
        const cls = ["pp-cell", `pp-${c.stage}`, c.stall ? "pp-stall" : "", c.loadUse ? "pp-lu" : "",
          c.stage === "writeback" && c.writes === false ? "pp-nowrite" : "", n === cycle ? "cursor" : ""].filter(Boolean).join(" ");
        return h("td", { class: cls, "data-cycle": n, "data-stage": c.stage, title: c.loadUse ? "held by the hazard unit" : "" },
          [LETTERS[c.stage] + (c.stall ? "*" : "")]);
      }
      if (i.flushed !== null && n === i.flushed + 1) return h("td", { class: "pp-cell pp-flushed", "data-cycle": n }, ["×"]);
      return h("td", { class: n === cycle ? "cursor" : "" }, [""]);
    }),
  ]));
  // the bubbles of EX: what an instruction that was not there left
  const bubbles = h("tr", { class: "pp-bubbles" }, [h("td", {}, [""]), h("td", {}, ["EX bubble"]),
    ...Array.from({ length: cols }, (_, k) => {
      const r = records[lo + k - api.first];
      return h("td", { "data-cycle": lo + k, class: r.pipe.execute.valid ? "" : "pp-cell pp-bubble" }, [r.pipe.execute.valid ? "" : "o"]);
    })]);
  return h("div", { class: "table-wrap" }, [h("table", { class: "pp-chart" }, [h("thead", {}, [head]), h("tbody", {}, [...body, bubbles])]),
    h("p", { class: "note" }, ["F fetch (the word kept), D decode, E execute, M memory (commit), W write-back (dashed: no ",
      "register written); * the stage keeps its instruction, red the hazard unit holds ID; × flushed; o a bubble in EX. ",
      "The column numbers are the cycle modulo 1000."])]);
}

function hazardTable(api, list) {
  const rows = list.filter((x) => x.stalls || x.flushSlots).map((x) => h("tr", { "data-pc": x.pc, "data-first": x.first,
    "data-stalls": x.stalls, "data-flush": x.flushSlots ?? "", ...pressable(() => api.cursor.set(x.first)) }, [
    h("td", {}, [String(x.first)]), h("td", { class: "mono" }, [hex8(x.pc)]), h("td", { class: "mono" }, [instText(api, x.pc, x.inst)]),
    h("td", {}, [String(x.stalls)]), h("td", {}, [x.flushSlots === null ? "past the window" : String(x.flushSlots)]),
  ]));
  return h("div", { class: "table-wrap" }, [h("table", { class: "pp-hazards" }, [
    h("thead", {}, [h("tr", {}, ["first cycle", "pc", "instruction", "cycles held in ID by the hazard unit",
      "bubbles after its taken branch or jump"].map((t) => h("th", {}, [t])))]),
    h("tbody", {}, rows)])]);
}

/** The hazards of each instruction occurrence, keyed by pc and occurrence number. */
function keyed(list) {
  const seen = new Map();
  const out = new Map();
  for (const x of list) {
    const k = seen.get(x.pc) ?? 0;
    seen.set(x.pc, k + 1);
    out.set(`${x.pc}:${k}`, x);
  }
  return out;
}

/** The variants side by side: for every occurrence that stalls or flushes in one of them, a bar per variant. */
function variantsChart(view) {
  const runs = [{ variant: view.api.header.variant, list: view.hazards }, ...view.compared];
  const maps = runs.map((r) => keyed(r.list));
  const keys = [];
  const seen = new Set();
  for (const m of maps) {
    for (const [k, x] of m) if ((x.stalls || x.flushSlots) && !seen.has(k)) { seen.add(k); keys.push(k); }
  }
  keys.sort((a, b) => (maps[0].get(a)?.first ?? Infinity) - (maps[0].get(b)?.first ?? Infinity));
  const max = Math.max(1, ...maps.flatMap((m) => [...m.values()].map((x) => (x.stalls ?? 0) + (x.flushSlots ?? 0))));
  const unit = 18;
  const rows = keys.map((k) => {
    const [pc, occ] = k.split(":").map(Number);
    const x0 = maps.find((m) => m.has(k)).get(k);
    return h("tr", { "data-pc": pc, "data-occurrence": occ }, [
      h("td", { class: "mono" }, [hex8(pc)]), h("td", { class: "mono" }, [instText(view.api, pc, x0.inst)]),
      ...runs.map((r, j) => {
        const x = maps[j].get(k);
        const st = x?.stalls ?? 0;
        const fl = x?.flushSlots ?? 0;
        const svg = s("svg", { width: unit * max + 2, height: 14, class: "pp-bar" }, [
          s("rect", { x: 0, y: 2, width: unit * st, height: 10, class: "pp-bar-stall" }),
          s("rect", { x: unit * st, y: 2, width: unit * fl, height: 10, class: "pp-bar-flush" })]);
        return h("td", { "data-variant": r.variant, "data-stalls": x ? st : "", "data-flush": x ? fl : "" },
          [svg, ` ${x ? `${st} + ${fl}` : "–"}`]);
      }),
    ]);
  });
  return h("div", { class: "table-wrap" }, [h("table", { class: "pp-variants" }, [
    h("thead", {}, [h("tr", {}, [h("th", {}, ["pc"]), h("th", {}, ["instruction"]), ...runs.map((r) => h("th", {}, [r.variant]))])]),
    h("tbody", {}, rows)]),
    h("p", { class: "note" }, ["Each bar: cycles held in ID by the hazard unit (solid), then the bubbles after a taken branch ",
      "or jump (light). A row is one occurrence of an instruction, counted in program order in each run."])]);
}

function comparePanel(view) {
  const { actions, message } = compareControls({
    prefix: "pp", addLabel: "add the served bundle", failed: "not added", header: view.api.header,
    isCurrent: () => current === view,
    async use(other, source) {
      if (!other.groups.includes("pipe")) throw new BundleError("it has no pipe group");
      const list = hazards(await other.window(other.first, other.last + 1));
      return () => {
        view.compared = view.compared.filter((c) => c.variant !== other.header.variant);
        view.compared.push({ variant: other.header.variant, list, label: source.label });
        clear(view.variants).append(variantsChart(view));
        return `added ${other.header.variant} (${source.label})`;
      };
    },
  });
  return h("div", { class: "pp-compare" }, [
    h("p", {}, ["Compare with a bundle of the same program on another variant (make obs PROG=",
      h("code", {}, [view.api.header.program]), " VARIANT=...):"]),
    actions, message]);
}

async function render() {
  const view = current;
  if (!view?.records) return;
  const n = view.api.cursor.get();
  clear(view.now).append(h("h2", {}, [`Stages in cycle ${n}`]), stagePanel(view.api, view.records[n - view.api.first]));
  clear(view.chart).append(h("h2", {}, ["Pipeline chart"]), chart(view, n));
}

export async function mount(container, api) {
  unmount();
  const root = h("div", { id: "pipeline" }, [h("p", {}, ["The pipeline of the recorded run. Chisel: ",
    repoLink(T4_SRC + "CPU.scala", "CPU"), ", ", repoLink(T4_SRC + "Hazard.scala", "HazardUnit"), ", ",
    repoLink(T4_SRC + "Stage.scala", "Stage"), ", ", repoLink(T4_SRC + "Fetch.scala", "Fetch"), ". Tests: ",
    repoLink(T4_TEST + "HazardSpec.scala", "HazardSpec"), ", ", repoLink(T4_TEST + "StageSpec.scala", "StageSpec"),
    ". The fields are those of docs/observation.md, \"pipe\"."])]);
  container.append(root);
  const token = { api, root, records: null, compared: [] };
  current = token;
  if (!api.groups.includes("pipe")) {
    root.append(h("p", { class: "warn" }, ["This bundle has no pipe group of probe fields."]));
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
  const followed = follow(records);
  const list = hazards(records, followed);
  Object.assign(token, {
    records, followed, hazards: list,
    now: h("section", { class: "panel", id: "pp-now" }), chart: h("section", { class: "panel", id: "pp-chart" }),
    variants: h("div", { id: "pp-variants" }),
  });
  status.textContent = `${api.header.variant}: cycles ${api.first} to ${api.last}` +
    (api.first > 0 ? "; an instruction already in the pipeline at the first cycle is followed from there" : "");
  token.variants.append(variantsChart(token));
  root.append(token.now, token.chart,
    h("section", { class: "panel", id: "pp-hazard-list" }, [h("h2", {}, ["Hazards in the window"]), hazardTable(api, list)]),
    h("section", { class: "panel", id: "pp-compare" }, [h("h2", {}, ["Variants side by side"]), comparePanel(token), token.variants]));
  api.cursor.addEventListener("change", render);
  await render();
}

export function unmount() {
  if (!current) return;
  current.api.cursor.removeEventListener("change", render);
  current.root.remove();
  current = null;
}
