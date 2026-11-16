// SPDX-License-Identifier: MIT
// Minimal <stdlib.h>: bump allocator and exit (see runtime/libc.c and the
// platform back ends).

#pragma once

#include <stddef.h>

#define EXIT_SUCCESS 0
#define EXIT_FAILURE 1

void *malloc(size_t size);
void free(void *ptr);
void exit(int code) __attribute__((noreturn));
int abs(int x);
