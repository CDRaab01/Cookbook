package com.cookbook.ui.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.PantryConfirmRequest
import com.cookbook.data.remote.PantryItemCreateRequest
import com.cookbook.data.repository.PantryRepository
import com.cookbook.util.PantryDraftStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One editable row on the confirmation screen. */
data class DetectedItem(
    val key: Long,
    val name: String,
    val category: String?,
    val lowConfidence: Boolean,
)

@HiltViewModel
class PantryConfirmViewModel @Inject constructor(
    private val pantryRepository: PantryRepository,
    pantryDraftStore: PantryDraftStore,
) : ViewModel() {

    private var nextKey = 0L

    private val _items = MutableStateFlow<List<DetectedItem>>(emptyList())
    val items: StateFlow<List<DetectedItem>> = _items

    /** The scan's own uncertainty note ("couldn't spot any food…"), when it had one. */
    val scanNote: String?

    private val _replace = MutableStateFlow(false)
    val replace: StateFlow<Boolean> = _replace

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** One-shot: items written — the screen pops back to the pantry. */
    private val _confirmed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val confirmed: SharedFlow<Unit> = _confirmed

    init {
        val draft = pantryDraftStore.consume()
        scanNote = draft?.note
        _items.value = draft?.items.orEmpty().map {
            DetectedItem(
                key = nextKey++,
                name = it.name,
                category = it.category,
                lowConfidence = it.confidence == "low",
            )
        }
    }

    fun updateItem(key: Long, name: String? = null, category: String? = null, clearCategory: Boolean = false) {
        _items.value = _items.value.map {
            if (it.key != key) {
                it
            } else {
                it.copy(
                    name = name ?: it.name,
                    category = if (clearCategory) null else category ?: it.category,
                )
            }
        }
    }

    fun removeItem(key: Long) {
        _items.value = _items.value.filterNot { it.key == key }
    }

    fun addItem(name: String, category: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _items.value = _items.value + DetectedItem(
            key = nextKey++,
            name = trimmed,
            category = category,
            lowConfidence = false,
        )
    }

    fun setReplace(value: Boolean) {
        _replace.value = value
    }

    fun confirm() {
        val toSave = _items.value.map { it.name.trim() }.zip(_items.value) { name, item ->
            PantryItemCreateRequest(name = name, category = item.category)
        }.filter { it.name.isNotEmpty() }
        if (toSave.isEmpty() || _saving.value) return
        viewModelScope.launch {
            _saving.value = true
            try {
                pantryRepository.confirmItems(
                    PantryConfirmRequest(items = toSave, replace = _replace.value),
                )
                _confirmed.tryEmit(Unit)
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't save your pantry"
            } finally {
                _saving.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
