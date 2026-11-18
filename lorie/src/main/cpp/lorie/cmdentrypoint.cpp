#pragma clang diagnostic ignored "-Wunknown-pragmas"
#pragma clang diagnostic ignored "-Wmissing-prototypes"
#pragma ide diagnostic ignored "bugprone-reserved-identifier"
#pragma ide diagnostic ignored "OCUnusedMacroInspection"
#pragma ide diagnostic ignored "EndlessLoop"
#define __USE_GNU
#ifdef HAVE_DIX_CONFIG_H
#include <dix-config.h>
#endif
#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <libgen.h>
#include <cerrno>
extern "C" {
#include <globals.h>
#define class lorie_reserved_class
#define public lorie_reserved_public
#include <xkbsrv.h>
#include <inpututils.h>
#include <randrstr.h>
#include <mi.h>
#undef class
#undef public
}
#include <dlfcn.h>
#include <linux/in.h>
#include <arpa/inet.h>
#include <poll.h>
#include "lorie.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_ ## prio, "LorieNative", __VA_ARGS__)

static int argc = 0;
static char** argv = nullptr;
__LIBC_HIDDEN__ volatile int conn_fd = -1;
extern DeviceIntPtr lorieMouse, lorieTouch, lorieKeyboard, loriePen, lorieEraser;
extern ScreenPtr pScreenPtr;
extern "C" int ucs2keysym(long ucs);
extern "C" void lorieKeysymKeyboardEvent(KeySym keysym, int down);

char *xtrans_unix_path_x11 = nullptr;
char *xtrans_unix_dir_x11 = nullptr;

struct xorg_list registeredBuffers;

static JNIEnv* serverEnv = nullptr;
static jobject thiz = nullptr;
static jmethodID sendBroadcast = nullptr;
static jmethodID sendBroadcastDelayed = nullptr;
static jmethodID setDocumentTag = nullptr;

// A stream socket hands back whole requests only by accident; the event protocol has no framing
// to find the next event again, so a short read or EOF makes the connection unusable.
static bool readFully(int fd, void* data, size_t len) {
    char* p = (char*) data;
    while (len) {
        ssize_t n = read(fd, p, len);
        if (n < 0) {
            if (errno == EINTR)
                continue;
            return false;
        }
        if (n == 0)
            return false;
        p += n;
        len -= (size_t) n;
    }
    return true;
}

// One event to the app, whole: the event lock keeps a header and its payload from being split.
static void sendEventToApp(const lorieEvent* e, const void* payload, size_t payloadSize) {
    lorieEventWriteLock();
    if (conn_fd != -1)
        lorieWriteEventLocked(conn_fd, e, payload, payloadSize);
    lorieEventWriteUnlock();
}

