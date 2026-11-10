package com.example.libremaps.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

 // DAO para acesso e manipulação de marcações na base de dados local Room.
@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE syncState != 'PENDING_DELETE' ORDER BY id DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bookmarks: List<BookmarkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmarks WHERE syncState = 'PENDING_INSERT' OR id < 0")
    suspend fun getPendingInserts(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE syncState = 'PENDING_DELETE'")
    suspend fun getPendingDeletes(): List<BookmarkEntity>

    @Query("UPDATE bookmarks SET syncState = :syncState WHERE id = :id")
    suspend fun updateSyncState(id: Long, syncState: String)

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAll()

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
