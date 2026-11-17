// SPDX-License-Identifier: MIT
// The 2D datapath: the description t<n>.json of the bundle's task
// drawn as SVG, with the value of every bound field at the shared cursor.
// The description holds structure and bindings only; every number on the
// drawing is the bundle's value of a schema field in the cycle at the cursor.

import { bootcampLink, clear, h, pressable, repoLink, s } from "../lib/dom.js";
import { formatValue } from "../lib/format.js";

const ROW = 18;
const TITLE = 22;

let current = null; // {api, off, svg, ...}
let generation = 0; // a mount that an unmount overtook while it waited does nothing
const descriptions = new Map(); // task -> promise of t<n>.json, or of null when there is none

function descriptionOf(task) {
  if (!descriptions.has(task)) {
    const url = new URL(`./${task.toLowerCase()}.json`, import.meta.url);
    // a task without a description answers 404, kept as null; any other failure is not kept,
    // so that the next mount asks again
    const promise = fetch(url).then((r) => {
      if (r.status === 404) return null;
      if (!r.ok) throw new Error(`${url.pathname} could not be read (HTTP ${r.status})`);
      return r.json();
    });
    promise.catch(() => {
      if (descriptions.get(task) === promise) descriptions.delete(task);
    });
    descriptions.set(task, promise);
  }
  return descriptions.get(task);
}

function shortName(field) {
  return field.split(".").pop();
}

function portPoint(c, side, at) {
  switch (side) {
    case "left": return [c.x, c.y + at * c.h];
    case "right": return [c.x + c.w, c.y + at * c.h];
    case "top": return [c.x + at * c.w, c.y];
    case "bottom": return [c.x + at * c.w, c.y + c.h];
    default: throw new Error(`unknown side ${side}`);
  }
}

function endpoint(byId, text) {
  const [id, side, ...rest] = text.split(".");
  const c = byId.get(id);
  if (!c) throw new Error(`a wire names component ${id}, which the description does not have`);
  return { comp: c, side, point: portPoint(c, side, Number(rest.join("."))) };
}

/** The wire's points: its ends and via points, with an elbow between two points that share no coordinate. */
function route(from, to, via) {
  const points = [from.point, ...(via ?? []), to.point];
  const out = [points[0]];
  for (let i = 1; i < points.length; i++) {
    const [px, py] = out[out.length - 1];
    const [qx, qy] = points[i];
    if (px !== qx && py !== qy) {
      // leave a top or bottom port vertically; after that, turn: a vertical segment is followed by a horizontal one
      const verticalFirst = out.length === 1 ? from.side === "top" || from.side === "bottom"
        : out[out.length - 2][0] !== px;
      out.push(verticalFirst ? [px, qy] : [qx, py]);
    }
    out.push([qx, qy]);
  }
  return out;
}

function labelPoint(points) {
  let best = [points[0], points[1]];
  let length = -1;
  for (let i = 1; i < points.length; i++) {
    const d = Math.abs(points[i][0] - points[i - 1][0]) + Math.abs(points[i][1] - points[i - 1][1]);
    if (d > length) {
      length = d;
      best = [points[i - 1], points[i]];
    }
  }
  const [[ax, ay], [bx, by]] = best;
  // two lines of text: above a horizontal segment, beside a vertical one
  return ay === by ? { x: (ax + bx) / 2, y: ay - 17, anchor: "middle" } : { x: ax + 5, y: (ay + by) / 2 - 6, anchor: "start" };
}

function valueEl(api, field, tag = "tspan") {
  const f = api.field(field);
  const attrs = { class: "v", "data-field": field, "data-bits": f?.bits ?? "" };
  if (!f?.present) attrs["data-concept"] = "1";
  return (tag === "tspan" ? s : h)(tag, attrs, [f?.present ? "" : "concept"]);
}

/** True when the bundle observes the field: it is a schema field of a group the bundle has. */
function observed(api, field) {
  return Boolean(field && api.field(field)?.present);
}

function isConcept(api, element, fields) {
  if (element.concept) return true;
  if (!fields.length) return false;
  return fields.every((name) => !observed(api, name));
}

