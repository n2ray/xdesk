"""Build the window manager against the same relocated Bionic sysroot as the desktop."""
import hashlib
from pathlib import Path
import re
import subprocess
import sys

here = Path(__file__).resolve().parent
prefix, compiler, cache, work = map(Path, sys.argv[1:])
archive = cache / "xfwm4-4.20.0.tar.bz2"
if not archive.exists():
    subprocess.run(["curl", "-fLsS", "--retry", "3", "https://archive.xfce.org/src/xfce/xfwm4/4.20/" + archive.name, "-o", archive], check=True)
if hashlib.sha256(archive.read_bytes()).hexdigest() != "a58b63e49397aa0d8d1dcf0636be93c8bb5926779aef5165e0852890190dcf06":
    raise ValueError("xfwm4 source checksum")
subprocess.run(["tar", "-xjf", archive, "-C", work], check=True)
src = work / "xfwm4-4.20.0"
with (here / "fullscreen.patch").open() as patch:
    subprocess.run(["patch", "-p1"], cwd=src, stdin=patch, check=True)
features = "COMPOSITOR EPOXY LIBSTARTUP_NOTIFICATION PRESENT_EXTENSION RANDR RENDER XI2 XRES XSYNC LIBX11"
headers = "ERRNO FCNTL INTTYPES MATH MEMORY SIGNAL STDARG STDINT STDIO STDLIB STRINGS STRING SYS_STAT SYS_TIME SYS_TYPES SYS_WAIT TIME UNISTD WCHAR"
defines = ["HAVE_" + f for f in features.split()] + ["HAVE_" + h + "_H" for h in headers.split()]
values = {"GETTEXT_PACKAGE":"xfwm4", "PACKAGE":"xfwm4", "PACKAGE_NAME":"xfwm4", "VERSION":"4.20.0", "REVISION":"xdesx",
          "PACKAGE_LOCALE_DIR":"/proc/self/cwd/share/locale", "DATADIR":"/proc/self/cwd/share",
          "PACKAGE_DATADIR":"/proc/self/cwd/share/xfwm4", "HELPERDIR":"/proc/self/cwd/lib"}
(src / "config.h").write_text("".join("#define "+d+" 1\n" for d in defines) +
                              "".join(f'#define {k} "{v}"\n' for k,v in values.items()))
includes = ["include","include/gtk-3.0","include/glib-2.0","lib/glib-2.0/include","include/cairo","include/pango-1.0",
            "include/harfbuzz","include/gdk-pixbuf-2.0","include/atk-1.0","include/freetype2","include/xfce4",
            "include/xfce4/libxfce4ui-2","include/xfce4/libxfce4kbd-private-3","include/xfce4/xfconf-0","include/startup-notification-1.0"]
libs = "xfce4kbd-private-3 xfce4ui-2 xfce4util xfconf-0 gtk-3 gdk-3 gdk_pixbuf-2.0 pango-1.0 pangocairo-1.0 cairo gio-2.0 gobject-2.0 glib-2.0 X11 Xext Xi Xrender Xcomposite Xdamage Xfixes Xpresent Xrandr Xinerama XRes epoxy startup-notification-1 m"
sources = re.findall(r'^\s*([\w-]+\.c)\s*\\', (src/"src/Makefile.am").read_text(), re.M)
subprocess.run([compiler,"-O2","-DHAVE_CONFIG_H","-I.",*["-I"+str(prefix/i) for i in includes],
                "-I"+str(here/"compat"),"common/xfwm-common.c", *["src/"+s for s in sources],"-L"+str(prefix/"lib"),
                "-Wl,--allow-shlib-undefined", *["-l"+l for l in libs.split()],"-o",prefix/"bin/xfwm4"],cwd=src,check=True)
