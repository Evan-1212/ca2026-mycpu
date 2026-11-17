// SPDX-License-Identifier: MIT
// DOM helpers of the waveform view and the board. Text, including every string
// that comes from a bundle, goes in through text nodes and attributes, never
// through HTML parsing.

import { h, replace } from '../lib/dom.js';

export { hex } from '../lib/format.js';

/**
 * el('button', {class: 'x', dataset: {frame: 3}, onclick: f}, 'text', child): lib/dom.js's h
 * with the children as arguments.
 */
export function el(tag, attrs = {}, ...children) {
  return h(tag, attrs, children);
}

/** Replaces the children of e. */
export function setChildren(e, ...children) {
  replace(e, children);
}

/** A stylesheet of the view's own directory, inside the view's container. */
export function styleLink(href) {
  return el('link', { rel: 'stylesheet', href: href.toString() });
}

/** localStorage, or null where it is not available (private mode, blocked, file://). */
function storage() {
  try {
    const s = window.localStorage;
    const probe = '__mycpu_obs_probe__';
    s.setItem(probe, probe);
    s.removeItem(probe);
    return s;
  } catch {
    return null;
  }
}

/** The JSON stored under key, or fallback. */
export function loadSetting(key, fallback) {
  const s = storage();
  if (!s) return fallback;
  try {
    const v = s.getItem(key);
    return v === null ? fallback : JSON.parse(v);
  } catch {
    return fallback;
  }
}

/** Stores value under key; false when it could not be stored. */
export function saveSetting(key, value) {
  const s = storage();
  if (!s) return false;
  try {
    s.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

/** Bytes as a string of two hex digits each (the decoded bytes in data attributes). */
export function bytesHex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** A byte as the terminal shows it: printable ASCII, else an escape. */
export function charLabel(byte) {
  if (byte === 0x0a) return '\\n';
  if (byte === 0x0d) return '\\r';
  if (byte === 0x09) return '\\t';
  if (byte >= 0x20 && byte < 0x7f) return String.fromCharCode(byte);
  return '\\x' + byte.toString(16).padStart(2, '0');
}
