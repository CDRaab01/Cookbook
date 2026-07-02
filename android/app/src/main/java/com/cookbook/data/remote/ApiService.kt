package com.cookbook.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {

    // --- Auth ---
    @POST("auth/register")
    suspend fun register(@Body req: RegisterRequest): TokenResponse

    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): TokenResponse

    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body req: ForgotPasswordRequest)

    @POST("auth/reset-password")
    suspend fun resetPassword(@Body req: ResetPasswordRequest)

    // --- Users ---
    @GET("users/me")
    suspend fun getMe(): UserOut

    // --- Meta ---
    @GET("version")
    suspend fun getServerVersion(): VersionOut
}
