//! The phone's own screen straight to a browser: WebRTC, for the Remote
//! desktop page on the web ([`crate::proto::DesktopOp::PhoneRtc`]).
//!
//! That page joins the phone's tunnel as WebAssembly, and a browser has no UDP
//! to give iroh: it reaches the phone through a relay, and every frame goes
//! there and back. WebRTC is the one way a browser has to reach a phone
//! directly. The browser offers it over the relayed link it already has, this
//! module answers, and ICE tries every pair of the two ends' addresses:
//!
//! - **Host candidates**: the phone's own addresses (Wi-Fi, mobile data,
//!   IPv6). The same Wi-Fi is one hop. A browser hides its own behind a
//!   `.local` name this side can't look up; its checks to the phone's
//!   addresses find it anyway (a peer-reflexive candidate).
//! - **Server reflexive**: the address the phone's router shows the internet,
//!   asked of a public STUN server, the same ones the browser asks. That is
//!   what crosses two home routers.
//! - **No TURN.** Two strict NATs (some mobile carriers, some offices) leave
//!   no direct pair; the page then keeps the relay, as before. A TURN relay
//!   would be a server to pay for.
//!
//! What goes over it is the phone's stream exactly as [`DesktopOp::Phone`]
//! carries it: one ordered, reliable data channel named `phone`, bytes in
//! chunks, the browser's WebSocket upgrade and frames one way and
//! PhoneStream's the other. The page's viewer, its acknowledgements and the
//! phone's rate control are the same on every route. Ordered and reliable
//! means a lost packet holds up what follows it, as on any other route here;
//! the frames in flight are already held to what the link carries.
//!
//! Only the browser that asked: its offer came over its paired, encrypted
//! link, and DTLS checks the browser's certificate against the fingerprint
//! in that offer. The session ends when the channel closes, the phone's
//! screen ends it, ICE loses the browser for a while, or the browser is
//! removed on the phone (asked every two seconds). Once the channel is up,
//! the relayed link it was set up over can come and go without ending it.
//!
//! str0m is sans IO: one task drives it, with a UDP socket for each address
//! offered, open only while the session lasts.
//!
//! [`DesktopOp::Phone`]: crate::proto::DesktopOp::Phone

use std::collections::HashMap;
use std::future::Future;
use std::net::{IpAddr, SocketAddr};
use std::pin::Pin;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};

use str0m::change::SdpOffer;
use str0m::channel::ChannelId;
use str0m::net::{DatagramRecv, Protocol, Receive};
use str0m::{Candidate, Event, IceConnectionState, Input, Output, Rtc, RtcConfig};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UdpSocket;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;
use tracing::{debug, info};

use crate::phone::PhoneHead;
use crate::pipe::Traffic;
use crate::proto::RtcSignal;

/// Asked for the phone's outside address, as the page asks for its own.
/// Free, public, and nothing of ours to run.
pub const STUN_SERVERS: &[(&str, u16)] = &[("stun.l.google.com", 19302), ("stun.cloudflare.com", 3478)];
/// How long ICE, DTLS and the channel may take before the page is left on the relay.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
/// Lost for this long after it was up (no answer to ICE's checks): gone.
const LOST_TIMEOUT: Duration = Duration::from_secs(8);
/// How long the phone's last bytes may take to reach the browser after its
/// screen has closed, before the session goes anyway.
const LAST_WORD: Duration = Duration::from_millis(1500);
/// Addresses of ours to offer, at most.
const MAX_LOCAL: usize = 8;
/// Candidates from the browser, at most (a browser has a handful).
const MAX_REMOTE: usize = 32;
/// The biggest offer taken (a browser's is a few kilobytes).
pub const MAX_OFFER: usize = 32 * 1024;
/// Packets read but not yet handled, at most: the sockets face every network
/// the phone is on, and a flood mustn't grow memory without end.
const MAX_QUEUED: usize = 1024;
/// One message on the channel, phone → browser, at most.
const CHUNK: usize = 16 * 1024;
/// What SCTP may hold unacknowledged: a key frame and the frames behind it.
const SEND_BUFFER: usize = 2 * 1024 * 1024;
/// Under this, the phone's screen is read again.
const LOW_WATER: usize = 512 * 1024;
/// Chunks read from the phone's screen and not yet taken: past this, its
/// socket is not read, and PhoneStream sees the link as full.
const PHONE_QUEUE: usize = 32;
/// The channel the page opens.
pub const LABEL: &str = "phone";

