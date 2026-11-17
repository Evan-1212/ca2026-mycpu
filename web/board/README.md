<!-- SPDX-License-Identifier: MIT -->
# Virtual teaching board

`web/board/` shows the recorded design as a 3D board, labeled on the board and in the page
"virtual teaching board, not a physical PCB": the SoC, the RAM, the boot ROM, a UART connector with
a virtual terminal, a VGA connector with a screen, clock, reset and power as concepts, and two
digital probes. Without WebGL it shows the same parts as a 2D block diagram.

| File | What it does |
|------|--------------|
| `index.js` | the view: `mount(container, api)`, `unmount()`, panels, probes, links |
| `model.js` | the parts, the nets, the D12 memory map and the UART and VGA registers, as data |
| `scene.js` | the three.js scene, built from boxes, cylinders and text drawn on canvases, and its own orbit, pan and zoom controls |
| `panels.js` | the 2D block diagram, the panels, the virtual terminal, the VGA screen |
| `board.css` | the view's style, light and dark |
| `tests/test_board.py` | the browser tests (below) |

three.js 0.186.1 is vendored in [`web/vendor/three/`](../vendor/three/README.md) and imported only
when WebGL is available. No model file, texture or add-on is loaded: every shape is made in
`scene.js`, every label is text drawn on a canvas at run time.

## What it shows

- **Parts.** A part of the design is drawn solid; a part of D12 that the bundle's task does not
  have (the boot ROM and the VGA before T5) is drawn faded and labeled "T5 only"; clock, reset and
  power are concepts, gray with a dashed outline and labeled "concept", because nothing in a bundle
  records them.
- **Panels.** A click on a part (or on a pin, which opens its part) opens its panel below the board:
  the SoC opens its block diagram (the 2D diagram, which needs no WebGL) and the D12 memory map,
  with the rows the task does not decode marked; the UART opens its registers (D12 offsets and
  addresses), the bit time, the values of `uart.*` at the cursor and its output; the VGA opens its
  registers and the screen; the RAM its base and size from the header and the data port at the
  cursor; the ROM its D12 entry; the concepts a short text.
- **Virtual terminal.** An HTML overlay on the board (below the diagram in 2D) with the bytes
  decoded from `uart.txd` (web/waveform/uart.js) whose stop bit was sampled by the cursor's cycle.
  A click on a character opens the waveform view and sends `uart-char`, so the waveform traces it
  back to its store.
- **VGA screen.** The first `+vga-dump` frame of the bundle (`manifest.vga`, a binary PPM read with
  `api.file`), or "no VGA data in this bundle". T1 and T2 bundles have none.
- **Probes.** Drag P1 or P2 onto a pin; within one board unit it snaps onto the pin, and the board
  opens the waveform view on the pin's field (`api.navigate('waveform')`, then
  `api.link('probe', {signal, from: 'board'})`). A pin whose field the bundle lacks, or a concept
  net, says so and stays on the board. The probes' places are remembered in `localStorage`
  (`mycpu-obs.board.probes`, in `try`/`catch`; without storage they start at home). Without WebGL
  the "Probe a net" list does the same.
- **Controls.** Drag to orbit, right-drag or shift-drag to pan, the wheel to zoom. The scene is
  drawn on demand, not in a loop.

## Contract with the host

The same as the waveform view's ([web/waveform/README.md](../waveform/README.md), "Contract"),
and in addition:

- `api.navigate(name)` returns a promise that resolves once the target view's `mount` has
  finished or failed (the test host's, once it has mounted; [web/README.md](../README.md), "The
  view contract"). The board awaits it and then sends its link. The waveform view registers its
  link handler before its first `await` and keeps links that arrive while it loads, so the link
  arrives either way.
- `api.file(path)`: a promise of the bytes (a `Uint8Array`, checked against the manifest's size
  and SHA-256) of a file that the manifest lists, by its path there (`vga/frame-<N>.ppm`); a path
  that the manifest does not list as the UART log, a VGA frame or the stores is rejected.
- Links sent: `probe` `{signal, from: 'board'}`, `uart-char` `{cycle, char, from: 'board'}`.
  Received: `uart-char` from other views marks that character in the terminal.

`unmount()` stops rendering, removes every listener, disposes of every geometry, material and
texture, disposes of the renderer, loses its WebGL context and empties the container.

## Tests

`make check-web-board` (`web/waveform.mk`) runs `tests/test_board.py` on the bundles of
`make web-bundles`: first in a Chromium with WebGL through swiftshader (software rendering, no
GPU), then in one with WebGL turned off, one browser at a time, through the test host of
`web/waveform/tests/`. With WebGL: the scene loads within its budget; a click at the projected
place of every part reaches its panel, with the contents checked; orbit and zoom move the scene
without opening a panel; dragging P1 onto the UART TX pin opens the waveform view on `uart.txd`
and the probe's place is remembered; a concept pin and a pin the bundle lacks keep the board;
the terminal's bytes at seven cursor positions equal the bytes of `uart.bin` whose frames ended by
then (from an independent decoder in the test that is first checked against `uart.bin`); a
character of the terminal traces back to the right store; unmount leaves no canvas and no listener.
Without WebGL: the 2D diagram appears, every block (by click and by keyboard) and every list entry
reaches its panel, the terminal and the probe list work, and the view works without storage.
`web/tests/test_integration.py` (part of `make -C web check`) runs the board under the site's own
host: the terminal against the site's cursor bar, the probe into the waveform view, the 2D
diagram without WebGL and a VGA frame read through `api.file`.

## Budgets

The time from `mount` to the first frame drawn (three.js imported, scene built, one render and
`gl.finish()`), measured by the test three times on the 1316-cycle T1 `uart` bundle, headless
Chromium 1243 with **swiftshader, a CPU renderer**, on the shared course host:

| What | Measured | Budget (the test fails above it) |
|------|---------:|---------------------------------:|
| mount to first frame, first load (modules not cached) | 600–760 ms | 2,500 ms |
| mount to first frame, modules cached | 324–480 ms | 2,500 ms |

A laptop GPU renders faster than swiftshader; the budget is set for swiftshader so that the test
holds on a machine without a GPU.
