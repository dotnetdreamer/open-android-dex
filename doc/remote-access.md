# Remote access — SSH into the phone's Linux, from anywhere

The phone runs Ubuntu. Remote access makes that Ubuntu a machine you log in
to from a computer somewhere else — `ssh galaxy-s25-ultra` from a Windows
laptop in a café while the phone is on mobile data in a bag — with nothing
opened on any router, no account anywhere, and nothing installed on the
computer beyond this project's desktop app (or one small binary, `dexremote`,
where the app cannot go).

What a person does:

1. **Phone:** Linux → Remote access → *Turn on* → *Add a computer*. An
   eight-character code appears, good for ten minutes.
2. **Computer:** Open Android DeX → *Phone Linux* → type the code → *Pair*.
   Or, with the phone on the cable, *Set up SSH to …* and nothing to type.
3. From then on: `ssh <phone>` in any terminal; VS Code's Remote-SSH lists the
   phone; scp, rsync and git work against it by name.

This record exists for the same reason `custom-titlebar-v2.md` and
`web-viewer.md` do: several decisions below look arbitrary until you know what
was measured, or what the platform refuses.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The tunnel, phone side | `openandroiddex-remote/src/phone.rs` → `libdexremote.so` | the phone's iroh endpoint; who is let in; splicing streams onto sshd; pairing endpoints |
| The tunnel, computer side | `openandroiddex-remote/src/pc.rs` | dialling a phone by key; the ProxyCommand; local port forward; probes; pairing |
| The computer's files | `openandroiddex-remote/src/computer.rs` | identity, paired phones, `~/.ssh` setup |
| Pairing | `openandroiddex-remote/src/pairing.rs` | code → meeting place; the two proofs |
| Wire format | `openandroiddex-remote/src/proto.rs` | three ALPNs, a few JSON frames, the delegation |
| Vocabulary | `LinuxRemote.java` | where everything lives; the list of computers; sshd_config |
| Runtime | `RemoteHost.java` (inside `LinuxService`) | the daemon process, sshd, wake locks, network changes |
| The window | `LinuxRemoteActivity.java` | on/off, the computers, the code |
| USB pairing | `RemoteProvider.java` | one adb `content call` |
| Coming back | `RemoteResumeReceiver.java` | boot, updates, the desktop app's teardown |
| The guest's sshd | `openandroiddex-linux/linux-ssh.sh`, `setup_ssh` in `linux-setup.sh` | OpenSSH in a proot of its own |
| Desktop app | `src-tauri/src/remote.rs`, `src/features/remote/RemotePanel.tsx` | Phone Linux panel, `ssh-proxy` |
| Check | `test/remote-test.js` | both ends against each other, real relays, real OpenSSH |

## Why iroh

The problem is not SSH; it is that **the phone has no address**. On mobile data
it sits behind carrier-grade NAT with no inbound path at all, and the computer
is behind a NAT of its own. Something both can dial out to has to introduce
them, and something has to carry the traffic when hole punching fails — which
it does between a symmetric NAT (common on mobile networks) and a
port-restricted one (most home routers).

What was weighed, and why it lost:

- **WebRTC, already in the APK for the Web viewer.** It needs a signalling
  server *and* a TURN relay, and there is no free, public, account-less TURN.
  Without one, exactly the mobile-data case fails. The computer side would also
  need a WebRTC stack of its own just to hand ssh a byte stream.
- **A Tor onion service.** Works through any NAT, needs no server, and the
  address is the key — attractive on paper. Onion-service round trips are
  around a second, which makes a shell feel broken and VS Code unusable.
- **Tailscale, ZeroTier, Nebula.** Real solutions, each needing an account or
  a server with a public address. This project promises neither.
- **Reverse SSH to a public forwarder.** Someone else's uptime and time
  limits, and the forwarder sees every connection's routing.

**iroh 1.0** (June 2026) is the fit: endpoints are Ed25519 keys and are dialled
by key alone; connections are QUIC, end-to-end encrypted and multipath; hole
punching gets a direct path in roughly nine cases in ten; and n0 runs public
relays — free, no account, rate-limited, no SLA — that carry the rest without
being able to read it. The wire protocol is stable across 1.x. A connection
also follows either side across a change of network (Wi-Fi to mobile data),
which no TCP-based tunnel does.

