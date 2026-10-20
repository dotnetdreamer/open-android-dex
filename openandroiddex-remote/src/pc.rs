//! The computer's end of a connection: dial a paired phone, by its endpoint id
//! alone, from wherever this computer happens to be.
//!
//! There is no address anywhere in here. iroh finds the phone's current home
//! relay through address lookup, meets it there, and moves the connection onto
//! a direct path as soon as hole punching finds one — in practice most of the
//! time, and silently re-done when either side changes network. What reaches
//! ssh is a byte stream, whichever way it went.
//!
//! Every process gets an endpoint of its own with a throwaway key, and the
//! computer's long-term key only ever SIGNS for it (see [`proto::Auth`]): VS
//! Code, a terminal and an scp all at once are three endpoints, not one key in
//! three places fighting over the relay.
//!
//! Every failure is turned into a sentence (see [`DialError`]): ssh prints
//! whatever its ProxyCommand writes to stderr, and "kex_exchange_identification:
//! Connection closed by remote host" has never once told anyone what to do.
//!
//! The phone's desktop ([`Desktop`]) rides the same tunnel: its web viewer is
//! served on a port of this computer's loopback, and a browser — or the
//! desktop app's window — loads it from there as though it were the phone's.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::Context;
use iroh::endpoint::{presets, Connection, ConnectionError, RecvStream, SendStream};
use iroh::{Endpoint, EndpointId, SecretKey};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tracing::{debug, info, warn};

use crate::pairing::PairKeys;
use crate::pipe::{splice, Traffic};
use crate::proto::{
    self, Auth, CtlRequest, CtlStatus, DesktopHello, DesktopOp, DesktopReply, DesktopSize, PairError, PairRequest,
    PairResponse, Refusal, SshHello, SshReady, ALPN_CTL, ALPN_DESKTOP, ALPN_PAIR, ALPN_SSH, CLOSE_NOT_PAIRED,
    CLOSE_SHUTDOWN, CLOSE_UNPAIRED,
};
use crate::sshkey;

/// Long enough for a phone that has to be woken, find its relay again and
/// start its SSH server; short enough that a phone that is simply off does not
/// leave ssh hanging for minutes.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);

/// Why a connection to a phone did not happen, in words a person can act on.
#[derive(Debug)]
pub enum DialError {
    /// Nothing answered.
    Unreachable,
    /// The phone answered, and its app is too old to know what was asked.
    Outdated,
    /// The phone answered and does not know this computer.
    NotPaired,
    /// The phone forgot this computer while a session was open.
    Unpaired,
    /// The phone switched remote access off.
    Stopped,
    /// The phone answered and could not put the connection through.
    Refused(Refusal),
    /// Anything else, with what the network stack said.
    Other(String),
}

impl std::fmt::Display for DialError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            DialError::Unreachable => {
                "The phone did not answer. Check that it is switched on and online, and that \
                 Remote access is turned on (Linux → Remote access on the phone)."
            }
            DialError::Outdated => {
                "The app on the phone is too old for this. Update Open Android DeX on the phone, \
                 then try again."
            }
            DialError::NotPaired => {
                "The phone does not know this computer. Pair it again: on the phone open \
                 Linux → Remote access → Add a computer."
            }
            DialError::Unpaired => "This computer was removed on the phone, so the session was closed.",
            DialError::Stopped => "Remote access was turned off on the phone.",
            DialError::Refused(Refusal::LinuxMissing) => {
                "Linux is not installed on the phone. Open Linux on the phone once to install it."
            }
            DialError::Refused(Refusal::LinuxInstalling) => {
                "Linux on the phone is still being set up. Try again in a minute."
            }
            DialError::Refused(Refusal::SshFailed) => {
                "The phone could not start its SSH server. Open Linux → Remote access on the \
                 phone to see what went wrong."
            }
            DialError::Refused(Refusal::Disabled) => "Remote access was turned off on the phone.",
            DialError::Refused(Refusal::LinuxUnfinished) => {
                "Linux on the phone did not finish setting up. Open Linux on the phone and tap Retry."
            }
            DialError::Refused(Refusal::DesktopStopped) => "The desktop is not running on the phone.",
            DialError::Refused(Refusal::DesktopFailed) => {
                "The phone could not start its desktop. Open Linux on the phone to see what went wrong."
            }
            DialError::Other(detail) => return write!(f, "The connection to the phone failed: {detail}"),
        })
    }
}

impl std::error::Error for DialError {}

