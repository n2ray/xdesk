// From termux_on_dex by PietroCav501 (GPL-3.0, github.com/PietroCav501/termux_on_dex, x11/patch/termux-x11.patch),
// taken over unchanged into XDesk; see recipes/glamor.cmake. Its comments are in Italian.
// tod: collegamento tra glamor (composizione X con la GPU) e Android.
// Acceso solo con TERMUX_X11_GLAMOR=1: il server apre un suo contesto GLES 3 (EGL di Android, senza superficie)
// e glamor prende il posto di EXA, cioè del disegno con la CPU. Senza la variabile non cambia niente.
// Tappa 2a: la radice è una texture di glamor (non ancora l'AHardwareBuffer che l'app mostra).
// DRI3: le immagini dei client (Turnip: dma-buf LINEAR) sono mappate in memoria e glamor le carica sulla GPU
// quando servono (una copia, ma la composizione è sulla GPU). Android non ha EGL_EXT_image_dma_buf_import.

#include <epoxy/egl.h>
#include <epoxy/gl.h>
#include <dix-config.h>
#include <android/log.h>
#include <errno.h>
#include <stdlib.h>
#include <time.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <android/hardware_buffer.h>
#include <unistd.h>
#ifndef DRM_FORMAT_MOD_LINEAR
#define DRM_FORMAT_MOD_LINEAR 0ULL
#endif
#ifndef DRM_FORMAT_MOD_INVALID
#define DRM_FORMAT_MOD_INVALID 0x00ffffffffffffffULL
#endif
#include "glamor_priv.h"
#include "buffer.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_ ## prio, "LorieNative", __VA_ARGS__)

// XDesk: where a Present of a client's image spends its time (TERMUX_X11_MISURA=1, written with tod-misura):
// wall time of each step of todCopia, and of todGlamorPubblica, summed over 5 s
enum { TOD_FINISH, TOD_LOCK, TOD_SYNC, TOD_MEMCPY, TOD_UNLOCK, TOD_COPIA_CPU, TOD_PUBBLICA, TOD_PASSI };
static uint64_t todPassoNs[TOD_PASSI], todPassoN[TOD_PASSI];
static uint64_t todAdesso(clockid_t c) {
    struct timespec t;
    clock_gettime(c, &t);
    return (uint64_t) t.tv_sec * 1000000000ull + t.tv_nsec;
}
#define TOD_PASSO(i, da) do { uint64_t _a = todAdesso(CLOCK_MONOTONIC); todPassoNs[i] += _a - (da); todPassoN[i]++; (da) = _a; } while (0)

void todGlamorMisura(uint64_t muro) {
    static const char *nomi[TOD_PASSI] = { "glFinish before the copy", "AHardwareBuffer_lock", "dma-buf sync start",
                                           "memcpy", "sync end + unlock", "the copy, CPU time", "root to the shown buffer (blit + glFinish)" };
    for (int i = 0; i < TOD_PASSI; i++) {
        if (todPassoN[i])
            log(INFO, "tod-misura:   present step %s: %.1f%% of the time, %llu times, %.0f us each", nomi[i],
                100.0 * todPassoNs[i] / muro, (unsigned long long) todPassoN[i], todPassoNs[i] / 1000.0 / todPassoN[i]);
        todPassoNs[i] = todPassoN[i] = 0;
    }
}

static EGLDisplay todDpy = EGL_NO_DISPLAY;
static EGLContext todCtx = EGL_NO_CONTEXT;

// DRI3: ogni immagine importata (dma-buf lineare di Turnip) è mappata in memoria. Con glamor:
//  - via AHardwareBuffer (predefinita): un AHardwareBuffer lineare della stessa misura, che la GPU legge come
//    texture (EGLImage); a ogni Present ci si copiano solo le zone cambiate (todGlamorAggiorna, memcpy);
//  - ripiego, o con TERMUX_X11_GLAMOR_COPIA=1: immagine in memoria, che glamor carica da sé a ogni uso.
// (Android non ha EGL_EXT_image_dma_buf_import, e Adreno rifiuta texture su memoria di Turnip importata con
// GL_EXT_memory_object_fd: provato il 29 ottobre.)
typedef struct {
    void *mappa;
    size_t lunghezza;
    char *dati;             // inizio dell'immagine nella mappa
    CARD32 passo;           // byte per riga nel dma-buf
    int fd;                 // il dma-buf (per DMA_BUF_IOCTL_SYNC), -1 se non c'è
    AHardwareBuffer *ahb;
    EGLImageKHR img;
    LorieBuffer *lorie;     // solo la radice: la vetrina, possiede il suo AHardwareBuffer
    GLuint vtex, vfb;       // texture e framebuffer della vetrina
} TodMappa;
static DevPrivateKeyRec todMappaKey;
static LorieBuffer *todRadiceBuf;
static DestroyPixmapProcPtr todDestroyPixmapOrig;

