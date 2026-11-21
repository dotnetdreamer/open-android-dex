#!/bin/sh
# The app guest_check.sh starts through oadx-run: writes down exactly what it
# was started with, then "done". OADX_TEST_BY is set by fake_service.sh for
# the starts it makes, so an unset one means oadx-run ran it itself.
out=$PROBE_OUT
printf '%s\0' "$@" > "$out.args"
printf '%s' "${TESTVAR-}" > "$out.env"
printf '%s' "$PWD" > "$out.cwd"
umask > "$out.umask"
echo $$ > "$out.pid"
echo "${OADX_TEST_BY:-here}" > "$out.by"
[ -n "${PROBE_COUNT:-}" ] && echo "${PROBE_KEY} ${OADX_TEST_BY:-here}" >> "$PROBE_COUNT"
: > "$out.done"
