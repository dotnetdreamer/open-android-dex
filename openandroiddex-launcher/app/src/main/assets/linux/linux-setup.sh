#!/system/bin/sh
# linux-setup.sh — provision Ubuntu 24.04 under proot at /data/local/tmp/linux.
# The PC pushes the tarball pair matching this device's ABI (arm64 phones,
# x86_64 emulators) under the arch-neutral names extracted below.
#
# Runs ON THE PHONE as the shell user (uid 2000, same privilege as the wmd
# daemon) under toybox /system/bin/sh — POSIX sh only: no bashisms, no arrays,
# no [[ ]]. The PC pushes the two tarballs plus this script and linux-rt.sh,
# then spawns this detached (setsid, output to setup.log), so it keeps
# installing even after the desktop session that kicked it ends.
#
#   sh linux-setup.sh <payload-version> <feature-level>
#
# Everything is phased: each completed phase drops a .stamp-* file, so a re-run
# (after an error, a reboot mid-install, or a payload version bump) redoes only
# what is missing. Progress is published through state.env — KEY=VALUE lines,
# written ATOMICALLY (state.env.tmp + mv) so the PC poller and the wmd
# LINUXSTATUS verb never read a torn file:
#
#   VERSION=<int>  FEATURES=<int>  PHASE=<phase>  PCT=<0-100>  MSG=<no-whitespace>
#
# MSG may also be <verb>:<name> — see apt_msg.
#   phases: pushing (written by the caller) extracting configuring apt-update
#           installing-desktop ready error
#
# Nothing here needs root: proot is a ptrace chroot, and apt runs as guest
# "root" purely by emulation (APT::Sandbox::User keeps it from trying to drop
# to a real _apt uid it could never become).

# ROOT and the proot binary come from the environment so the same script serves
# both callers: the app runs it under its own uid with ROOT in the app's
# filesDir and proot from the APK's native-lib dir; a bare shell falls back to
# the legacy /data/local/tmp layout with proot extracted from a tarball.
ROOT=${LINUX_ROOT:-/data/local/tmp/linux}
VER=$1
[ -n "$VER" ] || VER=1
# Feature level: what this payload installs INSIDE an already-provisioned
# guest. Deliberately separate from VER — a VER bump wipes the stamps and
# reprovisions from a clean rootfs, which is a container reset that throws away
# everything the user installed, while a FEAT bump only re-runs this script and
# lets the stamps skip straight to the phases that are new. Adding the browsers
# to a working install is exactly that case.
FEAT=$2
[ -n "$FEAT" ] || FEAT=1

# Which optional apps go in, from the app's chooser (Linux.setApps): ids in
# canonical order, space-separated, or "none" for an empty tick list. Unset
# means a bare shell run, which gets what every guest was built with before
# the chooser existed. The value is echoed back into apps.done VERBATIM at the
# end and compared byte-for-byte by Linux.needsProvision — the app's default
# selection is this same string, so the two must never drift — which is why it
# is never normalised, reordered or rewritten here.
APPS_SEL=${LINUX_APPS:-"firefox chromium code git"}
wants() { case " $APPS_SEL " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

# ── state.env / failure plumbing ──────────────────────────────────────────

state() { # phase pct msg
  printf 'VERSION=%s\nFEATURES=%s\nPHASE=%s\nPCT=%s\nMSG=%s\n' \
    "$VER" "$FEAT" "$1" "$2" "$3" > "$ROOT/state.env.tmp"
  mv "$ROOT/state.env.tmp" "$ROOT/state.env"
}

# Milestones go to logcat as well as to setup.log. The PC folds
# `logcat -s OpenDeX` into its session trace, while setup.log lives in private
# storage that a non-debuggable build cannot read — which is why "VS Code did
# not install" was, from the outside, simply unanswerable.
note() {
  echo "$1"
  [ -x /system/bin/log ] && /system/bin/log -p i -t OpenDeX "[linux-setup] $1"
}

# Copy a log somewhere a human can reach it. $ROOT is the app's private storage,
# which nothing can read without a debuggable build; LINUX_STORAGE is the app's
# EXTERNAL files dir, which plain `adb shell` and `adb pull` can read — but only
# once the copy is world-readable. setup.log is created by the app's own
# ProcessBuilder redirect under an app umask of 077, so it lands 0600, and `cp`
# carries that mode straight over: `adb pull` answered "Permission denied" and
# the escape hatch this function exists to provide did not actually work.
# Failure diagnosis should not require rebuilding the app.
share_log() { # file
  [ -n "$LINUX_STORAGE" ] || return 0
  [ -f "$1" ] || return 0
  mkdir -p "$LINUX_STORAGE" 2>/dev/null
  _dst=$LINUX_STORAGE/$(basename "$1")
  cp -f "$1" "$_dst" 2>/dev/null
  chmod 0644 "$_dst" 2>/dev/null
}

# Run something in the guest and, if it fails, put the tail of its output where
# someone can actually read it.
guest_or_note() { # label command
  _out=$(run_guest "$2" 2>&1) && return 0
  note "$1 FAILED: $(echo "$_out" | tail -3 | tr '\n' ' ' | cut -c1-400)"
  return 1
}

# The desktop install is the one phase guest_or_note cannot wrap: capturing it
# would put a 300-package apt transcript in a shell variable and, worse, take
# the live stream out of setup.log, which is the only thing a human tailing the
# install has to watch. So it keeps streaming — and when it fails, this lifts
# apt's own error lines back OUT of the log into logcat. Without it the biggest,
# most interruption-prone phase was also the only one that explained nothing:
# "FAILED: installing-desktop" was the entire record, and the transcript behind
# it sat in private storage that a non-debuggable build cannot read.
apt_note() { # label
  [ -f "$ROOT/setup.log" ] || return 0
  _e=$(grep -E '^(E: |dpkg: error)' "$ROOT/setup.log" 2>/dev/null \
       | tail -3 | tr '\n' ' ' | cut -c1-400)
  [ -n "$_e" ] || _e=$(tail -3 "$ROOT/setup.log" 2>/dev/null | tr '\n' ' ' | cut -c1-400)
  [ -n "$_e" ] && note "$1: $_e"
  return 0
}

# Undo whatever an interrupted install left behind. A run killed mid-unpack —
# and the app's :linux process being replaced is enough to do it, since the
# setup script is a plain child of it — leaves dpkg mid-transaction, which every
# later apt-get then refuses to work around. The repair is a LADDER, and the
# rungs are NOT interchangeable:
#
#   dpkg --configure -a     finishes packages left merely unconfigured
#   apt-get --fix-broken    resolves dependencies left unmet by a partial unpack
#   apt-get install <pkg>   re-UNPACKS a package dpkg will not touch at all
#
# That last rung is the one this went without for too long. "package is in a
# very bad inconsistent state; you should reinstall it before attempting
# configuration" is dpkg saying the UNPACK never finished — and neither rung
# above it ever repeats an unpack, so both fail on it identically, on every
# retry, forever. Measured on an S25 (2026-08-19): perl-base half-installed,
# four consecutive Retries each dying 3.5 s in, while the same package set
# installed all 311 packages cleanly into a scratch guest on the same phone.
#
# Plain install BEFORE --reinstall, which is the non-obvious half: --reinstall
# can only fetch the exact installed version, and the base image ships versions
# the archive has already superseded. perl-base 5.38.2-3.2ubuntu0.2 is in no
# pocket, so --reinstall dies with "Can't find a source to download version"
# while a plain install unpacks 0.3 over it and clears the state — verified both
# ways on device. --reinstall stays as the fallback for when the installed
# version IS the candidate, where a plain install is a no-op.
#
# All of it is best-effort and none of it is fatal: the phase that follows is
# the real verdict, and a guest that needed no repair passes straight through.
repair_dpkg() {
  run_guest "dpkg --configure -a; apt-get -y --fix-broken install; b=\$(dpkg -l | awk '\$1 ~ /^i[FHU]/ { print \$2 }'); [ -n \"\$b\" ] || exit 0; apt-get install -y \$b || apt-get install -y --reinstall \$b; true" || true
}

# What the desktop install is doing RIGHT NOW, as one state.env-safe token.
# apt is a firehose and MSG holds a single whitespace-free word, so this reduces
# the log tail to the last thing worth naming: which package is downloading,
# unpacking or being configured. Before it, the ~1.5 GB phase published one
# unchanging word ("apt-install") for its entire duration — a progress line that
# never moves reads as a hang, which is exactly what it is not.
#
# The package name is handed over after a COLON. LinuxActivity de-dashes only
# the verb and leaves the name alone, because a package's own dashes are part of
# its name: blanket dash→space rendered "setting-up-libgtk-3-0t64" as "setting
# up libgtk 3 0t64", four things instead of one.
#
# $1 is the line count setup.log had when the phase began, so the tail can never
# surface a leftover from `apt-get update` and announce "downloading Packages"
# over an install. No awk: toybox only grew one in 0.8.10, and this has to run
# on the phones that shipped before it. Parameter expansion instead of `set --`
# keeps apt's "[1779 kB]" from being read as a glob.
apt_msg() { # log-offset
  [ -f "$ROOT/setup.log" ] || { echo apt-install; return 0; }
  _l=$(tail -n "+$(($1 + 1))" "$ROOT/setup.log" 2>/dev/null \
       | grep -E '^(Get:[0-9]|Unpacking |Setting up |Processing triggers for |Reading package lists|Building dependency)' \
       | tail -1)
  case "$_l" in
    "Reading package lists"*) echo reading-package-lists; return 0 ;;
    "Building dependency"*)   echo resolving-dependencies; return 0 ;;
    "Unpacking "*)            _v=unpacking;   _p=${_l#Unpacking } ;;
    "Setting up "*)           _v=setting-up;  _p=${_l#Setting up } ;;
    "Processing triggers for "*) _v=finishing; _p=${_l#Processing triggers for } ;;
    Get:*)  # Get:<n> <uri> <suite>/<component> <arch> <package> …
      _v=downloading
      _p=${_l#* }; _p=${_p#* }; _p=${_p#* }; _p=${_p#* } ;;
    *) echo apt-install; return 0 ;;
  esac
  _p=${_p%% *}    # first word only
  _p=${_p%%:*}    # drop dpkg's :arch qualifier
  [ -n "$_p" ] || { echo apt-install; return 0; }
  echo "$_v:$_p"
}