static TodMappa *todMappa(PixmapPtr pixmap) {
    return dixGetPrivateAddr(&pixmap->devPrivates, &todMappaKey);
}

static Bool todDestroyPixmap(PixmapPtr pixmap) {
    ScreenPtr screen = pixmap->drawable.pScreen;
    TodMappa m = *todMappa(pixmap);
    Bool ultima = pixmap->refcnt == 1, r;
    screen->DestroyPixmap = todDestroyPixmapOrig;
    r = screen->DestroyPixmap(pixmap);
    todDestroyPixmapOrig = screen->DestroyPixmap;
    screen->DestroyPixmap = todDestroyPixmap;
    if (!ultima)
        return r;
    if (m.vfb || m.vtex) {
        glamor_make_current(glamor_get_screen_private(screen));
        glDeleteFramebuffers(1, &m.vfb);
        glDeleteTextures(1, &m.vtex);
    }
    if (m.img)
        eglDestroyImageKHR(todDpy, m.img);
    if (m.lorie) {
        if (todRadiceBuf == m.lorie)
            todRadiceBuf = NULL;
        LorieBuffer_release(m.lorie);
    } else if (m.ahb)
        AHardwareBuffer_release(m.ahb);
    if (m.mappa)
        munmap(m.mappa, m.lunghezza);
    if (m.fd > 0)
        close(m.fd);
    return r;
}

// copia nel suo AHardwareBuffer le zone indicate (coordinate dell'immagine), tutte se n == 0
static void todCopia(TodMappa *m, int larghezza, int altezza, const BoxRec *box, int n) {
    AHardwareBuffer_Desc d;
    BoxRec tutto = { 0, 0, (short) larghezza, (short) altezza };
    char *dst = NULL;

    struct dma_buf_sync s = { DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ };
    uint64_t t = todAdesso(CLOCK_MONOTONIC), cpu = todAdesso(CLOCK_THREAD_CPUTIME_ID);

    // 1) la GPU del server potrebbe ancora leggere il fotogramma precedente da questo AHardwareBuffer
    static int senzaFinish = -1;
    if (senzaFinish < 0) {
        const char *e = getenv("TERMUX_X11_GLAMOR_NOFINISH");
        senzaFinish = e && !strcmp(e, "1");
    }
    glamor_make_current(glamor_get_screen_private(screenInfo.screens[0]));
    // XDesk, for measuring only: TERMUX_X11_GLAMOR_NOFINISH=1 skips the wait (the picture may tear)
    if (!senzaFinish)
        glFinish();
    TOD_PASSO(TOD_FINISH, t);
    if (AHardwareBuffer_lock(m->ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, (void **) &dst) || !dst)
        return;
    TOD_PASSO(TOD_LOCK, t);
    // 2) la GPU del client potrebbe non aver finito di disegnare nel dma-buf: si aspetta (sincronizzazione implicita)
    if (m->fd > 0)
        ioctl(m->fd, DMA_BUF_IOCTL_SYNC, &s);
    TOD_PASSO(TOD_SYNC, t);
    AHardwareBuffer_describe(m->ahb, &d);
    if (n == 0) {
        box = &tutto;
        n = 1;
    }
    for (int i = 0; i < n; i++) {
        int x1 = max(box[i].x1, 0), y1 = max(box[i].y1, 0), x2 = min(box[i].x2, larghezza), y2 = min(box[i].y2, altezza);
        if (x2 <= x1)
            continue;
        for (int y = y1; y < y2; y++)
            memcpy(dst + ((size_t) y * d.stride + x1) * 4, m->dati + (size_t) y * m->passo + x1 * 4, (size_t) (x2 - x1) * 4);
    }
    TOD_PASSO(TOD_MEMCPY, t);
    if (m->fd > 0) {
        s.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
        ioctl(m->fd, DMA_BUF_IOCTL_SYNC, &s);
    }
    AHardwareBuffer_unlock(m->ahb, NULL);
    TOD_PASSO(TOD_UNLOCK, t);
    todPassoNs[TOD_COPIA_CPU] += todAdesso(CLOCK_THREAD_CPUTIME_ID) - cpu;
    todPassoN[TOD_COPIA_CPU]++;
}

