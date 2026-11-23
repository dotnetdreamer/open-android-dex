#!/bin/sh
# The guest half of "apps in an Android process of their own", run inside
# ubuntu:24.04 -- the guest's own dash, mawk and coreutils -- by
# test/linux-apps-test.mjs. It installs oadx-run and oadx-apps-menu from the
# repo exactly as the app does, and plays the app's side of the hand-over with
# fake_service.sh (the claim LinuxAppRunner makes, then linux-app.sh's inner
# claim, with no proot in between: the hand-over is renames in one folder).
#
# Prints "  ok   name" / "  FAIL name -- detail" lines and a summary; exits
# non-zero on any failure.

set -u
REPO=${REPO:-/work}
HERE=$REPO/test/linux-apps
DIR=/tmp/.oadx-apps
T=/tmp/oadx-test
pass=0
fail=0

ok() { pass=$((pass + 1)); echo "  ok   $1"; }
bad() { fail=$((fail + 1)); echo "  FAIL $1 -- $2"; }
same() { # name file-a file-b
  if cmp -s "$2" "$3"; then ok "$1"; else bad "$1" "$(od -c "$3" | head -5 | tr '\n' ' ') vs $(od -c "$2" | head -5 | tr '\n' ' ')"; fi
}
is() { # name got want
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "got [$2] want [$3]"; fi
}

install -m 755 "$REPO/openandroiddex-linux/guest/oadx-run" /usr/local/bin/oadx-run
install -m 755 "$REPO/openandroiddex-linux/guest/oadx-apps-menu" /usr/local/bin/oadx-apps-menu
install -m 755 "$HERE/probe.sh" /usr/local/bin/probe
rm -rf "$T" "$DIR"
mkdir -p "$T"

# Arguments no launcher should ever mangle, and an environment value with a
# newline, quotes and a dollar in it.
ARG1='plain'
ARG2='two words'
ARG3="it's \"quoted\" \$HOME \\back\\slash"
ARG4='line one
line two'
ARG5=''
ARG6='--flag=ünïcødé ✓'
TESTVAR="a 'b' \"c\" \$d
e"
export TESTVAR
printf '%s\0' "$ARG1" "$ARG2" "$ARG3" "$ARG4" "$ARG5" "$ARG6" > "$T/want.args"
printf '%s' "$TESTVAR" > "$T/want.env"
CWD="$T/a dir with 'quotes' and spaces"
mkdir -p "$CWD"
printf '%s' "$CWD" > "$T/want.cwd"

wait_for() { # file tenths
  n=0
  while [ ! -e "$1" ]; do
    n=$((n + 1))
    [ $n -gt "$2" ] && return 1
    sleep 0.1
  done
  return 0
}

start_service() { # mode
  rm -rf "$DIR"
  mkdir -p "$DIR"
  : > "$T/service.log"
  sh "$HERE/fake_service.sh" "$1" "$T/service.log" &
  SERVICE=$!
  wait_for "$DIR/ready" 50 || bad "fake service ($1) said ready" "no ready file"
}

stop_service() {
  kill "$SERVICE" 2>/dev/null
  wait "$SERVICE" 2>/dev/null
  rm -f "$DIR/ready"
}

launch() { # out [env...] -- runs oadx-run probe ARG... from $CWD, times it
  out=$1
  shift
  rm -f "$out" "$out".*
  t0=$(date +%s%N)
  (cd "$CWD" && umask 027 && env "$@" PROBE_OUT="$out" oadx-run probe "$ARG1" "$ARG2" "$ARG3" "$ARG4" "$ARG5" "$ARG6")
  rc=$?
  t1=$(date +%s%N)
  ELAPSED_MS=$(( (t1 - t0) / 1000000 ))
  return $rc
}

check_probe() { # label out
  if wait_for "$2.done" 60; then
    same "$1: argv byte for byte" "$T/want.args" "$2.args"
    same "$1: environment value with a newline, quotes and a dollar" "$T/want.env" "$2.env"
    same "$1: working folder with spaces and quotes" "$T/want.cwd" "$2.cwd"
    is "$1: umask" "$(cat "$2.umask")" "0027"
  else
    bad "$1: the app ran" "no $2.done within 6 s"
  fi
}

echo "oadx-run, nothing to hand over to"
rm -rf "$DIR"
launch "$T/p0" && check_probe "no requests folder: runs here" "$T/p0"
mkdir -p "$DIR"
launch "$T/p1" && check_probe "folder but no ready: runs here" "$T/p1"
echo 999999 > "$DIR/ready"
launch "$T/p2" && check_probe "ready names a dead pid: runs here" "$T/p2"
is "ready names a dead pid: no request left behind" "$(ls -A "$DIR" | grep -v '^ready$' | wc -l)" "0"
rm -f "$DIR/ready"
# Runs HERE means in the very process: exec, no child left waiting.
sh -c 'echo $$ > /tmp/oadx-test/p3.parent; exec env PROBE_OUT=/tmp/oadx-test/p3 oadx-run probe x'
wait_for "$T/p3.done" 30
is "runs here by exec, as the same process" "$(cat "$T/p3.pid" 2>/dev/null)" "$(cat "$T/p3.parent")"
oadx-run >/dev/null 2>&1
is "no command: usage, exit 2" "$?" "2"

