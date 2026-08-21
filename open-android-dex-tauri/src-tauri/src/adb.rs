use serde::Serialize;
use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};
use tauri::Manager;

/// Root of our bundled `resources/` tree.
/// Configured in tauri.conf.json under bundle.resources, so the relative
/// paths below are preserved inside the resource dir in both dev and
/// bundled builds.
pub fn resources_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .resource_dir()
        .map_err(|e| format!("cannot resolve resource dir: {e}"))?;
    Ok(plain_path(dir).join("resources"))
}

/// Directory holding the bundled adb / scrcpy binaries.
pub fn bin_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    Ok(resources_dir(app)?.join("bin"))
}

/// Drop Windows' `\\?\` verbatim prefix.
///
/// `resource_dir()` canonicalises, which on Windows hands back a verbatim
/// path. We can spawn those ourselves — Rust names the executable explicitly —
/// but we also hand these paths to *other* programs, and a verbatim path is
/// not universally accepted: `CreateProcess` in particular does not apply its
/// usual path resolution to one. Keeping the prefix only bought MAX_PATH
/// headroom that a `resources\bin` directory never needs.
fn plain_path(path: PathBuf) -> PathBuf {
    #[cfg(windows)]
    {
        if let Some(rest) = path.to_string_lossy().strip_prefix(r"\\?\") {
            // \\?\UNC\server\share is a different beast — leave it alone
            if !rest.starts_with("UNC\\") {
                return PathBuf::from(rest);
            }
        }
    }
    path
}

/// The bundled name of a helper executable on this platform.
///
/// `EXE_SUFFIX` is `.exe` on Windows and empty everywhere else, which is
/// exactly the difference between the two scrcpy releases we bundle: the
/// Windows zip ships `adb.exe`/`scrcpy.exe`, the macOS tarball ships plain
/// `adb`/`scrcpy`.
pub fn exe_name(stem: &str) -> String {
    format!("{stem}{}", std::env::consts::EXE_SUFFIX)
}

/// Keep a spawned console program from flashing a window.
///
/// Windows-only in effect: `adb` and `scrcpy` are console subsystem programs,
/// so without this every invocation blinks a black box on screen — and the
/// device poll runs one every 2.5 seconds forever. There is no equivalent to
/// suppress on macOS, where a spawned process gets no terminal of its own.
pub fn hide_console(cmd: &mut Command) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    #[cfg(not(windows))]
    {
        let _ = cmd;
    }
}

/// Say so, loudly, if a binary we are about to spawn has lost its executable
/// bit. Only macOS/Linux have one to lose.
///
/// **Reports, never repairs.** The obvious version of this function called
/// `chmod` — and on macOS that hung the app on launch, every time. The binaries
/// live inside the `.app`, and a process that modifies its own bundle while
/// Gatekeeper is still assessing that bundle blocks in `chmod(2)` and does not
/// come back; because this runs from `setup()`, the window never appeared at
/// all. A repair that can deadlock the app is worse than the problem it fixes.
///
/// It does not need to repair anything either. The bit is set in CI right after
/// the scrcpy tarball is unpacked, asserted there by the "Verify bundled
/// binaries" step, preserved by the Tauri bundler's copy and by the `.dmg`
/// (a filesystem image). What actually strips it is `actions/upload-artifact`,
/// which is exactly why the workflow never routes these two through one.
///
/// So this exists for the case none of that covers — someone unpacking a build
/// by hand — where a line in the log naming the file is the whole point:
/// otherwise the first adb call fails with a permission error that names no
/// cause.
#[cfg(unix)]
pub fn warn_if_not_executable(path: &std::path::Path) {
    use std::os::unix::fs::PermissionsExt;
    let Ok(meta) = std::fs::metadata(path) else {
        return; // absent is a different report — see `log_startup`
    };
    // Owner execute is the only bit that decides whether WE can spawn it.
    if meta.permissions().mode() & 0o100 != 0 {
        return;
    }
    log::error!(
        "{} is not executable ({:o}) — nothing can spawn it. Restore it with: chmod +x '{}'",
        path.display(),
        meta.permissions().mode() & 0o777,
        path.display()
    );
}

#[cfg(not(unix))]
pub fn warn_if_not_executable(_path: &std::path::Path) {}

/// A `Command` for the bundled adb (PATH fallback on dev machines), with
/// the console window suppressed.
pub fn adb_command(app: &tauri::AppHandle) -> Command {
    let bundled = bin_dir(app).map(|d| d.join(exe_name("adb")));
    let mut cmd = match bundled {
        Ok(p) if p.exists() => Command::new(p),
        _ => Command::new(exe_name("adb")),
    };
    // Drop the caller's mDNS overrides. `ADB_MDNS_OPENSCREEN=0` forces the old
    // Bonjour backend, which on a Windows box without Apple's Bonjour installed
    // means no discovery at all — and the variable is left set on developer
    // machines from years ago, when it was the flag that turned the *modern*
    // backend on. Wireless pairing depends on discovery working, so the
    // environment we inherited does not get a say in it.
    cmd.env_remove("ADB_MDNS_OPENSCREEN");
    cmd.env_remove("ADB_MDNS");
    hide_console(&mut cmd);
    cmd
}

/// Ceiling for an ordinary adb call. Nothing here is meant to take seconds:
/// a `settings put` is milliseconds once the phone answers. The timeout exists
/// for the case where the phone stops answering at all — an adb that hangs
/// used to hang the launch with it, silently and forever.
const ADB_TIMEOUT: Duration = Duration::from_secs(25);
/// `adb install` really can take a minute on a slow phone.
const INSTALL_TIMEOUT: Duration = Duration::from_secs(240);
/// Longer than this and the log says so, even for calls that succeed.
const SLOW_CALL: Duration = Duration::from_millis(1500);

pub fn run_adb(app: &tauri::AppHandle, args: &[&str]) -> Result<String, String> {
    run_adb_full(app, args, ADB_TIMEOUT, true)
}

/// Like [`run_adb`], but only writes to the log when the call fails or drags.
/// For the device poll, which runs every 2.5s forever and would otherwise be
/// the only thing in the file.
pub fn run_adb_quiet(app: &tauri::AppHandle, args: &[&str]) -> Result<String, String> {
    run_adb_full(app, args, ADB_TIMEOUT, false)
}

pub fn run_adb_timeout(
    app: &tauri::AppHandle,
    args: &[&str],
    timeout: Duration,
) -> Result<String, String> {
    run_adb_full(app, args, timeout, true)
}

fn read_pipe<R: Read + Send + 'static>(pipe: Option<R>) -> thread::JoinHandle<String> {
    thread::spawn(move || {
        let mut buf = Vec::new();
        if let Some(mut pipe) = pipe {
            let _ = pipe.read_to_end(&mut buf);
        }
        String::from_utf8_lossy(&buf).into_owned()
    })
}

/// Long outputs (a dumpsys, a package list) belong in the log, but not at
/// full length on every line.
fn trunc(text: &str) -> String {
    const MAX: usize = 1200;
    let text = text.trim();
    if text.chars().count() <= MAX {
        return text.to_string();
    }
    let head: String = text.chars().take(MAX).collect();
    format!("{head}… (+{} chars)", text.chars().count() - MAX)
}

/// One adb invocation, traced and bounded.
///
/// The pipes are drained on their own threads rather than by `output()`,
/// because the process has to stay killable: `output()` waits forever, and a
/// phone that has stopped talking to adb is exactly when we most need to give
/// up and say so.
fn run_adb_full(
    app: &tauri::AppHandle,
    args: &[&str],
    timeout: Duration,
    verbose: bool,
) -> Result<String, String> {
    let mut cmd = adb_command(app);
    cmd.args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let label = format!("adb {}", args.join(" "));
    if verbose {
        log::debug!("→ {}", trunc(&label));
    }
    let started = Instant::now();
    let mut child = cmd.spawn().map_err(|e| {
        log::error!("cannot spawn adb ({}): {e}", trunc(&label));
        format!("failed to run adb: {e}")
    })?;
    let out_reader = read_pipe(child.stdout.take());
    let err_reader = read_pipe(child.stderr.take());

    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Some(status),
            Ok(None) => {}
            Err(e) => {
                log::error!("adb wait failed ({}): {e}", trunc(&label));
                break None;
            }
        }
        if started.elapsed() >= timeout {
            let _ = child.kill();
            let _ = child.wait();
            break None;
        }
        thread::sleep(Duration::from_millis(10));
    };

    let stdout = out_reader.join().unwrap_or_default();
    let stderr = err_reader.join().unwrap_or_default();
    let stdout = stdout.trim().to_string();
    let stderr = stderr.trim().to_string();
    let ms = started.elapsed().as_millis();

    let Some(status) = status else {
        log::error!(
            "✗ {} — no answer after {}s, adb killed",
            trunc(&label),
            timeout.as_secs()
        );
        return Err(format!(
            "adb stopped responding after {}s: {label}",
            timeout.as_secs()
        ));
    };

    if status.success() {
        let text = if stdout.is_empty() { stderr } else { stdout };
        if verbose {
            log::debug!("← {ms}ms {}", one_line(&label, &text));
        } else if started.elapsed() > SLOW_CALL {
            log::debug!("← {ms}ms (slow) {}", one_line(&label, &text));
        }
        Ok(text)
    } else {
        let err = if stderr.is_empty() { stdout } else { stderr };
        log::warn!("✗ {} — {status} after {ms}ms: {}", trunc(&label), trunc(&err));
        Err(err)
    }
}

/// `adb …` plus its output, folded onto one log line when it is short enough
/// to fit and indented under it when it is not.
fn one_line(label: &str, text: &str) -> String {
    let label = trunc(label);
    if text.is_empty() {
        label
    } else if !text.contains('\n') {
        format!("{label} → {}", trunc(text))
    } else {
        format!("{label} →\n{}", trunc(text))
    }
}

