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

void lorieUnregisterBuffer(LorieBuffer* buffer);

#define log(prio, ...) __android_log_print(ANDROID_LOG_ ## prio, "LorieNative", __VA_ARGS__)

// Attese della GPU con un limite: girano sotto il lucchetto condiviso con l'app, una GPU che non
// risponde non deve fermare tutto il server X (come il renderer, 100 ms e si prosegue).
#define TOD_ATTESA_GPU_NS 100000000LL

// XDesk: where a Present of a client's image spends its time (TERMUX_X11_MISURA=1, written with tod-misura):
// wall time of each step of todGlamorAggiorna, and of todGlamorPubblica, summed over 5 s
enum { TOD_ATTESA, TOD_SYNC, TOD_MEMCPY, TOD_FINE, TOD_COPIA_CPU, TOD_PUBBLICA, TOD_PASSI };
static uint64_t todPassoNs[TOD_PASSI], todPassoN[TOD_PASSI];
static uint64_t todAdesso(clockid_t c) {
    struct timespec t;
    clock_gettime(c, &t);
    return (uint64_t) t.tv_sec * 1000000000ull + t.tv_nsec;
}
#define TOD_PASSO(i, da) do { uint64_t _a = todAdesso(CLOCK_MONOTONIC); todPassoNs[i] += _a - (da); todPassoN[i]++; (da) = _a; } while (0)

void todGlamorMisura(uint64_t muro) {
    static const char *nomi[TOD_PASSI] = { "waiting for the GPU to be done with the copy", "dma-buf sync start", "memcpy",
                                           "sync end + swap", "the whole step, CPU time", "root to the shown buffer (blit + glFinish)" };
    for (int i = 0; i < TOD_PASSI; i++) {
        if (todPassoN[i])
            log(INFO, "tod-misura:   present step %s: %.1f%% of the time, %llu times, %.0f us each", nomi[i],
                100.0 * todPassoNs[i] / muro, (unsigned long long) todPassoN[i], todPassoNs[i] / 1000.0 / todPassoN[i]);
        todPassoNs[i] = todPassoN[i] = 0;
    }
}

static EGLDisplay todDpy = EGL_NO_DISPLAY;
static EGLContext todCtx = EGL_NO_CONTEXT;
/** Il lato più grande che glamor può disegnare in un fbo: oltre, la radice non si crea (FatalError). */
static GLint todMaxLato = 0;

Bool todGlamorAttivo(void);

int todGlamorMaxLato(void) {
    return todGlamorAttivo() && todMaxLato > 0 ? todMaxLato : 32767;
}

// DRI3: ogni immagine importata (dma-buf lineare di Turnip) è mappata in memoria. Con glamor:
//  - via AHardwareBuffer (predefinita): la GPU legge una copia dell'immagine in un AHardwareBuffer lineare
//    (EGLImage), aggiornata a ogni Present (todGlamorAggiorna, memcpy);
//  - ripiego, o con TERMUX_X11_GLAMOR_COPIA=1: immagine in memoria, che glamor carica da sé a ogni uso.
// (Android non ha EGL_EXT_image_dma_buf_import, e Adreno rifiuta texture su memoria di Turnip importata con
// GL_EXT_memory_object_fd: provato il 29 ottobre, and again by XDesk on 17 November.)
// XDesk: the copy is in two AHardwareBuffers, written in turn. They are uncached (CPU_WRITE_RARELY) and stay
// locked from allocation to the end, so a Present costs no lock, unlock or cache maintenance; the GPU may
// still be reading the buffer about to be written, so each one gets a fence when the image moves to the
// other, and the next write into it waits for that fence, not for the whole GPU (glFinish). Measured on
// 17 November, glmark2 in a window: 316 with lock, unlock and glFinish each frame, EXA 381, about 650 like
// this. One buffer, if the second can not be had: written after a glFinish.
typedef struct {
    AHardwareBuffer *ahb;
    EGLImageKHR img;
    GLuint tex;             // owned by glamor's fbo while this copy is the one shown, by us otherwise
    char *dati;             // the AHardwareBuffer, locked for writing all its life
    uint32_t passo;         // its pixels per row
    EGLSyncKHR fine;        // signalled when the GPU is done with what was queued while this copy was shown
} TodCopia;

