// The renderer hides its own sprite on HDMI. Publish only shape changes, never motion.
static void publish_cursor(Display *display) {
    XFixesCursorImage *cursor=XFixesGetCursorImage(display);
    if (!cursor) return;
    guint count=(guint)cursor->width*cursor->height;
    if (count && cursor->width<=256 && cursor->height<=256) {
        guint32 *data=g_new(guint32,4+count);
        data[0]=GUINT32_TO_BE(cursor->width); data[1]=GUINT32_TO_BE(cursor->height);
        data[2]=GUINT32_TO_BE(cursor->xhot); data[3]=GUINT32_TO_BE(cursor->yhot);
        for(guint i=0;i<count;i++) data[4+i]=GUINT32_TO_BE((guint32)cursor->pixels[i]);
        if(g_file_set_contents("run/cursor-image",(gchar*)data,(4+count)*4,NULL)) action("cursor-image");
        g_free(data);
    }
    XFree(cursor);
}
static GdkFilterReturn cursor_event(GdkXEvent *event,GdkEvent *translated,gpointer data) {
    Panel *p=data;
    XEvent *x=event;
    if(x->type==p->cursor_event) publish_cursor(x->xany.display);
    return GDK_FILTER_CONTINUE;
}
static void watch_cursor(Panel *p) {
    Display *d=GDK_DISPLAY_XDISPLAY(gdk_display_get_default());
    int base,error;
    if(!XFixesQueryExtension(d,&base,&error)) return;
    p->cursor_event=base+XFixesCursorNotify;
    XFixesSelectCursorInput(d,DefaultRootWindow(d),XFixesDisplayCursorNotifyMask);
    gdk_window_add_filter(NULL,cursor_event,p);
    publish_cursor(d);
}