static jboolean start(JNIEnv *env, jobject self, jobjectArray args) {
    pthread_t t;
    JavaVM* vm = nullptr;

    // Same switch as InitOutput.c's server diagnostics: the per-event logs in this file are
    // gated on it instead of going to logcat on every touch.
    lorieDebugEnabled = getenv("TERMUX_X11_DEBUG") != NULL;

    thiz = env->NewGlobalRef(self);
    if (!thiz)
        FatalError("Failed to create a global reference for the CmdEntryPoint instance");
    sendBroadcast = env->GetMethodID(env->GetObjectClass(self), "sendBroadcast", "()V");
    sendBroadcastDelayed = env->GetMethodID(env->GetObjectClass(self), "sendBroadcastDelayed", "()V");
    setDocumentTag = env->GetMethodID(env->GetObjectClass(self), "setDocumentTag", "(Ljava/lang/String;)V");
    auto detectTracer = []() -> Bool {
        FILE *fp;
        char line[256];
        int pid = 0;

        fp = fopen("/proc/self/status", "r");
        if (!fp)
            return TRUE;

        while (fgets(line, sizeof(line), fp)) {
            if (strncmp(line, "TracerPid:", 10) == 0) {
                pid = (int) strtol(line + 10, nullptr, 10);
                break;
            }
        }

        if (pid != 0)
            log(INFO, "Tracer detected");

        fclose(fp);
        return pid != 0;
    };
    // execv's argv array is a bit incompatible with Java's String[], so we do some converting here...
    argc = env->GetArrayLength(args) + 1; // Leading executable path
    argv = (char**) calloc(argc, sizeof(char*));

    argv[0] = (char*) "Xlorie";
    for(int i=1; i<argc; i++) {
        auto js = (jstring) env->GetObjectArrayElement(args, i - 1);
        const char *pjc = env->GetStringUTFChars(js, JNI_FALSE);
        argv[i] = (char *) calloc(strlen(pjc) + 1, sizeof(char)); //Extra char for the terminating NULL
        strcpy((char *) argv[i], pjc);
        env->ReleaseStringUTFChars(js, pjc);
    }

    {
        cpu_set_t mask;
        long num_cpus = sysconf(_SC_NPROCESSORS_ONLN);

        CPU_ZERO(&mask);
        for (int i = num_cpus/2; i < num_cpus; i++)
            CPU_SET(i, &mask);

        if (sched_setaffinity(0, sizeof(cpu_set_t), &mask) == -1)
            log(ERROR, "Failed to set process affinity: %s", strerror(errno));
    }

    if (getenv("TERMUX_X11_DEBUG") && !fork()) {
        // Printing logs of local logcat.
        char pid[32] = {0};
        prctl(PR_SET_PDEATHSIG, SIGTERM);
        sprintf(pid, "%d", getppid());
        execlp("logcat", "logcat", "--pid", pid, nullptr);
    }

    // No matter what tracer is attached.
    // In the case of gdb or lldb LD_PRELOAD is already set.
    // In the case of proot or proot-distro libtermux-exec in LD_PRELOAD will break linking.
    if (access("/data/data/com.termux/files/usr/lib/libtermux-exec.so", F_OK) == 0 && !detectTracer()
            && !getenv("XSTARTUP_LD_PRELOAD"))
        setenv("LD_PRELOAD", "/data/data/com.termux/files/usr/lib/libtermux-exec.so", 1);

    // adb sets TMPDIR to /data/local/tmp which is pretty useless.
    if (!strcmp("/data/local/tmp", getenv("TMPDIR") ?: ""))
        unsetenv("TMPDIR");

    if (!getenv("TMPDIR")) {
        if (access("/tmp", F_OK) == 0)
            setenv("TMPDIR", "/tmp", 1);
        else if (access("/data/data/com.termux/files/usr/tmp", F_OK) == 0)
            setenv("TMPDIR", "/data/data/com.termux/files/usr/tmp", 1);
    }

    if (!getenv("TMPDIR")) {
        char* error = (char*) "$TMPDIR is not set. Normally it is pointing to /tmp of a container.";
        log(ERROR, "%s", error);
        dprintf(2, "%s\n", error);
        return JNI_FALSE;
    }

    {
        char* tmp = getenv("TMPDIR");
        char cwd[1024] = {0};

        if (!getcwd(cwd, sizeof(cwd)) || access(cwd, F_OK) != 0)
            chdir(tmp);
        asprintf(&xtrans_unix_path_x11, "%s/.X11-unix/X", tmp);
        asprintf(&xtrans_unix_dir_x11, "%s/.X11-unix/", tmp);
    }

    log(VERBOSE, "Using TMPDIR=\"%s\"", getenv("TMPDIR"));

    {
        const char *root_dir = dirname(getenv("TMPDIR"));
        const char* pathes[] = {
                "/etc/X11/fonts", "/usr/share/fonts/X11", "/share/fonts", nullptr
        };
        for (int i=0; pathes[i]; i++) {
            char current_path[1024] = {0};
            snprintf(current_path, sizeof(current_path), "%s%s", root_dir, pathes[i]);
            if (access(current_path, F_OK) == 0) {
                char default_font_path[4096] = {0};
                snprintf(default_font_path, sizeof(default_font_path),
                         "%s/misc,%s/TTF,%s/OTF,%s/Type1,%s/100dpi,%s/75dpi",
                         current_path, current_path, current_path, current_path, current_path, current_path);
                defaultFontPath = strdup(default_font_path);
                break;
            }
        }
    }

    if (!getenv("XKB_CONFIG_ROOT")) {
        // chroot case
        const char *root_dir = dirname(getenv("TMPDIR"));
        char current_path[1024] = {0};
        snprintf(current_path, sizeof(current_path), "%s/usr/share/X11/xkb", root_dir);
        if (access(current_path, F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", current_path, 1);
    }

    if (!getenv("XKB_CONFIG_ROOT")) {
        // proot case
        if (access("/usr/share/xkeyboard-config-2", F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", "/usr/share/xkeyboard-config-2", 1);
        else if (access("/usr/share/X11/xkb", F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", "/usr/share/X11/xkb", 1);
        // Termux case
        else if (access("/data/data/com.termux/files/usr/share/xkeyboard-config-2", F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", "/data/data/com.termux/files/usr/share/xkeyboard-config-2", 1);
        else if (access("/data/data/com.termux/files/usr/share/X11/xkb", F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", "/data/data/com.termux/files/usr/share/X11/xkb", 1);
    }

    if (!getenv("XKB_CONFIG_ROOT")) {
        char* error = (char*) "$XKB_CONFIG_ROOT is not set. Normally it is pointing to /usr/share/X11/xkb of a container.";
        log(ERROR, "%s", error);
        dprintf(2, "%s\n", error);
        return JNI_FALSE;
    }

    XkbBaseDirectory = getenv("XKB_CONFIG_ROOT");
    if (access(XkbBaseDirectory, F_OK) != 0) {
        log(ERROR, "%s is unaccessible: %s\n", XkbBaseDirectory, strerror(errno));
        printf("%s is unaccessible: %s\n", XkbBaseDirectory, strerror(errno));
        return JNI_FALSE;
    }

    env->GetJavaVM(&vm);

    // Starts the vsync chain; it keeps itself posted only while there is something to do.
    lorieSetChoreographer(AChoreographer_getInstance());

    xorg_list_init(&registeredBuffers);
    pthread_create(&t, nullptr, +[](void* cookie) -> void* {
        if (((JavaVM*) cookie)->AttachCurrentThread(&serverEnv, nullptr) != JNI_OK)
            FatalError("Failed to attach the X server thread to the JVM");
        exit(dix_main(argc, (char**) argv, (char*[]) { nullptr }));
    }, vm);
    return JNI_TRUE;
}

static Bool handleTouchEvent(__unused ClientPtr pClient, void *closure) {
    ValuatorMask mask;
    auto *e = (lorieEvent*) closure;
    double x = max(min((float) e->touch.x, pScreenPtr->width), 0);
    double y = max(min((float) e->touch.y, pScreenPtr->height), 0);
    valuator_mask_zero(&mask);
    DDXTouchPointInfoPtr touch = TouchFindByDDXID(lorieTouch, e->touch.id, FALSE);

    // Avoid duplicating events
    if (touch && touch->active) {
        double oldx = 0, oldy = 0;
        if (e->touch.type == XI_TouchUpdate &&
            valuator_mask_fetch_double(touch->valuators, 0, &oldx) &&
            valuator_mask_fetch_double(touch->valuators, 1, &oldy) &&
            oldx == x && oldy == y)
            goto end;
    }

    // Sometimes activity part does not send XI_TouchBegin and sends only XI_TouchUpdate.
    if (e->touch.type == XI_TouchUpdate && (!touch || !touch->active))
        e->touch.type = XI_TouchBegin;

    if (e->touch.type == XI_TouchEnd && (!touch || !touch->active))
        goto end;

    valuator_mask_set_double(&mask, 0, x * 0xFFFF / (float) pScreenPtr->width);
    valuator_mask_set_double(&mask, 1, y * 0xFFFF / (float) pScreenPtr->height);
    QueueTouchEvents(lorieTouch, e->touch.type, e->touch.id, 0, &mask);
    lorieSetCursorVisible(FALSE);

    end:
    free(e);
    return TRUE;
}

// The app's end of the event socket is gone. Only tear down the state belonging to the fd that
// actually hung up: a HUP can arrive for a descriptor a newer connection has already replaced,
// and clearing conn_fd then would leave that connection without a server (a black screen after
// a window was recreated).
static void disconnectLorie(int fd) {
    LorieBuffer* buf;

    InputThreadUnregisterDev(fd);
    lorieEventWriteLock();
    if (conn_fd != fd) {
        lorieEventWriteUnlock();
        return;
    }
    conn_fd = -1;
    close(fd);
    lorieEnableClipboardSync(FALSE);
    while ((buf = LorieBufferList_first(&registeredBuffers)))
        LorieBuffer_removeFromList(buf);
    lorieEventWriteUnlock();
}

void handleLorieEvents(int fd, __unused int ready, __unused void *ignored) {
    ValuatorMask mask;
    lorieEvent e = {0};
    valuator_mask_zero(&mask);

    if (ready & X_NOTIFY_ERROR) {
        disconnectLorie(fd);
        return;
    }

    again:
    if (readFully(fd, &e, sizeof(e))) {
        switch(e.type) {
            case EVENT_SCREEN_SIZE: {
                // The DPI shares its slot with the name pointer (see lorie.h), so it goes after the name and its zero
                size_t nameSize = e.screenSize.name_size;
                size_t tail, alloc;
                uint32_t dpi = e.screenSize.dpi;
                lorieEvent* copy;

                // A name bigger than the X server can ever use is a corrupt event; the addition
                // must not wrap into a short allocation that readFully would then overrun.
                if (nameSize > 65536 ||
                    __builtin_add_overflow(sizeof(lorieEvent), nameSize, &tail) ||
                    __builtin_add_overflow(tail, 1, &tail) ||
                    __builtin_add_overflow(tail, sizeof(uint32_t), &alloc) ||
                    !(copy = (lorieEvent*) calloc(1, alloc))) {
                    log(ERROR, "Screen size event with a bad name size %zu", nameSize);
                    disconnectLorie(fd);
                    return;
                }
                memcpy(copy, &e, sizeof(e));
                copy->screenSize.name = nameSize ? (char*) (copy + 1) : nullptr;
                if (nameSize && !readFully(fd, copy->screenSize.name, nameSize)) {
                    free(copy);
                    disconnectLorie(fd);
                    return;
                }
                memcpy((char*) copy + tail, &dpi, sizeof(dpi));
                QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
                    // This must be done only on X server thread.
                    auto* e = (lorieEvent*) closure;
                    uint32_t dpi;
                    memcpy(&dpi, (char*) (e + 1) + e->screenSize.name_size + 1, sizeof(dpi));
                    __android_log_print(ANDROID_LOG_ERROR, "tx11-request", "window changed: %d %d %u dpi %s", e->screenSize.width, e->screenSize.height, dpi, e->screenSize.name);
                    lorieConfigureNotify(e->screenSize.width, e->screenSize.height, e->screenSize.framerate, (int) dpi, e->screenSize.name_size, e->screenSize.name);
                    free(e);
                    return TRUE;
                }, nullptr, copy);
                lorieWakeServer();
                break;
            }
            case EVENT_TOUCH: {
                auto *copy = (lorieEvent*) calloc(1, sizeof(lorieEvent));
                memcpy(copy, &e, sizeof(e));
                QueueWorkProc(handleTouchEvent, nullptr, copy);
                lorieWakeServer();
                break;
            }
            case EVENT_STYLUS: {
                static int buttons_prev = 0;
                uint32_t released, pressed, diff;
                DeviceIntPtr device = e.stylus.mouse ? lorieMouse : (e.stylus.eraser ? lorieEraser : loriePen);
                if (!device) {
                    if (lorieDebugEnabled)
                        __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "got stylus event but device is not requested\n");
                    break;
                }
                if (lorieDebugEnabled)
                    __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "got stylus event %f %f %d %d %d %d %s\n", e.stylus.x, e.stylus.y, e.stylus.pressure, e.stylus.tilt_x, e.stylus.tilt_y, e.stylus.orientation,
                                        device == lorieMouse ? "lorieMouse" : (device == loriePen ? "loriePen" : "lorieEraser"));

                valuator_mask_set_double(&mask, 0, max(min(e.stylus.x, pScreenPtr->width), 0));
                valuator_mask_set_double(&mask, 1, max(min(e.stylus.y, pScreenPtr->height), 0));
                if (device != lorieMouse) {
                    valuator_mask_set_double(&mask, 2, e.stylus.pressure);
                    valuator_mask_set_double(&mask, 3, e.stylus.tilt_x);
                    valuator_mask_set_double(&mask, 4, e.stylus.tilt_y);
                    valuator_mask_set_double(&mask, 5, e.stylus.orientation);
                }
                QueuePointerEvents(device, MotionNotify, 0, POINTER_ABSOLUTE | POINTER_DESKTOP | (device == lorieMouse ? POINTER_NORAW : 0), &mask);

                diff = buttons_prev ^ e.stylus.buttons;
                released = diff & ~e.stylus.buttons;
                pressed = diff & e.stylus.buttons;

                for (int i=0; i<3; i++) {
                    if (released & 0x1) {
                        QueuePointerEvents(device, ButtonRelease, i + 1, POINTER_RELATIVE, nullptr);
                        if (lorieDebugEnabled)
                            __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "sending %d press", i+1);
                    }
                    if (pressed & 0x1) {
                        QueuePointerEvents(device, ButtonPress, i + 1, POINTER_RELATIVE, nullptr);
                        if (lorieDebugEnabled)
                            __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "sending %d release", i+1);
                    }
                    released >>= 1;
                    pressed >>= 1;
                }
                buttons_prev = e.stylus.buttons;

                break;
            }
            case EVENT_CURSOR_HIDDEN: {
                lorieSetCursorHidden(e.cursorHidden.hidden);
                break;
            }
            case EVENT_STYLUS_ENABLE: {
                lorieSetStylusEnabled(e.stylusEnable.enable);
                break;
            }
            case EVENT_MOUSE: {
                int flags;
                lorieSetCursorVisible(TRUE);
                switch(e.mouse.detail) {
                    case 0: // BUTTON_UNDEFINED
                        flags = (e.mouse.relative) ? POINTER_RELATIVE | POINTER_ACCELERATE : POINTER_ABSOLUTE | POINTER_SCREEN | POINTER_NORAW;
                        if (!e.mouse.relative) {
                            e.mouse.x = max(0, min(e.mouse.x, pScreenPtr->width));
                            e.mouse.y = max(0, min(e.mouse.y, pScreenPtr->height));
                        }
                        valuator_mask_set_double(&mask, 0, (double) e.mouse.x);
                        valuator_mask_set_double(&mask, 1, (double) e.mouse.y);
                        QueuePointerEvents(lorieMouse, MotionNotify, 0, flags, &mask);
                        break;
                    case 1: // BUTTON_LEFT
                    case 2: // BUTTON_MIDDLE
                    case 3: // BUTTON_RIGHT
                    case 8: // back (XDesk: a mouse's side button), X button 8 like a real X mouse's
                    case 9: // forward
                        QueuePointerEvents(lorieMouse, e.mouse.down ? ButtonPress : ButtonRelease, e.mouse.detail, POINTER_RELATIVE, nullptr);
                        break;
                    case 4: // BUTTON_SCROLL
                        if (e.mouse.x != 0.0f) {
                            valuator_mask_zero(&mask);
                            valuator_mask_set_double(&mask, 2, (double) e.mouse.x / 120);
                            QueuePointerEvents(lorieMouse, MotionNotify, 0, POINTER_RELATIVE, &mask);
                        }
                        if (e.mouse.y != 0.0f) {
                            valuator_mask_zero(&mask);
                            valuator_mask_set_double(&mask, 3, (double) e.mouse.y / 120);
                            QueuePointerEvents(lorieMouse, MotionNotify, 0, POINTER_RELATIVE, &mask);
                        }
                        break;
                }
                break;
            }
            case EVENT_KEY:
            case EVENT_UNICODE: {
                // UTF-32 code points span at most 21 bits (max 0x10FFFF), so shifting left by 1 is safe even on a 32-bit uintptr_t.
                uintptr_t payload = e.type == EVENT_KEY ? 1u | ((uintptr_t) e.key.state << 1) | ((uintptr_t) e.key.key << 2) : ((uintptr_t) e.unicode.code << 1);
                QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
                    // This must be done only on X server thread (touches XKB state directly).
                    uintptr_t payload = (uintptr_t) closure;
                    if (payload & 1) {
                        uintptr_t keycode = payload >> 2;
                        if (keycode > 255) {
                            for (size_t i = 0; i < lorieExtendedKeysCount; i++)
                                if (lorieExtendedKeys[i].code == keycode - 8)
                                    lorieKeysymKeyboardEvent(lorieExtendedKeys[i].keysym, payload >> 1 & 1);
                            return TRUE;
                        }
                        QueueKeyboardEvents(lorieKeyboard, (payload >> 1 & 1) ? KeyPress : KeyRelease, keycode);
                        mieqProcessInputEvents();
                    } else {
                        int ks = ucs2keysym((long) (payload >> 1));
                        if (lorieDebugEnabled)
                            __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "Trying to input keysym %d\n", ks);
                        lorieKeysymKeyboardEvent(ks, TRUE);
                        lorieKeysymKeyboardEvent(ks, FALSE);
                    }
                    return TRUE;
                }, nullptr, (void*) payload);
                lorieWakeServer();
                break;
            }
            case EVENT_CLIPBOARD_ENABLE:
                lorieEnableClipboardSync(e.clipboardEnable.enable);
                break;
            case EVENT_CLIPBOARD_ANNOUNCE:
                QueueWorkProc(+[](__unused ClientPtr pClient, __unused void *closure) -> Bool {
                    // This must be done only on X server thread.
                    lorieHandleClipboardAnnounce();
                    return TRUE;
                }, nullptr, nullptr);
                lorieWakeServer();
                break;
            case EVENT_CLIPBOARD_SEND: {
                // The app sends at most a few MB; a count near 2^32 would wrap count + 1 to an empty block
                size_t want = e.clipboardSend.count, got = 0;
                char *data = want <= (64u << 20) ? (char*) calloc(1, want + 1) : nullptr;
                while (data && got < want) {
                    ssize_t n = read(fd, data + got, want - got);
                    if (n < 0 && errno == EINTR) continue;
                    if (n <= 0) break;
                    got += (size_t) n;
                }
                if (!data || got < want) {
                    // Refused or cut short: what follows on the socket is no longer an event, end the connection
                    log(ERROR, "clipboard of %zu bytes: %s", want, data ? "cut short" : "refused");
                    free(data);
                    handleLorieEvents(fd, X_NOTIFY_ERROR, nullptr);
                    return;
                }
                data[got] = 0;
                QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
                    // This must be done only on X server thread.
                    lorieHandleClipboardData((const char*) closure);
                    return TRUE;
                }, nullptr, data);
                lorieWakeServer();
                break;
            }
            case EVENT_RENDERER_WAKEUP_COND: {
                int wakeupFd = ancil_recv_fd(fd);
                if (wakeupFd >= 0)
                    lorieSetRendererWakeupCond(wakeupFd);
                break;
            }
            case EVENT_GPU_COPY_DONE:
                QueueWorkProc(+[](__unused ClientPtr pClient, __unused void *closure) -> Bool {
                    // This must be done only on X server thread (touches present's internal vblank queue).
                    lorieRecheckGpuCopies();
                    return TRUE;
                }, nullptr, nullptr);
                lorieWakeServer();
                break;
            case EVENT_SYNC: {
                auto serial = (uintptr_t) e.sync.serial;
                QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
                    // This must be done only on X server thread. Forces mieq to drain everything
                    // enqueued before this marker, so the reply is a reliable "the server has
                    // applied every event sent so far" barrier.
                    mieqProcessInputEvents();
                    lorieSendSyncReply((uint32_t) (uintptr_t) closure);
                    return TRUE;
                }, nullptr, (void*) serial);
                lorieWakeServer();
                break;
            }
            case EVENT_LOCK_KEYS_STATE: {
                auto *copy = (lorieEvent*) calloc(1, sizeof(lorieEvent));
                memcpy(copy, &e, sizeof(e));
                QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
                    // This must be done only on X server thread (touches XKB state directly).
                    auto *e = (lorieEvent*) closure;
                    lorieSyncLockKeysState(e->lockKeysState.state);
                    free(e);
                    return TRUE;
                }, nullptr, copy);
                lorieWakeServer();
                break;
            }
        }

        int n;
        if (ioctl(fd, FIONREAD, &n) >= 0 && n > sizeof(e))
            goto again;
    } else {
        // EOF or a read error: the rest of the stream can not be parsed as events.
        disconnectLorie(fd);
        return;
    }
}

