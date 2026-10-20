import CryptoKit
import Foundation

public enum Hashing {
    public static func sha256Hex(_ data: Data) -> String {
        Hex.encode(Data(SHA256.hash(data: data)))
    }

    /// Streams a file through SHA-256 without loading it into memory.
    public static func sha256Hex(fileAt url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let data = try handle.read(upToCount: 1 << 20), !data.isEmpty {
            hasher.update(data: data)
        }
        return Hex.encode(Data(hasher.finalize()))
    }
}

/// The QR payload and the proof/confirmation math from PROTOCOL §3 and §6.
public struct PairingInvite: Equatable {
    public var hostId: String
    public var secret: Data
    public var hosts: [String]
    public var name: String

    public init(hostId: String, secret: Data, hosts: [String], name: String) {
        self.hostId = hostId
        self.secret = secret
        self.hosts = hosts
        self.name = name
    }

    public var uri: String {
        var components = URLComponents()
        components.scheme = "handspan"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "v", value: "1"),
            URLQueryItem(name: "id", value: hostId),
            URLQueryItem(name: "k", value: Base64URL.encode(secret)),
            URLQueryItem(name: "h", value: hosts.joined(separator: ",")),
            URLQueryItem(name: "n", value: name),
        ]
        // URLComponents leaves '[' and ']' unescaped in queries; percent-encode them so
        // IPv6 hosts are encoded the same way as the Android implementation.
        components.percentEncodedQuery = components.percentEncodedQuery?
            .replacingOccurrences(of: "[", with: "%5B")
            .replacingOccurrences(of: "]", with: "%5D")
        return components.string!
    }

    public init?(uri: String) {
        guard let components = URLComponents(string: uri),
              components.scheme == "handspan", components.host == "pair"
        else { return nil }
        var query: [String: String] = [:]
        for item in components.queryItems ?? [] { query[item.name] = item.value ?? "" }
        guard query["v"] == "1",
              let id = query["id"], id.count == 64, Hex.decode(id) != nil,
              let k = query["k"].flatMap(Base64URL.decode), k.count == 32
        else { return nil }
        hostId = id
        secret = k
        hosts = (query["h"] ?? "").split(separator: ",").map(String.init)
        name = query["n"] ?? ""
    }

    public static func proof(secret: Data, hostId: String, phoneId: String) -> String {
        Hex.encode(mac(secret: secret, label: "handspan-pair-v1", hostId: hostId, phoneId: phoneId))
    }

    public static func confirmCode(secret: Data, hostId: String, phoneId: String) -> String {
        let digest = mac(secret: secret, label: "handspan-confirm-v1", hostId: hostId, phoneId: phoneId)
        let value = digest.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        return String(format: "%06u", value % 1_000_000)
    }

    private static func mac(secret: Data, label: String, hostId: String, phoneId: String) -> Data {
        var message = Data(label.utf8)
        message.append(Hex.decode(hostId) ?? Data())
        message.append(Hex.decode(phoneId) ?? Data())
        return Data(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: secret)))
    }

    static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        return zip(x, y).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }
}

enum Base64URL {
    static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func decode(_ string: String) -> Data? {
        var s = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        return Data(base64Encoded: s)
    }
}
