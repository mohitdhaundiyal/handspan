import Foundation
import Network
import Security
import UniformTypeIdentifiers

/// UI-facing events. Every method is called on the main queue.
public protocol HostDelegate: AnyObject {
    func hostDidChange(_ snapshot: HostService.Snapshot)
    func hostDidPair(_ device: PairedDevice, code: String)
    func hostDidReceiveClipboard(_ text: String, from device: PairedDevice)
    func hostDidReceiveItems(_ items: [OfferItem], from device: PairedDevice)
    func hostShouldAccept(_ offer: Offer, from device: PairedDevice, decide: @escaping (Bool) -> Void)
    func hostDidReceiveFile(_ url: URL, from device: PairedDevice)
    func hostLog(_ message: String)
}

public extension HostDelegate {
    func hostDidPair(_: PairedDevice, code _: String) {}
    func hostDidReceiveClipboard(_: String, from _: PairedDevice) {}
    func hostDidReceiveItems(_: [OfferItem], from _: PairedDevice) {}
    func hostShouldAccept(_: Offer, from _: PairedDevice, decide: @escaping (Bool) -> Void) { decide(false) }
    func hostDidReceiveFile(_: URL, from _: PairedDevice) {}
    func hostLog(_: String) {}
}

/// The Mac side of Handspan: listens, advertises over Bonjour, owns pairing,
/// syncs the clipboard and moves files. All state lives on `queue`.
public final class HostService {
    public struct Config {
        public var name: String
        public var downloadsDir: URL
        public var stateDir: URL
        public var keychain: SecKeychain?
        public var preferredPort: UInt16 = 53999
        public var advertise = true
        public var pairingTTL: TimeInterval = 120

        public init(name: String, downloadsDir: URL, stateDir: URL, keychain: SecKeychain? = nil) {
            self.name = name
            self.downloadsDir = downloadsDir
            self.stateDir = stateDir
            self.keychain = keychain
        }
    }

    public struct DeviceStatus: Equatable, Identifiable {
        public var device: PairedDevice
        public var online: Bool
        public var id: String { device.id }
    }

    public struct PairingWindow: Equatable {
        public var invite: PairingInvite
        public var expiresAt: Date
    }

    public struct Snapshot: Equatable {
        public var port: UInt16?
        public var devices: [DeviceStatus] = []
        public var pairing: PairingWindow?
        public var transfers: [TransferProgress] = []
        public var clipboardSyncEnabled = true

        public init() {}

        init(port: UInt16?, devices: [DeviceStatus], pairing: PairingWindow?, transfers: [TransferProgress], clipboardSyncEnabled: Bool) {
            self.port = port
            self.devices = devices
            self.pairing = pairing
            self.transfers = transfers
            self.clipboardSyncEnabled = clipboardSyncEnabled
        }
    }

    public static let maxClipBytes = 1 << 20

    public weak var delegate: HostDelegate?
    public let identity: DeviceIdentity
    public let config: Config

    let queue = DispatchQueue(label: "app.handspan.host")
    private var listener: NWListener?
    private var port: UInt16?
    private var sessions: [ObjectIdentifier: Session] = [:]
    private var active: [String: Session] = [:]
    private var pairing: PairingWindow?
    private let store: PairedStore
    private let incoming: IncomingStore
    private var outgoing: [String: OutgoingTransfer] = [:]
    private var progress: [String: TransferProgress] = [:]
    private var progressOrder: [String] = []
    private var pendingDecisions: Set<String> = []
    private var deliveredItems: Set<String> = []
    private var lastAppliedClip: String?
    private var lastSentClip: String?
    private var syncEnabled = true
    private var timer: DispatchSourceTimer?
    private var lastProgressPush = Date.distantPast

    private final class Session {
        let conn: FramedConnection
        var hello: Hello?
        var paired = false
        var pairDeadline: Date?
        init(conn: FramedConnection) { self.conn = conn }
        var peerId: String? { conn.peerId }
    }

