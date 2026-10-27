//! Wireless projection — everything between "a phone on the same Wi-Fi" and
//! "a serial adb will talk to", without the user ever reading an IP address off
//! a Settings screen.
//!
//! Three ways in, in the order we want people to find them:
//!
//! 1. **The cable, once.** [`wireless_go_wireless`] flips a USB-attached phone
//!    into TCP mode, works out its address itself and connects. One click, no
//!    typing, and — the part that matters — it works on *every* Android
//!    version, because `adb tcpip` reads `service.adb.tcp.port` and has nothing
//!    to do with the Android 11 "Wireless debugging" toggle.
//! 2. **A QR code.** [`wireless_qr_begin`] / [`wireless_qr_poll`] put a code on
//!    screen for the phone's own "Pair device with QR code" scanner. Nothing is
//!    typed on either end.
//! 3. **A six-digit code.** [`wireless_pair`] with the address discovered for
//!    the user, so all they copy across is the number the phone is showing.
//!
//! After any of them the phone is remembered, and [`wireless_reconnect_known`]
//! gets it back on the next run before the user is asked anything at all.
//!
//! Two adb behaviours shape most of the code here, and both are traps:
//!
//! * `adb connect` reports failure on **stdout with exit code 0**.
//! * `adb pair` fails in *two* different shapes — exit 0 with `Failed: …` on
//!   stdout on older adb, and exit 1 with `error: protocol fault …` on newer,
//!   where the real message is lost server-side. Only a `Successfully paired
//!   to` prefix can be believed.

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::io::{Read, Write};
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::{Condvar, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};
use tauri::Manager;

use crate::adb::{adb_command, run_adb, run_adb_quiet, run_adb_timeout};

/// The port `adb tcpip` is pointed at. Android's own wireless-debugging mode
/// picks a random one instead, which is why the pairing paths below never
/// assume a port and always resolve one.
const TCPIP_PORT: u16 = 5555;

/// `adb tcpip` sets the property and returns; adbd restarts a moment later.
/// Polling for the property to actually take is what scrcpy does, and it is
/// the difference between "connect refused" and a connection — a fixed sleep
/// is either too short on a slow phone or wasted time on a fast one.
const TCPIP_ATTEMPTS: u32 = 40;
const TCPIP_POLL: Duration = Duration::from_millis(250);

/// A `PairingClient` that has connected but never completes leaves `adb pair`
/// waiting on a condition variable with no timeout of its own. Ours is the
/// only thing that will end it.
const PAIR_TIMEOUT: Duration = Duration::from_secs(25);

/// Reconnecting to a remembered phone that is switched off means waiting for a
/// TCP connect to a silent address. Short, because this runs during boot and
/// several of them run at once.
const RECONNECT_TIMEOUT: Duration = Duration::from_secs(6);

// ── Wire types ──────────────────────────────────────────────────────────

/// One row of `adb mdns services`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MdnsService {
    /// The instance name — `adb-<serial>-<rand>` for a phone's own records,
    /// or whatever we asked for in the QR payload.
    pub instance: String,
    /// `_adb-tls-pairing._tcp.` / `_adb-tls-connect._tcp.` / `_adb._tcp.`
    pub service: String,
    pub address: String,
    pub port: u16,
}

impl MdnsService {
    fn endpoint(&self) -> String {
        format!("{}:{}", self.address, self.port)
    }
    fn is_pairing(&self) -> bool {
        self.service.contains("adb-tls-pairing")
    }
    fn is_connect(&self) -> bool {
        self.service.contains("adb-tls-connect") || self.service.contains("_adb._tcp")
    }
}

/// A phone we have connected to wirelessly before.
///
/// `guid` is the important field. An address goes stale the moment DHCP hands
/// out a different lease, and Android's wireless-debugging port is re-rolled
/// every time the toggle is cycled — but the guid is stable, and it is the
/// mDNS instance name the phone advertises itself under. Reconnecting by guid
/// therefore survives both.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct KnownDevice {
    /// Human name for the UI ("Samsung SM-S911B").
    pub label: String,
    /// Last address that worked, as `ip:port`.
    pub address: String,
    /// `persist.adb.wifi.guid`, when the phone was reached by pairing.
    #[serde(default)]
    pub guid: Option<String>,
    /// `ro.serialno` — ties a saved entry back to the phone on the cable.
    #[serde(default)]
    pub hardware_serial: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WirelessResult {
    /// The serial to hand `adb -s` / scrcpy from here on.
    pub serial: String,
    pub address: String,
    pub label: String,
}

/// Whether the pairing paths are worth offering at all. adb's mDNS is what
/// finds the phone's randomly-chosen pairing port, and when it is unavailable
/// the QR and code flows cannot work — better to say so up front than to let
/// someone scan a code that nothing will ever answer.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WirelessSupport {
    pub mdns: bool,
    pub detail: String,
}

// ── adb plumbing that adb.rs does not cover ─────────────────────────────

fn drain<R: Read + Send + 'static>(pipe: Option<R>) -> thread::JoinHandle<String> {
    thread::spawn(move || {
        let mut buf = Vec::new();
        if let Some(mut pipe) = pipe {
            let _ = pipe.read_to_end(&mut buf);
        }
        String::from_utf8_lossy(&buf).into_owned()
    })
}

/// Run adb with something on stdin, and hand back **both** streams whatever
/// the exit code was.
///
/// [`crate::adb::run_adb`] collapses a failure into `Err(stderr)`, which is
/// right everywhere else and wrong for `adb pair`: the two adb generations put
/// the useful half of the answer on different streams with different exit
/// codes, so the caller has to see all of it.
fn run_adb_stdin(
    app: &tauri::AppHandle,
    args: &[&str],
    stdin_text: &str,
    timeout: Duration,
) -> Result<(bool, String, String), String> {
    let mut cmd = adb_command(app);
    cmd.args(args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let label = format!("adb {}", args.join(" "));
    log::debug!("→ {label} (secret on stdin)");
    let started = Instant::now();
    let mut child = cmd
        .spawn()
        .map_err(|e| format!("failed to run adb: {e}"))?;

    // The secret goes down the pipe rather than into argv: on Windows a
    // command line is readable by any process that can see the process list,
    // and this one is a live credential for the phone.
    if let Some(mut stdin) = child.stdin.take() {
        let _ = stdin.write_all(stdin_text.as_bytes());
        // dropped here — adb reads a line and blocks until the pipe closes
    }

    let out_reader = drain(child.stdout.take());
    let err_reader = drain(child.stderr.take());

    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Some(status),
            Ok(None) => {}
            Err(e) => {
                log::error!("adb wait failed ({label}): {e}");
                break None;
            }
        }
        if started.elapsed() >= timeout {
            let _ = child.kill();
            let _ = child.wait();
            break None;
        }
        thread::sleep(Duration::from_millis(20));
    };

    let stdout = out_reader.join().unwrap_or_default().trim().to_string();
    let stderr = err_reader.join().unwrap_or_default().trim().to_string();
    log::debug!(
        "← {}ms {label} → out={stdout:?} err={stderr:?}",
        started.elapsed().as_millis()
    );

    let Some(status) = status else {
        return Err(format!(
            "adb stopped responding after {}s: {label}",
            timeout.as_secs()
        ));
    };
    Ok((status.success(), stdout, stderr))
}

/// `adb connect`, with the exit code ignored on purpose.
///
/// adb prints `cannot connect to …` on stdout and still exits 0, so the text
/// is the only signal there is. `connected to` and `already connected to` are
/// the two shapes of success.
fn connect(app: &tauri::AppHandle, address: &str, timeout: Duration) -> Result<String, String> {
    let out = run_adb_timeout(app, &["connect", address], timeout).unwrap_or_else(|e| e);
    let lower = out.to_lowercase();
    if lower.contains("connected to") && !lower.contains("cannot") {
        Ok(out)
    } else if out.is_empty() {
        Err(format!("no answer from {address}"))
    } else {
        Err(out)
    }
}

// ── mDNS discovery ──────────────────────────────────────────────────────

/// Parse `adb mdns services`.
///
/// adb prints a header line and then `instance\tservice\tip:port` per row.
///
/// Tabs are tried before whitespace on purpose: when two phones advertise the
/// same instance name, mDNS renames the second to `adb-… (2)`, and a space
/// inside the first column turns a whitespace split into garbage. Some builds
/// space-pad the columns instead of tabbing them, which is why the whitespace
/// split is still there as a fallback.
///
/// The address is taken apart from the right — an IPv4 literal has no colons
/// of its own, and splitting from the right survives a build that starts
/// printing IPv6.
fn parse_mdns(raw: &str) -> Vec<MdnsService> {
    let mut out = Vec::new();
    for line in raw.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with("List of") {
            continue;
        }
        let tabbed: Vec<&str> = line.split('\t').filter(|c| !c.trim().is_empty()).collect();
        let cols: Vec<&str> = if tabbed.len() >= 3 {
            tabbed.iter().map(|c| c.trim()).collect()
        } else {
            line.split_whitespace().collect()
        };
        let (Some(instance), Some(service), Some(endpoint)) =
            (cols.first(), cols.get(1), cols.get(2))
        else {
            continue;
        };
        let (instance, service, endpoint) = (*instance, *service, *endpoint);
        if !service.starts_with('_') {
            continue;
        }
        let Some((address, port)) = endpoint.rsplit_once(':') else {
            continue;
        };
        let Ok(port) = port.parse::<u16>() else {
            continue;
        };
        out.push(MdnsService {
            instance: instance.to_string(),
            service: service.to_string(),
            address: address.to_string(),
            port,
        });
    }
    out
}

