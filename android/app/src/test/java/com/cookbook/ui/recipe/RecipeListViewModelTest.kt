package com.cookbook.ui.recipe

import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.util.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private lateinit var viewModel: RecipeListViewModel

    private fun summary(id: String, name: String, shared: Boolean = false) = RecipeSummaryOut(
        id = id, name = name, servings = 2, ingredientCount = 3, stepCount = 2, shared = shared,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = RecipeListViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load surfaces recipes`() = runTest(dispatcher) {
        whenever(repository.listRecipes()).thenReturn(
            listOf(summary("1", "Chicken Parm"), summary("2", "Chili")),
        )

        viewModel.load()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.recipes.value
        assertIs<UiState.Success<List<RecipeSummaryOut>>>(state)
        assertEquals(2, state.data.size)
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
}
