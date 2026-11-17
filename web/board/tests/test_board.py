# SPDX-License-Identifier: MIT
"""Browser tests of the virtual teaching board, on the bundles of
`make web-bundles`: first with WebGL (swiftshader, no GPU), then with WebGL
turned off, one browser at a time. Run with
    uv run --no-project --with playwright==1.63.0 python -I web/board/tests/test_board.py

Expected values come from the bundle's files and the run's retire log.
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(HERE)), "waveform", "tests"))
import runner  # noqa: E402
from runner import NO_WEBGL_ARGS, WEBGL_ARGS, bundle_records  # noqa: E402
from test_waveform import bundle_dir, log_tx_stores, py_frames, uart_log  # noqa: E402

# recorded in web/board/README.md ("Budgets"): mount to the first frame drawn, three.js included,
# measured under swiftshader (software WebGL on the CPU, slower than a laptop GPU)
BUDGET_SCENE_MS = 2500
COMPONENT_IDS = ["soc", "ram", "rom", "uart", "vga", "clock", "reset", "power"]


def open_board(s, url, **kw):
    page = s.page(url, view="board", **kw)
    page.wait_for_selector(".bd[data-state=ready], .bd[data-state=error]", timeout=60000)
    assert page.get_attribute(".bd", "data-state") == "ready", page.inner_text(".bd-status")
    return page


def hotspot(page, kind, ident, name=""):
    sel = f".bd-hotspots li[data-kind='{kind}'][data-id='{ident}'][data-name='{name}']"
    e = page.query_selector(sel)
    assert e, sel
    page.evaluate("document.querySelector('.bd-gl').scrollIntoView({ block: 'nearest' })")
    box = page.query_selector(".bd-gl").bounding_box()
    return box["x"] + float(e.get_attribute("data-x")), box["y"] + float(e.get_attribute("data-y"))


def check_panel(page, ident):
    page.wait_for_selector(f".bd-panel[data-panel='{ident}']")
    text = page.inner_text(f".bd-panel[data-panel='{ident}']")
    if ident == "soc":
        rows = page.query_selector_all(".bd-panel[data-panel='soc'] table.bd-map tr[data-device]")
        assert [r.get_attribute("data-device") for r in rows] == ["rom", "clint", "uart", "simctrl", "vga", "ram"]
        assert page.query_selector(".bd-panel[data-panel='soc'] svg.bd-diagram") is not None
        assert "0x1000_0000" in text and "0x8000_0000" in text
    elif ident == "uart":
        regs = page.query_selector_all(".bd-panel[data-panel='uart'] tr[data-reg]")
        assert [r.get_attribute("data-reg") for r in regs] == ["TXDATA", "RXDATA", "STATUS", "CTRL"]
        assert "0x1000_000c" in text
        assert page.query_selector(".bd-panel[data-panel='uart'] .bd-uart-out") is not None
    elif ident == "vga":
        assert "0x2000_0000" in text and "BUFFER1" in text
        assert "no VGA data in this bundle" in text
    elif ident in ("clock", "reset", "power"):
        assert "Concept" in text
    elif ident == "rom":
        assert "Not part of T1" in text
    return text


def expected_terminal(s, name, cycle):
    manifest, records = bundle_records(bundle_dir(s, name))
    frames = py_frames(records, manifest["header"]["uart_divider"])
    log = uart_log(s, name)
    assert bytes(f[2] for f in frames) == log, "the independent decoder disagrees with the UART log"
    return log[:sum(1 for f in frames if f[1] <= cycle)]


def drag(page, src, dst, steps=12):
    page.mouse.move(*src)
    page.mouse.down()
    for i in range(1, steps + 1):
        page.mouse.move(src[0] + (dst[0] - src[0]) * i / steps, src[1] + (dst[1] - src[1]) * i / steps)
    page.mouse.up()


# ---- with WebGL -------------------------------------------------------------------

def test_scene_loads_within_budget(s):
    times = []
    for _ in range(3):
        page = open_board(s, "/bundles/t1-uart/")
        assert page.get_attribute(".bd", "data-mode") == "3d", page.inner_text(".bd-stage")
        times.append(float(page.get_attribute(".bd", "data-scene-ms")))
        assert "virtual teaching board, not a physical PCB" in page.inner_text(".bd-note")
        assert not page.errors, page.errors
        page.context.close()
    print(f"       scene: {', '.join(f'{t:.0f}' for t in times)} ms (swiftshader)")
    assert max(times) <= BUDGET_SCENE_MS, times


def test_click_each_component_in_3d(s):
    page = open_board(s, "/bundles/t1-uart/")
    for ident in COMPONENT_IDS:
        page.mouse.click(*hotspot(page, "component", ident))
        page.wait_for_function(f"document.querySelector('.bd').dataset.panel === '{ident}'")
        check_panel(page, ident)
    page.mouse.click(*hotspot(page, "component", "soc"))
    page.wait_for_function("document.querySelector('.bd').dataset.panel === 'soc'")
    page.mouse.click(*hotspot(page, "component", "vga", "screen"))
    page.wait_for_function("document.querySelector('.bd').dataset.panel === 'vga'")
    page.mouse.click(*hotspot(page, "net", "uart-tx"))  # a pin opens the part it belongs to
    page.wait_for_function("document.querySelector('.bd').dataset.panel === 'uart'")
    assert not page.errors, page.errors


def test_orbit_and_zoom_move_the_scene(s):
    page = open_board(s, "/bundles/t1-uart/")
    before = hotspot(page, "component", "soc")
    box = page.query_selector(".bd-gl").bounding_box()
    empty = (box["x"] + 20, box["y"] + box["height"] - 20)
    drag(page, empty, (empty[0] + 120, empty[1] - 40))
    page.wait_for_timeout(100)
    after = hotspot(page, "component", "soc")
    assert after != before
    page.mouse.move(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2)
    ram_before = hotspot(page, "component", "ram")
    page.mouse.wheel(0, -600)
    page.wait_for_timeout(150)
    ram_after = hotspot(page, "component", "ram")
    assert ram_after != ram_before
    assert page.get_attribute(".bd", "data-panel") is None, "orbiting opened a panel"


def test_probe_drag_opens_the_waveform(s):
    page = open_board(s, "/bundles/t1-uart/")
    drag(page, hotspot(page, "probe", "P1"), hotspot(page, "net", "uart-tx"))
    page.wait_for_selector(".wf[data-state=ready]", timeout=60000)
    assert page.evaluate("window.testHost.view()") == "waveform"
    links = page.evaluate("window.testHost.links")
    assert {"kind": "probe", "payload": {"signal": "uart.txd", "from": "board"}} in links, links
    page.wait_for_function("document.querySelector('.wf-names .wf-name:nth-child(2)').dataset.row === 'uart.txd'")
    # the probe's place is remembered
    page.evaluate("window.testHost.show('board')")
    page.wait_for_selector(".bd[data-state=ready]")
    li = page.query_selector(".bd-hotspots li[data-kind='probe'][data-id='P1']")
    assert li.get_attribute("data-net") == "uart-tx"


def test_probe_on_a_concept_net_stays(s):
    page = open_board(s, "/bundles/t1-uart/")
    drag(page, hotspot(page, "probe", "P2"), hotspot(page, "net", "clk"))
    page.wait_for_function("document.querySelector('.bd-status').textContent.includes('concept')")
    assert page.evaluate("window.testHost.view()") == "board"
    drag(page, hotspot(page, "probe", "P2"), hotspot(page, "net", "vga-hs"))
    page.wait_for_function("document.querySelector('.bd-status').textContent.includes('not recorded')")
    assert page.evaluate("window.testHost.view()") == "board"


def test_terminal_shows_the_uart_bytes_up_to_the_cursor(s):
    name = "t1-uart-repeat"
    manifest, records = bundle_records(bundle_dir(s, name))
    frames = py_frames(records, manifest["header"]["uart_divider"])
    page = open_board(s, f"/bundles/{name}/")
    cycles = [0, frames[0][1] - 1, frames[0][1], frames[3][1], frames[3][1] + 1, frames[-1][1] - 1, len(records) - 1]
    for c in cycles:
        page.evaluate(f"window.testHost.cursor.set({c})")
        page.wait_for_function(f"document.querySelector('.bd-term-text').dataset.cycle === '{c}'")
        got = page.get_attribute(".bd-term-text", "data-bytes") or ""
        want = expected_terminal(s, name, c)
        assert got == want.hex(), (c, got, want)
        shown = page.inner_text(".bd-term-text")
        if want:
            assert shown.replace("\n", "") == want.decode().replace("\n", ""), (c, shown)


def test_terminal_character_traces_back(s):
    name = "t1-uart-repeat"
    stores = log_tx_stores(s, name)
    page = open_board(s, f"/bundles/{name}/")
    page.evaluate(f"window.testHost.cursor.set({len(bundle_records(bundle_dir(s, name))[1]) - 1})")
    page.click(".bd-ch:nth-child(3)")  # the first "l"
    page.wait_for_selector(".wf-tb[data-store-cycle]", timeout=60000)
    assert page.get_attribute(".wf-tb", "data-store-cycle") == str(stores[2][0])


def test_link_before_load_marks_the_character(s):
    """A uart-char link that arrives while the board loads is marked once the character is shown."""
    name = "t1-uart-repeat"
    manifest, records = bundle_records(bundle_dir(s, name))
    frames = py_frames(records, manifest["header"]["uart_divider"])
    page = s.page(f"/bundles/{name}/", view="waveform")
    page.wait_for_selector(".wf[data-state=ready]")
    page.evaluate("""(start) => new Promise((resolve) => {
        const view = document.getElementById('view');
        const obs = new MutationObserver(() => {
            if (!view.querySelector('.bd')) return;
            obs.disconnect();
            window.testHost.api.link('uart-char', { cycle: start, char: 108, from: 'waveform' });
            resolve();
        });
        obs.observe(view, { childList: true, subtree: true });
        window.testHost.show('board');
    })""", frames[2][0])
    page.wait_for_selector(".bd[data-state=ready]")
    page.evaluate(f"window.testHost.cursor.set({len(records) - 1})")
    page.wait_for_selector(f".bd-ch.bd-on[data-start='{frames[2][0]}']", timeout=5000)


# the renderer's context is made, then building a label fails: the board must release the context
FAIL_AFTER_RENDERER = """(() => {
    const get = HTMLCanvasElement.prototype.getContext;
    window.__glContexts = [];
    HTMLCanvasElement.prototype.getContext = function (type, ...rest) {
        if (type === '2d' && window.__glContexts.length >= 2) throw new Error('planted failure');
        const c = get.call(this, type, ...rest);
        if (c && String(type).startsWith('webgl')) window.__glContexts.push(c);
        return c;
    };
})();"""


def test_scene_failure_falls_back_and_releases(s):
    page = open_board(s, "/bundles/t1-uart/", init_script=FAIL_AFTER_RENDERER)
    assert page.get_attribute(".bd", "data-mode") == "2d"
    assert "could not be built" in page.inner_text(".bd-fallback")
    assert page.query_selector(".bd-gl") is None
    lost = page.evaluate("window.__glContexts.map((c) => c.isContextLost())")
    assert len(lost) >= 2 and all(lost), lost


def test_unmount_releases_the_scene(s):
    page = open_board(s, "/bundles/t1-uart/")
    assert page.evaluate("window.testHost.cursor.listeners") == 1
    page.evaluate("window.testHost.unmount()")
    assert page.evaluate("window.testHost.cursor.listeners") == 0
    assert page.evaluate("window.testHost.handlers.size") == 0
    assert page.query_selector("canvas") is None
    page.evaluate("window.testHost.cursor.set(50)")
    assert not page.errors, page.errors


# ---- without WebGL ----------------------------------------------------------------

def test_without_webgl_the_2d_diagram_appears(s):
    page = open_board(s, "/bundles/t1-uart/")
    assert page.evaluate("!!document.createElement('canvas').getContext('webgl')") is False
    assert page.get_attribute(".bd", "data-mode") == "2d"
    assert "2D block diagram" in page.inner_text(".bd-fallback")
    assert page.query_selector(".bd-gl") is None
    for ident in ["soc", "rom", "clint", "uart", "simctrl", "vga", "ram", "clock", "reset", "power"]:
        page.click(f".bd-stage svg .bd-block[data-id='{ident}']")
        page.wait_for_function(f"document.querySelector('.bd').dataset.panel === '{ident}'")
        if ident in COMPONENT_IDS:
            check_panel(page, ident)
    page.focus(".bd-stage svg .bd-block[data-id='ram']")
    page.keyboard.press("Enter")
    page.wait_for_function("document.querySelector('.bd').dataset.panel === 'ram'")
    for ident in COMPONENT_IDS:  # the component list reaches the same panels
        page.click(f".bd-item[data-id='{ident}']")
        check_panel(page, ident)
    assert not page.errors, page.errors


def test_without_webgl_terminal_and_probe(s):
    name = "t1-uart-repeat"
    page = open_board(s, f"/bundles/{name}/")
    end = len(bundle_records(bundle_dir(s, name))[1]) - 1
    page.evaluate(f"window.testHost.cursor.set({end})")
    page.wait_for_function(f"document.querySelector('.bd-term-text').dataset.cycle === '{end}'")
    assert page.get_attribute(".bd-term-text", "data-bytes") == expected_terminal(s, name, end).hex()
    assert page.is_disabled(".bd-net[data-net='vga-hs']")
    page.click(".bd-net[data-net='uart-rx']")
    page.wait_for_selector(".wf[data-state=ready]", timeout=60000)
    page.wait_for_function("document.querySelector('.wf-names .wf-name:nth-child(2)').dataset.row === 'uart.rxd'")


def test_ppm_parser(s):
    page = open_board(s, "/bundles/t1-uart/")
    r = page.evaluate("""async () => {
        const { parsePpm } = await import('/web/board/panels.js');
        const enc = (t) => new TextEncoder().encode(t);
        const ppm = (head, body) => { const h = enc(head); const b = new Uint8Array(h.length + body.length);
            b.set(h); b.set(body, h.length); return b.buffer; };
        const px = Uint8Array.from([1, 2, 3, 4, 5, 6]);
        const out = {};
        const img = parsePpm(ppm('P6\\n# a comment\\n2 1\\n255\\n', px));
        out.ok = [img.width, img.height, ...img.data];
        for (const [name, head, body] of [['truncated', 'P6 2 1 255\\n', px.subarray(0, 5)],
                                          ['maxval', 'P6 2 1 65535\\n', px], ['ascii', 'P3 2 1 255\\n', px]]) {
            try { parsePpm(ppm(head, body)); out[name] = 'accepted'; } catch (e) { out[name] = 'refused'; }
        }
        return out;
    }""")
    assert r["ok"] == [2, 1, 1, 2, 3, 255, 4, 5, 6, 255], r
    assert r["truncated"] == r["maxval"] == r["ascii"] == "refused", r


def test_without_storage(s):
    script = "Object.defineProperty(window, 'localStorage', { get() { throw new Error('denied'); } });"
    page = open_board(s, "/bundles/t2-uart/", init_script=script)
    page.click(".bd-item[data-id='uart']")
    check_panel(page, "uart")
    assert page.get_attribute(".bd-term-text", "data-bytes") == ""  # the cursor is at cycle 0


GROUPS = [
    (WEBGL_ARGS, [test_scene_loads_within_budget, test_click_each_component_in_3d, test_orbit_and_zoom_move_the_scene,
                  test_probe_drag_opens_the_waveform, test_probe_on_a_concept_net_stays,
                  test_terminal_shows_the_uart_bytes_up_to_the_cursor, test_terminal_character_traces_back,
                  test_link_before_load_marks_the_character, test_scene_failure_falls_back_and_releases,
                  test_unmount_releases_the_scene]),
    (NO_WEBGL_ARGS, [test_without_webgl_the_2d_diagram_appears, test_without_webgl_terminal_and_probe,
                     test_ppm_parser, test_without_storage]),
]

if __name__ == "__main__":
    sys.exit(runner.main("web/board", GROUPS))
