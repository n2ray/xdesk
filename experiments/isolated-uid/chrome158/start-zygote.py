"""Receive the M158 launch handles on stdin; exec only the installed stock helper."""
import array
import fcntl
import os
import socket
import stat
import sys

with socket.socket(fileno=0) as request:
    rights = array.array("i")
    msg, aux, flags, _ = request.recvmsg(1, socket.CMSG_SPACE(3 * rights.itemsize), socket.MSG_CMSG_CLOEXEC)
    if (msg != b"Z" or flags & (socket.MSG_CTRUNC | socket.MSG_TRUNC) or len(aux) != 1
            or aux[0][:2] != (socket.SOL_SOCKET, socket.SCM_RIGHTS)):
        sys.exit(125)
    rights.frombytes(aux[0][2])
    if len(rights) != 3:
        sys.exit(125)
    for slot, fd in enumerate(rights[:2]):
        with socket.socket(fileno=os.dup(fd)) as endpoint:
            if endpoint.family != socket.AF_UNIX or not isinstance(endpoint.getpeername(), (str, bytes)):
                sys.exit(125)
            if slot == 0 and endpoint.getsockopt(socket.SOL_SOCKET, socket.SO_TYPE) != socket.SOCK_SEQPACKET:
                sys.exit(125)
    if not stat.S_ISFIFO(os.fstat(rights[2]).st_mode) or fcntl.fcntl(rights[2], fcntl.F_GETFL) & os.O_ACCMODE:
        sys.exit(125)
    safe = [fcntl.fcntl(fd, fcntl.F_DUPFD_CLOEXEC, 10) for fd in rights]
    for fd in rights:
        os.close(fd)
    for src, dst in zip(safe, [3, 4, 7]):
        os.dup2(src, dst, inheritable=True)
        os.close(src)

# Avoid leaving stdin vacant across exec. The original helper owns its chroot
# protocol and the original zygote owns BOOT/HELLO.
null = os.open("/dev/null", os.O_RDONLY)
if null == 0:
    os.set_inheritable(0, True)
else:
    os.dup2(null, 0, inheritable=True)
    os.close(null)
os.setgroups([])
os.setresgid(1000, 1000, 1000)
os.setresuid(1000, 1000, 1000)
base = "/opt/xdesk-chrome/"
os.execve(base + "chrome-sandbox", [base + "chrome-sandbox", base + "chrome", "--type=zygote", "--headless=new"],
          {"LC_ALL": "C", "LD_LIBRARY_PATH": "/opt/xdesk-chrome", "SBX_CHROME_API_RQ": "1"})
