//! What the two ends say to each other.
//!
//! Four protocols, told apart by ALPN, so each kind of connection is refused
//! or routed before a byte of application data is read:
//!
//! | ALPN | on | from | what |
//! |---|---|---|---|
//! | [`ALPN_SSH`] | the phone's permanent endpoint | paired computers only | one bi-stream per SSH connection, spliced onto the guest's sshd |
//! | [`ALPN_CTL`] | the phone's permanent endpoint | paired computers only | one request, one answer: is the phone's Linux there, and how is it |
//! | [`ALPN_DESKTOP`] | the phone's permanent endpoint | paired computers only | the XFCE desktop: an `open` stream to start it (or ask after it), one bi-stream per connection to its web viewer, and a `ping` every few seconds |
//! | [`ALPN_PAIR`] | the short-lived pairing endpoint | whoever knows the code | the exchange in [`crate::pairing`] |
//!
//! Every handshake is a few JSON frames (u32 big-endian length, then UTF-8),
//! after which an SSH stream carries raw bytes in both directions. JSON rather
//! than anything tighter because each message is sent once per connection, and
//! being able to read a capture by eye has been worth more on this project than
//! the bytes would have been.

use serde::{de::DeserializeOwned, Deserialize, Serialize};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const ALPN_SSH: &[u8] = b"/openandroiddex/ssh/1";
pub const ALPN_CTL: &[u8] = b"/openandroiddex/ctl/1";
pub const ALPN_DESKTOP: &[u8] = b"/openandroiddex/desktop/1";
pub const ALPN_PAIR: &[u8] = b"/openandroiddex/pair/1";

/// In [`CtlStatus::features`]: the phone answers [`ALPN_DESKTOP`].
pub const FEATURE_DESKTOP: &str = "desktop";

/// Close codes, so the side that was turned away can say why.
pub const CLOSE_NOT_PAIRED: u32 = 403;
pub const CLOSE_UNPAIRED: u32 = 410;
pub const CLOSE_SHUTDOWN: u32 = 0;

/// No handshake frame is anywhere near this; a peer that claims otherwise is
/// not one of ours.
pub const MAX_FRAME: usize = 64 * 1024;

pub async fn write_frame<W, T>(w: &mut W, msg: &T) -> std::io::Result<()>
where
    W: AsyncWrite + Unpin,
    T: Serialize,
{
    let body = serde_json::to_vec(msg).map_err(std::io::Error::other)?;
    if body.len() > MAX_FRAME {
        return Err(std::io::Error::other("frame too large"));
    }
    w.write_all(&(body.len() as u32).to_be_bytes()).await?;
    w.write_all(&body).await?;
    w.flush().await
}

pub async fn read_frame<R, T>(r: &mut R) -> std::io::Result<T>
where
    R: AsyncRead + Unpin,
    T: DeserializeOwned,
{
    let mut len = [0u8; 4];
    r.read_exact(&mut len).await?;
    let len = u32::from_be_bytes(len) as usize;
    if len > MAX_FRAME {
        return Err(std::io::Error::other("frame too large"));
    }
    let mut body = vec![0u8; len];
    r.read_exact(&mut body).await?;
    serde_json::from_slice(&body).map_err(std::io::Error::other)
}

/// Which paired computer a connection speaks for, and the proof.
///
/// A computer never puts its own key on the network for long: every ssh, scp
/// and VS Code window runs a ProxyCommand of its own, and iroh allows each
/// endpoint id to be live in one place at a time — relays route to the newest
/// connection for an id, so two processes sharing the computer's key would
/// keep knocking each other off the relay. So each process binds a throwaway
/// key, and the computer's long-term key signs that throwaway id.
///
/// The signature binds the ENDPOINT, whose secret key the QUIC handshake has
/// already proven, so a delegation is useless to anyone who copies it: using
/// it would take the throwaway private key, which never leaves the process.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Auth {
    /// The paired computer's id, hex.
    pub computer: String,
    /// Hex Ed25519 signature by that computer over [`delegation_message`].
    pub sig: String,
}

/// What a computer signs to let one of its endpoints speak for it.
pub fn delegation_message(endpoint: &iroh::EndpointId) -> Vec<u8> {
    let mut msg = b"oadx-delegation-v1".to_vec();
    msg.extend_from_slice(endpoint.as_bytes());
    msg
}

