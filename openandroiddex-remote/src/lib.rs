//! Remote access to the phone's Linux: `ssh <phone>` from any computer, on any
//! network, with nothing opened on any router and no account anywhere — and
//! its XFCE desktop, served on the computer's loopback ([`pc::Desktop`]).
//!
//! The phone runs Ubuntu under proot with an sshd on loopback. Reaching that
//! sshd from a computer somewhere else is the whole problem — the phone may be
//! on mobile data behind carrier-grade NAT, the computer on a hotel's Wi-Fi,
//! and neither has an address the other can open. iroh solves exactly that:
//! each side is addressed by an Ed25519 key, connections are QUIC and end-to-end
//! encrypted, they start through a relay and move to a direct path as soon as
//! hole punching finds one (most of the time), and they follow either side
//! across a change of network.
//!
//! Map of the crate:
//!
//! * [`phone`] — the daemon on the phone (`dexremote serve`).
//! * [`pc`] — dialling a phone from a computer, and pairing with one.
//! * [`computer`] — the computer's files: identity, phones, `~/.ssh` setup.
//! * [`pairing`] — turning an eight-character code into a meeting place.
//! * [`proto`] — the ALPNs and the handful of messages.
//! * [`pipe`] — joining a QUIC stream to a socket or to stdio.
//! * [`sftp`] — files onto the phone's Linux, over SFTP in the same tunnel
//!   (feature `sftp`; a computer's build only).
//! * [`outbox`] — and files back: Send To in the guest, fetched into Downloads.
//! * [`sshkey`] — OpenSSH key files, written and read without `ssh-keygen`.
//! * [`usb`] — pairing over adb, with the launcher's RemoteProvider.
//! * [`crypto`] — HMAC and PBKDF2 over the tree's own SHA-256.
//!
//! The design record, with what was measured and why it is built this way,
//! is `doc/remote-access.md`.

pub mod computer;
pub mod crypto;
#[cfg(feature = "sftp")]
pub mod outbox;
pub mod pairing;
pub mod pc;
pub mod phone;
pub mod pipe;
pub mod proto;
#[cfg(feature = "sftp")]
pub mod sftp;
pub mod sshkey;
pub mod usb;

/// Re-exported so the desktop app names iroh's types through this crate and
/// can never end up linking a second, differently-featured copy of it.
pub use iroh;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;

/// Text that may contain spaces, as one token on a line protocol.
pub fn b64url(s: &str) -> String {
    URL_SAFE_NO_PAD.encode(s.as_bytes())
}

/// The inverse of [`b64url`].
pub fn unb64url(s: &str) -> Option<String> {
    URL_SAFE_NO_PAD
        .decode(s.as_bytes())
        .ok()
        .and_then(|b| String::from_utf8(b).ok())
}

/// `DEXREMOTE_RELAY_ONLY=1`: never send a packet straight to the other side,
/// only through the relay. A diagnostic first — it is how to tell "the relay
/// path is broken" from "hole punching is" — and a privacy setting second,
/// since a direct path shows each side the other's public IP.
pub fn relay_only() -> bool {
    std::env::var("DEXREMOTE_RELAY_ONLY").is_ok_and(|v| v == "1")
}

pub fn unix_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Log to stderr through `tracing`, which is what iroh speaks. `default` is a
/// filter like `warn,dexremote=info`; `DEXREMOTE_LOG` overrides it.
pub fn init_logging(default: &str) {
    use std::str::FromStr;
    use tracing_subscriber::filter::Targets;
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::util::SubscriberInitExt;

    let spec = std::env::var("DEXREMOTE_LOG").unwrap_or_else(|_| default.to_string());
    let filter = Targets::from_str(&spec).unwrap_or_else(|_| Targets::from_str("warn").expect("valid"));
    let _ = tracing_subscriber::registry()
        .with(
            tracing_subscriber::fmt::layer()
                .with_writer(std::io::stderr)
                .with_ansi(false)
                .with_target(false),
        )
        .with(filter)
        .try_init();
}

#[cfg(test)]
mod tests {
    #[test]
    fn b64url_round_trips_and_has_no_spaces() {
        let s = "Galaxy S25 Ultra — Idrees's";
        let e = super::b64url(s);
        assert!(!e.contains(' ') && !e.contains('='));
        assert_eq!(super::unb64url(&e).as_deref(), Some(s));
        assert_eq!(super::unb64url("!!"), None);
    }
}