TICKER=
fail() { # phase-name — kill the ticker FIRST or it overwrites the error state
  [ -n "$TICKER" ] && kill "$TICKER" 2>/dev/null
  state error 0 "$1"
  note "FAILED: $1"
  share_log "$ROOT/setup.log"
  exit 1
}

# setup.pid comes off on every exit path — fail(), plain exit, or success.
cleanup() {
  [ -n "$TICKER" ] && kill "$TICKER" 2>/dev/null
  rm -f "$ROOT/setup.pid"
}
trap cleanup EXIT
# A trappable signal (someone aborting the install) must surface as an error
# state, not vanish — and must take the ticker down with it. SIGKILL is
# untrappable; the ticker's parent-liveness check covers that hole.
trap 'fail interrupted' TERM INT HUP

# ── double-run guard ──────────────────────────────────────────────────────
# The PC kicks this on every desktop session start; only one instance may
# touch the rootfs. A stale pid (phone rebooted mid-install) is not a lock —
# and pid numbers get recycled, so liveness alone is not identity: the pid
# only counts as ours if its cmdline is actually this script.
if [ -f "$ROOT/setup.pid" ]; then
  oldpid=$(cat "$ROOT/setup.pid" 2>/dev/null)
  if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null \
     && grep -q linux-setup "/proc/$oldpid/cmdline" 2>/dev/null; then
    echo "setup already running (pid $oldpid) — exiting"
    trap - EXIT # never delete the live instance's pid file
    exit 0
  fi
fi
echo $$ > "$ROOT/setup.pid"

cd "$ROOT" || exit 1
# shm/ is bound over the guest's /dev/shm — Android has none of its own
# and we bind Android's /dev in. See linux-rt.sh.
mkdir -p tmp shm

# A version bump means a payload changed — every phase must run again.
if [ ! -f .stamp-version ] || [ "$(cat .stamp-version)" != "$VER" ]; then
  rm -f .stamp-*
  echo "$VER" > .stamp-version
fi

# ── guest exec helper ─────────────────────────────────────────────────────
# --kill-on-exit is load-bearing: killing proot reaps every guest process,
# which is what lets `pkill -9 -s <sid>` tear a session down cleanly.
# proot binary + loaders from env when the app supplies them (native-lib dir),
# else the in-ROOT tarball layout.
PROOT=${LINUX_PROOT:-$ROOT/proot/root/bin/proot}
export PROOT_LOADER=${PROOT_LOADER:-$ROOT/proot/root/libexec/proot/loader}
export PROOT_LOADER_32=${PROOT_LOADER_32:-$ROOT/proot/root/libexec/proot/loader32}
export PROOT_TMP_DIR=$ROOT/tmp
run_guest() { "$PROOT" -0 --link2symlink --kill-on-exit -r "$ROOT/rootfs" -b /dev -b /proc -b /sys -b "$ROOT/tmp:/tmp" -b "$ROOT/shm:/dev/shm" -w /root /usr/bin/env -i HOME=/root TERM=xterm-256color DEBIAN_FRONTEND=noninteractive PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/sh -c "$1"; }

# proot's syscall-interception mode is NOT one-size-fits-all, and picking wrong
# is silent-then-fatal:
#   * Samsung/OEM kernels break hardware seccomp — proot can't even execve a
#     guest binary (EPERM, kernel bug launchpad #1202161) — so they NEED
#     PROOT_NO_SECCOMP=1 (pure ptrace).
#   * The emulator (and mainline kernels) are the opposite: pure ptrace on this
#     old proot mis-emulates newer syscalls and apt dies with ENOSYS
#     ("Function not implemented"), while seccomp works perfectly.
# So we PROBE once the rootfs exists: try a trivial guest exec with seccomp on;
# if it fails, fall back to pure ptrace. The verdict is cached in $ROOT/noseccomp
# so linux-rt.sh runs the guest exactly the same way.
pick_seccomp() {
  [ -x "$ROOT/rootfs/usr/bin/true" ] || return 0   # nothing to probe yet
  unset PROOT_NO_SECCOMP
  if "$PROOT" -0 -r "$ROOT/rootfs" -b /dev -b /proc -b /sys /usr/bin/true 2>/dev/null; then
    : > "$ROOT/noseccomp"           # empty file = seccomp is fine
  else
    export PROOT_NO_SECCOMP=1
    echo 1 > "$ROOT/noseccomp"
  fi
}

# ── phase: proot ──────────────────────────────────────────────────────────
# Skip entirely when the app already handed us a proot binary (native-lib dir);
# only the legacy tarball layout needs extraction.
if [ ! -x "$PROOT" ]; then
  if [ ! -f .stamp-proot ]; then
    state extracting 5 extract-proot
    rm -rf proot
    mkdir -p proot
    tar -xzf linux-proot.tar.gz -C proot || fail extract-proot
    [ -x "$PROOT" ] || fail extract-proot
    touch .stamp-proot
  fi
fi

# ── phase: extract the Ubuntu base rootfs ─────────────────────────────────
# The tarball has hardlinks (perl aliases, doc files…) and our uid may not
# link() on /data (SELinux denies it). `proot --link2symlink` would rewrite
# them, but this kernel's SELinux ALSO blocks proot from exec'ing the system
# tar binary (only guest binaries load) — so we extract with system tar
# directly and repair the handful of failed hardlinks ourselves, turning each
# into an absolute-target symlink that proot resolves inside the rootfs. Uid-
# and location-independent, which keeps it valid if the guest ever moves into
# an app's own storage. Always from a clean slate: a partial extract (or a
# payload version bump, which wipes the stamps) must never leave half-written
# files under a fresh base — a version bump IS a container reset.
extract_rootfs() {
  rm -rf rootfs
  mkdir -p rootfs || return 1
  # rc is unreliable (toybox tar exits 0 despite link errors), so we judge
  # success by a sentinel file existing afterwards, not by $?.
  /system/bin/tar -xzf linux-rootfs.tar.gz -C rootfs 2>"$ROOT/tar.err"
  [ -x rootfs/bin/bash ] || [ -e rootfs/bin/sh ] || return 1
  # tar: can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl': Permission denied
  q=\'
  while IFS= read -r line; do
    case "$line" in
      *"link ${q}"*"-> ${q}"*)
        rest=${line#*link ${q}}
        new=${rest%%${q}*}
        rest=${rest#*-> ${q}}
        tgt=${rest%%${q}*}
        [ -n "$new" ] && [ -n "$tgt" ] || continue
        rm -f "rootfs/$new"
        ln -s "/$tgt" "rootfs/$new" 2>/dev/null
        ;;
    esac
  done < "$ROOT/tar.err"
  rm -f "$ROOT/tar.err"
}

if [ ! -f .stamp-rootfs ]; then
  state extracting 15 extract-rootfs
  extract_rootfs || fail extract-rootfs
  touch .stamp-rootfs
fi

# ── phase: configure the guest ────────────────────────────────────────────
# Plain file writes into the extracted tree — no proot needed yet.
configure_guest() {
  mkdir -p rootfs/etc/apt/sources.list.d rootfs/etc/apt/apt.conf.d || return 1
  # arm64 lives on ports.ubuntu.com, x86_64 (the emulator) on
  # archive.ubuntu.com; the base image ships without the updates/security
  # pockets, so the file is overwritten, not appended.
  case "$(uname -m)" in
    x86_64) MIRROR=http://archive.ubuntu.com/ubuntu ;;
    *)      MIRROR=http://ports.ubuntu.com/ubuntu-ports ;;
  esac
  cat > rootfs/etc/apt/sources.list.d/ubuntu.sources <<EOF || return 1
