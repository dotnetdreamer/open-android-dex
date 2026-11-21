#!/bin/sh
# oadx-apps-menu against desktop files made to cover every rule it has, run
# by guest_check.sh in ubuntu:24.04. Each case is a file and the copy (or no
# copy) it must give, compared byte for byte.
set -u
pass=0
fail=0
ok() { pass=$((pass + 1)); echo "  ok   $1"; }
bad() { fail=$((fail + 1)); echo "  FAIL $1 -- $2"; }
OUT=/usr/local/share/oadx-apps/applications
U=/usr/share/applications
L=/usr/local/share/applications
W=/tmp/oadx-menu-want

copy_is() { # name file -- the routed copy must equal the text on stdin
  cat > "$W"
  if [ ! -f "$OUT/$2" ]; then
    bad "$1" "no copy $OUT/$2"
  elif cmp -s "$W" "$OUT/$2"; then
    ok "$1"
  else
    bad "$1" "$(diff "$W" "$OUT/$2" | head -8 | tr '\n' '|')"
  fi
}
no_copy() { # name file
  if [ -e "$OUT/$1" ]; then bad "$2" "a copy was written"; else ok "$2"; fi
}
mtime() { stat -c %Y.%y "$1" 2>/dev/null; }

rm -rf "$U" "$L" "$OUT" /root/.config/xfce4/panel /root/Desktop
mkdir -p "$U" "$L" /root/.config/xfce4/panel/launcher-3 /root/Desktop

cat > "$U/app.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=App
Name[de]=Anwendung
# a comment stays
Exec=app %U
TryExec=app
Icon=app
Actions=new;

[Desktop Action new]
Name=New window
Exec=app --new-window %U

[X-Other Group]
Exec=not-a-launch
EOF
printf '[Desktop Entry]\nType=Application\nName=T\nExec=htop\nTerminal=true\n' > "$U/term.desktop"
printf '[Desktop Entry]\nType=Link\nName=L\nURL=https://example.org\n' > "$U/link.desktop"
printf '[Desktop Entry]\nType=Application\nName=H\nExec=h\nHidden=true\n' > "$U/hidden.desktop"
printf '[Desktop Entry]\nType=Application\nName=N\nExec=handler %%f\nNoDisplay=true\nMimeType=text/x-n;\n' > "$U/nodisplay.desktop"
printf '[Desktop Entry]\nType=Application\nName=D\nExec=dbus-app --gapplication-service\nDBusActivatable=true\n' > "$U/dbus.desktop"
printf '[Desktop Entry]\nType=Application\nName=DO\nDBusActivatable=true\n' > "$U/dbusonly.desktop"
printf '[Desktop Entry]\nType=Application\nName=O\nExec=o\nX-OADX-Route=false\n' > "$U/optout.desktop"
printf '[Desktop Entry]\nType=Application\nName=A\nExec=oadx-run already %%U\n' > "$U/already.desktop"
printf '[Desktop Entry]\nType=Application\nName=A2\nExec=/usr/local/bin/oadx-run abs\n' > "$U/already2.desktop"
printf '[Desktop Entry]\nType=Application\nName=S\nExec = spaced  --two  spaces\n' > "$U/spaced.desktop"
printf '[Desktop Entry]\r\nType=Application\r\nName=C\r\nExec=crlf %%u\r\n' > "$U/crlf.desktop"
printf '[Desktop Entry]\nType=Application\nName=Usr\nExec=usr-dup\n' > "$U/dup.desktop"
printf '[Desktop Entry]\nType=Application\nName=Local\nExec=local-dup\n' > "$L/dup.desktop"
printf '[Desktop Entry]\nType=Application\nName=Usr2\nExec=usr-dup2\n' > "$U/dup2.desktop"
printf '[Desktop Entry]\nType=Application\nName=Local2\nExec=local-dup2\nTerminal=true\n' > "$L/dup2.desktop"
printf 'just some text\nExec=nothing\n' > "$U/garbage.desktop"
: > "$U/empty.desktop"
printf '[Desktop Entry]\nType=Application\nName=Gone\nExec=gone\n' > "$U/gone.desktop"
printf '[Desktop Entry]\nVersion=1.0\nType=Application\nName=Firefox\nIcon=firefox\nExec=/usr/local/bin/dex-firefox %%U\nTerminal=false\n' \
  > /root/.config/xfce4/panel/launcher-3/dex-firefox.desktop