// Dal gancio di Present (lorieTryScheduleGpuCopy), prima che il contenuto dell'immagine venga usato.
// Si copia sempre l'immagine intera, non solo la zona dichiarata nel Present: i client (Chromium/Electron,
// Mesa) usano 2-3 buffer a turno e in ognuno ridisegnano ciò che è cambiato dall'ultima volta che hanno usato
// QUEL buffer, più di quanto dichiarano (29 ottobre: in un'app Electron ricomparivano pezzi di fotogrammi vecchi).
void todGlamorAggiorna(PixmapPtr pixmap, RegionPtr zona) {
    TodMappa *m = todMappa(pixmap);
    (void) zona;
    if (!m->ahb)
        return;
    todCopia(m, pixmap->drawable.width, pixmap->drawable.height, NULL, 0);
}

static PixmapPtr todImportaAhb(ScreenPtr screen, TodMappa *m, CARD16 width, CARD16 height, CARD8 depth) {
    static int stato = -1;   // -1 da provare, 0 non va, 1 va
    static const EGLint attr[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    const char *e = getenv("TERMUX_X11_GLAMOR_COPIA");
    AHardwareBuffer_Desc d = { .width = width, .height = height, .layers = 1,
                               .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
                               .usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE };
    PixmapPtr pixmap = NullPixmap;
    glamor_pixmap_fbo *fbo;
    GLuint tex = 0;
    GLint err;

    if (stato == 0 || (e && !strcmp(e, "1")))
        return NullPixmap;
    // l'immagine del client è BGRA in memoria: AHardwareBuffer RGBA8 e canali B/R scambiati in lettura
    if (AHardwareBuffer_allocate(&d, &m->ahb) || !m->ahb) {
        log(ERROR, "tod-glamor: AHardwareBuffer %ux%u non allocato", width, height);
        m->ahb = NULL;
        goto fallito;
    }
    glamor_make_current(glamor_get_screen_private(screen));
    while (glGetError() != GL_NO_ERROR) ;
    m->img = eglCreateImageKHR(todDpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                               eglGetNativeClientBufferANDROID(m->ahb), attr);
    if (!m->img) {
        log(ERROR, "tod-glamor: EGLImage non creata (0x%x)", eglGetError());
        goto fallito;
    }
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, m->img);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, GL_BLUE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_B, GL_RED);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    if ((err = glGetError()) != GL_NO_ERROR) {
        log(ERROR, "tod-glamor: texture sull'AHardwareBuffer non riuscita (0x%x)", err);
        goto fallito;
    }
    todCopia(m, width, height, NULL, 0);
    pixmap = glamor_create_pixmap(screen, width, height, depth, GLAMOR_CREATE_PIXMAP_NO_TEXTURE);
    if (!pixmap)
        goto fallito;
    glamor_set_pixmap_type(pixmap, GLAMOR_TEXTURE_DRM);
    if (!(fbo = glamor_create_fbo_from_tex(glamor_get_screen_private(screen), pixmap, width, height, tex, 0))) {
        log(ERROR, "tod-glamor: glamor non accetta la texture");
        goto fallito;
    }
    tex = 0;   // ora è di glamor
    glamor_pixmap_attach_fbo(pixmap, fbo);
    *todMappa(pixmap) = *m;
    if (stato < 0)
        log(INFO, "tod-glamor: DRI3 via AHardwareBuffer attivo (%ux%u)", width, height);
    stato = 1;
    return pixmap;

fallito:
    if (pixmap)
        screen->DestroyPixmap(pixmap);   // la mappa resta al chiamante: qui m non è ancora dell'immagine
    if (tex)
        glDeleteTextures(1, &tex);
    if (m->img)
        eglDestroyImageKHR(todDpy, m->img);
    if (m->ahb)
        AHardwareBuffer_release(m->ahb);
    m->img = NULL;
    m->ahb = NULL;
    if (stato < 0)
        stato = 0;
    return NullPixmap;
}

