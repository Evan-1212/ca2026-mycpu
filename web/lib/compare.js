// SPDX-License-Identifier: MIT
// The controls with which a view opens a second bundle to compare with the open one
// (the pipeline's variants, the VGA's frames): the path of a served bundle, a
// directory to import, and a message line. The newest request wins: one that
// finishes after a later one was asked for, or after the view unmounted, does nothing.

import { BundleError, openBundle, filesSource, urlSource } from "./bundle.js";
import { h } from "./dom.js";

/**
 * {actions, message}: the elements to place. `prefix` makes the ids (`<prefix>-compare-path`,
 * `-add`, `-files`, `-message`); `addLabel` is the text of the path's button; `failed` begins
 * the message of a request that failed ("not added"). `header` is the open bundle's: the other
 * one must be of its task and have run its program (the same ELF). `isCurrent()` is false once
 * the view has unmounted. `use(other, source)` reads what it needs of the other bundle (an api of
 * lib/bundle.js) and resolves with a function that applies it and returns the message; that
 * function runs only for the newest request.
 */
export function compareControls({ prefix, addLabel, failed, header, isCurrent, use }) {
  const message = h("p", { class: "note", id: `${prefix}-compare-message`, "aria-live": "polite" });
  const path = h("input", { type: "text", id: `${prefix}-compare-path`, placeholder: "examples/.../bundle",
    autocomplete: "off", "aria-label": "path of a served bundle" });
  const files = h("input", { type: "file", multiple: true, webkitdirectory: true, hidden: true, id: `${prefix}-compare-files` });
  let request = null;
  const start = async (makeSource) => {
    const mine = {};
    request = mine; // a request that cannot even start overtakes the one before it
    const latest = () => isCurrent() && request === mine;
    try {
      const source = makeSource();
      message.textContent = "reading...";
      const other = await openBundle(source, { expectTask: header.task });
      if (other.header.elf_sha256 !== header.elf_sha256) {
        throw new BundleError(`it ran another program (${other.header.program}, ELF ${String(other.header.elf_sha256).slice(0, 12)}) ` +
          `than the open bundle (${header.program}, ELF ${String(header.elf_sha256).slice(0, 12)})`);
      }
      if (!latest()) return; // overtaken while the manifest was read: its chunks or frames are not read
      const apply = await use(other, source);
      if (latest()) message.textContent = apply();
    } catch (e) {
      if (latest()) message.textContent = `${failed}: ${e.message}`;
    }
  };
  files.addEventListener("change", () => {
    if (!files.files.length) return;
    start(() => filesSource(files.files)); // filesSource copies the list before start's first await
    files.value = ""; // so that choosing the same directory again (a bundle made anew) is a change
  });
  const actions = h("div", { class: "actions" }, [path,
    h("button", { type: "button", id: `${prefix}-compare-add`, onclick: () => start(() => urlSource(path.value.trim())) }, [addLabel]),
    h("button", { type: "button", onclick: () => files.click() }, ["import a bundle directory"]), files]);
  return { actions, message };
}