// Every command below is `(async)`: Tauri runs a plain `fn` command on the
// main thread, so a single slow adb call — an install, a phone that stopped
// answering — froze the whole app, including the launch it was in the middle
// of. Off the main thread they merely take as long as they take.

#[tauri::command(async)]
pub fn adb_version(app: tauri::AppHandle) -> Result<String, String> {
    run_adb(&app, &["version"])
}

#[tauri::command(async)]
pub fn adb_devices(app: tauri::AppHandle) -> Result<String, String> {
    run_adb(&app, &["devices", "-l"])
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceInfo {
    pub serial: String,
    /// "device" | "unauthorized" | "offline" | ...
    pub state: String,
    pub model: String,
    pub product: String,
    /// "usb" | "wifi"
    pub connection: String,
    pub brand: Option<String>,
    pub android_version: Option<String>,
    /// `ro.serialno` — stable across USB and Wi-Fi, unlike [`Self::serial`].
    pub hardware_serial: Option<String>,
}

/// What the last poll saw, so the log records phones appearing, going
/// unauthorized and disappearing — and nothing in between. The poll itself
/// runs every 2.5s for as long as the app is open.
static LAST_SEEN: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

/// Parse `adb devices -l` into structured entries, enriched with
/// brand / Android version for devices that are actually online.
#[tauri::command(async)]
pub fn adb_list_devices(app: tauri::AppHandle) -> Result<Vec<DeviceInfo>, String> {
    let raw = run_adb_quiet(&app, &["devices", "-l"])?;
    let mut devices = Vec::new();
    for line in raw.lines().skip_while(|l| !l.starts_with("List of")).skip(1) {
        let line = line.trim();
        if line.is_empty() || line.starts_with('*') {
            continue;
        }
        let mut parts = line.split_whitespace();
        let Some(serial) = parts.next() else { continue };
        let state = parts.next().unwrap_or("unknown").to_string();
        let mut model = String::new();
        let mut product = String::new();
        for kv in parts {
            if let Some((key, value)) = kv.split_once(':') {
                match key {
                    "model" => model = value.replace('_', " "),
                    "product" => product = value.to_string(),
                    _ => {}
                }
            }
        }
        // A phone reached over TCP usually carries its address as the serial,
        // so a colon is the obvious tell — but a phone adb auto-connected to
        // after pairing is named after its mDNS service instead
        // ("adb-R5CT30…-vWgJpq._adb-tls-connect._tcp"), which has no colon at
        // all and was being reported as a device on the end of a cable.
        let connection = if serial.contains(':') || serial.contains("adb-tls-connect") {
            "wifi"
        } else {
            "usb"
        };

        // ro.serialno rather than the adb serial: it is the same string
        // whichever way the phone is attached, so it is what tells the UI that
        // the device on the cable and the device on Wi-Fi are one phone.
        let (brand, android_version, hardware_serial) = if state == "device" {
            let out = run_adb_quiet(
                &app,
                &[
                    "-s",
                    serial,
                    "shell",
                    "getprop ro.product.brand; getprop ro.build.version.release; getprop ro.serialno",
                ],
            )
            .unwrap_or_default();
            let mut lines = out.lines().map(|l| l.trim().to_string());
            (
                lines.next().filter(|s| !s.is_empty()),
                lines.next().filter(|s| !s.is_empty()),
                lines.next().filter(|s| !s.is_empty()),
            )
        } else {
            (None, None, None)
        };

        devices.push(DeviceInfo {
            serial: serial.to_string(),
            state,
            model,
            product,
            connection: connection.to_string(),
            brand,
            android_version,
            hardware_serial,
        });
    }

    let summary = if devices.is_empty() {
        "none".to_string()
    } else {
        devices
            .iter()
            .map(|d| {
                format!(
                    "{} [{}] {} {}",
                    d.serial,
                    d.state,
                    d.connection,
                    d.model.trim()
                )
            })
            .collect::<Vec<_>>()
            .join(", ")
    };
    let mut last = LAST_SEEN.lock().unwrap();
    if last.as_deref() != Some(summary.as_str()) {
        log::info!("devices: {summary}");
        *last = Some(summary);
    }
    Ok(devices)
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub package: String,
    /// Full launcher component ("pkg/activity") for `am start -n`.
    pub component: String,
    pub label: String,
    pub third_party: bool,
}

/// Well-known packages whose derived label would be misleading.
const KNOWN_LABELS: &[(&str, &str)] = &[
    ("com.android.vending", "Play Store"),
    ("com.android.camera2", "Camera"),
    ("com.google.android.youtube", "YouTube"),
    ("com.google.android.gm", "Gmail"),
    ("com.google.android.googlequicksearchbox", "Google"),
    ("com.whatsapp", "WhatsApp"),
    ("com.google.android.apps.docs", "Drive"),
];

/// Human-ish label from a package name — no companion APK, so this is a
/// heuristic: last non-generic dot-segment, capitalized.
fn label_from_package(pkg: &str) -> String {
    if let Some((_, label)) = KNOWN_LABELS.iter().find(|(p, _)| *p == pkg) {
        return (*label).to_string();
    }
    const GENERIC: &[&str] = &["android", "app", "apps", "mobile", "client", "main", "ui", "free", "google"];
    let seg = pkg
        .split('.')
        .rev()
        .find(|s| !GENERIC.contains(&s.to_lowercase().as_str()))
        .unwrap_or(pkg);
    let mut chars = seg.chars();
    match chars.next() {
        Some(f) => f.to_uppercase().collect::<String>() + chars.as_str(),
        None => pkg.to_string(),
    }
}

/// Launchable apps via `cmd package query-activities` — needs nothing on
/// the device beyond adb itself.
#[tauri::command(async)]
pub fn adb_list_apps(app: tauri::AppHandle, serial: String) -> Result<Vec<AppInfo>, String> {
    let out = run_adb(
        &app,
        &[
            "-s",
            &serial,
            "shell",
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER",
        ],
    )?;
    let third: HashSet<String> = run_adb(&app, &["-s", &serial, "shell", "pm list packages -3"])
        .unwrap_or_default()
        .lines()
        .filter_map(|l| l.trim().strip_prefix("package:"))
        .map(|s| s.trim().to_string())
        .collect();

    let mut seen = HashSet::new();
    let mut apps = Vec::new();
    for line in out.lines() {
        let line = line.trim();
        if !line.contains('/') || line.contains('=') {
            continue;
        }
        let Some(pkg) = line.split('/').next() else { continue };
        let pkg = pkg.trim();
        if pkg.is_empty() || !seen.insert(pkg.to_string()) {
            continue;
        }
        apps.push(AppInfo {
            package: pkg.to_string(),
            component: line.to_string(),
            label: label_from_package(pkg),
            third_party: third.contains(pkg),
        });
    }
    apps.sort_by(|a, b| a.label.to_lowercase().cmp(&b.label.to_lowercase()));
    Ok(apps)
}

/// The `Settings.Global` keys the desktop profile writes. Putting these back
/// is most of what "Exit DeX" means: freeform windowing and a relaxed
/// hidden-API policy are ours, not the phone's.
const DESKTOP_GLOBALS: [&str; 4] = [
    "force_desktop_mode_on_external_displays",
    "enable_freeform_support",
    "force_resizable_activities",
    "hidden_api_policy",
];

/// Android's tapjacking guard, which a desktop built out of overlay windows
/// cannot survive with.
///
/// Every piece of our shell — taskbar, app drawer, tray flyouts, the gauge —
/// is a TYPE_APPLICATION_OVERLAY window, and those are exactly what
/// `block_untrusted_touches` was written to distrust. The moment ANY system
/// surface is stacked above them, InputDispatcher stops delivering touches to
/// everything underneath and logs "Dropping untrusted touch event": the
/// desktop is still drawn, still animating, and completely dead to the mouse.
///
/// It does not take a hostile app to put such a surface there. Our own app
/// drawer asks for background blur, and the platform answers by creating a
/// display-wide "Dim Layer for - Display N" owned by uid 1000 — alpha 0.00,
/// invisible, and BLOCK_UNTRUSTED, which ignores opacity. Open the drawer once
/// and the taskbar beneath it is unclickable for the rest of the session,
/// including the click that would have dismissed the drawer.
///
/// Kept OUT of [`DESKTOP_GLOBALS`] on purpose: adding a fifth key there would
/// make every snapshot written before this existed fail the "already on file"
/// test, and re-snapshot the display profile from values we had already
/// written. A phone with such a snapshot simply has no row for this key, and
/// [`undo_globals_script`] reads a missing row as "delete it" — which is the
/// right answer anyway, since unset is the platform default and blocking.
const TOUCH_GLOBAL: &str = "block_untrusted_touches";

/// The `Settings.Global` keys "Reduce quality" zeroes (Settings → Performance).
///
/// Kept apart from [`DESKTOP_GLOBALS`] because the two are undone by different
/// rules: those four are written on every prepare and so are always ours to put
/// back, while these are only written when the user asks for the mode — see
/// [`undo_globals_script`].
///
/// This is the single biggest thing the mode does. An app launch spends most of
/// its visible time in the open transition, and at scale 0 the window is simply
/// there. Only adb can write them: the launcher does not hold
/// WRITE_SECURE_SETTINGS and nothing grants it, but the shell uid these run as
/// does.
pub const PERF_GLOBALS: [&str; 3] = [
    "window_animation_scale",
    "transition_animation_scale",
    "animator_duration_scale",
];

/// The `settings put` chain that switches the platform's animations off.
pub fn perf_globals_script() -> String {
    PERF_GLOBALS
        .iter()
        .map(|k| format!("settings put global {k} 0"))
        .collect::<Vec<_>>()
        .join("; ")
}

/// One `settings get` round trip for every key we may overwrite.
fn read_desktop_globals(app: &tauri::AppHandle, serial: &str) -> HashMap<String, String> {
    let keys = DESKTOP_GLOBALS
        .iter()
        .chain(PERF_GLOBALS.iter())
        .chain(std::iter::once(&TOUCH_GLOBAL))
        .copied()
        .collect::<Vec<_>>()
        .join(" ");
    let out = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            &format!("for k in {keys}; do echo \"$k=$(settings get global $k)\"; done"),
        ],
    )
    .unwrap_or_default();
    out.lines()
        .filter_map(|l| l.trim().split_once('='))
        .map(|(k, v)| (k.to_string(), v.trim().to_string()))
        .collect()
}

