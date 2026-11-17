# SPDX-License-Identifier: MIT
"""Browser tests of the web observation interface (web/README.md, "Tests"),
with Playwright's Chromium, headless:

    uv run --no-project --with playwright==1.63.0 python -I web/tests/browser.py [NAME...]

(`make -C web check` runs it after building map.json.) The tests serve the
repository's root from a local HTTP server on a free port and compare what the
page shows with what this file computes from the bundles on its own, with
the formatting rule of web/lib/format.js. One browser runs at a time, every
context is closed after its test, and a launch that fails for want of
inotify instances is retried after a pause. Exit status 0 when every test
passes.
"""

import functools
import hashlib
import http.server
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import traceback

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import sync_playwright

WEB = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(WEB)
sys.path.insert(0, os.path.join(ROOT, "scripts"))
from obslib import Schema, retire_line  # noqa: E402
EXAMPLES = os.path.join(WEB, "examples")
TMP_PREFIX = "/web/tmp-bundles/"
PHONE = {"width": 360, "height": 740}

# ---------------------------------------------------------------------------
# The bundles, read here independently of the page


def fmt(value, bits):
    """web/lib/format.js formatValue."""
    if value is None:
        return "–"
    if bits <= 5:
        return str(value)
    return "0x" + format(value, "0{}x".format(-(-bits // 4)))


def value_at(record, name):
    node = record
    for part in name.split("."):
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node


class Bundle:
    def __init__(self, path):
        self.path = path
        with open(os.path.join(path, "manifest.json")) as f:
            self.manifest = json.load(f)
        self.records = {}
        for chunk in self.manifest["chunks"]:
            with open(os.path.join(path, chunk["file"])) as f:
                for r in json.load(f)["cycles"]:
                    self.records[r["cycle"]] = r
        self.first = self.manifest["run"]["first"]
        self.last = self.first + self.manifest["run"]["count"] - 1


BITS = Schema(os.path.join(WEB, "schema", "obs-v1.json")).field_widths()


def example(ident):
    return Bundle(os.path.join(EXAMPLES, ident, "bundle"))


def listing(ident):
    with open(os.path.join(EXAMPLES, ident, "listing.json")) as f:
        return json.load(f)["lines"]


def rewrite_bundle(src, dst, change_manifest=None, change_record=None):
    """A copy of bundle src at dst, with its manifest and records changed and the chunk hashes made to fit."""
    shutil.copytree(src, dst)
    with open(os.path.join(dst, "manifest.json")) as f:
        manifest = json.load(f)
    if change_record:
        for chunk in manifest["chunks"]:
            path = os.path.join(dst, chunk["file"])
            with open(path) as f:
                data = json.load(f)
            for r in data["cycles"]:
                change_record(r)
            raw = json.dumps(data, separators=(",", ":")).encode()
            with open(path, "wb") as f:
                f.write(raw)
            chunk["bytes"] = len(raw)
            chunk["sha256"] = hashlib.sha256(raw).hexdigest()
    if change_manifest:
        change_manifest(manifest)
    with open(os.path.join(dst, "manifest.json"), "w") as f:
        json.dump(manifest, f)


def split_bundle(src, dst):
    """A copy of bundle src at dst whose one chunk is split in two halves; returns their first cycles."""
    shutil.copytree(src, dst)
    with open(os.path.join(dst, "manifest.json")) as f:
        manifest = json.load(f)
    chunk = manifest["chunks"][0]
    with open(os.path.join(dst, chunk["file"])) as f:
        cycles = json.load(f)["cycles"]
    os.remove(os.path.join(dst, chunk["file"]))
    half = len(cycles) // 2
    manifest["chunks"] = []
    for part in (cycles[:half], cycles[half:]):
        raw = json.dumps({"first": part[0]["cycle"], "count": len(part), "cycles": part}, separators=(",", ":")).encode()
        name = f"cycles/{part[0]['cycle']:012d}.json"
        with open(os.path.join(dst, name), "wb") as f:
            f.write(raw)
        manifest["chunks"].append({"file": name, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
                                   "first": part[0]["cycle"], "count": len(part)})
    with open(os.path.join(dst, "manifest.json"), "w") as f:
        json.dump(manifest, f)
    return manifest, cycles[0]["cycle"], cycles[half]["cycle"]


def hold_first(held):
    """A route handler that keeps the first request it sees in held and lets every later one through."""
    return lambda route: held.append(route) if not held else route.continue_()


def wait_held(page, held):
    while not held:
        page.wait_for_timeout(20)


# ---------------------------------------------------------------------------
# The server and the browser


class Handler(http.server.SimpleHTTPRequestHandler):
    tmp = None

    def translate_path(self, path):
        clean = path.split("?", 1)[0].split("#", 1)[0]
        if clean.startswith(TMP_PREFIX) and Handler.tmp:
            rest = clean[len(TMP_PREFIX):]
            if ".." not in rest.split("/"):
                return os.path.join(Handler.tmp, rest)
        return super().translate_path(path)

    def log_message(self, *args):
        pass


def serve():
    handler = functools.partial(Handler, directory=ROOT)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def launch(playwright):
    for attempt in range(6):
        try:
            return playwright.chromium.launch(headless=True)
        except PlaywrightError as e:
            if "inotify" not in str(e).lower() and "too many open files" not in str(e).lower() or attempt == 5:
                raise
            time.sleep(5 * (attempt + 1))
    raise RuntimeError("unreachable")


class Failure(Exception):
    pass


def check(condition, message):
    if not condition:
        raise Failure(message)


def equal(got, want, what):
    if got != want:
        raise Failure(f"{what}: got {got!r}, want {want!r}")


class Site:
    def __init__(self, browser, base):
        self.browser = browser
        self.base = base

    def page(self, query="", hash_="", viewport=None, init_script=None, map_route=None, routes=()):
        context = self.browser.new_context(viewport=viewport or {"width": 1280, "height": 900})
        if init_script:
            context.add_init_script(init_script)
        page = context.new_page()
        if map_route:
            page.route("**/web/map.json", map_route)
        for pattern, handler in routes:
            page.route(pattern, handler)
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto(f"{self.base}/web/{query}{hash_}")
        page.wait_for_selector("html[data-ready='1']")
        page.errors = errors
        return context, page


def wait_cycle(page, root, n):
    page.wait_for_selector(f"{root}[data-cycle='{n}']")


def no_horizontal_scroll(page, what):
    width = page.evaluate("document.documentElement.scrollWidth")
    inner = page.evaluate("window.innerWidth")
    check(width <= inner, f"{what}: the page is {width} px wide at a viewport of {inner} px")


# ---------------------------------------------------------------------------
# The course map, progress, import


def test_course_map(site):
    with open(os.path.join(WEB, "map.json")) as f:
        m = json.load(f)
    context, page = site.page()
    try:
        tasks = page.eval_on_selector_all("article.unit[data-task]", "els => els.map(e => e.dataset.task)")
        equal(tasks, [t["id"] for t in m["tasks"]], "units on the home page")
        for t in m["tasks"]:
            shown = page.eval_on_selector_all(f"#unit-{t['id']} li[data-exercise]", "els => els.map(e => e.dataset.exercise)")
            equal(shown, [x["id"] for x in t["exercises"]], f"exercises of {t['id']}")
            text = page.text_content(f"#unit-{t['id']}")
            for goal in t["goals"]:
                check(goal.replace("`", "") in text.replace("`", "") or goal in text, f"{t['id']}: goal {goal!r} not shown")
            for mod in t["modules"]:
                check(mod["file"] in text, f"{t['id']}: module file {mod['file']} not shown")
            for spec in t["tests"]:
                check(spec["name"] in text, f"{t['id']}: spec {spec['name']} not shown")
        check(page.is_visible("#unit-M5"), "the Module 5 unit is shown")
        check("not used for grading" in page.inner_text("body"), "progress is labeled 'not used for grading'")
        menu = page.eval_on_selector_all("#menu a", "els => els.map(e => e.dataset.view)")
        equal(menu, ["home"] + [v["name"] for v in m["views"]], "menu")
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()


def test_phone_width(site):
    for query, hash_ in (("", ""), ("?bundle=examples/t2-muldiv/bundle", "#/view/datapath"),
                         ("?bundle=examples/t1-add/bundle", "#/view/activities"),
                         ("?bundle=examples/t1-add/bundle", "#/view/retire")):
        context, page = site.page(query, hash_, viewport=PHONE)
        try:
            if hash_:
                page.wait_for_selector("#view-" + hash_.rsplit("/", 1)[1] + " > *")
            no_horizontal_scroll(page, f"{query}{hash_} at 360 px")
            check(not page.errors, f"page errors: {page.errors}")
        finally:
            context.close()


def test_progress_is_kept(site):
    context, page = site.page()
    try:
        page.check("#progress-read-T1")
        page.reload()
        page.wait_for_selector("html[data-ready='1']")
        check(page.is_checked("#progress-read-T1"), "the read state survives a reload")
        check(not page.is_checked("#progress-read-T2"), "the read state is per unit")
        stored = json.loads(page.evaluate("localStorage.getItem('mycpu-web-progress-v1')"))
        equal(stored["units"]["T1"], {"read": True}, "stored progress")
    finally:
        context.close()


def test_progress_without_storage(site):
    # a browser that refuses site data: every access to localStorage throws
    script = ("Object.defineProperty(window, 'localStorage', {get() { throw new DOMException('denied', "
              "'SecurityError'); }});")
    context, page = site.page(init_script=script)
    try:
        check(page.is_visible("#storage-note"), "a note says that progress cannot be stored")
        page.check("#progress-read-T1")
        check(page.is_checked("#progress-read-T1"), "progress still works in memory")
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()
    # stored data that is not JSON
    context, page = site.page(init_script="localStorage.setItem('mycpu-web-progress-v1', '{not json');")
    try:
        check("could not be read" in page.inner_text("#storage-note"), "corrupt progress is reported")
        page.check("#progress-read-T2")
        check(page.is_checked("#progress-read-T2"), "progress works after corrupt data")
    finally:
        context.close()
    # a quota that refuses the write of the progress (the probe at load time succeeds)
    script = ("const set = Storage.prototype.setItem; Storage.prototype.setItem = function (k, v) {"
              " if (k === 'mycpu-web-progress-v1') throw new DOMException('full', 'QuotaExceededError');"
              " return set.call(this, k, v); };")
    context, page = site.page(init_script=script)
    try:
        check(not page.is_visible("#storage-note"), "no note before a write fails")
        page.check("#progress-read-T2")
        check(page.is_checked("#progress-read-T2"), "a refused write keeps the state in memory")
        check("refused" in page.inner_text("#storage-note"), "the refused write is reported")
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()


def import_into(page, button, directory):
    with page.expect_file_chooser() as chooser:
        page.click(button)
    chooser.value.set_files(directory)


def test_import_records_test_results(site):
    context, page = site.page()
    try:
        import_into(page, "button[data-import='T1']", os.path.join(EXAMPLES, "t1-add", "bundle"))
        page.wait_for_selector("#bundle-name")
        page.goto(page.url.split("#")[0] + "#/")
        page.wait_for_selector("#progress-T1")
        text = page.inner_text("#progress-T1 [data-state='tests']")
        results = example("t1-add").manifest["tests"]["results"]
        passed = sum(r["result"] == "pass" for r in results)
        check(f"add, {passed} pass, {len(results) - passed} fail" in text, f"T1 test state: {text!r}")
        check("none" in page.inner_text("#progress-T2 [data-state='tests']"), "T2 has no test results")
    finally:
        context.close()


def test_refusals(site, tmp):
    cases = []
    t1 = os.path.join(EXAMPLES, "t1-add", "bundle")
    major = os.path.join(tmp, "schema-2")
    rewrite_bundle(t1, major, change_manifest=lambda m: (m.update(schema="2.0"), m["header"].update(schema="2.0")))
    cases.append(("button[data-import='T1']", major, "schema 2.0"))
    minor = os.path.join(tmp, "schema-1-9")
    rewrite_bundle(t1, minor, change_manifest=lambda m: (m.update(schema="1.9"), m["header"].update(schema="1.9")))
    cases.append(("button[data-import='T1']", minor, "newer than this site"))
    cases.append(("button[data-import='T1']", os.path.join(EXAMPLES, "t2-muldiv", "bundle"), "of task T2"))
    t4 = os.path.join(tmp, "task-9")
    rewrite_bundle(t1, t4, change_manifest=lambda m: m["header"].update(task="T9"))
    cases.append(("#import-any", t4, "no unit for it"))
    doctored = os.path.join(tmp, "doctored")
    shutil.copytree(t1, doctored)
    chunk = os.path.join(doctored, example("t1-add").manifest["chunks"][0]["file"])
    with open(chunk, "r+b") as f:
        data = f.read().replace(b'"pc":', b'"pc" :', 1)
        f.seek(0)
        f.write(data)
    cases.append(("#import-any", doctored, "bytes"))
    past = os.path.join(tmp, "past-the-end")
    rewrite_bundle(t1, past, change_manifest=lambda m: m["run"].update(cycles=m["run"]["first"] + 5))
    cases.append(("#import-any", past, "past the end of the run"))

    def twice(m):
        # one file named by two entries that cover the window between them
        c = m["chunks"][0]
        half = c["count"] // 2
        m["chunks"] = [dict(c, count=half), dict(c, first=c["first"] + half, count=c["count"] - half)]
    duplicate = os.path.join(tmp, "chunk-twice")
    rewrite_bundle(t1, duplicate, change_manifest=twice)
    cases.append(("#import-any", duplicate, "twice"))
    for name, entry in (("chunk-null", None), ("chunk-number", 42)):
        rewrite_bundle(t1, os.path.join(tmp, name), change_manifest=lambda m, e=entry: m.update(chunks=[e]))
        cases.append(("#import-any", os.path.join(tmp, name), "is not an object"))
    for name, tests, message in (("tests-object", {"results": {}}, "not an object with a list of results"),
                                 ("tests-result", {"results": [{"name": "x", "result": "maybe"}]}, "test result 0")):
        rewrite_bundle(t1, os.path.join(tmp, name), change_manifest=lambda m, t=tests: m.update(tests=t))
        cases.append(("#import-any", os.path.join(tmp, name), message))
    # a directory whose chunk is not at its path: a file of the same name elsewhere in it is not that chunk
    moved = os.path.join(tmp, "moved", "bundle")
    shutil.copytree(t1, moved)
    chunk_file = example("t1-add").manifest["chunks"][0]["file"]
    os.makedirs(os.path.join(moved, "old", "cycles"))
    os.rename(os.path.join(moved, chunk_file), os.path.join(moved, "old", chunk_file))
    cases.append(("#import-any", moved, "not among the files chosen"))
    for button, directory, message in cases:
        context, page = site.page()
        try:
            import_into(page, button, directory)
            page.wait_for_selector("#bundle-error:not([hidden])")
            text = page.inner_text("#bundle-error")
            check("refused" in text and message in text, f"{os.path.basename(directory)}: {text!r}")
            check(not page.is_visible("#bundle-name"), "a refused bundle is not opened")
        finally:
            context.close()
    for query in ("?bundle=https://example.org/b", "?bundle=../build/x", "?bundle=examples/t1-add/bundle&task=T2"):
        context, page = site.page(query)
        try:
            page.wait_for_selector("#bundle-error:not([hidden])")
            check("refused" in page.inner_text("#bundle-error"), f"{query}: {page.inner_text('#bundle-error')!r}")
        finally:
            context.close()


def test_bundle_text_is_not_markup(site, tmp):
    hostile = os.path.join(tmp, "hostile")
    payload = "<img src=x onerror=\"window.__xss=1\">"
    rewrite_bundle(os.path.join(EXAMPLES, "t1-add", "bundle"), hostile,
                   change_manifest=lambda m: (m["header"].update(program=payload, variant=payload, commit=payload),
                                              m["tests"]["results"][0].update(detail=payload)))
    context, page = site.page()
    try:
        import_into(page, "button[data-import='T1']", hostile)
        page.wait_for_selector("#bundle-name")
        page.goto(page.url.split("#")[0] + "#/view/activities")
        page.wait_for_selector("#activity-checks button.start")
        page.click("#activity-checks button.start")
        page.wait_for_selector("#activity-checks form")
        page.click("#activity-checks button[type='submit']")
        page.wait_for_selector("#activity-checks .score")
        check(payload in page.inner_text("#bundle-name"), "the program name is shown as text")
        equal(page.evaluate("window.__xss"), None, "script from the bundle ran")
        equal(page.eval_on_selector_all("img", "els => els.length"), 0, "an element made from bundle text")
    finally:
        context.close()


# ---------------------------------------------------------------------------
# The datapath

DISPLAYED = """root => {
  const out = [];
  for (const el of root.querySelectorAll('.v[data-field]'))
    out.push([el.dataset.field, el.textContent, el.dataset.concept === '1']);
  // a number that is not inside an element bound to a field was not taken from the bundle
  const loose = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    if (n.parentElement.closest('.v, [data-static], #dp-status, .legend, p, h2, h3, dt, code, a, button, .k, .name, .title')) continue;
    if (/\\b(0x[0-9a-f]+|[0-9]+)\\b/.test(n.textContent)) loose.push(n.textContent);
  }
  return {values: out, loose};
}"""


def compare_datapath(page, bundle, n, absent=()):
    got = page.eval_on_selector("#datapath", DISPLAYED)
    check(not got["loose"], f"cycle {n}: numbers not bound to a field: {got['loose']}")
    check(len(got["values"]) > 20, f"cycle {n}: only {len(got['values'])} values shown")
    record = bundle.records[n]
    for field, text, concept in got["values"]:
        if field.split(".")[0] in absent:
            check(concept and text == "concept", f"cycle {n}: {field} is not in the bundle, shown {text!r}")
            continue
        check(not concept, f"cycle {n}: {field} is marked concept")
        equal(text, fmt(value_at(record, field), BITS[field]), f"cycle {n}: {field}")


def next_retire(bundle, n, step):
    c = n + step
    while bundle.first <= c <= bundle.last:
        if "retire" in bundle.records[c]:
            return c
        c += step
    return None


def test_datapath_every_cycle(site):
    for ident in ("t1-add", "t1-lw", "t1-beq", "t2-muldiv"):
        bundle = example(ident)
        lines = listing(ident)
        context, page = site.page(f"?bundle=examples/{ident}/bundle", "#/view/datapath")
        try:
            wait_cycle(page, "#datapath", bundle.first)
            for n in range(bundle.first, bundle.last + 1):
                if n > bundle.first:
                    page.click("#cycle-next")
                    wait_cycle(page, "#datapath", n)
                compare_datapath(page, bundle, n)
                status = page.inner_text("#dp-status")
                pc = bundle.records[n]["core"]["pc"]
                want = lines.get(f"{pc:08x}", {}).get("text")
                check(want is None or want in status, f"{ident} cycle {n}: listing {want!r} not in {status!r}")
                if "div" in bundle.records[n]:
                    stalled = page.eval_on_selector(".dp-comp[data-id='fetch']", "e => e.classList.contains('stalled')")
                    equal(stalled, bundle.records[n]["div"]["busy"] == 1, f"{ident} cycle {n}: fetch marked stalled")
            # back and forth by instruction, from the last cycle
            n = bundle.last
            for step, button in ((-1, "#instr-prev"), (-1, "#instr-prev"), (1, "#instr-next")):
                target = next_retire(bundle, n, step)
                page.click(button)
                if target is not None:
                    n = target
                wait_cycle(page, "#datapath", n)
                compare_datapath(page, bundle, n)
            for _ in range(3):
                page.click("#cycle-prev")
                n -= 1
                wait_cycle(page, "#datapath", n)
            compare_datapath(page, bundle, n)
            check(not page.errors, f"{ident}: page errors: {page.errors}")
        finally:
            context.close()


def test_datapath_panels(site):
    with open(os.path.join(WEB, "datapath", "t2.json")) as f:
        description = json.load(f)
    bundle = example("t2-muldiv")
    stall = next(c for c in range(bundle.first, bundle.last + 1) if bundle.records[c]["div"]["busy"])
    context, page = site.page(f"?bundle=examples/t2-muldiv/bundle&cycle={stall}", "#/view/datapath")
    try:
        wait_cycle(page, "#datapath", stall)
        # its components and wires are buttons, which an img would hide from assistive technology
        equal(page.get_attribute("#datapath svg", "role"), "group", "the role of the drawing")
        comps = {c["id"]: c for c in description["components"]}
        for cid in ("divider", "execute", "fetch"):
            page.click(f".dp-comp[data-id='{cid}'] rect")
            panel = page.inner_text("#dp-info")
            c = comps[cid]
            check(c["chisel"]["module"] in panel and c["chisel"]["file"] in panel, f"{cid}: Chisel binding in {panel!r}")
            check(f"notebook {c['notebook']}" in panel, f"{cid}: notebook {c['notebook']} in {panel!r}")
            for spec in c["tests"]:
                check(spec in panel, f"{cid}: test {spec} in the panel")
            compare_datapath(page, bundle, stall)
        for w in description["wires"]:
            if w["id"] not in ("div-busy", "rs1v", "redirect"):
                continue
            if w["id"] == "redirect":  # by keyboard, as a button
                page.focus(f".dp-wire[data-id='{w['id']}']")
                page.keyboard.press("Enter")
            else:
                page.dispatch_event(f".dp-wire[data-id='{w['id']}']", "click")
            equal(page.inner_text("#dp-info [data-role='source']").split(" (")[0], comps[w["from"].split(".")[0]]["label"],
                  f"{w['id']} source")
            equal(page.inner_text("#dp-info [data-role='destination']").split(" (")[0], comps[w["to"].split(".")[0]]["label"],
                  f"{w['id']} destination")
            compare_datapath(page, bundle, stall)
        check(page.eval_on_selector(".dp-comp[data-id='fetch']", "e => e.classList.contains('stalled')"),
              "the fetch box is marked while div.busy is 1")
        for wid in ("imm", "result-wb", "mul"):
            g = page.query_selector(f".dp-wire[data-id='{wid}']")
            check("concept" in g.get_attribute("class") and "concept" in g.text_content(), f"{wid} is labeled concept")
        g = page.query_selector(".dp-comp[data-id='multiplier']")
        check("concept" in g.get_attribute("class") and "concept" in g.text_content(), "the multiplier is labeled concept")
        page.click("#dp-zoom-in")
        page.click("#dp-zoom-reset")
    finally:
        context.close()


def test_datapath_concept_when_a_group_is_missing(site, tmp):
    # the T2 bundle without its div group: the divider is drawn as a concept, everything else as recorded
    nodiv = os.path.join(tmp, "nodiv")
    rewrite_bundle(os.path.join(EXAMPLES, "t2-muldiv", "bundle"), nodiv,
                   change_manifest=lambda m: m["groups"].remove("div"), change_record=lambda r: r.pop("div"))
    bundle = Bundle(nodiv)
    context, page = site.page("?bundle=tmp-bundles/nodiv", "#/view/datapath")
    try:
        wait_cycle(page, "#datapath", bundle.first)
        compare_datapath(page, bundle, bundle.first, absent=("div",))
        g = page.query_selector(".dp-comp[data-id='divider']")
        check("concept" in g.get_attribute("class") and "concept" in g.text_content(), "the divider is labeled concept")
        g = page.query_selector(".dp-wire[data-id='div-busy']")
        check("concept" in g.get_attribute("class"), "the busy wire is drawn as a concept")
    finally:
        context.close()


# ---------------------------------------------------------------------------
# Activities, and the retire records they link to


def expected(bundle, q):
    """The answer of a question, computed here from the bundle (the definitions of web/activities)."""
    kind = q["kind"]
    rec = bundle.records
    if kind == "field":
        return value_at(rec[int(q["cycle"])], q["field"])
    if kind == "next-pc":
        return rec[int(q["cycle"]) + 1]["core"]["pc"]
    if kind == "hold":
        c = int(q["cycle"])
        n = c
        while n + 1 <= bundle.last and rec[n + 1]["core"]["pc"] == rec[c]["core"]["pc"]:
            n += 1
        return n - c + 1
    lo, hi = int(q.get("from", 0) or 0), int(q.get("to", 0) or 0)
    if kind == "count-busy":
        return sum(rec[c]["div"]["busy"] for c in range(lo, hi + 1))
    if kind == "retire-cycle":
        c = int(q["cycle"])
        return c if "retire" in rec[c] else next_retire(bundle, c, 1)
    if kind == "count-retire":
        return sum("retire" in rec[c] for c in range(lo, hi + 1))
    if kind == "distinct-pc":
        return len({rec[c]["core"]["pc"] for c in range(lo, hi + 1)})
    if kind == "tests-pass":
        return sum(r["result"] == "pass" for r in bundle.manifest["tests"]["results"])
    if kind == "sources-pc":
        sources = []
        if all("retire" in rec[c] for c in range(lo, hi + 1)):
            sources.append("retire")
        if all("core" in rec[c] for c in range(lo, hi + 1)):
            sources.append("waveform")
        return sources
    raise Failure(f"unknown question kind {kind}")


QUESTIONS = """form => Array.from(form.querySelectorAll('.question'), q => ({...q.dataset}))"""


def fill(page, form, questions, answers):
    for q, a in zip(questions, answers):
        sel = f"{form} .question[data-index='{q['index']}']"
        if q["kind"] == "sources-pc":
            for box in page.query_selector_all(f"{sel} input[type='checkbox']"):
                box.set_checked(box.get_attribute("value") in a)
        else:
            page.fill(f"{sel} input", a if isinstance(a, str) else str(a))


def first_cycle(questions):
    """The cycle at which the evidence links of an activity put the cursor: its first question's."""
    starts = [q.get("from") if q.get("from") is not None else q.get("cycle") for q in questions]
    return min(int(c) for c in starts if c is not None)


def run_activity(page, bundle, lines, activity, minimum):
    form = f"#activity-{activity} form"
    page.click(f"#activity-{activity} button.start")
    page.wait_for_selector(form)
    questions = page.eval_on_selector(form, QUESTIONS)
    check(len(questions) >= minimum, f"{activity}: {len(questions)} questions")
    answers = [expected(bundle, q) for q in questions]
    for q in questions:
        if "mnemonic" in q:
            text = lines[f"{bundle.records[int(q['cycle'])]['retire']['pc']:08x}"]["text"]
            check(text.split()[0] in (q["mnemonic"], q["mnemonic"] + "z"), f"{activity}: cycle {q['cycle']} is {text!r}")
    # the right answers, written as a student would (hexadecimal for wide values)
    typed = [a if isinstance(a, list) else hex(a) if a > 255 else str(a) for a in answers]
    fill(page, form, questions, typed)
    page.click(f"{form} button[type='submit']")
    page.wait_for_selector(f"{form} .score")
    verdicts = page.eval_on_selector_all(f"{form} .verdict", "els => els.map(e => e.className)")
    equal(verdicts, ["verdict ok"] * len(questions), f"{activity}: verdicts for the computed answers")
    equal(page.inner_text(f"{form} .score"), f"{len(questions)} of {len(questions)} as recorded.", f"{activity}: score")
    # the trace shown: every cycle from the first one asked about to the last one an answer names
    lo = first_cycle(questions)
    hi = lo
    for q, a in zip(questions, answers):
        if q["kind"] == "next-pc":
            hi = max(hi, int(q["cycle"]) + 1)
        elif q["kind"] == "hold":
            hi = max(hi, int(q["cycle"]) + a)
        elif q["kind"] == "retire-cycle":
            hi = max(hi, a)
        elif q.get("to") or q.get("cycle"):
            hi = max(hi, int(q.get("to") or q["cycle"]))
    shown = page.eval_on_selector_all(f"{form} table.evidence tr[data-cycle]", "els => els.map(e => Number(e.dataset.cycle))")
    equal(shown, list(range(lo, min(hi, lo + 63, bundle.last) + 1)), f"{activity}: cycles of the trace")
    # wrong answers are not accepted
    page.click(f"#activity-{activity} button.start")
    page.wait_for_selector(f"{form} button[type='submit']:not([disabled])")
    wrong = [["tests"] if isinstance(a, list) else str(a + 1) for a in answers]
    fill(page, form, questions, wrong)
    page.click(f"{form} button[type='submit']")
    page.wait_for_selector(f"{form} .score")
    verdicts = page.eval_on_selector_all(f"{form} .verdict", "els => els.map(e => e.className)")
    equal(verdicts, ["verdict bad"] * len(questions), f"{activity}: verdicts for wrong answers")
    return questions


def map_views():
    with open(os.path.join(WEB, "map.json")) as f:
        return [v["name"] for v in json.load(f)["views"]]


def follow_evidence_link(page, waveform, cycle):
    """The evidence link of "what each check proves": the waveform view once one exists, else the
    retire records; either opens with the cursor at the activity's first cycle."""
    target = "waveform" if waveform else "retire"
    other = "retire" if waveform else "waveform"
    check(page.query_selector(f"#activity-checks [data-goto='{other}']") is None, f"an evidence link to {other}")
    page.click(f"#activity-checks [data-goto='{target}']")
    if waveform:
        page.wait_for_selector(".wf[data-state=ready]", timeout=60000)
        equal(page.get_attribute(".wf-cursors", "data-a"), str(cycle), "the waveform's cursor A after the link")
    else:
        page.wait_for_selector("#retire tbody tr")
    equal(page.input_value("#cycle-input"), str(cycle), f"the site's cursor after the link to {target}")


def test_activity_link_without_a_waveform_view(site):
    # a map of a tree without web/waveform/: the evidence link falls back to the retire records
    def without_waveform(route):
        response = route.fetch()
        m = response.json()
        m["views"] = [v for v in m["views"] if v["name"] != "waveform"]
        for t in m["tasks"]:
            t["views"] = [v for v in t["views"] if v != "waveform"]
        route.fulfill(response=response, json=m)
    bundle = example("t1-add")
    context, page = site.page("?bundle=examples/t1-add/bundle", "#/view/activities", map_route=without_waveform)
    try:
        check("waveform" not in page.eval_on_selector_all("#menu a", "els => els.map(e => e.dataset.view)"),
              "the menu of the fixture map lists no waveform view")
        page.wait_for_selector("#activities")
        questions = run_activity(page, bundle, listing("t1-add"), "checks", 4)
        follow_evidence_link(page, False, first_cycle(questions))
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()


def test_retire_view_in_the_menu(site):
    # the waveform view does not replace the retire records: the menu still opens them
    context, page = site.page("?bundle=examples/t1-add/bundle")
    try:
        page.click("#menu a[data-view='retire']")
        page.wait_for_selector("#retire tbody tr")
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()


def test_activities(site):
    plan = {"t1-add": [("single-cycle", 3), ("checks", 4)], "t1-lw": [("single-cycle", 4), ("checks", 4)],
            "t1-beq": [("single-cycle", 4), ("checks", 4)], "t2-muldiv": [("divider", 5), ("checks", 4)]}
    for ident, activities in plan.items():
        bundle = example(ident)
        lines = listing(ident)
        context, page = site.page(f"?bundle=examples/{ident}/bundle", "#/view/activities")
        try:
            page.wait_for_selector("#activities")
            for activity, minimum in activities:
                questions = run_activity(page, bundle, lines, activity, minimum)
            if ident == "t2-muldiv":
                text = page.inner_text("#activity-single-cycle")
                check("does not have what the activity needs" in text or "start" in text, "single-cycle on T2")
            follow_evidence_link(page, "waveform" in map_views(), first_cycle(questions))
            page.goto(page.url.split("#")[0] + "#/")
            page.wait_for_selector(f"#progress-{bundle.manifest['header']['task']}")
            done = page.inner_text(f"#progress-{bundle.manifest['header']['task']} [data-state='activities']")
            check("checks (0 of 4)" in done, f"{ident}: activity progress {done!r}")
            check(not page.errors, f"{ident}: page errors: {page.errors}")
        finally:
            context.close()


def test_retire_view(site):
    for ident in ("t1-beq", "t2-muldiv"):
        bundle = example(ident)
        middle = (bundle.first + bundle.last) // 2
        context, page = site.page(f"?bundle=examples/{ident}/bundle&cycle={middle}", "#/view/retire")
        try:
            page.wait_for_selector("#retire tbody tr")
            rows = page.eval_on_selector_all("#retire tbody tr", "els => els.map(e => [e.dataset.cycle, e.cells[1].textContent])")
            want = []
            idle = None
            for c in range(max(bundle.first, middle - 24), min(bundle.last, middle + 24) + 1):
                r = bundle.records[c]
                if "retire" in r:
                    if idle:
                        want.append([str(idle[0]), f"idle {idle[1]}"])
                        idle = None
                    want.append([str(c), retire_line(r["retire"])])
                elif idle:
                    idle[1] += 1
                else:
                    idle = [c, 1]
            if idle:
                want.append([str(idle[0]), f"idle {idle[1]}"])
            equal(rows, want, f"{ident}: retire lines")
            target = next(int(c) for c, line in want if not line.startswith("idle") and int(c) != middle)
            page.click(f"#retire tr[data-cycle='{target}']")
            page.wait_for_selector(f"#retire tr.cursor[data-cycle='{target}']")
            equal(page.input_value("#cycle-input"), str(target), f"{ident}: a row moves the cursor")
        finally:
            context.close()


def test_late_completions_do_not_win(site):
    """A view mount or a bundle open that finishes after a later one has no effect."""
    context, page = site.page("?bundle=examples/t2-muldiv/bundle")
    try:
        held = []
        page.route("**/datapath/t2.json", lambda route: held.append(route) if not held else route.continue_())
        page.evaluate("location.hash = '#/view/datapath'")
        page.wait_for_function("() => true")
        while not held:
            page.wait_for_timeout(20)
        page.evaluate("location.hash = '#/'")
        page.wait_for_selector("#unit-T1")
        page.evaluate("location.hash = '#/view/datapath'")
        bundle = example("t2-muldiv")
        # both mounts wait for the one fetch of t2.json (fetched once per task); the first,
        # overtaken by the second, must leave the second alone
        held[0].continue_()
        wait_cycle(page, "#datapath", bundle.first)
        page.wait_for_timeout(300)
        page.click("#cycle-next")
        wait_cycle(page, "#datapath", bundle.first + 1)
        compare_datapath(page, bundle, bundle.first + 1)
        equal(page.eval_on_selector_all("#datapath", "els => els.length"), 1, "datapath views on the page")
    finally:
        context.close()
    # example A is asked for first and answers last: B, asked for second, stays open
    context, page = site.page()
    try:
        held = []
        page.route("**/examples/t1-add/bundle/manifest.json", lambda route: held.append(route))
        page.click("button.example[data-example='t1-add']")
        while not held:
            page.wait_for_timeout(20)
        page.click("button.example[data-example='t1-beq']")
        page.wait_for_selector("#bundle-name")
        held[0].continue_()
        page.wait_for_timeout(500)
        check("program beq" in page.inner_text("#bundle-name"), f"open bundle: {page.inner_text('#bundle-name')!r}")
        check("t1-beq" in page.url and "t1-add" not in page.url, f"the URL names the open bundle: {page.url}")
    finally:
        context.close()


def test_a_bad_later_chunk_is_reported(site, tmp):
    """A chunk is checked when a cycle of it is first shown; a refused one is reported where the values were."""
    bad = os.path.join(tmp, "two-chunks")
    manifest, first, later = split_bundle(os.path.join(EXAMPLES, "t1-add", "bundle"), bad)
    manifest["chunks"][1]["sha256"] = "0" * 64
    with open(os.path.join(bad, "manifest.json"), "w") as f:
        json.dump(manifest, f)
    context, page = site.page("?bundle=tmp-bundles/two-chunks", "#/view/datapath")
    try:
        wait_cycle(page, "#datapath", first)
        page.fill("#cycle-input", str(later))
        page.press("#cycle-input", "Enter")
        page.wait_for_function("() => /could not be read/.test(document.getElementById('dp-status').textContent)")
        check("SHA-256" in page.inner_text("#dp-status"), page.inner_text("#dp-status"))
        check("could not be read" in page.inner_text("#cursor-info"), page.inner_text("#cursor-info"))
        page.evaluate("location.hash = '#/view/retire'")
        page.wait_for_function("() => /could not be read/.test(document.getElementById('retire-caption')?.textContent ?? '')")
        page.evaluate("location.hash = '#/view/activities'")
        page.click("#activity-checks button.start")
        page.wait_for_selector("#activity-checks .error")
        check("could not be read" in page.inner_text("#activity-checks .error"), "activity error")
    finally:
        context.close()


def test_map_links_are_https_only(site):
    def doctored(route):
        response = route.fetch()
        m = response.json()
        m["module5"]["repository"]["url"] = "javascript:window.__xss=1//"
        route.fulfill(response=response, json=m)
    context = site.browser.new_context()
    page = context.new_page()
    try:
        page.route("**/web/map.json", doctored)
        page.goto(f"{site.base}/web/")
        page.wait_for_selector("html[data-ready='1']")
        hrefs = page.eval_on_selector_all("a[href]", "els => els.map(e => e.getAttribute('href'))")
        check(not [h for h in hrefs if h.lower().startswith("javascript:")], "a javascript: link from the map")
    finally:
        context.close()


def test_bundle_without_core(site, tmp):
    nocore = os.path.join(tmp, "nocore")
    rewrite_bundle(os.path.join(EXAMPLES, "t2-muldiv", "bundle"), nocore,
                   change_manifest=lambda m: m["groups"].remove("core"), change_record=lambda r: r.pop("core"))
    context, page = site.page("?bundle=tmp-bundles/nocore", "#/view/activities")
    try:
        page.wait_for_selector("#activities")
        for activity in ("single-cycle", "divider", "checks"):
            page.click(f"#activity-{activity} button.start")
            page.wait_for_selector(f"#activity-{activity} .warn")
            check("does not have what the activity needs" in page.inner_text(f"#activity-{activity}"), activity)
        page.evaluate("location.hash = '#/view/datapath'")
        page.wait_for_selector("#datapath[data-cycle]")
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()


# ---------------------------------------------------------------------------
# The bundle and cursor bars, and the reads that fail


NO_SUBTLE = "Object.defineProperty(Crypto.prototype, 'subtle', {get() { return undefined; }, configurable: true});"


def test_without_webcrypto(site):
    """A page outside a secure context has no crypto.subtle: an imported bundle is refused, a served
    one opens with the notice that its hashes are not checked."""
    context, page = site.page(init_script=NO_SUBTLE)
    try:
        equal(page.evaluate("typeof crypto.subtle"), "undefined", "crypto.subtle in the test page")
        import_into(page, "button[data-import='T1']", os.path.join(EXAMPLES, "t1-add", "bundle"))
        page.wait_for_selector("#bundle-error:not([hidden])")
        text = page.inner_text("#bundle-error")
        check("refused" in text and "SHA-256" in text and "localhost" in text, f"import without WebCrypto: {text!r}")
        check(not page.is_visible("#bundle-name"), "a bundle whose hashes cannot be checked is not opened")
    finally:
        context.close()
    context, page = site.page("?bundle=examples/t1-add/bundle", init_script=NO_SUBTLE)
    try:
        page.wait_for_selector("#bundle-name")
        check("hashes not checked" in page.inner_text("#bundle-unhashed"), "the notice of a served bundle")
        # the size is still checked
        page.click("#cycle-next")
        equal(page.input_value("#cycle-input"), str(example("t1-add").first + 1), "the cursor of a served bundle")
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()
    context, page = site.page("?bundle=examples/t1-add/bundle")
    try:
        page.wait_for_selector("#bundle-name")
        check(page.query_selector("#bundle-unhashed") is None, "no notice where the hashes are checked")
    finally:
        context.close()


def test_sizes_are_checked_before_reading(site, tmp):
    # a chunk whose size is not the manifest's is refused before its bytes are read
    wrong = os.path.join(tmp, "wrong-size")
    rewrite_bundle(os.path.join(EXAMPLES, "t1-add", "bundle"), wrong,
                   change_manifest=lambda m: m["chunks"][0].update(bytes=m["chunks"][0]["bytes"] + 1))
    script = ("window.__reads = []; const read = Blob.prototype.arrayBuffer;"
              " Blob.prototype.arrayBuffer = function () { window.__reads.push(this.name); return read.call(this); };")
    context, page = site.page(init_script=script)
    try:
        import_into(page, "#import-any", wrong)
        page.wait_for_selector("#bundle-error:not([hidden])")
        text = page.inner_text("#bundle-error")
        check("refused" in text and "bytes" in text, f"a chunk of another size: {text!r}")
        equal(page.evaluate("window.__reads"), ["manifest.json"], "the files read")
    finally:
        context.close()


def test_a_failure_after_open_keeps_the_open_bundle(site):
    # the site's cursor handler cannot be added to the second bundle (once): the first one stays open
    script = ("window.__failAttach = false; const add = EventTarget.prototype.addEventListener;"
              " EventTarget.prototype.addEventListener = function (type, fn, options) {"
              " if (window.__failAttach && type === 'change' && fn && fn.name === 'renderCursor') {"
              " window.__failAttach = false; throw new Error('the handler could not be added'); }"
              " return add.call(this, type, fn, options); };")
    bundle = example("t1-add")
    context, page = site.page("?bundle=examples/t1-add/bundle", init_script=script)
    try:
        page.wait_for_selector("#bundle-name")
        page.evaluate("window.__failAttach = true")
        page.click("button.example[data-example='t1-beq']")
        page.wait_for_selector("#bundle-error:not([hidden])")
        check("could not be shown" in page.inner_text("#bundle-error"), page.inner_text("#bundle-error"))
        check("program add" in page.inner_text("#bundle-name"), f"open bundle: {page.inner_text('#bundle-name')!r}")
        check("t1-add" in page.url and "t1-beq" not in page.url, f"the URL names the open bundle: {page.url}")
        page.click("#cycle-next")
        equal(page.input_value("#cycle-input"), str(bundle.first + 1), "the open bundle's cursor")
        pc = bundle.records[bundle.first + 1]["core"]["pc"]
        page.wait_for_function(f"() => document.getElementById('cursor-info').textContent.includes('{pc:08x}')")
        # an import that is refused leaves the URL of the open bundle as it was
        import_into(page, "button[data-import='T1']", os.path.join(EXAMPLES, "t2-muldiv", "bundle"))
        page.wait_for_function("() => /of task T2/.test(document.getElementById('bundle-error').textContent)")
        check("bundle=examples" in page.url and "t1-add" in page.url, f"the URL after a refused import: {page.url}")
        page.evaluate("location.hash = '#/view/datapath'")
        wait_cycle(page, "#datapath", bundle.first + 1)
        compare_datapath(page, bundle, bundle.first + 1)
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()


def test_failed_reads_are_not_kept(site):
    # the schema: the first read (while the map loads) fails, the bundle opened next reads it again
    held = []
    aborted = lambda route: (held.append(route), route.abort()) if not held else route.continue_()  # noqa: E731
    context, page = site.page(routes=[("**/web/schema/obs-v1.json", aborted)])
    try:
        check(held, "the schema was asked for while the page loaded")
        page.click("button.example[data-example='t1-add']")
        page.wait_for_selector("#bundle-name")
        check(page.is_hidden("#bundle-error"), f"bundle error: {page.inner_text('#bundle-error')!r}")
    finally:
        context.close()
    # the datapath's description: the first mount fails, the next one draws
    held = []
    aborted = lambda route: (held.append(route), route.abort()) if not held else route.continue_()  # noqa: E731
    context, page = site.page("?bundle=examples/t1-add/bundle", "#/view/datapath",
                              routes=[("**/datapath/t1.json", aborted)])
    try:
        page.wait_for_selector("#view-datapath .error")
        check("failed" in page.inner_text("#view-datapath .error"), page.inner_text("#view-datapath .error"))
        page.evaluate("location.hash = '#/'")
        page.wait_for_selector("#unit-T1")
        page.evaluate("location.hash = '#/view/datapath'")
        wait_cycle(page, "#datapath", example("t1-add").first)
    finally:
        context.close()


def test_cursor_bar_input(site):
    bundle = example("t1-add")
    context, page = site.page("?bundle=examples/t1-add/bundle")
    try:
        page.wait_for_selector("#bundle-name")
        page.focus("body")
        page.keyboard.press("ArrowRight")
        equal(page.input_value("#cycle-input"), str(bundle.first + 1), "ArrowRight")
        # with Alt, Ctrl or Meta the key is the browser's or the system's
        for key in ("Alt+ArrowRight", "Control+ArrowRight", "Meta+ArrowRight", "Control+Shift+ArrowLeft",
                    "Alt+Shift+ArrowRight"):
            page.keyboard.press(key)
            page.wait_for_timeout(50)
            equal(page.input_value("#cycle-input"), str(bundle.first + 1), key)
        page.keyboard.press("Shift+ArrowRight")
        target = next_retire(bundle, bundle.first + 1, 1)
        page.wait_for_function(f"() => document.getElementById('cycle-input').value === '{target}'")
        # an emptied cycle field moves nothing
        page.fill("#cycle-input", "")
        page.press("#cycle-input", "Enter")
        page.wait_for_timeout(100)
        equal(page.input_value("#cycle-input"), str(target), "the cycle field after it was emptied")
        page.fill("#cycle-input", str(bundle.first + 5))
        page.press("#cycle-input", "Enter")
        equal(page.input_value("#cycle-input"), str(bundle.first + 5), "a cycle typed in")
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()


def test_step_by_instruction_while_a_chunk_is_read(site, tmp):
    # the step waits for the second chunk; the cursor moved meanwhile stays where it was moved
    _, first, later = split_bundle(os.path.join(EXAMPLES, "t1-add", "bundle"), os.path.join(tmp, "two"))
    held = []
    context, page = site.page(f"?bundle=tmp-bundles/two&cycle={later - 1}",
                              routes=[(f"**/tmp-bundles/two/cycles/{later:012d}.json", hold_first(held))])
    try:
        page.wait_for_selector("#bundle-name")
        equal(page.input_value("#cycle-input"), str(later - 1), "the cursor at the last cycle of the first chunk")
        page.click("#instr-next")
        wait_held(page, held)
        page.click("#cycle-prev")
        equal(page.input_value("#cycle-input"), str(later - 2), "the cursor moved while the chunk is read")
        held[0].continue_()
        page.wait_for_timeout(500)
        equal(page.input_value("#cycle-input"), str(later - 2), "the cursor after the step's chunk arrived")
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()


def test_example_drops_the_task_asked_for(site):
    context, page = site.page("?bundle=examples/t1-add/bundle&task=T1&cycle=20")
    try:
        page.wait_for_selector("#bundle-name")
        page.click("button.example[data-example='t2-muldiv']")
        page.wait_for_function("() => /muldiv/.test(document.getElementById('bundle-name').textContent)")
        check("task=" not in page.url and "cycle=" not in page.url, f"the URL of the example: {page.url}")
        page.reload()
        page.wait_for_selector("html[data-ready='1']")
        page.wait_for_selector("#bundle-name")
        check("muldiv" in page.inner_text("#bundle-name"), "the example opens again after a reload")
    finally:
        context.close()


def test_activity_started_twice(site, tmp):
    # two starts while the window is read: one form; and the ids of the page are unique
    _, first, later = split_bundle(os.path.join(EXAMPLES, "t1-add", "bundle"), os.path.join(tmp, "two"))
    held = []
    context, page = site.page("?bundle=tmp-bundles/two", "#/view/activities",
                              routes=[(f"**/tmp-bundles/two/cycles/{later:012d}.json", hold_first(held))])
    try:
        page.wait_for_selector("#activities")
        page.click("#activity-checks button.start")
        wait_held(page, held)
        page.click("#activity-checks button.start")
        page.wait_for_timeout(100)
        held[0].continue_()
        page.wait_for_selector("#activity-checks form")
        page.wait_for_timeout(300)
        equal(page.eval_on_selector_all("#activity-checks form", "els => els.length"), 1, "forms of the activity")
        page.click("#activity-single-cycle button.start")
        page.wait_for_selector("#activity-single-cycle form")
        ids = page.eval_on_selector_all("[id]", "els => els.map(e => e.id)")
        equal(sorted({i for i in ids if ids.count(i) > 1}), [], "ids that the page has twice")
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()


def fake_t4(src, dst, variant):
    """Bundle src as a T4 bundle of variant `variant` whose `pipe` group is all zero (nothing in the pipeline)."""
    pipe = {}
    for name in BITS:
        if name.startswith("pipe."):
            node = pipe
            *parents, leaf = name.split(".")[1:]
            for part in parents:
                node = node.setdefault(part, {})
            node[leaf] = 0

    def manifest(m):
        m["header"].update(task="T4", variant=variant)
        m["groups"].append("pipe")
    rewrite_bundle(src, dst, change_manifest=manifest, change_record=lambda r: r.update(pipe=json.loads(json.dumps(pipe))))


def test_pipeline_compare_newest_wins(site, tmp):
    src = os.path.join(EXAMPLES, "t1-add", "bundle")
    for variant in ("Stall", "Forward", "EarlyBranch"):
        fake_t4(src, os.path.join(tmp, variant), variant)
    held = []
    context, page = site.page("?bundle=tmp-bundles/Stall", "#/view/pipeline",
                              routes=[("**/tmp-bundles/Forward/manifest.json", hold_first(held))])
    try:
        page.wait_for_selector("#pp-compare-path")
        page.fill("#pp-compare-path", "tmp-bundles/Forward")
        page.click("#pp-compare-add")
        wait_held(page, held)
        page.fill("#pp-compare-path", "tmp-bundles/EarlyBranch")
        page.click("#pp-compare-add")
        page.wait_for_function("() => /added EarlyBranch/.test(document.getElementById('pp-compare-message').textContent)")
        held[0].continue_()
        page.wait_for_timeout(500)
        check("added EarlyBranch" in page.inner_text("#pp-compare-message"), page.inner_text("#pp-compare-message"))
        heads = page.eval_on_selector_all("#pp-variants th", "els => els.map(e => e.textContent)")
        check("Forward" not in heads and "EarlyBranch" in heads, f"the variants side by side: {heads}")
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()
    # a later request refused before it starts (a path that is not one) overtakes the pending one too
    held = []
    context, page = site.page("?bundle=tmp-bundles/Stall", "#/view/pipeline",
                              routes=[("**/tmp-bundles/Forward/manifest.json", hold_first(held))])
    try:
        page.wait_for_selector("#pp-compare-path")
        page.fill("#pp-compare-path", "tmp-bundles/Forward")
        page.click("#pp-compare-add")
        wait_held(page, held)
        page.fill("#pp-compare-path", "../elsewhere")
        page.click("#pp-compare-add")
        held[0].continue_()
        page.wait_for_timeout(500)
        check("not added" in page.inner_text("#pp-compare-message"), page.inner_text("#pp-compare-message"))
        heads = page.eval_on_selector_all("#pp-variants th", "els => els.map(e => e.textContent)")
        check("Forward" not in heads, f"the variants side by side: {heads}")
    finally:
        context.close()


def test_example_overtaken_while_its_view_mounts(site):
    # example add opens and its datapath waits for t1.json; a bundle imported meanwhile is the open one
    held = []
    context, page = site.page("", "#/view/datapath", routes=[("**/datapath/t1.json", hold_first(held))])
    try:
        page.click("#view-datapath button.example[data-example='t1-add']")
        wait_held(page, held)
        # the URL names a bundle once it is shown, not once its view has mounted
        check("t1-add" in page.url, f"the URL while the view of the example mounts: {page.url}")
        page.set_input_files("#bundle-input", os.path.join(EXAMPLES, "t1-beq", "bundle"))
        page.wait_for_function("() => /program beq/.test(document.getElementById('bundle-name')?.textContent ?? '')")
        check("bundle=" not in page.url, f"the URL while the view of the import mounts: {page.url}")
        held[0].continue_()
        wait_cycle(page, "#datapath", example("t1-beq").first)
        page.wait_for_timeout(500)
        check("program beq" in page.inner_text("#bundle-name"), f"open bundle: {page.inner_text('#bundle-name')!r}")
        check("bundle=" not in page.url, f"the URL names no bundle but the imported one is open: {page.url}")
        check(not page.errors, f"page errors: {page.errors}")
    finally:
        context.close()


TESTS = [test_course_map, test_phone_width, test_progress_is_kept, test_progress_without_storage,
         test_import_records_test_results, test_refusals, test_bundle_text_is_not_markup, test_datapath_every_cycle,
         test_datapath_panels, test_datapath_concept_when_a_group_is_missing, test_activities,
         test_activity_link_without_a_waveform_view, test_retire_view_in_the_menu, test_retire_view,
         test_late_completions_do_not_win, test_a_bad_later_chunk_is_reported, test_map_links_are_https_only,
         test_bundle_without_core, test_without_webcrypto, test_sizes_are_checked_before_reading,
         test_a_failure_after_open_keeps_the_open_bundle, test_failed_reads_are_not_kept, test_cursor_bar_input,
         test_step_by_instruction_while_a_chunk_is_read, test_example_drops_the_task_asked_for,
         test_activity_started_twice, test_pipeline_compare_newest_wins, test_example_overtaken_while_its_view_mounts]


def main(names):
    if not os.path.isfile(os.path.join(WEB, "map.json")):
        print("browser: web/map.json not found: run make -C web map", file=sys.stderr)
        return 2
    selected = [t for t in TESTS if not names or t.__name__ in names]
    server = serve()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    failures = 0
    tmp = tempfile.mkdtemp(prefix="web-tests-")
    Handler.tmp = tmp
    try:
        with sync_playwright() as playwright:
            browser = launch(playwright)
            try:
                site = Site(browser, base)
                for test in selected:
                    case_tmp = os.path.join(tmp, test.__name__)
                    os.makedirs(case_tmp)
                    Handler.tmp = case_tmp
                    started = time.time()
                    try:
                        if test.__code__.co_argcount == 2:
                            test(site, case_tmp)
                        else:
                            test(site)
                        print(f"PASS {test.__name__} ({time.time() - started:.1f} s)")
                    except (Failure, PlaywrightError, AssertionError, KeyError) as e:
                        failures += 1
                        print(f"FAIL {test.__name__}: {e}")
                        if not isinstance(e, Failure):
                            traceback.print_exc()
            finally:
                browser.close()
    finally:
        server.shutdown()
        shutil.rmtree(tmp, ignore_errors=True)
    print(f"browser: {len(selected) - failures} of {len(selected)} tests pass")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
