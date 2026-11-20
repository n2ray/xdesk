//! XDesk display for a computer. The phone connects here, shows its picture and sound, and takes this computer's
//! keyboard and mouse while the window is in front and the pointer is on it.
//!
//! Control+Option with: Esc gives the keyboard and mouse back until the next click, F full screen, B back, H home,
//! R the recent apps of an old phone, or the switchboard (启动台) with FEATURES bit 0, D the latency panel, L low
//! latency. The same things are in the bar on the right edge and in the menu.

mod clip;
mod lang;
mod menu;
mod net;
mod overlay;
mod stats;
mod tap;
mod update;
mod vt;

use lang::tr;
use net::{out, Out, Video};
use openh264::decoder::Decoder;
use openh264::formats::YUVSource;
use overlay::Image;
use sdl2::audio::{AudioQueue, AudioSpecDesired};
use sdl2::event::{Event, WindowEvent};
use sdl2::mouse::MouseButton;
use sdl2::pixels::{Color, PixelFormatEnum};
use sdl2::rect::Rect;
use sdl2::render::{BlendMode, Canvas, Texture, TextureCreator};
use sdl2::video::{FullscreenType, Window, WindowContext};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, sync_channel};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tap::{ACT_ABOUT, ACT_BACK, ACT_DISCONNECT, ACT_FULLSCREEN, ACT_HOME, ACT_LOW_LATENCY, ACT_PANEL, ACT_RECENTS, ACT_UPDATE};
use vt::{Failure, VtDecoder};