void lorieSendClipboardData(const char* data) {
    if (data) {
        size_t len = strlen(data);
        lorieEvent e = { .clipboardSend = { .t = EVENT_CLIPBOARD_SEND, .count = (uint32_t) len } };
        sendEventToApp(&e, data, len);
    }
}

void lorieSendSyncReply(uint32_t serial) {
    lorieEvent e = { .sync = { .t = EVENT_SYNC_REPLY, .serial = serial } };
    sendEventToApp(&e, nullptr, 0);
}

void lorieSendHeartbeat(void) {
    lorieEvent e = { .type = EVENT_HEARTBEAT };
    sendEventToApp(&e, nullptr, 0);
}

void lorieRequestClipboard(void) {
    lorieEvent e = { .type = EVENT_CLIPBOARD_REQUEST };
    sendEventToApp(&e, nullptr, 0);
}

// Preloaded by rendererCanRelockWhileLocked(): swaps the executable's main() for the probe.
extern "C" __attribute__((visibility("default"))) void __libc_init(void *rawArgs, void (*onExit)(void), int (*mainFn)(int, char **, char **), void *structors) {
    static int (*realMain)(int, char **, char **);
    realMain = mainFn;
    ((__typeof__(__libc_init) *) dlsym(RTLD_NEXT, "__libc_init"))(rawArgs, onExit, [](int argc, char **argv, char **envp) {
        if (argc == 3 && !strcmp(argv[1], "--relock-probe"))
            return rendererRelockProbeChild(atoi(argv[2]));
        return realMain(argc, argv, envp);
    }, structors);
}

