# Sound in the Linux guest

The XFCE panel has always had a volume plugin. It has always shown a crossed-out
speaker, and its "Audio mixer" item has always answered *Failed to execute
command "pavucontrol"* — two symptoms of one fact: there is no audio device in
this container and nothing was ever installed to pretend otherwise.

There still is no audio device. What there is now is a sink backed by nothing,
and an app on the other side of a socket playing whatever falls into it.

Status: **written and compiled, not yet run on hardware.** See "What is
unproven".

---

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The packages and `/etc/pulse/dex.pa` | `linux-setup.sh` → `setup_audio` | what exists in the guest |
| Starting and killing the daemon | `linux-rt.sh` | the session's audio lifetime |
| Socket → speaker | `LinuxAudio.java` | reconnection, the silence gate, the drift cap |
| Start/stop with the container | `LinuxService.startRuntime` / `stopRuntime` | that the pump cannot outlive the guest |

```
XFCE apps ──libpulse──▶ pulseaudio ──▶ null sink "dex"
                          (guest)          │
                                           ▼  monitor
                     module-simple-protocol-tcp
                                           │  raw s16le 48k stereo
                          127.0.0.1:6081 ──┼──▶ LinuxAudio ──▶ AudioTrack
                                                    (app, :linux process)
```

## Why a null sink and a socket, and not ALSA

Android's `/dev` is bound into the guest, so `/dev/snd` is *there* — and every
node in it belongs to the media uid, which this app is not. An app may not open
them, so ALSA in the guest enumerates nothing and `module-alsa-card` has nothing
to attach to. There is no path from the guest to the speaker that does not go
through the app's own `AudioTrack`, because the app's uid is the only thing in
this picture that Android will let make a sound at all.

So the guest plays into `module-null-sink`, whose monitor is a source carrying
exactly what was written to it, and `module-simple-protocol-tcp` hands that
monitor to whoever connects. **proot is a ptrace chroot and creates no network
namespace**, so the guest's `127.0.0.1` is the app's own loopback — the same
fact the viewer has stood on for websockify since the beginning.

The wire format is deliberately the dumbest one available: raw PCM, no header,
no handshake, s16le / 48 kHz / stereo on both ends. That is what `AudioTrack`
takes natively on every device this runs on, so nothing resamples anywhere and
the pump is a `read` into a `write`.

## The control port is TCP, and that is not a preference

`module-native-protocol-unix` would put its socket under `XDG_RUNTIME_DIR`,
which is `/tmp/runtime-root`, which is on `/data` — where **SELinux denies
binding a filesystem unix socket**. That is the same measured constraint that
made `dbus-daemon` take an abstract address by hand in `linux-rt.sh`, and unlike
dbus, PulseAudio has no abstract-address option to take.

So clients reach the daemon on `127.0.0.1:4713` with `auth-anonymous=1` — a
cookie would guard a door that is already inside a house only this app's uid can
enter.

**And therefore `autospawn = no` is load-bearing, not tidiness.** A libpulse
client that finds no server starts one *itself*, from the stock `default.pa`:
that daemon hunts for hardware, finds none, exits, and leaves the client silent —
sitting next to the working daemon it never looked for. `client.conf.d/dex.conf`
names the server and turns autospawn off in the same three lines for that
reason.

## Four things in the config that must not be tidied away

1. **No `module-suspend-on-idle`.** A suspended null sink stops its monitor, and
   the monitor is the entire audio path. Loading it — which every distro's
   `default.pa` does — would make the tap go quiet whenever the desktop was
   quiet.
2. **`-n --file=/etc/pulse/dex.pa`.** `-n` is what keeps `default.pa` out of it,
   and with it `module-udev-detect` and the card probing that has nothing to
   find in here.
3. **No `-D`.** A daemonising pulseaudio calls `setsid` and leaves the script's
   process group — the one escape hatch the container's group kill cannot
   follow. Same rule, and same reason, as `--nofork` on `dbus-daemon`.
4. **`rm -rf /tmp/runtime-root/pulse` before the start.** `/tmp` survives a
   session (it is `$ROOT/tmp` on disk). PulseAudio writes a pid file in there,
   and a session that was *killed* rather than logged out leaves it behind
   pointing at a pid Android has since reused — at which point the next daemon
   refuses to start because "one is already running". Nothing in that directory
   is worth keeping: in this configuration it holds the pid file and nothing
   else.

