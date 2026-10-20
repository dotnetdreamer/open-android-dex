use std::collections::{HashMap, HashSet, VecDeque};
use std::io::{BufRead, BufReader, Read};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager};

use crate::adb;
use crate::diag;
use crate::display;
use crate::files;
use crate::shell::ShellSession;
use crate::transfer;

/// Mirroring options, matching the settings the frontend persists.
/// Zero means "use scrcpy's default" for the numeric fields.
///
/// When `app_package` is set the session is a DeX-style app window: scrcpy
/// creates a virtual display on the device (`new_display`) and starts the
/// app on it — no companion APK involved.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MirrorOptions {
    pub serial: String,
    #[serde(default)]
    pub max_size: u32,
    #[serde(default)]
    pub video_bit_rate_mbps: u32,
    #[serde(default)]
    pub max_fps: u32,
    #[serde(default = "default_true")]
    pub audio: bool,
    #[serde(default)]
    pub stay_awake: bool,
    /// scrcpy --keep-active (4.0+): report user activity on the session's
    /// display every few seconds, so it never times out while it is open.
    ///
    /// Not a replacement for `stay_awake`, and the two cover different
    /// halves. `--stay-awake` is a global setting that only holds while the
    /// phone is CHARGING, which a USB cable usually means and a wireless
    /// session on battery never does — there, an idle desktop (reading, no
    /// mouse movement) used to time out and go dark under the user. This one
    /// works on either power source and changes no setting, so there is
    /// nothing to put back if the PC side dies.
    #[serde(default)]
    pub keep_active: bool,
    #[serde(default)]
    pub turn_screen_off: bool,
    #[serde(default)]
    pub always_on_top: bool,
    #[serde(default)]
    pub fullscreen: bool,
    #[serde(default)]
    pub window_title: String,
    #[serde(default)]
    pub auto_reconnect: bool,
    #[serde(default)]
    pub app_package: Option<String>,
    /// Virtual display size, e.g. "1920x1080" — only used with `app_package`.
    #[serde(default)]
    pub new_display: Option<String>,
    #[serde(default)]
    pub vd_no_decorations: bool,
    /// Spawn without OS decorations — used when the window will be
    /// embedded into the shell as a native child.
    #[serde(default)]
    pub window_borderless: bool,
    /// Capture playback audio (duplicated, Android 13+) instead of the mic
    /// path — the companion-free approach to audio for app windows.
    #[serde(default)]
    pub audio_playback: bool,
    /// Desktop sessions only: watch the virtual display and convert apps
    /// that open fullscreen (launched from the in-desktop home) into
    /// freeform windows.
    #[serde(default)]
    pub freeform: bool,
    /// scrcpy --mouse-bind value (e.g. "+hsn:bhsn" forwards right-clicks
    /// to Android so the launcher can show context menus).
    #[serde(default)]
    pub mouse_bind: Option<String>,
    /// scrcpy --mouse mode: "sdk" (default) or "uhid".
    ///
    /// Decides which side of the cable DRAWS the pointer, which is a much
    /// bigger deal than it sounds. In `sdk` scrcpy injects events straight
    /// into InputDispatcher, below the stage that owns the pointer sprite —
    /// Android renders no cursor at all and what the user sees is the PC's own,
    /// floating over the video. In `uhid` a virtual HID mouse exists, so
    /// Android draws the pointer INTO the stream and everything the launcher
    /// sets with PointerIcon (Settings → Mouse & cursor) becomes visible.
    ///
    /// None = leave scrcpy's default alone.
    #[serde(default)]
    pub mouse_mode: Option<String>,
    /// scrcpy --video-codec ("h264" | "h265" | "av1"); None = scrcpy's default.
    #[serde(default)]
    pub video_codec: Option<String>,
    /// scrcpy --video-encoder (a MediaCodec name); None = let scrcpy pick.
    #[serde(default)]
    pub video_encoder: Option<String>,
    /// Two-way clipboard sync. Off adds --no-clipboard-autosync.
    #[serde(default = "default_true")]
    pub clipboard_autosync: bool,
    /// An audio-only companion (`--no-video --no-window --no-control`), the
    /// process that carries the desktop's sound so that "where sound plays"
    /// can change without tearing the desktop down — see
    /// `reconcile_audio_session`. Never set by the frontend.
    #[serde(default)]
    pub audio_only: bool,
    /// Where scrcpy's window opens and how big, so that a display fitted to
    /// the screen is shown one pixel for one (display.rs). Worked out on every
    /// spawn from the screen the app is on, never sent by the frontend.
    #[serde(skip)]
    pub window: Option<display::WindowRect>,
    /// The desktop's Default density, which the Display size presets are
    /// multiples of: one dp per point of the computer's UI on a fitted
    /// display, the 1080-dp-tall one on a resolution picked in Settings.
    #[serde(skip)]
    pub base_dpi: Option<i32>,
    /// The display was fitted to the screen rather than picked in Settings.
    #[serde(skip)]
    pub fitted: bool,
    /// The display follows its window (`--flex-display`), and the phone is
    /// told to multiply every size the window asks for by this — pixels per
    /// window coordinate (display::flex_geometry). None: fitted once.
    #[serde(skip)]
    pub flex: Option<f64>,
    /// The desktop goes to this app's own window (dex.rs): the window daemon's
    /// dex makes the display and sends it at the pace the link takes, and
    /// scrcpy's window is the fallback. Decided on every spawn from what the
    /// settings and the phone allow ([`own_video_wanted`]), never sent by the
    /// frontend.
    #[serde(skip)]
    pub own_video: bool,
}

fn default_true() -> bool {
    true
}

impl MirrorOptions {
    /// One session per phone screen ("<serial>"), per virtual desktop
    /// ("<serial>|desktop"), per app window ("<serial>|<package>") — or the
    /// desktop's audio companion ("<serial>|audio"), which must be branched
    /// first: it has neither a package nor a display and would otherwise
    /// collide with the plain phone-screen mirror.
    fn session_key(&self) -> String {
        if self.audio_only {
            return format!("{}|audio", self.serial);
        }
        match (&self.app_package, &self.new_display) {
            (Some(pkg), _) => format!("{}|{}", self.serial, pkg),
            (None, Some(_)) => format!("{}|desktop", self.serial),
            (None, None) => self.serial.clone(),
        }
    }
}

/// Event pushed to the webview whenever a session changes state.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MirrorEvent {
    pub session_key: String,
    pub serial: String,
    pub app_package: Option<String>,
    /// "running" | "reconnecting" | "stopped"
    pub status: String,
    pub pid: Option<u32>,
    pub exit_code: Option<i32>,
    pub intentional: bool,
}

/// Rolling window of what scrcpy last printed, kept so a failure can be
/// reported with the lines that explain it instead of an exit code.
type OutputLog = Arc<Mutex<VecDeque<String>>>;

/// Enough to cover scrcpy's whole startup chatter.
const OUTPUT_KEEP: usize = 80;

fn output_tail(output: &OutputLog, lines: usize) -> String {
    let buf = output.lock().unwrap();
    let start = buf.len().saturating_sub(lines);
    buf.iter()
        .skip(start)
        .map(|l| format!("    {l}"))
        .collect::<Vec<_>>()
        .join("\n")
}

pub struct SessionHandle {
    child: Arc<Mutex<Child>>,
    stop_requested: Arc<AtomicBool>,
    pid: u32,
    serial: String,
    app_package: Option<String>,
    /// Android display id of the created virtual display (-1 = none/unknown),
    /// parsed from scrcpy's "New display: ... (id=N)" log line.
    display_id: Arc<AtomicI32>,
    /// Density (dpi) of the virtual display, from the same log line.
    density: Arc<AtomicI32>,
    /// The size the virtual display was actually created at, from the same
    /// log line — which is not always the size `--new-display` asked for: see
    /// [`parse_display_size_announced`]. None until the line is seen.
    display_size: Arc<Mutex<Option<(i32, i32)>>>,
    /// Native window handle once embedded into the shell (0 = not embedded).
    hwnd: Arc<std::sync::atomic::AtomicIsize>,
    /// Last lines scrcpy printed on this attempt.
    output: OutputLog,
    /// Whether this session was spawned with `--audio-dup` — what
    /// `reconcile_audio_session` compares against the stored config to decide
    /// if the audio companion must be cycled. Meaningless for video sessions.
    audio_playback: bool,
    /// The Default density of this attempt's display (-1 = not a desktop): what
    /// a Display size request is remembered as a multiple of, and what the
    /// launcher is told to build its presets on. Changes with the screen a
    /// display that follows its window is dragged onto (`watch_window_scale`).
    base_dpi: Arc<AtomicI32>,
}

impl SessionHandle {
    pub fn pid(&self) -> u32 {
        self.pid
    }
    pub fn hwnd(&self) -> &std::sync::atomic::AtomicIsize {
        &self.hwnd
    }
}

#[derive(Default)]
pub struct MirrorState(pub Mutex<HashMap<String, SessionHandle>>);

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionInfo {
    pub session_key: String,
    pub serial: String,
    pub app_package: Option<String>,
    pub pid: u32,
    pub display_id: Option<i32>,
}

fn emit_status(app: &AppHandle, ev: MirrorEvent) {
    log::info!(
        "mirror [{}] -> {} (pid={:?}, code={:?}, intentional={})",
        ev.session_key,
        ev.status,
        ev.pid,
        ev.exit_code,
        ev.intentional
    );
    let _ = app.emit("mirror:status", ev);
}

fn build_args(opts: &MirrorOptions) -> Vec<String> {
    let mut args = vec!["-s".into(), opts.serial.clone()];
    // The audio companion is sound and nothing else. No window means no
    // mouse/keyboard options may follow (scrcpy rejects several of them
    // without a display), and --no-control keeps it from being a second
    // clipboard syncer fighting the desktop session's.
    if opts.audio_only {
        args.push("--no-video".into());
        args.push("--no-window".into());
        args.push("--no-control".into());
        if opts.audio_playback {
            args.push("--audio-source=playback".into());
            args.push("--audio-dup".into());
        }
        return args;
    }
    if opts.max_size > 0 {
        args.push(format!("--max-size={}", opts.max_size));
    }
    if opts.video_bit_rate_mbps > 0 {
        args.push(format!("--video-bit-rate={}M", opts.video_bit_rate_mbps));
    }
    if opts.max_fps > 0 {
        args.push(format!("--max-fps={}", opts.max_fps));
    }
    if !opts.audio {
        args.push("--no-audio".into());
    } else if opts.audio_playback {
        args.push("--audio-source=playback".into());
        args.push("--audio-dup".into());
    }
    if opts.stay_awake {
        args.push("--stay-awake".into());
    }
    if opts.keep_active {
        args.push("--keep-active".into());
    }
    if opts.turn_screen_off {
        args.push("--turn-screen-off".into());
    }
    if opts.always_on_top {
        args.push("--always-on-top".into());
    }
    if opts.fullscreen {
        args.push("--fullscreen".into());
    }
    if opts.window_borderless {
        args.push("--window-borderless".into());
    }
    // Before --mouse-bind on purpose, because the two are a pair: scrcpy's
    // DEFAULT bind differs by mode (sdk reserves the shortcut buttons, uhid
    // forwards everything). We never take that default — the bind below is
    // pinned to "+hsn:bhsn", whose first character forwards right-click to
    // Android, so the launcher's context menus behave identically in both
    // modes. Anyone changing one of these must read the other.
    if let Some(mode) = &opts.mouse_mode {
        if mode == "uhid" {
            args.push("--mouse=uhid".into());
        }
    }
    if let Some(bind) = &opts.mouse_bind {
        args.push(format!("--mouse-bind={bind}"));
    }
    if let Some(codec) = &opts.video_codec {
        args.push(format!("--video-codec={codec}"));
    }
    // Only meaningful together with a codec: scrcpy validates the encoder
    // against the selected codec, and an encoder for the other one aborts
    // the session outright.
    if let Some(encoder) = &opts.video_encoder {
        args.push(format!("--video-encoder={encoder}"));
    }
    if !opts.clipboard_autosync {
        args.push("--no-clipboard-autosync".into());
    }
    // Where a file dropped on this window lands. scrcpy's own default, spelled
    // out because the transfer HUD names the folder and the progress poll
    // stats the file in it — see transfer.rs.
    args.push(format!("--push-target={}", transfer::PUSH_TARGET));
    // Black, which is what scrcpy drew before 4.0 made dark gray the default.
    // It is not only the letterbox colour: since 4.0 the window opens as soon
    // as the stream's size is known, BEFORE the first frame, and until that
    // frame arrives this is all it shows. An empty virtual display is black,
    // so gray would flash on every desktop start and every reconnect.
    args.push("--background-color=000000".into());
    // Once a second, the frames scrcpy's window showed: the classic window's
    // half of the readout every change to the desktop's video is measured by.
    // The log gets a summary, not the lines (`FpsTally`).
    if opts.new_display.is_some() && opts.app_package.is_none() {
        args.push("--print-fps".into());
    }
    if let Some(display) = &opts.new_display {
        args.push(format!("--new-display={display}"));
        if opts.vd_no_decorations {
            args.push("--no-vd-system-decorations".into());
        }
    }
    // scrcpy would size the window to fit the screen less a 96-point margin,
    // which shows a display fitted to the screen at ~0.9x: a little soft, the
    // one thing the fit is for. These four put its content exactly where the
    // display shows one for one. The window stays the user's to move after.
    if let Some(w) = &opts.window {
        args.push(format!("--window-x={}", w.x));
        args.push(format!("--window-y={}", w.y));
        args.push(format!("--window-width={}", w.width));
        args.push(format!("--window-height={}", w.height));
    }
    // A display that follows its window. Letterbox, not flex's own default of
    // "unscaled": the phone makes the display the window's size in PIXELS
    // (display-scale.patch), while the client draws content in window
    // coordinates — points on a Mac — so unscaled would draw it twice as big.
    // Letterboxed, it fills the window one pixel for one.
    if opts.flex.is_some() {
        args.push("--flex-display".into());
        args.push("--render-fit=letterbox".into());
    }
    if let Some(pkg) = &opts.app_package {
        args.push(format!("--start-app={pkg}"));
    }
    let title = opts.window_title.trim();
    if !title.is_empty() {
        args.push(format!("--window-title={title}"));
    }
    args
}

/// What the macOS Dock and menu bar call the scrcpy process.
///
/// A bare (unbundled) executable is named after its FILE — there is no
/// Info.plist to say otherwise — so the desktop window sat in the Dock as
/// "scrcpy" over our own icon. Spawning through a hardlink with this name is
/// the whole fix: same inode, same directory, so scrcpy still finds its
/// server and dylibs next to itself. MUST match productName in
/// tauri.conf.json — two names in the Dock would be worse than one wrong one.
#[cfg(target_os = "macos")]
const PROCESS_NAME: &str = "Open Android DeX";

/// The path to spawn scrcpy by: on macOS a product-named hardlink to the
/// bundled binary, refreshed when the binary changes so a bundle update can
/// never leave it pointing at last version's inode. Anywhere the link cannot
/// be made, the plain binary is the answer and the Dock label is the small
/// price.
///
/// The link lives in the app-data dir, NEVER next to the binary: on an
/// installed build that directory is inside the signed .app, and writing a
/// file into a sealed Resources tree invalidates the bundle's code signature.
/// The binary's own location stops mattering once it is spawned from
/// elsewhere — the server, adb and icon are all named by env var, and the
/// working directory is set to the real bin dir regardless.
#[cfg(target_os = "macos")]
fn branded_exe(app: &AppHandle, exe: &std::path::Path) -> std::path::PathBuf {
    use std::os::unix::fs::MetadataExt;
    let Ok(dir) = app.path().app_data_dir().map(|d| d.join("bin")) else {
        return exe.to_path_buf();
    };
    if std::fs::create_dir_all(&dir).is_err() {
        return exe.to_path_buf();
    }
    let link = dir.join(PROCESS_NAME);
    let Ok(src) = std::fs::metadata(exe) else {
        return exe.to_path_buf();
    };
    // Only touch the link when it is missing or superseded. Sessions spawn
    // concurrently (the desktop, its audio companion, mirror windows), and an
    // unconditional remove-and-relink would be a window in which another
    // spawn resolves a name that momentarily is not there. "Superseded" is
    // inode inequality for a hardlink; for the copy fallback it is a size
    // change, because a copy's mtime is its own and the sizes of two scrcpy
    // releases have never matched.
    if let Ok(l) = std::fs::metadata(&link) {
        if (l.dev() == src.dev() && l.ino() == src.ino()) || l.len() == src.len() {
            return link;
        }
    }
    let _ = std::fs::remove_file(&link);
    if std::fs::hard_link(exe, &link).is_ok() {
        return link;
    }
    // A different volume (app data and the .app usually share one, but this
    // is not guaranteed): fall back to a real copy, permissions included.
    match std::fs::copy(exe, &link) {
        Ok(_) => link,
        Err(e) => {
            log::debug!("cannot brand the scrcpy process name: {e}");
            exe.to_path_buf()
        }
    }
}

#[cfg(not(target_os = "macos"))]
fn branded_exe(_app: &AppHandle, exe: &std::path::Path) -> std::path::PathBuf {
    // Windows names taskbar entries after the window title, which is already
    // ours; nothing to fix.
    exe.to_path_buf()
}

/// Spawn scrcpy — bundled copy preferred, PATH fallback for dev machines.
/// The bundled adb is forced via the ADB env var so scrcpy shares our server.
///
/// The fallback must be scrcpy 4.0 or newer: the command line carries options
/// older releases reject outright (`--keep-active`, `--background-color`).
fn spawn_scrcpy(app: &AppHandle, opts: &MirrorOptions) -> Result<Child, String> {
    if let Some(scale) = opts.flex {
        write_display_scale(app, &opts.serial, scale);
    }
    let bin = adb::bin_dir(app)?;
    let exe = bin.join(adb::exe_name("scrcpy"));
    adb::warn_if_not_executable(&exe);
    let mut cmd = if exe.exists() {
        Command::new(branded_exe(app, &exe))
    } else {
        Command::new(adb::exe_name("scrcpy"))
    };
    // scrcpy resolves scrcpy-server relative to its own dir (and on Windows its
    // dlls too), and the Windows spelling of ADB below relies on this as well.
    if bin.is_dir() {
        cmd.current_dir(&bin);
    }
    // …but say it outright as well. The cwd trick only holds while the binary
    // being run is the bundled one; the PATH fallback on a dev machine is some
    // other scrcpy, which would look next to ITSELF and push a server of a
    // different version — a version mismatch scrcpy reports as a device error.
    // This env var is the documented way to pin it and costs nothing when the
    // two already agree.
    let server = bin.join("scrcpy-server");
    if server.is_file() {
        cmd.env("SCRCPY_SERVER_PATH", &server);
    }
    let adb_path = bin.join(adb::exe_name("adb"));
    if adb_path.exists() {
        adb::warn_if_not_executable(&adb_path);
        // On Windows, a NAME — never a path.
        //
        // scrcpy before 4.0 built the command line for its adb child by joining
        // argv with spaces and no quoting ("only make it work for this very
        // simple case", sys/win/process.c), so `CreateProcess` had to guess
        // where the executable ends. Handing it `C:\Users\Laptop city\…\adb.exe`
        // made that a guess, and on at least one machine it guessed wrong and
        // failed the whole session with `CreateProcessW() error 193` before a
        // display could exist. 4.0 quotes every argument (util/command.c), but
        // the bare name stays: it is still exactly right — `CreateProcess`
        // searches the calling program's own directory first, and scrcpy.exe
        // lives in the same directory as the adb.exe we want it to use.
        //
        // On macOS the reasoning inverts on both halves. There is no command
        // line to mis-split — scrcpy's posix backend hands argv to `execvp` as
        // an array — and `execvp` resolves a bare name against PATH, which
        // does NOT include the working directory. A bare "adb" would therefore
        // find the user's own adb or nothing at all, so the absolute path is
        // both safe and required.
        #[cfg(windows)]
        cmd.env("ADB", adb::exe_name("adb"));
        #[cfg(not(windows))]
        cmd.env("ADB", &adb_path);
    }
    // Our logo on the mirror window (and its taskbar button) instead of
    // scrcpy's.
    //
    // scrcpy 4.0+ loads two images, by fixed name, from SCRCPY_ICON_DIR:
    // `scrcpy.png`, the window icon, and `disconnected.png`, which fills the
    // window for two seconds when the phone drops. resources/icon holds both —
    // our logo under scrcpy's name, and scrcpy's own disconnected picture.
    // Without the variable the bundled (portable) builds read both from beside
    // the exe, where `scrcpy.png` is scrcpy's logo. A directory missing either
    // file is not fatal, but each miss is an ERROR line in the session log.
    // (3.x read a single file path from SCRCPY_ICON_PATH instead; it is no
    // longer set, because 4.0+ ignores it.)
    //
    // Unlike ADB above, this never reaches a command line — scrcpy opens the
    // files itself — so an absolute path is safe here.
    if let Ok(dir) = adb::resources_dir(app).map(|d| d.join("icon")) {
        if dir.join("scrcpy.png").is_file() {
            cmd.env("SCRCPY_ICON_DIR", &dir);
        }
    }
    #[allow(unused_mut)] // only Windows adds to it
    let mut args = build_args(opts);
    #[cfg(windows)]
    args.push(format!("--port={}", listen_port_range()));
    // The exact command line, because every scrcpy failure is a question
    // about which option it was given.
    log::info!(
        "scrcpy [{}] spawning: {} {}",
        opts.session_key(),
        if exe.exists() {
            exe.display().to_string()
        } else {
            format!("{} (from PATH)", adb::exe_name("scrcpy"))
        },
        args.join(" ")
    );
    cmd.args(args);
    cmd.stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    adb::hide_console(&mut cmd);
    cmd.spawn()
        .map_err(|e| format!("failed to start scrcpy: {e}"))
}

/// The session's next child: the desktop's own server (dex.rs), or scrcpy.
fn spawn_session(app: &AppHandle, opts: &MirrorOptions) -> Result<Child, String> {
    if opts.own_video {
        crate::dex::spawn(app, opts)
    } else {
        spawn_scrcpy(app, opts)
    }
}

/// Where the next session's scrcpy starts looking for a port to listen on
/// while its server dials back — Windows only.
///
/// scrcpy walks its port range (27183:27199) and takes the first port it can
/// listen on, which keeps concurrent sessions apart everywhere but Windows. It
/// sets SO_REUSEADDR, and on Windows that lets a second process bind a port
/// another is already LISTENING on. So the desktop and its audio companion,
/// spawned a fraction of a second apart, both sat on 27183, both `adb reverse`
/// rules pointed at it, and each server's connection landed in whichever of the
/// two processes Windows chose — on a Galaxy A13, no sound at all, and on a
/// second try the desktop lost its own video and control sockets as well.
///
/// Each spawn therefore starts one port further along. A port is only held
/// until the server has connected (scrcpy closes the listener then), so a few
/// seconds of each session's start, and the rotation can wrap freely.
#[cfg(windows)]
static NEXT_PORT: std::sync::atomic::AtomicU16 = std::sync::atomic::AtomicU16::new(0);

#[cfg(windows)]
fn listen_port_range() -> String {
    const FIRST: u16 = 27183;
    const LAST: u16 = 27199;
    let first = FIRST + NEXT_PORT.fetch_add(1, Ordering::SeqCst) % (LAST - FIRST + 1);
    format!("{first}:{LAST}")
}

/// "[server] INFO: New display: 1920x1080/370 (id=3)" → 3
fn parse_display_id(line: &str) -> Option<i32> {
    let rest = &line[line.find("New display:")?..];
    let idpos = rest.find("(id=")?;
    let num: String = rest[idpos + 4..]
        .chars()
        .take_while(|c| c.is_ascii_digit())
        .collect();
    num.parse().ok()
}

/// "New display: 1920x1080/370 (id=3)" → 370
fn parse_display_density(line: &str) -> Option<i32> {
    let rest = &line[line.find("New display:")?..];
    let slash = rest.find('/')?;
    let num: String = rest[slash + 1..]
        .chars()
        .take_while(|c| c.is_ascii_digit())
        .collect();
    num.parse().ok()
}

/// "New display: 1920x1072/160 (id=3)" → (1920, 1072)
///
/// The size the display was really created at, which since scrcpy 4.0 need not
/// be the one `--new-display` asked for: the server rounds it DOWN to a
/// multiple of the video encoder's size alignment (`NewDisplayCapture.prepare`),
/// so an encoder that wants 16 turns 1920x1080 into 1920x1072. The two phones
/// checked when upgrading report 2, which leaves 1080 alone — but maximized
/// windows and the taskbar strip are measured from the bottom edge, and must
/// not depend on which encoder a phone happens to have.
fn parse_display_size_announced(line: &str) -> Option<(i32, i32)> {
    let rest = &line[line.find("New display:")? + "New display:".len()..];
    parse_display_size(rest.split_whitespace().next()?)
}

/// "[server] INFO: Display resized: 2832x1706/320 (id=63)" → (2832, 1706, 320)
///
/// What the patched server (openandroiddex-scrcpy) says each time a display
/// that follows its window takes a new size — the only way this side learns
/// it without asking the phone.
fn parse_display_resized(line: &str) -> Option<(i32, i32, i32)> {
    let rest = &line[line.find("Display resized:")? + "Display resized:".len()..];
    let (size, dpi) = rest.split_whitespace().next()?.split_once('/')?;
    let (w, h) = parse_display_size(size)?;
    Some((w, h, dpi.trim().parse().ok()?))
}

/// "INFO: 58 fps" or "INFO: 45 fps (+3 frames skipped)" — scrcpy's fps counter
/// (`--print-fps`) — as the number of frames its window showed.
fn parse_fps(line: &str) -> Option<u32> {
    let rest = line.trim();
    let rest = rest.strip_prefix("INFO:").unwrap_or(rest).trim();
    let (n, tail) = rest.split_once(' ')?;
    (tail == "fps" || tail.starts_with("fps (")).then_some(())?;
    n.parse().ok()
}

/// scrcpy's frame counts, a line a second, turned into one line in the log
/// every half minute: what the classic window showed, against which the
/// desktop's own window's numbers are read.
struct FpsTally {
    since: Instant,
    counts: Vec<u32>,
}

impl FpsTally {
    const EVERY: Duration = Duration::from_secs(30);

    fn new() -> Self {
        Self { since: Instant::now(), counts: Vec::new() }
    }

