//! Driving the Virtual Display Driver: fetch, install, and the monitor
//! attach/detach that stands in for on/off.
//!
//! The privilege line runs through the middle of this file and everything is
//! arranged around it. *Above* it: downloading (curl.exe), hash checking
//! (certutil), unpacking (tar.exe) — all in app data, all as the user.
//! *On* it: one `Start-Process -Verb RunAs` per install/uninstall, running a
//! script the un-elevated half wrote out in full beforehand, so what the UAC
//! prompt approves is exactly reviewable on disk. *Below* it: nothing — the
//! driver's control pipe is ACL'd to Everyone and `ChangeDisplaySettingsExW`
//! is a plain user API, so sessions never elevate.
//!
//! The system tools rather than crates on purpose: curl.exe and (bsd)tar.exe
//! ship in System32 on every Windows 10 1803+, this feature is Windows-only,
//! and the rest of this app already talks to the OS through spawned tools.

use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use tauri::Manager;

use super::payload::{
    self, MONITOR_H, MONITOR_W, VDD_ADAPTER_STRING, VDD_PIPE, VDD_VERSION, VDD_ZIP_SHA256,
    VDD_ZIP_URL, NEFCON_ZIP_SHA256, NEFCON_ZIP_URL,
};

/// Where the virtual monitor ended up on the desktop, in virtual-screen
/// coordinates. Everything downstream (parking the receiver, capturing,
/// mapping portal clicks) works from this one rect.
#[derive(Debug, Clone, Copy)]
pub struct MonitorRect {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

/// Whether *this app* has the VDD monitor attached for a session right now.
///
/// The point is to never touch a monitor we did not attach: a user may run
/// VDD for their own reasons with a monitor they are actively using, and
/// exit-time cleanup detaching *that* would be a genuine "this app broke my
/// displays". Set only by a session's [`attach_monitor`], cleared by
/// [`detach_monitor`].
static WE_ATTACHED: AtomicBool = AtomicBool::new(false);

/// Does this app currently own an attached virtual monitor? Read by the
/// exit-time teardown so it leaves a user's own VDD alone.
pub fn we_own_monitor() -> bool {
    WE_ATTACHED.load(Ordering::SeqCst)
}

// ── Staging (no admin) ──────────────────────────────────────────────────

fn staging_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("no app data dir: {e}"))?
        .join("dexcast");
    std::fs::create_dir_all(&dir).map_err(|e| format!("could not create {}: {e}", dir.display()))?;
    Ok(dir)
}

/// The staged marker carries the pinned version, so bumping the pin in code
/// makes every existing installation re-download rather than trust old bytes.
pub fn staged(app: &tauri::AppHandle) -> bool {
    let Ok(dir) = staging_dir(app) else { return false };
    std::fs::read_to_string(dir.join("staged.ok"))
        .map(|v| v.trim() == VDD_VERSION)
        .unwrap_or(false)
        && dir.join("VirtualDisplayDriver").join("MttVDD.inf").exists()
        && dir.join("nefcon").join("x64").join("nefconc.exe").exists()
}

fn system32(tool: &str) -> String {
    let root = std::env::var("SystemRoot").unwrap_or_else(|_| "C:\\Windows".into());
    format!("{root}\\System32\\{tool}")
}

fn run(cmd: &mut Command, what: &str) -> Result<String, String> {
    cmd.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped());
    crate::adb::hide_console(cmd);
    let out = cmd.output().map_err(|e| format!("could not run {what}: {e}"))?;
    let stdout = String::from_utf8_lossy(&out.stdout).into_owned();
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr);
        let err = err.trim();
        return Err(format!(
            "{what} failed ({}): {}",
            out.status,
            if err.is_empty() { stdout.trim() } else { err }
        ));
    }
    Ok(stdout)
}

fn sha256_of(path: &Path) -> Result<String, String> {
    let out = run(
        Command::new(system32("certutil.exe")).args([
            "-hashfile",
            &path.to_string_lossy(),
            "SHA256",
        ]),
        "certutil",
    )?;
    payload::parse_certutil_sha256(&out)
        .ok_or_else(|| format!("certutil printed no hash for {}", path.display()))
}

