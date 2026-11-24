#!/usr/bin/env python3
"""Run the four shared-window presentation cases inside XDesk's X11 session."""
import argparse
import hashlib
import os
import socket
import struct
import subprocess
from pathlib import Path

DRIVER = "libgallium-27.0.0-devel.so"
SOCKET = Path("/tmp/xdesk-inner-cube.sock")
# Wire protocol identifier. Access is controlled by the private rootfs, socket
# permissions and the host's SO_PEERCRED check.
HELLO = b"c9bd08b56536d8ceba2f849787dd103b"
CASES = [(640, 480, 1), (640, 480, 0), (2560, 1600, 1), (2560, 1600, 0)]


def run_case(connection, index, driver_dir):
    expected = (index + 1, *CASES[index])
    connection.settimeout(30)
    actual = struct.unpack("=4i", connection.recv(16))
    if actual != expected:
        raise RuntimeError(f"Unexpected phase header {actual}, expected {expected}")
    _, width, height, copy = actual
    env = os.environ.copy()
    libpath = [str(driver_dir), *filter(None, env.get("LD_LIBRARY_PATH", "").split(":"))]
    env.update(
        XDESK_DIAG_PRESENT_FD=str(connection.fileno()),
        XDESK_DIAG_COPY=str(copy),
        MESA_LOADER_DRIVER_OVERRIDE="zink",
        GALLIUM_DRIVER="zink",
        TU_DEBUG="sysmem",
        mesa_glthread="false",
        LD_LIBRARY_PATH=":".join(libpath),
    )
    if width == 2560:
        env["LD_PRELOAD"] = str(driver_dir / "geometry-only.so")
    # The injected driver deliberately exits this disposable client when the
    # host acknowledges its last buffer. Never attach it to a user session.
    # Python's socket timeout enables O_NONBLOCK on the underlying file
    # description, including the fd inherited by Mesa's blocking recvmsg.
    connection.setblocking(True)
    child = subprocess.run(
        ["stdbuf", "-oL", "/usr/bin/glxgears", "-info", "-geometry", f"{width}x{height}+0+0"],
        env=env, pass_fds=(connection.fileno(),), timeout=30,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    print("\n".join(line for line in child.stdout.splitlines() if len(line) < 500), flush=True)
    child.check_returncode()
    if "KOPPER_DONE" not in child.stdout or child.stdout.count("KOPPER_PIXELS ") != 6:
        raise RuntimeError("Missing swap acknowledgement or per-slot pixel checks")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("driver_dir", type=Path)
    directory = parser.parse_args().driver_dir.resolve(strict=True)
    for name in [DRIVER, "geometry-only.so"]:
        if not (directory / name).is_file():
            parser.error(f"Missing {directory / name}")
    print("glxgears SHA256", hashlib.sha256(Path("/usr/bin/glxgears").read_bytes()).hexdigest(), flush=True)
    with socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET) as server:
        os.umask(0o077)
        server.bind(str(SOCKET))
        try:
            server.settimeout(60)
            server.listen(1)
            print("Ready for the Android diagnostic activity", flush=True)
            client, _ = server.accept()
            with client:
                client.sendall(HELLO)
                for phase in range(len(CASES)):
                    run_case(client, phase, directory)
        finally:
            SOCKET.unlink()
    print("PASS GLX")


if __name__ == "__main__":
    main()
