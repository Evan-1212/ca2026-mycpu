<!-- SPDX-License-Identifier: MIT -->
# Web Observation Interface

A static site that replays recorded simulation runs of the RTL: the course map, a 2D datapath of
T1 and T2, the CSRs and traps of T3 to T5, the pipeline of T4 and T5, the SoC's bus and VGA of T5,
predict-then-reveal activities and the retire records, all driven by an observation
bundle ([docs/observation.md](../docs/observation.md)). A bundle is a shipped example of the
reference core (`web/examples/`) or one that you make from your own core with
`make obs PROG=<name>` in a task directory, which writes it to `build/<model>/obs/<name>/bundle/`
(`build/t1/...` for T1, `build/t4-forward/...` for T4's default variant; docs/observation.md), and
import. Nothing here is part of grading, and the
progress that the site keeps stays in your browser.

## Commands

| Command | What it does |
|---------|--------------|
| `make -C web map` | build `web/map.json`, the course map (the only build step) |
| `make -C web serve` | build it and serve the repository's root; open `http://localhost:8000/web/` (`PORT=` changes the port) |
| `make -C web check` (or `make web-check`) | build the map, run the map and description tests (`web/tests/test_*.py`), the browser tests (`web/tests/browser.py`), then `make web-bundles` and the tests of the waveform and board views under the site (`web/tests/test_integration.py`), then `make web-view-bundles` and the tests of the views of T3 to T5 (`web/tests/test_views.py`) |
| `make -C web check-views` | only the last: the bundles of `web/views.mk` and `test_views.py` |
| `make -C web examples` | make the example bundles of `web/examples/` again (needs the Verilator models of T1 and T2) |

The site is served from the repository's root, not from `web/`, so that its links to the Chisel
sources and specs (`../1-single-cycle/...`) work. Opened as a file (`file://`), a browser does not
load the site's JavaScript modules; the page then shows how to serve it.

## Toolchain

- **Plain ES modules, no build step, no bundler, no `package.json`.** Every file under `web/` is
  what the browser loads. The one generated file is `map.json`, made by
  [`scripts/web-map.py`](../scripts/web-map.py) (Python standard library) and ignored by git.
- **No CDN and no third-party file at run time.** The one vendored library is three.js 0.186.1 in
  `web/vendor/three/` ([its README](vendor/three/README.md)), which the board view imports only
  when the browser has WebGL; the rest of the site needs no library.
- **Browser tests:** Playwright 1.63.0 for Python, run as
  `uv run --no-project --with playwright==1.63.0 python -I web/tests/browser.py`. That release
  uses Chromium build 1243 (`~/.cache/ms-playwright/chromium-1243` and
  `chromium_headless_shell-1243`); if your cache does not have it, `uv run --no-project --with
  playwright==1.63.0 python -m playwright install chromium` fetches it. The tests launch one
  browser, run every test in it one after another, close each context after its test, and retry a
  launch that fails for want of inotify instances.

## Files

| Path | What it is |
|------|------------|
| `index.html`, `style.css` | the page and its theme (light and dark from the system, `data-theme` on `<html>` forces one; usable at 360 px width) |
| `app.js` | the router, the course map, the bundle and cursor bars, bundle import |
| `lib/bundle.js` | reads and checks a bundle, builds the view `api` |
| `lib/router.js` | when the router has settled, which `api.navigate` waits for |
| `lib/progress.js` | the progress kept in `localStorage` |
| `lib/format.js`, `lib/dom.js` | how a value is printed; element construction without `innerHTML` |
| `datapath/` | the datapath view and its descriptions `t1.json`, `t2.json` |
| `csr/` | the CSRs, traps and interrupts of T3 to T5 (`lib/csr.js` names causes and bits) |
| `pipeline/` | the pipeline of T4 and T5 (`lib/pipe.js` follows each instruction through the stages) |
| `soc/` | the SoC's block diagram and its data bus, T5 |
| `vga/` | the VGA of T5: frame, framebuffer, the store behind a pixel (`lib/vgafb.js`), a second design next to it |
| `views.mk` | `make web-view-bundles`: the bundles of the views of T3 to T5, made with `make obs` into `build/` |
| `activities/` | the activities view |
| `retire/` | the retire records view |
| `waveform/` | the waveforms and logic analyzer view ([its README](waveform/README.md)) |
| `board/` | the virtual teaching board view ([its README](board/README.md)) |
| `vendor/three/` | three.js, for the board's 3D scene |
| `examples/` | the shipped bundles, their listings and `index.json` |
| `tests/` | `test_web_map.py`, `test_datapath.py` (unittest), `browser.py`, `test_integration.py` and `test_views.py` (Playwright), `elfsyms.py` (an ELF's symbols, for the tests) |

## Course map

`scripts/web-map.py --root <tree> --web web` writes `map.json` from:

- the task directories `<n>-<name>/` of the tree: each task's `README.md` (title, first paragraph,
  Goals, the Test Suite table), its Chisel modules (`class X ... extends Module` or `RawModule` under
  `src/main/scala`) and its spec files (`src/test/scala/**/*Spec.scala`; a spec that the README
  names but the tree does not have is not listed);
- `EXERCISES.md`: the exercises of each task and their blanks. An exercise whose task directory
  does not exist is an error;
- `docs/bootcamp-map.md`: the notebooks used, the notebooks that a task's exercises cite (shown as
  its prerequisites) and the bootcamp files that it instantiates; the top `Makefile` gives the
  pinned chisel-bootcamp URL and commit for the notebook links;
