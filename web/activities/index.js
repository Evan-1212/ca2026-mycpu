// SPDX-License-Identifier: MIT
// Predict-then-reveal activities (T1 to T5). An activity picks
// cycles of the open bundle, asks for a prediction, and then shows the
// recorded values and compares. Every expected answer is computed from the
// bundle when the activity starts (`answer` below); nothing is written here
// by hand. The questions name what they ask with data attributes, so that a
// browser test can compute the same answer from the bundle on its own.

import { traps } from "../lib/csr.js";
import { clear, h, repoLink } from "../lib/dom.js";
import { formatValue, hex6, retireLine, testCounts } from "../lib/format.js";
import { hazards } from "../lib/pipe.js";
import { parsePpm, pixelSource, wordsAt } from "../lib/vgafb.js";

let current = null;

const T1_SRC = "1-single-cycle/src/main/scala/mycpu/singlecycle/";
const T2_SRC = "2-rv32im/src/main/scala/mycpu/rv32im/";
const T3_SRC = "3-csr-trap/src/main/scala/mycpu/csrtrap/";
const T4_SRC = "4-pipeline/src/main/scala/mycpu/pipeline/";
const T5_SRC = "5-soc/src/main/scala/mycpu/soc/";
const UART_TXDATA = 0x10000000;

/** The pc of the instruction a record is about: the single-cycle core's, or the commit stage's. */
function pcOf(r) {
  return r.core ? r.core.pc : r.pipe ? r.pipe.memory.pc : null;
}

/** Every record of the window, read once per activity start. */
async function all(api) {
  return api.window(api.first, api.last + 1);
}

/**
 * The UART frames of uart.txd in the window (which must start at cycle 0, when the line is
 * idle): {start, byte} for each start bit, sampling every bit in its middle (uart_divider cycles
 * per bit, docs/observation.md), as the harness's decoder does. Kept here rather than imported
 * from web/waveform/uart.js, so that this view works in a tree without the waveform view.
 */
function uartFrames(rs, first, divider) {
  const out = [];
  const half = Math.floor(divider / 2);
  let i = 0;
  while (i < rs.length) {
    if (rs[i].uart.txd) { i++; continue; }
    const at = (bit) => i + half + bit * divider;
    if (at(9) >= rs.length) break;
    if (rs[at(0)].uart.txd) { i = at(0); continue; } // a glitch, not a start bit
    let byte = 0;
    for (let b = 0; b < 8; b++) byte |= rs[at(1 + b)].uart.txd << b;
    const stop = rs[at(9)].uart.txd;
    out.push({ start: first + i, byte, stop });
    i = at(9) + 1;
    // after a framing error the harness's decoder waits for the line to return to 1 (sim/uart.cpp)
    if (!stop) while (i < rs.length && !rs[i].uart.txd) i++;
  }
  return out;
}

/**
 * The store of a UART frame (web/waveform/link.js has the reasoning): the (txCount + 1)-th most
 * recent full-word store to TXDATA that retired before the frame's start bit.
 */
function storeOfFrame(rs, first, frame) {
  const stores = [];
  for (const r of rs) {
    if (r.cycle >= frame.start) break;
    const m = r.retire?.mem;
    if (m && m.write && m.addr === UART_TXDATA && m.bytes === 4) stores.push({ cycle: r.cycle, pc: r.retire.pc, data: m.data });
  }
  const k = stores.length - 1 - rs[frame.start - first].uart.txCount;
  return k >= 0 ? stores[k] : null;
}

// ---------------------------------------------------------------------------
// Answers, each computed from the records of the bundle

async function records(api, from, to) {
  return api.window(from, to + 1);
}

