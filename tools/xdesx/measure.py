"""Measure the physical phone across one steady workload. CPU is percent of a single core."""
import argparse
import json
import shlex
import statistics
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
parser.add_argument("--seconds", type=int, default=60)
parser.add_argument("--label", required=True)
parser.add_argument("--layer", help="exact SurfaceFlinger layer name for the external X surface")
args = parser.parse_args()
if args.seconds < 10:
    parser.error("sample at least 10 seconds")

def shell(cmd):
    return subprocess.check_output(["adb", "-s", args.serial, "shell", cmd], text=True, timeout=15)

def cpu():
    result = {}
    for line in shell("ps -A -w -o PID,NAME,TIME+").splitlines()[1:]:
        fields = line.split()
        if len(fields) != 3:
            continue
        pid, name, value = fields
        parts = value.split(":")
        total = 0
        for part in parts:
            total = 60 * total + float(part)
        result[(pid,name)] = total
    return result

def battery():
    prefix = "/data/data/com.termux/files/usr"
    cmd = f"run-as com.termux env PATH={prefix}/bin:/system/bin PREFIX={prefix} HOME=/data/data/com.termux/files/home timeout 4 termux-battery-status"
    return json.loads(shell(cmd))

def presents():
    if not args.layer:
        return set()
    rows = shell("dumpsys SurfaceFlinger --latency " + shlex.quote(args.layer)).splitlines()
    return {int(row.split()[1]) for row in rows if len(row.split()) == 3 and
            row.split()[1].isdigit() and 0 < int(row.split()[1]) < 2**63-1}

states = []
before = cpu()
initial = presents()
present = set()
start = time.monotonic()
deadline = start + args.seconds
battery_due = start
while True:
    if time.monotonic() >= battery_due:
        states.append(battery())
        battery_due += 10
    present.update(presents() - initial)
    remaining = deadline-time.monotonic()
    if remaining <= 0:
        break
    time.sleep(min(1.5 if args.layer else 10, remaining))
after = cpu()
duration = time.monotonic()-start
tracked = {"xdesx-x11", "xfwm4", "xfce4-panel", "surfaceflinger", "com.android.chrome",
           "com.google.android.youtube", "io.github.n2ray.xdesk.x11", "client"}
samples = {}
for (pid, name), value in after.items():
    if (pid, name) in before and name in tracked:
        samples[name] = samples.get(name, 0) + 100 * (value-before[(pid,name)])/duration
samples = {name: round(value,2) for name,value in samples.items()}
out = dict(label=args.label, seconds=round(duration,2), cpu_one_core_pct=samples, battery=states,
           burrow_seen=any("burrow" in name.lower() for _, name in set(before)|set(after)))
if all(s["plugged"]=="UNPLUGGED" and s["status"]=="DISCHARGING" for s in states):
    out["mean_battery_watts"]=round(statistics.mean(abs(s["current"])*s["voltage"]/1e9 for s in states),3)
if args.layer:
    times=sorted(present)
    intervals=sorted((b-a)/1e6 for a,b in zip(times,times[1:]))
    out["surface_presents"]=len(times)
    if intervals:
        out["present_interval_ms"]={"median":round(statistics.median(intervals),2),
                                    "p95":round(intervals[int(.95*(len(intervals)-1))],2)}
print(json.dumps(out,indent=2))
