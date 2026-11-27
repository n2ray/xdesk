// The guest publishes geometry only. Android remains responsible for composition.
#include <atspi/atspi.h>
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <stdlib.h>
#include <string.h>

static Display *display;
static Window root, owner;
static Atom active_atom, pid_atom, anchor;
static AtspiAccessible *editor;
static gboolean trace;

static unsigned long property(Window window, Atom atom, Atom type) {
    Atom actual;
    int format;
    unsigned long count, remaining, value = 0;
    unsigned char *data = NULL;
    if (XGetWindowProperty(display, window, atom, 0, 1, False, type, &actual, &format,
                           &count, &remaining, &data) == Success &&
        actual == type && format == 32 && count == 1)
        value = *(unsigned long *)data;
    if (data) XFree(data);
    return value;
}

static gboolean belongs(guint pid, unsigned long window_pid) {
    // Some applications export text from a renderer child of the window owner.
    while (pid > 1 && window_pid) {
        if (pid == window_pid) return TRUE;
        char path[48];
        g_snprintf(path, sizeof(path), "/proc/%u/stat", pid);
        gchar *stat = NULL;
        if (!g_file_get_contents(path, &stat, NULL, NULL)) return FALSE;
        char *end = strrchr(stat, ')');
        unsigned long parent = end && strlen(end) > 4 ? strtoul(end+4, NULL, 10) : 0;
        g_free(stat);
        if (parent == pid) return FALSE;
        pid = parent;
    }
    return FALSE;
}

static void clear_anchor(void) {
    if (owner) {
        XDeleteProperty(display, owner, anchor);
        XFlush(display);
        owner = None;
    }
    g_clear_object(&editor);
}

static gboolean report(AtspiAccessible *source) {
    Window win = property(root, active_atom, XA_WINDOW);
    if (trace && win) g_printerr("active PID %lu\n", property(win, pid_atom, XA_CARDINAL));
    if (!win || !belongs(atspi_accessible_get_process_id(source, NULL), property(win, pid_atom, XA_CARDINAL)))
        return FALSE;
    AtspiText *text = atspi_accessible_get_text_iface(source);
    if (!text) return FALSE;
    int offset = atspi_text_get_caret_offset(text, NULL);
    int length = atspi_text_get_character_count(text, NULL);
    gboolean sent = FALSE;
    if (offset >= 0 && offset <= length) {
        for (int index = offset; index >= 0; --index) {
            AtspiRect *r = atspi_text_get_character_extents(text, index, ATSPI_COORD_TYPE_SCREEN, NULL);
            if (trace && r) g_printerr("offset=%d index=%d rect=%d,%d %dx%d\n", offset,index,r->x,r->y,r->width,r->height);
            if (r && r->height > 0 && r->height <= 8192 && r->x >= -32768 && r->x <= 32767 &&
                r->y >= -32768 && r->y <= 32767) {
                Window child;
                int x, y;
                if (XTranslateCoordinates(display, root, win, r->x + (index == offset ? 0 : r->width),
                                          r->y, &x, &y, &child) && x >= 0 && y >= 0) {
                    if (owner != win || editor != source) {
                        clear_anchor();
                        owner = win;
                        editor = g_object_ref(source);
                    }
                    long pos[] = {x, y, r->height};
                    XChangeProperty(display, win, anchor, XA_INTEGER, 32, PropModeReplace,
                                    (unsigned char *)pos, 3);
                    XFlush(display);
                    sent = TRUE;
                }
            }
            g_free(r);
            // Some providers only return actual character boxes at end of text.
            if (sent || offset != length || index != offset) break;
        }
    }
    g_object_unref(text);
    return sent;
}

static void on_event(AtspiEvent *event, void *unused) {
    (void)unused;
    if (trace) g_printerr("caret event %s detail=%d active=0x%lx source-pid=%u\n", event->type, event->detail1, property(root, active_atom, XA_WINDOW), atspi_accessible_get_process_id(event->source, NULL));
    if (g_str_has_prefix(event->type, "object:state-changed:focused")) {
        if (event->detail1) {
            clear_anchor();
            report(event->source);
        } else if (editor == event->source) {
            clear_anchor();
        }
    } else {
        AtspiStateSet *states = atspi_accessible_get_state_set(event->source);
        if (states && atspi_state_set_contains(states, ATSPI_STATE_FOCUSED) &&
            !report(event->source) && editor == event->source)
            clear_anchor();
        g_clear_object(&states);
    }
    g_boxed_free(ATSPI_TYPE_EVENT, event);
}

static int stale_window(Display *d, XErrorEvent *e) {
    (void)d;
    (void)e;
    return 0;
}

int main(void) {
    trace = g_getenv("XDESX_CARET_TRACE") != NULL;
    display = XOpenDisplay(NULL);
    if (!display || atspi_init() != 0) return 1;
    XSetErrorHandler(stale_window);
    root = DefaultRootWindow(display);
    active_atom = XInternAtom(display, "_NET_ACTIVE_WINDOW", False);
    pid_atom = XInternAtom(display, "_NET_WM_PID", False);
    anchor = XInternAtom(display, "_XDESX_ACCESSIBLE_INSERTION_RECT", False);
    AtspiEventListener *listener = atspi_event_listener_new(on_event, NULL, NULL);
    if (!atspi_event_listener_register(listener, "object:state-changed:focused", NULL) ||
        !atspi_event_listener_register(listener, "object:text-caret-moved", NULL)) return 1;
    atspi_event_main();
    return 0;
}
