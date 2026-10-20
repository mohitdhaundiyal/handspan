import Foundation

public struct PairedDevice: Codable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var platform: String
    public var pairedAt: Date
    public var autoAccept: Bool
}

/// Paired devices as a JSON file. Only touched from the host queue.
final class PairedStore {
    private let url: URL
    private(set) var devices: [PairedDevice] = []

    init(url: URL) {
        self.url = url
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        if let data = try? Data(contentsOf: url), let list = try? decoder.decode([PairedDevice].self, from: data) {
            devices = list
        }
    }

    func device(_ id: String) -> PairedDevice? { devices.first { $0.id == id } }

    func upsert(_ device: PairedDevice) {
        devices.removeAll { $0.id == device.id }
        devices.append(device)
        save()
    }

    func remove(_ id: String) {
        devices.removeAll { $0.id == id }
        save()
    }

    private func save() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? encoder.encode(devices).write(to: url, options: .atomic)
    }
}