fn restore_store_path(app: &tauri::AppHandle) -> Option<PathBuf> {
    app.path()
        .app_config_dir()
        .ok()
        .map(|d| d.join("desktop-restore.json"))
}

/// serial → the values [`DESKTOP_GLOBALS`] held before we touched them.
fn load_restore_map(app: &tauri::AppHandle) -> HashMap<String, HashMap<String, String>> {
    restore_store_path(app)
        .and_then(|p| std::fs::read_to_string(p).ok())
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn save_restore_map(app: &tauri::AppHandle, map: &HashMap<String, HashMap<String, String>>) {
    let Some(path) = restore_store_path(app) else {
        return;
    };
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if let Ok(json) = serde_json::to_string(map) {
        let _ = std::fs::write(path, json);
    }
}

/// Record what the phone looked like before the desktop profile went on.
///
/// On disk, not in memory: the desktop app can be killed (or crash) with a
/// session live, and the next run is then the only chance to put the phone
/// back. For the same reason a snapshot is only taken when there isn't one —
/// a second prepare over a profile that is already applied would record OUR
/// values as the originals and make the restore a no-op.
///
/// A read that came back empty (adb did not answer) is recorded as such, and
/// means "none of these keys were set". That is the state of every phone that
/// has not run a desktop before, and it is the safe way to be wrong: the
/// alternative — waiting for a reading — is what records our own values later.
fn snapshot_desktop_globals(app: &tauri::AppHandle, serial: &str, before: &HashMap<String, String>) {
    let mut map = load_restore_map(app);
    // Asked of the KEYS, not of the serial: `arm_perf_globals` can put an entry
    // on file holding nothing but the animation scales, and that entry says
    // nothing about the four below — treating it as "already snapshotted"
    // would leave the display profile with no recorded original values.
    if map
        .get(serial)
        .is_some_and(|saved| DESKTOP_GLOBALS.iter().all(|k| saved.contains_key(*k)))
    {
        log::info!("desktop profile snapshot for {serial} already on file — keeping it");
        return;
    }
    log::info!(
        "remembering the pre-DeX display profile of {serial}: {}",
        DESKTOP_GLOBALS
            .iter()
            .map(|k| format!("{k}={}", before.get(*k).map_or("?", String::as_str)))
            .collect::<Vec<_>>()
            .join(" ")
    );
    // Merged, not replaced, for the mirror image of the reason above: a row
    // `arm_perf_globals` already recorded holds a value from BEFORE the mode
    // was switched on, and `before` here is a reading taken after it — writing
    // over it would record our own zero as the value to restore.
    let entry = map.entry(serial.to_string()).or_default();
    for (key, value) in before {
        entry.entry(key.clone()).or_insert_with(|| value.clone());
    }
    save_restore_map(app, &map);
}

/// Flip the switches for a freeform desktop display. `samsung_desktop`
/// additionally forces desktop mode on external displays, which on Samsung
/// summons the DeX shell (taskbar/home) onto the display — wanted when
/// running WITHOUT our own launcher, unwanted (UI mixing) with it.
#[tauri::command(async)]
pub fn adb_prepare_desktop(
    app: tauri::AppHandle,
    serial: String,
    samsung_desktop: bool,
) -> Result<bool, String> {
    log::info!("── prepare desktop profile on {serial} (samsung_desktop={samsung_desktop}) ──");
    crate::diag::log_device(&app, &serial);
    // Read before writing: this is both the "was Samsung desktop mode already
    // on" answer and the snapshot Exit DeX restores from.
    let before = read_desktop_globals(&app, &serial);
    let already = before
        .get("force_desktop_mode_on_external_displays")
        .is_some_and(|v| v == "1");
    snapshot_desktop_globals(&app, &serial, &before);
    let desktop_flag = if samsung_desktop { 1 } else { 0 };
    // hidden_api_policy=1 lets our launcher call setLaunchWindowingMode via
    // reflection — freeform from the first frame on decoration-free
    // displays, where the freeform-by-default machinery doesn't apply.
    run_adb(
        &app,
        &[
            "-s",
            &serial,
            "shell",
            &format!(
                "settings put global force_desktop_mode_on_external_displays {desktop_flag}; settings put global enable_freeform_support 1; settings put global force_resizable_activities 1; settings put global hidden_api_policy 1; settings put global {TOUCH_GLOBAL} 0"
            ),
        ],
    )?;
    // Read back rather than assume: on a managed or hardened device a
    // `settings put` is accepted and dropped, and every later symptom
    // (no freeform, launcher stuck fullscreen) points somewhere else.
    let after = read_desktop_globals(&app, &serial);
    log::info!(
        "desktop profile now: {}",
        DESKTOP_GLOBALS
            .iter()
            .chain(std::iter::once(&TOUCH_GLOBAL))
            .map(|k| format!("{k}={}", after.get(*k).map_or("?", String::as_str)))
            .collect::<Vec<_>>()
            .join(" ")
    );
    // Worth its own line: this is the one whose failure looks like nothing at
    // all until the first click, and then looks like a frozen desktop.
    if after.get(TOUCH_GLOBAL).map(String::as_str) != Some("0") {
        log::warn!(
            "{TOUCH_GLOBAL} did not take on {serial} — the phone is still blocking touches that              pass under a system dim layer, so opening the app drawer will leave the taskbar and              desktop unclickable until the drawer is closed from the PC"
        );
    }
    Ok(already)
}

/// End the desktop session on the phone and leave it an ordinary phone again:
/// the "Exit DeX" button, and also what runs when a session ends any other way.
///
/// Never fails the caller. Everything here is an undo, and an undo that cannot
/// run (phone unplugged, adb gone) must not turn into an error the UI has to
/// model — the log says what did and did not land.
#[tauri::command(async)]
pub fn adb_end_desktop(app: tauri::AppHandle, serial: String) -> Result<(), String> {
    restore_phone(&app, &serial);
    Ok(())
}

/// Ceiling for the undo. Shorter than [`ADB_TIMEOUT`] because this also runs
/// while the app is exiting, and a phone that has stopped answering must not
/// hold the process open.
const RESTORE_TIMEOUT: Duration = Duration::from_secs(8);

/// Drop our accessibility service from a colon-separated services list,
/// leaving anything the user enabled themselves (TalkBack, a password
/// manager) exactly where it was.
fn without_caption_service(list: &str) -> String {
    without_component(list, CAPTION_SERVICE_COMPONENT)
}

/// The same, for any component. Both of the secure settings we touch are
/// colon-separated lists shared with whatever the user enabled themselves, and
/// clobbering either one turns their own services off.
fn without_component(list: &str, component: &str) -> String {
    let list = list.trim();
    // `settings get` prints the literal string "null" for an unset key.
    if list.is_empty() || list == "null" {
        return String::new();
    }
    list.split(':')
        .map(str::trim)
        .filter(|s| !s.is_empty() && *s != component)
        .collect::<Vec<_>>()
        .join(":")
}

/// Add a component to a colon-separated list, or leave the list alone when it
/// is already there. The other half of [`without_component`].
fn with_component(list: &str, component: &str) -> String {
    let list = list.trim();
    let list = if list == "null" { "" } else { list };
    if list.is_empty() {
        return component.to_string();
    }
    if list.split(':').any(|s| s.trim() == component) {
        return list.to_string();
    }
    format!("{list}:{component}")
}

/// A value read back from `settings get` that is safe to hand to
/// `settings put` unquoted. These keys hold `0`/`1`; anything else is not
/// something we wrote, and is treated as "was never set".
fn restorable(value: &str) -> Option<&str> {
    let v = value.trim();
    (!v.is_empty()
        && v != "null"
        && v.len() <= 16
        && v.chars().all(|c| c.is_ascii_alphanumeric() || c == '.'))
    .then_some(v)
}

/// The one pointer key Settings → Mouse &amp; cursor writes.
///
/// `Settings.System.pointer_speed`, an int in -7..=7 defaulting to 0. It is a
/// PRIVATE_SETTING: an app is refused even holding WRITE_SETTINGS ("You cannot
/// change private secure settings") and the shell uid this adb runs as is
/// exempt — which is the whole reason it is written from here rather than by
/// the launcher.
///
/// Only means anything while the PHONE draws the pointer (`--mouse=uhid`),
/// where the mouse is relative and Android applies its own speed curve. In the
/// default sdk mode the pointer's motion is the computer's and nothing on the
/// device gets to scale it.
const POINTER_SPEED: &str = "pointer_speed";

/// How the key is stored in the restore snapshot.
///
/// Namespaced, because everything else in that map is `Settings.Global` and a
/// bare "pointer_speed" would not say which `settings` command puts it back.
fn pointer_speed_slot() -> String {
    format!("system/{POINTER_SPEED}")
}

/// The phone's current pointer speed, or None if the read did not happen.
///
/// `settings get` prints the literal string "null" for a key with no row, so an
/// EMPTY answer is the unambiguous signal that the read itself failed. The
/// difference matters: recording "" as the value to restore is exactly how an
/// undo becomes a permanent change.
fn read_pointer_speed(app: &tauri::AppHandle, serial: &str) -> Option<String> {
    let out = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            &format!("settings get system {POINTER_SPEED}"),
        ],
    )
    .ok()?;
    let value = out.trim();
    (!value.is_empty()).then(|| value.to_string())
}

