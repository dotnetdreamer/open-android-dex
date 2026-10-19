//! Pairing over adb: what the launcher's RemoteProvider expects and answers.
//!
//! adb has already proved which phone is on the other end of the cable (or of
//! a wireless-debugging session), and a person plugged it in, so this pairing
//! needs no code. One `content call` carries this computer's identity in and
//! the phone's back out. Shared by the desktop app (remote.rs) and `dexremote
//! pair-adb`, so the two cannot drift apart on the format.

use serde::{Deserialize, Serialize};

use crate::pc::Paired;

/// RemoteProvider's authority, in the desktop launcher.
pub const PROVIDER_URI: &str = "content://com.ccrstech.openandroiddex.launcher.remote";

/// What the phone says, for `status` and after `pair`.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PhoneAnswer {
    pub ok: bool,
    /// The phone can run Linux, so it can do remote access.
    pub available: bool,
    pub enabled: bool,
    pub name: String,
    pub user: String,
    /// ready, installing, missing, failed (the setup stopped; a retry in the
    /// Linux window helps), or ssh-failed (openssh-server would not install;
    /// switching remote access off and on retries). Waiting helps neither.
    pub linux: String,
    pub id: String,
    pub host_key: String,
    /// The computers it trusts, by id.
    pub peers: Vec<String>,
    /// After a `pair` that switched remote access on: whether Android let it
    /// start. False means it is on and waiting — Android refuses a background
    /// app a service unless an exemption applies — and the phone shows a
    /// notification that starts it with one tap. Absent when nothing was
    /// switched on.
    pub started: Option<bool>,
    pub error: Option<String>,
}

impl PhoneAnswer {
    /// What to tell the person at the computer about the phone's side, after
    /// pairing — or nothing when it is ready to log in to.
    pub fn caveat(&self) -> Option<&'static str> {
        if self.started == Some(false) {
            return Some(
                "Remote access is on, but Android is holding it until the phone is next used: \
                 unlock it and tap \u{201c}Remote access is paused\u{201d}, or open Linux once.",
            );
        }
        match self.linux.as_str() {
            "missing" => Some("Linux is not installed on it yet. SSH starts working once it is."),
            "installing" => Some("Linux on it is still being set up. SSH starts working when it finishes."),
            "failed" => Some("Linux on the phone did not finish setting up. Open Linux there to try again."),
            "ssh-failed" => Some(
                "The SSH server could not be installed in the phone's Linux. Check the phone's \
                 connection, then switch Remote access off and on there to try again.",
            ),
            _ => None,
        }
    }
}

impl PhoneAnswer {
    /// The phone as a pairing result, once its answer has been checked.
    pub fn paired(&self) -> Result<Paired, String> {
        if !self.ok {
            return Err(if !self.available {
                "This phone cannot run Linux, so it has no remote access (it needs 64-bit Android).".into()
            } else {
                "The phone did not accept this computer.".into()
            });
        }
        let id = self.id.parse().map_err(|_| "the phone sent no id".to_string())?;
        if crate::sshkey::parse_public_line(&self.host_key).is_none() {
            return Err("the phone sent no usable host key".into());
        }
        Ok(Paired {
            id,
            name: if self.name.is_empty() { "Phone".into() } else { self.name.clone() },
            user: if self.user.is_empty() { crate::phone::GUEST_USER.into() } else { self.user.clone() },
            host_key: self.host_key.clone(),
        })
    }
}

/// The `adb shell` arguments for one call: `content call --uri … --method M --arg A`.
/// The argument is base64url so no shell on the way can split or expand it.
pub fn shell_args(method: &str, arg: &serde_json::Value) -> Vec<String> {
    [
        "content",
        "call",
        "--uri",
        PROVIDER_URI,
        "--method",
        method,
        "--arg",
        &crate::b64url(&arg.to_string()),
    ]
    .iter()
    .map(|s| s.to_string())
    .collect()
}

/// The provider's answer out of what `content call` printed.
///
/// That is a Bundle's toString — `Result: Bundle[{result=…}]` — which is why
/// the provider sends base64url: it has none of the commas and braces a
/// Bundle uses to delimit itself.
pub fn parse_answer(printed: &str) -> Result<PhoneAnswer, String> {
    let start = match printed.find("result=") {
        Some(i) => i + "result=".len(),
        None => {
            return Err(
                if printed.contains("Could not find provider") || printed.contains("Unknown authority") {
                    "The launcher on this phone predates remote access. Start the desktop once to update it.".into()
                } else {
                    format!("unexpected answer from the phone: {}", printed.trim())
                },
            )
        }
    };
    let value: String = printed[start..]
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
        .collect();
    let json = crate::unb64url(&value).ok_or("the phone's answer could not be read")?;
    serde_json::from_str(&json).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_what_the_content_tool_prints() {
        let answer = r#"{"ok":true,"available":true,"enabled":true,"name":"Galaxy S25","user":"root","linux":"ready","id":"ab","hostKey":"k","peers":["x"]}"#;
        let printed = format!("Result: Bundle[{{result={}}}]\n", crate::b64url(answer));
        let a = parse_answer(&printed).unwrap();
        assert!(a.ok && a.enabled);
        assert_eq!(a.name, "Galaxy S25");
        assert_eq!(a.peers, vec!["x".to_string()]);
        assert_eq!(a.started, None, "absent when nothing was switched on");
        assert!(a.caveat().is_none());
        assert!(parse_answer("Error: Could not find provider: x").unwrap_err().contains("predates"));

        let held = r#"{"ok":true,"enabled":true,"linux":"ready","started":false}"#;
        let a = parse_answer(&format!("Result: Bundle[{{result={}}}]", crate::b64url(held))).unwrap();
        assert!(a.caveat().unwrap().contains("paused"));
    }

    #[test]
    fn the_argument_survives_a_shell() {
        let args = shell_args("pair", &serde_json::json!({"name": "My PC; rm -rf / $(x)"}));
        let arg = args.last().unwrap();
        assert!(arg.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
    }
}
