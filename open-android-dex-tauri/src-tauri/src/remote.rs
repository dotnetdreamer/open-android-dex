//! Phone Linux over SSH — the desktop app's half of remote access.
//!
//! The phone runs Ubuntu with an sshd behind an iroh tunnel (see
//! openandroiddex-remote/ and doc/remote-access.md). This module makes this
//! computer one of the ones allowed in, and then gets out of the way: pairing
//! writes a `Host` block for the phone into `~/.ssh`, after which `ssh <phone>`
//! works from any terminal, and so do scp, rsync, git and VS Code's Remote-SSH
//! — none of which know this app exists. The app binary is their ProxyCommand
//! (`ssh-proxy`, handled by [`run_cli`] before Tauri starts), so there is
//! nothing else to install, on Windows least of all.
//!
//! Two ways to pair:
//!
//! * **By code.** The phone shows eight characters; [`remote_pair_code`] turns
//!   them into a meeting place and pairs over the network, wherever both are.
//! * **Over the cable.** With the phone plugged in, adb has already proved
//!   which computer is at the other end, so [`remote_pair_usb`] exchanges keys
//!   with the launcher's RemoteProvider in one `content call` — no code at all.

use std::sync::Arc;
use std::time::Duration;

use std::sync::OnceLock;

use dexremote::computer::{self, Computer, Phone};
use dexremote::iroh::EndpointId;
use dexremote::pc::Client;
use dexremote::usb::{self, PhoneAnswer};
use serde::Serialize;
use tauri::AppHandle;
use tokio::sync::Mutex;

use crate::adb;

/// The ProxyCommand every phone's `Host` block runs: this very binary.
fn proxy_command() -> Vec<String> {
    let exe = std::env::current_exe()
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_else(|_| "open-android-dex".into());
    vec![exe, "ssh-proxy".into()]
}

/// A phone, as the window lists it.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhoneView {
    pub id: String,
    pub name: String,
    pub alias: String,
    pub user: String,
    pub paired_at: u64,
    pub via: String,
    /// What to type: `ssh <alias>`.
    pub ssh_command: String,
    /// Straight after a USB pairing: what still stands between this and a
    /// login on the phone's side, in a sentence (see `PhoneAnswer::caveat`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
}

impl From<&Phone> for PhoneView {
    fn from(p: &Phone) -> Self {
        PhoneView {
            id: p.id.clone(),
            name: p.name.clone(),
            alias: p.alias.clone(),
            user: p.user.clone(),
            paired_at: p.paired_at,
            via: p.via.clone(),
            ssh_command: format!("ssh {}", p.alias),
            note: None,
        }
    }
}

/// How a phone answered a probe.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeView {
    pub reachable: bool,
    pub direct: bool,
    pub rtt_ms: u64,
    /// The guest sshd: up, starting, down, failed, missing, installing.
    pub ssh: String,
    pub sessions: u32,
    /// A sentence, when it was not reachable.
    pub error: Option<String>,
}

fn computer() -> Result<Computer, String> {
    Computer::open_default().map_err(|e| e.to_string())
}

/// One network client for the window's lifetime — probes every few seconds
/// would otherwise each pay for a fresh endpoint and a relay handshake.
static CLIENT: OnceLock<Mutex<Option<Arc<Client>>>> = OnceLock::new();

async fn client() -> Result<Arc<Client>, String> {
    let mut slot = CLIENT.get_or_init(|| Mutex::new(None)).lock().await;
    if let Some(c) = slot.as_ref() {
        if !c.endpoint().is_closed() {
            return Ok(c.clone());
        }
    }
    let key = computer()?.endpoint_key().map_err(|e| e.to_string())?;
    let c = Arc::new(Client::bind(key).await.map_err(|e| e.to_string())?);
    *slot = Some(c.clone());
    Ok(c)
}

/// Rewrite the SSH files from the list, so a moved or updated app fixes its
/// own ProxyCommand. Run once at startup; a no-op with nothing paired.
pub fn refresh_ssh_files() {
    let Ok(c) = computer() else { return };
    if c.phones().is_empty() {
        return;
    }
    match c.write_ssh_files(&proxy_command()) {
        Ok(()) => log::info!("remote: SSH config refreshed for {} phone(s)", c.phones().len()),
        Err(e) => log::warn!("remote: could not refresh the SSH config: {e:#}"),
    }
}

#[tauri::command]
pub fn remote_phones() -> Result<Vec<PhoneView>, String> {
    Ok(computer()?.phones().iter().map(PhoneView::from).collect())
}

