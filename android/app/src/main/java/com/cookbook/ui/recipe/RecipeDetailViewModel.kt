package com.cookbook.ui.recipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.LogToPlateRequest
import com.cookbook.data.remote.RecipeNutritionOut
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
import retrofit2.HttpException
import java.time.LocalDate
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

    // --- Nutrition via Plate (Phase 7) ---

    private val _nutrition = MutableStateFlow<UiState<RecipeNutritionOut>>(UiState.Idle)
    val nutrition: StateFlow<UiState<RecipeNutritionOut>> = _nutrition

    fun estimateNutrition() {
        viewModelScope.launch {
            _nutrition.value = UiState.Loading
            _nutrition.value = try {
                UiState.Success(recipeRepository.getRecipeNutrition(recipeId))
            } catch (e: HttpException) {
                if (e.code() == 503) {
                    UiState.Error("Plate integration isn't configured on the server")
                } else {
                    UiState.Error("Estimate failed (${e.code()})")
                }
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Estimate failed")
            }
        }
    }

    /** One-shot status line for the log-to-Plate flow. */
    private val _plateLogStatus = MutableStateFlow<String?>(null)
    val plateLogStatus: StateFlow<String?> = _plateLogStatus

    fun logToPlate(date: LocalDate, meal: String, servingsEaten: Double) {
        viewModelScope.launch {
            _plateLogStatus.value = try {
                val result = recipeRepository.logRecipeToPlate(
                    recipeId,
                    LogToPlateRequest(date.toString(), meal, servingsEaten),
                )
                when {
                    result.logged == 0 -> "Nothing loggable — Plate matched no ingredients"
                    result.skipped > 0 ->
                        "Logged ${result.logged} ingredients to Plate (${result.skipped} unmatched)"
                    else -> "Logged ${result.logged} ingredients to Plate"
                }
            } catch (e: HttpException) {
                if (e.code() == 503) {
                    "Plate integration isn't configured on the server"
                } else {
                    "Logging failed (${e.code()})"
                }
            } catch (e: Exception) {
                e.message ?: "Logging failed"
            }
        }
    }

    fun clearPlateLogStatus() {
        _plateLogStatus.value = null
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
