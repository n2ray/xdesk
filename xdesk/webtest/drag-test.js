#!/usr/bin/env node
// A mouse drag in a real browser against fake-phone.js (run it with LOG_INPUT=1): does the page report the moves?
//   node drag-test.js [cdp port=9333] [page port=47199]
'use strict';
const cdp = process.argv[2] || 9333, page = process.argv[3] || 47199;
const sleep = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  let targets;
  for (let i = 0; i < 40 && !targets; i++) { try { targets = await (await fetch(`http://127.0.0.1:${cdp}/json`)).json(); } catch (e) { await sleep(250); } }
  const t = targets.find(x => x.type === 'page' && x.url.includes(String(page)));
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  await new Promise(r => { ws.onopen = r; });
  let id = 0;
  const send = (method, params) => ws.send(JSON.stringify({ id: ++id, method, params }));
  const mouse = (type, x, y, buttons, button) => send('Input.dispatchMouseEvent', { type, x, y, buttons, button, clickCount: type === 'mouseMoved' ? 0 : 1 });
  await sleep(5000);
  mouse('mouseMoved', 300, 200, 0, 'none'); await sleep(100);
  console.log('PRESS'); mouse('mousePressed', 300, 200, 1, 'left'); await sleep(100);
  for (let i = 1; i <= 40; i++) { mouse('mouseMoved', 300 + i * 5, 200 + i * 2, 1, 'left'); await sleep(16); }
  mouse('mouseReleased', 500, 280, 0, 'left'); await sleep(300);
  console.log('DONE'); process.exit(0);
})();
