// XDesk web display. The phone serves this page; the WebSocket at /ws first pairs (JSON), then carries the wireless
// display protocol of receiver/PROTOCOL.md, one protocol message per WebSocket message, plus input messages back.
// Video: WebCodecs where the page is a secure context (localhost, https), otherwise MSE with fragmented MP4 made here.
'use strict';
(() => {

// ---------------------------------------------------------------- text
const zh = /^zh/i.test(navigator.language || '');
const T = zh ? {
  connecting: '正在连接手机…', pair: '输入手机上显示的 4 位码', wrong: '码不对，再看一下手机', pairBtn: '连接',
  refused: '输错太多次，请 1 分钟后再试', busy: n => `手机正在投屏到 ${n || '别的设备'}，结束后会自动连上`,
  closed: '请在手机上打开 XDesk，打开后会自动连上', version: '手机上的 XDesk 版本不对，请更新', lost: '连接断开，正在重连…',
  noVideo: '这个浏览器不能播放 H.264 视频流，请换 Safari、Chrome、Edge 或 Firefox', connect: '连接',
  noteHttp: '提示：添加到主屏幕后，可以像 App 一样全屏使用。',
  full: '全屏', kbd: '键盘', sound: '声音', quit: '断开', soundOn: '点一下画面打开声音',
  backBlocked: '已拦住返回，要离开请点工具栏的断开', bye: '已断开。', reconnect: '重新连接',
  lockHint: '点一下画面锁定鼠标，按 Esc 解锁', ended: '手机结束了投屏', leave: '要断开 XDesk 吗？',
  pairing: '另一个浏览器正在配对，稍后自动重试', noPicture: '连上了手机，但几次都没有画面，已停止重连。看一下手机上 XDesk 的提示。',
} : {
  connecting: 'Connecting to the phone…', pair: 'Enter the 4-digit code shown on the phone', wrong: 'Wrong code, look at the phone again', pairBtn: 'Connect',
  refused: 'Too many wrong codes, try again in a minute', busy: n => `The phone is streaming to ${n || 'another device'}; this connects when it ends`,
  closed: 'Open XDesk on the phone; this connects once it is open', version: 'XDesk on the phone does not match, update it', lost: 'Connection lost, reconnecting…',
  noVideo: 'This browser cannot play an H.264 stream. Try Safari, Chrome, Edge or Firefox', connect: 'Connect',
  noteHttp: 'Tip: add this page to the home screen to use it full screen like an app.',
  full: 'Full screen', kbd: 'Keyboard', sound: 'Sound', quit: 'Disconnect', soundOn: 'Tap the picture to turn on sound',
  backBlocked: 'Back is blocked; disconnect from the toolbar to leave', bye: 'Disconnected.', reconnect: 'Connect again',
  lockHint: 'Click the picture to capture the mouse, Esc to release', ended: 'The phone ended the stream', leave: 'Disconnect XDesk?',
  pairing: 'Another browser is pairing; trying again shortly', noPicture: 'Connected several times without a picture, so this stopped trying. Look at XDesk on the phone.',
};

// ---------------------------------------------------------------- protocol
const VIDEO_FORMAT = 1, VIDEO_FRAME = 2, AUDIO_FORMAT = 3, AUDIO_DATA = 4, PING = 5, BYE = 6, SIZE = 7, KEY_FRAME = 8, TIME = 9, POINTER = 10;
const IN_MOVE = 20, IN_BUTTON = 21, IN_SCROLL = 22, IN_KEY = 23, IN_TEXT = 24, IN_ABS = 25;

const $ = id => document.getElementById(id);
const stage = $('stage'), video = $('video'), canvas = $('canvas'), kbd = $('kbd');
const panel = $('panel'), statusEl = $('status'), pairForm = $('pair'), codeIn = $('code'), noteEl = $('note');
const connectBtn = $('b-connect'), bar = $('bar'), hint = $('hint');

let pageVersion = null;
let ws = null, live = false, quitting = false, retryTimer = 0, retryDelay = 1500;
// Keepalive without timers, which hidden tabs run once a minute: answer what the phone sends (item 10)
let lastSent = 0;
// Sessions in a row that ended before any picture: stop after 3 rather than reconnecting forever
let gotPicture = false, noPicture = 0;
let player = null, sound = null, streamW = 0, streamH = 0;

const store = {
  get(k) { try { return localStorage.getItem('xdesk.' + k); } catch (e) { return null; } },
  set(k, v) { try { localStorage.setItem('xdesk.' + k, v); } catch (e) { /* private mode: pair again next time */ } },
};

function browserId() {
  let id = store.get('id');
  if (!/^[0-9a-f]{32}$/.test(id || '')) {
    const b = new Uint8Array(16);
    (window.crypto || window.msCrypto).getRandomValues(b);
    id = Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
    store.set('id', id);
  }
  return id;
}

function browserName() {
  const u = navigator.userAgent;
  const touchMac = /Macintosh/.test(u) && navigator.maxTouchPoints > 1;
  const dev = /iPad/.test(u) || touchMac ? 'iPad' : /iPhone/.test(u) ? 'iPhone' : /Android/.test(u) ? 'Android'
    : /Mac OS X/.test(u) ? 'Mac' : /Windows/.test(u) ? 'Windows' : /CrOS/.test(u) ? 'ChromeOS' : /Linux/.test(u) ? 'Linux' : 'Browser';
  const br = /Edg\//.test(u) ? 'Edge' : /Firefox\//.test(u) ? 'Firefox' : /Chrome\//.test(u) ? 'Chrome' : /Safari\//.test(u) ? 'Safari' : '';
  return br ? dev + ' ' + br : dev;
}
const apple = /Mac|iPad|iPhone/.test(navigator.platform || navigator.userAgent);

function screenSize() {
  let w = screen.width, h = screen.height;
  // iOS reports the portrait size whatever the orientation
  if ((innerWidth > innerHeight) !== (w > h)) [w, h] = [h, w];
  const dpr = Math.min(devicePixelRatio || 1, 3);
  return [Math.round(w * dpr), Math.round(h * dpr)];
}

function show(text, opts = {}) {
  panel.classList.remove('hidden');
  statusEl.textContent = text;
  pairForm.classList.toggle('hidden', !opts.pair);
  connectBtn.classList.toggle('hidden', !opts.button);
  connectBtn.textContent = opts.button || '';
  if (opts.pair) setTimeout(() => codeIn.focus(), 50);
}

function send(type, payload) {
  if (!ws || ws.readyState !== 1) return;
  const n = payload ? payload.byteLength : 0;
  const b = new Uint8Array(5 + n);
  const v = new DataView(b.buffer);
  v.setUint8(0, type);
  v.setUint32(1, n);
  if (n) b.set(payload instanceof Uint8Array ? payload : new Uint8Array(payload), 5);
  ws.send(b.buffer);
  lastSent = performance.now();
}

// ---------------------------------------------------------------- connection
function connect() {
  clearTimeout(retryTimer);
  if (ws) { try { ws.onclose = null; ws.close(); } catch (e) { /* gone */ } }
  quitting = false;
  live = false;
  show(T.connecting);
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(proto + '//' + location.host + '/ws');
  ws.binaryType = 'arraybuffer';
  let lastStatus = null;
  ws.onopen = () => {
    const [w, h] = screenSize();
    ws.send(JSON.stringify({ hello: 1, id: browserId(), name: browserName(), token: store.get('token') || '', w, h }));
  };
  ws.onmessage = e => {
    if (typeof e.data === 'string') {
      let m;
      try { m = JSON.parse(e.data); } catch (x) { return; }
      lastStatus = m.status;
      onStatus(m);
    } else if (live) {
      onMessage(new DataView(e.data));
      // The phone sends at least every 100 ms while streaming: answer from here, a hidden tab's timers may not run
      if (performance.now() - lastSent > 1500) send(PING);
    }
  };
  ws.onclose = () => {
    const wasLive = live;
    live = false;
    stopSession();
    if (quitting) return;
    if (lastStatus === 'refused') { show(T.refused, { button: T.reconnect }); return; }
    if (lastStatus === 'version') { show(T.version, { button: T.reconnect }); return; }
    if (lastStatus === 'busy' || lastStatus === 'closed' || lastStatus === 'pairing') { retryLater(5000); return; }
    if (lastStatus === 'pair' || lastStatus === 'wrong') { show(T.lost, { button: T.reconnect }); return; }
    // Connected but never a picture, again and again: the phone has a lasting problem, stop and say so
    if (wasLive && !gotPicture && ++noPicture >= 3) { noPicture = 0; show(T.noPicture, { button: T.reconnect }); return; }
    show(wasLive ? T.lost : T.connecting);
    retryLater(retryDelay);
    retryDelay = Math.min(retryDelay * 1.5, 8000);
  };
}

function retryLater(ms) {
  clearTimeout(retryTimer);
  retryTimer = setTimeout(() => { if (!document.hidden) connect(); else retryLater(1000); }, ms);
}

function onStatus(m) {
  switch (m.status) {
    case 'pair': show(T.pair, { pair: true }); codeIn.value = ''; break;
    case 'wrong': show(T.wrong, { pair: true }); codeIn.value = ''; break;
    case 'refused': show(T.refused); break;
    case 'busy': show(T.busy(m.name)); break;
    case 'closed': show(T.closed); break;
    case 'version': show(T.version); break;
    case 'pairing': show(T.pairing); break;
    // An ending that reconnecting would not help (no injector, no display, no foreground service)
    case 'ended': quitting = true; show(m.reason || T.ended, { button: T.reconnect }); break;
    case 'ok':
      if (m.token) store.set('token', m.token);
      // XDesk was updated while this page stayed open: its page may no longer fit
      if (m.version) {
        if (pageVersion && m.version !== pageVersion) { location.reload(); return; }
        pageVersion = m.version;
      }
      streamW = m.w; streamH = m.h;
      stage.classList.remove('phone-pointer');
      retryDelay = 1500;
      gotPicture = false;
      startSession();
      break;
  }
}

pairForm.addEventListener('submit', e => {
  e.preventDefault();
  const code = codeIn.value.replace(/\D/g, '');
  if (code.length !== 4 || !ws) return;
  ws.send(JSON.stringify({ code }));
  codeIn.blur();
  show(T.connecting);
});
codeIn.addEventListener('input', () => { if (codeIn.value.replace(/\D/g, '').length === 4) pairForm.requestSubmit ? pairForm.requestSubmit() : pairForm.dispatchEvent(new Event('submit')); });
connectBtn.addEventListener('click', () => { retryDelay = 1500; connect(); });

// ?debug: statistics on screen, and sent to the phone as text messages (it ignores them, a test server logs them)
const DEBUG = /[?&]debug\b/.test(location.search);
const stats = { path: '', framesIn: 0, keys: 0, configs: 0, decoded: 0, errors: 0, lastError: '', audio: 0 };
// Latency in ms, medians over each second, only with ?debug. The phone's clock is the frames' timestamps (capture), its
// offset from this one comes from TIME messages, keeping the sample with the shortest round trip, as NTP does.
// rttMs: to the phone and back. arriveMs: capture to here (encoding, sending, network). decodeMs: here to handed to the
// display. totalMs: capture to handed to the display. The monitor's own delay comes on top and cannot be seen from here.
const lat = { times: [], arrivals: new Map(), arrive: [], decode: [], total: [] };
function askTime() {
  const b = new DataView(new ArrayBuffer(8));
  b.setFloat64(0, performance.now());
  send(TIME, new Uint8Array(b.buffer));
}
function onTime(v) {
  const t0 = v.getFloat64(5), t1 = performance.now();
  const phone = Number(v.getBigUint64(13)) / 1000;
  lat.times.push({ rtt: t1 - t0, offset: phone - (t0 + t1) / 2 });
  if (lat.times.length > 16) lat.times.shift();
}
function bestTime() { return lat.times.reduce((a, b) => !a || b.rtt < a.rtt ? b : a, null); }
// How long ago the phone captured this frame, at the moment `at` on this page's clock
function ageOf(pts, at) { const t = bestTime(); return t ? at - (pts / 1000 - t.offset) : null; }
function noteArrival(pts) {
  const now = performance.now();
  lat.arrivals.set(pts, now);
  if (lat.arrivals.size > 240) lat.arrivals.delete(lat.arrivals.keys().next().value);
  const age = ageOf(pts, now);
  if (age !== null) lat.arrive.push(age);
}
function noteShown(pts, at) {
  const came = lat.arrivals.get(pts);
  if (came === undefined) return;
  lat.arrivals.delete(pts);
  lat.decode.push(at - came);
  const age = ageOf(pts, at);
  if (age !== null) lat.total.push(age);
}
function median(a) {
  if (!a.length) return null;
  const s = a.slice().sort((x, y) => x - y);
  a.length = 0;
  return Math.round(s[s.length >> 1]);
}
function noteError(e) { stats.errors++; stats.lastError = String(e && e.message || e).slice(0, 160); }
let debugEl = null;
function copyText(text, button) {
  const done = () => { button.textContent = 'Copied'; setTimeout(() => { button.textContent = 'Copy'; }, 1200); };
  if (navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(text).then(done, () => {}); return; }
  // Plain HTTP has no clipboard API
  const t = document.createElement('textarea');
  t.value = text;
  document.body.appendChild(t);
  t.select();
  try { if (document.execCommand('copy')) done(); } catch (e) { /* the text stays selectable */ }
  t.remove();
}
function debugTick() {
  if (player && player.extra) Object.assign(stats, player.extra());
  const best = bestTime();
  Object.assign(stats, { rttMs: best ? Math.round(best.rtt) : null, arriveMs: median(lat.arrive), decodeMs: median(lat.decode), totalMs: median(lat.total) });
  askTime();
  const s = JSON.stringify(stats);
  if (!debugEl) {
    // Outside the stage, so a click here does not take the mouse. It is rewritten every second, hence the button
    const box = document.createElement('div');
    box.style.cssText = 'position:fixed;left:6px;bottom:6px;padding:6px 8px;background:rgba(0,0,0,.7);z-index:20;max-width:90vw';
    const copy = document.createElement('button');
    copy.textContent = 'Copy';
    copy.style.cssText = 'font:12px monospace;margin-bottom:4px';
    copy.addEventListener('click', () => copyText(JSON.stringify(stats), copy));
    debugEl = document.createElement('pre');
    debugEl.style.cssText = 'margin:0;font:12px monospace;color:#7f7;white-space:pre-wrap;-webkit-user-select:text;user-select:text';
    box.append(copy, debugEl);
    document.body.appendChild(box);
  }
  debugEl.textContent = s.replace(/,/g, ', ');
  if (ws && ws.readyState === 1) ws.send(JSON.stringify({ debug: stats }));
}

let pingTimer = 0, debugTimer = 0;
function startSession() {
  live = true;
  panel.classList.add('hidden');
  if (!player) {
    player = makePlayer();
    if (!player) { show(T.noVideo); ws.close(); return; }
  }
  if (!sound) sound = new Sound();
  sound.unlock();
  pingTimer = setInterval(() => send(PING), 2000);
  if (DEBUG) { stats.path = player.name; debugTimer = setInterval(debugTick, 1000); askTime(); }
  trapHistory();
  flash(matchMedia('(pointer: fine)').matches ? T.lockHint : '', 3500);
  showBar(4000);
}

function stopSession() {
  clearInterval(pingTimer);
  clearInterval(debugTimer);
  releaseAll();
  if (document.pointerLockElement) document.exitPointerLock();
  if (player) player.reset();
  if (sound) sound.reset();
}

function onMessage(v) {
  const type = v.getUint8(0), len = v.getUint32(1);
  const p = new Uint8Array(v.buffer, v.byteOffset + 5, len);
  switch (type) {
    case VIDEO_FORMAT: {
      const w = v.getUint16(9), h = v.getUint16(11);
      streamW = w; streamH = h;
      player.format(w, h);
      break;
    }
    case VIDEO_FRAME: {
      const pts = Number(v.getBigUint64 ? v.getBigUint64(5) : v.getUint32(5) * 4294967296 + v.getUint32(9));
      const flags = v.getUint8(13);
      stats.framesIn++;
      if (!gotPicture) { gotPicture = true; noPicture = 0; }
      if (flags & 1) stats.configs++;
      if (flags & 2) stats.keys++;
      if (DEBUG && !(flags & 1)) noteArrival(pts);
      try {
        player.frame(flags, pts, new Uint8Array(v.buffer, v.byteOffset + 14, len - 9));
      } catch (e) { noteError(e); }
      break;
    }
    case AUDIO_FORMAT: break;
    case AUDIO_DATA: sound.data(new Uint8Array(v.buffer, v.byteOffset + 13, len - 8)); break;
    case PING: break;
    case TIME: if (len >= 16) onTime(v); break;
    case POINTER: stage.classList.toggle('phone-pointer', len >= 1 && p[0] === 1); break;
    case BYE: quitting = true; show(T.ended, { button: T.reconnect }); ws.close(); break;
  }
}

// At most once a second, as XDesk Receiver: a decoder behind would otherwise ask with every frame, and a run of
// key frames (bigger, slower to decode) only puts it further behind
let lastKeyRequest = 0;
function needKeyFrame() {
  const now = performance.now();
  if (now - lastKeyRequest < 1000) return;
  lastKeyRequest = now;
  send(KEY_FRAME);
}

// ---------------------------------------------------------------- H.264 helpers
function nalUnits(b) {
  // Annex B: split at 00 00 01 / 00 00 00 01
  const out = [];
  let i = 0, start = -1;
  while (i + 2 < b.length) {
    if (b[i] === 0 && b[i + 1] === 0 && (b[i + 2] === 1 || (b[i + 2] === 0 && i + 3 < b.length && b[i + 3] === 1))) {
      const sc = b[i + 2] === 1 ? 3 : 4;
      if (start >= 0) out.push(b.subarray(start, i));
      i += sc;
      start = i;
    } else i++;
  }
  if (start >= 0) out.push(b.subarray(start));
  else if (b.length) out.push(b);
  return out.map(n => { let e = n.length; while (e > 0 && n[e - 1] === 0) e--; return n.subarray(0, e); }).filter(n => n.length);
}
const hex2 = x => x.toString(16).padStart(2, '0');
function codecOf(sps) { return 'avc1.' + hex2(sps[1]) + hex2(sps[2]) + hex2(sps[3]); }

// ---------------------------------------------------------------- video: WebCodecs
class CodecsPlayer {
  constructor() {
    this.ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
    canvas.classList.add('on');
    this.decoder = null; this.config = null; this.codec = null; this.waitKey = true;
    this.name = 'webcodecs'; this.decoded = 0; this.failures = 0;
  }
  extra() { return { decoded: this.decoded, codec: this.codec, queue: this.decoder ? this.decoder.decodeQueueSize : -1, state: this.decoder ? this.decoder.state : 'none' }; }
  format(w, h) { canvas.width = w; canvas.height = h; this.reset(); }
  reset() {
    if (this.decoder && this.decoder.state !== 'closed') { try { this.decoder.close(); } catch (e) { /* closed */ } }
    this.decoder = null; this.waitKey = true;
  }
  frame(flags, pts, data) {
    if (flags & 1) {
      this.config = data.slice();
      const sps = nalUnits(this.config).find(n => (n[0] & 31) === 7);
      if (sps) this.codec = codecOf(sps);
      return;
    }
    const key = (flags & 2) !== 0;
    if (!this.decoder) {
      if (!key || !this.config || !this.codec) { needKeyFrame(); return; }
      this.decoder = new VideoDecoder({
        output: f => {
          this.decoded++;
          this.ctx.drawImage(f, 0, 0, canvas.width, canvas.height);
          if (DEBUG) noteShown(f.timestamp, performance.now());
          f.close();
        },
        error: e => {
          noteError(e);
          this.decoder = null; this.waitKey = true;
          if (++this.failures >= 2 && this.decoded === 0 && fallBackToMse(this.config)) return;
          needKeyFrame();
        },
      });
      try {
        this.decoder.configure({ codec: this.codec, optimizeForLatency: true });
      } catch (e) {
        noteError(e);
        this.decoder = null;
        if (!fallBackToMse(this.config)) needKeyFrame();
        return;
      }
    }
    if (this.decoder.decodeQueueSize > 8) { this.waitKey = true; needKeyFrame(); }
    if (this.waitKey && !key) return;
    this.waitKey = false;
    let payload = data;
    if (key) { payload = new Uint8Array(this.config.length + data.length); payload.set(this.config); payload.set(data, this.config.length); }
    try {
      this.decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: pts, data: payload }));
    } catch (e) { noteError(e); this.reset(); needKeyFrame(); }
  }
  rect() { return canvas.getBoundingClientRect(); }
}

