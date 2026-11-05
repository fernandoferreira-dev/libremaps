package com.example.libremaps

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.POST
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class AuthRequest(val email: String, val password: String)
data class AuthResponse(
    val success: Boolean,
    val message: String,
    val token: String? = null,
    val userId: Long? = null
)

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
    val confirmations: Int = 0
)

data class BookmarkReportRequest(val reason: String?)

interface AuthApi {
    @POST("login") suspend fun login(@Body req: AuthRequest): Response<AuthResponse>
    @POST("register") suspend fun register(@Body req: AuthRequest): Response<AuthResponse>
    @retrofit2.http.GET("bookmarks") suspend fun getBookmarks(): Response<List<Bookmark>>
    @POST("bookmarks") suspend fun createBookmark(
        @Header("Authorization") authorization: String,
        @Body req: BookmarkRequest
    ): Response<Bookmark>
    @DELETE("bookmarks/{id}") suspend fun deleteBookmark(
        @Header("Authorization") authorization: String,
        @retrofit2.http.Path("id") id: Long
    ): Response<AuthResponse>
    @POST("bookmarks/{id}/reports") suspend fun reportBookmark(
        @Header("Authorization") authorization: String,
        @retrofit2.http.Path("id") id: Long,
        @Body req: BookmarkReportRequest
    ): Response<AuthResponse>
    @POST("bookmarks/{id}/confirm") suspend fun confirmBookmark(
        @Header("Authorization") authorization: String,
        @retrofit2.http.Path("id") id: Long
    ): Response<AuthResponse>
    @POST("logout") suspend fun logout(@Header("Authorization") authorization: String): Response<AuthResponse>
}

object ApiClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val api: AuthApi = Retrofit.Builder()
        .baseUrl("http://10.21.5.60o:8080/") // Mudem o ip conforme necessário ( 127.0.0.1 se for no emulador )
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AuthApi::class.java)
}
