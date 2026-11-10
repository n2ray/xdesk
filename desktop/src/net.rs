//! This computer as a wireless display of the phone (receiver/PROTOCOL.md). The phone connects here: over the network
//! after someone picks this computer in its list, over USB when a cable with USB debugging is in.

use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::io::{ErrorKind, Read, Write};
use std::net::{IpAddr, Shutdown, TcpListener, TcpStream};
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender, SyncSender, TrySendError};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

const RECEIVER_PORT: u16 = 47140;
const SERVICE: &str = "_xdesk-display._tcp.local.";
const PACKAGE: &str = "io.github.n2ray.xdesk.x11";
const VIDEO_FORMAT: u8 = 1;
const VIDEO_FRAME: u8 = 2;
const AUDIO_DATA: u8 = 4;
const PING: u8 = 5;
const BYE: u8 = 6;
const SIZE: u8 = 7;
const KEY_FRAME: u8 = 8;
const TIME: u8 = 9;
const ADDRESSES: u8 = 11;
const CLIPBOARD: u8 = 12;
const APPS: u8 = 13;
const IN_ANDROID: u8 = 27;
const IN_CHOOSE: u8 = 28;
const FILE_START: u8 = 29;
const FILE_DATA: u8 = 30;
const FILE_END: u8 = 31;
/// A file goes in pieces of this size, so that the mouse never waits long behind one.
const FILE_CHUNK: usize = 64 * 1024;
const FILE_MAX_WAITING: usize = 16;
const IN_BUTTON: u8 = 21;
const IN_SCROLL: u8 = 22;
const IN_KEY: u8 = 23;
const IN_TEXT: u8 = 24;
const IN_ABS: u8 = 25;
const IN_LAYOUT: u8 = 26;
const WELCOME_OK: u8 = 0;
const WELCOME_PAIR: u8 = 1;
const WELCOME_VERSION: u8 = 3;
const WELCOME_REFUSED: u8 = 4;
const CHALLENGE: u8 = 5;
const PROTOCOL_VERSION: u8 = 2;
/// H.264, and this receiver sends input.
const CODECS: u8 = 0x81;
const MAX_TRIES: u32 = 3;
const USB_TRIES: u32 = 4;
const LOST_AFTER: Duration = Duration::from_secs(6);
const PING_AFTER: Duration = Duration::from_secs(2);
/// How long "connection lost" stays up before going back to how to connect.
const RECONNECT_NOTICE: Duration = Duration::from_secs(9);

pub enum Out {
    Key(u16, bool),
    Text(String),
    Abs(u16, u16),
    Button(u8, bool),
    Scroll(i16, i16),
    Layout(String),
    Size(u32, u32),
    Clipboard(String),
    Android(u8),
    Choose(String),
    FileStart(String),
    FileData(Vec<u8>),
    FileEnd,
    Bye,
}

pub enum Video {
    Reset,
    Data(Vec<u8>, Instant),
}

/// What the window says while there is no picture: a line, and something large such as a pairing code.
#[derive(Clone, Default, PartialEq)]
pub struct Notice {
    pub line: String,
    pub big: String,
}

static OUT: OnceLock<Sender<Out>> = OnceLock::new();
static NOTICE: Mutex<Option<Notice>> = Mutex::new(None);
static LAYOUT: Mutex<String> = Mutex::new(String::new());
static WANTED: Mutex<(u32, u32)> = Mutex::new((1280, 720));
static CURRENT: Mutex<Option<TcpStream>> = Mutex::new(None);
pub static STREAMING: AtomicBool = AtomicBool::new(false);
/// False once there is no point showing the last picture any more.
pub static KEEP_PICTURE: AtomicBool = AtomicBool::new(false);
/// Which connection is the current one: the newest to finish its handshake.
static GENERATION: AtomicU64 = AtomicU64::new(0);
pub static VIA_USB: AtomicBool = AtomicBool::new(false);
/// This computer asked to end the session: nothing to wait for afterwards.
static USER_ENDED: AtomicBool = AtomicBool::new(false);
static APPS_LIST: Mutex<Option<Vec<(String, String)>>> = Mutex::new(None);
static FILES_WAITING: AtomicUsize = AtomicUsize::new(0);
static FILES: OnceLock<Sender<PathBuf>> = OnceLock::new();