/// A window being dragged: the phone is asked for the new size once it stops.
const RESIZE_SETTLE: Duration = Duration::from_millis(120);
const SAVE_WINDOW_SETTLE: Duration = Duration::from_millis(500);
/// Bytes of sound allowed to wait, 150 ms of 48 kHz stereo: more is dropped so the sound stays with the picture.
const MAX_QUEUED: u32 = 28800;
/// Over a cable very little goes astray: 50 ms.
const MAX_QUEUED_USB: u32 = 9600;
const PERMISSION_POLL: Duration = Duration::from_secs(1);
const PANEL_EVERY: Duration = Duration::from_millis(500);
/// How often to look whether the window's screen changed its scale or refresh rate.
const SCREEN_LOOK: Duration = Duration::from_millis(500);
/// A hidden pointer is hidden again this often: macOS shows it again on its own now and then.
const CURSOR_AGAIN: Duration = Duration::from_secs(1);
fn needs_access() -> &'static str {
    tr("请在 系统设置 → 隐私与安全性 → 辅助功能 里打开 XDesk，键盘才能交给手机", "Allow XDesk in System Settings › Privacy & Security › Accessibility, so the keyboard can go to the phone")
}
/// The bar is XDesk Receiver's and the web display's: a blue handle on the right edge, the buttons in a column
/// beside it while open. In window points: the handle's line, the room around it a click finds it in, the buttons.
const HANDLE_WIDTH: u32 = 7;
const HANDLE_HEIGHT: u32 = 64;
const HANDLE_LEFT: u32 = 10;
const HANDLE_RIGHT: u32 = 8;
const HANDLE_ABOVE: u32 = 16;
const HANDLE_BLUE: Color = Color::RGB(0x3d, 0x7e, 0xf5);
const BAR_HEIGHT: u32 = 54;
const BAR_PADDING: u32 = 14;
const BAR_MIN_WIDTH: u32 = 72;
const BAR_INSET: u32 = 6;
/// Open, it closes this long after the last press, as on the receiver; the pointer resting on it keeps it open.
const BAR_OPEN_FOR: Duration = Duration::from_secs(6);
const BAR_TEXT: f32 = 14.0;
/// The bar on the picture. An old phone's 最近 asks for its list of apps (APPS), the panel this window draws; with
/// FEATURES bit 0 the same place is 启动台, which opens the switchboard.
fn bar(actions: bool) -> [(&'static str, u8); 5] {
    [
        (tr("返回", "Back"), ACT_BACK),
        (tr("主页", "Home"), ACT_HOME),
        (if actions { tr("启动台", "Launchpad") } else { tr("最近", "Recent") }, ACT_RECENTS),
        (tr("全屏", "Full screen"), ACT_FULLSCREEN),
        (tr("断开", "Disconnect"), ACT_DISCONNECT),
    ]
}
const LIST_WIDTH: u32 = 520;
const LIST_ROW: u32 = 44;
const LIST_MAX_ROWS: usize = 14;
const LIST_TEXT: f32 = 16.0;
const PANEL_TEXT: f32 = 13.0;
const TOAST_TOP: u32 = 24;
/// What is being typed into the Mac's input method: under the pointer, its candidates under that. In window points.
const COMPOSING_TEXT: f32 = 16.0;
const COMPOSING_BELOW: i32 = 24;
const COMPOSING_HEIGHT: u32 = 26;

pub struct Frame {
    width: u32,
    height: u32,
    y: Vec<u8>,
    /// With nv12, U and V together in this plane.
    u: Vec<u8>,
    v: Vec<u8>,
    strides: (usize, usize, usize),
    nv12: bool,
}

pub fn say_released() {
    net::toast(tr("键鼠已还给 Mac，点一下画面重新接管", "Keyboard and mouse are back with the Mac: click the picture to take them again"));
}

fn software_decode(decoder: &mut Decoder, data: &[u8], latest: &Mutex<Option<Frame>>, need_key: &AtomicBool) {
    match decoder.decode(data) {
        Ok(Some(yuv)) => {
            let (width, height) = yuv.dimensions();
            *latest.lock().unwrap() = Some(Frame {
                width: width as u32,
                height: height as u32,
                y: yuv.y().to_vec(),
                u: yuv.u().to_vec(),
                v: yuv.v().to_vec(),
                strides: yuv.strides(),
                nv12: false,
            });
        }
        Ok(None) => {}
        Err(e) => {
            eprintln!("decode: {e}");
            need_key.store(true, Ordering::Relaxed);
        }
    }
}

/// VideoToolbox while it works, the software decoder from the moment it does not.
fn decode_thread(videos: std::sync::mpsc::Receiver<Video>, latest: Arc<Mutex<Option<Frame>>>, need_key: Arc<AtomicBool>) {
    std::thread::spawn(move || {
        let mut hardware = VtDecoder::new(latest.clone());
        let mut software: Option<Decoder> = None;
        for v in videos {
            let (data, arrival) = match v {
                Video::Reset => {
                    hardware.reset();
                    if software.is_some() {
                        software = Some(Decoder::new().expect("H.264 decoder"));
                    }
                    continue;
                }
                Video::Data(d, arrival) => (d, arrival),
            };
            stats::decoding(arrival);
            let began = Instant::now();
            if let Some(decoder) = software.as_mut() {
                software_decode(decoder, &data, &latest, &need_key);
                stats::decoded(began.elapsed());
                continue;
            }
            match hardware.decode(&data) {
                Ok(()) => stats::decoded(began.elapsed()),
                Err(Failure::Frame(e)) => {
                    eprintln!("decode: {e}");
                    need_key.store(true, Ordering::Relaxed);
                }
                Err(Failure::Unavailable(e)) => {
                    eprintln!("VideoToolbox unavailable ({e}), decoding in software");
                    let mut decoder = Decoder::new().expect("H.264 decoder");
                    software_decode(&mut decoder, &hardware.config(), &latest, &need_key);
                    software_decode(&mut decoder, &data, &latest, &need_key);
                    software = Some(decoder);
                    need_key.store(true, Ordering::Relaxed);
                }
            }
        }
    });
}

/// Where a picture of this size goes in an area, keeping its shape.
fn fit(area: (u32, u32), picture: (u32, u32)) -> (i32, i32, u32, u32) {
    let scale = (area.0 as f64 / picture.0 as f64).min(area.1 as f64 / picture.1 as f64);
    let (w, h) = ((picture.0 as f64 * scale) as u32, (picture.1 as f64 * scale) as u32);
    (((area.0 - w) / 2) as i32, ((area.1 - h) / 2) as i32, w, h)
}

fn inside_changed() {
    if !tap::active() {
        tap::release_all();
    }
}

struct Words<'a> {
    texture: Texture<'a>,
    width: u32,
    height: u32,
}

fn words_texture<'a>(creator: &'a TextureCreator<WindowContext>, image: &Image) -> Result<Words<'a>, String> {
    let mut texture = creator.create_texture_streaming(PixelFormatEnum::ARGB8888, image.width, image.height).map_err(|e| e.to_string())?;
    texture.set_blend_mode(BlendMode::Blend);
    let bytes = unsafe { std::slice::from_raw_parts(image.pixels.as_ptr() as *const u8, image.pixels.len() * 4) };
    texture.update(None, bytes, image.width as usize * 4).map_err(|e| e.to_string())?;
    Ok(Words { texture, width: image.width, height: image.height })
}

/// Where the bar is, in pixels: the handle's line, the room a click finds the handle in, and the open bar's panel
/// with each of its buttons, in a column left of the handle, centred on it.
struct BarPlaces {
    line: Rect,
    handle: Rect,
    panel: Rect,
    buttons: Vec<Rect>,
}