/// A pointer speed that is safe to hand back to `settings put` unquoted.
///
/// Separate from [`restorable`] because this one is SIGNED ("-3"), and
/// loosening the shared helper would loosen it for every `Settings.Global` key
/// too.
fn restorable_pointer_speed(value: &str) -> Option<&str> {
    let v = value.trim();
    (v != "null" && v.len() <= 4 && v.parse::<i32>().is_ok()).then_some(v)
}

/// Put the pointer speed back, off an already-loaded snapshot.
///
/// Only touches the key when the snapshot has a ROW for it. A missing row is
/// not "it was unset", it is "this desktop never changed the pointer speed" —
/// and a phone whose owner runs a fast pointer must not have that deleted by a
/// session that never touched it.
fn undo_pointer_speed_script(saved: &HashMap<String, String>) -> String {
    let Some(before) = saved.get(&pointer_speed_slot()) else {
        return String::new();
    };
    match restorable_pointer_speed(before) {
        // A negative value goes back through the provider for the reason
        // pointer_speed_script explains — but WITHOUT the read-back guard it
        // uses, because this string is also armed into the on-device watchdog
        // and that one has to stay a single line with no quotes and no command
        // substitution (WmDaemon re-joins it from whitespace-split tokens).
        // `content insert` needs neither.
        Some(v) if v.starts_with('-') => format!(
            "content insert --uri content://settings/system \
             --bind name:s:{POINTER_SPEED} --bind value:i:{v}; "
        ),
        Some(v) => format!("settings put system {POINTER_SPEED} {v}; "),
        None => format!("settings delete system {POINTER_SPEED}; "),
    }
}

/// Make sure this phone's snapshot has a row for the pointer speed before it is
/// written, and answer whether it is now safe to write.
///
/// Only ever ADDS the row. Overwriting it would record the value we are about
/// to write as the one to restore, which is the one way to make an undo
/// permanent.
///
/// Returns false when the phone could not be read. The caller must then not
/// write either: changing a setting with nothing on file that knows how to put
/// it back is worse than the setting not taking.
fn arm_pointer_speed(app: &tauri::AppHandle, serial: &str) -> bool {
    let mut map = load_restore_map(app);
    if map
        .get(serial)
        .is_some_and(|e| e.contains_key(&pointer_speed_slot()))
    {
        return true;
    }
    let Some(before) = read_pointer_speed(app, serial) else {
        log::warn!(
            "could not read {POINTER_SPEED} on {serial} — refusing to change it \
             with nothing on file to put back"
        );
        return false;
    };
    log::info!("remembering system/{POINTER_SPEED}={before} on {serial} before the pointer speed");
    map.entry(serial.to_string())
        .or_default()
        .insert(pointer_speed_slot(), before);
    save_restore_map(app, &map);
    true
}

/// Turn the launcher's `cursor` request into a `settings` line.
///
/// Wire form is `speed.<n>`, which is what fits through the request pump's
/// argument filter (letters, digits, dots, underscores, hyphens) without
/// widening it for every other command sharing the queue. Anything that does
/// not parse — or a phone that could not be read — produces an empty script
/// rather than a half-applied one.
pub fn pointer_speed_script(app: &tauri::AppHandle, serial: &str, arg: &str) -> String {
    let Some(speed) = arg
        .strip_prefix("speed.")
        .and_then(|v| v.parse::<i32>().ok())
        .map(|v| v.clamp(-7, 7))
    else {
        return String::new();
    };
    if !arm_pointer_speed(app, serial) {
        return String::new();
    }
    if speed >= 0 {
        return format!("settings put system {POINTER_SPEED} {speed}");
    }
    // A NEGATIVE speed is an argv token starting with '-', and whether the
    // `settings` shell command takes that as a value or as an option it does
    // not recognise is a per-build detail of its argument parser that is not
    // worth betting the setting on. So the write checks itself and falls back
    // to the provider, where the value rides inside a `value:i:-3` token that
    // starts with a letter and cannot be read as an option.
    //
    // Costs one extra `settings get` only on the negative half of the slider;
    // "faster" (the reason this control exists) never reaches it.
    format!(
        "settings put system {POINTER_SPEED} {speed}; \
         [ \"$(settings get system {POINTER_SPEED})\" = \"{speed}\" ] || \
         content insert --uri content://settings/system \
         --bind name:s:{POINTER_SPEED} --bind value:i:{speed}"
    )
}

/// The `settings` chain that puts [`DESKTOP_GLOBALS`] back the way this phone
/// had them. A key that was unset is DELETED rather than written as 0 — the
/// phone never had a row for it, and a stray 0 is not "back to normal".
///
/// Shared with the on-device watchdog, which is the whole reason this is a
/// string and not a series of calls: the daemon can work out everything else
/// about a session by looking at the phone, but it cannot know what these
/// settings were BEFORE DeX — only this side kept that. So it is armed with
/// this exact line (see `wm::WmClient::arm`), which is why the output stays one
/// line with no quotes and no newlines in it.
pub fn undo_globals_script(app: &tauri::AppHandle, serial: &str) -> String {
    let saved = load_restore_map(app).remove(serial).unwrap_or_default();
    let mut out = String::new();
    for key in DESKTOP_GLOBALS {
        match saved.get(key).and_then(|v| restorable(v)) {
            Some(v) => out.push_str(&format!("settings put global {key} {v}; ")),
            None => out.push_str(&format!("settings delete global {key}; ")),
        }
    }
    // Missing row = delete, which is also what a snapshot taken before this key
    // existed should do — see the note on [`TOUCH_GLOBAL`].
    match saved.get(TOUCH_GLOBAL).and_then(|v| restorable(v)) {
        Some(v) => out.push_str(&format!("settings put global {TOUCH_GLOBAL} {v}; ")),
        None => out.push_str(&format!("settings delete global {TOUCH_GLOBAL}; ")),
    }
    out.push_str(&undo_perf_globals_script(&saved));
    // Carried by the same string, so a pulled cable puts the pointer speed back
    // too: the daemon's watchdog is armed with this line, and it is the only
    // thing that knows what the speed was before DeX.
    out.push_str(&undo_pointer_speed_script(&saved));
    out
}

/// The animation scales half of the undo, off an already-loaded snapshot.
///
/// Only touches a key the snapshot has a ROW for — the missing-key case is not
/// "it was unset", it is "this snapshot was taken before the app knew about
/// these keys", and a phone whose owner runs at 0.5x animations must not have
/// that deleted by a desktop session that never touched it. The four in
/// [`DESKTOP_GLOBALS`] can treat missing as unset because they are written on
/// every single prepare; these are written only while Reduce quality is on.
///
/// Runs on every exit regardless of whether the mode was ever switched on this
/// session — writing a value the phone already holds costs nothing, and it is
/// what puts the animations back after a session that died with the mode on.
fn undo_perf_globals_script(saved: &HashMap<String, String>) -> String {
    let mut out = String::new();
    for key in PERF_GLOBALS {
        let Some(before) = saved.get(key) else {
            continue;
        };
        match restorable(before) {
            Some(v) => out.push_str(&format!("settings put global {key} {v}; ")),
            None => out.push_str(&format!("settings delete global {key}; ")),
        }
    }
    out
}

/// The same undo for a caller that only has the serial — the request pump,
/// when "Reduce quality" is switched back off mid-session.
pub fn undo_perf_globals(app: &tauri::AppHandle, serial: &str) -> String {
    undo_perf_globals_script(&load_restore_map(app).remove(serial).unwrap_or_default())
}

/// Make sure this phone's snapshot has a row for each of [`PERF_GLOBALS`]
/// before any of them is written, and answer with the script that switches
/// the animations off.
///
/// `adb_prepare_desktop` already records all of them at session start, so this
/// is normally a read that changes nothing. It exists for the phone whose
/// snapshot predates this setting — an upgrade over a session that ended
/// badly, whose stored entry still has only the original four keys. Without
/// it, that phone would turn its animations off with nothing on file that
/// knows how to turn them back on.
///
/// Only ever ADDS rows. Overwriting one would record the zeros we are about to
/// write as the values to restore, which is the one way to make an undo a
/// permanent change.
pub fn arm_perf_globals(app: &tauri::AppHandle, serial: &str) -> String {
    let mut map = load_restore_map(app);
    let entry = map.entry(serial.to_string()).or_default();
    if PERF_GLOBALS.iter().any(|k| !entry.contains_key(*k)) {
        let now = read_desktop_globals(app, serial);
        for key in PERF_GLOBALS {
            if entry.contains_key(key) {
                continue;
            }
            let before = now.get(key).cloned().unwrap_or_default();
            log::info!("remembering {key}={before} on {serial} before Reduce quality");
            entry.insert(key.to_string(), before);
        }
        save_restore_map(app, &map);
    }
    perf_globals_script()
}

