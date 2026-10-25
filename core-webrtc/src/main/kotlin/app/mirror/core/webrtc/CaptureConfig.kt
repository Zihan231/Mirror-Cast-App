package app.mirror.core.webrtc

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CaptureConfig(val width: Int, val height: Int, val fps: Int) {
    companion object {
        /**
         * Scales the display size down (never up) so the long edge is at most [maxLongEdge],
         * keeping the aspect ratio. Dimensions are forced even; encoders reject odd sizes.
         */
        fun forDisplay(displayWidth: Int, displayHeight: Int, maxLongEdge: Int = 1920, fps: Int = 30): CaptureConfig {
            require(displayWidth > 0 && displayHeight > 0) { "display size must be positive" }
            require(maxLongEdge >= 2 && fps > 0)
            val scale = min(1.0, maxLongEdge.toDouble() / max(displayWidth, displayHeight))
            return CaptureConfig(even(displayWidth * scale), even(displayHeight * scale), fps)
        }

        private fun even(v: Double): Int = (v.roundToInt() and 1.inv()).coerceAtLeast(2)
    }
}
