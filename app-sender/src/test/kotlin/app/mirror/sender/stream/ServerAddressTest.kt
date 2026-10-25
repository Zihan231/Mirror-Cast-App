package app.mirror.sender.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    @Test
    fun `bare host and port gets ws scheme and the signaling path`() {
        assertEquals("ws://192.168.0.5:8080/ws", ServerAddress.normalize("192.168.0.5:8080"))
    }

    @Test
    fun `bare host without a port assumes the default server port`() {
        assertEquals("ws://192.168.0.5:8080/ws", ServerAddress.normalize("192.168.0.5"))
    }

    @Test
    fun `explicit ws and wss urls are kept`() {
        assertEquals("ws://pc.local:9000/ws", ServerAddress.normalize("ws://pc.local:9000/ws"))
        assertEquals("wss://cast.example.com/ws", ServerAddress.normalize("wss://cast.example.com"))
        assertEquals("wss://cast.example.com/custom", ServerAddress.normalize("wss://cast.example.com/custom"))
    }

    @Test
    fun `a pasted test page url is converted`() {
        assertEquals("ws://192.168.0.5:8080/ws", ServerAddress.normalize("http://192.168.0.5:8080/test"))
        assertEquals("wss://cast.example.com/ws", ServerAddress.normalize("https://cast.example.com/test"))
    }

    @Test
    fun `surrounding whitespace is trimmed but inner whitespace is refused`() {
        assertEquals("ws://10.0.0.2:8080/ws", ServerAddress.normalize("  10.0.0.2:8080 \n"))
        assertNull(ServerAddress.normalize("10.0.0 .2"))
    }

    @Test
    fun `garbage and unsupported schemes are refused`() {
        assertNull(ServerAddress.normalize(""))
        assertNull(ServerAddress.normalize("   "))
        assertNull(ServerAddress.normalize("ftp://host/ws"))
        assertNull(ServerAddress.normalize("ws://"))
        assertNull(ServerAddress.normalize("://"))
    }

    @Test
    fun `code is exactly six digits`() {
        assertTrue(ServerAddress.isValidCode("012345"))
        assertFalse(ServerAddress.isValidCode("12345"))
        assertFalse(ServerAddress.isValidCode("1234567"))
        assertFalse(ServerAddress.isValidCode("12345a"))
        assertFalse(ServerAddress.isValidCode(""))
    }

    @Test
    fun `the target never prints its code`() {
        assertFalse(StreamTarget("ws://h/ws", "123456").toString().contains("123456"))
    }
}
