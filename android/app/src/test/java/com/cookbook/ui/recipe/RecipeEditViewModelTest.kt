package com.cookbook.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.cookbook.data.remote.IngredientOut
import com.cookbook.data.remote.RecipeCreateRequest
import com.cookbook.data.remote.RecipeOut
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.data.repository.Stale
import com.cookbook.ui.navigation.Screen
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
    fun `moveStep reorders steps and clamps at the ends`() {
        val viewModel = newViewModel()
        viewModel.updateStep(0, "one")
        viewModel.addStep(); viewModel.updateStep(1, "two")
        viewModel.addStep(); viewModel.updateStep(2, "three")

        viewModel.moveStep(2, -1) // three moves up past two
        assertEquals(listOf("one", "three", "two"), viewModel.draft.value.steps)

        viewModel.moveStep(0, -1) // already first — no-op
        assertEquals(listOf("one", "three", "two"), viewModel.draft.value.steps)

        viewModel.moveStep(2, 1) // already last — no-op
        assertEquals(listOf("one", "three", "two"), viewModel.draft.value.steps)

        viewModel.moveStep(0, 1) // one moves down
        assertEquals(listOf("three", "one", "two"), viewModel.draft.value.steps)
    }

    @Test
    fun `save with invalid draft errors without hitting the repository`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertIs<UiState.Error>(viewModel.saveState.value)
    }

    // ── Ingredient sections ──────────────────────────────────────────────────
    //
    // Sections are stored denormalized on the wire (one value per ingredient) but edited as a
    // marker on the row that STARTS each run. These cover the fold in both directions plus the
    // two failure modes the marker model exists to prevent.

    @Test
    fun `save folds section markers onto every ingredient in the run`() = runTest(dispatcher) {
        whenever(repository.createRecipe(any())).thenReturn(
            RecipeOut(id = "new-id", name = "Fajitas", servings = 4),
        )
        val viewModel = newViewModel()
        viewModel.update { it.copy(name = "Fajitas", servings = "4") }
        viewModel.updateIngredient(0) { it.copy(name = "Lime juice", sectionHeader = "Marinade") }
        viewModel.addIngredient()
        viewModel.updateIngredient(1) { it.copy(name = "Cumin") }
        viewModel.addSection()
        viewModel.setSectionHeader(2, "Fajitas")
        viewModel.updateIngredient(2) { it.copy(name = "Skirt steak") }

        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        val captor = argumentCaptor<RecipeCreateRequest>()
        verify(repository).createRecipe(captor.capture())
        assertEquals(
            listOf("Marinade", "Marinade", "Fajitas"),
            captor.firstValue.ingredients.map { it.section },
        )
    }

    @Test
    fun `two runs with the same name stay separate while typing`() {
        // The failure the marker model exists to prevent: with sections derived by comparing
        // values, finishing the word "Sauce" in the second heading would silently merge the
        // runs and yank the field out from under the cursor.
        val viewModel = newViewModel()
        viewModel.updateIngredient(0) { it.copy(name = "Mayo", sectionHeader = "Sauce") }
        viewModel.addSection()
        viewModel.setSectionHeader(1, "Sauce")
        viewModel.updateIngredient(1) { it.copy(name = "Sriracha") }

        assertEquals(listOf("Sauce", "Sauce"), viewModel.draft.value.ingredients.map { it.sectionHeader })
    }

    @Test
    fun `an empty heading is a real state, not a collapse`() {
        // Clearing the text must leave the field there so it can be renamed; only the explicit
        // remove (null) merges the run into the one above.
        val viewModel = newViewModel()
        viewModel.updateIngredient(0) { it.copy(name = "Mayo", sectionHeader = "Sauce") }
        viewModel.setSectionHeader(0, "")
        assertEquals("", viewModel.draft.value.ingredients[0].sectionHeader)

        viewModel.setSectionHeader(0, null)
        assertNull(viewModel.draft.value.ingredients[0].sectionHeader)
    }

    @Test
    fun `an unnamed heading contributes no section on save`() = runTest(dispatcher) {
        whenever(repository.createRecipe(any())).thenReturn(
            RecipeOut(id = "new-id", name = "Chili", servings = 4),
        )
        val viewModel = newViewModel()
        viewModel.update { it.copy(name = "Chili", servings = "4") }
        viewModel.updateIngredient(0) { it.copy(name = "Beans", sectionHeader = "   ") }

        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        val captor = argumentCaptor<RecipeCreateRequest>()
        verify(repository).createRecipe(captor.capture())
        assertNull(captor.firstValue.ingredients[0].section)
    }

    @Test
    fun `deleting the row that starts a run hands its heading to the next row`() {
        val viewModel = newViewModel()
        viewModel.updateIngredient(0) { it.copy(name = "Mayo", sectionHeader = "Sauce") }
        viewModel.addIngredient()
        viewModel.updateIngredient(1) { it.copy(name = "Sriracha") }

        viewModel.removeIngredient(0)

        val remaining = viewModel.draft.value.ingredients
        assertEquals(1, remaining.size)
        assertEquals("Sriracha", remaining[0].name)
        // Without the hand-down, deleting the first ingredient would delete the section too.
        assertEquals("Sauce", remaining[0].sectionHeader)
    }

    @Test
    fun `moveIngredient reorders rows but leaves headings pinned`() {
        val viewModel = newViewModel()
        viewModel.updateIngredient(0) { it.copy(name = "Mayo", sectionHeader = "Sauce") }
        viewModel.addIngredient()
        viewModel.updateIngredient(1) { it.copy(name = "Sriracha") }

        viewModel.moveIngredient(0, 1)

        val rows = viewModel.draft.value.ingredients
        assertEquals(listOf("Sriracha", "Mayo"), rows.map { it.name })
        // The heading marks a position in the list, not a particular ingredient.
        assertEquals(listOf("Sauce", null), rows.map { it.sectionHeader })

        viewModel.moveIngredient(0, -1) // already first — no-op
        assertEquals(listOf("Sriracha", "Mayo"), viewModel.draft.value.ingredients.map { it.name })
    }

    @Test
    fun `addIngredient joins the run in progress`() = runTest(dispatcher) {
        whenever(repository.createRecipe(any())).thenReturn(
            RecipeOut(id = "new-id", name = "Fajitas", servings = 4),
        )
        val viewModel = newViewModel()
        viewModel.update { it.copy(name = "Fajitas", servings = "4") }
        viewModel.updateIngredient(0) { it.copy(name = "Lime juice", sectionHeader = "Marinade") }
        viewModel.addIngredient() // no marker of its own — typing straight down keeps working
        viewModel.updateIngredient(1) { it.copy(name = "Cumin") }

        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        val captor = argumentCaptor<RecipeCreateRequest>()
        verify(repository).createRecipe(captor.capture())
        assertEquals(listOf("Marinade", "Marinade"), captor.firstValue.ingredients.map { it.section })
    }

    @Test
    fun `loading an existing recipe folds sections back into markers`() = runTest(dispatcher) {
        whenever(repository.getRecipe("r1")).thenReturn(
            Stale(
                RecipeOut(
                    id = "r1",
                    name = "Fajitas",
                    servings = 6,
                    ingredients = listOf(
                        IngredientOut(id = "1", order = 0, name = "Lime juice", section = "Marinade"),
                        IngredientOut(id = "2", order = 1, name = "Cumin", section = "Marinade"),
                        IngredientOut(id = "3", order = 2, name = "Steak", section = "Fajitas"),
                        IngredientOut(id = "4", order = 3, name = "Tortillas", section = null),
                    ),
                ),
                asOfMs = null,
            ),
        )
        val viewModel = RecipeEditViewModel(
            repository,
            RecipeDraftStore(),
            SavedStateHandle(mapOf(Screen.RecipeEdit.ARG to "r1")),
        )
        dispatcher.scheduler.advanceUntilIdle()

        // A marker only where the section changes — and an explicit empty one where the recipe
        // returns to "no section", or those rows would render under the heading above.
        assertEquals(
            listOf("Marinade", null, "Fajitas", ""),
            viewModel.draft.value.ingredients.map { it.sectionHeader },
        )
    }
}
