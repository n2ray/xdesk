#include "frames.h"
#include <va/va_backend.h>
#include <va/va_drmcommon.h>
#include <dlfcn.h>
#include <new>

namespace {
struct Driver { VADriverVTable table; VADriverVTable *original; };
Driver *driver(VADriverContextP c) { return reinterpret_cast<Driver *>(c->vtable); }

void remember(VADriverContextP c, VASurfaceID id, const VADRMPRIMESurfaceDescriptor &d) {
    if (d.num_objects != 1 || d.num_layers != 1 || d.layers[0].num_planes != 1 ||
        d.layers[0].object_index[0] || d.layers[0].offset[0] ||
        d.objects[0].drm_format_modifier != 0) return;
    auto format = d.layers[0].drm_format;
    if (format == VA_FOURCC('A','R','2','4') || format == VA_FOURCC('X','R','2','4'))
        xdesk_va_frame(c, id, d.objects[0].fd, d.width, d.height, d.layers[0].pitch[0]);
}

VAStatus create(VADriverContextP c, unsigned fmt, unsigned w, unsigned h, VASurfaceID *ids,
                unsigned n, VASurfaceAttrib *attrs, unsigned count) {
    auto status = driver(c)->original->vaCreateSurfaces2(c, fmt, w, h, ids, n, attrs, count);
    if (status != VA_STATUS_SUCCESS || n != 1) return status;
    uint32_t type = 0;
    const void *desc = nullptr;
    for (unsigned i = 0; i < count; ++i) {
        if (attrs[i].type == VASurfaceAttribMemoryType) type = attrs[i].value.value.i;
        if (attrs[i].type == VASurfaceAttribExternalBufferDescriptor) desc = attrs[i].value.value.p;
    }
    if (type == VA_SURFACE_ATTRIB_MEM_TYPE_DRM_PRIME_2 && desc)
        remember(c, ids[0], *static_cast<const VADRMPRIMESurfaceDescriptor *>(desc));
    return status;
}
VAStatus export_fd(VADriverContextP c, VASurfaceID id, uint32_t type, uint32_t flags, void *out) {
    auto status = driver(c)->original->vaExportSurfaceHandle(c, id, type, flags, out);
    if (status == VA_STATUS_SUCCESS && type == VA_SURFACE_ATTRIB_MEM_TYPE_DRM_PRIME_2)
        remember(c, id, *static_cast<VADRMPRIMESurfaceDescriptor *>(out));
    return status;
}
VAStatus destroy(VADriverContextP c, VASurfaceID *ids, int n) {
    auto status = driver(c)->original->vaDestroySurfaces(c, ids, n);
    if (status == VA_STATUS_SUCCESS && n > 0) xdesk_va_destroy(c, ids, n);
    return status;
}
VAStatus terminate(VADriverContextP c) {
    auto d = driver(c);
    c->vtable = d->original;
    delete d;
    xdesk_va_destroy(c, nullptr, 0);
    return c->vtable->vaTerminate(c);
}
}

// The packaged driver is pinned to the 1.23 entry point by xdesk-gpu.
// Keeping the underlying Mesa DSO local prevents its symbols from entering
// Chrome's Zink/GLX namespace. libva itself loads this small DSO globally.
extern "C" VAStatus __vaDriverInit_1_23(VADriverContextP c) {
    static void *mesa = dlopen("/usr/local/lib/xdesk-hwvideo/termuxva_drv_video.so", RTLD_NOW | RTLD_LOCAL);
    static auto init = mesa ? reinterpret_cast<VAStatus(*)(VADriverContextP)>(
        dlsym(mesa, "__vaDriverInit_1_23")) : nullptr;
    if (!init) return VA_STATUS_ERROR_OPERATION_FAILED;
    auto d = new(std::nothrow) Driver;
    if (!d) return VA_STATUS_ERROR_ALLOCATION_FAILED;
    auto status = init(c);
    if (status != VA_STATUS_SUCCESS) { delete d; return status; }
    d->original = c->vtable;
    d->table = *c->vtable;
    d->table.vaTerminate = terminate;
    d->table.vaCreateSurfaces2 = create;
    d->table.vaExportSurfaceHandle = export_fd;
    d->table.vaDestroySurfaces = destroy;
    c->vtable = &d->table;
    return VA_STATUS_SUCCESS;
}
