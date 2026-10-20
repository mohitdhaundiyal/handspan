# Handspan

Handspan links your Mac and your Android phone on the same Wi-Fi. It syncs the clipboard both
ways and sends files, links and text. There is no cloud, account or relay: devices find each other
over mDNS and talk over mutual TLS 1.3, with keys pinned when you scan a QR code.

- **macOS:** a SwiftUI menu-bar app (macOS 13+). Lives in `macos/`.
- **Android:** Kotlin and Jetpack Compose (Android 10+). Lives in `android/`.
- **Protocol:** [`docs/PROTOCOL.md`](docs/PROTOCOL.md). The shared test vectors are in `protocol/vectors/`.
- **Design:** [`docs/SPEC.md`](docs/SPEC.md). Research notes are in [`docs/research/`](docs/research/localsend.md).

Licence: Apache-2.0.

## How it works

1. On the Mac, choose **Pair new device…** from the menu-bar icon. A QR code appears, valid for
   2 minutes. It contains the Mac's key fingerprint and a one-time secret.
2. Scan the code from the Handspan Android app. The phone accepts only the Mac whose key matches
   the code. The Mac accepts the phone only if it proves it knows the secret. Both screens then
   show the same 6-digit confirmation code.
3. After that, the phone reconnects on its own whenever both devices are on the same network.
   Unpaired devices are rejected during the TLS handshake, before any data is exchanged.

| | Mac → phone | Phone → Mac |
|---|---|---|
| Clipboard | Automatic | Automatic while Handspan is open. Otherwise use the **Send clipboard** Quick Settings tile or notification button (Android 10+ blocks background clipboard reads) |
| Files | **Send files…** or drop files on the menu panel | Share sheet → **Send to Mac**, or **Files** in the app |
| Links and text | **Send clipboard** in a device's menu | Share sheet, or **Text** in the app |

Files are sent in 256 KiB chunks and checked with SHA-256. An interrupted transfer resumes where it
stopped once the devices reconnect. The Mac skips clipboard items that password managers mark as
concealed.

## Build

### macOS (no Xcode required)

```sh
cd macos
scripts/bundle.sh            # → build/Handspan.app (ad-hoc signed)
open build/Handspan.app
scripts/test.sh              # unit + loopback TLS integration tests
```

The first time it runs, Handspan creates its identity key in your login keychain. With an
ad-hoc–signed build, macOS may ask again after each rebuild whether Handspan can use that key.
A Developer ID signature avoids this.

### Android

Requirements: JDK 17+, and an Android SDK with platform 37 installed.

```sh
cd android
./gradlew :app:assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test           # protocol unit tests (JVM)
```

### Cross-implementation test

This runs the Kotlin client against the real Swift host over loopback TLS. It covers pairing,
clipboard both ways, links, a phone → Mac upload interrupted and resumed, and a Mac → phone file.

```sh
scripts/interop.sh
```

`macos/` also builds `handspan-host`, a headless host driven over stdin and stdout. It is used by
the interop test and is handy for debugging the Android app against an emulator (the emulator
reaches the Mac at `10.0.2.2`). See [`docs/TESTING.md`](docs/TESTING.md).

## Layout

```
docs/       SPEC.md, PROTOCOL.md, TESTING.md, research/
protocol/   vectors/*.json, shared by the Swift and Kotlin tests
macos/      Swift package: HandspanCore, Handspan (menu-bar app), handspan-host (CLI)
android/    :core (pure Kotlin protocol and client), :app (Compose UI, service, tile, share target)
scripts/    interop.sh
```
