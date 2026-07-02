package com.cookbook.data.repository

import com.cookbook.data.local.TokenStore
import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.ForgotPasswordRequest
import com.cookbook.data.remote.LoginRequest
import com.cookbook.data.remote.RegisterRequest
import com.cookbook.data.remote.ResetPasswordRequest
import com.cookbook.data.remote.TokenResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
) : AuthRepository {

    override suspend fun register(
        name: String,
        email: String,
        password: String,
        inviteCode: String?,
    ) {
        val tokens = api.register(RegisterRequest(name, email, password, inviteCode))
        persist(tokens)
    }

    override suspend fun login(email: String, password: String) {
        val tokens = api.login(LoginRequest(email, password))
        persist(tokens)
    }

    override suspend fun forgotPassword(email: String) {
        api.forgotPassword(ForgotPasswordRequest(email))
    }

    override suspend fun resetPassword(token: String, newPassword: String) {
        api.resetPassword(ResetPasswordRequest(token, newPassword))
    }

    override suspend fun logout() {
        tokenStore.clear()
    }

    private suspend fun persist(tokens: TokenResponse) {
        tokenStore.save(tokens.accessToken, tokens.refreshToken)
    }
}
