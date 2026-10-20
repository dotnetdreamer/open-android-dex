# Linux apps as windows of their own

The phone's Linux has always been one window: XFCE, whole, in the Linux
window. This puts its apps beside the Android ones instead. Firefox, VS Code,
GIMP, the terminal, the file manager — everything in XFCE's Applications menu
is in the desktop's app drawer too, with a small Linux mark on its icon, and
opens in a window of its own: the desktop's title bar with all its buttons, a
taskbar button with the app's own icon, a place in the stack with the Android
windows around it. A second window the app opens (a dialog, "New Window", a
terminal from the file manager's menu) is a window of its own as well. Menus
open where they belong, over everything; copy and paste cross between Linux
and the desktop both ways; typing works in any layout and any script.

Nothing about the XFCE desktop changes, and the two can run at the same time.

This record exists for the reason the others do: several decisions below look
arbitrary until you know what was measured, or what the platform refuses.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The display | `openandroiddex-linux/linux-win.sh` | a second X server, `:2`, with no desktop on it; its own D-Bus; the apps it is asked to start |
| Starting it | `LinuxService` (`ACTION_WIN_RUN`, `ACTION_WIN_STOP`) | the session's process and its stdin, a fresh cookie for each, its sound |
| The protocol | `X11.java` | X11 on the wire: the requests, replies and events below, Composite, DAMAGE, XFIXES, XTEST, MIT-SHM, RANDR, BIG-REQUESTS |
| The window manager | `LinuxWm.java`, `X11Keys.java` | managing and placing windows, reading their pixels, input, the pointer's shape, the clipboard, XSETTINGS |
| The windows | `LinuxWindows.java`, `LinuxWindowActivity.java`, `LinuxWinView.java` | a task per window, an overlay per menu, Android's input turned into X's, the taskbar's and the title bar's questions |
| Drawing | `LinuxWinGl.java` | one GL thread: a texture per window, only what changed uploaded, drawn one pixel to one pixel |
| The apps | `LinuxApps.java`, `LauncherActivity` | the guest's `.desktop` files, read from outside, in the app list |
| Title bars, taskbar | `CaptionService`, `LauncherActivity` | titles, ✕, what an app asks of its window, a tile per window |
| Check | `test/linux-windows-test.mjs`, `test/linux-windows/` | the pure parts on a computer's JVM |

## Why a second X display, with this app as its window manager

The XFCE desktop is X display `:1`, and xfwm4 is its window manager: there
can be only one per display, and it draws the decorations, the shadows and
the desktop under them. So Linux apps that are to be windows of their own run
on a display of their own, `:2`, with no desktop, no panel and no window
manager but this app.

What was weighed for getting each window's pixels out:

- **Cutting windows out of one framebuffer** — `:1`'s, through the raw RFB
  the computer's desktop window already reads (`LinuxStreamRfb`). Windows on a
  desktop overlap, and an overlapped window has no pixels where it is covered;
  keeping X windows apart in a framebuffer large enough to hold them all side
  by side costs the memory of all of them at once and puts every menu in the
  wrong place.
- **Xpra.** It does exactly this job — but as a Python server under ptrace
  (proot), with a protocol of its own that would need a client written for
  Android from nothing.
- **A small X client in the guest**, C against libXcomposite, handing pixels
  to the app. A binary per architecture to build and ship into a rootfs that
  is otherwise the distribution's own, and a second protocol between it and
  the app.
- **Chosen: the app speaks X11 itself.** The launcher connects to `:2`'s
  socket and is its window manager and its compositor in one: the Composite
  extension redirects every top-level window into a pixmap of its own — so
  overlap does not matter, every window has all of its pixels all of the
  time — DAMAGE says which rectangles changed, and the image comes out with
  GetImage, or straight into memory shared with the X server (MIT-SHM). X11's
  wire protocol is fixed layouts and a few dozen requests; `X11.java` is that
  much and no more.

**Xvnc, not Xvfb**, for `:2`. It is already in every guest (the desktop is
Xvnc), so nothing new is installed and no `FEATURE_LEVEL` bump re-runs the
setup script, and it has every extension this needs — measured on TigerVNC
1.13.1, Ubuntu 24.04's: Composite, DAMAGE, XFIXES, XTEST, RANDR, MIT-SHM 1.2
(the version that takes a file descriptor), BIG-REQUESTS. Its VNC port, 5902,
is loopback only and takes the desktop's VNC password, for anyone who wants to
look at the bare display.

**A D-Bus of its own** (`dex-win-bus`), not the desktop's. Apps that keep one
instance — the terminal, the file manager, Firefox — hand a second launch to
the first over the bus, and on the desktop's bus that first one is on `:1`:
the window would open inside XFCE.

## One set of coordinates

The X screen is the desktop display's size, and each X window is kept exactly
where its Android window's content is (`LinuxWinView` says where, four times a
second and before every click; `LinuxWm.placed` moves it). So:

