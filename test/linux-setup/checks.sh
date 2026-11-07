#!/system/bin/sh
# linux-setup.sh's helpers on a phone, under its own /system/bin/sh — mksh, the
# shell LinuxService runs the script with. The functions are cut out of the real
# script by name, never copied, and run against scratch files in this
# directory; guest commands go to stand-ins, so no rootfs is needed and nothing
# of the app is touched. Run by test/linux-setup-test.mjs.
#
#   sh checks.sh <dir holding linux-setup.sh and this>

T=${1:-/data/local/tmp/oadx-setup-test}
S=$T/linux-setup.sh
cd "$T" || exit 1
passed=0 failed=0
check() { # name got want
  if [ "$2" = "$3" ]; then
    passed=$((passed + 1)); echo "PASS $1"
  else
    failed=$((failed + 1)); echo "FAIL $1  (got [$2], want [$3])"
  fi
}
lines() { wc -l | tr -d ' '; }

if /system/bin/sh -n "$S"; then check "the whole script parses" ok ok; else check "the whole script parses" no ok; fi

FUNCS="state ticker_stop step_begin step_end step_progress fsize apt_in fetch_tarball extract_tarball
  drop_half_tree dpkg_dirty repair_if_dirty redo_clean drop_unwanted guest_or_note vscode_settled
  pkg_ok novnc_files"
