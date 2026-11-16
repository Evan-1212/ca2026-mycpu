// SPDX-License-Identifier: MIT
// Stub <sys/time.h>: the type only, so that Dhrystone compiles. There is no
// wall clock; programs read the cycle counters from <mmio.h> instead.

#pragma once

struct timeval {
    long tv_sec;
    long tv_usec;
};
