// SPDX-License-Identifier: MIT
// Element construction without innerHTML: text from a bundle or a map is
// always set as text, so no value can become markup.

import { safePath } from "./bundle.js";

const SVG_NS = "http://www.w3.org/2000/svg";

function build(el, attrs, children) {
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key === "dataset") {
      Object.assign(el.dataset, value);
    } else if (key.startsWith("on") && typeof value === "function") {
      el.addEventListener(key.slice(2), value);
    } else {
      el.setAttribute(key, value === true ? "" : String(value));
    }
  }
  return append(el, children);
}

/** Appends children (nodes, or anything else as text; nested arrays flattened; null, undefined and false skipped). */
function append(el, children) {
  for (const child of [].concat(children ?? []).flat(Infinity)) {
    if (child === undefined || child === null || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

/** An HTML element: h("a", {href: "#", dataset: {frame: 3}}, ["text", other]). */
export function h(tag, attrs, children) {
  return build(document.createElement(tag), attrs, children);
}

/** An SVG element. */
export function s(tag, attrs, children) {
  return build(document.createElementNS(SVG_NS, tag), attrs, children);
}

/** Removes every child of el. */
export function clear(el) {
  el.replaceChildren();
  return el;
}

/**
 * The attributes of an element that acts as a button: focusable, run by a click and by Enter or
 * Space when it has the focus. h("tr", {...attrs, ...pressable(fn)}, ...).
 */
export function pressable(fn) {
  return {
    tabindex: 0,
    onclick: fn,
    onkeydown: (e) => {
      if (e.key !== "Enter" && e.key !== " ") return;
      e.preventDefault();
      fn(e);
    },
  };
}

/** Replaces the children of el with children (as h takes them). */
export function replace(el, children) {
  return append(clear(el), children);
}

/** A link into the pinned chisel-bootcamp repository (`repo` from map.json, checked by the site). */
export function bootcampLink(repo, file, text) {
  return repo ? h("a", { href: `${repo.url}/blob/${repo.commit}/${encodeURI(file)}`, rel: "noopener" }, [text])
    : h("span", {}, [text]);
}

/**
 * A link to a file of the repository. The site is served from the
 * repository's root (web/README.md), so a path of the tree is `../<path>`
 * from web/. Only relative paths without `..` are linked.
 */
export function repoLink(path, text) {
  return safePath(path) ? h("a", { href: "../" + path, class: "path" }, [text ?? path]) : h("code", {}, [path]);
}