: > funcs.sh
for f in $FUNCS; do
  check "linux-setup.sh still has $f()" "$(grep -c "^$f() {" "$S")" 1
  sed -n "/^$f() {/,/^}/p" "$S" >> funcs.sh
done
grep '^wants() {' "$S" >> funcs.sh
. ./funcs.sh
note() { echo "note: $1" >> "$T/notes"; }

ROOT=$T/root
rm -rf "$ROOT"; mkdir -p "$ROOT/tmp"; cd "$ROOT" || exit 1
VER=1 FEAT=18 STEP= TICKER= REDO=
STEP_STATUS=$ROOT/tmp/.oadx-apt-status

# -- sizes and percentages ------------------------------------------------
printf 'hello' > f5
check "fsize: five bytes" "$(fsize f5)" 5
check "fsize: no file is 0" "$(fsize nope)" 0
truncate -s 1100000000 huge
check "fsize: 1.1 GB, read as text" "$(fsize huge)" 1100000000
truncate -s 125829120 big
_sb_word=downloading:vscode _sb_file=$ROOT/big _sb_size=346030080
step_progress
check "a tarball's percent: 120 MB of 330 MB, no 32-bit overflow" "$_sp_msg|$_sp_pct" "downloading:vscode|36"
_sb_file=$ROOT/huge _sb_size=1150000000
step_progress
check "a tarball's percent: 1.1 GB of 1.15 GB" "$_sp_pct" 95
_sb_size=
step_progress
check "no size from the server, no percent" "$_sp_pct" -1

# -- apt's own progress (APT::Status-Fd) -----------------------------------
_sb_file= _sb_size= _sb_word=preparing:firefox
rm -f "$STEP_STATUS"; step_progress
check "apt: nothing yet reads as the step's own word" "$_sp_msg|$_sp_pct" "preparing:firefox|-1"
printf 'dlstatus:3:45.1234:Retrieving file 3 of 12 (2min 3s remaining)\n' > "$STEP_STATUS"
step_progress
check "apt: downloading" "$_sp_msg|$_sp_pct" "downloading:firefox|45"
printf 'dlstatus:1:0.0000:Retrieving file 1 of 2\npmstatus:libc6:arm64:20.0000:Unpacking libc6 (arm64)\n' > "$STEP_STATUS"
step_progress
check "apt: a package with :arch, read from the end" "$_sp_msg|$_sp_pct" "installing:libc6|20"
printf 'pmstatus:dpkg-exec:0.0000:Running dpkg\n' > "$STEP_STATUS"
step_progress
check "apt: dpkg-exec named as the step" "$_sp_msg|$_sp_pct" "installing:firefox|0"
printf 'pmstatus:curl:100.0000:Installed curl (arm64)\n' > "$STEP_STATUS"
step_progress
check "apt: a finished call says nothing about what comes next" "$_sp_msg|$_sp_pct" "preparing:firefox|-1"
printf 'garbage\n' > "$STEP_STATUS"
step_progress
check "apt: a line it cannot read" "$_sp_msg|$_sp_pct" "preparing:firefox|-1"
check "apt_in" "$(apt_in curl ca-certificates)" \
  "apt-get -o APT::Status-Fd=3 install -y --no-install-recommends curl ca-certificates 3>/tmp/.oadx-apt-status"

# -- state.env and the ticker ----------------------------------------------
STEP=chromium; state installing-desktop 94 preparing:chromium
check "state.env carries STEP and STEPPCT" "$(tr '\n' ' ' < state.env)" \
  "VERSION=1 FEATURES=18 PHASE=installing-desktop PCT=94 MSG=preparing:chromium STEP=chromium STEPPCT=-1 "
STEP=
step_begin chromium 94 preparing:chromium
printf 'dlstatus:2:10.5000:Retrieving file 2 of 5\n' > "$STEP_STATUS"
sleep 6
check "the ticker re-publishes within 5 s" "$(grep -E '^(MSG|STEP|STEPPCT)=' state.env | tr '\n' ' ')" \
  "MSG=downloading:chromium STEP=chromium STEPPCT=10 "
t=$TICKER
step_end
kill -0 "$t" 2>/dev/null && alive=yes || alive=no
check "step_end leaves no ticker" "$alive" no
check "step_end clears STEP" "[$STEP]" "[]"
check "no scratch state file left behind" "$(ls state.env.tmp* 2>/dev/null | lines)" 0

# -- dpkg left half way ------------------------------------------------------
mkdir -p rootfs/var/lib/dpkg/updates
printf 'Package: a\nStatus: install ok installed\n' > rootfs/var/lib/dpkg/status
dpkg_dirty && d=yes || d=no; check "dpkg clean" "$d" no
: > rootfs/var/lib/dpkg/updates/0001
dpkg_dirty && d=yes || d=no; check "dpkg: a journal entry is dirty" "$d" yes
rm -f rootfs/var/lib/dpkg/updates/0001
printf 'Package: perl-base\nStatus: install reinstreq half-installed\n' >> rootfs/var/lib/dpkg/status
dpkg_dirty && d=yes || d=no; check "dpkg: half-installed is dirty" "$d" yes
printf 'Package: a\nStatus: install ok unpacked\n' > rootfs/var/lib/dpkg/status
dpkg_dirty && d=yes || d=no; check "dpkg: unpacked is dirty" "$d" yes

# -- one package, asked of the status file (no apt) --------------------------
printf 'Package: python3-minimal\nStatus: install ok installed\n\nPackage: python3\nStatus: install ok half-configured\n\nPackage: sudo\nStatus: install ok installed\n' \
  > rootfs/var/lib/dpkg/status
pkg_ok python3 && d=yes || d=no; check "pkg_ok: half-configured is not installed" "$d" no
pkg_ok sudo && d=yes || d=no; check "pkg_ok: installed, in the last stanza" "$d" yes
pkg_ok python && d=yes || d=no; check "pkg_ok: a name another one only begins with" "$d" no
printf 'Package: python3\nStatus: install ok installed\nPriority: important\n\nPackage: python3-minimal\nStatus: install ok half-installed\n' \
  > rootfs/var/lib/dpkg/status
pkg_ok python3 && d=yes || d=no; check "pkg_ok: installed, beside a half-installed neighbour" "$d" yes
pkg_ok git && d=yes || d=no; check "pkg_ok: absent" "$d" no
printf 'Package: a\nStatus: install ok unpacked\n' > rootfs/var/lib/dpkg/status

# -- noVNC and websockify as files, and NOVNC_SYNC ---------------------------
# The stand-in guest logs which of the two commands it was handed, and a
# download places the files and the record the way the real one does. What it
# cannot show is the real command's order -- nothing in place removed before
# the new trees are unpacked and checked -- which is on device.
run_guest() {
  case "$1" in
    "apt-get update") echo update >> novnc.calls ;;
    *"apt-get download"*)
      echo download >> novnc.calls
      [ "$NOVNC_FAIL" = 1 ] && return 1
      mkdir -p rootfs/usr/bin rootfs/usr/share/novnc/core rootfs/usr/local/share/openandroiddex
      : > rootfs/usr/share/novnc/core/rfb.js; : > rootfs/usr/bin/websockify; chmod 755 rootfs/usr/bin/websockify
      echo "sync=$NOVNC_SYNC" > rootfs/usr/local/share/openandroiddex/novnc-files ;;
  esac
  return 0
}
novnc_try() {
  rm -f novnc.calls
  novnc_files && _r=ok || _r=failed
  echo "$_r|$(cat novnc.calls 2>/dev/null | tr '\n' ' ')"
}
NOVNC_SYNC=2 NOVNC_FAIL=
rm -rf rootfs/usr/share/novnc rootfs/usr/bin/websockify rootfs/usr/local/share/openandroiddex
printf 'Package: novnc\nStatus: install ok installed\n\nPackage: websockify\nStatus: install ok installed\n' \
  > rootfs/var/lib/dpkg/status