chmod 644 /root/.config/xfce4/panel/launcher-3/dex-firefox.desktop
cp /root/.config/xfce4/panel/launcher-3/dex-firefox.desktop /root/Desktop/dex-firefox.desktop
chmod 755 /root/Desktop/dex-firefox.desktop
printf '[Desktop Entry]\nType=Application\nName=TermIcon\nExec=htop\nTerminal=true\n' > /root/Desktop/term.desktop

echo "  -- the menu"
oadx-apps-menu --menu-only
copy_is "Exec lines of the entry and its actions behind oadx-run; other groups, keys and comments as they were" app.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=App
Name[de]=Anwendung
# a comment stays
Exec=oadx-run app %U
TryExec=app
Icon=app
Actions=new;

[Desktop Action new]
Name=New window
Exec=oadx-run app --new-window %U

[X-Other Group]
Exec=not-a-launch
EOF
no_copy term.desktop "Terminal=true: left alone (it would lose its terminal)"
no_copy link.desktop "Type=Link: left alone"
no_copy hidden.desktop "Hidden=true: left alone"
copy_is "NoDisplay (a file handler): routed" nodisplay.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=N
Exec=oadx-run handler %f
NoDisplay=true
MimeType=text/x-n;
EOF
copy_is "DBusActivatable with an Exec: activation off, Exec routed" dbus.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=D
Exec=oadx-run dbus-app --gapplication-service
DBusActivatable=false
EOF
no_copy dbusonly.desktop "D-Bus only, no Exec: left alone"
no_copy optout.desktop "X-OADX-Route=false: left alone"
copy_is "already behind oadx-run: not prefixed twice" already.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=A
Exec=oadx-run already %U
EOF
copy_is "already behind /usr/local/bin/oadx-run: not prefixed twice" already2.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=A2
Exec=/usr/local/bin/oadx-run abs
EOF
copy_is "spaces around = and inside the command" spaced.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=S
Exec=oadx-run spaced  --two  spaces
EOF
copy_is "CRLF file: routed, written with LF" crlf.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=C
Exec=oadx-run crlf %u
EOF
copy_is "same name in /usr/local/share and /usr/share: the /usr/local one" dup.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=Local
Exec=oadx-run local-dup
EOF
no_copy dup2.desktop "the /usr/local one is a terminal app: no copy of the /usr/share one either"
no_copy garbage.desktop "not a desktop file: left alone"
no_copy empty.desktop "empty file: left alone"
before=$(mtime "$OUT/app.desktop")
sleep 1.1
rm -f "$U/gone.desktop"
oadx-apps-menu --menu-only
if [ -e "$OUT/gone.desktop" ]; then bad "a package removed: its copy goes" "still there"; else ok "a package removed: its copy goes"; fi
if [ "$(mtime "$OUT/app.desktop")" = "$before" ]; then ok "unchanged: the copy is not rewritten"; else bad "unchanged copy rewritten" "mtime moved"; fi
sed -i 's/^Icon=app$/Icon=app2/' "$U/app.desktop"
oadx-apps-menu --menu-only
if grep -qx 'Icon=app2' "$OUT/app.desktop" && grep -qx 'Exec=oadx-run app %U' "$OUT/app.desktop"; then
  ok "a package updated: the copy follows, still routed"
else
  bad "a package updated" "$(cat "$OUT/app.desktop" | tr '\n' '|')"
