#!/bin/sh
# Builds dist/MacPad.app. Pass --install to copy it to /Applications and launch it.
set -eu
cd "$(dirname "$0")"

swift build -c release --arch arm64
BIN="$(swift build -c release --arch arm64 --show-bin-path)/MacPad"

APP=../dist/MacPad.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/MacPad"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp Resources/AppIcon.icns Resources/MenuIcon.png Resources/MenuIcon@2x.png "$APP/Contents/Resources/"

# A stable signing identity keeps the Screen Recording and Accessibility grants across rebuilds.
IDENTITY="${MACPAD_SIGN_IDENTITY:-$(security find-identity -v -p codesigning | awk -F'"' '/Apple Development/ { print $2; exit }')}"
if [ -z "$IDENTITY" ]; then
    echo "No Apple Development certificate found; signing ad hoc (permissions reset on every rebuild)."
    IDENTITY=-
fi
codesign --force --sign "$IDENTITY" "$APP"
echo "Built $APP"

if [ "${1:-}" = "--install" ]; then
    pkill -x MacPad 2>/dev/null || true
    rm -rf /Applications/MacPad.app
    cp -R "$APP" /Applications/MacPad.app
    open /Applications/MacPad.app
    echo "Installed and launched /Applications/MacPad.app"
fi
