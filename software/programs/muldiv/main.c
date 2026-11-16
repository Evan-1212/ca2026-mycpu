// SPDX-License-Identifier: MIT
// M extension corner cases. Built for rv32im, the compiler emits mul, mulh*,
// div* and rem*; built for rv32i, the same source calls libgcc. Both builds
// must print the same text, including division by zero and overflow, whose
// RISC-V results are x/0 = -1, x%0 = x, INT_MIN/-1 = INT_MIN, INT_MIN%-1 = 0
// (GCC 15's libgcc returns the same values, checked on rv32emu).
//
// The operands are volatile, so the compiler cannot fold anything at compile
// time and every case runs through the instruction or the libgcc call.

#include <stdint.h>
#include <stdio.h>

static uint32_t mulh_ss(int32_t a, int32_t b)
{
    return (uint32_t) (((int64_t) a * b) >> 32);
}

static uint32_t mulh_uu(uint32_t a, uint32_t b)
{
    return (uint32_t) (((uint64_t) a * b) >> 32);
}

static uint32_t mulh_su(int32_t a, uint32_t b)
{
    return (uint32_t) (((int64_t) a * (int64_t) (uint64_t) b) >> 32);
}

static volatile int32_t operands[] = {
    0, 1, -1, 7, -7, 3, 12345678, -98765, INT32_MAX, INT32_MIN,
};

int main(void)
{
    const int n = sizeof(operands) / sizeof(operands[0]);
    uint32_t hash = 0;

    for (int i = 0; i < n; i++) {
        for (int j = 0; j < n; j++) {
            int32_t a = operands[i], b = operands[j];
            uint32_t ua = (uint32_t) a, ub = (uint32_t) b;
            uint32_t r[8] = {
                (uint32_t) (a * b), mulh_ss(a, b),
                mulh_uu(ua, ub),    mulh_su(a, ub),
                (uint32_t) (a / b), ua / ub,
                (uint32_t) (a % b), ua % ub,
            };
            for (int k = 0; k < 8; k++)
                hash = (hash << 7 | hash >> 25) ^ r[k];
            if (j == 0 || j == 2 || i == n - 1)
                printf("%d,%d: %08x %08x %08x %08x %08x %08x %08x %08x\n",
                       (int) a, (int) b, (unsigned) r[0], (unsigned) r[1],
                       (unsigned) r[2], (unsigned) r[3], (unsigned) r[4],
                       (unsigned) r[5], (unsigned) r[6], (unsigned) r[7]);
        }
    }

    // 64-bit operations (libgcc __muldi3, __divdi3, __udivdi3, __moddi3 and
    // __umoddi3 on both builds, built from mul/div on rv32im).
    volatile int64_t x = -1234567890123456789LL, y = 987654321LL;
    volatile uint64_t ux = 18000000000000000000ULL, uy = 3000000007ULL;
    printf("64: %lld %lld %lld\n", (long long) (x * y), (long long) (x / y),
           (long long) (x % y));
    printf("64u: %llu %llu %llu\n", (unsigned long long) (ux * uy),
           (unsigned long long) (ux / uy), (unsigned long long) (ux % uy));

    printf("hash=0x%08x\n", (unsigned) hash);
    return 0;
}
