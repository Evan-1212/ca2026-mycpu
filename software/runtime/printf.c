// SPDX-License-Identifier: MIT
// printf family with integer formats only:
//   %d %i %u %x %X %c %s %p %%, length modifiers l and ll,
//   flags '-' and '0', and a field width (digits or '*'; a width that
//   nears INT_MAX is taken as INT_MAX).
// There is no floating-point support. The functions return the number of
// characters produced, or -1 when that number exceeds INT_MAX.

#include <limits.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#include "syscalls.h"

// Output sink: the console when buf is NULL, otherwise a bounded buffer.
struct sink {
    char *buf;
    size_t size;
    size_t len;  // characters produced so far, including dropped ones
};

static void emit(struct sink *out, char c)
{
    if (!out->buf)
        platform_putchar((unsigned char) c);
    else if (out->len + 1 < out->size)
        out->buf[out->len] = c;
    out->len++;
}

static void pad(struct sink *out, char c, int n)
{
    while (n-- > 0)
        emit(out, c);
}

// Format an unsigned value into tmp (reversed); return the digit count. 64-bit
// division (libgcc's __udivdi3) is used only while the value does not fit in 32
// bits.
static int utoa_rev(char *tmp, unsigned long long v, unsigned base, bool upper)
{
    const char *digits = upper ? "0123456789ABCDEF" : "0123456789abcdef";
    int n = 0;

    while (v > UINT32_MAX) {
        // One 64-bit division per digit; the remainder needs 32 bits only.
        unsigned long long q = v / base;
        tmp[n++] = digits[(uint32_t) v - (uint32_t) q * base];
        v = q;
    }
    uint32_t w = (uint32_t) v;
    do {
        tmp[n++] = digits[w % base];
        w /= base;
    } while (w);
    return n;
}

static void put_field(struct sink *out,
                      const char *prefix,
                      const char *rev,
                      int ndigits,
                      int width,
                      bool left,
                      bool zero)
{
    int plen = 0;

    while (prefix[plen])
        plen++;
    int padding = width - plen - ndigits;

    if (!left && !zero)
        pad(out, ' ', padding);
    for (int i = 0; i < plen; i++)
        emit(out, prefix[i]);
    if (!left && zero)
        pad(out, '0', padding);
    while (ndigits--)
        emit(out, rev[ndigits]);
    if (left)
        pad(out, ' ', padding);
}

static int format(struct sink *out, const char *fmt, va_list ap)
{
    char tmp[24];

    for (; *fmt; fmt++) {
        if (*fmt != '%') {
            emit(out, *fmt);
            continue;
        }
        fmt++;

        bool left = false, zero = false;
        for (;; fmt++) {
            if (*fmt == '-')
                left = true;
            else if (*fmt == '0')
                zero = true;
            else
                break;
        }

        int width = 0;
        if (*fmt == '*') {
            width = va_arg(ap, int);
            if (width < 0) {
                left = true;
                width = width == INT_MIN ? INT_MAX : -width;
            }
            fmt++;
        } else {
            // Saturate before width * 10 + 9 could overflow; the bound is a
            // constant, so no division is done here.
            for (; *fmt >= '0' && *fmt <= '9'; fmt++) {
                if (width > (INT_MAX - 9) / 10)
                    width = INT_MAX;
                else
                    width = width * 10 + (*fmt - '0');
            }
        }

        int longs = 0;
        while (*fmt == 'l') {
            longs++;
            fmt++;
        }

        switch (*fmt) {
        case 'd':
        case 'i': {
            long long v;
            if (longs >= 2)
                v = va_arg(ap, long long);
            else if (longs == 1)
                v = va_arg(ap, long);
            else
                v = va_arg(ap, int);
            unsigned long long mag = (unsigned long long) v;
            if (v < 0)
                mag = -mag;
            int n = utoa_rev(tmp, mag, 10, false);
            put_field(out, v < 0 ? "-" : "", tmp, n, width, left, zero);
            break;
        }
        case 'u':
        case 'x':
        case 'X': {
            unsigned long long v;
            if (longs >= 2)
                v = va_arg(ap, unsigned long long);
            else if (longs == 1)
                v = va_arg(ap, unsigned long);
            else
                v = va_arg(ap, unsigned int);
            int n = utoa_rev(tmp, v, *fmt == 'u' ? 10 : 16, *fmt == 'X');
            put_field(out, "", tmp, n, width, left, zero);
            break;
        }
        case 'p': {
            uintptr_t v = (uintptr_t) va_arg(ap, void *);
            int n = utoa_rev(tmp, v, 16, false);
            put_field(out, "0x", tmp, n, width, left, zero);
            break;
        }
        case 'c':
            tmp[0] = (char) va_arg(ap, int);
            put_field(out, "", tmp, 1, width, left, false);
            break;
        case 's': {
            const char *s = va_arg(ap, const char *);
            int n = 0;
            if (!s)
                s = "(null)";
            while (s[n])
                n++;
            if (!left)
                pad(out, ' ', width - n);
            for (int i = 0; i < n; i++)
                emit(out, s[i]);
            if (left)
                pad(out, ' ', width - n);
            break;
        }
        case '%':
            emit(out, '%');
            break;
        case '\0':
            fmt--;  // a lone '%' at the end: stop at the terminator
            break;
        default:  // unknown conversion: print it as is
            emit(out, '%');
            emit(out, *fmt);
            break;
        }
    }
    return out->len > INT_MAX ? -1 : (int) out->len;
}

int vprintf(const char *fmt, va_list ap)
{
    struct sink out = {NULL, 0, 0};
    return format(&out, fmt, ap);
}

int printf(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    int n = vprintf(fmt, ap);
    va_end(ap);
    return n;
}

int vsnprintf(char *buf, size_t size, const char *fmt, va_list ap)
{
    // A one-byte scratch buffer keeps the sink in buffer mode when size is 0.
    char scratch;
    struct sink out = {size ? buf : &scratch, size, 0};
    int n = format(&out, fmt, ap);

    if (size)
        buf[out.len < size ? out.len : size - 1] = '\0';
    return n;
}

int snprintf(char *buf, size_t size, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, size, fmt, ap);
    va_end(ap);
    return n;
}

int putchar(int c)
{
    platform_putchar((unsigned char) c);
    return (unsigned char) c;
}

int puts(const char *s)
{
    while (*s)
        platform_putchar((unsigned char) *s++);
    platform_putchar('\n');
    return 0;
}