PixmapPtr todGlamorPixmapFromFds(ScreenPtr screen, CARD8 num_fds, const int *fds, CARD16 width, CARD16 height,
                                 const CARD32 *strides, const CARD32 *offsets, CARD8 depth, CARD8 bpp, CARD64 modifier) {
    const CARD64 RAW_MMAPPABLE_FD = 1274;
    TodMappa m = { 0 };
    PixmapPtr pixmap;

    if (num_fds != 1 || (modifier != DRM_FORMAT_MOD_INVALID && modifier != DRM_FORMAT_MOD_LINEAR && modifier != RAW_MMAPPABLE_FD)) {
        log(ERROR, "tod-glamor: DRI3 rifiutato (%d fd, modifier %llu)", num_fds, (unsigned long long) modifier);
        return NullPixmap;
    }
    m.lunghezza = (size_t) offsets[0] + (size_t) strides[0] * height;
    m.mappa = mmap(NULL, m.lunghezza, PROT_READ | PROT_WRITE, MAP_SHARED, fds[0], 0);
    if (m.mappa == MAP_FAILED) {
        log(ERROR, "tod-glamor: DRI3 mmap non riuscito: %s", strerror(errno));
        return NullPixmap;
    }
    m.dati = (char *) m.mappa + offsets[0];
    m.passo = strides[0];
    m.fd = fcntl(fds[0], F_DUPFD_CLOEXEC, 3);
    if ((pixmap = todImportaAhb(screen, &m, width, height, depth)))
        return pixmap;
    pixmap = screen->CreatePixmap(screen, 0, 0, depth, GLAMOR_CREATE_PIXMAP_CPU);
    if (!pixmap) {
        if (m.fd > 0)
            close(m.fd);
        munmap(m.mappa, m.lunghezza);
        return NullPixmap;
    }
    screen->ModifyPixmapHeader(pixmap, width, height, depth, bpp, strides[0], m.dati);
    *todMappa(pixmap) = m;
    return pixmap;
}

struct LorieBuffer *todGlamorRadice(void) {
    return todRadiceBuf;
}


// La radice in due copie ("doppio buffer", come i compositori): glamor disegna in una sua immagine normale;
// la "vetrina" è un AHardwareBuffer BGRA (come i pixel di X: l'app lo mostra senza scambiare i canali) che l'app
// legge con il suo id. Una volta per fotogramma todGlamorPubblica copia lì, con la GPU, solo le zone cambiate,
// sotto il lucchetto condiviso con l'app: così l'app non vede mai un disegno a metà (29 ottobre: con una sola
// copia, scritta mentre l'app la leggeva, c'erano glitch).
PixmapPtr todGlamorCreaRadice(ScreenPtr screen, int width, int height, int depth) {
    static const EGLint attr[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    AHardwareBuffer_Desc d = { .width = width, .height = height, .layers = 1,
                               .format = AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM,
                               .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                                        AHARDWAREBUFFER_USAGE_CPU_READ_RARELY | AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY };
    AHardwareBuffer *ahb = NULL;
    LorieBuffer *lorie;
    EGLImageKHR img;
    PixmapPtr pixmap;
    GLuint tex, fb;
    GLint err;
    TodMappa *m;

    pixmap = glamor_create_pixmap(screen, width, height, depth, 0);
    if (!pixmap || !glamor_get_pixmap_private(pixmap)->fbo)
        FatalError("tod-glamor: radice %dx%d: immagine di glamor non creata\n", width, height);
    if (AHardwareBuffer_allocate(&d, &ahb) || !ahb)
        FatalError("tod-glamor: radice %dx%d: AHardwareBuffer BGRA non allocato\n", width, height);
    if (!(lorie = LorieBuffer_wrapAHardwareBuffer(ahb)))
        FatalError("tod-glamor: radice: LorieBuffer non creato\n");
    glamor_make_current(glamor_get_screen_private(screen));
    while (glGetError() != GL_NO_ERROR) ;
    img = eglCreateImageKHR(todDpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, eglGetNativeClientBufferANDROID(ahb), attr);
    if (!img)
        FatalError("tod-glamor: radice: EGLImage non creata (0x%x)\n", eglGetError());
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, img);
    glGenFramebuffers(1, &fb);
    glBindFramebuffer(GL_FRAMEBUFFER, fb);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE || (err = glGetError()) != GL_NO_ERROR)
        FatalError("tod-glamor: radice: vetrina non utilizzabile\n");
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    m = todMappa(pixmap);
    m->ahb = ahb;
    m->img = img;
    m->lorie = lorie;
    m->vtex = tex;
    m->vfb = fb;
    todRadiceBuf = lorie;
    log(INFO, "tod-glamor: radice %dx%d con vetrina AHardwareBuffer BGRA (id %llu)", width, height,
        (unsigned long long) LorieBuffer_description(lorie)->id);
    return pixmap;
}

