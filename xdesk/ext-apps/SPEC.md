# Android apps on the external display

Status: a prototype works on a Xiaomi 15 Ultra (HyperOS 3, Android 16, no root) with RustDesk 1.5.1 controlling a Mac. This document specifies turning it into a proper XDesk feature. The prototype is in this directory.

## Goal

XDesk stops being only a Linux desktop launcher. When it opens, the user chooses what runs on the external display:

- a Termux desktop (the existing `xdesk` sessions), or
- an Android app, full screen on the external display.

The phone stays the controller: touchpad, keyboard, physical mouse and keyboard forwarding.

## Required behaviour

1. **No automatic desktop start.** Opening the app no longer starts XFCE by itself. Show a chooser instead. Remove the "start the desktop when the app opens" behaviour, and do not restart a session unless the user chose one in this launch. Reattaching to a desktop that is already running is fine; say that it is running.
2. **Chooser.** Lists the Termux sessions (from `xdesk list`) and installed launchable Android apps, with recently used first. Works without an external display, and explains that one is needed to start.
3. **Launch on the external display.** Use `ActivityOptions.setLaunchDisplayId` with full screen windowing. Handle the display id changing: it is not stable across replugging, so never hard-code it.
4. **Input forwarding** for an Android app on the external display, using a privileged injector (see below): physical mouse and keyboard, and the existing phone touchpad and keyboard UI.
5. **Cursor.** Android's own pointer stays on the phone (`Pointer Display ID: 0`), even with desktop mode forced on. XDesk draws its own cursor on the external display with an overlay window created from a display context (`createDisplayContext`, `TYPE_APPLICATION_OVERLAY`, not focusable, not touchable). It needs the overlay permission; request it in the setup flow. It must follow injected movement, hide when input goes back to the phone, and not appear in a Termux desktop session, which has its own cursor.
6. **Switching.** A way to give the physical mouse and keyboard back to the phone and take them again. The prototype uses Ctrl+Alt+M; keep that as the default, and add a button in the phone UI. Do not grab devices until the user asks for it, and release every held key and button on release.
7. **Return to the chooser** from the phone UI, stopping forwarding and removing the overlay. Stopping an app must not kill a running Termux desktop.

The existing Termux desktop path keeps working exactly as it does now apart from point 1, including the touchpad, audio and `xdesk` CLI.

## Privileged injector

Normal apps cannot inject into other apps (`INJECT_EVENTS`), so this needs shell (uid 2000) privileges without root.

- Use **Shizuku** (`dev.rikka.shizuku:api`, `:provider`) to run the injector as a user service, with a clear setup flow when Shizuku is missing, not running, or not authorised. Shizuku is only needed for Android apps; a Termux desktop must keep working without it.
- Reading physical input: uid 2000 is in the `input` group and can open `/dev/input/event*`. The prototype's `evcap.c` uses `EVIOCGRAB`. Prefer doing this without shipping a native binary if practical; otherwise build it through the existing CMake setup. A grab ends by itself when the process exits, so a crash must not leave the user without a keyboard.
- Do not hard-code device names (the prototype matches "Logitech"). Find devices by capability: relative axes plus buttons for a mouse, letter keys for a keyboard. Handle hotplug. Skip the phone's own touch screen, buttons and sensors.

## Input rules found by testing

These cost a lot of trial and error. Keep them, with the reasons, in code comments. `Bridge.java` is the working reference.

