package com.tyraen.voicekeyboard.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Validated internet remains the fast pre-flight for public APIs. A connected local network or
 * loopback can also reach the user's own provider without passing Android's internet probe.
 *
 * We gate on [NetworkCapabilities.NET_CAPABILITY_VALIDATED], which means Android successfully
 * reached its connectivity probe, not merely that a Wi-Fi/cell link is associated. This is the
 * useful signal for cloud uploads, not proof of endpoint reachability. HTTP remains authoritative;
 * this pre-flight and the network-change callback only decide when an attempt is worth making.
 */
class ConnectivityMonitor(context: Context) {

    companion object {
        private const val TAG = "Connectivity"
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var onNetworkChanged: (() -> Unit)? = null
    private val networkGeneration = AtomicLong(0)
    private data class Lookup(val addresses: List<InetAddress>, val expiresAt: Long, val generation: Long)
    private val lookups = ConcurrentHashMap<String, Lookup>()
    private val networkStates = HashMap<Network, Int>()
    private val networkLinks = HashMap<Network, String>()
    // DNS can block in the platform resolver. Bound both the caller's wait and the number of
    // outstanding resolver threads; a dead router must not freeze capture or accumulate workers.
    private val resolver = ThreadPoolExecutor(
        0, 2, 15, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "provider-dns").apply { isDaemon = true } }
    )
    private val endpointPolicy = EndpointConnectivityPolicy(
        ::isOnline, ::hasNetworkLink, ::resolveBounded
    )

    /** Permit an HTTP attempt on an appropriate link; this does not probe the server itself. */
    suspend fun canAccessEndpoint(endpoint: String): Boolean = endpointPolicy.canAccessEndpoint(endpoint)

    /** Best-effort snapshot of whether validated internet is available. Cheap; safe to call often. */
    fun isOnline(): Boolean = runCatching { computeOnline() }.getOrDefault(false)

    private fun computeOnline(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun hasNetworkLink(): Boolean = runCatching {
        cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network) != null &&
                cm.getLinkProperties(network)?.linkAddresses?.isNotEmpty() == true
        }
    }.getOrDefault(false)

    private suspend fun resolveBounded(host: String): List<InetAddress> {
        val generation = networkGeneration.get()
        lookups[host]?.takeIf {
            it.generation == generation && it.expiresAt > SystemClock.elapsedRealtime()
        }?.let { return it.addresses }
        val addresses = runInterruptible(Dispatchers.IO) {
            val future = try {
                resolver.submit<List<InetAddress>> { InetAddress.getAllByName(host).toList() }
            } catch (_: RejectedExecutionException) {
                return@runInterruptible emptyList()
            }
            try {
                future.get(1500, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                emptyList()
            } catch (_: ExecutionException) {
                emptyList()
            } finally {
                future.cancel(true)
            }
        }
        if (networkGeneration.get() != generation) return emptyList()
        if (lookups.size >= 32) lookups.clear()
        val ttl = if (addresses.isEmpty()) 5_000L else 30_000L
        lookups[host] = Lookup(addresses, SystemClock.elapsedRealtime() + ttl, generation)
        return addresses
    }

    private fun changed() {
        networkGeneration.incrementAndGet()
        lookups.clear()
        onNetworkChanged?.invoke()
    }

    /**
     * Register a process-lifetime network callback. Call EXACTLY ONCE, from Application.onCreate.
     * The callback runs on a binder/background thread, so [onNetworkChanged] must be cheap and
     * thread-safe (we only ever launch a coroutine from it).
     */
    fun register(onNetworkChanged: () -> Unit) {
        this.onNetworkChanged = onNetworkChanged
        val request = NetworkRequest.Builder()
            // Include local-only Wi-Fi/Ethernet and VPN links: they may reach a private provider
            // while neither INTERNET nor VALIDATED is available.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { changed() }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    var state = 0
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) state = state or 1
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) state = state or 2
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) state = state or 4
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) state = state or 8
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) state = state or 16
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) state = state or 32
                    val previous = synchronized(networkStates) { networkStates.put(network, state) }
                    if (previous != state) {
                        DiagnosticLog.record(TAG, "Network availability changed — checking parked providers")
                        changed()
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
                    // Store an immutable snapshot; LinkProperties has no public copy constructor.
                    val state = link.toString()
                    val previous = synchronized(networkLinks) { networkLinks.put(network, state) }
                    if (previous != state) changed()
                }

                override fun onLost(network: Network) {
                    synchronized(networkStates) { networkStates.remove(network) }
                    synchronized(networkLinks) { networkLinks.remove(network) }
                    changed()
                }
            })
        } catch (e: Exception) {
            DiagnosticLog.recordFailure(TAG, "registerNetworkCallback failed", e)
        }
    }
}
