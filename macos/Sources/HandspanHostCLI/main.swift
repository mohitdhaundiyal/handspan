import Foundation
import HandspanCore
import Security

// Headless host for interop tests (scripts/interop.sh) and debugging.
// Uses a throwaway keychain and state directory, auto-accepts files, prints
// one JSON object per event on stdout and reads commands from stdin:
//   pair | clip <text> | text <text> | send <path> | quit

setvbuf(stdout, nil, _IOLBF, 0)

func emit(_ object: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
    print(String(data: data, encoding: .utf8)!)
}

let root = FileManager.default.temporaryDirectory.appendingPathComponent("handspan-host-\(UUID().uuidString)")
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
var keychain: SecKeychain?
let keychainStatus = SecKeychainCreate(root.appendingPathComponent("host.keychain").path, 4, "test", false, nil, &keychain)
guard keychainStatus == errSecSuccess, let keychain else {
    emit(["event": "error", "message": "keychain \(keychainStatus)"])
    exit(1)
}

final class Events: HostDelegate {
    var paired: [String] = []
    func hostDidChange(_ s: HostService.Snapshot) {
        for t in s.transfers where t.status == .done { emitOnce("done:\(t.id)", ["event": "transfer_done", "xferId": t.id, "direction": t.direction.rawValue]) }
        for t in s.transfers { if case let .failed(r) = t.status { emitOnce("fail:\(t.id)", ["event": "transfer_failed", "xferId": t.id, "reason": r]) } }
    }

    func hostDidPair(_ d: PairedDevice, code: String) {
        paired.append(d.id)
        emit(["event": "paired", "id": d.id, "name": d.name, "code": code])
    }

    func hostDidReceiveClipboard(_ t: String, from _: PairedDevice) { emit(["event": "clip", "text": t]) }
    func hostDidReceiveItems(_ items: [OfferItem], from _: PairedDevice) {
        for i in items { emit(["event": "item", "kind": i.kind.rawValue, "text": i.text ?? ""]) }
    }

    func hostShouldAccept(_: Offer, from _: PairedDevice, decide: @escaping (Bool) -> Void) { decide(true) }
    func hostDidReceiveFile(_ url: URL, from _: PairedDevice) {
        emit(["event": "file", "path": url.path, "sha256": (try? Hashing.sha256Hex(fileAt: url)) ?? ""])
    }

    func hostLog(_ message: String) { emit(["event": "log", "message": message]) }

    private var seen: Set<String> = []
    private func emitOnce(_ key: String, _ object: [String: Any]) {
        if seen.insert(key).inserted { emit(object) }
    }
}

var config = HostService.Config(name: "Interop Mac", downloadsDir: root.appendingPathComponent("Downloads"),
                                stateDir: root.appendingPathComponent("State"), keychain: keychain)
config.advertise = CommandLine.arguments.contains("--advertise")
config.preferredPort = 0
let host = try HostService(config: config)
let events = Events()
host.delegate = events
host.start()

func waitForPort() -> UInt16 {
    while true {
        if let port = host.snapshot().port { return port }
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    }
}

let port = waitForPort()
emit(["event": "ready", "port": Int(port), "id": host.identity.deviceId, "dir": root.path])

Thread.detachNewThread {
    while let line = readLine() {
        let parts = line.split(separator: " ", maxSplits: 1).map(String.init)
        let arg = parts.count > 1 ? parts[1] : ""
        DispatchQueue.main.async {
            let target = events.paired.last ?? ""
            switch parts.first ?? "" {
            case "pair":
                let invite = host.openPairingWindow()
                var uri = invite.uri
                // The CLI is reached over loopback (interop tests, or an emulator via
                // 10.0.2.2), so the invite lists only that address.
                if var c = URLComponents(string: uri) {
                    c.queryItems = c.queryItems?.map { $0.name == "h" ? URLQueryItem(name: "h", value: "127.0.0.1:\(port)") : $0 }
                    uri = c.string ?? uri
                }
                emit(["event": "pairing", "uri": uri])
            case "clip": host.localClipboardChanged(arg)
            case "text": host.sendText(arg, to: target)
            case "send": host.sendFiles([URL(fileURLWithPath: arg)], to: target)
            case "quit":
                host.stop()
                SecKeychainDelete(keychain)
                try? FileManager.default.removeItem(at: root)
                exit(0)
            default: emit(["event": "error", "message": "unknown command \(line)"])
            }
        }
    }
    DispatchQueue.main.async {
        SecKeychainDelete(keychain)
        try? FileManager.default.removeItem(at: root)
        exit(0)
    }
}

RunLoop.main.run()
