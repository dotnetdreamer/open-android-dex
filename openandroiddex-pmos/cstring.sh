#!/bin/sh
# cstring.sh NAME FILE: FILE as a C string constant called NAME, on stdout.
# build.sh makes here-js.h with it, so src/here.js stays a file of its own.
# Carriage returns go (a checkout with CRLF endings), backslashes and quotes
# are escaped, and each line keeps its newline.
set -eu
printf 'static const char %s[] =\n' "$1"
tr -d '\r' <"$2" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/^/    "/' -e 's/$/\\n"/'
printf ';\n'
