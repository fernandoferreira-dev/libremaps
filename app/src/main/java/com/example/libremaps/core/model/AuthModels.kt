package com.example.libremaps.core.model

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
