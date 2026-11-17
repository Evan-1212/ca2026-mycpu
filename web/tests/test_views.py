# SPDX-License-Identifier: MIT
"""Browser tests of the views of T3 to T5 under the site:
csr/, pipeline/, soc/, vga/ and the activities of T3 to T5. Run with

    uv run --no-project --with playwright==1.63.0 python -I web/tests/test_views.py --bundles DIR

(`make -C web check` runs it after `make web-view-bundles`). DIR holds the
bundles of `make web-view-bundles` (web/views.mk), each with the run's retire
log and ELF; the tests copy them into build/web/tmp-views/, which the test
server serves under web/tmp-bundles/ (browser.py) so that the site opens them
with `?bundle=`, and remove that directory when they end: nothing is written
into the source tree. Every expected value is computed here, from the bundle's files, the
retire log, the ELF's symbols, HazardSpec.scala and the golden frames, by code
of its own: none of it comes from the site's JavaScript.

Imported by the unittest discovery of `make -C web check-map`, it defines no
test case; Playwright is imported in main() only.
"""

import argparse
import gzip
import json
import os
import random
import re
import shutil
import sys
import time
import traceback

WEB = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(WEB)
TMP_OUT = os.path.join(ROOT, "build", "web", "tmp-views")  # the copies of the bundles
TMP_URL = "tmp-bundles"  # where the test server serves TMP_OUT (browser.TMP_PREFIX, below web/)
VARIANTS = [("Stall", "stall"), ("Forward", "forward"), ("EarlyBranch", "earlybranch")]
VGA_BASE = 0x20000000
UART_TXDATA = 0x10000000
QR_URL = "https://github.com/sysprog21/ca2026-mycpu"
BUNDLES = ["t3-irqtrap", "t3-uart", "t4-hazards-stall", "t4-hazards-forward", "t4-hazards-earlybranch", "t4-jit",
           "t5-nyancat", "t5-nyancat-shift", "t5-qrcode"]
SCAN_CYCLES = (479 * 800 + 639) * 2  # a frame's first visible pixel to its last, two cycles per pixel (Vga)


class Failure(Exception):
    pass


def check(condition, message):
    if not condition:
        raise Failure(message)


def equal(got, want, what):
    if got != want:
        raise Failure(f"{what}: got {got!r}, want {want!r}")


# ---------------------------------------------------------------------------
# What the tests expect, computed here


class Run:
    """A bundle of `make web-view-bundles`: its manifest, every record, the ELF's symbols."""

    def __init__(self, directory, name):
        from elfsyms import symbols
        self.name = name
        self.path = os.path.join(directory, name)
        with open(os.path.join(self.path, "manifest.json")) as f:
            self.manifest = json.load(f)
        self.records = []
        for c in self.manifest["chunks"]:
            with open(os.path.join(self.path, c["file"])) as f:
                self.records += json.load(f)["cycles"]
        self.first = self.manifest["run"]["first"]
        self.symbols = symbols(os.path.join(directory, name + ".elf"))
        self.stores = None
        if self.manifest.get("stores"):
            with open(os.path.join(self.path, "stores.json")) as f:
                self.stores = json.load(f)["stores"]

    def at(self, cycle):
        return self.records[cycle - self.first]

    def frame(self, n):
        entry = next(e for e in self.manifest["vga"] if e["frame"] == n)
        with open(os.path.join(self.path, entry["file"]), "rb") as f:
            return entry, ppm_pixels(f.read())

    def test(self, name):
        return next((t for t in self.manifest["tests"]["results"] if t["name"] == name), None)


def ppm_pixels(data):
    """The pixels of a 640 x 480 binary PPM as the harness writes it (P6\\n640 480\\n255\\n)."""
    header = b"P6\n640 480\n255\n"
    check(data.startswith(header) and len(data) == len(header) + 640 * 480 * 3, "not a frame of the harness")
    return data[len(header):]


def rgb(pixels, x, y):
    i = 3 * (640 * y + x)
    return (pixels[i] << 16) | (pixels[i + 1] << 8) | pixels[i + 2]


def hazard_spec():
    """HazardSpec.sequences: (name, producer, consumer, (stalls per variant), (flushes per variant));
    the stalls are None for a row that HazardSpec holds back (`heldBack`): the tests below take
    those from the bundles and check that the views show what the run did."""
    with open(os.path.join(ROOT, "4-pipeline", "src", "test", "scala", "mycpu", "pipeline", "HazardSpec.scala")) as f:
        text = f.read()
    named = {"none": (0, 0, 0), "heldBack": None}
    named["taken"] = tuple(int(x) for x in re.search(r"val taken = \((\d+), (\d+), (\d+)\)", text).groups())
    tup = lambda t: named[t] if t in named else tuple(int(x) for x in re.findall(r"\d+", t))  # noqa: E731
    rows = re.findall(r'Sequence\("([^"]+)", "([^"]+)", "([^"]+)", (\(\d+, \d+, \d+\)|none|heldBack), (\(\d+, \d+, \d+\)|none|taken)\)', text)
    check(len(rows) >= 20, f"HazardSpec has {len(rows)} sequences")
    return [(n, p.replace("\\n", "\n"), c, tup(st), tup(fl)) for n, p, c, st, fl in rows]


