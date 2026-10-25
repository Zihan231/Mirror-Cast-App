package app.mirror.sender.capture

import android.content.Intent
import app.mirror.core.webrtc.CaptureConfig
import app.mirror.core.webrtc.ScreenCapture
import app.mirror.core.webrtc.ScreenCaptureListener
import app.mirror.core.webrtc.SwitchableVideoSink
import org.webrtc.VideoTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lets the streaming layer hold the video track only while it is valid. */
interface CaptureBridge {
    fun onCaptureReady(track: VideoTrack)

    /** Called synchronously before the track is disposed; release every reference to it. */
    fun onCaptureReleasing()
}

fun interface CaptureSessionFactory {
    fun create(permissionData: Intent, listener: ScreenCaptureListener, previewSink: SwitchableVideoSink): ScreenCapture
}

/**
 * Single source of truth for the capture lifecycle, shared by the service and the UI.
 * All methods are main-thread only (service callbacks and WebRTC session callbacks both are).
 */
@Singleton
class CaptureRepository @Inject constructor(
    private val sessionFactory: CaptureSessionFactory,
) {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /** Stays attached to the active track; the UI points it at a renderer. */
    val previewSink = SwitchableVideoSink()

    /** At most one; set by the stream controller. */
    var bridge: CaptureBridge? = null

    private var session: ScreenCapture? = null

    /** Incremented per session so late callbacks from a dead session are ignored. */
    private var generation = 0

    /**
     * Starts a capture with a *fresh* consent result. Must be called after the foreground service
     * is running. Returns false (and ignores [permissionData]) if a capture is already active:
     * a token is never reused and never overlapped.
     */
    fun start(permissionData: Intent): Boolean {
        if (session != null) return false

        val gen = ++generation
        _state.value = CaptureState.Starting
        try {
            val newSession = sessionFactory.create(permissionData, SessionListener(gen), previewSink)
            session = newSession
            newSession.start() // cleans itself up when it throws
        } catch (e: SecurityException) {
            fail(gen, FailureReason.PROJECTION_REJECTED, e.message)
        } catch (e: Exception) {
            fail(gen, FailureReason.INTERNAL, e.message)
        }
        return true
    }

    /** Called by the service when startForeground() was refused, before any projection exists. */
    fun reportForegroundFailure(detail: String?) = reportFailure(FailureReason.FOREGROUND_START_DENIED, detail)

    /** Records a failure that happened before a session existed. No-op while a capture is active. */
    fun reportFailure(reason: FailureReason, detail: String?) {
        if (session != null) return
        _state.value = CaptureState.Failed(reason, detail)
    }

    /** Idempotent. Only transitions to Stopped if a capture was actually in progress. */
    fun stop(reason: StopReason) {
        val s = session ?: return
        generation++ // invalidate callbacks of this session
        session = null
        s.stop()
        if (_state.value.isActive) _state.value = CaptureState.Stopped(reason)
    }

    /** Clears a terminal state (Stopped / Failed) back to Idle, e.g. after the user dismissed it. */
    fun resetToIdle() {
        if (!_state.value.isActive) _state.value = CaptureState.Idle
    }

    private fun fail(gen: Int, reason: FailureReason, detail: String?) {
        if (gen != generation) return
        session = null
        generation++
        _state.value = CaptureState.Failed(reason, detail)
    }

    private inner class SessionListener(private val gen: Int) : ScreenCaptureListener {
        private fun current() = gen == generation

        override fun onStarted(config: CaptureConfig) {
            if (!current()) return
            _state.value = CaptureState.Capturing(config.width, config.height, config.fps)
        }

        override fun onContentResized(width: Int, height: Int) {
            if (!current()) return
            (_state.value as? CaptureState.Capturing)?.let {
                _state.value = it.copy(sharedContentWidth = width, sharedContentHeight = height)
            }
        }

        override fun onContentVisibilityChanged(visible: Boolean) {
            if (!current()) return
            (_state.value as? CaptureState.Capturing)?.let {
                _state.value = it.copy(contentVisible = visible)
            }
        }

        override fun onTrackReady(track: VideoTrack) {
            if (current()) bridge?.onCaptureReady(track)
        }

        // Deliberately not gated on the generation: stop() bumps it before the session releases.
        override fun onBeforeRelease() {
            bridge?.onCaptureReleasing()
        }

        override fun onProjectionStopped() {
            if (!current()) return
            generation++
            session = null // already released itself
            _state.value = CaptureState.Stopped(StopReason.SYSTEM)
        }
    }
}
