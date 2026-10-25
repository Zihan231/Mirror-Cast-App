package app.mirror.receiver.discovery

/** A signaling server advertised on the LAN as `_mirrorcast._tcp` (see docs/PROTOCOL.md, mDNS section). */
data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val path: String,
) {
    val url: String
        get() = "${if (tls) "wss" else "ws"}://${hostForUrl(host)}:$port$path"

    val label: String get() = "$name  ($host:$port)"

    companion object {
        const val SERVICE_TYPE = "_mirrorcast._tcp"

        /** Builds a server from a resolved service, validating what a LAN neighbour could put in TXT records. */
        fun from(name: String, host: String, port: Int, txt: Map<String, String>): DiscoveredServer? {
            if (host.isBlank() || port !in 1..65535) return null
            val path = txt["path"]?.takeIf { it.startsWith("/") && it.none(Char::isWhitespace) && it.length <= 64 } ?: "/ws"
            return DiscoveredServer(
                name = name.trim().take(64).ifEmpty { host },
                host = host,
                port = port,
                tls = txt["tls"] == "1",
                path = path,
            )
        }

        private fun hostForUrl(host: String): String {
            val bare = host.substringBefore('%') // drop an IPv6 scope id
            return if (':' in bare) "[$bare]" else bare
        }
    }
}
