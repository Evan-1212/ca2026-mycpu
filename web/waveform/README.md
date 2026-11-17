<!-- SPDX-License-Identifier: MIT -->
# Waveforms and logic analyzer

`web/waveform/` draws digital waveforms of the schema fields of an observation bundle
([docs/observation.md](../../docs/observation.md)): bus values in hexadecimal or decimal, 1-bit
fields as levels, two cursors with their distance, a trigger, zoom with level-of-detail summaries,
a UART decoder on `uart.txd` with the trace-back from a character to the store that wrote it, and
an AXI4-Lite decoder for bundles that have the `bus` group. The view says on screen that it shows
the RTL logic levels recorded once per cycle, not voltages.

Plain ES modules, no build step, nothing loaded from a CDN.

| File | What it does |
|------|--------------|
| `index.js` | the view: `mount(container, api)`, `unmount()`, the layout, cursors, links |
| `data.js` | what the view reads from the host, the schema's fields, the typed arrays and their run-length form; shared with `web/board/` |
| `render.js` | the canvas: ruler, levels, buses, UART frames, cursors |
| `lod.js` | the summary of one pixel column |
| `trigger.js` | the next or previous cycle in which a condition starts to hold |
| `uart.js` | the UART decoder and the bit-time measurement |
| `link.js` | from a UART frame to its store to TXDATA, and back |
| `axi.js` | AXI4-Lite transactions from the `bus` group |
| `dom.js` | DOM helpers (text only), `localStorage` with a no-storage path; shared with `web/board/` |
| `waveform.css` | the view's style, light and dark |
| `tests/` | the test host and the browser tests (below) |

## Contract with the host

A view is the ES module `web/<view>/index.js`; it exports `async function mount(container, api)`
and `function unmount()`. Only one instance of a view is mounted at a time; `mount` while mounted
unmounts first. `mount` resolves when the view has loaded the window and drawn it, and
`unmount` removes every listener the view added (to `api.cursor`, through `api.on`, on its own
elements) and empties the container.

The host is the site's: `api` is the object that `web/lib/bundle.js` builds ([web/README.md](../README.md),
"The view contract"). What this view uses of it:

