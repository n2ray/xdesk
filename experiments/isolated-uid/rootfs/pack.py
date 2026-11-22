"""Build the read-only path index and APK objects from the pinned XDesk tarball."""
from collections import defaultdict
import json
import struct
import shutil
import sys
import tarfile
from pathlib import Path, PurePosixPath

archive, lib, index = map(Path, sys.argv[1:])
# Stable object names let the existing stat-vs-maps assertion cover both APK formats.
observed = {
    "/usr/bin/dash": "libubuntu_dash.so",
    "/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1": "libubuntu_ld.so",
    "/usr/lib/aarch64-linux-gnu/libc.so.6": "libubuntu_libc.so",
}
nodes = {}
with tarfile.open(archive, "r|gz") as tar:
    for member in tar:
        path = PurePosixPath(member.name)
        if path.is_absolute() or ".." in path.parts:
            raise ValueError(member.name)
        path = "/" + "/".join(path.parts)
        if path in nodes:
            raise ValueError("duplicate " + path)
        if member.isdir():
            node = ("DIR", "")
        elif member.issym():
            node = ("LINK", member.linkname)
        elif member.isfile():
            name = observed.get(path, f"libfs{len(nodes):05}.so")
            with (lib / name).open("xb") as out:
                shutil.copyfileobj(tar.extractfile(member), out)
            node = ("FILE", name)
        else:
            raise ValueError("unsupported member " + path)
        nodes[path] = node

chrome_index = lib / "libchrome158_index.so"
if chrome_index.exists():
    # The isolated UID cannot traverse the browser's writable staging directory.
    # Resolve only the build-time allowlist to APK installer-owned objects.
    prefix = "/opt/xdesk-chrome"
    if nodes.get("/opt") != ("DIR", "") or prefix in nodes or "/opt/locales" in nodes:
        raise ValueError("unexpected /opt layout")
    nodes[prefix] = ("DIR", "")
    nodes[prefix + "/locales"] = ("DIR", "")
    nodes["/opt/locales"] = ("DIR", "")
    for line in chrome_index.read_text().splitlines():
        backing, name = line.split()
        path = prefix + "/" + name
        if PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts or path in nodes:
            raise ValueError(name)
        nodes[path] = ("FILE", backing)
        if name != "start-zygote.py":
            browser_path = "/opt/" + name
            if browser_path in nodes:
                raise ValueError("browser image collision: " + browser_path)
            # Keep the distribution helper in the isolated service's tree.
            # Only the browser has the XDesk dispatcher as its sibling.
            target = "libchrome158_dispatch.so" if name == "chrome-sandbox" else backing
            nodes[browser_path] = ("FILE", target)
    if "/opt/entry.py" in nodes:
        raise ValueError("browser entry already present")
    nodes["/opt/entry.py"] = ("FILE", "libchrome158_entry.so")

if not all(path in nodes for path in observed):
    raise ValueError("expected Ubuntu arm64 ELFs missing")
paths = sorted(nodes)
ids = {path: i for i, path in enumerate(paths)}
children = defaultdict(list)
for path in paths:
    if path != "/":
        children[str(PurePosixPath(path).parent)].append(path)

types = {"DIR": 4, "FILE": 8, "LINK": 10}  # linux_dirent64 d_type
with index.open("w") as out:
    for i, path in enumerate(paths):
        kind, value = nodes[path]
        backing = value if kind == "FILE" else f"libnode{i}.so"
        width = 0
        if kind == "DIR":
            entries = [(".", path), ("..", str(PurePosixPath(path).parent))]
            entries.extend((PurePosixPath(p).name, p) for p in children[path])
            # Uniform, aligned records in each directory allow one kernel read
            # to advance a shared open-file offset by whole directory entries.
            width = (20 + max(len(n.encode()) for n, _ in entries) + 7) & ~7
            with (lib / backing).open("xb") as directory:
                for row, (name, child) in enumerate(entries, 1):
                    header = struct.pack("<QqHB", ids[child], row * width, width, types[nodes[child][0]])
                    directory.write((header + name.encode() + b"\0").ljust(width, b"\0"))
        elif kind == "LINK":
            (lib / backing).write_bytes(value.encode())
        out.write(f"{{{json.dumps(path)}, IMG_{kind}, {json.dumps(value)}, {json.dumps(backing)}, {width}}},\n")
print(f"{len(nodes)} archive entries indexed")
