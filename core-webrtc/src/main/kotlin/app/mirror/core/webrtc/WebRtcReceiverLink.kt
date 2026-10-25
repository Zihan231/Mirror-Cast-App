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
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

class WebRtcReceiverLinkFactory(private val runtime: WebRtcRuntime) : ReceiverLinkFactory {
    override fun create(iceServers: List<IceServerConfig>, videoSink: VideoSink, listener: ReceiverLinkListener): ReceiverLink =
        WebRtcReceiverLink(runtime, iceServers, videoSink, listener)
}

/**
 * Answers one offer and renders the incoming video into [videoSink]. DTLS-SRTP is always on.
 * Decoding uses the platform's hardware decoders through the runtime's decoder factory.
 * Main thread only; WebRTC's own threads are marshalled back before any listener call.
 */
internal class WebRtcReceiverLink(
    private val runtime: WebRtcRuntime,
    private val iceServers: List<IceServerConfig>,
    private val videoSink: VideoSink,
    private val listener: ReceiverLinkListener,
) : ReceiverLink {

    private data class RemoteCandidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)

    private val main = Handler(Looper.getMainLooper())
    private val negotiation = AnswererNegotiation<RemoteCandidate>()
    private var pc: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
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
        pc = runtime.factory.createPeerConnection(config, observer) ?: return listener.onError("PeerConnection could not be created")
    }

    override fun onRemoteOffer(sdp: String) {
        val connection = pc ?: return
        if (closed || !negotiation.acceptOffer()) return // a second offer is never honoured

        connection.setRemoteDescription(object : SimpleSdpObserver("setRemoteDescription") {
            override fun onSetSuccess() = post {
                preferReceiveCodecs(connection)
                negotiation.offerApplied().forEach(::addCandidate)
                createAnswer(connection)
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    override fun onRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        if (closed || candidate.isEmpty()) return // "" = end-of-candidates
        negotiation.onRemoteCandidate(RemoteCandidate(candidate, sdpMid, sdpMLineIndex))?.let(::addCandidate)
    }

    override fun close() {
        if (closed) return
        closed = true
        negotiation.close()
        main.removeCallbacksAndMessages(null)
        // Detach from the renderer before the connection (and with it the track) goes away.
        runCatching { videoTrack?.removeSink(videoSink) }
        videoTrack = null
        val connection = pc
        pc = null
        runCatching { connection?.close() }
        runCatching { connection?.dispose() }
        listener.onConnectionState(LinkState.CLOSED)
    }

    // ------------------------------------------------------------------------------------------

    private fun createAnswer(connection: PeerConnection) {
        connection.createAnswer(object : SimpleSdpObserver("createAnswer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                connection.setLocalDescription(object : SimpleSdpObserver("setLocalDescription") {
                    override fun onSetSuccess() = post { listener.onAnswer(sdp.description) }
                }, sdp)
            }
        }, MediaConstraints())
    }

    private fun addCandidate(c: RemoteCandidate) {
        pc?.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate))
    }

    /** H.264 first (hardware decoders), VP8 next. Only affects which codec the answer selects. */
    private fun preferReceiveCodecs(connection: PeerConnection) {
        runCatching {
            val caps = runtime.factory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val ordered = CodecOrdering.prefer(caps, { it.mimeType }, { it.parameters })
            connection.transceivers
                .filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                .forEach { it.setCodecPreferences(ordered) }
        }.onFailure { listener.onError("codec preferences not applied: ${it.message}") }
    }

    private fun attachTrack(track: MediaStreamTrack?) {
        if (track is VideoTrack && videoTrack == null) {
            videoTrack = track
            track.setEnabled(true)
            track.addSink(videoSink)
        }
        // Audio needs no wiring: the audio device module plays received tracks.
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val connection = pc ?: return
            connection.getStats { report -> post { publishStats(report) } }
            main.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private fun publishStats(report: RTCStatsReport) {
        val all = report.statsMap.values
        val inbound = all.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" } ?: return
        val m = inbound.members

        val codec = (m["codecId"] as? String)?.let { report.statsMap[it]?.members?.get("mimeType") as? String }
        val bytes = (m["bytesReceived"] as? Number)?.toLong() ?: 0L
        val now = System.currentTimeMillis()
        val kbps = if (lastStatsAtMs > 0 && now > lastStatsAtMs) ((bytes - lastBytes) * 8 / (now - lastStatsAtMs)).toInt() else 0
        lastBytes = bytes
        lastStatsAtMs = now

        val emitted = (m["jitterBufferEmittedCount"] as? Number)?.toLong() ?: 0L
        val jitterMs = if (emitted > 0) ((m["jitterBufferDelay"] as? Number)?.toDouble() ?: 0.0) / emitted * 1000 else 0.0
        val decoded = (m["framesDecoded"] as? Number)?.toLong() ?: 0L
        val decodeMs = if (decoded > 0) ((m["totalDecodeTime"] as? Number)?.toDouble() ?: 0.0) / decoded * 1000 else 0.0
        val rttMs = all.filter { it.type == "candidate-pair" && it.members["nominated"] == true }
            .mapNotNull { (it.members["currentRoundTripTime"] as? Number)?.toDouble() }
            .firstOrNull()?.let { (it * 1000).toInt() }

        listener.onStats(
            ReceiverStats(
                codec = codec,
                decoder = m["decoderImplementation"] as? String,
                width = (m["frameWidth"] as? Number)?.toInt() ?: 0,
                height = (m["frameHeight"] as? Number)?.toInt() ?: 0,
                fps = (m["framesPerSecond"] as? Number)?.toInt() ?: 0,
                bitrateKbps = kbps.coerceAtLeast(0),
                jitterBufferMs = jitterMs.toInt(),
                decodeMs = decodeMs.toInt(),
                rttMs = rttMs,
                packetsLost = (m["packetsLost"] as? Number)?.toLong() ?: 0L,
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

        override fun onTrack(transceiver: RtpTransceiver) = post { attachTrack(transceiver.receiver.track()) }

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
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private open inner class SimpleSdpObserver(private val what: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = post { listener.onError("$what failed: $error") }
        override fun onSetFailure(error: String?) = post {
            negotiation.offerFailed()
            listener.onError("$what failed: $error")
        }
    }

    private companion object {
        const val STATS_INTERVAL_MS = 1_000L
    }
}
