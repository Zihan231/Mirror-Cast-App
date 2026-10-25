package app.mirror.core.webrtc

import org.webrtc.VideoSink

/**
 * Receive-side statistics for the debug overlay. [estimatedLatencyMs] is NOT glass-to-glass: the
 * stream carries no capture timestamps, so it is network (RTT/2) + jitter buffer + decode time.
 */
data class ReceiverStats(
    val codec: String?,
    val decoder: String?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val jitterBufferMs: Int,
    val decodeMs: Int,
    val rttMs: Int?,
    val packetsLost: Long,
) {
    val estimatedLatencyMs: Int get() = LatencyEstimate.estimate(rttMs, jitterBufferMs, decodeMs)
}

object LatencyEstimate {
    fun estimate(rttMs: Int?, jitterBufferMs: Int, decodeMs: Int): Int = (rttMs ?: 0) / 2 + jitterBufferMs + decodeMs
}

/** All callbacks arrive on the main thread. */
interface ReceiverLinkListener {
    fun onAnswer(sdp: String)
    fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
    fun onConnectionState(state: LinkState)
    fun onStats(stats: ReceiverStats)
    fun onError(message: String)
}

/** One peer connection that receives the screen. Signaling-agnostic. */
interface ReceiverLink {
    fun start()

    /** Applies the sender's offer and produces the answer. Only the first offer is honoured. */
    fun onRemoteOffer(sdp: String)
    fun onRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)

    /** Idempotent. Detaches the video sink first. */
    fun close()
}

fun interface ReceiverLinkFactory {
    /** Decoded video frames are delivered to [videoSink] (a long-lived sink owned by the caller). */
    fun create(iceServers: List<IceServerConfig>, videoSink: VideoSink, listener: ReceiverLinkListener): ReceiverLink
}
