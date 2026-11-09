package com.example.libremaps.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks ORDER BY id DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bookmarks: List<BookmarkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmarks WHERE id < 0")
    suspend fun getPendingBookmarks(): List<BookmarkEntity>

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAll()

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
