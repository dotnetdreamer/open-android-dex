#!/system/bin/sh
# linux-rt.sh — run the provisioned Ubuntu desktop. POSIX sh for toybox
# /system/bin/sh, like linux-setup.sh.
#
# Spawned by whoever owns the runtime (the app's LinuxService, or a bare
# `sh linux-rt.sh &`). Two facts about this script are load-bearing:
#
#  1. It makes itself a SESSION LEADER, so its pid is also a process-group id
#     and the whole container — proot plus every guest process — dies from one
#     `kill -9 -<pid>`. That matters because the app uid cannot browse /proc:
#     pattern kills (pkill -f) and pid-identity checks both read other
#     processes' cmdlines, so under the app they are unreliable at best. A
#     group kill needs no /proc at all. Everything the session starts must
#     therefore STAY in this group — see the --nofork on dbus-daemon below.
#  2. It removes rt.pid when proot exits. rt.pid is the app's only "is the
#     runtime up" signal, so a leftover file after a guest-side logout is what
#     used to leave the viewer connecting forever to a dead session.
#
# Inside the guest (loopback only, no root anywhere):
#   Xvnc :1     tcp 5901 — VncAuth against /root/.vnc/passwd
#   pulseaudio  tcp 4713 — the control protocol, for pavucontrol and the panel
#               tcp 6081 — the raw s16le/48k/stereo tap the APP reads and plays
#               through AudioTrack. There is no audio device in the guest; see
#               /etc/pulse/dex.pa, written by linux-setup.sh.
#   websockify  tcp 6080 — serves the noVNC web root; the launcher's WebView
#               loads OUR page from it, staged in there by LinuxService:
#               http://127.0.0.1:6080/dex.html?password=<vncpass>&v=<rtpid>
#               Ubuntu's own vnc.html and vnc_lite.html are untouched
#               beside it.
#   dockfit.py  no port — keeps the dock meeting the top bar whenever a
#               viewer resizes the screen to its window. Written below.
# The XFCE SESSION is what the runtime waits on: when it ends — the user logs
# out, or xfce4-session dies — Xvnc and websockify are taken down with it and
# proot exits. Without that the X server outlived the desktop and reconnecting
# landed on a live-but-empty display: the black screen with nothing on it.

# ROOT / proot from env (app: filesDir + native-lib dir), legacy fallback else.
ROOT=${LINUX_ROOT:-/data/local/tmp/linux}

# Re-exec under setsid once, so $$ below is a session (and process-group)
# leader. Guarded by an env flag because setsid REFUSES to run when the caller
# already leads its group, and skipped entirely if the binary is missing — a
# runtime that cannot be group-killed is still better than one that never
# starts.
# `setsid sh "$0"`, NOT `setsid "$0"`: setsid EXECS its argument, and this
# script lives in the app's private storage, which is neither marked executable
# nor allowed to be (W^X for a target-SDK-35 app). Handing setsid the
# INTERPRETER means nothing ever has to exec the script itself. Measured on
# device: `setsid /path/to/script` fails with "exec: Permission denied".
if [ -z "$LINUX_RT_SETSID" ] && [ -x /system/bin/setsid ]; then
  LINUX_RT_SETSID=1
  export LINUX_RT_SETSID
  exec /system/bin/setsid /system/bin/sh "$0" "$@"
fi

# Clear the previous session's verdict BEFORE announcing this one, so the app
# can never read a live rt.pid next to a stale exit marker.
rm -f "$ROOT/rt.exit"
echo $$ > "$ROOT/rt.pid"

# Window size, written just before the spawn by whoever starts the runtime.
GEO=$(cat "$ROOT/geometry" 2>/dev/null)
[ -n "$GEO" ] || GEO=1280x800

