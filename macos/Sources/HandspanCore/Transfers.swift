import Foundation

public struct TransferProgress: Equatable, Identifiable {
    public enum Direction: String { case outgoing, incoming }
    public enum Status: Equatable { case waiting, active, done, failed(String) }

    public var id: String
    public var peerId: String
    public var direction: Direction
    public var title: String
    public var bytesDone: Int64
    public var bytesTotal: Int64
    public var status: Status
}

/// A file offer we are sending. It lives in memory so it can resume after a reconnect.
final class OutgoingTransfer {
    enum State { case awaitingAnswer, sending, done, cancelled }

    let offer: Offer
    let peerId: String
    let sources: [String: URL]
    var state: State = .awaitingAnswer
    var queue: [(fileId: String, offset: Int64)] = []
    var verified: Set<String> = []
    var retried: Set<String> = []
    var offeredAt = Date()
    /// Bumped whenever streaming must stop (reconnect, cancel) so stale send loops exit.
    var generation = 0

    init(offer: Offer, peerId: String, sources: [String: URL]) {
        self.offer = offer
        self.peerId = peerId
        self.sources = sources
    }

    var totalBytes: Int64 { offer.files.reduce(0) { $0 + ($1.size ?? 0) } }
    var verifiedBytes: Int64 { offer.files.filter { verified.contains($0.fileId) }.reduce(0) { $0 + ($1.size ?? 0) } }
    var title: String {
        let files = offer.files
        return files.count == 1 ? (files[0].name ?? "file") : "\(files.count) files"
    }

    func item(_ fileId: String) -> OfferItem? { offer.items.first { $0.fileId == fileId } }
}

/// Receiver-side state: one directory per transfer under `partsDir`, holding
/// `state.json` and `<fileId>.part`. Persisted so an accepted transfer resumes
/// without asking the user again (PROTOCOL §8).
final class IncomingStore {
    struct State: Codable {
        var offer: Offer
        var peerId: String
        var accepted: [String]
        var verified: [String]
        var createdAt: Date
    }

    enum Finish {
        case verified(URL)
        case hashFail
    }

    enum StoreError: Error { case unknownFile, unexpectedOffset(expected: UInt64, got: UInt64) }

    let partsDir: URL
    let downloadsDir: URL
    private let fm = FileManager.default
    private var states: [String: State] = [:]
    private var openHandles: [String: (handle: FileHandle, size: UInt64)] = [:]

    init(downloadsDir: URL, partsDir: URL) {
        self.downloadsDir = downloadsDir
        self.partsDir = partsDir
        try? fm.createDirectory(at: partsDir, withIntermediateDirectories: true)
        load()
    }

    func state(_ xferId: String) -> State? { states[xferId] }

    func accept(_ offer: Offer, from peerId: String, files: [String]) throws {
        let state = State(offer: offer, peerId: peerId, accepted: files, verified: [], createdAt: Date())
        try fm.createDirectory(at: dir(offer.xferId), withIntermediateDirectories: true)
        states[offer.xferId] = state
        try save(state)
    }

    /// Start offsets for accepted, unverified files: the current part-file length.
    func offsets(_ xferId: String) -> [String: Int64] {
        guard let state = states[xferId] else { return [:] }
        var result: [String: Int64] = [:]
        for fileId in state.accepted where !state.verified.contains(fileId) {
            closeHandle(xferId, fileId)
            let size = (try? fm.attributesOfItem(atPath: part(xferId, fileId).path)[.size] as? NSNumber)?.int64Value ?? 0
            result[fileId] = size
        }
        return result
    }

    func write(_ chunk: Chunk) throws {
        let xferId = chunk.xferId.wire, fileId = chunk.fileId.wire
        guard let state = states[xferId], state.accepted.contains(fileId), !state.verified.contains(fileId) else {
            throw StoreError.unknownFile
        }
        let key = xferId + "/" + fileId
        var entry = try openHandles[key] ?? {
            let url = part(xferId, fileId)
            if !fm.fileExists(atPath: url.path) { fm.createFile(atPath: url.path, contents: nil) }
            let handle = try FileHandle(forWritingTo: url)
            return (handle, try handle.seekToEnd())
        }()
        guard chunk.offset == entry.size else {
            openHandles[key] = entry
            throw StoreError.unexpectedOffset(expected: entry.size, got: chunk.offset)
        }
        try entry.handle.write(contentsOf: chunk.data)
        entry.size += UInt64(chunk.data.count)
        openHandles[key] = entry
    }