The public relays are described by n0 as development- and hobby-grade, and that
is the honest description of this feature's dependency on them. The relay is
open source; `dexremote serve --relay <url>` points a phone at one of the
user's own, and nothing else changes.

## Who is let in

Three locks, each enough on its own:

1. **The tunnel.** After the QUIC handshake — which proves the far end holds
   the key it claims — and before a byte of application data, the phone checks
   that key against `peers.json`. Not paired: the connection is closed with
   code 403, which the computer prints as "the phone does not know this
   computer".
2. **sshd.** Public keys only, against an `authorized_keys` written from the
   same list. The tunnel ends on 127.0.0.1, which other apps on the phone can
   also reach, so this lock is not a formality.
3. **Pairing.** An eight-character code (Crockford base32, 40 bits) shown only
   on the phone's screen, good for one computer, once, for ten minutes.

And one more thing a first `ssh` to a new box never has: **the host key arrives
inside the pairing exchange**, authenticated by the code, and goes straight
into the computer's known_hosts with `StrictHostKeyChecking yes`. There is no
"authenticity of host can't be established" prompt to click through, and a
different key is a refusal (the check suite proves both).

The phone's **endpoint key never enters the guest.** Only `ssh/` is bound into
Ubuntu (sshd needs its host key in there); the key that *is* the phone on the
network stays outside, where nothing running in Linux can read it.

**Removing a computer is immediate.** The daemon re-reads `peers.json` on every
connection and every two seconds, and closes the live sessions of anyone no
longer in it.

### Pairing: a code that is a meeting place

The hard part of pairing is not trust, it is *finding*: neither side has an
address the other could type. So the code becomes a place to meet:

- Both sides stretch the code (PBKDF2-HMAC-SHA256, 100,000 rounds — 22 ms on
  an Apple-silicon Mac, measured; somewhat more on a phone, once per pairing)
  into a 32-byte Ed25519 seed and a 32-byte MAC key.
- The phone brings up a second, short-lived endpoint *with that seed*. iroh
  publishes it like any endpoint, so the code's public key is now dialable from
  anywhere — by whoever can compute it, which is whoever knows the code.
- The computer dials it and sends a MAC over its own identity, its name and its
  SSH key; the phone checks it, has the app save the computer, and answers with
  its permanent id and host key under a MAC of its own.

The stretch is slow on purpose: the code's public key sits in public DNS for as
long as pairing is open. Enumerating 2⁴⁰ codes at 100,000 HMAC rounds each in
ten minutes is out of reach; without the stretch it would merely be impractical.

### Delegation: one key cannot be in two places

Every `ssh`, `scp` and VS Code window runs a ProxyCommand of its own. iroh
allows an endpoint id to be live in one place at a time — a relay routes to the
newest connection for an id — so three processes binding the computer's key
would keep knocking each other off the relay. Each process therefore binds a
**throwaway key**, and the computer's long-term key **signs that throwaway id**
(`proto::Auth`). The phone verifies the signature against its list.

A delegation is useless to anyone who copies it: it names the throwaway
endpoint, whose private key the QUIC handshake has already proven and which
never leaves the process. `test/remote-test.js` runs three sessions at once
over the relay alone to keep this honest.

## The sshd, under proot

**OpenSSH, not dropbear**, because the people at the other end will be using
VS Code's Remote-SSH, scp (SFTP since OpenSSH 9), rsync and git, and OpenSSH is
what all of those are tested against.

Running it under proot rests on one measured fact about *this* proot
(green-green-avk v0.15): its fake-root extension **emulates `chroot()`** into a
directory with no bind mount inside it, by re-rooting the tracee
(`src/extension/fake_id0/chroot.c`), and fakes the setuid to the `sshd` user
that privilege separation does next. That is the step that killed sshd under
older proots (`chroot("/run/sshd"): Operation not permitted [preauth]`). It is
also why **no bind may ever land under `/run/sshd`** — the comment on the proot
line in linux-ssh.sh says so.

