"""Check Chrome 158's filesystem postcondition with an isolated Ubuntu process."""
import os
import sys

os.setgroups([])
os.setresgid(1000, 1000, 1000)
os.setresuid(1000, 1000, 1000)
with open("/proc/self/status", encoding="ascii") as status:
    for line in status:
        if line.startswith(("Pid:", "Uid:")):
            print(line.strip(), flush=True)

# Control: the known interpreter must be readable and identify as ELF.
with open(sys.executable, "rb") as executable:
    if executable.read(4) != b"\x7fELF":
        raise RuntimeError("unexpected executable")
    try:
        fd = os.open("/proc/self/exe", os.O_RDONLY)
    except OSError as error:
        print(f"open_self_exe=denied errno={error.errno}")
    else:
        with os.fdopen(fd, "rb") as proc_exe:
            same_file = os.path.samestat(os.fstat(proc_exe.fileno()), os.fstat(executable.fileno()))
            print(f"open_self_exe=ok magic={proc_exe.read(4).hex()} same_inode={int(same_file)}")
