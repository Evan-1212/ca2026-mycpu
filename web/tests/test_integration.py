# SPDX-License-Identifier: MIT
"""Browser tests of the waveform view (web/waveform/) and the virtual board
(web/board/) under the site's own host (web/app.js and web/lib/bundle.js),
not the test host of web/waveform/tests/. Run with

    uv run --no-project --with playwright==1.63.0 python -I web/tests/test_integration.py --bundles DIR

(`make -C web check` runs it after `make web-bundles`). DIR holds the bundles
of `make web-bundles`; the tests copy t1-uart-repeat, and two copies of it with a VGA
frame dump, into web/examples/tmp-integration/ (ignored by git) so that the
site opens them with `?bundle=`, and remove that directory when they end.
Every expected value is read here from the bundle's files and the run's
retire log. One browser runs at a time: first one without WebGL, then one
with WebGL through swiftshader.

This file defines no unittest case, so the unittest discovery of
`make -C web check-map` imports it and finds nothing to run; Playwright is
imported in main() only.
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import traceback

WEB = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(WEB)
TMP_DIR = "tmp-integration"  # under web/examples/, matched by web/.gitignore
TMP_URL = f"examples/{TMP_DIR}"
UART = "t1-uart-repeat"  # its input, "hello, world\n", repeats bytes
VGA_FRAME = 1
# a 4 x 2 binary PPM: red, green, blue, white / black, gray, yellow, cyan
PPM_PIXELS = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 255),
              (0, 0, 0), (128, 128, 128), (255, 255, 0), (0, 255, 255)]
PPM = b"P6\n4 2\n255\n" + bytes(c for p in PPM_PIXELS for c in p)

# Every `link` handler of the page is counted, and one more handler records every link: the
# test's own handler on the site's bus, next to the site's and the views'.
LINK_SPY = """
window.__links = [];
window.__linkHandlers = 0;
(() => {
  const add = EventTarget.prototype.addEventListener;
  const remove = EventTarget.prototype.removeEventListener;
  const spied = new WeakSet();
  const handlers = new WeakMap(); // target -> the link handlers added to it and not removed
  EventTarget.prototype.addEventListener = function (type, fn, opts) {
    if (type === 'link') {
      if (!spied.has(this)) {
        spied.add(this);
        add.call(this, 'link', (e) => window.__links.push(JSON.parse(JSON.stringify(e.detail))));
      }
      if (!handlers.has(this)) handlers.set(this, new Set());
      if (!handlers.get(this).has(fn)) {
        handlers.get(this).add(fn);
        window.__linkHandlers++;
      }
    }
    return add.call(this, type, fn, opts);
  };
  EventTarget.prototype.removeEventListener = function (type, fn, opts) {
    if (type === 'link' && handlers.get(this)?.delete(fn)) window.__linkHandlers--;
    return remove.call(this, type, fn, opts);
  };
})();
"""


class Failure(Exception):
    pass


def check(condition, message):
    if not condition:
        raise Failure(message)


def equal(got, want, what):
    if got != want:
        raise Failure(f"{what}: got {got!r}, want {want!r}")


# ---------------------------------------------------------------------------
# The bundles


def make_bundles(bundles):
    """web/examples/tmp-integration/: the UART bundle, t1-uart-vga (with frame 1) and t1-uart-badvga (a frame
    whose bytes are not the manifest's)."""
    out = os.path.join(WEB, "examples", TMP_DIR)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    src = os.path.join(bundles, UART)
    shutil.copytree(src, os.path.join(out, UART))
    for name, data in (("t1-uart-vga", PPM), ("t1-uart-badvga", PPM[:-1] + b"\x01")):
        dst = os.path.join(out, name)
        shutil.copytree(src, dst)
        os.makedirs(os.path.join(dst, "vga"))
        with open(os.path.join(dst, "vga", f"frame-{VGA_FRAME}.ppm"), "wb") as f:
            f.write(data)
        with open(os.path.join(dst, "manifest.json")) as f:
            manifest = json.load(f)
        manifest["vga"] = [{"file": f"vga/frame-{VGA_FRAME}.ppm", "bytes": len(PPM),
                            "sha256": hashlib.sha256(PPM).hexdigest(), "frame": VGA_FRAME}]
        with open(os.path.join(dst, "manifest.json"), "w") as f:
            json.dump(manifest, f)
    return out


class Bundles:
    """What the tests expect, from the bundle directory of `make web-bundles`."""

    def __init__(self, directory):
        from test_waveform import log_tx_stores, py_frames, uart_log
        from runner import bundle_records
        self.bundles = directory  # the attribute that the helpers of test_waveform.py read
        self.manifest, self.records = bundle_records(os.path.join(directory, UART))
        self.uart = uart_log(self, UART)
        self.frames = py_frames(self.records, self.manifest["header"]["uart_divider"])
        check(bytes(f[2] for f in self.frames) == self.uart, "the independent decoder disagrees with uart.bin")
        self.stores = log_tx_stores(self, UART)
        check(len(self.stores) >= len(self.frames), "fewer TXDATA stores than frames")

    def terminal(self, cycle):
        """The bytes of the frames whose stop bit was sampled by cycle."""
        return self.uart[:sum(1 for f in self.frames if f[1] <= cycle)]


# ---------------------------------------------------------------------------
# The browser


class Suite:
    def __init__(self, playwright, base, bundles):
        self.playwright = playwright
        self.base = base
        self.expect = bundles
        self.browser = None
        self.results = []

    def context(self):
        context = self.browser.new_context(viewport={"width": 1400, "height": 900})
        self.contexts.append(context)
        return context

    def page(self, query="", hash_="", map_route=None, init_script=None):
        context = self.context()
        context.add_init_script(LINK_SPY)
        if init_script:
            context.add_init_script(init_script)
        page = context.new_page()
        page.errors = []
        page.on("pageerror", lambda e: page.errors.append(str(e)))
        page.on("console", lambda m: page.errors.append(m.text) if m.type == "error" else None)
        if map_route:
            page.route("**/web/map.json", map_route)
        page.goto(f"{self.base}/web/{query}{hash_}")
        page.wait_for_selector("html[data-ready='1']", timeout=60000)
        return page

    def run(self, launch, args, tests):
        self.browser = launch(self.playwright, args)
        try:
            for test in tests:
                self.contexts = []
                t0 = time.perf_counter()
                try:
                    test(self)
                    status, detail = "pass", ""
                except Exception as e:  # a failed test is reported and the others still run
                    status = "FAIL"
                    detail = "".join(traceback.format_exception_only(type(e), e)).strip()
                    where = [f for f in traceback.extract_tb(e.__traceback__) if "site-packages" not in f.filename]
                    if where:
                        detail += f"\n       at {os.path.basename(where[-1].filename)}:{where[-1].lineno}: {where[-1].line}"
                finally:
                    for c in self.contexts:
                        c.close()
                ms = (time.perf_counter() - t0) * 1000
                self.results.append((test.__name__, status))
                print(f"  {status:4} {test.__name__} ({ms:.0f} ms)" + (f"\n       {detail}" if detail else ""), flush=True)
        finally:
            self.browser.close()
            self.browser = None


# the waveform's saved rows for T1, with uart.txd second: only the probe link puts it first
SAVED_ROWS = "localStorage.setItem('mycpu-obs.waveform.fields.T1', JSON.stringify(['core.pc', 'uart.txd']));"


def open_view(s, name, bundle=UART, ready=True, init_script=None):
    page = s.page(f"?bundle={TMP_URL}/{bundle}", f"#/view/{name}", init_script=init_script)
    if ready:
        root = ".wf" if name == "waveform" else ".bd"
        page.wait_for_selector(f"{root}[data-state=ready], {root}[data-state=error]", timeout=60000)
        equal(page.get_attribute(root, "data-state"), "ready", f"the {name} view under the site")
    return page


def set_site_cursor(page, cycle):
    """Moves the shared cursor through the site's cursor bar, as a student types a cycle."""
    page.fill("#cycle-input", str(cycle))
    page.dispatch_event("#cycle-input", "change")


def first_row(page):
    return page.eval_on_selector(".wf-names .wf-name:nth-child(2)", "e => e.dataset.row")


def check_probe_landed(page, how):
    """After a probe on the UART TX pin: the waveform view is mounted and uart.txd is its first row."""
    page.wait_for_selector(".wf[data-state=ready]", timeout=60000)
    equal(page.evaluate("location.hash"), "#/view/waveform", f"{how}: the hash")
    equal(page.get_attribute("#menu a[aria-current='page']", "data-view"), "waveform", f"{how}: the menu")
    check(page.query_selector(".bd") is None, f"{how}: the board is still on the page")
    # the probe link is handled once the window has loaded; a link sent before the mount began is lost
    page.wait_for_selector(".wf-status[data-probe='uart.txd']", timeout=5000)
    equal(first_row(page), "uart.txd", f"{how}: the first row of the waveform")
    check({"kind": "probe", "payload": {"signal": "uart.txd", "from": "board"}} in page.evaluate("window.__links"),
          f"{how}: no probe link")
    # the site's handler and the waveform's: the board's handler is gone
    equal(page.evaluate("window.__linkHandlers"), 2, f"{how}: link handlers after the board unmounted")
    check(not page.errors, f"{how}: console errors {page.errors}")


# ---------------------------------------------------------------------------
# The map and the menu


def test_menu_lists_the_views(s):
    with open(os.path.join(WEB, "map.json")) as f:
        views = [v["name"] for v in json.load(f)["views"]]
    for name in ("waveform", "board"):
        check(name in views, f"map.json lists no {name} view: {views}")
    page = s.page(f"?bundle={TMP_URL}/{UART}")
    menu = page.eval_on_selector_all("#menu a", "els => els.map(e => e.dataset.view)")
    equal(menu, ["home"] + views, "the menu")
    for name in ("waveform", "board"):
        check(page.get_attribute(f"#menu a[data-view='{name}']", "class") == "", f"{name} is not served for T1")


def test_a_view_whose_directory_is_absent_is_not_listed(s):
    # the map of a copy of web/ without board/: no board in the map, the menu or the router
    with tempfile.TemporaryDirectory(prefix="web-map-") as tmp:
        web = os.path.join(tmp, "web")
        shutil.copytree(WEB, web, ignore=shutil.ignore_patterns("board", "vendor", TMP_DIR, "map.json*"))
        public = ROOT if os.path.isfile(os.path.join(ROOT, "EXERCISES.md")) else os.path.join(ROOT, "build", "public")
        out = os.path.join(tmp, "map.json")
        subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "web-map.py"), "--root", public, "--web", web,
                        "--out", out, "--bootcamp", os.path.join(ROOT, "externals", "chisel-bootcamp")],
                       check=True, stdout=subprocess.DEVNULL)
        with open(out) as f:
            fixture = json.load(f)
    names = [v["name"] for v in fixture["views"]]
    check("board" not in names and "waveform" in names, f"the views of a tree without board/: {names}")
    page = s.page(f"?bundle={TMP_URL}/{UART}", "#/view/board",
                  map_route=lambda route: route.fulfill(json=fixture))
    menu = page.eval_on_selector_all("#menu a", "els => els.map(e => e.dataset.view)")
    equal(menu, ["home"] + names, "the menu of a tree without board/")
    check(page.query_selector("#view-board") is None and page.query_selector("article.unit") is not None,
          "#/view/board of a tree without board/ is not the course map")
    check(not page.errors, f"console errors {page.errors}")


