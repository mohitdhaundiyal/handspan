# Research: how LocalSend does discovery, clipboard, transport and file transfer

**Subject:** LocalSend 1.18.2 (`brew install --cask localsend`, installed at `/Applications/LocalSend.app`).
**Sources:** the installed bundle (Info.plist and entitlements) and the public, MIT-licensed source:
`localsend/protocol` @ `62bd340` and `localsend/localsend` @ `d01fdb1` (2026-10-15).
**Method:** I read the protocol spec and source and looked at the bundle metadata. No disassembly
was needed. This document records only behaviour, and Handspan contains no LocalSend code.

## 1. Device discovery and pairing

| Finding | Evidence |
|---|---|
| Discovery is a custom JSON message sent over UDP multicast to `224.0.0.167:53317`. It does **not** use mDNS/DNS-SD. LocalSend picked a group inside `224.0.0.0/24` because some Android devices drop other groups. | protocol `README.md` §1, §3.1. `packages/core/src/multicast/mod.rs:26-39` |
| Each announcement is sent 3 times, after delays of 100 ms, 500 ms and 2 s, because single datagrams get lost. The TTL is 1, so it stays on the link. | `multicast/mod.rs:41-48`. `multicast/socket.rs:118` |
| Peers answer an announcement with `POST /api/localsend/v2/register` to the announcer, or with a UDP reply as a fallback. When multicast fails, the legacy fallback POSTs to every IP on the subnet. | `README.md` §3.1–3.2 |
| The device identity ("fingerprint") is the uppercase hex SHA-256 of the DER certificate. | `README.md` §2. `crypto/cert.rs:99-106` |
| **There is no pairing step.** Any device on the LAN can send to any other. Consent comes from an Accept/Decline prompt for each transfer, plus an optional 6-digit PIN (`?pin=`) with rate limiting (429). | `README.md` §4.1. v3 `http-diagram.mermaid` |
| Protocol v3, which is in progress, adds a nonce exchange and signed tokens to `/register`. It still relies on trust on first use (TOFU) and has no out-of-band pairing. | `protocol/v3/http-diagram.mermaid` |
| The macOS app declares `NSLocalNetworkUsageDescription` and Bonjour types `_http._tcp`, `_bonjour._tcp`, `_lnp._tcp.` only so it can get the Local Network permission. It does not advertise a service of its own. | `Info.plist` of the installed app |

## 2. Clipboard monitoring and push

| Finding | Evidence |
|---|---|
| **LocalSend does not monitor or sync the clipboard.** You send clipboard content by hand, either by pasting (⌘V triggers `PickFileAction(FilePickerOption.clipboard)`) or through the picker. | `app/lib/widget/watcher/shortcut_watcher.dart:45-47` |
| Text is sent as a "file" with `fileType: text`. The text itself travels inline in the `preview` field of `prepare-upload`, so no upload request follows. | `app/lib/provider/network/send_provider.dart:121-123` |
| The receiver shows the message and a **Copy** button that calls `Clipboard.setData`. Nothing is copied automatically. | `app/lib/pages/receive_page.dart:229-236` |

**Takeaway for Handspan:** LocalSend has no clipboard monitoring for us to learn from, so we have
to design it ourselves (see SPEC §5). The platform limits drive that design: macOS has no
pasteboard change notification, and Android 10+ blocks clipboard reads from the background.

## 3. Transport and encryption

| Finding | Evidence |
|---|---|
| Transport is a REST API over HTTPS on TCP 53317. Each side runs a server and a client. | `README.md` §1, §4 |
| The certificate is self-signed **RSA-2048** with `CN=LocalSend User`, no SANs and validity from 1975 to 4096. It never rotates. | `crypto/cert.rs:21-56` |
| The client pins the server certificate by its SHA-256 fingerprint inside the TLS handshake (rustls `ServerCertVerifier`) and ignores the hostname. With no expected fingerprint (during discovery) it accepts **any valid certificate**, which is TOFU. | `http/client/server_cert_verifier.rs:45-115` |
| The server requires a client certificate (mTLS), but `verify_client_cert` "trust[s] any certificate that is valid". The fingerprint is later only *read* from `peer_certificates()` to identify the sender. | `http/server/common/client_cert_verifier.rs:54-66`. `http/server/mod.rs:503-513, 559-572` |
| Tests check that a fingerprint mismatch aborts the handshake **before any request body is sent**. | `packages/core/tests/v2_tls_pinning.rs:282-345` |
| The reverse "download" API uses plain HTTP, because browsers reject self-signed certificates. | `README.md` §5 |

## 4. File transfer: chunking and resume

| Finding | Evidence |
|---|---|
| A transfer has two phases. `prepare-upload` sends metadata only (name, size, MIME type, optional `sha256`, preview) and the receiver accepts all, some or none of the files. The response returns a `sessionId` and a token for each file. | `README.md` §4.1 |
| Each file is sent as **one streaming `POST /upload`** body. Files can upload in parallel. | `README.md` §4.2 |
| The protocol has no chunking. Chunks exist only as stream frames in the implementation: TLS records of about 16 KiB, a 16-slot channel for backpressure, and 512 KiB coalesced disk writes. | `http/server/common/save.rs:10-16, 164-191` |
| If the sender provided a hash, the receiver hashes while it streams and returns `422` on a mismatch. | `README.md` §4.2. `save.rs:225-235` |
| **There is no resume.** A broken stream marks the file `Failed` ("Upload aborted"). The protocol has no Range or offset request, and the only fix is to send the file again. | `save.rs:193-210`. A grep for `resume|Range|offset` finds nothing in `packages/core/src` |
| One session at a time, with `409 Blocked by another session`. | `README.md` §4.1 |

## What Handspan should keep and what it should change

- **Keep:** pinning inside the handshake so data never reaches a wrong peer, a metadata-then-content
  transfer, hashing while streaming, multicast-style repeated announcements, and the
  `224.0.0.0/24`-only Android lesson. That lesson does not apply to us because we use mDNS's own group.
- **Change:**
  1. Use real mDNS/DNS-SD (`_handspan._tcp`) instead of a custom multicast format.
  2. Use **explicit QR pairing** instead of TOFU. The server rejects unpaired client certificates
     during the handshake instead of trusting any valid certificate.
  3. Use ECDSA P-256 keys kept in the Keystore/Keychain instead of RSA-2048 PEM files.
  4. Add automatic two-way clipboard sync.
  5. Add resumable transfers based on offsets.