/** The expected answer of a question, from the bundle. null: the window does not hold what it needs. */
async function answer(api, q) {
  switch (q.kind) {
    case "field": {
      return api.value(await api.cycle(q.cycle), q.field) ?? null;
    }
    case "next-pc":
      return q.cycle + 1 <= api.last ? (await api.cycle(q.cycle + 1)).core.pc : null;
    case "hold": {
      const pc = (await api.cycle(q.cycle)).core.pc;
      let n = q.cycle;
      while (n + 1 <= api.last && (await api.cycle(n + 1)).core.pc === pc) n++;
      return n === api.last ? null : n - q.cycle + 1;
    }
    case "count-busy":
      return (await records(api, q.from, q.to)).filter((r) => r.div.busy === 1).length;
    case "retire-cycle":
      return (await api.cycle(q.cycle)).retire ? q.cycle : api.nextRetire(q.cycle, 1);
    case "count-retire":
      return (await records(api, q.from, q.to)).filter((r) => r.retire).length;
    case "distinct-pc":
      return new Set((await records(api, q.from, q.to)).map(pcOf)).size;
    case "tests-pass":
      return api.tests ? testCounts(api.tests).pass : null;
    case "trap": {
      const t = traps(await all(api)).find((x) => x.cycle === q.cycle);
      if (!t) return null;
      if (q.what === "mcause") return t.mcause;
      if (q.what === "mepc") return t.mepc;
      if (q.what === "handler") return t.handler ? t.handler.pc : null;
      if (q.what === "delay") return t.lineRise === null ? null : t.cycle - t.lineRise;
      return null;
    }
    case "hazard": {
      const x = hazards(await all(api)).filter((y) => y.pc === q.pc)[q.occurrence];
      if (!x) return null;
      return q.what === "stalls" ? x.stalls : x.flushSlots;
    }
    case "uart": {
      const rs = await all(api);
      const f = uartFrames(rs, api.first, api.header.uart_divider)[q.frame];
      if (!f) return null;
      if (q.what === "byte") return f.byte;
      const st = storeOfFrame(rs, api.first, f);
      if (q.what === "store-pc") return st ? st.pc : null;
      if (q.what === "delay") return st ? f.start - st.cycle : null;
      return null;
    }
    case "pixel": {
      const stores = await api.stores();
      const entry = api.manifest.vga.find((e) => e.frame === q.frame);
      if (!stores || !entry || entry.cycle === undefined) return null;
      const p = pixelSource(wordsAt(stores.stores, entry.cycle), q.frame, q.x, q.y);
      if (q.what === "addr") return p.addr;
      if (q.what === "index") return p.index;
      if (q.what === "store-pc") return p.store ? p.store.pc : null;
      return null;
    }
    case "sources-pc": {
      const rs = await records(api, q.from, q.to);
      const sources = [];
      if (rs.every((r) => r.retire)) sources.push("retire");
      if (rs.every((r) => pcOf(r) !== null)) sources.push("waveform");
      return sources.join(",");
    }
    default:
      throw new Error(`unknown question kind ${q.kind}`);
  }
}

function parseNumber(text) {
  const t = text.trim().toLowerCase().replace(/_/g, "");
  if (/^0x[0-9a-f]+$/.test(t)) return parseInt(t.slice(2), 16);
  if (/^[0-9]+$/.test(t)) return Number(t);
  return null;
}

// ---------------------------------------------------------------------------
// Choosing the cycles

function mnemonic(api, pc) {
  const text = api.listing(pc);
  return text ? text.split(" ")[0] : null;
}

/**
 * The first retired add, the first lw, and the first beq taken and not taken of the window
 * (found through the listing), each as {cycle, mnemonic, text, record}. The scan stops when
 * all four are found.
 */
async function picksOf(api) {
  const found = new Map();
  const wanted = ["add", "lw", "beq0", "beq1"];
  for (const r of await api.window(api.first, api.last + 1)) {
    const m = r.retire ? mnemonic(api, r.retire.pc) : null;
    // the disassembler prints `beq rs, zero` as `beqz rs`: the same instruction
    const family = m === "beqz" ? "beq" : m;
    const key = family === "beq" ? `beq${r.core.branchTaken}` : family;
    if (!wanted.includes(key) || found.has(key)) continue;
    found.set(key, { cycle: r.cycle, mnemonic: family, text: api.listing(r.retire.pc), record: r });
    if (found.size === wanted.length) break;
  }
  return [...found.values()].sort((a, b) => a.cycle - b.cycle);
}

