/* Publish GTK's live text density and the Xft default to clients on this X display. */
static void publish_desktop_settings(Panel *p,int scale) {
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
    /* Ubuntu's application bus mirrors this preference for clients using GSettings. */
    unsigned long dark=p->dark?1:0;
    XChangeProperty(d,root,XInternAtom(d,"_XDESX_PREFER_DARK",False),XA_CARDINAL,32,
                    PropModeReplace,(unsigned char*)&dark,1);
    Atom selection=XInternAtom(d,"_XSETTINGS_S0",False);
    Window owner=XGetSelectionOwner(d,selection);
    if(owner && owner!=p->dpi_owner)return;
    if(!p->dpi_owner)p->dpi_owner=XCreateSimpleWindow(d,root,0,0,1,1,0,0,0);
    /* Little-endian XSETTINGS packet on arm64: serial, Xft/DPI (integer) and Net/ThemeName (string), padded to four bytes. */
    guint32 serial=++p->dpi_serial;
    unsigned char packet[72]={0};
    guint32 count=2, value=dpi*1024;
    guint16 length=7;
    memcpy(packet+4,&serial,4);memcpy(packet+8,&count,4);
    memcpy(packet+14,&length,2);memcpy(packet+16,"Xft/DPI",7);
    memcpy(packet+24,&serial,4);memcpy(packet+28,&value,4);
    const char *theme=p->dark?"Adwaita-dark":"Adwaita";
    guint32 theme_len=strlen(theme);
    packet[32]=1; /* XSETTINGS string */
    length=13;
    memcpy(packet+34,&length,2);memcpy(packet+36,"Net/ThemeName",length);
    memcpy(packet+52,&serial,4);memcpy(packet+56,&theme_len,4);
    memcpy(packet+60,theme,theme_len);
    Atom prop=XInternAtom(d,"_XSETTINGS_SETTINGS",False);
    XChangeProperty(d,p->dpi_owner,prop,prop,8,PropModeReplace,packet,60+((theme_len+3)&~3u));
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