check "novnc: a guest with the packages is left to apt" "$(novnc_try)" "ok|"
: > rootfs/var/lib/dpkg/status
check "novnc: a fresh desktop takes the files, with no second update" "$(novnc_try)" "ok|download "
check "novnc: at the same sync level, nothing runs" "$(novnc_try)" "ok|"
NOVNC_SYNC=3
check "novnc: a sync bump refreshes the lists, then the files" "$(novnc_try)" "ok|update download "
NOVNC_SYNC=4 NOVNC_FAIL=1
check "novnc: a refresh that cannot fetch is a failure" "$(novnc_try)" "failed|update download "
NOVNC_FAIL=
rm -rf rootfs/usr/share/novnc rootfs/usr/bin/websockify rootfs/usr/local/share/openandroiddex novnc.calls
printf 'Package: a\nStatus: install ok unpacked\n' > rootfs/var/lib/dpkg/status

STUB=$ROOT/stub; mkdir -p "$STUB"
cat > "$STUB/dpkg-query" <<'EOF'
#!/system/bin/sh
for a; do p=$a; done
case "$p" in
  firefox) printf 'install reinstreq half-installed' ;;
  chromium) printf 'install ok installed' ;;
  gimp) printf 'install ok unpacked' ;;
  *) echo "dpkg-query: no packages found matching $p" >&2; exit 1 ;;
esac
EOF
printf '#!/system/bin/sh\necho "dpkg $*" >> %s/dpkg.calls\n' "$ROOT" > "$STUB/dpkg"
chmod 755 "$STUB/dpkg-query" "$STUB/dpkg"
run_guest() { PATH=$STUB:$PATH /system/bin/sh -c "$1"; }
repair_dpkg() { echo repair >> "$ROOT/dpkg.calls"; }
rm -f dpkg.calls; : > rootfs/var/lib/dpkg/updates/0002
APPS_SEL="code"; repair_if_dirty
check "repair: skipped apps half unpacked are removed, an installed one kept" \
  "$(tr '\n' '|' < dpkg.calls)" \
  "dpkg --remove --force-remove-reinstreq firefox|dpkg --remove --force-remove-reinstreq gimp|repair|"
rm -f dpkg.calls
APPS_SEL="firefox chromium gimp nodejs git"; repair_if_dirty
check "repair: a wanted app half unpacked is finished, not removed" "$(tr '\n' '|' < dpkg.calls)" "repair|"
rm -f dpkg.calls rootfs/var/lib/dpkg/updates/0002
printf 'Package: a\nStatus: install ok installed\n' > rootfs/var/lib/dpkg/status
repair_if_dirty
check "repair: nothing to repair, nothing run" "$(cat dpkg.calls 2>/dev/null)" ""

# -- Restart's clean-up, and what an unwanted app left -----------------------
mkdir -p rootfs/var/cache/apt/archives/partial rootfs/opt/.vscode-new
: > tmp/vscode.tar.gz; : > tmp/vscode.tar.gz.url; : > tmp/vscode.tar.gz.size; : > .vscode-attempts
: > rootfs/var/cache/apt/archives/partial/x.deb
REDO=chromium; redo_clean code
check "redo: another step's restart leaves VS Code's download" "$(ls tmp/vscode.tar.gz 2>/dev/null)" tmp/vscode.tar.gz
REDO=code; redo_clean code
check "redo: VS Code's download, record, staging and attempts all go" \
  "$(ls -d tmp/vscode.tar.gz* .vscode-attempts rootfs/opt/.vscode-new rootfs/var/cache/apt/archives/partial/x.deb 2>/dev/null | lines)" 0
check "redo: a request applies once" "[$REDO]" "[]"

