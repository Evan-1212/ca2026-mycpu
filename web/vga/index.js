// SPDX-License-Identifier: MIT
// The VGA of T5: a frame as the harness rebuilt it from the video
// signals (a +vga-dump of the bundle), the framebuffer that the run's stores
// had written by the cycle the frame was complete (stores.json, through the
// palette those stores set), the video fields at the cursor, and for a pixel
// chosen on the screen its framebuffer word and the store that last wrote it.
// A second bundle (the VGA_SHIFT=1 model of make check-vga, for one) is shown next to
// the first with the pixels in which the two frames differ. A QR frame shows
// what the bundle's test results say it decodes to (the QR showcase, make check-qr).

import { BundleError } from "../lib/bundle.js";
import { compareControls } from "../lib/compare.js";
import { clear, h, repoLink } from "../lib/dom.js";
import { formatValue, hex6, hex8 } from "../lib/format.js";
import { FB_HEIGHT, FB_WIDTH, framebuffer, HEIGHT, palette, parsePpm, pixelSource, rgbAt, SCALE, WIDTH, wordsAt } from "../lib/vgafb.js";

const T5_SRC = "5-soc/src/main/scala/mycpu/soc/";
const VGA_FIELDS = ["vga.tick", "vga.hsync", "vga.vsync", "vga.active", "vga.x", "vga.y", "vga.rgb"];

let current = null;

/** Sizes canvas to width x height and draws it: put(data, i) writes pixel i's four bytes at data[4 * i]. */
function paint(canvas, width, height, put) {
  canvas.width = width;
  canvas.height = height;
  const img = new ImageData(width, height);
  for (let i = 0; i < width * height; i++) put(img.data, i);
  canvas.getContext("2d").putImageData(img, 0, 0);
}

function draw(canvas, frame) {
  const rgb = frame.rgb;
  paint(canvas, frame.width, frame.height, (d, i) => {
    d[4 * i] = rgb[3 * i];
    d[4 * i + 1] = rgb[3 * i + 1];
    d[4 * i + 2] = rgb[3 * i + 2];
    d[4 * i + 3] = 255;
  });
}

function drawFramebuffer(canvas, fb, colors) {
  paint(canvas, FB_WIDTH, FB_HEIGHT, (d, i) => {
    const c = colors[fb[i]];
    d[4 * i] = (c >> 16) & 255;
    d[4 * i + 1] = (c >> 8) & 255;
    d[4 * i + 2] = c & 255;
    d[4 * i + 3] = 255;
  });
}

/** The pixels in which two frames differ, as a canvas (opaque where they differ) and a count. */
function diffCanvas(a, b) {
  const canvas = h("canvas", { id: "vga-diff", class: "vga-screen" });
  let count = 0;
  paint(canvas, WIDTH, HEIGHT, (d, i) => {
    if (a.rgb[3 * i] !== b.rgb[3 * i] || a.rgb[3 * i + 1] !== b.rgb[3 * i + 1] || a.rgb[3 * i + 2] !== b.rgb[3 * i + 2]) {
      d[4 * i] = 255;
      d[4 * i + 2] = 255;
      d[4 * i + 3] = 255;
      count++;
    }
  });
  return { canvas, count };
}

function signals(api, r) {
  if (!r.vga) return h("p", { class: "note" }, ["The bundle has no vga group."]);
  return h("dl", { class: "kv" }, VGA_FIELDS.flatMap((f) => {
    const v = api.value(r, f);
    return [h("dt", {}, [f]), h("dd", { "data-field": f }, [h("span", { class: "mono" }, [formatValue(v, api.field(f).bits)]),
      f === "vga.rgb" ? h("span", { class: "swatch", style: `background:#${hex6(v)}` }) : ""])];
  }).concat([h("dt", {}, ["note"]), h("dd", {}, ["the fields describe a pixel only in a cycle with vga.tick = 1"])]));
}

