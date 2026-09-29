package com.tyraen.voicekeyboard.core.network

/**
 * Makes a pasted API address usable as-is.
 *
 * Providers document their address as a base URL ("https://openrouter.ai/api/v1") while the app
 * needs the full request path ("…/v1/chat/completions"). Users paste what the docs show, the
 * request lands on the base URL, and the provider answers 404 — see issue #4. This closes that
 * gap without guessing too much:
 *
 * - whitespace and trailing slashes are dropped;
 * - a missing scheme becomes `https://` (our own setup hint spells the Mistral address without one);
 * - when the path ends in `/v1`, the provider's default request path is appended.
 *
 * Anything else — a custom proxy path, a query string, a bare host — passes through untouched,
 * because there is no safe way to complete it. The result is idempotent, so it can be applied
 * both when saving and when resolving.
 */
object ApiEndpoint {

    fun complete(raw: String, defaultPath: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.isEmpty()) return ""
        if (!url.contains("://")) url = "https://$url"
        if (url.contains('?') || url.contains('#')) return url
        return if (pathOf(url).endsWith("/v1")) url + defaultPath else url
    }

    /**
     * True for a plain `http://` address: audio, text and the API key would travel unencrypted.
     * Allowed for a self-hosted server on the user's own network; the settings screens say so.
     * A server on the phone itself (localhost, 127.x) is left out: nothing leaves the device.
     */
    fun isUnencrypted(url: String): Boolean {
        if (!url.trim().startsWith("http://", ignoreCase = true)) return false
        val host = hostOf(url)
        return host != "localhost" && !host.startsWith("127.")
    }

    /** Host part of the URL, lowercased, without credentials or port; "" when there is none. */
    fun hostOf(url: String): String {
        val afterScheme = url.trim().substringAfter("://", "")
        if (afterScheme.isEmpty()) return ""
        return afterScheme
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':')
            .lowercase()
    }

    /** Path part of the URL ("/api/v1"), without query or fragment; "" when there is none. */
    fun pathOf(url: String): String {
        val afterScheme = url.trim().substringAfter("://", "")
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return ""
        return afterScheme.substring(slash).substringBefore('?').substringBefore('#')
    }
}