def hazards_program():
    """The producer and consumer of each sequence k of software/programs/hazards/main.S: after the
    comment `// k:`, `la t3`, `sw t3` and four nops come the producer's lines, then `c<k>:` and the
    consumer."""
    with open(os.path.join(ROOT, "software", "programs", "hazards", "main.S")) as f:
        lines = [line.strip() for line in f.read().splitlines()]
    out = {}
    for i, line in enumerate(lines):
        m = re.match(r"// (\d+): ", line)
        if not m:
            continue
        k = int(m.group(1))
        check(lines[i + 1].startswith("la") and lines[i + 2].startswith("sw") and lines[i + 3:i + 7] == ["nop"] * 4,
              f"hazards/main.S: sequence {k} does not start as the others")
        j = i + 7
        producer = []
        while not lines[j].startswith(f"c{k}:"):
            producer.append(lines[j])
            j += 1
        out[k] = ("\n".join(producer), lines[j][len(f"c{k}:"):].strip())
    return out


def norm(text):
    return re.sub(r"\s+", " ", re.sub(r"\bn\d*\b", "n", text)).strip()


def decode_visits(run, pc):
    """The visits of `pc` to ID: runs of cycles in which ID holds it, a run going on while ID keeps it."""
    visits = []
    prev = None
    for r in run.records:
        d = r["pipe"]["decode"]
        here = d["valid"] and d["pc"] == pc
        if here and not (prev is not None and prev["pipe"]["decode"]["valid"] and prev["pipe"]["decode"]["pc"] == pc
                         and prev["pipe"]["decode"]["stall"]):
            visits.append([])
        if here:
            visits[-1].append(r)
        prev = r
    return visits


def ex_bubbles_after(run, pc):
    """The cycles after the last one in which EX holds `pc` up to the next one EX holds anything."""
    last = max(k for k, r in enumerate(run.records) if r["pipe"]["execute"]["valid"] and r["pipe"]["execute"]["pc"] == pc)
    n = 0
    k = last + 1
    while not run.records[k]["pipe"]["execute"]["valid"]:
        n += 1
        k += 1
    return n


def last_stores(stores, cycle):
    words = {}
    for c, pc, addr, size, data in stores:
        if c > cycle:
            break
        if size == 4:
            words[addr] = (c, pc, data)
    return words


def uart_frames(run):
    """(start, byte) of each UART frame of uart.txd, sampled in the middle of each bit."""
    div = run.manifest["header"]["uart_divider"]
    txd = [r["uart"]["txd"] for r in run.records]
    out = []
    i = 0
    while i + div // 2 + 9 * div < len(txd):
        if txd[i]:
            i += 1
            continue
        mid = i + div // 2
        if txd[mid]:
            i = mid
            continue
        byte = sum(txd[mid + b * div] << (b - 1) for b in range(1, 9))
        out.append((run.first + i, byte))
        i = mid + 9 * div + 1
    return out


def store_of_frame(run, start):
    stores = [(r["cycle"], r["retire"]["pc"]) for r in run.records if r["cycle"] < start and r.get("retire")
              and r["retire"].get("mem", {}).get("write") and r["retire"]["mem"]["addr"] == UART_TXDATA
              and r["retire"]["mem"]["bytes"] == 4]
    k = len(stores) - 1 - run.at(start)["uart"]["txCount"]
    return stores[k] if k >= 0 else None


# ---------------------------------------------------------------------------
# The browser


class Suite:
    def __init__(self, playwright, base, runs):
        self.playwright = playwright
        self.base = base
        self.runs = runs
        self.results = []

    def page(self, bundle, view, cycle=None):
        context = self.browser.new_context(viewport={"width": 1400, "height": 1000})
        self.contexts.append(context)
        page = context.new_page()
        page.errors = []
        page.on("pageerror", lambda e: page.errors.append(str(e)))
        page.on("console", lambda m: page.errors.append(m.text) if m.type == "error" else None)
        query = f"?bundle={TMP_URL}/{bundle}" + (f"&cycle={cycle}" if cycle is not None else "")
        page.goto(f"{self.base}/web/{query}#/view/{view}")
        page.wait_for_selector("html[data-ready='1']", timeout=60000)
        return page

    def run(self, launch, tests):
        self.browser = launch(self.playwright, [])
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
                self.results.append((test.__name__, status))
                print(f"  {status:4} {test.__name__} ({(time.perf_counter() - t0) * 1000:.0f} ms)" +
                      (f"\n       {detail}" if detail else ""), flush=True)
        finally:
            self.browser.close()


def cursor(page):
    return int(page.input_value("#cycle-input"))


def set_cursor(page, cycle):
    page.fill("#cycle-input", str(cycle))
    page.dispatch_event("#cycle-input", "change")
    page.wait_for_function(f"document.getElementById('cycle-input').value === '{cycle}'")


# ---------------------------------------------------------------------------
# The CSR file, traps and interrupts


