package app.mirror.core.signaling

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProtocolTest {
    private fun encoded(m: ClientMessage): JsonObject = Json.parseToJsonElement(SignalingJson.encode(m)).jsonObject

    @Test
    fun `join-pairing carries type and protocolVersion and omits null id`() {
        val o = encoded(ClientMessage.JoinPairing(code = "240610", deviceName = "Pixel 9"))
        assertEquals("join-pairing", o["type"]!!.jsonPrimitive.content)
        assertEquals("240610", o["code"]!!.jsonPrimitive.content)
        assertEquals(PROTOCOL_VERSION, o["protocolVersion"]!!.jsonPrimitive.int)
        assertTrue("id" !in o)
    }

    @Test
    fun `ice-candidate omits null optionals and keeps empty end-of-candidates`() {
        val o = encoded(ClientMessage.IceCandidate(candidate = ""))
        assertEquals(setOf("type", "candidate"), o.keys)
    }

    @Test
    fun `request id is echoed on the wire`() {
        val o = encoded(ClientMessage.CreatePairing(id = "r1"))
        assertEquals("r1", o["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `decodes paired with TURN credentials`() {
        val msg = SignalingJson.decodeServerMessage(
            """{"type":"paired","sessionId":"s1","role":"sender","peerName":"TV",
               "iceServers":[{"urls":["stun:a:3478"]},{"urls":["turn:a:3478"],"username":"1:x","credential":"c"}]}""",
        )
        val paired = assertIs<ServerMessage.Paired>(msg)
        assertEquals(2, paired.iceServers.size)
        assertEquals("c", paired.iceServers[1].credential)
        assertNull(paired.iceServers[0].username)
    }

    @Test
    fun `decodes error with retryAfterMs and ignores unknown fields`() {
        val msg = SignalingJson.decodeServerMessage(
            """{"type":"error","code":"rate-limited","message":"slow down","retryAfterMs":287000,"future":true}""",
        )
        val err = assertIs<ServerMessage.Error>(msg)
        assertEquals(ErrorCodes.RATE_LIMITED, err.code)
        assertEquals(287000, err.retryAfterMs)
    }

    @Test
    fun `decodes pong whose ts is an integer`() {
        val pong = assertIs<ServerMessage.Pong>(SignalingJson.decodeServerMessage("""{"type":"pong","ts":42,"serverTime":1}"""))
        assertEquals(42.0, pong.ts)
    }

    @Test
    fun `unknown or malformed server messages decode to null instead of throwing`() {
        assertNull(SignalingJson.decodeServerMessage("""{"type":"from-the-future"}"""))
        assertNull(SignalingJson.decodeServerMessage("not json"))
        assertNull(SignalingJson.decodeServerMessage("""{"type":"offer"}""")) // missing sdp
        assertNull(SignalingJson.decodeServerMessage("""{"sdp":"x"}""")) // missing type
    }

    @Test
    fun `reconnect backoff grows, caps and stays within jitter bounds`() {
        val p = ReconnectPolicy(initialDelayMs = 500, maxDelayMs = 4_000, multiplier = 2.0, jitter = 0.2)
        val rnd = Random(1)
        val expectedBase = listOf(500L, 1000L, 2000L, 4000L, 4000L, 4000L)
        expectedBase.forEachIndexed { i, base ->
            repeat(50) {
                val d = p.delayMs(i + 1, rnd)
                assertTrue(d in (base * 0.8).toLong()..(base * 1.2).toLong(), "attempt ${i + 1}: $d vs $base")
            }
        }
    }

    @Test
    fun `reconnect policy exhaustion`() {
        assertTrue(ReconnectPolicy(maxAttempts = 3).exhausted(4))
        assertTrue(!ReconnectPolicy(maxAttempts = 3).exhausted(3))
        assertTrue(!ReconnectPolicy().exhausted(1_000))
        assertTrue(ReconnectPolicy.NONE.exhausted(1))
        assertFailsWith<IllegalArgumentException> { ReconnectPolicy(initialDelayMs = 0) }
    }

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(value is T, "expected ${T::class.simpleName} but was $value")
        return value as T
    }
}