/// The phone's screen, opened when the browser's channel is: a socket to
/// PhoneStream, as [`crate::proto::DesktopOp::Phone`] opens it.
pub type OpenPhone =
    Box<dyn FnOnce() -> Pin<Box<dyn Future<Output = std::io::Result<tokio::net::UnixStream>> + Send>> + Send>;

/// An offer answered: the answer to send back, and the session to run.
pub struct Answered {
    pub answer: String,
    session: Session,
}

/// Answer a browser's offer on every address of the phone's worth offering.
pub fn answer(offer: &str) -> Result<Answered, String> {
    answer_on(offer, &pick(&interfaces()))
}

/// [`answer`] on the given addresses (a test's 127.0.0.1).
pub fn answer_on(offer: &str, ips: &[IpAddr]) -> Result<Answered, String> {
    if offer.len() > MAX_OFFER {
        return Err("the offer is too big".into());
    }
    let offer = SdpOffer::from_sdp_string(offer).map_err(|e| format!("the offer: {e}"))?;
    if ips.is_empty() {
        return Err("the phone is on no network".into());
    }
    let now = Instant::now();
    let mut rtc = RtcConfig::new().set_sctp_max_buffered_amount(SEND_BUFFER).build(now);
    let mut socks = Vec::new();
    for ip in ips.iter().take(MAX_LOCAL) {
        let sock = match std::net::UdpSocket::bind(SocketAddr::new(*ip, 0)) {
            Ok(s) => s,
            Err(e) => {
                debug!("webrtc: no socket on {}: {e}", masked_ip(*ip));
                continue;
            }
        };
        let Ok(local) = sock.local_addr() else { continue };
        if sock.set_nonblocking(true).is_err() {
            continue;
        }
        let Ok(sock) = UdpSocket::from_std(sock) else { continue };
        match Candidate::host(local, "udp") {
            Ok(c) => {
                if rtc.add_local_candidate(c).is_some() {
                    socks.push((local, Arc::new(sock)));
                }
            }
            Err(e) => debug!("webrtc: not a candidate, {}: {e}", masked(local)),
        }
    }
    if socks.is_empty() {
        return Err("no address of the phone's took a socket".into());
    }
    let answer = rtc.sdp_api().accept_offer(offer).map_err(|e| format!("the offer: {e}"))?;
    Ok(Answered {
        answer: answer.to_sdp_string(),
        session: Session {
            rtc,
            socks,
            born: now,
            up: false,
            lost_at: None,
            channel: None,
            remote: 0,
            ended: None,
            stun: HashMap::new(),
            stun_to: vec![],
            stun_tries: 0,
            stun_next: None,
            srflx: vec![],
            to_phone: None,
            from_phone: None,
            phone_done: false,
            closing: None,
            pending: None,
            full: false,
            head: None,
            open_phone: None,
            phone_tasks: vec![],
            out: None,
            traffic: None,
        },
    })
}

/// What a session needs from the daemon around it.
pub struct Around {
    /// Frames to the browser, over the link the offer came on.
    pub out: mpsc::UnboundedSender<RtcSignal>,
    /// Frames from the browser; closed when that link's stream ended.
    pub inp: mpsc::UnboundedReceiver<RtcSignal>,
    /// The STUN servers' addresses, once looked up.
    pub stun: oneshot::Receiver<Vec<SocketAddr>>,
    /// The phone's screen, opened with the channel.
    pub open_phone: OpenPhone,
    /// The browser's WebSocket upgrade, rewritten with who it is.
    pub head: PhoneHead,
    /// Whether the browser is still paired (a removed one's session ends).
    pub allowed: Box<dyn Fn() -> bool + Send>,
    /// Bytes each way, for the daemon's status.
    pub traffic: Arc<Traffic>,
}

