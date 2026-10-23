package com.tyraen.voicekeyboard.feature.audio

/** One recorder per process, shared by the keyboard, settings test and recognition service. */
class MicrophoneGate {
    private var owner: Lease? = null

    @Synchronized fun acquire(): Lease? {
        if (owner != null) return null
        return Lease(this).also { owner = it }
    }

    val isBusy: Boolean @Synchronized get() = owner != null

    @Synchronized private fun release(lease: Lease) {
        if (owner === lease) owner = null
    }

    class Lease internal constructor(private val gate: MicrophoneGate) : AutoCloseable {
        override fun close() = gate.release(this)
    }

    companion object { val shared = MicrophoneGate() }
}