    func receivedBytes(_ xferId: String) -> Int64 {
        guard let state = states[xferId] else { return 0 }
        return state.offer.files.filter { state.accepted.contains($0.fileId) }.reduce(Int64(0)) { sum, item in
            if state.verified.contains(item.fileId) { return sum + (item.size ?? 0) }
            if let open = openHandles[xferId + "/" + item.fileId] { return sum + Int64(open.size) }
            return sum
        }
    }

    /// Checks size and SHA-256 of a finished part file and moves it into Downloads.
    /// The hash runs on a background queue; `completion` runs on `queue`.
    func finish(_ xferId: String, _ fileId: String, queue: DispatchQueue, completion: @escaping (Result<Finish, Error>) -> Void) {
        guard let state = states[xferId], let item = state.offer.items.first(where: { $0.fileId == fileId }),
              state.accepted.contains(fileId)
        else { return completion(.failure(StoreError.unknownFile)) }
        closeHandle(xferId, fileId)
        let url = part(xferId, fileId)
        if !fm.fileExists(atPath: url.path) { fm.createFile(atPath: url.path, contents: nil) }
        DispatchQueue.global(qos: .utility).async {
            let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value
            let hash = try? Hashing.sha256Hex(fileAt: url)
            queue.async {
                guard size == item.size, hash == item.sha256?.lowercased() else {
                    try? self.fm.removeItem(at: url)
                    return completion(.success(.hashFail))
                }
                do {
                    try self.fm.createDirectory(at: self.downloadsDir, withIntermediateDirectories: true)
                    let target = FileNames.unique(FileNames.sanitize(item.name ?? "file"), in: self.downloadsDir)
                    try self.fm.moveItem(at: url, to: target)
                    var updated = state
                    updated.verified.append(fileId)
                    self.states[xferId] = updated
                    try self.save(updated)
                    if Set(updated.verified) == Set(updated.accepted) { self.remove(xferId) }
                    completion(.success(.verified(target)))
                } catch {
                    completion(.failure(error))
                }
            }
        }
    }

    func isComplete(_ xferId: String) -> Bool { states[xferId] == nil }

    func remove(_ xferId: String) {
        for key in openHandles.keys where key.hasPrefix(xferId + "/") {
            try? openHandles[key]?.handle.close()
            openHandles[key] = nil
        }
        states[xferId] = nil
        try? fm.removeItem(at: dir(xferId))
    }

    func purge(olderThan age: TimeInterval) {
        let cutoff = Date().addingTimeInterval(-age)
        for (id, state) in states where state.createdAt < cutoff { remove(id) }
    }

    private func closeHandle(_ xferId: String, _ fileId: String) {
        let key = xferId + "/" + fileId
        try? openHandles[key]?.handle.close()
        openHandles[key] = nil
    }

    private func dir(_ xferId: String) -> URL { partsDir.appendingPathComponent(xferId, isDirectory: true) }
    private func part(_ xferId: String, _ fileId: String) -> URL { dir(xferId).appendingPathComponent(fileId + ".part") }

    private func save(_ state: State) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(state).write(to: dir(state.offer.xferId).appendingPathComponent("state.json"), options: .atomic)
    }

    private func load() {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        for dir in (try? fm.contentsOfDirectory(at: partsDir, includingPropertiesForKeys: nil)) ?? [] {
            guard let data = try? Data(contentsOf: dir.appendingPathComponent("state.json")),
                  let state = try? decoder.decode(State.self, from: data),
                  state.offer.xferId == dir.lastPathComponent
            else {
                try? fm.removeItem(at: dir)
                continue
            }
            states[state.offer.xferId] = state
        }
    }
}
