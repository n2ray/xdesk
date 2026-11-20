//! Words on the window, for when there is no picture to look at: what to do next and the pairing code.

use ab_glyph::{point, Font, FontVec, PxScale, ScaleFont};
use crate::lang::tr;

const FONTS: [&str; 3] = ["/System/Library/Fonts/Hiragino Sans GB.ttc", "/System/Library/Fonts/STHeiti Medium.ttc", "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"];
const LINE_PX: f32 = 46.0;
const BIG_PX: f32 = 220.0;
const GAP: u32 = 40;
const MARGIN: u32 = 8;
const WAIT_TITLE_PX: f32 = 22.0;
const WAIT_LINE_PX: f32 = 14.0;
const WAIT_GAP: u32 = 14;
const WAITING_TEXT: u32 = 0xE8ECF1;
const WAITING_SHADE: u32 = 0xC9D0D8;

pub struct Overlay {
    font: Option<FontVec>,
}

/// Text with its coverage as alpha, one u32 ARGB per pixel.
pub struct Image {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u32>,
}

impl Overlay {
    pub fn new() -> Self {
        let font = FONTS.iter().find_map(|p| FontVec::try_from_vec_and_index(std::fs::read(p).ok()?, 0).ok());
        Overlay { font }
    }

    fn width_of(&self, font: &FontVec, text: &str, px: f32) -> f32 {
        let scaled = font.as_scaled(PxScale::from(px));
        text.chars().map(|c| scaled.h_advance(font.glyph_id(c))).sum()
    }

    fn draw(&self, font: &FontVec, image: &mut Image, text: &str, px: f32, top: u32, color: u32) {
        let left = (image.width as f32 - self.width_of(font, text, px)) / 2.0;
        self.draw_at(font, image, text, px, left, top as f32, color);
    }

    /// Text from this left edge, blended over what is already there (a card's fill under it).
    fn draw_at(&self, font: &FontVec, image: &mut Image, text: &str, px: f32, left: f32, top: f32, color: u32) {
        let scaled = font.as_scaled(PxScale::from(px));
        let mut x = left;
        let baseline = top + scaled.ascent();
        for c in text.chars() {
            let id = font.glyph_id(c);
            let glyph = id.with_scale_and_position(PxScale::from(px), point(x, baseline));
            x += scaled.h_advance(id);
            let Some(outline) = font.outline_glyph(glyph) else { continue };
            let bounds = outline.px_bounds();
            let (width, height) = (image.width as i64, image.height as i64);
            let pixels = &mut image.pixels;
            outline.draw(|gx, gy, coverage| {
                let (px, py) = (bounds.min.x as i64 + gx as i64, bounds.min.y as i64 + gy as i64);
                if px >= 0 && py >= 0 && px < width && py < height {
                    blend(&mut pixels[(py * width + px) as usize], color, coverage.clamp(0.0, 1.0));
                }
            });
        }
    }

    /// One line at this size, for buttons and the like.
    pub fn label(&self, text: &str, px: f32) -> Option<Image> {
        let font = self.font.as_ref()?;
        if text.is_empty() {
            return None;
        }
        let width = self.width_of(font, text, px).ceil() as u32 + MARGIN * 2;
        let height = font.as_scaled(PxScale::from(px)).height().ceil() as u32 + MARGIN;
        let mut image = Image { width, height, pixels: vec![0x00FF_FFFF; (width * height) as usize] };
        self.draw(font, &mut image, text, px, MARGIN / 2, 0xFFFFFF);
        Some(image)
    }

    /// Scaled down to fit a window this wide. None when there is nothing to say or no font.
    pub fn render(&self, line: &str, big: &str, area_width: u32) -> Option<Image> {
        let font = self.font.as_ref()?;
        if line.is_empty() && big.is_empty() {
            return None;
        }
        let measure = self.width_of(font, line, LINE_PX).max(self.width_of(font, big, BIG_PX));
        let limit = area_width.saturating_sub(80) as f32;
        let scale = if measure > limit && measure > 0.0 { limit / measure } else { 1.0 };
        let (line_px, big_px) = (LINE_PX * scale, BIG_PX * scale);
        let line_height = font.as_scaled(PxScale::from(line_px)).height().ceil() as u32;
        let big_height = if big.is_empty() { 0 } else { font.as_scaled(PxScale::from(big_px)).height().ceil() as u32 + GAP };
        let width = measure as u32 + MARGIN * 2;
        let width = (width as f32 * scale).ceil() as u32 + MARGIN * 2;
        let height = line_height + big_height + MARGIN * 2;
        let mut image = Image { width, height, pixels: vec![0x00FF_FFFF; (width * height) as usize] };
        self.draw(font, &mut image, line, line_px, MARGIN, 0xFFFFFF);
        if !big.is_empty() {
            self.draw(font, &mut image, big, big_px, MARGIN + line_height + GAP, 0xFFFFFF);
        }
        Some(image)
    }