Types: deb
URIs: $MIRROR
Suites: noble noble-updates noble-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
  # resolv.conf may be a dangling systemd-resolved symlink — rm first.
  rm -f rootfs/etc/resolv.conf || return 1
  printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > rootfs/etc/resolv.conf || return 1
  printf '127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n' > rootfs/etc/hosts || return 1
  cat > rootfs/etc/apt/apt.conf.d/99dex <<'EOF' || return 1
APT::Sandbox::User "root";
Acquire::Retries "3";
EOF
  # Android's gids ride into the guest (proot does not map supplementary
  # groups); naming them silences a wall of "cannot find name for group ID"
  # from every shell.
  for g in 1004:aid_input 1007:aid_log 1011:aid_adb 1015:aid_sdcard_rw \
           1028:aid_sdcard_r 1078:aid_ext_data_rw 1079:aid_ext_obb_rw \
           3001:aid_bt_admin 3002:aid_bt 3003:aid_inet 3006:aid_net_bw_stats \
           3009:aid_readproc 3011:aid_uhid 3012:aid_readtracefs; do
    grep -q ":${g%%:*}:" rootfs/etc/group 2>/dev/null \
      || echo "${g#*:}:x:${g%%:*}:" >> rootfs/etc/group
  done
}

if [ ! -f .stamp-configure ]; then
  state configuring 30 configure
  configure_guest || fail configure
  touch .stamp-configure
fi

# Decide proot's seccomp mode now that a guest binary exists — every run_guest
# below (and linux-rt.sh) depends on it.
pick_seccomp

# ── phase: apt-get update ─────────────────────────────────────────────────
if [ ! -f .stamp-apt-update ]; then
  state apt-update 32 apt-update
  run_guest "apt-get update" || fail apt-update
  touch .stamp-apt-update
fi

# ── phase: install the desktop ────────────────────────────────────────────
# The long pole (~1.5GB unpacked over the network). The BAR is the rootfs
# growing: a background ticker maps `du -sm rootfs` growth of 0→1500MB onto PCT
# 35→95. The TEXT is apt's own transcript, read back out of setup.log by
# apt_msg — this phase runs for many minutes and naming the package it is on is
# the difference between "installing desktop" for ten minutes and something a
# person can watch.
if [ ! -f .stamp-desktop ]; then
  state installing-desktop 35 apt-install
  base=$(du -sm rootfs 2>/dev/null | cut -f1)
  [ -n "$base" ] || base=0
  # Where the phase's own output starts, so apt_msg never quotes an older one.
  logoff=$(wc -l < "$ROOT/setup.log" 2>/dev/null)
  [ -n "$logoff" ] || logoff=0
  MAIN=$$
  (
    while :; do
      # If the main script died to SIGKILL (untrappable — nothing ran the
      # traps), an orphaned ticker would pin state.env at installing-desktop
      # forever. Follow the parent out instead.
      kill -0 "$MAIN" 2>/dev/null || exit 0
      now=$(du -sm rootfs 2>/dev/null | cut -f1)
      [ -n "$now" ] || now=$base
      pct=$((35 + (now - base) * 60 / 1500))
      [ "$pct" -lt 35 ] && pct=35
      [ "$pct" -gt 95 ] && pct=95
      state installing-desktop "$pct" "$(apt_msg "$logoff")"
      sleep 5
    done
  ) &
  TICKER=$!
  # Repair before installing: this phase is the long pole, so it is the one an
  # interruption lands in, and it must be able to pick itself up. See
  # repair_dpkg — `dpkg --configure -a` alone, which is all this used to do,
  # cannot.
  repair_dpkg
  run_guest "apt-get install -y --no-install-recommends xfce4 xfce4-terminal dbus-x11 x11-xserver-utils xfonts-base tigervnc-standalone-server tigervnc-tools novnc websockify sudo ca-certificates librsvg2-common" \
    || { apt_note installing-desktop; fail installing-desktop; }
  kill "$TICKER" 2>/dev/null
  TICKER=
  touch .stamp-desktop
fi

# ── phase: VNC password + xstartup ────────────────────────────────────────
setup_vnc() {
  # 12 alnum chars, generated host-side so the wmd LINUXSTATUS verb can hand
  # the password to the PC without ever entering the guest.
  PASS=$(tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 12)
  [ -n "$PASS" ] || return 1
  printf '%s\n' "$PASS" > vncpass || return 1
  # $PASS is alnum only, so inlining it inside the guest command is safe.
  run_guest "mkdir -p /root/.vnc && printf '%s' '$PASS' | vncpasswd -f > /root/.vnc/passwd && chmod 600 /root/.vnc/passwd" || return 1
  run_guest "printf '#!/bin/sh\nexec dbus-launch --exit-with-session startxfce4\n' > /root/.vnc/xstartup && chmod 755 /root/.vnc/xstartup" || return 1
}

if [ ! -f .stamp-vnc ]; then
  state installing-desktop 90 vnc-setup
  setup_vnc || fail vnc-setup
  touch .stamp-vnc
fi

# -- phase: browsers -------------------------------------------------------
# Ubuntu 24.04 ships `firefox` and `chromium` as SNAP transitional stubs: they
# install a shim that shells out to snapd, and snapd cannot exist in a proot
# container. That stub is the whole reason XFCE answered "Failed to execute
# default web browser" -- there was no browser behind the name.
#
# So both come from real .deb sources: Mozilla's own APT repo for Firefox
# (built for amd64 AND arm64) and the xtradeb PPA for Chromium (the maintained
# noble chromium .deb, also both arches). Mozilla's repo is pinned above the
# Ubuntu archive so the snap stub can never win the name back.
#
# Chromium is deliberately NON-FATAL: it comes from a third-party PPA, and a
# guest with Firefox in it is a working guest. Firefox failing is a real
# failure.
install_browsers() {
  # The base image has no downloader at all, and apt needs two signing keys.
  run_guest "apt-get install -y --no-install-recommends curl ca-certificates" || return 1
  run_guest "install -d -m 0755 /etc/apt/keyrings" || return 1

  # Each browser's repo goes in only when that browser was asked for: an
  # unticked browser should cost neither its download nor a repo apt polls on
  # every update from then on.
  if wants firefox; then
    # apt 2.4+ (noble has 2.7) reads ASCII-armoured keys straight from
    # Signed-By, so nothing has to be dearmoured and gpg is never needed.
    run_guest "curl -fsSL https://packages.mozilla.org/apt/repo-signing-key.gpg -o /etc/apt/keyrings/packages.mozilla.org.asc" || return 1
    mkdir -p rootfs/etc/apt/preferences.d || return 1
    cat > rootfs/etc/apt/sources.list.d/mozilla.sources <<'EOF' || return 1
Types: deb
URIs: https://packages.mozilla.org/apt
Suites: mozilla
Components: main
Signed-By: /etc/apt/keyrings/packages.mozilla.org.asc
EOF
    cat > rootfs/etc/apt/preferences.d/mozilla <<'EOF' || return 1
Package: *
Pin: origin packages.mozilla.org
Pin-Priority: 1000
EOF
  fi

  if wants chromium; then
    # Launchpad serves PPA keys by fingerprint from the Ubuntu keyserver.
    run_guest "curl -fsSL 'https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x5301FA4FD93244FBC6F6149982BB6851C64F6880' -o /etc/apt/keyrings/xtradeb.asc" || true
    cat > rootfs/etc/apt/sources.list.d/xtradeb.sources <<'EOF' || return 1
Types: deb
URIs: https://ppa.launchpadcontent.net/xtradeb/apps/ubuntu
Suites: noble
Components: main
Signed-By: /etc/apt/keyrings/xtradeb.asc
EOF
  fi

  # NOT fatal: apt-get update returns non-zero if ANY of the repos hiccups,
  # which says nothing about whether the package we want is installable. Let
  # the install be the judge.
  run_guest "apt-get update" || true
  if wants firefox; then
    run_guest "apt-get install -y --no-install-recommends firefox" || return 1
  fi
  if wants chromium && run_guest "apt-get install -y --no-install-recommends chromium"; then
    CHROMIUM_OK=1
  else
    CHROMIUM_OK=
    ! wants chromium || echo "WARNING: chromium install failed" >&2
  fi

  # Point the installed menu entries at the wrappers. /usr/local/bin comes
  # first on PATH, but a .desktop file names its binary by ABSOLUTE path, so
  # PATH order alone would never reach them.
  run_guest 'for d in /usr/share/applications/firefox*.desktop; do [ -f "$d" ] && sed -i -E "s#^(Exec=)[^ ]*firefox#\1/usr/local/bin/dex-firefox#" "$d"; done; for d in /usr/share/applications/chromium*.desktop; do [ -f "$d" ] && sed -i -E "s#^(Exec=)[^ ]*chromium#\1/usr/local/bin/dex-chromium#" "$d"; done; true' || true

  return 0
}

