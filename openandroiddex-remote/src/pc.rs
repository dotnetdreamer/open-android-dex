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

use std::net::SocketAddr;
use std::time::{Duration, Instant};

use anyhow::Context;
use iroh::endpoint::{presets, Connection, ConnectionError, RecvStream, SendStream};
use iroh::{Endpoint, EndpointId, SecretKey};
use tokio::net::TcpListener;
use tracing::{debug, info, warn};

use crate::pairing::PairKeys;
use crate::pipe::{splice, Traffic};
use crate::proto::{
    self, Auth, CtlRequest, CtlStatus, PairError, PairRequest, PairResponse, Refusal, SshHello, SshReady,
    ALPN_CTL, ALPN_PAIR, ALPN_SSH, CLOSE_NOT_PAIRED, CLOSE_SHUTDOWN, CLOSE_UNPAIRED,
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
                Err(DialError::Unreachable)
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
