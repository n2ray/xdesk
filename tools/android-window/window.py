"""Fixed-size XFCE window for the Android hardware-buffer experiment."""
import base64
import ctypes as C
import hashlib
import os
import select
import socket
import struct
import sys
import time
import zlib

W, H = 720, 480
MODIFIER = 0x58443333
P, U, N, I = C.c_void_p, C.c_ulong, C.c_uint, C.c_int
U8, U16 = C.c_uint8, C.c_uint16


def function(lib, name, result, *args):
    f = getattr(lib, name)
    f.restype, f.argtypes = result, args
    return f


def attach_window():
    x = C.CDLL("libX11.so.6")
    d = function(x, "XOpenDisplay", P, C.c_char_p)(None)
    if not d:
        raise RuntimeError("cannot open DISPLAY")
    root = function(x, "XDefaultRootWindow", U, P)(d)
    win = function(x, "XCreateSimpleWindow", U, P, U, I, I, N, N, N, U, U)(d, root, 100, 100, W, H, 0, 0, 0)
    function(x, "XStoreName", I, P, U, C.c_char_p)(d, win, b"Android Chrome - direct AHardwareBuffer")
    function(x, "XMapRaised", I, P, U)(d, win)
    gc = function(x, "XCreateGC", P, P, U, U, P)(d, win, 0, None)
    sync = function(x, "XSync", I, P, I)
    sync(d, 0)
    return x, d, win, gc, sync


class XImage(C.Structure):
    _fields_ = [("geometry", I * 4), ("data", P)]


def snapshot(x, d, win):
    get = function(x, "XGetImage", P, P, U, I, I, N, N, U, I)
    image = get(d, win, 0, 0, W, H, 0xffffffff, 2)
    if not image:
        raise RuntimeError("window readback failed")
    bgra = C.string_at(XImage.from_address(image).data, W * H * 4)
    function(x, "XDestroyImage", I, P)(image)
    return bgra


def png(bgra):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    rgb = bytearray(W * H * 3)
    rgb[0::3], rgb[1::3], rgb[2::3] = bgra[2::4], bgra[1::4], bgra[0::4]
    rows = b"".join(b"\x00" + rgb[i:i + W * 3] for i in range(0, len(rgb), W * 3))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def main():
    x, display, win, gc, sync = attach_window()
    conn = function(C.CDLL("libX11-xcb.so.1"), "XGetXCBConnection", P, P)(display)
    cb, dri = C.CDLL("libxcb.so.1"), C.CDLL("libxcb-dri3.so.0")
    generate = function(cb, "xcb_generate_id", N, P)
    import_frame = function(dri, "xcb_dri3_pixmap_from_buffers_checked", N, P, N, N, U8,
                            U16, U16, *([N] * 8), U8, U8, C.c_uint64, C.POINTER(I))
    check = function(cb, "xcb_request_check", P, P, N)
    copy = function(x, "XCopyArea", I, P, U, U, P, I, I, N, N, I, I)
    free = function(x, "XFreePixmap", I, P, U)
    keep_open = "--keep-open" in sys.argv
    count, distinct, last = 0, set(), None
    start = None
    with socket.socket(socket.AF_UNIX) as listener:
        listener.bind("\0xdesk-android-frame-probe")
        listener.listen(1)
        listener.settimeout(45)
        print("LISTENING", hex(win), flush=True)
        while True:
            try:
                client, _ = listener.accept()
            except TimeoutError:
                if keep_open and count:
                    sync(display, 0)
                    continue
                break
            with client:
                if not select.select([client], [], [], 2)[0]:
                    raise RuntimeError("no queued Android frame")
                if start is None:
                    start = time.monotonic()
                pix = generate(conn)
                fd = I(os.dup(client.fileno()))  # libxcb takes ownership.
                cookie = import_frame(conn, pix, win, 1, W, H, W*4, 0, 0, 0, 0, 0, 0, 0,
                                      24, 32, MODIFIER, C.byref(fd))
                error = check(conn, cookie)
                if error:
                    raise RuntimeError(f"DRI3 import error {C.string_at(error, 2)[1]}")
                copy(display, pix, win, gc, 0, 0, W, H, 0, 0)
                free(display, pix)
                sync(display, 0)  # Imported pixmap destruction completes GPU reads before ACK.
                client.sendall(b"\x01")
                count += 1
                if not keep_open and count % 10 == 1:
                    last = snapshot(x, display, win)
                    distinct.add(hashlib.sha256(last).digest())
                end = time.monotonic()
                listener.settimeout(4)
    if not count:
        raise RuntimeError("no imported frames")
    print("FRAMES", count, "SECONDS", round(end-start, 3), "DISTINCT_SAMPLES", len(distinct), flush=True)
    if last is not None:
        print("PNG=" + base64.b64encode(png(last)).decode())


if __name__ == "__main__":
    main()
