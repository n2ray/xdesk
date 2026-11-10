//! Words on the window, for when there is no picture to look at: what to do next and the pairing code.

use ab_glyph::{point, Font, FontVec, PxScale, ScaleFont};

const FONTS: [&str; 3] = ["/System/Library/Fonts/Hiragino Sans GB.ttc", "/System/Library/Fonts/STHeiti Medium.ttc", "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"];
const LINE_PX: f32 = 46.0;
const BIG_PX: f32 = 220.0;
const GAP: u32 = 40;
const MARGIN: u32 = 8;

pub struct Overlay {
    font: Option<FontVec>,
}

/// White text with its coverage as alpha, one u32 ARGB per pixel.
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

    fn draw(&self, font: &FontVec, image: &mut Image, text: &str, px: f32, top: u32) {
        let scaled = font.as_scaled(PxScale::from(px));
        let mut x = (image.width as f32 - self.width_of(font, text, px)) / 2.0;
        let baseline = top as f32 + scaled.ascent();
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
                    let alpha = (coverage.clamp(0.0, 1.0) * 255.0) as u32;
                    pixels[(py * width + px) as usize] = (alpha << 24) | 0x00FF_FFFF;
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
        self.draw(font, &mut image, text, px, MARGIN / 2);
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
        self.draw(font, &mut image, line, line_px, MARGIN);
        if !big.is_empty() {
            self.draw(font, &mut image, big, big_px, MARGIN + line_height + GAP);
        }
        Some(image)
    }
}