impl Answered {
    /// Run the session to its end, and say why it ended.
    pub async fn run(self, around: Around) -> String {
        let mut s = self.session;
        s.open_phone = Some(around.open_phone);
        s.head = Some(around.head);
        let why = s.run(around.out, around.inp, around.stun, around.allowed, around.traffic).await;
        info!("webrtc: ended ({why})");
        why
    }
}

struct Session {
    rtc: Rtc,
    /// Our sockets, by the address each is bound to (its host candidate).
    socks: Vec<(SocketAddr, Arc<UdpSocket>)>,
    born: Instant,
    up: bool,
    /// When ICE lost the browser, after it had been up.
    lost_at: Option<Instant>,
    channel: Option<ChannelId>,
    remote: usize,
    ended: Option<String>,
    /// Our STUN requests in flight: transaction id → the socket it went from.
    stun: HashMap<[u8; 12], usize>,
    stun_to: Vec<SocketAddr>,
    stun_tries: u32,
    stun_next: Option<Instant>,
    srflx: Vec<SocketAddr>,
    /// Bytes for the phone's screen (browser → phone), to its writer.
    to_phone: Option<mpsc::UnboundedSender<Vec<u8>>>,
    /// Chunks of the phone's screen (phone → browser), from its reader.
    from_phone: Option<mpsc::Receiver<Vec<u8>>>,
    /// The phone's screen has said all it will.
    phone_done: bool,
    /// Since when the session waits for its last bytes to be delivered.
    closing: Option<Instant>,
    /// A chunk SCTP had no room for yet.
    pending: Option<Vec<u8>>,
    /// SCTP is full: the phone's screen waits for its buffer to drain.
    full: bool,
    head: Option<PhoneHead>,
    open_phone: Option<OpenPhone>,
    phone_tasks: Vec<JoinHandle<()>>,
    /// Frames to the browser.
    out: Option<mpsc::UnboundedSender<RtcSignal>>,
    traffic: Option<Arc<Traffic>>,
}

