#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <cstring>
#include <pthread.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <linux/ashmem.h>
#include <cerrno>
#include <jni.h>
#include <android/looper.h>
#include <cwchar>
#include <linux/in.h>
#include <arpa/inet.h>
#include <poll.h>
#include <new>
#include "lorie.h"

#pragma clang diagnostic ignored "-Wunknown-pragmas"
#pragma ide diagnostic ignored "cppcoreguidelines-narrowing-conversions"
#pragma ide diagnostic ignored "ConstantFunctionResult"
#define log(prio, ...) __android_log_print(ANDROID_LOG_ ## prio, "LorieNative", __VA_ARGS__)
// `r` must be a LorieViewResources* — the connection fd is per-instance state, not a process global.
// The event goes through the view's sender thread, so the ordering with text being paced is kept.
#define sendEvent(r, ...) do { lorieEvent e_ = { __VA_ARGS__ }; lorieSendEvent((r), e_, nullptr, 0); } while (0)

bool lorieDebugEnabled = false;

// Timestamp of the last real input reaching the X session, from any source. Read/written only
// from the Android main thread via JNI.
static volatile int64_t lastInputTimestampMs = 0;

static int64_t nowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static struct {
    jclass self;
    jmethodID clientConnectedStateChanged, resetIme;
    jfieldID lastServerHeartbeat;
} MainActivity = {nullptr};

static struct {
    jclass self;
    jmethodID forName;
    jmethodID decode;
} Charset = {nullptr};

static struct {
    jclass self;
    jmethodID toString;
} CharBuffer = {nullptr};

static struct {
    jmethodID setLaunchWindowingMode;
} ActivityOptions = {nullptr};

// One queued event, or one text commit to parse and pace on the sender thread.
struct PendingEvent {
    PendingEvent* next;
    lorieEvent event;
    char* payload;      // owned: the event's payload, or the UTF-8 text to pace
    size_t payloadSize;
    bool text;
};

// Bundles the native state belonging to the current LorieView instance so it can be released
// as a unit when that instance is torn down, instead of leaking as scattered process globals.
struct LorieViewResources {
    Renderer renderer;
    JNIEnv* env = nullptr;    // GUI-thread JNIEnv. Must be used only in GUI thread.
    jobject thiz = nullptr;   // global ref to the owning LorieView
    jobject activity = nullptr; // global ref to the owning MainActivity
    volatile int connFd = -1;
    bool destroyed = true;

    // Events are written by one thread per view: a text commit is paced (the X server wants a
    // little time between two synthetic characters), and the events sent after it must not
    // overtake it. Only this thread ever waits on the pacing, never the UI thread.
    pthread_t eventThread{};
    pthread_mutex_t eventQueueLock{};
    pthread_cond_t eventQueueCond{};
    PendingEvent* eventHead = nullptr;
    PendingEvent* eventTail = nullptr;
    bool eventThreadRunning = false;
    bool eventStopping = false;

    LorieViewResources(JNIEnv* callerEnv, jobject view);
    ~LorieViewResources();
    void startEventThread();
    void stopEventThread();
    void connect(jint fd);
    int xcallback(int fd, int events);
    void showServerVersionMismatch();
};

// ---- The event socket in this process -------------------------------------
// A stream socket can return less than a whole event, and the protocol has no framing to find
// the next event again: a short read or EOF means the connection can not be used any more.
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

// The UI thread and the renderer thread (EVENT_GPU_COPY_DONE, see notifyGpuCopyDone) write to
// the same socket: one lock around each whole event keeps them from splitting each other.
static pthread_mutex_t eventWriteLock = PTHREAD_MUTEX_INITIALIZER;

