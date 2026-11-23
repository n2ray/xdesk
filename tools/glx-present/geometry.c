/* Keep an oversized diagnostic window out of the WM's size constraints. */
#define _GNU_SOURCE
#include <X11/Xlib.h>
#include <dlfcn.h>

int XMapWindow(Display *display, Window window)
{
    XSetWindowAttributes attributes = {.override_redirect = True};
    XChangeWindowAttributes(display, window, CWOverrideRedirect, &attributes);
    int (*map_window)(Display *, Window) = dlsym(RTLD_NEXT, "XMapWindow");
    return map_window ? map_window(display, window) : 0;
}
