//! Tracing: the log file, the device-side log stream, and the diagnostics dump.
//!
//! Everything the desktop needs in order to come up happens outside this
//! process — in adb, in scrcpy, in `system_server`, in our launcher — so when
//! a phone stops at "Creating virtual display…" there is nothing to look at
//! unless every one of those steps was written down as it happened. That is
//! what this module is for.
//!
//! Three sources, one file:
//!
//! * **host** — `log::…` from the Rust side: every adb invocation with its
//!   timing and output, scrcpy's command line and every line it prints,
//!   session state changes (`adb.rs`, `scrcpy.rs`).
//! * **webview** — the launch pipeline's own steps, logged from the frontend
//!   through `@tauri-apps/plugin-log`, so the UI's view of a run and the
//!   backend's view sit in the same timeline.
//! * **device** — `logcat` for scrcpy's server, our launcher and Java crashes,
//!   streamed in for as long as a session lives (see [`stream_device_log`]).
//!
//! The device stream is the one that is easy to underestimate. scrcpy's server
//! mirrors every line it prints to `logcat` under the `scrcpy` tag, so the
//! reason a display could not be created survives even when the process dies
//! before the PC drains its pipes — which is exactly the case that used to
//! leave us with no evidence at all.

use std::io::{BufRead, BufReader, Read};
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use tauri::{AppHandle, Manager, Runtime};
use tauri_plugin_log::{
    Builder as LogBuilder, FileOpenStrategy, RotationStrategy, Target, TargetKind, TimezoneStrategy,
};

use crate::adb;

/// Base name of the log: `<app log dir>/open-android-dex.log`, with the
/// previous runs kept alongside it as `open-android-dex_<date>.log`.
pub const LOG_FILE_STEM: &str = "open-android-dex";

/// Runs to keep. A user who notices a problem and reports it a few launches
/// later should still have the run that failed.
const KEEP_RUNS: usize = 10;

/// Per-run ceiling. The plugin's default is 40 KB, which a single desktop
/// session overflows in minutes — and with the default `KeepOne` strategy the
/// overflow deletes the evidence rather than rotating it.
const MAX_FILE_SIZE: u128 = 8 * 1024 * 1024;

const STAMP: &[time::format_description::FormatItem<'static>] = time::macros::format_description!(
    "[year]-[month]-[day] [hour]:[minute]:[second].[subsecond digits:3]"
);

/// The logger, configured for tracing a launch rather than for tidiness:
/// debug level, local timestamps to the millisecond, one file per run.
pub fn log_plugin<R: Runtime>() -> tauri::plugin::TauriPlugin<R> {
    // OADX_LOG=trace turns on the hot paths (per-tick window enforcement);
    // anything else is a plain level name.
    let level = match std::env::var("OADX_LOG")
        .unwrap_or_default()
        .to_ascii_lowercase()
        .as_str()
    {
        "trace" => log::LevelFilter::Trace,
        "info" => log::LevelFilter::Info,
        "warn" => log::LevelFilter::Warn,
        "error" => log::LevelFilter::Error,
        _ => log::LevelFilter::Debug,
    };

    LogBuilder::new()
        .clear_targets()
        .target(Target::new(TargetKind::Stdout))
        .target(Target::new(TargetKind::LogDir {
            file_name: Some(LOG_FILE_STEM.into()),
        }))
        .target(Target::new(TargetKind::Webview))
        .level(level)
        // the windowing/webview stack logs a lot of debug noise that has
        // nothing to do with a phone failing to connect
        .level_for("tao", log::LevelFilter::Warn)
        .level_for("wry", log::LevelFilter::Warn)
        .level_for("hyper", log::LevelFilter::Warn)
        .file_open_strategy(FileOpenStrategy::Rotate)
        .rotation_strategy(RotationStrategy::KeepSome(KEEP_RUNS))
        .max_file_size(MAX_FILE_SIZE)
        .timezone_strategy(TimezoneStrategy::UseLocal)
        .format(|out, message, record| {
            out.finish(format_args!(
                "[{}][{:<5}][{}] {}",
                TimezoneStrategy::UseLocal
                    .get_now()
                    .format(STAMP)
                    .unwrap_or_default(),
                record.level(),
                short_target(record.target()),
                message
            ))
        })
        .build()
}