/// Read a closed connection's close code back into a reason, if the phone gave one.
fn why_closed(conn: &Connection) -> Option<DialError> {
    match conn.close_reason()? {
        ConnectionError::ApplicationClosed(close) => {
            let code = close.error_code.into_inner();
            if code == CLOSE_NOT_PAIRED as u64 {
                Some(DialError::NotPaired)
            } else if code == CLOSE_UNPAIRED as u64 {
                Some(DialError::Unpaired)
            } else if code == CLOSE_SHUTDOWN as u64 {
                Some(DialError::Stopped)
            } else {
                None
            }
        }
        ConnectionError::TimedOut => Some(DialError::Unreachable),
        _ => None,
    }
}

fn failed(conn: &Connection, detail: impl std::fmt::Display) -> DialError {
    why_closed(conn).unwrap_or_else(|| DialError::Other(detail.to_string()))
}

/// The phone was there and turned the protocol down in the handshake: its app
/// is from before this kind of connection existed — a desktop asked of a phone
/// whose daemon only speaks SSH. TLS says so with alert 120,
/// no_application_protocol, which QUIC carries as crypto error 0x178. Telling
/// that apart is the difference between "update the app on the phone" and
/// "check that the phone is online", which it plainly is.
fn protocol_refused(e: &iroh::endpoint::ConnectError) -> bool {
    use iroh::endpoint::{ConnectError, ConnectingError, ConnectionError, TransportErrorCode};
    let conn = match e {
        ConnectError::Connecting { source: ConnectingError::ConnectionError { source, .. }, .. } => source,
        ConnectError::Connection { source, .. } => source,
        _ => return false,
    };
    matches!(conn, ConnectionError::ConnectionClosed(close) if close.error_code == TransportErrorCode::crypto(120))
}

/// What a phone says about itself, and how the path to it looks right now.
#[derive(Debug, Clone)]
pub struct Probe {
    pub status: CtlStatus,
    /// The round trip on the path in use.
    pub rtt_ms: u64,
    /// That path goes straight to the phone, not through a relay.
    pub direct: bool,
}

/// A phone this computer now trusts, as the phone described itself.
#[derive(Debug, Clone)]
pub struct Paired {
    pub id: EndpointId,
    pub name: String,
    pub user: String,
    pub host_key: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairFailure {
    /// What was typed is not a code.
    NotACode,
    /// No phone is listening for this code: mistyped, expired, or the phone is offline.
    NotFound,
    /// A phone answered, but not to this code.
    WrongCode,
    /// The phone said no.
    Rejected,
    /// The code ran out of time while it was being typed.
    Expired,
    /// The phone's answer did not add up. Never expected; never trusted.
    Invalid,
}

impl std::fmt::Display for PairFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            PairFailure::NotACode => "That is not a pairing code — it is eight letters and numbers, like K7QM-29XV.",
            PairFailure::NotFound => {
                "No phone is waiting for that code. Check it against the phone's screen; the code \
                 lasts ten minutes, and the phone has to stay online while you type it."
            }
            PairFailure::WrongCode => "That code does not match. Check it against the phone's screen.",
            PairFailure::Rejected => "The phone did not accept this computer.",
            PairFailure::Expired => "That code has expired. Ask the phone for a new one: Remote access, Add a computer.",
            PairFailure::Invalid => "The phone's answer could not be verified, so nothing was saved.",
        })
    }
}

impl std::error::Error for PairFailure {}

/// For a process that is ssh's ProxyCommand: call first thing.
///
/// ssh sends its proxy SIGHUP as it exits. A proxy that died of it never told
/// the phone the session was over, and the phone found out from QUIC's idle
/// timeout half a minute later — holding its wake lock all that time, and
/// showing a computer connected that had gone (measured on a phone: 30–40 s
/// per session). Ignored, the proxy ends the way it does when stdin closes:
/// the end passed on, the connection closed properly. Windows' ssh terminates
/// its proxy instead, which nothing can catch; the phone's end copes with that
/// on its own (see `pipe::splice`).
pub fn ignore_hangup() {
    #[cfg(unix)]
    // SAFETY: setting a signal's disposition, before any other thread exists.
    unsafe {
        libc::signal(libc::SIGHUP, libc::SIG_IGN);
    }
}

/// This computer on the network: a throwaway endpoint, speaking for the
/// computer's long-term key.
pub struct Client {
    ep: Endpoint,
    computer: SecretKey,
}

impl Client {
    /// A client endpoint on the public relays and address lookup, accepting
    /// nothing — a computer is only ever the one who dials.
    pub async fn bind(computer: SecretKey) -> anyhow::Result<Self> {
        let mut builder = Endpoint::builder(presets::N0).secret_key(SecretKey::generate());
        if crate::relay_only() {
            builder = builder.clear_ip_transports();
        }
        let ep = builder.bind().await.context("cannot start networking")?;
        Ok(Client { ep, computer })
    }