    /// One second's count; a summary when a half minute of them is in.
    fn add(&mut self, fps: u32) -> Option<String> {
        self.counts.push(fps);
        if self.since.elapsed() < Self::EVERY {
            return None;
        }
        let n = self.counts.len().max(1) as u32;
        let mean = self.counts.iter().sum::<u32>() / n;
        let low = self.counts.iter().copied().min().unwrap_or(0);
        let line = format!("{mean} fps on average, {low} at the lowest, over {n} moving seconds");
        self.counts.clear();
        self.since = Instant::now();
        Some(line)
    }
}

/// Drain one of scrcpy's output pipes: record every line, watch for the
/// virtual display id and announce it to the webview once seen.
///
/// Everything is kept, not just lines containing "ERROR". scrcpy explains
/// itself in INFO lines ("Device: …", "Renderer: …", "New display: …") and the
/// one that is missing is usually the whole answer; throwing them away left
/// nothing to read when a phone stopped part-way.
#[allow(clippy::too_many_arguments)]
fn spawn_output_reader<R: Read + Send + 'static>(
    app: AppHandle,
    key: String,
    serial: String,
    codec: Option<String>,
    flex: bool,
    display_id: Arc<AtomicI32>,
    density: Arc<AtomicI32>,
    display_size: Arc<Mutex<Option<(i32, i32)>>>,
    output: OutputLog,
    stream: R,
) {
    thread::spawn(move || {
        // The patched server says it is one before it makes the display.
        let mut follows_scale = false;
        let mut tally = FpsTally::new();
        for line in BufReader::new(stream).lines().map_while(Result::ok) {
            let line = line.trim_end().to_string();
            if line.is_empty() {
                continue;
            }
            if let Some(fps) = parse_fps(&line) {
                if let Some(summary) = tally.add(fps) {
                    log::info!("scrcpy [{key}] the picture: {summary}");
                }
                continue;
            }
            if line.contains("ERROR") {
                log::warn!("scrcpy [{key}] {line}");
            } else {
                log::info!("scrcpy [{key}] {line}");
            }
            {
                let mut buf = output.lock().unwrap();
                if buf.len() == OUTPUT_KEEP {
                    buf.pop_front();
                }
                buf.push_back(line.clone());
            }
            // A file dropped on this window is pushed by scrcpy itself, and
            // these lines are the only account of it there is.
            transfer::observe(&key, &line);
            // The size of the video, which comes out smaller than the display
            // only when the phone's encoder could not take the display's size.
            if let Some((tw, th)) = display::parse_texture_size(&line) {
                let shown = *display_size.lock().unwrap();
                if let Some((dw, dh)) = shown {
                    let (video, asked) = (tw.max(th), dw.max(dh).max(0) as u32);
                    if video < asked {
                        // Off this thread, like the dumpsys below: knowing
                        // which phone this is can take an adb round trip.
                        let (a2, s2, c2) = (app.clone(), serial.clone(), codec.clone());
                        thread::spawn(move || display::remember_video_cap(&a2, &s2, c2.as_deref(), video));
                    }
                }
            }
            if line.contains("Flex display:") {
                follows_scale = true;
            }
            if let Some((w, h, dpi)) = parse_display_resized(&line) {
                *display_size.lock().unwrap() = Some((w, h));
                density.store(dpi, Ordering::SeqCst);
                log::info!("scrcpy [{key}] the display follows its window: {w}x{h} at {dpi} dpi");
            }
            if display_id.load(Ordering::SeqCst) < 0 {
                if let Some(id) = parse_display_id(&line) {
                    if flex && !follows_scale {
                        log::warn!(
                            "scrcpy [{key}] this scrcpy server does not follow the screen's scale \
                             (the release's own, not openandroiddex-scrcpy's?) — the desktop will \
                             follow its window in points, half resolution on a Retina screen"
                        );
                    }
                    if let Some(d) = parse_display_density(&line) {
                        density.store(d, Ordering::SeqCst);
                    }
                    // Before the id, so that whoever sees the id sees the size.
                    let size = parse_display_size_announced(&line);
                    if size.is_some() {
                        *display_size.lock().unwrap() = size;
                    }
                    display_id.store(id, Ordering::SeqCst);
                    log::info!(
                        "scrcpy [{key}] virtual display id={id} density={} size={size:?}",
                        density.load(Ordering::SeqCst)
                    );
                    let _ = app.emit(
                        "mirror:display",
                        serde_json::json!({ "sessionKey": key, "serial": serial, "displayId": id }),
                    );
                    // Off this thread: it is reading scrcpy's output, and a dumpsys
                    // takes long enough to stall the lines behind it.
                    let (a2, s2, k2) = (app.clone(), serial.clone(), key.clone());
                    thread::spawn(move || warn_if_display_untrusted(&a2, &s2, &k2, id));
                }
            }
        }
    });
}

/// One spawned scrcpy attempt, as the monitor loop sees it.
struct Attempt {
    child: Arc<Mutex<Child>>,
    pid: u32,
    /// Set once this attempt has a display — the difference between "the
    /// phone was unplugged" and "this configuration never worked".
    display_id: Arc<AtomicI32>,
    output: OutputLog,
}

/// Wrap a freshly spawned child: wire up output readers, arm the display
/// watchdog, store the session handle, and return what the monitor needs.
fn register_session(
    app: &AppHandle,
    opts: &MirrorOptions,
    mut child: Child,
    stop: Arc<AtomicBool>,
) -> Attempt {
    let key = opts.session_key();
    let pid = child.id();
    let display_id = Arc::new(AtomicI32::new(-1));
    // Seeded from the dpi we asked for rather than left at -1: if scrcpy's
    // "New display" line is never seen, the taskbar height and the maximize
    // bounds still come out right.
    let density = Arc::new(AtomicI32::new(requested_density(opts).unwrap_or(-1)));
    let display_size = Arc::new(Mutex::new(None));
    let output: OutputLog = Arc::new(Mutex::new(VecDeque::with_capacity(OUTPUT_KEEP)));
    if let Some(out) = child.stdout.take() {
        spawn_output_reader(
            app.clone(),
            key.clone(),
            opts.serial.clone(),
            opts.video_codec.clone(),
            opts.flex.is_some(),
            display_id.clone(),
            density.clone(),
            display_size.clone(),
            output.clone(),
            out,
        );
    }
    if let Some(err) = child.stderr.take() {
        spawn_output_reader(
            app.clone(),
            key.clone(),
            opts.serial.clone(),
            opts.video_codec.clone(),
            opts.flex.is_some(),
            display_id.clone(),
            density.clone(),
            display_size.clone(),
            output.clone(),
            err,
        );
    }
    let child = Arc::new(Mutex::new(child));
    let handle = SessionHandle {
        child: child.clone(),
        stop_requested: stop.clone(),
        pid,
        serial: opts.serial.clone(),
        app_package: opts.app_package.clone(),
        display_id: display_id.clone(),
        density: density.clone(),
        display_size,
        hwnd: Arc::new(std::sync::atomic::AtomicIsize::new(0)),
        output: output.clone(),
        audio_playback: opts.audio_playback,
        base_dpi: Arc::new(AtomicI32::new(opts.base_dpi.unwrap_or(-1))),
    };
    let base_dpi = handle.base_dpi.clone();
    let state = app.state::<MirrorState>();
    state.0.lock().unwrap().insert(key.clone(), handle);
    log::info!("scrcpy [{key}] started, pid {pid}");

    if opts.flex.is_some() {
        watch_window_scale(
            app.clone(),
            opts.serial.clone(),
            key.clone(),
            pid,
            child.clone(),
            stop.clone(),
            display_id.clone(),
            density.clone(),
            base_dpi,
        );
    }

    spawn_display_watchdog(
        app.clone(),
        opts.clone(),
        key,
        display_id.clone(),
        density,
        child.clone(),
        output.clone(),
        stop,
    );

    Attempt {
        child,
        pid,
        display_id,
        output,
    }
}

/// Where the patched scrcpy server reads the scale it multiplies a flex
/// display's sizes by (DisplayScale.FILE in openandroiddex-scrcpy).
const DISPLAY_SCALE_FILE: &str = "/data/local/tmp/oadx-display-scale";

/// Tell the phone what a window coordinate is in pixels. Before the server
/// starts, which reads it once as it makes the display, and again whenever the
/// window lands on a screen of another scale, which its watcher picks up.
fn write_display_scale(app: &AppHandle, serial: &str, scale: f64) {
    let cmd = format!("echo {scale} > {DISPLAY_SCALE_FILE}");
    if let Err(e) = adb::run_adb(app, &["-s", serial, "shell", &cmd]) {
        log::warn!("{serial}: could not write the display scale: {e}");
    }
}

/// Keep a display that follows its window sharp, and the same size to look
/// at, when the window is dragged onto a screen of another scale — a Retina
/// laptop beside a 1x monitor is the everyday case.
///
/// The window asks for no new size when it moves: its size in points is the
/// same. On macOS the phone is told the new pixels-per-point and applies it to
/// the last size the window asked for; on Windows the window's pixel size
/// changes by itself as Windows rescales it. Either way the density goes with
/// the scale — one dp stays one point — at the phone's Display size preset.
///
/// Asked once a second, of the window server (embed::window_scale): there is
/// no event for it that this process could hear.
#[allow(clippy::too_many_arguments)]
fn watch_window_scale(
    app: AppHandle,
    serial: String,
    key: String,
    pid: u32,
    child: Arc<Mutex<Child>>,
    stop: Arc<AtomicBool>,
    display_id: Arc<AtomicI32>,
    density: Arc<AtomicI32>,
    base_dpi: Arc<AtomicI32>,
) {
    thread::spawn(move || {
        let mut scale = f64::from(base_dpi.load(Ordering::SeqCst).max(80)) / 160.0;
        loop {
            thread::sleep(Duration::from_secs(1));
            if stop.load(Ordering::SeqCst)
                || child.lock().unwrap().try_wait().map(|s| s.is_some()).unwrap_or(true)
            {
                return;
            }
            let display = display_id.load(Ordering::SeqCst);
            let Some(now) = crate::embed::window_scale(&app, pid) else {
                continue;
            };
            if display < 0 || (now - scale).abs() < 0.01 {
                continue;
            }
            log::info!("scrcpy [{key}] the window is on a {now}x screen now (was {scale}x)");
            if cfg!(target_os = "macos") {
                write_display_scale(&app, &serial, now);
            }
            if move_density(&app, &serial, &key, display, now, &density, &base_dpi) {
                scale = now;
            }
        }
    });
}

/// The density of a desktop whose window is on a screen of `scale` now: the
/// Default goes with the scale — one dp stays one point — at the phone's
/// Display size preset. True once the phone has it.
fn move_density(
    app: &AppHandle,
    serial: &str,
    key: &str,
    display: i32,
    scale: f64,
    density: &AtomicI32,
    base_dpi: &AtomicI32,
) -> bool {
    let base = ((160.0 * scale).round() as i32).clamp(80, 960);
    let factor = display::size_factor(app, serial, (1920, 1080));
    let dpi = ((f64::from(base) * factor).round() as i32).clamp(DPI_MIN, DPI_MAX);
    match adb::run_adb(app, &["-s", serial, "shell", &format!("wm density {dpi} -d {display}")]) {
        Ok(_) => {
            base_dpi.store(base, Ordering::SeqCst);
            density.store(dpi, Ordering::SeqCst);
            true
        }
        Err(e) => {
            log::warn!("scrcpy [{key}] could not move the density to {dpi}: {e}");
            false
        }
    }
}

/// The desktop's own window moved to a screen of another scale (dex.rs): the
/// density goes with it, as `watch_window_scale` moves it for scrcpy's. The
/// window has told the phone its new size in pixels itself.
pub(crate) fn desktop_scale_changed(app: &AppHandle, key: &str, scale: f64) {
    let (serial, display, density, base_dpi) = {
        let state = app.state::<MirrorState>();
        let map = state.0.lock().unwrap();
        let Some(s) = map.get(key) else { return };
        (s.serial.clone(), s.display_id.load(Ordering::SeqCst), s.density.clone(), s.base_dpi.clone())
    };
    if display < 0 || !(scale.is_finite() && scale > 0.0) {
        return;
    }
    let was = f64::from(base_dpi.load(Ordering::SeqCst).max(80)) / 160.0;
    if (was - scale).abs() < 0.01 {
        return;
    }
    log::info!("dex [{key}] the window is on a {scale}x screen now (was {was}x)");
    move_density(app, &serial, key, display, scale, &density, &base_dpi);
}

/// The Default density of this phone's running desktop, if it has one — the
/// base the launcher builds its Display size presets on (adb_start_launcher).
pub(crate) fn desktop_base_dpi(app: &AppHandle, serial: &str) -> Option<i32> {
    let state = app.state::<MirrorState>();
    let map = state.0.lock().unwrap();
    map.get(&format!("{serial}|desktop"))
        .map(|s| s.base_dpi.load(Ordering::SeqCst))
        .filter(|d| *d > 0)
}

/// The dpi baked into `--new-display=WxH/DPI`.
fn requested_density(opts: &MirrorOptions) -> Option<i32> {
    opts.new_display
        .as_deref()?
        .split('/')
        .nth(1)?
        .trim()
        .parse()
        .ok()
}

/// How long a virtual display may take to appear before the attempt counts as
/// failed. It normally takes under two seconds — the rest is headroom for a
/// cold phone pushing the scrcpy server over a slow cable.
const DISPLAY_TIMEOUT: Duration = Duration::from_secs(30);
/// When to stop believing the log line and go look for the display ourselves.
const PROBE_AFTER: Duration = Duration::from_secs(6);

/// Virtual displays on the phone that belong to scrcpy, by id.
///
/// scrcpy names its display "scrcpy", which `dumpsys display` prints inside
/// the `DisplayInfo{…}` for it. Dead sessions leave theirs behind, so callers
/// compare against a baseline rather than trusting a single reading.
fn probe_scrcpy_displays(app: &AppHandle, serial: &str) -> HashSet<i32> {
    adb::run_adb_quiet(
        app,
        &[
            "-s",
            serial,
            "shell",
            // `|| true`: no match is the expected answer most of the time, and
            // grep's exit 1 for it would be logged as a failed adb call
            "dumpsys display | grep -i -E 'scrcpy|Open Android DeX' | grep -oE 'displayId=[0-9]+' || true",
        ],
    )
    .unwrap_or_default()
    .lines()
    .filter_map(|l| l.trim().strip_prefix("displayId=")?.parse().ok())
    .collect()
}

/// Make sure a session that cannot produce a display says so.
///
/// The display id used to have exactly one source — scrcpy printing
/// `New display: … (id=N)` on a pipe — and the UI simply waited for it, so
/// anything that stopped that line from arriving left the app on "Creating
/// virtual display…" for as long as the user was willing to look at it.
///
/// The commercial DeX solves this by patching its scrcpy fork to report
/// `display_created` / `display_error` back to the PC over a socket. We ship
/// stock scrcpy, so the second source is the phone itself: `dumpsys display`
/// knows about the display whether or not we saw the line announcing it.
/// Failing both, the attempt is killed so the monitor can report it.
#[allow(clippy::too_many_arguments)]
fn spawn_display_watchdog(
    app: AppHandle,
    opts: MirrorOptions,
    key: String,
    display_id: Arc<AtomicI32>,
    density: Arc<AtomicI32>,
    child: Arc<Mutex<Child>>,
    output: OutputLog,
    stop: Arc<AtomicBool>,
) {
    if opts.new_display.is_none() {
        return; // mirroring the phone's own screen: no display to wait for
    }
    thread::spawn(move || {
        let serial = opts.serial.clone();
        // Taken now, while scrcpy is still starting: whatever is here already
        // belongs to an earlier session.
        let baseline = probe_scrcpy_displays(&app, &serial);
        log::debug!("scrcpy [{key}] scrcpy displays already on the phone: {baseline:?}");

        let started = Instant::now();
        let mut next_probe = PROBE_AFTER;
        loop {
            if stop.load(Ordering::SeqCst) || display_id.load(Ordering::SeqCst) >= 0 {
                return; // stopped, or the log line arrived as it should
            }
            let exited = child
                .lock()
                .unwrap()
                .try_wait()
                .map(|s| s.is_some())
                .unwrap_or(true);
            if exited {
                // the monitor reports it; this is the detail behind that report
                log::error!(
                    "scrcpy [{key}] exited after {}ms without creating a display. Last output:\n{}",
                    started.elapsed().as_millis(),
                    output_tail(&output, 30)
                );
                return;
            }
            if started.elapsed() >= DISPLAY_TIMEOUT {
                break;
            }
            if started.elapsed() >= next_probe {
                next_probe += Duration::from_secs(3);
                let now = probe_scrcpy_displays(&app, &serial);
                if let Some(id) = now.difference(&baseline).copied().max() {
                    log::warn!(
                        "scrcpy [{key}] never announced its display — found id={id} in dumpsys \
                         after {}ms, carrying on with it",
                        started.elapsed().as_millis()
                    );
                    display_id.store(id, Ordering::SeqCst);
                    let _ = app.emit(
                        "mirror:display",
                        serde_json::json!({
                            "sessionKey": key, "serial": serial, "displayId": id
                        }),
                    );
                    // Same question as the announced path asks, and the answer
                    // outlives this thread, which returns on the next line.
                    let (a2, s2, k2) = (app.clone(), serial.clone(), key.clone());
                    thread::spawn(move || warn_if_display_untrusted(&a2, &s2, &k2, id));
                    return;
                }
                log::debug!(
                    "scrcpy [{key}] still no display after {}ms (phone reports {now:?})",
                    started.elapsed().as_millis()
                );
            }
            thread::sleep(Duration::from_millis(200));
        }

        log::error!(
            "scrcpy [{key}] is running but no virtual display appeared within {}s — giving up on \
             this attempt (density asked for: {}). Last output:\n{}",
            DISPLAY_TIMEOUT.as_secs(),
            density.load(Ordering::SeqCst),
            output_tail(&output, 30)
        );
        // Killed without setting `stop`, so the monitor reads it as a failed
        // attempt rather than a deliberate shutdown — and killed outright
        // rather than through `shut_down`, for that same reason: SIGTERM lets
        // scrcpy exit 0, and a zero exit is precisely what tells `monitor`
        // nothing went wrong. The degraded retry that turns "the desktop never
        // came up" into "it came up without audio" hangs off that failure.
        let _ = child.lock().unwrap().kill();
    });
}

/// The session is still set up on the phone: its watchdog has not undone it.
///
/// Asked of the caption service, which every set-up turns on and every undo —
/// the watchdog's, and the PC's own — turns off. Not of the window daemon: an
/// adbd restart kills that without undoing anything (it is how Samsung switches
/// USB mode, which it does by itself right after a plug-in), and the session's
/// enforcer starts it again by itself.
fn desktop_still_set_up(app: &AppHandle, serial: &str) -> bool {
    crate::adb::run_adb(app, &["-s", serial, "shell", "settings get secure enabled_accessibility_services"])
        .is_ok_and(|out| out.contains("CaptionService"))
}

/// Block until the device is back in "device" state, or timeout / user stop.
fn wait_for_device(app: &AppHandle, serial: &str, stop: &AtomicBool) -> bool {
    let deadline = Instant::now() + Duration::from_secs(90);
    while Instant::now() < deadline {
        if stop.load(Ordering::SeqCst) {
            return false;
        }
        if let Ok(state) = adb::run_adb(app, &["-s", serial, "get-state"]) {
            if state.trim() == "device" {
                return true;
            }
        }
        thread::sleep(Duration::from_secs(2));
    }
    false
}

/// One standard task on a display, in dump order (topmost first).
struct TaskRec {
    id: u32,
    fullscreen: bool,
    freeform: bool,
    visible: bool,
    /// On One UI, closed-but-cached tasks stay attached to the display with
    /// visible=false; a freshly launching task is visibleRequested=true
    /// before its first frame. visible||visible_requested therefore means
    /// "actually on (or coming to) the screen".
    visible_requested: bool,
    /// "package/Activity" ("" when not found in the dump)
    comp: String,
    /// The task's ROOT activity ("" when not found). `comp` is the TOP one, so
    /// a task of OURS that is hosting a system dialog — the widget bind
    /// confirmation, which the launcher opens in a task of its own — reads as
    /// com.android.settings, and every "leave our own windows alone" filter
    /// here would stop matching it halfway through the flow.
    root_comp: String,
    bounds: Option<(i32, i32, i32, i32)>,
    /// The top activity asks to be portrait. The desktop display sets
    /// ignoreOrientationRequest, so the window manager hands such an app a
    /// landscape rect anyway and it stretches its phone layout across it —
    /// Bolt's sign-in screen becomes one full-width field above a full-width
    /// button. These get a phone-shaped window instead.
    portrait: bool,
}

impl TaskRec {
    fn package(&self) -> &str {
        self.comp.split('/').next().unwrap_or("")
    }

    /// Ours: the desktop itself, or a window we opened to host a system detour.
    fn is_ours(&self) -> bool {
        self.comp.starts_with(adb::LAUNCHER_PACKAGE)
            || self.root_comp.starts_with(adb::LAUNCHER_PACKAGE)
    }
}

/// "package/Activity" out of an ActivityRecord line, if it looks like one.
fn record_comp(line: &str) -> Option<String> {
    let pos = line.find(" u0 ")?;
    let comp = line[pos + 4..]
        .split_whitespace()
        .next()
        .unwrap_or("")
        .trim_end_matches('}');
    let safe = comp.contains('/')
        && comp
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '/' | '_' | '$'));
    safe.then(|| comp.to_string())
}

/// Parse `dumpsys activity activities`: every standard task of every display, topmost
/// first, with windowing mode, visibility, component and bounds.
///
/// Deliberately not scoped to the desktop display, even though that is the only one whose
/// geometry we manage. Telling "this app was closed" apart from "another display took
/// this window" needs to see where the task went, and a display-scoped parse cannot —
/// a claimed window simply stops appearing. Splitting the one dump we already fetch costs
/// nothing over parsing a single section of it.
fn parse_all_display_tasks(dump: &str) -> Vec<(i32, Vec<TaskRec>)> {
    let mut out = Vec::new();
    let root = root_task_indent(dump);
    // Everything before the first header belongs to no display.
    for chunk in dump.split("Display #").skip(1) {
        // "18 (activities from top to bottom):" — take the leading run of digits, which
        // is also what keeps display 1 from matching display 18.
        let Some(id) = chunk
            .split(|c: char| !c.is_ascii_digit())
            .next()
            .and_then(|s| s.parse::<i32>().ok())
        else {
            continue;
        };
        out.push((id, parse_section(chunk, root)));
    }
    out
}

/// The indent the dump prints ROOT tasks at: that of the first task line under any
/// display. It is the same for every display (2 on Android 15, 4 on One UI 4), which is
/// what lets it tell a display's own tasks from the listing after the last display —
/// see parse_section.
fn root_task_indent(dump: &str) -> Option<usize> {
    let body = &dump[dump.find("Display #")?..];
    body.lines().find_map(|line| {
        let t = line.trim_start();
        t.starts_with("* Task{").then(|| line.len() - t.len())
    })
}

/// One display's slice of the dump → its ROOT standard tasks, topmost first.
///
/// "Root" is load-bearing, and indentation is the only thing that says so. One UI prints
/// each display's root tasks at one indent level and their children deeper — but it also
/// prints, inside `Task{#3 name=SplitRoot}` at the END of display 0, a SECOND copy of the
/// entire device's task list, including tasks that live on other displays. Counting those
/// makes every desktop window look like it is also on the phone, which reads as the phone
/// having claimed all of them at once.
///
/// So a task line is only taken at the root indent. The SplitRoot container is itself
/// `type=undefined` and skipped on its own merit; what this rejects is the standard tasks
/// nested two levels below it.
///
/// And the root indent is the DUMP's (`root`, from root_task_indent), not the section's.
/// After the last display the dump prints the window hierarchy, which lists every
/// display's tasks again two levels deeper, and the last section runs on into it. A
/// display with tasks of its own measured its indent from them and skipped that listing;
/// an EMPTY one — a desktop the moment it is created, before the launcher lands, printed
/// last — measured it from the listing and took the phone's tasks for its own. The
/// enforcer then re-launched the visible one onto the desktop (`am start --display`):
/// whatever app was open on the phone when a session started jumped to the monitor.
fn parse_section(section: &str, root: Option<usize>) -> Vec<TaskRec> {
    let mut out = Vec::new();
    let mut cur: Option<TaskRec> = None;
    let mut root_indent: Option<usize> = root;
    for line in section.lines() {
        let t = line.trim_start();
        if t.starts_with("* Task{") {
            let indent = line.len() - t.len();
            match root_indent {
                None => root_indent = Some(indent),
                Some(root) if indent > root => {
                    // a child task, or the SplitRoot echo of the whole device
                    if let Some(rec) = cur.take() {
                        out.push(rec);
                    }
                    continue;
                }
                _ => {}
            }
        }
        if t.starts_with("* Task{") {
            if let Some(rec) = cur.take() {
                out.push(rec);
            }
            cur = if t.contains("type=standard") {
                t.split('#')
                    .nth(1)
                    .and_then(|s| s.split(' ').next())
                    .and_then(|s| s.parse().ok())
                    .map(|id| TaskRec {
                        id,
                        fullscreen: t.contains("mode=fullscreen"),
                        freeform: t.contains("mode=freeform"),
                        visible: t.contains("visible=true"),
                        visible_requested: t.contains("visibleRequested=true"),
                        comp: String::new(),
                        root_comp: String::new(),
                        bounds: None,
                        portrait: false,
                    })
            } else {
                None
            };
        } else if let Some(rec) = cur.as_mut() {
            if rec.comp.is_empty() {
                if let Some(comp) = record_comp(t) {
                    rec.comp = comp;
                }
            }
            // The LAST Hist line of the task is its root activity — first-wins
            // above already took the top one. Only "* Hist" lines: the display
            // section also prints ResumedActivity/mFocusedApp records, which
            // would otherwise overwrite the root with the top again.
            if t.starts_with("* Hist") {
                if let Some(comp) = record_comp(t) {
                    rec.root_comp = comp;
                }
            }
            if let Some(idx) = t.find("requestedOrientation=") {
                // SCREEN_ORIENTATION_{PORTRAIT,SENSOR_PORTRAIT,REVERSE_PORTRAIT,
                // USER_PORTRAIT} all mean "I want to be tall"
                if t[idx..].contains("PORTRAIT") {
                    rec.portrait = true;
                }
            }
            if rec.bounds.is_none() {
                if let Some(idx) = t.find("mBounds=Rect(") {
                    // '-' kept so offscreen (negative) coordinates survive;
                    // the lone "-" separator token fails the parse harmlessly
                    let nums: Vec<i32> = t[idx..]
                        .split(|c: char| !c.is_ascii_digit() && c != '-')
                        .filter(|s| !s.is_empty())
                        .filter_map(|s| s.parse().ok())
                        .collect();
                    if nums.len() >= 4 {
                        rec.bounds = Some((nums[0], nums[1], nums[2], nums[3]));
                    }
                }
            }
        }
    }
    if let Some(rec) = cur.take() {
        out.push(rec);
    }
    out
}

