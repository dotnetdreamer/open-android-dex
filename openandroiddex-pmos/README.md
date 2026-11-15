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
| `src/main.c` | the whole program: KWin's virtual output and fake input; Phosh's headless output, screencopy and virtual pointer and keyboard; the phone's keymap, the GStreamer pipeline, the framed messages on stdin and stdout, the inhibitions that keep the phone awake |
| `protocols/virtual-keyboard-unstable-v1.xml` | the virtual keyboard protocol, which no Alpine package ships, copied from wlroots 0.19.0 (MIT) |
| `Dockerfile` | the build environment: x86_64 Alpine with clang and the Wayland scanner, and an aarch64 Alpine sysroot with the phone's libraries |
| `build.sh` | builds the image once, then the program, into `prebuilt/` |
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
```

`probe` prints one line of JSON: whether KWin offers the virtual output and
fake input, what wlroots offers (output management, screencopy, virtual
pointer and keyboard) and its screens, the outputs, the encoders that open.
`snapshot` makes the screen, waits, and saves one frame of what Plasma put on
it (KWin only; on Phosh, `grim -o HEADLESS-1` does it). `serve` speaks the
framed messages on stdin and stdout that pmos.rs expects, and ends when stdin
closes or after fifteen seconds without a message. `rest` turns Phosh's
headless output off and gives the phone's panel the shell back.

On Phosh, `serve` also writes two files in the user's home, once: a marked
block in `~/.phoshdebug` that adds wlroots' headless output to the session
(from the next login), and `~/.config/autostart/com.ccrstech.openandroiddex.pmos-rest.desktop`,
which runs `rest` at each login. It starts `openandroiddex-pmos guard`, which
runs `rest` once `serve` has ended however it ended, and logs to
`~/.cache/openandroiddex/guard.log`. `~/.cache/openandroiddex/phosh-screen`
keeps the last size, which `rest` leaves the output at. Why each of these is
there: doc/pmos-desktop.md, "Phosh".

The phone needs `gst-plugins-base` and `gst-plugins-ugly` (x264), and on
Plasma `gst-plugin-pipewire`. The Plasma Mobile install on the phone this was
written on already had all three, and so did the Phosh install on the Galaxy
S24.