/// `open_android_dex_tauri_lib::scrcpy` → `scrcpy`, and every frontend record
/// → `ui` (the plugin tags those with the calling source location, which in a
/// dev build is a whole `http://localhost:1420/…` URL).
fn short_target(target: &str) -> &str {
    if target.starts_with(tauri_plugin_log::WEBVIEW_TARGET) {
        return "ui";
    }
    target.rsplit("::").next().unwrap_or(target)
}

pub fn log_dir(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_log_dir().ok()
}

pub fn log_file(app: &AppHandle) -> Option<PathBuf> {
    log_dir(app).map(|d| d.join(format!("{LOG_FILE_STEM}.log")))
}

fn now_stamp() -> String {
    TimezoneStrategy::UseLocal
        .get_now()
        .format(time::macros::format_description!(
            "[year]-[month]-[day]_[hour]-[minute]-[second]"
        ))
        .unwrap_or_else(|_| "unknown".into())
}

/// Header for every run: what this build is, where it lives, and what it is
/// about to drive the phone with. A log that starts mid-session cannot answer
/// "was the launcher APK even bundled?" — this can.
pub fn log_startup(app: &AppHandle) {
    let pkg = app.package_info();
    log::info!(
        "════ Open Android DeX {} starting ({} {}) ════",
        pkg.version,
        std::env::consts::OS,
        std::env::consts::ARCH
    );
    log::info!(
        "exe: {}",
        std::env::current_exe()
            .map(|p| p.display().to_string())
            .unwrap_or_else(|e| format!("<unknown: {e}>"))
    );
    match log_file(app) {
        Some(p) => log::info!("log file: {}", p.display()),
        None => log::warn!("no app log dir — logs are stdout only"),
    }

    // Bundled payloads: a release built with a skipped step ships without the
    // launcher APK or the wmd dex, and the symptom of that shows up minutes
    // later as "the desktop is empty".
    match adb::bin_dir(app) {
        Ok(dir) => {
            log::info!("resources: {}", dir.display());
            for name in bundled_payloads() {
                let path = dir.join(&name);
                match std::fs::metadata(&path) {
                    Ok(m) => log::info!("  {name}: {} bytes", m.len()),
                    Err(e) => log::error!("  {name}: MISSING ({e})"),
                }
            }
            // Before anything tries to run them: a binary that arrived
            // without its executable bit fails at the first adb call with a
            // permission error that names no cause.
            for stem in ["adb", "scrcpy"] {
                adb::warn_if_not_executable(&dir.join(adb::exe_name(stem)));
            }
        }
        Err(e) => log::error!("cannot resolve the resource dir: {e}"),
    }

    // Versions cost a process spawn each, so they must not sit in front of the
    // window appearing.
    let app = app.clone();
    thread::spawn(move || {
        match adb::run_adb(&app, &["version"]) {
            Ok(v) => log::info!("adb: {}", v.lines().next().unwrap_or("?")),
            Err(e) => log::error!("adb unusable: {e}"),
        }
        match scrcpy_version(&app) {
            Ok(v) => log::info!("scrcpy: {v}"),
            Err(e) => log::error!("scrcpy unusable: {e}"),
        }
    });
}

/// Everything `bundle.resources` is supposed to put in `resources/bin`, named
/// as this platform ships it. A release built with a skipped step is missing
/// one of these, and the symptom shows up minutes later as "the desktop is
/// empty".
fn bundled_payloads() -> [String; 5] {
    [
        adb::exe_name("adb"),
        adb::exe_name("scrcpy"),
        // Not a host binary — the dex scrcpy pushes to the phone. Same name
        // in every release.
        "scrcpy-server".to_string(),
        "openandroiddex-launcher.apk".to_string(),
        "openandroiddex-wmd.dex".to_string(),
    ]
}

fn scrcpy_version(app: &AppHandle) -> Result<String, String> {
    let exe = adb::bin_dir(app)?.join(adb::exe_name("scrcpy"));
    let mut cmd = if exe.exists() {
        std::process::Command::new(&exe)
    } else {
        std::process::Command::new(adb::exe_name("scrcpy"))
    };
    cmd.arg("--version");
    adb::hide_console(&mut cmd);
    let out = cmd.output().map_err(|e| e.to_string())?;
    let text = String::from_utf8_lossy(&out.stdout);
    Ok(text.lines().next().unwrap_or("?").trim().to_string())
}

