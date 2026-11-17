// SPDX-License-Identifier: MIT
// The site: the course map (map.json, made by scripts/web-map.py), the bundle
// that is open, the shared cycle cursor, and the router that mounts a view
// (`<view>/index.js`, web/README.md "The view contract").

import { BundleError, filesSource, loadSchema, openBundle, urlSource } from "./lib/bundle.js";
import { bootcampLink as repoBootcampLink, clear, h, repoLink } from "./lib/dom.js";
import { formatValue, hex8, testCounts } from "./lib/format.js";
import { Progress } from "./lib/progress.js";
import { RouteTracker } from "./lib/router.js";

const state = {
  map: null,
  progress: null,
  api: null,
  view: null, // {name, module}
  listings: new Map(), // listing.json of an example, by URL
  routes: new RouteTracker(), // the newest route (an older one that finishes later does nothing) and navigate's wait
  loading: 0, // the newest bundle open, likewise
  linkOff: null,
};

const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------------------
// Bundles

function bootcampLink(notebook) {
  const label = `notebook ${notebook.id}${notebook.title ? ": " + notebook.title : ""}`;
  return repoBootcampLink(notebook.file ? state.map.module5.repository : null, notebook.file, label);
}

function notebookById(id) {
  return state.map.module5.notebooks.find((n) => n.id === id) ?? { id, title: "", file: null };
}

function fetchListing(url) {
  if (!state.listings.has(url)) {
    // a listing is optional: without it the instruction words are shown alone
    state.listings.set(url, fetch(url).then((r) => (r.ok ? r.json() : null)).catch(() => null));
  }
  return state.listings.get(url);
}

/** The listing of a shipped example whose ELF is the bundle's (same SHA-256), or null. */
async function listingFor(header) {
  for (const example of state.map.examples) {
    if (example.task !== header.task || example.program !== header.program) continue;
    const listing = await fetchListing(example.listing);
    if (listing?.elf_sha256 === header.elf_sha256) return listing;
  }
  return null;
}

function showBundleError(message) {
  const box = $("bundle-error");
  if (box) {
    box.textContent = message;
    box.hidden = !message;
  }
}

/**
 * Opens a bundle and shows it. url is the `?bundle=` path that names it, or null for an
 * imported one: the URL is set when the bundle is shown, before its view has mounted, and a
 * bundle that is refused leaves the URL of the one before it. Resolves with whether it is the
 * open bundle once its route has settled.
 */
async function useBundle(source, { expectTask = null, cycle = null, url = undefined } = {}) {
  const load = ++state.loading;
  showBundleError("");
  const options = {
    expectTask,
    knownTasks: state.map.tasks.map((t) => t.id),
    navigate,
  };
  let api;
  try {
    api = await openBundle(source, options);
    // the first chunk is checked before anything is shown
    [options.listing] = await Promise.all([listingFor(api.header), api.cycle(api.first)]);
  } catch (e) {
    if (load === state.loading) {
      showBundleError(e instanceof BundleError ? `Bundle refused: ${e.message}` : `Bundle could not be read: ${e.message}`);
    }
    return false;
  }
  if (load !== state.loading) return false;
  const task = state.map.tasks.find((t) => t.id === api.header.task);
  api.course = {
    task, module5: state.map.module5, views: state.map.views.map((v) => v.name),
    notebook: notebookById, notebookLink: bootcampLink,
  };
  const previous = state.api;
  unmountView();
  let problem = null;
  try {
    detachBundle();
    attachBundle(api);
    if (cycle !== null) api.cursor.set(cycle);
    renderBars();
    // last: the progress records the test results of a bundle that is shown
    if (source.imported && api.tests) state.progress.setTests(api.header.task, api.header, api.tests);
  } catch (e) {
    // the bundle that was open, if any, stays open, with its bars and its view
    console.error(e);
    problem = e;
    detachBundle();
    if (previous) attachBundle(previous);
    renderBars();
  }
  if (!problem && url !== undefined) setBundleUrl(url);
  await route();
  if (problem) {
    if (load === state.loading) showBundleError(`Bundle could not be shown: ${problem.message}`);
    return false;
  }
  // a later bundle that opened while this one's view mounted is the open one
  return state.api === api;
}

