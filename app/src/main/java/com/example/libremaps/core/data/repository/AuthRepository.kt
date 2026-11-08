package com.example.libremaps.core.data.repository

import com.example.libremaps.core.model.*
import com.example.libremaps.core.network.ApiClient
import com.example.libremaps.core.network.AuthApi
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response

class AuthRepository(private val api: AuthApi = ApiClient.api) {
    suspend fun login(req: AuthRequest): Response<AuthResponse> = api.login(req)
    suspend fun register(req: AuthRequest): Response<AuthResponse> = api.register(req)
    suspend fun resetPassword(req: ResetPasswordRequest): Response<AuthResponse> = api.resetPassword(req)
    suspend fun changePassword(req: ChangePasswordRequest): Response<AuthResponse> = api.changePassword(req)
    suspend fun logout(token: String): Response<AuthResponse> = api.logout(token)
    suspend fun getProfile(token: String): Response<ProfileResponse> = api.getProfile(token)
    suspend fun updateProfile(token: String, req: UpdateProfileRequest): Response<AuthResponse> = api.updateProfile(token, req)
    suspend fun uploadAvatar(token: String, body: RequestBody): Response<AuthResponse> = api.uploadAvatar(token, body)
    suspend fun getAvatar(token: String): Response<ResponseBody> = api.getAvatar(token)
}
