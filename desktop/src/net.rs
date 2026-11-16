//! This computer as a wireless display of the phone (receiver/PROTOCOL.md). The phone connects here: over the network
//! after someone picks this computer in its list, over USB when a cable with USB debugging is in.

use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::io::{ErrorKind, Read, Write};
use std::net::{IpAddr, Shutdown, TcpListener, TcpStream};
use std::path::PathBuf;
use std::process::Command;
use crate::lang::tr;
use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender, SyncSender, TrySendError};
use std::sync::{Arc, LazyLock, Mutex, OnceLock};
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
const INPUT_MODE: u8 = 14;
const FEATURES: u8 = 18;
const SETTINGS: u8 = 19;
const IN_ANDROID: u8 = 27;
const IN_CHOOSE: u8 = 28;
const FILE_START: u8 = 29;
const FILE_DATA: u8 = 30;
const FILE_END: u8 = 31;
const KEYBOARD: u8 = 32;
/// FEATURES bits: 0 the phone handles ANDROID 4 and 5, 1 a Mac keyboard (KEYBOARD), 2 SETTINGS, 3 the phone says
/// whether the picture contains a cursor (INPUT_MODE 3), and expects the receiver to hide its own pointer then.
pub const FEATURE_ACTIONS: u32 = 1;
pub const FEATURE_RAW_COMMAND: u32 = 2;
pub const FEATURE_PICTURE_CURSOR: u32 = 8;
/// FEATURES bit 4: the phone takes SCROLL_FINE (33), fractions of a wheel step with their phase (MACAPP.md §2).
pub const FEATURE_SCROLL_FINE: u32 = 16;
/// What the phone said in FEATURES, 0 until it does (an old phone never sends it).
static FEATURES_BITS: AtomicU32 = AtomicU32::new(0);
/// INPUT_MODE 3: the desktop's cursor is in the picture; this computer's own pointer must not double it.
static PICTURE_CURSOR: AtomicBool = AtomicBool::new(false);
/// KEYBOARD (32) kind 1 goes out once per connection, after FEATURES bit 1 arrives.
static KEYBOARD_SENT: AtomicBool = AtomicBool::new(false);
/// A file goes in pieces of this size, so that the mouse never waits long behind one.
const FILE_CHUNK: usize = 64 * 1024;
const FILE_MAX_WAITING: usize = 16;
const IN_BUTTON: u8 = 21;
const IN_SCROLL: u8 = 22;
const IN_KEY: u8 = 23;
const IN_TEXT: u8 = 24;
const IN_ABS: u8 = 25;
const IN_LAYOUT: u8 = 26;
const IN_SCROLL_FINE: u8 = 33;
const WELCOME_OK: u8 = 0;
const WELCOME_PAIR: u8 = 1;
const WELCOME_BUSY: u8 = 2;
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
// Pairing, as XDesk Receiver does it (PROTOCOL.md): connections that have not proved themselves, how long they get, and
// failed pairings per address before a lockout, from anyone within a window, and in all until one succeeds
const MAX_UNPROVEN: usize = 16;
const MAX_UNPROVEN_PER_ADDRESS: usize = 4;
const HELLO_WITHIN: Duration = Duration::from_secs(10);
const PAIRING_WITHIN: Duration = Duration::from_secs(120);
const ADDRESS_FAILURES: u32 = 3;
const ALL_FAILURES: usize = 10;
const TOTAL_FAILURES: u32 = 30;
const LOCKOUT: Duration = Duration::from_secs(60);
const ALL_WINDOW: Duration = Duration::from_secs(600);
const ALL_LOCKOUT: Duration = Duration::from_secs(600);

