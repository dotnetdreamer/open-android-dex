#!/bin/bash
# build.sh — the from-anywhere page: the tunnel (iroh) as WebAssembly, its
# glue, and the viewer the phone's own door serves, into www/, which is the
# whole site (GitHub Pages serves it as it is).
#
# Needs: rustup target add wasm32-unknown-unknown; cargo install wasm-bindgen-cli
# --version 0.2.122; and a clang that targets wasm32 for ring's C (the Android
# NDK's does: ANDROID_NDK_HOME, or the one under ANDROID_HOME).
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
NDK=${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)
EXE=""; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=.exe ;; esac
OUT=${CARGO_TARGET_DIR:-target}
CC_wasm32_unknown_unknown="$BIN/clang$EXE" AR_wasm32_unknown_unknown="$BIN/llvm-ar$EXE" \
  RUSTFLAGS='--cfg getrandom_backend="wasm_js"' \
  cargo build --locked --target wasm32-unknown-unknown --release
wasm-bindgen --target web --out-dir www/pkg "$OUT/wasm32-unknown-unknown/release/dexremote_web.wasm"
# The viewer is the phone's own page code: one copy, kept in the launcher's assets.
A=../../openandroiddex-launcher/app/src/main/assets/linux/browser
cp -f "$A/phone.js" "$A/phone.css" "$A/sound.js" "$A/h264.js" "$A/keysym.js" "$A/icon.svg" www/
echo "www/ is the site: $(du -sh www | cut -f1)"
