package com.cookbook.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.ListSummaryOut
import com.cookbook.data.remote.ShoppingItemOut
import com.cookbook.data.remote.ShoppingListOut
import com.cookbook.data.remote.SuggestionOut
import com.cookbook.data.repository.ShoppingRepository
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShoppingViewModel @Inject constructor(
    private val shoppingRepository: ShoppingRepository,
) : ViewModel() {

    private val _list = MutableStateFlow<UiState<ShoppingListOut>>(UiState.Loading)
    val list: StateFlow<UiState<ShoppingListOut>> = _list

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** All lists for the switcher menu (empty while offline). */
    private val _allLists = MutableStateFlow<List<ListSummaryOut>>(emptyList())
    val allLists: StateFlow<List<ListSummaryOut>> = _allLists

    fun load() {
        viewModelScope.launch {
            if (_list.value !is UiState.Success) _list.value = UiState.Loading
            _list.value = try {
                UiState.Success(shoppingRepository.getDefaultList())
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load the list")
            }
            _allLists.value = try {
                shoppingRepository.lists()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun switchList(listId: String) {
        viewModelScope.launch {
            shoppingRepository.setActiveList(listId)
            load()
        }
    }

    fun createList(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                _list.value = UiState.Success(shoppingRepository.createList(name.trim()))
                _allLists.value = shoppingRepository.lists()
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't create the list"
            }
        }
    }

    fun renameCurrentList(name: String) {
        val current = (_list.value as? UiState.Success)?.data ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                _list.value = UiState.Success(shoppingRepository.renameList(current.id, name.trim()))
                _allLists.value = shoppingRepository.lists()
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't rename the list"
            }
        }
    }

    fun deleteCurrentList() {
        val current = (_list.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            try {
                shoppingRepository.deleteList(current.id)
                load()
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't delete the list"
            }
        }
    }

    fun toggleChecked(itemId: String, checked: Boolean) {
        val current = (_list.value as? UiState.Success)?.data ?: return
        // Optimistic flip so the in-store tap feels instant; reconciled with the server response
        // (or rolled back on failure). Full offline queueing lands in Phase 4.
        _list.value = UiState.Success(
            current.copy(
                items = current.items.map {
                    if (it.id == itemId) it.copy(checked = checked) else it
                },
            ),
        )
        viewModelScope.launch {
            try {
                _list.value =
                    UiState.Success(shoppingRepository.setChecked(current.id, itemId, checked))
            } catch (e: Exception) {
                _list.value = UiState.Success(current) // roll back
                _error.value = e.message ?: "Couldn't update the item"
            }
        }
    }

    fun addItem(name: String, quantity: Double?, unit: String?, category: String?) {
        val current = (_list.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            try {
                _list.value = UiState.Success(
                    shoppingRepository.addItem(current.id, name, quantity, unit, category),
                )
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't add the item"
            }
        }
    }

    fun editItem(
        itemId: String,
        name: String,
        quantity: Double?,
        unit: String?,
        category: String?,
    ) {
        val current = (_list.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            try {
                _list.value = UiState.Success(
                    shoppingRepository.editItem(current.id, itemId, name, quantity, unit, category),
                )
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't update the item"
            }
        }
    }

    // --- Add-dialog autocomplete (v0.2) ---

    private val _suggestions = MutableStateFlow<List<SuggestionOut>>(emptyList())
    val suggestions: StateFlow<List<SuggestionOut>> = _suggestions

    private var suggestJob: Job? = null

    /** Debounced history lookup as the user types in the add dialog. */
    fun onAddNameChanged(text: String) {
        suggestJob?.cancel()
        if (text.length < 2) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(200)
            _suggestions.value = try {
                shoppingRepository.suggest(text)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
    }

    /** The just-deleted item, offered back via the snackbar's Undo (mis-taps happen mid-scroll). */
    private val _undoable = MutableStateFlow<ShoppingItemOut?>(null)
    val undoable: StateFlow<ShoppingItemOut?> = _undoable

    fun deleteItem(itemId: String) {
        val current = (_list.value as? UiState.Success)?.data ?: return
        val item = current.items.firstOrNull { it.id == itemId } ?: return
        viewModelScope.launch {
            try {
                _list.value = UiState.Success(shoppingRepository.deleteItem(current.id, itemId))
                _undoable.value = item
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't delete the item"
            }
        }
    }

    fun undoDelete() {
        val item = _undoable.value ?: return
        _undoable.value = null
        val current = (_list.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            try {
                // Re-adding each measure rebuilds the aggregate through the normal merge path.
                var out = current
                if (item.measures.isEmpty()) {
                    out = shoppingRepository.addItem(
                        current.id, item.name, item.quantity, item.unit, item.category,
                    )
                } else {
                    for (m in item.measures) {
                        out = shoppingRepository.addItem(
                            current.id, item.name, m.quantity, m.unit, item.category,
                        )
                    }
                }
                _list.value = UiState.Success(out)
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't restore the item"
            }
        }
    }

    fun clearUndoable() {
        _undoable.value = null
    }

    fun clearChecked() {
        val current = (_list.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            try {
                _list.value = UiState.Success(shoppingRepository.clearChecked(current.id))
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't clear checked items"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
