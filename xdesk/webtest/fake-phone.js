#!/usr/bin/env node
// Test the web display page without a phone: serves lorie-app/src/main/assets/web/ and speaks its WebSocket protocol,
// pairing at once and streaming an Annex B H.264 file (made with x264 aud=1, no B-frames) in a loop at its frame rate.
// Pages opened with ?debug send their decoder statistics, which are printed here.
//
//   node fake-phone.js stream.h264 [port=47199] [fps=30]
//   ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30 -t 10 -c:v libx264 -bf 0 -g 60 -x264-params aud=1 -f h264 stream.h264
'use strict';
const http = require('http'), fs = require('fs'), path = require('path'), crypto = require('crypto');

const file = process.argv[2], port = +(process.argv[3] || 47199), fps = +(process.argv[4] || 30);
if (!file) { console.error('usage: node fake-phone.js stream.h264 [port] [fps]'); process.exit(2); }
const web = path.join(__dirname, '../../lorie-app/src/main/assets/web');

// Access units, split at access unit delimiters; parameter sets go out separately as codec config, as from MediaCodec
const data = fs.readFileSync(file);
function nals(b) {
  const out = []; let i = 0, start = -1;
  while (i + 2 < b.length) {
    if (b[i] === 0 && b[i + 1] === 0 && b[i + 2] === 1) { if (start >= 0) out.push(b.subarray(start, i > 0 && b[i - 1] === 0 ? i - 1 : i)); i += 3; start = i; } else i++;
  }
  if (start >= 0) out.push(b.subarray(start));
  return out;
}
const sc = Buffer.from([0, 0, 0, 1]);
let config = [], units = [], cur = null;
for (const n of nals(data)) {
  const t = n[0] & 31;
  if (t === 9) { if (cur && cur.parts.length) units.push(cur); cur = { key: false, parts: [] }; continue; }
  if (t === 7 || t === 8) { if (config.length < 4) config.push(sc, n); continue; }
  if (!cur) cur = { key: false, parts: [] };
  if (t === 5) cur.key = true;
  cur.parts.push(sc, n);
}
if (cur && cur.parts.length) units.push(cur);
config = Buffer.concat(config);
units = units.map(u => ({ key: u.key, data: Buffer.concat(u.parts) }));
const sps = nals(config).find(n => (n[0] & 31) === 7);
console.log(`${units.length} frames, ${units.filter(u => u.key).length} key, profile ${sps[1]} level ${sps[3]}`);
const W = +(process.env.W || 1280), H = +(process.env.H || 720);

function message(type, payload) {
  const b = Buffer.alloc(5 + payload.length);
  b[0] = type; b.writeUInt32BE(payload.length, 1); payload.copy(b, 5);
  return b;
}
function wsFrame(op, payload) {
  const n = payload.length;
  const head = n < 126 ? Buffer.from([0x80 | op, n]) : n < 65536 ? Buffer.from([0x80 | op, 126, n >> 8, n & 255])
    : (() => { const h = Buffer.alloc(10); h[0] = 0x80 | op; h[1] = 127; h.writeBigUInt64BE(BigInt(n), 2); return h; })();
  return Buffer.concat([head, payload]);
}

const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.webmanifest': 'application/manifest+json' };
const server = http.createServer((req, res) => {
  const p = req.url.split('?')[0];
  const f = p === '/' ? 'index.html' : p.slice(1);
  if (f === 'icon.png') { res.writeHead(404); return res.end(); }
  fs.readFile(path.join(web, path.basename(f)), (e, b) => {
    if (e) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'Content-Type': types[path.extname(f)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(b);
  });
});

server.on('upgrade', (req, sock) => {
  const accept = crypto.createHash('sha1').update(req.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  sock.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
  sock.setNoDelay(true);
  let buf = Buffer.alloc(0), timer = null, live = false, inputs = 0, t0 = Date.now();
  const send = (op, b) => { if (!sock.destroyed) sock.write(wsFrame(op, b)); };
  sock.on('data', d => {
    buf = Buffer.concat([buf, d]);
    while (buf.length >= 2) {
      let n = buf[1] & 127, o = 2;
      if (n === 126) { if (buf.length < 4) return; n = buf.readUInt16BE(2); o = 4; } else if (n === 127) { if (buf.length < 10) return; n = Number(buf.readBigUInt64BE(2)); o = 10; }
      if (buf.length < o + 4 + n) return;
      const mask = buf.subarray(o, o + 4), p = Buffer.from(buf.subarray(o + 4, o + 4 + n));
      for (let i = 0; i < n; i++) p[i] ^= mask[i & 3];
      const op = buf[0] & 15;
      buf = buf.subarray(o + 4 + n);
      if (op === 8) { sock.end(); return; }
      if (op === 1) {
        const m = JSON.parse(p.toString());
        if (m.hello) {
          console.log('hello', m.name, m.w + 'x' + m.h);
          send(1, Buffer.from(JSON.stringify({ status: 'ok', token: 'fake', phone: 'fake phone', w: W, h: H })));
          live = true; start();
        } else if (m.debug) console.log('debug', JSON.stringify(m.debug));
      } else if (op === 2 && p.length >= 5) {
        const t = p[0];
        if (t === 8) { console.log('KEY_FRAME requested'); }
        else if (t === 9) { const c = Buffer.alloc(8); c.writeBigUInt64BE(BigInt(Math.round((Date.now() - t0) * 1000))); send(2, message(9, Buffer.concat([p.subarray(5, 13), c]))); }
        else if (t >= 20) { inputs++; if (inputs % 50 === 1) console.log('input type', t, 'count', inputs); }
      }
    }
  });
  sock.on('close', () => { clearInterval(timer); console.log('closed'); });
  sock.on('error', () => {});
  function start() {
    const fmt = Buffer.alloc(8); fmt.writeUInt32BE(0x68323634, 0); fmt.writeUInt16BE(W, 4); fmt.writeUInt16BE(H, 6);
    send(2, message(1, fmt));
    const head = (pts, flags) => { const h = Buffer.alloc(9); h.writeBigUInt64BE(BigInt(pts), 0); h[8] = flags; return h; };
    send(2, message(2, Buffer.concat([head(0, 1), config])));
    let i = 0; t0 = Date.now();
    timer = setInterval(() => {
      const u = units[i % units.length];
      // Loop from the first frame again: it is a key frame
      const pts = Math.round((Date.now() - t0) * 1000);
      send(2, message(2, Buffer.concat([head(pts, u.key ? 2 : 0), u.data])));
      if (i % 25 === 0) { const pcm = Buffer.alloc(3840 * 2); send(2, message(4, Buffer.concat([Buffer.alloc(8), pcm]))); }
      i++;
    }, 1000 / fps);
  }
});
server.listen(port, '0.0.0.0', () => console.log('fake phone on port ' + port));
