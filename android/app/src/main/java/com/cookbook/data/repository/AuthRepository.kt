package com.cookbook.data.repository

/** Account operations the UI layer depends on. Implemented by [AuthRepositoryImpl]. */
interface AuthRepository {
    suspend fun register(name: String, email: String, password: String, inviteCode: String? = null)
    suspend fun login(email: String, password: String)
    suspend fun forgotPassword(email: String)
    suspend fun resetPassword(token: String, newPassword: String)
    suspend fun logout()
}
