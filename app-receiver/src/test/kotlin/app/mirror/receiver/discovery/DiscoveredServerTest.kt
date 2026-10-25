package app.mirror.receiver.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveredServerTest {

    @Test
    fun `plain LAN server becomes a ws url with the advertised path`() {
        val s = DiscoveredServer.from("Mirror Signaling", "192.168.0.130", 8080, mapOf("v" to "1", "path" to "/ws", "tls" to "0"))!!
        assertEquals("ws://192.168.0.130:8080/ws", s.url)
    }

    @Test
    fun `tls flag selects wss`() {
        val s = DiscoveredServer.from("x", "tv.local", 443, mapOf("tls" to "1"))!!
        assertEquals("wss://tv.local:443/ws", s.url)
    }

    @Test
    fun `missing or hostile txt values fall back to safe defaults`() {
        assertEquals("/ws", DiscoveredServer.from("x", "h", 1, emptyMap())!!.path)
        assertEquals("/ws", DiscoveredServer.from("x", "h", 1, mapOf("path" to "evil path"))!!.path)
        assertEquals("/ws", DiscoveredServer.from("x", "h", 1, mapOf("path" to "no-slash"))!!.path)
        assertEquals("/ws", DiscoveredServer.from("x", "h", 1, mapOf("path" to "/" + "a".repeat(100)))!!.path)
    }

    @Test
    fun `ipv6 hosts are bracketed and lose their scope id`() {
        val s = DiscoveredServer.from("x", "fe80::1%wlan0", 8080, emptyMap())!!
        assertEquals("ws://[fe80::1]:8080/ws", s.url)
    }

    @Test
    fun `invalid ports and blank hosts are rejected`() {
        assertNull(DiscoveredServer.from("x", "h", 0, emptyMap()))
        assertNull(DiscoveredServer.from("x", "h", 70000, emptyMap()))
        assertNull(DiscoveredServer.from("x", " ", 8080, emptyMap()))
    }

    @Test
    fun `name is trimmed, bounded, and falls back to the host`() {
        assertEquals("a".repeat(64), DiscoveredServer.from("a".repeat(200), "h", 1, emptyMap())!!.name)
        assertEquals("h", DiscoveredServer.from("   ", "h", 1, emptyMap())!!.name)
    }
}
