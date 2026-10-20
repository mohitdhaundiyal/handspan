import Foundation
import Security
import Testing
@testable import HandspanCore

@Suite struct IdentityTests {
    @Test func createsAndReloadsIdentity() throws {
        let kc = try TestKeychain()
        let first = try DeviceIdentity.loadOrCreate(label: "test.identity", keychain: kc.keychain)
        let again = try DeviceIdentity.loadOrCreate(label: "test.identity", keychain: kc.keychain)
        #expect(first.deviceId.count == 64)
        #expect(first.deviceId == again.deviceId)
        #expect(try DeviceID.of(publicKey: SecKeyCopyPublicKey(first.privateKey)!) == first.deviceId)
    }

    @Test func certificateIsSelfSignedAndVerifies() throws {
        let kc = try TestKeychain()
        let identity = try DeviceIdentity.loadOrCreate(label: "test.identity", keychain: kc.keychain)
        var trust: SecTrust?
        #expect(SecTrustCreateWithCertificates(identity.certificate, SecPolicyCreateBasicX509(), &trust) == errSecSuccess)
        SecTrustSetAnchorCertificates(trust!, [identity.certificate] as CFArray)
        var error: CFError?
        #expect(SecTrustEvaluateWithError(trust!, &error), "\(String(describing: error))")
    }
}