impl Session {
    async fn run(
        &mut self,
        out: mpsc::UnboundedSender<RtcSignal>,
        mut inp: mpsc::UnboundedReceiver<RtcSignal>,
        mut stun: oneshot::Receiver<Vec<SocketAddr>>,
        allowed: Box<dyn Fn() -> bool + Send>,
        traffic: Arc<Traffic>,
    ) -> String {
        self.out = Some(out.clone());
        self.traffic = Some(traffic.clone());
        let (ptx, mut prx) = mpsc::channel::<(usize, SocketAddr, Vec<u8>)>(MAX_QUEUED);
        let readers: Vec<JoinHandle<()>> =
            self.socks.iter().enumerate().map(|(i, (_, s))| tokio::spawn(read(i, s.clone(), ptx.clone()))).collect();
        drop(ptx);
        let mut looking = true;
        let mut signalling = true;
        let mut check = tokio::time::interval(Duration::from_secs(2));
        check.tick().await;

        let why = loop {
            let timeout = match self.drain() {
                Ok(t) => t,
                Err(e) => break e,
            };
            if let Some(why) = self.ended.take() {
                break why;
            }
            if !self.rtc.is_alive() {
                break "closed".into();
            }
            if let Err(e) = self.connect_phone().await {
                break e;
            }
            self.pump();
            if self.phone_done && self.pending.is_none() {
                // The phone's last word (who took over, why it ended) is in
                // SCTP now: gone only once the browser has it, or the page
                // would take the screen back by itself.
                let now = Instant::now();
                let since = *self.closing.get_or_insert(now);
                let left = self.channel.and_then(|id| self.rtc.channel(id)).map_or(0, |mut ch| ch.buffered_amount());
                if left == 0 || now.duration_since(since) > LAST_WORD {
                    break "the phone closed the screen".into();
                }
            }
            let now = Instant::now();
            if !self.up && now.duration_since(self.born) > CONNECT_TIMEOUT {
                break "no way through to the browser".into();
            }
            if self.lost_at.is_some_and(|t| now.duration_since(t) > LOST_TIMEOUT) {
                break "the browser was lost".into();
            }
            let mut wake = timeout;
            if let Some(t) = self.stun_next {
                wake = wake.min(t);
            }
            if !self.up {
                wake = wake.min(self.born + CONNECT_TIMEOUT + Duration::from_millis(10));
            }
            if let Some(t) = self.lost_at {
                wake = wake.min(t + LOST_TIMEOUT + Duration::from_millis(10));
            }
            if self.closing.is_some() {
                wake = wake.min(now + Duration::from_millis(20));
            }
            // The phone's screen is read only while SCTP has room for it.
            let reading = !self.full && self.pending.is_none() && self.from_phone.is_some();
            tokio::select! {
                p = prx.recv() => match p {
                    Some((i, from, data)) => self.packet(i, from, &data),
                    None => break "the network went".into(),
                },
                m = inp.recv(), if signalling => match m {
                    Some(m) => self.signal(m),
                    // Before the channel is up the browser has given up; after,
                    // the relayed link it came on may come and go.
                    None if !self.up => break "the browser went".into(),
                    None => signalling = false,
                },
                c = recv_chunk(&mut self.from_phone), if reading => match c {
                    Some(chunk) => {
                        traffic.out.fetch_add(chunk.len() as u64, Ordering::Relaxed);
                        self.pending = Some(chunk);
                    }
                    None => {
                        self.from_phone = None;
                        self.phone_done = true;
                    }
                },
                found = &mut stun, if looking => {
                    looking = false;
                    if let Ok(found) = found {
                        self.stun_to = found;
                        self.stun_send(Instant::now());
                    }
                }
                _ = check.tick() => {
                    if !allowed() {
                        break "the browser was removed on the phone".into();
                    }
                }
                _ = tokio::time::sleep_until(wake.into()) => {
                    let now = Instant::now();
                    if self.stun_next.is_some_and(|t| now >= t) {
                        self.stun_send(now);
                    }
                    let _ = self.rtc.handle_input(Input::Timeout(now));
                }
            }
        };
        if !self.up {
            let _ = out.send(RtcSignal { error: Some(why.clone()), ..Default::default() });
        }
        for r in readers {
            r.abort();
        }
        for t in self.phone_tasks.drain(..) {
            t.abort();
        }
        self.rtc.disconnect();
        let _ = self.drain();
        why
    }

    /// All str0m has to say, up to its next timeout. Nothing in here changes
    /// str0m: a change must come after a whole drain (str0m's rule), so
    /// changes wait for the loop.
    fn drain(&mut self) -> Result<Instant, String> {
        loop {
            match self.rtc.poll_output() {
                Ok(Output::Timeout(t)) => return Ok(t),
                Ok(Output::Transmit(t)) => self.transmit(t.source, t.destination, &t.contents),
                Ok(Output::Event(e)) => self.event(e),
                Err(e) => return Err(format!("WebRTC: {e}")),
            }
        }
    }

    fn transmit(&self, source: SocketAddr, to: SocketAddr, data: &[u8]) {
        if let Some((_, s)) = self.socks.iter().find(|(a, _)| *a == source) {
            // A full socket buffer loses this one packet, as the network might: SCTP sends it again.
            let _ = s.try_send_to(data, to);
        }
    }