mkdir -p "$ROOT/tmp"
# Android has NO /dev/shm (measured: `ls /dev/shm` -> No such file or
# directory), and we bind Android's /dev into the guest, so the rootfs's own
# empty /dev/shm is shadowed and POSIX shared memory simply does not exist in
# there. Firefox survives that only while it can use memfd_create, which it
# cannot under proot ("read-only dup failed; not using memfd") -- so it falls
# back to shm_open, finds nothing, and the content process dies on launch.
# A writable directory bound over /dev/shm is the whole fix; proot resolves the
# longer bind first, so this wins over -b /dev (verified with our own proot).
mkdir -p "$ROOT/shm"

PROOT=${LINUX_PROOT:-$ROOT/proot/root/bin/proot}
# Match the seccomp mode linux-setup.sh probed and cached: "1" = pure ptrace
# (Samsung/OEM, execve EPERM otherwise), empty/absent = hardware seccomp (the
# emulator + mainline; forcing ptrace there makes the guest hit ENOSYS).
[ "$(cat "$ROOT/noseccomp" 2>/dev/null)" = 1 ] && export PROOT_NO_SECCOMP=1
export PROOT_LOADER=${PROOT_LOADER:-$ROOT/proot/root/libexec/proot/loader}
export PROOT_LOADER_32=${PROOT_LOADER_32:-$ROOT/proot/root/libexec/proot/loader32}
export PROOT_TMP_DIR=$ROOT/tmp

# A shared-storage bind for file exchange (runtime only; setup has no business
# there). Best-effort: only bind it when the path is actually readable — under
# an app uid /sdcard is scoped-storage-gated and may be absent. Override with
# LINUX_STORAGE (e.g. the app's own getExternalFilesDir()).
STORAGE=${LINUX_STORAGE:-/sdcard}
STORAGE_BIND=
[ -r "$STORAGE" ] && STORAGE_BIND="-b $STORAGE:/root/storage"

# The one folder both sides can reach. $STORAGE above lives under
# /sdcard/Android/data, which no Android 11+ file manager will browse, so it has
# only ever been a diagnostics drop. This one is /sdcard/LinuxOnDeX, at the top
# of internal storage where a user actually looks: My Files, Files, a share
# sheet and USB/MTP all land on it without digging.
#
# The app pays for that placement with MANAGE_EXTERNAL_STORAGE -- a TOP-LEVEL
# name on shared storage cannot be created without it. So an unset or unusable
# $SHARED here usually means the user has not granted it; the app decides that,
# and simply does not pass the variable when it cannot use the folder.
#
# Bound straight into ~/Desktop, so it is the first thing on the guest desktop.
#
# Best-effort, and the guard is load-bearing rather than tidy: proot treats an
# unusable -b source as a FATAL startup error, not a skipped bind, so a bad path
# here would stop the container starting instead of merely costing it a folder.
# -w and not -d: a directory that exists but refuses writes is exactly the case
# that would take the session down.
#
# The MOUNTPOINT is pre-created rather than left to proot. proot registers a
# talloc destructor to remove a binding point it had to create itself, and an
# unclean exit -- --kill-on-exit, or the app's process-group SIGKILL -- never
# runs it, leaving a mode-000 stub in ~/Desktop that renders as an unreadable
# folder. Creating it here means proot hits EEXIST and registers nothing.
#
# $SHARED_BIND is expanded UNQUOTED on the proot argv so it splits into two
# items, so the path must be whitespace-free. It is: a fixed name at the root
# of internal storage.
SHARED=${LINUX_SHARED:-}
SHARED_BIND=
if [ -n "$SHARED" ]; then
  mkdir -p "$SHARED" 2>/dev/null
  if [ -w "$SHARED" ]; then
    mkdir -p "$ROOT/rootfs/root/Desktop/LinuxOnDeX" 2>/dev/null
    chmod 755 "$ROOT/rootfs/root/Desktop/LinuxOnDeX" 2>/dev/null
    SHARED_BIND="-b $SHARED:/root/Desktop/LinuxOnDeX"
    echo "shared folder: $SHARED -> /root/Desktop/LinuxOnDeX"
  else
    echo "shared folder: could not create or write $SHARED - bind skipped"
  fi
fi

