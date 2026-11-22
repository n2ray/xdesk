# XDesk isolated UID probe

Side-by-side test APK for arm64 Android. The normal process owns a private random marker and binds a non-exported service with `android:isolatedProcess="true"`. Binder passes a read-only `ParcelFileDescriptor`. Both processes run the same XDesk `libburrow.so` and an unmodified Ubuntu 26.04 arm64 executable, either static BusyBox or dynamically linked dash. Burrow's root is the APK's extracted native library directory. The guest sees explicit bindings for its executable, the test pathname and its SELinux label. The marker fd becomes stdin across spawn/exec. The build verifies the SHA-256 of each Ubuntu input package before extracting its ELF files.

## Build and run

Set `JAVA_HOME` to JDK 21, `ANDROID_SDK_ROOT` to an SDK with platform 35 and build-tools 35.0.0, and `ANDROID_NDK_ROOT` to the NDK (tested with r29). The build also uses `ar`, `bsdtar` with zstd support, `zip` and `shasum`. From this directory:

```sh
out=$(mktemp -d)
curl -fL https://ports.ubuntu.com/ubuntu-ports/pool/main/b/busybox/busybox-static_1.37.0-7ubuntu1_arm64.deb -o "$out/busybox.deb"
bash build.sh "$out" "$out/busybox.deb"
adb install --no-incremental -r "$out/probe.apk"
activity=io.github.n2ray.xdesk.isolationprobe/io.github.n2ray.xdesk.probe.ProbeActivity
adb shell am start -n "$activity"
adb logcat -d -s XDeskFdProbe:I
```

To run the dynamic variant, build with these two Resolute arm64 packages and repeat the install and activity commands above.

```sh
curl -fL https://ports.ubuntu.com/ubuntu-ports/pool/main/d/dash/dash_0.5.12-12ubuntu3_arm64.deb -o "$out/dash.deb"
curl -fL https://ports.ubuntu.com/ubuntu-ports/pool/main/g/glibc/libc6_2.43-2ubuntu2.7_arm64.deb -o "$out/libc6.deb"
bash build.sh "$out" "$out/dash.deb" "$out/libc6.deb"
```

Burrow executes `/dash -c`. Its ELF interpreter is `/lib/ld-linux-aarch64.so.1`. The guest sees the loader and libc under `/lib`, with `LD_LIBRARY_PATH=/lib`. The installed native library files contain the original package bytes. All guest observations use dash builtins so that the guest reads its own mappings. Each run prints the device and inode of the three installed files and the executable rows of the guest's `/proc/self/maps`. Both PASS checks require all three file identities to match an executable mapping.

Reuse the output directory and its local test key when reinstalling over the same experiment package. The APK requests no permissions. Starting the activity requires the caller to hold Android's DUMP permission (available to adb shell). Check the current invocation's timestamp and `PASS local marker`. The owner must be able to reopen its own file by path. The service must have a different UID, read the exact token through a read-only fd for the same device and inode, and see a pathname error at both the Android and guest layers. The guest's reported SELinux label must also be isolated. A hidden file can yield ENOENT and an inaccessible one EACCES.

## Additional check against the installed XDesk package

Generate a fresh UUID. Run `python3 release-owner.py UUID` **inside the installed XDesk built-in Ubuntu**, using its existing test script entrypoint or a terminal with the script available. When the owner prints `READY`, start the experiment within the 12-second window:

```sh
adb shell am start -n "$activity" --es release_marker "$run_uuid"
adb logcat -d -v epoch -s XDeskFdProbe:I
```

Require `PASS release private path UUID` between the owner's `READY` and `OWNER_RECHECK_OK`. Both owner records must show the same inode and UUID. The owner keeps its 0600 file open, verifies the random content by reopening the pathname before and after the check, and unlinks it. In this case the isolated child is passed a separate read-only marker from the experimental APK while trying to open the real XDesk file by pathname. No data from the real XDesk marker is sent across applications.

## XDesk Ubuntu read-only rootfs

This mode takes the complete XDesk `xdesk-ubuntu2604-arm64.tar.gz` used by the phone installer. The build pins its SHA-256 (`b2efc37b009681c24eb128c103264c808559ab8e3c3af8ea4adf4f62fcf3068e`). Set `BURROW_SRC` to a checkout containing commit `8705740dc78e472d15a80c99f0e785c029b1e1d8`. Only a temporary copy is patched. The build also uses Python 3, git, make and patch.

```sh
adb pull /sdcard/Android/data/io.github.n2ray.xdesk.x11/files/xdesk-ubuntu2604-arm64.tar.gz "$out/rootfs.tar.gz"
BURROW_SRC=/path/to/burrow bash build.sh "$out" --rootfs "$out/rootfs.tar.gz"
adb install --no-incremental -r "$out/probe.apk"
adb shell am start -n "$activity"
```

