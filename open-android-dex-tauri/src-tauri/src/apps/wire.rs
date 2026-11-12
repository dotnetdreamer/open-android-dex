//! The app windows' wire (doc/apps-on-pc.md, "The wire") — this computer's
//! half of `openandroiddex-wmd/.../Wire.java`.
//!
//! Every message is a type byte, a big-endian u32 length and that many bytes:
//!
//! ```text
//! both ways         0x01 JSON, UTF-8
//! phone → computer  0x02 flags u8, pts µs u64, H.264 Annex B   — 1 key frame, 2 codec config
//!                   0x03 package length u8, package, PNG       — an app's icon
//!                   0x04 bytes                                  — a piece of a file being got; an empty one ends it ([`Pieces`])
//! computer → phone  0x10                                       — one video frame arrived (paced streams)
//!                   0x20 buttons u8, x u16, y u16, meta u32     — the mouse, display pixels
//!                   0x21 x u16, y u16, h f32, v f32, meta u32   — the wheel, in notches
//!                   0x22 down u8, repeat u8, code u16, meta u32 — a key: Android's code and meta
//!                   0x23 UTF-8                                  — characters typed
//!                   0x24 count u8, count × (id u8, x u16, y u16) — exactly these fingers are down (doc/game-mode.md)
//!                   0x25 slot u8, 13-byte report                — a game controller's state; the window that says so owns it
//! ```

use std::io::{self, Read};

pub const JSON: u8 = 0x01;
pub const VIDEO: u8 = 0x02;
pub const ICON: u8 = 0x03;
pub const PIECE: u8 = 0x04;
pub const ACK: u8 = 0x10;
pub const POINTER: u8 = 0x20;
pub const SCROLL: u8 = 0x21;
pub const KEY: u8 = 0x22;
pub const TEXT: u8 = 0x23;
pub const TOUCH: u8 = 0x24;
pub const GAMEPAD: u8 = 0x25;

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

/// A file's bytes as `get` gives them (AppOffers.java): pieces, then an empty
/// piece — the end mark. Read as the file itself: its end only after the mark;
/// a stream that stops before it is an error, a file cut short, never one taken
/// for whole, whatever size the phone said.
pub struct Pieces<R> {
    inner: R,
    /// What is left of the piece being read.
    left: usize,
    ended: bool,
}

impl<R: Read> Pieces<R> {
    pub fn new(inner: R) -> Pieces<R> {
        Pieces { inner, left: 0, ended: false }
    }
}

impl<R: Read> Read for Pieces<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let cut = || io::Error::new(io::ErrorKind::UnexpectedEof, "the file was cut short");
        loop {
            if self.ended || buf.is_empty() {
                return Ok(0);
            }
            if self.left > 0 {
                let most = buf.len().min(self.left);
                let n = self.inner.read(&mut buf[..most])?;
                if n == 0 {
                    return Err(cut());
                }
                self.left -= n;
                return Ok(n);
            }
            let mut head = [0u8; 5];
            self.inner.read_exact(&mut head).map_err(|e| if e.kind() == io::ErrorKind::UnexpectedEof { cut() } else { e })?;
            if head[0] != PIECE {
                return Err(io::Error::new(io::ErrorKind::InvalidData, format!("a message of type {:#04x} in a file's bytes", head[0])));
            }
            self.left = u32::from_be_bytes([head[1], head[2], head[3], head[4]]) as usize;
            self.ended = self.left == 0;
        }
    }
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

/// Game controls' fingers: exactly these are down now (none lifts them all).
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
pub fn touch(fingers: &[super::game::engine::Finger]) -> Vec<u8> {
    let n = fingers.len().min(255);
    let mut p = Vec::with_capacity(1 + 5 * n);
    p.push(n as u8);
    for f in &fingers[..n] {
        p.push(f.id);
        p.extend_from_slice(&f.x.to_be_bytes());
        p.extend_from_slice(&f.y.to_be_bytes());
    }
    frame(TOUCH, &p)
}

/// A game controller's state, in its slot.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
pub fn gamepad(slot: u8, report: &[u8; 13]) -> Vec<u8> {
    let mut p = Vec::with_capacity(14);
    p.push(slot);
    p.extend_from_slice(report);
    frame(GAMEPAD, &p)
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

/// The phone's mark on a key frame (the decoder needs no telling; a still does, link.rs).
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
    fn fingers_and_a_controller_byte_for_byte() {
        use super::super::game::engine::Finger;
        let f = [Finger { id: 0, x: 300, y: 700 }, Finger { id: 3, x: 1, y: 65535 }];
        assert_eq!(touch(&f), vec![TOUCH, 0, 0, 0, 11, 2, 0, 1, 44, 2, 188, 3, 0, 1, 255, 255]);
        assert_eq!(touch(&[]), vec![TOUCH, 0, 0, 0, 1, 0]);
        let r = [0xdb, 0x7c, 0x03, 0xe8, 0x03, 0x30, 0xf8, 0xb8, 0x0b, 0x60, 0xf0, 0x40, 0xc8];
        let mut want = vec![GAMEPAD, 0, 0, 0, 14, 2];
        want.extend_from_slice(&r);
        assert_eq!(gamepad(2, &r), want);
    }

    #[test]
    fn a_video_message_splits_into_flags_time_and_picture() {
        let mut m = vec![VIDEO_KEY];
        m.extend_from_slice(&123_456u64.to_be_bytes());
        m.extend_from_slice(&[0, 0, 0, 1, 0x65]);
        assert_eq!(video(&m), Some((VIDEO_KEY, 123_456, &[0, 0, 0, 1, 0x65][..])));
        assert_eq!(video(&[1, 2]), None);
    }

    #[test]
    fn a_got_file_is_whole_only_with_its_end_mark() {
        let all = |b: &[u8]| {
            let mut out = Vec::new();
            Pieces::new(b).read_to_end(&mut out).map(|_| out)
        };
        let mut s = frame(PIECE, b"hello ");
        s.extend(frame(PIECE, b"world"));
        let whole = [s.clone(), frame(PIECE, b"")].concat();
        assert_eq!(all(&whole).unwrap(), b"hello world");
        // After the mark nothing more is read, whatever follows.
        assert_eq!(all(&[whole.clone(), b"junk".to_vec()].concat()).unwrap(), b"hello world");
        assert_eq!(all(&frame(PIECE, b"")).unwrap(), b"", "an empty file is a file");
        // Cut anywhere before the mark — between pieces, in a piece, in a header — is never a file.
        for at in [0, s.len(), s.len() - 2, 3, whole.len() - 1] {
            let e = all(&whole[..at]).unwrap_err();
            assert_eq!(e.kind(), io::ErrorKind::UnexpectedEof, "cut at {at}");
        }
        // Anything but a piece in there is no file either.
        assert_eq!(all(&[s, json(&serde_json::json!({"t": "x"}))].concat()).unwrap_err().kind(), io::ErrorKind::InvalidData);
        // Read a few bytes at a time, it is the same file.
        let mut r = Pieces::new(&whole[..]);
        let (mut out, mut buf) = (Vec::new(), [0u8; 3]);
        loop {
            match r.read(&mut buf).unwrap() {
                0 => break,
                n => out.extend_from_slice(&buf[..n]),
            }
        }
        assert_eq!(out, b"hello world");
    }
}
