#include "frames.h"
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <mutex>
#include <map>
#include <sys/stat.h>
#include <unistd.h>
#include <unordered_map>
#include <xcb/xcb.h>
#include <xcb/xcbext.h>

Frame::~Frame() { close(fd); }

namespace {
struct Pixmap { xcb_connection_t *owner; FramePtr frame; };
std::mutex lock;
std::unordered_map<uint32_t, Pixmap> pixmaps;

struct Layout { dev_t dev; ino_t ino; uint32_t w, h, stride; Generation generation; };
std::map<std::pair<void *, unsigned>, Layout> surfaces;

Generation generation_for(const struct stat &st, unsigned w, unsigned h, unsigned stride) {
    std::lock_guard<std::mutex> guard(lock);
    for (const auto &entry : surfaces) {
        auto &layout = entry.second;
        if (layout.dev == st.st_dev && layout.ino == st.st_ino &&
            layout.w == w && layout.h == h && layout.stride == stride) return layout.generation;
    }
    return {};
}

struct Descriptor {
    int fd = -1;
    xcb_connection_t *owner = nullptr;
    void clear() { if (fd >= 0) close(fd); fd = -1; owner = nullptr; }
    ~Descriptor() { clear(); }
};
thread_local Descriptor sent;
xcb_extension_t dri3 = {"DRI3", 0};

template<class T> T field(const unsigned char *p) {
    T value;
    memcpy(&value, p, sizeof(value));
    return value;
}

void forget(xcb_connection_t *c, uint32_t id) {
    std::lock_guard<std::mutex> guard(lock);
    auto it = pixmaps.find(id);
    if (it != pixmaps.end() && it->second.owner == c) pixmaps.erase(it);
}

// Chromium sends the legacy single-FD PixmapFromBuffer request. Preserve its
// implicit layout when spelling it as DRI3 1.2; never label a tiled BO linear.
struct Request {
    unsigned char bytes[64] = {};
    iovec vec[3] = {}; // XCB reserves the two preceding iovecs.
    xcb_protocol_request_t req;
    void prepare(xcb_connection_t *c, int &flags, iovec *&v,
                 const xcb_protocol_request_t *&q, int fd) {
        if (!(flags & XCB_REQUEST_RAW) || !q || q->count != 1 || !v ||
            !v->iov_base || v->iov_len < 8) return;
        auto p = static_cast<const unsigned char *>(v->iov_base);
        uint32_t id = field<uint32_t>(p + 4);
        if (p[0] == XCB_FREE_PIXMAP && v->iov_len == 8) { forget(c, id); return; }
        if (fd < 0 || p[0] < 128 || p[1] != 2 || v->iov_len != 24 ||
            p[23] != 32 || (p[22] != 32 && p[22] != 24)) return;
        auto ext = xcb_get_extension_data(c, &dri3);
        if (!ext || !ext->present || ext->major_opcode != p[0]) return;
        uint16_t w = field<uint16_t>(p+16), h = field<uint16_t>(p+18);
        uint32_t stride = field<uint16_t>(p+20);
        struct stat st;
        if (!w || !h || stride < w*4u || stride%4 || fstat(fd, &st) ||
            st.st_size < int64_t(stride)*h) return;
        auto generation = generation_for(st, w, h, stride);
        if (!generation) return;
        int owned = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (owned < 0) return;
        FramePtr frame(new Frame{owned, w, h, stride, generation});
        {
            std::lock_guard<std::mutex> guard(lock);
            pixmaps[id] = {c, frame};
        }
        memcpy(bytes, p, 12);
        bytes[1] = 7; // PixmapFromBuffers
        uint16_t length = sizeof(bytes)/4;
        memcpy(bytes+2, &length, 2);
        bytes[12] = 1;
        memcpy(bytes+16, p+16, 4);
        memcpy(bytes+20, &stride, 4);
        bytes[52] = p[22]; bytes[53] = p[23];
        uint64_t implicit = UINT64_C(0x00ffffffffffffff); // DRM_FORMAT_MOD_INVALID
        memcpy(bytes+56, &implicit, 8);
        req = *q; req.opcode = 7;
        vec[2] = {bytes, sizeof(bytes)};
        q = &req; v = vec+2;
    }
};
}

void xdesk_va_frame(void *driver, unsigned surface, int fd, uint32_t w, uint32_t h, uint32_t stride) {
    struct stat st;
    if (fstat(fd, &st)) return;
    std::lock_guard<std::mutex> guard(lock);
    auto &layout = surfaces[{driver, surface}];
    if (!layout.generation || layout.dev != st.st_dev || layout.ino != st.st_ino)
        layout = {st.st_dev, st.st_ino, w, h, stride, std::make_shared<std::atomic<uint64_t>>(0)};
}

void xdesk_va_written(void *driver, unsigned surface) {
    std::lock_guard<std::mutex> guard(lock);
    auto it = surfaces.find({driver, surface});
    if (it != surfaces.end()) ++*it->second.generation;
}

void xdesk_va_destroy(void *driver, const unsigned *ids, unsigned count) {
    std::lock_guard<std::mutex> guard(lock);
    if (ids) {
        for (unsigned i = 0; i < count; ++i) surfaces.erase({driver, ids[i]});
    } else {
        for (auto it = surfaces.begin(); it != surfaces.end();)
            if (it->first.first == driver) it = surfaces.erase(it); else ++it;
    }
}

FramePtr xdesk_frame(uint32_t id) {
    std::lock_guard<std::mutex> guard(lock);
    auto it = pixmaps.find(id);
    return it == pixmaps.end() ? nullptr : it->second.frame;
}

extern "C" void xcb_send_fd(xcb_connection_t *c, int fd) {
    static auto next = reinterpret_cast<decltype(&xcb_send_fd)>(dlsym(RTLD_NEXT, "xcb_send_fd"));
    sent.clear(); sent.fd = fcntl(fd, F_DUPFD_CLOEXEC, 0); sent.owner = c;
    next(c, fd);
}

#define SEND(name, type) \
extern "C" type name(xcb_connection_t *c, int flags, iovec *v, const xcb_protocol_request_t *q) { \
    static auto next = reinterpret_cast<decltype(&name)>(dlsym(RTLD_NEXT, #name)); \
    Descriptor held; \
    if (sent.owner == c) { held.fd = sent.fd; sent.fd = -1; sent.owner = nullptr; } \
    Request rewrite; rewrite.prepare(c, flags, v, q, held.fd); \
    return next(c, flags, v, q); \
}
SEND(xcb_send_request, unsigned)
SEND(xcb_send_request64, uint64_t)

#define SEND_FDS(name, type) \
extern "C" type name(xcb_connection_t *c, int flags, iovec *v, const xcb_protocol_request_t *q, unsigned n, int *fds) { \
    static auto next = reinterpret_cast<decltype(&name)>(dlsym(RTLD_NEXT, #name)); \
    Request rewrite; rewrite.prepare(c, flags, v, q, n == 1 ? fds[0] : -1); \
    return next(c, flags, v, q, n, fds); \
}
SEND_FDS(xcb_send_request_with_fds, unsigned)
SEND_FDS(xcb_send_request_with_fds64, uint64_t)

extern "C" void xcb_disconnect(xcb_connection_t *c) {
    static auto next = reinterpret_cast<decltype(&xcb_disconnect)>(dlsym(RTLD_NEXT, "xcb_disconnect"));
    {
        std::lock_guard<std::mutex> guard(lock);
        for (auto it = pixmaps.begin(); it != pixmaps.end();)
            if (it->second.owner == c) it = pixmaps.erase(it); else ++it;
    }
    next(c);
}