typedef struct {
    void *mappa;
    size_t lunghezza;
    char *dati;             // inizio dell'immagine nella mappa
    CARD32 passo;           // byte per riga nel dma-buf
    int fd;                 // il dma-buf (per DMA_BUF_IOCTL_SYNC), -1 se non c'è
    TodCopia copia[2];      // XDesk: the GPU's copies of a client's image
    int copie, mostrata;    // how many copies (0: none, the image is in memory), which one glamor uses
    AHardwareBuffer *ahb;   // the root's shown buffer (with lorie)
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

// Frees a copy; its texture too unless glamor has it (conTex 0)
static void todLiberaCopia(TodCopia *c, int conTex) {
    if (c->fine != EGL_NO_SYNC_KHR)
        eglDestroySyncKHR(todDpy, c->fine);
    if (conTex && c->tex)
        glDeleteTextures(1, &c->tex);
    if (c->img)
        eglDestroyImageKHR(todDpy, c->img);
    if (c->ahb) {
        if (c->dati)
            AHardwareBuffer_unlock(c->ahb, NULL);
        AHardwareBuffer_release(c->ahb);
    }
    memset(c, 0, sizeof(*c));
    c->fine = EGL_NO_SYNC_KHR;
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
    if (m.vfb || m.vtex || m.copie) {
        glamor_make_current(glamor_get_screen_private(screen));
        if (m.vfb)
            glDeleteFramebuffers(1, &m.vfb);
        if (m.vtex)
            glDeleteTextures(1, &m.vtex);
    }
    // glamor has just deleted the shown copy's texture with its fbo
    for (int i = 0; i < m.copie; i++)
        todLiberaCopia(&m.copia[i], i != m.mostrata);
    if (m.img)
        eglDestroyImageKHR(todDpy, m.img);
    if (m.lorie) {
        if (todRadiceBuf == m.lorie)
            todRadiceBuf = NULL;
        // Come la distruzione di EXA: l'app lascia il buffer della radice vecchia (audit: uno per ogni ridimensionamento)
        lorieUnregisterBuffer(m.lorie);
        LorieBuffer_release(m.lorie);
    } else if (m.ahb)
        AHardwareBuffer_release(m.ahb);
    if (m.mappa)
        munmap(m.mappa, m.lunghezza);
    if (m.fd > 0)
        close(m.fd);
    return r;
}

// An uncached AHardwareBuffer the size of the image, locked for writing, with its texture
static Bool todNuovaCopia(TodCopia *c, int larghezza, int altezza) {
    static const EGLint attr[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    AHardwareBuffer_Desc d = { .width = larghezza, .height = altezza, .layers = 1,
                               .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
                               .usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE };
    GLint err;

    memset(c, 0, sizeof(*c));
    c->fine = EGL_NO_SYNC_KHR;
    // l'immagine del client è BGRA in memoria: AHardwareBuffer RGBA8 e canali B/R scambiati in lettura
    if (AHardwareBuffer_allocate(&d, &c->ahb) || !c->ahb) {
        log(ERROR, "tod-glamor: AHardwareBuffer %ux%u non allocato", larghezza, altezza);
        c->ahb = NULL;
        return FALSE;
    }
    AHardwareBuffer_describe(c->ahb, &d);
    c->passo = d.stride;
    if (AHardwareBuffer_lock(c->ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, NULL, (void **) &c->dati) || !c->dati) {
        log(ERROR, "tod-glamor: AHardwareBuffer %ux%u non bloccato", larghezza, altezza);
        c->dati = NULL;
        goto fallito;
    }
    while (glGetError() != GL_NO_ERROR) ;
    c->img = eglCreateImageKHR(todDpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, eglGetNativeClientBufferANDROID(c->ahb), attr);
    if (!c->img) {
        log(ERROR, "tod-glamor: EGLImage non creata (0x%x)", eglGetError());
        goto fallito;
    }
    glGenTextures(1, &c->tex);
    glBindTexture(GL_TEXTURE_2D, c->tex);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, c->img);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, GL_BLUE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_B, GL_RED);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    if ((err = glGetError()) != GL_NO_ERROR) {
        log(ERROR, "tod-glamor: texture sull'AHardwareBuffer non riuscita (0x%x)", err);
        goto fallito;
    }
    return TRUE;

fallito:
    todLiberaCopia(c, 1);
    return FALSE;
}