// ---------------------------------------------------------------- video: MSE with fragmented MP4
const u32 = n => [n >>> 24 & 255, n >>> 16 & 255, n >>> 8 & 255, n & 255];
const u16 = n => [n >>> 8 & 255, n & 255];
const str = s => Array.from(s, c => c.charCodeAt(0));
function box(type, ...parts) {
  let len = 8;
  const arrs = parts.map(p => p instanceof Uint8Array ? p : new Uint8Array(p));
  arrs.forEach(a => len += a.length);
  const b = new Uint8Array(len);
  b.set(u32(len), 0); b.set(str(type), 4);
  let o = 8;
  arrs.forEach(a => { b.set(a, o); o += a.length; });
  return b;
}
const MATRIX = [0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 64, 0, 0, 0];
const TIMESCALE = 1000000;

function initSegment(w, h, sps, pps) {
  const avcC = box('avcC', [1, sps[1], sps[2], sps[3], 0xff, 0xe1, ...u16(sps.length)], sps, [1, ...u16(pps.length)], pps);
  const avc1 = box('avc1', [0, 0, 0, 0, 0, 0, 0, 1, ...new Array(16).fill(0), ...u16(w), ...u16(h),
    0, 0x48, 0, 0, 0, 0x48, 0, 0, 0, 0, 0, 0, 0, 1, ...new Array(32).fill(0), 0, 0x18, 0xff, 0xff], avcC);
  const stbl = box('stbl', box('stsd', [0, 0, 0, 0, 0, 0, 0, 1], avc1),
    box('stts', [0, 0, 0, 0, 0, 0, 0, 0]), box('stsc', [0, 0, 0, 0, 0, 0, 0, 0]),
    box('stsz', [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]), box('stco', [0, 0, 0, 0, 0, 0, 0, 0]));
  const minf = box('minf', box('vmhd', [0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0]),
    box('dinf', box('dref', [0, 0, 0, 0, 0, 0, 0, 1], box('url ', [0, 0, 0, 1]))), stbl);
  const mdia = box('mdia', box('mdhd', [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ...u32(TIMESCALE), 0, 0, 0, 0, 0x55, 0xc4, 0, 0]),
    box('hdlr', [0, 0, 0, 0, 0, 0, 0, 0, ...str('vide'), ...new Array(12).fill(0), ...str('XDesk'), 0]), minf);
  const tkhd = box('tkhd', [0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ...MATRIX, ...u16(w), 0, 0, ...u16(h), 0, 0]);
  const mvhd = box('mvhd', [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ...u32(TIMESCALE), 0, 0, 0, 0, 0, 1, 0, 0, 1, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ...MATRIX, ...new Array(24).fill(0), 0, 0, 0, 2]);
  const mvex = box('mvex', box('trex', [0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]));
  return concat(box('ftyp', str('iso5'), [0, 0, 2, 0], str('iso5iso6mp41')), box('moov', mvhd, box('trak', tkhd, mdia), mvex));
}