/** The first division of the window: the first cycle with div.reqValid = 1, and the cycle it retires. */
async function firstDivision(api) {
  for (let n = api.first; n <= api.last; n++) {
    const r = await api.cycle(n);
    if (r.div?.reqValid === 1) {
      const done = r.retire ? n : await api.nextRetire(n, 1);
      return done === null ? null : { start: n, done };
    }
  }
  return null;
}

/** A stretch to look at: the first division on T2, else the first 8 cycles of the window. */
async function stretch(api) {
  const d = await firstDivision(api);
  if (d) return { from: d.start, to: d.done, what: "the first division of the window" };
  return { from: api.first, to: Math.min(api.last, api.first + 7), what: "the first 8 cycles of the window" };
}

// ---------------------------------------------------------------------------
// The activities

const ACTIVITIES = [
  {
    id: "single-cycle",
    title: "The single-cycle datapath: add, lw, beq",
    tasks: ["T1", "T2"],
    sources: [[T1_SRC + "Execute.scala", "Execute"], [T1_SRC + "MemoryAccess.scala", "MemoryAccess"],
      [T1_SRC + "WriteBack.scala", "WriteBack"], [T1_SRC + "InstructionFetch.scala", "InstructionFetch"]],
    tests: ["T1ExecuteSpec", "T1MemoryAccessSpec", "T1FetchSpec"],
    groups: ["core"],
    needs: "the program listing of a shipped example (add, lw or beq), to find the instructions",
    async build(api) {
      const picks = await picksOf(api);
      if (!picks.length) return null;
      const questions = [];
      for (const p of picks) {
        const r = p.record;
        const given = `cycle ${p.cycle}: ${p.text} at pc ${formatValue(r.core.pc, 32)}; rs1 value ` +
          `${formatValue(r.core.rs1Value, 32)}, rs2 value ${formatValue(r.core.rs2Value, 32)}`;
        const ask = (kind, field, prompt) => ({ kind, field, cycle: p.cycle, prompt, given, mnemonic: p.mnemonic });
        if (p.mnemonic === "add") {
          questions.push(ask("field", "core.aluResult", "the ALU's result (core.aluResult)"),
            ask("field", "core.wbValue", "the value written to rd (core.wbValue)"),
            ask("next-pc", null, "the PC in the next cycle (core.pc of cycle + 1)"));
        } else if (p.mnemonic === "lw") {
          questions.push(ask("field", "core.aluResult", "the byte address (core.aluResult)"),
            ask("field", "core.memReqAddr", "the word address on the data port (core.memReqAddr)"),
            ask("field", "core.wbSource", "the write-back select (core.wbSource: 0 execute, 1 load, 2 link)"),
            ask("field", "core.wbValue", "the value written to rd (core.wbValue)"));
        } else {
          questions.push(ask("field", "core.branchTaken", "whether control goes elsewhere (core.branchTaken, 0 or 1)"),
            ask("next-pc", null, "the PC in the next cycle (core.pc of cycle + 1)"));
        }
      }
      return questions;
    },
  },
  {
    id: "divider",
    title: "The multi-cycle divider: the PC holds while it is busy",
    tasks: ["T2"],
    sources: [[T2_SRC + "Divider.scala", "Divider"], [T2_SRC + "Execute.scala", "Execute"], [T2_SRC + "CPU.scala", "CPU"]],
    tests: ["DivSpec", "StallSpec"],
    groups: ["core", "div"],
    needs: "a window with a division request (div.reqValid = 1) and its retire record",
    async build(api) {
      const d = await firstDivision(api);
      if (!d) return null;
      const r = await api.cycle(d.start);
      const text = api.listing(r.core.pc);
      const given = `cycle ${d.start}: ${text ?? "the instruction"} at pc ${formatValue(r.core.pc, 32)} requests a ` +
        `division (div.reqValid = 1, op ${r.div.op}), dividend ${formatValue(r.div.dividend, 32)}, ` +
        `divisor ${formatValue(r.div.divisor, 32)}`;
      const q = (kind, prompt, extra) => ({ kind, prompt, given, ...extra });
      return [
        q("hold", `for how many cycles, from cycle ${d.start} on, the PC keeps the value ${formatValue(r.core.pc, 32)}`,
          { cycle: d.start }),
        q("count-busy", `in how many of the cycles ${d.start} to ${d.done} div.busy is 1`, { from: d.start, to: d.done }),
        q("retire-cycle", "the cycle in which the division retires", { cycle: d.start }),
        q("count-retire", `how many retire records the cycles ${d.start} to ${d.done} have`, { from: d.start, to: d.done }),
        q("field", "the value written to rd when it retires (retire.val)", { field: "retire.val", cycle: d.done }),
      ];
    },
  },
  {
    id: "checks",
    title: "What each check proves: retire log, waveform, test result",
    tasks: ["T1", "T2"],
    sources: [["sim/main.cpp", "the harness (retire log)"], ["scripts/obs-pack.py", "obs-pack.py (test results)"]],
    tests: ["CoSimSpec"],
    groups: ["core"],
    needs: "a bundle with test results",
    async build(api) {
      if (!api.tests) return null;
      const st = await stretch(api);
      const given = `cycles ${st.from} to ${st.to}: ${st.what}`;
      return [
        { kind: "count-retire", from: st.from, to: st.to, given, prompt: "retire log: how many records it has for these cycles" },
        { kind: "distinct-pc", from: st.from, to: st.to, given, prompt: "waveform: how many different values core.pc takes in them" },
        { kind: "tests-pass", given: "the bundle's test results", prompt: "test result: how many of its checks pass" },
        { kind: "sources-pc", from: st.from, to: st.to, given, choices: [["retire", "the retire log"], ["waveform", "the waveform"],
          ["tests", "the test result"]], prompt: "which of them shows the PC in every one of these cycles" },
      ];
    },
  },
  {
    id: "traps",
    title: "Exceptions and interrupts: from the interrupt line to the handler",
    tasks: ["T3", "T4", "T5"],
    sources: [[T3_SRC + "CsrFile.scala", "CsrFile"], [T3_SRC + "CPU.scala", "CPU (T3)"], [T4_SRC + "CPU.scala", "CPU (T4)"]],
    tests: ["TrapSpec", "InterruptSpec", "PreciseTrapSpec"],
    groups: ["csr"],
    needs: "a trap in the window, and the cycle after it",
    async build(api) {
      const list = traps(await all(api)).filter((t) => t.mepc !== null);
      const t = list.find((x) => x.interrupt && x.lineRise !== null) ?? list[0];
      if (!t) return null;
      const r = await api.cycle(t.cycle - (t.cycle > api.first ? 1 : 0));
      const given = `cycle ${t.cycle}: a trap is taken (csr.trap = 1), ${t.interrupt ? "an interrupt" : "an exception"}; ` +
        `in the cycle before, mtvec is ${formatValue(r.csr.mtvec, 32)}, mie ${formatValue(r.csr.mie, 32)}, ` +
        `mstatus ${formatValue(r.csr.mstatus, 32)}, and the instruction ${t.interrupt ? "the interrupt comes before" : "that traps"} ` +
        `is at pc ${formatValue(t.pc, 32)}`;
      const q = (what, prompt) => ({ kind: "trap", what, cycle: t.cycle, prompt, given, bits: 32 });
      const out = [q("mcause", "mcause after the trap (csr.mcause of the next cycle)"),
        q("mepc", "mepc after the trap (csr.mepc of the next cycle)"),
        q("handler", "the pc of the handler's first instruction (its retire record)")];
      if (t.interrupt && t.lineRise !== null) {
        out.push({ ...q("delay", `how many cycles after its line rose in mip (cycle ${t.lineRise}) the trap is taken`), bits: 5 });
      }
      return out;
    },
  },
  {
    id: "hazards",
    title: "Pipeline hazards: forwarding, stall and flush",
    tasks: ["T4", "T5"],
    sources: [[T4_SRC + "Hazard.scala", "HazardUnit"], [T4_SRC + "CPU.scala", "CPU"], [T4_SRC + "Stage.scala", "Stage"]],
    tests: ["HazardSpec", "StageSpec"],
    groups: ["pipe"],
    needs: "an instruction that the hazard unit holds in ID, or a taken branch or jump",
    async build(api) {
      const list = hazards(await all(api));
      const stall = list.find((x) => x.stalls > 0);
      const flush = list.find((x) => x.flushSlots > 0);
      if (!stall && !flush) return null;
      const occurrence = (x) => list.filter((y) => y.pc === x.pc).indexOf(x);
      const out = [];
      for (const [x, what, prompt] of [[stall, "stalls", "for how many cycles the hazard unit holds it in ID (pipe.loadUseStall)"],
        [flush, "flush", "how many bubbles reach EX after it, before the next instruction (its taken branch or jump)"]]) {
        if (!x) continue;
        const text = api.listing(x.pc) ?? formatValue(x.inst, 32);
        out.push({ kind: "hazard", what, pc: x.pc, occurrence: occurrence(x), cycle: x.first, prompt, bits: 5,
          given: `${api.header.variant}: the instruction ${text} at pc ${formatValue(x.pc, 32)}, which enters the pipeline in cycle ${x.first}` });
      }
      return out;
    },
  },
  {
    id: "uart",
    title: "The UART: from a store to the terminal",
    tasks: ["T1", "T2", "T3", "T4", "T5"],
    sources: [["common/src/main/scala/mycpu/common/Uart.scala", "Uart"]],
    tests: ["UartSpec"],
    groups: ["uart"],
    needs: "a window from cycle 0 in which the UART sends a byte",
    async build(api) {
      if (api.first !== 0) return null;
      const rs = await all(api);
      const f = uartFrames(rs, api.first, api.header.uart_divider)[0];
      if (!f || !storeOfFrame(rs, api.first, f)) return null;
      const given = `the UART's first frame: its start bit begins in cycle ${f.start} (uart.txd falls to 0); ` +
        `a bit lasts ${api.header.uart_divider} cycles`;
      return [
        { kind: "uart", what: "byte", frame: 0, cycle: f.start, bits: 8, given, prompt: "the byte it sends (data bits LSB first)" },
        { kind: "uart", what: "store-pc", frame: 0, cycle: f.start, bits: 32, given, prompt: "the pc of the store to TXDATA that wrote it" },
        { kind: "uart", what: "delay", frame: 0, cycle: f.start, bits: 5, given,
          prompt: "how many cycles after that store's retire record the start bit begins" },
      ];
    },
  },
  {
    id: "vga",
    title: "The VGA: from the framebuffer to the screen",
    tasks: ["T5"],
    sources: [[T5_SRC + "Vga.scala", "Vga"]],
    tests: ["SocSpec"],
    groups: [],
    needs: "a VGA frame with its cycle and the run's stores (make obs ... OBS_VGA=1 OBS_STORES=20000000:20010000)",
    async build(api) {
      const entry = (api.manifest.vga ?? []).find((e) => e.cycle !== undefined);
      const stores = await api.stores();
      if (!entry || !stores) return null;
      // a pixel in the middle of the picture
      const x = 321;
      const y = 243;
      const frame = parsePpm(await api.file(entry.file));
      const i = 3 * (frame.width * y + x);
      const rgb = (frame.rgb[i] << 16) | (frame.rgb[i + 1] << 8) | frame.rgb[i + 2];
      const given = `frame ${entry.frame}, complete in cycle ${entry.cycle}: screen pixel (${x}, ${y}) shows ` +
        `#${hex6(rgb)}; the VGA's base is 0x20000000, BUFFER0 at +0x4000, BUFFER1 at +0x8000`;
      const q = (what, prompt, bits) => ({ kind: "pixel", what, frame: entry.frame, x, y, prompt, given, bits });
      return [q("addr", "the address of the framebuffer word that holds it", 32),
        q("index", "the palette index stored for it (4 bits of that word)", 4),
        q("store-pc", "the pc of the store that last wrote that word", 32)];
    },
  },
];

