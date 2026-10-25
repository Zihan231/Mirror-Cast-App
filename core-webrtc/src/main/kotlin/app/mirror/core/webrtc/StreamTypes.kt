package app.mirror.core.webrtc

data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

/**
 * Target bitrates for 1080p30 screen content. The sender starts at [startBitrateBps] and
 * congestion control moves within [minBitrateBps]..[maxBitrateBps]; on a good Wi-Fi link it
 * settles in the 4-6 Mbps range. The floor is deliberately low so a weak link degrades
 * (fps first, resolution is kept) instead of stalling.
 */
data class StreamQuality(
    val minBitrateBps: Int = 1_000_000,
    val startBitrateBps: Int = 5_000_000,
    val maxBitrateBps: Int = 6_000_000,
) {
    init {
        require(minBitrateBps in 1..startBitrateBps && startBitrateBps <= maxBitrateBps)
    }

    companion object {
        val DEFAULT = StreamQuality()
    }
}

enum class LinkState { CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

data class StreamStats(
    /** e.g. "video/H264". */
    val codec: String?,
    /** Encoder implementation reported by WebRTC, e.g. "c2.exynos.h264.encoder" (hardware) or "libvpx" (software). */
    val encoder: String?,
    val bitrateKbps: Int,
    val fps: Int,
    val width: Int,
    val height: Int,
)

/** All callbacks arrive on the main thread. */
interface SenderLinkListener {
    fun onOffer(sdp: String)
    fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
    fun onConnectionState(state: LinkState)
    fun onStats(stats: StreamStats)
    fun onError(message: String)
}

/** One peer connection that sends the screen track. Signaling-agnostic. */
interface SenderLink {
    /** Creates the peer connection and produces the offer. */
    fun start()
    fun onRemoteAnswer(sdp: String)
    fun onRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)

    /** Idempotent. Must be called before the track's owner disposes the track. */
    fun close()
}

fun interface SenderLinkFactory {
    fun create(track: org.webrtc.VideoTrack, iceServers: List<IceServerConfig>, listener: SenderLinkListener): SenderLink
}