    /// The computer's own id — what the phones have in their lists.
    pub fn computer_id(&self) -> EndpointId {
        self.computer.public()
    }

    pub fn endpoint(&self) -> &Endpoint {
        &self.ep
    }

    /// Close gracefully: QUIC sends its close frames, so the phone hears a
    /// reason rather than waiting for an idle timeout.
    pub async fn close(&self) {
        self.ep.close().await;
    }

    /// The computer's signature for this process's endpoint.
    fn auth(&self) -> Auth {
        let sig = self.computer.sign(&proto::delegation_message(&self.ep.id()));
        Auth {
            computer: self.computer.public().to_string(),
            sig: data_encoding::HEXLOWER.encode(&sig.to_bytes()),
        }
    }

    async fn dial(&self, phone: EndpointId, alpn: &[u8], timeout: Duration) -> Result<Connection, DialError> {
        match tokio::time::timeout(timeout, self.ep.connect(phone, alpn)).await {
            Ok(Ok(conn)) => Ok(conn),
            Ok(Err(e)) => {
                debug!("connect: {e:#}");
                Err(if protocol_refused(&e) { DialError::Outdated } else { DialError::Unreachable })
            }
            Err(_) => Err(DialError::Unreachable),
        }
    }

    /// Open one SSH stream to a phone, past its handshake: what comes back is
    /// a byte stream to the guest's sshd.
    pub async fn open_ssh(&self, phone: EndpointId) -> Result<(Connection, SendStream, RecvStream), DialError> {
        let conn = self.dial(phone, ALPN_SSH, CONNECT_TIMEOUT).await?;
        let (mut send, mut recv) = conn.open_bi().await.map_err(|e| failed(&conn, e))?;
        proto::write_frame(&mut send, &SshHello { v: 1, auth: Some(self.auth()) })
            .await
            .map_err(|e| failed(&conn, e))?;
        // The phone may wait up to 45 s for its sshd to come up before answering.
        let ready: SshReady = match tokio::time::timeout(Duration::from_secs(60), proto::read_frame(&mut recv)).await {
            Ok(Ok(ready)) => ready,
            Ok(Err(e)) => return Err(failed(&conn, e)),
            Err(_) => return Err(DialError::Refused(Refusal::SshFailed)),
        };
        if !ready.ok {
            return Err(DialError::Refused(ready.refusal.unwrap_or(Refusal::SshFailed)));
        }
        Ok((conn, send, recv))
    }

    /// ssh's ProxyCommand: stdin and stdout are the connection.
    pub async fn proxy_stdio(&self, phone: EndpointId) -> Result<(), DialError> {
        let (conn, send, recv) = self.open_ssh(phone).await?;
        let traffic = Traffic::default();
        let result = splice(tokio::io::stdin(), tokio::io::stdout(), send, recv, &traffic, None).await;
        // ssh gone before the phone's last bytes: nothing is left to hear them,
        // and a sentence on a terminal whose prompt is already back is noise.
        let result = match result {
            Err(e) if e.kind() == std::io::ErrorKind::BrokenPipe => Ok(()),
            other => other,
        };
        let outcome = result.map_err(|e| failed(&conn, e));
        conn.close(0u32.into(), b"done");
        outcome
    }

    /// A local port that leads to the phone's sshd, for the programs that
    /// cannot run a ProxyCommand — PuTTY, WinSCP, a file manager's "connect to
    /// server". Each local connection gets a stream of its own.
    pub async fn forward(self: std::sync::Arc<Self>, phone: EndpointId, listen: SocketAddr) -> anyhow::Result<()> {
        let listener = TcpListener::bind(listen)
            .await
            .with_context(|| format!("cannot listen on {listen}"))?;
        info!("forwarding {} to the phone", listener.local_addr()?);
        loop {
            let (tcp, from) = listener.accept().await?;
            let client = self.clone();
            tokio::spawn(async move {
                let _ = tcp.set_nodelay(true);
                match client.open_ssh(phone).await {
                    Ok((conn, send, recv)) => {
                        let (r, w) = tcp.into_split();
                        let traffic = Traffic::default();
                        if let Err(e) = splice(r, w, send, recv, &traffic, None).await {
                            debug!("{from}: {e}");
                        }
                        conn.close(0u32.into(), b"done");
                    }
                    Err(e) => warn!("{from}: {e}"),
                }
            });
        }
    }

