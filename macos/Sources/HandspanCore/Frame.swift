import Foundation

/// Frame types from PROTOCOL §5. Unknown raw values are kept so they can be ignored.
public enum FrameType: UInt8 {
    case hello = 0x01
    case ping = 0x02
    case pong = 0x03
    case pairRequest = 0x04
    case pairResult = 0x05
    case clip = 0x10
    case offer = 0x20
    case accept = 0x21
    case decline = 0x22
    case chunk = 0x23
    case fileDone = 0x24
    case verified = 0x25
    case hashFail = 0x26
    case cancel = 0x27
    case unpaired = 0x30
}

public struct Frame: Equatable {
    public static let maxPayload = 8 * 1024 * 1024
    public static let maxChunkData = 256 * 1024

    public let rawType: UInt8
    public let payload: Data

    public var type: FrameType? { FrameType(rawValue: rawType) }

    public init(rawType: UInt8, payload: Data) {
        self.rawType = rawType
        self.payload = payload
    }

    public init<T: Encodable>(_ type: FrameType, _ message: T) {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        self.init(rawType: type.rawValue, payload: try! encoder.encode(message))
    }

    public static func empty(_ type: FrameType) -> Frame {
        Frame(rawType: type.rawValue, payload: Data("{}".utf8))
    }

    public func decode<T: Decodable>(_: T.Type) throws -> T {
        try JSONDecoder().decode(T.self, from: payload)
    }

    public func encoded() -> Data {
        var data = Data(capacity: 5 + payload.count)
        var length = UInt32(1 + payload.count).bigEndian
        withUnsafeBytes(of: &length) { data.append(contentsOf: $0) }
        data.append(rawType)
        data.append(payload)
        return data
    }
}

public enum ProtocolError: Error, Equatable {
    case frameTooLarge(Int)
    case emptyFrame
    case malformed(String)
}

/// Accumulates stream bytes and yields whole frames.
public struct FrameDecoder {
    private var buffer = Data()

    public init() {}

    public mutating func append(_ data: Data) throws -> [Frame] {
        buffer.append(data)
        var frames: [Frame] = []
        while buffer.count >= 4 {
            let start = buffer.startIndex
            let length = buffer[start ..< start + 4].reduce(0) { ($0 << 8) | Int($1) }
            guard length >= 1 else { throw ProtocolError.emptyFrame }
            guard length <= 1 + Frame.maxPayload else { throw ProtocolError.frameTooLarge(length) }
            guard buffer.count >= 4 + length else { break }
            let type = buffer[start + 4]
            let payload = Data(buffer[start + 5 ..< start + 4 + length])
            frames.append(Frame(rawType: type, payload: payload))
            buffer.removeSubrange(start ..< start + 4 + length)
        }
        return frames
    }
}

/// CHUNK payload: xferId(16) ‖ fileId(16) ‖ offset u64 BE ‖ data.
public struct Chunk: Equatable {
    public let xferId: UUID
    public let fileId: UUID
    public let offset: UInt64
    public let data: Data

    public init(xferId: UUID, fileId: UUID, offset: UInt64, data: Data) {
        self.xferId = xferId
        self.fileId = fileId
        self.offset = offset
        self.data = data
    }

    public var frame: Frame {
        var payload = Data(capacity: 40 + data.count)
        payload.append(xferId.bytes)
        payload.append(fileId.bytes)
        var be = offset.bigEndian
        withUnsafeBytes(of: &be) { payload.append(contentsOf: $0) }
        payload.append(data)
        return Frame(rawType: FrameType.chunk.rawValue, payload: payload)
    }

    public init(frame: Frame) throws {
        let p = frame.payload
        guard p.count > 40, p.count - 40 <= Frame.maxChunkData else { throw ProtocolError.malformed("chunk size") }
        let s = p.startIndex
        xferId = UUID(bytes: p[s ..< s + 16])
        fileId = UUID(bytes: p[s + 16 ..< s + 32])
        offset = p[s + 32 ..< s + 40].reduce(0) { ($0 << 8) | UInt64($1) }
        data = Data(p[(s + 40)...])
    }
}

extension UUID {
    var bytes: Data {
        withUnsafeBytes(of: uuid) { Data($0) }
    }

    init(bytes: Data) {
        var raw: uuid_t = (0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        withUnsafeMutableBytes(of: &raw) { $0.copyBytes(from: bytes) }
        self.init(uuid: raw)
    }

    /// Lowercase canonical form used on the wire.
    public var wire: String { uuidString.lowercased() }
}