#[tauri::command]
pub async fn remote_pair_code(code: String) -> Result<PhoneView, String> {
    let c = computer()?;
    let ssh_key = c.ssh_public_key().map_err(|e| e.to_string())?;
    let client = client().await?;
    log::info!("remote: pairing by code");
    let paired = client
        .pair(&code, &computer::computer_name(), computer::os_name(), &ssh_key)
        .await
        .map_err(|e| e.to_string())?;
    let phone = c.add_phone(&paired, "code", &proxy_command()).map_err(|e| e.to_string())?;
    log::info!("remote: paired with {} as `{}`", phone.name, phone.alias);
    Ok(PhoneView::from(&phone))
}

#[tauri::command]
pub async fn remote_forget(app: AppHandle, id: String, serial: Option<String>) -> Result<(), String> {
    computer()?.remove_phone(&id, &proxy_command()).map_err(|e| e.to_string())?;
    // With the cable in, the phone forgets this computer too — the one case
    // where "forget" can be made to mean both ends without asking anyone to
    // pick the phone up.
    if let Some(serial) = serial {
        let key = computer()?.endpoint_key().map_err(|e| e.to_string())?;
        let arg = serde_json::json!({ "id": key.public().to_string() });
        if let Err(e) = tauri::async_runtime::spawn_blocking(move || content_call(&app, &serial, "forget", &arg))
            .await
            .map_err(|e| e.to_string())?
        {
            log::warn!("remote: the phone could not forget this computer over USB: {e}");
        }
    }
    Ok(())
}

#[tauri::command]
pub async fn remote_probe(id: String) -> Result<ProbeView, String> {
    let phone: EndpointId = id.parse().map_err(|_| "not a phone id".to_string())?;
    let client = client().await?;
    Ok(match client.probe(phone).await {
        Ok(p) => ProbeView {
            reachable: true,
            direct: p.direct,
            rtt_ms: p.rtt_ms,
            ssh: p.status.ssh,
            sessions: p.status.sessions,
            error: None,
        },
        Err(e) => ProbeView {
            reachable: false,
            direct: false,
            rtt_ms: 0,
            ssh: String::new(),
            sessions: 0,
            error: Some(e.to_string()),
        },
    })
}

// ── over the cable ─────────────────────────────────────────────────────────

/// What the launcher on a plugged-in phone says about remote access.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UsbStatus {
    pub ok: bool,
    pub available: bool,
    pub enabled: bool,
    pub name: String,
    /// ready, installing, missing, failed or ssh-failed.
    pub linux: String,
    pub id: String,
    /// This computer is already in the phone's list, and the phone in ours.
    pub paired_here: bool,
}

/// `content call` the launcher's RemoteProvider (see dexremote::usb).
fn content_call(app: &AppHandle, serial: &str, method: &str, arg: &serde_json::Value) -> Result<PhoneAnswer, String> {
    let mut args: Vec<String> = vec!["-s".into(), serial.into(), "shell".into()];
    args.extend(usb::shell_args(method, arg));
    let refs: Vec<&str> = args.iter().map(String::as_str).collect();
    let out = adb::run_adb_timeout(app, &refs, Duration::from_secs(20))?;
    usb::parse_answer(&out)
}

fn usb_status_blocking(app: &AppHandle, serial: &str) -> Result<UsbStatus, String> {
    let answer = content_call(app, serial, "status", &serde_json::json!({}))?;
    let me = computer()?.endpoint_key().map_err(|e| e.to_string())?.public().to_string();
    let ours = computer()?.phones().iter().any(|p| p.id == answer.id);
    Ok(UsbStatus {
        ok: answer.ok,
        available: answer.available,
        enabled: answer.enabled,
        paired_here: ours && answer.peers.iter().any(|p| *p == me),
        name: answer.name,
        linux: answer.linux,
        id: answer.id,
    })
}

#[tauri::command]
pub async fn remote_usb_status(app: AppHandle, serial: String) -> Result<UsbStatus, String> {
    tauri::async_runtime::spawn_blocking(move || usb_status_blocking(&app, &serial))
        .await
        .map_err(|e| e.to_string())?
}

/// Pair with the phone on the cable, and (with `enable`) switch remote access
/// on there in the same step.
#[tauri::command]
pub async fn remote_pair_usb(app: AppHandle, serial: String, enable: bool) -> Result<PhoneView, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let c = computer()?;
        let key = c.endpoint_key().map_err(|e| e.to_string())?;
        let ssh_key = c.ssh_public_key().map_err(|e| e.to_string())?;
        let request = serde_json::json!({
            "id": key.public().to_string(),
            "name": computer::computer_name(),
            "os": computer::os_name(),
            "ssh": ssh_key,
            "enable": enable,
        });
        let answer = content_call(&app, &serial, "pair", &request)?;
        let paired = answer.paired()?;
        let phone = c.add_phone(&paired, "usb", &proxy_command()).map_err(|e| e.to_string())?;
        log::info!("remote: paired over USB with {} as `{}`", phone.name, phone.alias);
        let mut view = PhoneView::from(&phone);
        view.note = answer.caveat().map(str::to_string);
        if let Some(note) = &view.note {
            log::info!("remote: {note}");
        }
        Ok(view)
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Is remote access switched on on this phone? Asked by the end-of-session
/// clean-up, which must not take away the battery exemption remote access
/// lives on. Any failure reads as "no", which is what the clean-up did before.
pub fn phone_remote_enabled(app: &AppHandle, serial: &str) -> bool {
    content_call(app, serial, "status", &serde_json::json!({}))
        .map(|a| a.enabled)
        .unwrap_or(false)
}

