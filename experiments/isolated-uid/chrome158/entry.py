"""Run the unmodified browser with a new profile and a local DOM oracle."""
import os
import resource
import stat
import sys

mode = sys.argv[1]
if mode not in {"sibling", "external", "browser", "installed"}:
    raise SystemExit("unknown Chrome experiment")
os.setgroups([])
os.setresgid(1000, 1000, 1000)
os.setresuid(1000, 1000, 1000)
resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
helper = "/opt/helper" if mode == "external" else "/opt/chrome-sandbox"
st = os.stat(helper)
print(f"case={mode} guest_uid={os.getuid()} helper={helper} owner={st.st_uid}:{st.st_gid} "
      f"chrome_owner={os.stat('/opt/chrome').st_uid} mode={stat.S_IMODE(st.st_mode):o} x_ok={int(os.access(helper, os.X_OK))}", flush=True)
if mode == "external":
    assert not os.path.exists("/opt/chrome-sandbox")
    os.environ["CHROME_DEVEL_SANDBOX"] = helper
if mode == "browser":
    os.environ["LD_PRELOAD"] = "/opt/browser-load.so"
with open("/tmp/local.html", "x") as out:
    out.write("<p id='xdesk-oracle'>isolated-158</p>")
os.mkdir("/tmp/profile", 0o700)
os.execv("/opt/chrome", ["/opt/chrome", "--headless=new", "--disable-gpu", "--no-first-run",
                         "--user-data-dir=/tmp/profile", "--dump-dom", "file:///tmp/local.html"])