function mediaSegment(seq, decodeTime, duration, key, sample) {
  const hi = Math.floor(decodeTime / 4294967296), lo = decodeTime >>> 0;
  const flags = key ? [2, 0, 0, 0] : [1, 1, 0, 0];
  const make = offset => box('moof', box('mfhd', [0, 0, 0, 0, ...u32(seq)]),
    box('traf', box('tfhd', [0, 2, 0, 0, 0, 0, 0, 1]), box('tfdt', [1, 0, 0, 0, ...u32(hi), ...u32(lo)]),
      box('trun', [0, 0, 7, 1, 0, 0, 0, 1, ...u32(offset), ...u32(duration), ...u32(sample.length), ...flags])));
  const size = make(0).length;
  return concat(make(size + 8), box('mdat', sample));
}

function concat(...a) {
  let n = 0; a.forEach(x => n += x.length);
  const b = new Uint8Array(n); let o = 0;
  a.forEach(x => { b.set(x, o); o += x.length; });
  return b;
}

class MsePlayer {
  constructor(MS) {
    this.MS = MS;
    video.classList.add('on');
    video.disableRemotePlayback = true;
    video.addEventListener('error', () => noteError('video: ' + (video.error && (video.error.code + ' ' + video.error.message))));
    this.w = 0; this.h = 0; this.name = 'mse';
    this.reset();
    if (DEBUG && video.requestVideoFrameCallback) {
      const shown = (now, m) => {
        if (this.base >= 0) noteShown(Math.round(this.base + m.mediaTime * TIMESCALE), m.presentationTime);
        video.requestVideoFrameCallback(shown);
      };
      video.requestVideoFrameCallback(shown);
    }
  }
  extra() {
    const q = video.getVideoPlaybackQuality ? video.getVideoPlaybackQuality() : {};
    const b = video.buffered;
    return { decoded: q.totalVideoFrames || 0, dropped: q.droppedVideoFrames || 0, codec: this.codec, ready: video.readyState,
      paused: video.paused, t: +video.currentTime.toFixed(2), end: b.length ? +b.end(b.length - 1).toFixed(2) : -1, queue: this.queue.length };
  }
  format(w, h) { this.w = w; this.h = h; this.reset(); }
  reset() {
    this.sb = null; this.queue = []; this.pending = null; this.lastT = -1; this.lastMoved = performance.now(); this.base = -1; this.seq = 1; this.waitKey = true; this.codec = null;
    if (this.ms && this.ms.readyState === 'open') { try { this.ms.endOfStream(); } catch (e) { /* closed */ } }
    this.ms = null;
    if (video.src) { URL.revokeObjectURL(video.src); video.removeAttribute('src'); video.srcObject = null; try { video.load(); } catch (e) { /* none */ } }
  }
  open(codec, init) {
    this.codec = codec;
    const ms = new this.MS();
    this.ms = ms;
    const type = 'video/mp4; codecs="' + codec + '"';
    const ready = () => {
      if (this.ms !== ms) return;
      try { this.sb = ms.addSourceBuffer(type); } catch (e) { noteError(e); show(T.noVideo); return; }
      this.sb.mode = 'segments';
      this.sb.addEventListener('error', () => noteError('sourcebuffer error'));
      this.sb.addEventListener('updateend', () => this.pump());
      ms.duration = Infinity;
      this.queue.unshift(init);
      this.pump();
    };
    ms.addEventListener('sourceopen', ready, { once: true });
    if (this.MS !== window.MediaSource && 'srcObject' in video) video.srcObject = ms;
    else video.src = URL.createObjectURL(ms);
    video.play().catch(() => { /* starts on the first tap */ });
  }
  frame(flags, pts, data) {
    if (flags & 1) {
      const nals = nalUnits(data);
      const sps = nals.find(n => (n[0] & 31) === 7), pps = nals.find(n => (n[0] & 31) === 8);
      if (!sps || !pps) return;
      const codec = codecOf(sps);
      this.reset();
      this.init = initSegment(this.w || streamW, this.h || streamH, sps, pps);
      this.open(codec, this.init);
      return;
    }
    const key = (flags & 2) !== 0;
    if (!this.ms) { needKeyFrame(); return; }
    if (this.stalled()) {
      // Frames keep coming but the picture stopped: the only way back is a new MediaSource and a key frame
      noteError('mse stalled, restarting');
      const codec = this.codec, init = this.init;
      this.reset();
      this.open(codec, init);
      needKeyFrame();
      return;
    }
    if (this.waitKey && !key) return;
    this.waitKey = false;
    // Length-prefixed NAL units, without parameter sets and delimiters
    const parts = [];
    for (const n of nalUnits(data)) {
      const t = n[0] & 31;
      if (t === 7 || t === 8 || t === 9) continue;
      parts.push(new Uint8Array(u32(n.length)), n);
    }
    if (!parts.length) return;
    const sample = concat(...parts);
    if (this.base < 0) this.base = pts;
    // A frame's duration is known when the next one comes: keep one back, so there are no gaps in the timeline
    const prev = this.pending;
    this.pending = { pts, key, sample };
    if (prev) this.append(prev, Math.max(1000, pts - prev.pts));
    if (this.queue.length > 30) { this.waitKey = true; this.pending = null; needKeyFrame(); }
  }
  // Nothing buffered although frames went in, or the position stuck for a while with data ahead of it
  stalled() {
    const now = performance.now(), t = video.currentTime;
    if (t !== this.lastT) { this.lastT = t; this.lastMoved = now; }
    if (!this.sb || this.sb.updating || this.seq < 8 || !this.ms || this.ms.readyState !== 'open') { this.lastMoved = Math.max(this.lastMoved || 0, now - 1500); return false; }
    const b = video.buffered;
    if (now - this.lastMoved < 2500) return false;
    return !b.length || b.end(b.length - 1) - t > 0.1;
  }
  append(f, duration) {
    this.queue.push(mediaSegment(this.seq++, f.pts - this.base, duration, f.key, f.sample));
    this.pump();
  }
  pump() {
    const sb = this.sb;
    if (!sb || sb.updating) return;
    const b = video.buffered;
    if (b.length) {
      const end = b.end(b.length - 1);
      // Stay at the live edge: the picture must follow the mouse, not play smoothly behind it
      if (end - video.currentTime > 0.25) video.currentTime = end - 0.04;
      if (video.paused) video.play().catch(() => {});
      if (this.seq % 300 === 0 && video.currentTime > 10) { try { sb.remove(0, video.currentTime - 5); return; } catch (e) { /* busy */ } }
    }
    const next = this.queue.shift();
    if (!next) return;
    try { sb.appendBuffer(next); } catch (e) {
      noteError(e);
      this.queue = []; this.waitKey = true;
      if (e.name === 'QuotaExceededError') { try { sb.remove(0, Math.max(0, video.currentTime - 1)); } catch (x) { /* busy */ } }
      needKeyFrame();
    }
  }
  rect() { return video.getBoundingClientRect(); }
}