# ---------------------------------------------------------------------------
# (a) the waveform under the site


def test_waveform_uart_and_trace_back(s):
    e = s.expect
    page = open_view(s, "waveform")
    equal(page.get_attribute(".wf-uart-text", "data-bytes"), e.uart.hex(), "the UART text of the waveform")
    for k in (0, 2, len(e.frames) - 1):
        page.evaluate("window.__links.length = 0")
        page.click(f".wf-char[data-frame='{k}']")
        cycle, pc, inst, data = e.stores[k]
        page.wait_for_function(f"document.getElementById('cycle-input').value === '{cycle}'")
        equal(page.get_attribute(".wf-tb", "data-store-cycle"), str(cycle), f"character {k}: the store")
        equal(page.get_attribute(".wf-cursors", "data-a"), str(cycle), f"character {k}: the waveform's cursor A")
        check(f"retires pc={pc:08x}" in page.inner_text("#cursor-info"), f"character {k}: the site's cursor info")
        links = page.evaluate("window.__links")
        kinds = [(m["kind"], m["payload"].get("from")) for m in links]
        equal(kinds, [("uart-char", "waveform"), ("store", "waveform"), ("instruction", "waveform")],
              f"character {k}: the links on the site's bus")
        equal(links[0]["payload"], {"cycle": e.frames[k][0], "char": e.frames[k][2], "from": "waveform"}, "uart-char")
        equal(links[1]["payload"], {"cycle": cycle, "addr": 0x10000000, "value": data, "from": "waveform"}, "store")
        equal(links[2]["payload"], {"cycle": cycle, "pc": pc, "inst": inst, "from": "waveform"}, "instruction")
    equal(page.evaluate("window.__linkHandlers"), 2, "link handlers: the site's and the waveform's")
    check(not page.errors, f"console errors {page.errors}")


