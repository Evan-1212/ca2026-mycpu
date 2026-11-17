// SPDX-License-Identifier: MIT
// The bundle's retire records, printed as the retire log prints them
// (version 2, sim/main.cpp): a record line per retired instruction and an
// `idle <n>` line for n cycles in a row without one. The rows around the
// cursor are shown; selecting a row moves the cursor to its cycle.

import { clear, h } from "../lib/dom.js";
import { retireLine } from "../lib/format.js";

const SPAN = 24; // cycles shown before and after the cursor

let current = null;

async function render() {
  const view = current;
  if (!view) return;
  const { api, body } = view;
  const n = api.cursor.get();
  const from = Math.max(api.first, n - SPAN);
  const to = Math.min(api.last, n + SPAN);
  let records;
  try {
    records = await api.window(from, to + 1);
  } catch (e) {
    if (view === current && n === api.cursor.get()) view.caption.textContent = `cycles ${from} to ${to} could not be read: ${e.message}`;
    return;
  }
  if (view !== current || n !== api.cursor.get()) return;
  clear(body);
  let idle = null;
  const flush = () => {
    if (!idle) return;
    body.append(h("tr", { "data-cycle": idle.first, class: idle.cursor ? "cursor" : "" }, [
      h("td", {}, [idle.first === idle.last ? String(idle.first) : `${idle.first}–${idle.last}`]),
      h("td", { class: "mono" }, [`idle ${idle.last - idle.first + 1}`])]));
    idle = null;
  };
  for (const r of records) {
    if (!r.retire) {
      if (!idle) idle = { first: r.cycle, last: r.cycle, cursor: false };
      idle.last = r.cycle;
      idle.cursor ||= r.cycle === n;
      continue;
    }
    flush();
    body.append(h("tr", { "data-cycle": r.cycle, class: r.cycle === n ? "cursor" : "", tabindex: 0 }, [
      h("td", {}, [String(r.cycle)]), h("td", { class: "mono", "data-line": "" }, [retireLine(r.retire)])]));
  }
  flush();
  view.caption.textContent = `cycles ${from} to ${to} of the window ${api.first} to ${api.last}; ` +
    `the run ended with ${api.run.how} after ${api.run.cycles} cycles and ${api.run.instret} retired instructions`;
}

export async function mount(container, api) {
  unmount();
  const body = h("tbody");
  // one handler for every row: a click, or Enter on a focused row, moves the cursor to its cycle
  const pick = (e) => {
    const row = e.target.closest("tr[data-cycle]");
    if (row && (e.type === "click" || e.key === "Enter")) api.cursor.set(Number(row.dataset.cycle));
  };
  body.addEventListener("click", pick);
  body.addEventListener("keydown", pick);
  const caption = h("p", { class: "note", id: "retire-caption" });
  const root = h("div", { id: "retire" }, [
    h("p", {}, ["The retire records of the bundle, in the form of the retire log (+retire-log) of the same run: ",
      "obs-pack.py packed them only after comparing them with that log line by line. The row of the cursor is marked."]),
    caption,
    h("div", { class: "table-wrap" }, [h("table", { class: "retire-log" }, [
      h("thead", {}, [h("tr", {}, [h("th", {}, ["cycle"]), h("th", {}, ["line"])])]), body])]),
  ]);
  container.append(root);
  api.cursor.addEventListener("change", render);
  current = { api, root, body, caption };
  await render();
}

export function unmount() {
  if (!current) return;
  current.api.cursor.removeEventListener("change", render);
  current.root.remove();
  current = null;
}