// The client's image into a copy, all of it (see todGlamorAggiorna)
static void todScrivi(TodMappa *m, TodCopia *c, int larghezza, int altezza, uint64_t *t) {
    struct dma_buf_sync s = { DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ };
    // la GPU del client potrebbe non aver finito di disegnare nel dma-buf: si aspetta (sincronizzazione implicita)
    if (m->fd > 0)
        ioctl(m->fd, DMA_BUF_IOCTL_SYNC, &s);
    TOD_PASSO(TOD_SYNC, *t);
    for (int y = 0; y < altezza; y++)
        memcpy(c->dati + (size_t) y * c->passo * 4, m->dati + (size_t) y * m->passo, (size_t) larghezza * 4);
    TOD_PASSO(TOD_MEMCPY, *t);
    if (m->fd > 0) {
        s.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
        ioctl(m->fd, DMA_BUF_IOCTL_SYNC, &s);
    }
}

// Dal gancio di Present (lorieTryScheduleGpuCopy), prima che il contenuto dell'immagine venga usato.
// Si copia sempre l'immagine intera, non solo la zona dichiarata nel Present: i client (Chromium/Electron,
// Mesa) usano 2-3 buffer a turno e in ognuno ridisegnano ciò che è cambiato dall'ultima volta che hanno usato
// QUEL buffer, più di quanto dichiarano (29 ottobre: in un'app Electron ricomparivano pezzi di fotogrammi vecchi).
// XDesk: written into the copy glamor is not using, which then becomes the one it uses.
void todGlamorAggiorna(PixmapPtr pixmap, RegionPtr zona) {
    TodMappa *m = todMappa(pixmap);
    glamor_pixmap_private *priv = glamor_get_pixmap_private(pixmap);
    int w = pixmap->drawable.width, h = pixmap->drawable.height;
    uint64_t t = todAdesso(CLOCK_MONOTONIC), cpu = todAdesso(CLOCK_THREAD_CPUTIME_ID);
    TodCopia *vecchia, *nuova;
    (void) zona;

    if (!m->copie || !priv || !priv->fbo)
        return;
    glamor_make_current(glamor_get_screen_private(pixmap->drawable.pScreen));
    vecchia = &m->copia[m->mostrata];
    if (m->copie == 1) {
        // one copy: the GPU may still be reading it
        glFinish();
        TOD_PASSO(TOD_ATTESA, t);
        todScrivi(m, vecchia, w, h, &t);
        TOD_PASSO(TOD_FINE, t);
    } else {
        nuova = &m->copia[1 - m->mostrata];
        if (nuova->fine != EGL_NO_SYNC_KHR) {
            // Il tempo è limitato: questa attesa avviene sotto il lucchetto del server, una GPU
            // bloccata lo fermerebbe per sempre. Scaduto il tempo si scrive lo stesso, come fa il
            // percorso di ripiego senza fence.
            static int scadute;
            if (eglClientWaitSyncKHR(todDpy, nuova->fine, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, TOD_ATTESA_GPU_NS) == EGL_TIMEOUT_EXPIRED_KHR &&
                scadute++ < 5)
                log(ERROR, "tod-glamor: la GPU non ha finito con la copia entro 100 ms, si scrive lo stesso");
            eglDestroySyncKHR(todDpy, nuova->fine);
            nuova->fine = EGL_NO_SYNC_KHR;
        } else
            glFinish();   // no fence was had: the old way
        TOD_PASSO(TOD_ATTESA, t);
        todScrivi(m, nuova, w, h, &t);
        // what glamor queued so far may read the old copy: a fence for it, before it is written again
        vecchia->fine = eglCreateSyncKHR(todDpy, EGL_SYNC_FENCE_KHR, NULL);
        glFlush();
        priv->fbo->tex = nuova->tex;
        if (priv->fbo->fb) {
            glBindFramebuffer(GL_FRAMEBUFFER, priv->fbo->fb);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, nuova->tex, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }
        m->mostrata = 1 - m->mostrata;
        TOD_PASSO(TOD_FINE, t);
    }
    todPassoNs[TOD_COPIA_CPU] += todAdesso(CLOCK_THREAD_CPUTIME_ID) - cpu;
    todPassoN[TOD_COPIA_CPU]++;
}