pub fn out(o: Out) {
    if let Some(t) = OUT.get() {
        let _ = t.send(o);
    }
}

pub fn notice() -> Notice {
    NOTICE.lock().unwrap().clone().unwrap_or_default()
}

fn say(line: &str, big: &str) {
    *NOTICE.lock().unwrap() = Some(Notice { line: line.to_string(), big: big.to_string() });
}

fn dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_default();
    let d = PathBuf::from(home).join("Library/Application Support/XDesk Desktop");
    let _ = std::fs::create_dir_all(&d);
    d
}

pub fn load(name: &str) -> String {
    std::fs::read_to_string(dir().join(name)).unwrap_or_default().trim().to_string()
}

pub fn save(name: &str, value: &str) {
    let _ = std::fs::write(dir().join(name), value);
}

fn random_hex(bytes: usize) -> String {
    let mut b = vec![0u8; bytes];
    std::fs::File::open("/dev/urandom").and_then(|mut f| f.read_exact(&mut b)).expect("urandom");
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn stored_id(name: &str) -> String {
    let id = load(name);
    if id.len() == 32 {
        return id;
    }
    let id = random_hex(16);
    save(name, &id);
    id
}

fn computer_name() -> String {
    Command::new("scutil")
        .args(["--get", "ComputerName"])
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|n| !n.is_empty())
        .unwrap_or_else(|| "Mac".into())
}

/// What the computer's keyboard is, from the keys typed. Only ever changes to a layout it has seen proof of.
pub fn set_layout(layout: &str) {
    let mut l = LAYOUT.lock().unwrap();
    if *l == layout {
        return;
    }
    *l = layout.to_string();
    save("layout", layout);
    out(Out::Layout(layout.to_string()));
}

/// The window's size in pixels: the phone then draws what fills it, up to what the connection carries.
pub fn set_window_size(w: u32, h: u32) {
    let size = even(w, h);
    let mut wanted = WANTED.lock().unwrap();
    if *wanted != size {
        *wanted = size;
        out(Out::Size(size.0, size.1));
    }
}

fn even(w: u32, h: u32) -> (u32, u32) {
    ((w & !1).max(320), (h & !1).max(240))
}

fn framed(kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut b = Vec::with_capacity(5 + payload.len());
    b.push(kind);
    b.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    b.extend_from_slice(payload);
    b
}

fn encoded(o: &Out) -> Vec<u8> {
    match o {
        Out::Key(scan, down) => {
            let s = scan.to_be_bytes();
            framed(IN_KEY, &[s[0], s[1], *down as u8])
        }
        Out::Text(t) => framed(IN_TEXT, t.as_bytes()),
        Out::Abs(x, y) => {
            let (a, b) = (x.to_be_bytes(), y.to_be_bytes());
            framed(IN_ABS, &[a[0], a[1], b[0], b[1]])
        }
        Out::Button(b, down) => framed(IN_BUTTON, &[*b, *down as u8]),
        Out::Scroll(dy, dx) => {
            let (a, b) = (dy.to_be_bytes(), dx.to_be_bytes());
            framed(IN_SCROLL, &[a[0], a[1], b[0], b[1]])
        }
        Out::Layout(l) => framed(IN_LAYOUT, l.as_bytes()),
        Out::Clipboard(t) => framed(CLIPBOARD, t.as_bytes()),
        Out::Android(b) => framed(IN_ANDROID, &[*b]),
        Out::Choose(k) => framed(IN_CHOOSE, k.as_bytes()),
        Out::FileStart(n) => framed(FILE_START, n.as_bytes()),
        Out::FileData(d) => framed(FILE_DATA, d),
        Out::FileEnd => framed(FILE_END, &[]),
        Out::Bye => framed(BYE, &[]),
        Out::Size(w, h) => {
            let (a, b) = ((*w as u16).to_be_bytes(), (*h as u16).to_be_bytes());
            framed(SIZE, &[a[0], a[1], b[0], b[1], 0, 0])
        }
    }
}

#[derive(Clone)]
struct Sinks {
    video: SyncSender<Video>,
    audio: Sender<Vec<i16>>,
    keyframe: Arc<AtomicBool>,
}

