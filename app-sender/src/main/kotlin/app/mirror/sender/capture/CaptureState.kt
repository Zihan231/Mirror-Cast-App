package app.mirror.sender.capture

enum class StopReason {
    /** Stop button in the app or the notification. */
    USER_REQUESTED,

    /** MediaProjection.Callback.onStop: status-bar chip, screen lock, another app took over, revoked. */
    SYSTEM,
}

enum class FailureReason {
    /** Android rejected the projection token (SecurityException, stale or invalid consent). */
    PROJECTION_REJECTED,

    /** startForeground(mediaProjection) was refused. */
    FOREGROUND_START_DENIED,

    INTERNAL,
}

sealed interface CaptureState {
    data object Idle : CaptureState

    /** Foreground service is up, the projection is being created. */
    data object Starting : CaptureState

    data class Capturing(
        val width: Int,
        val height: Int,
        val fps: Int,
        /** Android 14+: size of the shared content when it differs from the display (single-app share). */
        val sharedContentWidth: Int? = null,
        val sharedContentHeight: Int? = null,
        /** Android 14+: false while the shared app is not visible. */
        val contentVisible: Boolean = true,
    ) : CaptureState

    data class Stopped(val reason: StopReason) : CaptureState

    data class Failed(val reason: FailureReason, val detail: String? = null) : CaptureState
}

val CaptureState.isActive: Boolean
    get() = this is CaptureState.Starting || this is CaptureState.Capturing
