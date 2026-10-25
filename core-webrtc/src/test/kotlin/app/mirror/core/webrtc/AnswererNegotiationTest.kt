package app.mirror.core.webrtc

import app.mirror.core.webrtc.AnswererNegotiation.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswererNegotiationTest {

    @Test
    fun `candidates that beat the offer are released in order once it is applied`() {
        val n = AnswererNegotiation<String>()
        assertNull(n.onRemoteCandidate("c1")) // before the offer was even processed
        assertTrue(n.acceptOffer())
        assertNull(n.onRemoteCandidate("c2")) // offer being applied
        assertEquals(listOf("c1", "c2"), n.offerApplied())
        assertEquals(Phase.ESTABLISHED, n.phase)
        assertEquals("c3", n.onRemoteCandidate("c3"))
    }

    @Test
    fun `only the first offer is honoured, there is no renegotiation`() {
        val n = AnswererNegotiation<String>()
        assertTrue(n.acceptOffer())
        assertFalse("duplicate while applying", n.acceptOffer())
        n.offerApplied()
        assertFalse("second offer after establishment", n.acceptOffer())
    }

    @Test
    fun `failure or close discards everything`() {
        val n = AnswererNegotiation<String>()
        n.acceptOffer()
        n.onRemoteCandidate("c1")
        n.offerFailed()
        assertEquals(Phase.CLOSED, n.phase)
        assertTrue(n.offerApplied().isEmpty())
        assertNull(n.onRemoteCandidate("late"))
        assertFalse(n.acceptOffer())
    }

    @Test
    fun `queue is bounded`() {
        val n = AnswererNegotiation<Int>(maxQueued = 3)
        n.acceptOffer()
        repeat(10) { n.onRemoteCandidate(it) }
        assertEquals(listOf(0, 1, 2), n.offerApplied())
    }

    @Test
    fun `latency estimate is half the rtt plus buffer plus decode`() {
        assertEquals(40 / 2 + 60 + 5, LatencyEstimate.estimate(rttMs = 40, jitterBufferMs = 60, decodeMs = 5))
        assertEquals(65, LatencyEstimate.estimate(rttMs = null, jitterBufferMs = 60, decodeMs = 5))
    }
}
