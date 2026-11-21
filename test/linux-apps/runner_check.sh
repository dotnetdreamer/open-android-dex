#!/bin/sh
# LinuxAppRunner against real processes and a real proot (RealRunner.java):
# what a group kill misses and proot's SIGQUIT does not, an app whose proot
# Android killed, the service going while an app starts, a request from a
# session that has closed. Needs a JDK image with apt (eclipse-temurin).
#
# The proot is the one the app ships, built for this machine (build_proot.sh).
# Its signal handling (src/tracee/event.c) is the same code as the phone's.
#
#   runner_check.sh [RUNNER_JAVA]  -- another LinuxAppRunner.java to run against
set -u
REPO=${REPO:-/work}
RUNNER=${1:-$REPO/openandroiddex-launcher/app/src/main/java/com/ccrstech/openandroiddex/launcher/LinuxAppRunner.java}
HARNESS=${HARNESS:-$REPO/test/linux-apps/RealRunner.java}

echo "the runner against real processes and the app's proot"
sh "$REPO/test/linux-apps/build_proot.sh" || exit 3

# Android's shell and setsid where linux-app.sh looks for them.
mkdir -p /system/bin
ln -sf "$(command -v mksh)" /system/bin/sh
ln -sf "$(command -v setsid)" /system/bin/setsid

# The app's layout: the guest's root, its /tmp, the requests folder. The guest
# here is this container itself.
id app >/dev/null 2>&1 || useradd -m app
rm -rf /r /tmp/.oadx-apps
mkdir -p /r/shm /tmp/.oadx-apps
ln -s / /r/rootfs
ln -s /tmp /r/tmp
cp "$REPO/openandroiddex-linux/linux-app.sh" /r/linux-app.sh
chown -R app /r /tmp/.oadx-apps
chmod 700 /tmp/.oadx-apps
chmod 755 /root

rm -rf /tmp/rr
mkdir -p /tmp/rr/src /tmp/rr/out
cp "$RUNNER" /tmp/rr/src/LinuxAppRunner.java
cp "$HARNESS" /tmp/rr/src/RealRunner.java
javac -nowarn -d /tmp/rr/out /tmp/rr/src/*.java 2>/tmp/javac.log || { cat /tmp/javac.log; echo "  FAIL javac"; exit 3; }
chmod -R a+rX /tmp/rr
JAVA=$(command -v java)
su app -s /bin/sh -c "cd /tmp && $JAVA -cp /tmp/rr/out com.ccrstech.openandroiddex.launcher.RealRunner"
