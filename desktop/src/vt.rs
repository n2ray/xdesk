//! H.264 decoding with VideoToolbox, the Mac's hardware decoder. The phone sends Annex B with SPS and PPS; VideoToolbox
//! takes a format made from those and each frame with length prefixes.

use crate::Frame;
use std::os::raw::c_void;
use std::ptr::{null, null_mut};
use std::sync::{Arc, Mutex};

type Ref = *mut c_void;

#[repr(C)]
#[derive(Clone, Copy)]
struct CMTime {
    value: i64,
    timescale: i32,
    flags: u32,
    epoch: i64,
}

type OutputCallback = extern "C" fn(Ref, Ref, i32, u32, Ref, CMTime, CMTime);

#[repr(C)]
struct OutputRecord {
    callback: OutputCallback,
    refcon: Ref,
}

#[link(name = "CoreFoundation", kind = "framework")]
extern "C" {
    static kCFAllocatorNull: Ref;
    static kCFBooleanTrue: Ref;
    static kCFTypeDictionaryKeyCallBacks: u8;
    static kCFTypeDictionaryValueCallBacks: u8;
    fn CFRelease(object: Ref);
    fn CFNumberCreate(allocator: Ref, kind: isize, value: *const c_void) -> Ref;
    fn CFDictionaryCreate(allocator: Ref, keys: *const Ref, values: *const Ref, count: isize, key_callbacks: *const u8, value_callbacks: *const u8) -> Ref;
}

#[link(name = "CoreMedia", kind = "framework")]
extern "C" {
    fn CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: Ref, count: usize, sets: *const *const u8, sizes: *const usize, length_size: i32, out: *mut Ref) -> i32;
    fn CMBlockBufferCreateWithMemoryBlock(allocator: Ref, memory: Ref, length: usize, block_allocator: Ref, custom: Ref, offset: usize, data_length: usize, flags: u32, out: *mut Ref) -> i32;
    fn CMSampleBufferCreateReady(allocator: Ref, data: Ref, format: Ref, samples: isize, timings: isize, timing: *const c_void, sizes_count: isize, sizes: *const usize, out: *mut Ref) -> i32;
}

#[link(name = "CoreVideo", kind = "framework")]
extern "C" {
    static kCVPixelBufferPixelFormatTypeKey: Ref;
    fn CVPixelBufferLockBaseAddress(buffer: Ref, flags: u64) -> i32;
    fn CVPixelBufferUnlockBaseAddress(buffer: Ref, flags: u64) -> i32;
    fn CVPixelBufferGetBaseAddressOfPlane(buffer: Ref, plane: usize) -> *const u8;
    fn CVPixelBufferGetBytesPerRowOfPlane(buffer: Ref, plane: usize) -> usize;
    fn CVPixelBufferGetHeightOfPlane(buffer: Ref, plane: usize) -> usize;
    fn CVPixelBufferGetWidth(buffer: Ref) -> usize;
    fn CVPixelBufferGetHeight(buffer: Ref) -> usize;
}

#[link(name = "VideoToolbox", kind = "framework")]
extern "C" {
    fn VTDecompressionSessionCreate(allocator: Ref, format: Ref, specification: Ref, attributes: Ref, callback: *const OutputRecord, out: *mut Ref) -> i32;
    fn VTDecompressionSessionDecodeFrame(session: Ref, sample: Ref, flags: u32, refcon: Ref, info: *mut u32) -> i32;
    fn VTDecompressionSessionInvalidate(session: Ref);
    fn VTIsHardwareDecodeSupported(codec: u32) -> bool;
    static kVTDecompressionPropertyKey_RealTime: Ref;
    fn VTSessionSetProperty(session: Ref, key: Ref, value: Ref) -> i32;
}

const CODEC_H264: u32 = 0x6176_6331; // 'avc1'
const PIXEL_NV12_VIDEO: i32 = 0x3432_3076; // '420v'
const CF_NUMBER_I32: isize = 3;
const READ_ONLY: u64 = 1;
const NAL_SPS: u8 = 7;
const NAL_PPS: u8 = 8;
const NAL_IDR: u8 = 5;
const NAL_SLICE: u8 = 1;

pub enum Failure {
    /// This Mac cannot decode the stream this way: use the software decoder.
    Unavailable(String),
    /// One frame went wrong: ask for a key frame.
    Frame(String),
}

type Latest = Mutex<Option<Frame>>;

extern "C" fn on_frame(refcon: Ref, _source: Ref, status: i32, _flags: u32, image: Ref, _pts: CMTime, _duration: CMTime) {
    if status != 0 || image.is_null() {
        return;
    }
    let latest = unsafe { &*(refcon as *const Latest) };
    unsafe {
        if CVPixelBufferLockBaseAddress(image, READ_ONLY) != 0 {
            return;
        }
        let plane = |i: usize| {
            let bytes = CVPixelBufferGetBytesPerRowOfPlane(image, i) * CVPixelBufferGetHeightOfPlane(image, i);
            std::slice::from_raw_parts(CVPixelBufferGetBaseAddressOfPlane(image, i), bytes).to_vec()
        };
        let frame = Frame {
            width: CVPixelBufferGetWidth(image) as u32,
            height: CVPixelBufferGetHeight(image) as u32,
            y: plane(0),
            u: plane(1),
            v: Vec::new(),
            strides: (CVPixelBufferGetBytesPerRowOfPlane(image, 0), CVPixelBufferGetBytesPerRowOfPlane(image, 1), 0),
            nv12: true,
        };
        CVPixelBufferUnlockBaseAddress(image, READ_ONLY);
        *latest.lock().unwrap() = Some(frame);
    }
}

