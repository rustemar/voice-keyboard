package com.tyraen.voicekeyboard.feature.audio

import com.tyraen.voicekeyboard.core.logging.CaptureDiagnostics

/** One recorder per process, shared by the keyboard, settings test and recognition service. */
class MicrophoneGate {
    private var owner: Lease? = null

    @JvmOverloads
    @Synchronized fun acquire(ownerLabel: String = "unspecified"): Lease? {
        if (owner != null) {
            CaptureDiagnostics.event("Gate", "acquire_refused", "requester=$ownerLabel holder=${owner?.ownerLabel}")
            return null
        }
        return Lease(this, ownerLabel).also {
            owner = it
            CaptureDiagnostics.event("Gate", "acquired", "owner=$ownerLabel")
        }
    }

    val isBusy: Boolean @Synchronized get() = owner != null
    val diagnosticOwner: String? @Synchronized get() = owner?.ownerLabel

    @Synchronized private fun release(lease: Lease) {
        if (owner === lease) {
            owner = null
            CaptureDiagnostics.event("Gate", "released", "owner=${lease.ownerLabel}")
        } else {
            CaptureDiagnostics.event("Gate", "release_ignored", "requester=${lease.ownerLabel} holder=${owner?.ownerLabel}")
        }
    }

    class Lease internal constructor(private val gate: MicrophoneGate, internal val ownerLabel: String) : AutoCloseable {
        override fun close() = gate.release(this)
    }

    companion object { val shared = MicrophoneGate() }
}
