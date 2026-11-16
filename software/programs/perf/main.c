// SPDX-License-Identifier: MIT
// The performance counters (T3-04, T3-06): mcycle, minstret and the eight hpm
// events (docs/hpm-events.md) around a loop of divisions, branches and memory
// accesses. Event n is counted by mhpmcounter<n + 2>. All counters are frozen
// with mcountinhibit, zeroed, released for the loop and frozen again, so they
// cover the same cycles: then mcycle - minstret, the cycles without a retired
// instruction, is the sum of the partition events (1, 4, 5, 6, 7, 8) exactly,
// and the program checks it.
//
// The counts depend on the core and are printed on perf: lines; what the other
// lines say holds on every core from T3 on.
//
// Class privileged: the machine-mode counters and mcountinhibit, MyCPU with
// Zicsr only.

#include <mmio.h>
#include <stdint.h>
#include <stdio.h>

// Every program is built for every ARCH (also without Zicsr, where this one is
// never run): its CSR instructions are assembled with Zicsr enabled locally.
#define ZICSR_ON ".option push\n.option arch, +zicsr\n"
#define ZICSR_OFF "\n.option pop"

#define CSRR(csr)                                                         \
    ({                                                                    \
        uint32_t v_;                                                      \
        __asm__ volatile(ZICSR_ON "csrr %0, " #csr ZICSR_OFF : "=r"(v_)); \
        v_;                                                               \
    })
#define CSRW(csr, v) \
    __asm__ volatile(ZICSR_ON "csrw " #csr ", %0" ZICSR_OFF : : "r"(v))

#define INHIBIT_ALL 0x7fdu  // CY, IR, HPM3..HPM10

static const char *const names[8] = {
    "divider busy", "taken branch or jump", "trap taken",  "load-use stall",
    "flush bubble", "I-side wait",          "D-side wait", "other",
};

static volatile uint32_t data[64];

// Divisions, branches and loads and stores: the measured work.
static uint32_t work(void)
{
    uint32_t acc = 0;

    for (int i = 0; i < 64; i++)
        data[i] = (uint32_t) i * 2654435761u;
    for (int i = 1; i < 64; i++) {
        uint32_t x = data[i];
        acc += x / (uint32_t) i;
        if (x & 1)
            acc ^= x % 97u;
        else
            acc += x >> 3;
    }
    return acc;
}

int main(void)
{
    uint32_t c[10];

    CSRW(mcountinhibit, INHIBIT_ALL);
    CSRW(mhpmevent3, HPM_EVENT_DIVIDER_BUSY);
    CSRW(mhpmevent4, HPM_EVENT_TAKEN_BRANCH);
    CSRW(mhpmevent5, HPM_EVENT_TRAP_TAKEN);
    CSRW(mhpmevent6, HPM_EVENT_LOAD_USE_STALL);
    CSRW(mhpmevent7, HPM_EVENT_FLUSH_BUBBLE);
    CSRW(mhpmevent8, HPM_EVENT_INSTRUCTION_WAIT);
    CSRW(mhpmevent9, HPM_EVENT_DATA_WAIT);
    CSRW(mhpmevent10, HPM_EVENT_OTHER);
    CSRW(mcycle, 0);
    CSRW(minstret, 0);
    CSRW(mhpmcounter3, 0);
    CSRW(mhpmcounter4, 0);
    CSRW(mhpmcounter5, 0);
    CSRW(mhpmcounter6, 0);
    CSRW(mhpmcounter7, 0);
    CSRW(mhpmcounter8, 0);
    CSRW(mhpmcounter9, 0);
    CSRW(mhpmcounter10, 0);
    CSRW(mcountinhibit, 0);

    uint32_t result = work();

    CSRW(mcountinhibit, INHIBIT_ALL);
    c[0] = CSRR(mcycle);
    c[1] = CSRR(minstret);
    c[2] = CSRR(mhpmcounter3);
    c[3] = CSRR(mhpmcounter4);
    c[4] = CSRR(mhpmcounter5);
    c[5] = CSRR(mhpmcounter6);
    c[6] = CSRR(mhpmcounter7);
    c[7] = CSRR(mhpmcounter8);
    c[8] = CSRR(mhpmcounter9);
    c[9] = CSRR(mhpmcounter10);

    printf("work: %08x\n", (unsigned) result);
    printf("perf: mcycle %u\n", (unsigned) c[0]);
    printf("perf: minstret %u\n", (unsigned) c[1]);
    for (int e = 1; e <= 8; e++)
        printf("perf: event %d (%s) %u\n", e, names[e - 1],
               (unsigned) c[e + 1]);

    uint32_t partition = c[2] + c[5] + c[6] + c[7] + c[8] + c[9];
    int exact = c[0] - c[1] == partition;
    printf(
        "partition: mcycle - minstret %s the sum of events 1, 4, 5, 6, 7, 8\n",
        exact ? "equals" : "differs from");
    printf("traps in the loop: %u\n", (unsigned) c[4]);
    printf("taken branches counted: %s\n", c[3] > 64 ? "yes" : "no");
    CSRW(mcountinhibit, 0);
    return exact ? 0 : 1;
}
