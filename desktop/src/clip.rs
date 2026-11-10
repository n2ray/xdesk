//! The Mac's clipboard as text, through the Objective-C runtime. Text copied here goes to the phone, and the phone's comes here.

use crate::net::{out, Out, STREAMING};
use std::ffi::{c_char, c_void, CStr, CString};
use std::sync::atomic::Ordering;
use std::sync::Mutex;
use std::time::Duration;

pub type Id = *mut c_void;

#[link(name = "objc")]
extern "C" {
    fn objc_getClass(name: *const c_char) -> Id;
    fn sel_registerName(name: *const c_char) -> Id;
    fn objc_msgSend();
    fn objc_autoreleasePoolPush() -> Id;
    fn objc_autoreleasePoolPop(pool: Id);
}

#[link(name = "AppKit", kind = "framework")]
extern "C" {
    static NSPasteboardTypeString: Id;
}

const POLL: Duration = Duration::from_millis(400);
const MAX_BYTES: usize = 256 * 1024;

/// Last text known to be on both sides, and the Mac's change count that goes with it.
static LAST: Mutex<(Option<String>, isize)> = Mutex::new((None, -1));

pub fn selector(name: &str) -> Id {
    let c = CString::new(name).unwrap();
    unsafe { sel_registerName(c.as_ptr()) }
}

pub fn class(name: &str) -> Id {
    let c = CString::new(name).unwrap();
    unsafe { objc_getClass(c.as_ptr()) }
}

pub unsafe fn send0(target: Id, sel: &str) -> Id {
    let f: extern "C" fn(Id, Id) -> Id = std::mem::transmute(objc_msgSend as unsafe extern "C" fn());
    f(target, selector(sel))
}

pub unsafe fn send1(target: Id, sel: &str, a: Id) -> Id {
    let f: extern "C" fn(Id, Id, Id) -> Id = std::mem::transmute(objc_msgSend as unsafe extern "C" fn());
    f(target, selector(sel), a)
}

unsafe fn pasteboard() -> Id {
    send0(class("NSPasteboard"), "generalPasteboard")
}

unsafe fn change_count() -> isize {
    send0(pasteboard(), "changeCount") as isize
}

unsafe fn read_text() -> Option<String> {
    let s = send1(pasteboard(), "stringForType:", NSPasteboardTypeString);
    if s.is_null() {
        return None;
    }
    let p = send0(s, "UTF8String") as *const c_char;
    (!p.is_null()).then(|| CStr::from_ptr(p).to_string_lossy().into_owned())
}

unsafe fn write_text(text: &str) {
    let Ok(c) = CString::new(text) else { return };
    let string = send1(class("NSString"), "stringWithUTF8String:", c.as_ptr() as Id);
    let board = pasteboard();
    send0(board, "clearContents");
    let f: extern "C" fn(Id, Id, Id, Id) -> Id = std::mem::transmute(objc_msgSend as unsafe extern "C" fn());
    f(board, selector("setString:forType:"), string, NSPasteboardTypeString);
}

/// From the phone.
pub fn set(text: &str) {
    unsafe {
        let pool = objc_autoreleasePoolPush();
        write_text(text);
        *LAST.lock().unwrap() = (Some(text.to_string()), change_count());
        objc_autoreleasePoolPop(pool);
    }
}

/// Sends what changed since the last look. Also right before Cmd+V, so that it pastes what was just copied.
pub fn flush() {
    if !STREAMING.load(Ordering::Relaxed) {
        return;
    }
    unsafe {
        let pool = objc_autoreleasePoolPush();
        let count = change_count();
        let mut last = LAST.lock().unwrap();
        if last.1 != count {
            last.1 = count;
            let text = read_text();
            if text.is_some() && text != last.0 {
                let t = text.clone().unwrap();
                if t.len() <= MAX_BYTES {
                    out(Out::Clipboard(t));
                }
                last.0 = text;
            }
        }
        objc_autoreleasePoolPop(pool);
    }
}

/// What is on the clipboard when a session begins stays out of it.
pub fn baseline() {
    unsafe {
        let pool = objc_autoreleasePoolPush();
        *LAST.lock().unwrap() = (read_text(), change_count());
        objc_autoreleasePoolPop(pool);
    }
}

pub fn start() {
    std::thread::spawn(|| loop {
        std::thread::sleep(POLL);
        flush();
    });
}