/// Once a phone is known to be there, write down what it is. Every
/// device-shaped bug report starts with these six lines.
pub fn log_device(app: &AppHandle, serial: &str) {
    let app = app.clone();
    let serial = serial.to_string();
    thread::spawn(move || {
        let props = adb::run_adb_quiet(
            &app,
            &[
                "-s",
                &serial,
                "shell",
                "getprop ro.product.brand; getprop ro.product.model; \
                 getprop ro.build.version.release; getprop ro.build.version.sdk; \
                 getprop ro.build.version.security_patch; getprop ro.product.cpu.abi",
            ],
        )
        .unwrap_or_default();
        let f: Vec<&str> = props.lines().map(str::trim).collect();
        log::info!(
            "device [{serial}]: {} {} — Android {} (sdk {}, patch {}, {})",
            f.first().unwrap_or(&"?"),
            f.get(1).unwrap_or(&"?"),
            f.get(2).unwrap_or(&"?"),
            f.get(3).unwrap_or(&"?"),
            f.get(4).unwrap_or(&"?"),
            f.get(5).unwrap_or(&"?"),
        );
    });
}

// ── Device-side log stream ─────────────────────────────────────────────

/// Filter spec for the session log stream: scrcpy's server (which mirrors
/// every line it prints to logcat, including the reason a display could not be
/// created), our launcher and its caption service, plus any Java crash.
///
/// `ActivityTaskManager:I` is the one tag here that is not ours, and it earns its noise:
/// it prints a `START u0 {…cmp=pkg/act} from uid N on display D` line for EVERY activity
/// launch on the phone, whoever issued it. That is the only way to tell a window that
/// moved because we asked from one the phone's own launcher, a notification or an app's
/// own deep link took — which is the difference between a bug in this app and the
/// platform reusing a task across displays.
const LOGCAT_SPEC: &[&str] = &[
    "scrcpy:V",
    "OpenDeX:V",
    "DexCaption:V",
    "ActivityTaskManager:I",
    "AndroidRuntime:E",
    "*:S",
];

/// Stream the phone's log for this session into ours until `stop` flips.
///
/// The commercial app solves the same problem by patching its scrcpy fork to
/// report `display_created` / `display_error` events back over a socket. We
/// ship stock scrcpy, so the equivalent channel is its logcat output: the
/// server logs there as well as to the pipe, and logcat survives the process
/// dying with a pipe still buffered.
pub fn stream_device_log(app: &AppHandle, serial: &str, key: &str, stop: Arc<AtomicBool>) {
    let mut cmd = adb::adb_command(app);
    // -T 1: start at the end of the buffer, not at the beginning of boot
    cmd.args(["-s", serial, "logcat", "-v", "time", "-T", "1"]);
    cmd.args(LOGCAT_SPEC);
    cmd.stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            log::warn!("device log [{key}] could not start logcat: {e}");
            return;
        }
    };
    let Some(out) = child.stdout.take() else {
        return;
    };
    log::info!("device log [{key}] streaming ({})", LOGCAT_SPEC.join(" "));

    let child = Arc::new(Mutex::new(child));
    {
        // logcat blocks on the phone, so the only way it ends is being killed
        let child = child.clone();
        let key = key.to_string();
        thread::spawn(move || {
            while !stop.load(Ordering::SeqCst) {
                thread::sleep(Duration::from_millis(250));
            }
            let _ = child.lock().unwrap().kill();
            log::debug!("device log [{key}] stopped");
        });
    }
    let key = key.to_string();
    thread::spawn(move || {
        for line in BufReader::new(out).lines().map_while(Result::ok) {
            let line = line.trim_end();
            if line.is_empty() || line.starts_with("--------- beginning") {
                continue;
            }
            // logcat's own level letter (E/W) decides ours, so a phone-side
            // error is as visible in the file as a host-side one
            if line.contains(" E/") || line.contains(" F/") {
                log::warn!("device [{key}] {line}");
            } else {
                log::info!("device [{key}] {line}");
            }
        }
        let _ = child.lock().unwrap().wait();
    });
}

// ── Diagnostics dump ───────────────────────────────────────────────────