pub enum Out {
    Key(u16, bool),
    Text(String),
    Abs(u16, u16),
    Button(u8, bool),
    Scroll(i16, i16),
    /// dy, dx in 1/120 of a wheel step, and the phase (0 a wheel, 1 began, 2 changed, 3 ended, 4 to 6 the coast).
    ScrollFine(i16, i16, u8),
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
/// The phone's natural scrolling (SETTINGS, INPUT.md §12): true is the direction macOS sends its wheel in.
pub static NATURAL_SCROLL: AtomicBool = AtomicBool::new(true);
/// False once there is no point showing the last picture any more.
pub static KEEP_PICTURE: AtomicBool = AtomicBool::new(false);
/// Which connection is the current one: the newest to finish its handshake.
static GENERATION: AtomicU64 = AtomicU64::new(0);
pub static VIA_USB: AtomicBool = AtomicBool::new(false);
/// This computer asked to end the session: nothing to wait for afterwards.
static USER_ENDED: AtomicBool = AtomicBool::new(false);
/// The same, for the cable: no connecting over it again until it is plugged in again.
static USB_ON_HOLD: AtomicBool = AtomicBool::new(false);
static APPS_LIST: Mutex<Option<Vec<(String, String)>>> = Mutex::new(None);
/// The window's density as the phone counts it, 160 per point's pixel (a Retina window is 320), and the screen's
/// refresh rate: told in WELCOME and, the density, with every SIZE.
static DENSITY: AtomicU32 = AtomicU32::new(160);
static REFRESH: AtomicU32 = AtomicU32::new(60);
/// The phone's scroll speed (SETTINGS), 1 the default, as f32 bits.
static SCROLL_SPEED: AtomicU32 = AtomicU32::new(0x3f80_0000);
/// The phone streaming here: only it may take over (a new cable, a new network); another one gets BUSY.
static CURRENT_PHONE: Mutex<Option<String>> = Mutex::new(None);
static GUARD: LazyLock<Mutex<Guard>> = LazyLock::new(|| Mutex::new(Guard::default()));

/// What keeps the 4-digit code from being guessed (PROTOCOL.md, as XDesk Receiver does it).
#[derive(Default)]
struct Guard {
    unproven: HashMap<IpAddr, usize>,
    unproven_total: usize,
    address_failures: HashMap<IpAddr, u32>,
    locked_until: HashMap<IpAddr, Instant>,
    all_failures: VecDeque<Instant>,
    all_locked_until: Option<Instant>,
    total_failures: u32,
    closed: bool,
    /// The same code across connections until it is entered right, or wrong 3 times.
    code: Option<String>,
    wrong: u32,
}

enum Code {
    Right,
    Wrong,
    Refused,
}

impl Guard {
    fn locked_out(&mut self, ip: IpAddr) -> bool {
        let now = Instant::now();
        if self.closed || self.all_locked_until.is_some_and(|t| t > now) {
            return true;
        }
        match self.locked_until.get(&ip) {
            Some(t) if *t > now => true,
            _ => {
                self.locked_until.remove(&ip);
                false
            }
        }
    }

    /// A wrong code, or a pairing given up without one. True when that locks this address out.
    fn failed(&mut self, ip: IpAddr) -> bool {
        let now = Instant::now();
        let mine = self.address_failures.get(&ip).copied().unwrap_or(0) + 1;
        if mine >= ADDRESS_FAILURES {
            self.address_failures.remove(&ip);
            self.locked_until.insert(ip, now + LOCKOUT);
        } else {
            self.address_failures.insert(ip, mine);
        }
        self.all_failures.push_back(now);
        while self.all_failures.front().is_some_and(|t| now.duration_since(*t) > ALL_WINDOW) {
            self.all_failures.pop_front();
        }
        if self.all_failures.len() >= ALL_FAILURES && !self.all_locked_until.is_some_and(|t| t > now) {
            self.all_failures.clear();
            self.all_locked_until = Some(now + ALL_LOCKOUT);
        }
        self.total_failures += 1;
        if self.total_failures >= TOTAL_FAILURES && !self.closed {
            eprintln!("too many failed pairings: closed until XDesk is opened again");
            self.closed = true;
        }
        self.locked_out(ip)
    }

    fn pairing_code(&mut self) -> String {
        if self.code.is_none() {
            self.code = Some(random_hex(4).bytes().take(4).map(|b| char::from(b'0' + b % 10)).collect());
            self.wrong = 0;
        }
        self.code.clone().unwrap()
    }

    fn check(&mut self, typed: &str, ip: IpAddr) -> Code {
        if self.code.as_deref() == Some(typed.trim()) {
            self.code = None;
            self.address_failures.remove(&ip);
            self.total_failures = 0;
            return Code::Right;
        }
        self.wrong += 1;
        if self.failed(ip) || self.wrong >= MAX_TRIES {
            self.code = None;
            return Code::Refused;
        }
        Code::Wrong
    }
}

/// A connection that has not proved itself yet, counted until it does or closes.
struct Unproven {
    ip: Option<IpAddr>,
}

impl Unproven {
    fn admit(ip: IpAddr) -> Option<Unproven> {
        let mut g = GUARD.lock().unwrap();
        let mine = g.unproven.get(&ip).copied().unwrap_or(0);
        if g.unproven_total >= MAX_UNPROVEN || mine >= MAX_UNPROVEN_PER_ADDRESS {
            return None;
        }
        g.unproven.insert(ip, mine + 1);
        g.unproven_total += 1;
        Some(Unproven { ip: Some(ip) })
    }

