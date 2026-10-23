//! A Mac window's picture: the phone's H.264, handed to an
//! AVSampleBufferDisplayLayer as each frame arrives.
//!
//! The layer is macOS's own way to show a stream: it decodes on the media
//! engine (VideoToolbox) and the window server composites its decoded
//! surfaces as they are, so nothing here decodes, copies or draws. Each frame
//! is marked to be shown at once, the moment it is decoded, rather than at
//! its timestamp: the phone already sent it when it was composed.
//!
//! What the layer wants is not quite what the phone sends (h264.rs): the
//! parameter sets become a format description, and every frame's NAL units
//! go after their lengths instead of start codes.

use std::ffi::c_void;
use std::ptr::{self, NonNull};

use objc2::rc::Retained;
use objc2_av_foundation::{AVLayerVideoGravityResize, AVQueuedSampleBufferRenderingStatus, AVSampleBufferDisplayLayer};
use objc2_core_foundation::{kCFBooleanTrue, CFMutableDictionary, CFRetained};
use objc2_core_media::{
    kCMBlockBufferAssureMemoryNowFlag, kCMSampleAttachmentKey_DisplayImmediately,
    CMVideoFormatDescriptionCreateFromH264ParameterSets, CMBlockBuffer, CMFormatDescription, CMSampleBuffer,
};

use super::super::{h264, wire};

/// The layer, which the link's reader feeds from its own thread: enqueuing,
/// flushing and reading its status are what AVFoundation means to be done
/// from a media queue, never only the main one. Made, placed and released on
/// the main thread.
pub struct Layer(pub Retained<AVSampleBufferDisplayLayer>);
// SAFETY: see above — only the queue-side methods are used off the main thread.
unsafe impl Send for Layer {}

/// A format description is immutable once made.
struct Format(CFRetained<CMFormatDescription>);
// SAFETY: CoreMedia's format descriptions are immutable CF objects.
unsafe impl Send for Format {}

/// A new display layer for a window's picture, stretched to the frame it is
/// given (the window lays it out so the phone's padding falls outside).
pub fn new_layer() -> Retained<AVSampleBufferDisplayLayer> {
    unsafe {
        let layer = AVSampleBufferDisplayLayer::new();
        if let Some(gravity) = AVLayerVideoGravityResize {
            layer.setVideoGravity(gravity);
        }
        layer
    }
}

/// What came of one piece of the stream.
#[derive(Debug, PartialEq, Eq)]
pub enum Fed {
    /// A frame went to the layer to be shown.
    Shown,
    /// Nothing to show: parameter sets, or a frame before the key frame it needs.
    Nothing,
    /// The layer's decoder gave up; it has been reset, and the phone should send a key frame.
    WantsKeyFrame,
}

/// The stream as the layer has it.
pub struct Feed {
    layer: Option<Layer>,
    format: Option<Format>,
    /// The parameter sets the format was made from, to tell a new stream from the same.
    sets: Option<(Vec<u8>, Vec<u8>)>,
    /// Frames wait for a key frame: at the start, after a new format, after a failure.
    want_key: bool,
}

impl Feed {
    pub fn new() -> Self {
        Feed { layer: None, format: None, sets: None, want_key: true }
    }

    /// The window's layer arrives, or goes (the window closing): main thread.
    pub fn set_layer(&mut self, layer: Option<Layer>) -> Option<Layer> {
        std::mem::replace(&mut self.layer, layer)
    }

