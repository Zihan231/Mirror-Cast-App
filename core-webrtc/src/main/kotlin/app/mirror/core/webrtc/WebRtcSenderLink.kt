package app.mirror.core.webrtc

import android.os.Handler
import android.os.Looper
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

class WebRtcSenderLinkFactory(
    private val runtime: WebRtcRuntime,
    private val quality: StreamQuality = StreamQuality.DEFAULT,
) : SenderLinkFactory {
    override fun create(track: VideoTrack, iceServers: List<IceServerConfig>, listener: SenderLinkListener): SenderLink =
        WebRtcSenderLink(runtime, track, iceServers, quality, listener)
}

/**
 * Sends [track] to one remote peer (DTLS-SRTP is always on). Hardware H.264 is preferred through
 * codec preferences, VP8 is the fallback; bitrate is capped for 1080p30 screen content.
 *
 * The track is owned by the capture session and is never disposed here, but [close] must run
 * before the owner disposes it. Not thread-safe: call from the main thread. WebRTC's own threads
 * are marshalled back to main before any listener callback.
 */
internal class WebRtcSenderLink(
    private val runtime: WebRtcRuntime,
    private val track: VideoTrack,
    private val iceServers: List<IceServerConfig>,
    private val quality: StreamQuality,
    private val listener: SenderLinkListener,
) : SenderLink {

    private data class RemoteCandidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)

    private val main = Handler(Looper.getMainLooper())
    private val negotiation = NegotiationState<RemoteCandidate>()
    private var pc: PeerConnection? = null
    private var closed = false
    private var lastBytes = 0L
    private var lastStatsAtMs = 0L

    override fun start() {
        check(pc == null && !closed) { "start() may only be called once" }
        val config = PeerConnection.RTCConfiguration(
            iceServers.map { s ->
                PeerConnection.IceServer.builder(s.urls).apply {
                    s.username?.let(::setUsername)
                    s.credential?.let(::setPassword)
                }.createIceServer()
            },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val connection = runtime.factory.createPeerConnection(config, observer)
            ?: return listener.onError("PeerConnection could not be created")
        pc = connection

        val transceiver = connection.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
        )
        preferCodecs(transceiver)
        configureEncoding(transceiver.sender)
        connection.setBitrate(quality.minBitrateBps, quality.startBitrateBps, quality.maxBitrateBps)

        connection.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                connection.setLocalDescription(object : SimpleSdpObserver("setLocalDescription") {
                    override fun onSetSuccess() = post {
                        negotiation.offerSent()
                        listener.onOffer(sdp.description)
                    }
                }, sdp)
            }
        }, MediaConstraints())
    }

    override fun onRemoteAnswer(sdp: String) {
        val connection = pc ?: return
        if (closed || !negotiation.acceptAnswer()) return // duplicate or out-of-order answer
        connection.setRemoteDescription(object : SimpleSdpObserver("setRemoteDescription") {
            override fun onSetSuccess() = post {
                negotiation.answerApplied().forEach(::addCandidate)
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun onRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        if (closed || candidate.isEmpty()) return // "" is end-of-candidates; libwebrtc needs no signal for it
        negotiation.onRemoteCandidate(RemoteCandidate(candidate, sdpMid, sdpMLineIndex))?.let(::addCandidate)
    }

    override fun close() {
        if (closed) return
        closed = true
        negotiation.close()
        main.removeCallbacksAndMessages(null)
        val connection = pc
        pc = null
        runCatching { connection?.close() }
        runCatching { connection?.dispose() }
        listener.onConnectionState(LinkState.CLOSED)
    }

    // ------------------------------------------------------------------------------------------

    private fun addCandidate(c: RemoteCandidate) {
        pc?.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate))
    }

    private fun preferCodecs(transceiver: RtpTransceiver) {
        runCatching {
            val caps = runtime.factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val ordered = CodecOrdering.prefer(caps, { it.mimeType }, { it.parameters })
            transceiver.setCodecPreferences(ordered)
        }.onFailure { listener.onError("codec preferences not applied: ${it.message}") }
    }

    private fun configureEncoding(sender: RtpSender) {
        val params = sender.parameters
        params.encodings.firstOrNull()?.let { enc ->
            enc.maxBitrateBps = quality.maxBitrateBps
            enc.minBitrateBps = quality.minBitrateBps
            enc.maxFramerate = 30
            enc.scaleResolutionDownBy = 1.0 // never shrink text; drop frames instead
        }
        // Screen content: keep resolution, shed frame rate when bandwidth or CPU is short.
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        sender.parameters = params
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val connection = pc ?: return
            connection.getStats { report -> post { publishStats(report) } }
            main.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private fun publishStats(report: RTCStatsReport) {
        if (closed) return
        val stats = report.statsMap.values
        val outbound = stats.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" } ?: return
        val codecId = outbound.members["codecId"] as? String
        val codec = codecId?.let { report.statsMap[it]?.members?.get("mimeType") as? String }

        val bytes = (outbound.members["bytesSent"] as? Number)?.toLong() ?: 0L
        val now = System.currentTimeMillis()
        val kbps = if (lastStatsAtMs > 0 && now > lastStatsAtMs) {
            ((bytes - lastBytes) * 8 / (now - lastStatsAtMs)).toInt() // bits per ms == kbit/s
        } else {
            0
        }
        lastBytes = bytes
        lastStatsAtMs = now

        listener.onStats(
            StreamStats(
                codec = codec,
                encoder = outbound.members["encoderImplementation"] as? String,
                bitrateKbps = kbps.coerceAtLeast(0),
                fps = (outbound.members["framesPerSecond"] as? Number)?.toInt() ?: 0,
                width = (outbound.members["frameWidth"] as? Number)?.toInt() ?: 0,
                height = (outbound.members["frameHeight"] as? Number)?.toInt() ?: 0,
            ),
        )
    }

    private fun post(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) = post {
            listener.onIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = post {
            when (newState) {
                PeerConnection.PeerConnectionState.NEW,
                PeerConnection.PeerConnectionState.CONNECTING -> listener.onConnectionState(LinkState.CONNECTING)
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    listener.onConnectionState(LinkState.CONNECTED)
                    lastStatsAtMs = 0
                    main.removeCallbacks(statsTick)
                    main.postDelayed(statsTick, STATS_INTERVAL_MS)
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> listener.onConnectionState(LinkState.DISCONNECTED)
                PeerConnection.PeerConnectionState.FAILED -> listener.onConnectionState(LinkState.FAILED)
                PeerConnection.PeerConnectionState.CLOSED -> Unit // close() reports it
            }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit // single offer; capture-size changes never renegotiate
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private open inner class SimpleSdpObserver(private val what: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = post { listener.onError("$what failed: $error") }
        override fun onSetFailure(error: String?) = post {
            negotiation.answerFailed()
            listener.onError("$what failed: $error")
        }
    }

    private companion object {
        const val STATS_INTERVAL_MS = 2_000L
    }
}
