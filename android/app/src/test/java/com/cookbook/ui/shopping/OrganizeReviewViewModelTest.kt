package com.cookbook.ui.shopping

import com.cookbook.data.remote.OrganizeDraftOut
import com.cookbook.data.remote.OrganizeMove
import com.cookbook.data.remote.OrganizeSuggestion
import com.cookbook.data.remote.ShoppingListOut
import com.cookbook.data.repository.ShoppingRepository
import com.cookbook.util.OrganizeDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * The Organize review. What makes this screen necessary rather than just applying quietly: a
 * whole-list pass can propose moving something the user placed by hand, so only ticked moves are
 * ever written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrganizeReviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository: ShoppingRepository = mock()
    private lateinit var draftStore: OrganizeDraftStore
    private lateinit var viewModel: OrganizeReviewViewModel

    private fun suggestion(id: String, name: String, from: String?, to: String) =
        OrganizeSuggestion(itemId = id, name = name, currentCategory = from, suggestedCategory = to)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        draftStore = OrganizeDraftStore()
        viewModel = OrganizeReviewViewModel(repository, draftStore)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening loads the draft with everything accepted by default`() = runTest(dispatcher) {
        // Every suggestion here already survived the server-side parser, which dropped anything it
        // couldn't verify — so making the user tick each one would be busywork.
        draftStore.offer(
            OrganizeDraftOut(
                suggestions = listOf(
                    suggestion("1", "Milk", "produce", "dairy"),
                    suggestion("2", "Diapers", "household", "baby"),
                ),
            ),
        )
        viewModel.open("list-1")
        assertEquals(2, viewModel.suggestions.value.size)
        assertEquals(setOf("1", "2"), viewModel.accepted.value)
    }

    @Test
    fun `the draft is consumed so backing out does not resurrect a stale one`() =
        runTest(dispatcher) {
            draftStore.offer(OrganizeDraftOut(suggestions = listOf(suggestion("1", "Milk", null, "dairy"))))
            viewModel.open("list-1")
            assertNull(draftStore.draft.value)
        }

    @Test
    fun `only ticked moves are applied`() = runTest(dispatcher) {
        draftStore.offer(
            OrganizeDraftOut(
                suggestions = listOf(
                    suggestion("1", "Milk", "produce", "dairy"),
                    suggestion("2", "Diapers", "household", "baby"),
                ),
            ),
        )
        viewModel.open("list-1")
        viewModel.toggle("2") // the user disagrees about diapers
        whenever(repository.applyOrganize(any(), any())).thenReturn(ShoppingListOut("list-1", "G"))

        var applied = false
        viewModel.apply { applied = true }
        testScheduler.advanceUntilIdle()

        assertTrue(applied)
        verify(repository).applyOrganize("list-1", listOf(OrganizeMove("1", "dairy")))
    }

    @Test
    fun `unticking everything applies nothing and never calls the server`() = runTest(dispatcher) {
        draftStore.offer(OrganizeDraftOut(suggestions = listOf(suggestion("1", "Milk", null, "dairy"))))
        viewModel.open("list-1")
        viewModel.setAll(false)

        var applied = false
        viewModel.apply { applied = true }
        testScheduler.advanceUntilIdle()

        assertTrue(applied) // the screen still closes; the user chose "none"
        verify(repository, never()).applyOrganize(any(), any())
    }

    @Test
    fun `select all re-ticks everything`() = runTest(dispatcher) {
        draftStore.offer(
            OrganizeDraftOut(
                suggestions = listOf(
                    suggestion("1", "Milk", null, "dairy"),
                    suggestion("2", "Bread", null, "bakery"),
                ),
            ),
        )
        viewModel.open("list-1")
        viewModel.setAll(false)
        viewModel.setAll(true)
        assertEquals(setOf("1", "2"), viewModel.accepted.value)
    }

    @Test
    fun `an offline apply says so and does not close the screen`() = runTest(dispatcher) {
        draftStore.offer(OrganizeDraftOut(suggestions = listOf(suggestion("1", "Milk", null, "dairy"))))
        viewModel.open("list-1")
        // thenAnswer, not thenThrow: Mockito rejects a checked exception the signature doesn't
        // declare, and Kotlin declares none.
        whenever(repository.applyOrganize(any(), any())).thenAnswer { throw IOException("offline") }

        var applied = false
        viewModel.apply { applied = true }
        testScheduler.advanceUntilIdle()

        assertTrue(!applied)
        assertTrue(viewModel.error.value!!.contains("offline"))
    }

    @Test
    fun `opening with no draft leaves the screen empty rather than crashing`() =
        runTest(dispatcher) {
            viewModel.open("list-1")
            assertEquals(emptyList<OrganizeSuggestion>(), viewModel.suggestions.value)
        }
}