bool lorieConnectionAlive(int fd) {
    if (fd == -1)
        return false;

    // Check if socket is closed or has errors.
    struct pollfd p = { .fd = fd, .events = POLLIN | POLLHUP | POLLERR | POLLRDHUP };
    return !(poll(&p, 1, 0) == 1 && (p.revents & (POLLERR | POLLNVAL | POLLRDHUP | POLLHUP)));
}

void lorieSendSharedServerState(int memfd) {
    lorieEvent e = { .type = EVENT_SHARED_SERVER_STATE };
    lorieEventWriteLock();
    // The fd goes with the event it was announced by: both under the lock.
    if (conn_fd != -1 && lorieWriteEventLocked(conn_fd, &e, nullptr, 0))
        ancil_send_fd(conn_fd, memfd);
    lorieEventWriteUnlock();
}

void lorieRegisterBuffer(LorieBuffer* buffer) {
    unsigned long id;
    const LorieBuffer_Desc* desc;
    lorieEvent e = { .type = EVENT_ADD_BUFFER };

    if (!buffer)
        return;
    id = LorieBuffer_description(buffer)->id;
    if (LorieBufferList_findById(&registeredBuffers, id))
        return; // Already registered

    lorieEventWriteLock();
    if (conn_fd != -1 && lorieWriteEventLocked(conn_fd, &e, nullptr, 0)) {
        LorieBuffer_sendHandleToUnixSocket(buffer, conn_fd);
        LorieBuffer_addToList(buffer, &registeredBuffers);
        desc = LorieBuffer_description(buffer);
        log(INFO, "Sent shared buffer width %d stride %d height %d format %d type %d id %llu", desc->width, desc->stride, desc->height, desc->format, desc->type, desc->id);
    }
    lorieEventWriteUnlock();
}