    /// Ask a paired phone how it is. The path is sampled after a short wait,
    /// because the first answer always comes through the relay — hole punching
    /// starts with the connection, and an instant reading would report
    /// "relayed" for phones that go direct a moment later.
    pub async fn probe(&self, phone: EndpointId) -> Result<Probe, DialError> {
        let conn = self.dial(phone, ALPN_CTL, Duration::from_secs(20)).await?;
        let started = Instant::now();
        let (mut send, mut recv) = conn.open_bi().await.map_err(|e| failed(&conn, e))?;
        proto::write_frame(&mut send, &CtlRequest { v: 1, auth: Some(self.auth()) })
            .await
            .map_err(|e| failed(&conn, e))?;
        let _ = send.finish();
        let status: CtlStatus = match tokio::time::timeout(Duration::from_secs(15), proto::read_frame(&mut recv)).await {
            Ok(Ok(status)) => status,
            Ok(Err(e)) => return Err(failed(&conn, e)),
            Err(_) => return Err(DialError::Unreachable),
        };
        let answered = started.elapsed();
        let mut path = selected_path(&conn);
        let settle = Instant::now() + Duration::from_secs(3);
        while !path.0 && Instant::now() < settle {
            tokio::time::sleep(Duration::from_millis(250)).await;
            path = selected_path(&conn);
        }
        conn.close(0u32.into(), b"done");
        let rtt_ms = if path.1 > 0 { path.1 } else { answered.as_millis() as u64 };
        Ok(Probe { status, rtt_ms, direct: path.0 })
    }

    /// Pair with the phone showing `code`. `ssh_key` is this computer's SSH
    /// public key line; the phone puts it in the guest's authorized_keys.
    pub async fn pair(&self, code: &str, name: &str, os: &str, ssh_key: &str) -> Result<Paired, PairFailure> {
        let typed = code.to_string();
        let keys = tokio::task::spawn_blocking(move || PairKeys::derive(&typed))
            .await
            .ok()
            .flatten()
            .ok_or(PairFailure::NotACode)?;
        let target = keys.endpoint_id();

        // The phone's pairing endpoint publishes itself the moment it opens, but
        // a lookup that races that — or a phone whose network blinked — answers
        // "not found" for a few seconds. Keep dialling for a while before saying so.
        let deadline = Instant::now() + Duration::from_secs(45);
        let conn = loop {
            match tokio::time::timeout(Duration::from_secs(15), self.ep.connect(target, ALPN_PAIR)).await {
                Ok(Ok(conn)) => break conn,
                other => {
                    if let Ok(Err(e)) = other {
                        debug!("pair: {e:#}");
                    }
                    if Instant::now() >= deadline {
                        return Err(PairFailure::NotFound);
                    }
                    tokio::time::sleep(Duration::from_secs(2)).await;
                }
            }
        };

        let me = self.computer_id();
        let ssh_key = ssh_key.trim();
        let request = PairRequest {
            v: 1,
            name: name.to_string(),
            os: os.to_string(),
            ssh_key: ssh_key.to_string(),
            proof: data_encoding::HEXLOWER.encode(&keys.computer_proof(&me, name, os, ssh_key)),
            auth: Some(self.auth()),
        };
        let (mut send, mut recv) = conn.open_bi().await.map_err(|_| PairFailure::NotFound)?;
        proto::write_frame(&mut send, &request).await.map_err(|_| PairFailure::NotFound)?;
        let _ = send.finish();
        // The phone waits for its app to save the pairing before it answers.
        let response: PairResponse = match tokio::time::timeout(Duration::from_secs(45), proto::read_frame(&mut recv)).await {
            Ok(Ok(r)) => r,
            _ => return Err(PairFailure::NotFound),
        };
        conn.close(0u32.into(), b"done");

        if !response.ok {
            return Err(match response.error {
                Some(PairError::BadCode) => PairFailure::WrongCode,
                Some(PairError::Rejected) => PairFailure::Rejected,
                Some(PairError::Expired) => PairFailure::Expired,
                _ => PairFailure::Invalid,
            });
        }
        let id: EndpointId = response
            .id
            .as_deref()
            .and_then(|s| s.parse().ok())
            .ok_or(PairFailure::Invalid)?;
        let host_key = response.host_key.unwrap_or_default();
        if sshkey::parse_public_line(&host_key).is_none() {
            return Err(PairFailure::Invalid);
        }
        let expected = keys.phone_proof(&id, &me, &host_key);
        if !PairKeys::verify(&expected, response.proof.as_deref().unwrap_or("")) {
            return Err(PairFailure::Invalid);
        }
        Ok(Paired {
            id,
            name: response.name.unwrap_or_else(|| "Phone".into()),
            user: response.user.unwrap_or_else(|| crate::phone::GUEST_USER.into()),
            host_key,
        })
    }
}