// Da lorieRedraw, con il lucchetto dell'app preso: copia nella vetrina le zone cambiate e aspetta la GPU.
void todGlamorPubblica(PixmapPtr radice, RegionPtr zona) {
    static int errori;
    glamor_pixmap_private *priv;
    TodMappa *m;
    BoxPtr b;
    int n;
    GLint err;

    uint64_t t = todAdesso(CLOCK_MONOTONIC);

    if (!radice || !(m = todMappa(radice))->vfb || !(priv = glamor_get_pixmap_private(radice)) || !priv->fbo)
        return;
    glamor_make_current(glamor_get_screen_private(radice->drawable.pScreen));
    glBindFramebuffer(GL_READ_FRAMEBUFFER, priv->fbo->fb);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, m->vfb);
    // Si copia tutta la radice, non solo le zone segnate come cambiate (29 ottobre: con le sole zone, passando il
    // fuoco dal desktop a un'app Electron, l'app mostrava per un fotogramma la finestra a bande spostate). Per la GPU è poco.
    // TERMUX_X11_GLAMOR_ZONE=1: solo le zone (per confronto).
    {
        const char *e = getenv("TERMUX_X11_GLAMOR_ZONE");
        if (e && !strcmp(e, "1")) {
            b = RegionRects(zona);
            n = RegionNumRects(zona);
            for (int i = 0; i < n; i++)
                glBlitFramebuffer(b[i].x1, b[i].y1, b[i].x2, b[i].y2, b[i].x1, b[i].y1, b[i].x2, b[i].y2,
                                  GL_COLOR_BUFFER_BIT, GL_NEAREST);
        } else {
            int w = radice->drawable.width, h = radice->drawable.height;
            glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glFinish();
    TOD_PASSO(TOD_PUBBLICA, t);
    if ((err = glGetError()) != GL_NO_ERROR && errori++ < 5)
        log(ERROR, "tod-glamor: copia nella vetrina non riuscita (0x%x)", err);
}

// Chiusura dello schermo: le risorse della radice escono dalla sua immagine (che distruggerà la chiusura stessa)
// e si liberano dopo, con todGlamorLiberaRadice.
void *todGlamorStaccaRadice(PixmapPtr pixmap) {
    TodMappa *m, *copia;
    if (!pixmap || !(copia = malloc(sizeof(*copia))))
        return NULL;
    m = todMappa(pixmap);
    *copia = *m;
    memset(m, 0, sizeof(*m));
    return copia;
}

void todGlamorLiberaRadice(void *radice) {
    TodMappa *m = radice;
    if (!m)
        return;
    if ((m->vfb || m->vtex) && eglGetCurrentContext() == todCtx) {
        glDeleteFramebuffers(1, &m->vfb);
        glDeleteTextures(1, &m->vtex);
    }
    if (m->img)
        eglDestroyImageKHR(todDpy, m->img);
    if (m->lorie) {
        if (todRadiceBuf == m->lorie)
            todRadiceBuf = NULL;
        LorieBuffer_release(m->lorie);
    }
    free(m);
}

Bool todGlamorAttivo(void) {
    static int attivo = -1;
    if (attivo < 0) {
        const char *e = getenv("TERMUX_X11_GLAMOR");
        attivo = e && !strcmp(e, "1");
    }
    return attivo;
}

static void todMakeCurrent(struct glamor_context *c) {
    if (eglGetCurrentContext() != c->ctx)
        eglMakeCurrent(c->display, EGL_NO_SURFACE, EGL_NO_SURFACE, c->ctx);
}

// Chiamata da glamor_init (GLAMOR_USE_EGL_SCREEN): gli diamo il nostro contesto.
void glamor_egl_screen_init(ScreenPtr screen, struct glamor_context *glamor_ctx) {
    glamor_ctx->display = todDpy;
    glamor_ctx->ctx = todCtx;
    glamor_ctx->drawable = EGL_NO_SURFACE;
    glamor_ctx->make_current = todMakeCurrent;
}

static EGLContext todCreaContesto(EGLDisplay dpy) {
    static const EGLint attr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    static const EGLint scelta[] = { EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE };
    EGLConfig cfg = NULL;
    EGLint n = 0;
    EGLContext ctx;

    if (epoxy_has_egl_extension(dpy, "EGL_KHR_no_config_context") &&
        (ctx = eglCreateContext(dpy, EGL_NO_CONFIG_KHR, EGL_NO_CONTEXT, attr)) != EGL_NO_CONTEXT)
        return ctx;
    if (!eglChooseConfig(dpy, scelta, &cfg, 1, &n) || n < 1)
        return EGL_NO_CONTEXT;
    return eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, attr);
}

