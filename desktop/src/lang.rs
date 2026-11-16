//! The language of the window's words and the menu: Chinese on a Mac whose first language is Chinese, English on any
//! other, as the phone app has both.

use crate::clip::{class, send0, Id};
use std::ffi::{c_char, CStr};
use std::sync::OnceLock;

#[link(name = "objc")]
extern "C" {
    fn objc_autoreleasePoolPush() -> Id;
    fn objc_autoreleasePoolPop(pool: Id);
}

static ZH: OnceLock<bool> = OnceLock::new();

fn first_language() -> String {
    unsafe {
        let pool = objc_autoreleasePoolPush();
        let languages = send0(class("NSLocale"), "preferredLanguages");
        let first = if languages.is_null() { std::ptr::null_mut() } else { send0(languages, "firstObject") };
        let text = if first.is_null() {
            String::new()
        } else {
            let p = send0(first, "UTF8String") as *const c_char;
            if p.is_null() { String::new() } else { CStr::from_ptr(p).to_string_lossy().into_owned() }
        };
        objc_autoreleasePoolPop(pool);
        text
    }
}

pub fn zh() -> bool {
    *ZH.get_or_init(|| first_language().starts_with("zh"))
}

/// The words for this Mac's language.
pub fn tr(zh: &'static str, en: &'static str) -> &'static str {
    if self::zh() {
        zh
    } else {
        en
    }
}
