# XDesk

<p align="center"><img src="docs/img/readme-desk.jpg" width="720" alt="XDesk on a portable display: an Android desktop with a dock, the phone in a stand as touchpad, a keyboard and a mouse"></p>

**Put Android apps on the big screen from your phone, with no root.** Install one APK, activate it once, connect a screen, and your Android apps run full screen on it, with the phone as touchpad and keyboard. The display's home is the same launcher as on the phone, and a push of the pointer against the bottom edge raises a dock, so it feels much like a computer. From there you can add Linux desktops through Termux, bring Mac windows and the Mac desktop onto the display, cast over Wi-Fi to another Android device, use XDesk on a Mac as the big screen, or use any browser as the display. With a gamepad, or a keyboard and mouse, and a cloud gaming app such as GeForce NOW, the phone becomes a [game console](#games-the-phone-as-a-game-console) on the big screen.

> **0.6.0 is a beta.** It reworks how XDesk handles content and devices — one switchboard for the phone, the display and the docks, devices added on demand, Mac support, and more — and it has been tested less than the stable releases. v0.5.1 stays the stable version. The betas are published on the [releases](https://github.com/n2ray/xdesk/releases) page (the newest is `v0.6.0-beta.4`) and install over 0.5.1.

| Screen | How it connects |
|---|---|
| Monitor, portable screen or dock | USB-C video output, the lowest latency |
| Car | Android Auto, parked only (experimental) |
| Projector, TV box or old phone | Wi-Fi, to XDesk Receiver |
| Mac with Apple silicon | USB or Wi-Fi, with the [XDesk Mac app](#mac) and the Mac's keyboard and mouse; Mac windows and the Mac desktop also come to the display |
| iPad, laptop or smart TV | Any browser, nothing to install there |

**[Website](https://n2ray.github.io/xdesk/)** · [中文说明](README.zh-CN.md) · The Linux desktop is based on [Termux:X11](https://github.com/termux/termux-x11) ([upstream README](README.upstream.md)) · GPL-3.0 · [Built by me and Claude](#built-by-me-and-an-ai)

> **Status:** an early personal project that works daily on one device, the European (EEA) Xiaomi 15 Ultra (model 25010PN30G, Snapdragon 8 Elite, HyperOS 3, Android 16, locked bootloader). It has not been tried anywhere else, including the Chinese version of the same phone. Other phones and ROMs may behave differently. The UI follows the system language: English, or Simplified Chinese when the phone is set to it. On Android 13 and later you can also pick the language in XDesk's settings. Reports from other devices are very welcome.

## Quick start

Android 11 or newer (only tested on Android 16). No root, no computer and no other app needed.

### 1 Install the app

Download `xdesk.apk` from the [releases](https://github.com/n2ray/xdesk/releases) page (the latest stable, or the v0.6.0-beta.1 tag for the beta) and install it. If your file manager reports "app not installed", use `adb install -r xdesk.apk`; on Xiaomi phones, first turn on **USB install** in Developer options.

From v0.2.0 the APK is signed with XDesk's own release key. v0.1.x used upstream's public test key, so Android refuses to update it: uninstall the old XDesk first.

### 2 Activate it once

Android doesn't let normal apps send input to other apps, so XDesk starts a small service of its own through wireless debugging. Open XDesk and follow its activation page:

1. Connect to Wi-Fi. It is only needed while activating, and the phone's own hotspot probably doesn't count.
2. In Developer options, turn on **Wireless debugging** and tap **Pair device with pairing code**.
3. Type the 6-digit code into XDesk, in its notification or on its activation page.
4. Allow the overlay permission when XDesk asks, so it can draw a cursor on the display.

On Xiaomi phones, also turn on **USB debugging (Security settings)** in Developer options and allow the permission to get installed apps. Already using [Shizuku](https://shizuku.rikka.app/)? XDesk can use it instead. A reboot needs activation again.

### 3 Connect a screen

Connect a monitor with a USB-C to HDMI cable or dock, and open **XDesk**. The phone needs **video output over USB-C** (DisplayPort Alt Mode): if plugging in a monitor mirrors the screen, it qualifies. Then tap an app. Nothing starts until you choose.

<p align="center"><img src="docs/img/app-android.jpg" width="560" alt="An Android browser full screen on the display"> <img src="docs/img/phone-touchpad.jpg" width="150" alt="The phone as a touchpad"></p>

### Then add what you want

- [Linux desktops](#linux-desktops-with-termux), through Termux, added in **Add device › Linux**
- [Mac windows and the Mac desktop](#mac), paired as a device, and [a Mac as the big screen](#mac)
- [Another Android device as the display](#wireless-display), over Wi-Fi with XDesk Receiver
- [Any browser as the display](#web-display)
- A car through Android Auto, parked only (see [What you get](#what-you-get))

## Android apps on the display

The external display's home is the Launchpad: the same switchboard as the list on the phone, one model shown in two places. It has tabs for running items, favourites, the phone and every device, and the two screens share the current tab and search (search reaches every device, pinyin initials included). Push the pointer against the bottom edge to open the dock over any content — Android apps, Linux and Mac — and move away to close it.

Tap an app in the launcher to run it on the external display. Physical keys and mouse go to the external content by default; Ctrl+Alt+M gives them to the phone for the current content, and again gives them back. With a mouse attached, the phone stays on the launcher and its touchpad is available from the top row; without a mouse it opens the touchpad.

Long-press any item on the phone, or right-click its icon on the display, to open its card: open, close, hide, favourite and its own settings, all in one place (the card opens on the phone). The item that is on the display has **Open the Launchpad** instead of Open: the Launchpad appears over it without ending it. Icons of other devices' items carry a small badge (a display for a Mac, `>_` for Termux, a proot or chroot's initial), so you can tell them apart in favourites and the docks.

- **Activation, with no other app.** Android does not let normal apps inject input into other apps, so a small service of this APK runs as the shell user. It reads `/dev/input` and injects events. XDesk starts it itself through wireless debugging (Android 11 or later). You turn on wireless debugging and enter the pairing code once, in the notification or on XDesk's activation page. Then XDesk starts the service whenever it opens with wireless debugging on. Termux sessions need none of this.
  - Activating needs a Wi-Fi connection: Android switches wireless debugging off without one, and the phone's own hotspot probably does not count. Once the service runs, Wi-Fi can go away.
  - A reboot needs activation again. If the service managed to give XDesk the `WRITE_SECURE_SETTINGS` permission, XDesk turns wireless debugging on when you open it with Wi-Fi connected, and off again once the service runs. Otherwise, it says what to do. Some systems refuse that permission.
  - Do not turn off USB debugging or developer options while it runs.
  - The service stays running after you end XDesk, holding no input, sound route or display setting, and only XDesk's uid can call it. It stops at a reboot, when XDesk is updated or removed, or with **Stop built-in service** in the settings.
  - XDesk's key stays paired until you revoke it in the developer options. With wireless debugging on, that key can open a shell on the phone, as for any paired device.
- **[Shizuku](https://shizuku.rikka.app/) still works.** If it is running and has allowed XDesk, XDesk uses it instead and the service runs through it, as before.
- **Needs the overlay permission,** because Android keeps its own pointer on the phone and XDesk draws a cursor on the display.
- **Switchboard and docks.** The switchboard is one model with three faces: the list on the phone, the Launchpad grid on the display (the home when nothing is on it), and the docks. Search is by name or package across every device. Dual apps (a clone profile, as HyperOS makes them) and work profile apps are listed too, marked "(dual)", and start and close on their own. The star on a row adds it to favourites, and the docks show the item on the display, the previous one, your favourites and **All** (the Launchpad over the content). One tap switches what is on the display without going back to the chooser. An app that is already running comes to the front with its state. A desktop keeps running behind an app and an app's task stays behind a desktop, as long as Android does not close it. Desktop audio is silent while an app is in front.
- **Cursor in RustDesk.** During a remote session XDesk's cursor is a small faint dot over the remote picture, so it stays out of the way of the remote cursor but still shows on RustDesk's own screens. It is the normal cursor over the toolbar and for a few seconds after a toolbar click. It detects a session from a window flag seen in one version. If that is wrong, turn on the setting to always show the cursor.
- **Android Back.** The **Back** key sends Back to the app on the display, for apps that rely on the edge swipe, and a mouse's side buttons send Back and Forward. In a RustDesk session the side buttons keep going to RustDesk. If Back leaves no app on the display, the list opens. It also goes to an app that Back uncovers.
- **Two input modes, per app, and the cursor shows which.** **Mouse & keyboard** has XDesk's black arrow and is keyboard and mouse: the phone's touchpad, buttons and keys, and a physical mouse and keyboard. **Touch** is for apps and pages that only listen to touch, and has a rounded black arrowhead, which hides when still, so you can keep watching the big screen: one finger moves the cursor, a tap is a tap at the cursor, tap then press and slide is a drag, and press and hold is a long press. Two or more fingers, up to five, are real fingers placed around the cursor, with a dot on the display for each, so pinches and swipes pass through. A physical mouse then acts as fingers, left as one, right as two, and the wheel as a swipe. XDesk remembers the mode per app, the default is Mouse & keyboard, and RustDesk stays at Mouse & keyboard.
- **Full screen touch area.** An icon in the touch area's corner makes it the whole phone screen, with the system bars hidden, and XDesk remembers this. Small translucent corner icons give Back and the keyboard, and leave full screen, as does the phone's Back. In Mouse & keyboard mode the left and right buttons go away, since tap, two-finger tap and hold-to-drag cover them.
- **Keyboard.** Apps raise the phone's soft keyboard on the phone. The display's input method setting changes only while a physical keyboard types on the display, and is put back when it returns to the phone, when the session ends, or when the service dies.
- **Home and dashboard.** When nothing is on the display it shows the Launchpad — the switchboard grid, the same as the phone's list, with its own pointer and search — over a dashboard with a clock and status. **End XDesk** removes it, and mirroring returns.
- **Ending the session.** The display stays as it is until you end XDesk with **End XDesk** (in the settings), or by swiping it away from recent tasks. Then apps on the display move back to the phone, not closed, the display returns to mirroring, input is released, and the sound route and interface size are reset. A Termux desktop keeps running without its window, and the launcher shows it as running. Moving apps needs the injector service. Without it they stay on the display, and XDesk says so. Going to the launcher or to the home screen ends nothing: taken input stays taken, and Ctrl+Alt+M still works.
- **XDesk never restarts a desktop by itself.** If the X server stops, the launcher says so and the session can be started again with a tap.
- **Interface size.** The screen's **Size** setting sizes XDesk's own screens — the Launchpad, the docks, the cursor — and X and Mac contents; **Android apps' interface size** applies only to Android apps. It sets the default density from 120 to 220 dpi while an Android app is on the display, remembered per physical display. An app can have its own number on its card, which applies before it starts and whenever you switch to it. Density only changes when the value differs, because apps reconfigure. The cursor keeps its size, set from the display's height. It is reset when the session ends, so mirroring is normal again. While a Termux desktop is shown, the display uses a separate number, 168 dpi by default and set in the same place. It only sizes Android's windows there, such as the input method's candidates; the desktop has its own DPI setting. On HyperOS a cleared density falls back to the phone's own, so XDesk sets the display's own value instead. It needs the injector service.
- **RustDesk's input relies on its internals.** Flutter breaks drags from normal mouse events there, so XDesk sends button changes as hover events, and guesses when a click is meant for RustDesk's own toolbar. A RustDesk update may break this. Packages with their own method are listed in `AppInputProfile.java`. Everything else gets normal Android mouse events.
- **The phone keyboard can only send what a keyboard can type.** Chinese and other IME text does not reach Android apps.

## Games: the phone as a game console

Open a cloud gaming app such as **GeForce NOW** on the display, connect a gamepad, and the phone is a game console on the big screen. Gamepads go straight to the app. For games played with a **keyboard and mouse**, press **Ctrl+Alt+G**: XDesk lets go of them and the app gets the real keyboard and mouse, so mouse look, WASD and every key work as on a PC. Press Ctrl+Alt+G again, or touch the phone, to give them back to XDesk.

- Tried with GeForce NOW on the test phone: a Bluetooth gamepad, and a keyboard and mouse with Ctrl+Alt+G, smooth on a USB-C display and over Wi-Fi to XDesk Receiver on a projector.
- The same should work for Steam Link, Moonlight and other apps that want the real keyboard and mouse; they have not been tried yet.
- The keyboard and mouse must be connected to the phone (Bluetooth or USB). The picture can go to any screen XDesk drives.
- While Ctrl+Alt+G is on, the Dock, the notification centre and XDesk's other shortcuts wait until you leave it. Details in [GAMING.md](GAMING.md).

## Linux desktops with Termux

Optional, and Android apps need none of it. The phone's own apps are there from the first start; Linux desktops are devices you add on demand. In XDesk, open **Add device › Linux** and add Termux (and any proot or chroot environment the `xdesk` command knows). XDesk asks for Termux's permission to run commands there. Each environment then gets its own tab with its apps; there is nothing to connect, the lists are always there.

This part needs [Termux](https://github.com/termux/termux-app). Run this in Termux:

```sh
curl -fsSL https://raw.githubusercontent.com/n2ray/xdesk/xdesk/xdesk/install.sh | bash
```

The installer asks before it turns on `allow-external-apps` (Termux's switch that lets other apps run commands): it explains, and waits for a `y`. Then it installs the `xdesk` command and its loader, looks for desktops you already have, and downloads the app, which you can ignore if it is already installed. No desktop yet? For example: `pkg install xfce4 xfce4-terminal && xdesk setup`.

> Please read scripts before piping them to a shell: [install.sh](xdesk/install.sh).

If XDesk finds no Termux, the Termux tab and the add page offer **Install xdesk in Termux**: it copies the install line and opens Termux, and you paste and press Enter. If you later remove Termux or reinstall it, XDesk notices and tells you, and forgets everything it knew of the old one: environments, sessions, added devices, hidden items, Linux favourites and recents, and the program list. Add the new Termux again to start over.

A Linux environment is a local device: its apps are always listed, and opening a program starts a light X on demand (X, the window manager, no panel or desktop). Only **Whole desktop**, first in every Linux tab, starts the desktop session. **Stop** — on the device row and its settings — ends the X server all environments share, after a confirmation when windows or a desktop are running.

XDesk's lifetime is separate from any desktop. Opening or swiping away XDesk never starts or stops XFCE. A Termux desktop is one entry in the launcher, and you start and stop it yourself.

XDesk only provides the display. Anything that can reach its X socket can show up on it.

```sh
xdesk x                 # start only the X server and audio
xdesk run CMD...        # run any program on the display
xdesk setup             # look for desktops in Termux and proot-distro
xdesk list              # list sessions, * marks the default
xdesk use NAME          # choose the default session
xdesk start [NAME]      # start a session
xdesk switch NAME       # stop the current session and start another, the window stays
xdesk add NAME 'CMD' [--root DIR] [--launch 'PREFIX']   # add your own session
xdesk rm NAME           # remove a session
xdesk status            # what is running
xdesk stop              # stop everything
xdesk envs              # the environments (Termux, each proot or chroot) and their sessions
xdesk apps              # the programs XDesk can open one by one (.desktop files)
xdesk launch SCOPE/ID   # open one of them, e.g. termux/org.xfce.mousepad
xdesk start NAME+apps   # a session with only the window manager, no panel or desktop
xdesk ime setup         # set up and start the X side's input method (fcitx5)
xdesk ime layout NAME   # the keyboard layout of the X side, e.g. de-nodeadkeys; auto to forget
```

A session is one command, stored in `~/.config/xdesk/sessions/NAME.sh`. Edit these files freely, `xdesk setup` will not overwrite them.

Programs of a proot-distro session are found in its system and run in it. For a chroot session, `xdesk add NAME 'CMD' --root DIR --launch 'PREFIX'` writes `NAME.root` (one line: the path of its root; xdesk warns when it cannot read it, e.g. under `/data/local`, but writes it anyway) and `NAME.launch` (a script that runs `PREFIX` with the arguments it is called with — `--launch 'chroot DIR'` for a plain chroot — and passes `DISPLAY` and the other variables on) next to `NAME.sh`.

| Environment | How to connect |
|---|---|
| Termux | Found by `xdesk setup`, or `xdesk run xterm` |
| proot-distro | Found by `xdesk setup`. By hand: `xdesk add box "proot-distro login ubuntu --shared-tmp -- env DISPLAY=:1 PULSE_SERVER=127.0.0.1 PULSE_COOKIE=/tmp/xdesk-pulse-cookie dbus-launch --exit-with-session startxfce4"` |
| Plain proot | Bind `$PREFIX/tmp` to `/tmp` and set `DISPLAY=:1` |
| chroot (root) | Bind `$PREFIX/tmp/.X11-unix` to `/tmp/.X11-unix` in the chroot, set `DISPLAY=:1`. Little tested so far |

GPU acceleration is only set up for Termux on Adreno: the installer adds Turnip, Mesa's open Vulkan driver, and Mesa then runs OpenGL on the real GPU through zink, falling back to software rendering on GPUs too old for Turnip. Inside proot or chroot, expect software rendering unless you configure Mesa there yourself. That is fine for debugging, not for games.

Audio from proot and chroot reaches the app through PulseAudio on `127.0.0.1`.

### Starting from the app

The launcher lists each device's programs, and tapping one starts it — the light X first, if needed — which needs permission to run commands in Termux. XDesk asks for it when you add Termux; to allow it later:

```sh
xdesk enable-app-start
```

This sets `allow-external-apps = true` in Termux, so apps can ask to run commands there. Each app also has to be granted the permission, and XDesk asks the first time it runs a command.

**Whole desktop**, first in every Linux tab, is the only thing that starts the desktop session. A session that is already running is marked in the launcher. Tapping it reattaches without restarting anything. Its **Stop** button, or **End Termux desktop** in the settings, is how you stop it, because leaving XDesk does not. `xdesk stop` also works.

### Input method and keyboard layout

A physical keyboard types into Linux through the input method of the X side when one runs there. With fcitx5 in Termux (run `xdesk ime setup` once, which says what to install if fcitx5 is missing, then writes a first config and starts it), the phone's keyboard types into X as keys and fcitx5 composes, with its candidates on the external screen, at the text — Termux, proot and chroot programs alike. Shift+Space switches the input method (Ctrl+Space belongs to Android's own). Without fcitx5, the phone's input method composes and the finished text is sent. Each Linux device page has the switch **Keyboard types with the phone's input method**, and says under it whether an input method runs on the X side now.

The keyboard layout of the X side is remembered: `xdesk ime layout NAME` (fcitx5's name, e.g. `us`, `de`, `de-nodeadkeys`; `auto` to forget it), also set from the row on the Linux device page. A fresh X server starts with `us`, so a non-US keyboard needs this once.

## Wireless display

No cable? Install **XDesk Receiver** (`xdesk-receiver.apk` from the [releases](https://github.com/n2ray/xdesk/releases)) on another device on the same network: an old phone, a projector or a TV box, Android 5.0 or later. XDesk then streams a display to it over Wi-Fi, and everything works as on a cable: Android apps, a Termux desktop and the sound. On a tablet or old phone the receiver can also control the phone: in mouse mode its screen is a touchpad, in touch mode you tap where you want, and the phone's own keyboard opens on the picture. A projector or TV box only shows the picture and plays the sound; see [receiver/README.md](receiver/README.md).

1. Open XDesk Receiver on the other device. It shows its name and address and waits.
2. In XDesk, open settings → **Display** → **Wireless display** and choose the receiver. It is found over mDNS; across subnets, use **Enter address** with the address shown on the receiver.
3. The first time, type the 4-digit code shown on the receiver. Paired devices connect without a code from then on.

- **Needs the injector service** (built-in or Shizuku): it creates the display that is streamed. The wireless display has its own session in the service, so apps starting and ending do not touch it, and XDesk makes the display again if the service drops it.
- **Video** is H.264 at the receiver's screen size, capped at 1920 on the long side and to what its decoder handles, at about 12 Mbit/s for 1080p, decoded with low latency. **Sound** is the phone's whole output, captured as PCM and played on the receiver while the phone is silent. It follows the same sound output setting as a cable: system default goes with the display, here the receiver; choosing one of the phone's outputs, e.g. headphones, keeps the sound on the phone. It can be changed while streaming.
- **The receiver's handle** has Back, Home, **Overview** — which opens the switchboard on the display — and **Switcher** — one step of the app switcher — with the phone's touch settings (natural scrolling, pointer speed, scroll speed) sent over, so it feels like the phone.
- **Stop** from the same settings row, with **End XDesk**, or by closing the receiver.
- Once paired, each side proves it holds the token without sending it. The pairing code, the token issued at pairing, and the picture and sound still travel unencrypted, so pairing keeps other phones from casting by accident but does not protect against someone who can read the network. The protocol is in [receiver/PROTOCOL.md](receiver/PROTOCOL.md).

## Mac

Two ways to use a Mac, both with a Mac with Apple silicon. Pairing (on the phone, in **Add device › Mac**, or settings → **Connect device**) takes the Mac's address and the 12-digit code Sill shows, or scans the QR code in Sill's pairing window, or opens a `sill://` pairing link from the Mac. Or tap **Ask the Mac for a code**, and Sill shows one for you. The Mac needs [Sill](https://github.com/Saffsanity/sill) 0.6.1 or later.

### XDesk on the Mac as the big screen

Shows the phone's picture and sound in a window on a Mac, with the Mac's keyboard and mouse: [download `XDesk-macos-arm64.dmg`](https://github.com/n2ray/xdesk/releases/latest/download/XDesk-macos-arm64.dmg), the same version as on the phone. In my tests it took about 15 to 20 ms from the phone to the Mac over USB.

- **First start:** the app is self-signed, so macOS stops it. Choose **Open Anyway** in **System Settings → Privacy & Security**. Then turn on XDesk under **Accessibility** so that the keyboard can go to the phone. The window says so, and there is no need to restart.
- **Connecting:** with a cable and USB debugging it connects over USB, and back over Wi-Fi when the cable is pulled. Without a cable, pick this Mac under Connect to a receiver in XDesk on the phone. The first time asks for a pairing code.
- **Use:** rest the pointer on the window's top edge for Back, Home, Recents, Full screen and Disconnect, also in the Phone menu. Drop a file on the window to send it to the phone's Downloads, and choose Send to computer in the phone's share sheet to send one to the Mac's Downloads. Text on the clipboard is shared; it can be turned off under Connect to a receiver. On the Linux desktop you type with the Mac's input method, and Control+Space switches it; in Android apps you use the phone's. A Mac keyboard keeps Command as Command.
- **Keys:** Control+Option with Esc (release the keyboard and mouse to the Mac), F (full screen), B (back), H (home), R (recents), D (latency panel), L (low latency).

### Mac windows and the Mac desktop on your display

Pair a Mac as a device and its tab lists the whole desktop, its open windows and its apps; any of them can be the content on the display. A Mac window can be resized to your screen (**Fit to screen**); Sill puts it back when the stream ends. The whole desktop is how you unlock a locked Mac from XDesk, and a Mac locked when you connect says so.

- **Typing.** A physical keyboard on the phone types with the Mac's own input method by default: the keys go to the Mac as keys, the Mac composes, and its candidates show in the Mac's picture, at the text. The per-Mac switch **Keyboard types with the phone's input method** changes that. On a PC keyboard Ctrl works as ⌘, Win as Ctrl, Alt as ⌥.
- **Picture.** **Quick quality** switches Sharp / Balanced / Smooth in one tap — the Mac's own settings, so every device connected to it follows, and Sill's menu on the Mac changes them too. With a Mac on the display, Ctrl+Alt+Q cycles the modes. **Lower quality automatically on a weak link** steps the quality down when the Mac reports that the link cannot carry the picture, and back up after it has kept up for a while. The picture and sound page also shows the **round trip** to the Mac, with advice when it stays high.
- **Sound.** **Play the Mac's sound here (experimental)** is on by default and needs the Mac's own **Send Audio** turned on in Sill; the sound then follows XDesk's sound setting. Muting is per device: **Mute this device**, and pulling the wired or Bluetooth headphones the sound goes to mutes it until you unmute. The sound plays without lip sync.

## Web display

Any device with a browser can be the display: an iPad, a laptop, a smart TV. No app to install. Turn on settings → **Display** → **Web display**; XDesk then serves a page on port 47150 and lists the addresses to open, by network:

- **Wireless:** the phone's Wi-Fi address, or its hotspot's address when the other device joins the hotspot.
- **Wired:** connect a USB cable and turn on **USB tethering** (the row opens the tethering settings). A wired address appears. The delay is lower and steadier than over Wi-Fi. iPad and Mac only speak NCM over USB: tap **USB tethering as NCM** first (it needs the permission the built-in service gives; otherwise run `adb shell settings put global tether_force_usb_functions 1`), then turn tethering off and on.

The first time, the browser asks for a 4-digit code that XDesk shows on the phone; it remembers the phone from then on. You get the picture, the phone's sound, and the browser's mouse, keyboard and touch:

- **Mouse:** your computer's own pointer, at its own speed and with no delay. The desktop's pointer is hidden meanwhile, so the pointer's shape does not change over text or links; it comes back when you use the phone's touchpad, and the computer's pointer is hidden until you move the mouse again. Buttons and the wheel go to the display. Adding `?lock` to the address captures the mouse instead (Pointer Lock, Esc releases it), for games that need relative movement.
- **Touch** works as the phone's touchpad: one finger moves, tap clicks, tap and drag drags, two fingers scroll, a two-finger tap is a right click, three fingers a middle click.
- **Keyboard:** characters are the ones the computer's keyboard really types, so a German Mac keyboard works as itself. Shortcuts go as key codes; on Mac and iPad keyboards Command acts as Ctrl. Text from input methods (Chinese, the iPad's soft keyboard through ⌨) goes as text.
- **Input:** the page's keyboard and mouse work on the display while the page has focus, whether or not the phone has its own. The phone's input method then shows its candidates in the page.
- **Staying on the page:** the toolbar at the top (move the mouse up or touch the top edge) has full screen, keyboard, sound, Back, Home, Overview and disconnect. Overview opens the switchboard on the display. Back is blocked and leaving asks first; the page does not zoom, scroll, select or show menus. In Chrome full screen, Esc and browser shortcuts go to the display too. On the iPad, **Add to Home Screen** opens it full screen like an app.

It shares its machinery with the wireless display, so there is one browser or receiver at a time, it needs the injector service, and XDesk must stay open on the phone. The page is plain HTTP: the pairing token, picture, sound and input are not encrypted, so use it only on networks you trust. To keep it as a window of its own, use the browser's full screen or, in desktop Chrome, a shortcut opened as a window. Video is H.264, decoded with WebCodecs where the browser allows it (localhost, HTTPS) and otherwise with Media Source Extensions, which works on plain HTTP in Safari, Chrome, Edge and Firefox. The page and protocol are in `lorie-app/src/main/assets/web/` and [receiver/PROTOCOL.md](receiver/PROTOCOL.md).

## What you get

- Phone as touchpad: move, tap, two-finger right click and scroll, long press or tap-and-hold to drag
- Soft keyboard with IME text input including Chinese, and a shortcut row (Esc, Tab, Ctrl, Alt, Super, Shift, arrows)
- Physical mouse and keyboard go to the display while XDesk is open, Ctrl+Alt+M moves them back to the phone and again. A physical keyboard types through the input method of the content: fcitx5 in Termux for Linux, with its candidates at the text on the display; the Mac's own for a Mac. Needs the injector service
- Android apps full screen on the display, activated through wireless debugging or Shizuku
- One switchboard: the list on the phone, the display's home (the Launchpad) and the docks are one model, with tabs for running items, favourites, the phone and every device, shared between the screens, and search across every device. A push of the pointer against the bottom edge opens the dock over any content
- Devices added on demand: the phone's own apps come first, then Linux in Termux (each proot or chroot its own device), and Macs, each with its tab, status and settings
- Linux programs as apps, opened one by one with a light X and no desktop, GPU acceleration on Adreno (Turnip, zink) set up by the installer
- Mac windows, apps and the desktop on the display, and XDesk on the Mac as the big screen, with text and files shared. See [Mac](#mac)
- Wireless display: the same over Wi-Fi to XDesk Receiver on a projector, TV box or old phone, with sound, and the receiver's handle has Back, Home, Overview and Switcher
- Audio output you can switch: phone speaker, HDMI, Bluetooth, USB, wired, or system default. It is one setting for every mode and survives switching. With the injector service running, it sets the system's preferred media device, which covers desktops, Android apps and everything else, and clears it when you return to the chooser or exit. The next session applies your choice again. Without it, this is best effort for desktop audio only: on the test phone it could not bring sound back to the phone speaker while HDMI was connected. It only points output at the device, and says nothing about quality or Bluetooth codecs. If XDesk is killed so hard that it cannot clear the preference, choose system default in XDesk once, or reboot
- Resolution presets, sensitivity, portrait or landscape touch screen, dim the phone when idle
- Survives unplugging the display, switching apps and locking the phone
- Games: cloud gaming such as GeForce NOW on the big screen with a gamepad, and with the real keyboard and mouse after Ctrl+Alt+G. See [Games](#games-the-phone-as-a-game-console)
- Android Auto, parked only (experimental): XDesk appears among the car's apps and shows a clock on the car's screen. From the phone you can run a Linux desktop there, sized to the car's screen, with the phone as touchpad and keyboard, and its sound plays in the car. Android apps (YouTube, Chrome and others) run there too, on a display XDesk makes for them, and the car's touch screen works on them. With an external display also connected, the external display is used

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
- **Android apps** are started on the display with the injector (`InjectorService`), which runs as the shell user and sends events with `injectInputEvent`. `CursorOverlay` draws the pointer.
- **Audio** goes from a PulseAudio null sink through a socket in Termux's private directory; the X server hands the app a connection to it. The app plays it with `AudioTrack`, which is how it can pick the output device without special permissions. Clients in proot or a chroot reach PulseAudio on `127.0.0.1:4713` with its cookie, since every app on the phone can reach that port. `xdesk` sets `PULSE_COOKIE` for its sessions and adds it to proot-distro sessions. A chroot or other setup of your own needs `PULSE_COOKIE` set to `$PREFIX/tmp/xdesk-pulse-cookie` as seen from inside it. If that is not possible, `touch ~/.config/xdesk/pulse-anonymous` restores the old port without a cookie, which any app on the phone can use to record the desktop sound and control PulseAudio.
- **Wireless display:** the injector creates a public display whose surface is a MediaCodec H.264 encoder, and records the phone's output through `REMOTE_SUBMIX` (`WirelessDisplay`, `OutputCapture`). XDesk connects to the receiver on port 47140 and treats that display as its external display.

Almost all changes live in `lorie-app/` and `xdesk/`, so merging upstream stays simple.

## Troubleshooting

Logs are in `$PREFIX/tmp/xdesk.log`.

| Symptom | Likely cause |
|---|---|
| Waiting forever | You attached to the X display and nothing is running yet. Run `xdesk start`, and check `xdesk status` |
| No Linux device in the launcher | Add it in **Add device › Linux**. Then run `xdesk enable-app-start` and `xdesk setup`, and allow XDesk to run Termux commands |
| Android app list is short | Some ROMs limit what apps can see. On Xiaomi, check the permission to get installed apps |
| Injector service does not start | Check that Wi-Fi is connected and wireless debugging is on, then return to XDesk. It has to be activated again after a reboot. With Shizuku, check that Shizuku is running |
| Pairing fails or the notification takes no input | Keep the pairing dialog open. Enter the code on XDesk's activation page, using split screen or a small window if leaving Settings closes the dialog |
| "Display :1 is used by another X server" | The official Termux:X11 is running. Stop it, for example with `pkill -f termux-x11` |
| App's start button fails | Run `xdesk enable-app-start` and check the permission |
| Desktop does not fit the screen | It started before the app connected. Open the app first, then restart the session |
| External screen goes black after a few minutes | XFCE's screensaver. Move the mouse, or raise `/saver/idle-activation/delay` in the `xfce4-screensaver` xfconf channel |
| Phone's own cursor appears | Mouse capture was lost. Tap the XDesk touch screen |
| No sound | Check the output in the top bar. Many monitors have no speakers |
| Ctrl + letter does nothing on the soft keyboard | Pinyin mode keeps the letter in the IME. Switch to the English layout or use a physical keyboard |
| Chinese with a physical keyboard in Linux | Run `xdesk ime setup` in Termux to set up fcitx5; Shift+Space switches the input method |
| Android apps get no input on Xiaomi | Turn on **USB debugging (Security settings)** in Developer options |
| Flickering blocks in GPU programs on the car | Turnip's tiled rendering is interrupted by the composition of Android Auto's display. Start them with `TU_DEBUG=sysmem`; for Chromium, `--use-angle=gl --disable-features=Vulkan` is also faster |

## Limitations

- 0.6.0 is a beta, and the new parts have been tested less than the rest. In particular, the receiver paths (XDesk Receiver, the web display, the Mac app) were not retested on real devices in this round, and proot and chroot environments are little tested
- Android apps need activation through wireless debugging or Shizuku, and only one full screen app or desktop can use the display at a time. Split screen, several windows and multiple displays come later (phase 2), as do Windows support and receiver protocol v3
- Mac sound plays without lip sync
- Android Auto: only while parked. When Android Auto closes XDesk or disconnects, the desktop keeps running without its window, and Android apps keep running and come back when XDesk is opened on the car again. DRM video (some streaming apps) may be black on the car. Android apps on the car need activation, as on an external display, and go back to the phone when the session ends
- Termux and XDesk must both stay alive. Aggressive system cleaners can kill them
- Wireless display: one phone per receiver, picture and sound only, and only as smooth as the Wi-Fi. Tested with a 32-bit Android 9 projector
- Used daily on a single phone, the European Xiaomi 15 Ultra, and barely tested on others
- The Mac app is for Apple silicon and tested on one M4 Mac. It is self-signed, and over Wi-Fi it is not encrypted, like the other wireless modes. There is no Windows or Linux version yet

## Building

The default branch is `xdesk`, `master` tracks upstream. Pushes build on GitHub Actions, and tags like `v0.2.0` publish a release, signed with the key in the repository secrets (see `.github/workflows/release.yml`). A tag with a suffix, such as `v0.6.0-beta.1`, publishes a pre-release.

```sh
git clone --recursive https://github.com/n2ray/xdesk
cd xdesk && ./gradlew assembleDebug      # needs the Android SDK and NDK
```

To merge upstream:

```sh
git remote add upstream https://github.com/termux/termux-x11
git fetch upstream && git merge upstream/master
```

The Mac app is in `desktop/`, written in Rust. On a Mac with Rust and Homebrew's `sdl2`, run `cargo build --release` in that directory. `desktop/packaging/build-app.sh` makes the package.

XDesk relies on upstream's manifest, class names under `com.termux.x11` and the `LorieView` API. The app id is set in `lorie-app/build.gradle` and `shell-loader/build.gradle`, and they must match.

## Built by me and an AI

I'm [n2ray](https://github.com/n2ray), and I build XDesk together with Claude, an AI. I decide what XDesk should do and test it on real phones, monitors, projectors, cars and Macs. Claude wrote most of the code and documentation, including this README.

## License

GPL-3.0, same as Termux:X11. All credit for the X server and rendering goes to the [Termux:X11 authors](https://github.com/termux/termux-x11).

The Mac client (Mac windows and the Mac desktop on the external screen) talks to [Sill](https://github.com/Saffsanity/sill) by Noah Saffer and reuses code from [Sall](https://github.com/n2ray/sall), a third-party Android client for Sill that I (n2ray) also wrote. Neither is an official Sill app. The code is used under the Apache License 2.0 (`LICENSES/Apache-2.0-Sill.txt`, `NOTICE-Sill.txt` in the app).