    fn event(&mut self, e: Event) {
        match e {
            Event::Connected => debug!("webrtc: ICE and DTLS up in {} ms", self.born.elapsed().as_millis()),
            Event::IceConnectionStateChange(state) => match state {
                IceConnectionState::Disconnected if self.up => {
                    if self.lost_at.is_none() {
                        debug!("webrtc: the browser stopped answering");
                        self.lost_at = Some(Instant::now());
                    }
                }
                IceConnectionState::Connected | IceConnectionState::Completed => self.lost_at = None,
                _ => {}
            },
            Event::ChannelOpen(id, label) if label == LABEL && self.channel.is_none() => {
                self.channel = Some(id);
                if let Some(mut ch) = self.rtc.channel(id) {
                    ch.set_buffered_amount_low_threshold(LOW_WATER);
                }
                info!("webrtc: straight to the browser in {} ms", self.born.elapsed().as_millis());
                self.up = true;
                if let Some(out) = &self.out {
                    let _ = out.send(RtcSignal { up: true, ..Default::default() });
                }
            }
            Event::ChannelData(d) if Some(d.id) == self.channel => {
                if let Some(t) = &self.traffic {
                    t.inn.fetch_add(d.data.len() as u64, Ordering::Relaxed);
                }
                let Some(head) = self.head.as_mut() else { return };
                match head.feed(&d.data) {
                    Ok(Some(bytes)) => {
                        if let Some(tx) = &self.to_phone {
                            let _ = tx.send(bytes);
                        }
                    }
                    Ok(None) => {}
                    Err(e) => self.ended = Some(e),
                }
            }
            Event::ChannelClose(id) if Some(id) == self.channel => self.ended = Some("the browser closed it".into()),
            Event::ChannelBufferedAmountLow(id) if Some(id) == self.channel => self.full = false,
            _ => {}
        }
    }

    /// The channel is open and the phone's screen not yet: open it, a reader
    /// and a writer for its socket.
    async fn connect_phone(&mut self) -> Result<(), String> {
        if self.channel.is_none() || self.to_phone.is_some() {
            return Ok(());
        }
        let Some(open) = self.open_phone.take() else { return Ok(()) };
        let sock = open().await.map_err(|e| format!("the phone's screen did not answer: {e}"))?;
        let (mut r, mut w) = sock.into_split();
        let (wtx, mut wrx) = mpsc::unbounded_channel::<Vec<u8>>();
        let (rtx, rrx) = mpsc::channel::<Vec<u8>>(PHONE_QUEUE);
        self.phone_tasks.push(tokio::spawn(async move {
            while let Some(bytes) = wrx.recv().await {
                if w.write_all(&bytes).await.is_err() {
                    return;
                }
            }
            let _ = w.shutdown().await;
        }));
        self.phone_tasks.push(tokio::spawn(async move {
            loop {
                let mut buf = vec![0u8; CHUNK];
                match r.read(&mut buf).await {
                    Ok(0) | Err(_) => return,
                    Ok(n) => {
                        buf.truncate(n);
                        if rtx.send(buf).await.is_err() {
                            return;
                        }
                    }
                }
            }
        }));
        self.to_phone = Some(wtx);
        self.from_phone = Some(rrx);
        Ok(())
    }

    /// The chunk waiting, into SCTP if it has room; else the phone's screen
    /// waits for the buffer to drain.
    fn pump(&mut self) {
        let (Some(id), Some(chunk)) = (self.channel, self.pending.as_ref()) else { return };
        let Some(mut ch) = self.rtc.channel(id) else { return };
        match ch.write(true, chunk) {
            Ok(true) => self.pending = None,
            Ok(false) => self.full = true,
            Err(e) => self.ended = Some(format!("the channel: {e}")),
        }
    }

    fn signal(&mut self, m: RtcSignal) {
        let Some(line) = m.candidate else { return };
        if self.remote >= MAX_REMOTE {
            return;
        }
        let line = line.trim().trim_start_matches("a=");
        // A browser's own addresses can come as names (name.local, mDNS) that
        // this side can't look up: those are left out, and its checks to us
        // find it anyway.
        if let Ok(c) = Candidate::from_sdp_string(line) {
            self.remote += 1;
            self.rtc.add_remote_candidate(c);
        }
    }

