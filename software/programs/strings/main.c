// SPDX-License-Identifier: MIT
// String literals live in .rodata. A 2025 loader copied only .text and .data,
// so every literal read as zero bytes. This program prints literals, a const
// table and a checksum of them, so a missing .rodata shows at once.

#include <stdint.h>
#include <stdio.h>
#include <string.h>

static const char *const words[] = {
    "alpha", "bravo", "charlie", "delta", "echo",
};

static const char banner[] = "strings: .rodata is loaded";

int main(void)
{
    uint32_t sum = 0;
    char buf[64];

    puts(banner);
    for (unsigned i = 0; i < sizeof(words) / sizeof(words[0]); i++) {
        printf("%u: %s (%u bytes)\n", i, words[i], (unsigned) strlen(words[i]));
        for (const char *p = words[i]; *p; p++)
            sum = sum * 31 + (unsigned char) *p;
    }
    snprintf(buf, sizeof(buf), "%s-%s", words[0], words[4]);
    printf("snprintf: %s\n", buf);
    printf("checksum=0x%08x\n", (unsigned) sum);
    printf("%5d|%-5d|%05d|%x|%X|%c|%%|%lld|%llu\n", 42, 42, 42, 0xbeef, 0xbeef,
           'z', -1234567890123LL, 18446744073709551615ULL);
    return strcmp(buf, "alpha-echo") != 0;
}