- `web/`: every `<view>/index.js` is a view (see below), and `examples/index.json` lists the
  shipped bundles.

The map describes this tree, whose `EXERCISES.md` is at its top. A task directory without a
README is listed with its modules, specs and exercises.

Each unit shows its goals, prerequisites, notebooks, Chisel modules, specs, exercises, the views
that serve it, its example bundles, and three separate progress states: **read** (a checkbox),
**activities done** (each activity finished, with its score) and **test results from an imported
bundle** (the pass and fail counts of the last bundle imported for the unit). They are stored
under the key `mycpu-web-progress-v1`, with every access in `try`/`catch`: when the browser
refuses storage or a write, or the stored data cannot be read, the page says so and keeps the
progress for that page only.

## The view contract

A view is a directory `web/<view>/` with an `index.js` module. A directory without it is not a
view, and a view that is absent is not in the menu: the router reads the views from `map.json`,
which `web-map.py` makes from the directories that exist. An optional `view.json` gives
`title`, `order` (menu position, default 100), `summary` and `tasks` (the tasks the view serves).
Without `tasks`, a view serves the tasks that have a `t<n>.json` in its directory, or every task
when it has none. The module exports:

```js
export async function mount(container, api) { ... }   // draw into container
export function unmount() { ... }                      // remove listeners and elements
```

The router calls `unmount()` of the current view before it mounts another one or opens another
bundle. It does not cancel a `mount` that is still waiting: a view whose `mount` awaits something
(a fetch) must check after the wait that no `unmount()` came in between, and then do nothing
(`datapath/index.js` keeps a counter for this). A view is mounted only when a bundle is open and
the view serves the bundle's task. Cycle numbers everywhere in `api` are absolute bundle cycles.
`api` (from `lib/bundle.js`) has:

| Member | What it is |
|--------|------------|
| `header`, `run`, `groups`, `tests` | the manifest's parts as obs-pack.py wrote them |
| `manifest` | the whole `manifest.json` (`run.first`, `groups`, `uart`, `vga`, `chunks`, ...) |
| `first`, `last` | the first and last cycle number of the window (absolute cycle numbers, as in the bundle) |
| `cycles()` | the number of cycles in the window |
| `cycle(n)` | a promise of the record of cycle `n`; reads and checks the chunk that holds it on first use |
| `retire(n)` | a promise of the retire object of cycle `n`, or `null` |
| `nextRetire(n, direction)` | a promise of the next (`1`) or previous (`-1`) cycle with a retire record, or `null` |
| `window(start, end)` | a promise of the records of cycles `start` to `end - 1`, clipped to the window |
| `field(name)` | `{name, group, bits, sample, description, present}` of a schema field (`core.pc`, `retire.mem.addr`), or `null`; `present` is false when the bundle lacks its group |
| `value(record, name)` | the value of a field in a record, or `undefined` |
| `cursor` | the shared cursor: an `EventTarget` with `get()`, `set(n)` (clamped to the window) and a `change` event whose `detail` is `{cycle, previous}` |
| `link(kind, payload)`, `on("link", handler)` | cross-view links: `link` sends `{kind, payload}` to every handler, the sender's own included (a payload carries `from`, the sending view's name, so that a view can ignore its echoes); `on` returns a function that removes the handler |
| `navigate(view)` | opens another view with the same bundle and cursor, through the hash `#/view/<view>`. Returns a promise that resolves once the router has settled: the route has rendered, and the view's `mount` has resolved or failed (a failure is shown in its place). It resolves at once when that view is shown already and no route is running; after the route's message for a view that does not exist or does not serve the bundle's task; and, when a newer route supersedes this one (another `navigate`, a menu click, a new bundle), only once the newest has finished. It never rejects. A view's `mount` must not await it: the router settles only after that mount |
| `listing(pc)` | the disassembly text of the instruction at `pc` when the bundle's ELF is that of a shipped example, else `null` |
| `stores()` | a promise of the bundle's `stores.json` (schema 1.1: `{lo, hi, stores}`, each store `[cycle, pc, addr, bytes, data]` of the whole run's retire log), checked like the files below and for its form, or `null` when the manifest lists none |
| `uart()`, `vgaFrame(n)` | promises of the bundle's UART log and VGA frame `n` as bytes (a `Uint8Array`, a copy for each call), checked against the size and SHA-256 that the manifest gave when the bundle opened (a view that changes `api.manifest` changes neither the checks nor the files that can be read), or `null` when the manifest lists none |
| `file(path)` | a promise of the bytes, checked the same way, of a file that the manifest lists as its UART log (`uart.file`), a VGA frame (`vga[i].file`, `vga/frame-<N>.ppm`) or its stores (`stores.file`); any other path or value, a chunk or `manifest.json` included, rejects with a `BundleError` ("... is not a file the manifest lists"), so no other file of the bundle's directory is read. A read that fails is not kept; the next call reads the file again |
| `course` | the map's entry of the bundle's task, the Module 5 notebooks, the view names, and helpers for notebook links |
| `schema` | the site's schema: version and every field |

Link kinds in use: `activity-done` (`{task, id, correct, total}`, recorded as progress by the
site); the waveform sends `uart-char` (`{cycle, char, from}`), `store` (`{cycle, addr, value,
from}`) and `instruction` (`{cycle, pc, inst, from}`) and handles `probe`, `uart-char` and
`store`; the board sends `probe` (`{signal, from}`) and `uart-char` and handles `uart-char`. A
view that opens another one and sends it a link awaits `navigate` first (the board does). A
handler ignores kinds it does not know. The cursor bar of the site (step by cycle, step by
instruction, a cycle number, and the arrow keys, with Shift for instructions) moves the shared
cursor for every view; an emptied cycle number moves nothing, and an arrow key with Alt, Ctrl or
Meta is left to the browser. A step by instruction that waits for a chunk does nothing when the
cursor moved or another bundle opened in the meantime. A view that takes the arrow keys itself
(the waveform's canvas) calls `preventDefault()`, and the site then leaves that key press alone.

## Bundles

A shipped bundle opens from the URL: `?bundle=examples/t1-add/bundle` (a path relative to `web/`;
anything with a scheme or `..` is refused). `&cycle=N` sets the first cursor position, and
`&task=T1` refuses a bundle of another task. A bundle of your own is imported from its
directory with **import a bundle directory**, or with **import a bundle of T<n>** in a unit, which
refuses a bundle of another task. An imported bundle lasts until the page is reloaded.

`lib/bundle.js` reads the format of `scripts/obs-pack.py` and refuses, with a message:

- a manifest of another format, a schema of another major version (`2.0`) or of a newer minor
  version than the site's, a header whose schema differs from the manifest's;
- a task other than the one asked for, or one for which the site has no unit;
- unknown groups; chunk entries that are not objects, that name one file twice, or that do not
  hold the window cycle after cycle; test results (`tests`) that are not a list of
  `{name, result, detail}` with `result` `pass` or `fail`;
- a chunk whose size or SHA-256 is not the manifest's (an imported file of another size is refused
  before it is read), whose cycles are out of order, or whose records lack a listed group, hold an
  unlisted one, or hold a value that is not an integer of the field's width;
