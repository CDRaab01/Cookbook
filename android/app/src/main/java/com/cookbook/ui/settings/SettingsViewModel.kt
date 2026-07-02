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

    private val _migrationStatus = MutableStateFlow<String?>(null)
    val migrationStatus: StateFlow<String?> = _migrationStatus

    private val _migrating = MutableStateFlow(false)
    val migrating: StateFlow<Boolean> = _migrating

    /** One-time pull of the user's Plate recipes (idempotent server-side; safe to re-tap). */
    fun migrateFromPlate() {
        if (_migrating.value) return
        viewModelScope.launch {
            _migrating.value = true
            _migrationStatus.value = try {
                val result = api.migrateFromPlate()
                when {
                    result.imported > 0 && result.skipped > 0 ->
                        "Imported ${result.imported} recipes (${result.skipped} already here)"
                    result.imported > 0 -> "Imported ${result.imported} recipes from Plate"
                    result.skipped > 0 -> "Nothing new — all ${result.skipped} already imported"
                    else -> "No recipes found in Plate"
                }
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 503) {
                    "Plate integration isn't configured on the server"
                } else {
                    "Import failed (${e.code()})"
                }
            } catch (e: Exception) {
                e.message ?: "Import failed"
            } finally {
                _migrating.value = false
            }
        }
    }

    fun clearMigrationStatus() {
        _migrationStatus.value = null
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onLoggedOut()
        }
    }
}
