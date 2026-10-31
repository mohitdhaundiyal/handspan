# Handspan — MVP Specification (draft for approval)

Handspan is an open-source app that links one Mac and one Android phone **on the same local
network**. It syncs the clipboard both ways and sends files, links and text. There is no cloud,
no account and no relay server.

Findings this spec builds on: [`research/localsend.md`](research/localsend.md). This is a
clean-room design. It contains no code from the apps we studied.

## 1. Scope

**In the MVP**
- QR pairing between one Mac and one or more Android devices (a "Mac ↔ Android" star).
- Automatic Mac → Android clipboard sync, and Android → Mac clipboard sync (see §5.2 for the Android limits).
- Clipboard content is **plain text only**, which covers URLs. The limit is 1 MiB.
- Sending files, links and text in either direction, with resume after a dropped connection.
- Android: Kotlin, Jetpack Compose, minSdk 29 (Android 10), targetSdk 36.
- macOS: Swift, SwiftUI `MenuBarExtra`, macOS 13+, `LSUIElement` (no Dock icon).

**Not in the MVP:** image/RTF clipboard sync, Android ↔ Android or Mac ↔ Mac pairing, internet
or relay transport, iOS, Windows, transfers across subnets, encrypted history.

## 2. Architecture

```
┌──────────── macOS ────────────┐            ┌──────────── Android ───────────┐
│ MenuBarExtra UI (SwiftUI)     │            │ Compose UI + QS tile + share   │
│ PasteboardWatcher (poll)      │            │ ClipboardBridge                │
│ HandspanCore (Swift package)  │◀── TLS ──▶│ :core (Kotlin module)          │
│  ├ Discovery (Bonjour advert) │            │  ├ Discovery (NsdManager)      │
│  ├ Identity (Keychain, P-256) │   1.3      │  ├ Identity (AndroidKeyStore)  │
│  ├ Session / Framing          │  mutual    │  ├ Session / Framing           │
│  └ Transfer engine            │  pinned    │  └ Transfer engine             │
└───────────────────────────────┘            │ Foreground service (connDevice)│
                                             └────────────────────────────────┘
```

Both cores implement the same wire protocol (§4) and run the same shared test vectors
(`protocol/vectors/*.json`). The protocol is defined in `docs/PROTOCOL.md` and changes there first.

## 3. Discovery and pairing

### 3.1 Identity
- Each device generates one **ECDSA P-256** key the first time it runs.
  - Android stores it in the AndroidKeyStore and it never leaves the Keystore.
  - macOS stores it in the Keychain as a `SecIdentity`. It is not marked as exportable.
- Each device wraps the key in a self-signed X.509 certificate valid for 10 years.
- **Device ID** = SHA-256 of the certificate's SubjectPublicKeyInfo (SPKI). We pin the SPKI,
  not the whole certificate, so a certificate can be re-issued without pairing again.
  LocalSend pins the hash of the whole DER certificate.

### 3.2 Discovery (mDNS / DNS-SD)
- Service type `_handspan._tcp`, with the port picked at random by the OS.
- The TXT record holds `v=1`, `id=<first 16 hex chars of the device ID>` and `n=<display name>`.
  It never contains secrets.
- macOS only advertises (`NWListener.service`). It declares
  `NSBonjourServices = [_handspan._tcp]`.
- Android uses `NsdManager` and holds a `MulticastLock` while browsing.
  On Android 14+ it uses `registerServiceInfoCallback`.
- A peer is shown as "online" only if its `id` matches a paired device.
  Unpaired adverts are ignored, except on the pairing screen.

### 3.3 QR pairing (the only way to trust a device)
1. On the Mac, choose **Pair new device…** from the menu. The Mac shows a QR code holding:
   `handspan://pair?v=1&id=<macDeviceId b64url>&k=<32-byte one-time secret b64url>&h=<ip:port,…>&n=<name>`
   The secret expires after **120 s** or after one use.
2. The Android device scans the code with CameraX + ZXing (open source, no Google Play
   Services). It then connects to a host from `h`, or finds the Mac over mDNS by `id`.
3. Android opens TLS and accepts the server only if its SPKI hash equals `id` (pinned from the
   QR code). Android presents its own certificate.
4. Android sends `PAIR_REQUEST{ name, mac = HMAC-SHA256(k, "handspan-pair-v1" ‖ macId ‖ androidId) }`.
   The Mac recomputes the HMAC from the **certificate Android actually presented**, so a man in
   the middle cannot swap keys.
5. If the HMAC matches, the Mac stores Android's ID and both sides show a 6-digit
   **confirmation code** taken from `HMAC(k, "confirm" ‖ …)`. Comparing it is optional,
   because the QR code is already an authenticated channel.