fi
if cmp -s /root/Desktop/dex-firefox.desktop /root/.config/xfce4/panel/launcher-3/dex-firefox.desktop && \
   grep -qx 'Exec=/usr/local/bin/dex-firefox %U' /root/Desktop/dex-firefox.desktop; then
  ok "--menu-only leaves the dock and the desktop icons alone"
else
  bad "--menu-only touched launchers" "$(grep Exec /root/Desktop/dex-firefox.desktop)"
fi

echo "  -- the dock and the desktop icons"
m1=$(stat -c %a /root/Desktop/dex-firefox.desktop)
m2=$(stat -c %a /root/.config/xfce4/panel/launcher-3/dex-firefox.desktop)
menu_before=$(mtime "$OUT/app.desktop")
oadx-apps-menu --launchers-only
for f in /root/.config/xfce4/panel/launcher-3/dex-firefox.desktop /root/Desktop/dex-firefox.desktop; do
  if grep -qx 'Exec=oadx-run /usr/local/bin/dex-firefox %U' "$f" && [ "$(grep -c . "$f")" = 7 ]; then
    ok "routed in place: ${f#/root/}"
  else
    bad "routed in place: ${f#/root/}" "$(cat "$f" | tr '\n' '|')"
  fi
done
if [ "$(stat -c %a /root/Desktop/dex-firefox.desktop)" = "$m1" ] && \
   [ "$(stat -c %a /root/.config/xfce4/panel/launcher-3/dex-firefox.desktop)" = "$m2" ]; then
  ok "file modes kept ($m1 on the desktop icon, which xfdesktop needs, $m2 in the dock)"
else
  bad "file modes kept" "$(stat -c %a /root/Desktop/dex-firefox.desktop) / $(stat -c %a /root/.config/xfce4/panel/launcher-3/dex-firefox.desktop)"
fi
if grep -qx 'Exec=htop' /root/Desktop/term.desktop; then ok "a terminal app on the desktop: left alone"; else bad "terminal icon" "changed"; fi
if [ "$(mtime "$OUT/app.desktop")" = "$menu_before" ]; then ok "--launchers-only leaves the menu alone"; else bad "--launchers-only touched the menu" "mtime moved"; fi
icon_before=$(mtime /root/Desktop/dex-firefox.desktop)
sleep 1.1
oadx-apps-menu
if [ "$(mtime /root/Desktop/dex-firefox.desktop)" = "$icon_before" ] && \
   [ "$(grep -c '^Exec=oadx-run oadx-run' /root/Desktop/dex-firefox.desktop)" = 0 ]; then
  ok "run again: nothing rewritten, nothing prefixed twice"
else
  bad "run again" "$(grep Exec /root/Desktop/dex-firefox.desktop)"
fi

