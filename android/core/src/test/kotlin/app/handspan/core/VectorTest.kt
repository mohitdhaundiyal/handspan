package app.handspan.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

fun vectors(name: String): JsonObject =
    Json.parseToJsonElement(File(System.getProperty("handspan.vectors"), name).readText()).jsonObject

private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

class VectorTest {
    @Test fun deviceIdsFromSpki() {
        val v = vectors("pairing.json")
        for (entry in v.getValue("identity").jsonArray) {
            val o = entry.jsonObject
            val spki = Hex.decode(o.str("spkiDerHex"))
            assertEquals(o.str("deviceId"), DeviceId.fromSpki(spki))
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            assertEquals(o.str("deviceId"), DeviceId.of(key))
        }
        val raw = v.getValue("rawP256Point").jsonObject
        assertArrayEquals(Hex.decode(raw.str("spkiDerHex")), DeviceId.P256_SPKI_PREFIX + Hex.decode(raw.str("pointHex")))
    }

    @Test fun pairingProofAndCode() {
        val p = vectors("pairing.json").getValue("pairing").jsonObject
        val secret = Hex.decode(p.str("secretHex"))
        assertEquals(p.str("proofHex"), PairingInvite.proof(secret, p.str("macId"), p.str("phoneId")))
        assertEquals(p.str("confirmCode"), PairingInvite.confirmCode(secret, p.str("macId"), p.str("phoneId")))
    }

    @Test fun qrRoundTrip() {
        val q = vectors("pairing.json").getValue("qr").jsonObject
        val invite = PairingInvite.parse(q.str("uri"))!!
        assertEquals(q.str("id"), invite.hostId)
        assertArrayEquals(Hex.decode(q.str("secretHex")), invite.secret)
        assertEquals(q.getValue("hosts").jsonArray.map { it.jsonPrimitive.content }, invite.hosts)
        assertEquals(q.str("name"), invite.name)
        assertEquals(q.str("uri"), invite.uri)
        assertNull(PairingInvite.parse(q.str("uri").replace("v=1", "v=2")))
        assertEquals("fe80::1" to 53999, parseHostPort("[fe80::1]:53999"))
        assertEquals("192.168.1.20" to 53999, parseHostPort("192.168.1.20:53999"))
    }

    @Test fun frames() {
        val v = vectors("frames.json")
        assertEquals(Frame.MAX_PAYLOAD, v.getValue("maxFramePayload").jsonPrimitive.int)
        for (entry in v.getValue("frames").jsonArray) {
            val f = entry.jsonObject
            when (f.str("name")) {
                "PING" -> assertEquals(f.str("hex"), Hex.encode(Frame.empty(FrameType.PING).encoded()))
                "CLIP" -> {
                    val clip = WireJson.decodeFromString<Clip>(f.getValue("json").toString())
                    assertTrue(clip.isIntact)
                    val back = FrameReader(ByteArrayInputStream(frameOf(FrameType.CLIP, clip).encoded())).read()!!
                    assertEquals(clip, back.decode<Clip>())
                }
                "CHUNK" -> {
                    val chunk = Chunk(UUID.fromString(f.str("xferId")), UUID.fromString(f.str("fileId")), f.str("offset").toLong(), Hex.decode(f.str("dataHex")))
                    assertEquals(f.str("hex"), Hex.encode(chunk.frame().encoded()))
                    val parsed = Chunk.parse(FrameReader(ByteArrayInputStream(Hex.decode(f.str("hex")))).read()!!)
                    assertEquals(chunk.xferId, parsed.xferId)
                    assertEquals(chunk.offset, parsed.offset)
                    assertArrayEquals(chunk.data, parsed.data)
                }
            }
        }
    }

    @Test(expected = ProtocolException::class)
    fun oversizedFrameIsRejected() {
        FrameReader(ByteArrayInputStream(byteArrayOf(0x00, 0x80.toByte(), 0x00, 0x02, 0x10))).read()
    }

    @Test fun fileNames() {
        for (entry in vectors("files.json").getValue("sanitizeFileName").jsonArray) {
            val o = entry.jsonObject
            assertEquals(o.str("input"), o.str("output"), FileNames.sanitize(o.str("input")))
        }
        val cut = FileNames.sanitize("é".repeat(300) + ".txt")
        assertTrue(cut.toByteArray().size <= 200 && cut.endsWith(".txt"))
        assertEquals("a (2).txt", FileNames.unique("a.txt") { it in setOf("a.txt", "a (1).txt") })
    }

    @Test fun selfSignedIdentityIsP256() {
        val identity = SelfSignedCert.generate()
        assertEquals(64, identity.deviceId.length)
        identity.certificate.verify(identity.certificate.publicKey)
    }

    @Test fun urlDetection() {
        assertEquals(ItemKind.URL, OfferItem.text(" https://example.com/x ").kind)
        assertEquals(ItemKind.TEXT, OfferItem.text("javascript:alert(1)").kind)
        assertEquals(ItemKind.TEXT, OfferItem.text("hello world").kind)
    }
}
