# XDesk Receiver

Turns a device without video output (an old phone, a projector, a TV box) into a wireless display for XDesk. XDesk on
the phone streams one of its displays to it over the local network as H.264 video with PCM sound; the receiver shows
the picture and plays the sound. On a tablet or phone it can also control the phone: touch the picture, or use a
mouse and keyboard connected to it. The protocol is in [PROTOCOL.md](PROTOCOL.md).

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

Options (long-press the idle screen, or press Menu / hold OK on a remote): rename the receiver, forget paired phones,
turn controlling the phone from this device on or off.
Back exits; leaving the app stops the receiver and tells the phone.

## Controlling the phone

On by default where there is a touch screen; turn it on in the options for a TV box with an air mouse. While it is on:

- Tap the picture to click there, drag to drag. Long-press or tap with two fingers to right-click, move two fingers
  to scroll.
- A mouse points and clicks where it is on the picture, and its wheel scrolls. A keyboard types on the phone, Esc and
  shortcuts included.
- The handle on the right edge opens Keyboard (this device's input method, Chinese included), Back, Home and
  Disconnect. Back on this device is Back on the phone; to leave, use Disconnect or go home.
- Text copied on either side can be pasted on the other, and files sent from the phone with *Send to computer or
  tablet* go to Downloads (on Android 9 and earlier, to this app's own Download folder).
- Disconnecting here, or leaving the app, ends the stream; the phone connects here only when you choose this
  receiver in XDesk, and a Mac plugged into the phone meanwhile does not take the stream over.

## What is supported

- H.264 (H.265 is offered when the device decodes it at the same size), decoded with low latency and shown as soon
  as each frame is ready, aspect ratio kept.
- The receiver asks for its screen size, scaled down to fit its decoder and at most 1920 on the long side, and tells
  the phone when the screen changes (rotation, HDMI mode).
- 16-bit PCM sound, kept within 150 ms of the picture.
- One phone at a time; another phone gets "busy".

The pairing token travels unencrypted: it keeps other phones on the network from casting by accident, it does not
protect against someone who can read the network.