def test_waveform_arrow_keys_step_once(s):
    # the canvas and the site's cursor bar both take the arrow keys: one press is one cycle
    page = open_view(s, "waveform")
    set_site_cursor(page, 100)
    page.wait_for_function("document.querySelector('.wf-cursors').dataset.a === '100'")
    page.focus(".wf-canvas")
    page.keyboard.press("ArrowRight")
    page.wait_for_timeout(100)
    equal(page.input_value("#cycle-input"), "101", "the cursor after ArrowRight on the canvas")
    page.keyboard.press("ArrowLeft")
    page.wait_for_timeout(100)
    equal(page.input_value("#cycle-input"), "100", "the cursor after ArrowLeft on the canvas")
    page.focus("#menu a[data-view='home']")
    page.keyboard.press("ArrowRight")
    page.wait_for_timeout(100)
    equal(page.input_value("#cycle-input"), "101", "the cursor after ArrowRight elsewhere on the page")


# ---------------------------------------------------------------------------
# (b), (c), (d) the board under the site, without WebGL


def test_board_terminal_follows_the_site_cursor(s):
    e = s.expect
    page = open_view(s, "board")
    equal(page.get_attribute(".bd", "data-mode"), "2d", "the board without WebGL")
    check(page.query_selector(".bd-stage svg.bd-diagram") is not None, "no 2D block diagram")
    for cycle in (e.frames[0][1] - 1, e.frames[3][1], e.records[-1]["cycle"]):
        set_site_cursor(page, cycle)
        page.wait_for_function(f"document.querySelector('.bd-term-text').dataset.cycle === '{cycle}'")
        equal(page.get_attribute(".bd-term-text", "data-bytes") or "", e.terminal(cycle).hex(), f"terminal at {cycle}")
    check(not page.errors, f"console errors {page.errors}")