# -- phase: browser tuning -------------------------------------------------
# Separate from the install phase because it has to reach guests that already
# have the browsers: install_browsers only runs while a wanted browser is
# missing, so anything added here instead of there still lands on guests whose
# browsers settled long ago. This phase carries its own stamp and a FEATURE
# bump brings it to everyone.
tune_browsers() {
  # Launch wrappers. BOTH browsers' sandboxes want user namespaces, which a
  # ptrace chroot cannot provide. They live here rather than in the install
  # phase so that changing them reaches a guest that already has the browsers,
  # and so a launch from a terminal -- which inherits none of the session
  # environment linux-rt.sh sets -- still gets the same treatment. Wrapping
  # beats patching the packages: an apt upgrade cannot undo it.
  mkdir -p rootfs/usr/local/bin || return 1
  cat > rootfs/usr/local/bin/dex-firefox <<'EOF' || return 1
#!/bin/sh
# Every Firefox child process sandboxes itself with user namespaces and
# seccomp; a ptrace chroot has neither, and disabling only the content one
# leaves the rest to crash. The crash reporter is off because a crash would
# otherwise stack a modal window over the desktop.
export MOZ_DISABLE_CONTENT_SANDBOX=1
export MOZ_DISABLE_GMP_SANDBOX=1
export MOZ_DISABLE_RDD_SANDBOX=1
export MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1
export MOZ_DISABLE_UTILITY_SANDBOX=1
export MOZ_DISABLE_GPU_SANDBOX=1
export MOZ_CRASHREPORTER_DISABLE=1
export MOZ_ENABLE_WAYLAND=0
exec /usr/bin/firefox "$@"
EOF
  cat > rootfs/usr/local/bin/dex-chromium <<'EOF' || return 1
#!/bin/sh
# --no-sandbox: no user namespaces under proot.
# --disable-dev-shm-usage: /dev/shm exists here but is disk-backed, not the
#   tmpfs Chromium sizes its shared memory against.
# --disable-gpu: Xvnc has no GL.
# --password-store=basic: no gnome-keyring or kwallet in this session.
exec /usr/bin/chromium --no-sandbox --disable-dev-shm-usage --disable-gpu --password-store=basic "$@"
EOF
  chmod 755 rootfs/usr/local/bin/dex-firefox rootfs/usr/local/bin/dex-chromium || return 1

  # Chromium's --no-sandbox is not optional here (no user namespaces under
  # proot), so it permanently earns the "You are using an unsupported
  # command-line flag" banner. Silence it the SUPPORTED way -- the enterprise
  # policy -- rather than with --test-type, an undocumented test switch that
  # quietly changes other behaviour too.
  mkdir -p rootfs/etc/chromium/policies/managed || return 1
  cat > rootfs/etc/chromium/policies/managed/openandroiddex.json <<'EOF' || return 1
{
  "CommandLineFlagSecurityWarningsEnabled": false,
  "DefaultBrowserSettingEnabled": false,
  "MetricsReportingEnabled": false
}
EOF

  # Firefox: the env in linux-rt.sh disables the sandboxes for processes it
  # launches, but a pref cannot come from the environment, and the content
  # sandbox level has to be 0 or the tab dies whatever the env says. AutoConfig
  # is the supported way to set prefs for every profile, including ones that do
  # not exist yet -- which matters, because the profile is created on first run.
  if [ -d rootfs/usr/lib/firefox ]; then
    mkdir -p rootfs/usr/lib/firefox/defaults/pref || return 1
    cat > rootfs/usr/lib/firefox/defaults/pref/autoconfig.js <<'EOF' || return 1
pref("general.config.filename", "openandroiddex.cfg");
pref("general.config.obscure_value", 0);
pref("general.config.sandbox_enabled", false);
EOF
    cat > rootfs/usr/lib/firefox/openandroiddex.cfg <<'EOF' || return 1
// AutoConfig ignores the first line of this file. Do not remove it.
defaultPref("security.sandbox.content.level", 0);
defaultPref("gfx.webrender.software", true);
defaultPref("media.hardware-video-decoding.enabled", false);
defaultPref("dom.ipc.processCount", 1);
defaultPref("browser.shell.checkDefaultBrowser", false);
defaultPref("browser.crashReports.unsubmittedCheck.enabled", false);
EOF
  fi

  # Which browser "the web browser" means. Chromium is preferred when it is
  # actually installed: it is the one verified working on a real phone under
  # this container, and Firefox asks far more of the kernel than proot has to
  # give. One word here is the whole switch.
  if [ -x rootfs/usr/bin/chromium ]; then
    DEFAULT_BROWSER=dex-chromium
  else
    DEFAULT_BROWSER=dex-firefox
  fi

  # XFCE resolves "the web browser" through exo's helper table, NOT through
  # $BROWSER or update-alternatives -- that lookup is the one that printed
  # "Failed to execute default web browser". Give it helpers of our own so the
  # answer cannot depend on how either browser happens to be packaged.
  mkdir -p rootfs/usr/share/xfce4/helpers rootfs/root/.config/xfce4 || return 1
  cat > rootfs/usr/share/xfce4/helpers/dex-firefox.desktop <<'EOF' || return 1
[Desktop Entry]
Version=1.0
Encoding=UTF-8
Type=X-XFCE-Helper
X-XFCE-Category=WebBrowser
X-XFCE-Binaries=dex-firefox;
X-XFCE-Commands=/usr/local/bin/dex-firefox;
X-XFCE-CommandsWithParameter=/usr/local/bin/dex-firefox "%s";
Icon=firefox
Name=Firefox
EOF
  cat > rootfs/usr/share/xfce4/helpers/dex-chromium.desktop <<'EOF' || return 1
[Desktop Entry]
Version=1.0
Encoding=UTF-8
Type=X-XFCE-Helper
X-XFCE-Category=WebBrowser
X-XFCE-Binaries=dex-chromium;
X-XFCE-Commands=/usr/local/bin/dex-chromium;
X-XFCE-CommandsWithParameter=/usr/local/bin/dex-chromium "%s";
Icon=chromium
Name=Chromium
EOF
  printf '[Configuration]\nWebBrowser=%s\n' "$DEFAULT_BROWSER" \
    > rootfs/root/.config/xfce4/helpers.rc || return 1

  # Belt for everything that does not ask exo: x-www-browser and $BROWSER.
  run_guest "update-alternatives --install /usr/bin/x-www-browser x-www-browser /usr/local/bin/$DEFAULT_BROWSER 200; update-alternatives --install /usr/bin/gnome-www-browser gnome-www-browser /usr/local/bin/$DEFAULT_BROWSER 200; true" || true
  return 0
}

# -- phase: VS Code --------------------------------------------------------
# From Microsoft's official TARBALL, not their .deb, and that is not a
# preference -- the .deb cannot be installed in here at all. Measured on the
# phone: 108 dependency packages unpack fine, then dpkg dies on code's own
# 105 MB archive with
#
#   malloc(): corrupted top size
#   dpkg-deb: error: <decompress> subprocess was killed by signal (Aborted)
#   cannot copy extracted data for './usr/share/code/locales/mr.pak' ...
#
# i.e. heap corruption inside the decompress subprocess dpkg forks during
# --unpack. Plain `dpkg-deb -x` on the same archive decompresses it fine, so it
# is that fork-and-pipe path under proot, not the package and not the size --
# Chromium's deb is comparable and installs without complaint. The tarball is
# the same build from the same vendor and touches none of it: curl, tar, done.
#
# Non-fatal, like Chromium: a guest without VS Code is still a usable guest.
install_vscode() {
  case "$(uname -m)" in
    aarch64|arm64) VS_ARCH=linux-arm64 ;;
    x86_64)        VS_ARCH=linux-x64 ;;
    *) note "vscode: no build for $(uname -m)"; return 1 ;;
  esac

  # Undo the failed .deb attempts first. A guest that tried the old path is
  # left with a half-unpacked `code` package and a dirty dpkg journal, and in
  # that state EVERY later apt-get fails -- including the dependency install
  # right below, which is how a working tarball path would still have produced
  # no VS Code. Removing the apt source too, so nothing offers the .deb again.
  run_guest "rm -f /etc/apt/sources.list.d/vscode.sources" || true
  run_guest "dpkg --configure -a" || true
  run_guest "dpkg --remove --force-all code" || true
  run_guest "apt-get -y --fix-broken install" || true

  # The runtime libraries the .deb's Depends would have pulled in, listed by
  # hand now that dpkg is no longer resolving them. These are the Electron /
  # GTK / NSS pieces, and they install without trouble -- it was only code's
  # own archive that dpkg could not unpack.
  note "vscode: installing runtime libraries"
  guest_or_note "vscode-deps" "apt-get install -y --no-install-recommends \
    ca-certificates curl xdg-utils libgtk-3-0t64 libnss3 libnspr4 libasound2t64 \
    libxkbfile1 libsecret-1-0 libgbm1 libdrm2 libxss1 libxtst6 libxcomposite1 \
    libxdamage1 libxfixes3 libxrandr2 libcups2t64 libpango-1.0-0 libcairo2 \
    libatk1.0-0t64 libatk-bridge2.0-0t64 libatspi2.0-0t64" || return 1

  note "vscode: downloading (about 330 MB)"
  guest_or_note "vscode-download" \
    "curl -fL --retry 3 -o /tmp/vscode.tar.gz https://update.code.visualstudio.com/latest/$VS_ARCH/stable" \
    || return 1

  note "vscode: extracting"
  # --strip-components=1 drops the tarball's VSCode-linux-<arch>/ wrapper dir.
  # The rm is outside the && chain so a partial extraction still frees 330 MB.
  guest_or_note "vscode-extract" \
    "rm -rf /opt/vscode && mkdir -p /opt/vscode && tar -xzf /tmp/vscode.tar.gz -C /opt/vscode --strip-components=1; rm -f /tmp/vscode.tar.gz" \
    || return 1
  if [ ! -x rootfs/opt/vscode/bin/code ]; then
    note "vscode-extract FAILED: /opt/vscode/bin/code missing after extraction"
    return 1
  fi
  # So everything that asks "is VS Code here?" -- setup_dock, the app's
  # installedApps log -- can go on asking it of /usr/bin/code.
  run_guest "ln -sf /opt/vscode/bin/code /usr/bin/code" || return 1

  # Electron is Chromium, so it wants the same things Chromium cannot have
  # here. --no-sandbox is doubly required: no user namespaces, and everything
  # in this container runs as proot's fake root, which VS Code refuses without
  # it. The wrapper lives here rather than in tune_browsers because it is the
  # only thing that knows where the tarball put the launcher.
  cat > rootfs/usr/local/bin/dex-code <<'EOF' || return 1