def test_csr_follows_irqtrap(s):
    run = s.runs["t3-irqtrap"]
    page = s.page("t3-irqtrap", "csr")
    page.wait_for_selector("#csr-trap-list tbody tr")
    rows = page.eval_on_selector_all("#csr-trap-list tbody tr", "rs => rs.map(r => r.dataset.cycle)")
    want = [r["cycle"] for r in run.records if r["csr"]["trap"]]
    equal([int(c) for c in rows], want, "the cycles of the traps")
    check(len(want) >= 5, f"irqtrap's first 10,000 cycles have {len(want)} traps")
    followed = 0
    for cycle in want:
        r = run.at(cycle)
        rec = r["retire"]
        check(rec and "trap" in rec, f"cycle {cycle}: csr.trap without a trap record")
        if cycle + 1 >= run.first + len(run.records):
            continue
        after = run.at(cycle + 1)["csr"]
        # the displayed mepc and mcause equal the retire record's pc and cause
        row = f"#csr-trap-list tr[data-cycle='{cycle}']"
        equal(page.inner_text(f"{row} td[data-col=pc]"), f"{rec['pc']:08x}", f"cycle {cycle}: the record's pc")
        equal(page.inner_text(f"{row} td[data-col=mepc]"), f"{rec['pc']:08x}", f"cycle {cycle}: mepc after the trap")
        equal(page.inner_text(f"{row} td[data-col=mcause]"), f"{rec['trap']['cause']:08x}", f"cycle {cycle}: mcause")
        equal(after["mepc"], rec["pc"], f"cycle {cycle}: csr.mepc of the next cycle and the record")
        equal(after["mcause"], rec["trap"]["cause"], f"cycle {cycle}: csr.mcause of the next cycle and the record")
        if not rec["trap"]["cause"] & 0x80000000:
            continue
        # an interrupt: follow it from the line rising in mip to the handler's first instruction
        code = rec["trap"]["cause"] & 0x7fffffff
        k = cycle - run.first
        while k > 0 and (run.records[k - 1]["csr"]["mip"] >> code) & 1:
            k -= 1
        rise = run.records[k]["cycle"] if k > 0 else None
        handler = next(x for x in run.records[cycle - run.first + 1:] if x.get("retire"))
        page.click(f"{row} button[data-follow]")
        page.wait_for_selector(f"ol.csr-path[data-trap='{cycle}']")
        if rise is not None:
            equal(cursor(page), rise, f"interrupt at {cycle}: the cursor on its line's rise")
            equal(page.get_attribute("li[data-step=line]", "data-cycle"), str(rise), "the line step")
        for step, at in (("trap", cycle), ("csrs", cycle + 1), ("handler", handler["cycle"])):
            equal(page.get_attribute(f"li[data-step={step}]", "data-cycle"), str(at), f"interrupt at {cycle}: step {step}")
            page.click(f"li[data-step={step}] button")
            page.wait_for_function(f"document.getElementById('cycle-input').value === '{at}'")
        # at the handler: its pc is mtvec's base (direct mode), and the CSRs shown are the record's
        mtvec = r["csr"]["mtvec"]
        check(mtvec & 3 == 0, f"interrupt at {cycle}: mtvec {mtvec:08x} is not in direct mode")
        equal(handler["retire"]["pc"], mtvec, f"interrupt at {cycle}: the handler's pc and mtvec")
        check(f"retires pc={handler['retire']['pc']:08x}" in page.inner_text("#cursor-info"), "the site's cursor info at the handler")
        set_cursor(page, cycle + 1)
        page.wait_for_selector(f"#csr-now h2:text-is('Cycle {cycle + 1}')")
        equal(page.inner_text("#csr-now [data-field='csr.mepc'] .mono"), f"0x{rec['pc']:08x}", "the CSR panel's mepc")
        equal(page.inner_text("#csr-now [data-field='csr.mcause'] .mono"), f"0x{rec['trap']['cause']:08x}", "the CSR panel's mcause")
        followed += 1
    check(followed >= 5, f"{followed} interrupts followed")
    check(not page.errors, f"console errors {page.errors}")


# ---------------------------------------------------------------------------
# The pipeline


def hazard_cases(s):
    spec = hazard_spec()
    program = hazards_program()
    equal(len(program), len(spec), "the sequences of the hazards program and of HazardSpec")
    for k, (name, producer, consumer, _, _) in enumerate(spec):
        equal(norm(program[k][1]), norm(consumer), f"sequence {k} ({name}): the consumer")
        equal(norm(program[k][0]), norm(producer), f"sequence {k} ({name}): the producer")
    return spec


def test_pipeline_counts_equal_hazardspec(s):
    spec = hazard_cases(s)
    for v, (variant, model) in enumerate(VARIANTS):
        run = s.runs[f"t4-hazards-{model}"]
        page = s.page(f"t4-hazards-{model}", "pipeline")
        page.wait_for_selector("#pp-hazard-list table")
        for k, (name, _, _, stalls, flushes) in enumerate(spec):
            c = run.symbols[f"c{k}"]
            what = f"{variant}, sequence {k} ({name})"
            # this file's own count from the bundle first
            visits = decode_visits(run, c)
            equal(len(visits), 1, f"{what}: visits of the consumer to ID")
            counted = sum(r["pipe"]["loadUseStall"] for r in visits[0])
            if stalls is None:
                stalls = (counted,) * len(VARIANTS)  # held back: the view must show what the run did
            equal(counted, stalls[v], f"{what}: the bundle's stall cycles")
            if flushes[v]:
                equal(ex_bubbles_after(run, c), flushes[v], f"{what}: the bundle's bubbles after the branch")
            rows = page.query_selector_all(f"#pp-hazard-list tr[data-pc='{c}']")
            if stalls[v] == 0 and flushes[v] == 0:
                equal(len(rows), 0, f"{what}: rows of a sequence without a hazard")
                continue
            equal(len(rows), 1, f"{what}: rows")
            equal(rows[0].get_attribute("data-stalls"), str(stalls[v]), f"{what}: stall cycles shown")
            equal(rows[0].get_attribute("data-flush"), str(flushes[v]), f"{what}: bubbles shown")
        check(not page.errors, f"{variant}: console errors {page.errors}")


