import Foundation
import Testing
@testable import HandspanCore

/// Records host delegate events for assertions.
final class RecordingDelegate: HostDelegate {
    let lock = NSLock()
    var snapshot = HostService.Snapshot()
    var paired: [(PairedDevice, String)] = []
    var clips: [String] = []
    var items: [OfferItem] = []
    var files: [URL] = []
    var offers: [Offer] = []
    var acceptOffers = true

    func hostDidChange(_ s: HostService.Snapshot) { lock.withLock { snapshot = s } }
    func hostDidPair(_ d: PairedDevice, code: String) { lock.withLock { paired.append((d, code)) } }
    func hostDidReceiveClipboard(_ t: String, from _: PairedDevice) { lock.withLock { clips.append(t) } }
    func hostDidReceiveItems(_ i: [OfferItem], from _: PairedDevice) { lock.withLock { items += i } }
    func hostDidReceiveFile(_ u: URL, from _: PairedDevice) { lock.withLock { files.append(u) } }
    func hostShouldAccept(_ o: Offer, from _: PairedDevice, decide: @escaping (Bool) -> Void) {
        lock.withLock { offers.append(o) }
        decide(acceptOffers)
    }

    func read<T>(_ f: (RecordingDelegate) -> T) -> T { lock.withLock { f(self) } }
}

/// A minimal phone speaking the wire protocol through the same FramedConnection.
final class PhoneSim {
    let identity: DeviceIdentity
    let queue = DispatchQueue(label: "phone-sim")
    private let lock = NSLock()
    private var inbox: [Frame] = []
    private(set) var closed = false
    private(set) var ready = false
    var conn: FramedConnection?

    init(identity: DeviceIdentity) { self.identity = identity }

    func connect(port: UInt16, hostId: String) async throws {
        let conn = FramedConnection.dial(host: "127.0.0.1", port: port, identity: identity, expectedPeerId: hostId, queue: queue)
        self.conn = conn
        conn.onReady = { [weak self] in self?.lock.withLock { self?.ready = true } }
        conn.onFrame = { [weak self] f in self?.lock.withLock { self?.inbox.append(f) } }
        conn.onClose = { [weak self] _ in self?.lock.withLock { self?.closed = true } }
        conn.start()
        try await waitUntil { self.lock.withLock { self.ready || self.closed } }
    }

    var isReady: Bool { lock.withLock { ready && !closed } }
    var isClosed: Bool { lock.withLock { closed } }

    func send(_ frame: Frame) { conn?.send(frame) }

    func hello() async throws -> Hello {
        send(Frame(.hello, Hello(v: 1, id: identity.deviceId, name: "Pixel Test", platform: "android")))
        return try await next(.hello).decode(Hello.self)
    }

    func next(_ type: FrameType, timeout: TimeInterval = 5) async throws -> Frame {
        var found: Frame?
        try await waitUntil(timeout: timeout) {
            self.lock.withLock {
                if let i = self.inbox.firstIndex(where: { $0.type == type }) { found = self.inbox.remove(at: i) }
                return found != nil
            }
        }
        return found!
    }

    func drain(_ type: FrameType) -> [Frame] {
        lock.withLock {
            let matching = inbox.filter { $0.type == type }
            inbox.removeAll { $0.type == type }
            return matching
        }
    }

    func close() { conn?.close() }
}

struct Timeout: Error {}

func waitUntil(timeout: TimeInterval = 5, _ condition: @escaping () -> Bool) async throws {
    let deadline = Date().addingTimeInterval(timeout)
    while !condition() {
        guard Date() < deadline else { throw Timeout() }
        try await Task.sleep(nanoseconds: 20_000_000)
    }
}

@Suite(.serialized) final class HostIntegrationTests {
    let hostKeychain: TestKeychain
    let phoneKeychain: TestKeychain
    let dir: URL
    let delegate = RecordingDelegate()
    let host: HostService
    let phone: PhoneSim
    var port: UInt16 = 0

    init() async throws {
        hostKeychain = try TestKeychain()
        phoneKeychain = try TestKeychain()
        dir = hostKeychain.dir
        var config = HostService.Config(name: "Test Mac", downloadsDir: dir.appendingPathComponent("Downloads"),
                                        stateDir: dir.appendingPathComponent("State"), keychain: hostKeychain.keychain)
        config.preferredPort = 0
        config.advertise = false
        host = try HostService(config: config)
        host.delegate = delegate
        phone = PhoneSim(identity: try DeviceIdentity.loadOrCreate(label: "phone", keychain: phoneKeychain.keychain))
        host.start()
        try await waitUntil { self.host.snapshot().port != nil }
        port = host.snapshot().port!
    }

    deinit { host.stop() }