- a pointer position on the desktop *is* a position on the X screen, and input
  goes in where it was aimed with no translation;
- an app positioning a menu against the screen's edge sees the real edge —
  and `_NET_WORKAREA` stops above the taskbar, so menus flip before they reach
  it;
- X windows are stacked as Android stacks them: a window is raised on X when
  it is focused or clicked, in the same batch of requests as the click itself,
  and sent to the bottom when it is minimised, so a window nobody can see never
  takes a click meant for one that is showing where it used to be.

The X screen grows (RANDR) when the display grows; it never shrinks, and a
window hanging off the display's edge is fine — its pixels are its own
pixmap's either way.

## Pixels

Composite's *manual* redirect: the X server draws nothing to the screen at
all. DAMAGE, at the delta-rectangles level, reports what changed; the next
frame clears the damage and asks for the images **in the same batch, clear
first** — whatever is drawn after the clear is reported afresh, and whatever
was drawn before it is in the images. Rectangles are clipped to the window and
merged into their bounding box when that costs little more than the parts.

The images land in memory shared with the X server where Android allows it:
a `memfd` (Android 11 and later) handed over with MIT-SHM's AttachFd. System V
shared memory, the kind every other X client uses, does not exist on Android's
kernel. Older phones get the same images over the socket.

`LinuxWinGl` draws: one thread, one GL context, a texture per window, only the
changed rectangles uploaded (`glTexSubImage2D`), and the texture drawn into the
window's surface one pixel to one pixel, its top-left at the surface's — never
scaled, so text is exactly as the app drew it. The bytes are X's (B, G, R,
unused) and the shader reads them back the right way round, as the computer's
desktop video does.

## Menus

Menus, tooltips and drop-down lists are override-redirect windows: they
position themselves on the screen and routinely reach past the window they
belong to. Each one is shown in an overlay of its own at its own place,
above every window and the taskbar — the caption service's accessibility
overlay, which needs no permission, or the app's own overlay where that is
not running — and drawn like any window.

A click that should close a menu can land on an Android window, which X never
hears of. When the overlay is told of a touch outside it and none of our
windows took the click, the window manager clicks the bare X screen beside
everything: with a menu up, the press goes to the menu's grab and closes it;
with nothing grabbing, it lands on the root, where nothing listens.

## Input

**The pointer** goes in with XTEST at the event's raw display position, which
is the X position (above). A press raises the window and gives it X's
keyboard focus in the same batch — click to focus, as a window manager does.
The wheel is buttons 4 to 7, in whole steps with the fractions kept.

**Keys go by meaning**, the rules the computer's desktop window follows for a
browser (`keysym.ts`): a character the desktop's own layout made is typed as
that character; a key with Ctrl, Super or a plain Alt is a shortcut, the key's
own letter under the modifiers held. XTEST presses keycodes, so `X11Keys` finds
each keysym in the X layout and Shift or Level 3 is pressed or let go around it
as that layout needs, then put back; AltGr and a Mac's Option are let go around
the character they made. A keysym the layout lacks — é on a us layout, any CJK
character, an emoji — is lent a keycode nobody uses, as xdotool and TigerVNC's
own server do. X's own key repeat is off on `:2` (`xset r off`): Android repeats
a held key itself.

