#include "frames.h"
#include <GL/gl.h>
#include <GL/glx.h>
#include <cstring>
#include <dlfcn.h>
#include <linux/dma-buf.h>
#include <map>
#include <mutex>
#include <sys/ioctl.h>
#include <sys/mman.h>

namespace {
void *system_gl() {
    static void *handle = dlopen("/usr/lib/aarch64-linux-gnu/libGL.so.1", RTLD_NOW | RTLD_LOCAL);
    return handle;
}
template<class F> F symbol(const char *name) { return reinterpret_cast<F>(dlsym(system_gl(), name)); }
template<class F> F proc(const char *name) {
    static auto resolve = symbol<decltype(&glXGetProcAddressARB)>("glXGetProcAddressARB");
    return reinterpret_cast<F>(resolve(reinterpret_cast<const GLubyte *>(name)));
}
GLXContext context() {
    static auto current = symbol<decltype(&glXGetCurrentContext)>("glXGetCurrentContext");
    return current();
}

using Drawable = std::pair<Display *, GLXDrawable>;
using Texture = std::pair<GLXContext, GLuint>;
struct Binding { Drawable drawable; FramePtr frame; };
std::mutex lock;
std::map<Drawable, FramePtr> drawables;
std::map<Texture, Binding> textures;

void release(Drawable key) {
    for (auto it = textures.begin(); it != textures.end();)
        if (it->second.drawable == key) it = textures.erase(it); else ++it;
}

void upload(const Frame &f, bool allocate) {
    size_t size = size_t(f.stride) * f.height;
    void *ptr = mmap(nullptr, size, PROT_READ, MAP_SHARED, f.fd, 0);
    if (ptr == MAP_FAILED) return;
    dma_buf_sync sync{DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ};
    if (ioctl(f.fd, DMA_BUF_IOCTL_SYNC, &sync) == 0) {
        static auto get = proc<decltype(&glGetIntegerv)>("glGetIntegerv");
        static auto store = proc<decltype(&glPixelStorei)>("glPixelStorei");
        static auto buffer = proc<PFNGLBINDBUFFERPROC>("glBindBuffer");
        static auto image = proc<decltype(&glTexImage2D)>("glTexImage2D");
        static auto sub = proc<decltype(&glTexSubImage2D)>("glTexSubImage2D");
        GLint row, unpack, skip_x, skip_y, alignment;
        get(GL_UNPACK_ROW_LENGTH, &row); get(GL_PIXEL_UNPACK_BUFFER_BINDING, &unpack);
        get(GL_UNPACK_SKIP_PIXELS, &skip_x); get(GL_UNPACK_SKIP_ROWS, &skip_y);
        get(GL_UNPACK_ALIGNMENT, &alignment);
        buffer(GL_PIXEL_UNPACK_BUFFER, 0);
        store(GL_UNPACK_ROW_LENGTH, f.stride/4); store(GL_UNPACK_ALIGNMENT, 1);
        store(GL_UNPACK_SKIP_PIXELS, 0); store(GL_UNPACK_SKIP_ROWS, 0);
        if (allocate)
            image(GL_TEXTURE_2D, 0, GL_RGBA8, f.width, f.height, 0, GL_BGRA, GL_UNSIGNED_BYTE, ptr);
        else
            sub(GL_TEXTURE_2D, 0, 0, 0, f.width, f.height, GL_BGRA, GL_UNSIGNED_BYTE, ptr);
        store(GL_UNPACK_ROW_LENGTH, row); store(GL_UNPACK_ALIGNMENT, alignment);
        store(GL_UNPACK_SKIP_PIXELS, skip_x); store(GL_UNPACK_SKIP_ROWS, skip_y);
        buffer(GL_PIXEL_UNPACK_BUFFER, unpack);
        sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
        ioctl(f.fd, DMA_BUF_IOCTL_SYNC, &sync);
    }
    munmap(ptr, size);
}
}

