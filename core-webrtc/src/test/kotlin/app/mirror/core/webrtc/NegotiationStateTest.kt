package app.mirror.core.webrtc

import app.mirror.core.webrtc.NegotiationState.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NegotiationStateTest {

    @Test
    fun `happy path releases queued candidates in order once the answer is applied`() {
        val s = NegotiationState<String>()
        s.offerSent()
        assertNull(s.onRemoteCandidate("c1")) // trickled before the answer
        assertTrue(s.acceptAnswer())
        assertNull(s.onRemoteCandidate("c2")) // answer being applied
        assertEquals(listOf("c1", "c2"), s.answerApplied())
        assertEquals(Phase.ESTABLISHED, s.phase)
        assertEquals("c3", s.onRemoteCandidate("c3")) // now added immediately
    }

    @Test
    fun `answers are accepted exactly once`() {
        val s = NegotiationState<String>()
        assertFalse("answer before offer", s.acceptAnswer())
        s.offerSent()
        assertTrue(s.acceptAnswer())
        assertFalse("duplicate answer", s.acceptAnswer())
        s.answerApplied()
        assertFalse("late answer", s.acceptAnswer())
    }

    @Test
    fun `a second offer is a programming error`() {
        val s = NegotiationState<String>()
        s.offerSent()
        assertThrows(IllegalStateException::class.java) { s.offerSent() }
    }

    @Test
    fun `candidates before the offer or after close are dropped`() {
        val s = NegotiationState<String>()
        assertNull(s.onRemoteCandidate("early"))
        s.offerSent()
        s.acceptAnswer()
        s.answerApplied()
        s.close()
        assertNull(s.onRemoteCandidate("late"))
        assertTrue(s.answerApplied().isEmpty())
    }

    @Test
    fun `early candidates were not kept when the offer had not been sent`() {
        val s = NegotiationState<String>()
        s.onRemoteCandidate("early")
        s.offerSent()
        s.acceptAnswer()
        assertTrue(s.answerApplied().isEmpty())
    }

    @Test
    fun `failed answer closes the state and discards the queue`() {
        val s = NegotiationState<String>()
        s.offerSent()
        s.onRemoteCandidate("c1")
        s.acceptAnswer()
        s.answerFailed()
        assertEquals(Phase.CLOSED, s.phase)
        assertTrue(s.answerApplied().isEmpty())
    }

    @Test
    fun `candidate queue is bounded`() {
        val s = NegotiationState<Int>(maxQueued = 3)
        s.offerSent()
        repeat(10) { s.onRemoteCandidate(it) }
        s.acceptAnswer()
        assertEquals(listOf(0, 1, 2), s.answerApplied())
    }
}
