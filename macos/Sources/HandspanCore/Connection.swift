import Foundation
import Network
import Security

enum TLS {
    /// Mutual TLS 1.3 options. `admit` sees the peer's device ID inside the handshake;
    /// returning false aborts it before any application data is exchanged (PROTOCOL §4).
    static func options(identity: DeviceIdentity, queue: DispatchQueue, admit: @escaping (String) -> Bool) -> NWProtocolTLS.Options {
        let options = NWProtocolTLS.Options()
        let sec = options.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv13)
        sec_protocol_options_set_local_identity(sec, sec_identity_create(identity.secIdentity)!)
        sec_protocol_options_set_peer_authentication_required(sec, true)
        sec_protocol_options_set_verify_block(sec, { _, trust, complete in
            let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate],
                  let leaf = chain.first,
                  let peerId = try? DeviceID.of(certificate: leaf)
            else { return complete(false) }
            complete(admit(peerId))
        }, queue)
        return options
    }

    static func parameters(identity: DeviceIdentity, queue: DispatchQueue, admit: @escaping (String) -> Bool) -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let params = NWParameters(tls: options(identity: identity, queue: queue, admit: admit), tcp: tcp)
        params.includePeerToPeer = false
        return params
    }

    /// The device ID of the certificate the peer presented on an established connection.
    static func peerDeviceId(of connection: NWConnection) -> String? {
        guard let metadata = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata else { return nil }
        var leaf: SecCertificate?
        sec_protocol_metadata_access_peer_certificate_chain(metadata.securityProtocolMetadata) { cert in
            if leaf == nil { leaf = sec_certificate_copy_ref(cert).takeRetainedValue() }
        }
        return leaf.flatMap { try? DeviceID.of(certificate: $0) }
    }
}

/// A framed, TLS-authenticated connection. All callbacks run on `queue`.
public final class FramedConnection {
    public private(set) var peerId: String?
    public var onReady: (() -> Void)?
    public var onFrame: ((Frame) -> Void)?
    public var onClose: ((Error?) -> Void)?
    public private(set) var lastReceived = Date()

    let connection: NWConnection
    private let queue: DispatchQueue
    private var decoder = FrameDecoder()
    private var closed = false

    init(connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
    }

    /// Dials a peer whose device ID must equal `expectedPeerId`.
    public static func dial(host: String, port: UInt16, identity: DeviceIdentity, expectedPeerId: String, queue: DispatchQueue) -> FramedConnection {
        let params = TLS.parameters(identity: identity, queue: queue) { $0 == expectedPeerId }
        let nw = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: params)
        return FramedConnection(connection: nw, queue: queue)
    }

    public func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                peerId = TLS.peerDeviceId(of: connection)
                lastReceived = Date()
                onReady?()
                receive()
            case let .failed(error):
                finish(error)
                connection.cancel()
            case let .waiting(error):
                // TLS rejections and refused connections land here; don't wait for a path change.
                finish(error)
                connection.cancel()
            case .cancelled:
                finish(nil)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    public func send(_ frame: Frame, completion: ((Error?) -> Void)? = nil) {
        guard !closed else { completion?(ProtocolError.malformed("closed")); return }
        connection.send(content: frame.encoded(), completion: .contentProcessed { error in
            completion?(error)
        })
    }

    public func close() {
        guard !closed else { return }
        connection.cancel()
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 18) { [weak self] data, _, isComplete, error in
            guard let self, !closed else { return }
            if let data, !data.isEmpty {
                lastReceived = Date()
                do {
                    for frame in try decoder.append(data) {
                        guard !closed else { return }
                        onFrame?(frame)
                    }
                } catch {
                    finish(error)
                    connection.cancel()
                    return
                }
            }
            if isComplete || error != nil {
                finish(error)
                connection.cancel()
            } else {
                receive()
            }
        }
    }

    private func finish(_ error: Error?) {
        guard !closed else { return }
        closed = true
        onClose?(error)
        onFrame = nil
        onReady = nil
        onClose = nil
    }
}