/// What the last browse saw, so the log records phones appearing and going and
/// nothing in between. Mirrors the device poll's `LAST_SEEN` in `adb.rs`, and
/// for the same reason: `wireless_discover` runs every 2s for as long as a
/// pairing tab is open.
static LAST_MDNS: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

fn mdns_services(app: &tauri::AppHandle) -> Vec<MdnsService> {
    match run_adb_quiet(app, &["mdns", "services"]) {
        Ok(raw) => {
            let found = parse_mdns(&raw);
            // Worth a line even when it is empty, because empty is the whole
            // problem in every wireless bug report: "no phones waiting" looks
            // the same whether the phone is not advertising, the network eats
            // multicast, or macOS has denied this app the Local Network
            // permission. A log that says the browse ran and saw nothing at
            // least rules out the browse never running.
            let summary = if found.is_empty() {
                "none".to_string()
            } else {
                found
                    .iter()
                    .map(|s| format!("{} [{}] {}", s.instance, s.service, s.endpoint()))
                    .collect::<Vec<_>>()
                    .join(", ")
            };
            let mut last = LAST_MDNS.lock().unwrap();
            if last.as_deref() != Some(summary.as_str()) {
                log::info!("mdns: {summary}");
                *last = Some(summary);
            }
            found
        }
        Err(e) => {
            log::warn!("mdns: adb could not list services — {e}");
            Vec::new()
        }
    }
}

/// Phones advertising a pairing server right now, i.e. sitting on the "Pair
/// device with pairing code / QR code" screen.
#[tauri::command(async)]
pub fn wireless_discover(app: tauri::AppHandle) -> Result<Vec<MdnsService>, String> {
    Ok(mdns_services(&app)
        .into_iter()
        .filter(|s| s.is_pairing())
        .collect())
}

/// Is adb's mDNS daemon actually up?
///
/// `adb mdns check` exits 0 whatever the answer, so the string is the answer.
#[tauri::command(async)]
pub fn wireless_support(app: tauri::AppHandle) -> Result<WirelessSupport, String> {
    let out = run_adb_quiet(&app, &["mdns", "check"]).unwrap_or_else(|e| e);
    let lower = out.to_lowercase();
    let ok = lower.contains("mdns daemon version");
    let detail = if ok {
        out.clone()
    } else if lower.contains("discovery disabled") {
        "adb's mDNS discovery is switched off (ADB_MDNS=0)".to_string()
    } else if lower.contains("unknown") && lower.contains("command") {
        "this adb is too old for wireless pairing".to_string()
    } else if out.is_empty() {
        "adb did not answer".to_string()
    } else {
        out.clone()
    };
    if !ok {
        log::warn!("wireless: mDNS unavailable — {detail}");
    }
    Ok(WirelessSupport { mdns: ok, detail })
}

// ── The cable, once ─────────────────────────────────────────────────────

/// Where a phone that has just been put into TCP mode is listening, according
/// to the phone itself.
///
/// Once `service.adb.tcp.port` is set, adbd advertises `_adb._tcp` under the
/// instance name `adb-<ro.serialno>` — carrying both the address and the port.
/// That is strictly better than reading an address out of a shell command:
/// nothing is parsed, the port is authoritative rather than assumed, and a
/// phone on an unusual interface still answers correctly. It only works where
/// multicast does, which is why [`device_ip`] is still here behind it.
fn mdns_endpoint(app: &tauri::AppHandle, hardware_serial: &str) -> Option<String> {
    if hardware_serial.is_empty() {
        return None;
    }
    let want = format!("adb-{hardware_serial}");
    let found = mdns_services(app).into_iter().find(|s| {
        s.service.contains("_adb._tcp") && (s.instance == want || s.instance.starts_with(&want))
    })?;
    log::info!(
        "wireless: {hardware_serial} advertises itself at {}",
        found.endpoint()
    );
    Some(found.endpoint())
}

/// The phone's own Wi-Fi address, read off the phone.
///
/// `ip route` is parsed by tokens rather than by column offset. scrcpy reads
/// fixed columns, which breaks the moment a route carries an extra attribute
/// (`metric`, `table`, `mtu`) — and Samsung's routes routinely do. Looking for
/// the words `dev` and `src` instead cannot be knocked out of alignment.
fn device_ip(app: &tauri::AppHandle, serial: &str) -> Option<String> {
    let routes = run_adb_quiet(app, &["-s", serial, "shell", "ip route"]).unwrap_or_default();
    if let Some(ip) = parse_ip_route(&routes) {
        log::info!("wireless: {serial} is at {ip}");
        return Some(ip);
    }

    // Fallback: ask the interface directly. Reached when the routing table is
    // shaped unusually, or when `ip route` printed nothing at all.
    let addrs = run_adb_quiet(app, &["-s", serial, "shell", "ip -f inet addr show wlan0"])
        .unwrap_or_default();
    if let Some(ip) = parse_inet_addr(&addrs) {
        log::info!("wireless: {serial} is at {ip} (wlan0)");
        return Some(ip);
    }
    log::warn!("wireless: could not work out the Wi-Fi address of {serial}");
    None
}

fn is_ipv4(text: &str) -> bool {
    let mut parts = 0;
    for part in text.split('.') {
        if part.parse::<u8>().is_err() {
            return false;
        }
        parts += 1;
    }
    parts == 4
}

/// Pull the Wi-Fi address out of `ip route`.
///
/// The interface filter is the whole job, and a real Galaxy shows why: with
/// mobile data on, the *first* route is the cellular one —
/// `192.0.0.0/27 dev rmnet_data0 proto kernel scope link src 192.0.0.2` —
/// and that address is not reachable from this PC. Taking the first `src`, or
/// reading a fixed column, hands back an address that can never connect.
///
/// `starts_with("wlan")` rather than a substring test, because Samsung's
/// mobile-hotspot interface is `swlan0` and is not the one we want either.
fn parse_ip_route(routes: &str) -> Option<String> {
    routes.lines().find_map(|line| {
        let tokens: Vec<&str> = line.split_whitespace().collect();
        let after = |key: &str| {
            tokens
                .iter()
                .position(|t| *t == key)
                .and_then(|i| tokens.get(i + 1))
                .copied()
        };
        match (after("dev"), after("src")) {
            (Some(dev), Some(src)) if dev.starts_with("wlan") && is_ipv4(src) => {
                Some(src.to_string())
            }
            _ => None,
        }
    })
}

/// Pull the address out of `ip -f inet addr show wlan0`.
fn parse_inet_addr(addrs: &str) -> Option<String> {
    addrs.lines().find_map(|line| {
        let tokens: Vec<&str> = line.split_whitespace().collect();
        let cidr = tokens
            .iter()
            .position(|t| *t == "inet")
            .and_then(|i| tokens.get(i + 1))?;
        let ip = cidr.split('/').next()?;
        is_ipv4(ip).then(|| ip.to_string())
    })
}

fn getprop(app: &tauri::AppHandle, serial: &str, prop: &str) -> String {
    run_adb_quiet(app, &["-s", serial, "shell", "getprop", prop])
        .unwrap_or_default()
        .trim()
        .to_string()
}

/// What a saved phone is called in the list: its own Device name when it has
/// one ("Galaxy S25 Ultra" — what the device list shows too), else its brand
/// and model, else the serial.
fn device_label(app: &tauri::AppHandle, serial: &str) -> String {
    if let Some(name) = run_adb_quiet(app, &["-s", serial, "shell", "settings get global device_name"])
        .ok()
        .and_then(|out| crate::adb::clean_device_name(&out))
    {
        return name;
    }
    let out = run_adb_quiet(
        app,
        &[
            "-s",
            serial,
            "shell",
            "getprop ro.product.brand; getprop ro.product.model",
        ],
    )
    .unwrap_or_default();
    let parts: Vec<String> = out
        .lines()
        .map(|l| l.trim().to_string())
        .filter(|l| !l.is_empty())
        .collect();
    if parts.is_empty() {
        serial.to_string()
    } else {
        parts.join(" ")
    }
}

