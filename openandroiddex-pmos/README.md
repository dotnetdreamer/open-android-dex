# openandroiddex-pmos

The phone's half of the desktop on a phone running postmarketOS with KDE
Plasma Mobile. The desktop app (`src-tauri/src/pmos.rs`) puts this program on
the phone over SSH and starts it; it asks KWin for a virtual output the size of
the computer's screen, encodes it as H.264, and puts the computer's mouse and
keyboard on it. The design record, with what was measured and what was ruled
out, is [`doc/pmos-desktop.md`](../doc/pmos-desktop.md).

## Layout

| file | |
| --- | --- |
| `src/main.c` | the whole program: KWin's virtual output and fake input, the phone's keymap, the GStreamer pipeline, the framed messages on stdin and stdout, the inhibitions that keep the phone awake |
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
```

`probe` prints one line of JSON: whether KWin offers the virtual output and
fake input, the outputs, the encoders that open. `snapshot` makes the screen,
waits, and saves one frame of what Plasma put on it. `serve` speaks the framed
messages on stdin and stdout that pmos.rs expects, and ends when stdin closes
or after fifteen seconds without a message.

The phone needs `gst-plugin-pipewire`, `gst-plugins-base` and
`gst-plugins-ugly` (x264). The Plasma Mobile install on the phone this was
written on already had all three.