echo "  -- a guest with GVfs metadata"
# Before 4.19 xfdesktop trusts a launcher by its executable bit alone, GVfs
# or not (4.18, the guest's, has no launcher checksum: no such strings in it
# or its libxfce4ui), and a rewrite keeps the bit. From 4.19 on, with GVfs
# metadata, it checks a checksum too, and a rewritten icon would ask to be
# trusted again: the icons are left alone there. The version is dpkg's,
# stood in for here.
mkdir -p /usr/libexec /tmp/stub
: > /usr/libexec/gvfsd-metadata
gvfs_case() { # xfdesktop version ("" for none), want: routed|alone
  printf '[Desktop Entry]\nType=Application\nName=New\nExec=newapp %%U\n' > /root/Desktop/new.desktop
  chmod 755 /root/Desktop/new.desktop
  printf '[Desktop Entry]\nType=Application\nName=NewDock\nExec=newdock\n' > /root/.config/xfce4/panel/launcher-3/new.desktop
  printf '#!/bin/sh\n[ -n "%s" ] && printf %%s "%s" && exit 0\necho "dpkg-query: no packages found matching xfdesktop4" >&2\nexit 1\n' "$1" "$1" > /tmp/stub/dpkg-query
  chmod 755 /tmp/stub/dpkg-query
  PATH=/tmp/stub:$PATH oadx-apps-menu --launchers-only
  what="xfdesktop ${1:-not installed}"
  if [ "$2" = routed ]; then
    if grep -qx 'Exec=oadx-run newapp %U' /root/Desktop/new.desktop && [ "$(stat -c %a /root/Desktop/new.desktop)" = 755 ]; then
      ok "with GVfs metadata, $what: desktop icons routed, still executable"
    else bad "gvfs, $what: icon" "$(grep Exec /root/Desktop/new.desktop) $(stat -c %a /root/Desktop/new.desktop)"; fi
  else
    if grep -qx 'Exec=newapp %U' /root/Desktop/new.desktop; then ok "with GVfs metadata, $what: desktop icons left alone"
    else bad "gvfs, $what: icon" "$(grep Exec /root/Desktop/new.desktop)"; fi
  fi
  if grep -qx 'Exec=oadx-run newdock' /root/.config/xfce4/panel/launcher-3/new.desktop; then ok "with GVfs metadata, $what: the dock routed"
  else bad "gvfs, $what: dock" "$(grep Exec /root/.config/xfce4/panel/launcher-3/new.desktop)"; fi
}
gvfs_case "4.18.1-1build3" routed
gvfs_case "" routed
gvfs_case "4.20.0-1" alone
gvfs_case "4.19.2-1" alone
rm -rf /usr/libexec/gvfsd-metadata /tmp/stub /root/Desktop/new.desktop /root/.config/xfce4/panel/launcher-3/new.desktop

echo "  -- links"
# What a link points at may be a package's own file: never written through.
ln -sf "$U/app.desktop" /root/Desktop/linked.desktop
ln -sf "$U/nodisplay.desktop" /root/.config/xfce4/panel/launcher-3/linked.desktop
sum=$(md5sum "$U/app.desktop" "$U/nodisplay.desktop")
oadx-apps-menu --launchers-only
if [ "$(md5sum "$U/app.desktop" "$U/nodisplay.desktop")" = "$sum" ] && [ -L /root/Desktop/linked.desktop ] && \
   [ -L /root/.config/xfce4/panel/launcher-3/linked.desktop ]; then
  ok "a desktop icon or dock launcher that is a link: neither it nor its target written"
else
  bad "links" "$(grep Exec "$U/app.desktop" "$U/nodisplay.desktop" | tr '\n' '|')"
fi
rm -f /root/Desktop/linked.desktop /root/.config/xfce4/panel/launcher-3/linked.desktop

echo "  -- the user's own entries"
# ~/.local/share/applications comes before every copy on the menu's search:
# the menu editor's overrides, an app's own "install a launcher". In place.
UL=/root/.local/share/applications
mkdir -p "$UL"
printf '[Desktop Entry]\nType=Application\nName=Mine\nExec=mine --flag %%F\n' > "$UL/mine.desktop"
chmod 600 "$UL/mine.desktop"
printf '[Desktop Entry]\nType=Application\nName=App (mine)\nExec=app --mine %%U\n' > "$UL/app.desktop"
printf '[Desktop Entry]\nType=Application\nName=MyTerm\nExec=htop\nTerminal=true\n' > "$UL/myterm.desktop"
printf '[Desktop Entry]\nType=Application\nName=Hide\nExec=gone\nHidden=true\n' > "$UL/hide.desktop"
ln -sf "$U/nodisplay.desktop" "$UL/linked.desktop"
sum=$(md5sum "$U/nodisplay.desktop")
oadx-apps-menu --menu-only
if grep -qx 'Exec=oadx-run mine --flag %F' "$UL/mine.desktop" && [ "$(stat -c %a "$UL/mine.desktop")" = 600 ]; then
  ok "a user's own entry: routed in place, its mode kept"
