import Foundation
import Testing
@testable import HandspanCore

private func vectors(_ name: String) throws -> [String: Any] {
    let url = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("protocol/vectors/\(name)")
    return try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
}

@Suite struct VectorTests {
    @Test func deviceIdsFromSPKI() throws {
        let v = try vectors("pairing.json")
        for entry in v["identity"] as! [[String: String]] {
            #expect(DeviceID.fromSPKI(Hex.decode(entry["spkiDerHex"]!)!) == entry["deviceId"])
        }
        let raw = v["rawP256Point"] as! [String: String]
        #expect(DeviceID.spki(fromRawPoint: Hex.decode(raw["pointHex"]!)!) == Hex.decode(raw["spkiDerHex"]!))
    }

    @Test func pairingProofAndCode() throws {
        let p = try vectors("pairing.json")["pairing"] as! [String: String]
        let secret = Hex.decode(p["secretHex"]!)!
        #expect(Base64URL.encode(secret) == p["secretB64url"])
        #expect(PairingInvite.proof(secret: secret, hostId: p["macId"]!, phoneId: p["phoneId"]!) == p["proofHex"])
        #expect(PairingInvite.confirmCode(secret: secret, hostId: p["macId"]!, phoneId: p["phoneId"]!) == p["confirmCode"])
    }

    @Test func qrRoundTrip() throws {
        let q = try vectors("pairing.json")["qr"] as! [String: Any]
        let invite = try #require(PairingInvite(uri: q["uri"] as! String))
        #expect(invite.hostId == q["id"] as? String)
        #expect(invite.secret == Hex.decode(q["secretHex"] as! String))
        #expect(invite.hosts == q["hosts"] as? [String])
        #expect(invite.name == q["name"] as? String)
        #expect(invite.uri == q["uri"] as? String)
        #expect(PairingInvite(uri: (q["uri"] as! String).replacingOccurrences(of: "v=1", with: "v=2")) == nil)
    }

    @Test func frames() throws {
        let v = try vectors("frames.json")
        #expect(v["maxFramePayload"] as? Int == Frame.maxPayload)
        for f in v["frames"] as! [[String: Any]] {
            let type = FrameType(rawValue: UInt8(f["type"] as! Int))!
            switch type {
            case .ping:
                #expect(Hex.encode(Frame.empty(.ping).encoded()) == f["hex"] as? String)
            case .clip:
                let json = try JSONSerialization.data(withJSONObject: f["json"]!)
                let clip = try JSONDecoder().decode(Clip.self, from: json)
                #expect(clip.isIntact)
                var decoder = FrameDecoder()
                let decoded = try decoder.append(Frame(.clip, clip).encoded())
                #expect(try decoded.first?.decode(Clip.self) == clip)
            case .chunk:
                let chunk = Chunk(xferId: UUID(uuidString: f["xferId"] as! String)!, fileId: UUID(uuidString: f["fileId"] as! String)!,
                                  offset: UInt64(f["offset"] as! Int), data: Hex.decode(f["dataHex"] as! String)!)
                let hex = f["hex"] as! String
                #expect(Hex.encode(chunk.frame.encoded()) == hex)
                var decoder = FrameDecoder()
                let bytes = Hex.decode(hex)!
                #expect(try decoder.append(bytes.prefix(7)).isEmpty)
                let frames = try decoder.append(bytes.dropFirst(7))
                #expect(try Chunk(frame: frames[0]) == chunk)
            default:
                Issue.record("unexpected vector \(type)")
            }
        }
    }

    @Test func oversizedFrameIsRejected() {
        var decoder = FrameDecoder()
        #expect(throws: ProtocolError.self) { try decoder.append(Data([0x00, 0x80, 0x00, 0x02, 0x10])) }
    }

    @Test func fileNames() throws {
        for case let entry as [String: String] in try vectors("files.json")["sanitizeFileName"] as! [Any] {
            #expect(FileNames.sanitize(entry["input"]!) == entry["output"], "\(entry["input"]!.debugDescription)")
        }
        let long = String(repeating: "é", count: 300) + ".txt"
        let cut = FileNames.sanitize(long)
        #expect(cut.utf8.count <= 200 && cut.hasSuffix(".txt"))
    }
}
