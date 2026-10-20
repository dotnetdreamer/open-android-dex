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
4. And the desktop: *Phone Linux* → *Desktop* opens the phone's XFCE in a
   window on the computer — see [The desktop, in a window](#the-desktop-in-a-window).
   Copy on one side and paste on the other; drop files on the window and
   they land on the guest's Desktop; Send To this computer in Linux and they
   land in its Downloads.

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
| The desktop's window | `src-tauri/src/remote_desktop.rs`, `src/features/remote/DesktopWindow.tsx` | the window, its local port, reconnecting, fullscreen |
| What crosses, either way | `src/features/remote/DesktopRouter.tsx`, `DesktopCards.tsx` | the clipboard's switch, drops and what Linux sends, over adb or the tunnel |
| The desktop's page | `assets/linux/novnc/dex.js` (`ctx=pc`) | the phone's own viewer, in that window |
| The clipboard | `src/features/remote/clipboardShare.ts` | when it crosses, which way, and the switch |
| Files onto the phone | `openandroiddex-remote/src/sftp.rs` | SFTP in the tunnel, never over anything; `dexremote put` |
| Files back from it | `openandroiddex-remote/src/outbox.rs` | Send To and the dock icon in the guest, the outbox, fetching into Downloads; `dexremote get` |
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
| `desktop <STATE> <PASS> <W>x<H>` | running, starting, stopped, failed, unfinished, installing, missing; `-` for unknown | `desktop-start <W> <H>` | a computer opened it; start it |
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

## The desktop, in a window

SSH reaches everything in the guest except its desktop. *Phone Linux* →
*Desktop* opens that too: the phone's XFCE in a window on the computer, through
the same tunnel, wherever the two are.

**It is the phone's own viewer.** websockify already serves noVNC's page and
the VNC WebSocket on one loopback port (6080, linux-rt.sh) for the Linux window
on the phone. A fourth ALPN, `desktop/1`, splices streams onto that port the way
`ssh/1` splices them onto sshd, and the computer serves them on a port of its
own loopback. The page, its modules and the WebSocket keep the one origin they
need — noVNC imports from `/core/`, and websockify sends no CORS headers — and
the computer needs no copy of noVNC. Xvnc's 5901 straight through would have
needed one, and a WebSocket server of the app's own to feed it.

**One connection per window.** The `open`, every file the page loads and the
WebSocket are streams on one QUIC connection: a page load costs no handshakes,
and no file waits behind the picture. The phone counts the connection as one
session — for the wake lock and the notification — however many streams it
opens, for as long as it is open: a viewer on an unchanging desktop moves no
bytes, and still wants the phone awake.

**Starting it.** An `open` that says `start` — with the window's size in
logical pixels, held to 800×600–3840×2400 — makes the daemon print
`desktop-start <W> <H>`; RemoteHost hands that to LinuxService, which starts the
runtime exactly as the Linux window would. Three rules:

- *A running desktop is never restarted*, whatever size was asked for. It has
  someone's windows in it; it is resized instead (below).
- *Only `start` starts.* A window coming back after a drop opens without it,
  so a desktop someone logged out of on the phone is not booted up again
  behind them; the window says it is not running and offers to start it.
- *Up means websockify answers*, not that rt.pid exists: it comes up a few
  seconds after Xvnc. The daemon tries the port twice a second while the app
  says `running`, and answers only then.

The app describes the desktop to the daemon on every change (`desktop <STATE>
<PASS> <W>x<H>`), from files alone: probing websockify on every tick would be a
connection it forks for, under ptrace, every three seconds. `failed` is an
`rt.exit` newer than the request, inside its two minutes, from a session that
had not lasted half a minute — the start clears the marker before it spawns, so
one written after the request is that session's, and one that had been up
longer was logged out of, which is only `stopped`. As with `need-ssh`, a
`failed` still there three seconds after asking is the answer to the asking.

`running` is rt.pid *and* a runtime this process started that is still alive
(`LinuxService.runtimeAlive`). rt.pid alone outlives a runtime killed along
with the app process — an update, a force-stop, the desktop app's
end-of-session clean-up — and on the phone, straight after an update, it read
`running` for a desktop that was gone: the daemon would have waited two
minutes on websockify and never asked for a start. A runtime always dies with
the process that started it, so one this process did not start is not running.

**The size follows the window.** The page asks Xvnc to match its box
(noVNC's `resizeSession`, the ExtendedDesktopSize extension) once connected
and after every resize, half a second after the last; on a computer the box is
the whole window below the bar, so the desktop is always one pixel per pixel —
windowed, maximised, or fullscreen with the bar folded away. XFCE re-lays
itself out on RandR like any X desktop. The ceiling is the start's,
3840×2400. The keys row is laid over the picture rather than taken out of it,
so opening it does not re-lay the desktop out. A phone that is also showing
the desktop sees the new size, letterboxed.

**The password rides the answer.** Xvnc's password (`vncpass`, made at setup)
reaches the daemon on the `desktop` line and the computer in the `open` answer,
and the window gives it to the page in its URL, as LinuxActivity does. Nobody
types it.

**Sound stays off.** A desktop started from a computer does not start the
phone's audio pump: the phone may be in a bag in another room, and a video
playing on the laptop must not come out of it. The Linux window opening on the
phone brings the sound on.

### Who gets in, on the computer's side

The tunnel's lock is unchanged, and Xvnc's password is the second. The third is
new, because the computer's end is a TCP port and **loopback is not private to
the app**: any web page in any browser on the computer can open a WebSocket to
`127.0.0.1`, and websockify checks nothing. So the forward reads each request's
head first, and passes it on only if `Host` is that port on `127.0.0.1` or
`localhost`, and `Origin`, when there is one, is that same address; anything
else gets a 403. A page from another site names itself in `Origin`, and a
DNS-rebinding page carries its own name in `Host`. What is left to the password
is a program running on the computer — the job it already had on the phone,
where every app can reach websockify.

### The window

The page comes from the guest, where anything running as root can rewrite it,
so it is not the window's own page. The window loads one of the app's
(`DesktopWindow.tsx`) with the viewer in a **frame** of another origin, which
Tauri gives no IPC: no capability names a remote origin
(`capabilities/phone-desktop.json` covers the window's own page), every command
from one is refused, and Tauri's scripts — the invoke key with them — go into
the main frame only. The frame is sandboxed to scripts and its own origin, which
its modules and its WebSocket need; the window navigates only to its own page
and its viewer's port, and opens no other windows. A rewritten page can draw in
its rectangle and post messages, which the window checks for source and origin.

What crosses between the computer and the guest — the clipboard, files
dropped on the window, what Linux sends back — belongs to the window, not to
this page (`DesktopRouter.tsx`): the window also shows the desktop over adb
([usb-desktop.md](usb-desktop.md)), and those outlast a move between the two.

The two talk in those messages (dex.js, "the window around a computer's page"):

| page → window | | window → page | |
| --- | --- | --- | --- |
| `status` | the words LinuxActivity reads from `#status` | `quality`, `compression` | noVNC's two knobs, from the path |
| `fullscreen: "toggle"` | F11, or the bar's button | `fullscreen` | it is, or is not: fold the bar |
| `clip` | the guest's clipboard changed: its text | `link` | "Direct · 23 ms", on the status dot |
| `share: "toggle"` | the clipboard switch was pressed | `focus` | keys go to the desktop |
| | | `share` | the clipboard is shared, or not: draw the switch |
| | | `paste` | the computer's clipboard, for the guest |

`ctx=pc` is a third context beside `phone` and `desktop`: Direct and nothing
else, no on-screen keyboard, no zoom (the desktop is the window's size), no
Remote access button, and a fullscreen button. F11 is the window's, as in a
browser: dex.js takes a bare one in the capture phase, ahead of noVNC, and
passes it up, so the guest never gets half a key. Fullscreen folds the bar into
the bubble, and the desktop grows to the whole screen. Escape is left alone:
it is vim's, and every dialog's, and F11 always gets back out.

**Coming back.** The page never reconnects itself — on the phone LinuxActivity
owns the retry ladder — so on a computer the window does. On `closed` or
`broke` it opens without `start` after 0.3 s, then backs off to every 15 s, on
a new connection if the old one has gone; up again is a fresh frame on the same
port, so the page's origin never changes under it. A refusal that will not pass
by itself — the computer removed, Linux uninstalled, the desktop stopped — ends
the retrying and says so. `failed` from the page is Xvnc refusing the password,
which means Linux was reinstalled since; the window asks once for the new one.

The window also **pings** the phone every 5 s on its connection (`ping`, a
stream the daemon answers and nothing more), and hangs up when 12 s go by with
no answer. A daemon started again — the app updated, a DeX session's clean-up
force-stopping it — is back at the same address, the relay hands it the old
connection's packets, and nothing ever says that connection is dead: measured
on the phone, the viewer sat on its last frame, looking connected, for three
minutes. Hanging up ends the page's socket, and the ordinary coming-back
above takes over. Twelve seconds rides out the phone changing networks, which
the connection would have followed.

A DeX session ending on the same computer — its scrcpy window closed — runs
the desktop app's end-of-session clean-up, which force-stops the launcher, and
the Linux desktop goes with it, as open SSH sessions do. The window then says
the desktop is not running and offers to start it.

**Speed.** On a direct path (17–49 ms on the phone that was measured) the
desktop is fine at noVNC's own settings; through the relay (113 ms, and shared
and rate-limited) it is usable but sluggish. So the window sets noVNC's JPEG
quality and zlib effort from the path: its defaults (6, 2) direct, quality 3
and compression 6 on the relay or past 80 ms. The first path is always the
relay and hole punching usually moves it within seconds, so the picture
sharpens by itself.

**Over adb it is not VNC at all.** With the phone on the cable, or on wireless
debugging, the same window takes the desktop as video from the phone's hardware
encoder, decoded on the computer's GPU, with the computer's own pointer — and
moves between that and this tunnel as the cable comes and goes. See
[usb-desktop.md](usb-desktop.md).

**Without the app**, `dexremote desktop <phone>` does the same in a browser
tab: starts the desktop if asked, prints the page's address with the password,
serves it on `127.0.0.1:6080` (or any free port) behind the same checks, and
hangs up on Ctrl+C — the phone stays awake for as long as it believes someone
is looking, so a closed viewer must say so rather than time out.

### The clipboard

Text, both ways. The frame cannot reach this computer's clipboard, and must
not be the one to decide when it may: anything running as root in the guest
can rewrite it. So the window does it (`clipboardShare.ts`, through the
clipboard plugin's read-text and write-text, which `phone-desktop.json` grants
the window's own page and nothing else), and the page only carries the text
to Xvnc and back: noVNC's `clipboardPasteFrom` one way, its `clipboard` event
the other.

**When it crosses** — the rules of TigerVNC's own viewer:

- *Computer → Linux when the window comes to the front*, and when the page
  connects with the window in front, if the clipboard changed since the two
  last agreed. That is the moment someone who copied something elsewhere comes
  back to paste it. It is the native window's focus, not the page's: focus
  moving into the frame blurs the page.
- *Linux → computer while the window is in front.* What the guest copies
  while the window is elsewhere — a program in Linux, not a person — waits for
  the window to come back, and is dropped if this computer's clipboard changed
  meanwhile: the newer copy wins.
- A selection being dragged out in the guest announces itself many times a
  second; the clipboard gets the last of them, 150 ms after.
- *A new connection gets the computer's text again* if that is what the guest
  last had. Xvnc holds a client's clipboard only while that client stays —
  when it leaves, Xvnc lets the selection go — so a reconnect, or the window
  moving between adb and the tunnel, would otherwise leave Linux with nothing
  to paste.

**Xvnc already does its half.** TigerVNC 1.13.1, as Ubuntu 24.04 ships it,
keeps the clipboard itself (no `vncconfig`), and its defaults are the ones
wanted — read off the phone with `Xvnc -help`: `SendCutText=1`,
`AcceptCutText=1`, `SendPrimary=1`, `SetPrimary=1`, `MaxCutText=262144`. So
linux-rt.sh's Xvnc line is unchanged. What the computer pastes becomes both
CLIPBOARD and PRIMARY in the guest, so Ctrl+V and a middle click both paste
it, and both selections are sent back. SendPrimary has one consequence a Mac
or Windows user will notice: text *selected* in Linux, without Ctrl+C, lands
on the computer's clipboard too, as it does in any Linux VNC viewer.
`-SendPrimary=0` on the Xvnc line would make only Ctrl+C count.

It is UTF-8 throughout: noVNC 1.3 and TigerVNC both speak the extended
clipboard (UTF-8, CRLF line ends, zlib), and the old Latin-1 one is only the
fallback. Two of noVNC 1.3's quirks are the page's to work around:

- **A paste waits for the server's answer.** noVNC chooses between the two by
  what Xvnc said it can take, which Xvnc says in answer to the encodings — a
  round trip after `connect`. A paste sent sooner goes the Latin-1 way, and
  everything outside Latin-1 arrives as a byte of nonsense: the browser check
  measured "—" landing as U+0014 and 日本語 as junk. The canvas is no clue (it
  is sized from ServerInit, before the answer) and nothing public says when it
  came, so a paste waits for noVNC 1.3's own record of it
  (`_clipboardServerCapabilitiesFormats`), polled every 50 ms, for up to 5 s;
  on a noVNC without that field, for a second and a half.
- **Line ends.** noVNC 1.3 turns only the *first* CRLF from Xvnc into a LF
  (`replace("\r\n", "\n")`); the page turns them all. The window writes LF on
  a Mac and on Linux, and CRLF on Windows.

Xvnc drops an update from a client over 256 KB (MaxCutText, counted as UTF-8
with CRLF and a NUL), so the window does not send one, and says so once.
What the guest may put on this computer's clipboard is held to 4 MB.

**Privacy, and the switch.** Whatever is on this computer's clipboard when
the window comes to the front becomes readable by every program in the guest
— Xvnc hands it to any X client that asks — and whatever is copied in Linux
lands on this computer's clipboard, ready to be pasted somewhere that
matters. That is the feature, and it is also its cost, so the viewer's bar
has a switch for it (`ctx=pc` only: the clipboard icon, struck through while
off), and it is on by default.

The page draws the switch; the window keeps it. Pressing it asks the window
to flip it (`share: "toggle"`); the window remembers it per phone (the store
plugin, `phone-desktop.json` — the window is incognito, so its own storage
would not outlast it), tells the page which way it is, and says so in its own
words over the picture. A page rewritten in the guest can ask for the switch
to flip, then, but not without being seen to; and with it off the window
neither reads this computer's clipboard nor takes the guest's, whatever the
page sends. Turning it off also takes back the computer's text if that is
what the guest is still holding (an empty paste), so the last thing shared
does not sit in Linux waiting to be pasted. Nothing is handed over before the
setting has been read.

Reading the clipboard when the window comes to the front is a programmatic
read. macOS 15.4 previewed a prompt for exactly that, opt-in
(`EnablePasteboardPrivacyDeveloperPreview`); it is not on by default in
macOS 26. If Apple turns it on, the first read will ask, once per app.

### Files, dropped on the window

Dragging files or folders over the window — over adb or through the tunnel,
the copy goes this way either way, and a cable pulled during one does not stop
it — covers it with "Drop to copy to the
Desktop on ‹phone›"; dropping them copies them — a folder with everything in
it — into the guest's `~/Desktop`, where XFCE shows them as they land. A card
in the window's corner shows how far it has got, with a Cancel; a drop made
during a copy waits its turn, and a cancel takes the waiting ones with it.

The drag is the webview's own (`onDragDropEvent`), so it covers the frame's
rectangle as well as the page's, gives real paths, and the frame never sees a
drop. Only a drag that carries files covers the window, and one that arrives
while the desktop is not up says so and copies nothing.

**The transport is SFTP, inside SSH, inside the tunnel** (`dexremote::sftp`):
an SSH stream opened exactly as `ssh` opens one (`Client::open_ssh`), a login
with the pairing's key (`~/.ssh/openandroiddex_ed25519`), the phone's host key
checked against the one the pairing pinned — anything else is refused before a
byte of a file is sent — and SFTP in the guest's sshd, the `sftp-server` that
`sftp-shim` already makes work under proot. What was weighed:

- **A `put` op on the desktop connection, written by the daemon.** No SSH on
  the computer — but the daemon runs outside proot, as the app, so every write
  would land on the phone's real filesystem through a path the guest controls.
  `~/Desktop` itself can be a link the guest made, and a link planted under a
  name about to be written carries the write wherever it points: into
  `linux-remote/`, the endpoint key and the list of computers included. It
  would need the no-follow discipline `ssh/` gets (temporaries made outside,
  renames in, links replaced rather than followed) at every component of every
  path, and a new phone build before anyone could use it.
- **SFTP — chosen.** sftp-server runs under proot as the guest's root, so
  every path is resolved inside the container, where a link can only lead
  somewhere in Linux, and the guest's own permissions apply. It needs nothing
  new on the phone: any phone that takes `ssh` takes this, from any paired
  computer — `dexremote put <phone> <files>` does the same from a terminal. The
  cost is an SSH client in the desktop app.

That client is russh 0.64 with russh-sftp 2.4, in process. Both are
maintained: russh 0.64.1 and russh-sftp 3.0.1 came out in October and
September 2026. It is built on ring, which iroh brings anyway, rather than
russh's default aws-lc-rs, a C build that wants CMake and NASM on Windows.
russh-sftp 2.4 rather than 3, because 3.0 raised its floor for log, thiserror
and the whole wasm-bindgen family past what the app's lockfile holds, and 2.4
has the same pipelined writes. It adds 65 crates to the app's lockfile and
moves none. The phone's binary is built without it (`--no-default-features`):
the phone never dials anyone.

**Nothing is ever overwritten.** Every file is created with `O_EXCL` and every
folder with `mkdir`, and both fail rather than replace — a dangling link in
the way included — so it is the server that says whether a name is free, with
no race. A name already taken gets the one Thunar 4.18 gives a copy: "report
(copy 1).pdf", then "(copy 2)", by Thunar's own idea of an extension ("backup
(copy 1).tar.gz"; a folder has none). Not "report (2).pdf", which is what the
File transfer window does on the phone's own storage: on an XFCE desktop,
Thunar's names are the native ones.

**A cancel takes back the item in progress** — the file, or the folder and
everything already in it — and leaves the items that had finished. A failure
does the same and says which file and why. The guest's free space is checked
first (`statvfs@openssh.com`).

**Links inside a folder are not followed.** A link dropped on its own is
copied as what it points to — someone picked it — but links met inside a
folder are left out, which also keeps one pointing back up the tree from
walking forever; they are counted and reported, as are files this computer
would not let the copy read. Times and the executable bit come along. A
folder's files go eight at a time — each costs three round trips that carry
no data — and each file's bytes go in writes as large as the phone's
sftp-server takes (255 KB), as many in flight as SSH's 2 MB window holds.

### Files the other way

A real drag out of the window cannot work: the drag happens inside X, and VNC
carries neither drags nor files. So the guest gets the nearest things it has
of its own: **Thunar's *Send To → ‹this computer›***, and a ***Send to
computer* icon on the dock** to drop files on — the way for a file on the
Desktop, since xfdesktop's own icon menu has no Send To (that is Thunar's).

Both run `oadx-send`, a few lines of sh in the guest, which queues what was
picked in this computer's outbox there
(`~/.local/share/openandroiddex/outbox/‹computer›/‹batch›/`, one small file per
item naming its path, the batch renamed into place whole). While the desktop
window is open the app looks at that outbox every two seconds over SFTP,
fetches each item into Downloads, takes it off the queue, and the window says
"Saved to Downloads — Show"; Show reveals it in Finder or Explorer, and only
ever something that window saved. `dexremote get <phone>` does the same once,
from a terminal.

**The computer writes the guest's half**, over SFTP, each time its window
connects: the script, its Send To entry (with the name the phone's list gives
it), and the dock's launcher. So nothing new is needed on the phone, every
write lands inside the container, and an update of the app reaches the guest
by itself. The launcher goes into the folder linux-setup.sh keeps the dock's
launchers in, and dock.py puts it on the dock at the next start of the
desktop — not before: xfce4-panel with two panels asks the user which one
before adding anything (`panel_application_add_new_item`), and a running
panel writes its own layout over any edit as it exits.

**Paths, not copies.** A copy into the outbox would cost the phone twice the
space and twice the time of a large video, for a snapshot nobody asked for;
Bluetooth's Send To and every file manager read a file from where it is. The
cost: a file deleted before the computer comes back is gone, and is counted
as gone, not failed.

**Which computer.** Each computer rewrites a `.seen` file in its outbox every
five seconds — stamped by the guest's own clock, so no two clocks are ever
compared — and the dock's icon sends to the newest: the computer showing the
desktop now, or else the one that showed it last.

**What arrives** never replaces anything ("report (2).pdf", the app's own
convention on this side, the same as its File transfer window's), is never
opened, and is named for this computer's filesystem — Windows' reserved
characters and device names included. A picked link is followed; links inside
a folder are left out, as on the way in. A fetch that is cut off — the window
closed, the connection gone — takes back what it had written of the item in
progress and leaves the item queued, to come again whole. Large files are
read in four ranges at once, each through a handle of its own: russh-sftp 2.4
asks for one chunk per round trip, which held a 32 MB file to ~1 MB/s on the
phone, against ~6 MB/s in ranges (debug builds, both).

**What it trusts.** While the window is open, anything in Linux can queue
files for this computer, and they land in Downloads. That is the feature; they
are never opened, never replace anything, and every arrival is announced.
There is no telling the guest's user that a file went: the guest has no
notify-send, no gdbus and no notification daemon, so the window on the
computer is what says so.

## Verified, and not yet

Verified, on a Mac, over the real public relays and address lookup
(`node test/remote-test.js`, 86 checks): an unpaired computer is turned away
with a sentence; pairing by code (typed lower-case, with a dash); the generated
SSH setup; direct and relay-only paths; 4 MB through intact, half-close passed
on; removal closing a live session within ~2 s; a restarted daemon keeping its
identity; real OpenSSH (the system sshd, on the daemon's own host key) logging
in with the pairing's files and no prompt; exit status; scp; three sessions at
once, direct and over the relay alone; the daemon on its own resolver, taking
new DNS servers from a `net` and staying reachable; a reopened code ending at
its first deadline and pairing nobody after it; a changed host key refused.
Files onto it with `dexremote put`, against the same sshd: a file and a
folder of 27 byte for byte, times and the executable bit kept; a link inside
the folder left out, and said so; a second and a third drop named "(copy 1)"
and "(copy 2)" with the first untouched; the relay alone; Ctrl+C half way
through taking back the whole folder it was in; a host key other than the
paired one refused before a byte was sent; and every copy's session let go.
And back with `dexremote get`: the guest's script and Send To entry written;
the script, run as Thunar runs it, queueing one batch whole; a 2 MB file (in
ranges, with its time) and a folder fetched byte for byte, the link inside it
left out; a missing and an unreadable item counted, not fatal; the outbox
empty after; the same again landing as "(2)"; and the dock's `--open` going to
the computer that looked, and refusing when none ever had.
And the desktop, with `dexremote desktop` against a stand-in websockify: no
Linux refused in a sentence; a stopped desktop not started unless asked; a
start the app reports failed, reported as one; an oversized request held to
3840 wide; the password handed over; the page, and 1 MB each way through the
WebSocket, as one session however many streams; another site's `Origin` and
another `Host` refused; a daemon killed and started again under the same
identity noticed by the ping in seconds, and the next request reaching its
replacement; Ctrl+C ending the session on the phone at once.
Unit tests cover HMAC and PBKDF2 against RFC 4231 and RFC 7914, the key files
against real `ssh-keygen` output, eight simultaneous first starts agreeing on
one identity (they do not without the lock), and the links the guest can plant
in its directory never being followed, and Thunar's copy names, extensions
and all. The Android build runs on an Android 16 arm64
emulator (as the shell user) and reaches a relay within a second. The desktop
app, Windows-only code included, type-checks for `x86_64-pc-windows-msvc` from
macOS — which is how a lockfile that paired wmi's `windows` 0.61 with
`windows-core` 0.62 was caught: it builds everywhere except Windows. It still
does with russh in it.

The clipboard and the window, in headless Chrome, off the phone: the real
dex.html and noVNC 1.3.0 against a stand-in Xvnc that speaks TigerVNC's
extended clipboard (its capabilities sent 400 ms late, as a round trip makes
them), framed by a page of another origin running the window's own
`clipboardShare.ts` — 24 checks: UTF-8 both ways and never the Latin-1 path
(which is how the early paste was found: "—" arrived as U+0014), every CRLF
undone, one write for a selection dragged out, the guest's copy held while
the window was elsewhere and the computer's newer one winning, the switch
asking, the window saving it and saying so, the guest's copy of the
computer's text taken back, nothing crossing while off, the 256 KB cap, no
paste taken from anything but the window, and no switch in a plain tab. Then
`DesktopWindow.tsx` itself, from the dev server, with Tauri's IPC mocked
under it and the frame in a process of its own — 24 checks: the switch read
before the clipboard, the clipboard on connect and on Tauri's focus event,
the drop cover, `remote_desktop_put` with the dropped paths, the card's
progress, a drop queued behind a copy, Cancel, and a copy that finished and
one that failed. And again once the window took the desktop over adb too
(`DesktopRouter.tsx`, 41 checks, the adb end a stand-in LinuxStream): the
clipboard and its switch over the stream as through the frame, a drop over
the video, the cable pulled in the middle of a copy — the window on the
tunnel within a look at adb, the copy carrying on and finishing there, the
guest given the computer's text again on the new connection — and the cable
back, the window on adb within its three seconds, what Linux sends asked for
once per window and its card kept.

Verified on a phone (Galaxy S25 Ultra, Android 16, 2026-10-19): USB pairing
by `dexremote pair-adb` switching remote access on; the daemon as the app's
uid, its identity made through `init` under the `flock`; sshd under the real
proot — privilege separation's chroot, logins as root on Ubuntu 24.04; the
direct path (17–49 ms) and the relay alone (113 ms); scp and sftp, 20 MB each
way intact; remote access coming back by itself after the launcher was
reinstalled; and, with the phone's Wi-Fi off, the phone on its carrier's
network and the computer on home broadband, SSH and scp **direct**, hole
punched through the mobile network, at ~30 ms.

The desktop, the same evening, on the same phone over a direct path: started
from a computer in under six seconds at the size asked for (2560×1440); the
page, its modules and the WebSocket through the tunnel in 1.6–1.8 s; Xvnc
taking the password it was handed; the guest's screen made the window's size
as the page connected, and following the window down to 1500×856 0.4 s after
it shrank; a click opening XFCE's Applications menu; no sound on the phone;
and the desktop app's own window (WKWebView, the viewer in its frame) showing
the desktop within a second of the button.

The clipboard and drops on the same phone, later that evening, through the
page the new launcher staged (`dexremote desktop` on the app's own pairing,
headless Chrome, the guest's selections read and set with libX11 over ssh):
what the computer had copied — "Grüße vom Computer ✓ — 日本語", two lines — was
the guest's CLIPBOARD and PRIMARY as the window connected; the switch off
emptied it and on put it back; a program owning CLIPBOARD, and another only
PRIMARY, both reached the computer's clipboard; and coming back to the window
handed the guest what had been copied since. `dexremote put` put a folder of
42 files and a `.tar.gz` (26 MB in all, one link left out) on the guest's
Desktop byte for byte, XFCE showing both icons as they landed; a second drop landed as
"oadx-drop-folder (copy 1)" and "oadx-drop-note (copy 1).tar.gz"; and Ctrl+C
during a 160 MB file took the folder back. Over the direct Wi-Fi path (45 ms)
copies ran at 1–6 MB/s, in the same band as OpenSSH's own scp through the
same tunnel (0.8–6.7 MB/s, in alternating runs), so the path and the phone,
not the client, are the limit. Files land as the guest's umask has them —
077, Android's, so `0700` for a script — which is how Linux's own programs
make theirs there.

Files the other way, on the same phone: `dexremote get` set up "IK's M1" in the
guest — the script, the Send To entry, and the dock's launcher beside
linux-setup.sh's own — and Thunar's real context menu showed *Send To → IK's
M1*; clicking it queued the file, and the next `get` saved it byte for byte. A
folder with a link inside came back without the link, and 32 MB came back in
7.3 s, setting up included.

The phone found five things no computer test could:

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
- **rt.pid outlives the runtime.** Straight after the launcher was updated the
  phone said its desktop was `running` — rt.pid, from a runtime the update had
  killed. The desktop now counts as running only while a runtime this process
  started is alive (`LinuxService.runtimeAlive`).
- **A restarted daemon leaves the old connection hanging.** The DeX session's
  clean-up force-stopped the launcher; the daemon came back under the same
  identity, the relay handed it the old connection's packets, and the
  computer's viewer sat on a frozen frame, its dot green, for three minutes.
  The window pings now, and hangs up after 12 s without an answer.
- **A phone from before the desktop** refuses its ALPN in the handshake (TLS
  alert 120), which the computer used to report as "the phone did not
  answer". It now says the app on the phone is too old.

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
- the desktop on WebView2 and WebKitGTK; F11 from inside the frame in the
  app's window; the quality stepping up when the path goes direct; and how it
  feels through the relay.
- a real drag in the app's real window: that wry delivers drag-and-drop
  events over the frame's rectangle as it does over the page — on WKWebView,
  and on WebView2 and WebKitGTK above all — and the clipboard plugin's
  read-text on a focus event. Everything on either side of those was run;
  those two seams were not, since nothing here can drag a file onto a window;
- the clipboard on Windows (CRLF written, and read back as the same text) and
  on Linux;
- macOS asking before a programmatic clipboard read, if Apple turns that on;
- the desktop app's own fetching, in its real window ("Saved to Downloads —
  Show", and Show revealing it): the same `outbox::fetch`, run by
  `dexremote get` on the phone, and the window's card under mocked IPC, but not
  the two together;
- the dock's *Send to computer* icon on the dock itself, which dock.py adds at
  the next start of the desktop; and Downloads on Windows and Linux.