#[derive(Default)]
struct Stream {
    dropping: bool,
}

impl Stream {
    /// One message of the stream. False when the phone ended it.
    fn message(&mut self, kind: u8, p: &[u8], sinks: &Sinks) -> bool {
        match kind {
            VIDEO_FORMAT => {
                let _ = sinks.video.send(Video::Reset);
                self.dropping = false;
            }
            VIDEO_FRAME if p.len() > 9 => {
                let flags = p[8];
                if self.dropping && flags & 3 == 0 {
                    return true;
                }
                if flags & 1 == 0 {
                    crate::stats::received(u64::from_be_bytes([p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]]));
                }
                match sinks.video.try_send(Video::Data(p[9..].to_vec(), Instant::now())) {
                    Ok(()) => self.dropping = false,
                    Err(TrySendError::Full(_)) => {
                        self.dropping = true;
                        sinks.keyframe.store(true, Ordering::Relaxed);
                    }
                    Err(TrySendError::Disconnected(_)) => return false,
                }
            }
            AUDIO_DATA if p.len() > 8 => {
                let pcm = p[8..].chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]])).collect();
                let _ = sinks.audio.send(pcm);
            }
            BYE => return false,
            CLIPBOARD => crate::clip::set(&String::from_utf8_lossy(p)),
            TIME => crate::stats::time_reply(p),
            APPS => {
                let text = String::from_utf8_lossy(p);
                let list = text.lines().filter_map(|l| l.split_once('\t')).map(|(k, n)| (k.to_string(), n.to_string())).collect();
                *APPS_LIST.lock().unwrap() = Some(list);
            }
            _ => {}
        }
        true
    }
}

fn read_string(s: &mut TcpStream) -> std::io::Result<String> {
    let mut len = [0u8; 2];
    s.read_exact(&mut len)?;
    let n = u16::from_be_bytes(len) as usize;
    if n > 1024 {
        return Err(ErrorKind::InvalidData.into());
    }
    let mut b = vec![0u8; n];
    s.read_exact(&mut b)?;
    Ok(String::from_utf8_lossy(&b).into_owned())
}

fn put_string(b: &mut Vec<u8>, s: &str) {
    b.extend_from_slice(&(s.len() as u16).to_be_bytes());
    b.extend_from_slice(s.as_bytes());
}

fn proof(token: &str, label: &str, phone: &str, receiver: &str, phone_nonce: &str, receiver_nonce: &str) -> String {
    let mut mac = Hmac::<Sha256>::new_from_slice(token.as_bytes()).expect("any key length");
    mac.update(format!("{label}\n{phone}\n{receiver}\n{phone_nonce}\n{receiver_nonce}").as_bytes());
    mac.finalize().into_bytes().iter().map(|x| format!("{x:02x}")).collect()
}

fn welcome_ok(s: &mut TcpStream, id: &str, name: &str, token: &str) -> std::io::Result<()> {
    let (w, h) = *WANTED.lock().unwrap();
    let mut b = vec![WELCOME_OK];
    put_string(&mut b, id);
    put_string(&mut b, name);
    put_string(&mut b, token);
    for v in [w as u16, h as u16, 160, 60] {
        b.extend_from_slice(&v.to_be_bytes());
    }
    b.push(CODECS);
    s.write_all(&b)
}

fn welcome(s: &mut TcpStream, status: u8, id: &str, name: &str) -> std::io::Result<()> {
    let mut b = vec![status];
    put_string(&mut b, id);
    put_string(&mut b, name);
    s.write_all(&b)
}