void lorieUnregisterBuffer(LorieBuffer* buffer) {
    unsigned long id;
    lorieEvent e = {0};

    if (!buffer || (!LorieBufferList_findById(&registeredBuffers, (id = LorieBuffer_description(buffer)->id))))
        return;  // Not exist or not registered so no need to unregister

    e.removeBuffer.t = EVENT_REMOVE_BUFFER;
    e.removeBuffer.id = id;
    lorieEventWriteLock();
    if (conn_fd != -1) {
        lorieWriteEventLocked(conn_fd, &e, nullptr, 0);
        LorieBuffer_removeFromList(buffer);
    }
    lorieEventWriteUnlock();
}

extern "C" void DDXNotifyFocusChanged(void) {
    lorieEvent e = { .type = EVENT_WINDOW_FOCUS_CHANGED };
    sendEventToApp(&e, nullptr, 0);
}

static jobject getXConnection(JNIEnv *env, __unused jobject cls) {
    int client[2];
    jclass ParcelFileDescriptorClass = env->FindClass("android/os/ParcelFileDescriptor");
    jmethodID adoptFd = env->GetStaticMethodID(ParcelFileDescriptorClass, "adoptFd", "(I)Landroid/os/ParcelFileDescriptor;");
    socketpair(AF_UNIX, SOCK_STREAM, 0, client);
    QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
        int fd = (int) (int64_t) closure;
        int old;

        InputThreadRegisterDev(fd, handleLorieEvents, nullptr);
        // Swap under the event lock: writers read conn_fd there, and disconnectLorie compares
        // under it, so a HUP on the old fd can not clear the new connection.
        lorieEventWriteLock();
        old = conn_fd;
        conn_fd = fd;
        lorieEventWriteUnlock();
        if (old != -1 && old != fd) {
            InputThreadUnregisterDev(old);
            close(old);
        }
        lorieActivityConnected();
        return TRUE;
    }, nullptr, (void*) (int64_t) client[1]);
    lorieWakeServer();

    return env->CallStaticObjectMethod(ParcelFileDescriptorClass, adoptFd, client[0]);
}

