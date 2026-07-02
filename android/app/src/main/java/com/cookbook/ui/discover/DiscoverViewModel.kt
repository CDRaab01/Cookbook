package com.cookbook.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.DiscoveredRecipe
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<UiState<List<DiscoveredRecipe>>>(UiState.Idle)
    val results: StateFlow<UiState<List<DiscoveredRecipe>>> = _results

    /** Source id currently importing (disables that row's button). */
    private val _importing = MutableStateFlow<String?>(null)
    val importing: StateFlow<String?> = _importing

    /** One-shot: the imported recipe's id — the screen navigates to its detail. */
    private val _imported = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val imported: SharedFlow<String> = _imported

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun setQuery(value: String) {
        _query.value = value
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
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
    }

    fun import(sourceId: String) {
        if (_importing.value != null) return
        viewModelScope.launch {
            _importing.value = sourceId
            try {
                val recipe = recipeRepository.importRecipe(sourceId)
                _imported.tryEmit(recipe.id)
            } catch (e: Exception) {
                _error.value = e.message ?: "Import failed"
            } finally {
                _importing.value = null
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