/// True when the phone may stream to this computer.
fn handshake(s: &mut TcpStream, id: &str, name: &str) -> std::io::Result<bool> {
    let mut head = [0u8; 5];
    s.read_exact(&mut head)?;
    if &head[..4] != b"XDSK" {
        return Ok(false);
    }
    if head[4] != PROTOCOL_VERSION {
        welcome(s, WELCOME_VERSION, id, name)?;
        say("手机上的 XDesk 版本和这个程序不匹配，请更新", "");
        return Ok(false);
    }
    let phone = read_string(s)?;
    let phone_name = read_string(s)?;
    let phone_nonce = read_string(s)?;
    if !phone.chars().all(|c| c.is_ascii_hexdigit()) || phone.len() != 32 {
        return Ok(false);
    }
    let token_file = format!("phone_{phone}");
    let token = load(&token_file);
    let nonce = random_hex(32);
    let mut b = vec![CHALLENGE];
    put_string(&mut b, id);
    put_string(&mut b, name);
    put_string(&mut b, &nonce);
    put_string(&mut b, &if token.is_empty() { String::new() } else { proof(&token, "receiver", &phone, id, &phone_nonce, &nonce) });
    s.write_all(&b)?;
    let answer = read_string(s)?;
    if !token.is_empty() && answer == proof(&token, "phone", &phone, id, &phone_nonce, &nonce) {
        welcome_ok(s, id, name, "")?;
        return Ok(true);
    }
    let code: String = random_hex(4).bytes().take(4).map(|b| char::from(b'0' + b % 10)).collect();
    say(&format!("在 {phone_name} 上输入这个配对码"), &code);
    for _ in 0..MAX_TRIES {
        welcome(s, WELCOME_PAIR, id, name)?;
        if read_string(s)? == code {
            let new_token = random_hex(32);
            save(&token_file, &new_token);
            welcome_ok(s, id, name, &new_token)?;
            return Ok(true);
        }
    }
    welcome(s, WELCOME_REFUSED, id, name)?;
    say("配对码错太多次，请重新连接", "");
    Ok(false)
}

/// This computer's other addresses, for when the phone loses the one in use.
fn lan_addresses() -> String {
    let mut found = Vec::new();
    for iface in if_addrs::get_if_addrs().unwrap_or_default() {
        if let IpAddr::V4(ip) = iface.ip() {
            let o = ip.octets();
            let tailscale = o[0] == 100 && (64..128).contains(&o[1]);
            if !ip.is_loopback() && !ip.is_link_local() && !tailscale {
                found.push(ip.to_string());
            }
        }
    }
    found.join(",")
}

/// True when the phone ended the stream on purpose, false when the connection went away.
fn stream_until_end(s: TcpStream, rx: &Receiver<Out>, sinks: &Sinks) -> std::io::Result<bool> {
    s.set_read_timeout(Some(LOST_AFTER))?;
    let usb = s.peer_addr().map(|a| a.ip().is_loopback()).unwrap_or(false);
    VIA_USB.store(usb, Ordering::Relaxed);
    FILES_WAITING.store(0, Ordering::Relaxed);
    crate::stats::reset();
    STREAMING.store(true, Ordering::Relaxed);
    KEEP_PICTURE.store(true, Ordering::Relaxed);
    say(if usb { "已通过 USB 连接" } else { "已通过 Wi-Fi 连接" }, "");
    while rx.try_recv().is_ok() {}
    crate::clip::baseline();
    let layout = LAYOUT.lock().unwrap().clone();
    let mut writer = s.try_clone()?;
    writer.write_all(&framed(IN_LAYOUT, layout.as_bytes()))?;
    writer.write_all(&framed(ADDRESSES, lan_addresses().as_bytes()))?;
    let done = Arc::new(AtomicBool::new(false));
    let ended = Arc::new(AtomicBool::new(false));
    let reader_ended = ended.clone();
    let reader_done = done.clone();
    let mut input = s;
    let reader_sinks = sinks.clone();
    let reader = std::thread::spawn(move || {
        let mut stream = Stream::default();
        let mut head = [0u8; 5];
        while input.read_exact(&mut head).is_ok() {
            let n = u32::from_be_bytes([head[1], head[2], head[3], head[4]]) as usize;
            let mut p = vec![0u8; n.min(1 << 24)];
            if input.read_exact(&mut p).is_err() {
                break;
            }
            if !stream.message(head[0], &p, &reader_sinks) {
                reader_ended.store(true, Ordering::Relaxed);
                break;
            }
        }
        reader_done.store(true, Ordering::Relaxed);
        let _ = input.shutdown(Shutdown::Both);
    });
    let mut last_out = Instant::now();
    let mut last_key_request = Instant::now() - Duration::from_secs(5);
    let mut last_time = Instant::now() - Duration::from_secs(5);
    let result = (|| -> std::io::Result<()> {
        while !done.load(Ordering::Relaxed) {
            match rx.recv_timeout(Duration::from_millis(5)) {
                Ok(o) => {
                    writer.write_all(&encoded(&o))?;
                    if matches!(o, Out::FileData(_)) {
                        FILES_WAITING.fetch_sub(1, Ordering::Relaxed);
                    }
                    last_out = Instant::now();
                }
                Err(RecvTimeoutError::Timeout) => {}
                Err(RecvTimeoutError::Disconnected) => break,
            }
            if sinks.keyframe.swap(false, Ordering::Relaxed) && last_key_request.elapsed() > Duration::from_secs(1) {
                last_key_request = Instant::now();
                writer.write_all(&framed(KEY_FRAME, &[]))?;
            }
            if crate::stats::PANEL.load(Ordering::Relaxed) && last_time.elapsed() > Duration::from_secs(1) {
                last_time = Instant::now();
                writer.write_all(&framed(TIME, &crate::stats::now_us().to_be_bytes()))?;
            }
            if last_out.elapsed() > PING_AFTER {
                writer.write_all(&framed(PING, &[]))?;
                last_out = Instant::now();
            }
        }
        Ok(())
    })();
    let _ = writer.shutdown(Shutdown::Both);
    let _ = reader.join();
    result.map(|()| ended.load(Ordering::Relaxed))
}

