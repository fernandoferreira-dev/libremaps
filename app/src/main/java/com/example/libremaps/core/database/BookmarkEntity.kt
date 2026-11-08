package com.example.libremaps.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.libremaps.core.model.Bookmark

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val id: Long,
    val type: String,
    val description: String?,
    val latitude: Double,
    val longitude: Double,
    val confirmations: Int = 0,
    val createdAtMs: Long = 0
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(id, type, description, latitude, longitude, confirmations, createdAtMs)
fun BookmarkEntity.toModel(): Bookmark = Bookmark(id, type, description, latitude, longitude, confirmations, createdAtMs)
