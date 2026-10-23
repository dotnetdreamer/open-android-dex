//! The phone's app icons, kept on this computer so the Apps page draws them
//! at once the second time: each is stored under its package and when the app
//! was last updated, so an update brings a new icon and nothing else is asked
//! for again.

use std::path::PathBuf;

use tauri::Manager;

/// The size icons are drawn at on the phone: crisp in the page's grid at 2x
/// and in the title bar and taskbar, small enough that a hundred of them is a
/// few hundred kilobytes.
pub const PX: u32 = 96;

fn dir(app: &tauri::AppHandle, serial: &str) -> Option<PathBuf> {
    let safe: String = serial.chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '_' }).collect();
    let d = app.path().app_cache_dir().ok()?.join("phone-apps").join(safe);
    std::fs::create_dir_all(&d).ok()?;
    Some(d)
}

fn file(app: &tauri::AppHandle, serial: &str, pkg: &str, updated: u64) -> Option<PathBuf> {
    let safe: String = pkg.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '.' || *c == '_').collect();
    Some(dir(app, serial)?.join(format!("{safe}@{updated}.png")))
}

pub fn cached(app: &tauri::AppHandle, serial: &str, pkg: &str, updated: u64) -> Option<Vec<u8>> {
    std::fs::read(file(app, serial, pkg, updated)?).ok()
}

/// The newest icon this computer has for `pkg`, with the update it is from:
/// for opening an app before the phone has said how recent its icon is.
pub fn newest(app: &tauri::AppHandle, serial: &str, pkg: &str) -> Option<(u64, Vec<u8>)> {
    let name = file(app, serial, pkg, 0)?.file_name()?.to_string_lossy().into_owned();
    let stem = name.strip_suffix("0.png")?.to_string();
    let updated = std::fs::read_dir(dir(app, serial)?)
        .ok()?
        .filter_map(|e| e.ok()?.file_name().into_string().ok())
        .filter_map(|n| n.strip_prefix(&stem)?.strip_suffix(".png")?.parse::<u64>().ok())
        .max()?;
    Some((updated, cached(app, serial, pkg, updated)?))
}

pub fn store(app: &tauri::AppHandle, serial: &str, pkg: &str, updated: u64, png: &[u8]) {
    if let Some(f) = file(app, serial, pkg, updated) {
        let _ = std::fs::write(f, png);
    }
}

/// An .ico holding the PNG, which is what a pinned taskbar button's icon has
/// to be: Windows reads the relaunch icon from a file, never from the window.
#[cfg(windows)]
pub fn ico_file(app: &tauri::AppHandle, serial: &str, pkg: &str, updated: u64, png: &[u8]) -> Option<PathBuf> {
    let f = file(app, serial, pkg, updated)?.with_extension("ico");
    if !f.exists() {
        let size = png.len() as u32;
        let mut ico = Vec::with_capacity(22 + png.len());
        ico.extend_from_slice(&[0, 0, 1, 0, 1, 0]); // reserved, type 1 (icon), one image
        // 0 means 256 in an ICONDIR entry; the PNG's own header has the real size
        ico.extend_from_slice(&[(PX as u8), (PX as u8), 0, 0, 1, 0, 32, 0]);
        ico.extend_from_slice(&size.to_le_bytes());
        ico.extend_from_slice(&22u32.to_le_bytes());
        ico.extend_from_slice(png);
        std::fs::write(&f, ico).ok()?;
    }
    Some(f)
}

pub fn data_url(png: &[u8]) -> String {
    format!("data:image/png;base64,{}", base64(png))
}

fn base64(bytes: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity((bytes.len() + 2) / 3 * 4);
    for chunk in bytes.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = ((b[0] as u32) << 16) | ((b[1] as u32) << 8) | b[2] as u32;
        out.push(T[(n >> 18) as usize & 63] as char);
        out.push(T[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if chunk.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    out
}

#[cfg(test)]
mod tests {
    #[test]
    fn base64_is_standard() {
        assert_eq!(super::base64(b"Man"), "TWFu");
        assert_eq!(super::base64(b"Ma"), "TWE=");
        assert_eq!(super::base64(b"M"), "TQ==");
        assert_eq!(super::base64(&[0xFF, 0xFE, 0xFD, 0xFC]), "//79/A==");
    }
}