fn connection(mut s: TcpStream, id: &str, name: &str, rx: &Mutex<Receiver<Out>>, sinks: &Sinks) {
    let _ = s.set_nodelay(true);
    if s.set_read_timeout(Some(Duration::from_secs(120))).is_err() || !matches!(handshake(&mut s, id, name), Ok(true)) {
        return;
    }
    // The phone is back or has moved to another cable: this one replaces whatever it had before
    if let Some(old) = CURRENT.lock().unwrap().take() {
        let _ = old.shutdown(Shutdown::Both);
    }
    let mine = GENERATION.fetch_add(1, Ordering::Relaxed) + 1;
    *CURRENT.lock().unwrap() = s.try_clone().ok();
    let rx = rx.lock().unwrap();
    let ended = stream_until_end(s, &rx, sinks).unwrap_or_else(|e| {
        eprintln!("connection ended: {e}");
        false
    });
    // A newer connection has taken over: it is not this one that ended
    if GENERATION.load(Ordering::Relaxed) != mine {
        return;
    }
    *CURRENT.lock().unwrap() = None;
    STREAMING.store(false, Ordering::Relaxed);
    VIA_USB.store(false, Ordering::Relaxed);
    if ended || USER_ENDED.swap(false, Ordering::Relaxed) {
        KEEP_PICTURE.store(false, Ordering::Relaxed);
        *LOST_AT.lock().unwrap() = None;
        say(HOW_TO_CONNECT, "");
    } else {
        say("连接断开，正在等手机重新连接", "");
        *LOST_AT.lock().unwrap() = Some(Instant::now());
    }
}

/// Words over the picture for a few seconds.
static TOAST: Mutex<Option<(String, Instant)>> = Mutex::new(None);

pub fn toast(text: &str) {
    *TOAST.lock().unwrap() = Some((text.to_string(), Instant::now() + Duration::from_secs(3)));
}

pub fn current_toast() -> String {
    TOAST.lock().unwrap().as_ref().filter(|(_, until)| Instant::now() < *until).map(|(t, _)| t.clone()).unwrap_or_default()
}

static LOST_AT: Mutex<Option<Instant>> = Mutex::new(None);

pub fn disconnect() {
    if STREAMING.load(Ordering::Relaxed) {
        USER_ENDED.store(true, Ordering::Relaxed);
        out(Out::Bye);
    }
}

/// The phone's answer to asking for its list of apps, once.
pub fn take_apps() -> Option<Vec<(String, String)>> {
    APPS_LIST.lock().unwrap().take()
}

pub fn send_file(path: PathBuf) {
    if let Some(t) = FILES.get() {
        let _ = t.send(path);
    }
}

