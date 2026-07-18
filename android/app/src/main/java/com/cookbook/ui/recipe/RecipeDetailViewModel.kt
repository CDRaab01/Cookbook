package com.cookbook.ui.recipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.IngredientIn
import com.cookbook.data.remote.LogToPlateRequest
import com.cookbook.data.remote.RecipeCreateRequest
import com.cookbook.data.remote.RecipeNutritionOut
import com.cookbook.data.remote.RecipeOut
import com.cookbook.data.repository.RecipeAlreadyOnListException
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.data.repository.ShoppingRepository
import com.cookbook.ui.navigation.Screen
import com.cookbook.util.UiState
import com.cookbook.util.offlineAwareMessage
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

    /** Non-null ⇒ the shown recipe came from the offline cache, captured at this epoch-millis
     *  moment — the screen renders the "Offline — as of …" banner. Null ⇒ fresh. */
    private val _staleAsOf = MutableStateFlow<Long?>(null)
    val staleAsOf: StateFlow<Long?> = _staleAsOf

    /** One-shot: the recipe was deleted; the screen navigates back. */
    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun load() {
        viewModelScope.launch {
            if (_recipe.value !is UiState.Success) _recipe.value = UiState.Loading
            _recipe.value = try {
                val result = recipeRepository.getRecipe(recipeId)
                _staleAsOf.value = result.asOfMs
                UiState.Success(result.value)
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
                // Deleting is deliberately online-only; name the outage plainly.
                _error.value = e.offlineAwareMessage("Couldn't delete recipe")
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    // --- Favorite / duplicate / share (v0.2) ---

    fun toggleFavorite() {
        val current = (_recipe.value as? UiState.Success)?.data ?: return
        val next = !current.favorite
        // Optimistic flip; reconciled with the server response or rolled back.
        _recipe.value = UiState.Success(current.copy(favorite = next))
        viewModelScope.launch {
            try {
                _recipe.value = UiState.Success(recipeRepository.setFavorite(recipeId, next))
            } catch (e: Exception) {
                _recipe.value = UiState.Success(current)
                _error.value = e.message ?: "Couldn't update favorite"
            }
        }
    }

    /** Family mode: share this recipe with the household (or make it private). Creator only —
     *  the screen only shows the toggle when is_owner. Optimistic, reconciled or rolled back. */
    fun toggleShared() {
        val current = (_recipe.value as? UiState.Success)?.data ?: return
        val next = !current.shared
        _recipe.value = UiState.Success(current.copy(shared = next))
        viewModelScope.launch {
            try {
                _recipe.value = UiState.Success(recipeRepository.setShared(recipeId, next))
            } catch (e: Exception) {
                _recipe.value = UiState.Success(current)
                _error.value = e.message ?: "Couldn't update sharing"
            }
        }
    }

    /** One-shot after "I made this": times cooked, so the screen can offer the Plate log. */
    private val _madeIt = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val madeIt: SharedFlow<Int> = _madeIt

    fun markCooked(rating: Int? = null) {
        viewModelScope.launch {
            try {
                val result = recipeRepository.markCooked(recipeId, rating)
                val current = (_recipe.value as? UiState.Success)?.data
                if (current != null) {
                    _recipe.value = UiState.Success(
                        current.copy(
                            timesCooked = result.timesCooked,
                            lastCookedAt = result.lastCookedAt,
                            avgRating = result.avgRating,
                        ),
                    )
                }
                _madeIt.tryEmit(result.timesCooked)
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't record it"
            }
        }
    }

    fun undoCooked() {
        viewModelScope.launch {
            try {
                val result = recipeRepository.unmarkCooked(recipeId)
                val current = (_recipe.value as? UiState.Success)?.data
                if (current != null) {
                    _recipe.value = UiState.Success(
                        current.copy(
                            timesCooked = result.timesCooked,
                            lastCookedAt = result.lastCookedAt,
                        ),
                    )
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't undo"
            }
        }
    }

    /** Save the personal notes card; "" clears server-side. */
    fun saveNotes(text: String) {
        viewModelScope.launch {
            try {
                _recipe.value = UiState.Success(
                    recipeRepository.updateRecipe(
                        recipeId,
                        com.cookbook.data.remote.RecipeUpdateRequest(notes = text.trim()),
                    ),
                )
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't save notes"
            }
        }
    }

    /** One-shot: id of the duplicated recipe — the screen navigates to it. */
    private val _duplicated = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val duplicated: SharedFlow<String> = _duplicated

    fun duplicate() {
        val current = (_recipe.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            try {
                val copy = recipeRepository.createRecipe(
                    RecipeCreateRequest(
                        name = "${current.name} (copy)",
                        description = current.description,
                        servings = current.servings,
                        prepMinutes = current.prepMinutes,
                        cookMinutes = current.cookMinutes,
                        imageUrl = current.imageUrl,
                        tags = current.tags,
                        steps = current.steps.map { it.text },
                        ingredients = current.ingredients.map {
                            IngredientIn(
                                name = it.name,
                                quantity = it.quantity,
                                unit = it.unit,
                                category = it.category,
                                note = it.note,
                            )
                        },
                    ),
                )
                _duplicated.tryEmit(copy.id)
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't duplicate"
            }
        }
    }

    /** Plain-text rendering for the system share sheet; null until the recipe loads. */
    fun shareText(): String? {
        val r = (_recipe.value as? UiState.Success)?.data ?: return null
        return buildString {
            appendLine(r.name)
            if (!r.description.isNullOrBlank()) appendLine(r.description)
            appendLine()
            appendLine("Serves ${r.servings}")
            appendLine()
            appendLine("Ingredients:")
            r.ingredients.forEach { ing ->
                val qty = formatQuantity(ing.quantity, ing.unit)
                appendLine(if (qty != null) "- $qty ${ing.name}" else "- ${ing.name}")
            }
            if (r.steps.isNotEmpty()) {
                appendLine()
                appendLine("Steps:")
                r.steps.forEach { appendLine("${it.order + 1}. ${it.text}") }
            }
            appendLine()
            append("Shared from Cookbook")
        }
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
