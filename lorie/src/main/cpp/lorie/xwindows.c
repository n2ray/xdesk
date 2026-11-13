/*
 * XDesk: the X windows as contents of the window exchange (DISPLAY.md §1.2, §1.5).
 *
 * Done inside the X server rather than by an X client: the window manager's EWMH properties are read
 * where they are stored, a change is noticed through PropertyStateCallback as it happens (no client,
 * no connection, no polling), and requests to the window manager are delivered the way a pager's
 * SendEvent would be. While the app does not listen (XWindows.nativeSetWanted), the callback returns
 * after one comparison.
 *
 * To Java (com.termux.x11.XWindows, X server thread):
 *   onWindows(byte[]): one line per managed window, see appendWindow
 *   onIcon(window, width, height, int[] argb): a window's _NET_WM_ICON nearest to 48 px, or 0x0
 */

#pragma clang diagnostic ignored "-Wunknown-pragmas"

#ifdef HAVE_DIX_CONFIG_H
#include <dix-config.h>
#endif

#include <string.h>
#include <stdlib.h>
#include <android/log.h>
#include <jni.h>
#include <X11/Xatom.h>
#include <X11/X.h>
#include <X11/Xproto.h>
#include <misc.h>
#include <os.h>
#include <dix.h>
#include <dixstruct.h>
#include <client.h>
#include <windowstr.h>
#include <propertyst.h>
#include <scrnintstr.h>
#include <inputstr.h>

#include "lorie.h"

#define xlog(prio, ...) __android_log_print(ANDROID_LOG_ ## prio, "XDeskWindows", __VA_ARGS__)

/* Window commands, the same numbers as XWindows.java */
#define CMD_ACTIVATE 1
#define CMD_FULLSCREEN_ON 2
#define CMD_FULLSCREEN_OFF 3
#define CMD_CLOSE 4

#define FLAG_FULLSCREEN 1
#define FLAG_ACTIVE 2
#define FLAG_HIDDEN 4

#define MAX_TRACKED 256
#define MAX_TEXT 400
#define ICON_WANT 48
#define ICON_MAX 128

static JavaVM *vm;
static jclass xwindowsClass;
static jmethodID onWindowsMethod, onIconMethod;

static Bool wanted, sendAllIcons;
static OsTimerPtr timer;
static Bool scheduled;

static struct { Window id; Bool iconDirty; } tracked[MAX_TRACKED];
static int nTracked;

static Atom aClientList, aActive, aName, aUtf8, aState, aFullscreen, aHidden, aSkipTaskbar, aType, aTypeDock,
        aTypeDesktop, aIcon, aPid, aStartupId, aCloseWindow;

static Atom atom(const char *name) {
    return MakeAtom(name, strlen(name), TRUE);
}

