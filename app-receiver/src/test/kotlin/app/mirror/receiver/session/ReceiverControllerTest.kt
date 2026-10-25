package app.mirror.receiver.session

import app.mirror.core.signaling.ReconnectPolicy
import app.mirror.core.signaling.SignalingClient
import app.mirror.core.webrtc.LinkState
import app.mirror.core.webrtc.ReceiverLink
import app.mirror.core.webrtc.ReceiverLinkFactory
import app.mirror.core.webrtc.ReceiverLinkListener
import app.mirror.core.webrtc.ReceiverStats
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real signaling client against a scripted server, with a fake peer link. */
class ReceiverControllerTest {

    private class FakeLink(val listener: ReceiverLinkListener, val videoSinkWasProvided: Boolean) : ReceiverLink {
        val offers = CopyOnWriteArrayList<String>()
        val remoteIce = CopyOnWriteArrayList<String>()
        @Volatile var started = false
        @Volatile var closed = false

        override fun start() {
            started = true
        }

        override fun onRemoteOffer(sdp: String) {
            offers += sdp
            listener.onAnswer("answer-sdp")
            listener.onIceCandidate("candidate:7 1 udp 1 10.0.0.7 9 typ host", "0", 0)
        }

        override fun onRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
            remoteIce += candidate
        }

