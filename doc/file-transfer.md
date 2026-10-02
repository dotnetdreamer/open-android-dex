# File transfer

*Design record for the File transfer window (2026-09). **This extends
[`web-viewer.md`](web-viewer.md), it does not replace it.** That document's
[§Files](web-viewer.md#files) is this repository's existing decision about
moving files onto the phone — uploads land in `/sdcard/Download`, they narrate
themselves over `ACTION_TRANSFER`, and the all-files grant decides how much
works. All three of those rules still hold. What changes here is one of them,
deliberately, and the reconciliation is written down in
[§Two file stories, reconciled](#two-file-stories-reconciled).*

A two-pane window on the phone's DeX desktop. The left pane is the **computer's**
drives and folders; the right pane is **this phone's** storage. Each side
navigates on its own, and two buttons between them copy the selection into the
other pane's current folder, in either direction.

The whole feature is one TCP connection the phone opens to the computer, and the
only reason it is possible at all is that `adb reverse` is the mirror image of
the `adb forward` this project already depends on for window management.

This record exists for the same reason `custom-titlebar-v2.md` does: three of
the decisions below look arbitrary until you know what was measured, and
re-litigating them costs days.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The host file service | `src-tauri/src/files.rs` | the loopback listener, the verbs, `std::fs` |
| The tunnel | `src-tauri/src/adb.rs` | `reverse_files_port`, and its `--remove` twin |
| The beacon and the lifecycle | `src-tauri/src/scrcpy.rs` | `am broadcast …FILES`, start, re-arm, teardown |
| Beacon state | `HostLink.java` | os, token, freshness — memory only |
| The client | `HostFiles.java` | one control socket, one socket per file |
| The copy engine | `FileTransfer.java` | queue, progress, cancel, `.part`, media scan |
| The window | `FileTransferActivity.java` | two panes, selection, the arrow column |
| The phone pane's filesystem | `WebFiles.java` | listing, unique names, the drop card |

Nothing new lives in `openandroiddex-wmd`, nothing rides the `content query`
queue, no Tauri command is registered, and `Cargo.toml` gains no dependency.
Those are all consequences of the transport decision below rather than
coincidences.

## Where the window lives, and why it is not on the PC

Every other SYSTEM APPS tile — Settings, Linux, Docker, the Web viewer, the Task
Manager — opens a freeform window **on the DeX desktop**, with a caption strip, a
taskbar tile and remembered geometry (the Web viewer is supposed to and does not,
which is a pre-existing omission, not a different design — see below). Two
PC-side designs were considered and both fail structurally, not fixably:

- A pane inside the Tauri window means the user leaves the mirrored desktop to
  move a file into it. The DeX window is usually fullscreen (`shared.fs_on`), so
  on macOS the Tauri window is on a **different Space** — the file manager is not
  merely elsewhere, it is off-screen behind a desktop-switch animation.
- A separate PC window has the same problem plus a second window to manage.

The requirement this feature was asked for — "obvious with zero explanation" — is
the one both of those miss. So the window is an ordinary in-desktop window built
from `TaskManagerActivity`, which is the smallest complete example of one, and it
registers in all six of the places a window has to register: the manifest,
`CaptionService.isDesktopTask`, `CaptionService.onClose`, **both** chains of
`addOwnWindowTiles`, and `TaskManagerActivity.ownRows`. Missing any of them is
silent — a window absent from `isDesktopTask` is classified as the desktop
background and wears no caption at all, which is the defect `WebActivity` has
today, and one added to `addOwnWindowTiles`' glyph chain but not its click chain
opens Settings instead of itself. `WindowMemory` is the one place that needs no
registration: it keys on the activity name, so the geometry is remembered the
moment the window exists, and only the comment that counts the windows had to
change.

## The transport, and the three that lost

The phone has to ask the computer for a directory listing. There were four ways
to do that and three of them were rejected on measurements that already exist in
this tree.

**The `content query` request queue — rejected, and forbidden.** This is the
existing launcher→PC channel: the launcher writes a row, the PC's request pump
polls for it every **150 ms** (`scrcpy.rs:3020`) and each drain costs roughly
**990 ms**, because `content` boots a JVM per invocation. A folder click would
therefore cost about **1.1 s** before any bytes moved, and a deep folder tree is
nothing but folder clicks. `AGENTS.md:24-26` forbids new traffic on it outright,
and the queue could not carry a path anyway: `safe_arg` restricts arguments to
`[A-Za-z0-9._-]`, so `C:\Users\ik\My Documents` is not expressible, and a
malformed command is **silently consumed** ("unknown command: consume",
`scrcpy.rs:2530`) rather than reported.

**A mailbox verb in `wmd` — rejected on the architecture rule.** `wmd` is the
uid-2000 daemon and it is the fastest channel in the project, but `AGENTS.md`
fixes it as "no UI, no policy": it does task enumeration, bounds, z-order,
focusability and caption-strip reservation, and nothing else. Its `serve()` is
per-connection and its `handle()` is a pure switch — there is no queue and no
mailbox to add a verb to, so this would not be one verb but a second
architecture inside the privileged process. A file service is policy. It stays
out.

**`adb push` / `adb pull` — rejected on two independent hazards.** `run_adb_full`
buffers the child to EOF and **hard-kills at `ADB_TIMEOUT = 25 s`**
(`adb.rs:147`), so any file that takes longer than 25 seconds to move is
truncated by the harness rather than by the link. And `adb push` writes its
progress to stderr **without trailing newlines** — a hazard this project has
already been bitten by and documented at `transfer.rs:93-102`, where
`Batch::find` needs an `ends_with` fallback precisely because adb's last progress
chunk shares a line with the message after it. Building a file manager on a
channel whose progress output cannot be reliably split into lines is choosing to
inherit that bug at a larger scale.

**Loopback TCP over `adb reverse` — chosen.** The measured round trip over
`adb forward` is **2.57 ms median, 5.30 ms p95, 10.5 ms max** over 200 PINGs
(`custom-titlebar-v2.md` §0.1, measured PC→daemon), and a reverse is the same
plumbing pointed the other way. A folder click is therefore roughly 400× cheaper
than the queue it replaces. Bytes stream on the socket, which buys three things
`adb push` cannot: byte-accurate progress, a cancel that is a `socket.close()`,
and no 25-second ceiling.

```
┌── PHONE ───────────────────────────────┐        ┌── PC (Tauri) ─────────────┐
│ FileTransferActivity  (new window)     │        │                           │
│   left pane  = the computer  ──────┐   │        │  files.rs                 │
│   right pane = this phone          │   │        │   TcpListener 127.0.0.1   │
│      via WebFiles.list/receive     │   │        │   one thread per conn     │
│                                    │   │        │   std::fs only            │
│ HostFiles.java (WmClient twin)  ───┼───┼── adb ─┼─▶ 127.0.0.1:<hostPort>    │
│   control socket + data sockets    │   │ reverse│                           │
│   127.0.0.1:7192                   │   │        │  scrcpy.rs enforcer:      │
│                                    │   │        │   am broadcast …FILES     │
│ filesReceiver ◀── am broadcast ────┴───┼────────┼── --es os --es token      │
└────────────────────────────────────────┘        └───────────────────────────┘
```

`adb reverse`'s argument order is `<device> <host>` — the **opposite** of
`forward`. Getting it backwards fails silently, which is why it is stated in the
function's own doc comment as well as here.

## The wire protocol

Line-oriented ASCII over TCP, `\n`-terminated, deliberately drivable by hand with
`nc`. That is the rule `WmDaemon` sets for the other socket in this project, and
diagnosing a socket you can talk to yourself is worth more than a compact
encoding.

**Encoding.** Every path, name, label and error detail is **base64url without
padding** (alphabet `A-Za-z0-9-_`). That keeps `split("\\s+")` a valid parser and
survives spaces, quotes, `$`, `|`, newlines and non-ASCII names — none of which
the `content query` channel can express at all. Java uses
`Base64.URL_SAFE | NO_WRAP | NO_PADDING`; Rust mirrors `transfer::b64`
(`transfer.rs:341`) with the url alphabet and padding suppressed.

**Handshake — the mandatory first line on *every* connection, control or data:**

```
HELLO <token> 1                 -> OK <b64hostLabel> <os> <sep>
                                   os  = win | mac | linux
                                   sep = 5c (backslash) | 2f (slash)   as a hex byte
                                -> ERR auth        (then the host closes)
```

**Control connection — one per open window, persistent:**

```
ROOTS                           -> OK <b64json>
LIST <b64path>                  -> OK <b64json> | ERR <code>
MKDIR <b64path>                 -> OK | ERR <code>            (mkdir -p semantics)
FREE <b64path>                  -> OK <freeBytes> <totalBytes> | ERR <code>
UNIQUE <b64dir> <b64name>       -> OK <b64name>               host-side "report (2).pdf"
PLAN <b64path>                  -> OK <b64json> | ERR <code>  flat recursive walk of a folder
BYE                             -> (host closes)
```

**Data connection — a fresh socket per file, closed after:**

```
GET <b64path>                   -> OK <size>\n  then exactly <size> raw bytes, then close
                                -> ERR <code>
PUT <b64path> <size>            -> OK\n  client writes exactly <size> bytes
                                   -> OK <b64landedName>      (host renamed .part → final)
                                -> ERR <code>
```

A data socket per file rather than a framed multiplex on the control socket is a
deliberate simplification: a copy in flight must never block browsing, and with a
socket of its own, cancel is `socket.close()` and needs no protocol at all.

**Errors** are `ERR <code>` or `ERR <code> <b64detail>`, with codes `auth`,
`proto`, `notfound`, `denied`, `notdir`, `isdir`, `exists`, `io`, `space`,
`toobig`, `unknown`. The code is what the UI branches on; the detail is what the
log gets.

**JSON payloads**, camelCase, decoded with `org.json` on the phone:

```jsonc
// ROOTS
[{"label":"C:","path":"C:\\","kind":"drive"},
 {"label":"Home","path":"/Users/ik","kind":"home"}]
// kind = drive | volume | home | desktop | documents | downloads | pictures | videos

// LIST
{"path":"C:\\Users\\ik","parent":"C:\\Users","truncated":false,
 "entries":[{"name":"notes.txt","path":"C:\\Users\\ik\\notes.txt",
             "dir":false,"size":1234,"modified":1738000000}]}

// PLAN — for a PC→phone folder copy
{"root":"C:\\Users\\ik\\trip",
 "dirs":["photos","photos/raw"],                       // relative, ALWAYS '/'-separated
 "files":[{"rel":"photos/a.jpg","path":"C:\\…\\a.jpg","size":91234}],
 "truncated":false}
```

Three properties of `LIST` are load-bearing:

- **Entries carry their full path.** The phone never joins a path with a host
  separator, so there is exactly one place in the system that knows what `\` means
  and it is the machine that owns the filesystem. `sep` is handshaked anyway,
  because the *destination* of a phone→PC copy is a path the phone composes.
- **Ordering is directories first, then case-insensitive name** — byte-identical
  to `WebFiles.list()`, so the two panes can never disagree about what sorted
  means and a user comparing them side by side sees one convention.
- **The cap is 5000 entries, with `truncated` saying so.** There is no view
  recycling anywhere in this shell — the drawer's `GridView` is the only
  adapter in the whole launcher — so an uncapped `/sdcard/DCIM` would inflate
  thousands of views on the main thread. The cap is a cap, not a fix; introducing
  the shell's first adapter-backed list is a larger change than this feature.

**Recursion lives on the sending side, once per direction.** PC→phone is
`PLAN` → `mkdirs()` per `dirs` entry → one `GET` per file. Phone→PC is a local
`File.listFiles()` walk → `MKDIR` per directory → one `PUT` per file. Neither
side ever implements the other's tree walk, symlinks are not followed in either
direction, and the count of what was skipped is reported rather than hidden.

## The beacon, the token, and an honest security posture

The phone has to learn two things: that a computer is there at all, and what
credential to present. Both arrive on one new broadcast, sent on the same
`forced || changed || ticks % 50 == 0` cadence as `RUNNING`:

```
am broadcast -a com.ccrstech.openandroiddex.launcher.FILES \
  -p com.ccrstech.openandroiddex.launcher \
  --es os win|mac|linux --es token <32 hex chars>
```

**The `-p` is load-bearing and is commented as such at both ends.** `RUNNING` and
`TRANSFER` are sent deliberately *without* a package, so any receiver of ours can
hear them; this one carries a credential, so it is addressed. `GESTURE` already
does the same thing and this copies it.

The token is 16 bytes from `wireless::fill_random` (already `#[cfg]`-twinned for
Windows and not-Windows, so no new dependency), hex-encoded, minted per session.
It is held **in memory only**, in `HostLink`, and is never written to `DexPrefs`:
a persisted token would outlive the host that issued it. `KEY_HOST_TOUCHPAD` is
persisted, but it only *annotates* a Settings section — it does not authorise a
socket.

**The beacon deliberately does not carry a port.** The device port is
`7192`, a compile-time constant on both sides, sitting one above `wmd`'s 7191.
The host port is whatever `files.rs` managed to bind (7192, scanning to 7199) and
the phone never learns it — `adb reverse tcp:7192 tcp:<hostPort>` hides it. That
is the whole reason a forged beacon buys an attacker nothing.

What is genuinely exposed, stated plainly rather than argued away:

- **The reversed device port is reachable by any app on the phone.** Loopback on
  Android is not per-uid. This is the *same* exposure `wmd` already accepts on
  7191 — and 7191 speaks with shell authority, so 7192 is strictly the smaller
  of the two.
- **`ACTION_FILES` is an exported receiver**, because the sender is
  `am broadcast` from another uid and there is no way for it not to be. Another
  app can therefore forge a beacon. What it gains: the window tries to connect
  with a bogus token and gets `ERR auth`. What it does not gain: any ability to
  redirect where the window connects, because the port is a constant and is never
  sent.
- **The remaining attack is squatting 7192 before the session starts.** A
  `signature`-level permission on the receiver was considered and does not help
  here — it protects against a forged beacon, which is already harmless, and does
  nothing against a squatter. It was left out rather than shipped as security
  theatre.
- **The host listener binds `127.0.0.1` only, never `0.0.0.0`**, and `HELLO` must
  be the first line on every connection including data connections, so a
  connection that guessed the port still cannot read a byte without the token.

The listener's accept loop polls at 200 ms so it observes both the session stop
flag and `session_display(...).is_none()` — the leak rule the freeform enforcer
already follows. For two seconds after any accept it polls every 2 ms instead: a
copy opens one data socket per file, and at 200 ms each of them waited out the
poll in the backlog — measured at 208 ms per file for a folder of small files,
against ~3 ms with the short poll. `files::forget(&key)` runs beside
`transfer::forget(&key)` at session teardown and `files::shutdown_all()` runs
beside `scrcpy::kill_all` on app exit, because a bound listener must not outlive
the process and a per-session flag alone races the door.

## Why the tile is gated on `!onPhone()` and not on the beacon

The obvious gate is "show the tile when a computer has announced itself". It was
rejected.

`systemTiles()` is evaluated inside `buildUi()`, which runs from `rebuildShell()`.
A beacon gate makes tile visibility depend on something that becomes true roughly
five seconds into a session, so it needs a debounced, edge-triggered
`rebuildShell()` — an entire new invalidation mechanism, whose visible effect is
a **full view-tree teardown of the desktop a few seconds after the user gets
there**.

`!onPhone()` costs nothing: it is true from the first frame, and it is already
re-evaluated on display change through `displayListener` →
`maybeRebuildForDisplay()` → `rebuildShell()` → `buildUi()`. It is also the more
honest predicate. The left pane of this window *is* the computer's filesystem;
when the shell is drawn on the phone's own screen there is no computer, and there
never will be one during that session. That is the same rule that hides Docker
where the ABI cannot run it.

The consequence is deliberate and is the point: **a computer that is there but
not answering is a fault, and a fault gets an error state inside the window**
("The computer is not answering") with a Retry button — not a tile that silently
vanishes. A control that appears and disappears on its own teaches the user
nothing; a control that says what is wrong teaches them everything.

## Two file stories, reconciled

`web-viewer.md` §Files fixed three rules for getting a file onto this phone.
Two survive unchanged here, and the third is the one thing this feature exists to
change:

- **Uploads land in `/sdcard/Download`** — *changed, on purpose.* This window's
  whole premise is that the user chose a destination folder, so a copy lands
  where they navigated. `WebFiles.finished` hard-codes `Web.DEF_UPLOAD_DIR`, so a
  five-argument overload taking the directory was added and the existing
  four-argument one delegates to it. Without that the HUD card would name
  Downloads for a file that went somewhere else, which is worse than no card.
- **Narration rides `ACTION_TRANSFER`** — *unchanged.* At the end of a PC→phone
  batch, one `WebFiles.finished(...)` raises exactly the card on the desktop that
  a scrcpy drop raises, with its "Open folder" affordance. One drop story, now
  three ways in. `WebFiles.progress()` is deliberately **not** called per
  percent: the window's own progress row is the live narration, and a second card
  competing with it is noise, not information. The `seq` extra is still not sent,
  for precisely the reason `web-viewer.md` gives — it exists so the card can
  ignore a replay from a restarted PC, and a second counter would look exactly
  like that restart.
- **The all-files grant decides how much works** — *unchanged, and rarely
  visible.* `adb_start_launcher` appop-grants `MANAGE_EXTERNAL_STORAGE` on every
  desktop connect and never revokes it, and this window only exists when a
  computer made the display, so the degraded branch is nearly unreachable in
  practice. It is still handled, and its "Give access" button routes through
  `WebFiles.requestAllFiles(activity, opts)`'s **confirm-first** flow rather than
  straight to the OS screen: revoking the op makes the platform kill the whole
  app id (`StorageManagerService.killAppForOpChange`), taking the desktop's HOME
  task down with it. That dialog moved out of `LauncherActivity` and into
  `WebFiles` as part of this change, because two windows now offer the same
  grant and a second copy of the warning is a second place to forget it. What
  stays on the desktop side is the only part it alone can do: shaping the OS
  screen into a freeform rect on this display.

Phone→PC sends **no broadcast at all.** The `ACTION_TRANSFER` vocabulary is
entirely inbound — "Copying to %s", and an "Open folder" that opens a folder
**on this phone** (`/sdcard/Download` when the broadcast names none). Reusing it
for an outbound copy would put a card on the phone pointing at a folder on the
computer.

## Copy semantics

- **Never a silent overwrite, and never a prompt.** A name that already exists at
  the destination becomes `report (2).pdf`, on both sides: `WebFiles.uniqueIn()`
  on the phone and the `UNIQUE` verb on the host, which mirrors it exactly. A
  Replace / Keep both / Skip dialog with an apply-to-all was costed at about half
  a day and carries a TOCTOU race between the answer and the write. Keep-both
  loses nothing and cannot destroy anything.
- **Every write goes to `<target>.part` and is renamed on success**, with the
  `.part` deleted on any error or early EOF. That is the invariant
  `WebFiles.receive` and `WebRtcFiles.finishUpload` both already hold. `list()`
  now skips `.part` names alongside the existing hidden-file skip, so a pane
  refreshed mid-copy does not show `movie.mp4.part` next to real files.
- **Every file written to the phone is handed to the media scanner.** This is
  written down three separate times in this codebase already
  (`WebFiles.scan`, `TransferHud`, `Linux`) and it is the same reason each time: a
  file written through the filesystem leaves no MediaStore row, so without the
  scan the copy is on the phone and invisible in the folder the user is looking
  at.
- **Progress is emitted only when the percentage changes**, which is the choice
  `WebFiles.pump` already makes, with the same 64 KB buffer.

## The dexcast exclusion

`dexcast/` (see [`wireless-dex-no-adb.md`](wireless-dex-no-adb.md)) is the
adb-free route: Samsung's own wireless DeX over Miracast, hosted on a virtual
monitor and mirrored into a window of this app. **This tile cannot appear there,
by construction and not by a check.** There is no adb, so there is no
`adb reverse`; there is no launcher APK and no `wmd`, because both are installed
and driven over adb; and the desktop on screen is Samsung's DeX shell, not our
shell — our `systemTiles()` is not running at all. `wireless-dex-no-adb.md`
already says this in its own terms under "What this is not": no taskbar of ours,
no widgets, **no file drop**. This feature does not change that sentence and does
not add a code path that has to be excluded from it.

## Not verified on a device

**`adb reverse` has never run in this repository.** Everything above is
downstream of it. It is a one-word change from `adb forward`, which is proven
here (`forward_wm_port`, its re-arm hook, its `--remove` teardown), and it is
standard adb — but "standard" is not "measured", and this project's own history
is a list of standard things that behaved differently on One UI. The spike is
half a day and must happen first: reverse the port, listen with `nc` on the PC,
connect **from the app uid** rather than the shell uid (a `nc` from `adb shell`
proves the tunnel, not the app's access to it), then repeat over `adb connect`
and on both Windows and macOS hosts, then pull the cable and confirm the reverse
is gone and that re-issuing it from the ARM-failure branch restores it.

If a leg of that fails, **do not improvise a transport.** The fallback is written
out in full in the plan of record's Appendix A, and it changes only the direction
in which the TCP connection is opened, not the direction of the RPC: the
*launcher* binds `127.0.0.1:7192` with a `ServerSocket` (proven — `WebServer`
does exactly this), the PC keeps a plain `adb forward` and dials **in** with two
pooled connections that reconnect on EOF, and the protocol above is unchanged
except that the server end is now the phone. It costs a connection pool, a
reconnect loop, and a doc comment loud enough that nobody reading the source
mistakes the direction. It uses no unproven plumbing at all.

Also wanting eyes on a real device, in rough order of likelihood:

- Whether a copy survives the phone dropping off adb mid-file. The launcher
  process outlives the display, so a copy in flight when the cable is pulled sees
  a socket EOF, deletes its `.part` and reports failure — on a phone whose
  desktop has just vanished, i.e. with nowhere to show the report.
- Two progress stories interleaving. A PC→phone batch raises the `TransferHud`
  card while the window shows its own row, and `ACTION_TRANSFER` is
  `RECEIVER_EXPORTED`, so a concurrent scrcpy drag will interleave with it.
  `TransferHud`'s `seq` guard survives this on paper — `WebFiles.finished`
  deliberately sends no `seq` — but two concurrent producers have never been
  exercised.
- Multi-select, which has **no precedent anywhere in this shell**: not one row in
  the launcher is multi-selectable today. The selection-square affordance and its
  `theme.accentSoft` fill are invented here and are the most likely thing to need
  a second design pass after someone actually uses them.
- The recursive folder copy. Nothing else in this repository walks a tree, and
  `TransferHud` explicitly models a folder as "no size to measure". It is the
  least-precedented code in the change and should land as its own commit, after
  single-file copy is proven end to end in both directions.
- Windows drive enumeration. `volumes()` reads the drive letters from
  `GetLogicalDrives`, which touches no media, so an empty card reader is listed
  the way Explorer lists it instead of raising a "no disk" box. It and the two
  other kernel32 calls are declared in `files.rs` itself, so `Cargo.toml` is
  unchanged. Volume labels are not shown; the upgrade path, if they are ever
  wanted, is `GetVolumeInformationW`.
- The Linux root enumeration (`/media/$USER`, `/run/media/$USER`, `/media`,
  `/mnt`). Linux is now a release target (`build-linux` in `desktop.yml`), but
  this has not been run against a real Linux desktop.

Note finally that **nothing verifies any of this until a human builds it**:
neither workflow runs on push or PR, and `npm run tauri dev` from
`open-android-dex-tauri/` is the only sanctioned test flow. A Java-only change
additionally needs `node open-android-dex-tauri/scripts/build-launcher-apk.mjs`
and a phone reconnect, because `beforeDevCommand` runs exactly once per dev
session.