// WebCodecs only where it really decodes H.264: Chromium builds without proprietary codecs have the API, refuse
// H.264 there, and still play it through MSE. Decided once at start, before connecting.
let webCodecsH264 = false;
const detected = (async () => {
  if (!window.isSecureContext || !('VideoDecoder' in window) || !('EncodedVideoChunk' in window)) return;
  try {
    const r = await Promise.all(['avc1.42e01f', 'avc1.640028'].map(codec => VideoDecoder.isConfigSupported({ codec, optimizeForLatency: true })));
    webCodecsH264 = r.every(x => x.supported);
  } catch (e) { webCodecsH264 = false; }
})();

function mseClass() {
  const MS = window.ManagedMediaSource || window.MediaSource || window.WebKitMediaSource;
  return MS && MS.isTypeSupported && MS.isTypeSupported('video/mp4; codecs="avc1.42e01f"') ? MS : null;
}
function makePlayer() {
  if (webCodecsH264) return new CodecsPlayer();
  const MS = mseClass();
  return MS ? new MsePlayer(MS) : null;
}
// WebCodecs refused this stream after all: continue with MSE, from the codec config and the next key frame
function fallBackToMse(config) {
  const MS = mseClass();
  if (!MS || !player || player.name !== 'webcodecs') return false;
  canvas.classList.remove('on');
  player = new MsePlayer(MS);
  player.format(streamW, streamH);
  if (config) player.frame(1, 0, config);
  if (DEBUG) stats.path = 'mse (fallback)';
  needKeyFrame();
  return true;
}

