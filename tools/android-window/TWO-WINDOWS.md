# Chrome and YouTube window prototype

The phone runs the signed Android-frame experimental APK based on XDesk 0.8.0. Start its built-in Ubuntu desktop on the external display with xfwm4 running. The guest needs Python 3 and its existing X11, XCB and DRI3 libraries. The prototype uses the APK's shell-only frame provider, which the stock 0.8.0 APK does not contain.

Build the standalone shell program on the host with a JDK, Android SDK platform 35 and build-tools 35.0.0. From the repository root:

```sh
tools/android-window/build-two.sh /tmp/xdesk-two-build
python3 tools/android-window/start-two.py /tmp/xdesk-two-build/two.jar --serial DEVICE_SERIAL
```

Set ANDROID_HOME to the SDK directory first. The launcher uses an already-authorized ADB connection, the built-in Linux debug receiver, and the guest's xdesk user. The test opens Chrome on WebGL Aquarium and YouTube on its home activity. Pass `--rednote` to also open Xiaohongshu (com.xingin.xhs). Each window has its own virtual display. Drag a border and pause briefly to let the guest report the settled client size. Android resizes the existing display and replaces its ImageReader surface. The shell producer waits for the acquire fence, sends one handle at a time to the existing direct-import provider, and recycles the image after the guest acknowledges the GPU copy.

Closing a window releases its virtual display. Closing all windows ends the guest and producer. A short left click inside a client window sends a touchscreen tap to that window’s virtual display. Buttons on the xfwm4 title bar continue to be handled by the window manager. Keyboard, scrolling, long press and dragging inside applications are outside this prototype.
