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

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read};
use std::path::PathBuf;
use std::process::{Child, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

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
///
/// For the life of the session, not of one `logcat`. A pulled cable or a Wi‑Fi
/// drop ends the process while the session carries on through its reconnect,
/// and the stream used to end with it: everything the launcher did after the
/// first reconnect — the stretch most worth reading — never reached the file.
/// So it is picked up again whenever the phone answers, from the time of the
/// last line it gave (`-T`), which brings in what the phone logged while it was
/// away as well.
pub fn stream_device_log(app: &AppHandle, serial: &str, key: &str, stop: Arc<AtomicBool>) {
    // The logcat running now, for the stopper to kill; None between runs.
    let current: Arc<Mutex<Option<Child>>> = Arc::new(Mutex::new(None));
    {
        // logcat blocks on the phone, so the only way it ends is being killed
        let current = current.clone();
        let stop = stop.clone();
        let key = key.to_string();
        thread::spawn(move || {
            while !stop.load(Ordering::SeqCst) {
                thread::sleep(Duration::from_millis(250));
            }
            if let Some(child) = current.lock().unwrap().as_mut() {
                let _ = child.kill();
            }
            log::debug!("device log [{key}] stopped");
        });
    }
    let app = app.clone();
    let serial = serial.to_string();
    let key = key.to_string();
    thread::spawn(move || {
        // What the file already has, for the run that picks the stream up again.
        let mut resume = Resume::default();
        let mut first = true;
        let mut retry = RESTREAM_FIRST;
        let mut gap_said = false;
        while !stop.load(Ordering::SeqCst) {
            let mut cmd = adb::adb_command(&app);
            let from = resume.from();
            cmd.args(["-s", serial.as_str(), "logcat", "-v", "time", "-T", from.as_str()]);
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
            // logcat never ends by itself, and an app killed mid-session never
            // gets to the stopper.
            adb::end_with_this_app(&child);
            let Some(out) = child.stdout.take() else {
                return;
            };
            *current.lock().unwrap() = Some(child);
            // A stop that landed while this run was starting found nothing to kill.
            if stop.load(Ordering::SeqCst) {
                if let Some(child) = current.lock().unwrap().as_mut() {
                    let _ = child.kill();
                }
                break;
            }
            if first {
                log::info!("device log [{key}] streaming ({})", LOGCAT_SPEC.join(" "));
                first = false;
            }
            let mut replay = resume.replay();
            let mut got = 0usize;
            for line in BufReader::new(out).lines().map_while(Result::ok) {
                let line = line.trim_end();
                if line.is_empty() || line.starts_with("--------- beginning") {
                    continue;
                }
                if replay.already_printed(line) {
                    continue;
                }
                if got == 0 && gap_said {
                    log::info!("device log [{key}] the phone answers again — picked up from {from}");
                    gap_said = false;
                }
                got += 1;
                resume.printed(line);
                // logcat's own level letter (E/W) decides ours, so a phone-side
                // error is as visible in the file as a host-side one
                if line.contains(" E/") || line.contains(" F/") {
                    log::warn!("device [{key}] {line}");
                } else {
                    log::info!("device [{key}] {line}");
                }
            }
            if let Some(mut child) = current.lock().unwrap().take() {
                let _ = child.wait();
            }
            if stop.load(Ordering::SeqCst) {
                break;
            }
            // The phone went away under it with the session still up. Said once a
            // gap, not once a retry: a reconnect can take a minute.
            if got > 0 {
                retry = RESTREAM_FIRST;
            }
            if !gap_said {
                log::info!(
                    "device log [{key}] ended with the session still up — picking it up again \
                     when the phone answers"
                );
                gap_said = true;
            }
            let until = Instant::now() + retry;
            while Instant::now() < until && !stop.load(Ordering::SeqCst) {
                thread::sleep(Duration::from_millis(250));
            }
            retry = (retry * 2).min(RESTREAM_MAX);
        }
    });
}

/// First wait before a device log that ended mid-session is started again, and
/// the longest any later wait grows to while the phone stays away.
const RESTREAM_FIRST: Duration = Duration::from_secs(1);
const RESTREAM_MAX: Duration = Duration::from_secs(5);

/// Where a device log got to, for the run that picks it up after a gap: the
/// `-v time` stamp of the last line it printed, and every printed line that
/// carried that stamp.
///
/// `-T` replays from the start of that millisecond, so a picked-up run opens with
/// lines the file already has. They come off the replay one for one: each printed
/// line cancels one identical replayed line at that stamp, and whatever is left
/// over is new. Matching only the last line printed was ambiguous both ways — two
/// identical lines in one millisecond replayed one of them, and a boundary line
/// not seen again dropped every new line that shared its millisecond. One case is
/// beyond telling apart: the same line logged twice in one millisecond, once on
/// each side of the gap, is printed once.
#[derive(Default)]
struct Resume {
    stamp: Option<String>,
    at_stamp: Vec<String>,
}

impl Resume {
    /// `line` went to the file.
    fn printed(&mut self, line: &str) {
        let Some(stamp) = logcat_stamp(line) else {
            return;
        };
        if self.stamp.as_deref() != Some(stamp) {
            self.stamp = Some(stamp.to_string());
            self.at_stamp.clear();
        }
        self.at_stamp.push(line.to_string());
    }

    /// What `-T` takes: that stamp, or 1 — the end of the buffer rather than the
    /// beginning of boot — for a stream that has printed nothing yet.
    fn from(&self) -> String {
        self.stamp.clone().unwrap_or_else(|| "1".to_string())
    }

    /// The lines a run started from [`Resume::from`] must not print again.
    fn replay(&self) -> Replay {
        let mut left: HashMap<String, usize> = HashMap::new();
        for line in &self.at_stamp {
            *left.entry(line.clone()).or_default() += 1;
        }
        Replay { stamp: self.stamp.clone(), left }
    }
}

/// The opening of a picked-up run: the lines of [`Resume`]'s millisecond the file
/// already has, and how many of each.
struct Replay {
    stamp: Option<String>,
    left: HashMap<String, usize>,
}

impl Replay {
    /// True for a replayed line the file already has, which it then uses up.
    fn already_printed(&mut self, line: &str) -> bool {
        if self.left.is_empty() {
            return false;
        }
        match logcat_stamp(line) {
            Some(stamp) if Some(stamp) == self.stamp.as_deref() => {
                // Not among them: logged in that millisecond after the gap, so new.
                let Some(n) = self.left.get_mut(line) else {
                    return false;
                };
                *n -= 1;
                if *n == 0 {
                    self.left.remove(line);
                }
                true
            }
            // Past that millisecond nothing more can be a repeat.
            Some(_) => {
                self.left.clear();
                false
            }
            None => false,
        }
    }
}

/// The `-v time` stamp a logcat line opens with (`MM-DD hh:mm:ss.mmm`), if it
/// opens with one: what `-T` takes back to pick a stream up where it ended.
fn logcat_stamp(line: &str) -> Option<&str> {
    let stamp = line.get(..18)?;
    let b = stamp.as_bytes();
    let shaped = b[2] == b'-' && b[5] == b' ' && b[8] == b':' && b[11] == b':' && b[14] == b'.';
    (shaped && b.iter().enumerate().all(|(i, c)| [2, 5, 8, 11, 14].contains(&i) || c.is_ascii_digit()))
        .then_some(stamp)
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
            // At the tab: a serial named after an mDNS service can have a
            // space in it (see adb::split_device_line).
            let (serial, state) = l.split_once('\t')?;
            (state.trim() == "device").then(|| serial.to_string())
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn logcat_stamp_is_the_time_a_stream_picks_up_from() {
        assert_eq!(
            logcat_stamp("11-09 18:45:39.079 I/OpenDeX (31243): [launcher] desktop ready"),
            Some("11-09 18:45:39.079")
        );
        // logcat's own banner, a line cut short, and lines that only look like one:
        // handing any of them to `-T` would start the stream somewhere wrong.
        assert_eq!(logcat_stamp("--------- beginning of main"), None);
        assert_eq!(logcat_stamp("11-09 18:45"), None);
        assert_eq!(logcat_stamp("11/09 18:45:39.079 I/OpenDeX: x"), None);
        assert_eq!(logcat_stamp("1a-09 18:45:39.079 I/OpenDeX: x"), None);
        assert_eq!(logcat_stamp("11-09 18:45:39,079 I/OpenDeX: x"), None);
    }

    /// What a run picked up after a gap prints of `replayed`, given what the file
    /// had from before it.
    fn picked_up(before: &[&str], replayed: &[&str]) -> Vec<String> {
        let mut resume = Resume::default();
        for line in before {
            resume.printed(line);
        }
        let mut replay = resume.replay();
        replayed
            .iter()
            .filter(|line| !replay.already_printed(line))
            .map(|line| line.to_string())
            .collect()
    }

    #[test]
    fn a_picked_up_stream_prints_each_line_once() {
        let first = "11-12 10:00:00.000 I/OpenDeX ( 1001): [x] first";
        let same = "11-12 10:00:00.001 I/OpenDeX ( 1001): [x] same";
        let next = "11-12 10:00:00.002 I/OpenDeX ( 1001): [x] next";
        // Two identical lines in one millisecond, both in the file before the gap.
        // Matching the last line printed took the first of them for the boundary and
        // printed the second again.
        assert_eq!(picked_up(&[first, same, same], &[same, same, next]), [next]);
        assert_eq!(picked_up(&[first, same], &[same, same, next]), [same, next]);

        // A line logged in the boundary's millisecond after the gap is new, wherever
        // it sorts on the way back — and so is everything after a boundary line the
        // buffer no longer has, which matching on it skipped whole.
        let before = "11-12 10:00:01.500 I/OpenDeX ( 1001): [x] before the gap";
        let during = "11-12 10:00:01.500 I/OpenDeX ( 1001): [x] during the gap";
        assert_eq!(picked_up(&[before], &[before, during]), [during]);
        assert_eq!(picked_up(&[before], &[during, before]), [during]);
        assert_eq!(picked_up(&[before], &[during, next]), [during, next]);

        // A stream that never printed anything has nothing to skip.
        assert_eq!(picked_up(&[], &[same, next]), [same, next]);
    }
}
