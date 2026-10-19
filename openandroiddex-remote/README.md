# openandroiddex-remote

Remote access to the phone's Linux: `ssh <phone>` from any computer, on any
network — the phone on mobile data, the computer on someone else's Wi-Fi — with
nothing opened on a router and no account anywhere. The design record, with what
was measured and what was ruled out, is [`doc/remote-access.md`](../doc/remote-access.md).

One crate, both ends:

- **On the phone**, `LinuxService` runs the `dexremote` binary (shipped as
  `libdexremote.so` in the launcher's jniLibs) while Remote access is on. It is
  an [iroh](https://iroh.computer) endpoint that paired computers dial by key,
  and it splices each SSH stream onto the guest's sshd on loopback.
- **On a computer**, the desktop app links this crate as a library: pairing,
  probing, and the `ssh-proxy` ProxyCommand. A computer without the app can use
  the same binary as a client — see below.

## Layout

| file | |
| --- | --- |
| `src/phone.rs` | the daemon: who is let in, splicing, pairing endpoints, status |
| `src/pc.rs` | dialling a phone, the ProxyCommand, `forward`, `probe`, `pair` |
| `src/computer.rs` | a computer's identity, its phones, and the `~/.ssh` it writes |
| `src/pairing.rs` | an eight-character code as a place to meet |
| `src/proto.rs` | ALPNs, frames, messages, the delegation |
| `src/pipe.rs` | joining a QUIC stream to a socket or to stdio, half-close included |
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
`Cargo.lock` committed so the build stays repeatable.

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
```

`pair` writes the same `~/.ssh` setup the app does, with `dexremote proxy` as
the ProxyCommand. `DEXREMOTE_RELAY_ONLY=1` forces every packet through the
relay, which tells "the relay path is broken" apart from "hole punching is";
`DEXREMOTE_LOG=debug` turns the logging up.
