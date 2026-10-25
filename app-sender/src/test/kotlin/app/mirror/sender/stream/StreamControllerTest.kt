package app.mirror.sender.stream

import android.content.Intent
import app.mirror.core.signaling.ReconnectPolicy
import app.mirror.core.signaling.SignalingClient
import app.mirror.core.webrtc.CaptureConfig
import app.mirror.core.webrtc.LinkState
import app.mirror.core.webrtc.ScreenCapture
import app.mirror.core.webrtc.ScreenCaptureListener
import app.mirror.core.webrtc.SenderLink
import app.mirror.core.webrtc.SenderLinkFactory
import app.mirror.core.webrtc.SenderLinkListener
import app.mirror.sender.capture.CaptureRepository
import app.mirror.sender.capture.CaptureState
import app.mirror.sender.capture.StopReason
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
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

/** Drives the real signaling client against a scripted WebSocket server with a fake peer link. */
class StreamControllerTest {

    private class FakeLink(val listener: SenderLinkListener) : SenderLink {
        val answers = CopyOnWriteArrayList<String>()
        val remoteIce = CopyOnWriteArrayList<Triple<String, String?, Int>>()
        @Volatile var started = false
        @Volatile var closed = false

        override fun start() {
            started = true
            listener.onOffer("offer-sdp")
            listener.onIceCandidate("candidate:1 1 udp 1 10.0.0.1 9 typ host", "0", 0)
        }

        override fun onRemoteAnswer(sdp: String) {
            answers += sdp
        }

        override fun onRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
            remoteIce += Triple(candidate, sdpMid, sdpMLineIndex)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeCaptureSession(private val listener: ScreenCaptureListener) : ScreenCapture {
        override fun start() = listener.onStarted(CaptureConfig(864, 1920, 30))
        override fun stop() = Unit
    }

    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val received = CopyOnWriteArrayList<String>()
    private val links = CopyOnWriteArrayList<FakeLink>()

    private val repo = CaptureRepository { _, listener, _ -> FakeCaptureSession(listener) }
    private val controller = StreamController(
        captureRepository = repo,
        linkFactory = SenderLinkFactory { _, _, _ -> error("the real link factory must not be used in this test") },
        deviceName = "Test Phone",
        scope = scope,
        clientFactory = { url, s, cleartext -> SignalingClient(url, s, cleartext, ReconnectPolicy.NONE) },
    )

    private val target get() = StreamTarget("ws://${server.hostName}:${server.port}/ws", "123456")

    @After
    fun tearDown() {
        scope.cancel()
        try {
            server.shutdown()
        } catch (_: IOException) {
        }
    }

    private fun script(onText: (WebSocket, String) -> Unit) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    onText(webSocket, text)
                }

