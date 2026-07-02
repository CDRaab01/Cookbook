package com.cookbook.ui.recipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecipeListViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
) : ViewModel() {

    private val _recipes = MutableStateFlow<UiState<List<RecipeSummaryOut>>>(UiState.Loading)
    val recipes: StateFlow<UiState<List<RecipeSummaryOut>>> = _recipes

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    fun load() {
        viewModelScope.launch {
            // Only show the spinner when nothing is on screen yet (Spotter convention) so a
            // resume-refresh doesn't flash.
            if (_recipes.value !is UiState.Success) _recipes.value = UiState.Loading
            _recipes.value = try {
                UiState.Success(recipeRepository.listRecipes())
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load recipes")
            }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    /** The loaded list filtered by the search box (name match, case-insensitive). */
    fun filtered(list: List<RecipeSummaryOut>): List<RecipeSummaryOut> {
        val q = _query.value.trim()
        if (q.isEmpty()) return list
        return list.filter { it.name.contains(q, ignoreCase = true) }
    }
}
