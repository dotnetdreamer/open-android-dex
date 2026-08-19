//! Windows "Project to this PC" — the Miracast escape hatch.
//!
//! **Windows only.** macOS has no Miracast receiver, and cannot have one: the
//! protocol rides Wi-Fi Direct, which Apple's Wi-Fi stack does not expose to
//! third parties, and AirPlay — the thing a Mac *can* receive — is not a
//! protocol Samsung DeX can send. There is no adapter to write here, so on
//! macOS every command in this module reports the route as unavailable and the
//! UI hides the tab entirely (see `hostPlatform` in App.tsx). The adb route,
//! which is the whole rest of this app, is unaffected.
//!
//! This is the one route in the app that does **not** end in a device this
//! app drives. Samsung phones can cast DeX straight to Windows' own Wireless
//! Display receiver over Miracast, with no adb, no cable and no software on
//! either side beyond what Windows and One UI already ship.
//!
//! What arrives is *Samsung's* DeX shell, drawn by Microsoft's receiver app.
//! Our launcher, taskbar, window daemon, file drop and caption bar are not
//! involved, because we cannot host the stream: `MiracastReceiver` is a WinRT
//! sink API that reports `MiracastNotSupported` when called from a Win32
//! process, and Microsoft's position is that using it from a desktop app is
//! out of support. The alternative native path (`WFDDisplaySink`) is
//! end-of-support. So there is nothing to embed and nothing to control — only
//! a signpost worth putting in front of someone whose ADB path is broken.
//!
//! Accordingly this module does two small things: says whether the hardware
//! could do it at all, and opens the two Settings pages that have to be
//! visited. The steps themselves live in the UI.

use serde::Serialize;
#[cfg(windows)]
use std::process::{Command, Stdio};

/// Whether this PC's Wi-Fi stack can do Miracast at all.
///
/// Worth checking because it is the one part the user cannot fix from a
/// settings page: a desktop with no wireless adapter, or an adapter whose
/// driver has no Wi-Fi Direct support, will never appear on the phone however
/// many toggles get turned on. Everything else is a switch, so it is a step
/// rather than a check.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProjectionSupport {
    /// `Some(false)` is a definite no, `None` means the answer could not be
    /// read — a localised Windows prints this line in its own language, and
    /// guessing "unsupported" from a failed string match would talk people out
    /// of a route that works.
    pub miracast: Option<bool>,
    pub detail: String,
}

/// What every command here answers on a host that has no Miracast receiver.
///
/// `Some(false)` rather than `None`: this is a definite no, not a reading that
/// failed. `None` is reserved for a Windows box whose localised `netsh` output
/// could not be parsed, where talking the user out of the route would be wrong.
#[cfg(not(windows))]
const NO_MIRACAST: &str =
    "Miracast is a Windows feature. macOS has no wireless-display receiver a phone can \
     cast DeX to — use the USB cable or Wi-Fi debugging instead.";

/// `netsh wlan show driver`, which reports Miracast support on the line
/// "Wireless Display Supported: Yes (Graphics Driver: Yes, Wi-Fi Driver: Yes)".
#[cfg(windows)]
#[tauri::command(async)]
pub fn projection_support() -> Result<ProjectionSupport, String> {
    let mut cmd = Command::new("netsh");
    cmd.args(["wlan", "show", "driver"])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    crate::adb::hide_console(&mut cmd);

    let out = cmd.output().map_err(|e| format!("could not run netsh: {e}"))?;
    let text = String::from_utf8_lossy(&out.stdout);

    let line = text
        .lines()
        .find(|l| l.contains("Wireless Display Supported"))
        .map(str::trim);

    let (miracast, detail) = match line {
        Some(l) => {
            let answer = l.split_once(':').map(|(_, v)| v.trim()).unwrap_or_default();
            let yes = answer.starts_with("Yes");
            (Some(yes), l.to_string())
        }
        None => {
            // No wireless interface at all is the common case here, and netsh
            // says so on stderr in the system language.
            let hint = String::from_utf8_lossy(&out.stderr);
            let hint = hint.trim();
            (
                None,
                if hint.is_empty() {
                    "This PC's Wi-Fi driver did not report whether it supports Miracast."
                        .to_string()
                } else {
                    hint.to_string()
                },
            )
        }
    };
    log::info!("projection: miracast={miracast:?} — {detail}");
    Ok(ProjectionSupport { miracast, detail })
}

#[cfg(not(windows))]
#[tauri::command(async)]
pub fn projection_support() -> Result<ProjectionSupport, String> {
    Ok(ProjectionSupport {
        miracast: Some(false),
        detail: NO_MIRACAST.to_string(),
    })
}

/// The Features-on-Demand package behind "Project to this PC".
///
/// Four tildes, and the version suffix matters — DISM matches the whole string.
pub const RECEIVER_CAPABILITY: &str = "App.WirelessDisplay.Connect~~~~0.0.1.0";

