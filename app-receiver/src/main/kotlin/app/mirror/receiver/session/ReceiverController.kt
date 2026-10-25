package app.mirror.receiver.session

import android.os.SystemClock
import android.util.Log
import app.mirror.core.signaling.ClientMessage
import app.mirror.core.signaling.ConnectionState
import app.mirror.core.signaling.DenyReasons
import app.mirror.core.signaling.ReconnectPolicy
import app.mirror.core.signaling.ServerMessage
import app.mirror.core.signaling.SignalingClient
import app.mirror.core.webrtc.IceServerConfig
import app.mirror.core.webrtc.LinkState
import app.mirror.core.webrtc.ReceiverLink
import app.mirror.core.webrtc.ReceiverLinkFactory
import app.mirror.core.webrtc.ReceiverLinkListener
import app.mirror.core.webrtc.ReceiverStats
import app.mirror.core.webrtc.SwitchableVideoSink
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
import kotlinx.coroutines.launch

class ReceiverDeviceInfo(val name: String)

/**
 * The TV side of a session: registers with the signaling server, publishes a pairing code, asks the
 * user to approve the sender, then answers the sender's offer and exposes the decoded video through
 * [videoSink]. At most one session at a time; it always ends with the link closed before anything
 * else, and the server is told with `bye`.
 *
 * If the signaling socket drops, the server ends the session on its side, so we tear down locally,
 * reconnect with backoff and register again with a fresh code.
 */