# The dock's keeper, written into the guest at every start for the reason the
# app copies this script in at every start: a guest built by an older setup
# gets it with no setup pass. Started beside XFCE further down.
#
# Why it exists: dock.py stands the dock on the left edge, snapped to the
# bottom, at a LENGTH that is a percent of the screen height -- the height less
# the top bar, so the two edges meet -- and works it out once, for the screen it
# saw. The screen does not hold still any more: every viewer asks Xvnc to make
# it the shape of its window (the phone turned on its side, a window on the
# desktop display dragged, a computer through remote access), and the same
# percent of a new height leaves a gap under the top bar, or runs the dock up
# into it and over the Applications button.
mkdir -p "$ROOT/rootfs/usr/local/share/openandroiddex" 2>/dev/null
cat > "$ROOT/rootfs/usr/local/share/openandroiddex/dockfit.py" 2>/dev/null <<'PYEOF'
"""Keep the dock meeting the top bar whenever the screen changes size.

Waits on the X server and puts the dock's length right after every resize.
RandR tells the root window it was resized with a ConfigureNotify; blocked on
that, this costs nothing while nothing changes, and it dies with the X server.
The length goes through xfconfd (xfconf-query), never into the file: xfconfd
owns the channel while the session runs, and the panel writes it all back out
when it exits.

Only while the dock is where dock.py put it -- vertical, snapped to the bottom
of the left edge -- and at the length this gave it last (STATE), or, before
there is a record, at a length dock.py could have made. A dock someone moved,
or a length they chose, is theirs. The formula and the top bar's height are
dock.py's own, so the first pass over a fresh guest changes nothing.

The one argument is the session's start geometry, from linux-rt.sh.
"""
import ctypes
import subprocess
import sys

MARK = "/root/.config/openandroiddex/dock-positioned"
STATE = "/root/.config/openandroiddex/dock-length"
CONFIGURE_NOTIFY = 22
STRUCTURE_NOTIFY_MASK = 1 << 17

# The height this session started at: dock.py worked the length out for it,
# if this is the session it placed the dock in.
START_H = 0
if len(sys.argv) > 1:
    try:
        START_H = int(sys.argv[1].lower().split("x")[1])
    except (IndexError, ValueError):
        START_H = 0


def xfconf(*args):
    try:
        return subprocess.run(("xfconf-query", "-c", "xfce4-panel") + args,
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                              universal_newlines=True, timeout=20)
    except (OSError, subprocess.SubprocessError):
        return None


def panels():
    """{"panel-1": {"position": "p=6;x=0;y=0", ...}, ...} in one call.

    -l -v prints each property's name, padding, then its value; arrays print
    as <<UNSUPPORTED>>, and none of the ones read here is an array.
    """
    found = {}
    r = xfconf("-l", "-v")
    if r is None or r.returncode != 0:
        return found
    for line in r.stdout.splitlines():
        name, _, value = line.partition(" ")
        parts = name.split("/")
        if (len(parts) == 4 and parts[1] == "panels"
                and parts[2].startswith("panel-")):
            found.setdefault(parts[2], {})[parts[3]] = value.strip()
    return found


def snap(props):
    pos = props.get("position", "")
    if not pos.startswith("p="):
        return -1
    try:
        return int(pos[2:].split(";")[0])
    except ValueError:
        return -1


def top_bar(found, dock):
    """dock.py's rule: a horizontal panel snapped along the top, 0 if none."""
    for name in sorted(found):
        props = found[name]
        if name == dock or props.get("mode", "0") != "0":
            continue
        # NE, NW, NC, N -- the four that put a horizontal panel on top.
        if snap(props) not in (2, 6, 9, 11):
            continue
        try:
            return int(props.get("size") or 48) * int(props.get("nrows") or 1)
        except ValueError:
            return 48
    return 0


def number(text):
    try:
        return float(text)
    except (TypeError, ValueError):
        return None


def made_for(height, top):
    """dock.py's length for a screen this tall, as dock.py writes it."""
    return "%.2f" % (float(height - top) * 100.0 / height)


