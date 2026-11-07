#!/usr/bin/env node
// Connects to a phone's web display like the page does and reports what arrives: video, codec config, key frames,
// sound. For pairing it runs the command in $CODE_CMD, which must print the 4-digit code shown on the phone.
//   CODE_CMD='...' node probe-client.js 192.168.1.20 [seconds=15]
'use strict';
const { execSync } = require('child_process');
const host = process.argv[2], secs = +(process.argv[3] || 15);
const port = process.env.PORT || 47150;
const id = process.env.ID || 'a'.repeat(32);
let token = process.env.TOKEN || '';
const ws = new WebSocket(`ws://${host}:${port}/ws`);
ws.binaryType = 'arraybuffer';
const c = { formats: 0, frames: 0, configs: 0, keys: 0, bytes: 0, audioFormat: null, audioMsgs: 0, audioBytes: 0, audioPeak: 0, pings: 0, bye: 0 };
let live = false, t0 = 0;
const msg = (type, payload = new Uint8Array(0)) => { const b = new Uint8Array(5 + payload.length); b[0] = type; new DataView(b.buffer).setUint32(1, payload.length); b.set(payload, 5); return b; };
ws.onopen = () => ws.send(JSON.stringify({ hello: 1, id, name: 'probe', token, w: 1280, h: 720 }));
ws.onmessage = e => {
  if (typeof e.data === 'string') {
    const m = JSON.parse(e.data);
    console.log('status', JSON.stringify(m));
    if (m.status === 'pair' || m.status === 'wrong') {
      const code = execSync(process.env.CODE_CMD, { encoding: 'utf8' }).trim();
      console.log('code from phone:', code);
      ws.send(JSON.stringify({ code }));
    } else if (m.status === 'ok') {
      if (m.token) console.log('TOKEN=' + m.token);
      live = true; t0 = Date.now();
      setInterval(() => ws.send(msg(5)), 2000);
      setTimeout(() => ws.send(msg(20, new Uint8Array([0, 10, 0, 10]))), 2000);
      setTimeout(() => { console.log(JSON.stringify(c)); ws.send(msg(6)); ws.close(); process.exit(0); }, secs * 1000);
    }
    return;
  }
  const v = new DataView(e.data), type = v.getUint8(0), len = v.getUint32(1);
  if (type === 1) { c.formats++; console.log('VIDEO_FORMAT', v.getUint16(9) + 'x' + v.getUint16(11)); }
  else if (type === 2) { c.frames++; c.bytes += len; const f = v.getUint8(13); if (f & 1) c.configs++; if (f & 2) c.keys++; }
  else if (type === 3) c.audioFormat = [v.getUint32(5), v.getUint8(9), v.getUint8(10)];
  else if (type === 4) { c.audioMsgs++; c.audioBytes += len - 8; for (let i = 13; i + 1 < 5 + len; i += 64) c.audioPeak = Math.max(c.audioPeak, Math.abs(v.getInt16(i, true))); }
  else if (type === 5) c.pings++;
  else if (type === 6) { c.bye++; }
};
ws.onclose = e => { if (!live) console.log('closed before ok', e.code); };
ws.onerror = e => console.log('error', e.message || e.type);