@Singleton
class ReceiverController internal constructor(
    private val linkFactory: ReceiverLinkFactory,
    private val deviceName: String,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val clientFactory: (url: String, scope: CoroutineScope, allowCleartext: Boolean) -> SignalingClient,
) {
    @Inject
    constructor(linkFactory: ReceiverLinkFactory, deviceInfo: ReceiverDeviceInfo) : this(
        linkFactory,
        deviceInfo.name,
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        SystemClock::elapsedRealtime,
        ::defaultClient,
    )

    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<ReceiverNotice?>(null)
    val notice: StateFlow<ReceiverNotice?> = _notice.asStateFlow()

    /** Long-lived: the UI points a renderer at it, the link feeds it. */
    val videoSink = SwitchableVideoSink()

    private var run: Run? = null

    /** Idempotent for the same address. Switching address ends the current session. */
    fun start(serverUrl: String) {
        if (run?.serverUrl == serverUrl) return
        stop()
        _notice.value = null
        val r = Run(serverUrl)
        run = r
        r.job = scope.launch {
            try {
                r.execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "receiver run failed", e)
                if (run === r) _state.value = ReceiverState.Failed(ReceiverFailure.SERVER_ERROR, e.message)
            } finally {
                r.close()
            }
        }
    }

    /** Disconnects completely (server change, app in background). Safe to call at any time. */
    fun stop() {
        val r = run ?: return
        run = null
        r.close()
        r.job?.cancel()
        _state.value = ReceiverState.Idle
    }

    fun approve() = run?.decide(true)

    fun reject() = run?.decide(false)

    /** User ended the running session (Back). The server is told and a new code is published. */
    fun endSession() = run?.endSession()

    fun dismissNotice() {
        _notice.value = null
    }

    // ------------------------------------------------------------------------------------------

    private sealed interface Event {
        data class Connected(val generation: Int) : Event
        data class Reconnecting(val attempt: Int) : Event
        data class Server(val message: ServerMessage) : Event
        data object LinkFailed : Event
        data class LinkError(val message: String) : Event
    }

    private inner class Run(val serverUrl: String) {
        var job: Job? = null
        private val events = Channel<Event>(Channel.UNLIMITED)
        private var client: SignalingClient? = null
        private var link: ReceiverLink? = null
        private var peerName: String = ""
        private var closed = false

        private val linkListener = object : ReceiverLinkListener {
            override fun onAnswer(sdp: String) {
                client?.send(ClientMessage.Answer(sdp))
            }

            override fun onIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                client?.send(ClientMessage.IceCandidate(candidate, sdpMid, sdpMLineIndex))
            }

            override fun onConnectionState(state: LinkState) {
                when (state) {
                    LinkState.CONNECTED -> if (!closed && link != null) _state.value = ReceiverState.Streaming(peerName)
                    LinkState.FAILED -> events.trySend(Event.LinkFailed)
                    LinkState.CONNECTING, LinkState.DISCONNECTED, LinkState.CLOSED -> Unit
                }
            }

            override fun onStats(stats: ReceiverStats) {
                (_state.value as? ReceiverState.Streaming)?.let { if (!closed) _state.value = it.copy(stats = stats) }
            }

            override fun onError(message: String) {
                events.trySend(Event.LinkError(message))
            }
        }

        /** Idempotent. The link goes first: it feeds the renderer. */
        fun close() {
            if (closed) return
            closed = true
            link?.close()
            link = null
            client?.send(ClientMessage.Bye("receiver-closed"))
            client?.close()
            events.close()
        }

        fun decide(approve: Boolean) {
            val s = _state.value as? ReceiverState.AwaitingApproval ?: return
            client?.send(ClientMessage.PairingDecision(s.pairingId, approve))
            if (approve) {
                peerName = s.senderName
                _state.value = ReceiverState.Negotiating(s.senderName)
            } else {
                // The code was consumed by the join; the TV needs a new one.
                requestCode()
            }
        }

        fun endSession() {
            if (link == null && _state.value !is ReceiverState.Negotiating && _state.value !is ReceiverState.Streaming) return
            teardownLink()
            client?.send(ClientMessage.Bye("receiver-ended"))
            requestCode()
        }

        private fun teardownLink() {
            link?.close()
            link = null
        }

        private fun requestCode() {
            _state.value = ReceiverState.Connecting(serverUrl)
            client?.send(ClientMessage.CreatePairing())
        }

        suspend fun execute() = coroutineScope {
            _state.value = ReceiverState.Connecting(serverUrl)
            val c = clientFactory(serverUrl, scope, serverUrl.startsWith("ws://"))
            client = c

            // Subscribe before connecting: the message flow does not replay.
            launch(start = CoroutineStart.UNDISPATCHED) {
                c.messages.collect { events.trySend(Event.Server(it)) }
            }
            launch {
                c.state.collect { s ->
                    when (s) {
                        is ConnectionState.Connected -> events.trySend(Event.Connected(s.generation))
                        is ConnectionState.WaitingToReconnect -> events.trySend(Event.Reconnecting(s.attempt))
                        else -> Unit
                    }
                }
            }
            try {
                c.connect()
                for (event in events) handle(c, event)
            } finally {
                coroutineContext.cancelChildren()
            }
        }

        private fun handle(c: SignalingClient, event: Event) {
            when (event) {
                is Event.Connected -> {
                    // A new socket is a new identity on the server: register again.
                    teardownLink()
                    _state.value = ReceiverState.Connecting(serverUrl)
                    c.send(ClientMessage.RegisterReceiver(deviceName))
                }
                is Event.Reconnecting -> {
                    // The server ended any session when the socket dropped.
                    if (link != null) _notice.value = ReceiverNotice.CONNECTION_FAILED
                    teardownLink()
                    _state.value = ReceiverState.Connecting(serverUrl, attempt = event.attempt)
                }
                Event.LinkFailed -> failSession(c, ReceiverNotice.CONNECTION_FAILED)
                is Event.LinkError -> failSession(c, ReceiverNotice.CONNECTION_FAILED)
                is Event.Server -> handleServer(c, event.message)
            }
        }

        private fun failSession(c: SignalingClient, notice: ReceiverNotice) {
            _notice.value = notice
            teardownLink()
            c.send(ClientMessage.Bye("connection-failed"))
            requestCode()
        }

        private fun handleServer(c: SignalingClient, m: ServerMessage) {
            when (m) {
                is ServerMessage.Registered -> requestCode()
                is ServerMessage.PairingCreated -> _state.value = ReceiverState.Pairing(
                    serverUrl = serverUrl,
                    code = m.code,
                    ttlSeconds = m.ttlSeconds,
                    expiresAtElapsedMs = clock() + m.ttlSeconds * 1000L,
                    qrPayload = m.qrPayload,
                )
                is ServerMessage.PairingExpired -> requestCode()
                is ServerMessage.PairingRequest -> {
                    _notice.value = null
                    _state.value = ReceiverState.AwaitingApproval(m.pairingId, m.senderName, clock() + m.approvalTimeoutMs)
                }
                is ServerMessage.PairingDenied -> {
                    // Approval timed out on the server; the code is gone.
                    if (m.reason == DenyReasons.TIMEOUT) _notice.value = ReceiverNotice.APPROVAL_TIMED_OUT
                    requestCode()
                }
                is ServerMessage.Paired -> {
                    teardownLink()
                    val ice = m.iceServers.map { IceServerConfig(it.urls, it.username, it.credential) }
                    peerName = m.peerName
                    _state.value = ReceiverState.Negotiating(m.peerName)
                    link = linkFactory.create(ice, videoSink, linkListener).also { it.start() }
                }
                is ServerMessage.Offer -> link?.onRemoteOffer(m.sdp)
                is ServerMessage.IceCandidate -> link?.onRemoteIceCandidate(m.candidate, m.sdpMid, m.sdpMLineIndex ?: 0)
                is ServerMessage.Bye -> {
                    val wasSession = link != null
                    teardownLink()
                    if (wasSession) _notice.value = ReceiverNotice.SESSION_ENDED
                    requestCode()
                }
                is ServerMessage.Error -> {
                    // wrong-state / busy can follow our own races (e.g. two quick Back presses); a fresh code fixes it.
                    Log.w(TAG, "server error ${m.code}: ${m.message}")
                    if (_state.value is ReceiverState.Connecting) {
                        _state.value = ReceiverState.Failed(ReceiverFailure.SERVER_ERROR, m.message)
                    }
                }
                else -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "ReceiverController"
    }
}

private fun defaultClient(url: String, scope: CoroutineScope, allowCleartext: Boolean): SignalingClient =
    SignalingClient(url = url, scope = scope, allowCleartext = allowCleartext, reconnectPolicy = ReconnectPolicy())