What scrcpy sends for the computer's ⌘V, ⌘C and ⌘X — Android's PASTE, COPY and
CUT keys — becomes what the app takes for them: Ctrl+V, C and X, and in a
terminal, where Ctrl+C is an interrupt, Ctrl+Shift+V, C and X (a terminal is
told by its window class). A paste first hands Linux the clipboard scrcpy has
just set. The CUA keys (Shift+Insert and the rest) were the first try and the
wrong one: xfce4-terminal copies with Ctrl+Shift+C only, and its Shift+Insert
pastes the selection, not the clipboard.

## Windows

- A top-level window that maps gets a task: where the app's windows were last
  left (the window memory, keyed by the app's window class, so Firefox opens
  where Firefox was), else where it asked to be, else centred; a dialog
  centred over its window.
- The title is the window's (`_NET_WM_NAME`, `WM_NAME`), as it changes; the
  icon is the window's own (`_NET_WM_ICON`), else its app's from the
  `.desktop` file.
- ✕ asks the app — `WM_DELETE_WINDOW`, or the client is disconnected when it
  does not take that — and the app may ask about unsaved work first. The task
  goes when the window does. A task closed from outside (the Task Manager)
  asks the same; a window the app keeps gets its task back.
- What an app asks of its window: to come forward (`_NET_ACTIVE_WINDOW`), to
  be minimised (`WM_CHANGE_STATE`), fullscreen (maximised: the desktop has no
  fullscreen for one window), a new size (the task resized from its top-left).
  A move it asks for is not Android's to grant; it is told where it really is
  (ICCCM 4.1.5).
- The taskbar has a tile per window, but not for a dialog over another window
  or one that asks to be left off.

## The clipboard

Both ways. What Linux copies (`CLIPBOARD`) goes on the desktop's clipboard —
and from there to the computer's, which scrcpy keeps in step. Text merely
selected in Linux stays in Linux. What the desktop has goes to Linux when a
Linux window comes to the front, and at once while one is in front — the rule
the computer's desktop window follows (`clipboardShare.ts`), and Android lets
only the focused app read it anyway. It goes in as both `CLIPBOARD` and
`PRIMARY`, as Xvnc does for the XFCE desktop, so Ctrl+V, a terminal's
Shift+Insert and a middle click all paste it. Large texts come in pieces
(INCR) and go out in one request (BIG-REQUESTS).

## The session

`linux-win.sh` starts the first time a Linux app is opened, at the desktop
display's size and DPI, and reads one line per app to start. It ends three
minutes after the last window closes, when the app process goes (the DeX
session ending force-stops it), or when the container is reinstalled. Apps are
started without `setsid`, so the session's one group kill takes them all, as
`linux-rt.sh`'s does.

**Who gets in.** The X socket is abstract, which any app on the phone can
reach. Each session gets a fresh cookie (MIT-MAGIC-COOKIE-1) that Xvnc checks;
the service writes it, the launcher reads it from the same file, and apps in
the guest find it through `XAUTHORITY`.

**Look and size.** XSETTINGS from the guest's own xfconf `xsettings` channel —
the theme, icons, fonts and switches the XFCE desktop has — with `Xft/DPI` from
the desktop display's density, so a Linux app is the size an Android one is
beside it. Apps run in `C.UTF-8`: without a UTF-8 locale a terminal shows an
accented letter, a dash or any CJK as nothing at all.

**Sound** comes from the desktop's PulseAudio when the XFCE desktop is running,
and the window session starts the same daemon on the same ports when it is not.

## Verified, and not yet

Off the phone (`node test/linux-windows-test.mjs`, 49 checks): the pure parts
on a computer's JVM — keysyms in a us layout's columns, Shift and Level 3,
keycodes lent and never taken back while held; the cookie; xfconf to
XSETTINGS and the wire format read back; damage merged; the `.desktop` scanner
on a scratch rootfs, links that name guest paths included.

