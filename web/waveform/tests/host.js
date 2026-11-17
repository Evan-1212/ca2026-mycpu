// SPDX-License-Identifier: MIT
// A minimal host for the browser tests of web/waveform and web/board: the site's
// own api (web/lib/bundle.js, openBundle, web/README.md "The view contract") over
// one bundle at any URL, with the two views and no router. It records the links
// and counts the listeners on the cursor and the link handlers, so that a test
// can check that unmount() removed every one.

import { BundleError, openBundle } from '../../lib/bundle.js';

const VIEWS = {
  waveform: new URL('../index.js', import.meta.url),
  board: new URL('../../board/index.js', import.meta.url),
};

/** A served bundle at any URL (the site's urlSource takes paths below web/ only). */
function source(bundleBase) {
  const base = new URL(bundleBase, location.href);
  return {
    label: bundleBase,
    imported: false,
    async read(name) {
      const r = await fetch(new URL(name, base));
      if (!r.ok) throw new BundleError(`${name}: HTTP ${r.status}`);
      return new Uint8Array(await r.arrayBuffer());
    },
  };
}

export async function createHost(bundleBase, container) {
  let current = null;
  let currentName = null;
  const host = { links: [], handlers: new Set(), view: () => currentName };
  const api = await openBundle(source(bundleBase), { navigate: (name) => host.show(name) });

  const cursor = api.cursor;
  cursor.listeners = 0;
  const add = cursor.addEventListener.bind(cursor);
  const remove = cursor.removeEventListener.bind(cursor);
  cursor.addEventListener = (...args) => { cursor.listeners++; add(...args); };
  cursor.removeEventListener = (...args) => { cursor.listeners--; remove(...args); };

  const on = api.on;
  api.on = (kind, handler) => {
    const off = on(kind, handler);
    host.handlers.add(off);
    return () => { host.handlers.delete(off); off(); };
  };
  on('link', (m) => host.links.push(m));

  Object.assign(host, { api, manifest: api.manifest, cursor });
  host.show = async (name) => {
    if (!VIEWS[name]) throw new Error(`no view ${name}`);
    if (current) current.unmount();
    const mod = await import(VIEWS[name]);
    current = mod;
    currentName = name;
    container.dataset.view = name;
    await mod.mount(container, api);
  };
  host.unmount = () => {
    if (current) current.unmount();
    current = null;
    currentName = null;
  };
  return host;
}
