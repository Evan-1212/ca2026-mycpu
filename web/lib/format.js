// SPDX-License-Identifier: MIT
// How every view prints a field value. web/tests/ applies the same rule to
// the bundle's numbers and compares the result with the page's text.

/**
 * A field value as text: a 1-bit field as 0 or 1, a field of 2 to 5 bits in
 * decimal (register numbers, selects, sizes), a wider one in hexadecimal with
 * one digit per 4 bits.
 */
export function formatValue(value, bits) {
  if (value === undefined || value === null) return "–";
  if (bits <= 5) return String(value);
  return hex(value, Math.ceil(bits / 4));
}

/**
 * A negative 32-bit integer (the result of a bitwise operator) as the unsigned value of its bits;
 * any other value as it is, so that a number wider than 32 bits (a prediction typed in an
 * activity) is printed whole rather than cut to its low 32 bits.
 */
function unsigned(value) {
  return value < 0 ? value >>> 0 : value;
}

/** A value as 0x and `digits` hexadecimal digits (more if it needs them). */
export function hex(value, digits = 8) {
  return "0x" + unsigned(value).toString(16).padStart(digits, "0");
}

/** A PC or address as 8 hexadecimal digits, as the retire log prints it. */
export function hex8(value) {
  return unsigned(value).toString(16).padStart(8, "0");
}

/** A 24-bit color as 6 hexadecimal digits, without #. */
export function hex6(rgb) {
  return rgb.toString(16).padStart(6, "0");
}

/** The retire log's record line (sim/main.cpp; scripts/obslib.py, retire_line) of a retire object. */
export function retireLine(r) {
  const mem = r.mem ? `${r.mem.write ? "w" : "r"}:${hex8(r.mem.addr)}:${r.mem.bytes}:${hex8(r.mem.data)}` : "-";
  const csr = r.csr ? `${r.csr.addr.toString(16).padStart(3, "0")}:${hex8(r.csr.wdata)}` : "-";
  const trap = r.trap ? `${hex8(r.trap.cause)}:${hex8(r.trap.tval)}` : "-";
  const mip = r.mip ? hex8(r.mip) : "-";
  return `pc=${hex8(r.pc)} inst=${hex8(r.inst)} next=${hex8(r.next)} rd=${r.rd} val=${hex8(r.val)} ` +
    `mem=${mem} csr=${csr} trap=${trap} halt=${r.halt} mip=${mip}`;
}

/** {pass, fail}: how many of a bundle's test results (manifest.tests) passed and failed. */
export function testCounts(tests) {
  const results = tests?.results ?? [];
  return {
    pass: results.filter((r) => r.result === "pass").length,
    fail: results.filter((r) => r.result === "fail").length,
  };
}