static JNIEnv *jniEnv(void) {
    JNIEnv *env = NULL;
    if (!vm || (*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK)
        return NULL;
    return env;
}

static PropertyPtr prop(WindowPtr w, Atom name) {
    PropertyPtr p = NULL;
    if (dixLookupProperty(&p, w, name, serverClient, DixReadAccess) != Success)
        return NULL;
    return p;
}

static Bool hasAtom(PropertyPtr p, Atom a) {
    if (!p || p->format != 32)
        return FALSE;
    for (uint32_t i = 0; i < p->size; i++)
        if (((CARD32 *) p->data)[i] == a)
            return TRUE;
    return FALSE;
}

static CARD32 card32(PropertyPtr p, CARD32 fallback) {
    return p && p->format == 32 && p->size >= 1 ? ((CARD32 *) p->data)[0] : fallback;
}

typedef struct { char *data; size_t len, cap; } Buf;

static void put(Buf *b, const char *s, size_t n) {
    if (b->len + n + 1 > b->cap) {
        size_t cap = (b->cap ? b->cap : 1024);
        while (cap < b->len + n + 1)
            cap *= 2;
        char *d = realloc(b->data, cap);
        if (!d)
            return;
        b->data = d;
        b->cap = cap;
    }
    memcpy(b->data + b->len, s, n);
    b->len += n;
}

/* Text from a property, with the separators of the snapshot (tab, newline, NUL) as spaces */
static void putText(Buf *b, PropertyPtr p, Bool firstOnly) {
    char tmp[MAX_TEXT];
    size_t n = 0;
    if (p && p->format == 8) {
        const char *s = p->data;
        for (uint32_t i = 0; i < p->size && n < sizeof(tmp); i++) {
            char c = s[i];
            if (c == 0 && firstOnly)
                break;
            tmp[n++] = (c == '\t' || c == '\n' || c == '\r' || c == 0) ? ' ' : c;
        }
    }
    put(b, tmp, n);
}

/*
 * id \t _NET_WM_PID \t pid of the client \t flags \t WM_CLASS instance \t WM_CLASS class \t _NET_STARTUP_ID \t title \n
 * Returns FALSE for a window that is no app (dock, desktop, skip taskbar).
 */
static Bool appendWindow(Buf *b, WindowPtr w, Window active) {
    char head[96];
    PropertyPtr state = prop(w, aState), type = prop(w, aType), cls = prop(w, XA_WM_CLASS), name;
    int flags = 0, n;
    pid_t clientPid;

    if (hasAtom(type, aTypeDock) || hasAtom(type, aTypeDesktop) || hasAtom(state, aSkipTaskbar))
        return FALSE;
    if (hasAtom(state, aFullscreen)) flags |= FLAG_FULLSCREEN;
    if (hasAtom(state, aHidden)) flags |= FLAG_HIDDEN;
    if (w->drawable.id == active) flags |= FLAG_ACTIVE;
    clientPid = wClient(w) ? GetClientPid(wClient(w)) : -1;
    n = snprintf(head, sizeof(head), "%u\t%d\t%d\t%d\t", (unsigned) w->drawable.id,
                 (int) card32(prop(w, aPid), 0), (int) clientPid, flags);
    put(b, head, n);

    /* WM_CLASS is "instance\0class\0" */
    if (cls && cls->format == 8) {
        const char *s = cls->data;
        size_t first = strnlen(s, cls->size);
        putText(b, cls, TRUE);
        put(b, "\t", 1);
        if (first + 1 < cls->size) {
            PropertyRec rest = *cls;
            rest.data = (char *) s + first + 1;
            rest.size = cls->size - first - 1;
            putText(b, &rest, TRUE);
        }
    } else {
        put(b, "\t", 1);
    }
    put(b, "\t", 1);
    putText(b, prop(w, aStartupId), FALSE);
    put(b, "\t", 1);
    name = prop(w, aName);
    if (!name || !name->size)
        name = prop(w, XA_WM_NAME);
    putText(b, name, FALSE);
    put(b, "\n", 1);
    return TRUE;
}

/* The icon nearest to ICON_WANT px that is not larger than ICON_MAX, as ARGB; 0x0 without one */
static void sendIcon(JNIEnv *env, WindowPtr w) {
    PropertyPtr p = prop(w, aIcon);
    const CARD32 *d = p && p->format == 32 ? p->data : NULL;
    size_t size = d ? p->size : 0, i = 0, best = 0;
    CARD32 bw = 0, bh = 0;
    jintArray pixels;

    while (i + 2 <= size) {
        CARD32 iw = d[i], ih = d[i + 1];
        if (!iw || !ih || iw > 1024 || ih > 1024 || (size_t) iw * ih > size - i - 2)
            break;
        if (iw <= ICON_MAX && ih <= ICON_MAX) {
            Bool better;
            if (!bw)
                better = TRUE;
            else if (bw >= ICON_WANT)
                better = iw >= ICON_WANT && iw < bw;
            else
                better = iw > bw;
            if (better) {
                best = i;
                bw = iw;
                bh = ih;
            }
        }
        i += 2 + (size_t) iw * ih;
    }

    pixels = (*env)->NewIntArray(env, (jsize) (bw * bh));
    if (!pixels) {
        (*env)->ExceptionClear(env);
        return;
    }
    if (bw)
        (*env)->SetIntArrayRegion(env, pixels, 0, (jsize) (bw * bh), (const jint *) (d + best + 2));
    (*env)->CallStaticVoidMethod(env, xwindowsClass, onIconMethod, (jint) w->drawable.id, (jint) bw, (jint) bh, pixels);
    (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, pixels);
}

static int trackedIndex(Window id) {
    for (int i = 0; i < nTracked; i++)
        if (tracked[i].id == id)
            return i;
    return -1;
}

static void refresh(void) {
    JNIEnv *env = jniEnv();
    WindowPtr root = screenInfo.numScreens ? screenInfo.screens[0]->root : NULL;
    PropertyPtr list;
    Window active;
    Buf b = { 0 };
    Window ids[MAX_TRACKED];
    Bool iconFor[MAX_TRACKED];
    int n = 0;
    jbyteArray bytes;

    if (!env || !xwindowsClass || !root || !wanted)
        return;
    list = prop(root, aClientList);
    active = card32(prop(root, aActive), None);

    put(&b, "", 0);
    if (list && list->format == 32) {
        for (uint32_t i = 0; i < list->size && n < MAX_TRACKED; i++) {
            WindowPtr w;
            Window id = ((CARD32 *) list->data)[i];
            int t;
            if (dixLookupWindow(&w, id, serverClient, DixGetAttrAccess) != Success)
                continue;
            if (!appendWindow(&b, w, active))
                continue;
            t = trackedIndex(id);
            ids[n] = id;
            iconFor[n] = sendAllIcons || t < 0 || tracked[t].iconDirty;
            n++;
        }
    }
    sendAllIcons = FALSE;
    for (int i = 0; i < n; i++) {
        tracked[i].id = ids[i];
        tracked[i].iconDirty = FALSE;
    }
    nTracked = n;

    bytes = (*env)->NewByteArray(env, (jsize) b.len);
    if (bytes) {
        if (b.len)
            (*env)->SetByteArrayRegion(env, bytes, 0, (jsize) b.len, (const jbyte *) b.data);
        (*env)->CallStaticVoidMethod(env, xwindowsClass, onWindowsMethod, bytes);
        (*env)->DeleteLocalRef(env, bytes);
    }
    (*env)->ExceptionClear(env);
    free(b.data);

    for (int i = 0; i < n; i++) {
        WindowPtr w;
        if (iconFor[i] && dixLookupWindow(&w, ids[i], serverClient, DixGetAttrAccess) == Success)
            sendIcon(env, w);
    }
}

static CARD32 timerFired(OsTimerPtr t, CARD32 now, void *arg) {
    scheduled = FALSE;
    refresh();
    return 0;
}

/* Changes come in bursts (a window maps with a dozen properties): one snapshot for all of them */
static void schedule(void) {
    if (scheduled)
        return;
    scheduled = TRUE;
    timer = TimerSet(timer, 0, 50, timerFired, NULL);
}

static void propertyChanged(CallbackListPtr *list, void *closure, void *data) {
    PropertyStateRec *rec = data;
    Atom a;
    int t;

    if (!wanted || !rec || !rec->prop || !rec->win)
        return;
    a = rec->prop->propertyName;
    if (!rec->win->parent) {
        if (a == aClientList || a == aActive)
            schedule();
        return;
    }
    if (a != aName && a != XA_WM_NAME && a != aState && a != aIcon && a != XA_WM_CLASS && a != aStartupId)
        return;
    if ((t = trackedIndex(rec->win->drawable.id)) < 0)
        return;
    if (a == aIcon)
        tracked[t].iconDirty = TRUE;
    schedule();
}

/* What a pager sends: a ClientMessage to the root window, for the window manager (SubstructureRedirect) */
static void sendToWindowManager(Window w, Atom type, CARD32 l0, CARD32 l1, CARD32 l2, CARD32 l3) {
    WindowPtr root = screenInfo.numScreens ? screenInfo.screens[0]->root : NULL;
    xEvent ev;
    if (!root)
        return;
    memset(&ev, 0, sizeof(ev));
    ev.u.u.type = ClientMessage | 0x80; /* sent by SendEvent */
    ev.u.u.detail = 32;
    ev.u.clientMessage.window = w;
    ev.u.clientMessage.u.l.type = type;
    ev.u.clientMessage.u.l.longs0 = (INT32) l0;
    ev.u.clientMessage.u.l.longs1 = (INT32) l1;
    ev.u.clientMessage.u.l.longs2 = (INT32) l2;
    ev.u.clientMessage.u.l.longs3 = (INT32) l3;
    DeliverEventsToWindow(PickPointer(serverClient), root, &ev, 1,
                          SubstructureRedirectMask | SubstructureNotifyMask, NullGrab);
}

typedef struct { Window window; int command; } Command;

static Bool runCommand(ClientPtr client, void *closure) {
    Command *c = closure;
    WindowPtr w;
    UpdateCurrentTimeIf();
    if (dixLookupWindow(&w, c->window, serverClient, DixGetAttrAccess) == Success) {
        switch (c->command) {
            case CMD_ACTIVATE:
                /* Source 2: a pager, which focus stealing prevention lets through */
                sendToWindowManager(c->window, aActive, 2, currentTime.milliseconds, 0, 0);
                break;
            case CMD_FULLSCREEN_ON:
            case CMD_FULLSCREEN_OFF:
                sendToWindowManager(c->window, aState, c->command == CMD_FULLSCREEN_ON ? 1 : 0, aFullscreen, 0, 2);
                break;
            case CMD_CLOSE:
                sendToWindowManager(c->window, aCloseWindow, currentTime.milliseconds, 2, 0, 0);
                break;
        }
    }
    free(c);
    return TRUE;
}

static Bool setWanted(ClientPtr client, void *closure) {
    wanted = closure != NULL;
    nTracked = 0;
    if (wanted) {
        sendAllIcons = TRUE;
        refresh();
    } else if (timer) {
        TimerCancel(timer);
        scheduled = FALSE;
    }
    return TRUE;
}

void lorieInitWindows(void) {
    aClientList = atom("_NET_CLIENT_LIST");
    aActive = atom("_NET_ACTIVE_WINDOW");
    aName = atom("_NET_WM_NAME");
    aUtf8 = atom("UTF8_STRING");
    aState = atom("_NET_WM_STATE");
    aFullscreen = atom("_NET_WM_STATE_FULLSCREEN");
    aHidden = atom("_NET_WM_STATE_HIDDEN");
    aSkipTaskbar = atom("_NET_WM_STATE_SKIP_TASKBAR");
    aType = atom("_NET_WM_WINDOW_TYPE");
    aTypeDock = atom("_NET_WM_WINDOW_TYPE_DOCK");
    aTypeDesktop = atom("_NET_WM_WINDOW_TYPE_DESKTOP");
    aIcon = atom("_NET_WM_ICON");
    aPid = atom("_NET_WM_PID");
    aStartupId = atom("_NET_STARTUP_ID");
    aCloseWindow = atom("_NET_CLOSE_WINDOW");
    /* A new server generation starts with no timers and no callbacks */
    timer = NULL;
    scheduled = FALSE;
    nTracked = 0;
    sendAllIcons = TRUE;
    if (!AddCallback(&PropertyStateCallback, propertyChanged, NULL))
        xlog(ERROR, "Adding PropertyStateCallback failed");
}

static void nativeSetWanted(JNIEnv *env, jclass cls, jboolean on) {
    QueueWorkProc(setWanted, NULL, on ? (void *) 1 : NULL);
    lorieWakeServer();
}

static void nativeCommand(JNIEnv *env, jclass cls, jint window, jint command) {
    Command *c = calloc(1, sizeof(Command));
    if (!c)
        return;
    c->window = (Window) (uint32_t) window;
    c->command = command;
    QueueWorkProc(runCommand, NULL, c);
    lorieWakeServer();
}

/* From JNI_OnLoad, in the app as well as in the X server; only the X server ever calls them */
void lorieRegisterWindowsNatives(JNIEnv *env) {
    static JNINativeMethod methods[] = {
            {"nativeSetWanted", "(Z)V", (void *) nativeSetWanted},
            {"nativeCommand", "(II)V", (void *) nativeCommand},
    };
    jclass cls = (*env)->FindClass(env, "com/termux/x11/XWindows");
    if (!cls) {
        (*env)->ExceptionClear(env);
        return;
    }
    (*env)->GetJavaVM(env, &vm);
    xwindowsClass = (*env)->NewGlobalRef(env, cls);
    onWindowsMethod = (*env)->GetStaticMethodID(env, cls, "onWindows", "([B)V");
    onIconMethod = (*env)->GetStaticMethodID(env, cls, "onIcon", "(III[I)V");
    if (!onWindowsMethod || !onIconMethod) {
        (*env)->ExceptionClear(env);
        xwindowsClass = NULL;
        return;
    }
    (*env)->RegisterNatives(env, cls, methods, sizeof(methods) / sizeof(methods[0]));
    (*env)->ExceptionClear(env);
}
