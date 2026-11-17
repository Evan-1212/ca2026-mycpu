# SPDX-License-Identifier: MIT
"""Browser tests of the waveform view, on the bundles of
`make web-bundles`. Run with
    uv run --no-project --with playwright==1.63.0 python -I web/waveform/tests/test_waveform.py

Every expected value comes from the bundle's files or the run's retire log,
read here in Python, never from the page.
"""

import hashlib
import json
import os
import re
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import runner  # noqa: E402
from runner import NO_WEBGL_ARGS, ROOT, bundle_records  # noqa: E402

sys.path.insert(0, os.path.join(ROOT, "scripts"))
from obslib import read_retire_log  # noqa: E402

TXDATA = 0x10000000
# recorded in web/waveform/README.md ("Budgets"); measured on the shared host of the course
BUDGET_LOAD_MS = 3000     # api.window over 10,000 cycles: 10 chunk fetches and JSON parses
# one full redraw of the 10,000-cycle window or of a zoom of it: the median, and the worst of 35, which
# other processes on a shared host can delay (100 ms is the limit for a response that feels immediate)
BUDGET_RENDER_MEDIAN_MS = 40
BUDGET_RENDER_MS = 100
BUDGET_TRIGGER_MS = 50    # a trigger search over the window, until the cursor has moved

_FIELDS = re.compile(r"pc=([0-9a-f]{8}) inst=([0-9a-f]{8}) .* mem=(?:w:([0-9a-f]{8}):4:([0-9a-f]{8})|\S+) ")


def bundle_dir(s, name):
    return os.path.join(s.bundles, name)


def uart_log(s, name):
    with open(os.path.join(bundle_dir(s, name), "uart.bin"), "rb") as f:
        return f.read()


def log_tx_stores(s, name):
    """The full-word stores to TXDATA of the whole run, from its retire log: (cycle, pc, inst, data)."""
    _, end, _ = read_retire_log(bundle_dir(s, name) + ".log", 0, 0)
    records, _, _ = read_retire_log(bundle_dir(s, name) + ".log", 0, end["cycles"])
    out = []
    for cycle, line in sorted(records.items()):
        m = _FIELDS.match(line)
        if m.group(3) and int(m.group(3), 16) == TXDATA:
            out.append((cycle, int(m.group(1), 16), int(m.group(2), 16), int(m.group(4), 16)))
    return out


def py_frames(records, divider):
    """An independent decoder of uart.txd for a window from cycle 0: (start, stop sample, byte)."""
    txd = [r["uart"]["txd"] for r in records]
    half = divider // 2
    i = 0
    out = []
    while i < len(txd):
        if txd[i]:
            i += 1
            continue
        stop = i + half + 9 * divider
        if stop >= len(txd):
            break
        if txd[i + half]:
            i += half
            continue
        byte = sum(txd[i + half + (k + 1) * divider] << k for k in range(8))
        out.append((i, stop, byte))
        i = stop + 1
    return out


def open_view(s, url, **kw):
    page = s.page(url, **kw)
    page.wait_for_selector(".wf[data-state=ready], .wf[data-state=error]", timeout=60000)
    assert page.get_attribute(".wf", "data-state") == "ready", page.inner_text(".wf-status")
    return page


def decoded_hex(page):
    return page.get_attribute(".wf-uart-text", "data-bytes")


def check_decode(s, url, expected):
    page = open_view(s, url)
    got = decoded_hex(page)
    assert got == expected.hex(), f"{url}: decoded {got!r}, the UART log has {expected.hex()!r}"
    assert not page.errors, page.errors
    return page


def test_uart_decode_equals_uart_log(s):
    for name in ("t1-uart", "t2-uart", "t1-uart-repeat"):
        log = uart_log(s, name)
        assert log, f"{name}: empty UART log"
        page = check_decode(s, f"/bundles/{name}/", log)
        manifest, _ = bundle_records(bundle_dir(s, name))
        assert page.get_attribute(".wf-divider", "data-divider") == str(manifest["header"]["uart_divider"])


