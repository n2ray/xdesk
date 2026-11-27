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
        Atom sources[]={p->anchor_atom,p->accessible_anchor_atom};
        for (unsigned i=0;i<G_N_ELEMENTS(sources) && !h;++i) {
            if (XGetWindowProperty(d,id,sources[i],0,3,False,XA_INTEGER,&type,&format,&n,&remaining,&bytes)==Success &&
                    type==XA_INTEGER && format==32 && n==3 && !remaining) {
                long *a=(long *)bytes;
                if (a[2]>0 && a[2]<=8192 && XTranslateCoordinates(d,id,DefaultRootWindow(d),a[0],a[1],&x,&y,&child))
                    h=a[2];
            }
            if (bytes) { XFree(bytes); bytes=NULL; }
        }
        GdkPoint *click=g_object_get_data(G_OBJECT(w),"xdesx-last-click");
        if (!h && click && XTranslateCoordinates(d,id,DefaultRootWindow(d),click->x,click->y,&x,&y,&child))
            h=MAX(1,8*p->scale/100);
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
    if (e->type==PropertyNotify && (e->xproperty.atom==p->anchor_atom || e->xproperty.atom==p->accessible_anchor_atom)) {
        WnckWindow *w=wnck_screen_get_active_window(p->screen);
        if (w && e->xproperty.window==wnck_window_get_xid(w)) queue_anchor(p);
    }
    return GDK_FILTER_CONTINUE;
}

// Only button presses wake the observer. Save client coordinates so moving a
// window also moves its fallback anchor. A real insertion rectangle always wins.
static void remember_anchor_click(Panel *p) {
    GdkDisplay *gd=gdk_display_get_default();
    Display *d=GDK_DISPLAY_XDISPLAY(gd);
    Window current=DefaultRootWindow(d), root, child;
    int rx,ry,x,y;
    unsigned mask;
    WnckWindow *target=NULL;
    GdkPoint point={0};
    gdk_x11_display_error_trap_push(gd);
    while (current && XQueryPointer(d,current,&root,&child,&rx,&ry,&x,&y,&mask)) {
        WnckWindow *w=wnck_window_get(current);
        if (w) {
            target=w;
            point.x=x; point.y=y;
            break;
        }
        current=child;
    }
    if (target && !wnck_window_is_skip_tasklist(target) && wnck_window_get_pid(target)!=(int)getpid()) {
        Atom type=None; int format; unsigned long count,rest; unsigned char *data=NULL;
        int status=XGetWindowProperty(d,current,XInternAtom(d,"_XDESX_KEY",False),0,0,False,
                                      AnyPropertyType,&type,&format,&count,&rest,&data);
        if (data) XFree(data);
        if (status==Success && type==None)
            g_object_set_data_full(G_OBJECT(target),"xdesx-last-click",g_memdup2(&point,sizeof(point)),g_free);
    }
    gdk_x11_display_error_trap_pop_ignored(gd);
    queue_anchor(p);
}

static gboolean anchor_buttons(GIOChannel *channel,GIOCondition condition,gpointer data) {
    Panel *p=data;
    if (condition & (G_IO_ERR|G_IO_HUP)) {
        p->click_watch=0;
        return G_SOURCE_REMOVE;
    }
    Display *d=p->click_display;
    while (XPending(d)) {
        XEvent e; XNextEvent(d,&e);
        if (e.type!=GenericEvent || e.xcookie.extension!=p->xi_opcode ||
            e.xcookie.evtype!=XI_RawButtonPress || !XGetEventData(d,&e.xcookie)) continue;
        if (((XIRawEvent *)e.xcookie.data)->detail==1) remember_anchor_click(p);
        XFreeEventData(d,&e.xcookie);
    }
    return G_SOURCE_CONTINUE;
}

static void watch_anchor_clicks(Panel *p) {
    Display *d=p->click_display=XOpenDisplay(NULL);
    int event,error,major=2,minor=0;
    if (!d || !XQueryExtension(d,"XInputExtension",&p->xi_opcode,&event,&error) ||
        XIQueryVersion(d,&major,&minor)!=Success) return;
    unsigned char bits[XIMaskLen(XI_RawButtonPress)]={0};
    XISetMask(bits,XI_RawButtonPress);
    XIEventMask mask={XIAllMasterDevices,sizeof(bits),bits};
    XISelectEvents(d,DefaultRootWindow(d),&mask,1);
    XFlush(d);
    GIOChannel *channel=g_io_channel_unix_new(ConnectionNumber(d));
    p->click_watch=g_io_add_watch(channel,G_IO_IN|G_IO_ERR|G_IO_HUP,anchor_buttons,p);
    g_io_channel_unref(channel);
}