def test_board_probe_list_opens_the_waveform(s):
    page = open_view(s, "board", init_script=SAVED_ROWS)
    page.click(".bd-net[data-net='uart-tx']")
    check_probe_landed(page, "the probe list")


def test_board_character_opens_the_waveform(s):
    e = s.expect
    page = open_view(s, "board")
    set_site_cursor(page, e.records[-1]["cycle"])
    page.wait_for_selector(".bd-term-text .bd-ch")
    page.click(".bd-ch:nth-child(3)")
    page.wait_for_selector(".wf-tb[data-store-cycle]", timeout=60000)
    equal(page.get_attribute(".wf-tb", "data-store-cycle"), str(e.stores[2][0]), "the store of the third character")
    equal(page.input_value("#cycle-input"), str(e.stores[2][0]), "the site's cursor")
    check(not page.errors, f"console errors {page.errors}")


def test_board_vga_screen_through_api_file(s):
    page = open_view(s, "board", bundle="t1-uart-vga")
    page.click(".bd-item[data-id='vga']")
    page.wait_for_selector(f".bd-panel[data-panel='vga'] figcaption[data-frame='{VGA_FRAME}']")
    pixels = page.eval_on_selector(".bd-panel[data-panel='vga'] canvas", """c => {
        const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
        return {w: c.width, h: c.height, rgb: Array.from({length: c.width * c.height}, (_, i) => [d[4*i], d[4*i+1], d[4*i+2]])};
    }""")
    equal((pixels["w"], pixels["h"]), (4, 2), "the size of the VGA screen")
    equal([tuple(p) for p in pixels["rgb"]], PPM_PIXELS, "the pixels of the VGA screen")
    check(not page.errors, f"console errors {page.errors}")
    # a frame whose bytes are not the manifest's is refused, and the panel says so
    page = open_view(s, "board", bundle="t1-uart-badvga")
    page.click(".bd-item[data-id='vga']")
    page.wait_for_selector(".bd-panel[data-panel='vga'] figcaption[data-frame='']")
    text = page.inner_text(".bd-panel[data-panel='vga']")
    check(f"VGA frame {VGA_FRAME} could not be read" in text and "SHA-256" in text, f"the refused frame: {text!r}")