/// Take a phone that is on the cable and leave it reachable over Wi-Fi.
///
/// This is the path worth steering people to: it is the only one that needs no
/// pairing screen, no code and no mDNS, so it works on every Android version
/// and on networks that block multicast. `adb tcpip` drives
/// `service.adb.tcp.port`, which is a different mechanism from the Android 11
/// "Wireless debugging" toggle (`persist.adb.tls_server.enable`) — having the
/// toggle off does not stop this.
///
/// The one thing it cannot do is survive a reboot: `service.adb.*` is not a
/// persistent property, so the phone comes back USB-only and this has to be
/// run again. Saying so is [`wireless_reconnect_known`]'s job.
#[tauri::command(async)]
pub fn wireless_go_wireless(app: tauri::AppHandle, serial: String) -> Result<WirelessResult, String> {
    log::info!("── switching {serial} to Wi-Fi ──");
    if serial.contains(':') || serial.contains("adb-tls-connect") {
        return Err("that device is already connected over Wi-Fi".into());
    }

    // Already in TCP mode? Re-running `adb tcpip` would restart adbd for no
    // reason and drop every transport that is currently up, this app's own
    // included.
    let current = getprop(&app, &serial, "service.adb.tcp.port");
    let port: u16 = current.parse().unwrap_or(0);
    let port = if port > 0 {
        log::info!("wireless: {serial} is already listening on {port}");
        port
    } else {
        run_adb(&app, &["-s", &serial, "tcpip", &TCPIP_PORT.to_string()])?;
        // Wait for the property to actually take rather than sleeping a fixed
        // amount: adb returns as soon as it has *asked*, and connecting before
        // adbd has restarted is refused.
        let mut live = 0;
        for _ in 0..TCPIP_ATTEMPTS {
            thread::sleep(TCPIP_POLL);
            if getprop(&app, &serial, "service.adb.tcp.port").trim() == TCPIP_PORT.to_string() {
                live = TCPIP_PORT;
                break;
            }
        }
        if live == 0 {
            return Err(format!(
                "the phone did not switch to Wi-Fi mode within {}s. Some devices \
                 need Developer options → Wireless debugging turned on first.",
                (TCPIP_ATTEMPTS as u64 * TCPIP_POLL.as_millis() as u64) / 1000
            ));
        }
        live
    };

    // Ask the network first, the phone second. The mDNS record carries the
    // port adbd actually bound, so it stays right even if `adb tcpip` landed
    // on something other than what we asked for.
    let hardware = getprop(&app, &serial, "ro.serialno");
    let address = match mdns_endpoint(&app, &hardware) {
        Some(endpoint) => endpoint,
        None => {
            let ip = device_ip(&app, &serial).ok_or(
                "the phone is not on Wi-Fi, or its address could not be read. Connect it to \
                 the same Wi-Fi network as this PC and try again.",
            )?;
            format!("{ip}:{port}")
        }
    };

    // adbd has restarted; the listener can be a beat behind the property.
    let mut last = String::new();
    for attempt in 1..=8 {
        match connect(&app, &address, Duration::from_secs(6)) {
            Ok(msg) => {
                log::info!("wireless: {msg}");
                let label = device_label(&app, &address);
                remember(
                    &app,
                    KnownDevice {
                        label: label.clone(),
                        address: address.clone(),
                        guid: None,
                        hardware_serial: Some(hardware.clone()).filter(|s| !s.is_empty()),
                    },
                );
                return Ok(WirelessResult {
                    serial: address.clone(),
                    address,
                    label,
                });
            }
            Err(e) => {
                last = e;
                log::debug!("wireless: connect attempt {attempt} to {address} — {last}");
                thread::sleep(Duration::from_millis(600));
            }
        }
    }
    Err(format!("could not reach the phone at {address}: {last}"))
}

// ── Wi-Fi beside the cable, for the phone's app windows ─────────────────

/// What came of making a phone on the cable reachable over Wi-Fi as well
/// ([`ready_wifi`]).
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
pub enum WifiReady {
    /// Attached over Wi-Fi as well now, as this serial.
    Yes(String),
    /// Wireless debugging was off and has just been switched on, and the
    /// phone asks its owner first whether to allow it on this network.
    Asking,
    /// Wireless debugging is off, and the phone does not let this computer
    /// switch it on — a Xiaomi phone's shell may not write the setting unless
    /// "USB debugging (Security settings)" is on. Its owner can, on the phone.
    Refused,
    /// Wireless debugging is off: its owner has not allowed it on this
    /// network (yet), or turned it off after this app switched it on. It is
    /// switched on once a run, never again behind their back.
    Off,
    /// Not now, and why: the phone is not on Wi-Fi, or did not answer there.
    No(String),
    /// Never: Wireless debugging came with Android 11.
    Never,
}

/// How long the phone takes to say whether it asks its owner first, and then
/// to listen.
const WIFI_ON_WAIT: Duration = Duration::from_secs(6);

/// A Galaxy A13's first connection over Wi-Fi took 7.6 s; later ones 0.5–1.4 s.
const WIFI_CONNECT_TIMEOUT: Duration = Duration::from_secs(12);

/// Switching Wireless debugging on or off is one `settings` call.
const SWITCH_TIMEOUT: Duration = Duration::from_secs(4);

/// How long the exit waits for tries under way ([`let_wifi_go`]): each looks
/// every half second whether the app is exiting, but an `adb connect` is not
/// cut short.
const TRIES_FINISH: Duration = Duration::from_secs(4);

/// What a try says once the app is exiting.
const EXITING: &str = "Open Android DeX is closing";

/// What this run did to make phones reachable over Wi-Fi beside their cables
/// ([`ready_wifi`]), for the exit to undo ([`let_wifi_go`]).
#[derive(Default)]
struct Beside {
    /// Each phone's link made: its `ro.serialno`, and the serial adb calls it
    /// by there. For this run only, never remembered: a remembered phone is
    /// taken over Wi-Fi at the next start, its cable in or not.
    made: HashMap<String, String>,
    /// The phones whose Wireless debugging this run switched on. One that had
    /// it on already keeps it.
    switched: HashSet<String>,
    /// The phones that would not let this run switch it on ([`WifiReady::Refused`]).
    refused: HashSet<String>,
    /// Tries under way ([`Trying`]): the exit waits for them.
    running: usize,
    /// The app is exiting: no try starts, and none records what it did — one
    /// under way undoes it itself.
    exiting: bool,
}

/// The app is exiting: what a try did is not kept for the exit to undo.
#[derive(Debug, PartialEq, Eq)]
struct Exiting;

impl Beside {
    /// `phone` about to be switched on — said before it is done, so that an
    /// exit meanwhile switches it off again.
    fn switching_on(&mut self, phone: &str) -> Result<(), Exiting> {
        if self.exiting {
            return Err(Exiting);
        }
        self.switched.insert(phone.to_string());
        Ok(())
    }

    /// `way`, a link made to `phone`, kept for the exit to let go: the link it
    /// replaces, if one was kept.
    fn keep_link(&mut self, phone: &str, way: &str) -> Result<Option<String>, Exiting> {
        if self.exiting {
            return Err(Exiting);
        }
        Ok(self.made.insert(phone.to_string(), way.to_string()))
    }
}

fn beside() -> &'static (Mutex<Beside>, Condvar) {
    static B: OnceLock<(Mutex<Beside>, Condvar)> = OnceLock::new();
    B.get_or_init(Default::default)
}