def dockpy_made(length, top):
    """Could dock.py have given the dock this length?

    It works the length out once, for the screen it first sees: this
    session's, or that of a desktop the app started before this script
    existed, every one of which was 600 to 2400 tall (LinuxService's floor and
    ceiling then). A length it could not have made was chosen by someone.
    """
    heights = [START_H] if START_H > top else []
    heights += [h for h in range(600, 2401) if h > top]
    return any(abs(float(made_for(h, top)) - length) < 0.001 for h in heights)


def ours(length, top):
    """Is the dock still at the length this gave it -- or dock.py did?"""
    if length is None:
        return False
    try:
        with open(STATE) as f:
            last = number(f.read().strip())
    except OSError:
        # Never recorded: a guest from before this, or the first pass over a
        # new one. The length is dock.py's only if dock.py placed the dock (its
        # marker) and the length is one it could have made. One someone chose
        # in Panel Preferences is theirs -- unless it happens to be one dock.py
        # could have made too, which nothing left on the guest can tell apart.
        try:
            with open(MARK) as f:
                placed = int(f.read().strip() or 0) >= 4
        except (OSError, ValueError):
            return False
        return placed and dockpy_made(length, top)
    return last is not None and abs(length - last) < 0.01


def fit(height):
    found = panels()
    dock = None
    for name in sorted(found):
        if found[name].get("mode") == "1" and snap(found[name]) == 8:
            dock = name
            break
    if dock is None:
        return
    top = top_bar(found, dock)
    if not 0 < top < height:
        return
    length = number(found[dock].get("length"))
    if not ours(length, top):
        return
    want = made_for(height, top)
    if length is None or abs(length - float(want)) >= 0.01:
        r = xfconf("-p", "/panels/%s/length" % dock, "-n", "-t", "double", "-s", want)
        if r is None or r.returncode != 0:
            return
    try:
        with open(STATE, "w") as f:
            f.write(want + "\n")
    except OSError:
        pass


class XConfigureEvent(ctypes.Structure):
    _fields_ = [("type", ctypes.c_int), ("serial", ctypes.c_ulong),
                ("send_event", ctypes.c_int), ("display", ctypes.c_void_p),
                ("event", ctypes.c_ulong), ("window", ctypes.c_ulong),
                ("x", ctypes.c_int), ("y", ctypes.c_int),
                ("width", ctypes.c_int), ("height", ctypes.c_int),
                ("border_width", ctypes.c_int), ("above", ctypes.c_ulong),
                ("override_redirect", ctypes.c_int)]


class XEvent(ctypes.Union):
    _fields_ = [("type", ctypes.c_int), ("xconfigure", XConfigureEvent),
                ("pad", ctypes.c_long * 24)]


def main():
    try:
        x = ctypes.CDLL("libX11.so.6")
    except OSError:
        return
    x.XOpenDisplay.restype = ctypes.c_void_p
    x.XOpenDisplay.argtypes = (ctypes.c_char_p,)
    x.XDefaultRootWindow.restype = ctypes.c_ulong
    x.XDefaultRootWindow.argtypes = (ctypes.c_void_p,)
    x.XSelectInput.argtypes = (ctypes.c_void_p, ctypes.c_ulong, ctypes.c_long)
    x.XGetGeometry.argtypes = (ctypes.c_void_p, ctypes.c_ulong,
                               ctypes.POINTER(ctypes.c_ulong),
                               ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int),
                               ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint),
                               ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint))
    x.XPending.argtypes = (ctypes.c_void_p,)
    x.XNextEvent.argtypes = (ctypes.c_void_p, ctypes.POINTER(XEvent))

    dpy = x.XOpenDisplay(None)
    if not dpy:
        return
    root = x.XDefaultRootWindow(dpy)
    # Selected before the size is read, so a resize in between is not missed:
    # the read is a round trip, and the request goes out ahead of it.
    x.XSelectInput(dpy, root, STRUCTURE_NOTIFY_MASK)
    win, pos = ctypes.c_ulong(), ctypes.c_int()
    w, h, other = ctypes.c_uint(), ctypes.c_uint(), ctypes.c_uint()
    height = 0
    if x.XGetGeometry(dpy, root, ctypes.byref(win), ctypes.byref(pos), ctypes.byref(pos),
                      ctypes.byref(w), ctypes.byref(h), ctypes.byref(other),
                      ctypes.byref(other)):
        height = h.value
    # The session may have started at a height the length was not made for.
    if height:
        fit(height)

    ev = XEvent()
    while True:
        x.XNextEvent(dpy, ctypes.byref(ev))
        new = height
        while True:
            if ev.type == CONFIGURE_NOTIFY and ev.xconfigure.window == root:
                new = ev.xconfigure.height
            # A burst is answered once, for the size it ends at.
            if not x.XPending(dpy):
                break
            x.XNextEvent(dpy, ctypes.byref(ev))
        # Only the height moves the dock's length.
        if new > 0 and new != height:
            height = new
            fit(height)