function drawComponent(api, c, select) {
  const fields = c.rows.flat();
  const concept = isConcept(api, c, fields);
  const g = s("g", {
    class: `dp-comp${concept ? " concept" : ""}`, "data-id": c.id, role: "button",
    ...pressable(() => select({ kind: "component", item: c })),
    "aria-label": `${c.label}${concept ? " (concept)" : ""}`,
  });
  g.append(s("rect", { class: "box", x: c.x, y: c.y, width: c.w, height: c.h, rx: 6 }),
    s("text", { class: "title", x: c.x + 8, y: c.y + 16 }, [c.label]));
  c.rows.forEach((row, i) => {
    const text = s("text", { class: "val", x: c.x + 8, y: c.y + TITLE + 12 + i * ROW });
    row.forEach((field, j) => {
      text.append(s("tspan", { class: "k" }, [`${j ? "  " : ""}${shortName(field)} `]), valueEl(api, field));
    });
    g.append(text);
  });
  if (concept) g.append(s("text", { class: "tag", x: c.x + c.w - 8, y: c.y + c.h - 6, "text-anchor": "end" }, ["concept"]));
  if (c.alert) {
    g.append(s("text", { class: "tag alert", x: c.x + c.w - 8, y: c.y + 16, "text-anchor": "end", "data-alert": c.alert.field,
      visibility: "hidden" }, [s("tspan", { class: "k" }, [`${c.alert.field} = `]), valueEl(api, c.alert.field)]));
  }
  return g;
}

function drawWire(api, w, byId, select) {
  const from = endpoint(byId, w.from);
  const to = endpoint(byId, w.to);
  const points = route(from, to, w.via);
  const d = points.map(([x, y], i) => `${i ? "L" : "M"}${x} ${y}`).join(" ");
  const concept = !observed(api, w.field);
  const g = s("g", {
    class: `dp-wire${concept ? " concept" : ""}`, "data-id": w.id, role: "button",
    ...pressable(() => select({ kind: "wire", item: w, from, to })),
    "aria-label": `wire ${w.name}${concept ? " (concept)" : ""}`,
  });
  const last = points[points.length - 1];
  const prev = points[points.length - 2];
  const angle = Math.atan2(last[1] - prev[1], last[0] - prev[0]);
  const arrow = [[-8, -4], [-8, 4]].map(([ax, ay]) => [
    last[0] + ax * Math.cos(angle) - ay * Math.sin(angle), last[1] + ax * Math.sin(angle) + ay * Math.cos(angle)]);
  g.append(s("path", { class: "hit", d }), s("path", { class: "line", d }),
    s("path", { class: "line", d: `M${arrow[0].join(" ")} L${last.join(" ")} L${arrow[1].join(" ")}` }));
  const at = w.label ? { x: w.label[0], y: w.label[1], anchor: w.label[2] ?? "middle" } : labelPoint(points);
  const text = s("text", { x: at.x, y: at.y, "text-anchor": at.anchor });
  text.append(s("tspan", { class: "name" }, [w.name]));
  const second = w.field && !concept ? valueEl(api, w.field) : s("tspan", { class: "tag" }, ["concept"]);
  second.setAttribute("x", at.x);
  second.setAttribute("dy", "1.1em");
  text.append(second);
  g.append(text);
  return g;
}

function signalRow(api, field) {
  const f = api.field(field);
  return [h("dt", {}, [field]), h("dd", {}, [valueEl(api, field, "span"), h("span", { "data-static": "" }, [
    f ? ` (${f.bits} bit${f.bits > 1 ? "s" : ""}, ${f.sample}${f.present ? "" : ", not in this bundle"})` : " (not a schema field)"])])];
}


