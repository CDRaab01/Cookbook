package com.cookbook.ui.stores

import com.cookbook.data.remote.AisleIn
import com.cookbook.data.remote.StoreAisleOut
import com.cookbook.data.remote.StoreDetailOut
import com.cookbook.data.remote.StoreLayoutDraftOut
import com.cookbook.data.repository.StoreRepository
import com.cookbook.util.StoreLayoutDraft
import com.cookbook.util.StoreLayoutDraftStore
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

@OptIn(ExperimentalCoroutinesApi::class)
class StoreEditViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var draftStore: StoreLayoutDraftStore
    private lateinit var repository: FakeStoreRepository
    private lateinit var viewModel: StoreEditViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        draftStore = StoreLayoutDraftStore()
        repository = FakeStoreRepository()
        viewModel = StoreEditViewModel(repository, draftStore)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- the suggested-layout draft ---

    @Test
    fun `a suggested layout prefills the editor and saves nothing until Save`() = runTest(dispatcher) {
        draftStore.offer(
            StoreLayoutDraft(
                chain = "Meijer",
                label = "Maysville Rd",
                aisles = listOf(
                    AisleIn(null, "Produce", listOf("produce")),
                    AisleIn(null, "Aisle 5", listOf("pantry")),
                ),
                lowConfidence = false,
                note = "A rough guess",
            ),
        )
        viewModel.open(null)

        assertEquals(listOf("Produce", "Aisle 5"), viewModel.rows.value.map { it.name })
        assertEquals("Meijer — Maysville Rd", viewModel.title.value)
        assertEquals("A rough guess", viewModel.note.value)
        // The house rule: a draft is a draft.
        assertTrue(repository.created.isEmpty())

        var saved = false
        viewModel.save { saved = true }
        testScheduler.advanceUntilIdle()
        assertTrue(saved)
        assertEquals(1, repository.created.size)
        assertEquals("Meijer" to "Maysville Rd", repository.created[0].first)
        assertEquals(listOf("Produce", "Aisle 5"), repository.created[0].second.map { it.name })
    }

    @Test
    fun `a draft with no aisles seeds the standard order rather than a blank screen`() =
        runTest(dispatcher) {
            // The model was unreachable or unreadable. The user asked to set up a store; a list to
            // drag around beats an empty page and an error.
            draftStore.offer(StoreLayoutDraft("Zorbnax", null, emptyList(), true, "no luck"))
            viewModel.open(null)
            assertEquals(defaultAisleRows().map { it.name }, viewModel.rows.value.map { it.name })
        }

    @Test
    fun `the draft is consumed so backing out and returning does not resurrect it`() =
        runTest(dispatcher) {
            draftStore.offer(StoreLayoutDraft("Meijer", null, emptyList(), false, null))
            viewModel.open(null)
            assertNull(draftStore.draft.value)
        }

    // --- editing an existing store ---

    @Test
    fun `opening an existing store loads its aisles in walk order and keeps their ids`() =
        runTest(dispatcher) {
            repository.detail = StoreDetailOut(
                id = "s1",
                name = "Meijer",
                aisles = listOf(
                    StoreAisleOut("a2", 1, "Second", listOf("pantry")),
                    StoreAisleOut("a1", 0, "First", listOf("produce")),
                ),
            )
            viewModel.open("s1")
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("First", "Second"), viewModel.rows.value.map { it.name })
            assertEquals(listOf("a1", "a2"), viewModel.rows.value.map { it.id })
        }

    @Test
    fun `saving an existing store carries the ids so placements survive a reorder`() =
        runTest(dispatcher) {
            repository.detail = StoreDetailOut(
                id = "s1",
                name = "Meijer",
                aisles = listOf(
                    StoreAisleOut("a1", 0, "Produce", listOf("produce")),
                    StoreAisleOut("a2", 1, "Pantry", listOf("pantry")),
                ),
            )
            viewModel.open("s1")
            testScheduler.advanceUntilIdle()
            viewModel.move(1, -1)
            viewModel.rename(0, "Aisle 5 — Pantry")
            viewModel.save {}
            testScheduler.advanceUntilIdle()

            val (storeId, aisles) = repository.aislesPut.single()
            assertEquals("s1", storeId)
            // Ids carried through: the server updates in place instead of deleting and recreating,
            // which is what keeps every learned placement.
            assertEquals(listOf("a2", "a1"), aisles.map { it.id })
            assertEquals("Aisle 5 — Pantry", aisles[0].name)
        }

    // --- category assignment ---

    @Test
    fun `assigning a category takes it off the aisle that had it`() = runTest(dispatcher) {
        draftStore.offer(
            StoreLayoutDraft(
                "Meijer", null,
                listOf(
                    AisleIn(null, "Front", listOf("dairy")),
                    AisleIn(null, "Back", emptyList()),
                ),
                false, null,
            ),
        )
        viewModel.open(null)
        viewModel.assignCategory(1, "dairy")
        assertEquals(emptyList<String>(), viewModel.rows.value[0].categories)
        assertEquals(listOf("dairy"), viewModel.rows.value[1].categories)
    }

    @Test
    fun `unassigning leaves the category unclaimed`() = runTest(dispatcher) {
        draftStore.offer(
            StoreLayoutDraft("Meijer", null, listOf(AisleIn(null, "Front", listOf("dairy"))), false, null),
        )
        viewModel.open(null)
        viewModel.unassignCategory(0, "dairy")
        assertTrue("dairy" in unclaimedCategories(viewModel.rows.value))
    }

    @Test
    fun `reset keeps ids so it reorders rather than wiping every placement`() = runTest(dispatcher) {
        repository.detail = StoreDetailOut(
            id = "s1",
            name = "Meijer",
            aisles = listOf(StoreAisleOut("a1", 0, "Fresh stuff", listOf("produce"))),
        )
        viewModel.open("s1")
        testScheduler.advanceUntilIdle()
        viewModel.resetToDefaults()
        val produce = viewModel.rows.value.first { it.categories == listOf("produce") }
        assertEquals("a1", produce.id)
    }

    // --- guards ---

    @Test
    fun `saving with no aisles is refused`() = runTest(dispatcher) {
        draftStore.offer(StoreLayoutDraft("Meijer", null, listOf(AisleIn(null, "X", emptyList())), false, null))
        viewModel.open(null)
        viewModel.removeAisle(0)
        var saved = false
        viewModel.save { saved = true }
        testScheduler.advanceUntilIdle()
        assertTrue(!saved)
        assertTrue(repository.created.isEmpty())
        assertTrue(viewModel.error.value!!.contains("at least one aisle"))
    }

    @Test
    fun `move is bounds-checked`() = runTest(dispatcher) {
        draftStore.offer(
            StoreLayoutDraft(
                "Meijer", null,
                listOf(AisleIn(null, "A", emptyList()), AisleIn(null, "B", emptyList())),
                false, null,
            ),
        )
        viewModel.open(null)
        viewModel.move(0, -1)
        viewModel.move(1, 1)
        assertEquals(listOf("A", "B"), viewModel.rows.value.map { it.name })
    }
}

private class FakeStoreRepository : StoreRepository {
    var detail: StoreDetailOut? = null
    val created = mutableListOf<Pair<Pair<String, String?>, List<AisleIn>>>()
    val aislesPut = mutableListOf<Pair<String, List<AisleIn>>>()

    override suspend fun stores() = emptyList<com.cookbook.data.remote.StoreOut>()
    override suspend fun store(storeId: String) = detail
    override suspend fun create(name: String, label: String?, aisles: List<AisleIn>?): StoreDetailOut {
        created += (name to label) to (aisles ?: emptyList())
        return StoreDetailOut(id = "new", name = name, label = label)
    }
    override suspend fun rename(storeId: String, name: String?, label: String?) = detail!!
    override suspend fun delete(storeId: String) {}
    override suspend fun putAisles(storeId: String, aisles: List<AisleIn>): StoreDetailOut {
        aislesPut += storeId to aisles
        return detail!!
    }
    override suspend fun suggestLayout(chain: String) = StoreLayoutDraftOut()
    override suspend fun placeItem(storeId: String, itemName: String, aisleId: String) {}
    override suspend fun removePlacement(storeId: String, placementId: String) {}
    override suspend fun syncPendingPlacements() {}
}
