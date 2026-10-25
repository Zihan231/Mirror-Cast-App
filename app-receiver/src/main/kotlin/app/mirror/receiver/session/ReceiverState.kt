package app.mirror.receiver.session

import app.mirror.core.webrtc.ReceiverStats

sealed interface ReceiverState {
    /** No server selected / not connected. */
    data object Idle : ReceiverState

    /** [attempt] > 0 means the server could not be reached and we are retrying. */
    data class Connecting(val serverUrl: String, val attempt: Int = 0) : ReceiverState

    data class Pairing(
        val serverUrl: String,
        val code: String,
        val ttlSeconds: Int,
        /** SystemClock.elapsedRealtime() at which the code stops being valid. Local clock only. */
        val expiresAtElapsedMs: Long,
        val qrPayload: String?,
    ) : ReceiverState

    /** A sender entered the code; nothing is shown or connected until the user approves. */
    data class AwaitingApproval(
        val pairingId: String,
        val senderName: String,
        val deadlineElapsedMs: Long,
    ) : ReceiverState

    data class Negotiating(val peerName: String) : ReceiverState

    data class Streaming(val peerName: String, val stats: ReceiverStats? = null) : ReceiverState

    data class Failed(val reason: ReceiverFailure, val detail: String? = null) : ReceiverState
}

enum class ReceiverFailure { SERVER_ERROR }

/** One-line messages shown on the pairing screen after something ended. */
enum class ReceiverNotice { SESSION_ENDED, APPROVAL_TIMED_OUT, CONNECTION_FAILED }
