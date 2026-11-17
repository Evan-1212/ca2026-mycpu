// SPDX-License-Identifier: MIT
// The CSR file and the traps of a run, from the `csr` group and the
// retire records (docs/observation.md, "`csr` (T3 on)"). The names of the
// causes and of the mstatus, mie and mip bits are those of the Privileged
// spec (§3.1.6, §3.1.9, §3.1.15); everything else is a recorded value.

const INTERRUPT = 0x80000000;

const INTERRUPTS = { 3: "machine software interrupt", 7: "machine timer interrupt", 11: "machine external interrupt" };
const EXCEPTIONS = {
  0: "instruction address misaligned", 1: "instruction access fault", 2: "illegal instruction", 3: "breakpoint",
  4: "load address misaligned", 5: "load access fault", 6: "store/AMO address misaligned", 7: "store/AMO access fault",
  11: "environment call from M-mode",
};

/** The interrupt lines of mip and mie: [bit, short name]. */
export const LINES = [[3, "MSI"], [7, "MTI"], [11, "MEI"]];

/** Whether an mcause value is an interrupt. */
export function isInterrupt(cause) {
  return cause >= INTERRUPT;
}

/** The name of an mcause value, as the Privileged spec's table of causes has it. */
export function causeName(cause) {
  const code = cause & 0x7fffffff;
  const name = isInterrupt(cause) ? INTERRUPTS[code] : EXCEPTIONS[code];
  return `${isInterrupt(cause) ? "interrupt" : "exception"} ${code}${name ? ` (${name})` : ""}`;
}

/** The fields of mstatus that this hart has: MIE (bit 3), MPIE (bit 7), MPP (bits 12:11). */
export function mstatusFields(v) {
  return { MIE: (v >> 3) & 1, MPIE: (v >> 7) & 1, MPP: (v >> 11) & 3 };
}

/** mtvec as BASE and MODE (0 direct, 1 vectored). */
export function mtvecFields(v) {
  return { base: (v & ~3) >>> 0, mode: v & 3 };
}

/** The interrupt lines that are set in a mip or mie value, by name. */
export function lineNames(v) {
  return LINES.filter(([bit]) => (v >> bit) & 1).map(([, name]) => name);
}

/** The last cycle at or before `cycle` in which bit `bit` of csr.mip rose (0 in the record before, 1 in this one). */
function riseOf(records, index, field, bit) {
  for (let k = index; k > 0; k--) {
    const now = (records[k].csr[field] >> bit) & 1;
    const before = (records[k - 1].csr[field] >> bit) & 1;
    if (!now) return null;
    if (!before) return records[k].cycle;
  }
  return null;
}

/** The last cycle at or before records[index] in which csr.irq became 1. */
function irqRise(records, index) {
  for (let k = index; k > 0; k--) {
    if (!records[k].csr.irq) return null;
    if (!records[k - 1].csr.irq) return records[k].cycle;
  }
  return null;
}

/**
 * Every trap of the records (consecutive cycles with the csr group): the cycle it is taken
 * (csr.trap), its cause and the record of that cycle (`pc` is mepc: the instruction that traps,
 * or the one an interrupt comes before), the CSRs in the cycle after it (`mepc`, `mcause`,
 * `mstatus`, null if the records end), and the handler's first instruction: the next retire
 * record (`handler` {cycle, pc}, null if the records end first). For an interrupt, `lineRise`
 * is the cycle its line rose in mip and `irqRise` the cycle csr.irq became 1 (each null if that
 * was before the records), and `wfi` whether a WFI waited in the cycle before the trap.
 */
export function traps(records) {
  const out = [];
  for (let k = 0; k < records.length; k++) {
    const r = records[k];
    if (!r.csr.trap) continue;
    const after = records[k + 1] ?? null;
    let handler = null;
    for (let j = k + 1; j < records.length; j++) {
      if (records[j].retire) {
        handler = { cycle: records[j].cycle, pc: records[j].retire.pc };
        break;
      }
    }
    const cause = r.csr.cause;
    const code = cause & 0x7fffffff;
    out.push({
      cycle: r.cycle,
      cause,
      interrupt: isInterrupt(cause),
      pc: r.retire ? r.retire.pc : null,
      tval: r.retire && r.retire.trap ? r.retire.trap.tval : null,
      record: r.retire ?? null,
      mepc: after ? after.csr.mepc : null,
      mcause: after ? after.csr.mcause : null,
      mstatus: after ? after.csr.mstatus : null,
      handler,
      lineRise: isInterrupt(cause) ? riseOf(records, k, "mip", code) : null,
      irqRise: isInterrupt(cause) ? irqRise(records, k) : null,
      wfi: k > 0 ? records[k - 1].csr.wfi === 1 : false,
    });
  }
  return out;
}
