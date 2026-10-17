#!/usr/bin/env bash
# Builds app/libs/engine.aar from engine/ with gomobile.
# Needs Go 1.23+ and the Android NDK (ANDROID_NDK_HOME, or ANDROID_HOME/ndk/<version>).
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -n "${ANDROID_NDK_LATEST_HOME:-}" ]; then
    export ANDROID_NDK_HOME="$ANDROID_NDK_LATEST_HOME"
  elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$(ls "$ANDROID_HOME/ndk" | sort -V | tail -n 1)"
  fi
fi
[ -n "${ANDROID_NDK_HOME:-}" ] || { echo "No Android NDK found. Set ANDROID_NDK_HOME." >&2; exit 1; }

export PATH="$(go env GOPATH)/bin:$PATH"
if ! command -v gomobile >/dev/null; then
  go install golang.org/x/mobile/cmd/gomobile@latest
  go install golang.org/x/mobile/cmd/gobind@latest
fi
gomobile init

cd engine
# gomobile bind needs golang.org/x/mobile in the module; added here, not committed,
# so the engine itself keeps no dependencies.
cp go.mod go.mod.bak
[ -f go.sum ] && cp go.sum go.sum.bak
trap 'mv go.mod.bak go.mod; if [ -f go.sum.bak ]; then mv go.sum.bak go.sum; else rm -f go.sum; fi' EXIT
go get golang.org/x/mobile/bind@latest

mkdir -p ../app/libs
# 16 KB pages: Android 15 devices may use them, and Play requires it.
gomobile bind -target=android/arm64,android/arm -androidapi 26 \
  -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
  -o ../app/libs/engine.aar .
echo "Built app/libs/engine.aar"
