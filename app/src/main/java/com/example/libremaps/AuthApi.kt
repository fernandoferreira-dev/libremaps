package com.example.libremaps

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

// ---------- Autenticação ----------
data class AuthRequest(val email: String, val password: String)

data class AuthResponse(
    val success: Boolean,
    val message: String,
    val token: String? = null,
    val userId: Long? = null
)

data class ResetPasswordRequest(val email: String, val newPassword: String)

data class ChangePasswordRequest(
    val email: String,
    val oldPassword: String,
    val newPassword: String
)

// ---------- Bookmarks ----------
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

// ---------- API ----------
interface AuthApi {
    @POST("login") suspend fun login(@Body req: AuthRequest): Response<AuthResponse>
    @POST("register") suspend fun register(@Body req: AuthRequest): Response<AuthResponse>
    @POST("reset-password") suspend fun resetPassword(@Body req: ResetPasswordRequest): Response<AuthResponse>
    @POST("change-password") suspend fun changePassword(@Body req: ChangePasswordRequest): Response<AuthResponse>
    @POST("logout") suspend fun logout(@Header("Authorization") authorization: String): Response<AuthResponse>

    @GET("bookmarks") suspend fun getBookmarks(): Response<List<Bookmark>>

    @POST("bookmarks") suspend fun createBookmark(
        @Header("Authorization") authorization: String,
        @Body req: BookmarkRequest
    ): Response<Bookmark>

    @DELETE("bookmarks/{id}") suspend fun deleteBookmark(
        @Header("Authorization") authorization: String,
        @Path("id") id: Long
    ): Response<AuthResponse>

    @POST("bookmarks/{id}/reports") suspend fun reportBookmark(
        @Header("Authorization") authorization: String,
        @Path("id") id: Long,
        @Body req: BookmarkReportRequest
    ): Response<AuthResponse>

    @POST("bookmarks/{id}/confirm") suspend fun confirmBookmark(
        @Header("Authorization") authorization: String,
        @Path("id") id: Long
    ): Response<AuthResponse>
}

object ApiClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val api: AuthApi = Retrofit.Builder()
        .baseUrl("http://10.0.2.2:8080/") // 10.0.2.2 = localhost do PC visto pelo emulador Android; num telemóvel real usem o IP da máquina na rede
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AuthApi::class.java)
}