package com.cookbook.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.GrocerySpendOut
import com.cookbook.data.remote.ListSummaryOut
import com.cookbook.data.remote.ShoppingItemOut
import com.cookbook.data.remote.ShoppingListOut
import com.cookbook.data.remote.SuggestionOut
import com.cookbook.data.repository.ShoppingRepository
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.cookbook.util.DEFAULT_AISLE_ORDER
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShoppingViewModel @Inject constructor(
    private val shoppingRepository: ShoppingRepository,
    private val widgetRefresher: com.cookbook.widget.WidgetRefresher,
    appPreferences: com.cookbook.util.AppPreferences,
) : ViewModel() {

    private val _list = MutableStateFlow<UiState<ShoppingListOut>>(UiState.Loading)
    val list: StateFlow<UiState<ShoppingListOut>> = _list

    /** True while the repository is serving the Room mirror (server unreachable) — the screen
     *  shows the "Offline — changes will sync" banner. The local queue is authoritative, so
     *  this is a sync-pending notice, not an "as of" staleness stamp. */
    val offline: StateFlow<Boolean> = shoppingRepository.offline

    /** The user's store-walk aisle order, for grouping list items (falls back to the default). */
    val aisleOrder: StateFlow<List<String>> = appPreferences.aisleOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_AISLE_ORDER)

    /** The pinned default list (what both tabs open to on launch); null when none is pinned. Drives
     *  the "· Default" marker in the list switcher. */
    val pinnedListId: StateFlow<String?> = appPreferences.pinnedListId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    /** Pin the current list as the default both tabs open to on launch. */
    fun setDefaultList(listId: String) {
        viewModelScope.launch {
            shoppingRepository.setDefaultList(listId)
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
        clearLink: Boolean = false,
    ) {
        val current = (_list.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            try {
                _list.value = UiState.Success(
                    shoppingRepository.editItem(
                        current.id, itemId, name, quantity, unit, category, clearLink,
                    ),
                )
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't update the item"
            }
        }
    }

    /** The −/＋ count stepper on a link item. Optimistic like [toggleChecked]; floors at 1. */
    fun setLinkItemQuantity(item: ShoppingItemOut, count: Int) {
        val current = (_list.value as? UiState.Success)?.data ?: return
        val newCount = count.coerceAtLeast(1)
        if (item.quantity?.toInt() == newCount) return
        _list.value = UiState.Success(
            current.copy(
                items = current.items.map {
                    if (it.id == item.id) it.copy(quantity = newCount.toDouble(), unit = null) else it
                },
            ),
        )
        viewModelScope.launch {
            try {
                _list.value = UiState.Success(
                    shoppingRepository.editItem(
                        current.id, item.id, item.name, newCount.toDouble(), null, item.category,
                    ),
                )
            } catch (e: Exception) {
                _list.value = UiState.Success(current) // roll back
                _error.value = e.message ?: "Couldn't update the quantity"
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
                // A link item re-adds as "name url" so the standard split restores the link.
                val rawName = listOfNotNull(item.name, item.linkUrl).joinToString(" ")
                var out = current
                if (item.measures.isEmpty()) {
                    out = shoppingRepository.addItem(
                        current.id, rawName, item.quantity, item.unit, item.category,
                    )
                } else {
                    for (m in item.measures) {
                        out = shoppingRepository.addItem(
                            current.id, rawName, m.quantity, m.unit, item.category,
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
