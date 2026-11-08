package com.example.libremaps.core.model

data class ProfileResponse(
    val email: String,
    val displayName: String? = null,
    val hasAvatar: Boolean = false
)

data class UpdateProfileRequest(val displayName: String)
