#!/bin/sh
# Builds Handspan.app from the Swift package without Xcode.
# Usage: scripts/bundle.sh [debug|release]   (default: release)
# Release builds are universal (Apple silicon and Intel); debug builds target this Mac only.
set -eu
cd "$(dirname "$0")/.."
config="${1:-release}"
app="build/Handspan.app"
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
if [ "$config" = release ]; then
    for arch in arm64 x86_64; do
        swift build -c release --product Handspan --triple "$arch-apple-macosx13.0" --scratch-path ".build/$arch"
    done
    lipo -create -output "$app/Contents/MacOS/Handspan" \
        "$(swift build -c release --triple arm64-apple-macosx13.0 --scratch-path .build/arm64 --show-bin-path)/Handspan" \
        "$(swift build -c release --triple x86_64-apple-macosx13.0 --scratch-path .build/x86_64 --show-bin-path)/Handspan"
else
    swift build -c "$config" --product Handspan
    cp "$(swift build -c "$config" --show-bin-path)/Handspan" "$app/Contents/MacOS/Handspan"
fi
cp Resources/Info.plist "$app/Contents/Info.plist"
# Ad-hoc signature. Builds signed with a real Developer ID keep keychain access across updates.
codesign --force --sign - --timestamp=none "$app"
echo "Built $app"
