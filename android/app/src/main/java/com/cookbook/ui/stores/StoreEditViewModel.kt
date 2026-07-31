package com.cookbook.ui.stores

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.AisleIn
import com.cookbook.data.repository.StoreRepository
import com.cookbook.util.DEFAULT_AISLE_ORDER
import com.cookbook.util.StoreLayoutDraftStore
import com.cookbook.util.categoryLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * One row in the editor. [id] is the server's aisle id when this row already exists — carrying it
 * through the save is what lets a reorder or rename keep the placements learned by walking the
 * store; a row without one is new, and an aisle dropped from the list is deleted server-side.
 */
data class AisleRow(
    val id: String?,
    val name: String,
    val categories: List<String>,
)

/** Every canonical category not claimed by any row — offered when assigning. */
fun unclaimedCategories(rows: List<AisleRow>): List<String> {
    val claimed = rows.flatMap { it.categories }.toSet()
    return DEFAULT_AISLE_ORDER.filterNot { it in claimed }
}

/** The canonical walk order as editor rows — the "start from defaults" shape. */
fun defaultAisleRows(): List<AisleRow> =
    DEFAULT_AISLE_ORDER.map { AisleRow(null, categoryLabel(it), listOf(it)) }

@HiltViewModel
class StoreEditViewModel @Inject constructor(
    private val repository: StoreRepository,
    private val draftStore: StoreLayoutDraftStore,
) : ViewModel() {

    private val _rows = MutableStateFlow<List<AisleRow>>(emptyList())
    val rows: StateFlow<List<AisleRow>> = _rows

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    /** Set when this screen is composing an unsaved draft — the store doesn't exist yet. */
    private val _pendingStore = MutableStateFlow<Pair<String, String?>?>(null)
    val pendingStore: StateFlow<Pair<String, String?>?> = _pendingStore

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var storeId: String? = null

    /**
     * Open an existing store's floor plan, or the pending draft when [id] is null.
     *
     * A draft with no aisles (the model was unreachable, or returned nothing usable) seeds the
     * canonical order rather than an empty screen: the user asked to set up a store, and a list to
     * drag around is a better answer than a blank page and an error.
     */
    fun open(id: String?) {
        if (id != null) {
            storeId = id
            viewModelScope.launch {
                val store = repository.store(id)
                if (store == null) {
                    _error.value = "Couldn't load that store"
                    return@launch
                }
                _title.value = store.displayName
                _rows.value = store.aisles.sortedBy { it.order }
                    .map { AisleRow(it.id, it.name, it.categories) }
            }
            return
        }
        val draft = draftStore.consume()
        if (draft == null) {
            _error.value = "Nothing to edit"
            return
        }
        _pendingStore.value = draft.chain to draft.label
        _title.value = listOfNotNull(draft.chain, draft.label).joinToString(" — ")
        _rows.value = draft.aisles
            .map { AisleRow(null, it.name, it.categories) }
            .ifEmpty { defaultAisleRows() }
        _note.value = draft.note
    }

    fun move(index: Int, delta: Int) {
        val current = _rows.value.toMutableList()
        val target = index + delta
        if (index !in current.indices || target !in current.indices) return
        current.add(target, current.removeAt(index))
        _rows.value = current
    }

    fun rename(index: Int, name: String) {
        _rows.value = _rows.value.mapIndexed { i, row -> if (i == index) row.copy(name = name) else row }
    }

    fun addAisle(name: String) {
        if (name.isBlank()) return
        _rows.value = _rows.value + AisleRow(null, name.trim(), emptyList())
    }

    fun removeAisle(index: Int) {
        _rows.value = _rows.value.filterIndexed { i, _ -> i != index }
    }

    /**
     * Assign a category to an aisle, taking it off whichever aisle held it.
     *
     * Exclusive on purpose: the server's routing gives a twice-claimed category to the first aisle
     * in walk order, so letting the editor show it in two places would display a rule the list
     * doesn't follow.
     */
    fun assignCategory(index: Int, category: String) {
        _rows.value = _rows.value.mapIndexed { i, row ->
            when {
                i == index && category !in row.categories -> row.copy(categories = row.categories + category)
                i != index -> row.copy(categories = row.categories - category)
                else -> row
            }
        }
    }

    fun unassignCategory(index: Int, category: String) {
        _rows.value = _rows.value.mapIndexed { i, row ->
            if (i == index) row.copy(categories = row.categories - category) else row
        }
    }

    fun resetToDefaults() {
        // Keep the ids so a reset is still a reorder, not a wipe of every learned placement.
        val byCategory = _rows.value.associateBy { it.categories.firstOrNull() }
        _rows.value = DEFAULT_AISLE_ORDER.map { category ->
            AisleRow(byCategory[category]?.id, categoryLabel(category), listOf(category))
        }
    }

    /** Persist. Creates the store first when this was a draft, so nothing exists until now. */
    fun save(onSaved: () -> Unit) {
        val rows = _rows.value.filter { it.name.isNotBlank() }
        if (rows.isEmpty()) {
            _error.value = "A store needs at least one aisle"
            return
        }
        viewModelScope.launch {
            _saving.value = true
            try {
                val aisles = rows.map { AisleIn(it.id, it.name.trim(), it.categories) }
                val pending = _pendingStore.value
                if (pending != null) {
                    repository.create(pending.first, pending.second, aisles)
                } else {
                    repository.putAisles(storeId ?: return@launch, aisles)
                }
                onSaved()
            } catch (e: Exception) {
                _error.value = when (e) {
                    is IOException -> "Couldn't save — you're offline. Store layouts need a connection."
                    else -> e.message ?: "Couldn't save that layout"
                }
            } finally {
                _saving.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
