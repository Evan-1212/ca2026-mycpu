// SPDX-License-Identifier: MIT
// The pipeline chart of T4 and T5 from the `pipe` group of a run's
// records (docs/observation.md, "`pipe` (T4, T5)"). Every value comes from a
// record; this file only follows each instruction from stage to stage, by the
// pc that each stage holds and by the `stall` flag of the cycle before:
//
//   - IF (`fetch`): the fetch unit keeps a word in this cycle; it enters the
//     queue at the next edge, and ID takes the queue's oldest entry;
//   - ID, EX, MEM: a stage holds the instruction of the cycle before if it
//     stalled then, else the one that the stage before it held;
//   - an instruction retires in the cycle of its retire record, from MEM, and
//     WB writes its register in the next cycle;
//   - one that leaves a stage and reaches no other was flushed, and so was
//     every word in the queue when the fetch unit was redirected.
//
// It decides nothing that the core decides: which instruction stalls, and why,
// is in the record (`stall`, `loadUseStall`, `flush`).

export const STAGES = ["fetch", "decode", "execute", "memory", "writeback"];
/** FENCE.I: opcode MISC-MEM (0001111) with funct3 001 (Unprivileged spec, "Zifencei"). */
const FENCE_I = 0x100f;

export const LETTERS = { fetch: "F", decode: "D", execute: "E", memory: "M", writeback: "W" };

/** An instruction's way through the pipeline. */
class Instance {
  constructor(id, pc, inst) {
    this.id = id;
    this.pc = pc;
    this.inst = inst;
    this.cells = new Map(); // cycle -> {stage, stall, loadUse}
    this.first = null;
    this.retired = null; // the cycle of its retire record
    this.trapped = false; // its record is a trap
    this.flushed = null; // the last cycle it was in the pipeline, if it was flushed
    this.redirect = null; // what its redirect of the fetch unit was: "branch" (EX or ID), "fence.i", "trap", "mret"
    this.redirectCycle = null;
    this.known = true; // false: it was in the pipeline before the records start
  }

  put(cycle, stage, extra = {}) {
    this.cells.set(cycle, { stage, ...extra });
    if (this.first === null || cycle < this.first) this.first = cycle;
  }

  /** The cycles in which it was in `stage`. */
  cyclesIn(stage) {
    return [...this.cells].filter(([, c]) => c.stage === stage).map(([n]) => n).sort((a, b) => a - b);
  }
}

function pipeOf(r) {
  return r && r.pipe ? r.pipe : null;
}

/**
 * Follows every instruction through the records (consecutive cycles of the pipe group).
 * Returns {instances, byCycle}, where byCycle maps a cycle to {decode, execute, memory} (the
 * instance each stage holds, or null) and instances are in the order they entered the pipeline.
 */