function decodeNote(api, entry) {
  const results = api.tests?.results ?? [];
  const decoded = results.find((t) => t.name === `qr-decode:${entry.frame}`);
  const golden = results.find((t) => t.name === `golden:${entry.frame}`);
  return h("p", { id: "vga-decode" }, [
    decoded ? [`QR decoder: ${decoded.result}`, decoded.detail ? `, reads "${decoded.detail}"` : ""] : "",
    golden ? [decoded ? "; " : "", `golden frame: ${golden.result}${golden.detail ? ` (${golden.detail})` : ""}`] : "",
  ]);
}

function pixelPanel(view, x, y) {
  const { entry, frame, words, api } = view;
  const p = pixelSource(words, entry.frame, x, y, view.stores?.stores ?? null, entry.cycle ?? null);
  const screen = rgbAt(frame, x, y);
  const inWindow = p.store && p.store.cycle >= api.first && p.store.cycle <= api.last;
  // without stores.json nothing is known of the word or the palette: no "never written", no "agrees"
  const known = Boolean(view.stores);
  const fromStores = known ? [
    h("dt", {}, ["last store to it"]), h("dd", {}, [p.store
      ? [h("span", { class: "mono" }, [`cycle ${p.store.cycle}, pc ${hex8(p.store.pc)}, data ${hex8(p.store.data)}`]),
        inWindow ? [" ", h("button", { type: "button", class: "link", onclick: () => api.cursor.set(p.store.cycle) }, ["go there"])]
          : " (before the bundle's window, from stores.json)"]
      : "none: the word was never written"]),
    h("dt", {}, ["palette index"]), h("dd", {}, [`${p.index}: ${p.paletteStore ? `#${hex6(p.color)}, written in cycle ${p.paletteStore.cycle}` : "never written (black)"}`]),
    h("dt", {}, ["agrees"]), h("dd", { id: "vga-agrees" }, [p.color === screen ? "yes: the palette color of the stored index is the screen's"
      : "no: the screen shows another color"]),
  ] : [h("dt", {}, ["stores"]), h("dd", { id: "vga-agrees" }, ["unknown: the bundle has no stores.json, so its word, palette index and the ",
    "store behind it cannot be shown"])];
  return h("div", { id: "vga-pixel", "data-x": x, "data-y": y, "data-addr": p.addr, "data-store-cycle": p.store?.cycle ?? "",
    "data-store-pc": p.store?.pc ?? "", "data-store-data": p.store?.data ?? "", "data-index": known ? p.index : "",
    "data-color": known ? p.color : "", "data-screen": screen, "data-uncertain": p.uncertain ? 1 : 0 }, [
    h("h3", {}, [`Pixel (${x}, ${y}) of frame ${entry.frame}`]),
    h("dl", { class: "kv" }, [
      h("dt", {}, ["on the screen"]), h("dd", {}, [h("span", { class: "mono" }, [`#${hex6(screen)}`]),
        h("span", { class: "swatch", style: `background:#${hex6(screen)}` })]),
      h("dt", {}, ["framebuffer pixel"]), h("dd", {}, [`(${p.fx}, ${p.fy}) of buffer ${p.buffer} (frame ${entry.frame} shows buffer ${p.buffer})`]),
      h("dt", {}, ["its word"]), h("dd", { class: "mono" }, [`${hex8(p.addr)}, bits ${p.bits[0]}:${p.bits[1]}`]),
      ...fromStores,
      ...(p.uncertain ? [h("dt", {}, ["uncertain"]), h("dd", { class: "warn" }, [`the word or its palette entry was written while the ` +
        `frame was scanned out (cycle${p.during.length > 1 ? "s" : ""} ${p.during.map((d) => d[0]).join(", ")}): the pixel may show ` +
        "the value before that store"])] : []),
    ]),
  ]);
}

async function showFrame(view, entry) {
  // the newest choice wins: a frame read that finishes after a later choice does nothing
  const request = {};
  view.request = request;
  let frame;
  try {
    frame = parsePpm(await view.api.file(entry.file));
  } catch (e) {
    if (current === view && view.request === request) {
      clear(view.screens).append(h("p", { class: "error" }, [`Frame ${entry.frame} could not be read: ${e.message}`]));
    }
    return;
  }
  if (current !== view || view.request !== request) return;
  view.entry = entry;
  view.frame = frame;
  const at = entry.cycle ?? view.api.run.cycles - 1;
  view.words = view.stores ? wordsAt(view.stores.stores, at) : new Map();
  const screen = h("canvas", { id: "vga-screen", class: "vga-screen", tabindex: 0, "aria-label": `frame ${entry.frame}`,
    "aria-describedby": "vga-screen-help" });
  draw(screen, view.frame);
  let chosen = null;
  const choose = (x, y) => {
    chosen = { x: Math.max(0, Math.min(WIDTH - 1, x)), y: Math.max(0, Math.min(HEIGHT - 1, y)) };
    clear(view.pixel).append(pixelPanel(view, chosen.x, chosen.y));
  };
  screen.addEventListener("click", (e) => {
    const box = screen.getBoundingClientRect();
    choose(Math.floor(((e.clientX - box.left) / box.width) * WIDTH), Math.floor(((e.clientY - box.top) / box.height) * HEIGHT));
  });
  // the keyboard's way to the same panel: the arrow keys move the chosen pixel (from (0, 0)),
  // by one framebuffer pixel with Shift; preventDefault keeps the site's arrow keys off the cursor
  const MOVES = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
  screen.addEventListener("keydown", (e) => {
    const move = MOVES[e.key];
    if (!move || e.altKey || e.ctrlKey || e.metaKey) return;
    e.preventDefault();
    const step = e.shiftKey ? SCALE : 1;
    if (!chosen) choose(0, 0);
    else choose(chosen.x + move[0] * step, chosen.y + move[1] * step);
  });
  const parts = [h("figure", {}, [screen, h("figcaption", {}, [`frame ${entry.frame}, rebuilt by the harness from the video ` +
    `signals${entry.cycle !== undefined ? `, complete in cycle ${entry.cycle}` : ""}`])])];
  if (view.stores) {
    const fb = h("canvas", { id: "vga-framebuffer", class: "vga-fb" });
    drawFramebuffer(fb, framebuffer(view.words, entry.frame % 2), palette(view.words));
    parts.push(h("figure", {}, [fb, h("figcaption", {}, [`buffer ${entry.frame % 2} as the stores of stores.json left it by cycle ${at}, ` +
      "through the palette they set: 160 x 120, each pixel 4 x 4 on the screen"])]));
  } else {
    parts.push(h("p", { class: "note" }, ["The bundle has no stores.json (obs-pack.py --stores 20000000:20010000), ",
      "so the framebuffer and the stores behind a pixel cannot be shown."]));
  }
  clear(view.screens).append(h("div", { class: "vga-row" }, parts), decodeNote(view.api, entry),
    h("p", { class: "note", id: "vga-screen-help" }, ["A pixel is chosen with a click on the screen, or from the keyboard: ",
      "with the screen focused, the arrow keys move it (with Shift, by 4 pixels: one framebuffer pixel)."]));
  clear(view.pixel).append(h("p", { class: "note" }, ["Choose a pixel on the screen."]));
  renderCompare(view);
}

function renderCompare(view) {
  clear(view.compareOut);
  if (!view.other || !view.frame) return;
  const otherEntry = view.other.frames.get(view.entry.frame);
  if (!otherEntry) {
    view.compareOut.append(h("p", { class: "warn" }, [`The other bundle has no frame ${view.entry.frame}.`]));
    return;
  }
  const canvas = h("canvas", { id: "vga-other", class: "vga-screen" });
  draw(canvas, otherEntry);
  const diff = diffCanvas(view.frame, otherEntry);
  view.compareOut.append(h("div", { class: "vga-row" }, [
    h("figure", {}, [canvas, h("figcaption", {}, [`frame ${view.entry.frame} of ${view.other.label} (${view.other.variant})`])]),
    h("figure", {}, [diff.canvas, h("figcaption", {}, ["the pixels in which the two frames differ"])])]),
  h("p", { id: "vga-diff-count", "data-count": diff.count }, [diff.count ? `${diff.count} pixels differ` : "the two frames are equal"]));
}

async function render() {
  const view = current;
  if (!view?.records) return;
  const n = view.api.cursor.get();
  const r = await view.api.cycle(n);
  // a newer cursor position or an unmount while the chunk was read: that render draws instead
  if (current !== view || view.api.cursor.get() !== n) return;
  clear(view.now).append(h("h2", {}, [`Video fields in cycle ${n}`]), signals(view.api, r));
}

export async function mount(container, api) {
  unmount();
  const root = h("div", { id: "vga" }, [h("p", {}, ["The VGA of the recorded run. Chisel: ", repoLink(T5_SRC + "Vga.scala", "Vga"),
    " (its register map and pixel packing); checks: make check-vga and check-qr (sim/vga.mk). The screen is what the harness ",
    "rebuilt from hsync, vsync and rgb; the framebuffer is what the stores wrote."])]);
  container.append(root);
  const token = { api, root, records: null, other: null };
  current = token;
  const entries = Array.isArray(api.manifest.vga) ? api.manifest.vga : [];
  if (!entries.length && !api.groups.includes("vga")) {
    root.append(h("p", { class: "warn" }, ["This bundle has no VGA frame and no vga group."]));
    return;
  }
  try {
    token.stores = await api.stores();
  } catch (e) {
    root.append(h("p", { class: "error" }, [`The bundle's stores could not be read: ${e.message}`]));
    token.stores = null;
  }
  if (current !== token) return;
  const pick = h("select", { id: "vga-frame", "aria-label": "frame" }, entries.map((e) => h("option", { value: e.frame }, [`frame ${e.frame}`])));
  token.screens = h("div", { id: "vga-screens" });
  token.pixel = h("div", { id: "vga-pixel-box", "aria-live": "polite" });
  token.now = h("section", { class: "panel", id: "vga-now" });
  token.compareOut = h("div", { id: "vga-compare-out" });
  const { actions, message } = compareControls({
    prefix: "vga", addLabel: "compare with the served bundle", failed: "not compared", header: api.header,
    isCurrent: () => current === token,
    async use(other, source) {
      if (!other.manifest.vga?.length) throw new BundleError("it has no VGA frame");
      const theirs = other.manifest.vga;
      const read = await Promise.all(theirs.map(async (e) => parsePpm(await other.file(e.file))));
      return () => {
        token.other = { frames: new Map(theirs.map((e, i) => [e.frame, read[i]])), label: source.label, variant: other.header.variant };
        renderCompare(token);
        return `comparing with ${source.label} (${other.header.variant}, ${other.header.program})`;
      };
    },
  });
  root.append(
    h("section", { class: "panel", id: "vga-frames" }, [h("h2", {}, ["Screen and framebuffer"]),
      entries.length ? h("label", {}, ["show ", pick]) : h("p", { class: "note" }, ["The bundle has no +vga-dump frame."]),
      token.screens, token.pixel]),
    token.now,
    h("section", { class: "panel", id: "vga-compare" }, [h("h2", {}, ["Next to another design"]),
      h("p", {}, ["A bundle of the same program on another model, the VGA_SHIFT=1 model of make check-vga for one ",
        "(vgaShift=1: the picture one pixel late):"]),
      actions, message, token.compareOut]));
  token.records = true;
  pick.addEventListener("change", () => showFrame(token, entries.find((e) => e.frame === Number(pick.value))));
  if (entries.length) await showFrame(token, entries[0]);
  if (current !== token) return;
  api.cursor.addEventListener("change", render);
  await render();
}

export function unmount() {
  if (!current) return;
  current.api.cursor.removeEventListener("change", render);
  current.root.remove();
  current = null;
}