/// Undo the phone-side half of a desktop session.
///
/// Two adb round trips, not a dozen: the accessibility list has to be read
/// before it can be rewritten, and everything else goes down as one chained
/// shell command. Called from the app-exit path too, where each extra call is
/// another chance to hang on a phone that is already gone.
///
/// This is the path that runs when the PC is still attached. When the cable is
/// pulled instead, none of it can run, and the daemon's dead-man switch does
/// the same work on the device — see `WmDaemon.watchdog`.
pub fn restore_phone(app: &tauri::AppHandle, serial: &str) {
    log::info!("── exiting DeX on {serial}: putting the phone back ──");

    // 0. the phone's own panel, if the taskbar's tile left it dark. Ahead of everything
    //    else because step 4 kills the daemon, the call needs the daemon's authority, and
    //    ART does not run shutdown hooks on SIGTERM — once that process is gone nothing on
    //    the device can undo it, and the user is left pressing power twice at a phone that
    //    looks broken.
    let wm = crate::wm::WmClient::new();
    if wm.restore_screen() {
        log::info!("{serial}: the phone's own screen was off — turned back on");
    }
    // 0b. the media-route pin, for the same reason and under the same
    //     deadline: it is desktop policy set with the daemon's authority, and
    //     step 4 is about to take that authority away. Left in place it would
    //     overrule the phone's own output switcher long after DeX is gone.
    if wm.clear_audio_route() {
        log::info!("{serial}: the media-output pin was cleared");
    }

    let services = run_adb_timeout(
        app,
        &[
            "-s",
            serial,
            "shell",
            "settings get secure enabled_accessibility_services",
        ],
        RESTORE_TIMEOUT,
    )
    .unwrap_or_default();
    let remaining = without_caption_service(&services);

    // 1. the display profile, back to whatever it was before the first launch.
    let mut script = undo_globals_script(app, serial);

    // 2. the caption service. With nothing else in the list the master switch
    //    goes off too, which is the state a phone that never ran DeX is in.
    if remaining.is_empty() {
        script.push_str(
            "settings delete secure enabled_accessibility_services; \
             settings put secure accessibility_enabled 0; ",
        );
    } else {
        script.push_str(&format!(
            "settings put secure enabled_accessibility_services '{remaining}'; "
        ));
    }

    // 2b. notification access, but ONLY when this app is what turned it on.
    //
    //     The launcher runs standalone on the phone, where the user grants this
    //     themselves on the phone's own screen — and that grant is for the
    //     desktop they use without a PC. Revoking it here because a cable was
    //     unplugged would take away something we never gave, every session.
    //     `enable_notification_listener` records which case this is.
    //
    //     `disallow_listener` rather than a rewrite of
    //     `enabled_notification_listeners`, so the user's own listeners (a
    //     watch companion, a car head unit) are untouched by construction and
    //     the undo costs no extra read. On a build with no such verb it prints
    //     a usage line and changes nothing — the same outcome as the grant
    //     never having landed.
    if notification_grant_is_ours(app, serial) {
        script.push_str(&format!(
            "cmd notification disallow_listener {NOTIFICATION_LISTENER_COMPONENT}; "
        ));
    }

    // 3. the app-ops and the widget-bind whitelist we granted over adb
    //    without ever asking the user. Widgets already bound stay bound —
    //    revokebind only closes the door on silent NEW binds, and the next
    //    session's deploy opens it again.
    script.push_str(&format!(
        "appops set {LAUNCHER_PACKAGE} SYSTEM_ALERT_WINDOW default; \
         appops set {LAUNCHER_PACKAGE} ACCESS_RESTRICTED_SETTINGS default; \
         cmd appwidget revokebind --package {LAUNCHER_PACKAGE} --user 0; "
    ));

    // 3b. the battery-optimisation exemption, for the same reason: the user
    //     never asked for it, and unlike the app-ops the doze whitelist
    //     SURVIVES A REBOOT — left behind, it would keep a package the phone
    //     no longer runs permanently exempt. The standby bucket is not reset:
    //     the platform re-evaluates it on its own and there is no "unset".
    for op in BACKGROUND_OPS {
        script.push_str(&format!("appops set {LAUNCHER_PACKAGE} {op} default; "));
    }
    script.push_str(&format!(
        "dumpsys deviceidle whitelist -{LAUNCHER_PACKAGE}; "
    ));

    // 4. the shell-uid window daemon. `[W]mDaemon` and not `WmDaemon`: -f
    //    matches whole command lines, and this script IS a command line
    //    containing that word — the plain spelling makes the shell kill itself
    //    partway through the undo. The bracket never matches itself.
    script.push_str("pkill -f '[W]mDaemon'; ");

    // 4b. any scrcpy server still running ON THE PHONE.
    //
    //     The virtual display belongs to this process, not to the PC one, so
    //     killing scrcpy.exe does not necessarily take the display with it — and a
    //     PC app that was killed rather than closed never even asks. Observed on
    //     SM-S938B: the app had been shut for 19 hours and display 40 was still
    //     there, still hosting a resumed LauncherActivity, which held
    //     `topDisplayFocusedRootTask` for the WHOLE DEVICE. Android sends a
    //     brand-new task to the top-focused display area when the launch does not
    //     pin one, so every app the user opened fresh on the phone went to a
    //     display nothing was rendering any more. Apps already running were fine,
    //     because those are found by task lookup on display 0 first.
    //
    //     Only `.Server` is killed: scrcpy's own `CleanUp` process is what puts
    //     the phone's settings back when the server dies, so it must outlive it.
    //     Bracketed for the same reason as the daemon above.
    script.push_str("pkill -f '[c]om.genymobile.scrcpy.Server'; ");

    // 5. the launcher, and the phone's own home screen in front. Killing the
    //    launcher is what actually releases top-display focus — an empty virtual
    //    display does not hold it, a resumed home activity on one does.
    script.push_str(&format!(
        "am force-stop {LAUNCHER_PACKAGE}; input keyevent KEYCODE_HOME"
    ));

    let undone = match run_adb_timeout(app, &["-s", serial, "shell", &script], RESTORE_TIMEOUT) {
        Ok(_) => {
            log::info!("{serial} is back to a plain phone");
            true
        }
        Err(e) => {
            log::warn!(
                "{serial}: the desktop profile could not be fully undone ({e}) — \
                 the recorded original values are kept for the next attempt"
            );
            false
        }
    };

    // The snapshot is only forgotten once it has actually been applied. An adb
    // that was not there (the phone unplugged, the app exiting) would otherwise
    // lose the original values for good, and the profile would stay on the
    // phone with nothing left that knows how to take it off.
    if undone {
        let mut map = load_restore_map(app);
        if map.remove(serial).is_some() {
            save_restore_map(app, &map);
        }
    }

    let forward = format!("tcp:{}", crate::wm::HOST_PORT);
    let _ = run_adb_timeout(
        app,
        &["-s", serial, "forward", "--remove", &forward],
        RESTORE_TIMEOUT,
    );
}

#[tauri::command(async)]
pub fn adb_reboot(app: tauri::AppHandle, serial: String) -> Result<String, String> {
    log::info!("rebooting {serial} at the user's request");
    run_adb(&app, &["-s", &serial, "reboot"])
}

/// `adb connect <ip[:port]>` — wireless debugging. adb reports failures on
/// stdout with exit code 0, so sniff the message.
#[tauri::command(async)]
pub fn adb_connect(app: tauri::AppHandle, address: String) -> Result<String, String> {
    let addr = address.trim();
    // `_` and `.` are allowed because this also accepts an mDNS service name
    // ("adb-R5CT30…-vWgJpq._adb-tls-connect._tcp"), which adb resolves itself;
    // rejecting the underscore turned every paired phone into "invalid
    // address".
    if addr.is_empty()
        || !addr
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | ':' | '-' | '_'))
    {
        return Err("invalid address".into());
    }
    let out = run_adb(&app, &["connect", addr])?;
    let lower = out.to_lowercase();
    if lower.contains("cannot") || lower.contains("failed") || lower.contains("unable") {
        Err(out)
    } else {
        Ok(out)
    }
}

pub const LAUNCHER_PACKAGE: &str = "com.ccrstech.openandroiddex.launcher";
pub const LAUNCHER_COMPONENT: &str =
    "com.ccrstech.openandroiddex.launcher/.LauncherActivity";

/// True when an `adb install` failure is a signing-key conflict — the device
/// already has this package signed by a *different* key, so the framework
/// refuses the update. No install flag can override this; the old copy has to
/// go first.
fn is_signature_conflict(err: &str) -> bool {
    let e = err.to_uppercase();
    e.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        || e.contains("INCONSISTENT_CERTIFICATES")
        || e.contains("SIGNATURES DO NOT MATCH")
}

/// True when an `adb install` failure is the device refusing to go backwards:
/// the copy on the phone has a higher versionCode than the APK being pushed.
///
/// Reachable only since the launcher's versionCode started tracking the
/// release version (openandroiddex-launcher/app/build.gradle). It was pinned
/// at 2 for every build ever made, so no install could be a downgrade and
/// every one was accepted. Now a developer on an older checkout -- or anyone
/// rolling back to a previous release -- is pushing a lower number at a phone
/// that already has a higher one.
fn is_version_downgrade(err: &str) -> bool {
    err.to_uppercase().contains("INSTALL_FAILED_VERSION_DOWNGRADE")
}

/// `adb install -r -d`, normalising the two ways adb reports a rejected install.
///
/// `-d` is INSTALL_ALLOW_DOWNGRADE. The desktop always deploys the launcher it
/// ships with (see the "always reinstall" note at the call site), so a phone
/// holding a newer launcher than this build has to yield to it -- otherwise
/// running an older release after a newer one fails with nothing but a pm
/// error code to go on. Not every framework honours the flag, which is what
/// is_version_downgrade above is for.
///
/// Modern adb exits non-zero ("adb: failed to install …"), but it also has a
/// long history of printing `Failure [INSTALL_FAILED_…]` on *stdout* with exit
/// code 0. Treating only the exit code as truth makes a failed install look
/// like a success, and the real error then surfaces much later as a missing
/// launcher, so check the text as well.
fn install_apk(app: &tauri::AppHandle, serial: &str, apk: &str) -> Result<(), String> {
    match run_adb_timeout(app, &["-s", serial, "install", "-r", "-d", apk], INSTALL_TIMEOUT) {
        Err(e) => Err(e),
        Ok(out) if out.contains("Failure [") || out.contains("failed to install") => Err(out),
        Ok(_) => Ok(()),
    }
}