def test_doctored_txd_fails_the_decode_check(s):
    """The check of the test above fails when one data bit of uart.txd is flipped."""
    src = bundle_dir(s, "t1-uart-repeat")
    dst = os.path.join(s.server.extra, "doctored")
    shutil.copytree(src, dst)
    manifest, records = bundle_records(src)
    d = manifest["header"]["uart_divider"]
    start = py_frames(records, d)[1][0]          # the frame of "e" (0x65, bit 0 is 1)
    target = start + d // 2 + d                  # the sample of data bit 0
    lo, hi = target - d // 2, target - d // 2 + d  # the whole bit, so that the edge moves with it
    manifest = json.loads(json.dumps(manifest))  # bundle_records caches its manifest: change a copy
    for c in manifest["chunks"]:
        path = os.path.join(dst, c["file"])
        with open(path) as f:
            chunk = json.load(f)
        for r in chunk["cycles"]:
            if lo <= r["cycle"] < hi:
                r["uart"]["txd"] = 0
        data = json.dumps(chunk).encode()
        with open(path, "wb") as f:
            f.write(data)
        # the host checks every chunk against the manifest, as the site does
        c["bytes"], c["sha256"] = len(data), hashlib.sha256(data).hexdigest()
    with open(os.path.join(dst, "manifest.json"), "w") as f:
        json.dump(manifest, f)
    try:
        check_decode(s, "/extra/doctored/", uart_log(s, "t1-uart-repeat"))
    except AssertionError as e:
        assert "decoded" in str(e), e
    else:
        raise AssertionError("a doctored uart.txd decoded to the UART log")
    page = open_view(s, "/extra/doctored/")
    assert bytes.fromhex(decoded_hex(page))[:2] == b"hd", decoded_hex(page)


def trace_back(page, index):
    """Clicks decoded character `index`; returns the store cycle shown and the store's table."""
    char = f".wf-char[data-frame='{index}']"
    start = page.get_attribute(char, "data-start")
    page.click(char)
    page.wait_for_selector(f".wf-tb[data-frame='{start}']")
    tb = page.query_selector(".wf-tb")
    cell = lambda k: tb.query_selector(f"td[data-key='{k}']")  # noqa: E731
    return tb.get_attribute("data-store-cycle"), {k: (cell(k).inner_text() if cell(k) else None)
                                                   for k in ("pc", "inst", "address", "data")}


def test_traceback_lands_on_the_store_of_each_character(s):
    name = "t1-uart-repeat"
    log = uart_log(s, name)
    stores = log_tx_stores(s, name)
    assert len(stores) == len(log), (len(stores), len(log))
    page = open_view(s, f"/bundles/{name}/")
    # the k-th byte of the UART log was written by the k-th full-word store to TXDATA (FIFO order)
    for k, (cycle, pc, inst, data) in enumerate(stores):
        assert data & 0xFF == log[k]
        page.evaluate("window.testHost.links.length = 0")
        got_cycle, cells = trace_back(page, k)
        assert got_cycle == str(cycle), (k, chr(log[k]), got_cycle, cycle)
        assert cells == {"pc": f"0x{pc:08x}", "inst": f"0x{inst:08x}", "address": f"0x{TXDATA:08x}",
                         "data": f"0x{data:08x}"}, (k, cells)
        assert page.evaluate("window.testHost.cursor.get()") == cycle
        links = page.evaluate("window.testHost.links")
        assert [m["kind"] for m in links] == ["uart-char", "store", "instruction"], links
        assert links[0]["payload"]["char"] == log[k]
        assert links[1]["payload"] == {"cycle": cycle, "addr": TXDATA, "value": data, "from": "waveform"}
        assert links[2]["payload"]["pc"] == pc and links[2]["payload"]["cycle"] == cycle
    # the repeated "l": the nearest earlier store of the same byte is not the right one for the first
    manifest, records = bundle_records(bundle_dir(s, name))
    frames = py_frames(records, manifest["header"]["uart_divider"])
    first_l = log.index(b"l")
    nearest = max(c for c, _, _, d in stores if d & 0xFF == ord("l") and c < frames[first_l][0])
    assert nearest != stores[first_l][0], "the input does not tell FIFO order from the nearest store"


