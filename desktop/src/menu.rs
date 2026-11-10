//! A "手机" menu in the Mac's menu bar, doing what the keyboard chords and the bar on the picture do.

use crate::clip::{class, selector, send0, send1, Id};
use crate::tap;
use std::ffi::{c_char, c_void, CString};
use std::ptr::null_mut;

#[link(name = "objc")]
extern "C" {
    fn objc_allocateClassPair(superclass: Id, name: *const c_char, extra: usize) -> Id;
    fn objc_registerClassPair(class: Id);
    fn class_addMethod(class: Id, selector: Id, implementation: *const c_void, types: *const c_char) -> bool;
    fn objc_msgSend();
    fn objc_autoreleasePoolPush() -> Id;
    fn objc_autoreleasePoolPop(pool: Id);
}

const ITEMS: [(&str, u8); 7] = [
    ("返回    ⌃⌥B", tap::ACT_BACK),
    ("主页    ⌃⌥H", tap::ACT_HOME),
    ("最近任务    ⌃⌥R", tap::ACT_RECENTS),
    ("全屏    ⌃⌥F", tap::ACT_FULLSCREEN),
    ("延迟面板    ⌃⌥D", tap::ACT_PANEL),
    ("低延迟模式    ⌃⌥L", tap::ACT_LOW_LATENCY),
    ("断开手机", tap::ACT_DISCONNECT),
];

extern "C" fn clicked(_this: Id, _selector: Id, sender: Id) {
    tap::request(unsafe { send0(sender, "tag") } as usize as u8);
}

unsafe fn text(s: &str) -> Id {
    let c = CString::new(s).unwrap();
    send1(class("NSString"), "stringWithUTF8String:", c.as_ptr() as Id)
}

unsafe fn with_title(kind: &str, init: &str, title: &str) -> Id {
    send1(send0(class(kind), "alloc"), init, text(title))
}

/// After the window exists: SDL makes the main menu when it starts.
pub fn install() {
    unsafe {
        let pool = objc_autoreleasePoolPush();
        let main = send0(send0(class("NSApplication"), "sharedApplication"), "mainMenu");
        if !main.is_null() {
            let name = CString::new("XDeskMenuTarget").unwrap();
            let kind = objc_allocateClassPair(class("NSObject"), name.as_ptr(), 0);
            let types = CString::new("v@:@").unwrap();
            class_addMethod(kind, selector("clicked:"), clicked as *const c_void, types.as_ptr());
            objc_registerClassPair(kind);
            let target = send0(send0(kind, "alloc"), "init");
            let new_item: extern "C" fn(Id, Id, Id, Id, Id) -> Id = std::mem::transmute(objc_msgSend as unsafe extern "C" fn());
            let empty = text("");
            let menu = with_title("NSMenu", "initWithTitle:", "手机");
            for (title, action) in ITEMS {
                let item = new_item(send0(class("NSMenuItem"), "alloc"), selector("initWithTitle:action:keyEquivalent:"), text(title), selector("clicked:"), empty);
                send1(item, "setTarget:", target);
                send1(item, "setTag:", action as usize as Id);
                send1(menu, "addItem:", item);
            }
            let holder = new_item(send0(class("NSMenuItem"), "alloc"), selector("initWithTitle:action:keyEquivalent:"), text("手机"), null_mut(), empty);
            send1(holder, "setSubmenu:", menu);
            send1(main, "addItem:", holder);
        }
        objc_autoreleasePoolPop(pool);
    }
}