// ---------------------------------------------------------------------------
// Rendering

function questionInput(q, id) {
  if (q.choices) {
    return h("fieldset", {}, [h("legend", {}, [q.prompt]), ...q.choices.map(([value, label]) =>
      h("label", {}, [h("input", { type: "checkbox", name: id, value }), ` ${label}`]))]);
  }
  return h("label", {}, [`${q.prompt}: `, h("input", { type: "text", id, autocomplete: "off", inputmode: "text" })]);
}

function readPrediction(el, q) {
  if (q.choices) {
    return Array.from(el.querySelectorAll("input:checked"), (i) => i.value).sort().join(",");
  }
  return parseNumber(el.querySelector("input").value);
}

function shown(q, value) {
  if (value === null) return "the window does not hold it";
  if (q.choices) return value ? value.split(",").map((v) => q.choices.find((c) => c[0] === v)[1]).join(" and ") : "none of them";
  if (q.kind === "field" || ((q.kind === "trap" || q.kind === "uart" || q.kind === "pixel") && q.bits === 32)) {
    return formatValue(value, Number(q.bits));
  }
  if (q.kind === "next-pc") return formatValue(value, 32);
  return String(value);
}

/** The cycles that the questions look at: from the first one asked about to the last one an answer names. */
function span(questions) {
  let lo = Infinity;
  let hi = -Infinity;
  for (const q of questions) {
    const first = q.from ?? q.cycle;
    if (first === undefined) continue;
    let last = q.to ?? q.cycle;
    if (q.kind === "next-pc") last += 1;
    if (q.kind === "hold" && q.expected !== null) last = q.cycle + q.expected;
    if (q.kind === "retire-cycle" && q.expected !== null) last = q.expected;
    lo = Math.min(lo, first);
    hi = Math.max(hi, last);
  }
  return lo === Infinity ? null : { from: lo, to: hi };
}

