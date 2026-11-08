package com.example.libremaps.core.model

data class BookmarkRequest(
    val type: String,
    val description: String?,
    val latitude: Double,
    val longitude: Double
)

data class Bookmark(
    val id: Long,
    val type: String,
    val description: String?,
    val latitude: Double,
    val longitude: Double,
    val confirmations: Int = 0,
    val createdAtMs: Long = 0
)

data class BookmarkReportRequest(val reason: String?)
