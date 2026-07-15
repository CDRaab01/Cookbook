package com.cookbook.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.GrocerySpendOut
import com.cookbook.data.remote.ListSummaryOut
import com.cookbook.data.remote.MemberOut
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
    private val widgetRefresher: com.cookbook.widget.WidgetRefresher,
) : ViewModel() {

    private val _list = MutableStateFlow<UiState<ShoppingListOut>>(UiState.Loading)
    val list: StateFlow<UiState<ShoppingListOut>> = _list

    init {
        // Any successful list state (load or mutation) redraws the home-screen widget, so it
        // never sits stale next to a fresh app.
        viewModelScope.launch {
            _list.collect { if (it is UiState.Success) widgetRefresher.refresh() }
        }
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** All lists for the switcher menu (empty while offline). */
    private val _allLists = MutableStateFlow<List<ListSummaryOut>>(emptyList())
    val allLists: StateFlow<List<ListSummaryOut>> = _allLists

    /** This month's grocery spend, reported by Magpie; null hides the tile (Link D). */
    private val _grocerySpend = MutableStateFlow<GrocerySpendOut?>(null)
    val grocerySpend: StateFlow<GrocerySpendOut?> = _grocerySpend

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
            // Best-effort; the repo already swallows failures to null (tile hides).
            _grocerySpend.value = shoppingRepository.grocerySpend()
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

    // --- Household sharing ---

    private val _members = MutableStateFlow<List<MemberOut>>(emptyList())
    val members: StateFlow<List<MemberOut>> = _members

    /** The signed-in user's id, so the share sheet can mark "you" and enable Leave. */
    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId

    /** Load the active list's members (call when opening the share sheet). Also caches "me". */
    fun loadMembers() {
        val current = (_list.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            if (_currentUserId.value == null) {
                _currentUserId.value = runCatching { shoppingRepository.me().id }.getOrNull()
            }
            _members.value = try {
                shoppingRepository.listMembers(current.id)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun shareWith(email: String) {
        val current = (_list.value as? UiState.Success)?.data ?: return
        if (email.isBlank()) return
        viewModelScope.launch {
            try {
                _members.value = shoppingRepository.shareList(current.id, email.trim())
                _allLists.value = shoppingRepository.lists() // refresh the "shared" badge
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't share the list"
            }
        }
    }

    /** Remove a member (owner) or leave (a member removing their own id). */
    fun removeMember(memberId: String) {
        val current = (_list.value as? UiState.Success)?.data ?: return
        val leaving = memberId == _currentUserId.value
        viewModelScope.launch {
            try {
                shoppingRepository.removeMember(current.id, memberId)
                if (leaving) {
                    _members.value = emptyList()
                    load() // lost access — reload (the list drops out of the index)
                } else {
                    _members.value = shoppingRepository.listMembers(current.id)
                    _allLists.value = shoppingRepository.lists()
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't update sharing"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