export function follow(records) {
  const instances = [];
  const byCycle = new Map();
  let pending = []; // fetched, not yet in ID, oldest first
  let prev = null; // {record, decode, execute, memory}
  const make = (pc, inst, known = true) => {
    const i = new Instance(instances.length, pc, inst);
    i.known = known;
    instances.push(i);
    return i;
  };
  for (const r of records) {
    const p = pipeOf(r);
    if (!p) throw new Error(`cycle ${r.cycle} has no pipe group`);
    const pp = prev ? pipeOf(prev.record) : null;
    const now = { decode: null, execute: null, memory: null };
    // carried: the instance that `stage` held in the cycle before, if it kept it
    const kept = (stage) => (pp && prev[stage] && pp[stage].stall && p[stage].valid &&
      p[stage].pc === prev[stage].pc ? prev[stage] : null);
    const moved = (from, stage) => (pp && prev[from] && p[stage].valid && p[stage].pc === prev[from].pc ? prev[from] : null);
    if (p.memory.valid) now.memory = kept("memory") ?? moved("execute", "memory") ?? make(p.memory.pc, p.memory.inst, false);
    if (p.execute.valid) now.execute = kept("execute") ?? moved("decode", "execute") ?? make(p.execute.pc, p.execute.inst, false);
    if (p.decode.valid) {
      now.decode = kept("decode");
      if (!now.decode) {
        const k = pending.findIndex((i) => i.pc === p.decode.pc);
        if (k >= 0) {
          for (const skipped of pending.slice(0, k)) skipped.flushed ??= r.cycle - 1;
          now.decode = pending[k];
          pending = pending.slice(k + 1);
        } else {
          now.decode = make(p.decode.pc, p.decode.inst, false); // fetched before the records start
        }
      }
    }
    // what left a stage and reached no other was flushed in the cycle before
    if (prev) {
      const still = new Set([now.decode, now.execute, now.memory].filter(Boolean));
      for (const stage of ["decode", "execute", "memory"]) {
        const i = prev[stage];
        if (i && !still.has(i) && i.retired === null) i.flushed ??= prev.record.cycle;
      }
    }
    for (const stage of ["decode", "execute", "memory"]) {
      const i = now[stage];
      if (i) i.put(r.cycle, stage, { stall: p[stage].stall === 1, loadUse: stage === "decode" && p.loadUseStall === 1 });
    }
    // the retire record of MEM's instruction (an interrupt's record names an instruction that did not execute)
    const rt = r.retire;
    if (rt && now.memory && rt.pc === now.memory.pc && !(rt.trap && rt.trap.cause >= 0x80000000 && rt.inst === 0)) {
      now.memory.retired = r.cycle;
      now.memory.trapped = Boolean(rt.trap);
      if (!rt.trap) now.memory.put(r.cycle + 1, "writeback", { writes: false });
    }
    // a redirect of the fetch unit, and who made it (docs/observation.md: the flush of each stage)
    if (p.fetch.flush) {
      const cause = r.csr && (r.csr.trap || r.csr.mret) ? (r.csr.trap ? "trap" : "mret") : null;
      const who = cause ? now.memory : (p.execute.flush && now.execute ? now.execute : now.decode);
      if (who && who.redirect === null) {
        // ID redirects for a FENCE.I too (T4-07), which is no branch: its word is the one encoding of FENCE.I
        who.redirect = cause ?? (who === now.decode && (who.inst & 0x707f) === FENCE_I ? "fence.i" : "branch");
        who.redirectCycle = r.cycle;
      }
      for (const i of pending) i.flushed ??= r.cycle;
      pending = [];
    }
    if (p.fetch.valid) {
      const i = make(p.fetch.pc, p.fetch.inst);
      i.put(r.cycle, "fetch", {});
      pending.push(i);
    }
    byCycle.set(r.cycle, now);
    prev = { record: r, ...now };
  }
  // a W cell is a register write only where the record of its cycle says so
  const at = new Map(records.map((r) => [r.cycle, r]));
  for (const i of instances) {
    for (const [n, c] of i.cells) {
      if (c.stage === "writeback") c.writes = at.has(n) ? pipeOf(at.get(n)).writeback.valid === 1 : null;
    }
  }
  return { instances, byCycle };
}

/**
 * The hazards of the records, per instruction that was not flushed (an instruction on a path
 * that a branch or a trap canceled is not counted, so that the k-th occurrence of a pc is the
 * same instruction on every variant): `stalls`, the cycles it waited in ID with
 * `loadUseStall` (the hazard unit held it), and for an instruction that redirected the fetch
 * unit as a branch or jump, `flushSlots`: the cycles after it left EX in which EX held no
 * instruction, up to the next one (the bubbles its taken branch cost). null where the records
 * end before the count does.
 */
export function hazards(records, followed = follow(records)) {
  const { instances, byCycle } = followed;
  const last = records.length ? records[records.length - 1].cycle : -1;
  const out = [];
  for (const i of instances) {
    if (i.flushed !== null || (i.cyclesIn("decode").length === 0 && i.cyclesIn("execute").length === 0)) continue;
    const stalls = [...i.cells.values()].filter((c) => c.stage === "decode" && c.loadUse).length;
    let flushSlots = 0;
    if (i.redirect === "branch") {
      const ex = i.cyclesIn("execute");
      if (!ex.length) flushSlots = null;
      else {
        let n = ex[ex.length - 1] + 1;
        while (n <= last && !byCycle.get(n).execute) {
          flushSlots++;
          n++;
        }
        if (n > last) flushSlots = null;
      }
    }
    out.push({ instance: i, pc: i.pc, inst: i.inst, first: i.first, stalls, flushSlots, redirect: i.redirect });
  }
  return out;
}
