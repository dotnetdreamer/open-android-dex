# openandroiddex-signal

The rendezvous for the Web viewer's WebRTC transport. About 400 lines of Node,
no dependencies, meant to sit on the box that already runs your coturn.

## What it is for

The launcher's Web viewer serves its own page and streams over a WebSocket, and
that is all you need on a home network or behind a tunnel. It stops working the
moment the phone has no address anyone can open — on mobile data, where
carrier-grade NAT gives it no inbound path, there is no port on a router to
forward and no tunnel client on the phone.

What the phone can always do is dial **out**. So it dials out to this, announces
a room, and waits. A browser opens the same room here, the two exchange one
offer and one answer through it, and everything after that — video, clicks,
typing, files — goes peer-to-peer, or through your TURN server when neither end
is directly reachable.

This process therefore carries a few kilobytes per session and nothing else:

- It relays SDP and ICE candidates. It has no opinion about their contents.
- **It never sees the access code.** The phone checks that over the encrypted
  data channel, after the connection exists. A compromised relay still cannot
  watch anyone's screen.
- **It never sees the room in a request.** The browser reads the room from the
  URL fragment, which browsers do not send to servers, and hands it over on the
  socket instead — so rooms do not accumulate in an access log.

It also serves the viewer page, because in this kind of session there is nowhere
else for the page to come from, and `/ice`, because the browser needs ICE
servers before it can answer and this box is the one that knows them.

## Running it

```sh
node server.js
```

| Variable | Meaning |
| --- | --- |
| `PORT` | listen port (default `8788`) |
| `HOST` | bind address (default `0.0.0.0`) |
| `PUBLIC_DIR` | where the viewer page lives; defaults to the launcher's `assets/web` when run from a checkout |
| `STUN_URL` | comma-separated STUN urls (default Google's) |
| `TURN_URL` | e.g. `turn:turn.example.com:3478` |
| `TURN_SECRET` | coturn's `static-auth-secret` — **the recommended option** |
| `TURN_USER` / `TURN_PASS` | long-term credentials, used only when there is no secret |
| `TURN_TTL` | seconds a minted credential lasts (default `86400`) |

With `TURN_SECRET` set, `/ice` mints coturn's REST-style credentials: the
username is an expiry timestamp and the password is its HMAC under the shared
secret. Nothing long-lived ever reaches a browser, and revoking is a matter of
rotating one secret. That is why it is the documented path.

The matching coturn side is the usual:

```
use-auth-secret
static-auth-secret=the-same-secret
realm=turn.example.com
```

### Behind TLS

Put it behind whatever already terminates TLS on that host. A browser will not
open a `wss://` socket from an `https://` page otherwise, and the phone verifies
the certificate hostname before it will speak.

```nginx
location / {
    proxy_pass http://127.0.0.1:8788;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 1h;          # the phone's socket is idle between viewers
}
```

The `proxy_read_timeout` matters: this connection is *supposed* to sit silent
for hours. The phone pings every 25 seconds to keep NAT bindings alive, but a
60-second proxy timeout will still cut a session that is merely waiting.

### As a service

```ini
[Unit]
Description=Open Android DeX rendezvous
After=network.target

[Service]
ExecStart=/usr/bin/node /opt/openandroiddex-signal/server.js
Environment=PORT=8788
Environment=HOST=127.0.0.1
Environment=TURN_URL=turn:turn.example.com:3478
Environment=TURN_SECRET=…
Restart=always
User=dexsignal
DynamicUser=yes

[Install]
WantedBy=multi-user.target
```

Copy `openandroiddex-launcher/app/src/main/assets/web/` to `public/` beside
`server.js` when you deploy it outside a checkout, or point `PUBLIC_DIR` at it.

## Setting the phone up

In the desktop's **Web viewer** window:

1. Set **Rendezvous address** to `wss://your-host/signal`.
2. Set **TURN server**, **TURN username** and **TURN password**. With
   `TURN_SECRET` on the server, generate a pair with coturn's
   `turnadmin -k -u dex -r your-realm` or use any long-term credential you have
   configured — the phone needs a credential of its own; only the browser's
   comes from `/ice`.
3. Press **Start**. The window shows a **Rendezvous** link — that is what you
   open from anywhere, and the access code beside it is what it will ask for.

## Limits

Deliberate, and all in one place at the top of `server.js`: 200 rooms, 8 viewers
per room, 64 KB per message, and a 2-minute idle cut. An open relay with no
limits is somebody's botnet.