- an imported bundle, when the page has no WebCrypto to check the hashes with. A browser offers
  WebCrypto in a secure context only: `localhost` and https are one, a page served over http from
  another address (`python3 -m http.server` opened at a LAN address) is not. There a shipped
  example, served by the site itself, still opens, with **hashes not checked** in the bundle bar
  and its sizes checked.

A read that fails (the site's schema, a datapath description, a chunk, a file) is not kept: the
next bundle or view asks for it again. An imported directory is read at the paths that the
manifest names below `manifest.json`; only files chosen one by one, without a directory, are
found by their names alone.

Every text from a bundle or the map is set as text, never as markup.

## Datapath

`datapath/t1.json` and `t2.json` describe the datapath as data: components (position, label, the
fields shown inside, the Chisel file and module of this tree or the bootcamp file, the notebook,
the specs) and wires (two ends `<component>.<side>.<fraction>`, optional via points, a bit width,
and a schema field). They hold names and bindings only: no expression and no logic
(`test_datapath.py` refuses Chisel or Scala operators in them). T2 adds the divider (the `div`
group), the multiplier and the `div.busy` mark on the fetch stage and the CPU box.

Every value on the drawing is `api.value(record, field)` of the record at the cursor, printed by
`lib/format.js`: a 1-bit field as 0 or 1, a field of 2 to 5 bits in decimal, a wider one in
hexadecimal. A state field shows a register after the edge that begins the cycle, a comb field
the value computed in it (docs/observation.md, "Sampling"); a retire row shows `–` in a cycle
without a retire record. A component or wire with no field, or bound to a field of a group the
bundle lacks, is drawn dashed and labeled "concept"; the immediate, the extended load value, the
link address, the device selects, the multiplier and T2's execute result have no probe field.
Clicking a component shows its Chisel module, its signals with their values, its notebook and
its specs; clicking a wire shows its value, source and destination.

## CSRs and traps, pipeline, SoC bus, VGA (T3 to T5)

Each of these views reads the whole window once when it mounts, and shows only fields of the
bundle (docs/observation.md has their signals) and retire records.

- **CSRs and traps** (`csr/`, T3 to T5): `mstatus`, `mie`, `mip`, `mtvec`, `mepc`, `mcause`
  and the WFI wait at the cursor, with the bits and the cause named as the Privileged spec names
  them; the CSR access, trap, MRET and `irq` of the cycle; a row per cycle with `csr.trap`: its
  cause, the record's pc, `mepc` and `mcause` in the cycle after, the handler's first retire
  record; "follow" lists the steps of an interrupt (its line rises in `mip`, `csr.irq` becomes
  1, the trap, the CSRs after it, the handler) and moves the cursor to each; a timeline of the
  interrupt lines, `mstatus.MIE`, `irq`, traps, MRET, WFI and retire records around the cursor.
- **Pipeline** (`pipeline/`, T4, T5): the five stages at the cursor with their `stall`,
  `flush` and `bubble`; the forwarding paths as concepts (not recorded); a chart of 41 cycles
  around the cursor, one row per instruction, from `lib/pipe.js`: a stage holds the instruction
  of the cycle before if it stalled then, else the one the stage before it held (ID takes the
  oldest word the fetch unit kept); an instruction that leaves a stage and reaches no other is
  crossed out; a row of the bubbles in EX. "Hazards in the window" lists every instruction that
  the hazard unit held in ID (`pipe.loadUseStall`, in cycles) or whose taken branch or jump left
  bubbles in EX (the cycles after it left EX up to the next instruction there; the redirect of
  the fetch unit is the branch's when EX's register is flushed with it, else ID's, unless a
  trap or an MRET made it; an ID redirect of a FENCE.I is not counted as a branch). "Variants side by side" adds bundles of the same ELF on other
  variants (a served path or an imported directory) and draws a bar per variant for each
  occurrence of such an instruction.
- **SoC bus** (`soc/`, T5): the block diagram with the D12 map, the five channels of the data
  master (valid, ready, payload; a channel transfers when both are 1) and the slave of the open
  transaction; the transactions near the cursor: an AW and a W transfer make a write that the
  next B answers, an AR one that the next R answers, and a B or R with nothing of the window
  open answers a transaction begun before the window, which is listed as such.
- **VGA** (`vga/`, T5): each `+vga-dump` frame; the framebuffer that `stores.json` gives at the
  frame's `cycle` (buffer N mod 2 for frame N, through the palette those stores set); for a
  pixel clicked on the screen (or chosen with the arrow keys on the focused screen, Shift for 4
  pixels) its framebuffer pixel, word, bits, the last whole-word store to
  that word (its cycle and pc), the palette index and color, whether that color is the
  screen's, and a warning when the word or the palette entry was written while the frame was
  scanned out (the attribution then holds only for the pixels scanned after that store); without
  `stores.json`, only the framebuffer pixel and its word; the video fields at the cursor; a second bundle's
  frame next to it (of the same program: another ELF is refused), with a canvas of
  the pixels in which the two differ; and the test results `qr-decode:N` and `golden:N` that
  `sim/tests/obs-vga-checks.py` adds (the QR decoder's text, the golden frame of `check-vga`).

## Activities

Seven activities, each linked to its Chisel sources and specs:

| Activity | Tasks | What it asks |
|----------|-------|--------------|
| The single-cycle datapath: add, lw, beq | T1, T2 | for the first `add`, `lw` and `beq` (taken and not taken) of the window: the ALU result, the addresses, the write-back select and value, whether control transfers, the next PC |
| The multi-cycle divider | T2 | for the first division of the window: how long the PC holds, how many cycles `div.busy` is 1, when the division retires, how many retire records the stall has, the value written |
| What each check proves | T1, T2 | for a stretch of cycles: what the retire log, the waveform (the per-cycle fields) and the test result each record, and which of them shows the PC in every cycle |
| Exceptions and interrupts | T3, T4, T5 | for the first interrupt whose line rises in the window (else the first trap): `mcause` and `mepc` after it, the handler's first pc, the cycles from the line's rise to the trap |
| Pipeline hazards | T4, T5 | for the first instruction that the hazard unit holds in ID and the first taken branch or jump with bubbles: the cycles held, the bubbles in EX |
| The UART: from a store to the terminal | T1 to T5 | for the first frame on `uart.txd` (a window from cycle 0): the byte, the pc of the store to TXDATA behind it (`waveform/link.js`'s rule), the cycles from that store to the start bit |
| The VGA: from the framebuffer to the screen | T5 | for screen pixel (321, 243) of the first frame with a cycle: the framebuffer word's address, the palette index stored for it, the pc of the store that wrote it |

Each expected answer is computed from the open bundle when the activity starts (the reference
core's behavior for an example, your core's for your bundle). The first activity finds its
instructions through the program's listing, so it runs on a bundle whose ELF is that of a shipped
example. The evidence link of "what each check proves" opens the waveform view at the activity's
first cycle (the retire records view in a tree that has no `waveform` view); the menu opens the
retire records in either case.

## Example bundles

`make -C web examples` runs `scripts/web-examples.py`, which runs each program once to find the
window by a rule and once more to pack it with `make obs` (OBS_START, OBS_CYCLES):

| Example | Model | Window | Bytes (bundle and listing) |
|---------|-------|--------|------:|
| `t1-add` | T1 | 40 cycles from the first record at `main` (cycle 17) | 31,075 |
| `t1-lw` | T1 | 40 cycles from cycle 17 | 31,663 |
| `t1-beq` | T1 | 40 cycles from cycle 17 | 29,284 |
| `t2-muldiv` | T2, `div34` | 48 cycles from 4 cycles before the first stretch of 20 cycles without a retire record (cycles 545 to 592: a `remu` that stalls 35 cycles) | 33,686 |

Next to each bundle, `listing.json` holds the disassembly lines (from the software build's
`.dis` of the same ELF, matched by SHA-256) of the PCs in the window. The four together are
125,708 bytes, far below the 9,000,000-byte budget of docs/observation.md. Reference bundles
are published only for these T1 and T2 programs. T3 to T5 get none: their behavior is itself
an exercise (the timing of a trap, of a hazard, of a bus transaction), and students import
bundles of their own design ([docs/observation-teaching.md](../docs/observation-teaching.md),
"Which bundles exist"). `make web-view-bundles` (`views.mk`) makes the bundles that
`test_views.py` replays from the reference, into `build/web/view-bundles/`, each next to its
run's retire log and ELF: `t3-irqtrap` (10,000 cycles), `t3-uart`, `t4-hazards-<variant>`,
`t5-nyancat` and `t5-nyancat-shift` (3,000 cycles, frame 1, the stores to the VGA, the golden
check), `t5-qrcode` (1,000 cycles, the six styles, the decoder's text).

## Tests

`make -C web check` runs:

- `test_views.py`, after `make web-view-bundles`: the CSR view lists exactly the cycles with
  `csr.trap` of irqtrap, and for each its record's pc, `mepc` and `mcause` equal the record and
  the CSRs of the next cycle; "follow" on each interrupt puts the cursor on the rise of its line
  in `mip`, then on the trap, the cycle after it (the CSR panel shows the record's `mepc` and
  `mcause`) and the handler's first instruction, whose pc is `mtvec`. For the 21 sequences of
  `HazardSpec` (parsed from the spec, and checked against `software/programs/hazards/main.S`)
  on each variant: the stall cycles and bubbles that the test counts from the bundle, and those
  that the pipeline view lists, equal the spec's numbers, a sequence without a hazard has no
  row, the chart shows each taken branch's bubbles after its E cell, and the variants side by
  side show all three; a bundle of another program is refused. On T5 nyancat: every store of the
  window is an AW and W transfer with its word address and data, and the SoC view counts the
  transactions and marks the transferring channel; 20 random pixels of frame 1 (the golden frame)
  land on the store that last wrote their word, whose 4 bits through the palette are the
  pixel's color; next to the VGA_SHIFT bundle the diff canvas marks exactly the pixels in which
  the mutant's frame differs from the golden frame, and the bundles' `golden:1` results say pass
  and fail; the six QR styles show the decoder's text and their golden check. Each activity of
  T3 to T5, on these bundles, refuses wrong answers and takes the answers this file computes;

- `test_web_map.py`: on fixture trees, the map lists exactly the task directories and the
  exercises of `EXERCISES.md`, a task directory added and removed appears and disappears, an
  exercise of a missing task and two directories of one task are errors, views are the
  directories with `index.js`; on the student tree, the map equals the directory listing and
  `EXERCISES.md`;
- `test_datapath.py`: every field of the descriptions exists with the width of its wire, every
  Chisel binding names a file that defines the module, every spec exists, no description holds
  code;
- `browser.py`: the home page shows every unit and exercise of the map; no horizontal scroll at
  360 px; progress survives a reload, and works when storage throws, holds bad data or refuses a
  write; importing a bundle records its test results; refusals of schema 2.0, schema 1.9, a T2
  bundle in the T1 unit, a T4 bundle, a doctored chunk, a window past the end of its run, and
  unsafe `?bundle=` paths; bundle text is not markup; for every cycle of the four examples,
  every value on the datapath equals the bundle's value of its field, and no number on it is
  outside an element bound to a field, stepping by cycle and by instruction both ways, with the
  fetch stage marked exactly while `div.busy` is 1; the component and wire panels; concept
  elements, also for a T2 bundle without its `div` group; each activity with the answers that
  the test computes from the bundle (all correct), with wrong ones (all refused), and the cycles
  of its trace; the evidence link of "what each check proves" opens the waveform view (or, with a
  map that has no `waveform` view, the retire records) with the cursor at the activity's first
  cycle, and the menu still opens the retire records; the retire records view against the bundle line by line, and a row that moves
  the cursor; a bundle open or a view mount that finishes after a later one; a chunk refused
  after the bundle opened, reported by every view; a map whose repository link is not https;
  a bundle without the `core` group; refusals of a manifest that names one chunk file twice, of
  chunk entries that are not objects, of test results that are not a list of results, and of a
  directory whose chunk lies elsewhere than at its path; without `crypto.subtle` an imported
  bundle refused and a served one shown with "hashes not checked"; a chunk of another size than
  the manifest's refused unread; a failure after a bundle opened that leaves the bundle open
  before it, its URL, cursor bar and datapath, and a refused import that leaves the URL; an
  example overtaken by an import while its view mounts (the URL names each when it is shown, and
  the import last); a failed read of the schema and of a datapath description that the next
  bundle or mount reads again; arrow keys with Alt, Ctrl or Meta and
  an emptied cycle number that move nothing; a step by instruction overtaken by a cursor move;
  an example opened after a `?task=` URL drops the task; an activity started twice while its
  chunk is read shows one form, and the page's ids are unique; in the pipeline view, a
  comparison that finishes after a later one, or after a later one refused before it started,
  changes nothing;
- `test_integration.py`, after `make web-bundles` (`web/waveform.mk`): the waveform and board
  views under this site's host, not the test host of `web/waveform/tests/`. It copies the T1
  `uart` bundle fed `hello, world\n`, and two copies of it with a VGA frame dump added, into
  `web/examples/tmp-integration/` (ignored by git, removed when the tests end) and opens them with
  `?bundle=`. The map and the menu list both views, and a map made from a copy of `web/` without
  `board/` lists no board view; the waveform's UART text equals `uart.bin`, and a click on a
  character moves the site's cursor to the store that wrote it, with `uart-char`, `store` and
  `instruction` on the bus; one arrow key on the waveform's canvas moves the cursor one cycle; the
  board's terminal equals the UART bytes up to the cursor at three positions set in the site's
  cursor bar, and without WebGL the 2D block diagram is shown; a probe on the UART TX pin, from the
  2D list, after menu clicks, and dragged in 3D under swiftshader, opens the waveform with
  `uart.txd` on top, which needs `navigate` to wait for the mount, and leaves no board link
  handler and no console error; the board's VGA screen shows the frame through `api.file`, a
  frame whose bytes are not the manifest's is refused, and `api.file` rejects every path that the
  manifest does not list as the UART log or a VGA frame; `lib/router.js` in the page: settled at
  once, after a new route's mount, at once for the view already shown, only after the newest of
  two routes, for the same hash while a mount runs, after a failed mount, after a route that only
  renders a message, and for two hash changes in one task.
