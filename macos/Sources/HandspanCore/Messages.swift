import Foundation

// JSON payloads from PROTOCOL §5. UUIDs travel as lowercase strings.

public struct Hello: Codable, Equatable {
    public var v: Int
    public var id: String
    public var name: String
    public var platform: String
    public var paired: Bool?
}

public struct PairRequest: Codable, Equatable {
    public var name: String
    public var proof: String
}

public struct PairResult: Codable, Equatable {
    public var ok: Bool
    public var name: String?
    public var reason: String?
}

public struct Clip: Codable, Equatable {
    public var id: String
    public var origin: String
    public var mime: String
    public var text: String
    public var sha256: String

    public init(id: String = UUID().wire, origin: String, text: String) {
        self.id = id
        self.origin = origin
        mime = "text/plain"
        self.text = text
        sha256 = Hashing.sha256Hex(Data(text.utf8))
    }

    public var isIntact: Bool { Hashing.sha256Hex(Data(text.utf8)) == sha256 }
}

public enum ItemKind: String, Codable {
    case file, text, url
}

public struct OfferItem: Codable, Equatable {
    public var fileId: String
    public var kind: ItemKind
    public var name: String?
    public var size: Int64?
    public var mime: String?
    public var sha256: String?
    public var text: String?

    public static func file(name: String, size: Int64, mime: String, sha256: String) -> OfferItem {
        OfferItem(fileId: UUID().wire, kind: .file, name: name, size: size, mime: mime, sha256: sha256)
    }

    public static func text(_ text: String) -> OfferItem {
        let isURL = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)).map { ["http", "https"].contains($0.scheme?.lowercased()) && $0.host != nil } ?? false
        return OfferItem(fileId: UUID().wire, kind: isURL ? .url : .text, text: text)
    }
}

public struct Offer: Codable, Equatable {
    public var xferId: String
    public var items: [OfferItem]

    public var files: [OfferItem] { items.filter { $0.kind == .file } }
}

public struct Accept: Codable, Equatable {
    public var xferId: String
    public var files: [String: Int64]
}

public struct TransferRef: Codable, Equatable {
    public var xferId: String
}

public struct FileRef: Codable, Equatable {
    public var xferId: String
    public var fileId: String
}

public struct Cancel: Codable, Equatable {
    public var xferId: String
    public var reason: String
}
