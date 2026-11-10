package com.example.libremaps.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.libremaps.core.model.Bookmark

 // Entidade Room para armazenamento local de marcações/ocorrências.
 // Suporta sincronização diferida
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val id: Long,
    val type: String,
    val description: String?,
    val latitude: Double,
    val longitude: Double,
    val confirmations: Int = 0,
    val createdAtMs: Long = 0,
    val syncState: String = SYNC_STATE_SYNCED
) {
    companion object {
        const val SYNC_STATE_SYNCED = "SYNCED"
        const val SYNC_STATE_PENDING_INSERT = "PENDING_INSERT"
        const val SYNC_STATE_PENDING_DELETE = "PENDING_DELETE"
    }
}

fun Bookmark.toEntity(syncState: String = BookmarkEntity.SYNC_STATE_SYNCED): BookmarkEntity =
    BookmarkEntity(id, type, description, latitude, longitude, confirmations, createdAtMs, syncState)

fun BookmarkEntity.toModel(): Bookmark =
    Bookmark(id, type, description, latitude, longitude, confirmations, createdAtMs)