/** The `?bundle=` parameter of the URL: path, or none (null); the cursor and task of another bundle go. */
function setBundleUrl(path) {
  const params = new URLSearchParams(location.search);
  if (path === null) params.delete("bundle");
  else params.set("bundle", path);
  params.delete("cycle");
  params.delete("task");
  const query = params.toString();
  history.replaceState(null, "", `${location.pathname}${query ? "?" + query : ""}${location.hash}`);
}

/** Makes api the open bundle: the site's handlers of its links and of its cursor. */
function attachBundle(api) {
  state.api = api;
  state.linkOff = api.on("link", ({ kind, payload }) => {
    if (kind === "activity-done") state.progress.setActivity(payload.task, payload.id, payload);
  });
  api.cursor.addEventListener("change", renderCursor);
}

function detachBundle() {
  state.linkOff?.();
  state.linkOff = null;
  state.api?.cursor.removeEventListener("change", renderCursor);
  state.api = null;
}

function pickFiles(expectTask) {
  const input = $("bundle-input");
  input.dataset.expect = expectTask ?? "";
  input.value = "";
  input.click();
}

function bundleInput() {
  const input = h("input", {
    id: "bundle-input", type: "file", multiple: true, webkitdirectory: true, hidden: true,
    "aria-label": "Bundle directory",
  });
  input.addEventListener("change", async () => {
    if (!input.files.length) return;
    let source;
    try {
      source = filesSource(input.files);
    } catch (e) {
      showBundleError(`Bundle refused: ${e.message}`);
      return;
    }
    // an imported bundle has no URL
    await useBundle(source, { expectTask: input.dataset.expect || null, url: null });
  });
  return input;
}

function openExample(example) {
  return useBundle(urlSource(example.bundle), { url: example.bundle });
}

// ---------------------------------------------------------------------------
// The bars above the page: the open bundle and the cursor

function renderBars() {
  const api = state.api;
  const bar = $("bundle-bar");
  const cursorBar = $("cursor-bar");
  bar.hidden = cursorBar.hidden = !api;
  if (!api) return;
  const hd = api.header;
  const counts = testCounts(api.tests);
  clear(bar).append(
    h("span", { id: "bundle-name" }, [`${hd.task} ${hd.variant}, program ${hd.program}`]),
    h("span", {}, [`cycles ${api.first}–${api.last} of a run of ${api.run.cycles} (${api.run.how})`]),
    h("span", {}, [`commit ${String(hd.commit).slice(0, 12)}`]),
    h("span", {}, [api.tests ? `tests: ${counts.pass} pass, ${counts.fail} fail` : "no test results"]),
    h("span", {}, [api.source.imported ? `imported from ${api.source.label}` : `example ${api.source.label}`]),
    ...(api.hashed ? [] : [h("span", { id: "bundle-unhashed", class: "warn" }, [
      "hashes not checked: this page is not in a secure context (open it from localhost or over https), " +
      "so only the sizes of the bundle's files are checked"])]),
  );
  clear(cursorBar).append(
    h("button", { id: "instr-prev", type: "button", onclick: () => stepInstruction(-1) }, ["◀ instruction"]),
    h("button", { id: "cycle-prev", type: "button", onclick: () => api.cursor.set(api.cursor.get() - 1) }, ["◀ cycle"]),
    h("label", {}, ["cycle ", h("input", {
      id: "cycle-input", type: "number", min: api.first, max: api.last, value: api.cursor.get(),
      onchange: (e) => {
        // an emptied field is not cycle 0: it shows the cursor again
        if (e.target.value.trim() === "") e.target.value = api.cursor.get();
        else api.cursor.set(e.target.value);
      },
    })]),
    h("button", { id: "cycle-next", type: "button", onclick: () => api.cursor.set(api.cursor.get() + 1) }, ["cycle ▶"]),
    h("button", { id: "instr-next", type: "button", onclick: () => stepInstruction(1) }, ["instruction ▶"]),
    h("span", { id: "cursor-info", "aria-live": "polite" }),
  );
  renderCursor();
}