/// Download one pinned archive and unpack it, or report exactly which step
/// said no. A wrong hash deletes the file: a corrupt download should retry
/// cleanly, and a *tampered* one should certainly not linger looking staged.
fn fetch(dir: &Path, url: &str, sha: &str, zip_name: &str, unpack_into: &Path) -> Result<(), String> {
    let zip = dir.join(zip_name);
    if !zip.exists() || sha256_of(&zip)? != sha {
        log::info!("dexcast: downloading {url}");
        run(
            Command::new(system32("curl.exe")).args([
                "-sSL",
                "--fail",
                "--retry",
                "2",
                "-o",
                &zip.to_string_lossy(),
                url,
            ]),
            "curl",
        )?;
        let got = sha256_of(&zip)?;
        if got != sha {
            let _ = std::fs::remove_file(&zip);
            return Err(format!(
                "{zip_name} did not match its pinned checksum (got {got}) — the download was \
                 corrupted or altered, nothing was kept"
            ));
        }
    }
    std::fs::create_dir_all(unpack_into)
        .map_err(|e| format!("could not create {}: {e}", unpack_into.display()))?;
    run(
        Command::new(system32("tar.exe")).args([
            "-xf",
            &zip.to_string_lossy(),
            "-C",
            &unpack_into.to_string_lossy(),
        ]),
        "tar",
    )?;
    Ok(())
}

/// Stage everything the install needs: both archives verified against their
/// pins, unpacked, plus the settings file the elevated script will copy.
pub fn prepare(app: &tauri::AppHandle) -> Result<String, String> {
    if os_is_arm64() {
        // The ARM64 build of the driver may require test-signing mode
        // (upstream README) — a machine-wide security downgrade this app will
        // not walk anyone into.
        return Err(
            "This is an ARM64 PC. The virtual display driver for ARM64 may require Windows \
             test-signing mode, so this route is limited to x64 PCs for now."
                .into(),
        );
    }
    let dir = staging_dir(app)?;
    // VDD's zip carries a VirtualDisplayDriver/ folder; unpack at the root.
    fetch(&dir, VDD_ZIP_URL, VDD_ZIP_SHA256, "vdd-driver.zip", &dir)?;
    // nefcon's zip is bare x64/ x86/ ARM64/ folders; give it a home.
    fetch(&dir, NEFCON_ZIP_URL, NEFCON_ZIP_SHA256, "nefcon.zip", &dir.join("nefcon"))?;
    std::fs::write(dir.join("vdd_settings.xml"), payload::settings_xml())
        .map_err(|e| format!("could not write vdd_settings.xml: {e}"))?;
    std::fs::write(dir.join("staged.ok"), VDD_VERSION)
        .map_err(|e| format!("could not write the staged marker: {e}"))?;
    log::info!("dexcast: payload {VDD_VERSION} staged in {}", dir.display());
    Ok(format!(
        "Downloaded and verified the display driver (v{VDD_VERSION}, ~2 MB) into app data."
    ))
}

/// x64 process on an ARM64 Windows sees the real machine in
/// `PROCESSOR_ARCHITEW6432`; a native process in `PROCESSOR_ARCHITECTURE`.
fn os_is_arm64() -> bool {
    std::env::var("PROCESSOR_ARCHITEW6432")
        .or_else(|_| std::env::var("PROCESSOR_ARCHITECTURE"))
        .map(|a| a.eq_ignore_ascii_case("ARM64"))
        .unwrap_or(false)
}

// ── Install / uninstall (the one elevation) ─────────────────────────────

/// The exit code the elevation wrapper returns when it could not start the
/// admin process at all — a declined UAC prompt, chiefly. `ERROR_CANCELLED`,
/// picked because the inner scripts only ever exit 0 or 1, so it cannot
/// collide with a real install failure.
const EXIT_ELEVATION_DECLINED: i32 = 1223;

/// The environment variable the outer PowerShell reads the script path from.
const SCRIPT_ENV: &str = "OAD_DEXCAST_SCRIPT";

