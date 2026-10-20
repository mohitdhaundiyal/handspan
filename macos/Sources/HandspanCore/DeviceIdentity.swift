import CryptoKit
import Foundation
import Security

public enum IdentityError: Error, CustomStringConvertible {
    case keychain(OSStatus, String)
    case unsupportedKey
    case noIdentity

    public var description: String {
        switch self {
        case let .keychain(status, step): return "Keychain error \(status) during \(step)"
        case .unsupportedKey: return "Peer key is not P-256"
        case .noIdentity: return "Could not pair the certificate with its private key"
        }
    }
}

/// Device IDs are lowercase hex SHA-256 of the P-256 SubjectPublicKeyInfo (PROTOCOL §1).
public enum DeviceID {
    static let p256SPKIPrefix: [UInt8] = [
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    ]

    public static func spki(fromRawPoint point: Data) -> Data {
        Data(p256SPKIPrefix) + point
    }

    public static func fromSPKI(_ spki: Data) -> String {
        Hex.encode(Data(SHA256.hash(data: spki)))
    }

    public static func of(publicKey: SecKey) throws -> String {
        var error: Unmanaged<CFError>?
        guard let point = SecKeyCopyExternalRepresentation(publicKey, &error) as Data?,
              point.count == 65, point.first == 0x04
        else { throw IdentityError.unsupportedKey }
        return fromSPKI(spki(fromRawPoint: point))
    }

    public static func of(certificate: SecCertificate) throws -> String {
        guard let key = SecCertificateCopyKey(certificate) else { throw IdentityError.unsupportedKey }
        return try of(publicKey: key)
    }
}

/// This device's long-lived P-256 key and self-signed certificate, kept in a keychain.
public final class DeviceIdentity {
    public let privateKey: SecKey
    public let certificate: SecCertificate
    public let secIdentity: SecIdentity
    public let deviceId: String

    private init(privateKey: SecKey, certificate: SecCertificate, secIdentity: SecIdentity) throws {
        self.privateKey = privateKey
        self.certificate = certificate
        self.secIdentity = secIdentity
        deviceId = try DeviceID.of(certificate: certificate)
    }

    /// Loads the identity stored under `label`, creating it on first use.
    /// `keychain` defaults to the user's login keychain; tests pass a throwaway one.
    public static func loadOrCreate(label: String = "app.handspan.identity", keychain: SecKeychain? = nil) throws -> DeviceIdentity {
        let tag = Data(label.utf8)
        if let key = findKey(tag: tag, keychain: keychain), let cert = findCertificate(matching: key, keychain: keychain) {
            return try DeviceIdentity(privateKey: key, certificate: cert, secIdentity: identity(for: cert, keychain: keychain))
        }
        deleteAll(label: label, tag: tag, keychain: keychain)

        var privateAttrs: [CFString: Any] = [
            kSecAttrIsPermanent: true,
            kSecAttrApplicationTag: tag,
            kSecAttrLabel: label,
        ]
        if let keychain { privateAttrs[kSecUseKeychain] = keychain }
        var keyAttrs: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits: 256,
            kSecPrivateKeyAttrs: privateAttrs,
        ]
        if let keychain { keyAttrs[kSecUseKeychain] = keychain }
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(keyAttrs as CFDictionary, &error) else {
            throw IdentityError.keychain(errSecParam, "key generation: \(error!.takeRetainedValue())")
        }

        let certDER = try makeSelfSignedCertificate(privateKey: key, commonName: "Handspan \(UUID().uuidString.prefix(8))")
        guard let cert = SecCertificateCreateWithData(nil, certDER as CFData) else {
            throw IdentityError.keychain(errSecDecode, "certificate parse")
        }
        var addQuery: [CFString: Any] = [
            kSecClass: kSecClassCertificate,
            kSecValueRef: cert,
            kSecAttrLabel: label,
        ]
        if let keychain { addQuery[kSecUseKeychain] = keychain }
        let status = SecItemAdd(addQuery as CFDictionary, nil)
        guard status == errSecSuccess else { throw IdentityError.keychain(status, "certificate store") }