// ── opening things ─────────────────────────────────────────────────────────

fn find(id: &str) -> Result<Phone, String> {
    computer()?
        .phones()
        .into_iter()
        .find(|p| p.id == id)
        .ok_or_else(|| "that phone is not paired with this computer".to_string())
}

/// A terminal window running `ssh <alias>`, in whatever terminal this host has.
#[tauri::command]
pub fn remote_open_terminal(id: String) -> Result<(), String> {
    let phone = find(&id)?;
    let alias = phone.alias;
    #[cfg(windows)]
    {
        // Windows Terminal when it is there, the classic console otherwise.
        // `start` gives ssh a window of its own; cmd itself is hidden.
        let mut wt = std::process::Command::new("wt.exe");
        wt.args(["new-tab", "--title", &alias, "ssh", &alias]);
        if wt.spawn().is_ok() {
            return Ok(());
        }
        let mut cmd = std::process::Command::new("cmd.exe");
        cmd.args(["/c", "start", "", "ssh", &alias]);
        adb::hide_console(&mut cmd);
        cmd.spawn().map(|_| ()).map_err(|e| e.to_string())
    }
    #[cfg(target_os = "macos")]
    {
        let script = format!(
            "tell application \"Terminal\"\n  activate\n  do script \"ssh {alias}\"\nend tell"
        );
        std::process::Command::new("osascript")
            .args(["-e", &script])
            .spawn()
            .map(|_| ())
            .map_err(|e| e.to_string())
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        let attempts: [(&str, Vec<&str>); 5] = [
            ("x-terminal-emulator", vec!["-e", "ssh", &alias]),
            ("gnome-terminal", vec!["--", "ssh", &alias]),
            ("konsole", vec!["-e", "ssh", &alias]),
            ("xfce4-terminal", vec!["-x", "ssh", &alias]),
            ("xterm", vec!["-e", "ssh", &alias]),
        ];
        for (bin, args) in attempts {
            if std::process::Command::new(bin).args(&args).spawn().is_ok() {
                return Ok(());
            }
        }
        Err(format!("No terminal was found. Run `ssh {alias}` in yours."))
    }
}

/// VS Code, connected to the phone through Remote-SSH, in the guest's home.
#[tauri::command]
pub fn remote_open_vscode(app: AppHandle, id: String) -> Result<(), String> {
    use tauri_plugin_opener::OpenerExt;
    let phone = find(&id)?;
    let home = if phone.user == "root" { "/root".to_string() } else { format!("/home/{}", phone.user) };
    let url = format!("vscode://vscode-remote/ssh-remote+{}{}", phone.alias, home);
    app.opener().open_url(url, None::<&str>).map_err(|e| e.to_string())
}

// ── the ProxyCommand ───────────────────────────────────────────────────────

/// `open-android-dex ssh-proxy <phone-id>`: what each phone's `Host` block runs.
///
/// Handled before Tauri is touched — no window, no single-instance check, no
/// log plugin — and the process exits as soon as the connection does: stdin is
/// read on a thread that a returning `main` would otherwise wait on. stdout is
/// the SSH connection itself, so nothing may print to it; the one sentence a
/// failure earns goes to stderr, which ssh shows.
pub fn run_cli() -> Option<i32> {
    let mut args = std::env::args().skip(1);
    if args.next().as_deref() != Some("ssh-proxy") {
        return None;
    }
    let Some(id) = args.next() else {
        eprintln!("Open Android DeX: usage: ssh-proxy <phone id>");
        return Some(2);
    };
    dexremote::pc::ignore_hangup();
    let run = || -> Result<(), String> {
        let phone: EndpointId = id.parse().map_err(|_| format!("{id} is not a phone id"))?;
        let key = computer()?.endpoint_key().map_err(|e| e.to_string())?;
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .map_err(|e| e.to_string())?;
        rt.block_on(async {
            let client = Client::bind(key).await.map_err(|e| e.to_string())?;
            let result = client.proxy_stdio(phone).await.map_err(|e| e.to_string());
            client.close().await;
            result
        })
    };
    Some(match run() {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("Open Android DeX: {e}");
            255
        }
    })
}
