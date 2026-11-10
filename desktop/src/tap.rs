//! The keyboard, taken from macOS with an event tap while this window is in front and the pointer is on it, so that
//! Cmd+Space, Control+Space and Cmd+Tab reach the phone. Needs the Accessibility permission of whatever started it.

use crate::net::{out, set_layout, Out};
use std::collections::HashSet;
use std::os::raw::{c_ulong, c_void};
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};
use std::sync::{LazyLock, Mutex};

type Ref = *mut c_void;
type Callback = extern "C" fn(Ref, u32, Ref, Ref) -> Ref;

#[link(name = "ApplicationServices", kind = "framework")]
extern "C" {
    static kAXTrustedCheckOptionPrompt: Ref;
    fn AXIsProcessTrusted() -> bool;
    fn AXIsProcessTrustedWithOptions(options: Ref) -> bool;
    fn CGEventTapCreate(tap: u32, place: u32, options: u32, mask: u64, callback: Callback, info: Ref) -> Ref;
    fn CGEventTapEnable(tap: Ref, enable: bool);
    fn CGEventGetIntegerValueField(event: Ref, field: u32) -> i64;
    fn CGEventGetFlags(event: Ref) -> u64;
    fn CGEventKeyboardGetUnicodeString(event: Ref, max: c_ulong, actual: *mut c_ulong, chars: *mut u16);
}

#[link(name = "CoreFoundation", kind = "framework")]
extern "C" {
    static kCFRunLoopCommonModes: Ref;
    static kCFBooleanTrue: Ref;
    static kCFTypeDictionaryKeyCallBacks: u8;
    static kCFTypeDictionaryValueCallBacks: u8;
    fn CFDictionaryCreate(allocator: Ref, keys: *const Ref, values: *const Ref, count: isize, key_callbacks: *const u8, value_callbacks: *const u8) -> Ref;
    fn CFRelease(object: Ref);
    fn CFMachPortCreateRunLoopSource(allocator: Ref, port: Ref, order: i64) -> Ref;
    fn CFRunLoopGetCurrent() -> Ref;
    fn CFRunLoopAddSource(run_loop: Ref, source: Ref, mode: Ref);
}

const SESSION_TAP: u32 = 1;
const KEY_DOWN: u32 = 10;
const KEY_UP: u32 = 11;
const FLAGS_CHANGED: u32 = 12;
const DISABLED_TIMEOUT: u32 = 0xFFFF_FFFE;
const DISABLED_INPUT: u32 = 0xFFFF_FFFF;
const FIELD_AUTOREPEAT: u32 = 8;
const FIELD_KEYCODE: u32 = 9;
const SHIFT: u64 = 0x20000;
const CONTROL: u64 = 0x40000;
const OPTION: u64 = 0x80000;
const COMMAND: u64 = 0x100000;
const KEY_ESC: i64 = 53;
/// What Control+Option with these keys does; the bar on the picture and the menu do the same.
pub const ACT_BACK: u8 = 11;
pub const ACT_HOME: u8 = 4;
pub const ACT_RECENTS: u8 = 15;
pub const ACT_FULLSCREEN: u8 = 3;
pub const ACT_PANEL: u8 = 2;
pub const ACT_LOW_LATENCY: u8 = 37;
pub const ACT_DISCONNECT: u8 = 200;
const CHORD_KEYS: [u8; 6] = [ACT_BACK, ACT_HOME, ACT_RECENTS, ACT_FULLSCREEN, ACT_PANEL, ACT_LOW_LATENCY];
/// Linux codes of Ctrl and Alt: what made a character must not stay held on the other side.
const CTRL_ALT: [u16; 4] = [29, 97, 56, 100];

static TAP: AtomicPtr<c_void> = AtomicPtr::new(null_mut());
pub static FOCUSED: AtomicBool = AtomicBool::new(false);
pub static INSIDE: AtomicBool = AtomicBool::new(false);
pub static RELEASED: AtomicBool = AtomicBool::new(false);
static ACTIONS: Mutex<Vec<u8>> = Mutex::new(Vec::new());

pub fn request(action: u8) {
    ACTIONS.lock().unwrap().push(action);
}

pub fn take_actions() -> Vec<u8> {
    std::mem::take(&mut *ACTIONS.lock().unwrap())
}