    /// MacWait.dc.html: the receiver logo, 等待手机连接 over how to connect, and under them the notes as cards
    /// (`true`: orange, something to do on the phone; `false`: grey). `ui` is the window's pixels per point.
    pub fn waiting(&self, line: &str, cards: &[(String, bool)], area_width: u32, ui: f32) -> Option<Image> {
        let font = self.font.as_ref()?;
        if line.is_empty() {
            return None;
        }
        let ui = ui.max(1.0);
        let title = tr("等待手机连接", "Waiting for the phone");
        let limit = area_width.saturating_sub((80.0 * ui) as u32) as f32;
        let measure = self.width_of(font, title, WAIT_TITLE_PX * ui).max(self.width_of(font, line, WAIT_LINE_PX * ui));
        let fit = if measure > limit && measure > 0.0 { limit / measure } else { 1.0 };
        let (title_px, line_px) = (WAIT_TITLE_PX * ui * fit, WAIT_LINE_PX * ui * fit);
        let title_height = font.as_scaled(PxScale::from(title_px)).height().ceil();
        let line_height = font.as_scaled(PxScale::from(line_px)).height().ceil();
        let gap = (WAIT_GAP as f32 + 4.0) * ui;
        let logo = (88.0 * ui * fit).round();

        // The cards: as wide as the mockup's column (420 points) or the window allows, their words wrapped
        let card_width = (420.0 * ui).min(limit.max(200.0 * ui));
        let card_px = 13.0 * ui;
        let card_line = (font.as_scaled(PxScale::from(card_px)).height() * 1.25).ceil();
        let (pad_x, pad_y, dot, dot_gap) = (14.0 * ui, 10.0 * ui, 8.0 * ui, 10.0 * ui);
        let text_room = card_width - pad_x * 2.0 - dot - dot_gap;
        let wrapped: Vec<(Vec<String>, bool)> =
            cards.iter().map(|(text, warn)| (self.wrap(font, text, card_px, text_room), *warn)).collect();
        let card_heights: Vec<f32> = wrapped.iter().map(|(lines, _)| lines.len() as f32 * card_line + pad_y * 2.0).collect();
        let cards_height = if cards.is_empty() {
            0.0
        } else {
            6.0 * ui + gap + card_heights.iter().sum::<f32>() + 8.0 * ui * (cards.len() - 1) as f32
        };

        let content = (measure * fit).max(logo).max(if cards.is_empty() { 0.0 } else { card_width });
        let width = content.ceil() as u32 + MARGIN * 2;
        let height = (logo + gap + title_height + gap + line_height + cards_height).ceil() as u32 + MARGIN * 2;
        let mut image = Image { width, height, pixels: vec![0x00FF_FFFF; (width * height) as usize] };

        let mut top = MARGIN as f32;
        draw_logo(&mut image, (width as f32 - logo) / 2.0, top, logo);
        top += logo + gap;
        self.draw(font, &mut image, title, title_px, top as u32, WAITING_TEXT);
        top += title_height + gap;
        self.draw(font, &mut image, line, line_px, top as u32, WAITING_SHADE);
        top += line_height + gap + 6.0 * ui;
        let left = (width as f32 - card_width) / 2.0;
        for ((lines, warn), h) in wrapped.iter().zip(card_heights.iter()) {
            let border = ui.max(1.0);
            let radius = 12.0 * ui;
            fill_card(&mut image, left, top, card_width, *h, radius, border, *warn);
            let dot_color = if *warn { WARN } else { 0x5C6470 };
            fill_circle(&mut image, left + pad_x + dot / 2.0, top + h / 2.0, dot / 2.0, dot_color);
            let text_color = if *warn { WAITING_TEXT } else { WAITING_SHADE };
            let mut y = top + pad_y + (card_line - font.as_scaled(PxScale::from(card_px)).height()) / 2.0;
            for l in lines {
                self.draw_at(font, &mut image, l, card_px, left + pad_x + dot + dot_gap, y, text_color);
                y += card_line;
            }
            top += h + 8.0 * ui;
        }
        Some(image)
    }

    /// Words broken into lines no wider than `room`: at a space where there is one (English), else between two
    /// characters (Chinese has no spaces).
    fn wrap(&self, font: &FontVec, text: &str, px: f32, room: f32) -> Vec<String> {
        let mut lines = Vec::new();
        let mut current = String::new();
        for c in text.chars() {
            let mut next = current.clone();
            next.push(c);
            if !current.is_empty() && self.width_of(font, &next, px) > room {
                match current.rfind(' ').filter(|&at| at > 0) {
                    Some(at) => {
                        let rest = current[at + 1..].to_string();
                        lines.push(current[..at].to_string());
                        current = rest;
                    }
                    None => lines.push(std::mem::take(&mut current)),
                }
                if !(current.is_empty() && c == ' ') {
                    current.push(c);
                }
            } else {
                current = next;
            }
        }
        if !current.is_empty() {
            lines.push(current);
        }
        lines
    }
}

