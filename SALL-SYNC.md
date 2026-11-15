# Keeping XDesk's Mac support in step with Sall

XDesk shows a Mac through [Sill](https://github.com/Saffsanity/sill) on the Mac. Its client code is a port of
Sall, the Android client in the owner's fork (github.com/n2ray/sall, `AndroidClient/`), which follows upstream
Sill. This file says which Sall commit each part follows and how to see what changed since.

**Last compared: Sall `3d17c0e` (2026-11-13, "Recover from HEVC decoder failures"); the round trip and the audio mute follow the checkout's HEAD `ff5d1f0d` (2026-11-13, "Guard oversized HEVC streams on Android").**

## What follows what

| XDesk (`lorie-app/src/main/java/com/termux/x11/`) | Sall / Sill source |
|---|---|
| `mac/MacVideo.java` | `VideoDecoderPolicy.java` (software HEVC decoders after the platform's fails, as of 3d17c0e), `Hevc.java` |
| `mac/SillWire.java` | `AndroidClient/…/StreamWire.java`, `Sources/StreamProtocol/StreamMessage.swift` (kind numbers) |
| `mac/HostSettings.java` | `Sources/StreamProtocol/HostSettings.swift` (StreamSettings, RunningStream, AwayQuality, LinkReport, HostSettingsState, HostSettingsChange, SettingsChoices, QualityPreset); `HostSettingsOptions.java`; `ConnectionDiagnostics.java` (`advice`, `accepted`) |
| `mac/Rtt.java` | `ConnectionDiagnostics.Rtt` (the samples, the per-second medians, `slow`), fed from `MacClient`'s `PONG` as `MainActivity` case 11 feeds it |
| `MacStreamSettings.java`'s round trip row and slow-link advice (`HostSettings.advice(boolean slow)`) | `MainActivity.refreshDiagnostics` (the readout, the 1 s tick), `ConnectionDiagnostics.advice`'s `olderHost && remote && slow` case. XDesk has no route label (`ConnectionRouteLabel` is not ported), so any address counts as remote: the advice also shows on a slow local link, where it is just as true |
| `mac/AudioMute.java`; `MacStreamSettings.java`'s "mute this device" switch; `MacAudio.setMuted` | `LocalAudioControl.java`, `AudioMutePolicy.java` (the switch, `ACTION_AUDIO_BECOMING_NOISY`, the "mute only if sound played" rule); `MainActivity`'s `soundMuted` preference (XDesk keeps it per Mac in `MacStore.Stream.muted`, beside `sound`), `muteIndex` row, `case 43`'s `localAudio.received` |
| `mac/MacClient.java`, section "Sill's settings": `settingsState`, `changeSetting`, `settingsCleared` | `MainActivity.java`: `case 16`, `changeHostSetting`, the reset on disconnect. Field and method names kept (`pendingSettingsToken`, `pendingSettingsField`, …) |
| `MacStreamSettings.java` (the page) | `MainActivity.settings()`: `refreshInlineHostSettings`, `addHostChoice`, `addHostSegments`, `addHostToggle`, `showQualityMenu`, `refreshDiagnostics`, `applyDiagnosticsAdvice` |
| `mac/AacEldConfig.java` | `AacEldConfig.java`, unchanged but for the package and records written as classes (XDesk builds as Java 9) |
| `mac/MacAudio.java` | wire and decoder setup: `Sources/StreamProtocol/Audio.swift`, `AudioPlayer.java` (`decoderName`, `configure`), `AudioPackets.java` (`parse`, `needsFlush`, `primingFrames`), `AndroidAudioDevice.java` (`open`, `poll`). **The playout is XDesk's own**, see below |
| `mac/MacClient.java`: `helloAudio`, `audioMessage` | `MainActivity.java`: the hello's `audio` (`wantsAudio`), `case 43` |
| `mac/MacClient.java`: `viewportFps` | `ViewportFps.java` (Sall caps at 60 on power saving or away; XDesk has a per-Mac choice instead) |

## To compare later

```sh
cd sall && git fetch && git diff 3d17c0e..origin/main --stat -- \
  Sources/StreamProtocol/HostSettings.swift Sources/StreamProtocol/Audio.swift \
  Sources/StreamProtocol/StreamMessage.swift Sources/SillHost/DeviceSettings.swift \
  AndroidClient/app/src/main/java/io/github/n2ray/sall/{HostSettingsOptions,ConnectionDiagnostics,AacEldConfig,AudioPackets,AudioPlayer,AndroidAudioDevice,ViewportFps}.java
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
  change. A device sends (kind 17) only the field one control changed, as an absolute value, with a `token` that
  only ever grows; the answer carries it as `answering`. A refused value is not an error: the answer simply shows
  the Mac's value (`DeviceSettings.accepted` in SillHost logs "Settings from … refused: …").
- A device may pick only the Mac menu's values (`SettingsChoices`): frame rate limit 60 or 120, resolution Retina
  (2) or Standard (1), quality one of the seven presets. A field the Mac did not report is never shown or sent.
- The frame rate limit is a ceiling for all devices. What one device gets is the rate its viewport (kind 9) asks
  for: `min(limit, max(24, highest rate asked by a connected device))`. XDesk's "Frame rate asked for" is that.
- Link advice ("cannot carry High … suggesting Balanced" in Sill.log) reaches the device as `link` in kind 16
  while the link is behind; the page shows it with a row that applies the suggestion. Round trips that stay high
  on a host from before the away quality get a plain advice row instead (`HostSettings.advice(boolean slow)`).
- Direct Wireless (`directWireless`) is parsed and not shown: it adds AWDL to the Mac's listener for Apple
  devices, and XDesk reaches a Mac by the address typed when pairing.

## Sound

Sall plays the Mac's sound against the video's presentation clock: `AudioRuntime`, `AudioPlayout`,
`AudioDecodeMapping`, `AudioOutputTimeline`, `AudioPresentationClock`, `AudioScheduleJoin`, `AudioStartupSupply`
(about 1500 lines, Java 17), and stops it on any underrun. XDesk shows a frame as soon as it is decoded and has no
such clock, so `MacAudio` plays the sound as it arrives behind a short buffer, like `AudioPlayer` does for the
Linux desktop: 40 ms before the track starts, a 100 ms track, and a backlog over 200 ms is dropped. It goes to the
output of XDesk's sound setting (`AudioRoute`, through `MacClient.audioDevice`). Two switches, both off at first as
in Sall: the Mac's "Send Audio" (`sendAudio`, for every device) and this device's "Play the Mac's sound here"
(`MacStore.Stream.sound`: the hello lists `aac-eld`, so it takes a new connection). Packets are played only while a
screen shows the Mac. This device can also mute itself ("Mute this device", `MacStore.Stream.muted`, off at first,
shown only while it plays the sound): the packets still come and `MacAudio` keeps its decoder in step but plays
nothing, so unmuting is immediate and no new connection; when the wired or Bluetooth headphones the sound goes to
are pulled while it plays, Android's `ACTION_AUDIO_BECOMING_NOISY` mutes it the same way (`AudioMute`), with a
toast, until the switch is turned off. If Sall's lip sync is ever wanted, port those classes behind `MacAudio`'s
`receive`.

## In Sall and not in XDesk

| Sall | XDesk |
|---|---|
| Share clipboard, paste right away, send clipboard (`ClipboardDefaultPolicy`, …) | XDesk syncs the clipboard whenever the Mac offers it, without a switch |
| Reverse scrolling | XDesk's own "Natural scrolling" (all touchpads) |
| Multi-finger Mac gestures, thumbnail hold time, larger picture, picture in picture, the tour, connection tiles, Aa text scale | Sall's own touch interface; XDesk's input and windows are its own (INPUT.md, DISPLAY.md) |
| Pair by QR code or link, ask the Mac for a code | XDesk pairs by address and the 12-digit code (MacSettings) |
