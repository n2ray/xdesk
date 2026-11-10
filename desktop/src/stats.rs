//! The numbers of the latency panel (Control+Option+D). The phone's clock is brought to this one with TIME messages.

use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

const SAMPLES: usize = 8;
const MAX_BELIEVABLE_US: i64 = 5_000_000;

pub static PANEL: AtomicBool = AtomicBool::new(false);
static START: LazyLock<Instant> = LazyLock::new(Instant::now);
static OFFSET: AtomicI64 = AtomicI64::new(i64::MIN);
static CAPTURE: AtomicU64 = AtomicU64::new(0);
static DECODE: AtomicU64 = AtomicU64::new(0);
static SHOW: AtomicU64 = AtomicU64::new(0);
static ROUND_TRIP: AtomicU64 = AtomicU64::new(0);
static SHOWN: AtomicU64 = AtomicU64::new(0);
static ARRIVAL: Mutex<Option<Instant>> = Mutex::new(None);
static RECENT: Mutex<Vec<(u64, i64)>> = Mutex::new(Vec::new());
static COUNTED_FROM: Mutex<Option<Instant>> = Mutex::new(None);

pub fn now_us() -> u64 {
    START.elapsed().as_micros() as u64
}

fn average(into: &AtomicU64, value: u64) {
    let old = into.load(Ordering::Relaxed);
    into.store(if old == 0 { value } else { (old * 7 + value) / 8 }, Ordering::Relaxed);
}

pub fn reset() {
    OFFSET.store(i64::MIN, Ordering::Relaxed);
    RECENT.lock().unwrap().clear();
    for a in [&CAPTURE, &DECODE, &SHOW, &ROUND_TRIP] {
        a.store(0, Ordering::Relaxed);
    }
}

/// Of the last few answers, the one that took the shortest way says most about the clocks.
pub fn time_reply(p: &[u8]) {
    if p.len() != 16 {
        return;
    }
    let sent = u64::from_be_bytes(p[..8].try_into().unwrap());
    let phone = u64::from_be_bytes(p[8..16].try_into().unwrap()) as i64;
    let round_trip = now_us().saturating_sub(sent);
    let offset = phone - (sent + round_trip / 2) as i64;
    average(&ROUND_TRIP, round_trip);
    let mut recent = RECENT.lock().unwrap();
    if recent.len() == SAMPLES {
        recent.remove(0);
    }
    recent.push((round_trip, offset));
    if let Some(best) = recent.iter().min_by_key(|s| s.0) {
        OFFSET.store(best.1, Ordering::Relaxed);
    }
}

/// A frame came in, stamped with the phone's clock when it was drawn.
pub fn received(pts: u64) {
    let offset = OFFSET.load(Ordering::Relaxed);
    if offset == i64::MIN || !PANEL.load(Ordering::Relaxed) {
        return;
    }
    let took = now_us() as i64 + offset - pts as i64;
    if (0..MAX_BELIEVABLE_US).contains(&took) {
        average(&CAPTURE, took as u64);
    }
}

pub fn decoding(arrival: Instant) {
    *ARRIVAL.lock().unwrap() = Some(arrival);
}

pub fn decoded(took: Duration) {
    average(&DECODE, took.as_micros() as u64);
}

/// A new picture is on the screen.
pub fn presented() {
    SHOWN.fetch_add(1, Ordering::Relaxed);
    if let Some(a) = *ARRIVAL.lock().unwrap() {
        average(&SHOW, a.elapsed().as_micros() as u64);
    }
}

fn ms(a: &AtomicU64) -> String {
    match a.load(Ordering::Relaxed) {
        0 => "—".into(),
        v => format!("{:.1}", v as f64 / 1000.0),
    }
}

pub fn line(route: &str, picture: (u32, u32), low_latency: bool) -> String {
    let mut from = COUNTED_FROM.lock().unwrap();
    let seconds = from.map_or(1.0, |t| t.elapsed().as_secs_f64()).max(0.05);
    *from = Some(Instant::now());
    let fps = SHOWN.swap(0, Ordering::Relaxed) as f64 / seconds;
    format!(
        "{route} {}×{} {fps:.0} 帧/秒 · 编码+传输 {} · 解码 {} · 到屏幕 {} · 往返 {} ms · {}",
        picture.0,
        picture.1,
        ms(&CAPTURE),
        ms(&DECODE),
        ms(&SHOW),
        ms(&ROUND_TRIP),
        if low_latency { "低延迟" } else { "垂直同步" }
    )
}