const EVIDENCE_ROWS = 64;

/** The recorded records of the cycles asked about, and the test results when a question names them. */
async function evidence(api, questions, range) {
  const parts = [];
  if (range) {
    const to = Math.min(range.to, range.from + EVIDENCE_ROWS - 1, api.last);
    const rows = (await api.window(range.from, to + 1)).map((r) => h("tr", { "data-cycle": r.cycle }, [
      h("td", {}, [String(r.cycle)]), h("td", { class: "mono" }, [pcOf(r) === null ? "–" : formatValue(pcOf(r), 32)]),
      h("td", { class: "mono" }, [r.div ? `busy ${r.div.busy}` : ""]),
      h("td", { class: "mono" }, [r.retire ? retireLine(r.retire) : "no retire record"])]));
    parts.push(h("div", { class: "table-wrap" }, [h("table", { class: "evidence" }, [h("tr", {},
      ["cycle", "pc (core.pc or pipe.memory.pc)", "div", "retire record"].map((t) => h("th", {}, [t]))), ...rows])]));
  }
  if (api.tests && questions.some((q) => q.kind === "tests-pass")) {
    parts.push(h("ul", { class: "evidence-tests" }, api.tests.results.map((t) =>
      h("li", {}, [`${t.name}: ${t.result}${t.detail ? ` (${t.detail})` : ""}`]))));
  }
  return parts;
}