fn send_one_file(path: &std::path::Path) {
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    if !STREAMING.load(Ordering::Relaxed) {
        toast("连上手机后才能传文件");
        return;
    }
    let size = match std::fs::metadata(path) {
        Ok(m) if m.is_file() => m.len(),
        _ => {
            toast(&format!("只能传文件，不能传文件夹：{name}"));
            return;
        }
    };
    let Ok(mut file) = std::fs::File::open(path) else {
        toast(&format!("打不开：{name}"));
        return;
    };
    let session = GENERATION.load(Ordering::Relaxed);
    out(Out::FileStart(name.clone()));
    let mut buffer = vec![0u8; FILE_CHUNK];
    let (mut sent, mut said) = (0u64, Instant::now());
    loop {
        while FILES_WAITING.load(Ordering::Relaxed) >= FILE_MAX_WAITING && STREAMING.load(Ordering::Relaxed) && GENERATION.load(Ordering::Relaxed) == session {
            std::thread::sleep(Duration::from_millis(3));
        }
        if !STREAMING.load(Ordering::Relaxed) || GENERATION.load(Ordering::Relaxed) != session {
            toast(&format!("连接断了，没传完：{name}"));
            return;
        }
        let n = match file.read(&mut buffer) {
            Ok(n) => n,
            Err(_) => {
                out(Out::FileStart(String::new()));
                toast(&format!("读取失败：{name}"));
                return;
            }
        };
        if n == 0 {
            break;
        }
        FILES_WAITING.fetch_add(1, Ordering::Relaxed);
        out(Out::FileData(buffer[..n].to_vec()));
        sent += n as u64;
        if said.elapsed() > Duration::from_secs(1) && size > 0 {
            said = Instant::now();
            toast(&format!("正在传 {name}：{}%", sent * 100 / size));
        }
    }
    out(Out::FileEnd);
    toast(&format!("已传到手机的“下载”：{name}"));
}

/// In the phone's list of wireless displays for as long as this runs.
fn announce(id: &str, name: &str) -> Option<mdns_sd::ServiceDaemon> {
    let daemon = mdns_sd::ServiceDaemon::new().ok()?;
    let host = format!("xdesk-{}.local.", &id[..8]);
    let properties = [("id", id), ("v", "1")];
    let info = mdns_sd::ServiceInfo::new(SERVICE, name, &host, "", RECEIVER_PORT, &properties[..]).ok()?.enable_addr_auto();
    *ANNOUNCED.lock().unwrap() = Some(info.get_fullname().to_string());
    daemon.register(info).ok()?;
    Some(daemon)
}

static ANNOUNCED: Mutex<Option<String>> = Mutex::new(None);
static DAEMON: Mutex<Option<mdns_sd::ServiceDaemon>> = Mutex::new(None);

/// Leaving: the phones on the network hear it now rather than when their records run out.
pub fn goodbye() {
    let daemon = DAEMON.lock().unwrap().take();
    let name = ANNOUNCED.lock().unwrap().take();
    if let (Some(daemon), Some(name)) = (daemon, name) {
        if let Ok(done) = daemon.unregister(&name) {
            let _ = done.recv_timeout(Duration::from_millis(800));
        }
    }
}

fn listen(rx: Arc<Mutex<Receiver<Out>>>, sinks: Sinks, id: String, name: String) {
    let Ok(listener) = TcpListener::bind(("0.0.0.0", RECEIVER_PORT)) else {
        say("端口被占用，可能已经开着另一个 XDesk", "");
        return;
    };
    *DAEMON.lock().unwrap() = announce(&id, &name);
    for s in listener.incoming().flatten() {
        let (rx, sinks, id, name) = (rx.clone(), sinks.clone(), id.clone(), name.clone());
        std::thread::spawn(move || connection(s, &id, &name, &rx, &sinks));
    }
}

// --- USB ---

fn adb_path() -> Option<String> {
    let home = std::env::var("HOME").unwrap_or_default();
    // Packaged, it sits next to the program
    let inside = std::env::current_exe().ok().and_then(|e| Some(e.parent()?.join("adb").to_str()?.to_string())).unwrap_or_default();
    [inside, "adb".to_string(), "/opt/homebrew/bin/adb".into(), "/usr/local/bin/adb".into(), format!("{home}/Library/Android/sdk/platform-tools/adb")]
        .into_iter()
        .find(|p| Command::new(p).arg("version").output().map(|o| o.status.success()).unwrap_or(false))
}

