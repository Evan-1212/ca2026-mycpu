// SPDX-License-Identifier: MIT
// UART decoding of a recorded transmit line: 8N1 frames, a start bit
// of 0, eight data bits LSB first and a stop bit of 1, each `divider` cycles
// long. The sampling is that of the harness's decoder (sim/uart.cpp), so a
// window that begins at cycle 0 decodes to the bytes of the run's +uart-log:
// the start bit is the first cycle in which the line is 0, and bit k
// (0 = start, 1..8 = data, 9 = stop) is sampled in cycle
// start + floor(divider / 2) + k * divider.

function frameAt(txd, start, divider, half) {
  const samples = [];
  for (let k = 0; k < 10; k++) samples.push(start + half + k * divider);
  if (samples[9] >= txd.length) return null; // the frame runs past the window
  let byte = 0;
  for (let k = 0; k < 8; k++) if (txd[samples[k + 1]]) byte |= 1 << k;
  return { start, samples, byte, glitch: txd[samples[0]] === 1, ok: txd[samples[9]] === 1 };
}

/** The clean frames that decode from a 0 at `start`, until an error, 8 bit times of idle or the end. */
function cleanFrames(txd, start, divider, half) {
  let count = 0;
  let i = start;
  for (;;) {
    const f = frameAt(txd, i, divider, half);
    if (!f || f.glitch || !f.ok) return count;
    count++;
    i = f.samples[9] + 1;
    const idle = i;
    while (i < txd.length && txd[i]) i++;
    if (i >= txd.length || i - idle >= 8 * divider) return count;
  }
}

/** Where the decoding of a window that may begin inside a frame starts: {at, searched}. */
function findFirstStart(txd, divider, half) {
  const n = txd.length;
  const limit = Math.min(n, 18 * divider);
  let best = null;
  let bestCount = 0;
  let high = 0;
  for (let i = 0; i < n; i++) {
    if (txd[i]) { high++; continue; }
    if (high >= 8 * divider) return best !== null ? { at: best, searched: true } : { at: i, searched: false };
    if (i < limit && (i === 0 || txd[i - 1])) {
      const c = cleanFrames(txd, i, divider, half);
      if (c > bestCount) { best = i; bestCount = c; }
    }
    high = 0;
    if (i >= limit && best !== null) break;
  }
  return best !== null ? { at: best, searched: true } : { at: n, searched: false };
}

/**
 * Decodes txd (a typed array of 0/1, one value per cycle from cycle `first`).
 * Returns {frames, searched}, frames {start, end, byte, ok, samples}: start is the first cycle of
 * the start bit, end the cycle in which the stop bit was sampled, ok false for
 * a frame whose stop bit was 0 (a framing error; its byte is not output).
 * samples are the cycles of the ten samples.
 *
 * `synced` says that the line is idle at the window's first cycle (true for a
 * window that begins at cycle 0, which follows reset). Otherwise the first
 * cycle may lie inside a frame, and the first start bit is searched for among
 * the falling edges of the first 18 bit times (a frame and the 8 bit times of
 * idle line after which a 0 can only be a start bit): the edge from which the
 * most frames decode without a framing error, the earliest of those that tie.
 * `searched` is then true: for data that looks like shifted
 * frames the search can choose a wrong edge, which a window from cycle 0
 * never needs.
 */
export function decodeUart(txd, first, divider, { synced = true } = {}) {
  if (!(divider >= 1)) throw new Error(`UART divider ${divider} is not a positive integer`);
  const half = Math.floor(divider / 2);
  const frames = [];
  const n = txd.length;
  const lock = synced ? { at: 0, searched: false } : findFirstStart(txd, divider, half);
  let i = lock.at;
  while (i < n) {
    if (txd[i]) {
      i++;
      continue;
    }
    const f = frameAt(txd, i, divider, half);
    if (!f) break;
    if (f.glitch) { // the start bit is 1 in its middle: not a frame, as the harness treats it
      i = f.samples[0];
      continue;
    }
    frames.push({ start: first + i, end: first + f.samples[9], byte: f.byte, ok: f.ok,
      samples: f.samples.map((c) => first + c) });
    i = f.samples[9] + 1;
    // after a framing error the harness waits for the line to return to 1
    if (!f.ok) while (i < n && !txd[i]) i++;
  }
  return { frames, searched: lock.searched };
}

/**
 * The UART decoding of a trace's uart.txd, as both views show it: the
 * divider (chooseDivider) with {frames, searched}; null without the field.
 * A window from cycle 0 follows reset, so its line starts idle.
 */
export function decodeTrace(trace, header) {
  if (!trace.has('uart.txd')) return null;
  const txd = trace.values.get('uart.txd');
  const div = chooseDivider(header, txd);
  const decoded = div.divider ? decodeUart(txd, trace.first, div.divider, { synced: trace.first === 0 })
    : { frames: [], searched: false };
  return Object.assign(div, decoded);
}

/** The bytes of the frames without framing errors. */
export function frameBytes(frames) {
  return Uint8Array.from(frames.filter((f) => f.ok).map((f) => f.byte));
}

/** The frames whose stop bit has been sampled by cycle n (the terminal at cursor n). */
export function framesUpTo(frames, n) {
  return frames.filter((f) => f.end <= n);
}

function gcd(a, b) {
  while (b) [a, b] = [b, a % b];
  return a;
}

/**
 * The bit time measured from the line: the greatest common divisor of the
 * lengths of its complete low pulses (each a start bit plus the data bits of 0
 * that follow it, so a multiple of the bit time). null without a complete low
 * pulse. Equal to the header's divider, or a multiple of it when every pulse
 * of the run is longer than one bit.
 */
export function measureBitTime(txd) {
  let g = 0;
  let pulses = 0;
  let fall = -1;
  for (let i = 1; i < txd.length; i++) {
    if (txd[i - 1] && !txd[i]) fall = i;
    else if (!txd[i - 1] && txd[i] && fall >= 0) {
      g = gcd(g, i - fall);
      pulses++;
      fall = -1;
    }
  }
  return pulses ? { bitTime: g, pulses } : null;
}

/**
 * The divider to decode with and what the line says about it. The header's
 * uart_divider is an elaboration parameter of the model, so it is used when
 * present; the measurement is a cross-check.
 */
export function chooseDivider(header, txd) {
  const measured = measureBitTime(txd);
  const fromHeader = Number.isInteger(header && header.uart_divider) ? header.uart_divider : null;
  const divider = fromHeader !== null ? fromHeader : measured ? measured.bitTime : null;
  let agrees = null;
  if (fromHeader !== null && measured) agrees = measured.bitTime % fromHeader === 0;
  return { divider, fromHeader, measured, agrees };
}
