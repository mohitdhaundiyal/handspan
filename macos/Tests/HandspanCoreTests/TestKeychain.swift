import Foundation
import Security

/// A throwaway file keychain so tests never touch the login keychain.
final class TestKeychain {
    let keychain: SecKeychain
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("handspan-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var kc: SecKeychain?
        let password = "test"
        let status = SecKeychainCreate(dir.appendingPathComponent("test.keychain").path, UInt32(password.utf8.count), password, false, nil, &kc)
        guard status == errSecSuccess, let kc else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        keychain = kc
    }

    deinit {
        SecKeychainDelete(keychain)
        try? FileManager.default.removeItem(at: dir)
    }
}
