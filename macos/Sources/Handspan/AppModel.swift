import AppKit
import CoreImage.CIFilterBuiltins
import HandspanCore
import ServiceManagement
import UserNotifications

/// Something that arrived from a device, shown under "Recent".
struct RecentItem: Identifiable {
    enum Kind { case text(String), link(URL), file(URL), clipboard(String) }
    let id = UUID()
    let kind: Kind
    let from: String
    let date = Date()
}

@MainActor
final class AppModel: ObservableObject, HostDelegate {
    @Published private(set) var snapshot = HostService.Snapshot()
    @Published private(set) var recent: [RecentItem] = []
    @Published private(set) var pairingQR: NSImage?
    @Published private(set) var lastPairing: (name: String, code: String)?
    @Published private(set) var startupError: String?
    @Published var dropTargeted = false
    @Published var launchAtLogin = SMAppService.mainApp.status == .enabled

    private(set) var host: HostService?
    private let watcher = PasteboardWatcher()
    private var watcherFast = false

    static let downloadsDir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Handspan", isDirectory: true)
    static let stateDir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Handspan", isDirectory: true)

    init() {
        let name = Host.current().localizedName ?? "Mac"
        do {
            let host = try HostService(config: .init(name: name, downloadsDir: Self.downloadsDir, stateDir: Self.stateDir))
            host.delegate = self
            host.start()
            self.host = host
        } catch {
            startupError = "Could not load this Mac's identity: \(error)"
        }
        watcher.onText = { [weak self] text in self?.host?.localClipboardChanged(text) }
        watcher.start(interval: 2)
        if Bundle.main.bundleIdentifier != nil {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }
    }

    var onlineDevices: [HostService.DeviceStatus] { snapshot.devices.filter(\.online) }

    // MARK: Actions

    func startPairing() {
        guard let invite = host?.openPairingWindow() else { return }
        lastPairing = nil
        pairingQR = Self.qrImage(invite.uri)
    }

    func cancelPairing() {
        host?.closePairingWindow()
        pairingQR = nil
    }

    func setClipboardSync(_ on: Bool) { host?.setClipboardSync(on) }
    func setAutoAccept(_ on: Bool, for id: String) { host?.setAutoAccept(on, for: id) }
    func unpair(_ id: String) { host?.unpair(id) }
    func cancelTransfer(_ id: String) { host?.cancelTransfer(id) }

    func chooseFilesAndSend(to deviceId: String) {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "Send"
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK else { return }
        host?.sendFiles(panel.urls, to: deviceId)
    }

    func send(files: [URL], to deviceId: String) { host?.sendFiles(files, to: deviceId) }

    func sendClipboard(to deviceId: String) {
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else { return }
        host?.sendText(text, to: deviceId)
    }

    func copy(_ text: String) { watcher.write(text) }

    func setLaunchAtLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            NSLog("Handspan: launch at login failed: \(error)")
        }
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }

    // MARK: HostDelegate (main queue)

    nonisolated func hostDidChange(_ snapshot: HostService.Snapshot) {
        MainActor.assumeIsolated {
            self.snapshot = snapshot
            if snapshot.pairing == nil { pairingQR = nil }
            let fast = snapshot.devices.contains(where: \.online)
            if fast != watcherFast {
                watcherFast = fast
                watcher.start(interval: fast ? 0.5 : 2)
            }
        }
    }

    nonisolated func hostDidPair(_ device: PairedDevice, code: String) {
        MainActor.assumeIsolated {
            pairingQR = nil
            lastPairing = (device.name, code)
        }
    }

    nonisolated func hostDidReceiveClipboard(_ text: String, from device: PairedDevice) {
        MainActor.assumeIsolated {
            watcher.write(text)
            add(RecentItem(kind: .clipboard(text), from: device.name))
        }
    }

    nonisolated func hostDidReceiveItems(_ items: [OfferItem], from device: PairedDevice) {
        MainActor.assumeIsolated {
            for item in items {
                guard let text = item.text else { continue }
                if item.kind == .url, let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)) {
                    add(RecentItem(kind: .link(url), from: device.name))
                    notify(title: "Link from \(device.name)", body: url.absoluteString)
                } else {
                    add(RecentItem(kind: .text(text), from: device.name))
                    notify(title: "Text from \(device.name)", body: String(text.prefix(200)))
                }
            }
        }
    }

    nonisolated func hostShouldAccept(_ offer: Offer, from device: PairedDevice, decide: @escaping (Bool) -> Void) {
        MainActor.assumeIsolated {
            let files = offer.files
            let total = files.reduce(Int64(0)) { $0 + ($1.size ?? 0) }
            let alert = NSAlert()
            alert.messageText = "\(device.name) wants to send \(files.count == 1 ? "a file" : "\(files.count) files")"
            alert.informativeText = files.prefix(5).map { FileNames.sanitize($0.name ?? "file") }.joined(separator: "\n")
                + (files.count > 5 ? "\n…" : "")
                + "\n\n\(ByteCountFormatter.string(fromByteCount: total, countStyle: .file)) · saved to Downloads/Handspan"
            alert.addButton(withTitle: "Accept")
            alert.addButton(withTitle: "Decline")
            NSApp.activate(ignoringOtherApps: true)
            decide(alert.runModal() == .alertFirstButtonReturn)
        }
    }

    nonisolated func hostDidReceiveFile(_ url: URL, from device: PairedDevice) {
        MainActor.assumeIsolated {
            add(RecentItem(kind: .file(url), from: device.name))
            notify(title: "File from \(device.name)", body: url.lastPathComponent)
        }
    }

    nonisolated func hostLog(_ message: String) {
        NSLog("Handspan: %@", message)
    }

    // MARK: Helpers

    private func add(_ item: RecentItem) {
        recent.insert(item, at: 0)
        if recent.count > 10 { recent.removeLast(recent.count - 10) }
    }

    private func notify(title: String, body: String) {
        guard Bundle.main.bundleIdentifier != nil else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    static func qrImage(_ string: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        let rep = NSCIImageRep(ciImage: output)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}