fn bar_places(area: (u32, u32), scale: f32, widths: &[u32]) -> BarPlaces {
    let px = |v: u32| ((v as f32 * scale) as u32).max(1);
    let (hw, hh) = (px(HANDLE_LEFT + HANDLE_WIDTH + HANDLE_RIGHT), px(HANDLE_HEIGHT + HANDLE_ABOVE * 2));
    let handle = Rect::new(area.0 as i32 - hw as i32, (area.1 as i32 - hh as i32) / 2, hw, hh);
    let line = Rect::new(handle.x() + px(HANDLE_LEFT) as i32, handle.y() + px(HANDLE_ABOVE) as i32, px(HANDLE_WIDTH), px(HANDLE_HEIGHT));
    let inset = px(BAR_INSET);
    let row = px(BAR_HEIGHT);
    let width = (widths.iter().copied().max().unwrap_or(0) + px(BAR_PADDING) * 2).max(px(BAR_MIN_WIDTH));
    let (pw, ph) = (width + inset * 2, row * widths.len() as u32 + inset * 2);
    let panel = Rect::new(handle.x() - pw as i32, (area.1 as i32 - ph as i32) / 2, pw, ph);
    let buttons = (0..widths.len())
        .map(|i| Rect::new(panel.x() + inset as i32, panel.y() + inset as i32 + (row * i as u32) as i32, width, row))
        .collect();
    BarPlaces { line, handle, panel, buttons }
}

/// The pointer is on the handle, or on the open bar: it works the bar, not the phone.
fn over_bar(bar: &BarPlaces, open: bool, streaming: bool, point: (i32, i32)) -> bool {
    streaming && (bar.handle.contains_point(point) || (open && bar.panel.contains_point(point)))
}

/// The panel of the list of apps and its rows, in pixels.
fn list_places(area: (u32, u32), scale: f32, rows: usize) -> (Rect, Vec<Rect>) {
    let width = ((LIST_WIDTH as f32 * scale) as u32).min(area.0.saturating_sub(40));
    let row = (LIST_ROW as f32 * scale) as u32;
    let height = row * rows as u32;
    let (x, y) = (((area.0.saturating_sub(width)) / 2) as i32, ((area.1.saturating_sub(height)) / 2) as i32);
    let places = (0..rows).map(|i| Rect::new(x, y + (row as i32) * i as i32, width, row)).collect();
    (Rect::new(x, y, width, height.max(1)), places)
}

fn index_at(places: &[Rect], point: (i32, i32)) -> Option<usize> {
    places.iter().position(|r| r.contains_point(point))
}

fn draw_in<'a>(canvas: &mut Canvas<Window>, words: &Words<'a>, place: Rect) -> Result<(), String> {
    let x = place.x() + (place.width().saturating_sub(words.width) / 2) as i32;
    let y = place.y() + (place.height().saturating_sub(words.height) / 2) as i32;
    canvas.copy(&words.texture, None, Some(Rect::new(x, y, words.width, words.height)))
}

fn saved_window(video: &sdl2::VideoSubsystem) -> Option<(i32, i32, u32, u32)> {
    let numbers: Vec<i64> = net::load("window").split(' ').filter_map(|n| n.parse().ok()).collect();
    let [x, y, w, h] = numbers[..] else { return None };
    if w < 400 || h < 300 {
        return None;
    }
    let (x, y) = (x as i32, y as i32);
    let on_a_screen = (0..video.num_video_displays().ok()?).any(|i| video.display_bounds(i).is_ok_and(|b| b.contains_point((x + 40, y + 40))));
    on_a_screen.then_some((x, y, w as u32, h as u32))
}

fn remember_window(canvas: &Canvas<Window>) {
    let window = canvas.window();
    if window.fullscreen_state() == FullscreenType::Off {
        let (x, y) = window.position();
        let (w, h) = window.size();
        net::save("window", &format!("{x} {y} {w} {h}"));
    }
}

fn set_vsync(canvas: &Canvas<Window>, on: bool) {
    unsafe { sdl2::sys::SDL_RenderSetVSync(canvas.raw(), on as i32) };
}

/// The density the phone counts for this window (160 a pixel per point, a Retina window 320) and its screen's refresh
/// rate (MACAPP.md §3).
fn screen_of(canvas: &Canvas<Window>, video: &sdl2::VideoSubsystem) -> (u32, u32) {
    let window = canvas.window();
    let points = window.size().0.max(1) as f32;
    let pixels = canvas.output_size().map_or(points, |s| s.0 as f32);
    let density = (160.0 * pixels / points).round() as u32;
    let refresh = window.display_index().ok().and_then(|i| video.current_display_mode(i).ok()).map_or(60, |m| m.refresh_rate.max(0) as u32);
    (density, if refresh == 0 { 60 } else { refresh })
}