    fn packet(&mut self, i: usize, from: SocketAddr, data: &[u8]) {
        let Some((local, _)) = self.socks.get(i) else { return };
        let local = *local;
        if !self.stun.is_empty() {
            if let Some(mapped) = stun_mapped(data, |t| self.stun.contains_key(t)) {
                self.stun.clear();
                self.stun_next = None;
                self.found(local, mapped);
                return;
            }
        }
        let Ok(contents) = DatagramRecv::try_from(data) else { return };
        let input = Input::Receive(Instant::now(), Receive { proto: Protocol::Udp, source: from, destination: local, contents });
        // One packet str0m won't take (a stray, a late one) isn't the end of the session.
        let _ = self.rtc.handle_input(input);
    }

    /// The internet's view of one of our addresses, from a STUN server: one
    /// more candidate, for the browser too.
    fn found(&mut self, base: SocketAddr, mapped: SocketAddr) {
        if mapped == base || self.srflx.contains(&mapped) {
            return;
        }
        self.srflx.push(mapped);
        let Ok(c) = Candidate::server_reflexive(mapped, base, "udp") else { return };
        if let Some(c) = self.rtc.add_local_candidate(c) {
            let line = c.to_sdp_string();
            debug!("webrtc: the internet sees the phone at {}", masked(mapped));
            if let Some(out) = &self.out {
                let _ = out.send(RtcSignal { candidate: Some(line), ..Default::default() });
            }
        }
    }

    /// Ask the STUN servers for our IPv4 addresses' outside view (IPv6 ones are their own).
    fn stun_send(&mut self, now: Instant) {
        self.stun_next = None;
        if self.stun_tries >= 3 || !self.srflx.is_empty() {
            return;
        }
        let servers: Vec<SocketAddr> = self.stun_to.iter().filter(|a| a.is_ipv4()).copied().collect();
        if servers.is_empty() {
            return;
        }
        self.stun_tries += 1;
        for (i, (local, s)) in self.socks.iter().enumerate() {
            if !local.is_ipv4() {
                continue;
            }
            for to in &servers {
                let mut tid = [0u8; 12];
                getrandom::fill(&mut tid).expect("the system random source is unavailable");
                if s.try_send_to(&stun_request(tid), *to).is_ok() {
                    self.stun.insert(tid, i);
                }
            }
        }
        self.stun_next = Some(now + Duration::from_millis(400 * self.stun_tries as u64));
    }
}

/// The next chunk of the phone's screen, or None once it has ended.
async fn recv_chunk(rx: &mut Option<mpsc::Receiver<Vec<u8>>>) -> Option<Vec<u8>> {
    match rx {
        Some(rx) => rx.recv().await,
        None => std::future::pending().await,
    }
}

async fn read(i: usize, sock: Arc<UdpSocket>, tx: mpsc::Sender<(usize, SocketAddr, Vec<u8>)>) {
    let mut buf = vec![0u8; 2048];
    loop {
        match sock.recv_from(&mut buf).await {
            Ok((n, from)) => match tx.try_send((i, from, buf[..n].to_vec())) {
                Ok(()) | Err(mpsc::error::TrySendError::Full(_)) => {}
                Err(mpsc::error::TrySendError::Closed(_)) => return,
            },
            // ICMP for a port that went (Linux reports it on the next read): not the socket's end.
            Err(e) if e.kind() == std::io::ErrorKind::ConnectionRefused => continue,
            Err(_) => return,
        }
    }
}

// ── the phone's addresses ─────────────────────────────────────────────────

/// Every up interface's addresses, with the interface's name.
fn interfaces() -> Vec<(String, IpAddr)> {
    let mut out = vec![];
    // SAFETY: getifaddrs hands back a list this reads and then frees, once.
    unsafe {
        let mut ifap: *mut libc::ifaddrs = std::ptr::null_mut();
        if libc::getifaddrs(&mut ifap) != 0 {
            return out;
        }
        let mut p = ifap;
        while !p.is_null() {
            let ifa = &*p;
            p = ifa.ifa_next;
            if ifa.ifa_addr.is_null() || ifa.ifa_flags & (libc::IFF_UP as u32) == 0 {
                continue;
            }
            let name = std::ffi::CStr::from_ptr(ifa.ifa_name).to_string_lossy().to_string();
            let ip = match (*ifa.ifa_addr).sa_family as i32 {
                libc::AF_INET => {
                    let sin = &*(ifa.ifa_addr as *const libc::sockaddr_in);
                    IpAddr::from(std::net::Ipv4Addr::from(u32::from_be(sin.sin_addr.s_addr)))
                }
                libc::AF_INET6 => {
                    let sin6 = &*(ifa.ifa_addr as *const libc::sockaddr_in6);
                    IpAddr::from(std::net::Ipv6Addr::from(sin6.sin6_addr.s6_addr))
                }
                _ => continue,
            };
            out.push((name, ip));
        }
        libc::freeifaddrs(ifap);
    }
    out
}