// Al posto di exaDriverInit, dopo fbScreenInit e fbPictureInit.
Bool todGlamorPrepara(ScreenPtr screen) {
    EGLint maj = 0, min = 0;

    todDpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (todDpy == EGL_NO_DISPLAY || !eglInitialize(todDpy, &maj, &min)) {
        log(ERROR, "tod-glamor: eglInitialize non riuscito (0x%x)", eglGetError());
        return FALSE;
    }
    eglBindAPI(EGL_OPENGL_ES_API);
    if (!epoxy_has_egl_extension(todDpy, "EGL_KHR_surfaceless_context")) {
        log(ERROR, "tod-glamor: manca EGL_KHR_surfaceless_context");
        return FALSE;
    }
    if ((todCtx = todCreaContesto(todDpy)) == EGL_NO_CONTEXT ||
        !eglMakeCurrent(todDpy, EGL_NO_SURFACE, EGL_NO_SURFACE, todCtx)) {
        log(ERROR, "tod-glamor: contesto GLES 3 non creato (0x%x)", eglGetError());
        return FALSE;
    }
    log(INFO, "tod-glamor: EGL %d.%d, %s, %s", maj, min, glGetString(GL_RENDERER), glGetString(GL_VERSION));
    {
        static const char *egl[] = { "EGL_EXT_image_dma_buf_import", "EGL_EXT_image_dma_buf_import_modifiers",
                                     "EGL_ANDROID_image_native_buffer", "EGL_ANDROID_get_native_client_buffer",
                                     "EGL_KHR_image_base", "EGL_KHR_fence_sync", "EGL_ANDROID_native_fence_sync" };
        static const char *gl[] = { "GL_OES_EGL_image", "GL_OES_EGL_image_external", "GL_EXT_memory_object",
                                    "GL_EXT_memory_object_fd", "GL_EXT_semaphore_fd", "GL_EXT_EGL_image_storage" };
        for (unsigned i = 0; i < sizeof(egl) / sizeof(*egl); i++)
            log(INFO, "tod-glamor: %s %s", egl[i], epoxy_has_egl_extension(todDpy, egl[i]) ? "sì" : "no");
        for (unsigned i = 0; i < sizeof(gl) / sizeof(*gl); i++)
            log(INFO, "tod-glamor: %s %s", gl[i], epoxy_has_gl_extension(gl[i]) ? "sì" : "no");
    }
    if (!glamor_init(screen, GLAMOR_USE_EGL_SCREEN)) {
        log(ERROR, "tod-glamor: glamor_init non riuscito (estensioni in logcat: %s)",
            (const char *) glGetString(GL_EXTENSIONS));
        return FALSE;
    }
    if (!dixRegisterPrivateKey(&todMappaKey, PRIVATE_PIXMAP, sizeof(TodMappa)))
        return FALSE;
    todDestroyPixmapOrig = screen->DestroyPixmap;
    screen->DestroyPixmap = todDestroyPixmap;
    log(INFO, "tod-glamor: glamor attivo");
    return TRUE;
}

// Le altre funzioni che glamor si aspetta dal suo backend EGL di Xorg (qui non servono ancora).
int glamor_egl_fd_name_from_pixmap(ScreenPtr screen, PixmapPtr pixmap, CARD16 *stride, CARD32 *size) {
    return -1;
}

int glamor_egl_fds_from_pixmap(ScreenPtr screen, PixmapPtr pixmap, int *fds, uint32_t *offsets, uint32_t *strides,
                               uint64_t *modifier) {
    return 0;
}

int glamor_egl_fd_from_pixmap(ScreenPtr screen, PixmapPtr pixmap, CARD16 *stride, CARD32 *size) {
    return -1;
}

// glamor sui PC può prendere il contesto da GLX; qui il contesto è sempre EGL di Android.
Bool glamor_glx_screen_init(struct glamor_context *glamor_ctx) {
    return FALSE;
}