        return try DeviceIdentity(privateKey: key, certificate: cert, secIdentity: identity(for: cert, keychain: keychain))
    }

    /// Removes the stored identity, e.g. for a "reset" action or test cleanup.
    public static func delete(label: String = "app.handspan.identity", keychain: SecKeychain? = nil) {
        deleteAll(label: label, tag: Data(label.utf8), keychain: keychain)
    }

    /// Builds a v3 self-signed certificate (no extensions) valid for 10 years.
    static func makeSelfSignedCertificate(privateKey: SecKey, commonName: String, now: Date = Date()) throws -> Data {
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else { throw IdentityError.unsupportedKey }
        var error: Unmanaged<CFError>?
        guard let point = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else { throw IdentityError.unsupportedKey }

        var serial = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, serial.count, &serial)
        serial[0] &= 0x7F
        serial[0] |= 0x01

        let algorithm = DER.sequence(DER.oidEcdsaWithSHA256)
        let name = DER.sequence(DER.set(DER.sequence(DER.oidCommonName, DER.utf8String(commonName))))
        let notBefore = now.addingTimeInterval(-24 * 3600)
        let notAfter = now.addingTimeInterval(10 * 365 * 24 * 3600)
        let tbs = DER.sequence(
            DER.explicit(0, DER.integer([2])),
            DER.integer(serial),
            algorithm,
            name,
            DER.sequence(DER.utcTime(notBefore), DER.utcTime(notAfter)),
            name,
            Array(DeviceID.spki(fromRawPoint: point))
        )
        guard let signature = SecKeyCreateSignature(privateKey, .ecdsaSignatureMessageX962SHA256, Data(tbs) as CFData, &error) as Data? else {
            throw IdentityError.keychain(errSecParam, "certificate signing: \(error!.takeRetainedValue())")
        }
        return Data(DER.sequence(tbs, algorithm, DER.bitString(Array(signature))))
    }

    private static func findKey(tag: Data, keychain: SecKeychain?) -> SecKey? {
        var query: [CFString: Any] = [
            kSecClass: kSecClassKey,
            kSecAttrApplicationTag: tag,
            kSecAttrKeyClass: kSecAttrKeyClassPrivate,
            kSecReturnRef: true,
        ]
        if let keychain { query[kSecMatchSearchList] = [keychain] }
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return (result as! SecKey)
    }

    /// File keychains derive a certificate's label from its subject, so certificates
    /// are matched to the key by device ID rather than by label.
    private static func findCertificate(matching key: SecKey, keychain: SecKeychain?) -> SecCertificate? {
        guard let publicKey = SecKeyCopyPublicKey(key), let keyId = try? DeviceID.of(publicKey: publicKey) else { return nil }
        var query: [CFString: Any] = [
            kSecClass: kSecClassCertificate,
            kSecMatchLimit: kSecMatchLimitAll,
            kSecReturnRef: true,
        ]
        if let keychain { query[kSecMatchSearchList] = [keychain] }
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let certs = result as? [SecCertificate] else { return nil }
        return certs.first { (try? DeviceID.of(certificate: $0)) == keyId }
    }

    private static func identity(for cert: SecCertificate, keychain: SecKeychain?) throws -> SecIdentity {
        var identity: SecIdentity?
        let status = SecIdentityCreateWithCertificate(keychain, cert, &identity)
        guard status == errSecSuccess, let identity else { throw IdentityError.noIdentity }
        return identity
    }

    private static func deleteAll(label _: String, tag: Data, keychain: SecKeychain?) {
        if let key = findKey(tag: tag, keychain: keychain), let cert = findCertificate(matching: key, keychain: keychain) {
            SecItemDelete([kSecClass: kSecClassCertificate, kSecValueRef: cert] as CFDictionary)
        }
        var keyQuery: [CFString: Any] = [kSecClass: kSecClassKey, kSecAttrApplicationTag: tag]
        if let keychain { keyQuery[kSecMatchSearchList] = [keychain] }
        SecItemDelete(keyQuery as CFDictionary)
    }
}

enum Hex {
    static func encode(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func decode(_ string: String) -> Data? {
        guard string.count % 2 == 0 else { return nil }
        var data = Data(capacity: string.count / 2)
        var index = string.startIndex
        while index < string.endIndex {
            let next = string.index(index, offsetBy: 2)
            guard let byte = UInt8(string[index ..< next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        return data
    }
}
