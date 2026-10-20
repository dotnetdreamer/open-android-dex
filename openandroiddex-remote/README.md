# openandroiddex-remote

Remote access to the phone's Linux: `ssh <phone>` from any computer, on any
network — the phone on mobile data, the computer on someone else's Wi-Fi — with
nothing opened on a router and no account anywhere. And its XFCE desktop, in a
window of the desktop app or a browser tab, over the same tunnel. The design record, with what
was measured and what was ruled out, is [`doc/remote-access.md`](../doc/remote-access.md).

One crate, both ends:

- **On the phone**, `LinuxService` runs the `dexremote` binary (shipped as
  `libdexremote.so` in the launcher's jniLibs) while Remote access is on. It is
  an [iroh](https://iroh.computer) endpoint that paired computers dial by key,
  and it splices each SSH stream onto the guest's sshd on loopback — and each
  desktop stream onto websockify, which serves the viewer page and the VNC
  WebSocket, or onto the launcher's LinuxStream (`--stream-socket`), which
  serves the desktop as hardware-encoded video.
- **On a computer**, the desktop app links this crate as a library: pairing,
  probing, and the `ssh-proxy` ProxyCommand. A computer without the app can use
  the same binary as a client — see below.

## Layout

| file | |
| --- | --- |
| `src/phone.rs` | the daemon: who is let in, splicing, the desktop, pairing endpoints, status |
| `src/pc.rs` | dialling a phone, the ProxyCommand, `forward`, `probe`, `pair`, the desktop's local port |
| `src/computer.rs` | a computer's identity, its phones, and the `~/.ssh` it writes |
| `src/pairing.rs` | an eight-character code as a place to meet |
| `src/proto.rs` | ALPNs, frames, messages, the delegation |
| `src/pipe.rs` | joining a QUIC stream to a socket or to stdio, half-close included |
| `src/sftp.rs` | files onto the phone's Linux: SFTP in the tunnel, never over anything (feature `sftp`) |
| `src/outbox.rs` | and back: Send To in the guest, its outbox, fetched into Downloads (feature `sftp`) |
| `src/sshkey.rs` | OpenSSH key files, without `ssh-keygen` |
| `src/crypto.rs` | HMAC-SHA256 and PBKDF2 over the tree's own SHA-256 |
| `src/main.rs` | the `dexremote` command line |

## Building

```sh
cargo test                      # unit tests: RFC vectors, real ssh-keygen output
node ../test/remote-test.js     # both ends against each other, over the real relays
./build-android.sh              # the phone binary, into the launcher's jniLibs
```

`build-android.sh` needs the two Android targets
(`rustup target add aarch64-linux-android x86_64-linux-android`) and an NDK
(`sdkmanager "ndk;29.0.14206865"`, or `ANDROID_NDK_HOME`). Like proot, the
results are **committed**: an APK build needs no Rust or NDK. The script checks
that every LOAD segment is 16 KB aligned, which Android 15's 16 KB-page devices
require. Rebuild them whenever anything under `src/` changes, and keep
`Cargo.lock` committed so the build stays repeatable. The phone's binary is
built with `--no-default-features`: the default `sftp` feature is the
computer's SSH and SFTP client (russh), and the phone never dials anyone.

The phone binary is built for the `*-linux-android` targets, not as a static
musl binary, on purpose: iroh supports Android as a platform, and the Android
build knows not to watch the network over netlink — which Android denies to
apps — and leaves network changes to the app (`net` on stdin).

## Without the desktop app

```sh
dexremote pair K7QM-29XV        # the code from Linux → Remote access → Add a computer
ssh galaxy-s25-ultra            # the name it printed
dexremote forward galaxy-s25-ultra 127.0.0.1:2222    # for PuTTY, WinSCP, …
dexremote probe galaxy-s25-ultra                     # reachable? direct or relayed?
dexremote desktop galaxy-s25-ultra                   # its desktop: prints the address to open
dexremote put galaxy-s25-ultra report.pdf photos/    # onto its Linux desktop
dexremote get galaxy-s25-ultra                       # what its Linux sent this computer
```

`desktop` starts the phone's desktop at `--size` (default 1920x1080) if it is
not running, prints `http://127.0.0.1:6080/dex.html?password=…` — the page the
phone's own Linux window loads, password included — and serves it until
Ctrl+C. Only that page's own requests get through the port: anything with
another site's `Origin` or another `Host` is refused, so a web page open in
some other tab cannot reach the desktop through it. With `--video-origin
ORIGIN` it also prints `ws://127.0.0.1:6080/stream/<token>`: the desktop as
H.264, for a page from ORIGIN that knows LinuxStream's wire (the desktop app's
window is one; `doc/usb-desktop.md`).

`put` is what dropping files on the desktop app's window does: SFTP inside
the same tunnel, logged in with the pairing's key against the host key the
pairing pinned, into the guest's `~/Desktop` (or `--to <dir>` in its home).
Nothing there is ever replaced — a name in use gets Thunar's "(copy 1)" — and
Ctrl+C takes back the item it was in the middle of. Links inside a folder are
left out, and said so.

`get` is the other way: it sets up this computer's entry in the guest's
Thunar *Send To* menu (and a *Send to computer* icon for the dock, there from
the desktop's next start), then fetches whatever Linux has sent this computer
since into `~/Downloads` (or `--to <dir>`), never over anything already
there. The desktop app does the same every two seconds while its desktop
window is open.

`pair` writes the same `~/.ssh` setup the app does, with `dexremote proxy` as
the ProxyCommand. `DEXREMOTE_RELAY_ONLY=1` forces every packet through the
relay, which tells "the relay path is broken" apart from "hole punching is";
`DEXREMOTE_LOG=debug` turns the logging up.
