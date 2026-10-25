package app.mirror.core.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class CodecOrderingTest {

    private data class Codec(val mime: String, val params: Map<String, String> = emptyMap(), val id: Int = 0)

    private fun order(vararg codecs: Codec) = CodecOrdering.prefer(codecs.toList(), { it.mime }, { it.params }).map { it.id }

    private val cb = mapOf("profile-level-id" to "42e01f", "packetization-mode" to "1")
    private val high = mapOf("profile-level-id" to "640c1f", "packetization-mode" to "1")

    @Test
    fun `h264 constrained baseline first, then other h264, then vp8, then the rest`() {
        val result = order(
            Codec("video/VP9", id = 1),
            Codec("video/VP8", id = 2),
            Codec("video/H264", high, id = 3),
            Codec("video/rtx", id = 4),
            Codec("video/H264", cb, id = 5),
            Codec("video/AV1", id = 6),
        )
        assertEquals(listOf(5, 3, 2, 1, 4, 6), result)
    }

    @Test
    fun `without h264 vp8 leads`() {
        assertEquals(listOf(2, 1), order(Codec("video/VP9", id = 1), Codec("video/VP8", id = 2)))
    }

    @Test
    fun `mime matching is case-insensitive and packetization-mode 0 ranks lowest among h264`() {
        val mode0 = mapOf("profile-level-id" to "42e01f", "packetization-mode" to "0")
        assertEquals(listOf(2, 1, 3), order(Codec("video/h264", mode0, id = 1), Codec("video/H264", cb, id = 2), Codec("video/vp8", id = 3)))
    }

    @Test
    fun `order is stable for equal ranks`() {
        assertEquals(listOf(1, 2, 3), order(Codec("video/rtx", id = 1), Codec("video/red", id = 2), Codec("video/ulpfec", id = 3)))
    }
}