/// First frame on an SSH stream, computer → phone.
#[derive(Debug, Serialize, Deserialize)]
pub struct SshHello {
    pub v: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub auth: Option<Auth>,
}

/// Why a phone could not put a stream through to its SSH server. The computer
/// turns these into sentences; see `pc::explain`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Refusal {
    /// There is no Linux on the phone — never installed, or uninstalled.
    LinuxMissing,
    /// Linux is still being installed or updated; the SSH server is not in yet.
    LinuxInstalling,
    /// The phone tried to start its SSH server and it did not come up.
    SshFailed,
    /// Remote access was switched off on the phone while this was in flight.
    Disabled,
    /// Linux's setup stopped with an error. SSH may still get in; the desktop
    /// does not start until the setup is finished on the phone.
    LinuxUnfinished,
    /// The desktop is not running, and this request did not ask for it to start.
    DesktopStopped,
    /// The phone asked for its desktop and it did not come up.
    DesktopFailed,
}

/// The phone's answer to [`SshHello`]. After `ok: true`, raw bytes.
#[derive(Debug, Serialize, Deserialize)]
pub struct SshReady {
    pub ok: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub refusal: Option<Refusal>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CtlRequest {
    pub v: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub auth: Option<Auth>,
}

/// One snapshot of a phone, for the computer's list of paired phones.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CtlStatus {
    /// The phone's own name for itself, e.g. "Galaxy S25 Ultra".
    pub name: String,
    /// The guest's SSH server: up, starting, down, missing, installing.
    pub ssh: String,
    /// SSH streams and desktop connections currently open, from every computer.
    pub sessions: u32,
    /// This crate's version on the phone.
    pub version: String,
    /// What the phone answers besides SSH ([`FEATURE_DESKTOP`]). A phone from
    /// before the field leaves it out, and that is the answer: nothing.
    #[serde(default)]
    pub features: Vec<String>,
    /// The desktop, as the phone's app last described it: running, starting,
    /// stopped, failed, unfinished, installing or missing. Empty when the
    /// phone cannot open its desktop to a computer.
    #[serde(default)]
    pub desktop: String,
}

/// What a desktop stream is for.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum DesktopOp {
    /// Is the desktop up, and how does one log in to it? With
    /// [`DesktopHello::start`], bring it up first if it is not running.
    Open,
    /// After the answer, raw bytes to the desktop's web viewer: one HTTP
    /// request, or the VNC WebSocket.
    Web,
    /// Is this connection still answered? Nothing more. A viewer asks every
    /// few seconds, because a phone whose daemon was killed and started
    /// again is back at the same address, and nothing tells a computer that
    /// its connection to the old one has died.
    Ping,
}

/// A desktop's size in pixels, which for a computer's window is its screen's
/// size in logical pixels — the size XFCE is laid out at.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct DesktopSize {
    pub w: u32,
    pub h: u32,
}

/// First frame on a desktop stream, computer → phone.
#[derive(Debug, Serialize, Deserialize)]
pub struct DesktopHello {
    pub v: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub auth: Option<Auth>,
    pub op: DesktopOp,
    /// With [`DesktopOp::Open`]: start the desktop at this size if it is not
    /// running. A desktop that is running keeps its size — and its windows.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub start: Option<DesktopSize>,
}

/// The phone's answer on a desktop stream. An `open` may be answered
/// `starting` first, any number of times, while the phone brings the desktop
/// up; every stream ends with exactly one frame that is `ok` or carries a
/// refusal. After a `web` stream's `ok`, raw bytes.
#[derive(Debug, Default, Serialize, Deserialize)]
pub struct DesktopReply {
    #[serde(default)]
    pub ok: bool,
    /// Not an answer yet: the desktop is on its way up. More frames follow.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub starting: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub refusal: Option<Refusal>,
    /// An `open`'s answer: the VNC password, so nobody has to type it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub password: Option<String>,
    /// An `open`'s answer: the size the desktop is running at.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub size: Option<DesktopSize>,
}

impl DesktopReply {
    pub fn refused(refusal: Refusal) -> Self {
        DesktopReply { refusal: Some(refusal), ..Default::default() }
    }
}