/// A try at a Wi-Fi link beside a cable, counted while it runs: the exit waits
/// for it. None once the app is exiting.
struct Trying(&'static (Mutex<Beside>, Condvar));

impl Trying {
    fn start(on: &'static (Mutex<Beside>, Condvar)) -> Option<Trying> {
        let mut b = on.0.lock().unwrap();
        if b.exiting {
            return None;
        }
        b.running += 1;
        Some(Trying(on))
    }
}

impl Drop for Trying {
    fn drop(&mut self) {
        let (b, done) = self.0;
        b.lock().unwrap().running -= 1;
        done.notify_all();
    }
}

/// No try starts from here on, and those under way are waited for, up to
/// `most`: one could switch Wireless debugging on, or make a link, just after
/// the exit undid them. What the run did, to undo.
fn wind_up(on: &(Mutex<Beside>, Condvar), most: Duration) -> (HashSet<String>, HashMap<String, String>) {
    let (b, done) = on;
    let mut b = b.lock().unwrap();
    b.exiting = true;
    let until = Instant::now() + most;
    while b.running > 0 {
        let left = until.saturating_duration_since(Instant::now());
        if left.is_zero() {
            log::warn!("wireless: {} tries at Wi-Fi beside the cable still under way as the app exits", b.running);
            break;
        }
        b = done.wait_timeout(b, left).unwrap().0;
    }
    (std::mem::take(&mut b.switched), std::mem::take(&mut b.made))
}

/// Make `phone`, on the cable as `cable`, reachable over Wi-Fi as well — so
/// that its app windows have somewhere to go when the cable is pulled
/// (apps/mod.rs), not only the cable to wait for.
///
/// It is the phone's own Wireless debugging (Android 11 and later), switched on
/// from the shell. No pairing: adbd lets in over Wi-Fi the computers it trusts
/// on the cable, by the same key. And unlike `adb tcpip` ([`wireless_go_wireless`])
/// it does not restart adbd, which would end every window's server.
///
/// The first time on a network the phone asks its owner whether to allow it
/// there, and says no until they do; it is switched on once a run, so that
/// question is asked once. Its port is read off the phone's own socket table
/// ([`adbd_ports`]), else from mDNS: a Galaxy A13 does not let the shell read
/// `service.adb.tls.port`, and a Redmi 9A does not announce itself.
///
/// It takes a phone that trusts this computer for good — allowed on the cable
/// with "Always allow from this computer" — and a network that lets the two
/// reach each other; adb's answer does not say which one failed.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
pub fn ready_wifi(app: &tauri::AppHandle, cable: &str, phone: &str) -> WifiReady {
    let Some(_trying) = Trying::start(beside()) else {
        return WifiReady::No(EXITING.into());
    };
    let on = || {
        run_adb_quiet(app, &["-s", cable, "shell", "settings get global adb_wifi_enabled"])
            .is_ok_and(|v| v.trim() == "1")
    };
    let was_on = on();
    if !was_on {
        {
            let b = beside().0.lock().unwrap();
            if b.switched.contains(phone) {
                return WifiReady::Off;
            }
            if b.refused.contains(phone) {
                return WifiReady::Refused;
            }
        }
        match getprop(app, cable, "ro.build.version.sdk").parse::<u32>() {
            Ok(sdk) if sdk < 30 => return WifiReady::Never,
            Ok(_) => {}
            Err(_) => return WifiReady::No("the phone did not say which Android it runs".into()),
        }
    }
    let Some(ip) = device_ip(app, cable) else {
        return WifiReady::No("the phone is not on Wi-Fi".into());
    };
    if !was_on {
        if beside().0.lock().unwrap().switching_on(phone).is_err() {
            return WifiReady::No(EXITING.into());
        }
        log::info!("wireless: switching on Wireless debugging on {phone}, for its app windows");
        let switch = ["-s", cable, "shell", "settings put global adb_wifi_enabled 1"];
        match run_adb_timeout(app, &switch, SWITCH_TIMEOUT) {
            Ok(_) => {}
            // Refused outright (a Redmi 9A's shell lacks WRITE_SECURE_SETTINGS):
            // nothing landed, so there is nothing to switch off.
            Err(e) if e.contains("SecurityException") || e.contains("Permission denial") => {
                let mut b = beside().0.lock().unwrap();
                b.switched.remove(phone);
                b.refused.insert(phone.to_string());
                return WifiReady::Refused;
            }
            // Still counted as switched on: it may have landed all the same,
            // and the exit then switches it off.
            Err(e) => return WifiReady::No(format!("Wireless debugging would not switch on: {e}")),
        }
    }
    let until = Instant::now() + WIFI_ON_WAIT;
    loop {
        if beside().0.lock().unwrap().exiting {
            return WifiReady::No(EXITING.into());
        }
        // On a network it was never allowed on, the phone puts the switch back
        // off at once, and asks.
        if !was_on && !on() {
            return WifiReady::Asking;
        }
        for way in wifi_ways(app, cable, phone, &ip) {
            let said = match connect(app, &way, WIFI_CONNECT_TIMEOUT) {
                Ok(said) => said,
                Err(e) => {
                    log::debug!("wireless: {way} did not answer — {e}");
                    continue;
                }
            };
            // Ours to let go, unless adb had it already.
            let ours = !said.to_lowercase().contains("already");
            let serial = getprop(app, &way, "ro.serialno");
            if serial == phone {
                let kept = beside().0.lock().unwrap().keep_link(phone, &way);
                let Ok(was) = kept else {
                    // The exit has undone this run's links already: this one goes too.
                    if ours {
                        let _ = run_adb_timeout(app, &["disconnect", &way], RECONNECT_TIMEOUT);
                    }
                    return WifiReady::No(EXITING.into());
                };
                log::info!("wireless: {said}");
                // A link made earlier to a port the phone has since left.
                if let Some(old) = was.filter(|old| *old != way) {
                    let _ = run_adb_timeout(app, &["disconnect", &old], RECONNECT_TIMEOUT);
                }
                return WifiReady::Yes(way);
            }
            if serial.is_empty() {
                // Let in, and then refused everything: what a phone that
                // trusts this computer for the cable's while only looks like.
                log::info!("wireless: {way} took the connection but would not run anything");
            } else {
                log::warn!("wireless: {way} is {serial}, not {phone}");
            }
            if ours {
                let _ = run_adb_timeout(app, &["disconnect", &way], RECONNECT_TIMEOUT);
            }
        }
        if Instant::now() >= until {
            return WifiReady::No(format!(
                "Wireless debugging is on, but {phone} did not let this computer in over Wi-Fi at {ip} — \
                 allowed on the cable only once rather than always, or the network keeps them apart"
            ));
        }
        thread::sleep(Duration::from_millis(500));
    }
}

/// Where `phone`'s Wireless debugging may be listening: the ports adbd
/// listens on for the network, at the phone's Wi-Fi address `ip`, then where
/// the phone announces itself over mDNS.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
fn wifi_ways(app: &tauri::AppHandle, cable: &str, phone: &str, ip: &str) -> Vec<String> {
    let mut ways: Vec<String> = adbd_ports(app, cable).into_iter().map(|port| format!("{ip}:{port}")).collect();
    if let Some(way) = announced(app, phone) {
        if !ways.contains(&way) {
            ways.push(way);
        }
    }
    ways
}

/// Where `phone` announces its Wireless debugging over mDNS: as
/// `adb-<ro.serialno>-<a suffix of its own>`, which survives a new port.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
fn announced(app: &tauri::AppHandle, phone: &str) -> Option<String> {
    let name = format!("adb-{phone}-");
    mdns_services(app)
        .into_iter()
        .find(|s| s.service.contains("adb-tls-connect") && s.instance.starts_with(&name))
        .map(|s| s.endpoint())
}

/// The ports adbd listens on for the network, from the phone's own socket table.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
fn adbd_ports(app: &tauri::AppHandle, serial: &str) -> Vec<u16> {
    let table = run_adb_quiet(app, &["-s", serial, "shell", "cat /proc/net/tcp6 /proc/net/tcp"]).unwrap_or_default();
    parse_adbd_ports(&table)
}

/// The listening sockets in `/proc/net/tcp6` and `/proc/net/tcp` that are the
/// shell's — adbd runs as it — and bound to every address, as a port for the
/// network is: the app server's touchpad, also the shell's, listens on loopback.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
fn parse_adbd_ports(table: &str) -> Vec<u16> {
    let mut ports = Vec::new();
    for line in table.lines() {
        // sl, local address, remote address, state, queues, timer, retransmits, uid…
        let cols: Vec<&str> = line.split_whitespace().collect();
        let (Some(local), Some(state), Some(uid)) = (cols.get(1), cols.get(3), cols.get(7)) else {
            continue;
        };
        if *state != "0A" || *uid != "2000" {
            continue;
        }
        let Some((address, port)) = local.split_once(':') else { continue };
        if !address.bytes().all(|b| b == b'0') {
            continue;
        }
        if let Ok(port) = u16::from_str_radix(port, 16) {
            if !ports.contains(&port) {
                ports.push(port);
            }
        }
    }
    ports
}

/// `phone`'s link made beside its cable, let go once adb lists it offline
/// while the phone is reached over Wi-Fi another way: its adbd restarted, and
/// it was found again on a new port — by adb's own mDNS connection, say. Left
/// in adb's list, the Desktop page reads it as a phone that left the network.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
pub fn drop_stale(app: &tauri::AppHandle, phone: &str, devices: &[crate::adb::DeviceInfo]) {
    let Some(made) = beside().0.lock().unwrap().made.get(phone).cloned() else { return };
    if !is_stale(&made, phone, devices) {
        return;
    }
    {
        let mut b = beside().0.lock().unwrap();
        if b.made.get(phone) != Some(&made) {
            return;
        }
        b.made.remove(phone);
    }
    log::info!("wireless: letting {made} go: {phone} is reached over Wi-Fi another way now");
    let app = app.clone();
    thread::spawn(move || {
        let _ = run_adb_timeout(&app, &["disconnect", &made], RECONNECT_TIMEOUT);
    });
}

/// Whether `made`, the link to `phone` made beside its cable, is offline in
/// adb's list while another way to the phone over Wi-Fi is up. With no
/// other, it is left to [`ready_wifi`], which lets it go as it makes the next.
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
fn is_stale(made: &str, phone: &str, devices: &[crate::adb::DeviceInfo]) -> bool {
    devices.iter().any(|d| d.serial == made && d.state != "device")
        && devices.iter().any(|d| {
            d.serial != made && d.state == "device" && d.connection == "wifi" && d.hardware_serial.as_deref() == Some(phone)
        })
}

/// This app is exiting: what [`ready_wifi`] did is undone — Wireless debugging
/// off again where it switched it on, over whichever way still reaches the
/// phone, and the Wi-Fi links it made let go. Tries under way finish first
/// ([`wind_up`]).
pub fn let_wifi_go(app: &tauri::AppHandle) {
    let (switched, made) = wind_up(beside(), TRIES_FINISH);
    if switched.is_empty() && made.is_empty() {
        return;
    }
    // A fresh look, not the last poll's: the cable may have gone a moment ago.
    let devices = crate::adb::adb_list_devices(app.clone()).unwrap_or_default();
    let handles: Vec<_> = switched
        .into_iter()
        .map(|phone| {
            let ways = ways_to_switch_off(&phone, &devices, made.get(&phone));
            let app = app.clone();
            thread::spawn(move || {
                let off = |way: &str| {
                    let switch = ["-s", way, "shell", "settings put global adb_wifi_enabled 0"];
                    run_adb_timeout(&app, &switch, SWITCH_TIMEOUT).is_ok()
                };
                match ways.iter().find(|way| off(way)) {
                    Some(way) => log::info!("wireless: Wireless debugging switched off again on {phone}, over {way}"),
                    None => log::warn!(
                        "wireless: Wireless debugging could not be switched off again on {phone} — no way to it \
                         answered ({ways:?}); the phone switches it off as it leaves this network"
                    ),
                }
            })
        })
        .collect();
    for h in handles {
        let _ = h.join();
    }
    for serial in made.values() {
        let _ = run_adb_timeout(app, &["disconnect", serial], Duration::from_secs(2));
    }
}

/// The ways to switch `phone`'s Wireless debugging off over, in turn: the cable
/// first — switched off over Wi-Fi, the link goes as it is — then each way over
/// Wi-Fi adb has, and the link this run made if adb's list did not name it.
fn ways_to_switch_off(phone: &str, devices: &[crate::adb::DeviceInfo], made: Option<&String>) -> Vec<String> {
    let mut found: Vec<&crate::adb::DeviceInfo> =
        devices.iter().filter(|d| d.state == "device" && d.hardware_serial.as_deref() == Some(phone)).collect();
    found.sort_by_key(|d| d.connection != "usb");
    let mut ways: Vec<String> = found.into_iter().map(|d| d.serial.clone()).collect();
    if let Some(made) = made.filter(|m| !ways.contains(m)) {
        ways.push(made.clone());
    }
    ways
}

// ── Pairing ─────────────────────────────────────────────────────────────

/// `adb pair`, told apart from its failures properly.
///
/// Every other outcome — a wrong code, a phone that walked away, the newer
/// adb's lost error message — is indistinguishable from the outside, so this
/// only ever claims success on the one string adb prints when it means it.
fn pair(app: &tauri::AppHandle, address: &str, code: &str) -> Result<String, String> {
    let (_ok, stdout, stderr) =
        run_adb_stdin(app, &["pair", address], &format!("{code}\n"), PAIR_TIMEOUT)?;

    // The prompt adb writes before reading stdin sits in front of the answer.
    let answer = stdout
        .split("Enter pairing code:")
        .last()
        .unwrap_or(&stdout)
        .trim();

    if let Some(rest) = answer.strip_prefix("Successfully paired to ") {
        let guid = rest
            .split_once("[guid=")
            .and_then(|(_, g)| g.split_once(']'))
            .map(|(g, _)| g.to_string())
            .unwrap_or_default();
        log::info!("wireless: paired with {address} (guid={guid})");
        return Ok(guid);
    }

    // `Failed: …` on stdout is the older adb; `error: protocol fault` on
    // stderr is the newer one, where the server tears the transport down
    // before the real message reaches the client. Neither says which of
    // "wrong code" or "phone gave up" happened.
    let raw = if answer.is_empty() { &stderr } else { answer };
    log::warn!("wireless: pairing with {address} failed — out={answer:?} err={stderr:?}");
    Err(if raw.contains("protocol fault") || raw.contains("Wrong password") || raw.is_empty() {
        "Pairing failed. Check the code — it is only valid while the phone's pairing \
         dialog is open, and a new one is issued every time it is reopened."
            .to_string()
    } else {
        raw.to_string()
    })
}

/// The serial adb is already using for this phone, if it has one.
///
/// adb auto-connects after a successful pair and names the transport after the
/// mDNS service ("adb-R5CT30…-vWgJpq._adb-tls-connect._tcp"). Connecting again
/// by address would work, and would leave the same phone in `adb devices`
/// twice under two different serials — one phone, two desktops to choose from.
fn attached_as(app: &tauri::AppHandle, guid: &str) -> Option<String> {
    if guid.is_empty() {
        return None;
    }
    let raw = run_adb_quiet(app, &["devices"]).unwrap_or_default();
    // Split at the tab, not at whitespace: a serial named after an mDNS
    // service can have a space in it ("… (2)._adb-tls-connect._tcp").
    raw.lines()
        .filter(|l| l.ends_with("device"))
        .filter_map(|l| l.split('\t').next())
        .find(|serial| serial.contains(guid))
        .map(str::to_string)
}

/// Finish a pairing: find where the freshly-trusted phone is listening and
/// connect to it.
///
/// adb auto-connects on a successful pair, but only when its own mDNS
/// auto-connect is enabled — which is a default, not a guarantee. Doing it
/// explicitly costs one lookup and removes the "paired, but nothing happened"
/// dead end.
fn connect_after_pair(app: &tauri::AppHandle, guid: &str) -> Result<WirelessResult, String> {
    let mut last = String::from("the phone did not reappear on the network");
    for attempt in 1..=10 {
        // adb may have got there first.
        if let Some(serial) = attached_as(app, guid) {
            log::info!("wireless: adb already has {serial} attached");
            let label = device_label(app, &serial);
            remember(
                app,
                KnownDevice {
                    label: label.clone(),
                    address: serial.clone(),
                    guid: Some(guid.to_string()),
                    hardware_serial: Some(getprop(app, &serial, "ro.serialno"))
                        .filter(|s| !s.is_empty()),
                },
            );
            return Ok(WirelessResult {
                serial: serial.clone(),
                address: serial,
                label,
            });
        }
        // Match on the guid: on a LAN with two phones, connecting to whichever
        // pairing record happened to be first is how you end up projecting
        // someone else's device.
        let found = mdns_services(app).into_iter().find(|s| {
            s.is_connect() && (guid.is_empty() || s.instance == guid || s.instance.starts_with(guid))
        });
        if let Some(svc) = found {
            let address = svc.endpoint();
            match connect(app, &address, Duration::from_secs(8)) {
                Ok(msg) => {
                    log::info!("wireless: {msg}");
                    let label = device_label(app, &address);
                    remember(
                        app,
                        KnownDevice {
                            label: label.clone(),
                            address: address.clone(),
                            guid: Some(guid.to_string()).filter(|g| !g.is_empty()),
                            hardware_serial: Some(getprop(app, &address, "ro.serialno"))
                                .filter(|s| !s.is_empty()),
                        },
                    );
                    return Ok(WirelessResult {
                        serial: address.clone(),
                        address,
                        label,
                    });
                }
                Err(e) => last = e,
            }
        }
        log::debug!("wireless: waiting for {guid} to advertise itself ({attempt}/10)");
        thread::sleep(Duration::from_millis(700));
    }
    Err(last)
}

/// Pair with the six-digit code the phone is showing.
///
/// `address` may be left empty: the pairing service is discovered over mDNS,
/// which is the whole point — the phone shows an address *and* a code, and
/// only the code should have to be copied across.
#[tauri::command(async)]
pub fn wireless_pair(
    app: tauri::AppHandle,
    code: String,
    address: Option<String>,
) -> Result<WirelessResult, String> {
    let code = code.trim().to_string();
    // Six random digits, and it can start with a zero — never a number.
    if code.len() < 6 || !code.chars().all(|c| c.is_ascii_digit()) {
        return Err("the pairing code is the six digits shown on the phone".into());
    }

    let address = match address.map(|a| a.trim().to_string()).filter(|a| !a.is_empty()) {
        Some(a) => a,
        None => {
            let services = mdns_services(&app);
            let pairing: Vec<_> = services.iter().filter(|s| s.is_pairing()).collect();
            match pairing.len() {
                0 => return Err(
                    "No phone is waiting to be paired. Open Developer options → Wireless \
                     debugging → Pair device with pairing code, and leave that screen open."
                        .into(),
                ),
                1 => pairing[0].endpoint(),
                _ => {
                    return Err(
                        "More than one phone is waiting to be paired. Enter the address shown \
                         on the phone as well, so the right one is chosen."
                            .into(),
                    )
                }
            }
        }
    };

    log::info!("── pairing with {address} ──");
    let guid = pair(&app, &address, &code)?;
    connect_after_pair(&app, &guid)
}

// ── QR pairing ──────────────────────────────────────────────────────────

/// Instance-name alphabet. DNS-SD instance labels want letters, digits and
/// hyphens; a hyphen at either end is asking for trouble, so it is left out
/// entirely rather than special-cased.
const NAME_ALPHABET: &[u8] = b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
/// Password alphabet. `\ ; , :` are absent deliberately: the first three are
/// separators in the QR payload grammar, and a `:` would be worse — adb splits
/// its own `host:pair:<password>:<host>` request on the first colon, so a
/// password containing one is silently truncated.
const PASSWORD_ALPHABET: &[u8] =
    b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()-+*/<>{}";

#[derive(Debug, Clone)]
struct QrSession {
    service_name: String,
    password: String,
}

/// The armed QR challenge, if any.
///
/// The password is a live credential for the phone, so it is kept here and
/// never handed to the webview — the front end only ever learns the module
/// matrix to draw.
///
/// `generation` and `busy` exist because polling is on a timer and the work is
/// slow. `busy` keeps a second tick out while the first is still talking to
/// adb — `adb mdns services` can outlast the poll interval, and `adb pair`
/// routinely does. `generation` is how a poll that started before a cancel (or
/// before a re-arm) knows that what it is holding is stale, instead of putting
/// a dead challenge back or pairing against a code that is no longer on screen.
#[derive(Default)]
struct QrState {
    session: Option<QrSession>,
    generation: u64,
    busy: bool,
}

static QR_SESSION: Mutex<QrState> = Mutex::new(QrState {
    session: None,
    generation: 0,
    busy: false,
});

/// Bytes from the system CSPRNG.
///
/// `pub(crate)` rather than private because the file service mints its session
/// token with it too (`files.rs`): both are live credentials for the phone, and
/// a second implementation would be a second place to get it wrong — or to
/// quietly fall back to a seeded hasher.
#[cfg(windows)]
pub(crate) fn fill_random(out: &mut [u8]) -> bool {
    use windows_sys::Win32::Security::Cryptography::{
        BCryptGenRandom, BCRYPT_USE_SYSTEM_PREFERRED_RNG,
    };
    let status = unsafe {
        BCryptGenRandom(
            std::ptr::null_mut(),
            out.as_mut_ptr(),
            out.len() as u32,
            BCRYPT_USE_SYSTEM_PREFERRED_RNG,
        )
    };
    status == 0
}

/// The same, from the kernel's own pool.
#[cfg(not(windows))]
pub(crate) fn fill_random(out: &mut [u8]) -> bool {
    use std::io::Read as _;
    std::fs::File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(out))
        .is_ok()
}

