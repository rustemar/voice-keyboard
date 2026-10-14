package com.tyraen.voicekeyboard.feature.postprocessing

/**
 * Bounded, process-local reference text. Confined to Main by ProcessingQueue; never serialized.
 * A recording keeps its generation from capture start, so work that finishes after a reset cannot
 * read or repopulate the next application's history. The clock must be monotonic.
 */
class RecentDictationContext(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        const val MAX_MESSAGES = 5
        const val MAX_MESSAGE_CHARS = 1_000
        const val IDLE_TIMEOUT_MS = 30 * 60_000L
    }

    private var appPackage: String? = null
    private var generation = 0L
    private var lastActivityMs = nowMs()
    private val messages = ArrayDeque<String>()

    /** Null means the editor forbids learning, is a password, or has no known application. */
    fun setScope(packageName: String?) {
        val next = packageName?.takeIf { it.isNotBlank() }
        if (appPackage == next) return
        appPackage = next
        clear()
    }

    fun clear() {
        messages.clear()
        generation++
        lastActivityMs = nowMs()
    }

    fun captureToken(): Long? {
        expire()
        return if (appPackage != null) generation else null
    }

    fun snapshot(token: Long?): List<String> {
        expire()
        return if (isCurrent(token)) messages.toList() else emptyList()
    }

    fun remember(token: Long?, text: String) {
        expire()
        if (!isCurrent(token)) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // Avoid cutting an emoji's UTF-16 surrogate pair at the size limit.
        val bounded = trimmed.take(MAX_MESSAGE_CHARS).let {
            if (it.last().isHighSurrogate()) it.dropLast(1) else it
        }
        messages.addLast(bounded)
        while (messages.size > MAX_MESSAGES) messages.removeFirst()
        lastActivityMs = nowMs()
    }

    private fun isCurrent(token: Long?): Boolean =
        appPackage != null && token != null && token == generation

    private fun expire() {
        if (nowMs() - lastActivityMs >= IDLE_TIMEOUT_MS) clear()
    }
}
