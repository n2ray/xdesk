"""One fixed no-helper fork on private sockets (Chromium 158, Linux arm64)."""
import array
import errno
import fcntl
import os
import socket
import struct
import traceback

FORK, REAP, STATUS, REAL_PID = 0, 1, 3, 4
CHILD_PING = b"CHILD_PING\0"
MARKER_KEY = 73  # Experiment-owned descriptor key.
WORD = struct.Struct("<i")


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def frame(payload):
    return WORD.pack(len(payload)) + payload


def string(value, encoding):
    data = value.encode(encoding)
    units = len(data) // 2 if encoding == "utf-16-le" else len(data)
    return WORD.pack(units) + data + bytes(-len(data) % 4)


def command(*values):
    return frame(b"".join(WORD.pack(value) for value in values))


def fork_request():
    args = ("probe", "--type=renderer")
    return frame(WORD.pack(FORK) + string("renderer", "ascii") + WORD.pack(len(args))
                 + b"".join(string(arg, "ascii") for arg in args)
                 + string("Etc/UTC", "utf-16-le") + struct.pack("<iI", 2, MARKER_KEY))


def recv_fork(control):
    data, records, flags, _ = control.recvmsg(65536, socket.CMSG_SPACE(8), socket.MSG_CMSG_CLOEXEC)
    fds = array.array("i")
    for level, kind, value in records:
        if (level, kind) == (socket.SOL_SOCKET, socket.SCM_RIGHTS):
            fds.frombytes(value)
    if (flags & (socket.MSG_TRUNC | socket.MSG_CTRUNC) or data != fork_request()
            or len(records) != 1 or len(fds) != 2):
        for fd in fds:
            os.close(fd)
        raise RuntimeError("unexpected fork request or descriptor mapping")
    return socket.socket(fileno=fds[0]), fds[1]


def browser(control, receive_fixed, boot, zygote, uid):
    control.sendall(command(STATUS))
    oracle, peer = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
    with oracle, peer:
        oracle.setsockopt(socket.SOL_SOCKET, socket.SO_PASSCRED, 1)
        oracle.settimeout(5)
        token = os.urandom(16)
        marker, writer = os.pipe()
        try:
            try:
                check(os.write(writer, token) == len(token), "short marker write")
            finally:
                os.close(writer)
            rights = array.array("i", [peer.fileno(), marker])
            packet = fork_request()
            check(control.sendmsg([packet], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, rights)])
                  == len(packet), "short fork request")
        finally:
            os.close(marker)
        peer.close()
        pid, observed_uid = receive_fixed(oracle, CHILD_PING)
    check(observed_uid == uid and pid not in (boot, zygote, os.getpid()), "unexpected oracle identity")
    control.sendall(command(REAL_PID, pid))
    check(control.recv(16) == WORD.pack(0), "unexpected status flags")
    # A PID and a length-zero UMA string form the entire reply for this case.
    check(control.recv(2048) == frame(WORD.pack(pid) + string("", "ascii")), "fork reply PID/UMA")
    control.sendall(command(REAP, pid))
    print(f"oracle_pid_uid={pid}:{uid} fork_reply_pid={pid} reap_requested={pid}")
    print(f"marker={token.hex()}")


def renderer(oracle, marker, read_pid, roster):
    oracle.sendall(CHILD_PING)
    oracle.close()
    pid = os.getpid()
    check(os.read(read_pid, WORD.size) == WORD.pack(pid), "global PID differs from child PID")
    os.close(read_pid)
    roster.sendall(b"C")
    roster.close()
    check(fcntl.fcntl(marker, fcntl.F_GETFL) & os.O_ACCMODE == os.O_RDONLY, "writable marker")
    token = os.read(marker, 17)
    check(len(token) == 16 and os.read(marker, 1) == b"", "unexpected marker length")
    try:
        os.write(marker, b"x")
    except OSError as e:
        check(e.errno == errno.EBADF, "unexpected write errno")
    else:
        raise RuntimeError("read-only pipe accepted write")
    os.close(marker)
    print(f"renderer_pid={pid} parent={os.getppid()} global_pid={pid}\nrenderer_guest_uid={os.getuid()}", flush=True)
    print(f"marker={token.hex()}\nfd_key={MARKER_KEY} mode=O_RDONLY write=EBADF", flush=True)


def zygote(control, roster):
    check(control.recv(64) == command(STATUS), "missing sandbox status request")
    control.sendall(WORD.pack(0))  # No Chrome sandbox flags are measured by the probe.
    oracle, marker = recv_fork(control)
    read_pid, write_pid = os.pipe()
    child = os.fork()
    if child == 0:
        try:
            control.close()
            os.close(write_pid)
            renderer(oracle, marker, read_pid, roster)
        except BaseException:
            traceback.print_exc()
            os._exit(1)
        os._exit(0)
    oracle.close()
    os.close(marker)
    os.close(read_pid)
    reaped = False
    try:
        try:
            # These two processes share a PID namespace on the target phone.
            check(control.recv(64) == command(REAL_PID, child), "real PID differs from fork PID")
            check(os.write(write_pid, WORD.pack(child)) == WORD.size, "short PID write")
        finally:
            os.close(write_pid)
        control.sendall(frame(WORD.pack(child) + string("", "ascii")))
        check(control.recv(64) == command(REAP, child), "unexpected Reap request")
        waited, status = os.waitpid(child, 0)
        reaped = True
        check(waited == child and os.waitstatus_to_exitcode(status) == 0, "renderer exit")
        try:
            os.waitpid(child, os.WNOHANG)
        except ChildProcessError as e:
            check(e.errno == errno.ECHILD, "unexpected second wait errno")
        else:
            raise RuntimeError("renderer still waitable")
        print(f"fork_pid={child} wait_pid={waited} exit=0 wait_again=ECHILD", flush=True)
    finally:
        if not reaped:
            os.waitpid(child, 0)
