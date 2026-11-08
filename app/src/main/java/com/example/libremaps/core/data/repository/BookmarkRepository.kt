package com.example.libremaps.core.data.repository

import com.example.libremaps.core.database.BookmarkDao
import com.example.libremaps.core.database.toEntity
import com.example.libremaps.core.database.toModel
import com.example.libremaps.core.model.AuthResponse
import com.example.libremaps.core.model.Bookmark
import com.example.libremaps.core.model.BookmarkReportRequest
import com.example.libremaps.core.model.BookmarkRequest
import com.example.libremaps.core.network.ApiClient
import com.example.libremaps.core.network.AuthApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.Response

class BookmarkRepository(
    private val api: AuthApi = ApiClient.api,
    private val dao: BookmarkDao? = null
) {
    fun getLocalBookmarks(): Flow<List<Bookmark>>? {
        return dao?.getAllBookmarks()?.map { entities -> entities.map { it.toModel() } }
    }

    suspend fun getRemoteBookmarks(): Response<List<Bookmark>> = api.getBookmarks()

    suspend fun saveLocalBookmarks(bookmarks: List<Bookmark>) {
        dao?.insertAll(bookmarks.map { it.toEntity() })
    }

    suspend fun saveLocalBookmark(bookmark: Bookmark) {
        dao?.insert(bookmark.toEntity())
    }

    suspend fun clearLocalBookmarks() {
        dao?.deleteAll()
    }

    suspend fun deleteLocalBookmark(id: Long) {
        dao?.deleteById(id)
    }

    suspend fun createBookmark(token: String, req: BookmarkRequest): Response<Bookmark> =
        api.createBookmark(token, req)

    suspend fun deleteBookmark(token: String, id: Long): Response<AuthResponse> =
        api.deleteBookmark(token, id)

    suspend fun reportBookmark(token: String, id: Long, req: BookmarkReportRequest): Response<AuthResponse> =
        api.reportBookmark(token, id, req)

    suspend fun confirmBookmark(token: String, id: Long): Response<AuthResponse> =
        api.confirmBookmark(token, id)
}