function renderInfo(api, panel, description, selection) {
  clear(panel);
  if (!selection) {
    panel.append(h("h2", {}, ["Select a box or a wire"]),
      h("p", {}, ["A box shows its Chisel module, its signals, the bootcamp notebook and its tests; a wire shows its ",
        "value, where it comes from and where it goes."]));
    return;
  }
  const { kind, item } = selection;
  if (kind === "wire") {
    const concept = !observed(api, item.field);
    panel.append(h("h2", {}, [`Wire: ${item.name}`]), h("dl", { class: "kv", id: "dp-info-wire" }, [
      h("dt", {}, ["from"]), h("dd", { "data-role": "source" }, [`${selection.from.comp.label} (${selection.from.side})`]),
      h("dt", {}, ["to"]), h("dd", { "data-role": "destination" }, [`${selection.to.comp.label} (${selection.to.side})`]),
      h("dt", {}, ["bits"]), h("dd", {}, [h("span", { "data-static": "" }, [String(item.bits)])]),
      ...(item.field ? signalRow(api, item.field) : [h("dt", {}, ["field"]), h("dd", {}, ["none"])]),
    ]));
    if (item.field && api.field(item.field)) panel.append(h("p", { class: "note" }, [api.field(item.field).description]));
    if (concept) panel.append(h("p", { class: "note" }, ["concept: no probe field of this bundle observes this wire, so it shows no value"]));
    return;
  }
  const c = item;
  const task = api.course?.task;
  const fields = [...new Set([...c.rows.flat(), ...description.wires
    .filter((w) => w.field && (w.from.split(".")[0] === c.id || w.to.split(".")[0] === c.id)).map((w) => w.field)])];
  const tests = (c.tests ?? []).map((name) => {
    const spec = task?.tests.find((t) => t.name === name);
    return h("li", {}, [h("code", {}, [name]), spec ? [" ", repoLink(spec.file), spec.checks ? ` – ${spec.checks}` : ""] : ""]);
  });
  const notebook = c.notebook && api.course ? api.course.notebookLink(api.course.notebook(c.notebook)) : null;
  panel.append(h("h2", {}, [c.label]), h("dl", { class: "kv", id: "dp-info-component" }, [
    h("dt", {}, ["Chisel"]), h("dd", {}, [c.chisel ? [h("code", {}, [c.chisel.module]), " ", repoLink(c.chisel.file)]
      : c.bootcamp ? bootcampLink(api.course?.module5?.repository, c.bootcamp.file,
        `${c.bootcamp.module} (${c.bootcamp.file} in your bootcamp clone)`) : "none"]),
    h("dt", {}, ["notebook"]), h("dd", {}, [notebook ?? "none"]),
  ]));
  if (c.note) panel.append(h("p", { class: "note" }, [c.note]));
  if (isConcept(api, c, c.rows.flat())) panel.append(h("p", { class: "note" }, ["concept: this bundle observes none of its signals"]));
  panel.append(h("h3", {}, ["Signals"]), fields.length ? h("dl", { class: "kv" }, fields.flatMap((f) => signalRow(api, f)))
    : h("p", {}, ["none observed"]), h("h3", {}, ["Tests"]), tests.length ? h("ul", {}, tests) : h("p", {}, ["none named"]));
}

async function update() {
  const view = current;
  if (!view) return;
  const n = view.api.cursor.get();
  let record;
  try {
    record = await view.api.cycle(n);
  } catch (e) {
    // a chunk is checked when one of its cycles is first shown: a refused one is reported here
    if (view === current && n === view.api.cursor.get()) view.status.textContent = `cycle ${n} could not be read: ${e.message}`;
    return;
  }
  if (view !== current || n !== view.api.cursor.get()) return;
  for (const el of view.root.querySelectorAll(".v[data-field]")) {
    if (el.dataset.concept) continue;
    el.textContent = formatValue(view.api.value(record, el.dataset.field), Number(el.dataset.bits));
  }
  for (const c of view.description.components) {
    if (!c.alert) continue;
    const on = view.api.value(record, c.alert.field) === c.alert.value;
    const g = view.svg.querySelector(`.dp-comp[data-id="${c.id}"]`);
    g.classList.toggle("stalled", on);
    g.querySelector("[data-alert]").setAttribute("visibility", on ? "visible" : "hidden");
  }
  const text = record.core ? view.api.listing(record.core.pc) : null;
  view.status.textContent = `cycle ${n}` + (record.core ? `, pc ${formatValue(record.core.pc, 32)}` : "") +
    (text ? ` (${text})` : "") + (record.retire ? ", an instruction retires" : ", no instruction retires");
  view.root.dataset.cycle = String(n);
}