// The picture's rectangle inside the element (object-fit: contain), for mapping pointer movement to its pixels
function pictureRect() {
  const r = player ? player.rect() : stage.getBoundingClientRect();
  if (!streamW || !streamH) return r;
  const s = Math.min(r.width / streamW, r.height / streamH);
  const w = streamW * s, h = streamH * s;
  return { left: r.left + (r.width - w) / 2, top: r.top + (r.height - h) / 2, width: w, height: h, scale: s };
}

// ---------------------------------------------------------------- sound
class Sound {
  constructor() { this.ctx = null; this.next = 0; this.warned = false; }
  unlock() {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    if (!this.ctx) {
      try { this.ctx = new AC({ sampleRate: 48000, latencyHint: 'interactive' }); } catch (e) { this.ctx = new AC(); }
    }
    if (this.ctx.state !== 'running') this.ctx.resume().catch(() => {});
  }
  reset() { this.next = 0; }
  data(pcm) {
    const c = this.ctx;
    if (!c || c.state !== 'running') {
      if (!this.warned) { this.warned = true; flash(T.soundOn, 4000); }
      return;
    }
    const frames = pcm.length >> 2;
    if (!frames) return;
    const buf = c.createBuffer(2, frames, 48000);
    const l = buf.getChannelData(0), r = buf.getChannelData(1);
    const v = new DataView(pcm.buffer, pcm.byteOffset, frames * 4);
    for (let i = 0; i < frames; i++) {
      l[i] = v.getInt16(i * 4, true) / 32768;
      r[i] = v.getInt16(i * 4 + 2, true) / 32768;
    }
    const now = c.currentTime;
    if (this.next < now + 0.01) this.next = now + 0.06;
    // More than this queued: drop, so sound stays with the picture
    if (this.next - now > 0.25) return;
    const s = c.createBufferSource();
    s.buffer = buf;
    s.connect(c.destination);
    s.start(this.next);
    this.next += frames / 48000;
    $('b-sound').classList.add('on');
  }
}

