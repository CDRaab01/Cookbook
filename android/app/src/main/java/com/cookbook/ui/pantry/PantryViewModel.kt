package com.cookbook.ui.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.PantryItemOut
import com.cookbook.data.remote.StaplesOut
import com.cookbook.data.repository.PantryRepository
import com.cookbook.util.PantryDraftStore
import com.cookbook.util.UiState
import com.cookbook.util.offlineAwareMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

@HiltViewModel
class PantryViewModel @Inject constructor(
    private val pantryRepository: PantryRepository,
    private val pantryDraftStore: PantryDraftStore,
) : ViewModel() {

    private val _pantry = MutableStateFlow<UiState<List<PantryItemOut>>>(UiState.Idle)
    val pantry: StateFlow<UiState<List<PantryItemOut>>> = _pantry

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** One-shot: a scan draft is in [PantryDraftStore] — the screen opens the confirm flow. */
    private val _scanDraftReady = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scanDraftReady: SharedFlow<Unit> = _scanDraftReady

    /** Null until loaded; confirmed=false triggers the one-time staples review sheet. */
    private val _staples = MutableStateFlow<StaplesOut?>(null)
    val staples: StateFlow<StaplesOut?> = _staples

    private val _savingStaples = MutableStateFlow(false)
    val savingStaples: StateFlow<Boolean> = _savingStaples

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun load() {
        viewModelScope.launch {
            if (_pantry.value !is UiState.Success) _pantry.value = UiState.Loading
            _pantry.value = try {
                UiState.Success(pantryRepository.getPantry())
            } catch (e: Exception) {
                UiState.Error(e.offlineAwareMessage("Couldn't load your pantry"))
            }
            // Staples ride along on the first load; a failure just skips the first-use sheet.
            if (_staples.value == null) {
                _staples.value = try {
                    pantryRepository.getStaples()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun scanPhoto(bytes: ByteArray, mimeType: String, fileName: String) {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            try {
                val draft = pantryRepository.scanPhoto(bytes, mimeType, fileName)
                pantryDraftStore.offer(draft)
                _scanDraftReady.tryEmit(Unit)
            } catch (e: HttpException) {
                _error.value = when (e.code()) {
                    503 -> "Couldn't reach LM Studio. Is it running?"
                    504 -> "The vision model timed out — it may still be loading."
                    else -> "Couldn't read that photo (${e.code()})"
                }
            } catch (e: Exception) {
                _error.value = e.offlineAwareMessage("Couldn't read that photo")
            } finally {
                _scanning.value = false
            }
        }
    }

    fun addItem(name: String, category: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                pantryRepository.addItem(trimmed, category)
                load()
            } catch (e: Exception) {
                _error.value = e.offlineAwareMessage("Couldn't add that item")
            }
        }
    }

    fun updateItem(id: String, name: String, category: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                pantryRepository.updateItem(id, trimmed, category)
                load()
            } catch (e: Exception) {
                _error.value = e.offlineAwareMessage("Couldn't update that item")
            }
        }
    }

    fun deleteItem(id: String) {
        viewModelScope.launch {
            try {
                pantryRepository.deleteItem(id)
                load()
            } catch (e: Exception) {
                _error.value = e.offlineAwareMessage("Couldn't remove that item")
            }
        }
    }

    /** First-use sheet: persist the reviewed staples and stamp the confirmation. */
    fun confirmStaples(staples: List<String>) {
        if (_savingStaples.value) return
        viewModelScope.launch {
            _savingStaples.value = true
            try {
                _staples.value = pantryRepository.putStaples(staples)
            } catch (e: Exception) {
                _error.value = e.offlineAwareMessage("Couldn't save your staples")
            } finally {
                _savingStaples.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
