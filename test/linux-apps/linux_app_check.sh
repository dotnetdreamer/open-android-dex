#!/bin/sh
# linux-app.sh as LinuxAppsService runs it, under mksh (the shell Android
# ships as /system/bin/sh) with util-linux setsid as /system/bin/setsid, and a
# stand-in for proot that records its arguments and runs the command it was
# given with no translation: the guest's /tmp is the host's here, through a
# link. What this shows: the request check, the session it makes, the pid it
# writes (the group the service kills), the proot command line, and the inner
# claim (ID.run to ID.go) racing oadx-run's own. What it cannot: proot itself,
# and toybox's setsid, which the phone run covers (doc/linux-apps-process.md).
set -u
REPO=${REPO:-/work}
pass=0
fail=0
ok() { pass=$((pass + 1)); echo "  ok   $1"; }
bad() { fail=$((fail + 1)); echo "  FAIL $1 -- $2"; }

mkdir -p /system/bin
ln -sf "$(command -v mksh)" /system/bin/sh
ln -sf "$(command -v setsid)" /system/bin/setsid
R=/r
rm -rf "$R" /tmp/.oadx-apps
mkdir -p "$R/rootfs/usr/bin" /tmp/.oadx-apps
ln -s /tmp "$R/tmp"
SCRIPT=$R/linux-app.sh
cp "$REPO/openandroiddex-linux/linux-app.sh" "$SCRIPT"
cat > "$R/fakeproot" <<'EOF'
#!/bin/sh
# Records the command line, then runs what follows "-w DIR".
printf '%s\n' "$@" > /tmp/oadx-proot.args
while [ $# -gt 0 ]; do
  case "$1" in
    -w) shift 2; break ;;
    *) shift ;;
  esac
done
exec "$@"
EOF
chmod 755 "$R/fakeproot"
export LINUX_ROOT=$R LINUX_PROOT=$R/fakeproot PROOT_LOADER=/l PROOT_LOADER_32=/l32 LINUX_STORAGE=/nonexistent

req() { # id body
  printf '#!/bin/sh\n# oadx-run 1 display=:1 name=t\nrm -f -- "$0"\n%s\n' "$2" > "/tmp/.oadx-apps/$1.run"
}

echo "linux-app.sh"
/system/bin/sh "$SCRIPT" 'x;rm -rf /' >/tmp/o 2>&1
rc=$?
if [ $rc = 2 ] && grep -q "bad id" /tmp/o; then ok "an id with shell in it is refused"; else bad "bad id" "rc $rc: $(cat /tmp/o)"; fi
/system/bin/sh "$SCRIPT" 1-1 >/tmp/o 2>&1
rc=$?
if [ $rc = 2 ] && grep -q "no request" /tmp/o; then ok "no ID.run: nothing starts"; else bad "missing request" "rc $rc"; fi

req 100-1 'echo ran > /tmp/oadx-ran.1; echo $$ > /tmp/oadx-sh.1; sleep 2'
/system/bin/sh "$SCRIPT" 100-1 >/tmp/o 2>&1 &
child=$!
sleep 0.6
pid=$(cat /tmp/.oadx-apps/100-1.pid 2>/dev/null)
is_() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "got [$2] want [$3]"; fi; }
is_ "the pid it writes is the process the service started (setsid did not fork)" "$pid" "$child"
is_ "and that process leads its own group, which the service kills" "$(ps -o pgid= -p "$child" | tr -d ' ')" "$child"
is_ "and its own session" "$(ps -o sid= -p "$child" | tr -d ' ')" "$child"
is_ "the request ran" "$(cat /tmp/oadx-ran.1 2>/dev/null)" "ran"
if [ ! -e /tmp/.oadx-apps/100-1.run ] && [ ! -e /tmp/.oadx-apps/100-1.go ]; then
  ok "claimed (ID.run to ID.go) and gone once it started"
else
  bad "claimed and gone" "$(ls /tmp/.oadx-apps)"
fi
args=$(tr '\n' ' ' < /tmp/oadx-proot.args)
case " $args " in
  *" -0 --link2symlink -r $R/rootfs "*) ok "proot: fake root, link2symlink, the rootfs" ;;
  *) bad "proot options" "$args" ;;
esac
case " $args " in
  *"--kill-on-exit"*) bad "proot: no --kill-on-exit" "$args" ;;
  *) ok "proot: no --kill-on-exit, so an app that hands over and exits stays" ;;
esac
case " $args " in
  *" -b /dev -b /proc -b /sys -b $R/tmp:/tmp -b $R/shm:/dev/shm "*) ok "proot: the sessions' binds" ;;
  *) bad "proot binds" "$args" ;;
esac
case " $args " in
  *"/root/storage"*) bad "storage bind" "bound a path that is not there: $args" ;;
  *) ok "proot: no storage bind for a storage path that is not there (proot would fail on it)" ;;
esac
case " $args " in
  *" /usr/bin/env -i HOME=/root PATH="*) ok "proot: the app starts from a bare environment" ;;
  *) bad "bare environment" "$args" ;;
esac
wait $child
is_ "ends when the app does" "$?" "0"

# The race: oadx-run takes ID.run back in the instant the proot starts. The
# rename decides, and the proot side must start nothing when it loses.
req 200-1 'echo ran > /tmp/oadx-ran.2'
mv /tmp/.oadx-apps/200-1.run /tmp/.oadx-apps/200-1.self
req 200-1 'echo ran > /tmp/oadx-ran.2'
# (a fresh .run so the host-side check passes, then taken just before the proot runs)
cat > "$R/fakeproot2" <<'EOF'
#!/bin/sh
mv -f /tmp/.oadx-apps/200-1.run /tmp/.oadx-apps/200-1.taken
while [ $# -gt 0 ]; do case "$1" in -w) shift 2; break ;; *) shift ;; esac; done
exec "$@"
EOF
chmod 755 "$R/fakeproot2"
rm -f /tmp/oadx-ran.2
LINUX_PROOT=$R/fakeproot2 /system/bin/sh "$SCRIPT" 200-1 >/tmp/o 2>&1
rc=$?
is_ "taken back first: the proot side starts nothing (exit 75)" "$rc" "75"
if [ ! -e /tmp/oadx-ran.2 ]; then ok "taken back first: the app did not run on this side"; else bad "ran twice" "ran on the proot side too"; fi

rm -f /tmp/.oadx-apps/*
echo "  linux-app.sh: $pass passed, $fail failed"
[ $fail -eq 0 ]