async function stepInstruction(direction) {
  const api = state.api;
  if (!api) return;
  const start = api.cursor.get();
  let target;
  try {
    target = await api.nextRetire(start, direction);
  } catch (e) {
    const info = $("cursor-info");
    if (info && api === state.api) info.textContent = `the next instruction could not be found: ${e.message}`;
    return;
  }
  // another bundle, or a cursor moved while the chunks were read: that move stands
  if (target !== null && api === state.api && api.cursor.get() === start) api.cursor.set(target);
}

async function renderCursor() {
  const api = state.api;
  if (!api) return;
  const n = api.cursor.get();
  const input = $("cycle-input");
  if (input) input.value = n;
  let record;
  let problem = null;
  try {
    record = await api.cycle(n);
  } catch (e) {
    problem = e;
  }
  if (api !== state.api || n !== api.cursor.get()) return;
  const info = $("cursor-info");
  if (!info) return;
  if (problem) {
    info.textContent = `cycle ${n} could not be read: ${problem.message}`;
    return;
  }
  const parts = [];
  if (record.core) {
    parts.push(`pc ${formatValue(record.core.pc, 32)}`);
    const text = api.listing(record.core.pc);
    if (text) parts.push(text);
  }
  parts.push(record.retire ? `retires pc=${hex8(record.retire.pc)}` : "no retire record");
  info.textContent = parts.join(" · ");
}

// ---------------------------------------------------------------------------
// The home page: the course map

function details(title, children, open = false) {
  return h("details", { open }, [h("summary", {}, [title]), ...children]);
}

function progressRow(unit) {
  const p = state.progress.unit(unit.id);
  const done = Object.entries(p.activities);
  const tests = p.tests;
  const read = h("input", { type: "checkbox", id: `progress-read-${unit.id}`, checked: p.read });
  read.addEventListener("change", () => state.progress.setRead(unit.id, read.checked));
  return h("div", { class: "progress", id: `progress-${unit.id}` }, [
    h("label", { class: "state" }, [read, " read"]),
    h("span", { class: "state", "data-state": "activities" }, [
      done.length ? `activities done: ${done.map(([id, r]) => `${id} (${r.correct} of ${r.total})`).join(", ")}`
        : "activities done: none"]),
    h("span", { class: "state", "data-state": "tests" }, [
      tests ? `test results from an imported bundle: ${tests.program}, ${tests.pass} pass, ${tests.fail} fail ` +
        `(commit ${tests.commit.slice(0, 12)})` : "test results from an imported bundle: none"]),
  ]);
}

function viewLinks(unit) {
  const views = state.map.views.filter((v) => unit.views.includes(v.name));
  if (!views.length) return h("p", {}, ["No view serves this task yet."]);
  return h("ul", { class: "inline" }, views.map((v) => h("li", {}, [h("a", { href: `#/view/${v.name}` }, [v.title])])));
}

function exampleButtons(ids) {
  const examples = state.map.examples.filter((e) => ids.includes(e.id));
  return examples.map((e) => h("button", {
    type: "button", class: "example", "data-example": e.id, title: e.title, onclick: () => openExample(e),
  }, [`open example: ${e.program}`]));
}

function taskCard(unit) {
  const prereq = unit.prerequisites;
  return h("article", { class: "unit", id: `unit-${unit.id}`, "data-task": unit.id }, [
    h("h2", {}, [`${unit.id}: ${unit.title}`, " ", h("code", {}, [unit.dir])]),
    unit.summary ? h("p", {}, [unit.summary]) : h("p", {}, ["This task directory has no README yet."]),
    progressRow(unit),
    details("Goals", unit.goals.length ? [h("ul", {}, unit.goals.map((g) => h("li", {}, [g])))]
      : [h("p", {}, ["The task's README lists no goals."])], true),
    details("Prerequisites", [
      h("p", {}, ["The bootcamp notebooks that this task's exercises cite (docs/bootcamp-map.md):"]),
      prereq.notebooks.length ? h("ul", {}, prereq.notebooks.map((n) => h("li", {}, [bootcampLink(notebookById(n.id))])))
        : h("p", {}, ["none listed"]),
      prereq.bootcamp_files.length ? h("p", {}, ["Your bootcamp files that it instantiates: ",
        prereq.bootcamp_files.map((f) => `${f.file} (${f.notebook})`).join(", ")]) : null,
    ]),
    details(`Chisel modules (${unit.modules.length})`, [h("ul", {}, unit.modules.map((m) =>
      h("li", {}, [h("code", {}, [m.name]), " ", repoLink(m.file)])))]),
    details(`Tests (${unit.tests.length} specs)`, [h("ul", {}, unit.tests.map((t) =>
      h("li", {}, [h("code", {}, [t.name]), " ", repoLink(t.file), t.checks ? ` – ${t.checks}` : ""])))]),
    details(`Exercises (${unit.exercises.length})`, [h("ul", { class: "exercises" }, unit.exercises.map((x) =>
      h("li", { "data-exercise": x.id }, [h("strong", {}, [x.id]), ` ${x.title} `,
        repoLink(x.blanks[0].file, `${x.blanks[0].file.split("/").pop()}:${x.blanks[0].line}`),
        x.blanks.length > 1 ? ` (${x.blanks.length} blanks)` : ""])))]),
    h("div", { class: "views" }, [h("h3", {}, ["Views available now"]), viewLinks(unit),
      h("div", { class: "actions" }, [...exampleButtons(unit.examples),
        h("button", { type: "button", class: "import", "data-import": unit.id, onclick: () => pickFiles(unit.id) },
          [`import a bundle of ${unit.id}`])])]),
  ]);
}

