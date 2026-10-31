# Handspan Wire Protocol v1

This document is the source of truth for both implementations. Any change goes here first, and
then into `protocol/vectors/`, which both test suites load.

Roles: the **host** is the Mac. It listens, advertises itself and owns the pairing window.
The **phone** is Android. It dials the host. A host may have several phones paired. A phone is
paired with at most one host.

## 1. Identity

- Each device has one ECDSA **P-256** key pair, created the first time it runs and never exported.
- Each device presents a self-signed X.509 certificate for that key, signed with
  ecdsa-with-SHA256 and valid for 10 years. Peers ignore everything in the certificate except its
  public key, including the validity dates. TLS itself proves the peer holds the key, through
  CertificateVerify.
- **Device ID** = lowercase hex of `SHA-256(SubjectPublicKeyInfo DER)`, which is 64 characters.
  - For P-256 the SPKI DER is always the 26-byte prefix
    `3059301306072a8648ce3d020106082a8648ce3d030107034200` followed by the 65-byte uncompressed
    point `04‖X‖Y`.
  - Vectors: `pairing.json → identity, rawP256Point`.

## 2. Discovery

- The host advertises the DNS-SD service `_handspan._tcp.local.` on an ephemeral TCP port.
- The TXT record holds:
  - `v=1`
  - `id=<first 16 hex chars of the host device ID>`
  - `n=<host display name>`
- The phone browses for the service and connects only to instances whose `id` prefix matches one of
  its paired hosts. Fallbacks, in order: the last address that worked, then the other addresses the
  host was reached at, including the hosts from the QR code.
- A host advertises and accepts connections only while its macOS login session is active, so at most
  one account of a Mac is reachable at a time.

## 3. Pairing QR code

```
handspan://pair?v=1&id=<hostId hex64>&k=<secret, base64url, no padding>&h=<host:port>[,<host:port>…]&n=<percent-encoded name>
```

- `k` is a 32-byte random value. It is valid for **120 s** and for **one** successful pairing,
  and it is cleared when the pairing window closes.
- `h` lists every non-loopback interface address. IPv6 literals go in brackets.
- Unknown query parameters are ignored, and `v` values other than 1 are rejected.
- Vector: `pairing.json → qr`.

## 4. Connection

1. The phone opens TCP to the host and starts **TLS 1.3**. TLS 1.2 and earlier are refused.
   Both sides present their certificates (mutual TLS). There is no SNI or hostname check.
2. **Phone verifies the host:** the leaf certificate's device ID must equal the paired or
   QR-scanned `hostId`. Otherwise the phone aborts the handshake.
3. **Host verifies the phone:** the leaf certificate's device ID must be in the paired list,
   **or** a pairing window must be open. Otherwise the host aborts the handshake.
4. After the handshake, each side sends `HELLO` (the phone first, and the host replies). The
   device ID in `HELLO.id` must equal the one derived from the peer's certificate. If it
   doesn't, close.
5. If the phone is not paired, the host replies `HELLO{paired:false}`. The phone must then send
   `PAIR_REQUEST` within 30 s or the host closes. While unpaired, every frame except `PAIR_REQUEST`
   and `PING` closes the connection.
6. If a phone believes it is paired but receives `HELLO{paired:false}` outside a pairing attempt,
   it was unpaired on the host. It deletes its pairing and closes.
7. If a phone that is already connected connects again, the host keeps the **newest** connection
   and closes the older one.

**Liveness:**
- The phone sends `PING` every 20 s, and the host answers `PONG`.
- Either side closes the connection after 60 s with no frame received.
- The phone reconnects with backoff: 1, 2, 4, 8, 16, 30, 30 … s. The backoff resets when the
  phone joins a network and when the host's service reappears in discovery.

## 5. Framing

```
u32 length (big-endian)  — number of bytes that follow (type + payload), 1 ≤ length ≤ 1 + 8 MiB
u8  type
…   payload              — UTF-8 JSON object for every type except CHUNK
```

- Receivers ignore unknown frame types and unknown JSON fields.
- A length over the limit, or JSON that doesn't parse, is a protocol error: close the connection.
- Vectors: `frames.json`.

