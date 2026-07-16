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

    private val _userName = MutableStateFlow<String?>(null)
    val userName: StateFlow<String?> = _userName

    private val _userEmail = MutableStateFlow<String?>(null)
    val userEmail: StateFlow<String?> = _userEmail

    // Family mode (household sharing). Null until loaded; best-effort like the other reads.
    private val _household = MutableStateFlow<com.cookbook.data.remote.HouseholdOut?>(null)
    val household: StateFlow<com.cookbook.data.remote.HouseholdOut?> = _household

    private val _householdError = MutableStateFlow<String?>(null)
    val householdError: StateFlow<String?> = _householdError

    fun load() {
        viewModelScope.launch {
            _serverVersion.value = try {
                val v = api.getServerVersion()
                "${v.version} · ${v.commit.take(7)}"
            } catch (_: Exception) {
                null
            }
            try {
                val me = api.getMe()
                _userName.value = me.name
                _userEmail.value = me.email
            } catch (_: Exception) {
                // Leave name/email null; the header falls back to a neutral label.
            }
            // Best-effort: a failed household read must not fail the screen.
            _household.value = runCatching { api.getHousehold() }.getOrNull()
        }
    }

    /** Share the cookbook + lists with another Cookbook user by email (owner only, enforced server-side). */
    fun addHouseholdMember(email: String) {
        val trimmed = email.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _householdError.value = null
            try {
                _household.value = api.addHouseholdMember(
                    com.cookbook.data.remote.AddMemberRequest(trimmed),
                )
            } catch (e: retrofit2.HttpException) {
                _householdError.value = when (e.code()) {
                    404 -> "No Cookbook user with that email — they need to sign in first"
                    403 -> "Only the household owner can invite"
                    409 -> "They're already in another household"
                    else -> "Couldn't share with that email (${e.code()})"
                }
            } catch (e: Exception) {
                _householdError.value = e.message ?: "Couldn't share with that email"
            }
        }
    }

    /** Owner removes a member from the household. */
    fun removeHouseholdMember(userId: String) {
        viewModelScope.launch {
            _householdError.value = null
            try {
                api.removeHouseholdMember(userId)
                _household.value = runCatching { api.getHousehold() }.getOrNull()
            } catch (e: Exception) {
                _householdError.value = e.message ?: "Couldn't remove"
            }
        }
    }

    /** Leave the household (a non-owner member). */
    fun leaveHousehold() {
        viewModelScope.launch {
            _householdError.value = null
            try {
                api.leaveHousehold()
                _household.value = runCatching { api.getHousehold() }.getOrNull()
            } catch (e: Exception) {
                _householdError.value = e.message ?: "Couldn't leave"
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