/// Run a prepared script elevated and hand back its exit code.
///
/// The inner script was written to disk first and is referenced by path — the
/// UAC prompt covers a file the user (or a support thread) can open and read,
/// not an opaque command line. Deliberately waited on: unlike the DISM
/// receiver install (minutes, own progress window), this runs in seconds and
/// the caller's next step depends on the outcome.
///
/// Two edges the naive version got wrong:
/// * A declined UAC prompt makes `Start-Process -Verb RunAs` throw a
///   statement-terminating error; unguarded, the script then runs
///   `exit $null.ExitCode` → `exit 0`, reporting success for an install that
///   never happened. The `try/catch` turns that into a distinct sentinel code.
/// * Any attempt to *interpolate* the script path into the command string has
///   a quoting context to get wrong (a profile path can hold spaces, an
///   apostrophe, even a `$`). So the path is not interpolated at all: it rides
///   an environment variable this (un-elevated) outer PowerShell reads into a
///   `$s` variable, whose value Start-Process then quotes for the child. No
///   part of the path is ever re-parsed as source.
fn run_elevated(script_path: &Path) -> Result<i32, String> {
    // `"`"$s`""` — a double-quoted string that expands $s once and wraps it in
    // literal quotes, so a path with spaces reaches the child as one argument;
    // $s's value is inserted, never re-scanned, so quotes/`$` in it are inert.
    let script = format!(
        "$s = $env:{SCRIPT_ENV}; \
         try {{ $p = Start-Process -FilePath powershell -ArgumentList \
         '-NoProfile','-ExecutionPolicy','Bypass','-File',\"`\"$s`\"\" \
         -Verb RunAs -Wait -PassThru -ErrorAction Stop; exit $p.ExitCode }} \
         catch {{ [Console]::Error.WriteLine($_); exit {EXIT_ELEVATION_DECLINED} }}"
    );
    let out = {
        let mut cmd = Command::new("powershell");
        cmd.args(["-NoProfile", "-NonInteractive", "-Command", &script])
            .env(SCRIPT_ENV, script_path)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped());
        crate::adb::hide_console(&mut cmd);
        cmd.output().map_err(|e| format!("could not start the installer: {e}"))?
    };
    match out.status.code() {
        Some(0) => Ok(0),
        Some(EXIT_ELEVATION_DECLINED) => Err(
            "The administrator prompt was declined (or the elevated step could not start), \
             so nothing was changed."
                .to_string(),
        ),
        code => {
            let err = String::from_utf8_lossy(&out.stderr);
            let err = err.trim();
            Err(if err.is_empty() {
                format!(
                    "The elevated step failed (exit {code:?}) — see install.log in the app's \
                     dexcast folder for the transcript."
                )
            } else {
                err.to_string()
            })
        }
    }
}