        override fun close() {
            closed = true
        }
    }

    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val received = CopyOnWriteArrayList<String>()
    private val links = CopyOnWriteArrayList<FakeLink>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val registerCount = AtomicInteger()
    private val createCount = AtomicInteger()
    private val now = AtomicInteger(0)

    private val controller = ReceiverController(
        linkFactory = ReceiverLinkFactory { ice, sink, listener ->
            assertEquals(2, ice.size)
            FakeLink(listener, sink != null).also { links += it }
        },
        deviceName = "Living Room TV",
        scope = scope,
        clock = { now.get().toLong() },
        clientFactory = { url, s, cleartext ->
            SignalingClient(url, s, cleartext, ReconnectPolicy(initialDelayMs = 20, maxDelayMs = 50, jitter = 0.0))
        },
    )

    private val url get() = "ws://${server.hostName}:${server.port}/ws"

    @After
    fun tearDown() {
        controller.stop()
        scope.cancel()
        try {
            server.shutdown()
        } catch (_: IOException) {
        }
    }

    private val pairedJson =
        """{"type":"paired","sessionId":"s1","role":"receiver","peerName":"Pixel 9","iceServers":[{"urls":["stun:x:3478"]},{"urls":["turn:x:3478"],"username":"u","credential":"c"}]}"""

    /** Plays the server: registers receivers, issues codes, pairs on approval. */
    private fun script(code: String = "123456", onExtra: (WebSocket, String) -> Unit = { _, _ -> }) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets += webSocket
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    when {
                        "\"register-receiver\"" in text -> {
                            registerCount.incrementAndGet()
                            webSocket.send("""{"type":"registered","receiverId":"r1","serverTime":1,"protocolVersion":1}""")
                        }
                        "\"create-pairing\"" in text -> {
                            createCount.incrementAndGet()
                            webSocket.send("""{"type":"pairing-created","pairingId":"p${createCount.get()}","code":"$code","expiresAt":1,"ttlSeconds":300,"qrPayload":null}""")
                        }
                        "\"pairing-decision\"" in text && "\"approve\":true" in text -> webSocket.send(pairedJson)
                        else -> onExtra(webSocket, text)
                    }
                }
            }),
        )
    }

    private val ws get() = sockets.last()

    @Test
    fun `registers with the device name and shows the pairing code`() {
        script()
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }
        val p = controller.state.value as ReceiverState.Pairing
        assertEquals("123456", p.code)
        assertEquals(url, p.serverUrl)
        assertTrue(received.any { "\"register-receiver\"" in it && "Living Room TV" in it })
    }

    @Test
    fun `nothing connects until the user approves`() {
        script()
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }

        ws.send("""{"type":"pairing-request","pairingId":"p1","senderName":"Pixel 9","approvalTimeoutMs":60000}""")
        await("approval") { controller.state.value is ReceiverState.AwaitingApproval }
        assertEquals("Pixel 9", (controller.state.value as ReceiverState.AwaitingApproval).senderName)
        assertTrue("no link before approval", links.isEmpty())
        assertFalse(received.any { "pairing-decision" in it })
    }

    @Test
    fun `approve then answers the offer, exchanges ice, and streams`() {
        script()
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }
        ws.send("""{"type":"pairing-request","pairingId":"p1","senderName":"Pixel 9","approvalTimeoutMs":60000}""")
        await("approval") { controller.state.value is ReceiverState.AwaitingApproval }

        controller.approve()
        await("link created") { links.isNotEmpty() }
        assertTrue(received.any { "\"pairing-decision\"" in it && "\"pairingId\":\"p1\"" in it && "\"approve\":true" in it })
        assertTrue(links.first().started)
        assertTrue(links.first().videoSinkWasProvided)
        assertEquals(ReceiverState.Negotiating("Pixel 9"), controller.state.value)

        ws.send("""{"type":"offer","sdp":"offer-sdp"}""")
        ws.send("""{"type":"ice-candidate","candidate":"candidate:1 1 udp 1 10.0.0.1 9 typ host","sdpMid":"0","sdpMLineIndex":0}""")
        await("offer applied") { links.first().offers == listOf("offer-sdp") }
        await("remote ice applied") { links.first().remoteIce.size == 1 }
        await("answer relayed") { received.any { "\"answer\"" in it && "answer-sdp" in it } }
        await("ice relayed") { received.any { "\"ice-candidate\"" in it && "10.0.0.7" in it } }

        links.first().listener.onConnectionState(LinkState.CONNECTED)
        await("streaming") { controller.state.value is ReceiverState.Streaming }

        val stats = ReceiverStats("video/H264", "c2.exynos.h264.decoder", 1920, 1152, 30, 4800, 40, 5, 6, 0)
        links.first().listener.onStats(stats)
        await("stats") { (controller.state.value as? ReceiverState.Streaming)?.stats == stats }
        assertEquals(40 + 5 + 3, stats.estimatedLatencyMs)
    }

    @Test
    fun `reject tells the server and publishes a fresh code`() {
        script()
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }
        ws.send("""{"type":"pairing-request","pairingId":"p1","senderName":"Stranger","approvalTimeoutMs":60000}""")
        await("approval") { controller.state.value is ReceiverState.AwaitingApproval }

        controller.reject()
        await("decision sent") { received.any { "\"approve\":false" in it } }
        await("new code") { createCount.get() == 2 && controller.state.value is ReceiverState.Pairing }
        assertTrue(links.isEmpty())
    }

    @Test
    fun `sender leaving ends the session with a notice and a new code`() {
        script()
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }
        ws.send("""{"type":"pairing-request","pairingId":"p1","senderName":"Pixel 9","approvalTimeoutMs":60000}""")
        await("approval") { controller.state.value is ReceiverState.AwaitingApproval }
        controller.approve()
        await("link") { links.isNotEmpty() }
        links.first().listener.onConnectionState(LinkState.CONNECTED)
        await("streaming") { controller.state.value is ReceiverState.Streaming }

        ws.send("""{"type":"bye","reason":"peer-disconnected"}""")
        await("link closed") { links.first().closed }
        await("new code") { createCount.get() == 2 && controller.state.value is ReceiverState.Pairing }
        assertEquals(ReceiverNotice.SESSION_ENDED, controller.notice.value)
    }

    @Test
    fun `user ending the session sends bye, closes the link and publishes a new code`() {
        script()
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }
        ws.send("""{"type":"pairing-request","pairingId":"p1","senderName":"Pixel 9","approvalTimeoutMs":60000}""")
        await("approval") { controller.state.value is ReceiverState.AwaitingApproval }
        controller.approve()
        await("link") { links.isNotEmpty() }
        links.first().listener.onConnectionState(LinkState.CONNECTED)
        await("streaming") { controller.state.value is ReceiverState.Streaming }

        controller.endSession()
        assertTrue("link closed synchronously", links.first().closed)
        await("bye sent") { received.any { "\"bye\"" in it && "receiver-ended" in it } }
        await("new code") { createCount.get() == 2 && controller.state.value is ReceiverState.Pairing }
    }

    @Test
    fun `ending a session when none is running is a no-op`() {
        script()
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }
        controller.endSession()
        assertTrue(controller.state.value is ReceiverState.Pairing)
        assertEquals(1, createCount.get())
    }

    @Test
    fun `an expired code is replaced`() {
        script()
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }
        ws.send("""{"type":"pairing-expired","pairingId":"p1"}""")
        await("second code") { createCount.get() == 2 }
    }

    @Test
    fun `approval timeout on the server brings a new code and a notice`() {
        script()
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }
        ws.send("""{"type":"pairing-request","pairingId":"p1","senderName":"Pixel 9","approvalTimeoutMs":60000}""")
        await("approval") { controller.state.value is ReceiverState.AwaitingApproval }
        ws.send("""{"type":"pairing-denied","pairingId":"p1","reason":"timeout"}""")
        await("new code") { createCount.get() == 2 && controller.state.value is ReceiverState.Pairing }
        assertEquals(ReceiverNotice.APPROVAL_TIMED_OUT, controller.notice.value)
    }

    @Test
    fun `a failed video connection ends the session and offers a new code`() {
        script()
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }
        ws.send("""{"type":"pairing-request","pairingId":"p1","senderName":"Pixel 9","approvalTimeoutMs":60000}""")
        await("approval") { controller.state.value is ReceiverState.AwaitingApproval }
        controller.approve()
        await("link") { links.isNotEmpty() }

        links.first().listener.onConnectionState(LinkState.FAILED)
        await("link closed") { links.first().closed }
        await("new code") { createCount.get() == 2 && controller.state.value is ReceiverState.Pairing }
        assertEquals(ReceiverNotice.CONNECTION_FAILED, controller.notice.value)
    }

    @Test
    fun `dropped signaling socket ends the session and re-registers on reconnect`() {
        script() // first connection
        script() // reconnection
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }
        ws.send("""{"type":"pairing-request","pairingId":"p1","senderName":"Pixel 9","approvalTimeoutMs":60000}""")
        await("approval") { controller.state.value is ReceiverState.AwaitingApproval }
        controller.approve()
        await("link") { links.isNotEmpty() }
        links.first().listener.onConnectionState(LinkState.CONNECTED)
        await("streaming") { controller.state.value is ReceiverState.Streaming }

        sockets.first().close(1001, "server restart")

        await("link closed") { links.first().closed }
        await("registered again") { registerCount.get() == 2 }
        await("fresh code") { createCount.get() == 2 && controller.state.value is ReceiverState.Pairing }
        assertEquals(ReceiverNotice.CONNECTION_FAILED, controller.notice.value)
    }

    @Test
    fun `stop disconnects, closes any link and returns to idle`() {
        script()
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }
        ws.send("""{"type":"pairing-request","pairingId":"p1","senderName":"Pixel 9","approvalTimeoutMs":60000}""")
        await("approval") { controller.state.value is ReceiverState.AwaitingApproval }
        controller.approve()
        await("link") { links.isNotEmpty() }

        controller.stop()
        assertTrue(links.first().closed)
        assertEquals(ReceiverState.Idle, controller.state.value)
        await("bye sent") { received.any { "\"bye\"" in it } }
    }

    @Test
    fun `starting the same address twice does not open a second connection`() {
        script()
        controller.start(url)
        controller.start(url)
        await("pairing") { controller.state.value is ReceiverState.Pairing }
        assertEquals(1, server.requestCount)
    }

    private fun await(what: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what (state=${controller.state.value}, received=$received)")
    }
}
