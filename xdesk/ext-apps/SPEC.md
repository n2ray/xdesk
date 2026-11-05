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
