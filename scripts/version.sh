#!/bin/sh
# The Android and macOS apps share one version.
#
#   scripts/version.sh            print the version name; fail if the apps disagree
#   scripts/version.sh code       print the build number (Android versionCode, CFBundleVersion)
#   scripts/version.sh bump X.Y.Z set the version on both apps and increment the build number
set -eu
cd "$(dirname "$0")/.."
exec python3 - "$@" <<'EOF'
import pathlib, plistlib, re, sys

gradle = pathlib.Path("android/app/build.gradle.kts")
plist = pathlib.Path("macos/Resources/Info.plist")

def read():
    text = gradle.read_text()
    name = re.search(r'versionName = "([^"]+)"', text).group(1)
    code = int(re.search(r"versionCode = (\d+)", text).group(1))
    info = plistlib.loads(plist.read_bytes())
    mac = (info["CFBundleShortVersionString"], int(info["CFBundleVersion"]))
    if (name, code) != mac:
        sys.exit(f"Version mismatch: Android {name} ({code}), macOS {mac[0]} ({mac[1]})")
    return name, code

args = sys.argv[1:]
name, code = read()
if not args:
    print(name)
elif args == ["code"]:
    print(code)
elif len(args) == 2 and args[0] == "bump":
    new = args[1]
    if not re.fullmatch(r"\d+\.\d+\.\d+", new):
        sys.exit(f"Not a semantic version: {new}")
    if tuple(map(int, new.split("."))) <= tuple(map(int, name.split("."))):
        sys.exit(f"{new} is not newer than {name}")
    text = gradle.read_text()
    text = re.sub(r'versionName = "[^"]+"', f'versionName = "{new}"', text)
    text = re.sub(r"versionCode = \d+", f"versionCode = {code + 1}", text)
    gradle.write_text(text)
    # Edit the plist as text to keep its formatting and comments.
    text = plist.read_text()
    text = re.sub(r"(<key>CFBundleShortVersionString</key>\s*<string>)[^<]+", rf"\g<1>{new}", text)
    text = re.sub(r"(<key>CFBundleVersion</key>\s*<string>)[^<]+", rf"\g<1>{code + 1}", text)
    plist.write_text(text)
    print(f"{name} ({code}) -> {new} ({code + 1})")
else:
    sys.exit("Usage: scripts/version.sh [code | bump X.Y.Z]")
EOF
