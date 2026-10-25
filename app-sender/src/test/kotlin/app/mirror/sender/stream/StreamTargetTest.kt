package app.mirror.sender.stream

import org.junit.Assert.assertFalse
import org.junit.Test

class StreamTargetTest {
    @Test
    fun `the target never prints its code`() {
        assertFalse(StreamTarget("ws://h/ws", "123456").toString().contains("123456"))
    }
}