6. Unpairing on either side deletes the stored ID. The other side sees `UNPAIRED` the next time it connects.

### 3.4 Connection authorisation
- Mutual TLS 1.3 only. Cipher suites are the platform defaults.
- Each side's **custom trust evaluator** accepts a peer only if the peer's SPKI hash is in its
  paired-device list. The pairing listener (step 3) is the one exception.
- This check happens **inside the handshake**, so an unpaired peer never gets any data.
  LocalSend's server accepts any valid client certificate.

## 4. Wire protocol

The authoritative definition is [`PROTOCOL.md`](PROTOCOL.md). In summary:

- **The phone always dials the Mac.** The Mac runs the only listener. Android finds it over mDNS,
  falling back to the last known address and then the QR hosts. This removes connection races,
  and Android needs no server socket. (Changed from the first draft, where either side could dial.)
- One long-lived mutual-TLS connection per paired phone, carrying length-prefixed frames:
  JSON control frames and binary CHUNK frames.
- Keepalive is a PING every 20 s. The connection drops after 60 s of silence. Android
  reconnects with backoff (1, 2, 4 … 30 s).

## 5. Clipboard sync

### 5.1 macOS
- `NSPasteboard` has no change event, so a timer reads `changeCount` **every 500 ms**. The
  interval stretches to 2 s when no peer is connected.
- **Skip** anything marked `org.nspasteboard.ConcealedType`, `TransientType` or `AutoGeneratedType`.
  Password managers set these markers.
- Incoming CLIP: write the text to the pasteboard and record the new `changeCount` so we don't echo it back.

### 5.2 Android
- **Writing** (Mac → Android) works from the foreground service with `setPrimaryClip`. Android 13+
  shows its own "copied" toast. We give the clip a Handspan label
  so we can spot it later.
- **Reading** (Android → Mac): Android 10+ lets only the focused app or the default keyboard read
  the clipboard. The MVP therefore offers:
  1. Automatic sync while Handspan is in the foreground (`OnPrimaryClipChangedListener`).
  2. A **Quick Settings tile** and a **persistent notification action**, "Send clipboard". Each one
     opens a 1-pixel transparent activity that gets focus, reads the clip, sends it and finishes.
  3. A **share target**: "Share → Handspan" from any app.

  The UI states this limitation honestly. We do **not** use accessibility-service or logcat
  workarounds.
- The foreground service uses type `connectedDevice` to keep the connection alive. It runs only
  while at least one device is paired.

### 5.3 Preventing echo loops
- Every CLIP has an `origin` device ID and a `sha256`. Each side keeps the hash of the last text
  it applied and the last text it sent, and ignores matches.
- Both sides have a **pause sync** toggle. Sync is on by default after pairing.

## 6. Sending files, links and text

- **Mac:** drag files onto the menu-bar icon, **Send files…** in the menu, or the Services or
  Share menu ("Send with Handspan").
- **Android:** the share sheet (`ACTION_SEND`/`SEND_MULTIPLE`) or the in-app picker (SAF).
- The receiver asks before saving. A per-device "auto-accept" option is off by default.
  - Mac files go to `~/Downloads/Handspan/`.
  - Android files go to `Download/Handspan/` through MediaStore.
  - Links open the browser on tap. Text can be copied with one tap.
- **Chunking:** 256 KiB CHUNK frames, at most one file in flight per transfer, with backpressure
  from TCP. The sender hashes ahead of time (or as it streams, for content URIs) and puts the
  hash in the OFFER.
- **Resume:**
  - The receiver writes `<name>.handspan-part` and a sidecar file holding `{xferId, fileId, sha256, size}`.
  - After a dropped connection, the sender sends the same OFFER again, with the same `xferId` and
    file hashes. The receiver answers ACCEPT with each file's current `offset`, and the sender
    seeks there.
  - The receiver verifies the whole file's SHA-256 after FILE_DONE. On a mismatch it deletes the
    part file and replies HASH_FAIL, and the sender restarts that file from 0.
  - Part files older than 24 h are deleted.
- Progress is shown in the Mac menu and in an Android notification. Either side can cancel.

## 7. UI (MVP)

- **Mac menu:**
  - Paired devices with an online dot.
  - Clipboard sync toggle.
  - Send files…
  - Recent items (last 10).
  - Pair new device…
  - Settings (name, download folder, launch at login with `SMAppService`, auto-accept).
  - Quit
- **Android:**
  - Home: device card with status, sync toggle and a "Send clipboard" button.
  - Recent transfers.
  - Pair (scanner).
  - Settings.
  - Also: Quick Settings tile, share target and a persistent notification.