- Inject with `InputManagerGlobal.injectInputEvent(event, 0)`, `SOURCE_MOUSE`, `TOOL_TYPE_MOUSE`, and hidden `MotionEvent.setDisplayId`. `KeyEvent.setDisplayId` for keys.
- `adb shell input mouse` does not work for clicks because it sets no `buttonState`.
- **RustDesk's remote picture:** an Android `ACTION_DOWN` becomes both a mouse and a touch pointer in Flutter. The touch sets RustDesk's `isPhysicalMouse` to false, and every move during the press is lost, so dragging fails. Changing the device id or adding `HOVER_EXIT` does not help. So send everything, including button changes, as `ACTION_HOVER_MOVE` with `buttonState` set. RustDesk's `_getMouseEvent` treats a change of buttons as a press or release.
- **Nudge:** Flutter's `LocationNeedsUpdate` drops a hover whose position has not changed, along with its button change, so a click without movement vanishes. Move one pixel on each button change.
- **Frames:** Android merges hovers within a frame and keeps the first one's buttons, so separate button changes (the prototype waits 20 ms before and 40 ms after).
- **`ACTION_BUTTON_PRESS`/`RELEASE`** reach Flutter as hovers and break the press. Do not send them on this path.
- **RustDesk's own UI** (toolbar and its menus) needs real `ACTION_DOWN`/`ACTION_UP`; hover clicks do nothing there. The prototype sends real presses when `y >= 1440` on a 1508 high display, or for the click right after a toolbar click, because a menu is open. That is a heuristic: make the toolbar height proportional to the display rather than a fixed value, and keep the known weakness (a toolbar click that opens no menu makes the next click go to RustDesk) in a comment.
- **Generic apps:** the hover method is specific to RustDesk. Use normal `DOWN`/`MOVE`/`UP` with `HOVER_ENTER`/`EXIT` for other apps, chosen per package. Provide a table of packages, RustDesk (`com.carriez.flutter_hbb`) being the first entry.
- **Focus:** injected keys only reach a display with a focused window, and touching the phone takes focus away. Call `IActivityTaskManager.focusTopTask(displayId)` when taking input.
- **Stale modifiers:** RustDesk caches Ctrl, Alt and so on and adds them to every mouse event, and on a Mac Ctrl plus left click is a right click. The switching hotkey's own Ctrl and Alt must not reach the app, and after switching send a full press and release of Ctrl and Alt once both are physically up. A key-up alone does not clear the cache.
- Mouse speed factor 2 felt right on a 2560 wide display. Scale with display size, and make it a setting.

## Known problems to handle

- The external display reports 2560x1508 for a 2560x1440 monitor on this phone. Use the logical size for coordinates.
- Only one full screen app per display. Moving between an Android app and a Termux desktop goes through the chooser.
- Another app may already be presenting on the external display (a `Presentation` window covers ours). Detect what is possible, and do not kill other apps.

## Out of scope for now

A DeX-style desktop (launcher, task bar, freeform windows). Keep the injector and cursor reusable for it, but do not build it.

## Delivery

- Work on branch `ext-apps`. Do not push to `xdesk` or `master`, do not tag or release, and do not change the package name.
- The project is a fork of Termux:X11; follow the existing style in `lorie-app/src/main/java/com/termux/x11/`.
- Keep README in English and Chinese accurate for what ships. Say plainly that Android app mode needs Shizuku, was tested only on one phone, and that the RustDesk input method relies on RustDesk internals.
- No personal data in the repository: no IP addresses, device ids, names or remote machine details.

# Second round (after the first phone test)

The first version works well on the phone, including other Android apps full screen. These come from that test.

## Fixes since the first version

RustDesk clicks no longer use the toolbar heuristic: see `hoverMethodButton` in `InputInjector`. A press with no movement is a real DOWN and UP, and one that moves goes as hovers.

## 1. No double cursor in a RustDesk session

XDesk's cursor and the remote machine's cursor overlap in a RustDesk session. It is usable but ugly.

- While RustDesk is in a remote session, hide XDesk's cursor over the remote picture and show it over the bottom toolbar, where the remote picture has no cursor. Keep showing it on RustDesk's home page and in every other app.
- Signal for "in a session": RustDesk's window has `KEEP_SCREEN_ON` during a session and not on its home page. Seen in `dumpsys window windows` (`fl=KEEP_SCREEN_ON`) and in `dumpsys power` wake lock history. This is from a limited observation, and it can be polled from the shell-user injector about once a second. Keep the check in the per-package profile with the reason in a comment.
- A menu opened from the toolbar covers the picture and has no remote cursor. Do something reasonable: for example show XDesk's cursor for a few seconds after a toolbar click, or until the cursor has moved off the menu. Say what you chose.
- Add a setting to always show XDesk's cursor, in case the signal is wrong on another version.

## 2. The phone UI becomes a launcher and app switcher

The app list is one long list and it is hard to find an app.

- **Search** over the app list, filtering as you type, by label and package.
- **Dock:** a user-defined row of favourite apps. Add and remove from the list (long press or a clear button), reorder, and keep it across restarts. A Termux desktop can be in the dock too.
- **Switcher:** the dock stays visible on the phone during a session, on the touchpad screen, not just on the chooser. One tap switches what is on the external display, with no trip back to the chooser. Leave room for the touchpad, and handle landscape and portrait.
- Switching to an app that is already running must bring its existing task to the front on the external display, not restart it, so its state survives.
- Show which app is currently on the display.
- On a switch, change the input profile for the new app and refocus, so keys and mouse follow. Cursor hiding follows the new app too.
- When switching to a Termux desktop and back, the desktop keeps running and the Android app's task survives. Say plainly if Android or HyperOS prevents that.
- Good app icons and fast loading: cache what is slow so the list does not stall. The phone is the main control surface, so this has to feel quick.

