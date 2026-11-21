#!/bin/sh
# The sessions' D-Bus policy (openandroiddex-linux/guest/oadx-apps-dbus.conf)
# against a real dbus-daemon in ubuntu:24.04, the guest's own. The daemon runs
# as root, the way the sessions' buses run as proot's fake root; the clients
# run as the users the bus sees on a phone:
#   10556  this app's Android user: what an app in a proot of its own is to
#          the desktop's bus (proot fakes a peer's user only for its own
#          tracees). GLib's gdbus and libdbus's dbus-send both.
#   10557  another app on the phone. Must stay out, both ways.
# First without the file (the bus as it was: 10556 refused, the failure seen
# on the phone), then with it and the passwd line LinuxAppsLink writes.
#
# Then the phone's own shape, with the app's own proot (build_proot.sh): the bus
# in one proot and
# each client in another, all as the one real user 10556, each believing it is
# root. A libdbus client then says root over a socket that says 10556, which
# EXTERNAL refuses, so it has to prove itself with the cookie in
# /root/.dbus-keyrings: shown working, and shown failing with
# DBUS_COOKIE_SHA1 taken out of the policy.
set -u
REPO=${REPO:-/work}
pass=0
fail=0
ok() { pass=$((pass + 1)); echo "  ok   $1"; }
bad() { fail=$((fail + 1)); echo "  FAIL $1 -- $2"; }
ADDR=unix:abstract=oadx-test-bus

start_bus() {
  dbus-daemon --session --address=$ADDR --nofork --print-address=1 >/tmp/bus.addr 2>/tmp/bus.log &
  BUS=$!
  n=0
  while [ ! -s /tmp/bus.addr ] && [ $n -lt 50 ]; do sleep 0.1; n=$((n + 1)); done
}
stop_bus() { kill $BUS 2>/dev/null; wait $BUS 2>/dev/null; }

as() { # uid command... -- the command as that user, no supplementary groups
  u=$1
  shift
  setpriv --reuid="$u" --regid="$u" --clear-groups env HOME=/tmp/home-$u DBUS_SESSION_BUS_ADDRESS=$ADDR "$@"
}
gd() { as "$1" timeout 10 gdbus call --session --dest org.freedesktop.DBus --object-path /org/freedesktop/DBus --method org.freedesktop.DBus.GetId >/tmp/out 2>&1; }
ds() { as "$1" timeout 10 dbus-send --session --print-reply --dest=org.freedesktop.DBus /org/freedesktop/DBus org.freedesktop.DBus.GetId >/tmp/out 2>&1; }
mkdir -p /tmp/home-10556 /tmp/home-10557
chown 10556:10556 /tmp/home-10556
chown 10557:10557 /tmp/home-10557

echo "  -- the bus as it was"
rm -f /etc/dbus-1/session.d/oadx-apps.conf
start_bus
if gd 10556; then bad "without the policy, the app's own user is refused (GLib)" "got in"; else ok "without the policy, the app's own user is refused (GLib): the phone's failure"; fi
if ds 10556; then bad "without the policy, the app's own user is refused (libdbus)" "got in"; else ok "without the policy, the app's own user is refused (libdbus)"; fi
stop_bus

echo "  -- with the policy and the passwd line"
mkdir -p /etc/dbus-1/session.d
cp "$REPO/openandroiddex-linux/guest/oadx-apps-dbus.conf" /etc/dbus-1/session.d/oadx-apps.conf
# LinuxAppsLink.passwdText's line (its format is LinuxAppsLinkTest's).
echo "oadx-android:x:10556:10556:LinuxOnDroid app user (oadx-run):/nonexistent:/usr/sbin/nologin" >> /etc/passwd
start_bus
if grep -qi "unknown username" /tmp/bus.log; then bad "the bus knows the user" "$(cat /tmp/bus.log)"; else ok "the bus resolves oadx-android (no \"Unknown username\")"; fi
if gd 10556; then ok "the app's own user gets in (GLib: EXTERNAL, taken as the socket's user)"; else bad "app user, GLib" "$(head -3 /tmp/out)"; fi
if ds 10556; then ok "the app's own user gets in (libdbus)"; else bad "app user, libdbus" "$(head -3 /tmp/out)"; fi
if gd 10557; then bad "another app's user stays out (GLib)" "got in"; else ok "another app's user stays out (GLib)"; fi
if ds 10557; then bad "another app's user stays out (libdbus)" "got in"; else ok "another app's user stays out (libdbus)"; fi
if gd 0; then ok "root, the bus's own user, still gets in"; else bad "root" "$(head -3 /tmp/out)"; fi
# The cookie: the server makes it in its own home, readable by root alone.
if [ -d /root/.dbus-keyrings ]; then
  m=$(stat -c %a /root/.dbus-keyrings)
  if [ "$m" = 700 ]; then ok "the cookie folder is root's alone ($m)"; else bad "cookie folder mode" "$m"; fi
