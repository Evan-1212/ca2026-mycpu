// SPDX-License-Identifier: MIT
// Tiny C library: string functions, a bump allocator and exit. It is the same
// object on every platform, so Dhrystone's strcpy/strcmp run the same code on
// the CPU and on rv32emu (VER-06).
//
// Build with -fno-tree-loop-distribute-patterns, or GCC may turn the loops
// below back into calls to memset and memcpy.

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "syscalls.h"

void *memcpy(void *dst, const void *src, size_t n)
{
    unsigned char *d = dst;
    const unsigned char *s = src;

    // Copy words when both pointers are word aligned.
    if ((((uintptr_t) d | (uintptr_t) s) & 3) == 0) {
        for (; n >= 4; n -= 4, d += 4, s += 4)
            *(uint32_t *) d = *(const uint32_t *) s;
    }
    while (n--)
        *d++ = *s++;
    return dst;
}

void *memmove(void *dst, const void *src, size_t n)
{
    unsigned char *d = dst;
    const unsigned char *s = src;

    if (d <= s || d >= s + n)
        return memcpy(dst, src, n);
    while (n--)
        d[n] = s[n];
    return dst;
}

void *memset(void *dst, int c, size_t n)
{
    unsigned char *d = dst;

    while (n--)
        *d++ = (unsigned char) c;
    return dst;
}

int memcmp(const void *a, const void *b, size_t n)
{
    const unsigned char *p = a, *q = b;

    for (; n; n--, p++, q++) {
        if (*p != *q)
            return *p - *q;
    }
    return 0;
}

char *strcpy(char *dst, const char *src)
{
    char *d = dst;

    while ((*d++ = *src++) != '\0')
        ;
    return dst;
}

int strcmp(const char *a, const char *b)
{
    while (*a && *a == *b) {
        a++;
        b++;
    }
    return (unsigned char) *a - (unsigned char) *b;
}

size_t strlen(const char *s)
{
    const char *p = s;

    while (*p)
        p++;
    return (size_t) (p - s);
}

int abs(int x)
{
    return x < 0 ? -x : x;
}

// Bump allocator over [_end, __heap_end) from link.ld. free does nothing.
extern char _end[], __heap_end[];
static char *heap_ptr = _end;

void *malloc(size_t size)
{
    char *p = heap_ptr;

    // Reject a size that would wrap when rounded up to 8 bytes.
    if (size > SIZE_MAX - 7)
        return NULL;
    size = (size + 7) & ~(size_t) 7;
    if (size > (size_t) (__heap_end - p))
        return NULL;
    heap_ptr = p + size;
    return p;
}

void free(void *ptr)
{
    (void) ptr;
}

void exit(int code)
{
    platform_exit(code);
}