## 3. Sound output

The monitor may have no speakers, so the user chooses where sound goes.

- A setting listing the available outputs: phone speaker, HDMI or the external display, Bluetooth, USB or wired, using `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`. Update it when devices come and go, and fall back cleanly when the chosen one disappears. Remember the choice.
- **Android apps and everything else on the system:** the shell user holds `MODIFY_AUDIO_ROUTING`. This worked in an earlier test through `app_process`: reflection on `AudioSystem.setDevicesRoleForStrategy` with `STRATEGY_MEDIA` = 5 and role preferred, then clearing it with `removeDevicesRoleForStrategy`. It needs an `AudioDeviceAttributes` for the chosen device. The earlier test was phone speaker only, and it probably does not survive a reboot. Do it from the injector, and clear the preference when XDesk's session ends so the phone returns to normal. Check the method names against the Android version at runtime with a clear error rather than a crash.
- **Termux desktop audio:** XDesk's own `AudioPlayer` can use `AudioTrack.setPreferredDevice` with no Shizuku. Use that, so a desktop still gets a choice without Shizuku.
- Without Shizuku, say that the choice only covers desktop audio.
- Be honest in the UI about what cannot be verified, such as Bluetooth codec behaviour. Put a quick output switch where it is easy to reach during a session.

## Delivery for this round

Same rules as above: branch `ext-apps` only, no tag or release, no personal data. Run the debug build with `gh workflow run debug_build.yml --ref ext-apps`, because this branch does not trigger it. Report what was built, the differences from this spec, and a test list, riskiest first.

# Third UI

Agreed after the second round. XDesk's job is putting apps on the big screen, with the phone as the control surface.

**External display dashboard.** XDesk's own window at the bottom of the external display, needing no Shizuku, shown as soon as XDesk is open with a display connected. Apps and desktops open on top of it, and when an app exits it is uncovered rather than mirroring. It shows a clock, the date, a hint and a status row, shifts slowly and dims against burn-in, and its sizes are parts of its window. 结束 XDesk removes it and mirroring returns. It is not moved to the phone and does not count as an app in front.

**Phone launcher.** Header; 我的收藏, starting with the single previous app and a divider, then favourites that scroll; 所有应用 with search, which collapses the favourites, and Linux desktops as a small group at the top with running state and start or stop. Long press opens the per-app card.

**Control screen.** Status and buttons, the same row, the touch area with collapsible function keys, and the bottom controls. In portrait the left and right buttons are directly under the touch area. In landscape they are on its sides, and status and favourites share one row. Termux desktops use the same screen.

**Input modes per app.** 键鼠 is the keyboard and mouse path. 触控 sends a real touch stream with positions from the cursor, because the user watches the big screen: one finger moves the cursor, tap, tap then slide to drag, press and hold. Two to five fingers are real pointers placed around the cursor, with a dot per finger on the display. There are no mouse buttons on the phone. A physical mouse then acts as fingers. The cursor shows the mode: 键鼠 has our own arrow, 触控 has our own rounded arrowhead without a tail, and hides when still. Both are drawn in code, so they work on any device. RustDesk stays at 键鼠. Absolute mapping was tried and replaced, and only makes sense with a live preview on the phone, which is not built.

**Full screen touch area.** An icon in the touch area's corner makes it the whole phone screen with the system bars hidden, remembered globally as a property of the touch area. Small translucent corner icons give Back and the keyboard, and leave it.

**Settings** is a full page of grouped rounded cards (显示, 输入, 声音, 外屏, 关于) with values on the right, and its own switches and selectors in the accent colour. 结束 XDesk is last, separate and red. The per-app card uses the same components.

**Keyboard on the display.** By default the display's IME policy is set to hide while the injector runs, so apps do not raise the keyboard there, and the old value returns at the end, also on crash paths. A setting turns it off.

**Orientation** follows the system by default, with a setting to fix it. **Look:** dark, one blue accent, status colour only on small dots, icons for common actions, targets of at least 48 dp.

# Built-in activation

Android apps no longer require Shizuku, which replaces "Use Shizuku" under Privileged injector. When Shizuku is running and has allowed XDesk, it is used as before. Otherwise XDesk pairs once with the device's own adbd over wireless debugging (libadb-android, API 30+) and starts `ShellServer` through `app_process`. That process stays between sessions, because starting it again may need Wi-Fi, and each session is one `InjectorService`, which gives back what it holds when it ends or when the app dies. `AppSession` only sees an `IInjector`.