def test_api_file_reads_only_listed_files(s):
    page = s.page()
    r = page.evaluate("""async (path) => {
        const { openBundle, urlSource, BundleError } = await import('/web/lib/bundle.js');
        const api = await openBundle(urlSource(path));
        const refusal = async (p) => {
            try { await api.file(p); return 'read'; } catch (e) { return e instanceof BundleError ? e.message : 'not a BundleError: ' + e; }
        };
        const vga = await api.file(api.manifest.vga[0].file);
        const uart = await api.file(api.manifest.uart.file);
        return {
            vga: Array.from(vga),
            same: Array.from(await api.vgaFrame(api.manifest.vga[0].frame)).join() === Array.from(vga).join(),
            // bytes that a view changes are its own copy
            own: (vga.fill(0), Array.from(await api.file(api.manifest.vga[0].file))),
            uart: Array.from(uart),
            refused: await Promise.all(['manifest.json', api.manifest.chunks[0].file, '../map.json', 'vga/../manifest.json',
                '/web/map.json', 'http://127.0.0.1/x', '', null, 7, 1n, {}].map(refusal)),
            navigate: (await api.navigate('waveform')) === undefined,
            // a view that changes api.manifest does not change what api.file may read
            changed: await (async () => {
                const uartFile = api.manifest.uart.file;
                Object.assign(api.manifest.uart, { file: 'manifest.json' });
                api.manifest.vga.push({ file: api.manifest.chunks[0].file, frame: 9 });
                const out = [await refusal('manifest.json'), await refusal(api.manifest.chunks[0].file),
                             Array.from(await api.file(uartFile)), await api.vgaFrame(9)];
                return out;
            })(),
            // the UART log and a VGA frame that name one file: each is checked against its own entry
            shared: await (async () => {
                const base = urlSource(path);
                const doctored = { ...base, async read(name) {
                    const bytes = await base.read(name);
                    if (name !== 'manifest.json') return bytes;
                    const m = JSON.parse(new TextDecoder().decode(bytes));
                    m.vga = [{ ...m.uart, frame: 1, sha256: '0'.repeat(64) }];
                    return new TextEncoder().encode(JSON.stringify(m));
                } };
                const two = await openBundle(doctored);
                const results = [];
                for (const read of [() => two.uart(), () => two.vgaFrame(1), () => two.file(two.manifest.uart.file)]) {
                    try { results.push((await read()).length); } catch (e) { results.push(e instanceof BundleError ? 'refused' : String(e)); }
                }
                return results;
            })(),
            // a read that failed is not kept: the next read of that file reads it again
            retried: await (async () => {
                const base = urlSource(path);
                let reads = 0;
                const flaky = { ...base, async read(name) {
                    const bytes = await base.read(name);
                    if (name !== 'vga/frame-1.ppm' || reads++ > 0) return bytes;
                    return bytes.slice(1);
                } };
                const three = await openBundle(flaky);
                const first = await three.file('vga/frame-1.ppm').then(() => 'read', (e) => e instanceof BundleError ? 'refused' : String(e));
                const second = await three.file('vga/frame-1.ppm').then((b) => b.length, (e) => String(e));
                return [first, second, reads];
            })(),
        };
    }""", f"{TMP_URL}/t1-uart-vga")
    equal(bytes(r["vga"]), PPM, "api.file of the VGA frame")
    check(r["same"], "api.file and api.vgaFrame give different bytes for one frame")
    equal(bytes(r["own"]), PPM, "api.file after a view changed the bytes it was given")
    equal(r["retried"], ["refused", len(PPM), 2], "a read after a failed one")
    equal(bytes(r["uart"]), s.expect.uart, "api.file of the UART log")
    for message in r["refused"]:
        check(message.endswith("is not a file the manifest lists (its UART log, a VGA frame or its stores)"), message)
    check(r["navigate"], "api.navigate of a bundle opened without a site does not resolve to undefined")
    check(r["changed"][0].endswith("is not a file the manifest lists (its UART log, a VGA frame or its stores)"), r["changed"][0])
    check(r["changed"][1].endswith("is not a file the manifest lists (its UART log, a VGA frame or its stores)"), r["changed"][1])
    equal(bytes(r["changed"][2]), s.expect.uart, "the UART log after a view changed api.manifest")
    equal(r["changed"][3], None, "a VGA frame that a view added to api.manifest")
    n = len(s.expect.uart)
    equal(r["shared"], [n, "refused", n], "a VGA entry with the UART log's file and another hash")


