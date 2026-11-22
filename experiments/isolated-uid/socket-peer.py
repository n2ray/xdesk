"""One end of the isolated-UID experiment, run by Ubuntu's own Python."""
import array
import errno
import fcntl
import os
import socket
import struct
import sys

creds = struct.Struct("3i")
integer = array.array("i").itemsize


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def receive(sock, want_fd=False):
    payload, controls, flags, _ = sock.recvmsg(
        256, socket.CMSG_SPACE(creds.size) + socket.CMSG_SPACE(integer),
        socket.MSG_CMSG_CLOEXEC)
    require(payload and not flags & (socket.MSG_TRUNC | socket.MSG_CTRUNC), "incomplete packet")
    sender = None
    fds = array.array("i")
    for level, kind, data in controls:
        require(level == socket.SOL_SOCKET, "unexpected control level")
        if kind == socket.SCM_CREDENTIALS:
            require(sender is None, "duplicate sender")
            sender = creds.unpack(data)
        elif kind == socket.SCM_RIGHTS:
            fds.frombytes(data)
        else:
            raise RuntimeError("unexpected control type")
    require(sender and sender[0] > 0 and len(fds) == int(want_fd), "missing or extra ancillary data")
    return payload, sender, fds


def send(sock, payload, fd=None):
    controls = [] if fd is None else [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [fd]))]
    require(sock.sendmsg([payload], controls) == len(payload), "short packet")


def run(role):
    # fd 3 connects only to this guest's Android launcher, which measures the
    # kernel credentials independently of the socket between the two guests.
    os.write(3, b"R")
    os.close(3)
    with socket.socket(fileno=0) as sock:
        sock.settimeout(5)
        creator = creds.unpack(sock.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, creds.size))
        print("guest_pid=" + str(os.getpid()))
        print("pair_peercred_pid_uid=%d:%d" % creator[:2])
        challenge = os.urandom(16)
        if role == "ordinary":
            hello, peer, _ = receive(sock)
            require(len(hello) == 17 and hello[:1] == b"H", "bad hello")
            with open("/owner-marker", "rb", buffering=0) as marker:
                expected = marker.read()
                require(len(expected) == 37, "invalid test marker")
                marker.seek(0)
                send(sock, b"F" + hello[1:] + challenge, marker.fileno())
                reply, again, _ = receive(sock)
                require(again == peer and reply == b"A" + challenge + expected, "bad acknowledgement")
            send(sock, b"D" + challenge)
        else:
            send(sock, b"H" + challenge)
            request, peer, (fd,) = receive(sock, True)
            with os.fdopen(fd, "rb") as marker:
                require(len(request) == 33 and request[:17] == b"F" + challenge, "bad request")
                st = os.fstat(fd)
                require(fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_ACCMODE == os.O_RDONLY, "writable file")
                try:
                    os.write(fd, b"")
                except OSError as e:
                    require(e.errno == errno.EBADF, "unexpected write errno")
                else:
                    raise RuntimeError("write accepted")
                print(f"received_file={st.st_dev}:{st.st_ino}")
                print("received_mode=O_RDONLY write=EBADF")
                data = marker.read(64)
            try:
                with open("/owner-marker", "rb"):
                    raise RuntimeError("private path opened")
            except OSError as e:
                require(e.errno in (errno.ENOENT, errno.EACCES), "unexpected path error")
                print(f"denied_path_errno={e.errno}")
            send(sock, b"A" + request[17:] + data)
            done, again, _ = receive(sock)
            require(again == peer and done == b"D" + request[17:], "missing final acknowledgement")
        print("sender_pid_uid=%d:%d" % peer[:2])
        print("socket_exchange=ok")


run(sys.argv[1])
