package com.tyraen.voicekeyboard.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog

/**
 * Thin wrapper over [ConnectivityManager] that answers one question: "is there real internet right
 * now?" — and fires a callback when validated internet returns after being absent.
 *
 * We gate on [NetworkCapabilities.NET_CAPABILITY_VALIDATED], which means Android successfully
 * reached its connectivity probe, not merely that a Wi-Fi/cell link is associated. This is the
 * reliable equivalent of a ping: it avoids firing doomed uploads at an access point with no real
 * upstream (the exact 4-minute-hang situation seen in the logs). The authoritative signal is still
 * the HTTP result; this is only a cheap pre-flight and a wake-up trigger.
 */
class ConnectivityMonitor(context: Context) {

    companion object {
        private const val TAG = "Connectivity"
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var online: Boolean = runCatching { computeOnline() }.getOrDefault(false)

    private var onValidatedAvailable: (() -> Unit)? = null

    /** Best-effort snapshot of whether validated internet is available. Cheap; safe to call often. */
    fun isOnline(): Boolean = runCatching { computeOnline() }.getOrDefault(false).also { online = it }

    private fun computeOnline(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Register a process-lifetime network callback. Call EXACTLY ONCE, from Application.onCreate.
     * The callback runs on a binder/background thread, so [onValidatedAvailable] must be cheap and
     * thread-safe (we only ever launch a coroutine from it).
     */
    fun register(onValidatedAvailable: () -> Unit) {
        this.onValidatedAvailable = onValidatedAvailable
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val rose = validated && !online
                    online = validated
                    if (rose) {
                        DiagnosticLog.record(TAG, "Validated internet available — resuming retries")
                        this@ConnectivityMonitor.onValidatedAvailable?.invoke()
                    }
                }

                override fun onLost(network: Network) {
                    online = runCatching { computeOnline() }.getOrDefault(false)
                }
            })
        } catch (e: Exception) {
            DiagnosticLog.recordFailure(TAG, "registerNetworkCallback failed", e)
        }
    }
}
