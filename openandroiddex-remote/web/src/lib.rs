//! Remote desktop in a browser, from anywhere: the phone's tunnel (iroh) in
//! WebAssembly, for a page that is not on the phone's network.
//!
//! The browser is a computer like any other to the phone: it has a key of its
//! own (kept by the page), pairs with the eight-character code from the
//! phone's Remote access page, and then opens the phone's screen with the
//! daemon's `phone` op on `desktop/1`, as the desktop app's window does. What
//! comes back is PhoneStream's bytes: the page speaks the WebSocket upgrade and
//! frames over them itself (anywhere.js), and the viewer is the same page code
//! as on the phone's Wi-Fi.
//!
//! A browser has no UDP, so iroh reaches the phone through a relay (n0's
//! public ones, as everything else here): no server of ours, nothing to pay,
//! and slower than a direct path. So the page first asks for a direct one:
//! WebRTC, signalled over this same relayed link ([`Browser::rtc`]), the
//! media then straight between the browser and the phone wherever the two
//! networks allow (the phone's end is the daemon's src/rtc.rs). Where they
//! don't, the relay carries the picture as before.
//!
//! The pairing and the frames are the daemon's own code, compiled in here:
//! the crypto, the code's keys, the wire, the SSH key line the pairing carries.

#[path = "../../src/crypto.rs"]
mod crypto;
#[path = "../../src/pairing.rs"]
mod pairing;
#[path = "../../src/proto.rs"]
mod proto;
#[path = "../../src/sshkey.rs"]
mod sshkey;

use std::rc::Rc;

use iroh::endpoint::{RecvStream, SendStream};
use iroh::{Endpoint, EndpointId, SecretKey};
use tokio::io::AsyncWriteExt;
use tokio::sync::Mutex;
use wasm_bindgen::prelude::*;

fn js<E: std::fmt::Display>(e: E) -> JsError {
    JsError::new(&e.to_string())
}

fn hex(b: &[u8]) -> String {
    data_encoding::HEXLOWER.encode(b)
}

/// This browser, as the phone knows it.
#[wasm_bindgen]
pub struct Browser {
    ep: Endpoint,
    key: SecretKey,
}

#[wasm_bindgen]
impl Browser {
    /// The browser's endpoint: `secret` is the 64 hex characters the page
    /// kept from before, or "" for a browser that has none yet.
    pub async fn open(secret: String) -> Result<Browser, JsError> {
        console_error_panic_hook::set_once();
        let mut seed = [0u8; 32];
        match data_encoding::HEXLOWER_PERMISSIVE.decode(secret.as_bytes()) {
            Ok(b) if b.len() == 32 => seed.copy_from_slice(&b),
            _ => getrandom::fill(&mut seed).map_err(js)?,
        }
        let key = SecretKey::from_bytes(&seed);
        let ep = Endpoint::builder(iroh::endpoint::presets::N0)
            .secret_key(key.clone())
            .bind()
            .await
            .map_err(js)?;
        Ok(Browser { ep, key })
    }

    /// What the page keeps, to be this browser again next time.
    pub fn secret(&self) -> String {
        hex(&self.key.to_bytes())
    }

    pub fn id(&self) -> String {
        self.ep.id().to_string()
    }

    /// Pair with the code the phone shows (Remote access → Add a computer):
    /// answers `{"id":…,"name":…}`, the phone to open from now on.
    pub async fn pair(&self, code: String, name: String) -> Result<String, JsError> {
        let keys = pairing::PairKeys::derive(&code).ok_or_else(|| JsError::new("That is not a code from the phone."))?;
        let target = keys.endpoint_id();
        let mut tries = 0;
        let conn = loop {
            match self.ep.connect(target, proto::ALPN_PAIR).await {
                Ok(conn) => break conn,
                Err(e) => {
                    tries += 1;
                    if tries >= 8 {
                        return Err(JsError::new(&format!("The phone did not answer for that code ({e}).")));
                    }
                    n0_future::time::sleep(std::time::Duration::from_secs(2)).await;
                }
            }
        };
        let me = self.ep.id();
        let os = "browser";
        // The pairing carries an SSH key for the phone's Linux; a browser has
        // no SSH, and gives the public half of its own key, which nothing uses.
        let ssh_key = sshkey::public_line(me.as_bytes(), "browser");
        let request = proto::PairRequest {
            v: 1,
            name: name.clone(),
            os: os.to_string(),
            ssh_key: ssh_key.clone(),
            proof: hex(&keys.computer_proof(&me, &name, os, &ssh_key)),
            auth: None,
        };
        let (mut send, mut recv) = conn.open_bi().await.map_err(js)?;
        proto::write_frame(&mut send, &request).await.map_err(js)?;
        let _ = send.finish();
        let response: proto::PairResponse = proto::read_frame(&mut recv).await.map_err(js)?;
        conn.close(0u32.into(), b"done");
        if !response.ok {
            return Err(JsError::new(match response.error {
                Some(proto::PairError::BadCode) => "That code did not work. Check the code on the phone.",
                Some(proto::PairError::Expired) => "That code ran out. Make a new one on the phone.",
                Some(proto::PairError::Rejected) => "The phone said no.",
                _ => "The phone could not pair.",
            }));
        }
        let id: EndpointId = response
            .id
            .as_deref()
            .and_then(|s| s.parse().ok())
            .ok_or_else(|| JsError::new("The phone's answer was not understood."))?;
        let host_key = response.host_key.clone().unwrap_or_default();
        let expected = keys.phone_proof(&id, &me, &host_key);
        if !pairing::PairKeys::verify(&expected, response.proof.as_deref().unwrap_or("")) {
            return Err(JsError::new("The phone's answer did not prove it knows the code."));
        }
        let name = response.name.clone().unwrap_or_default();
        Ok(serde_json::json!({ "id": id.to_string(), "name": name }).to_string())
    }

