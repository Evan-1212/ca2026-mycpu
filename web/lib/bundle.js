// SPDX-License-Identifier: MIT
// Reads an observation bundle (docs/observation.md, "Bundles"; written by
// scripts/obs-pack.py) and builds the `api` that every view receives
// (web/README.md, "The view contract"). The format is obs-pack.py's: this
// file only reads it, and refuses a bundle it cannot vouch for with a
// BundleError whose message says why.

import { hex8 } from "./format.js";

const BUNDLE_FORMAT = "mycpu-obs-bundle";
const SCHEMA_URL = new URL("../schema/obs-v1.json", import.meta.url);
const SAFE_PATH = /^[A-Za-z0-9_][A-Za-z0-9_.\-/]*$/;

export class BundleError extends Error {
  constructor(message) {
    super(message);
    this.name = "BundleError";
  }
}

function need(condition, message) {
  if (!condition) throw new BundleError(message);
}

/** A relative path without a scheme, a leading slash or `..`. */
export function safePath(path) {
  return typeof path === "string" && SAFE_PATH.test(path) && !path.split("/").includes("..");
}

function parseVersion(text) {
  const m = /^(\d+)\.(\d+)$/.exec(typeof text === "string" ? text : "");
  return m ? { major: Number(m[1]), minor: Number(m[2]) } : null;
}

// ---------------------------------------------------------------------------
// The schema: which fields exist, their widths and sampling classes

let schemaPromise = null;

/** The site's schema (web/schema/obs-v1.json): version and every leaf field. */
export function loadSchema() {
  if (!schemaPromise) {
    const promise = fetch(SCHEMA_URL).then(async (response) => {
      need(response.ok, `the site's schema ${SCHEMA_URL} could not be read (${response.status})`);
      return readSchema(await response.json());
    });
    // a read that failed is not kept: the next bundle asks again
    promise.catch(() => {
      if (schemaPromise === promise) schemaPromise = null;
    });
    schemaPromise = promise;
  }
  return schemaPromise;
}

/** The fields of a JSON Schema document of the observation schema. */
export function readSchema(doc) {
  const version = parseVersion(doc["x-version"]);
  need(version, "the site's schema has no x-version");
  const defs = doc.$defs;
  const fields = new Map();
  const groups = new Map();
  const walk = (node, prefix, group) => {
    if (node.$ref && !("x-width" in node)) node = defs[node.$ref.replace("#/$defs/", "")];
    if ("x-width" in node) {
      fields.set(prefix, {
        name: prefix, group, bits: node["x-width"], sample: node["x-sample"], description: node.description ?? "",
      });
      groups.get(group).push(prefix);
      return;
    }
    for (const [key, child] of Object.entries(node.properties ?? {})) walk(child, `${prefix}.${key}`, group);
  };
  for (const group of Object.keys(defs.cycle.properties)) {
    if (group === "cycle") continue;
    groups.set(group, []);
    walk(defs[group], group, group);
  }
  return { version: doc["x-version"], major: version.major, minor: version.minor, fields, groups };
}

// ---------------------------------------------------------------------------
// Where a bundle's files come from

/**
 * A bundle served with the site: `path` is the bundle directory relative to
 * web/ (`examples/t1-add/bundle`), as the `?bundle=` parameter gives it.
 */
