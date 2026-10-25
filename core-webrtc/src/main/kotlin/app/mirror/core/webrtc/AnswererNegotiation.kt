package app.mirror.core.webrtc

/**
 * SDP/ICE bookkeeping for the answering side, free of WebRTC types so it can be unit tested.
 *
 *   NEW --acceptOffer--> APPLYING_OFFER --offerApplied--> ESTABLISHED
 *
 * Exactly one offer is honoured: the stream never renegotiates (rotation and resolution changes
 * happen inside the existing session), so a second offer is refused. Candidates that arrive before
 * the offer has been applied are queued, bounded, and released by [offerApplied].
 */
class AnswererNegotiation<C>(private val maxQueued: Int = 256) {

    enum class Phase { NEW, APPLYING_OFFER, ESTABLISHED, CLOSED }

    var phase: Phase = Phase.NEW
        private set

    private val queued = ArrayDeque<C>()

    /** @return true if the caller should apply this offer. */
    fun acceptOffer(): Boolean {
        if (phase != Phase.NEW) return false
        phase = Phase.APPLYING_OFFER
        return true
    }

    /** The offer is the remote description now. @return queued candidates to add, in arrival order. */
    fun offerApplied(): List<C> {
        if (phase != Phase.APPLYING_OFFER) return emptyList()
        phase = Phase.ESTABLISHED
        return queued.toList().also { queued.clear() }
    }

    fun offerFailed() = close()

    /** @return the candidate if it can be added immediately; null if queued or dropped. */
    fun onRemoteCandidate(candidate: C): C? = when (phase) {
        Phase.ESTABLISHED -> candidate
        // Trickle ICE may legitimately beat the offer's processing, never the offer itself in
        // practice, but queueing in NEW is harmless and bounded.
        Phase.NEW, Phase.APPLYING_OFFER -> {
            if (queued.size < maxQueued) queued.addLast(candidate)
            null
        }
        Phase.CLOSED -> null
    }

    fun close() {
        phase = Phase.CLOSED
        queued.clear()
    }
}