#!/bin/sh
# --no-sandbox AND --user-data-dir: this container is proot's fake root
# throughout, and VS Code refuses to start as root without BOTH. Measured -- it
# says so itself: "please add the argument `--no-sandbox` and specify an
# alternate user data directory using the `--user-data-dir` argument".
# --disable-gpu: Xvnc has no GL.
# --password-store=basic: no gnome-keyring or kwallet in this session.
exec /opt/vscode/bin/code --no-sandbox --user-data-dir=/root/.vscode \
  --disable-gpu --password-store=basic "$@"
EOF
  chmod 755 rootfs/usr/local/bin/dex-code || return 1

  # The tarball ships no .desktop, so the Applications menu needs one from us.
  # This one DOES belong on the XDG path: there is no packaged entry for it to
  # duplicate, which is exactly what made the browsers' copies wrong there.
  mkdir -p rootfs/usr/local/share/applications || return 1
  cat > rootfs/usr/local/share/applications/code.desktop <<'EOF' || return 1
[Desktop Entry]
Version=1.0
Type=Application
Name=Visual Studio Code
GenericName=Text Editor
Comment=Code Editing. Redefined.
Icon=/opt/vscode/resources/app/resources/linux/code.png
Exec=/usr/local/bin/dex-code %F
Terminal=false
StartupNotify=true
StartupWMClass=Code
Categories=Development;IDE;TextEditor;
MimeType=text/plain;inode/directory;
EOF
  note "vscode: installed"
  return 0
}
# -- phase: git ------------------------------------------------------------
# VS Code's Source Control panel is useless without it: with no git on PATH it
# shows "Source control depends on Git being installed" and a Download Git for
# Linux button, which in here leads nowhere a user can act on. An editor that
# cannot open a repository is half an editor, so git comes with it.
#
# openssh-client alongside git, and it is not padding: without ssh, `git clone`
# of any git@host: URL fails at the transport, which is how most people reach
# their own repositories. ca-certificates is what makes the https half work,
# and the desktop phase already installs it — asked for again here because that
# phase is stamped and a guest built before it would not have got it.
#
# Deliberately UNSTAMPED and guarded on the binary instead, for the reason
# recorded on setup_share: a touch-stamp freezes the first implementation
# forever on existing guests, since `rm -f .stamp-*` only fires on a VERSION
# change. The guard costs one stat when git is already there.
setup_git() {
  if [ -x rootfs/usr/bin/git ]; then
    note "git: already installed"
  else
    guest_or_note "git" \
      "apt-get install -y --no-install-recommends git openssh-client ca-certificates" \
      || return 1
  fi

  # Two settings that stop git refusing to work, rather than preferences.
  #
  # safe.directory: the shared folder and anything reached through it is owned
  # by a different uid than the guest's root, and modern git REFUSES to operate
  # on a repository it thinks belongs to someone else ("detected dubious
  # ownership"). Every path in this container is reachable only by this app's
  # own uid already, so the check protects nothing here and only breaks things.
  #
  # init.defaultBranch: silences the long hint git prints on every `git init`,
  # and picks the name every host now defaults to.
  guest_or_note "git-config" \
    "git config --system --replace-all safe.directory '*'; \
     git config --system init.defaultBranch main" || true

  [ -x rootfs/usr/bin/git ] && note "git: ready" || note "git: MISSING"
  return 0
}

# -- phase: Node.js --------------------------------------------------------
# The current LTS line, from NodeSource's repo. noble's own nodejs .deb is
# 18.x, two LTS lines behind what the chooser row promises — and it ships
# WITHOUT npm, which Ubuntu splits into a package of its own. NodeSource's
# nodejs carries npm inside it, so one install is the whole toolchain. Same
# recipe as Mozilla's repo in install_browsers: apt 2.4+ reads the
# ASCII-armoured key straight from Signed-By, no gpg — plus the same pin,
# because `nodejs` is a name the Ubuntu archive also answers to and the
# archive must never win it.
#
# The phase is judged by VERSION AND npm, in the guest — never by a binary
# existing. The first cut checked presence, and presence cannot tell node 24
# from the archive's 18: one hiccup in the (deliberately non-fatal) apt-get
# update left NodeSource's list unfetched, apt satisfied `nodejs` from noble
# instead, and the guard then called node-18-without-npm settled forever.
# Measured on device. node_current is both the "already installed" guard and
# the post-install verdict, so a guest holding the wrong node heals on its
# next provisioning pass instead of keeping it.
#
# NODE_MAJOR moves by hand when the LTS line does (even majors, every
# October): NodeSource serves no "current LTS" alias for its deb path, so a
# URL that tracked it would be a URL that does not exist.
#
# Unstamped, like setup_git: the chooser can tick this long after every other
# phase settled, and a touch-stamp would skip straight past that request
# forever. Non-fatal like Chromium — a guest without Node is still a working
# guest.
NODE_MAJOR=24
node_current() {
  _nv=$(run_guest "npm --version >/dev/null 2>&1 && node --version" 2>/dev/null | head -1)
  case "$_nv" in v*) ;; *) return 1 ;; esac
  _nv=${_nv#v}
  _nv=${_nv%%.*}
  [ "$_nv" -ge "$NODE_MAJOR" ] 2>/dev/null
}
setup_node() {
  if node_current; then
    note "nodejs: already installed"
    return 0
  fi
  guest_or_note "nodejs-prereqs" \
    "apt-get install -y --no-install-recommends curl ca-certificates" || return 1
  run_guest "install -d -m 0755 /etc/apt/keyrings" || return 1
  guest_or_note "nodejs-key" \
    "curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key -o /etc/apt/keyrings/nodesource.asc" \
    || return 1
  cat > rootfs/etc/apt/sources.list.d/nodesource.sources <<EOF || return 1
Types: deb
URIs: https://deb.nodesource.com/node_${NODE_MAJOR}.x
Suites: nodistro
Components: main
Signed-By: /etc/apt/keyrings/nodesource.asc
EOF
  mkdir -p rootfs/etc/apt/preferences.d || return 1
  cat > rootfs/etc/apt/preferences.d/nodesource <<'EOF' || return 1
Package: nodejs
Pin: origin deb.nodesource.com
Pin-Priority: 1000
EOF
  # Non-fatal like every other update here: a hiccup in ONE repo fails the
  # whole command and says nothing about whether nodejs is installable.
  run_guest "apt-get update" || true
  # But refuse to install ANYTHING when NodeSource's list is not actually
  # there: apt would quietly satisfy `nodejs` from the noble archive instead,
  # and a wrong node in place is worse than none — it reads as done to
  # everything except a version check.
  if ! run_guest "apt-cache policy nodejs | grep -q nodesource"; then
    note "nodejs: NodeSource repo unavailable — refusing the archive's 18.x"
    return 1
  fi
  guest_or_note "nodejs" \
    "apt-get install -y --no-install-recommends nodejs" || return 1
  if ! node_current; then
    note "nodejs: wrong version or npm missing after install"
    return 1
  fi
  note "nodejs: $(run_guest "node --version" 2>/dev/null | head -1) with npm — ready"
  return 0
}

# Before the browsers on purpose: git is one small package and the browsers are
# the slowest phase in the script. An editor that can open a repository should
# not be waiting on a 400 MB download to get there.
if wants git; then
  state installing-desktop 92 git
  setup_git || note "git: phase failed, continuing"
fi

# Ahead of the browsers for the same reason git is: one small download that
# should not queue behind two big ones.
if wants nodejs; then
  state installing-desktop 92 nodejs
  setup_node || note "nodejs: phase failed, continuing"
fi