    fn proven(&mut self) {
        if let Some(ip) = self.ip.take() {
            let mut g = GUARD.lock().unwrap();
            g.unproven_total -= 1;
            match g.unproven.get(&ip).copied() {
                Some(n) if n > 1 => {
                    g.unproven.insert(ip, n - 1);
                }
                _ => {
                    g.unproven.remove(&ip);
                }
            }
        }
    }
}

impl Drop for Unproven {
    fn drop(&mut self) {
        self.proven();
    }
}
static FILES_WAITING: AtomicUsize = AtomicUsize::new(0);
static FILES: OnceLock<Sender<PathBuf>> = OnceLock::new();

pub fn out(o: Out) {
    if let Some(t) = OUT.get() {
        let _ = t.send(o);
    }
}

/// FEATURES bit 0: the phone handles ANDROID 4 (the switchboard) and 5 (a step of its app switcher).
pub fn features_actions() -> bool {
    FEATURES_BITS.load(Ordering::Relaxed) & FEATURE_ACTIONS != 0
}

/// FEATURES bit 1: the phone handles a Mac keyboard, whose Command goes as Meta (KEY_LEFTMETA/RIGHTMETA).
pub fn raw_command() -> bool {
    FEATURES_BITS.load(Ordering::Relaxed) & FEATURE_RAW_COMMAND != 0
}

/// The picture contains the desktop's cursor (INPUT_MODE 3, from a phone that said FEATURES bit 3): hide this
/// computer's own pointer over it.
pub fn picture_cursor() -> bool {
    PICTURE_CURSOR.load(Ordering::Relaxed) && FEATURES_BITS.load(Ordering::Relaxed) & FEATURE_PICTURE_CURSOR != 0
}

/// FEATURES bit 4: a trackpad's scrolling goes as SCROLL_FINE, with its fractions and phases.
pub fn scroll_fine() -> bool {
    FEATURES_BITS.load(Ordering::Relaxed) & FEATURE_SCROLL_FINE != 0
}

/// The phone's scroll speed (SETTINGS `scrollSpeed`), 1 the default.
pub fn scroll_speed() -> f32 {
    f32::from_bits(SCROLL_SPEED.load(Ordering::Relaxed))
}

/// The screen the window is on: its density as the phone counts it (160 a pixel per point) and its refresh rate.
pub fn set_screen(density: u32, refresh: u32) {
    REFRESH.store(refresh.clamp(24, 240), Ordering::Relaxed);
    DENSITY.store(density.clamp(80, 640), Ordering::Relaxed);
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
    let density = DENSITY.load(Ordering::Relaxed);
    let mut wanted = WANTED.lock().unwrap();
    // Also for the density alone: the window went to a screen with another scale (MACAPP.md §3)
    if *wanted != size || SENT_DENSITY.swap(density, Ordering::Relaxed) != density {
        *wanted = size;
        out(Out::Size(size.0, size.1));
    }
}

/// The density last told the phone, in WELCOME or SIZE.
static SENT_DENSITY: AtomicU32 = AtomicU32::new(0);

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

/// What follows a field's name in the SETTINGS JSON. The phone is the only sender: no full JSON parsing.
fn json_value<'a>(json: &'a str, name: &str) -> Option<&'a str> {
    let key = format!("\"{name}\"");
    let at = json.find(&key)?;
    Some(json[at + key.len()..].trim_start().strip_prefix(':')?.trim_start())
}

fn json_number(json: &str, name: &str) -> Option<f32> {
    let value = json_value(json, name)?;
    let end = value.find(|c: char| !(c.is_ascii_digit() || matches!(c, '.' | '-' | '+' | 'e' | 'E'))).unwrap_or(value.len());
    value[..end].parse().ok().filter(|v: &f32| v.is_finite())
}