# ---------------------------------------------------------------------------
# (e) navigate: the wait, unit by unit, in the page


def test_route_tracker(s):
    # a page of the site's origin without the site's router, so that only the test's router routes
    page = s.context().new_page()
    page.goto(f"{s.base}/web/schema/obs-v1.json")
    r = page.evaluate("""async () => {
        const { RouteTracker } = await import('/web/lib/router.js');
        const out = {};
        const deferred = () => { let resolve, reject; const p = new Promise((a, b) => { resolve = a; reject = b; }); return { p, resolve, reject }; };
        const tick = () => new Promise((r) => setTimeout(r, 50));
        const done = (p) => { let v = false; p.then(() => { v = true; }); return () => v; };
        // a fresh page whose own router is told nothing: a router of the test, on the same location
        const t = new RouteTracker();
        let mounts = [];
        const route = (token) => { const d = deferred(); mounts.push({ hash: location.hash, token, d }); return d.p; };
        const onHash = (e) => { t.hashChanged(e.newURL); t.run(route).catch(() => {}); };
        window.addEventListener('hashchange', onHash);
        const navigate = (h) => { t.setHash(h); return t.settled(); };

        // settled at once when nothing runs
        out.idle = await Promise.race([t.settled().then(() => 'resolved'), tick().then(() => 'waited')]);

        // a new hash: not settled before its route has finished
        let d = done(navigate('#/view/a'));
        await tick();
        out.newBeforeMount = [d(), mounts.length, mounts[0]?.hash];
        mounts[0].d.resolve();
        await tick();
        out.newAfterMount = d();

        // the view already shown: at once, no route runs
        mounts = [];
        d = done(navigate('#/view/a'));
        await tick();
        out.same = [d(), mounts.length];

        // superseded: a finishes after b started, the wait ends with b only
        mounts = [];
        d = done(navigate('#/view/b'));
        await tick();
        const d2 = done(navigate('#/view/c'));
        await tick();
        mounts[0].d.resolve();
        await tick();
        out.superseded = [mounts.map((m) => m.hash), d(), d2()];
        mounts[1].d.resolve();
        await tick();
        out.supersededEnd = [d(), d2()];

        // the same hash while its mount is still running: no hashchange, the wait ends with that mount
        mounts = [];
        d = done(navigate('#/view/e'));
        await tick();
        const again = t.setHash('#/view/e');
        const d3 = done(t.settled());
        await tick();
        out.sameWhileMounting = [again, d(), d3(), mounts.length];
        mounts[0].d.resolve();
        await tick();
        out.sameWhileMountingEnd = [d(), d3()];

        // a mount that fails: settled all the same
        mounts = [];
        d = done(navigate('#/view/f'));
        await tick();
        mounts[0].d.reject(new Error('mount failed'));
        await tick();
        out.failed = d();

        // a route that renders at once (an unknown or unserved view): settled after it
        window.removeEventListener('hashchange', onHash);
        let routes = 0;
        const sync = (e) => { t.hashChanged(e.newURL); routes++; t.run(() => {}); };
        window.addEventListener('hashchange', sync);
        out.unknown = await Promise.race([navigate('#/view/nope').then(() => 'resolved'), tick().then(tick).then(() => 'hung')]);

        // two changes in one task, back to where it was: two hashchange events, no hang
        const h0 = location.hash;
        const p = (t.setHash('#/view/x'), t.setHash(h0), t.settled());
        out.backAndForth = await Promise.race([p.then(() => 'resolved'), tick().then(tick).then(() => 'hung')]);

        // a hash change of the page's own (a menu click), then a navigate in the same task: the
        // route of the menu's hashchange finishes first, and the navigate waits for its own route
        await tick();
        routes = 0;
        location.hash = '#/view/menu';
        const atResolve = navigate('#/view/target').then(() => routes);
        out.afterOther = await Promise.race([atResolve, tick().then(tick).then(() => 'hung')]);
        window.removeEventListener('hashchange', sync);
        return out;
    }""")
    equal(r["idle"], "resolved", "idle")
    equal(r["newBeforeMount"], [False, 1, "#/view/a"], "a new hash before its mount")
    equal(r["newAfterMount"], True, "a new hash after its mount")
    equal(r["same"], [True, 0], "the view already shown")
    equal(r["superseded"], [["#/view/b", "#/view/c"], False, False], "superseded, the older mount done")
    equal(r["supersededEnd"], [True, True], "superseded, the newer mount done")
    equal(r["sameWhileMounting"], [False, False, False, 1], "the same hash while mounting")
    equal(r["sameWhileMountingEnd"], [True, True], "the same hash, the mount done")
    equal(r["failed"], True, "a failed mount")
    equal(r["unknown"], "resolved", "a route that renders at once")
    equal(r["backAndForth"], "resolved", "two hash changes in one task")
    equal(r["afterOther"], 2, "routes run when a navigate after a menu click resolves")


