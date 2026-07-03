package com.cookbook.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.data.repository.PlanRepository
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.data.repository.ShoppingRepository
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/** Everything the Home dashboard shows in one shot. */
data class HomeData(
    val userName: String?,
    val recipeCount: Int,
    val recentRecipes: List<RecipeSummaryOut>,
    val uncheckedItems: Int,
    val plannedThisWeek: Int,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val shoppingRepository: ShoppingRepository,
    private val planRepository: PlanRepository,
    private val api: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<HomeData>>(UiState.Loading)
    val state: StateFlow<UiState<HomeData>> = _state

    private val _greeting = MutableStateFlow(timeGreeting())
    val greeting: StateFlow<String> = _greeting

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _greeting.value = timeGreeting()
            // Only spin when nothing is on screen yet (Spotter/Plate convention) so resume doesn't flash.
            if (_state.value !is UiState.Success) _state.value = UiState.Loading
            _state.value = try {
                // The recipe list gates success; the rest are best-effort so one slow/failed call
                // doesn't blank the whole dashboard.
                val recipes = recipeRepository.listRecipes()
                val name = runCatching { api.getMe().name }.getOrNull()
                val unchecked = runCatching {
                    shoppingRepository.getDefaultList().items.count { !it.checked }
                }.getOrDefault(0)
                val planned = runCatching {
                    val (start, end) = weekRange()
                    planRepository.getPlan(start, end).size
                }.getOrDefault(0)
                UiState.Success(
                    HomeData(
                        userName = name,
                        recipeCount = recipes.size,
                        recentRecipes = recipes.sortedByDescending { it.createdAt }.take(4),
                        uncheckedItems = unchecked,
                        plannedThisWeek = planned,
                    ),
                )
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load your kitchen")
            }
        }
    }
}

private fun timeGreeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

/** Monday–Sunday of the current week, as ISO date strings for the /plan range. */
private fun weekRange(): Pair<String, String> {
    val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return ISO.format(monday) to ISO.format(monday.plusDays(6))
}