def test_store_to_frame(s):
    name = "t1-uart-repeat"
    page = open_view(s, f"/bundles/{name}/")
    manifest, records = bundle_records(bundle_dir(s, name))
    frames = py_frames(records, manifest["header"]["uart_divider"])
    for k in range(len(frames)):
        page.click(f".wf-store[data-store='{k}']")
        page.wait_for_selector(f".wf-tb[data-frame='{frames[k][0]}']")
        assert page.evaluate("window.testHost.cursor.get()") == frames[k][0]


def test_late_window_traceback(s):
    """A window from cycle 1000: frames found by the search, stores before the window reported."""
    full = "t1-uart-repeat"
    name = "t1-uart-repeat-late"
    manifest, records = bundle_records(bundle_dir(s, full))
    late, late_records = bundle_records(bundle_dir(s, name))
    first, end = late["run"]["first"], late["run"]["first"] + late["run"]["count"]
    assert first > 0
    frames = [f for f in py_frames(records, manifest["header"]["uart_divider"]) if f[0] >= first and f[1] < end]
    stores = log_tx_stores(s, full)
    page = open_view(s, f"/bundles/{name}/")
    assert decoded_hex(page) == bytes(f[2] for f in frames).hex()
    all_frames = py_frames(records, manifest["header"]["uart_divider"])
    for k, f in enumerate(frames):
        cycle = stores[all_frames.index(f)][0]
        got, _ = trace_back(page, k)
        if cycle < first:
            assert got is None and "before the first cycle" in page.inner_text(".wf-tb"), (k, got)
        else:
            assert got == str(cycle), (k, got, cycle)


def test_values_at_the_cursor(s):
    name = "t2-muldiv"
    manifest, records = bundle_records(bundle_dir(s, name))
    page = open_view(s, f"/bundles/{name}/")
    for cycle in (0, 1, 4321, 9999):
        page.evaluate(f"window.testHost.cursor.set({cycle})")
        values = page.eval_on_selector_all(".wf-value", "es => es.map(e => [e.dataset.field, e.textContent])")
        assert len(values) >= 8, values
        for field, text in values:
            group, key = field.split(".")
            assert int(text, 16) == records[cycle][group][key], (cycle, field, text)
        retire = records[cycle].get("retire")
        pc = page.query_selector(".wf-retire td[data-key='pc']")
        assert (pc.inner_text() if pc else None) == (f"0x{retire['pc']:08x}" if retire else None)


def test_cursors_and_delta(s):
    page = open_view(s, "/bundles/t1-uart/")
    box = page.query_selector(".wf-canvas").bounding_box()
    page.click(".wf-canvas", position={"x": box["width"] * 0.25, "y": 10})
    a = page.evaluate("window.testHost.cursor.get()")
    page.click(".wf-canvas", position={"x": box["width"] * 0.75, "y": 10}, modifiers=["Shift"])
    assert page.evaluate("window.testHost.cursor.get()") == a, "shift-click moved cursor A"
    delta = int(page.get_attribute(".wf-cursors", "data-delta"))
    count = 1316
    assert abs(delta - count / 2) <= 2, (a, delta)
    assert f"B - A: {delta} cycles" in page.inner_text(".wf-cursors")