| Type | Name | Direction | Payload |
|---|---|---|---|
| 0x01 | HELLO | both | `{v:1, id, name, platform:"macos"|"android", paired?:bool (host only)}` |
| 0x02 | PING | phone→host | `{}` |
| 0x03 | PONG | host→phone | `{}` |
| 0x04 | PAIR_REQUEST | phone→host | `{name, proof}` |
| 0x05 | PAIR_RESULT | host→phone | `{ok:true, name}` or `{ok:false, reason}` |
| 0x10 | CLIP | both | `{id:uuid, origin:deviceId, mime:"text/plain", text, sha256}` |
| 0x20 | OFFER | both | `{xferId:uuid, items:[Item]}` |
| 0x21 | ACCEPT | both | `{xferId, files:{fileId: offset}}` |
| 0x22 | DECLINE | both | `{xferId}` |
| 0x23 | CHUNK | both | binary: `xferId 16B ‖ fileId 16B ‖ offset u64 BE ‖ data` with 0 < data ≤ 256 KiB |
| 0x24 | FILE_DONE | both | `{xferId, fileId}` |
| 0x25 | VERIFIED | both | `{xferId, fileId}` |
| 0x26 | HASH_FAIL | both | `{xferId, fileId}` |
| 0x27 | CANCEL | both | `{xferId, reason}` |
| 0x30 | UNPAIRED | both | `{}`. The receiver deletes the pairing and closes |

UUIDs are lowercase canonical strings in JSON and 16 raw bytes in CHUNK. Hashes are lowercase hex SHA-256.

## 6. Pairing proof

```
proof   = hex( HMAC-SHA256(k, "handspan-pair-v1"    ‖ hostId[32 raw bytes] ‖ phoneId[32 raw bytes]) )
confirm = HMAC-SHA256(k, "handspan-confirm-v1" ‖ hostId ‖ phoneId)
code    = big-endian uint32(confirm[0..4]) mod 1 000 000, shown as 6 digits with leading zeros
```

- The host computes `phoneId` from the certificate the phone actually presented, never from
  `HELLO`, and compares the proofs in constant time.
- On a match, the host stores `{phoneId, name}`, closes the pairing window and sends
  `PAIR_RESULT{ok:true}`. The same connection then works as a normal paired session.
- On a mismatch, the host sends `PAIR_RESULT{ok:false, reason:"bad_proof"}` and closes.
  The window stays open until it expires.
- Both UIs show `code` so the user can compare them if they want.
- Vector: `pairing.json → pairing`.

## 7. Clipboard

- `text` is at most 1 MiB of UTF-8, and `sha256` is the hash of those UTF-8 bytes. A receiver
  drops a CLIP whose hash doesn't match.
- **Loop prevention:** each side remembers the hash of the last clip it *applied* and the last
  one it *sent*.
  - It sends no local change whose hash equals either of them.
  - It doesn't apply a received clip whose hash equals the last one applied.
- **Fan-out:** the host forwards a clip from one phone to its other connected phones, keeping the
  original `origin`. Phones never forward.

## 8. Transfers (files, text, links)

`Item` is one of:

```json5
{ fileId, kind: "file", name, size, mime, sha256 }
{ fileId, kind: "text", text }     // ≤ 1 MiB
{ fileId, kind: "url",  text }     // absolute http(s) URL
```

1. The sender sends `OFFER`. The receiver shows `text` and `url` items immediately, with no prompt.
2. If the offer contains at least one `file` item, the receiver must answer `ACCEPT` or `DECLINE`.
   - `ACCEPT.files` lists the accepted files and the byte offset each one should start from.
     Files that aren't listed are skipped.
   - If there is no answer within 5 min, the sender sends `CANCEL{reason:"timeout"}`.
3. The sender sends the accepted files **one at a time, in offer order**:
   - CHUNK frames from `offset` up to `size`, in order, then `FILE_DONE`.
   - It waits for `VERIFIED` or `HASH_FAIL` before starting the next file.
   - An empty file is just `FILE_DONE`.
4. The receiver writes chunks to a part file and closes with `CANCEL` if a chunk's offset is not
   the next byte expected.
   - On `FILE_DONE` it checks the size and SHA-256 of the whole part file.
   - If both match, it renames the part file to the final name and sends `VERIFIED`.
   - Otherwise it deletes the part file and sends `HASH_FAIL`.
5. After `HASH_FAIL`, the sender retries that file once, from offset 0. A second failure cancels the transfer.
6. Either side may send `CANCEL` at any time. The receiver then deletes the transfer's part files.

**Resume:**
- If the connection drops mid-transfer, the sender keeps the transfer in memory and sends the
  **same** `OFFER` (same `xferId`, `fileId`s and hashes) after reconnecting.
- A receiver that already accepted that `xferId` replies `ACCEPT` without asking the user again.
  Each listed offset is the current part-file length, and verified files are left out.
- Part files and their sidecars are deleted after 24 h.

**File names:** the receiver sanitizes `name` before using it. Vectors: `files.json`.
1. Remove control characters (U+0000–U+001F and U+007F).
2. Replace `\` with `/` and keep only the text after the last `/`.
3. Replace `:` with `_`.
4. Trim whitespace, then remove leading dots.
5. Truncate to 200 UTF-8 bytes, keeping the extension.
6. If the result is empty, use `file`.
7. If the name is taken, add ` (1)`, ` (2)` and so on before the extension.
