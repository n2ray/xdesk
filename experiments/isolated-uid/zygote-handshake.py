"""Chrome 158's two startup records on a private experiment socketpair."""
import array
import os
import socket
import struct
import sys

BOOT = b"ZYGOTE_BOOT\0"
HELLO = b"ZYGOTE_OK\0"
UCRED = struct.Struct("3i")


def receive_fixed(control, expected):
    data, records, flags, _ = control.recvmsg(
        len(expected) + 1, socket.CMSG_SPACE(UCRED.size) + socket.CMSG_SPACE(4),
        socket.MSG_CMSG_CLOEXEC)
    credentials = []
    extra = False
    for level, kind, value in records:
        if (level, kind) == (socket.SOL_SOCKET, socket.SCM_CREDENTIALS):
            credentials.append(UCRED.unpack(value))
        else:
            extra = True
            if (level, kind) == (socket.SOL_SOCKET, socket.SCM_RIGHTS):
                fds = array.array("i")
                fds.frombytes(value)
                for fd in fds:
                    os.close(fd)
    if (flags & (socket.MSG_TRUNC | socket.MSG_CTRUNC) or data != expected or extra
            or len(credentials) != 1 or credentials[0][0] <= 1):
        raise RuntimeError("invalid startup record")
    return credentials[0][:2]


def receiver(control, launch, fork, mojo):
    boot_pid, hello_pid, isolated_uid = map(int, launch.split(":"))
    for stage, packet, authorized_pid in (("boot", BOOT, boot_pid), ("hello", HELLO, hello_pid)):
        pid, uid = receive_fixed(control, packet)
        print(f"{stage}_observed={pid}:{uid}")
        # XDesk's additional policy; Chromium's receive helper only uses pid.
        if (pid, uid) != (authorized_pid, isolated_uid):
            reason = "pid" if pid != authorized_pid else "uid"
            print(f"decision=reject_{stage}_{reason}")
            return 23
    print("decision=accept")
    if fork:
        fork["browser"](control, receive_fixed, boot_pid, hello_pid, isolated_uid, mojo)
    return 0


def sender(control, fork, mojo):
    with socket.socket(fileno=4) as registration:
        registration.settimeout(8)
        registration.sendall(b"B")
        control.sendall(BOOT)
        child = os.fork()
        if child == 0:
            done = False
            try:
                registration.sendall(b"H")
                control.sendall(HELLO)
                if fork:
                    fork["zygote"](control, registration, mojo)
                # Keep both identities live until the receiver makes its decision.
                done = registration.recv(1) == b"D"
            except Exception:
                import traceback
                traceback.print_exc()
            finally:
                os._exit(0 if done else 1)
        _, status = os.waitpid(child, 0)
        print(f"boot_pid={os.getpid()} hello_pid={child}")
        return os.waitstatus_to_exitcode(status)


os.write(3, b"R")  # Independent Android launcher observation from the socket probe.
os.dup2(0, 3)
os.close(0)
with socket.socket(fileno=3) as control:
    control.settimeout(6)
    policy = sys.argv[2] if len(sys.argv) > 2 else ""
    fork = None
    mojo = policy.startswith("mojo:")
    if mojo or policy.startswith("fork:"):
        import runpy
        fork = runpy.run_path("/renderer-fork.py")
        policy = policy[5:]
    sys.exit(sender(control, fork, mojo) if sys.argv[1] == "send" else receiver(control, policy, fork, mojo))
