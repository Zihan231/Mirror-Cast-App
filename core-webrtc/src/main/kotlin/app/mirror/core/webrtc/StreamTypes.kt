package app.mirror.core.webrtc

data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

/**
 * Target bitrates for 1080p30 screen content: 4-6 Mbps, starting at 5. A high floor matters for
 * screen content: a hardware encoder spends its bit budget on the first frame of a still screen
 * and never refines it, so a low budget there means blurry text until something changes.
 * Static screens cost almost nothing on the wire, so the floor does not waste bandwidth.
 */
data class StreamQuality(
    val minBitrateBps: Int = 4_000_000,
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
