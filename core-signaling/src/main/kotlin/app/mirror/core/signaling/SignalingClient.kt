package app.mirror.core.signaling

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState

    /** [generation] increases on every (re)connect, so collectors can tell a reconnect from a no-op. */
    data class Connected(val generation: Int) : ConnectionState
    data class WaitingToReconnect(val attempt: Int, val delayMs: Long, val cause: String?) : ConnectionState
    data object Closed : ConnectionState
}

fun interface SignalingLogger {
    fun log(message: String)

    companion object {
        val NONE = SignalingLogger { }
    }
}

/**
 * WebSocket signaling client with auto-reconnect.
 *
 * - Collect [messages] *before* [connect]; it does not replay.
 * - The server forgets everything when a socket drops, so after every [ConnectionState.Connected]
 *   the caller must re-register / re-pair (watch `generation`). This class deliberately does not
 *   replay messages: stale pairing state must not be resurrected.
 * - `wss://` only, unless [allowCleartext] is set (LAN mode).
 * - An instance is single-use: after [close] create a new one.
 */
class SignalingClient(
    private val url: String,
    private val scope: CoroutineScope,
    private val allowCleartext: Boolean = false,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val http: OkHttpClient = defaultHttpClient(),
    private val random: Random = Random.Default,
    private val logger: SignalingLogger = SignalingLogger.NONE,
) {
    init {
        val secure = url.startsWith("wss://")
        val cleartext = url.startsWith("ws://")
        require(secure || (cleartext && allowCleartext)) {
            "Signaling URL must be wss:// (ws:// only with allowCleartext = true): $url"
        }
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<ServerMessage>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closing = false
    private var job: Job? = null
    private val generation = AtomicInteger(0)

    /** Idempotent. Starts the connect / reconnect loop in [scope]. */
    @Synchronized
    fun connect() {
        if (job?.isActive == true || closing) return
        job = scope.launch { runLoop() }
    }

    /** Returns false when there is no open socket (the message is *not* queued). */
    fun send(message: ClientMessage): Boolean {
        val ws = socket ?: return false
        return ws.send(SignalingJson.encode(message))
    }

    suspend fun awaitConnected(): ConnectionState.Connected = state.first { it is ConnectionState.Connected } as ConnectionState.Connected

    /** Graceful close; stops reconnecting. */
    fun close() {
        closing = true
        val ws = socket
        if (ws == null) {
            job?.cancel()
            _state.value = ConnectionState.Closed
            return
        }
        ws.close(1000, "client closing")
        // If the peer never answers the close handshake, drop the connection.
        scope.launch {
            delay(2_000)
            socket?.cancel()
        }
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive && !closing) {
            _state.value = ConnectionState.Connecting(attempt)
            val cause = connectOnce(onOpened = {
                attempt = 0
                _state.value = ConnectionState.Connected(generation.incrementAndGet())
            })
            if (closing) break
            attempt += 1
            if (reconnectPolicy.exhausted(attempt)) {
                logger.log("giving up after ${attempt - 1} reconnect attempts")
                break
            }
            val wait = reconnectPolicy.delayMs(attempt, random)
            logger.log("disconnected ($cause); retry #$attempt in ${wait}ms")
            _state.value = ConnectionState.WaitingToReconnect(attempt, wait, cause)
            delay(wait)
        }
        _state.value = ConnectionState.Closed
    }

    /** Opens one socket and suspends until it is gone. Returns a short cause string. */
    private suspend fun connectOnce(onOpened: () -> Unit): String? = suspendCancellableCoroutine { cont ->
        var ws: WebSocket? = null
        fun finish(cause: String?) {
            if (socket === ws) socket = null
            if (cont.isActive) cont.resume(cause)
        }

        ws = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket = webSocket
                    if (closing) {
                        webSocket.close(1000, "client closing")
                        return
                    }
                    onOpened()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = SignalingJson.decodeServerMessage(text)
                    if (msg == null) {
                        logger.log("ignored undecodable server message")
                        return
                    }
                    if (!_messages.tryEmit(msg)) logger.log("message buffer full; dropped ${msg::class.simpleName}")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finish("closed $code $reason".trim())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finish(response?.let { "HTTP ${it.code}" } ?: t.message ?: t.javaClass.simpleName)
                }
            },
        )
        cont.invokeOnCancellation { ws?.cancel() }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS) // detects dead connections; server also pings
            .retryOnConnectionFailure(true)
            .build()
    }
}