/// The addresses to offer: Wi-Fi first, then a cable, then mobile data, then
/// the rest. Never loopback, link-local, the 464XLAT stand-in Android names
/// `v4-…` (its traffic is the mobile network's own IPv6), Wi-Fi Direct and
/// Wi-Fi Aware, or a tethered phone's hotspot side.
fn pick(found: &[(String, IpAddr)]) -> Vec<IpAddr> {
    let mut ranked: Vec<(u8, IpAddr)> = vec![];
    for (name, ip) in found {
        if name == "lo" || ["v4-", "clat", "dummy", "p2p", "aware", "swlan", "ip6tnl", "sit", "ifb"].iter().any(|p| name.starts_with(p)) {
            continue;
        }
        let ok = match ip {
            IpAddr::V4(a) => !(a.is_loopback() || a.is_link_local() || a.is_unspecified() || a.is_multicast() || a.is_broadcast()),
            IpAddr::V6(a) => {
                let first = a.segments()[0];
                // Global (2000::/3) or unique local (fc00::/7); not link-local, loopback, multicast.
                (first & 0xe000) == 0x2000 || (first & 0xfe00) == 0xfc00
            }
        };
        if !ok || ranked.iter().any(|(_, a)| a == ip) {
            continue;
        }
        let rank = if name.starts_with("wlan") {
            0
        } else if name.starts_with("eth") || name.starts_with("usb") || name.starts_with("rndis") {
            1
        } else if name.starts_with("rmnet") || name.starts_with("ccmni") || name.starts_with("seth") {
            2
        } else {
            3
        };
        // IPv4 before IPv6 on the same network: home networks and browsers do IPv4 everywhere.
        let rank = rank * 2 + ip.is_ipv6() as u8;
        ranked.push((rank, *ip));
    }
    ranked.sort_by_key(|(r, _)| *r);
    ranked.into_iter().map(|(_, ip)| ip).take(MAX_LOCAL).collect()
}

/// An address for the log: the network, not the whole address (logs get pasted).
pub fn masked(a: SocketAddr) -> String {
    masked_ip(a.ip())
}

fn masked_ip(ip: IpAddr) -> String {
    match ip {
        IpAddr::V4(v) => {
            let o = v.octets();
            format!("{}.{}.x.x", o[0], o[1])
        }
        IpAddr::V6(v) => format!("{:x}:{:x}:…", v.segments()[0], v.segments()[1]),
    }
}

// ── STUN, just enough to ask "how do you see me" ──────────────────────────

const STUN_COOKIE: [u8; 4] = [0x21, 0x12, 0xa4, 0x42];

/// A binding request (RFC 8489) with this transaction id, no attributes.
fn stun_request(tid: [u8; 12]) -> [u8; 20] {
    let mut m = [0u8; 20];
    m[0..2].copy_from_slice(&[0x00, 0x01]);
    m[2..4].copy_from_slice(&[0x00, 0x00]);
    m[4..8].copy_from_slice(&STUN_COOKIE);
    m[8..20].copy_from_slice(&tid);
    m
}

