//! This app's version, and whether a newer one is out: on the same line as `xdesk update` (CLI.md §1), a beta
//! looks at the newest release of all, a stable version only at stable releases. It only says so and opens the release
//! page; nothing is downloaded or installed by itself.

use crate::lang::tr;
use std::cmp::Ordering;
use std::process::Command;
use std::sync::Mutex;

const REPO: &str = "n2ray/xdesk";

/// Written by the packaging script (CI stamps the release's version); "dev" for a build at home.
pub fn version() -> &'static str {
    option_env!("XDESK_VERSION").filter(|v| !v.is_empty()).unwrap_or("dev")
}

/// A beta, or a build at home, follows the betas.
fn beta() -> bool {
    let v = version();
    v == "dev" || v.contains('-')
}

static NEWER: Mutex<Option<String>> = Mutex::new(None);

/// The newer version found, without its "v".
pub fn newer() -> Option<String> {
    NEWER.lock().unwrap().clone()
}

/// The release page of a version (this one when none is given).
pub fn open_release(version: Option<&str>) {
    let url = match version {
        Some(v) => format!("https://github.com/{REPO}/releases/tag/v{v}"),
        None if self::version() != "dev" => format!("https://github.com/{REPO}/releases/tag/v{}", self::version()),
        None => format!("https://github.com/{REPO}/releases"),
    };
    let _ = Command::new("open").arg(url).spawn();
}

fn newest_tag() -> Option<String> {
    let url = if beta() {
        format!("https://api.github.com/repos/{REPO}/releases?per_page=1")
    } else {
        format!("https://api.github.com/repos/{REPO}/releases/latest")
    };
    let out = Command::new("curl").args(["-fsSL", "--max-time", "15", "-H", "Accept: application/vnd.github+json", &url]).output().ok()?;
    if !out.status.success() {
        return None;
    }
    let body = String::from_utf8_lossy(&out.stdout);
    let at = body.find("\"tag_name\"")?;
    let rest = body[at + 10..].trim_start().strip_prefix(':')?.trim_start().strip_prefix('"')?;
    let tag = &rest[..rest.find('"')?];
    let v = tag.strip_prefix('v')?;
    v.chars().next().is_some_and(|c| c.is_ascii_digit()).then(|| v.to_string())
}

/// 0.6.0-beta.5 before 0.6.0-beta.10 before 0.6.0; parts compared as numbers where both are numbers.
pub fn compare(a: &str, b: &str) -> Ordering {
    let split = |v: &str| -> (Vec<String>, Option<Vec<String>>) {
        let (core, pre) = match v.split_once('-') {
            Some((c, p)) => (c, Some(p)),
            None => (v, None),
        };
        (core.split('.').map(str::to_string).collect(), pre.map(|p| p.split('.').map(str::to_string).collect()))
    };
    let part = |x: &str, y: &str| match (x.parse::<u64>(), y.parse::<u64>()) {
        (Ok(m), Ok(n)) => m.cmp(&n),
        (Ok(_), Err(_)) => Ordering::Less,
        (Err(_), Ok(_)) => Ordering::Greater,
        _ => x.cmp(y),
    };
    let parts = |x: &[String], y: &[String]| {
        for (p, q) in x.iter().zip(y) {
            let o = part(p, q);
            if o != Ordering::Equal {
                return o;
            }
        }
        x.len().cmp(&y.len())
    };
    let ((core_a, pre_a), (core_b, pre_b)) = (split(a), split(b));
    match parts(&core_a, &core_b) {
        Ordering::Equal => match (pre_a, pre_b) {
            (None, None) => Ordering::Equal,
            (None, Some(_)) => Ordering::Greater,
            (Some(_), None) => Ordering::Less,
            (Some(x), Some(y)) => parts(&x, &y),
        },
        o => o,
    }
}

/// Looks once, in the background. With `tell`, the answer comes as a toast either way (the menu's 检查更新).
pub fn check(tell: bool) {
    std::thread::spawn(move || {
        let found = newest_tag();
        let mine = version();
        match found {
            Some(v) if mine == "dev" || compare(&v, mine) == Ordering::Greater => {
                *NEWER.lock().unwrap() = Some(v.clone());
                if tell {
                    crate::net::toast(&format!("{}{v}", tr("有新版本：", "A newer version is out: ")));
                    open_release(Some(&v));
                }
            }
            Some(_) if tell => crate::net::toast(&format!("{}{mine}", tr("已是最新版本：", "Up to date: "))),
            None if tell => crate::net::toast(tr("查不到新版本，请检查网络", "Could not look for updates: check the network")),
            _ => {}
        }
    });
}

#[cfg(test)]
mod tests {
    use super::compare;
    use std::cmp::Ordering::*;

    #[test]
    fn order() {
        assert_eq!(compare("0.6.0-beta.10", "0.6.0-beta.5"), Greater);
        assert_eq!(compare("0.6.0", "0.6.0-beta.9"), Greater);
        assert_eq!(compare("0.6.1-beta.1", "0.6.0"), Greater);
        assert_eq!(compare("0.5.1", "0.6.0-beta.1"), Less);
        assert_eq!(compare("0.6.0", "0.6.0"), Equal);
    }
}
