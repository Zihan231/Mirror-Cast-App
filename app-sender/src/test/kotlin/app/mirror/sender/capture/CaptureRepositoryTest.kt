package app.mirror.sender.capture

import android.content.Intent
import app.mirror.core.webrtc.CaptureConfig
import app.mirror.core.webrtc.ScreenCapture
import app.mirror.core.webrtc.ScreenCaptureListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRepositoryTest {

    private class FakeSession(
        val listener: ScreenCaptureListener,
        private val failWith: Throwable?,
    ) : ScreenCapture {
        var startCalls = 0
        var stopCalls = 0

        override fun start() {
            startCalls++
            failWith?.let { throw it }
            listener.onStarted(CaptureConfig(864, 1920, 30))
        }

        override fun stop() {
            stopCalls++
        }
    }

    private class Fixture {
        val sessions = mutableListOf<FakeSession>()
        var failNextStartWith: Throwable? = null
        val repo = CaptureRepository { _, listener, _ ->
            FakeSession(listener, failNextStartWith.also { failNextStartWith = null }).also { sessions += it }
        }
        val last get() = sessions.last()
    }

    private fun token() = Intent()

    @Test
    fun `start reaches Capturing with the session's config`() {
        val f = Fixture()
        assertTrue(f.repo.start(token()))
        assertEquals(CaptureState.Capturing(864, 1920, 30), f.repo.state.value)
        assertEquals(1, f.last.startCalls)
    }

    @Test
    fun `a second start while active is refused and does not create a session`() {
        val f = Fixture()
        f.repo.start(token())
        assertFalse(f.repo.start(token()))
        assertEquals(1, f.sessions.size)
    }

    @Test
    fun `user stop releases the session once and is idempotent`() {
        val f = Fixture()
        f.repo.start(token())
        f.repo.stop(StopReason.USER_REQUESTED)
        f.repo.stop(StopReason.USER_REQUESTED)
        f.repo.stop(StopReason.SYSTEM)
        assertEquals(1, f.last.stopCalls)
        assertEquals(CaptureState.Stopped(StopReason.USER_REQUESTED), f.repo.state.value)
    }

    @Test
    fun `projection onStop from the system ends the capture without calling stop again`() {
        val f = Fixture()
        f.repo.start(token())
        f.last.listener.onProjectionStopped()
        assertEquals(CaptureState.Stopped(StopReason.SYSTEM), f.repo.state.value)
        f.repo.stop(StopReason.USER_REQUESTED) // e.g. service onDestroy afterwards
        assertEquals(0, f.last.stopCalls)
        assertEquals(CaptureState.Stopped(StopReason.SYSTEM), f.repo.state.value)
    }

    @Test
    fun `a new consent after a stop starts a brand new session`() {
        val f = Fixture()
        f.repo.start(token())
        f.repo.stop(StopReason.USER_REQUESTED)
        assertTrue(f.repo.start(token()))
        assertEquals(2, f.sessions.size)
        assertNotSame(f.sessions[0], f.sessions[1])
        assertTrue(f.repo.state.value is CaptureState.Capturing)
    }

    @Test
    fun `callbacks from a dead session cannot affect the next one`() {
        val f = Fixture()
        f.repo.start(token())
        val old = f.last
        f.repo.stop(StopReason.USER_REQUESTED)
        f.repo.start(token())

        old.listener.onProjectionStopped()
        old.listener.onContentVisibilityChanged(false)
        old.listener.onContentResized(10, 10)

        assertEquals(CaptureState.Capturing(864, 1920, 30), f.repo.state.value)
    }

    @Test
    fun `SecurityException maps to PROJECTION_REJECTED and allows a retry`() {
        val f = Fixture()
        f.failNextStartWith = SecurityException("token reused")
        f.repo.start(token())
        assertEquals(FailureReason.PROJECTION_REJECTED, (f.repo.state.value as CaptureState.Failed).reason)

        assertTrue(f.repo.start(token()))
        assertTrue(f.repo.state.value is CaptureState.Capturing)
    }

    @Test
    fun `other exceptions map to INTERNAL`() {
        val f = Fixture()
        f.failNextStartWith = IllegalStateException("boom")
        f.repo.start(token())
        assertEquals(FailureReason.INTERNAL, (f.repo.state.value as CaptureState.Failed).reason)
    }

    @Test
    fun `partial-share callbacks update the capturing state`() {
        val f = Fixture()
        f.repo.start(token())
        f.last.listener.onContentResized(720, 1280)
        f.last.listener.onContentVisibilityChanged(false)
        val s = f.repo.state.value as CaptureState.Capturing
        assertEquals(720, s.sharedContentWidth)
        assertEquals(1280, s.sharedContentHeight)
        assertFalse(s.contentVisible)
    }

    @Test
    fun `foreground failure is reported only when nothing is capturing`() {
        val f = Fixture()
        f.repo.reportForegroundFailure("SecurityException")
        assertEquals(FailureReason.FOREGROUND_START_DENIED, (f.repo.state.value as CaptureState.Failed).reason)

        f.repo.resetToIdle()
        f.repo.start(token())
        f.repo.reportForegroundFailure("late")
        assertTrue(f.repo.state.value is CaptureState.Capturing)
    }

    @Test
    fun `stop while idle is a no-op and resetToIdle never clears an active capture`() {
        val f = Fixture()
        f.repo.stop(StopReason.USER_REQUESTED)
        assertEquals(CaptureState.Idle, f.repo.state.value)

        f.repo.start(token())
        f.repo.resetToIdle()
        assertTrue(f.repo.state.value is CaptureState.Capturing)

        f.repo.stop(StopReason.USER_REQUESTED)
        f.repo.resetToIdle()
        assertEquals(CaptureState.Idle, f.repo.state.value)
    }
}
