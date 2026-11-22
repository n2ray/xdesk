#!/usr/bin/python3 -I
"""Private launcher for the fixed headless sandboxed zygote only."""
import array
import os
import socket
import sys

if sys.argv[1:] == ["--get-api"]:
    print(1)
    sys.exit(0)
if sys.argv[1:2] != ["/opt/chrome"] or sys.argv.count("--type=zygote") != 1:
    sys.exit(125)
if any(arg in {"--no-sandbox", "--no-zygote-sandbox"} for arg in sys.argv):
    sys.exit(125)

# M158: zygote control, sandbox IPC, SUID ABI dummy pipe. The service starts
# only the signed headless command and does not receive caller arguments.
with socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET) as request:
    request.settimeout(45)
    request.connect("/tmp/zygote.sock")
    rights = array.array("i", [3, 4, 7])
    if request.sendmsg([b"Z"], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, rights)]) != 1:
        sys.exit(125)
    reply = request.recv(2)
    sys.exit(reply[0] if len(reply) == 1 else 125)
