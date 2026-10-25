package app.mirror.core.webrtc

/**
 * Orders sender codecs: H.264 first (the hardware encoder is only offered by WebRTC when the
 * device has one), VP8 as the fallback, everything else (VP9, AV1, rtx, red, ulpfec, ...) after.
 * Within H.264, constrained baseline with packetization-mode=1 is preferred because every
 * browser and TV decoder supports it. The sort is stable, so the original order breaks ties.
 */
object CodecOrdering {

    fun <T> prefer(codecs: List<T>, mimeType: (T) -> String, parameters: (T) -> Map<String, String>): List<T> =
        codecs.sortedBy { rank(mimeType(it), parameters(it)) }

    private fun rank(mime: String, params: Map<String, String>): Int {
        val m = mime.lowercase()
        return when {
            m == "video/h264" -> {
                val profile = params["profile-level-id"]?.lowercase().orEmpty()
                val constrainedBaseline = profile.startsWith("42e0") || profile.startsWith("4200")
                val mode1 = params["packetization-mode"] == "1"
                when {
                    constrainedBaseline && mode1 -> 0
                    mode1 -> 1
                    else -> 2
                }
            }
            m == "video/vp8" -> 3
            else -> 4
        }
    }
}
