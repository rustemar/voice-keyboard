package com.tyraen.voicekeyboard.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/** Internet validation is useful for public APIs, but it does not describe a LAN server. */
internal class EndpointConnectivityPolicy(
    private val hasValidatedInternet: () -> Boolean,
    private val hasNetworkLink: () -> Boolean,
    private val resolve: suspend (String) -> List<InetAddress>
) {
    suspend fun canAccessEndpoint(endpoint: String): Boolean {
        if (hasValidatedInternet()) return true
        val host = ApiEndpoint.complete(endpoint, "").toHttpUrlOrNull()?.host?.trimEnd('.') ?: return false
        val literal = literalAddress(host)
        if (literal?.isLoopbackAddress == true || host == "localhost" || host.endsWith(".localhost")) return true
        if (!hasNetworkLink()) return false
        if (literal != null) return isLocalAddress(literal)

        // A dead upstream must still park common cloud providers immediately, without DNS waits.
        if (host in PUBLIC_API_HOSTS) return false
        // A NAS can use a single label, .local, or an ordinary split-DNS domain. Resolve it with
        // the same system resolver as HTTP rather than guessing from its suffix. Resolution is
        // bounded and off Main in ConnectivityMonitor; a public answer or failure stays parked.
        return resolve(host).any(::isLocalAddress)
    }

    companion object {
        private val PUBLIC_API_HOSTS = setOf(
            "api.openai.com", "api.groq.com", "api.anthropic.com", "api.mistral.ai",
            "openrouter.ai", "eu.openrouter.ai", "api.deepseek.com", "api.together.xyz"
        )

        private fun literalAddress(host: String): InetAddress? {
            // A colon in HttpUrl's canonical host denotes a numeric IPv6 address, not a DNS
            // name. Numeric parsing does not query DNS, including IPv4-mapped IPv6 literals.
            if (host.contains(':')) return runCatching { InetAddress.getByName(host) }.getOrNull()
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return null
            val octets = parts.map { it.toIntOrNull() ?: return null }
            if (octets.any { it !in 0..255 }) return null
            return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
        }

        internal fun isLocalAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isMulticastAddress) return false
            if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
            val bytes = address.address
            // RFC 4193 IPv6 unique-local addresses are not covered by Java's isSiteLocalAddress.
            if (bytes.size == 16) return (bytes[0].toInt() and 0xfe) == 0xfc
            // Carrier-grade/private overlay networks (including common self-hosted VPNs).
            return bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 &&
                (bytes[1].toInt() and 0xff) in 64..127
        }
    }
}
