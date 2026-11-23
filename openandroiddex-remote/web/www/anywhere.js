// Remote desktop from anywhere, in a browser with nothing installed: this page
// joins the phone's own tunnel (iroh, as WebAssembly: ../src/lib.rs) and shows
// the phone's screen with the same viewer as on the phone's Wi-Fi (phone.js).
//
// The first time, the page pairs with the eight-character code the phone shows
// under Remote access → Add a computer. The phone lets a browser in to its own
// screen and nothing else (no Linux, no SSH). The page keeps its key and the
// phone it paired with in this browser's storage, and opens that phone from
// then on; "Forget this phone" takes both away.
//
// The picture goes straight between the browser and the phone where the two
// networks allow it: WebRTC, a data channel the phone's daemon answers
// (src/rtc.rs there), set up over the tunnel. Where it can't (two strict NATs,
// UDP blocked), the tunnel's relay carries it, as a browser has no UDP of its
// own for iroh. Nothing to run, nothing to pay either way.
import init, { Browser } from './pkg/dexremote_web.js';

const $ = (id) => document.getElementById(id);
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch { /* private window */ } },
};

/** Asked for this browser's outside address, as the phone asks for its own: free and public. */
const STUN = ['stun:stun.l.google.com:19302', 'stun:stun.cloudflare.com:3478'];
/** How long a direct connection may take before the relay carries the picture. */
const DIRECT_WAIT_MS = 6000;

/**
 * A WebSocket, as phone.js uses one, over a byte link to the phone's screen:
 * the upgrade request and its answer, then RFC 6455 frames, ours masked as a
 * client's. The link is whatever `opener` gives: a WebRTC data channel, or
 * the tunnel's relayed stream; both carry the same bytes.
 */
class TunnelSocket {
  constructor(opener) {
    this.readyState = 0;
    this.binaryType = 'arraybuffer';
    this.onopen = this.onmessage = this.onclose = this.onerror = null;
    this.link = null;
    this.buf = new Uint8Array(0);
    void this.start(opener);
  }

  async start(opener) {
    try {
      this.link = await opener();
      if (this.readyState === 3) { this.link.close(); return; }
      const key = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(16))));
      await this.link.write(new TextEncoder().encode(
        `GET /phone HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n`
        + `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`));
      // The answer's head, up to the blank line; what follows is frames.
      for (;;) {
        const end = this.headEnd();
        if (end >= 0) {
          const head = new TextDecoder().decode(this.buf.subarray(0, end));
          this.buf = this.buf.subarray(end + 4);
          if (!/^HTTP\/1\.1 101/.test(head)) throw new Error('the phone did not take the stream');
          break;
        }
        if (!(await this.more())) throw new Error('the phone closed');
      }
      this.readyState = 1;
      this.onopen && this.onopen();
      await this.frames();
    } catch (e) {
      this.onerror && this.onerror(e);
    }
    this.finish();
  }

  headEnd() {
    for (let i = 0; i + 3 < this.buf.length; i++) {
      if (this.buf[i] === 13 && this.buf[i + 1] === 10 && this.buf[i + 2] === 13 && this.buf[i + 3] === 10) return i;
    }
    return -1;
  }

  async more() {
    const chunk = await this.link.read();
    if (!chunk) return false;
    const next = new Uint8Array(this.buf.length + chunk.length);
    next.set(this.buf);
    next.set(chunk, this.buf.length);
    this.buf = next;
    return true;
  }

  async need(n) {
    while (this.buf.length < n) if (!(await this.more())) return false;
    return true;
  }

  async frames() {
    let parts = [];
    let partOp = 0;
    for (;;) {
      if (!(await this.need(2))) return;
      const b0 = this.buf[0], b1 = this.buf[1];
      let len = b1 & 0x7f, at = 2;
      if (len === 126) {
        if (!(await this.need(4))) return;
        len = (this.buf[2] << 8) | this.buf[3];
        at = 4;
      } else if (len === 127) {
        if (!(await this.need(10))) return;
        len = Number(new DataView(this.buf.buffer, this.buf.byteOffset + 2, 8).getBigUint64(0));
        at = 10;
      }
      if (!(await this.need(at + len))) return;
      const payload = this.buf.slice(at, at + len);
      this.buf = this.buf.subarray(at + len);
      const op = b0 & 0x0f, fin = (b0 & 0x80) !== 0;
      if (op === 8) return;                       // the phone's close
      if (op === 9) { this.frame(10, payload); continue; } // ping
      if (op === 10) continue;
      if (op === 0) { parts.push(payload); } else { parts = [payload]; partOp = op; }
      if (!fin) continue;
      const whole = parts.length === 1 ? parts[0] : concat(parts);
      parts = [];
      const data = partOp === 1 ? new TextDecoder().decode(whole) : whole.buffer;
      this.onmessage && this.onmessage({ data });
    }
  }

  frame(op, payload) {
    if (!this.link) return;
    const n = payload.length;
    const head = n < 126 ? 2 : n < 65536 ? 4 : 10;
    const out = new Uint8Array(head + 4 + n);
    out[0] = 0x80 | op;
    if (n < 126) out[1] = 0x80 | n;
    else if (n < 65536) { out[1] = 0x80 | 126; out[2] = n >> 8; out[3] = n & 0xff; }
    else { out[1] = 0x80 | 127; new DataView(out.buffer).setBigUint64(2, BigInt(n)); }
    const mask = crypto.getRandomValues(new Uint8Array(4));
    out.set(mask, head);
    for (let i = 0; i < n; i++) out[head + 4 + i] = payload[i] ^ mask[i & 3];
    // Remembered: close() lets what was written go before the link does.
    const written = Promise.resolve(this.link.write(out)).catch(() => this.finish());
    this.written = this.written ? Promise.all([this.written, written]) : written;
  }

  send(m) {
    if (this.readyState !== 1) return;
    if (typeof m === 'string') this.frame(1, new TextEncoder().encode(m));
    else this.frame(2, m instanceof Uint8Array ? m : new Uint8Array(m));
  }

  /**
   * The close frame, then the link, once what was written before it has gone
   * (the page's goodbye, {"t":"bye"}, above all): until 2026-11-22 the link
   * was closed in the same moment, and the goodbye could be lost with it. A
   * page going away has little time, so a third of a second at most.
   */
  close() {
    if (this.readyState === 3 || this.readyState === 2) return;
    if (this.readyState === 1) this.frame(8, new Uint8Array(0));
    this.readyState = 2;
    const sent = this.written || Promise.resolve();
    void Promise.race([sent, new Promise((r) => setTimeout(r, 300))]).then(() => this.finish());
  }

  finish() {
    if (this.readyState === 3) return;
    this.readyState = 3;
    try { this.link && this.link.close(); } catch { /* gone */ }
    this.onclose && this.onclose({ code: 1006 });
  }
}

