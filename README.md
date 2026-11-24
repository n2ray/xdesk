# XDesk

<p align="center"><img src="docs/img/hero.svg" width="760" alt="A monitor showing XDesk's Launchpad, the phone beside it as a touchpad, a keyboard and mouse in front"></p>

**Your phone as a computer on any screen.** One app puts three things on a monitor, projector, tablet or car screen: your **Android apps**, a **built-in Ubuntu** desktop, and your **Mac**. XDesk is their display, keyboard and mouse; the phone becomes the touchpad, keyboard, gamepad or launcher. No root, and nothing else to install.

**[Website](https://n2ray.github.io/xdesk/)** · [Download](https://github.com/n2ray/xdesk/releases/latest) · [中文](README.zh-CN.md) · [Manual](MANUAL.md) · GPL-3.0

## What it does

Not every phone has DeX or a desktop mode, and not every screen can take a cable. XDesk fills that gap, with three kinds of things to run there:

| On the big screen | Needs | |
|---|---|---|
| **Android apps**, full screen | Nothing else: XDesk's own input service, paired once in wireless debugging (or Shizuku) | Your phone's apps, games and cloud gaming |
| **Built-in Linux**: Ubuntu 26.04 with XFCE | Nothing else: XDesk runs it, a one-time download of about 470 MB | apt, GPU acceleration on Snapdragon, Chrome with hardware video |
| **Your Mac** | macOS's own Screen Sharing (Apple silicon, macOS 14+), or [Sill](https://github.com/Saffsanity/sill) on the Mac | The Mac's desktop with sound, HEVC or H.264, at your screen's size |

And as many screens:

| Use | Connection | The phone is |
|---|---|---|
| **A computer**: monitor + keyboard and mouse | USB-C video out, just plug in | Launcher or touchpad; keyboard and mouse go straight through |
| **Alongside your Mac** | The XDesk Mac app, USB or Wi-Fi | The phone in a Mac window; clipboard and files go both ways |
| **Projector nights**: films and games | XDesk Receiver on the projector or TV box, Wi-Fi | A gamepad games take as a real one, or a remote |
| **Phone apps on a tablet** | XDesk Receiver on the tablet, Wi-Fi | Touch the tablet directly, many fingers at once |
| **The car**, while parked | Android Auto only (no CarPlay), parked only | Touchpad and keyboard |

More ways to use it:

| Use | How | Tried |
|---|---|---|
| **Cloud gaming**: GeForce NOW and others | Open the app on the big screen and connect a gamepad; for keyboard-and-mouse games press Ctrl+Alt+G, and the game gets the real keyboard and mouse: mouse look and WASD as on a PC | GeForce NOW: Bluetooth gamepad and keyboard and mouse both smooth, on a USB-C monitor and on a projector receiver. Steam Link and Moonlight should work too, not tried yet |
| **The phone as a gamepad** | With an Android app on the big screen, tap **Gamepad**: the phone turns sideways into a gamepad, with a layout you can edit | Games see a wired Xbox 360 controller, GeForce NOW included |
| **Remote desktop**: RustDesk | Open RustDesk on the big screen and drive the remote computer with keyboard and mouse or the phone's touchpad | Drags, side buttons and the cursor are handled specially |
| **Mac windows on the big screen** | Install the original [Sill](https://github.com/Saffsanity/sill) on the Mac (no XDesk needed there); on the phone, Add device › Mac and enter the code Sill shows, or scan it. The Mac's desktop, windows and apps can then go on the phone's display, and a locked Mac can be unlocked | Types with the Mac's own input method; picture quality in one tap |
| **Linux in Termux** | For those who already use Termux, or want another distribution: proot or chroot desktops and programs, beside the built-in Linux | GPU accelerated on Snapdragon (Turnip, zink) |
| **Any browser as the display** | Settings › Web display, then open the address it shows on an iPad, laptop or smart TV | Nothing to install; USB tethering has lower delay than Wi-Fi |
| **Sound where you want it** | Phone speaker, HDMI, Bluetooth, USB or wired headphones, one setting for every mode | On a wireless receiver or web display the sound plays there and the phone is silent |

The big screen gets a Launchpad, a dock at the bottom and your notifications. On the phone, pick **Console** (touchpad first) or **Launchpad** (app grid first), in portrait or landscape.

## How to use it

**1. Install.** Download `xdesk.apk` from the [latest release](https://github.com/n2ray/xdesk/releases/latest). Android 11 or newer.

**2. Follow the checklist.** On first start XDesk lists what it needs and walks you through each: on Wi-Fi, turn on Wireless debugging and pair once, then allow the overlay. [Shizuku](https://shizuku.rikka.app/) works too. After a reboot, activate again.

**3. Connect a screen.**

| Screen | How |
|---|---|
| Monitor | Plug in over USB-C. If the phone mirrors to it, it works |
| Mac (Apple silicon) | Get [XDesk-macos-arm64.dmg](https://github.com/n2ray/xdesk/releases/latest/download/XDesk-macos-arm64.dmg) and [Sill](https://github.com/Saffsanity/sill) on the Mac; plug in, or pick the Mac on the phone |
| Projector, TV, tablet, old phone | Install [xdesk-receiver.apk](https://github.com/n2ray/xdesk/releases/latest/download/xdesk-receiver.apk) (Android 5+) and open it; on the phone, Settings › Connect to a receiver |
| Browser | On the phone, Settings › Web display; open the address it shows |
| Car | Open XDesk in Android Auto, parked |

**4. Optional: Linux.** On the phone, Devices › Add › Linux › **Built-in Linux**, then Install: Ubuntu with XFCE, no Termux needed. Already on Termux? Install [Termux](https://github.com/termux/termux-app), run this (please [read the script](xdesk/install.sh) first), then add Termux in XDesk under Devices › Add › Linux:

```sh
curl -fsSL https://raw.githubusercontent.com/n2ray/xdesk/xdesk/xdesk/install.sh | bash
```

**Shortcuts** (with a keyboard on the phone):

| Keys | Does |
|---|---|
| Ctrl+Alt+M | Move keyboard and mouse between the display and the phone |
| Ctrl+Alt+N | Open the notification centre |
| Ctrl+Alt+G | Game passthrough: keyboard and mouse straight to the game |
| Ctrl+Alt+Space | Open a Mac app's menus |
| Ctrl+Alt+Q | Cycle the Mac's picture quality |

Troubleshooting, Linux commands, every screen in detail, how it works and how to build it: see the [manual](MANUAL.md).

## Full disclosure: the permissions

Better said before you install. XDesk shows the same list on first start, and Settings › Permissions shows what is on at any time.

| Permission | Level | What for | Without it |
|---|---|---|---|
| Wireless debugging (or [Shizuku](https://shizuku.rikka.app/)) | Required | Runs a small service as the shell user that sends keyboard, mouse and touch to Android apps on the big screen | Android apps on the big screen can be watched, not used |
| Display over other apps | Required | Draws the cursor and the Launchpad on the big screen | No pointer on the big screen, so barely usable |
| Notifications | Recommended | Type the pairing code right in the notification; a notification while the service runs | Type the code in the app instead |
| Notification access | When needed | Your phone's notifications on the big screen | They only show on the phone |
| Run Termux commands | When needed | Start a Linux desktop in one tap | Ignore it if you don't use Linux |
| Camera | When needed | Scan a QR code to add a Mac | Type the address instead |
| Do Not Disturb | When needed | The Do Not Disturb switch on the big screen | That switch does nothing |
| Autostart (Xiaomi and others) | When needed | Stops the system from cutting off notification forwarding | Notifications on the big screen come and go |

**Not asked for:** root, accessibility, storage, phone, location, contacts.

**The honest bits:**
- After pairing over wireless debugging, XDesk's service runs as the shell user, like adb: it can inject input, create virtual displays and route sound, but cannot read other apps' private data. Only XDesk itself can call it, and it stops at a reboot or when XDesk is updated or removed. The pairing key stays valid until you revoke it in the developer options.
- The code still carries an accessibility service inherited from Termux:X11 (for catching system shortcuts). It is off by default and XDesk never asks you to turn it on.
- Picture, sound and input to XDesk Receiver, the web display and a Mac over Wi-Fi are not encrypted. Use them only on networks you trust.

## Notes and credits

- **Tested on very few devices.** I use it daily on a European Xiaomi 15 Ultra (Android 16) and test the Mac app on one M4 Mac. Other phones may differ; [reports](https://github.com/n2ray/xdesk/issues) are welcome.
- **Wireless, web and the Mac on Wi-Fi are not encrypted.** Use them on networks you trust. **In a car, park first**; never while driving.

**I built XDesk together with an AI, test it on the devices I have, and use it every day. There is no warranty and no support desk: use it at your own risk.** If XDesk is useful to you, you can [buy me a coffee on Ko-fi](https://ko-fi.com/n2ray).

**Thanks to**

- [Termux:X11](https://github.com/termux/termux-x11): the Linux desktop is built on it, and the X server and rendering are its authors' work (GPL-3.0, [upstream README](README.upstream.md)).
- [Sill](https://github.com/Saffsanity/sill) by Noah Saffer: the Mac connection talks to it; XDesk is not an official Sill app (Apache-2.0).
- [libinput](https://gitlab.freedesktop.org/libinput/libinput): the touchpad's two-finger scroll, pinch and axis lock rules (MIT).
- [termux_on_dex](https://github.com/PietroCav501/termux_on_dex) by PietroCav501: GPU drawing in the X server, glamor (GPL-3.0).

XDesk is GPL-3.0; the licence and third-party licences are in [LICENSE](LICENSE) and [LICENSES/](LICENSES/).