fn selected_path(conn: &Connection) -> (bool, u64) {
    conn.paths()
        .iter()
        .find(|p| p.is_selected())
        .map(|p| (p.is_ip(), p.rtt().as_millis() as u64))
        .unwrap_or((false, 0))
}

/// Where a connection's chosen path runs: through a relay, straight across
/// the local network, or straight across the internet — a hole punched
/// through the phone's mobile network, say. Direct alone cannot tell the
/// last two apart, and they are the difference a person notices.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PathKind {
    Relay,
    Local,
    Internet,
}

fn selected_path_kind(conn: &Connection) -> Option<PathKind> {
    conn.paths().iter().find(|p| p.is_selected()).map(|p| match p.remote_addr() {
        iroh::TransportAddr::Ip(remote) => {
            let local = match p.local_addr() {
                iroh::endpoint::LocalTransportAddr::Ip(ip) => *ip,
                _ => None,
            };
            if on_local_network(remote.ip(), local) {
                PathKind::Local
            } else {
                PathKind::Internet
            }
        }
        _ => PathKind::Relay,
    })
}

/// A direct path stays on the local network when the phone's address is a
/// private one — RFC 1918, link-local, an IPv6 ULA — or, IPv6 having no NAT
/// to give it away, when it shares this computer's /64: the one prefix a home
/// network hands everything on it. Carrier-grade NAT's 100.64/10 is the
/// carrier's, not the room's.
pub fn on_local_network(remote: std::net::IpAddr, local: Option<std::net::IpAddr>) -> bool {
    use std::net::IpAddr;
    match remote {
        IpAddr::V4(v4) => v4.is_private() || v4.is_link_local() || v4.is_loopback(),
        IpAddr::V6(v6) => {
            if let Some(v4) = v6.to_ipv4_mapped() {
                return on_local_network(IpAddr::V4(v4), local);
            }
            let s = v6.segments();
            (s[0] & 0xfe00) == 0xfc00
                || (s[0] & 0xffc0) == 0xfe80
                || v6.is_loopback()
                || matches!(local, Some(IpAddr::V6(l)) if l.segments()[..4] == s[..4])
        }
    }
}

// ── the desktop ───────────────────────────────────────────────────────────

/// How to log in to a phone's desktop, as its `open` answered.
#[derive(Debug, Clone)]
pub struct DesktopLogin {
    /// Xvnc's password. The viewer page takes it in its URL, as it does on
    /// the phone, so nobody has to type it.
    pub password: String,
    /// The size the desktop is running at, when the phone knows it.
    pub size: Option<DesktopSize>,
}

/// The viewer page for a desktop served on `port` of this computer's loopback.
/// `ctx=pc` tells the page it is on a computer: a mouse and a keyboard, no
/// touch methods, and no Remote access button of its own.
pub fn desktop_url(port: u16, login: &DesktopLogin) -> String {
    let password: String = login
        .password
        .bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect();
    format!("http://127.0.0.1:{port}/dex.html?password={password}&ctx=pc")
}

/// How often a desktop's connection is asked whether it is still answered,
/// and how long the answer may take. Long enough to ride out the phone moving
/// networks, which the connection follows it across; short next to the
/// minutes a dead daemon's connection otherwise lingers. A phone that starts
/// its daemon again — the app updated, the desktop app's end-of-session
/// clean-up — is back under the same address, the relay hands it the old
/// connection's packets, and nothing ever says that connection is gone: the
/// viewer sat on its last frame, looking connected, for three minutes.
const PING_EVERY: Duration = Duration::from_secs(5);
const PING_WAIT: Duration = Duration::from_secs(12);

/// A phone's desktop, served on a port of this computer's loopback.
///
/// One QUIC connection carries all of it: the `open`, every file the viewer
/// page loads and the VNC WebSocket are streams on it, so a page load costs no
/// handshakes and no file waits behind the picture. When the connection goes —
/// a network change neither end could follow, a phone asleep too long — the
/// next stream dials a new one.
pub struct Desktop {
    client: Arc<Client>,
    phone: EndpointId,
    conn: std::sync::Mutex<Option<Connection>>,
    /// One dial at a time: a page load opens dozens of streams at once, and
    /// they should share one new connection, not race to make their own.
    dialing: tokio::sync::Mutex<()>,
}