#[derive(Default)]
struct State {
    held: HashSet<u16>,
    typed: HashSet<i64>,
    modifiers: HashSet<i64>,
}

static STATE: LazyLock<Mutex<State>> = LazyLock::new(Mutex::default);

pub fn active() -> bool {
    FOCUSED.load(Ordering::Relaxed) && INSIDE.load(Ordering::Relaxed) && !RELEASED.load(Ordering::Relaxed)
}

fn key(state: &mut State, scan: u16, down: bool) {
    if if down { state.held.insert(scan) } else { state.held.remove(&scan) } {
        out(Out::Key(scan, down));
    }
}

/// Nothing stays pressed on the phone once the keyboard goes back to the Mac.
pub fn release_all() {
    let mut s = STATE.lock().unwrap();
    for scan in std::mem::take(&mut s.held) {
        out(Out::Key(scan, false));
    }
    s.typed.clear();
}

/// macOS key code → Linux key code, by place on the keyboard.
fn linux_code(mac: i64) -> u16 {
    match mac {
        0 => 30, 1 => 31, 2 => 32, 3 => 33, 4 => 35, 5 => 34, 6 => 44, 7 => 45, 8 => 46, 9 => 47,
        10 => 41, 11 => 48, 12 => 16, 13 => 17, 14 => 18, 15 => 19, 16 => 21, 17 => 20, 18 => 2, 19 => 3,
        20 => 4, 21 => 5, 22 => 7, 23 => 6, 24 => 13, 25 => 10, 26 => 8, 27 => 12, 28 => 9, 29 => 11,
        30 => 27, 31 => 24, 32 => 22, 33 => 26, 34 => 23, 35 => 25, 36 => 28, 37 => 38, 38 => 36, 39 => 40,
        40 => 37, 41 => 39, 42 => 43, 43 => 51, 44 => 53, 45 => 49, 46 => 50, 47 => 52, 48 => 15, 49 => 57,
        50 => 86, 51 => 14, 53 => 1,
        65 => 83, 67 => 55, 69 => 78, 71 => 69, 75 => 98, 76 => 96, 78 => 74, 82 => 82, 83 => 79, 84 => 80,
        85 => 81, 86 => 75, 87 => 76, 88 => 77, 89 => 71, 91 => 72, 92 => 73,
        96 => 63, 97 => 64, 98 => 65, 99 => 61, 100 => 66, 101 => 67, 103 => 87, 109 => 68, 111 => 88,
        118 => 62, 120 => 60, 122 => 59,
        114 => 110, 115 => 102, 116 => 104, 117 => 111, 119 => 107, 121 => 109,
        123 => 105, 124 => 106, 125 => 108, 126 => 103,
        _ => 0,
    }
}

/// The Linux code a modifier key sends, with its flag. Command does what Ctrl does on the phone.
fn modifier(mac: i64) -> Option<(u16, u64)> {
    match mac {
        56 => Some((42, SHIFT)),
        60 => Some((54, SHIFT)),
        59 => Some((29, CONTROL)),
        62 => Some((97, CONTROL)),
        58 => Some((56, OPTION)),
        61 => Some((100, OPTION)),
        55 => Some((29, COMMAND)),
        54 => Some((97, COMMAND)),
        _ => None,
    }
}

fn typed_text(event: Ref) -> String {
    let mut chars = [0u16; 8];
    let mut length: c_ulong = 0;
    unsafe { CGEventKeyboardGetUnicodeString(event, 8, &mut length, chars.as_mut_ptr()) };
    String::from_utf16_lossy(&chars[..length as usize])
}

fn printable(text: &str) -> bool {
    let mut it = text.chars();
    match (it.next(), it.next()) {
        (Some(c), None) => c > ' ' && c != '\u{7f}' && !('\u{f700}'..='\u{f8ff}').contains(&c),
        _ => false,
    }
}

fn learn_layout(mac: i64, text: &str) {
    let lower = text.to_lowercase();
    let c = lower.chars().next().unwrap_or('\0');
    let found = match (mac, c) {
        (16, 'z') | (6, 'y') | (_, 'ä' | 'ö' | 'ü' | 'ß') => "de",
        (12, 'a') | (0, 'q') => "fr",
        (_, 'ñ') => "es",
        (_, 'ò' | 'ì') => "it",
        (_, '£') => "gb",
        _ => return,
    };
    set_layout(found);
}

