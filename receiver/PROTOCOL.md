# XDesk wireless display protocol, version 1

XDesk on the phone (the sender) streams one of its displays to XDesk Receiver on another device (a phone without
video output, a projector, a TV box) over the local network. The receiver shows the picture and plays the sound;
with a touch screen, mouse or keyboard it can also send input, as XDesk for a computer does (see below).

## Discovery

- The receiver listens on **TCP port 47140** and registers an mDNS service through `NsdManager`:
  type `_xdesk-display._tcp`, instance name = the receiver's name (the device name), TXT records `id` (the
  receiver's id, see below) and `v` (`1`).
- The phone browses for that type. Where mDNS does not cross the network (another subnet behind NAT), the user types
  the receiver's address; the receiver shows it on its idle screen.
- The **phone connects to the receiver**, never the other way: the receiver may sit in a subnet the phone can reach
  but not the reverse.

## Encoding

Big-endian throughout. A **string** is a u16 byte length followed by that many bytes of UTF-8. Lengths above 1024
bytes are an error.

## Handshake

1. Phone → receiver, `HELLO`:
   - 4 bytes magic `XDSK`
   - u8 version, `2`
   - string phone id (random, made once per XDesk install, 32 hex characters)
   - string phone name (shown on the receiver while pairing and streaming)
   - string phone nonce: 32 random bytes as 64 hex characters, new for each connection
