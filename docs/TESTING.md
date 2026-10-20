# Testing Handspan

## Automated

| Command | What it covers |
|---|---|
| `macos/scripts/test.sh` | Shared vectors; keychain identity and certificate; host over real mutual TLS on loopback: handshake rejection of unpaired and wrong-key peers, bad pairing proof, pairing, clipboard both ways without echo, text and link items, file receive with resume, hash failure, decline, file send with resume, unpair |
| `cd android && ./gradlew :core:test` | Shared vectors; framing; QR parsing; file names; incoming store resume across restart; out-of-order and corrupt chunks |
| `scripts/interop.sh` | Kotlin `PhoneClient` against the Swift `HostService`: pairing (both sides derive the same code), clipboard both ways, links both ways, a 1.5 MB upload cut mid-way and resumed, a 900 KB download |
| `cd android && ./gradlew :app:lintDebug` | Android lint |

`macos/scripts/test.sh` retries once when the Command Line Tools lose the Swift Testing macro
plugin during an incremental build. That is a toolchain issue, not a test failure.

## Android emulator against the headless host

This setup was used to check the real APK: AndroidKeyStore TLS client auth, the foreground
service, MediaStore saving and the share target.

```sh
# Terminal 1: host, fed commands from a file
: > /tmp/host.cmd
tail -n +1 -f /tmp/host.cmd | "$(cd macos && swift build --show-bin-path)/handspan-host"

# Terminal 2
echo pair >> /tmp/host.cmd                      # prints {"event":"pairing","uri":"handspan://pair?…h=127.0.0.1:PORT…"}
adb shell am start -a android.intent.action.VIEW \
  -d '"handspan://pair?…h=10.0.2.2:PORT…"' app.handspan.android    # 127.0.0.1 → 10.0.2.2
echo "clip hello" >> /tmp/host.cmd              # Mac → phone clipboard
echo "text https://example.com" >> /tmp/host.cmd
echo "send /path/to/file" >> /tmp/host.cmd      # Mac → phone file (accept on the phone)
```

Host commands: `pair`, `clip <text>`, `text <text>`, `send <path>`, `quit`. The host auto-accepts
incoming files and prints one JSON event per line.

## Manual checklist (real devices)

- [ ] Mac: **Pair new device…** shows a QR code with a countdown. It expires after 2 minutes.
- [ ] Phone: **Scan pairing code** → camera permission prompt → pairs. The 6-digit code matches on both screens.
- [ ] The menu-bar icon is filled while the phone is connected. The phone shows **Connected**.
- [ ] Copy on the Mac → it's on the phone's clipboard within about a second (Android shows its own "copied" toast).
- [ ] Copy on the phone with Handspan open → it's on the Mac. With Handspan closed, the tile or notification **Send clipboard** sends it.
- [ ] Copy a password from a password manager on the Mac → it is **not** sent.
- [ ] Turn clipboard sync off on either side → nothing syncs automatically, and explicit sends still work.
- [ ] Share a photo from the Gallery → **Send to Mac** → a prompt on the Mac → the file is in `~/Downloads/Handspan`.
- [ ] Mac **Send files…** → a prompt on the phone (dialog or notification) → the file is in `Download/Handspan`.
- [ ] Turn Wi-Fi off on the phone mid-transfer of a large file, then turn it back on → the transfer resumes rather than restarting.
- [ ] Reboot the phone → Handspan reconnects without being opened.
- [ ] Unpair on the Mac → the phone returns to the pairing screen. Unpair on the phone → the Mac lists the device as removed.
- [ ] Move the phone to a different network → the status says "Not reachable", and nothing connects.
