package com.cookbook.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.OrganizeMove
import com.cookbook.data.remote.OrganizeSuggestion
import com.cookbook.data.repository.ShoppingRepository
import com.cookbook.util.OrganizeDraftStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class OrganizeReviewViewModel @Inject constructor(
    private val repository: ShoppingRepository,
    private val draftStore: OrganizeDraftStore,
) : ViewModel() {

    private val _suggestions = MutableStateFlow<List<OrganizeSuggestion>>(emptyList())
    val suggestions: StateFlow<List<OrganizeSuggestion>> = _suggestions

    /**
     * Which moves are ticked. Default is **all on**: every suggestion here is one the model
     * already decided was a real mistake, and the parser dropped everything it couldn't verify,
     * so making the user tick each one would be busywork. Untick is the escape hatch.
     */
    private val _accepted = MutableStateFlow<Set<String>>(emptySet())
    val accepted: StateFlow<Set<String>> = _accepted

    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var listId: String? = null

    fun open(listId: String) {
        this.listId = listId
        // Consume: backing out of the review discards it rather than letting a stale draft
        // reappear over a list that has since changed.
        val draft = draftStore.consume() ?: return
        _suggestions.value = draft.suggestions
        _accepted.value = draft.suggestions.map { it.itemId }.toSet()
    }

    fun toggle(itemId: String) {
        _accepted.value = if (itemId in _accepted.value) {
            _accepted.value - itemId
        } else {
            _accepted.value + itemId
        }
    }

    fun setAll(accepted: Boolean) {
        _accepted.value = if (accepted) _suggestions.value.map { it.itemId }.toSet() else emptySet()
    }

    /** Write only the ticked moves. No model call, so this works with LM Studio down. */
    fun apply(onApplied: () -> Unit) {
        val id = listId ?: return
        val moves = _suggestions.value
            .filter { it.itemId in _accepted.value }
            .map { OrganizeMove(it.itemId, it.suggestedCategory) }
        if (moves.isEmpty()) {
            onApplied()
            return
        }
        viewModelScope.launch {
            _applying.value = true
            try {
                repository.applyOrganize(id, moves)
                onApplied()
            } catch (e: Exception) {
                _error.value = when (e) {
                    is IOException -> "You're offline — try applying again when you're back on."
                    else -> e.message ?: "Couldn't apply those changes"
                }
            } finally {
                _applying.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