# -- phase: GIMP -----------------------------------------------------------
# Straight from the Ubuntu archive: unlike the browsers there is no snap stub
# squatting on this name — noble's gimp is the real package. Its menu entry
# comes with the .deb, so unlike VS Code and IntelliJ nothing has to be
# written for it; setup_dock adds the desktop and panel launchers.
#
# Unstamped and guarded on the binary, like setup_git: the chooser can tick
# this long after every other phase settled. Non-fatal like Chromium — a
# guest without GIMP is still a working guest.
setup_gimp() {
  if [ -x rootfs/usr/bin/gimp ]; then
    note "gimp: already installed"
    return 0
  fi
  guest_or_note "gimp" \
    "apt-get install -y --no-install-recommends gimp" || return 1
  if [ ! -x rootfs/usr/bin/gimp ]; then
    note "gimp: MISSING after install"
    return 1
  fi
  note "gimp: ready"
  return 0
}

if wants gimp; then
  state installing-desktop 93 gimp
  setup_gimp || note "gimp: phase failed, continuing"
fi

# Guarded on the selection AND the binaries, not on a stamp: the chooser can
# come back with a browser ticked that the stamped pass was never asked for,
# and a plain .stamp-browsers would skip past that request forever. A wanted
# browser that is already in place costs one stat here, exactly like setup_git.
browsers_pending() {
  if wants firefox && [ ! -x rootfs/usr/bin/firefox ]; then return 0; fi
  if wants chromium && [ ! -x rootfs/usr/bin/chromium ]; then return 0; fi
  return 1
}

BROWSERS_RAN=
if browsers_pending; then
  state installing-desktop 93 install-browsers
  install_browsers || fail install-browsers
  BROWSERS_RAN=1
fi

# Re-tuned whenever an install pass just ran, not only on the first pass: the
# tuning is per-browser (Firefox's AutoConfig only lands when Firefox exists)
# and the default-browser pick depends on what is installed NOW.
if [ ! -f .stamp-browser-tune ] || [ -n "$BROWSERS_RAN" ]; then
  state installing-desktop 96 configure-browsers
  tune_browsers || fail configure-browsers
  touch .stamp-browser-tune
fi

# -- phase: the shared folder ----------------------------------------------
# /sdcard/LinuxOnDeX, which linux-rt.sh binds onto
# ~/Desktop/LinuxOnDeX. The bind AND the mountpoint belong to linux-rt.sh, not
# here: only it knows whether the bind is actually happening, and creating the
# folder here unconditionally would leave a permanently empty LinuxOnDeX on the
# desktop of every guest whose bind was skipped -- a folder that looks broken
# rather than absent.
#
# What this phase owns is the two things the guest needs in order to KNOW about
# it: a sidebar bookmark, and the certainty that xfdesktop draws folder icons.
#
# Runs BEFORE the VS Code phase deliberately. A FEATURE bump makes VS Code
# re-attempt, and that phase is the slow, networked one; the shared folder is
# neither, so it lands first and is not held hostage by it.
#
# Deliberately UNSTAMPED, like setup_dock and for the reason recorded on
# .stamp-vscode: a plain touch-stamp freezes the first implementation forever
# on existing guests, since `rm -f .stamp-*` only fires on a VERSION change.
# Everything in here is idempotent.
setup_share() {
  # One file, read by Thunar's sidebar AND by every GTK3 file chooser -- the
  # browsers' download dialogs and VS Code's open dialog included, since no
  # xdg-desktop-portal is installed and they all fall back to GTK's own. Both
  # watch it with a GFileMonitor, so writing it now is picked up by a session
  # that starts later.
  mkdir -p rootfs/root/.config/gtk-3.0 || return 1
  _bm=rootfs/root/.config/gtk-3.0/bookmarks
  _line='file:///root/Desktop/LinuxOnDeX LinuxOnDeX'
  if ! grep -qxF "$_line" "$_bm" 2>/dev/null; then
    # A file that does not end in a newline would otherwise get this
    # concatenated onto its last line, which `grep -qxF` then never matches --
    # so it would be appended again on every pass and the sidebar entry would
    # never appear. $( ) strips the trailing newline, so a properly terminated
    # file tests empty here.
    [ -s "$_bm" ] && [ -n "$(tail -c 1 "$_bm")" ] && printf '
' >> "$_bm"
    echo "$_line" >> "$_bm" || return 1
  fi

  # xfdesktop draws desktop ICONS only when its style says so, and these
  # scripts write no xfce4-desktop channel at all -- so it is otherwise left to
  # whatever the distro defaults to. If that default were launchers-only the
  # bound folder would render as nothing, which looks exactly like a broken
  # bind. style=2 is "file/launcher icons".
  #
  # Written ONLY when the user has no channel of their own, so it can never
  # overwrite a desktop layout they chose. The show-* entries keep the desktop
  # to the shared folder and our launchers instead of adding Home, Filesystem
  # and Trash icons the user never asked for.
  _xfd=rootfs/root/.config/xfconf/xfconf-perchannel-xml/xfce4-desktop.xml
  if [ ! -f "$_xfd" ]; then
    mkdir -p rootfs/root/.config/xfconf/xfconf-perchannel-xml || return 1
    cat > "$_xfd" <<'EOF' || return 1
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-desktop" version="1.0">
  <property name="desktop-icons" type="empty">
    <property name="style" type="int" value="2"/>
    <property name="file-icons" type="empty">
      <property name="show-home" type="bool" value="false"/>
      <property name="show-filesystem" type="bool" value="false"/>
      <property name="show-removable" type="bool" value="false"/>
      <property name="show-trash" type="bool" value="false"/>
    </property>
  </property>
</channel>
EOF
  fi
  # The folder ICON. adwaita-icon-theme ships places/folder as a PNG at 16x16
  # only -- every larger size is an SVG, and xfdesktop draws at 48. Rendering
  # those needs the gdk-pixbuf SVG loader out of librsvg2-common, which is a
  # Recommends and so is dropped by our --no-install-recommends. Without it the
  # shared folder appears on the desktop as a bare label with no icon.
  #
  # Installed from HERE and not only from the desktop apt line, because that
  # phase is stamped: no guest that already exists would ever get it there.
  # Guarded on the loader file so a settled guest costs no network round trip.
  if ! ls rootfs/usr/lib/*/gdk-pixbuf-2.0/*/loaders/libpixbufloader-svg.so        >/dev/null 2>&1; then
    guest_or_note "share-icons"       "apt-get install -y --no-install-recommends librsvg2-common" || true
  fi
  note "share: sidebar bookmark + desktop icon style configured"
  return 0
}

state installing-desktop 97 share
setup_share || true

# -- phase: dock + desktop launchers ---------------------------------------
# Installing an app is not the same as being able to FIND it, and a menu three
# levels deep is not finding it. Firefox, Chromium and VS Code get an entry we
# own, a desktop icon, and a place on the panel.
#
# The .desktop entries are ours rather than the packages' because they point at
# the dex-* wrappers (an apt upgrade rewrites the packaged ones straight back to
# the unwrapped binary) and because we can decline to create the one whose app
# failed to install, instead of leaving a dead icon on the desktop.
# LAUNCHERS is deliberately NOT under /usr/local/share/applications. That path
# IS on XDG_DATA_DIRS, so entries there join the Applications menu — and since
# ours carry the same Name as the packages' own, the Internet menu showed
# "Firefox" and "Chromium" twice. These files are templates for the dock and the
# desktop, not menu entries; the packaged .desktop files (whose Exec we rewrite
# to the wrappers) are what the menu should show.
LAUNCHERS=usr/local/share/openandroiddex/launchers

dock_entry() { # id name icon exec-wrapper categories
  cat > "rootfs/$LAUNCHERS/$1.desktop" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=$2
Icon=$3
Exec=$4 %U
Terminal=false
StartupNotify=true
Categories=$5
EOF
  chmod 755 "rootfs/$LAUNCHERS/$1.desktop"
  cp -f "rootfs/$LAUNCHERS/$1.desktop" "rootfs/root/Desktop/$1.desktop"
  # xfdesktop refuses to run a launcher it does not consider trusted, and the
  # executable bit is what it reads as trust.
  chmod 755 "rootfs/root/Desktop/$1.desktop"
}

