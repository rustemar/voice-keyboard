package com.tyraen.voicekeyboard.feature.ime

/** RAM-only, revocable consent for one isolated dictation. Never restore this after retry. */
class AutoSendPolicy {
    class Request internal constructor() {
        private var pending = true

        fun cancel() { pending = false }

        internal fun consume(): Boolean {
            if (!pending) return false
            pending = false // Consume before dispatch: a rejected/throwing action is never retried.
            return true
        }

        internal val isPending: Boolean get() = pending
    }

    private var current: Request? = null

    fun prepare(eligible: Boolean): Request? {
        invalidate()
        return if (eligible) Request().also { current = it } else null
    }

    fun isCurrent(request: Request?): Boolean = request != null && current === request && request.isPending

    fun consume(request: Request?): Boolean {
        if (!isCurrent(request)) return false
        current = null
        return request!!.consume()
    }

    /** Rejection and host exceptions leave the inserted text alone and cannot authorize retry. */
    fun dispatchOnce(request: Request?, dispatch: () -> Boolean): Boolean {
        if (!consume(request)) return false
        return try {
            dispatch()
        } catch (_: RuntimeException) {
            false
        }
    }

    fun invalidate() {
        current?.cancel()
        current = null
    }
}
