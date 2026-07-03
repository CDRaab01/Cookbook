package com.cookbook.ui.pantry

import com.cookbook.data.remote.CookbookSuggestion
import com.cookbook.data.remote.PantrySuggestionsOut
import com.cookbook.data.repository.PantryRepository
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.util.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class PantrySuggestionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val pantryRepository: PantryRepository = mock()
    private val recipeRepository: RecipeRepository = mock()
    private lateinit var viewModel: PantrySuggestionsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = PantrySuggestionsViewModel(pantryRepository, recipeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load lands the merged payload`() = runTest(dispatcher) {
        val payload = PantrySuggestionsOut(
            cookbook = listOf(
                CookbookSuggestion(
                    recipeId = "r1",
                    name = "Carbonara",
                    total = 4,
                    matched = 3,
                    missing = listOf("cream"),
                ),
            ),
            external = emptyList(),
            externalAvailable = false,
        )
        whenever(pantryRepository.getSuggestions(any())).thenReturn(payload)

        viewModel.load()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.suggestions.value
        assertIs<UiState.Success<PantrySuggestionsOut>>(state)
        assertEquals("Carbonara", state.data.cookbook.single().name)
        assertEquals(false, state.data.externalAvailable)
    }

    @Test
    fun `load failure lands in Error`() = runTest(dispatcher) {
        whenever(pantryRepository.getSuggestions(any())).doThrow(RuntimeException("down"))

        viewModel.load()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.suggestions.value
        assertIs<UiState.Error>(state)
        assertEquals("down", state.message)
    }

    @Test
    fun `import fires the one-shot with the new recipe id`() = runTest(dispatcher) {
        val imported = com.cookbook.data.remote.RecipeOut(
            id = "new-recipe",
            name = "Web pasta bake",
            servings = 4,
        )
        whenever(recipeRepository.importRecipe(any())).thenReturn(imported)

        var landed: String? = null
        val collector = launch { landed = viewModel.imported.first() }
        viewModel.import("777")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("new-recipe", landed)
        collector.cancel()
    }
}
