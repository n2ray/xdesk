#!/usr/bin/env node
// Checks of the web display's pairing and connection limits against a phone (see receiver/PROTOCOL.md).
//   node review-tests.js HOST lockout|concurrent|flood
// lockout: wrong codes over separate connections lock this address out after 3 failures.
// concurrent: while one pairing waits, a second browser gets "pairing" at once.
// flood: 20 idle TCP connections; those over the limit are closed at once.
// It never sends the phone's real code. Afterwards this address is locked out for a minute.
'use strict';
const net = require('net');
const host = process.argv[2], test = process.argv[3], port = 47150;
const rid = () => require('crypto').randomBytes(16).toString('hex');

function attempt(id, code, holdMs) {
  return new Promise(resolve => {
    const ws = new WebSocket(`ws://${host}:${port}/ws`);
    const seen = [];
    let done = false;
    const finish = () => { if (!done) { done = true; try { ws.close(); } catch (e) { } resolve(seen); } };
    ws.onopen = () => ws.send(JSON.stringify({ hello: 1, id, name: 'review-test', token: '', w: 1280, h: 720 }));
    ws.onmessage = e => {
      if (typeof e.data !== 'string') return;
      const s = JSON.parse(e.data).status;
      seen.push(s);
      if (s === 'pair' && code !== null) ws.send(JSON.stringify({ code }));
      else if (s === 'pair' && holdMs) setTimeout(finish, holdMs);
      else if (s === 'wrong') finish();   // one wrong code per connection, then drop: must still count
    };
    ws.onclose = finish;
    ws.onerror = finish;
    setTimeout(finish, 20000);
  });
}

(async () => {
  if (test === 'lockout') {
    for (let i = 1; i <= 4; i++) {
      // 'abcd' can never be the 4-digit code
      console.log('connection', i, await attempt(rid(), 'abcd'));
    }
  } else if (test === 'concurrent') {
    const first = attempt(rid(), null, 8000);
    await new Promise(r => setTimeout(r, 1500));
    console.log('second while the first pairs:', await attempt(rid(), 'abcd'));
    console.log('first:', await first);
  } else if (test === 'flood') {
    const socks = [];
    let closedEarly = 0;
    for (let i = 0; i < 20; i++) {
      const s = net.connect(port, host);
      s.on('close', () => { closedEarly++; });
      s.on('error', () => {});
      socks.push(s);
    }
    await new Promise(r => setTimeout(r, 2000));
    console.log(`20 idle connections: ${closedEarly} closed by the phone within 2 s (expect 20 - 4 per address = 16)`);
    socks.forEach(s => s.destroy());
  }
  process.exit(0);
})();