/// Install the bundled launcher, recovering from a signing-key conflict by
/// removing the incompatible copy first.
///
/// This is not an edge case, it is the normal upgrade path. The launcher ships
/// as an `assembleDebug` build, so it is signed with whatever
/// `~/.android/debug.keystore` the building machine had — a key the Android
/// Gradle Plugin generates on demand. A CI runner is a fresh machine every
/// time, so *every release carries a different signature*, and so does every
/// developer's local build. Any device that already has the launcher on it
/// therefore rejects the next one with INSTALL_FAILED_UPDATE_INCOMPATIBLE, and
/// the desktop never comes up.
///
/// Uninstalling drops the launcher's own preferences with it. That is the only
/// available trade: Android offers no way to re-sign an installed package, and
/// a desktop that cannot start is worse than one that forgets its settings.
fn install_launcher(app: &tauri::AppHandle, serial: &str, apk: &str) -> Result<(), String> {
    let err = match install_apk(app, serial, apk) {
        Ok(()) => return Ok(()),
        Err(e) => e,
    };
    let reason = if is_signature_conflict(&err) {
        "signed with a different key"
    } else if is_version_downgrade(&err) {
        // `-d` above should already have covered this; a framework that
        // ignores the flag leaves removal as the only way through.
        "newer than the one this build ships"
    } else {
        return Err(err);
    };
    log::warn!(
        "{LAUNCHER_PACKAGE}: installed copy is {reason} — \
         uninstalling it so the bundled launcher can take its place \
         (its settings are lost). Original error: {err}"
    );

    // Plain `adb uninstall` removes the package for every user it is installed
    // for, which is what we want. It fails when the package only exists in a
    // secondary profile the shell user cannot reach implicitly (Samsung's
    // Secure Folder is the common one here), so fall back to naming user 0
    // explicitly before giving up.
    let removed = run_adb(app, &["-s", serial, "uninstall", LAUNCHER_PACKAGE])
        .map(|o| o.contains("Success"))
        .unwrap_or(false)
        || run_adb(
            app,
            &[
                "-s",
                serial,
                "shell",
                &format!("pm uninstall --user 0 {LAUNCHER_PACKAGE}"),
            ],
        )
        .map(|o| o.contains("Success"))
        .unwrap_or(false);

    if !removed {
        return Err(format!(
            "The phone has an incompatible copy of {LAUNCHER_PACKAGE} that could not be \
             removed automatically. Uninstall \"Open Android DeX\" on the phone \
             (including from Secure Folder or any work profile) and reconnect.\n\n{err}"
        ));
    }

    // Retry once. A second signature conflict means something put the package
    // back — a work-profile clone or an app-cloning feature — and looping would
    // not help, so report it with the same guidance.
    install_apk(app, serial, apk).map_err(|e| {
        if is_signature_conflict(&e) {
            format!(
                "{LAUNCHER_PACKAGE} was removed but the install still conflicts — another \
                 profile on the phone likely holds a copy. Uninstall \"Open Android DeX\" \
                 everywhere (work profile, Secure Folder) and reconnect.\n\n{e}"
            )
        } else {
            e
        }
    })
}

/// Install (or update) the bundled Open Android DeX launcher APK and start
/// it on the given display. Skips the install when the same version is
/// already on the device.
#[tauri::command(async)]
pub fn adb_start_launcher(
    app: tauri::AppHandle,
    serial: String,
    display_id: i32,
) -> Result<String, String> {
    log::info!("── deploy launcher on {serial}, display {display_id} ──");
    let deploy_started = Instant::now();
    let apk = bin_dir(&app)?.join("openandroiddex-launcher.apk");
    if !apk.exists() {
        log::error!("launcher APK missing from the bundle: {}", apk.display());
        return Err("bundled openandroiddex-launcher.apk not found".into());
    }
    // always reinstall: it is tiny and this guarantees updates land
    let apk_str = apk.to_string_lossy().to_string();
    log::info!("installing {}", apk.display());
    install_launcher(&app, &serial, &apk_str)?;
    log::info!(
        "launcher installed in {}ms",
        deploy_started.elapsed().as_millis()
    );
    // Overlay-taskbar permission: the taskbar is a TYPE_APPLICATION_OVERLAY
    // window so it floats above app windows (base layer ~111000 vs 21000 for
    // app windows). The op only sticks because the APK *declares*
    // SYSTEM_ALERT_WINDOW — without the manifest entry `appops set` silently
    // stays "default", canDrawOverlays() returns false and the launcher falls
    // back to an in-activity bar that every app window covers. Read it back
    // so that failure is loud instead of silent.
    let _ = run_adb(
        &app,
        &[
            "-s",
            &serial,
            "shell",
            &format!("appops set {LAUNCHER_PACKAGE} SYSTEM_ALERT_WINDOW allow"),
        ],
    );
    let overlay_ok = run_adb(
        &app,
        &[
            "-s",
            &serial,
            "shell",
            &format!("appops get {LAUNCHER_PACKAGE} SYSTEM_ALERT_WINDOW"),
        ],
    )
    .map(|o| o.contains("allow"))
    .unwrap_or(false);
    if overlay_ok {
        log::info!("SYSTEM_ALERT_WINDOW granted — taskbar can float above app windows");
    } else {
        log::warn!(
            "openandroiddex-launcher: SYSTEM_ALERT_WINDOW not granted — the taskbar will \
             fall back to an in-activity bar that app windows can cover"
        );
    }
    // Shared-folder permission, for the Linux feature's /sdcard/LinuxOnDeX.
    //
    // MediaProvider gates TOP-LEVEL names on shared storage, so the launcher
    // cannot even mkdir that folder without this op — measured on SM-S938B:
    // with the op at "default" the directory simply never appeared, and the
    // instant it was set to allow the launcher created it on next start. A
    // subdirectory of Documents/ would have needed nothing, but a folder
    // nobody can find is not a shared folder.
    //
    // Same manifest rule as SYSTEM_ALERT_WINDOW above: the op only sticks
    // because the APK declares the permission, so read it back rather than
    // trusting the set.
    //
    // DELIBERATELY NOT UNDONE in restore_phone, unlike the ops above. Two
    // reasons: revoking this op makes the platform kill the whole app id
    // (StorageManagerService.killAppForOpChange), which on a cable pull would
    // SIGKILL a live container mid-write; and the user can grant the very same
    // op themselves from the launcher's Linux menu, which a session-end reset
    // would silently undo. It is a persistent, user-visible feature, not
    // session state. `appops set ... default` by hand, or Settings > Apps >
    // Open Android DeX > All files access, takes it away.
    let _ = run_adb(
        &app,
        &[
            "-s",
            &serial,
            "shell",
            &format!("appops set {LAUNCHER_PACKAGE} MANAGE_EXTERNAL_STORAGE allow"),
        ],
    );
    let files_ok = run_adb(
        &app,
        &[
            "-s",
            &serial,
            "shell",
            &format!("appops get {LAUNCHER_PACKAGE} MANAGE_EXTERNAL_STORAGE"),
        ],
    )
    .map(|o| o.contains("allow"))
    .unwrap_or(false);
    if files_ok {
        log::info!("MANAGE_EXTERNAL_STORAGE granted — /sdcard/LinuxOnDeX is shared with Linux");
    } else {
        log::warn!(
            "openandroiddex-launcher: MANAGE_EXTERNAL_STORAGE not granted — the LinuxOnDeX              shared folder cannot be created, and Linux will start without it. Grant it from              the desktop: right-click the Linux tile > Allow access to all files"
        );
    }
    // Widget hosting: binding an AppWidget id to a provider is guarded by
    // BIND_APPWIDGET, a signature|privileged permission a sideloaded APK can
    // never hold — but the shell can whitelist a host package, which is the
    // same standing the preinstalled launcher gets at build time. Without
    // this the launcher still works: every "Add widget" just detours through
    // the system's bind-confirmation dialog. Absent on Android < 12 (no
    // `cmd appwidget`), which is exactly that fallback.
    //
    // Read back like the overlay grant above, because the failure is otherwise
    // invisible: `cmd appwidget` prints its usage text (or an error) instead of
    // failing, and the first symptom is a bind-confirmation dialog nobody
    // expected. Empty output is the command's way of saying it worked.
    let bind_grant = run_adb(
        &app,
        &[
            "-s",
            &serial,
            "shell",
            &format!("cmd appwidget grantbind --package {LAUNCHER_PACKAGE} --user 0"),
        ],
    );
    match bind_grant {
        Ok(out) if out.trim().is_empty() => {
            log::info!("widget bind granted — Add widget places widgets without a dialog");
        }
        Ok(out) => log::warn!(
            "widget bind not granted ({}) — every Add widget will detour through the \
             system's bind-confirmation dialog",
            out.trim()
        ),
        Err(e) => log::warn!(
            "widget bind not granted ({e}) — every Add widget will detour through the \
             system's bind-confirmation dialog"
        ),
    }
    exempt_from_power_saving(&app, &serial);
    start_wmd(&app, &serial);

    // fresh task, pinned fullscreen (windowingMode 1): the launcher IS the
    // desktop surface even though the display defaults to freeform. A
    // running instance keeps its old windowing mode, hence the force-stop.
    let started_out = run_adb(
        &app,
        &[
            "-s",
            &serial,
            "shell",
            &format!(
                "am force-stop {LAUNCHER_PACKAGE}; am start --display {display_id} --windowingMode 1 -n {LAUNCHER_COMPONENT}"
            ),
        ],
    )?;

    log::info!("launcher started on display {display_id}: {}", started_out.trim());

    // MUST come after the force-stop above, never before. Force-stopping a
    // package makes the framework prune its services out of
    // `enabled_accessibility_services` — granting first means the grant is
    // wiped again microseconds later, and the appop (which force-stop does NOT
    // clear) is left looking granted, so the state reads as "permission fine,
    // service just isn't running".
    enable_caption_service(&app, &serial);
    // After the force-stop for the same reason as the line above: force-stop
    // prunes the package's services out of `enabled_notification_listeners`
    // too, so granting first would be wiped microseconds later.
    enable_notification_listener(&app, &serial);
    log::info!(
        "── launcher deployed in {}ms ──",
        deploy_started.elapsed().as_millis()
    );

    Ok(started_out)
}

/// The app-ops that decide whether the launcher may do anything while it is not
/// the foreground app. Granted on deploy, put back to `default` on exit.
const BACKGROUND_OPS: [&str; 2] = ["RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND"];

