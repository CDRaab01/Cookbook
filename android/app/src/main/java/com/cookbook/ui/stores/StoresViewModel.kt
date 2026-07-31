package com.cookbook.ui.stores

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.StoreOut
import com.cookbook.data.repository.StoreRepository
import com.cookbook.util.StoreLayoutDraft
import com.cookbook.util.StoreLayoutDraftStore
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class StoresViewModel @Inject constructor(
    private val repository: StoreRepository,
    private val draftStore: StoreLayoutDraftStore,
) : ViewModel() {

    private val _stores = MutableStateFlow<UiState<List<StoreOut>>>(UiState.Loading)
    val stores: StateFlow<UiState<List<StoreOut>>> = _stores

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** True while "Suggest layout" is waiting on the local model — it takes ~10s. */
    private val _suggesting = MutableStateFlow(false)
    val suggesting: StateFlow<Boolean> = _suggesting

    /** The pending suggestion, consumed by the editor. A draft, never saved on its own. */
    val draft: StateFlow<StoreLayoutDraft?> = draftStore.draft

    fun load() {
        viewModelScope.launch {
            _stores.value = try {
                UiState.Success(repository.stores())
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load your stores")
            }
        }
    }

    /**
     * Ask the local model for a starting layout. Never fails the flow — the server already falls
     * back to the standard aisle order when the model is unreachable, and an unexpected failure
     * here still yields a draft with no aisles, which the editor seeds from the defaults.
     */
    fun suggestLayout(chain: String, label: String?) {
        if (chain.isBlank()) return
        viewModelScope.launch {
            _suggesting.value = true
            val result = try {
                val out = repository.suggestLayout(chain.trim())
                StoreLayoutDraft(
                    chain.trim(), label?.trim(), out.aisles, out.lowConfidence, out.note,
                )
            } catch (_: IOException) {
                StoreLayoutDraft(
                    chain.trim(), label?.trim(), emptyList(), true,
                    "Couldn't reach the server — start from the standard aisles instead.",
                )
            } catch (e: Exception) {
                StoreLayoutDraft(chain.trim(), label?.trim(), emptyList(), true, e.message)
            }
            draftStore.offer(result)
            _suggesting.value = false
        }
    }

    /** Create with no aisles: the server seeds the canonical walk order. */
    fun createStore(name: String, label: String?, onCreated: (String) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val store = repository.create(name.trim(), label?.trim()?.ifBlank { null }, null)
                load()
                onCreated(store.id)
            } catch (e: Exception) {
                _error.value = friendly(e, "Couldn't add that store")
            }
        }
    }

    fun deleteStore(storeId: String) {
        viewModelScope.launch {
            try {
                repository.delete(storeId)
                load()
            } catch (e: Exception) {
                _error.value = friendly(e, "Couldn't delete that store")
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /** Store edits are online-only, so "you're offline" is the honest message, not a stack trace. */
    private fun friendly(e: Exception, fallback: String): String = when (e) {
        is IOException -> "$fallback — you're offline. Stores sync when you're back on."
        else -> e.message ?: fallback
    }
}
