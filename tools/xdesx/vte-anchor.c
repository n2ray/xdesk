// Ubuntu GTK 3 input context. Android composes the text. VTE supplies the caret
// rectangle relative to its GdkWindow, including changes made using the keyboard.
#include <gtk/gtk.h>
#include <gtk/gtkimmodule.h>
#include <gdk/gdkx.h>
#include <X11/Xatom.h>

typedef struct {
    GtkIMContextSimple base;
    GdkWindow *client;
    GdkRectangle rect;
    gboolean focused, valid;
} XdesxIM;
typedef GtkIMContextSimpleClass XdesxIMClass;
G_DEFINE_DYNAMIC_TYPE(XdesxIM, xdesx_im, GTK_TYPE_IM_CONTEXT_SIMPLE)

static void publish(XdesxIM *im, gboolean clear) {
    if (!im->client || !GDK_IS_X11_WINDOW(im->client) || gdk_window_is_destroyed(im->client)) return;
    GdkWindow *top = gdk_window_get_toplevel(im->client);
    Display *d = GDK_WINDOW_XDISPLAY(top);
    Window win = GDK_WINDOW_XID(top);
    Atom a = XInternAtom(d, "_XDESX_INSERTION_RECT", False);
    if (clear) {
        XDeleteProperty(d, win, a);
    } else if (im->focused && im->valid) {
        int x, y, tx, ty, scale = gdk_window_get_scale_factor(top);
        gdk_window_get_origin(im->client, &x, &y);
        gdk_window_get_origin(top, &tx, &ty);
        long pos[] = {(x-tx+im->rect.x)*scale, (y-ty+im->rect.y)*scale, im->rect.height*scale};
        XChangeProperty(d, win, a, XA_INTEGER, 32, PropModeReplace, (unsigned char *)pos, 3);
    }
}
static void client(GtkIMContext *ctx, GdkWindow *window) {
    XdesxIM *im = (XdesxIM *)ctx;
    if (im->client != window) {
        if (im->focused) publish(im, TRUE);
        g_set_object(&im->client, window);
        im->valid = FALSE;
    }
    GtkIMContextClass *parent = GTK_IM_CONTEXT_CLASS(xdesx_im_parent_class);
    if (parent->set_client_window) parent->set_client_window(ctx, window);
}
static void location(GtkIMContext *ctx, GdkRectangle *rect) {
    XdesxIM *im = (XdesxIM *)ctx;
    im->rect = *rect;
    im->valid = rect->height > 0;
    if (im->focused) publish(im, !im->valid);
}
static void focus_in(GtkIMContext *ctx) {
    XdesxIM *im = (XdesxIM *)ctx;
    im->focused = TRUE;
    GtkIMContextClass *parent = GTK_IM_CONTEXT_CLASS(xdesx_im_parent_class);
    if (parent->focus_in) parent->focus_in(ctx);
    publish(im, FALSE);
}
static void focus_out(GtkIMContext *ctx) {
    XdesxIM *im = (XdesxIM *)ctx;
    if (im->focused) publish(im, TRUE);
    im->focused = FALSE;
    GtkIMContextClass *parent = GTK_IM_CONTEXT_CLASS(xdesx_im_parent_class);
    if (parent->focus_out) parent->focus_out(ctx);
}
static void dispose(GObject *o) {
    g_clear_object(&((XdesxIM *)o)->client);
    G_OBJECT_CLASS(xdesx_im_parent_class)->dispose(o);
}
static void xdesx_im_class_init(XdesxIMClass *cls) {
    GObjectClass *object = G_OBJECT_CLASS(cls);
    GtkIMContextClass *im = GTK_IM_CONTEXT_CLASS(cls);
    object->dispose = dispose;
    im->set_client_window = client;
    im->set_cursor_location = location;
    im->focus_in = focus_in;
    im->focus_out = focus_out;
}
static void xdesx_im_class_finalize(XdesxIMClass *cls) {}
static void xdesx_im_init(XdesxIM *im) {}
static const GtkIMContextInfo info = {"xdesx", "xdesx", "", "", ""};
static const GtkIMContextInfo *contexts[] = {&info};
G_MODULE_EXPORT void im_module_init(GTypeModule *m) { xdesx_im_register_type(m); }
G_MODULE_EXPORT void im_module_exit(void) {}
G_MODULE_EXPORT void im_module_list(const GtkIMContextInfo ***list, int *count) { *list = contexts; *count = 1; }
G_MODULE_EXPORT GtkIMContext *im_module_create(const gchar *id) {
    return g_strcmp0(id, "xdesx") == 0 ? g_object_new(xdesx_im_get_type(), NULL) : NULL;
}
