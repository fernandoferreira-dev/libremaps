package com.example.libremaps

import androidx.room.Entity
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.util.concurrent.TimeUnit
import androidx.room.PrimaryKey

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

// ---------- Perfil ----------
data class ProfileResponse(
    val email: String,
    val displayName: String? = null,
    val hasAvatar: Boolean = false
)

data class UpdateProfileRequest(val displayName: String)

// ---------- Bookmarks ----------
data class BookmarkRequest(
    val type: String,
    val description: String?,
    val latitude: Double,
    val longitude: Double
)

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

data class Bookmark(
    val id: Long,
    val type: String,
    val description: String?,
    val latitude: Double,
    val longitude: Double,
    val confirmations: Int = 0, 
    val createdAtMs: Long = 0
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(id, type, description, latitude, longitude, confirmations)
fun BookmarkEntity.toModel(): Bookmark = Bookmark(id, type, description, latitude, longitude, confirmations)

data class BookmarkReportRequest(val reason: String?)

// ---------- API ----------
interface AuthApi {
    @POST("login") suspend fun login(@Body req: AuthRequest): Response<AuthResponse>
    @POST("register") suspend fun register(@Body req: AuthRequest): Response<AuthResponse>
    @POST("reset-password") suspend fun resetPassword(@Body req: ResetPasswordRequest): Response<AuthResponse>
    @POST("change-password") suspend fun changePassword(@Body req: ChangePasswordRequest): Response<AuthResponse>
    @POST("logout") suspend fun logout(@Header("Authorization") authorization: String): Response<AuthResponse>

    // Perfil
    @GET("profile") suspend fun getProfile(
        @Header("Authorization") authorization: String
    ): Response<ProfileResponse>

    @PUT("profile") suspend fun updateProfile(
        @Header("Authorization") authorization: String,
        @Body req: UpdateProfileRequest
    ): Response<AuthResponse>

    @PUT("profile/avatar") suspend fun uploadAvatar(
        @Header("Authorization") authorization: String,
        @Body body: RequestBody
    ): Response<AuthResponse>

    @GET("profile/avatar") suspend fun getAvatar(
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    // Bookmarks
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val api: AuthApi = Retrofit.Builder()
        .baseUrl("http://127.0.0.1:8080/")
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AuthApi::class.java)
}