Against the guest's real Xvnc (TigerVNC 1.13.1, Ubuntu 24.04 arm64, in an
Android 16 emulator under adb root; the window manager on a computer's JVM over
`adb forward`, 2026-10-20): every extension present, MIT-SHM 1.2 among them;
`linux-win.sh` itself starting the display and its D-Bus and running apps from
its stdin; xterm and xfce4-terminal managed, their pixels read out of the
composite pixmaps, typing including é — ü € and CJK (as code points; the
guest had no CJK font) once the locale was UTF-8 — which is how `LANG=C.UTF-8`
came in; mousepad's File menu as a popup with its own pixels; Ctrl+A, Ctrl+C
in mousepad reaching the desktop side as the exact text, and the desktop's text
pasted in with Ctrl+V; closing it asking "Save changes?" in a window of its own,
centred on its parent, and "Don't Save" closing both.

On the phone (Galaxy S25 Ultra, Android 16, the desktop app from `tauri dev`,
2026-10-20):

- The drawer lists the guest's apps with the Linux mark — Firefox, Chromium,
  VS Code, the file manager, the terminals — and search finds them beside the
  Android ones ("term": Terminal Emulator and Xfce Terminal, Linux, and
  Termux, Android).
- A launch starts the window session; the launcher is its window manager
  within half a second, with shared memory; the first window is up 0.8 s
  after the tap for a terminal, 1.3 s for Firefox and 2 s for VS Code.
- Thunar: the caption titled from X ("root - Thunar"), its own icon on the
  taskbar, the guest's Arc-Dark theme. Its File and Go menus open as overlays
  exactly under their titles, also after the window was dragged across the
  screen; a click on the Android desktop closes them. "Open Terminal Here"
  opened the terminal as a second window, a task and a tile of its own.
- The taskbar tile raises a window; dragging the caption moves the X window
  with it; maximise fills the display (the terminal 190×50) and stays —
  after a fix: the terminal's answer to its new size, rounded to whole
  cells, had been taken as a resize and undid the maximise; minimise hides it
  with its tile kept, and the tile brings it back with its content.
- Keys through Android's own key events: ASCII and every shifted symbol,
  Ctrl+Shift chords, Return; Android's COPY and PASTE keys as Ctrl+Shift+C
  and V in the terminal.
- The clipboard: text copied in the Linux terminal on the Mac's clipboard
  (through scrcpy); text copied in an Android text field pasted in the
  terminal, by Ctrl+Shift+V and by the PASTE key.
- ✕ closing Firefox through WM_DELETE_WINDOW, its task and tile going with it.
- VS Code: the user's workspace in a window of its own, a file opened from its
  Explorer, the caption following its title, the wheel scrolling it.
- Chromium: its window in a second, DuckDuckGo as its start page, a URL typed
  and Wikipedia loaded, the caption following the page's title.
- The terminal reopened where the last terminal had been left (the window
  memory, by window class).

Found on the phone, and not this feature's: **Firefox 158's tabs crash under
proot** — "IPDL protocol error: Shared memory PlatformHandle is not safe to
map", then signal 11, for every content process — on the XFCE desktop exactly
as in a window of its own (checked on both displays). Firefox's own window
works; the pages do not load. It needs a fix in the guest. Chromium, from the
same guest, loads pages normally.

Not yet:

- the computer's own mouse through scrcpy (hover, right and middle buttons,
  a trackpad's fractional wheel) and typing from its keyboard: every check
  above injected Android events with `input`; ⌘V and ⌘C from the Mac itself;
- the pointer's shape from X (XFIXES → PointerIcon), which only shows under
  `--mouse=uhid`;
- GIMP and IntelliJ windows; apps that draw their own title bar (Firefox,
  VS Code, Chromium) show it under the desktop's;
- frames per second of a window scrolling or playing video;
- an app's own requests: a dialog growing (resize), F11 (fullscreen →
  maximise), minimising itself;
- a display that grows (RANDR), the launcher's process restarting under a
  running session (`resume`), the session ending three minutes after its last
  window;
- Android 10 and older (no `memfd_create`: images over the socket).