def test_pipeline_chart_shows_the_flushed_slots(s):
    spec = hazard_spec()
    for v, (variant, model) in enumerate(VARIANTS):
        run = s.runs[f"t4-hazards-{model}"]
        page = s.page(f"t4-hazards-{model}", "pipeline")
        page.wait_for_selector("#pp-chart table")
        for k, (name, _, _, _, flushes) in enumerate(spec):
            if not flushes[v]:
                continue
            c = run.symbols[f"c{k}"]
            ex = [r["cycle"] for r in run.records if r["pipe"]["execute"]["valid"] and r["pipe"]["execute"]["pc"] == c]
            set_cursor(page, ex[-1])
            page.wait_for_selector(f"#pp-chart th.cursor[data-cycle='{ex[-1]}']")
            cells = page.eval_on_selector_all("#pp-chart tr.pp-bubbles td[data-cycle]",
                                              "ts => ts.map(t => [Number(t.dataset.cycle), t.classList.contains('pp-bubble')])")
            after = [b for n, b in cells if n > ex[-1]]
            got = 0
            while got < len(after) and after[got]:
                got += 1
            equal(got, flushes[v], f"{variant}, sequence {k} ({name}): EX bubbles after the branch in the chart")
            # the branch's row has its E cell in the cursor's column
            row = page.query_selector(f"#pp-chart tr[data-pc='{c}'] td[data-cycle='{ex[-1]}'][data-stage=execute]")
            check(row is not None, f"{variant}, sequence {k}: the branch's E cell at {ex[-1]}")
        check(not page.errors, f"{variant}: console errors {page.errors}")


def test_pipeline_fence_i_is_no_branch(s):
    # jit runs fence.i, which redirects the fetch unit from ID: its refetch is not a branch's bubbles
    run = s.runs["t4-jit"]
    fences = sorted({r["pipe"]["decode"]["pc"] for r in run.records
                     if r["pipe"]["decode"]["valid"] and r["pipe"]["decode"]["inst"] & 0x707f == 0x100f})
    check(fences, "the jit window has no fence.i in ID")
    check(any(r["pipe"]["fetch"]["flush"] and r["pipe"]["decode"]["valid"] and r["pipe"]["decode"]["pc"] in fences
              and not r["pipe"]["execute"]["flush"] for r in run.records), "no fence.i redirect from ID in the window")
    page = s.page("t4-jit", "pipeline")
    page.wait_for_selector("#pp-hazard-list table")
    for pc in fences:
        for row in page.query_selector_all(f"#pp-hazard-list tr[data-pc='{pc}']"):
            equal(row.get_attribute("data-flush"), "0", f"fence.i at {pc:08x}: bubbles counted as a branch's")
    check(not page.errors, f"console errors {page.errors}")


def test_pipeline_variants_side_by_side(s):
    spec = hazard_spec()
    page = s.page("t4-hazards-forward", "pipeline")
    page.wait_for_selector("#pp-variants table")
    for _, model in (VARIANTS[0], VARIANTS[2]):
        page.fill("#pp-compare-path", f"{TMP_URL}/t4-hazards-{model}")
        page.click("#pp-compare-add")
        page.wait_for_function("document.getElementById('pp-compare-message').textContent.startsWith('added')", timeout=30000)
    run = s.runs["t4-hazards-forward"]
    for k, (name, _, _, stalls, flushes) in enumerate(spec):
        c = run.symbols[f"c{k}"]
        if stalls is None:  # held back: the stall cycles each variant's run counted
            stalls = tuple(sum(r["pipe"]["loadUseStall"] for r in decode_visits(s.runs[f"t4-hazards-{m}"], c)[0])
                           for _, m in VARIANTS)
        row = page.query_selector(f"#pp-variants tr[data-pc='{c}'][data-occurrence='0']")
        if not any(stalls) and not any(flushes):
            check(row is None, f"sequence {k} ({name}): a row without a hazard in any variant")
            continue
        check(row is not None, f"sequence {k} ({name}): no row")
        for v, (variant, _) in enumerate(VARIANTS):
            cell = row.query_selector(f"td[data-variant='{variant}']")
            equal((cell.get_attribute("data-stalls"), cell.get_attribute("data-flush")), (str(stalls[v]), str(flushes[v])),
                  f"sequence {k} ({name}) on {variant}")
    # a bundle of another program is refused
    page.fill("#pp-compare-path", f"{TMP_URL}/t5-nyancat")
    page.click("#pp-compare-add")
    page.wait_for_function("document.getElementById('pp-compare-message').textContent.startsWith('not added')", timeout=30000)
    check(not page.errors, f"console errors {page.errors}")


# ---------------------------------------------------------------------------
# The SoC's bus and the VGA


