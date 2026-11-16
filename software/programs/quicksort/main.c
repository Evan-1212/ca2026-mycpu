// SPDX-License-Identifier: MIT
// Quicksort of pseudo-random numbers, checked for order, with a checksum.

#include <stdint.h>
#include <stdio.h>

#define N 200

static int32_t data[N];

static void quicksort(int32_t *a, int lo, int hi)
{
    while (lo < hi) {
        int32_t pivot = a[lo + (hi - lo) / 2];
        int i = lo, j = hi;

        while (i <= j) {
            while (a[i] < pivot)
                i++;
            while (a[j] > pivot)
                j--;
            if (i <= j) {
                int32_t t = a[i];
                a[i++] = a[j];
                a[j--] = t;
            }
        }
        // Recurse into the smaller half to bound the stack depth.
        if (j - lo < hi - i) {
            quicksort(a, lo, j);
            lo = i;
        } else {
            quicksort(a, i, hi);
            hi = j;
        }
    }
}

int main(void)
{
    uint32_t x = 2463534242u;  // xorshift32 seed
    uint32_t checksum = 0;
    int sorted = 1;

    for (int i = 0; i < N; i++) {
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        data[i] = (int32_t) (x >> 8) - (1 << 23);  // signed values
    }
    quicksort(data, 0, N - 1);
    for (int i = 0; i < N; i++) {
        if (i > 0 && data[i - 1] > data[i])
            sorted = 0;
        checksum = (checksum << 5 | checksum >> 27) ^ (uint32_t) data[i];
    }
    printf("n=%d min=%d max=%d\n", N, (int) data[0], (int) data[N - 1]);
    printf("sorted=%d checksum=0x%08x\n", sorted, (unsigned) checksum);
    return sorted ? 0 : 1;
}
