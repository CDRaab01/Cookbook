package com.cookbook.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.DiscoveredRecipe
import com.cookbook.data.remote.RecipePreviewOut
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.util.RecipeDraftStore
import com.cookbook.util.SharedIntentStore
import com.cookbook.util.UiState
import com.cookbook.util.offlineAwareMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val sharedIntentStore: SharedIntentStore,
    private val recipeDraftStore: RecipeDraftStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<UiState<List<DiscoveredRecipe>>>(UiState.Idle)
    val results: StateFlow<UiState<List<DiscoveredRecipe>>> = _results

    /** Non-null while the tap-to-preview sheet is open. */
    private val _preview = MutableStateFlow<UiState<RecipePreviewOut>?>(null)
    val preview: StateFlow<UiState<RecipePreviewOut>?> = _preview

    /** Source id currently importing (disables that row's button). */
    private val _importing = MutableStateFlow<String?>(null)
    val importing: StateFlow<String?> = _importing

    /** Non-null while the URL-import dialog is open; holds the draft URL. */
    private val _urlDraft = MutableStateFlow<String?>(null)
    val urlDraft: StateFlow<String?> = _urlDraft

    private val _importingUrl = MutableStateFlow(false)
    val importingUrl: StateFlow<Boolean> = _importingUrl

    /** One-shot: the imported recipe's id — the screen navigates to its detail. */
    private val _imported = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val imported: SharedFlow<String> = _imported

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _importingPhoto = MutableStateFlow(false)
    val importingPhoto: StateFlow<Boolean> = _importingPhoto

    /** One-shot: a photo draft is ready — the screen opens a new, pre-filled recipe editor. */
    private val _photoDraftReady = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val photoDraftReady: SharedFlow<Unit> = _photoDraftReady

    init {
        // A URL shared from the browser (ACTION_SEND) opens the import dialog pre-filled.
        viewModelScope.launch {
            sharedIntentStore.sharedUrl.collect { url ->
                if (url != null) {
                    _urlDraft.value = sharedIntentStore.consume()
                }
            }
        }
    }

    private var searchJob: Job? = null

    /** Live search: debounced so every keystroke doesn't fire a request, but no button-press
     * is required — matches Discover to how the rest of the app searches. */
    fun setQuery(value: String) {
        _query.value = value
        searchJob?.cancel()
        val q = value.trim()
        if (q.isEmpty()) {
            _results.value = UiState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            runSearch(q)
        }
    }

    /** Explicit trigger (search-icon tap, IME action) — skips the debounce. */
    fun search() {
        searchJob?.cancel()
        val q = _query.value.trim()
        if (q.isEmpty()) return
        viewModelScope.launch { runSearch(q) }
    }

    private suspend fun runSearch(q: String) {
        _results.value = UiState.Loading
        _results.value = try {
            UiState.Success(recipeRepository.discoverRecipes(q))
        } catch (e: HttpException) {
            if (e.code() == 503) {
                UiState.Error(
                    "Discovery isn't set up yet — add a Spoonacular key to the server.",
                )
            } else {
                UiState.Error(e.message ?: "Search failed")
            }
        } catch (e: Exception) {
            UiState.Error(e.message ?: "Search failed")
        }
    }

    fun openPreview(sourceId: String) {
        viewModelScope.launch {
            _preview.value = UiState.Loading
            _preview.value = try {
                UiState.Success(recipeRepository.previewRecipe(sourceId))
            } catch (e: Exception) {
                UiState.Error(e.offlineAwareMessage("Couldn't load the recipe"))
            }
        }
    }

    fun closePreview() {
        _preview.value = null
    }

    fun import(sourceId: String) {
        if (_importing.value != null) return
        viewModelScope.launch {
            _importing.value = sourceId
            try {
                val recipe = recipeRepository.importRecipe(sourceId)
                _preview.value = null
                _imported.tryEmit(recipe.id)
            } catch (e: Exception) {
                _error.value = e.offlineAwareMessage("Import failed")
            } finally {
                _importing.value = null
            }
        }
    }

    // --- URL import ---

    fun openUrlDialog(prefill: String = "") {
        _urlDraft.value = prefill
    }

    fun setUrlDraft(value: String) {
        _urlDraft.value = value
    }

    fun closeUrlDialog() {
        _urlDraft.value = null
    }

    fun importFromUrl() {
        val url = _urlDraft.value?.trim().orEmpty()
        if (url.isEmpty() || _importingUrl.value) return
        viewModelScope.launch {
            _importingUrl.value = true
            try {
                val recipe = recipeRepository.importRecipeFromUrl(url)
                _urlDraft.value = null
                _imported.tryEmit(recipe.id)
            } catch (e: HttpException) {
                _error.value = if (e.code() == 422) {
                    "Couldn't find a recipe at that link. Try the site's original recipe page."
                } else {
                    "Import failed (${e.code()})"
                }
            } catch (e: Exception) {
                _error.value = e.offlineAwareMessage("Import failed")
            } finally {
                _importingUrl.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    // --- Photo import ---

    fun importPhoto(bytes: ByteArray, mimeType: String, fileName: String) {
        if (_importingPhoto.value) return
        viewModelScope.launch {
            _importingPhoto.value = true
            try {
                val draft = recipeRepository.importPhoto(bytes, mimeType, fileName)
                recipeDraftStore.offer(draft)
                _photoDraftReady.tryEmit(Unit)
            } catch (e: HttpException) {
                _error.value = when (e.code()) {
                    503 -> "Couldn't reach LM Studio. Is it running?"
                    504 -> "The vision model timed out — it may still be loading."
                    else -> "Couldn't read that photo (${e.code()})"
                }
            } catch (e: Exception) {
                _error.value = e.offlineAwareMessage("Couldn't read that photo")
            } finally {
                _importingPhoto.value = false
            }
        }
    }
}
