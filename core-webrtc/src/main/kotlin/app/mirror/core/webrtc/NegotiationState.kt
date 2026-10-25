package app.mirror.core.webrtc

/**
 * SDP/ICE bookkeeping for the offering side, free of WebRTC types so it can be unit tested.
 *
 *   NEW --offerSent--> AWAITING_ANSWER --acceptAnswer--> APPLYING_ANSWER --answerApplied--> ESTABLISHED
 *
 * Remote candidates may arrive before the answer has been applied (trickle ICE); they are queued and
 * released by [answerApplied]. Duplicate or unexpected answers are refused.
 */
class NegotiationState<C>(private val maxQueued: Int = 256) {

    enum class Phase { NEW, AWAITING_ANSWER, APPLYING_ANSWER, ESTABLISHED, CLOSED }

    var phase: Phase = Phase.NEW
        private set

    private val queued = ArrayDeque<C>()

    fun offerSent() {
        check(phase == Phase.NEW) { "offer already sent (phase=$phase)" }
        phase = Phase.AWAITING_ANSWER
    }

    /** @return true if the caller should now apply the answer as the remote description. */
    fun acceptAnswer(): Boolean {
        if (phase != Phase.AWAITING_ANSWER) return false
        phase = Phase.APPLYING_ANSWER
        return true
    }

    /** The remote description was applied. @return queued candidates to add now, in arrival order. */
    fun answerApplied(): List<C> {
        if (phase != Phase.APPLYING_ANSWER) return emptyList()
        phase = Phase.ESTABLISHED
        return queued.toList().also { queued.clear() }
    }

    fun answerFailed() {
        if (phase != Phase.CLOSED) phase = Phase.CLOSED
        queued.clear()
    }

    /** @return the candidate if it can be added immediately, null if it was queued or dropped. */
    fun onRemoteCandidate(candidate: C): C? = when (phase) {
        Phase.ESTABLISHED -> candidate
        Phase.AWAITING_ANSWER, Phase.APPLYING_ANSWER -> {
            if (queued.size < maxQueued) queued.addLast(candidate)
            null
        }
        Phase.NEW, Phase.CLOSED -> null
    }

    fun close() {
        phase = Phase.CLOSED
        queued.clear()
    }
}