pub fn install(app: &tauri::AppHandle) -> Result<String, String> {
    if pipe_ping() {
        // VDD already runs on this machine — possibly installed by the user
        // for something else, with a monitor they are actively using. Adopt
        // it rather than installing on top, and do NOT touch its monitor: a
        // session attaches/detaches its own. Only the receiver firewall rule
        // the full install would have added is still needed here.
        ensure_firewall(app)?;
        return Ok("The virtual display driver is already installed — adopted as is.".into());
    }
    if !staged(app) {
        prepare(app)?;
    }
    let dir = staging_dir(app)?;
    let script_path = dir.join("install.ps1");
    std::fs::write(&script_path, payload::install_script())
        .map_err(|e| format!("could not write install.ps1: {e}"))?;

    log::info!("dexcast: requesting elevation to install the virtual display driver");
    run_elevated(&script_path)?;

    // The device node exists once nefcon returns, but the UMDF service takes
    // a moment to come up and open its pipe. Poll rather than sleep — the
    // same reasoning as the tcpip wait in wireless.rs.
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        if pipe_ping() {
            // A fresh install attaches its monitor to the desktop
            // immediately — park it until a session actually wants it, so
            // setup ends with the machine looking untouched.
            let _ = detach_monitor();
            log::info!("dexcast: driver installed and answering");
            return Ok("The virtual display driver is installed.".into());
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    Err(
        "The driver installed but its control pipe never answered. A restart of Windows \
         usually completes a first driver install — see install.log for the transcript."
            .into(),
    )
}

pub fn uninstall(app: &tauri::AppHandle) -> Result<(), String> {
    let dir = staging_dir(app)?;
    // The uninstall script re-verifies and re-extracts nefcon from its zip
    // (same trust boundary as install); make sure the zip is present.
    if !dir.join("nefcon.zip").exists() {
        fetch(&dir, NEFCON_ZIP_URL, NEFCON_ZIP_SHA256, "nefcon.zip", &dir.join("nefcon"))?;
    }
    let script_path = dir.join("uninstall.ps1");
    std::fs::write(&script_path, payload::uninstall_script())
        .map_err(|e| format!("could not write uninstall.ps1: {e}"))?;
    log::info!("dexcast: requesting elevation to remove the virtual display driver");
    run_elevated(&script_path)?;
    Ok(())
}

/// Add the receiver's firewall allow-rule if it is not already there,
/// elevating only when needed. Reading firewall rules is allowed for standard
/// users, so the common case (rule already present, or a fresh install that
/// added it) costs no UAC prompt.
fn ensure_firewall(app: &tauri::AppHandle) -> Result<(), String> {
    if firewall_rule_present() {
        return Ok(());
    }
    let dir = staging_dir(app)?;
    let script_path = dir.join("firewall.ps1");
    std::fs::write(&script_path, payload::firewall_script())
        .map_err(|e| format!("could not write firewall.ps1: {e}"))?;
    log::info!("dexcast: ensuring the Wireless Display firewall rule");
    run_elevated(&script_path)?;
    Ok(())
}

/// Is the inbound receiver firewall rule already there? Best-effort and
/// unelevated: a query failure returns `false`, which just means the caller
/// offers to add it.
fn firewall_rule_present() -> bool {
    let name = payload::firewall_rule_name("Inbound");
    let script = format!(
        "if (Get-NetFirewallRule -DisplayName '{}' -ErrorAction SilentlyContinue) \
         {{ exit 0 }} else {{ exit 1 }}",
        name.replace('\'', "''")
    );
    let mut cmd = Command::new("powershell");
    cmd.args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    crate::adb::hide_console(&mut cmd);
    matches!(cmd.status(), Ok(s) if s.success())
}

// ── The driver's control pipe (no admin) ────────────────────────────────

/// One command, one reply, UTF-16LE both ways — the shape the driver's pipe
/// server and its own Python client agree on.
fn pipe_command(cmd: &str) -> Result<String, String> {
    use windows::core::HSTRING;
    use windows::Win32::Foundation::{CloseHandle, GENERIC_READ, GENERIC_WRITE};
    use windows::Win32::Storage::FileSystem::{
        CreateFileW, ReadFile, WriteFile, FILE_FLAGS_AND_ATTRIBUTES, FILE_SHARE_NONE,
        OPEN_EXISTING,
    };

    unsafe {
        let handle = CreateFileW(
            &HSTRING::from(VDD_PIPE),
            (GENERIC_READ | GENERIC_WRITE).0,
            FILE_SHARE_NONE,
            None,
            OPEN_EXISTING,
            FILE_FLAGS_AND_ATTRIBUTES(0),
            None,
        )
        .map_err(|e| {
            // ERROR_PIPE_BUSY as an HRESULT. The driver serves one client at
            // a time, so "busy" is a *live* driver with someone else (the
            // upstream VDD Control app, say) connected — callers that only
            // ask "is it there" need to tell this apart from "not there".
            if e.code() == windows::core::HRESULT(0x800700E7u32 as i32) {
                PIPE_BUSY.to_string()
            } else {
                format!("driver pipe not available: {e}")
            }
        })?;

        // Trailing NUL wchar: the driver treats the buffer as a wide C
        // string, and an unterminated one reads whatever follows it.
        let mut wide: Vec<u16> = cmd.encode_utf16().collect();
        wide.push(0);
        let bytes: &[u8] =
            std::slice::from_raw_parts(wide.as_ptr() as *const u8, wide.len() * 2);
        let write = WriteFile(handle, Some(bytes), None, None);
        if let Err(e) = write {
            let _ = CloseHandle(handle);
            return Err(format!("pipe write failed: {e}"));
        }

        let mut buf = [0u8; 1024];
        let mut read = 0u32;
        let res = ReadFile(handle, Some(&mut buf), Some(&mut read), None);
        let _ = CloseHandle(handle);
        res.map_err(|e| format!("pipe read failed: {e}"))?;

        let wide: Vec<u16> = buf[..read as usize]
            .chunks_exact(2)
            .map(|c| u16::from_le_bytes([c[0], c[1]]))
            .take_while(|&c| c != 0)
            .collect();
        Ok(String::from_utf16_lossy(&wide))
    }
}

/// The sentinel `pipe_command` answers when the pipe exists but is serving
/// someone else.
const PIPE_BUSY: &str = "driver pipe busy";

/// Is the driver alive? `PING` → `PONG` per Driver.cpp, and doubles as the
/// "is VDD installed" probe — a pipe nobody serves does not open.
///
/// A busy pipe counts as alive: that is still an installed, answering driver,
/// and reporting it "missing" would tell the user to reinstall something that
/// works.
pub fn pipe_ping() -> bool {
    match pipe_command("PING") {
        Ok(r) => r.starts_with("PONG"),
        Err(e) => e == PIPE_BUSY,
    }
}

// ── The monitor itself (no admin) ───────────────────────────────────────

use windows::Win32::Graphics::Gdi::{
    ChangeDisplaySettingsExW, EnumDisplayDevicesW, EnumDisplaySettingsW, CDS_NORESET,
    CDS_TYPE, CDS_UPDATEREGISTRY, DEVMODEW, DISPLAY_DEVICEW, DISPLAY_DEVICE_ATTACHED_TO_DESKTOP,
    DISP_CHANGE_SUCCESSFUL, DM_DISPLAYFREQUENCY, DM_PELSHEIGHT, DM_PELSWIDTH, DM_POSITION,
    ENUM_CURRENT_SETTINGS,
};
use windows::Win32::UI::WindowsAndMessaging::EDD_GET_DEVICE_INTERFACE_NAME;

pub(super) fn wide_str(buf: &[u16]) -> String {
    let len = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
    String::from_utf16_lossy(&buf[..len])
}

/// Find the VDD adapter by the device string its INF declares. Answers the
/// GDI device name (`\\.\DISPLAYn`) and whether it currently has desktop.
fn find_adapter() -> Option<(String, bool)> {
    let mut i = 0u32;
    loop {
        let mut dd = DISPLAY_DEVICEW {
            cb: std::mem::size_of::<DISPLAY_DEVICEW>() as u32,
            ..Default::default()
        };
        let ok = unsafe {
            EnumDisplayDevicesW(None, i, &mut dd, EDD_GET_DEVICE_INTERFACE_NAME)
        };
        if !ok.as_bool() {
            return None;
        }
        if wide_str(&dd.DeviceString) == VDD_ADAPTER_STRING {
            let attached = dd.StateFlags.contains(DISPLAY_DEVICE_ATTACHED_TO_DESKTOP);
            return Some((wide_str(&dd.DeviceName), attached));
        }
        i += 1;
    }
}

/// The right edge of the current desktop, where the virtual monitor goes.
///
/// To the right rather than into a gap: coordinates that overlap a real
/// monitor make Windows shove the layout around, and the user watching
/// their icons jump would rightly wonder what this app just did.
fn desktop_right_edge() -> i32 {
    let mut right = 0i32;
    let mut i = 0u32;
    loop {
        let mut dd = DISPLAY_DEVICEW {
            cb: std::mem::size_of::<DISPLAY_DEVICEW>() as u32,
            ..Default::default()
        };
        if !unsafe { EnumDisplayDevicesW(None, i, &mut dd, 0) }.as_bool() {
            break;
        }
        if dd.StateFlags.contains(DISPLAY_DEVICE_ATTACHED_TO_DESKTOP) {
            let mut dm = DEVMODEW {
                dmSize: std::mem::size_of::<DEVMODEW>() as u16,
                ..Default::default()
            };
            let name = windows::core::HSTRING::from(wide_str(&dd.DeviceName));
            if unsafe { EnumDisplaySettingsW(&name, ENUM_CURRENT_SETTINGS, &mut dm) }.as_bool() {
                let x = unsafe { dm.Anonymous1.Anonymous2.dmPosition.x };
                right = right.max(x + dm.dmPelsWidth as i32);
            }
        }
        i += 1;
    }
    right
}

fn apply_devmode(device: &str, dm: Option<&DEVMODEW>) -> Result<(), String> {
    let name = windows::core::HSTRING::from(device);
    let res = unsafe {
        ChangeDisplaySettingsExW(
            &name,
            dm.map(|d| d as *const DEVMODEW),
            None,
            CDS_UPDATEREGISTRY | CDS_NORESET,
            None,
        )
    };
    if res != DISP_CHANGE_SUCCESSFUL {
        return Err(format!("ChangeDisplaySettingsExW({device}) answered {}", res.0));
    }
    // The NORESET write only stages the change; the empty call commits the
    // whole topology in one relayout instead of one flash per field.
    let res = unsafe { ChangeDisplaySettingsExW(None, None, None, CDS_TYPE(0), None) };
    if res != DISP_CHANGE_SUCCESSFUL {
        return Err(format!("display topology commit answered {}", res.0));
    }
    Ok(())
}

/// Give the virtual monitor desktop space and answer where it landed.
pub fn attach_monitor() -> Result<MonitorRect, String> {
    // The adapter can lag the pipe by a moment after an install or a driver
    // reload — poll briefly before concluding it is not there.
    let deadline = Instant::now() + Duration::from_secs(5);
    let (device, attached) = loop {
        if let Some(found) = find_adapter() {
            break found;
        }
        if Instant::now() >= deadline {
            return Err(
                "No virtual display adapter found — the driver may still be starting, or the \
                 install did not finish."
                    .into(),
            );
        }
        std::thread::sleep(Duration::from_millis(250));
    };

    // If the monitor is *already* attached (a previous unclean shutdown, or a
    // stray adopt), detach it first. Otherwise desktop_right_edge() counts the
    // VDD monitor's own current width into the new x, and each cycle shoves it
    // one desktop-width further right — a drift Windows persists to the
    // registry.
    if attached {
        detach_monitor()?;
    }

    let x = desktop_right_edge();
    let mut dm = DEVMODEW {
        dmSize: std::mem::size_of::<DEVMODEW>() as u16,
        dmFields: DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY,
        dmPelsWidth: MONITOR_W as u32,
        dmPelsHeight: MONITOR_H as u32,
        dmDisplayFrequency: 60,
        ..Default::default()
    };
    dm.Anonymous1.Anonymous2.dmPosition.x = x;
    dm.Anonymous1.Anonymous2.dmPosition.y = 0;
    apply_devmode(&device, Some(&dm))?;

    // Read back rather than assume: Windows is free to adjust a requested
    // position, and the capture + portal math must use where it *is*.
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let mut cur = DEVMODEW {
            dmSize: std::mem::size_of::<DEVMODEW>() as u16,
            ..Default::default()
        };
        let name = windows::core::HSTRING::from(device.as_str());
        if unsafe { EnumDisplaySettingsW(&name, ENUM_CURRENT_SETTINGS, &mut cur) }.as_bool()
            && cur.dmPelsWidth > 0
        {
            // From here the monitor is ours until we detach it — exit cleanup
            // keys off this so it never parks a user's own VDD monitor.
            WE_ATTACHED.store(true, Ordering::SeqCst);
            return Ok(MonitorRect {
                x: unsafe { cur.Anonymous1.Anonymous2.dmPosition.x },
                y: unsafe { cur.Anonymous1.Anonymous2.dmPosition.y },
                w: cur.dmPelsWidth as i32,
                h: cur.dmPelsHeight as i32,
            });
        }
        if Instant::now() >= deadline {
            return Err("The virtual monitor never became active after attaching.".into());
        }
        std::thread::sleep(Duration::from_millis(250));
    }
}

/// Take the virtual monitor's desktop space away again. The monitor itself
/// keeps existing (the driver's floor is one) — detached, it is invisible to
/// the user, to window placement and to the mouse, which is all "off" needs
/// to mean.
pub fn detach_monitor() -> Result<(), String> {
    // Whatever the outcome, this app no longer holds the monitor attached.
    WE_ATTACHED.store(false, Ordering::SeqCst);
    let Some((device, attached)) = find_adapter() else {
        // No adapter, nothing to park — the driver is simply not installed.
        return Ok(());
    };
    if !attached {
        return Ok(());
    }
    let mut dm = DEVMODEW {
        dmSize: std::mem::size_of::<DEVMODEW>() as u16,
        dmFields: DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT,
        dmPelsWidth: 0,
        dmPelsHeight: 0,
        ..Default::default()
    };
    dm.Anonymous1.Anonymous2.dmPosition.x = 0;
    dm.Anonymous1.Anonymous2.dmPosition.y = 0;
    apply_devmode(&device, Some(&dm))
}
