"""Bundle the arm64 Bionic desktop from pinned Termux debs and resolve its ELF closure."""
from xfwm import theme
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CACHE = Path(os.environ.get("XDESX_PACKAGE_CACHE", "/tmp/xdesx-pkgs"))
LOCK = HERE / "packages.lock.json"
PREFIX = b"/data/data/com.termux/files/usr"
# Every native desktop child starts in its private prefix, including GIO desktop entries.
RELOCATED = b"/proc/self/cwd".ljust(len(PREFIX), b"/")
BASE = "https://packages-cf.termux.dev/apt/termux-"
SDK = Path(os.environ["ANDROID_NDK_HOME"]) / "toolchains/llvm/prebuilt"
BIN = next(SDK.glob("*/bin"))
CACHE.mkdir(parents=True, exist_ok=True)
update = "--lock" in sys.argv
packages = {} if update else json.loads(LOCK.read_text())
owners = {}
if update:
    for repo, suite in [("main","stable"),("x11","x11")]:
        for local, remote in [("gz","main/binary-aarch64/Packages.gz"),("contents.gz","Contents-aarch64.gz")]:
            path = CACHE / (repo+"."+local)
            if not path.exists():
                subprocess.run(["curl","-fLsS",BASE+repo+"/dists/"+suite+"/"+remote,"-o",path],check=True)
        for par in gzip.open(CACHE/(repo+".gz"),"rt").read().split("\n\n"):
            fields = dict(line.split(": ",1) for line in par.splitlines() if ": " in line and not line.startswith(" "))
            if "Package" in fields:
                packages[fields["Package"]]=[BASE+repo+"/"+fields["Filename"],fields["SHA256"]]
        for line in gzip.open(CACHE/(repo+".contents.gz"),"rt"):
            path, _, owner = line.strip().rpartition(" ")
            if path.startswith(PREFIX.decode()[1:]+"/lib/"):
                owners[path.split("/usr/",1)[1]]=owner.split(",")[0]
work=Path(tempfile.mkdtemp(prefix="xdesx-runtime-"))
prefix=work/"data/data/com.termux/files/usr"
assets=ROOT/"lorie-app/src/main/assets/xdesx"
native=ROOT/"lorie-app/src/main/jniLibs/arm64-v8a"
selected=set()
def install(name):
    if name in selected: return
    url,checksum=packages[name]
    deb=CACHE/url.rsplit("/",1)[1]
    if not deb.exists():
        subprocess.run(["curl","--retry","3","-fLsS",url,"-o",deb],check=True)
    if hashlib.sha256(deb.read_bytes()).hexdigest()!=checksum: raise ValueError(name+" checksum")
    archive=next(n for n in subprocess.check_output(["ar","t",deb],text=True).splitlines() if n.startswith("data.tar"))
    with tempfile.NamedTemporaryFile(suffix=archive.rstrip("/")) as f:
        subprocess.run([BIN/"llvm-ar","p",deb,archive.rstrip("/")],stdout=f,check=True); f.seek(0)
        subprocess.run(["tar","-xf",f.name,"-C",work],check=True)
    selected.add(name)

for n in (["xfwm4","xfce4-panel","xfconf","dbus","glib","libx11","libxcb","xorgproto","xkeyboard-config","gsettings-desktop-schemas","gtk3","libxau","libxdmcp"] if update else packages):
    install(n)
subprocess.run([sys.executable,HERE/"xfwm/build.py",prefix,BIN/"aarch64-linux-android30-clang",CACHE,work],check=True)
programs=["bin/xfwm4","bin/xfce4-panel","bin/dbus-daemon","lib/xfce4/xfconf/xfconfd",
          "libexec/gio-launch-desktop","lib/xfce4/panel/wrapper-2.0"]