static PixmapPtr todImportaAhb(ScreenPtr screen, TodMappa *m, CARD16 width, CARD16 height, CARD8 depth) {
    static int stato = -1;   // -1 da provare, 0 non va, 1 va
    const char *e = getenv("TERMUX_X11_GLAMOR_COPIA");
    PixmapPtr pixmap = NullPixmap;
    glamor_pixmap_fbo *fbo;
    uint64_t t = 0;

    if (stato == 0 || (e && !strcmp(e, "1")))
        return NullPixmap;
    glamor_make_current(glamor_get_screen_private(screen));
    if (!todNuovaCopia(&m->copia[0], width, height))
        goto fallito;
    m->copie = 1;
    if (todNuovaCopia(&m->copia[1], width, height))
        m->copie = 2;
    m->mostrata = 0;
    todScrivi(m, &m->copia[0], width, height, &t);
    pixmap = glamor_create_pixmap(screen, width, height, depth, GLAMOR_CREATE_PIXMAP_NO_TEXTURE);
    if (!pixmap)
        goto fallito;
    glamor_set_pixmap_type(pixmap, GLAMOR_TEXTURE_DRM);
    if (!(fbo = glamor_create_fbo_from_tex(glamor_get_screen_private(screen), pixmap, width, height, m->copia[0].tex, 0))) {
        log(ERROR, "tod-glamor: glamor non accetta la texture");
        goto fallito;
    }
    glamor_pixmap_attach_fbo(pixmap, fbo);   // copia[0].tex is now glamor's, as mostrata says
    *todMappa(pixmap) = *m;
    if (stato < 0)
        log(INFO, "tod-glamor: DRI3 via AHardwareBuffer attivo (%ux%u, %d copie)", width, height, m->copie);
    stato = 1;
    return pixmap;

fallito:
    if (pixmap)
        screen->DestroyPixmap(pixmap);   // la mappa resta al chiamante: qui m non è ancora dell'immagine
    for (int i = 0; i < 2; i++)
        todLiberaCopia(&m->copia[i], 1);
    m->copie = 0;
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
    // todScrivi copia width*4 byte per riga: un'immagine a 16 bit o un passo più corto leggerebbe
    // oltre la mappa, e una dimensione che va in overflow darebbe una mappa più corta delle righe.
    if (bpp != 32 || strides[0] < (CARD32) width * 4 ||
        __builtin_mul_overflow((size_t) strides[0], (size_t) height, &m.lunghezza) ||
        __builtin_add_overflow(m.lunghezza, (size_t) offsets[0], &m.lunghezza)) {
        log(ERROR, "tod-glamor: DRI3 rifiutato (bpp %d, passo %u per larghezza %d, %u righe, offset %u)",
            bpp, strides[0], width, height, offsets[0]);
        return NullPixmap;
    }
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
    // Si aspetta la fine della copia con una fence, non con glFinish: questa funzione gira sotto
    // il lucchetto che l'app aspetta, e una GPU bloccata fermerebbe tutto il server X. Scaduto il
    // tempo si prosegue, come il percorso di ripiego.
    {
        EGLSyncKHR fine = eglCreateSyncKHR(todDpy, EGL_SYNC_FENCE_KHR, NULL);
        static int scadute;
        glFlush();
        if (fine != EGL_NO_SYNC_KHR) {
            if (eglClientWaitSyncKHR(todDpy, fine, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, TOD_ATTESA_GPU_NS) == EGL_TIMEOUT_EXPIRED_KHR &&
                scadute++ < 5)
                log(ERROR, "tod-glamor: la GPU non ha finito la copia nella vetrina entro 100 ms, si prosegue");
            eglDestroySyncKHR(todDpy, fine);
        } else if (scadute++ < 5)
            log(ERROR, "tod-glamor: fence non creata, non si aspetta la GPU");
    }
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
        lorieUnregisterBuffer(m->lorie);  // anche la radice dell'ultima generazione: l'app la lascia (audit)
        LorieBuffer_release(m->lorie);
    }
    free(m);
}

