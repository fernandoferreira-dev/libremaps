package com.example.libremaps.core.data.repository

import com.example.libremaps.core.database.BookmarkDao
import com.example.libremaps.core.database.BookmarkEntity
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


 //Repositório de marcações/ocorrências.
 //Implementa sincronização bidirecional entre o armazenamento local (Room) e o servidor remoto (Retrofit).
class BookmarkRepository(
    private val api: AuthApi = ApiClient.api,
    private val dao: BookmarkDao? = null
) {
    fun getLocalBookmarks(): Flow<List<Bookmark>>? {
        return dao?.getAllBookmarks()?.map { entities -> entities.map { it.toModel() } }
    }

    suspend fun saveLocalBookmark(bookmark: Bookmark, syncState: String = BookmarkEntity.SYNC_STATE_SYNCED) {
        dao?.insert(bookmark.toEntity(syncState))
    }

    suspend fun deleteLocalBookmark(id: Long) {
        dao?.deleteById(id)
    }

    suspend fun markForPendingDelete(id: Long) {
        dao?.updateSyncState(id, BookmarkEntity.SYNC_STATE_PENDING_DELETE)
    }

    suspend fun createBookmark(token: String, req: BookmarkRequest): Response<Bookmark> =
        api.createBookmark(authHeader(token), req)

    suspend fun deleteBookmark(token: String, id: Long): Response<AuthResponse> =
        api.deleteBookmark(authHeader(token), id)

    suspend fun reportBookmark(token: String, id: Long, req: BookmarkReportRequest): Response<AuthResponse> =
        api.reportBookmark(authHeader(token), id, req)

    suspend fun confirmBookmark(token: String, id: Long): Response<AuthResponse> =
        api.confirmBookmark(authHeader(token), id)

    private fun authHeader(token: String): String =
        if (token.startsWith("Bearer ")) token else "Bearer $token"

     //Sincroniza marcações pendentes (inserções e remoções offline) com o servidor remoto, e descarrega a lista atualizada de ocorrências.
     // Se [isOnline] for falso, a sincronização fica em standby para economizar bateria e rede.
    suspend fun syncBookmarks(token: String, isOnline: Boolean) {
        if (!isOnline || dao == null) return

        // Processar inserções pendentes criadas offline
        val pendingInserts = dao.getPendingInserts()
        for (pending in pendingInserts) {
            try {
                val req = BookmarkRequest(
                    type = pending.type,
                    description = pending.description,
                    latitude = pending.latitude,
                    longitude = pending.longitude
                )
                val response = api.createBookmark(authHeader(token), req)
                if (response.isSuccessful) {
                    val remoteBookmark = response.body()
                    if (remoteBookmark != null) {
                        dao.deleteById(pending.id)
                        dao.insert(
                            remoteBookmark.copy(
                                createdAtMs = if (remoteBookmark.createdAtMs > 0) remoteBookmark.createdAtMs else System.currentTimeMillis()
                            ).toEntity(BookmarkEntity.SYNC_STATE_SYNCED)
                        )
                    }
                }
            } catch (_: Exception) {
                // Em caso de falha de rede temporária, mantém para a próxima tentativa
            }
        }

        // Processar eliminações pendentes efetuadas offline
        val pendingDeletes = dao.getPendingDeletes()
        for (pending in pendingDeletes) {
            try {
                val response = api.deleteBookmark(authHeader(token), pending.id)
                if (response.isSuccessful || response.code() == 404) {
                    dao.deleteById(pending.id)
                }
            } catch (_: Exception) {
                // Em caso de falha de rede temporária, mantém para a próxima tentativa
            }
        }

        // Obter marcações remotas atualizadas do servidor
        try {
            val response = api.getBookmarks()
            if (response.isSuccessful) {
                val remoteBookmarks = response.body().orEmpty()
                val receivedAt = System.currentTimeMillis()
                for (remote in remoteBookmarks) {
                    dao.insert(
                        remote.copy(
                            createdAtMs = if (remote.createdAtMs > 0) remote.createdAtMs else receivedAt
                        ).toEntity(BookmarkEntity.SYNC_STATE_SYNCED)
                    )
                }
            }
        } catch (_: Exception) {
            // Em caso de erro, os dados locais mantêm-se operacionais
        }
    }
}
