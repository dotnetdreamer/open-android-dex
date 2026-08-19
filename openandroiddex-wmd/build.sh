#!/usr/bin/env bash
# Build openandroiddex-wmd into a single dex, no Gradle and no Android project.
# The daemon is not an app: it is loaded by `app_process` at uid 2000, so it needs
# nothing but class files run through d8.
#
#   ./build.sh            compile + dex
#   ./build.sh push       ... and push to /data/local/tmp/wmd.dex
#
# The macOS/Linux twin of build.cmd. Keep the two in sync — and in sync with the
# "Build wmd daemon dex" step in .github/workflows/desktop.yml, which is a third
# copy of the same three commands (CI has neither script on its PATH).

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Android SDK ───────────────────────────────────────────────────────────
# ANDROID_HOME / ANDROID_SDK_ROOT if the environment sets them (CI does), then
# the platform's default install location.
for candidate in \
  "${ANDROID_HOME:-}" \
  "${ANDROID_SDK_ROOT:-}" \
  "$HOME/Library/Android/sdk" \
  "$HOME/Android/Sdk"; do
  if [ -n "$candidate" ] && [ -d "$candidate/platforms" ]; then
    SDK="$candidate"
    break
  fi
done

if [ -z "${SDK:-}" ]; then
  echo "ERROR: Android SDK not found. Set ANDROID_HOME." >&2
  exit 1
fi

# android-36 is what the sources compile against (build.cmd pins the same one).
PLATFORM_JAR="$SDK/platforms/android-36/android.jar"
if [ ! -f "$PLATFORM_JAR" ]; then
  echo "ERROR: android-36 platform not found under $SDK" >&2
  exit 1
fi

# Newest first, matching build.cmd's preference order.
BUILDTOOLS=""
for v in 36.1.0 36.0.0 35.0.0 34.0.0; do
  if [ -z "$BUILDTOOLS" ] && [ -x "$SDK/build-tools/$v/d8" ]; then
    BUILDTOOLS="$SDK/build-tools/$v"
  fi
done
if [ -z "$BUILDTOOLS" ]; then
  echo "ERROR: no build-tools with d8 found under $SDK/build-tools" >&2
  exit 1
fi

# ── javac ─────────────────────────────────────────────────────────────────
# JAVA_HOME if set, else Android Studio's bundled JBR (the JDK build.cmd uses
# on Windows), else whatever `javac` is on PATH.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
elif [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javac" ]; then
  JAVAC="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javac"
elif command -v javac >/dev/null 2>&1; then
  JAVAC="$(command -v javac)"
else
  echo "ERROR: no javac. Set JAVA_HOME to a JDK 17 or newer." >&2
  exit 1
fi

echo "[1/3] javac  ($JAVAC)"
rm -rf "$HERE/build"
mkdir -p "$HERE/build/classes"
find "$HERE/src" -name '*.java' > "$HERE/build/sources.txt"
# android.jar goes on the classpath, not the bootclasspath: JDK 17+ rejects
# -bootclasspath alongside -target 17. Everything we touch outside android.*
# is java.lang/util/io/net, which Android provides at runtime anyway.
"$JAVAC" -nowarn -Xlint:-options -source 17 -target 17 \
  -cp "$PLATFORM_JAR" \
  -d "$HERE/build/classes" "@$HERE/build/sources.txt"

echo "[2/3] d8     ($BUILDTOOLS)"
mkdir -p "$HERE/build/dex"
find "$HERE/build/classes" -name '*.class' > "$HERE/build/classes.txt"
"$BUILDTOOLS/d8" --min-api 26 --output "$HERE/build/dex" "@$HERE/build/classes.txt"
cp -f "$HERE/build/dex/classes.dex" "$HERE/openandroiddex-wmd.dex"

echo "[3/3] done -> $HERE/openandroiddex-wmd.dex"

if [ "${1:-}" = "push" ]; then
  adb push "$HERE/openandroiddex-wmd.dex" /data/local/tmp/wmd.dex
fi
