package com.tyraen.voicekeyboard.feature.ime

import android.content.Context
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Durable, process-wide store of recordings that failed to transcribe. The audio lives in
 * `filesDir/parked/<id>.<ext>` (NOT externalCacheDir, which the OS may evict) with a JSON sidecar
 * `<id>.json` holding the non-secret config snapshot ([ParkedRecording]).
 *
 * Concurrency model: ALL state ([items], [inFlight], [loaded]) is confined to a single dedicated
 * thread ([dispatcher]). Every public method is a `suspend` that hops onto it, so callers from the
 * IME main thread, the connectivity callback thread, and IO can interleave safely without locks.
 * Only [count] (a thread-safe StateFlow) is read from other threads.
 */
class ParkedRecordingStore(context: Context) {

    companion object {
        private const val TAG = "ParkedStore"
        private const val DIR_NAME = "parked"
        /** Safety valve so a permanently-failing endpoint can't fill storage without bound. */
        private const val MAX_ITEMS = 50
        private val seq = AtomicLong(0)
    }

    private val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "parked-store").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // --- confined to [dispatcher] ---
    private val items = ArrayList<ParkedRecording>()
    private val inFlight = HashSet<String>()
    private var loaded = false
    // ---

    private val _count = MutableStateFlow(0)
    /** Total parked recordings (any state). Drives the resend badge; survives view rebuilds. */
    val count: StateFlow<Int> = _count

    /** Scan disk and load any parked recordings. Idempotent; safe to call from app startup. */
    suspend fun ensureLoaded() = withContext(dispatcher) { loadIfNeeded() }

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        val files = dir.listFiles() ?: emptyArray()
        for (sidecar in files) {
            if (!sidecar.name.endsWith(".json")) continue
            val rec = runCatching { ParkedRecording.fromJson(sidecar.readText()) }.getOrNull()
            if (rec == null) {
                sidecar.delete() // unparseable sidecar — drop it
                continue
            }
            if (!File(rec.audioPath).exists()) {
                sidecar.delete() // audio gone — nothing left to retry
                continue
            }
            // A crash could have left an item mid-attempt; reset transient ones to WAITING so the
            // retry loop picks them up again. Permanent (NEEDS_ATTENTION) ones stay as-is.
            items.add(
                if (rec.state == ParkedRecording.State.NEEDS_ATTENTION) rec
                else rec.copy(state = ParkedRecording.State.WAITING_NETWORK)
            )
        }
        // Note: orphan audio with no sidecar is intentionally left untouched — we can't reconstruct
        // its config, but deleting it is exactly the data loss we are preventing.
        publish()
        DiagnosticLog.record(TAG, "Loaded ${items.size} parked recording(s)")
    }

    /** Number of items that can be claimed for a retry right now (WAITING and not in flight). */
    suspend fun waitingCount(): Int = withContext(dispatcher) {
        loadIfNeeded()
        items.count { it.state == ParkedRecording.State.WAITING_NETWORK && it.id !in inFlight }
    }

    /** Atomically take all retryable items and mark them in-flight so no other drain double-claims. */
    suspend fun claimWaiting(): List<ParkedRecording> = withContext(dispatcher) {
        loadIfNeeded()
        val claimable = items.filter {
            it.state == ParkedRecording.State.WAITING_NETWORK && it.id !in inFlight
        }
        claimable.forEach { inFlight.add(it.id) }
        claimable
    }

    /** Park a brand-new failed recording: move its audio into the store and write the sidecar. */
    suspend fun park(
        item: ProcessingQueue.QueueItem,
        source: File,
        state: ParkedRecording.State,
        attemptCount: Int
    ): ParkedRecording? = withContext(dispatcher) {
        loadIfNeeded()
        if (!source.exists()) {
            DiagnosticLog.record(TAG, "park: source missing ${source.name}")
            return@withContext null
        }
        val ext = source.extension.ifBlank { "ogg" }
        val id = "parked_${System.currentTimeMillis()}_${seq.getAndIncrement()}"
        val dest = File(dir, "$id.$ext")
        // Write the sidecar BEFORE moving the audio. That way we can never end up with audio that
        // has no sidecar (which loadIfNeeded can't reconstruct and would silently leak). If the
        // move then fails, the sidecar points at missing audio and is dropped on the next load.
        val rec = ParkedRecording.fromQueueItem(item, id, dest.absolutePath, state, attemptCount)
        if (!writeSidecar(rec)) {
            DiagnosticLog.record(TAG, "park: failed to write sidecar for $id")
            return@withContext null
        }
        val moved = source.renameTo(dest) || runCatching {
            source.copyTo(dest, overwrite = true); source.delete(); true
        }.getOrDefault(false)
        if (!moved || !dest.exists()) {
            DiagnosticLog.record(TAG, "park: failed to move audio for $id")
            File(dir, sidecarName(id)).delete()
            return@withContext null
        }
        items.add(rec)
        enforceCap()
        publish()
        DiagnosticLog.record(TAG, "Parked $id state=$state total=${items.size}")
        rec
    }

    /** Record the outcome of a finished attempt on an already-parked item; releases the in-flight claim. */
    suspend fun update(id: String, state: ParkedRecording.State) = withContext(dispatcher) {
        loadIfNeeded()
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val updated = items[idx].copy(state = state, attemptCount = items[idx].attemptCount + 1)
            items[idx] = updated
            writeSidecar(updated)
        }
        inFlight.remove(id)
        publish()
    }

    /** A recording was transcribed successfully: delete its sidecar then its audio, drop it. */
    suspend fun markDone(id: String) = withContext(dispatcher) {
        loadIfNeeded()
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val rec = items.removeAt(idx)
            // Delete audio first: if the process is killed between the two deletes, a sidecar
            // pointing at missing audio is cleaned up on the next load, whereas an orphan audio
            // with no sidecar would leak forever.
            File(rec.audioPath).delete()
            File(dir, sidecarName(id)).delete()
        }
        inFlight.remove(id)
        publish()
        DiagnosticLog.record(TAG, "Done $id total=${items.size}")
    }

    /** Manual resend: give every NEEDS_ATTENTION item another chance (user likely fixed the key). */
    suspend fun promoteNeedsAttentionToWaiting() = withContext(dispatcher) {
        loadIfNeeded()
        var changed = false
        for (i in items.indices) {
            if (items[i].state == ParkedRecording.State.NEEDS_ATTENTION) {
                items[i] = items[i].copy(state = ParkedRecording.State.WAITING_NETWORK)
                writeSidecar(items[i])
                changed = true
            }
        }
        if (changed) publish()
    }

    private fun enforceCap() {
        while (items.size > MAX_ITEMS) {
            val rec = items.removeAt(0) // drop oldest; keep the most recent dictations
            File(dir, sidecarName(rec.id)).delete()
            File(rec.audioPath).delete()
            inFlight.remove(rec.id)
            DiagnosticLog.record(TAG, "Cap exceeded — dropped oldest ${rec.id}")
        }
    }

    private fun sidecarName(id: String) = "$id.json"

    private fun writeSidecar(rec: ParkedRecording): Boolean = try {
        val tmp = File(dir, "${rec.id}.json.tmp")
        tmp.writeText(rec.toJson())
        val finalFile = File(dir, sidecarName(rec.id))
        if (!tmp.renameTo(finalFile)) {
            finalFile.writeText(rec.toJson())
            tmp.delete()
        }
        true
    } catch (e: Exception) {
        DiagnosticLog.recordFailure(TAG, "writeSidecar failed for ${rec.id}", e)
        false
    }

    private fun publish() {
        _count.value = items.size
    }
}
