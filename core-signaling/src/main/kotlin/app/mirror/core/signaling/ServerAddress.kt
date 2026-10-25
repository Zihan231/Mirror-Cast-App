package app.mirror.core.signaling

import java.net.URI
import java.net.URISyntaxException

/** Parsing for the dev connect screen. */
object ServerAddress {
    private const val DEFAULT_PORT = 8080
    private val CODE = Regex("^\\d{6}$")

    fun isValidCode(code: String): Boolean = CODE.matches(code)

    /**
     * Accepts `192.168.0.5:8080`, `ws://host:8080/ws`, `wss://cast.example.com`, or a pasted browser
     * URL such as `http://192.168.0.5:8080/test`, and returns a ws(s) URL ending in the signaling path.
     * Returns null when the input cannot be a server address.
     */
    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val hasScheme = "://" in text
        val uri = try {
            URI(if (hasScheme) text else "ws://$text")
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = when (uri.scheme?.lowercase()) {
            "ws", "http" -> "ws"
            "wss", "https" -> "wss"
            else -> return null
        }
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        val port = when {
            uri.port != -1 -> uri.port
            !hasScheme -> DEFAULT_PORT // bare host typed by hand: assume the server's default port
            else -> -1
        }
        val path = uri.path.orEmpty().let { if (it.isEmpty() || it == "/" || it == "/test") "/ws" else it }
        return buildString {
            append(scheme).append("://").append(host)
            if (port != -1) append(':').append(port)
            append(path)
        }
    }
}