APPS_SEL="firefox code"
mkdir -p rootfs/etc/apt/sources.list.d rootfs/etc/apt/keyrings rootfs/etc/apt/preferences.d rootfs/usr/bin rootfs/opt/.intellij-new
: > rootfs/etc/apt/sources.list.d/xtradeb.sources; : > rootfs/etc/apt/keyrings/xtradeb.asc
: > rootfs/var/cache/apt/archives/chromium_156.0_arm64.deb; : > rootfs/etc/apt/sources.list.d/mozilla.sources
: > tmp/intellij.tar.gz; : > tmp/intellij.tar.gz.size; : > tmp/vscode.tar.gz
drop_unwanted
check "drop: an unwanted, absent Chromium's repo, key and .deb" \
  "$(ls rootfs/etc/apt/sources.list.d/xtradeb.sources rootfs/etc/apt/keyrings/xtradeb.asc rootfs/var/cache/apt/archives/chromium_156.0_arm64.deb 2>/dev/null | lines)" 0
check "drop: a wanted Firefox keeps its repo" "$(ls rootfs/etc/apt/sources.list.d/mozilla.sources 2>/dev/null | lines)" 1
check "drop: an unwanted IntelliJ's download and staging" "$(ls -d tmp/intellij.tar.gz* rootfs/opt/.intellij-new 2>/dev/null | lines)" 0
check "drop: a wanted VS Code keeps its download" "$(ls tmp/vscode.tar.gz 2>/dev/null | lines)" 1
APPS_SEL="firefox"
: > rootfs/etc/apt/sources.list.d/xtradeb.sources; : > rootfs/usr/bin/chromium; chmod 755 rootfs/usr/bin/chromium
drop_unwanted
check "drop: an installed Chromium keeps its repo, wanted or not" "$(ls rootfs/etc/apt/sources.list.d/xtradeb.sources 2>/dev/null | lines)" 1

# -- a half-extracted IDE is never taken for an install ----------------------
rm -f tmp/vscode.tar.gz*; rm -rf rootfs/opt/vscode rootfs/opt/intellij
mkdir -p rootfs/opt/vscode/bin; : > rootfs/opt/vscode/bin/code; chmod 755 rootfs/opt/vscode/bin/code
: > tmp/vscode.tar.gz
REDO=code
drop_half_tree vscode vscode.tar.gz
redo_clean code
vscode_settled && s=installed || s=to-install
check "a half VS Code tree with its tarball beside it is not an install, Restart or not" "$s" to-install
check "...and is gone" "$(ls -d rootfs/opt/vscode 2>/dev/null | lines)" 0
mkdir -p rootfs/opt/vscode/bin; : > rootfs/opt/vscode/bin/code; chmod 755 rootfs/opt/vscode/bin/code
drop_half_tree vscode vscode.tar.gz
vscode_settled && s=installed || s=to-install
check "a whole VS Code tree, its tarball gone, is an install" "$s" installed
mkdir -p rootfs/opt/intellij/bin; : > rootfs/opt/intellij/bin/idea.sh; chmod 755 rootfs/opt/intellij/bin/idea.sh
: > tmp/intellij.tar.gz
drop_half_tree intellij intellij.tar.gz
[ -x rootfs/opt/intellij/bin/idea.sh ] && s=installed || s=to-install
check "a half IntelliJ tree with its tarball beside it is not an install" "$s" to-install
rm -f tmp/intellij.tar.gz