main()
PYEOF

# The guest session, as one string so the quoting stays in one place.
#
# A stale X lock from a killed session would stop Xvnc starting again, hence
# the rm. The guest's /tmp lives on /data, where SELinux denies FILESYSTEM unix
# sockets while ABSTRACT ones bind fine (measured on device) — so dbus-daemon
# is given an abstract address by hand instead of going through dbus-launch
# (whose /tmp/dbus-XXXX bind dies with EACCES and takes the session with it),
# and X clients reach Xvnc through the abstract @/tmp/.X11-unix/X1 that Xorg
# always binds alongside the (failing) file socket.
#
# --nofork on dbus-daemon is not cosmetic: a daemonising dbus calls setsid and
# leaves this script's process group, which is exactly the escape hatch the
# group kill cannot follow.
#
# The MOZ_DISABLE_*_SANDBOX family: every one of Firefox's child processes
# sandboxes itself with user namespaces and seccomp, neither of which survives
# a ptrace chroot, and disabling only the CONTENT one leaves the rest to crash.
# MOZ_CRASHREPORTER_DISABLE keeps a crash from stacking a modal reporter window
# on top of the desktop. The env reaches the browsers because every menu launch
# inherits xfce4-session's environment, which is this one.
SESSION='rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 /tmp/.oadx-bus
mkdir -p /tmp/runtime-root && chmod 700 /tmp/runtime-root
# The GPU (doc/linux-gpu.md). OADX_GPU names the driver the app copied into
# this guest: turnip on an Adreno phone, xclipse (RADV) on an Exynos with an
# Xclipse GPU, nothing anywhere else. Whether it really runs here is the probe
# to say, which opens the GPU the way an app would, through that driver alone.
# It runs beside the Xvnc start, so it costs the session next to nothing.
GPUPROBE=
GPUDIR=/usr/local/lib/openandroiddex/gpu
if [ -n "$OADX_GPU" ] && [ -x $GPUDIR/oadx-vkprobe ] && [ -f $GPUDIR/${OADX_GPU}_icd.json ]; then
  VK_ICD_FILENAMES=$GPUDIR/${OADX_GPU}_icd.json \
    timeout 15 $GPUDIR/oadx-vkprobe >/tmp/.oadx-gpu 2>&1 &
  GPUPROBE=$!
fi
Xvnc :1 -geometry GEOMETRY -depth 24 -rfbport 5901 -localhost \
  -rfbauth /root/.vnc/passwd -SecurityTypes VncAuth &
XVNC=$!
# Only a yes points GL at the GPU, because Mesa does not fall back by itself:
# an app told to use Zink with no working GPU under it gets no GL at all.
# Exported here, after Xvnc and before the bus: every app the session starts
# gets it, the ones the bus activates too, and the X server does not -- its
# own GLX would otherwise keep a GPU context open for nothing. Zink is GL on
# top of our Vulkan driver, presenting through its own swapchain (kopper). Xvnc
# has no DRI3, so GLX allows that only with LIBGL_KOPPER_DRI2 set, and the
# WSI software path is how each finished frame reaches the window. NOT
# GALLIUM_DRIVER=zink: that puts Zink under the software GL path, where it
# renders and never shows a frame -- measured, a black window at 1600 FPS.
if [ -n "$GPUPROBE" ]; then
  if wait $GPUPROBE; then
    echo "gpu: $(head -c 200 /tmp/.oadx-gpu)"
    export MESA_LOADER_DRIVER_OVERRIDE=zink LIBGL_KOPPER_DRI2=1 MESA_VK_WSI_DEBUG=sw
  else
    echo "gpu: not used -- $(head -c 300 /tmp/.oadx-gpu)"
  fi
