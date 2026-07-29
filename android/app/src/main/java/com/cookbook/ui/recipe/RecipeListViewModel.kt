package com.cookbook.ui.recipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.util.AppPreferences
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RecipeSort(val label: String) {
    Name("Name"),
    Newest("Newest"),
    Quickest("Quickest"),
    LeastRecentlyCooked("Haven't made lately"),
}

@HiltViewModel
class RecipeListViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val api: ApiService,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _recipes = MutableStateFlow<UiState<List<RecipeSummaryOut>>>(UiState.Loading)
    val recipes: StateFlow<UiState<List<RecipeSummaryOut>>> = _recipes

    /** Non-null ⇒ the loaded book came from the offline cache, captured at this epoch-millis
     *  moment — the screen renders the "Offline — as of …" banner. Null ⇒ fresh. */
    private val _staleAsOf = MutableStateFlow<Long?>(null)
    val staleAsOf: StateFlow<Long?> = _staleAsOf

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _sort = MutableStateFlow(RecipeSort.Name)
    val sort: StateFlow<RecipeSort> = _sort

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag

    /**
     * How many of your recipes are still private while you're in an actually-shared household —
     * 0 hides the prompt. Joining a household shares the lists and plans but leaves every recipe
     * private until its creator opts in, and nothing on this screen said so.
     */
    private val _unsharedCount = MutableStateFlow(0)
    val unsharedCount: StateFlow<Int> = _unsharedCount

    private val _sharingAll = MutableStateFlow(false)
    val sharingAll: StateFlow<Boolean> = _sharingAll

    fun load() {
        viewModelScope.launch {
            // Only show the spinner when nothing is on screen yet (Spotter convention) so a
            // resume-refresh doesn't flash.
            if (_recipes.value !is UiState.Success) _recipes.value = UiState.Loading
            _recipes.value = try {
                val result = recipeRepository.listRecipes()
                _staleAsOf.value = result.asOfMs
                UiState.Success(result.value)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load recipes")
            }
            refreshNudge()
        }
    }

    /** Best-effort, like the other household reads — offline or an older server just means no prompt. */
    private suspend fun refreshNudge() {
        if (appPreferences.shareAllNudgeDismissed.first()) {
            _unsharedCount.value = 0
            return
        }
        val household = runCatching { api.getHousehold() }.getOrNull()
        _unsharedCount.value =
            if (household != null && household.shared) household.unsharedRecipeCount else 0
    }

    /** Bulk opt-in from the prompt. Yours only — server-enforced. Reloads so the list re-splits. */
    fun shareAllRecipes() {
        if (_sharingAll.value) return
        viewModelScope.launch {
            _sharingAll.value = true
            try {
                api.shareAllRecipes()
                _unsharedCount.value = 0
                val result = runCatching { recipeRepository.listRecipes() }.getOrNull()
                if (result != null) {
                    _staleAsOf.value = result.asOfMs
                    _recipes.value = UiState.Success(result.value)
                }
            } catch (_: Exception) {
                // Leave the prompt up; the Settings → Family action reports errors properly.
            } finally {
                _sharingAll.value = false
            }
        }
    }

    /** "Not now" — never ask again on this device; the action stays in Settings → Family. */
    fun dismissNudge() {
        _unsharedCount.value = 0
        viewModelScope.launch { appPreferences.dismissShareAllNudge() }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setSort(value: RecipeSort) {
        _sort.value = value
    }

    fun toggleFavoritesOnly() {
        _favoritesOnly.value = !_favoritesOnly.value
    }

    fun selectTag(tag: String?) {
        _selectedTag.value = if (_selectedTag.value == tag) null else tag
    }

    /** All tags present in the loaded list, most-used first — the filter chip row. */
    fun availableTags(list: List<RecipeSummaryOut>): List<String> =
        list.flatMap { it.tags }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }

    /**
     * Split the (already filtered + sorted) recipes into the two Family-mode sections:
     * **Family** (shared == true — recipes shared across the household, from anyone) and **Yours**
     * (shared == false — your private recipes). Order within each section is preserved, so the
     * active sort/filter still holds. Pure — the screen renders the two lists under their headers.
     */
    fun partitionFamily(list: List<RecipeSummaryOut>): Pair<List<RecipeSummaryOut>, List<RecipeSummaryOut>> =
        list.partition { it.shared }

    /** The loaded list with search + favorites + tag filters and the chosen sort applied. */
    fun filtered(list: List<RecipeSummaryOut>): List<RecipeSummaryOut> {
        val q = _query.value.trim()
        val tag = _selectedTag.value
        var out = list
        if (q.isNotEmpty()) out = out.filter { it.name.contains(q, ignoreCase = true) }
        if (_favoritesOnly.value) out = out.filter { it.favorite }
        if (tag != null) out = out.filter { tag in it.tags }
        return when (_sort.value) {
            RecipeSort.Name -> out.sortedBy { it.name.lowercase() }
            // createdAt is ISO-8601, so lexicographic order is chronological.
            RecipeSort.Newest -> out.sortedByDescending { it.createdAt }
            RecipeSort.Quickest -> out.sortedBy {
                val total = (it.prepMinutes ?: 0) + (it.cookMinutes ?: 0)
                if (total > 0) total else Int.MAX_VALUE
            }
            // Never-cooked first, then longest-ago — "what should I make again?".
            RecipeSort.LeastRecentlyCooked -> out.sortedBy { it.lastCookedAt ?: "" }
        }
    }
}
