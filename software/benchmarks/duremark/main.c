/*
 * DureMark Main Benchmark Execution Orchestrates workload execution and
 * calculates scores
 */

#include "duremark.h"

#include <stdio.h>
#ifdef MYCPU
#include <mmio.h>
#else
#include <time.h>
#endif

#ifdef MYCPU
/* MYCPU: a fixed iteration count (no self-calibration) and the cycle counter
 * instead of clock(). The score is printed in integers only.
 */
#ifndef ITERATIONS
#define ITERATIONS 20
#endif
_Static_assert((ITERATIONS) > 0 && (ITERATIONS) <= 4000000000u,
               "ITERATIONS must be between 1 and 4000000000");
#define DUREMARK_FIXED_ITERATIONS ITERATIONS

static uint64_t start_cycle;

static void du_start_time(void)
{
    start_cycle = read_cycle();
}

static du_ticks_t du_get_time(void)
{
    return read_cycle() - start_cycle;
}

/* part / total in per mille, part <= total. Both are halved until
 * part * 1000 fits in 64 bits, which keeps the quotient to within 1.
 */
static unsigned long long du_per_mille(uint64_t part, uint64_t total)
{
    while (part > UINT64_MAX / 1000) {
        part >>= 1;
        total >>= 1;
    }
    return part * 1000 / total;
}
#else
/* POSIX timing via clock(3) */
static clock_t start_clock;

static void du_start_time(void)
{
    start_clock = clock();
}

static du_ticks_t du_get_time(void)
{
    return (du_ticks_t) (clock() - start_clock);
}

static double du_get_sec_per_tick(void)
{
    return 1.0 / CLOCKS_PER_SEC;
}
#endif

/* Run benchmark for at least so long. */
enum {
    TARGET_SECONDS = 3,
};

static void du_iterate(du_results_t *res)
{
    uint32_t i;
    uint32_t checksum = 0;

    /* Time each workload once over its whole loop. Timing individual sub-
     * microsecond calls and summing them quantizes to zero against clock()
     * granularity; timing the batch does not.
     */
    res->list_ticks = 0;
    res->matrix_ticks = 0;
    res->state_ticks = 0;

    if (res->execs & ID_LIST) {
        du_start_time();
        for (i = 0; i < res->iterations; i++)
            checksum += du_bench_list(res, (int16_t) (i & 0x7FFF));
        res->list_ticks = du_get_time();
    }

    if (res->execs & ID_MATRIX) {
        du_start_time();
        for (i = 0; i < res->iterations; i++) {
            checksum += du_bench_matrix(&res->mat, (matdat_t) (i & 0x7FFF));
        }
        res->matrix_ticks = du_get_time();
    }

    if (res->execs & ID_STATE) {
        du_start_time();
        for (i = 0; i < res->iterations; i++)
            checksum += du_bench_state((int16_t) (i & 0x7F));
        res->state_ticks = du_get_time();
    }

    res->checksum = checksum;
    res->total_ticks = res->list_ticks + res->matrix_ticks + res->state_ticks;
}

int main(void)
{
    /* Zero everything; execs selects which algorithms to run */
    du_results_t res = {
        .execs = ID_LIST | ID_MATRIX | ID_STATE,
    };
#ifdef MYCPU
    uint64_t instret;
#else
    double sec_per_tick = du_get_sec_per_tick();
    double total_sec;
#endif
#ifndef DUREMARK_FIXED_ITERATIONS
    int multiplier_count;
#endif

    /* Initialize workloads */
    res.list = du_list_init();
    if (!res.list) {
        printf("ERROR: Cannot allocate lists!\n");
        return 1;
    }
    du_init_matrix(&res.mat);
    du_init_state();

#ifdef DUREMARK_FIXED_ITERATIONS
    /* Fixed iteration count: always on MYCPU. A host build with
     * -DDUREMARK_FIXED_ITERATIONS=n runs the same work and prints the same
     * checksum, to check a MYCPU run against the host.
     */
    res.iterations = DUREMARK_FIXED_ITERATIONS;
#ifdef MYCPU
    bench_mark_start();
    instret = read_instret();
#endif
    du_iterate(&res);
#ifdef MYCPU
    instret = read_instret() - instret;
    bench_mark_stop();
#endif
#else
    /* Start with three iterations */
    res.iterations = 3;

    /* Run benchmark with auto-scaling iterations */
    for (multiplier_count = 0; multiplier_count < 20; multiplier_count++) {
        printf("Try %lu iterations...\n", res.iterations);

        /* Run benchmark */
        du_iterate(&res);

        /* Check if we need to increase iterations */
        if (res.total_ticks * sec_per_tick >= TARGET_SECONDS) {
            /* Execution time >= target, we're done */
            break;
        }

        /* Increase iterations by 3x or 10/3x */
        if (res.iterations % 3 == 0) {
            res.iterations = res.iterations * 10 / 3;
        } else {
            res.iterations *= 3;
        }
    }
#endif

#ifdef MYCPU
    /* Output results: integers only, platform-dependent lines start with
     * "perf:". Score = DureMark/MHz * 1000 = iterations * 10^9 / cycles.
     */
    printf("\n");
    printf("DureMark 1.0 Results\n");
    printf("=======================\n");
    printf("Iterations      : %lu\n", res.iterations);
    printf("perf: Cycles          : %llu\n",
           (unsigned long long) res.total_ticks);
    printf("perf: Instret         : %llu\n", (unsigned long long) instret);
    if (res.total_ticks) {
        printf("perf: List Workload   : %llu permille\n",
               du_per_mille(res.list_ticks, res.total_ticks));
        printf("perf: Matrix Workload : %llu permille\n",
               du_per_mille(res.matrix_ticks, res.total_ticks));
        printf("perf: State Workload  : %llu permille\n",
               du_per_mille(res.state_ticks, res.total_ticks));
        printf("perf: Total Score     : %llu DureMark/MHz*1000\n",
               (unsigned long long) ((uint64_t) res.iterations *
                                     1000000000ull / res.total_ticks));
    }
    printf("-----------------------\n");
    printf("Checksum        : %lu\n\n", (unsigned long) res.checksum);
    return 0;
#else
    /* Output results */
    total_sec = res.total_ticks * sec_per_tick;
    printf("\n");
    printf("DureMark 1.0 Results\n");
    printf("=======================\n");
    printf("Iterations      : %lu\n", res.iterations);
    printf("Execution Time  : %.1f sec\n", total_sec);
    printf("List Workload   : %.1f%%\n",
           res.list_ticks * 100.0 / res.total_ticks);
    printf("Matrix Workload : %.1f%%\n",
           res.matrix_ticks * 100.0 / res.total_ticks);
    printf("State Workload  : %.1f%%\n",
           res.state_ticks * 100.0 / res.total_ticks);
    printf("-----------------------\n");
    printf("Total Score     : %.2f DureMark\n", res.iterations / total_sec);
    /* Print the checksum so the compiler cannot eliminate the workloads that
     * feed it. This is the observable sink that keeps the benchmark honest.
     */
    printf("Checksum        : %lu\n\n", (unsigned long) res.checksum);
    return 0;
#endif
}
