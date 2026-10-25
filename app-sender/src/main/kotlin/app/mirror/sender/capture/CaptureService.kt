package app.mirror.sender.capture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import app.mirror.sender.R
import app.mirror.sender.stream.StreamController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type mediaProjection) that owns the capture for its whole lifetime.
 *
 * Order of operations on ACTION_START, required on Android 14+:
 *  1. startForeground(type = mediaProjection)         <- before the token is used
 *  2. repository.start(token)                          <- getMediaProjection() + callback + VirtualDisplay
 * The token is handed over exactly once and never stored here.
 */
@AndroidEntryPoint
class CaptureService : Service() {

    @Inject lateinit var repository: CaptureRepository

    /** Injected so it is alive (and registered as the capture bridge) before any capture starts. */
    @Inject lateinit var streamController: StreamController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CaptureNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> {
                // Null or unknown intent (e.g. process restart): nothing to do. If we were started with
                // startForegroundService we must still reach startForeground to avoid the 5 s crash.
                enterForeground(getString(R.string.notification_text_starting))
                shutDown()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val token = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

        // Must be first: startForegroundService() obliges us to call this within ~5 s.
        if (!enterForeground(getString(R.string.notification_text_starting))) {
            shutDown()
            return
        }

        if (token == null) {
            repository.reportFailure(FailureReason.INTERNAL, "missing consent result")
            shutDown()
            return
        }
        if (!repository.start(token)) {
            // Already capturing: the extra token is simply dropped.
            Log.w(TAG, "ACTION_START ignored: capture already active")
        }
        observeState()
    }

    private fun handleStop() {
        // Reached via startService(); if not yet foreground we still must not crash.
        repository.stop(StopReason.USER_REQUESTED)
        shutDown()
    }

    /** @return false if the system refused foreground promotion. */
    private fun enterForeground(text: String): Boolean = try {
        ServiceCompat.startForeground(
            this,
            CaptureNotifications.NOTIFICATION_ID,
            CaptureNotifications.build(this, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        true
    } catch (e: Exception) {
        // SecurityException (no projection grant / missing permission) or
        // ForegroundServiceStartNotAllowedException (an IllegalStateException).
        Log.w(TAG, "startForeground refused", e)
        repository.reportForegroundFailure(e.javaClass.simpleName)
        false
    }

    private fun observeState() {
        if (observing?.isActive == true) return
        observing = scope.launch {
            repository.state.collect { state ->
                when (state) {
                    is CaptureState.Capturing ->
                        CaptureNotifications.update(
                            this@CaptureService,
                            getString(R.string.notification_text_capturing, state.width, state.height),
                        )
                    is CaptureState.Stopped, is CaptureState.Failed -> shutDown()
                    CaptureState.Idle, CaptureState.Starting -> Unit
                }
            }
        }
    }

    private fun shutDown() {
        observing?.cancel()
        observing = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Covers process-level teardown paths: never leave a projection running without its service.
        repository.stop(StopReason.SYSTEM)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_START = "app.mirror.sender.action.START_CAPTURE"
        const val ACTION_STOP = "app.mirror.sender.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** [resultData] is the single-use output of the consent dialog; pass a fresh one every time. */
        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }
}
