package com.cookbook.ui.recipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.RecipeOut
import com.cookbook.data.repository.RecipeAlreadyOnListException
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.data.repository.ShoppingRepository
import com.cookbook.ui.navigation.Screen
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecipeDetailViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val shoppingRepository: ShoppingRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val recipeId: String = checkNotNull(savedStateHandle[Screen.RecipeDetail.ARG])

    private val _recipe = MutableStateFlow<UiState<RecipeOut>>(UiState.Loading)
    val recipe: StateFlow<UiState<RecipeOut>> = _recipe

    /** One-shot: the recipe was deleted; the screen navigates back. */
    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun load() {
        viewModelScope.launch {
            if (_recipe.value !is UiState.Success) _recipe.value = UiState.Loading
            _recipe.value = try {
                UiState.Success(recipeRepository.getRecipe(recipeId))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load recipe")
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                recipeRepository.deleteRecipe(recipeId)
                _deleted.tryEmit(Unit)
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't delete recipe"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /** One-shot: ingredients landed on the list ("Added to your shopping list" snackbar). */
    private val _addedToList = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val addedToList: SharedFlow<Unit> = _addedToList

    /** The server said this recipe is already on the list; the screen asks re-add/skip. */
    private val _addConflict = MutableStateFlow(false)
    val addConflict: StateFlow<Boolean> = _addConflict

    fun addToList(scale: Double, force: Boolean = false) {
        viewModelScope.launch {
            try {
                val list = shoppingRepository.getDefaultList()
                shoppingRepository.addRecipe(list.id, recipeId, scale, force)
                _addConflict.value = false
                _addedToList.tryEmit(Unit)
            } catch (_: RecipeAlreadyOnListException) {
                _addConflict.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't add to the shopping list"
            }
        }
    }

    fun dismissAddConflict() {
        _addConflict.value = false
    }
}