/// "1920x1080" or "1920x1080/240" → (1920, 1080)
fn parse_display_size(s: &str) -> Option<(i32, i32)> {
    let (w, h) = s.split('/').next()?.split_once('x')?;
    Some((w.trim().parse().ok()?, h.trim().parse().ok()?))
}

// ── Per-device display size ─────────────────────────────────────────────
// The launcher reconciles the display's density against its stored choice
// AFTER it starts, which used to mean a couple of seconds of the phone's
// ~340dpi ("very large") desktop before the override landed — a visible
// zoom flash on every launch. The PC executes every density request, so it
// remembers the choice per phone and bakes it into `--new-display=WxH/DPI`:
// the display then renders at the right scale from the very first frame.
//
// Remembered as a multiple of the Default density rather than as a dpi
// (display::size_factor): the Default is the computer's, and the same phone
// on a Retina laptop and on a 1x monitor wants 320 and 160 for the same size.

/// The densities a desktop may be given — scrcpy's, the request pump's and
/// the launcher's bounds alike. Very large (1.5x) on a 3x screen is 720.
const DPI_MIN: i32 = 70;
const DPI_MAX: i32 = 960;

// ── Stream configuration (Settings → Scrcpy Config / Clipboard) ────────
// The in-desktop Settings window cannot run scrcpy, so it stores its choice
// on the phone (for the UI) and pushes it here through the launcher's request
// queue. This side is what actually applies it: the values are remembered
// across runs and baked into the command line of every desktop session,
// including the ones an auto-reconnect respawns.
//
// One flat string map, not a typed struct: the device sends `<key>.<value>`
// pairs and an unknown key from a newer launcher must be stored and ignored
// rather than break parsing of the whole file.

fn config_store_path(app: &AppHandle) -> Option<std::path::PathBuf> {
    app.path()
        .app_config_dir()
        .ok()
        .map(|d| d.join("stream-config.json"))
}

