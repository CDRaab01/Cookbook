package com.cookbook.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    @SerialName("invite_code") val inviteCode: String? = null,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class ForgotPasswordRequest(
    val email: String,
)

@Serializable
data class ResetPasswordRequest(
    val token: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
)

@Serializable
data class UserOut(
    val id: String,
    val name: String,
    val email: String,
)

@Serializable
data class VersionOut(
    val name: String = "",
    val version: String = "",
    val commit: String = "unknown",
    @SerialName("built_at") val builtAt: String = "unknown",
)