function zoomable(svg, size) {
  const vb = { x: 0, y: 0, w: size[0], h: size[1] };
  const apply = () => svg.setAttribute("viewBox", `${vb.x} ${vb.y} ${vb.w} ${vb.h}`);
  const zoom = (factor, cx = vb.x + vb.w / 2, cy = vb.y + vb.h / 2) => {
    const w = Math.min(size[0] * 2, Math.max(size[0] / 8, vb.w * factor));
    const k = w / vb.w;
    vb.x = cx - (cx - vb.x) * k;
    vb.y = cy - (cy - vb.y) * k;
    vb.w = w;
    vb.h *= k;
    apply();
  };
  const toSvg = (e) => {
    const r = svg.getBoundingClientRect();
    return [vb.x + ((e.clientX - r.left) / r.width) * vb.w, vb.y + ((e.clientY - r.top) / r.height) * vb.h];
  };
  svg.addEventListener("wheel", (e) => {
    e.preventDefault();
    const [cx, cy] = toSvg(e);
    zoom(e.deltaY > 0 ? 1.15 : 1 / 1.15, cx, cy);
  }, { passive: false });
  let drag = null;
  svg.addEventListener("pointerdown", (e) => {
    if (e.target.closest(".dp-comp, .dp-wire")) return;
    drag = { x: e.clientX, y: e.clientY, vx: vb.x, vy: vb.y };
    svg.setPointerCapture(e.pointerId);
  });
  svg.addEventListener("pointermove", (e) => {
    if (!drag) return;
    const r = svg.getBoundingClientRect();
    vb.x = drag.vx - ((e.clientX - drag.x) / r.width) * vb.w;
    vb.y = drag.vy - ((e.clientY - drag.y) / r.height) * vb.h;
    apply();
  });
  const end = () => { drag = null; };
  svg.addEventListener("pointerup", end);
  svg.addEventListener("pointercancel", end);
  apply();
  return {
    in: () => zoom(1 / 1.25),
    out: () => zoom(1.25),
    reset: () => { Object.assign(vb, { x: 0, y: 0, w: size[0], h: size[1] }); apply(); },
  };
}

export async function mount(container, api) {
  unmount();
  const mine = generation;
  const task = api.header.task;
  const description = await descriptionOf(task);
  if (mine !== generation) return;
  if (!description) {
    container.append(h("p", { class: "warn" }, [`There is no datapath description for ${task}.`]));
    return;
  }
  const byId = new Map(description.components.map((c) => [c.id, c]));
  const root = h("div", { class: "datapath", id: "datapath", "data-task": task });
  // a group, not an image: its components and wires are buttons, which role img would hide
  const svg = s("svg", { role: "group", "aria-label": description.title, preserveAspectRatio: "xMidYMid meet" });
  const frame = h("div", { class: "datapath-frame" }, [svg]);
  const panel = h("aside", { class: "panel", id: "dp-info", "aria-live": "polite" });
  const status = h("p", { class: "mono", id: "dp-status" });
  const select = (selection) => {
    for (const el of svg.querySelectorAll(".selected")) el.classList.remove("selected");
    svg.querySelector(`[data-id="${selection.item.id}"]`)?.classList.add("selected");
    renderInfo(api, panel, description, selection);
    update(); // the panel's values are those of the cursor's cycle
  };
  const wires = s("g", { class: "wires" }, description.wires.map((w) => drawWire(api, w, byId, select)));
  const comps = s("g", { class: "components" }, description.components.map((c) => drawComponent(api, c, select)));
  svg.append(comps, wires);
  const zoom = zoomable(svg, description.size);
  root.append(
    h("p", {}, [description.about]),
    h("div", { class: "dp-tools" }, [
      h("button", { type: "button", id: "dp-zoom-in", onclick: zoom.in }, ["zoom in"]),
      h("button", { type: "button", id: "dp-zoom-out", onclick: zoom.out }, ["zoom out"]),
      h("button", { type: "button", id: "dp-zoom-reset", onclick: zoom.reset }, ["fit"]),
      h("span", { class: "legend" }, ["dashed: concept (no probe field); values: 1-bit as 0/1, up to 5 bits in decimal, ",
        "wider in hexadecimal; state fields show a register after the edge that begins the cycle, comb fields ",
        "the value computed in it"]),
    ]),
    status, frame, panel,
  );
  container.append(root);
  renderInfo(api, panel, description, null);
  api.cursor.addEventListener("change", update);
  current = { api, root, svg, status, description };
  await update();
}

export function unmount() {
  generation++;
  if (!current) return;
  current.api.cursor.removeEventListener("change", update);
  current.root.remove();
  current = null;
}
