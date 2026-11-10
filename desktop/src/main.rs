//! XDesk display for a computer. The phone connects here, shows its picture and sound, and takes this computer's
//! keyboard and mouse while the window is in front and the pointer is on it.
//!
//! Control+Option with: Esc gives the keyboard and mouse back until the next click, F full screen, B back, H home,
//! R recent apps, D the latency panel, L low latency. The same things are in the bar at the top edge and in the menu.

mod clip;
mod menu;
mod net;
mod overlay;
mod stats;
mod tap;
mod vt;

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
use tap::{ACT_BACK, ACT_DISCONNECT, ACT_FULLSCREEN, ACT_HOME, ACT_LOW_LATENCY, ACT_PANEL, ACT_RECENTS};
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
const NEEDS_ACCESS: &str = "请在 系统设置 → 隐私与安全性 → 辅助功能 里打开 XDesk，键盘才能交给手机";
/// How close to the top edge, and for how long, before the bar shows. In window points.
const BAR_EDGE: i32 = 6;
const BAR_DWELL: Duration = Duration::from_millis(200);
const BAR_KEEP_BELOW: i32 = 24;
const BAR_TOP: u32 = 8;
const BAR_TOP_FULLSCREEN: u32 = 34;
const BAR_HEIGHT: u32 = 36;
const BAR_PADDING: u32 = 16;
const BAR_TEXT: f32 = 14.0;
const BAR: [(&str, u8); 5] = [("返回", ACT_BACK), ("主页", ACT_HOME), ("最近", ACT_RECENTS), ("全屏", ACT_FULLSCREEN), ("断开", ACT_DISCONNECT)];
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
    net::toast("键鼠已还给 Mac，点一下画面重新接管");
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
                    eprintln!("VideoToolbox 不可用（{e}），改用软解");
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

