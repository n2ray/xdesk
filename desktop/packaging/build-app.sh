#!/bin/bash
# Makes target/package/XDesk.app and XDesk.dmg, for Apple Silicon, with SDL inside the program and adb next to it.
#     build-app.sh VERSION [signing identity]
# Without an identity the app is signed ad hoc, enough to run it on this Mac.
set -euo pipefail
version=$1
identity=${2:--}
cd "$(dirname "$0")/.."
export CMAKE_POLICY_VERSION_MINIMUM=3.5
export MACOSX_DEPLOYMENT_TARGET=12.0
cargo build --release --no-default-features --features bundled-sdl --target-dir target/bundled --color never
rm -rf target/package
app=target/package/XDesk.app
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp target/bundled/release/xdesk-desktop "$app/Contents/MacOS/"
sed "s/@VERSION@/$version/g" packaging/Info.plist > "$app/Contents/Info.plist"
cp packaging/XDesk.icns "$app/Contents/Resources/"
tools=$(mktemp -d)
curl -fsSL -o "$tools/tools.zip" https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
unzip -q "$tools/tools.zip" -d "$tools"
cp "$tools/platform-tools/adb" "$app/Contents/MacOS/adb"
cp "$tools/platform-tools/NOTICE.txt" "$app/Contents/Resources/adb-NOTICE.txt"
codesign --force --timestamp=none --sign "$identity" "$app/Contents/MacOS/adb"
codesign --force --timestamp=none --sign "$identity" "$app"
codesign --verify --strict "$app"
stage=$(mktemp -d)
cp -R "$app" "$stage/"
ln -s /Applications "$stage/Applications"
hdiutil create -quiet -volname XDesk -srcfolder "$stage" -ov -format UDZO target/package/XDesk.dmg
echo "built target/package/XDesk.dmg"