    /// One piece of the stream, as the wire brings it.
    pub fn push(&mut self, flags: u8, data: &[u8]) -> Result<Fed, String> {
        let units = h264::nal_units(data);
        // A new stream's parameter sets: in their own buffer, or ahead of a key frame.
        if let Some(sets) = h264::parameter_sets(&units) {
            if self.sets.as_ref() != Some(&sets) {
                self.format = Some(Format(format_for(&sets.0, &sets.1)?));
                self.sets = Some(sets);
                self.want_key = true;
            }
        }
        if flags & wire::VIDEO_CONFIG != 0 {
            return Ok(Fed::Nothing);
        }
        let (Some(layer), Some(format)) = (&self.layer, &self.format) else { return Ok(Fed::Nothing) };
        #[allow(deprecated)] // the layer's own queue, for macOS 11 to 13 as well as 14 on
        unsafe {
            if layer.0.status() == AVQueuedSampleBufferRenderingStatus::Failed {
                let why = layer.0.error().map(|e| e.localizedDescription().to_string()).unwrap_or_default();
                log::warn!("apps: macOS could not decode the phone's picture ({why}); starting again from a key frame");
                layer.0.flush();
                self.want_key = true;
                return Ok(Fed::WantsKeyFrame);
            }
        }
        if self.want_key {
            if !h264::has_idr(&units) {
                return Ok(Fed::Nothing);
            }
            self.want_key = false;
        }
        let frame = h264::length_prefixed(&units);
        if frame.is_empty() {
            return Ok(Fed::Nothing);
        }
        let sample = sample_for(&frame, &format.0)?;
        #[allow(deprecated)]
        unsafe {
            layer.0.enqueueSampleBuffer(&sample);
        }
        Ok(Fed::Shown)
    }
}

/// The format description of a stream with these parameter sets: what tells
/// the decoder the profile, the size and the crop.
fn format_for(sps: &[u8], pps: &[u8]) -> Result<CFRetained<CMFormatDescription>, String> {
    if sps.is_empty() || pps.is_empty() {
        return Err("empty parameter sets".into());
    }
    let pointers = [NonNull::from(&sps[0]), NonNull::from(&pps[0])];
    let sizes = [sps.len(), pps.len()];
    let mut out: *const CMFormatDescription = ptr::null();
    let status = unsafe {
        CMVideoFormatDescriptionCreateFromH264ParameterSets(
            None,
            2,
            NonNull::from(&pointers[0]),
            NonNull::from(&sizes[0]),
            4,
            NonNull::from(&mut out),
        )
    };
    match NonNull::new(out as *mut CMFormatDescription) {
        Some(p) if status == 0 => Ok(unsafe { CFRetained::from_raw(p) }),
        _ => Err(format!("macOS refused the phone's parameter sets (OSStatus {status})")),
    }
}

/// One frame as a sample buffer the layer shows the moment it is decoded.
fn sample_for(units: &[u8], format: &CMFormatDescription) -> Result<CFRetained<CMSampleBuffer>, String> {
    unsafe {
        // A block CoreMedia owns, the frame copied in: it outlives this call.
        let mut block: *mut CMBlockBuffer = ptr::null_mut();
        let status = CMBlockBuffer::create_with_memory_block(
            None,
            ptr::null_mut(),
            units.len(),
            None,
            ptr::null(),
            0,
            units.len(),
            kCMBlockBufferAssureMemoryNowFlag,
            NonNull::from(&mut block),
        );
        let block = match NonNull::new(block) {
            Some(b) if status == 0 => CFRetained::from_raw(b),
            _ => return Err(format!("no block for a frame (OSStatus {status})")),
        };
        let status = CMBlockBuffer::replace_data_bytes(
            NonNull::new_unchecked(units.as_ptr() as *mut c_void),
            &block,
            0,
            units.len(),
        );
        if status != 0 {
            return Err(format!("a frame did not fit its block (OSStatus {status})"));
        }
        let size = units.len();
        let mut sample: *mut CMSampleBuffer = ptr::null_mut();
        let status = CMSampleBuffer::create_ready(
            None,
            Some(&block),
            Some(format),
            1,
            0,
            ptr::null(),
            1,
            &size,
            NonNull::from(&mut sample),
        );
        let sample = match NonNull::new(sample) {
            Some(s) if status == 0 => CFRetained::from_raw(s),
            _ => return Err(format!("no sample for a frame (OSStatus {status})")),
        };
        if let Some(attachments) = sample.sample_attachments_array(true) {
            let first = attachments.value_at_index(0) as *const CFMutableDictionary;
            if let (Some(dict), Some(yes)) = (first.as_ref(), kCFBooleanTrue) {
                CFMutableDictionary::set_value(
                    Some(dict),
                    kCMSampleAttachmentKey_DisplayImmediately as *const _ as *const c_void,
                    yes as *const _ as *const c_void,
                );
            }
        }
        Ok(sample)
    }
}