def axi_writes(records):
    """The writes of the data master, each (addr, data, strb, aw, w, b), from one transaction at a
    time as the SoC's masters have it (docs/observation.md, "bus"): AW and W, then B. A B with no
    write of these records open answers one begun before them: (None, ..., b)."""
    fire = lambda r, ch: r["bus"][ch]["valid"] and r["bus"][ch]["ready"]  # noqa: E731
    out = []
    aw = w = None
    waiting = None  # a write with AW and W, before its B
    for r in records:
        c = r["cycle"]
        if fire(r, "b"):
            if waiting is None:
                waiting = (aw[1] if aw else None, w[1] if w else None, w[2] if w else None, aw[0] if aw else None,
                           w[0] if w else None)
                aw = w = None
            out.append(waiting + (c,))
            waiting = None
        if fire(r, "aw"):
            aw = (c, r["bus"]["aw"]["addr"])
        if fire(r, "w"):
            w = (c, r["bus"]["w"]["data"], r["bus"]["w"]["strb"])
        if aw and w:
            check(waiting is None, f"cycle {c}: a second write while one waits for its B")
            waiting = (aw[1], w[1], w[2], aw[0], w[0])
            aw = w = None
    if waiting:
        out.append(waiting + (None,))
    return out


def test_soc_transactions_match_the_stores(s):
    run = s.runs["t5-nyancat"]
    writes = axi_writes(run.records)
    check(writes, "the window has no write of the data master")
    # every store that retired in the window is a write of the data master with its word address and data,
    # in order; a write whose B is past the window's end has no record yet
    stores = [r["retire"]["mem"] for r in run.records if r.get("retire") and r["retire"].get("mem", {}).get("write")]
    check(len(writes) - len(stores) in (0, 1), f"{len(writes)} writes and {len(stores)} stores")
    for m, (addr, data, strb, *_) in zip(stores, writes):
        equal(addr & ~3, m["addr"] & ~3, "a store's AW address")
        lane = 8 * (m["addr"] & 3)
        mask = ((1 << (8 * m["bytes"])) - 1) << lane
        equal(data & mask, (m["data"] << lane) & mask, f"the W data of the store to {m['addr']:08x}")
        equal(strb, ((1 << m["bytes"]) - 1) << (m["addr"] & 3), f"the W strobes of the store to {m['addr']:08x}")
    reads = sum(1 for r in run.records if r["bus"]["ar"]["valid"] and r["bus"]["ar"]["ready"])
    page = s.page("t5-nyancat", "soc")
    page.wait_for_selector("#soc-now svg.soc-diagram")
    check(f"{len(writes)} writes and {reads} reads" in page.inner_text("#soc-list"), "the counts of the transactions")
    by_start = {min(x for x in (wr[3], wr[4]) if x is not None): wr for wr in writes}
    for wr in writes[:3] + writes[len(writes) // 2:len(writes) // 2 + 2] + writes[-2:]:
        at = wr[4]
        set_cursor(page, at)
        page.wait_for_selector(f"#soc-now h2:text-is('Cycle {at}')")
        check("fire" in (page.get_attribute(".soc-channels tr[data-channel=w]", "class") or ""), f"cycle {at}: W transfers")
        check(f"write of {wr[0]:08x}" in page.inner_text("#soc-open"), f"cycle {at}: the open write of {wr[0]:08x}")
        rows = page.eval_on_selector_all("#soc-list tbody tr[data-kind=write]",
                                         "rs => rs.map(r => [Number(r.dataset.start), r.dataset.addr, r.dataset.data])")
        check(rows, f"cycle {at}: no write listed")
        for start, addr, data in rows:
            want = by_start[start]
            equal((int(addr), int(data)), (want[0], want[1]), f"the listed write that starts in cycle {start}")
    # a window that begins inside a write: its B answers a write begun before it, and the pairing goes on
    k = len(writes) // 2
    cut = writes[k][4] + 1  # after the AW and W of write k, before its B
    check(writes[k][5] is not None and writes[k][5] > cut - 1, "write k has its B in the window")
    got = page.evaluate("""async ([url, cut]) => {
        const m = await import('./soc/index.js');
        const manifest = await (await fetch(url + '/manifest.json')).json();
        let records = [];
        for (const c of manifest.chunks) records = records.concat((await (await fetch(url + '/' + c.file)).json()).cycles);
        return m.transactions(records.filter((r) => r.cycle >= cut)).writes
            .map((w) => [w.addr, w.data, w.aw, w.w, w.b, w.before]);
    }""", [f"{TMP_URL}/t5-nyancat", cut])
    equal(got[0], [None, None, None, None, writes[k][5], True], "the write that began before the cut")
    equal([g[:5] for g in got[1:]], [[w[0], w[1], w[3], w[4], w[5]] for w in writes[k + 1:]], "the writes after it")
    check(not page.errors, f"console errors {page.errors}")


def test_vga_pixels_land_on_their_stores(s):
    run = s.runs["t5-nyancat"]
    entry, pixels = run.frame(1)
    with gzip.open(os.path.join(ROOT, "sim", "tests", "golden", "nyancat-1.ppm.gz")) as f:
        equal(ppm_pixels(f.read()), pixels, "the bundle's frame 1 and the golden frame")
    words = last_stores(run.stores, entry["cycle"])
    palette = [words.get(VGA_BASE + 0x40 + 4 * i, (0, 0, 0))[2] & 0xffffff for i in range(16)]
    page = s.page("t5-nyancat", "vga")
    page.wait_for_selector("#vga-screen")
    box = page.eval_on_selector("#vga-screen", "e => { const b = e.getBoundingClientRect(); return [b.left, b.top, b.width, b.height]; }")
    rnd = random.Random(20261116)
    for _ in range(20):
        x, y = rnd.randrange(640), rnd.randrange(480)
        page.mouse.click(box[0] + (x + 0.5) * box[2] / 640, box[1] + (y + 0.5) * box[3] / 480)
        page.wait_for_selector(f"#vga-pixel[data-x='{x}'][data-y='{y}']")
        addr = VGA_BASE + 0x4000 * (1 + 1 % 2) + 4 * (20 * (y // 4) + (x // 4) // 8)
        equal(int(page.get_attribute("#vga-pixel", "data-addr")), addr, f"pixel ({x}, {y}): the word")
        cycle, pc, data = words[addr]
        equal(int(page.get_attribute("#vga-pixel", "data-store-cycle")), cycle, f"pixel ({x}, {y}): the store's cycle")
        equal(int(page.get_attribute("#vga-pixel", "data-store-pc")), pc, f"pixel ({x}, {y}): the store's pc")
        # the store's data hold the pixel: its 4 bits, through the palette, are the screen's color
        index = (data >> (4 * ((x // 4) % 8))) & 15
        equal(palette[index], rgb(pixels, x, y), f"pixel ({x}, {y}): the stored index's color and the frame")
        equal(int(page.get_attribute("#vga-pixel", "data-index")), index, f"pixel ({x}, {y}): the index shown")
        check(page.inner_text("#vga-agrees").startswith("yes"), f"pixel ({x}, {y}): the view says they disagree")
        # nyancat leaves the front buffer and the palette alone while a frame is shown
        written = [c for c, _, a, size, _ in run.stores if a in (addr, VGA_BASE + 0x40 + 4 * index) and size == 4
                   and entry["cycle"] - SCAN_CYCLES < c <= entry["cycle"]]
        equal(page.get_attribute("#vga-pixel", "data-uncertain"), "1" if written else "0", f"pixel ({x}, {y}): uncertain")
    check(not page.errors, f"console errors {page.errors}")


def test_vga_pixel_from_the_keyboard(s):
    # the same panel without a mouse: the arrow keys on the focused screen, Shift for 4 pixels
    page = s.page("t5-nyancat", "vga")
    page.wait_for_selector("#vga-screen")
    page.focus("#vga-screen")
    before = page.input_value("#cycle-input")  # the site's cursor stays put
    page.keyboard.press("ArrowRight")
    page.wait_for_selector("#vga-pixel[data-x='0'][data-y='0']")
    for key in ["Shift+ArrowRight", "Shift+ArrowRight", "ArrowRight", "Shift+ArrowDown", "ArrowDown", "ArrowLeft"]:
        page.keyboard.press(key)
    page.wait_for_selector("#vga-pixel[data-x='8'][data-y='5']")
    for _ in range(3):
        page.keyboard.press("ArrowUp")
    page.keyboard.press("Shift+ArrowUp")
    page.wait_for_selector("#vga-pixel[data-x='8'][data-y='0']")
    addr = VGA_BASE + 0x4000 * (1 + 1 % 2) + 4 * (20 * (0 // 4) + (8 // 4) // 8)
    equal(int(page.get_attribute("#vga-pixel", "data-addr")), addr, "pixel (8, 0) from the keyboard: the word")
    equal(page.input_value("#cycle-input"), before, "the arrow keys on the screen moved the site's cursor")
    check(not page.errors, f"console errors {page.errors}")


def test_vga_mutant_differs_where_check_vga_says(s):
    good, bad = s.runs["t5-nyancat"], s.runs["t5-nyancat-shift"]
    equal(good.test("golden:1")["result"], "pass", "check-vga's golden frame on the SoC")
    equal(bad.test("golden:1")["result"], "fail", "check-vga's golden frame on the VGA_SHIFT model")
    with gzip.open(os.path.join(ROOT, "sim", "tests", "golden", "nyancat-1.ppm.gz")) as f:
        golden = ppm_pixels(f.read())
    _, shifted = bad.frame(1)
    want = sorted(i for i in range(640 * 480) if golden[3 * i:3 * i + 3] != shifted[3 * i:3 * i + 3])
    check(want, "the mutant's frame equals the golden frame")
    page = s.page("t5-nyancat", "vga")
    page.wait_for_selector("#vga-screen")
    page.fill("#vga-compare-path", f"{TMP_URL}/t5-nyancat-shift")
    page.click("#vga-compare-add")
    page.wait_for_selector("#vga-diff-count", timeout=30000)
    equal(int(page.get_attribute("#vga-diff-count", "data-count")), len(want), "the pixels that differ")
    got = page.evaluate("""() => {
        const c = document.getElementById('vga-diff');
        const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data;
        const out = [];
        for (let i = 0; i < c.width * c.height; i++) if (d[4 * i + 3] === 255) out.push(i);
        return out;
    }""")
    equal(got, want, "the diff mask and the pixels in which the mutant's frame differs from the golden one")
    check(not page.errors, f"console errors {page.errors}")


def test_vga_latest_frame_choice_wins(s):
    # frame 2's file comes late; a choice of frame 3 made meanwhile is the one shown
    page = s.page("t5-qrcode", "vga")
    page.wait_for_selector("#vga-screen[aria-label='frame 1']")
    def slow(route):
        time.sleep(1.5)
        route.continue_()
    page.route("**/vga/frame-2.ppm", slow)
    page.select_option("#vga-frame", "2")
    page.select_option("#vga-frame", "3")
    page.wait_for_selector("#vga-screen[aria-label='frame 3']")
    page.wait_for_timeout(2500)
    equal(page.get_attribute("#vga-screen", "aria-label"), "frame 3", "the frame shown after the late read of frame 2")
    check(page.inner_text("#vga-decode").count("reads") == 1, "the decode note of one frame")
    check(not page.errors, f"console errors {page.errors}")


def test_vga_latest_comparison_wins(s):
    # the frames of the first bundle to compare with come late; the comparison asked for meanwhile
    # (the bundle with itself) is the one shown, and the late one does not replace it
    page = s.page("t5-nyancat", "vga")
    page.wait_for_selector("#vga-screen")
    def slow(route):
        time.sleep(1.5)
        route.continue_()
    page.route("**/t5-nyancat-shift/vga/*.ppm", slow)
    page.fill("#vga-compare-path", f"{TMP_URL}/t5-nyancat-shift")
    page.click("#vga-compare-add")
    page.fill("#vga-compare-path", f"{TMP_URL}/t5-nyancat")
    page.click("#vga-compare-add")
    page.wait_for_selector("#vga-diff-count", timeout=30000)
    page.wait_for_timeout(3000)
    message = page.inner_text("#vga-compare-message")
    check(message.startswith(f"comparing with {TMP_URL}/t5-nyancat (") and "shift" not in message,
          f"the message after the late comparison: {message!r}")
    equal(int(page.get_attribute("#vga-diff-count", "data-count")), 0, "the pixels that differ from the frame itself")
    check(not page.errors, f"console errors {page.errors}")


def test_vga_frames_of_another_size_are_refused(s):
    page = s.page("t5-nyancat", "vga")
    page.wait_for_selector("#vga-screen")
    got = page.evaluate("""async () => {
        const { parsePpm } = await import('./lib/vgafb.js');
        const ppm = (w, h) => {
            const head = new TextEncoder().encode(`P6\n${w} ${h}\n255\n`);
            const b = new Uint8Array(head.length + 3 * w * h);
            b.set(head);
            return b;
        };
        const out = {};
        for (const [w, h] of [[640, 480], [320, 240], [640, 481], [641, 480], [2, 1]]) {
            try { const f = parsePpm(ppm(w, h)); out[`${w}x${h}`] = `${f.width}x${f.height}`; }
            catch (e) { out[`${w}x${h}`] = e.message; }
        }
        return out;
    }""")
    equal(got["640x480"], "640x480", "a whole screen")
    for size in ("320x240", "640x481", "641x480", "2x1"):
        w, h = size.split("x")
        equal(got[size], f"a frame of {w} x {h} pixels, not 640 x 480", f"a PPM of {size}")
    check(not page.errors, f"console errors {page.errors}")


def test_qr_showcase_with_its_decode_result(s):
    run = s.runs["t5-qrcode"]
    page = s.page("t5-qrcode", "vga")
    page.wait_for_selector("#vga-screen")
    frames = [e["frame"] for e in run.manifest["vga"]]
    equal(frames, [1, 2, 3, 4, 5, 6], "the six styles")
    for n in frames:
        page.select_option("#vga-frame", str(n))
        page.wait_for_selector(f"#vga-screen[aria-label='frame {n}']")
        decoded = run.test(f"qr-decode:{n}")
        equal((decoded["result"], decoded["detail"]), ("pass", QR_URL), f"frame {n}: the bundle's decode result")
        equal(run.test(f"golden:{n}")["result"], "pass", f"frame {n}: the golden frame")
        text = page.inner_text("#vga-decode")
        check(f'reads "{QR_URL}"' in text and "golden frame: pass" in text, f"frame {n}: the decode note: {text!r}")
    check(not page.errors, f"console errors {page.errors}")


# ---------------------------------------------------------------------------
# The activities of T3 to T5, each answer computed here


def expected_answer(run, q):
    kind, what = q["kind"], q.get("what")
    if kind == "trap":
        cycle = int(q["cycle"])
        after = run.at(cycle + 1)["csr"]
        if what == "mcause":
            return after["mcause"]
        if what == "mepc":
            return after["mepc"]
        if what == "handler":
            return next(r for r in run.records[cycle - run.first + 1:] if r.get("retire"))["retire"]["pc"]
        code = run.at(cycle)["csr"]["cause"] & 0x7fffffff
        k = cycle - run.first
        while (run.records[k - 1]["csr"]["mip"] >> code) & 1:
            k -= 1
        return cycle - run.records[k]["cycle"]
    if kind == "hazard":
        pc, occurrence = int(q["pc"]), int(q["occurrence"])
        visit = decode_visits(run, pc)[occurrence]
        if what == "stalls":
            return sum(r["pipe"]["loadUseStall"] for r in visit)
        return ex_bubbles_after(run, pc)  # the first visit's branch is the one asked about here
    if kind == "uart":
        start, byte = uart_frames(run)[int(q["frame"])]
        if what == "byte":
            return byte
        store = store_of_frame(run, start)
        return store[1] if what == "store-pc" else start - store[0]
    if kind == "pixel":
        n, x, y = int(q["frame"]), int(q["x"]), int(q["y"])
        entry, _ = run.frame(n)
        addr = VGA_BASE + 0x4000 * (1 + n % 2) + 4 * (20 * (y // 4) + (x // 4) // 8)
        cycle, pc, data = last_stores(run.stores, entry["cycle"])[addr]
        return {"addr": addr, "store-pc": pc, "index": (data >> (4 * ((x // 4) % 8))) & 15}[what]
    raise Failure(f"no expected answer for {kind}")


def play(s, bundle, activity, want_kinds):
    run = s.runs[bundle]
    page = s.page(bundle, "activities")
    page.click(f"#activity-{activity} button.start")
    page.wait_for_selector(f"#activity-{activity} form.activity", timeout=30000)
    questions = page.eval_on_selector_all(f"#activity-{activity} .question", "qs => qs.map(q => ({...q.dataset}))")
    check(questions, f"{bundle}, {activity}: no question")
    equal(sorted({q["kind"] for q in questions}), sorted(want_kinds), f"{bundle}, {activity}: the kinds of questions")
    answers = [expected_answer(run, q) for q in questions]
    # wrong answers first: every one refused
    for q, a in zip(questions, answers):
        page.fill(f"#activity-{activity} .question[data-index='{q['index']}'] input", str(a + 1))
    page.click(f"#activity-{activity} button.primary")
    page.wait_for_selector(f"#activity-{activity} .score")
    equal(page.inner_text(f"#activity-{activity} .score"), f"0 of {len(questions)} as recorded.", f"{bundle}, {activity}: wrong answers")
    page.click(f"#activity-{activity} button.start")
    page.wait_for_selector(f"#activity-{activity} form.activity button.primary:not([disabled])")
    for q, a in zip(questions, answers):
        page.fill(f"#activity-{activity} .question[data-index='{q['index']}'] input", hex(a) if a > 9 else str(a))
    page.click(f"#activity-{activity} button.primary")
    page.wait_for_selector(f"#activity-{activity} .score")
    equal(page.inner_text(f"#activity-{activity} .score"), f"{len(questions)} of {len(questions)} as recorded.",
          f"{bundle}, {activity}: the answers computed from the bundle")
    check(not page.errors, f"{bundle}, {activity}: console errors {page.errors}")
    return questions


def test_activity_traps(s):
    for bundle in ("t3-irqtrap",):
        qs = play(s, bundle, "traps", ["trap"])
        check({q["what"] for q in qs} == {"mcause", "mepc", "handler", "delay"}, f"{bundle}: the trap questions {qs}")


def test_activity_hazards(s):
    for _, model in VARIANTS:
        qs = play(s, f"t4-hazards-{model}", "hazards", ["hazard"])
        check({q["what"] for q in qs} == {"stalls", "flush"}, f"{model}: the hazard questions {qs}")


def test_activity_uart(s):
    for bundle in ("t3-uart",):
        play(s, bundle, "uart", ["uart"])


def test_activity_vga(s):
    play(s, "t5-nyancat", "vga", ["pixel"])


TESTS = [test_csr_follows_irqtrap, test_pipeline_counts_equal_hazardspec, test_pipeline_chart_shows_the_flushed_slots,
         test_pipeline_fence_i_is_no_branch, test_pipeline_variants_side_by_side, test_soc_transactions_match_the_stores,
         test_vga_pixels_land_on_their_stores, test_vga_pixel_from_the_keyboard,
         test_vga_mutant_differs_where_check_vga_says, test_vga_latest_frame_choice_wins,
         test_vga_latest_comparison_wins, test_vga_frames_of_another_size_are_refused,
         test_qr_showcase_with_its_decode_result, test_activity_traps,
         test_activity_hazards, test_activity_uart, test_activity_vga]


def main():
    p = argparse.ArgumentParser(description="browser tests of the views of T3 to T5")
    p.add_argument("--bundles", default=os.path.join(ROOT, "build", "web", "view-bundles"),
                   help="the directory of `make web-view-bundles`")
    p.add_argument("-k", default="", help="run only the tests whose name contains this text")
    args = p.parse_args()
    directory = os.path.abspath(args.bundles)
    missing = [b for b in BUNDLES if not os.path.isdir(os.path.join(directory, b))]
    if missing:
        print(f"{directory}: no {', '.join(missing)} (make web-view-bundles)", file=sys.stderr)
        return 2
    if not os.path.isfile(os.path.join(WEB, "map.json")):
        print("web/map.json not found: run make -C web map", file=sys.stderr)
        return 2
    sys.path.insert(0, os.path.join(WEB, "tests"))
    sys.path.insert(0, os.path.join(WEB, "waveform", "tests"))
    from playwright.sync_api import sync_playwright
    from runner import launch
    import browser

    runs = {b: Run(directory, b) for b in BUNDLES}
    if browser.TMP_PREFIX != f"/web/{TMP_URL}/":
        print(f"browser.TMP_PREFIX is {browser.TMP_PREFIX}, not /web/{TMP_URL}/", file=sys.stderr)
        return 2
    out = TMP_OUT
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    for b in BUNDLES:
        shutil.copytree(os.path.join(directory, b), os.path.join(out, b))
    browser.Handler.tmp = out
    server = browser.serve()
    try:
        with sync_playwright() as pw:
            suite = Suite(pw, f"http://127.0.0.1:{server.server_address[1]}", runs)
            suite.run(launch, [t for t in TESTS if args.k in t.__name__])
    finally:
        server.shutdown()
        server.server_close()
        shutil.rmtree(out, ignore_errors=True)
    failed = [n for n, status in suite.results if status != "pass"]
    print(f"views: {len(suite.results) - len(failed)} of {len(suite.results)} tests passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