                override fun onOpen(webSocket: WebSocket, response: Response) = Unit
            }),
        )
    }

    private val pairedJson =
        """{"type":"paired","sessionId":"s1","role":"sender","peerName":"Browser","iceServers":[{"urls":["stun:x:3478"]},{"urls":["turn:x:3478"],"username":"u","credential":"c"}]}"""

    /** Plays the receiver side of a successful pairing and negotiation. */
    private fun scriptHappyReceiver(afterAnswer: (WebSocket) -> Unit = {}) = script { ws, text ->
        when {
            "\"join-pairing\"" in text -> {
                ws.send("""{"type":"pairing-pending","pairingId":"p1","approvalTimeoutMs":60000}""")
                ws.send(pairedJson)
            }
            "\"offer\"" in text -> {
                ws.send("""{"type":"answer","sdp":"answer-sdp"}""")
                ws.send("""{"type":"ice-candidate","candidate":"candidate:9 1 udp 1 10.0.0.9 9 typ host","sdpMid":"0","sdpMLineIndex":0}""")
                afterAnswer(ws)
            }
        }
    }

    private fun startRun() = controller.startRun(target) { ice, listener ->
        assertEquals(2, ice.size)
        assertEquals("c", ice[1].credential)
        FakeLink(listener).also { links += it }
    }

    @Test
    fun `pairs, exchanges offer answer and ice, then tears down cleanly when capture stops`() {
        scriptHappyReceiver()
        startRun()

        await("answer applied") { links.firstOrNull()?.answers == listOf("answer-sdp") }
        await("remote ice applied") { links.first().remoteIce.size == 1 }
        assertEquals(Triple("candidate:9 1 udp 1 10.0.0.9 9 typ host", "0", 0), links.first().remoteIce[0])

        // What went over the wire.
        assertTrue(received.any { "\"join-pairing\"" in it && "\"code\":\"123456\"" in it && "\"deviceName\":\"Test Phone\"" in it })
        assertTrue(received.any { "\"offer\"" in it && "offer-sdp" in it })
        assertTrue(received.any { "\"ice-candidate\"" in it && "10.0.0.1" in it })

        assertEquals(StreamState.Negotiating, controller.state.value)
        links.first().listener.onConnectionState(LinkState.CONNECTED)
        await("streaming") { controller.state.value is StreamState.Streaming }

        // The capture layer tells us the track is about to be disposed.
        controller.onCaptureReleasing()
        assertTrue("link must be closed synchronously", links.first().closed)
        assertEquals(StreamState.Idle, controller.state.value)
        await("bye sent") { received.any { "\"bye\"" in it } }
    }

    @Test
    fun `wrong code ends in CODE_REJECTED and never creates a link`() {
        script { ws, text ->
            if ("\"join-pairing\"" in text) ws.send("""{"type":"error","code":"invalid-code","message":"invalid or expired code"}""")
        }
        controller.startRun(target) { _, _ -> error("no link expected") }
        await("failed") { controller.state.value is StreamState.Failed }
        assertEquals(StreamFailure.CODE_REJECTED, (controller.state.value as StreamState.Failed).reason)
        assertTrue(links.isEmpty())
    }

    @Test
    fun `rate limiting is reported with the retry delay`() {
        script { ws, text ->
            if ("\"join-pairing\"" in text) ws.send("""{"type":"error","code":"rate-limited","message":"x","retryAfterMs":287000}""")
        }
        controller.startRun(target) { _, _ -> error("no link expected") }
        await("failed") { controller.state.value is StreamState.Failed }
        val failed = controller.state.value as StreamState.Failed
        assertEquals(StreamFailure.RATE_LIMITED, failed.reason)
        assertEquals("287000", failed.detail)
    }

    @Test
    fun `receiver rejection and approval timeout are distinguished`() {
        script { ws, text ->
            if ("\"join-pairing\"" in text) {
                ws.send("""{"type":"pairing-pending","pairingId":"p1","approvalTimeoutMs":60000}""")
                ws.send("""{"type":"pairing-denied","pairingId":"p1","reason":"rejected"}""")
            }
        }
        controller.startRun(target) { _, _ -> error("no link expected") }
        await("denied") { controller.state.value == StreamState.Failed(StreamFailure.DENIED_BY_RECEIVER) }

        script { ws, text ->
            if ("\"join-pairing\"" in text) ws.send("""{"type":"pairing-denied","pairingId":"p1","reason":"timeout"}""")
        }
        controller.startRun(target) { _, _ -> error("no link expected") }
        await("timeout") { controller.state.value == StreamState.Failed(StreamFailure.APPROVAL_TIMEOUT) }
    }

    @Test
    fun `waiting for approval is surfaced`() {
        script { ws, text ->
            if ("\"join-pairing\"" in text) ws.send("""{"type":"pairing-pending","pairingId":"p1","approvalTimeoutMs":60000}""")
        }
        controller.startRun(target) { _, _ -> error("no link expected") }
        await("waiting") { controller.state.value == StreamState.WaitingForApproval }
        controller.onCaptureReleasing()
    }

    @Test
    fun `unreachable server fails fast`() {
        val dead = StreamTarget("ws://127.0.0.1:1/ws", "123456")
        controller.startRun(dead) { _, _ -> error("no link expected") }
        await("failed") { controller.state.value is StreamState.Failed }
        assertEquals(StreamFailure.SERVER_UNREACHABLE, (controller.state.value as StreamState.Failed).reason)
    }

    @Test
    fun `receiver ending the session stops the capture`() {
        repo.start(Intent())
        assertTrue(repo.state.value is CaptureState.Capturing)

        scriptHappyReceiver { ws -> ws.send("""{"type":"bye","reason":"peer-disconnected"}""") }
        startRun()

        await("ended") { controller.state.value == StreamState.Ended }
        assertEquals(CaptureState.Stopped(StopReason.REMOTE_ENDED), repo.state.value)
        await("link closed") { links.first().closed }
    }

    @Test
    fun `a link failure after pairing is reported as CONNECTION_FAILED and the capture keeps running`() {
        repo.start(Intent())
        scriptHappyReceiver()
        startRun()
        await("negotiating") { links.firstOrNull()?.answers?.isNotEmpty() == true }

        links.first().listener.onConnectionState(LinkState.FAILED)
        await("failed") { controller.state.value is StreamState.Failed }
        assertEquals(StreamFailure.CONNECTION_FAILED, (controller.state.value as StreamState.Failed).reason)
        await("link closed after the failure") { links.first().closed }
        assertTrue("capture is independent of the stream", repo.state.value is CaptureState.Capturing)
    }

    @Test
    fun `connect without a running capture is refused`() {
        assertFalse(controller.connect(target))
        assertEquals(StreamState.Idle, controller.state.value)
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