fn key_down(event: Ref, mac: i64, flags: u64) {
    const KEY_V: i64 = 9;
    if mac == KEY_V && flags & COMMAND != 0 {
        crate::clip::flush();
    }
    let text = typed_text(event);
    let mut s = STATE.lock().unwrap();
    if flags & OPTION == 0 {
        learn_layout(mac, &text);
    }
    if flags & (COMMAND | CONTROL) == 0 && printable(&text) {
        for scan in CTRL_ALT {
            key(&mut s, scan, false);
        }
        s.typed.insert(mac);
        out(Out::Text(text));
        return;
    }
    let scan = linux_code(mac);
    if scan == 0 {
        return;
    }
    if unsafe { CGEventGetIntegerValueField(event, FIELD_AUTOREPEAT) } != 0 {
        key(&mut s, scan, false);
    }
    key(&mut s, scan, true);
}

fn key_up(mac: i64) {
    let mut s = STATE.lock().unwrap();
    if s.typed.remove(&mac) {
        return;
    }
    let scan = linux_code(mac);
    if scan != 0 {
        key(&mut s, scan, false);
    }
}

fn flags_changed(mac: i64, flags: u64) {
    let Some((scan, flag)) = modifier(mac) else { return };
    let mut s = STATE.lock().unwrap();
    let down = flags & flag != 0 && !s.modifiers.contains(&mac);
    if down {
        s.modifiers.insert(mac);
    } else {
        s.modifiers.remove(&mac);
    }
    if active() {
        key(&mut s, scan, down);
    }
}

extern "C" fn callback(_proxy: Ref, kind: u32, event: Ref, _info: Ref) -> Ref {
    if kind == DISABLED_TIMEOUT || kind == DISABLED_INPUT {
        unsafe { CGEventTapEnable(TAP.load(Ordering::Relaxed), true) };
        return event;
    }
    let mac = unsafe { CGEventGetIntegerValueField(event, FIELD_KEYCODE) };
    let flags = unsafe { CGEventGetFlags(event) };
    if kind == FLAGS_CHANGED {
        flags_changed(mac, flags);
        return event;
    }
    if !active() {
        if kind == KEY_UP {
            STATE.lock().unwrap().typed.remove(&mac);
        }
        return event;
    }
    let chord = flags & CONTROL != 0 && flags & OPTION != 0;
    if chord && kind == KEY_DOWN && mac == KEY_ESC {
        RELEASED.store(true, Ordering::Relaxed);
        release_all();
        crate::say_released();
        return null_mut();
    }
    if chord && CHORD_KEYS.contains(&(mac as u8)) {
        if kind == KEY_DOWN && unsafe { CGEventGetIntegerValueField(event, FIELD_AUTOREPEAT) } == 0 {
            request(mac as u8);
        }
        return null_mut();
    }
    match kind {
        KEY_DOWN => key_down(event, mac, flags),
        KEY_UP => key_up(mac),
        _ => return event,
    }
    null_mut()
}

/// Opens the system's question about the Accessibility permission, once.
pub fn ask_for_access() {
    unsafe {
        let keys = [kAXTrustedCheckOptionPrompt];
        let values = [kCFBooleanTrue];
        let options = CFDictionaryCreate(null_mut(), keys.as_ptr(), values.as_ptr(), 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
        AXIsProcessTrustedWithOptions(options);
        CFRelease(options);
    }
}

/// On the main thread, whose run loop SDL turns. False when macOS does not allow it.
pub fn install() -> bool {
    unsafe {
        if !AXIsProcessTrusted() {
            return false;
        }
        let mask = 1u64 << KEY_DOWN | 1 << KEY_UP | 1 << FLAGS_CHANGED;
        let tap = CGEventTapCreate(SESSION_TAP, 0, 0, mask, callback, null_mut());
        if tap.is_null() {
            return false;
        }
        TAP.store(tap, Ordering::Relaxed);
        let source = CFMachPortCreateRunLoopSource(null_mut(), tap, 0);
        CFRunLoopAddSource(CFRunLoopGetCurrent(), source, kCFRunLoopCommonModes);
        CGEventTapEnable(tap, true);
        true
    }
}