The configuration (`LinuxRemote.sshdConfig`, rewritten before every start):

- `UsePAM no` — Ubuntu's sshd PAM stack ends in `pam_loginuid`, which writes
  `/proc/self/loginuid`, which nothing in a proot can, and it is `required`:
  every login would be closed the instant it opened.
- `StrictModes no` — proot reports owners the way it fakes them, and one uid
  owns the whole container anyway.
- `Subsystem sftp internal-sftp`, TCP forwarding on (VS Code needs it), keys
  only, `127.0.0.1` only.
- The port is on sshd's command line (`-o Port=`), chosen by the app: 7022 when
  free — Termux's sshd defaults to 8022, and phones that run both must not have
  them fight — and whatever the kernel hands out when it is not.

It runs from **`linux-ssh.sh` in a proot of its own**, with the same lifetime
discipline as `linux-rt.sh` (session leader, pid file), so that logging out of
XFCE does not cut off an SSH session and remote access works with no window
open at all. A tmux started over SSH lives until remote access is switched off.

**Stopping it is SIGQUIT, then SIGKILL, to the group.** A group SIGKILL alone
is not enough: sshd setsid()s each login out of the group, and this proot sets
no `PTRACE_O_EXITKILL`, so with proot killed the logins — and the tmux or VS
Code server they left — would run on *untraced*, outside the container's paths.
proot ignores SIGTERM; on SIGQUIT it SIGKILLs every process it traces, whatever
session it is in, and exits (`src/tracee/event.c`, `kill_all_tracees`).
Only an sshd this process started is ever signalled: a `ssh.pid` left by a
process that died names nothing alive (Android kills a dead app process's
whole cgroup), and the number may by then belong to anything — this app's
own processes included.

**Probed until it answers, then trusted.** Each probe is a connection sshd forks
for and logs, under ptrace, so after the first one the process watcher says when
it dies and the daemon's `need-ssh` when a connection to it fails — which, for
an sshd that was answering, restarts it.

**Not a FEATURE_LEVEL bump.** `setup_ssh` installs `openssh-server` on every
fresh guest, but existing guests get it only when their owner switches remote
access on (`Linux.needsProvision` asks for it then). A bump would have re-run
the setup script on every container to install a server most will never use.
It runs `apt-get update` first — a guest's lists age, and an install off old
ones fails on 404s — and finishes any dpkg transaction a force-stop cut short.
"Installed" is dpkg's word (`.stamp-ssh`, written once `dpkg-query` says
`install ok installed`), not the binary's presence: an install killed after
unpacking leaves `/usr/sbin/sshd` with its postinst never run. It settles after
three failures at a feature level, the way VS Code's install does, so a package
that will not install does not rebuild the guest on every launch; until then
the host asks again every few minutes, and switching remote access on again
starts the count over.
`policy-rc.d` (exit 101) stops the package's postinst from trying to start sshd
on port 22, which this uid can never bind.

**The guest's directory is the guest's.** `ssh/` is bound in writable, so
nothing the app or the daemon writes there goes through a temporary file in it
(a planted symlink under the temporary name would carry the write into the
endpoint key or the computer list): temporaries are made outside, with O_EXCL,
and renamed in, which replaces a link instead of following it. A host key that
is not a plain file is replaced, and `ssh/` itself is put back as a directory if
the guest swapped it for a link.

## Android

- **A foreground service, sticky while remote access is on.** A container the
  user is looking at is theirs to restart; one they asked to keep reachable has
  to come back if Android reclaims the process.
- **Network changes come from Java.** Native code on Android gets no netlink
  events, and iroh says so and asks the app to tell it: every default-network
  change becomes a `net` line, which is what lets a session follow the phone
  from Wi-Fi onto mobile data.
