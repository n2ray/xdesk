"""Two xfwm4 client windows. Run inside the XDesk guest with DISPLAY=:1."""
import ctypes as C
import os
import secrets
import socket
import struct
import time
from window import function, P, U, N, I, U8, U16, MODIFIER


class XWindows:
    def __init__(self):
        self.x = x = C.CDLL("libX11.so.6")
        self.d = d = function(x,"XOpenDisplay",P,C.c_char_p)(None)
        if not d:
            raise RuntimeError("DISPLAY unavailable")
        self.root = function(x,"XDefaultRootWindow",U,P)(d)
        self.atom = function(x,"XInternAtom",U,P,C.c_char_p,I)
        self.delete = self.atom(d,b"WM_DELETE_WINDOW",0)
        self.protocols = self.atom(d,b"WM_PROTOCOLS",0)
        self.sizes = [(880,600),(840,580)]
        self.pending_sizes = [None,None]
        self.wins = []
        self.backings = [None,None]
        self.last_frame_sizes = [None,None]
        for i,title in enumerate([b"Android Chrome",b"Android YouTube"]):
            w,h=self.sizes[i]
            win=function(x,"XCreateSimpleWindow",U,P,U,I,I,N,N,N,U,U)(d,self.root,60+930*i,80,w,h,0,0,0)
            self.wins.append(win)
            function(x,"XStoreName",I,P,U,C.c_char_p)(d,win,title)
            function(x,"XSetWMProtocols",I,P,U,C.POINTER(U),I)(d,win,C.byref(U(self.delete)),1)
            function(x,"XSelectInput",I,P,U,C.c_long)(d,win,1<<17)
            function(x,"XMapWindow",I,P,U)(d,win)
        self.gc=function(x,"XCreateGC",P,P,U,U,P)(d,self.wins[0],0,None)
        self.sync=function(x,"XSync",I,P,I)
        self.sync(d,0)
        self.pending=function(x,"XPending",I,P)
        self.next=function(x,"XNextEvent",I,P,P)
        self.geometry=function(x,"XGetGeometry",I,P,U,C.POINTER(U),C.POINTER(I),C.POINTER(I),*[C.POINTER(N)]*4)
        self.copy=function(x,"XCopyArea",I,P,U,U,P,I,I,N,N,I,I)
        self.free=function(x,"XFreePixmap",I,P,U)
        self.create=function(x,"XCreatePixmap",U,P,U,N,N,N)
        self.background=function(x,"XSetWindowBackgroundPixmap",I,P,U,U)
        self.clear=function(x,"XClearWindow",I,P,U)
        cb=C.CDLL("libxcb.so.1")
        self.conn=function(C.CDLL("libX11-xcb.so.1"),"XGetXCBConnection",P,P)(d)
        self.generate=function(cb,"xcb_generate_id",N,P)
        self.check=function(cb,"xcb_request_check",P,P,N)
        self.import_frame=function(C.CDLL("libxcb-dri3.so.0"),"xcb_dri3_pixmap_from_buffers_checked",
                                   N,P,N,N,U8,U16,U16,*([N]*8),U8,U8,C.c_uint64,C.POINTER(I))

    def changes(self):
        # XClientMessageEvent has five native-word header fields before message_type.
        ev=(C.c_long*24)()
        while self.pending(self.d):
            self.next(self.d,C.byref(ev))
            if ev[0]&0xffffffff==33 and ev[5]==self.protocols and ev[7]==self.delete and ev[4] in self.wins:
                i=self.wins.index(ev[4])
                function(self.x,"XDestroyWindow",I,P,U)(self.d,self.wins[i])
                self.wins[i]=0
                self.sizes[i]=(0,0)
                if self.backings[i]:
                    self.free(self.d,self.backings[i][0])
                    self.backings[i]=None
        for i,win in enumerate(self.wins):
            if not win: continue
            root=U(); x=I(); y=I(); w=N(); h=N(); border=N(); depth=N()
            self.geometry(self.d,win,*map(C.byref,(root,x,y,w,h,border,depth)))
            size=w.value,h.value
            if size==self.sizes[i]:
                self.pending_sizes[i]=None
            elif self.pending_sizes[i] is None or self.pending_sizes[i][0]!=size:
                self.pending_sizes[i]=(size,time.monotonic())
            elif time.monotonic()-self.pending_sizes[i][1]>=.10:
                self.sizes[i]=size
                self.pending_sizes[i]=None
        return struct.pack(">4I",*self.sizes[0],*self.sizes[1])

    def frame(self,i,w,h,client):
        if not self.wins[i]:
            return
        pix=self.generate(self.conn)
        fd=I(os.dup(client.fileno()))
        cookie=self.import_frame(self.conn,pix,self.wins[i],1,w,h,w*4,0,0,0,0,0,0,0,24,32,MODIFIER,C.byref(fd))
        if self.check(self.conn,cookie): raise RuntimeError(f"DRI3 import failed for {i} {w}x{h}")
        backing=self.backings[i]
        if backing is None or backing[1:]!=(w,h):
            new=self.create(self.d,self.wins[i],w,h,24)
            self.background(self.d,self.wins[i],new)
            if backing: self.free(self.d,backing[0])
            self.backings[i]=backing=(new,w,h)
        # The background pixmap also restores static app content after expose/unminimize.
        self.copy(self.d,pix,backing[0],self.gc,0,0,w,h,0,0)
        self.clear(self.d,self.wins[i])
        self.free(self.d,pix)
        self.sync(self.d,0)
        if self.last_frame_sizes[i]!=(w,h):
            self.last_frame_sizes[i]=(w,h)
            print("IMPORTED",i,w,h,flush=True)


def serve():
    windows=XWindows()
    with socket.socket(socket.AF_UNIX) as frames, socket.socket() as control:
        frames.bind("\0xdesk-android-frame-probe"); frames.listen(1); frames.settimeout(6)
        control.bind(("127.0.0.1",0)); control.listen(1); control.settimeout(90)
        token=secrets.token_hex(16).encode()
        print("READY",control.getsockname()[1],token.decode(),*map(hex,windows.wins),flush=True)
        deadline=time.monotonic()+90
        while True:
            control.settimeout(max(.1,deadline-time.monotonic()))
            conn,_=control.accept()
            with conn:
                conn.settimeout(5)
                with conn.makefile("rb") as stream:
                    if stream.read(len(token))!=token:
                        if time.monotonic()>deadline: return
                        continue
                    while True:
                        cmd=stream.read(1)
                        if not cmd: return
                        if cmd==b"\0":
                            conn.sendall(windows.changes())
                            if not any(windows.wins): return
                        elif cmd in (b"\1",b"\2"):
                            w,h=struct.unpack(">II",stream.read(8))
                            peer,_=frames.accept()
                            with peer:
                                windows.frame(cmd[0]-1,w,h,peer)
                                peer.sendall(b"\1")
                        else: raise ValueError("invalid control message")


if __name__=="__main__":
    serve()