extern "C" GLXPixmap glXCreatePixmap(Display *d, GLXFBConfig cfg, Pixmap p, const int *attrs) {
    static auto next = symbol<decltype(&glXCreatePixmap)>("glXCreatePixmap");
    auto drawable = next(d, cfg, p, attrs);
    if (auto f = xdesk_frame(p); drawable && f) {
        std::lock_guard<std::mutex> guard(lock);
        drawables[{d, drawable}] = f;
    }
    return drawable;
}
extern "C" void glXDestroyPixmap(Display *d, GLXPixmap p) {
    static auto next = symbol<decltype(&glXDestroyPixmap)>("glXDestroyPixmap");
    {
        std::lock_guard<std::mutex> guard(lock);
        drawables.erase({d,p}); release({d,p});
    }
    next(d,p);
}
extern "C" void glXBindTexImageEXT(Display *d, GLXDrawable p, int b, const int *attrs) {
    static auto next = proc<PFNGLXBINDTEXIMAGEEXTPROC>("glXBindTexImageEXT");
    next(d,p,b,attrs);
    FramePtr f;
    {
        std::lock_guard<std::mutex> guard(lock);
        auto it = drawables.find({d,p});
        if (it == drawables.end()) return;
        GLint id = 0;
        static auto get = proc<decltype(&glGetIntegerv)>("glGetIntegerv");
        get(GL_TEXTURE_BINDING_2D, &id);
        if (!id) return;
        f = it->second;
        textures[{context(), GLuint(id)}] = {{d,p}, f};
    }
    upload(*f, true);
}
extern "C" void glXReleaseTexImageEXT(Display *d, GLXDrawable p, int b) {
    static auto next = proc<PFNGLXRELEASETEXIMAGEEXTPROC>("glXReleaseTexImageEXT");
    { std::lock_guard<std::mutex> guard(lock); release({d,p}); }
    next(d,p,b);
}
extern "C" void glBindTexture(GLenum target, GLuint t) {
    static auto next = proc<decltype(&glBindTexture)>("glBindTexture");
    next(target,t);
    if (!t || target != GL_TEXTURE_2D) return;
    FramePtr f;
    {
        std::lock_guard<std::mutex> guard(lock);
        auto it = textures.find({context(), t});
        if (it == textures.end()) return;
        f = it->second.frame;
    }
    upload(*f, false);
}
extern "C" void glDeleteTextures(GLsizei n, const GLuint *ids) {
    static auto next = proc<decltype(&glDeleteTextures)>("glDeleteTextures");
    {
        std::lock_guard<std::mutex> guard(lock);
        auto c = context();
        for (GLsizei i=0; i<n; ++i) textures.erase({c,ids[i]});
    }
    next(n,ids);
}
extern "C" void glXDestroyContext(Display *d, GLXContext c) {
    static auto next = symbol<decltype(&glXDestroyContext)>("glXDestroyContext");
    {
        std::lock_guard<std::mutex> guard(lock);
        for (auto it = textures.begin(); it != textures.end();)
            if (it->first.first == c) it = textures.erase(it); else ++it;
    }
    next(d,c);
}
extern "C" __GLXextFuncPtr glXGetProcAddressARB(const GLubyte *name) {
#define WRAPPED(fn) if (!strcmp(reinterpret_cast<const char *>(name), #fn)) return reinterpret_cast<__GLXextFuncPtr>(&fn)
    WRAPPED(glXCreatePixmap); WRAPPED(glXDestroyPixmap); WRAPPED(glXBindTexImageEXT);
    WRAPPED(glXReleaseTexImageEXT); WRAPPED(glBindTexture); WRAPPED(glDeleteTextures);
    WRAPPED(glXDestroyContext);
#undef WRAPPED
    return proc<__GLXextFuncPtr>(reinterpret_cast<const char *>(name));
}
extern "C" __GLXextFuncPtr glXGetProcAddress(const GLubyte *name) { return glXGetProcAddressARB(name); }
