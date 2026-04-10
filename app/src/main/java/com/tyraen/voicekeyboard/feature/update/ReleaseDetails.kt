package com.tyraen.voicekeyboard.feature.update

data class ReleaseDetails(
    val version: String,
    val pageUrl: String,
    val directDownloadUrl: String?,
    val changeNotes: String
)
