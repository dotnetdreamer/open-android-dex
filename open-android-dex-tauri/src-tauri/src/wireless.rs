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
use std::io::{Read, Write};
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::Mutex;
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

fn device_label(app: &tauri::AppHandle, serial: &str) -> String {
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
    raw.lines()
        .filter(|l| l.ends_with("device"))
        .filter_map(|l| l.split_whitespace().next())
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

#[cfg(windows)]
fn fill_random(out: &mut [u8]) -> bool {
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

#[cfg(not(windows))]
fn fill_random(out: &mut [u8]) -> bool {
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
    let known: Vec<KnownDevice> = load(&app)
        .into_iter()
        .filter(|d| only.as_deref().is_none_or(|a| a == d.address))
        .collect();
    if known.is_empty() {
        return Ok(Vec::new());
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
                        let mut list = load(&app);
                        if let Some(entry) = list.iter_mut().find(|d| d.address == saved) {
                            entry.address = address.clone();
                        }
                        save(&app, &list);
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
    Ok(already)
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