def test_navigate_through_the_menu_and_back(s):
    # menu clicks change the hash without navigate(); a probe afterwards still waits for its mount
    page = open_view(s, "board", init_script=SAVED_ROWS)
    page.click("#menu a[data-view='retire']")
    page.wait_for_selector("#retire tbody tr")
    page.click("#menu a[data-view='board']")
    page.wait_for_selector(".bd[data-state=ready]", timeout=60000)
    page.click(".bd-net[data-net='uart-tx']")
    check_probe_landed(page, "a probe after menu clicks")


# ---------------------------------------------------------------------------
# with WebGL


def test_board_3d_probe_drag_opens_the_waveform(s):
    from test_board import drag, hotspot
    page = open_view(s, "board", init_script=SAVED_ROWS)
    equal(page.get_attribute(".bd", "data-mode"), "3d", "the board with WebGL")
    drag(page, hotspot(page, "probe", "P1"), hotspot(page, "net", "uart-tx"))
    check_probe_landed(page, "the 3D probe")


NO_WEBGL = [test_menu_lists_the_views, test_a_view_whose_directory_is_absent_is_not_listed,
            test_waveform_uart_and_trace_back, test_waveform_arrow_keys_step_once,
            test_board_terminal_follows_the_site_cursor, test_board_probe_list_opens_the_waveform,
            test_board_character_opens_the_waveform, test_board_vga_screen_through_api_file,
            test_api_file_reads_only_listed_files, test_route_tracker, test_navigate_through_the_menu_and_back]
WEBGL = [test_board_3d_probe_drag_opens_the_waveform]


def main():
    p = argparse.ArgumentParser(description="browser tests of the waveform and board views under the site")
    p.add_argument("--bundles", default=os.path.join(ROOT, "build", "web", "bundles"),
                   help="the directory of `make web-bundles`")
    p.add_argument("-k", default="", help="run only the tests whose name contains this text")
    args = p.parse_args()
    if not os.path.isdir(os.path.join(args.bundles, UART)):
        print(f"{args.bundles}: no {UART} bundle (make web-bundles)", file=sys.stderr)
        return 2
    if not os.path.isfile(os.path.join(WEB, "map.json")):
        print("web/map.json not found: run make -C web map", file=sys.stderr)
        return 2
    for d in (os.path.join(WEB, "waveform", "tests"), os.path.join(WEB, "board", "tests"), os.path.join(WEB, "tests")):
        sys.path.insert(0, d)
    from playwright.sync_api import sync_playwright
    from runner import NO_WEBGL_ARGS, WEBGL_ARGS, launch
    from browser import serve

    expect = Bundles(os.path.abspath(args.bundles))
    make_bundles(os.path.abspath(args.bundles))
    server = serve()
    try:
        with sync_playwright() as pw:
            suite = Suite(pw, f"http://127.0.0.1:{server.server_address[1]}", expect)
            for browser_args, tests in ((NO_WEBGL_ARGS, NO_WEBGL), (WEBGL_ARGS, WEBGL)):
                chosen = [t for t in tests if args.k in t.__name__]
                if chosen:
                    suite.run(launch, browser_args, chosen)
    finally:
        server.shutdown()
        server.server_close()
        shutil.rmtree(os.path.join(WEB, "examples", TMP_DIR), ignore_errors=True)
    failed = [n for n, status in suite.results if status != "pass"]
    print(f"integration: {len(suite.results) - len(failed)} of {len(suite.results)} tests passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