/// A boolean field of the SETTINGS JSON, if it has one.
fn json_bool(json: &str, name: &str) -> Option<bool> {
    let value = json_value(json, name)?;
    if value.starts_with("true") {
        Some(true)
    } else if value.starts_with("false") {
        Some(false)
    } else {
        None
    }
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
        Out::ScrollFine(dy, dx, phase) => {
            let (a, b) = (dy.to_be_bytes(), dx.to_be_bytes());
            framed(IN_SCROLL_FINE, &[a[0], a[1], b[0], b[1], *phase])
        }
        Out::Size(w, h) => {
            let (a, b) = ((*w as u16).to_be_bytes(), (*h as u16).to_be_bytes());
            let d = (DENSITY.load(Ordering::Relaxed) as u16).to_be_bytes();
            framed(SIZE, &[a[0], a[1], b[0], b[1], d[0], d[1]])
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
    incoming: Option<IncomingFile>,
}

/// A file from the phone on its way to Downloads, under a hidden name until all of it is there.
struct IncomingFile {
    file: std::fs::File,
    part: PathBuf,
    name: String,
}

fn downloads() -> PathBuf {
    PathBuf::from(std::env::var("HOME").unwrap_or_default()).join("Downloads")
}

/// No way out of the folder, and not hidden.
fn clean_file_name(name: &str) -> String {
    let clean = name.replace(['/', '\\', ':', '\0'], "_");
    let clean = clean.trim().trim_start_matches('.').to_string();
    if clean.is_empty() { tr("文件", "file").to_string() } else { clean }
}

/// The name in that folder, with a number when it is taken.
fn free_path(dir: &std::path::Path, name: &str) -> PathBuf {
    let first = dir.join(name);
    if !first.exists() {
        return first;
    }
    let (stem, extension) = match name.rsplit_once('.') {
        Some((s, e)) if !s.is_empty() => (s, format!(".{e}")),
        _ => (name, String::new()),
    };
    (1..).map(|n| dir.join(format!("{stem} ({n}){extension}"))).find(|p| !p.exists()).unwrap()
}

impl Stream {
    fn drop_file(&mut self) {
        if let Some(i) = self.incoming.take() {
            drop(i.file);
            let _ = std::fs::remove_file(&i.part);
        }
    }

    fn file_start(&mut self, name: &str) {
        self.drop_file();
        if name.is_empty() {
            return;
        }
        let name = clean_file_name(name);
        let part = downloads().join(format!(".{name}.{}.xdesk-part", random_hex(4)));
        match std::fs::File::create(&part) {
            Ok(file) => {
                toast(&format!("{}{name}", tr("正在接收：", "Receiving ")));
                self.incoming = Some(IncomingFile { file, part, name });
            }
            Err(e) => {
                eprintln!("file: {e}");
                toast(&format!("{}{name}", tr("保存失败：", "Could not save ")));
            }
        }
    }

    fn file_data(&mut self, data: &[u8]) {
        let Some(i) = self.incoming.as_mut() else { return };
        if i.file.write_all(data).is_err() {
            toast(&format!("{}{}", tr("保存失败：", "Could not save "), i.name));
            self.drop_file();
        }
    }

    fn file_end(&mut self) {
        let Some(i) = self.incoming.take() else { return };
        drop(i.file);
        let target = free_path(&downloads(), &i.name);
        match std::fs::rename(&i.part, &target) {
            Ok(()) => toast(&format!("{}{}", tr("已收到，在“下载”里：", "Received, in Downloads: "), i.name)),
            Err(_) => {
                let _ = std::fs::remove_file(&i.part);
                toast(&format!("{}{}", tr("保存失败：", "Could not save "), i.name));
            }
        }
    }

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
            INPUT_MODE if p.len() == 1 => {
                crate::tap::DESKTOP.store(p[0] != 0, Ordering::Relaxed);
                // 3 (FEATURES bit 3): the desktop's cursor is in the picture; 0, 1 and 2 mean it is not
                PICTURE_CURSOR.store(p[0] == 3, Ordering::Relaxed);
            }
            FEATURES if p.len() >= 4 => {
                FEATURES_BITS.store(u32::from_be_bytes([p[0], p[1], p[2], p[3]]), Ordering::Relaxed);
            }
            SETTINGS => {
                // {"naturalScroll":true,...}: the phone's input settings, every field optional (INPUT.md §12).
                // A mouse receiver has no speeds of its own: only the wheel direction applies here
                let json = String::from_utf8_lossy(p);
                if let Some(natural) = json_bool(&json, "naturalScroll") {
                    NATURAL_SCROLL.store(natural, Ordering::Relaxed);
                }
                // The wheel and the trackpad scroll this much faster (INPUT.md §12); the pointer is placed, not moved
                if let Some(speed) = json_number(&json, "scrollSpeed") {
                    SCROLL_SPEED.store(speed.clamp(0.25, 4.0).to_bits(), Ordering::Relaxed);
                }
            }
            FILE_START => self.file_start(&String::from_utf8_lossy(p)),
            FILE_DATA => self.file_data(p),
            FILE_END => self.file_end(),
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

/// Before each read the socket gets what is left of the time this step has, so slow bytes cannot hold it longer.
fn by(s: &TcpStream, until: Instant) -> std::io::Result<()> {
    let left = until.saturating_duration_since(Instant::now());
    if left.is_zero() {
        return Err(ErrorKind::TimedOut.into());
    }
    s.set_read_timeout(Some(left))
}

fn read_string(s: &mut TcpStream, until: Instant) -> std::io::Result<String> {
    by(s, until)?;
    let mut len = [0u8; 2];
    s.read_exact(&mut len)?;
    let n = u16::from_be_bytes(len) as usize;
    if n > 1024 {
        return Err(ErrorKind::InvalidData.into());
    }
    let mut b = vec![0u8; n];
    by(s, until)?;
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

/// Compared in the same time whatever the bytes, so the answer cannot be found a byte at a time.
fn same(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0u8, |d, (x, y)| d | (x ^ y)) == 0
}

fn welcome_ok(s: &mut TcpStream, id: &str, name: &str, token: &str) -> std::io::Result<()> {
    let (w, h) = *WANTED.lock().unwrap();
    let density = DENSITY.load(Ordering::Relaxed);
    SENT_DENSITY.store(density, Ordering::Relaxed);
    let mut b = vec![WELCOME_OK];
    put_string(&mut b, id);
    put_string(&mut b, name);
    put_string(&mut b, token);
    for v in [w, h, density, REFRESH.load(Ordering::Relaxed)] {
        b.extend_from_slice(&(v as u16).to_be_bytes());
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

/// The phone streaming here may come again on a new connection (another cable, another network); a phone that is not
/// streaming here may come when none is.
fn may_take(phone: &str, proven: bool) -> bool {
    match CURRENT_PHONE.lock().unwrap().as_deref() {
        None => true,
        Some(current) => proven && current == phone,
    }
}

/// The phone's id when it may stream to this computer.
fn handshake(s: &mut TcpStream, id: &str, name: &str, ip: IpAddr, unproven: &mut Unproven) -> std::io::Result<Option<String>> {
    let hello_until = Instant::now() + HELLO_WITHIN;
    let mut head = [0u8; 5];
    by(s, hello_until)?;
    s.read_exact(&mut head)?;
    if &head[..4] != b"XDSK" {
        return Ok(None);
    }
    if head[4] != PROTOCOL_VERSION {
        welcome(s, WELCOME_VERSION, id, name)?;
        say(tr("手机上的 XDesk 版本和这个程序不匹配，请把两边都更新到最新", "XDesk on the phone and this app do not match: update both"), "");
        return Ok(None);
    }
    let phone = read_string(s, hello_until)?;
    let phone_name = read_string(s, hello_until)?;
    let phone_nonce = read_string(s, hello_until)?;
    if !phone.chars().all(|c| c.is_ascii_hexdigit()) || phone.len() != 32 {
        return Ok(None);
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
    let answer = read_string(s, hello_until)?;
    let proven = !token.is_empty() && same(&answer, &proof(&token, "phone", &phone, id, &phone_nonce, &nonce));
    if !proven && GUARD.lock().unwrap().locked_out(ip) {
        welcome(s, WELCOME_REFUSED, id, name)?;
        return Ok(None);
    }
    if !may_take(&phone, proven) {
        welcome(s, WELCOME_BUSY, id, name)?;
        return Ok(None);
    }
    if proven {
        unproven.proven();
        welcome_ok(s, id, name, "")?;
        return Ok(Some(phone));
    }
    let code = GUARD.lock().unwrap().pairing_code();
    let shown = format!("{} {phone_name} {}", tr("在", "Type this code on"), tr("上输入这个配对码", ""));
    say(shown.trim(), &code);
    let pair_until = Instant::now() + PAIRING_WITHIN;
    let mut typed_any = false;
    let result = (|| -> std::io::Result<Option<String>> {
        loop {
            welcome(s, WELCOME_PAIR, id, name)?;
            let typed = read_string(s, pair_until)?;
            typed_any = true;
            let verdict = GUARD.lock().unwrap().check(&typed, ip);
            match verdict {
                Code::Right => {
                    let new_token = random_hex(32);
                    save(&token_file, &new_token);
                    unproven.proven();
                    welcome_ok(s, id, name, &new_token)?;
                    return Ok(Some(phone.clone()));
                }
                Code::Wrong => {}
                Code::Refused => {
                    welcome(s, WELCOME_REFUSED, id, name)?;
                    say(tr("配对码错太多次，请稍后重新连接", "Too many wrong codes: connect again later"), "");
                    return Ok(None);
                }
            }
        }
    })();
    if !matches!(result, Ok(Some(_))) {
        // Given up without a code counts as a failed pairing too; the code goes from the window either way
        if !typed_any {
            GUARD.lock().unwrap().failed(ip);
        }
        if notice().big == code {
            say(how_to_connect(), "");
        }
    }
    result
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
    FEATURES_BITS.store(0, Ordering::Relaxed);
    PICTURE_CURSOR.store(false, Ordering::Relaxed);
    KEYBOARD_SENT.store(false, Ordering::Relaxed);
    crate::stats::reset();
    STREAMING.store(true, Ordering::Relaxed);
    KEEP_PICTURE.store(true, Ordering::Relaxed);
    // A phone that never sends SETTINGS gets the defaults, not the last phone's
    NATURAL_SCROLL.store(true, Ordering::Relaxed);
    SCROLL_SPEED.store(1f32.to_bits(), Ordering::Relaxed);
    say(if usb { tr("已通过 USB 连接", "Connected over USB") } else { tr("已通过 Wi-Fi 连接", "Connected over Wi-Fi") }, "");
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
        stream.drop_file();
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
            // This keyboard is a Mac keyboard: said once, after FEATURES bit 1 arrived, so the phone translates
            // Command (now Meta) per target
            if raw_command() && !KEYBOARD_SENT.swap(true, Ordering::Relaxed) {
                writer.write_all(&framed(KEYBOARD, &[1]))?;
                last_out = Instant::now();
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
    let Ok(ip) = s.peer_addr().map(|a| a.ip()) else { return };
    // Connections that have not proved themselves are few, and each gets 10 seconds, a pairing 2 minutes
    let Some(mut unproven) = Unproven::admit(ip) else { return };
    let phone = match handshake(&mut s, id, name, ip, &mut unproven) {
        Ok(Some(phone)) => phone,
        _ => return,
    };
    drop(unproven);
    // The phone is back or has moved to another cable: this one replaces whatever it had before
    if let Some(old) = CURRENT.lock().unwrap().take() {
        let _ = old.shutdown(Shutdown::Both);
    }
    let mine = GENERATION.fetch_add(1, Ordering::Relaxed) + 1;
    *CURRENT.lock().unwrap() = s.try_clone().ok();
    *CURRENT_PHONE.lock().unwrap() = Some(phone);
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
    *CURRENT_PHONE.lock().unwrap() = None;
    crate::tap::DESKTOP.store(false, Ordering::Relaxed);
    STREAMING.store(false, Ordering::Relaxed);
    VIA_USB.store(false, Ordering::Relaxed);
    if ended || USER_ENDED.swap(false, Ordering::Relaxed) {
        KEEP_PICTURE.store(false, Ordering::Relaxed);
        *LOST_AT.lock().unwrap() = None;
        say(how_to_connect(), "");
    } else {
        say(tr("连接断开，正在等手机重新连接", "Connection lost, waiting for the phone to come back"), "");
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
        USB_ON_HOLD.store(true, Ordering::Relaxed);
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
        toast(tr("连上手机后才能传文件", "Connect the phone first to send files"));
        return;
    }
    let size = match std::fs::metadata(path) {
        Ok(m) if m.is_file() => m.len(),
        _ => {
            toast(&format!("{}{name}", tr("只能传文件，不能传文件夹：", "Only files, not folders: ")));
            return;
        }
    };
    let Ok(mut file) = std::fs::File::open(path) else {
        toast(&format!("{}{name}", tr("打不开：", "Cannot open ")));
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
            toast(&format!("{}{name}", tr("连接断了，没传完：", "Connection lost before the end of ")));
            return;
        }
        let n = match file.read(&mut buffer) {
            Ok(n) => n,
            Err(_) => {
                out(Out::FileStart(String::new()));
                toast(&format!("{}{name}", tr("读取失败：", "Could not read ")));
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
            toast(&format!("{} {name}: {}%", tr("正在传", "Sending"), sent * 100 / size));
        }
    }
    out(Out::FileEnd);
    toast(&format!("{}{name}", tr("已传到手机的“下载”：", "Sent to the phone's Downloads: ")));
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
        say(tr("端口被占用，可能已经开着另一个 XDesk", "The port is in use: another XDesk may be open"), "");
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
/// For one device by its serial: without it adb refuses as soon as two are plugged in.
fn connect_over_usb(path: &str, serial: &str, name: &str) {
    let port = format!("tcp:{RECEIVER_PORT}");
    if adb(path, &["-s", serial, "reverse", &port, &port]).is_none() {
        return;
    }
    let running = adb(path, &["-s", serial, "shell", "pidof", PACKAGE]).is_some_and(|s| !s.trim().is_empty());
    if !running {
        let activity = format!("{PACKAGE}/com.termux.x11.XDeskActivity");
        let _ = adb(path, &["-s", serial, "shell", "am", "start", "-n", &activity]);
        std::thread::sleep(Duration::from_millis(1500));
    }
    let receiver = format!("{PACKAGE}/com.termux.x11.ConnectReceiver");
    let quoted = format!("'{}'", name.replace('\'', ""));
    let _ = adb(path, &["-s", serial, "shell", "am", "broadcast", "-n", &receiver, "--es", "host", "127.0.0.1", "--ei", "port", &RECEIVER_PORT.to_string(), "--es", "name", &quoted]);
}

fn how_to_connect() -> &'static str {
    tr("插上手机（打开 USB 调试），或者在手机的 XDesk 里选“连接接收器”", "Plug in the phone (with USB debugging on), or choose this computer in XDesk on the phone")
}

fn watch_usb(path: Option<String>, name: String) {
    let mut tries = 0;
    let mut was_up = false;
    loop {
        let devices = path.as_deref().and_then(|p| adb(p, &["devices"])).unwrap_or_default();
        let lines: Vec<&str> = devices.lines().skip(1).collect();
        let ready: Vec<&str> = lines.iter().filter_map(|l| l.strip_suffix("\tdevice")).collect();
        let up = !ready.is_empty();
        let unauthorized = lines.iter().any(|l| l.ends_with("\tunauthorized"));
        if up && !was_up {
            tries = USB_TRIES;
        }
        was_up = up;
        if USB_ON_HOLD.swap(false, Ordering::Relaxed) {
            tries = 0;
        }
        let streaming = STREAMING.load(Ordering::Relaxed);
        if up && tries > 0 && !VIA_USB.load(Ordering::Relaxed) {
            tries -= 1;
            if let Some(p) = &path {
                if !streaming {
                    say(tr("已插线，正在连接手机", "Cable in, connecting to the phone"), "");
                }
                for serial in &ready {
                    connect_over_usb(p, serial, &name);
                }
            }
        } else if !streaming {
            let lost = LOST_AT.lock().unwrap().is_some_and(|t| t.elapsed() < RECONNECT_NOTICE);
            let pairing = !notice().big.is_empty();
            if !lost {
                KEEP_PICTURE.store(false, Ordering::Relaxed);
            }
            if !lost && !pairing {
                if unauthorized {
                    say(tr("在手机上点“允许”这台电脑的 USB 调试", "On the phone, allow USB debugging for this computer"), "");
                } else if up {
                    say(tr("已插线，在手机的 XDesk 里连接这台电脑，或者重新插一次线", "Cable in: connect to this computer in XDesk on the phone, or plug the cable in again"), "");
                } else {
                    say(how_to_connect(), "");
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
    say(how_to_connect(), "");
    let (id, name) = (stored_id("receiver_id"), computer_name());
    let sinks = Sinks { video, audio, keyframe };
    let rx = Arc::new(Mutex::new(rx));
    let listen_name = name.clone();
    std::thread::spawn(move || listen(rx, sinks, id, listen_name));
    std::thread::spawn(move || watch_usb(adb_path(), name));
}