| Member | Used as |
|--------|---------|
| `api.first`, `api.last`, `api.cycles()` | the window's first and last cycle and its number of cycles |
| `api.groups`, `api.schema` | the bundle's groups, and the site's schema: the view draws every field of the groups but `retire`, in schema order, with its width and sampling class |
| `api.header` | the manifest's `header` (task, variant, program, `uart_divider`, `ram_base`, `ram_bytes`) |
| `api.manifest` | for the board, `vga` (the frame dumps), read through `api.file` |
| `api.window(start, end)` | **a promise of the cycle records of cycles `start` … `end - 1`, absolute cycle numbers, in order**, each the schema's `cycle` object, checked by `lib/bundle.js`. The view reads the whole window in blocks of 4096 cycles once, at mount. |
| `api.cursor` | an `EventTarget` with `get()` and `set(n)` and a `change` event; `n` is an **absolute** cycle number. The view reads `get()` in its handler, ignores the event's detail, and clamps the value to the window. |
| `api.link(kind, payload)` | the view sends `uart-char` `{cycle, char, from}` (cycle = the frame's first cycle, char = the byte), `store` `{cycle, addr, value, from}` and `instruction` `{cycle, pc, inst, from}`, with `from: 'waveform'` |
| `api.on('link', handler)` | `handler` receives `{kind, payload}`; **`api.on` returns a function that unsubscribes**. The view ignores messages with `from: 'waveform'` and handles `probe` `{signal}` (puts that field on top), `uart-char` `{cycle}` (selects the frame that starts at, or contains, that cycle and traces it back) and `store` `{cycle}` (shows the frame of a TXDATA store retired in that cycle). |

`api.cycle`, `api.retire`, `api.field` and `api.navigate` are not used by this view. The left and right arrow keys on the canvas call `preventDefault()`,
so that the site's own arrow keys do not move the cursor a second time. The handler of `link` is
registered before the view's first `await`, and links that arrive while the bundle loads are
handled after it has loaded, so a host may send a link right after it has called `mount`.

Strings from a bundle are inserted with `textContent` and attributes, never parsed as HTML.
The chosen fields are remembered per task in `localStorage`
(`mycpu-obs.waveform.fields.<task>`), the row of the UART decoder (`#uart`) among them; a field
that one bundle of the task lacks stays chosen and is drawn again with a bundle that has it. Every
access is in `try`/`catch`, and the view works the same without storage.

## What the view shows

**Cycles and sampling classes.** Cycle N is drawn from x(N) to x(N + 1). A `state` field shows the
register after the edge that begins cycle N, a `comb` field the value computed within cycle N, an
`input` field what the harness drove in cycle N; each row names its class. Cursor A is the shared
`api.cursor`; a click sets it, shift-click sets cursor B, and the toolbar shows B − A in cycles.
The left and right arrow keys move cursor A by one cycle. The retire record of cycle A is listed
below the waveforms.

**Trigger.** On any field: equals a value (decimal or `0x` hexadecimal), rise, fall or change.
A match is the first cycle of a run of the field, so "equals" matches where the field takes the
value; the first cycle of the window never matches an edge, because the cycle before it is not in
the bundle. "Next match" and "Previous match" move cursor A.

**Level of detail.** When a pixel column covers more than one cycle, it is summarized from the
field's run-length form with two binary searches: its number of changes and, if it has none, its
one value. A column with a change in it is drawn as changing (a full-height bar for a 1-bit field,
a filled band for a bus), so a pulse of one cycle stays visible at any zoom. Constant columns of a
bus merge into a labeled segment.

**UART decoder.** 8N1 frames on `uart.txd`: a start bit of 0, eight data bits LSB first, a stop bit
of 1. The bit time is the header's `uart_divider`, which `obs-pack.py` copies from the trace header
(an elaboration parameter of the model; docs/observation.md, "Header"). The view also measures it
from the line, as the greatest common divisor of the lengths of the low pulses (each a start bit and
the zero data bits after it), shows both, and warns when the measurement is not a multiple of the
header's value; without `uart_divider` it decodes with the measurement. The samples are those of the
harness's decoder (`sim/uart.cpp`): bit k (0 start, 1–8 data, 9 stop) in cycle
start + ⌊divider/2⌋ + k·divider, a start bit that is 1 in its middle is not a frame, and after a
framing error the decoder waits for the line to return to 1. So a window from cycle 0 decodes to the
bytes of the run's `+uart-log`, and the tests check that byte for byte. A frame whose stop bit falls
after the window's last cycle is not decoded, and bytes that the harness decoded in the cycles it
runs after EXIT are in `uart.bin` only.

A window that starts after cycle 0 may start inside a frame. The decoder then takes as the first
start bit the falling edge, within the first 18 bit times, from which the most frames decode
without a framing error, and the panel says so; a 0 after 8 bit times of 1 is always a start bit.
Data that looks like shifted frames could mislead that search; a window from cycle 0 never needs it.

**Trace-back.** Clicking a decoded character sends `uart-char`, finds the store that wrote the
byte, moves cursor A to the cycle in which that store retired, marks it and sends `store` and
`instruction`. A full-word store to TXDATA (D12, `0x1000_0000`) pushes one byte into the TX FIFO;
a store with other byte strobes is ignored by the UART and is not counted. The transmitter takes
bytes in order and has taken a frame's byte by the frame's first cycle s; the bytes pushed after it
and before s are still in the FIFO in cycle s, and `uart.txCount` (a state field) counts them. So
the store of the frame is the (txCount[s] + 1)-th most recent TXDATA store that retired before s.
The nearest earlier store with the same byte is not it whenever a byte repeats while the FIFO
holds more than one byte (the `hello, world` test has that case). A store that retired before the
window's first cycle is reported as such. The list of TXDATA stores goes the other way: a click
shows the frame that sent that store's byte.

**AXI4-Lite.** Only with a `bus` group (T5); no T1 or T2 bundle has one, and the panel says so. A
channel transfers in a cycle in which valid and ready are both 1; the k-th AW, W and B transfers
form the k-th write and the k-th AR and R transfers the k-th read (AXI4-Lite has no IDs, so a
manager's transactions complete in order). For a window that starts after cycle 0 a transaction
open at its start shifts the pairing, and the panel says so.

## Tests

`make web-bundles` makes the bundles (`web/waveform.mk`): `uart` on T1 and T2 with the default
input, `uart` on T1 with `hello, world\n` as input from cycle 0 and from cycle 1000, and the first
10,000 cycles of `muldiv` on T2, each with its retire log. `make check-web-waveform` runs
`tests/test_waveform.py` with Playwright 1.63.0 (its Chromium, revision 1243, is the one installed
under `~/.cache/ms-playwright`), one browser at a time; `tests/runner.py` serves `web/` and the
bundles and retries a launch that fails for lack of inotify watches.

`tests/host.js` and `tests/standalone.html` are a minimal host for these tests (and those of
`web/board/`): the site's own `api` (`openBundle` of `lib/bundle.js`) over one bundle at any URL,
with the two views and no router, and with counts of the listeners a view leaves behind. A test
that changes a bundle's chunks writes their new sizes and SHA-256 into its manifest. `web/tests/test_integration.py` (part of `make -C web check`) runs both views
under the site's host. Every expected value is read in Python from the bundle's files, `uart.bin` and
the retire log. The checks include: the decoded bytes equal `uart.bin` (and a copy of the bundle
with one data bit of `uart.txd` flipped makes that same check fail); every character's trace-back
lands on the k-th full-word TXDATA store of the retire log, with its pc, instruction, address and
data; the late window; the values at the cursor against the records; trigger matches; a one-cycle
pulse survives the level of detail; decoder cases (dividers 1, 2, 3, 16, framing error, start
glitch, a window inside back-to-back frames); AXI pairing on a synthetic trace; bundle text that
looks like HTML stays text; no storage; listeners removed on unmount.

## Budgets

Measured with `test_10000_cycle_window_budget` on the 10,000-cycle `muldiv` bundle, headless
Chromium 1243 on the shared course host (canvas 2D in software, 1400 × 900 viewport), every field
of the bundle on screen (46 rows), 35 redraws: the whole window and six zoom steps, five times.

| What | Measured | Budget (the test fails above it) |
|------|---------:|---------------------------------:|
| reading the 10,000 cycles through `api.window` (10 chunks, 7.2 MB of JSON) | 200–350 ms | 3,000 ms |
| one full redraw, median of 35 | 15–21 ms | 40 ms |
| one full redraw, worst of 35 | 23–63 ms (the high end while other browsers ran on the host) | 100 ms |

The level of detail keeps a redraw at O(rows × columns × log runs), independent of the window's
length; the load is linear in the window and happens once.