fi
# NOTHING BELOW WAITS ON A CLOCK. This used to be Xvnc, sleep 2, the bus,
# sleep 1, two python3 starts one after the other, the sound server, sleep 1,
# and only then XFCE: four seconds of fixed sleeps on every launch whether the
# phone needed them or not, and no check at all that they had been enough. Now
# everything that needs neither X nor the bus starts beside Xvnc, and each
# thing XFCE needs is waited for by asking it -- the way linux-win.sh already
# waits for its own display and sound server.
#
# NO APOSTROPHES ANYWHERE IN THIS STRING -- not even in prose. It is
# single-quoted shell, so a lone one closes it early and every $VAR after that
# expands on the HOST instead of inside the guest, silently. sh -n does not
# catch it, because the quotes still balance overall.
#
# --print-address is the bus saying it is ready: dbus-daemon writes its address
# only once the socket is bound, so a file with anything in it means listening.
# The file is removed above, because a killed session leaves the last one.
dbus-daemon --session --address=unix:abstract=dex-session-bus --nofork \
  --print-address=5 5>/tmp/.oadx-bus &
DBUS=$!
# Sound, when the guest has been through a setup pass that installs it -- an
# older container simply has no audio, exactly as before. There is no audio
# device in here: the desktop plays into a null sink and the APP reads that
# sink monitor off 127.0.0.1:6081. See /etc/pulse/dex.pa.
#
# NOT daemonised, which is the dbus-daemon rule again rather than a style
# choice: pulseaudio -D calls setsid and leaves this script process group,
# which is the one escape hatch the group kill cannot follow. Its log goes to
# stderr and so into rt.log with everything else.
#
# The runtime dir is cleared first because /tmp SURVIVES a session: pulseaudio
# writes a pid file in there, and a session that was killed rather than logged
# out leaves that file behind pointing at a pid Android has since handed to
# somebody else -- at which point the next daemon refuses to start, saying one
# is already running. Nothing in that directory is worth keeping: with no unix
# socket and no cookie in this configuration, it holds only the pid file.
PULSE=
if [ -x /usr/bin/pulseaudio ] && [ -f /etc/pulse/dex.pa ]; then
  rm -rf /tmp/runtime-root/pulse
  XDG_RUNTIME_DIR=/tmp/runtime-root pulseaudio -n --file=/etc/pulse/dex.pa \
    --exit-idle-time=-1 --disallow-exit=1 --disable-shm=1 &
  PULSE=$!
fi
# The dock, applied while NO panel is running. xfce4-panel writes its config out
# when it EXITS, so an edit made during a live session is discarded on the way
# out -- which is why the launchers added at provision time never appeared.
# Doing it here, a moment before the session starts, is what makes them stick.
# Idempotent: it adds only what is not already on the panel.
# The geometry is passed because the LENGTH of the dock is computed from it:
# the panel stops exactly where the top bar ends, which needs the screen height
# and is knowable only here. Substituted by the same sed that fills the one on
# the Xvnc line -- that sed rewrites the first match on each line, and these
# are two separate lines.
#
# In the background, beside Xvnc: it only edits files, and all that matters is
# that it has finished before the panel starts, which the wait further down
# makes certain. It and theme.py write disjoint files, so they run together.
DOCK=
if [ -f /usr/local/share/openandroiddex/dock.py ]; then
  python3 /usr/local/share/openandroiddex/dock.py GEOMETRY >/dev/null 2>&1 &
  DOCK=$!