echo "oadx-run, handed over"
start_service claim
launch "$T/h1" DISPLAY=:1
rc=$?
is "returns 0 once the app is started" "$rc" "0"
if [ "$ELAPSED_MS" -lt 3000 ]; then ok "returns within 3 s (${ELAPSED_MS} ms)"; else bad "returns within 3 s" "${ELAPSED_MS} ms"; fi
check_probe "handed over" "$T/h1"
is "handed over: ran in the service's child, not here" "$(cat "$T/h1.by")" "service"
id=$(sed -n 's/^claimed //p' "$T/service.log" | head -1)
if printf %s "$id" | grep -Eqx '[0-9]{1,32}-[0-9]{1,10}'; then ok "request id is what LinuxAppRunner.ID accepts ($id)"; else bad "request id format" "[$id]"; fi
hdr=$(sed -n 's/^header //p' "$T/service.log" | head -1)
if printf %s "$hdr" | grep -Eqx '# oadx-run 1 display=[A-Za-z0-9:._-]* name=[A-Za-z0-9:._+-]*'; then
  ok "header is what LinuxAppRunner.HEADER accepts"
else
  bad "header format" "[$hdr]"
fi
is "header carries the display and the name" "$hdr" "# oadx-run 1 display=:1 name=probe"
sleep 0.3
is "nothing left in the folder but ready" "$(ls -A "$DIR" | grep -v '^ready$' | tr '\n' ' ')" ""
# An odd display and a binary path with a space: still one header line, still accepted.
mkdir -p "$T/my bin"
cp /usr/local/bin/probe "$T/my bin/pro be"
(cd "$CWD" && env DISPLAY='bad display;$(x)' PROBE_OUT="$T/h2" oadx-run "$T/my bin/pro be" a)
wait_for "$T/h2.done" 60
hdr=$(sed -n 's/^header //p' "$T/service.log" | sed -n 2p)
is "odd display and a spaced name are made safe" "$hdr" "# oadx-run 1 display=bad_display___x_ name=pro_be"
stop_service

echo "oadx-run, nothing claims it"
start_service noclaim
launch "$T/n1" OADX_RUN_WAIT=10
is "not claimed: falls back, exit 0" "$?" "0"
check_probe "not claimed: runs here after the wait" "$T/n1"
is "not claimed: ran here, not in the service" "$(cat "$T/n1.by")" "here"
if [ "$ELAPSED_MS" -ge 900 ]; then ok "not claimed: waited its time first (${ELAPSED_MS} ms)"; else bad "waited first" "${ELAPSED_MS} ms"; fi
is "not claimed: nothing left behind" "$(ls -A "$DIR" | grep -v '^ready$' | tr '\n' ' ')" ""
stop_service

echo "oadx-run, claimed but the proot never comes"
start_service claimonly
launch "$T/c1" OADX_RUN_WAIT=10
check_probe "claimed, never started: oadx-run takes it back and runs it" "$T/c1"
is "claimed, never started: ran here" "$(cat "$T/c1.by")" "here"
is "claimed, never started: nothing left behind" "$(ls -A "$DIR" | grep -v '^ready$' | tr '\n' ' ')" ""
stop_service

echo "exactly once, racing the timeout"
# The service claims and starts each request at a random moment around
# oadx-run's deadline (1 s here). Every launch must run exactly once,
# whichever side wins each rename.
for mode in latestart latereq; do
  start_service "$mode"
  : > "$T/count"
  i=0
  while [ $i -lt 40 ]; do
    i=$((i + 1))
    (cd "$CWD" && env OADX_RUN_WAIT=10 PROBE_OUT="$T/r$i" PROBE_COUNT="$T/count" PROBE_KEY="$i" \
      oadx-run probe x >/dev/null 2>&1) &
  done
  wait_all=0
  while [ "$(wc -l < "$T/count")" -lt 40 ] && [ $wait_all -lt 80 ]; do
    wait_all=$((wait_all + 1))
    sleep 0.1
  done
  sleep 2.5 # anything that would run twice has had its chance
  runs=$(wc -l < "$T/count")
  dups=$(sort "$T/count" | uniq -d | wc -l)
  here=$(grep -c ' here$' "$T/count")
  is "$mode: 40 launches, 40 runs" "$runs" "40"
  is "$mode: none ran twice" "$dups" "0"
  echo "        ($mode: $here ran here after the deadline, $((runs - here)) in the service)"
  is "$mode: nothing left in the folder" "$(ls -A "$DIR" | grep -v '^ready$' | tr '\n' ' ')" ""
  stop_service
  wait 2>/dev/null
done

echo "oadx-apps-menu"
sh "$HERE/menu_check.sh"
mrc=$?
[ $mrc -eq 0 ] || fail=$((fail + 1))

echo
echo "guest: $pass passed, $fail failed"
[ $fail -eq 0 ]
