"""Private session sockets. Forward menu selections until the phone controller disconnects."""
from pathlib import Path
import os
import select
import socket

base = Path.home() / ".local/share/xdesx"
key = (base / "session.token").read_text().strip()
up = Path(os.environ["TMPDIR"]) / ("xdesx-" + key)
menus = Path(os.environ["XDG_RUNTIME_DIR"]) / "launch.sock"
try:
    with socket.socket(socket.AF_UNIX) as app, socket.socket(socket.AF_UNIX) as requests:
        for listener, path in [(app, up),(requests, menus)]:
            path.unlink(missing_ok=True)
            listener.bind(str(path))
            listener.listen(4)
        phone, _ = app.accept()
        with phone:
            while True:
                ready, _, _ = select.select([phone, requests], [], [])
                if phone in ready:
                    break
                peer, _ = requests.accept()
                with peer, peer.makefile("rb") as stream:
                    peer.settimeout(3)
                    line = stream.readline(2048)
                    if line.endswith(b"\n"):
                        phone.sendall(line)
finally:
    up.unlink(missing_ok=True)
    menus.unlink(missing_ok=True)