## 8. Security and privacy summary

- Nothing leaves the LAN. There is no telemetry.
- Mutual TLS 1.3 with pinned SPKI hashes is checked inside the handshake. Trust is set only by QR pairing.
- The pairing secret is single-use, expires after 120 s and is bound to both keys.
- The Mac skips concealed (password) clipboard items, so they never reach the phone.
- Private keys are never exportable.

## 9. Repo layout and tooling

```
handspan/
  docs/        SPEC.md, PROTOCOL.md, research/
  protocol/    vectors/*.json (frames, HMAC, device-id test vectors)
  android/     Gradle (KTS) project: :app (Compose), :core (pure Kotlin + Android crypto)
  macos/       Swift package: HandspanCore (library) + Handspan (SwiftUI menu-bar executable);
               scripts/bundle.sh wraps the binary into Handspan.app (no Xcode project required)
  LICENSE      Apache-2.0 (proposed)
```

- Tests: unit tests for framing, HMAC/pairing and resume offsets in both cores against the
  shared vectors, and a loopback integration test inside each platform.
- Manual end-to-end test checklist in `docs/TESTING.md`.

## 10. Implementation order (after approval)

1. `PROTOCOL.md` and test vectors.
2. Both cores: identity, framing, TLS, with unit tests.
3. Discovery and QR pairing.
4. Clipboard sync.
5. File, link and text transfer with resume.
6. UI polish, README and build instructions.

## 11. Decisions (approved 2026-10-18)

1. Licence: **Apache-2.0**.
2. Minimum versions: Android 10 (minSdk 29) and macOS 13.
3. Image clipboard sync is a follow-up, not part of the MVP.
4. Pending outgoing transfers live in memory. They resume after a dropped connection but not
   after an app restart.

## 12. Implementation notes (where the MVP differs from the draft above)

- **macOS sending:**
  - You send files with **Send files…** or by dropping files on the open menu panel.
  - The Services and Share menu extension ("Send with Handspan") and dropping onto the menu-bar
    icon itself are not built. Both need an Xcode app-extension target.
- **macOS prompts:** incoming file offers use an `NSAlert`. Received links and text post a user
  notification and appear under "Recent".
- **Android build:**
  - compileSdk is 37 because current Compose requires it.
  - targetSdk stays 36. Android 17's local-network permission is enforced from target 37, which is
    a follow-up.
- **Android identity:** the AndroidKeyStore generates the certificate, and the key allows the SHA-256
  and NONE digests, so TLS 1.3 client authentication works with a non-exportable key. Verified on an
  API 36 emulator against the Swift host.
- **Phone send-clipboard activity:** if the clipboard read is refused while the notification shade is
  still closing, it retries briefly (up to 4 tries, 150 ms apart).

## 13. Several Macs and macOS accounts (1.1.0)

A phone can be paired with several Macs, including several accounts on the same Mac, and
switches between them without pairing again.

### 13.1 Behaviour

- **Phone:** keeps a list of paired Macs. Scanning a pairing code adds a Mac (or refreshes it, if
  it is already in the list) and never removes another one. Each Mac can be unpaired on its own.
- **One connection at a time:** the phone connects to one paired Mac. When it is not connected, it
  tries the Macs that discovery currently sees first, then the others, most recently connected
  first. Clipboard, text and files go to the connected Mac.
- **macOS accounts:** Handspan only listens and advertises while its login session is the active
  one on screen. When the account is switched away from (fast user switching, or the login window),
  it closes its connections and stops advertising; when the account becomes active again it starts
  listening again. With two accounts logged in, only the account in front is reachable, so the
  phone follows whichever account is on screen.
- **Names:** a Mac identifies itself by its computer name plus the account's first name, for
  example `Studio Mac (Mohit)`, so the accounts of one Mac can be told apart on the phone. The first
  name is left out when the computer name already contains it, as in macOS's default
  `Mohit’s MacBook Air`; another account on that Mac shows as `Mohit’s MacBook Air (Expert)`.

### 13.2 Transfers

- Outgoing files that are still waiting, or were interrupted, are offered to the next Mac the
  phone connects to. Files that a Mac already verified are not sent again.
- Incoming transfers are keyed by transfer ID, so a Mac's interrupted transfer resumes when that
  Mac is connected again, as before.

### 13.3 Compatibility

- The wire protocol does not change. A 1.1 phone works with 1.0 Macs, and a 1.0 phone with a 1.1
  Mac (it stays paired with one Mac, as before).
- On upgrade, the phone's existing pairing becomes the first entry of the list.