/// Take the launcher out of every power-saving bucket the shell can reach.
///
/// Android will not start a doze-restricted app promptly, and it is worse than
/// slow: an app the platform has decided is "unused" gets its background work
/// deferred to the next maintenance window, so the first tap on a taskbar icon
/// does nothing for a while and then everything happens at once. Samsung is the
/// most aggressive here — One UI's Device Care puts an app to sleep after a few
/// days of not being opened *on the phone*, which is the normal state of a
/// launcher that only ever runs on a desktop display.
///
/// Three separate mechanisms, because they are three separate gates and being
/// out of one does not exempt you from the others:
///
/// * the **Doze whitelist** — the same list "Unrestricted" battery usage puts an
///   app on, and what Samsung's sleeping-apps sweep honours;
/// * the **standby bucket** — `active` is the least throttled, and the bucket is
///   what actually delays a launch;
/// * the **background app-ops** — without these the process may be started and
///   then immediately have its work deferred.
///
/// Best-effort throughout, and deliberately not fatal: `dumpsys deviceidle` is
/// absent or renamed on some vendor builds, and a phone that refuses all three
/// still runs the desktop — just with a slower first launch. That is worth a
/// warning in the log and nothing more.
///
/// Everything here is undone by [`restore_phone`]. This is a permission the user
/// never explicitly granted, so it does not outlive the session.
fn exempt_from_power_saving(app: &tauri::AppHandle, serial: &str) {
    // `+package` adds; the list survives reboots, which is why exit removes it.
    let whitelisted = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            &format!("dumpsys deviceidle whitelist +{LAUNCHER_PACKAGE}"),
        ],
    )
    .is_ok();

    // Read back rather than trust the call: on a device where the command is
    // accepted but the list is policy-controlled, this is the only tell.
    let exempt = run_adb_quiet(
        app,
        &[
            "-s",
            serial,
            "shell",
            &format!("dumpsys deviceidle whitelist | grep -c {LAUNCHER_PACKAGE} || true"),
        ],
    )
    .map(|o| o.trim() != "0" && !o.trim().is_empty())
    .unwrap_or(false);

    // `active` is the least-restricted bucket. The system still re-evaluates
    // buckets on its own schedule, so this is a head start rather than a
    // permanent state — which is also why it needs no undo.
    let _ = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            &format!("am set-standby-bucket {LAUNCHER_PACKAGE} active"),
        ],
    );

    let ops = BACKGROUND_OPS
        .iter()
        .map(|op| format!("cmd appops set {LAUNCHER_PACKAGE} {op} allow"))
        .collect::<Vec<_>>()
        .join("; ");
    let _ = run_adb(app, &["-s", serial, "shell", &ops]);

    if exempt {
        log::info!(
            "{LAUNCHER_PACKAGE} is exempt from battery optimisation — the desktop starts \
             without waiting on a doze maintenance window"
        );
    } else {
        log::warn!(
            "{LAUNCHER_PACKAGE} could not be exempted from battery optimisation \
             (deviceidle whitelist {}) — the first launch after the phone has been idle \
             may be slow. On Samsung, check Settings → Battery → Background usage limits \
             → Sleeping apps",
            if whitelisted { "not applied" } else { "unavailable" }
        );
    }
}

pub const CAPTION_SERVICE_COMPONENT: &str = "com.ccrstech.openandroiddex.launcher/\
     com.ccrstech.openandroiddex.launcher.CaptionService";

/// Re-grant the accessibility service that draws window captions.
///
/// This has to run after *every* install, not once at setup: installing an APK
/// drops the package out of `enabled_accessibility_services` **and** resets its
/// `ACCESS_RESTRICTED_SETTINGS` appop. Since `adb_start_launcher` always
/// reinstalls, captions would otherwise silently disappear on every dev run —
/// the service simply never starts, so there is no error anywhere to find.
///
/// It must also run after the launcher is (re)started — see the call site.
/// `am force-stop` clears the same setting that installing does.
///
/// The appop is the Android 13+ "restricted setting" gate. Without it the
/// enable below is accepted and then quietly dropped.
///
/// Appends rather than overwrites: the user's own services (TalkBack, a
/// password manager) live in the same colon-separated list and clobbering it
/// would turn them off.
fn enable_caption_service(app: &tauri::AppHandle, serial: &str) {
    let _ = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            &format!("appops set {LAUNCHER_PACKAGE} ACCESS_RESTRICTED_SETTINGS allow"),
        ],
    );

    let current = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            "settings get secure enabled_accessibility_services",
        ],
    )
    .unwrap_or_default();
    let current = current.trim();
    // `settings get` prints the literal string "null" for an unset key.
    let current = if current == "null" { "" } else { current };

    let wanted = if current.is_empty() {
        CAPTION_SERVICE_COMPONENT.to_string()
    } else if current
        .split(':')
        .any(|s| s.trim() == CAPTION_SERVICE_COMPONENT)
    {
        current.to_string()
    } else {
        format!("{current}:{CAPTION_SERVICE_COMPONENT}")
    };

    // Clear, then set — even when the value is already correct, and as TWO separate adb
    // invocations rather than one chained shell command.
    //
    // Writing the same value is a no-op that leaves the service running, and a service that
    // was already running cannot draw on this desktop: the caption surface is attached to
    // the display's accessibility overlay, and that attach only works for a display the
    // service already knows about. Every scrcpy session mints a brand new display id, so
    // without a restart the attach silently succeeds and orphans the surface — no parent,
    // never composited, no error anywhere.
    //
    // Chaining the two writes in one shell command does NOT restart it: the pair lands
    // faster than AccessibilityManagerService tears the service down, so it never
    // disconnects. Two round trips give it the gap it needs. Verified by looking for a
    // fresh `onCreate` in the service's own breadcrumb log.
    let _ = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            "settings put secure enabled_accessibility_services ''",
        ],
    );
    let _ = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            &format!(
                "settings put secure enabled_accessibility_services '{wanted}'; \
                 settings put secure accessibility_enabled 1"
            ),
        ],
    );

    // Read back: a value that will not stick is the signature of a device-policy
    // allowlist (an MDM Profile Owner with `setPermittedAccessibilityServices`
    // blocks all third-party services device-wide, work profile or not). That is
    // policy, not a bug, and no amount of retrying fixes it — but it is invisible
    // unless something says so.
    let stuck = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            "settings get secure enabled_accessibility_services",
        ],
    )
    .map(|o| o.contains("CaptionService"))
    .unwrap_or(false);
    if !stuck {
        log::warn!(
            "CaptionService could not be enabled — window captions will not appear. \
             A device-policy allowlist is the usual cause; check \
             `adb shell dumpsys device_policy` for a Profile Owner"
        );
    }
}

pub const NOTIFICATION_LISTENER_COMPONENT: &str = "com.ccrstech.openandroiddex.launcher/\
     com.ccrstech.openandroiddex.launcher.DexNotifications";

/// Let the launcher read the phone's notifications and its media sessions.
///
/// A CONVENIENCE, not a dependency, and the difference matters: the launcher
/// runs standalone on the phone with no PC anywhere, and both surfaces this
/// grant feeds — the taskbar's notification flyout and the quick-settings media
/// card — say so and offer the phone's own notification-access screen when it
/// is missing. All this does is save that trip on a cabled session. Nothing
/// here is retried, escalated, or reported as a failure to the user.
///
/// Like the caption service it has to run after *every* install and after the
/// force-stop: both wipe the package out of `enabled_notification_listeners`.
/// It relies on the `ACCESS_RESTRICTED_SETTINGS` appop that
/// [`enable_caption_service`] sets just before — Android 13+ gates notification
/// access for a sideloaded package behind the same "restricted setting", and
/// without it the write below is accepted and then quietly dropped.
///
/// `cmd notification allow_listener` first because it is the platform's own
/// entry point and does the list merge itself; the `settings put` is the
/// fallback for builds whose notification shell command does not carry that
/// verb. Both append rather than overwrite — the user's own listeners (a
/// smartwatch companion, a car head unit) live in the same list.
fn enable_notification_listener(app: &tauri::AppHandle, serial: &str) {
    let current = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            "settings get secure enabled_notification_listeners",
        ],
    )
    .unwrap_or_default();

    // Already on before we touched it: the user granted this themselves on the
    // phone, for the standalone desktop, and it is not ours to take away when
    // the cable comes out. Recorded so the exit path knows to leave it —
    // without this, plugging in once and unplugging would revoke a grant we
    // never gave, every time.
    if current.contains("DexNotifications") {
        remember_notification_grant(app, serial, false);
        log::info!("notification access was already granted on {serial} — leaving it alone");
        return;
    }

    let allowed = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            &format!("cmd notification allow_listener {NOTIFICATION_LISTENER_COMPONENT}"),
        ],
    )
    .map(|o| {
        let o = o.trim();
        // The command prints nothing on success and a usage dump naming the
        // unknown command on a build that does not carry that verb.
        o.is_empty() || !o.to_ascii_lowercase().contains("unknown")
    })
    .unwrap_or(false);

    if !allowed {
        let wanted = with_component(&current, NOTIFICATION_LISTENER_COMPONENT);
        let _ = run_adb(
            app,
            &[
                "-s",
                serial,
                "shell",
                &format!("settings put secure enabled_notification_listeners '{wanted}'"),
            ],
        );
    }

    // Read back and say so once. A value that will not stick is a device-policy
    // allowlist or an OEM that gates this behind its own screen — policy, not a
    // bug, and the phone-side surfaces already handle it. Worth one line so
    // "the bell shows nothing" has an explanation in the log.
    let stuck = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            "settings get secure enabled_notification_listeners",
        ],
    )
    .map(|o| o.contains("DexNotifications"))
    .unwrap_or(false);
    remember_notification_grant(app, serial, stuck);
    if stuck {
        log::info!("notification access granted — the desktop can show the phone's notifications");
    } else {
        log::info!(
            "notification access could not be granted over adb — the taskbar's bell will \
             offer the phone's own screen for it instead"
        );
    }
}

