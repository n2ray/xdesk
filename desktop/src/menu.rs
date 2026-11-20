//! A "手机" (Phone) menu in the Mac's menu bar, doing what the keyboard chords and the bar on the picture do.

use crate::clip::{class, selector, send0, send1, Id};
use crate::lang::tr;
use crate::tap;
use std::ffi::{c_char, c_void, CString};
use std::ptr::null_mut;
use std::sync::atomic::{AtomicPtr, Ordering};

#[link(name = "objc")]
extern "C" {
    fn objc_allocateClassPair(superclass: Id, name: *const c_char, extra: usize) -> Id;
    fn objc_registerClassPair(class: Id);
    fn class_addMethod(class: Id, selector: Id, implementation: *const c_void, types: *const c_char) -> bool;
    fn objc_msgSend();
    fn objc_autoreleasePoolPush() -> Id;
    fn objc_autoreleasePoolPop(pool: Id);
}

/// The item whose title says whether low latency is on, as MacApp.dc.html shows it.
static LOW_LATENCY_ITEM: AtomicPtr<c_void> = AtomicPtr::new(null_mut());

fn low_latency(on: bool) -> &'static str {
    if on {
        tr("低延迟模式 开", "Low latency on")
    } else {
        tr("低延迟模式 关", "Low latency off")
    }
}

fn items(low_latency_on: bool) -> [(&'static str, u8); 9] {
    [
        (tr("主页    ⌃⌥H", "Home    ⌃⌥H"), tap::ACT_HOME),
        (tr("返回    ⌃⌥B", "Back    ⌃⌥B"), tap::ACT_BACK),
        // A phone from before 0.6 shows its recent apps here instead
        (tr("启动台    ⌃⌥R", "Launchpad    ⌃⌥R"), tap::ACT_RECENTS),
        (tr("全屏    ⌃⌥F", "Full screen    ⌃⌥F"), tap::ACT_FULLSCREEN),
        (low_latency(low_latency_on), tap::ACT_LOW_LATENCY),
        (tr("延迟面板    ⌃⌥D", "Latency panel    ⌃⌥D"), tap::ACT_PANEL),
        (tr("断开手机", "Disconnect the phone"), tap::ACT_DISCONNECT),
        (tr("检查更新", "Check for updates"), tap::ACT_UPDATE),
        (tr("关于 XDesk", "About XDesk"), tap::ACT_ABOUT),
    ]
}

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
    let low_latency_on = crate::net::load("low_latency") != "0";
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
            let menu = with_title("NSMenu", "initWithTitle:", tr("手机", "Phone"));
            for (title, action) in items(low_latency_on) {
                let item = new_item(send0(class("NSMenuItem"), "alloc"), selector("initWithTitle:action:keyEquivalent:"), text(title), selector("clicked:"), empty);
                send1(item, "setTarget:", target);
                send1(item, "setTag:", action as usize as Id);
                if action == tap::ACT_LOW_LATENCY {
                    // No check mark: the title itself says 开 or 关 (MacApp.dc.html)
                    send1(item, "setState:", null_mut());
                    LOW_LATENCY_ITEM.store(item, Ordering::Relaxed);
                }
                send1(menu, "addItem:", item);
            }
            let holder = new_item(send0(class("NSMenuItem"), "alloc"), selector("initWithTitle:action:keyEquivalent:"), text(tr("手机", "Phone")), null_mut(), empty);
            send1(holder, "setSubmenu:", menu);
            send1(main, "addItem:", holder);
        }
        objc_autoreleasePoolPop(pool);
    }
}

/// The low-latency item says whether it is on; the MacApp's mockup has no check mark beside it.
pub fn set_low_latency(on: bool) {
    let item = LOW_LATENCY_ITEM.load(Ordering::Relaxed);
    if item.is_null() {
        return;
    }
    unsafe {
        let pool = objc_autoreleasePoolPush();
        send1(item, "setTitle:", text(low_latency(on)));
        send1(item, "setState:", null_mut());
        objc_autoreleasePoolPop(pool);
    }
}