def test_trigger(s):
    name = "t2-muldiv"
    _, records = bundle_records(bundle_dir(s, name))
    page = open_view(s, f"/bundles/{name}/")
    rises = [r["cycle"] for i, r in enumerate(records) if i and r["div"]["busy"] and not records[i - 1]["div"]["busy"]]
    assert len(rises) >= 3
    page.select_option(".wf-trigger select[aria-label='trigger signal']", "div.busy")
    page.select_option(".wf-trigger select[aria-label='trigger condition']", "rise")
    for want in rises[:3]:
        page.click("text=Next match")
        assert page.evaluate("window.testHost.cursor.get()") == want
    page.click("text=Previous match")
    assert page.evaluate("window.testHost.cursor.get()") == rises[1]
    # equals: the first cycle in which the data address is TXDATA... or a value muldiv has
    addr = next(r["core"]["memReqAddr"] for r in records[10:] if r["core"]["memReqValid"])
    want = next(r["cycle"] for i, r in enumerate(records)
                if r["core"]["memReqAddr"] == addr and (i == 0 or records[i - 1]["core"]["memReqAddr"] != addr))
    assert want > 0, "the expected match must not be where the cursor already is"
    page.evaluate("window.testHost.cursor.set(0)")
    page.select_option(".wf-trigger select[aria-label='trigger signal']", "core.memReqAddr")
    page.select_option(".wf-trigger select[aria-label='trigger condition']", "value")
    page.fill(".wf-trigger input", hex(addr))
    t0 = page.evaluate("performance.now()")
    page.click("text=Next match")
    page.wait_for_function(f"window.testHost.cursor.get() === {want}")
    ms = page.evaluate("performance.now()") - t0
    assert page.evaluate("window.testHost.cursor.get()") == want
    assert ms < BUDGET_TRIGGER_MS + 200, ms  # includes the click's round trip through Playwright
    page.fill(".wf-trigger input", "0x1z")
    page.click("text=Next match")
    assert "must be" in page.inner_text(".wf-trig-status")


