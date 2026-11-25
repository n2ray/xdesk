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
token = (base / "session.token").read_text().strip()
key = secrets.token_hex(16)
path = Path(os.environ["TMPDIR"]) / ("xdesx-" + key)
try:
    with socket.socket(socket.AF_UNIX) as listener:
        listener.bind(str(path))
        listener.listen(3)
        subprocess.run(["am","start","--display","0","-n",
                        "io.github.n2ray.xdesk.x11/com.termux.x11.XdesxActivity",
                        "--es","token",token,"--es","window",key,"--es","component",component],
                       check=True)
        subprocess.run([str(base / "client"),str(listener.fileno()),label],
                       pass_fds=(listener.fileno(),),check=True)
finally:
    path.unlink(missing_ok=True)