function viewLink(api, name, cycle, label) {
  return h("button", { type: "button", class: "link", "data-goto": name, onclick: () => { api.cursor.set(cycle); api.navigate(name); } }, [label]);
}

// the newest start of each activity's box: a start that finishes after a later one does nothing
const requests = new WeakMap();

async function startActivity(api, activity, box) {
  const request = {};
  requests.set(box, request);
  const latest = () => requests.get(box) === request && box.isConnected;
  clear(box);
  const missing = activity.groups.filter((g) => !api.groups.includes(g));
  if (missing.length) {
    box.append(h("p", { class: "warn" }, [`This bundle does not have what the activity needs: the ${missing.join(" and ")} ` +
      `group${missing.length > 1 ? "s" : ""} of probe fields.`]));
    return;
  }
  let questions;
  try {
    questions = await activity.build(api);
    for (const q of questions ?? []) {
      if (q.kind === "field") q.bits = api.field(q.field).bits;
      q.expected = await answer(api, q);
    }
  } catch (e) {
    if (!latest()) return;
    box.append(h("p", { class: "error" }, [`The bundle could not be read for this activity: ${e.message}`]));
    return;
  }
  if (!latest()) return;
  if (!questions) {
    box.append(h("p", { class: "warn" }, [`This bundle does not have what the activity needs: ${activity.needs}.`]));
    return;
  }
  const form = h("form", { class: "activity", "data-activity": activity.id });
  let lastGiven = null;
  questions.forEach((q, i) => {
    if (q.given !== lastGiven) {
      form.append(h("p", { class: "given" }, [q.given]));
      lastGiven = q.given;
    }
    const attrs = { class: "question", "data-kind": q.kind, "data-index": i };
    for (const k of ["cycle", "field", "from", "to", "mnemonic", "what", "pc", "occurrence", "frame", "x", "y"]) if (q[k] !== undefined && q[k] !== null) attrs[`data-${k}`] = q[k];
    // the ids are the page's: one per activity and question, since every activity is on the page
    form.append(h("div", attrs, [questionInput(q, `q-${activity.id}-${i}`), h("p", { class: "verdict", hidden: true })]));
  });
  const reveal = h("button", { type: "submit", class: "primary" }, ["reveal the recorded run"]);
  const result = h("div", { class: "result", "aria-live": "polite" });
  form.append(reveal, result);
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    let correct = 0;
    const divs = form.querySelectorAll(".question");
    questions.forEach((q, i) => {
      const prediction = readPrediction(divs[i], q);
      const ok = q.expected !== null && prediction === q.expected;
      correct += ok;
      const verdict = divs[i].querySelector(".verdict");
      verdict.hidden = false;
      verdict.className = `verdict ${ok ? "ok" : "bad"}`;
      verdict.textContent = `recorded: ${shown(q, q.expected)}; you predicted ${prediction === null ? "nothing readable" : shown(q, prediction)}`;
    });
    reveal.disabled = true;
    clear(result).append(h("p", { class: "score" }, [`${correct} of ${questions.length} as recorded.`]));
    const views = api.course?.views ?? [];
    const range = span(questions);
    const at = range?.from ?? api.first;
    result.append(h("h3", {}, ["The recorded trace"]), ...await evidence(api, questions, range),
      h("div", { class: "actions" }, [
        (api.course?.task?.views ?? []).includes("datapath") ? viewLink(api, "datapath", at, "see it on the datapath") : "",
        views.includes("waveform") ? viewLink(api, "waveform", at, "see it in the waveform")
          : viewLink(api, "retire", at, "see the bundle's retire records"),
      ]));
    api.link("activity-done", { task: api.header.task, id: activity.id, correct, total: questions.length });
  });
  box.append(form);
}