/// Everything about the phone that decides whether a desktop can come up, in
/// one file the user can attach to a report.
const DEVICE_PROBES: &[(&str, &str)] = &[
    (
        "build",
        "getprop ro.product.brand; getprop ro.product.model; getprop ro.product.device; \
         getprop ro.build.version.release; getprop ro.build.version.sdk; \
         getprop ro.build.version.security_patch; getprop ro.build.characteristics",
    ),
    ("screen", "wm size; wm density"),
    (
        "desktop settings",
        "for k in force_desktop_mode_on_external_displays enable_freeform_support \
         force_resizable_activities hidden_api_policy; do echo \"$k=$(settings get global $k)\"; done",
    ),
    (
        "freeform support",
        "pm list features | grep -i -E 'freeform|multiwindow|display'",
    ),
    (
        "displays",
        "dumpsys display | grep -E 'mDisplayId=|DisplayInfo\\{|mUniqueId=' | head -40",
    ),
    (
        // Which display each task is actually on, which is the whole of the
        // phone-vs-desktop ownership question. Task lines only: the geometry the enforcer
        // also parses would drown the interesting part.
        "tasks per display",
        "dumpsys activity activities | grep -E 'Display #|\\* Task\\{' | head -80",
    ),
    (
        "launcher package",
        "dumpsys package com.ccrstech.openandroiddex.launcher | \
         grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime|signatures' | head -10",
    ),
    (
        // MANAGE_EXTERNAL_STORAGE is READ here and never set: the user grants it
        // on the phone, and revoking it SIGKILLs this uid - which would take a
        // live Linux container and the desktop shell down mid-session. Without
        // it the shared folder is outbound-only (the guest cannot open a file
        // Android put there), so "the files I copy in never show up in Linux"
        // is answered by this line plus the listing beside it.
        "launcher permissions",
        "appops get com.ccrstech.openandroiddex.launcher SYSTEM_ALERT_WINDOW; \
         appops get com.ccrstech.openandroiddex.launcher ACCESS_RESTRICTED_SETTINGS; \
         appops get com.ccrstech.openandroiddex.launcher MANAGE_EXTERNAL_STORAGE; \
         ls -la /sdcard/LinuxOnDeX 2>&1 | head -20; \
         settings get secure enabled_accessibility_services",
    ),
    (
        "wmd daemon",
        "ps -A -o USER,PID,NAME | grep -i -E 'app_process|WmDaemon' | head -10; \
         tail -40 /data/local/tmp/wmd.log",
    ),
    (
        "device policy",
        "dumpsys device_policy | grep -E 'Profile Owner|Device Owner' | head -10",
    ),
    (
        "recent logcat",
        "logcat -d -t 300 -v time scrcpy:V OpenDeX:V DexCaption:V ActivityTaskManager:I \
         AndroidRuntime:E '*:S'",
    ),
];