else
  ok "no cookie used yet by these clients (EXTERNAL sufficed)"
fi
stop_bus

echo "  -- the phone's shape: bus and clients in proots of their own, one real user"
if [ -x /opt/proot/proot ]; then
  PADDR=unix:abstract=oadx-proot-bus
  # The guest's /root is this app's on a phone; proot shows it as root's.
  rm -rf /root/.dbus-keyrings
  chown 10556:10556 /root
  chmod 700 /root
  inproot() { # uid command... -- in a proot of its own, believing it is root
    u=$1
    shift
    setpriv --reuid="$u" --regid="$u" --clear-groups env HOME=/root DBUS_SESSION_BUS_ADDRESS=$PADDR \
      PROOT_LOADER=/opt/proot/loader PROOT_TMP_DIR=/tmp /opt/proot/proot -0 "$@"
  }
  start_pbus() {
    rm -f /tmp/pbus.addr
    inproot 10556 dbus-daemon --session --address=$PADDR --nofork --print-address=1 >/tmp/pbus.addr 2>/tmp/pbus.log &
    PBUS=$!
    n=0
    while [ ! -s /tmp/pbus.addr ] && [ $n -lt 100 ]; do sleep 0.1; n=$((n + 1)); done
  }
  stop_pbus() { kill $PBUS 2>/dev/null; pkill -f "address=$PADDR" 2>/dev/null; wait $PBUS 2>/dev/null; }
  pds() { inproot "$1" timeout 10 dbus-send --session --print-reply --dest=org.freedesktop.DBus /org/freedesktop/DBus org.freedesktop.DBus.GetId >/tmp/out 2>&1; }
  pgd() { inproot "$1" timeout 10 gdbus call --session --dest org.freedesktop.DBus --object-path /org/freedesktop/DBus --method org.freedesktop.DBus.GetId >/tmp/out 2>&1; }
  who=$(inproot 10556 id -u)
  [ "$who" = 0 ] && ok "in proot -0, 10556 believes it is root (id -u says $who)" || bad "proot -0" "id -u says $who"

  # Without the cookie: the libdbus client has nothing that works.
  sed 's|<auth>DBUS_COOKIE_SHA1</auth>||' "$REPO/openandroiddex-linux/guest/oadx-apps-dbus.conf" > /etc/dbus-1/session.d/oadx-apps.conf
  start_pbus
  if pds 10556; then bad "with no DBUS_COOKIE_SHA1, libdbus saying root over a 10556 socket is refused" "got in"
  else ok "with no DBUS_COOKIE_SHA1, libdbus saying root over a 10556 socket is refused (the phone's failure for it)"; fi
  stop_pbus
  rm -rf /root/.dbus-keyrings

  cp "$REPO/openandroiddex-linux/guest/oadx-apps-dbus.conf" /etc/dbus-1/session.d/oadx-apps.conf
  start_pbus
  if pds 10556; then ok "with the policy, it gets in: libdbus from another proot (EXTERNAL refused, then the cookie)"
  else bad "libdbus across proots" "$(head -3 /tmp/out)"; fi
  if [ -s /root/.dbus-keyrings/org_freedesktop_general ]; then
    ok "and it was the cookie: the bus wrote /root/.dbus-keyrings/org_freedesktop_general for it ($(stat -c %a /root/.dbus-keyrings))"
  else bad "the cookie was used" "no keyring: $(ls -la /root/.dbus-keyrings 2>&1 | head -3)"; fi
  if pgd 10556; then ok "GLib from another proot gets in (EXTERNAL, taken as 10556, allowed by name)"
  else bad "GLib across proots" "$(head -3 /tmp/out)"; fi
  if pds 10557; then bad "another app's user in a proot stays out (libdbus)" "got in"
  else ok "another app's user in a proot stays out (libdbus: its socket is not 10556, the cookie not its to read)"; fi
  if pgd 10557; then bad "another app's user in a proot stays out (GLib)" "got in"
  else ok "another app's user in a proot stays out (GLib)"; fi
  stop_pbus
  rm -rf /root/.dbus-keyrings
  chown 0:0 /root
else
  bad "the phone's shape" "no /opt/proot/proot (build_proot.sh) in this container"
fi

echo "  dbus: $pass passed, $fail failed"
[ $fail -eq 0 ]