- **DNS servers come from Java too**, from the active network's LinkProperties:
  on the command line at start (`--dns`) and on every `net` after, because a
  resolver fixed at start keeps asking the Wi-Fi router after the phone has
  walked onto mobile data — every lookup, the relay's first, waits out a dead
  server, and on a network that blocks public DNS none answers. The daemon's
  resolver rebuilds itself from the latest list whenever iroh resets it
  (`Resolver::reset`, on every major network change) and when `net` says the
  servers moved; public resolvers are raced alongside them, because a server
  that drops queries takes longer to give up on than iroh's lookups wait. They are always given
  explicitly on Android, because iroh's default reads them through JNI, and in
  an executable with no JVM that read panics (caught, but printed, on every
  start).
- **Every start brings it up.** Android re-creates a sticky service with the
  start that was pending when it died, and only with a null intent when there
  was none, so every start but the one turning remote access off starts it.
- **One identity, however many first starts.** The window's `init` and the
  service's `serve` can both make the keys on a phone's very first start; they
  take turns on a `flock` (std's `File::lock` compiles for Android and answers
  Unsupported there), so neither goes on using a key that lost the rename.
- **Wake lock: while a computer is connected, and while charging.** A CPU that
  sleeps between keystrokes makes a shell crawl and lets the NAT mapping lapse.
  Otherwise the phone sleeps normally; a packet on the relay connection wakes
  it the way a push message does.
- **Coming back.** A reboot (BOOT_COMPLETED), an update (MY_PACKAGE_REPLACED),
  the desktop starting (LauncherActivity), the Linux window opening, and the
  desktop app's end-of-session clean-up — which force-stops the launcher and
  then sends `RemoteResumeReceiver` a broadcast over adb. Starting a foreground
  service from the background needs the battery exemption the window asks for;
  when Android refuses anyway, a "tap to resume" notification is the fallback,
  because a tap is always allowed.
- **The desktop app no longer takes that exemption away** when the user gave
  it, or while remote access is on. It used to remove it on every unplug.

### LinuxService ↔ daemon

One line each way, over the daemon's stdin and stdout:

| command | | event | |
| --- | --- | --- | --- |
| `pair <CODE> <TTL> [<UNTIL>]` | open a code | `ready <ID> <HOSTKEY64>` | serving |
| `pair-cancel` | close it | `online <RELAY>` / `offline` | reachable or not |
| `pair-commit <ID>` / `pair-reject <ID>` | the app saved it, or not | `pair-listening <PAIRING> <UNTIL>` | the code is live |
| `net [DNS,…]` | the network changed, and its resolvers | `pair-request <ID> <NAME64> <OS> <KEY64> <PAIRING>` | save this computer? |
| `ssh <STATE>` | up, starting, down, failed, missing, installing | `paired <ID> <PAIRING>` / `pair-failed <WHY> [<PAIRING>]` / `pair-closed <PAIRING>` | how it went |
| `port <N>` | sshd moved | `sessions <N>` | wake lock |
| `quit` | stop | `need-ssh` · `revoked <ID>` | start sshd · a removal took effect |

stdin closing means the service is gone, and the daemon goes too: an orphaned
tunnel would be a door into the phone that nothing on screen knows is open.

**Pairing events name their pairing** (the endpoint the code derives), because
by the time the app reads one, a new code may be on screen: the old code's
"expired" must not be shown as the new one's. Each `pair` is answered by
exactly one `pair-listening`, or a `pair-failed` that names no pairing because
none opened, in order — so the app counts its unanswered `pair`s, and the
answer that brings the count to zero is the current code's. A code still on
screen when the daemon or the process dies is opened again for the time it has
left — with the deadline it was first shown with, sent along as an
absolute time, so a restart cannot stretch it. The deadline is the wall
clock's, on both sides: tokio's timers stop while the phone sleeps, and a code
the screen calls expired must not still let anyone in. `pair-request` names its
pairing too, and one for a code no longer on screen is refused.

## The computer

Pairing writes, all of it regenerated from `phones.json` on every change:

| file | |
| --- | --- |
| `<app data>/remote/computer.key`, `phones.json` | identity and the list |
| `~/.ssh/openandroiddex_ed25519` (+`.pub`) | the key the phones were given |
| `~/.ssh/openandroiddex_known_hosts` | each phone's host key, under a HostKeyAlias |
| `~/.ssh/openandroiddex.conf` | one `Host` block per phone |
| `~/.ssh/config` | one `Include openandroiddex.conf`, at the very top |

