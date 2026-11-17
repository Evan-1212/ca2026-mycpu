// SPDX-License-Identifier: MIT
// From a VGA frame back to the stores that drew it. The register map
// and the pixel packing are those of the VGA's documentation
// (5-soc/src/main/scala/mycpu/soc/Vga.scala, `MemMap.Vga`, mmio.h): two
// buffers of 160 x 120 pixels of 4 bits, each shown as a block of 4 x 4 screen
// pixels, pixel (x, y) in word 20 * y + x / 8 at bits 4 * (x % 8) + 3 .. 0,
// a palette of 16 words of 0xRRGGBB at 0x40, and the buffer that is shown
// flips with each swap, so frame N (the first whole frame after the N-th swap,
// sim/main.cpp) shows buffer N mod 2. The stores come from the bundle's
// stores.json (the whole run's retire log, docs/observation.md): the last
// whole-word store to a word up to the cycle in which the frame was complete
// is the one that the frame shows, provided that the program left the word and
// the palette alone while the frame was scanned out (Vga.scala's condition for
// a frame to be what the program made). A store to either in that time makes
// the attribution uncertain, and pixelSource says so.

export const VGA_BASE = 0x20000000;
export const VGA_SIZE = 0x10000;
export const WIDTH = 640;
export const HEIGHT = 480;
export const SCALE = 4;
export const FB_WIDTH = WIDTH / SCALE;
export const FB_HEIGHT = HEIGHT / SCALE;
const WORDS_PER_LINE = FB_WIDTH / 8;
const PALETTE = 0x40;
/** Cycles from a frame's first visible pixel to its last: 479 lines of 800 pixels and 639 pixels, two cycles each (Vga's pixelDiv). */
export const SCAN_CYCLES = (479 * 800 + 639) * 2;
const BUFFERS = [0x4000, 0x8000];

/**
 * A binary PPM (P6, maxval 255) of a whole screen, WIDTH x HEIGHT pixels (what +vga-dump writes,
 * and the size the views index), as {width, height, rgb: Uint8Array of 3 bytes per pixel}.
 */
export function parsePpm(bytes) {
  let pos = 0;
  const space = (b) => b === 0x20 || b === 0x0a || b === 0x0d || b === 0x09;
  const token = () => {
    for (;;) {
      while (pos < bytes.length && space(bytes[pos])) pos++;
      if (bytes[pos] !== 0x23) break;
      while (pos < bytes.length && bytes[pos] !== 0x0a) pos++;
    }
    const s = pos;
    while (pos < bytes.length && !space(bytes[pos])) pos++;
    return String.fromCharCode(...bytes.subarray(s, pos));
  };
  if (token() !== "P6") throw new Error("not a binary PPM (P6)");
  const width = Number(token());
  const height = Number(token());
  const max = Number(token());
  pos++;
  if (!(width > 0 && height > 0 && max === 255) || bytes.length - pos < width * height * 3) {
    throw new Error("a PPM of an unexpected form");
  }
  if (width !== WIDTH || height !== HEIGHT) throw new Error(`a frame of ${width} x ${height} pixels, not ${WIDTH} x ${HEIGHT}`);
  return { width, height, rgb: bytes.subarray(pos, pos + width * height * 3) };
}

/** The color of screen pixel (x, y) of a parsed frame, as 0xRRGGBB. */
export function rgbAt(frame, x, y) {
  const i = 3 * (frame.width * y + x);
  return (frame.rgb[i] << 16) | (frame.rgb[i + 1] << 8) | frame.rgb[i + 2];
}

/** The buffer that frame `n` shows: 0 or 1. */
export function bufferOf(n) {
  return n % 2;
}

/**
 * The state of the VGA's memory at `cycle` from the run's stores ([cycle, pc, addr, bytes, data],
 * in order): for every word the last whole-word store to it (the VGA ignores a store with
 * other strobes), as a Map from the offset to {cycle, pc, data}.
 */
export function wordsAt(stores, cycle) {
  const words = new Map();
  for (const [c, pc, addr, bytes, data] of stores) {
    if (c > cycle) break;
    if (bytes !== 4 || addr < VGA_BASE || addr >= VGA_BASE + VGA_SIZE) continue;
    words.set(addr - VGA_BASE, { cycle: c, pc, data, addr });
  }
  return words;
}

/** The 16 colors of the palette in `words` (0 for one never written). */
export function palette(words) {
  return Array.from({ length: 16 }, (_, i) => (words.get(PALETTE + 4 * i)?.data ?? 0) & 0xffffff);
}

/** The whole-word stores to `addr` in cycles `from` + 1 to `to`. */
export function storesDuring(stores, addr, from, to) {
  return stores.filter(([c, , a, bytes]) => c > from && c <= to && a === addr && bytes === 4);
}

/**
 * What screen pixel (x, y) of frame `n` is made of: the framebuffer pixel (fx, fy), its buffer,
 * the word's address and the bits of the pixel in it, the store that last wrote the word
 * (null if none did), the 4-bit index it holds there, and that index's palette entry and color.
 * With the run's `stores` and the frame's `cycle`, `during` lists the stores to the word or to
 * that palette entry while the frame was scanned out (`uncertain` when there is one).
 */
export function pixelSource(words, n, x, y, stores = null, cycle = null) {
  const fx = Math.floor(x / SCALE);
  const fy = Math.floor(y / SCALE);
  const buffer = bufferOf(n);
  const offset = BUFFERS[buffer] + 4 * (WORDS_PER_LINE * fy + Math.floor(fx / 8));
  const shift = 4 * (fx % 8);
  const store = words.get(offset) ?? null;
  const index = store ? (store.data >>> shift) & 15 : 0;
  const entry = words.get(PALETTE + 4 * index) ?? null;
  const during = stores && cycle !== null
    ? [...storesDuring(stores, VGA_BASE + offset, cycle - SCAN_CYCLES, cycle),
      ...storesDuring(stores, VGA_BASE + PALETTE + 4 * index, cycle - SCAN_CYCLES, cycle)] : [];
  return {
    x, y, fx, fy, buffer, addr: VGA_BASE + offset, bits: [shift + 3, shift], store, index,
    paletteStore: entry, color: (entry?.data ?? 0) & 0xffffff, during, uncertain: during.length > 0,
  };
}

/** The framebuffer of buffer `b` in `words` as 160 x 120 palette indices. */
export function framebuffer(words, b) {
  const fb = new Uint8Array(FB_WIDTH * FB_HEIGHT);
  for (let fy = 0; fy < FB_HEIGHT; fy++) {
    for (let w = 0; w < WORDS_PER_LINE; w++) {
      const data = words.get(BUFFERS[b] + 4 * (WORDS_PER_LINE * fy + w))?.data ?? 0;
      for (let k = 0; k < 8; k++) fb[FB_WIDTH * fy + 8 * w + k] = (data >>> (4 * k)) & 15;
    }
  }
  return fb;
}