def test_10000_cycle_window_budget(s):
    name = "t2-muldiv"
    manifest, _ = bundle_records(bundle_dir(s, name))
    assert manifest["run"]["count"] == 10000
    page = open_view(s, f"/bundles/{name}/")
    load_ms = float(page.get_attribute(".wf", "data-load-ms"))
    # every field of the bundle on screen, then redraws of the whole window and of zooms of it
    page.evaluate("""() => { const sel = document.querySelector('.wf-toolbar select');
        for (const o of sel.options) { sel.value = o.value; document.querySelector('.wf-toolbar button').click(); } }""")
    times = page.evaluate("""async () => {
        const root = document.querySelector('.wf');
        const out = [];
        const frame = () => new Promise((r) => requestAnimationFrame(() => r()));
        const whole = [...document.querySelectorAll('.wf-toolbar button')].find((b) => b.textContent === 'Whole window');
        const zin = [...document.querySelectorAll('.wf-toolbar button')].find((b) => b.textContent === 'Zoom in');
        for (let i = 0; i < 5; i++) { whole.click(); await frame(); await frame(); out.push(+root.dataset.renderMs);
          for (let z = 0; z < 6; z++) { zin.click(); await frame(); await frame(); out.push(+root.dataset.renderMs); } }
        return { out, rows: document.querySelectorAll('.wf-name').length, view: [root.dataset.viewStart, root.dataset.viewEnd] };
    }""")
    worst = max(times["out"])
    median = sorted(times["out"])[len(times["out"]) // 2]
    print(f"       10,000 cycles: load {load_ms:.0f} ms, redraw median {median:.1f} ms, worst {worst:.1f} ms over "
          f"{len(times['out'])} redraws of {times['rows'] - 1} rows")
    assert times["rows"] > 40, times["rows"]
    assert load_ms <= BUDGET_LOAD_MS, load_ms
    assert median <= BUDGET_RENDER_MEDIAN_MS, times
    assert worst <= BUDGET_RENDER_MS, times


def test_level_of_detail_keeps_a_one_cycle_pulse(s):
    page = s.page("/bundles/t1-uart/")
    result = page.evaluate("""async () => {
        const { summarize } = await import('/web/waveform/lod.js');
        const { runLength } = await import('/web/waveform/data.js');
        const v = new Uint32Array(10000); v[5000] = 1;
        const cols = summarize(runLength(v), 0, 10000, 800);
        const bus = new Uint32Array(10000).fill(7); bus[1234] = 8;
        const bcols = summarize(runLength(bus), 0, 10000, 300);
        const idx = [...Array(800).keys()];
        return { pulse: idx.filter((i) => Number.isNaN(cols.value[i])).map((i) => [i, null, cols.changes[i]]),
                 flat: idx.filter((i) => cols.value[i] === 0).length,
                 bus: [...Array(300).keys()].filter((i) => Number.isNaN(bcols.value[i]) || bcols.edge[i])
                   .map((i) => bcols.changes[i]) };
    }""")
    assert len(result["pulse"]) == 1 and result["pulse"][0][0] == 400 and result["pulse"][0][2] == 2, result
    assert result["flat"] == 799
    assert result["bus"] and sum(result["bus"]) >= 2, result


def test_uart_decoder_cases(s):
    page = s.page("/bundles/t1-uart/")
    r = page.evaluate("""async () => {
        const { decodeUart, frameBytes, measureBitTime } = await import('/web/waveform/uart.js');
        const line = (bytes, d, { stop = 1, gap = 3, lead = 5 } = {}) => {
            const bits = [];
            for (let i = 0; i < lead; i++) bits.push(1);
            for (const b of bytes) {
                const frame = [0]; for (let k = 0; k < 8; k++) frame.push((b >> k) & 1); frame.push(stop);
                for (const x of frame) for (let i = 0; i < d; i++) bits.push(x);
                for (let i = 0; i < gap; i++) bits.push(1);
            }
            for (let i = 0; i < 4 * d; i++) bits.push(1);
            return Uint8Array.from(bits);
        };
        const out = {};
        for (const d of [1, 2, 3, 16]) out['d' + d] = [...frameBytes(decodeUart(line([0x41, 0x00, 0xff, 0x55], d), 0, d).frames)];
        const bad = decodeUart(line([0x41], 16, { stop: 0 }), 0, 16).frames;
        out.framing = bad.map((f) => f.ok);
        const glitch = line([0x42], 16); glitch.copyWithin(2, 0, 0); glitch[3] = 0; // a 1-cycle low before the frame
        out.glitch = [...frameBytes(decodeUart(glitch, 0, 16).frames)];
        // a window that starts inside a frame of back-to-back bytes
        const b2b = line([0x6c, 0x6c, 0x6f, 0x2c, 0x20], 16, { gap: 1, lead: 0 });
        const late = decodeUart(b2b.slice(40), 40, 16, { synced: false });
        out.late = [...frameBytes(late.frames)]; out.searched = late.searched;
        out.first = late.frames[0].start;
        out.measured = measureBitTime(line([0x6f], 16)).bitTime;
        out.offset = decodeUart(line([0x41], 16), 100, 16).frames[0].start;
        return out;
    }""")
    for d in (1, 2, 3, 16):
        assert r[f"d{d}"] == [0x41, 0x00, 0xFF, 0x55], (d, r)
    assert r["framing"] == [False], r
    assert r["glitch"] == [0x42], r
    # frames of 10 bits of 16 cycles and a gap of 1: the second frame starts in cycle 161
    assert r["late"] == [0x6C, 0x6F, 0x2C, 0x20] and r["searched"] is True and r["first"] == 161, r
    assert r["measured"] == 16
    assert r["offset"] == 105, r


def test_axi_decoder_only_with_a_bus_group(s):
    page = open_view(s, "/bundles/t1-uart/")
    assert page.get_attribute(".wf-axi", "data-enabled") == "false"
    assert "no bus group" in page.inner_text(".wf-axi")
    r = page.evaluate("""async () => {
        const { Trace } = await import('/web/waveform/data.js');
        const { decodeAxi, AXI_FIELDS } = await import('/web/waveform/axi.js');
        const t = new Trace(0, 8, AXI_FIELDS.map((n) => ({ name: n, path: n.split('.'), bits: 32 })));
        const set = (n, c, v) => { t.values.get('bus.' + n)[c] = v; };
        set('aw.valid', 1, 1); set('aw.ready', 1, 1); set('aw.addr', 1, 0x10000000);
        set('w.valid', 1, 1); set('w.ready', 2, 1); set('w.valid', 2, 1); set('w.data', 2, 0x41); set('w.strb', 2, 15);
        set('b.valid', 3, 1); set('b.ready', 3, 1);
        set('ar.valid', 4, 1); set('ar.ready', 4, 1); set('ar.addr', 4, 0x80000000);
        set('r.valid', 6, 1); set('r.ready', 6, 1); set('r.data', 6, 7);
        set('aw.valid', 7, 1); set('aw.ready', 7, 1); set('aw.addr', 7, 4);
        return decodeAxi(t);
    }""")
    assert r["writes"][0] == {"addr": 0x10000000, "data": 0x41, "strb": 15, "resp": 0, "aw": 1, "w": 2, "b": 3}, r
    assert r["writes"][1]["aw"] == 7 and r["writes"][1]["w"] is None and r["writes"][1]["b"] is None, r
    assert r["reads"] == [{"addr": 0x80000000, "data": 7, "resp": 0, "ar": 4, "r": 6}], r


def test_bundle_text_is_not_parsed_as_html(s):
    src = bundle_dir(s, "t1-uart")
    dst = os.path.join(s.server.extra, "hostile")
    shutil.copytree(src, dst)
    path = os.path.join(dst, "manifest.json")
    with open(path) as f:
        m = json.load(f)
    payload = '<img src=x onerror="window.__xss=1">'
    # the task is checked (T1 to T9) before a view sees it; the program and the variant are free text
    m["header"]["program"] = payload
    m["header"]["variant"] = payload
    with open(path, "w") as f:
        json.dump(m, f)
    page = open_view(s, "/extra/hostile/")
    assert page.query_selector(".wf img") is None
    assert payload in page.inner_text(".wf-meta")
    assert page.evaluate("window.__xss") is None


def test_works_without_storage(s):
    script = "Object.defineProperty(window, 'localStorage', { get() { throw new Error('denied'); } });"
    page = open_view(s, "/bundles/t1-uart/", init_script=script)
    page.select_option(".wf-toolbar select", "core.rs1")
    page.click(".wf-toolbar button:text('Add')")
    assert page.query_selector(".wf-name[data-row='core.rs1']") is not None
    assert decoded_hex(page) == uart_log(s, "t1-uart").hex()


def test_selected_signals_are_remembered(s):
    page = open_view(s, "/bundles/t1-uart/")
    page.click(".wf-name[data-row='core.pc'] button[aria-label='remove core.pc']")
    page.reload()
    page.wait_for_selector(".wf[data-state=ready]")
    assert page.query_selector(".wf-name[data-row='core.pc']") is None
    assert page.query_selector(".wf-name[data-row='core.inst']") is not None


def test_probe_link_and_unmount(s):
    page = open_view(s, "/bundles/t1-uart/")
    page.evaluate("window.testHost.api.link('probe', { signal: 'core.wbValue', from: 'board' })")
    first_row = page.eval_on_selector(".wf-names .wf-name:nth-child(2)", "e => e.dataset.row")
    assert first_row == "core.wbValue", first_row
    page.evaluate("window.testHost.api.link('probe', { signal: 'vga.hsync', from: 'board' })")
    assert "not recorded" in page.inner_text(".wf-status")
    assert page.evaluate("window.testHost.cursor.listeners") == 1
    assert page.evaluate("window.testHost.handlers.size") == 1
    page.evaluate("window.testHost.unmount()")
    assert page.evaluate("window.testHost.cursor.listeners") == 0
    assert page.evaluate("window.testHost.handlers.size") == 0
    assert page.evaluate("document.getElementById('view').children.length") == 0
    page.evaluate("window.testHost.cursor.set(100)")  # nothing is left listening
    assert not page.errors, page.errors


def test_link_before_load_is_kept(s):
    """A probe link that arrives after mount() started and before the bundle is loaded."""
    page = s.page("/bundles/t2-muldiv/", view="board")
    # a MutationObserver runs before the view's first await returns, so the view is still loading
    state = page.evaluate("""() => new Promise((resolve) => {
        const view = document.getElementById('view');
        const obs = new MutationObserver(() => {
            const wf = view.querySelector('.wf');
            if (!wf) return;
            obs.disconnect();
            const seen = wf.dataset.state;
            window.testHost.api.link('probe', { signal: 'uart.rxd', from: 'board' });
            resolve(seen);
        });
        obs.observe(view, { childList: true, subtree: true });
        window.testHost.show('waveform');
    })""")
    assert state == "loading", state
    page.wait_for_selector(".wf[data-state=ready]", timeout=60000)
    page.wait_for_function("document.querySelector('.wf-names .wf-name:nth-child(2)').dataset.row === 'uart.rxd'")


def test_zoom_at_the_smallest_span_does_not_pan(s):
    page = open_view(s, "/bundles/t1-uart/")
    box = page.query_selector(".wf-canvas").bounding_box()
    page.mouse.move(box["x"] + box["width"] / 2, box["y"] + 10)
    for _ in range(60):  # far past the smallest span of 4 cycles
        page.mouse.wheel(0, -100)
    page.wait_for_timeout(100)
    before = (page.get_attribute(".wf", "data-view-start"), page.get_attribute(".wf", "data-view-end"))
    for _ in range(20):
        page.mouse.wheel(0, -100)
    page.wait_for_timeout(100)
    after = (page.get_attribute(".wf", "data-view-start"), page.get_attribute(".wf", "data-view-end"))
    assert before == after, (before, after)
    assert int(after[1]) - int(after[0]) <= 5, after


def test_cursor_line_at_a_fractional_view_start(s):
    page = s.page("/bundles/t1-uart/")
    r = page.evaluate("""async () => {
        const { viewport, cursorX } = await import('/web/waveform/render.js');
        const vp = viewport(5.2, 15.2, 100);
        return [cursorX(vp, 5), cursorX(vp, 4), cursorX(vp, 14), cursorX(vp, 15)];
    }""")
    assert r[0] is not None and r[1] is None and r[2] is not None and r[3] is None, r


def test_fields_of_other_bundles_stay_remembered(s):
    script = """try { localStorage.setItem('mycpu-obs.waveform.fields.T1',
        JSON.stringify(['vga.hsync', 'uart.txd', 'core.pc'])); } catch (e) {}"""
    page = open_view(s, "/bundles/t1-uart/", init_script=script)
    page.select_option(".wf-toolbar select", "core.rs1")
    page.click(".wf-toolbar button:text('Add')")
    saved = page.evaluate("JSON.parse(localStorage.getItem('mycpu-obs.waveform.fields.T1'))")
    assert "vga.hsync" in saved and "core.rs1" in saved and "core.pc" in saved, saved


TESTS = [
    test_uart_decode_equals_uart_log,
    test_doctored_txd_fails_the_decode_check,
    test_traceback_lands_on_the_store_of_each_character,
    test_store_to_frame,
    test_late_window_traceback,
    test_values_at_the_cursor,
    test_cursors_and_delta,
    test_trigger,
    test_10000_cycle_window_budget,
    test_level_of_detail_keeps_a_one_cycle_pulse,
    test_uart_decoder_cases,
    test_axi_decoder_only_with_a_bus_group,
    test_bundle_text_is_not_parsed_as_html,
    test_works_without_storage,
    test_selected_signals_are_remembered,
    test_probe_link_and_unmount,
    test_link_before_load_is_kept,
    test_zoom_at_the_smallest_span_does_not_pan,
    test_cursor_line_at_a_fractional_view_start,
    test_fields_of_other_bundles_stay_remembered,
]

if __name__ == "__main__":
    # the waveform view needs no WebGL; the board's tests run both ways
    sys.exit(runner.main("web/waveform", [(NO_WEBGL_ARGS, TESTS)]))