fi
# The theme, for the same reason and in the same window. Our defaults live in
# /etc/xdg and cannot be clobbered, but a guest provisioned before that existed
# already has the whole xfwm4 defaults block written into its OWN channel, and
# the user file is merged after the system one -- so a stale Default theme and
# use_compositing=true would win forever. This rewrites those, and only those:
# a setting the user actually chose is left alone. It has to happen with no
# xfconfd alive, because xfconfd caches a channel on first read and never
# re-reads it, so an edit under a live session is erased wholesale the next
# time anything dirties the channel. No-op on a guest that has no user file.
#
# It runs ONCE per guest and drops a marker when it has, after which it would
# only start python3 to exit again -- a cold interpreter start through proot on
# every launch, for nothing. So the marker is read here, where it costs a stat.
# The path is the DONE constant in theme.py.
THEME=
if [ -f /usr/local/share/openandroiddex/theme.py ] && \
    [ ! -f /root/.config/openandroiddex/theme-migrated ]; then
  python3 /usr/local/share/openandroiddex/theme.py >/dev/null 2>&1 &
  THEME=$!
fi
# Now the waits, each one asking the thing itself. The display: Xvnc answers
# a client once it is up, and xset is the client linux-win.sh asks with too.
# Thirty seconds at most, and no more polls once Xvnc has died -- XFCE then
# fails exactly as it would have after the old fixed sleep. Every desktop has
# had xset since the first one (x11-xserver-utils), but a guest someone has
# taken it out of gets the old two seconds rather than thirty of failed asks.
n=0
until DISPLAY=:1 xset q >/dev/null 2>&1; do
  n=$((n + 1))
  [ $n -gt 150 ] && break
  kill -0 $XVNC 2>/dev/null || break
  [ -x /usr/bin/xset ] || { sleep 2; break; }
  sleep 0.2
done
# The root window in the backdrop colour from the moment X answers. The app
# takes its card down once noVNC is connected, and XFCE paints its backdrop
# the better part of a second after that -- for which the picture was the bare
# X root, and that is black (measured on the S25 with the window checking at
# 4 Hz: card down at 1.8 s, black until 2.6 s, every start). xfdesktop covers
# the root the moment it draws; until then the screen is already the colour of
# the desktop it is about to be. 0.129 0.141 0.169 is the rgba1 linux-setup.sh
# writes for the backdrop; a guest whose user changed it sees this colour for
# that moment before theirs. In the background: nothing waits on it.
DISPLAY=:1 xsetroot -solid "#21242b" >/dev/null 2>&1 &
# The bus: see --print-address above. It has had the whole Xvnc start to come
# up in, so this almost never goes round even once.
n=0
until [ -s /tmp/.oadx-bus ]; do
  n=$((n + 1))
  [ $n -gt 50 ] && break
  kill -0 $DBUS 2>/dev/null || break
  sleep 0.1
done
# Both config edits on disk before xfce4-panel and xfconfd first read them.
[ -n "$DOCK" ] && wait $DOCK
[ -n "$THEME" ] && wait $THEME
# Sound: the volume plugin and every app that starts with the session pick
# their audio output once, at start, so they get the server or nothing -- the
# reason linux-win.sh waits for its own. A few seconds at most.
if [ -n "$PULSE" ] && [ -x /usr/bin/pactl ]; then
  n=0
  until PULSE_SERVER=tcp:127.0.0.1:4713 pactl info >/dev/null 2>&1; do
    n=$((n + 1))
    [ $n -gt 25 ] && break
    kill -0 $PULSE 2>/dev/null || break
    sleep 0.2
  done
fi
env DISPLAY=:1 DBUS_SESSION_BUS_ADDRESS=unix:abstract=dex-session-bus \
  XDG_RUNTIME_DIR=/tmp/runtime-root XDG_SESSION_TYPE=x11 \
  PULSE_SERVER=tcp:127.0.0.1:4713 \
  MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_DISABLE_GMP_SANDBOX=1 \
  MOZ_DISABLE_RDD_SANDBOX=1 MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1 \
  MOZ_DISABLE_UTILITY_SANDBOX=1 MOZ_DISABLE_GPU_SANDBOX=1 \
  MOZ_CRASHREPORTER_DISABLE=1 MOZ_ENABLE_WAYLAND=0 \
  startxfce4 >/root/.vnc/session.log 2>&1 &
