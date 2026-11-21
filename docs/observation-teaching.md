<!-- SPDX-License-Identifier: MIT -->
# Teaching with the Observation Interface

Notes for instructors on the web observation interface (`web/`, [web/README.md](../web/README.md)):
which view and activity serves which notebook of bootcamp Module 5 and which task, where each
fits in a lab session, and what students must be told about its limits. The data behind every
view is defined in [observation.md](observation.md).

The site replays a recorded Verilator run of an RTL design. A student opens a shipped example,
or makes a bundle from their own design with `make obs PROG=<program>` in a task directory and
imports it. Every number on the screen is a probe field, a retire record, a frame that the
harness rebuilt from the video signals, or a store of the run's retire log; nothing is
simulated in the browser.

## Views

| View | Tasks | Module 5 notebooks | What it shows |
|------|-------|--------------------|---------------|
| Datapath | T1, T2 | 5.2, 5.4 (T2: 2.6 for the divider's handshake) | the single-cycle datapath as a drawing, every wire with its value at the cursor; T2 adds the multiplier, the divider and the PC held while `div.busy` |
| Pipeline | T4, T5 | 5.6 (stages and flushes), 5.7 (forwarding) | the five stages at the cursor; a pipeline chart (instructions by cycles) with stalls, flushes and bubbles in EX; the hazards of the window (cycles held in ID by the hazard unit, bubbles after a taken branch or jump); bundles of the other variants side by side |
| SoC bus | T5 | 5.8 (a bus and a UART) | the SoC's block diagram with the memory map, the five AXI4-Lite channels of the data master at the cursor, and the transactions of the window on their slaves |
| CSRs and traps | T3, T4, T5 | none (Privileged spec chapter 3) | the CSRs at the cursor, every trap of the window with its cause, `mepc` and the handler's first instruction, and the path of an interrupt from its line in `mip` to the handler |
| VGA | T5 | none (T5's VGA, `Vga.scala`) | a frame rebuilt from hsync, vsync and rgb, the framebuffer that the run's stores wrote, and for a chosen pixel the word and the store behind it; a second design (the `VGA_SHIFT=1` model of `make check-vga`) next to it, with the pixels that differ; for the QR showcase the decoder's text |
| Waveforms | every task | 2.6 (ChiselSim, waveforms) | every probe field as a waveform, two cursors, a trigger, a UART decoder that traces a character back to its store, and AXI4-Lite pairing on T5 |
| Virtual board | every task | 5.8 | a virtual teaching board: its parts, a UART terminal, a VGA screen and probes that open the waveform |
| Retire records | every task | 5.5 (the tests), 5.4 (comparing with the golden model) | the retire records in the form of the retire log |
| Activities | every task (each activity names its own) | as below | predict-then-reveal questions, answered from the open bundle |

## Activities and lab sessions

Each activity asks for predictions, then shows what the recorded run did and the cycles it looked
at. The expected answers are computed from the open bundle when the activity starts. With a
bundle of a student's own design, an activity is therefore a check of that design against what
the student expects, not against the reference.

| Activity | Tasks | Lab session | Needs |
|----------|-------|-------------|-------|
| The single-cycle datapath: add, lw, beq | T1, T2 | after notebook 5.4, before exercise 1.1; again when 1.4 to 1.8 are done | a shipped T1 example (its listing names the instructions) |
| The multi-cycle divider | T2 | after exercises 2.3 to 2.6 | a T2 bundle with a division (the `t2-muldiv` example) |
| What each check proves: retire log, waveform, test result | T1, T2 | the first session that uses `make obs`; it explains why a retire log alone does not show a stall | a bundle with test results |
| The UART: from a store to the terminal | T1 to T5 | after notebook 5.8 and exercise 1.7 | a bundle of the `uart` program from cycle 0 |
| Exceptions and interrupts | T3, T4, T5 | after exercises 3.2 to 3.4 (trap entry, the handler address) and 3.9 (taking an interrupt) | a bundle with a trap, irqtrap for an interrupt (`make obs PROG=irqtrap OBS_CYCLES=10000`) |
| Pipeline hazards: forwarding, stall and flush | T4, T5 | after exercises 4.2 to 4.3, once per variant (`VARIANT=Stall`, `Forward`, `EarlyBranch`): the same program gives other numbers | a bundle of the `hazards` program |
| The VGA: from the framebuffer to the screen | T5 | after exercises 5.6 to 5.8 (the VGA) and `make check-vga` | a nyancat bundle with a frame and the stores (`OBS_VGA=1 OBS_VGA_EXIT=1 OBS_STORES=20000000:20010000`) |

A session that uses the pipeline view can compare the variants: a student makes three bundles of
`hazards` and adds two of them under "Variants side by side". The numbers to expect are derived
in `4-pipeline/README.md` and checked by `HazardSpec`; the view counts them from the run and
does not know them.

## Which bundles exist

The course ships bundles of the reference design for T1 and T2 only: `t1-add`, `t1-lw`,
`t1-beq` and `t2-muldiv` (`web/examples/`). For T3 to T5 none is shipped: the timing of
an interrupt, of a pipeline hazard and of a bus transaction is what those tasks' exercises
build, and a reference bundle would show it. The bundles of T3 to T5 are therefore always built
locally: students make them from their own design with `make obs`, and the browser tests make
them from the reference with `make web-view-bundles` (`web/views.mk`) into `build/`, which is
not published. An instructor who wants to show a T3 to T5 view in class builds the bundle on
their own machine the same way and does not publish it.

## What students must be told

- **Digital only.** The views show logic levels of the RTL sampled once per cycle, never
  voltages, analog timing or anything inside a cycle. The waveform says so on screen.
- **A virtual board.** The 3D board is a teaching drawing, labeled "virtual teaching board, not
  a physical PCB"; its clock, reset and power are concepts, and its probes read probe fields.
- **Concept elements.** An element that no probe field drives is drawn dashed and labeled
  "concept": the datapath's immediate, multiplier, RAM and SimCtrl boxes, the SoC's
  instruction master; the pipeline view names its forwarding paths in a note of its own. Its
  value is not shown because it was not recorded. The fields
  `pipe.forwardA` and `pipe.forwardB` of a T4 or T5 bundle are 0 by design: the forwarding
  select is inside the bootcamp's `Forward` (notebook 5.7), an exercise, and a probe would have
  to restate it ([observation.md](observation.md), the `pipe` group). A 0 there says nothing
  about whether the design forwards. Forwarding shows only indirectly, on `Forward` and
  `EarlyBranch`: an instruction that uses an ALU result of the instruction before it and that
  the pipeline chart does not hold in ID (a load-use pair is still held for a cycle, and `Stall`
  holds every such pair), and the value it writes in its retire record; neither says which path
  the operand took.
- **A window, not the whole run.** A bundle holds the cycles of its window. A VGA frame is drawn
  long before it is shown, so the VGA view finds its stores in `stores.json`, the whole run's
  stores to the VGA, not in the window. A window that starts after cycle 0 is said so: the
  pipeline view follows an instruction already in the pipeline from the first cycle, and AXI
  pairing and a UART decode say that they may be off at their start.
- **Progress is not grading.** The site keeps "read", "activity done" and "test results from an
  imported bundle" in the browser's storage only. The grade comes from the tests of each task's
  README and the tests that are not in the repository.
- **A bundle of one's own design.** Activities and views say what that design did. A wrong
  answer of an activity on one's own bundle can be a wrong prediction or a wrong design; the
  tests of the task tell which.
