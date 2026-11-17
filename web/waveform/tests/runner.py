# SPDX-License-Identifier: MIT
"""Shared parts of the browser tests of web/waveform and web/board: a static
server for web/ and the bundles, one headless Chromium at a time, and a small
test runner that prints each test's time.

The server maps /web/ to the repository's web/ directory, /bundles/ to the
bundles directory given on the command line (make web-bundles) and /extra/ to a
temporary directory for doctored copies of bundles. Nothing else is served.
"""

import argparse
import functools
import http.server
import os
import shutil
import sys
import tempfile
import threading
import time
import traceback

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import sync_playwright

WEB = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ROOT = os.path.dirname(WEB)

# swiftshader gives WebGL without a GPU; the no-WebGL runs turn both WebGL versions off
WEBGL_ARGS = ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"]
NO_WEBGL_ARGS = ["--disable-webgl", "--disable-webgl2", "--disable-3d-apis"]


class Handler(http.server.SimpleHTTPRequestHandler):
    roots = {}

    def translate_path(self, path):
        path = path.split("?", 1)[0].split("#", 1)[0]
        for prefix, root in self.roots.items():
            if path.startswith(prefix):
                rest = os.path.normpath(path[len(prefix):]).lstrip("/")
                full = os.path.realpath(os.path.join(root, rest))
                if full == os.path.realpath(root) or full.startswith(os.path.realpath(root) + os.sep):
                    return full
        return os.path.join(ROOT, "nonexistent-path")

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, *args):
        pass


Handler.extensions_map = dict(Handler.extensions_map, **{".js": "text/javascript", ".json": "application/json"})


class Server:
    def __init__(self, bundles):
        self.extra = tempfile.mkdtemp(prefix="obs-web-")
        handler = type("H", (Handler,), {"roots": {"/web/": WEB, "/bundles/": bundles, "/extra/": self.extra}})
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        shutil.rmtree(self.extra, ignore_errors=True)


def launch(playwright, args, attempts=6):
    """Launches Chromium; backs off and retries while the host is out of inotify watches."""
    for attempt in range(attempts):
        try:
            return playwright.chromium.launch(headless=True, args=args)
        except PlaywrightError as e:
            text = str(e)
            if attempt + 1 == attempts or not any(s in text for s in ("inotify", "ENOSPC", "Too many open files")):
                raise
            delay = 5 * (attempt + 1)
            print(f"  launch failed ({text.splitlines()[0][:80]}), retrying in {delay} s", flush=True)
            time.sleep(delay)
    raise RuntimeError("unreachable")


class Suite:
    """Runs test functions test_*(ctx) with one browser at a time; ctx gives page()."""

    def __init__(self, name, server, playwright):
        self.name = name
        self.server = server
        self.playwright = playwright
        self.browser = None
        self.args = None
        self.results = []

    def use_browser(self, args):
        if self.browser is not None and self.args == args:
            return
        self.close_browser()
        self.browser = launch(self.playwright, args)
        self.args = args

    def close_browser(self):
        if self.browser is not None:
            self.browser.close()
            self.browser = None

    def page(self, url, view="waveform", init_script=None, wait=True):
        context = self.browser.new_context(viewport={"width": 1400, "height": 900})
        self._contexts.append(context)
        if init_script:
            context.add_init_script(init_script)
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.errors = errors
        page.goto(f"{self.server.url}/web/waveform/tests/standalone.html?bundle={url}&view={view}")
        if wait:
            page.wait_for_function("document.body.dataset.host === 'ready' || document.body.dataset.host === 'error'",
                                   timeout=60000)
            if page.evaluate("document.body.dataset.host") != "ready":
                raise AssertionError("the test host failed: " + page.inner_text("#view"))
        return page

    def run(self, tests, args):
        self.use_browser(args)
        for test in tests:
            self._contexts = []
            t0 = time.perf_counter()
            try:
                test(self)
                status, detail = "pass", ""
            except Exception as e:  # a failed test is reported and the suite goes on
                status, detail = "FAIL", "".join(traceback.format_exception_only(type(e), e)).strip()
                # the line of the test that failed, without the frames inside Playwright
                where = [f for f in traceback.extract_tb(e.__traceback__) if "site-packages" not in f.filename]
                if where:
                    detail += f"\n       at {os.path.basename(where[-1].filename)}:{where[-1].lineno}: {where[-1].line}"
            finally:
                for c in self._contexts:
                    c.close()
            ms = (time.perf_counter() - t0) * 1000
            self.results.append((test.__name__, status, ms, detail))
            print(f"  {status:4} {test.__name__} ({ms:.0f} ms)" + (f"\n       {detail}" if detail else ""), flush=True)

    def summary(self):
        failed = [r for r in self.results if r[1] != "pass"]
        total = sum(r[2] for r in self.results) / 1000
        print(f"{self.name}: {len(self.results) - len(failed)} of {len(self.results)} tests passed in {total:.1f} s")
        return 1 if failed else 0


def main(name, groups):
    """groups: [(browser args, [tests])], run in order, one browser at a time."""
    p = argparse.ArgumentParser(description=f"browser tests of {name}")
    p.add_argument("--bundles", default=os.path.join(ROOT, "build", "web", "bundles"),
                   help="the directory of `make web-bundles`")
    p.add_argument("-k", default="", help="run only the tests whose name contains this text")
    args = p.parse_args()
    if not os.path.isdir(args.bundles):
        print(f"{args.bundles}: no bundles (make web-bundles)", file=sys.stderr)
        return 2
    server = Server(os.path.abspath(args.bundles))
    code = 1
    try:
        with sync_playwright() as pw:
            suite = Suite(name, server, pw)
            suite.bundles = os.path.abspath(args.bundles)
            try:
                for browser_args, tests in groups:
                    chosen = [t for t in tests if args.k in t.__name__]
                    if chosen:
                        suite.run(chosen, browser_args)
            finally:
                suite.close_browser()
            code = suite.summary()
    finally:
        server.close()
    return code


@functools.lru_cache(maxsize=None)
def bundle_records(path):
    """Every cycle record of a bundle, in order."""
    import json
    with open(os.path.join(path, "manifest.json")) as f:
        manifest = json.load(f)
    records = []
    for c in manifest["chunks"]:
        with open(os.path.join(path, c["file"])) as f:
            records += json.load(f)["cycles"]
    return manifest, records