The Include goes at the top because ssh takes the first value it finds for each
option, and one placed after a `Host *` block would be read inside it. The
user's file is otherwise untouched (its BOM and line endings included).

The **ProxyCommand is the app's own binary** (`ssh-proxy <phone id>`), handled
in `main` before Tauri starts, and rewritten into the config at every app start
so an update or a move does not orphan it. A computer without the app uses
`dexremote` instead — `dexremote pair <CODE>` writes the same setup with itself
as the ProxyCommand, and `dexremote forward` gives PuTTY and WinSCP a local port.

## Verified, and not yet

Verified, on a Mac, over the real public relays and address lookup
(`node test/remote-test.js`, 38 checks): an unpaired computer is turned away
with a sentence; pairing by code (typed lower-case, with a dash); the generated
SSH setup; direct and relay-only paths; 4 MB through intact, half-close passed
on; removal closing a live session within ~2 s; a restarted daemon keeping its
identity; real OpenSSH (the system sshd, on the daemon's own host key) logging
in with the pairing's files and no prompt; exit status; scp; three sessions at
once, direct and over the relay alone; the daemon on its own resolver, taking
new DNS servers from a `net` and staying reachable; a reopened code ending at
its first deadline and pairing nobody after it; a changed host key refused.
Unit tests cover HMAC and PBKDF2 against RFC 4231 and RFC 7914, the key files
against real `ssh-keygen` output, eight simultaneous first starts agreeing on
one identity (they do not without the lock), and the links the guest can plant
in its directory never being followed. The Android build runs on an Android 16 arm64
emulator (as the shell user) and reaches a relay within a second. The desktop
app, Windows-only code included, type-checks for `x86_64-pc-windows-msvc` from
macOS — which is how a lockfile that paired wmi's `windows` 0.61 with
`windows-core` 0.62 was caught: it builds everywhere except Windows.

Verified on a phone (Galaxy S25 Ultra, Android 16, 2026-10-19): USB pairing
by `dexremote pair-adb` switching remote access on; the daemon as the app's
uid, its identity made through `init` under the `flock`; sshd under the real
proot — privilege separation's chroot, logins as root on Ubuntu 24.04; the
direct path (17–49 ms) and the relay alone (113 ms); scp and sftp, 20 MB each
way intact; remote access coming back by itself after the launcher was
reinstalled; and, with the phone's Wi-Fi off, the phone on its carrier's
network and the computer on home broadband, SSH and scp **direct**, hole
punched through the mobile network, at ~30 ms.

The phone found two things no computer test could:

- **SFTP fails under proot.** OpenSSH's SFTP server makes itself undumpable
  and treats failure as fatal; proot swallows `prctl(PR_SET_DUMPABLE)` on
  purpose but leaves a non-zero result behind, so every SFTP request ended in
  "unable to make the process undumpable: Success". `sftp-shim/` preloads a
  2 KB library into sftp-server alone that answers that one call with 0.
- **ssh SIGHUPs its ProxyCommand on exit**, and a proxy that died of it left
  the phone to learn of the session's end from QUIC's idle timeout, 30–40 s
  later, wake lock held. The proxy now ignores SIGHUP and closes properly
  (sessions gone within ~2 s), and the phone ends a session 2 s after sshd
  closes its side, for clients that kill the proxy outright — checked on the
  phone with a connection that sshd rejected and the computer held open.

Not yet verified on a device:

- the Windows ProxyCommand: a GUI-subsystem binary as ssh's ProxyCommand, and
  Win32-OpenSSH's quoting of a path under `C:/Program Files`;
- the Windows Defender Firewall prompt the first UDP socket on all interfaces
  may raise. Declining it should still leave the relay and hole-punched paths,
  which are replies to traffic the app sent first;
- battery and Doze behaviour over hours with no session open;
- VS Code Remote-SSH's server running inside proot;
- the SIGQUIT teardown on a phone: a tmux started over SSH gone after remote
  access is switched off, and no proot left behind.
