#pragma once

#include <cstdint>
#include <memory>

// XIDs are server-wide. Chrome uses different XCB connections for producing
// and consuming a pixmap on the session's X display.
struct Frame {
    int fd;
    uint16_t width, height;
    uint32_t stride;
    ~Frame();
};
using FramePtr = std::shared_ptr<const Frame>;
FramePtr xdesk_frame(uint32_t pixmap);

// Only linear AR24/XR24 VA surfaces may be sampled through a CPU mapping.
void xdesk_va_frame(void *driver, unsigned surface, int fd, uint32_t w,
                    uint32_t h, uint32_t stride);
void xdesk_va_destroy(void *driver, const unsigned *surfaces, unsigned count);
