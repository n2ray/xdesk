"""Start the two-window probe with an existing XDesk experimental APK and running XFCE desktop."""
import argparse
import base64
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import tempfile
import time

PACKAGE = "io.github.n2ray.xdesk.x11"
FILES = "/sdcard/Android/data/" + PACKAGE + "/files/"
HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial]

    def run(*words):
        return subprocess.check_output(adb + list(words))

    def shell(command):
        return run("shell", command).decode().strip()

    if not shell("pidof xfwm4"):
        raise RuntimeError("Start the XDesk built-in desktop first.")
    instance = secrets.token_hex(5)
    device = "/data/local/tmp/xdesk-dual-" + instance
    guest = "/tmp/xdesk-dual-" + instance
    files = ["window.py", "two_windows.py"]
    script = ["set -eu", "(", "umask 077", "mkdir " + guest, "cd " + guest]
    for name in files:
        data = base64.b64encode((HERE / name).read_bytes()).decode()
        script.append(f"echo {data} | base64 -d > {name}")
    script += [
        "chown -R xdesk:xdesk " + guest,
        "export DISPLAY=:1",
        "/usr/local/bin/xdesk-asuser xdesk python3 -u "+guest+"/two_windows.py > windows.log 2>&1 &",
        "child=$!", "trap 'kill $child 2>/dev/null || true' EXIT",
        "for t in $(seq 1 50); do grep -q '^READY ' windows.log && break; kill -0 $child || break; sleep .1; done",
        "echo SESSION "+instance, "cat windows.log", "wait $child", ")"
    ]
    with tempfile.TemporaryDirectory() as tmp:
        job = Path(tmp) / "debug.sh"
        prior = Path(tmp) / "original.sh"
        prior.write_bytes(run("exec-out", "cat", FILES+"debug.sh"))
        job.write_text("\n".join(script)+"\n")
        run("push", str(job), FILES+"debug.sh")
        try:
            shell("am broadcast -a io.github.n2ray.xdesk.BUILTIN_LINUX -p "+PACKAGE+" --es cmd run")
            for _ in range(60):
                log = run("exec-out","cat",FILES+"logs/run.log").decode()
                ready = re.search(r"^READY (\d+) ([0-9a-f]{32}) (0x\w+) (0x\w+)$",log,re.M)
                if ready and "SESSION "+instance in log: break
                time.sleep(.2)
            else: raise RuntimeError("Guest did not start: "+log[-800:])
        finally:
            run("push",str(prior),FILES+"debug.sh")
    port,token,chrome_win,youtube_win = ready.groups()
    shell("mkdir -m 700 "+device)
    run("push",str(args.jar),device+"/two.jar")
    shell(f"CLASSPATH={device}/two.jar app_process / TwoDisplays {port} {token} < /dev/null > {device}/events.log 2>&1 &")
    for _ in range(50):
        events = shell("cat "+device+"/events.log")
        ids = dict(re.findall(r"^(Chrome|YouTube) display=(\d+)",events,re.M))
        if len(ids)==2: break
        time.sleep(.2)
    else: raise RuntimeError("Virtual displays did not start: "+events)
    chrome = "https://webglsamples.org/aquarium/aquarium.html?numFish=10000"
    shell(f"am start --display {ids['Chrome']} -f 0x18000000 -a android.intent.action.VIEW -d {shlex.quote(chrome)} -p com.android.chrome")
    component = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.google.android.youtube").splitlines()[-1]
    if not component.startswith("com.google.android.youtube/"): raise RuntimeError(component)
    shell(f"am start --display {ids['YouTube']} -f 0x18000000 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {shlex.quote(component)}")
    print("Chrome",ids["Chrome"],chrome_win,"YouTube",ids["YouTube"],youtube_win)
    print("Producer",device+"/events.log","Guest",guest+"/windows.log")


if __name__ == "__main__":
    main()
