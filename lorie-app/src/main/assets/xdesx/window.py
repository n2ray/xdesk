"""Create one native X client and request its Android surface from the phone."""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys

base = Path(__file__).resolve().parent
index = int(sys.argv[1])
component, label = json.loads((base / "catalog.json").read_text())[index]
key = secrets.token_hex(16)
path = Path(os.environ["TMPDIR"]) / ("xdesx-" + key)
try:
    with socket.socket(socket.AF_UNIX) as listener:
        listener.bind(str(path))
        listener.listen(3)
        with socket.socket(socket.AF_UNIX) as request:
            request.connect(str(base / "runtime/launch.sock"))
            request.sendall((json.dumps([key, component])+"\n").encode())
        subprocess.run([str(base / "client"),str(listener.fileno()),label],
                       pass_fds=(listener.fileno(),),check=True)
finally:
    path.unlink(missing_ok=True)