impl Desktop {
    pub fn new(client: Arc<Client>, phone: EndpointId) -> Arc<Self> {
        Arc::new(Desktop {
            client,
            phone,
            conn: std::sync::Mutex::new(None),
            dialing: tokio::sync::Mutex::new(()),
        })
    }

    fn live(&self) -> Option<Connection> {
        self.conn
            .lock()
            .unwrap()
            .as_ref()
            .filter(|c| c.close_reason().is_none())
            .cloned()
    }

    /// The connection, dialled again if the last one has closed.
    async fn connection(&self) -> Result<Connection, DialError> {
        let _turn = self.dialing.lock().await;
        if let Some(conn) = self.live() {
            return Ok(conn);
        }
        let conn = self.client.dial(self.phone, ALPN_DESKTOP, CONNECT_TIMEOUT).await?;
        *self.conn.lock().unwrap() = Some(conn.clone());
        Ok(conn)
    }

    /// Is the desktop up, and how does one log in? With `start`, the phone
    /// brings it up at that size if it is not running; without, a desktop that
    /// is not running is [`Refusal::DesktopStopped`]. `starting` is called if
    /// the phone says it is on its way up, which can take a minute.
    pub async fn open(&self, start: Option<DesktopSize>, starting: impl FnOnce()) -> Result<DesktopLogin, DialError> {
        let conn = self.connection().await?;
        let (mut send, mut recv) = conn.open_bi().await.map_err(|e| failed(&conn, e))?;
        let hello = DesktopHello { v: 1, auth: Some(self.client.auth()), op: DesktopOp::Open, start };
        proto::write_frame(&mut send, &hello).await.map_err(|e| failed(&conn, e))?;
        let _ = send.finish();
        let mut starting = Some(starting);
        loop {
            // The phone gives up after two minutes; a little longer here, so
            // that its answer is what ends this and not a guess.
            let reply: DesktopReply =
                match tokio::time::timeout(Duration::from_secs(150), proto::read_frame(&mut recv)).await {
                    Ok(Ok(reply)) => reply,
                    Ok(Err(e)) => return Err(failed(&conn, e)),
                    Err(_) => return Err(DialError::Refused(Refusal::DesktopFailed)),
                };
            if reply.starting {
                if let Some(starting) = starting.take() {
                    starting();
                }
                continue;
            }
            if !reply.ok {
                return Err(DialError::Refused(reply.refusal.unwrap_or(Refusal::DesktopFailed)));
            }
            return Ok(DesktopLogin { password: reply.password.unwrap_or_default(), size: reply.size });
        }
    }

    /// Ask the live connection, if there is one, whether it is still
    /// answered. One that is not is closed, which ends the viewer's socket on
    /// it — so the viewer reconnects, on a new connection — rather than
    /// leaving it looking at a picture nothing is drawing any more.
    pub async fn ping(&self) -> bool {
        let Some(conn) = self.live() else { return true };
        let asked = async {
            let (mut send, mut recv) = conn.open_bi().await.ok()?;
            let hello = DesktopHello { v: 1, auth: Some(self.client.auth()), op: DesktopOp::Ping, start: None };
            proto::write_frame(&mut send, &hello).await.ok()?;
            let _ = send.finish();
            proto::read_frame::<_, DesktopReply>(&mut recv).await.ok()
        };
        if let Ok(Some(_)) = tokio::time::timeout(PING_WAIT, asked).await {
            return true;
        }
        info!("desktop: the phone stopped answering; hanging up so the viewer reconnects");
        conn.close(0u32.into(), b"no answer");
        false
    }

    /// [`Self::ping`] every few seconds, for as long as the desktop is open.
    pub async fn keep_alive(self: Arc<Self>) {
        loop {
            tokio::time::sleep(PING_EVERY).await;
            self.ping().await;
        }
    }

    /// The live connection's path: direct or not, and its round trip.
    pub fn path(&self) -> Option<(bool, u64)> {
        self.live().map(|c| selected_path(&c))
    }

    /// Where the live connection's path runs, for the window's title.
    pub fn path_kind(&self) -> Option<PathKind> {
        self.live().and_then(|c| selected_path_kind(&c))
    }

    /// Why the connection closed, once it has; `None` while it is up or
    /// before there has been one.
    pub fn closed(&self) -> Option<DialError> {
        let conn = self.conn.lock().unwrap().clone()?;
        conn.close_reason()
            .map(|e| why_closed(&conn).unwrap_or_else(|| DialError::Other(e.to_string())))
    }

    /// Hang up, saying so. Anything that asks again dials again.
    pub fn close(&self) {
        if let Some(conn) = self.conn.lock().unwrap().take() {
            conn.close(0u32.into(), b"done");
        }
    }

