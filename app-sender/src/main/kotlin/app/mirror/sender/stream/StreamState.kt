package app.mirror.sender.stream

import app.mirror.core.webrtc.StreamStats

/** Never persisted. The code is single-use and is dropped as soon as the join was sent. */
data class StreamTarget(val serverUrl: String, val code: String) {
    override fun toString() = "StreamTarget(serverUrl=$serverUrl, code=***)"
}

enum class StreamFailure {
    SERVER_UNREACHABLE,
    SIGNALING_LOST,
    CODE_REJECTED,
    RATE_LIMITED,
    DENIED_BY_RECEIVER,
    APPROVAL_TIMEOUT,
    RECEIVER_GONE,
    SIGNALING_ERROR,
    CONNECTION_FAILED,
    INTERNAL,
}

sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data object WaitingForApproval : StreamState
    data object Negotiating : StreamState
    data class Streaming(val stats: StreamStats? = null) : StreamState
    data class Failed(val reason: StreamFailure, val detail: String? = null) : StreamState

    /** The receiver ended the session. */
    data object Ended : StreamState
}

val StreamState.isBusy: Boolean
    get() = this is StreamState.Connecting || this is StreamState.WaitingForApproval ||
        this is StreamState.Negotiating || this is StreamState.Streaming
