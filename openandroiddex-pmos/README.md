# openandroiddex-pmos

The phone's half of the desktop on a phone running postmarketOS with KDE
Plasma Mobile or Phosh. The desktop app (`src-tauri/src/pmos.rs`) puts this
program on the phone over SSH and starts it. On Plasma it asks KWin for a
virtual output the size of the computer's screen; on Phosh it sizes wlroots'
headless output and makes it Phosh's primary screen. Either way it encodes the
screen as H.264 and puts the computer's mouse and keyboard on it. The design record, with what was measured and what was ruled
out, is [`doc/pmos-desktop.md`](../doc/pmos-desktop.md).

## Layout

| file | |
| --- | --- |
| `src/main.c` | the whole program: KWin's virtual output and fake input; Phosh's headless output, screencopy and virtual pointer and keyboard; the phone's keymap, the GStreamer pipeline, the framed messages on stdin and stdout, the inhibitions that keep the phone awake; the clipboard (data-control); files from the computer into Downloads, and dropped on the app under them; on KWin, the session bus service for `here.js` |
| `src/here.js` | a KWin script, loaded while the computer has the screen, that makes an app opened from a screen show on that screen |
| `protocols/virtual-keyboard-unstable-v1.xml` | the virtual keyboard protocol, which no Alpine package ships, copied from wlroots 0.19.0 (MIT) |
| `Dockerfile` | the build environment: x86_64 Alpine with clang and the Wayland scanner, and an aarch64 Alpine sysroot with the phone's libraries |
| `build.sh` | builds the image once, then the program, into `prebuilt/` |
| `cstring.sh` | turns `src/here.js` into a C string for the build (`here-js.h`) |
| `prebuilt/openandroiddex-pmos-aarch64` | what the desktop app embeds (`include_bytes!`) and installs on the phone |

## Building

```
./build.sh
```

Needs Docker, nothing else. postmarketOS is Alpine, so the program links
against Alpine's musl, libwayland-client, libxkbcommon, GLib and GStreamer, as
the phone's own programs do. It uses only long-stable symbols, so it also runs
on a stable postmarketOS with older libraries. The built binary is committed,
like the launcher's `libdexremote.so`: building the app should not need Docker.
After a rebuild, the app embeds the new binary the next time it is built.

## Running it by hand

On the phone, in its graphical session or over SSH (it finds the session's
Wayland socket and bus itself):

```
openandroiddex-pmos version
openandroiddex-pmos probe
openandroiddex-pmos snapshot --width 1920 --height 1080 --scale 1 --out /tmp/screen.png --wait 3
openandroiddex-pmos serve --width 1920 --height 1080 --scale 1
openandroiddex-pmos rest
openandroiddex-pmos clip
openandroiddex-pmos clip --set "some text"
```

`probe` prints one line of JSON: whether KWin offers the virtual output and
fake input, what wlroots offers (output management, screencopy, virtual
pointer and keyboard) and its screens, the outputs, the encoders that open.
`snapshot` makes the screen, waits, and saves one frame of what Plasma put on
it (KWin only; on Phosh, `grim -o HEADLESS-1` does it). `serve` speaks the
framed messages on stdin and stdout that pmos.rs expects, and ends when stdin
closes or after fifteen seconds without a message. `rest` puts Plasma
Mobile's docked mode back as the phone had it, and turns Phosh's headless
output off and gives the phone's panel the shell back. `clip` prints the
text on the phone's clipboard, and `clip --set` puts some there until
something else is copied, for looking at the clipboard by hand (the phone
may have no `wl-paste`). `receive` is for the app: it takes the files dropped
on the window on its stdin (below).

On Phosh, `serve` also writes two files in the user's home, once: a marked
block in `~/.phoshdebug` that adds wlroots' headless output to the session
(from the next login), and `~/.config/autostart/com.ccrstech.openandroiddex.pmos-rest.desktop`,
which runs `rest` at each login. It starts `openandroiddex-pmos guard`, which
runs `rest` once `serve` has ended however it ended, and logs to
`~/.cache/openandroiddex/guard.log`. `~/.cache/openandroiddex/phosh-screen`
keeps the last size, which `rest` leaves the output at. Why each of these is
there: doc/pmos-desktop.md, "Phosh".

On Plasma Mobile, `serve` turns on docked mode (`convergenceModeEnabled` in
`plasmamobilerc`, with kwriteconfig6 --notify) while the computer has the
screen, so apps there are windows with title bars, and puts back what the phone
had when it ends. Until then that is kept in
`~/.cache/openandroiddex/plasma-docked-before`; the guard and the autostart
entry above (written here too, for Plasma as well as Phosh) put it back if
`serve` ended without doing so. Docked mode is one setting, so the phone's own
screen has windows too while a computer is connected.

On KWin, `serve` also has KWin run `here.js` (written to
`~/.cache/openandroiddex/kwin-here.js`, loaded as the script
`openandroiddex-pmos`) while the computer has the screen: Plasma Mobile's
drawer switches to an app's window wherever it is, and the script brings it,
or a new window of the app, to the screen it was opened from. `serve` answers
the script on the session bus as `com.ccrstech.openandroiddex.Pmos`, opens the
apps' new-window actions for it, and logs its decisions as `here:` lines. It
unloads the script before the screen closes, and `rest` (the guard's, if
`serve` died) unloads one left behind. Why and how: doc/pmos-desktop.md,
"Apps open where they are opened". When the screen has gone it also has KWin
start its on-screen keyboard again (it ends the keyboard process, which KWin
restarts), so the phone's keyboard is laid out for the phone: "The on-screen
keyboard after a session".

`serve` runs on the phone's fast cores only (all but those with the lowest top
clock) and encodes with one x264 thread there; it logs the frames it sent
every ten seconds while the screen moves. Why: doc/pmos-desktop.md, "The
Galaxy S24's cores".

While a window is open, `serve` shares the clipboard both ways: it puts the
computer's text on the phone's clipboard and sends what is copied on the
phone, through Wayland's data-control (the standard `ext-data-control-v1`,
else wlroots' own), text only. Files dropped on the window come over a second
channel of the same SSH connection to `receive`, which saves them in the
user's Downloads without replacing anything ("report (1).pdf"), and those of
4 MiB or more over four more channels of that connection at once (`receive
--part`); then `serve` drops them on the
app where they were let go of, with a drag of its own. Why and how:
doc/pmos-desktop.md, "The clipboard and dropped files".

The phone needs `gst-plugins-base` and `gst-plugins-ugly` (x264), and on
Plasma `gst-plugin-pipewire`. The Plasma Mobile install on the phone this was
written on already had all three, and so did the Phosh install on the Galaxy
S24.