function concat(parts) {
  const out = new Uint8Array(parts.reduce((a, p) => a + p.length, 0));
  let at = 0;
  for (const p of parts) { out.set(p, at); at += p.length; }
  return out;
}

/** The phone's screen as bytes over a WebRTC data channel: read() and write() as the relayed link has them. */
class ChannelLink {
  constructor(pc, dc, sig) {
    this.pc = pc;
    this.dc = dc;
    this.sig = sig;
    this.queue = [];
    this.waiting = null;
    this.ended = false;
    dc.onmessage = (e) => this.push(new Uint8Array(e.data));
    dc.onclose = () => this.end();
    pc.addEventListener('connectionstatechange', () => {
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed') this.end();
    });
  }

  push(b) {
    if (this.waiting) { const w = this.waiting; this.waiting = null; w(b); } else this.queue.push(b);
  }

  end() {
    if (this.ended) return;
    this.ended = true;
    if (this.waiting) { const w = this.waiting; this.waiting = null; w(null); }
  }

  read() {
    if (this.queue.length) return Promise.resolve(this.queue.shift());
    if (this.ended) return Promise.resolve(null);
    return new Promise((r) => { this.waiting = r; });
  }

  async write(bytes) {
    if (this.dc.readyState !== 'open') throw new Error('the direct link closed');
    this.dc.send(bytes);
  }

  /**
   * The channel first, which sends what it still holds before it closes; the
   * connection under it once the channel has closed, or in half a second.
   * Closing the connection at once could drop the page's last words.
   */
  close() {
    try { this.dc.close(); } catch { /* gone */ }
    const rest = () => {
      try { this.pc.close(); } catch { /* gone */ }
      try { this.sig.close(); } catch { /* gone */ }
    };
    if (this.dc.readyState === 'closed') rest();
    else {
      this.dc.addEventListener('close', rest, { once: true });
      setTimeout(rest, 500);
    }
    this.end();
  }
}

/**
 * Straight to the phone, if the two networks let a pair of addresses through:
 * an offer over the tunnel, the phone's answer and candidates back, and the
 * data channel open within DIRECT_WAIT_MS. Throws otherwise, and leaves
 * nothing open.
 */