function module5Card() {
  const m5 = state.map.module5;
  return h("article", { class: "unit", id: "unit-M5" }, [
    h("h2", {}, [m5.title]),
    h("p", {}, ["The notebooks behind the exercises, and the bootcamp files that the tasks build on."]),
    progressRow({ id: "M5" }),
    details("Notebooks used", [h("ul", {}, m5.notebooks.map((n) => h("li", {}, [bootcampLink(n)])))], true),
    details("Bootcamp files the tasks instantiate", [h("ul", {}, m5.bootcamp_files.map((f) =>
      h("li", {}, [h("code", {}, [f.file]), ` (notebook ${f.notebook}): ${f.use}`])))]),
  ]);
}

function storageNote() {
  return h("p", { class: "warn", id: "storage-note", hidden: !state.progress.problem }, [state.progress.problem]);
}

function renderHome(main) {
  main.append(
    h("section", { class: "intro" }, [
      h("h1", {}, ["Course map"]),
      h("p", {}, ["Each unit lists what the repository has for it. The views replay a recorded run of the RTL from an ",
        "observation bundle: a shipped example of the reference core, or one you make with ",
        h("code", {}, ["make obs PROG=<name>"]), " in a task directory and import here: it writes the bundle directory ",
        h("code", {}, ["build/<model>/obs/<name>/bundle/"]), ", where the model is t1, t2 or t3 for T1 to T3 and t4-forward or ",
        "t5-forward for T4 and T5 by default (another VARIANT names another model)."]),
      h("p", { class: "note" }, ["Progress (read, activities done, test results of imported bundles) is kept in this ",
        "browser only and is not used for grading."]),
      storageNote(),
    ]),
    h("section", { class: "bundle-panel" }, [
      h("h2", {}, ["Bundle"]),
      h("div", { class: "actions" }, [...exampleButtons(state.map.examples.map((e) => e.id)),
        h("button", { type: "button", id: "import-any", onclick: () => pickFiles(null) }, ["import a bundle directory"])]),
      h("p", { id: "bundle-error", class: "error", role: "alert", hidden: true }),
    ]),
    module5Card(),
    ...state.map.tasks.map(taskCard),
  );
}

// ---------------------------------------------------------------------------
// The router

function unmountView() {
  if (state.view?.module) {
    try {
      state.view.module.unmount();
    } catch (e) {
      console.error(`unmount of ${state.view.name}:`, e);
    }
  }
  state.view = null;
}

function renderMenu(active) {
  const menu = clear($("menu"));
  const items = [{ name: "", title: "Course map" }, ...state.map.views];
  for (const v of items) {
    const href = v.name ? `#/view/${v.name}` : "#/";
    const served = !v.name || (state.api && (!v.tasks || v.tasks.includes(state.api.header.task)));
    menu.append(h("a", {
      href, "data-view": v.name || "home", class: served ? "" : "unserved",
      "aria-current": v.name === active ? "page" : null,
    }, [v.title]));
  }
}

