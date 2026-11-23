#!/bin/sh
# The app's side of the hand-over, for guest_check.sh: what LinuxAppsService
# does with a request (LinuxAppRunner.offer: rename ID.req to ID.run), then
# what linux-app.sh runs inside its proot (rename ID.run to ID.go, run it).
# No proot and no Android: the hand-over is renames in one folder, and these
# are the same renames.
#
#   fake_service.sh MODE LOG
#     claim      claim and start at once (the normal case)
#     noclaim    say ready, never claim (a service that is stuck)
#     claimonly  claim, never start (a proot that never comes up)
#     latestart  claim at once, start 0.7-1.3 s later: around oadx-run's deadline
#     latereq    claim 0.7-1.3 s after the request appears, then start
mode=$1
log=$2
DIR=/tmp/.oadx-apps

echo $$ > "$DIR/.ready.tmp" && mv "$DIR/.ready.tmp" "$DIR/ready"

start() { # id
  OADX_TEST_BY=service sh -c 'mv -f -- "$1" "$2" 2>/dev/null || exit 75; exec /bin/sh "$2"' \
    oadx-app "$DIR/$1.run" "$DIR/$1.go" </dev/null >/dev/null 2>&1
}

claim() { # id -- 0 when this side won the rename
  if mv -f "$DIR/$1.req" "$DIR/$1.run" 2>/dev/null; then
    echo "claimed $1" >> "$log"
    echo "header $(sed -n 2p "$DIR/$1.run")" >> "$log"
    return 0
  fi
  return 1
}

later() { # a random 0.7 to 1.3 seconds
  r=$(od -An -N2 -tu2 /dev/urandom | tr -d ' ')
  t=$((7 + r % 7))
  sleep "$((t / 10)).$((t % 10))"
}

seen=" "
while :; do
  for f in "$DIR"/*.req; do
    [ -e "$f" ] || continue
    id=${f##*/}
    id=${id%.req}
    case "$seen" in *" $id "*) continue ;; esac
    seen="$seen$id "
    case $mode in
      noclaim) ;;
      claim) claim "$id" && start "$id" & ;;
      claimonly) claim "$id" ;;
      latestart) claim "$id" && { later; start "$id"; } & ;;
      latereq) { later; claim "$id" && start "$id"; } & ;;
    esac
  done
  sleep 0.02
done
