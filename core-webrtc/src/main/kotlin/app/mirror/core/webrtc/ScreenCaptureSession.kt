package app.mirror.core.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** Callbacks are always delivered on the main thread. */
interface ScreenCaptureListener {
    fun onStarted(config: CaptureConfig)

    /** Android 14+: size of the shared content changed (single-app sharing, rotation of that app). */
    fun onContentResized(width: Int, height: Int)

    /** Android 14+: false when the shared app is no longer visible (frames stop or go black). */
    fun onContentVisibilityChanged(visible: Boolean)

    /** The system or the user ended the projection. The session is already torn down. */
    fun onProjectionStopped()

    /** The video track exists and is producing frames. Valid until [onBeforeRelease] returns. */
    fun onTrackReady(track: VideoTrack) {}

    /**
     * The session is about to dispose its track. Anything holding the track (a peer connection)
     * must let go of it synchronously here, before this returns.
     */
    fun onBeforeRelease() {}
}

interface ScreenCapture {
    /** Throws on failure; the session is already cleaned up when it does. */
    fun start()

    /** Idempotent, main thread. */
    fun stop()
}

/**
 * One MediaProjection grant == one session. The permission result [Intent] is single-use: it is
 * dropped as soon as capture has been attempted and the session can never be restarted.
 *
 * Preconditions enforced by the caller (the foreground service):
 *  - a foreground service of type `mediaProjection` is already running,
 *  - [permissionData] came from a fresh consent dialog.
 *
 * `ScreenCapturerAndroid.startCapture` obtains the projection and registers [projectionCallback]
 * *before* it creates the VirtualDisplay, which is the order Android 14+ requires.
 */
class ScreenCaptureSession(
    context: Context,
    private val runtime: WebRtcRuntime,
    permissionData: Intent,
    private val config: CaptureConfig,
    private val previewSink: VideoSink,
    private val listener: ScreenCaptureListener,
) : ScreenCapture {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var permissionData: Intent? = permissionData

    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var capturer: ScreenCapturerAndroid? = null

    private var started = false
    private var stopped = false

    // These arrive on the SurfaceTextureHelper thread; hop to main before touching anything.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { handleProjectionStopped() }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            main.post { if (!stopped) listener.onContentResized(width, height) }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            main.post { if (!stopped) listener.onContentVisibilityChanged(isVisible) }
        }
    }

    override fun start() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "start() must run on the main thread" }
        check(!started && !stopped) { "a capture session can only be started once" }
        started = true
        val data = permissionData ?: error("permission result already consumed")
        permissionData = null // never keep the token around, whatever happens next

        try {
            val helper = SurfaceTextureHelper.create("ScreenCaptureThread", runtime.eglBase.eglBaseContext)
                .also { this.helper = it }
            val source = runtime.factory.createVideoSource(/* isScreencast = */ true)
                .also { this.source = it }
            // VirtualDisplay ignores the fps argument; cap the pipeline instead.
            source.adaptOutputFormat(config.width, config.height, config.fps)

            val capturer = ScreenCapturerAndroid(data, projectionCallback).also { this.capturer = it }
            capturer.initialize(helper, appContext, source.capturerObserver)

            val track = runtime.factory.createVideoTrack("screen", source).also { this.track = it }
            track.addSink(previewSink)

            capturer.startCapture(config.width, config.height, config.fps)
        } catch (t: Throwable) {
            releaseResources()
            throw t
        }
        listener.onStarted(config)
        track?.let(listener::onTrackReady)
    }

    override fun stop() {
        if (stopped) return
        releaseResources()
    }

    private fun handleProjectionStopped() {
        if (stopped) return
        releaseResources()
        listener.onProjectionStopped()
    }

    private fun releaseResources() {
        if (!stopped && track != null) runCatching { listener.onBeforeRelease() }
        stopped = true
        permissionData = null
        // Order matters: stop the producer, then dispose consumers, then the thread helper.
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { track?.removeSink(previewSink) }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { helper?.dispose() }
        capturer = null
        track = null
        source = null
        helper = null
    }
}