/**
 * Opens view `name` through the hash, so that the hashchange handler routes to it. Resolves when
 * the router has settled: that route has rendered its page and the view's mount has finished or
 * failed, or so has a newer route that superseded it. A view that is shown already resolves at once.
 */
function navigate(name) {
  state.routes.setHash(`#/view/${encodeURIComponent(name)}`);
  return state.routes.settled();
}

function route() {
  return state.routes.run(renderRoute);
}

async function renderRoute(token) {
  const m = /^#\/view\/([a-z0-9-]+)$/.exec(location.hash);
  const view = m ? state.map.views.find((v) => v.name === m[1]) : null;
  unmountView();
  renderMenu(view?.name ?? "");
  const main = clear($("main"));
  if (!view) {
    renderHome(main);
    return;
  }
  const container = h("div", { class: "view", id: `view-${view.name}` });
  main.append(h("h1", {}, [view.title]), container);
  if (!state.api) {
    container.append(h("p", {}, ["This view replays a bundle. Open one:"]),
      h("div", { class: "actions" }, exampleButtons(state.map.examples
        .filter((e) => !view.tasks || view.tasks.includes(e.task)).map((e) => e.id))),
      h("p", { id: "bundle-error", class: "error", role: "alert", hidden: true }));
    return;
  }
  if (view.tasks && !view.tasks.includes(state.api.header.task)) {
    container.append(h("p", { class: "warn" }, [
      `This view serves ${view.tasks.join(", ")}; the open bundle is of ${state.api.header.task}.`]));
    return;
  }
  let module;
  try {
    module = await import(`./${view.name}/index.js`);
  } catch (e) {
    container.append(h("p", { class: "error" }, [`The view ${view.name} could not be loaded: ${e.message}`]));
    return;
  }
  if (!state.routes.current(token)) return;
  state.view = { name: view.name, module };
  try {
    await module.mount(container, state.api);
  } catch (e) {
    console.error(e);
    container.append(h("p", { class: "error" }, [`The view ${view.name} failed: ${e.message}`]));
  }
}

async function boot() {
  const main = $("main");
  let map;
  loadSchema().catch(() => {}); // fetched while the map is; a failure is reported when a bundle opens
  try {
    const response = await fetch("map.json");
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    map = await response.json();
  } catch (e) {
    clear(main).append(h("p", { class: "error" }, [`map.json could not be read (${e.message}): run make -C web map.`]));
    return;
  }
  // the links of the map lead to the pinned bootcamp repository only over https
  const repo = map.module5.repository;
  if (!repo || !/^https:\/\/[^\s"<>]+$/.test(repo.url) || !/^[0-9a-f]{40}$/.test(repo.commit)) map.module5.repository = null;
  state.map = map;
  state.progress = new Progress();
  state.progress.addEventListener("change", () => {
    if (!state.view) {
      for (const unit of [{ id: "M5" }, ...map.tasks]) $(`progress-${unit.id}`)?.replaceWith(progressRow(unit));
      $("storage-note")?.replaceWith(storageNote());
    }
  });
  document.body.append(bundleInput());
  window.addEventListener("hashchange", (e) => {
    state.routes.hashChanged(e.newURL);
    route();
  });
  document.addEventListener("keydown", (e) => {
    // a view that handles the key itself (the waveform's canvas) prevents its default
    if (!state.api || e.defaultPrevented || e.target.closest?.("input, textarea, select")) return;
    // Alt, Ctrl and Meta with an arrow are the browser's and the system's (history, words, desktops)
    if (e.altKey || e.ctrlKey || e.metaKey) return;
    if (e.key === "ArrowRight" || e.key === "ArrowLeft") {
      const direction = e.key === "ArrowRight" ? 1 : -1;
      if (e.shiftKey) stepInstruction(direction);
      else state.api.cursor.set(state.api.cursor.get() + direction);
      e.preventDefault();
    }
  });
  const params = new URLSearchParams(location.search);
  await route();
  if (params.has("bundle")) {
    try {
      const source = urlSource(params.get("bundle"));
      const cycle = params.has("cycle") ? Number(params.get("cycle")) : null;
      await useBundle(source, { expectTask: params.get("task"), cycle });
    } catch (e) {
      showBundleError(`Bundle refused: ${e.message}`);
    }
  }
  document.documentElement.dataset.ready = "1";
}

boot();
