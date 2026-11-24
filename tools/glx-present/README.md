# Shared presentation for a GLX window

This opt-in prototype presents a real Ubuntu GLX window on an Android child SurfaceControl. It works without root on the tested Xiaomi 15 Ultra. It lives outside the shipped APK and xdesk-core package while the desktop integration is being developed.

Android allocates three linear RGBA hardware buffers. The Mesa patch imports their dma-bufs into Zink and binds the current slot as the window's default back buffer. On GLX swap it flushes rendering and sends the GPU completion fence over a private Unix socket. SurfaceFlinger consumes the buffer with that fence. The host waits for the corresponding release fence before reusing the slot. The comparison path maps a private render target and transfers all its rows to the Android buffer.

Rotating the Gallium resource also requires invalidating the DRI drawable, validating the state tracker's framebuffer attachments and marking framebuffer state dirty. Without the rebind the compositor continued presenting on time while two of the three slots could contain black. The guest now checks red, green and blue pixels in each slot twice at startup.

## Internal-display test, 2026-11-24

Ubuntu's distribution glxgears (mesa-utils 9.0.0-2build1), xdesk-core 7, Adreno 830v1, Zink on Turnip, Mesa 27.0.0-devel, sysmem. The base app was 0.8.0-beta.1 (800), SHA-256 `2d2a3d167891b40dda09a2ed7c3e6b3ca73475a9c5236bd0b67d6f197d0aaeee`. Each phase ran for ten seconds, paced to 60 Hz. The real X window and its back buffer had the requested resolution. Android scaled it to 960 pixels wide on the internal display. The large test window set override-redirect before mapping to avoid the virtual desktop's size limit.

| GLX window | Route | Submitted / distinct present timestamps | Steady fps | p95 interval |
| --- | --- | ---: | ---: | ---: |
| 640×480 | CPU copy | 579 / 579 | 60.00 | 16.65 ms |
| 640×480 | Shared buffer | 594 / 593 | 60.01 | 16.65 ms |
| 2560×1600 | CPU copy | 591 / 591 | 59.78 | 24.97 ms |
| 2560×1600 | Shared buffer | 596 / 596 | 60.01 | 16.65 ms |

Rates use distinct successful SurfaceFlinger present-fence timestamps, omitting the first second and last 0.3 seconds. The 12 timing-run screenshots contained all three gears. The p95 difference at the larger resolution describes frame pacing under this workload. This experiment does not quantify the fish-tank workload, desktop composition or sustained thermal performance. See [timing results](inner-display.json).

## Build and reproduce

The **GLX shared presentation prototype** workflow builds both unsigned components from source. For a local build use Android NDK r29 with build-tools 35.0.0 and platform 35, and arm64 Ubuntu 26.04 with the build dependencies listed in the workflow.

1. Run `tools/glx-present/build-display.sh OUT VERSION_CODE` with the installed standalone XDesk base version code. Sign its unsigned APK with the identity that signed that base. The diagnostic Activity requires the Android DUMP permission and runs in its own process.
2. Add the signed split to the existing package using an inherit session. Record the SHA-256 of the installed base APK before installation and verify it afterwards. These commands assume the standalone package is the one under test.

   ```sh
   adb push signed.apk /data/local/tmp/xdesk-glx-signed.apk
   adb shell pm install-create -r -p io.github.n2ray.xdesk.x11
   # Use the session ID printed by install-create
   adb shell pm install-write SESSION xdesk_glx_diag /data/local/tmp/xdesk-glx-signed.apk
   adb shell pm install-commit SESSION
   adb shell rm /data/local/tmp/xdesk-glx-signed.apk
   ```

3. Run `tools/glx-present/build-guest.sh OUT` on the arm64 builder and copy both output libraries into a private test directory inside Ubuntu. With the desktop running, run `python3 run-guest.py /path/to/test-directory` as the desktop user with its usual DISPLAY, X authority and GPU environment. The launcher scopes the diagnostic driver to four disposable glxgears processes.
4. From the host run `adb shell am start -W --display 0 -n io.github.n2ray.xdesk.x11/android.app.NativeActivity`. Capture fresh logcat for the Activity PID with the tag `Damage3D`. A successful run ends with `PASS GLX` in Ubuntu and `PASS` in the Android log. Verify all the `KOPPER_PIXELS` lines and sample the displayed image. Each `tick` contains the phase/sequence, latch time and actual present-fence timestamp in nanoseconds.
5. To remove the split use a new `pm install-create -r -p io.github.n2ray.xdesk.x11` session, then `pm install-remove SESSION xdesk_glx_diag` and `pm install-commit SESSION`. Verify `pm path` and the original base hash, and remove the private guest test directory.

## Integration scope

The receiver uses Android 16 per-buffer release callbacks and a device-specific, checked linear dma-buf layout. The sender deliberately terminates its disposable client when the fixed test sequence ends. These choices are restricted to the separate diagnostic processes. To enable this for ordinary desktop windows, the X server needs to select eligible windows, track stacking/occlusion, resize and unmap events, and switch back to ordinary composited presentation on import failure or loss of eligibility. Chrome, video and mixed-window workloads still need end-to-end validation on the external display.