// XDesk: on unless TERMUX_X11_GLAMOR=0 (from 0.6.0-beta.7; the X server's environment is ~/.config/xdesk/x11-env),
// and off again when it can not start (todGlamorSpegni): the screen is then EXA's, as before.
static int todAttivo = -1;
Bool todGlamorAttivo(void) {
    if (todAttivo < 0) {
        const char *e = getenv("TERMUX_X11_GLAMOR");
        todAttivo = !(e && !strcmp(e, "0"));
    }
    return todAttivo;
}

void todGlamorSpegni(void) {
    todAttivo = 0;
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

// Una vetrina 64x64 come quella della radice: AHB BGRA da GPU, EGLImage, texture, framebuffer completo. Poi tutto via.
static Bool todProvaVetrina(void) {
    static const EGLint attr[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    AHardwareBuffer_Desc d = { .width = 64, .height = 64, .layers = 1,
                               .format = AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM,
                               .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                                        AHARDWAREBUFFER_USAGE_CPU_READ_RARELY | AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY };
    AHardwareBuffer *ahb = NULL;
    EGLImageKHR img;
    GLuint tex = 0, fb = 0;
    Bool ok;

    if (!epoxy_has_egl_extension(todDpy, "EGL_ANDROID_image_native_buffer") ||
        !epoxy_has_egl_extension(todDpy, "EGL_ANDROID_get_native_client_buffer") ||
        !epoxy_has_egl_extension(todDpy, "EGL_KHR_image_base") || !epoxy_has_gl_extension("GL_OES_EGL_image"))
        return FALSE;
    if (AHardwareBuffer_allocate(&d, &ahb) || !ahb)
        return FALSE;
    while (glGetError() != GL_NO_ERROR) ;
    img = eglCreateImageKHR(todDpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, eglGetNativeClientBufferANDROID(ahb), attr);
    if (!img) {
        AHardwareBuffer_release(ahb);
        return FALSE;
    }
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, img);
    glGenFramebuffers(1, &fb);
    glBindFramebuffer(GL_FRAMEBUFFER, fb);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE && glGetError() == GL_NO_ERROR;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fb);
    glDeleteTextures(1, &tex);
    eglDestroyImageKHR(todDpy, img);
    AHardwareBuffer_release(ahb);
    return ok;
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
    // Lo stesso contesto per ogni generazione del server: uno nuovo a ogni reset restava lì (audit)
    if ((todCtx == EGL_NO_CONTEXT && (todCtx = todCreaContesto(todDpy)) == EGL_NO_CONTEXT) ||
        !eglMakeCurrent(todDpy, EGL_NO_SURFACE, EGL_NO_SURFACE, todCtx)) {
        log(ERROR, "tod-glamor: contesto GLES 3 non creato (0x%x)", eglGetError());
        return FALSE;
    }
    log(INFO, "tod-glamor: EGL %d.%d, %s, %s", maj, min, glGetString(GL_RENDERER), glGetString(GL_VERSION));
    // XDesk 0.6.0: di serie solo su Adreno, l'unica GPU su cui è stato provato. Mali e PowerVR: le immagini DRI3 via
    // socket AHB (il wrapper Vulkan), la coerenza CPU/GPU degli AHB tenuti bloccati, i compilatori di shader più
    // severi sono rischi non verificati (audit). TERMUX_X11_GLAMOR=1 lo accende lo stesso
    {
        const char *e = getenv("TERMUX_X11_GLAMOR"), *r = (const char *) glGetString(GL_RENDERER);
        if (!(e && !strcmp(e, "1")) && !(r && strstr(r, "Adreno"))) {
            log(INFO, "tod-glamor: GPU %s non Adreno, EXA (TERMUX_X11_GLAMOR=1 per glamor)", r ? r : "?");
            return FALSE;
        }
    }
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &todMaxLato);
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
    // La radice (todGlamorCreaRadice) arriva dopo il punto in cui si può ripiegare su EXA, e lì un errore è fatale:
    // la si prova qui in piccolo, con le stesse estensioni e lo stesso AHardwareBuffer (audit, 17 novembre)
    if (!todProvaVetrina()) {
        log(ERROR, "tod-glamor: la vetrina AHardwareBuffer non funziona su questo dispositivo");
        return FALSE;
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