static jobject getLogcatOutput(JNIEnv *env, __unused jobject cls) {
    jclass ParcelFileDescriptorClass = env->FindClass("android/os/ParcelFileDescriptor");
    jmethodID adoptFd = env->GetStaticMethodID(ParcelFileDescriptorClass, "adoptFd", "(I)Landroid/os/ParcelFileDescriptor;");
    const char *debug = getenv("TERMUX_X11_DEBUG");
    if (debug && !strcmp(debug, "1")) {
        pthread_t t;
        int p[2];
        pipe(p);
        fchmod(p[1], 0777);
        pthread_create(&t, nullptr, +[](void *arg) -> void* {
            char buffer[4096];
            size_t len;
            while((len = read((int) (int64_t) arg, buffer, 4096)) >=0)
                write(2, buffer, len);
            close((int) (int64_t) arg);
            return nullptr;
        }, (void*) (uint64_t) p[0]);
        return env->CallStaticObjectMethod(ParcelFileDescriptorClass, adoptFd, p[1]);
    }
    return nullptr;
}

static void reportFatalError(JNIEnv *env, __unused jobject cls, jstring message) {
    if (conn_fd != -1)
        return;

    const char *chars = env->GetStringUTFChars(message, nullptr);
    char *copy = strdup(chars);
    env->ReleaseStringUTFChars(message, chars);

    QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
        // Runs the same teardown as any other FatalError, on the server's own thread.
        FatalError("%s", (char *) closure);
    }, nullptr, copy);
    lorieWakeServer();
}