    private func pair() async throws {
        let invite = host.openPairingWindow()
        try await phone.connect(port: port, hostId: host.identity.deviceId)
        #expect(try await phone.hello().paired == false)
        let proof = PairingInvite.proof(secret: invite.secret, hostId: invite.hostId, phoneId: phone.identity.deviceId)
        phone.send(Frame(.pairRequest, PairRequest(name: "Pixel Test", proof: proof)))
        #expect(try await phone.next(.pairResult).decode(PairResult.self).ok)
        try await waitUntil { self.delegate.read { !$0.paired.isEmpty } }
    }

    private func reconnect() async throws {
        phone.close()
        try await waitUntil { self.phone.isClosed }
        try await phone.connect(port: port, hostId: host.identity.deviceId)
        #expect(try await phone.hello().paired == true)
    }

    /// In TLS 1.3 the client may report "ready" before the server's verdict on its
    /// certificate arrives, so assert on the outcome: closed, and never answered.
    private func expectRejected() async throws {
        try await phone.connect(port: port, hostId: host.identity.deviceId)
        phone.send(Frame(.hello, Hello(v: 1, id: phone.identity.deviceId, name: "x", platform: "android")))
        try await waitUntil { self.phone.isClosed }
        #expect(phone.drain(.hello).isEmpty)
    }

    @Test func unpairedPhoneIsRejectedDuringHandshake() async throws {
        try await expectRejected()
        #expect(host.snapshot().devices.isEmpty)
    }

    @Test func phoneRejectsWrongHostId() async throws {
        _ = host.openPairingWindow()
        try await phone.connect(port: port, hostId: String(repeating: "0", count: 64))
        #expect(!phone.isReady)
    }

    @Test func badProofIsRefused() async throws {
        _ = host.openPairingWindow()
        try await phone.connect(port: port, hostId: host.identity.deviceId)
        _ = try await phone.hello()
        phone.send(Frame(.pairRequest, PairRequest(name: "x", proof: String(repeating: "a", count: 64))))
        let result = try await phone.next(.pairResult).decode(PairResult.self)
        #expect(result.ok == false && result.reason == "bad_proof")
        try await waitUntil { self.phone.isClosed }
        #expect(host.snapshot().devices.isEmpty)
    }

    @Test func pairsAndShowsMatchingCode() async throws {
        try await pair()
        let (device, code) = delegate.read { $0.paired[0] }
        #expect(device.id == phone.identity.deviceId)
        #expect(code.count == 6)
        try await waitUntil { self.delegate.read { $0.snapshot.devices.first?.online == true } }
        #expect(host.snapshot().pairing == nil)
        try await reconnect()
    }

    @Test func clipboardSyncsBothWaysWithoutEcho() async throws {
        try await pair()
        phone.send(Frame(.clip, Clip(origin: phone.identity.deviceId, text: "from phone")))
        try await waitUntil { self.delegate.read { $0.clips == ["from phone"] } }

        host.localClipboardChanged("from phone") // the watcher sees our own write: must not echo
        host.localClipboardChanged("from mac")
        let clip = try await phone.next(.clip).decode(Clip.self)
        #expect(clip.text == "from mac" && clip.origin == host.identity.deviceId)
        try await Task.sleep(nanoseconds: 200_000_000)
        #expect(phone.drain(.clip).isEmpty)
    }

    @Test func textAndLinkItemsArriveWithoutPrompt() async throws {
        try await pair()
        phone.send(Frame(.offer, Offer(xferId: UUID().wire, items: [.text("https://example.com/a"), .text("hello")])))
        try await waitUntil { self.delegate.read { $0.items.count == 2 } }
        #expect(delegate.read { $0.items.map(\.kind) } == [.url, .text])
        #expect(delegate.read { $0.offers.isEmpty })
    }

    @Test func receivesFileAndResumesAfterDisconnect() async throws {
        try await pair()
        let content = Data((0 ..< 700_000).map { UInt8(truncatingIfNeeded: $0 &* 31) })
        let item = OfferItem.file(name: "../photo.jpg", size: Int64(content.count), mime: "image/jpeg", sha256: Hashing.sha256Hex(content))
        let offer = Offer(xferId: UUID().wire, items: [item])
        let xfer = UUID(uuidString: offer.xferId)!, file = UUID(uuidString: item.fileId)!

        phone.send(Frame(.offer, offer))
        #expect(try await phone.next(.accept).decode(Accept.self).files == [item.fileId: 0])
        phone.send(Chunk(xferId: xfer, fileId: file, offset: 0, data: content.prefix(Frame.maxChunkData)).frame)
        try await Task.sleep(nanoseconds: 200_000_000)

        try await reconnect()
        phone.send(Frame(.offer, offer))
        let resumed = try await phone.next(.accept).decode(Accept.self)
        #expect(resumed.files == [item.fileId: Int64(Frame.maxChunkData)])
        #expect(delegate.read { $0.offers.count } == 1, "resume must not prompt again")

        var offset = Frame.maxChunkData
        while offset < content.count {
            let end = min(offset + Frame.maxChunkData, content.count)
            phone.send(Chunk(xferId: xfer, fileId: file, offset: UInt64(offset), data: content.subdata(in: offset ..< end)).frame)
            offset = end
        }
        phone.send(Frame(.fileDone, FileRef(xferId: offer.xferId, fileId: item.fileId)))
        _ = try await phone.next(.verified)
        try await waitUntil { self.delegate.read { $0.files.count == 1 } }
        let saved = delegate.read { $0.files[0] }
        #expect(saved.lastPathComponent == "photo.jpg")
        #expect(try Data(contentsOf: saved) == content)
        try await waitUntil { self.delegate.read { $0.snapshot.transfers.first?.status == .done } }
    }

