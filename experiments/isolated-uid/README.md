# XDesk isolated UID probe

Side-by-side test APK for arm64 Android. The normal process owns a private random marker and binds a non-exported service with `android:isolatedProcess="true"`. Binder passes a read-only `ParcelFileDescriptor`. Both processes run the same XDesk `libburrow.so` and unmodified Ubuntu 26.04 arm64 BusyBox. Burrow's root is the APK's extracted native library directory. The guest sees explicit bindings for its executable, the test pathname and its SELinux label. The marker fd becomes stdin across spawn/exec. The code hashes and pins the Ubuntu input package during the build.

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

Reuse the output directory and its local test key when reinstalling over the same experiment package. The APK requests no permissions. Starting the activity requires the caller to hold Android's DUMP permission (available to adb shell). Check the current invocation's timestamp and `PASS local marker`. The owner must be able to reopen its own file by path. The service must have a different UID, read the exact token through a read-only fd for the same device and inode, and see a pathname error at both the Android and guest layers. The guest's reported SELinux label must also be isolated. A hidden file can yield ENOENT and an inaccessible one EACCES.

## Additional check against the installed XDesk package

Generate a fresh UUID. Run `python3 release-owner.py UUID` **inside the installed XDesk built-in Ubuntu**, using its existing test script entrypoint or a terminal with the script available. When the owner prints `READY`, start the experiment within the 12-second window:

```sh
adb shell am start -n "$activity" --es release_marker "$run_uuid"
adb logcat -d -v epoch -s XDeskFdProbe:I
```

Require `PASS release private path UUID` between the owner's `READY` and `OWNER_RECHECK_OK`. Both owner records must show the same inode and UUID. The owner keeps its 0600 file open, verifies the random content by reopening the pathname before and after the check, and unlinks it. In this case the isolated child is passed a separate read-only marker from the experimental APK while trying to open the real XDesk file by pathname. No data from the real XDesk marker is sent across applications.

This tests one static Ubuntu ELF loaded from the installed APK. The test exercises neither a dynamically linked Ubuntu rootfs nor a browser.
