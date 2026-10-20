import HandspanCore
import SwiftUI
import UniformTypeIdentifiers

@main
struct HandspanApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        MenuBarExtra {
            MenuPanel()
                .environmentObject(model)
        } label: {
            Image(systemName: model.onlineDevices.isEmpty ? "hand.raised" : "hand.raised.fill")
        }
        .menuBarExtraStyle(.window)
    }
}

struct MenuPanel: View {
    // View state lives in AppModel rather than @State: @State is a macro in current SDKs,
    // and its plugin ships only with Xcode, not the Command Line Tools.
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            if let error = model.startupError {
                Text(error).font(.callout).foregroundStyle(.red)
            }
            if let qr = model.pairingQR {
                PairingView(qr: qr)
            } else if let paired = model.lastPairing {
                Label("Paired with \(paired.name) · code \(paired.code)", systemImage: "checkmark.circle.fill")
                    .font(.callout)
                    .foregroundStyle(.green)
            }
            devices
            if !model.snapshot.transfers.isEmpty { transfers }
            if !model.recent.isEmpty { recent }
            Divider()
            footer
        }
        .padding(14)
        .frame(width: 340)
        .background(model.dropTargeted ? Color.accentColor.opacity(0.08) : Color.clear)
        .onDrop(of: [.fileURL], isTargeted: $model.dropTargeted) { providers in
            guard let target = model.onlineDevices.first?.id else { return false }
            for provider in providers {
                _ = provider.loadObject(ofClass: URL.self) { url, _ in
                    guard let url else { return }
                    Task { @MainActor in model.send(files: [url], to: target) }
                }
            }
            return true
        }
    }

    private var header: some View {
        HStack {
            Text("Handspan").font(.headline)
            Spacer()
            Toggle("Clipboard sync", isOn: Binding(
                get: { model.snapshot.clipboardSyncEnabled },
                set: { model.setClipboardSync($0) }
            ))
            .toggleStyle(.switch)
            .controlSize(.small)
        }
    }

    @ViewBuilder private var devices: some View {
        if model.snapshot.devices.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text("No devices yet").font(.subheadline)
                Text("Install Handspan on your Android phone, then scan the pairing code. Both devices need to be on the same Wi-Fi.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } else {
            ForEach(model.snapshot.devices) { status in
                DeviceRow(status: status)
            }
        }
    }

    private var transfers: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Transfers").font(.caption).foregroundStyle(.secondary)
            ForEach(model.snapshot.transfers.prefix(4)) { t in
                TransferRow(transfer: t)
            }
        }
    }

    private var recent: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Recent").font(.caption).foregroundStyle(.secondary)
            ForEach(model.recent) { item in
                RecentRow(item: item)
            }
        }
    }

    private var footer: some View {
        HStack {
            Button(model.pairingQR == nil ? "Pair new device…" : "Cancel pairing") {
                model.pairingQR == nil ? model.startPairing() : model.cancelPairing()
            }
            Spacer()
            Menu {
                Toggle("Launch at login", isOn: Binding(get: { model.launchAtLogin }, set: { model.setLaunchAtLogin($0) }))
                Button("Open Downloads/Handspan") {
                    try? FileManager.default.createDirectory(at: AppModel.downloadsDir, withIntermediateDirectories: true)
                    NSWorkspace.shared.open(AppModel.downloadsDir)
                }
                Divider()
                Button("Quit Handspan") { NSApp.terminate(nil) }
            } label: {
                Image(systemName: "gearshape")
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
        }
    }
}

struct PairingView: View {
    @EnvironmentObject var model: AppModel
    let qr: NSImage

    var body: some View {
        VStack(spacing: 8) {
            Image(nsImage: qr)
                .interpolation(.none)
                .resizable()
                .frame(width: 200, height: 200)
                .accessibilityLabel("Pairing QR code")
            Text("Scan with Handspan on your phone")
                .font(.callout)
            if let expires = model.snapshot.pairing?.expiresAt {
                Text("Expires \(expires, style: .relative)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
    }
}

struct DeviceRow: View {
    @EnvironmentObject var model: AppModel
    let status: HostService.DeviceStatus

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(status.online ? Color.green : Color.secondary.opacity(0.4))
                .frame(width: 8, height: 8)
                .accessibilityLabel(status.online ? "Online" : "Offline")
            VStack(alignment: .leading, spacing: 1) {
                Text(status.device.name)
                Text(status.online ? "Connected" : "Not connected")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                model.chooseFilesAndSend(to: status.id)
            } label: {
                Image(systemName: "paperplane")
            }
            .help("Send files…")
            .disabled(!status.online)
            Menu {
                Button("Send clipboard") { model.sendClipboard(to: status.id) }
                    .disabled(!status.online)
                Toggle("Accept files automatically", isOn: Binding(
                    get: { status.device.autoAccept },
                    set: { model.setAutoAccept($0, for: status.id) }
                ))
                Divider()
                Button("Unpair", role: .destructive) { model.unpair(status.id) }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
        }
    }
}

struct TransferRow: View {
    @EnvironmentObject var model: AppModel
    let transfer: TransferProgress

    var body: some View {
        HStack {
            Image(systemName: transfer.direction == .incoming ? "arrow.down.circle" : "arrow.up.circle")
            VStack(alignment: .leading, spacing: 2) {
                Text(transfer.title).lineLimit(1).truncationMode(.middle)
                switch transfer.status {
                case .waiting:
                    Text("Waiting for the device…").font(.caption).foregroundStyle(.secondary)
                case .active:
                    ProgressView(value: Double(transfer.bytesDone), total: Double(max(transfer.bytesTotal, 1)))
                        .controlSize(.small)
                case .done:
                    Text("Done").font(.caption).foregroundStyle(.secondary)
                case let .failed(reason):
                    Text(reason).font(.caption).foregroundStyle(.red)
                }
            }
            Spacer()
            if transfer.status == .active || transfer.status == .waiting {
                Button { model.cancelTransfer(transfer.id) } label: { Image(systemName: "xmark.circle") }
                    .buttonStyle(.borderless)
                    .help("Cancel")
            }
        }
    }
}

struct RecentRow: View {
    @EnvironmentObject var model: AppModel
    let item: RecentItem

    var body: some View {
        HStack {
            switch item.kind {
            case let .link(url):
                Image(systemName: "link")
                Text(url.absoluteString).lineLimit(1).truncationMode(.middle)
                Spacer()
                Button("Open") { NSWorkspace.shared.open(url) }.buttonStyle(.borderless)
            case let .text(text):
                Image(systemName: "text.alignleft")
                Text(text).lineLimit(1)
                Spacer()
                Button("Copy") { model.copy(text) }.buttonStyle(.borderless)
            case let .clipboard(text):
                Image(systemName: "doc.on.clipboard")
                Text(text).lineLimit(1).foregroundStyle(.secondary)
                Spacer()
            case let .file(url):
                Image(systemName: "doc")
                Text(url.lastPathComponent).lineLimit(1).truncationMode(.middle)
                Spacer()
                Button("Show") { NSWorkspace.shared.activateFileViewerSelecting([url]) }.buttonStyle(.borderless)
            }
        }
        .font(.callout)
        .help("From \(item.from)")
    }
}