/// Collect host + device state into a text file next to the log and return its
/// path. Best effort throughout: a probe that fails is recorded as failing,
/// because "this command is not available on this phone" is itself a finding.
#[tauri::command(async)]
pub fn diag_collect(app: AppHandle, serial: Option<String>) -> Result<String, String> {
    let mut report = String::new();
    report.push_str(&format!(
        "Open Android DeX diagnostics — {}\nversion {}\nos: {} {}\n",
        now_stamp(),
        app.package_info().version,
        std::env::consts::OS,
        std::env::consts::ARCH
    ));

    report.push_str("\n──── host ────\n");
    match adb::bin_dir(&app) {
        Ok(dir) => {
            report.push_str(&format!("resources: {}\n", dir.display()));
            for name in bundled_payloads() {
                match std::fs::metadata(dir.join(&name)) {
                    Ok(m) => report.push_str(&format!("  {name}: {} bytes\n", m.len())),
                    Err(e) => report.push_str(&format!("  {name}: MISSING ({e})\n")),
                }
            }
        }
        Err(e) => report.push_str(&format!("resource dir unavailable: {e}\n")),
    }
    report.push_str(&format!(
        "adb: {}\n",
        adb::run_adb(&app, &["version"]).unwrap_or_else(|e| format!("<{e}>"))
    ));
    report.push_str(&format!(
        "scrcpy: {}\n",
        scrcpy_version(&app).unwrap_or_else(|e| format!("<{e}>"))
    ));
    report.push_str(&format!(
        "devices:\n{}\n",
        adb::run_adb(&app, &["devices", "-l"]).unwrap_or_else(|e| format!("<{e}>"))
    ));

    // Wireless is the half of this app with no visible failure of its own: when
    // discovery finds nothing, the panel says "no phones waiting", which looks
    // identical whether the phone is not advertising, the network drops
    // multicast, or macOS has denied this app the Local Network permission.
    // These three lines separate those.
    //
    // `mdns check` names the backend, and there are two with different
    // characters: Openscreen is adb's own implementation and does its own
    // multicast, Bonjour hands it to the system daemon. Which one answered is
    // the first thing worth knowing.
    report.push_str(&format!(
        "\nmdns check: {}\n",
        adb::run_adb(&app, &["mdns", "check"]).unwrap_or_else(|e| format!("<{e}>"))
    ));
    report.push_str(&format!(
        "mdns services:\n{}\n",
        adb::run_adb(&app, &["mdns", "services"]).unwrap_or_else(|e| format!("<{e}>"))
    ));
    // Who owns the adb server matters on macOS specifically. It is a shared
    // daemon on port 5037 that outlives whoever launched it, and the Local
    // Network permission belongs to the process that started it — so an adb
    // server left behind by a terminal makes discovery work here for reasons
    // that have nothing to do with this app, and vice versa.
    #[cfg(unix)]
    report.push_str(&format!(
        "adb server processes:\n{}\n",
        std::process::Command::new("ps")
            .args(["-Ao", "pid,ppid,command"])
            .output()
            .map(|o| {
                String::from_utf8_lossy(&o.stdout)
                    .lines()
                    .filter(|l| l.contains("adb") && l.contains("fork-server"))
                    .collect::<Vec<_>>()
                    .join("\n")
            })
            .unwrap_or_else(|e| format!("<{e}>"))
    ));

    let serial = serial
        .filter(|s| !s.trim().is_empty())
        .or_else(|| first_online_device(&app));
    match serial {
        Some(serial) => {
            report.push_str(&format!("\n──── device {serial} ────\n"));
            for (label, probe) in DEVICE_PROBES {
                let out = adb::run_adb(&app, &["-s", &serial, "shell", probe])
                    .unwrap_or_else(|e| format!("<failed: {e}>"));
                report.push_str(&format!("\n── {label} ──\n{}\n", out.trim()));
            }
        }
        None => report.push_str("\nno online device to probe\n"),
    }

    report.push_str(&format!(
        "\n──── live scrcpy sessions ────\n{}\n",
        crate::scrcpy::live_session_output(&app)
    ));

    // The tail of the run's own log, so one attachment is enough.
    if let Some(path) = log_file(&app) {
        report.push_str(&format!("\n──── log tail ({}) ────\n", path.display()));
        report.push_str(&tail_of(&path, 400));
    }

    let dir = log_dir(&app).ok_or("no log directory")?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let out = dir.join(format!("diagnostics_{}.txt", now_stamp()));
    std::fs::write(&out, report).map_err(|e| e.to_string())?;
    log::info!("diagnostics written to {}", out.display());
    Ok(out.to_string_lossy().to_string())
}

fn first_online_device(app: &AppHandle) -> Option<String> {
    let raw = adb::run_adb(app, &["devices"]).ok()?;
    raw.lines()
        .skip(1)
        .filter_map(|l| {
            let mut parts = l.split_whitespace();
            let serial = parts.next()?;
            (parts.next()? == "device").then(|| serial.to_string())
        })
        .next()
}

/// Last `lines` lines of a (possibly large) text file.
fn tail_of(path: &PathBuf, lines: usize) -> String {
    let Ok(mut file) = std::fs::File::open(path) else {
        return "<unreadable>".into();
    };
    // ~2 MB is far more than `lines` lines and cheap enough to read whole
    const MAX: u64 = 2 * 1024 * 1024;
    let len = file.metadata().map(|m| m.len()).unwrap_or(0);
    if len > MAX {
        use std::io::Seek;
        let _ = file.seek(std::io::SeekFrom::Start(len - MAX));
    }
    let mut buf = Vec::new();
    let _ = file.read_to_end(&mut buf);
    let text = String::from_utf8_lossy(&buf);
    let all: Vec<&str> = text.lines().collect();
    all[all.len().saturating_sub(lines)..].join("\n")
}

/// Path of the current run's log file — shown in the UI so a user can find it
/// without being told where Windows keeps app data.
#[tauri::command]
pub fn diag_log_path(app: AppHandle) -> Result<String, String> {
    log_file(&app)
        .map(|p| p.to_string_lossy().to_string())
        .ok_or_else(|| "no log directory".into())
}

/// Open the log folder with the current run's file selected.
#[tauri::command]
pub fn diag_reveal(app: AppHandle, path: Option<String>) -> Result<(), String> {
    let target = match path {
        Some(p) if !p.trim().is_empty() => PathBuf::from(p),
        _ => log_file(&app).ok_or("no log directory")?,
    };
    if let Some(dir) = target.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    tauri_plugin_opener::reveal_item_in_dir(&target).map_err(|e| e.to_string())
}