`--disable-shm=1` is a fifth, smaller one: `memfd_create` does not work under
proot (the same thing that breaks Firefox's sandbox), and POSIX shm in here is a
directory we bind ourselves. Over TCP no client would negotiate shared memory
anyway, so turning it off costs nothing and removes a way to fail.

## What the app end has to get right

**It reconnects, and it gives up.** The pump starts with the runtime, before the
guest has booted, so the first several connects are refused and that is normal —
one log line per outage, not one per retry. It stops after ~150 consecutive
failures, because a guest-side logout ends the container without telling this
class anything, and a pump retrying forever would wake the process once a second
for the rest of the session. The counter resets on every successful connect.

**Silence is gated.** A null sink's monitor never stops: an idle desktop
produces 192 kB/s of exact zeroes. Without a gate the phone would hold an audio
output open, playing nothing, for as long as the window existed. Three seconds
of pure zeroes pauses the track; one non-zero byte resumes it. The test is `!=
0` rather than a threshold precisely because the source is `memset`, not a
microphone.

**Backlog is dropped, not carried.** The guest is a real-time producer with no
flow control of any kind, and the two clocks involved — the guest's system timer
and the phone's audio clock — are not the same clock. Drift and a momentary
stall look identical from here: bytes piling up in the receive buffer. Every one
of those bytes is latency that can never be worked off, so more than ~250 ms of
them is skipped. It costs one click; carrying it costs a desktop whose sound
runs further behind its picture the longer it is open.

**No audio focus is requested.** The tap runs for the whole life of the window
and is silent for most of it — asking for focus would pause the user's music the
moment a Linux window opened.

**The capture policy is stated out loud.** `ALLOW_CAPTURE_BY_ALL` is already the
default for `USAGE_MEDIA`, but it is what makes any of this audible *on the PC*:
a DeX session hears the guest only because scrcpy captures the phone's playback
(`--audio-source=playback --audio-dup`), and an app that opts out of playback
capture is simply silent over there. Saying it here means a later policy change
elsewhere in the app cannot quietly take the desktop's sound away.

## Costs

Four packages (~15 MB installed: pulseaudio, pulseaudio-utils, pavucontrol,
xfce4-pulseaudio-plugin), one daemon in the guest, two loopback ports, and one
daemon thread in the `:linux` process. While the desktop is silent the pump
reads and discards 192 kB/s and holds no audio output open.

Feature level 13 carries it into containers that already exist — no
reprovisioning, and nothing the user installed is touched.

## What is unproven

None of this has run on a device. Roughly in order of how quietly it would fail:

- **Whether PulseAudio runs as guest root at all.** It warns ("not intended to
  be run as root") and, in every version this has been read against, continues —
  only `--system` without root is fatal. If some build refuses, the daemon
  simply never appears and `rt.log` says so. *Test:* `pactl info` in the guest
  terminal.
- **Whether `in.available()` reports the socket backlog usefully on this
  platform.** If it under-reports, the drift cap never fires and latency grows
  slowly over a long session; if it over-reports, audio is dropped for no
  reason. *Test:* play a long video and watch A/V sync after an hour, and watch
  for "dropped Nms of backlog" in the log while nothing is wrong.
- **Whether the panel plugin picks the daemon up on the first session.**
  pulseaudio is started a second before `startxfce4`, and the plugin retries its
  connection, but the ordering has not been watched on hardware. *Test:* the
  speaker icon is not crossed out, with no logout in between.
- **Whether pausing and resuming the track clips the start of short sounds.**
  A notification ding arriving after three seconds of silence has to survive the
  resume. *Test:* let the desktop go quiet, then trigger a terminal bell.
- **Whether the phone's own volume keys reach `STREAM_MUSIC` while the Linux
  window has focus**, on both displays. Nothing here touches volume; the guest's
  own mixer is the second control.
- **Whether the DeX display hears it.** Playback capture is scrcpy's, not ours,
  and the phone's audio has been going that way already — but a null-sink stream
  from an app under `specialUse` foreground service has not been watched through
  it. *Test:* play something in the guest with audio forwarding on and listen at
  the PC.
- **How much the container's start costs now.** One more apt phase on the first
  pass; nothing on later ones.
