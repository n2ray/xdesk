"""Keep a synthetic file in release XDesk's private Ubuntu rootfs for 12 s.

Run inside XDesk Ubuntu: python3 release-owner.py RUN_UUID
The same UUID is supplied to the side-by-side test activity while READY.
"""
import os
from pathlib import Path
import secrets
import sys
import time
import uuid

run_id = str(uuid.UUID(sys.argv[1]))
path = Path("/tmp") / ("fd-probe-" + run_id)
token = secrets.token_bytes(32)
with path.open("xb") as marker:
    try:
        os.fchmod(marker.fileno(), 0o600)
        marker.write(token)
        marker.flush()
        st = os.fstat(marker.fileno())

        def owner_can_read():
            return os.path.samestat(st, path.stat()) and path.read_bytes() == token

        if not owner_can_read():
            raise RuntimeError("owner control failed before test")
        print("READY", run_id, st.st_dev, st.st_ino, time.time(), flush=True)
        time.sleep(12)
        if not owner_can_read():
            raise RuntimeError("owner control failed after test")
        print("OWNER_RECHECK_OK", run_id, st.st_dev, st.st_ino, time.time(), flush=True)
    finally:
        path.unlink()
