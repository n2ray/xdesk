# XDesk

**Any Linux desktop on an external display, from an Android phone, with no root.** XDesk turns the monitor into an X display and the phone into its touchpad and keyboard. What runs on it is up to you: XFCE in Termux, a desktop in proot or chroot, or a single program.

[中文说明](README.zh-CN.md) · Based on [Termux:X11](https://github.com/termux/termux-x11) ([upstream README](README.upstream.md)) · GPL-3.0

> **Status:** an early personal project that works daily on one device, the European (EEA) Xiaomi 15 Ultra (model 25010PN30G, Snapdragon 8 Elite, HyperOS 3, Android 16, locked bootloader). It has not been tried anywhere else, including the Chinese version of the same phone. Other phones and ROMs may behave differently. The app's UI is Chinese only for now. Reports from other devices are very welcome.

## Install

In [Termux](https://github.com/termux/termux-app):

```sh
curl -fsSL https://raw.githubusercontent.com/n2ray/xdesk/xdesk/xdesk/install.sh | bash
```

It installs the `xdesk` command and its loader, looks for desktops you already have, and downloads the app. Then:

1. Install the app it downloaded. If your file manager reports "app not installed", use `adb install -r xdesk.apk`. On Xiaomi phones, first turn on **USB install** in Developer options.
2. Plug in a display and open **XDesk**.
3. Run `xdesk start`.

No desktop yet? For example: `pkg install xfce4 xfce4-terminal && xdesk setup`.

> Please read scripts before piping them to a shell: [install.sh](xdesk/install.sh). The APK is signed with the public test key from upstream, so it is fine for trying out, but anyone can sign an "update" with the same key.

## Requirements

- An Android phone with **video output over USB-C** (DisplayPort Alt Mode). If plugging in a monitor mirrors the screen, it qualifies
- USB-C to HDMI cable or dock
- Android 11 or newer (only tested on Android 16)
- Termux

## Use it with any environment

XDesk only provides the display. Anything that can reach its X socket can show up on it.

```sh
xdesk x                 # start only the X server and audio
xdesk run CMD...        # run any program on the display
xdesk setup             # look for desktops in Termux and proot-distro
xdesk list              # list sessions, * marks the default
xdesk use NAME          # choose the default session
xdesk start [NAME]      # start a session
xdesk switch NAME       # stop the current session and start another, the window stays
xdesk add NAME 'CMD'    # add your own session
xdesk rm NAME           # remove a session
xdesk status            # what is running
xdesk stop              # stop everything
```

A session is one command, stored in `~/.config/xdesk/sessions/NAME.sh`. Edit these files freely, `xdesk setup` will not overwrite them.

| Environment | How to connect |
|---|---|
| Termux | Found by `xdesk setup`, or `xdesk run xterm` |
| proot-distro | Found by `xdesk setup`. By hand: `xdesk add box "proot-distro login ubuntu --shared-tmp -- env DISPLAY=:1 PULSE_SERVER=127.0.0.1 dbus-launch --exit-with-session startxfce4"` |
| Plain proot | Bind `$PREFIX/tmp` to `/tmp` and set `DISPLAY=:1` |
| chroot (root) | Bind `$PREFIX/tmp/.X11-unix` to `/tmp/.X11-unix` in the chroot, set `DISPLAY=:1`. Untested so far |

GPU acceleration is only set up for Termux on Adreno (zink + Turnip). Inside proot or chroot, expect software rendering unless you configure Mesa there yourself. That is fine for debugging, not for games.

Audio from proot and chroot reaches the app through PulseAudio on `127.0.0.1`.

### Starting from the app

XDesk waits for a desktop by default and needs no Termux permission. To use its **start** button or the auto-start setting:

```sh
xdesk enable-app-start
```

This sets `allow-external-apps = true` in Termux, so apps can ask to run commands there. Each app also has to be granted the permission, and XDesk asks for it when you press the button.

## What you get

- Phone as touchpad: move, tap, two-finger right click and scroll, long press or tap-and-hold to drag
- Soft keyboard with IME text input including Chinese, and a shortcut row (Esc, Tab, Ctrl, Alt, Super, Shift, arrows)
- Physical mouse and keyboard, forwarded to the desktop
- Audio output you can switch: phone speaker, HDMI, or system default
- Resolution presets, sensitivity, portrait or landscape touch screen, dim the phone when idle
- Survives unplugging the display, switching apps and locking the phone

It installs next to the official Termux:X11 and does not touch it. Do not run both at once, since both use display `:1`.

## How it works

```
 Phone screen                        External display
┌───────────────────────┐           ┌───────────────────────┐
│ XDeskActivity         │  direct   │ MainActivity (upstream)│
│ touchpad, keys, audio ├──calls───►│ LorieView shows X      │
└───────────┬───────────┘           └───────────▲───────────┘
            │ local port (audio)                │ X11 socket
┌───────────▼───────────────────────────────────┴───────────┐
│ xdesk: X server, PulseAudio                               │
│ Termux, proot, chroot: your desktop or program            │
└───────────────────────────────────────────────────────────┘
```

- **One app, two windows.** The upstream `MainActivity` runs unchanged on the external display. `XDeskActivity` runs on the phone in the same process and calls `LorieView.sendMouseEvent`, `sendKeyEvent` and `sendTextEvent` directly.
- **The X server runs in Termux**, started with a loader built for this app id.
- **Audio** goes from a PulseAudio null sink to port 47130. The app plays it with `AudioTrack`, which is how it can pick the output device without special permissions. The port is bound to localhost but has no authentication, so another app on the phone could read the desktop audio.

Almost all changes live in `lorie-app/` and `xdesk/`, so merging upstream stays simple.

## Troubleshooting

Logs are in `$PREFIX/tmp/xdesk.log`.

| Symptom | Likely cause |
|---|---|
| Waiting forever | Nothing is running yet. Run `xdesk start`, and check `xdesk status` |
| "Display :1 is used by another X server" | The official Termux:X11 is running. Stop it, for example with `pkill -f termux-x11` |
| App's start button fails | Run `xdesk enable-app-start` and check the permission |
| Desktop does not fit the screen | It started before the app connected. Open the app first, then restart the session |
| External screen goes black after a few minutes | XFCE's screensaver. Move the mouse, or raise `/saver/idle-activation/delay` in the `xfce4-screensaver` xfconf channel |
| Phone's own cursor appears | Mouse capture was lost. Tap the XDesk touch screen |
| No sound | Check the output in the top bar. Many monitors have no speakers |
| Ctrl + letter does nothing on the soft keyboard | Pinyin mode keeps the letter in the IME. Switch to the English layout or use a physical keyboard |

## Limitations

- Linux programs only. Android apps can be placed on the display but not controlled, because the system keeps the Android mouse on the phone screen and regular apps cannot inject input into others
- Termux and XDesk must both stay alive. Aggressive system cleaners can kill them
- Tested on a single phone, the European Xiaomi 15 Ultra

## Building

The default branch is `xdesk`, `master` tracks upstream. Pushes build on GitHub Actions, and tags like `v0.1.0` publish a release.

```sh
git clone --recursive https://github.com/n2ray/xdesk
cd xdesk && ./gradlew assembleDebug      # needs the Android SDK and NDK
```

To merge upstream:

```sh
git remote add upstream https://github.com/termux/termux-x11
git fetch upstream && git merge upstream/master
```

XDesk relies on upstream's manifest, class names under `com.termux.x11` and the `LorieView` API. The app id is set in `lorie-app/build.gradle` and `shell-loader/build.gradle`, and they must match.

## License

GPL-3.0, same as Termux:X11. All credit for the X server and rendering goes to the [Termux:X11 authors](https://github.com/termux/termux-x11).
