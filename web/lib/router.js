// SPDX-License-Identifier: MIT
// When the router has settled: no route is running and no hash change that
// the site made is still waiting for its `hashchange`. `api.navigate(view)`
// resolves then (web/README.md, "The view contract"), so that a view that
// awaits it can send a link to the view it opened. The site's `hashchange`
// handler stays the one entry that runs a route for a changed hash; this
// file only counts and waits.

export class RouteTracker {
  #token = 0; // the newest route
  #running = false; // whether the newest route has not finished yet
  #pending = []; // the URLs of the hash changes made by setHash whose hashchange has not fired yet
  #waiters = [];

  /**
   * Sets the hash of `where` (window.location by default) and, when the hash changed, waits
   * for the `hashchange` of that change. Returns whether it changed.
   */
  setHash(hash, where = globalThis.location) {
    const before = where.href;
    where.hash = hash;
    if (where.href === before) return false;
    this.#pending.push(where.href);
    return true;
  }

  /**
   * Called by the hashchange handler, with the event's newURL, before it runs the route. A hash
   * change that setHash did not make (a link, the history) is not one that a navigate waits for.
   */
  hashChanged(newURL) {
    const i = this.#pending.indexOf(newURL);
    if (i >= 0) this.#pending.splice(i, 1);
  }

  /**
   * Runs route(token) as the newest route, superseding every older one; the router has not
   * settled until it has finished, returned or thrown, unless a newer route has started meanwhile.
   */
  async run(route) {
    const token = ++this.#token;
    this.#running = true;
    try {
      return await route(token);
    } finally {
      if (token === this.#token) {
        this.#running = false;
        this.#flush();
      }
    }
  }

  /** Whether the route of `token` is still the newest one. */
  current(token) {
    return token === this.#token;
  }

  /** A promise resolved once the router has settled (at once if it has). */
  settled() {
    return new Promise((resolve) => {
      this.#waiters.push(resolve);
      this.#flush();
    });
  }

  #flush() {
    if (this.#running || this.#pending.length) return;
    for (const resolve of this.#waiters.splice(0)) resolve();
  }
}