/// The NAL units of an Annex B buffer, without their start codes.
fn nal_units(data: &[u8]) -> Vec<&[u8]> {
    let mut starts = Vec::new();
    let mut i = 0;
    while i + 3 <= data.len() {
        if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 {
            starts.push(i + 3);
            i += 3;
        } else {
            i += 1;
        }
    }
    let mut units = Vec::with_capacity(starts.len());
    for (n, &start) in starts.iter().enumerate() {
        let mut end = starts.get(n + 1).map_or(data.len(), |next| next - 3);
        while end > start && data[end - 1] == 0 {
            end -= 1;
        }
        if end > start {
            units.push(&data[start..end]);
        }
    }
    units
}

pub struct VtDecoder {
    latest: Arc<Latest>,
    session: Ref,
    format: Ref,
    sps: Vec<u8>,
    pps: Vec<u8>,
    frame: Vec<u8>,
}

unsafe impl Send for VtDecoder {}

impl VtDecoder {
    pub fn new(latest: Arc<Latest>) -> Self {
        println!("硬件 H.264 解码：{}", if unsafe { VTIsHardwareDecodeSupported(CODEC_H264) } { "支持" } else { "不支持" });
        VtDecoder { latest, session: null_mut(), format: null_mut(), sps: Vec::new(), pps: Vec::new(), frame: Vec::new() }
    }

    /// The parameter sets seen so far, as Annex B, for a software decoder taking over.
    pub fn config(&self) -> Vec<u8> {
        let mut out = vec![0, 0, 0, 1];
        out.extend_from_slice(&self.sps);
        out.extend_from_slice(&[0, 0, 0, 1]);
        out.extend_from_slice(&self.pps);
        out
    }

    pub fn reset(&mut self) {
        unsafe {
            if !self.session.is_null() {
                VTDecompressionSessionInvalidate(self.session);
                CFRelease(self.session);
                self.session = null_mut();
            }
            if !self.format.is_null() {
                CFRelease(self.format);
                self.format = null_mut();
            }
        }
    }

    fn open(&mut self) -> Result<(), Failure> {
        self.reset();
        let sets = [self.sps.as_ptr(), self.pps.as_ptr()];
        let sizes = [self.sps.len(), self.pps.len()];
        unsafe {
            let status = CMVideoFormatDescriptionCreateFromH264ParameterSets(null_mut(), 2, sets.as_ptr(), sizes.as_ptr(), 4, &mut self.format);
            if status != 0 {
                self.format = null_mut();
                return Err(Failure::Unavailable(format!("format {status}")));
            }
            let value = PIXEL_NV12_VIDEO;
            let number = CFNumberCreate(null_mut(), CF_NUMBER_I32, &value as *const i32 as *const c_void);
            let keys = [kCVPixelBufferPixelFormatTypeKey];
            let values = [number];
            let attributes = CFDictionaryCreate(null_mut(), keys.as_ptr(), values.as_ptr(), 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
            let record = OutputRecord { callback: on_frame, refcon: Arc::as_ptr(&self.latest) as Ref };
            let status = VTDecompressionSessionCreate(null_mut(), self.format, null_mut(), attributes, &record, &mut self.session);
            CFRelease(attributes);
            CFRelease(number);
            if status != 0 {
                self.session = null_mut();
                return Err(Failure::Unavailable(format!("session {status}")));
            }
            VTSessionSetProperty(self.session, kVTDecompressionPropertyKey_RealTime, kCFBooleanTrue);
        }
        println!("VideoToolbox 解码已启动");
        Ok(())
    }

    pub fn decode(&mut self, data: &[u8]) -> Result<(), Failure> {
        self.frame.clear();
        let mut changed = false;
        for unit in nal_units(data) {
            match unit[0] & 0x1f {
                NAL_SPS if self.sps != unit => {
                    self.sps = unit.to_vec();
                    changed = true;
                }
                NAL_PPS if self.pps != unit => {
                    self.pps = unit.to_vec();
                    changed = true;
                }
                NAL_SLICE | NAL_IDR => {
                    self.frame.extend_from_slice(&(unit.len() as u32).to_be_bytes());
                    self.frame.extend_from_slice(unit);
                }
                _ => {}
            }
        }
        if (changed || self.session.is_null()) && !self.sps.is_empty() && !self.pps.is_empty() {
            self.open()?;
        }
        if self.frame.is_empty() || self.session.is_null() {
            return Ok(());
        }
        unsafe {
            let (mut block, mut sample) = (null_mut(), null_mut());
            let length = self.frame.len();
            let status = CMBlockBufferCreateWithMemoryBlock(null_mut(), self.frame.as_mut_ptr() as Ref, length, kCFAllocatorNull, null_mut(), 0, length, 0, &mut block);
            if status != 0 {
                return Err(Failure::Frame(format!("block {status}")));
            }
            let status = CMSampleBufferCreateReady(null_mut(), block, self.format, 1, 0, null(), 1, &length, &mut sample);
            CFRelease(block);
            if status != 0 {
                return Err(Failure::Frame(format!("sample {status}")));
            }
            // Flags 0: decoded before this returns, while the frame's memory is still ours
            let status = VTDecompressionSessionDecodeFrame(self.session, sample, 0, null_mut(), null_mut());
            CFRelease(sample);
            if status != 0 {
                return Err(Failure::Frame(format!("decode {status}")));
            }
        }
        Ok(())
    }
}

impl Drop for VtDecoder {
    fn drop(&mut self) {
        self.reset();
    }
}