/// Key in the per-serial restore map: "1" when THIS app turned notification
/// access on, so the exit path knows whether it has anything to undo.
const NOTIFICATION_GRANT_KEY: &str = "notification_listener_granted_by_us";

fn remember_notification_grant(app: &tauri::AppHandle, serial: &str, ours: bool) {
    let mut map = load_restore_map(app);
    map.entry(serial.to_string()).or_default().insert(
        NOTIFICATION_GRANT_KEY.to_string(),
        if ours { "1" } else { "0" }.to_string(),
    );
    save_restore_map(app, &map);
}

fn notification_grant_is_ours(app: &tauri::AppHandle, serial: &str) -> bool {
    load_restore_map(app)
        .get(serial)
        .and_then(|saved| saved.get(NOTIFICATION_GRANT_KEY))
        .is_some_and(|v| v == "1")
}

/// Bring the daemon's loopback port to the PC so the host can drive windows
/// directly. Without it only the device-side launcher can reach the daemon, and
/// every host->device window command has to ride the `content query`
/// ContentProvider queue, which boots a JVM per invocation (~1 s per round).
///
/// Also called from the enforcer when the daemon stops answering: forwards do
/// not survive the device dropping off adb, so after a cable is pulled and put
/// back this is what re-opens the channel — including the one the watchdog is
/// armed over, which would otherwise stay silent and have the daemon conclude
/// the PC had gone for good.
///
/// Idempotent: adb replaces an existing forward for the same local port.
pub fn forward_wm_port(app: &tauri::AppHandle, serial: &str) -> bool {
    let host = format!("tcp:{}", crate::wm::HOST_PORT);
    let device = format!("tcp:{}", crate::wm::DEVICE_PORT);
    match run_adb_quiet(app, &["-s", serial, "forward", &host, &device]) {
        Ok(_) => true,
        Err(e) => {
            log::warn!("adb forward for wmd failed: {e} — host-side window control unavailable");
            false
        }
    }
}

/// Push and (re)start the shell-uid daemon.
///
/// It must run at uid 2000: MANAGE_ACTIVITY_TASKS is what lets anything move,
/// re-order or inset another app's task, and the launcher can never hold it.
/// `setsid` detaches it so it outlives the adb shell that launched it — a plain
/// background job dies with the connection.
///
/// Best-effort: the desktop is fully usable without it, minus window chrome and
/// the fast command path.
fn start_wmd(app: &tauri::AppHandle, serial: &str) {
    let dex = match bin_dir(app) {
        Ok(d) => d.join("openandroiddex-wmd.dex"),
        Err(_) => return,
    };
    if !dex.exists() {
        log::warn!("openandroiddex-wmd.dex not bundled — window chrome disabled");
        return;
    }
    let dex_str = dex.to_string_lossy().to_string();
    if let Err(e) = run_adb(
        app,
        &["-s", serial, "push", &dex_str, "/data/local/tmp/wmd.dex"],
    ) {
        log::warn!("wmd push failed: {e}");
        return;
    }
    // One daemon at a time: a second bind on 7191 would just throw.
    let _ = run_adb(app, &["-s", serial, "shell", "pkill -f WmDaemon"]);
    let _ = run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            "setsid sh -c 'CLASSPATH=/data/local/tmp/wmd.dex exec app_process /system/bin \
             com.ccrstech.openandroiddex.wmd.WmDaemon > /data/local/tmp/wmd.log 2>&1' &",
        ],
    );

    if !forward_wm_port(app, serial) {
        return;
    }
    // The daemon writes its own startup errors to that log and nothing reads
    // it, so a daemon that died on launch looked exactly like one that never
    // ran: no chrome, no explanation.
    match run_adb(app, &["-s", serial, "shell", "tail -5 /data/local/tmp/wmd.log"]) {
        Ok(tail) if !tail.trim().is_empty() => log::info!("wmd log: {}", tail.trim()),
        Ok(_) => log::info!("wmd started (no output yet)"),
        Err(e) => log::warn!("wmd log unreadable: {e}"),
    }
}

/// Open an app on a specific (virtual) display — how apps land on the
/// virtual desktop. `freeform` launches it as a movable, resizable window
/// (WINDOWING_MODE_FREEFORM = 5). Plain `am start`, nothing installed.
#[tauri::command(async)]
pub fn adb_launch_on_display(
    app: tauri::AppHandle,
    serial: String,
    component: String,
    display_id: i32,
    freeform: bool,
) -> Result<String, String> {
    // components come from our own query-activities parse; keep shell-safe
    if !component
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '/' | '_' | '$'))
    {
        return Err(format!("invalid component: {component}"));
    }
    let mode = if freeform { " --windowingMode 5" } else { "" };
    run_adb(
        &app,
        &[
            "-s",
            &serial,
            "shell",
            &format!("am start --display {display_id}{mode} -n {component}"),
        ],
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Verbatim from a user report on the 0.3.0 portable build — the string
    /// this detector exists for.
    const REAL_FAILURE: &str = "adb.exe: failed to install \
        \\\\?\\C:\\Users\\x\\Downloads\\Open.Android.DeX_0.3.0_x64_portable\\resources\\bin\\openandroiddex-launcher.apk: \
        Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package \
        com.ccrstech.openandroiddex.launcher signatures do not match newer version; ignoring!]";

    #[test]
    fn detects_signature_conflicts() {
        assert!(is_signature_conflict(REAL_FAILURE));
        assert!(is_signature_conflict(
            "Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES]"
        ));
        // Some framework builds phrase it without any INSTALL_ code.
        assert!(is_signature_conflict("signatures do not match"));
    }

    /// The launcher's versionCode tracks the release version now, so this is
    /// a real failure mode rather than a theoretical one: an older desktop
    /// build pushing its bundled launcher at a phone a newer release already
    /// updated. Same recovery as a key mismatch, different cause and a
    /// different message, so the two detectors must not overlap.
    #[test]
    fn detects_version_downgrades() {
        let real = "adb: failed to install openandroiddex-launcher.apk: Failure [INSTALL_FAILED_VERSION_DOWNGRADE]";
        assert!(is_version_downgrade(real));
        assert!(!is_signature_conflict(real));
        assert!(!is_version_downgrade(REAL_FAILURE));
        for other in [
            "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]",
            "Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES]",
            "adb: device offline",
        ] {
            assert!(!is_version_downgrade(other), "not a downgrade: {other}");
        }
    }

    /// A false positive here uninstalls the launcher and takes its settings
    /// with it, so everything that is *not* a key mismatch must fall through
    /// to a plain error — including the two failures that merely sound alike.
    #[test]
    fn leaves_other_failures_alone() {
        for other in [
            "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]",
            "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]",
            // Unsigned APK — a build problem, not an installed-copy problem.
            "Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES]",
            "Failure [INSTALL_FAILED_USER_RESTRICTED]",
            "adb: device offline",
            "error: no devices/emulators found",
        ] {
            assert!(!is_signature_conflict(other), "must not uninstall for: {other}");
        }
    }

    /// Exiting DeX must take OUR service out of the accessibility list and
    /// nothing else. The list is shared with whatever the user runs — turning
    /// TalkBack off on the way out would be the worst bug in this file.
    #[test]
    fn exit_removes_only_our_accessibility_service() {
        let theirs = "com.google.android.marvin.talkback/.TalkBackService";
        assert_eq!(
            without_caption_service(&format!("{theirs}:{CAPTION_SERVICE_COMPONENT}")),
            theirs
        );
        assert_eq!(
            without_caption_service(&format!("{CAPTION_SERVICE_COMPONENT}:{theirs}")),
            theirs
        );
        assert_eq!(without_caption_service(CAPTION_SERVICE_COMPONENT), "");
        // `settings get` on an unset key, and on a device that never had one
        assert_eq!(without_caption_service("null"), "");
        assert_eq!(without_caption_service("  "), "");
        assert_eq!(without_caption_service(theirs), theirs);
    }

    /// Same rule for the notification-listener list, which is shared with the
    /// user's watch companion, their car head unit and anything else that reads
    /// their notifications. The fallback path writes this list back verbatim.
    #[test]
    fn granting_notification_access_keeps_the_user_s_own_listeners() {
        let theirs = "com.google.android.wearable.app/\
                      com.google.android.clockwork.companion.NotificationListener";
        assert_eq!(
            with_component(theirs, NOTIFICATION_LISTENER_COMPONENT),
            format!("{theirs}:{NOTIFICATION_LISTENER_COMPONENT}")
        );
        // Already there: the list must come back byte for byte, not doubled.
        let both = format!("{theirs}:{NOTIFICATION_LISTENER_COMPONENT}");
        assert_eq!(with_component(&both, NOTIFICATION_LISTENER_COMPONENT), both);
        // `settings get` on an unset key, and on a phone that never had one.
        assert_eq!(
            with_component("null", NOTIFICATION_LISTENER_COMPONENT),
            NOTIFICATION_LISTENER_COMPONENT
        );
        assert_eq!(
            with_component("  ", NOTIFICATION_LISTENER_COMPONENT),
            NOTIFICATION_LISTENER_COMPONENT
        );
        // …and the way back out leaves theirs standing.
        assert_eq!(
            without_component(&both, NOTIFICATION_LISTENER_COMPONENT),
            theirs
        );
    }

    /// The restore writes these values straight into `settings put global`, so
    /// anything that is not a value we could have written is refused — the key
    /// is then deleted instead, which is the state an untouched phone is in.
    #[test]
    fn only_plain_setting_values_are_written_back() {
        assert_eq!(restorable("1"), Some("1"));
        assert_eq!(restorable(" 0 "), Some("0"));
        assert_eq!(restorable("null"), None);
        assert_eq!(restorable(""), None);
        assert_eq!(restorable("1; reboot"), None);
        assert_eq!(restorable("$(id)"), None);
    }
}
