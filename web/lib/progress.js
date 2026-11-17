// SPDX-License-Identifier: MIT
// The student's progress, kept in this browser only and never used for
// grading. Three separate states per unit: "read" (set by the student),
// "activity done" (one entry per activity finished), and "has test results"
// (the results of the last bundle imported for the unit). Storage can be
// missing or refuse writes (a private window, blocked site data, a full
// quota): then the progress lives in memory for this page only, and
// `problem` says so.

import { testCounts } from "./format.js";

const KEY = "mycpu-web-progress-v1";

function openStorage() {
  try {
    const storage = globalThis.localStorage;
    const probe = KEY + ":probe";
    storage.setItem(probe, "1");
    storage.removeItem(probe);
    return storage;
  } catch {
    return null;
  }
}

function empty() {
  return { version: 1, units: {} };
}

export class Progress extends EventTarget {
  constructor(storage = openStorage()) {
    super();
    this.storage = storage;
    this.problem = storage ? "" : "this browser does not let the page store data, so progress lasts until the page is closed";
    this.data = empty();
    if (!storage) return;
    try {
      const text = storage.getItem(KEY);
      if (text) {
        const parsed = JSON.parse(text);
        if (parsed && parsed.version === 1 && parsed.units && typeof parsed.units === "object") this.data = parsed;
        else this.problem = "the stored progress had an unknown form and was set aside";
      }
    } catch {
      this.problem = "the stored progress could not be read and was set aside";
    }
  }

  unit(id) {
    const u = this.data.units[id] ?? {};
    return { read: u.read === true, activities: { ...(u.activities ?? {}) }, tests: u.tests ?? null };
  }

  update(id, change) {
    const u = (this.data.units[id] ??= {});
    change(u);
    if (this.storage) {
      try {
        this.storage.setItem(KEY, JSON.stringify(this.data));
      } catch {
        this.problem = "the browser refused to store progress; it lasts until the page is closed";
      }
    }
    this.dispatchEvent(new CustomEvent("change", { detail: { unit: id } }));
  }

  setRead(id, read) {
    this.update(id, (u) => { u.read = Boolean(read); });
  }

  setActivity(id, activity, result) {
    this.update(id, (u) => { (u.activities ??= {})[activity] = { correct: result.correct, total: result.total }; });
  }

  /** The test results of an imported bundle of the unit (manifest.tests), with where they came from. */
  setTests(id, header, tests) {
    this.update(id, (u) => {
      u.tests = { program: String(header.program), commit: String(header.commit), ...testCounts(tests) };
    });
  }
}
