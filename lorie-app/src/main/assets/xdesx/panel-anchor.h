// Keep only the active window's insertion point. The GTK client owns coordinates
// inside the window; xfwm4 can move its frame without moving that insertion point.
static gboolean publish_anchor(gpointer data) {
    Panel *p=data;
    p->anchor_idle=0;
    WnckWindow *w=wnck_screen_get_active_window(p->screen);
    int x=0,y=0,h=0;
    if (w) {
        GdkDisplay *gd=gdk_display_get_default();
        Display *d=GDK_DISPLAY_XDISPLAY(gd);
        Window id=wnck_window_get_xid(w), child;
        Atom type; int format; unsigned long n, remaining; unsigned char *bytes=NULL;
        gdk_x11_display_error_trap_push(gd);
        if (XGetWindowProperty(d,id,p->anchor_atom,0,3,False,XA_INTEGER,&type,&format,&n,&remaining,&bytes)==Success &&
                type==XA_INTEGER && format==32 && n==3 && !remaining) {
            long *a=(long *)bytes;
            if (a[2]>0 && a[2]<=8192 && XTranslateCoordinates(d,id,DefaultRootWindow(d),a[0],a[1],&x,&y,&child))
                h=a[2];
        }
        if (bytes) XFree(bytes);
        if (gdk_x11_display_error_trap_pop(gd)) h=0;
    }
    if (!h) x=y=0;
    if (x!=p->anchor_x || y!=p->anchor_y || h!=p->anchor_h) {
        char msg[80];
        g_snprintf(msg,sizeof(msg),"caret %d %d %d",x,y,h);
        if (action(msg)) {p->anchor_x=x;p->anchor_y=y;p->anchor_h=h;}
    }
    return G_SOURCE_REMOVE;
}
static void queue_anchor(Panel *p) {
    if (!p->anchor_idle) p->anchor_idle=g_idle_add(publish_anchor,p);
}
static void anchor_geometry(WnckWindow *w,Panel *p) {
    if (w==wnck_screen_get_active_window(p->screen)) queue_anchor(p);
}
static GdkFilterReturn anchor_event(GdkXEvent *raw,GdkEvent *unused,gpointer data) {
    XEvent *e=raw; Panel *p=data;
    if (e->type==PropertyNotify && e->xproperty.atom==p->anchor_atom) {
        WnckWindow *w=wnck_screen_get_active_window(p->screen);
        if (w && e->xproperty.window==wnck_window_get_xid(w)) queue_anchor(p);
    }
    return GDK_FILTER_CONTINUE;
}
