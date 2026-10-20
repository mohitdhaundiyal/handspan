#!/bin/sh
# Builds Handspan.app from the Swift package without Xcode.
# Usage: scripts/bundle.sh [debug|release]   (default: release)
set -eu
cd "$(dirname "$0")/.."
config="${1:-release}"
swift build -c "$config" --product Handspan
bin="$(swift build -c "$config" --show-bin-path)/Handspan"
app="build/Handspan.app"
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$bin" "$app/Contents/MacOS/Handspan"
cp Resources/Info.plist "$app/Contents/Info.plist"
# Ad-hoc signature. Builds signed with a real Developer ID keep keychain access across updates.
codesign --force --sign - --timestamp=none "$app"
echo "Built $app"
