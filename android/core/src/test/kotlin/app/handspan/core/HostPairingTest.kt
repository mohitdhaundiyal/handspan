package app.handspan.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HostPairingTest {
    @Test fun prefersIpv4AndKeepsRecentFirst() {
        val p = HostPairing("id", "Mac", addresses = listOf("192.168.1.33:53999", "[2401:db8::7a6c]:53999"))
            .remember("[2401:db8::beef]:53999")
            .remember("192.168.1.40:53999")
        assertEquals(
            listOf("192.168.1.40:53999", "192.168.1.33:53999", "[2401:db8::beef]:53999", "[2401:db8::7a6c]:53999"),
            p.knownAddresses,
        )
    }

    @Test fun capsRememberedAddresses() {
        var p = HostPairing("id", "Mac")
        repeat(20) { p = p.remember("10.0.0.$it:1") }
        assertEquals(8, p.addresses.size)
        assertEquals("10.0.0.19:1", p.knownAddresses.first())
    }

    @Test fun readsPairingsSavedBeforeAddressHistory() {
        val old = """{"hostId":"id","hostName":"Mac","lastAddress":"[2401:db8::7a6c]:53999"}"""
        val p = WireJson.decodeFromString<HostPairing>(old)
        assertEquals(listOf("[2401:db8::7a6c]:53999"), p.knownAddresses)
    }
}