else bad "user entry" "$(grep Exec "$UL/mine.desktop") $(stat -c %a "$UL/mine.desktop")"; fi
if grep -qx 'Exec=oadx-run app --mine %U' "$UL/app.desktop"; then ok "a user's override of a package's entry: routed too"
else bad "user override" "$(grep Exec "$UL/app.desktop")"; fi
if grep -qx 'Exec=htop' "$UL/myterm.desktop" && grep -qx 'Exec=gone' "$UL/hide.desktop"; then
  ok "a user's terminal entry and a hidden one: left alone"
else bad "user terminal/hidden" "changed"; fi
if [ "$(md5sum "$U/nodisplay.desktop")" = "$sum" ]; then ok "a user's link to a package's entry: not written through"
else bad "user link" "target changed"; fi
m=$(mtime "$UL/mine.desktop")
sleep 1.1
oadx-apps-menu --menu-only
if [ "$(mtime "$UL/mine.desktop")" = "$m" ] && [ "$(grep -c 'oadx-run oadx-run' "$UL/mine.desktop")" = 0 ]; then
  ok "run again: the user's entry is not rewritten"
else bad "user entry run again" "$(grep Exec "$UL/mine.desktop")"; fi
rm -rf /root/.local/share/applications

echo "  -- the helpers"
# "The web browser", "the terminal" (and so every Terminal=true entry XFCE's
# menus start): xfce4/helpers along XDG_DATA_DIRS, as linux-setup.sh writes
# its own and as xfce4-settings ships the rest.
HU=/usr/share/xfce4/helpers
HL=/usr/local/share/xfce4/helpers
HO=/usr/local/share/oadx-apps/xfce4/helpers
mkdir -p "$HU" "$HL"
# A command is routed only when its program is there (XFCE moves on to the
# next one in the list when one will not start, and oadx-run always starts):
# stand-ins for the programs the helpers below name.
for b in dex-chromium m mybrowser; do printf '#!/bin/sh\n' > "/usr/local/bin/$b"; chmod 755 "/usr/local/bin/$b"; done
cat > "$HU/dex-chromium.desktop" <<'EOF'
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
printf '[Desktop Entry]\nType=X-XFCE-Helper\nX-XFCE-Category=TerminalEmulator\nX-XFCE-Binaries=xfce4-terminal;Terminal;\nX-XFCE-Commands=%%B;\nX-XFCE-CommandsWithParameter=%%B -x %%s;\nName=Xfce Terminal\n' > "$HU/xfce4-terminal.desktop"
printf '[Desktop Entry]\nType=X-XFCE-Helper\nX-XFCE-Category=MailReader\nX-XFCE-Binaries=m;\nX-XFCE-Commands=m;m --alt; oadx-run m2\nX-XFCE-CommandsWithParameter=nosuchprog %%s;m %%s\nName=M\n' > "$HU/multi.desktop"
printf '[Desktop Entry]\nType=Application\nName=NotAHelper\nExec=x\n' > "$HU/nothelper.desktop"
printf '[Desktop Entry]\nType=X-XFCE-Helper\nX-XFCE-Category=WebBrowser\nX-XFCE-Binaries=o;\nX-XFCE-Commands=o;\nX-OADX-Route=false\nName=O\n' > "$HU/optout.desktop"
printf '[Desktop Entry]\nType=X-XFCE-Helper\nX-XFCE-Category=WebBrowser\nX-XFCE-Binaries=u;\nX-XFCE-Commands=m --usr;\nName=U\n' > "$HU/dup.desktop"
printf '[Desktop Entry]\nType=X-XFCE-Helper\nX-XFCE-Category=WebBrowser\nX-XFCE-Binaries=l;\nX-XFCE-Commands=m --local;\nName=L\n' > "$HL/dup.desktop"
printf '[Desktop Entry]\nType=X-XFCE-Helper\nX-XFCE-Category=FileManager\nX-XFCE-Binaries=g;\nX-XFCE-Commands=g;\nName=G\n' > "$HU/gone.desktop"
oadx-apps-menu --menu-only
hcopy_is() { # name file
  cat > "$W"
  if [ ! -f "$HO/$2" ]; then bad "$1" "no copy $HO/$2"
  elif cmp -s "$W" "$HO/$2"; then ok "$1"
  else bad "$1" "$(diff "$W" "$HO/$2" | head -8 | tr '\n' '|')"; fi
}
hcopy_is "the setup's browser helper: both its commands behind oadx-run, all else as it was" dex-chromium.desktop <<'EOF'
[Desktop Entry]
Version=1.0
Encoding=UTF-8
Type=X-XFCE-Helper
X-XFCE-Category=WebBrowser
X-XFCE-Binaries=dex-chromium;
X-XFCE-Commands=oadx-run /usr/local/bin/dex-chromium;
X-XFCE-CommandsWithParameter=oadx-run /usr/local/bin/dex-chromium "%s";
Icon=chromium
Name=Chromium
EOF
hcopy_is "the terminal's: %B kept for XFCE to fill in, behind oadx-run" xfce4-terminal.desktop <<'EOF'
[Desktop Entry]
Type=X-XFCE-Helper
X-XFCE-Category=TerminalEmulator
X-XFCE-Binaries=xfce4-terminal;Terminal;
X-XFCE-Commands=oadx-run %B;
X-XFCE-CommandsWithParameter=oadx-run %B -x %s;
Name=Xfce Terminal
EOF
hcopy_is "a list of commands: each one, one already behind oadx-run not twice, one whose program is not there left for XFCE to pass over" multi.desktop <<'EOF'
[Desktop Entry]
Type=X-XFCE-Helper
X-XFCE-Category=MailReader
X-XFCE-Binaries=m;
X-XFCE-Commands=oadx-run m;oadx-run m --alt; oadx-run m2
X-XFCE-CommandsWithParameter=nosuchprog %s;oadx-run m %s
Name=M
EOF
if [ ! -e "$HO/nothelper.desktop" ] && [ ! -e "$HO/optout.desktop" ]; then ok "not a helper, and one that says X-OADX-Route=false: no copy"
else bad "helper skips" "$(ls "$HO")"; fi
if grep -qx 'X-XFCE-Commands=oadx-run m --local;' "$HO/dup.desktop" 2>/dev/null; then ok "same name in /usr/local/share and /usr/share: the /usr/local one"
else bad "helper by name" "$(cat "$HO/dup.desktop" 2>/dev/null | tr '\n' '|')"; fi
rm -f "$HU/gone.desktop"
oadx-apps-menu --menu-only
if [ ! -e "$HO/gone.desktop" ] && [ -e "$HO/dex-chromium.desktop" ]; then ok "a helper removed: its copy goes, the rest stay"
else bad "helper removed" "$(ls "$HO")"; fi
UH=/root/.local/share/xfce4/helpers
mkdir -p "$UH"
printf '[Desktop Entry]\nType=X-XFCE-Helper\nX-XFCE-Category=WebBrowser\nX-XFCE-Binaries=mybrowser;\nX-XFCE-Commands=mybrowser;\nX-XFCE-CommandsWithParameter=mybrowser "%%s";\nName=Custom\n' > "$UH/custom-WebBrowser.desktop"
oadx-apps-menu --menu-only
if grep -qx 'X-XFCE-CommandsWithParameter=oadx-run mybrowser "%s";' "$UH/custom-WebBrowser.desktop"; then
  ok "a helper the user made in Preferred Applications: routed in place"
