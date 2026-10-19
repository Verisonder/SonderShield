#!/usr/bin/env bash
# Builds SonderShield for Linux: the program (static, no cgo), the WebKitGTK window helper,
# and the AppImage that holds both. Run on Ubuntu with libwebkit2gtk-4.1-dev installed.
#
#   tools/build_appimage.sh <version> <out-dir>
#
# Writes <out-dir>/SonderShield-Linux-<version>-x86_64.AppImage and
# <out-dir>/sondershield-linux-x86_64 (the plain program the service updates itself from).
set -euo pipefail
VERSION="$1"
OUT="$(mkdir -p "$2" && cd "$2" && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
APPDIR="$WORK/SonderShield.AppDir"
mkdir -p "$APPDIR/usr/bin"

echo "== program"
(cd "$ROOT/engine" && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath \
  -ldflags "-s -w -X main.version=$VERSION" -o "$APPDIR/usr/bin/sondershield" ./cmd/sondershield)

echo "== window"
cc -O2 -Wall "$ROOT/linux/window/sondershield-window.c" -o "$APPDIR/usr/bin/sondershield-window" \
  $(pkg-config --cflags --libs gtk+-3.0 webkit2gtk-4.1)
strip "$APPDIR/usr/bin/sondershield-window"

echo "== AppImage"
cp "$ROOT/linux/AppRun" "$APPDIR/AppRun"
cp "$ROOT/linux/sondershield.desktop" "$APPDIR/sondershield.desktop"
cp "$ROOT/linux/sondershield.png" "$APPDIR/sondershield.png"
ln -s sondershield.png "$APPDIR/.DirIcon"
mkdir -p "$APPDIR/usr/share/icons/hicolor/256x256/apps"
cp "$ROOT/linux/sondershield.png" "$APPDIR/usr/share/icons/hicolor/256x256/apps/sondershield.png"

TOOL="$WORK/appimagetool"
curl -sfL -o "$TOOL" https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
chmod +x "$TOOL"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$APPDIR" "$OUT/SonderShield-Linux-$VERSION-x86_64.AppImage"

cp "$APPDIR/usr/bin/sondershield" "$OUT/sondershield-linux-x86_64"
rm -rf "$WORK"
ls -la "$OUT"