fn main() -> Result<(), String> {
    let mut tap_ready = tap::install();
    if !tap_ready {
        tap::ask_for_access();
    }
    let sdl = sdl2::init()?;
    let video = sdl.video()?;
    let audio = sdl.audio()?;
    sdl2::hint::set("SDL_RENDER_SCALE_QUALITY", "1");
    sdl2::hint::set("SDL_MAC_CTRL_CLICK_EMULATE_RIGHT_CLICK", "0");

    let place = saved_window(&video);
    let (width, height) = place.map_or((1280, 800), |p| (p.2, p.3));
    let mut builder = video.window("XDesk", width, height);
    match place {
        Some((x, y, ..)) => builder.position(x, y),
        None => builder.position_centered(),
    };
    let window = builder.resizable().allow_highdpi().build().map_err(|e| e.to_string())?;
    menu::install();
    let text_input = video.text_input();
    text_input.start();
    let mut canvas = window.into_canvas().build().map_err(|e| e.to_string())?;
    let mut low_latency = net::load("low_latency") != "0";
    set_vsync(&canvas, !low_latency);
    let creator = canvas.texture_creator();
    let mut texture = None;
    let mut picture = (0u32, 0u32);
    let mut texture_nv12 = false;

    let spec = AudioSpecDesired { freq: Some(48000), channels: Some(2), samples: Some(512) };
    let sound: AudioQueue<i16> = audio.open_queue(None, &spec)?;
    sound.resume();

    let (video_tx, video_rx) = sync_channel(30);
    let (audio_tx, audio_rx) = channel::<Vec<i16>>();
    let need_key = Arc::new(AtomicBool::new(false));
    let latest = Arc::new(Mutex::new(None::<Frame>));
    decode_thread(video_rx, latest.clone(), need_key.clone());
    let mut screen = screen_of(&canvas, &video);
    net::set_screen(screen.0, screen.1);
    net::start(canvas.output_size()?, video_tx, audio_tx, need_key);
    clip::start();
    update::check(false);
    let mut newer_told = false;
    let mut last_area = canvas.output_size()?;
    let mut last_screen_look = Instant::now();
    let mut cursor_said = Instant::now();
    let words = overlay::Overlay::new();
    let mut shown = (String::new(), String::new());
    let mut notice = net::Notice::default();
    let mut notice_image: Option<Words> = None;
    let mut notice_width = 0u32;
    let mut was_streaming = false;
    let mut resize_at: Option<Instant> = None;
    let mut save_window_at: Option<Instant> = None;
    let mut last_permission_look = Instant::now();
    let mut last_panel = Instant::now() - PANEL_EVERY;
    let mut panel_image: Option<Words> = None;
    let mut dirty = true;

    let mut mouse = (0i32, 0i32);
    let mut bar_open = false;
    let mut bar_since = Instant::now();
    let mut cursor_hidden = false;
    // macOS may show the cursor again on its own when the pointer enters the window: re-apply the wanted state
    let mut cursor_stale = true;
    let mut bar_hover: Option<usize> = None;
    let mut bar_labels: Vec<(u8, Words)> = Vec::new();
    let mut bar_scale = 0f32;
    let mut bar_actions = false;
    let mut apps: Option<Vec<(String, Words)>> = None;
    let mut apps_hover: Option<usize> = None;
    let mut composing_image: Option<Words> = None;

    let mut events = sdl.event_pump()?;
    let mut scroll = (0f32, 0f32);
    let mut pressed = [false; 4];
    'run: loop {
        let area = canvas.output_size()?;
        // Another screen with another scale changes the pixels without a resize: the phone hears of it the same way
        if area != last_area {
            last_area = area;
            resize_at = Some(Instant::now() + RESIZE_SETTLE);
            dirty = true;
        }
        if last_screen_look.elapsed() >= SCREEN_LOOK {
            last_screen_look = Instant::now();
            let now = screen_of(&canvas, &video);
            if now != screen {
                screen = now;
                net::set_screen(now.0, now.1);
                resize_at = Some(Instant::now() + RESIZE_SETTLE);
            }
        }
        let points = canvas.window().size();
        let scale = area.0 as f32 / points.0.max(1) as f32;
        let features_actions = net::features_actions();
        if (scale - bar_scale).abs() > 0.01 || features_actions != bar_actions {
            bar_scale = scale;
            bar_actions = features_actions;
            bar_labels.clear();
            for (name, action) in bar(features_actions) {
                if let Some(image) = words.label(name, BAR_TEXT * scale) {
                    bar_labels.push((action, words_texture(&creator, &image)?));
                }
            }
        }
        let widths: Vec<u32> = bar_labels.iter().map(|(_, w)| w.width).collect();
        let bar = bar_places(area, scale, &widths);
        let list = apps.as_ref().map(|a| list_places(area, scale, a.len()));
        let pixel = |m: (i32, i32)| ((m.0 as f32 * scale) as i32, (m.1 as f32 * scale) as i32);
        let mut actions = tap::take_actions();
        for event in events.poll_iter() {
            match event {
                Event::Quit { .. } => break 'run,
                Event::DropFile { filename, .. } => net::send_file(filename.into()),
                Event::TextEditing { text, .. } => {
                    tap::COMPOSING.store(!text.is_empty(), Ordering::Relaxed);
                    composing_image = match words.label(&text, COMPOSING_TEXT * scale) {
                        Some(image) => Some(words_texture(&creator, &image)?),
                        None => None,
                    };
                    dirty = true;
                }
                Event::TextInput { text, .. } => {
                    tap::COMPOSING.store(false, Ordering::Relaxed);
                    composing_image = None;
                    dirty = true;
                    // What the Mac's input method committed goes as TEXT, whatever the phone's INPUT_MODE says
                    if tap::active() {
                        out(Out::Text(text));
                    }
                }
                Event::Window { win_event, .. } => match win_event {
                    WindowEvent::FocusGained => {
                        tap::FOCUSED.store(true, Ordering::Relaxed);
                        // Coming to the front, macOS shows the pointer again whatever was asked before
                        cursor_stale = true;
                    }
                    WindowEvent::FocusLost => {
                        tap::FOCUSED.store(false, Ordering::Relaxed);
                        inside_changed();
                    }
                    WindowEvent::SizeChanged(..) => {
                        resize_at = Some(Instant::now() + RESIZE_SETTLE);
                        save_window_at = Some(Instant::now() + SAVE_WINDOW_SETTLE);
                        dirty = true;
                    }
                    WindowEvent::Moved(..) => save_window_at = Some(Instant::now() + SAVE_WINDOW_SETTLE),
                    WindowEvent::Exposed => dirty = true,
                    WindowEvent::Enter => {
                        tap::INSIDE.store(true, Ordering::Relaxed);
                        cursor_stale = true;
                    }
                    WindowEvent::Leave => {
                        tap::INSIDE.store(false, Ordering::Relaxed);
                        tap::RELEASED.store(false, Ordering::Relaxed);
                        inside_changed();
                    }
                    _ => {}
                },
                Event::MouseMotion { x, y, .. } => {
                    mouse = (x, y);
                    // Where the candidates of the Mac's input method go: the X cursor's place is not known here
                    text_input.set_rect(Rect::new(x, y + COMPOSING_BELOW, 1, COMPOSING_HEIGHT));
                    let over_something = apps.is_some() || over_bar(&bar, bar_open, net::STREAMING.load(Ordering::Relaxed), pixel(mouse));
                    if over_something || tap::RELEASED.load(Ordering::Relaxed) || picture.0 == 0 {
                        continue;
                    }
                    tap::INSIDE.store(true, Ordering::Relaxed);
                    let (px, py, w, h) = fit(points, picture);
                    // The last point of the window is the last point of the phone's display: its edges must be reachable
                    let fx = ((x - px) as f64 / (w.max(2) - 1) as f64).clamp(0.0, 1.0);
                    let fy = ((y - py) as f64 / (h.max(2) - 1) as f64).clamp(0.0, 1.0);
                    out(Out::Abs((fx * 65535.0) as u16, (fy * 65535.0) as u16));
                }
                Event::MouseButtonDown { mouse_btn, x, y, .. } => {
                    mouse = (x, y);
                    if let Some((_, rows)) = &list {
                        if let (Some(i), Some(open)) = (index_at(rows, pixel(mouse)), apps.as_ref()) {
                            out(Out::Choose(open[i].0.clone()));
                        }
                        apps = None;
                        apps_hover = None;
                        dirty = true;
                        continue;
                    }
                    if net::STREAMING.load(Ordering::Relaxed) && bar.handle.contains_point(pixel(mouse)) {
                        bar_open = !bar_open;
                        bar_since = Instant::now();
                        dirty = true;
                        continue;
                    }
                    if bar_open && bar.panel.contains_point(pixel(mouse)) {
                        if let Some(i) = index_at(&bar.buttons, pixel(mouse)) {
                            actions.push(bar_labels[i].0);
                        }
                        bar_since = Instant::now();
                        continue;
                    }
                    if tap::RELEASED.swap(false, Ordering::Relaxed) {
                        continue;
                    }
                    if mouse_btn == MouseButton::X1 {
                        out(Out::Android(1));
                    } else if let Some(b) = button(mouse_btn) {
                        pressed[b as usize] = true;
                        out(Out::Button(b, true));
                    }
                }
                Event::MouseButtonUp { mouse_btn, .. } => {
                    if let Some(b) = button(mouse_btn) {
                        if std::mem::take(&mut pressed[b as usize]) {
                            out(Out::Button(b, false));
                        }
                    }
                }
                Event::MouseWheel { precise_x, precise_y, .. } if !tap::RELEASED.load(Ordering::Relaxed) && apps.is_none() => {
                    // Only a wheel or a trackpad the event tap did not take: without FEATURES bit 4, or the window not in front
                    // As macOS made it, its speed and natural direction (INPUT.md §12): the phone's settings are for the
                    // phone's own touchpad and wheel
                    scroll.0 += precise_y;
                    scroll.1 += precise_x;
                    let (dy, dx) = (scroll.0.trunc(), scroll.1.trunc());
                    if dy != 0.0 || dx != 0.0 {
                        scroll.0 -= dy;
                        scroll.1 -= dx;
                        // macOS up is the protocol's down
                        out(Out::Scroll(-dy as i16, dx as i16));
                    }
                }
                _ => {}
            }
        }
        for action in actions {
            match action {
                ACT_BACK => out(Out::Android(1)),
                ACT_HOME => out(Out::Android(2)),
                // 3 asks for the app list (an old phone's panel); 4 opens the switchboard (FEATURES bit 0)
                ACT_RECENTS => out(Out::Android(if net::features_actions() { 4 } else { 3 })),
                ACT_FULLSCREEN => {
                    let next = if canvas.window().fullscreen_state() == FullscreenType::Off { FullscreenType::Desktop } else { FullscreenType::Off };
                    canvas.window_mut().set_fullscreen(next)?;
                }
                ACT_PANEL => {
                    let on = !stats::PANEL.load(Ordering::Relaxed);
                    stats::PANEL.store(on, Ordering::Relaxed);
                    if !on {
                        panel_image = None;
                    }
                    last_panel = Instant::now() - PANEL_EVERY;
                    dirty = true;
                }
                ACT_LOW_LATENCY => {
                    low_latency = !low_latency;
                    set_vsync(&canvas, !low_latency);
                    net::save("low_latency", if low_latency { "1" } else { "0" });
                    menu::set_low_latency(low_latency);
                    net::toast(if low_latency { tr("低延迟模式：开", "Low latency: on") } else { tr("低延迟模式：关（垂直同步）", "Low latency: off (vertical sync)") });
                }
                ACT_DISCONNECT => net::disconnect(),
                ACT_ABOUT => {
                    net::toast(&format!("XDesk {}", update::version()));
                    update::open_release(None);
                }
                ACT_UPDATE => update::check(true),
                _ => {}
            }
        }
        let streaming = net::STREAMING.load(Ordering::Relaxed);
        if !tap_ready && last_permission_look.elapsed() >= PERMISSION_POLL {
            last_permission_look = Instant::now();
            tap_ready = tap::install();
        }
        // The bar: the handle opens and closes it; it closes by itself a while after the last press
        let inside = tap::INSIDE.load(Ordering::Relaxed) && !tap::RELEASED.load(Ordering::Relaxed);
        if bar_open && inside && bar.panel.contains_point(pixel(mouse)) {
            bar_since = Instant::now();
        }
        if bar_open && (!streaming || bar_since.elapsed() >= BAR_OPEN_FOR) {
            bar_open = false;
            dirty = true;
        }
        let hover = if bar_open && inside { index_at(&bar.buttons, pixel(mouse)) } else { None };
        if hover != bar_hover {
            bar_hover = hover;
            dirty = true;
        }
        if let Some((_, rows)) = &list {
            let hover = index_at(rows, pixel(mouse));
            if hover != apps_hover {
                apps_hover = hover;
                dirty = true;
            }
        }
        if !streaming && apps.is_some() {
            apps = None;
            dirty = true;
        }
        tap::LIST_OPEN.store(apps.is_some(), Ordering::Relaxed);
        if !newer_told {
            if let Some(v) = update::newer() {
                newer_told = true;
                net::toast(&format!("{}{v}{}", tr("有新版本：", "A newer version is out: "), tr("（菜单 › 手机 › 检查更新）", " (menu › Phone › Check for updates)")));
                notice_width = 0;
            }
        }
        // The picture contains the desktop's cursor (INPUT_MODE 3, FEATURES bit 3): this computer's own pointer
        // must not double it over the picture. Over the receiver's own bar and lists it stays visible.
        if streaming {
            let over_picture = picture.0 > 0 && !over_bar(&bar, bar_open, streaming, pixel(mouse)) && apps.is_none() && {
                let (px, py, pw, ph) = fit(points, picture);
                mouse.0 >= px && mouse.0 < px + pw as i32 && mouse.1 >= py && mouse.1 < py + ph as i32
            };
            let hide = net::picture_cursor() && over_picture;
            if hide != cursor_hidden || cursor_stale || (hide && cursor_said.elapsed() >= CURSOR_AGAIN) {
                cursor_hidden = hide;
                cursor_stale = false;
                cursor_said = Instant::now();
                unsafe { sdl2::sys::SDL_ShowCursor(if hide { 0 } else { 1 }) };
            }
        } else if cursor_hidden || cursor_stale {
            cursor_hidden = false;
            cursor_stale = false;
            unsafe { sdl2::sys::SDL_ShowCursor(1) };
        }
        if let Some(found) = net::take_apps() {
            if found.is_empty() {
                net::toast(tr("还没有最近打开的应用", "No recent apps yet"));
            } else {
                let mut rows = Vec::new();
                for (key, name) in found.into_iter().take(LIST_MAX_ROWS) {
                    if let Some(image) = words.label(&name, LIST_TEXT * scale) {
                        rows.push((key, words_texture(&creator, &image)?));
                    }
                }
                apps = Some(rows);
                apps_hover = None;
                dirty = true;
            }
        }
        let panel_on = stats::PANEL.load(Ordering::Relaxed) && streaming;
        if panel_on && last_panel.elapsed() >= PANEL_EVERY {
            last_panel = Instant::now();
            let route = if net::VIA_USB.load(Ordering::Relaxed) { "USB" } else { "Wi-Fi" };
            let line = stats::line(route, picture, low_latency);
            panel_image = match words.label(&line, PANEL_TEXT * scale) {
                Some(image) => Some(words_texture(&creator, &image)?),
                None => None,
            };
            dirty = true;
        }
        let current = net::notice();
        let wanted = if !tap_ready {
            (needs_access().to_string(), String::new())
        } else if streaming {
            (net::current_toast(), String::new())
        } else {
            // A toast also shows without a picture (an update found, the version), in place of the line for a while
            let toast = net::current_toast();
            (if toast.is_empty() { current.line.clone() } else { toast }, current.big.clone())
        };
        if current != notice || area.0 != notice_width || streaming != was_streaming || wanted != shown {
            notice = current;
            shown = wanted;
            notice_width = area.0;
            was_streaming = streaming;
            dirty = true;
            let mut title = if streaming {
                match net::current_phone() {
                    Some(phone) if !phone.is_empty() => format!("XDesk — {phone}"),
                    _ => "XDesk".to_string(),
                }
            } else if net::waiting() || notice.line.is_empty() {
                "XDesk".to_string()
            } else {
                format!("XDesk: {}", notice.line)
            };
            if let Some(v) = update::newer() {
                title = format!("{title} — {}{v}", tr("有新版本 ", "update "));
            }
            canvas.window_mut().set_title(&title).map_err(|e| e.to_string())?;
            // MacWait.dc.html's title over how to connect, but not while a toast has the picture's place
            let waiting = !streaming && tap_ready && net::waiting() && net::current_toast().is_empty();
            let image = if waiting {
                words.waiting(&shown.0, area.0)
            } else {
                words.render(&shown.0, &shown.1, area.0)
            };
            notice_image = match image {
                Some(image) => Some(words_texture(&creator, &image)?),
                None => None,
            };
        }
        let most_queued = if net::VIA_USB.load(Ordering::Relaxed) { MAX_QUEUED_USB } else { MAX_QUEUED };
        while let Ok(pcm) = audio_rx.try_recv() {
            if sound.size() < most_queued {
                sound.queue_audio(&pcm)?;
            }
        }
        if texture.is_some() && !net::KEEP_PICTURE.load(Ordering::Relaxed) {
            texture = None;
            picture = (0, 0);
            latest.lock().unwrap().take();
            dirty = true;
        }
        if resize_at.is_some_and(|t| Instant::now() >= t) {
            resize_at = None;
            let (w, h) = canvas.output_size()?;
            net::set_window_size(w, h);
        }
        if save_window_at.is_some_and(|t| Instant::now() >= t) {
            save_window_at = None;
            remember_window(&canvas);
        }
        let frame = latest.lock().unwrap().take();
        let new_picture = frame.is_some();
        if let Some(f) = frame {
            dirty = true;
            if picture != (f.width, f.height) || texture.is_none() || texture_nv12 != f.nv12 {
                picture = (f.width, f.height);
                texture_nv12 = f.nv12;
                let format = if f.nv12 { PixelFormatEnum::NV12 } else { PixelFormatEnum::IYUV };
                texture = Some(creator.create_texture_streaming(format, f.width, f.height).map_err(|e| e.to_string())?);
            }
            if let Some(t) = texture.as_mut() {
                if f.nv12 {
                    let status = unsafe {
                        sdl2::sys::SDL_UpdateNVTexture(t.raw(), std::ptr::null(), f.y.as_ptr(), f.strides.0 as i32, f.u.as_ptr(), f.strides.1 as i32)
                    };
                    if status != 0 {
                        return Err(sdl2::get_error());
                    }
                } else {
                    t.update_yuv(None, &f.y, f.strides.0, &f.u, f.strides.1, &f.v, f.strides.2).map_err(|e| e.to_string())?;
                }
            }
        }
        // Only when there is something new: between frames this loop turns the keyboard's events at once
        if !dirty {
            std::thread::sleep(Duration::from_millis(1));
            continue;
        }
        dirty = false;
        canvas.set_draw_color(Color::BLACK);
        canvas.clear();
        canvas.set_blend_mode(BlendMode::Blend);
        if let Some(t) = texture.as_ref() {
            let (x, y, w, h) = fit(area, picture);
            canvas.copy(t, None, Some(Rect::new(x, y, w, h)))?;
        }
        if let Some(n) = notice_image.as_ref() {
            if !streaming {
                canvas.set_draw_color(Color::RGBA(0, 0, 0, 170));
                canvas.fill_rect(None)?;
            }
            let x = (area.0.saturating_sub(n.width) / 2) as i32;
            let y = if streaming { (TOAST_TOP as f32 * scale) as i32 } else { (area.1.saturating_sub(n.height) / 2) as i32 };
            if streaming {
                // White words straight on the picture were lost on a light one (the low-latency switch said nothing)
                canvas.set_draw_color(Color::RGBA(29, 29, 31, 220));
                canvas.fill_rect(Rect::new(x, y, n.width, n.height))?;
            }
            canvas.copy(&n.texture, None, Some(Rect::new(x, y, n.width, n.height)))?;
        }
        if let Some(p) = panel_image.as_ref().filter(|_| panel_on) {
            let margin = (10.0 * scale) as i32;
            let place = Rect::new(margin, margin, p.width + margin as u32, p.height + margin as u32);
            canvas.set_draw_color(Color::RGBA(0, 0, 0, 190));
            canvas.fill_rect(place)?;
            draw_in(&mut canvas, p, place)?;
        }
        if let Some(c) = composing_image.as_ref() {
            let (x, y) = pixel((mouse.0, mouse.1 + COMPOSING_BELOW));
            let place = Rect::new(x, y, c.width + (8.0 * scale) as u32, c.height + (4.0 * scale) as u32);
            canvas.set_draw_color(Color::RGBA(0, 0, 0, 210));
            canvas.fill_rect(place)?;
            draw_in(&mut canvas, c, place)?;
        }
        if streaming {
            canvas.set_draw_color(HANDLE_BLUE);
            canvas.fill_rect(bar.line)?;
        }
        if bar_open {
            canvas.set_draw_color(Color::RGBA(17, 20, 26, 224));
            canvas.fill_rect(bar.panel)?;
            for (i, r) in bar.buttons.iter().enumerate() {
                if bar_hover == Some(i) {
                    canvas.set_draw_color(Color::RGBA(255, 255, 255, 30));
                    canvas.fill_rect(*r)?;
                }
                draw_in(&mut canvas, &bar_labels[i].1, *r)?;
            }
        }
        if let (Some(open), Some((panel, rows))) = (apps.as_ref(), list.as_ref()) {
            canvas.set_draw_color(Color::RGBA(0, 0, 0, 120));
            canvas.fill_rect(None)?;
            canvas.set_draw_color(Color::RGBA(35, 35, 35, 240));
            canvas.fill_rect(*panel)?;
            for (i, r) in rows.iter().enumerate().take(open.len()) {
                if apps_hover == Some(i) {
                    canvas.set_draw_color(Color::RGBA(90, 90, 90, 240));
                    canvas.fill_rect(*r)?;
                }
                draw_in(&mut canvas, &open[i].1, *r)?;
            }
        }
        canvas.present();
        if new_picture {
            stats::presented();
        }
    }
    remember_window(&canvas);
    tap::release_all();
    net::goodbye();
    std::thread::sleep(Duration::from_millis(100));
    Ok(())
}

fn button(b: MouseButton) -> Option<u8> {
    match b {
        MouseButton::Left => Some(1),
        MouseButton::Middle => Some(2),
        MouseButton::Right => Some(3),
        _ => None,
    }
}
