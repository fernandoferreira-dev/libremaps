package com.example.libremaps.core.network

import com.example.libremaps.core.model.*
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

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