/// Computer → phone, on the pairing endpoint.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairRequest {
    pub v: u32,
    /// How the phone will list this computer: its host name.
    pub name: String,
    /// windows, macos or linux. Picks an icon; nothing depends on it.
    pub os: String,
    /// The computer's SSH client key, one `ssh-ed25519 …` line.
    pub ssh_key: String,
    /// Hex [`crate::pairing::PairKeys::computer_proof`].
    pub proof: String,
    /// The computer behind this (throwaway) endpoint. Absent when it dialled
    /// with its own key, in which case the connection's id is the computer.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub auth: Option<Auth>,
}

/// Why a pairing did not happen, for the computer to say.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PairError {
    /// The proof did not verify: a different code, or a mistyped one.
    BadCode,
    /// The person at the phone said no, or the phone could not save the pairing.
    Rejected,
    /// The request was malformed.
    Invalid,
    /// The code's time ran out — by the wall clock, which is the one the
    /// phone's screen counts down.
    Expired,
}

/// Phone → computer, on the pairing endpoint.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairResponse {
    pub ok: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<PairError>,
    /// The phone's permanent endpoint id, hex.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// The login name inside the guest.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user: Option<String>,
    /// The guest sshd's host key, one `ssh-ed25519 …` line.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub host_key: Option<String>,
    /// Hex [`crate::pairing::PairKeys::phone_proof`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub proof: Option<String>,
}

impl PairResponse {
    pub fn refused(error: PairError) -> Self {
        PairResponse {
            ok: false,
            error: Some(error),
            id: None,
            name: None,
            user: None,
            host_key: None,
            proof: None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn frames_round_trip_and_refuse_oversize() {
        let (mut a, mut b) = tokio::io::duplex(1 << 20);
        write_frame(&mut a, &SshReady { ok: false, refusal: Some(Refusal::LinuxInstalling) })
            .await
            .unwrap();
        let got: SshReady = read_frame(&mut b).await.unwrap();
        assert!(!got.ok);
        assert_eq!(got.refusal, Some(Refusal::LinuxInstalling));

        // A length prefix past the cap is refused before anything is allocated.
        a.write_all(&((MAX_FRAME as u32) + 1).to_be_bytes()).await.unwrap();
        assert!(read_frame::<_, SshReady>(&mut b).await.is_err());
    }

    #[test]
    fn refusals_are_kebab_case_on_the_wire() {
        let s = serde_json::to_string(&SshReady { ok: false, refusal: Some(Refusal::LinuxMissing) }).unwrap();
        assert_eq!(s, r#"{"ok":false,"refusal":"linux-missing"}"#);
        let ok = serde_json::to_string(&SshReady { ok: true, refusal: None }).unwrap();
        assert_eq!(ok, r#"{"ok":true}"#);
    }

    #[test]
    fn desktop_frames_read_as_they_are_documented() {
        let hello = DesktopHello { v: 1, auth: None, op: DesktopOp::Open, start: Some(DesktopSize { w: 1512, h: 982 }) };
        assert_eq!(serde_json::to_string(&hello).unwrap(), r#"{"v":1,"op":"open","start":{"w":1512,"h":982}}"#);
        let web: DesktopHello = serde_json::from_str(r#"{"v":1,"op":"web"}"#).unwrap();
        assert_eq!(web.op, DesktopOp::Web);
        assert!(web.start.is_none());

        let starting = DesktopReply { starting: true, ..Default::default() };
        assert_eq!(serde_json::to_string(&starting).unwrap(), r#"{"ok":false,"starting":true}"#);
        let stopped = serde_json::to_string(&DesktopReply::refused(Refusal::DesktopStopped)).unwrap();
        assert_eq!(stopped, r#"{"ok":false,"refusal":"desktop-stopped"}"#);
    }

    #[test]
    fn a_status_from_an_older_phone_still_reads() {
        // Before the desktop: no features, no desktop. Both read as "none".
        let old: CtlStatus =
            serde_json::from_str(r#"{"name":"Phone","ssh":"up","sessions":0,"version":"0.1.0"}"#).unwrap();
        assert!(old.features.is_empty());
        assert!(old.desktop.is_empty());
    }
}
