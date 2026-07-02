package com.cookbook.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.BuildConfig
import com.cookbook.data.remote.ApiService
import com.cookbook.data.repository.AuthRepository
import com.cookbook.util.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val appPreferences: AppPreferences,
    private val api: ApiService,
) : ViewModel() {

    val appVersion: String = BuildConfig.VERSION_NAME

    val serverUrl: StateFlow<String> = appPreferences.serverUrl.stateIn(
        viewModelScope, SharingStarted.Eagerly, "",
    )

    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion

    private val _userLabel = MutableStateFlow<String?>(null)
    val userLabel: StateFlow<String?> = _userLabel

    fun load() {
        viewModelScope.launch {
            _serverVersion.value = try {
                val v = api.getServerVersion()
                "${v.version} · ${v.commit.take(7)}"
            } catch (_: Exception) {
                null
            }
            _userLabel.value = try {
                val me = api.getMe()
                "${me.name} · ${me.email}"
            } catch (_: Exception) {
                null
            }
        }
    }

    fun setServerUrl(value: String) {
        viewModelScope.launch { appPreferences.setServerUrl(value.trim()) }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onLoggedOut()
        }
    }
}
