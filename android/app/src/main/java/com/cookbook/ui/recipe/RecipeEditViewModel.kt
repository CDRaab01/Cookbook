package com.cookbook.ui.recipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.IngredientIn
import com.cookbook.data.remote.RecipeCreateRequest
import com.cookbook.data.remote.RecipeUpdateRequest
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.ui.navigation.Screen
import com.cookbook.util.RecipeDraftStore
import com.cookbook.util.UiState
import com.cookbook.util.offlineAwareMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One editable ingredient row. Quantity is kept as raw text until save so typing "1." works. */
data class IngredientDraft(
    val name: String = "",
    val quantity: String = "",
    val unit: String = "",
    val category: String? = null,
    val note: String = "",
)

data class RecipeDraft(
    val name: String = "",
    val description: String = "",
    val servings: String = "1",
    val prepMinutes: String = "",
    val cookMinutes: String = "",
    val imageUrl: String = "",
    val tags: List<String> = emptyList(),
    val ingredients: List<IngredientDraft> = listOf(IngredientDraft()),
    val steps: List<String> = listOf(""),
)

@HiltViewModel
class RecipeEditViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val recipeDraftStore: RecipeDraftStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Null ⇒ creating a new recipe; non-null ⇒ editing that one. */
    val recipeId: String? = savedStateHandle[Screen.RecipeEdit.ARG]

    private val _draft = MutableStateFlow(RecipeDraft())
    val draft: StateFlow<RecipeDraft> = _draft

    private val _saveState = MutableStateFlow<UiState<String>>(UiState.Idle)
    /** Success carries the saved recipe's id so the screen can navigate to its detail. */
    val saveState: StateFlow<UiState<String>> = _saveState

    private val _loading = MutableStateFlow(recipeId != null)
    val loading: StateFlow<Boolean> = _loading

    /** Set when this editor opened pre-filled from a photo-import draft the model wasn't
     * fully confident about — the screen surfaces it as a one-time banner. */
    private val _photoNote = MutableStateFlow<String?>(null)
    val photoNote: StateFlow<String?> = _photoNote

    init {
        if (recipeId == null) {
            recipeDraftStore.consume()?.let { photo ->
                _draft.value = RecipeDraft(
                    name = photo.name,
                    servings = photo.servings?.toString() ?: "1",
                    prepMinutes = photo.prepMinutes?.toString().orEmpty(),
                    cookMinutes = photo.cookMinutes?.toString().orEmpty(),
                    ingredients = photo.ingredients.map {
                        IngredientDraft(
                            name = it.name,
                            quantity = it.quantity?.let { q ->
                                if (q % 1.0 == 0.0) q.toInt().toString() else q.toString()
                            }.orEmpty(),
                            unit = it.unit.orEmpty(),
                        )
                    }.ifEmpty { listOf(IngredientDraft()) },
                    steps = photo.steps.ifEmpty { listOf("") },
                )
                if (photo.lowConfidence) _photoNote.value = photo.note
            }
        }
        if (recipeId != null) {
            viewModelScope.launch {
                try {
                    val recipe = recipeRepository.getRecipe(recipeId).value
                    _draft.value = RecipeDraft(
                        name = recipe.name,
                        description = recipe.description.orEmpty(),
                        servings = recipe.servings.toString(),
                        prepMinutes = recipe.prepMinutes?.toString().orEmpty(),
                        cookMinutes = recipe.cookMinutes?.toString().orEmpty(),
                        imageUrl = recipe.imageUrl.orEmpty(),
                        tags = recipe.tags,
                        ingredients = recipe.ingredients.map {
                            IngredientDraft(
                                name = it.name,
                                quantity = it.quantity?.let { q ->
                                    if (q % 1.0 == 0.0) q.toInt().toString() else q.toString()
                                }.orEmpty(),
                                unit = it.unit.orEmpty(),
                                category = it.category,
                                note = it.note.orEmpty(),
                            )
                        }.ifEmpty { listOf(IngredientDraft()) },
                        steps = recipe.steps.map { it.text }.ifEmpty { listOf("") },
                    )
                } catch (e: Exception) {
                    _saveState.value = UiState.Error(e.offlineAwareMessage("Couldn't load recipe"))
                } finally {
                    _loading.value = false
                }
            }
        }
    }

    fun update(transform: (RecipeDraft) -> RecipeDraft) {
        _draft.value = transform(_draft.value)
    }

    fun addIngredient() = update { it.copy(ingredients = it.ingredients + IngredientDraft()) }

    fun removeIngredient(index: Int) = update {
        it.copy(ingredients = it.ingredients.filterIndexed { i, _ -> i != index })
    }

    fun updateIngredient(index: Int, transform: (IngredientDraft) -> IngredientDraft) = update {
        it.copy(
            ingredients = it.ingredients.mapIndexed { i, ing ->
                if (i == index) transform(ing) else ing
            },
        )
    }

    fun addTag(raw: String) = update {
        val tag = raw.trim().lowercase()
        if (tag.isEmpty() || tag in it.tags || it.tags.size >= 10) it
        else it.copy(tags = it.tags + tag)
    }

    fun removeTag(tag: String) = update { it.copy(tags = it.tags - tag) }

    fun addStep() = update { it.copy(steps = it.steps + "") }

    fun removeStep(index: Int) = update {
        it.copy(steps = it.steps.filterIndexed { i, _ -> i != index })
    }

    fun updateStep(index: Int, text: String) = update {
        it.copy(steps = it.steps.mapIndexed { i, s -> if (i == index) text else s })
    }

    /** Move a step one slot toward `index + delta` (delta ±1); no-op if it would fall off an end.
     * The server renumbers `order` from list position on save, so reordering here is enough. */
    fun moveStep(index: Int, delta: Int) = update {
        val target = index + delta
        if (index !in it.steps.indices || target !in it.steps.indices) return@update it
        val reordered = it.steps.toMutableList()
        reordered.add(target, reordered.removeAt(index))
        it.copy(steps = reordered)
    }

    /** Client-side validation mirror of the server rules; returns null when the draft is savable. */
    fun validate(d: RecipeDraft = _draft.value): String? {
        if (d.name.isBlank()) return "Give the recipe a name"
        if (d.servings.toIntOrNull()?.takeIf { it in 1..100 } == null) {
            return "Servings must be between 1 and 100"
        }
        if (d.ingredients.none { it.name.isNotBlank() }) return "Add at least one ingredient"
        val badQty = d.ingredients.any {
            it.name.isNotBlank() && it.quantity.isNotBlank() &&
                (it.quantity.toDoubleOrNull()?.takeIf { q -> q > 0 } == null)
        }
        if (badQty) return "Ingredient quantities must be positive numbers"
        return null
    }

    fun save() {
        val d = _draft.value
        val validationError = validate(d)
        if (validationError != null) {
            _saveState.value = UiState.Error(validationError)
            return
        }
        val ingredients = d.ingredients
            .filter { it.name.isNotBlank() }
            .map {
                IngredientIn(
                    name = it.name.trim(),
                    quantity = it.quantity.toDoubleOrNull(),
                    unit = it.unit.trim().lowercase().ifEmpty { null },
                    category = it.category,
                    note = it.note.trim().ifEmpty { null },
                )
            }
        val steps = d.steps.map { it.trim() }.filter { it.isNotEmpty() }

        viewModelScope.launch {
            _saveState.value = UiState.Loading
            _saveState.value = try {
                val saved = if (recipeId == null) {
                    recipeRepository.createRecipe(
                        RecipeCreateRequest(
                            name = d.name.trim(),
                            description = d.description.trim().ifEmpty { null },
                            servings = d.servings.toInt(),
                            prepMinutes = d.prepMinutes.toIntOrNull(),
                            cookMinutes = d.cookMinutes.toIntOrNull(),
                            imageUrl = d.imageUrl.trim().ifEmpty { null },
                            tags = d.tags.ifEmpty { null },
                            steps = steps,
                            ingredients = ingredients,
                        ),
                    )
                } else {
                    recipeRepository.updateRecipe(
                        recipeId,
                        RecipeUpdateRequest(
                            name = d.name.trim(),
                            // PATCH null means "leave untouched", so cleared fields need their
                            // clear sentinels: "" for text, 0 for minutes.
                            description = d.description.trim(),
                            servings = d.servings.toInt(),
                            prepMinutes = d.prepMinutes.toIntOrNull() ?: 0,
                            cookMinutes = d.cookMinutes.toIntOrNull() ?: 0,
                            imageUrl = d.imageUrl.trim(),
                            tags = d.tags, // replaces wholesale; empty list clears
                            steps = steps,
                            ingredients = ingredients,
                        ),
                    )
                }
                UiState.Success(saved.id)
            } catch (e: Exception) {
                // Recipe editing is deliberately online-only; name the outage instead of a
                // raw socket message.
                UiState.Error(e.offlineAwareMessage("Couldn't save recipe"))
            }
        }
    }

    fun clearSaveError() {
        if (_saveState.value is UiState.Error) _saveState.value = UiState.Idle
    }

    fun clearPhotoNote() {
        _photoNote.value = null
    }
}