else bad "user helper" "$(grep Commands "$UH/custom-WebBrowser.desktop" | tr '\n' '|')"; fi
rm -rf /root/.local/share/xfce4
if command -v xfce4-mime-helper >/dev/null 2>&1 && command -v xvfb-run >/dev/null 2>&1; then
  # XFCE's own lookup, xfce4-settings 4.18's xfce4-mime-helper (what
  # exo-open --launch hands to), along the XDG_DATA_DIRS the sessions set.
  mkdir -p /tmp/mh /root/.config/xfce4
  for b in oadx-run xfce4-terminal; do
    printf '#!/bin/sh\necho "%s [$*]" >> /tmp/mh/ran\n' "$b" > "/tmp/mh/$b"
    chmod 755 "/tmp/mh/$b"
  done
  printf '#!/bin/sh\necho "dex-chromium [$*]" >> /tmp/mh/ran\n' > /usr/local/bin/dex-chromium
  chmod 755 /usr/local/bin/dex-chromium
  printf '[Configuration]\nWebBrowser=dex-chromium\nTerminalEmulator=xfce4-terminal\n' > /root/.config/xfce4/helpers.rc
  mh() { : > /tmp/mh/ran; PATH=/tmp/mh:$PATH XDG_DATA_DIRS=/usr/local/share/oadx-apps:/usr/local/share:/usr/share \
    timeout 20 xvfb-run -a xfce4-mime-helper "$@" >/dev/null 2>&1; cat /tmp/mh/ran 2>/dev/null; }
  got=$(mh --launch WebBrowser "https://example.org/a b")
  if [ "$got" = "oadx-run [/usr/local/bin/dex-chromium https://example.org/a b]" ]; then ok "XFCE's own lookup takes the routed browser: $got"
  else bad "XFCE lookup, browser" "$got"; fi
  got=$(mh --launch TerminalEmulator htop)
  if [ "$got" = "oadx-run [/tmp/mh/xfce4-terminal -x htop]" ]; then ok "and the routed terminal, which is how a Terminal=true entry opens: $got"
  else bad "XFCE lookup, terminal" "$got"; fi
  rm -rf /tmp/mh /root/.config/xfce4/helpers.rc /usr/local/bin/dex-chromium
