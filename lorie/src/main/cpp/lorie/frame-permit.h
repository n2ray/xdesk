#pragma once

#include <stdint.h>

// The existing shared ABI uses 0 for an available refresh and 1 for a spent one.
// Only the renderer claims a refresh. Multiple ticks before a draw coalesce.
static inline void lorieGrantFrame(volatile uint8_t *spent) {
    __atomic_store_n(spent, 0, __ATOMIC_RELEASE);
}

static inline int lorieFrameAvailable(const volatile uint8_t *spent) {
    return !__atomic_load_n(spent, __ATOMIC_ACQUIRE);
}

static inline int lorieClaimFrame(volatile uint8_t *spent) {
    return !__atomic_exchange_n(spent, 1, __ATOMIC_ACQ_REL);
}