// ---------------------------------------------------------------- input: mouse
let restX = 0, restY = 0, wheelX = 0, wheelY = 0, moveQueued = false;
const buttonsDown = new Set(), keysDown = new Set();

function i16(n) { return [n >> 8 & 255, n & 255]; }
function queueMove(dx, dy) {
  restX += dx; restY += dy;
  if (moveQueued) return;
  moveQueued = true;
  requestAnimationFrame(flushMove);
}
function flushMove() {
  moveQueued = false;
  let x = Math.trunc(restX), y = Math.trunc(restY);
  if (!x && !y) return;
  restX -= x; restY -= y;
  x = Math.max(-32768, Math.min(32767, x)); y = Math.max(-32768, Math.min(32767, y));
  send(IN_MOVE, new Uint8Array([...i16(x), ...i16(y)]));
}
function sendButton(b, down) {
  if (down) buttonsDown.add(b); else if (!buttonsDown.delete(b)) return;
  flushMove();
  send(IN_BUTTON, new Uint8Array([b, down ? 1 : 0]));
}
function sendScroll(dy, dx) { send(IN_SCROLL, new Uint8Array([...i16(dy), ...i16(dx)])); }
function releaseAll() {
  for (const b of [...buttonsDown]) sendButton(b, false);
  for (const k of [...keysDown]) sendKey(k, false);
}
// Movement in the page's CSS pixels → the display's pixels, so the remote cursor keeps up with the hand
function toRemote() { const r = pictureRect(); return r.scale ? 1 / r.scale : 1; }

const locked = () => document.pointerLockElement === stage;
const BUTTON = [1, 2, 3];   // DOM 0 left, 1 middle, 2 right → X 1, 2, 3

// The computer's own pointer, at its own speed and with no delay: the page sends where it is. ?lock captures the mouse
// instead and sends movement, for games that need it.
const LOCK = /[?&]lock\b/.test(location.search);
if (!LOCK) stage.classList.add('free');
let lastAbs = -1;
function sendAbsolute(e) {
  const r = pictureRect();
  if (!r.width || !r.height) return;
  const x = Math.round(Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * 65535);
  const y = Math.round(Math.max(0, Math.min(1, (e.clientY - r.top) / r.height)) * 65535);
  const both = x * 65536 + y;
  if (both === lastAbs) return;
  lastAbs = both;
  send(IN_ABS, new Uint8Array([x >> 8, x & 255, y >> 8, y & 255]));
}

stage.addEventListener('pointerdown', e => {
  if (!live) return;
  if (sound) sound.unlock();
  if (e.pointerType === 'mouse') {
    e.preventDefault();
    if (!LOCK) {
      stage.classList.remove('phone-pointer');
      sendAbsolute(e);
      // Keeps the moves and the release coming when the mouse leaves the window during a drag
      try { stage.setPointerCapture(e.pointerId); } catch (x) { /* it ended already */ }
    }
    if (LOCK && !locked() && stage.requestPointerLock && !mouseUnlockedOnPurpose) {
      // The first click only captures the mouse
      try {
        const p = stage.requestPointerLock({ unadjustedMovement: true });
        if (p && p.catch) p.catch(() => { try { stage.requestPointerLock(); } catch (x) { /* none */ } });
      } catch (x) {
        try { stage.requestPointerLock(); } catch (y) { /* none */ }
      }
      return;
    }
    const b = BUTTON[e.button];
    if (b) sendButton(b, true);
  } else {
    touchDown(e);
  }
});
window.addEventListener('pointerup', e => {
  if (!live) return;
  if (e.pointerType === 'mouse') {
    const b = BUTTON[e.button];
    if (b) sendButton(b, false);
  } else touchUp(e);
});
window.addEventListener('pointercancel', e => {
  if (e.pointerType !== 'mouse') touchUp(e, true);
  else for (const b of [...buttonsDown]) sendButton(b, false);
});
// A browser's own drag and drop would swallow the moves and the release, and leave the button held on the desktop
document.addEventListener('dragstart', e => e.preventDefault());
let mouseUnlockedOnPurpose = false;
document.addEventListener('mousemove', e => {
  if (!live) return;
  if (!locked() && !e.buttons && document.activeElement !== kbd && !stage.contains(e.target)) return;
  if (!locked()) {
    // A release the page never saw: the button must not stay held on the desktop
    if (!e.buttons && buttonsDown.size) for (const b of [...buttonsDown]) sendButton(b, false);
    // The phone's touchpad has the pointer until this mouse really moves; browsers also send moves for layout changes
    if (stage.classList.contains('phone-pointer')) {
      if (!e.movementX && !e.movementY) return;
      stage.classList.remove('phone-pointer');
    }
    sendAbsolute(e);
    return;
  }
  const f = toRemote();
  queueMove(e.movementX * f, e.movementY * f);
});
document.addEventListener('pointerlockchange', () => {
  stage.classList.toggle('free', !locked());
  if (!locked()) releaseAll();
});
stage.addEventListener('wheel', e => {
  if (!live) return;
  e.preventDefault();
  const unit = e.deltaMode === 1 ? 1 / 3 : e.deltaMode === 2 ? 3 : 1 / 100;
  wheelY += e.deltaY * unit; wheelX += e.deltaX * unit;
  const y = Math.trunc(wheelY), x = Math.trunc(wheelX);
  if (x || y) { wheelY -= y; wheelX -= x; sendScroll(y, x); }
}, { passive: false });