export function urlSource(path) {
  need(safePath(path), `"${path}" is not a bundle path of this site (a relative path without "..")`);
  const base = new URL(path.replace(/\/*$/, "/"), new URL("../", import.meta.url));
  return {
    label: path,
    imported: false,
    async read(name) {
      const response = await fetch(new URL(name, base));
      need(response.ok, `${path}/${name} could not be read (HTTP ${response.status})`);
      return new Uint8Array(await response.arrayBuffer());
    },
  };
}

/**
 * A bundle chosen with a file input: the files of the bundle directory
 * (a directory input gives `bundle/manifest.json`, `bundle/cycles/...`), or
 * the manifest and the chunks picked as single files. Only a selection of
 * single files (no path names a directory) finds a file by its name alone; in
 * a directory, a file is found at its path below the manifest's directory only.
 */
export function filesSource(fileList) {
  const files = Array.from(fileList ?? []);
  const manifests = files
    .map((f) => ({ file: f, path: f.webkitRelativePath || f.name }))
    .filter((e) => e.path.split("/").pop() === "manifest.json")
    .sort((a, b) => a.path.length - b.path.length);
  need(manifests.length, "the files chosen hold no manifest.json: choose the bundle directory that make obs wrote " +
    "(build/<model>/obs/<name>/bundle: build/t1/obs/<name>/bundle for T1, build/t4-forward/... for T4)");
  const root = manifests[0].path.slice(0, -"manifest.json".length);
  const flat = files.every((f) => !(f.webkitRelativePath || f.name).includes("/"));
  const byPath = new Map();
  const byName = new Map();
  for (const f of files) {
    const path = f.webkitRelativePath || f.name;
    if (path.startsWith(root)) byPath.set(path.slice(root.length), f);
    if (flat) byName.set(f.name, f);
  }
  return {
    label: root.replace(/\/$/, "") || manifests[0].file.name,
    imported: true,
    // bytes, when given, is the size that the manifest lists: a file of another size is refused unread
    async read(name, bytes = null) {
      const file = byPath.get(name) ?? (flat ? byName.get(name.split("/").pop()) : undefined);
      need(file, `the bundle's ${name} is not among the files chosen`);
      need(bytes === null || file.size === bytes, `${name} has ${file.size} bytes, the manifest says ${bytes}`);
      return new Uint8Array(await file.arrayBuffer());
    },
  };
}

// ---------------------------------------------------------------------------
// The checks of a manifest and of a chunk

/**
 * Whether this page can check SHA-256 hashes: the browser offers WebCrypto (`crypto.subtle`)
 * in a secure context only, which `localhost` and https are and a page served over http from
 * another address (a LAN IP, say) is not.
 */
export function canHash() {
  return Boolean(globalThis.crypto?.subtle);
}

const NO_HASH = "this page cannot check the SHA-256 hashes of an imported bundle: the browser offers " +
  "WebCrypto only in a secure context, and a page served over http from an address other than localhost " +
  "is not one. Open the site at http://localhost:<port>/web/ (make -C web serve) or over https";

async function sha256(bytes) {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * The bytes of a file that the manifest lists, checked against its size and SHA-256. Without
 * WebCrypto an imported file is refused, and a served one (a bundle of the site's own origin)
 * is checked for its size only, which its api says (`hashed`).
 */
async function verified(source, entry, what) {
  need(safePath(entry.file), `the manifest names ${what} "${entry.file}", which is not a path inside the bundle`);
  need(Number.isInteger(entry.bytes) && entry.bytes >= 0, `the manifest gives ${what} ${entry.file} no size`);
  need(canHash() || !source.imported, NO_HASH);
  const bytes = await source.read(entry.file, entry.bytes);
  need(bytes.length === entry.bytes, `${entry.file} has ${bytes.length} bytes, the manifest says ${entry.bytes}`);
  if (canHash()) {
    need(await sha256(bytes) === entry.sha256, `${entry.file} is not the file the manifest lists (its SHA-256 differs)`);
  }
  return bytes;
}

const isObject = (x) => x !== null && typeof x === "object" && !Array.isArray(x);

/** Refuses test results (manifest.tests) that are neither absent nor {results: [{name, result, detail}]}. */
function checkTests(tests) {
  if (tests === undefined || tests === null) return;
  need(isObject(tests) && Array.isArray(tests.results), "the manifest's tests are not an object with a list of results");
  tests.results.forEach((r, i) => {
    need(isObject(r) && typeof r.name === "string" && (r.result === "pass" || r.result === "fail") &&
      (r.detail === undefined || typeof r.detail === "string"),
    `the manifest's test result ${i} is not {name, result: "pass" or "fail", detail}`);
  });
}

/**
 * Refuses a manifest that this site cannot read: another format, a schema
 * of another major version or of a newer minor version, a task other than
 * the expected one or one the site has no unit for, unknown groups, chunk
 * entries that are not objects, name one file twice or do not cover the window
 * cycle after cycle, and test results that are not a list of results.
 */
export function checkManifest(manifest, schema, { expectTask = null, knownTasks = null } = {}) {
  need(manifest && typeof manifest === "object", "manifest.json is not a JSON object");
  need(manifest.format === BUNDLE_FORMAT,
    `manifest.json is not an observation bundle (its format is ${JSON.stringify(manifest.format)}, not "${BUNDLE_FORMAT}")`);
  const version = parseVersion(manifest.schema);
  need(version, `the bundle names no schema version (${JSON.stringify(manifest.schema)})`);
  need(version.major === schema.major,
    `the bundle uses observation schema ${manifest.schema}; this site reads schema ${schema.major}.x only. ` +
    "Make the bundle again with this repository's scripts/obs-pack.py, or use the site of the bundle's repository.");
  need(version.minor <= schema.minor,
    `the bundle uses observation schema ${manifest.schema}, newer than this site's ${schema.version}: ` +
    "it may hold fields this site does not know. Update the site.");
  const header = manifest.header ?? {};
  need(header.schema === manifest.schema, `the bundle's header names schema ${header.schema}, its manifest ${manifest.schema}`);
  need(/^T[1-9]$/.test(header.task ?? ""), `the bundle names no task (${JSON.stringify(header.task)})`);
  if (expectTask) {
    need(header.task === expectTask,
      `this bundle is of task ${header.task} (program ${header.program}), not of ${expectTask}: ` +
      `import it into the ${header.task} unit, or choose a bundle of ${expectTask}`);
  }
  if (knownTasks) {
    need(knownTasks.includes(header.task),
      `this bundle is of task ${header.task}, and this site has no unit for it (it has ${knownTasks.join(", ")})`);
  }
  const groups = manifest.groups;
  need(Array.isArray(groups) && groups.length, "the manifest lists no groups");
  for (const g of groups) need(schema.groups.has(g), `the manifest lists group "${g}", which schema ${schema.version} does not have`);
  const run = manifest.run ?? {};
  need(Number.isInteger(run.first) && run.first >= 0 && Number.isInteger(run.count) && run.count > 0,
    "the manifest's run has no window of cycles");
  need(Number.isInteger(run.cycles) && run.first + run.count <= run.cycles,
    `the window ${run.first} +${run.count} reaches past the end of the run (${run.cycles} cycles)`);
  const chunks = manifest.chunks;
  need(Array.isArray(chunks) && chunks.length, "the manifest lists no chunks");
  let next = run.first;
  const files = new Set();
  chunks.forEach((c, i) => {
    need(isObject(c), `the manifest's chunk ${i} is not an object`);
    // a chunk's records are kept by its file name: two entries of one file would share them
    need(!files.has(c.file), `the manifest lists chunk ${c.file} twice`);
    files.add(c.file);
    need(c.first === next && Number.isInteger(c.count) && c.count > 0,
      `the chunks do not hold the window cycle after cycle (chunk ${c.file} starts at ${c.first}, expected ${next})`);
    next += c.count;
  });
  need(next === run.first + run.count, `the chunks hold ${next - run.first} cycles, the run's window ${run.count}`);
  checkTests(manifest.tests);
}

/**
 * The check of one record against the manifest's groups and the schema's widths, prepared once
 * per bundle: every field with its path, its width, and, for the optional parts of a retire
 * record (mem, csr, trap, absent where the log prints `-`), the path of the part.
 */
function recordChecker(groups, schema) {
  const listed = new Set(groups);
  const required = groups.filter((g) => g !== "retire");
  const fields = groups.flatMap((g) => schema.groups.get(g).map((name) => {
    const path = name.split(".");
    return { name, group: g, path, part: g === "retire" && path.length > 2 ? path.slice(0, -1) : null,
      limit: 2 ** schema.fields.get(name).bits, bits: schema.fields.get(name).bits };
  }));
  return (record, cycle, file) => {
    need(record && record.cycle === cycle, `${file}: cycle ${cycle} is missing or out of order`);
    for (const key of Object.keys(record)) {
      need(key === "cycle" || listed.has(key), `${file}: cycle ${cycle} has group "${key}", which the manifest does not list`);
    }
    for (const g of required) need(g in record, `${file}: cycle ${cycle} lacks group "${g}"`);
    for (const f of fields) {
      if (!(f.group in record)) continue;
      const value = walk(record, f.path);
      if (value === undefined && f.part && walk(record, f.part) === undefined) continue;
      need(Number.isInteger(value) && value >= 0 && value < f.limit,
        `${file}: cycle ${cycle}: ${f.name} is ${JSON.stringify(value)}, not a ${f.bits}-bit value`);
    }
  };
}

function walk(node, path) {
  for (const part of path) {
    if (node === undefined || node === null || typeof node !== "object") return undefined;
    node = node[part];
  }
  return node;
}

/** The value of field `name` (`core.pc`, `retire.mem.addr`) in a record, or undefined. */
export function valueAt(record, name) {
  return walk(record, name.split("."));
}

// ---------------------------------------------------------------------------
// The bundle and its api

class Cursor extends EventTarget {
  constructor(first, last) {
    super();
    this.first = first;
    this.last = last;
    this.value = first;
  }

  get() {
    return this.value;
  }

  /** Moves to cycle n (clamped to the window); fires `change` with {cycle, previous} if it moved. */
  set(n) {
    const cycle = Math.min(this.last, Math.max(this.first, Math.trunc(Number(n))));
    if (!Number.isFinite(cycle) || cycle === this.value) return this.value;
    const previous = this.value;
    this.value = cycle;
    this.dispatchEvent(new CustomEvent("change", { detail: { cycle, previous } }));
    return cycle;
  }
}

/**
 * Opens a bundle: reads and checks its manifest. The chunks are read when a
 * cycle of theirs is asked for, and checked then.
 *
 * options: expectTask, knownTasks (see checkManifest), navigate(viewName) (its
 * result, a promise of the router having settled, is what api.navigate resolves with),
 * listing ({lines: {pc hex: {text}}} of the program; read when a view asks,
 * so the site may set it after the bundle is open).
 */
export async function openBundle(source, options = {}) {
  need(canHash() || !source.imported, NO_HASH);
  const schema = await loadSchema();
  let manifest;
  try {
    manifest = JSON.parse(new TextDecoder().decode(await source.read("manifest.json")));
  } catch (e) {
    if (e instanceof BundleError) throw e;
    throw new BundleError(`manifest.json is not JSON: ${e.message}`);
  }
  checkManifest(manifest, schema, options);
  return makeApi(source, manifest, schema, options);
}

function makeApi(source, manifest, schema, options) {
  const run = manifest.run;
  const first = run.first;
  const last = run.first + run.count - 1;
  const chunks = manifest.chunks;
  const loaded = new Map();
  const checkRecordOf = recordChecker(manifest.groups, schema);
  const bus = new EventTarget();
  const cursor = new Cursor(first, last);

  function chunkOf(n) {
    need(Number.isInteger(n) && n >= first && n <= last, `cycle ${n} is outside the bundle's window ${first}..${last}`);
    let lo = 0;
    let hi = chunks.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (chunks[mid].first <= n) lo = mid;
      else hi = mid - 1;
    }
    return chunks[lo];
  }

  function load(chunk) {
    if (!loaded.has(chunk.file)) {
      const promise = (async () => {
        const bytes = await verified(source, chunk, "chunk");
        let data;
        try {
          data = JSON.parse(new TextDecoder().decode(bytes));
        } catch (e) {
          throw new BundleError(`${chunk.file} is not JSON: ${e.message}`);
        }
        need(data.first === chunk.first && data.count === chunk.count && Array.isArray(data.cycles) &&
          data.cycles.length === chunk.count, `${chunk.file} does not hold the cycles the manifest lists for it`);
        data.cycles.forEach((record, i) => checkRecordOf(record, chunk.first + i, chunk.file));
        return data.cycles;
      })();
      promise.catch(() => loaded.delete(chunk.file));
      loaded.set(chunk.file, promise);
    }
    return loaded.get(chunk.file);
  }

  async function cycle(n) {
    const chunk = chunkOf(n);
    return (await load(chunk))[n - chunk.first];
  }

  async function nextRetire(n, direction) {
    const step = direction < 0 ? -1 : 1;
    let c = n + step;
    while (c >= first && c <= last) {
      const chunk = chunkOf(c);
      const records = await load(chunk);
      for (; c >= chunk.first && c < chunk.first + chunk.count; c += step) {
        if (records[c - chunk.first].retire) return c;
      }
    }
    return null;
  }

  async function window(start, end) {
    const out = [];
    let n = Math.max(first, start);
    const stop = Math.min(last + 1, end);
    while (n < stop) {
      const chunk = chunkOf(n);
      const records = await load(chunk);
      const upto = Math.min(stop, chunk.first + chunk.count);
      for (let k = n; k < upto; k++) out.push(records[k - chunk.first]);
      n = upto;
    }
    return out;
  }

  // The files besides the chunks that the manifest lists, the only ones that uart(), vgaFrame()
  // and file() read: copies taken now, so that a view that changes api.manifest changes nothing.
  const copy = (e) => (e && typeof e === "object"
    ? Object.freeze({ file: e.file, bytes: e.bytes, sha256: e.sha256, frame: e.frame, cycle: e.cycle }) : null);
  const uartEntry = copy(manifest.uart);
  const vga = (Array.isArray(manifest.vga) ? manifest.vga : []).map(copy).filter(Boolean);
  const storesEntry = manifest.stores && typeof manifest.stores === "object" // schema 1.1, optional
    ? Object.freeze({ ...copy(manifest.stores), lo: manifest.stores.lo, hi: manifest.stores.hi, count: manifest.stores.count })
    : null;
  const listed = [uartEntry, ...vga, storesEntry].filter(Boolean);

  // By entry, not by path: two entries that name one file are each checked against their own
  // size and hash. A read that failed is not kept, and each caller gets its own copy of the bytes.
  const fileCache = new Map();
  async function file(entry) {
    if (!entry) return null;
    if (!fileCache.has(entry)) {
      const what = entry === uartEntry ? "the UART log" : entry === storesEntry ? "the stores" : `VGA frame ${entry.frame}`;
      const promise = verified(source, entry, what);
      promise.catch(() => fileCache.delete(entry));
      fileCache.set(entry, promise);
    }
    return (await fileCache.get(entry)).slice();
  }

  async function listedFile(path) {
    const hit = typeof path === "string" ? listed.find((e) => e.file === path) : undefined;
    need(hit, `${typeof path === "string" ? JSON.stringify(path) : `a ${typeof path}`} is not a file the manifest lists ` +
      "(its UART log, a VGA frame or its stores)");
    return file(hit);
  }

  return {
    header: manifest.header,
    run,
    groups: manifest.groups.slice(),
    tests: manifest.tests,
    manifest,
    schema,
    source: { label: source.label, imported: source.imported },
    hashed: canHash(), // false: a served bundle whose files are checked for their size only
    first,
    last,
    course: null, // set by the site: the map's entry of the task
    cursor,
    cycles: () => run.count,
    cycle,
    async retire(n) {
      return (await cycle(n)).retire ?? null;
    },
    nextRetire,
    window,
    field(name) {
      const f = schema.fields.get(name);
      return f ? { ...f, present: manifest.groups.includes(f.group) } : null;
    },
    value: valueAt,
    listing(pc) {
      const line = options.listing?.lines?.[hex8(pc)];
      return line ? line.text : null;
    },
    uart: () => file(uartEntry),
    vgaFrame: (frame) => file(vga.find((v) => v.frame === frame)),
    stores: async () => {
      if (!storesEntry) return null;
      let data;
      try {
        data = JSON.parse(new TextDecoder().decode(await file(storesEntry)));
      } catch (e) {
        if (e instanceof BundleError) throw e;
        throw new BundleError(`${storesEntry.file} is not JSON: ${e.message}`);
      }
      const what = storesEntry.file;
      need(data && Array.isArray(data.stores) && data.lo === storesEntry.lo && data.hi === storesEntry.hi &&
        data.stores.length === storesEntry.count && data.lo < data.hi,
      `${what} does not hold the range and the number of stores that the manifest lists`);
      // the order of the log, inside the run and the range, each field within its width (scripts/obs-validate.py)
      let last = -1;
      data.stores.forEach((x, i) => {
        const ok = Array.isArray(x) && x.length === 5 && x.every((v) => Number.isInteger(v) && v >= 0);
        const [cycle, pc, addr, bytes, value] = ok ? x : [];
        need(ok && cycle >= last && cycle < run.cycles && addr >= data.lo && addr < data.hi && [1, 2, 4].includes(bytes) &&
          addr % bytes === 0 && pc < 2 ** 32 && value < 2 ** (8 * bytes),
        `${what}: store ${i} is not [cycle, pc, addr, bytes, data] of a store in the run's order to the range`);
        last = cycle;
      });
      return data;
    },
    file: listedFile,
    link(kind, payload) {
      bus.dispatchEvent(new CustomEvent("link", { detail: { kind, payload } }));
    },
    on(type, handler) {
      const listener = (event) => handler(event.detail);
      bus.addEventListener(type, listener);
      return () => bus.removeEventListener(type, listener);
    },
    navigate(view) {
      return Promise.resolve(options.navigate?.(view));
    },
  };
}