    @Test func corruptFileGetsHashFail() async throws {
        try await pair()
        let item = OfferItem.file(name: "a.bin", size: 4, mime: "application/octet-stream", sha256: Hashing.sha256Hex(Data([1, 2, 3, 4])))
        let offer = Offer(xferId: UUID().wire, items: [item])
        phone.send(Frame(.offer, offer))
        _ = try await phone.next(.accept)
        phone.send(Chunk(xferId: UUID(uuidString: offer.xferId)!, fileId: UUID(uuidString: item.fileId)!, offset: 0, data: Data([9, 9, 9, 9])).frame)
        phone.send(Frame(.fileDone, FileRef(xferId: offer.xferId, fileId: item.fileId)))
        _ = try await phone.next(.hashFail)
    }

    @Test func declinedOfferIsDeclined() async throws {
        delegate.acceptOffers = false
        try await pair()
        let offer = Offer(xferId: UUID().wire, items: [.file(name: "x", size: 1, mime: "a/b", sha256: Hashing.sha256Hex(Data([0])))])
        phone.send(Frame(.offer, offer))
        #expect(try await phone.next(.decline).decode(TransferRef.self).xferId == offer.xferId)
    }

    @Test func sendsFileToPhoneAndResumes() async throws {
        try await pair()
        let source = dir.appendingPathComponent("outgoing.bin")
        let content = Data((0 ..< 600_000).map { UInt8(truncatingIfNeeded: $0 &* 7) })
        try content.write(to: source)
        host.sendFiles([source], to: phone.identity.deviceId)

        let offer = try await phone.next(.offer).decode(Offer.self)
        let item = try #require(offer.files.first)
        #expect(item.size == Int64(content.count) && item.sha256 == Hashing.sha256Hex(content))
        phone.send(Frame(.accept, Accept(xferId: offer.xferId, files: [item.fileId: 0])))
        let first = try Chunk(frame: try await phone.next(.chunk))
        #expect(first.offset == 0)

        try await reconnect()
        _ = phone.drain(.chunk)
        let again = try await phone.next(.offer).decode(Offer.self)
        #expect(again == offer, "the same offer is re-sent after reconnect")
        let resumeAt = Int64(first.data.count)
        phone.send(Frame(.accept, Accept(xferId: offer.xferId, files: [item.fileId: resumeAt])))

        var received = first.data
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            let chunks = phone.drain(.chunk)
            for frame in chunks {
                let chunk = try Chunk(frame: frame)
                #expect(chunk.offset == UInt64(received.count))
                received += chunk.data
            }
            if !chunks.isEmpty { continue }
            if received.count == content.count, !phone.drain(.fileDone).isEmpty { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        #expect(received == content, "got \(received.count) of \(content.count)")
        phone.send(Frame(.verified, FileRef(xferId: offer.xferId, fileId: item.fileId)))
        try await waitUntil { self.delegate.read { $0.snapshot.transfers.first?.status == .done } }
    }

    @Test func unpairFromHostNotifiesPhone() async throws {
        try await pair()
        host.unpair(phone.identity.deviceId)
        _ = try await phone.next(.unpaired)
        try await waitUntil { self.phone.isClosed }
        try await expectRejected()
    }

    /// Switching to another macOS account suspends the host; switching back resumes it
    /// without pairing again.
    @Test func suspendDropsConnectionsUntilResumed() async throws {
        try await pair()
        try await waitUntil { self.host.snapshot().devices.first?.online == true }

        host.suspend()
        try await waitUntil { self.phone.isClosed }
        let suspended = host.snapshot()
        #expect(suspended.suspended && suspended.port == nil)
        #expect(suspended.devices.map(\.online) == [false])
        let probe = PhoneSim(identity: phone.identity)
        try await probe.connect(port: port, hostId: host.identity.deviceId)
        #expect(!probe.isReady)

        host.resume()
        try await waitUntil { self.host.snapshot().port != nil }
        port = host.snapshot().port!
        try await phone.connect(port: port, hostId: host.identity.deviceId)
        #expect(try await phone.hello().paired == true)
        #expect(!host.snapshot().suspended)
    }
}
