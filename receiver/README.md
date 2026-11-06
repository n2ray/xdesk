# XDesk Receiver

Turns a device without video output (an old phone, a projector, a TV box) into a wireless display for XDesk. XDesk on
the phone streams one of its displays to it over the local network as H.264 video with PCM sound; the receiver only
shows the picture and plays the sound, all input stays on the phone. The protocol is in [PROTOCOL.md](PROTOCOL.md).

## Install

Download `xdesk-receiver.apk` from the [releases](https://github.com/n2ray/xdesk/releases) and install it on the
receiving device, for example with `adb install -r xdesk-receiver.apk`. It runs on Android 5.0 (API 21) and later and
shows up in both phone and TV (leanback) launchers.

## Use

1. Open XDesk Receiver. It shows its name, its address and port (47140), and waits.
2. Open XDesk on the phone, on the same network, and choose this display. It is found over mDNS
   (`_xdesk-display._tcp`); across subnets, type the address shown on the receiver.
3. The first time, the receiver shows a 4-digit code: type it on the phone. After 3 wrong codes the attempt is refused
   and the next one gets a new code. Paired phones connect without a code from then on.

Options (long-press the idle screen, or press Menu / hold OK on a remote): rename the receiver, forget paired phones.
Back exits; leaving the app stops the receiver and tells the phone.

## What is supported

- H.264 (H.265 is offered when the device decodes it at the same size), decoded with low latency and shown as soon
  as each frame is ready, aspect ratio kept.
- The receiver asks for its screen size, scaled down to fit its decoder and at most 1920 on the long side, and tells
  the phone when the screen changes (rotation, HDMI mode).
- 16-bit PCM sound, kept within 150 ms of the picture.
- One phone at a time; another phone gets "busy".

The pairing token travels unencrypted: it keeps other phones on the network from casting by accident, it does not
protect against someone who can read the network.