// ---------------------------------------------------------------- input: touch, as a touchpad
const touches = new Map();
let tapStart = 0, tapMoved = 0, maxFingers = 0, lastTapEnd = 0, dragging = false, scrollAcc = 0, scrollAccX = 0;
const TOUCH_SPEED = 1.6, TAP_MS = 220, TAP_MOVE = 12, SCROLL_STEP = 36;

function touchDown(e) {
  e.preventDefault();
  try { stage.setPointerCapture(e.pointerId); } catch (x) { /* fine */ }
  if (touches.size === 0) {
    tapStart = performance.now(); tapMoved = 0; maxFingers = 0;
    // Tap, then touch again and move: drag with the left button
    if (tapStart - lastTapEnd < 280) { dragging = true; sendButton(1, true); }
  }
  touches.set(e.pointerId, { x: e.clientX, y: e.clientY });
  maxFingers = Math.max(maxFingers, touches.size);
}
stage.addEventListener('pointermove', e => {
  if (!live || e.pointerType === 'mouse') return;
  const t = touches.get(e.pointerId);
  if (!t) return;
  e.preventDefault();
  const dx = e.clientX - t.x, dy = e.clientY - t.y;
  t.x = e.clientX; t.y = e.clientY;
  tapMoved += Math.abs(dx) + Math.abs(dy);
  if (touches.size === 1) {
    const f = toRemote() * TOUCH_SPEED;
    queueMove(dx * f, dy * f);
  } else if (touches.size === 2) {
    // Two fingers scroll, content follows the fingers. Half for each finger's event
    scrollAcc += -dy / 2; scrollAccX += -dx / 2;
    const y = Math.trunc(scrollAcc / SCROLL_STEP), x = Math.trunc(scrollAccX / SCROLL_STEP);
    if (x || y) { scrollAcc -= y * SCROLL_STEP; scrollAccX -= x * SCROLL_STEP; sendScroll(y, x); }
  }
});
function touchUp(e, cancelled) {
  if (!touches.delete(e.pointerId) || touches.size) return;
  scrollAcc = scrollAccX = 0;
  if (dragging) { dragging = false; sendButton(1, false); lastTapEnd = 0; return; }
  const short = performance.now() - tapStart < TAP_MS && tapMoved < TAP_MOVE * maxFingers;
  if (cancelled || !short) return;
  const b = maxFingers === 1 ? 1 : maxFingers === 2 ? 3 : 2;
  sendButton(b, true); sendButton(b, false);
  lastTapEnd = maxFingers === 1 ? performance.now() : 0;
}

// ---------------------------------------------------------------- input: keyboard
// KeyboardEvent.code → Linux input key code
const CODES = {
  Escape: 1, Digit1: 2, Digit2: 3, Digit3: 4, Digit4: 5, Digit5: 6, Digit6: 7, Digit7: 8, Digit8: 9, Digit9: 10, Digit0: 11,
  Minus: 12, Equal: 13, Backspace: 14, Tab: 15, KeyQ: 16, KeyW: 17, KeyE: 18, KeyR: 19, KeyT: 20, KeyY: 21, KeyU: 22,
  KeyI: 23, KeyO: 24, KeyP: 25, BracketLeft: 26, BracketRight: 27, Enter: 28, ControlLeft: 29, KeyA: 30, KeyS: 31,
  KeyD: 32, KeyF: 33, KeyG: 34, KeyH: 35, KeyJ: 36, KeyK: 37, KeyL: 38, Semicolon: 39, Quote: 40, Backquote: 41,
  ShiftLeft: 42, Backslash: 43, KeyZ: 44, KeyX: 45, KeyC: 46, KeyV: 47, KeyB: 48, KeyN: 49, KeyM: 50, Comma: 51,
  Period: 52, Slash: 53, ShiftRight: 54, NumpadMultiply: 55, AltLeft: 56, Space: 57, CapsLock: 58, F1: 59, F2: 60,
  F3: 61, F4: 62, F5: 63, F6: 64, F7: 65, F8: 66, F9: 67, F10: 68, NumLock: 69, ScrollLock: 70, Numpad7: 71, Numpad8: 72,
  Numpad9: 73, NumpadSubtract: 74, Numpad4: 75, Numpad5: 76, Numpad6: 77, NumpadAdd: 78, Numpad1: 79, Numpad2: 80,
  Numpad3: 81, Numpad0: 82, NumpadDecimal: 83, IntlBackslash: 86, F11: 87, F12: 88, IntlRo: 89, NumpadEnter: 96,
  ControlRight: 97, NumpadDivide: 98, PrintScreen: 99, AltRight: 100, Home: 102, ArrowUp: 103, PageUp: 104,
  ArrowLeft: 105, ArrowRight: 106, End: 107, ArrowDown: 108, PageDown: 109, Insert: 110, Delete: 111,
  AudioVolumeMute: 113, AudioVolumeDown: 114, AudioVolumeUp: 115, NumpadEqual: 117, Pause: 119, IntlYen: 124,
  MetaLeft: 125, MetaRight: 126, ContextMenu: 127,
};
// Soft keyboards (iPad) often leave code empty: named keys by their key instead
const NAMED = { Backspace: 14, Enter: 28, Tab: 15, Escape: 1, ArrowLeft: 105, ArrowRight: 106, ArrowUp: 103, ArrowDown: 108,
  Delete: 111, Home: 102, End: 107, PageUp: 104, PageDown: 109 };
// On a Mac or iPad keyboard, Command does what Ctrl does on the desktop (copy, paste, close)
const CMD_AS_CTRL = apple;

function scanOf(e) {
  let s = CODES[e.code] || NAMED[e.key] || 0;
  if (CMD_AS_CTRL && (e.code === 'MetaLeft' || e.code === 'MetaRight')) s = e.code === 'MetaLeft' ? 29 : 97;
  return s;
}
function sendKey(scan, down) {
  if (down) keysDown.add(scan); else if (!keysDown.delete(scan)) return;
  send(IN_KEY, new Uint8Array([scan >> 8, scan & 255, down ? 1 : 0]));
}
function sendText(s) {
  if (!s) return;
  send(IN_TEXT, new TextEncoder().encode(s));
}

window.addEventListener('keydown', e => {
  if (!live || document.activeElement === codeIn) return;
  if (e.isComposing || e.keyCode === 229) return;   // an input method is composing: its text comes at the end
  const scan = scanOf(e);
  if (!scan) {
    // A key without a code, as from some soft keyboards: send what it types
    if (e.key && e.key.length === 1 && !e.ctrlKey && !e.metaKey) { e.preventDefault(); sendText(e.key); }
    return;
  }
  e.preventDefault();
  e.stopPropagation();
  if (!e.repeat || !keysDown.has(scan)) sendKey(scan, true);
  else { sendKey(scan, false); sendKey(scan, true); }
}, true);
window.addEventListener('keyup', e => {
  if (!live || document.activeElement === codeIn) return;
  const scan = scanOf(e);
  if (scan) { e.preventDefault(); sendKey(scan, false); }
  // Mac: no keyup for other keys while Command is held
  if (apple && (e.key === 'Meta')) releaseAll();
}, true);

