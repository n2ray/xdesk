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

Both variants load code from the installed APK directory. The dynamic variant exercises the interpreter and libc of one ELF. The full Ubuntu rootfs and browser are outside this experiment.
