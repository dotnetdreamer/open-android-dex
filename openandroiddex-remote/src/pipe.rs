//! Joining a QUIC stream to something local: the guest's sshd on the phone,
//! stdin/stdout (ssh's ProxyCommand) or a forwarded TCP socket on the computer.
//!
//! Half-close is the whole difficulty. SSH ends a session by one side closing
//! its write half and then reading until the other side does the same, so each
//! direction is copied on its own and its end is passed on as an end (a QUIC
//! FIN, or a TCP shutdown) instead of tearing everything down at the first EOF.
//! An *error* in either direction is different: the link is gone, and the other
//! direction is dropped at once — otherwise a phone that lost its network would
//! leave sshd holding a session open until its own keepalive gave up.

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use iroh::endpoint::{RecvStream, SendStream};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Bytes moved in each direction, readable while the splice is still running.
#[derive(Debug, Default)]
pub struct Traffic {
    /// Local side → QUIC.
    pub out: AtomicU64,
    /// QUIC → local side.
    pub inn: AtomicU64,
}

/// How long a finished stream waits for its last bytes to be acknowledged.
/// Exiting before that can drop them: closing a QUIC connection discards
/// anything still unacknowledged.
const DRAIN: Duration = Duration::from_secs(5);

async fn pump<R, W>(r: &mut R, w: &mut W, counter: &AtomicU64) -> std::io::Result<()>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    // 32 KB: big enough that an scp is not syscall-bound, small enough that a
    // hundred idle sessions on a phone cost nothing worth measuring.
    let mut buf = vec![0u8; 32 * 1024];
    loop {
        let n = r.read(&mut buf).await?;
        if n == 0 {
            // shutdown() is a TCP FIN on a socket and a QUIC finish() on a
            // stream — the same "no more from me" either way.
            return w.shutdown().await;
        }
        w.write_all(&buf[..n]).await?;
        // A no-op for sockets and QUIC; it is what makes stdout deliver each
        // chunk to ssh instead of holding it in a buffer.
        w.flush().await?;
        counter.fetch_add(n as u64, Ordering::Relaxed);
    }
}

/// Copy both ways until both ways have ended, or until either fails.
///
/// `local_r`/`local_w` are the two halves of whatever is on this machine; the
/// QUIC halves are the stream to the other end. On a clean finish this waits
/// (briefly) for the peer to acknowledge everything we sent, so the caller may
/// close the connection straight after.
///
/// `local_end_grace`: once the local side has ended, how long the other end
/// gets to finish too before this stops waiting for it. For the phone's end,
/// whose local side is sshd: sshd closes the socket outright when a session is
/// over, so nothing the computer sends after that can be delivered anyway, and
/// a computer whose proxy was killed rather than closed — Windows' ssh does
/// that — would otherwise hold the session open until QUIC's idle timeout,
/// and the phone's wake lock with it. `None` keeps full half-close semantics,
/// which the computer's end needs.
pub async fn splice<LR, LW>(
    mut local_r: LR,
    mut local_w: LW,
    mut send: SendStream,
    mut recv: RecvStream,
    traffic: &Traffic,
    local_end_grace: Option<Duration>,
) -> std::io::Result<()>
where
    LR: AsyncRead + Unpin,
    LW: AsyncWrite + Unpin,
{
    let result = {
        let out = pump(&mut local_r, &mut send, &traffic.out);
        let inn = pump(&mut recv, &mut local_w, &traffic.inn);
        tokio::pin!(out, inn);
        let (mut out_done, mut inn_done) = (false, false);
        let mut grace: Option<std::pin::Pin<Box<tokio::time::Sleep>>> = None;
        loop {
            tokio::select! {
                r = &mut out, if !out_done => match r {
                    Ok(()) => {
                        out_done = true;
                        grace = local_end_grace.map(|g| Box::pin(tokio::time::sleep(g)));
                    }
                    Err(e) => break Err(e),
                },
                r = &mut inn, if !inn_done => match r {
                    Ok(()) => inn_done = true,
                    Err(e) => break Err(e),
                },
                () = async { grace.as_mut().expect("guarded").await }, if grace.is_some() && !inn_done => {
                    break Ok(());
                }
            }
            if out_done && inn_done {
                break Ok(());
            }
        }
    };
    if result.is_ok() {
        let _ = tokio::time::timeout(DRAIN, send.stopped()).await;
    }
    result
}
