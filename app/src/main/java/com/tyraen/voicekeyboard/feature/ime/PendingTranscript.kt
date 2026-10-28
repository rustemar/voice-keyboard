package com.tyraen.voicekeyboard.feature.ime

import org.json.JSONObject

/** A finished dictation waiting for the user to insert, copy or delete it. */
data class PendingTranscript(
    val id: String,
    val text: String,
    val addTrailingSpace: Boolean,
    val createdAt: Long
) {
    internal fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("addTrailingSpace", addTrailingSpace)
        put("createdAt", createdAt)
    }.toString()

    companion object {
        internal fun fromJson(json: String): PendingTranscript {
            val value = JSONObject(json)
            return PendingTranscript(
                id = value.getString("id"),
                text = value.getString("text"),
                addTrailingSpace = value.getBoolean("addTrailingSpace"),
                createdAt = value.getLong("createdAt")
            ).also {
                require(it.id.isNotBlank()) { "Missing dictation identity" }
                require(it.text.isNotBlank()) { "Empty dictation" }
            }
        }
    }
}
