package com.cookbook.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.cookbook.data.remote.RecipeCreateRequest
import com.cookbook.data.remote.RecipeOut
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.util.RecipeDraftStore
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeEditViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository: RecipeRepository = mock()

    private fun newViewModel() =
        RecipeEditViewModel(repository, RecipeDraftStore(), SavedStateHandle())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `validate rejects a nameless draft`() {
        val viewModel = newViewModel()
        assertEquals("Give the recipe a name", viewModel.validate())
    }

    @Test
    fun `validate rejects a draft with no ingredients`() {
        val viewModel = newViewModel()
        viewModel.update { it.copy(name = "Chili") }
        assertEquals("Add at least one ingredient", viewModel.validate())
    }

    @Test
    fun `validate rejects junk quantities`() {
        val viewModel = newViewModel()
        viewModel.update { it.copy(name = "Chili") }
        viewModel.updateIngredient(0) { it.copy(name = "Beans", quantity = "two") }
        assertEquals("Ingredient quantities must be positive numbers", viewModel.validate())
    }

    @Test
    fun `valid draft passes`() {
        val viewModel = newViewModel()
        viewModel.update { it.copy(name = "Chili") }
        viewModel.updateIngredient(0) { it.copy(name = "Beans", quantity = "2", unit = "Cans") }
        assertNull(viewModel.validate())
    }

    @Test
    fun `save creates recipe with normalized units and dropped blank rows`() = runTest(dispatcher) {
        whenever(repository.createRecipe(any())).thenReturn(
            RecipeOut(id = "new-id", name = "Chili", servings = 4),
        )
        val viewModel = newViewModel()
        viewModel.update { it.copy(name = "Chili", servings = "4") }
        viewModel.updateIngredient(0) { it.copy(name = "Beans", quantity = "2", unit = "Cans") }
        viewModel.addIngredient() // stays blank; must be dropped
        viewModel.addStep()
        viewModel.updateStep(0, "Simmer everything.")

        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        val captor = argumentCaptor<RecipeCreateRequest>()
        verify(repository).createRecipe(captor.capture())
        val req = captor.firstValue
        assertEquals(1, req.ingredients.size)
        assertEquals("cans", req.ingredients[0].unit)
        assertEquals(listOf("Simmer everything."), req.steps)

        val state = viewModel.saveState.value
        assertIs<UiState.Success<String>>(state)
        assertEquals("new-id", state.data)
    }

    @Test
    fun `save with invalid draft errors without hitting the repository`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertIs<UiState.Error>(viewModel.saveState.value)
    }
}
