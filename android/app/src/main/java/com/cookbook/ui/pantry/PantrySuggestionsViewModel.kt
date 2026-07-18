package com.cookbook.ui.pantry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.PantrySuggestionsOut
import com.cookbook.data.remote.RecipePreviewOut
import com.cookbook.data.repository.PantryRepository
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.util.UiState
import com.cookbook.util.offlineAwareMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PantrySuggestionsViewModel @Inject constructor(
    private val pantryRepository: PantryRepository,
    private val recipeRepository: RecipeRepository,
) : ViewModel() {

    private val _suggestions = MutableStateFlow<UiState<PantrySuggestionsOut>>(UiState.Idle)
    val suggestions: StateFlow<UiState<PantrySuggestionsOut>> = _suggestions

    /** Non-null while the external-hit preview sheet is open (the Discover idiom). */
    private val _preview = MutableStateFlow<UiState<RecipePreviewOut>?>(null)
    val preview: StateFlow<UiState<RecipePreviewOut>?> = _preview

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    /** One-shot: an external hit was imported — the screen opens the new recipe. */
    private val _imported = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val imported: SharedFlow<String> = _imported

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun load() {
        viewModelScope.launch {
            _suggestions.value = UiState.Loading
            _suggestions.value = try {
                UiState.Success(pantryRepository.getSuggestions())
            } catch (e: Exception) {
                UiState.Error(e.offlineAwareMessage("Couldn't load suggestions"))
            }
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
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            try {
                val recipe = recipeRepository.importRecipe(sourceId)
                _preview.value = null
                _imported.tryEmit(recipe.id)
            } catch (e: Exception) {
                _error.value = e.offlineAwareMessage("Import failed")
            } finally {
                _importing.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