    /// The phone's screen: its tunnel's `phone` op, then PhoneStream's bytes
    /// both ways ([`PhoneLink`]).
    pub async fn phone(&self, phone: String) -> Result<PhoneLink, JsError> {
        let id: EndpointId = phone.parse().map_err(js)?;
        let conn = self.ep.connect(id, proto::ALPN_DESKTOP).await.map_err(js)?;
        let (mut send, mut recv) = conn.open_bi().await.map_err(js)?;
        let hello = proto::DesktopHello { v: 1, auth: None, op: proto::DesktopOp::Phone, start: None };
        proto::write_frame(&mut send, &hello).await.map_err(js)?;
        let reply: proto::DesktopReply = proto::read_frame(&mut recv).await.map_err(js)?;
        if !reply.ok {
            let why = match reply.refusal {
                Some(proto::Refusal::PhoneUnavailable) => "Remote desktop is off on the phone.".to_string(),
                other => format!("The phone said no ({other:?})."),
            };
            return Err(JsError::new(&why));
        }
        Ok(PhoneLink {
            send: Rc::new(Mutex::new(send)),
            recv: Rc::new(Mutex::new(recv)),
            conn,
        })
    }

    /// The phone's screen straight to this browser, by WebRTC: this opens the
    /// phone's `phone-rtc` op and gives the page the stream its offer, the
    /// phone's answer and both ends' candidates go over ([`RtcLink`]). The
    /// page makes the RTCPeerConnection; the media never crosses this link.
    pub async fn rtc(&self, phone: String) -> Result<RtcLink, JsError> {
        let id: EndpointId = phone.parse().map_err(js)?;
        let conn = self.ep.connect(id, proto::ALPN_DESKTOP).await.map_err(js)?;
        let (mut send, mut recv) = conn.open_bi().await.map_err(js)?;
        let hello = proto::DesktopHello { v: 1, auth: None, op: proto::DesktopOp::PhoneRtc, start: None };
        proto::write_frame(&mut send, &hello).await.map_err(js)?;
        let reply: proto::DesktopReply = proto::read_frame(&mut recv).await.map_err(js)?;
        if !reply.ok {
            return Err(JsError::new(&format!("The phone offers no direct way ({:?}).", reply.refusal)));
        }
        Ok(RtcLink {
            send: Rc::new(Mutex::new(send)),
            recv: Rc::new(Mutex::new(recv)),
            conn,
        })
    }
}

/// The signalling of one WebRTC connection to the phone: [`proto::RtcSignal`]
/// frames, as JSON text, both ways.
#[wasm_bindgen]
pub struct RtcLink {
    send: Rc<Mutex<SendStream>>,
    recv: Rc<Mutex<RecvStream>>,
    conn: iroh::endpoint::Connection,
}

#[wasm_bindgen]
impl RtcLink {
    /// One message to the phone: `{"offer":…}` first, then `{"candidate":…}`.
    pub fn send(&self, json: String) -> js_sys::Promise {
        let send = self.send.clone();
        wasm_bindgen_futures::future_to_promise(async move {
            let m: proto::RtcSignal = serde_json::from_str(&json).map_err(|e| JsValue::from_str(&e.to_string()))?;
            let mut s = send.lock().await;
            proto::write_frame(&mut *s, &m).await.map_err(|e| JsValue::from_str(&e.to_string()))?;
            Ok(JsValue::UNDEFINED)
        })
    }

    /// The phone's next message, as JSON text, or null once it has said all.
    pub fn recv(&self) -> js_sys::Promise {
        let recv = self.recv.clone();
        wasm_bindgen_futures::future_to_promise(async move {
            let mut r = recv.lock().await;
            match proto::read_frame::<_, proto::RtcSignal>(&mut *r).await {
                Ok(m) => Ok(JsValue::from_str(&serde_json::to_string(&m).unwrap_or_default())),
                Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => Ok(JsValue::NULL),
                Err(e) => Err(JsValue::from_str(&e.to_string())),
            }
        })
    }

    pub fn close(&self) {
        self.conn.close(0u32.into(), b"closed");
    }
}

/// One open screen: bytes to the phone and from it.
#[wasm_bindgen]
pub struct PhoneLink {
    send: Rc<Mutex<SendStream>>,
    recv: Rc<Mutex<RecvStream>>,
    conn: iroh::endpoint::Connection,
}

#[wasm_bindgen]
impl PhoneLink {
    /// Bytes to the phone, in order.
    pub fn write(&self, bytes: Vec<u8>) -> js_sys::Promise {
        let send = self.send.clone();
        wasm_bindgen_futures::future_to_promise(async move {
            let mut s = send.lock().await;
            s.write_all(&bytes).await.map_err(|e| JsValue::from_str(&e.to_string()))?;
            Ok(JsValue::UNDEFINED)
        })
    }

    /// The next bytes from the phone, or null when it closed.
    pub fn read(&self) -> js_sys::Promise {
        let recv = self.recv.clone();
        wasm_bindgen_futures::future_to_promise(async move {
            let mut r = recv.lock().await;
            match r.read_chunk(256 * 1024).await {
                Ok(Some(chunk)) => Ok(js_sys::Uint8Array::from(&chunk[..]).into()),
                Ok(None) => Ok(JsValue::NULL),
                Err(e) => Err(JsValue::from_str(&e.to_string())),
            }
        })
    }

    pub fn close(&self) {
        self.conn.close(0u32.into(), b"closed");
    }
}
