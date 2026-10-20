#!/bin/sh
# Cross-implementation test: the Kotlin PhoneClient against the Swift HostService
# over real mutual TLS on loopback. Needs macOS (Swift toolchain) and JDK 17+.
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/macos"
swift build --product handspan-host 2>&1 | grep -vE "search path .* not found" | tail -1
HANDSPAN_HOST_BIN="$(swift build --show-bin-path)/handspan-host"
export HANDSPAN_HOST_BIN
cd "$root/android"
./gradlew :core:test --tests app.handspan.core.InteropTest --rerun "$@"
