//! A socket on the phone, reached through the adb server itself.
//!
//! The app server listens on an abstract socket only adbd may connect to
//! (doc/apps-on-pc.md, "Who gets in"). The way there is the adb server's own
//! protocol — `host:transport:<serial>`, then `localabstract:<name>` — on the
//! connection this process opens to it, so nothing listens on this computer:
//! an `adb forward` would be a loopback port any program here, or any page in
//! any browser, could reach and type into the phone with. Every window opens
//! one; adb carries them side by side over the one USB or Wi-Fi link, so one
//! app's key frame never waits behind another's.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::Duration;

/// The adb server's port: 5037, unless the environment moved it — the bundled
/// adb honours the same variable.
fn adb_port() -> u16 {
    std::env::var("ANDROID_ADB_SERVER_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(5037)
}

/// A stream to `localabstract:<socket>` on `serial`.
pub fn connect(serial: &str, socket: &str) -> Result<TcpStream, String> {
    let mut s = TcpStream::connect(("127.0.0.1", adb_port())).map_err(|e| format!("the adb server: {e}"))?;
    let _ = s.set_nodelay(true);
    let _ = s.set_read_timeout(Some(Duration::from_secs(5)));
    request(&mut s, &format!("host:transport:{serial}"))?;
    request(&mut s, &format!("localabstract:{socket}"))?;
    let _ = s.set_read_timeout(None);
    Ok(s)
}

fn request(s: &mut TcpStream, what: &str) -> Result<(), String> {
    s.write_all(format!("{:04x}{what}", what.len()).as_bytes())
        .map_err(|e| format!("adb: {e}"))?;
    let mut status = [0u8; 4];
    s.read_exact(&mut status).map_err(|e| format!("adb: {e}"))?;
    if &status == b"OKAY" {
        return Ok(());
    }
    // FAIL, then a hex length and the reason
    let mut len = [0u8; 4];
    let reason = if s.read_exact(&mut len).is_ok() {
        let n = std::str::from_utf8(&len).ok().and_then(|h| usize::from_str_radix(h, 16).ok()).unwrap_or(0);
        let mut msg = vec![0u8; n.min(4096)];
        let _ = s.read_exact(&mut msg);
        String::from_utf8_lossy(&msg).into_owned()
    } else {
        String::from_utf8_lossy(&status).into_owned()
    };
    Err(format!("adb refused {what}: {reason}"))
}