- Only XDesk can get the Binder (a package-limited broadcast to a receiver requiring `INTERACT_ACROSS_USERS_FULL`) and only XDesk's uid can call it (`onTransact`).
- A running XDesk asks for the Binder by connecting to a loopback port. That connection carries nothing. The server binds the port the kernel picks and names it in its broadcast, so no app can take it first, and XDesk forgets a saved port that does not answer.
- Before libadb connects, XDesk checks that the port is adbd asking for TLS (`AdbTlsProbe`): libadb trusts any certificate and would sign an old style AUTH token. adbd makes a new key whenever it starts, so its certificate is remembered per boot, from the last start of the server. A refusal from a port with another certificate in that boot does not count as the pairing being lost.
- A server exits when its APK goes away, and XDesk ends a server with another install's version.
- 停止内置服务 in the settings ends the server, its session first. XDesk then starts it again only when the user asks: an Android app, or 去启动.
- XDesk only ever writes `adb_wifi_enabled` (and writes 0 back after a start when it was XDesk that turned it on), never `adb_enabled` or `adb_allowed_connection_time`, has no boot receiver and does not use `adb tcpip`.

# Android Auto (parked)

`CarActivity` is XDesk's entry in Android Auto (`CAR_LAUNCHER`; Android Auto only lists games as parked apps, hence `appCategory="game"`). Android Auto makes a virtual display just for it, owned by its own package, with a new id on every connection. Its other displays (maps, toolbars, its dashboard) are virtual displays too.

- **Which display.** `TargetDisplay` alone decides: a real external display (not the phone, not virtual) wins, otherwise the car's display, which is the one `CarActivity` reports it is on. Every other display is ignored, also in the display listener. While a desktop runs, it stays on the kind of display it is on: a display of the other kind arriving does not move it. When its display is gone, a newly plugged external display takes it as before, and the car takes it back when `CarActivity` returns.
- **On the car**, a Linux desktop puts `MainActivity` on top of `CarActivity`, sized to the car's display, and `CarActivity` shows again when it ends. Launching on a virtual display owned by another app needs `allowEmbedded` on `MainActivity`. There is no `DashboardActivity` (`CarActivity` is the dashboard) and no cover overlay on the car.
- **Android apps on the car.** Android Auto's virtual device blocks other packages' activities on its display (they become `BlockedAppStreamingActivity`). So the injector makes a display of its own for the session (`AppDisplay`, `IInjector.createVirtualDisplay`, as scrcpy's new display): public, own content only, touch, trusted, always unlocked, its own display group, no touch feedback; not secure, so DRM video may be black, and not destroy-content-on-removal. It has the car display's size and density, and the app's or the display's chosen density applies as on an external display. `AppSession` runs on it as on an external display: launch, injection, cursor overlay, density, Dock and 返回 App all use its id, and so do the physical devices and the phone's touchpad.
  - `CarActivity` shows it in a full screen `SurfaceView` with a buffer of the display's size. When the surface comes, the display is made the first time and the surface attached; when it goes (Android Auto closes `CarActivity`, disconnects, driving), it is detached with `setVirtualDisplaySurface(null)` and the display and its apps stay until `CarActivity` comes back. The dashboard shows when no app session runs on the car.
  - Touches on the `SurfaceView` go to the display as touchscreen fingers at their own positions, scaled if the window and the display differ, in either input mode (`screenTouch`).
  - The display ends with the session (结束 XDesk, swipe-away, `stop`, injector death), and its apps go to the phone, moved as from an external display and otherwise by the system. The keyboard is the phone's: the display has no system decorations, so the input method falls back to the phone. Sound goes through Android Auto as below.
- **Sound on the car.** Desktop audio plays on the default media route, which Android Auto takes to the car. While the car is the target, the chosen output is not applied (neither `setPreferredDevice` nor the injector's media role, which is cleared if an earlier session left one) and the output chooser says so. The saved choice is kept and applies again with an external display.
- **Car gone** (Android Auto disconnects or closes `CarActivity`): X and the desktop keep running, as when an external display is unplugged, and the phone says so.
- The car's display, XDesk placing a desktop on it, and the apps' display (made, its size and dpi, surface attached and detached) go to logcat (tags `XDeskCar` and `XDesk`). Nothing is written to a file.