/// Where each of the bar's buttons is, in pixels.
fn bar_places(area: (u32, u32), scale: f32, fullscreen: bool, widths: &[u32]) -> Vec<Rect> {
    let padding = (BAR_PADDING as f32 * scale) as u32;
    let height = (BAR_HEIGHT as f32 * scale) as u32;
    let top = ((if fullscreen { BAR_TOP_FULLSCREEN } else { BAR_TOP }) as f32 * scale) as i32;
    let total: u32 = widths.iter().map(|w| w + padding * 2).sum();
    let mut x = (area.0.saturating_sub(total) / 2) as i32;
    widths
        .iter()
        .map(|w| {
            let r = Rect::new(x, top, w + padding * 2, height);
            x += r.width() as i32;
            r
        })
        .collect()
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
    net::start(canvas.output_size()?, video_tx, audio_tx, need_key);
    clip::start();
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
    let mut at_top_since: Option<Instant> = None;
    let mut bar_open = false;
    let mut bar_hover: Option<usize> = None;
    let mut bar_labels: Vec<(u8, Words)> = Vec::new();
    let mut bar_scale = 0f32;
    let mut apps: Option<Vec<(String, Words)>> = None;
    let mut apps_hover: Option<usize> = None;
    let mut composing_image: Option<Words> = None;

    let mut events = sdl.event_pump()?;
    let mut scroll = (0f32, 0f32);
    let mut pressed = [false; 4];
    'run: loop {
        let area = canvas.output_size()?;
        let points = canvas.window().size();
        let scale = area.0 as f32 / points.0.max(1) as f32;
        let fullscreen = canvas.window().fullscreen_state() != FullscreenType::Off;
        if (scale - bar_scale).abs() > 0.01 {
            bar_scale = scale;
            bar_labels.clear();
            for (name, action) in BAR {
                if let Some(image) = words.label(name, BAR_TEXT * scale) {
                    bar_labels.push((action, words_texture(&creator, &image)?));
                }
            }
        }
        let widths: Vec<u32> = bar_labels.iter().map(|(_, w)| w.width).collect();
        let bar = bar_places(area, scale, fullscreen, &widths);
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
                    if tap::active() && tap::DESKTOP.load(Ordering::Relaxed) {
                        out(Out::Text(text));
                    }
                }
                Event::Window { win_event, .. } => match win_event {
                    WindowEvent::FocusGained => tap::FOCUSED.store(true, Ordering::Relaxed),
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
                    WindowEvent::Enter => tap::INSIDE.store(true, Ordering::Relaxed),
                    WindowEvent::Leave => {
                        tap::INSIDE.store(false, Ordering::Relaxed);
                        tap::RELEASED.store(false, Ordering::Relaxed);
                        inside_changed();
                    }
                    _ => {}
                },
                Event::MouseMotion { x, y, .. } => {
                    mouse = (x, y);
                    if tap::DESKTOP.load(Ordering::Relaxed) {
                        // Where the candidates of the Mac's input method go: the X cursor's place is not known here
                        text_input.set_rect(Rect::new(x, y + COMPOSING_BELOW, 1, COMPOSING_HEIGHT));
                    }
                    let over_something = apps.is_some() || (bar_open && index_at(&bar, pixel(mouse)).is_some());
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
                    if bar_open {
                        if let Some(i) = index_at(&bar, pixel(mouse)) {
                            actions.push(bar_labels[i].0);
                            continue;
                        }
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
                    scroll.0 += precise_y;
                    scroll.1 += precise_x;
                    let (dy, dx) = (scroll.0.trunc(), scroll.1.trunc());
                    if dy != 0.0 || dx != 0.0 {
                        scroll.0 -= dy;
                        scroll.1 -= dx;
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
                ACT_RECENTS => out(Out::Android(3)),
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
                    net::toast(if low_latency { "低延迟模式：开" } else { "低延迟模式：关（垂直同步）" });
                }
                ACT_DISCONNECT => net::disconnect(),
                _ => {}
            }
        }
        let streaming = net::STREAMING.load(Ordering::Relaxed);
        if !tap_ready && last_permission_look.elapsed() >= PERMISSION_POLL {
            last_permission_look = Instant::now();
            tap_ready = tap::install();
        }
        // The bar: comes when the pointer rests on the top edge, goes when it moves away
        let inside = tap::INSIDE.load(Ordering::Relaxed) && !tap::RELEASED.load(Ordering::Relaxed);
        if streaming && inside && mouse.1 <= BAR_EDGE {
            let since = *at_top_since.get_or_insert_with(Instant::now);
            if !bar_open && since.elapsed() >= BAR_DWELL {
                bar_open = true;
                dirty = true;
            }
        } else {
            at_top_since = None;
            let bottom = bar.first().map_or(0, |r| r.bottom());
            if bar_open && (!streaming || !inside || (mouse.1 as f32 * scale) as i32 > bottom + (BAR_KEEP_BELOW as f32 * scale) as i32) {
                bar_open = false;
                dirty = true;
            }
        }
        let hover = if bar_open { index_at(&bar, pixel(mouse)) } else { None };
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
        if let Some(found) = net::take_apps() {
            if found.is_empty() {
                net::toast("还没有最近打开的应用");
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
            (NEEDS_ACCESS.to_string(), String::new())
        } else if streaming {
            (net::current_toast(), String::new())
        } else {
            (current.line.clone(), current.big.clone())
        };
        if current != notice || area.0 != notice_width || streaming != was_streaming || wanted != shown {
            notice = current;
            shown = wanted;
            notice_width = area.0;
            was_streaming = streaming;
            dirty = true;
            let title = if notice.line.is_empty() { "XDesk".to_string() } else { format!("XDesk: {}", notice.line) };
            canvas.window_mut().set_title(&title).map_err(|e| e.to_string())?;
            notice_image = match words.render(&shown.0, &shown.1, area.0) {
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
        if bar_open {
            for (i, r) in bar.iter().enumerate() {
                let level = if bar_hover == Some(i) { 90 } else { 40 };
                canvas.set_draw_color(Color::RGBA(level, level, level, 225));
                canvas.fill_rect(*r)?;
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
