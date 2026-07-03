package com.cookbook.ui.auth

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.SuiteAuthManager
import com.cookbook.data.repository.AuthRepository
import com.cookbook.util.AuthEventBus
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val suiteAuthManager: SuiteAuthManager,
    authEventBus: AuthEventBus,
) : ViewModel() {

    private val _authState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val authState: StateFlow<UiState<Unit>> = _authState

    /** Forced-logout signal (e.g. a rejected refresh token) — the nav graph bounces to login. */
    val logoutEvents: SharedFlow<Unit> = authEventBus.events

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = UiState.Loading
            _authState.value = try {
                authRepository.login(email, password)
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Login failed")
            }
        }
    }

    fun register(name: String, email: String, password: String, inviteCode: String? = null) {
        viewModelScope.launch {
            _authState.value = UiState.Loading
            _authState.value = try {
                authRepository.register(name, email, password, inviteCode)
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Registration failed")
            }
        }
    }

    fun forgotPassword(email: String) {
        viewModelScope.launch {
            _authState.value = UiState.Loading
            _authState.value = try {
                authRepository.forgotPassword(email)
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Request failed. Please try again.")
            }
        }
    }

    fun resetPassword(token: String, newPassword: String) {
        viewModelScope.launch {
            _authState.value = UiState.Loading
            _authState.value = try {
                authRepository.resetPassword(token, newPassword)
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Reset failed. Please try again.")
            }
        }
    }

    /** Intent that launches the Dragonfly (suite SSO) sign-in — launch via an ActivityResult. */
    fun suiteAuthorizeIntent(): Intent = suiteAuthManager.authorizeIntent()

    /** Handle the sign-in result: exchange → /auth/suite → session. Success drives navigation. */
    fun completeSuiteLogin(data: Intent?) {
        viewModelScope.launch {
            _authState.value = UiState.Loading
            _authState.value = try {
                suiteAuthManager.complete(data)
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Dragonfly sign-in failed")
            }
        }
    }

    fun clearState() {
        _authState.value = UiState.Idle
    }
}
