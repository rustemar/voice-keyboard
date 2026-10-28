package com.tyraen.voicekeyboard.feature.ime

import android.content.Context
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Finished text stays in private app storage until the user consumes or deletes it. Each capture
 * has its own atomic file, so later background dictations cannot overwrite earlier results or the
 * system clipboard. The queue must successfully [save] before deleting the source recording.
 *
 * All disk and mutable-state operations are serialized by [mutex] on [dispatcher]. Failed writes
 * or deletions throw without publishing an in-memory success. There is no age/count eviction.
 */
class PendingTranscriptStore internal constructor(
    private val dir: File,
    private val dispatcher: CoroutineDispatcher
) {
    constructor(context: Context) : this(File(context.filesDir, DIR_NAME), Dispatchers.IO)

    companion object {
        private const val DIR_NAME = "pending_transcripts"
        private const val TAG = "PendingTranscripts"
    }

    private val mutex = Mutex()
    private val items = LinkedHashMap<String, PendingTranscript>()
    private val acknowledgedWork = HashSet<String>()
    private var loaded = false
    private val published = MutableStateFlow<List<PendingTranscript>>(emptyList())

    /** Oldest first, including every saved result across keyboard/process restarts. */
    val transcripts: StateFlow<List<PendingTranscript>> = published

    suspend fun ensureLoaded() = withContext(dispatcher) {
        mutex.withLock { loadIfNeeded() }
    }

    /** Whether this capture still has visible text; consumed completion markers are excluded. */
    suspend fun contains(workId: String): Boolean = withContext(dispatcher) {
        mutex.withLock {
            loadIfNeeded()
            workId in items
        }
    }

    /**
     * Completion outlives consuming the text until its original audio is cleaned up. A crash
     * between saving text and deleting parked audio must not transcribe a consumed result again.
     */
    suspend fun isCompleted(workId: String): Boolean = withContext(dispatcher) {
        mutex.withLock {
            loadIfNeeded()
            if (workId in items) return@withLock true
            // A marker write may have failed after the JSON was committed in this same process.
            // Repair it before exposing consumable text; a repeated failure keeps audio retryable.
            if (fileFor(workId).exists()) {
                restoreUnpublished(workId)
                return@withLock true
            }
            hasCompletionMarker(workId)
        }
    }

    /** Call only after the queue has removed this capture's original audio and parked sidecar. */
    suspend fun acknowledgeAudioCleanup(workId: String) = withContext(dispatcher) {
        mutex.withLock {
            loadIfNeeded()
            val item = items[workId]
            if (item != null && workId !in acknowledgedWork) {
                // Persist ACK in the remaining text before deleting its completion marker. Without
                // it a restart could mistake a normal ACK for a crash and recreate that marker.
                writeAtomically(fileFor(workId), serialize(item, acknowledged = true))
                acknowledgedWork.add(workId)
            }
            deleteCompletionMarker(workId)
        }
    }

    /**
     * Persist one completed capture. Re-delivery of the same identity keeps the first saved text;
     * it neither adds a duplicate nor silently replaces a result the user has not consumed yet.
     * If text was consumed before audio cleanup, its marker prevents republishing it; the returned
     * candidate is then only a receipt and is not added to [transcripts].
     */
    suspend fun save(
        workId: String,
        text: String,
        addTrailingSpace: Boolean,
        createdAt: Long
    ): PendingTranscript = withContext(dispatcher) {
        require(workId.isNotBlank()) { "Missing dictation identity" }
        require(text.isNotBlank()) { "Empty dictation" }
        mutex.withLock {
            loadIfNeeded()
            items[workId]?.let {
                if (workId !in acknowledgedWork) ensureCompletionMarker(workId)
                return@withLock it
            }
            ensureDirectory()
            val result = PendingTranscript(workId, text, addTrailingSpace, createdAt)
            val destination = fileFor(workId)
            // Retain/repair a JSON committed before a failed marker write. A corrupt file throws
            // instead of being overwritten, even if its old completion marker still exists.
            if (destination.exists()) return@withLock restoreUnpublished(workId)
            if (hasCompletionMarker(workId)) return@withLock result
            writeAtomically(destination, serialize(result, acknowledged = false))
            ensureCompletionMarker(workId)
            items[workId] = result
            publish()
            result
        }
    }

    /** Delete after successful insertion, or after an explicit user request. */
    suspend fun remove(id: String): Boolean = withContext(dispatcher) {
        mutex.withLock {
            loadIfNeeded()
            if (id !in items) return@withLock false
            if (id in acknowledgedWork) deleteCompletionMarker(id)
            else ensureCompletionMarker(id)
            val file = fileFor(id)
            if (file.exists() && !file.delete()) throw IOException("Could not delete the saved dictation")
            items.remove(id)
            acknowledgedWork.remove(id)
            publish()
            true
        }
    }

    private fun loadIfNeeded() {
        if (loaded) return
        ensureDirectory()
        val files = dir.listFiles() ?: throw IOException("Could not read saved dictations")
        val restored = LinkedHashMap<String, PendingTranscript>()
        val restoredAcknowledged = HashSet<String>()
        for (file in files) {
            if (!file.isFile || !file.name.endsWith(".json")) continue
            val disk = try {
                readDiskItem(file)
            } catch (_: Exception) {
                // Keep the original file. Atomic writes should prevent truncation, but storage
                // damage must not destroy the only remaining copy or hide other valid results.
                DiagnosticLog.recordFailure(TAG, "Could not read a saved dictation; file retained")
                continue
            }
            // Do not publish a result that could be consumed before its durable completion marker
            // exists. In particular, let marker-write errors propagate instead of hiding success.
            if (disk.second) restoredAcknowledged.add(disk.first.id)
            else ensureCompletionMarker(disk.first.id)
            restored[disk.first.id] = disk.first
        }
        items.putAll(restored)
        acknowledgedWork.addAll(restoredAcknowledged)
        loaded = true
        publish()
    }

    private fun restoreUnpublished(id: String): PendingTranscript {
        val disk = try {
            readDiskItem(fileFor(id))
        } catch (error: Exception) {
            throw IOException("An unreadable saved dictation already exists", error)
        }
        if (disk.second) acknowledgedWork.add(id)
        else ensureCompletionMarker(id)
        items[id] = disk.first
        publish()
        return disk.first
    }

    private fun readDiskItem(file: File): Pair<PendingTranscript, Boolean> {
        val json = file.readText()
        val item = PendingTranscript.fromJson(json)
        if (file.name != fileFor(item.id).name) throw IOException("Saved dictation identity mismatch")
        return item to JSONObject(json).optBoolean("audioCleanupAcknowledged", false)
    }

    private fun serialize(item: PendingTranscript, acknowledged: Boolean): String =
        JSONObject(item.toJson()).put("audioCleanupAcknowledged", acknowledged).toString()

    private fun markerFor(id: String): File = File(dir, "${fileFor(id).nameWithoutExtension}.done")

    private fun hasCompletionMarker(id: String): Boolean {
        val marker = markerFor(id)
        if (!marker.exists()) return false
        if (!marker.isFile || marker.readText() != id) throw IOException("Could not read dictation completion marker")
        return true
    }

    private fun ensureCompletionMarker(id: String) {
        if (!hasCompletionMarker(id)) writeAtomically(markerFor(id), id)
    }

    private fun deleteCompletionMarker(id: String) {
        if (hasCompletionMarker(id) && !markerFor(id).delete()) {
            throw IOException("Could not acknowledge dictation audio cleanup")
        }
    }

    private fun ensureDirectory() {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create saved dictations directory")
    }

    private fun writeAtomically(destination: File, text: String) {
        val temporary = File.createTempFile("pending-", ".tmp", dir)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // Both files are in one app-private directory. A failed rename is not replaced with a
            // non-atomic overwrite, so the caller can retain/retry its original recording.
            if (!temporary.renameTo(destination)) throw IOException("Could not save the finished dictation")
        } finally {
            temporary.delete()
        }
    }

    /** Arbitrary capture identities cannot escape the store directory. */
    private fun fileFor(id: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(dir, "$hash.json")
    }

    private fun publish() {
        published.value = items.values.sortedWith(compareBy<PendingTranscript> { it.createdAt }.thenBy { it.id })
    }
}
