package com.cookbook.ui.recipe

import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.HouseholdOut
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.data.remote.ShareAllOut
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.data.repository.Stale
import com.cookbook.util.AppPreferences
import com.cookbook.util.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository: RecipeRepository = mock()
    private val api: ApiService = mock()
    private lateinit var viewModel: RecipeListViewModel

    private fun summary(id: String, name: String, shared: Boolean = false) = RecipeSummaryOut(
        id = id, name = name, servings = 2, ingredientCount = 3, stepCount = 2, shared = shared,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = RecipeListViewModel(repository, api, prefs(dismissed = false))
    }

    /** Nudge prefs stub (ShoppingViewModelTest precedent — AppPreferences is mocked, not built). */
    private fun prefs(dismissed: Boolean) = mock<AppPreferences> {
        whenever(it.shareAllNudgeDismissed).thenReturn(flowOf(dismissed))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load surfaces recipes`() = runTest(dispatcher) {
        whenever(repository.listRecipes()).thenReturn(
            Stale(listOf(summary("1", "Chicken Parm"), summary("2", "Chili")), asOfMs = null),
        )

        viewModel.load()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.recipes.value
        assertIs<UiState.Success<List<RecipeSummaryOut>>>(state)
        assertEquals(2, state.data.size)
    }

    @Test
    fun `cached load surfaces the staleness stamp`() = runTest(dispatcher) {
        whenever(repository.listRecipes()).thenReturn(
            Stale(listOf(summary("1", "Chicken Parm")), asOfMs = 1_234L),
        )

        viewModel.load()
        dispatcher.scheduler.advanceUntilIdle()

        assertIs<UiState.Success<List<RecipeSummaryOut>>>(viewModel.recipes.value)
        assertEquals(1_234L, viewModel.staleAsOf.value)
    }

    @Test
    fun `load failure surfaces error`() = runTest(dispatcher) {
        whenever(repository.listRecipes()).doThrow(RuntimeException("offline"))

        viewModel.load()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.recipes.value
        assertIs<UiState.Error>(state)
        assertEquals("offline", state.message)
    }

    @Test
    fun `filtered matches name case-insensitively`() = runTest(dispatcher) {
        val list = listOf(summary("1", "Chicken Parm"), summary("2", "Chili"), summary("3", "Tacos"))

        viewModel.setQuery("chi")
        assertEquals(listOf("Chicken Parm", "Chili"), viewModel.filtered(list).map { it.name })

        viewModel.setQuery("")
        assertEquals(3, viewModel.filtered(list).size)
    }

    @Test
    fun `partitionFamily splits shared from private and preserves order`() {
        val list = listOf(
            summary("1", "Chicken Parm", shared = false),
            summary("2", "Grandma's Chili", shared = true),
            summary("3", "Tacos", shared = false),
            summary("4", "Family Lasagna", shared = true),
        )

        val (family, yours) = viewModel.partitionFamily(list)

        assertEquals(listOf("Grandma's Chili", "Family Lasagna"), family.map { it.name })
        assertEquals(listOf("Chicken Parm", "Tacos"), yours.map { it.name })
    }

    @Test
    fun `partitionFamily leaves family empty when nothing is shared`() {
        val list = listOf(summary("1", "Chicken Parm"), summary("2", "Chili"))

        val (family, yours) = viewModel.partitionFamily(list)

        assertEquals(emptyList(), family)
        assertEquals(2, yours.size)
    }

    private fun household(shared: Boolean, unshared: Int) = HouseholdOut(
        members = emptyList(),
        youAreOwner = true,
        shared = shared,
        unsharedRecipeCount = unshared,
    )

    @Test
    fun `nudge counts unshared recipes in a shared household`() = runTest(dispatcher) {
        whenever(repository.listRecipes()).thenReturn(Stale(emptyList(), null))
        whenever(api.getHousehold()).thenReturn(household(shared = true, unshared = 10))

        viewModel.load()
        testScheduler.advanceUntilIdle()

        assertEquals(10, viewModel.unsharedCount.value)
    }

    @Test
    fun `nudge stays hidden when the household is not actually shared`() = runTest(dispatcher) {
        // Solo, or invited-but-not-accepted: sharing recipes would show them to nobody.
        whenever(repository.listRecipes()).thenReturn(Stale(emptyList(), null))
        whenever(api.getHousehold()).thenReturn(household(shared = false, unshared = 10))

        viewModel.load()
        testScheduler.advanceUntilIdle()

        assertEquals(0, viewModel.unsharedCount.value)
    }

    @Test
    fun `nudge stays hidden once dismissed`() = runTest(dispatcher) {
        viewModel = RecipeListViewModel(repository, api, prefs(dismissed = true))
        whenever(repository.listRecipes()).thenReturn(Stale(emptyList(), null))
        whenever(api.getHousehold()).thenReturn(household(shared = true, unshared = 10))

        viewModel.load()
        testScheduler.advanceUntilIdle()

        assertEquals(0, viewModel.unsharedCount.value)
    }

    @Test
    fun `a failed household read just means no nudge`() = runTest(dispatcher) {
        whenever(repository.listRecipes()).thenReturn(Stale(emptyList(), null))
        whenever(api.getHousehold()).doThrow(RuntimeException("offline"))

        viewModel.load()
        testScheduler.advanceUntilIdle()

        assertEquals(0, viewModel.unsharedCount.value)
        // The book itself still loaded — the nudge is strictly additive.
        assertIs<UiState.Success<List<RecipeSummaryOut>>>(viewModel.recipes.value)
    }

    @Test
    fun `sharing all clears the nudge and reloads the book`() = runTest(dispatcher) {
        whenever(repository.listRecipes()).thenReturn(Stale(emptyList(), null))
        whenever(api.getHousehold()).thenReturn(household(shared = true, unshared = 2))
        viewModel.load()
        testScheduler.advanceUntilIdle()
        assertEquals(2, viewModel.unsharedCount.value)

        whenever(api.shareAllRecipes()).thenReturn(ShareAllOut(sharedCount = 2))
        whenever(repository.listRecipes()).thenReturn(
            Stale(listOf(summary("1", "Chili", shared = true)), null),
        )
        viewModel.shareAllRecipes()
        testScheduler.advanceUntilIdle()

        assertEquals(0, viewModel.unsharedCount.value)
        val state = viewModel.recipes.value
        assertIs<UiState.Success<List<RecipeSummaryOut>>>(state)
        assertEquals(listOf(true), state.data.map { it.shared })
    }
}