static bool writeFully(int fd, const void* data, size_t len) {
    const char* p = (const char*) data;
    while (len) {
        ssize_t n = write(fd, p, len);
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

bool lorieWriteEventLocked(int fd, const lorieEvent* event, const void* payload, size_t payloadSize) {
    return fd >= 0 && writeFully(fd, event, sizeof(*event)) &&
           (!payloadSize || writeFully(fd, payload, payloadSize));
}

void lorieEventWriteLock(void) { pthread_mutex_lock(&eventWriteLock); }
void lorieEventWriteUnlock(void) { pthread_mutex_unlock(&eventWriteLock); }

// Writes to whichever fd the view is connected to right now, under the event lock: connect() can
// swap or close the fd under the same lock, so no event can go to a descriptor that is gone.
static void writeEventToView(LorieViewResources* r, const lorieEvent& e, const void* payload, size_t payloadSize) {
    lorieEventWriteLock();
    if (r->connFd != -1)
        lorieWriteEventLocked(r->connFd, &e, payload, payloadSize);
    lorieEventWriteUnlock();
}

// The text as key/unicode events, 2.5 ms apart: the X server takes a synthetic character at a
// time, and a whole paste fired at once lands late in the window it was typed into.
static void sendTextNow(LorieViewResources* r, const char* text, size_t length) {
    mbstate_t mbstate = { 0 };
    const char* p = text;

    while (length && *p) {
        wchar_t wc;
        size_t remaining = length - (size_t) (p - text);
        size_t len = mbrtowc(&wc, p, remaining, &mbstate);

        if (len == (size_t) -1 || len == (size_t) -2) {
            log(ERROR, "Invalid UTF-8 sequence encountered");
            break;
        }
        if (len == 0)
            break;

        if (wc == L'\n') {
            lorieEvent down = { .key = { .t = EVENT_KEY, .key = KEY_ENTER + 8, .state = 1 } };
            lorieEvent up = { .key = { .t = EVENT_KEY, .key = KEY_ENTER + 8, .state = 0 } };
            writeEventToView(r, down, nullptr, 0);
            writeEventToView(r, up, nullptr, 0);
        } else if (wc != L'\r') {
            lorieEvent unicode = { .unicode = { .t = EVENT_UNICODE, .code = (uint32_t) wc } };
            writeEventToView(r, unicode, nullptr, 0);
        }

        p += len;
        if ((size_t) (p - text) >= length)
            break;
        usleep(2500);
        if (__atomic_load_n(&r->eventStopping, __ATOMIC_RELAXED))
            break;
    }
}

static void* eventThreadMain(void* arg) {
    auto* r = (LorieViewResources*) arg;

    pthread_mutex_lock(&r->eventQueueLock);
    for (;;) {
        while (!r->eventHead && !r->eventStopping)
            pthread_cond_wait(&r->eventQueueCond, &r->eventQueueLock);
        if (r->eventStopping)
            break;

        PendingEvent* job = r->eventHead;
        r->eventHead = job->next;
        if (!r->eventHead)
            r->eventTail = nullptr;
        pthread_mutex_unlock(&r->eventQueueLock);

        if (job->text)
            sendTextNow(r, job->payload, job->payloadSize);
        else
            writeEventToView(r, job->event, job->payload, job->payloadSize);

        free(job->payload);
        free(job);
        pthread_mutex_lock(&r->eventQueueLock);
    }
    pthread_mutex_unlock(&r->eventQueueLock);
    return nullptr;
}

void LorieViewResources::startEventThread() {
    pthread_mutex_init(&eventQueueLock, nullptr);
    pthread_cond_init(&eventQueueCond, nullptr);
    eventThreadRunning = pthread_create(&eventThread, nullptr, eventThreadMain, this) == 0;
    if (!eventThreadRunning)
        log(ERROR, "Could not start the event sender thread, sending input from the caller");
}

void LorieViewResources::stopEventThread() {
    if (eventThreadRunning) {
        pthread_mutex_lock(&eventQueueLock);
        __atomic_store_n(&eventStopping, true, __ATOMIC_RELAXED);
        pthread_cond_broadcast(&eventQueueCond);
        pthread_mutex_unlock(&eventQueueLock);
        pthread_join(eventThread, nullptr);
        eventThreadRunning = false;
    }

    while (eventHead) {
        PendingEvent* job = eventHead;
        eventHead = job->next;
        free(job->payload);
        free(job);
    }
    eventTail = nullptr;
}

// Takes ownership of the job. Events are queued in send order; a text commit stays at the head
// while it is paced, so anything sent after it waits behind it instead of overtaking it.
static void queueEvent(LorieViewResources* r, PendingEvent* job) {
    pthread_mutex_lock(&r->eventQueueLock);
    if (r->eventStopping) {
        pthread_mutex_unlock(&r->eventQueueLock);
        free(job->payload);
        free(job);
        return;
    }
    if (r->eventTail)
        r->eventTail->next = job;
    else
        r->eventHead = job;
    r->eventTail = job;
    pthread_cond_signal(&r->eventQueueCond);
    pthread_mutex_unlock(&r->eventQueueLock);
}

static void lorieSendEvent(LorieViewResources* r, const lorieEvent& e, const void* payload, size_t payloadSize) {
    if (!r || r->connFd == -1)
        return;

    if (!r->eventThreadRunning) {
        writeEventToView(r, e, payload, payloadSize);
        return;
    }

    PendingEvent* job = (PendingEvent*) calloc(1, sizeof(*job));
    if (!job) {
        writeEventToView(r, e, payload, payloadSize); // rare: do not lose input, at the cost of order
        return;
    }
    if (payloadSize) {
        job->payload = (char*) malloc(payloadSize);
        if (!job->payload) {
            free(job);
            writeEventToView(r, e, payload, payloadSize);
            return;
        }
        memcpy(job->payload, payload, payloadSize);
        job->payloadSize = payloadSize;
    }
    job->event = e;
    queueEvent(r, job);
}

// Takes ownership of `text`.
static void lorieSendText(LorieViewResources* r, char* text, size_t length) {
    if (!r || r->connFd == -1) {
        free(text);
        return;
    }

    if (r->eventThreadRunning) {
        PendingEvent* job = (PendingEvent*) calloc(1, sizeof(*job));
        if (job) {
            job->text = true;
            job->payload = text;
            job->payloadSize = length;
            queueEvent(r, job);
            return;
        }
    }

    // No sender thread: pace on the caller's thread, like before.
    sendTextNow(r, text, length);
    free(text);
}

static jclass FindClassOrDie(JNIEnv *env, const char* name) {
    jclass clazz = env->FindClass(name);
    if (!clazz) {
        char buffer[1024] = {0};
        sprintf(buffer, "class %s not found", name);
        log(ERROR, "%s", buffer);
        env->FatalError(buffer);
        return nullptr;
    }

    return (jclass) env->NewGlobalRef(clazz);
}

static jmethodID FindMethodOrDie(JNIEnv *env, jclass clazz, const char* name, const char* signature, jboolean isStatic) {
    jmethodID method = isStatic ? env->GetStaticMethodID(clazz, name, signature) : env->GetMethodID(clazz, name, signature);
    if (!method) {
        char buffer[1024] = {0};
        sprintf(buffer, "method %s %s not found", name, signature);
        log(ERROR, "%s", buffer);
        env->FatalError(buffer);
        return nullptr;
    }

    return method;
}

// @CriticalNative: no implicit JNIEnv*/jclass, unlike the @FastNative entries below.
static jboolean requestConnection(__unused jlong ptr) {
#define check(cond, fmt, ...) if ((cond)) do { __android_log_print(ANDROID_LOG_ERROR, "requestConnection", fmt, ## __VA_ARGS__); goto end; } while (0)
    bool sent = JNI_FALSE;
    // We do not want to block GUI thread for a long time so we will set timeout to 20 msec.
    struct sockaddr_in server = { .sin_family = AF_INET, .sin_port = htons(PORT) };
    server.sin_addr.s_addr = inet_addr("127.0.0.1");
    int so_error, sock = socket(AF_INET, SOCK_STREAM, 0);
    check(sock < 0, "Could not create socket: %s", strerror(errno));
    check(fcntl(sock, F_SETFL, O_NONBLOCK) < 0, "failed to set socket non-block: %s", strerror(errno));
    int r;
    r = connect(sock, (struct sockaddr *)&server, sizeof(server));
    check(r < 0 && errno != EINPROGRESS, "failed to connect socket: %s", strerror(errno));
    if (r < 0 && errno == EINPROGRESS) {
        // Connection is in progress; use poll to wait for it
        struct pollfd pfd = { .fd = sock, .events = POLLOUT };
        r = poll(&pfd, 1, 20);  // timeout set to 50ms
        if (!r) goto end;
        // check(!r, "Connection timed out after 20ms."); // We do not want to flood logcat with this message
        check(r < 0, "poll failed: %s", strerror(errno));
        socklen_t len = sizeof(so_error);
        check(getsockopt(sock, SOL_SOCKET, SO_ERROR, &so_error, &len) < 0, "getsockopt failed: %s", strerror(errno));
        if (so_error == ECONNREFUSED) goto end; // Regular situation which happens often if server is not started. No need to spam logcat with this.
        check(so_error != 0, "Connection failed: %s", strerror(so_error));

        check(write(sock, MAGIC, sizeof(MAGIC)) < 0, "failed to send message: %s", strerror(errno));
        sent = JNI_TRUE;
        goto end;
    }

    check(1, "something went wrong: %s, %s", strerror(errno), strerror(r));

    end: if (sock >= 0) close(sock);
    return sent;
#undef errorReturn
}

static jlong nativeInit(JNIEnv *env, jobject thiz) {
    if (!Charset.self) {
        // Init clipboard-related JNI stuff
        Charset.self = FindClassOrDie(env, "java/nio/charset/Charset");
        Charset.forName = FindMethodOrDie(env, Charset.self, "forName", "(Ljava/lang/String;)Ljava/nio/charset/Charset;", JNI_TRUE);
        Charset.decode = FindMethodOrDie(env, Charset.self, "decode", "(Ljava/nio/ByteBuffer;)Ljava/nio/CharBuffer;", JNI_FALSE);

        CharBuffer.self = FindClassOrDie(env,  "java/nio/CharBuffer");
        CharBuffer.toString = FindMethodOrDie(env, CharBuffer.self, "toString", "()Ljava/lang/String;", JNI_FALSE);

        MainActivity.self = FindClassOrDie(env,  "com/termux/x11/MainActivity");
        MainActivity.clientConnectedStateChanged = FindMethodOrDie(env, MainActivity.self, "clientConnectedStateChanged", "()V", JNI_FALSE);
        MainActivity.lastServerHeartbeat = env->GetFieldID(MainActivity.self, "lastServerHeartbeat", "J");
        MainActivity.resetIme = FindMethodOrDie(env, env->GetObjectClass(thiz), "resetIme", "()V", JNI_FALSE);
    }

    return (jlong) (intptr_t) new (malloc(sizeof(LorieViewResources))) LorieViewResources(env, thiz);
}

LorieViewResources::LorieViewResources(JNIEnv *callerEnv, jobject view) {
    JavaVM* vm;
    destroyed = false;
    renderer.init(callerEnv, view);
    renderer.connFdPtr = &connFd; // lets the renderer thread wake up a GPU copy waiter

    callerEnv->GetJavaVM(&vm);
    vm->AttachCurrentThread(&env, nullptr);
    thiz = env->NewGlobalRef(view);

    jfieldID activityField = env->GetFieldID(env->GetObjectClass(view), "activity", "Lcom/termux/x11/MainActivity;");
    jobject a = env->GetObjectField(view, activityField);
    if (a)
        activity = env->NewGlobalRef(a);

    startEventThread();
    connect(-1);
}

LorieViewResources::~LorieViewResources() {
    destroyed = true;

    // Stop the sender before the fd goes: a queued event must not reach a descriptor number
    // that connect() or a later open could reuse.
    stopEventThread();

    if (connFd != -1) {
        ALooper_removeFd(ALooper_forThread(), connFd);
        lorieEventWriteLock();
        int fd = connFd;
        connFd = -1;
        close(fd);
        lorieEventWriteUnlock();
    }

    renderer.destroy();

    if (thiz) {
        env->DeleteGlobalRef(thiz);
        thiz = nullptr;
    }

    if (activity) {
        env->DeleteGlobalRef(activity);
        activity = nullptr;
    }
}

// Size of a shared memory region (memfd or ashmem), -1 if it can not be found out.
static off_t sharedRegionSize(int fd) {
    struct stat st = {};
    if (fstat(fd, &st) == 0 && st.st_size > 0)
        return st.st_size;
    int size = ioctl(fd, ASHMEM_GET_SIZE, nullptr);
    return size > 0 ? size : -1;
}

// The X server and this app do not agree on the shared state: nothing can be drawn until the X
// server is restarted, so tell the user (a toast, the view itself stays empty).
void LorieViewResources::showServerVersionMismatch() {
    static const char message[] = "XDesk: the running X server is from another XDesk version and must be restarted. "
                                  "Run \"xdesk stop\" in Termux, then start the desktop again.";
    jclass viewClass = env->FindClass("android/view/View");
    jclass toastClass = env->FindClass("android/widget/Toast");
    jmethodID getContext = viewClass ? env->GetMethodID(viewClass, "getContext", "()Landroid/content/Context;") : nullptr;
    jmethodID makeText = toastClass ? env->GetStaticMethodID(toastClass, "makeText", "(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;") : nullptr;
    jmethodID show = toastClass ? env->GetMethodID(toastClass, "show", "()V") : nullptr;
    if (getContext && makeText && show) {
        jobject context = env->CallObjectMethod(thiz, getContext);
        jstring text = env->NewStringUTF(message);
        jobject toast = context && text ? env->CallStaticObjectMethod(toastClass, makeText, context, text, 1 /* LENGTH_LONG */) : nullptr;
        if (toast)
            env->CallVoidMethod(toast, show);
        env->DeleteLocalRef(toast);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(context);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(viewClass);
    env->DeleteLocalRef(toastClass);
}

int LorieViewResources::xcallback(int fd, int events) {
    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        Bool current;

        ALooper_removeFd(ALooper_forThread(), fd);
        // The fd may have been replaced since this callback was queued: only tear the
        // connection down when the one that hung up is still the current one.
        lorieEventWriteLock();
        current = connFd == fd;
        if (current) {
            connFd = -1;
            close(fd);
        }
        lorieEventWriteUnlock();
        if (!current)
            return 1;
        renderer.setSharedState(nullptr);
        renderer.removeAllBuffers();
        log(DEBUG, "disconnected");
        if (activity)
            env->CallVoidMethod(activity, MainActivity.clientConnectedStateChanged);
        return 1;
    }

    if (connFd != -1) {
        lorieEvent e = {0};

        again:
        if (readFully(connFd, &e, sizeof(e))) {
            switch(e.type) {
                case EVENT_CLIPBOARD_SEND: {
                    // As on the other side: anything larger is refused, the stream can not be
                    // resynchronised after swallowing it.
                    const size_t maxClipboard = 64u << 20;
                    if (!e.clipboardSend.count)
                        break;
                    if (e.clipboardSend.count > maxClipboard) {
                        log(ERROR, "Refusing a clipboard of %u bytes", e.clipboardSend.count);
                        return xcallback(fd, ALOOPER_EVENT_HANGUP);
                    }
                    size_t count = e.clipboardSend.count;
                    char *clipboard = (char*) calloc(count + 1, 1);
                    if (!clipboard) {
                        log(ERROR, "Could not allocate clipboard buffer");
                        return xcallback(fd, ALOOPER_EVENT_HANGUP);
                    }
                    if (!readFully(connFd, clipboard, count)) {
                        log(ERROR, "Clipboard of %zu bytes: connection lost", count);
                        free(clipboard);
                        return xcallback(fd, ALOOPER_EVENT_HANGUP);
                    }
                    clipboard[count] = 0;
                    log(DEBUG, "Clipboard: %zu bytes", count);
                    jmethodID id = env->GetMethodID(env->GetObjectClass(thiz), "setClipboardText","(Ljava/lang/String;)V");
                    jobject bb = env->NewDirectByteBuffer(clipboard, strlen(clipboard));
                    jobject charset = env->CallStaticObjectMethod(Charset.self, Charset.forName, env->NewStringUTF("UTF-8"));
                    jobject cb = env->CallObjectMethod(charset, Charset.decode, bb);
                    env->DeleteLocalRef(bb);

                    auto str = (jstring) env->CallObjectMethod(cb, CharBuffer.toString);
                    env->CallVoidMethod(thiz, id, str);
                    free(clipboard);
                    break;
                }
                case EVENT_CLIPBOARD_REQUEST: {
                    env->CallVoidMethod(thiz, env->GetMethodID(env->GetObjectClass(thiz), "requestClipboard", "()V"));
                    break;
                }
                case EVENT_SHARED_SERVER_STATE: {
                    struct lorie_shared_server_state* state = nullptr;
                    int stateFd = ancil_recv_fd(connFd);

                    if (stateFd < 0)
                        break;

                    // An X server started before the app was updated can share a state laid out
                    // differently: using it would corrupt the lock and everything after it.
                    off_t size = sharedRegionSize(stateFd);
                    if (size >= 0 && (size_t) size < sizeof(*state)) {
                        log(ERROR, "Server state is %lld bytes, this app expects %zu: the X server is from another XDesk version",
                            (long long) size, sizeof(*state));
                        showServerVersionMismatch();
                    } else {
                        state = (struct lorie_shared_server_state*) mmap(nullptr, sizeof(*state), PROT_READ|PROT_WRITE, MAP_SHARED, stateFd, 0);
                        if (!state || state == MAP_FAILED) {
                            log(ERROR, "Failed to map server state: %s", strerror(errno));
                            state = nullptr;
                        } else if (state->magic != LORIE_SHARED_STATE_MAGIC || state->version != LORIE_SHARED_STATE_VERSION ||
                                   state->size != sizeof(*state)) {
                            log(ERROR, "Server state has magic %08x version %u size %u, this app expects %08x %u %zu: "
                                       "the X server is from another XDesk version",
                                state->magic, state->version, state->size, LORIE_SHARED_STATE_MAGIC, LORIE_SHARED_STATE_VERSION, sizeof(*state));
                            munmap(state, sizeof(*state));
                            state = nullptr;
                            showServerVersionMismatch();
                        }
                    }

                    renderer.setSharedState(state);

                    close(stateFd); // Closing file descriptor does not unmmap shared memory fragment.
                    break;
                }
                case EVENT_ADD_BUFFER: {
                    static LorieBuffer* buffer = nullptr;
                    const LorieBuffer_Desc* desc;
                    LorieBuffer_recvHandleFromUnixSocket(connFd, &buffer);
                    desc = LorieBuffer_description(buffer);
                    log(INFO, "Received shared buffer width %d stride %d height %d format %d type %d id %llu", desc->width, desc->stride, desc->height, desc->format, desc->type, desc->id);
                    renderer.addBuffer(buffer);
                    break;
                }
                case EVENT_REMOVE_BUFFER: {
                    renderer.removeBuffer(e.removeBuffer.id);
                    break;
                }
                case EVENT_WINDOW_FOCUS_CHANGED: {
                    env->CallVoidMethod(thiz, MainActivity.resetIme);
                    break;
                }
                case EVENT_HEARTBEAT: {
                    if (activity)
                        env->SetLongField(activity, MainActivity.lastServerHeartbeat, nowMs());
                    break;
                }
                case EVENT_SYNC_REPLY: {
                    jmethodID id = env->GetMethodID(env->GetObjectClass(thiz), "onSyncReply", "(I)V");
                    env->CallVoidMethod(thiz, id, (jint) e.sync.serial);
                    break;
                }
            }
        } else {
            // EOF or a read error: the rest of the stream can not be parsed as events.
            return xcallback(fd, ALOOPER_EVENT_HANGUP);
        }

        int n;
        if (ioctl(connFd, FIONREAD, &n) >= 0 && n > sizeof(e))
            goto again;
    }

    return 1;
}

void LorieViewResources::connect(jint fd) {
    int old = -1;

    if (connFd != -1) {
        ALooper_removeFd(ALooper_forThread(), connFd);
        renderer.setSharedState(nullptr);
        renderer.removeAllBuffers();
        log(DEBUG, "disconnected");
    }

    // Swap under the event lock: the sender thread reads the fd there, so it can never write to
    // the old descriptor after it was closed (and the number reused).
    lorieEventWriteLock();
    old = connFd;
    connFd = fd;
    lorieEventWriteUnlock();
    if (old != -1 && old != fd)
        close(old);

    if (fd != -1) {
        ALooper_addFd(ALooper_forThread(), fd, 0, ALOOPER_EVENT_INPUT | ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP,
                      +[](int fd, int events, void* data) -> int { return ((LorieViewResources*) data)->xcallback(fd, events); }, this);

        // Give the X server our renderer wakeup cond var fd, resent on every reconnect. The fd
        // belongs to the event, so it is sent while the event lock is still held.
        lorieEvent e = { .type = EVENT_RENDERER_WAKEUP_COND };
        lorieEventWriteLock();
        if (lorieWriteEventLocked(connFd, &e, nullptr, 0))
            ancil_send_fd(connFd, renderer.getWakeupCondFd());
        lorieEventWriteUnlock();

        log(DEBUG, "XCB connection is successfull");
    }
}

static void startLogcat(JNIEnv *env, __unused jclass clazz, __unused jlong ptr, jint fd) {
    log(DEBUG, "Starting logcat with output to given fd");
    lorieDebugEnabled = true;

    switch(fork()) {
        case -1:
            log(ERROR, "fork: %s", strerror(errno));
            return;
        case 0:
            dup2(fd, 1);
            dup2(fd, 2);
            prctl(PR_SET_PDEATHSIG, SIGTERM);
            char buf[64] = {0};
            sprintf(buf, "--pid=%d", getppid());
            execl("/system/bin/logcat", "logcat", buf, NULL);
            log(ERROR, "exec logcat: %s", strerror(errno));
            env->FatalError("Exiting");
    }
}

static void sendTextEvent(JNIEnv *env, __unused jobject thiz, jlong ptr, jbyteArray text) {
    lastInputTimestampMs = nowMs();
    auto* r = (LorieViewResources*) ptr;
    if (r && r->connFd != -1 && text) {
        jsize length = env->GetArrayLength(text);
        jbyte *str = env->GetByteArrayElements(text, nullptr);
        if (!str)
            return;
        if (!length) {
            env->ReleaseByteArrayElements(text, str, JNI_ABORT);
            return;
        }

        log(DEBUG, "Sending %d bytes of text", (int) length);

        // The parse and the pacing are the sender thread's job; the array is only pinned for
        // the copy.
        char *copy = (char*) malloc((size_t) length + 1);
        if (copy) {
            memcpy(copy, str, (size_t) length);
            copy[length] = 0;
        }
        env->ReleaseByteArrayElements(text, str, JNI_ABORT);
        if (copy)
            lorieSendText(r, copy, (size_t) length);
    }
}

extern char* __progname;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, __unused void *reserved) {
    JNIEnv* env;

    if (!strcmp(__progname, "com.termux.x11")) {
        // Redirects stderr to logcat.
        pthread_create([]{ static pthread_t t; return &t; }(), nullptr, +[](__unused void* cookie) -> void* {
            FILE *fp;
            int p[2];
            size_t len;
            char *line = nullptr;
            pipe(p);

            fp = fdopen(p[0], "r");

            dup2(p[1], 2);
            dup2(p[1], 1);
            while ((getline(&line, &len, fp)) != -1) {
                log(DEBUG, "%s%s", line, (line[len - 1] == '\n') ? "" : "\n");
            }

            return nullptr;
        }, nullptr);
    }

    static JNINativeMethod methods[] = {
            {"nativeInit", "()J", (void *)&nativeInit},
            {"nativeDestroy", "(J)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr) {
                auto* r = (LorieViewResources*) ptr;
                if (!r) return;
                r->~LorieViewResources();
                free(r);
            }},
            {"surfaceChanged", "(JLandroid/view/Surface;)V", (void *) +[](JNIEnv *env, __unused jobject thiz, jlong ptr, jobject sfc) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.setWindow(env, sfc);
            }},
            {"setViewport", "(JIIIIIII)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jint x, jint y, jint w, jint h, jint ew, jint eh, jint hidden) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.setViewport(x, y, w, h, ew, eh, hidden);
            }},
            {"setRendererZoom", "(JI)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jint percent) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.setZoom(percent);
            }},
            {"setZoomAnchor", "(JFFFF)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jfloat sourceX, jfloat sourceY, jfloat fracX, jfloat fracY) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.setZoomAnchor(sourceX, sourceY, fracX, fracY);
            }},
            {"clearZoomAnchor", "(J)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.clearZoomAnchor();
            }},
            {"setFollowCursorPan", "(JZ)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jboolean enabled) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.setFollowCursorPan(enabled);
            }},
            {"getCursorPosition", "(J)J", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr) -> jlong {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed || !r->renderer.state) return 0;
                return ((jlong) r->renderer.state->cursor.x << 32) | (jlong) r->renderer.state->cursor.y;
            }},
            {"sendSync", "(JI)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jint serial) {
                auto* r = (LorieViewResources*) ptr;
                sendEvent(r, .sync = { .t = EVENT_SYNC, .serial = (uint32_t) serial });
            }},
            {"setFiltering", "(JI)V", (void *) +[](__unused JNIEnv* env, __unused jobject self, jlong ptr, jint filtering) {
                auto* r = (LorieViewResources*) ptr;
                if (!r || r->destroyed) return;
                r->renderer.setFiltering(filtering);
            }},
            {"connect", "(JI)V", (void *) +[](__unused JNIEnv* env, __unused jclass clazz, jlong ptr, jint fd) {
                auto* r = (LorieViewResources*) ptr;
                if (!r) return;
                r->connect(fd);
            }},
            // @CriticalNative: no implicit JNIEnv*/jclass, unlike the @FastNative entries below.
            {"connected", "(J)Z", (void *) +[](jlong ptr) -> jboolean {
                auto* r = (LorieViewResources*) ptr;
                return r && r->connFd != -1;
            }},
            {"startLogcat", "(JI)V", (void *)&startLogcat},
            {"setClipboardSyncEnabled", "(JZZ)V", (void *) +[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jboolean enable, __unused jboolean ignored) {
                auto* r = (LorieViewResources*) ptr;
                sendEvent(r, .clipboardEnable = { .t = EVENT_CLIPBOARD_ENABLE, .enable = enable });
            }},
            {"sendClipboardAnnounce", "(J)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr) {
                auto* r = (LorieViewResources*) ptr;
                sendEvent(r, .type = EVENT_CLIPBOARD_ANNOUNCE);
            }},
            {"sendClipboardEvent", "(J[B)V", (void *) +[](JNIEnv *env, __unused jobject thiz, jlong ptr, jbyteArray text) {
                auto* r = (LorieViewResources*) ptr;
                if (r && r->connFd != -1 && text) {
                    jsize length = env->GetArrayLength(text);
                    jbyte* str = env->GetByteArrayElements(text, nullptr);
                    if (!str) return;
                    lorieEvent e = { .clipboardSend = { .t = EVENT_CLIPBOARD_SEND, .count = (uint32_t) length } };
                    lorieSendEvent(r, e, str, (size_t) length);
                    env->ReleaseByteArrayElements(text, str, JNI_ABORT);
                }
            }},
            {"sendWindowChange", "(JIIIILjava/lang/String;)V", (void *) +[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jint width, jint height, jint framerate, jint dpi, jstring jname) {
                auto* r = (LorieViewResources*) ptr;
                if (r && r->connFd != -1) {
                    const char *name = (!jname || width <= 0 || height <= 0) ? nullptr : env->GetStringUTFChars(jname, JNI_FALSE);
                    lorieEvent e = {};
                    e.screenSize.t = EVENT_SCREEN_SIZE;
                    e.screenSize.width = (uint16_t) width;
                    e.screenSize.height = (uint16_t) height;
                    e.screenSize.framerate = (uint16_t) framerate;
                    e.screenSize.name_size = name ? strlen(name) : 0;
                    e.screenSize.dpi = dpi > 0 ? (uint32_t) dpi : 0;
                    lorieSendEvent(r, e, name, name ? strlen(name) : 0);
                    if (name)
                        env->ReleaseStringUTFChars(jname, name);
                }
            }},
            {"setCursorHidden", "(JZ)V", (void *) +[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jboolean hidden) {
                auto* r = (LorieViewResources*) ptr;
                if (r && r->connFd != -1)
                    sendEvent(r, .cursorHidden = { .t = EVENT_CURSOR_HIDDEN, .hidden = hidden });
            }},
            {"sendMouseEvent", "(JFFIZZ)V", (void *) +[](JNIEnv* env, __unused jobject cls, jlong ptr, jfloat x, jfloat y, jint which_button, jboolean button_down, jboolean relative) {
                lastInputTimestampMs = nowMs();
                auto* r = (LorieViewResources*) ptr;
                if (r && r->connFd != -1) {
                    if (which_button > 0)
                        env->CallVoidMethod(r->thiz, MainActivity.resetIme);
                    sendEvent(r, .mouse = { .t = EVENT_MOUSE, .x = x, .y = y, .detail = (uint8_t) which_button, .down = button_down, .relative = relative });
                }
            }},
            {"sendTouchEvent", "(JIIII)V", (void *) +[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jint action, jint id, jint x, jint y) {
                lastInputTimestampMs = nowMs();
                auto* r = (LorieViewResources*) ptr;
                if (action != -1)
                    sendEvent(r, .touch = { .t = EVENT_TOUCH, .type = (uint16_t) action, .id = (uint16_t) id, .x = (uint16_t) x, .y = (uint16_t) y });
            }},
            {"sendStylusEvent", "(JFFIIIIIZZ)V", (void *) +[](JNIEnv *env, __unused jobject thiz, jlong ptr, jfloat x, jfloat y, jint pressure, jint tilt_x, jint tilt_y, jint orientation, jint buttons, jboolean eraser, jboolean mouse) {
                lastInputTimestampMs = nowMs();
                auto* r = (LorieViewResources*) ptr;
                if (r && r->connFd != -1) {
                    env->CallVoidMethod(r->thiz, MainActivity.resetIme);
                    sendEvent(r, .stylus = { .t = EVENT_STYLUS, .x = x, .y = y, .pressure = (uint16_t) pressure, .tilt_x = (int8_t) tilt_x, .tilt_y = (int8_t) tilt_y, .orientation = (int16_t) orientation, .buttons = (uint8_t) buttons, .eraser = eraser, .mouse = mouse });
                }
            }},
            {"requestStylusEnabled", "(JZ)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jboolean enabled) {
                auto* r = (LorieViewResources*) ptr;
                sendEvent(r, .stylusEnable = { .t = EVENT_STYLUS_ENABLE, .enable = enabled });
            }},
            {"sendLockKeysState", "(JI)V", (void *) +[](__unused JNIEnv *env, __unused jobject thiz, jlong ptr, jint state) {
                auto* r = (LorieViewResources*) ptr;
                sendEvent(r, .lockKeysState = { .t = EVENT_LOCK_KEYS_STATE, .state = (uint8_t) state });
            }},
            {"sendKeyEvent", "(JIIZ)Z", (void *) +[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jint scan_code, jint key_code, jboolean key_down) -> jboolean {
                lastInputTimestampMs = nowMs();
                auto* r = (LorieViewResources*) ptr;
                if (r && r->connFd != -1) {
                    int code = scan_code ?: (key_code >= 0 && key_code < (jint) (sizeof(android_to_linux_keycode) / sizeof(*android_to_linux_keycode)) ? android_to_linux_keycode[key_code] : 0);
                    sendEvent(r, .key = { .t = EVENT_KEY, .key = (uint16_t) (code + 8), .state = key_down });
                }
                return true;
            }},
            {"sendLayoutKey", "(JIZ)V", (void *) +[](__unused JNIEnv* env, __unused jobject cls, jlong ptr, jint sym, jboolean down) {
                lastInputTimestampMs = nowMs();
                auto* r = (LorieViewResources*) ptr;
                if (r && r->connFd != -1)
                    sendEvent(r, .layoutKey = { .t = EVENT_LAYOUT_KEY, .state = down, .sym = (uint32_t) sym });
            }},
            {"sendTextEvent", "(J[B)V", (void *)&sendTextEvent},
            {"requestConnection", "(J)Z", (void *)&requestConnection},
            {"getLastInputTimestamp", "()J", (void *) +[](__unused JNIEnv* env, __unused jclass clazz) -> jlong {
                return (jlong) lastInputTimestampMs;
            }},
            {"markUserActivity", "()V", (void *) +[](__unused JNIEnv* env, __unused jclass clazz) {
                lastInputTimestampMs = nowMs();
            }},
    };
    vm->AttachCurrentThread(&env, nullptr);
    jclass cls = env->FindClass("com/termux/x11/LorieView");
    env->RegisterNatives(cls, methods, sizeof(methods)/sizeof(methods[0]));

    pthread_t freeformLookupThread;
    pthread_create(&freeformLookupThread, nullptr, +[](void* arg) -> void* {
        auto* vm = (JavaVM*) arg;
        JNIEnv* threadEnv;
        vm->AttachCurrentThread(&threadEnv, nullptr);
        jclass activityOptionsClass = threadEnv->FindClass("android/app/ActivityOptions");
        jmethodID method = activityOptionsClass ? threadEnv->GetMethodID(activityOptionsClass, "setLaunchWindowingMode", "(I)V") : nullptr;
        threadEnv->ExceptionClear();
        vm->DetachCurrentThread();
        return (void*) method;
    }, vm);
    void* freeformMethod;
    pthread_join(freeformLookupThread, &freeformMethod);
    ActivityOptions.setLaunchWindowingMode = (jmethodID) freeformMethod;
    if (!ActivityOptions.setLaunchWindowingMode)
        log(ERROR, "ActivityOptions.setLaunchWindowingMode(int) not found");

    static JNINativeMethod mainActivityMethods[] = {
            {"nativeSetLaunchWindowingModeFreeform", "(Ljava/lang/Object;)V", (void *) +[](JNIEnv *env, __unused jclass clazz, jobject options) {
                if (ActivityOptions.setLaunchWindowingMode)
                    env->CallVoidMethod(options, ActivityOptions.setLaunchWindowingMode, 5); // WINDOWING_MODE_FREEFORM
            }},
    };
    env->RegisterNatives(env->FindClass("com/termux/x11/MainActivity"), mainActivityMethods, sizeof(mainActivityMethods)/sizeof(mainActivityMethods[0]));

    registerCmdEntryPointNatives(env);

    return JNI_VERSION_1_6;
}