    public init(config: Config) throws {
        self.config = config
        identity = try DeviceIdentity.loadOrCreate(keychain: config.keychain)
        store = PairedStore(url: config.stateDir.appendingPathComponent("paired.json"))
        incoming = IncomingStore(downloadsDir: config.downloadsDir, partsDir: config.stateDir.appendingPathComponent("parts", isDirectory: true))
        incoming.purge(olderThan: 24 * 3600)
    }

    // MARK: - Lifecycle

    public func start() {
        queue.async { [self] in
            startListener(port: NWEndpoint.Port(rawValue: config.preferredPort) ?? .any)
            let timer = DispatchSource.makeTimerSource(queue: queue)
            timer.schedule(deadline: .now() + 5, repeating: 5)
            timer.setEventHandler { [weak self] in self?.tick() }
            timer.resume()
            self.timer = timer
        }
    }

    public func stop() {
        queue.sync {
            timer?.cancel()
            timer = nil
            listener?.cancel()
            listener = nil
            for session in sessions.values { session.conn.close() }
        }
    }

    private func startListener(port: NWEndpoint.Port) {
        let params = TLS.parameters(identity: identity, queue: queue) { [weak self] peerId in
            self?.admit(peerId) ?? false
        }
        params.allowLocalEndpointReuse = true
        guard let listener = try? NWListener(using: params, on: port) else {
            if port != .any { startListener(port: .any) }
            return
        }
        if config.advertise {
            let txt = NWTXTRecord(["v": "1", "id": String(identity.deviceId.prefix(16)), "n": config.name])
            listener.service = NWListener.Service(name: config.name, type: "_handspan._tcp", domain: nil, txtRecord: txt)
        }
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self, let listener else { return }
            switch state {
            case .ready:
                self.port = listener.port?.rawValue
                log("Listening on port \(self.port ?? 0)")
                pushSnapshot()
            case let .failed(error):
                log("Listener failed: \(error)")
                listener.cancel()
                if self.listener === listener {
                    self.listener = nil
                    if port != .any { startListener(port: .any) }
                }
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] nw in self?.accept(nw) }
        self.listener = listener
        listener.start(queue: queue)
    }

    // MARK: - Public API (thread-safe)

    public func snapshot() -> Snapshot { queue.sync { makeSnapshot() } }

    public func openPairingWindow() -> PairingInvite {
        queue.sync {
            var secret = Data(count: 32)
            _ = secret.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
            let hosts = Interfaces.addresses().map { $0.contains(":") ? "[\($0)]:\(port ?? 0)" : "\($0):\(port ?? 0)" }
            let invite = PairingInvite(hostId: identity.deviceId, secret: secret, hosts: hosts, name: config.name)
            pairing = PairingWindow(invite: invite, expiresAt: Date().addingTimeInterval(config.pairingTTL))
            pushSnapshot()
            return invite
        }
    }

    public func closePairingWindow() {
        queue.async { [self] in
            pairing = nil
            pushSnapshot()
        }
    }

    public func unpair(_ deviceId: String) {
        queue.async { [self] in
            if let session = active[deviceId] {
                session.conn.send(.empty(.unpaired)) { _ in session.conn.close() }
            }
            store.remove(deviceId)
            for (id, t) in outgoing where t.peerId == deviceId { fail(t, id: id, reason: "Unpaired") }
            pushSnapshot()
        }
    }

    public func setAutoAccept(_ value: Bool, for deviceId: String) {
        queue.async { [self] in
            guard var device = store.device(deviceId) else { return }
            device.autoAccept = value
            store.upsert(device)
            pushSnapshot()
        }
    }

    public func setClipboardSync(_ enabled: Bool) {
        queue.async { [self] in
            syncEnabled = enabled
            pushSnapshot()
        }
    }

    /// Called by the pasteboard watcher when the Mac clipboard changes.
    public func localClipboardChanged(_ text: String) {
        queue.async { [self] in
            guard syncEnabled, !text.isEmpty, text.utf8.count <= Self.maxClipBytes else { return }
            let clip = Clip(origin: identity.deviceId, text: text)
            guard clip.sha256 != lastAppliedClip, clip.sha256 != lastSentClip else { return }
            lastSentClip = clip.sha256
            for session in active.values { session.conn.send(Frame(.clip, clip)) }
        }
    }

    public func sendText(_ text: String, to deviceId: String) {
        queue.async { [self] in
            guard let session = active[deviceId], text.utf8.count <= Self.maxClipBytes else {
                return log("Cannot send text: device offline")
            }
            session.conn.send(Frame(.offer, Offer(xferId: UUID().wire, items: [.text(text)])))
        }
    }

    /// Hashes the files off the queue, then offers them. The offer waits in memory
    /// for the device if it is offline and is re-sent on every reconnect until done.
    public func sendFiles(_ urls: [URL], to deviceId: String) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            var items: [OfferItem] = []
            var sources: [String: URL] = [:]
            for url in urls {
                guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize,
                      let hash = try? Hashing.sha256Hex(fileAt: url)
                else { continue }
                let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
                let item = OfferItem.file(name: url.lastPathComponent, size: Int64(size), mime: mime, sha256: hash)
                items.append(item)
                sources[item.fileId] = url
            }
            guard !items.isEmpty else { return }
            queue.async { [self] in
                let transfer = OutgoingTransfer(offer: Offer(xferId: UUID().wire, items: items), peerId: deviceId, sources: sources)
                outgoing[transfer.offer.xferId] = transfer
                setProgress(transfer.offer.xferId, peerId: deviceId, direction: .outgoing, title: transfer.title, done: 0, total: transfer.totalBytes, status: .waiting)
                if let session = active[deviceId] { session.conn.send(Frame(.offer, transfer.offer)) }
            }
        }
    }

    public func cancelTransfer(_ xferId: String) {
        queue.async { [self] in
            if let t = outgoing[xferId] {
                active[t.peerId]?.conn.send(Frame(.cancel, Cancel(xferId: xferId, reason: "cancelled")))
                fail(t, id: xferId, reason: "Cancelled")
            } else if let state = incoming.state(xferId) {
                active[state.peerId]?.conn.send(Frame(.cancel, Cancel(xferId: xferId, reason: "cancelled")))
                incoming.remove(xferId)
                updateProgress(xferId) { $0.status = .failed("Cancelled") }
            }
        }
    }

    // MARK: - Connections

    private func admit(_ peerId: String) -> Bool {
        if store.device(peerId) != nil { return true }
        if let pairing, pairing.expiresAt > Date() { return true }
        log("Rejected unpaired device \(peerId.prefix(8))")
        return false
    }

    private func accept(_ nw: NWConnection) {
        let conn = FramedConnection(connection: nw, queue: queue)
        let session = Session(conn: conn)
        sessions[ObjectIdentifier(session)] = session
        conn.onFrame = { [weak self, weak session] frame in
            guard let self, let session else { return }
            handle(frame, from: session)
        }
        conn.onClose = { [weak self, weak session] error in
            guard let self, let session else { return }
            closed(session, error: error)
        }
        conn.start()
    }

    private func closed(_ session: Session, error: Error?) {
        sessions[ObjectIdentifier(session)] = nil
        guard let peerId = session.peerId, active[peerId] === session else { return }
        active[peerId] = nil
        for t in outgoing.values where t.peerId == peerId { t.generation += 1 }
        log("Device \(peerId.prefix(8)) disconnected\(error.map { ": \($0)" } ?? "")")
        pushSnapshot()
    }

    private func activate(_ session: Session) {
        guard let peerId = session.peerId else { return }
        session.paired = true
        session.pairDeadline = nil
        if let old = active[peerId], old !== session { old.conn.close() }
        active[peerId] = session
        log("Device \(peerId.prefix(8)) connected")
        for t in outgoing.values where t.peerId == peerId && (t.state == .awaitingAnswer || t.state == .sending) {
            t.generation += 1
            t.state = .awaitingAnswer
            t.offeredAt = Date()
            session.conn.send(Frame(.offer, t.offer))
        }
        pushSnapshot()
    }

    private func handle(_ frame: Frame, from session: Session) {
        guard let type = frame.type else { return }
        do {
            if session.hello == nil {
                guard type == .hello else { return session.conn.close() }
                try handleHello(frame.decode(Hello.self), session)
                return
            }
            if type == .ping { return session.conn.send(.empty(.pong)) }
            if !session.paired {
                guard type == .pairRequest else { return session.conn.close() }
                return try handlePairRequest(frame.decode(PairRequest.self), session)
            }
            guard let peerId = session.peerId, let device = store.device(peerId) else { return session.conn.close() }
            switch type {
            case .clip: handleClip(try frame.decode(Clip.self), from: device)
            case .offer: handleOffer(try frame.decode(Offer.self), from: device, session)
            case .chunk: handleChunk(try Chunk(frame: frame), session)
            case .fileDone: handleFileDone(try frame.decode(FileRef.self), from: device, session)
            case .accept: handleAccept(try frame.decode(Accept.self), from: peerId)
            case .decline:
                let ref = try frame.decode(TransferRef.self)
                if let t = outgoing[ref.xferId], t.peerId == peerId { fail(t, id: ref.xferId, reason: "Declined") }
            case .verified: handleVerified(try frame.decode(FileRef.self), from: peerId)
            case .hashFail: handleHashFail(try frame.decode(FileRef.self), from: peerId)
            case .cancel: handleCancel(try frame.decode(Cancel.self), from: peerId)
            case .unpaired:
                store.remove(peerId)
                session.conn.close()
                log("Device \(peerId.prefix(8)) unpaired itself")
                pushSnapshot()
            default:
                break
            }
        } catch {
            log("Protocol error from \(session.peerId?.prefix(8) ?? "?"): \(error)")
            session.conn.close()
        }
    }

    private func handleHello(_ hello: Hello, _ session: Session) {
        guard hello.v == 1, let peerId = session.peerId, hello.id == peerId else { return session.conn.close() }
        session.hello = hello
        let paired = store.device(peerId) != nil
        session.conn.send(Frame(.hello, Hello(v: 1, id: identity.deviceId, name: config.name, platform: "macos", paired: paired)))
        if paired {
            if var device = store.device(peerId), device.name != hello.name {
                device.name = hello.name
                store.upsert(device)
            }
            activate(session)
        } else {
            session.pairDeadline = Date().addingTimeInterval(30)
        }
    }

    private func handlePairRequest(_ request: PairRequest, _ session: Session) {
        guard let peerId = session.peerId, let window = pairing, window.expiresAt > Date() else {
            session.conn.send(Frame(.pairResult, PairResult(ok: false, reason: "window_closed"))) { _ in session.conn.close() }
            return
        }
        let expected = PairingInvite.proof(secret: window.invite.secret, hostId: identity.deviceId, phoneId: peerId)
        guard PairingInvite.constantTimeEquals(expected, request.proof.lowercased()) else {
            log("Pairing proof mismatch from \(peerId.prefix(8))")
            session.conn.send(Frame(.pairResult, PairResult(ok: false, reason: "bad_proof"))) { _ in session.conn.close() }
            return
        }
        let code = PairingInvite.confirmCode(secret: window.invite.secret, hostId: identity.deviceId, phoneId: peerId)
        let device = PairedDevice(id: peerId, name: request.name, platform: session.hello?.platform ?? "android", pairedAt: Date(), autoAccept: false)
        store.upsert(device)
        pairing = nil
        session.conn.send(Frame(.pairResult, PairResult(ok: true, name: config.name)))
        activate(session)
        DispatchQueue.main.async { [weak self] in self?.delegate?.hostDidPair(device, code: code) }
    }

    // MARK: - Clipboard

    private func handleClip(_ clip: Clip, from device: PairedDevice) {
        guard syncEnabled, clip.isIntact, clip.text.utf8.count <= Self.maxClipBytes, clip.sha256 != lastAppliedClip else { return }
        lastAppliedClip = clip.sha256
        for (peerId, session) in active where peerId != device.id && peerId != clip.origin {
            session.conn.send(Frame(.clip, clip))
        }
        let text = clip.text
        DispatchQueue.main.async { [weak self] in self?.delegate?.hostDidReceiveClipboard(text, from: device) }
    }

    // MARK: - Receiving

    private func handleOffer(_ offer: Offer, from device: PairedDevice, _ session: Session) {
        guard Self.isWireUUID(offer.xferId), !offer.items.isEmpty, offer.items.allSatisfy(Self.isValid) else {
            return session.conn.send(Frame(.cancel, Cancel(xferId: offer.xferId, reason: "invalid_offer")))
        }
        let inline = offer.items.filter { $0.kind != .file }
        if !inline.isEmpty, deliveredItems.insert(offer.xferId).inserted {
            DispatchQueue.main.async { [weak self] in self?.delegate?.hostDidReceiveItems(inline, from: device) }
        }
        let files = offer.files
        guard !files.isEmpty else { return }

        if let state = incoming.state(offer.xferId) {
            guard state.peerId == device.id else { return }
            return sendAccept(offer.xferId, title: Self.title(files), peerId: device.id)
        }
        guard pendingDecisions.insert(offer.xferId).inserted else { return }
        let decide: (Bool) -> Void = { [weak self] accepted in
            self?.queue.async { self?.decided(offer, accepted: accepted, from: device) }
        }
        if device.autoAccept { return decide(true) }
        DispatchQueue.main.async { [weak self] in
            if let delegate = self?.delegate { delegate.hostShouldAccept(offer, from: device, decide: decide) } else { decide(false) }
        }
    }

    private func decided(_ offer: Offer, accepted: Bool, from device: PairedDevice) {
        pendingDecisions.remove(offer.xferId)
        guard let session = active[device.id] else { return }
        guard accepted else { return session.conn.send(Frame(.decline, TransferRef(xferId: offer.xferId))) }
        do {
            try incoming.accept(offer, from: device.id, files: offer.files.map(\.fileId))
            sendAccept(offer.xferId, title: Self.title(offer.files), peerId: device.id)
        } catch {
            log("Could not accept transfer: \(error)")
            session.conn.send(Frame(.decline, TransferRef(xferId: offer.xferId)))
        }
    }

    private func sendAccept(_ xferId: String, title: String, peerId: String) {
        guard let session = active[peerId], let state = incoming.state(xferId) else { return }
        let total = state.offer.files.filter { state.accepted.contains($0.fileId) }.reduce(Int64(0)) { $0 + ($1.size ?? 0) }
        setProgress(xferId, peerId: peerId, direction: .incoming, title: title, done: incoming.receivedBytes(xferId), total: total, status: .active)
        session.conn.send(Frame(.accept, Accept(xferId: xferId, files: incoming.offsets(xferId))))
    }

    private func handleChunk(_ chunk: Chunk, _ session: Session) {
        let xferId = chunk.xferId.wire
        do {
            try incoming.write(chunk)
            updateProgress(xferId, throttled: true) { [incoming] in $0.bytesDone = incoming.receivedBytes(xferId) }
        } catch {
            log("Rejecting chunk for \(xferId.prefix(8)): \(error)")
            session.conn.send(Frame(.cancel, Cancel(xferId: xferId, reason: "bad_chunk")))
            incoming.remove(xferId)
            updateProgress(xferId) { $0.status = .failed("Transfer error") }
        }
    }

    private func handleFileDone(_ ref: FileRef, from device: PairedDevice, _ session: Session) {
        incoming.finish(ref.xferId, ref.fileId, queue: queue) { [weak self] result in
            guard let self else { return }
            let reply = active[device.id]?.conn ?? session.conn
            switch result {
            case let .success(.verified(url)):
                reply.send(Frame(.verified, ref))
                updateProgress(ref.xferId) { [incoming] in
                    $0.bytesDone = incoming.receivedBytes(ref.xferId)
                    if incoming.isComplete(ref.xferId) { $0.bytesDone = $0.bytesTotal; $0.status = .done }
                }
                DispatchQueue.main.async { [weak self] in self?.delegate?.hostDidReceiveFile(url, from: device) }
            case .success(.hashFail):
                log("Hash mismatch for \(ref.fileId.prefix(8))")
                reply.send(Frame(.hashFail, ref))
            case let .failure(error):
                log("Could not finish file: \(error)")
                reply.send(Frame(.cancel, Cancel(xferId: ref.xferId, reason: "receiver_error")))
                incoming.remove(ref.xferId)
                updateProgress(ref.xferId) { $0.status = .failed("Could not save file") }
            }
        }
    }

    // MARK: - Sending

    private func handleAccept(_ accept: Accept, from peerId: String) {
        guard let t = outgoing[accept.xferId], t.peerId == peerId, t.state == .awaitingAnswer else { return }
        t.queue = t.offer.files.compactMap { item in
            guard let offset = accept.files[item.fileId], !t.verified.contains(item.fileId) else { return nil }
            return (item.fileId, min(max(offset, 0), item.size ?? 0))
        }
        t.state = .sending
        updateProgress(accept.xferId) { $0.status = .active }
        pumpNext(t)
    }

    private func handleVerified(_ ref: FileRef, from peerId: String) {
        guard let t = outgoing[ref.xferId], t.peerId == peerId, t.queue.first?.fileId == ref.fileId else { return }
        t.verified.insert(ref.fileId)
        t.queue.removeFirst()
        updateProgress(ref.xferId) { $0.bytesDone = t.verifiedBytes }
        pumpNext(t)
    }

    private func handleHashFail(_ ref: FileRef, from peerId: String) {
        guard let t = outgoing[ref.xferId], t.peerId == peerId, t.queue.first?.fileId == ref.fileId else { return }
        guard t.retried.insert(ref.fileId).inserted else {
            active[peerId]?.conn.send(Frame(.cancel, Cancel(xferId: ref.xferId, reason: "hash_mismatch")))
            return fail(t, id: ref.xferId, reason: "Integrity check failed")
        }
        t.queue[0].offset = 0
        pumpNext(t)
    }

    private func handleCancel(_ cancel: Cancel, from peerId: String) {
        if let t = outgoing[cancel.xferId], t.peerId == peerId {
            fail(t, id: cancel.xferId, reason: "Cancelled by device (\(cancel.reason))")
        } else if let state = incoming.state(cancel.xferId), state.peerId == peerId {
            incoming.remove(cancel.xferId)
            updateProgress(cancel.xferId) { $0.status = .failed("Cancelled by device") }
        }
    }

    private func pumpNext(_ t: OutgoingTransfer) {
        guard t.state == .sending, let session = active[t.peerId] else { return }
        guard let (fileId, offset) = t.queue.first else {
            t.state = .done
            outgoing[t.offer.xferId] = nil
            return updateProgress(t.offer.xferId) { $0.bytesDone = $0.bytesTotal; $0.status = .done }
        }
        guard let url = t.sources[fileId], let handle = try? FileHandle(forReadingFrom: url) else {
            session.conn.send(Frame(.cancel, Cancel(xferId: t.offer.xferId, reason: "source_missing")))
            return fail(t, id: t.offer.xferId, reason: "File is no longer available")
        }
        do { try handle.seek(toOffset: UInt64(offset)) } catch { try? handle.close(); return }
        t.generation += 1
        streamChunks(t, fileId: fileId, offset: UInt64(offset), handle: handle, conn: session.conn, generation: t.generation)
    }

    private func streamChunks(_ t: OutgoingTransfer, fileId: String, offset: UInt64, handle: FileHandle, conn: FramedConnection, generation: Int) {
        guard t.generation == generation, t.state == .sending else { try? handle.close(); return }
        let data = (try? handle.read(upToCount: Frame.maxChunkData)) ?? Data()
        guard !data.isEmpty else {
            try? handle.close()
            conn.send(Frame(.fileDone, FileRef(xferId: t.offer.xferId, fileId: fileId)))
            return
        }
        let chunk = Chunk(xferId: UUID(uuidString: t.offer.xferId)!, fileId: UUID(uuidString: fileId)!, offset: offset, data: data)
        conn.send(chunk.frame) { [weak self] error in
            guard let self else { return }
            guard error == nil else { try? handle.close(); return }
            let next = offset + UInt64(data.count)
            updateProgress(t.offer.xferId, throttled: true) { $0.bytesDone = t.verifiedBytes + Int64(next) }
            streamChunks(t, fileId: fileId, offset: next, handle: handle, conn: conn, generation: generation)
        }
    }

    private func fail(_ t: OutgoingTransfer, id: String, reason: String) {
        t.state = .cancelled
        t.generation += 1
        outgoing[id] = nil
        updateProgress(id) { $0.status = .failed(reason) }
    }

    // MARK: - Housekeeping

    private func tick() {
        let now = Date()
        if let window = pairing, window.expiresAt <= now {
            pairing = nil
            pushSnapshot()
        }
        for session in sessions.values {
            if now.timeIntervalSince(session.conn.lastReceived) > 60 { session.conn.close() }
            if let deadline = session.pairDeadline, deadline < now { session.conn.close() }
        }
        for (id, t) in outgoing where t.state == .awaitingAnswer && active[t.peerId] != nil && now.timeIntervalSince(t.offeredAt) > 300 {
            active[t.peerId]?.conn.send(Frame(.cancel, Cancel(xferId: id, reason: "timeout")))
            fail(t, id: id, reason: "No answer from device")
        }
    }

    private func setProgress(_ id: String, peerId: String, direction: TransferProgress.Direction, title: String, done: Int64, total: Int64, status: TransferProgress.Status) {
        if progress[id] == nil { progressOrder.append(id) }
        progress[id] = TransferProgress(id: id, peerId: peerId, direction: direction, title: title, bytesDone: done, bytesTotal: total, status: status)
        while progressOrder.count > 20 { progress[progressOrder.removeFirst()] = nil }
        pushSnapshot()
    }

    private func updateProgress(_ id: String, throttled: Bool = false, _ change: (inout TransferProgress) -> Void) {
        guard var p = progress[id] else { return }
        change(&p)
        progress[id] = p
        if throttled, Date().timeIntervalSince(lastProgressPush) < 0.25 { return }
        pushSnapshot()
    }

    private func makeSnapshot() -> Snapshot {
        Snapshot(
            port: port,
            devices: store.devices.sorted { $0.pairedAt < $1.pairedAt }.map { DeviceStatus(device: $0, online: active[$0.id] != nil) },
            pairing: pairing,
            transfers: progressOrder.reversed().compactMap { progress[$0] },
            clipboardSyncEnabled: syncEnabled
        )
    }

    private func pushSnapshot() {
        lastProgressPush = Date()
        let snapshot = makeSnapshot()
        DispatchQueue.main.async { [weak self] in self?.delegate?.hostDidChange(snapshot) }
    }

    private func log(_ message: String) {
        DispatchQueue.main.async { [weak self] in self?.delegate?.hostLog(message) }
    }

    static func isWireUUID(_ s: String) -> Bool { UUID(uuidString: s)?.wire == s }

    private static func isValid(_ item: OfferItem) -> Bool {
        guard isWireUUID(item.fileId) else { return false }
        switch item.kind {
        case .file:
            guard let size = item.size, size >= 0, item.name != nil, let hash = item.sha256 else { return false }
            return hash.count == 64 && Hex.decode(hash) != nil
        case .text, .url:
            return (item.text?.utf8.count ?? Int.max) <= maxClipBytes
        }
    }

    private static func title(_ files: [OfferItem]) -> String {
        files.count == 1 ? FileNames.sanitize(files[0].name ?? "file") : "\(files.count) files"
    }
}

enum Interfaces {
    /// Non-loopback IPv4 and global IPv6 addresses of active interfaces.
    static func addresses() -> [String] {
        var result: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(ptr.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0, let addr = ptr.pointee.ifa_addr else { continue }
            let family = addr.pointee.sa_family
            guard family == UInt8(AF_INET) || family == UInt8(AF_INET6) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let len = socklen_t(family == UInt8(AF_INET) ? MemoryLayout<sockaddr_in>.size : MemoryLayout<sockaddr_in6>.size)
            guard getnameinfo(addr, len, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let string = String(cString: host)
            if family == UInt8(AF_INET6) && (string.hasPrefix("fe80") || string.contains("%")) { continue }
            let name = String(cString: ptr.pointee.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }
            result.append(string)
        }
        return result.sorted { !$0.contains(":") && $1.contains(":") }
    }
}