# extract_tarball's own guest command, run with /opt and /tmp mapped to here.
# Only a path after a space is the guest's: this directory's own path has a
# /tmp/ in it too (/data/local/tmp/...), which must stay as it is.
G=$ROOT/guest; mkdir -p "$G/opt" "$G/tmp"
run_guest() { /system/bin/sh -c "$(printf '%s' "$1" | sed "s# /opt/# $G/opt/#g; s# /tmp/# $G/tmp/#g")"; }
mkdir -p mk/VSCode-linux-arm64/bin
echo new > mk/VSCode-linux-arm64/bin/code; chmod 755 mk/VSCode-linux-arm64/bin/code
echo x > mk/VSCode-linux-arm64/resources.pak
(cd mk && tar -czf "$G/tmp/vscode.tar.gz" VSCode-linux-arm64)
: > "$G/tmp/vscode.tar.gz.url"; : > "$G/tmp/vscode.tar.gz.size"
extract_tarball vscode vscode.tar.gz vscode && rc=0 || rc=1
check "extract: a good tarball lands whole in place" "$rc|$(cat "$G/opt/vscode/bin/code" 2>/dev/null)|$(ls "$G/opt/vscode" | lines)" "0|new|2"
check "extract: no staging, tarball or records left" "$(ls -d "$G/opt/.vscode-new" "$G"/tmp/vscode.tar.gz* 2>/dev/null | lines)" 0
printf 'not a tarball' > "$G/tmp/vscode.tar.gz"
extract_tarball vscode vscode.tar.gz vscode && rc=0 || rc=1
check "extract: a bad tarball fails and leaves the tree that was there" "$rc|$(cat "$G/opt/vscode/bin/code" 2>/dev/null)" "1|new"
check "extract: the bad tarball and the staging are gone" "$(ls -d "$G/opt/.vscode-new" "$G/tmp/vscode.tar.gz" 2>/dev/null | lines)" 0

# -- a download is kept unless the server will not resume it -----------------
# curl as fetch_tarball calls it, doing what the case asks:
#   the probe (-fsIL)  prints $HEAD and succeeds, or fails when HEAD is empty
#   -C - (resume)      exits $RESUME_RC with http=$RESUME_HTTP; on 0 fills to $SIZE
#   from zero          fills to $SIZE
DST=$ROOT/tmp/vscode.tar.gz
fake_curl() {
  _u=$(printf '%s' "${1##* }" | tr -d "'")
  case "$1" in
    *-fsIL*) [ -n "$HEAD" ] || return 6; printf '%s' "$HEAD"; return 0 ;;
    *"-C -"*)
      echo "resume $_u at $(fsize "$DST")" >> "$ROOT/calls"
      [ "$RESUME_RC" = 0 ] && truncate -s "$SIZE" "$DST"
      echo "http=$RESUME_HTTP"; return "$RESUME_RC" ;;
    *) echo "plain $_u at $(fsize "$DST")" >> "$ROOT/calls"; truncate -s "$SIZE" "$DST"; return 0 ;;
  esac
}
run_guest() { fake_curl "$1"; }
step_begin() { STEP=$1; }
fetch() { rm -f "$ROOT/calls"; fetch_tarball code 98 vscode https://latest vscode.tar.gz && echo ok || echo failed; }
calls() { tr '\n' '|' 2>/dev/null < "$ROOT/calls"; }
U1=https://cdn/v1.tar.gz U2=https://cdn/v2.tar.gz

rm -f "$DST"*; truncate -s 524288000 "$DST"; echo "$U1" > "$DST.url"
HEAD="$U1 600000000" RESUME_RC=28 RESUME_HTTP=000 SIZE=600000000
r=$(fetch)
check "fetch: a timeout keeps all of a 500 MB partial for the next attempt" \
  "$r|$(fsize "$DST")|$(calls)" "failed|524288000|resume $U1 at 524288000|"
rm -f "$DST"*; truncate -s 700 "$DST"; echo "$U1" > "$DST.url"
HEAD="$U1 600" RESUME_RC=22 RESUME_HTTP=416 SIZE=600
r=$(fetch)
check "fetch: a range past the end (416) starts that file over" \
  "$r|$(fsize "$DST")|$(calls)" "ok|600|resume $U1 at 700|plain $U1 at 0|"
rm -f "$DST"*; truncate -s 300 "$DST"; echo "$U1" > "$DST.url"
HEAD="$U1 600" RESUME_RC=33 RESUME_HTTP=200 SIZE=600
r=$(fetch)
check "fetch: a server with no ranges (33) starts that file over" \
  "$r|$(fsize "$DST")|$(calls)" "ok|600|resume $U1 at 300|plain $U1 at 0|"
rm -f "$DST"*; truncate -s 300 "$DST"; echo "$U1" > "$DST.url"; echo 600 > "$DST.size"
HEAD= RESUME_RC=0 RESUME_HTTP=206 SIZE=600
r=$(fetch)
check "fetch: a failed probe resumes the recorded build" "$r|$(calls)" "ok|resume $U1 at 300|"
rm -f "$DST"*; truncate -s 300 "$DST"; echo "$U1" > "$DST.url"
HEAD="$U2 600" RESUME_RC=0 RESUME_HTTP=206 SIZE=600
r=$(fetch)
check "fetch: a new build starts clean and is recorded" "$r|$(calls)|$(cat "$DST.url")" "ok|resume $U2 at 0||$U2"
rm -f "$DST"*; truncate -s 300 "$DST"
HEAD= RESUME_RC=0 RESUME_HTTP=200 SIZE=600
r=$(fetch)
check "fetch: no build known, from the start, nothing recorded" \
  "$r|$(calls)|$(ls "$DST.url" 2>/dev/null | lines)" "ok|resume https://latest at 0||0"
rm -f "$DST"*; truncate -s 600 "$DST"; echo "$U1" > "$DST.url"
HEAD="$U1 600" RESUME_RC=28 SIZE=600
r=$(fetch)
check "fetch: already whole, nothing fetched" "$r|$(calls)" "ok|"

# -- one run is one process group (the real re-exec block) ------------------
cd "$T" || exit 1
{
  sed -n '/^if \[ -z "\$LINUX_SETUP_SETSID" \]/,/^fi/p' "$S"
  echo 'echo $$ > '"$T"'/t2.pid'
  echo '( while sleep 1; do :; done ) &'
  echo 'sleep 300'
} > t2.sh
check "the re-exec block is in the script" "$(grep -c LINUX_SETUP_SETSID t2.sh)" 3
/system/bin/sh t2.sh & bg=$!
sleep 1
me=$(cat t2.pid 2>/dev/null)
check "setsid execs in place: the pid the app waits on is the script's" "$me" "$bg"
check "...and leads its own process group" "$(ps -o PGID= -p "$me" 2>/dev/null | tr -d ' ')" "$me"
before=$(ps -A -o PGID=,PID= | while read g p; do [ "$g" = "$me" ] && echo "$p"; done | lines)
check "the group holds the script and what it started" "$([ "$before" -ge 3 ] && echo yes || echo "no ($before)")" yes
/system/bin/kill -9 -"$me"
wait "$bg" 2>/dev/null
sleep 1
after=$(ps -A -o PGID=,PID= | while read g p; do [ "$g" = "$me" ] && echo "$p"; done | lines)
check "one kill -9 -<pid> takes the whole group" "$after" 0

# -- a step's end must not stop the run: the real traps, under setsid --------
# A ticker stopped with a catchable signal made mksh run the SCRIPT's own TERM
# trap five seconds later — "FAILED: interrupted" in every run's first step —
# which nothing above could see, since these checks run without the script's
# traps. So this builds a script from the real pieces (the setsid re-exec,
# fail(), cleanup(), both trap lines), begins a step, ends it, and must carry
# on past those five seconds.
{
  sed -n '/^if \[ -z "\$LINUX_SETUP_SETSID" \]/,/^fi/p' "$S"
  echo 'ROOT=$1; VER=1; FEAT=18; STEP=; TICKER=; STEP_STATUS=$ROOT/tmp/.oadx-apt-status'
  echo 'note() { echo "note: $1"; }; share_log() { :; }'
  cat "$T/funcs.sh"
  sed -n '/^fail() {/,/^}/p; /^cleanup() {/,/^}/p' "$S"
  grep -E "^trap (cleanup EXIT|'STEP=; fail interrupted')" "$S"
  echo 'step_begin git 92 preparing:git'
  echo 'sleep 1; t0=$(date +%s); step_end; echo "step_end took $(( $(date +%s) - t0 )) s"'
  echo 'sleep 6; echo "carried on"'
} > "$T/shape.sh"
check "the real-shape script has both of the script's traps" "$(grep -c '^trap ' "$T/shape.sh")" 2
mkdir -p "$T/shape-root/tmp"
/system/bin/sh "$T/shape.sh" "$T/shape-root" > "$T/shape.out" 2>&1
check "a step's end does not stop the run: no 'interrupted', and it carries on" \
  "$(grep -c interrupted "$T/shape.out")|$(grep -c 'carried on' "$T/shape.out")" "0|1"
took=$(sed -n 's/^step_end took \([0-9]*\) s$/\1/p' "$T/shape.out")
check "...and the ticker is gone at once, not after its sleep" \
  "$([ "${took:-9}" -le 1 ] && echo yes || echo "no (${took:-?} s)")" yes

echo "checks: $passed passed, $failed failed"