setup_dock() {
  mkdir -p "rootfs/$LAUNCHERS" rootfs/root/Desktop \
           rootfs/usr/local/share/openandroiddex || return 1
  # Clean up the earlier location, which duplicated every entry in the menu.
  rm -f rootfs/usr/local/share/applications/dex-*.desktop

  DOCK_IDS=
  if [ -x rootfs/usr/bin/firefox ]; then
    dock_entry dex-firefox Firefox firefox /usr/local/bin/dex-firefox 'Network;WebBrowser;'
    DOCK_IDS="$DOCK_IDS dex-firefox"
  fi
  if [ -x rootfs/usr/bin/chromium ]; then
    dock_entry dex-chromium Chromium chromium /usr/local/bin/dex-chromium 'Network;WebBrowser;'
    DOCK_IDS="$DOCK_IDS dex-chromium"
  fi
  # /usr/bin/code is a symlink whose target is guest-absolute, so from out here
  # it resolves against ANDROID's filesystem and always looks broken. Ask about
  # the real file.
  if [ -x rootfs/opt/vscode/bin/code ]; then
    dock_entry dex-code "Visual Studio Code" /opt/vscode/resources/app/resources/linux/code.png /usr/local/bin/dex-code 'Development;IDE;'
    DOCK_IDS="$DOCK_IDS dex-code"
  fi
  if [ -x rootfs/usr/bin/gimp ]; then
    dock_entry dex-gimp GIMP gimp /usr/bin/gimp 'Graphics;'
    DOCK_IDS="$DOCK_IDS dex-gimp"
  fi
  if [ -x rootfs/opt/intellij/bin/idea.sh ]; then
    dock_entry dex-intellij "IntelliJ IDEA" /opt/intellij/bin/idea.svg /opt/intellij/bin/idea.sh 'Development;IDE;'
    DOCK_IDS="$DOCK_IDS dex-intellij"
  fi
  note "dock: launchers ->${DOCK_IDS:- none}"
  [ -n "$DOCK_IDS" ] || return 0

  # The panel edit needs a real XML parser. Regex on xfconf's config is how you
  # hand someone an empty panel, and python3 is a rounding error next to the
  # browsers already in here. NOT python3-minimal: it ships without shutil and
  # without ElementTree, so the edit would have failed silently (measured).
  run_guest "apt-get install -y --no-install-recommends python3" || true
  [ -x rootfs/usr/bin/python3 ] || return 0

  cat > rootfs/usr/local/share/openandroiddex/dock.py <<'PYEOF'
"""Append launchers to the XFCE panel without rewriting the user's layout.

Authoring a whole panel config would mean inventing positions, sizes and plugin
sets, and getting one of them wrong hands the user a broken desktop. So this
starts from whatever config already exists -- theirs, or the distro default --
and only ADDS: a launcher plugin per app, appended to the first panel's
plugin-ids, plus the launcher item files the panel resolves those against.
"""
import os
import sys
import xml.etree.ElementTree as ET

USER = "/root/.config/xfconf/xfconf-perchannel-xml/xfce4-panel.xml"
SYSTEM = "/etc/xdg/xfce4/panel/default.xml"
APPS = "/usr/local/share/openandroiddex/launchers"

# Whatever is in the launcher directory, which is only ever the apps that
# actually installed. No argument list to keep in step with it.
try:
    ids = sorted(f[:-len(".desktop")] for f in os.listdir(APPS)
                 if f.endswith(".desktop"))
except OSError:
    ids = []
if not ids:
    sys.exit(0)

src = USER if os.path.exists(USER) else SYSTEM
if not os.path.exists(src):
    sys.exit("no panel config to extend")

tree = ET.parse(src)
root = tree.getroot()

panels = root.find("./property[@name='panels']")
plugins = root.find("./property[@name='plugins']")
if panels is None or plugins is None:
    sys.exit("panel config has no panels/plugins section")

kind = {}
for child in plugins:
    kind[child.get("name", "")] = child.get("value", "")


def launcher_positions(panel_el):
    """Indices of the launcher plugins already on this panel."""
    ids_el = panel_el.find("./property[@name='plugin-ids']")
    if ids_el is None:
        return []
    return [i for i, v in enumerate(ids_el)
            if kind.get("plugin-" + str(v.get("value"))) == "launcher"]


# THE DOCK is whichever panel already holds launchers -- on a stock Ubuntu
# XFCE that is panel-2, the bottom strip, while panel-1 is the top bar with the
# menu and the clock. Picking "the first panel" put these on the top bar next
# to the clock, which is not what anyone means by the dock. Falling back to the
# last panel, then the first, keeps this working on a layout we have not seen.
candidates = [c for c in panels if c.get("name", "").startswith("panel-")]
if not candidates:
    sys.exit("no panel to extend")
panel = None
for candidate in candidates:
    if launcher_positions(candidate):
        panel = candidate
        break
if panel is None:
    panel = candidates[-1]

plugin_ids = panel.find("./property[@name='plugin-ids']")
if plugin_ids is None:
    plugin_ids = ET.SubElement(
        panel, "property", {"name": "plugin-ids", "type": "array"})

# Go in beside the launchers that are already there, not after the clock or
# the trailing separator.
here = launcher_positions(panel)
at = (here[-1] + 1) if here else len(list(plugin_ids))

used = set()
for value in plugin_ids.findall("value"):
    try:
        used.add(int(value.get("value")))
    except (TypeError, ValueError):
        pass
for child in plugins:
    name = child.get("name", "")
    if name.startswith("plugin-"):
        try:
            used.add(int(name.split("-", 1)[1]))
        except ValueError:
            pass

# Already ours? Then this has run before and must not stack duplicates.
existing = set()
for child in plugins:
    for item in child.findall("./property[@name='items']/value"):
        existing.add(os.path.basename(item.get("value", "")))

added = 0
next_id = (max(used) + 1) if used else 1
for app in ids:
    item = "%s.desktop" % app
    if item in existing:
        continue
    desktop = os.path.join(APPS, item)
    if not os.path.exists(desktop):
        continue
    pid = next_id
    next_id += 1

    plugin = ET.SubElement(
        plugins, "property",
        {"name": "plugin-%d" % pid, "type": "string", "value": "launcher"})
    items = ET.SubElement(plugin, "property", {"name": "items", "type": "array"})
    ET.SubElement(items, "value", {"type": "string", "value": item})

    # The panel resolves an item name against its own launcher directory, so
    # the .desktop has to be copied in beside it -- a path is not accepted.
    d = "/root/.config/xfce4/panel/launcher-%d" % pid
    os.makedirs(d, exist_ok=True)
    with open(desktop, "rb") as src_f, open(os.path.join(d, item), "wb") as dst_f:
        dst_f.write(src_f.read())

    plugin_ids.insert(at, ET.Element("value", {"type": "int", "value": str(pid)}))
    at += 1
    added += 1

if not added:
    sys.exit(0)

os.makedirs(os.path.dirname(USER), exist_ok=True)
tree.write(USER, encoding="UTF-8", xml_declaration=True)
print("added %d launcher(s) to %s" % (added, panel.get("name")))
PYEOF

  # Run it here too, for the case where no session is up — but linux-rt.sh is
  # what makes it stick. xfce4-panel writes its config out when it EXITS, so an
  # edit made while a session is live is simply overwritten by that session's
  # own idea of the layout on the way out, which is why the launchers never
  # appeared: provisioning runs alongside the desktop that then discards it.
  # linux-rt.sh runs the same script just BEFORE starting xfce, when no panel
  # is holding the config. Idempotent either way.
  guest_or_note "dock" "python3 /usr/local/share/openandroiddex/dock.py" || true
  return 0
}

# .stamp-vscode means SETTLED, not installed: either VS Code went in, or it
# failed often enough that we stop paying for the attempt. It HOLDS THE FEATURE
# LEVEL it settled at, and that is the important part — a plain touch-file said
# only "we tried", so when the install method changed underneath it (the .deb
# gave way to the tarball) the phase stayed skipped and the new method never
# ran. A higher FEAT means a different attempt is on offer, so try again.
#
# The stamp is also what Linux.needsProvision watches, and it reads the level
# out of it the same way: an unsettled VS Code is what brings the setup script
# back at all, since the guest is otherwise `ready` and provisioning is skipped.
vscode_settled() {
  # Already installed? Then this FEAT's attempt has nothing to do. Record the
  # level and skip. Without this, EVERY feature bump re-ran install_vscode in
  # full on a guest that already had it -- dpkg --remove, the dependency
  # apt-get and a fresh 330 MB download over the phone's connection, to arrive
  # exactly where it started. The retry machinery below exists for guests where
  # the install FAILED, not for ones where it worked.
  if [ -x rootfs/opt/vscode/bin/code ]; then
    echo "$FEAT" > .stamp-vscode
    rm -f .vscode-attempts
    return 0
  fi
  [ -f .stamp-vscode ] || return 1
  _at=$(cat .stamp-vscode 2>/dev/null)
  [ -n "$_at" ] || return 1
  [ "$_at" -ge "$FEAT" ] 2>/dev/null
}