fn adb(path: &str, args: &[&str]) -> Option<String> {
    let o = Command::new(path).args(args).output().ok()?;
    o.status.success().then(|| String::from_utf8_lossy(&o.stdout).into_owned())
}

/// Forwards the port to the phone and asks XDesk to connect here, opening it first if it is not running.
fn connect_over_usb(path: &str, name: &str) {
    let port = format!("tcp:{RECEIVER_PORT}");
    if adb(path, &["reverse", &port, &port]).is_none() {
        return;
    }
    let running = adb(path, &["shell", "pidof", PACKAGE]).is_some_and(|s| !s.trim().is_empty());
    if !running {
        let activity = format!("{PACKAGE}/com.termux.x11.XDeskActivity");
        let _ = adb(path, &["shell", "am", "start", "-n", &activity]);
        std::thread::sleep(Duration::from_millis(1500));
    }
    let receiver = format!("{PACKAGE}/com.termux.x11.ConnectReceiver");
    let quoted = format!("'{}'", name.replace('\'', ""));
    let _ = adb(path, &["shell", "am", "broadcast", "-n", &receiver, "--es", "host", "127.0.0.1", "--ei", "port", &RECEIVER_PORT.to_string(), "--es", "name", &quoted]);
}

const HOW_TO_CONNECT: &str = "插上手机（打开 USB 调试），或者在手机的 XDesk 里选“连接接收器”";

fn watch_usb(path: Option<String>, name: String) {
    let mut tries = 0;
    let mut was_up = false;
    loop {
        let devices = path.as_deref().and_then(|p| adb(p, &["devices"])).unwrap_or_default();
        let lines: Vec<&str> = devices.lines().skip(1).collect();
        let up = lines.iter().any(|l| l.ends_with("\tdevice"));
        let unauthorized = lines.iter().any(|l| l.ends_with("\tunauthorized"));
        if up && !was_up {
            tries = USB_TRIES;
        }
        was_up = up;
        let streaming = STREAMING.load(Ordering::Relaxed);
        if up && tries > 0 && !VIA_USB.load(Ordering::Relaxed) {
            tries -= 1;
            if let Some(p) = &path {
                if !streaming {
                    say("已插线，正在连接手机", "");
                }
                connect_over_usb(p, &name);
            }
        } else if !streaming {
            let lost = LOST_AT.lock().unwrap().is_some_and(|t| t.elapsed() < RECONNECT_NOTICE);
            let pairing = !notice().big.is_empty();
            if !lost {
                KEEP_PICTURE.store(false, Ordering::Relaxed);
            }
            if !lost && !pairing {
                if unauthorized {
                    say("在手机上点“允许”这台电脑的 USB 调试", "");
                } else if up {
                    say("已插线，在手机的 XDesk 里连接这台电脑，或者重新插一次线", "");
                } else {
                    say(HOW_TO_CONNECT, "");
                }
            }
        }
        std::thread::sleep(Duration::from_secs(2));
    }
}

/// Runs for as long as the program does.
pub fn start(size: (u32, u32), video: SyncSender<Video>, audio: Sender<Vec<i16>>, keyframe: Arc<AtomicBool>) {
    let (tx, rx) = channel();
    let _ = OUT.set(tx);
    let (file_tx, file_rx) = channel::<PathBuf>();
    let _ = FILES.set(file_tx);
    std::thread::spawn(move || {
        for path in file_rx {
            send_one_file(&path);
        }
    });
    *WANTED.lock().unwrap() = even(size.0, size.1);
    let saved = load("layout");
    *LAYOUT.lock().unwrap() = if saved.is_empty() { "us".into() } else { saved };
    say(HOW_TO_CONNECT, "");
    let (id, name) = (stored_id("receiver_id"), computer_name());
    let sinks = Sinks { video, audio, keyframe };
    let rx = Arc::new(Mutex::new(rx));
    let listen_name = name.clone();
    std::thread::spawn(move || listen(rx, sinks, id, listen_name));
    std::thread::spawn(move || watch_usb(adb_path(), name));
}