/// A random string over `alphabet`.
///
/// Rejection sampling rather than `% len`: the modulo would make the first
/// few characters of the alphabet more likely, and this string is the only
/// thing standing between a stranger on the same Wi-Fi and a paired phone.
fn random_string(len: usize, alphabet: &[u8]) -> Result<String, String> {
    let mut out = String::with_capacity(len);
    let limit = (256 / alphabet.len()) * alphabet.len();
    let mut buf = [0u8; 64];
    while out.len() < len {
        if !fill_random(&mut buf) {
            return Err("the system random number generator is unavailable".into());
        }
        for b in buf.iter() {
            if (*b as usize) < limit {
                out.push(alphabet[*b as usize % alphabet.len()] as char);
                if out.len() == len {
                    break;
                }
            }
        }
    }
    Ok(out)
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QrChallenge {
    /// Square side in modules.
    pub width: usize,
    /// Row-major, `true` = dark. Drawn by the front end so it can match the
    /// window's own theme instead of shipping a bitmap.
    pub modules: Vec<bool>,
}

/// Put a pairing QR on screen.
///
/// The payload is the Wi-Fi-QR grammar with the ADB type, exactly what
/// Android's own scanner expects:
/// `WIFI:T:ADB;S:<service name>;P:<password>;;`
///
/// `S:` is a *request*: the phone advertises its pairing server under that
/// instance name, which is how [`wireless_qr_poll`] knows which of the phones
/// on the network just scanned our code rather than someone else's.
/// Build the scanner payload.
///
/// Values are escaped even though the alphabets above cannot produce a
/// character that needs it. The escaping is the contract Android's parser
/// implements, and a later change to either alphabet should not be able to
/// quietly corrupt the payload instead of failing loudly.
fn qr_payload(service_name: &str, password: &str) -> String {
    fn esc(value: &str) -> String {
        let mut out = String::with_capacity(value.len());
        for ch in value.chars() {
            if matches!(ch, '\\' | ';' | ':' | ',') {
                out.push('\\');
            }
            out.push(ch);
        }
        out
    }
    format!("WIFI:T:ADB;S:{};P:{};;", esc(service_name), esc(password))
}

#[tauri::command(async)]
pub fn wireless_qr_begin() -> Result<QrChallenge, String> {
    let service_name = format!("oadex-{}", random_string(10, NAME_ALPHABET)?);
    let password = random_string(12, PASSWORD_ALPHABET)?;
    let payload = qr_payload(&service_name, &password);

    let code =
        qrcode::QrCode::with_error_correction_level(payload.as_bytes(), qrcode::EcLevel::L)
            .map_err(|e| format!("could not build the QR code: {e}"))?;
    let width = code.width();
    let modules = code
        .to_colors()
        .into_iter()
        .map(|c| c == qrcode::Color::Dark)
        .collect();

    log::info!("wireless: QR pairing armed, waiting for {service_name}");
    let mut state = QR_SESSION.lock().unwrap();
    state.generation = state.generation.wrapping_add(1);
    state.busy = false;
    state.session = Some(QrSession {
        service_name,
        password,
    });
    drop(state);
    Ok(QrChallenge { width, modules })
}

#[tauri::command(async)]
pub fn wireless_qr_cancel() {
    let mut state = QR_SESSION.lock().unwrap();
    if state.session.take().is_some() {
        log::info!("wireless: QR pairing cancelled");
    }
    // Bumped even when there was nothing to cancel: a poll already in flight
    // must not be allowed to finish against a challenge the user has left.
    state.generation = state.generation.wrapping_add(1);
    state.busy = false;
}

/// Has the phone scanned it yet? Called on a timer while the code is up.
///
/// Returns `None` until the phone appears, then does the whole rest of the
/// job — pair, connect, remember — and returns the device.
#[tauri::command(async)]
pub fn wireless_qr_poll(app: tauri::AppHandle) -> Result<Option<WirelessResult>, String> {
    // Claim the tick. Anything already talking to adb owns the challenge until
    // it is finished with it.
    let (session, generation) = {
        let mut state = QR_SESSION.lock().unwrap();
        if state.busy {
            return Ok(None);
        }
        let Some(session) = state.session.clone() else {
            return Ok(None);
        };
        state.busy = true;
        (session, state.generation)
    };

    /// Release the claim, unless the challenge has moved on underneath us.
    fn release(generation: u64, consume: bool) {
        let mut state = QR_SESSION.lock().unwrap();
        if state.generation != generation {
            return;
        }
        state.busy = false;
        if consume {
            state.session = None;
        }
    }

    // An exact match, not a prefix: the name was generated precisely so that
    // another phone — or another copy of this app — cannot be mistaken for the
    // one the user is holding.
    let found = mdns_services(&app)
        .into_iter()
        .find(|s| s.is_pairing() && s.instance == session.service_name);

    let Some(svc) = found else {
        release(generation, false);
        return Ok(None);
    };

    // Still ours? A cancel or a re-arm during the lookup means this code is no
    // longer the one on screen, and pairing against it would consume a
    // challenge the user has already walked away from.
    {
        let state = QR_SESSION.lock().unwrap();
        if state.generation != generation {
            return Ok(None);
        }
    }

    log::info!("── QR scanned, pairing with {} ──", svc.endpoint());
    let outcome = pair(&app, &svc.endpoint(), &session.password)
        .and_then(|guid| connect_after_pair(&app, &guid));
    // Spent either way: the phone unregisters its pairing record the moment
    // the handshake ends, so a retry with this code could only ever fail.
    release(generation, true);
    outcome.map(Some)
}

// ── Remembered phones ───────────────────────────────────────────────────

fn store_path(app: &tauri::AppHandle) -> Option<PathBuf> {
    app.path()
        .app_config_dir()
        .ok()
        .map(|d| d.join("wireless-devices.json"))
}

fn load(app: &tauri::AppHandle) -> Vec<KnownDevice> {
    store_path(app)
        .and_then(|p| std::fs::read_to_string(p).ok())
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn save(app: &tauri::AppHandle, list: &[KnownDevice]) {
    let Some(path) = store_path(app) else { return };
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if let Ok(json) = serde_json::to_string_pretty(list) {
        let _ = std::fs::write(path, json);
    }
}

/// Record a phone, replacing any earlier entry for the same one.
///
/// Identity is the hardware serial or the guid, never the address: a phone
/// that moved to a new DHCP lease is the same phone, and keying on the address
/// is how a saved list turns into a list of every address a phone has ever
/// had.
fn remember(app: &tauri::AppHandle, device: KnownDevice) {
    let mut list = load(app);
    list.retain(|d| {
        let same_hardware = match (&d.hardware_serial, &device.hardware_serial) {
            (Some(a), Some(b)) => a == b,
            _ => false,
        };
        let same_guid = match (&d.guid, &device.guid) {
            (Some(a), Some(b)) => a == b,
            _ => false,
        };
        !(same_hardware || same_guid || d.address == device.address)
    });
    log::info!(
        "wireless: remembering {} at {}",
        device.label,
        device.address
    );
    list.insert(0, device);
    list.truncate(8);
    save(app, &list);
}

#[tauri::command(async)]
pub fn wireless_known(app: tauri::AppHandle) -> Result<Vec<KnownDevice>, String> {
    Ok(load(&app))
}

#[tauri::command(async)]
pub fn wireless_forget(app: tauri::AppHandle, address: String) -> Result<(), String> {
    let mut list = load(&app);
    let before = list.len();
    list.retain(|d| d.address != address);
    if list.len() != before {
        log::info!("wireless: forgetting {address}");
        save(&app, &list);
    }
    // Also drop the transport, or the phone stays in `adb devices` and the
    // desktop relaunches onto the device that was just forgotten.
    let _ = run_adb_timeout(&app, &["disconnect", &address], RECONNECT_TIMEOUT);
    Ok(())
}

/// Try every remembered phone at once, and say which came back.
///
/// This runs at boot before the connect screen is shown, so the common case —
/// same phone, same Wi-Fi, second time — reaches the desktop without the user
/// touching anything.
///
/// The guid lookup is what makes it hold up over time. A saved address goes
/// stale on a new DHCP lease, and Android re-rolls the wireless-debugging port
/// every time the toggle is cycled, but the phone advertises itself under the
/// same guid throughout — so the address is a hint and mDNS is the answer.
///
/// `only` narrows it to a single saved address — what the "Connect" button on
/// one row means. Without it the caller cannot tell whose reply came back, and
/// clicking Connect on one phone would happily attach a different one.
#[tauri::command(async)]
pub fn wireless_reconnect_known(
    app: tauri::AppHandle,
    only: Option<String>,
) -> Result<Vec<String>, String> {
    Ok(reconnect_where(&app, |d| only.as_deref().is_none_or(|a| a == d.address)))
}

/// `phone`'s Wi-Fi link made again (its `ro.serialno`), if this computer
/// remembers how to reach it, or made it reachable beside its cable this run
/// ([`ready_wifi`]) — what an app window waiting for its phone asks for: after
/// a Samsung phone's adbd restarted as the cable went, nothing else brings its
/// wireless transport back, and its port may have moved. The serials it is
/// attached as now.
pub fn reconnect_phone(app: &tauri::AppHandle, phone: &str) -> Vec<String> {
    let back = reconnect_where(app, |d| d.hardware_serial.as_deref() == Some(phone));
    if !back.is_empty() {
        return back;
    }
    // Made reachable beside its cable this run (ready_wifi): where it
    // announces itself now, else where it was — a Samsung phone's adbd,
    // restarting as the cable goes, listens on a new port after. Or never
    // reached that way, and announcing Wireless debugging now: allowed on the
    // phone only after its cable went.
    let Some(_trying) = Trying::start(beside()) else {
        return back;
    };
    let was = beside().0.lock().unwrap().made.get(phone).cloned();
    let Some(address) = was.or_else(|| announced(app, phone)) else {
        return back;
    };
    let device = KnownDevice {
        label: phone.to_string(),
        address,
        guid: Some(format!("adb-{phone}-")),
        hardware_serial: Some(phone.to_string()),
    };
    let back = reconnect_these(app, vec![device], moved_beside);
    // Connected by this app, so let go as it exits; one adb made itself is
    // named after its mDNS service.
    if let Some(made) = back.iter().find(|s| s.contains(':')) {
        let mut b = beside().0.lock().unwrap();
        if b.exiting {
            drop(b);
            // The exit has undone this run's links already: this one goes too.
            let _ = run_adb_timeout(app, &["disconnect", made], RECONNECT_TIMEOUT);
        } else {
            b.made.entry(phone.to_string()).or_insert_with(|| made.clone());
        }
    }
    back
}

/// The remembered phones `keep` says, connected again: [`wireless_reconnect_known`].
fn reconnect_where(app: &tauri::AppHandle, keep: impl Fn(&KnownDevice) -> bool) -> Vec<String> {
    let known: Vec<KnownDevice> = load(app).into_iter().filter(|d| keep(d)).collect();
    reconnect_these(app, known, moved_saved)
}

/// A remembered phone answered somewhere other than its saved address: the
/// saved list follows it.
fn moved_saved(app: &tauri::AppHandle, saved: &str, now: &str) {
    let mut list = load(app);
    if let Some(entry) = list.iter_mut().find(|d| d.address == saved) {
        entry.address = now.to_string();
    }
    save(app, &list);
}

/// A phone made reachable beside its cable answered somewhere new: this run
/// follows it, and its old link goes — left offline in adb's list, the Desktop
/// page would take it for a phone that left the network.
fn moved_beside(app: &tauri::AppHandle, saved: &str, now: &str) {
    for address in beside().0.lock().unwrap().made.values_mut() {
        if address == saved {
            *address = now.to_string();
        }
    }
    let _ = run_adb_timeout(app, &["disconnect", saved], RECONNECT_TIMEOUT);
}

/// `known` connected again, at once: the serials they are attached as now.
/// `moved` is told of one that answered at another address than it had.
fn reconnect_these(app: &tauri::AppHandle, known: Vec<KnownDevice>, moved: fn(&tauri::AppHandle, &str, &str)) -> Vec<String> {
    let app = app.clone();
    if known.is_empty() {
        return Vec::new();
    }
    log::info!("wireless: trying {} remembered phone(s)", known.len());

    let live = mdns_services(&app);
    let mut handles = Vec::new();
    let mut already = Vec::new();
    for device in known {
        // adb keeps its transports across runs of this app, so the phone may
        // already be attached — under its mDNS name rather than the saved
        // address. Connecting again would double it up in `adb devices`.
        if let Some(serial) = device.guid.as_deref().and_then(|g| attached_as(&app, g)) {
            log::info!("wireless: {} is already attached as {serial}", device.label);
            already.push(serial);
            continue;
        }
        // Prefer wherever the phone says it is right now over wherever it was.
        let address = device
            .guid
            .as_deref()
            .and_then(|guid| {
                live.iter()
                    .find(|s| s.is_connect() && (s.instance == guid || s.instance.starts_with(guid)))
            })
            .map(|s| s.endpoint())
            .unwrap_or_else(|| device.address.clone());

        let app = app.clone();
        let saved = device.address.clone();
        handles.push(thread::spawn(move || {
            match connect(&app, &address, RECONNECT_TIMEOUT) {
                Ok(msg) => {
                    log::info!("wireless: remembered phone is back — {msg}");
                    // The address it answered on may not be the one on file.
                    if address != saved {
                        moved(&app, &saved, &address);
                    }
                    Some(address)
                }
                Err(e) => {
                    log::debug!("wireless: {address} did not answer — {e}");
                    None
                }
            }
        }));
    }

    already.extend(handles.into_iter().filter_map(|h| h.join().ok().flatten()));
    already
}

/// Drop a wireless transport. The phone keeps listening; this end just stops
/// treating it as attached.
#[tauri::command(async)]
pub fn wireless_disconnect(app: tauri::AppHandle, serial: String) -> Result<String, String> {
    log::info!("wireless: disconnecting {serial}");
    run_adb_timeout(&app, &["disconnect", &serial], RECONNECT_TIMEOUT)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_mdns_rows() {
        let raw = "List of discovered mdns services\n\
                   adb-14141FDF600081\t_adb._tcp\t192.168.86.38:5555\n\
                   adb-14141FDF600081-QXjCrW\t_adb-tls-pairing._tcp\t192.168.86.38:33861\n\
                   oadex-Ab3xK9pQ71\t_adb-tls-pairing._tcp\t192.168.86.39:55861\n\
                   \n";
        let out = parse_mdns(raw);
        assert_eq!(out.len(), 3);
        assert_eq!(out[0].instance, "adb-14141FDF600081");
        assert_eq!(out[0].port, 5555);
        assert!(out[0].is_connect());
        assert!(out[1].is_pairing());
        assert_eq!(out[2].endpoint(), "192.168.86.39:55861");
    }

    #[test]
    fn tolerates_space_padded_columns() {
        let raw = "List of discovered mdns services\n\
                   adb-X-QXjCrW   _adb-tls-connect._tcp   10.0.0.4:37015\n";
        let out = parse_mdns(raw);
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].address, "10.0.0.4");
        assert_eq!(out[0].port, 37015);
    }

    /// Verbatim from a Galaxy S25 Ultra (SM-S938B, One UI) with mobile data
    /// and Wi-Fi both up. The cellular route comes first, which is exactly the
    /// case that makes a "first src wins" parser return an unreachable address.
    const REAL_IP_ROUTE: &str = "192.0.0.0/27 dev rmnet_data0 proto kernel scope link src 192.0.0.2 \n\
                                 192.168.2.0/24 dev wlan0 proto kernel scope link src 192.168.2.28 \n";

    #[test]
    fn ignores_the_cellular_route() {
        assert_eq!(parse_ip_route(REAL_IP_ROUTE).as_deref(), Some("192.168.2.28"));
    }

    #[test]
    fn ignores_vpn_and_hotspot_interfaces() {
        let routes = "10.8.0.0/24 dev tun0 proto kernel scope link src 10.8.0.6\n\
                      192.168.43.0/24 dev swlan0 proto kernel scope link src 192.168.43.1\n\
                      192.168.2.0/24 dev wlan0 proto kernel scope link src 192.168.2.28\n";
        assert_eq!(parse_ip_route(routes).as_deref(), Some("192.168.2.28"));
        assert_eq!(parse_ip_route("192.0.0.0/27 dev rmnet_data0 src 192.0.0.2\n"), None);
    }

    #[test]
    fn reads_the_wlan0_fallback() {
        // Also verbatim from the same device.
        let addrs = "45: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP group default qlen 3000\n    \
                     inet 192.168.2.28/24 brd 192.168.2.255 scope global dynamic wlan0\n       \
                     valid_lft 81964sec preferred_lft 81964sec\n";
        assert_eq!(parse_inet_addr(addrs).as_deref(), Some("192.168.2.28"));
    }

    /// The listener on 0xA709 (42761) is verbatim from a Galaxy A13 (Android
    /// 12) with Wireless debugging on; around it, what else a phone's table
    /// holds: a connection to it, the app server's touchpad on loopback, `adb
    /// tcpip`'s 5555, root's and an app's own listeners.
    #[test]
    fn reads_adbds_ports_off_the_socket_table() {
        let table = "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n\
                     0: 00000000000000000000000000000000:A709 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 45251593 1 0000000000000000 100 0 0 10 0\n\
                     1: 0000000000000000FFFF00000A02A8C0:A709 0000000000000000FFFF00002202A8C0:D2C4 01 00000000:00000000 02:00000A8C 00000000  2000        0 45251601 2 0000000000000000 20 4 30 10 -1\n\
                     sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n\
                     0: 0100007F:9715 00000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 45250011 1 0000000000000000 100 0 0 10 0\n\
                     1: 00000000:15B3 00000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 45250099 1 0000000000000000 100 0 0 10 0\n\
                     2: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 30311 1 0000000000000000 100 0 0 10 0\n\
                     3: 00000000:22B8 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10234        0 30411 1 0000000000000000 100 0 0 10 0\n";
        assert_eq!(parse_adbd_ports(table), vec![42761, 5555]);
        assert!(parse_adbd_ports("").is_empty());
    }

    fn device(serial: &str, state: &str, hardware: Option<&str>) -> crate::adb::DeviceInfo {
        crate::adb::DeviceInfo {
            serial: serial.into(),
            state: state.into(),
            model: "M2006C3LG".into(),
            product: String::new(),
            connection: if serial.contains(':') || serial.contains("adb-tls-connect") { "wifi" } else { "usb" }.into(),
            brand: None,
            android_version: None,
            hardware_serial: hardware.map(Into::into),
            name: None,
        }
    }

    #[test]
    fn the_exit_waits_for_tries_under_way_and_takes_what_they_did() {
        let on: &'static (Mutex<Beside>, Condvar) = Box::leak(Box::default());
        // A try that switched a phone on and made a link before the exit…
        let trying = Trying::start(on).unwrap();
        on.0.lock().unwrap().switching_on("R58T51N3G2X").unwrap();
        on.0.lock().unwrap().keep_link("R58T51N3G2X", "192.168.2.10:42761").unwrap();
        let started = Instant::now();
        let finishing = thread::spawn(move || {
            thread::sleep(Duration::from_millis(150));
            // …and, the app exiting meanwhile, may keep nothing more: it lets
            // its link go itself.
            assert_eq!(on.0.lock().unwrap().keep_link("R58T51N3G2X", "192.168.2.10:37011"), Err(Exiting));
            drop(trying);
        });
        let (switched, made) = wind_up(on, Duration::from_secs(5));
        assert!(started.elapsed() >= Duration::from_millis(150), "the exit waited for the try");
        finishing.join().unwrap();
        assert!(switched.contains("R58T51N3G2X"));
        assert_eq!(made.get("R58T51N3G2X").map(String::as_str), Some("192.168.2.10:42761"));
        // None starts after, and none records.
        assert!(Trying::start(on).is_none());
        assert_eq!(on.0.lock().unwrap().switching_on("YPKRA6VS754L6XTO"), Err(Exiting));
    }

    #[test]
    fn the_exit_waits_for_a_try_only_so_long() {
        let on: &'static (Mutex<Beside>, Condvar) = Box::leak(Box::default());
        // In an `adb connect` that is not cut short.
        let _trying = Trying::start(on).unwrap();
        let started = Instant::now();
        wind_up(on, Duration::from_millis(50));
        let waited = started.elapsed();
        assert!(waited >= Duration::from_millis(50) && waited < Duration::from_secs(2), "{waited:?}");
    }

    /// The cable, then Wi-Fi, then the link this run made — and from a list
    /// made at the exit: a cable pulled a moment before is not tried.
    #[test]
    fn wireless_debugging_is_switched_off_over_any_way_left() {
        let phone = "YPKRA6VS754L6XTO";
        let made = "192.168.2.24:34719".to_string();
        let cable = device(phone, "device", Some(phone));
        let adbs_own = device("adb-YPKRA6VS754L6XTO-g2Hl70._adb-tls-connect._tcp", "device", Some(phone));
        let another = device("R58T51N3G2X", "device", Some("R58T51N3G2X"));
        let offline = device("192.168.2.24:39551", "offline", None);
        let list = [adbs_own.clone(), another.clone(), offline.clone(), cable];
        assert_eq!(
            ways_to_switch_off(phone, &list, Some(&made)),
            vec![phone.to_string(), adbs_own.serial.clone(), made.clone()]
        );
        // The cable just pulled: Wi-Fi only.
        assert_eq!(
            ways_to_switch_off(phone, &[another, offline, adbs_own.clone()], Some(&made)),
            vec![adbs_own.serial.clone(), made.clone()]
        );
        // The link this run made, already in the list, is not tried twice.
        let listed = device(&made, "device", Some(phone));
        assert_eq!(ways_to_switch_off(phone, &[listed], Some(&made)), vec![made]);
    }

    /// As adb listed a Redmi 9A after its adbd restarted (2026-10-27): the
    /// link made beside the cable offline, adb's own mDNS one to the new port up.
    #[test]
    fn a_link_beside_the_cable_goes_stale_when_another_takes_over() {
        let (made, phone) = ("192.168.2.24:39551", "YPKRA6VS754L6XTO");
        let cable = device(phone, "device", Some(phone));
        let offline = device(made, "offline", None);
        let adbs_own = device("adb-YPKRA6VS754L6XTO-g2Hl70._adb-tls-connect._tcp", "device", Some(phone));
        assert!(is_stale(made, phone, &[cable.clone(), offline.clone(), adbs_own.clone()]));
        // No other way over Wi-Fi — the cable is not one: ready_wifi's to replace.
        assert!(!is_stale(made, phone, &[cable.clone(), offline]));
        // Up, or gone from the list already: nothing to let go.
        assert!(!is_stale(made, phone, &[cable.clone(), device(made, "device", Some(phone)), adbs_own.clone()]));
        assert!(!is_stale(made, phone, &[cable, adbs_own]));
    }

    #[test]
    fn qr_payload_matches_the_android_grammar() {
        assert_eq!(
            qr_payload("oadex-Ab3xK9pQ71", "s0meP@ssw0rd"),
            "WIFI:T:ADB;S:oadex-Ab3xK9pQ71;P:s0meP@ssw0rd;;"
        );
        // Not reachable from our alphabets, but the parser's contract all the
        // same — and the guard against a later alphabet change going unnoticed.
        assert_eq!(
            qr_payload("name", r"a;b:c,d\e"),
            r"WIFI:T:ADB;S:name;P:a\;b\:c\,d\\e;;"
        );
    }

    #[test]
    fn qr_encodes_a_real_challenge() {
        let payload = qr_payload(
            &format!("oadex-{}", random_string(10, NAME_ALPHABET).unwrap()),
            &random_string(12, PASSWORD_ALPHABET).unwrap(),
        );
        let code =
            qrcode::QrCode::with_error_correction_level(payload.as_bytes(), qrcode::EcLevel::L)
                .unwrap();
        assert_eq!(code.to_colors().len(), code.width() * code.width());
    }

    #[test]
    fn random_strings_avoid_qr_separators() {
        let pw = random_string(64, PASSWORD_ALPHABET).unwrap();
        assert_eq!(pw.len(), 64);
        assert!(!pw.contains(':'));
        assert!(!pw.contains(';'));
        assert!(!pw.contains(','));
        assert!(!pw.contains('\\'));
    }
}
