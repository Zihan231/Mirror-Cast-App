package app.mirror.core.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureConfigTest {
    @Test
    fun `tall phone screen is scaled to a 1920 long edge`() {
        assertEquals(CaptureConfig(864, 1920, 30), CaptureConfig.forDisplay(1080, 2400))
    }

    @Test
    fun `landscape is symmetric`() {
        assertEquals(CaptureConfig(1920, 864, 30), CaptureConfig.forDisplay(2400, 1080))
    }

    @Test
    fun `small displays are never upscaled`() {
        assertEquals(CaptureConfig(800, 600, 30), CaptureConfig.forDisplay(800, 600))
    }

    @Test
    fun `odd sizes are made even`() {
        val c = CaptureConfig.forDisplay(1081, 1921, maxLongEdge = 4000)
        assertEquals(0, c.width % 2)
        assertEquals(0, c.height % 2)
    }

    @Test
    fun `rejects non-positive input`() {
        assertThrows(IllegalArgumentException::class.java) { CaptureConfig.forDisplay(0, 100) }
    }
}
