// SPDX-License-Identifier: MIT
// Recursive Fibonacci: a first program that needs only RV32I and a stack.

#include <stdio.h>

static int fib(int n)
{
    return n < 2 ? n : fib(n - 1) + fib(n - 2);
}

int main(void)
{
    static const int ns[] = {0, 1, 2, 10, 20, 25};

    for (unsigned i = 0; i < sizeof(ns) / sizeof(ns[0]); i++)
        printf("fib(%d)=%d\n", ns[i], fib(ns[i]));
    return fib(10) == 55 ? 0 : 1;
}
