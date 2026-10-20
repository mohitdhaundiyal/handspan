# Privacy Policy

_Last updated: 2026-10-20_

Handspan connects your own Android phone and Mac over your local network. It has no servers,
accounts, analytics, advertising or crash reporting.

## What Handspan handles

- **Clipboard text, files, links and text you send** go directly from one of your paired devices
  to the other over your local network, encrypted with mutual TLS 1.3. They never pass through
  the internet or any third party.
- **Device keys and pairing records** are created and stored only on your devices (the Android
  Keystore and the macOS keychain). They are not backed up or transferred.
- **Received files** are saved to your Downloads folder on the receiving device.

## What Handspan does not do

- It does not collect, sell or share any personal data.
- It does not connect to the internet.
- It does not read your clipboard in the background on Android. Clipboard text is sent only
  while the app is open or when you tap **Send clipboard**.

## Permissions (Android)

- **Camera:** scanning the pairing QR code. Images are processed on the device and not stored.
- **Network access and Wi-Fi multicast:** finding and connecting to your paired Mac on the local
  network. Android labels socket access "internet", but Handspan only connects to your Mac.
- **Notifications, foreground service and start at boot:** keeping the connection to your Mac
  and showing its status and incoming items.

## Contact

Questions: open an issue at <https://github.com/mohitdhaundiyal/handspan/issues>.
