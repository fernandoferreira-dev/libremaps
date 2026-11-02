package com.example.libremaps

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class AuthRequest(val email: String, val password: String)
data class AuthResponse(val success: Boolean, val message: String)

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
    val longitude: Double
)

interface AuthApi {
    @POST("login") suspend fun login(@Body req: AuthRequest): Response<AuthResponse>
    @POST("register") suspend fun register(@Body req: AuthRequest): Response<AuthResponse>
    @retrofit2.http.GET("bookmarks") suspend fun getBookmarks(): Response<List<Bookmark>>
    @POST("bookmarks") suspend fun createBookmark(@Body req: BookmarkRequest): Response<Bookmark>
}

object ApiClient {
    val api: AuthApi = Retrofit.Builder()
        .baseUrl("http://127.0.0.1:8080/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AuthApi::class.java)
}
