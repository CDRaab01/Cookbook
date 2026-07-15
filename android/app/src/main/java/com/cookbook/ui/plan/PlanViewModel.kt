package com.cookbook.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.ListSummaryOut
import com.cookbook.data.remote.MEAL_SLOTS
import com.cookbook.data.remote.PlanEntryOut
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.data.repository.PlanRepository
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.data.repository.ShoppingRepository
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val planRepository: PlanRepository,
    private val recipeRepository: RecipeRepository,
    private val shoppingRepository: ShoppingRepository,
) : ViewModel() {

    private val fmt = DateTimeFormatter.ISO_LOCAL_DATE

    /** Which list's plan is shown: null = your own; a shared list's id = that household's plan. */
    private val _selectedListId = MutableStateFlow<String?>(null)
    val selectedListId: StateFlow<String?> = _selectedListId

    /** Shared lists you can plan on (for the plan-context picker); "My plan" covers your own. */
    private val _sharedLists = MutableStateFlow<List<ListSummaryOut>>(emptyList())
    val sharedLists: StateFlow<List<ListSummaryOut>> = _sharedLists

    // The Monday of the visible week — a stable anchor the UI pages by whole weeks.
    private val _weekStart = MutableStateFlow(
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
    )
    val weekStart: StateFlow<LocalDate> = _weekStart

    private val _entries = MutableStateFlow<UiState<List<PlanEntryOut>>>(UiState.Loading)
    val entries: StateFlow<UiState<List<PlanEntryOut>>> = _entries

    private val _recipes = MutableStateFlow<List<RecipeSummaryOut>>(emptyList())
    val recipes: StateFlow<List<RecipeSummaryOut>> = _recipes

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** One-shot after "Send week to list": items landed, so the screen can offer to view it. */
    private val _sentToList = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val sentToList: SharedFlow<Int> = _sentToList

    init {
        load()
        viewModelScope.launch {
            _recipes.value = runCatching { recipeRepository.listRecipes() }.getOrDefault(emptyList())
        }
        viewModelScope.launch {
            // Only shared lists are offered as plan contexts ("My plan" covers your own).
            _sharedLists.value = runCatching {
                shoppingRepository.lists().filter { it.shared }
            }.getOrDefault(emptyList())
        }
    }

    /** Switch which list's plan is shown ("My plan" = null, or a shared list's id). */
    fun selectList(listId: String?) {
        _selectedListId.value = listId
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _entries.value = try {
                val start = _weekStart.value
                UiState.Success(
                    planRepository.getPlan(
                        start.format(fmt),
                        start.plusDays(6).format(fmt),
                        _selectedListId.value,
                    ),
                )
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load the plan")
            }
        }
    }

    fun goToPreviousWeek() {
        _weekStart.value = _weekStart.value.minusWeeks(1)
        load()
    }

    fun goToNextWeek() {
        _weekStart.value = _weekStart.value.plusWeeks(1)
        load()
    }

    fun goToThisWeek() {
        _weekStart.value = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        load()
    }

    fun addRecipe(date: LocalDate, slot: String, recipeId: String) {
        require(slot in MEAL_SLOTS)
        viewModelScope.launch {
            try {
                planRepository.addEntry(date.format(fmt), slot, recipeId, null, _selectedListId.value)
                load()
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't add that to the plan"
            }
        }
    }

    fun addNote(date: LocalDate, slot: String, note: String) {
        if (note.isBlank()) return
        viewModelScope.launch {
            try {
                planRepository.addEntry(date.format(fmt), slot, null, note.trim(), _selectedListId.value)
                load()
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't add that to the plan"
            }
        }
    }

    fun setEaten(id: String, eaten: Boolean) {
        viewModelScope.launch {
            try {
                planRepository.setEaten(id, eaten)
                load()
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't update that"
            }
        }
    }

    fun removeEntry(id: String) {
        viewModelScope.launch {
            try {
                planRepository.deleteEntry(id)
                load()
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't remove that"
            }
        }
    }

    fun sendWeekToList() {
        viewModelScope.launch {
            try {
                val start = _weekStart.value
                val result = planRepository.sendToList(
                    start.format(fmt),
                    start.plusDays(6).format(fmt),
                    _selectedListId.value,
                )
                _sentToList.tryEmit(result.itemsOnList)
            } catch (e: Exception) {
                _error.value = e.message ?: "Nothing to send — plan a recipe first"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