export async function mount(container, api) {
  unmount();
  const root = h("div", { class: "activities", id: "activities" });
  const task = api.header.task;
  const specs = api.course?.task?.tests ?? [];
  const list = ACTIVITIES.filter((a) => a.tasks.includes(task));
  root.append(h("p", {}, ["Predict, then reveal what the recorded run did. The expected answers are computed from the ",
    "open bundle, so they are the reference core's behavior for a shipped example and your core's for a bundle you import."]));
  for (const a of list) {
    const box = h("div", { class: "activity-body" });
    root.append(h("section", { class: "panel", id: `activity-${a.id}` }, [
      h("h2", {}, [a.title]),
      h("p", { class: "note" }, ["Chisel: ", ...a.sources.flatMap(([file, name], i) => [i ? ", " : "", repoLink(file, name)]),
        ". Tests: ", ...a.tests.flatMap((name, i) => {
          const spec = specs.find((t) => t.name === name);
          return [i ? ", " : "", spec ? repoLink(spec.file, name) : name];
        }), "."]),
      h("button", { type: "button", class: "start", onclick: () => startActivity(api, a, box) }, ["start"]),
      box,
    ]));
  }
  if (!list.length) root.append(h("p", {}, [`No activity serves ${task} yet.`]));
  container.append(root);
  current = { root };
}

export function unmount() {
  current?.root.remove();
  current = null;
}