/// The command that installs it, for the user to copy and run themselves.
pub fn install_command() -> String {
    format!("DISM /Online /Add-Capability /CapabilityName:{RECEIVER_CAPABILITY}")
}

#[tauri::command(async)]
pub fn projection_install_command() -> String {
    install_command()
}

/// Install the Wireless Display receiver.
///
/// This exists because the obvious route is broken: on Windows 11 24H2 and
/// 25H2 "Wireless Display" is frequently **missing from the Optional features
/// list entirely**, so telling someone to search for it there sends them
/// looking for something that is not on screen. The capability is still
/// present and installable — just only by name, through DISM.
///
/// DISM needs administrator rights, so this goes through `Start-Process
/// -Verb RunAs` and the user approves the UAC prompt. It is deliberately not
/// waited on: the install takes minutes, and DISM's own console window is
/// better progress than anything this app could show. A restart is needed
/// afterwards before the receiver appears.
#[cfg(windows)]
#[tauri::command(async)]
pub fn projection_install_receiver() -> Result<(), String> {
    log::info!("projection: requesting elevation to add {RECEIVER_CAPABILITY}");
    let script = format!(
        "Start-Process -FilePath dism.exe -ArgumentList \
         '/Online','/Add-Capability','/CapabilityName:{RECEIVER_CAPABILITY}' -Verb RunAs"
    );
    let mut cmd = Command::new("powershell");
    cmd.args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped());
    crate::adb::hide_console(&mut cmd);

    let out = cmd
        .output()
        .map_err(|e| format!("could not start the installer: {e}"))?;
    if out.status.success() {
        return Ok(());
    }
    // The usual failure is the user declining the UAC prompt, which PowerShell
    // reports as a cancelled operation rather than anything actionable.
    let err = String::from_utf8_lossy(&out.stderr);
    let err = err.trim();
    log::warn!("projection: the install could not be started — {err}");
    Err(if err.contains("cancelled") || err.contains("canceled") {
        "The administrator prompt was declined, so nothing was installed.".to_string()
    } else if err.is_empty() {
        "The installer could not be started.".to_string()
    } else {
        err.to_string()
    })
}

#[cfg(not(windows))]
#[tauri::command(async)]
pub fn projection_install_receiver() -> Result<(), String> {
    Err(NO_MIRACAST.to_string())
}

/// Open one of the two Settings pages this needs.
///
/// Restricted to a known list rather than taking a URI: this is called from
/// the webview, and handing arbitrary strings to the shell's protocol handler
/// from there is not a thing worth being able to do.
#[cfg(windows)]
#[tauri::command(async)]
pub fn projection_open_settings(page: String) -> Result<(), String> {
    let uri = match page.as_str() {
        "project" => "ms-settings:project",
        "features" => "ms-settings:optionalfeatures",
        other => return Err(format!("unknown settings page: {other}")),
    };
    log::info!("projection: opening {uri}");
    tauri_plugin_opener::open_url(uri, None::<&str>).map_err(|e| e.to_string())
}

/// The `ms-settings:` scheme has no counterpart outside Windows, and the two
/// pages it opens describe features that do not exist here. Still validates the
/// page name, so a caller that reaches this by mistake gets the same "unknown
/// page" answer it would get on Windows rather than a confusing one.
#[cfg(not(windows))]
#[tauri::command(async)]
pub fn projection_open_settings(page: String) -> Result<(), String> {
    match page.as_str() {
        "project" | "features" => Err(NO_MIRACAST.to_string()),
        other => Err(format!("unknown settings page: {other}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Verbatim from `netsh wlan show driver` on the dev machine.
    #[test]
    fn reads_the_netsh_line() {
        let line = "    Wireless Display Supported: Yes (Graphics Driver: Yes, Wi-Fi Driver: Yes)";
        let answer = line
            .trim()
            .split_once(':')
            .map(|(_, v)| v.trim())
            .unwrap_or_default();
        assert!(answer.starts_with("Yes"));

        let no = "    Wireless Display Supported: No (Graphics Driver: Yes, Wi-Fi Driver: No)";
        let answer = no
            .trim()
            .split_once(':')
            .map(|(_, v)| v.trim())
            .unwrap_or_default();
        assert!(!answer.starts_with("Yes"));
    }

    #[test]
    fn the_capability_name_is_exact() {
        // Four tildes and the version suffix — DISM matches the whole string,
        // and a near-miss fails with a capability-not-found that reads like
        // the feature does not exist on this build.
        assert_eq!(RECEIVER_CAPABILITY, "App.WirelessDisplay.Connect~~~~0.0.1.0");
        assert_eq!(RECEIVER_CAPABILITY.matches('~').count(), 4);
        assert_eq!(
            install_command(),
            "DISM /Online /Add-Capability /CapabilityName:App.WirelessDisplay.Connect~~~~0.0.1.0"
        );
    }

    #[test]
    fn rejects_unknown_settings_pages() {
        assert!(projection_open_settings("about:blank".into()).is_err());
        assert!(projection_open_settings("../../evil".into()).is_err());
    }
}