fn load_config(app: &AppHandle) -> HashMap<String, String> {
    config_store_path(app)
        .and_then(|p| std::fs::read_to_string(p).ok())
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn save_config(app: &AppHandle, map: &HashMap<String, String>) {
    let Some(path) = config_store_path(app) else {
        return;
    };
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if let Ok(json) = serde_json::to_string(map) {
        let _ = std::fs::write(path, json);
    }
}

/// One stored setting, for callers outside this module.
///
/// Read straight off disk on every call rather than cached. The gesture engine
/// is the only caller and asks a few times a minute, which buys it something
/// no other setting here has: a remapped gesture takes effect on the next
/// swipe instead of at the next session, because unlike the rest of this file
/// it is not a scrcpy argument.
pub fn config_value(app: &AppHandle, key: &str) -> Option<String> {
    load_config(app).remove(key)
}

/// Store one `<key>.<value>` pair from the launcher. `reset.all` clears
/// everything, which is how the Settings window's factory reset keeps this
/// side from outliving the phone-side copy it mirrors.
fn remember_config(app: &AppHandle, key: &str, value: &str) {
    let mut map = load_config(app);
    if key == "reset" {
        if !map.is_empty() {
            map.clear();
            save_config(app, &map);
        }
        return;
    }
    if map.get(key).map(String::as_str) == Some(value) {
        return;
    }
    map.insert(key.to_string(), value.to_string());
    save_config(app, &map);
}

/// Apply the stored stream configuration to a desktop session.
///
/// Desktop sessions only: an app-window session is a different beast and its
/// caller owns its options. Runs BEFORE `apply_stored_density`, because a
/// resolution change rewrites the `--new-display` base that the density is
/// then appended to.
/// The phone's API level, or None when it could not be asked.
///
/// None is deliberately NOT treated as "too old" by the one caller: a phone
/// that did not answer is usually a phone that is still settling after being
/// plugged in, and refusing a feature on that basis would make it depend on
/// timing.
fn device_sdk(app: &AppHandle, serial: &str) -> Option<u32> {
    crate::adb::run_adb(
        app,
        &["-s", serial, "shell", "getprop ro.build.version.sdk"],
    )
    .ok()
    .and_then(|out| out.trim().parse().ok())
}

fn apply_stored_config(app: &AppHandle, opts: &mut MirrorOptions) {
    if opts.app_package.is_some() || opts.new_display.is_none() {
        return;
    }
    let map = load_config(app);
    // Before the size: how large a display this phone's encoder takes is
    // remembered per codec.
    match map.get("codec").map(String::as_str) {
        Some("h264") => opts.video_codec = Some("h264".into()),
        Some("h265") => opts.video_codec = Some("h265".into()),
        Some("av1") => opts.video_codec = Some("av1".into()),
        _ => opts.video_codec = None, // "auto" or unset
    }
    // A resolution picked in Settings — trusted only if it parses, since a bad
    // value would take the desktop down — or else ("auto", or never picked)
    // the screen the app is on, at its own pixels (display.rs).
    // The display that follows its window where the host can (macOS, Windows),
    // else one fitted to the screen once.
    let picked = map.get("res").filter(|r| parse_display_size(r).is_some());
    opts.own_video = own_video_wanted(app, opts, &map);
    let fit = match picked {
        Some(_) => None,
        // A window of our own opens filling the screen the app is on, and its
        // display follows it from there (dex.rs) — scrcpy's flex is scrcpy's.
        None if opts.own_video => display::desktop_window_geometry(app),
        None => display::desktop_flex_geometry(app).or_else(|| {
            display::desktop_geometry(app, &opts.serial, opts.video_codec.as_deref())
        }),
    };
    match (picked, fit) {
        (Some(res), _) => {
            opts.new_display = Some(res.clone());
            opts.window = None;
            opts.base_dpi = None;
            opts.fitted = false;
            opts.flex = None;
        }
        (None, Some(g)) => {
            opts.new_display = Some(format!("{}x{}", g.width, g.height));
            opts.window = g.window;
            opts.base_dpi = Some(g.base_dpi);
            opts.fitted = true;
            opts.flex = if opts.own_video { None } else { g.flex };
        }
        // No screen to fit (none reported, or too small): the size the
        // frontend asked for, as before this existed.
        (None, None) => {
            opts.window = None;
            opts.base_dpi = None;
            opts.fitted = false;
            opts.flex = None;
        }
    }
    // A bit rate picked in Settings, or else scrcpy's 8 Mbps for 1080p scaled
    // to the most pixels this display will have. 0 is how the launcher says
    // "Automatic".
    let largest = fit
        .map(|g| g.largest)
        .or_else(|| {
            let (w, h) = opts.new_display.as_deref().and_then(parse_display_size)?;
            Some((w.max(0) as u32, h.max(0) as u32))
        });
    match map
        .get("bitrate")
        .and_then(|v| v.parse::<u32>().ok())
        .filter(|m| (1..=100).contains(m))
    {
        Some(mbps) => opts.video_bit_rate_mbps = mbps,
        None => {
            if let Some((w, h)) = largest {
                opts.video_bit_rate_mbps =
                    display::auto_bit_rate_mbps(w, h, display::is_wireless(&opts.serial));
            }
        }
    }
    if let Some(fps) = map.get("fps").and_then(|v| v.parse::<u32>().ok()) {
        if fps <= 240 {
            opts.max_fps = fps; // 0 = scrcpy's own default
        }
    }
    // An encoder belongs to exactly one codec, so it is only passed through
    // when the codec was pinned too — otherwise scrcpy rejects the pair and
    // the desktop never comes up.
    match map.get("encoder") {
        Some(name) if opts.video_codec.is_some() && name != "auto" && !name.is_empty() => {
            opts.video_encoder = Some(name.clone())
        }
        _ => opts.video_encoder = None,
    }
    // Audio never rides the desktop session any more. It lives in a companion
    // audio-only scrcpy process (see `reconcile_audio_session`), precisely so
    // that "where sound plays" — the taskbar's quick settings, and Settings →
    // Stream — can change LIVE, by cycling that small process, instead of
    // costing a desktop restart. The stored `audio`/`audiodup` keys are still
    // the source of truth; `stored_audio_forwarding` reads them for the
    // companion.
    opts.audio = false;
    opts.audio_playback = false;
    if let Some(clipboard) = map.get("clipboard") {
        opts.clipboard_autosync = clipboard != "off";
    }
    // Settings → Mouse & cursor → Pointer rendering. Like every other scrcpy
    // argument it only lands on a fresh session, which is why that card
    // carries the restart footer.
    match map.get("mouse").map(String::as_str) {
        Some("uhid") => opts.mouse_mode = Some("uhid".into()),
        _ => opts.mouse_mode = None, // "sdk" or unset — scrcpy's own default
    }
    // "Reduce quality" (Settings → Performance), the half that costs frames on
    // the WIRE rather than on the phone. Over Wi-Fi the encoder asking for more
    // bandwidth than the link can carry is what produces the stutter the mode
    // is meant to remove, and the bitrate is the one knob that lowers that
    // demand without moving the desktop underneath the user — the resolution
    // is deliberately left alone, because it is also the desktop's size and
    // the density is derived from it.
    //
    // A ceiling, not an override: a user who has already dialled the bitrate
    // below the cap meant it, and this must not raise it back up.
    //
    // Stored in the same per-PC (not per-serial) config as bitrate/fps/codec,
    // so with two phones attached the cap follows the last one to set it while
    // the animation scales stay per-phone. Consistent with how every other
    // stream setting already behaves; called out because the other half of
    // this same switch is not.
    if map.get("perf").map(String::as_str) == Some("on") {
        opts.video_bit_rate_mbps = opts.video_bit_rate_mbps.min(PERF_BIT_RATE_MBPS);
    }
}

/// Whether a desktop goes to this app's own window (dex.rs) — the default — or
/// to scrcpy's: picked in the phone's Settings (`video`), needed by a pointer
/// the phone draws itself (uhid is scrcpy's alone), or because this path has
/// already failed on this phone since the app started. `OPENDEX_VIDEO=scrcpy`
/// asks for scrcpy's from the command line.
fn own_video_wanted(app: &AppHandle, opts: &MirrorOptions, map: &HashMap<String, String>) -> bool {
    if opts.audio_only || opts.app_package.is_some() || opts.new_display.is_none() {
        return false;
    }
    if std::env::var("OPENDEX_VIDEO").is_ok_and(|v| v == "scrcpy")
        || map.get("video").map(String::as_str) == Some("scrcpy")
        || map.get("mouse").map(String::as_str) == Some("uhid")
    {
        return false;
    }
    !crate::dex::fell_back(&opts.serial) && crate::dex::bundled(app)
}

/// A bit rate picked in the phone's Settings, or "Reduce quality"'s cap, for
/// the desktop's own window — bits a second. None: the window's own, by the
/// display's size and the route, which the phone then holds to what gets
/// through.
pub(crate) fn picked_bitrate_bps(app: &AppHandle) -> Option<u32> {
    let map = load_config(app);
    let picked = map
        .get("bitrate")
        .and_then(|v| v.parse::<u32>().ok())
        .filter(|m| (1..=100).contains(m));
    let perf = map.get("perf").map(String::as_str) == Some("on");
    let mbps = match (picked, perf) {
        (Some(m), true) => m.min(PERF_BIT_RATE_MBPS),
        (Some(m), false) => m,
        (None, true) => PERF_BIT_RATE_MBPS,
        (None, false) => return None,
    };
    Some(mbps * 1_000_000)
}

/// The frame rate picked in the phone's Settings; 60 when it was left alone.
pub(crate) fn stored_fps(app: &AppHandle) -> u32 {
    load_config(app)
        .get("fps")
        .and_then(|v| v.parse::<u32>().ok())
        .filter(|f| (10..=120).contains(f))
        .unwrap_or(60)
}

/// Settings → Clipboard on the phone.
pub(crate) fn clipboard_shared(app: &AppHandle) -> bool {
    load_config(app).get("clipboard").map(|v| v != "off").unwrap_or(true)
}

/// Bitrate ceiling (Mbps) while "Reduce quality" is on. Half the 8 Mbps
/// default: enough for a desktop of text and windows, and low enough that a
/// Wi-Fi link with something else on it stops being the bottleneck.
const PERF_BIT_RATE_MBPS: u32 = 4;

/// The stored "where sound plays" choice, as (forward, duplicate).
///
/// Three states behind two booleans: (true, false) moves the sound to this
/// computer (scrcpy's default source DIVERTS — the handset goes silent),
/// (true, true) plays it on both, (false, _) leaves the phone alone. Defaults
/// to "both", a deliberate reversal of scrcpy's own behaviour and the
/// commonest answer to "there is no sound" — MUST match DEF_AUDIO /
/// DEF_AUDIO_DUP in the launcher's DexPrefs.
fn stored_audio_forwarding(app: &AppHandle) -> (bool, bool) {
    let map = load_config(app);
    let forward = map.get("audio").map(|v| v != "off").unwrap_or(true);
    let dup = map.get("audiodup").map(|v| v != "off").unwrap_or(true);
    (forward, dup)
}

/// The audio companion's whole command line, by way of MirrorOptions.
///
/// Everything visual is zeroed on purpose: `build_args` short-circuits on
/// `audio_only` and emits none of it, and `session_key` brands the session
/// "<serial>|audio". No auto-reconnect — when the phone drops, the desktop's
/// own reconnect brings this companion back (see `monitor`), which avoids
/// handing a process that can fail instantly (a refused audio source) an
/// unsupervised respawn loop.
fn audio_companion_options(serial: &str, dup: bool) -> MirrorOptions {
    MirrorOptions {
        serial: serial.to_string(),
        max_size: 0,
        video_bit_rate_mbps: 0,
        max_fps: 0,
        audio: true,
        stay_awake: false,
        keep_active: false,
        turn_screen_off: false,
        always_on_top: false,
        fullscreen: false,
        window_title: String::new(),
        auto_reconnect: false,
        app_package: None,
        new_display: None,
        vd_no_decorations: false,
        window_borderless: false,
        audio_playback: dup,
        freeform: false,
        mouse_bind: None,
        mouse_mode: None,
        video_codec: None,
        video_encoder: None,
        clipboard_autosync: true,
        audio_only: true,
        window: None,
        base_dpi: None,
        fitted: false,
        flex: None,
        own_video: false,
    }
}

/// Serialises the companion's whole lifecycle: reconciles against each other
/// (two quick settings taps must not race two stop/start pairs into one
/// session key), and — just as load-bearing — teardown against a reconcile in
/// flight. A reconcile checks "is the desktop live" at its top and then
/// blocks for seconds (an adb round-trip, a SIGTERM grace) before spawning;
/// `stop_audio_companion` and `kill_all` take this lock so that they run
/// either before that check (the reconcile then sees the desktop gone) or
/// after the spawn (they then find the companion in the map and stop it).
/// Without it, a companion spawned into that window would divert the phone's
/// sound to a computer showing nothing, with nothing left to stop it.
/// Process-wide rather than per-serial — this path runs on a settings tap,
/// never in a loop.
///
/// Locked with [`audio_lock`], never with a bare `.lock().unwrap()`.
static AUDIO_RECONCILE: Mutex<()> = Mutex::new(());

/// AUDIO_RECONCILE, poison ignored. The guarded state is `()`, so a panic in
/// one reconcile thread proves nothing about any invariant — but an unwrap
/// here would resurface that panic in whoever locks next, and one of those is
/// `kill_all` on the MAIN thread during app exit, where it would skip every
/// phone and host restore this app owes on the way out.
fn audio_lock() -> std::sync::MutexGuard<'static, ()> {
    AUDIO_RECONCILE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Make the audio companion match the stored config, live.
///
/// Runs whenever the answer could have changed: a desktop session coming up,
/// an `audio`/`audiodup` cfg row arriving from the launcher, a desktop
/// reconnect. Cycling a `--no-video` scrcpy takes about a second and touches
/// nothing the user is looking at — that is the whole trick that turns "on
/// the next session" into "now".
///
/// Blocking (adb round-trips, a SIGTERM grace): callers spawn it on a thread.
fn reconcile_audio_session(app: &AppHandle, serial: &str) {
    let _guard = audio_lock();
    let desktop_key = format!("{serial}|desktop");
    let audio_key = format!("{serial}|audio");

    let (forward, mut dup) = stored_audio_forwarding(app);

    // The companion accompanies a desktop; without one there is nothing to
    // keep silent-but-streaming, and a leftover companion would divert the
    // phone's sound to a computer showing nothing.
    let desktop_live = {
        let state = app.state::<MirrorState>();
        let map = state.0.lock().unwrap();
        map.get(&desktop_key)
            .map(|s| {
                s.child
                    .lock()
                    .unwrap()
                    .try_wait()
                    .map(|st| st.is_none())
                    .unwrap_or(false)
            })
            .unwrap_or(false)
    };
    let mut want = desktop_live && forward;

    if want {
        // Both of scrcpy's audio paths have an Android floor, and asking past
        // it is not a degraded session — it is the companion exiting on its
        // command line. --audio-dup needs playback capture (13); any audio
        // forwarding at all needs 11. A phone that did not answer is treated
        // as able: it is usually still settling, and the worst case is a
        // companion that exits with its reason in the log.
        let sdk = device_sdk(app, serial);
        if dup && sdk.is_some_and(|s| s < 33) {
            log::info!(
                "{serial}: Android 12 or older — sound will move to this computer rather \
                 than playing on both, which is all scrcpy can do below 13"
            );
            dup = false;
        }
        if sdk.is_some_and(|s| s < 30) {
            log::info!("{serial}: Android 10 or older — scrcpy cannot forward audio at all");
            want = false;
        }
    }

    // What is running now, if anything — and is it already the right shape?
    let current = {
        let state = app.state::<MirrorState>();
        let map = state.0.lock().unwrap();
        map.get(&audio_key).and_then(|s| {
            let live = s
                .child
                .lock()
                .unwrap()
                .try_wait()
                .map(|st| st.is_none())
                .unwrap_or(false);
            live.then_some(s.audio_playback)
        })
    };

    match (current, want) {
        (Some(running_dup), true) if running_dup == dup => return, // already right
        (None, false) => return,                                   // already absent
        (Some(_), _) => {
            // Wrong shape, or no longer wanted: stop it. stop_mirror sets the
            // intent flag first, so the companion's monitor reads the exit as
            // deliberate and does not report a failure.
            let _ = stop_mirror(app.clone(), audio_key.clone());
            // A SIGKILL after an ignored SIGTERM returns before the exit is
            // reaped; give it a beat so the restart below does not trip
            // start_mirror's duplicate-key check on a corpse.
            for _ in 0..20 {
                let gone = {
                    let state = app.state::<MirrorState>();
                    let map = state.0.lock().unwrap();
                    map.get(&audio_key)
                        .map(|s| {
                            s.child
                                .lock()
                                .unwrap()
                                .try_wait()
                                .map(|st| st.is_some())
                                .unwrap_or(true)
                        })
                        .unwrap_or(true)
                };
                if gone {
                    break;
                }
                thread::sleep(Duration::from_millis(50));
            }
        }
        (None, true) => {}
    }
    if !want {
        log::info!("audio [{serial}] sound stays on the phone — no companion");
        return;
    }

    // The liveness check at the top is seconds old by now (device_sdk, the
    // stop grace). Teardown holds AUDIO_RECONCILE too, so a spawn past a dead
    // desktop would still be caught and stopped — this re-check just keeps
    // that from being a spawn at all when the desktop ended mid-reconcile.
    let desktop_still_live = {
        let state = app.state::<MirrorState>();
        let map = state.0.lock().unwrap();
        map.get(&desktop_key)
            .map(|s| {
                s.child
                    .lock()
                    .unwrap()
                    .try_wait()
                    .map(|st| st.is_none())
                    .unwrap_or(false)
            })
            .unwrap_or(false)
    };
    if !desktop_still_live {
        log::info!("audio [{serial}] the desktop ended mid-reconcile — no companion");
        return;
    }

    match start_mirror(app.clone(), audio_companion_options(serial, dup)) {
        Ok(_) => log::info!(
            "audio [{serial}] forwarding to this computer ({})",
            if dup { "phone keeps playing too" } else { "phone goes silent" }
        ),
        Err(e) => log::warn!("audio [{serial}] companion did not start: {e}"),
    }
}

/// `reconcile_audio_session` from a thread that must not block (the request
/// pump, start_mirror's caller).
fn reconcile_audio_session_bg(app: &AppHandle, serial: &str) {
    let app = app.clone();
    let serial = serial.to_string();
    thread::spawn(move || reconcile_audio_session(&app, &serial));
}

/// Stop the audio companion if one is up — the desktop it accompanied is
/// going away. Quiet when there is none: that is the common case whenever
/// sound was left on the phone.
///
/// Takes AUDIO_RECONCILE, and that is the point: a reconcile in flight when
/// the desktop ends is about to spawn a companion this function would
/// otherwise not see. Behind the lock it runs after that spawn and stops it —
/// or before the reconcile's own liveness check, which then answers "gone".
fn stop_audio_companion(app: &AppHandle, serial: &str) {
    let _guard = audio_lock();
    let audio_key = format!("{serial}|audio");
    let present = {
        let state = app.state::<MirrorState>();
        let present = state.0.lock().unwrap().contains_key(&audio_key);
        present
    };
    if present {
        let _ = stop_mirror(app.clone(), audio_key);
    }
}

/// The "Default" display-size preset of a resolution picked in Settings:
/// 160dpi at 1080p, scaled by resolution, so the desktop is 1080 dp tall —
/// MUST match SettingsActivity.defaultDpi in the launcher. A display fitted to
/// the screen has the computer's own Default instead (display::geometry).
fn default_density(size: &str) -> i32 {
    parse_display_size(size)
        .map(|(w, h)| (160f32 * w.min(h) as f32 / 1080f32).round() as i32)
        .unwrap_or(160)
}

/// Bake the remembered (or default) display size into a desktop session's
/// `--new-display`, as a dpi: the session's Default times the phone's preset.
/// Re-run before every (re)spawn so a size changed mid-session survives an
/// auto-reconnect without a zoom flash. Any dpi we previously appended is
/// replaced.
fn apply_stored_density(app: &AppHandle, opts: &mut MirrorOptions) {
    if opts.app_package.is_some() {
        return;
    }
    let Some(nd) = &opts.new_display else {
        return;
    };
    let base = nd.split('/').next().unwrap_or(nd).to_string();
    let base_dpi = opts.base_dpi.unwrap_or_else(|| default_density(&base));
    // A dpi remembered before presets were multiples meant one at the
    // resolution then in force.
    let legacy_res = load_config(app)
        .get("res")
        .and_then(|r| parse_display_size(r))
        .unwrap_or((1920, 1080));
    let factor = display::size_factor(app, &opts.serial, legacy_res);
    let dpi = ((f64::from(base_dpi) * factor).round() as i32).clamp(DPI_MIN, DPI_MAX);
    opts.new_display = Some(format!("{base}/{dpi}"));
    opts.base_dpi = Some(base_dpi);
}

/// Height of the launcher taskbar in px for a given display density (52dp).
fn taskbar_px(density: i32) -> i32 {
    if density > 0 {
        52 * density / 160
    } else {
        104
    }
}

/// The little bit of state the task poller and the request pump both touch.
///
/// The two run on separate threads (and separate adb shells) because
/// draining the launcher's request queue costs ~1s per pass — `content` is
/// a Java shell tool, so every invocation boots an `app_process` VM — while
/// reading the task list costs ~70ms. Sharing one loop made the taskbar
/// (which mirrors the task list) lag a full second behind the phone.
#[derive(Default)]
pub struct Shared {
    /// Whether we put the PC-side scrcpy window into fullscreen (taskbar ⛶).
    /// Written by the request pump, read by the broadcaster.
    fs_on: AtomicBool,
    /// Set by the request pump to force the next broadcast, so a state
    /// change it made reaches the taskbar without waiting for the periodic
    /// re-broadcast.
    resync: AtomicBool,
    /// Packages the user asked to maximize/restore from the taskbar's app
    /// menu, or with a touchpad gesture. Filled by the request pump and the
    /// gesture engine, drained by the task poller — that is the side that
    /// knows task ids, windowing modes and bounds.
    pub(crate) window_reqs: Mutex<Vec<String>>,
    /// Set by the request pump when a `cursor` request has just added the
    /// pointer-speed row to the restore snapshot.
    ///
    /// The watchdog string is read ONCE at session start (see
    /// `spawn_freeform_enforcer`) and armed into the on-device daemon. A row
    /// that appears afterwards is therefore absent from the armed copy, and a
    /// cable pulled later would leave the pointer speed behind. This tells the
    /// enforcer to read it again before the next arm.
    undo_stale: AtomicBool,
}

impl Shared {
    /// Is the desktop's scrcpy window fullscreen right now?
    ///
    /// Our own belief, not the window's: it is set by whoever last changed it
    /// and is what the taskbar's icon is drawn from. Escape reads it to decide
    /// whether the key is worth taking, and then asks the window itself rather
    /// than trusting this — see `embed::exit_fullscreen`.
    pub(crate) fn is_fullscreen(&self) -> bool {
        self.fs_on.load(Ordering::SeqCst)
    }

    /// Record the window's new fullscreen state and make sure the taskbar's ⛶
    /// icon hears about it without waiting for the periodic re-broadcast.
    ///
    /// The two writes belong together: leaving one out is how the button ends
    /// up lying about the state until the next press.
    pub(crate) fn record_fullscreen(&self, on: bool) {
        self.fs_on.store(on, Ordering::SeqCst);
        self.resync.store(true, Ordering::SeqCst);
    }
}

/// What one window on the desktop should look like, and whether the command
/// that gets it there has landed yet.
struct WinState {
    /// true = should fill the display above the taskbar
    maxed: bool,
    /// bounds to come back to when it is restored
    windowed: (i32, i32, i32, i32),
    /// Set while our transition is in flight, and the reason the maximize
    /// toggle stays in sync with the user.
    ///
    /// One caption press is seen by the poller many times over: One UI puts
    /// the task in fullscreen mode for a moment, and some apps re-assert
    /// fullscreen repeatedly (Brave does it for ~a minute). Counting each
    /// sighting as a press is what used to make a single click maximize and
    /// then immediately restore. While this is set, screen-filling sightings
    /// are absorbed rather than counted; if the task still has not reached
    /// the target when it expires we re-issue the SAME target instead of
    /// flipping, so a stubborn app converges instead of oscillating.
    pending: Option<Instant>,
    /// Re-issues of the current target. Capped so an app that simply will
    /// not take our bounds cannot turn into an endless `am start` storm.
    attempts: u8,
    /// Whether this task has been seen as a window (freeform) since we began
    /// shaping it. One that never has, after a full round of re-launches, is
    /// one the platform keeps full screen — see `Enforcer::unshapeable`.
    seen_freeform: bool,
}

/// How long to wait for a transition to land before re-issuing it.
const PENDING_TIMEOUT: Duration = Duration::from_millis(2500);
/// How often the phone is asked how big the file being copied onto it has
/// grown. One `stat` is ~30ms of the tick's budget and only runs while a
/// transfer is in flight; at this rate the bar still moves smoothly.
const STAT_EVERY: Duration = Duration::from_millis(400);
/// Give up re-issuing after this many tries (see WinState::attempts).
const MAX_APPLY_ATTEMPTS: u8 = 6;
/// Whether a window that has left the desktop is dragged back onto it.
///
/// OFF by default, and that default is a decision rather than caution. When the phone
/// takes one of our tasks it is nearly always because the user tapped that app ON THE
/// PHONE, and "an app I open on the phone opens there and stays" is the behaviour asked
/// for. Pulling it back turns that into "it opens on the phone and then jumps to the
/// monitor", which is a worse bug than the one it was meant to fix.
///
/// The switch stays because the opposite platform behaviour is also possible — a phone
/// launch that merely fronts our task in place, leaving the phone showing nothing — and
/// on a device where the task-move log shows THAT, taking the window back is right.
/// Run with `OPENDEX_RECLAIM=1` to try it.
fn reclaim_enabled() -> bool {
    std::env::var("OPENDEX_RECLAIM").is_ok_and(|v| v == "1")
}

/// How long the on-device watchdog waits, hearing nothing from here, before it
/// concludes the PC is gone and undoes the session itself.
///
/// Ten seconds: a phone unplugged mid-session should be its own again about
/// as soon as someone looks at it — it was a minute, and that minute was spent
/// in DeX's settings with no DeX (2026-10-20, at the user's request). What
/// keeps a briefly quiet session from being torn down is the margin below
/// (five re-arms missed before it acts), the watchdog's second condition (the
/// desktop display gone too), and a deliberate restart arming for longer
/// first ([`RESTART_TTL_SECS`]).
const WATCHDOG_TTL_SECS: u32 = 10;
/// Enforcer ticks between re-arms. The tick is ~100ms plus ~70ms of dump, so
/// this is roughly every 2 seconds: five to a TTL.
const ARM_EVERY_TICKS: u32 = 12;
/// What a restart the desktop asked for arms the watchdog with before the
/// session goes: the old one is torn down and the new one spawned with no
/// enforcer re-arming in between, and that gap must not read as a pulled cable.
const RESTART_TTL_SECS: u32 = 60;

/// How many times one steal of one window is fought before letting it go.
///
/// Deliberately small. The budget is refunded the moment the window is seen back home, so
/// this bounds a FAILING reclaim — a task the daemon cannot move, or one the phone
/// re-takes instantly — not the number of steals a session survives.
const MAX_RECLAIM_ATTEMPTS: u8 = 3;

/// Window managers round and clamp bounds, so "did our resize land" has to
/// allow a little slack rather than demand an exact rect.
fn near(a: (i32, i32, i32, i32), b: (i32, i32, i32, i32)) -> bool {
    let d = |x: i32, y: i32| (x - y).abs() <= 8;
    d(a.0, b.0) && d(a.1, b.1) && d(a.2, b.2) && d(a.3, b.3)
}

/// Freeform enforcer (desktop sessions): apps started from the in-desktop
/// home open fullscreen; relaunching the same component onto the display
/// with `--windowingMode 5` re-parents the task into a freeform window
/// (verified: same task, state kept). Windows are never left in true
/// fullscreen — a maximized window fills the display above the taskbar
/// (pin w-4).
///
/// It also feeds the launcher's taskbar: every tick it broadcasts the
/// open-apps set. Device I/O runs over one persistent adb shell so a poll
/// costs one roundtrip instead of a process spawn.
struct Enforcer {
    app: AppHandle,
    key: String,
    display_size: Option<(i32, i32)>,
    /// The display follows its window, so its size changes under the session.
    flex: bool,
    /// The display just changed size: maximised windows are fitted to the new one.
    refit: bool,
    /// When a display that follows its window was last measured on the phone.
    next_size_check: Instant,
    shell: ShellSession,
    /// maximize/restore state per task on this display
    wins: HashMap<u32, WinState>,
    /// Task id -> package, for every task ever seen on this display: the ledger of which
    /// windows are the desktop's. See `reclaim_pass`.
    owned: HashMap<u32, String>,
    /// Reclaim attempts per task, so a window that will not come back is not chased on
    /// every poll for the rest of the session.
    reclaims: HashMap<u32, u8>,
    /// Tasks this display keeps full screen however often they are re-launched in
    /// freeform: an app that will not resize, on a display that will not force it
    /// (YouTube on a Galaxy A13). Left alone until they become a window some other
    /// way, leave the display, or are asked for from the taskbar menu. The parking
    /// fallback used to re-arm itself for these, which re-launched the app every
    /// PENDING_TIMEOUT for as long as it was on screen.
    unshapeable: HashSet<u32>,
    /// Which display each task was on at the previous poll — the instrument behind
    /// `track_moves`.
    seen_on: HashMap<u32, i32>,
    /// Socket to the shell-uid daemon. Only `reclaim_pass` uses it, and only once
    /// something has actually gone missing, so a device without the daemon never pays
    /// for it.
    wm: crate::wm::WmClient,
    last_display: i32,
    /// The phone screen's width/height, so a portrait-locked app can be
    /// given a window the shape it was designed for. Read once per session.
    phone_aspect: f32,
    default_freeform: bool,
    /// The `settings` chain that undoes this phone's desktop profile, read once
    /// per session and re-sent to the daemon on a timer. Empty when there is
    /// nothing to put back.
    undo_globals: String,
    /// When the daemon was last started again because it had gone (see the
    /// re-arm in `tick`), so a phone that keeps losing it is not hammered.
    wmd_restarted_at: Option<Instant>,
    /// last broadcast payload, to skip redundant broadcasts
    last_pkgs: Option<String>,
    /// Same, for the file-transfer HUD: its state is broadcast only when it
    /// changes, and never re-sent periodically — a drop is an event, not a
    /// state the launcher has to be able to resync to.
    last_transfer: Option<String>,
    tseq: u32,
    /// Next time the phone may be asked how big the file in flight has grown.
    next_stat: Instant,
    /// broadcast sequence number: broadcasts run backgrounded and may
    /// deliver out of order; the launcher drops stale ones by seq
    bseq: u32,
    ticks: u32,
    shared: Arc<Shared>,
}

impl Enforcer {
    /// Run a device shell command, logging (not propagating) failures.
    fn run(&mut self, cmd: &str) -> Option<String> {
        match self.shell.run(&self.app, cmd) {
            Ok(out) => Some(out),
            Err(e) => {
                log::warn!("freeform-enforcer [{}] shell: {e}", self.key);
                None
            }
        }
    }

    /// Fire-and-forget: backgrounded on the device so a slow am/broadcast
    /// never stalls the poll loop. Returns false when the shell was
    /// unreachable and the command never left the PC.
    ///
    /// The background `&` MUST stay inside the outer subshell: the shell
    /// session appends `; echo <sentinel>` to every line, and a top-level
    /// trailing `&` would make that `&;` — a syntax error that kills the
    /// whole (non-interactive) shell. That exact bug once made every
    /// broadcast/force-stop/keyevent silently die for days.
    fn run_bg(&mut self, cmd: &str) -> bool {
        self.run(&format!("(({cmd}) >/dev/null 2>&1 &)")).is_some()
    }

    fn default_windowed(&self) -> (i32, i32, i32, i32) {
        let (w, h) = self.display_size.unwrap_or((1920, 1080));
        (w / 5, h / 8, w * 4 / 5, h * 8 / 9)
    }

    fn resize(&mut self, task: u32, r: (i32, i32, i32, i32)) {
        self.run_bg(&format!(
            "am task resize {task} {} {} {} {}",
            r.0, r.1, r.2, r.3
        ));
    }

    /// Does this rect cover the display? Our maximized pin is deliberately
    /// 4px narrower so it does NOT, which is how a window we maximized is
    /// told apart from one the user just asked to maximize.
    ///
    /// `l <= 4` keeps a merely-dragged-wide window from counting.
    fn fills(&self, b: (i32, i32, i32, i32)) -> bool {
        let Some((w, _)) = self.display_size else {
            return false;
        };
        b.0 <= 4 && b.2 >= w - 1 && (b.2 - b.0) >= w * 9 / 10
    }

    /// A window shaped like the phone: full height above the taskbar, width
    /// from the phone's own aspect, centred. What a portrait-locked app gets
    /// instead of a landscape rect it would only stretch across.
    fn phone_rect(&self, density: i32) -> Option<(i32, i32, i32, i32)> {
        let (w, h) = self.display_size?;
        let ph = h - taskbar_px(density);
        let pw = ((ph as f32) * self.phone_aspect).round() as i32;
        // never wider than the display, and never so narrow it is unusable
        let pw = pw.clamp(320.min(w), w - 4);
        let x = (w - pw) / 2;
        Some((x, 0, x + pw, ph))
    }

    /// Is this rect already about the shape we would give a phone app? Keeps
    /// the launch path from re-applying the same geometry forever.
    fn is_phone_shaped(&self, b: (i32, i32, i32, i32), density: i32) -> bool {
        self.phone_rect(density).is_some_and(|p| near(b, p))
    }

    /// Where a task should sit when it is "as big as it gets": the whole
    /// display bar the taskbar, or a phone-shaped column for an app that
    /// asked to be portrait.
    fn grown_rect(&self, rec: &TaskRec, density: i32) -> Option<(i32, i32, i32, i32)> {
        if rec.portrait {
            self.phone_rect(density)
        } else {
            self.maxed_rect(density)
        }
    }

    /// Rect a maximized window occupies: the whole display bar the taskbar
    /// strip. The 4px width inset is load-bearing — it is how a window WE
    /// maximized is told apart from one One UI just made full-width, which
    /// is the signal we react to (see fullwidth_pass).
    fn maxed_rect(&self, density: i32) -> Option<(i32, i32, i32, i32)> {
        let (w, h) = self.display_size?;
        Some((0, 0, w - 4, h - taskbar_px(density)))
    }

    /// Log every task that changed display since the last poll.
    ///
    /// The instrument for "who claimed this window". Runs first in the tick, so the log
    /// reads observation-then-reaction: a move WE make is announced by the pass that makes
    /// it, so a transition that appears here with nothing of ours beside it came from the
    /// phone — the platform reusing a task across displays for a launch we did not issue.
    /// Pair it with the `ActivityTaskManager` START lines in the device log (diag.rs,
    /// LOGCAT_SPEC) to get the uid that asked for it.
    ///
    /// Only the desktop is worth announcing arrivals for; the phone has dozens of cached
    /// tasks and logging each one's first sighting would bury the session's first frames.
    /// Transitions are logged wherever they happen, because that is the event in question.
    fn track_moves(&mut self, displays: &[(i32, Vec<TaskRec>)], desktop: i32) {
        let mut now: HashMap<u32, i32> = HashMap::new();
        for (display, tasks) in displays {
            for rec in tasks {
                now.insert(rec.id, *display);
                let mode = if rec.freeform {
                    "freeform"
                } else if rec.fullscreen {
                    "fullscreen"
                } else {
                    "other"
                };
                match self.seen_on.get(&rec.id) {
                    Some(prev) if prev != display => {
                        let note = if *display == desktop {
                            " — ONTO the desktop"
                        } else if *prev == desktop {
                            " — OFF the desktop"
                        } else {
                            ""
                        };
                        log::warn!(
                            "task-move [{}] task {} ({}) display {prev} -> {display}{note} \
                             [{mode}, visible={}]",
                            self.key,
                            rec.id,
                            rec.package(),
                            rec.visible
                        );
                    }
                    None if *display == desktop => log::info!(
                        "task-move [{}] task {} ({}) appeared on the desktop [{mode}]",
                        self.key,
                        rec.id,
                        rec.package()
                    ),
                    _ => {}
                }
            }
        }
        for (id, prev) in &self.seen_on {
            if !now.contains_key(id) && *prev == desktop {
                log::info!(
                    "task-move [{}] task {id} is gone (was on the desktop)",
                    self.key
                );
            }
        }
        self.seen_on = now;
    }

    /// Keep the ledger of which windows are the desktop's, and take back the ones another
    /// display has claimed.
    ///
    /// The launcher owns the other half of this: an app opened here gets a task of its
    /// own (LauncherActivity.startWindowed, FLAG_ACTIVITY_MULTIPLE_TASK), which is what
    /// stops the desktop from taking a window off the phone. Nothing over there can stop
    /// the traffic in the other direction — a tap on the phone's own launcher, or a
    /// notification, runs ActivityStarter with no display of ours in mind, and
    /// RootWindowContainer#findTask searches every display for a reusable task. When it
    /// finds one of ours, the window leaves the monitor mid-use. This puts it back.
    ///
    /// Only tasks first seen HERE are ever chased, so an app the user genuinely started
    /// on the phone is never dragged onto the desktop.
    fn reclaim_pass(&mut self, here: &[TaskRec], elsewhere: &[(i32, Vec<TaskRec>)], display: i32) {
        for rec in here {
            // Our own launcher IS the desktop — it is not a window that can be stolen,
            // and a task with no component cannot be identified later anyway.
            if rec.comp.is_empty() || rec.is_ours() {
                continue;
            }
            self.owned.insert(rec.id, rec.package().to_string());
            self.reclaims.remove(&rec.id); // home: the next steal starts with a full budget
        }

        // Drop tasks that no longer exist anywhere, so a long session's ledger stays the
        // size of what is open rather than of everything ever opened. Done before the
        // reclaim below, not after, so an early return cannot skip it.
        let live: HashSet<u32> = here
            .iter()
            .map(|t| t.id)
            .chain(elsewhere.iter().flat_map(|(_, ts)| ts.iter().map(|t| t.id)))
            .collect();
        self.owned.retain(|id, _| live.contains(id));
        self.reclaims.retain(|id, _| live.contains(id));

        // Windows of ours that are somewhere else, with a try still left on the clock.
        let mut stolen: Vec<(i32, u32, String)> = Vec::new();
        for (other, tasks) in elsewhere {
            for rec in tasks {
                let Some(pkg) = self.owned.get(&rec.id).cloned() else {
                    continue; // never ours
                };
                let t = self.reclaims.entry(rec.id).or_insert(0);
                if *t >= MAX_RECLAIM_ATTEMPTS {
                    continue;
                }
                *t += 1;
                stolen.push((*other, rec.id, pkg));
            }
        }

        // Confirmed once, and only when there is something to take back: the daemon is
        // reached over a single `adb forward` on a fixed port, which the newest connect
        // wins outright (adb.rs, ensure_wmd), so with two phones attached the socket can
        // be pointing at the OTHER device. Asking which display hosts the desktop is the
        // cheap way to be sure this is the right phone — and it doubles as "the display
        // is still alive", which matters because a session being torn down would
        // otherwise have us moving windows onto a display that has just gone.
        if stolen.is_empty() {
            return;
        }
        if !reclaim_enabled() {
            for (other, id, pkg) in &stolen {
                log::warn!(
                    "freeform-enforcer [{}] task {id} ({pkg}) left the desktop for display \
                     {other}. Not pulling it back: if the user tapped this app ON THE PHONE \
                     then the phone is where it belongs. Set OPENDEX_RECLAIM=1 to take it \
                     back instead.",
                    self.key
                );
                self.reclaims.insert(*id, MAX_RECLAIM_ATTEMPTS); // say it once, not at 10Hz
            }
            return;
        }
        if self.wm.desktop_display() != Some(display) {
            log::warn!(
                "freeform-enforcer [{}] {} window(s) left display {display}, but the window \
                 daemon is not reachable for this session — leaving them where they went",
                self.key,
                stolen.len()
            );
            return;
        }
        for (other, id, pkg) in stolen {
            let tries = self.reclaims.get(&id).copied().unwrap_or(0);
            log::info!(
                "freeform-enforcer [{}] display {other} claimed task {id} ({pkg}) — moving \
                 it back to {display} (try {tries})",
                self.key
            );
            if !self.wm.move_to_display(id as i32, display) {
                // The move itself was refused — an OEM without the hidden method, or a
                // task already dying. Say it once and stop chasing: the window is not
                // lost, it is on the phone where whoever launched it can see it, and
                // repeating this at 10Hz would bury the log.
                log::warn!(
                    "freeform-enforcer [{}] could not move task {id} back — it stays on \
                     display {other}",
                    self.key
                );
                self.reclaims.insert(id, MAX_RECLAIM_ATTEMPTS);
            }
        }
    }

    /// Explicit maximize/restore, asked for from the taskbar's app menu —
    /// same toggle as the caption button, just with the user's intent
    /// stated outright instead of inferred from a sighting.
    fn window_pass(&mut self, tasks: &[TaskRec], display: i32, density: i32) {
        let pkgs = {
            let mut queued = self.shared.window_reqs.lock().unwrap();
            std::mem::take(&mut *queued)
        };
        for pkg in pkgs {
            // Never onto a task of ours. A taskbar entry can only name a window
            // broadcast() published, and that pass already skips ours — but a
            // task hosting a system dialog reports the DIALOG's package, so a
            // bare package match would resolve "maximize Settings" onto the
            // widget bind confirmation standing on our detour task.
            let Some(rec) = tasks
                .iter()
                .find(|t| !t.is_ours() && t.package() == pkg && (t.visible || t.visible_requested))
            else {
                continue;
            };
            let id = rec.id;
            let want_maxed = !self.wins.get(&id).is_some_and(|w| w.maxed);
            log::info!(
                "freeform-enforcer [{}] task {id} ({pkg}) menu request -> maxed={want_maxed}",
                self.key
            );
            // Asked for by name, so it gets a full round again even if this display
            // refused it before.
            self.unshapeable.remove(&id);
            self.apply(rec, want_maxed, display, density);
        }
    }

    /// Drive one task to `maxed` (or back to its windowed bounds) and mark
    /// the transition in flight.
    fn apply(&mut self, rec: &TaskRec, maxed: bool, display: i32, density: i32) {
        let Some(max_rect) = self.grown_rect(rec, density) else {
            return;
        };
        // a portrait-locked app has no sensible landscape "restore" size —
        // its window IS the phone-shaped one, at every size
        let dflt = if rec.portrait {
            self.phone_rect(density)
                .unwrap_or_else(|| self.default_windowed())
        } else {
            self.default_windowed()
        };
        let full_w = self.display_size.map(|(w, _)| w).unwrap_or(i32::MAX);
        let fills = |b: (i32, i32, i32, i32)| {
            b.0 <= 4 && b.2 >= full_w - 1 && (b.2 - b.0) >= full_w * 9 / 10
        };
        let id = rec.id;
        let entry = self.wins.entry(id).or_insert(WinState {
            maxed: false,
            windowed: dflt,
            pending: None,
            attempts: 0,
            seen_freeform: rec.freeform,
        });
        // a new target restarts the attempt budget
        if entry.maxed != maxed || entry.pending.is_none() {
            entry.attempts = 0;
        }
        // Remember where a window was before it grew, so restore has a
        // target — but ONLY if it was genuinely windowed. One UI often makes
        // the window full-width while still freeform rather than switching to
        // fullscreen mode, and adopting THAT as the restore target is how a
        // restore turned into "fills the display, behind the taskbar" and
        // then never converged, because it still reads as screen-filling.
        if maxed && !entry.maxed {
            if let Some(b) = rec.bounds.filter(|b| !rec.fullscreen && !fills(*b)) {
                entry.windowed = b;
            }
        }
        entry.maxed = maxed;
        entry.pending = Some(Instant::now());
        // last line of defence: a restore must never land somewhere that
        // reads as filling, or it can never be seen to have finished
        if fills(entry.windowed) {
            entry.windowed = dflt;
        }
        let rect = if maxed { max_rect } else { entry.windowed };
        let comp = rec.comp.clone();
        // a fullscreen task ignores `am task resize` — it has to come back to
        // freeform first, and that is also what restores its caption
        if rec.fullscreen && !comp.is_empty() {
            // Announced because this is one of only two things we do that can move a
            // window BETWEEN displays: it is `am start -n <component>`, addressed by
            // component and not by task, so if that component also has a task on the
            // phone the platform may hand us that one instead. When the task-move log
            // shows something arriving on the desktop, this line is the first suspect.
            log::info!(
                "freeform-enforcer [{}] task {id} ({comp}) is fullscreen — re-launching it \
                 onto display {display} in freeform (am start --display {display} \
                 --windowingMode 5 -n {comp})",
                self.key
            );
            let cmd = self.freeform_then_resize(id, &comp, display, rect);
            self.run_bg(&cmd);
        } else {
            self.resize(id, rect);
        }
    }

    /// Keeps this side's idea of each window in step with the screen, and
    /// guarantees windows keep a caption.
    ///
    /// A window "fills the screen" when it is in fullscreen windowing mode
    /// (One UI's own response to its caption button, which also takes the
    /// caption away) or sits at full freeform width. Our maximized pin — and
    /// the launcher caption's, which is the same rect — is deliberately 4px
    /// narrower so it never reads as filling. That inset is what tells a
    /// window WE maximized from one the platform made full-width.
    ///
    /// This pass OBSERVES; it does not toggle. The only maximize toggle in the
    /// system is the user's press — on the caption's ▢ (which applies the pin
    /// itself) or in the taskbar's app menu (window_pass). Inferring a second
    /// toggle from geometry here double-counted every caption press and
    /// restored windows the user had just asked to maximize.
    ///
    /// The first sighting of a task is a launch: it lands windowed, and
    /// anything already filling the screen is pulled into shape rather than
    /// counted as anything.
    fn window_state_pass(&mut self, tasks: &[TaskRec], display: i32, density: i32) {
        let Some(max_rect) = self.maxed_rect(density) else {
            return;
        };
        // The display changed size under maximised windows. Left alone they
        // would read as "resized by hand" below and drop to windowed; they are
        // maximised, so they are fitted to the display as it is now.
        if std::mem::take(&mut self.refit) {
            let maxed: Vec<u32> = self.wins.iter().filter(|(_, w)| w.maxed).map(|(id, _)| *id).collect();
            for rec in tasks.iter().filter(|t| maxed.contains(&t.id)) {
                log::info!("freeform-enforcer [{}] task {} re-fitted to the display", self.key, rec.id);
                self.apply(rec, true, display, density);
            }
        }
        let dflt = self.default_windowed();
        let live: Vec<&TaskRec> = tasks
            .iter()
            .filter(|t| t.visible && !t.comp.is_empty())
            // our own launcher IS the desktop — leave it fullscreen. A detour
            // task of ours is left alone too: it is already the size the
            // desktop asked for, and re-launching a bind dialog from shell uid
            // would sever the result link it needs to exist at all.
            .filter(|t| !t.is_ours())
            .collect();
        let ids: HashSet<u32> = live.iter().map(|t| t.id).collect();
        self.wins.retain(|id, _| ids.contains(id));
        // Kept while the task is on this display at all, hidden or not, so that
        // bringing it back in front does not start the re-launches over.
        self.unshapeable.retain(|id| tasks.iter().any(|t| t.id == *id));

        for rec in live {
            let id = rec.id;
            let filling = rec.fullscreen
                || (rec.freeform && rec.bounds.is_some_and(|b| self.fills(b)));

            if self.unshapeable.contains(&id) {
                if filling {
                    continue;
                }
                // It became a window after all, so it is ours to shape again.
                self.unshapeable.remove(&id);
            }
            if rec.freeform {
                if let Some(w) = self.wins.get_mut(&id) {
                    w.seen_freeform = true;
                }
            }

            let Some(state) = self.wins.get(&id) else {
                // Unknown task: a launch. Anything already filling the screen
                // is pulled into a window — never counted as a maximize press.
                // A portrait-locked app is re-shaped even when it did not
                // launch filling the screen: One UI hands it whatever
                // landscape rect it last had, which is exactly the stretched
                // layout we are trying to avoid.
                let mis_shaped = rec.portrait
                    && !rec.bounds.is_some_and(|b| self.is_phone_shaped(b, density));
                if filling || mis_shaped {
                    log::info!(
                        "freeform-enforcer [{}] task {id} launched -> {}",
                        self.key,
                        if rec.portrait { "phone-shaped" } else { "windowed" }
                    );
                    self.apply(rec, false, display, density);
                } else {
                    self.wins.insert(
                        id,
                        WinState {
                            maxed: false,
                            windowed: rec.bounds.unwrap_or(dflt),
                            pending: None,
                            attempts: 0,
                            seen_freeform: rec.freeform,
                        },
                    );
                }
                continue;
            };

            // portrait-locked apps grow into a phone-shaped column, not a
            // landscape rect they would only stretch across
            let max_rect = self.grown_rect(rec, density).unwrap_or(max_rect);
            let want = state.maxed;
            // Being maximized means freeform AT the pin, not merely sized
            // like it: our resize can land while the task is still in
            // fullscreen mode, and treating that as arrived cleared `pending`
            // one tick before the mode caught up — so the still-fullscreen
            // task read as a second press and instantly un-maximized.
            let at_target = if want {
                !filling && rec.bounds.is_some_and(|b| near(b, max_rect))
            } else {
                !filling
            };

            if let Some(since) = state.pending {
                // our own transition is still in flight
                if at_target {
                    self.wins.get_mut(&id).unwrap().pending = None;
                } else if since.elapsed() > PENDING_TIMEOUT {
                    // never flip here — an app that keeps re-asserting
                    // fullscreen would oscillate forever
                    let tries = self.wins.get(&id).map_or(0, |w| w.attempts);
                    let ever_a_window = self.wins.get(&id).is_some_and(|w| w.seen_freeform);
                    if tries >= MAX_APPLY_ATTEMPTS && !ever_a_window {
                        // Not one of those re-launches made it a window, so
                        // parking is no way out either — maximized is freeform
                        // too. That is the platform refusing, not an app
                        // fighting back, and asking again every PENDING_TIMEOUT
                        // only re-launches it for as long as it is on screen.
                        // Leave it full screen.
                        log::warn!(
                            "freeform-enforcer [{}] task {id} ({}) never became a window in \
                             {tries} re-launches — this display keeps it full screen, leaving it be",
                            self.key,
                            rec.comp
                        );
                        self.wins.remove(&id);
                        self.unshapeable.insert(id);
                    } else if tries >= MAX_APPLY_ATTEMPTS {
                        // Giving up must not leave the window filling the
                        // display: it would have no caption and would sit
                        // under the taskbar, and the next tick would read it
                        // as a fresh press and flip straight back. Settle on
                        // maximized, which is reachable, keeps the caption and
                        // clears the taskbar — the next press then restores.
                        // Reachable because this task HAS been a window: an
                        // app that keeps re-asserting fullscreen (Brave, for
                        // about a minute) is outlasted this way, not abandoned.
                        log::warn!(
                            "freeform-enforcer [{}] task {id} will not settle at maxed={want} \
                             after {tries} tries — parking it maximized",
                            self.key
                        );
                        self.wins.get_mut(&id).unwrap().windowed = dflt;
                        self.apply(rec, true, display, density);
                        self.wins.get_mut(&id).unwrap().attempts = 0;
                    } else {
                        log::info!(
                            "freeform-enforcer [{}] task {id} still not maxed={want}, re-applying",
                            self.key
                        );
                        self.apply(rec, want, display, density);
                        self.wins.get_mut(&id).unwrap().attempts = tries + 1;
                    }
                }
                continue;
            }

            // Sitting on a maximize pin? Either pin counts: this task's own
            // (phone-shaped for a portrait app) or the plain display-wide one,
            // because the caption bar applies the display-wide rect and cannot
            // know the app asked for portrait.
            let at_pin = rec.bounds.is_some_and(|b| {
                near(b, max_rect) || self.maxed_rect(density).is_some_and(|m| near(b, m))
            });

            if filling {
                // Something OTHER than our caption made this cover the whole
                // display — One UI's own caption, or an app asking for it. Pin
                // it, which gets it out from under the taskbar and gives it a
                // caption back.
                //
                // This used to TOGGLE here (`next = !want`), on the reading
                // that a screen-filling window is how a caption press looks
                // from the outside. That was true only while the caption had
                // no maximize button of its own. Now it has one, and it sends
                // exactly this rect, so a single press was counted twice: once
                // by the caption, once by this toggle. Out of phase — after a
                // taskbar-menu maximize, a parked retry, or a caption-service
                // restart on reconnect — the second count ran as a RESTORE and
                // dropped the window onto `windowed`, the last rect it had been
                // observed at un-maximized, i.e. the left half of the display
                // after a snap. That is the "maximize goes half width" bug.
                // The caption now maximizes to the pin below (4px narrow), so
                // this branch means what it says again.
                log::info!(
                    "freeform-enforcer [{}] task {id} fills the display — pinning it maximized",
                    self.key
                );
                self.apply(rec, true, display, density);
            } else if at_pin {
                // The caption maximized it. Adopt that as the state instead of
                // arguing with it, and leave `windowed` alone — overwriting the
                // restore target with the maximized rect is what turned the
                // next restore into a no-op.
                if !want {
                    self.wins.get_mut(&id).unwrap().maxed = true;
                }
            } else if want {
                // maximized window dragged or resized by hand — it is a
                // normal window again, and its new bounds are the target
                let b = rec.bounds.unwrap_or(dflt);
                let st = self.wins.get_mut(&id).unwrap();
                st.maxed = false;
                st.windowed = b;
            } else if let Some(b) = rec.bounds {
                self.wins.get_mut(&id).unwrap().windowed = b;
            }
        }
    }
}

/// Drains the launcher's request queue (taskbar close, nav keys,
/// quick-settings, display size, window and PC-fullscreen toggles) and
/// executes it with adb's shell rights, which the launcher does not have.
///
/// Runs on its own thread and its own adb shell: one pass costs ~1s because
/// `content` boots a VM on the device, and the taskbar's open-apps row must
/// not wait behind that.
/// When the last desktop restart was granted — process-wide, because the
/// request that asks for one outlives the session that received it.
static LAST_RESTART: Mutex<Option<Instant>> = Mutex::new(None);
/// Long enough to cover a full stop → respawn → launcher-deploy cycle.
const RESTART_COOLDOWN: Duration = Duration::from_secs(20);
/// When the last "Exit DeX" was granted. Same hazard as [`LAST_RESTART`] and
/// the same window: an exit row that outlives the session it was raised in
/// would be read by the pump of the session started right after — closing a
/// desktop the user had just asked for.
static LAST_EXIT: Mutex<Option<Instant>> = Mutex::new(None);

struct RequestPump {
    app: AppHandle,
    key: String,
    shell: ShellSession,
    /// highest launcher-request id already executed (v2 ack protocol). The
    /// queue is only cleared AFTER processing — a request can no longer be
    /// lost when the adb shell dies between draining and reading the output.
    last_req_id: u64,
    shared: Arc<Shared>,
    /// The fast channel to the launcher's queue. See [`RequestPump::tick`].
    wm: crate::wm::WmClient,
    /// Whether the last drain came over the daemon. Sets the poll rate: see
    /// [`RequestPump::poll_interval`].
    fast: bool,
}

/// One queued request, as the PC reads it off either channel.
///
/// `content query` prints `Row: 0 id=17, cmd=close, arg=com.foo` and the daemon's rows are
/// rendered into the same shape by [`request_row`], so this is the single parser for both.
/// Returns the id (absent on a pre-v2 launcher), the command and its argument.
fn parse_request_row(line: &str) -> Option<(Option<u64>, &str, &str)> {
    let cmd_pos = line.find("cmd=")?;
    let arg_pos = line.find("arg=")?;
    let id = line.find("id=").and_then(|p| {
        line[p + 3..]
            .chars()
            .take_while(|c| c.is_ascii_digit())
            .collect::<String>()
            .parse()
            .ok()
    });
    let cmd = line[cmd_pos + 4..].split(',').next().unwrap_or("").trim();
    let arg = line[arg_pos + 4..].trim();
    Some((id, cmd, arg))
}

/// The daemon's rows in the shape [`parse_request_row`] reads. Paired with it on purpose:
/// the two channels must not be able to disagree about what a request is.
fn request_row(id: u64, cmd: &str, arg: &str) -> String {
    format!("id={id}, cmd={cmd}, arg={arg}")
}

impl RequestPump {
    fn run(&mut self, cmd: &str) -> Option<String> {
        match self.shell.run(&self.app, cmd) {
            Ok(out) => Some(out),
            Err(e) => {
                log::warn!("request-pump [{}] shell: {e}", self.key);
                None
            }
        }
    }

    /// Fire-and-forget; see Enforcer::run_bg for why the `&` must stay
    /// inside the subshell.
    fn run_bg(&mut self, cmd: &str) -> bool {
        self.run(&format!("(({cmd}) >/dev/null 2>&1 &)")).is_some()
    }

    /// One drain of the launcher's request queue.
    ///
    /// The queue lives in two places at once and this reads whichever is cheaper. The
    /// ContentProvider is the channel that always exists, and `content query` is a whole
    /// app_process VM per read — 537 ms measured on a Redmi Note 7 (SDM660, Android 10)
    /// against 73 ms for a bare adb shell round trip. At this poll rate that was a JVM
    /// starting roughly every 0.7 s for the entire session whether or not anything had
    /// been pressed, which is most of what made an idle desktop warm, and it still left
    /// most of a second between a taskbar press and anything happening.
    ///
    /// The daemon holds the same rows under the same ids and answers over a socket that
    /// is already open, so the idle case — by far the common one — costs a write and a
    /// read. `content query` is then only paid on phones where the daemon could not
    /// start, which is what keeps a press working there exactly as before.
    fn tick(&mut self, display: i32) {
        if let Some(rows) = self.wm.req_get() {
            self.fast = true;
            if !rows.is_empty() {
                // Rendered into the shape `handle_requests` already parses rather than
                // given a second parser: the id watermark, the argument charset check and
                // every verb below must stay identical across the two channels, and the
                // surest way to keep them identical is for there to be one of them.
                let reqs = rows
                    .iter()
                    .map(|(id, cmd, arg)| request_row(*id, cmd, arg))
                    .collect::<Vec<_>>()
                    .join("
");
                self.handle_requests(&reqs, display);
            }
            return;
        }
        self.fast = false;
        let Some(reqs) = self.run(
            "content query --uri content://com.ccrstech.openandroiddex.launcher.requests/v2 2>/dev/null",
        ) else {
            return;
        };
        self.handle_requests(&reqs, display);
    }

    /// How long to wait before draining again.
    ///
    /// The old 150 ms was a compromise with the cost of asking: at 537 ms per
    /// `content query` a shorter period would have had the phone starting a VM
    /// continuously, and a longer one made the taskbar feel broken. Over the daemon the
    /// question is nearly free, so the period can be what actually decides how quickly a
    /// press is noticed. It stays at 150 ms whenever the daemon is not answering, because
    /// there the old cost is back and polling harder would only heat the phone.
    fn poll_interval(&self) -> Duration {
        if self.fast {
            Duration::from_millis(40)
        } else {
            Duration::from_millis(150)
        }
    }

    /// Execute the launcher's queued requests. v2 rows carry an id and stay
    /// queued on the phone until acked below — and a row is only acked once
    /// its command actually reached the device, so a dying adb shell delays
    /// a request by one poll instead of silently eating it. Rows already
    /// executed (id <= last_req_id) are skipped, so a lost ack never re-runs
    /// a command. Rows without an id (old launcher APK, destructive drain)
    /// run best-effort.
    fn handle_requests(&mut self, reqs: &str, display: i32) {
        let mut ack_id = 0u64;
        // The audio mode arrives as TWO cfg rows (audio + audiodup — see
        // DexMedia.setAudioMode), usually in the same drain. Reconciling after
        // each row would briefly spawn the companion in the in-between shape,
        // so the rows only mark this and one reconcile runs after the loop.
        let mut audio_dirty = false;
        for line in reqs.lines() {
            let Some((id, cmd, arg)) = parse_request_row(line) else {
                continue;
            };
            if let Some(id) = id {
                if id <= self.last_req_id {
                    ack_id = ack_id.max(id); // re-ack in case the delete was lost
                    continue;
                }
            }
            // '-' is here for MediaCodec encoder names, which the Settings
            // window sends verbatim; every consumer below either parses the
            // value or passes it to scrcpy as a single argv entry, never to a
            // shell, so the set stays shell-safe either way.
            let safe_arg = !arg.is_empty()
                && arg
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'));
            // false → the shell was unreachable: leave this row (and the ones
            // after it, to keep order) queued for the next poll.
            let executed = if !safe_arg {
                true // malformed: consume, retrying cannot help
            } else if cmd == "close" {
                log::info!("request-pump [{}] close request: {arg}", self.key);
                self.run_bg(&format!("am force-stop {arg}"))
            } else if cmd == "qs" {
                // Quick-settings toggle from the taskbar tray. arg is
                // "<what>.on" / "<what>.off" (dots pass the charset check).
                let (what, state) = arg.rsplit_once('.').unwrap_or((arg, "on"));
                let on = state == "on";
                let en = if on { "enable" } else { "disable" };
                let shell_cmd = match what {
                    "wifi" => format!("svc wifi {en}"),
                    // `svc bluetooth` disappeared on newer builds; chain the
                    // cmd service fallback, one of the two will land
                    "bt" => format!("svc bluetooth {en}; cmd bluetooth_manager {en}"),
                    "data" => format!("svc data {en}"),
                    "airplane" => format!("cmd connectivity airplane-mode {en}"),
                    "rotate" => format!(
                        "settings put system accelerometer_rotation {}",
                        if on { 1 } else { 0 }
                    ),
                    "location" => format!(
                        "cmd location set-location-enabled {on}; settings put secure location_mode {}",
                        if on { 3 } else { 0 }
                    ),
                    // lock the phone itself (DeX-style Lock tile)
                    "lock" => "input keyevent KEYCODE_SLEEP".into(),
                    _ => String::new(),
                };
                if shell_cmd.is_empty() {
                    true // unknown toggle: consume
                } else {
                    log::info!("request-pump [{}] qs request: {arg}", self.key);
                    self.run_bg(&shell_cmd)
                }
            } else if cmd == "fullscreen" {
                // Toggle the desktop-side scrcpy window between fullscreen and
                // its previous frame. What "fullscreen" means is the host's
                // answer, not ours — see the embed backends.
                let toggled = crate::dex::toggle_fullscreen(&self.app, &self.key)
                    .unwrap_or_else(|| crate::embed::toggle_fullscreen(&self.app, &self.key));
                match toggled {
                    Ok(on) => self.shared.record_fullscreen(on),
                    Err(e) => {
                        // The taskbar button is on the PHONE, so an error here
                        // has nowhere to be shown — the log is the only place
                        // it can land. Worth a warn rather than a debug: on
                        // macOS this is where a missing Accessibility grant
                        // announces itself, and it is otherwise invisible.
                        log::warn!("request-pump [{}] fullscreen: {e}", self.key)
                    }
                }
                // consume even on error — retrying can't conjure a window,
                // and a toggle must never replay from a stale queue
                true
            } else if cmd == "window" {
                // Maximize/restore the app's window, asked for from the
                // taskbar's app menu. Handing it to the task poller keeps
                // the decision where the task ids and bounds live.
                log::info!("request-pump [{}] window request: {arg}", self.key);
                self.shared.window_reqs.lock().unwrap().push(arg.to_string());
                true
            } else if cmd == "cfg" {
                // Stream/clipboard setting from the Settings window, as
                // "<key>.<value>". Split on the FIRST dot only: encoder names
                // are dotted ("c2.exynos.h264.encoder") and are the value.
                let (what, value) = arg.split_once('.').unwrap_or((arg, ""));
                log::info!("request-pump [{}] config: {what}={value}", self.key);
                remember_config(&self.app, what, value);
                // "Where sound plays" is the one stream setting that takes
                // effect NOW: audio lives in its own scrcpy process, so the
                // change is a cycle of that companion, not a desktop restart.
                // "reset" belongs in the list: a factory reset clears the
                // stored audio keys, which is a mode change back to the
                // default in everything but spelling.
                if what == "audio" || what == "audiodup" || what == "reset" {
                    audio_dirty = true;
                }
                true
            } else if cmd == "perf" {
                // "Reduce quality" (Settings → Performance). The launcher does
                // its own half — blur, grain, surface alpha, the taskbar's
                // shadow — off the pref alone; this is the half it has no
                // privilege for. The animation scales are Settings.Global,
                // which needs WRITE_SECURE_SETTINGS: this adb shell holds it,
                // and the launcher only for the cable-pulled undo.
                //
                // Remembered as well as applied, so the next spawn can cap the
                // stream's bitrate (see apply_stored_config) — that is the part
                // of the mode that matters over a wireless connection, and like
                // every other scrcpy argument it only lands on a fresh session.
                let on = arg == "on";
                remember_config(&self.app, "perf", if on { "on" } else { "off" });
                let serial = self.key.split('|').next().unwrap_or("").to_string();
                if serial.is_empty() {
                    true // nothing to address: consume rather than spin
                } else {
                    log::info!("request-pump [{}] reduce quality: {arg}", self.key);
                    let script = if on {
                        crate::adb::arm_perf_globals(&self.app, &serial)
                    } else {
                        crate::adb::undo_perf_globals(&self.app, &serial)
                    };
                    // An empty undo is the honest answer for a phone with no
                    // recorded values (see undo_perf_globals) — there is
                    // nothing to put back, and running `sh -c ""` to discover
                    // that would be a round trip for nothing.
                    script.trim().is_empty() || self.run_bg(&script)
                }
            } else if cmd == "cursor" {
                // Settings → Mouse & cursor → Pointer speed. The launcher draws
                // its own pointers over its own windows off the prefs alone;
                // this is the half it has no privilege for, since
                // Settings.System.pointer_speed is a PRIVATE_SETTING refused to
                // an app even with WRITE_SETTINGS and allowed to this shell.
                //
                // Not stored in stream-config.json like the scrcpy settings
                // are: this one lives on the PHONE and is undone on exit, so
                // the value that matters is already on the device. The
                // launcher re-raises it at session start (reapplyPointerSpeed).
                let serial = self.key.split('|').next().unwrap_or("").to_string();
                if serial.is_empty() {
                    true // nothing to address: consume rather than spin
                } else {
                    log::info!("request-pump [{}] pointer speed: {arg}", self.key);
                    let script = crate::adb::pointer_speed_script(&self.app, &serial, arg);
                    if script.trim().is_empty() {
                        // An argument that did not parse, or a phone that could
                        // not be read for a snapshot. Neither is fixed by
                        // retrying, and running `sh -c ""` to find out would be
                        // a round trip for nothing.
                        true
                    } else if self.run_bg(&script) {
                        self.shared.undo_stale.store(true, Ordering::SeqCst);
                        true
                    } else {
                        false
                    }
                }
            } else if cmd == "restart" {
                // Stream settings only take at spawn time. The webview owns
                // the session lifecycle, so it is told to cycle the desktop.
                //
                // Rate-limited, and this is not belt-and-braces. The row is
                // acked on the session's own adb shell, which the restart is
                // about to kill; the launcher PROCESS outlives the display, so
                // its queue (and any un-acked row in it) is still there when
                // the next session's pump starts with a fresh id watermark. A
                // restart that replayed itself would loop forever.
                let mut last = LAST_RESTART.lock().unwrap();
                if last.is_some_and(|t| t.elapsed() < RESTART_COOLDOWN) {
                    log::info!("request-pump [{}] ignoring repeat restart", self.key);
                } else {
                    *last = Some(Instant::now());
                    log::info!("request-pump [{}] desktop restart requested", self.key);
                    // Quiet for as long as the restart takes, which is longer
                    // than the watchdog waits for a pulled cable: said first.
                    let serial = self.key.split('|').next().unwrap_or("").to_string();
                    let undo = crate::adb::undo_globals_script(&self.app, &serial);
                    let _ = self.wm.arm(RESTART_TTL_SECS, &undo);
                    let _ = self
                        .app
                        .emit("desktop:restart", serde_json::json!({ "sessionKey": self.key }));
                }
                true
            } else if cmd == "exit" {
                // "Exit DeX" from the taskbar or the Settings window. The
                // webview owns the session lifecycle (see `restart`), so it is
                // told to end this one; the phone-side undo — display profile,
                // caption service, window daemon — runs from there, once the
                // display it belongs to is actually gone.
                //
                // Acked here and synchronously, before the event goes out. The
                // ack at the bottom of this function is fire-and-forget on the
                // very adb shell this exit is about to kill, and the launcher
                // process can outlive the display — a row that survives is a
                // row the next session's pump would read and act on.
                if let Some(id) = id {
                    let _ = self.run(&format!(
                        "content delete --uri content://com.ccrstech.openandroiddex.launcher.requests/v2 --where \"id<={id}\""
                    ));
                    self.last_req_id = self.last_req_id.max(id);
                }
                let mut last = LAST_EXIT.lock().unwrap();
                if last.is_some_and(|t| t.elapsed() < RESTART_COOLDOWN) {
                    log::info!("request-pump [{}] ignoring repeat exit", self.key);
                } else {
                    *last = Some(Instant::now());
                    log::info!("request-pump [{}] exit requested from the desktop", self.key);
                    let _ = self
                        .app
                        .emit("desktop:exit", serde_json::json!({ "sessionKey": self.key }));
                }
                true
            } else if cmd == "key" {
                // taskbar nav buttons — injected on the virtual display so
                // the focused app there receives them
                let code = match arg {
                    "back" => Some("KEYCODE_BACK"),
                    "home" => Some("KEYCODE_HOME"),
                    "recents" => Some("KEYCODE_APP_SWITCH"),
                    _ => None,
                };
                match code {
                    Some(code) => self.run_bg(&format!("input -d {display} keyevent {code}")),
                    None => true,
                }
            } else if cmd == "density" {
                // Display size (Settings window): override the virtual
                // display's density. Synchronous so the log captures a
                // failure; the config change recreates the on-display UIs.
                match arg.parse::<i32>() {
                    Ok(dpi) if (DPI_MIN..=DPI_MAX).contains(&dpi) => {
                        log::info!("request-pump [{}] density request: {dpi}", self.key);
                        match self.run(&format!("wm density {dpi} -d {display}")) {
                            Some(out) => {
                                if !out.trim().is_empty() {
                                    log::info!("request-pump [{}] wm density: {out}", self.key);
                                }
                                // taskbar_px and the maximize bounds derive
                                // from the session density — keep in step
                                let state = self.app.state::<MirrorState>();
                                let map = state.0.lock().unwrap();
                                let base_dpi = map.get(&self.key).map(|s| {
                                    s.density.store(dpi, Ordering::SeqCst);
                                    s.base_dpi.load(Ordering::SeqCst)
                                });
                                drop(map);
                                // future sessions start straight at this size,
                                // as a multiple of whatever their Default is
                                let serial =
                                    self.key.split('|').next().unwrap_or("").to_string();
                                if let (false, Some(base)) = (serial.is_empty(), base_dpi.filter(|b| *b > 0)) {
                                    let f = display::remember_size_factor(&self.app, &serial, dpi, base);
                                    log::info!(
                                        "request-pump [{}] display size {f}x Default ({base} dpi)",
                                        self.key
                                    );
                                }
                                true
                            }
                            None => false, // shell down: retry next poll
                        }
                    }
                    Ok(dpi) => {
                        log::warn!(
                            "request-pump [{}] density request out of range: {dpi}",
                            self.key
                        );
                        true
                    }
                    Err(_) => true,
                }
            } else {
                true // unknown command: consume so it never blocks the queue
            };
            match id {
                Some(id) if executed => ack_id = ack_id.max(id),
                Some(_) => break, // keep this and later rows for the next poll
                None => {}
            }
        }
        if ack_id > 0 {
            // Ack AFTER execution: drop the handled rows on the phone. If
            // the ack itself is lost, last_req_id keeps the rows from
            // re-executing and a later pass cleans them up.
            //
            // BOTH channels, always: the launcher queues every request to both, so
            // acking only the one this row arrived on leaves the other copy behind —
            // harmless to correctness (the watermark skips it) but it would grow the
            // provider's queue for the whole session. The daemon ack is a socket write;
            // the `content delete` is the expensive one and is why this is fired in the
            // background rather than waited on.
            self.wm.req_ack(ack_id);
            self.run_bg(&format!(
                "content delete --uri content://com.ccrstech.openandroiddex.launcher.requests/v2 --where \"id<={ack_id}\""
            ));
            self.last_req_id = self.last_req_id.max(ack_id);
        }
        if audio_dirty {
            // On a thread — this pump also carries window requests, and a
            // reconcile holds an adb round-trip and a SIGTERM grace.
            let serial = self.key.split('|').next().unwrap_or("").to_string();
            if !serial.is_empty() {
                reconcile_audio_session_bg(&self.app, &serial);
            }
        }
    }
}

impl Enforcer {
    /// One chained on-device command that pulls a task back into freeform
    /// and lands it on `rect`.
    ///
    /// The resize only takes once the mode change has landed, and how long
    /// that is varies with how busy the phone is. Rather than sleep for the
    /// worst case — which sets a floor on how long the window stays
    /// fullscreen, and that fullscreen moment is exactly the flash the user
    /// sees — retry on a short ladder and let the first one that lands win.
    /// `am` is a thin wrapper over `cmd activity` (~35ms measured, unlike
    /// `content`, which boots a VM), so the extra attempts are nearly free
    /// and a resize to bounds the window already has is a no-op.
    fn freeform_then_resize(
        &self,
        task: u32,
        comp: &str,
        display: i32,
        r: (i32, i32, i32, i32),
    ) -> String {
        // The ladder runs ALONGSIDE `am start`, not after it. `am start`
        // re-fronts the activity and does not return promptly, and every
        // millisecond it holds the ladder up is a millisecond the window
        // spends fullscreen — which is the flash. Racing them means the
        // first resize after the mode flips wins, whenever that is.
        format!(
            "(for d in 0.05 0.1 0.15 0.25 0.4 0.6; do sleep $d; \
             am task resize {task} {} {} {} {}; done) & \
             am start --display {display} --windowingMode 5 -n {comp}",
            r.0, r.1, r.2, r.3
        )
    }

    /// Push the open-apps set to the launcher's taskbar. Re-broadcast
    /// periodically even when unchanged so a recreated launcher activity
    /// resyncs. Also carries the PC window's fullscreen state so the
    /// taskbar's toggle icon stays true after e.g. a missed click.
    fn broadcast(&mut self, tasks: &[TaskRec]) {
        let mut pkgs: Vec<&str> = Vec::new();
        for t in tasks {
            // Skip tasks that are not (about to be) on screen: on One UI the
            // caption ✕ merely hides the window — the task stays attached to
            // the display, invisible, and used to sit in the taskbar forever
            // until a right-click force-stop.
            if !t.visible && !t.visible_requested {
                continue;
            }
            let p = t.package();
            // is_ours and not p != LAUNCHER_PACKAGE: a detour task reports the
            // dialog's package, which would flash "Settings" into the taskbar
            // for as long as one is open.
            if !p.is_empty() && !t.is_ours() && !pkgs.contains(&p) {
                pkgs.push(p);
            }
        }
        let pkgs = pkgs.join(",");
        self.ticks = self.ticks.wrapping_add(1);
        // the request pump asks for a resync when it changes state the
        // taskbar mirrors (currently the PC-window fullscreen flag)
        let forced = self.shared.resync.swap(false, Ordering::SeqCst);
        if forced || self.last_pkgs.as_deref() != Some(&pkgs) || self.ticks % 50 == 0 {
            self.bseq = self.bseq.wrapping_add(1);
            let seq = self.bseq;
            let fs = self.shared.fs_on.load(Ordering::SeqCst);
            // Whether this computer has a touchpad we can read. The Settings
            // window dims its gesture rows when it does not, rather than
            // offering a section that cannot do anything — the same
            // dim-and-explain the pointer-speed card uses. Safe to add: the
            // receiver reads the extras it knows by name and ignores the rest.
            let tp = crate::gestures::host_has_touchpad();
            self.run_bg(&format!(
                "am broadcast -a com.ccrstech.openandroiddex.launcher.RUNNING --ei seq {seq} --ez fs {fs} --ez tp {tp} --es pkgs '{pkgs}'"
            ));
            // -p, unlike RUNNING above: this one carries a credential, and
            // RUNNING is deliberately unaddressed so any receiver of ours can
            // hear it. Suppressed entirely when the file service did not come
            // up, so a launcher that never hears this shows an honest
            // "not answering" instead of a half-working window.
            if let Some((token, os)) = crate::files::beacon(&self.key) {
                self.run_bg(&format!(
                    "am broadcast -a com.ccrstech.openandroiddex.launcher.FILES \
                     -p com.ccrstech.openandroiddex.launcher --es os {os} --es token {token}"
                ));
            }
            self.last_pkgs = Some(pkgs);
        }
    }

    /// Drive the launcher's file-transfer HUD for files dropped on this
    /// window (see transfer.rs for how a drop is seen at all).
    ///
    /// Two things happen here: the phone is asked how far the copy has got —
    /// `adb push` writes straight to the destination path, so its size *is*
    /// the progress — and the result is broadcast to the launcher, which owns
    /// the card and the toast. Nothing is sent while there is no transfer, and
    /// nothing is re-sent while it has not changed.
    fn transfer_pass(&mut self) {
        let Some(mut snap) = transfer::snapshot(&self.key) else {
            return;
        };
        if !snap.active_dest.is_empty() && snap.pct >= 0 && Instant::now() >= self.next_stat {
            self.next_stat = Instant::now() + STAT_EVERY;
            // A file that has not been created yet stats as an error, which is
            // simply 0 bytes so far.
            let bytes = self
                .run(&format!(
                    "stat -c %s {} 2>/dev/null",
                    transfer::sh_quote(&snap.active_dest)
                ))
                .and_then(|o| o.trim().parse::<u64>().ok())
                .unwrap_or(0);
            transfer::note_bytes(&self.key, bytes);
            if let Some(fresh) = transfer::snapshot(&self.key) {
                snap = fresh;
            }
        }
        // The percentage is deliberately part of the identity: it is what
        // makes the bar move. Everything else changes once per file.
        let payload = format!(
            "{}|{}|{}/{}|{}|{}|{}|{}",
            if snap.done { "done" } else { "active" },
            snap.pct,
            snap.index,
            snap.total,
            snap.ok,
            snap.failed,
            snap.install,
            snap.name,
        );
        if self.last_transfer.as_deref() == Some(payload.as_str()) {
            return;
        }
        self.last_transfer = Some(payload);
        self.tseq = self.tseq.wrapping_add(1);
        let (seq, state) = (self.tseq, if snap.done { "done" } else { "active" });
        // Names are user data and this is a shell command line — see
        // transfer::b64. `landed` rides along only on the last broadcast of a
        // batch, where the launcher uses it to get the files into the media
        // store so the phone's file manager can see them. A drop big enough to
        // strain the command line sends none, and the launcher scans the whole
        // folder instead.
        let names = if snap.done { snap.landed.join("\n") } else { String::new() };
        let landed = if names.len() <= 1500 {
            transfer::b64(&names)
        } else {
            log::info!(
                "transfer [{}] {} names is too much for one broadcast — the launcher \
                 will scan the folder instead",
                self.key,
                snap.landed.len()
            );
            String::new()
        };
        // Logged because it is the only trace of the HUD there is: the card is
        // drawn on the phone, so "the transfer worked but nothing appeared" has
        // to be answerable from this end.
        log::info!(
            "transfer [{}] hud {state} {}/{} {} pct={} ok={} fail={}",
            self.key,
            snap.index,
            snap.total,
            snap.name,
            snap.pct,
            snap.ok,
            snap.failed
        );
        self.run_bg(&format!(
            "am broadcast -a com.ccrstech.openandroiddex.launcher.TRANSFER \
             --ei seq {seq} --es state {state} --es name '{}' --ei idx {} --ei total {} \
             --ei pct {} --ei ok {} --ei fail {} --ez install {} --es dir '{}' --es landed '{landed}'",
            transfer::b64(&snap.name),
            snap.index,
            snap.total,
            snap.pct,
            snap.ok,
            snap.failed,
            snap.install,
            transfer::b64(transfer::PUSH_TARGET.trim_end_matches('/')),
        ));
    }

    /// Bounce the accessibility service that draws window captions, because the desktop
    /// display it must work on has just been (re)created.
    ///
    /// An AccessibilityService only sees displays that existed when it connected: on a
    /// newer one `getWindowsOnAllDisplays()` has no entry at all, so the service finds no
    /// windows, attaches nothing, and reports no error. Since every scrcpy session — and
    /// every reconnect — mints a fresh display id, a service left running from the previous
    /// session is simply blind, which is what made captions "sometimes" not appear.
    ///
    /// `adb_start_launcher` does this too, but only on connect; a respawn never went
    /// through it. Clearing and re-writing the setting is what forces the restart, and the
    /// two writes must be separate invocations — chained in one shell command they land
    /// faster than AccessibilityManagerService tears the service down, and it never
    /// disconnects.
    fn restart_caption_service(&mut self, display: i32) {
        log::info!(
            "freeform-enforcer [{}] display {display} — restarting CaptionService so it \
             can see this display",
            self.key
        );
        self.run("settings put secure enabled_accessibility_services ''");
        self.run(&format!(
            "settings put secure enabled_accessibility_services '{}'; \
             settings put secure accessibility_enabled 1",
            adb::CAPTION_SERVICE_COMPONENT
        ));
    }

    fn tick(&mut self, display: i32, density: i32) {
        if display != self.last_display {
            self.wins.clear();
            // Task ids are not reused across displays, but the ledger describes windows
            // on the display that just went away — keeping it would have us "reclaiming"
            // tasks onto a display they never belonged to.
            self.owned.clear();
            self.reclaims.clear();
            self.unshapeable.clear();
            self.seen_on.clear();
            self.last_pkgs = None;
            self.last_display = display;
            // The size the display was really made at, which can be a few
            // pixels short of the one asked for (parse_display_size_announced).
            // Per display rather than once: a reconnect mints a new one, and it
            // may have been asked for at a size changed mid-session.
            if let Some(size) = session_display_size(&self.app, &self.key) {
                if self.display_size != Some(size) {
                    log::info!(
                        "freeform-enforcer [{}] display {display} is {}x{} (geometry was \
                         based on {:?})",
                        self.key,
                        size.0,
                        size.1,
                        self.display_size
                    );
                }
                self.display_size = Some(size);
            }
            // Make freeform the display's DEFAULT windowing mode, so every
            // launch — including from the in-desktop home — starts windowed
            // with no fullscreen flash. Where the wm command is missing,
            // convert_pass still fixes it after the fact.
            self.run(&format!("wm set-display-windowing-mode -d {display} 5"));
            // "Physical size: 1080x2340" for the phone panel itself
            if let Some((pw, ph)) = self
                .run("wm size")
                .and_then(|o| o.split(':').nth(1).and_then(|v| parse_display_size(v.trim())))
            {
                if pw > 0 && ph > 0 {
                    self.phone_aspect = pw.min(ph) as f32 / pw.max(ph) as f32;
                }
            }
            self.default_freeform = self
                .run(&format!("wm get-display-windowing-mode -d {display}"))
                .map(|o| o.contains("freeform"))
                .unwrap_or(false);
            log::info!(
                "freeform-enforcer [{}] display {display} default_freeform={}",
                self.key,
                self.default_freeform
            );
            self.restart_caption_service(display);
        }
        // A display that follows its window changes size under the session. The
        // patched server says so as it happens ("Display resized", read in
        // spawn_output_reader); the phone is asked as well every few seconds, for
        // a server that does not.
        if self.flex && Instant::now() >= self.next_size_check {
            self.next_size_check = Instant::now() + FLEX_SIZE_CHECK;
            if let Some(size) = self
                .run(&format!("wm size -d {display}"))
                .and_then(|o| physical_size(&o))
            {
                if session_display_size(&self.app, &self.key) != Some(size) {
                    set_session_display_size(&self.app, &self.key, size);
                }
            }
        }
        if let Some(size) = session_display_size(&self.app, &self.key) {
            if self.display_size.is_some_and(|old| old != size) {
                log::info!(
                    "freeform-enforcer [{}] display {display} is {}x{} now (was {:?})",
                    self.key,
                    size.0,
                    size.1,
                    self.display_size
                );
                self.refit = true;
            }
            self.display_size = Some(size);
        }
        // Keep the daemon's dead-man switch fed. If the cable is pulled, this
        // side never gets to run the undo — nothing here can reach the phone
        // any more — so the daemon does it once this goes quiet and the desktop
        // display has gone with it. See `wm::WmClient::arm`.
        //
        // Every ~2s against a 10s TTL: five missed arms before it acts, so a
        // busy phone or a stalled poll is not mistaken for an unplugged cable.
        // The snapshot on disk can gain a row mid-session (the pointer speed is
        // the only one that does), and the copy armed into the daemon was read
        // before it existed. Re-read it here rather than per arm: this fires
        // once, on the tick after the write.
        if self.shared.undo_stale.swap(false, Ordering::SeqCst) {
            let serial = self.key.split('|').next().unwrap_or("").to_string();
            self.undo_globals = crate::adb::undo_globals_script(&self.app, &serial);
        }
        if self.ticks % ARM_EVERY_TICKS == 0 && !self.wm.arm(WATCHDOG_TTL_SECS, &self.undo_globals)
        {
            // Unreachable daemon. Usually it is simply not running, but it is
            // also what a reconnect looks like: adb forwards die with the
            // device, so a cable pulled and put back leaves the daemon alive
            // and us unable to say so. Re-opening the forward costs one adb
            // call every ~5s and is what stops the watchdog from firing on a
            // session that came back.
            let serial = self.key.split('|').next().unwrap_or("").to_string();
            if !serial.is_empty() {
                crate::adb::forward_wm_port(&self.app, &serial);
                // The file service's reverse died in the same event, and
                // nothing else would ever notice: the launcher only finds out
                // by failing to connect, which it reports as the computer
                // being unreachable.
                if let Some(host_port) = crate::files::host_port(&self.key) {
                    crate::adb::reverse_files_port(&self.app, &serial, host_port);
                }
                // Still unanswered with the forward back: the daemon is not
                // running. An adbd restart takes everything adb started with
                // it — `setsid` or not, it is all in adbd's process group — and
                // Samsung restarts adbd whenever the USB mode changes, which it
                // does by itself a few seconds after a phone is plugged in and
                // unlocked (charging → file transfer). Measured on the S25
                // Ultra, 2026-10-20: the daemon gone within seconds of every
                // such plug-in, and with it the window chrome and the dead-man
                // switch for the rest of the session. Started again, and armed.
                if !self.wm.arm(WATCHDOG_TTL_SECS, &self.undo_globals)
                    && self.wmd_restarted_at.is_none_or(|at| at.elapsed() >= Duration::from_secs(10))
                {
                    self.wmd_restarted_at = Some(Instant::now());
                    log::info!("freeform-enforcer [{}] the window daemon is gone — starting it again", self.key);
                    crate::adb::start_wmd(&self.app, &serial);
                    if self.wm.arm(WATCHDOG_TTL_SECS, &self.undo_globals) {
                        log::info!("freeform-enforcer [{}] the window daemon is back and armed", self.key);
                    }
                }
            }
        }
        // grep on the device: the full dump is hundreds of KB and shipping
        // it 5x/second would dominate the tick; the parser only needs these
        // four line shapes. Measured ~70ms — the launcher's request queue is
        // drained on its own thread precisely because it costs ~1s and used
        // to stretch this poll (and with it the taskbar) to over a second.
        let Some(dump) = self.run(
            "dumpsys activity activities | grep -E 'Display #|\\* Task|mBounds=Rect| u0 |requestedOrientation='",
        ) else {
            return;
        };
        // Parsed for every display, not just ours: telling "this app was closed" apart
        // from "the phone took this window" needs to see where the task went.
        let mut displays = parse_all_display_tasks(&dump);
        // First, before anything of ours reacts to it: what changed since the last poll.
        self.track_moves(&displays, display);
        let tasks = match displays.iter().position(|(id, _)| *id == display) {
            Some(ix) => displays.remove(ix).1,
            None => Vec::new(),
        };
        self.reclaim_pass(&tasks, &displays, display);
        self.window_pass(&tasks, display, density);
        self.window_state_pass(&tasks, display, density);
        self.broadcast(&tasks);
        self.transfer_pass();
    }
}

/// The session's virtual display and its density, or None once the session
/// is gone (which ends both loops below).
pub(crate) fn session_display(app: &AppHandle, key: &str) -> Option<(i32, i32)> {
    let state = app.state::<MirrorState>();
    let map = state.0.lock().unwrap();
    map.get(key).map(|s| {
        (
            s.display_id.load(Ordering::SeqCst),
            s.density.load(Ordering::SeqCst),
        )
    })
}

/// The session's current child has its display: its server is up and listening.
pub(crate) fn session_has_display(app: &AppHandle, key: &str) -> bool {
    session_display(app, key).is_some_and(|(display, _)| display >= 0)
}

/// End the session's child and not the session: the monitor reads it as a
/// failure and acts on it — dex.rs's way of handing a desktop to scrcpy.
pub(crate) fn kill_child(app: &AppHandle, key: &str) {
    let child = {
        let state = app.state::<MirrorState>();
        let map = state.0.lock().unwrap();
        map.get(key).map(|s| s.child.clone())
    };
    if let Some(child) = child {
        let _ = child.lock().unwrap().kill();
    }
}

/// Each live desktop's shared state, for what reaches it from outside its own
/// threads: the desktop's own window going fullscreen by itself.
fn shared_states() -> &'static Mutex<HashMap<String, std::sync::Weak<Shared>>> {
    static SHARED: std::sync::OnceLock<Mutex<HashMap<String, std::sync::Weak<Shared>>>> = std::sync::OnceLock::new();
    SHARED.get_or_init(Default::default)
}

/// The desktop's window is fullscreen now, or not: the taskbar's ⛶ follows.
pub(crate) fn record_desktop_fullscreen(key: &str, on: bool) {
    if let Some(shared) = shared_states().lock().unwrap().get(key).and_then(|w| w.upgrade()) {
        shared.record_fullscreen(on);
    }
}

/// The size a session's display was actually created at, once scrcpy has
/// announced it. None before that, and for a display only ever found through
/// dumpsys (see `spawn_display_watchdog`), where the size asked for is all
/// there is to go on.
fn session_display_size(app: &AppHandle, key: &str) -> Option<(i32, i32)> {
    let state = app.state::<MirrorState>();
    let map = state.0.lock().unwrap();
    map.get(key).and_then(|s| *s.display_size.lock().unwrap())
}

fn set_session_display_size(app: &AppHandle, key: &str, size: (i32, i32)) {
    let state = app.state::<MirrorState>();
    let map = state.0.lock().unwrap();
    if let Some(s) = map.get(key) {
        *s.display_size.lock().unwrap() = Some(size);
    }
}

/// How often a display that follows its window is measured on the phone, on
/// top of what the server reports as it resizes.
const FLEX_SIZE_CHECK: Duration = Duration::from_millis(2500);

/// `wm size -d N` → the display's own size: "Physical size: 2832x1706".
fn physical_size(out: &str) -> Option<(i32, i32)> {
    out.lines()
        .find_map(|l| l.trim().strip_prefix("Physical size:"))
        .and_then(|v| parse_display_size(v.trim()))
        .filter(|(w, h)| *w > 0 && *h > 0)
}

/// The Android display a session is showing, for callers outside this module.
///
/// The gesture engine needs it per gesture rather than once: an auto-reconnect
/// mints a new virtual display, and a keyevent sent to the old id lands
/// nowhere.
pub fn session_display_id(app: &AppHandle, key: &str) -> Option<i32> {
    session_display(app, key).map(|(display, _)| display)
}

/// The pid of a session's scrcpy process, or `None` once it has ended.
///
/// Used to answer "is the DeX window the one in front" — and re-read per
/// question for the same reason as the display id: a reconnect replaces the
/// process without replacing the session.
///
/// **Never blocks**, and that is the whole point of it existing separately from
/// the other readers of this map. Its callers are the touchpad reader, the
/// macOS event taps and the Windows keyboard hook — and the last of those is
/// called by the system for every keystroke on the machine, with the sending
/// thread's input stalled until it returns. `kill_all` holds this map across a
/// process kill, so a blocking lock here would put a hitch in the user's typing
/// at exactly the moment the app is shutting down, and a hook that is slow
/// enough often enough is one Windows silently uninstalls.
///
/// A contended read answers `None`, which every caller reads as "not the DeX
/// window" and passes the input on untouched. Losing one gesture or one Escape
/// to a lock that was busy for a microsecond is the right trade.
pub fn session_pid(app: &AppHandle, key: &str) -> Option<u32> {
    let state = app.state::<MirrorState>();
    let map = state.0.try_lock().ok()?;
    map.get(key).map(|s| s.pid())
}

fn spawn_freeform_enforcer(
    app: AppHandle,
    serial: String,
    key: String,
    display_size: Option<(i32, i32)>,
    flex: bool,
    stop: Arc<AtomicBool>,
) {
    let shared = Arc::new(Shared::default());
    shared_states().lock().unwrap().insert(key.clone(), Arc::downgrade(&shared));

    // Task poller: reads the display's task list and keeps windows freeform,
    // maximized windows pinned above the taskbar, and the taskbar's open-apps
    // row in step with what is actually on screen.
    {
        let (app, key, shared, stop) = (app.clone(), key.clone(), shared.clone(), stop.clone());
        let serial = serial.clone();
        // Read here rather than per-arm: it comes off disk, the values cannot
        // change mid-session, and a session that ends normally deletes the
        // snapshot it is read from.
        let undo_globals = crate::adb::undo_globals_script(&app, &serial);
        thread::spawn(move || {
            let mut enforcer = Enforcer {
                shell: ShellSession::new(serial),
                app,
                key,
                display_size,
                flex,
                refit: false,
                next_size_check: Instant::now(),
                wins: Default::default(),
                owned: Default::default(),
                reclaims: Default::default(),
                unshapeable: Default::default(),
                seen_on: Default::default(),
                wm: crate::wm::WmClient::new(),
                last_display: -1,
                phone_aspect: 1080.0 / 2340.0,
                default_freeform: false,
                undo_globals,
                // As if just started: the deploy starts the daemon itself a
                // moment after the display comes up, and a second start racing
                // it would only kill the first.
                wmd_restarted_at: Some(Instant::now()),
                last_pkgs: None,
                last_transfer: None,
                tseq: 0,
                next_stat: Instant::now(),
                bseq: 0,
                ticks: 0,
                shared,
            };
            loop {
                if stop.load(Ordering::SeqCst) {
                    return;
                }
                let Some((display, density)) = session_display(&enforcer.app, &enforcer.key) else {
                    return; // session ended
                };
                if display >= 0 {
                    enforcer.tick(display, density);
                }
                // How long a caption press leaves the window fullscreen is
                // detection latency plus the conversion, so the poll period
                // is half of what the user sees as the flash. The dump costs
                // ~70ms of system_server time, which is what stops this from
                // going lower. While a transition is in flight, poll harder
                // so it is noticed as landed the moment it does.
                let converging = enforcer.wins.values().any(|w| w.pending.is_some());
                thread::sleep(Duration::from_millis(if converging { 50 } else { 100 }));
            }
        });
    }

    // Escape leaves fullscreen. Unlike the gesture engine this is started on
    // every host and whatever hardware is attached — a mouse user can put the
    // desktop fullscreen from the taskbar just as easily, and then the only
    // way back is a button that is now behind the video.
    crate::hotkeys::start(app.clone(), key.clone(), shared.clone(), stop.clone());

    // Touchpad gestures: its own thread again, and for a harder reason than
    // the pump's. The reader blocks in the host's event loop rather than
    // polling, and a three-finger swipe has to land in tens of milliseconds —
    // handing it to a 100 ms poll would add a poll period of jitter to
    // something the user performed with their hand.
    crate::gestures::start(
        app.clone(),
        serial.clone(),
        key.clone(),
        shared.clone(),
        stop.clone(),
    );

    // The file service the phone's File transfer window talks to. Started
    // per session and torn down with it; the reverse is what lets the phone
    // reach it at all, and without one the window says so rather than hanging.
    if let Some(host_port) = crate::files::start(app.clone(), key.clone(), stop.clone()) {
        crate::adb::reverse_files_port(&app, &serial, host_port);
    }

    // Request pump: its own thread and adb shell, because one drain of the
    // launcher's queue costs ~1s on the device.
    thread::spawn(move || {
        let mut pump = RequestPump {
            wm: crate::wm::WmClient::new(),
            fast: false,
            shell: ShellSession::new(serial),
            app,
            key,
            last_req_id: 0,
            shared,
        };
        loop {
            if stop.load(Ordering::SeqCst) {
                return;
            }
            let Some((display, _)) = session_display(&pump.app, &pump.key) else {
                return;
            };
            if display >= 0 {
                pump.tick(display);
            }
            thread::sleep(pump.poll_interval());
        }
    });
}

/// The ways scrcpy reports "I could not launch the adb you gave me", on either
/// host. Windows fails inside `CreateProcessW`; the posix backend fails in
/// `execvp`, and a resource that arrived without its executable bit fails there
/// with a plain permission error.
///
/// Shared by [`explain_failure`] and [`worth_degrading`] so the two cannot
/// drift: the second exists to stop a retry that would fail identically, and a
/// marker known to only one of them is a retry loop with the real message
/// buried under a copy of itself.
/// Deliberately NOT "permission denied": that phrase is far more often the
/// PHONE refusing something (`adb: error: failed to copy … Permission denied`
/// while pushing scrcpy-server is the common one), and this list is checked
/// before the device-side branches. Matching it here would answer a phone
/// problem with "move the app to your Applications folder" and, worse, mark the
/// failure un-retryable so the stripped-down second attempt never runs. An adb
/// that cannot be executed — including one whose executable bit was lost —
/// still lands on "could not execute", which is what scrcpy's posix backend
/// prints when the exec fails for any reason.
const ADB_LAUNCH_MARKERS: &[&str] = &[
    "could not start adb server",
    "createprocessw",
    "execvp",
    "could not execute",
];

fn adb_would_not_launch(lower: &str) -> bool {
    ADB_LAUNCH_MARKERS.iter().any(|m| lower.contains(m))
}

/// Turn scrcpy's parting words into something a user can act on.
fn explain_failure(tail: &str) -> String {
    let t = tail.to_lowercase();
    if adb_would_not_launch(&t) {
        // scrcpy could not launch the adb we handed it — our problem, not the
        // phone's, and worth saying so plainly: everything about the phone
        // looks fine in this case, which sends people hunting in the wrong place.
        //
        // The remedy differs by host because the cause does. On Windows it is
        // almost always the unquoted command line scrcpy builds for its adb
        // child tripping over a space in the path. On macOS there is no command
        // line to trip over — it is Gatekeeper refusing a quarantined binary,
        // or a lost executable bit. On Linux there is no Gatekeeper either, and
        // the remaining causes are the executable bit and a missing libudev,
        // which is the one shared library the bundled adb and scrcpy need and
        // the only one a very small install might not already have.
        if cfg!(windows) {
            "The bundled adb could not be launched by scrcpy. This is a problem with this \
             installation, not with the phone — try unzipping the app somewhere else, ideally \
             a path with no spaces in it."
                .into()
        } else if cfg!(target_os = "macos") {
            "The bundled adb could not be launched by scrcpy. This is a problem with this \
             installation, not with the phone — move Open Android DeX into your Applications \
             folder and open it again. If macOS has quarantined it, right-click the app and \
             choose Open once to allow it."
                .into()
        } else {
            "The bundled adb could not be launched by scrcpy. This is a problem with this \
             installation, not with the phone — check that the bundled adb is still executable \
             (chmod +x), and that libudev is installed. Reinstalling the package restores both."
                .into()
        }
    } else if t.contains("could not create display")
        || t.contains("new virtual display is not supported")
    {
        "This phone refused to create the virtual display. Android 14 or newer is required for \
         the desktop, and some devices additionally block it for the shell user."
            .into()
    } else if t.contains("encoder") && (t.contains("not found") || t.contains("could not create")) {
        "The selected video encoder does not exist on this phone. Reset the codec to Auto in the \
         desktop's Settings window."
            .into()
    } else if t.contains("unauthorized") {
        "The phone has not authorised this computer for USB debugging — accept the prompt on the \
         phone and try again."
            .into()
    } else if t.contains("device disconnected") || t.contains("device offline") {
        "The phone disconnected while the desktop was starting.".into()
    } else if t.contains("adb: error") || t.contains("failed to execute") {
        "adb could not talk to the phone.".into()
    } else {
        // the most specific line scrcpy gave us, or nothing at all
        tail.lines()
            .rev()
            .find(|l| l.contains("ERROR"))
            .map(|l| l.trim().to_string())
            .unwrap_or_else(|| "scrcpy stopped before the desktop display existed.".into())
    }
}

/// Whether a degraded retry could plausibly help.
///
/// It only ever strips stream options, so it is worth an attempt when scrcpy
/// died somewhere those options are in play. A session that never got as far
/// as talking to the phone — a bundled adb that will not launch, a device that
/// is not authorised — fails identically the second time, and retrying only
/// buries the real message under a second copy of itself.
fn worth_degrading(tail: &str) -> bool {
    let t = tail.to_lowercase();
    !(adb_would_not_launch(&t)
        || t.contains("unauthorized")
        || t.contains("device offline")
        || t.contains("could not find adb device"))
}

/// A stripped-down version of the same session, or `None` when it is already
/// as plain as it gets.
///
/// Everything dropped here is optional decoration around the one thing that
/// matters — a virtual display — and each of them can abort scrcpy on its own:
/// an encoder name that only exists on the phone it was chosen on (the stream
/// settings are stored per PC, not per phone), a codec the device lacks, an
/// audio source it refuses. Trying again without them turns "the desktop never
/// came up" into "the desktop came up without audio", and the log says which.
fn fallback_options(opts: &MirrorOptions) -> Option<MirrorOptions> {
    let strippable = opts.audio
        || opts.video_codec.is_some()
        || opts.video_encoder.is_some()
        || opts.mouse_bind.is_some()
        || opts.mouse_mode.is_some()
        || opts.max_fps > 0
        || opts.video_bit_rate_mbps > 0
        || opts.fitted;
    if !strippable {
        return None;
    }
    // A display fitted to the screen goes back to the fixed 1080p it replaced,
    // which every phone has run, at the same Display size preset.
    let (new_display, base_dpi) = if opts.fitted {
        let factor = requested_density(opts)
            .zip(opts.base_dpi)
            .map(|(dpi, base)| f64::from(dpi) / f64::from(base.max(1)))
            .unwrap_or(1.0);
        let dpi = ((160.0 * factor).round() as i32).clamp(DPI_MIN, DPI_MAX);
        (Some(format!("1920x1080/{dpi}")), Some(160))
    } else {
        (opts.new_display.clone(), opts.base_dpi)
    };
    Some(MirrorOptions {
        new_display,
        base_dpi,
        window: None,
        fitted: false,
        flex: None,
        audio: false,
        audio_playback: false,
        video_codec: None,
        video_encoder: None,
        mouse_bind: None,
        // uhid is the likeliest single cause of an abort in this list: it needs
        // scrcpy >= 3.3 to reach a virtual display at all, and write access to
        // /dev/uhid from the shell uid. Dropping it costs the custom cursors
        // and keeps the desktop.
        mouse_mode: None,
        max_fps: 0,
        video_bit_rate_mbps: 0,
        ..opts.clone()
    })
}

/// Watch one scrcpy process: report exits, optionally respawn when the
/// device drops (non-zero exit while auto-reconnect is on).
fn monitor(app: AppHandle, mut opts: MirrorOptions, attempt: Attempt, stop: Arc<AtomicBool>) {
    let key = opts.session_key();
    let Attempt {
        mut child,
        mut pid,
        mut display_id,
        mut output,
    } = attempt;
    // Only one degraded retry per session — a second would be the same
    // command line again.
    let mut tried_fallback = false;
    loop {
        let status = loop {
            {
                let mut guard = child.lock().unwrap();
                match guard.try_wait() {
                    Ok(Some(st)) => break Some(st),
                    Ok(None) => {}
                    Err(_) => break None,
                }
            }
            thread::sleep(Duration::from_millis(300));
        };

        let intentional = stop.load(Ordering::SeqCst);
        let success = status.map(|s| s.success()).unwrap_or(false);
        let exit_code = status.and_then(|s| s.code());
        let had_display = display_id.load(Ordering::SeqCst) >= 0;
        log::info!(
            "scrcpy [{key}] pid {pid} exited (code={exit_code:?}, intentional={intentional}, \
             display={})",
            if had_display { "yes" } else { "never" }
        );

        // The desktop's own video could not start — its server never made a
        // display — or its window asked for scrcpy's (no WebCodecs, or the
        // person): the same desktop in scrcpy's window. Remembered for this
        // phone until the app starts again, so a reconnect does not try first.
        let asked = opts.own_video && crate::dex::take_fallback(&key);
        if opts.own_video && !intentional && (asked || !had_display) {
            log::warn!(
                "mirror [{key}] the desktop's own video {} — scrcpy's window instead. Last output:\n{}",
                if asked { "was given up by its window" } else { "could not start" },
                output_tail(&output, 20)
            );
            crate::dex::remember_fell_back(&opts.serial);
            crate::dex::close_window(&app, &key);
            apply_stored_config(&app, &mut opts);
            apply_stored_density(&app, &mut opts);
            notice(
                &app,
                &key,
                if asked {
                    "The desktop is in its classic window now."
                } else {
                    "This phone could not send the desktop's own video, so it opened in the classic window."
                },
            );
            match spawn_scrcpy(&app, &opts) {
                Ok(new_child) => {
                    let next = register_session(&app, &opts, new_child, stop.clone());
                    pid = next.pid;
                    child = next.child;
                    display_id = next.display_id;
                    output = next.output;
                    continue;
                }
                Err(e) => log::error!("mirror [{key}] scrcpy could not be started either: {e}"),
            }
        }

        // A session that died without ever getting a display did not lose the
        // phone — it could not work with the options it was given. Reconnecting
        // it forever (which is what auto-reconnect used to do) just repeats the
        // same failure with nobody watching, which is how a phone ended up
        // parked on "Creating virtual display…" indefinitely.
        if !intentional && !success && !had_display && opts.new_display.is_some() {
            let tail = output_tail(&output, 30);
            let reason = explain_failure(&tail);
            log::error!("mirror [{key}] could not start: {reason}");

            if let (false, true, Some(minimal)) = (
                tried_fallback,
                worth_degrading(&tail),
                fallback_options(&opts),
            ) {
                tried_fallback = true;
                log::warn!(
                    "mirror [{key}] retrying without audio, codec/encoder pinning and mouse \
                     bindings{} — one of them is the likeliest cause",
                    if opts.fitted { ", at 1920x1080" } else { "" }
                );
                let _ = app.emit(
                    "mirror:notice",
                    serde_json::json!({
                        "sessionKey": key,
                        "text": "First attempt failed — retrying with audio and codec options off",
                    }),
                );
                opts = minimal;
                if let Ok(new_child) = spawn_scrcpy(&app, &opts) {
                    let next = register_session(&app, &opts, new_child, stop.clone());
                    pid = next.pid;
                    child = next.child;
                    display_id = next.display_id;
                    output = next.output;
                    continue;
                }
                log::error!("mirror [{key}] the reduced retry could not be spawned either");
            }

            let _ = app.emit(
                "mirror:failed",
                serde_json::json!({
                    "sessionKey": key,
                    "serial": opts.serial,
                    "reason": reason,
                    "detail": tail,
                }),
            );
            // fall through to cleanup: no reconnect loop for a configuration
            // that has already proved it cannot start
        } else if !intentional && opts.auto_reconnect && !success {
            emit_status(
                &app,
                MirrorEvent {
                    session_key: key.clone(),
                    serial: opts.serial.clone(),
                    app_package: opts.app_package.clone(),
                    status: "reconnecting".into(),
                    pid: None,
                    exit_code,
                    intentional: false,
                },
            );
            log::info!("mirror [{key}] waiting for the phone to come back");
            // A desktop whose phone undid it while the cable was out — the
            // window daemon's watchdog does that WATCHDOG_TTL_SECS after the PC
            // goes quiet — has lost its display profile, its grants and the
            // daemon itself. A new display on its own would be a desktop half
            // set up, with nothing on the phone left to take it off again; the
            // window starts it over instead, as a fresh connect does.
            let back = wait_for_device(&app, &opts.serial, &stop) && !stop.load(Ordering::SeqCst);
            if back && opts.new_display.is_some() && opts.app_package.is_none() && !desktop_still_set_up(&app, &opts.serial) {
                log::info!("mirror [{key}] the phone undid the session while it was away — setting it up again");
                let _ = app.emit("desktop:relaunch", serde_json::json!({ "sessionKey": key }));
            } else if back {
                // A size or stream setting picked mid-session must survive the
                // reconnect — unless this session only got going by dropping
                // those very settings, in which case restoring them from the
                // store would re-break it.
                if !tried_fallback {
                    apply_stored_config(&app, &mut opts);
                }
                apply_stored_density(&app, &mut opts);
                // Settings changed while the phone was away may have moved the
                // desktop between the two windows.
                if !opts.own_video {
                    crate::dex::close_window(&app, &key);
                }
                match spawn_session(&app, &opts) {
                    Ok(new_child) => {
                        let next = register_session(&app, &opts, new_child, stop.clone());
                        if opts.own_video {
                            if let Err(e) = crate::dex::open_window(&app, &key, &opts) {
                                log::warn!("mirror [{key}] the desktop's window did not open again: {e}");
                            }
                        }
                        pid = next.pid;
                        child = next.child;
                        display_id = next.display_id;
                        output = next.output;
                        emit_status(
                            &app,
                            MirrorEvent {
                                session_key: key.clone(),
                                serial: opts.serial.clone(),
                                app_package: opts.app_package.clone(),
                                status: "running".into(),
                                pid: Some(pid),
                                exit_code: None,
                                intentional: false,
                            },
                        );
                        // The audio companion has no reconnect of its own — it
                        // died with the cable. A desktop that just came back
                        // brings it back too.
                        if opts.new_display.is_some() && opts.app_package.is_none() {
                            reconcile_audio_session_bg(&app, &opts.serial);
                        }
                        continue;
                    }
                    Err(e) => log::error!("mirror [{key}] reconnect failed: {e}"),
                }
            }
        }

        // Final cleanup — only remove the entry if it is still ours.
        let state = app.state::<MirrorState>();
        {
            let mut map = state.0.lock().unwrap();
            if map.get(&key).map(|s| s.pid) == Some(pid) {
                map.remove(&key);
            }
        }
        // The session is over however it ended, so everything hanging off it
        // ends too — the enforcer threads and, in particular, the `adb logcat`
        // feeding the device log, which would otherwise outlive every session
        // the user ever started.
        stop.store(true, Ordering::SeqCst);
        transfer::forget(&key);
        files::forget(&key);
        // The desktop's own window goes with its session, however it ended.
        crate::dex::close_window(&app, &key);
        shared_states().lock().unwrap().remove(&key);
        // …and so does the audio companion, whose lifetime nests inside the
        // desktop's. Left running it would keep diverting the phone's sound
        // to a computer that no longer shows anything. A desktop RESTART
        // passes through here too; the relaunch's start_mirror reconciles a
        // fresh companion, so the sound is back within a couple of seconds.
        //
        // BEFORE the "stopped" emit below, and that ordering is an invariant:
        // every relaunch path (desktop:restart, the wireless switch) starts
        // the next desktop only after hearing "stopped", so this stop can
        // never race a new desktop's freshly reconciled companion. A caller
        // that started a desktop for this serial earlier than that would have
        // its companion killed by this line — as silence, not as a leak.
        if opts.new_display.is_some() && opts.app_package.is_none() && !opts.audio_only {
            stop_audio_companion(&app, &opts.serial);
        }
        emit_status(
            &app,
            MirrorEvent {
                session_key: key,
                serial: opts.serial,
                app_package: opts.app_package,
                status: "stopped".into(),
                pid: None,
                exit_code,
                intentional,
            },
        );
        break;
    }
}

/// Kill scrcpy servers this device is still running from a previous life of this app.
///
/// A `--new-display` virtual display is owned by the server process ON THE PHONE, not by
/// scrcpy.exe. A PC app that was killed rather than closed therefore leaves the display
/// behind — and with it the LauncherActivity resumed on it, which holds
/// `topDisplayFocusedRootTask` for the WHOLE DEVICE. Android sends a brand-new task to the
/// top-focused display area whenever the launch does not pin one, so every app the user
/// opens fresh on the phone lands on a display nothing is rendering any more. Apps that
/// are already running behave perfectly, because those are found by task lookup on
/// display 0 first — which is exactly the "existing apps are fine, new ones get pulled
/// away" shape this bug has.
///
/// Measured on SM-S938B: the app had been closed for 19 hours and the orphan was still
/// there, still holding focus. Killing it alone returned `topDisplayFocusedRootTask` to
/// the phone's own home task.
///
/// Only `.Server` is matched: scrcpy's `CleanUp` process is what restores the phone's
/// settings when the server dies, so it has to outlive it. Skipped entirely while any
/// session for this device is live, because the pattern cannot tell our own server from
/// an orphan.
fn sweep_orphan_servers(app: &AppHandle, serial: &str) {
    let live = {
        let state = app.state::<MirrorState>();
        let map = state.0.lock().unwrap();
        map.iter().any(|(_, s)| {
            s.serial == serial
                && s.child
                    .lock()
                    .unwrap()
                    .try_wait()
                    .map(|st| st.is_none())
                    .unwrap_or(false)
        })
    };
    if live {
        return;
    }
    match adb::run_adb(
        app,
        &[
            "-s",
            serial,
            "shell",
            "s=; pkill -f '[c]om.genymobile.scrcpy.Server' && s=1; \
             pkill -f '[c]om.ccrstech.openandroiddex.wmd.Desktop' && s=1; \
             [ -n \"$s\" ] && echo swept || true",
        ],
    ) {
        Ok(out) if out.contains("swept") => log::info!(
            "{serial}: killed a scrcpy server left over from a previous run — its virtual \
             display was still holding this phone's top-display focus"
        ),
        Ok(_) => log::debug!("{serial}: no orphaned scrcpy server"),
        Err(e) => log::warn!("{serial}: could not check for orphaned scrcpy servers: {e}"),
    }
}

/// A banner on the launch screen, which is where the desktop's own status
/// already lives.
fn notice(app: &AppHandle, session_key: &str, text: &str) {
    let _ = app.emit(
        "mirror:notice",
        serde_json::json!({ "sessionKey": session_key, "text": text }),
    );
}

/// The two ways a phone hands back a desktop that streams perfectly and
/// ignores every click.
///
/// Neither one makes scrcpy exit, so neither reaches `explain_failure`: by
/// every measure we have the session is healthy, and what the user is left
/// holding is a dead desktop that reads as our bug. Both are knowable BEFORE
/// the session exists, which is the only moment at which saying so is useful.
///
/// ONE adb round trip for all of it. `input` boots a JVM (~0.5 s, and slowest
/// on exactly the phones that fail this) while the two getprops are free
/// beside it, so they ride the same shell rather than paying for three.
///
/// Err = nothing will ever work, and refusing to start is kinder than
/// starting. A notice = it MIGHT work, and taking the session away on a guess
/// is worse than a banner.
/// Whether the display the phone just made can hold input focus.
///
/// `FLAG_TRUSTED` is the bit that decides it. Without it the desktop still
/// paints and the launcher still runs, and the display reports
/// `mCurrentFocus=null` for as long as it exists — a desktop that streams
/// beautifully and ignores you.
///
/// Asked OF THE DISPLAY rather than inferred from an API level, because the
/// answer is a property of the build: shell can only request a trusted
/// display where the platform has `ADD_TRUSTED_DISPLAY`, which does not exist
/// at all before Android 11 and which a manufacturer is free to withhold
/// after it. A version number is wrong in both directions — it condemns
/// builds that work and clears builds that do not.
fn display_is_trusted(dump: &str) -> bool {
    dump.contains("FLAG_TRUSTED")
}

/// The `DisplayInfo{…}` line for logical display `id`, out of `dumpsys display`.
///
/// Matched on the character after the number as well as the number, so display 1
/// does not answer display 11's question — and that character depends on the
/// release. Android 10 closes the quoted name with it (`DisplayInfo{"scrcpy,
/// displayId 11", …`), Android 12 still prints a stray quote (`DisplayInfo{"scrcpy",
/// displayId 15", …`), and Android 13 and later a comma (`displayId 2, …`). Matching
/// the quote alone never found the display on 13+, so a phone there whose maker
/// withholds trusted displays was never told why typing did not work.
fn display_info_line(dump: &str, id: i32) -> Option<&str> {
    let needle = format!("displayId {id}");
    dump.lines().find(|line| {
        line.contains("DisplayInfo{")
            && line
                .match_indices(needle.as_str())
                .any(|(at, _)| matches!(line[at + needle.len()..].chars().next(), Some('"' | ',')))
    })
}

/// One dumpsys, once per session, off the thread that reads scrcpy's output.
fn warn_if_display_untrusted(app: &AppHandle, serial: &str, session_key: &str, id: i32) {
    // Every display's info line, a handful of them; picking ours is display_info_line's
    // job, where the format can be tested rather than spelled in a grep pattern.
    let query = "dumpsys display | grep -F 'DisplayInfo{'";
    let Ok(dump) = adb::run_adb(app, &["-s", serial, "shell", query]) else {
        log::warn!("{serial}: could not read the flags of display {id}");
        return;
    };
    let Some(line) = display_info_line(&dump, id) else {
        log::warn!("{serial}: display {id} is not in dumpsys — cannot tell if it is trusted");
        return;
    };
    if display_is_trusted(line) {
        log::info!("{serial}: display {id} is trusted — windows on it can take focus");
        return;
    }
    log::warn!("{serial}: display {id} is NOT trusted: {}", line.trim());
    notice(
        app,
        session_key,
        // Not "too old": this also fires on Android 13+ phones whose maker withholds
        // trusted displays. And "some": a Galaxy A13 gave Clock a window, YouTube none.
        concat!(
            "This phone doesn't fully support app windows. Some apps open full screen, ",
            "and typing won't work. To leave an app, use Home screen below.",
        ),
    );
}

/// What the phone said, whichever of adb's two paths it came back on.
struct Probe {
    release: String,
    sdk: Option<u32>,
    refused: bool,
}

/// The first two lines are the getprops, in the order they were asked for;
/// anything after them is `input` complaining.
///
/// Two spellings, because the refusal is worded differently across Android
/// versions and OEM skins — the permission's own name appears in all of them,
/// the exception class in most. Neither string can occur in a version number,
/// so a phone that simply answered cannot trip this.
fn read_probe(out: &str) -> Probe {
    let mut lines = out.lines();
    let release = lines.next().unwrap_or("").trim().to_string();
    let sdk = lines.next().and_then(|l| l.trim().parse().ok());
    let lowered = out.to_lowercase();
    Probe {
        release,
        sdk,
        refused: lowered.contains("inject_events") || lowered.contains("securityexception"),
    }
}

fn preflight_input(app: &AppHandle, opts: &MirrorOptions) -> Result<(), String> {
    // The audio companion is a second scrcpy on the same phone that carries no
    // input at all. It would fail this check for nothing, and pay a JVM for
    // the privilege.
    if opts.audio_only {
        return Ok(());
    }
    // KEYCODE_UNKNOWN: injected exactly like any other key, ignored by every
    // app that receives it. So a phone that permits injection prints NOTHING
    // and one that does not prints its refusal — there is no third answer, and
    // nothing on screen moves either way.
    let probe = adb::run_adb(
        app,
        &[
            "-s",
            opts.serial.as_str(),
            "shell",
            "getprop ro.build.version.release; getprop ro.build.version.sdk; \
             input keyevent 0 2>&1",
        ],
    );
    // A REFUSAL EXITS NON-ZERO, so the case this whole function exists for
    // arrives as Err, not Ok. The text is identical either way — the `2>&1`
    // above folded the phone's complaint into its stdout, and run_adb hands
    // back stdout when there is no stderr. What genuinely-absent looks like
    // is adb's own "stopped responding": no version line and no refusal in
    // it, which falls through every test below and starts the session. That
    // is deliberate — a phone that did not answer is usually one still
    // settling after being plugged in, the same reason `device_sdk` reads
    // silence as able rather than unable.
    let out = match probe {
        Ok(out) => out,
        Err(err) => err,
    };
    let Probe {
        release,
        sdk,
        refused,
    } = read_probe(&out);

    if refused {
        log::error!(
            "{}: the phone refuses input injected from this computer",
            opts.serial
        );
        // uhid does not use the API being blocked — it asks the kernel for a
        // HID mouse instead, so the POINTER survives. The keyboard does not:
        // there is no uhid keyboard in this session.
        if opts.mouse_mode.as_deref() == Some("uhid") {
            notice(
                app,
                &opts.session_key(),
                "This phone is blocking keystrokes sent from a computer, so typing will not \
                 reach it. The mouse still works. To fix the keyboard, open Developer options \
                 on the phone, turn on \"USB debugging (Security settings)\", and restart it.",
            );
        } else {
            return Err("This phone is blocking clicks and keystrokes sent from a computer. \
                 The desktop would open and then ignore everything you do in it.\n\n\
                 On Xiaomi, Redmi and POCO phones this is a setting: open Developer options, \
                 turn on \"USB debugging (Security settings)\", and restart the phone. A few \
                 other brands have the same switch under a slightly different name."
                .into());
        }
    }

    // Deliberately NOT a version check. Whether the desktop can be clicked
    // turns on whether the display comes back TRUSTED, and that is a property
    // of the build rather than of the API level - `warn_if_display_untrusted`
    // asks the display itself, once it exists. This line only puts the version
    // in the log beside that answer, so the two can be read together.
    log::info!("{}: Android {release} (SDK {sdk:?})", opts.serial);
    Ok(())
}

#[tauri::command(async)]
pub fn start_mirror(app: AppHandle, mut options: MirrorOptions) -> Result<SessionInfo, String> {
    if options.serial.trim().is_empty() {
        return Err("no device serial given".into());
    }
    // resolution/codec first: it rewrites the --new-display base that the
    // density is appended to
    apply_stored_config(&app, &mut options);
    // desktop displays are born at the user's chosen density — no zoom flash
    apply_stored_density(&app, &mut options);
    let key = options.session_key();
    log::info!("── start session [{key}] ──");
    log::info!(
        "options: display={:?} fitted={} default={:?}dpi window={:?} decorations={} audio={} \
         codec={:?} encoder={:?} bitrate={}Mbps fps={} freeform={} reconnect={} video={}",
        options.new_display,
        options.fitted,
        options.base_dpi,
        options.window,
        !options.vd_no_decorations,
        options.audio,
        options.video_codec,
        options.video_encoder,
        options.video_bit_rate_mbps,
        options.max_fps,
        options.freeform,
        options.auto_reconnect,
        if options.own_video { "own window" } else { "scrcpy" }
    );

    let state = app.state::<MirrorState>();
    {
        let mut map = state.0.lock().unwrap();
        if let Some(existing) = map.get(&key) {
            let exited = existing
                .child
                .lock()
                .unwrap()
                .try_wait()
                .map(|s| s.is_some())
                .unwrap_or(true);
            if !exited {
                return Err(format!("session already running: {key}"));
            }
            map.remove(&key);
        }
    }

    // Before anything is spawned: a phone that will not take input gives
    // back a desktop that streams and ignores you, which is worse than a
    // desktop that never started.
    preflight_input(&app, &options)?;

    sweep_orphan_servers(&app, &options.serial);

    let spawned = spawn_session(&app, &options)?;
    let stop = Arc::new(AtomicBool::new(false));
    let attempt = register_session(&app, &options, spawned, stop.clone());
    let pid = attempt.pid;

    // The phone's own log for as long as this session lives: scrcpy's server
    // logs there too, and so does our launcher. Not for the audio companion —
    // its lifetime nests inside a desktop session that is already streaming
    // this same log, and a second logcat would double every line.
    if !options.audio_only {
        diag::stream_device_log(&app, &options.serial, &key, stop.clone());
    }

    emit_status(
        &app,
        MirrorEvent {
            session_key: key.clone(),
            serial: options.serial.clone(),
            app_package: options.app_package.clone(),
            status: "running".into(),
            pid: Some(pid),
            exit_code: None,
            intentional: false,
        },
    );

    if options.freeform && options.new_display.is_some() && options.app_package.is_none() {
        let size = options.new_display.as_deref().and_then(parse_display_size);
        spawn_freeform_enforcer(
            app.clone(),
            options.serial.clone(),
            key.clone(),
            size,
            // The desktop's own window's display follows it too.
            options.flex.is_some() || options.own_video,
            stop.clone(),
        );
    }

    // A desktop brings its sound along: the audio companion is spawned (or
    // not — "phone only") to match the stored choice. On a thread, because
    // the reconcile asks the phone its API level and this command is on the
    // launch pipeline's critical path.
    if options.new_display.is_some() && options.app_package.is_none() && !options.audio_only {
        reconcile_audio_session_bg(&app, &options.serial);
    }

    // The desktop's own window, opened as its server starts: it says what it is
    // waiting for until the picture comes. One that cannot be opened hands the
    // desktop to scrcpy's window, through the monitor.
    if options.own_video {
        if let Err(e) = crate::dex::open_window(&app, &key, &options) {
            log::warn!("mirror [{key}] the desktop's own window did not open ({e}) — scrcpy's instead");
            crate::dex::request_fallback(&key);
            kill_child(&app, &key);
        }
    }

    let info = SessionInfo {
        session_key: key,
        serial: options.serial.clone(),
        app_package: options.app_package.clone(),
        pid,
        display_id: None,
    };
    let app2 = app.clone();
    thread::spawn(move || monitor(app2, options, attempt, stop));
    Ok(info)
}

/// How long a scrcpy process is given to stop itself before it is killed.
///
/// A ceiling for a hung process, not a budget: scrcpy's teardown is an SDL
/// quit and a few frees, and it lands in tens of milliseconds. It is spent on
/// the app-exit path too, where the user has already asked for the window to
/// go away, so it stays short enough not to read as a slow quit.
#[cfg(unix)]
const EXIT_GRACE: Duration = Duration::from_millis(600);

#[cfg(unix)]
extern "C" {
    /// `kill(2)`, for the one signal `Child::kill` cannot send.
    ///
    /// Declared rather than pulled in: a crate for three lines of C would be
    /// the only reason `libc` appeared in this tree, and the Accessibility API
    /// in `embed/macos.rs` is already declared the same way.
    fn kill(pid: i32, sig: i32) -> i32;
}

/// Stop a scrcpy process, asking before insisting.
///
/// `Child::kill` is SIGKILL on Unix, and an uncatchable signal is the one way
/// to leave the user's POINTER behind. Under `--mouse=uhid` scrcpy's window
/// has the mouse captured — on macOS that is SDL holding
/// `CGAssociateMouseAndMouseCursorPosition(false)` — and the only code that
/// ever hands it back is scrcpy's own shutdown. SIGKILLed, that code never
/// runs and the capture outlives the process that asked for it, so the cursor
/// stays inside a window that is no longer there.
///
/// The next session is then blamed for it. Switching Settings → Mouse &
/// cursor → Pointer rendering back to "Computer" restarts the desktop and the
/// mouse is *still* held — not by the innocent `--mouse=sdk` session that has
/// just started, but by the uhid one this function used to close too bluntly.
///
/// Windows keeps the blunt kill, and that is not an oversight: the OS drops a
/// dead process's cursor clip itself, and asking a GUI process to quit there
/// means finding its window and posting `WM_CLOSE` — machinery bought for a
/// problem that host does not have.
///
/// **Callers must set `stop_requested` first.** A clean exit is exit code 0,
/// and `monitor` reads a zero exit as "did not fail" — which is the right
/// reading only when it already knows the stop was deliberate. The one caller
/// that cannot say that (a session which never produced a display, so the
/// degraded retry has to see a failure) still kills outright, on purpose.
fn shut_down(child: &Mutex<Child>) {
    #[cfg(unix)]
    {
        const SIGTERM: i32 = 15;
        let pid = child.lock().unwrap().id() as i32;
        // scrcpy installs a handler for this — it is the path Ctrl+C already
        // takes — and turns it into an SDL quit, which is the same teardown
        // the window's close button runs.
        if unsafe { kill(pid, SIGTERM) } == 0 {
            let deadline = Instant::now() + EXIT_GRACE;
            while Instant::now() < deadline {
                // try_wait caches the status, so the monitor thread polling
                // this same child still sees the exit after this reaps it.
                if matches!(child.lock().unwrap().try_wait(), Ok(Some(_))) {
                    return;
                }
                thread::sleep(Duration::from_millis(20));
            }
            log::warn!(
                "scrcpy pid {pid} ignored SIGTERM for {}ms — killing it, which can leave the \
                 mouse captured if this session was drawing the pointer on the phone",
                EXIT_GRACE.as_millis()
            );
        }
    }
    let _ = child.lock().unwrap().kill();
}

#[tauri::command(async)]
pub fn stop_mirror(app: AppHandle, session_key: String) -> Result<(), String> {
    let state = app.state::<MirrorState>();
    let handle = {
        let map = state.0.lock().unwrap();
        map.get(&session_key)
            .map(|s| (s.child.clone(), s.stop_requested.clone()))
    };
    match handle {
        Some((child, stop)) => {
            log::info!("mirror [{session_key}] stop requested");
            stop.store(true, Ordering::SeqCst);
            shut_down(&child);
            Ok(())
        }
        None => {
            log::warn!("mirror [{session_key}] stop requested but no such session");
            Err(format!("no active mirror session: {session_key}"))
        }
    }
}

/// Bring a session's scrcpy window to the foreground (taskbar refocus).
///
/// The mechanics are per host and live in `embed`, which is where the rest of
/// the native window control is.
#[tauri::command(async)]
pub fn focus_session(app: AppHandle, session_key: String) -> Result<(), String> {
    if crate::dex::focus(&app, &session_key) {
        return Ok(());
    }
    crate::embed::activate(&app, &session_key)
}

#[tauri::command]
pub fn list_mirror_sessions(app: AppHandle) -> Vec<SessionInfo> {
    let state = app.state::<MirrorState>();
    let map = state.0.lock().unwrap();
    map.iter()
        .map(|(key, s)| {
            let display = s.display_id.load(Ordering::SeqCst);
            SessionInfo {
                session_key: key.clone(),
                serial: s.serial.clone(),
                app_package: s.app_package.clone(),
                pid: s.pid,
                display_id: (display >= 0).then_some(display),
            }
        })
        .collect()
}

/// What each live session's scrcpy has printed lately, for the diagnostics
/// dump — the session that is misbehaving is usually still running.
pub fn live_session_output(app: &AppHandle) -> String {
    let Some(state) = app.try_state::<MirrorState>() else {
        return "no session state\n".into();
    };
    let map = state.0.lock().unwrap();
    if map.is_empty() {
        return "no live sessions\n".into();
    }
    map.iter()
        .map(|(key, s)| {
            format!(
                "[{key}] pid {} display {} density {}\n{}",
                s.pid,
                s.display_id.load(Ordering::SeqCst),
                s.density.load(Ordering::SeqCst),
                output_tail(&s.output, 40)
            )
        })
        .collect::<Vec<_>>()
        .join("\n\n")
}

/// Kill every running session — called when the app exits.
///
/// The phone is put back too. Closing the desktop app is as much an "exit
/// DeX" as pressing the button is, and a phone left with freeform windowing
/// and a relaxed hidden-API policy on it is the state users notice.
pub fn kill_all(app: &AppHandle) {
    // Held across the whole exit: a reconcile that has already read "the
    // desktop is live" is seconds from spawning an audio companion, and one
    // spawned after the snapshot below would be an ownerless scrcpy diverting
    // the phone's sound long after this app is gone. Behind the lock the
    // reconcile either finished (its companion is in the snapshot) or starts
    // after the desktops are dead and declines to spawn.
    let _audio_guard = audio_lock();
    let mut serials: Vec<String> = Vec::new();
    // Collected under the lock and stopped outside it. `shut_down` waits, and
    // the session map is read by the Escape hook on every keystroke the
    // machine sees (`session_pid`) — that reader answers "not our window" and
    // passes the key on when the lock is busy, so holding it across the wait
    // would spend the user's last half second in the app deaf to Escape.
    let mut children: Vec<(String, Arc<Mutex<Child>>)> = Vec::new();
    if let Some(state) = app.try_state::<MirrorState>() {
        let map = state.0.lock().unwrap();
        for (key, s) in map.iter() {
            s.stop_requested.store(true, Ordering::SeqCst);
            children.push((key.clone(), s.child.clone()));
            if !serials.contains(&s.serial) {
                serials.push(s.serial.clone());
            }
        }
    }
    for (key, child) in children {
        shut_down(&child);
        log::info!("mirror [{key}] stopped on app exit");
    }
    // Outside the lock: the restore talks to adb, and holding the session map
    // across seconds of I/O would block anything else that touches it.
    for serial in serials {
        crate::adb::restore_phone(app, &serial);
    }
}

#[cfg(test)]
mod tests {

    /// The launcher queues every request twice — once in the ContentProvider, once in the
    /// window daemon — and the PC reads whichever answered. If the two shapes ever
    /// disagreed, presses would be silently dropped on one path or run twice on the
    /// other, so the synthesiser and the parser are pinned to each other here.
    #[test]
    fn both_request_channels_parse_identically() {
        // what `content query` actually prints
        let from_provider = "Row: 0 id=17, cmd=close, arg=com.foo.bar";
        // what the daemon's rows are rendered into
        let from_daemon = request_row(17, "close", "com.foo.bar");

        let a = parse_request_row(from_provider).expect("provider row");
        let b = parse_request_row(&from_daemon).expect("daemon row");
        assert_eq!(a, b);
        assert_eq!(a, (Some(17), "close", "com.foo.bar"));
    }

    /// A pre-v2 launcher's rows carry no id and must still run (best-effort), rather than
    /// being skipped for having no watermark.
    #[test]
    fn a_row_without_an_id_still_parses() {
        let got = parse_request_row("Row: 0 cmd=key, arg=back").expect("row");
        assert_eq!(got, (None, "key", "back"));
    }

    /// An argument may hold spaces (a config value does); it runs to end of line.
    #[test]
    fn request_arg_keeps_its_spaces() {
        let row = request_row(9, "cfg", "encoder OMX.qcom.video");
        let got = parse_request_row(&row).expect("row");
        assert_eq!(got, (Some(9), "cfg", "encoder OMX.qcom.video"));
    }
    use super::*;

    /// Exactly the shape the enforcer feeds the parser: `dumpsys activity activities`
    /// already reduced on the device to the four line kinds the grep keeps.
    ///
    /// Display 1 is in here on purpose. It is the trap the old single-display parser
    /// guarded against with a trailing space in its marker, and the reason this one takes
    /// the leading run of digits instead of a prefix match.
    /// Section order matches the device: the virtual display is printed first (it holds
    /// top focus) and display 0 last, with One UI's SplitRoot echo of the WHOLE device
    /// task list at the end of it.
    const DUMP: &str = concat!(
        "Display #18 (activities from top to bottom):\n",
        "  * Task{a3 #90 type=standard A=10.com.bar U=0 visible=true visibleRequested=true mode=freeform}\n",
        "      mBounds=Rect(549, 178 - 1605, 955)\n",
        "        requestedOrientation=SCREEN_ORIENTATION_PORTRAIT\n",
        "        * Hist  #0: ActivityRecord{h3 u0 com.bar/.Main t90}\n",
        "  * Task{a4 #91 type=home A=10.launcher U=0 visible=true mode=fullscreen}\n",
        "Display #1 (activities from top to bottom):\n",
        "  * Task{a2 #61 type=standard A=10.com.decoy U=0 visible=false visibleRequested=false mode=fullscreen}\n",
        "        * Hist  #0: ActivityRecord{h2 u0 com.decoy/.Main t61}\n",
        "Display #0 (activities from top to bottom):\n",
        "  * Task{a1 #57 type=standard A=10.com.foo U=0 visible=true visibleRequested=true mode=fullscreen}\n",
        "      mBounds=Rect(0, 0 - 1080, 2340)\n",
        "        * Hist  #0: ActivityRecord{h1 u0 com.foo/.MainActivity t57}\n",
        // Verbatim in shape from SM-S938B. Task 90 lives on display 18 and must not also
        // count as being on display 0 — reading it as if it did is what made every
        // desktop window look like the phone had claimed it the instant a session began.
        "  * Task{b2 #3 name=SplitRoot type=undefined U=0 visible=false mode=fullscreen}\n",
        "    * Task{b3 #4 type=undefined U=0 rootTaskId=3 visible=false mode=multi-window}\n",
        "      * Task{a3 #90 type=standard A=10.com.bar U=0 visible=true mode=freeform}\n",
        "        * Hist  #0: ActivityRecord{h8 u0 com.bar/.Main t90}\n",
        "      * Task{a1 #57 type=standard A=10.com.foo U=0 visible=true mode=fullscreen}\n",
    );

    /// A widget detour: our trampoline at the root of the task, the platform's
    /// bind dialog on top of it. The enforcer has to read the task as ours, or
    /// it reshapes the dialog and re-launches it from shell uid — which cuts
    /// the result link the dialog needs and cancels the add.
    ///
    /// The `#N` labels count DOWN the printed list: dumpsys walks the task's
    /// bottom-to-top activity list in reverse, so the root is always #0 and
    /// always the LAST line. Which is why root_comp is last-line-wins and never
    /// reads the printed index — inverting that would make is_ours() false for
    /// every real detour task.
    const DETOUR_DUMP: &str = concat!(
        "Display #18 (activities from top to bottom):\n",
        "  * Task{a9 #99 type=standard A=10.launcher.detour U=0 visible=true visibleRequested=true mode=freeform}\n",
        "      mBounds=Rect(700, 260 - 1220, 820)\n",
        "        * Hist  #1: ActivityRecord{h9 u0 com.android.settings/.AllowBindAppWidgetActivity t99}\n",
        "        * Hist  #0: ActivityRecord{h10 u0 com.ccrstech.openandroiddex.launcher/.WidgetDetourActivity t99}\n",
    );

    #[test]
    fn a_detour_task_is_ours_even_while_a_system_dialog_is_on_top() {
        let all = parse_all_display_tasks(DETOUR_DUMP);
        let rec = &all[0].1[0];
        assert_eq!(rec.comp, "com.android.settings/.AllowBindAppWidgetActivity");
        assert!(rec.root_comp.starts_with(adb::LAUNCHER_PACKAGE));
        assert!(rec.is_ours());
    }

    /// The desktop itself: one activity, so top and root are the same line.
    #[test]
    fn the_desktop_task_is_ours_too() {
        let all = parse_all_display_tasks(DUMP);
        let phone = &all[2].1[0];
        assert_eq!(phone.comp, "com.foo/.MainActivity");
        assert_eq!(phone.root_comp, "com.foo/.MainActivity");
        assert!(!phone.is_ours());
    }

    #[test]
    fn splits_every_display_out_of_one_dump() {
        let all = parse_all_display_tasks(DUMP);
        let ids: Vec<i32> = all.iter().map(|(id, _)| *id).collect();
        assert_eq!(ids, vec![18, 1, 0]);
    }

    /// Display 1 must not be swallowed by display 18, in either direction.
    #[test]
    fn does_not_confuse_display_1_with_display_18() {
        let all = parse_all_display_tasks(DUMP);
        let one = &all.iter().find(|(id, _)| *id == 1).expect("display 1").1;
        assert_eq!(one.len(), 1);
        assert_eq!(one[0].package(), "com.decoy");

        let eighteen = &all.iter().find(|(id, _)| *id == 18).expect("display 18").1;
        // type=home is not a standard task and is skipped
        assert_eq!(eighteen.len(), 1);
        assert_eq!(eighteen[0].id, 90);
    }

    #[test]
    fn reads_a_tasks_geometry_and_orientation() {
        let all = parse_all_display_tasks(DUMP);
        let desktop = &all.iter().find(|(id, _)| *id == 18).expect("display 18").1;
        let rec = &desktop[0];
        assert_eq!(rec.comp, "com.bar/.Main");
        assert_eq!(rec.bounds, Some((549, 178, 1605, 955)));
        assert!(rec.freeform && !rec.fullscreen);
        assert!(rec.visible && rec.visible_requested);
        assert!(rec.portrait, "requestedOrientation=…PORTRAIT");
    }

    /// The invisible task on display 1 is what a closed-but-cached window looks like;
    /// the enforcer's own passes lean on both flags being read, not just `visible`.
    #[test]
    fn reads_invisible_tasks_too() {
        let all = parse_all_display_tasks(DUMP);
        let one = &all.iter().find(|(id, _)| *id == 1).expect("display 1").1;
        assert!(!one[0].visible && !one[0].visible_requested);
    }

    /// The regression that made every desktop window look stolen the moment a session
    /// started. Display 0's section ends with an echo of the whole device.
    #[test]
    fn ignores_the_splitroot_echo_of_other_displays_tasks() {
        let all = parse_all_display_tasks(DUMP);
        let zero = &all.iter().find(|(id, _)| *id == 0).expect("display 0").1;
        let ids: Vec<u32> = zero.iter().map(|t| t.id).collect();
        assert_eq!(
            ids,
            vec![57],
            "task 90 lives on display 18; the SplitRoot echo must not put it on display 0"
        );
    }

    /// A desktop the moment it exists: no tasks of its own yet and printed LAST, so its
    /// section runs on into the window hierarchy, which repeats every display's tasks two
    /// levels deeper. Shapes as the enforcer's grep leaves them, from a Galaxy A13
    /// (Android 12: root tasks at indent 4) and the Android 15 emulator (indent 2).
    const EMPTY_DESKTOP_LAST_A12: &str = concat!(
        "Display #0 (activities from top to bottom):\n",
        "  mBounds=Rect(0, 0 - 0, 0)\n",
        "    * Task{6d9da2b #8 type=home A=10105:com.sec.android.app.launcher.activities.LauncherActivity U=0 rootTaskId=1 visible=true mode=fullscreen translucent=false sz=1}\n",
        "      * Hist #0: ActivityRecord{ec3327a u0 com.sec.android.app.launcher/.activities.LauncherActivity t8}\n",
        "  mBounds=Rect(0, 0 - 0, 0)\n",
        "    * Task{baaf261 #901 type=standard A=10742:com.dotnetdreamer.ludowala U=0 visible=false mode=fullscreen translucent=true sz=1}\n",
        "      * Hist #0: ActivityRecord{fc953ba u0 com.dotnetdreamer.ludowala/.MainActivity t901}\n",
        "Display #15 (activities from top to bottom):\n",
        "  ResumedActivity: ActivityRecord{ec3327a u0 com.sec.android.app.launcher/.activities.LauncherActivity t8}\n",
        "  mFocusedApp=ActivityRecord{ec3327a u0 com.sec.android.app.launcher/.activities.LauncherActivity t8}\n",
        "      * Task{acaab19 #1 type=home ?? U=0 visible=true mode=fullscreen translucent=false sz=1}\n",
        "        * Task{6d9da2b #8 type=home A=10105:com.sec.android.app.launcher.activities.LauncherActivity U=0 rootTaskId=1 visible=true mode=fullscreen translucent=false sz=1}\n",
        "          * ActivityRecord{ec3327a u0 com.sec.android.app.launcher/.activities.LauncherActivity t8}\n",
        "      * Task{baaf261 #901 type=standard A=10742:com.dotnetdreamer.ludowala U=0 visible=false mode=fullscreen translucent=true sz=1}\n",
        "        * ActivityRecord{fc953ba u0 com.dotnetdreamer.ludowala/.MainActivity t901}\n",
    );
    const EMPTY_DESKTOP_LAST_A15: &str = concat!(
        "Display #0 (activities from top to bottom):\n",
        "  * Task{df43d14 #1 type=home U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}\n",
        "    * Task{91928b #148 type=home I=com.google.android.apps.nexuslauncher/.NexusLauncherActivity U=0 rootTaskId=1 visible=false visibleRequested=false mode=fullscreen translucent=false sz=1}\n",
        "      * Hist  #1: ActivityRecord{7df105a u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t148}\n",
        "  * Task{e769d3e #151 type=standard A=10209:com.dotnetdreamer.lightsnip U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}\n",
        "    * Hist  #0: ActivityRecord{96999f9 u0 com.dotnetdreamer.lightsnip/.MainActivity t151}\n",
        "Display #2 (activities from top to bottom):\n",
        "  ResumedActivity: ActivityRecord{96999f9 u0 com.dotnetdreamer.lightsnip/.MainActivity t151}\n",
        "  mFocusedApp=ActivityRecord{96999f9 u0 com.dotnetdreamer.lightsnip/.MainActivity t151}\n",
        "      * Task{df43d14 #1 type=home U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}\n",
        "        * Task{91928b #148 type=home I=com.google.android.apps.nexuslauncher/.NexusLauncherActivity U=0 rootTaskId=1 visible=false visibleRequested=false mode=fullscreen translucent=false sz=1}\n",
        "          * ActivityRecord{7df105a u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t148}\n",
        "      * Task{e769d3e #151 type=standard A=10209:com.dotnetdreamer.lightsnip U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}\n",
        "        * ActivityRecord{96999f9 u0 com.dotnetdreamer.lightsnip/.MainActivity t151}\n",
    );

    /// The phone's open app must stay the phone's. Read as the desktop's, the enforcer
    /// re-launched it onto the desktop the instant a session began.
    #[test]
    fn an_empty_desktop_printed_last_owns_no_tasks() {
        for (dump, desktop, phone_task) in [
            (EMPTY_DESKTOP_LAST_A12, 15, 901),
            (EMPTY_DESKTOP_LAST_A15, 2, 151),
        ] {
            let all = parse_all_display_tasks(dump);
            let on = |id: i32| -> Vec<u32> {
                all.iter()
                    .find(|(d, _)| *d == id)
                    .map(|(_, ts)| ts.iter().map(|t| t.id).collect())
                    .unwrap_or_default()
            };
            assert!(on(desktop).is_empty(), "display {desktop} took {:?}", on(desktop));
            assert_eq!(on(0), vec![phone_task]);
        }
    }

    /// A root task with a child task is one entry, not two.
    #[test]
    fn counts_only_root_tasks() {
        let dump = concat!(
            "Display #0 (activities from top to bottom):\n",
            "  * Task{r1 #1551 type=standard A=10.com.sec U=0 visible=true mode=fullscreen}\n",
            "    * Task{r2 #1552 type=standard A=10.com.sec U=0 rootTaskId=1551 visible=true mode=fullscreen}\n",
            "        * Hist  #0: ActivityRecord{h1 u0 com.sec/.Launcher t1552}\n",
        );
        let all = parse_all_display_tasks(dump);
        assert_eq!(all[0].1.len(), 1);
        assert_eq!(all[0].1[0].id, 1551);
    }

    #[test]
    fn survives_a_dump_with_no_displays() {
        assert!(parse_all_display_tasks("").is_empty());
        assert!(parse_all_display_tasks("nothing useful here\n").is_empty());
    }

    /// The two real answers, captured from the phones themselves: a Redmi
    /// Note 7 on MIUI 12.5, which blocks injected input, and an S25 Ultra,
    /// which does not. The refusal reaches us through run_adb's Err path —
    /// `input` exits non-zero — which is why preflight_input reads both.
    const REFUSED: &str = concat!(
        "10
29
",
        "java.lang.SecurityException: Injecting to another application requires ",
        "INJECT_EVENTS permission
",
        "	at android.os.Parcel.createException(Parcel.java:2074)
",
    );
    const ALLOWED: &str = "16
36";

    #[test]
    fn probe_spots_a_phone_that_refuses_input() {
        let p = read_probe(REFUSED);
        assert!(p.refused);
        assert_eq!(p.release, "10");
        assert_eq!(p.sdk, Some(29));
    }

    #[test]
    fn probe_lets_a_healthy_phone_through() {
        let p = read_probe(ALLOWED);
        assert!(!p.refused);
        assert_eq!(p.release, "16");
        assert_eq!(p.sdk, Some(36));
    }

    #[test]
    fn probe_reads_silence_as_neither() {
        // adb's own giving-up text. No version and no refusal in it, so every
        // test in preflight_input falls through and the session starts: a
        // phone that did not answer is usually one still settling.
        let p = read_probe("adb stopped responding after 12s: adb -s X shell getprop");
        assert!(!p.refused);
        assert_eq!(p.sdk, None);
    }

    /// Verbatim from the Redmi Note 7 (Android 10) that started all this: the
    /// display exists, the launcher renders into it, and no window on it can
    /// ever take focus. FLAG_PRESENTATION is present, FLAG_TRUSTED is not.
    const UNTRUSTED: &str = concat!(
        r#"    mBaseDisplayInfo=DisplayInfo{"scrcpy, displayId 11", uniqueId "#,
        r#""virtual:com.android.shell,2000,scrcpy,0", app 1920 x 1080, real 1920 x 1080, "#,
        "type VIRTUAL, state ON, owner com.android.shell (uid 2000), FLAG_PRESENTATION, ",
        "removeMode 1}",
    );

    #[test]
    fn an_untrusted_display_is_spotted() {
        assert!(!display_is_trusted(UNTRUSTED));
    }

    #[test]
    fn a_trusted_display_is_left_alone() {
        // CONSTRUCTED, not captured: the phone that produces the real thing
        // was off Wi-Fi when this was written. The flag's spelling is
        // DisplayInfo.flagsToString's own, which is what dumpsys prints.
        let trusted = UNTRUSTED.replace("FLAG_PRESENTATION", "FLAG_PRESENTATION, FLAG_TRUSTED");
        assert!(display_is_trusted(&trusted));
    }

    /// `dumpsys display | grep -F 'DisplayInfo{'`, captured: a Galaxy A13 on
    /// Android 12 (untrusted, stray quote after the id) and the Android 15
    /// emulator (trusted, a comma). The built-in screen is in both and IS
    /// trusted, so answering with the wrong line reads as the wrong verdict.
    const DISPLAYS_A12: &str = concat!(
        r#"    mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0", displayGroupId 0, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_TRUSTED, real 1080 x 2408, type INTERNAL}"#,
        "\n",
        r#"    mBaseDisplayInfo=DisplayInfo{"scrcpy", displayId 15", displayGroupId 0, FLAG_PRESENTATION, real 1920 x 1080, type VIRTUAL, owner com.android.shell (uid 2000), removeMode 1}"#,
        "\n",
    );
    const DISPLAYS_A15: &str = concat!(
        r#"    mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0, displayGroupId 0, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_TRUSTED, real 1220 x 2712, type INTERNAL}"#,
        "\n",
        r#"    mBaseDisplayInfo=DisplayInfo{"scrcpy", displayId 2, displayGroupId 1, FLAG_PRESENTATION, FLAG_TRUSTED, FLAG_OWN_DISPLAY_GROUP, FLAG_ALWAYS_UNLOCKED, FLAG_TOUCH_FEEDBACK_DISABLED, real 1920 x 1080,"#,
        "\n",
    );

    /// The Android 13+ spelling went unread until 2026-10: every such display
    /// came back "not in dumpsys", trusted or not.
    #[test]
    fn finds_the_display_in_every_releases_format() {
        let a10 = display_info_line(UNTRUSTED, 11).expect("Android 10");
        assert!(!display_is_trusted(a10));
        let a12 = display_info_line(DISPLAYS_A12, 15).expect("Android 12");
        assert!(a12.contains("scrcpy") && !display_is_trusted(a12));
        let a15 = display_info_line(DISPLAYS_A15, 2).expect("Android 15");
        assert!(a15.contains("scrcpy") && display_is_trusted(a15));
    }

    #[test]
    fn display_1_does_not_answer_for_display_11() {
        assert_eq!(display_info_line(UNTRUSTED, 1), None);
        let a15 = DISPLAYS_A15.replace("displayId 2,", "displayId 12,");
        assert_eq!(display_info_line(&a15, 1), None);
        assert_eq!(display_info_line(&a15, 2), None);
        assert!(display_info_line(&a15, 12).is_some());
    }

    /// scrcpy's announcement as the output reader receives it (scrcpy 5.0.1,
    /// NewDisplayCapture), for a display asked for at 1920x1080 on an encoder
    /// that aligns to 16.
    const NEW_DISPLAY: &str = "[server] INFO: New display: 1920x1072/160 (id=214)";

    #[test]
    fn reads_the_new_display_announcement() {
        assert_eq!(parse_display_id(NEW_DISPLAY), Some(214));
        assert_eq!(parse_display_density(NEW_DISPLAY), Some(160));
        // the size the display was made at, not the 1080 that was asked for
        assert_eq!(parse_display_size_announced(NEW_DISPLAY), Some((1920, 1072)));
    }

    #[test]
    fn other_lines_announce_no_display() {
        let line = "INFO: Renderer: direct3d11";
        assert_eq!(parse_display_id(line), None);
        assert_eq!(parse_display_size_announced(line), None);
    }

    #[test]
    fn reads_the_patched_servers_resize_lines() {
        let line = "[server] INFO: Display resized: 2832x1706/320 (id=63)";
        assert_eq!(parse_display_resized(line), Some((2832, 1706, 320)));
        assert_eq!(parse_display_resized("[server] INFO: Display resized: 2464x1220/160 (id=7)"), Some((2464, 1220, 160)));
        assert_eq!(parse_display_resized("[server] INFO: New display: 2832x1706/320 (id=63)"), None);
        assert_eq!(parse_display_resized("[server] INFO: Display resized: wide/tall"), None);
    }

    #[test]
    fn reads_a_displays_own_size_from_wm() {
        assert_eq!(physical_size("Physical size: 2560x1280"), Some((2560, 1280)));
        assert_eq!(physical_size("Physical size: 2832x1706\nOverride size: 1920x1080"), Some((2832, 1706)));
        // what a display that is gone answers
        assert_eq!(physical_size("Physical size: 0x0"), None);
        assert_eq!(physical_size(""), None);
    }

    /// The frontend sends camelCase, and a misspelt key would drop the option
    /// silently rather than fail — which is what this pins.
    #[test]
    fn a_desktop_session_keeps_its_display_awake() {
        let opts: MirrorOptions = serde_json::from_value(serde_json::json!({
            "serial": "X",
            "newDisplay": "1920x1080/160",
            "keepActive": true,
        }))
        .expect("desktop options");
        let args = build_args(&opts);
        assert!(args.iter().any(|a| a == "--keep-active"));
        assert!(args.iter().any(|a| a == "--background-color=000000"));
    }

    /// The companion runs with --no-control, and scrcpy refuses to start with
    /// that and --keep-active together ("Cannot keep device active if control
    /// is disabled") — sound would silently stop following the desktop.
    #[test]
    fn the_audio_companion_carries_no_window_options() {
        let args = build_args(&audio_companion_options("X", true));
        assert!(!args
            .iter()
            .any(|a| a == "--keep-active" || a.starts_with("--background-color")));
    }

    /// The desktop and its audio companion are spawned back to back; on
    /// Windows, starting both on the same port is what cross-wired their
    /// servers' connections (see `NEXT_PORT`).
    #[cfg(windows)]
    #[test]
    fn back_to_back_spawns_listen_on_different_ports() {
        let a = listen_port_range();
        let b = listen_port_range();
        assert_ne!(a, b);
        for range in [&a, &b] {
            let (first, last) = range.split_once(':').expect("first:last");
            assert!((27183..=27199).contains(&first.parse::<u16>().expect("port")));
            assert_eq!(last, "27199");
        }
    }
}