const WARN: u32 = 0xF0883E;

/// This colour at this much coverage over the pixel (straight alpha, as the texture is blended).
fn blend(pixel: &mut u32, color: u32, alpha: f32) {
    if alpha <= 0.0 {
        return;
    }
    let old = *pixel;
    let below = (old >> 24) as f32 / 255.0;
    let out = alpha + below * (1.0 - alpha);
    let mix = |shift: u32| {
        let (c, d) = (((color >> shift) & 0xFF) as f32, ((old >> shift) & 0xFF) as f32);
        (((c * alpha + d * below * (1.0 - alpha)) / out).round() as u32).min(255) << shift
    };
    *pixel = (((out * 255.0).round() as u32).min(255) << 24) | mix(16) | mix(8) | mix(0);
}

/// Signed distance from a point to a rounded rectangle (negative inside).
fn rounded_distance(x: f32, y: f32, left: f32, top: f32, w: f32, h: f32, r: f32) -> f32 {
    let (hw, hh) = (w / 2.0, h / 2.0);
    let qx = (x - left - hw).abs() - (hw - r);
    let qy = (y - top - hh).abs() - (hh - r);
    (qx.max(0.0).powi(2) + qy.max(0.0).powi(2)).sqrt() + qx.max(qy).min(0.0) - r
}

fn each_pixel(image: &mut Image, left: f32, top: f32, w: f32, h: f32, mut f: impl FnMut(f32, f32, &mut u32)) {
    let (x0, y0) = (left.floor().max(0.0) as u32, top.floor().max(0.0) as u32);
    let (x1, y1) = (((left + w).ceil() as u32).min(image.width), ((top + h).ceil() as u32).min(image.height));
    for y in y0..y1 {
        for x in x0..x1 {
            let i = (y * image.width + x) as usize;
            f(x as f32 + 0.5, y as f32 + 0.5, &mut image.pixels[i]);
        }
    }
}

/// MacWait.dc.html's note card: orange tint with a thin orange line round it, or a faint grey fill.
fn fill_card(image: &mut Image, left: f32, top: f32, w: f32, h: f32, r: f32, border: f32, warn: bool) {
    each_pixel(image, left, top, w, h, |x, y, p| {
        let outer = (0.5 - rounded_distance(x, y, left, top, w, h, r)).clamp(0.0, 1.0);
        if warn {
            let inner = (0.5 - rounded_distance(x, y, left + border, top + border, w - border * 2.0, h - border * 2.0, (r - border).max(0.0))).clamp(0.0, 1.0);
            blend(p, WARN, 0.30 * (outer - inner).max(0.0));
            blend(p, WARN, 0.10 * inner);
        } else {
            blend(p, 0xFFFFFF, 0.05 * outer);
        }
    });
}

fn fill_circle(image: &mut Image, cx: f32, cy: f32, r: f32, color: u32) {
    each_pixel(image, cx - r - 1.0, cy - r - 1.0, r * 2.0 + 2.0, r * 2.0 + 2.0, |x, y, p| {
        let d = ((x - cx).powi(2) + (y - cy).powi(2)).sqrt() - r;
        blend(p, color, (0.5 - d).clamp(0.0, 1.0));
    });
}

/// The receivers' mirrored logo (the Mac app's icon): a dark rounded tile, the white stroke broken where the blue
/// one crosses it. In the icon's own 108-unit square.
fn draw_logo(image: &mut Image, left: f32, top: f32, size: f32) {
    let unit = size / 108.0;
    let segment = |x: f32, y: f32, a: (f32, f32), b: (f32, f32)| {
        let (dx, dy) = (b.0 - a.0, b.1 - a.1);
        let t = (((x - a.0) * dx + (y - a.1) * dy) / (dx * dx + dy * dy)).clamp(0.0, 1.0);
        ((x - a.0 - t * dx).powi(2) + (y - a.1 - t * dy).powi(2)).sqrt()
    };
    each_pixel(image, left, top, size, size, |x, y, p| {
        let tile = (0.5 - rounded_distance(x, y, left, top, size, size, 24.0 * unit)).clamp(0.0, 1.0);
        blend(p, 0x11151C, tile);
        let (u, v) = ((x - left) / unit, (y - top) / unit);
        let white = segment(u, v, (36.0, 36.0), (44.6, 44.6)).min(segment(u, v, (63.4, 63.4), (72.0, 72.0)));
        blend(p, 0xFFFFFF, (0.5 - (white - 5.5) * unit).clamp(0.0, 1.0) * tile);
        let blue = segment(u, v, (72.0, 36.0), (36.0, 72.0));
        blend(p, 0x3D7EF5, (0.5 - (blue - 5.5) * unit).clamp(0.0, 1.0) * tile);
    });
}