// Called from OsVendorInit() once the display number and "-tag" argument (if any) are resolved,
// so the port to knock on and the tag to report back to Java are already known.
void lorieListenForKnocks(int port, const char *tag) {
    // Loopback only: the app knocks from this device (activity.cpp), nothing on the network has to
    struct sockaddr_in address = { .sin_family = AF_INET, .sin_port = htons(port), .sin_addr = { .s_addr = htonl(INADDR_LOOPBACK) } };
    int fd, reuse = 1;

    serverEnv->CallVoidMethod(thiz, setDocumentTag, tag ? serverEnv->NewStringUTF(tag) : nullptr);

    // Even in the case if it will fail for some reason everything will work fine
    // But connection will be delayed a bit

    if ((fd = socket(AF_INET, SOCK_STREAM, 0)) < 0) {
        log(ERROR, "Failed to create the knock-listening socket: %s", strerror(errno));
        serverEnv->CallVoidMethod(thiz, sendBroadcastDelayed);
        return;
    }

    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &reuse, sizeof(reuse));

    if (bind(fd, (struct sockaddr *) &address, sizeof(address)) < 0) {
        log(ERROR, "Failed to bind the knock-listening socket to port %d: %s", port, strerror(errno));
        close(fd);
        serverEnv->CallVoidMethod(thiz, sendBroadcastDelayed);
        return;
    }

    if (listen(fd, 5) < 0) {
        log(ERROR, "Failed to listen on the knock-listening socket: %s", strerror(errno));
        close(fd);
        serverEnv->CallVoidMethod(thiz, sendBroadcastDelayed);
        return;
    }

    SetNotifyFd(fd, +[](int fd, int ready, __unused void *data) {
        struct sockaddr_in address;
        socklen_t addrlen = sizeof(address);
        uint8_t buffer[512] = {0};
        int client, count;

        if (ready & X_NOTIFY_ERROR) {
            RemoveNotifyFd(fd);
            close(fd);
            return;
        }

        if ((client = accept(fd, (struct sockaddr *) &address, &addrlen)) < 0) {
            log(ERROR, "Failed to accept a connection on the knock-listening socket: %s", strerror(errno));
            return;
        }

        if (poll((struct pollfd[]) {{ .fd = client, .events = POLLIN }}, 1, 200) > 0 &&
            (count = recv(client, buffer, sizeof(buffer), MSG_DONTWAIT)) > 0 &&
            count >= (int) sizeof(MAGIC) && !memcmp(buffer, MAGIC, sizeof(MAGIC))) {
            log(DEBUG, "New client connection!\n");
            serverEnv->CallVoidMethod(thiz, sendBroadcast);
        }
        close(client);
    }, X_NOTIFY_READ, NULL);

    serverEnv->CallVoidMethod(thiz, sendBroadcastDelayed);
}

void registerCmdEntryPointNatives(JNIEnv *env) {
    static JNINativeMethod methods[] = {
            {"start", "([Ljava/lang/String;)Z", (void *) &start},
            {"getXConnection", "()Landroid/os/ParcelFileDescriptor;", (void *) &getXConnection},
            {"getLogcatOutput", "()Landroid/os/ParcelFileDescriptor;", (void *) &getLogcatOutput},
            {"reportFatalError", "(Ljava/lang/String;)V", (void *) &reportFatalError},
            {"connected", "()Z", (void *) +[]() -> jboolean { return conn_fd != -1; }}, // @CriticalNative
    };
    jclass cls = env->FindClass("com/termux/x11/CmdEntryPoint");
    env->RegisterNatives(cls, methods, sizeof(methods)/sizeof(methods[0]));
    lorieRegisterWindowsNatives(env);
}

void abort(void) {
    _exit(134);
}

void exit(int code) {
    _exit(code);
}