    /// Put every connection made to `listener` through to the phone's web
    /// viewer, each on a stream of its own — after its request has shown it
    /// comes from the viewer itself (see [`check_request`]).
    pub async fn serve(self: Arc<Self>, listener: TcpListener) -> std::io::Result<()> {
        let port = listener.local_addr()?.port();
        loop {
            let (tcp, _) = listener.accept().await?;
            let desktop = self.clone();
            tokio::spawn(async move { desktop.forward(tcp, port).await });
        }
    }

    async fn forward(&self, mut tcp: TcpStream, port: u16) {
        let _ = tcp.set_nodelay(true);
        let Some(received) = read_request_head(&mut tcp).await else {
            return;
        };
        if let Err(why) = check_request(&received, port) {
            info!("desktop: refused a request to the viewer's port ({why})");
            let _ = tcp
                .write_all(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .await;
            return;
        }
        let (send, recv) = match self.open_web().await {
            Ok(stream) => stream,
            Err(e) => {
                debug!("desktop: {e}");
                // Seen only if the page is loaded by hand while the phone is
                // away: the app's window covers it with its own explanation.
                let body = e.to_string();
                let answer = format!(
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\n\
                     Content-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = tcp.write_all(answer.as_bytes()).await;
                return;
            }
        };
        let mut send = send;
        // Everything read so far goes first: the request head, and whatever
        // the browser had already sent behind it.
        if send.write_all(&received).await.is_err() {
            return;
        }
        let (r, w) = tcp.into_split();
        let traffic = Traffic::default();
        if let Err(e) = splice(r, w, send, recv, &traffic, None).await {
            debug!("desktop: a stream ended with {e}");
        }
    }

    /// One `web` stream, past its answer.
    async fn open_web(&self) -> Result<(SendStream, RecvStream), DialError> {
        let conn = self.connection().await?;
        let (mut send, mut recv) = conn.open_bi().await.map_err(|e| failed(&conn, e))?;
        let hello = DesktopHello { v: 1, auth: Some(self.client.auth()), op: DesktopOp::Web, start: None };
        proto::write_frame(&mut send, &hello).await.map_err(|e| failed(&conn, e))?;
        let reply: DesktopReply = match tokio::time::timeout(Duration::from_secs(30), proto::read_frame(&mut recv)).await
        {
            Ok(Ok(reply)) => reply,
            Ok(Err(e)) => return Err(failed(&conn, e)),
            Err(_) => return Err(DialError::Unreachable),
        };
        if !reply.ok {
            return Err(DialError::Refused(reply.refusal.unwrap_or(Refusal::DesktopStopped)));
        }
        Ok((send, recv))
    }
}

/// No request the viewer makes comes near this: it asks for files by name and
/// opens one WebSocket.
const MAX_REQUEST_HEAD: usize = 16 * 1024;

/// Read until the end of an HTTP request head. Returns everything read, which
/// may run past the head; `None` for a connection that sent nothing usable.
async fn read_request_head(tcp: &mut TcpStream) -> Option<Vec<u8>> {
    let mut received = Vec::with_capacity(1024);
    let mut chunk = [0u8; 4096];
    let read = async {
        loop {
            let n = tcp.read(&mut chunk).await.ok()?;
            if n == 0 {
                return None;
            }
            received.extend_from_slice(&chunk[..n]);
            if head_end(&received).is_some() {
                return Some(());
            }
            if received.len() > MAX_REQUEST_HEAD {
                return None;
            }
        }
    };
    tokio::time::timeout(Duration::from_secs(10), read).await.ok()??;
    Some(received)
}

fn head_end(received: &[u8]) -> Option<usize> {
    received.windows(4).position(|w| w == b"\r\n\r\n").map(|i| i + 4)
}

/// Is this request the viewer's own? Addressed to this port by name — and,
/// when the browser says which page sent it, sent by a page served from here.
///
/// The port is on loopback, but loopback is not private to the app: any web
/// page open in any browser on this computer can open a WebSocket to
/// 127.0.0.1, and websockify checks nothing. Such a page names itself in
/// `Origin`, and a DNS-rebinding one gives its own name in `Host`, so both are
/// turned away here. That leaves the VNC password to stand against local
/// programs only — the job it already had on the phone, where every app can
/// reach websockify.
fn check_request(received: &[u8], port: u16) -> Result<(), &'static str> {
    let end = head_end(received).ok_or("no request")?;
    let head = std::str::from_utf8(&received[..end]).map_err(|_| "not text")?;
    let ours = [format!("127.0.0.1:{port}"), format!("localhost:{port}")];
    let (mut host, mut origin) = (None, None);
    for line in head.split("\r\n").skip(1) {
        let Some((name, value)) = line.split_once(':') else { continue };
        let slot = if name.eq_ignore_ascii_case("host") {
            &mut host
        } else if name.eq_ignore_ascii_case("origin") {
            &mut origin
        } else {
            continue;
        };
        if slot.replace(value.trim()).is_some() {
            return Err("a header twice");
        }
    }
    let host = host.ok_or("no Host")?;
    if !ours.iter().any(|o| o.eq_ignore_ascii_case(host)) {
        return Err("another host");
    }
    if let Some(origin) = origin {
        if !ours.iter().any(|o| origin.eq_ignore_ascii_case(&format!("http://{o}"))) {
            return Err("another origin");
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(lines: &[&str]) -> Vec<u8> {
        let mut text = lines.join("\r\n");
        text.push_str("\r\n\r\n");
        text.into_bytes()
    }

    #[test]
    fn a_direct_path_is_local_when_its_address_says_so() {
        let ip = |s: &str| s.parse::<std::net::IpAddr>().unwrap();
        assert!(on_local_network(ip("192.168.2.12"), None));
        assert!(on_local_network(ip("10.0.0.5"), None));
        assert!(on_local_network(ip("172.20.1.1"), None));
        assert!(on_local_network(ip("fe80::1"), None));
        assert!(on_local_network(ip("fd12:3456::1"), None));
        assert!(on_local_network(ip("::ffff:192.168.1.4"), None));
        assert!(!on_local_network(ip("77.165.215.32"), None));
        assert!(!on_local_network(ip("100.64.1.2"), None), "carrier-grade NAT is the carrier's");
        // Measured: the phone and this computer on one home network, by IPv6.
        let mine = Some(ip("2a02:a44b:4c7:0:f082:8d42:1bd6:81e7"));
        assert!(on_local_network(ip("2a02:a44b:4c7:0:4ce9:32ff:fee0:e2db"), mine), "the same /64");
        assert!(!on_local_network(ip("2a02:a44b:4c7:1::5"), mine), "another /64");
        assert!(!on_local_network(ip("2001:db8::1"), None));
    }

    #[test]
    fn the_viewers_own_requests_go_through() {
        let page = request(&["GET /dex.html?password=x&ctx=pc HTTP/1.1", "Host: 127.0.0.1:6080"]);
        assert_eq!(check_request(&page, 6080), Ok(()));
        let socket = request(&[
            "GET /websockify HTTP/1.1",
            "host: 127.0.0.1:6080",
            "Upgrade: websocket",
            "Origin: http://127.0.0.1:6080",
        ]);
        assert_eq!(check_request(&socket, 6080), Ok(()));
        let by_name = request(&["GET / HTTP/1.1", "Host: localhost:6080", "Origin: http://localhost:6080"]);
        assert_eq!(check_request(&by_name, 6080), Ok(()));
    }

    #[test]
    fn other_pages_and_other_names_are_turned_away() {
        let cross = request(&["GET /websockify HTTP/1.1", "Host: 127.0.0.1:6080", "Origin: https://example.com"]);
        assert!(check_request(&cross, 6080).is_err(), "a page from elsewhere");
        let rebound = request(&["GET /dex.html HTTP/1.1", "Host: example.com:6080"]);
        assert!(check_request(&rebound, 6080).is_err(), "DNS rebinding");
        let other_port = request(&["GET / HTTP/1.1", "Host: 127.0.0.1:6081"]);
        assert!(check_request(&other_port, 6080).is_err());
        let sandboxed = request(&["GET / HTTP/1.1", "Host: 127.0.0.1:6080", "Origin: null"]);
        assert!(check_request(&sandboxed, 6080).is_err());
        let nameless = request(&["GET / HTTP/1.1"]);
        assert!(check_request(&nameless, 6080).is_err());
        let doubled = request(&["GET / HTTP/1.1", "Host: 127.0.0.1:6080", "Host: example.com"]);
        assert!(check_request(&doubled, 6080).is_err());
        assert!(check_request(b"GET / HTTP/1.1\r\nHost: 127.0.0.1:6080\r\n", 6080).is_err(), "unfinished head");
    }

    #[test]
    fn the_password_is_carried_in_the_url_intact() {
        let login = DesktopLogin { password: "aZ09 &=?".into(), size: None };
        assert_eq!(desktop_url(6080, &login), "http://127.0.0.1:6080/dex.html?password=aZ09%20%26%3D%3F&ctx=pc");
    }
}
