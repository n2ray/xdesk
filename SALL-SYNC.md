# Keeping XDesk's Mac support in step with Sall

XDesk shows a Mac through [Sill](https://github.com/Saffsanity/sill) on the Mac. Its client code is a port of
Sall, the owner's own third-party Android client for Sill (github.com/n2ray/sall, `AndroidClient/`; not an official Sill app), which follows upstream
Sill. This file says which Sall commit each part follows and how to see what changed since.

**Last compared: Sall `3d17c0e` (2026-11-13, "Recover from HEVC decoder failures"); the round trip, the audio mute and the Mac's menus (kinds 24, 25 and 27) follow the checkout's HEAD `ff5d1f0d` (2026-11-13, "Guard oversized HEVC streams on Android").**

## What follows what

| XDesk (`lorie-app/src/main/java/com/termux/x11/`) | Sall / Sill source |
|---|---|
| `mac/MacVideo.java` | `VideoDecoderPolicy.java` (software HEVC decoders after the platform's fails, as of 3d17c0e), `Hevc.java`. XDesk's decoder is asynchronous (MediaCodec callbacks on a `THREAD_PRIORITY_DISPLAY` thread), configures at the SPS's size, sets `KEY_OPERATING_RATE` and reports render lag; Sall's is synchronous |
| `mac/MacDiscovery.java`, `mac/HomeDiscoveryMetadata.java`, `mac/RecognitionTag.java` | `MacDiscovery.java`, `HomeDiscoveryMetadata.java`, `RecognitionTag.java` (`DiscoveryEpoch.java`'s ticket guard is the `known` set: a resolution that comes after its service was lost is dropped); `Sources/StreamProtocol/Pairing.swift` (`RecognitionTag`, `HomeDoorTXT`); `Sources/SillHost/DoorPolicy.swift` (`trusts`: a paired key is trusted for `sill/1` at the home door), `StreamServer.swift` (the home listener and its Bonjour TXT). `MacStore.Mac.recognition` keeps the 32-byte key Sill sends once in its pairing answer (`PairResult.recognitionKey`); a Mac paired by an older Sill has none and is dialled at its saved address |
| `mac/SillWire.java` | `AndroidClient/…/StreamWire.java`, `Sources/StreamProtocol/StreamMessage.swift` (kind numbers) |
| `mac/HostSettings.java` | `Sources/StreamProtocol/HostSettings.swift` (StreamSettings, RunningStream, AwayQuality, LinkReport, HostSettingsState, HostSettingsChange, SettingsChoices, QualityPreset); `HostSettingsOptions.java`; `ConnectionDiagnostics.java` (`advice`, `accepted`) |
| `mac/Rtt.java` | `ConnectionDiagnostics.Rtt` (the samples, the per-second medians, `slow`), fed from `MacClient`'s `PONG` as `MainActivity` case 11 feeds it |
| `MacStreamSettings.java`'s round trip row and slow-link advice (`HostSettings.advice(boolean slow)`) | `MainActivity.refreshDiagnostics` (the readout, the 1 s tick), `ConnectionDiagnostics.advice`'s `olderHost && remote && slow` case. XDesk has no route label (`ConnectionRouteLabel` is not ported), so any address counts as remote: the advice also shows on a slow local link, where it is just as true |
| `mac/AudioMute.java`; `MacStreamSettings.java`'s "mute this device" switch; `MacAudio.setMuted` | `LocalAudioControl.java`, `AudioMutePolicy.java` (the switch, `ACTION_AUDIO_BECOMING_NOISY`, the "mute only if sound played" rule); `MainActivity`'s `soundMuted` preference (XDesk keeps it per Mac in `MacStore.Stream.muted`, beside `sound`), `muteIndex` row, `case 43`'s `localAudio.received` |
| `mac/MacClient.java`, section "Sill's settings": `settingsState`, `changeSetting`, `changeAll`/`pumpChanges`, `settingsCleared` | `MainActivity.java`: `case 16`, `changeHostSetting`, the reset on disconnect. Field and method names kept (`pendingSettingsToken`, `pendingSettingsField`, …); a quality mode's fields go together in one `HostSettingsChange`, as `Sources/StreamProtocol/HostSettings.swift` and iOS `HostSettingsLedger.swift` send them |
| `mac/MacMenuState.java` | `MacMenuState.java` (the mirrored menus: version, tokens, the refusals by their `note` words, and the row shaping — `Row`, `Content`, `topRows`, `sections`, `mark`, `clean`; `MacMenuRow.noteText`). Plain Java, tested in /tmp |
| `mac/MacMenuNav.java`; `MacMenus.java`, `MacMenuBar.java`, `MacMenuDialog.java`; `CornerButton.java` (the bell's and the ≡'s shared button) | `MacMenuAdapter.java` (the row: mark, title, key, ›), `MainActivity.java`'s `showMenuRows`/`openMenuRow`/`sendMenuCommand` (the dialogs, the fetch on open, the press, the refusals as note rows, "The menus changed…" and the no-Accessibility words). XDesk's bar is its own overlay (MACMENU.md: the corner button, the push, Ctrl+Alt+Space, the driven pointer's grab as the notification centre's), Sall has no such thing |
| `mac/MacClient.java`, section "quitting an app": `quitApp`, `case 24`, kinds 25 and 27 | `MainActivity.java`: `sendMenuCommand`, `sendMenu`, `case 24` (without the dialog rows); `Sources/StreamProtocol/MacMenu.swift` (kinds 24, 25 and 27, the rules in its header); `Sources/SillHost/MenuMirror.swift`, `MenuReader.swift`, `MenuPolicy.swift` (what each refusal's `note` says — matched to tell them apart) |
| `MacStreamSettings.java` (the page) | `MainActivity.settings()`: `refreshInlineHostSettings`, `addHostChoice`, `addHostSegments`, `addHostToggle`, `showQualityMenu`, `refreshDiagnostics`, `applyDiagnosticsAdvice` |
| `mac/AacEldConfig.java` | `AacEldConfig.java`, unchanged but for the package and records written as classes (XDesk builds as Java 9) |
| `mac/MacAudio.java` | wire and decoder setup: `Sources/StreamProtocol/Audio.swift`, `AudioPlayer.java` (`decoderName`, `configure`), `AudioPackets.java` (`parse`, `needsFlush`, `primingFrames`), `AndroidAudioDevice.java` (`open`, `poll`). **The playout is XDesk's own**, see below; the low-latency track, the prefill-sized buffer and the once-a-second `getTimestamp` backlog drop follow iOS `AudioPlayout.swift`'s bounded buffer |
| `mac/MacClient.java`: `helloAudio`, `audioMessage` | `MainActivity.java`: the hello's `audio` (`wantsAudio`), `case 43` |
| `mac/MacClient.java`: `viewportFps`, `MacActivity.giveSurface`/`onDisplayChanged` | `ViewportFps.java` (Sall caps at 60 on power saving or away; XDesk has a per-Mac choice instead), iOS `StreamClient+Viewport.swift` (`wantedFPS`: the screen's current rate, 60 while away) |
| `mac/SillTls.java`: the cached `SSLContext`/`KeyManagerFactory`, `setTrafficClass(0xA0)`, the home door dial | `Sources/StreamProtocol/RemoteTLS.swift` (TLS 1.3, `sill/1`, the same pin at both doors), `Sources/SillHost/DoorPolicy.swift` |
| `mac/MacClient.java`: `showDesktop`, `stepToDisplay`, `desktopDisplays`; `MacSource.windows()` (one desktop per display, `m:<mac>:desktop:<n>`) | `Sources/StreamProtocol/Switcher.swift` (`desktopDisplays`, `desktopDisplay`, `desktopDisplayNames`), `StreamMessage.swift` (kind 47 `nextDisplay`), `DesktopDisplays.swift` (`button`); Sall `20d40323` (2026-11-16, "Show desktop display navigation for Sill 0.6.2"): `MainActivity`'s list case and the Desktop button's tap. Sall steps one display per tap; XDesk lists every display and steps to the chosen one, a kind 47 per list until `desktopDisplay` is it |
| `mac/PairLink.java` | `PairLink.java` (the sill://pair link: its fields, parsing, address order, name cleaning; records written as classes). `MacPairing.pairLink` still passes no local candidates: the discovery ported for a saved Mac's home door is not used for pairing (pairing is unchanged) |
| `mac/SillTls.java`: `pairQr`, `ask` | `SillConnection.java`: `pairQrIdentity` (kind 19 method "qr": the link's secret is the key), `homePairingCommand` (kind 19 "ask": the Mac answers "shown", "locked", "busy"…, and the remote door "closed" without counting a try) |
| `mac/MacPairing.java`: `pairLink`, `ask`, `pair` with the Mac's key | `MainActivity.java`: `beginPairLink` (dial the link's addresses, pinned to its key), `sendHomeAsk` (ask, then pair with the code against the key the ask saw) |
| `MacSettings.java`: "Scan QR code", the code step's "Ask the Mac for a code", the link's confirmation; `mac/PairScannerActivity.java` | `PairScannerActivity.java`, `MainActivity.java`: `handlePairIntent` (confirm the link before pairing), `openHomePairing`. XDesk's scanner hands the link straight to MacSettings (a static one-shot receiver), never through an Intent, because XDesk has no activity-result plumbing |

## To compare later

```sh
cd sall && git fetch && git diff 3d17c0e..origin/main --stat -- \
  Sources/StreamProtocol/HostSettings.swift Sources/StreamProtocol/Audio.swift \
  Sources/StreamProtocol/StreamMessage.swift Sources/SillHost/DeviceSettings.swift \
  Sources/StreamProtocol/Pairing.swift Sources/StreamProtocol/MacMenu.swift \
  Sources/SillHost/{MenuMirror,MenuReader,MenuPolicy}.swift \
  AndroidClient/app/src/main/java/io/github/n2ray/sall/{HostSettingsOptions,ConnectionDiagnostics,AacEldConfig,AudioPackets,AudioPlayer,AndroidAudioDevice,ViewportFps,MacDiscovery,HomeDiscoveryMetadata,RecognitionTag,DiscoveryEpoch,PairLink,PairScannerActivity,SillConnection,MacMenuState,MacMenuAdapter,MacMenuRow}.java \
  Sources/StreamProtocol/{RemoteTLS,Pairing,HostSettings}.swift Sources/SillHost/{DoorPolicy,Door,StreamServer,StreamCoordinator}.swift \
  iOSClient/{StreamClient+Viewport,HostSettingsLedger,AudioPlayout}.swift
# the settings panel lives in one big file: look at just its functions
git log -p 3d17c0e..origin/main -L '/private void changeHostSetting(String field, Object value, Double/,+16:AndroidClient/app/src/main/java/io/github/n2ray/sall/MainActivity.java'
git diff 3d17c0e..origin/main -- AndroidClient/app/src/main/java/io/github/n2ray/sall/MainActivity.java | grep -n 'hostSettings\|addHost\|QUALITY_'
# the round trip and the audio mute follow the checkout's HEAD ff5d1f0d instead
git diff ff5d1f0d..origin/main -- AndroidClient/app/src/main/java/io/github/n2ray/sall/{ConnectionDiagnostics,LocalAudioControl,AudioMutePolicy}.java
```

A new setting on the Mac shows up as a new optional field of `StreamSettings` and `HostSettingsChange`
(`HostSettings.swift`'s header lists every place it goes). In XDesk it needs: a field and a `value()` case in
`HostSettings.java`, a row in `MacStreamSettings.fill`, and its strings in both `strings_mac.xml`. A new quality
preset is a new entry in `HostSettings.QUALITY_RATES` and in `xd_mac_quality_names` (both languages); until then it
shows as "Custom — N Mbps". After comparing, update the commit at the top of this file.

## How the settings work (so nobody "fixes" it)

- The settings are the **Mac's**: one set for every connected device, saved by Sill.app, changed from its menu too.
  A device does not store and re-send them. XDesk keeps only the last state per Mac (`MacStore.Stream.host`) to
  show while not connected.
- The Mac sends its state (kind 16) after the window list, whenever anything in it changes, and as the answer to a
  change. A device sends (kind 17) the fields one control changed, as absolute values, with a `token` that only
  ever grows; the answer carries it as `answering`. One control changes one field, except a quick quality mode,
  which sends its differing fields together in one message (as iOS's `HostSettingsLedger.pick` does). A refused
  value is not an error: the answer simply shows the Mac's value (`DeviceSettings.accepted` in SillHost logs
  "Settings from … refused: …").
- A device may pick only the Mac menu's values (`SettingsChoices`): frame rate limit 60 or 120, resolution Retina
  (2) or Standard (1), quality one of the seven presets. A field the Mac did not report is never shown or sent.
- The frame rate limit is a ceiling for all devices. What one device gets is the rate its viewport (kind 9) asks
  for: `min(limit, max(24, highest rate asked by a connected device))`. XDesk's "Frame rate asked for" is that.
- Link advice ("cannot carry High … suggesting Balanced" in Sill.log) reaches the device as `link` in kind 16
  while the link is behind; the page shows it with a row that applies the suggestion. Round trips that stay high
  on a host from before the away quality get a plain advice row instead (`HostSettings.advice(boolean slow)`).
- Direct Wireless (`directWireless`) is parsed and not shown: it adds AWDL to the Mac's listener for Apple
  devices, and XDesk reaches a Mac by the address typed when pairing.

## The Mac's menus and quitting apps

Sill's menu protocol (kinds 24, 25 and 27, `MacMenu.swift`) carries the streamed app's menu bar, read on
the Mac over Accessibility: XDesk asks once per connection for the top level (a kind 27 without an id, after
the first window list), and the host then serves one menu's items (kind 27 with an id, its version and the
title shown) and a choice (kind 25). The version is the host's, one for all devices, and an id is good only
within it; a fetch and a choice carry the title this side showed, and the host acts only while the item at
the id still has it. Tokens are this side's, strictly increasing per connection, echoed in `answering`. The
host's refusals come as `note` words (`MenuPolicy.swift`'s `MenuRefusal.note`); `MacMenuState` matches those
words to tell them apart (the menus changed, the app is gone, it is not answering, no Accessibility, too
many requests…).

The protocol serves the menu bar UI (MACMENU.md, `MacMenus`/`MacMenuBar`/`MacMenuDialog`: the ≡ corner
button, the bar overlay, the phone's list) and **quitting an app** (`MacClient.quitApp`, the Running app item's
"Quit", `MacSource.close` on an "app" key; MACMENU.md §8: a window's card only closes it, quitting is the menu's,
⌘Q's and the app item's). Sill can't quit an app on its own, and
its AppInfo has no running flag, so an app with no windows would stay on the Mac unseen — quitting one is
what this is for. quitApp makes the app the streamed app first (one of its windows, or kind 7 with no
window), fetches its first top-level menu (the app menu: the Apple menu is left out), presses the enabled
item whose key is "⌘Q" (the shortcut as the Mac draws it, found whatever the menu's language; only with no
⌘Q item at all, an item whose title starts with "Quit"/"退出"/"Beenden"…), and waits ~5 s for the app's
windows to leave the window list. The stream switch never touches XDesk's own content — only the Mac's
stream changes, and it goes back to what streamed before afterwards; a screen showing this Mac sees the
switch, with a "Quitting…" line. If the app keeps windows (a save dialog, most likely), its newest window
becomes the content and the user is told, on the phone and over the picture, to answer it on the Mac. If
the menus are refused for Accessibility (Sill has none on the Mac), ⌘Q goes as key input to the streamed
app instead (MacInput's raw key path), and the user is told once that allowing Sill Accessibility makes
quitting reliable. A "The menus changed…" answer is retried once, against the new top level the host sends
by itself.

**Limit:** Sill's AppInfo (the kind 5 app list) has no running flag, so XDesk cannot tell an installed app
that runs without windows from one that is not running: the Mac lists all installed apps, and whether one
runs is known only by its windows.

## Sound

Sall plays the Mac's sound against the video's presentation clock: `AudioRuntime`, `AudioPlayout`,
`AudioDecodeMapping`, `AudioOutputTimeline`, `AudioPresentationClock`, `AudioScheduleJoin`, `AudioStartupSupply`
(about 1500 lines, Java 17), and stops it on any underrun. XDesk shows a frame as soon as it is decoded and has no
such clock, so `MacAudio` plays the sound as it arrives behind a short buffer, like `AudioPlayer` does for the
Linux desktop, but with a cushion that adapts to the link (60 ms, 40 ms more after each underrun up to 300 ms, back down after 30 s without one; a track sized to that 300 ms maximum, low-latency mode where the output has it) and, past the cushion plus 250 ms, only the oldest messages dropped, down to the cushion (owner heard gaps over Tailscale, 2026-11-15). Once a second the frames queued in the track (`framesWritten` less the sound card's timestamped position) are held to the cushion plus 40 ms by dropping decoded PCM, as iOS `AudioPlayout` keeps its buffer bounded. It goes to the
output of XDesk's sound setting (`AudioRoute`, through `MacClient.audioDevice`). Two switches: the Mac's "Send Audio"
(`sendAudio`, for every device, off at first) and this device's "Play the Mac's sound here" (`MacStore.Stream.sound`,
saved as `playSound`: the hello lists `aac-eld`, so it takes a new connection). Unlike Sall's, the second is on at
first (owner's choice, 2026-11-15): sound then follows the Mac's switch alone. Each hello logs whether it asks for sound. Packets are played only while a
screen shows the Mac. This device can also mute itself ("Mute this device", `MacStore.Stream.muted`, off at first,
shown only while it plays the sound): the packets still come and `MacAudio` keeps its decoder in step but plays
nothing, so unmuting is immediate and no new connection; when the wired or Bluetooth headphones the sound goes to
are pulled while it plays, Android's `ACTION_AUDIO_BECOMING_NOISY` mutes it the same way (`AudioMute`), with a
toast, until the switch is turned off. If Sall's lip sync is ever wanted, port those classes behind `MacAudio`'s
`receive`.

## XDesk's own additions on top of Sall

Three things Sall does not have, all built on the same kind 16/17 rules as the settings page (only the Mac
menu's values, only fields the Mac reported, one change at a time, the Mac's answer is what counts):

- **Quick quality modes** (`mac/AutoQuality.java`, `MacModeRow`): Sharp / Balanced / Smooth, one control.
  Sharp is the High preset (`QUALITY_RATES[3]`, 25 Mbps; Extreme is more than Wi-Fi or Tailscale links carry) at Retina
  (captureScale 2) with prioritizeSpeed off; Balanced the middle preset (Balanced, `QUALITY_RATES[2]`, 15 Mbps)
  at Retina with speed off; Smooth the lowest (Low, `QUALITY_RATES[0]`, 4 Mbps — what Sill suggests most often
  for a weak link) at the standard resolution (1) with speed on. The control shows which mode the Mac's settings
  match, or Custom when they match none; picking one sends only the fields that differ, together in one
  `HostSettingsChange` with one token, and applies the Mac's one answer (`MacClient.changeAll`). The row sits at the top of MacStreamSettings,
  at the top of a Mac's device page (DeviceSettings) and in a Mac item's card (ItemCard), with a note that the
  settings are the Mac's, so they hold for every connected device; Ctrl+Alt+Q cycles the modes while a Mac is on
  the external screen, flashing the new mode over the picture. A mode needs all three fields, so a host that
  never reported one shows no row.
- **Auto quality** (the machine in `AutoQuality.java`, driven by `MacClient.autoWatch`): a switch per Mac in
  MacStreamSettings ("Lower quality automatically on a weak link", `MacStore.Stream.autoQuality`, on by
  default). While the Mac's link report stays behind (BEHIND_SUGGESTION or BEHIND) for 5 s continuously, XDesk
  applies the Mac's suggestion, or steps one mode down when there is none (or it already matches), and remembers
  the user's mode; a short notice shows over the picture. After the link has kept up for 2 min it steps back up
  one mode, never above the user's mode, with a notice; a raise that goes behind again within 2 min doubles the
  wait before the next raise (up to 16 min), a raise that held for 2 min resets it. A manual pick (the mode row,
  Ctrl+Alt+Q, or any of the page's picture controls) cancels anything pending and becomes the user's mode;
  changes made elsewhere (the Mac's menu, another device) are followed, not fought. Custom settings are never
  raised back to: XDesk cannot legally send a bitrate the Mac's menu does not offer. The machine is plain Java
  and its test runs in /tmp without Android.

## In Sall and not in XDesk

| Sall | XDesk |
|---|---|
| Share clipboard, paste right away, send clipboard (`ClipboardDefaultPolicy`, …) | XDesk syncs the clipboard whenever the Mac offers it, without a switch |
| Reverse scrolling | XDesk's own "Natural scrolling" (all touchpads) |
| Multi-finger Mac gestures, thumbnail hold time, larger picture, picture in picture, the tour, connection tiles, Aa text scale | Sall's own touch interface; XDesk's input and windows are its own (INPUT.md, DISPLAY.md) |
| Sall's menu dialogs themselves (the iPad's bar is its own; XDesk's overlay is MACMENU.md's corner button, push and hotkey) | XDesk's `MacMenuBar` draws the same rows over the external screen, `MacMenuDialog` the same lists on the phone |
| PairingAuthorization, QrPairingAttempt, OverlayPairing* (cancel and remove-while-pairing guards; pair another Mac while a session runs), the paste-link dialog, InlinePairScanner (scan during a session) | XDesk pairs only from the Mac settings page, one attempt at a time, and a Mac paired by QR or link lands in MacStore exactly as one paired by code |
