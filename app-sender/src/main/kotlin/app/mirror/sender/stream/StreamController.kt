package app.mirror.sender.stream

import android.util.Log
import app.mirror.core.signaling.ClientMessage
import app.mirror.core.signaling.ConnectionState
import app.mirror.core.signaling.DenyReasons
import app.mirror.core.signaling.ErrorCodes
import app.mirror.core.signaling.ReconnectPolicy
import app.mirror.core.signaling.ServerMessage
import app.mirror.core.signaling.SignalingClient
import app.mirror.core.webrtc.IceServerConfig
import app.mirror.core.webrtc.LinkState
import app.mirror.core.webrtc.SenderLink
import app.mirror.core.webrtc.SenderLinkFactory
import app.mirror.core.webrtc.SenderLinkListener
import app.mirror.core.webrtc.StreamStats
import app.mirror.sender.capture.CaptureBridge
import app.mirror.sender.capture.CaptureRepository
import app.mirror.sender.capture.StopReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DeviceInfo(val name: String)

/**
 * Connects the running screen capture to a receiver: signaling (join -> approval -> paired),
 * then one [SenderLink] that sends the offer and applies the answer and ICE.
 *
 * The capture track is only borrowed. [onCaptureReleasing] is called synchronously by the capture
 * layer right before the track is disposed and closes the link first.
 *
 * Main-thread only, apart from the signaling callbacks which are marshalled by coroutines.
 */