/// The address in a successful binding response to one of our requests
/// (`ours` knows their ids): XOR-MAPPED-ADDRESS, or the old MAPPED-ADDRESS.
fn stun_mapped(buf: &[u8], ours: impl Fn(&[u8; 12]) -> bool) -> Option<SocketAddr> {
    if buf.len() < 20 || buf[0..2] != [0x01, 0x01] || buf[4..8] != STUN_COOKIE {
        return None;
    }
    let mut tid = [0u8; 12];
    tid.copy_from_slice(&buf[8..20]);
    if !ours(&tid) {
        return None;
    }
    let len = u16::from_be_bytes([buf[2], buf[3]]) as usize;
    let body = buf.get(20..20 + len)?;
    let mut plain = None;
    let mut i = 0;
    while i + 4 <= body.len() {
        let kind = u16::from_be_bytes([body[i], body[i + 1]]);
        let n = u16::from_be_bytes([body[i + 2], body[i + 3]]) as usize;
        let v = body.get(i + 4..i + 4 + n)?;
        match kind {
            0x0020 => return address(v, Some((&STUN_COOKIE, &tid))),
            0x0001 => plain = address(v, None),
            _ => {}
        }
        i += 4 + n.div_ceil(4) * 4;
    }
    plain
}

fn address(v: &[u8], xor: Option<(&[u8; 4], &[u8; 12])>) -> Option<SocketAddr> {
    if v.len() < 4 {
        return None;
    }
    let mut port = u16::from_be_bytes([v[2], v[3]]);
    let mut key = [0u8; 16];
    if let Some((cookie, tid)) = xor {
        key[..4].copy_from_slice(cookie);
        key[4..].copy_from_slice(tid);
        port ^= u16::from_be_bytes([cookie[0], cookie[1]]);
    }
    match v[1] {
        0x01 if v.len() >= 8 => {
            let mut o = [0u8; 4];
            for k in 0..4 {
                o[k] = v[4 + k] ^ key[k];
            }
            Some(SocketAddr::new(IpAddr::from(o), port))
        }
        0x02 if v.len() >= 20 => {
            let mut o = [0u8; 16];
            for k in 0..16 {
                o[k] = v[4 + k] ^ key[k];
            }
            Some(SocketAddr::new(IpAddr::from(o), port))
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn offers_wifi_before_mobile_and_skips_android_stand_ins() {
        let found = vec![
            ("lo".to_string(), "127.0.0.1".parse().unwrap()),
            ("rmnet_data1".to_string(), "10.20.30.40".parse().unwrap()),
            ("v4-rmnet_data1".to_string(), "192.0.0.4".parse().unwrap()),
            ("rmnet_data1".to_string(), "2a00:1:2:3::5".parse().unwrap()),
            ("wlan0".to_string(), "192.168.1.20".parse().unwrap()),
            ("wlan0".to_string(), "fe80::1".parse().unwrap()),
            ("dummy0".to_string(), "10.0.0.1".parse().unwrap()),
        ];
        let picked = pick(&found);
        let want: Vec<IpAddr> =
            vec!["192.168.1.20".parse().unwrap(), "10.20.30.40".parse().unwrap(), "2a00:1:2:3::5".parse().unwrap()];
        assert_eq!(picked, want);
    }

    #[test]
    fn reads_the_address_a_stun_server_saw() {
        let tid = [7u8; 12];
        // XOR-MAPPED-ADDRESS for 203.0.113.9:40000.
        let ip = [203u8, 0, 113, 9];
        let port: u16 = 40000;
        let xport = port ^ 0x2112;
        let mut attr = vec![0x00, 0x20, 0x00, 0x08, 0x00, 0x01];
        attr.extend_from_slice(&xport.to_be_bytes());
        for k in 0..4 {
            attr.push(ip[k] ^ STUN_COOKIE[k]);
        }
        let mut msg = vec![0x01, 0x01];
        msg.extend_from_slice(&(attr.len() as u16).to_be_bytes());
        msg.extend_from_slice(&STUN_COOKIE);
        msg.extend_from_slice(&tid);
        msg.extend_from_slice(&attr);
        assert_eq!(stun_mapped(&msg, |t| *t == tid), Some("203.0.113.9:40000".parse().unwrap()));
        assert_eq!(stun_mapped(&msg, |_| false), None, "not one of ours");
    }
}
