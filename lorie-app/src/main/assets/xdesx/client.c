#include <X11/Xlib.h>
#include <X11/Xlib-xcb.h>
#include <X11/Xatom.h>
#include <X11/XKBlib.h>
#include <X11/keysym.h>
#include <X11/Xutil.h>
#include <xcb/dri3.h>
#include <arpa/inet.h>
#include <errno.h>
#include <sys/un.h>
#include <sys/random.h>
#include <stddef.h>
#include <limits.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static int event(int fd, unsigned kind, unsigned x, unsigned y) {
    uint32_t msg[] = {htonl(kind), htonl(x), htonl(y)};
    return send(fd, msg, sizeof(msg), MSG_NOSIGNAL) == sizeof(msg);
}
static void window_icon(Display *d, Window w, const char *title_file) {
    char filename[PATH_MAX], raw[PATH_MAX];
    if (snprintf(filename,sizeof(filename),"%s.icon",title_file)>=(int)sizeof(filename)) return;
    FILE *meta=fopen(filename,"r");
    if (!meta) return;
    int found=fgets(raw,sizeof(raw),meta)!=NULL;
    fclose(meta);
    FILE *image=found?fopen(raw,"rb"):NULL;
    if (!image) return;
    uint32_t pixels[4096];
    if (fread(pixels,sizeof(*pixels),4096,image)==4096) {
        unsigned long icon[4098]={64,64};
        for (int i=0;i<4096;i++) icon[i+2]=ntohl(pixels[i]);
        XChangeProperty(d,w,XInternAtom(d,"_NET_WM_ICON",False),XA_CARDINAL,32,PropModeReplace,(unsigned char*)icon,4098);
    }
    fclose(image);
}
int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--check-display") == 0) {
        Display *probe = XOpenDisplay(NULL);
        if (!probe) return 1;
        XCloseDisplay(probe);
        return 0;
    }
    int fixed1080=argc==4 && !strcmp(argv[3],"--fixed-1080") && !strncmp(argv[1],"mac-",4);
    if (argc != 3 && !fixed1080) return 2;
    const char *menu = getenv("XDESX_MENU"), *tmp = getenv("TMPDIR");
    unsigned char random[16];
    char key[33], path[108], request[160];
    if (!menu || !tmp || strlen(menu)>95 || strlen(argv[1])>12 || getrandom(random,sizeof(random),0)!=16) return 1;
    for (int i=0;i<16;i++) snprintf(key+2*i,3,"%02x",random[i]);
    if (snprintf(path,sizeof(path),"%s/xdesx-%s",tmp,key)>=(int)sizeof(path)) return 1;
    Display *d = XOpenDisplay(NULL);
    if (!d) return 1;
    unsigned num_mask = XkbKeysymToModifiers(d, XK_Num_Lock);
    unsigned scroll_mask = XkbKeysymToModifiers(d, XK_Scroll_Lock);
    int w = 800, h = fixed1080 ? 450 : 560;
    Window win = XCreateSimpleWindow(d, DefaultRootWindow(d), 40, 70, w, h, 0, 0, 0);
    char identity[256]="xdesx.app",metadata[PATH_MAX];
    snprintf(metadata,sizeof(metadata),"%s.class",argv[2]);
    FILE *identity_file=fopen(metadata,"r");
    if (!identity_file) return 1;
    int have_identity = fgets(identity,sizeof(identity),identity_file) != NULL;
    fclose(identity_file);
    if (!have_identity) return 1;
    // Reserve the package before asking Android to create its task. The X server
    // releases the selection when this client exits, including failed launches.
    Atom activate = XInternAtom(d, "_XDESX_ACTIVATE", False);
    if (strcmp(identity,"xdesx.mac") != 0) {
        char name[sizeof(identity) + 32];
        snprintf(name,sizeof(name),"_XDESX_ANDROID_PACKAGE_%s",identity);
        Atom selection = XInternAtom(d,name,False);
        XGrabServer(d);
        Window owner = XGetSelectionOwner(d,selection);
        if (owner == None) {
            XSetSelectionOwner(d,selection,win,CurrentTime);
        } else {
            XEvent msg = {0};
            msg.xclient = (XClientMessageEvent){.type=ClientMessage,.window=owner,.message_type=activate,.format=32};
            XSendEvent(d,owner,False,NoEventMask,&msg);
        }
        XUngrabServer(d);
        XFlush(d);
        if (owner != None) { XCloseDisplay(d); return 0; }
    }
    struct sockaddr_un addr={.sun_family=AF_UNIX};
    strcpy(addr.sun_path,path);
    int listener=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);
    if (listener<0 || bind(listener,(void*)&addr,sizeof(addr)) || listen(listener,4)) return 1;
    int notify=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);
    memset(addr.sun_path,0,sizeof(addr.sun_path));
    strcpy(addr.sun_path+1,menu);
    int size=snprintf(request,sizeof(request),"%s %s\n",key,argv[1]);
    if (notify<0 || connect(notify,(void*)&addr,offsetof(struct sockaddr_un,sun_path)+1+strlen(menu)) ||
            write(notify,request,size)!=size) { unlink(path); return 1; }
    close(notify);
    struct pollfd start = { listener, POLLIN, 0 };
    if (poll(&start, 1, 30000) <= 0) { unlink(path); return 1; }
    int ctrl = accept(listener, NULL, NULL);
    if (ctrl < 0) return 1;
    char title[512]="Android";
    FILE *label=fopen(argv[2],"r");
    if (label) { if (!fgets(title,sizeof(title),label)) strcpy(title,"Android"); fclose(label); }
    XChangeProperty(d,win,XInternAtom(d,"_XDESX_KEY",False),XA_STRING,8,PropModeReplace,(unsigned char*)key,32);
    XClassHint hint={identity,identity};
    XSetClassHint(d,win,&hint);
    window_icon(d,win,argv[2]);
    XStoreName(d, win, title);
    XChangeProperty(d, win, XInternAtom(d,"_NET_WM_NAME",False),XInternAtom(d,"UTF8_STRING",False),
                    8, PropModeReplace,(unsigned char *)title,strlen(title));
    Atom del = XInternAtom(d, "WM_DELETE_WINDOW", False);
    Atom full=XInternAtom(d,"_XDESX_NATIVE_FULLSCREEN",False), protocols[]={del,full};
    Atom wm=XInternAtom(d,"WM_PROTOCOLS",False);
    XSetWMProtocols(d,win,protocols,2);
    Atom wm_state=XInternAtom(d,"WM_STATE",False);
    int minimized=0;
    XSelectInput(d, win, FocusChangeMask | PropertyChangeMask | StructureNotifyMask | PointerMotionMask | ButtonPressMask | ButtonReleaseMask | KeyPressMask | KeyReleaseMask);
    uint32_t initial_aspect[2];
    if(recv(ctrl,initial_aspect,sizeof(initial_aspect),MSG_WAITALL)!=sizeof(initial_aspect))return 1;
    int aw=fixed1080?16:(int)ntohl(initial_aspect[0]);
    int ah=fixed1080?9:(int)ntohl(initial_aspect[1]);
    if(aw>0 && ah>0 && aw<=32767 && ah<=32767){
        XSizeHints hints={0};
        hints.flags=PAspect;
        if(fixed1080){hints.flags|=PResizeInc;hints.width_inc=16;hints.height_inc=9;}
        hints.min_aspect.x=hints.max_aspect.x=aw;
        hints.min_aspect.y=hints.max_aspect.y=ah;
        XSetWMNormalHints(d,win,&hints);
    }
    XMapWindow(d, win);
    XFlush(d);
    xcb_connection_t *conn = XGetXCBConnection(d);
    GC gc = XCreateGC(d, win, 0, NULL);
    Pixmap backing = 0;
    int bw = 0, bh = 0;
    int mapped=0;
    struct pollfd fds[] = {{ConnectionNumber(d), POLLIN, 0}, {listener, POLLIN, 0}, {ctrl, POLLIN, 0}};
    for (;;) {
        while (XPending(d)) {
            XEvent e;
            XNextEvent(d, &e);
            // Temporary keyboard grabs (menus, Alt+Tab) do not change the active editor.
            if ((e.type==FocusIn || e.type==FocusOut) &&
                (e.xfocus.mode==NotifyNormal || e.xfocus.mode==NotifyWhileGrabbed) &&
                e.xfocus.detail!=NotifyInferior && !event(ctrl,9,e.type==FocusIn,0)) return 1;
            if (e.type==ClientMessage && e.xclient.message_type==activate) {
                XEvent msg = {0};
                msg.xclient = (XClientMessageEvent){.type=ClientMessage,.window=win,
                    .message_type=XInternAtom(d,"_NET_ACTIVE_WINDOW",False),.format=32};
                msg.xclient.data.l[0]=2; // launcher activation, also restores an iconic window
                XSendEvent(d,DefaultRootWindow(d),False,SubstructureRedirectMask|SubstructureNotifyMask,&msg);
                XFlush(d);
            }
            if (e.type==ClientMessage && e.xclient.message_type==wm && e.xclient.format==32) {
                if ((Atom)e.xclient.data.l[0]==del) return 0;
                if ((Atom)e.xclient.data.l[0]==full && !event(ctrl,7,0,0)) return 1;
            }
            if (e.type==PropertyNotify && e.xproperty.atom==wm_state) {
                Atom type; int format; unsigned long count, remaining; unsigned char *data=NULL;
                if (XGetWindowProperty(d,win,wm_state,0,2,False,wm_state,&type,&format,&count,&remaining,&data)==Success && format==32 && count) {
                    int hidden=((long*)data)[0]==IconicState;
                    if (hidden!=minimized && !event(ctrl,8,hidden,0)) return 1;
                    minimized=hidden;
                }
                if(data) XFree(data);
            }
            if (!mapped && e.type==MapNotify) {
                XWindowAttributes attr;
                if (!XGetWindowAttributes(d,win,&attr)) return 1;
                w=attr.width;h=attr.height;mapped=1;
                if (!event(ctrl,1,w,h)) return 1;
            }
            if (mapped && e.type == ConfigureNotify && (w != e.xconfigure.width || h != e.xconfigure.height)) {
                w = e.xconfigure.width; h = e.xconfigure.height;
                if (!event(ctrl, 1, w, h)) return 1;
            }
            if (e.type == MotionNotify && !event(ctrl,6,e.xmotion.x,e.xmotion.y)) return 1;
            if (e.type == ButtonPress || e.type == ButtonRelease) {
                unsigned b=e.xbutton.button;
                if (b<=7 && !event(ctrl,(e.type==ButtonPress?16:32)+b,e.xbutton.x,e.xbutton.y)) return 1;
            }
            if (e.type == MappingNotify) {
                XRefreshKeyboardMapping(&e.xmapping);
                num_mask = XkbKeysymToModifiers(d, XK_Num_Lock);
                scroll_mask = XkbKeysymToModifiers(d, XK_Scroll_Lock);
            }
            if (e.type == KeyPress || e.type == KeyRelease) {
                unsigned consumed;
                KeySym sym = NoSymbol;
                // A VNC keysym names the actual symbol, including the active group, Shift and AltGr.
                XkbLookupKeySym(d, e.xkey.keycode, e.xkey.state, &consumed, &sym);
                unsigned locks = !!(e.xkey.state & LockMask)
                    | (!!(e.xkey.state & num_mask) << 1) | (!!(e.xkey.state & scroll_mask) << 2);
                unsigned scan_and_locks = (e.xkey.keycode - 8) | (locks << 16);
                if (!event(ctrl, e.type == KeyPress ? 4 : 5, scan_and_locks, sym)) return 1;
            }
        }
        if (poll(fds, 3, -1) < 0) { if (errno == EINTR) continue; return 1; }
        if (fds[2].revents & POLLIN) {
            uint32_t aspect[2];
            if (recv(ctrl, aspect, sizeof(aspect), MSG_WAITALL) != sizeof(aspect)) return 0;
            int aw = ntohl(aspect[0]), ah = ntohl(aspect[1]);
            if (aw > 0 && aw < ah && ah <= 32767) {
                XSizeHints hints = {0};
                hints.flags = PAspect;
                hints.min_aspect.x = hints.max_aspect.x = aw;
                hints.min_aspect.y = hints.max_aspect.y = ah;
                XSetWMNormalHints(d, win, &hints);
                int width = ((long)h * aw + ah / 2) / ah;
                if (width > 0 && width != w) XResizeWindow(d, win, width, h);
                XFlush(d);
            }
        }
        if ((fds[2].revents & (POLLERR|POLLHUP|POLLNVAL)) || (fds[0].revents & (POLLERR|POLLHUP))) return 0;
        if (!(fds[1].revents & POLLIN)) continue;
        int frame = accept(listener, NULL, NULL);
        uint32_t header[2];
        if (frame < 0 || recv(frame, header, sizeof(header), MSG_WAITALL) != sizeof(header)) return 1;
        int fw = ntohl(header[0]), fh = ntohl(header[1]);
        xcb_pixmap_t pix = xcb_generate_id(conn);
        int fd = dup(frame);
        xcb_void_cookie_t request = xcb_dri3_pixmap_from_buffers_checked(conn,pix,win,1,fw,fh,
            fw*4,0,0,0,0,0,0,0,24,32,0x58443333,&fd);
        xcb_generic_error_t *error = xcb_request_check(conn,request);
        if (error) { fprintf(stderr,"DRI3 import failed: %d\n",error->error_code); return 1; }
        if (bw != fw || bh != fh) {
            if (backing) XFreePixmap(d,backing);
            backing = XCreatePixmap(d,win,fw,fh,24);
            XSetWindowBackgroundPixmap(d,win,backing);
            bw = fw; bh = fh;
        }
        XCopyArea(d,pix,backing,gc,0,0,fw,fh,0,0);
        XClearWindow(d,win);
        XFreePixmap(d,pix);
        XSync(d,False);
        if (send(frame,"\1",1,MSG_NOSIGNAL) != 1) return 1;
        close(frame);
    }
}
