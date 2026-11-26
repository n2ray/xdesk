/* Publish GTK's live text density and the Xft default to clients on this X display. */
static void publish_dpi(Panel *p,int scale) {
    Display *d=GDK_DISPLAY_XDISPLAY(gdk_display_get_default());
    Window root=DefaultRootWindow(d);
    int dpi=(96*scale+50)/100;
    Atom type;
    int format;
    unsigned long size,remaining;
    unsigned char *raw=NULL;
    if(XGetWindowProperty(d,root,XA_RESOURCE_MANAGER,0,262144,False,XA_STRING,
                          &type,&format,&size,&remaining,&raw)==Success && !remaining) {
        GString *resources=g_string_new(NULL);
        gchar **lines=g_strsplit(raw?(char*)raw:"","\n",-1);
        for(int i=0;lines[i];i++) {
            if (*lines[i] && !g_str_has_prefix(lines[i],"Xft.dpi:"))
                g_string_append_printf(resources,"%s\n",lines[i]);
        }
        g_string_append_printf(resources,"Xft.dpi:\t%d\n",dpi);
        XChangeProperty(d,root,XA_RESOURCE_MANAGER,XA_STRING,8,PropModeReplace,
                        (unsigned char*)resources->str,resources->len);
        g_string_free(resources,TRUE);
        g_strfreev(lines);
    }
    if(raw)XFree(raw);
    Atom selection=XInternAtom(d,"_XSETTINGS_S0",False);
    Window owner=XGetSelectionOwner(d,selection);
    if(owner && owner!=p->dpi_owner)return;
    if(!p->dpi_owner)p->dpi_owner=XCreateSimpleWindow(d,root,0,0,1,1,0,0,0);
    /* Little-endian XSETTINGS packet on arm64: serial, one integer setting, its value. */
    guint32 serial=++p->dpi_serial;
    unsigned char packet[32]={0};
    guint32 count=1, value=dpi*1024;
    guint16 length=7;
    memcpy(packet+4,&serial,4);memcpy(packet+8,&count,4);
    memcpy(packet+14,&length,2);memcpy(packet+16,"Xft/DPI",7);
    memcpy(packet+24,&serial,4);memcpy(packet+28,&value,4);
    Atom prop=XInternAtom(d,"_XSETTINGS_SETTINGS",False);
    XChangeProperty(d,p->dpi_owner,prop,prop,8,PropModeReplace,packet,sizeof(packet));
    if(!owner){
        XSetSelectionOwner(d,selection,p->dpi_owner,CurrentTime);
        XEvent message={0};
        message.xclient.type=ClientMessage;
        message.xclient.window=root;
        message.xclient.message_type=XInternAtom(d,"MANAGER",False);
        message.xclient.format=32;
        message.xclient.data.l[1]=selection;
        message.xclient.data.l[2]=p->dpi_owner;
        XSendEvent(d,root,False,StructureNotifyMask,&message);
    }
    XFlush(d);
}