async function directLink(browser, phone) {
  if (typeof RTCPeerConnection !== 'function') throw new Error('no WebRTC in this browser');
  const sig = await browser.rtc(phone);
  const pc = new RTCPeerConnection({ iceServers: [{ urls: STUN }] });
  const dc = pc.createDataChannel('phone', { ordered: true });
  dc.binaryType = 'arraybuffer';
  // Candidates found before the offer is on its way wait for it: the phone takes the offer first.
  let offered = false;
  const early = [];
  const tell = (line) => { void sig.send(JSON.stringify({ candidate: line })).catch(() => {}); };
  pc.onicecandidate = (e) => {
    if (!e.candidate || !e.candidate.candidate) return;
    if (offered) tell(e.candidate.candidate); else early.push(e.candidate.candidate);
  };
  const cleanup = () => {
    try { dc.close(); } catch { /* gone */ }
    try { pc.close(); } catch { /* gone */ }
    try { sig.close(); } catch { /* gone */ }
  };
  try {
    await pc.setLocalDescription(await pc.createOffer());
    await sig.send(JSON.stringify({ offer: pc.localDescription.sdp }));
    offered = true;
    for (const line of early.splice(0)) tell(line);
    const opened = new Promise((resolve, reject) => {
      dc.onopen = resolve;
      pc.addEventListener('connectionstatechange', () => {
        if (pc.connectionState === 'failed') reject(new Error('no direct way to the phone'));
      });
    });
    // The phone's answer, then its later candidates, for as long as it sends any.
    let up = false;
    const listen = (async () => {
      for (;;) {
        const text = await sig.recv();
        if (text == null) { if (!up) throw new Error('the phone ended it'); return; }
        const m = JSON.parse(text);
        if (m.answer) await pc.setRemoteDescription({ type: 'answer', sdp: m.answer });
        else if (m.candidate) await pc.addIceCandidate({ candidate: m.candidate, sdpMLineIndex: 0 }).catch(() => {});
        else if (m.error && !up) throw new Error(m.error);
      }
    })();
    const late = new Promise((_, reject) => setTimeout(() => reject(new Error('no direct way in time')), DIRECT_WAIT_MS));
    await Promise.race([opened, late, listen]);
    up = true;
    listen.catch(() => { /* after it is up, the tunnel's stream may come and go */ });
    return new ChannelLink(pc, dc, sig);
  } catch (e) {
    cleanup();
    throw e;
  }
}

async function main() {
  $('words').textContent = 'Starting…';
  await init();
  const browser = await Browser.open(store.get('dex.anywhere.key') || '');
  store.set('dex.anywhere.key', browser.secret());
  let phone = store.get('dex.anywhere.phone');
  if (!phone) {
    $('words').textContent = 'On the phone, open LinuxOnDroid → Settings → Remote desktop → Add a computer, and type the code it shows.';
    $('apair').hidden = false;
    $('acode').focus();
    phone = await new Promise((resolve) => {
      $('apair').onsubmit = async (e) => {
        e.preventDefault();
        $('apair-error').textContent = '';
        $('apair-go').disabled = true;
        try {
          const name = `Browser on ${navigator.platform || 'a computer'}`;
          const r = JSON.parse(await browser.pair($('acode').value, name));
          store.set('dex.anywhere.phone', r.id);
          store.set('dex.anywhere.name', r.name || '');
          resolve(r.id);
        } catch (err) {
          $('apair-error').textContent = String(err && err.message || err);
        }
        $('apair-go').disabled = false;
      };
    });
  }
  $('start').hidden = true;
  $('forget').hidden = false;
  window.PHONE_SOCKET = () => new TunnelSocket(async () => {
    try {
      const link = await directLink(browser, phone);
      window.PHONE_ROUTE = 'direct';
      return link;
    } catch (e) {
      console.log(`direct: ${e && e.message || e}; the relay carries the picture`);
      window.PHONE_ROUTE = 'through a relay';
      return browser.phone(phone);
    }
  });
  await import('./phone.js');
}

// This browser forgets the phone and its own key; the phone still lists the
// browser under Remote desktop until it is removed there.
$('forget').onclick = () => {
  if (!confirm('Forget this phone? This browser then needs a new code from the phone. On the phone, remove this browser under Remote desktop too.')) return;
  store.set('dex.anywhere.phone', null);
  store.set('dex.anywhere.name', null);
  store.set('dex.anywhere.key', null);
  location.reload();
};
main().catch((e) => { $('words').textContent = `Could not start: ${e && e.message || e}`; });