system=set("libandroid.so libc.so libdl.so liblog.so libm.so libz.so libEGL.so libGLESv1_CM.so libGLESv2.so libGLESv3.so libjnigraphics.so libOpenSLES.so libvulkan.so libmediandk.so libnativewindow.so libaaudio.so".split())
pending=programs+["lib/libX11-xcb.so","lib/libxcb-dri3.so","lib/libwnck-3.so","lib/libxfconf-0.so"]
links={}
blobs={}
while pending:
    item=pending.pop()
    if item in links: continue
    src=prefix/item
    if not src.exists() and update: install(owners[item])
    data=src.read_bytes()
    digest=hashlib.sha256(data).digest()
    if digest in blobs:
        links[item]=blobs[digest]
        continue
    out="libxd_"+str(len(blobs))+".so"
    blobs[digest]=out
    links[item]=out
    (native/out).write_bytes(data.replace(PREFIX,RELOCATED))
    needed=re.findall(r'Shared library: \[(.*?)\]',subprocess.check_output([BIN/"llvm-readelf","-d",src],text=True))
    pending.extend("lib/"+n for n in needed if n not in system)
client=native/"libxdclient.so"
subprocess.run([BIN/"aarch64-linux-android30-clang","-O2","-I"+str(prefix/"include"),assets/"client.c","-L"+str(prefix/"lib"),"-Wl,--allow-shlib-undefined","-lX11","-lX11-xcb","-lxcb","-lxcb-dri3","-o",client],check=True)
links["bin/xdesx-window"]=client.name
# Compile the internal panel module against the same pinned Bionic ABI as its host.
includes = ["include", "include/gtk-3.0", "include/glib-2.0", "lib/glib-2.0/include",
            "include/cairo", "include/pango-1.0", "include/harfbuzz", "include/gdk-pixbuf-2.0",
            "include/atk-1.0", "include/xfce4", "include/xfce4/libxfce4panel-2.0",
            "include/xfce4/xfconf-0", "include/libwnck-3.0", "include/freetype2"]
panel=native/"libxdpanel.so"
subprocess.run([BIN/"aarch64-linux-android30-clang","-O2","-fPIC","-shared",
    *["-I"+str(prefix/i) for i in includes],assets/"panel.c","-L"+str(prefix/"lib"),
    "-Wl,--allow-shlib-undefined","-lxfce4panel-2.0","-lxfconf-0","-lwnck-3","-lgtk-3",
    "-lgdk-3","-lgdk_pixbuf-2.0","-lgio-2.0","-lgobject-2.0","-lglib-2.0","-lX11","-lcairo","-lXcursor","-lXfixes","-lm","-o",panel],check=True)
links["lib/xfce4/panel/plugins/libxdesx.so"]=panel.name
schemas=prefix/"share/glib-2.0/schemas"
subprocess.run(["glib-compile-schemas",schemas],check=True)
with zipfile.ZipFile(assets/"runtime.zip","w",zipfile.ZIP_DEFLATED) as z:
    for pattern in ["share/X11/xkb/**/*","share/xkeyboard-config*/**/*","share/xfwm4/defaults","share/themes/Default/xfwm4/*",
                    "share/xfce4/panel/plugins/applicationsmenu.desktop","share/glib-2.0/schemas/gschemas.compiled",
                    "etc/xdg/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml","etc/xdg/xfce4/xfconf/xfce-perchannel-xml/xfce4-keyboard-shortcuts.xml","share/licenses/**/*","share/doc/*/copyright"]:
        for p in prefix.glob(pattern):
            if p.is_file(): z.writestr(str(p.relative_to(prefix)),p.read_bytes().replace(PREFIX,RELOCATED))
    theme.write(z)
    z.write(ROOT/"tools/xdesx/art/settings.png","share/xdesx/settings.png")
    z.write(HERE/"art/power.png","share/xdesx/power.png")
    z.write(HERE/"art/fonts/LICENSE.txt","share/licenses/source-han-sans/LICENSE.txt")
    z.write(assets/"xdesx.desktop","share/xfce4/panel/plugins/xdesx.desktop")
    z.writestr("links.json",json.dumps(links))
if update: LOCK.write_text(json.dumps({n:packages[n] for n in sorted(selected)},indent=2)+"\n")
shutil.rmtree(work)
print(len(links),"ELF files from",len(selected),"packages")