2. Receiver → phone, `CHALLENGE`:
   - u8 status `5`
   - string receiver id
   - string receiver name
   - string receiver nonce (like the phone's)
   - string receiver proof: `proof("receiver")` with the token paired with this phone id, empty if there is none

   The phone looks up the token for that receiver id. Only if it has one and the receiver proof is right does it send
   `ANSWER`, a string `proof("phone")`; otherwise it sends an empty string. The token itself is never sent.

   `proof(label)` is HMAC-SHA256, keyed with the token's UTF-8 bytes, over the UTF-8 of `label \n phone id \n receiver id
   \n phone nonce \n receiver nonce`, as 64 lowercase hex characters.
3. Receiver → phone, `WELCOME`:
   - u8 status: `0` OK, `1` PAIR (a code is now on the receiver's screen), `2` BUSY (another phone is streaming),
     `3` VERSION (unsupported version, sent right after the version byte, instead of `CHALLENGE`), `4` REFUSED (too
     many failed pairings)
   - string receiver id (random, made once per receiver install, 32 hex characters)
   - string receiver name
   - only with status OK:
     - string token: a new token when this connection just paired, otherwise empty (keep the old one)
     - u16 width, u16 height: the size the receiver wants, in pixels, landscape or portrait as its screen is now,
       already capped to what its decoder handles (see below), both even
     - u16 density: the receiver screen's density in dpi, as `DisplayMetrics.densityDpi`
     - u16 refresh rate in frames per second, rounded
     - u8 codecs: bit 0 H.264 (always set), bit 1 H.265, bit 6 with bit 7: not a computer (see below), bit 7 this receiver sends keyboard and mouse input as the web display does (XDesk for a computer)
4. While the status is PAIR, the phone sends `CODE`: a string with the 4 digits the user typed. The receiver answers
   with another `WELCOME`: OK (paired, with a new token), PAIR (wrong code, try again, same code still shown) or
   REFUSED. After 3 wrong codes the receiver answers REFUSED, closes, and shows a new code next time.
   Failed pairings count across connections, a pairing that ends without any code included: 3 lock the address out for
   a minute, 10 from anyone within 10 minutes pause pairing for 10 minutes, and 30 in all until one succeeds close
   pairing until the receiver app is opened again. A phone with a valid answer is not affected.
   Either side may close at any time; the user cancelling on the phone closes the socket.

Pairing: the receiver stores phone id → token (32 random bytes as 64 hex characters) and accepts a right answer
without asking. The phone stores receiver id → token. A wrong or empty answer gets PAIR. The receiver offers "Forget
paired phones". The new token in the OK after a pairing, the codes and the stream all travel unencrypted, so this does
not protect against someone who can read the network. A device that only announces a receiver's id learns nothing
about the token.

Limits: at most 16 connections that have not proved themselves, 4 per address; more are closed at once. The HELLO and
answer must arrive within 10 seconds, and a pairing ends 2 minutes after it began, however slowly bytes come. A
connection holding the receiver is only replaced by a phone with a right answer: its own earlier connection, or one
still pairing. Everything else gets BUSY.

Any status other than OK, PAIR and CHALLENGE ends the connection after it.

## Messages

After an OK, both sides send framed messages: u8 type, u32 payload length, payload. Payloads above 16 MiB are an error.

Phone → receiver:

| Type | Name | Payload |
|---|---|---|
| 1 | VIDEO_FORMAT | u32 codec (`0x68323634` "h264" or `0x68323635` "h265"), u16 width, u16 height. Sent first and again whenever the size changes; the receiver resets its decoder |
| 2 | VIDEO_FRAME | u64 presentation time in µs, u8 flags (bit 0 codec config, bit 1 key frame), then the encoded data |
| 3 | AUDIO_FORMAT | u32 sample rate, u8 channels, u8 encoding (`1` = 16-bit little-endian PCM). Version 1 always sends 48000, 2, 1 |
| 4 | AUDIO_DATA | u64 presentation time in µs, then PCM |
| 5 | PING | empty |
| 6 | BYE | empty: the phone stops on purpose; the receiver goes back to its idle screen at once |

Receiver → phone:

| Type | Name | Payload |
|---|---|---|
| 5 | PING | empty |
| 6 | BYE | empty: the user closed the receiver |
| 7 | SIZE | u16 width, u16 height, u16 density: the receiver's screen changed (rotation, resolution). The phone may follow with a new VIDEO_FORMAT |
| 8 | KEY_FRAME | empty: the receiver lost sync (decoder reset, dropped data) and needs a key frame |

Each side sends a PING after 2 seconds without sending anything, and treats 6 seconds without receiving anything as a
lost connection.

## Behaviour

- **Codec config:** the phone sends the encoder's codec config (SPS/PPS, VIDEO_FRAME with flag bit 0) after every VIDEO_FORMAT, usually only then. A receiver that resets its decoder feeds the last config again before the next key frame; KEY_FRAME may be answered with a key frame alone.
- **Density:** the receiver reports its screen's own `densityDpi`, not scaled to the size it asks for. The phone chooses the display's density itself (XDesk: as for a monitor of that width, 1920 wide is 160 dpi).
- **Video:** H.264 without B-frames; the receiver decodes with low latency and shows each frame as soon as it is
  decoded, not by its time stamp. It keeps the picture's aspect ratio, black around it. If the decoder cannot keep up,
  the receiver may drop frames until the next key frame and send KEY_FRAME.
- **Size the receiver asks for:** its screen's real size, scaled down to fit the decoder's largest supported size
  (`MediaCodecInfo.VideoCapabilities`) and at most 1920 on the long side, keeping the aspect ratio, rounded to even.
- **Audio:** played with `AudioTrack`; when more than 150 ms is queued the receiver drops data to catch up, so sound
  stays with the picture.
- **One phone at a time.** A second phone gets BUSY.
- **Idle screen:** the receiver's name, its address and port, and how to start from XDesk. While pairing, the
  phone's name and the 4-digit code in large digits. While streaming, only the picture.

## Web display (browser as the receiver)

XDesk can also serve the display to a browser (settings → Display → Web display). Here the **phone is the server**:
it listens on **TCP port 47150** for HTTP/1.1, serves the page (`/`, `/xdesk.js`, `/xdesk.css`, `/manifest.webmanifest`,
`/icon.png`) and accepts a WebSocket at `/ws`. A WebSocket whose `Origin` is not the page's own host is refused.
It answers only on the networks the settings list (Wi-Fi, hotspot, USB tethering, Ethernet; IPv4) and on the phone itself; connections on other interfaces (VPNs, mobile data, IPv6) are closed at once. Before pairing at most 16 connections, 4 per address; each must finish its requests within a minute and send its hello within 15 seconds; messages are at most 8 KiB. While the web display is on, XDesk keeps its foreground service, so a browser can connect while the phone is locked.

If a browser's connection is lost without a BYE, the phone keeps the session and its display for 8 seconds. The same browser id with the same picture size then continues it: after `ok` it gets VIDEO_FORMAT, the codec config and a key frame, as at the start.

Pairing, as JSON text messages, before anything else:

1. Browser → phone: `{"hello":1, "id":"<32 hex, made once per browser>", "name":"iPad Safari", "token":"<from an earlier pairing, or empty>", "w":<px>, "h":<px>}`. `w` and `h` are the browser screen's size in device pixels, in its current orientation.
2. Phone → browser, `{"status": ...}`:
   - `ok`, with `token` (new after pairing, otherwise empty: keep the old one), `phone` (its name), `version` (XDesk's; a page that was loaded from another version reloads), `w`, `h` (the size it streams: at most 1920 on the long side and 1088 on the short one, aspect kept, even). The binary protocol starts.
   - `pair`: XDesk shows a 4-digit code on the phone. The browser sends `{"code":"1234"}`; a wrong one gets `wrong` and another try, the third `refused`. Wrong codes count per address across connections, and so does a pairing that ends without a right code (closed, timed out, anything else sent): 3 lock the address out for a minute, 10 from anyone within 10 minutes pause pairing for 10 minutes. A pairing ends 2 minutes after it began.
   - `pairing`: another browser is pairing now; try again later.
   - `ended`, with `reason`: the phone could not start, or it stopped in a way trying again would not help (no injector, no display). The browser shows the reason and does not reconnect by itself. Also sent after a stream, before the phone closes.
   - `busy` (with `name`, where the phone streams now), `closed` (XDesk is not open on the phone), `version`, `refused`: the phone closes the WebSocket.

After `ok` each binary WebSocket message holds exactly one message of the framing above (u8 type, u32 length, payload),
both ways, with the same types, timing and pings. The browser decodes with WebCodecs, or with Media Source
Extensions by wrapping each frame in a fragmented MP4 segment. Browser → phone also has input, which only the web
display sends:

| Type | Name | Payload |
|---|---|---|
| 20 | MOVE | i16 dx, i16 dy: relative pointer movement in the display's pixels |
| 25 | POSITION | u16 x, u16 y: where the pointer is, 0 to 65535 across and down the picture |
| 21 | BUTTON | u8 button (1 left, 2 middle, 3 right), u8 down |
| 22 | SCROLL | i16 dy, i16 dx in wheel steps, dy > 0 down |
| 23 | KEY | u16 Linux input key code, u8 down |
| 24 | TEXT | UTF-8 text from an input method, at most 4096 bytes |
| 10 | POINTER | Phone → browser. u8 1: the phone's touchpad took the pointer, the page hides the computer's until that moves |
| 9 | TIME | With `?debug`, and from a computer.  8 bytes of the browser's own; the phone answers with TIME: those 8 bytes, then u64 its clock in microseconds, the clock of the frames' timestamps |

Input goes where the phone's touchpad and keyboard go: the Termux desktop or the Android app on the display.

## XDesk for a computer

A receiver with bit 7 of its codecs set is XDesk for a computer, or XDesk Receiver on a device that controls the phone. XDesk Receiver also sets bit 6: it is not a computer, so the phone connects to it only when it is chosen, and a computer plugged in over USB while it streams does not take the stream (the phone says so instead). It sends the input messages of the web display, as well as:

| Type | Name | Payload |
|---|---|---|
| 11 | ADDRESSES | Receiver → phone. UTF-8, comma-separated IPv4 addresses where this receiver can also be reached |
| 12 | CLIPBOARD | Both ways. The clipboard as UTF-8 text, at most 256 KiB, when it changes |
| 13 | APPS | Phone → receiver. Lines of key, tab, label: the apps it can switch to |
| 14 | INPUT_MODE | Phone → computer. u8: 1 the Termux desktop is showing, 0 Android apps |
| 27 | ANDROID | Receiver → phone. u8: 1 Back, 2 Home, 3 ask for APPS |
| 28 | CHOOSE | Receiver → phone. A key from APPS |
| 29, 30, 31 | FILE_START, FILE_DATA, FILE_END | Both ways. A file's UTF-8 name, its bytes in chunks, and its end: saved in Downloads. An empty name drops what came of the file so far |

If the connection to such a receiver is lost the phone keeps the session for 8 seconds and connects again: to the address in use, then to the others. Over USB the computer forwards the port with `adb reverse` and sends the broadcast `com.termux.x11.ConnectReceiver` (extras `host`, `port`, `name`), which only the shell user may send. A session on `127.0.0.1` allows a picture up to 2560 by 1600.