// Text from input methods and soft keyboards, through the hidden text field
let composing = false;
kbd.addEventListener('compositionstart', () => { composing = true; });
kbd.addEventListener('compositionend', e => { composing = false; sendText(e.data); kbd.value = ''; });
kbd.addEventListener('input', e => {
  if (composing || e.isComposing) return;
  if (e.inputType === 'insertText' || e.inputType === 'insertReplacementText') sendText(e.data || kbd.value);
  else if (e.inputType === 'deleteContentBackward') { sendKey(14, true); sendKey(14, false); }
  else if (e.inputType === 'insertLineBreak') { sendKey(28, true); sendKey(28, false); }
  kbd.value = '';
});

// ---------------------------------------------------------------- staying on the page
let historyTrapped = false;
function trapHistory() {
  if (historyTrapped) return;
  historyTrapped = true;
  try { history.pushState({ xdesk: 1 }, '', location.href); } catch (e) { /* file */ }
}
window.addEventListener('popstate', () => {
  if (!live) return;
  try { history.pushState({ xdesk: 1 }, '', location.href); } catch (e) { /* file */ }
  flash(T.backBlocked, 2500);
});
window.addEventListener('beforeunload', e => {
  if (!live) return;
  e.preventDefault();
  e.returnValue = T.leave;
  return T.leave;
});
for (const ev of ['contextmenu', 'dragstart', 'selectstart', 'gesturestart', 'gesturechange', 'gestureend', 'dblclick']) {
  document.addEventListener(ev, e => { if (e.target !== codeIn) e.preventDefault(); }, { passive: false });
}
// No pinch zoom, pull to refresh or rubber band from the page itself
document.addEventListener('touchmove', e => { if (e.touches.length > 1 || e.target !== codeIn) e.preventDefault(); }, { passive: false });
document.addEventListener('touchstart', e => { if (e.touches.length > 1) e.preventDefault(); }, { passive: false });
window.addEventListener('blur', releaseAll);
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { releaseAll(); return; }
  if (!live && !quitting && (!ws || ws.readyState > 1)) connect();
  requestWakeLock();
});

let wakeLock = null;
function requestWakeLock() {
  if (!('wakeLock' in navigator) || document.hidden) return;
  navigator.wakeLock.request('screen').then(l => { wakeLock = l; }).catch(() => {});
}

// ---------------------------------------------------------------- toolbar
function fullscreenEl() { return document.fullscreenElement || document.webkitFullscreenElement; }
function toggleFullscreen() {
  const d = document.documentElement;
  if (fullscreenEl()) { (document.exitFullscreen || document.webkitExitFullscreen).call(document); return; }
  const req = d.requestFullscreen || d.webkitRequestFullscreen;
  if (!req) return;
  const p = req.call(d, { navigationUI: 'hide' });
  const after = () => {
    // Chrome: Esc, Ctrl+W and the like go to the desktop instead of the browser while full screen
    if (navigator.keyboard && navigator.keyboard.lock) navigator.keyboard.lock().catch(() => {});
    if (screen.orientation && screen.orientation.lock) screen.orientation.lock('landscape').catch(() => {});
  };
  if (p && p.then) p.then(after).catch(() => {}); else after();
}
function onFullscreen() { $('b-full').classList.toggle('on', !!fullscreenEl()); }
document.addEventListener('fullscreenchange', onFullscreen);
document.addEventListener('webkitfullscreenchange', onFullscreen);

const icons = { full: '⛶', kbd: '⌨', sound: '♪', quit: '⏻' };
for (const [k, id] of [['full', 'b-full'], ['kbd', 'b-kbd'], ['sound', 'b-sound'], ['quit', 'b-quit']]) {
  $(id).textContent = icons[k];
  $(id).title = T[k];
  $(id).setAttribute('aria-label', T[k]);
}
$('b-full').addEventListener('click', toggleFullscreen);
$('b-kbd').addEventListener('click', () => {
  if (document.activeElement === kbd) { kbd.blur(); return; }
  kbd.value = '';
  kbd.focus();
});
$('b-sound').addEventListener('click', () => { if (sound) sound.unlock(); });
$('b-quit').addEventListener('click', () => {
  if (!confirm(T.leave)) return;
  quitting = true;
  // Let go of keys and buttons first: the phone stops reading at BYE (item 8)
  releaseAll();
  send(BYE);
  live = false;
  stopSession();
  if (ws) ws.close();
  show(T.bye, { button: T.reconnect });
});
for (const b of bar.querySelectorAll('button')) {
  b.addEventListener('pointerdown', e => e.stopPropagation());
}

// The bar shows near the top edge or on a touch at the top, and hides again
let barTimer = 0;
function showBar(ms) {
  bar.classList.remove('hidden');
  clearTimeout(barTimer);
  barTimer = setTimeout(() => bar.classList.add('hidden'), ms || 3000);
}
document.addEventListener('mousemove', e => { if (!locked() && e.clientY < 48) showBar(2500); });
stage.addEventListener('pointerdown', e => { if (e.pointerType !== 'mouse' && e.clientY < 60) showBar(4000); }, true);
// With the mouse captured, Esc releases it: show the bar so it can be used
document.addEventListener('pointerlockchange', () => {
  if (!locked() && live) { mouseUnlockedOnPurpose = true; showBar(4000); setTimeout(() => { mouseUnlockedOnPurpose = false; }, 600); }
});

let flashTimer = 0;
function flash(text, ms) {
  if (!text) return;
  hint.textContent = text;
  hint.classList.remove('hidden');
  clearTimeout(flashTimer);
  flashTimer = setTimeout(() => hint.classList.add('hidden'), ms || 2500);
}

// ---------------------------------------------------------------- start
stage.classList.add('free');
pairForm.querySelector('button').textContent = T.pairBtn;
const standalone = matchMedia('(display-mode: standalone), (display-mode: fullscreen)').matches || navigator.standalone;
noteEl.textContent = standalone ? '' : T.noteHttp;
window.addEventListener('resize', () => { if (live) send(SIZE, new Uint8Array([...u16(screenSize()[0]), ...u16(screenSize()[1]), 0, 0])); });
requestWakeLock();
detected.then(connect, connect);
})();