The signed APK contains every regular file from the archive as a separately installed object. Its compiled path index records the archive's directories and symlink targets. The experimental Burrow build resolves guest paths against this index, then opens the corresponding installer-owned file. Burrow starts `/bin/dash`; the interpreter and multiarch libraries are resolved from the image. The runner supplies no individual ELF bindings or library search override in this mode.

Directory objects contain aligned Linux directory records for the archive's immediate children, including `.` and `..`. Each open has its own kernel file offset, shared by duplicates of that fd. A directory fd identifies its indexed guest directory. Burrow records the current guest directory across exec, and resolves relative paths and symlink targets from their respective parents.

Both PASS checks require dash to list `/etc/xdesk`, read `image` relative to that directory, inspect and follow the relative `/etc/os-release` and absolute `/etc/localtime` links, and enumerate all 671 entries under `/bin` (a link to `/usr/bin`). An independently executed `pwd -P` checks the physical working directory. The probe also checks the XDesk image ID, Ubuntu version, fd contents, and rejection of append and creation in the image. Run the release package control above to check the real XDesk private path.

The results apply to the pinned image, the tested filesystem operations and read-only installed objects. The projection does not reproduce all POSIX ownership and permission metadata. Updating the image requires rebuilding and reinstalling the experiment APK.

## Unix socket and process identity

The rootfs build also runs the image's `/usr/bin/python3` in two separate Burrow processes. The ordinary side runs under the experimental Android app UID. The second runs under its non-exported isolated service. The app creates an AF_UNIX SOCK_SEQPACKET socketpair, enables SO_PASSCRED on both endpoints, and grants one connected endpoint to the isolated service via Binder. Only the ordinary guest opens the random marker file. The isolated guest receives its descriptor in SCM_RIGHTS, checks O_RDONLY, attempts a write, and returns the marker content with the challenge response.

The PASS line requires each guest's SCM_CREDENTIALS sender PID and UID to match the other guest as measured on a separate native Android credential socket. SO_PEERCRED on the inherited socketpair is logged separately. Each launcher must successfully wait for its Burrow supervisor, see ECHILD on a second wait, and see ESRCH for its guest PID. With the release UUID supplied above, the isolated socket participant also tries to open the real XDesk marker by pathname while the release app holds it. Look for `PASS Unix socket` and `PASS release private path` in the same owner window.

## Sandboxed zygote startup records

With the rootfs APK installed, run the dedicated handshake fixture:

```sh
adb shell am start -n "$activity" --ez zygote_handshake true
adb logcat -d -v epoch -s XDeskFdProbe:I
```

Check the current run for `PASS zygote handshake match=1 rejected=3`. Each case uses fresh unnamed socketpairs. An isolated Ubuntu boot process sends the first Chromium 158 startup record and forks a child to send the second. The ordinary Ubuntu receiver checks the two fixed records in order, including their terminating NUL, their kernel sender credentials, and the absence of file descriptors. The control channel carries only those two packets.

On a separate registration channel the Android launcher obtains the boot and child PIDs from kernel credentials and checks their UID against the isolated service's UID. Both processes stay alive until the decision. The fixture supplies these expectations to the receiver before it processes the control channel. Negative cases change only the launch expectations. The receiver must exit 23 at the first mismatch; only an accepted handshake returns zero. This additional PID/UID policy belongs to XDesk. Upstream Chromium checks the sender PIDs and records the final PID. The fixture covers startup messaging and leaves Chromium, namespace setup, the renderer and Mojo outside the run.

## Renderer fork and reap

With the same Ubuntu rootfs APK, run the fork fixture and read the new log entries.

```sh
adb shell am start -n "$activity" --ez renderer_fork true
adb logcat -d -v epoch -s XDeskFdProbe:I
```

Look for `PASS renderer fork android_registered=<pid>:<uid>`. After BOOT and HELLO, the ordinary Ubuntu process sends the status query and a Chromium 158 fork request over the control socket. Descriptor zero in the request is the PID oracle endpoint. The other descriptor is the read end of a pipe containing 16 random bytes. The fixed child reports on the oracle, reads back its global PID through its parent's pipe, and records the marker. Android compares the two marker values and independently checks the child's kernel credentials. The child logs Burrow’s guest-visible UID separately from the Android UID reported by the kernel. The isolated zygote handles the one-way Reap request and logs its actual wait result and a second wait returning ECHILD.

The fixture accepts one fixed renderer argument list, one experiment descriptor key, and the empty UMA reply. The raw sandbox-status word is zero. All sockets belong to this test. It exercises the no-helper fork protocol with Ubuntu Python and does not launch a Chrome renderer or initialize Mojo.