@Singleton
class StreamController internal constructor(
    private val captureRepository: CaptureRepository,
    private val linkFactory: SenderLinkFactory,
    private val deviceName: String,
    private val scope: CoroutineScope,
    private val clientFactory: (url: String, scope: CoroutineScope, allowCleartext: Boolean) -> SignalingClient,
) : CaptureBridge {

    @Inject
    constructor(
        captureRepository: CaptureRepository,
        linkFactory: SenderLinkFactory,
        deviceInfo: DeviceInfo,
    ) : this(
        captureRepository,
        linkFactory,
        deviceInfo.name,
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ::defaultClient,
    )

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private var pendingTarget: StreamTarget? = null
    private var track: org.webrtc.VideoTrack? = null
    private var run: Run? = null

    init {
        captureRepository.bridge = this
    }

    /** Connect automatically as soon as the capture that is about to start has a track. */
    fun setPendingTarget(target: StreamTarget?) {
        pendingTarget = target
    }

    /** Connect now using the capture that is already running. @return false if nothing is capturing. */
    fun connect(target: StreamTarget): Boolean {
        val t = track ?: return false
        startRun(target) { ice, listener -> linkFactory.create(t, ice, listener) }
        return true
    }

    override fun onCaptureReady(track: org.webrtc.VideoTrack) {
        this.track = track
        val target = pendingTarget ?: return
        pendingTarget = null
        startRun(target) { ice, listener -> linkFactory.create(track, ice, listener) }
    }

    override fun onCaptureReleasing() {
        track = null
        pendingTarget = null
        val r = run
        run = null
        r?.close(sendBye = true)
        r?.job?.cancel()
        if (_state.value.let { it !is StreamState.Failed && it !is StreamState.Ended }) _state.value = StreamState.Idle
    }

    internal fun startRun(target: StreamTarget, buildLink: (List<IceServerConfig>, SenderLinkListener) -> SenderLink) {
        run?.let {
            it.close(sendBye = true)
            it.job?.cancel()
        }
        val r = Run(target, buildLink)
        run = r
        r.job = scope.launch {
            try {
                r.execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "stream run failed", e)
                r.fail(StreamFailure.INTERNAL, e.message)
            } finally {
                r.close(sendBye = true)
                if (run === r) run = null
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    private sealed interface Event {
        data class Server(val message: ServerMessage) : Event
        data object SignalingClosed : Event
        data object LinkFailed : Event
        data class LinkError(val message: String) : Event
    }

    private inner class Run(
        private val target: StreamTarget,
        private val buildLink: (List<IceServerConfig>, SenderLinkListener) -> SenderLink,
    ) {
        var job: Job? = null
        private val events = Channel<Event>(Channel.UNLIMITED)
        private var client: SignalingClient? = null
        private var link: SenderLink? = null
        private var paired = false
        private var closed = false
        private var lastStats: StreamStats? = null

        private val linkListener = object : SenderLinkListener {
            override fun onOffer(sdp: String) {
                client?.send(ClientMessage.Offer(sdp))
            }

            override fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                client?.send(ClientMessage.IceCandidate(candidate, sdpMid, sdpMLineIndex))
            }

            override fun onConnectionState(state: LinkState) {
                when (state) {
                    LinkState.CONNECTED -> if (!closed) _state.value = StreamState.Streaming(lastStats)
                    LinkState.FAILED -> events.trySend(Event.LinkFailed)
                    LinkState.CONNECTING, LinkState.DISCONNECTED, LinkState.CLOSED -> Unit
                }
            }

            override fun onStats(stats: StreamStats) {
                lastStats = stats
                if (!closed && _state.value is StreamState.Streaming) _state.value = StreamState.Streaming(stats)
            }

            override fun onError(message: String) {
                events.trySend(Event.LinkError(message))
            }
        }

        fun fail(reason: StreamFailure, detail: String? = null) {
            if (closed) return
            _state.value = StreamState.Failed(reason, detail)
        }

        /** Idempotent. Closes the link before anything else: it holds the capture track. */
        fun close(sendBye: Boolean) {
            if (closed) return
            closed = true
            link?.close()
            link = null
            if (paired && sendBye) client?.send(ClientMessage.Bye("capture-stopped"))
            client?.close()
            events.close()
        }

        suspend fun execute() = coroutineScope {
            _state.value = StreamState.Connecting
            val c = clientFactory(target.serverUrl, scope, target.serverUrl.startsWith("ws://"))
            client = c

            // Subscribe before connecting: the message flow does not replay.
            launch(start = CoroutineStart.UNDISPATCHED) {
                c.messages.collect { events.trySend(Event.Server(it)) }
            }
            launch {
                c.state.collect { s ->
                    if (s is ConnectionState.Closed || s is ConnectionState.WaitingToReconnect) events.trySend(Event.SignalingClosed)
                }
            }

            try {
                c.connect()
                val up = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { c.state.first { it is ConnectionState.Connected || it is ConnectionState.Closed } }
                if (up !is ConnectionState.Connected) return@coroutineScope fail(StreamFailure.SERVER_UNREACHABLE)

                c.send(ClientMessage.JoinPairing(code = target.code, deviceName = deviceName))

                for (event in events) {
                    if (!handle(event)) break
                }
            } finally {
                coroutineContext.cancelChildren()
            }
        }

        /** @return false when the run is over. */
        private fun handle(event: Event): Boolean {
            when (event) {
                Event.SignalingClosed -> {
                    // After the connection is up the media path no longer needs the server.
                    if (_state.value !is StreamState.Streaming) {
                        fail(StreamFailure.SIGNALING_LOST)
                        return false
                    }
                }
                Event.LinkFailed -> {
                    fail(StreamFailure.CONNECTION_FAILED)
                    return false
                }
                is Event.LinkError -> {
                    fail(StreamFailure.CONNECTION_FAILED, event.message)
                    return false
                }
                is Event.Server -> return handleServer(event.message)
            }
            return true
        }

        private fun handleServer(m: ServerMessage): Boolean {
            when (m) {
                is ServerMessage.PairingPending -> _state.value = StreamState.WaitingForApproval
                is ServerMessage.PairingDenied -> {
                    fail(
                        when (m.reason) {
                            DenyReasons.REJECTED -> StreamFailure.DENIED_BY_RECEIVER
                            DenyReasons.TIMEOUT -> StreamFailure.APPROVAL_TIMEOUT
                            else -> StreamFailure.RECEIVER_GONE
                        },
                    )
                    return false
                }
                is ServerMessage.Paired -> {
                    paired = true
                    _state.value = StreamState.Negotiating
                    val ice = m.iceServers.map { IceServerConfig(it.urls, it.username, it.credential) }
                    link = buildLink(ice, linkListener).also { it.start() }
                }
                is ServerMessage.Answer -> link?.onRemoteAnswer(m.sdp)
                is ServerMessage.IceCandidate -> link?.onRemoteIceCandidate(m.candidate, m.sdpMid, m.sdpMLineIndex ?: 0)
                is ServerMessage.Bye -> {
                    _state.value = StreamState.Ended
                    // The receiver is gone; there is nothing left to capture for.
                    captureRepository.stop(StopReason.REMOTE_ENDED)
                    return false
                }
                is ServerMessage.Error -> {
                    fail(
                        when (m.code) {
                            ErrorCodes.INVALID_CODE -> StreamFailure.CODE_REJECTED
                            ErrorCodes.RATE_LIMITED -> StreamFailure.RATE_LIMITED
                            ErrorCodes.RECEIVER_UNAVAILABLE -> StreamFailure.RECEIVER_GONE
                            else -> StreamFailure.SIGNALING_ERROR
                        },
                        if (m.code == ErrorCodes.RATE_LIMITED) "${m.retryAfterMs ?: 0}" else m.message,
                    )
                    return false
                }
                else -> Unit // registered / pairing-created / pong / ice-servers: not for the sender
            }
            return true
        }
    }

    private companion object {
        const val TAG = "StreamController"
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}

private fun defaultClient(url: String, scope: CoroutineScope, allowCleartext: Boolean): SignalingClient =
    SignalingClient(
        url = url,
        scope = scope,
        allowCleartext = allowCleartext,
        // Pairing codes are single use; a silent reconnect could not re-pair anyway.
        reconnectPolicy = ReconnectPolicy.NONE,
    )