# A tick that is NEW since the last build re-opens a settled verdict: settling
# means "stop paying for the attempt", and a user who just asked again has put
# fresh attempts back on the table. apps.done is what the last build was asked
# for; it is rewritten at the end of this run.
prev_wants() { case " $(cat apps.done 2>/dev/null) " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
if wants code && ! prev_wants code; then rm -f .stamp-vscode .vscode-attempts; fi

if wants code && ! vscode_settled; then
  state installing-desktop 98 install-vscode
  if install_vscode; then
    echo "$FEAT" > .stamp-vscode
    rm -f .vscode-attempts
  else
    tries=$(cat .vscode-attempts 2>/dev/null)
    [ -n "$tries" ] || tries=0
    tries=$((tries + 1))
    echo "$tries" > .vscode-attempts
    if [ "$tries" -ge 3 ]; then
      note "vscode: giving up after $tries attempts — the rest of the guest is fine"
      echo "$FEAT" > .stamp-vscode
      rm -f .vscode-attempts
    else
      note "vscode: attempt $tries failed, will retry on the next launch"
    fi
  fi
fi

# -- phase: the vscode:// URL handler --------------------------------------
# Signing in to GitHub from VS Code ends in the browser on a page that redirects
# to vscode://vscode.github-authentication/did-authenticate?… — and with nothing
# registered for that scheme, XFCE answers "Failed to open URI. Operation not
# supported." and the sign-in stops there. Measured in the guest.
#
# The packaged VS Code ships a second, hidden .desktop for exactly this. The
# tarball ships no .desktop at all, so it is ours to write, and registration is
# two halves that both have to land: an entry that CLAIMS
# x-scheme-handler/vscode, and a default that POINTS at it. GIO — which both
# exo-open and xdg-open end up inside — will not choose a handler on its own.
#
# Unstamped and guarded on the binary, like setup_git and for a sharper reason:
# a guest that already has VS Code never re-runs install_vscode (see
# vscode_settled), so anything added in there would never reach the machines
# that need this.
setup_vscode_urls() {
  [ -x rootfs/opt/vscode/bin/code ] || return 0

  mkdir -p rootfs/usr/local/share/applications || return 1
  cat > rootfs/usr/local/share/applications/code-url-handler.desktop <<'EOF' || return 1
[Desktop Entry]
Version=1.0
Type=Application
Name=Visual Studio Code - URL Handler
GenericName=Text Editor
Comment=Code Editing. Redefined.
Icon=/opt/vscode/resources/app/resources/linux/code.png
Exec=/usr/local/bin/dex-code --open-url %U
Terminal=false
NoDisplay=true
StartupNotify=true
StartupWMClass=Code
Categories=Utility;TextEditor;Development;IDE;
MimeType=x-scheme-handler/vscode;
EOF

  # xdg-mime rather than editing mimeapps.list by hand: the file has sections,
  # the browser wiring may already own part of it, and toybox sed cannot insert
  # a line under a header without mangling the rest. update-desktop-database
  # first because GIO reads the CACHE — without it the entry above is invisible
  # no matter how correct it is.
  guest_or_note "vscode-url-handler" \
    "command -v update-desktop-database >/dev/null 2>&1 \
       || apt-get install -y --no-install-recommends desktop-file-utils; \
     command -v xdg-mime >/dev/null 2>&1 \
       || apt-get install -y --no-install-recommends xdg-utils; \
     update-desktop-database /usr/local/share/applications 2>/dev/null; \
     xdg-mime default code-url-handler.desktop x-scheme-handler/vscode" || return 1

  note "vscode: vscode:// handler registered"
  return 0
}

state installing-desktop 98 vscode-url-handler
setup_vscode_urls || note "vscode-url-handler: phase failed, continuing"

# -- phase: IntelliJ IDEA --------------------------------------------------
# Community Edition, from JetBrains' official tarball — the same shape as the
# VS Code phase, for stronger reasons: JetBrains publishes no .deb at all
# (their Linux channels are snap and Toolbox, neither of which can exist in
# here), and the tarball bundles its own JetBrains Runtime, so no Java has to
# be installed for it. The data-services URL is JetBrains' documented "latest
# release" redirect, the same role update.code.visualstudio.com plays for VS
# Code. A JVM needs no sandbox flags, so unlike the Electron apps there is no
# dex- wrapper: the stock idea.sh is the launcher everywhere.
#
# The X/AWT libraries are listed by hand because --no-install-recommends
# strips them from everything else and the bundled JBR dlopens them at
# startup; missing, IDEA dies before its first window.
#
# The tarball ships no .desktop, so the Applications menu needs one from us —
# same as VS Code, and like it this one belongs on the XDG path: there is no
# packaged entry for it to duplicate.
#
# Unstamped and guarded on the binary, like setup_git. Non-fatal like
# Chromium — a guest without IDEA is still a working guest.
setup_intellij() {
  if [ -x rootfs/opt/intellij/bin/idea.sh ]; then
    note "intellij: already installed"
    return 0
  fi
  case "$(uname -m)" in
    aarch64|arm64) IJ_PLATFORM=linuxARM64 ;;
    x86_64)        IJ_PLATFORM=linux ;;
    *) note "intellij: no build for $(uname -m)"; return 1 ;;
  esac

  guest_or_note "intellij-deps" "apt-get install -y --no-install-recommends \
    curl ca-certificates fontconfig libfreetype6 libxext6 libxrender1 \
    libxtst6 libxi6" || return 1

  note "intellij: downloading (about 1 GB)"
  guest_or_note "intellij-download" \
    "curl -fL --retry 3 -o /tmp/intellij.tar.gz 'https://data.services.jetbrains.com/products/download?code=IIC&platform=$IJ_PLATFORM&type=release'" \
    || return 1

  note "intellij: extracting"
  # --strip-components=1 drops the tarball's idea-IC-<build>/ wrapper dir.
  # The rm is outside the && chain so a partial extraction still frees 1 GB.
  guest_or_note "intellij-extract" \
    "rm -rf /opt/intellij && mkdir -p /opt/intellij && tar -xzf /tmp/intellij.tar.gz -C /opt/intellij --strip-components=1; rm -f /tmp/intellij.tar.gz" \
    || return 1
  if [ ! -x rootfs/opt/intellij/bin/idea.sh ]; then
    note "intellij-extract FAILED: /opt/intellij/bin/idea.sh missing after extraction"
    return 1
  fi

  mkdir -p rootfs/usr/local/share/applications || return 1
  cat > rootfs/usr/local/share/applications/intellij.desktop <<'EOF' || return 1
[Desktop Entry]
Version=1.0
Type=Application
Name=IntelliJ IDEA Community
GenericName=Java IDE
Comment=Capable and Ergonomic IDE for JVM
Icon=/opt/intellij/bin/idea.svg
Exec=/opt/intellij/bin/idea.sh %f
Terminal=false
StartupNotify=true
StartupWMClass=jetbrains-idea-ce
Categories=Development;IDE;
EOF
  note "intellij: installed"
  return 0
}

if wants intellij; then
  state installing-desktop 98 intellij
  setup_intellij || note "intellij: phase failed, continuing"
fi

# -- VS Code settings ------------------------------------------------------
# Extension installs fail in here with "cannot verify the extension signature
# ... Signature verification failed with 'UnknownError'". The verifier is a
# native module (@vscode/vsce-sign) that does not work on this platform, and it
# fails closed, so EVERY marketplace extension is refused. `extensions.
# verifySignature` is the supported switch for exactly that case -- the same
# thing the dialog's "Install Anyway" button does, made permanent.
#
# update.mode=none because this VS Code came from a tarball we manage: it cannot
# replace itself, so update prompts are noise that leads nowhere.
#
# setdefault, never overwrite: if the user has since chosen either of these,
# their choice stands.
tune_vscode() {
  [ -x rootfs/opt/vscode/bin/code ] || return 0
  [ -x rootfs/usr/bin/python3 ] || return 0
  mkdir -p rootfs/usr/local/share/openandroiddex rootfs/root/.vscode/User || return 1
  cat > rootfs/usr/local/share/openandroiddex/vscode-settings.py <<'PYEOF'
"""Merge the settings VS Code needs to be usable in this container."""
import json
import os

# Must match --user-data-dir in /usr/local/bin/dex-code.
PATH = "/root/.vscode/User/settings.json"
WANT = {
    # The signature verifier does not work on this platform and fails closed.
    "extensions.verifySignature": False,
    # A tarball install cannot replace itself.
    "update.mode": "none",
}

os.makedirs(os.path.dirname(PATH), exist_ok=True)
settings = {}
if os.path.exists(PATH):
    try:
        with open(PATH) as f:
            settings = json.load(f)
        if not isinstance(settings, dict):
            settings = {}
    except Exception:
        # VS Code's settings.json permits comments, which json cannot read, and
        # a hand-edited file may simply be broken. Keep a copy rather than
        # either clobbering it silently or giving up on the fix.
        os.replace(PATH, PATH + ".bak")
        settings = {}

changed = False
for key, value in WANT.items():
    if key not in settings:
        settings[key] = value
        changed = True

if changed:
    with open(PATH, "w") as f:
        json.dump(settings, f, indent=2)
    print("vscode settings updated")
PYEOF
  guest_or_note "vscode-settings" "python3 /usr/local/share/openandroiddex/vscode-settings.py" || true
  return 0
}

# Deliberately UNSTAMPED. It has to run on every setup pass, because the app it
# would have added an icon for may only have installed on THIS pass — VS Code's
# phase is retried until it succeeds, and a stamped dock would have missed it
# forever. Everything it does is idempotent: dock.py skips launchers that are
# already on the panel, and an icon is only created for an app that exists.
state installing-desktop 99 dock
setup_dock || true
# After setup_dock, which is what installs python3.
tune_vscode || true

# The selection this guest was built from, echoed back VERBATIM:
# Linux.needsProvision compares it byte-for-byte against the stored tick list,
# and a mismatch is what brings this script back when the chooser's answer
# changes. Written just before `ready`, so a run that died mid-way never
# records a selection it did not build.
printf '%s\n' "$APPS_SEL" > apps.done

state ready 100 ready
echo "provisioned OK (payload version $VER, features $FEAT)"
