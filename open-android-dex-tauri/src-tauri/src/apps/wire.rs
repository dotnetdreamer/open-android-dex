//! The app windows' wire (doc/apps-on-pc.md, "The wire") — this computer's
//! half of `openandroiddex-wmd/.../Wire.java`.
//!
//! Every message is a type byte, a big-endian u32 length and that many bytes:
//!
//! ```text
//! both ways         0x01 JSON, UTF-8
//! phone → computer  0x02 flags u8, pts µs u64, H.264 Annex B   — 1 key frame, 2 codec config
//!                   0x03 package length u8, package, PNG       — an app's icon
//! computer → phone  0x10                                        — one video frame arrived (paced streams)
//!                   0x20 buttons u8, x u16, y u16, meta u32     — the mouse, display pixels
//!                   0x21 x u16, y u16, h f32, v f32, meta u32   — the wheel, in notches
//!                   0x22 down u8, repeat u8, code u16, meta u32 — a key: Android's code and meta
//!                   0x23 UTF-8                                  — characters typed
//! ```

use std::io::{self, Read};

pub const JSON: u8 = 0x01;
pub const VIDEO: u8 = 0x02;
pub const ICON: u8 = 0x03;
pub const ACK: u8 = 0x10;
pub const POINTER: u8 = 0x20;
pub const SCROLL: u8 = 0x21;
pub const KEY: u8 = 0x22;
pub const TEXT: u8 = 0x23;

/// A key frame at a high bit rate is the largest thing that comes; nothing
/// honest is near this.
const MAX_MESSAGE: usize = 16 * 1024 * 1024;

pub struct Msg {
    pub kind: u8,
    pub data: Vec<u8>,
}

/// The next message, or None at the end of the stream.
pub fn read(r: &mut impl Read) -> io::Result<Option<Msg>> {
    let mut head = [0u8; 5];
    match r.read_exact(&mut head[..1]) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    r.read_exact(&mut head[1..])?;
    let len = u32::from_be_bytes([head[1], head[2], head[3], head[4]]) as usize;
    if len > MAX_MESSAGE {
        return Err(io::Error::new(io::ErrorKind::InvalidData, format!("a message of {len} bytes")));
    }
    let mut data = vec![0u8; len];
    r.read_exact(&mut data)?;
    Ok(Some(Msg { kind: head[0], data }))
}

pub fn frame(kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(5 + payload.len());
    out.push(kind);
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(payload);
    out
}

pub fn json(value: &serde_json::Value) -> Vec<u8> {
    frame(JSON, value.to_string().as_bytes())
}

pub fn ack() -> Vec<u8> {
    frame(ACK, &[])
}

pub fn pointer(buttons: u8, x: u16, y: u16, meta: u32) -> Vec<u8> {
    let mut p = Vec::with_capacity(9);
    p.push(buttons);
    p.extend_from_slice(&x.to_be_bytes());
    p.extend_from_slice(&y.to_be_bytes());
    p.extend_from_slice(&meta.to_be_bytes());
    frame(POINTER, &p)
}

pub fn scroll(x: u16, y: u16, h: f32, v: f32, meta: u32) -> Vec<u8> {
    let mut p = Vec::with_capacity(16);
    p.extend_from_slice(&x.to_be_bytes());
    p.extend_from_slice(&y.to_be_bytes());
    p.extend_from_slice(&h.to_bits().to_be_bytes());
    p.extend_from_slice(&v.to_bits().to_be_bytes());
    p.extend_from_slice(&meta.to_be_bytes());
    frame(SCROLL, &p)
}

pub fn key(down: bool, repeat: u8, code: u16, meta: u32) -> Vec<u8> {
    let mut p = Vec::with_capacity(8);
    p.push(down as u8);
    p.push(repeat);
    p.extend_from_slice(&code.to_be_bytes());
    p.extend_from_slice(&meta.to_be_bytes());
    frame(KEY, &p)
}

pub fn text(s: &str) -> Vec<u8> {
    frame(TEXT, s.as_bytes())
}

/// A video message's parts: its flags, its timestamp (the phone's
/// CLOCK_MONOTONIC, in µs) and the H.264 it carries.
pub fn video(data: &[u8]) -> Option<(u8, u64, &[u8])> {
    if data.len() < 9 {
        return None;
    }
    let pts = u64::from_be_bytes(data[1..9].try_into().ok()?);
    Some((data[0], pts, &data[9..]))
}

#[allow(dead_code)] // the phone's mark on a key frame; the decoder needs no telling
pub const VIDEO_KEY: u8 = 1;
pub const VIDEO_CONFIG: u8 = 2;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_message_reads_back_as_it_was_written() {
        let mut bytes = json(&serde_json::json!({"t": "open", "pkg": "com.android.settings"}));
        bytes.extend(pointer(1, 300, 700, 0x1000));
        bytes.extend(key(true, 2, 66, 0));
        let mut r = &bytes[..];
        let a = read(&mut r).unwrap().unwrap();
        assert_eq!(a.kind, JSON);
        assert_eq!(String::from_utf8(a.data).unwrap(), r#"{"pkg":"com.android.settings","t":"open"}"#);
        let b = read(&mut r).unwrap().unwrap();
        assert_eq!((b.kind, b.data), (POINTER, vec![1, 1, 44, 2, 188, 0, 0, 16, 0]));
        let c = read(&mut r).unwrap().unwrap();
        assert_eq!((c.kind, c.data), (KEY, vec![1, 2, 0, 66, 0, 0, 0, 0]));
        assert!(read(&mut r).unwrap().is_none());
    }

    #[test]
    fn a_video_message_splits_into_flags_time_and_picture() {
        let mut m = vec![VIDEO_KEY];
        m.extend_from_slice(&123_456u64.to_be_bytes());
        m.extend_from_slice(&[0, 0, 0, 1, 0x65]);
        assert_eq!(video(&m), Some((VIDEO_KEY, 123_456, &[0, 0, 0, 1, 0x65][..])));
        assert_eq!(video(&[1, 2]), None);
    }
}
