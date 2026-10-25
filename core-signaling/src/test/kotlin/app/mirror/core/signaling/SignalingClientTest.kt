package app.mirror.core.signaling

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** Runs against a real OkHttp MockWebServer; uses real time with small backoffs. */
class SignalingClientTest {
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fastReconnect = ReconnectPolicy(initialDelayMs = 20, maxDelayMs = 50, jitter = 0.0)

    private fun wsUrl() = server.url("/ws").toString().replaceFirst("http", "ws")

    private fun client(policy: ReconnectPolicy = fastReconnect) =
        SignalingClient(wsUrl(), scope, allowCleartext = true, reconnectPolicy = policy)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        // MockWebServer waits for open websockets and throws if peers linger; irrelevant to the assertions.
        try {
            server.shutdown()
        } catch (_: java.io.IOException) {
        }
    }

    @Test
    fun `refuses cleartext unless explicitly allowed`() {
        assertFailsWith<IllegalArgumentException> { SignalingClient("ws://10.0.0.2:8080/ws", scope) }
        assertFailsWith<IllegalArgumentException> { SignalingClient("http://10.0.0.2/ws", scope, allowCleartext = true) }
        SignalingClient("wss://cast.example.com/ws", scope) // ok
        SignalingClient("ws://10.0.0.2:8080/ws", scope, allowCleartext = true) // ok
    }

    @Test
    fun `receives server messages and sends encoded client messages`() = runBlocking<Unit> {
        val received = CopyOnWriteArrayList<String>()
        val got = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"pairing-pending","pairingId":"p1","approvalTimeoutMs":60000}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    got.countDown()
                }
            }),
        )
        val c = client()
        // UNDISPATCHED: must be subscribed before connect(); `messages` does not replay.
        val first = async(start = CoroutineStart.UNDISPATCHED) { c.messages.first() }
        c.connect()
        withTimeout(5_000) { c.awaitConnected() }
        val msg = withTimeout(5_000) { first.await() }
        assertEquals(ServerMessage.PairingPending("p1", 60_000), msg)

        assertTrue(c.send(ClientMessage.JoinPairing(code = "123456", deviceName = "Pixel")))
        assertTrue(got.await(5, TimeUnit.SECONDS))
        assertTrue(received.single().contains(""""type":"join-pairing"""") && received.single().contains(""""code":"123456""""))
        c.close()
        withTimeout(5_000) { c.state.first { it == ConnectionState.Closed } }
    }

    @Test
    fun `reconnects automatically after the server drops the socket`() = runBlocking<Unit> {
        val dropFirst = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.close(1001, "restart")
            }
        }
        val keepSecond = object : WebSocketListener() {}
        server.enqueue(MockResponse().withWebSocketUpgrade(dropFirst))
        server.enqueue(MockResponse().withWebSocketUpgrade(keepSecond))

        val c = client()
        c.connect()
        val gen2 = withTimeout(10_000) { c.state.first { it is ConnectionState.Connected && it.generation >= 2 } }
        assertEquals(2, (gen2 as ConnectionState.Connected).generation)
        assertEquals(2, server.requestCount)
        c.close()
    }

    @Test
    fun `keeps retrying while the server refuses and recovers when it accepts`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))

        val c = client()
        c.connect()
        withTimeout(10_000) { c.awaitConnected() }
        assertEquals(3, server.requestCount)
        c.close()
    }

    @Test
    fun `gives up when the policy is exhausted`() = runBlocking<Unit> {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val c = client(ReconnectPolicy(initialDelayMs = 10, maxDelayMs = 20, jitter = 0.0, maxAttempts = 1))
        c.connect()
        withTimeout(10_000) { c.state.first { it == ConnectionState.Closed } }
        assertEquals(2, server.requestCount) // initial try + 1 retry
    }

    @Test
    fun `send returns false while disconnected`() {
        assertEquals(false, client().send(ClientMessage.Ping()))
    }
}
