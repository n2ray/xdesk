"""Build the read-only path index and APK objects from the pinned XDesk tarball."""
import json
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

if not all(path in nodes for path in observed):
    raise ValueError("expected Ubuntu arm64 ELFs missing")
with index.open("w") as out:
    for path, (kind, value) in sorted(nodes.items()):
        out.write(f"{{{json.dumps(path)}, IMG_{kind}, {json.dumps(value)}}},\n")
print(f"{len(nodes)} archive entries indexed")
