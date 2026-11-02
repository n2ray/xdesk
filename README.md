# XDesk

**A full Linux desktop on an external display, from an Android phone, with no root.** Plug in a monitor, open XDesk, and XFCE appears on the big screen while the phone becomes a touchpad and keyboard.

[中文说明](README.zh-CN.md) · Based on [Termux:X11](https://github.com/termux/termux-x11) ([upstream README](README.upstream.md)) · GPL-3.0

> **Status:** a personal project that works daily on one device, a Xiaomi 15 Ultra (Snapdragon 8 Elite, HyperOS 3, Android 16, locked bootloader). It has not been tried anywhere else. Other phones may behave differently, especially the GPU settings in `xdesk/startx.sh`. The UI is Chinese only for now.

## What you get

- XFCE on the external display, GPU accelerated through Mesa (zink + Turnip on Adreno)
- Phone as touchpad: move, tap, two-finger right click and scroll, long press or tap-and-hold to drag
- Soft keyboard with IME text input including Chinese, and a shortcut row (Esc, Tab, Ctrl, Alt, Super, Shift, arrows)
- Physical mouse and keyboard, forwarded to the desktop
- Audio output you can switch: phone speaker, HDMI, or system default
- Resolution presets, sensitivity, portrait or landscape touch screen, dim the phone when idle
- Survives unplugging the display, switching apps and locking the phone. If the system kills the X server, the desktop restarts by itself, but unsaved work in it is lost

No root, no `adb` in daily use. It installs next to the official Termux:X11 and does not touch it.

## How it works

```
 Phone screen                        External display
┌───────────────────────┐           ┌───────────────────────┐
│ XDeskActivity         │  direct   │ MainActivity (upstream)│
│ touchpad, keys, audio ├──calls───►│ LorieView shows XFCE   │
└───────────┬───────────┘           └───────────▲───────────┘
            │ RUN_COMMAND                       │ X11
┌───────────▼───────────────────────────────────┴───────────┐
│ Termux: x11-session.sh → X server, XFCE, PulseAudio       │
└───────────────────────────────────────────────────────────┘
```

- **One app, two windows.** The upstream `MainActivity` runs unchanged on the external display. `XDeskActivity` runs on the phone in the same process and calls `LorieView.sendMouseEvent`, `sendKeyEvent` and `sendTextEvent` directly.
- **X and XFCE stay in Termux**, started through Termux's `RUN_COMMAND`, using a loader built for this app id.
- **Audio** goes from a PulseAudio null sink to a local TCP port. The app plays it with `AudioTrack`, which is how it can pick the output device without special permissions. The port is bound to localhost but has no authentication, so another app on the phone could read the desktop audio.

Almost all changes live in `lorie-app/`, so merging upstream stays simple.

## Requirements

- An Android phone with **video output over USB-C** (DisplayPort Alt Mode). If plugging in a monitor mirrors the screen, it qualifies
- USB-C to HDMI cable or dock
- Android 11 or newer (only tested on Android 16)
- [Termux](https://github.com/termux/termux-app) with storage access

## Install

### 1. Termux packages

```sh
termux-setup-storage
pkg upgrade
pkg install x11-repo
pkg install xfce4 xfce4-terminal pulseaudio mesa vulkan-loader mesa-vulkan-icd-freedreno xorg-xrandr dbus util-linux git
```

`mesa-vulkan-icd-freedreno` is for Adreno GPUs. On other GPUs, replace it and adjust the variables in `xdesk/startx.sh`.

### 2. Get the build

Download both artifacts from the same run on the [Actions page](https://github.com/n2ray/xdesk/actions/workflows/debug_build.yml) (requires a GitHub login):

- `termux-x11-universal-debug`: the APK
- `termux-companion packages`: contains `termux-x11-nightly-*.deb`, which holds the loader

Or fork this repository and let Actions build it for you. Local builds need the Android SDK and NDK:

```sh
git clone --recursive https://github.com/n2ray/xdesk
cd xdesk && ./gradlew assembleDebug
```

> The APK is signed with the public test key from this repository. That is fine for trying it out, but anyone can sign an update with the same key.

### 3. Install the Termux side

```sh
git clone https://github.com/n2ray/xdesk ~/xdesk
bash ~/xdesk/xdesk/install.sh /path/to/termux-x11-nightly-*.deb
```

This copies the scripts to `/sdcard/Termux/xdesk`, installs the loader to `~/.local/libexec/xdesk/loader.apk` and sets `allow-external-apps = true`. That setting lets apps run commands in Termux, but each app also needs the permission in step 5.

### 4. Install the APK

```sh
adb install -r termux-x11-universal-debug.apk
```

On our phone the file manager refused it with a generic "app not installed", while `adb install` worked. On Xiaomi phones, turn on **USB install** in Developer options first.

### 5. Run it

1. Plug in the display
2. Open **XDesk** and allow it to **run Termux commands** and post notifications
3. After a few seconds, XFCE appears on the display

End the desktop from **Settings → End desktop**. Unsaved work is lost.

## Use your own XFCE setup

If `/sdcard/Termux/startx.sh` exists, XDesk uses it instead of the bundled copy. It rewrites two lines on the fly, so these must stay as they are:

```sh
termux-x11 :1 -ac -xstartup "dbus-run-session startxfce4" &
am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity
```

## Troubleshooting

Logs are in `$PREFIX/tmp/xdesk.log` and `$PREFIX/tmp/xfce_startup.log`.

| Symptom | Likely cause |
|---|---|
| Stuck on "starting", then "desktop did not start" | `allow-external-apps` is off, the permission was denied, or the loader is missing. Check the log |
| External screen goes black after a few minutes | XFCE's screensaver. Move the mouse, or raise `/saver/idle-activation/delay` in the `xfce4-screensaver` xfconf channel |
| Black bars on the display | Android may report an aspect ratio that differs from the monitor's. XDesk renders at the reported ratio to fill the screen |
| Phone's own cursor appears | Mouse capture was lost. Tap the XDesk touch screen |
| No sound | Check the output in the top bar. Many monitors have no speakers |
| Ctrl + letter does nothing on the soft keyboard | Pinyin mode keeps the letter in the IME. Switch to the English layout or use a physical keyboard |
| `X server stalled` | Comes from upstream's watchdog. Do not recreate the window with `FLAG_ACTIVITY_CLEAR_TASK` |

## Limitations

- Linux apps only. Android apps can be placed on the display but not controlled, because the system keeps the Android mouse on the phone screen and regular apps cannot inject input into others
- Termux and XDesk must both stay alive. Aggressive system cleaners can kill them
- XDesk and the official Termux:X11 share one XFCE config and display `:1`, so do not run both at once
- Tested on a single phone

## Building on this

The `xdesk` branch is the default branch. `master` tracks upstream.

```sh
git remote add upstream https://github.com/termux/termux-x11
git fetch upstream && git merge upstream/master
```

Upstream's manifest, package names under `com.termux.x11` and `LorieView` API are what XDesk relies on. The app id is set in `lorie-app/build.gradle` and `shell-loader/build.gradle`, and they must match.

## License

GPL-3.0, same as Termux:X11. All credit for the X server and rendering goes to the [Termux:X11 authors](https://github.com/termux/termux-x11).
