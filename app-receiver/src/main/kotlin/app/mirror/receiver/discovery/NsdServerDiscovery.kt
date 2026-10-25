package app.mirror.receiver.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.core.content.getSystemService
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * mDNS discovery through the platform NsdManager (no Google Play services, so it works on Fire TV).
 * Resolution is serialised: older Android versions fail concurrent resolves.
 */
@Singleton
class NsdServerDiscovery @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
) {
    private val nsd: NsdManager? = context.getSystemService()
    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private val lock = Any()
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        val manager = nsd ?: return
        val l = synchronized(lock) {
            if (listener != null) return
            discoveryListener().also { listener = it }
        }
        try {
            manager.discoverServices(DiscoveredServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "discoverServices failed", e)
            synchronized(lock) { listener = null }
        }
    }

    fun stop() {
        val manager = nsd ?: return
        val l = synchronized(lock) {
            pending.clear()
            resolving = false
            listener.also { listener = null }
        } ?: return
        try {
            manager.stopServiceDiscovery(l)
        } catch (_: IllegalArgumentException) {
            // already stopped
        }
        _servers.value = emptyList()
    }

    private fun discoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            // The type comes back with a trailing dot ("_mirrorcast._tcp.").
            if (!info.serviceType.trimEnd('.').startsWith(DiscoveredServer.SERVICE_TYPE)) return
            synchronized(lock) { pending.addLast(info) }
            resolveNext()
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            _servers.update { list -> list.filterNot { it.name == info.serviceName } }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "start discovery failed: $errorCode")
            synchronized(lock) { listener = null }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
    }

    @Suppress("DEPRECATION") // resolveService is the only option below API 34
    private fun resolveNext() {
        val manager = nsd ?: return
        val info = synchronized(lock) {
            if (resolving) return
            val next = pending.removeFirstOrNull() ?: return
            resolving = true
            next
        }
        try {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        toServer(resolved)?.let { server ->
                            _servers.update { list -> (list.filterNot { it.name == server.name } + server).sortedBy { it.name } }
                        }
                        finishResolve()
                    }

                    override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "resolve failed for ${failed.serviceName}: $errorCode")
                        finishResolve()
                    }
                },
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "resolveService rejected", e)
            finishResolve()
        }
    }

    private fun finishResolve() {
        synchronized(lock) { resolving = false }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun toServer(info: NsdServiceInfo): DiscoveredServer? {
        // Prefer IPv4: it is what the server's QR / LAN URL uses and avoids link-local scope ids.
        val address = info.host ?: return null
        val host = (address as? Inet4Address ?: address).hostAddress ?: return null
        val txt = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
        return DiscoveredServer.from(info.serviceName, host, info.port, txt)
    }

    private companion object {
        const val TAG = "NsdServerDiscovery"
    }
}
