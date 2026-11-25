#include <X11/Xlib.h>
#include <X11/Xlib-xcb.h>
#include <X11/Xatom.h>
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
int main(int argc, char **argv) {
    if (argc != 3) return 2;
    const char *menu = getenv("XDESX_MENU"), *tmp = getenv("TMPDIR");
    unsigned char random[16];
    char key[33], path[108], request[160];
    if (!menu || !tmp || strlen(menu)>95 || strlen(argv[1])>12 || getrandom(random,sizeof(random),0)!=16) return 1;
    for (int i=0;i<16;i++) snprintf(key+2*i,3,"%02x",random[i]);
    if (snprintf(path,sizeof(path),"%s/xdesx-%s",tmp,key)>=(int)sizeof(path)) return 1;
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
    if (poll(&start, 1, 30000) <= 0) return 1;
    int ctrl = accept(listener, NULL, NULL);
    Display *d = XOpenDisplay(NULL);
    if (ctrl < 0 || !d) return 1;
    int w = 800, h = 560;
    Window win = XCreateSimpleWindow(d, DefaultRootWindow(d), 40, 70, w, h, 0, 0, 0);
    char title[512]="Android";
    FILE *label=fopen(argv[2],"r");
    if (label) { if (!fgets(title,sizeof(title),label)) strcpy(title,"Android"); fclose(label); }
    XStoreName(d, win, title);
    XChangeProperty(d, win, XInternAtom(d,"_NET_WM_NAME",False),XInternAtom(d,"UTF8_STRING",False),
                    8, PropModeReplace,(unsigned char *)title,strlen(title));
    Atom del = XInternAtom(d, "WM_DELETE_WINDOW", False);
    XSetWMProtocols(d, win, &del, 1);
    XSelectInput(d, win, StructureNotifyMask | PointerMotionMask | ButtonPressMask | ButtonReleaseMask | KeyPressMask | KeyReleaseMask);
    XMapWindow(d, win);
    XFlush(d);
    xcb_connection_t *conn = XGetXCBConnection(d);
    GC gc = XCreateGC(d, win, 0, NULL);
    Pixmap backing = 0;
    int bw = 0, bh = 0;
    if (!event(ctrl, 1, w, h)) return 1;
    struct pollfd fds[] = {{ConnectionNumber(d), POLLIN, 0}, {listener, POLLIN, 0}, {ctrl, POLLIN, 0}};
    for (;;) {
        while (XPending(d)) {
            XEvent e;
            XNextEvent(d, &e);
            if (e.type == ClientMessage && (Atom)e.xclient.data.l[0] == del) return 0;
            if (e.type == ConfigureNotify && (w != e.xconfigure.width || h != e.xconfigure.height)) {
                w = e.xconfigure.width; h = e.xconfigure.height;
                if (!event(ctrl, 1, w, h)) return 1;
            }
            if (e.type == MotionNotify && !event(ctrl,6,e.xmotion.x,e.xmotion.y)) return 1;
            if (e.type == ButtonPress || e.type == ButtonRelease) {
                unsigned b=e.xbutton.button;
                if (b<=7 && !event(ctrl,(e.type==ButtonPress?16:32)+b,e.xbutton.x,e.xbutton.y)) return 1;
            }
            if (e.type == KeyPress || e.type == KeyRelease)
                if (!event(ctrl, e.type == KeyPress ? 4 : 5, e.xkey.keycode - 8, 0)) return 1;
        }
        if (poll(fds, 3, -1) < 0) { if (errno == EINTR) continue; return 1; }
        if (fds[2].revents || (fds[0].revents & (POLLERR|POLLHUP))) return 0;
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