XFCE=$!
/usr/bin/websockify --web=/usr/share/novnc 127.0.0.1:6080 127.0.0.1:5901 &
WS=$!
# The dock follows the screen: see dockfit.py, written above this string. It
# waits on X for a resize and costs nothing until one comes; niced, so that its
# start never competes with the desktop starting beside it. Not on a guest
# without python3, which has no dock.py to keep up with either. The geometry
# tells it the height dock.py was handed, by the same sed as the Xvnc line.
FIT=
if [ -x /usr/bin/python3 ] && [ -f /usr/local/share/openandroiddex/dockfit.py ]; then
  env DISPLAY=:1 DBUS_SESSION_BUS_ADDRESS=unix:abstract=dex-session-bus \
    nice -n 10 python3 /usr/local/share/openandroiddex/dockfit.py GEOMETRY >/dev/null 2>&1 &
  FIT=$!
fi
# THE session lifetime. Everything else is torn down behind it, so a logout
# ends the container instead of leaving an empty X server behind. Its exit
# status is carried all the way out: xfce4-session exits 0 when the user logged
# out and non-zero when it fell over, and that is the only honest way to tell
# those apart. Duration is NOT — someone can log out five seconds after logging
# in, and reading that as a crash is exactly what this used to do.
wait $XFCE
RC=$?
kill -9 $WS $DBUS $XVNC $PULSE $FIT 2>/dev/null
exit $RC'

# $GEO is host-side; substitute it rather than letting the guest shell see it.
SESSION=$(echo "$SESSION" | sed "s/GEOMETRY/$GEO/")

# NOT exec'd: proot runs as a child so this script outlives it by exactly long
# enough to clear rt.pid, which is how the app learns the session is over.
#
# XDG_CONFIG_DIRS is what puts our theme defaults in front of the distro's.
# linux-setup.sh writes them to /usr/local/etc/xdg rather than /etc/xdg,
# because three of those channel files are dpkg conffiles owned by
# xfce4-settings, xfce4-power-manager and xfce4-session, and overwriting the
# last of those deletes the failsafe session list and leaves xfce4-session with
# nothing to start. Two directories, merged per property, collide with nothing.
#
# It goes HERE, on the proot env, and NOT on the env that runs startxfce4 --
# which looks like the obvious place and is the wrong one. xfconfd is D-Bus
# ACTIVATED, so it is spawned by dbus-daemon and inherits dbus-daemon's
# environment, not the session command's. dbus-daemon is started from inside
# $SESSION, so the only environment both of them share is this one.
"$PROOT" -0 --link2symlink --kill-on-exit -r "$ROOT/rootfs" \
  -b /dev -b /proc -b /sys -b "$ROOT/tmp:/tmp" -b "$ROOT/shm:/dev/shm" \
  $STORAGE_BIND $SHARED_BIND \
  -w /root /usr/bin/env -i HOME=/root TERM=xterm-256color DEBIAN_FRONTEND=noninteractive \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  XDG_CONFIG_DIRS=/usr/local/etc/xdg:/etc/xdg \
  OADX_GPU="${LINUX_GPU:-}" \
  /bin/sh -c "$SESSION" &
PROOT_PID=$!
wait "$PROOT_PID"
RC=$?

# rt.pid is the app's "runtime is up" signal — clearing it here is what tells
# the viewer a guest-side logout actually ended the session.
rm -f "$ROOT/rt.pid"

# The session's verdict; proot passes the guest's status straight through.
#
# Its EXISTENCE is the load-bearing part. The viewer starts a runtime whenever
# none is running, so with no marker a logout silently booted a fresh desktop
# the instant the old one went away — which is not what "log out" means. The
# number in it only chooses the wording: 0 = the user logged out, anything else
# = the session fell over. A kill from the app side never reaches this line
# (the whole process group dies at once), and that is right: no marker, so the
# next open starts clean.
echo "$RC" > "$ROOT/rt.exit"