else
  bad "XFCE's own helper lookup" "no xfce4-mime-helper or xvfb-run in this container"
fi
rm -rf "$HU" "$HL" "$HO"

echo "  -- many apps"
i=0
while [ $i -lt 400 ]; do
  i=$((i + 1))
  printf '[Desktop Entry]\nType=Application\nName=Bulk %s\nExec=bulk%s %%U\nIcon=b\nComment=One of many\n' $i $i > "$U/bulk$i.desktop"
done
t0=$(date +%s%N)
oadx-apps-menu
t1=$(date +%s%N)
n=$(ls "$OUT" | grep -c '^bulk')
ms=$(( (t1 - t0) / 1000000 ))
if [ "$n" = 400 ]; then ok "400 apps: 400 copies ($ms ms, one awk for all of them)"; else bad "400 apps" "$n copies"; fi
# The session's run and apt's can overlap: each keeps its own list.
oadx-apps-menu --menu-only & r1=$!
oadx-apps-menu --menu-only & r2=$!
oadx-apps-menu --menu-only & r3=$!
wait $r1 $r2 $r3
n=$(ls "$OUT" | grep -c '^bulk')
if [ "$n" = 400 ]; then ok "three runs at once: every copy still there"; else bad "runs at once" "$n copies"; fi
rm -f "$U"/bulk*.desktop
oadx-apps-menu --menu-only
n=$(ls "$OUT" | grep -c '^bulk')
if [ "$n" = 0 ]; then ok "400 apps removed: 400 copies gone"; else bad "removed bulk" "$n left"; fi
if [ -z "$(ls -A "$OUT" /usr/local/share/oadx-apps/xfce4/helpers 2>/dev/null | grep '^\.keep')" ]; then
  ok "no working file left in the menu or helper folder"
else bad "working file" "$(ls -A "$OUT" /usr/local/share/oadx-apps/xfce4/helpers | grep keep)"; fi

echo "  menu: $pass passed, $fail failed"
[ $fail -eq 0 ]
